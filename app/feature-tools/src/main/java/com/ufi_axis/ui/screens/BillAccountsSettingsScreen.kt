package com.ufi_axis.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.ufi_axis.data.model.CarrierBillSettingsState
import com.ufi_axis.data.model.CarrierKeepAliveAccount
import com.ufi_axis.ui.components.common.ToastMessage
import com.ufi_axis.ui.components.common.ToastType
import com.ufi_axis.ui.components.common.UfiBadgeType
import com.ufi_axis.ui.components.common.UfiButton
import com.ufi_axis.ui.components.common.UfiConfirmDialog
import com.ufi_axis.ui.components.common.UfiEmptyState
import com.ufi_axis.ui.components.common.UfiListErrorState
import com.ufi_axis.ui.components.common.UfiLoadingIndicator
import com.ufi_axis.ui.components.common.UfiNoticeCard
import com.ufi_axis.ui.components.common.UfiNoticeSeverity
import com.ufi_axis.ui.components.common.UfiPageBackgroundBox
import com.ufi_axis.ui.components.common.UfiScreenScaffold
import com.ufi_axis.ui.components.common.UfiSettingsItem
import com.ufi_axis.ui.components.common.UfiSettingsRowCard
import com.ufi_axis.ui.components.common.UfiStatusCard
import com.ufi_axis.ui.components.common.UfiSwitch
import com.ufi_axis.ui.components.common.UfiToastHost
import com.ufi_axis.ui.navigation.Routes
import com.ufi_axis.ui.navigation.ufiNavigateOnce
import com.ufi_axis.ui.theme.LocalResolvedPalette
import com.ufi_axis.ui.theme.Spacing
import com.ufi_axis.ui.theme.UfiTextStyles
import com.ufi_axis.viewmodel.MainViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 「参与账号」子页（2026-11-13）。
 *
 * 从设置主页的「参与账号」行进入。账号列表用 [LazyColumn]，数量再多也只滚动这一页，
 * 不会把设置主页拉长；主页始终只保留一行摘要。
 *
 * 这里仍只改**参与位**，不改间隔/额度：参数全部在主页的设备策略里。
 * 每个账号行显示运营商、状态、参与开关；不满足「移动号码 / SIM 在当前设备 /
 * 设备读到号码与账号一致」时显示「不支持」；受支持且已参与时可以「立即登录」。
 */
