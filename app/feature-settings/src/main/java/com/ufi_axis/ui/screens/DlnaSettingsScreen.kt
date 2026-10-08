package com.ufi_axis.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.ufi_axis.ui.components.common.*
import com.ufi_axis.viewmodel.MainViewModel

/**
 * DLNA 投屏（设置 → DLNA 投屏，2026-10-09）。
 *
 * 开关门控（用户明确要求）：**共享目录未配置时开关置灰**，描述行提示先选目录。
 * 开关与目录都在本页完成 —— 开关打开的前提就是目录已配置，不做成两步导航。
 *
 * 目录选择复用媒体的 [com.ufi_axis.ui.media.MediaScanDirsDialog] 交互（多选目录，
 * 取数走 `viewModel.media.browseDirs`，即 `/api/files/list`），但语义不同：
 * 这里选的是**对外共享**的目录（DLNA 播放器可见），与媒体中心的三类扫描目录是两份配置。
 */
@Composable
fun DlnaSettingsScreen(viewModel: MainViewModel, navController: NavHostController) {
    val state by viewModel.dlna.state.collectAsState()
    val status = state.status

    var showDirsDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { viewModel.dlna.load() }

    UfiScreenScaffold(title = "DLNA 投屏", navController = navController, showBack = true) { padding ->
        UfiPageBackground(modifier = Modifier.padding(padding)) {
            Column(
                Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // ── 开关行 ──
                UfiSettingsRowCard {
                    UfiSettingsItem(
                        icon = Icons.Default.Cast,
                        title = "DLNA Media Server",
                        description = when {
                            !status.ready -> "请先选择共享目录"
                            status.running -> "运行中 · 局域网播放器可发现「UFI-AXIS Media」"
                            status.enabled -> "已开启，等待服务启动"
                            else -> "关闭"
                        },
                        trailing = {
                            // 目录未配置（ready=false）→ 置灰；点击行为改为先去选目录。
                            UfiSwitch(
                                checked = status.enabled && status.ready,
                                enabled = state.loaded && !state.saving,
                                onCheckedChange = { want ->
                                    if (want && !status.ready) {
                                        showDirsDialog = true
                                    } else {
                                        viewModel.dlna.setEnabled(want)
                                    }
                                }
                            )
                        }
                    )
                }

                // ── 共享目录行 ──
                UfiSettingsRowCard {
                    UfiSettingsItem(
                        icon = Icons.Default.Folder,
                        title = "共享目录",
                        description = if (status.dirs.isEmpty()) {
                            "未选择 · DLNA 需要至少一个目录"
                        } else {
                            status.dirs.joinToString("\n") { it }
                        },
                        onClick = { showDirsDialog = true },
                        trailing = { UfiSettingsChevron() }
                    )
                }

                state.errorMessage?.let {
                    UfiSettingsRowCard {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))

                UfiSettingsRowCard {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                        Text(
                            "同一 WiFi 下的电视 / 播放器（BubbleUPnP、nPlayer 等）在设备列表中选择「UFI-AXIS Media」即可浏览播放共享目录内的视频、音乐与图片。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))
            }
        }
    }

    if (showDirsDialog) {
        // 直调公共件而不是媒体的 MediaScanDirsDialog：后者是 internal、且语义是"媒体库扫描范围"
        // （空选择 = 整个媒体库）。DLNA 共享目录空选择没有意义 —— 至少要一个目录才开得了。
        UfiDirectoryPickerDialog(
            visible = true,
            title = "DLNA 共享目录",
        // root 用 /storage 而不是 UFI_DEVICE_STORAGE_ROOT：外接 SD 卡挂载在 /storage/XXXX-XXXX，
        // 与 emulated/0 平级 —— 起点写死内部存储就永远看不到外接卷（2026-10-09 实机反馈）。
        root = "/storage",
            initialSelection = status.dirs,
            multiSelect = true,
            confirmText = "保存",
            emptySelectionHint = "未选择目录 · DLNA 至少需要一个共享目录",
            browse = { path -> viewModel.media.browseDirs(path) },
            onDismiss = { showDirsDialog = false },
            onConfirm = { dirs ->
                showDirsDialog = false
                viewModel.dlna.setDirs(dirs)
            }
        )
    }
}
