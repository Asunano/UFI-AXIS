package com.ufi_axis.util

import android.content.Context
import android.os.Environment
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * 详细通信报错日志落盘（诊断「服务无法链接」的核心手段）。
 *
 * 根目录：`Download/UFI-AXIS/log`
 *   - 优先使用公共 Download 目录（用户可直接通过文件管理器查看）；
 *   - 不可用时回退到应用内部 `appContext.filesDir/ufi_axis_log`；
 *   - 目录不存在时 `mkdirs()`。
 *
 * 每次失败写入：
 *   1. 个体文件 `api_error_<yyyy-MM-dd_HH-mm-ss>.log`（含完整字段 + 完整 stacktrace）；
 *   2. 追加到滚动文件 `api_error.log`（便于一次性翻看所有历史）。
 * 对 `api_error_` 个体文件按修改时间裁剪（上限 [MAX_INDIVIDUAL_FILES]，删最旧），
 * 滚动文件超 [ROLLING_MAX_BYTES] 时截掉头部只留尾部 [ROLLING_KEEP_BYTES]。
 *
 * 同时 emit 一行 `DebugLog.e("ApiError", 摘要)` 便于 logcat 快速定位。
 *
 * 设计约束（来自需求）：
 *   - 全部逻辑包裹在 [runCatching]，绝不抛异常、绝不阻塞主流程；
 *   - 调用方应在 IO 协程（或 OkHttp 网络线程）内调用，本对象不做线程切换；
 *   - 同 key 在 [DEBOUNCE_MS] 内去抖，避免重试中间态 / 轮询失败刷屏。
 *
 * 2026-09-04（日志噪音治理，实测样本 `log-2.zip`）：
 * 1. **去抖只记「最后一个 key」**，`lastWriteKey == key` 才算重复 —— 设备离线时
 *    `HTTP_REQUEST` 与 `WS_CONNECT` 交替失败，两个 key 互相把对方顶掉，**去抖完全失效**：
 *    2 秒一份新文件，24 秒写了 12 份。改为按 key 各自记时间戳。
 * 2. **去抖窗口 3s 太短**：断网期间轮询每 2s 一轮，同一个 ConnectException 一分钟能写 20 次。
 *    窗口放大到 60s，与 core `AppLogger` 的重复折叠窗口一致。
 * 3. **滚动文件 `api_error.log` 完全没有上限**（只有个体文件有 100 份的裁剪）。
 */
object ApiErrorLogger {

    private const val BASE_DIR_NAME = "UFI-AXIS"
    private const val LOG_SUBDIR = "log"
    private const val ROLLING_FILE = "api_error.log"
    private const val INDIVIDUAL_PREFIX = "api_error_"
    private const val MAX_INDIVIDUAL_FILES = 20
    private const val RESPONSE_BODY_LIMIT = 2048
    private const val DEBOUNCE_MS = 60_000L
    private const val DEBOUNCE_MAP_MAX = 256
    private const val ROLLING_MAX_BYTES = 1_048_576L
    private const val ROLLING_KEEP_BYTES = 256 * 1024

    private lateinit var appContext: Context

