package com.ufi_axis.data.download

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.ufi_axis.util.AppHttpClient
import com.ufi_axis.util.AppPreferences
import com.ufi_axis.util.DebugLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File

/**
 * 媒体下载 Worker：把队列（[MediaDownloadQueue]）里的文件逐个下到手机。
 *
 * ## 为什么是 WorkManager 而不是 ViewModel 里的协程
 * 用户要的是"退出 App 也继续下、进程被杀之后自动接上"。WorkManager 负责后两件事：
 * 任务记录在它自己的数据库里，进程死了会重新拉起本 Worker；而"下到哪了"由
 * 缓存里的 `.part` 分片文件 + `Range` 请求接上（core 的 `/api/files/stream` 支持 206）。
 * 这条路子不引入常驻服务、不设周期闹钟 —— 只有队列非空时才有一个前台任务在跑。
 *
 * ## 为什么先下到 cacheDir 再搬进公共目录
 * 公共 `Download/` 在 Android 10+ 只能通过 MediaStore 写，而 MediaStore 的输出流
 * **不支持随机续写**（IS_PENDING 期间也只能顺序写）。所以：
 * 分片续传发生在应用私有缓存里，下完整份之后一次性搬进
 * `Download/UFI-AXIS/Movies`（[MediaDownloadQueue.RELATIVE_DIR]），失败了下次从分片继续。
 *
 * ## 串行，不并发
 * 源头是随身 WiFi 上的 core（单核跑 Ktor + 一张 TF 卡）。两个流一起拉只会让两个都变慢，
 * 还会把它的内存挤爆。所以一次一个，快慢由局域网决定。
 */
class MediaDownloadWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        MediaDownloadQueue.ensureLoaded(applicationContext)
        ensureChannel()

        while (true) {
            if (isStopped) return@withContext Result.retry()
            val task = MediaDownloadQueue.nextPending() ?: break
            setForegroundSafe(task.name, 0)
            MediaDownloadQueue.update(task.path) {
                it.copy(status = MediaDownloadQueue.STATUS_RUNNING, error = "")
            }
            val outcome = runCatching { downloadOne(task) }
            outcome.fold(
                onSuccess = { message ->
                    MediaDownloadQueue.finish(applicationContext, task, ok = true, message = message)
                },
                onFailure = { e ->
                    if (isStopped) {
                        // 被系统收回：不算失败，回 retry 让 WorkManager 重新排（分片留着）
                        MediaDownloadQueue.update(task.path) {
                            it.copy(status = MediaDownloadQueue.STATUS_PENDING)
                        }
                        return@withContext Result.retry()
                    }
                    val reason = e.message ?: e.javaClass.simpleName
                    DebugLog.w(TAG, "下载失败: ${task.path} → $reason")
                    MediaDownloadQueue.markError(task.path, reason)
                }
            )
        }
        Result.success()
    }

    /**
     * 下一个文件。返回落点描述（写进历史，用户要知道文件去哪了）。
     *
     * 已有分片就带 `Range: bytes=N-` 续传，但**只有服务端确实回 206 才追加** ——
     * 回 200 说明它把整个文件从头发了一遍，此时追加会得到一个前半段重复的坏文件。
     */
    private suspend fun downloadOne(task: MediaDownloadQueue.Task): String {
        val prefs = AppPreferences(applicationContext)
        val url = "http://${prefs.effectiveHost}:${prefs.serverPort}/api/files/stream" +
            "?path=" + java.net.URLEncoder.encode(task.path, "UTF-8")

        val part = File(applicationContext.cacheDir, "mediadl_${keyOf(task.path)}.part")
        val existing = if (part.isFile) part.length() else 0L

        val request = Request.Builder().url(url).apply {
            if (existing > 0) header("Range", "bytes=$existing-")
        }.build()

        val response = AppHttpClient.instance.newCall(request).execute()
        response.use { resp ->
            if (!resp.isSuccessful) {
                throw IllegalStateException("HTTP ${resp.code}")
            }
            val partial = resp.code == 206
            if (existing > 0 && !partial) part.delete()
            val body = resp.body ?: throw IllegalStateException("响应无内容")
            val declared = body.contentLength().takeIf { it > 0 } ?: 0L
            val total = when {
                partial -> parseTotal(resp.header("Content-Range")) ?: (declared + existing)
                else -> declared
            }
            MediaDownloadQueue.update(task.path) {
                it.copy(total = total, received = if (partial) existing else 0L)
            }

            var received = if (partial) existing else 0L
            var lastNotified = 0L
            body.byteStream().use { input ->
                java.io.FileOutputStream(part, partial && existing > 0).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        if (isStopped) throw InterruptedException("已停止")
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        received += read
                        // 进度写盘/通知都节流：每 500ms 一次，否则 IO 全花在更新进度上
                        val now = System.currentTimeMillis()
                        if (now - lastNotified > PROGRESS_INTERVAL_MS) {
                            lastNotified = now
                            val done = received
                            MediaDownloadQueue.update(task.path) { it.copy(received = done) }
                            val percent = if (total > 0) (done * 100 / total).toInt() else 0
                            setForegroundSafe(task.name, percent)
                        }
                    }
                }
            }
        }

        val relativeDir = buildString {
            append(MediaDownloadQueue.RELATIVE_DIR)
            if (task.subDir.isNotBlank()) append('/').append(task.subDir.trim('/'))
        }
        try {
            saveToPublicDownloads(part, task.name, relativeDir)
        } finally {
            part.delete()
        }
        return relativeDir
    }

    /**
     * 搬进公共下载目录。
     *
     * `RELATIVE_PATH` 在 Android 10+ 由 MediaStore 负责建目录，所以"如果是文件夹就额外建
     * 文件夹"不需要我们自己 mkdir。API 28 及以下没有这个字段，退回直接写文件系统
     * （那个年代 WRITE_EXTERNAL_STORAGE 就够）。
     */
    private fun saveToPublicDownloads(source: File, fileName: String, relativeDir: String) {
        val mime = android.webkit.MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(fileName.substringAfterLast('.', ""))
            ?: "application/octet-stream"

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            val dir = File(
                android.os.Environment.getExternalStorageDirectory(),
                relativeDir
            ).apply { if (!isDirectory) mkdirs() }
            source.copyTo(File(dir, fileName), overwrite = true)
            return
        }

        val resolver = applicationContext.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, relativeDir)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("无法在 $relativeDir 创建文件")
        resolver.openOutputStream(uri)?.use { out ->
            source.inputStream().use { it.copyTo(out) }
        } ?: throw IllegalStateException("无法写入 $relativeDir/$fileName")
        values.clear()
        values.put(MediaStore.Downloads.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
    }

    private fun parseTotal(contentRange: String?): Long? =
        contentRange?.substringAfter('/', "")?.toLongOrNull()

    private fun keyOf(path: String): String = runCatching {
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(path.toByteArray())
            .take(8)
            .joinToString("") { "%02x".format(it) }
    }.getOrElse { Integer.toHexString(path.hashCode()) }

    /**
     * 提到前台并更新进度。
     *
     * `setForeground` 在部分场景会抛（通知权限被拒、系统限制前台启动）——
     * 那时下载仍然可以继续跑完，只是没有常驻通知，所以吞掉异常而不是中断任务。
     */
    private suspend fun setForegroundSafe(name: String, percent: Int) {
        runCatching { setForeground(foregroundInfo(name, percent)) }
    }

    private fun foregroundInfo(name: String, percent: Int): ForegroundInfo {
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("正在下载到手机")
            .setContentText(name)
            .setProgress(100, percent.coerceIn(0, 100), percent <= 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                NOTIFICATION_ID,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = applicationContext.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "媒体下载", NotificationManager.IMPORTANCE_LOW).apply {
                description = "把设备上的视频下载到手机时的进度通知"
                setShowBadge(false)
            }
        )
    }

    companion object {
        private const val TAG = "MediaDownload"
        private const val CHANNEL_ID = "media_download"
        private const val NOTIFICATION_ID = 9701
        private const val PROGRESS_INTERVAL_MS = 500L
        private const val WORK_NAME = "media-download-queue"

        /**
         * 唤起队列处理。
         *
         * `ExistingWorkPolicy.KEEP`：已经在跑就不要再排一个 —— 队列是共享的，
         * 第二个 Worker 只会和第一个抢同一批任务。约束只要"有网"：这是局域网传输，
         * 不该要求不计费网络（随身 WiFi 本身常被系统判成计费网络）。
         */
        fun kick(context: Context) {
            val request = OneTimeWorkRequestBuilder<MediaDownloadWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request)
        }

        /** 取消整条队列的执行（不清队列本身，用户可能只是想暂停）。 */
        fun stop(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }
}
