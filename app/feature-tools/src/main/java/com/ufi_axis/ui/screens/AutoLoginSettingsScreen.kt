package com.ufi_axis.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Sms
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.ufi_axis.data.model.CarrierBillSettingsState
import com.ufi_axis.ui.components.common.ToastMessage
import com.ufi_axis.ui.components.common.ToastType
import com.ufi_axis.ui.components.common.UfiBadge
import com.ufi_axis.ui.components.common.UfiBadgeType
import com.ufi_axis.ui.components.common.UfiDivider
import com.ufi_axis.ui.components.common.UfiGroupHeader
import com.ufi_axis.ui.components.common.UfiInputDialog
import com.ufi_axis.ui.components.common.UfiListErrorState
import com.ufi_axis.ui.components.common.UfiLoadingIndicator
import com.ufi_axis.ui.components.common.UfiNoticeCard
import com.ufi_axis.ui.components.common.UfiNoticeSeverity
import com.ufi_axis.ui.components.common.UfiPageBackground
import com.ufi_axis.ui.components.common.UfiScreenScaffold
import com.ufi_axis.ui.components.common.UfiSettingsChevron
import com.ufi_axis.ui.components.common.UfiSettingsGroup
import com.ufi_axis.ui.components.common.UfiSettingsItem
import com.ufi_axis.ui.components.common.UfiSettingsToggle
import com.ufi_axis.ui.components.common.UfiSettingsValue
import com.ufi_axis.ui.components.common.UfiToastHost
import com.ufi_axis.ui.navigation.Routes
import com.ufi_axis.ui.navigation.ufiNavigateOnce
import com.ufi_axis.ui.theme.Spacing
import com.ufi_axis.viewmodel.MainViewModel
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 自动登录独立设置页（2026-11-13）。
 *
 * 从「账单设置」的自动登录行进入；不再使用 hero 卡，全部是标准设置行：
 * 总开关 + 今日短信 / 适用条件 / 参与账号 / 自动登录策略（三个数值行）。
 */
