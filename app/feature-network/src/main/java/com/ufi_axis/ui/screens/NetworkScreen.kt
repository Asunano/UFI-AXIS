package com.ufi_axis.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.ufi_axis.ui.navigation.Routes
import com.ufi_axis.ui.animation.page.isUfiPageForeground
import com.ufi_axis.ui.components.*
import com.ufi_axis.ui.components.common.*
import com.ufi_axis.ui.theme.*
import com.ufi_axis.viewmodel.MainViewModel


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NetworkScreen(viewModel: MainViewModel, navController: NavHostController) {
    val state by viewModel.networkState.collectAsState()

    // ── 无弹窗状态（全部已转为独立页面） ──

    // ── 页面前台判定（离屏 Tab 门控的总开关） ──
    // 原实现是手写的 isScreenActive（DisposableEffect.onDispose 里置 false）。它真正表达的是
    // 「组合是否存活」，而本页是底部 Tab 的 index 1，宿主 UfiPageSwitcher 开了 keepPagesAlive
    // （→ beyondViewportPageCount = 1），组合几乎永不销毁 → isScreenActive 恒为 true，形同虚设：
    // 本页作为后台 / 预加载 Tab 时，下面的首次加载、出错重试、ON_RESUME 刷新统统照跑，
    // 白白消耗 CPU / 流量 / 电量。故整体换成 isUfiPageForeground() 做门控。
    //
    // 必须用 isUfiPageForeground() 而不是 LocalUfiPageActive.current：后者默认 false，
    // 页面不在 Switcher 内（Preview / 单测 / 被 NavHost 单独打开）时只能拿到默认值，
    // 直接拿它门控会被误判成后台页而永不加载数据。isUfiPageForeground() 在「非宿主管理」
    // 场景恒返回 true，保持原有行为不受影响。
    val pageForeground = isUfiPageForeground()

    // 首次加载：仅在页面处于前台时才拉，离屏预组合时一个请求都不发。
    // pageForeground 作为 key：用户首次切到本 Tab 时 LaunchedEffect 以新协程重启并自动补拉。
    LaunchedEffect(pageForeground) {
        if (!pageForeground) return@LaunchedEffect
        kotlinx.coroutines.delay(200)
        loadNetworkAll(viewModel)
    }
    var retryCount by remember { mutableIntStateOf(0) }

    // WiFi 热点设置弹窗开关
    var showWifiSettingsDialog by remember { mutableStateOf(false) }

    // 出错 5s 后自动重试，最多 3 次（网络界面无轮询，不重试会导致出错后永久卡住）。
    // 同样只在页面前台时重试：后台 Tab 出错先躺着，等用户切回来（pageForeground 变 true
    // 会重启本效应）再补，避免看不见的页面在后台反复空转重试。
    LaunchedEffect(state.errorMessage, pageForeground) {
        if (state.errorMessage != null && pageForeground && retryCount < 3) {
            kotlinx.coroutines.delay(5_000)
            retryCount++
            loadNetworkAll(viewModel)
        }
    }

    // UID-007 (Wave 2)：返回前台自动刷新一次（仅 ON_RESUME 触发，不引入常轮询）。
    // ON_RESUME 是 Activity 级事件，会广播给所有存活的页面，因此必须在回调体内再判一次
    // 页面前台，否则息屏解锁一次，5 个保活 Tab 会各刷一遍。
    // 这里用 rememberUpdatedState 而非直接捕获 pageForeground：rememberResumeRefresh 内部的
    // DisposableEffect 只以 lifecycleOwner 为 key，观察者闭包在首次组合后就固定下来，
    // 直接捕获的是那一刻的 Boolean 快照（永远是首次值）；捕获 State 才能读到调用时的最新值。
    val foregroundForResume by rememberUpdatedState(pageForeground)
    rememberResumeRefresh {
        if (foregroundForResume) loadNetworkAll(viewModel)
    }

    // 顶栏不放手动刷新：本页有 ON_RESUME 自动重取，错误横幅自带「重试」，手动按钮属重复能力。
    UfiScreenScaffold(
        title = "网络设置",
        navController = navController,
        showBack = false
    ) { padding ->
        // 原来这里是 `PullToRefreshBox(isRefreshing = false, onRefresh = refreshNetwork)` ——
        // 一个**永远不显示指示器**的下拉手势。既然没有任何视觉反馈，用户下拉时无从知道
        // 触发了什么；而它照样和页面滚动抢纵向手势。整体删掉，改由上面的顶栏按钮承担。
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            // 不再挂 blurEntrance：入场动画已上移到 MainNavGraph 根节点（"app-launch"），
            // 整个 App 只在冷启动播一次。挂在页面上会导致「首次切到本 Tab」「从二级页返回
            // 导致宿主组合重建」时各播一次 —— 观感就是切界面抖一下。
            UfiPageBackground {
                // 2026-09-05：错误提示改由 Activity 级全局错误浮层统一展示
                //（MainActivity 读 viewModel.globalError，带「重试」= network.refreshNetwork()）。

                // ═══════════ 1. 蜂窝网络状态 & SIM 卡 ═══════════
                // 频段标签由 core 拼好（服务小区统一字段 band_label，NR 优先），客户端不按制式分支
                CellularStatusCard(
                    state = state,
                    currentBand = state.signalInfo?.band_label
                )

                Spacer(Modifier.height(10.dp))

                // ═══════════ 2. 网络设置列表（一行一张卡 + 10dp 间距） ═══════════
                // 2026-08-30：原来是「移动数据走 UfiSettingsGroup + UfiSettingsToggle，其余 4 行
                // 各自手搓 Box(rowModifier) + Row/Icon/Column/Text」。两套容器的阴影(4dp vs 2dp)、
                // 描边、内边距(20 vs 16dp)、图标 tint(palette.accent vs colorScheme.primary)
                // 全不一样，移动数据那张卡看起来又胖又重 —— 就是用户说的"不一致"。
                // 现在 5 行统一成公共的 UfiSettingsRowCard（单行卡容器）+ UfiSettingsItem /
                // UfiSettingsToggle，箭头用 UfiSettingsChevron，页面里不再有手写卡片容器。
                // 卡间距 10dp 与设置页一致：平级入口靠间距分隔，不挤在一张大卡里。

                val wifi = state.wifiSettings
                val allStations = state.wifiClients?.allStations ?: emptyList()

                val speedTestState by viewModel.speedTestState.collectAsState()
                // 2026-08-26：SpeedTestState 不再存拼好的整句文本，这里按数值现拼一行摘要
                val lastResult = if (speedTestState.avgMbps > 0) {
                    val kind = if (speedTestState.testType == "external") "外网" else "内网"
                    val up = if (speedTestState.uploadMbps > 0) {
                        " ↑ ${String.format(java.util.Locale.US, "%.1f", speedTestState.uploadMbps)}"
                    } else ""
                    val latency = speedTestState.latencyMs?.let { " · ${String.format(java.util.Locale.US, "%.0f", it)} ms" } ?: ""
                    "$kind ↓ ${String.format(java.util.Locale.US, "%.1f", speedTestState.avgMbps)}$up Mbps$latency"
                } else null

                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    // 移动数据：pending 期间只把描述标成"切换中"，开关本身仍可点（见 mobileDataPending 注释）
                    UfiSettingsRowCard {
                        UfiSettingsToggle(
                            icon = Icons.Default.DataUsage,
                            title = "移动数据",
                            description = when {
                                state.mobileDataPending -> "切换中…"
                                state.mobileDataEnabled -> "已开启"
                                else -> "已关闭"
                            },
                            checked = state.mobileDataEnabled,
                            onCheckedChange = { viewModel.network.toggleMobileData(it) }
                        )
                    }

                    UfiSettingsRowCard {
                        UfiSettingsItem(
                            icon = Icons.Default.CellTower,
                            title = "蜂窝网络高级设置",
                            description = "网络模式 · 频段锁定 · 基站信息",
                            onClick = { navController.navigate("detail/cellular-advanced") },
                            trailing = { UfiSettingsChevron() }
                        )
                    }

                    // WiFi 热点：点行进设置弹窗，点开关直接开/关热点
                    UfiSettingsRowCard {
                        UfiSettingsToggle(
                            icon = Icons.Default.Wifi,
                            title = "WiFi 热点",
                            description = if (state.wifiEnabled) {
                                "${wifi?.ssid?.ifBlank { "未命名" } ?: "未命名"} · ${allStations.size} 台设备 · " +
                                    if (wifi?.activeChip == "chip2") "5GHz" else "2.4GHz"
                            } else "已关闭",
                            checked = state.wifiEnabled,
                            onCheckedChange = { viewModel.network.setWifiEnabled(it) },
                            onClick = { showWifiSettingsDialog = true }
                        )
                    }

                    UfiSettingsRowCard {
                        UfiSettingsItem(
                            icon = Icons.Default.Speed,
                            title = "网速测试",
                            description = lastResult ?: "内网上下行 / 外网下行 · 多流并发测速",
                            onClick = { navController.navigate(Routes.DETAIL_SPEED_TEST) },
                            trailing = { UfiSettingsChevron() }
                        )
                    }

                    // 在线设备（二级页）
                    // 2026-08-30：原来是页尾一张会随设备数变高的列表卡，设备一多就被底部胶囊
                    // 导航栏压住，所以改成入口行 + 独立页（OnlineDevicesScreen）。
                    UfiSettingsRowCard {
                        UfiSettingsItem(
                            icon = Icons.Default.Devices,
                            title = "在线设备",
                            description = if (allStations.isEmpty()) "暂无连接设备" else "${allStations.size} 台设备 · WiFi 与 LAN",
                            onClick = { navController.navigate(Routes.DETAIL_ONLINE_DEVICES) },
                            trailing = { UfiSettingsChevron() }
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))
            }

            // WiFi 热点设置弹窗（替代原独立页面跳转）
            if (showWifiSettingsDialog) {
                WifiSettingsDialog(
                    viewModel = viewModel,
                    visible = true,
                    onDismiss = { showWifiSettingsDialog = false }
                )
            }
        }
    }
}

