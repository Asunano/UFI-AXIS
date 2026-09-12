package com.ufi_axis.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavHostController
import com.ufi_axis.data.model.LocalSmsConfigPatch
import com.ufi_axis.data.model.LocalSmsTestResponse
import com.ufi_axis.data.notification.NotificationCenter
import com.ufi_axis.data.notification.NotifyPrefs
import com.ufi_axis.ui.components.common.*
import com.ufi_axis.ui.navigation.Routes
import com.ufi_axis.ui.theme.LocalResolvedPalette
import com.ufi_axis.ui.theme.Spacing
import com.ufi_axis.ui.theme.UfiTextStyles
import com.ufi_axis.viewmodel.MainViewModel

/**
 * 「本机短信」通知渠道配置页（2026-09-09 阶段 3）。入口在 [PushChannelsScreen]。
 *
 * core 侧真源：`LocalSmsConfig` / `LocalSmsChannel`，端点 `/api/notify/sms/{config,test}`。
 * 本页**只渲染 core 的数据**：可选级别（`levels`）、每日上限的值域
 * （`daily_limit_min|max`）、"能不能投"的判据（`configured`）、今日用量
 * （`sent_today` / `quota_remaining`）全部来自 GET 响应，app 里没有第二份。
 *
 * ## 这一页与另两条渠道页的根本区别：**每一次投递都要付钱**
 *
 * 所以界面上有三件事是别的渠道页不需要做的：
 * 1. 启用开关的副文案**先说费用**（"会产生短信费用，由本机 SIM 发出"），不是先说功能；
 * 2. "今日用量"是一整行只读信息，不是藏在详情里的次要数字；
 * 3. 「发送测试」点下去之前必须过一次 [UfiConfirmDialog] —— 它会**真的发出一条短信并消耗
 *    一条配额**，做成"点了再说"的按钮就是给用户一个可以连点的烧钱开关。
 *
 * ## 页面结构（一项一卡，节奏与 [WebhookNotifyScreen] 一致）
 *
 * 1. 启用开关 —— 副文案说费用 + core 的 `configured`
 * 2. 目标号码 —— 弹窗输入，本地先校验一遍（core 是唯一强制点，但等 PUT 报错等于白填一次）
 * 3. 最低级别 —— [NotifyMinLevelRow]（三条渠道共用），选项来自 core 的 `levels`
 * 4. 每日上限 —— [NotifyDailyLimitRow] + [NotifyDailyLimitDialog]，区间来自 core
 * 5. 今日用量 —— [NotifyQuotaRow]
 * 6. 触发场景 —— 与邮件 / Webhook 同一套 [NOTIFY_SCENE_LABELS]
 * 7. 遵守免打扰（副文案带「严重事件兜底」提示）
 * 8. 发送测试（先确认）+ 最近测试结果
 * 9. 最近投递 —— 入口，指向 [DeliveryHistoryScreen]（channel = local_sms）
 *
 * ## 2026-09-10：③④⑤ 三行改用共用件
 *
 * core 把三条渠道的规则做成了同构的（每条都有最低级别 + 每日上限），所以这三行搬进了
 * `NotifyChannelRules.kt`，邮件与 Webhook 用的是同一批组件与同一套措辞。
 * 本页刻意保留的差异只有一处：`daily_limit_min = 1`，**不允许"不限"** ——
 * 这条渠道花钱。差异由共用件按 `min` 参数在界面上写出来，不靠用户去撞 400。
 *
 * ## 读不到配置时
 *
 * 不渲染任何表单，只给空态 + 重试。用默认值把界面画出来等于让用户对着假配置改，
 * 一保存就把设备上的真配置覆盖掉（同 [WebhookNotifyScreen]）。
 *
 * @param viewModel 读 `localSmsState`，写 `tools.saveLocalSmsConfig` / `tools.testLocalSms`
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocalSmsNotifyScreen(viewModel: MainViewModel, navController: NavHostController) {
    val palette = LocalResolvedPalette.current
    val state by viewModel.localSmsState.collectAsState()
    // 只为「最近投递」入口卡的右侧摘要
    val toolsState by viewModel.toolsState.collectAsState()

    // 「严重事件兜底」的本机镜像（真源在 core）。本页只读它来决定免打扰 / 场景那两处
    // 提示怎么写 —— 兜底关着却写"严重事件会穿透"就是承诺一件不会发生的事。
    //
    // 走 NotifyPrefs.switchOn 而不是自己 getBoolean：默认值只在那一处定义（本字段默认 true）。
    // 不在本页调 refreshNotificationConfig：镜像由持有那个开关的页面负责刷新
    // （「通知与守护」是本页的上游路径，进那一页时已经回读过一次），这里只在恢复前台时重读，
    // 于是"去把兜底关掉再回来"这条路径上文案会跟着变。
    val context = LocalContext.current
    val readCriticalOverride = {
        NotifyPrefs.switchOn(
            context,
            NotificationCenter.KEY_CRITICAL_OVERRIDE,
            NotificationCenter.DEFAULT_CRITICAL_OVERRIDE
        )
    }
    var criticalOverrideOn by remember { mutableStateOf(readCriticalOverride()) }
    rememberResumeRefresh { criticalOverrideOn = readCriticalOverride() }

    LaunchedEffect(Unit) {
        viewModel.tools.loadLocalSmsConfig()
        viewModel.tools.loadDeliveryHistory(DELIVERY_CHANNEL_LOCAL_SMS)
    }

    var toastMessage by remember { mutableStateOf<ToastMessage?>(null) }
    // 打开哪个弹窗。用一个 sealed 状态而不是四个布尔：四个布尔可以同时为真，
    // 而"两个弹窗同时开着"是个没人想要的状态（同 WebhookNotifyScreen）。
    var dialog by remember { mutableStateOf<LocalSmsDialog?>(null) }
    /** 待确认的测试发送。非 null = 确认弹窗开着。 */
    var pendingTest by remember { mutableStateOf(false) }

    fun persist(patch: LocalSmsConfigPatch) = viewModel.tools.saveLocalSmsConfig(patch)

    // 保存结算：saveTick 每次 PUT 有结果就 +1。用递增计数而不是嗅 saving 的下降沿，
    // 布尔边沿会被 snapshot 合并掉（本仓已在骨架屏上踩过同一个坑）。
    LaunchedEffect(state.saveTick) {
        if (state.saveTick == 0) return@LaunchedEffect
        val err = state.saveError
        toastMessage = if (err == null) {
            ToastMessage("配置已保存", ToastType.SUCCESS)
        } else {
            // core 的 400 文案里写明了是哪一条约束不过（号码形状 / 上限区间 / 未知场景 /
            // 级别名），原样转出去比"保存失败"有用。
            ToastMessage("保存失败", ToastType.ERROR, subtitle = err)
        }
    }

    // 测试结算：配额有没有被扣掉必须说出来 —— 那是真金白银。
    LaunchedEffect(state.testTick) {
        if (state.testTick == 0) return@LaunchedEffect
        val result = state.lastTest ?: return@LaunchedEffect
        toastMessage = buildLocalSmsTestToast(result)
    }

    UfiScreenScaffold(title = "本机短信通知", navController = navController, showBack = true) { padding ->
        UfiPageBackground(modifier = Modifier.padding(padding)) {
            state.errorMessage?.let { err -> UfiErrorBanner(message = err) }

            val cfg = state.config
            if (cfg == null) {
                // 还没读完 → 骨架；读完了还是 null → 真读失败，给重试。
                if (!state.loaded) {
                    UfiSkeletonList(rows = LOCAL_SMS_SKELETON_ROWS)
                } else {
                    UfiEmptyState(
                        icon = Icons.Default.CloudOff,
                        message = "读不到本机短信配置",
                        hint = "设备可能未连接，或设备端版本不支持该功能",
                        action = {
                            UfiButton(
                                text = "重试",
                                size = UfiButtonSize.Small,
                                onClick = { viewModel.tools.loadLocalSmsConfig() }
                            )
                        }
                    )
                }
                return@UfiPageBackground
            }

            // ── ① 启用开关。副文案**先说费用**，再说能不能投 ──
            UfiSettingsRowCard {
                UfiSettingsToggle(
                    title = "启用本机短信通知",
                    description = when {
                        !cfg.configured && cfg.target_number.isBlank() ->
                            "会产生短信费用，由本机 SIM 发出；还需填写目标号码"
                        !cfg.configured ->
                            "会产生短信费用，由本机 SIM 发出；当前号码格式不合法，无法发送"
                        else ->
                            "会产生短信费用，由本机 SIM 发出；勾选的场景达到最低级别时才会发送"
                    },
                    checked = cfg.enabled,
                    onCheckedChange = { persist(LocalSmsConfigPatch(enabled = it)) }
                )
            }

            // ── ② 目标号码 ──
            UfiSettingsRowCard {
                UfiSettingsValue(
                    title = "目标号码",
                    description = "通知会发送到这个号码。可填写为 138-0013-8000，设备会自动去掉分隔符",
                    value = cfg.target_number.ifBlank { "未填写" },
                    onClick = { dialog = LocalSmsDialog.TargetNumber }
                )
            }

            // ── ③ 最低级别 · ④ 每日上限 · ⑤ 今日用量 ──
            //
            // 三行都走 NotifyChannelRules.kt 里的共用件：core 已经把三条渠道的规则做成同构的，
            // 界面也必须长成同一种。本页只把自己的值传进去 —— 值域（levels / daily_limit_min|max）
            // 全部来自 GET 响应，app 里没有第二份。
            //
            // metered = true 只影响级别提示后面接的那句代价说明：这条渠道每发一次都要付钱。
            NotifyMinLevelRow(
                levels = cfg.levels,
                current = cfg.min_level,
                metered = true,
                onSelect = { persist(LocalSmsConfigPatch(min_level = it)) }
            )

            NotifyDailyLimitRow(
                dailyLimit = cfg.daily_limit,
                min = cfg.daily_limit_min,
                max = cfg.daily_limit_max,
                onClick = { dialog = LocalSmsDialog.DailyLimit }
            )

            NotifyQuotaRow(
                sentToday = cfg.sent_today,
                dailyLimit = cfg.daily_limit,
                quotaRemaining = cfg.quota_remaining
            )


            // ── ⑥ 触发场景。与邮件 / Webhook 共用 NOTIFY_SCENE_LABELS ──
            UfiSettingsRowCard {
                UfiSettingsValue(
                    title = "触发场景",
                    description = "勾选的场景触发时会发送短信",
                    value = if (cfg.scenes.isEmpty()) "未选择" else "${cfg.scenes.size} 个场景",
                    onClick = { dialog = LocalSmsDialog.Scenes }
                )
            }

            // ── ⑦ 遵守免打扰。时段本身在「通知与守护 → 通知管理」里改 ──
            //    副文案末尾必须带上兜底提示：兜底开着时严重事件会穿透静默时段，
            //    不说出来的话"设了免打扰还是半夜发短信"会被当成故障（而且这条渠道花钱）。
            UfiSettingsRowCard {
                UfiSettingsToggle(
                    title = "遵守免打扰时段",
                    description = if (cfg.respect_dnd) {
                        "免打扰时段内不发短信（时段在「通知管理」里设置）· " +
                            notifyCriticalOverrideNote(criticalOverrideOn)
                    } else {
                        "免打扰时段内仍会发送短信"
                    },
                    checked = cfg.respect_dnd,
                    onCheckedChange = { persist(LocalSmsConfigPatch(respect_dnd = it)) }
                )
            }

            // ── ⑧ 发送测试 + 最近结果 ──
            UfiSettingsGroup {
                UfiSectionHeader(title = "测试")
                // 只看本行自己的请求在飞，不看别人的 loading（邮件页踩过的坑）。
                // 未启用时不给点：core 的 /test 在 `!enabled` 时直接回错。
                val canTest = cfg.enabled && cfg.configured && !state.testing
                UfiSettingsItem(
                    title = "发送测试短信",
                    description = when {
                        !cfg.enabled -> "需先启用本机短信通知"
                        !cfg.configured -> "需先填写合法的目标号码"
                        // quota_remaining 现在是 Int?（null = 不限）。本渠道的 daily_limit_min
                        // 是 1，拿不到 null；仍然显式判空，别让"不限"被当成"用尽"。
                        cfg.quota_remaining?.let { it <= 0 } == true ->
                            "今日配额已用尽，暂时无法发送测试短信"
                        else -> "将发送一条真实短信，产生短信费用并占用一条今日配额"
                    },
                    enabled = canTest,
                    modifier = Modifier.clickable(enabled = canTest) { pendingTest = true },
                    trailing = {
                        Text(
                            text = if (state.testing) "发送中…" else "点击试发",
                            style = UfiTextStyles.label,
                            color = if (canTest) palette.accent else palette.textSecondary
                        )
                    }
                )
                state.lastTest?.let { result ->
                    UfiDivider()
                    UfiSettingsValue(
                        title = "最近测试结果",
                        description = summarizeLocalSmsTestOutcome(result),
                        value = verdictLabel(result),
                        onClick = { dialog = LocalSmsDialog.TestResult }
                    )
                }
            }

            // ── ⑨ 最近投递。位置与另两条渠道页一致（测试之后、页尾之前）：
            //    先给"能不能发出"，再给"实际发了几条"。花钱的渠道，这一行还兼作对账入口 ──
            UfiSettingsRowCard {
                UfiSettingsValue(
                    title = DELIVERY_HISTORY_ENTRY_TITLE,
                    description = DELIVERY_HISTORY_ENTRY_DESCRIPTION,
                    value = deliveryHistorySummary(
                        // 三条渠道共用一个 state 槽位，先确认里面装的就是本机短信那一份。
                        ready = toolsState.deliveryHistoryLoaded &&
                            toolsState.deliveryHistoryChannel == DELIVERY_CHANNEL_LOCAL_SMS,
                        total = toolsState.deliveryHistoryTotal,
                        failedTotal = toolsState.deliveryHistoryFailedTotal,
                        skippedTotal = toolsState.deliveryHistorySkippedTotal
                    ),
                    onClick = {
                        navController.navigate(Routes.deliveryHistory(DELIVERY_CHANNEL_LOCAL_SMS))
                    }
                )
            }

            Spacer(Modifier.height(Spacing.Large))
        }

        // ── 弹窗 ──
        when (dialog) {
            null -> Unit
            LocalSmsDialog.TargetNumber -> TargetNumberDialog(
                current = state.config?.target_number ?: "",
                saving = state.saving,
                onDismiss = { dialog = null },
                onSave = { number ->
                    dialog = null
                    persist(LocalSmsConfigPatch(target_number = number))
                }
            )
            LocalSmsDialog.DailyLimit -> NotifyDailyLimitDialog(
                current = state.config?.daily_limit ?: 0,
                min = state.config?.daily_limit_min ?: 0,
                max = state.config?.daily_limit_max ?: 0,
                saving = state.saving,
                onDismiss = { dialog = null },
                onSave = { limit ->
                    dialog = null
                    persist(LocalSmsConfigPatch(daily_limit = limit))
                }
            )
            LocalSmsDialog.Scenes -> NotifyScenesDialog(
                title = "触发场景",
                leadNote = "勾选的场景每次触发都会发送一条短信并产生费用。网络中断或流量超限时，" +
                    "邮件与 Webhook 可能无法送出，短信仍可送达。",
                selected = state.config?.scenes ?: emptyList(),
                criticalOverrideOn = criticalOverrideOn,
                onDismiss = { dialog = null },
                onSave = { next ->
                    dialog = null
                    persist(LocalSmsConfigPatch(scenes = next))
                }
            )
            LocalSmsDialog.TestResult -> LocalSmsTestResultDialog(
                result = state.lastTest,
                onDismiss = { dialog = null }
            )
        }

        // 测试发送确认。不问就发等于给用户一个可以连点的烧钱按钮。
        if (pendingTest) {
            val cfg = state.config
            UfiConfirmDialog(
                title = "发送一条测试短信？",
                text = "会通过本机 SIM 发出一条短信到 ${cfg?.target_number ?: "目标号码"}，" +
                    "产生一条短信费用，并占用一条今日配额" +
                    (cfg?.let {
                        "（当前 ${notifyQuotaValue(it.sent_today, it.daily_limit)}）"
                    } ?: "") + "。",
                confirmText = "发送",
                onConfirm = {
                    pendingTest = false
                    viewModel.tools.testLocalSms()
                },
                onDismiss = { pendingTest = false }
            )
        }

        UfiToastHost(toastMessage = toastMessage, onDismiss = { toastMessage = null })
    }
}

