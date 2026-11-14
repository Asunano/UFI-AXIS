package com.ufi_axis.data.screen

import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.media.Image
import android.media.ImageReader
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import java.nio.ByteBuffer
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * H.264 硬解 → ARGB 位图（2026-11-01，原生投屏）。
 *
 * ## 这条链路（和上一版的结构差异）
 * ```
 * WS 线程 ──submit()──▶ 命令队列 ──▶ 解码线程（唯一的 MediaCodec 调用方）
 *                                      │ releaseOutputBuffer(idx, render=true)
 *                                      ▼
 *                                ImageReader(YUV_420_888, 3)
 *                                      │ onImageAvailable（取帧线程）
 *                                      ▼
 *                        YUV→ARGB（复用 IntArray + 双 Bitmap）──▶ onFrame(Bitmap)
 * ```
 * **解码与取帧彻底分开**：上一版在同一个 Handler 里先 `releaseOutputBuffer` 再
 * `acquireLatestImage`，而 ImageReader 的帧是经 BufferQueue **异步**交出来的 ——
 * 刚 release 完立刻 acquire 拿到 null 是常态，表现就是"解码计数在涨、画面永远是黑的"。
 * 现在由 ImageReader 的 listener 驱动取帧（它一定在图像真正可用时才回调）。
 *
 * ## 为什么输出到 ImageReader 而不是视图 Surface
 * 实测这台设备（Android 13 / ZTE F50）把 `releaseOutputBuffer(index, true)` 打到
 * SurfaceView / TextureView 的 Surface 上会**原生崩溃**（解码本身 60+ 帧无异常），
 * 进程内 ImageReader 的输出目标由我们自己持有，绕开了视图生命周期。代价是
 * YUV→ARGB 的 CPU 开销 —— 所以下面每一处分配都做了复用。
 *
 * ## 为什么不能在输入侧随便丢帧
 * H.264 的 P 帧依赖前序帧。丢一个非关键帧，后面所有帧都花屏/错位，直到下一个 IDR。
 * 所以 `submit()` 满了就**等**（给 WS 读线程反压 → TCP 反压 → 服务端自然降速），
 * 而不是像上一版那样 `if (queued > MAX) return` 直接丢；真等超时才丢，并计数报出来。
 *
 * ## 线程
 * MediaCodec 是 native 对象，跨线程乱序调用在部分平台会直接 SIGSEGV（Java 层 try/catch
 * 兜不住）。所以 configure / queue / dequeue / release 全在**同一条**线程上串行执行。
 * 这条线程不跑 Looper：它就是一个 `while` 循环从命令队列取活，避免"循环占死 Looper，
 * 于是 configure/release 的 post 永远排不上"这类死锁。
 */
