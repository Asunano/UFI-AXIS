package com.ufi_axis.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.ufi_axis.ui.components.common.*
import com.ufi_axis.ui.theme.LocalResolvedPalette
import com.ufi_axis.ui.theme.Spacing
import com.ufi_axis.ui.theme.ufiStandardCard
import com.ufi_axis.viewmodel.MainViewModel

/**
 * 蜂窝网络高级设置（二级页面）
 *
 * 从网络主屏「蜂窝网络高级设置」单一入口进入，
 * 展示网络模式 / 频段锁定 / 基站信息 / DHCP 设置 / 网络功能等子入口。
 *
 * 2026-08-27：
 *  - 补 `showBack = true`。本模块其它二级页（频段锁定 / 网络模式 / 基站信息）都传了，
 *    只有这里和网络功能页漏了，进来之后没有返回箭头。
 *  - DHCP 设置由二级页面改回弹窗（[DhcpSettingsDialog]），路由 `detail/dhcp-settings` 一并删除。
 */
@Composable
fun CellularAdvancedScreen(viewModel: MainViewModel, navController: NavHostController) {
    val deviceSettingsState by viewModel.deviceSettingsState.collectAsState()
    val roamingEnabled = deviceSettingsState.settings?.roamingEnabled ?: false
    var showDhcpDialog by remember { mutableStateOf(false) }

    // 进入时加载 deviceSettings（漫游开关依赖此数据）
    LaunchedEffect(Unit) { viewModel.network.loadDeviceSettings() }

    UfiScreenScaffold(
        title = "蜂窝网络高级设置",
        navController = navController,
        showBack = true
    ) { padding ->
        val palette = LocalResolvedPalette.current

        Column(
            Modifier
                .padding(padding)
                .fillMaxWidth()
                .padding(horizontal = Spacing.CardHorizontalMargin),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Spacer(Modifier.height(8.dp))

            // ═══ 数据漫游开关（独立行卡片） ═══
            Box(Modifier.ufiStandardCard().padding(horizontal = 16.dp, vertical = 4.dp)) {
                UfiSettingsToggle(
                    icon = Icons.Default.Public,
                    title = "数据漫游",
                    description = if (roamingEnabled) "漫游中" else "已关闭",
                    checked = roamingEnabled,
                    onCheckedChange = { viewModel.network.setRoamingEnabled(it) }
                )
            }

            // ═══ 网络模式 / 频段锁定 / APN / 基站信息（合一卡片） ═══
            Box(Modifier.ufiStandardCard().padding(16.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    // 网络模式
                    NavRowItem(
                        icon = Icons.Default.SettingsInputAntenna,
                        title = "网络模式",
                        subtitle = "连接模式 · 网络制式",
                        onClick = { navController.navigate("detail/network-mode") },
                        palette = palette
                    )

                    HorizontalDivider(color = palette.divider.copy(alpha = 0.3f))

                    // 频段锁定
                    NavRowItem(
                        icon = Icons.Default.Tune,
                        title = "频段锁定",
                        subtitle = "锁定指定 LTE / NR 频段",
                        onClick = { navController.navigate("detail/band-lock") },
                        palette = palette
                    )

                    HorizontalDivider(color = palette.divider.copy(alpha = 0.3f))

                    // 基站信息
                    NavRowItem(
                        icon = Icons.Default.GpsFixed,
                        title = "基站信息",
                        subtitle = "查看/锁定邻区基站",
                        onClick = { navController.navigate("detail/cell-lock") },
                        palette = palette
                    )
                }
            }

            // ═══ DHCP 设置 / 网络功能（合一卡片） ═══
            Box(Modifier.ufiStandardCard().padding(16.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    NavRowItem(
                        icon = Icons.Default.Link,
                        title = "DHCP 设置",
                        subtitle = "局域网地址分配与租约配置",
                        onClick = { showDhcpDialog = true },
                        palette = palette
                    )

                    HorizontalDivider(color = palette.divider.copy(alpha = 0.3f))

                    NavRowItem(
                        icon = Icons.Default.Extension,
                        title = "网络功能",
                        subtitle = "FOTA · USB 共享 · SAMBA",
                        onClick = { navController.navigate("detail/network-features") },
                        palette = palette
                    )
                }
            }

            Spacer(Modifier.height(32.dp))
        }

        DhcpSettingsDialog(
            viewModel = viewModel,
            visible = showDhcpDialog,
            onDismiss = { showDhcpDialog = false }
        )
    }
}

/** 导航行项（图标 + 标题 + 副标题 + 箭头）— 蜂窝高级设置内复用 */
@Composable
private fun NavRowItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    palette: com.ufi_axis.ui.theme.ResolvedPalette,
) {
    Row(
        Modifier.clickable(onClick = onClick).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, Modifier.size(24.dp), tint = palette.accent)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = palette.textPrimary)
            Text(subtitle, style = MaterialTheme.typography.bodySmall,
                color = palette.textSecondary.copy(alpha = 0.7f))
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = palette.textSecondary)
    }
}
