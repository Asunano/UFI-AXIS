package com.ufi_axis.ui.components.common

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 弹窗串行闸：**同一时刻只允许一个弹窗在场**（2026-11-15）。
 *
 * ## 要解决的现象
 * App 与 Core 同时有新版本时，先弹 Core 的更新提示；点「稍后」关闭它，
 * 紧接着 App 的更新提示出现，但**背景是花的** —— 前一个弹窗的离场 ramp 还没跑完，
 * 两个 `Dialog` 窗口在同一帧里叠着，后者的 backdrop（窗口级模糊/dim）作用在一个
 * 正在销毁的窗口之上。两个窗口各写各的 Window 属性，谁后写谁生效，视觉上就是"背景异常"。
 *
 * ## 为什么在 [UfiDialogShell] 里收口
 * 全库 133 个弹窗调用点里有 85 个是 `if (show) { UfiXxxDialog(...) }` 这种硬挂载。
 * 逐个调用点去排队既改不完，也防不住以后新增的；而所有弹窗最终都经过
 * [UfiDialogShell] 这一个 `Dialog(...)` 出口 —— 在那里做互斥，等于对全库生效。
 *
 * ## 语义
 * - 只有持有者能渲染；后来的排队（`tryAcquire` 返回 false，弹窗不组合自己的内容）。
 * - 持有者**完全离场**（组合被销毁）后才释放，正好覆盖"离场 ramp 期间窗口还活着"那段。
 * - 释放时 `generation` 自增，排队者据此重试（它是 Compose 状态，能触发重组）。
 *
 * 这里刻意不排队**多个**等待者：同时挤三个以上弹窗本身就是设计问题，
 * 让它们按"谁先重组谁先拿到"的顺序依次进场即可。
 */
internal object UfiDialogArbiter {

    /** 当前持有者；`null` 表示没有弹窗在场。 */
    private var owner: Any? = null

    /**
     * 释放次数。等待中的弹窗把它当 key 观察：持有者一释放就重试。
     *
     * 用自增计数而不是布尔：两个等待者同时被唤醒时，需要有一个明确的"变了"信号。
     */
    internal var generation by mutableStateOf(0)
        private set

    /** 尝试取得独占权；已持有则幂等返回 true。 */
    fun tryAcquire(token: Any): Boolean {
        val current = owner
        if (current == null) {
            owner = token
            return true
        }
        return current === token
    }

    /** 释放独占权（只有持有者释放才生效）。 */
    fun release(token: Any) {
        if (owner === token) {
            owner = null
            generation++
        }
    }
}
