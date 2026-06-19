package com.ufi_axis.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.google.gson.JsonObject
import com.ufi_axis.ui.components.*
import com.ufi_axis.ui.components.common.*
import com.ufi_axis.ui.theme.Spacing
import com.ufi_axis.viewmodel.MainViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdvancedScreen(viewModel: MainViewModel, navController: NavHostController) {
    val state by viewModel.advancedState.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.loadAdvancedStatus()
    }

    UfiScreenScaffold(title = "高级工具", navController = navController, showBack = true) { padding ->
        UfiScrollableColumn(modifier = Modifier.padding(padding)) {
            state.errorMessage?.let { error ->
                UfiErrorBanner(message = error, onRetry = { viewModel.clearAdvancedMessage() })
            }
            state.operationMessage?.let { msg ->
                UfiResultCard(text = msg)
            }

            // ===== 守护进程开关 =====
            UfiSectionHeader(title = "守护进程")
            UfiSettingsGroup {
                SettingsToggle(
                    title = "TTYD Web 终端",
                    description = if (state.ttydRunning) "运行中 · 端口 1146" else "已停止",
                    checked = state.ttydRunning,
                    onCheckedChange = { viewModel.toggleTtyd(it) }
                )
                UfiDivider()
                SettingsToggle(
                    title = "iperf3 服务端",
                    description = if (state.iperf3Running) "运行中" else "已停止",
                    checked = state.iperf3Running,
                    onCheckedChange = { viewModel.toggleIperf3(it) }
                )
            }

            // ===== CPU 核心管理 =====
            UfiSectionHeader(title = "CPU 核心管理")
            UfiSettingsGroup {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    UfiPrimaryButton(
                        text = "开启小核",
                        onClick = { viewModel.setCpuCores(true) },
                        modifier = Modifier.weight(1f)
                    )
                    UfiSecondaryButton(
                        text = "关闭小核",
                        onClick = { viewModel.setCpuCores(false) },
                        modifier = Modifier.weight(1f)
                    )
                }
                LaunchedEffect(Unit) { viewModel.loadCpuCores() }
                state.cpuCores?.let { cores ->
                    val coresArr = (cores as? JsonObject)?.getAsJsonArray("cores")
                    coresArr?.forEach { elem ->
                        val core = elem.asJsonObject
                        val id = core.get("core")?.asInt ?: 0
                        val online = core.get("online")?.asBoolean ?: false
                        val freq = core.get("frequency")?.asLong ?: 0
                        val freqMhz = freq / 1000
                        Spacer(Modifier.height(2.dp))
                        Text(
                            "CPU$id: ${if (online) "在线 ${freqMhz}MHz" else "离线"}",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (online) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // ===== FOTA 管理 =====
            UfiSectionHeader(title = "固件更新管理")
            UfiSettingsGroup {
                LaunchedEffect(Unit) { viewModel.loadFotaStatus() }
                state.fotaStatus?.let { fota ->
                    val packages = (fota as? JsonObject)?.getAsJsonObject("packages")
                    packages?.entrySet()?.forEach { (pkg, status) ->
                        Text(
                            "$pkg: ${status.asString}",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (status.asString == "removed") MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.error
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                UfiPrimaryButton(text = "一键禁用 FOTA", onClick = { viewModel.disableFota() })
            }

            // ===== 系统优化 =====
            UfiSectionHeader(title = "系统优化")
            UfiSettingsGroup {
                UfiNavigationItem(
                    title = "移除 ZTE 流量整形",
                    description = "删除固件内置的 iptables 限速规则和 tc 队列，解除设备对网络吞吐的人为限制。重启后需重新执行。",
                    onClick = { viewModel.netAccelerate() }
                )
                UfiDivider()
                UfiNavigationItem(
                    title = "禁用后台进程回收",
                    description = "关闭 Android 系统的 Phantom Process Killer，防止 Ktor 服务、TTYD、iperf3 等后台进程被系统自动杀死。",
                    onClick = { viewModel.disablePhantomKiller() }
                )
            }

            Spacer(Modifier.height(Spacing.Large))
        }
    }
}
