package com.ufi_axis.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import com.ufi_axis.ui.components.*
import com.ufi_axis.ui.components.common.*
import com.ufi_axis.ui.theme.LocalResolvedPalette
import com.ufi_axis.ui.theme.Spacing
import com.ufi_axis.ui.theme.UfiTextStyles
import com.ufi_axis.viewmodel.MainViewModel
import com.ufi_axis.viewmodel.state.PairedDeviceItem

/**
 * 配对与访问：本机配对信息、已配对设备列表、配对数量限制、配对密码、解除全部配对。
 *
 * 配对密码是本页所有破坏性操作的门禁（配对确认、移除设备、解除全部配对都校验它），
 * 所以修改入口就放在这一页；移除设备弹窗里的密码框是**校验**它，不是修改。
 *
 * 视觉与交互一律走 `ui/components/common` 的公共组件（2026-09-10 收敛：卡内标题、
 * 信息格、设备行、分隔线、进度条、按钮、弹窗按钮此前都是本页手搓）。
 */
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
    var showChangePwdDialog by remember { mutableStateOf(false) }
    var pendingRenameFp by remember { mutableStateOf<String?>(null) }
    var pendingRenameName by remember { mutableStateOf("") }
    var pendingRemoveFp by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        viewModel.network.loadPairingStatus()
    }

    LaunchedEffect(pairingState) {
        if (!pairingState.isLoading) {
            pairingEnabled = pairingState.pairingEnabled
            maxDevices = pairingState.pairingMaxDevices
        }
    }

    UfiScreenScaffold(
        title = "配对与访问",
        navController = navController,
        showBack = true,
        showHeader = showHeader
    ) { padding ->
        // 入场动画统一收敛到 MainNavGraph 根节点（"app-launch"，只在冷启动播一次）。
        // 二级页本来就有 NavHost 的 detailEnter 平移转场，再叠一层淡入+上移是双重动画。
        UfiPageBackground(modifier = Modifier.padding(padding)) {
            pairingState.errorMessage?.let { err ->
                UfiErrorBanner(message = err, onRetry = { viewModel.network.loadPairingStatus() })
            }

            Column(
                Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(Spacing.SettingsCardGap)
            ) {
                // ═══════════ 本机信息卡（2×2 紧凑网格） ═══════════
                UfiSettingsRowCard(contentPadding = PaddingValues(Spacing.XLarge)) {
                    UfiGroupHeader("本机信息")
                    Column(verticalArrangement = Arrangement.spacedBy(Spacing.Large)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.Large)) {
                            UfiInfoCell(
                                modifier = Modifier.weight(1f),
                                label = "设备名",
                                value = pairingState.deviceName.ifBlank { pairingState.deviceId }.ifEmpty { "—" }
                            )
                            UfiInfoCell(
                                modifier = Modifier.weight(1f),
                                label = "设备标识",
                                value = pairingState.deviceId.ifEmpty { "—" }
                            )
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.Large)) {
                            UfiInfoCell(
                                modifier = Modifier.weight(1f),
                                label = "配对状态",
                                value = if (pairingState.paired) "已配对" else "未配对"
                            )
                            UfiInfoCell(
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
                    // 纵向留白交给行内 UfiSettingsItem（自带 10dp 上下），卡只给 8dp，合起来与其它设置卡同高
                    UfiSettingsRowCard(
                        contentPadding = PaddingValues(horizontal = Spacing.XLarge, vertical = Spacing.Medium)
                    ) {
                        UfiGroupHeader("已配对设备")
                        deviceItems.forEachIndexed { idx, item ->
                            if (idx > 0) UfiDivider()
                            UfiSettingsItem(
                                title = item.deviceName.ifBlank { item.fingerprint }
                                    .ifEmpty { "设备 #${idx + 1}" },
                                description = "${item.fingerprint} · ${formatEpochMillis(item.lastSeen)}",
                                icon = Icons.Default.Smartphone,
                                titleMaxLines = 1,
                                descriptionMaxLines = 1,
                                trailing = {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(Spacing.Small)
                                    ) {
                                        UfiButton(
                                            text = "编辑",
                                            onClick = {
                                                pendingRenameFp = item.fingerprint
                                                pendingRenameName =
                                                    item.deviceName.ifBlank { item.fingerprint }
                                            },
                                            variant = UfiButtonVariant.Subtle,
                                            size = UfiButtonSize.Small
                                        )
                                        UfiButton(
                                            text = "移除",
                                            onClick = { pendingRemoveFp = item.fingerprint },
                                            variant = UfiButtonVariant.Danger,
                                            size = UfiButtonSize.Small
                                        )
                                    }
                                }
                            )
                        }
                    }
                }

                // ═══════════ 配对限制配置 ═══════════
                UfiSettingsRowCard(
                    contentPadding = PaddingValues(horizontal = Spacing.XLarge, vertical = Spacing.Small)
                ) {
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
                        UfiDivider()
                        // remember(maxDevices)：远端回填新的上限后输入框要跟着复位
                        var inputText by remember(maxDevices) { mutableStateOf(maxDevices.toString()) }
                        UfiSettingsItem(
                            title = "最大配对设备数",
                            description = "0 表示不限制数量",
                            trailing = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    // 数字过滤交给 UfiDigitField；fillMaxWidth = false 保住行内窄宽
                                    UfiDigitField(
                                        value = inputText,
                                        onValueChange = { inputText = it },
                                        label = "数量",
                                        modifier = Modifier.width(Spacing.InlineDigitFieldWidth),
                                        fillMaxWidth = false
                                    )
                                    Spacer(Modifier.width(Spacing.Medium))
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
                        )

                        if (maxDevices > 0 && pairingState.pairedCount > 0) {
                            UfiDivider()
                            val quotaFull = pairingState.pairedCount >= maxDevices
                            Column(modifier = Modifier.padding(vertical = Spacing.Large)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        "已用配额",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = palette.textSecondary
                                    )
                                    Text(
                                        "${pairingState.pairedCount} / $maxDevices",
                                        style = UfiTextStyles.noteEmphasis,
                                        color = if (quotaFull) palette.error else palette.textPrimary
                                    )
                                }
                                Spacer(Modifier.height(Spacing.Small))
                                UfiCompactProgressBar(
                                    progress = pairingState.pairedCount.toFloat() / maxDevices,
                                    color = if (quotaFull) palette.error else palette.accent,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }
                }

                // ═══════════ 配对密码 ═══════════
                UfiSettingsRowCard {
                    UfiSettingsItem(
                        title = "配对密码",
                        description = "客户端连接本服务时验证的密码",
                        trailing = {
                            UfiButton(
                                text = "修改",
                                onClick = { showChangePwdDialog = true },
                                variant = UfiButtonVariant.Subtle,
                                size = UfiButtonSize.Small
                            )
                        }
                    )
                }

                // ═══════════ 解除全部配对 ═══════════
                if (pairingState.paired) {
                    UfiSettingsRowCard(contentPadding = PaddingValues(Spacing.XLarge)) {
                        UfiButton(
                            text = "解除全部配对",
                            onClick = { showUnpairDialog = true },
                            variant = UfiButtonVariant.Danger,
                            icon = Icons.Default.Delete
                        )
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

    // ─ 移除单个设备（阶段3：需配对密码，DELETE /api/pairing/devices/{fp}） ──
    // 按钮走 actions 槽位而不是塞进 body：塞进 body 会让底部按钮参与内容滚动，
    // 且 footer 高度预算算不准（见 UfiScrollableDialog 的 FIX-24）。
    pendingRemoveFp?.let { fp ->
        var pwd by remember(fp) { mutableStateOf("") }
        UfiScrollableDialog(
            visible = true,
            onDismiss = { pendingRemoveFp = null },
            title = "移除设备",
            icon = rememberVectorPainter(Icons.Filled.Link),
            showCloseButton = false,
            actions = {
                UfiDialogActions(
                    onDismiss = { pendingRemoveFp = null },
                    onConfirm = {
                        viewModel.network.removeDevice(fp, pwd)
                        pendingRemoveFp = null
                    },
                    confirmText = "移除",
                    confirmDestructive = true,
                    enabled = pwd.isNotBlank()
                )
            }
        ) {
            UfiDialogBody {
                Text(
                    "移除后该客户端需要重新配对才能连接。请输入配对密码确认。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = palette.textSecondary
                )
                UfiDialogPasswordField(
                    label = "配对密码",
                    value = pwd,
                    onValueChange = { pwd = it }
                )
            }
        }
    }

    // ─ 修改配对密码（POST pairing/change-password，root 路径） ──
    // 与「移除设备」同一套写法：按钮走 actions 槽位，三个密码框走公共的弹窗密码字段。
    if (showChangePwdDialog) {
        var oldPw by remember { mutableStateOf("") }
        var newPw by remember { mutableStateOf("") }
        var confirmNewPw by remember { mutableStateOf("") }
        val mismatch = confirmNewPw.isNotEmpty() && confirmNewPw != newPw
        UfiScrollableDialog(
            visible = true,
            onDismiss = { showChangePwdDialog = false },
            title = "修改配对密码",
            icon = rememberVectorPainter(Icons.Filled.VpnKey),
            showCloseButton = false,
            actions = {
                UfiDialogActions(
                    onDismiss = { showChangePwdDialog = false },
                    onConfirm = {
                        viewModel.network.changeDevicePassword(oldPw, newPw)
                        showChangePwdDialog = false
                    },
                    confirmText = "确认修改",
                    enabled = oldPw.isNotBlank() && newPw.length in 4..64 && newPw == confirmNewPw
                )
            }
        ) {
            UfiDialogBody {
                Text(
                    "修改后旧密码立即失效，已配对的客户端下次验证需使用新密码。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = palette.textSecondary
                )
                UfiDialogPasswordField(
                    label = "旧密码",
                    value = oldPw,
                    onValueChange = { oldPw = it }
                )
                UfiDialogPasswordField(
                    label = "新密码（4-64 位）",
                    value = newPw,
                    onValueChange = { newPw = it }
                )
                UfiDialogPasswordField(
                    label = "确认新密码",
                    value = confirmNewPw,
                    onValueChange = { confirmNewPw = it }
                )
                // UfiDialogPasswordField 不带错误态槽位，两次不一致的提示单独一行给出
                if (mismatch) {
                    Text(
                        "两次输入的新密码不一致",
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.error
                    )
                }
            }
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
