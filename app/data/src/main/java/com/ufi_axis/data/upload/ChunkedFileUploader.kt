package com.ufi_axis.data.upload

import com.ufi_axis.util.AppHttpClient
import com.ufi_axis.util.AppJson
import com.ufi_axis.util.DebugLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import java.io.File
import java.io.RandomAccessFile

/**
 * 文件上传执行器：**整体 + 分片（可续传）**两条路径，走哪条由设备端下发的能力位决定。
 *
 * ## 为什么 app 也要接分片
 * app 端原先只有整体上传：一次 multipart 打过去，断了从零重来，上限 200MB。
 * 传一个 GB 级视频时任何一次网络抖动都会让几分钟的上行白费，而且 200MB 直接挡死。
 * core 的三个分片端点（`upload/session|chunk|complete`）早就在了，web 端已接，
 * app 端此前一个都没用 —— 两端能力不对等，同一台设备上"网页能传、手机不能传"。
 *
 * ## 与 web 端实现保持同口径
 * `web/src/views/files/useFileUpload.ts` 是同一套语义的另一份实现，两边必须一致：
 * - 分片大小**取服务端下发的 `chunk_size`**，客户端不得自定（自定会让
 *   `received / chunk_size` 算出的下一片序号对不上，续传拼出坏文件）；
 * - `index` 由服务端回的 `received` 推导，不自己维护计数器 —— 续传与重试自然对齐；
 * - 会话过期回 **410**（不是 404），重开会话即可按 name+size 续上；
 * - `409` 表示顺序不匹配，服务端在 extra 里给 `received`，据此纠正后重试；
 * - 取消时必须 `DELETE session`，否则设备端留一份 `.ufipart` 垃圾。
 *
 * ## 为什么走裸 OkHttp 而不是 Retrofit
 * 分片 body 是**裸二进制**且要按偏移切片、还要逐字节回调进度。Retrofit 的
 * `@Body RequestBody` 能做到，但这条路径本来就已经在 `FileManagerModule` 里用
 * `AppHttpClient` 手搓（为了 `writeTo` 里推进度），再引一层 Retrofit 声明只会多一处要同步的地方。
 * 鉴权由 `AppHttpClient` 的拦截器统一注入，这里不碰 token。
 */
