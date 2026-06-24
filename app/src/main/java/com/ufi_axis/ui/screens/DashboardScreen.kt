package com.ufi_axis.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.navigation.NavHostController
import com.ufi_axis.ui.animation.blurEntrance
import com.ufi_axis.ui.components.*
import com.ufi_axis.ui.components.common.*
import com.ufi_axis.ui.theme.*
import com.ufi_axis.ui.util.rememberIsActive
import com.ufi_axis.ui.util.useAutoRefresh
import com.ufi_axis.util.FormatUtils
import com.ufi_axis.util.FormatUtils.sanitizeUnknown
import com.ufi_axis.viewmodel.MainViewModel
import com.ufi_axis.viewmodel.state.DashboardState
import kotlin.math.sin

/** 格式化百分比（Double → 1 位小数） */
private fun formatPercent(value: Double): String = "%.1f%%".format(value)

/** 格式化存储容量 */
private fun formatStorage(bytes: Long): String {
    val gb = bytes / (1024.0 * 1024.0 * 1024.0)
    return "%.1f GB".format(gb)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(viewModel: MainViewModel, navController: NavHostController) {
    val state by viewModel.dashboardState.collectAsState()
    var isRefreshing by remember { mutableStateOf(false) }
    LaunchedEffect(state.isLoading) { if (!state.isLoading) isRefreshing = false }

    val isActive = rememberIsActive()
    LaunchedEffect(isActive.value) {
        if (isActive.value) viewModel.dashboard.startAutoRefresh(10_000) else viewModel.dashboard.stopAutoRefresh()
    }
    DisposableEffect(Unit) {
        onDispose { viewModel.dashboard.stopAutoRefresh() }
    }

    UfiScreenScaffold(title = "UFI-AXIS", actions = {
        IconButton(onClick = { viewModel.dashboard.refreshDashboard() }) { Icon(Icons.Default.Refresh, "刷新") }
    }) { padding ->
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = { isRefreshing = true; viewModel.dashboard.refreshDashboard() },
            modifier = Modifier.fillMaxSize().padding(padding)
        ) {
            UfiPageBackground(modifier = Modifier.blurEntrance("dashboard"), useGradient = true, contentHPadding = Spacing.CardHorizontalMargin) {
                if (state.isOffline) {
                    UfiOfflineBanner(lastUpdated = state.lastUpdated?.let { FormatUtils.formatRelativeTime(it) })
                }
                state.errorMessage?.let { e ->
                    UfiErrorBanner(message = e, onRetry = { viewModel.dashboard.refreshDashboard() })
                }
                if (state.isLoading && state.deviceInfo == null) {
                    UfiShimmerLoading()
                    return@UfiPageBackground
                }

                // ═══════════ ① Hero: 设备信息 + 网络状态 + 实时网速 + 流量液动进度 ═══════════
                TrafficLiquidHero(state)

                Spacer(Modifier.height(Spacing.SectionTop))

                // ═══════════ ② 核心指标卡片 2x2 ═══════════
                Row(
                    Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // CPU 负载卡片
                    DashboardMetricCard(
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        title = "CPU 负载",
                        icon = Icons.Default.Memory,
                        value = state.cpuInfo?.let { formatPercent(it.usage_percent) } ?: "--",
                        valueColor = state.cpuInfo?.let {
                            when {
                                it.usage_percent > 80 -> SignalDead
                                it.usage_percent > 50 -> SignalFair
                                else -> SignalExcellent
                            }
                        },
                        progress = state.cpuInfo?.let { (it.usage_percent / 100.0).toFloat() },
                        progressColor = state.cpuInfo?.let {
                            when {
                                it.usage_percent > 80 -> SignalDead
                                it.usage_percent > 50 -> SignalFair
                                else -> SignalExcellent
                            }
                        },
                        subtitle = buildString {
                            state.cpuInfo?.let { append("${it.core_count}核") }
                            state.cpuInfo?.cores?.maxByOrNull { it.freq_mhz }?.let {
                                append(" · ${it.freq_display}")
                            }
                        }.ifEmpty { null },
                        onCardClick = { navController.navigate("detail/system") }
                    )
                    // 温度卡片
                    DashboardMetricCard(
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        title = "温度",
                        icon = Icons.Default.DeviceThermostat,
                        value = state.cpuInfo?.let {
                            FormatUtils.formatTemperature(it.temperature)
                        } ?: "--",
                        valueColor = state.cpuInfo?.temperature?.let { t ->
                            when {
                                t > 80 -> SignalDead
                                t > 60 -> SignalFair
                                else -> null
                            }
                        },
                        progress = state.cpuInfo?.let { ((it.temperature / 100.0).coerceIn(0.0, 1.0)).toFloat() },
                        progressColor = state.cpuInfo?.temperature?.let { t ->
                            when {
                                t > 80 -> SignalDead
                                t > 60 -> SignalFair
                                else -> SignalExcellent
                            }
                        },
                        subtitle = state.batteryInfo?.let { b ->
                            "${b.temperature}°C 电池"
                        },
                        onCardClick = { navController.navigate("detail/system") }
                    )
                }

                Spacer(Modifier.height(8.dp))

                Row(
                    Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // 电池卡片
                    DashboardMetricCard(
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        title = "电池",
                        icon = Icons.Default.BatteryChargingFull,
                        value = state.batteryInfo?.let { "${it.percent}%" } ?: "--",
                        valueColor = state.batteryInfo?.let { batteryColor(it.percent) },
                        progress = state.batteryInfo?.let { (it.percent / 100f).coerceIn(0f, 1f) },
                        progressColor = state.batteryInfo?.let { batteryColor(it.percent) },
                        subtitle = buildString {
                            state.batteryInfo?.let { b ->
                                if (b.is_charging) append("⚡充电中") else append("放电中")
                                append(" · ${b.voltage}V")
                            }
                        }.ifEmpty { null },
                        onCardClick = { navController.navigate("detail/system") }
                    )
                    // 内部储存卡片
                    DashboardMetricCard(
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        title = "储存",
                        icon = Icons.Default.SdStorage,
                        value = state.storageInfo?.let { formatPercent(it.usage_percent) } ?: "--",
                        valueColor = state.storageInfo?.let {
                            when {
                                it.usage_percent > 90 -> SignalDead
                                it.usage_percent > 70 -> SignalFair
                                else -> null
                            }
                        },
                        progress = state.storageInfo?.let { (it.usage_percent / 100.0).toFloat() },
                        progressColor = state.storageInfo?.let {
                            when {
                                it.usage_percent > 90 -> SignalDead
                                it.usage_percent > 70 -> SignalFair
                                else -> SignalExcellent
                            }
                        },
                        subtitle = state.storageInfo?.let { s ->
                            "已用${formatStorage(s.used)} / ${formatStorage(s.total)}"
                        },
                        onCardClick = { navController.navigate("detail/system") }
                    )
                }

                // ═══════════ ③ 设备信息（优化显示） ═══════════
                state.deviceInfo?.let { info ->
                    Spacer(Modifier.height(Spacing.SectionTop))
                    DeviceInfoCard(info, state)
                }

                // ═══════════ ④ 快捷入口网格 ═══════════
                Spacer(Modifier.height(Spacing.SectionTop))
                UfiSectionHeader(title = "快捷功能")
                DashboardQuickGrid(
                    items = listOf(
                        DashboardQuickItem("文件管理", Icons.Default.FolderOpen, "浏览/管理文件") {
                            navController.navigate("detail/files")
                        },
                        DashboardQuickItem("下载管理", Icons.Default.CloudDownload, "远程下载/NAS") {
                            navController.navigate("detail/downloads")
                        },
                        DashboardQuickItem("监控中心", Icons.Default.Timeline, "性能/信号历史") {
                            navController.navigate("detail/monitor")
                        },
                        DashboardQuickItem("系统详情", Icons.Default.Dns, "CPU/内存/电池") {
                            navController.navigate("detail/system")
                        },
                        DashboardQuickItem("应用管理", Icons.Default.Apps, "安装/卸载/冻结") {
                            navController.navigate("detail/apps")
                        },
                        DashboardQuickItem("网络详情", Icons.Default.WifiFind, "信号/流量详情") {
                            navController.navigate("detail/network")
                        },
                        DashboardQuickItem("高级工具", Icons.Default.Terminal, "TTYD/iPerf3") {
                            navController.navigate("detail/advanced")
                        },
                        DashboardQuickItem("调试日志", Icons.Default.BugReport, "后端日志查看") {
                            navController.navigate("detail/debug-log")
                        },
                    )
                )

                Spacer(Modifier.height(Spacing.Large))
            }
        }
    }
}

