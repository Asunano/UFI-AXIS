package com.ufi_axis.ui.screens

import android.content.Context
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Http
import androidx.compose.material.icons.filled.Sms
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavHostController
import com.ufi_axis.data.model.LocalSmsConfigResponse
import com.ufi_axis.data.model.SmsForwardConfig
import com.ufi_axis.data.model.WebhookConfigResponse
import com.ufi_axis.data.notification.NotificationCenter
import com.ufi_axis.ui.components.common.*
import com.ufi_axis.ui.navigation.Routes
import com.ufi_axis.ui.theme.Spacing
import com.ufi_axis.viewmodel.MainViewModel

/**
 * 「推送渠道」总览页（2026-09-09）。
 *
 * 回答一个问题：**通知会被送到哪里去**。每条**可配置**的渠道一张卡：
 *
 * | 渠道 | 配置在哪 | 状态来源 |
 * |------|---------|---------|
 * | 邮件 | [EmailNotifyScreen]（既有页，不重做） | `GET /api/sms-forward/config` |
 * | Webhook | [WebhookNotifyScreen] | `GET /api/notify/webhook/config` |
 * | 本机短信 | [LocalSmsNotifyScreen] | `GET /api/notify/sms/config` |
 *
 * WS 实时推送**不在这里列**：它随 core 常开、没有任何可配项，摆一行只读说明只会让用户
 * 以为自己漏设了什么。「客户端要不要弹通知」的开关本来就在「通知与守护」那一页。
 *
 * 三条渠道的排序不是随意的：**按"发出去要付的代价"从低到高**（邮件与 Webhook 免费走数据网，
 * 本机短信按条计费走信令网）。花钱的那条放最后，顺带让它的摘要（"今日 2/5"）离费用提示最近。
 *
 * ## 状态文案的口径
 *
 * 读不到配置时一律显示"加载中 / 读取失败"，**绝不显示"未启用"** ——
 * 那会让用户以为设置丢了，然后重填一遍把真配置覆盖掉。
 * "配置未填完"取自 core 自己的判据（Webhook / 本机短信读 `configured`，邮件读 `sendable`），
 * app 侧不再算第二套。
 *
 * **总闸也算一道闸**（2026-09-11）：`master_enabled` 关闭时 core 对三条渠道一律回
 * `Skipped(MASTER_OFF)`，一条自动通知都不会发。所以三行都要把它说出来，
 * 否则"已启用"就是假开关 —— 用户只有在点了「发送测试」之后才会知道。
 */
@Composable
fun PushChannelsScreen(viewModel: MainViewModel, navController: NavHostController) {
    val mailState by viewModel.smsForwardState.collectAsState()
    val webhookState by viewModel.webhookState.collectAsState()
    val localSmsState by viewModel.localSmsState.collectAsState()

    // 总闸取值与「通知与守护」页的 masterOn **同源**（同一个 key、同一个默认值 false）：
    // 抄一个 default = true 会让从未碰过开关的用户在这里看到"已启用"，而实际一条都发不出。
    val context = LocalContext.current
    val prefs = remember {
        context.getSharedPreferences(NotificationCenter.PREFS_NAME, Context.MODE_PRIVATE)
    }
    var masterOn by remember {
        mutableStateOf(prefs.getBoolean(NotificationCenter.KEY_NOTIFY_MASTER, false))
    }

    // 三份配置都只为摘要，各拉一次；失败各自兜底成占位文案，不阻塞导航。
    LaunchedEffect(Unit) {
        viewModel.tools.loadSmsForwardConfig()
        viewModel.tools.loadWebhookConfig()
        viewModel.tools.loadLocalSmsConfig()
    }
    // 从二级页返回后重查：否则改完 Webhook 回来这一页的状态还停在进页面那一刻的快照上。
    // 总闸不是可观察状态（存在 prefs 里，且入口在另一页），所以也在这里重读一次。
    rememberResumeRefresh {
        viewModel.tools.loadSmsForwardConfig()
        viewModel.tools.loadWebhookConfig()
        viewModel.tools.loadLocalSmsConfig()
        masterOn = prefs.getBoolean(NotificationCenter.KEY_NOTIFY_MASTER, false)
    }

    UfiScreenScaffold(title = "推送渠道", navController = navController, showBack = true) { padding ->
        UfiPageBackground(modifier = Modifier.padding(padding)) {
            // 三条渠道读失败都要说出来：这一页对应的状态位因此不可信，
            // 沉默会让用户把"读取失败"当成"未启用"。
            mailState.errorMessage?.let { err -> UfiErrorBanner(message = err) }
            webhookState.errorMessage?.let { err -> UfiErrorBanner(message = err) }
            localSmsState.errorMessage?.let { err -> UfiErrorBanner(message = err) }

            UfiSettingsRowCard {
                UfiSettingsValue(
                    title = "邮件",
                    description = "SMTP 发信 · 场景勾选 · 发送记录",
                    value = summarizeMailChannel(mailState.loaded, mailState.config, masterOn),
                    icon = Icons.Default.Email,
                    onClick = { navController.navigate(Routes.DETAIL_EMAIL_NOTIFY) }
                )
            }

            UfiSettingsRowCard {
                UfiSettingsValue(
                    title = "Webhook",
                    description = "Bark / ntfy / 群机器人 / 自建服务，按模板发 HTTP 请求",
                    value = summarizeWebhookChannel(webhookState.loaded, webhookState.config, masterOn),
                    icon = Icons.Default.Http,
                    onClick = { navController.navigate(Routes.DETAIL_WEBHOOK_NOTIFY) }
                )
            }

            UfiSettingsRowCard {
                UfiSettingsValue(
                    title = "本机短信",
                    // 副文案必须先说代价：另两条免费，这条按条计费，用户点进去之前就该知道。
                    description = "由本机 SIM 发出，会产生短信费用；网络中断或套餐用尽时仍可送达",
                    value = summarizeLocalSmsChannel(localSmsState.loaded, localSmsState.config, masterOn),
                    icon = Icons.Default.Sms,
                    onClick = { navController.navigate(Routes.DETAIL_LOCAL_SMS_NOTIFY) }
                )
            }

            Spacer(Modifier.height(Spacing.Large))
        }
    }
}

