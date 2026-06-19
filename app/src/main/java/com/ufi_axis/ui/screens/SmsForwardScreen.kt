package com.ufi_axis.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.ufi_axis.data.model.SmsForwardConfig
import com.ufi_axis.ui.components.*
import com.ufi_axis.ui.components.common.*
import com.ufi_axis.ui.theme.Spacing
import com.ufi_axis.viewmodel.MainViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SmsForwardScreen(viewModel: MainViewModel, navController: NavHostController) {
    val state by viewModel.smsForwardState.collectAsState()
    LaunchedEffect(Unit) { viewModel.loadSmsForwardConfig() }

    val cfg = state.config
    var enabled by remember(cfg) { mutableStateOf(cfg?.enabled ?: false) }
    var method by remember(cfg) { mutableStateOf(cfg?.method ?: "disabled") }
    var smtpHost by remember(cfg) { mutableStateOf(cfg?.smtp_host ?: "") }
    var smtpPort by remember(cfg) { mutableStateOf((cfg?.smtp_port ?: 465).toString()) }
    var smtpUser by remember(cfg) { mutableStateOf(cfg?.smtp_user ?: "") }
    var smtpPass by remember(cfg) { mutableStateOf("") }
    var smtpFrom by remember(cfg) { mutableStateOf(cfg?.smtp_from ?: "") }
    var smtpTo by remember(cfg) { mutableStateOf(cfg?.smtp_to ?: "") }
    var curlUrl by remember(cfg) { mutableStateOf(cfg?.curl_url ?: "") }
    var curlTemplate by remember(cfg) { mutableStateOf(cfg?.curl_template ?: "") }
    var dingtalkToken by remember(cfg) { mutableStateOf(cfg?.dingtalk_token ?: "") }
    var dingtalkSecret by remember(cfg) { mutableStateOf("") }
    var forwardDevInfo by remember(cfg) { mutableStateOf(cfg?.forward_dev_info ?: false) }
    val blacklist = remember(cfg) { mutableStateListOf<String>().apply { addAll(cfg?.blacklist ?: emptyList()) } }
    var newBlacklistItem by remember { mutableStateOf("") }
    var saveAttempted by remember { mutableStateOf(false) }

    // 保存成功检测：attempted 且不在 loading 且无错误 → 成功
    val saveSucceeded = saveAttempted && !state.isLoading && state.errorMessage == null

    UfiScreenScaffold(title = "短信转发", navController = navController, showBack = true) { padding ->
        UfiScrollableColumn(modifier = Modifier.padding(padding)) {
            state.errorMessage?.let { err -> UfiErrorBanner(message = err) }

            // 总开关
            UfiSettingsGroup {
                SettingsToggle(
                    title = "启用短信转发",
                    description = if (enabled) "已开启自动转发新短信" else "已关闭",
                    checked = enabled,
                    onCheckedChange = { enabled = it }
                )
            }

            if (enabled) {
                UfiSettingsGroup {
                    UfiSectionHeader(title = "转发方式")
                    UfiSingleChipSelector(
                        options = listOf("smtp" to "SMTP 邮件", "curl" to "CURL 回调", "dingtalk" to "钉钉机器人"),
                        selectedValue = if (method == "disabled") "smtp" else method,
                        onSelect = { method = it }
                    )
                }

                if (method == "smtp" || method == "disabled") {
                    UfiSettingsGroup {
                        UfiSectionHeader(title = "SMTP 配置")
                        UfiTextField(value = smtpHost, onValueChange = { smtpHost = it; saveAttempted = false }, label = "SMTP 服务器")
                        UfiFieldRow {
                            UfiDigitField(value = smtpPort, onValueChange = { smtpPort = it; saveAttempted = false }, label = "端口", modifier = Modifier.weight(1f))
                            UfiTextField(value = smtpUser, onValueChange = { smtpUser = it; saveAttempted = false }, label = "用户名", modifier = Modifier.weight(1.5f))
                        }
                        UfiPasswordField(value = smtpPass, onValueChange = { smtpPass = it; saveAttempted = false },
                            label = if (cfg?.smtp_pass_set == true) "密码 (已设置，留空不修改)" else "密码")
                        UfiTextField(value = smtpFrom, onValueChange = { smtpFrom = it; saveAttempted = false }, label = "发件地址 (留空用用户名)")
                        UfiTextField(value = smtpTo, onValueChange = { smtpTo = it; saveAttempted = false }, label = "收件地址")
                    }
                }

                if (method == "curl") {
                    UfiSettingsGroup {
                        UfiSectionHeader(title = "CURL 配置")
                        UfiTextField(value = curlUrl, onValueChange = { curlUrl = it; saveAttempted = false }, label = "回调 URL")
                        UfiTextField(value = curlTemplate, onValueChange = { curlTemplate = it; saveAttempted = false }, label = "请求模板", singleLine = false)
                        Spacer(Modifier.height(Spacing.Small))
                        Text("可用占位符: {{sms-from}} {{sms-body}} {{sms-time}} {{device-info}}",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                if (method == "dingtalk") {
                    UfiSettingsGroup {
                        UfiSectionHeader(title = "钉钉配置")
                        UfiTextField(value = dingtalkToken, onValueChange = { dingtalkToken = it; saveAttempted = false }, label = "Access Token")
                        UfiPasswordField(value = dingtalkSecret, onValueChange = { dingtalkSecret = it; saveAttempted = false },
                            label = if (cfg?.dingtalk_secret_set == true) "签名密钥 (已设置，留空不修改)" else "签名密钥 (可选)")
                    }
                }

                // 设备信息开关
                UfiSettingsGroup {
                    SettingsToggle(
                        title = "附加设备信息",
                        description = "转发内容中附加电池、CPU、内存等状态",
                        checked = forwardDevInfo,
                        onCheckedChange = { forwardDevInfo = it }
                    )
                }

                // 黑名单管理
                UfiSettingsGroup {
                    UfiSectionHeader(title = "黑名单")
                    Text("包含以下号码或关键词的短信不会被转发",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(Spacing.Small))
                    blacklist.forEachIndexed { index, item ->
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(item, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            IconButton(onClick = {
                                blacklist.removeAt(index)
                                saveAttempted = false
                            }) {
                                Icon(Icons.Default.Delete, "删除", tint = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        UfiTextField(
                            value = newBlacklistItem,
                            onValueChange = { newBlacklistItem = it },
                            label = "添加号码或关键词",
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.width(8.dp))
                        IconButton(onClick = {
                            if (newBlacklistItem.isNotBlank()) {
                                blacklist.add(newBlacklistItem.trim())
                                newBlacklistItem = ""
                                saveAttempted = false
                            }
                        }) {
                            Icon(Icons.Default.Add, "添加")
                        }
                    }
                }
            }

            if (saveSucceeded) {
                Text("配置已保存", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(Spacing.Small))
            }

            UfiButtonRow {
                UfiPrimaryButton(text = if (state.isLoading) "保存中..." else "保存配置",
                    modifier = Modifier.weight(1f),
                    enabled = !state.isLoading,
                    onClick = {
                    val actualMethod = if (enabled) (if (method == "disabled") "smtp" else method) else "disabled"
                    saveAttempted = true
                    viewModel.saveSmsForwardConfig(SmsForwardConfig(
                        enabled = enabled,
                        method = actualMethod,
                        smtp_host = smtpHost, smtp_port = smtpPort.toIntOrNull() ?: 465,
                        smtp_user = smtpUser, smtp_pass = smtpPass,
                        smtp_from = smtpFrom, smtp_to = smtpTo,
                        curl_url = curlUrl, curl_template = curlTemplate,
                        dingtalk_token = dingtalkToken, dingtalk_secret = dingtalkSecret,
                        forward_dev_info = forwardDevInfo,
                        blacklist = blacklist
                    ))
                })
                if (enabled && method != "disabled") {
                    UfiSecondaryButton(text = "测试发送", modifier = Modifier.weight(1f), onClick = {
                        viewModel.testSmsForward()
                    })
                }
            }

            Spacer(Modifier.height(Spacing.Large))
        }
    }
}
