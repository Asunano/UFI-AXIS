package com.ufi_axis.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.ufi_axis.ui.components.*
import com.ufi_axis.ui.components.common.*
import com.ufi_axis.ui.navigation.Routes
import com.ufi_axis.ui.theme.LocalResolvedPalette
import com.ufi_axis.ui.theme.Spacing
import com.ufi_axis.ui.theme.UfiCardDefaults
import com.ufi_axis.ui.theme.UfiTextStyles
import com.ufi_axis.ui.theme.ufiCardShadow
import com.ufi_axis.viewmodel.MainViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: MainViewModel,
    onServerConfigChanged: () -> Unit,
    navController: NavHostController? = null,
    onRepairRequested: () -> Unit = {}
) {
    // 危险操作二次确认（UID-005）：shutdown / reboot / restart_service，点击后先弹确认。
    var pendingPowerOp by remember { mutableStateOf<String?>(null) }

    // 服务状态（进入页面即拉一次；开关/重启后由 ViewModel 自行回写）
    val serviceState by viewModel.serviceState.collectAsState()
    LaunchedEffect(Unit) { viewModel.network.loadServiceStatus() }

    UfiScreenScaffold(title = "设置") { padding ->
        // 入场动画已上移到 MainNavGraph 根节点（"app-launch"），只在冷启动播一次；
        // 页面级 blurEntrance / staggeredEntrance 会在切 Tab、从二级页返回时重播，观感是抖动。
        UfiPageBackground(modifier = Modifier.padding(padding)) {

            // 对齐网络主页布局：每项都是独立的行卡（公共 UfiSettingsRowCard），10dp 间距，无分隔线。
            // 2026-08-30：原来这里手搓 rowShape + rowModifier（ufiCardShadow/clip/background/border 四链），
            // 与网络页各写一份，改一处漏一处 —— 现在容器统一由公共组件提供。
            val palette = LocalResolvedPalette.current

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                // ═══════════ 设备与服务 — Hero 卡 + 快捷操作（2026-08-26 改版）═══════════
                // 上半是 Hero：大号状态（图标底色随状态变化）+ 一句话说明 + 徽标；
                // 下半是 4 个等宽快捷操作按钮（启停 / 重启服务 / 关机 / 重启设备）；
                // 开机自启作为唯一的常规设置项留在卡底。
                // 2026-08-28：从页面最底部上移到顶部（原来压在四个行卡之后，视觉重心太靠下）。
                UfiSettingsRowCard(contentPadding = PaddingValues(20.dp)) {
                    val statusColor = when {
                        serviceState.restarting -> palette.accentSecondary
                        !serviceState.loaded -> palette.textSecondary
                        serviceState.enabled && serviceState.collecting -> palette.accent
                        serviceState.enabled -> palette.accentSecondary
                        else -> palette.error
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {

                        // ── Hero ──
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(
                                shape = CircleShape,
                                color = statusColor.copy(alpha = 0.12f),
                                modifier = Modifier.size(56.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(Icons.Default.Dns, null, Modifier.size(28.dp), tint = statusColor)
                                }
                            }
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        when {
                                            serviceState.restarting -> "重启中"
                                            !serviceState.loaded -> "服务状态未知"
                                            serviceState.enabled && serviceState.collecting -> "运行中"
                                            serviceState.enabled -> "未采集"
                                            else -> "已停止"
                                        },
                                        style = UfiTextStyles.screenTitle,
                                        color = palette.textPrimary
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        "设备与服务",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = palette.textSecondary.copy(alpha = 0.7f)
                                    )
                                }
                                Text(
                                    when {
                                        serviceState.restarting -> "后端服务正在重启，约 10 秒后恢复"
                                        !serviceState.loaded -> "正在读取服务状态…"
                                        serviceState.enabled && serviceState.collecting ->
                                            "已运行 ${formatUptime(serviceState.uptimeMs)} · 数据采集与告警检测正常"
                                        serviceState.enabled -> "开关已开但采集循环未运行，建议重启服务"
                                        else -> "后台采集已停止，HTTP 服务仍在运行"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = palette.textSecondary.copy(alpha = 0.7f)
                                )
                            }
                        }

                        // ── 快捷操作 ──
                        // busy/重启中一律禁用启停与重启，避免 13 秒重启窗口里被连点触发多次重启
                        val serviceActionEnabled = serviceState.loaded && !serviceState.isBusy && !serviceState.restarting
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            QuickActionButton(
                                modifier = Modifier.weight(1f),
                                icon = if (serviceState.loaded && serviceState.enabled) Icons.Default.Stop else Icons.Default.PlayArrow,
                                label = if (serviceState.loaded && serviceState.enabled) "停止服务" else "启动服务",
                                enabled = serviceActionEnabled,
                                onClick = { viewModel.network.setBackgroundService(!serviceState.enabled) }
                            )
                            QuickActionButton(
                                modifier = Modifier.weight(1f),
                                icon = Icons.Default.Refresh,
                                label = "重启服务",
                                enabled = serviceActionEnabled,
                                onClick = { pendingPowerOp = "restart_service" }
                            )
                            QuickActionButton(
                                modifier = Modifier.weight(1f),
                                icon = Icons.Default.PowerSettingsNew,
                                label = "关机",
                                enabled = !serviceState.restarting,
                                danger = true,
                                onClick = { pendingPowerOp = "shutdown" }
                            )
                            QuickActionButton(
                                modifier = Modifier.weight(1f),
                                icon = Icons.Default.RestartAlt,
                                label = "重启设备",
                                enabled = !serviceState.restarting,
                                danger = true,
                                onClick = { pendingPowerOp = "reboot" }
                            )
                        }

                        UfiDivider()

                        // ── 开机自启（唯一常规设置项；启停已上移到快捷操作） ──
                        UfiSettingsItem(
                            title = "开机自启",
                            description = "设备重启后自动拉起后端服务",
                            enabled = serviceState.loaded,
                            trailing = {
                                UfiSwitch(
                                    checked = serviceState.autoStartOnBoot,
                                    enabled = serviceState.loaded,
                                    onCheckedChange = { viewModel.network.setServiceAutoStart(it) }
                                )
                            }
                        )
                    }
                }

                // 外观
                UfiSettingsRowCard {
                    UfiSettingsItem(
                        icon = Icons.Default.Palette,
                        title = "外观",
                        description = "主题、深色模式、配色预设",
                        onClick = { navController?.navigate("detail/appearance") },
                        trailing = { UfiSettingsChevron() }
                    )
                }
                // 服务器（合并：服务器配置 / 设备控制 / 配对管理）
                UfiSettingsRowCard {
                    UfiSettingsItem(
                        icon = Icons.Default.Dns,
                        title = "服务器",
                        description = "Core API · 设备控制 · 配对管理",
                        onClick = { navController?.navigate(Routes.DETAIL_SERVER) },
                        trailing = { UfiSettingsChevron() }
                    )
                }
                // 通知与守护（合并：系统告警 / 后台守护）
                UfiSettingsRowCard {
                    UfiSettingsItem(
                        icon = Icons.Default.NotificationsActive,
                        title = "通知与守护",
                        description = "系统告警 · 后台通知",
                        onClick = { navController?.navigate(Routes.DETAIL_NOTIFICATIONS_GUARD) },
                        trailing = { UfiSettingsChevron() }
                    )
                }
                // 关于设备
                UfiSettingsRowCard {
                    UfiSettingsItem(
                        icon = Icons.Default.Info,
                        title = "关于设备",
                        description = "固件版本 · 基带版本 · 设备信息",
                        onClick = { navController?.navigate(Routes.DETAIL_ABOUT) },
                        trailing = { UfiSettingsChevron() }
                    )
                }
            }

            // ── 危险操作二次确认弹窗（UID-005） ──
            pendingPowerOp?.let { op ->
                val (title, text) = when (op) {
                    "shutdown" -> "确认关机" to "确定要关闭设备吗？设备将短暂断开，正在进行的下载与传输会中断。"
                    "restart_service" -> "确认重启服务" to "将重启后端服务（不重启设备）。HTTP 接口会中断约 10 秒，之后自动恢复。"
                    else -> "确认重启设备" to "确定要重启设备吗？设备将短暂断开，正在进行的下载与传输会中断，预计数秒后恢复。"
                }
                UfiConfirmDialog(
                    title = title,
                    text = text,
                    confirmText = when (op) {
                        "shutdown" -> "关机"
                        "restart_service" -> "重启服务"
                        else -> "重启"
                    },
                    dismissText = "取消",
                    destructive = true,
                    onConfirm = {
                        when (op) {
                            "shutdown" -> viewModel.network.shutdownDevice()
                            "restart_service" -> viewModel.network.restartBackendService()
                            else -> viewModel.network.rebootDevice()
                        }
                        pendingPowerOp = null
                    },
                    onDismiss = { pendingPowerOp = null }
                )
            }

            Spacer(Modifier.height(Spacing.Large))
        }
    }
}