// ═══════════════════════════════════════════════
// 蜂窝网络状态 Hero 卡（方案 A 风格：accent 渐变 + 全白字）
// 视觉与首页 HomeConnectionCard 完全统一（accent 明暗衍生渐变 / 16dp 圆角 / 3dp 阴影 / 1px 白描边）
// ═══════════════════════════════════════════════

@Composable
private fun CellularStatusCard(
    state: com.ufi_axis.viewmodel.state.NetworkState,
    currentBand: String? = null
) {
    val palette = LocalResolvedPalette.current
    val sig = state.signalInfo; val net = state.networkStatus
    // RAT 标签：优先用原始值(LTE/NR)，fallback 映射
    val rawRat = sig?.rat?.takeIf { it.isNotBlank() && it != "WiFi" }
        ?: net?.network_type?.takeIf { it.isNotBlank() && it != "WiFi" }
    // 制式显示标签（用于右上胶囊）
    val networkTypeLabel = when {
        rawRat == null -> ""
        rawRat.contains("5G", true) || rawRat.contains("NR", true) -> "5G"
        rawRat.contains("4G", true) || rawRat.contains("LTE", true) -> "4G"
        else -> rawRat
    }
    val operator = sig?.operator?.takeIf { it.isNotBlank() } ?: net?.operator?.takeIf { it.isNotBlank() } ?: "未知"
    // 右上胶囊：制式 + 频段
    val pillText = buildString {
        append(networkTypeLabel)
        val band = currentBand?.takeIf { it.isNotBlank() && it != "—" }
        if (band != null) append(" $band")
    }.takeIf { it.isNotBlank() }
    // 副标题：设备 LAN IP（取自 lanSettings，无数据时 —）
    val ipText = state.lanSettings?.lanIp?.takeIf { it.isNotBlank() } ?: "—"
    val rsrpVal = sig?.rsrp
    val bars = rsrpVal?.let { signalBars(it) } ?: 0

    // ── 渐变：与首页 Hero 一致的 accent 明暗衍生色（阶梯口径见 theme 层的 Color.ufiShade）──
    val gradColors = if (palette.isDark) {
        listOf(palette.accent.ufiShade(-0.14f), palette.accent.ufiShade(-0.04f), palette.accent)
    } else {
        listOf(palette.accent, palette.accent.ufiShade(0.12f), palette.accent.ufiShade(0.22f))
    }
    val cardShape = UfiCardDefaults.shape

    // 阴影：#9CA4AC @ 30%，Y偏移4，模糊半径16（与全局 token 一致，见 UfiCardDefaults.ufiCardShadow）
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .ufiCardShadow(elevation = 4.dp, shape = cardShape)
            .clip(cardShape)
            .background(brush = Brush.linearGradient(colors = gradColors))
            // 2026-09-04（P2-中）：本 Hero 卡原有 10 处写死 Color.White[.copy(alpha)]（含下方 HeroMetric）。
            // 卡底是 accent 明暗三段渐变（见上方 gradColors），换配色时底色跟着 accent 变、
            // 这套白色内容不变 ⇒ 浅色 accent 主题下白字白图标没对比度，正是 G3 要治的残留。
            // 现在：不透明前景 → palette.onGradient；带 alpha 的弱化前景/装饰 → palette.gradientMuted。
            // 两槽默认值都是 Color.White，预设不填 ⇒ 观感零变化；alpha 档位仍留在调用点（12%~72% 不等）。
            .border(width = 1.dp, color = palette.gradientMuted.copy(alpha = 0.18f), shape = cardShape)  // 原 Color.White 18%：1px 高光描边
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 16.dp)
        ) {
            // ═══ 行1：图标 + 标题(运营商) + 胶囊(制式频段) + 副标题(IP) / 信号条右上 ═══
            // heroIconSize：左侧图标圆底的直径，同时作为右侧信号条的对齐轴高度
            // （信号条在这段高度内垂直居中 ⇒ 与运营商标题那一行同处一条水平带）。
            val heroIconSize = 36.dp
            Row(verticalAlignment = Alignment.Top) {
                Surface(
                    shape = CircleShape,
                    color = palette.gradientMuted.copy(alpha = 0.18f),  // 原 Color.White 18%：图标圆底
                    modifier = Modifier.size(heroIconSize)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Default.SatelliteAlt,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = palette.onGradient  // 原 Color.White：卫星图标
                        )
                    }
                }
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = operator,
                            style = UfiTextStyles.screenTitle,
                            color = palette.onGradient,  // 原 Color.White：运营商主标题
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.width(8.dp))
                        pillText?.let {
                            Surface(
                                color = palette.gradientMuted.copy(alpha = 0.2f),  // 原 Color.White 20%：制式胶囊底
                                shape = UfiCardDefaults.microShape
                            ) {
                                Text(
                                    text = " $it ",
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                    color = palette.onGradient,  // 原 Color.White：胶囊内文字
                                    style = UfiTextStyles.tag.copy(fontWeight = UfiWeight.Max)
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = "IP $ipText",
                        style = MaterialTheme.typography.bodyMedium,
                        color = palette.gradientMuted.copy(alpha = 0.72f),  // 原 Color.White 72%：IP 副标题
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.width(10.dp))
                // 右上角信号条（不显示 dBm 文字，dBm 数值在下方三张 HeroMetric）
                // 2026-09-05：原为 40dp 高 / 64dp 宽（barWidth = null 各段平分 64dp 容器），
                // 约为首页 HomeConnectionCard 同款信号条的 9 倍面积。现改回组件默认尺寸
                // （12dp 高 × 23dp 宽，固定 3dp 段宽），两处视觉逐位一致；
                // 外层容器由「固定 64dp 宽 + BottomEnd」改为「与图标圆底等高 + 居中」。
                Box(Modifier.height(heroIconSize), contentAlignment = Alignment.Center) {
                    UfiGradientSignalBar(bars = bars)
                }
            }

            Spacer(Modifier.height(18.dp))

            // ═══ 行2：RSRP / SNR / RSRQ 三 metric（半透明白卡，一行） ═══
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HeroMetric(sig?.rsrp?.toString() ?: "—", "RSRP dBm", Modifier.weight(1f))
                HeroMetric(sig?.sinr?.toString() ?: "—", "SNR dB", Modifier.weight(1f))
                HeroMetric(sig?.rsrq?.toString() ?: "—", "RSRQ dB", Modifier.weight(1f))
            }

        }
    }
}



