package com.ufi_axis_core.media

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.ufi_axis_core.contract.WsDataTopic
import com.ufi_axis_core.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 媒体封面预热（2026-10-05 G6，FFmpeg 接入计划书 §4.2）。
 *
 * ## 它做什么
 * 开关打开时，逐条给 MediaStore 的**视频**用 ffmpeg 补封面：查 thumbCache 命中 → 跳过；
 * miss → `FfmpegThumbnailService.extractJpeg`（内部串行 + 20s 超时 + 有效性判定）→
 * 成功按 MediaRoutes 同一套布局落 `filesDir/thumbs/video_<id>.jpg`，
 * 失败写 `.fail` 标记（同 app 侧 6h 冷却语义）避免每轮都重试坏文件。
 *
 * ## 为什么在这里而不是 MediaRoutes / DataScheduler
 * - 它需要的判据（thumbCache 布局、FrameValidator、ffmpeg 服务）全在 `:core:api` 的
 *   media 层或本包内；DataScheduler（`:core:scheduler`）依赖不了 `:core:api`。
 * - 所以调度侧只负责「何时跑」（DataScheduler 收一个 attach 进来的挂起回调），
 *   「怎么跑」由本对象全权负责 —— 与 `attachTrafficLimitProvider` 同一个解耦口径。
 *
 * ## 让位原则
 * 预热永远让位给前台交互：每张之间 `delay(2000)`（由调度侧提供节流回调），且
 * ① 开关关（每次循环迭代现读 prefs，PUT 完下一轮生效）；
 * ② `thumb_prewarm_wifi_only` 开（默认）时要求「充电或电量 > 30%」（core 是网关设备，
 *    该开关语义 =「闲时才干活」，判据用 sticky 的 ACTION_BATTERY_CHANGED，零额外注册）；
 * ③ 有进行中的写操作（WriteGate.updateBusy，OTA 半写状态不叠加 CPU 大户）。
 * 三条任一不满足就整轮跳过。
 */
object ThumbPrewarmWorker {

    private const val TAG = "ThumbPrewarm"

    /** 失败冷却标记的生命周期（与 app 侧 MediaThumbnailBuilder 的 6h 语义一致）。 */
    private const val FAIL_COOLDOWN_MS = 6L * 60 * 60 * 1000

    /** 电量门：`thumb_prewarm_wifi_only` 开启时，未充电则要求电量高于此值。 */
    private const val PREWARM_MIN_BATTERY_PERCENT = 30

    private val running = AtomicBoolean(false)

    // ── 2026-10-07：进度持久化 ──
    // 之前进度只活在 WS 广播里，客户端退出页面再进来就"失忆"（无从得知上一轮跑没跑、
    // 跑到哪）。现在每处理一条都把快照落盘，任何客户端随时 GET 都能拿到真实状态。
    // 快照很小（一行 JSON），每条一次写盘可接受（预热本来就是秒级间隔的任务）。

    /** 持久化进度快照。 */
    data class Progress(
        val done: Int,
        val total: Int,
        val failed: Int,
        val running: Boolean,
        /** 当前正在抽帧的那条的文件名（running=false 时为上一轮最后一条）。 */
        val currentName: String = "",
        /** 本轮开始时刻（epoch ms）；0 = 未知（旧快照）。 */
        val startedAt: Long = 0,
        /** 快照写入时刻（epoch ms）——客户端据此区分"刚跑完"与"很久前的一轮"。 */
        val updatedAt: Long = 0
    ) {
        fun toJson(): String = """{"done":$done,"total":$total,"failed":$failed,""" +
            """"running":$running,"current_name":"${currentName.replace("\\", "\\\\").replace("\"", "\\\"")}",""" +
            """"started_at":$startedAt,"updated_at":$updatedAt}"""

        companion object {
            fun fromJson(text: String): Progress? = runCatching {
                // 手写极简解析：字段固定、无嵌套，避免为一个快照引入 JSON 依赖
                fun str(key: String): String =
                    text.substringAfter("\"$key\":\"", "").substringBefore('"')
                fun num(key: String): Long =
                    text.substringAfter("\"$key\":", "").substringBefore(',').trim().toLongOrNull() ?: 0L
                Progress(
                    done = num("done").toInt(),
                    total = num("total").toInt(),
                    failed = num("failed").toInt(),
                    running = text.contains("\"running\":true"),
                    currentName = str("current_name"),
                    startedAt = num("started_at"),
                    updatedAt = num("updated_at")
                )
            }.getOrNull()
        }
    }

    /** 最新进度快照（内存镜像；进程被杀后由 [readPersisted] 恢复）。 */
    @Volatile
    var lastProgress: Progress? = null
        private set

    private fun snapshotFile(context: Context): File = File(context.filesDir, "thumbs/prewarm_progress.json")

    /** 读持久化快照（进程重启后恢复用）。文件不存在/损坏 = null。 */
    fun readPersisted(context: Context): Progress? =
        lastProgress ?: runCatching {
            snapshotFile(context).takeIf { it.isFile }?.readText()?.let { Progress.fromJson(it) }
        }.getOrNull().also { lastProgress = it }

