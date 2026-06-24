package com.ufi_axis.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.ufi_axis.ui.components.*
import com.ufi_axis.ui.components.common.*
import com.ufi_axis.ui.theme.Spacing
import com.ufi_axis.viewmodel.MainViewModel

/**
 * 高级控制台 — 合并 AT 指令 + Shell 命令两个强力工具
 * 从 ToolsScreen 提取，降低工具首页信息密度
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdvancedConsoleScreen(viewModel: MainViewModel, navController: NavHostController) {
    val state by viewModel.toolsState.collectAsState()

    UfiScreenScaffold(
        title = "高级控制台",
        navController = navController,
        showBack = true
    ) { padding ->
        UfiPageBackground(modifier = Modifier.padding(padding)) {
            state.errorMessage?.let { error -> UfiErrorBanner(message = error) }

            // ═══════════ 1. AT 指令 ═══════════
            UfiSectionHeader(title = "AT 指令")
            UfiSettingsGroup {
                UfiSectionGroupTitle("发送 AT 命令到基带",
                    if (state.atResponse.isNotBlank())
                        state.atResponse.take(80).replace("\n", " ")
                    else "查询设备信息、网络状态、频段等")

                var command by remember { mutableStateOf("") }
                UfiInputWithAction(
                    value = command, onValueChange = { command = it },
                    label = "指令", placeholder = "ATI",
                    actionText = "发送",
                    onAction = { if (command.isNotBlank()) viewModel.tools.sendAtCommand(command) },
                    enabled = !state.isLoading, actionEnabled = !state.isLoading
                )
                if (state.atResponse.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    UfiCodeBlock(text = state.atResponse)
                }
                if (state.atHistory.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text("历史记录", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(4.dp))
                    state.atHistory.take(8).forEach { entry -> UfiHistoryItem(text = entry) }
                }
            }

            // ═══════════ 2. Shell 命令 ═══════════
            UfiSectionHeader(title = "Shell 命令")
            UfiSettingsGroup {
                UfiSectionGroupTitle("执行 Linux 命令",
                    if (state.shellResponse.isNotBlank())
                        state.shellResponse.take(80).replace("\n", " ")
                    else "直接操作底层系统")

                var command by remember { mutableStateOf("") }
                var isRoot by remember { mutableStateOf(true) }

                UfiInputWithAction(
                    value = command, onValueChange = { command = it },
                    label = "命令", placeholder = "ls -la /data",
                    actionText = "执行", onAction = {
                        if (command.isNotBlank()) viewModel.tools.executeShell(command, isRoot)
                    },
                    enabled = !state.isLoading, actionEnabled = !state.isLoading && command.isNotBlank()
                )
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = isRoot, onCheckedChange = { isRoot = it })
                    Spacer(Modifier.width(4.dp))
                    Text("Root 权限", style = MaterialTheme.typography.bodySmall)
                }
                if (state.shellResponse.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    UfiCodeBlock(text = state.shellResponse.take(2000))
                }
                if (state.shellHistory.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text("历史", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(4.dp))
                    state.shellHistory.take(8).forEach { entry ->
                        UfiHistoryItem(text = entry.take(500))
                    }
                }
            }

            UfiLoadingBox(isLoading = state.isLoading) {}
            Spacer(Modifier.height(Spacing.Large))
        }
    }
}
