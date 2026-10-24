package com.ufi_axis.ui.media

import android.content.Context
import coil3.SingletonImageLoader
import coil3.request.ImageRequest

/**
 * 音频封面的**有界预加载**（2026-10-21）。
 *
 * ## 为什么是"几首"而不是"整队列"
 * `/api/media/cover` 是 core **按需解析**的（读文件、抽内嵌封面），一次冷请求是几十到几百
 * 毫秒。整队列预取（几十上百首）会把 Coil 的请求队列和 core 的文件 IO 一起灌满，反过来拖慢
 * 用户此刻真正在看的那一张 —— 这就是这里两个窗口都刻意取小值的原因。
 *
 * ## 两个窗口
 * - [QUEUE_WINDOW]：播放页里当前曲**前后各**这么多首。点"上一首 / 下一首"、自动续播都落在
 *   这个圈里，所以换歌时是内存命中，不会出现"空白几帧再出图"。
 * - [LIST_HEAD]：列表首屏的条数。滚动时靠前的那几首不用等 —— 但只取前几首，不给整页发请求。
 *
 * ## 两套键
 * 播放页那张封面用的是**显式**内存缓存键（[audioCoverMemoryCacheKey]，键里带尺寸），所以
 * 这里的 [playerSize] 必须用同一套；列表缩略图走 `MediaThumb` 的默认请求口径，[thumbs]
 * 就**不**设显式键 —— 否则键对不上，预取白做。
 */
internal object UfiAudioCoverPrefetch {

    /** 播放页预取窗口：当前曲前后各几首。 */
    const val QUEUE_WINDOW = 3

    /** 列表首屏预取条数。 */
    const val LIST_HEAD = 8

    /**
     * 按**播放页尺寸**预取（带显式内存键，与 `MediaAudioPlayerScreen` 的封面请求一致）。
     *
     * 尺寸必须是封面框的 px：键里带尺寸，解错尺寸等于没预取。
     */
    fun playerSize(context: Context, urls: List<String>, px: Int) {
        enqueue(context, urls, px) { url -> audioCoverMemoryCacheKey(url, px) }
    }

    /** 按**列表缩略图尺寸**预取（不设显式键，与 `MediaThumb` 的 `AsyncImage` 同一口径）。 */
    fun thumbs(context: Context, urls: List<String>, px: Int) {
        enqueue(context, urls, px) { null }
    }

    private inline fun enqueue(
        context: Context,
        urls: List<String>,
        px: Int,
        keyOf: (String) -> String?
    ) {
        if (px <= 0) return
        val targets = urls.filter { it.isNotBlank() }.distinct()
        if (targets.isEmpty()) return
        val loader = SingletonImageLoader.get(context)
        targets.forEach { url ->
            val builder = ImageRequest.Builder(context)
                .data(url)
                // 写死尺寸：不写的话 Coil 按"原始尺寸"解，与显示侧按约束算出来的图不是同一份
                .size(px, px)
            keyOf(url)?.let { builder.memoryCacheKey(it) }
            // enqueue 而不是 execute：只要把图放进内存 / 磁盘缓存，结果本身没人关心，
            // 也不该阻塞调用方的组合协程。
            loader.enqueue(builder.build())
        }
    }
}
