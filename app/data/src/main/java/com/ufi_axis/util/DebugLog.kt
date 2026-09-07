package com.ufi_axis.util

import android.util.Log

/**
 * APP 侧统一日志入口。
 *
 * 2026-08-27 重构（双端日志）：
 * 1. **每条日志同时进 [AppLogBuffer]**，日志页的「APP」页签才有内容可看 —— 在此之前这里只写
 *    Logcat，App 自己的日志在设备上完全看不到，只能接 adb。
 * 2. **[enabled] 默认改为 false**。原来硬编码 `true`，而 [AppPreferences.debugMode] 默认 false 且
 *    只有 setter 会回灌，导致每次冷启动不管用户关没关，`d/i/json` 一律照打，直到用户手动碰一次
 *    开关或 `refreshDeviceConfig()` 从 core 回读配置才纠正。现在由
 *    `UfiAxisApplication.onCreate()` 显式 `DebugLog.enabled = prefs.debugMode` 完成启动同步。
 * 3. 新增 [net] 网络日志入口（tag 前缀 [LogKind.NET_TAG]），与运行日志在 UI 上可分开筛选。
 * 4. `d`/`i` 增加 lambda 重载：日志关闭时**不构造消息字符串**。原来 140 处调用无条件做字符串
 *    拼接/序列化（只有 `DashboardModule` 一处手工加了 `if (DebugLog.enabled)` 守卫），
 *    每 5s 一次的仪表盘轮询就在白烧 CPU。新代码请用 `DebugLog.d("Tag") { "..." }`。
 *
 * 级别策略与 core 的 `AppLogger` 对齐：`w`/`e` 无条件记录（关掉日志也要留错误痕迹），
 * `d`/`i`/`json` 受 [enabled] 控制。
 */
object DebugLog {
    private const val GLOBAL_TAG = "UFI-AXIS"

    /**
     * 日志总开关（真源 [AppPreferences.logEnabled]，**默认 false**，与 core 的 `log_enabled` 同一语义）。
     *
     * false = **两端一条都不记**：不写 Logcat、不进 [AppLogBuffer]、不落盘，连脱敏正则都不跑。
     *
     * 2026-09-04：初值由 true 改为 false，与持久化默认值对齐。进程内初值**不得比持久化默认值更宽松**
     * （与 core `AppLogger` 同一条规则）—— 否则「Application.onCreate 同步之前」这段窗口里
     * 日志照写，而用户的选择是"关"。崩溃日志不走这里，[com.ufi_axis.crash.CrashHandler] 直接写文件。
     */
    @Volatile
    var masterEnabled = false
        set(value) {
            field = value
            // 关掉后释放落盘文件句柄（与 core 的 AppLogger.stopWriting 对称）
            if (!active) AppFileLogger.closeWriters()
        }

    /**
     * app 端子开关（真源 [AppPreferences.appLogEnabled]，默认 true，对应 core 配置 `app_log_enabled`）。
     *
     * 与 [masterEnabled] 是「与」关系。core 侧对应的是 `AppLogger.setCoreLogEnabled`，
     * 两侧分开是为了能「只关一侧」—— core 常驻写盘，通常先关它。
     */
    @Volatile
    var appEnabled = true
        set(value) {
            field = value
            if (!active) AppFileLogger.closeWriters()
        }

    /** app 实际是否记录日志：总开关与 app 子开关都开才算。 */
    val active: Boolean get() = masterEnabled && appEnabled

    /**
     * 调试日志开关（详细级别）。真源是 [AppPreferences.debugMode]（其 setter 会同步这里），
     * 启动同步在 `UfiAxisApplication.onCreate()`。**不要**直接改这个值做持久化开关。
     *
     * 只控制 DEBUG/INFO；WARN/ERROR 不受它影响（但受 [active] 影响）。
     */
    @Volatile
    var enabled = false

    /**
     * 敏感字段脱敏：将日志中的 token / secret / password / bearer 等值替换为 ***，
     * 避免凭据泄漏到 logcat。支持 "token":"..."、Bearer xxx、password=xxx 等形态，
     * 同时保留关键字前缀（如 token）仅对值打码。
     *
     * 正则与 core 的 `com.ufi_axis_core.util.AppLogger` 逐字一致（T16 已把两端对齐）；
     * 值字符类排除 `&` / `;`，避免 `secret=def&token=ghi` 被整段吞掉。
     */
    private val SENSITIVE_PATTERN = Regex(
        "(?i)(token|secret|password|bearer)\\b[ \\t]*[\"']?[ \\t]*:?[ \\t]*=?[ \\t]*" +
            "(?:\"[^\"']*|'[^ \"']*|[^\"'\r\n\\s,;&}]{1,128})"
    )

    internal fun desensitize(msg: String): String {
        if (msg.isEmpty()) return msg
        return SENSITIVE_PATTERN.replace(msg) { match -> "${match.groupValues[1]}***" }
    }

    /** 是否记录 DEBUG/INFO 级：总开关 + app 子开关 + 详细开关都开才算。lambda 重载据此跳过消息构造。 */
    val verbose: Boolean get() = active && enabled

    // ── 运行日志 ──

