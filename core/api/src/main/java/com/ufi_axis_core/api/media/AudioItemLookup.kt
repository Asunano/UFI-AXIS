package com.ufi_axis_core.api.media

/**
 * 「按路径回查音频曲目」的能力。
 *
 * ## 为什么要这么一个接口
 * 歌单存的是路径（见 [PlaylistItem] 的说明），但两端要显示的是完整曲目信息
 * （id / 时长 / 专辑 / 歌手 / 内嵌曲名）。这层回查必须在 core 做：
 * - 放到客户端就得让 app 和 web 各写一份"路径 → 曲目"的拼装，口径迟早对不上；
 * - 而且客户端只能逐条问 `/api/media/tags`，一个 300 首的歌单就是 300 次请求。
 *
 * 实现方是 `MediaRoutes`（那里已经有 MediaStore 的 projection 与行映射，是唯一
 * 能保证"歌单里的 item 和 `/api/media/list` 里的 item 形状完全一致"的地方）。
 * `PlaylistRoutes` 只认这个接口，不认 `MediaRoutes` 整个类。
 */
interface AudioItemLookup {

    /**
     * 现在能不能读音频媒体库。
     *
     * 歌单页必须能区分「这些歌都没了」和「没给读媒体的权限」—— 后者回一份全是 `missing`
     * 的列表就是在骗用户"你的歌全丢了"，正确做法是回 403 让客户端去引导授权
     * （与 `/api/media/list` 同口径）。
     */
    fun audioReadable(): Boolean

    /**
     * 按绝对路径批量回查音频曲目。
     *
     * @return path → 曲目 map，形状与 `/api/media/list` 的 `items[]` 逐字段一致。
     *   **查不到的路径不会出现在返回值里**（文件被删、卡没插、还没被系统扫到），
     *   调用方据此判定 `missing`。
     */
    suspend fun audioItemsByPaths(paths: List<String>): Map<String, Map<String, Any?>>
}