@Composable
fun AutoLoginSettingsScreen(
    viewModel: MainViewModel,
    navController: NavHostController,
) {
    val remote by viewModel.tools.billSettings.collectAsState()
    var draft by remember(remote) { mutableStateOf(remote) }
    var loading by remember { mutableStateOf(remote == null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var toast by remember { mutableStateOf<ToastMessage?>(null) }
    var editField by remember { mutableStateOf<BillSettingField?>(null) }
    var editInitial by remember { mutableStateOf("") }

    fun reload() {
        loading = remote == null
        loadError = null
        viewModel.tools.loadBillSettings { result ->
            loading = false
            result.onFailure {
                val message = billSettingsErrorMessage(viewModel, it, "读取自动登录设置失败")
                loadError = message
                toast = ToastMessage(message, ToastType.ERROR)
            }
        }
    }

    fun refreshSilently() {
        viewModel.tools.loadBillSettings { result ->
            loading = false
            result.onSuccess { loadError = null }
                .onFailure {
                    loadError = billSettingsErrorMessage(viewModel, it, "同步失败")
                }
        }
    }

    fun commit(next: CarrierBillSettingsState, patch: JsonObject) {
        val previous = draft
        draft = next
        busy = true
        viewModel.tools.saveBillSettings(patch) { result ->
            busy = false
            result.onFailure {
                draft = previous
                toast = ToastMessage(
                    billSettingsErrorMessage(viewModel, it, "保存失败"),
                    ToastType.ERROR,
                )
            }
        }
    }

    LaunchedEffect(Unit) { reload() }
    LaunchedEffect(Unit) {
        while (true) {
            delay(5_000)
            if (!busy && editField == null) refreshSilently()
        }
    }

    UfiScreenScaffold(
        title = "自动登录",
        navController = navController,
        showBack = true,
    ) { padding ->
        UfiPageBackground(modifier = Modifier.padding(padding)) {
            val state = draft
            when {
                state == null && loading -> {
                    Box(
                        Modifier.fillMaxWidth().height(220.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        UfiLoadingIndicator()
                    }
                }

                state == null -> {
                    Box(Modifier.fillMaxWidth().height(220.dp)) {
                        UfiListErrorState(
                            message = loadError ?: "读取自动登录设置失败",
                            onRetry = { reload() },
                        )
                    }
                }

                else -> {
                    val exhausted = state.keepalive.attemptsToday >= state.keepalive.dailySmsLimit
                    Column(
                        verticalArrangement = Arrangement.spacedBy(Spacing.SettingsCardGap),
                    ) {
                        if (loadError != null) {
                            BillStaleSyncNote(
                                message = loadError.orEmpty(),
                                onRetry = { reload() },
                            )
                        }

                        UfiSettingsGroup {
                            UfiSettingsToggle(
                                title = "开启自动登录",
                                description = "会话失效时自动收码登录；所有参与账号共用下面的策略",
                                checked = state.keepalive.enabled,
                                onCheckedChange = { enabled ->
                                    commit(
                                        state.copy(
                                            keepalive = state.keepalive.copy(enabled = enabled),
                                        ),
                                        buildJsonObject {
                                            put("keepalive", buildJsonObject { put("enabled", enabled) })
                                        },
                                    )
                                },
                                icon = Icons.Default.Autorenew,
                                enabled = !busy,
                            )
                            UfiDivider()
                            UfiSettingsItem(
                                title = "今日短信",
                                description = "手动登录与自动登录共用上限",
                                icon = Icons.Default.Sms,
                                trailing = {
                                    UfiBadge(
                                        text = "${state.keepalive.attemptsToday}/" +
                                            "${state.keepalive.dailySmsLimit} 条",
                                        type = if (exhausted) {
                                            UfiBadgeType.WARNING
                                        } else {
                                            UfiBadgeType.DEFAULT
                                        },
                                    )
                                },
                            )
                        }

                        UfiNoticeCard(
                            message = "仅限中国移动号码；SIM 必须插在当前设备，且设备读到的号码与账号一致。" +
                                "不满足的账号会显示「不支持」，不会参与自动登录。",
                            severity = UfiNoticeSeverity.INFO,
                        )

                        UfiSettingsGroup {
                            UfiSettingsItem(
                                title = "参与账号",
                                description = accountsSummary(state),
                                icon = Icons.Default.Smartphone,
                                trailing = { UfiSettingsChevron() },
                                onClick = {
                                    navController.ufiNavigateOnce(
                                        Routes.DETAIL_BILL_SETTINGS_ACCOUNTS
                                    )
                                },
                            )
                        }

                        UfiSettingsGroup {
                            UfiGroupHeader("自动登录策略")
                            UfiSettingsValue(
                                title = "检查间隔",
                                description = "1–60 分钟；多久检查一次会话",
                                value = "${state.keepalive.checkIntervalMinutes} 分钟",
                                enabled = state.keepalive.enabled && !busy,
                                onClick = {
                                    editInitial = BillSettingField.CheckInterval
                                        .currentValue(state).toString()
                                    editField = BillSettingField.CheckInterval
                                },
                            )
                            UfiDivider()
                            UfiSettingsValue(
                                title = "重登间隔",
                                description = "10–720 分钟；会话有效时主动重登",
                                value = "${state.keepalive.reloginIntervalMinutes} 分钟",
                                enabled = state.keepalive.enabled && !busy,
                                onClick = {
                                    editInitial = BillSettingField.ReloginInterval
                                        .currentValue(state).toString()
                                    editField = BillSettingField.ReloginInterval
                                },
                            )
                            UfiDivider()
                            UfiSettingsValue(
                                title = "每日短信上限",
                                description = "1–20 条/日；手动与自动共用",
                                value = "${state.keepalive.dailySmsLimit} 条/日",
                                enabled = state.keepalive.enabled && !busy,
                                onClick = {
                                    editInitial = BillSettingField.DailySms
                                        .currentValue(state).toString()
                                    editField = BillSettingField.DailySms
                                },
                            )
                        }

                        Spacer(Modifier.height(Spacing.Large))
                    }
                }
            }
        }

        UfiToastHost(toastMessage = toast, onDismiss = { toast = null })
    }

    editField?.let { field ->
        val state = draft
        if (state != null) {
            UfiInputDialog(
                title = field.title,
                initialValue = editInitial,
                hint = field.hint,
                confirmText = "保存",
                validator = { raw ->
                    val value = raw.toIntOrNull()
                    when {
                        value == null -> "请输入数字"
                        value !in field.range -> "取值 ${field.range.first}–${field.range.last}"
                        else -> null
                    }
                },
                onConfirm = { raw ->
                    val value = raw.toIntOrNull() ?: return@UfiInputDialog
                    editField = null
                    commit(field.applyTo(state, value), field.patch(value))
                },
                onDismiss = { editField = null },
            )
        }
    }
}

private fun accountsSummary(state: CarrierBillSettingsState): String {
    val participating = state.accounts.count { it.participating }
    val unsupported = state.accounts.count { !it.supported }
    return buildString {
        append("$participating 个账号参与")
        if (unsupported > 0) append(" · $unsupported 个不支持")
    }
}
