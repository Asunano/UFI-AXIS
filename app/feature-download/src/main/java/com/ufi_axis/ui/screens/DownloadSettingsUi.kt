package com.ufi_axis.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.KeyboardType
import androidx.navigation.NavHostController
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import com.ufi_axis.ui.components.common.*
import com.ufi_axis.ui.navigation.Routes
import com.ufi_axis.ui.theme.Spacing
import com.ufi_axis.ui.theme.UfiCardDefaults
import com.ufi_axis.ui.theme.UfiTextStyles
import com.ufi_axis.ui.theme.UfiWeight
import com.ufi_axis.ui.theme.LocalResolvedPalette
import com.ufi_axis.ui.theme.StatusRamp
import com.ufi_axis.util.FormatUtils
import com.ufi_axis.viewmodel.MainViewModel
import com.ufi_axis.viewmodel.state.DownloadConfigItem
import com.ufi_axis.viewmodel.state.DownloadState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 下载设置总入口（二级页）。
 *
 * 2026-09-02：4 个分组由弹窗改成各自的独立页面（`Routes.DETAIL_DOWNLOAD_*`）。
 * 原来的两级提交（弹窗改 draft → 回本页 → 底部"保存配置"）也一起去掉：每个子页面
 * 自己持 draft，自己保存，改完立刻能看到是否落库，不会出现"点了完成却忘了保存"。
 */
@Composable
fun DownloadSettingsScreen(
    viewModel: MainViewModel,
    navController: NavHostController
) {
    val state by viewModel.downloadState.collectAsState()
    val config = state.config

    UfiScreenScaffold(
        title = "下载设置",
        navController = navController,
        showBack = true
    ) { padding ->
        UfiPageBackground(modifier = Modifier.padding(padding)) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (state.pendingEngineRestart) {
                    PendingRestartNotice()
                }
                SettingsEntryRow(
                    title = "基础设置",
                    subtitle = "并发 ${config.maxConcurrent} · 连接 ${config.maxConnectionsPerServer} · " +
                        (if (config.globalSpeedLimit > 0) "${FormatUtils.formatBytes(config.globalSpeedLimit)}/s" else "不限速"),
                    icon = Icons.Default.Download,
                    onClick = { navController.navigate(Routes.DETAIL_DOWNLOAD_BASIC) }
                )
                SettingsEntryRow(
                    title = "智能性能控制",
                    subtitle = buildString {
                        when (state.throttleState) {
                            "stopped" -> append("已暂停")
                            "critical" -> append("节流中")
                            "warning" -> append("限速中")
                            "disabled" -> append("已关闭")
                            else -> append("正常")
                        }
                        append(" · ${state.throttleTemp}°C · CPU ${state.throttleCpu}%")
                        if (state.throttleBattery >= 0) {
                            append(" · ")
                            if (state.throttleCharging) append("⚡")
                            append("${state.throttleBattery}%")
                        }
                    },
                    icon = Icons.Default.Speed,
                    onClick = { navController.navigate(Routes.DETAIL_DOWNLOAD_THROTTLE) }
                )
                SettingsEntryRow(
                    title = "BT / Tracker",
                    subtitle = "${state.trackerCount} 条 · ${state.trackerStatus}",
                    icon = Icons.Default.Share,
                    onClick = { navController.navigate(Routes.DETAIL_DOWNLOAD_TRACKER) }
                )
                SettingsEntryRow(
                    title = "高级设置",
                    subtitle = "分片 ${config.splitCount} · BT节点 ${config.btMaxPeers}",
                    icon = Icons.Default.Tune,
                    onClick = { navController.navigate(Routes.DETAIL_DOWNLOAD_ADVANCED) }
                )
            }
        }
    }
}

