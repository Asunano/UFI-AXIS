// [F24] STABLE-UI-API：公共组件签名已冻结，请勿在无向后兼容前提下修改；实验性组件请使用 @UfiExperimentalApi（见 UfiStableApi.kt / UfiExperimentalApi.kt）。
package com.ufi_axis.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.ufi_axis.ui.components.common.*
import com.ufi_axis.ui.navigation.Routes
import com.ufi_axis.ui.theme.LocalResolvedPalette
import com.ufi_axis.ui.theme.Spacing
import com.ufi_axis.ui.theme.UfiCardDefaults
import com.ufi_axis.ui.theme.UfiTextStyles
import com.ufi_axis.viewmodel.MainViewModel
import kotlinx.coroutines.delay

/**
 * FIX-9（2026-08-23）：内网穿透主页改为入口卡片式列表（两个独立入口，无通用设置卡）。
 *
 * - **不再使用 TabRow**：FRP / Cloudflare Tunnel 各自独立详情页（DETAIL_TUNNEL_FRP / DETAIL_TUNNEL_CF）。
 * - **主页两张入口卡片**：左侧品牌色块圆 + 大图标（Hub / Cloud），右侧标题 + 版本或未安装状态。
 * - **顶部工具栏右上角「停止全部」按钮**：有任意实例在运行时显示，一键停掉两个引擎的全部实例。
 * - **5s 自动刷新**：保留原行为，loadStatus() 周期轮询。
 *
 * 2026-09-02：组件管理整块移到「内网穿透设置」页（Routes.DETAIL_COMPONENTS，常驻入口），本页不再
 * 拉组件清单也不再轮询安装任务；入口卡片只保留「标题 + 版本/未安装」，去掉产品介绍类副标题
 * 与隧道计数详情行 —— 隧道数量在各自的列表页里一眼可见，主页重复一遍只是噪声。
 */
@Composable
fun TunnelScreen(viewModel: MainViewModel, navController: NavHostController) {
    val state by viewModel.tunnelState.collectAsState()
    val palette = LocalResolvedPalette.current
    var toastMessage by remember { mutableStateOf<ToastMessage?>(null) }

    LaunchedEffect(Unit) {
        viewModel.tunnel.loadStatus()
        viewModel.tunnel.loadConfigs()
    }
    // 5s 自动刷新（保留原行为）
    LaunchedEffect(Unit) {
        while (true) {
            delay(5000)
            viewModel.tunnel.loadStatus()
        }
    }

    UfiScreenScaffold(
        title = "内网穿透",
        navController = navController,
        showBack = true,
        actions = {
            if (state.anyRunning) {
                IconButton(onClick = {
                    viewModel.tunnel.stopAll()
                    // 不能在这里报"已停止"：强杀失败时后端会回 success=false，
                    // 真实结果由 errorMessage 横幅 / 下一轮 loadStatus 呈现
                    toastMessage = ToastMessage("正在停止所有隧道…", ToastType.INFO)
                }) {
                    Icon(Icons.Default.Stop, contentDescription = "停止全部")
                }
            }
            // 通用设置（通知/重连/日志）此前没有任何入口，路由注册了却进不去
            IconButton(onClick = { navController.navigate(Routes.DETAIL_TUNNEL_SETTINGS) }) {
                Icon(Icons.Default.Settings, contentDescription = "隧道设置")
            }
        }
    ) { padding ->
        UfiPageBackground(
            modifier = Modifier.padding(padding),
            contentHPadding = Spacing.PagePadding
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                state.errorMessage?.let { msg ->
                    UfiErrorBanner(message = msg)
                }

                // ── FRP 入口卡片 ──
                TunnelEntryCard(
                    title = "FRP",
                    icon = Icons.Default.Hub,
                    iconTint = palette.accent,
                    running = state.frpRunningCount > 0,
                    installed = state.frpInstalled,
                    version = state.frpVersion,
                    onClick = { navController.navigate(Routes.DETAIL_TUNNEL_FRP) }
                )

                // ── Cloudflare Tunnel 入口卡片 ──
                TunnelEntryCard(
                    title = "Cloudflare Tunnel",
                    icon = Icons.Default.Cloud,
                    iconTint = palette.accentSecondary,
                    running = state.cfRunningCount > 0,
                    installed = state.cfInstalled,
                    version = state.cfVersion,
                    onClick = { navController.navigate(Routes.DETAIL_TUNNEL_CF) }
                )

                Spacer(Modifier.height(Spacing.Medium))
                UfiToastHost(toastMessage = toastMessage, onDismiss = { toastMessage = null })
            }
        }
    }
}

/**
 * 内网穿透入口卡片：左侧品牌色块圆 + 大图标，右侧标题 + 版本/未安装，末尾操作箭头。
 *
 * 图标恒定用 [iconTint] 着色（不随运行状态变灰）：两个入口靠颜色区分身份，
 * 运行状态由标题右侧的「运行中」徽标表达，两者职责分开。
 */
@Composable
private fun TunnelEntryCard(
    title: String,
    icon: ImageVector,
    iconTint: Color,
    running: Boolean,
    installed: Boolean,
    version: String,
    onClick: () -> Unit
) {
    val palette = LocalResolvedPalette.current
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = UfiCardDefaults.dialogShape,
        color = palette.cardBg,
        border = BorderStroke(1.dp, palette.cardBorder)
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(UfiCardDefaults.shape)
                    .background(iconTint.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(26.dp)
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        title,
                        style = UfiTextStyles.panelTitleStrong,
                        color = palette.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (running) {
                        Spacer(Modifier.width(8.dp))
                        Surface(
                            color = palette.accent.copy(alpha = 0.14f),
                            shape = UfiCardDefaults.tagShape
                        ) {
                            Text(
                                "运行中",
                                style = UfiTextStyles.caption,
                                color = palette.accent,
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
                Spacer(Modifier.height(3.dp))
                Text(
                    when {
                        !installed -> "未安装"
                        version.isNotBlank() -> "v$version"
                        else -> "已安装"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = "管理",
                tint = palette.textSecondary,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}
