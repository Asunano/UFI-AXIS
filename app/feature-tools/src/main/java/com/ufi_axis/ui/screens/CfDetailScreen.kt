// [F24] STABLE-UI-API：公共组件签名已冻结，请勿在无向后兼容前提下修改；实验性组件请使用 @UfiExperimentalApi（见 UfiStableApi.kt / UfiExperimentalApi.kt）。
package com.ufi_axis.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Cloud
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
 * Cloudflare Tunnel 隧道列表页（2026-08-26 重构）。
 *
 * 只保留 remote-managed（token）模式：临时隧道（trycloudflare）与本地 ingress 编辑已移除 ——
 * 带 token 运行时路由由 Cloudflare 面板下发，本地写 yml 也会被忽略。
 * 右上角 + 新建隧道（名称 + token），点条目进 [CfTunnelScreen] 管理该隧道。
 */
@Composable
fun CfDetailScreen(viewModel: MainViewModel, navController: NavHostController) {
    val state by viewModel.tunnelState.collectAsState()
    val palette = LocalResolvedPalette.current
    var toastMessage by remember { mutableStateOf<ToastMessage?>(null) }
    var showNewTunnel by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    var newToken by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        // errorMessage 是隧道模块共享的：进页面先清一次
        viewModel.tunnel.clearError()
        viewModel.tunnel.loadStatus()
        viewModel.tunnel.loadCfTunnels()
    }

    UfiScreenScaffold(
        title = "Cloudflare Tunnel",
        navController = navController,
        showBack = true,
        actions = {
            IconButton(onClick = {
                newName = ""
                newToken = ""
                showNewTunnel = true
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

                if (state.cfTunnelItems.isEmpty()) {
                    UfiEmptyState(
                        icon = Icons.Default.Cloud,
                        message = "暂无隧道",
                        hint = "点击右上角 + 新建一条隧道"
                    )
                } else {
                    state.cfTunnelItems.forEach { item ->
                        UfiEntryCard(
                            title = item.name,
                            subtitle = if (item.tokenSet) "token 已配置" else "缺少 token",
                            icon = Icons.Default.Cloud,
                            onClick = { navController.navigate(Routes.tunnelCfTunnel(item.name)) },
                            badgeText = if (item.running) "运行中" else null,
                            highlighted = item.name == state.cfActiveTunnel,
                            iconTint = if (item.running) palette.accent else palette.textSecondary
                        )
                    }
                }

                Spacer(Modifier.height(Spacing.Medium))
            }

            if (showNewTunnel) {
                UfiCustomDialog(
                    visible = true,
                    onDismiss = { showNewTunnel = false },
                    title = "新建隧道"
                ) {
                    UfiDialogBody {
                        UfiDialogTextField(
                            label = "隧道名称",
                            value = newName,
                            onValueChange = { newName = it },
                            placeholder = "如 家宽 / home-nas"
                        )
                        UfiDialogPasswordField(
                            label = "Tunnel token",
                            value = newToken,
                            onValueChange = { newToken = it },
                            placeholder = "面板 Networks → Tunnels 里复制"
                        )
                        UfiDialogWarning(message = "token 等同于隧道凭据，请勿分享给他人。")
                        UfiDialogActions(
                            onDismiss = { showNewTunnel = false },
                            onConfirm = {
                                val name = newName.trim()
                                when {
                                    name.isBlank() ->
                                        toastMessage = ToastMessage("请输入隧道名称", ToastType.ERROR)
                                    name.contains(ILLEGAL_NAME_CHARS) || name.contains("..") ->
                                        toastMessage = ToastMessage("名称不能包含 \\ / : * ? \" < > | 或 ..", ToastType.ERROR)
                                    name.length > MAX_TUNNEL_NAME_LEN ->
                                        toastMessage = ToastMessage("名称不能超过 $MAX_TUNNEL_NAME_LEN 个字符", ToastType.ERROR)
                                    state.cfTunnelItems.any { it.name.equals(name, true) } ->
                                        toastMessage = ToastMessage("隧道 [$name] 已存在", ToastType.ERROR)
                                    newToken.isBlank() ->
                                        toastMessage = ToastMessage("请填写 token", ToastType.ERROR)
                                    else -> {
                                        viewModel.tunnel.saveCfTunnel(name, newToken.trim())
                                        showNewTunnel = false
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
