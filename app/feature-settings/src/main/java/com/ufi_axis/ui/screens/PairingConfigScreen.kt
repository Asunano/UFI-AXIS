package com.ufi_axis.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.ufi_axis.ui.components.*
import com.ufi_axis.ui.components.common.*
import com.ufi_axis.ui.theme.LocalResolvedPalette
import com.ufi_axis.ui.theme.Spacing
import com.ufi_axis.ui.theme.UfiCardDefaults
import com.ufi_axis.ui.theme.UfiTextStyles
import com.ufi_axis.ui.theme.ufiCardShadow
import com.ufi_axis.viewmodel.MainViewModel
import com.ufi_axis.viewmodel.state.PairedDeviceItem

@Composable
fun PairingConfigScreen(
    viewModel: MainViewModel,
    navController: NavHostController,
    showHeader: Boolean = true  // 2026-08-11：被外层 ServerScreen Tab 嵌套调用时传 false 避免多重标题栏
) {
    val palette = LocalResolvedPalette.current
    val pairingState by viewModel.network.pairingState.collectAsState()

    var pairingEnabled by remember { mutableStateOf(false) }
    var maxDevices by remember { mutableIntStateOf(0) }
    var showUnpairDialog by remember { mutableStateOf(false) }
    var pendingRenameFp by remember { mutableStateOf<String?>(null) }
    var pendingRenameName by remember { mutableStateOf("") }
    var pendingRemoveFp by remember { mutableStateOf<String?>(null) }
    var showChangePwdDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.network.loadPairingStatus()
    }

    LaunchedEffect(pairingState) {
        if (!pairingState.isLoading) {
            pairingEnabled = pairingState.pairingEnabled
            maxDevices = pairingState.pairingMaxDevices
        }
    }

    UfiScreenScaffold(title = "配对管理", navController = navController, showBack = true, showHeader = showHeader) { padding ->
        // 入场动画统一收敛到 MainNavGraph 根节点（"app-launch"，只在冷启动播一次）。
        // 二级页本来就有 NavHost 的 detailEnter 平移转场，再叠一层淡入+上移是双重动画。
        UfiPageBackground(modifier = Modifier.padding(padding)) {
            pairingState.errorMessage?.let { err ->
                UfiErrorBanner(message = err, onRetry = { viewModel.network.loadPairingStatus() })
            }

            Column(
                Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // ═══════════ 本机信息卡（2×2 紧凑网格） ═══════════
                // 卡容器统一走公共 UfiSettingsRowCard（2026-08-30：原来本页手搓 rowModifier 四链）
                UfiSettingsRowCard(contentPadding = PaddingValues(16.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            "本机信息",
                            style = UfiTextStyles.cardTitle,
                            color = palette.accent
                        )
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            PairInfoCell(
                                modifier = Modifier.weight(1f),
                                label = "设备名",
                                value = pairingState.deviceName.ifBlank { pairingState.deviceId }.ifEmpty { "—" }
                            )
                            PairInfoCell(
                                modifier = Modifier.weight(1f),
                                label = "设备标识",
                                value = pairingState.deviceId.ifEmpty { "—" }
                            )
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            PairInfoCell(
                                modifier = Modifier.weight(1f),
                                label = "配对状态",
                                value = if (pairingState.paired) "已配对" else "未配对"
                            )
                            PairInfoCell(
                                modifier = Modifier.weight(1f),
                                label = "已配对设备",
                                value = "${pairingState.pairedCount} 台"
                            )
                        }
                    }
                }

                // ═══════════ 已配对设备列表 ═══════════
                val deviceItems = if (pairingState.devices.isNotEmpty()) pairingState.devices
                else pairingState.pairedFingerprints.map { PairedDeviceItem(it, it, 0L, 0L) }
                if (deviceItems.isNotEmpty()) {
                    UfiSettingsRowCard(contentPadding = PaddingValues(16.dp)) {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(
                                "已配对设备",
                                style = UfiTextStyles.cardTitle,
                                color = palette.accent
                            )
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                deviceItems.forEachIndexed { idx, item ->
                                    if (idx > 0) HorizontalDivider(
                                        color = palette.divider.copy(alpha = 0.3f),
                                        modifier = Modifier.padding(vertical = 4.dp)
                                    )
                                    PairedDeviceRow(
                                        item = item,
                                        index = idx,
                                        onRename = {
                                            pendingRenameFp = item.fingerprint
                                            pendingRenameName = item.deviceName.ifBlank { item.fingerprint }
                                        },
                                        onRemove = { pendingRemoveFp = item.fingerprint }
                                    )
                                }
                            }
                        }
                    }
                }

                // ═══════════ 配对限制配置 ═══════════
                UfiSettingsRowCard(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)) {
                    Column {
                        UfiSettingsToggle(
                            title = "启用配对数量限制",
                            description = if (pairingEnabled) "开启后将限制可配对设备数量" else "关闭后允许任意数量设备配对",
                            checked = pairingEnabled,
                            onCheckedChange = {
                                pairingEnabled = it
                                viewModel.network.updatePairingConfig(it, maxDevices)
                            }
                        )

                        if (pairingEnabled) {
                            HorizontalDivider(color = palette.divider.copy(alpha = 0.3f))
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                                    Text("最大配对设备数", style = UfiTextStyles.listItemTitle, color = palette.textPrimary)
                                    Text("0 表示不限制数量", style = UfiTextStyles.note, color = palette.textSecondary)
                                }
                                var inputText by remember(maxDevices) { mutableStateOf(maxDevices.toString()) }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    // 2026-08-31：裸 OutlinedTextField → 公共 UfiDigitField
                                    //（数字过滤交给组件；fillMaxWidth = false 保住 90dp 行内窄宽）
                                    UfiDigitField(
                                        value = inputText,
                                        onValueChange = { inputText = it },
                                        label = "数量",
                                        modifier = Modifier.width(90.dp),
                                        fillMaxWidth = false
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    UfiButton(
                                        size = UfiButtonSize.Small,
                                        text = "保存",
                                        onClick = {
                                            inputText.toIntOrNull()?.let {
                                                maxDevices = it
                                                viewModel.network.updatePairingConfig(pairingEnabled, it)
                                            }
                                        }
                                    )
                                }
                            }

                            if (maxDevices > 0 && pairingState.pairedCount > 0) {
                                HorizontalDivider(color = palette.divider.copy(alpha = 0.3f))
                                Column(modifier = Modifier.padding(vertical = 12.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text("已用配额", style = MaterialTheme.typography.bodySmall, color = palette.textSecondary)
                                        Text(
                                            "${pairingState.pairedCount} / $maxDevices",
                                            style = UfiTextStyles.noteEmphasis,
                                            color = if (pairingState.pairedCount >= maxDevices) palette.error else palette.textPrimary
                                        )
                                    }
                                    Spacer(Modifier.height(6.dp))
                                    val pct = (pairingState.pairedCount.toFloat() / maxDevices * 100).coerceIn(0f, 100f)
                                    LinearProgressIndicator(
                                        progress = { pct / 100f },
                                        modifier = Modifier.fillMaxWidth().height(5.dp),
                                        color = if (pairingState.pairedCount >= maxDevices) palette.error else palette.accent,
                                        trackColor = palette.divider.copy(alpha = 0.4f)
                                    )
                                }
                            }
                        }
                    }
                }

                // ═══════════ 修改设备密码 ═══════════
                UfiSettingsRowCard(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)) {
                    UfiSettingsItem(
                        title = "修改设备密码",
                        description = "修改后旧密码失效，设备管理需使用新密码",
                        trailing = {
                            TextButton(onClick = { showChangePwdDialog = true }) { Text("修改") }
                        }
                    )
                }

                // ═══════════ 解除全部配对 ═══════════
                if (pairingState.paired) {
                    UfiSettingsRowCard(contentPadding = PaddingValues(16.dp)) {
                        OutlinedButton(
                            onClick = { showUnpairDialog = true },
                            modifier = Modifier.fillMaxWidth().height(44.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = palette.error)
                        ) {
                            Icon(Icons.Default.Delete, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("解除全部配对")
                        }
                    }
                }

                Spacer(Modifier.height(Spacing.Large))
            }
        }
    }

    // ─ 重命名设备（阶段3：PATCH /api/pairing/devices/{fp}） ──
    pendingRenameFp?.let { fp ->
        UfiInputDialog(
            title = "重命名设备",
            initialValue = pendingRenameName,
            hint = "输入新的设备名（1-32 字符）",
            confirmText = "保存",
            onConfirm = { name ->
                if (name.isNotBlank()) viewModel.network.renameDevice(fp, name)
                pendingRenameFp = null
            },
            onDismiss = { pendingRenameFp = null }
        )
    }

    // ─ 移除单个设备（阶段3：需设备密码，DELETE /api/pairing/devices/{fp}） ──
    pendingRemoveFp?.let { fp ->
        var pwd by remember(fp) { mutableStateOf("") }
        UfiCustomDialog(
            visible = true,
            onDismiss = { pendingRemoveFp = null },
            title = "移除设备",
            icon = rememberVectorPainter(Icons.Filled.Link),
            showCloseButton = false,
            dismissButton = {
                OutlinedButton(onClick = { pendingRemoveFp = null }) { Text("取消") }
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.network.removeDevice(fp, pwd)
                        pendingRemoveFp = null
                    },
                    enabled = pwd.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = palette.error,
                        contentColor = palette.onAccent
                    )
                ) { Text("移除") }
            }
        ) {
            Text(
                "移除后该客户端需要重新配对才能连接。请输入设备密码确认。",
                style = MaterialTheme.typography.bodyMedium,
                color = palette.textSecondary
            )
            Spacer(Modifier.height(Spacing.Medium))
            UfiPasswordField(value = pwd, onValueChange = { pwd = it }, label = "设备密码")
        }
    }

    // ─ 修改设备密码（阶段3：POST pairing/change-password，root 路径） ──
    if (showChangePwdDialog) {
        var oldPw by remember { mutableStateOf("") }
        var newPw by remember { mutableStateOf("") }
        var confirmNewPw by remember { mutableStateOf("") }
        UfiCustomDialog(
            visible = true,
            onDismiss = { showChangePwdDialog = false },
            title = "修改设备密码",
            icon = rememberVectorPainter(Icons.Filled.VpnKey),
            showCloseButton = false,
            dismissButton = {
                OutlinedButton(onClick = { showChangePwdDialog = false }) { Text("取消") }
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.network.changeDevicePassword(oldPw, newPw)
                        showChangePwdDialog = false
                    },
                    enabled = oldPw.isNotBlank() && newPw.length in 4..64 && newPw == confirmNewPw
                ) { Text("确认修改") }
            }
        ) {
            UfiPasswordField(value = oldPw, onValueChange = { oldPw = it }, label = "旧密码")
            Spacer(Modifier.height(Spacing.Medium))
            UfiPasswordField(value = newPw, onValueChange = { newPw = it }, label = "新密码（4-64 位）")
            Spacer(Modifier.height(Spacing.Medium))
            UfiPasswordField(
                value = confirmNewPw,
                onValueChange = { confirmNewPw = it },
                label = "确认新密码",
                isError = confirmNewPw.isNotEmpty() && confirmNewPw != newPw,
                errorMessage = if (confirmNewPw.isNotEmpty() && confirmNewPw != newPw) "两次输入的新密码不一致" else null
            )
        }
    }

    // ── 解除全部配对确认 ──
    if (showUnpairDialog) {
        UfiConfirmDialog(
            title = "解除全部配对",
            text = "确定解除全部 ${pairingState.pairedCount} 台设备的配对？解除后所有客户端需要重新配对。",
            confirmText = "确定解除",
            dismissText = "取消",
            destructive = true,
            onConfirm = {
                viewModel.network.unpairAll()
                showUnpairDialog = false
            },
            onDismiss = { showUnpairDialog = false }
        )
    }
}

