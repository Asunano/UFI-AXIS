package com.ufi_axis.data.api

/**
 * 缩略图来源记录（2026-10-07）。
 *
 * core 的 `GET /api/media/thumbnail` 响应带 `X-Thumb-Source` 头（cache/system/mmr/ffmpeg），
 * 404 时 body 里带 `reason`。app 的封面走 Coil 直连 URL，响应头只能在拦截器层拿到 ——
 * 这里用一个按 (type,id) 记录最后来源的小表，详情弹窗读它显示「封面来源」。
 *
 * 只记最近一页条目（LinkedHashMap LRU，访问序），封面是滚动态的，全量记录没有意义。
 */
object ThumbSourceRegistry {
    private const val MAX = 512
    private val map = object : LinkedHashMap<Long, String>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, String>): Boolean = size > MAX
    }

    /** 把 `?type=video&id=3&size=…` 的 URL 解析成 key；解析不出返回 null（不记录）。 */
    fun keyOf(url: String): Long? {
        val withoutScheme = url.substringAfter("://", "")
        val path = withoutScheme.substringAfter('/').substringBefore('?')
        if (path != "api/media/thumbnail") return null
        val query = url.substringAfter('?', "")
        val type = query.split('&').firstOrNull { it.startsWith("type=") }?.substringAfter('=') ?: return null
        val id = query.split('&').firstOrNull { it.startsWith("id=") }?.substringAfter('=')?.toLongOrNull()
            ?: return null
        return "$type/$id".hashCode().toLong() and 0x7FFFFFFFL
    }

    fun record(url: String, source: String) {
        val key = keyOf(url) ?: return
        synchronized(map) { map[key] = source }
    }

    /** 404 时记录失败原因（与来源同一个槽位，显示时按前缀区分）。 */
    fun recordFailure(url: String, reason: String) {
        val key = keyOf(url) ?: return
        synchronized(map) { map[key] = "fail:$reason" }
    }

    /** 返回来源（"cache"/"system"/"mmr"/"ffmpeg"），失败返回 "fail:原因"，没记录返回 null。 */
    fun sourceOf(url: String): String? {
        val key = keyOf(url) ?: return null
        synchronized(map) { return map[key] }
    }

    /** 手动重抽后清掉旧记录，避免详情弹窗读到过期来源。 */
    fun invalidate(url: String) {
        val key = keyOf(url) ?: return
        synchronized(map) { map.remove(key) }
    }
}
