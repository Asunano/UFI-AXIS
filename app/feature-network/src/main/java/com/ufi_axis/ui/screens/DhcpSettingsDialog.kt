package com.ufi_axis.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Router
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import com.ufi_axis.ui.components.common.*
import com.ufi_axis.viewmodel.MainViewModel


/**
 * DHCP 设置弹窗（2026-08-27：由 `DhcpSettingsScreen` 独立页面改回弹窗）。
 *
 * 结构上是**一层**弹窗：直接就是可编辑表单。之前的实现是「只读信息弹窗 → 再点『编辑』开
 * 第二个 `DhcpEditDialog`」两层套娃，改页面时又把表单摊平成整页，两者都已删除。
 *
 * 用 [UfiScrollableDialog]：表单最多 1 个开关 + 5 个输入框，小屏 + 输入法弹起时必须能滚，
 * 固定高度的 `UfiCustomDialog` 会把「保存」按钮挤出可视区。底部按钮走 `actions` 槽位而不是
 * 塞进 content——组件里注明过，塞进 content 会让页脚预算算错、底部空出一截。
 *
 * 草稿语义：所有字段先落在本地 draft，点「保存」才下发；`remember(lan, visible)` 让每次重新
 * 打开都用最新的 `lanSettings` 重新播种，避免上次改了没保存的值留在表单里。
 *
 * 字段：`LanSettingsResponse` 是强类型（阶段 4.3），键名只在 `DeviceResponses.kt` 出现一次；
 * 租约有的固件给秒（`dhcpLease`）、有的只给小时（`dhcpLease_hour`），这两个是**不同字段**
 * 而不是别名，换算逻辑收在 `LanSettingsResponse.leaseSeconds` 里。
 */
@Composable
fun DhcpSettingsDialog(
    viewModel: MainViewModel,
    visible: Boolean,
    onDismiss: () -> Unit
) {
    val state by viewModel.networkState.collectAsState()

    LaunchedEffect(visible) {
        if (visible && state.lanSettings == null) viewModel.network.loadLanSettings()
    }

    val lan = state.lanSettings

    var dhcpEnabled by remember(lan, visible) { mutableStateOf(lan?.dhcpOn ?: false) }
    var lanIp by remember(lan, visible) { mutableStateOf(lan?.lanIp ?: "") }
    var lanNetmask by remember(lan, visible) { mutableStateOf(lan?.lanNetmask ?: "") }
    var dhcpStart by remember(lan, visible) { mutableStateOf(lan?.dhcpStart ?: "") }
    var dhcpEnd by remember(lan, visible) { mutableStateOf(lan?.dhcpEnd ?: "") }
    var dhcpLease by remember(lan, visible) {
        mutableStateOf((lan?.leaseSeconds?.takeIf { it > 0 } ?: 86400).toString())
    }

    UfiScrollableDialog(
        visible = visible,
        onDismiss = onDismiss,
        title = "DHCP 设置",
        icon = rememberVectorPainter(Icons.Filled.Router),
        showCloseButton = false,
        actions = {
            UfiDialogActions(
                onDismiss = onDismiss,
                onConfirm = {
                    viewModel.network.setDhcpSetting(
                        lanIp, lanNetmask,
                        if (dhcpEnabled) "SERVER" else "DISABLE",
                        dhcpStart, dhcpEnd, dhcpLease
                    )
                    viewModel.network.loadLanSettings()
                    onDismiss()
                },
                confirmText = "保存"
            )
        }
    ) {
        UfiDialogBody {
            UfiSettingsToggle(
                title = "DHCP 服务器",
                description = if (dhcpEnabled) "自动分配局域网 IP" else "手动配置 IP",
                checked = dhcpEnabled,
                onCheckedChange = { dhcpEnabled = it }
            )
            UfiDivider()
            UfiDialogTextField(label = "LAN IP 地址", value = lanIp, onValueChange = { lanIp = it })
            UfiDialogTextField(label = "子网掩码", value = lanNetmask, onValueChange = { lanNetmask = it })
            // 关掉 DHCP 时地址池 / 租约没有意义，直接不显示，避免"填了但不生效"的困惑
            if (dhcpEnabled) {
                UfiDialogTextField(label = "DHCP 起始 IP", value = dhcpStart, onValueChange = { dhcpStart = it })
                UfiDialogTextField(label = "DHCP 结束 IP", value = dhcpEnd, onValueChange = { dhcpEnd = it })
                UfiDigitField(
                    value = dhcpLease, onValueChange = { dhcpLease = it },
                    label = "租约时间（秒）", modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}