/**
 * Hero 卡里的快捷操作按钮：淡色底 + 图标 + 短标签，等宽铺满一行。
 * `danger=true` 走 error 色（关机 / 重启设备），其余走主题主色。
 */
@Composable
private fun QuickActionButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    danger: Boolean = false,
) {
    val palette = LocalResolvedPalette.current
    val tint = when {
        !enabled -> palette.textSecondary.copy(alpha = 0.45f)
        danger -> palette.error
        else -> palette.accent
    }
    val shape = UfiCardDefaults.shape
    Column(
        modifier = modifier
            .clip(shape)
            .background(tint.copy(alpha = if (enabled) 0.10f else 0.05f))
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Icon(icon, null, Modifier.size(22.dp), tint = tint)
        Text(label, style = MaterialTheme.typography.labelSmall, color = tint, maxLines = 1)
    }
}

/** 运行时长格式化：`0` → "—"，否则按 天/时/分/秒 就近两级显示。 */
private fun formatUptime(ms: Long): String {
    if (ms <= 0L) return "—"
    val totalSec = ms / 1000
    val d = totalSec / 86_400
    val h = (totalSec % 86_400) / 3_600
    val m = (totalSec % 3_600) / 60
    val s = totalSec % 60
    return when {
        d > 0 -> "$d 天 $h 小时"
        h > 0 -> "$h 小时 $m 分"
        m > 0 -> "$m 分 $s 秒"
        else -> "$s 秒"
    }
}