// ══════════════════════════════════════════════════════
// ① Hero：液动流量进度 Hero 卡片
//    - 背景：暗色渐变 + 从左到右浮动的「液体」填充（Canvas 波浪）
//    - 填充占比 = 本月已用 ÷ 限额
//    - 颜色分阶段：绿 (0~60%) → 黄 (60~85%) → 红 (85%+)
// ══════════════════════════════════════════════════════

/** 流量使用率对应的阶段颜色 */
private fun trafficStageColor(ratio: Float): Color = when {
    ratio < 0.60f -> Color(0xFF2ECC71)  // 绿 — 充裕
    ratio < 0.85f -> Color(0xFFF39C12)  // 黄 — 告警
    else          -> Color(0xFFE74C3C)  // 红 — 紧张
}

@Composable
private fun TrafficLiquidHero(state: DashboardState) {
    val info = state.deviceInfo ?: return
    val summary = state.trafficSummary
    val limit = state.trafficLimitConfig

    // ── 计算本月已用字节 + 限额字节 ──
    val usedBytes: Long = if (limit != null && limit.monthly_rx_bytes + limit.monthly_tx_bytes > 0) {
        limit.monthly_rx_bytes + limit.monthly_tx_bytes
    } else {
        // fallback: 从 summary 的显示文本估算（不够精确，但聊胜于无）
        0L
    }
    val limitBytes: Long = if (limit != null && limit.limit_size.isNotBlank()) {
        val size = limit.limit_size.toDoubleOrNull() ?: 0.0
        val mult = when (limit.limit_unit.uppercase()) {
            "GB" -> 1024.0 * 1024.0 * 1024.0
            "TB" -> 1024.0 * 1024.0 * 1024.0 * 1024.0
            else -> 1024.0 * 1024.0 // default MB
        }
        (size * mult).toLong()
    } else 0L

    val hasLimit = limitBytes > 0 && limit?.enabled == true
    val ratio = if (hasLimit && limitBytes > 0) {
        (usedBytes.toFloat() / limitBytes.toFloat()).coerceIn(0f, 1f)
    } else if (limitBytes > 0) {
        // 未启用但有数值：仅展示占比
        (usedBytes.toFloat() / limitBytes.toFloat()).coerceIn(0f, 1f)
    } else 0f

    val liquidColor = trafficStageColor(ratio)

    // ── 动画 ──
    val animatedProgress by animateFloatAsState(
        targetValue = ratio,
        animationSpec = tween(durationMillis = 1200, easing = FastOutSlowInEasing),
        label = "liquidProgress"
    )
    val animatedColor by animateColorAsState(
        targetValue = liquidColor,
        animationSpec = tween(durationMillis = 800),
        label = "liquidColor"
    )

    // 波浪相位循环动画
    val infiniteTransition = rememberInfiniteTransition(label = "waveMotion")
    val wavePhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 2f * Math.PI.toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(3000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "wavePhase"
    )

    // 液体表层微波动画
    val shimmerPhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "shimmer"
    )

    // 独立开关：用户可单独控制液动特效的显示
    var liquidAnimationEnabled by remember { mutableStateOf(true) }
    val liquidVisible = liquidAnimationEnabled && hasLimit && limit?.enabled == true

    // 不用 Card，直接用 Box 避免嵌套布局导致尺寸丢失
    // height(IntrinsicSize.Min) 关键：在 verticalScroll 容器中，Box 高度由内容 Column 决定，
    // 这样 Canvas 的 fillMaxHeight() 才能拿到有限高度，否则 Canvas 高度为 0
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .shadow(2.dp, UfiCardDefaults.widgetShape)
            .clip(UfiCardDefaults.widgetShape)
            .background(Color(0xFF1A1C2B))
    ) {
        // ── 液态填充层（仅在限额启用 + 动画开关打开时显示 Canvas 波浪） ──
        if (liquidVisible) {
            Canvas(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(animatedProgress.coerceAtLeast(0.01f))
                    .alpha(0.85f)
            ) {
                val w = size.width
                val h = size.height
                val waveAmp = 8.dp.toPx()
                val waveLen = w * 0.6f

                // 底层：纯色矩形
                drawRect(color = animatedColor)

                // 表层：波动高光条纹
                val highlightColor = animatedColor.copy(alpha = 0.35f)
                val stepY = h / 3f
                for (row in 0..2) {
                    val baseY = row * stepY + stepY / 2f
                    val path = Path()
                    path.moveTo(0f, baseY)
                    for (x in 0..w.toInt() step 8) {
                        val px = x.toFloat()
                        val angle = (px / waveLen * 2 * Math.PI + wavePhase + row * 0.8f).toFloat()
                        val py = baseY + sin(angle) * waveAmp * (0.5f + 0.5f * shimmerPhase)
                        path.lineTo(px, py)
                    }
                    drawPath(
                        path = path,
                        color = highlightColor,
                        style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
                    )
                }
            }

            // ── 液态前沿波浪边缘 ──
            Canvas(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(animatedProgress.coerceAtLeast(0.01f))
            ) {
                val w = size.width
                val h = size.height
                val edgeWidth = 24.dp.toPx()
                val startX = (w - edgeWidth).coerceAtLeast(0f)

                if (startX < w) {
                    val edgePath = Path()
                    edgePath.moveTo(startX, 0f)
                    for (y in 0..h.toInt() step 6) {
                        val py = y.toFloat()
                        val angle = (py / 60.dp.toPx() * 2 * Math.PI + wavePhase * 1.3f).toFloat()
                        val px = startX + sin(angle) * edgeWidth * 0.5f + edgeWidth * 0.5f
                        edgePath.lineTo(px, py)
                    }
                    edgePath.lineTo(startX, h)
                    edgePath.close()
                    drawPath(edgePath, color = animatedColor.copy(alpha = 0.9f))
                }
            }
        } // end if (liquidVisible)

        // ── 内容层（始终显示，不受液动开关影响） ──
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            // 第一行：设备名称 + 信号 + 特效开关
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = info.device?.model?.sanitizeUnknown() ?: "UFI Device",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Black,
                        color = Color.White
                    )
                    Spacer(Modifier.height(2.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val netType = state.networkStatus?.network_type?.sanitizeUnknown()
                        if (netType != null) {
                            Surface(
                                color = networkTypeColor(netType),
                                shape = UfiCardDefaults.smallShape
                            ) {
                                Text(
                                    " $netType ",
                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                                    color = Color.White,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.ExtraBold
                                )
                            }
                            Spacer(Modifier.width(6.dp))
                        }
                        Text(
                            text = state.networkStatus?.operator?.sanitizeUnknown() ?: "未知运营商",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.7f),
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
                // 液动特效独立开关
                if (hasLimit || (limitBytes > 0 && usedBytes > 0)) {
                    Surface(
                        color = Color.White.copy(alpha = 0.1f),
                        shape = UfiCardDefaults.inputShape
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.WaterDrop, null,
                                Modifier.size(14.dp),
                                tint = if (liquidAnimationEnabled) liquidColor else Color.White.copy(alpha = 0.25f)
                            )
                            Spacer(Modifier.width(4.dp))
                            Switch(
                                checked = liquidAnimationEnabled,
                                onCheckedChange = { liquidAnimationEnabled = it },
                                modifier = Modifier.height(20.dp),
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = liquidColor,
                                    checkedTrackColor = liquidColor.copy(alpha = 0.4f),
                                    uncheckedThumbColor = Color.White.copy(alpha = 0.4f),
                                    uncheckedTrackColor = Color.White.copy(alpha = 0.12f),
                                )
                            )
                        }
                    }
                }
                Spacer(Modifier.width(4.dp))
                // 信号柱状图
                state.signalInfo?.rsrp?.let {
                    UfiSignalBars(it, maxHeight = 24.dp, barWidth = 4.dp)
                }
            }

            Spacer(Modifier.height(10.dp))

            // 第二行：实时网速
            RealTimeSpeedInline(state)

            // ── 流量使用区（液动条的核心数值） ──
            if (hasLimit || (limitBytes > 0 && usedBytes > 0)) {
                Spacer(Modifier.height(14.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text("本月流量", style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.55f))
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = FormatUtils.formatBytes(usedBytes),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("限额", style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.55f))
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = if (limitBytes > 0) FormatUtils.formatBytes(limitBytes) else "无限制",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White.copy(alpha = 0.75f)
                        )
                    }
                }

                Spacer(Modifier.height(6.dp))

                // 百分比 + 剩余标签
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "${(ratio * 100).toInt()}% 已用",
                        style = MaterialTheme.typography.labelSmall,
                        color = animatedColor
                    )
                    if (hasLimit) {
                        Text(
                            text = "剩余 ${FormatUtils.formatBytes((limitBytes - usedBytes).coerceAtLeast(0))}",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.45f)
                        )
                    }
                }
            } else {
                // 无限额配置时：显示本月下载/上传简述
                if (summary != null) {
                    Spacer(Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Download, null, Modifier.size(12.dp), tint = TrafficDown)
                                Spacer(Modifier.width(3.dp))
                                Text(summary.month_rx_display, style = MaterialTheme.typography.bodyMedium,
                                    color = Color.White, fontWeight = FontWeight.Medium)
                            }
                            Text("本月下载", style = MaterialTheme.typography.labelSmall,
                                color = Color.White.copy(alpha = 0.45f))
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Upload, null, Modifier.size(12.dp), tint = TrafficUp)
                                Spacer(Modifier.width(3.dp))
                                Text(summary.month_tx_display, style = MaterialTheme.typography.bodyMedium,
                                    color = Color.White, fontWeight = FontWeight.Medium)
                            }
                            Text("本月上传", style = MaterialTheme.typography.labelSmall,
                                color = Color.White.copy(alpha = 0.45f))
                        }
                    }
                }
            }
        }
    }
}

