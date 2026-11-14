package com.ufi_axis_core.api.update

import com.ufi_axis_core.api.geo.GeoDetector
import com.ufi_axis_core.util.AppLogger
import com.ufi_axis_core.util.AppSettings
import java.net.URL

/**
 * 更新下载源解析：**core 是唯一决策方**（2026-09-22，见 `docs/update-source-core-plan.md`）。
 *
 * 在这之前，「用不用镜像」这件事有一份半实现：app 里有完整的模式 + 地区判断，core 只看
 * `update_mirror_base` 空不空，于是 app 上的「自动」到了 core 就失真成「强制镜像」——
 * 海外设备被迫走 gh-proxy。而且同样的 `applyMirrorToUrl` 在 core 里抄了三份
 * （UpdateManager / WebUpdateManager / ComponentManager），core 自己又没有失败换源能力。
 * 这个类把三份收成一份，并把决策从「一个布尔」升级成「候选列表」。
 *
 * 决策规则（唯一真源）：
 * - [AppSettings.updateSourceMode] 是唯一决策字段：`auto` / `mirror` / `direct`
 * - [AppSettings.updateMirrorBase] 降级为「自定义前缀覆盖」：非空且不在 [BUILTIN_MIRROR_PREFIXES]
 *   里时，作为候选列表的**第一个**（老配置里填过的自定义前缀因此不会失效）
 * - `auto` → 出网地区为 `CN` 时同 `mirror`；其他地区直连；**地区未知也直连**
 *   （不确定时直连在任何地区都能用，镜像只是在国内更快）
 * - 只对 GitHub 域名拼前缀，非 GitHub 域名（自建 CDN / 本地地址）原样返回
 *
 * 用法：流程入口先 `suspend` 调一次 [prepare]（按需刷新地区），之后各处用同步的
 * [candidates] 取候选。拆成两步是为了不把所有下载函数都改成 suspend。
 */
object MirrorResolver {

    private const val TAG = "MirrorResolver"

    /**
     * 内置镜像前缀，按优先级排列。与 app 的 `UpdateSource.MIRROR_PREFIXES` **同序同值**，
     * 改这里必须同步改那边（客户端的「镜像」与 core 的「镜像」得是同一个东西）。
     * 拼接是 gh-proxy 风格：前缀 + 完整 URL。
     */
    val BUILTIN_MIRROR_PREFIXES: List<String> = listOf(
        "https://mirror.drxian.qzz.io/",
        "https://max.drxian.qzz.io/",
        "https://v4.gh-proxy.org/"
    )

    /** GitHub 相关域名（命中才拼前缀） */
    private val GITHUB_HOSTS = setOf(
        "github.com",
        "raw.githubusercontent.com",
        "objects.githubusercontent.com",
        "codeload.github.com"
    )

    /** 走镜像时才有意义的国家码 */
    private const val MIRROR_COUNTRY = "CN"

    /**
     * 按需刷新地区，供更新流程入口调用一次。
     *
     * 只在 [GeoDetector.isStale] 为真时出网（7 天一次），**失败不抛也不阻断**：
     * 测不出来就按「地区未知 → 直连」走。不要每次检查更新都强制重测 —— 出网抖动会把更新拖慢。
     */
    suspend fun prepare(settings: AppSettings) {
        if (settings.updateSourceMode != AppSettings.UPDATE_MODE_AUTO) return
        if (!GeoDetector.isStale(settings)) return
        try {
            val country = GeoDetector.refreshIfStale(settings)
            AppLogger.i(TAG, "auto 模式：地区记录已过期，重测结果=${country ?: "未测出"}")
        } catch (e: Exception) {
            AppLogger.w(TAG, "auto 模式：地区重测失败（${e.message}），本次按直连处理")
        }
    }

    /** 当前是否应该走镜像。纯读 prefs，不出网。 */
    fun useMirror(settings: AppSettings): Boolean =
        useMirrorFor(settings.updateSourceMode, settings.geoCountry)

    /**
     * 生效的镜像前缀列表：自定义前缀（若有且非内置）排第一，内置三个跟后面。
     * 不判断模式 —— 供只读端点展示与 [candidates] 内部使用。
     */
    fun mirrorPrefixes(settings: AppSettings): List<String> = prefixesOf(settings.updateMirrorBase)


    /**
     * 按当前模式生成候选 URL，供「逐个试、失败降级」的下载循环使用。
     * 只是 [candidatesOf] 的取值转发：真正的规则在那个纯函数里。
     */
    fun candidates(settings: AppSettings, urlStr: String): List<String> = candidatesOf(
        mode = settings.updateSourceMode,
        country = settings.geoCountry,
        customPrefix = settings.updateMirrorBase,
        urlStr = urlStr
    )

    /**
     * 候选列表的**纯函数**实现（不碰 prefs / 网络，可直接单测）。
     *
     * - 非 GitHub 域名 / 空 URL → 只有原地址（拼前缀没有意义）
     * - 不走镜像 → 只有原地址
     * - 走镜像 → 各前缀拼出的地址 + **原地址兜底**（镜像全挂时还能直连）
     *
     * 抽成纯函数是为了能在不 mock `AppSettings`（Android SharedPreferences）的前提下
     * 覆盖「3 模式 × GitHub/非 GitHub × 自定义前缀有无 × 地区 CN/US/未知」这些组合。
     *
     * @param mode `auto` / `mirror` / `direct`；其它值按 `auto` 处理
     * @param country 出网国家码；空串 = 未测出（按直连处理，**不等于海外**）
     * @param customPrefix 自定义前缀；空或已在内置列表中时忽略
     */
    fun candidatesOf(
        mode: String,
        country: String,
        customPrefix: String,
        urlStr: String
    ): List<String> {
        if (urlStr.isBlank()) return emptyList()
        if (!isGithubUrl(urlStr)) return listOf(urlStr)
        if (!useMirrorFor(mode, country)) return listOf(urlStr)
        val result = mutableListOf<String>()
        prefixesOf(customPrefix).forEach { prefix ->
            result += prefix.trimEnd('/') + "/" + urlStr
        }
        result += urlStr
        return result.distinct()
    }

    /** [useMirror] 的纯函数版本 */
    fun useMirrorFor(mode: String, country: String): Boolean = when (mode) {
        AppSettings.UPDATE_MODE_MIRROR -> true
        AppSettings.UPDATE_MODE_DIRECT -> false
        else -> country.trim().uppercase() == MIRROR_COUNTRY
    }

    /** [mirrorPrefixes] 的纯函数版本 */
    fun prefixesOf(customPrefix: String): List<String> {
        val custom = customPrefix.trim()
        if (custom.isEmpty() || custom in BUILTIN_MIRROR_PREFIXES) return BUILTIN_MIRROR_PREFIXES
        return listOf(custom) + BUILTIN_MIRROR_PREFIXES
    }


    /** 单候选写法：只取 [candidates] 的第一个，供确实不需要降级的场景。 */
    fun firstCandidate(settings: AppSettings, urlStr: String): String =
        candidates(settings, urlStr).firstOrNull() ?: urlStr

    /** 是否 GitHub 相关域名。URL 非法时返回 false（按「不拼前缀」处理，交给下游报错）。 */
    fun isGithubUrl(urlStr: String): Boolean {
        val host = try {
            URL(urlStr).host?.lowercase()
        } catch (e: Exception) {
            null
        } ?: return false
        return host in GITHUB_HOSTS || host.endsWith(".githubusercontent.com")
    }
}
