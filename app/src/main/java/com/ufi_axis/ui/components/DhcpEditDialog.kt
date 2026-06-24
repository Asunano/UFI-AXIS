package com.ufi_axis.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.google.gson.JsonElement
import com.ufi_axis.ui.components.common.*
import com.ufi_axis.ui.theme.Spacing
import com.ufi_axis.ui.theme.UfiCardDefaults

/**
 * DHCP 配置编辑弹窗。
 * 自动从 [lanSettings] JSON 填充表单字段，支持编辑后保存。
 */
@Composable
fun DhcpEditDialog(
    lanSettings: JsonElement?,
    onDismiss: () -> Unit,
    onSave: (lanIp: String, lanNetmask: String, dhcpEnabled: Boolean, dhcpStart: String, dhcpEnd: String, dhcpLease: String) -> Unit
) {
    val lan = lanSettings?.asJsonObject ?: com.google.gson.JsonObject()
    // 多个可能的 key 名称，兼容不同设备
    fun multiKey(vararg keys: String): String? {
        for (k in keys) { lan.get(k)?.asString?.let { if (it.isNotBlank()) return it } }
        return null
    }
    fun intKey(vararg keys: String): Int? {
        for (k in keys) {
            val el = lan.get(k) ?: continue
            val v = if (el.isJsonPrimitive && el.asJsonPrimitive.isNumber) el.asInt.toString()
                    else el.asString
            v.toIntOrNull()?.let { return it }
        }
        return null
    }
    var dhcpEnabled by remember {
        val v = multiKey("dhcpEnabled", "DhcpEnabled", "dhcp_enable", "DhcpStatus") ?: ""
        mutableStateOf(v == "1" || v.equals("true", ignoreCase = true) || v == "SERVER")
    }
    var lanIp by remember { mutableStateOf(multiKey("lan_ipaddr", "LanIP", "lan_ip", "ipaddr") ?: "") }
    var lanNetmask by remember { mutableStateOf(multiKey("lan_netmask", "LanNetmask", "netmask") ?: "") }
    var dhcpStart by remember { mutableStateOf(multiKey("dhcpStart", "DhcpStartIP", "dhcp_start", "start_ip") ?: "") }
    var dhcpEnd by remember { mutableStateOf(multiKey("dhcpEnd", "DhcpEndIP", "dhcp_end", "end_ip") ?: "") }
    var dhcpLease by remember {
        val leaseSec = intKey("dhcpLease", "DhcpLease", "dhcp_lease") ?: (intKey("dhcpLease_hour", "dhcp_lease_hour")?.let { it * 3600 })
        mutableStateOf((leaseSec?.takeIf { it > 0 } ?: 86400).toString())
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = UfiCardDefaults.largeSurfaceShape,
        title = {
            Text("DHCP 配置", fontWeight = FontWeight.Bold)
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                UfiSettingsToggle(
                    title = "DHCP 服务器",
                    description = if (dhcpEnabled) "自动分配 IP" else "手动配置 IP",
                    checked = dhcpEnabled,
                    onCheckedChange = { dhcpEnabled = it }
                )
                UfiDivider()
                UfiTextField(value = lanIp, onValueChange = { lanIp = it }, label = "LAN IP 地址")
                UfiTextField(value = lanNetmask, onValueChange = { lanNetmask = it }, label = "子网掩码")
                if (dhcpEnabled) {
                    UfiTextField(value = dhcpStart, onValueChange = { dhcpStart = it }, label = "DHCP 起始 IP")
                    UfiTextField(value = dhcpEnd, onValueChange = { dhcpEnd = it }, label = "DHCP 结束 IP")
                    UfiDigitField(
                        value = dhcpLease, onValueChange = { dhcpLease = it },
                        label = "租约时间（秒）", modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(lanIp, lanNetmask, dhcpEnabled, dhcpStart, dhcpEnd, dhcpLease)
            }) {
                Text("保存", fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}
