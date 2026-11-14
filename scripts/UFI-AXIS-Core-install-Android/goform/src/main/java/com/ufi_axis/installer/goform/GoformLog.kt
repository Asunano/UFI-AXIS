package com.ufi_axis.installer.goform

/**
 * 轻量日志包装：优先走 Android [android.util.Log]，非 Android 环境（如 JVM 单测）回退到 println。
 * 这样库模块既能在安装器里正常打日志，也能在纯 JVM 测试里跑而不抛 NoClassDefFoundError。
 *
 * 另外提供 [sink]：上层（安装器）可以把 WARN/ERROR 同时接到自己的安装日志里，
 * 否则「登录被拒」这类关键原因只留在 logcat，用户在「查看日志」里看不到。
 */
object GoformLog {
    private const val PREFIX = "UFI-GOFORM"

    enum class Level { DEBUG, INFO, WARN, ERROR }

    /** 外部日志汇。由上层设置，只接 WARN/ERROR 级别，避免刷屏。 */
    @Volatile
    var sink: ((Level, String) -> Unit)? = null

    internal fun d(tag: String, msg: String) {
        try { android.util.Log.d(PREFIX, "[$tag] $msg") } catch (_: Throwable) { println("D/$PREFIX [$tag] $msg") }
    }

    internal fun i(tag: String, msg: String) {
        try { android.util.Log.i(PREFIX, "[$tag] $msg") } catch (_: Throwable) { println("I/$PREFIX [$tag] $msg") }
    }

    internal fun w(tag: String, msg: String) {
        try { android.util.Log.w(PREFIX, "[$tag] $msg") } catch (_: Throwable) { println("W/$PREFIX [$tag] $msg") }
        emit(Level.WARN, "[$tag] $msg")
    }

    internal fun e(tag: String, msg: String, t: Throwable? = null) {
        try {
            if (t != null) android.util.Log.e(PREFIX, "[$tag] $msg", t) else android.util.Log.e(PREFIX, "[$tag] $msg")
        } catch (_: Throwable) {
            println("E/$PREFIX [$tag] $msg ${t?.message ?: ""}")
        }
        emit(Level.ERROR, "[$tag] $msg${if (t != null) "（${t.message}）" else ""}")
    }

    private fun emit(level: Level, text: String) {
        try { sink?.invoke(level, text) } catch (_: Throwable) { }
    }
}