// ══════════════════════════════════════════════════════════════
// 弹窗
// ══════════════════════════════════════════════════════════════

/** 本页可能打开的弹窗。互斥，所以是一个可空的 sealed 值而不是一堆布尔。 */
private sealed interface LocalSmsDialog {
    data object TargetNumber : LocalSmsDialog
    data object DailyLimit : LocalSmsDialog
    data object Scenes : LocalSmsDialog
    data object TestResult : LocalSmsDialog
}

/**
 * 目标号码弹窗（暂存-确认）。
 *
 * 本地校验的口径与 core 的 `LocalSmsDelivery.isValidNumber` 一致（数字 / `+` / `-` / 空格，
 * 去掉分隔符后 3..20 位）。**core 仍是唯一强制点**（web 与 curl 也能 PUT），
 * 这里只是别让用户填完一屏再等 PUT 回来才报错。
 */
@Composable
private fun TargetNumberDialog(
    current: String,
    saving: Boolean,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    val palette = LocalResolvedPalette.current
    var number by remember { mutableStateOf(current) }

    val trimmed = number.trim()
    // 空号码是合法的（= 还没配完，core 会判它不可投），但填了就必须能拨出去。
    val error = if (trimmed.isNotEmpty() && !isDialableNumber(trimmed)) {
        "只能是数字，可带 + 前缀与 - / 空格分隔；去掉分隔符后需 $MIN_NUMBER_DIGITS–$MAX_NUMBER_DIGITS 位"
    } else null

    UfiCustomDialog(
        visible = true,
        onDismiss = onDismiss,
        title = "目标号码",
        confirmButton = {
            UfiButton(
                text = "保存",
                enabled = error == null && !saving,
                onClick = { onSave(trimmed) }
            )
        },
        dismissButton = {
            UfiButton(variant = UfiButtonVariant.Secondary, text = "取消", onClick = onDismiss)
        }
    ) {
        UfiDialogBody {
            UfiDialogTextField(
                label = "手机号 / 短号",
                value = number,
                onValueChange = { number = it },
                placeholder = "13800138000",
                isError = error != null,
                errorMessage = error
            )
            Text(
                text = "留空表示不启用这条渠道。这里填的是接收通知的号码，" +
                    "设备会在触发时向该号码发送短信。",
                style = MaterialTheme.typography.bodySmall,
                color = palette.textSecondary
            )
        }
    }
}

