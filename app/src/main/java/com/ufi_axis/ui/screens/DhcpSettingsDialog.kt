package com.ufi_axis.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ufi_axis.ui.components.*
import com.ufi_axis.ui.components.common.*
import com.ufi_axis.ui.theme.*
import com.ufi_axis.viewmodel.MainViewModel

/** DHCP 设置弹窗 */
@Composable
fun DhcpSettingsDialog(
    viewModel: MainViewModel,
    visible: Boolean,
    onDismiss: () -> Unit
) {
    val state by viewModel.networkState.collectAsState()

    var showDhcpDialog by remember { mutableStateOf(false) }

    LaunchedEffect(visible) {
        if (visible && state.lanSettings == null) viewModel.network.loadLanSettings()
    }

    UfiCustomDialog(
        visible = visible,
        onDismiss = onDismiss,
        title = "DHCP 设置"
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.DialogPaddingH)
        ) {
            val dhcpOn = remember(state.lanSettings) {
                val json = state.lanSettings?.asJsonObject
                val v = json?.get("dhcpEnabled")?.asString
                    ?: json?.get("DhcpEnabled")?.asString ?: json?.get("dhcp_enable")?.asString
                    ?: json?.get("DhcpStatus")?.asString ?: ""
                v == "1" || v.equals("true", ignoreCase = true) || v == "SERVER"
            }

            val lanIp = remember(state.lanSettings) {
                val json = state.lanSettings?.asJsonObject
                fun mk(vararg keys: String): String? {
                    for (k in keys) { json?.get(k)?.asString?.let { if (it.isNotBlank()) return it } }; return null
                }
                mk("lan_ipaddr", "LanIP", "lan_ip", "ipaddr") ?: "—"
            }
            val dhcpRange = remember(state.lanSettings) {
                val json = state.lanSettings?.asJsonObject
                fun mk(vararg keys: String): String? {
                    for (k in keys) { json?.get(k)?.asString?.let { if (it.isNotBlank()) return it } }; return null
                }
                val s = mk("dhcpStart", "DhcpStartIP", "dhcp_start", "start_ip") ?: "—"
                val e = mk("dhcpEnd", "DhcpEndIP", "dhcp_end", "end_ip") ?: "—"
                "$s ~ $e"
            }
            val dhcpLease = remember(state.lanSettings) {
                val json = state.lanSettings?.asJsonObject
                fun ik(vararg keys: String): Int? {
                    for (k in keys) {
                        val el = json?.get(k) ?: continue
                        val v = if (el.isJsonPrimitive && el.asJsonPrimitive.isNumber) el.asInt.toString() else el.asString
                        v.toIntOrNull()?.let { return it }
                    }; return null
                }
                val s = ik("dhcpLease", "DhcpLease", "dhcp_lease")
                    ?: (ik("dhcpLease_hour", "dhcp_lease_hour")?.let { it * 3600 })
                if (s == null || s <= 0) "86400" else s.toString()
            }

            UfiInfoRow("状态", if (dhcpOn) "DHCP 服务器 开" else "DHCP 已关闭")
            Spacer(Modifier.height(6.dp))
            UfiInfoRow("LAN IP", lanIp)
            if (dhcpOn) {
                Spacer(Modifier.height(6.dp))
                UfiInfoRow("DHCP 范围", dhcpRange)
                Spacer(Modifier.height(6.dp))
                UfiInfoRow("租约时间", "${dhcpLease.toIntOrNull()?.div(3600) ?: "—"} 小时")
            }

            Spacer(Modifier.height(16.dp))
            UfiPrimaryButton(
                text = "编辑 DHCP 配置",
                onClick = { viewModel.network.loadLanSettings(); showDhcpDialog = true }
            )
        }
    }

    if (showDhcpDialog && state.lanSettings != null) {
        DhcpEditDialog(
            lanSettings = state.lanSettings,
            onDismiss = { showDhcpDialog = false },
            onSave = { lanIp, lanNetmask, dhcpEnabled, dhcpStart, dhcpEnd, dhcpLease ->
                viewModel.network.setDhcpSetting(
                    lanIp, lanNetmask,
                    if (dhcpEnabled) "SERVER" else "DISABLE",
                    dhcpStart, dhcpEnd, dhcpLease
                )
                showDhcpDialog = false; viewModel.network.loadLanSettings()
            }
        )
    }
}