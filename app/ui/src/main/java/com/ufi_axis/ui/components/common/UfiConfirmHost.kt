// [F24] STABLE-UI-API：公共组件签名已冻结，请勿在无向后兼容前提下修改。
package com.ufi_axis.ui.components.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.painter.Painter

/**
 * 「二次确认」的统一入口 —— 把一个弹窗里再弹确认框这件事，收敛成两个小工具：
 *
 * 1. [UfiDialogSuspendState]：父弹窗「为弹确认框而程序性收起」的状态 + `onDismiss` 守卫；
 * 2. [UfiConfirmRequest] + [UfiConfirmHost]：确认框的请求对象与宿主。
 *
 * ## 为什么需要它（2026-10-22）
 *
 * 两个 Dialog 窗口同时在场时，后一个窗口的 backdrop 会丢模糊 / 闪烁，甚至被前一个的离场带走
 * （用户实测：「点确认后二次确认弹窗异常」）。r53 试过在 [UfiDialogShell] 的恢复分支补写窗口属性，
 * **实测无效且引入闪烁抽搐**，已撤回。
 *
 * 正确做法（[WifiSettingsDialog] 2026-10-12 首次落地、本次推广）：
 *
 * ```
 * val suspend = rememberUfiDialogSuspendState()
 * var confirm by remember { mutableStateOf<UfiConfirmRequest?>(null) }
 *
 * UfiScrollableDialog(
 *     visible = suspend.onScreen(visible),          // 确认框在场时父弹窗收起
 *     onDismiss = {
 *         if (suspend.consumeProgrammaticDismiss()) return@UfiScrollableDialog
 *         onDismiss()                                // 用户真的关掉了
 *     },
 *     actions = {
 *         UfiDialogActions(onConfirm = {
 *             suspend.suspend()                      // 先收起本弹窗
 *             confirm = UfiConfirmRequest(...)       // 确认框独立上场
 *         }, onDismiss = onDismiss)
 *     }
 * )
 *
 * UfiConfirmHost(confirm)   // 在 confirm 的 onConfirm / onDismiss 里把 suspend.resume() 补上
 * ```
 *
 * 规约：**不要在父弹窗里直接写 `UfiConfirmDialog`** —— 那样两个窗口会叠着开。
 * 一律走 `suspend + UfiConfirmHost`。
 */
@Immutable
data class UfiConfirmRequest(
    val title: String,
    val text: String,
    val confirmText: String = "确认",
    val dismissText: String = "取消",
    val destructive: Boolean = false,
    val icon: Painter? = null,
    val onConfirm: () -> Unit,
    val onDismiss: () -> Unit = {}
)

/**
 * 父弹窗的「程序性收起」状态。
 *
 * 只负责三件事：算出这次要不要有窗口在场（[onScreen]）、发起收起（[suspend]）、
 * 在 `onDismiss` 里分辨「程序性收起」与「用户真的关了」（[consumeProgrammaticDismiss]）。
 * 确认框被关掉后要调 [resume] 让父弹窗回来。
 */
@Stable
class UfiDialogSuspendState {
    var suspended by mutableStateOf(false)
        private set

    /** 父弹窗此刻是否真的有窗口在场：外部可见 且 没有为弹确认框而收起。 */
    fun onScreen(externalVisible: Boolean): Boolean = externalVisible && !suspended

    /** 收起父弹窗，准备让确认框独立上场。 */
    fun suspend() {
        suspended = true
    }

    /** 确认框关闭后调用：父弹窗重新进场（草稿/输入都还在，因为父弹窗没有离开组合）。 */
    fun resume() {
        suspended = false
    }

    /**
     * 放在父弹窗的 `onDismiss` 第一行：返回 true 表示这次回调是「为弹确认框而收起」，
     * 调用方应直接 return，不要真的关闭整个流程。
     *
     * 真正的关闭（如提交成功后主动关）要先 [resume] 再 `onDismiss()` —— 那样这里返回 false，
     * 关闭意图正常透传。
     */
    fun consumeProgrammaticDismiss(): Boolean {
        if (suspended) {
            suspended = false
            return true
        }
        return false
    }
}

@Composable
fun rememberUfiDialogSuspendState(): UfiDialogSuspendState = remember { UfiDialogSuspendState() }

/**
 * 确认框宿主：`request == null` 时不渲染任何东西。
 *
 * 关闭时序由公共 [UfiConfirmDialog]（内部走 [LocalUfiDialogClose]）负责，
 * 调用点只维护 [UfiConfirmRequest] 的有无。
 *
 * @param onCleared 确认框收起后回调（无论确认还是取消），通常在这里 `suspend.resume()`。
 */
@Composable
fun UfiConfirmHost(request: UfiConfirmRequest?) {
    if (request == null) return
    UfiConfirmDialog(
        title = request.title,
        text = request.text,
        confirmText = request.confirmText,
        dismissText = request.dismissText,
        destructive = request.destructive,
        icon = request.icon,
        onConfirm = request.onConfirm,
        onDismiss = request.onDismiss
    )
}
