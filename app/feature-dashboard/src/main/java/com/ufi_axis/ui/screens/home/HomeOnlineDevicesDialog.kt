package com.ufi_axis.ui.screens.home

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.ufi_axis.data.model.OnlineStation
import com.ufi_axis.ui.components.common.LocalUfiDialogClose
import com.ufi_axis.ui.components.common.UfiButton
import com.ufi_axis.ui.components.common.UfiButtonSize
import com.ufi_axis.ui.components.common.UfiButtonVariant
import com.ufi_axis.ui.components.common.UfiCustomDialog
import com.ufi_axis.ui.components.common.UfiDialogBody
import com.ufi_axis.ui.components.common.UfiDivider
import com.ufi_axis.ui.components.common.UfiEmptyState
import com.ufi_axis.ui.components.common.UfiLoadingBox
import com.ufi_axis.ui.components.common.UfiOnlineDeviceRow
import com.ufi_axis.ui.components.common.UfiScrollableDialog
import com.ufi_axis.ui.theme.LocalResolvedPalette

/**
 * 首页 hero 卡「已连接设备」→ 在线设备弹窗。
 *
 * 2026-09-25 新增。数据与「网络设置 → 在线设备」页**同源**：
 * `networkState.wifiClients` 经 `com.ufi_axis.data.model.parseOnlineStations` 解析
 * （那份解析本轮从 feature-network 搬到了 :app:data，全库只有一份），行视觉复用
 * `app/ui` 的 [UfiOnlineDeviceRow]。
 *
 * 与在线设备页的两点产品差异（都是这个入口的定位决定的，不是随手改的）：
 * 1. **只列在线、不列已拉黑**：过滤在调用点（DashboardScreen）做 —— 首页这个入口是
 *    "谁在用我的网"，管理黑名单是网络页的事，不在首页塞第二套管理面板。
 *    于是本弹窗的每一行 `blocked` 恒为 false、不传 `onUnblock`。
 * 2. 没有「已拉黑设备」入口卡、没有 5s 轮询（首页不为一个弹窗起轮询；打开时拉一次，
 *    见 DashboardScreen 里 `showOnlineDevices` 的 LaunchedEffect）。
 *
 * 用 [UfiScrollableDialog] 而不是 UfiCustomDialog：设备可能有十几台，列表必须能滚。
 * 不传 actions / confirmButton ⇒ 由 shell 在标题行右上角自动渲染 ×（与
 * OnlineDevicesScreen 里「已拉黑设备」空名单那一档同一种关法）。
 *
 * @param stations **已过滤掉已拉黑设备**的在线列表（过滤在调用点做，见上）。
 * @param isLoading 与在线设备页同一口径：仅在「还没有任何数据」时用来区分"加载中"与"确实没有"。
 * @param pendingMac 正在下发拉黑/解除的 MAC（小写）。只用于把那一行的按钮置灰，
 *   不要拿 `isLoading` 替代 —— 那是全页共享位，会让整个列表无故变灰。
 */
@Composable
fun HomeOnlineDevicesDialog(
    visible: Boolean,
    stations: List<OnlineStation>,
    isLoading: Boolean,
    pendingMac: String?,
    onDismiss: () -> Unit,
    onBlock: (OnlineStation) -> Unit
) {
    val palette = LocalResolvedPalette.current
    UfiScrollableDialog(
        visible = visible,
        onDismiss = onDismiss,
        title = "在线设备"
    ) {
        UfiDialogBody {
            when {
                stations.isNotEmpty() -> {
                    Text(
                        text = "${stations.size} 台设备正在连接",
                        style = MaterialTheme.typography.labelMedium,
                        color = palette.textSecondary
                    )
                    stations.forEach { station ->
                        // 每行前都画一道分隔线：第一道同时把列表与上面那句"N 台设备正在连接"
                        // 分开（在线设备页那边没有这句头，所以是 `if (index > 0)`）。
                        UfiDivider()
                        val mac = station.mac.lowercase()
                        UfiOnlineDeviceRow(
                            hostname = station.hostname,
                            ip = station.ip,
                            mac = station.mac,
                            viaLan = station.viaLan,
                            // 本弹窗的列表已在调用点过滤掉已拉黑的，所以这一位恒为 false，
                            // 也就不需要 onUnblock（见 UfiOnlineDeviceRow 的 KDoc）。
                            blocked = false,
                            // MAC 为空的元素没法拉黑（设备名单以 MAC 为主键）
                            actionEnabled = mac.isNotEmpty() && pendingMac != mac,
                            onBlock = { onBlock(station) }
                        )
                    }
                }
                // 加载中且尚无数据：不画空态，避免"暂无设备 → 列表"闪一下
                isLoading -> UfiLoadingBox(isLoading = true) {}
                else -> UfiEmptyState(
                    icon = Icons.Default.Devices,
                    message = "暂无在线设备",
                    hint = "热点关闭、或者还没有设备连上来。开启热点后这里会自动出现。"
                )
            }
        }
    }
}

/**
 * 拉黑二次确认（首页侧独立挂一个，文案与在线设备页逐字同口径）。
 *
 * 为什么必须二次确认：拉黑会把设备**立刻踢下线**，误点代价不小 —— 尤其是误拉黑
 * 自己这台手机（它往往就在列表第一行）。
 *
 * @param station null = 不显示。它同时是"待确认的那台设备"，所以调用方直接用一个
 *   `OnlineStation?` state 持有即可，不需要再多一个 boolean。
 */
@Composable
fun HomeBlockDeviceConfirmDialog(
    station: OnlineStation?,
    onDismiss: () -> Unit,
    onConfirm: (OnlineStation) -> Unit
) {
    val palette = LocalResolvedPalette.current
    UfiCustomDialog(
        visible = station != null,
        onDismiss = onDismiss,
        title = "拉黑设备",
        dismissButton = {
            // 关闭动作交给 shell 排时序：离场 backdrop 要播完才卸载窗口，见 LocalUfiDialogClose。
            // local 必须在弹窗自己的 slot 内部读，在弹窗外面读会拿到"直接执行"的默认实现。
            val close = LocalUfiDialogClose.current
            UfiButton(
                variant = UfiButtonVariant.Secondary,
                text = "取消",
                onClick = { close(onDismiss) },
                modifier = Modifier
            )
        },
        confirmButton = {
            val close = LocalUfiDialogClose.current
            UfiButton(
                size = UfiButtonSize.Small,
                text = "拉黑",
                modifier = Modifier,
                onClick = {
                    close {
                        station?.let(onConfirm)
                        onDismiss()
                    }
                }
            )
        }
    ) {
        // 间距统一到 UfiDialogBody（12dp）
        UfiDialogBody {
            Text(
                text = "将「${station?.hostname?.ifEmpty { "未知设备" } ?: ""}」（${station?.mac?.uppercase() ?: ""}）" +
                    "加入 WiFi 黑名单？该设备会立刻断开，之后无法再连接本热点。",
                style = MaterialTheme.typography.bodyMedium,
                color = palette.textSecondary
            )
        }
    }
}