// ==================== 子页：基础设置 ====================

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun DownloadBasicSettingsScreen(
    viewModel: MainViewModel,
    navController: NavHostController
) {
    val state by viewModel.downloadState.collectAsState()
    var draft by remember(state.config) { mutableStateOf(state.config) }
    var toast by remember { mutableStateOf<ToastMessage?>(null) }
    val palette = LocalResolvedPalette.current

    SettingsSubScaffold(
        title = "基础设置",
        navController = navController,
        dirty = draft != state.config,
        onSave = {
            viewModel.downloads.updateDownloadConfig(draft)
            toast = ToastMessage("已保存", ToastType.SUCCESS)
        },
        toast = toast,
        onToastDismiss = { toast = null }
    ) {
        SettingsCard(title = "并发与限速") {
            SettingSliderRow(
                label = "最大并发下载数",
                value = draft.maxConcurrent,
                range = 1..10,
                unit = "个",
                description = "同时下载的任务数量，超出的排队等待",
                onValueChange = { draft = draft.copy(maxConcurrent = it) }
            )
            UfiDivider()
            SettingSliderRow(
                label = "每服务器最大连接数",
                value = draft.maxConnectionsPerServer,
                range = 1..16,
                unit = "连接",
                description = "对同一服务器并行建立的连接数，太高可能被服务端拒绝",
                onValueChange = { draft = draft.copy(maxConnectionsPerServer = it) }
            )
            UfiDivider()
            SpeedLimitRow(
                label = "全局速度限制",
                valueBytes = draft.globalSpeedLimit,
                description = "所有任务合计的下载上限，留空或填 0 表示不限速",
                onValueChange = { draft = draft.copy(globalSpeedLimit = it) }
            )
        }

        SettingsCard(title = "保存路径") {
            UfiTextField(
                value = draft.saveDir,
                onValueChange = { draft = draft.copy(saveDir = it) },
                label = "默认保存路径",
                placeholder = "/storage/emulated/0/Download/UFI-AXIS/Download",
                supportingText = {
                    Text(
                        "下载完成后文件转移到这里。必须以 / 开头；" +
                            "写在 /storage/emulated/0/Download/ 之下才能被系统文件管理器看到。",
                        style = UfiTextStyles.caption,
                        color = palette.textSecondary
                    )
                },
                leadingIcon = { Icon(Icons.Default.Folder, contentDescription = null) }
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "快捷路径",
                style = UfiTextStyles.caption,
                color = palette.textSecondary,
                modifier = Modifier.padding(bottom = 4.dp)
            )
            val presets = listOf(
                "/storage/emulated/0/Download/UFI-AXIS/Download" to "UFI-AXIS（默认）",
                "/storage/emulated/0/Download" to "系统下载",
                "/storage/emulated/0/Movies" to "视频",
                "/storage/emulated/0/Documents" to "文档"
            )
            UfiSingleChipSelector(
                options = presets,
                selectedValue = draft.saveDir,
                onSelect = { path -> draft = draft.copy(saveDir = path) },
                wrapContent = true,
                wrap = true
            )
            Spacer(Modifier.height(6.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                val pathValid = draft.saveDir.startsWith("/")
                Icon(
                    if (pathValid) Icons.Default.CheckCircle else Icons.Default.Warning,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = if (pathValid) StatusRamp.good(palette) else palette.error
                )
                Text(
                    if (pathValid) "路径格式有效" else "路径可能无效",
                    style = UfiTextStyles.caption,
                    color = palette.textSecondary
                )
                Spacer(Modifier.weight(1f))
                TextButton(
                    onClick = {
                        // 校验结果由后端给（是否存在/可写/剩余空间），成功失败都用 toast 反馈，
                        // 原来这里传的是空回调 —— 点了没有任何反应。
                        viewModel.downloads.validatePath(draft.saveDir) { result ->
                            val ok = result["valid"] == true
                            toast = if (ok) ToastMessage("路径存在且可写", ToastType.SUCCESS)
                            else ToastMessage(
                                "路径不可用：${result["error"] ?: "不存在或不可写"}",
                                ToastType.WARNING
                            )
                        }
                    },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                    modifier = Modifier.height(24.dp)
                ) {
                    Text("验证路径", style = UfiTextStyles.caption)
                }
            }
        }
    }
}

// ==================== 子页：智能性能控制 ====================

@Composable
fun DownloadThrottleSettingsScreen(
    viewModel: MainViewModel,
    navController: NavHostController
) {
    val state by viewModel.downloadState.collectAsState()
    var draft by remember(state.config) { mutableStateOf(state.config) }
    var toast by remember { mutableStateOf<ToastMessage?>(null) }

    SettingsSubScaffold(
        title = "智能性能控制",
        navController = navController,
        dirty = draft != state.config,
        onSave = {
            viewModel.downloads.updateDownloadConfig(draft)
            toast = ToastMessage("已保存", ToastType.SUCCESS)
        },
        toast = toast,
        onToastDismiss = { toast = null }
    ) {
        SettingsCard(title = "开关") {
            SettingSwitchRow(
                label = "启用智能限速",
                description = "根据温度、CPU、电量和内存动态调整下载性能",
                checked = draft.smartThrottle,
                onCheckedChange = { draft = draft.copy(smartThrottle = it) }
            )
            UfiDivider()
            SettingSwitchRow(
                label = "仅充电时下载",
                description = "设备未充电时自动暂停，充电后自动恢复",
                checked = draft.onlyDownloadWhenCharging,
                onCheckedChange = { draft = draft.copy(onlyDownloadWhenCharging = it) }
            )
        }

        if (draft.smartThrottle) {
            SettingsCard(title = "温度阈值") {
                SettingSliderRow(label = "警告温度", value = draft.throttleTempWarn.toInt(), range = 40..80,
                    unit = "°C", description = "超过后下载速度降到 50%",
                    onValueChange = { draft = draft.copy(throttleTempWarn = it.toFloat()) })
                UfiDivider()
                SettingSliderRow(label = "临界温度", value = draft.throttleTempCritical.toInt(), range = 50..90,
                    unit = "°C", description = "超过后降到 20%，持续过热则暂停任务",
                    onValueChange = { draft = draft.copy(throttleTempCritical = it.toFloat()) })
            }
            SettingsCard(title = "CPU 阈值") {
                SettingSliderRow(label = "CPU 警告", value = draft.throttleCpuWarn, range = 30..95,
                    unit = "%", description = "CPU 占用超过此值降到 50%",
                    onValueChange = { draft = draft.copy(throttleCpuWarn = it) })
                UfiDivider()
                SettingSliderRow(label = "CPU 临界", value = draft.throttleCpuCritical, range = 40..100,
                    unit = "%", description = "CPU 占用超过此值降到 20%",
                    onValueChange = { draft = draft.copy(throttleCpuCritical = it) })
            }
            SettingsCard(title = "电量阈值（充电时不触发）") {
                SettingSliderRow(label = "电量警告", value = draft.throttleBatteryWarn, range = 10..50,
                    unit = "%", description = "电量低于此值降到 50%",
                    onValueChange = { draft = draft.copy(throttleBatteryWarn = it) })
                UfiDivider()
                SettingSliderRow(label = "电量临界", value = draft.throttleBatteryCritical, range = 5..30,
                    unit = "%", description = "电量低于此值暂停下载，充电或回升后自动恢复",
                    onValueChange = { draft = draft.copy(throttleBatteryCritical = it) })
            }
            SettingsCard(title = "内存阈值") {
                SettingSliderRow(label = "内存警告", value = draft.throttleMemoryWarn, range = 50..95,
                    unit = "%", description = "内存占用超过此值降到 50%",
                    onValueChange = { draft = draft.copy(throttleMemoryWarn = it) })
                UfiDivider()
                SettingSliderRow(label = "内存临界", value = draft.throttleMemoryCritical, range = 60..98,
                    unit = "%", description = "内存占用超过此值降到 20%",
                    onValueChange = { draft = draft.copy(throttleMemoryCritical = it) })
            }
            ThrottleStatusCard(state = state, draft = draft)
        }
    }
}

// ==================== 子页：BT / Tracker ====================

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun DownloadTrackerSettingsScreen(
    viewModel: MainViewModel,
    navController: NavHostController
) {
    val state by viewModel.downloadState.collectAsState()
    var draft by remember(state.config) { mutableStateOf(state.config) }
    var toast by remember { mutableStateOf<ToastMessage?>(null) }
    val palette = LocalResolvedPalette.current
    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }

    // 列表编辑从弹窗搬进本页，进页面就把缓存列表拉下来
    LaunchedEffect(Unit) { viewModel.downloads.loadTrackers() }
    var trackerText by remember(state.cachedTrackerList) {
        mutableStateOf(state.cachedTrackerList.replace(",", "\n").trim())
    }

    SettingsSubScaffold(
        title = "BT / Tracker",
        navController = navController,
        dirty = draft != state.config,
        onSave = {
            viewModel.downloads.updateDownloadConfig(draft)
            toast = ToastMessage("已保存", ToastType.SUCCESS)
        },
        toast = toast,
        onToastDismiss = { toast = null }
    ) {
        SettingsCard(title = "自动更新") {
            SettingSwitchRow(
                label = "自动更新 Tracker",
                description = "定期从远程源拉取最新 Tracker 列表，拉到后热加载进运行中的 aria2",
                checked = draft.btTrackerAutoUpdate,
                onCheckedChange = { draft = draft.copy(btTrackerAutoUpdate = it) }
            )
            UfiDivider()
            ChipSelectorRow(
                label = "更新间隔",
                options = listOf("12" to "12 小时", "24" to "24 小时", "48" to "48 小时"),
                selectedValue = draft.btTrackerUpdateIntervalHours.toString(),
                onSelect = { draft = draft.copy(btTrackerUpdateIntervalHours = it.toInt()) }
            )
            UfiDivider()
            UfiTextField(
                value = draft.btTrackerSourceUrl,
                onValueChange = { draft = draft.copy(btTrackerSourceUrl = it) },
                label = "Tracker 源地址",
                placeholder = "https://…/trackers_best.txt",
                supportingText = {
                    Text(
                        "纯文本列表，每行一个 tracker 地址",
                        style = UfiTextStyles.caption,
                        color = palette.textSecondary
                    )
                }
            )
            Spacer(Modifier.height(8.dp))
            UfiButton(
                variant = UfiButtonVariant.Secondary,
                text = if (state.trackerRefreshing) "刷新中…" else "立即刷新",
                onClick = { viewModel.downloads.refreshTrackers() },
                modifier = Modifier.fillMaxWidth()
            )
        }

        SettingsCard(title = "当前状态") {
            UfiInfoRow(label = "Tracker 数量", value = "${state.trackerCount} 条")
            if (state.trackerLastUpdated > 0L) {
                UfiInfoRow(label = "上次更新", value = dateFormat.format(Date(state.trackerLastUpdated)))
            }
            UfiInfoRow(label = "状态", value = state.trackerStatus)
            Text(
                "拉不到列表时后端会退回内置 Tracker，磁链不会因为列表为空而卡在获取种子信息。",
                style = UfiTextStyles.note,
                color = palette.textSecondary,
                modifier = Modifier.padding(top = 4.dp)
            )
        }

        SettingsCard(title = "Tracker 列表（每行一个）") {
            if (state.trackerListLoading) {
                // 2026-09-04：原为不确定态 `LinearProgressIndicator`（无限左右扫动的 2dp 细条）。
                // 下载模块本轮统一删掉所有动效，这里换成一行静态文案：
                // 既没有动画，也仍然告诉用户"列表还在拉，现在框里是空的不代表没配置"。
                Text(
                    "正在载入 Tracker 列表…",
                    style = UfiTextStyles.note,
                    color = palette.textSecondary
                )
                Spacer(Modifier.height(6.dp))
            }
            UfiTextField(
                value = trackerText,
                onValueChange = { trackerText = it },
                label = "",
                modifier = Modifier.fillMaxWidth().heightIn(min = 200.dp, max = 360.dp),
                placeholder = "udp://tracker.example.com:80/announce\n" +
                    "http://tracker2.example.com/announce",
                maxLines = 20
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "当前 ${trackerText.split("\n").count { it.trim().isNotBlank() }} 条，保存后热加载到运行中的 aria2",
                style = UfiTextStyles.note,
                color = palette.textSecondary
            )
            Spacer(Modifier.height(8.dp))
            UfiButton(
                variant = UfiButtonVariant.Secondary,
                text = "保存 Tracker 列表",
                onClick = {
                    val commaSeparated = trackerText.split("\n")
                        .map { it.trim() }.filter { it.isNotBlank() }.joinToString(",")
                    viewModel.downloads.saveTrackerList(commaSeparated)
                    toast = ToastMessage("Tracker 列表已保存", ToastType.SUCCESS)
                },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

// ==================== 子页：高级设置 ====================

@Composable
fun DownloadAdvancedSettingsScreen(
    viewModel: MainViewModel,
    navController: NavHostController
) {
    val state by viewModel.downloadState.collectAsState()
    var draft by remember(state.config) { mutableStateOf(state.config) }
    var toast by remember { mutableStateOf<ToastMessage?>(null) }
    val palette = LocalResolvedPalette.current

    SettingsSubScaffold(
        title = "高级设置",
        navController = navController,
        dirty = draft != state.config,
        onSave = {
            viewModel.downloads.updateDownloadConfig(draft)
            toast = ToastMessage("已保存", ToastType.SUCCESS)
        },
        toast = toast,
        onToastDismiss = { toast = null }
    ) {
        SettingsCard(title = "分片与限速") {
            SettingSliderRow(label = "文件分片数", value = draft.splitCount, range = 1..16,
                unit = "片", description = "单个文件切成几段并行下载",
                onValueChange = { draft = draft.copy(splitCount = it) })
            UfiDivider()
            SpeedLimitRow(label = "单任务速度限制", valueBytes = draft.perTaskSpeedLimit,
                description = "单个任务的下载上限，留空或填 0 表示不限速",
                onValueChange = { draft = draft.copy(perTaskSpeedLimit = it) })
            UfiDivider()
            SpeedLimitRow(label = "全局上传限制", valueBytes = draft.maxOverallUploadLimit,
                description = "BT 做种的上传上限，留空或填 0 表示不限速",
                onValueChange = { draft = draft.copy(maxOverallUploadLimit = it) })
        }

        SettingsCard(title = "BT") {
            SettingSliderRow(label = "BT 最大节点数", value = draft.btMaxPeers, range = 10..200,
                unit = "个", description = "单个 BT 任务同时连接的节点上限",
                onValueChange = { draft = draft.copy(btMaxPeers = it) })
            UfiDivider()
            SettingSwitchRow(label = "启用 DHT", description = "分布式哈希表，用于 BT 节点发现（磁链没有 tracker 时靠它）",
                checked = draft.btEnableDht, onCheckedChange = { draft = draft.copy(btEnableDht = it) })
            UfiDivider()
            SettingSwitchRow(label = "启用 LPD", description = "局域网内 BT 节点发现",
                checked = draft.btEnableLpd, onCheckedChange = { draft = draft.copy(btEnableLpd = it) })
            UfiDivider()
            Row(Modifier.fillMaxWidth().padding(vertical = Spacing.Small), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("做种分享比率", style = UfiTextStyles.listItemTitle, color = palette.textPrimary)
                    Text("上传量达到文件体积的这个倍数后停止做种，0 = 不做种",
                        style = UfiTextStyles.note, color = palette.textSecondary)
                }
                var ratioText by remember(draft.btSeedRatio) { mutableStateOf(String.format(Locale.US, "%.1f", draft.btSeedRatio)) }
                // 做种率是小数（如 1.5），不能用 UfiDigitField —— 它会把小数点过滤掉
                UfiTextField(
                    value = ratioText,
                    onValueChange = {
                        ratioText = it
                        it.toFloatOrNull()?.let { v -> if (v in 0f..100f) { draft = draft.copy(btSeedRatio = v) } }
                    },
                    label = "",
                    modifier = Modifier.width(96.dp),
                    fillMaxWidth = false,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    trailingIcon = {
                        Text("倍", style = UfiTextStyles.caption, color = palette.textSecondary,
                            modifier = Modifier.padding(end = 8.dp))
                    }
                )
            }
        }

        SettingsCard(title = "网络与重试") {
            SettingSwitchRow(label = "禁用 IPv6", description = "仅使用 IPv4 连接",
                checked = draft.disableIpv6, onCheckedChange = { draft = draft.copy(disableIpv6 = it) })
            UfiDivider()
            SettingSwitchRow(label = "校验证书", description = "HTTPS 连接时验证服务器证书",
                checked = draft.checkCertificate, onCheckedChange = { draft = draft.copy(checkCertificate = it) })
            UfiDivider()
            SettingSliderRow(label = "最大重试次数", value = draft.maxTries, range = 1..20,
                unit = "次", description = "单个连接失败后重试的上限",
                onValueChange = { draft = draft.copy(maxTries = it) })
            UfiDivider()
            SettingSliderRow(label = "重试等待", value = draft.retryWait, range = 0..60,
                unit = "秒", description = "两次重试之间的间隔",
                onValueChange = { draft = draft.copy(retryWait = it) })
        }

        SettingsCard(title = "生效方式") {
            Text(
                "并发数、全局上传/下载限速、BT 最大节点数会立刻热更新到运行中的 aria2。" +
                    "分片数、每服务器连接数、DHT / LPD、做种率、IPv6、证书校验、重试参数和保存路径" +
                    "属于启动期选项：保存时若没有任务在跑，后端会自动重启引擎让它们生效；" +
                    "有任务在跑则等下次引擎启动。",
                style = UfiTextStyles.note,
                color = palette.textSecondary
            )
        }
    }
}

// ==================== 页面骨架 / 公共行 ====================

/** 子设置页统一骨架：滚动内容 + 底部保存按钮（无改动时按钮不出现）。 */
@Composable
private fun SettingsSubScaffold(
    title: String,
    navController: NavHostController,
    dirty: Boolean,
    onSave: () -> Unit,
    toast: ToastMessage?,
    onToastDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    UfiScreenScaffold(
        title = title,
        navController = navController,
        showBack = true
    ) { padding ->
        UfiPageBackground(modifier = Modifier.padding(padding)) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                content()
                if (dirty) {
                    UfiButton(
                        text = "保存",
                        onClick = onSave,
                        modifier = Modifier.fillMaxWidth()
                            .padding(horizontal = Spacing.CardHorizontalMargin)
                    )
                }
                Spacer(Modifier.height(Spacing.Medium))
                UfiToastHost(toastMessage = toast, onDismiss = onToastDismiss)
            }
        }
    }
}

/** 分组卡片：统一走公共的 [UfiSettingsGroup] + [UfiGroupHeader]，与告警/软件设置页同款。 */
@Composable
private fun SettingsCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    UfiSettingsGroup {
        UfiGroupHeader(title)
        content()
    }
}