    private val timestampFmt = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)
    private val isoFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    /** 去抖：每个 key 各自记最近一次写入时间（不是全局只记最后一个 key）。 */
    private val lastWriteAt = ConcurrentHashMap<String, Long>()

    /** 在 [android.app.Application.onCreate] 中尽早调用，提供 fallback 写盘所需的 Context。 */
    fun init(context: Context) {
        runCatching { appContext = context.applicationContext }
    }

    /** 阶段标签。 */
    enum class Stage { HTTP_REQUEST, WS_CONNECT, PARSE, AUTH }

    // ───────────────────────── 公开记录入口 ─────────────────────────

    /**
     * 记录一次 HTTP 请求失败（由 [com.ufi_axis.data.api.RetrofitClient] 的 errorCaptureInterceptor 调用）。
     *
     * @param stage        阶段标签（HTTP_REQUEST / AUTH ...）
     * @param baseUrl      目标 baseUrl（如 `http://192.168.0.1:8088/`）
     * @param method       请求方法（GET/POST/...）
     * @param url          完整请求 URL
     * @param errorType    OkHttp 错误类型（如 `CONNECT_FAILED` / `TIMEOUT` / `HTTP_401`）
     * @param exception    异常（非 2xx 时可为 null）
     * @param httpStatus   HTTP 状态码（非 2xx 时有值）
     * @param responseBody 响应体前 2KB（非 2xx 时有值）
     * @param requestLine  请求行（method+URL，Authorization 已脱敏）
     */
    fun logHttp(
        stage: Stage,
        baseUrl: String,
        method: String,
        url: String,
        errorType: String,
        exception: Throwable?,
        httpStatus: Int? = null,
        responseBody: String? = null,
        requestLine: String
    ) {
        // 2026-08-28：受日志开关约束。此前这里无条件写盘 ——「日志总开关关了但
        // Download/UFI-AXIS/log 还在长文件」正是从这来的。关就是关。
        if (!DebugLog.active) return
        val key = "$stage|$errorType|${httpStatus ?: ""}|${exception?.message ?: ""}"
        if (isDebounced(key)) return

        val summary = buildString {
            append("[${stage.name}] ")
            if (httpStatus != null) append("HTTP $httpStatus ")
            append("$method ${redactAuth(url)}")
            append(" -> $errorType")
            exception?.message?.let { append(": ${redactAuth(it)}") }
        }

        runCatching {
            val dir = logDir() ?: return@runCatching
            val now = Date()
            val stamp = timestampFmt.format(now)
            val file = File(dir, "${INDIVIDUAL_PREFIX}${stamp}_${stage.name}.log")
            val content = buildContent(
                now, stage, baseUrl, method, url, errorType, exception, httpStatus, responseBody, requestLine
            )
            writeFile(file, content)
            appendRolling(dir, now, content)
            trimIndividualFiles(dir)
        }

        DebugLog.e("ApiError", summary)
    }

    /** WebSocket 连接失败（由 WebSocketRepository.onFailure 调用）。 */
    fun logWs(
        baseUrl: String,
        errorType: String,
        exception: Throwable?,
        requestLine: String
    ) {
        logHttp(
            stage = Stage.WS_CONNECT,
            baseUrl = baseUrl,
            method = "WS",
            url = baseUrl,
            errorType = errorType,
            exception = exception,
            requestLine = requestLine
        )
    }

    /** 通信报文解析失败（如 WebSocket 下发的 JSON 解析异常）。 */
    fun logParse(
        baseUrl: String,
        url: String,
        exception: Throwable?,
        responseBody: String? = null
    ) {
        logHttp(
            stage = Stage.PARSE,
            baseUrl = baseUrl,
            method = "PARSE",
            url = url,
            errorType = "PARSE_ERROR",
            exception = exception,
            responseBody = responseBody,
            requestLine = "PARSE $url"
        )
    }

    // ───────────────────────── 目录与写盘 ─────────────────────────

    /**
     * 解析日志根目录。
     * @return 可用目录；若连 fallback 都无法准备则返回 null（调用方跳过文件写盘，仅走 DebugLog）。
     */
    private fun logDir(): File? {
        val publicDir = runCatching {
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        }.getOrNull()
        if (publicDir != null) {
            val dir = File(publicDir, "$BASE_DIR_NAME${File.separator}$LOG_SUBDIR")
            if (dir.exists() || dir.mkdirs()) return dir
        }
        if (this::appContext.isInitialized) {
            val fallback = File(appContext.filesDir, "ufi_axis_log")
            if (!fallback.exists()) fallback.mkdirs()
            return fallback
        }
        return null
    }

    private fun buildContent(
        now: Date,
        stage: Stage,
        baseUrl: String,
        method: String,
        url: String,
        errorType: String,
        exception: Throwable?,
        httpStatus: Int?,
        responseBody: String?,
        requestLine: String
    ): String = buildString {
        appendLine("===== UFI-AXIS API ERROR =====")
        appendLine("Time       : ${isoFmt.format(now)}")
        appendLine("Stage      : ${stage.name}")
        appendLine("BaseUrl    : ${redactAuth(baseUrl)}")
        appendLine("Request    : ${redactAuth(requestLine)}")
        appendLine("Method     : $method")
        appendLine("URL        : ${redactAuth(url)}")
        appendLine("ErrorType  : $errorType")
        if (httpStatus != null) appendLine("HttpStatus : $httpStatus")
        appendLine("Exception  : ${exception?.javaClass?.name ?: "(none)"}")
        appendLine("Message    : ${exception?.message?.let { redactAuth(it) } ?: "(none)"}")
        appendLine()
        appendLine("----- StackTrace -----")
        exception?.let { t ->
            runCatching {
                val sw = java.io.StringWriter()
                t.printStackTrace(PrintWriter(sw))
                // T18：堆栈首行是 `类名: message`，message 里可能带 URL query 的 token —— 必须同样脱敏，
                // 否则 Message 行打了码、堆栈里又把原文写回文件。
                appendLine(redactAuth(sw.toString()))
            }.onFailure { appendLine("(stacktrace unavailable: ${redactAuth(it.message ?: "")})") }
        } ?: appendLine("(no exception object)")
        if (!responseBody.isNullOrBlank()) {
            appendLine()
            appendLine("----- Response Body (first $RESPONSE_BODY_LIMIT bytes) -----")
            appendLine(redactAuth(responseBody.take(RESPONSE_BODY_LIMIT)))
        }
        appendLine()
    }

    private fun writeFile(file: File, content: String) {
        runCatching {
            FileWriter(file, false).use { it.write(content) }
        }
    }

    private fun appendRolling(dir: File, now: Date, content: String) {
        runCatching {
            val rolling = File(dir, ROLLING_FILE)
            // 超上限时截头留尾（与 core 的 DownloadLog 同一策略）：滚动文件此前**毫无上限**，
            // 只靠个体文件的份数裁剪，设备长时间离线就是一个只增不减的大文件。
            if (rolling.length() > ROLLING_MAX_BYTES) {
                val bytes = rolling.readBytes()
                rolling.writeBytes(
                    bytes.copyOfRange((bytes.size - ROLLING_KEEP_BYTES).coerceAtLeast(0), bytes.size)
                )
            }
            FileWriter(rolling, true).use { writer ->
                writer.appendLine("──────────── ${isoFmt.format(now)} ────────────")
                writer.append(content)
            }
        }
    }

    /** 对 `api_error_` 个体文件按修改时间升序裁剪，超出上限删除最旧的。 */
    private fun trimIndividualFiles(dir: File) {
        runCatching {
            val files = dir.listFiles { f ->
                f.isFile && f.name.startsWith(INDIVIDUAL_PREFIX) && f.name.endsWith(".log")
            }?.sortedBy { it.lastModified() } ?: return
            val excess = files.size - MAX_INDIVIDUAL_FILES
            if (excess > 0) files.take(excess).forEach { runCatching { it.delete() } }
        }
    }

    // ───────────────────────── 去抖与脱敏 ─────────────────────────

    private fun isDebounced(key: String): Boolean {
        val now = System.currentTimeMillis()
        val last = lastWriteAt[key]
        if (last != null && now - last < DEBOUNCE_MS) return true
        // 上限保护：key 含 URL/异常消息，基数理论无界；满了整体清空而不是 LRU，
        // 代价只是下一轮少去抖几条（与 AppLogger.repeatMap 同一策略）。
        if (lastWriteAt.size >= DEBOUNCE_MAP_MAX) lastWriteAt.clear()
        lastWriteAt[key] = now
        return false
    }

    /**
     * 脱敏凭据字段。**直接复用 [DebugLog.desensitize]**，与 core 的 `AppLogger` 同一份正则（T18）。
     *
     * 原先此处是第三份独立正则（`token\s*[=:]`），在 JSON 形态 `"token":"abc"` 下匹配不上
     * （引号在冒号之前），且不含 `password` 关键字 —— 与 T16 修掉的缺陷同类。
     * 禁止在此重新实现正则：任何脱敏规则变更只改 `DebugLog` / `AppLogger` 两处同源副本。
     */
    internal fun redactAuth(input: String): String = DebugLog.desensitize(input)
}