    private fun persist(context: Context, p: Progress) {
        lastProgress = p
        runCatching {
            val f = snapshotFile(context)
            f.parentFile?.mkdirs()
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(p.toJson())
            if (!tmp.renameTo(f)) tmp.delete()
        }
    }

    /**
     * 跑一轮预热。由 DataScheduler 的 idle 循环周期性调用（其 scope 取消时协程随之取消）。
     *
     * @param throttledPerItem 每处理完一张后等待一次（调用方给 `delay(2000)`），
     *   预热让位给前台交互的节流点。
     * @param broadcast 进度广播（`data_changed` + [WsDataTopic.MEDIA_THUMB_PREWARM]）。
     *   每处理一张发一次；无订阅者时 WebSocketManager 内部直接丢弃，调用方无需自己判断。
     */
    suspend fun runOnce(
        context: Context,
        throttledPerItem: suspend () -> Unit,
        broadcast: suspend (done: Int, total: Int, failed: Int, running: Boolean, currentName: String) -> Unit,
        /** 2026-10-07：手动触发（POST /media/thumbnail-prewarm）时忽略闲时门 —— 用户点按钮就是要现在跑。 */
        forceIdle: Boolean = false
    ) {
        // start/stop 互斥：调度循环的周期比一轮可能跑的时间短，重入会双份抽帧
        if (!running.compareAndSet(false, true)) return
        try {
            val settings = com.ufi_axis_core.util.AppSettings.getInstance(context)
            // ① 总开关：每轮现读 prefs，PUT 完下一轮生效（与 goform_dump_enabled 同口径）
            if (!settings.thumbPrewarmEnabled) return

            // ② 闲时门：充电 或 电量 > 30%（手动触发 forceIdle 时跳过；总开关仍生效）
            if (!forceIdle && settings.thumbPrewarmWifiOnly && !isIdleEnough(context)) {
                AppLogger.d(TAG, "预热跳过：非闲时（未充电且电量<=${PREWARM_MIN_BATTERY_PERCENT}%）")
                return
            }

            // ③ 更新流程占用中让路（OTA 拷贝与软解叠一起，1.5GB 内存设备顶不住）
            if (com.ufi_axis_core.api.routes.WriteGate.updateBusy()) return

            val thumbDir = File(context.filesDir, "thumbs").apply { if (!isDirectory) mkdirs() }
            val ids = listVideoIds(context)
            if (ids.isEmpty()) return
            val names = idToName(context)

            var done = 0
            var failed = 0
            val startedAt = System.currentTimeMillis()
            AppLogger.i(TAG, "预热开始：${ids.size} 个视频")
            persist(context, Progress(0, ids.size, 0, true, startedAt = startedAt, updatedAt = startedAt))
            broadcast(done, ids.size, failed, true, "")
            var writeGateAborted = false
            for (id in ids) {
                // ③ WriteGate 每条复查（审查修复）：大库一轮可达数十分钟，
                // OTA 可能在循环中途开始——撞上即中止本轮，下轮闲时再续
                if (com.ufi_axis_core.api.routes.WriteGate.updateBusy()) {
                    writeGateAborted = true
                    AppLogger.i(TAG, "预热中止：OTA 写操作开始（已处理 $done 条）")
                    break
                }
                when {
                    hasCache(thumbDir, id) -> Unit                       // 已有图（回传/上轮成果）
                    isCoolingDown(thumbDir, id) -> Unit                  // 6h 内失败过
                    else -> {
                        // 进度快照带上当前条目名，客户端能看到"正在处理 xxx.mkv"
                        persist(context, Progress(done, ids.size, failed, true,
                            currentName = names[id] ?: "", startedAt = startedAt,
                            updatedAt = System.currentTimeMillis()))
                        val ok = prewarmOne(context, thumbDir, id)
                        if (ok) done++ else failed++
                        persist(context, Progress(done, ids.size, failed, true,
                            currentName = names[id] ?: "", startedAt = startedAt,
                            updatedAt = System.currentTimeMillis()))
                        broadcast(done, ids.size, failed, true, names[id] ?: "")
                        throttledPerItem()                               // 节流：让位前台
                    }
                }
            }
            val finishedAt = System.currentTimeMillis()
            AppLogger.i(TAG, "预热结束：新增 $done / 失败 $failed / 共 ${ids.size}" +
                (if (writeGateAborted) "（OTA 中止）" else ""))
            persist(context, Progress(done, ids.size, failed, false,
                startedAt = startedAt, updatedAt = finishedAt))
            broadcast(done, ids.size, failed, false, "")
        } catch (e: kotlinx.coroutines.CancellationException) {
            // 被取消（调度 scope 关闭/服务停止）：快照标成非 running，别让客户端永远等"预热中"
            runCatching {
                persist(context, (lastProgress ?: Progress(0, 0, 0, false)).copy(running = false))
            }
            throw e
        } catch (e: Exception) {
            // 预热是纯锦上添花，任何一轮的异常都不该冒泡打断调度循环
            AppLogger.w(TAG, "预热轮异常: ${e.javaClass.simpleName}: ${e.message}")
            runCatching {
                persist(context, (lastProgress ?: Progress(0, 0, 0, false)).copy(running = false))
            }
        } finally {
            running.set(false)
        }
    }

