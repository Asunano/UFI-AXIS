// [F24] STABLE-UI-API：公共组件签名已冻结，请勿在无向后兼容前提下修改；实验性组件请使用 @UfiExperimentalApi（见 UfiStableApi.kt / UfiExperimentalApi.kt）。
package com.ufi_axis.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import com.ufi_axis.ui.components.common.*
import com.ufi_axis.ui.navigation.Routes
import com.ufi_axis.ui.theme.LocalResolvedPalette
import com.ufi_axis.ui.theme.Spacing
import com.ufi_axis.viewmodel.MainViewModel

/**
 * FRP 隧道列表页（2026-08-26 重构）。
 *
 * 本页只负责"有哪些隧道"：右上角 + 新建隧道（输入名称即创建入口），点击条目进入
 * [FrpChannelScreen] 做具体配置（编辑 TOML / 查看日志 / 启停 / 删除）。
 * 通道列表以后端 configs 目录为唯一事实来源，删空后为空列表而不是 404。
 */
@Composable
fun FrpDetailScreen(viewModel: MainViewModel, navController: NavHostController) {
    val state by viewModel.tunnelState.collectAsState()
    val palette = LocalResolvedPalette.current
    var toastMessage by remember { mutableStateOf<ToastMessage?>(null) }
    var showNewChannel by remember { mutableStateOf(false) }
    var newChannelName by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        // errorMessage 是隧道模块共享的：进页面先清一次，否则上一个页面的失败横幅会挂在这里
        viewModel.tunnel.clearError()
        viewModel.tunnel.loadStatus()
        viewModel.tunnel.loadFrpConfigs()
    }

    UfiScreenScaffold(
        title = "FRP",
        navController = navController,
        showBack = true,
        actions = {
            IconButton(onClick = {
                newChannelName = ""
                showNewChannel = true
            }) {
                Icon(Icons.Default.Add, contentDescription = "新建隧道", tint = palette.accent)
            }
        }
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

                if (state.frpConfigItems.isEmpty()) {
                    UfiEmptyState(
                        icon = Icons.Default.Dns,
                        message = "暂无隧道",
                        hint = "点击右上角 + 新建一条隧道"
                    )
                } else {
                    state.frpConfigItems.forEach { item ->
                        UfiEntryCard(
                            title = item.name,
                            subtitle = buildString {
                                if (item.serverAddr.isBlank()) append("未配置服务端")
                                else append("${item.serverAddr}:${item.serverPort}")
                                append(" · ${item.proxyCount} 个代理")
                            },
                            icon = Icons.Default.Dns,
                            onClick = { navController.navigate(Routes.tunnelFrpChannel(item.name)) },
                            badgeText = if (item.running) "运行中" else null,
                            highlighted = item.name == state.frpActiveConfig,
                            iconTint = if (item.running) palette.accent else palette.textSecondary
                        )
                    }
                }

                Spacer(Modifier.height(Spacing.Medium))
            }

            if (showNewChannel) {
                UfiCustomDialog(
                    visible = true,
                    onDismiss = { showNewChannel = false },
                    title = "新建隧道"
                ) {
                    UfiDialogBody {
                        UfiDialogTextField(
                            label = "隧道名称",
                            value = newChannelName,
                            onValueChange = { newChannelName = it },
                            placeholder = "如 家宽穿透 / my_frp"
                        )
                        UfiDialogActions(
                            onDismiss = { showNewChannel = false },
                            onConfirm = {
                                val name = newChannelName.trim()
                                when {
                                    name.isBlank() ->
                                        toastMessage = ToastMessage("请输入隧道名称", ToastType.ERROR)
                                    name.contains(ILLEGAL_NAME_CHARS) || name.contains("..") ->
                                        toastMessage = ToastMessage("名称不能包含 \\ / : * ? \" < > | 或 ..", ToastType.ERROR)
                                    name.length > MAX_TUNNEL_NAME_LEN ->
                                        toastMessage = ToastMessage("名称不能超过 $MAX_TUNNEL_NAME_LEN 个字符", ToastType.ERROR)
                                    state.frpConfigItems.any { it.name.equals(name, true) } ->
                                        toastMessage = ToastMessage("隧道 [$name] 已存在", ToastType.ERROR)
                                    else -> {
                                        viewModel.tunnel.saveFrpConfigFile(name, minimalFrpToml())
                                        showNewChannel = false
                                        toastMessage = ToastMessage("已创建隧道 [$name]", ToastType.SUCCESS)
                                    }
                                }
                            },
                            confirmText = "确认"
                        )
                    }
                }
            }

            UfiToastHost(toastMessage = toastMessage, onDismiss = { toastMessage = null })
        }
    }
}

/** 隧道名里不允许出现的字符（与后端 FrpEngine.isValidName 同规则，避免保存时被 400 拒绝） */
internal val ILLEGAL_NAME_CHARS = Regex("[\\\\/:*?\"<>|]")

/** 隧道名最大长度（与后端 FrpEngine.sanitizeName 的 take(64) 一致，超长会被后端 400） */
internal const val MAX_TUNNEL_NAME_LEN = 64

/** 最小可用 TOML 模板（新建隧道时的占位，用户需在详情页填写真实服务端信息）。 */
internal fun minimalFrpToml(): String = buildString {
    appendLine("serverAddr = \"\"")
    appendLine("serverPort = 7000")
    appendLine("auth.token = \"\"")
    appendLine()
    appendLine("[[proxies]]")
    appendLine("name = \"web\"")
    appendLine("type = \"tcp\"")
    appendLine("localIP = \"127.0.0.1\"")
    appendLine("localPort = 8088")
    appendLine("remotePort = 8088")
}

