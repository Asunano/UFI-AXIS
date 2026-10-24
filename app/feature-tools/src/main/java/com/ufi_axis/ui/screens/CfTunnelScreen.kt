// [F24] STABLE-UI-API：公共组件签名已冻结，请勿在无向后兼容前提下修改；实验性组件请使用 @UfiExperimentalApi（见 UfiStableApi.kt / UfiExperimentalApi.kt）。
package com.ufi_axis.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.ufi_axis.ui.components.common.*
import com.ufi_axis.ui.theme.LocalResolvedPalette
import com.ufi_axis.ui.theme.Spacing
import com.ufi_axis.ui.theme.UfiTextStyles
import com.ufi_axis.viewmodel.MainViewModel
import kotlinx.coroutines.delay

/**
 * CF 单隧道管理页（2026-08-26 新增）：token 编辑 + 启停 + 日志 + 删除。
 *
 * 进入即把该隧道设为"选中"并读取 token。启停判定以 cloudflared 该实例的进程状态为准，
 * 不能用"被选中"冒充 —— 只是点进来也会改写选中项。cloudflared 支持多实例，本页只管这一条。
 */
@Composable
fun CfTunnelScreen(viewModel: MainViewModel, navController: NavHostController, tunnelName: String) {
    val state by viewModel.tunnelState.collectAsState()

    // ── lifecycle 门控（2026-09-21）──
    var lifecycleResumed by remember { mutableStateOf(true) }
    val lifecycleOwner = LocalLifecycleOwner.current
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
    val palette = LocalResolvedPalette.current
    val clipboard = LocalClipboardManager.current
    var toastMessage by remember { mutableStateOf<ToastMessage?>(null) }

    var tokenText by remember { mutableStateOf("") }
    var loadedFor by remember { mutableStateOf<String?>(null) }
    var logPolling by remember { mutableStateOf(false) }
    var showLogDialog by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    LaunchedEffect(tunnelName) {
        tokenText = ""
        loadedFor = null
        // errorMessage 是隧道模块共享的，进页面先清一次
        viewModel.tunnel.clearError()
        viewModel.tunnel.loadStatus()
        // tokenSet 来自列表接口，直达本页（进程重启后导航恢复）时必须自己拉一次，
        // 否则启动键会被永久禁用并误报"尚未保存 token"
        viewModel.tunnel.loadCfTunnels()
        viewModel.tunnel.selectCfTunnel(tunnelName)
        viewModel.tunnel.refreshCfLog(tunnelName)
    }

    // token 现在按隧道名分桶（state.cfTokens），不会再串台；null = 还没读到过
    LaunchedEffect(tunnelName, state.cfTokens) {
        val loaded = state.cfTokenOf(tunnelName)
        if (loadedFor != tunnelName && loaded != null) {
            tokenText = loaded
            loadedFor = tunnelName
        }
    }

    LaunchedEffect(logPolling, tunnelName, lifecycleResumed) {
        while (logPolling && lifecycleResumed) {
            viewModel.tunnel.refreshCfLog(tunnelName)
            delay(2000)
        }
    }

    // 状态兜底轮询：进程可能自己退出（token 失效）或被别处停掉，不轮询会一直显示"运行中"
    LaunchedEffect(tunnelName, lifecycleResumed) {
        if (!lifecycleResumed) return@LaunchedEffect
        while (true) {
            delay(5000)
            viewModel.tunnel.loadStatus()
        }
    }

    // 本隧道的运行期状态：多实例下每条隧道各自一份
    val instance = state.cfInstance(tunnelName)
    val thisRunning = instance?.running == true
    val thisStatus = instance?.status ?: "Stopped"
    val thisError = instance?.lastError ?: ""
    // 磁盘上是否已存有 token（启停判据用它，不能用编辑框内容：清空输入框不等于删掉 token）
    val tokenSaved = state.cfTunnelItems.firstOrNull { it.name == tunnelName }?.tokenSet == true
    val tokenDirty = loadedFor == tunnelName &&
        tokenText.trim() != (state.cfTokenOf(tunnelName) ?: "").trim()
    // 日志按隧道取，归属不对就不显示
    val thisLog = state.cfLogOf(tunnelName)


    UfiScreenScaffold(
        title = tunnelName,
        navController = navController,
        showBack = true
    ) { padding ->
        /*
         * 横向留白口径（2026-10-13 统一）：本页**不再**用 `contentHPadding = PagePadding`（12dp），
         * 改为「谁自带外边距谁给」——
         *  · 自带：`UfiSettingsRowCard` / `UfiStatusCard` / `UfiLogPanel` 内建
         *    `CardHorizontalMargin`（16dp），调用点**不得**再补；
         *  · 不自带：`UfiButtonRow` / 裸 `UfiButton` 由调用点补同一个 Modifier ——
         *    与 `TunnelSettingsScreen` / `TrafficManagementScreen` 同做法。
         *  · **`UfiErrorBanner` 谁都不补**：它是 Popup，横向位置由 `UfiBannerDefaults.sideMargin`
         *    独立算出、与页面的 `contentHPadding` 无关；传 padding 反而会让长文案左右不对称
         *    （详见该组件 KDoc 的 2026-10-13 更正）。全库 9 个调用点已统一去掉。
         *
         * 为什么必须整页一起换：两种口径混排会左右差 4dp（16 vs 12），肉眼可见。
         * token 卡原先手搓 `Surface`（只有 1dp 描边、**无阴影**、内距 12dp），
         * 换成 `UfiSettingsRowCard` 后才与设置页的卡同族；而它自带 16dp 外边距，
         * 所以同页其余组件必须一起对齐到 16dp，否则新卡会比邻居凸出来。
         *
         * 2026-10-13 补完：`UfiStatusCard` / `UfiLogPanel` 现已自带同一个外边距（全站卡片组件
         * 一起收敛），本页给它们补的那两层 Modifier 已删除 —— 否则就是 16+16=32dp 的双重内距。
         */
        UfiPageBackground(modifier = Modifier.padding(padding)) {
            // 平级卡/面板（状态卡 · token 卡 · 日志面板 · 操作按钮行）之间的间距统一走
            // Spacing.SettingsCardGap，与设置类页面同档；原来是 Spacing.Large（12dp）。
            // token 卡**内部**的 spacedBy(Spacing.Medium) 是表单字段堆叠，保持不动。
            Column(
                modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.Large),
                verticalArrangement = Arrangement.spacedBy(Spacing.SettingsCardGap)
            ) {
                state.errorMessage?.let { msg -> UfiErrorBanner(message = msg) }

                UfiStatusCard(
                    title = "隧道状态",
                    statusText = when {
                        // 句柄还在但状态是 Error：这是"强制停止失败"，不是启动失败
                        thisRunning && thisStatus == "Error" -> "停止失败"
                        thisRunning -> thisStatus
                        thisError.isNotBlank() -> "启动失败"
                        else -> "未运行"
                    },
                    statusType = when {
                        thisRunning && thisStatus == "Error" -> UfiBadgeType.ERROR
                        thisRunning -> UfiBadgeType.SUCCESS
                        thisError.isNotBlank() -> UfiBadgeType.ERROR
                        else -> UfiBadgeType.DEFAULT
                    },
                    subtitle = when {
                        // 后端记录的失败原因（token 无效秒退等）直接摊开给用户，别让他去翻日志猜
                        thisError.isNotBlank() -> thisError
                        !tokenSaved -> "尚未保存 token，保存后才能启动"
                        state.cfVersion.isNotBlank() -> "cloudflared ${state.cfVersion}"
                        else -> null
                    }
                )

                // ── token ──
                // 2026-10-13：由手搓 `Surface`（1dp 描边 + cardBg + InnerPadding，**无阴影**）
                // 换成公共的 `UfiSettingsRowCard`。理由：它是本页唯一一张"设置项形态"的卡
                //（标题 + 表单字段 + 提交按钮），而手搓版与设置页的卡不同族 —— 邻居有 2dp 阴影、
                // 它只有描边，同屏看就是"这张卡陷进去了"。
                // 内距用 `XLarge`(16dp)：卡里放的是表单而非单行设置项，按 `UfiSettingsRowCard`
                // 的 KDoc 指引覆盖（同 `DownloadSettingsUi` 的「保存路径」卡）。
                UfiSettingsRowCard(contentPadding = PaddingValues(Spacing.XLarge)) {
                    Column(verticalArrangement = Arrangement.spacedBy(Spacing.Medium)) {
                        Text(
                            "Tunnel token",
                            style = UfiTextStyles.sectionTitle,
                            color = palette.textPrimary
                        )
                        UfiPasswordField(
                            value = tokenText,
                            onValueChange = { tokenText = it },
                            label = "token",
                            placeholder = "Zero Trust 面板 Networks → Tunnels 里复制"
                        )
                        Text(
                            if (tokenDirty) "token 有未保存的改动：先「保存 token」再启动，否则启动用的还是已保存的旧 token。"
                            else "路由（ingress）由面板下发，本地不需要写 yml；改完 token 需重新启动隧道才生效。",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (tokenDirty) palette.error else palette.textSecondary
                        )
                        UfiButton(
                            text = "保存 token",
                            enabled = tokenText.isNotBlank(),
                            onClick = {
                                viewModel.tunnel.saveCfTunnel(tunnelName, tokenText.trim())
                                toastMessage = ToastMessage("token 已保存", ToastType.SUCCESS)
                            }
                        )
                    }
                }

                UfiLogPanel(
                    log = thisLog,
                    autoRefreshing = logPolling,
                    onToggleAutoRefresh = { logPolling = !logPolling },
                    onRefresh = { viewModel.tunnel.refreshCfLog(tunnelName) },
                    // "查看完整"读运行期落盘的日志文件（尾部 256KB），而不是内存里的最近 200 行
                    onViewFull = {
                        viewModel.tunnel.refreshCfLog(tunnelName, full = true)
                        showLogDialog = true
                    },
                    emptyHint = "（暂无日志，启动后将显示 cloudflared 输出）"
                )

                UfiButtonRow(modifier = Modifier.padding(horizontal = Spacing.CardHorizontalMargin)) {
                    UfiButton(
                        text = "启动",
                        modifier = Modifier.weight(1f),
                        enabled = !thisRunning && tokenSaved,
                        onClick = {
                            viewModel.tunnel.startCfTunnel(tunnelName)
                            logPolling = true
                            toastMessage = ToastMessage("隧道启动中…", ToastType.INFO)
                        }
                    )
                    UfiButton(
                        variant = UfiButtonVariant.Secondary,
                        text = "停止",
                        modifier = Modifier.weight(1f),
                        enabled = thisRunning,
                        onClick = {
                            viewModel.tunnel.stopCfTunnel(tunnelName)
                            logPolling = false
                            toastMessage = ToastMessage("正在停止…", ToastType.INFO)
                        }
                    )
                }

                UfiButton(
                    variant = UfiButtonVariant.Danger,
                    text = "删除该隧道",
                    modifier = Modifier.padding(horizontal = Spacing.CardHorizontalMargin),
                    onClick = { showDeleteConfirm = true }
                )

                Spacer(Modifier.height(Spacing.Medium))
            }

            UfiLogDialog(
                visible = showLogDialog,
                onDismiss = { showLogDialog = false },
                log = thisLog,
                title = "cloudflared 完整日志",
                onCopy = {
                    clipboard.setText(AnnotatedString(thisLog))
                    toastMessage = ToastMessage("日志已复制", ToastType.SUCCESS)
                }
            )

            if (showDeleteConfirm) {
                UfiConfirmDialog(
                    title = "删除隧道",
                    text = "确定删除隧道 [$tunnelName] 吗？保存的 token 将一并删除，此操作不可撤销。",
                    confirmText = "删除",
                    destructive = true,
                    onConfirm = {
                        viewModel.tunnel.deleteCfTunnel(tunnelName)
                        showDeleteConfirm = false
                        navController.popBackStack()
                    },
                    onDismiss = { showDeleteConfirm = false }
                )
            }

            UfiToastHost(toastMessage = toastMessage, onDismiss = { toastMessage = null })
        }
    }
}