/** 测试结果弹窗：固件结论 + 配额结算 + 总开关状态。 */
@Composable
private fun LocalSmsTestResultDialog(result: LocalSmsTestResponse?, onDismiss: () -> Unit) {
    val palette = LocalResolvedPalette.current
    if (result == null) return

    UfiScrollableDialog(
        visible = true,
        onDismiss = onDismiss,
        title = "测试结果",
        actions = {
            UfiDialogActions(
                onDismiss = onDismiss,
                onConfirm = onDismiss,
                confirmText = "知道了",
                dismissText = null
            )
        }
    ) {
        UfiDialogBody {
            UfiDialogInfoRow(label = "结果", value = verdictLabel(result))
            // 本次**根本没投递**（总开关关、免打扰、配额用尽、配置不完整 …）时 core 不再回
            // 诊断四件套（verdict / detail / counted_toward_quota / attempted_at 整个键都不出现），
            // 判据是 attempted_at 在不在。这一行必须跟着消失：没发起过的一次，
            // 摆一个"计入今日配额：否"会让人以为设备真去发了一次、只是没被计数。
            if (result.attempted_at != null) {
                UfiDialogInfoRow(
                    label = "计入今日配额",
                    value = if (result.counted_toward_quota) "是" else "否"
                )
            }
            UfiDialogInfoRow(
                label = "今日用量",
                value = "${result.sent_today}/${result.daily_limit}"
            )
            UfiDialogInfoRow(
                label = "自动通知总开关",
                value = if (result.auto_notify_enabled) "开启" else "关闭"
            )
            if (!result.auto_notify_enabled) {
                UfiDialogWarning(
                    "测试短信不受总开关约束，自动通知受其约束。总开关关闭时这条测试可以发出，" +
                        "但自动通知不会发送；在「通知与守护」中打开「全局通知」后自动通知才会发出。"
                )
            }
            // PENDING 是设备"受理了但没在几秒内给最终状态"。它既不算成功也不会重试
            // （重试就是第二条话费），所以要专门解释一句，别让用户以为坏了。
            if (result.verdict == VERDICT_PENDING) {
                UfiDialogWarning(
                    "设备已受理但未在几秒内回报最终状态，短信可能已经发出。" +
                        "系统不会自动重发，请先确认手机是否已收到。"
                )
            }
            result.error?.takeIf { it.isNotBlank() }?.let {
                UfiDialogField(label = "原因") {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = palette.error)
                }
            }
            result.detail?.takeIf { it.isNotBlank() }?.let {
                UfiDialogField(label = "设备返回") {
                    Text(it, style = UfiTextStyles.label, color = palette.textPrimary)
                }
            }
        }
    }
}

