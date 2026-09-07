// [F24] STABLE-UI-API：公共组件签名已冻结，请勿在无向后兼容前提下修改；实验性组件请使用 @UfiExperimentalApi（见 UfiStableApi.kt / UfiExperimentalApi.kt）。
package com.ufi_axis.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.navigation.NavHostController
import com.ufi_axis.ui.components.common.*
import com.ufi_axis.ui.theme.LocalResolvedPalette
import com.ufi_axis.ui.theme.Spacing
import com.ufi_axis.viewmodel.MainViewModel
import kotlinx.coroutines.delay

/**
 * FRP 单条隧道的配置页（2026-08-26 重构为公共组件）。
 *
 * 从 [FrpDetailScreen] 列表点击进入，负责该隧道的：编辑 TOML 配置、智能导入、查看运行日志、
 * 启动/停止、删除。进入时把该隧道设为后端的活动通道，启动即启动这条隧道的配置文件。
 *
 * 注意：frpc 支持多实例，本页只管这一条通道 —— 状态与日志都按通道名取，互不影响。
 */
@Composable
fun FrpChannelScreen(viewModel: MainViewModel, navController: NavHostController, channelName: String) {
    val state by viewModel.tunnelState.collectAsState()
    val clipboard = LocalClipboardManager.current
    var toastMessage by remember { mutableStateOf<ToastMessage?>(null) }

    var tomlText by remember { mutableStateOf("") }
    // 已把哪个通道的文本灌进编辑器：frpConfigText 是全局共享 state，
    // 不做归属判定会把上一个通道的 TOML 带进本页（随后保存就写错了通道）
    var loadedFor by remember { mutableStateOf<String?>(null) }
    var showImportDialog by remember { mutableStateOf(false) }
    var showLogDialog by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var logPolling by remember { mutableStateOf(false) }

    // 进入页面（或切换通道）：清空编辑器，设为选中通道并加载该通道的 TOML 与日志
    LaunchedEffect(channelName) {
        tomlText = ""
        loadedFor = null
        // errorMessage 是隧道模块共享的，进页面先清一次，避免别的通道的失败横幅挂在这里
        viewModel.tunnel.clearError()
        viewModel.tunnel.loadStatus()
        viewModel.tunnel.selectFrpConfig(channelName)
        viewModel.tunnel.refreshFrpLog(channelName)
    }

    LaunchedEffect(channelName, state.frpConfigTextName, state.frpConfigText) {
        if (loadedFor != channelName && state.frpConfigTextName == channelName) {
            tomlText = state.frpConfigText
            loadedFor = channelName
        }
    }

    // 启动后轮询日志（每 2 秒）
    LaunchedEffect(logPolling, channelName) {
        while (logPolling) {
            viewModel.tunnel.refreshFrpLog(channelName)
            delay(2000)
        }
    }

    // 状态兜底轮询：frpc 可能自己退出或被别处停掉，不轮询会一直显示"运行中"
    LaunchedEffect(channelName) {
        while (true) {
            delay(5000)
            viewModel.tunnel.loadStatus()
        }
    }

    // 本通道的运行期状态：多实例下每条通道各自一份（不能用引擎级全局量）
    val instance = state.frpInstance(channelName)
    val thisRunning = instance?.running == true
    val thisStatus = instance?.status ?: "Stopped"
    val thisError = instance?.lastError ?: ""
    // 日志是按通道取的，归属不对就不显示（避免把上一条通道的日志当成本页的）
    val thisLog = state.frpLogOf(channelName)


    UfiScreenScaffold(
        title = channelName,
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
                    title = channelName,
                    statusText = when {
                        // 句柄还在（thisRunning）但状态是 Error：这是"强制停止失败"，不是启动失败
                        thisRunning && thisStatus == "Error" -> "停止失败"
                        thisRunning -> thisStatus
                        // frpc 秒退/校验不通过：显示"启动失败"而不是"未运行"
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
                        // 后端记录的失败原因（缺 serverAddr、秒退、停不掉等）直接显示，避免用户对着状态猜
                        thisError.isNotBlank() -> thisError
                        state.frpVersion.isNotBlank() -> "frpc ${state.frpVersion}"
                        else -> null
                    }
                )

                UfiCodeEditorCard(
                    title = "配置 (TOML)",
                    value = tomlText,
                    onValueChange = { tomlText = it },
                    label = "frpc.toml 内容",
                    actionText = "复制",
                    onAction = {
                        clipboard.setText(AnnotatedString(tomlText))
                        toastMessage = ToastMessage("已复制配置", ToastType.SUCCESS)
                    }
                ) {
                    UfiButtonRow {
                        UfiButton(
                            variant = UfiButtonVariant.Secondary,
                            text = "智能导入",
                            onClick = { showImportDialog = true },
                            modifier = Modifier.weight(1f)
                        )
                        UfiButton(
                            text = "保存配置",
                            onClick = {
                                viewModel.tunnel.saveFrpConfigFile(channelName, tomlText)
                                toastMessage = ToastMessage("配置已保存", ToastType.SUCCESS)
                            },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                UfiLogPanel(
                    log = thisLog,
                    autoRefreshing = logPolling,
                    onToggleAutoRefresh = { logPolling = !logPolling },
                    onRefresh = { viewModel.tunnel.refreshFrpLog(channelName) },
                    // "查看完整"读运行期落盘的日志文件（尾部 256KB），而不是内存里的最近 200 行
                    onViewFull = {
                        viewModel.tunnel.refreshFrpLog(channelName, full = true)
                        showLogDialog = true
                    },
                    emptyHint = "（暂无日志，启动后将显示 frpc 输出）"
                )

                UfiButtonRow {
                    UfiButton(
                        text = "启动",
                        onClick = {
                            viewModel.tunnel.startFrpWithConfig(channelName)
                            logPolling = true
                            toastMessage = ToastMessage("FRP 启动中…", ToastType.INFO)
                        },
                        enabled = !thisRunning,
                        modifier = Modifier.weight(1f)
                    )
                    UfiButton(
                        variant = UfiButtonVariant.Secondary,
                        text = "停止",
                        onClick = {
                            viewModel.tunnel.stopFrp(channelName)
                            logPolling = false
                            toastMessage = ToastMessage("正在停止…", ToastType.INFO)
                        },
                        enabled = thisRunning,
                        modifier = Modifier.weight(1f)
                    )
                }

                UfiButton(variant = UfiButtonVariant.Danger, text = "删除该隧道", onClick = { showDeleteConfirm = true })

                Spacer(Modifier.height(Spacing.Medium))
            }

            if (showImportDialog) {
                FrpImportDialog(
                    onDismiss = { showImportDialog = false },
                    onImported = { toml ->
                        tomlText = toml
                        loadedFor = channelName
                        showImportDialog = false
                        toastMessage = ToastMessage("已解析并填入配置，请点击保存", ToastType.SUCCESS)
                    }
                )
            }

            UfiLogDialog(
                visible = showLogDialog,
                onDismiss = { showLogDialog = false },
                log = thisLog,
                title = "FRP 完整日志",
                onCopy = {
                    clipboard.setText(AnnotatedString(thisLog))
                    toastMessage = ToastMessage("日志已复制", ToastType.SUCCESS)
                }
            )

            UfiConfirmDialog(
                visible = showDeleteConfirm,
                title = "删除隧道",
                text = "确定删除隧道 [$channelName] 吗？配置文件将一并删除，此操作不可撤销。",
                confirmText = "删除",
                destructive = true,
                onConfirm = {
                    viewModel.tunnel.deleteFrpConfig(channelName)
                    showDeleteConfirm = false
                    navController.popBackStack()
                },
                onDismiss = { showDeleteConfirm = false }
            )

            UfiToastHost(toastMessage = toastMessage, onDismiss = { toastMessage = null })
        }
    }
}

/** 智能导入弹窗：粘贴 frpc.ini 全文 → 解析为 TOML 填回编辑器（不直接落盘，需用户点保存）。 */
@Composable
private fun FrpImportDialog(onDismiss: () -> Unit, onImported: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    UfiScrollableDialog(
        visible = true,
        onDismiss = onDismiss,
        title = "智能导入配置",
        subtitle = {
            Text(
                text = "粘贴 frpc.ini 全文，自动解析 [common] 与代理段并转为 TOML",
                style = MaterialTheme.typography.bodySmall,
                color = LocalResolvedPalette.current.textSecondary
            )
        }
    ) {
        UfiDialogBody {
            UfiDialogTextField(
                label = "FRP 配置文本",
                value = text,
                onValueChange = { text = it; error = null },
                placeholder = "支持标准换行或紧凑无换行格式",
                singleLine = false,
                isError = error != null,
                errorMessage = error
            )
            UfiDialogActions(
                onDismiss = onDismiss,
                onConfirm = {
                    try {
                        onImported(frpConfigToToml(parseFrpcIni(text)))
                    } catch (e: Exception) {
                        error = e.message ?: "解析失败"
                    }
                },
                confirmText = "解析并导入",
                enabled = text.isNotBlank()
            )
        }
    }
}
