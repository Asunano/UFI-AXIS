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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.ufi_axis.ui.components.*
import com.ufi_axis.ui.components.common.*
import com.ufi_axis.ui.theme.*
import com.ufi_axis.viewmodel.MainViewModel

/** 网速测试独立页面 — 支持内网/外网测速及可配置时长 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpeedTestScreen(viewModel: MainViewModel, navController: NavHostController) {
    val speedTestState by viewModel.speedTestState.collectAsState()
    val palette = LocalResolvedPalette.current

    var selectedTab by remember { mutableIntStateOf(0) }
    // 预设时长选项: 5s, 10s, 15s, 30s, 60s
    val durationOptions = listOf(5, 10, 15, 30, 60)
    var selectedDuration by remember { mutableIntStateOf(10) }
    var customUrl by remember { mutableStateOf("https://speedtest.tele2.net/100MB.zip") }
    var showUrlEdit by remember { mutableStateOf(false) }

    val isRunning = speedTestState.isRunning
    val result = speedTestState.result
    val error = speedTestState.errorMessage

    UfiScreenScaffold(
        title = "网速测试",
        navController = navController,
        showBack = true
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            // ═══════════ 测速类型切换 ═══════════
            UfiSectionHeader(title = "测速类型")
            UfiSettingsGroup {
                PrimaryTabRow(
                    selectedTabIndex = selectedTab,
                    containerColor = Color.Transparent,
                    contentColor = MaterialTheme.colorScheme.primary
                ) {
                    Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 },
                        text = { Text("内网测速") })
                    Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 },
                        text = { Text("外网测速") })
                }
            }

            Spacer(Modifier.height(Spacing.Medium))

            // ═══════════ 时长配置 ═══════════
            UfiSectionHeader(title = "测速时长")
            UfiSettingsGroup {
                UfiSectionGroupTitle(
                    "最大测速时长",
                    "${selectedDuration} 秒"
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    durationOptions.forEach { dur ->
                        val isSelected = dur == selectedDuration
                        FilterChip(
                            selected = isSelected,
                            onClick = { if (!isRunning) selectedDuration = dur },
                            label = { Text("${dur}s") },
                            shape = UfiCardDefaults.inputShape,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                // 自定义时长滑块
                Spacer(Modifier.height(12.dp))
                var customSec by remember(selectedDuration) {
                    mutableStateOf(selectedDuration.toFloat())
                }
                Text("自定义: ${customSec.toInt()} 秒",
                    style = MaterialTheme.typography.labelMedium,
                    color = palette.textSecondary)
                Slider(
                    value = customSec,
                    onValueChange = { if (!isRunning) { customSec = it; selectedDuration = it.toInt().coerceAtLeast(1) } },
                    valueRange = 1f..120f,
                    steps = 0,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // ═══════════ 外网测速 URL ═══════════
            if (selectedTab == 1) {
                Spacer(Modifier.height(Spacing.Medium))
                UfiSectionHeader(title = "测试 URL")
                UfiSettingsGroup {
                    UfiSectionGroupTitle(
                        "目标地址",
                        if (showUrlEdit) "输入下载测速的 URL" else customUrl.take(50)
                    )
                    if (showUrlEdit) {
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = customUrl,
                            onValueChange = { customUrl = it },
                            label = { Text("URL") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            TextButton(onClick = { showUrlEdit = false }) { Text("取消") }
                            Spacer(Modifier.width(8.dp))
                            TextButton(onClick = { showUrlEdit = false }) { Text("确认") }
                        }
                    } else {
                        Spacer(Modifier.height(4.dp))
                        TextButton(onClick = { if (!isRunning) showUrlEdit = true }) {
                            Icon(Icons.Default.Edit, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("修改 URL")
                        }
                    }
                }
            }

            Spacer(Modifier.height(Spacing.Medium))

            // ═══════════ 开始测速 ═══════════
            UfiSettingsGroup {
                val desc = when {
                    isRunning && speedTestState.testType == "internal" -> "内网测速中… (最长 ${selectedDuration}s)"
                    isRunning && speedTestState.testType == "external" -> "外网测速中… (最长 ${selectedDuration}s)"
                    selectedTab == 0 -> "从设备下载数据，测试内网吞吐量"
                    else -> "下载外部页面，测试实际上网带宽"
                }
                UfiSectionGroupTitle(
                    if (selectedTab == 0) "内网测速" else "外网测速",
                    desc
                )

                Spacer(Modifier.height(8.dp))

                UfiPrimaryButton(
                    text = when {
                        isRunning && speedTestState.testType == "internal" -> "内网测速中…"
                        isRunning && speedTestState.testType == "external" -> "外网测速中…"
                        selectedTab == 0 -> "开始内网测速 (${selectedDuration}s)"
                        else -> "开始外网测速 (${selectedDuration}s)"
                    },
                    onClick = {
                        if (selectedTab == 0) {
                            viewModel.network.runSpeedTest(selectedDuration)
                        } else {
                            viewModel.network.runExternalSpeedTest(customUrl, selectedDuration)
                        }
                    },
                    enabled = !isRunning,
                    loading = isRunning
                )

                // 测速进度
                if (isRunning) {
                    Spacer(Modifier.height(12.dp))
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }

                // 结果展示
                result?.let { r ->
                    Spacer(Modifier.height(12.dp))
                    UfiResultCard(text = r)
                }
                error?.let { err ->
                    Spacer(Modifier.height(8.dp))
                    Text(
                        err,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }

            Spacer(Modifier.height(Spacing.Large))
        }
    }
}
