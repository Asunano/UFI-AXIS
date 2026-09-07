package com.ufi_axis.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.ufi_axis.ui.components.common.*
import com.ufi_axis.ui.theme.*
import com.ufi_axis.viewmodel.MainViewModel
import com.ufi_axis_core.contract.NetworkMode

/**
 * 网络模式 — 独立页面（路由 detail/network-mode）。
 * ac 重构方案：英雄卡展示"当前在用什么" + 公共弹窗 UfiCustomDialog（网格磁贴）做制式选择。
 * 由 NetworkScreen「网络模式」导航进入，自带返回按钮（UfiScreenScaffold）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NetworkModeScreen(viewModel: MainViewModel, navController: NavHostController) {
    val state by viewModel.networkState.collectAsState()
    val deviceSettingsState by viewModel.deviceSettingsState.collectAsState()
    val palette = LocalResolvedPalette.current

    val connModeJson = connModeOf(deviceSettingsState.settings)
    val netModeJson = netModeOf(deviceSettingsState.settings)

    // T15：档位键统一为 contract 别名（标签与 NetworkModeDialog 共用 NETWORK_MODE_LABELS），
    // 设备回读的 net_select 是 Bearer 取值域，比对前先经 fromBearer 换算。
    val currentMode = NetworkMode.fromBearer(netModeJson)

    var showSheet by remember { mutableStateOf(false) }

    // 进入即刷新设备设置，保证初值准确
    LaunchedEffect(Unit) {
        viewModel.network.refreshNetwork()
        viewModel.network.loadDeviceSettings()
    }

    UfiScreenScaffold(
        title = "网络模式",
        navController = navController,
        showBack = true,
        actions = {
            IconButton(onClick = { viewModel.network.loadDeviceSettings() }) {
                Icon(Icons.Default.Refresh, null)
            }
        }
    ) { padding ->
        UfiPageBackground(modifier = Modifier.padding(padding)) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.CardHorizontalMargin)
            ) {
                Spacer(Modifier.height(8.dp))

                // ═══ 英雄卡：当前制式 + 更改入口（方案 A） ═══
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.CardHorizontalMargin, vertical = Spacing.CardBottomMargin)
                        .ufiStandardCard()
                        .padding(Spacing.CardPadding)
                ) {
                    Column(Modifier.fillMaxWidth()) {
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            UfiBadge(
                                text = if (connModeJson == "auto") "已连接" else "已断开",
                                type = if (connModeJson == "auto") UfiBadgeType.SUCCESS else UfiBadgeType.WARNING
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "当前网络",
                                style = MaterialTheme.typography.labelMedium,
                                color = palette.textSecondary
                            )
                            Spacer(Modifier.weight(1f))
                            Icon(
                                Icons.Default.SignalCellularAlt,
                                null,
                                Modifier.size(20.dp),
                                tint = palette.accent
                            )
                        }

                        Spacer(Modifier.height(14.dp))

                        Text(
                            NETWORK_MODE_LABELS[currentMode] ?: netModeJson,
                            style = UfiTextStyles.dialogTitle.copy(fontWeight = UfiWeight.Strong),
                            color = palette.textPrimary
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            if (connModeJson == "auto") "自动拨号 · 设备根据信号在 4G/5G 间自动切换"
                            else "手动拨号 · 由你指定网络制式",
                            style = MaterialTheme.typography.bodySmall,
                            color = palette.textSecondary
                        )

                        Spacer(Modifier.height(18.dp))

                        UfiButton(
                            text = "更改网络制式",
                            onClick = {
                                showSheet = true
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                Spacer(Modifier.height(Spacing.Medium))

                // ═══ 连接模式（小卡） ═══
                UfiSettingsGroup {
                    UfiSectionGroupTitle("连接模式", "选择拨号方式")
                    Spacer(Modifier.height(8.dp))
                    // 2026-08-31：两个手绘 FilterChip → 公共 UfiOptionGrid（双栏，leading 槽位保留勾选图标）
                    val isAuto = connModeJson == "auto"
                    UfiOptionGrid(
                        options = listOf(
                            UfiOptionItem(
                                value = "auto",
                                label = "自动拨号",
                                leading = {
                                    Icon(
                                        if (isAuto) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                                        null, Modifier.size(18.dp)
                                    )
                                }
                            ),
                            UfiOptionItem(
                                value = "manual",
                                label = "手动拨号",
                                leading = {
                                    Icon(
                                        if (!isAuto) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                                        null, Modifier.size(18.dp)
                                    )
                                }
                            )
                        ),
                        selectedValue = if (isAuto) "auto" else "manual",
                        onSelect = { viewModel.network.setConnectionMode(it) },
                        columns = 2
                    )
                }

                Spacer(Modifier.height(Spacing.Large))
            }
        }

        // ═══ 公共弹窗：UfiCustomDialog + 2列网格选择制式 ═══
        if (showSheet) {
            UfiCustomDialog(
                visible = showSheet,
                onDismiss = { showSheet = false },
                title = "选择网络制式",
                icon = rememberVectorPainter(Icons.Filled.SignalCellularAlt),
                showCloseButton = false
            ) {
                UfiDialogBody {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        contentPadding = PaddingValues(bottom = Spacing.Large),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        items(NetworkMode.UI_OPTIONS.size) { idx ->
                            val key = NetworkMode.UI_OPTIONS[idx]
                            val label = NETWORK_MODE_LABELS[key] ?: key
                            val isSelected = key == currentMode
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { viewModel.network.setNetworkMode(key); showSheet = false }
                                    .border(
                                        width = if (isSelected) 1.5.dp else 1.dp,
                                        color = if (isSelected) palette.accent else palette.cardBorder,
                                        shape = UfiCardDefaults.microShape
                                    )
                                    .background(
                                        color = if (isSelected) palette.accent.copy(alpha = 0.08f) else Color.Transparent,
                                        shape = UfiCardDefaults.microShape
                                    )
                                    .padding(14.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    label,
                                    style = UfiTextStyles.body.copy(
                                        fontWeight = if (isSelected) UfiWeight.Strong else UfiWeight.Medium
                                    ),
                                    color = if (isSelected) palette.accent else palette.textPrimary
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// parseConnModeDlg / parseNetModeDlg 定义在 NetworkModeDialog.kt（同 package internal，此处直接可见）
