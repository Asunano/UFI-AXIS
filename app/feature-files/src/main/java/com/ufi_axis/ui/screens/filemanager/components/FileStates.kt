package com.ufi_axis.ui.screens.filemanager.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.ufi_axis.ui.components.common.UfiListEmptyState
import com.ufi_axis.ui.components.common.UfiListErrorState
import com.ufi_axis.ui.components.common.UfiListLoadingState

/**
 * 文件管理器空/加载/错误状态组件集（T6）。
 *
 * 2026-09-16：三个形态全部**转发公共层**
 * （[UfiListLoadingState] / [UfiListEmptyState] / [UfiListErrorState]）。
 * 本文件现在只剩"文件管理器的文案与参数"这一层薄壳 —— 骨架条数、空态版式、错误态按钮
 * 的实现只有公共层那一份。
 *
 * 历史沿革（别再走回头路）：
 *  · 最初这里手搓 5 张静态灰卡，与公共 `UfiSkeleton` 的扫光骨架并存，同一个 App 两种骨架观感；
 *  · 2026-09-04 加载态改成转发 `UfiSkeletonList`（骨架统一）；
 *  · 2026-09-16 媒体库拆成三页时同样需要这三态，于是把「何时骨架、空态长什么样、
 *    错误态带不带重试」这套编排一并上提，错误态的裸 M3 `Button` 顺势换成 `UfiButton`。
 *
 * 保留这三个薄壳函数而不是让 `FileManagerRoot` 直接调公共件：文案（"暂无文件"）与
 * 骨架条数是**本页的决定**，散到调用点后下次改文案要在几个 when 分支里找。
 *
 * 用法约束不变：无状态，根布局接受外部 [Modifier]（调用方通常传 `Modifier.weight(1f)`），
 * 且因为位于 `Column` 作用域内，内部不使用 `Modifier.align()`。
 */

/**
 * 加载态：首屏骨架屏（6 条，40dp 圆形前置槽 —— 与 [FileRowCard] 的 40dp 图标容器同形）。
 *
 * @param modifier 根布局修饰符（调用方通常传 `Modifier.weight(1f)`）
 */
@Composable
fun FileLoadingState(modifier: Modifier = Modifier) {
    UfiListLoadingState(modifier = modifier)
}

/**
 * 错误态：居中显示错误信息，可选附带「重试」按钮。
 *
 * @param message 错误文案（通常用 [com.ufi_axis.viewmodel.state.FileManagerState.errorMessage]）
 * @param onRetry 重试回调；为 `null` 时不显示按钮
 * @param modifier 根布局修饰符（调用方通常传 `Modifier.weight(1f)`）
 */
@Composable
fun FileErrorState(
    message: String,
    onRetry: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    UfiListErrorState(message = message, modifier = modifier, onRetry = onRetry)
}

/**
 * 空态：居中显示「这个目录是空的」。
 *
 * 文案不用"暂无文件"：目录为空与"筛选/搜索没命中"是两件事，前者该说清"是这个目录空"。
 *
 * @param modifier 根布局修饰符（调用方通常传 `Modifier.weight(1f)`）
 */
@Composable
fun FileEmptyState(modifier: Modifier = Modifier) {
    UfiListEmptyState(text = "这个目录是空的", modifier = modifier)
}