/** 实时网速内联条（英雄卡片内使用，暗色背景适配） */
@Composable
private fun RealTimeSpeedInline(state: DashboardState) {
    val traffic = state.trafficRealtime
    Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(
            color = Color.White.copy(alpha = 0.12f),
            shape = UfiCardDefaults.chipShape
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Download, null, Modifier.size(12.dp), tint = TrafficDown)
                Spacer(Modifier.width(4.dp))
                Text(
                    text = traffic?.let {
                        if (it.rx_speed_display.isNotBlank()) it.rx_speed_display
                        else FormatUtils.formatBytes(it.rx_speed) + "/s"
                    } ?: "—",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
        }
        Spacer(Modifier.width(6.dp))
        Surface(
            color = Color.White.copy(alpha = 0.12f),
            shape = UfiCardDefaults.chipShape
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Upload, null, Modifier.size(12.dp), tint = TrafficUp)
                Spacer(Modifier.width(4.dp))
                Text(
                    text = traffic?.let {
                        if (it.tx_speed_display.isNotBlank()) it.tx_speed_display
                        else FormatUtils.formatBytes(it.tx_speed) + "/s"
                    } ?: "—",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
        }
    }
}

// ══════════════════════════════════════════════════════
// ② 指标卡片（带进度条）
// ══════════════════════════════════════════════════════

