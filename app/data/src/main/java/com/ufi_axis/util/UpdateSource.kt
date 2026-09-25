package com.ufi_axis.util

import com.ufi_axis.data.api.RetrofitClient
import com.ufi_axis.data.model.UpdateSourceInfo

/**
 * app 自更新的候选 URL 生成（2026-08-10：前端直连 GitHub + 镜像加速，镜像源自动逐级降级）。
 *
 * 2026-09-22 决策下沉 core：「走不走镜像」「走哪个镜像」全由 core 的 `MirrorResolver` 决定，
 * app 只调 `GET /api/update/source` 取结果再拼 URL。原因是这份判断以前在 app 与 core 各有一份，
 * `auto` 到了 core 就失真成「强制镜像」，海外设备被迫走 gh-proxy。
 *
 * 下载本身仍留在 app、不走 core 代理：那是 2026-08-10 刻意去 Core 化的设计 ——
 * core 崩了 app 还得能更新自己，否则两端互相锁死。
 *
 * 镜像前缀用法 = 直接拼接在完整 URL 前（gh-proxy 风格）：
 * `https://mirror.drxian.qzz.io/https://github.com/...`
 */
object UpdateSource {

    /**
     * 内置镜像前缀（优先级顺序：mirror.drxian.qzz.io → max.drxian.qzz.io → v4.gh-proxy.org）。
     *
     * **这不是真源** —— 真源是 core 的 `MirrorResolver`，正常路径下用的是 core 返回的
     * `mirror_prefixes`。这里留一份同序同值的副本只为 **core 不可达时兜底**
     * （用户选了 mirror 却连不上设备时仍能更新 app）。改动必须与 core 那份同步，否则
     * 「app 能更新、core 更新不了」会重新变成可达状态。
     */
    val MIRROR_PREFIXES: List<String> = listOf(
        "https://mirror.drxian.qzz.io/",
        "https://max.drxian.qzz.io/",
        "https://v4.gh-proxy.org/"
    )

    const val MODE_AUTO = "auto"
    const val MODE_MIRROR = "mirror"
    const val MODE_DIRECT = "direct"

    /**
     * 向 core 取更新源决策。core 不可达 / 老 core 没这个端点时返回 null，由调用方兜底。
     */
    private suspend fun sourceFromCore(prefs: AppPreferences): UpdateSourceInfo? = try {
        RetrofitClient.getApiService(prefs).getUpdateSource()
    } catch (e: Exception) {
        DebugLog.w("UpdateSource", "读取 core 的 /api/update/source 失败，本次用本地缓存的模式兜底: ${e.message}")
        null
    }

    /**
     * 向 core 取出网国家码，顺手写入 [AppPreferences.lastCountry] 缓存（仅供 UI 显示）。
     * core 不可达 / 它自己也没测出来时返回 null。
     */
    private suspend fun countryFromCore(prefs: AppPreferences): String? = try {
        val country = RetrofitClient.getApiService(prefs).getGeo().country.trim()
        if (country.isEmpty()) {
            null
        } else {
            prefs.lastCountry = country
            country
        }
    } catch (e: Exception) {
        DebugLog.w("UpdateSource", "读取 core 的 /api/geo 失败，界面显示回落本地缓存: ${e.message}")
        null
    }

    /**
     * 供设置页显示用的国家码：优先用 core 的当前值，取不到时回落到 [AppPreferences.lastCountry]
     * 缓存（"上次连上设备时它说的"），两者都没有则空串。
     *
     * 纯展示用，不参与选源 —— 选源问的是 [sourceFromCore]。
     */
    suspend fun countryForDisplay(prefs: AppPreferences): String =
        countryFromCore(prefs) ?: prefs.lastCountry

    /**
     * 按优先级生成候选 URL 列表（用于"失败逐级递减"的请求循环）：镜像候选在前、原 URL 兜底。
     *
     * 正常路径按 core 的决策走：`use_mirror=false` → 只直连；`use_mirror=true` → core 给的
     * 前缀逐个拼 + 直连兜底（core 没给前缀才用内置副本）。
     *
     * core 不可达时按本地缓存的 [AppPreferences.updateSourceMode] 兜底，其中 `auto` 走**直连**：
     * 地区未知时直连在任何地区都能用，镜像只是在国内更快，所以"不确定"的正确答案是直连。
     *
     * @param url 原始 GitHub URL
     */
    suspend fun candidateUrls(prefs: AppPreferences, url: String): List<String> {
        if (url.isBlank()) return emptyList()
        val info = sourceFromCore(prefs)
        val prefixes = when {
            info != null && !info.use_mirror -> emptyList()
            info != null -> info.mirror_prefixes.ifEmpty { MIRROR_PREFIXES }
            prefs.updateSourceMode == MODE_MIRROR -> MIRROR_PREFIXES
            else -> emptyList()
        }
        val candidates = prefixes.map { it.trimEnd('/') + "/" + url } + url
        return candidates.distinct()
    }
}
