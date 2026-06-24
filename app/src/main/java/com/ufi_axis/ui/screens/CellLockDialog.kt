package com.ufi_axis.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ufi_axis.ui.components.*
import com.ufi_axis.ui.components.common.*
import com.ufi_axis.ui.theme.*
import com.ufi_axis.viewmodel.MainViewModel
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

private data class CellItem(
    val band: String, val earfcn: String, val pci: String,
    val rsrp: Int?, val rsrq: Int?, val sinr: Int?
)

private data class LockedCellItem(
    val earfcn: String, val pci: String, val rat: String
)

/** 基站管理弹窗 — 使用可滚动弹窗 */
@Composable
fun CellLockDialog(
    viewModel: MainViewModel,
    visible: Boolean,
    onDismiss: () -> Unit
) {
    val state by viewModel.networkState.collectAsState()
    var message by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(visible) {
        if (visible) { isLoading = true; viewModel.network.loadCellInfo() }
    }

    LaunchedEffect(state) {
        if (state.cellInfo != null || state.errorMessage != null) isLoading = false
    }

    LaunchedEffect(Unit) { kotlinx.coroutines.delay(5000); isLoading = false }

    UfiScrollableDialog(
        visible = visible,
        onDismiss = onDismiss,
        title = "基站管理"
    ) {
        // 错误提示
        state.errorMessage?.let { UfiErrorBanner(message = it, modifier = Modifier.padding(horizontal = Spacing.DialogPaddingH)) }
        message?.let {
            Surface(color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.DialogPaddingH)) {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(12.dp))
            }
            Spacer(Modifier.height(8.dp))
        }

        if (isLoading) {
            UfiLoadingBox(isLoading = true) {}
            return@UfiScrollableDialog
        }

        // 安全解析 cellInfo JSON
        val cellJson: JsonObject? = when (val info = state.cellInfo) {
            is JsonObject -> info
            is JsonElement -> try { JsonParser.parseString(info.toString()).asJsonObject } catch (_: Exception) { null }
            else -> null
        }

        if (cellJson == null) {
            Text("基站数据不可用，请检查 AT 通道连接或稍后重试",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Spacing.DialogPaddingH, vertical = 16.dp))
            return@UfiScrollableDialog
        }

        val netType = cellJson.get("network_type")?.asString
        val is5G = netType == "20" || netType == "19" || netType == "NR"

        // ═════ 当前基站 ═════
        UfiSettingsGroup(modifier = Modifier.padding(horizontal = Spacing.DialogPaddingH)) {
            UfiSectionGroupTitle("当前基站", "信号详情")

            val ratLabel = when {
                is5G -> "5G"; netType == "13" || netType == "LTE" -> "4G"
                netType != null -> netType; else -> "未知"
            }
            val pci = if (is5G) cellJson.get("Nr_pci")?.asString ?: "" else cellJson.get("Lte_pci")?.asString ?: ""
            val fcn = if (is5G) cellJson.get("Nr_fcn")?.asString ?: "" else cellJson.get("Lte_fcn")?.asString ?: ""
            val band = if (is5G) cellJson.get("Nr_bands")?.asString ?: "" else cellJson.get("Lte_bands")?.asString ?: ""

            fun jsonInt(key: String): Int? {
                val el = cellJson.get(key) ?: return null
                return try {
                    when {
                        el.isJsonPrimitive && el.asJsonPrimitive.isNumber -> el.asInt
                        el.isJsonPrimitive -> el.asString.toIntOrNull()
                        else -> null
                    }
                } catch (_: Exception) { null }
            }
            val rsrp = jsonInt("nr_rsrp") ?: jsonInt("lte_rsrp")
            val sinr = jsonInt("Nr_snr") ?: jsonInt("lte_snr")

            Row(verticalAlignment = Alignment.CenterVertically) {
                SuggestionChip(onClick = {}, label = { Text(ratLabel, fontWeight = FontWeight.Bold) },
                    colors = SuggestionChipDefaults.suggestionChipColors(
                        containerColor = if (is5G) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.tertiaryContainer))
            }
            Spacer(Modifier.height(8.dp))

            if (band.isNotEmpty()) UfiInfoRow("频段", "${if (is5G) "n" else "B"}$band")
            if (pci.isNotEmpty()) UfiInfoRow("PCI", pci)
            if (fcn.isNotEmpty()) UfiInfoRow("频点(FCN)", fcn)
            rsrp?.let { UfiInfoRow("RSRP", "$it dBm") }
            sinr?.let { UfiInfoRow("SINR", "$it dB") }

            if (pci.isEmpty() && rsrp == null) {
                Text("暂无基站信息", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            rsrp?.let { r ->
                Spacer(Modifier.height(8.dp))
                SignalStrengthBarDlg(rsrp = r)
            }

            if (pci.isNotEmpty() && fcn.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Button(onClick = {
                    viewModel.network.cellLock(pci, fcn, if (is5G) "16" else "12")
                    message = "已发送锁定当前基站命令 (PCI=$pci, FCN=$fcn)"
                    isLoading = true
                }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Lock, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("锁定当前基站")
                }
            }
        }

        // ═════ 已锁定基站 ═════
        Spacer(Modifier.height(Spacing.Medium))
        UfiSettingsGroup(modifier = Modifier.padding(horizontal = Spacing.DialogPaddingH)) {
            UfiSectionGroupTitle("已锁定基站", "当前锁定的基站列表")
            val lockedCells = parseLockedCellsDlg(cellJson.get("locked_cell_info"))
            if (lockedCells.isEmpty()) {
                Text("暂无已锁定基站", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 4.dp))
            } else {
                lockedCells.forEachIndexed { index, cell ->
                    if (index > 0) UfiDivider()
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        val ratText = if (cell.rat == "16") "5G" else "4G"
                        SuggestionChip(onClick = {}, label = { Text(ratText, style = MaterialTheme.typography.labelSmall) },
                            colors = SuggestionChipDefaults.suggestionChipColors(
                                containerColor = if (cell.rat == "16") MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.tertiaryContainer))
                        Spacer(Modifier.width(8.dp))
                        Text("PCI ${cell.pci}  ·  频点 ${cell.earfcn}",
                            style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }

        // ═════ 邻区列表 ═════
        Spacer(Modifier.height(Spacing.Medium))
        UfiSettingsGroup(modifier = Modifier.padding(horizontal = Spacing.DialogPaddingH)) {
            UfiSectionGroupTitle("邻区列表", "周边基站信号")
            val neighbors = parseNeighborCellsDlg(cellJson.get("neighbor_cell_info"), netType)
            if (neighbors.isEmpty()) {
                Text("暂无邻区信息", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 4.dp))
            } else {
                neighbors.forEachIndexed { index, cell ->
                    if (index > 0) UfiDivider()
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        cell.rsrp?.let { r ->
                            Box(Modifier.size(10.dp).clip(CircleShape).background(signalColorDlg(r)))
                            Spacer(Modifier.width(8.dp))
                        }
                        Column(Modifier.weight(1f)) {
                            val isNR = inferIsNRDlg(cell.band, cell.earfcn, netType)
                            val bandPrefix = if (isNR) "n" else "B"
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("$bandPrefix${cell.band}  PCI ${cell.pci}",
                                    style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                                Spacer(Modifier.width(8.dp))
                                Text("FCN ${cell.earfcn}", style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Spacer(Modifier.height(2.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                cell.rsrp?.let { Text("RSRP $it", style = MaterialTheme.typography.labelSmall, color = signalColorDlg(it), fontWeight = FontWeight.Medium) }
                                cell.sinr?.let { Text("SINR $it", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                cell.rsrq?.let { Text("RSRQ $it", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            }
                        }
                        TextButton(onClick = {
                            viewModel.network.cellLock(cell.pci, cell.earfcn, if (inferIsNRDlg(cell.band, cell.earfcn, netType)) "16" else "12")
                            message = "已锁定 PCI ${cell.pci}"
                        }) {
                            Icon(Icons.Default.Lock, null, Modifier.size(14.dp))
                            Spacer(Modifier.width(2.dp))
                            Text("锁定", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }

        // ═════ 底部操作 ═════
        Spacer(Modifier.height(Spacing.Medium))
        Row(Modifier.fillMaxWidth().padding(horizontal = Spacing.DialogPaddingH), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { isLoading = true; viewModel.network.loadCellInfo(); message = null }, modifier = Modifier.weight(1f)) { Text("刷新") }
            OutlinedButton(onClick = { viewModel.network.unlockAllCell(); message = "已解锁全部"; isLoading = true },
                modifier = Modifier.weight(1f), colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("解锁全部") }
        }

        // 警告
        Spacer(Modifier.height(8.dp))
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)),
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.DialogPaddingH)
        ) {
            Text("注意：锁定错误基站可能导致信号不稳定。建议仅锁定当前已连接的基站。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(10.dp))
        }

        Spacer(Modifier.height(8.dp))
    }
}

// ── 信号强度可视化条 ──
@Composable
private fun SignalStrengthBarDlg(rsrp: Int) {
    val quality = ((rsrp + 120).toFloat() / 60f).coerceIn(0f, 1f)
    val barColor = when {
        quality > 0.6f -> MaterialTheme.colorScheme.primary
        quality > 0.3f -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.error
    }
    Column {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("信号强度", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(6.dp))
            Text(
                when { quality > 0.8f -> "优"; quality > 0.6f -> "良"; quality > 0.3f -> "一般"; else -> "差" },
                style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = barColor)
        }
        Spacer(Modifier.height(4.dp))
        LinearProgressIndicator(progress = { quality }, modifier = Modifier.fillMaxWidth().height(6.dp),
            color = barColor, trackColor = MaterialTheme.colorScheme.surfaceVariant)
    }
}

@Composable
private fun signalColorDlg(rsrp: Int) = when {
    rsrp >= -85 -> MaterialTheme.colorScheme.primary
    rsrp >= -105 -> MaterialTheme.colorScheme.tertiary
    else -> MaterialTheme.colorScheme.error
}

// ── JSON 解析辅助 ──
private fun parseNeighborCellsDlg(element: JsonElement?, networkType: String?): List<CellItem> {
    if (element == null) return emptyList()
    return try {
        val arr = when {
            element.isJsonArray -> element.asJsonArray
            element.isJsonPrimitive && element.asString.startsWith("[") -> JsonParser.parseString(element.asString).asJsonArray
            else -> return emptyList()
        }
        arr.mapNotNull { item ->
            try {
                val obj = item.asJsonObject
                CellItem(
                    band = obj.get("band")?.asString ?: "", earfcn = obj.get("earfcn")?.asString ?: "",
                    pci = obj.get("pci")?.asString ?: "", rsrp = obj.get("rsrp")?.asString?.toIntOrNull(),
                    rsrq = obj.get("rsrq")?.asString?.toIntOrNull(), sinr = obj.get("sinr")?.asString?.toIntOrNull()
                ).takeIf { it.pci.isNotEmpty() }
            } catch (_: Exception) { null }
        }.sortedByDescending { it.rsrp ?: -999 }
    } catch (_: Exception) { emptyList() }
}

private fun parseLockedCellsDlg(element: JsonElement?): List<LockedCellItem> {
    if (element == null) return emptyList()
    return try {
        val arr = when {
            element.isJsonArray -> element.asJsonArray
            element.isJsonPrimitive && element.asString.startsWith("[") -> JsonParser.parseString(element.asString).asJsonArray
            else -> return emptyList()
        }
        if (arr.size() == 0) return emptyList()
        arr.mapNotNull { item ->
            try {
                val obj = item.asJsonObject
                LockedCellItem(
                    earfcn = obj.get("earfcn")?.asString ?: "", pci = obj.get("pci")?.asString ?: "",
                    rat = obj.get("rat")?.asString ?: "12"
                ).takeIf { it.pci.isNotEmpty() }
            } catch (_: Exception) { null }
        }
    } catch (_: Exception) { emptyList() }
}

private fun inferIsNRDlg(band: String, earfcn: String, networkType: String?): Boolean {
    val bandNum = band.toIntOrNull() ?: 0
    val earfcnNum = earfcn.toIntOrNull() ?: 0
    if (earfcnNum > 100000) return true
    if (bandNum > 255) return true
    val is5G = networkType == "20" || networkType == "19" || networkType == "NR"
    return is5G && bandNum in 1..255
}