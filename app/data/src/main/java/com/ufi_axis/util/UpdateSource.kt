package com.ufi_axis.util

import com.ufi_axis.data.api.RetrofitClient

/**
 * 更新源 / 镜像拼接工具（2026-08-10：前端直连 GitHub + 镜像加速，镜像源自动逐级降级）。
 *
 * 三种模式（[AppPreferences.updateSourceMode]）：
 * - "auto"    自动：按**core 给的出网国家**决定是否走镜像——中国大陆（CN）走镜像（按优先级降级），
 *             其他地区 GitHub 直连。
 * - "mirror"  镜像：强制按候选 URL 优先级逐级尝试（内置镜像 1/2/3 → 直连兜底，忽略国家）。
 * - "direct"  直连：始终使用 GitHub 原始链接（不走镜像）。
 *
 * 镜像前缀用法 = 直接拼接在完整 URL 前（gh-proxy 风格）：
 * `https://mirror.drxian.qzz.io/https://github.com/...`
 *
 * 2026-09-18：国家从哪来变了，镜像列表与拼接规则**一个字都没动**。app 侧不再做地理检测
 * （原 `GeoDetector` 已删除）—— 判据是出网 IP 的归属，而设备才是出网点，见 `GET /api/geo`。
 */
object UpdateSource {

    /** 内置镜像前缀（优先级顺序：mirror.drxian.qzz.io → max.drxian.qzz.io → v4.gh-proxy.org） */
    val MIRROR_PREFIXES: List<String> = listOf(
        "https://mirror.drxian.qzz.io/",
        "https://max.drxian.qzz.io/",
        "https://v4.gh-proxy.org/"
    )

    const val MODE_AUTO = "auto"
    const val MODE_MIRROR = "mirror"
    const val MODE_DIRECT = "direct"

    /**
     * 是否应使用镜像。
     * auto 模式：问 core 要出网国家（CN→true，其他→false，**core 不可达→false**）；
     * mirror 模式恒 true（强制走镜像，忽略国家）；direct 模式恒 false。
     */
    suspend fun shouldUseMirror(prefs: AppPreferences): Boolean {
        return when (prefs.updateSourceMode) {
            MODE_MIRROR -> true
            MODE_DIRECT -> false
            else -> countryFromCore(prefs) == "CN"
        }
    }

    /**
     * 向 core 取出网国家码，顺手写入 [AppPreferences.lastCountry] 缓存（仅供 UI 显示）。
     * core 不可达 / 它自己也没测出来时返回 null。
     *
     * **不读本地缓存兜底**：缓存是"上次连上设备时它说的"，而镜像决策要的是当下的出网位置；
     * 拿一份可能过期的值去决定走不走镜像，等于把"设备被带出国"变成一次谁都看不懂的下载失败。
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
        DebugLog.w("UpdateSource", "读取 core 的 /api/geo 失败，本次按直连处理: ${e.message}")
        null
    }

    /**
     * 供设置页显示用的国家码：优先用 core 的当前值，取不到时回落到 [AppPreferences.lastCountry]
     * 缓存（"上次连上设备时它说的"），两者都没有则空串。
     *
     * 与 [shouldUseMirror] 刻意不同源：显示一个略旧的值只是信息不新，而**决策**用旧值会选错源。
     */
    suspend fun countryForDisplay(prefs: AppPreferences): String =
        countryFromCore(prefs) ?: prefs.lastCountry

    /**
     * 按优先级生成候选 URL 列表（用于"失败逐级递减"的请求循环）。
     * auto 模式：CN→内置镜像 1/2/3 + 直连兜底；非 CN（含 core 不可达）→ 仅直连
     * （直连在任何地区都能用，镜像只是在国内更快，所以"不确定"的正确答案是直连）；
     * mirror 模式：内置镜像 1/2/3 + 直连兜底（强制走镜像）；
     * direct 模式：仅原 URL。
     * @param url 原始 GitHub URL
     */
    suspend fun candidateUrls(prefs: AppPreferences, url: String): List<String> {
        if (url.isBlank()) return emptyList()
        if (prefs.updateSourceMode == MODE_DIRECT) return listOf(url)
        val candidates = mutableListOf<String>()
        if (shouldUseMirror(prefs)) {
            MIRROR_PREFIXES.forEach { candidates += it + url }
        }
        candidates += url // 直连兜底（auto 非 CN 模式或所有镜像失效时）
        return candidates.distinct()
    }

    /**
     * 对原始下载/清单 URL 应用镜像拼接（单候选，供无需降级的场景；通常用 candidateUrls）。
     * mirror 模式 → 内置第一优先级；auto CN → 内置第一优先级；其他 → 原 URL。
     */
    suspend fun applyMirror(prefs: AppPreferences, url: String): String {
        if (url.isBlank()) return url
        if (!shouldUseMirror(prefs)) return url
        return MIRROR_PREFIXES.first() + url
    }

    /**
     * 当前生效的镜像前缀（同步到设备端 Core 用，direct 模式返回 null）。
     */
    fun selectedMirrorPrefix(prefs: AppPreferences): String? {
        if (prefs.updateSourceMode == MODE_DIRECT) return null
        return MIRROR_PREFIXES.firstOrNull()
    }
}
