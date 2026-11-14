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
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Text
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
import com.ufi_axis.ui.components.common.UfiDivider
import com.ufi_axis.ui.components.common.UfiGroupHeader
import com.ufi_axis.ui.components.common.UfiInputDialog
import com.ufi_axis.ui.components.common.UfiListErrorState
import com.ufi_axis.ui.components.common.UfiLoadingIndicator
import com.ufi_axis.ui.components.common.UfiPageBackground
import com.ufi_axis.ui.components.common.UfiScreenScaffold
import com.ufi_axis.ui.components.common.UfiSettingsChevron
import com.ufi_axis.ui.components.common.UfiSettingsGroup
import com.ufi_axis.ui.components.common.UfiSettingsItem
import com.ufi_axis.ui.components.common.UfiSettingsRowCard
import com.ufi_axis.ui.components.common.UfiSettingsToggle
import com.ufi_axis.ui.components.common.UfiSettingsValue
import com.ufi_axis.ui.components.common.UfiToastHost
import com.ufi_axis.ui.navigation.Routes
import com.ufi_axis.ui.navigation.ufiNavigateOnce
import com.ufi_axis.ui.theme.LocalResolvedPalette
import com.ufi_axis.ui.theme.Spacing
import com.ufi_axis.ui.theme.UfiTextStyles
import com.ufi_axis.viewmodel.MainViewModel
import kotlinx.coroutines.delay
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 账单设置（2026-11-13：自动登录改为独立入口）。
 *
 * 本页只保留两条设备级入口：
 * 1. 「自动登录」—— 一行 entry，点进去是 [AutoLoginSettingsScreen]（总开关/策略/参与账号/条件）；
 * 2. 「后台更新」—— 开关 + 刷新间隔，直接在本页改。
 *
 * 自动登录不再用 hero 卡：它是账单的一个独立子页，入口保持普通设置行的密度。
 */
@Composable
fun BillSettingsScreen(
    viewModel: MainViewModel,
    navController: NavHostController,
) {
    val palette = LocalResolvedPalette.current
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
                val message = billSettingsErrorMessage(viewModel, it, "读取账单设置失败")
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

    fun commit(next: CarrierBillSettingsState, patch: kotlinx.serialization.json.JsonObject) {
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
        title = "账单设置",
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
                            message = loadError ?: "读取账单设置失败",
                            onRetry = { reload() },
                        )
                    }
                }

                else -> {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(Spacing.SettingsCardGap),
                    ) {
                        if (loadError != null) {
                            BillStaleSyncNote(
                                message = loadError.orEmpty(),
                                onRetry = { reload() },
                            )
                        }

                        UfiSettingsRowCard {
                            UfiSettingsItem(
                                title = "自动登录",
                                description = autoLoginSummary(state),
                                icon = Icons.Default.Autorenew,
                                trailing = { UfiSettingsChevron() },
                                onClick = {
                                    navController.ufiNavigateOnce(
                                        Routes.DETAIL_BILL_SETTINGS_AUTO_LOGIN
                                    )
                                },
                            )
                        }

                        UfiSettingsGroup {
                            UfiGroupHeader("后台更新")
                            UfiSettingsToggle(
                                title = "自动刷新",
                                checked = state.refresh.enabled,
                                onCheckedChange = { enabled ->
                                    commit(
                                        state.copy(refresh = state.refresh.copy(enabled = enabled)),
                                        buildJsonObject {
                                            put("refresh", buildJsonObject { put("enabled", enabled) })
                                        },
                                    )
                                },
                                icon = Icons.Default.Refresh,
                                enabled = !busy,
                            )
                            UfiDivider()
                            UfiSettingsValue(
                                title = "刷新间隔",
                                description = "5–240 分钟；只影响缓存新鲜度",
                                value = "${state.refresh.intervalMinutes} 分钟",
                                enabled = state.refresh.enabled && !busy,
                                onClick = {
                                    editInitial = BillSettingField.RefreshInterval
                                        .currentValue(state).toString()
                                    editField = BillSettingField.RefreshInterval
                                },
                            )
                        }

                        Text(
                            "自动登录与后台更新都作用于整台设备。",
                            style = UfiTextStyles.note,
                            color = palette.textSecondary,
                            modifier = Modifier.padding(horizontal = Spacing.CardHorizontalMargin),
                        )
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

/** 账单设置主页的自动登录摘要：只报状态/参与/不支持，详细策略在子页。 */
private fun autoLoginSummary(state: CarrierBillSettingsState): String {
    val participating = state.accounts.count { it.participating }
    val unsupported = state.accounts.count { !it.supported }
    return buildString {
        append(if (state.keepalive.enabled) "已开启" else "未开启")
        append(" · $participating 个账号参与")
        if (unsupported > 0) append(" · $unsupported 个不支持")
    }
}
