package com.ufi_axis.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavHostController
import com.ufi_axis.data.repository.ConnectionState
import com.ufi_axis.ui.navigation.Routes
import com.ufi_axis.ui.animation.page.isUfiPageForeground
import com.ufi_axis.ui.components.*
import com.ufi_axis.ui.components.common.*
import com.ufi_axis.ui.theme.*
import com.ufi_axis.viewmodel.MainViewModel
import com.ufi_axis.viewmodel.module.FOREGROUND_REFRESH_INTERVAL_MS
import com.ufi_axis.ui.navigation.ufiNavigateOnce


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NetworkScreen(viewModel: MainViewModel, navController: NavHostController) {
    val state by viewModel.networkState.collectAsState()

    // 2026-10-05 P7：A 级操作进度弹窗（确认器状态机驱动；running=圆环 / success=对勾 / failed=叉）
    val opState by viewModel.network.opController.state.collectAsState()
    UfiOperationProgressDialog(
        state = opState,
        onBackground = { viewModel.network.opController.toBackground() },
        onDismiss = { viewModel.network.opController.dismiss() },
        // 2026-10-05 P7：failed 态提供「重试」（复跑最近一次规格）
        onRetry = { viewModel.network.opController.retry() },
    )

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
    val wsState by viewModel.wsConnectionState.collectAsState()

    // ── 「页面真的可见」判定（2026-11-07，10s 轻量轮询的启停条件）──
    // pageForeground 只回答「这个 Tab 是不是当前选中页」，**不回答**「有没有二级页盖在上面」。
    // 用户从网络页点进「蜂窝网络高级设置 / 频段锁定」时，Tab 仍处于选中态，pageForeground
    // 仍是 true —— 仅用它门控会让 10s 轮询在二级页背后继续空跑。
    //
    // LocalLifecycleOwner 在 Navigation Compose 里是**当前 NavBackStackEntry**（这里是 MAIN），
    // 二级页压栈时它退到 STARTED，返回时重新 RESUMED。用它补上「二级页是否盖住」这一维。
    // 默认 true：首次组合时 MAIN 就是 RESUMED；被预组合的离屏页由 pageForeground 拦住。
    val lifecycleOwner = LocalLifecycleOwner.current
    var lifecycleResumed by remember(lifecycleOwner) {
        // 不能用恒 true：本页可能在某条二级页压栈期间被预组合，observer 挂上时
        // MAIN 已经是 STARTED，收不到那次 ON_PAUSE。按当前状态播种才准。
        mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            lifecycleResumed = event == Lifecycle.Event.ON_RESUME
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            lifecycleResumed = false
        }
    }

    // 首次加载：仅在页面处于前台时才拉，离屏预组合时一个请求都不发。
    // pageForeground 作为 key：用户首次切到本 Tab 时 LaunchedEffect 以新协程重启并自动补拉。
    //
    // 2026-09-22：扇出从本文件的私有 loadNetworkAll(viewModel) 搬到 NetworkModule.loadNetworkAll()，
    // 那里带**新鲜度闸门**（NETWORK_ALL_FRESH_MS = 30s）。所以首屏预加载已经拉过的话，
    // 这里切过来是零请求、直接渲染 —— 这就是"切页不等待"的实现点。
    // delay(200) 保留：给横滑落定留出窗口，避免请求写状态落在胶囊归位的可见运动里。
    LaunchedEffect(pageForeground) {
        if (!pageForeground) return@LaunchedEffect
        kotlinx.coroutines.delay(200)
        viewModel.network.loadNetworkAll()
    }
    var retryCount by remember { mutableIntStateOf(0) }

    // WiFi 热点设置弹窗开关
    var showWifiSettingsDialog by remember { mutableStateOf(false) }
    // 连接二维码弹窗开关（2026-10-14：从 WiFi 设置弹窗里拆出来的独立入口）
    var showWifiQrDialog by remember { mutableStateOf(false) }

    // 出错 5s 后自动重试，最多 3 次（本页只有 10s 轻量轮询，出错后不重试会卡住）。
    // 同样只在页面前台时重试：后台 Tab 出错先躺着，等用户切回来（pageForeground 变 true
    // 会重启本效应）再补，避免看不见的页面在后台反复空转重试。
    LaunchedEffect(state.errorMessage, pageForeground) {
        if (state.errorMessage != null && pageForeground && retryCount < 3) {
            kotlinx.coroutines.delay(5_000)
            retryCount++
            // force：出错重试的前提就是"上一次没成功"，必须绕过新鲜度闸门。
            // 部分失败时 refreshNetwork 不打戳，本来也不会被闸门挡住，但写明更稳。
            viewModel.network.loadNetworkAll(force = true)
        }
    }

    // UID-007 (Wave 2)：返回前台自动刷新一次（ON_RESUME 触发；另有下面的 10s 轻量轮询）。
    // ON_RESUME 在 MAIN 这个 NavBackStackEntry 上触发，5 个保活 Tab 共享同一个 entry，
    // 因此必须在回调体内再判一次页面前台，否则息屏解锁一次，5 个 Tab 会各刷一遍。
    // 这里用 rememberUpdatedState 而非直接捕获 pageForeground：rememberResumeRefresh 内部的
    // DisposableEffect 只以 lifecycleOwner 为 key，观察者闭包在首次组合后就固定下来，
    // 直接捕获的是那一刻的 Boolean 快照（永远是首次值）；捕获 State 才能读到调用时的最新值。
    val foregroundForResume by rememberUpdatedState(pageForeground)
    rememberResumeRefresh {
        // 不 force：回前台时若数据还在 30s 新鲜窗口内就不必重取（息屏几秒解锁属常态）。
        if (foregroundForResume) viewModel.network.loadNetworkAll()
    }

    // ── 10s 轻量轮询（2026-11-07，与 Web 网络页对齐）────────────────────────
    // WS 负责 signal / connection；但 networkStatus 与 wifiClients 没有高频频道，
    // 且设备面板直改（绕过 core）只有轮询能发现。所以和 Web 一样每 10s 补两个请求：
    //   · GET /api/network/status  → 连接态 + 移动数据开关 + 运营商/制式
    //   · GET /api/wifi/clients    → 在线设备数
    // WS 断线时再额外拉一次 signal，顶住实时信号（与 Web 的 `wsStore.status !== 'connected'`
    // 分支同口径）。WS 在线时 signal 走推送，这里不发。
    //
    // 只在这页**真的可见**时跑：pageForeground（选中该 Tab）+ lifecycleResumed（没有被
    // 二级页盖住、App 在前台）。两个条件任一为 false 都会取消协程，不会后台空转。
    // 用 rememberUpdatedState 而不是把 wsState 放进 LaunchedEffect 的 key：WS 状态每变一次
    // 就重启 10s 计时器，在反复重连时 REST 兜底可能永远轮不到。捕获 State 读最新值即可。
    val wsStateForPoll by rememberUpdatedState(wsState)
    LaunchedEffect(pageForeground, lifecycleResumed) {
        if (!pageForeground || !lifecycleResumed) return@LaunchedEffect
        while (true) {
            kotlinx.coroutines.delay(FOREGROUND_REFRESH_INTERVAL_MS)
            viewModel.network.refreshNetworkLive(
                includeSignal = wsStateForPoll != ConnectionState.CONNECTED
            )
        }
    }

    // 顶栏不放手动刷新：本页有 ON_RESUME 自动重取，错误横幅自带「重试」，手动按钮属重复能力。
    UfiScreenScaffold(
        title = "网络设置",
        navController = navController,
        showBack = false,
        // 首页 Tab：标题栏显示「正在播放」
        showNowPlaying = true
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
                    // 2026-10-11：显示层转大写（n78 → N78），不改 core 的 band_label 值
                    currentBand = state.signalInfo?.bandLabelDisplay
                )

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
                            onClick = { navController.ufiNavigateOnce("detail/cellular-advanced") },
                            trailing = { UfiSettingsChevron() }
                        )
                    }

                    // WiFi 热点：点行进设置弹窗，点开关直接开/关热点
                    UfiSettingsRowCard {
                        UfiSettingsToggle(
                            icon = Icons.Default.Wifi,
                            title = "WiFi 热点",
                            // 2026-10-12（用户要求）：去掉「N 台设备」。原来那一项在这行里
                            // 只会挤窄 SSID（长名字会被截断得看不出全貌），而在线台数在 WiFi
                            // 设置弹窗里有独立列表和更完整的信息，这里重复一遍没有价值。
                            description = if (state.wifiEnabled) {
                                "${wifi?.ssid?.ifBlank { "未命名" } ?: "未命名"} · " +
                                    if (wifi?.activeChip == "chip2") "5GHz" else "2.4GHz"
                            } else "已关闭",
                            checked = state.wifiEnabled,
                            onCheckedChange = { viewModel.network.setWifiEnabled(it) },
                            onClick = { showWifiSettingsDialog = true }
                        )
                    }

                    // 连接二维码（2026-10-14 用户要求）：从 WiFi 设置弹窗里拆出来，单独一个入口，
                    // 就放在「WiFi 热点」下面。二维码是**只读**的一件事，而设置弹窗里全是
                    // "改完要保存"的草稿字段 —— 混在一起会让人以为二维码也要点保存才更新。
                    UfiSettingsRowCard {
                        UfiSettingsItem(
                            icon = Icons.Default.QrCode,
                            title = "连接二维码",
                            description = if (state.wifiEnabled) {
                                "扫码连接 ${wifi?.ssid?.ifBlank { "未命名" } ?: "未命名"} 热点"
                            } else {
                                "热点已关闭，开启后可用"
                            },
                            onClick = { showWifiQrDialog = true },
                            trailing = { UfiSettingsChevron() }
                        )
                    }

                    UfiSettingsRowCard {
                        UfiSettingsItem(
                            icon = Icons.Default.Speed,
                            title = "网速测试",
                            description = lastResult ?: "内网上下行 / 外网下行 · 多流并发测速",
                            onClick = { navController.ufiNavigateOnce(Routes.DETAIL_SPEED_TEST) },
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
                            onClick = { navController.ufiNavigateOnce(Routes.DETAIL_ONLINE_DEVICES) },
                            trailing = { UfiSettingsChevron() }
                        )
                    }
                }

                // 底部为胶囊导航栏留白（2026-09-17）：Tab 页上胶囊可交互，原来只有 8dp，
                // 最后那张「在线设备」入口卡正好落进胶囊那条带子里、点不动。
                // （2026-08-30 把在线设备列表拆成二级页时绕过的是同一个问题，这次修在根上。）
                Spacer(Modifier.ufiCapsuleBottomInset(Spacing.Medium))
            }

            // WiFi 热点设置弹窗（替代原独立页面跳转）
            if (showWifiSettingsDialog) {
                WifiSettingsDialog(
                    viewModel = viewModel,
                    visible = true,
                    onDismiss = { showWifiSettingsDialog = false }
                )
            }

            // 连接二维码弹窗（2026-10-14：从 WiFi 设置弹窗里拆出来的独立入口）。
            // chip 用**设备当前生效频段**：二维码由设备按已生效的配置生成，
            // 草稿里的选择还没保存时拿它去请求只会得到旧频段的图。
            if (showWifiQrDialog) {
                WifiQrCodeDialog(
                    viewModel = viewModel,
                    // 直接用 state 里的值：上面那个 `wifi` 局部 val 声明在内容列内部，这里已经出作用域
                    chip = state.wifiSettings?.activeChip ?: "chip1",
                    onDismiss = { showWifiQrDialog = false }
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
    val isNsa = rawRat?.let {
        it.contains("NSA", true) || it.contains("ENDC", true) || it.contains("EN-DC", true)
    } == true
    val networkTypeLabel = when {
        rawRat == null -> ""
        // EN-DC/ENDC 是 LTE 锚点 + NR 的 NSA 5G，不是独立制式。
        isNsa -> "5G NSA"
        rawRat.contains("5G", true) || rawRat.contains("NR", true) -> "5G"
        rawRat.contains("4G", true) || rawRat.contains("LTE", true) -> "4G"
        else -> rawRat
    }
    val operator = com.ufi_axis.data.model.CarrierNames
        .displayName(sig?.operator?.takeIf { it.isNotBlank() } ?: net?.operator)
        .ifBlank { "未知" }
    // 右上胶囊：制式 + 频段
    val pillText = buildString {
        append(networkTypeLabel)
        val band = currentBand?.takeIf { it.isNotBlank() && it != "—" }
        if (band != null) append(" $band")
    }.takeIf { it.isNotBlank() }
    // 副标题：本机号码(msisdn) + IMEI（取自设备身份信息，无数据时 —）
    // 手机号按用户要求显示完整号码，并去掉中国大陆区号前缀；不做 *** 脱敏。
    val msisdn = state.deviceIdentity?.get("msisdn")
        ?.takeIf { it.isNotBlank() }
        ?.let(::displayPhone)
        ?: "—"
    val imei = state.deviceIdentity?.get("imei")?.takeIf { it.isNotBlank() } ?: "—"
    val rsrpVal = sig?.rsrp
    val bars = rsrpVal?.let { signalBars(it) } ?: 0
    val isConnected = net?.isCellularConnected == true

    // ── 渐变：与首页 Hero 一致的 accent 明暗衍生色（阶梯口径见 theme 层的 Color.ufiShade）──
    // 深色档系数改自 GRADIENT_MID_LIGHTEN_DARK / GRADIENT_TOP_LIGHTEN_DARK 常量，理由见常量定义。
    val gradColors = if (palette.isDark) {
        listOf(
            palette.accent,
            palette.accent.ufiShade(GRADIENT_MID_LIGHTEN_DARK),
            palette.accent.ufiShade(GRADIENT_TOP_LIGHTEN_DARK)
        )
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
                // 2026-10-12（用户要求）：纵向内边距 16dp → 12dp，与首页 Hero 卡
                // （HomeConnectionCard，`horizontal = 16.dp, vertical = 12.dp`）对齐。
                // 原来顶部多出 4dp，运营商那行离卡片上边缘明显比其他 Hero 远一截，
                // 两个界面并排看时能直接看出错位。横向保持 16dp 不动。
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            // ═══ 行1：图标 + 标题(运营商) + 胶囊(制式频段) + 副标题(IP) / 信号条右上 ═══
            // 与仪表盘 Hero 一致：左上只有运营商 + 制式胶囊，右上连接状态 + 信号条。
            // 不再放左侧卫星图标；网络详情改用下方扁平指标行展示。
            Row(verticalAlignment = Alignment.Top) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = operator,
                            style = UfiTextStyles.metricValueCompact,
                            color = palette.onGradient,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            // 与仪表盘 Hero 同样的光学校正：中文粗体字形比下方小字略右偏。
                            modifier = Modifier
                                .weight(1f, fill = false)
                                .offset(x = (-0.7).dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        pillText?.let {
                            Surface(
                                color = palette.gradientMuted.copy(alpha = 0.18f),
                                shape = UfiCardDefaults.tagShape
                            ) {
                                Text(
                                    text = it,
                                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 1.5.dp),
                                    color = palette.onGradient,
                                    style = UfiTextStyles.label.copy(fontWeight = UfiWeight.Emphasis),
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                    // 与仪表盘 Hero 的第二行同款：运营商下方放一行弱化身份/网络副标题。
                    // 有本机号码时显示号码；没有就显示“蜂窝网络”，保持两行结构不塌。
                    Text(
                        text = "蜂窝网络",
                        style = UfiTextStyles.note.copy(fontWeight = UfiWeight.Medium),
                        color = palette.gradientMuted.copy(alpha = 0.7f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.width(10.dp))
                Row(
                    modifier = Modifier.padding(top = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(UfiCardDefaults.pillShape)
                            .background(
                                if (isConnected) StatusOnline
                                else palette.gradientMuted.copy(alpha = 0.4f)
                            )
                    )
                    Text(
                        text = if (isConnected) "已连接" else "未连接",
                        style = MaterialTheme.typography.labelSmall,
                        color = palette.gradientMuted.copy(alpha = 0.9f),
                        maxLines = 1
                    )
                    Spacer(Modifier.width(2.dp))
                    UfiGradientSignalBar(bars = if (isConnected) bars else 0)
                }
            }

            Spacer(Modifier.height(18.dp))
            // 中间只放三项核心信号读数：RSRP / SNR / RSRQ。
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min),
            ) {
                NetworkHeroMetric(sig?.rsrp?.toString() ?: "—", "RSRP dBm", Modifier.weight(1f))
                NetworkHeroDivider()
                NetworkHeroMetric(sig?.sinr?.toString() ?: "—", "SNR dB", Modifier.weight(1f))
                NetworkHeroDivider()
                NetworkHeroMetric(sig?.rsrq?.toString() ?: "—", "RSRQ dB", Modifier.weight(1f))
            }
            Spacer(Modifier.height(19.dp))
            // 与仪表盘网速胶囊同一套材质和几何：两枚等宽半透明胶囊。
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                NetworkHeroCapsule(
                    label = "手机号",
                    value = msisdn,
                    // 手机号与 IMEI 各占一半；完整值放在标签下方，避免横向挤压省略。
                    modifier = Modifier.weight(1f)
                )
                NetworkHeroCapsule(
                    label = "IMEI",
                    value = imei,
                    modifier = Modifier.weight(1f)
                )
            }

        }
    }
}