@Composable
private fun DashboardMetricCard(
    modifier: Modifier = Modifier,
    title: String,
    icon: ImageVector,
    value: String,
    valueColor: Color? = null,
    progress: Float? = null,
    progressColor: Color? = null,
    subtitle: String? = null,
    onCardClick: (() -> Unit)? = null,
) {
    Card(
        modifier = modifier
            .then(if (onCardClick != null) Modifier.clickable(onClick = onCardClick) else Modifier),
        shape = UfiCardDefaults.shape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = UfiCardDefaults.cardElevation(),
        border = UfiCardDefaults.cardLightBorder()
    ) {
        Column(Modifier.padding(12.dp)) {
            // 标题行
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = CircleShape,
                    color = (valueColor ?: MaterialTheme.colorScheme.primary).copy(alpha = 0.1f),
                    modifier = Modifier.size(28.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            icon, null, Modifier.size(16.dp),
                            tint = valueColor ?: MaterialTheme.colorScheme.primary
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    title,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Spacer(Modifier.height(6.dp))
            // 主数值
            Text(
                value,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.ExtraBold,
                color = valueColor ?: MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            // 进度条
            if (progress != null) {
                Spacer(Modifier.height(4.dp))
                LinearProgressIndicator(
                    progress = { progress.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(4.dp).clip(UfiCardDefaults.microShape),
                    color = progressColor ?: valueColor ?: MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                )
            }
            // 副标题
            if (subtitle != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

// ══════════════════════════════════════════════════════
// ③ 设备信息卡片（优化显示，信息更全）
// ══════════════════════════════════════════════════════

@Composable
private fun DeviceInfoCard(info: com.ufi_axis.data.model.DeviceInfoResponse, state: DashboardState) {
    val id = info.identity
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = UfiCardDefaults.shape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = UfiCardDefaults.cardElevation(),
        border = UfiCardDefaults.cardLightBorder()
    ) {
        Column(Modifier.padding(horizontal = Spacing.InnerPadding, vertical = 14.dp)) {
            // 标题
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.PhoneAndroid, null, Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(6.dp))
                Text("设备信息", style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(10.dp))

            // ── 本机号码 + WAN IP (第一行) ──
            val msisdn = id?.get("msisdn")?.takeIf { it.isNotBlank() }
            val wanIp = id?.get("wan_ipaddr")?.takeIf { it.isNotBlank() }
            val ipv6WanIp = id?.get("ipv6_wan_ipaddr")?.takeIf { it.isNotBlank() }
            DeviceInfoRow(
                leftLabel = "本机号码",
                leftValue = msisdn ?: "—",
                leftValueStyle = MaterialTheme.typography.titleSmall,
                rightLabel = if (wanIp != null) "WAN IP" else null,
                rightValue = wanIp,
                rightValueStyle = MaterialTheme.typography.bodyMedium
            )

            // IPv6 WAN
            if (ipv6WanIp != null) {
                Spacer(Modifier.height(6.dp))
                UfiDivider()
                Spacer(Modifier.height(6.dp))
                Text("WAN IPv6", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(2.dp))
                Text(ipv6WanIp,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface)
            }

            // IMEI
            val imei = id?.get("imei")?.takeIf { it.isNotBlank() }
            if (imei != null || id?.get("imsi")?.isNotBlank() == true) {
                Spacer(Modifier.height(6.dp))
                UfiDivider()
                Spacer(Modifier.height(6.dp))
                if (imei != null) {
                    Row(Modifier.fillMaxWidth()) {
                        Column {
                            Text(imei, style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface)
                            Text("IMEI", style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                id?.get("imsi")?.takeIf { it.isNotBlank() }?.let { imsi ->
                    if (imei != null) Spacer(Modifier.height(4.dp))
                    Text(imsi, style = MaterialTheme.typography.bodySmall,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("IMSI", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
                }
            }

            // ── 版本信息 ──
            val hwVer = id?.get("hardware_version")?.takeIf { it.isNotBlank() }
            val webVer = id?.get("web_version")?.takeIf { it.isNotBlank() }
                ?: id?.get("cr_version")?.takeIf { it.isNotBlank() }
            val kernel = info.kernel
            val sdkVer = info.device?.let { "Android ${it.android_version} (SDK ${it.sdk_version})" }

            if (hwVer != null || webVer != null || kernel != null || sdkVer != null) {
                Spacer(Modifier.height(6.dp))
                UfiDivider()
                Spacer(Modifier.height(6.dp))
            }

            // 硬件 / 固件
            if (hwVer != null || webVer != null) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    hwVer?.let {
                        Column(Modifier.weight(1f)) {
                            Text("硬件版本", style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(it, style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface)
                        }
                    }
                    webVer?.let {
                        Column(Modifier.weight(1f)) {
                            Text("固件版本", style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(it, style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                                color = MaterialTheme.colorScheme.onSurface)
                        }
                    }
                    // 对齐占位
                    if (hwVer != null && webVer == null) Spacer(Modifier.weight(1f))
                    if (webVer != null && hwVer == null) Spacer(Modifier.weight(1f))
                }
            }

            // Android / Kernel
            if (sdkVer != null || kernel != null) {
                if (hwVer != null || webVer != null) Spacer(Modifier.height(4.dp))
                sdkVer?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                kernel?.let {
                    Spacer(Modifier.height(2.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
                }
            }

            // 运行时间
            state.uptimeInfo?.let { up ->
                Spacer(Modifier.height(6.dp))
                UfiDivider()
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Timer, null, Modifier.size(12.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
                    Spacer(Modifier.width(4.dp))
                    Text("运行时间", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(up.uptime_display, style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}

@Composable
private fun DeviceInfoRow(
    leftLabel: String,
    leftValue: String,
    leftValueStyle: androidx.compose.ui.text.TextStyle,
    rightLabel: String?,
    rightValue: String?,
    rightValueStyle: androidx.compose.ui.text.TextStyle,
) {
    Row(Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) {
            Text(leftValue,
                style = leftValueStyle,
                fontWeight = FontWeight.Bold,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurface)
            Text(leftLabel, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (rightLabel != null && rightValue != null) {
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                Text(rightValue,
                    style = rightValueStyle,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface)
                Text(rightLabel, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

// ══════════════════════════════════════════════════════
// 快捷入口网格
// ══════════════════════════════════════════════════════

private data class DashboardQuickItem(
    val title: String,
    val icon: ImageVector,
    val description: String,
    val onClick: () -> Unit,
)

@Composable
private fun DashboardQuickGrid(
    items: List<DashboardQuickItem>,
    columns: Int = 4,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = UfiCardDefaults.shape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = UfiCardDefaults.cardElevation(),
        border = UfiCardDefaults.cardLightBorder()
    ) {
        Column(Modifier.padding(Spacing.InnerPadding)) {
            items.chunked(columns).forEachIndexed { rowIdx, rowItems ->
                if (rowIdx > 0) {
                    Spacer(Modifier.height(4.dp))
                }
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    rowItems.forEach { item ->
                        QuickGridCell(item, Modifier.weight(1f))
                    }
                    repeat(columns - rowItems.size) {
                        Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun QuickGridCell(
    item: DashboardQuickItem,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(UfiCardDefaults.legacyShape)
            .clickable(onClick = item.onClick)
            .padding(vertical = 12.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Surface(
            shape = UfiCardDefaults.largeSurfaceShape,
            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.4f),
            modifier = Modifier.size(48.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(item.icon, null, Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.onSecondaryContainer)
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(item.title, style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            fontWeight = FontWeight.Medium)
    }
}
