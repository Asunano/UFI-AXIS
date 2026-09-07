package com.ufi_axis.ui.screens.filemanager.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ufi_axis.ui.theme.LocalResolvedPalette
import androidx.compose.foundation.layout.fillMaxWidth
import com.ufi_axis.ui.components.common.UfiSkeletonList
import com.ufi_axis.ui.theme.Spacing

/**
 * 文件管理器空/加载/错误状态组件集（T6）。
 *
 * 三个组件均完全无状态，且根布局接受并应用外部传入的 [modifier]，便于调用方
 * 通过 `Modifier.weight(1f)` 在 [androidx.compose.foundation.layout.Column] 中占满剩余空间。
 *
 * 设计系统约束：
 *  - 颜色统一走 [com.ufi_axis.ui.theme.LocalResolvedPalette.current]，禁止 `colorScheme.tertiary`。
 *  - 这些组件位于 [androidx.compose.foundation.layout.Column] 作用域，禁止 `Modifier.align()`，
 *    居中统一通过 [Box] 的 `contentAlignment` 或 [Column] 的 `Arrangement`/`horizontalAlignment` 实现。
 */

/**
 * 加载态：首屏骨架屏。
 *
 * 2026-09-04：本函数原来自己手搓了 5 张卡 × (40dp 图标块 + 两条文字条)，用的是
 * `palette.divider` 纯色 —— 形状对，但是**静态死灰块**，且与公共 `UfiSkeleton` 那套
 * 扫光骨架并存，同一个 App 里出现两种骨架观感。现在整体转发公共组件
 * [com.ufi_axis.ui.components.common.UfiSkeletonList]（同款左图标 + 两行文字条比例，
 * 外加统一的 shimmer 与「降低动效」降级），本页不再持有第二份实现。
 *
 * 条数由 5 提到 6：公共默认值，铺满一屏更完整；差一条不构成观感回归。
 *
 * @param modifier 根布局修饰符（调用方通常传 `Modifier.weight(1f)`）
 */
@Composable
fun FileLoadingState(modifier: Modifier = Modifier) {
    UfiSkeletonList(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.CardHorizontalMargin, vertical = 8.dp)
    )
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
    val palette = LocalResolvedPalette.current
    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(text = message, color = palette.warning)
        if (onRetry != null) {
            Button(
                onClick = onRetry,
                colors = ButtonDefaults.buttonColors(
                    containerColor = palette.accent,
                    contentColor = palette.onAccent
                )
            ) {
                Text(text = "重试")
            }
        }
    }
}

/**
 * 空态：居中显示「暂无文件」。
 *
 * @param modifier 根布局修饰符（调用方通常传 `Modifier.weight(1f)`）
 */
@Composable
fun FileEmptyState(modifier: Modifier = Modifier) {
    val palette = LocalResolvedPalette.current
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Text(text = "暂无文件", color = palette.textSecondary)
    }
}