// ══════════════════════════════════════════════════════════════
// 纯函数：标签 / 摘要 / 校验
// ══════════════════════════════════════════════════════════════

/**
 * 最近测试结果行右侧的一句话结论。固件结论比"成功/失败"更有信息量。
 *
 * `PENDING` / `NO_RESPONSE` 两档**必须带上"可能已发出"**：它们在投递记录里是"失败"，
 * 但短信很可能真的发出去了。只显示一个干巴巴的"失败"，用户会去手动重发 —— 那才是真花钱。
 */
private fun verdictLabel(result: LocalSmsTestResponse): String = when {
    result.success -> "已发出"
    result.verdict == VERDICT_PENDING -> "未回报最终状态，可能已发出"
    result.verdict == VERDICT_NO_RESPONSE -> "无法确认，可能已发出"
    result.verdict == VERDICT_REJECTED -> "设备明确拒收"
    result.verdict == VERDICT_FAILED -> "设备发送失败"
    else -> "未发出"
}

/**
 * 最近测试结果行的副文案。成功也要给一句 —— 用户要知道配额被扣了没有。
 *
 * 本次没投递（`attempted_at` 缺失，core 在 `Skipped` 时不回诊断字段）时**只说原因**：
 * 那种情况下"未计入配额"这句话没有信息量，还会让人误以为设备真去发了一次。
 */
