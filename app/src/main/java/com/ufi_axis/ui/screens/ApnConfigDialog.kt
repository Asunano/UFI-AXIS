package com.ufi_axis.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ufi_axis.ui.components.*
import com.ufi_axis.ui.components.common.*
import com.ufi_axis.ui.theme.*
import com.ufi_axis.util.DebugLog
import com.ufi_axis.viewmodel.MainViewModel
import com.google.gson.JsonObject
import kotlinx.coroutines.delay

/** APN 接入点配置弹窗 */
@Composable
fun ApnConfigDialog(
    viewModel: MainViewModel,
    visible: Boolean,
    onDismiss: () -> Unit
) {
    val palette = LocalResolvedPalette.current

    var apnData by remember { mutableStateOf<JsonObject?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var isAutoMode by remember { mutableStateOf(true) }
    var appliedAutoMode by remember { mutableStateOf(true) }
    var showEditDialog by remember { mutableStateOf(false) }
    var editIndex by remember { mutableIntStateOf(0) }
    var isNewProfile by remember { mutableStateOf(false) }

    var profileName by remember { mutableStateOf("") }; var apnVal by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }; var password by remember { mutableStateOf("") }
    var authType by remember { mutableStateOf("none") }; var pdpType by remember { mutableStateOf("IPv4v6") }
    var selectedProfileIdx by remember { mutableIntStateOf(0) }
    var toastMsg by remember { mutableStateOf<String?>(null) }

    fun reload() {
        isLoading = true; viewModel.network.loadApnConfig { result ->
            try {
                val obj = result?.asJsonObject; apnData = obj
                val mode = obj?.get("apn_mode")?.asString == "auto"
                isAutoMode = mode; appliedAutoMode = mode
                val curIdx = obj?.get("Current_index")?.asString?.toIntOrNull() ?: 0
                selectedProfileIdx = curIdx
            } catch (e: Exception) { DebugLog.w("ApnConfig", "Failed to parse APN config", e) }
            isLoading = false
        }
    }

    LaunchedEffect(visible) { if (visible) reload() }

    LaunchedEffect(isLoading) {
        if (isLoading) { delay(5_000L); if (isLoading) isLoading = false }
    }

    val hasPendingAutoChange = isAutoMode != appliedAutoMode

    UfiCustomDialog(
        visible = visible,
        onDismiss = onDismiss,
        title = "APN 接入点"
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.DialogPaddingH)
        ) {
            // 紧急恢复
            UfiSmallButton(
                text = "重置 Telephony (紧急恢复SIM)",
                onClick = {
                    isLoading = false
                    viewModel.network.resetTelephony { success, msg ->
                        toastMsg = msg
                        if (success) reload()
                    }
                }
            )

            toastMsg?.let { msg ->
                Spacer(Modifier.height(4.dp))
                Text(msg, style = MaterialTheme.typography.bodySmall,
                    color = if (msg.startsWith("重置失败")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            }

            Spacer(Modifier.height(8.dp))

            if (isLoading) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Spacer(Modifier.height(4.dp))
                Text("正在加载 APN 配置…", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                UfiSettingsToggle(
                    title = "自动模式",
                    checked = isAutoMode,
                    onCheckedChange = { isAutoMode = it }
                )

                if (hasPendingAutoChange) {
                    Spacer(Modifier.height(4.dp))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Warning, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.tertiary)
                        Spacer(Modifier.width(4.dp))
                        Text("模式已更改，点击保存生效", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.tertiary, modifier = Modifier.weight(1f))
                        UfiSmallButton(text = "应用", onClick = {
                            if (isAutoMode) {
                                viewModel.network.setApnConfig(mapOf("auto_select" to true))
                                appliedAutoMode = true
                            } else {
                                viewModel.network.switchApnProfile(selectedProfileIdx) { success ->
                                    if (success) { appliedAutoMode = false; reload() }
                                }
                            }
                        })
                    }
                }

                if (!appliedAutoMode || hasPendingAutoChange) {
                    Spacer(Modifier.height(12.dp))
                    val profiles = parseApnProfilesDlg(apnData)
                    if (profiles.isNotEmpty()) {
                        val profileOptions = profiles.mapIndexed { idx, p -> idx.toString() to "${p.name} (${p.apn})" }
                        Text("选择配置", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = palette.textPrimary)
                        Spacer(Modifier.height(4.dp))
                        UfiSingleChipSelector(
                            options = profileOptions, selectedValue = selectedProfileIdx.toString(),
                            onSelect = { selectedProfileIdx = it.toInt() }, wrapContent = true
                        )
                        Spacer(Modifier.height(12.dp))
                        UfiButtonRow {
                            UfiSmallButton(text = "应用所选", onClick = { viewModel.network.switchApnProfile(selectedProfileIdx) { reload() } })
                            UfiSmallButton(text = "编辑", onClick = {
                                val p = profiles[selectedProfileIdx]; profileName = p.name; apnVal = p.apn
                                username = p.username; password = p.password; authType = p.authType; pdpType = p.pdpType
                                editIndex = selectedProfileIdx; isNewProfile = false; showEditDialog = true
                            })
                            UfiSmallButton(text = "新增", onClick = {
                                profileName = ""; apnVal = ""; username = ""; password = ""
                                authType = "none"; pdpType = "IPv4v6"; editIndex = profiles.size; isNewProfile = true; showEditDialog = true
                            })
                        }
                    }
                }
            }
        }
    }

    // 编辑/新增 APN 弹窗
    if (showEditDialog) {
        AlertDialog(onDismissRequest = { showEditDialog = false },
            title = { Text(if (isNewProfile) "新增 APN" else "编辑 APN") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    UfiTextField(value = profileName, onValueChange = { profileName = it }, label = "名称")
                    UfiTextField(value = apnVal, onValueChange = { apnVal = it }, label = "APN")
                    UfiTextField(value = username, onValueChange = { username = it }, label = "用户名")
                    UfiTextField(value = password, onValueChange = { password = it }, label = "密码")
                }
            },
            confirmButton = { TextButton(onClick = {
                viewModel.network.setApnConfig(mapOf(
                    "index" to editIndex, "profile_name" to profileName,
                    "apn" to apnVal, "username" to username, "password" to password,
                    "auth_type" to authType, "pdp_type" to pdpType
                ))
                showEditDialog = false; reload()
            }) { Text("保存") } }
        )
    }
}

private data class ApnProfileDlg(
    val name: String, val apn: String, val username: String, val password: String,
    val authType: String, val pdpType: String
)

private fun parseApnProfilesDlg(obj: JsonObject?): List<ApnProfileDlg> {
    if (obj == null) return emptyList()
    return (0..19).mapNotNull { i ->
        val raw = obj.get("APN_config$i")?.asString ?: return@mapNotNull null
        if (raw.isBlank()) return@mapNotNull null
        val parts = raw.split("($)")
        if (parts.size < 2) return@mapNotNull null
        ApnProfileDlg(
            parts.getOrElse(0) { "" }, parts.getOrElse(1) { "" },
            parts.getOrElse(4) { "" }, parts.getOrElse(5) { "" },
            parts.getOrElse(3) { "none" }, parts.getOrElse(7) { "IPv4v6" }
        )
    }
}