// [F24] STABLE-UI-API：公共组件签名已冻结，请勿在无向后兼容前提下修改；实验性组件请使用 @UfiExperimentalApi（见 UfiStableApi.kt / UfiExperimentalApi.kt）。
package com.ufi_axis.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.ufi_axis.ui.components.common.*
import com.ufi_axis.ui.theme.LocalResolvedPalette
import com.ufi_axis.ui.theme.Spacing
import com.ufi_axis.ui.theme.UfiCardDefaults
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

    // token 归属判定：cfTokenText 是全局共享 state，不判归属会把上一条隧道的 token 带进本页
    LaunchedEffect(tunnelName, state.cfTokenName, state.cfTokenText) {
        if (loadedFor != tunnelName && state.cfTokenName == tunnelName) {
            tokenText = state.cfTokenText
            loadedFor = tunnelName
        }
    }

    LaunchedEffect(logPolling, tunnelName) {
        while (logPolling) {
            viewModel.tunnel.refreshCfLog(tunnelName)
            delay(2000)
        }
    }

    // 状态兜底轮询：进程可能自己退出（token 失效）或被别处停掉，不轮询会一直显示"运行中"
    LaunchedEffect(tunnelName) {
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
    val tokenDirty = loadedFor == tunnelName && tokenText.trim() != state.cfTokenText.trim()
    // 日志按隧道取，归属不对就不显示
    val thisLog = state.cfLogOf(tunnelName)


    UfiScreenScaffold(
        title = tunnelName,
        navController = navController,
        showBack = true
    ) { padding ->
        UfiPageBackground(
            modifier = Modifier.padding(padding),
            contentHPadding = Spacing.PagePadding
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.Large),
                verticalArrangement = Arrangement.spacedBy(Spacing.Large)
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
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = UfiCardDefaults.shape,
                    color = palette.cardBg,
                    border = BorderStroke(1.dp, palette.cardBorder)
                ) {
                    Column(
                        modifier = Modifier.padding(Spacing.InnerPadding),
                        verticalArrangement = Arrangement.spacedBy(Spacing.Medium)
                    ) {
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

                UfiButtonRow {
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

                UfiButton(variant = UfiButtonVariant.Danger, text = "删除该隧道", onClick = { showDeleteConfirm = true })

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