private fun summarizeLocalSmsTestOutcome(result: LocalSmsTestResponse): String {
    val reason = result.error?.takeIf { it.isNotBlank() }
    if (result.attempted_at == null) return reason ?: "未发送"
    val quota = if (result.counted_toward_quota) {
        "已计入配额（${result.sent_today}/${result.daily_limit}）"
    } else {
        "未计入配额"
    }
    return if (reason == null) quota else "$reason · $quota"
}

/**
 * 测试结果 toast。
 *
 * 成功也要把配额说出来：这条渠道花钱，"发出去了"与"今天还剩几条"是两件用户都要知道的事。
 */
private fun buildLocalSmsTestToast(result: LocalSmsTestResponse): ToastMessage {
    val quota = "今日 ${result.sent_today}/${result.daily_limit}"
    return when {
        result.success -> ToastMessage(
            text = "测试短信已发出（$quota）",
            type = ToastType.SUCCESS,
            subtitle = if (result.auto_notify_enabled) null
            else "通知总开关当前关闭，自动通知不会发出"
        )
        // 未确认不是失败：设备受理了，多半已经发出去，只是没在几秒内回最终状态。
        result.verdict == VERDICT_PENDING -> ToastMessage(
            text = "设备未回报最终状态，可能已发出（$quota）",
            type = ToastType.WARNING,
            subtitle = "系统不会自动重发，请先确认手机是否已收到"
        )
        // 连设备的表态都没拿到（请求没走完/超时）。同样可能已经发出去了，所以口径与上一档一致。
        result.verdict == VERDICT_NO_RESPONSE -> ToastMessage(
            text = "无法确认设备是否已发出（$quota）",
            type = ToastType.WARNING,
            subtitle = "系统不会自动重发，请先确认手机是否已收到，再决定是否手动重发"
        )
        else -> ToastMessage(
            text = "测试失败（$quota）",
            type = ToastType.ERROR,
            subtitle = result.error?.takeIf { it.isNotBlank() } ?: result.detail
        )
    }
}