class ScreenVideoDecoder(
    /** 状态回调：配置成功 / 首帧 / 解码失败 —— 没有日志时的唯一线索 */
    private val onState: (String) -> Unit = {},
    /**
     * 输出目标（Surface 直绘）。
     *
     * 非 null 时走"解码器 → Surface → 合成器"：**不产生可读像素**，也就没有
     * YUV→ARGB 与 Bitmap 上传（2026-11-02：对齐 web 端 WebGL 的做法）。
     * null 时退回像素路径（ImageReader + Kotlin 换算），保证任何机型都有兜底。
     */
    private val surface: android.view.Surface? = null,
    /** 是否优先软解。Surface 路径下可以放心用硬解（不读像素，tiled 布局无所谓） */
    private val preferSoftware: Boolean = true,
    /** 每解出一帧 ARGB 位图回调一次（在取帧线程上） */
    private val onFrame: (Bitmap) -> Unit = {},
) {

    private sealed interface Cmd {
        class Configure(val w: Int, val h: Int, val csd: ByteArray) : Cmd {
            val ok = AtomicBoolean(false)
            val latch = java.util.concurrent.CountDownLatch(1)
        }
        class Frame(val data: ByteArray, val ptsUs: Long, val keyframe: Boolean) : Cmd
    }

    /**
     * 命令队列（有界）。
     *
     * 容量就是背压阀门：解码跟不上时 `offer(timeout)` 会失败 → 调用方计数丢帧，
     * 而不是让队列无限涨到 OOM。96 帧在 20fps 下约 5 秒，正常抖动吃不满。
     */
    private val queue = LinkedBlockingQueue<Cmd>(QUEUE_CAPACITY)

    @Volatile
    private var running = true

    @Volatile
    private var codec: MediaCodec? = null

    @Volatile
    private var reader: ImageReader? = null

    private val renderThread = HandlerThread("ufi-screen-yuv").apply { start() }
    private val renderHandler = Handler(renderThread.looper)

    /** 当前配置尺寸（解码线程写、取帧线程读） */
    @Volatile private var width = 0
    @Volatile private var height = 0

    /** 复用的像素缓冲与双 Bitmap（见 [render] 的注释） */
    private var pixels: IntArray? = null
    private var bitmapA: Bitmap? = null
    private var bitmapB: Bitmap? = null
    private var flip = false
    /** 首帧的 plane 布局只报一次（诊断"颜色不对/画面撕裂"时最有用的一行） */
    private var firstFrameLogged = false

    /** 实际用上的解码器名（写进 [stats]，排"硬解/软解"这类问题一眼就能看到） */
    @Volatile
    private var codecName: String = ""

    private val info = MediaCodec.BufferInfo()
    private val released = AtomicBoolean(false)

    /** 丢帧后置位：只喂关键帧，直到重新同步（或超时放弃） */
    @Volatile
    private var awaitingKeyframe = false
    private var resyncDeadlineMs = 0L
    private val decodeThread = Thread(::decodeLoop, "ufi-screen-dec").apply {
        isDaemon = true
        start()
    }

    @Volatile
    var submittedFrames: Long = 0L
        private set

    @Volatile
    var decodedFrames: Long = 0L
        private set

    @Volatile
    var renderedFrames: Long = 0L
        private set

    /** 等不到位置而丢掉的帧数（>0 说明确实跟不上，且画面会花屏到下一个 IDR） */
    @Volatile
    var droppedFrames: Long = 0L
        private set

    val isRunning: Boolean get() = codec != null && running

    /** 一行诊断：贴在页面上比翻 logcat 有用（release 构建里 INFO 级日志不落地） */
    fun stats(): String =
        "${codecName.ifBlank { "?" }} 提交=$submittedFrames 解码=$decodedFrames 出图=$renderedFrames 丢弃=$droppedFrames"

    /**
     * 用配置包（SPS/PPS）初始化解码器。
     *
     * 同步等结果（最多 3s）：调用方需要知道 configure 成没成，失败时要能立刻把
     * "这台设备不吃这套参数"报到界面上，而不是继续喂帧喂到黑屏。
     */
    fun configure(width: Int, height: Int, csd: ByteArray): Boolean {
        if (width <= 0 || height <= 0 || csd.isEmpty()) {
            onState("配置参数非法（${width}x$height csd=${csd.size}）")
            return false
        }
        if (!running) return false
        val cmd = Cmd.Configure(width, height, csd)
        // Configure 必须能入队：队列满时阻塞等（解码线程一定在消费），绝不能被丢
        runCatching { queue.put(cmd) }.getOrElse { return false }
        cmd.latch.await(3, TimeUnit.SECONDS)
        return cmd.ok.get()
    }

    /**
     * 提交一帧（Annex-B，带起始码）。
     *
     * **不会静默丢帧**：队列满时最多等 500ms 拿位置；实在放不进去才丢并计数
     * （这时候解码已经落后半秒以上，画质本来就没救了）。未配置完成前直接忽略。
     */
    fun submit(frame: ByteArray, ptsUs: Long, keyframe: Boolean) {
        if (!running || codec == null) return
        if (frame.isEmpty() || frame.size > MAX_FRAME_BYTES) return
        val accepted = runCatching {
            queue.offer(Cmd.Frame(frame, ptsUs, keyframe), QUEUE_OFFER_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        }.getOrDefault(false)
        if (!accepted && running) {
            droppedFrames++
            // 丢过帧之后码流已经断了：从下一个 IDR 重新开始，别再喂残帧（那只会持续花屏）
            awaitingKeyframe = true
            resyncDeadlineMs = System.currentTimeMillis() + RESYNC_TIMEOUT_MS
            if (droppedFrames % 30L == 1L) {
                onState("解码跟不上，已丢弃 $droppedFrames 帧，等待下一个关键帧重同步")
            }
        }
    }

    // ── 解码线程 ──

    private fun decodeLoop() {
        while (running) {
            val cmd = try {
                queue.poll(200, TimeUnit.MILLISECONDS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            } ?: continue

            when (cmd) {
                is Cmd.Configure -> {
                    try {
                        cmd.ok.set(doConfigure(cmd.w, cmd.h, cmd.csd))
                    } catch (t: Throwable) {
                        onState("配置异常：${t.javaClass.simpleName}: ${t.message}")
                    } finally {
                        cmd.latch.countDown()
                    }
                }
                is Cmd.Frame -> {
                    try {
                        feed(cmd)
                    } catch (t: Throwable) {
                        onState("解码异常：${t.javaClass.simpleName}: ${t.message}")
                    }
                }
            }
        }
        releaseAll()
    }

    private fun doConfigure(w: Int, h: Int, csd: ByteArray): Boolean {
        releaseAll()
        return try {
            // 输出目标：ImageReader。3 张缓冲 —— 取帧线程慢一拍时解码器不至于马上被卡住。
            // 必须是 YUV_420_888：绝大多数硬件解码器只保证 YUV 输出，RGBA 会在 native 崩。
            val r = ImageReader.newInstance(w, h, ImageFormat.YUV_420_888, 3)
            // 先挂到字段上：下面 configure/start 万一抛异常，catch 里能把它关掉（否则泄漏）
            reader = r
            r.setOnImageAvailableListener({ source -> render(source) }, renderHandler)

            val useSurface = surface != null && surface.isValid
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h)
            // 低延迟提示：priority=0 表示实时优先（C2 会把这个进程当"正在放视频"对待，
            // 不再为了省电把帧攒起来）。这两项不是所有实现都认，认不出的会被忽略，设了没坏处。
            runCatching { format.setInteger(MediaFormat.KEY_PRIORITY, 0) }
            runCatching { format.setInteger(MediaFormat.KEY_OPERATING_RATE, 30) }
            // csd-0 = SPS，csd-1 = PPS。**按 NAL 类型挑**，不按位置：
            // 有些编码器的配置包前面还带 AUD(9) / SEI(6)，按位置取会把它们当 SPS 交出去。
            val (sps, pps) = ScreenProtocol.spsPps(csd)
            val parts = ScreenProtocol.splitCsd(csd)
            val csd0 = sps ?: parts.getOrNull(0)
            val csd1 = pps ?: parts.getOrNull(1)
            if (csd0 != null) format.setByteBuffer("csd-0", ByteBuffer.wrap(csd0))
            if (csd1 != null) format.setByteBuffer("csd-1", ByteBuffer.wrap(csd1))

            // ★ 优先用**软件解码器**（c2.android.avc.decoder）。
            //
            // 2026-11-01 真机定位：同样的字节、同样的 csd，在 shell 进程（app_process，只能用软件解码器）
            // 解出来是正确画面，而在 app 进程里解出来是绿色噪点 —— 唯一可观测的差别是平面布局：
            // shell 侧 I420（uvRow=192, uvPix=1），app 侧 NV12（uvRow=384, uvPix=2），
            // 说明 app 被分配到了**厂商硬解**。这条流的 SPS/PPS 与厂商硬解不兼容时就会解出花屏，
            // 而软件解码器对同一份数据是稳的。360×640 的软件解码开销完全吃得下。
            val c = createAvcDecoder(useSurface || preferSoftware)
            codecName = c.name + if (useSurface) "·surface" else ""
            codec = c

            if (useSurface) {
                // Surface 直绘：解码器把帧直接交给合成器，CPU 不碰像素。
                // 这条路上 tiles/私有布局都无所谓，所以硬解也能放心用。
                pixels = null
                bitmapA = null
                bitmapB = null
                r.setOnImageAvailableListener(null, null)
                r.close()
                reader = null
                width = w
                height = h
                c.configure(format, surface, null, 0)
                c.start()
            } else {
                // ★ 缓冲与尺寸必须在 start() 之前就绪：start 之后解码器随时可能吐第一帧，
                // 而取帧线程一进来就会读 pixels/bitmapA——晚一步设置就是第一帧空指针/尺寸为 0。
                width = w
                height = h
                pixels = IntArray(w * h)
                bitmapA = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                bitmapB = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                flip = false
                c.configure(format, r.surface, null, 0)
                c.start()
            }

            submittedFrames = 0
            decodedFrames = 0
            renderedFrames = 0
            droppedFrames = 0
            onState("解码器 ${c.name} 已配置 ${w}x$h（SPS=${csd0?.size ?: 0}B PPS=${csd1?.size ?: 0}B）")
            true
        } catch (t: Throwable) {
            // 低端机上 configure/start 失败是预期内的（没有 AVC 解码器、参数不被接受）
            onState("解码器初始化失败：${t.javaClass.simpleName}: ${t.message}")
            runCatching { reader?.close() }
            reader = null
            runCatching { codec?.release() }
            codec = null
            false
        }
    }

    private fun feed(cmd: Cmd.Frame) {
        val c = codec ?: return
        if (awaitingKeyframe) {
            // 等到 IDR 才继续；等太久（编码器迟迟不发 IDR）就放弃等待 —— 有花屏也比冻屏强
            if (cmd.keyframe) {
                awaitingKeyframe = false
                onState("已从关键帧重同步")
            } else if (System.currentTimeMillis() > resyncDeadlineMs) {
                awaitingKeyframe = false
                onState("等不到关键帧，恢复喂帧（画面可能花屏）")
            } else {
                return
            }
        }
        var inIndex = c.dequeueInputBuffer(INPUT_TIMEOUT_US)
        // 输入缓冲暂时没有：等一下再试，**不丢帧**（丢 P 帧会让后面全花）
        var waits = 0
        while (inIndex < 0 && running && waits < INPUT_RETRIES) {
            inIndex = c.dequeueInputBuffer(INPUT_TIMEOUT_US)
            waits++
        }
        if (inIndex < 0) {
            droppedFrames++
            return
        }
        val buf = c.getInputBuffer(inIndex)
        if (buf == null || cmd.data.size > buf.capacity()) {
            // 放不进去也必须把这个索引还回去，否则 codec 永远少一个输入缓冲
            c.queueInputBuffer(inIndex, 0, 0, 0, 0)
            droppedFrames++
            onState("帧超缓冲 ${cmd.data.size} > ${buf?.capacity() ?: 0}，丢弃")
            return
        }
        buf.clear()
        buf.put(cmd.data)
        c.queueInputBuffer(inIndex, 0, cmd.data.size, cmd.ptsUs, 0)
        submittedFrames++
        drain(c)
    }

    private fun drain(c: MediaCodec) {
        while (true) {
            val outIndex = try {
                c.dequeueOutputBuffer(info, 0)
            } catch (t: Throwable) {
                onState("出队异常：${t.javaClass.simpleName}")
                return
            }
            when {
                outIndex >= 0 -> {
                    // 配置缓冲（SPS/PPS）在部分实现上会作为输出帧出现：它没有画面，
                    // 必须 render=false 还回去，否则会被当成一张图交给 ImageReader。
                    //
                    // ★ 只用 flags 判，**不能用 `info.size == 0`**：输出到 Surface / ImageReader 时
                    // `BufferInfo.size` 本来就可能是 0（详见 MediaCodec.BufferInfo 文档），
                    // 拿它当判据会把每一帧都当成配置帧丢掉 —— 表现正是"解码计数在涨、画面全黑"。
                    val isConfig = (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                    try {
                        c.releaseOutputBuffer(outIndex, !isConfig)
                    } catch (t: Throwable) {
                        onState("回收异常：${t.javaClass.simpleName}: ${t.message}")
                    }
                    if (!isConfig) {
                        decodedFrames++
                        // Surface 路径没有取帧回调，出图数就在这里记（页面照样能看 提交/解码/出图）
                        if (reader == null) {
                            renderedFrames++
                            // ★ 2026-11-04：Surface 路径也要报一次"首帧"。
                            // 两处用途：
                            // 1. 页面据此把遮罩从"正在建立会话"切到控制条（首帧前必须显示进度环）；
                            // 2. 硬解黑屏排查时，trace 里有没有这一行能直接区分
                            //    "码流/解码没起来" 与 "解码正常、但 Surface 没显示"。
                            if (!firstFrameLogged) {
                                firstFrameLogged = true
                                val of = runCatching { c.outputFormat }.getOrNull()
                                val ow = runCatching { of?.getInteger("width") }.getOrNull() ?: 0
                                val oh = runCatching { of?.getInteger("height") }.getOrNull() ?: 0
                                onState("首帧 ${ow}x$oh（Surface 直绘）")
                            }
                        }
                    }
                }
                outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    // ★ 这里才是**解码器自己的**几何：ImageReader 报的 width 是消费者侧（我们建的）尺寸，
                    // 两者不一致时画面就会"内容比缓冲区窄/宽"，表现为条纹或重复。
                    val of = runCatching { c.outputFormat }.getOrNull()
                    val ow = runCatching { of?.getInteger("width") }.getOrNull()
                    val oh = runCatching { of?.getInteger("height") }.getOrNull()
                    val stride = runCatching { of?.getInteger("stride") }.getOrNull()
                    val slice = runCatching { of?.getInteger("slice-height") }.getOrNull()
                    val color = runCatching { of?.getInteger("color-format") }.getOrNull()
                    onState("输出格式 ${ow}x$oh stride=$stride slice=$slice color=$color")
                }
                // INFO_TRY_AGAIN_LATER：这一轮没有输出，正常，下一帧再来
                else -> return
            }
        }
    }

    // ── 取帧线程 ──

    /**
     * ImageReader 回调（取帧线程）：取**最新**一张 → YUV→ARGB → 回调。
     *
     * ## 为什么用 acquireLatestImage
     * 取帧偶尔慢一拍时，`acquireNextImage` 会把积压的旧帧一张张吐出来，画面越拖越晚；
     * `acquireLatestImage` 直接跳到最新那帧，并把其余的直接关掉。
     *
     * ## 为什么要复用 Bitmap
     * 848p 一帧 ARGB 是 1.6MB，20fps 就是 32MB/s 的分配 —— 弱机上光 GC 就能把画面拖垮。
     * 这里固定两张 Bitmap 轮换：UI 正在画 A 时我们写 B，写完再换。极端情况下可能出现
     * 一道撕裂，换掉绝大部分 GC 压力是划算的。
     */
    private fun render(source: ImageReader) {
        val image = try {
            source.acquireLatestImage()
        } catch (t: Throwable) {
            onState("取帧异常：${t.javaClass.simpleName}: ${t.message}")
            null
        } ?: return
        try {
            val w = image.width
            val h = image.height
            if (w != width || h != height) {
                // 尺寸变了（旋转/重建）：重建缓冲
                width = w
                height = h
                pixels = IntArray(w * h)
                bitmapA = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                bitmapB = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            }
            val out = pixels ?: return
            yuvToArgb(image, out, w, h)
            val logFirst = !firstFrameLogged
            if (logFirst) firstFrameLogged = true
            flip = !flip
            val target = (if (flip) bitmapA else bitmapB) ?: return
            target.setPixels(out, 0, w, 0, 0, w, h)
            renderedFrames++
            if (logFirst) {
                val p1 = image.planes[1]
                // 一行把"几何 + 首像素"都说清楚，用来区分三种可能：
                //   · yRow/uvRow/img 不对 → 换算输入就不对（几何/布局问题）
                //   · p00 已经是绿色 → 换算出来的像素就是错的（解码或换算问题）
                //   · p00 正常但 b00 不同 → setPixels/Bitmap 这层出了问题
                onState(
                    "首帧 ${w}x$h yRow=${image.planes[0].rowStride} uvRow=${p1.rowStride} " +
                        "uvPix=${p1.pixelStride} p00=#${String.format("%06X", out[0] and 0xFFFFFF)} " +
                        "b00=#${String.format("%06X", target.getPixel(0, 0) and 0xFFFFFF)}"
                )
            }
            onFrame(target)
        } catch (t: Throwable) {
            onState("转码异常：${t.javaClass.simpleName}: ${t.message}")
        } finally {
            runCatching { image.close() }
        }
    }

    /**
     * YUV_420_888 → ARGB_8888。
     *
     * 必须按 `rowStride` / `pixelStride` 索引：不同厂商给的 plane 布局不一样
     * （有的带行尾 padding、有的 UV 交错），按 `width/2` 硬算会得到花屏或越界。
     * 色彩矩阵用 BT.601 有限范围（编码器默认），与 scrcpy 的输出一致。
     */
    private fun yuvToArgb(image: Image, out: IntArray, w: Int, h: Int) {
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        val yBuf = yPlane.buffer
        val uBuf = uPlane.buffer
        val vBuf = vPlane.buffer
        val yArr = ByteArray(yBuf.remaining()).also { yBuf.get(it) }
        val uArr = ByteArray(uBuf.remaining()).also { uBuf.get(it) }
        val vArr = ByteArray(vBuf.remaining()).also { vBuf.get(it) }

        val yRow = yPlane.rowStride
        val uvRow = uPlane.rowStride
        val uvPix = uPlane.pixelStride
        val uvMax = uArr.size - 1
        val uvMaxV = vArr.size - 1

        // Y 平面的实际长度可能是 `rowStride * (h-1) + w`（最后一行不带行尾 padding）。
        // 按 j*rowStride 硬算会在最后一行越界，这里把基点夹到"仍能放下 w 个像素"的位置。
        val yLimit = (yArr.size - w).coerceAtLeast(0)

        var i = 0
        for (j in 0 until h) {
            val yBase = (j * yRow).coerceAtMost(yLimit)
            val uvBase = (j shr 1) * uvRow
            val x2 = w shr 1
            for (x in 0 until x2) {
                val uvIndex = uvBase + x * uvPix
                val u = (uArr[if (uvIndex <= uvMax) uvIndex else uvMax].toInt() and 0xFF) - 128
                val v = (vArr[if (uvIndex <= uvMaxV) uvIndex else uvMaxV].toInt() and 0xFF) - 128
                val rAdd = 1.596f * v
                val gAdd = -0.392f * u - 0.813f * v
                val bAdd = 2.017f * u
                // 一对像素共用一组 UV
                for (k in 0 until 2) {
                    val luma = (yArr[yBase + x * 2 + k].toInt() and 0xFF) - 16
                    val yf = if (luma > 0) luma * 1.164f else 0f
                    val r = (yf + rAdd).toInt().let { if (it < 0) 0 else if (it > 255) 255 else it }
                    val g = (yf + gAdd).toInt().let { if (it < 0) 0 else if (it > 255) 255 else it }
                    val b = (yf + bAdd).toInt().let { if (it < 0) 0 else if (it > 255) 255 else it }
                    out[i++] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                }
            }
            // 奇数宽：最后一列单独处理
            if (w and 1 == 1) {
                val x = w - 1
                val yv = (yArr[yBase + x].toInt() and 0xFF) - 16
                val yf = if (yv > 0) yv * 1.164f else 0f
                val uvIndex = uvBase + (x shr 1) * uvPix
                val u = (uArr[if (uvIndex <= uvMax) uvIndex else uvMax].toInt() and 0xFF) - 128
                val v = (vArr[if (uvIndex <= uvMaxV) uvIndex else uvMaxV].toInt() and 0xFF) - 128
                val r = (yf + 1.596f * v).toInt().let { if (it < 0) 0 else if (it > 255) 255 else it }
                val g = (yf - 0.392f * u - 0.813f * v).toInt().let { if (it < 0) 0 else if (it > 255) 255 else it }
                val b = (yf + 2.017f * u).toInt().let { if (it < 0) 0 else if (it > 255) 255 else it }
                out[i++] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
    }

    /**
     * 选一个 AVC 解码器：**能用软件的就用软件的**。
     *
     * 为什么不是 `createDecoderByType`：那条路会把厂商硬解排在前面，而厂商硬解对
     * csd 的形态（起始码 / SPS+PPS 是否同包 / 是否需要 crop 信息）要求各不相同，
     * 不满足时不会报错，只会**解出花屏** —— 真机上一模一样地踩过。
     * 软件解码器对同一份 Annex-B 流是确定的，360×640 的开销也完全够用。
     */
    private fun createAvcDecoder(preferSoftware: Boolean): MediaCodec {
        if (!preferSoftware) return MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        val prefer = listOf("c2.android.avc.decoder", "OMX.google.h264.decoder")
        val available = runCatching {
            android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS)
                .codecInfos
                .filter { info ->
                    !info.isEncoder && info.supportedTypes.any { it.equals(MediaFormat.MIMETYPE_VIDEO_AVC, true) }
                }
                .map { it.name }
        }.getOrDefault(emptyList())
        val picked = prefer.firstOrNull { name -> available.any { it == name } }
        return if (picked != null) {
            MediaCodec.createByCodecName(picked)
        } else {
            MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        }
    }

    /**
     * 释放解码器与取帧线程。可重复调用。
     *
     * ★ **不 join**：调用方是页面（主线程）。断开连接 / 切模式 / 离开页面都会调到这里，
     * 而解码线程可能正卡在 `releaseOutputBuffer` 里（ImageReader 满了要等取帧线程），
     * 在主线程上等它就是拿一次 ANR 换一次释放。这里只负责"让它知道该停了"：
     * 置位 + 清队列 + interrupt 叫醒 `poll`，收尾在解码线程自己身上（循环退出前 releaseAll）。
     *
     * 状态位先置（`released`）再动 `running`：重复调用时第二次直接返回，不会重复关 codec。
     */
    fun release() {
        if (!released.compareAndSet(false, true)) return
        running = false
        queue.clear()
        // interrupt 只是为了立刻叫醒 queue.poll；MediaCodec 的 native 调用不受它影响
        runCatching { decodeThread.interrupt() }
        runCatching { renderThread.quitSafely() }
    }

    private fun releaseAll() {
        runCatching { reader?.setOnImageAvailableListener(null, null) }
        runCatching { reader?.close() }
        reader = null
        val c = codec ?: return
        codec = null
        runCatching { c.stop() }
        runCatching { c.release() }
    }

    private companion object {
        const val INPUT_TIMEOUT_US = 10_000L
        /** 输入缓冲等待次数：50 × 10ms = 0.5s，再拿不到就是解码器真卡住了 */
        const val INPUT_RETRIES = 50
        /** 单帧上限 2MB：正常 848p 帧 < 200KB，超过就是流错位了 */
        const val MAX_FRAME_BYTES = 2 * 1024 * 1024
        const val QUEUE_OFFER_TIMEOUT_MS = 500L
        /** 丢帧后最多等多久的 IDR（等不到就恢复喂帧，宁可花屏不冻屏） */
        const val RESYNC_TIMEOUT_MS = 3_000L
        /** 待解码帧队列容量（≈ 20fps 下 5 秒），同时也是内存上限 */
        const val QUEUE_CAPACITY = 96
    }
}