class ChunkedFileUploader(
    private val baseUrl: String,
    /** 进度回调：0f..1f。整体上传是单次推进，分片是"已完成字节 + 当前片已发"。 */
    private val onProgress: (Float) -> Unit
) {

    /** 设备端上传能力位（`GET /api/files/status`）。老固件只回权限字段，其余走默认值。 */
    data class Caps(
        val maxUploadBytes: Long = FALLBACK_MAX_UPLOAD,
        val supportsChunked: Boolean = false,
        val chunkSize: Long = 0L,
        val maxChunkedBytes: Long = FALLBACK_MAX_UPLOAD,
        /**
         * 设备端是否支持「上传到外部存储源」（手机 → core 暂存 → 远端）。
         *
         * 默认 **false**：老固件的 `/upload*` 一律没有 `remote:` 分支，带前缀的路径会被
         * `safeResolve` 判成非法路径回 400。这个位为 false 时 UI 必须把远端目录的上传入口
         * 整个撤掉，而不是让用户点一个必然失败的按钮。
         */
        val supportsRemoteUpload: Boolean = false,
        /**
         * 这份能力位是**真的从设备端读到的**吗。
         *
         * false = `/api/files/status` 那次探测失败了（超时 / 非 2xx / 解析失败），
         * 当前这份是默认值，不代表设备端的真实能力。
         *
         * 为什么必须有这一位：探测失败时所有能力位都是保守默认（`supportsRemoteUpload=false`），
         * 如果照着它报错就会告诉用户"设备端固件不支持"——而真相可能只是刚才那一次请求超时。
         * 把"不知道"和"知道它不支持"混成一句话，用户会去做完全无用的事（比如重刷设备端）。
         */
        val probed: Boolean = false
    ) {
        /** 单文件实际可传上限：支持分片时取分片上限，否则取整体上限。 */
        val effectiveLimit: Long get() = if (supportsChunked) maxChunkedBytes else maxUploadBytes
    }

    /**
     * 上传结果。`fileName` 是**设备端最终落盘的名字**（可能被自动改名）。
     *
     * @param staged 目标是远端存储源时为 true —— 此刻文件只到了 **core 的暂存目录**，
     *   还没到远端。调用方**不能**提示"上传完成"，要接着看推送作业（[pushJobId]）。
     * @param pushJobId 第二阶段（core → 远端）的作业 id，供轮询 `/api/files/remote-push`
     * @param destPath 最终目标路径（远端形态是 `remote:<id>/dir/name`）
     */
    sealed interface Result {
        data class Success(
            val fileName: String,
            val renamed: Boolean,
            val staged: Boolean = false,
            val pushJobId: String = "",
            val destPath: String = ""
        ) : Result
        data class Failure(val reason: String) : Result
    }

    private val client get() = AppHttpClient.instance

    /**
     * 读能力位。**本函数自己切到 IO 线程**，不依赖调用方的上下文。
     *
     * 2026-09-21 修 `NetworkOnMainThreadException`：这里原来是个只带 `suspend` 修饰、
     * 内部直接 `client.newCall(req).execute()`（同步阻塞）的函数。`suspend` 本身**不会**
     * 换线程，而调用它的 `uploadFilesToServer` 是在 `scope.launch { }`（viewModelScope，
     * 主调度器）里、又恰好由文件选择器的 `onActivityResult` 触发，于是这次请求直接跑在主线程上：
     * StrictMode 抛 `NetworkOnMainThreadException` → 被 `runCatching` 吞掉 → 回落成默认
     * `Caps()`（`supportsRemoteUpload=false`）→ 用户看到的却是"设备端固件不支持上传到
     * 外部存储源"。一个线程问题被报成了版本问题，而重试（同样在主线程）也永远不会成功。
     *
     * 失败会重试一次（隔 400ms）：它只是个几百字节的 GET，一次瞬时超时不该把整批上传判死。
     * 两次都失败时 [Caps.probed] 保持 false，由调用方按"能力未知"报错，不要说成"固件不支持"。
     */
    suspend fun loadCaps(): Caps = withContext(Dispatchers.IO) {
        repeat(CAPS_ATTEMPTS) { attempt ->
            val caps = probeCaps()
            if (caps != null) return@withContext caps
            if (attempt < CAPS_ATTEMPTS - 1) delay(CAPS_RETRY_DELAY_MS)
        }
        Caps()
    }

    /** 单次探测。返回 null = 这次没读到（调用方决定要不要重试）。 */
    private fun probeCaps(): Caps? = runCatching {
        val req = Request.Builder().url("$baseUrl/api/files/status").get().build()
        client.newCall(req).execute().use { res ->
            if (!res.isSuccessful) return@runCatching null
            val obj = AppJson.parseToJsonElement(res.body?.string() ?: "{}").jsonObject
            val chunk = obj["chunk_size"]?.jsonPrimitive?.longOrNull ?: 0L
            Caps(
                maxUploadBytes = obj["max_upload_bytes"]?.jsonPrimitive?.longOrNull ?: FALLBACK_MAX_UPLOAD,
                supportsChunked = (obj["supports_chunked_upload"]?.jsonPrimitive?.booleanOrNull == true) && chunk > 0,
                chunkSize = chunk,
                maxChunkedBytes = obj["max_chunked_upload_bytes"]?.jsonPrimitive?.longOrNull ?: FALLBACK_MAX_UPLOAD,
                supportsRemoteUpload = obj["supports_remote_upload"]?.jsonPrimitive?.booleanOrNull == true,
                probed = true
            )
        }
    }.getOrElse {
        DebugLog.w(TAG, "loadCaps failed: ${it.message}", it as? Exception)
        null
    }

    /**
     * 传一个本地文件。
     *
     * @param source 已落地的本地文件（调用方负责从 `Uri` 复制过来并在结束后删除）。
     *   分片路径需要**按偏移随机读**，所以必须是真实文件而不是 `InputStream`。
     * @param fileName 期望的目标文件名；目标已存在时服务端会自动改名，真实名字见返回值。
     */
    suspend fun upload(source: File, fileName: String, targetDir: String, caps: Caps): Result {
        val size = source.length()
        // 远端目标**必须**走分片：`/api/files/upload`（multipart 整体）在 core 侧没有
        // `remote:` 分支，路径带前缀会被 safeResolve 判成非法路径回 400。
        // 会话那条路径才认远端（落 core 暂存目录 + 收尾时排推送作业）。
        if (targetDir.startsWith(REMOTE_PREFIX)) {
            // 先把"没读到能力位"和"读到了、确实不支持"分开报 —— 前者是连接问题（重试有用），
            // 后者是设备端版本问题（重试无用、要更新固件）。混成一句话会让人去做完全无用的事。
            if (!caps.probed) {
                return Result.Failure("无法读取设备端上传能力（连接超时），请检查与设备的连接后重试")
            }
            if (!caps.supportsRemoteUpload) {
                return Result.Failure("设备端固件不支持上传到外部存储源，请更新设备端后重试")
            }
            if (!caps.supportsChunked) return Result.Failure("设备端不支持分片上传，无法上传到外部存储源")
            return uploadChunked(source, fileName, targetDir, caps.chunkSize)
        }
        // 小于一个分片的文件走整体：少两次往返，也避免"一片就完事"的多余会话
        val useChunked = caps.supportsChunked && size > caps.chunkSize
        return if (useChunked) uploadChunked(source, fileName, targetDir, caps.chunkSize)
        else uploadWhole(source, fileName, targetDir, size)
    }

    // ──────────────────────── 整体上传 ────────────────────────

    private suspend fun uploadWhole(source: File, fileName: String, targetDir: String, size: Long): Result {
        val mediaType = "application/octet-stream".toMediaTypeOrNull()!!
        var lastError = ""
        for (attempt in 0..MAX_ATTEMPTS - 1) {
            currentCoroutineContext().ensureActive()
            if (attempt > 0) {
                delay(RETRY_BASE_MS * attempt)
                onProgress(0f) // 整体重发从头开始，进度也得归零，否则条子会往回跳
            }
            val body = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("path", targetDir)
                .addFormDataPart("file", fileName, progressBody(source, mediaType, size, 0L, size))
                .build()
            val req = Request.Builder().url("$baseUrl/api/files/upload").post(body).build()
            client.newCall(req).execute().use { res ->
                if (res.isSuccessful) return Result.Success(fileName, renamed = false)
                if (res.code == 429 && attempt < MAX_ATTEMPTS - 1) return@use
                lastError = errorTextOf(res.code, res.body?.string())
                return Result.Failure(lastError)
            }
        }
        return Result.Failure(lastError.ifEmpty { "上传失败" })
    }

    // ──────────────────────── 分片上传 ────────────────────────

    private suspend fun uploadChunked(
        source: File,
        fileName: String,
        targetDir: String,
        chunkSizeHint: Long
    ): Result {
        val total = source.length()
        var sessionId = ""
        try {
            // 会话过期（410）时允许整体重来一次：重开会话会按 name+size 匹配到已有
            // `.ufipart` 并从 received 继续，所以"重来"的代价只是一次往返
            for (round in 0 until MAX_SESSION_ROUNDS) {
                currentCoroutineContext().ensureActive()
                val open = openSession(targetDir, fileName, total)
                    ?: return Result.Failure("无法创建上传会话")
                sessionId = open.sessionId
                val chunkSize = if (open.chunkSize > 0) open.chunkSize else chunkSizeHint
                var received = open.received
                if (received > 0) onProgress((received.toFloat() / total).coerceIn(0f, 0.99f))

                var expired = false
                while (received < total) {
                    currentCoroutineContext().ensureActive()
                    val index = received / chunkSize
                    val start = index * chunkSize
                    val end = minOf(start + chunkSize, total)
                    val base = received
                    val res = putChunk(sessionId, index, source, start, end, total, base)
                    when {
                        res.status == 410 -> { expired = true; break }
                        res.status == 409 -> {
                            // 服务端认的进度与本地不一致，以它为准后重试这一轮
                            val fixed = res.received
                            if (fixed >= 0 && fixed != received) received = fixed
                            else return Result.Failure(res.error.ifEmpty { "分片顺序不匹配" })
                        }
                        !res.ok -> return Result.Failure(res.error)
                        else -> {
                            received = if (res.received >= 0) res.received else received + (end - start)
                            onProgress((received.toFloat() / total).coerceIn(0f, 0.99f))
                        }
                    }
                }
                if (expired) continue // 重开会话续传

                return completeSession(sessionId, open.finalName, open.renamed)
            }
            return Result.Failure("上传会话反复过期，请重试")
        } catch (e: Exception) {
            // 协程被取消（用户点取消 / 页面销毁）也会走到这里：必须让设备端删掉 `.ufipart`，
            // 否则每次取消都在目标目录留一份垃圾（虽然有 TTL 兜底，但没必要等它）
            if (sessionId.isNotEmpty()) discardSession(sessionId)
            throw e
        }
    }

    private class OpenResult(
        val sessionId: String,
        val chunkSize: Long,
        val received: Long,
        val finalName: String,
        val renamed: Boolean
    )

    private fun openSession(targetDir: String, fileName: String, size: Long): OpenResult? = runCatching {
        // 用 buildJsonObject 而不是手拼字符串：路径与文件名里可能有引号、反斜杠、换行，
        // 手拼一定会在某个文件名上炸成非法 JSON。JsonObject.toString() 本身就是合法 JSON。
        val payload = buildJsonObject {
            put("path", JsonPrimitive(targetDir))
            put("name", JsonPrimitive(fileName))
            put("size", JsonPrimitive(size))
        }.toString()
        val req = Request.Builder()
            .url("$baseUrl/api/files/upload/session")
            .post(payload.toRequestBody(JSON_MEDIA))
            .build()
        client.newCall(req).execute().use { res ->
            if (!res.isSuccessful) return@runCatching null
            val obj = AppJson.parseToJsonElement(res.body?.string() ?: "{}").jsonObject
            val id = obj["session_id"]?.jsonPrimitive?.content ?: return@runCatching null
            OpenResult(
                sessionId = id,
                chunkSize = obj["chunk_size"]?.jsonPrimitive?.longOrNull ?: 0L,
                received = obj["received"]?.jsonPrimitive?.longOrNull ?: 0L,
                finalName = obj["file_name"]?.jsonPrimitive?.content ?: fileName,
                renamed = obj["renamed"]?.jsonPrimitive?.booleanOrNull == true
            )
        }
    }.getOrNull()

    private class ChunkResult(val ok: Boolean, val status: Int, val received: Long, val error: String)

    private fun putChunk(
        sessionId: String,
        index: Long,
        source: File,
        start: Long,
        end: Long,
        total: Long,
        base: Long
    ): ChunkResult = runCatching {
        val body = progressBody(source, OCTET_MEDIA, end - start, start, total, base)
        val req = Request.Builder()
            .url("$baseUrl/api/files/upload/chunk?session=$sessionId&index=$index")
            .put(body)
            .build()
        client.newCall(req).execute().use { res ->
            val raw = res.body?.string()
            val obj = runCatching { AppJson.parseToJsonElement(raw ?: "{}").jsonObject }.getOrNull()
            ChunkResult(
                ok = res.isSuccessful,
                status = res.code,
                received = obj?.get("received")?.jsonPrimitive?.longOrNull ?: -1L,
                error = if (res.isSuccessful) "" else errorTextOf(res.code, raw)
            )
        }
    }.getOrElse { ChunkResult(false, 0, -1L, it.localizedMessage ?: "分片上传失败") }

    private fun completeSession(sessionId: String, finalName: String, renamed: Boolean): Result = runCatching {
        val payload = "{\"session_id\":\"$sessionId\"}"
        val req = Request.Builder()
            .url("$baseUrl/api/files/upload/complete")
            .post(payload.toRequestBody(JSON_MEDIA))
            .build()
        client.newCall(req).execute().use { res ->
            val raw = res.body?.string()
            if (res.isSuccessful) {
                onProgress(1f)
                val obj = runCatching { AppJson.parseToJsonElement(raw ?: "{}").jsonObject }.getOrNull()
                // `staged: true` = 只到了 core 的暂存目录，第二阶段（core → 远端）才刚排上队。
                // 这个信号必须一路带到 UI：把它当成"上传完成"是最误导人的一种提示。
                Result.Success(
                    fileName = finalName,
                    renamed = renamed,
                    staged = obj?.get("staged")?.jsonPrimitive?.booleanOrNull == true,
                    pushJobId = obj?.get("push_job_id")?.jsonPrimitive?.content ?: "",
                    destPath = obj?.get("path")?.jsonPrimitive?.content ?: ""
                )
            } else {
                Result.Failure(errorTextOf(res.code, raw))
            }
        }
    }.getOrElse { Result.Failure(it.localizedMessage ?: "收尾失败") }

    /** 删会话（取消 / 异常时）。失败无所谓：设备端的 TTL 与 `/list` 惰性清理会兜住。 */
    private fun discardSession(sessionId: String) {
        runCatching {
            val req = Request.Builder()
                .url("$baseUrl/api/files/upload/session?session=$sessionId")
                .delete()
                .build()
            client.newCall(req).execute().close()
        }
    }

    // ──────────────────────── 公共工具 ────────────────────────

    /**
     * 按偏移读取 `[start, start+length)` 并在写出过程中推进度的 RequestBody。
     *
     * 用 [RandomAccessFile] 而不是 `inputStream().skip()`：skip 在部分实现上
     * 会退化成逐字节读，传到文件尾部的分片时每片都要重扫前面所有字节。
     *
     * `baseLoaded` 是"这一片之前已经确认收下的总字节"，用来把单片进度换算成整体进度。
     */
    private fun progressBody(
        source: File,
        type: MediaType,
        length: Long,
        start: Long,
        total: Long,
        baseLoaded: Long = 0L
    ): RequestBody = object : RequestBody() {
        override fun contentType() = type
        override fun contentLength() = length
        override fun writeTo(sink: BufferedSink) {
            RandomAccessFile(source, "r").use { raf ->
                raf.seek(start)
                val buf = ByteArray(BUFFER_SIZE)
                var written = 0L
                while (written < length) {
                    val want = minOf(BUFFER_SIZE.toLong(), length - written).toInt()
                    val n = raf.read(buf, 0, want)
                    if (n <= 0) break
                    sink.write(buf, 0, n)
                    written += n
                    // 每 64KB 推一次，和 web 端的采样密度相当；太密会让 UI 重组过于频繁
                    if (total > 0 && written % PROGRESS_STEP < BUFFER_SIZE) {
                        onProgress(((baseLoaded + written).toFloat() / total).coerceIn(0f, 0.99f))
                    }
                }
            }
        }
    }

    /**
     * 把失败响应翻成可读原因。
     *
     * core 的失败信封是 `{"error": "..."}`；413 那条由 StatusPages 给出，内容是英文的
     * `Request body (N bytes) exceeds …`。413 单独换成中文 —— 那句英文是给日志看的。
     */
    private fun errorTextOf(code: Int, raw: String?): String {
        val parsed = raw?.let {
            runCatching { AppJson.parseToJsonElement(it).jsonObject["error"]?.jsonPrimitive?.content }.getOrNull()
        }
        // 按 HTTP 状态码分类 —— 用户需要知道"是什么不让我传"，而不是一个数字
        return when (code) {
            400 -> "请求被拒绝：${parsed ?: "参数不合法"}"
            401 -> "设备端要求重新认证（配对已失效或密码被改）"
            403 -> "设备端拒绝了请求（权限不足或路径被禁止）"
            404 -> "目标目录不存在或已被删除"
            409 -> parsed ?: "同时进行的上传过多，请等已有任务完成后再试"
            413 -> "文件超过设备端的单文件上限（${parsed ?: "200MB/2GB"}）"
            429 -> "被设备端限流了，请稍后重试"
            507 -> "设备存储空间不足"
            in 500..599 -> "设备端内部错误（HTTP $code）：${parsed ?: "请查看设备端日志"}"
            else -> parsed?.takeIf { it.isNotBlank() } ?: "HTTP $code"
        }
    }

    private fun String.toRequestBody(type: MediaType): RequestBody =
        this.toByteArray().toRequestBody(type)

    companion object {
        private const val TAG = "ChunkedUpload"

        /** 老固件不回能力位时的保守上限（与 core 的 `FileRoutes.UPLOAD_BODY_LIMIT` 对齐）。 */
        const val FALLBACK_MAX_UPLOAD = 200L * 1024 * 1024

        private const val MAX_ATTEMPTS = 3
        private const val RETRY_BASE_MS = 1500L

        /** 会话过期后最多重开几次。设成 2 而不是无限：真反复过期说明有别的问题。 */
        private const val MAX_SESSION_ROUNDS = 2

        /** 远端存储源的路径前缀，与 core 的 `FileProviderRegistry.REMOTE_PREFIX` 同一个契约。 */
        private const val REMOTE_PREFIX = "remote:"

        /** 能力位探测的尝试次数（含首次）。 */
        private const val CAPS_ATTEMPTS = 2

        /** 能力位探测重试前的等待。短到用户感知不到，长到足够跨过一次瞬时抖动。 */
        private const val CAPS_RETRY_DELAY_MS = 400L

        private const val BUFFER_SIZE = 8192
        private const val PROGRESS_STEP = 65536L

        private val JSON_MEDIA: MediaType = "application/json; charset=utf-8".toMediaTypeOrNull()!!
        private val OCTET_MEDIA: MediaType = "application/octet-stream".toMediaTypeOrNull()!!
    }
}