/**
 * 号码能不能拨出去。**与 core 的 `LocalSmsDelivery.isValidNumber` 同口径**：
 * 去掉 `-` 与空格后长度 3..20，且除首位可选的 `+` 之外全是数字。
 *
 * 这里重列一遍规则而不是让 core 回一个正则：一个正则字符串跨端传递之后，
 * 两边的引擎与转义规则并不完全一样，反而更容易分叉。真正的强制点仍在 core，
 * 本函数只影响"保存按钮灰不灰"。
 */
private fun isDialableNumber(raw: String): Boolean {
    val n = raw.filterNot { it == '-' || it == ' ' }
    if (n.length !in MIN_NUMBER_DIGITS..MAX_NUMBER_DIGITS) return false
    val digits = if (n.startsWith('+')) n.drop(1) else n
    return digits.isNotEmpty() && digits.all { it.isDigit() }
}

// ══════════════════════════════════════════════════════════════
// 常量
// ══════════════════════════════════════════════════════════════

/** 固件结论枚举名（core `GoformSmsClient.SendVerdict`）。 */
private const val VERDICT_PENDING = "PENDING"
private const val VERDICT_REJECTED = "REJECTED"
private const val VERDICT_FAILED = "FAILED"

/**
 * 拿不到设备表态的那一档（请求没走完 / 响应无法解析 / 超时）。
 *
 * 与 [VERDICT_REJECTED] 是两件事：那一档设备明确说了"没收下"（可重试、不计配额），
 * 这一档排除不了"其实已经发出去了"（不重试、计配额），所以文案也必须分开。
 */
private const val VERDICT_NO_RESPONSE = "NO_RESPONSE"

/** 号码长度区间，与 core `LocalSmsDelivery.MIN_NUMBER_CHARS` / `MAX_NUMBER_CHARS` 一致。 */
private const val MIN_NUMBER_DIGITS = 3
private const val MAX_NUMBER_DIGITS = 20

/** 首屏骨架的行数：本页稳态大约就是这么多张卡，行数对不上会让骨架→内容时明显跳一下。 */
private const val LOCAL_SMS_SKELETON_ROWS = 7
