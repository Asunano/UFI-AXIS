package com.ufi_axis.installer.logging

/** 一条日志 */
data class LogLine(
    val timestamp: Long,
    val level: Level,
    val message: String
) {
    enum class Level { INFO, OK, WARN, ERROR }

    /** 与 bat 日志风格对齐：[HH:mm:ss.SSS] [LEVEL] message */
    fun format(): String = "[${LogLineFormat.time(timestamp)}] [${level.name}] $message"

    fun formatForUi(): String = format()
}

internal object LogLineFormat {
    private val fmt = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US)

    fun time(millis: Long): String = synchronized(fmt) { fmt.format(java.util.Date(millis)) }

    fun stamp(millis: Long): String {
        val f = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
        return f.format(java.util.Date(millis))
    }
}
