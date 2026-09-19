package com.ufi_axis.installer.goform

/**
 * 轻量日志包装：优先走 Android [android.util.Log]，非 Android 环境（如 JVM 单测）回退到 println。
 * 这样库模块既能在安装器里正常打日志，也能在纯 JVM 测试里跑而不抛 NoClassDefFoundError。
 */
internal object GoformLog {
    private const val PREFIX = "UFI-GOFORM"

    fun d(tag: String, msg: String) {
        try { android.util.Log.d(PREFIX, "[$tag] $msg") } catch (_: Throwable) { println("D/$PREFIX [$tag] $msg") }
    }

    fun i(tag: String, msg: String) {
        try { android.util.Log.i(PREFIX, "[$tag] $msg") } catch (_: Throwable) { println("I/$PREFIX [$tag] $msg") }
    }

    fun w(tag: String, msg: String) {
        try { android.util.Log.w(PREFIX, "[$tag] $msg") } catch (_: Throwable) { println("W/$PREFIX [$tag] $msg") }
    }

    fun e(tag: String, msg: String, t: Throwable? = null) {
        try {
            if (t != null) android.util.Log.e(PREFIX, "[$tag] $msg", t) else android.util.Log.e(PREFIX, "[$tag] $msg")
        } catch (_: Throwable) {
            println("E/$PREFIX [$tag] $msg ${t?.message ?: ""}")
        }
    }
}
