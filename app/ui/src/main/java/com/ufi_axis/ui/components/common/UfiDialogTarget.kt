package com.ufi_axis.ui.components.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/**
 * 弹窗目标的「最后一次非空值」（2026-11-08）。
 *
 * ## 解决什么问题
 *
 * 常见写法：
 * ```kotlin
 * target?.let { p -> UfiCustomDialog(visible = true, …) { … } }
 * ```
 * 只要把 `target` 置空，**整棵弹窗子树当帧就从组合里移除**，而 [UfiDialogShell] 的离场动画
 * （backdrop 逐渐清晰 → 卸载窗口）要求"`visible` 翻 false、组件仍然挂载"才跑得起来。
 * 结果就是点「取消」弹窗"啪"地消失、没有过渡。
 *
 * 正确写法：
 * ```kotlin
 * val dialogP = rememberLastNonNull(target)
 * if (dialogP != null) {
 *     UfiCustomDialog(visible = target != null, …) { … }
 * }
 * ```
 * - `visible` 跟随真实状态 → shell 能播离场；
 * - `dialogP` 留住上一份非空值 → 淡出过程中卡片里还有内容，不会先变成空白再消失。
 *
 * 只在"目标从有到无"时保留；换一个新目标时立即更新，不会显示旧对象。
 *
 * @param value 当前目标（可为 null）
 * @return 当前值，或最近一次的非空值
 */
@Composable
fun <T : Any> rememberLastNonNull(value: T?): T? {
    var last by remember { mutableStateOf<T?>(value) }
    LaunchedEffect(value) { if (value != null) last = value }
    return value ?: last
}
