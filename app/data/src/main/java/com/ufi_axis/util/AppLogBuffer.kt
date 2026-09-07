package com.ufi_axis.util

/**
 * APP 侧日志环形缓冲（2026-08-27 新建）。
 *
 * 在此之前 [DebugLog] 只往 Logcat 写，手机上看不到 App 自己的日志 —— 排障必须接 adb。
 * 现在每条日志同时进这个内存缓冲，日志页的「APP」页签直接读它。
 *
 * 容量与 core 的 `AppLogger.MAX_BUFFER_SIZE` 对齐（500 条），行为也对齐：
 * 只在内存里保留，进程退出即丢；需要长期留存的错误由 [ApiErrorLogger] 落盘。
 *
 * 线程安全：所有读写都在 [lock] 内，日志可能来自任意线程（OkHttp / WorkManager / 主线程）。
 * 这里**不暴露任何 Flow**：网络日志默认常开，逐条通知 UI 会让每写一条都触发一次
 * 500 元素快照 + 过滤重组。UI 侧按固定节拍主动调 [snapshot] 即可。
 */
object AppLogBuffer {

    private const val MAX_SIZE = 500

    private val lock = Any()
    private val entries = ArrayDeque<LogEntry>(MAX_SIZE)
    private var nextSeq = 0L

    fun add(level: LogLevel, tag: String, message: String) {
        val entry = LogEntry(
            seq = synchronized(lock) { nextSeq++ },
            time = nowTime(),
            source = LogSource.APP,
            level = level,
            kind = LogKind.of(tag),
            tag = tag,
            message = message
        )
        synchronized(lock) {
            entries.addLast(entry)
            while (entries.size > MAX_SIZE) entries.removeFirst()
        }
        // 落盘：app 侧所有日志都汇聚到这里，所以文件输出只需要挂这一处
        AppFileLogger.append(entry)
    }

    /** 旧 → 新顺序的快照（与 core `getBufferedLogs` 的顺序一致）。 */
    fun snapshot(): List<LogEntry> = synchronized(lock) { entries.toList() }

    fun clear() {
        synchronized(lock) { entries.clear() }
    }


    /**
     * `HH:mm:ss.SSS`，与 core 的时间格式逐字一致，双端日志混排时对齐。
     * 用 SimpleDateFormat 而非 java.time：本模块 minSdk 下 desugaring 可用但这里没必要引入，
     * 且格式化在日志热路径上，SimpleDateFormat 实例按线程缓存更省。
     */
    private val timeFormat = ThreadLocal.withInitial {
        java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US)
    }

    private fun nowTime(): String = timeFormat.get()!!.format(java.util.Date())
}
