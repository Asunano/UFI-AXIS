package com.ufi_axis_core.media

import kotlinx.coroutines.sync.Semaphore

/**
 * 媒体解码闸门（2026-10-13，退役系统解码计划的 Phase 2）。
 *
 * ## 它替代了什么
 * 原来是 `MediaRoutes.thumbSystemSemaphore`（一把 `Mutex`），语义是"**对 MediaProvider 的调用**
 * 串行化" —— 因为当时最大的内存问题是 `android.process.media` 被几十个并发 `loadThumbnail`
 * 顶到 471MB。系统解码退役之后那件事消失了，但**解码本身还在**（ffmpeg 抽帧、图片降采样），
 * 而它们原先几乎都没有闸门：
 *
 * | 路径 | 退役前的闸门 |
 * |---|---|
 * | `loadThumbnail`（MediaProvider） | ✅ 有（就是被替代的那把） |
 * | `FfmpegThumbnailService` | ✅ `Semaphore(1)`（自带） |
 * | `downscaledImageThumbnail`（BitmapFactory） | ❌ **无** |
 * | `videoFrameThumbnail`（MMR，已删） | ❌ **无** |
 *
 * 所以闸门必须从"MediaProvider 调用"改成"**所有解码工作**"。不换的话，删掉系统解码只是把
 * 内存压力从系统进程搬进 core 自己 —— 从"`android.process.media` 涨到 471MB"
 * 变成"core 被 OOM 杀掉"，**更糟**（core 一死，整套服务连同 HTTP 一起没）。
 *
 * ## 为什么是两把而不是一把
 * `heavy` 与 `light` **必须分开**，否则会互相饿死：
 *
 * - [heavy] 单次 ffmpeg 抽帧最长可达 `FfmpegThumbnailService.EXTRACT_TIMEOUT_MS`（20s），
 *   而且封面预热（`ThumbPrewarmWorker`）会在闲时循环里连续跑它。若与图片共用**一个**许可，
 *   用户在预热期间滚图片网格就会**每张卡 20 秒** —— 那不是"慢一点"，是功能不可用。
 * - [light] 单次只解一张降采样图（4000×3000 按 `inSampleSize` 解出 500×375 ≈ 750KB），
 *   并发 2 的峰值内存约 1.5MB，对 1.5GB 的设备完全无压力。
 *
 * ## ⚠️ 使用规则（违反会死锁）
 * `Semaphore` **不可重入**。所以：
 *  1. [heavy] **只在 `FfmpegThumbnailService` 内部取**。它的三个入口
 *     （`extractJpeg` / `probeVideoInfo` / `probeDuration`）自己已经取了，
 *     调用方**不得**再包一层 —— 那会在同一协程里二次取同一把信号量，永久挂住。
 *  2. [light] 每条路径**只在一层取**：图片在 `MediaRoutes.downscaledImageThumbnail`，
 *     音频封面在 `MediaRoutes.audioCoverBytes`。不要在两个嵌套的函数里各取一次。
 *
 * 这两条是**约定**，编译器不会替你检查；`MediaDecodeGateTest` 用"峰值并发"断言间接看住它们。
 */
object MediaDecodeGate {

    /**
     * 重解码：ffmpeg 软解抽帧（视频帧、视频元信息探测）。
     *
     * 许可数 **1** 是刻意的：`FfmpegThumbnailService` 的 KDoc 已经算过这笔账 ——
     * 1.5GB 内存的设备上并发两张 4K HEVC 就可能 OOM。前台请求与后台预热**共用**这一把，
     * 所以"预热正在跑"时用户点开的封面会排队而不是叠加。
     */
    val heavy = Semaphore(1)

    /**
     * 轻解码：图片降采样（`BitmapFactory`）与音频内嵌封面的字节读取。
     *
     * 许可数 **2**：单次内存已由 `inSampleSize` / 封面大小上限压到 MB 级，
     * 留 2 个许可让列表滚动时有两张图在并行解，体感明显好于完全串行。
     * 取 2 而不是 4，是因为这台设备的内存余量本来就紧张，而图片解码是纯 CPU 的短任务 ——
     * 串行度带来的延迟远小于"多解几张把可用内存吃掉"的代价。
     */
    val light = Semaphore(2)
}