    /** id → 文件名映射（一次查询，进度快照显示"正在处理谁"用）。失败返回空表（快照无名可显）。 */
    private fun idToName(context: Context): Map<Long, String> = runCatching {
        val out = mutableMapOf<Long, String>()
        context.contentResolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME),
            null, null,
            "${MediaStore.MediaColumns._ID} ASC"
        )?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val nameCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            while (c.moveToNext()) out[c.getLong(idCol)] = c.getString(nameCol) ?: ""
        }
        out
    }.getOrDefault(emptyMap())

    /** 闲时判据：充电中（含 FULL）或电量 > [PREWARM_MIN_BATTERY_PERCENT]。 */
    private fun isIdleEnough(context: Context): Boolean {
        return runCatching {
            val sticky = context.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
                ?: return@runCatching false
            val status = sticky.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1)
            val charging = status == android.os.BatteryManager.BATTERY_STATUS_CHARGING ||
                status == android.os.BatteryManager.BATTERY_STATUS_FULL
            if (charging) return@runCatching true
            val level = sticky.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1)
            val scale = sticky.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1)
            level >= 0 && scale > 0 && level * 100 / scale > PREWARM_MIN_BATTERY_PERCENT
        }.getOrDefault(false)
    }

    /** MediaStore 全部视频 id（升序）。查询失败 = 这轮放弃，不让权限瞬态炸掉调度循环。 */
    private fun listVideoIds(context: Context): List<Long> = runCatching {
        val ids = mutableListOf<Long>()
        // 预热按 id 升序逐条铺：顺序稳定，中断后下一轮从断点附近自然续上
        context.contentResolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.MediaColumns._ID),
            null, null,
            "${MediaStore.MediaColumns._ID} ASC"
        )?.use { c ->
            val col = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            while (c.moveToNext()) ids.add(c.getLong(col))
        }
        ids
    }.getOrDefault(emptyList())

    private fun cacheFile(thumbDir: File, id: Long): File = File(thumbDir, "video_$id.jpg")

    private fun hasCache(thumbDir: File, id: Long): Boolean =
        cacheFile(thumbDir, id).let { it.isFile && it.length() > 0 }

    /** `.fail` 标记是否仍在冷却期。 */
    private fun isCoolingDown(thumbDir: File, id: Long): Boolean {
        val mark = failMark(thumbDir, id)
        if (!mark.isFile) return false
        val until = runCatching { mark.readText().trim().toLongOrNull() ?: 0L }.getOrDefault(0L)
        if (System.currentTimeMillis() >= until) {
            mark.delete()   // 冷却过了顺手清掉，目录不积垃圾
            return false
        }
        return true
    }

    private fun failMark(thumbDir: File, id: Long): File = File(thumbDir, "video_$id.fail")

    /** 单条预热：ffmpeg 抽帧 → 有效性判定（G3 四判据）→ 原子落盘 / 失败写冷却标记。 */
    private suspend fun prewarmOne(context: Context, thumbDir: File, id: Long): Boolean {
        val uri: Uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)
        val bytes = FfmpegThumbnailService.extractJpeg(context, uri, size = 256, atSeconds = 0.0)
        val verdict = FrameValidator.validate(bytes, allowDark = true)
        if (verdict !is FrameValidator.Verdict.Valid &&
            verdict !is FrameValidator.Verdict.TooDark
        ) {
            // Blank/TooSmall/Undecodable：写冷却标记，6h 内不再碰这条（PUT /thumbnail 拒收的是同一组判据）
            // 原子写（审查修复）：半写的 .fail 会把时间戳写坏 → toLongOrNull()=0 → 冷却失效
            val mark = failMark(thumbDir, id)
            mark.parentFile?.mkdirs()
            val tmpMark = File(mark.parentFile, mark.name + "." + java.util.UUID.randomUUID() + ".tmp")
            tmpMark.writeText((System.currentTimeMillis() + FAIL_COOLDOWN_MS).toString())
            if (!tmpMark.renameTo(mark)) tmpMark.delete()
            AppLogger.w(TAG, "预热失败($id): $verdict")
            return false
        }
        return withContext(Dispatchers.IO) {
            runCatching {
                val target = cacheFile(thumbDir, id)
                // 先写临时文件再 rename：中途被杀不会留下半张图被 GET 当缓存命中（口径同 PUT /thumbnail）
                val tmp = File(target.parentFile, "${target.name}.tmp")
                tmp.writeBytes(bytes!!)
                if (!tmp.renameTo(target)) {
                    tmp.delete()
                    // rename 失败（极罕见，如跨文件系统）直接写目标，半写风险小于永远出不了图
                    target.writeBytes(bytes)
                }
                true
            }.getOrElse {
                AppLogger.w(TAG, "预热落盘失败($id): ${it.message}")
                false
            }
        }
    }
}
