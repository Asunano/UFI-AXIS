package com.ufi_axis.ui.screens

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavHostController
import com.ufi_axis.data.model.SmsForwardConfig
import com.ufi_axis.data.notification.NotificationCenter
import com.ufi_axis.data.notification.NotifyPrefs
import com.ufi_axis.data.notification.NotifyScene
import com.ufi_axis.ui.components.common.*
import com.ufi_axis.ui.navigation.Routes
import com.ufi_axis.ui.theme.LocalResolvedPalette
import com.ufi_axis.ui.theme.Spacing
import com.ufi_axis.ui.theme.UfiTextStyles
import com.ufi_axis.viewmodel.MainViewModel

/**
 * 「邮件通知」二级页（2026-08-29 由原「短信转发」页改造）。
 *
 * 改造要点：
 * - **只保留邮件通道**：原 CURL 回调 / 钉钉机器人一并删除（core 的
 *   `SmsForwardController` 也已删干净），因此不再有「转发方式」选择器 ——
 *   开关打开即走 SMTP。
 * - **不只转发短信**：按 [NotifyScene] 逐场景勾选，勾中的场景在系统通知发出成功后
 *   同时投一封邮件（app 侧钩子在 `NotificationCenter.forwardToMail`）。
 * - **场景白名单存在 core**：SMTP 凭据只在 core，白名单跟着凭据放同一处，
 *   app 侧不留第二份开关，避免两端不一致。
 *
 * ## 2026-08-30 二次重排：统计卡 + 入口行 + 弹窗
 * 前一版是「Hero 开关卡 + 六个输入框的 SMTP 卡 + 七个 chip 的范围卡」，滚动两屏还看不到重点。
 * 现在页面只有两张卡：
 * 1. **发送统计卡** —— 总发送 / 成功 / 失败（core 侧累计，跨重启保留）+ 最近成功/失败。
 *    Hero 卡删了：它的三个格子（可发信/场景数/SMTP）其实是"配置完整性"，
 *    而用户真正想知道的是"到底发出去几封"，那才值得占第一屏。
 * 2. **设置卡** —— 启用开关 + SMTP 入口行 + 转发范围入口行 + 附加设备信息开关。
 *    输入框全部搬进弹窗（[SmtpConfigDialog] / [MailScopeDialog]），列表页只显示摘要。
 *
 * ## 写入时机
 * 页面上的开关**改完立即下发**（不再有页面级「保存配置」按钮）；弹窗是暂存-确认，
 * 点「保存」才下发。两者都走同一个 `persist(...)`：core 的 POST 是字段级合并，
 * 但 app 侧发的是整个对象，**未编辑的字段必须从 `cfg` 原样带回**，否则会被空串覆盖。
 *
 * ## 2026-09-10「规则同构」
 *
 * core 给三条渠道配了同一组投递规则（最低级别 + 每日上限），本页因此多了三行：
 * 最低级别 / 每日上限 / 今日用量。组件、措辞、排布全部取自 `NotifyChannelRules.kt`，
 * 与 [WebhookNotifyScreen] / [LocalSmsNotifyScreen] 逐字一致 —— 这次改造的目的就是
 * 让用户在任一渠道页看到同一组旋钮。邮件的区间是 `0..500` 且允许 0（不限）。
 *
 * 同时 `POST /api/sms-forward/config` **从"永不报错"变成了可能回 400**
 * （上限越界 / 级别名认不出）。失败原因由 [UfiErrorBanner] 常驻显示，
 * 文案是 core 的中文原句（`ToolsModule.coreErrorMessage` 从错误体里取）。
 *
 * 路由/接口仍沿用 `sms-forward` 命名（`/api/sms-forward/…`）—— web 端与 API
 * 手册都引用该路径，改名只会破坏跨端契约，没有功能收益。
 *
 * @param viewModel 读 `smsForwardState`，写 `tools.saveSmsForwardConfig`
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EmailNotifyScreen(viewModel: MainViewModel, navController: NavHostController) {
    val palette = LocalResolvedPalette.current
    val state by viewModel.smsForwardState.collectAsState()
    // 只为「最近投递」入口的右侧摘要（没发出 / 已跳过 / 总数）
    val toolsState by viewModel.toolsState.collectAsState()
    LaunchedEffect(Unit) {
        viewModel.tools.loadSmsForwardConfig()
        viewModel.tools.loadDeliveryHistory(DELIVERY_CHANNEL_MAIL)
    }
    // 诊断跟着配置走：保存成功后 ToolsModule 会自动重载 config，这里随之重新拉一次诊断，
    // 于是统计数字与「可发信」在保存/测试后立刻刷新，不需要用户手动下拉。
    LaunchedEffect(state.config) { if (state.config != null) viewModel.tools.loadSmsForwardDiagnose() }

    val cfg = state.config
    var enabled by remember(cfg) { mutableStateOf(cfg?.enabled ?: false) }
    var forwardDevInfo by remember(cfg) { mutableStateOf(cfg?.forward_dev_info ?: false) }
    var scenes by remember(cfg) { mutableStateOf(cfg?.scenes ?: emptyList()) }
    // ── 「规则同构」的两个旋钮（2026-09-10）──
    // 与 enabled / scenes 同一套写法：本地镜像 cfg，改完立即下发。
    // **必须有本地副本**：本页的 POST 发的是整个对象，未编辑的字段要原样带回，
    // 少带一个就会被 DTO 默认值覆盖（min_level 掉回 info、daily_limit 掉回 0）。
    var minLevel by remember(cfg) { mutableStateOf(cfg?.min_level ?: NOTIFY_LEVEL_INFO) }
    var dailyLimit by remember(cfg) { mutableIntStateOf(cfg?.daily_limit ?: 0) }
    var smtpDialogOpen by remember { mutableStateOf(false) }
    var scopeDialogOpen by remember { mutableStateOf(false) }
    var limitDialogOpen by remember { mutableStateOf(false) }

    // ── 邮件是否遵守免打扰时段（2026-09-08）──
    // 判定在 core（NotificationRoutes.mailAllowed）；本地 prefs 只是镜像，供本页显示与下发。
    // 时段本身在「通知与守护」页改，这里只读来显示，避免造第二个编辑入口。
    val context = LocalContext.current
    val prefs = remember {
        context.getSharedPreferences(NotificationCenter.PREFS_NAME, Context.MODE_PRIVATE)
    }
    var mailRespectDnd by remember {
        mutableStateOf(prefs.getBoolean(NotificationCenter.KEY_MAIL_RESPECT_DND, false))
    }
    val dndOn = prefs.getBoolean(NotificationCenter.KEY_DND_ENABLED, false)
    val dndStart = prefs.getInt(NotificationCenter.KEY_DND_START_HOUR, NotificationCenter.DEFAULT_DND_START_HOUR)
    val dndEnd = prefs.getInt(NotificationCenter.KEY_DND_END_HOUR, NotificationCenter.DEFAULT_DND_END_HOUR)

    // 「严重事件兜底」的本机镜像（真源在 core）。只用来决定免打扰 / 场景那两处提示怎么写 ——
    // 兜底关着却写"严重事件会穿透"就是承诺一件不会发生的事。
    // 走 NotifyPrefs.switchOn 而不是上面那种 prefs.getBoolean：这个字段默认 **true**，
    // 默认值只在 NotifyPrefs / NotificationCenter 定义一处，抄进来必然某天分叉成 false。
    val criticalOverrideOn = NotifyPrefs.switchOn(
        context,
        NotificationCenter.KEY_CRITICAL_OVERRIDE,
        NotificationCenter.DEFAULT_CRITICAL_OVERRIDE
    )

    // 本页触发的在途动作。为什么需要它：`state.isLoading` 是 SmsForwardState 的**单一** loading 位，
    // 被 loadSmsForwardConfig / saveSmsForwardConfig / testSmsForward 三个动作共用。
    // 之前按钮直接读 isLoading，于是：
    //  ① 刚进页面（自动 loadSmsForwardConfig）按钮就变成「保存中...」并置灰；
    //  ② 点「测试发送」时保存按钮也跟着变「保存中...」。
    // 记下是谁在飞，按钮才只对自己的请求转圈。
    var pending by remember { mutableStateOf<EmailNotifyAction?>(null) }
    var toastMessage by remember { mutableStateOf<ToastMessage?>(null) }

    /**
     * 下发配置。未在参数里显式给出的字段一律从 [cfg] 原样带回 ——
     * 传空串会被 core 当成"用户就是要清空"（它只跳过 `null`/缺失键与空密码）。
     */
    fun persist(
        newEnabled: Boolean = enabled,
        newDevInfo: Boolean = forwardDevInfo,
        newScenes: List<String> = scenes,
        newMinLevel: String = minLevel,
        newDailyLimit: Int = dailyLimit,
        host: String = cfg?.smtp_host ?: "",
        port: Int = cfg?.smtp_port ?: 465,
        user: String = cfg?.smtp_user ?: "",
        pass: String = "",
        from: String = cfg?.smtp_from ?: "",
        to: String = cfg?.smtp_to ?: ""
    ) {
        pending = EmailNotifyAction.SAVE
        viewModel.tools.saveSmsForwardConfig(
            SmsForwardConfig(
                enabled = newEnabled,
                smtp_host = host,
                smtp_port = port,
                smtp_user = user,
                // 空串 = 不修改密码（core 侧 `takeIf { it.isNotEmpty() }` 保留已存值）
                smtp_pass = pass,
                smtp_from = from,
                smtp_to = to,
                forward_dev_info = newDevInfo,
                scenes = newScenes,
                min_level = newMinLevel,
                daily_limit = newDailyLimit
            )
        )
    }

    // 结算靠 isLoading 的**下降沿** + pending 归属。原判据是 `saveAttempted && !isLoading && err == null`，
    // 两个毛病：① 提示会一直挂着，直到用户随便改一个字段才消失；
    // ② 「测试发送」成功同样满足该判据（它也把 errorMessage 清成 null），于是点测试却亮出「配置已保存」。
    LaunchedEffect(state.isLoading) {
        if (state.isLoading) return@LaunchedEffect
        val action = pending ?: return@LaunchedEffect
        pending = null
        // 失败信息由 UfiErrorBanner 常驻展示（加载失败也走同一处），这里只报成功，避免同一条错误报两遍。
        if (state.errorMessage == null) {
            toastMessage = when (action) {
                EmailNotifyAction.SAVE -> ToastMessage("配置已保存", ToastType.SUCCESS)
                // 测试成功 ≠ 用户以后收得到：测试信走 manual 口径**不受通知总闸约束**，
                // 而自动通知受。core 在 /test 响应里回了 auto_notify_enabled 就是为了说明这件事；
                // 不读它的话用户看到"测试邮件已发送"就以为配好了，实际一条自动通知都不会来。
                EmailNotifyAction.TEST -> if (state.lastTestAutoNotifyEnabled == false) {
                    ToastMessage(
                        text = "测试已发出，但通知总开关当前关闭，自动通知不会发送",
                        type = ToastType.WARNING,
                        subtitle = "在「通知与守护」页打开「全局通知」后，自动通知才会发出"
                    )
                } else {
                    ToastMessage("测试邮件已发送", ToastType.SUCCESS)
                }
            }
        }
    }

    UfiScreenScaffold(title = "邮件通知", navController = navController, showBack = true) { padding ->
        UfiPageBackground(modifier = Modifier.padding(padding)) {
            state.errorMessage?.let { err -> UfiErrorBanner(message = err) }

            val diagnose = state.diagnose

            // ── ① 发送统计。数字来自 core 侧累计计数（只统计真正发起过 SMTP 投递的次数，
            //    黑名单拦截 / 场景未勾选不计），所以"失败 > 0"确实代表 SMTP 有问题。
            UfiSettingsGroup {
                UfiSectionHeader(title = "发送统计")
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.Medium),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    UfiStatItem(
                        value = diagnose?.sent_total?.toString() ?: "--",
                        label = "总发送",
                        modifier = Modifier.weight(1f)
                    )
                    UfiStatItem(
                        value = diagnose?.sent_success?.toString() ?: "--",
                        label = "成功",
                        modifier = Modifier.weight(1f)
                    )
                    UfiStatItem(
                        value = diagnose?.sent_failed?.toString() ?: "--",
                        label = "失败",
                        modifier = Modifier.weight(1f)
                    )
                }
                UfiDivider()
                UfiInfoRow(
                    label = "最近成功",
                    value = diagnose?.last_sent_at?.takeIf { it > 0 }?.let { formatMailTime(it) } ?: "暂无记录"
                )
                // 最近失败只在真的失败过时才占一行：常态下这行是空的，留着等于给用户一个"是不是坏了"的错觉
                diagnose?.last_error?.takeIf { it.isNotBlank() }?.let { err ->
                    UfiInfoRow(label = "最近失败", value = err)
                }
                UfiDivider()
                // 试发放在统计卡末尾（2026-08-30 从页尾的独立按钮挪进来）：
                // 点完就能在上面三个数字里看到 +1，"发了没有 / 成不成功"在同一张卡里闭环。
                // 用行内触发而不是大按钮，跟本页其它行同一节奏，页尾也不再挂一个孤零零的按钮。
                val testing = state.isLoading && pending == EmailNotifyAction.TEST
                // 可用性**不看** `state.isLoading`：那是全页共享的 loading 位，进页面自动
                // loadSmsForwardConfig 时它就是 true，于是本行一进来就是灰的，得等用户随手改个
                // 开关（触发一次 save→reload）才恢复 —— 正是本文件上方注释里记过的坑①。
                // 只用「本行自己的请求在飞」当禁用条件，别人的 loading 与它无关。
                val canTest = enabled && !testing
                UfiSettingsItem(
                    title = "发送测试邮件",
                    description = if (enabled) {
                        "按「新短信」模板渲染，收到即说明 SMTP 通畅"
                    } else {
                        "需先启用邮件通知并填完 SMTP 配置"
                    },
                    enabled = canTest,
                    modifier = Modifier.clickable(enabled = canTest) {
                        pending = EmailNotifyAction.TEST
                        viewModel.tools.testSmsForward()
                    },
                    trailing = {
                        Text(
                            text = if (testing) "发送中…" else "点击试发",
                            style = UfiTextStyles.label,
                            color = if (canTest) palette.accent else palette.textSecondary
                        )
                    }
                )
            }

            // ── ② 设置。输入项全部收进弹窗，列表行只显示摘要 + 右侧值。
            //
            // 2026-09-08 摊平：原来这一段套在一张「邮件通知」分组卡里，而页面标题已经就是
            // 「邮件通知」—— 那个分组头是在重复标题。下面的记录也一样（「发送记录」组里只有
            // 一行「邮件发送记录」）。现在一项一卡，不再有比内容还多的分组头。
            UfiSettingsRowCard {
                UfiSettingsToggle(
                    title = "启用邮件通知",
                    description = if (diagnose?.sendable == true) {
                        "配置完整，可正常发信"
                    } else {
                        "需先在下方填完 SMTP 配置才能发出"
                    },
                    checked = enabled,
                    onCheckedChange = {
                        enabled = it
                        persist(newEnabled = it)
                    }
                )
            }

            UfiSettingsRowCard {
                UfiSettingsValue(
                    title = "SMTP 配置",
                    description = "服务器、端口、账号与收件地址",
                    value = summarizeSmtp(cfg),
                    onClick = { smtpDialogOpen = true }
                )
            }

            // ── 最低级别 · 每日上限 · 今日用量（2026-09-10「规则同构」）──
            //
            // 三行走 NotifyChannelRules.kt 的共用件，与 Webhook / 本机短信逐字一致。
            // 顺序也一致（级别 → 上限 → 用量 → 场景 → 免打扰）：三页看到的是同一组旋钮，
            // 这正是这次改造的目的。metered = false —— 邮件不按条计费。
            NotifyMinLevelRow(
                levels = cfg?.levels ?: emptyList(),
                current = minLevel,
                metered = false,
                // 本页的 cfg 可能还是 null（回读没完成）：那时候 levels 空只是"还没读到"，
                // 不该亮"设备端版本较旧"那句话。
                configLoaded = cfg != null,
                onSelect = {
                    minLevel = it
                    persist(newMinLevel = it)
                }
            )

            NotifyDailyLimitRow(
                dailyLimit = dailyLimit,
                min = cfg?.daily_limit_min ?: 0,
                max = cfg?.daily_limit_max ?: 0,
                onClick = { limitDialogOpen = true }
            )

            NotifyQuotaRow(
                sentToday = cfg?.sent_today ?: 0,
                dailyLimit = dailyLimit,
                quotaRemaining = cfg?.quota_remaining
            )

            UfiSettingsRowCard {
                UfiSettingsValue(
                    title = "邮件转发范围",
                    description = "哪些通知在推送的同时发一封邮件",
                    value = if (scenes.isEmpty()) "仅短信" else "${scenes.size} 个场景",
                    onClick = { scopeDialogOpen = true }
                )
            }

            UfiSettingsRowCard {
                UfiSettingsToggle(
                    title = "遵守免打扰时段",
                    description = when {
                        !dndOn -> "尚未设置免打扰时段，可在「通知管理」中设置"
                        mailRespectDnd -> "免打扰时段内（%02d:00–%02d:00）暂停发送邮件".format(dndStart, dndEnd) +
                            " · " + notifyCriticalOverrideNote(criticalOverrideOn)
                        else -> "免打扰时段内仍会发送邮件"
                    },
                    checked = mailRespectDnd,
                    onCheckedChange = {
                        mailRespectDnd = it
                        prefs.edit().putBoolean(NotificationCenter.KEY_MAIL_RESPECT_DND, it).apply()
                        // 真源在 core（邮件由设备端自己发），本地 prefs 只是镜像
                        viewModel.tools.updateNotificationConfig(mapOf("mail_respect_dnd" to it))
                    }
                )
            }

            UfiSettingsRowCard {
                UfiSettingsToggle(
                    title = "附加设备信息",
                    description = "邮件正文附加电池、CPU、内存等状态",
                    checked = forwardDevInfo,
                    onCheckedChange = {
                        forwardDevInfo = it
                        persist(newDevInfo = it)
                    }
                )
            }

            // 短信拦截（号码黑名单 + 关键词）不在本页：它作用于所有接收通道，不只邮件转发。
            // 入口在 短信页 → 顶栏齿轮 → 短信设置 → 拦截规则。

            // ── ③ 最近投递（2026-09-08 从原「通知历史」双 Tab 页拆出）──
            // 放在本页而不是通知那边：这份记录只对邮件渠道有意义，跟 SMTP 配置在一起才找得到。
            // 摘要与另两条渠道页的入口卡共用 deliveryHistorySummary，三处逐字一致。
            UfiSettingsRowCard {
                UfiSettingsValue(
                    title = DELIVERY_HISTORY_ENTRY_TITLE,
                    description = DELIVERY_HISTORY_ENTRY_DESCRIPTION,
                    value = deliveryHistorySummary(
                        // 三条渠道共用一个 state 槽位，先确认里面装的就是邮件那一份。
                        ready = toolsState.deliveryHistoryLoaded &&
                            toolsState.deliveryHistoryChannel == DELIVERY_CHANNEL_MAIL,
                        total = toolsState.deliveryHistoryTotal,
                        failedTotal = toolsState.deliveryHistoryFailedTotal,
                        skippedTotal = toolsState.deliveryHistorySkippedTotal
                    ),
                    onClick = {
                        navController.navigate(Routes.deliveryHistory(DELIVERY_CHANNEL_MAIL))
                    }
                )
            }

            Spacer(Modifier.height(Spacing.Large))
        }

        if (smtpDialogOpen) {
            SmtpConfigDialog(
                config = cfg,
                onDismiss = { smtpDialogOpen = false },
                onSave = { host, port, user, pass, from, to ->
                    smtpDialogOpen = false
                    persist(host = host, port = port, user = user, pass = pass, from = from, to = to)
                }
            )
        }
        if (scopeDialogOpen) {
            NotifyScenesDialog(
                title = "邮件转发范围",
                leadNote = "勾选哪些内容要发邮件；短信正文与验证码是两个独立开关" +
                    "（同一条短信抓到验证码时按验证码算）",
                selected = scenes,
                criticalOverrideOn = criticalOverrideOn,
                onDismiss = { scopeDialogOpen = false },
                onSave = { next ->
                    scenes = next
                    scopeDialogOpen = false
                    persist(newScenes = next)
                }
            )
        }
        if (limitDialogOpen) {
            NotifyDailyLimitDialog(
                current = dailyLimit,
                min = cfg?.daily_limit_min ?: 0,
                max = cfg?.daily_limit_max ?: 0,
                // 本页的写动作共用 state.isLoading，所以只在"是本页发起的保存"时算 saving
                saving = state.isLoading && pending == EmailNotifyAction.SAVE,
                onDismiss = { limitDialogOpen = false },
                onSave = { limit ->
                    dailyLimit = limit
                    limitDialogOpen = false
                    persist(newDailyLimit = limit)
                }
            )
        }

        UfiToastHost(toastMessage = toastMessage, onDismiss = { toastMessage = null })
    }
}

