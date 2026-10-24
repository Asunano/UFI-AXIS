package com.ufi_axis.ui.components.common

import androidx.compose.runtime.mutableIntStateOf

/**
 * 「当前有几个弹窗开着」的**进程级**计数（2026-10-23）。
 *
 * ## 为什么需要它
 *
 * 胶囊底部导航栏是一个**独立窗口**（`UfiCapsuleBlurHost` 用 WindowManager 挂载，且刻意清掉
 * 了 `FLAG_DIM_BEHIND` —— 它是常驻浮层，永远不自己变暗）。而 Compose 的 `Dialog` 是另一个
 * 窗口，它的 scrim/模糊只作用于**Activity 窗口**，管不到上一层窗口。于是弹窗打开时：
 * 页面被压暗模糊、底栏却仍然亮着盖在弹窗上 —— 用户报的「配对弹窗背景模糊层级不对、
 * 主界面低栏仍然可见」就是这一条。
 *
 * 这套计数从 [UfiDialogShell]（所有公共弹窗的唯一入口）进出，供窗口级浮层（底栏）订阅：
 * 弹窗开着时底栏不渲染，关掉自动恢复。
 *
 * ## 为什么是进程级单例而不是 CompositionLocal
 *
 * 弹窗的内容跑在**另一个 ComposeView / 窗口**里，Activity 树上的 CompositionLocal 传不进去，
 * 而底栏也在第三个窗口里。只有进程级对象能同时被两边读到；用 `mutableIntStateOf` 是为了让
 * 订阅方（底栏）在计数变化时**自动重组**。
 */
object UfiDialogVisibility {
    private val open = mutableIntStateOf(0)

    /** 当前处于可见状态的弹窗数量。读取它的 Composable 会随计数变化重组。 */
    val openCount: Int get() = open.intValue

    /** 是否有任意弹窗在场。 */
    val isOpen: Boolean get() = open.intValue > 0

    internal fun push() {
        open.intValue += 1
    }

    internal fun pop() {
        if (open.intValue > 0) open.intValue -= 1
    }
}