/** 待重启提示：启动期配置改过，但当时有任务在跑没法重启引擎。 */
@Composable
private fun PendingRestartNotice() {
    val palette = LocalResolvedPalette.current
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.CardHorizontalMargin),
        shape = UfiCardDefaults.inputShape,
        color = palette.accentContainer.copy(alpha = 0.5f)
    ) {
        Text(
            "有启动期配置改动尚未生效：保存时有任务在下载，无法重启下载引擎。" +
                "任务结束后引擎下次启动会自动应用。",
            style = UfiTextStyles.note,
            color = palette.textPrimary,
            modifier = Modifier.padding(10.dp)
        )
    }
}

/** 设置入口行：标题 + 摘要副标题 + 右侧 chevron（与软件设置同款独立行卡）。 */
@Composable
private fun SettingsEntryRow(
    title: String,
    subtitle: String,
    icon: ImageVector,
    onClick: () -> Unit
) {
    UfiSettingsRowCard {
        UfiSettingsItem(
            icon = icon,
            title = title,
            description = subtitle,
            onClick = onClick,
            trailing = { UfiSettingsChevron() }
        )
    }
}

@Composable
private fun ThrottleStatusCard(state: DownloadState, draft: DownloadConfigItem) {
    val palette = LocalResolvedPalette.current
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.CardHorizontalMargin),
        shape = UfiCardDefaults.inputShape,
        color = when (state.throttleState) {
            "stopped" -> palette.errorContainer.copy(alpha = 0.7f)
            "critical" -> palette.errorContainer.copy(alpha = 0.5f)
            "warning" -> palette.accentContainer.copy(alpha = 0.5f)
            else -> palette.surfaceMuted.copy(alpha = 0.4f)
        }
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Text("当前状态", style = UfiTextStyles.label.copy(fontWeight = UfiWeight.Emphasis))
            Spacer(Modifier.height(4.dp))
            Text(
                "限速级别: ${
                    when (state.throttleState) {
                        "stopped" -> "已暂停 (等待条件恢复)"
                        "critical" -> "严重 (降至 20%)"
                        "warning" -> "警告 (降至 50%)"
                        "disabled" -> "已禁用"
                        else -> "正常"
                    }
                }",
                style = MaterialTheme.typography.labelSmall,
                color = when (state.throttleState) {
                    "stopped", "critical" -> palette.error
                    "warning" -> palette.accentSecondary
                    else -> palette.textSecondary
                }
            )
            Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) {
                    ThrottleMetricRow("温度", "${state.throttleTemp}°C",
                        state.throttleTemp >= draft.throttleTempCritical, state.throttleTemp >= draft.throttleTempWarn)
                    ThrottleMetricRow("CPU", "${state.throttleCpu}%",
                        state.throttleCpu >= draft.throttleCpuCritical, state.throttleCpu >= draft.throttleCpuWarn)
                }
                Column(Modifier.weight(1f)) {
                    if (state.throttleBattery >= 0) {
                        ThrottleMetricRow("电量",
                            if (state.throttleCharging) "⚡ ${state.throttleBattery}%" else "${state.throttleBattery}%",
                            !state.throttleCharging && state.throttleBattery in 1..draft.throttleBatteryCritical,
                            !state.throttleCharging && state.throttleBattery in 1..draft.throttleBatteryWarn)
                    } else ThrottleMetricRow("电量", "未知", false, false)
                    ThrottleMetricRow("内存", "${state.throttleMemory}%",
                        state.throttleMemory >= draft.throttleMemoryCritical, state.throttleMemory >= draft.throttleMemoryWarn)
                }
            }
        }
    }
}