/**
 * 配对信息单元格：标签 + 值垂直排列，用于 2×2 紧凑网格。
 */
@Composable
private fun PairInfoCell(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    val palette = LocalResolvedPalette.current
    Column(modifier = modifier) {
        Text(
            text = label,
            style = UfiTextStyles.note,
            color = palette.textSecondary
        )
        Text(
            text = value,
            style = UfiTextStyles.bodyEmphasis,
            color = palette.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * 已配对设备单行：左侧头像/图标 + 主副标题，右侧编辑/删除文字按钮。
 */
@Composable
private fun PairedDeviceRow(
    item: PairedDeviceItem,
    index: Int,
    onRename: () -> Unit,
    onRemove: () -> Unit
) {
    val palette = LocalResolvedPalette.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Surface(
            modifier = Modifier.size(40.dp),
            shape = UfiCardDefaults.shape,
            color = palette.accent.copy(alpha = 0.12f)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Default.Smartphone,
                    contentDescription = null,
                    tint = palette.accent,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.deviceName.ifBlank { item.fingerprint }.ifEmpty { "设备 #${index + 1}" },
                style = UfiTextStyles.listItemTitle,
                color = palette.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "${item.fingerprint} · ${formatEpochMillis(item.lastSeen)}",
                style = UfiTextStyles.note,
                color = palette.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(
                onClick = onRename,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
            ) { Text("编辑", color = palette.textSecondary) }
            TextButton(
                onClick = onRemove,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
            ) { Text("移除", color = palette.error) }
        }
    }
}

/** 格式化 epoch millis 为 "yyyy-MM-dd HH:mm"（本地时区）；非法/0 返回 "—"。 */
private fun formatEpochMillis(ms: Long): String {
    if (ms <= 0L) return "—"
    return try {
        java.time.Instant.ofEpochMilli(ms)
            .atZone(java.time.ZoneId.systemDefault())
            .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
    } catch (e: Exception) {
        "—"
    }
}