/** 本页会触发的写动作。用于把共用的 `SmsForwardState.isLoading` 归属到具体按钮。 */
private enum class EmailNotifyAction { SAVE, TEST }

/**
 * SMTP 配置弹窗（暂存-确认）。
 *
 * 密码框初值恒为空串：core 的 GET 只回 `smtp_pass_set` 布尔，拿不到原文，
 * 所以"留空"必须表示"不修改"，不能表示"清空密码"。
 */
@Composable
private fun SmtpConfigDialog(
    config: SmsForwardConfig?,
    onDismiss: () -> Unit,
    onSave: (host: String, port: Int, user: String, pass: String, from: String, to: String) -> Unit
) {
    var host by remember { mutableStateOf(config?.smtp_host ?: "") }
    var port by remember { mutableStateOf((config?.smtp_port ?: 465).toString()) }
    var user by remember { mutableStateOf(config?.smtp_user ?: "") }
    var pass by remember { mutableStateOf("") }
    var from by remember { mutableStateOf(config?.smtp_from ?: "") }
    var to by remember { mutableStateOf(config?.smtp_to ?: "") }

    UfiScrollableDialog(
        visible = true,
        onDismiss = onDismiss,
        title = "SMTP 配置",
        // 确认位必须是 size = Standard（默认档）：它和 dismiss 位一样吃 Spacing.ButtonHeight
        // 且默认 fillWidth。若改成 size = Small（SmallButtonHeight 更矮、默认不铺满），
        // 而 UfiScrollableDialog 的两个 weight(1f) Box **没有**固定高度（UfiCustomDialog 才有），
        // min 约束传不下去，于是出现「取消高 / 保存矮」的错位。全仓其余弹窗也都是 Primary+Secondary。
        confirmButton = {
            UfiButton(
                text = "保存",
                onClick = { onSave(host, port.toIntOrNull() ?: 465, user, pass, from, to) }
            )
        },
        dismissButton = {
            UfiButton(variant = UfiButtonVariant.Secondary, text = "取消", onClick = onDismiss)
        }
    ) {
        UfiDialogBody {
            UfiFieldRow {
                UfiTextField(
                    value = host, onValueChange = { host = it },
                    label = "SMTP 服务器", modifier = Modifier.weight(2f)
                )
                UfiDigitField(
                    value = port, onValueChange = { port = it },
                    label = "端口", modifier = Modifier.weight(1f)
                )
            }
            UfiTextField(value = user, onValueChange = { user = it }, label = "用户名")
            UfiPasswordField(
                value = pass, onValueChange = { pass = it },
                label = if (config?.smtp_pass_set == true) "密码（留空不修改）" else "密码"
            )
            UfiTextField(
                value = from, onValueChange = { from = it },
                label = "发件地址（留空用用户名）"
            )
            UfiTextField(value = to, onValueChange = { to = it }, label = "收件地址")
        }
    }
}

/** SMTP 摘要：`smtp.qq.com:465`；缺服务器或收件地址都算未配置完。 */
private fun summarizeSmtp(config: SmsForwardConfig?): String = when {
    config == null -> "加载中"
    config.smtp_host.isBlank() -> "未配置"
    config.smtp_to.isBlank() -> "缺收件地址"
    else -> "${config.smtp_host}:${config.smtp_port}"
}

private fun formatMailTime(ts: Long): String =
    java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
        .format(java.util.Date(ts))

// 可选场景（`sceneId to 中文标签`）的映射已搬到 [NOTIFY_SCENE_LABELS]（NotifySceneLabels.kt）：
// Webhook 渠道勾的是同一套场景 id，各留一份必然分叉。
// 这里是**邮件**的场景白名单（core 的 `SmsForwardConfig.scenes`），与 app 的系统通知开关
// 是两件事，所以不遍历 `NotifyScene.entries`。