@Composable
fun BillAccountsSettingsScreen(
    viewModel: MainViewModel,
    navController: NavHostController,
) {
    val palette = LocalResolvedPalette.current
    val remote by viewModel.tools.billSettings.collectAsState()
    var draft by remember(remote) { mutableStateOf(remote) }
    var busy by remember { mutableStateOf(false) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var toast by remember { mutableStateOf<ToastMessage?>(null) }
    var runTarget by remember { mutableStateOf<CarrierKeepAliveAccount?>(null) }
    val scope = rememberCoroutineScope()

    fun reload() {
        viewModel.tools.loadBillSettings { result ->
            result.onSuccess { loadError = null }
                .onFailure {
                    loadError = billSettingsErrorMessage(viewModel, it, "读取失败")
                }
        }
    }

    LaunchedEffect(Unit) {
        reload()
        while (true) {
            delay(5_000)
            if (!busy) reload()
        }
    }

    fun toggleParticipating(account: CarrierKeepAliveAccount, participating: Boolean) {
        val previous = draft ?: return
        draft = previous.copy(
            accounts = previous.accounts.map {
                if (it.id == account.id) it.copy(participating = participating) else it
            },
        )
        busy = true
        viewModel.tools.setKeepAliveParticipating(account.id, participating) { result ->
            busy = false
            result.onFailure {
                draft = previous
                toast = ToastMessage(
                    billSettingsErrorMessage(viewModel, it, "保存参与状态失败"),
                    ToastType.ERROR,
                )
            }
        }
    }

    UfiScreenScaffold(
        title = "参与账号",
        navController = navController,
        showBack = true,
    ) { padding ->
        UfiPageBackgroundBox(modifier = Modifier.fillMaxSize().padding(padding)) {
            val state = draft
            if (state == null) {
                if (loadError == null) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        UfiLoadingIndicator()
                    }
                } else {
                    UfiListErrorState(
                        message = loadError.orEmpty(),
                        onRetry = { reload() },
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = Spacing.Medium),
                    verticalArrangement = Arrangement.spacedBy(Spacing.SettingsCardGap),
                ) {
                    item {
                        UfiStatusCard(
                            title = "参与情况",
                            statusText = "${state.accounts.count { it.participating }} / ${state.accounts.size}",
                            statusType = if (state.accounts.any { it.participating }) {
                                UfiBadgeType.SUCCESS
                            } else {
                                UfiBadgeType.DEFAULT
                            },
                            subtitle = "总开关打开后只跑已参与的账号；当前 " +
                                "${state.accounts.count { it.supported }}/${state.accounts.size} 个账号满足条件。",
                        )
                    }

                    if (loadError != null) {
                        item {
                            Text(
                                "上次同步失败：$loadError",
                                style = UfiTextStyles.noteCompact,
                                color = palette.error,
                                modifier = Modifier.padding(horizontal = Spacing.CardHorizontalMargin),
                            )
                        }
                    }

                    if (state.accounts.isEmpty()) {
                        item {
                            UfiEmptyState(
                                icon = Icons.Default.Smartphone,
                                message = "还没有运营商账号",
                                hint = "先添加中国移动账号，再选择是否参与自动登录。联通与物联卡不参与。",
                                action = {
                                    UfiButton(
                                        text = "去添加账号",
                                        onClick = {
                                            navController.ufiNavigateOnce(Routes.DETAIL_BILL_ADD)
                                        },
                                        modifier = Modifier.width(180.dp),
                                    )
                                },
                            )
                        }
                    } else {
                        items(state.accounts, key = { it.id }) { account ->
                            AccountRowCard(
                                account = account,
                                globalEnabled = state.keepalive.enabled,
                                busy = busy,
                                onToggle = { toggleParticipating(account, it) },
                                onRun = { runTarget = account },
                            )
                        }
                    }

                    item {
                        UfiNoticeCard(
                            message = "自动登录仅支持中国移动号码，且 SIM 必须插在当前设备、" +
                                "设备读到的号码与账号一致。",
                            severity = UfiNoticeSeverity.INFO,
                        )
                    }
                    item { Spacer(Modifier.height(Spacing.Large)) }
                }
            }
        }

        UfiToastHost(toastMessage = toast, onDismiss = { toast = null })
    }

    runTarget?.let { account ->
        UfiConfirmDialog(
            title = "立即登录",
            text = "将向「${account.displayTitle}」发送一条真实短信验证码，并计入今日额度。确定继续？",
            confirmText = "发送",
            onConfirm = {
                runTarget = null
                busy = true
                viewModel.tools.runAccountAutoLogin(account.id) { result ->
                    busy = false
                    result.onSuccess { state ->
                        if (state.phase == "disabled") {
                            toast = ToastMessage(
                                state.message ?: "自动登录未开启",
                                ToastType.WARNING,
                            )
                        } else {
                            toast = ToastMessage("已请求自动登录，正在等待结果…", ToastType.INFO)
                            scope.launch {
                                delay(1_500)
                                reload()
                                delay(3_500)
                                reload()
                            }
                        }
                    }.onFailure {
                        toast = ToastMessage(
                            billSettingsErrorMessage(viewModel, it, "触发失败"),
                            ToastType.ERROR,
                        )
                    }
                }
            },
            onDismiss = { runTarget = null },
        )
    }
}

@Composable
private fun AccountRowCard(
    account: CarrierKeepAliveAccount,
    globalEnabled: Boolean,
    busy: Boolean,
    onToggle: (Boolean) -> Unit,
    onRun: () -> Unit,
) {
    val palette = LocalResolvedPalette.current
    UfiSettingsRowCard {
        UfiSettingsItem(
            title = account.displayTitle,
            description = keepAliveAccountStatus(account, globalEnabled),
            descriptionMaxLines = 2,
            icon = Icons.Default.Smartphone,
            trailing = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (account.supported && account.participating && globalEnabled && !busy) {
                        TextButton(
                            onClick = onRun,
                            contentPadding = PaddingValues(horizontal = 8.dp),
                        ) {
                            Text(
                                "立即登录",
                                style = UfiTextStyles.note,
                                color = palette.accent,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Spacer(Modifier.width(4.dp))
                    }
                    UfiSwitch(
                        checked = account.participating,
                        onCheckedChange = onToggle,
                        enabled = !busy && (account.supported || account.participating),
                    )
                }
            },
        )
    }
}
