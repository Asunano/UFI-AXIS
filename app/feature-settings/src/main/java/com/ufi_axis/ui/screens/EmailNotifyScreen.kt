package com.ufi_axis.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import com.ufi_axis.data.model.SmsForwardConfig
import com.ufi_axis.data.notification.NotifyScene
import com.ufi_axis.ui.components.common.*
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
    LaunchedEffect(Unit) { viewModel.tools.loadSmsForwardConfig() }
    // 诊断跟着配置走：保存成功后 ToolsModule 会自动重载 config，这里随之重新拉一次诊断，
    // 于是统计数字与「可发信」在保存/测试后立刻刷新，不需要用户手动下拉。
    LaunchedEffect(state.config) { if (state.config != null) viewModel.tools.loadSmsForwardDiagnose() }

    val cfg = state.config
    var enabled by remember(cfg) { mutableStateOf(cfg?.enabled ?: false) }
    var forwardDevInfo by remember(cfg) { mutableStateOf(cfg?.forward_dev_info ?: false) }
    var scenes by remember(cfg) { mutableStateOf(cfg?.scenes ?: emptyList()) }
    var smtpDialogOpen by remember { mutableStateOf(false) }
    var scopeDialogOpen by remember { mutableStateOf(false) }

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
                // 黑名单 UI 已于 2026-08-30 从本页移除（将并入短信页统一管理），
                // 但字段仍在载荷里 —— 必须**原样回传**，传 emptyList() 会静默清空设备上的黑名单。
                blacklist = cfg?.blacklist ?: emptyList(),
                scenes = newScenes
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
            toastMessage = ToastMessage(
                when (action) {
                    EmailNotifyAction.SAVE -> "配置已保存"
                    EmailNotifyAction.TEST -> "测试邮件已发送"
                },
                ToastType.SUCCESS
            )
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
            UfiSettingsGroup {
                UfiGroupHeader("邮件通知")
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
                UfiDivider()
                UfiSettingsValue(
                    title = "SMTP 配置",
                    description = "服务器、端口、账号与收件地址",
                    value = summarizeSmtp(cfg),
                    onClick = { smtpDialogOpen = true }
                )
                UfiDivider()
                UfiSettingsValue(
                    title = "邮件转发范围",
                    description = "哪些通知在推送的同时发一封邮件",
                    value = if (scenes.isEmpty()) "仅短信" else "${scenes.size} 个场景",
                    onClick = { scopeDialogOpen = true }
                )
                UfiDivider()
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

            // 「短信黑名单」已于 2026-08-30 从本页移除：它只作用于短信转发，
            // 与短信页的过滤逻辑重复，后续统一到短信界面管理。
            // 注意保存时仍需回传 cfg.blacklist（见上方 persist）。

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
            MailScopeDialog(
                selected = scenes,
                onDismiss = { scopeDialogOpen = false },
                onSave = { next ->
                    scenes = next
                    scopeDialogOpen = false
                    persist(newScenes = next)
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

/** 邮件转发范围弹窗（暂存-确认）：多选 chip，点「保存」才下发。 */
@Composable
private fun MailScopeDialog(
    selected: List<String>,
    onDismiss: () -> Unit,
    onSave: (List<String>) -> Unit
) {
    val palette = LocalResolvedPalette.current
    val draft = remember { mutableStateListOf<String>().apply { addAll(selected) } }

    UfiCustomDialog(
        visible = true,
        onDismiss = onDismiss,
        title = "邮件转发范围",
        confirmButton = {
            UfiButton(text = "保存", onClick = { onSave(draft.toList()) })
        },
        dismissButton = {
            UfiButton(variant = UfiButtonVariant.Secondary, text = "取消", onClick = onDismiss)
        }
    ) {
        UfiDialogBody {
            Text(
                "勾选哪些内容要发邮件；短信正文与验证码是两个独立开关（同一条短信抓到验证码时按验证码算）",
                style = MaterialTheme.typography.bodySmall,
                color = palette.textSecondary
            )

            UfiMultiChipSelector(
                options = MAIL_SCENES.map { (scene, label) -> scene.sceneId to label },
                selectedValues = draft.toSet(),
                onToggle = { id -> if (draft.contains(id)) draft.remove(id) else draft.add(id) }
            )
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

/**
 * 可选邮件转发场景（顺序即 chip 顺序）。
 *
 * 不直接遍历 `NotifyScene.entries`：这里是**邮件**的场景白名单（core 的 `SmsForwardConfig.scenes`），
 * 与 app 的系统通知开关是两件事，能发邮件的场景只有这几个。
 * 短信正文与验证码是 core 短信链路的两个独立开关（`forwardSms` 按是否抓到验证码分流）。
 */
private val MAIL_SCENES: List<Pair<NotifyScene, String>> = listOf(
    NotifyScene.SMS to "短信正文",
    NotifyScene.VERIFICATION_CODE to "验证码",
    NotifyScene.ALERT to "阈值告警",
    NotifyScene.CONNECTIVITY to "离线/上线",
    NotifyScene.TRAFFIC_80 to "流量预警",
    NotifyScene.DOWNLOAD to "下载结束",
    NotifyScene.TUNNEL to "隧道异常",
    NotifyScene.DEVICE_EVENTS to "设备事件"
)

