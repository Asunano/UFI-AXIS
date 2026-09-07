package com.ufi_axis_core.util

/**
 * `:app:ui` 的日志出口桥。
 *
 * ## 为什么需要它
 * `:app:ui` 是纯 UI 模块，**不依赖** `:app:data`，因此拿不到 APP 侧的 `DebugLog`
 * （唯一能写进「设置 → 关于设备 → 调试日志」那个页面的入口）。在此之前 `:app:ui` 里
 * 只能用 `android.util.Log`，日志只进 Logcat，应用内完全看不到 —— 排查 UI 层问题时
 * 必须接电脑抓 logcat，很不方便。
 *
 * 本对象只做一件事：提供一个可注入的 sink。`:core:common` 同时被 `:app:ui` 与
 * `:app:data` 依赖，所以放在这里两边都能触达，且不会引入任何新的模块依赖
 * （与同目录的 [UiFrameGate] 同款做法）。
 *
 * ## 接线
 * 在 `:app`（application 模块，能同时看到 `DebugLog`）的启动路径里注入一次：
 * ```
 * UiLogBridge.sink = { tag, msg -> DebugLog.w(tag, msg) }
 * ```
 * 用 `w` 而不是 `i`：`DebugLog.i` 受「详细日志」开关约束，`w` 无条件记录，
 * 用户不必先去打开任何开关就能看到。
 *
 * ## 线程与开销
 * 未注入时 [w] 是一次空判断，可以放心留在生产代码里。`@Volatile` 保证
 * 注入对其它线程立即可见（注入发生在主线程，读取可能来自动画协程）。
 */
object UiLogBridge {

    /** 日志接收端。参数为 `(tag, message)`。未注入（`null`）时所有写入静默丢弃。 */
    @Volatile
    @JvmStatic
    var sink: ((String, String) -> Unit)? = null

    /** 写一条日志。未注入 sink 时为空操作。 */
    @JvmStatic
    fun w(tag: String, message: String) {
        sink?.invoke(tag, message)
    }

    /** 惰性求值版：sink 未注入时连字符串都不拼。 */
    inline fun w(tag: String, message: () -> String) {
        val target = sink ?: return
        target(tag, message())
    }
}
