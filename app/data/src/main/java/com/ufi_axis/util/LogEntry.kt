package com.ufi_axis.util

/**
 * 双端统一日志模型（2026-08-27 新建）。
 *
 * 重构前的问题：APP 侧 [DebugLog] 只写 Logcat，日志页读的却是 core 的 `/api/debug-logs`，
 * 两套东西同名不同源 —— 「APP 日志」在设备上根本看不到，「网络日志」只有 DEBUG 构建才进 Logcat。
 * 现在两端都归一到本模型：
 * - APP 侧运行日志/网络日志由 [DebugLog] 写入 [AppLogBuffer]；
 * - core 侧日志经 `GET /api/debug-logs` 拉回字符串行，由 [parseCoreLine] 解析成同一结构。
 *
 * core 行格式由 `com.ufi_axis_core.util.AppLogger.log()` 决定：`HH:mm:ss.SSS [LEVEL] [TAG] message`。
 * **该格式同时被 web 端 SettingsView 解析，改格式必须三端同步**，所以这里只做解析、不改契约。
 *
 * 运行/网络的区分靠 tag 前缀 `NET`（见 [LogKind.of]）：两端的网络日志 tag 统一写成 `NET/xxx`。
 */
enum class LogSource(val label: String) {
    /** 手机 App 自身（本进程内存缓冲） */
    APP("APP"),

    /** 设备端 core 服务（HTTP 拉取） */
    CORE("core")
}

/** 与 core `AppLogger.LogLevel` 逐字一致，便于双向解析。 */
enum class LogLevel(val label: String) {
    DEBUG("D"), INFO("I"), WARN("W"), ERROR("E");

    companion object {
        fun of(name: String): LogLevel =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: INFO
    }
}

/** 日志种类：运行程序日志 / 网络请求日志。 */
enum class LogKind(val label: String) {
    RUNTIME("运行"), NETWORK("网络");

    companion object {
        /** tag 以 `NET` 开头即网络日志（约定：`NET/HTTP`、`NET/goform`…）。 */
        fun of(tag: String): LogKind =
            if (tag == NET_TAG || tag.startsWith("$NET_TAG/")) NETWORK else RUNTIME

        /** 网络日志 tag 前缀，两端共用。 */
        const val NET_TAG = "NET"
    }
}

/**
 * 一条日志。[seq] 仅用于 LazyColumn 的稳定 key 与选区排序，不参与展示。
 */
data class LogEntry(
    val seq: Long,
    val time: String,
    val source: LogSource,
    val level: LogLevel,
    val kind: LogKind,
    val tag: String,
    val message: String
) {
    /** 还原为 core 的行格式，用于复制 / 导出（两端一致，便于粘贴给他人排查）。 */
    fun format(): String = "$time [${level.name}] [$tag] $message"

    companion object {
        private val CORE_LINE = Regex(
            """^(\d{2}:\d{2}:\d{2}\.\d{3})\s+\[(\w+)]\s+\[([^]]*)]\s?([\s\S]*)$"""
        )

        /**
         * 解析 core 返回的一行。
         *
         * 格式不匹配时**不丢弃**：整行当 message、级别按内容猜。core 侧 `AppLogger.e` 会把堆栈
         * 单独 log 一次，那一条同样是标准格式（message 内含换行），所以这里允许 message 跨行。
         */
        fun parseCoreLine(raw: String, seq: Long): LogEntry {
            val m = CORE_LINE.matchEntire(raw)
            if (m == null) {
                val level = when {
                    "[ERROR]" in raw -> LogLevel.ERROR
                    "[WARN]" in raw -> LogLevel.WARN
                    else -> LogLevel.INFO
                }
                return LogEntry(seq, "", LogSource.CORE, level, LogKind.RUNTIME, "-", raw)
            }
            val tag = m.groupValues[3]
            return LogEntry(
                seq = seq,
                time = m.groupValues[1],
                source = LogSource.CORE,
                level = LogLevel.of(m.groupValues[2]),
                kind = LogKind.of(tag),
                tag = tag,
                message = m.groupValues[4]
            )
        }
    }
}