// ═══════════════════════════════════════════════
// 频段锁定组件（被 BandLockDialog 复用）
// ═══════════════════════════════════════════════

@Composable
fun BandLockSection(viewModel: MainViewModel, state: com.ufi_axis.viewmodel.state.NetworkState) {
    var selectedLte by remember { mutableStateOf(setOf<Int>()) }
    var selectedNr by remember { mutableStateOf(setOf<Int>()) }
    val supportLte = listOf(1, 3, 5, 8, 34, 38, 39, 40, 41)
    val supportNr = listOf(1, 5, 8, 28, 41, 78)
    val palette = LocalResolvedPalette.current
    val lteColor = BandLte
    val nrColor = BandNr

    LaunchedEffect(state.bandStatus) {
        // 逗号串 → Set<Int> 的解析在 BandStatusResponse 里（"0"/"all" = 未锁定）
        selectedLte = state.bandStatus?.lteBands ?: emptySet()
        selectedNr = state.bandStatus?.nrBands ?: emptySet()
    }

    val isFullyUnlocked = selectedLte.isEmpty() && selectedNr.isEmpty()

    Column(verticalArrangement = Arrangement.spacedBy(Spacing.Medium)) {
        // ── 状态卡 ──
        Box(
            Modifier
                .fillMaxWidth()
                .ufiStandardCard(elevation = 3.dp)
                .padding(16.dp)
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("LTE 频段", style = UfiTextStyles.labelStrong, color = lteColor)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "已锁定 ${selectedLte.size} 个",
                        style = MaterialTheme.typography.labelSmall,
                        color = palette.textSecondary
                    )
                }
                Spacer(Modifier.width(Spacing.Medium))
                Column(Modifier.weight(1f)) {
                    Text("NR 频段", style = UfiTextStyles.labelStrong, color = nrColor)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "已锁定 ${selectedNr.size} 个",
                        style = MaterialTheme.typography.labelSmall,
                        color = palette.textSecondary
                    )
                }
                Spacer(Modifier.width(Spacing.Medium))
                Surface(
                    color = if (isFullyUnlocked) palette.accent.copy(alpha = 0.12f) else palette.warning.copy(alpha = 0.18f),
                    shape = UfiCardDefaults.microShape
                ) {
                    Text(
                        if (isFullyUnlocked) "未锁定" else "已锁定",
                        Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        style = UfiTextStyles.caption,
                        color = if (isFullyUnlocked) palette.accent else palette.warning
                    )
                }
            }
        }

        // ── 频段选择区（统一卡片包裹，与状态卡/按钮卡对齐） ──
        Box(
            Modifier
                .fillMaxWidth()
                .ufiStandardCard()
                .padding(16.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.Medium)) {
                // ── LTE 频段（绿） ──
                Text("LTE 频段", style = UfiTextStyles.labelStrong, color = lteColor)
                Spacer(Modifier.height(4.dp))
                // 2026-08-31：手绘 FilterChip FlowRow → 公共 UfiMultiChipSelector（accentColor 保留 LTE 绿）
                UfiMultiChipSelector(
                    options = supportLte.map { it.toString() to "B$it" },
                    selectedValues = selectedLte.map { it.toString() }.toSet(),
                    onToggle = { v ->
                        val band = v.toInt()
                        selectedLte = if (band in selectedLte) selectedLte - band else selectedLte + band
                    },
                    accentColor = lteColor
                )

                // ── NR 频段（紫） ──
                Text("NR 频段", style = UfiTextStyles.labelStrong, color = nrColor)
                Spacer(Modifier.height(4.dp))
                // 2026-08-31：同上，accentColor 保留 NR 紫
                UfiMultiChipSelector(
                    options = supportNr.map { it.toString() to "N$it" },
                    selectedValues = selectedNr.map { it.toString() }.toSet(),
                    onToggle = { v ->
                        val band = v.toInt()
                        selectedNr = if (band in selectedNr) selectedNr - band else selectedNr + band
                    },
                    accentColor = nrColor
                )
            }
        }

        // ── 操作按钮（收进卡片内） ──
        Box(
            Modifier
                .fillMaxWidth()
                .ufiStandardCard()
                .padding(Spacing.CardPadding)
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                UfiButton(
                    text = "锁定所选",
                    onClick = {
                        viewModel.network.lockBands(
                            if (selectedLte.isNotEmpty()) selectedLte.joinToString(",") else null,
                            if (selectedNr.isNotEmpty()) selectedNr.joinToString(",") else null
                        )
                    },
                    modifier = Modifier.weight(1f)
                )
                UfiButton(
                    variant = UfiButtonVariant.Secondary,
                    text = "全部解锁",
                    onClick = {
                        viewModel.network.lockBands(null, null)
                        selectedLte = emptySet(); selectedNr = emptySet()
                    },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

// ═══════════════════════════════════════════════
// 内部辅助函数
// ═══════════════════════════════════════════════

private fun loadNetworkAll(viewModel: MainViewModel) {
    viewModel.network.refreshNetwork()
    viewModel.network.loadBandStatus()
    viewModel.network.loadCellInfo()
    viewModel.network.loadDeviceSettings()
    viewModel.network.loadLanSettings()
}


// ═══════════════════════════════════════════════
// Hero 卡辅助组件（与首页 HomeConnectionCard 视觉统一）
// ═══════════════════════════════════════════════

// 2026-09-05：私有 `Color.shade` 已删除 —— 与 HomeConnectionCard / TrafficManagementScreen 是
// 逐字节相同的三份拷贝（MonitorOverview 还有一份 Oklab lerp 版，像素并不相同）。
// 现统一走主题层 [com.ufi_axis.ui.theme.ufiShade]，它同时是 `onGradient` 判据
// （`heroGradientBrightestStop`）的输入 —— 运行时画的和测试判的只有一份实现。

/**
 * 2026-08-31：私有 GradientSignalBar 已删除 —— 与 HomeConnectionCard 那份是同一段代码，
 * 已合并到公共层 [com.ufi_axis.ui.components.common.UfiGradientSignalBar]。
 */

/** 半透明白底 metric 小卡（大号数值 + 小标签，与截图一致） */
@Composable
private fun HeroMetric(value: String, label: String, modifier: Modifier = Modifier) {
    // 2026-09-04（P2-中）：本函数原有 3 处写死 Color.White（小卡底 12% / 数值不透明 / 标签 70%）。
    // 它铺在外层渐变 Hero 卡上，换配色时渐变随 accent 变、这三处不变 ⇒ 浅色 accent 主题下残留。
    // 私有 composable，加一行 palette 读取即可，公共组件签名不受影响。
    val palette = LocalResolvedPalette.current
    Surface(
        modifier = modifier,
        color = palette.gradientMuted.copy(alpha = 0.12f),  // 原 Color.White 12%：小卡半透明底
        shape = UfiCardDefaults.subtleShape
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 10.dp, horizontal = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                value,
                style = UfiTextStyles.panelTitleStrong,
                color = palette.onGradient,  // 原 Color.White：RSRP/SNR/RSRQ 数值
                maxLines = 1
            )
            Spacer(Modifier.height(2.dp))
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = palette.gradientMuted.copy(alpha = 0.7f)  // 原 Color.White 70%：指标单位标签
            )
        }
    }
}

