package com.ufi_axis.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.ufi_axis.ui.components.common.*
import com.ufi_axis.ui.theme.LocalResolvedPalette
import com.ufi_axis.ui.theme.Spacing
import com.ufi_axis.util.AppPreferences
import com.ufi_axis.viewmodel.MainViewModel

@Composable
fun ServerConfigScreen(
    viewModel: MainViewModel,
    onServerConfigChanged: () -> Unit,
    navController: NavHostController
) {
    val context = LocalContext.current
    val prefs = remember { AppPreferences(context) }
    val palette = LocalResolvedPalette.current

    // ── QoS: load backend config on enter ──
    val qosState by viewModel.qosConfigState.collectAsState()
    var qosEverLoaded by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { viewModel.tools.loadQosConfig() }

    // Local editing defaults (synced from backend on first load)
    var qosEnabled by remember { mutableStateOf(true) }
    var qosShellMax by remember { mutableStateOf(3) }
    var qosCacheTtl by remember { mutableStateOf(2000) }
    var qosGoformQuery by remember { mutableStateOf(4) }
    var qosGoformSet by remember { mutableStateOf(2) }

    LaunchedEffect(qosState.config) {
        val cfg = qosState.config
        if (cfg != null && !qosEverLoaded) {
            qosEverLoaded = true
            qosEnabled = cfg.enabled
            qosShellMax = cfg.shellMaxConcurrent
            qosCacheTtl = cfg.cacheTtlMs
            qosGoformQuery = cfg.goformQueryMax
            qosGoformSet = cfg.goformSetMax
        }
    }

    val qosSaveMsg = remember { mutableStateOf<String?>(null) }

    UfiScreenScaffold(title = "服务器配置", navController = navController, showBack = true) { padding ->
        UfiPageBackground(modifier = Modifier.padding(padding)) {

            // ═══════════ 后端连接 ═══════════
            UfiSectionHeader(title = "后端连接")
            UfiSettingsGroup {
                var ip by remember { mutableStateOf(prefs.serverIp) }
                var port by remember { mutableStateOf(prefs.serverPort.toString()) }
                var token by remember { mutableStateOf(prefs.token) }
                UfiTextField(value = ip, onValueChange = { ip = it }, label = "Core IP")
                UfiFieldRow {
                    UfiDigitField(value = port, onValueChange = { port = it }, label = "端口",
                        modifier = Modifier.weight(1f))
                    UfiTextField(value = token, onValueChange = { token = it }, label = "Token",
                        modifier = Modifier.weight(1.5f))
                }
                Spacer(Modifier.height(10.dp))
                UfiPrimaryButton(text = "保存连接", onClick = {
                    prefs.serverIp = ip
                    prefs.serverPort = port.toIntOrNull() ?: 8088
                    prefs.token = token
                    onServerConfigChanged()
                })
            }

            // ═══════════ 网关配置 ═══════════
            UfiSectionHeader(title = "网关配置")
            UfiSettingsGroup {
                var gwAddress by remember { mutableStateOf("${prefs.gatewayIp}:${prefs.goformPort}") }
                var gwMsg by remember { mutableStateOf<String?>(null) }
                UfiSectionGroupTitle("网关配置", "Web 管理 API 地址")
                UfiTextField(value = gwAddress, onValueChange = { gwAddress = it; gwMsg = null },
                    label = "网关地址", placeholder = "192.168.0.1:8080")
                Spacer(Modifier.height(10.dp))
                UfiPrimaryButton(text = "同步到后端", onClick = {
                    val parts = gwAddress.split(":")
                    val ip = parts.getOrElse(0) { "192.168.0.1" }.ifBlank { "192.168.0.1" }
                    val port = parts.getOrNull(1)?.toIntOrNull() ?: 8080
                    prefs.gatewayIp = ip; prefs.goformPort = port
                    viewModel.tools.syncGatewayConfig(ip, prefs.goformPassword, port)
                    gwMsg = "已同步 $ip:$port"
                })
                gwMsg?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary)
                }
            }

            // ═══════════ 管理密码 ═══════════
            UfiSectionHeader(title = "管理密码")
            UfiSettingsGroup {
                var oldPwd by remember { mutableStateOf("") }
                var newPwd by remember { mutableStateOf("") }
                var confirmPwd by remember { mutableStateOf("") }
                var pwdError by remember { mutableStateOf<String?>(null) }

                UfiSectionGroupTitle("管理密码", "修改路由器 Web 管理密码")
                UfiPasswordField(value = oldPwd, onValueChange = { oldPwd = it; pwdError = null },
                    label = "当前密码", showToggle = false)
                Spacer(Modifier.height(Spacing.Small))
                UfiPasswordField(value = newPwd, onValueChange = { newPwd = it; pwdError = null },
                    label = "新密码", showToggle = false)
                Spacer(Modifier.height(Spacing.Small))
                UfiPasswordField(value = confirmPwd, onValueChange = { confirmPwd = it; pwdError = null },
                    label = "确认新密码",
                    isError = confirmPwd.isNotEmpty() && confirmPwd != newPwd,
                    errorMessage = if (confirmPwd.isNotEmpty() && confirmPwd != newPwd) "密码不一致" else null)
                Spacer(Modifier.height(Spacing.Small))
                UfiPrimaryButton(text = "修改密码", onClick = {
                    when {
                        newPwd != confirmPwd -> pwdError = "两次密码不一致"
                        oldPwd.isEmpty() || newPwd.isEmpty() -> pwdError = "密码不能为空"
                        else -> {
                            viewModel.network.changePassword(oldPwd, newPwd)
                            prefs.goformPassword = newPwd
                            pwdError = "已提交修改"; oldPwd = ""; newPwd = ""; confirmPwd = ""
                        }
                    }
                })
                pwdError?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall,
                        color = if (it.contains("不一致") || it.contains("为空"))
                            MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                }
            }

            // ═══════════ QoS 性能控制 ═══════════
            UfiSectionHeader(title = "QoS 性能控制")
            UfiSettingsGroup {
                UfiSectionGroupTitle("并发与缓存", "控制后端对设备的数据采集并发数")

                // Enable / Disable
                UfiSettingsToggle(
                    title = "启用 QoS",
                    description = "关闭后请求不限制并发，高负载时设备可能超时",
                    checked = qosEnabled,
                    onCheckedChange = { qosEnabled = it; qosSaveMsg.value = null }
                )

                Spacer(Modifier.height(Spacing.Medium))

                // Shell max concurrent
                UfiSlider(
                    value = qosShellMax.toFloat(),
                    onValueChange = { qosShellMax = it.toInt(); qosSaveMsg.value = null },
                    valueRange = 1f..10f,
                    steps = 8,
                    label = "Shell 最大并发",
                    valueLabel = qosShellMax.toString(),
                    enabled = qosEnabled
                )

                Spacer(Modifier.height(Spacing.Medium))

                // Cache TTL
                var cacheTtlText by remember(qosCacheTtl) { mutableStateOf(qosCacheTtl.toString()) }
                UfiDigitField(
                    value = cacheTtlText,
                    onValueChange = { raw ->
                        cacheTtlText = raw
                        val v = raw.toIntOrNull()
                        if (v != null && v in 500..30000) { qosCacheTtl = v; qosSaveMsg.value = null }
                    },
                    label = "缓存有效时间 (ms)",
                    modifier = Modifier.fillMaxWidth()
                )
                Text("范围 500-30000ms，缓存数据在此时间内复用",
                    style = MaterialTheme.typography.bodySmall, color = palette.textSecondary)

                Spacer(Modifier.height(Spacing.Medium))

                // Goform query max
                UfiSlider(
                    value = qosGoformQuery.toFloat(),
                    onValueChange = { qosGoformQuery = it.toInt(); qosSaveMsg.value = null },
                    valueRange = 1f..8f,
                    steps = 6,
                    label = "Goform 查询并发",
                    valueLabel = qosGoformQuery.toString(),
                    enabled = qosEnabled
                )

                Spacer(Modifier.height(Spacing.Medium))

                // Goform set max
                UfiSlider(
                    value = qosGoformSet.toFloat(),
                    onValueChange = { qosGoformSet = it.toInt(); qosSaveMsg.value = null },
                    valueRange = 1f..4f,
                    steps = 2,
                    label = "Goform 写入并发",
                    valueLabel = qosGoformSet.toString(),
                    enabled = qosEnabled
                )

                // Error from backend
                qosState.errorMessage?.let {
                    Spacer(Modifier.height(Spacing.Small))
                    Text(it, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error)
                }

                // Save feedback
                qosSaveMsg.value?.let {
                    Spacer(Modifier.height(Spacing.Small))
                    Text(it, style = MaterialTheme.typography.bodySmall,
                        color = palette.accent)
                }

                Spacer(Modifier.height(10.dp))
                UfiPrimaryButton(
                    text = if (qosState.isSaving) "保存中..." else "保存 QoS 配置",
                    onClick = {
                        qosSaveMsg.value = null
                        viewModel.tools.saveQosConfig(
                            enabled = qosEnabled,
                            shellMax = qosShellMax,
                            cacheTtl = qosCacheTtl,
                            goformQuery = qosGoformQuery,
                            goformSet = qosGoformSet
                        )
                        qosSaveMsg.value = "已保存"
                    }
                )
            }

            Spacer(Modifier.height(Spacing.Large))
        }
    }
}