// ==================== Setting Helpers ====================

/**
 * 滑杆行。相比旧版补了三件用户看得见的信息：
 * ① 当前值带单位；② 滑杆下方标出量程两端（原来只有一根光秃秃的轨道）；③ 可选的一行说明。
 * 单位不要再塞进 label（"警告温度 (°C)" 这种写法），传 [unit]。
 */
@Composable
private fun SettingSliderRow(
    label: String,
    value: Int,
    range: IntRange,
    unit: String = "",
    description: String? = null,
    onValueChange: (Int) -> Unit
) {
    val palette = LocalResolvedPalette.current
    var sliderValue by remember(value) { mutableFloatStateOf(value.toFloat()) }
    val current = sliderValue.roundToInt()
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.Small)) {
        UfiSlider(
            value = sliderValue,
            onValueChange = { sliderValue = it },
            onValueChangeFinished = { onValueChange(sliderValue.roundToInt()) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            steps = (range.last - range.first - 1).coerceAtLeast(0),
            label = label,
            valueLabel = if (unit.isEmpty()) "$current" else "$current $unit"
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                if (unit.isEmpty()) "${range.first}" else "${range.first} $unit",
                style = UfiTextStyles.caption,
                color = palette.textSecondary
            )
            Text(
                if (unit.isEmpty()) "${range.last}" else "${range.last} $unit",
                style = UfiTextStyles.caption,
                color = palette.textSecondary
            )
        }
        if (description != null) {
            Text(
                description,
                style = UfiTextStyles.note,
                color = palette.textSecondary,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }
}

/** 限速输入的单位。存的一律是 byte/s，界面按用户选的单位换算。 */
private enum class SpeedUnit(val label: String, val factor: Long) {
    KB("KB/s", 1024L),
    MB("MB/s", 1024L * 1024L)
}

/**
 * 限速行。旧版只有一个裸输入框：单位只在值 > 0 时才出现在框内，
 * "0 = 不限速" 这条规则完全没写出来，而且只能按 MB 输入 —— 想限到 512 KB/s 做不到。
 * 现在是「数值 + 单位选择 + 当前生效值说明 + 一键不限速」。
 */
@Composable
private fun SpeedLimitRow(
    label: String,
    valueBytes: Long,
    description: String? = null,
    onValueChange: (Long) -> Unit
) {
    val palette = LocalResolvedPalette.current
    // 初始单位按当前值挑：不是整 MB 的值用 KB/s 显示才不会被取整成 0
    var unit by remember {
        mutableStateOf(
            if (valueBytes > 0 && valueBytes % SpeedUnit.MB.factor != 0L) SpeedUnit.KB else SpeedUnit.MB
        )
    }
    var text by remember(unit) {
        mutableStateOf(if (valueBytes > 0) (valueBytes / unit.factor).toString() else "")
    }
    // 外部值变化（配置刚从设备拉到 / 点了"不限速"）时同步回输入框，
    // 但只在两者真的不等时才覆盖，避免边打字边被重置。
    LaunchedEffect(valueBytes, unit) {
        val parsed = (text.toLongOrNull() ?: 0L) * unit.factor
        if (parsed != valueBytes) {
            text = if (valueBytes > 0) (valueBytes / unit.factor).toString() else ""
        }
    }

    fun commit(raw: String, u: SpeedUnit) {
        val n = raw.toLongOrNull()
        onValueChange(if (n == null || n <= 0L) 0L else n * u.factor)
    }

    Column(modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.Small)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(label, style = UfiTextStyles.listItemTitle, color = palette.textPrimary)
                Text(
                    if (valueBytes <= 0) "当前：不限速" else "当前：${FormatUtils.formatBytes(valueBytes)}/s",
                    style = UfiTextStyles.note,
                    color = if (valueBytes <= 0) palette.textSecondary else palette.accent
                )
            }
            UfiDigitField(
                value = text,
                onValueChange = { newText ->
                    text = newText
                    commit(newText, unit)
                },
                label = "",
                modifier = Modifier.width(96.dp),
                fillMaxWidth = false,
                placeholder = "0",
                trailingIcon = {
                    Text(
                        unit.label,
                        style = UfiTextStyles.caption,
                        color = palette.textSecondary,
                        modifier = Modifier.padding(end = 8.dp)
                    )
                }
            )
        }
        Spacer(Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            UfiSingleChipSelector(
                options = SpeedUnit.values().map { it.name to it.label },
                selectedValue = unit.name,
                onSelect = { picked ->
                    val next = SpeedUnit.valueOf(picked)
                    if (next != unit) {
                        // 换单位保持"实际速度"不变，只改显示刻度
                        unit = next
                        text = if (valueBytes > 0) (valueBytes / next.factor).toString() else ""
                    }
                },
                wrapContent = true
            )
            Spacer(Modifier.weight(1f))
            if (valueBytes > 0) {
                TextButton(
                    onClick = { text = ""; onValueChange(0L) },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                    modifier = Modifier.height(24.dp)
                ) {
                    Text("不限速", style = UfiTextStyles.caption)
                }
            }
        }
        Text(
            description ?: "留空或填 0 表示不限速",
            style = UfiTextStyles.note,
            color = palette.textSecondary,
            modifier = Modifier.padding(top = 2.dp)
        )
    }
}