/** 网络 Hero 底部胶囊：与仪表盘 SpeedCapsule 同一档半透明底、圆角和内边距。 */
@Composable
private fun NetworkHeroCapsule(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    val palette = LocalResolvedPalette.current
    Surface(
        modifier = modifier,
        color = palette.gradientMuted.copy(alpha = 0.15f),
        shape = UfiCardDefaults.subtleShape
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            // 按可用宽度自适应：空间够就「标签 + 值」一行，不够才上下排。
            // 估算按 13sp 数字的保守字宽；IMEI 比手机号长，通常更早进入上下排。
            val inlineThreshold = (label.length * 12 + value.length * 9 + 6).dp + 24.dp
            val inline = maxWidth >= inlineThreshold
            if (inline) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        label,
                        style = MaterialTheme.typography.labelSmall,
                        color = palette.gradientMuted.copy(alpha = 0.75f),
                        maxLines = 1
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        value,
                        style = UfiTextStyles.bodyStrong.copy(fontSize = 13.sp),
                        color = palette.onGradient,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f)
                    )
                }
            } else {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        label,
                        style = MaterialTheme.typography.labelSmall,
                        color = palette.gradientMuted.copy(alpha = 0.75f),
                        maxLines = 1
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        value,
                        style = UfiTextStyles.bodyStrong.copy(fontSize = 13.sp),
                        color = palette.onGradient,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Start,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}

/**
 * 手机号展示：完整显示，不脱敏；只去掉 core 透传的 +86 / 86 国家码前缀。
 * 设备可能回 `+86138…`、`86138…` 或本地 `138…`，三种都归一到 11 位本地号码。
 */
private fun displayPhone(raw: String): String {
    val text = raw.trim()
    return when {
        text.startsWith("+86") -> text.removePrefix("+86").trim()
        text.startsWith("86") && text.length > 11 -> text.removePrefix("86").trim()
        else -> text
    }
}

/** 网络 Hero 的扁平指标格：与仪表盘 Hero 的指标行同一套字号/留白。 */
@Composable
private fun NetworkHeroMetric(value: String, label: String, modifier: Modifier = Modifier) {
    val palette = LocalResolvedPalette.current
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            value,
            style = UfiTextStyles.metricValue,
            color = palette.onGradient,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = palette.gradientMuted.copy(alpha = 0.7f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun NetworkHeroDivider() {
    val palette = LocalResolvedPalette.current
    Box(
        Modifier
            .fillMaxHeight()
            .width(1.dp)
            .background(palette.gradientMuted.copy(alpha = 0.18f))
    )
}


