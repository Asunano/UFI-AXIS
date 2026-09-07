package com.ufi_axis.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.ufi_axis.ui.components.*
import com.ufi_axis.ui.components.common.*
import com.ufi_axis.ui.theme.*
import com.ufi_axis.viewmodel.MainViewModel
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private data class CellItem(
    val band: String, val earfcn: String, val pci: String,
    val rsrp: Int?, val rsrq: Int?, val sinr: Int?
)

private data class LockedCellItem(
    val earfcn: String, val pci: String, val rat: String
)

/**
 * 基站管理 — 独立页面（路由 detail/cell-lock）。
 * 由 NetworkScreen「基站信息」导航进入，自带返回按钮（UfiScreenScaffold）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CellLockScreen(viewModel: MainViewModel, navController: NavHostController) {
    val state by viewModel.networkState.collectAsState()
    val palette = LocalResolvedPalette.current
    var message by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        isLoading = true
        viewModel.network.loadCellInfo()
    }
    LaunchedEffect(state) {
        if (state.cellInfo != null || state.errorMessage != null) isLoading = false
    }
    LaunchedEffect(Unit) { kotlinx.coroutines.delay(5000); isLoading = false }

    UfiScreenScaffold(title = "基站信息", navController = navController, showBack = true) { padding ->
        UfiPageBackground(modifier = Modifier.padding(padding)) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.CardHorizontalMargin)
            ) {
                Spacer(Modifier.height(8.dp))
            // 错误提示
            state.errorMessage?.let { UfiErrorBanner(message = it) }
            message?.let {
                Surface(
                    color = palette.accent.copy(alpha = 0.1f),
                    shape = UfiCardDefaults.shape,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = palette.accent,
                        modifier = Modifier.padding(12.dp))
                }
                Spacer(Modifier.height(8.dp))
            }

            if (isLoading) {
                UfiLoadingBox(isLoading = true) {}
                return@Column
            }

            // 小区信息（强类型，阶段 4.3）。注意 `/api/network/cell-info` 只带 LTE 侧字段与
            // 邻区/已锁定列表 —— NR 与服务小区字段归 signal 分组，所以服务小区一律读 signalInfo
            // 的统一字段（band_label / pci / arfcn / signal_strength，core 已做 NR 优先合并），
            // LTE 值只在 signalInfo 缺失时兜底。这样客户端不需要按制式 if。
            val cellInfo = state.cellInfo
            val sig = state.signalInfo

            if (cellInfo == null && sig == null) {
                Text("基站数据不可用，请检查 AT 通道连接或稍后重试",
                    style = MaterialTheme.typography.bodyMedium, color = palette.textSecondary,
                    modifier = Modifier.padding(vertical = 16.dp))
                return@Column
            }

            val is5G = sig?.isNr == true

            // ═════ 当前基站 ═════
            UfiSettingsGroup {
                UfiSectionGroupTitle("当前基站", "信号详情")

                val ratLabel = if (is5G) "5G" else sig?.rat?.takeIf { it.isNotBlank() } ?: "4G"
                // pci / arfcn 是非负量，-1 是「无数据」哨兵（见 contract 的 Units）
                val pci = sig?.pci?.takeIf { it >= 0 }?.toString()
                    ?: cellInfo?.ltePci?.takeIf { it.isNotBlank() } ?: ""
                val fcn = sig?.arfcn?.takeIf { it >= 0 }?.toString()
                    ?: cellInfo?.lteEarfcn?.takeIf { it.isNotBlank() } ?: ""
                val bandLabel = sig?.band_label?.takeIf { it.isNotBlank() }
                    ?: cellInfo?.lteBands?.takeIf { it.isNotBlank() }?.let { "B$it" }

                val rsrp = sig?.rsrp ?: cellInfo?.lteRsrp?.toIntOrNull()
                val sinr = sig?.sinr ?: cellInfo?.lteSnr?.toIntOrNull()
                val rsrq = sig?.rsrq ?: cellInfo?.lteRsrq?.toIntOrNull()

                // 制式标签（中性灰 chip）
                Row(verticalAlignment = Alignment.CenterVertically) {
                    UfiBadge(text = ratLabel, type = UfiBadgeType.INFO)
                    if (bandLabel != null) {
                        Spacer(Modifier.width(8.dp))
                        Text(bandLabel, style = MaterialTheme.typography.labelMedium,
                            color = palette.textSecondary)
                    }
                }
                Spacer(Modifier.height(10.dp))

                // 4 宫格指标（PCI / 频点 / SINR / RSRQ）
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MetricTile("PCI", pci.ifEmpty { "—" }, Modifier.weight(1f))
                        MetricTile("频点", fcn.ifEmpty { "—" }, Modifier.weight(1f))
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MetricTile("SINR", sinr?.let { "$it dB" } ?: "—", Modifier.weight(1f))
                        MetricTile("RSRQ", rsrq?.let { "$it dB" } ?: "—", Modifier.weight(1f))
                    }
                }

                rsrp?.let { r ->
                    Spacer(Modifier.height(12.dp))
                    SignalStrengthBarDlg(rsrp = r)
                }

                if (pci.isEmpty() && fcn.isEmpty() && rsrp == null) {
                    Spacer(Modifier.height(8.dp))
                    Text("暂无基站信息", style = MaterialTheme.typography.bodyMedium, color = palette.textSecondary)
                }

                if (pci.isNotEmpty() && fcn.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    UfiButton(
                        text = "锁定当前基站",
                        onClick = {
                            viewModel.network.cellLock(pci, fcn, if (is5G) "NR" else "LTE")
                            message = "已发送锁定当前基站命令 (PCI=$pci, FCN=$fcn)"
                            isLoading = true
                        }
                    )
                }
                UfiDivider(Modifier.padding(vertical = 12.dp))

                // ═════ 已锁定基站 ═════
                UfiSectionGroupTitle("已锁定基站", "当前锁定的基站列表")
                val lockedCells = parseLockedCellsDlg(cellInfo?.lockedCells)
                if (lockedCells.isEmpty()) {
                    Text("暂无已锁定基站", style = MaterialTheme.typography.bodyMedium,
                        color = palette.textSecondary, modifier = Modifier.padding(vertical = 4.dp))
                } else {
                    lockedCells.forEachIndexed { index, cell ->
                        if (index > 0) UfiDivider()
                        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            val ratText = if (cell.rat == "16") "5G" else "4G"
                            UfiBadge(text = ratText)
                            Spacer(Modifier.width(8.dp))
                            Text("PCI ${cell.pci}  ·  频点 ${cell.earfcn}",
                                style = UfiTextStyles.bodyEmphasis)
                        }
                    }
                }

                UfiDivider(Modifier.padding(vertical = 12.dp))

                // ═════ 邻区列表 ═════
                UfiSectionGroupTitle("邻区列表", "周边基站信号")
                // 实时端点优先（/api/network/neighbor-cells），它没拉到才回落 cell-info 的缓存快照
                val neighbors = parseNeighborCellsDlg(
                    state.neighborCells?.neighbors ?: cellInfo?.neighbors
                )
                if (neighbors.isEmpty()) {
                    Text("暂无邻区信息", style = MaterialTheme.typography.bodyMedium,
                        color = palette.textSecondary, modifier = Modifier.padding(vertical = 4.dp))
                } else {
                    neighbors.forEachIndexed { index, cell ->
                        if (index > 0) UfiDivider()
                        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                val isNR = inferIsNRDlg(cell.band, cell.earfcn, is5G)
                                val bandPrefix = if (isNR) "n" else "B"
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("$bandPrefix${cell.band}  PCI ${cell.pci}",
                                        style = UfiTextStyles.bodyEmphasis)
                                    Spacer(Modifier.width(8.dp))
                                    Text("FCN ${cell.earfcn}", style = MaterialTheme.typography.labelSmall,
                                        color = palette.textSecondary)
                                    if (isNR) {
                                        Spacer(Modifier.width(6.dp))
                                        UfiBadge(text = "5G", type = UfiBadgeType.INFO)
                                    }
                                }
                                Spacer(Modifier.height(3.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    cell.rsrp?.let { Text("RSRP $it", style = UfiTextStyles.caption,
                                        color = palette.textSecondary) }
                                    cell.sinr?.let { Text("SINR $it", style = MaterialTheme.typography.labelSmall,
                                        color = palette.textSecondary) }
                                    cell.rsrq?.let { Text("RSRQ $it", style = MaterialTheme.typography.labelSmall,
                                        color = palette.textSecondary) }
                                }
                            }
                            UfiButton(
                                size = UfiButtonSize.Small,
                                text = "锁定",
                                onClick = {
                                    viewModel.network.cellLock(cell.pci, cell.earfcn,
                                        if (inferIsNRDlg(cell.band, cell.earfcn, is5G)) "NR" else "LTE")
                                    message = "已锁定 PCI ${cell.pci}"
                                }
                            )
                        }
                    }
                }
            }

            // ═══ 底部操作 + 警告（合并为一张卡） ═══
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.CardHorizontalMargin, vertical = Spacing.CardBottomMargin)
                    .ufiStandardCard()
                    .padding(Spacing.CardPadding)
            ) {
                Column {
                    // 警告提示置顶
                    Surface(
                        color = palette.warning.copy(alpha = 0.1f),
                        shape = UfiCardDefaults.microShape,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            "注意：锁定错误基站可能导致信号不稳定。建议仅锁定当前已连接的基站。",
                            style = MaterialTheme.typography.bodySmall, color = palette.warning,
                            modifier = Modifier.padding(12.dp)
                        )
                    }

                    Spacer(Modifier.height(16.dp))

                    // 操作按钮行
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        UfiButton(
                            variant = UfiButtonVariant.Secondary,
                            text = "刷新",
                            onClick = { isLoading = true; viewModel.network.loadCellInfo(); message = null },
                            modifier = Modifier.weight(1f)
                        )
                        UfiButton(
                            variant = UfiButtonVariant.Danger,
                            text = "解锁全部",
                            onClick = { viewModel.network.unlockAllCell(); message = "已解锁全部"; isLoading = true },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            Spacer(Modifier.height(32.dp))
            }
        }
    }
}

// ── 指标小卡（卡片内嵌：纯透明底 + 文字排版，无任何背景/边框） ──
@Composable
private fun MetricTile(label: String, value: String, modifier: Modifier = Modifier) {
    val palette = LocalResolvedPalette.current
    Column(modifier.padding(10.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = palette.textSecondary)
        Spacer(Modifier.height(2.dp))
        Text(value, style = UfiTextStyles.sectionTitle,
            color = palette.textPrimary)
    }
}

// ── 信号强度可视化条（统一 accent 单色，走 palette token） ──
@Composable
private fun SignalStrengthBarDlg(rsrp: Int) {
    val palette = LocalResolvedPalette.current
    val quality = ((rsrp + 120).toFloat() / 60f).coerceIn(0f, 1f)
    Column {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("信号强度", style = MaterialTheme.typography.labelSmall, color = palette.textSecondary)
            Spacer(Modifier.width(6.dp))
            Text(
                when { quality > 0.8f -> "优"; quality > 0.6f -> "良"; quality > 0.3f -> "一般"; else -> "差" },
                style = UfiTextStyles.captionStrong,
                color = palette.textSecondary)
        }
        Spacer(Modifier.height(4.dp))
        LinearProgressIndicator(progress = { quality }, modifier = Modifier.fillMaxWidth().height(6.dp),
            color = palette.accent, trackColor = palette.cardBorder)
    }
}

// ── 列表元素解析 ──
// 容器字段的「数组 or 数组字符串」两种形态已由 CellInfoResponse 统一（asArray），这里只解元素。
// 元素键名（pci / earfcn / rsrp / rsrq / sinr / rat）由 core 归一，见 DeviceFields.CellInfo.ITEM_*。
private fun parseNeighborCellsDlg(arr: JsonArray?): List<CellItem> {
    if (arr == null) return emptyList()
    return arr.mapNotNull { item ->
        try {
            val obj = item.jsonObject
            CellItem(
                band = obj["band"]?.jsonPrimitive?.content ?: "", earfcn = obj["earfcn"]?.jsonPrimitive?.content ?: "",
                pci = obj["pci"]?.jsonPrimitive?.content ?: "", rsrp = obj["rsrp"]?.jsonPrimitive?.content?.toIntOrNull(),
                rsrq = obj["rsrq"]?.jsonPrimitive?.content?.toIntOrNull(), sinr = obj["sinr"]?.jsonPrimitive?.content?.toIntOrNull()
            ).takeIf { it.pci.isNotEmpty() }
        } catch (_: Exception) { null }
    }.sortedByDescending { it.rsrp ?: -999 }
}

private fun parseLockedCellsDlg(arr: JsonArray?): List<LockedCellItem> {
    if (arr == null) return emptyList()
    return arr.mapNotNull { item ->
        try {
            val obj = item.jsonObject
            LockedCellItem(
                earfcn = obj["earfcn"]?.jsonPrimitive?.content ?: "", pci = obj["pci"]?.jsonPrimitive?.content ?: "",
                rat = obj["rat"]?.jsonPrimitive?.content ?: "12"
            ).takeIf { it.pci.isNotEmpty() }
        } catch (_: Exception) { null }
    }
}

/**
 * 邻区是 NR 还是 LTE：先看频点/频段号的量级（NR-ARFCN > 100000、NR band 号可超 255），
 * 都判不出来时借服务小区的制式（[servingIsNr]，来自 `SignalInfo.isNr`）做推断。
 */
private fun inferIsNRDlg(band: String, earfcn: String, servingIsNr: Boolean): Boolean {
    val bandNum = band.toIntOrNull() ?: 0
    val earfcnNum = earfcn.toIntOrNull() ?: 0
    if (earfcnNum > 100000) return true
    if (bandNum > 255) return true
    return servingIsNr && bandNum in 1..255
}

