package com.ufi_axis.util

/**
 * 更新源 / 镜像拼接工具（2026-08-10：前端直连 GitHub + 镜像加速，镜像源自动逐级降级）。
 *
 * 三种模式（[AppPreferences.updateSourceMode]）：
 * - "auto"    自动：[GeoDetector] 国家缓存决定是否走镜像——中国大陆（CN）走镜像（按优先级降级），
 *             其他地区 GitHub 直连；首次缓存空会触发一次地理请求并回写。
 * - "mirror"  镜像：强制按候选 URL 优先级逐级尝试（内置镜像 1/2/3 → 直连兜底，忽略国家）。
 * - "direct"  直连：始终使用 GitHub 原始链接（不走镜像）。
 *
 * 镜像前缀用法 = 直接拼接在完整 URL 前（gh-proxy 风格）：
 * `https://mirror.drxian.qzz.io/https://github.com/...`
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
     * 是否应使用镜像。auto 模式：依据国家缓存（CN→true，其他→false，空缓存可触发检测）；
     * mirror 模式恒 true（强制走镜像，忽略国家）；direct 模式恒 false。
     */
    suspend fun shouldUseMirror(prefs: AppPreferences, detectIfUnknown: Boolean = true): Boolean {
        return when (prefs.updateSourceMode) {
            MODE_MIRROR -> true
            MODE_DIRECT -> false
            else -> {
                var country = prefs.lastCountry
                if (country.isEmpty() && detectIfUnknown) {
                    country = GeoDetector.detectCountry() ?: ""
                    prefs.lastCountry = country
                }
                country == "CN"
            }
        }
    }

    /**
     * 按优先级生成候选 URL 列表（用于"失败逐级递减"的请求循环）。
     * auto 模式：CN→内置镜像 1/2/3 + 直连兜底；非 CN → 仅直连（境外 GitHub 直连快，无需镜像）；
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
    suspend fun applyMirror(prefs: AppPreferences, url: String, detectIfUnknown: Boolean = true): String {
        if (url.isBlank()) return url
        if (!shouldUseMirror(prefs, detectIfUnknown)) return url
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