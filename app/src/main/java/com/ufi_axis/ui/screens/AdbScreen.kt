package com.ufi_axis.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.ufi_axis.ui.components.*
import com.ufi_axis.ui.components.common.*
import com.ufi_axis.ui.theme.Spacing
import com.ufi_axis.viewmodel.MainViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdbScreen(viewModel: MainViewModel, navController: NavHostController) {
    val state by viewModel.adbState.collectAsState()
    LaunchedEffect(Unit) { viewModel.refreshAdbStatus() }

    UfiScreenScaffold(title = "ADB WiFi", navController = navController, showBack = true) { padding ->
        UfiScrollableColumn(modifier = Modifier.padding(padding)) {
            state.errorMessage?.let { err -> UfiErrorBanner(message = err, onRetry = { viewModel.refreshAdbStatus() }) }

            state.status?.let { s ->
                UfiSettingsGroup {
                    UfiSectionHeader(title = "ADB 状态")
                    InfoRow("运行状态", if (s.connected) "运行中" else "已停止")
                    InfoRow("端口", s.port.toString())
                    if (s.last_ping_ms > 0) {
                        val ago = (System.currentTimeMillis() - s.last_ping_ms) / 1000
                        InfoRow("最后保活", "${ago}秒前")
                    }
                }

                UfiButtonRow {
                    UfiPrimaryButton(text = "启动 ADB", onClick = { viewModel.startAdb() },
                        enabled = !s.connected && !state.isLoading, modifier = Modifier.weight(1f))
                    UfiSecondaryButton(text = "停止 ADB", onClick = { viewModel.stopAdb() },
                        enabled = s.connected && !state.isLoading, modifier = Modifier.weight(1f))
                }

                if (s.connected) {
                    Card(
                        shape = RoundedCornerShape(Spacing.CardCorner),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text("连接信息", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(4.dp))
                            Text("adb connect <设备IP>:${s.port}", style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer)
                            Spacer(Modifier.height(4.dp))
                            Text("通过 ADB WiFi 可远程管理设备", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f))
                        }
                    }
                }
            }

            UfiLinearLoading(isLoading = state.isLoading)
        }
    }
}