/**
 * 「已启用」那一档的完整取值。
 *
 * 总闸关着时**不能只说"已启用"**：core 此时对三条渠道一律回 `Skipped(MASTER_OFF)`，
 * 这条渠道配得再对也不会发出一条自动通知。口径与各渠道页的免打扰 / 兜底提示一致 ——
 * 状态位后面接一句"为什么它现在不生效"，而不是另造一档状态。
 *
 * @param extra 渠道自己要补的内容（本机短信的今日用量）。
 */
private fun enabledValue(masterOn: Boolean, extra: String? = null): String {
    val head = if (extra == null) "已启用" else "已启用 · $extra"
    return if (masterOn) head else "$head · 总开关关闭，自动通知不会发出"
}

/**
 * 邮件渠道摘要。
 *
 * 判据只用 `GET /api/sms-forward/config` 的字段，不额外调 diagnose ——
 * 这一页只要"配没配、开没开"，为一行摘要多打一个诊断请求不值得。
 *
 * 「配置不完整」读的是 core 新回的 [SmsForwardConfig.sendable]（= `isSendable()`：
 * enabled + 服务器 + 用户名 + 密码 + 收件地址五项）。**app 侧不再自己判 SMTP 字段** ——
 * 原来那套 `smtp_host.isBlank() || smtp_to.isBlank()` 只看两项，于是"填了服务器和收件人、
 * 漏了用户名或密码"时这一行显示"已启用"而一封都发不出去，还与同页另两条渠道
 * （读 `configured`）和邮件详情页（读 `diagnose.sendable`）凑成三种口径。
 *
 * @param loaded 单调闩锁「已完成过一次加载尝试」，口径同另两条渠道：
 *   它为 false 时只能说"加载中"；为 true 而 [config] 仍是 null，说明这次真的读失败了。
 */
private fun summarizeMailChannel(
    loaded: Boolean,
    config: SmsForwardConfig?,
    masterOn: Boolean
): String = when {
    !loaded -> "加载中"
    config == null -> "读取失败"
    !config.enabled -> "未启用"
    !config.sendable -> "配置不完整"
    else -> enabledValue(masterOn)
}

/**
 * Webhook 渠道摘要。
 *
 * @param loaded 单调闩锁「已完成过一次加载尝试」。它为 false 时只能说"加载中"；
 *   为 true 而 [config] 仍是 null，说明这次真的读失败了（banner 里已经写了原因）。
 * @param config `configured` 直接来自 core 渠道的 `isConfigured()`，
 *   包含"预设里的 `<占位>` 还没换成真值"这种 app 侧看不出来的情况。
 */
private fun summarizeWebhookChannel(
    loaded: Boolean,
    config: WebhookConfigResponse?,
    masterOn: Boolean
): String = when {
    !loaded -> "加载中"
    config == null -> "读取失败"
    !config.enabled -> "未启用"
    config.url.isBlank() -> "未填地址"
    !config.configured -> "配置未填完"
    else -> enabledValue(masterOn)
}

/**
 * 本机短信渠道摘要。前四档与另两条渠道**逐字同口径**（加载中 / 读取失败 / 未启用 / 未填号码 /
 * 配置未填完），只有最后一档不同：这条渠道花钱，所以"已启用"后面必须带**今日用量**。
 *
 * 为什么用量要出现在总览页而不是只在详情页：用户来这一页的常见动机就是"我今天是不是把
 * 短信额度用完了"。要点进去才看得到的话，这一页就只是一个跳转列表。
 *
 * @param config `configured` 直接来自 core 渠道的 `isConfigured()`（开关 + 号码合法）。
 *   用量走 [notifyQuotaValue]（与三条渠道页里那一行同一个格式化函数）：本渠道的
 *   `daily_limit_min` 是 1、不会出现"不限"，但格式化只留一份，将来 core 放宽了也不用改这里。
 */
private fun summarizeLocalSmsChannel(
    loaded: Boolean,
    config: LocalSmsConfigResponse?,
    masterOn: Boolean
): String = when {
    !loaded -> "加载中"
    config == null -> "读取失败"
    !config.enabled -> "未启用"
    config.target_number.isBlank() -> "未填号码"
    !config.configured -> "配置未填完"
    else -> enabledValue(masterOn, "今日 ${notifyQuotaValue(config.sent_today, config.daily_limit)}")
}
