package com.ufi_axis.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.ufi_axis.ui.components.*
import com.ufi_axis.ui.components.common.*
import com.ufi_axis.ui.theme.Spacing
import com.ufi_axis.util.AppPreferences
import com.ufi_axis.viewmodel.MainViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolsScreen(viewModel: MainViewModel, navController: NavHostController) {
    val state by viewModel.toolsState.collectAsState()

    UfiScreenScaffold(title = "工具") { padding ->
        UfiScrollableColumn(modifier = Modifier.padding(padding)) {
            state.errorMessage?.let { error -> UfiErrorBanner(message = error) }

            UfiSectionHeader(title = "通信工具")

            UfiSettingsGroup {
                Text("AT 指令", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                var command by remember { mutableStateOf("") }
                UfiInputWithAction(value = command, onValueChange = { command = it },
                    label = "指令", placeholder = "ATI",
                    actionText = "发送", onAction = { if (command.isNotBlank()) viewModel.sendAtCommand(command) },
                    enabled = !state.isLoading, actionEnabled = !state.isLoading)
                if (state.atResponse.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    UfiCodeBlock(text = state.atResponse)
                }
                if (state.atHistory.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text("历史记录", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(4.dp))
                    state.atHistory.take(10).forEach { entry ->
                        UfiHistoryItem(text = entry)
                    }
                }
            }

            UfiSettingsGroup {
                Text("USSD", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                var code by remember { mutableStateOf("") }
                UfiInputWithAction(value = code, onValueChange = { code = it },
                    label = "代码", placeholder = "*100#",
                    actionText = "发送", onAction = { if (code.isNotBlank()) viewModel.sendUssd(code) },
                    enabled = !state.isLoading, actionEnabled = !state.isLoading)
                if (state.ussdResponse.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    UfiResultCard(text = state.ussdResponse)
                }
            }

            val speedTestState by viewModel.speedTestState.collectAsState()
            UfiSettingsGroup {
                Text("网速测试", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                UfiPrimaryButton(text = if (speedTestState.isRunning) "测试中..." else "开始测速",
                    onClick = { viewModel.runSpeedTest() },
                    enabled = !speedTestState.isRunning, loading = speedTestState.isRunning)
                speedTestState.result?.let { result ->
                    Spacer(Modifier.height(4.dp))
                    UfiResultCard(text = result)
                }
                speedTestState.errorMessage?.let { err ->
                    Spacer(Modifier.height(4.dp))
                    Text(err, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }

            UfiSectionHeader(title = "系统工具")
            ShellExecSection()

            UfiSectionHeader(title = "管理")
            UfiSettingsGroup {
                UfiNavigationItem(title = "应用管理", description = "安装、卸载、冻结应用", onClick = { navController.navigate("detail/apps") })
                UfiDivider()
                UfiNavigationItem(title = "ADB WiFi", description = "无线调试连接", onClick = { navController.navigate("detail/adb") })
                UfiDivider()
                UfiNavigationItem(title = "定时任务", description = "自动化脚本调度", onClick = { navController.navigate("detail/tasks") })
                UfiDivider()
                UfiNavigationItem(title = "短信转发", description = "SMTP 邮件转发", onClick = { navController.navigate("detail/sms-forward") })
                UfiDivider()
                UfiNavigationItem(title = "短信", description = "查看和发送短信", onClick = { navController.navigate("detail/sms") })
                UfiDivider()
                UfiNavigationItem(title = "文件管理", description = "浏览、复制、移动文件", onClick = { navController.navigate("detail/files") })
                UfiDivider()
                UfiNavigationItem(title = "下载管理", description = "URL 下载、断点续传、任务管理", onClick = { navController.navigate("detail/downloads") })
                UfiDivider()
                UfiNavigationItem(title = "高级工具", description = "CPU核心、Boot提取、TTYD、iperf3等", onClick = { navController.navigate("detail/advanced") })
            }

            UfiLoadingBox(isLoading = state.isLoading) {}
            Spacer(Modifier.height(Spacing.Large))
        }
    }
}

@Composable
private fun ShellExecSection() {
    var command by remember { mutableStateOf("") }
    var output by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var isRoot by remember { mutableStateOf(true) }
    var history by remember { mutableStateOf(listOf<String>()) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    UfiSettingsGroup {
        Text("Shell 命令", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        UfiInputWithAction(value = command, onValueChange = { command = it },
            label = "命令", placeholder = "ls -la /data",
            actionText = "执行", onAction = {
                if (command.isBlank()) return@UfiInputWithAction
                isLoading = true
                scope.launch {
                    try {
                        val prefs = AppPreferences(context)
                        val base = "http://${prefs.serverIp}:${prefs.serverPort}"
                        val json = """{"command":"${command.replace("\"", "\\\"")}","as_root":$isRoot,"timeout":30}"""
                        val conn = java.net.URL("$base/api/shell/exec").openConnection() as java.net.HttpURLConnection
                        conn.requestMethod = "POST"
                        conn.setRequestProperty("Authorization", "Bearer ${prefs.token}")
                        conn.setRequestProperty("Content-Type", "application/json")
                        conn.doOutput = true; conn.connectTimeout = 30000; conn.readTimeout = 30000
                        conn.outputStream.write(json.toByteArray())
                        output = java.io.BufferedReader(java.io.InputStreamReader(conn.inputStream)).readText()
                        history = listOf("> $command\n$output") + history
                    } catch (e: Exception) { output = "Error: ${e.message}" }
                    isLoading = false
                }
            },
            enabled = !isLoading, actionEnabled = !isLoading && command.isNotBlank())
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = isRoot, onCheckedChange = { isRoot = it })
            Spacer(Modifier.width(4.dp))
            Text("Root 权限", style = MaterialTheme.typography.bodySmall)
        }
        if (output.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            UfiCodeBlock(text = output.take(2000))
        }
        if (history.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text("历史", style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.height(4.dp))
            history.take(5).forEach { entry ->
                UfiHistoryItem(text = entry.take(500))
            }
        }
    }
}