/** 开关行：直接用公共的 [UfiSettingsToggle]，不再自己搭 Row + UfiSwitch。 */
@Composable
private fun SettingSwitchRow(
    label: String,
    description: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    UfiSettingsToggle(
        title = label,
        description = description,
        checked = checked,
        onCheckedChange = onCheckedChange
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun ChipSelectorRow(
    label: String,
    options: List<Pair<String, String>>,
    selectedValue: String,
    onSelect: (String) -> Unit
) {
    Column {
        Text(label, style = UfiTextStyles.listItemTitle, modifier = Modifier.padding(bottom = 4.dp))
        UfiSingleChipSelector(
            options = options,
            selectedValue = selectedValue,
            onSelect = onSelect,
            wrapContent = true,
            wrap = true
        )
    }
}

@Composable
private fun ThrottleMetricRow(
    label: String,
    value: String,
    isCritical: Boolean,
    isWarning: Boolean
) {
    val palette = LocalResolvedPalette.current
    val color = when {
        isCritical -> palette.error
        isWarning -> palette.accentSecondary
        else -> palette.textSecondary
    }
    Row(modifier = Modifier.padding(vertical = 1.dp), verticalAlignment = Alignment.CenterVertically) {
        Surface(shape = CircleShape, color = color, modifier = Modifier.size(6.dp)) {}
        Spacer(Modifier.width(4.dp))
        Text("$label: $value", style = UfiTextStyles.caption, color = color)
    }
}
