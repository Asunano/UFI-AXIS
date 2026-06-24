package com.ufi_axis.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

import androidx.navigation.NavHostController
import com.ufi_axis.ui.animation.blurEntrance
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
        UfiPageBackground(modifier = Modifier.blurEntrance("tools").padding(padding), useGradient = true) {
            state.errorMessage?.let { error -> UfiErrorBanner(message = error) }

            // ═══════════ 1. NAS 文件入口 (核心入口, 大图标) ═══════════
            UfiSectionHeader(title = "文件与下载")
            UfiSettingsGroup {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    UfiActionCard(
                        modifier = Modifier.weight(1f),
                        title = "文件管理",
                        description = "浏览/复制/移动/上传",
                        icon = Icons.Default.FolderOpen,
                        onClick = { navController.navigate("detail/files") }
                    )
                    UfiActionCard(
                        modifier = Modifier.weight(1f),
                        title = "下载管理",
                        description = "远程下载/aria2",
                        icon = Icons.Default.CloudDownload,
                        onClick = { navController.navigate("detail/downloads") }
                    )
                }
            }

            // ═══════════ 2. 通信工具 ═══════════
            UfiSectionHeader(title = "通信工具")

            // 高级控制台入口（AT+Shell）
            UfiSettingsGroup {
                UfiNavigationItem(
                    title = "高级控制台",
                    description = "AT 指令 · Shell 命令",
                    onClick = { navController.navigate("detail/tools-advanced") }
                )
            }

            // ═══════════ 3. 管理与自动化 ═══════════
            UfiSectionHeader(title = "管理与自动化")
            UfiSettingsGroup {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    UfiGridCard(
                        modifier = Modifier.weight(1f), title = "应用管理",
                        icon = Icons.Default.Apps, description = "安装/卸载",
                        onClick = { navController.navigate("detail/apps") }
                    )
                    UfiGridCard(
                        modifier = Modifier.weight(1f), title = "ADB WiFi",
                        icon = Icons.Default.DeveloperMode, description = "无线调试",
                        onClick = { navController.navigate("detail/adb") }
                    )
                    UfiGridCard(
                        modifier = Modifier.weight(1f), title = "高级工具",
                        icon = Icons.Default.Terminal, description = "TTYD/iPerf3",
                        onClick = { navController.navigate("detail/advanced") }
                    )
                }
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    UfiGridCard(
                        modifier = Modifier.weight(1f), title = "定时任务",
                        icon = Icons.Default.Schedule, description = "脚本调度",
                        onClick = { navController.navigate("detail/tasks") }
                    )
                    UfiGridCard(
                        modifier = Modifier.weight(1f), title = "短信转发",
                        icon = Icons.Default.ForwardToInbox, description = "邮件转发",
                        onClick = { navController.navigate("detail/sms-forward") }
                    )
                    UfiGridCard(
                        modifier = Modifier.weight(1f), title = "短信",
                        icon = Icons.Default.Sms, description = "收发短信",
                        onClick = { navController.navigate("detail/sms") }
                    )
                }
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    UfiGridCard(
                        modifier = Modifier.weight(1f), title = "流量管理",
                        icon = Icons.Default.DataUsage, description = "限额与统计",
                        onClick = { navController.navigate("detail/traffic-management") }
                    )
                }
            }

            UfiLoadingBox(isLoading = state.isLoading) {}
            Spacer(Modifier.height(Spacing.Large))
        }
    }
}



