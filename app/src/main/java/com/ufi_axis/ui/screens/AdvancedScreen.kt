package com.ufi_axis.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
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
        viewModel.advanced.loadAdvancedStatus()
    }

    UfiScreenScaffold(title = "高级工具", navController = navController, showBack = true) { padding ->
        PullToRefreshBox(
            isRefreshing = state.isLoading,
            onRefresh = { viewModel.advanced.loadAdvancedStatus() },
            modifier = Modifier.padding(padding).fillMaxSize()
        ) {
        UfiPageBackground {
            state.errorMessage?.let { error ->
                UfiErrorBanner(message = error, onRetry = { viewModel.advanced.clearMessage() })
            }
            state.operationMessage?.let { msg ->
                UfiResultCard(text = msg)
            }

            // ===== 守护进程开关 =====
            UfiSectionHeader(title = "守护进程")
            UfiSettingsGroup {
                UfiSettingsToggle(
                    title = "TTYD Web 终端",
                    description = if (state.ttydRunning) "运行中 · 端口 1146" else "已停止",
                    checked = state.ttydRunning,
                    onCheckedChange = { viewModel.advanced.toggleTtyd(it) }
                )
                UfiDivider()
                UfiSettingsToggle(
                    title = "iperf3 服务端",
                    description = if (state.iperf3Running) "运行中" else "已停止",
                    checked = state.iperf3Running,
                    onCheckedChange = { viewModel.advanced.toggleIperf3(it) }
                )
            }

            // ===== CPU 核心管理 =====
            UfiSectionHeader(title = "CPU 核心管理")
            UfiSettingsGroup {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    UfiPrimaryButton(
                        text = "开启小核",
                        onClick = { viewModel.advanced.setCpuCores(true) },
                        modifier = Modifier.weight(1f)
                    )
                    UfiSecondaryButton(
                        text = "关闭小核",
                        onClick = { viewModel.advanced.setCpuCores(false) },
                        modifier = Modifier.weight(1f)
                    )
                }
                LaunchedEffect(Unit) { viewModel.advanced.loadCpuCores() }
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

            // ===== 带宽限制 =====
            UfiSectionHeader(title = "带宽限制")
            UfiSettingsGroup {
                LaunchedEffect(Unit) { viewModel.advanced.loadBandwidthLimit() }

                if (state.bandwidthEnabled && state.bandwidthMbit > 0) {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text(
                                "当前限制: ${state.bandwidthMbit} Mbit/s",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }

                var mbitValue by remember { mutableStateOf("") }
                var mbitError by remember { mutableStateOf<String?>(null) }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = mbitValue,
                        onValueChange = {
                            mbitValue = it
                            mbitError = if (it.isNotBlank() && (it.toIntOrNull() == null || it.toInt() <= 0)) "请输入正整数" else null
                        },
                        label = { Text("Mbit/s") },
                        keyboardOptions = KeyboardOptions.Default.copy(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                        isError = mbitError != null,
                        supportingText = if (mbitError != null) {{ Text(mbitError!!, color = MaterialTheme.colorScheme.error) }} else null,
                        singleLine = true
                    )
                    UfiPrimaryButton(
                        text = "应用",
                        onClick = {
                            if (mbitValue.isNotBlank() && mbitError == null) {
                                viewModel.advanced.setBandwidthLimit(mbitValue)
                            }
                        },
                        enabled = mbitValue.isNotBlank() && mbitError == null
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "通过 tc HTB qdisc 对 br0 网桥设置全局带宽上限",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (state.bandwidthEnabled) {
                    Spacer(Modifier.height(8.dp))
                    UfiDivider()
                    Spacer(Modifier.height(8.dp))
                    UfiSecondaryButton(
                        text = "解除带宽限制",
                        onClick = { viewModel.advanced.removeBandwidthLimit() },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            // ===== FOTA 管理 =====
            UfiSectionHeader(title = "固件更新管理")
            UfiSettingsGroup {
                LaunchedEffect(Unit) { viewModel.advanced.loadFotaStatus() }
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
                UfiPrimaryButton(text = "一键禁用 FOTA", onClick = { viewModel.advanced.disableFota() })
            }

            // ===== 系统优化 =====
            UfiSectionHeader(title = "系统优化")
            UfiSettingsGroup {
                UfiNavigationItem(
                    title = "移除 ZTE 流量整形",
                    description = "删除固件内置的 iptables 限速规则和 tc 队列，解除设备对网络吞吐的人为限制。重启后需重新执行。",
                    onClick = { viewModel.advanced.netAccelerate() }
                )
                UfiDivider()
                UfiNavigationItem(
                    title = "禁用后台进程回收",
                    description = "关闭 Android 系统的 Phantom Process Killer，防止 Ktor 服务、TTYD、iperf3 等后台进程被系统自动杀死。",
                    onClick = { viewModel.advanced.disablePhantomKiller() }
                )
            }

            Spacer(Modifier.height(Spacing.Large))
        }
        }
    }
}