    fun d(tag: String, msg: String) {
        if (verbose) emit(LogLevel.DEBUG, tag, msg)
    }

    fun i(tag: String, msg: String) {
        if (verbose) emit(LogLevel.INFO, tag, msg)
    }

    fun w(tag: String, msg: String, tr: Throwable? = null) {
        emit(LogLevel.WARN, tag, msg, tr)
    }

    fun e(tag: String, msg: String, tr: Throwable? = null) {
        emit(LogLevel.ERROR, tag, msg, tr)
    }

    /** 延迟求值版：日志关闭时连消息都不拼。新代码优先用这个。 */
    inline fun d(tag: String, msg: () -> String) {
        if (verbose) d(tag, msg())
    }

    /** 延迟求值版：日志关闭时连消息都不拼。新代码优先用这个。 */
    inline fun i(tag: String, msg: () -> String) {
        if (verbose) i(tag, msg())
    }

    // ── 网络日志 ──

    /**
     * 网络请求日志。tag 会被加上 [LogKind.NET_TAG] 前缀，日志页据此归到「网络」类。
     * WARN/ERROR 只受总开关约束（请求失败必须留痕），DEBUG/INFO 还需要详细开关。
     */
    fun net(level: LogLevel, tag: String, msg: String) {
        if (level >= LogLevel.WARN || verbose) {
            emit(level, "${LogKind.NET_TAG}/$tag", msg)
        }
    }

    /** HTTP/WS 响应体快照（超长截断）。 */
    fun json(tag: String, url: String, json: String) {
        if (!verbose) return
        val truncated = if (json.length > 2000) json.take(2000) + "..." else json
        emit(LogLevel.DEBUG, "${LogKind.NET_TAG}/JSON/$tag", "$url\n$truncated")
    }

    /** 反序列化失败：只要总开关开着就记录，附原始响应体摘要便于对齐契约。 */
    fun parseError(tag: String, url: String, responseBody: String, error: Throwable) {
        emit(
            LogLevel.ERROR,
            "${LogKind.NET_TAG}/PARSE/$tag",
            "URL: $url\nBody: ${responseBody.take(3000)}",
            error
        )
    }

    /** 唯一出口：开关 → 重复折叠 → 脱敏 → Logcat → 内存缓冲。 */
    private fun emit(level: LogLevel, tag: String, msg: String, tr: Throwable? = null) {
        // 开关：关闭后连脱敏正则都不跑（高频日志下正则才是真开销）
        if (!active) return
        // 重复折叠：设备离线时同一条失败日志会按轮询节拍无限重复（实测 2s 一条，
        // 一次 20 分钟离线写出 600 行一模一样的 ConnectException）。
        val gated = repeatGate(level, tag, msg) ?: return
        val safe = desensitize(gated)
        val logcatTag = "$GLOBAL_TAG/$tag"
        when (level) {
            LogLevel.DEBUG -> Log.d(logcatTag, safe)
            LogLevel.INFO -> Log.i(logcatTag, safe)
            LogLevel.WARN -> Log.w(logcatTag, safe, tr)
            LogLevel.ERROR -> Log.e(logcatTag, safe, tr)
        }
        // 异常堆栈并进同一条，否则日志页里「错误」和「它的堆栈」会被筛选拆开
        val buffered = if (tr == null) safe else "$safe\n${tr.stackTraceToString()}"
        AppLogBuffer.add(level, tag, buffered)
    }

    // ── 重复行折叠（2026-09-04，与 core `AppLogger.repeatGate` 同一策略）──
    // app 侧此前完全没有这层闸门：`DebugLog.net(ERROR, ...)` 对每个失败请求写一行，
    // 设备离线 / WiFi 抖动期间轮询不停重试，`error.log` 与 `net.log` 里几百行一字不差。
    // 只按「完全相同的 (级别, tag, 整条消息)」折叠，带变量的日志（耗时、URL 参数）不受影响。
    private const val REPEAT_WINDOW_MS = 60_000L
    private const val REPEAT_MAP_MAX = 256

    private class RepeatState(var windowStart: Long, var count: Int)

    private val repeatMap = java.util.concurrent.ConcurrentHashMap<String, RepeatState>()

    /** @return null = 抑制；非 null = 应输出的消息（窗口翻页时带上被折叠次数）。 */
    private fun repeatGate(level: LogLevel, tag: String, message: String): String? {
        val now = System.currentTimeMillis()
        val key = "${level.name}|$tag|$message"
        val existing = repeatMap[key]
        if (existing == null) {
            // 满了整体清空而不是 LRU：代价只是下一轮少折叠几条，换实现简单且无锁竞争
            if (repeatMap.size >= REPEAT_MAP_MAX) repeatMap.clear()
            repeatMap[key] = RepeatState(now, 0)
            return message
        }
        synchronized(existing) {
            if (now - existing.windowStart < REPEAT_WINDOW_MS) {
                existing.count++
                return null
            }
            val suppressed = existing.count
            existing.windowStart = now
            existing.count = 0
            return if (suppressed > 0) "$message  <上一窗口内重复 $suppressed 次已折叠>" else message
        }
    }
}
