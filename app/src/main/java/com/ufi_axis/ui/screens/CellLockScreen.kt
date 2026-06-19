package com.ufi_axis.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CellLockScreen(viewModel: MainViewModel, navController: NavHostController) {
    val state by viewModel.networkState.collectAsState()
    var message by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { viewModel.loadCellInfo() }

    UfiScreenScaffold(
        title = "基站管理",
        navController = navController,
        showBack = true,
        actions = {
            IconButton(onClick = { viewModel.loadCellInfo(); message = null }) {
                Icon(Icons.Default.Refresh, contentDescription = "刷新")
            }
        }
    ) { padding ->
        UfiScrollableColumn(modifier = Modifier.padding(padding)) {

            state.errorMessage?.let { UfiErrorBanner(message = it) }
            message?.let {
                Text(it, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp))
            }

            val cellJson: JsonObject? = state.cellInfo?.let {
                try { JsonParser.parseString(it.toString()).asJsonObject } catch (_: Exception) { null }
            }
            val netType = cellJson?.get("network_type")?.asString

            // ── 当前基站 ──
            UfiSettingsGroup {
                Text("当前基站", style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(4.dp))

                val is5G = netType == "20" || netType == "19"
                val ratLabel = if (is5G) "5G" else if (netType == "13") "4G" else netType ?: "未知"

                // PCI/FCN/Band（部分 ZTE 设备不提供这些字段）
                val pci = if (is5G) cellJson?.get("Nr_pci")?.asString
                    ?: "" else cellJson?.get("Lte_pci")?.asString ?: ""
                val fcn = if (is5G) cellJson?.get("Nr_fcn")?.asString
                    ?: "" else cellJson?.get("Lte_fcn")?.asString ?: ""
                val band = if (is5G) cellJson?.get("Nr_bands")?.asString
                    ?: "" else cellJson?.get("Lte_bands")?.asString ?: ""

                // RSRP fallback（设备可能不提供 PCI 但有信号值）
                // 注: Z5g_rsrp 可能是 number 或 string，需安全提取
                fun jsonInt(key: String): Int? {
                    val el = cellJson?.get(key) ?: return null
                    return try {
                        when {
                            el.isJsonPrimitive && el.asJsonPrimitive.isNumber -> el.asInt
                            el.isJsonPrimitive -> el.asString.toIntOrNull()
                            else -> null
                        }
                    } catch (_: Exception) { null }
                }
                val rsrp = jsonInt("Z5g_rsrp") ?: jsonInt("lte_rsrp")

                InfoRow("制式", ratLabel)
                if (band.isNotEmpty()) {
                    val bandPrefix = if (is5G) "n" else "B"
                    InfoRow("频段", "$bandPrefix$band")
                }
                if (pci.isNotEmpty()) InfoRow("PCI", pci)
                if (fcn.isNotEmpty()) InfoRow("频点", fcn)
                rsrp?.let {
                    InfoRow("RSRP", "$it dBm")
                }

                if (pci.isEmpty() && rsrp == null) {
                    Text("暂无基站信息", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                // 锁定当前基站（需要 PCI + FCN）
                if (pci.isNotEmpty() && fcn.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = {
                            val rat = if (is5G) "16" else "12"
                            viewModel.cellLock(pci, fcn, rat)
                            message = "已发送锁定当前基站命令"
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Lock, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("锁定当前基站")
                    }
                }
            }

            // ── 已锁定基站 ──
            UfiSettingsGroup {
                Text("已锁定基站", style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(4.dp))

                val lockedCells = parseLockedCells(cellJson?.get("locked_cell_info"))
                if (lockedCells.isEmpty()) {
                    Text("暂无已锁定基站", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    lockedCells.forEachIndexed { index, cell ->
                        if (index > 0) UfiDivider()
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val ratText = if (cell.rat == "16") "5G" else "4G"
                            SuggestionChip(
                                onClick = {}, label = { Text(ratText, style = MaterialTheme.typography.labelSmall) },
                                modifier = Modifier.width(52.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text("PCI ${cell.pci}  \u00B7  频点 ${cell.earfcn}",
                                    style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            }

            // ── 邻区列表 ──
            UfiSettingsGroup {
                Text("邻区列表", style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(4.dp))

                val neighbors = parseNeighborCells(cellJson?.get("neighbor_cell_info"), netType)
                if (neighbors.isEmpty()) {
                    Text("暂无邻区信息", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    neighbors.forEachIndexed { index, cell ->
                        if (index > 0) UfiDivider()
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                // 第一行: 频段 + PCI + FCN
                                val isNR = inferIsNR(cell.band, cell.earfcn, netType)
                                val bandPrefix = if (isNR) "n" else "B"
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("$bandPrefix${cell.band}  PCI ${cell.pci}",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium)
                                    Spacer(Modifier.width(8.dp))
                                    Text("FCN ${cell.earfcn}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                // 第二行: 信号指标
                                Spacer(Modifier.height(2.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    cell.rsrp?.let {
                                        Text("RSRP $it", style = MaterialTheme.typography.labelSmall,
                                            color = signalColor(it), fontWeight = FontWeight.Medium)
                                    }
                                    cell.sinr?.let {
                                        Text("SINR $it", style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    cell.rsrq?.let {
                                        Text("RSRQ $it", style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                            // 锁定按钮
                            TextButton(onClick = {
                                val rat = if (inferIsNR(cell.band, cell.earfcn, netType)) "16" else "12"
                                viewModel.cellLock(cell.pci, cell.earfcn, rat)
                                message = "已发送锁定 PCI ${cell.pci} 命令"
                            }) {
                                Icon(Icons.Default.Lock, null, modifier = Modifier.size(14.dp))
                                Spacer(Modifier.width(2.dp))
                                Text("锁定", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }

            // ── 底部操作 ──
            UfiSettingsGroup {
                UfiButtonRow {
                    OutlinedButton(
                        onClick = { viewModel.loadCellInfo(); message = null },
                        modifier = Modifier.weight(1f)
                    ) { Text("刷新") }
                    OutlinedButton(
                        onClick = {
                            viewModel.unlockAllCell()
                            message = "已发送解锁全部基站命令"
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text("解锁全部") }
                }

                Spacer(Modifier.height(8.dp))
                Text(
                    "注意：锁定错误基站可能导致信号不稳定或无信号",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            Spacer(Modifier.height(Spacing.Large))
        }
    }
}

// ── JSON 解析辅助 ──

private fun parseNeighborCells(element: JsonElement?, networkType: String?): List<CellItem> {
    if (element == null) return emptyList()
    return try {
        val arr = when {
            element.isJsonArray -> element.asJsonArray
            element.isJsonPrimitive && element.asString.startsWith("[") ->
                JsonParser.parseString(element.asString).asJsonArray
            else -> return emptyList()
        }
        arr.mapNotNull { item ->
            try {
                val obj = item.asJsonObject
                CellItem(
                    band = obj.get("band")?.asString ?: "",
                    earfcn = obj.get("earfcn")?.asString ?: "",
                    pci = obj.get("pci")?.asString ?: "",
                    rsrp = obj.get("rsrp")?.asString?.toIntOrNull(),
                    rsrq = obj.get("rsrq")?.asString?.toIntOrNull(),
                    sinr = obj.get("sinr")?.asString?.toIntOrNull()
                ).takeIf { it.pci.isNotEmpty() }
            } catch (_: Exception) { null }
        }.sortedByDescending { it.rsrp ?: -999 }
    } catch (_: Exception) { emptyList() }
}

private fun parseLockedCells(element: JsonElement?): List<LockedCellItem> {
    if (element == null) return emptyList()
    return try {
        val arr = when {
            element.isJsonArray -> element.asJsonArray
            element.isJsonPrimitive && element.asString.startsWith("[") ->
                JsonParser.parseString(element.asString).asJsonArray
            else -> return emptyList()
        }
        if (arr.size() == 0) return emptyList()
        arr.mapNotNull { item ->
            try {
                val obj = item.asJsonObject
                LockedCellItem(
                    earfcn = obj.get("earfcn")?.asString ?: "",
                    pci = obj.get("pci")?.asString ?: "",
                    rat = obj.get("rat")?.asString ?: "12"
                ).takeIf { it.pci.isNotEmpty() }
            } catch (_: Exception) { null }
        }
    } catch (_: Exception) { emptyList() }
}

/**
 * 推断邻区是否为 NR (5G) 小区
 * - band > 255: 纯 NR band（如 n257, n258, n260）
 * - earfcn > 100000: NR 频点范围（LTE EARFCN 通常 < 70000）
 * - network_type 为 5G 时共享频段（1-255）视为 NR
 */
private fun inferIsNR(band: String, earfcn: String, networkType: String?): Boolean {
    val bandNum = band.toIntOrNull() ?: 0
    val earfcnNum = earfcn.toIntOrNull() ?: 0
    // NR 频点 > 100000（LTE EARFCN 范围约 0-70000）
    if (earfcnNum > 100000) return true
    // band > 255 是纯 NR
    if (bandNum > 255) return true
    // 共享频段 (1-255): 依赖 network_type 判断
    val is5G = networkType == "20" || networkType == "19"
    return is5G && bandNum in 1..255
}
