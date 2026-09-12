package com.ufi_axis.ui.screens

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.navigation.NavHostController
import com.ufi_axis.data.notification.GuardScheduler
import com.ufi_axis.data.notification.NotificationCenter
import com.ufi_axis.data.notification.NotificationConfigClient
import com.ufi_axis.data.notification.NotifyDispatchReceiver
import com.ufi_axis.data.notification.NotifyPrefs
import com.ufi_axis.ui.components.common.*
import com.ufi_axis.ui.navigation.Routes
import com.ufi_axis.ui.theme.LocalResolvedPalette
import com.ufi_axis.ui.theme.Spacing
import com.ufi_axis.ui.theme.UfiTextStyles
import com.ufi_axis.viewmodel.MainViewModel
import com.ufi_axis_core.contract.Alerts

/**
 * 「通知与守护」聚合入口页。
 *
 * ## 2026-09-08 重构：一个总闸 + 三个入口
 *
 * 上一版这一页同屏摆了两个都长得像总开关的东西，谁管谁分不清：
 * - Hero 卡「告警总开关」其实是 `AlertConfig.enabled` —— **设备端**告警引擎（产不产生告警）
 * - 中间的「全局通知」才是 `master_enabled` —— **客户端**投递总闸（产生了要不要提醒你）
 *
 * 加上「告警类通知」（分类）、免打扰、测试通知、四条入口全挤在一页，等于把三个不同层级
 * 的东西平铺在一起。现在：
 *
 * - **Hero 只放真正的通知主管**：全局通知（L1）。它一关，状态栏与邮件两条渠道全静默。
 * - **紧跟一张「严重事件兜底」卡**（2026-09-10）：它同样是 L1 级、跨渠道的一道口子，
 *   与总闸是相邻的两句话（总闸决定"发不发"，兜底决定"严重事件能不能越过下面几道闸"）。
 *   不放进「通知管理」是因为那一页是 L3 分类层，会被读成"只影响状态栏"；
 *   不放进「设备告警」是因为那一页的真源是 `AlertConfig`（设备产不产生告警），
 *   与本字段所属的 `NotificationConfig` 是两回事。
 * - 四个入口，各自内部自洽，按"通知的生命周期"排：
 *   - 设备告警 → [Routes.DETAIL_ALERT_SETTINGS]（引擎开关 / 各类告警与阈值）
 *   - 通知管理 → [Routes.DETAIL_NOTIFY_MANAGE]（分类开关 / 免打扰 / 测试 / 系统通知记录）
 *   - 推送渠道 → [Routes.DETAIL_PUSH_CHANNELS]（邮件 / Webhook / 本机短信 / 实时推送）
 *   - 后台守护 → [Routes.DETAIL_BACKGROUND_GUARD]
 *
 * 2026-09-09：中间那条原本是「邮件通知」（直达 [Routes.DETAIL_EMAIL_NOTIFY]）。
 * Webhook 渠道上线后改为指向渠道总览 —— 邮件仍在那一页的第一行，路径只多一跳；
 * 而"每加一条渠道就往本页塞一张卡"会把这页变成配置大杂烩。
 *
 * ## 2026-09-10：「设备告警」入口从监控设置迁回本页
 *
 * 它 2026-09-08 曾被移到「监控中心 → 监控设置」，理由是"告警的产物是监控中心的事件流"。
 * 那个理由没错，但用户的实际动线是**先想到"我要收到提醒"**：调完这一页的总闸、分类、渠道，
 * 还要跳到监控设置里才能决定"设备到底检测哪几类、阈值多少"，中间隔着两级导航。
 * 现在入口只留本页一处（监控设置里那张卡已删）—— 同一个目标两个入口，
 * 用户只会问"这两个有什么区别"（口径同下面「推送渠道」那条注释）。
 *
 * **它是本页唯一不跟着总闸置灰的入口**：告警引擎的产物首先是设备侧的事件流与监控中心的列表，
 * 通知只是其中一条出口。总闸关着时"检测哪几类、阈值多少"仍然是有意义的设置 ——
 * 跟着置灰会让用户误以为关掉通知就等于关掉了检测。
 *
 * 每条入口右侧显示真实摘要而不是空串 —— 目的是"不点进去也知道当前是什么状态"。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationsGuardScreen(
    viewModel: MainViewModel,
    navController: NavHostController
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("ufi_axis_prefs", Context.MODE_PRIVATE) }

    val guardState by viewModel.backgroundGuard.state.collectAsState()
    val mailState by viewModel.smsForwardState.collectAsState()
    val webhookState by viewModel.webhookState.collectAsState()
    // 「设备告警」入口摘要用的两份数据。优先取 prefs 仓库那份镜像（带 version 守门），
    // 它还没落地时退回一次性拉取的 alertsState —— 口径与 AlertSettingsScreen 的 `config` 一致。
    val alertPrefs by viewModel.alertPrefs.configFlow.collectAsState()
    val alertsState by viewModel.tools.alertsState.collectAsState()
    val alertConfig = alertPrefs ?: alertsState.config

    // 摘要需要的几份数据都不是本页自己的开关，进来时各拉一次。
    // 全部允许失败（各自内部静默兜底）—— 摘要读不到就退化成占位文案，不阻塞导航。
    LaunchedEffect(Unit) {
        viewModel.tools.refreshNotificationConfig()
        viewModel.tools.loadSmsForwardConfig()
        viewModel.tools.loadWebhookConfig()
        viewModel.tools.loadAlerts()
        viewModel.backgroundGuard.refresh()
    }

    // ── L1 全局通知总闸（KEY_NOTIFY_MASTER）──
    //
    // 默认值必须与闸门一致（闸门是 `NotificationCenter.notify` 里的
    // `switchOn(KEY_NOTIFY_MASTER, false)`）。读 default=true 会让从未碰过开关的用户看到
    // "已开启" → 不去点它 → Android 13+ 的 POST_NOTIFICATIONS 永远不申请（全仓只有本页会弹
    // 权限框）→ 通知一条也发不出来，且 notify() 静默 return，日志里什么都看不到。
    var masterOn by remember { mutableStateOf(prefs.getBoolean(NotificationCenter.KEY_NOTIFY_MASTER, false)) }

    // ── 「假开关」防线 ──
    //
    // 教训：开关只反映 prefs，与**系统是否真的放行**零关联，于是出现"界面写着已开启、
    // 实际一条都发不出去"（notify() 在 areNotificationsEnabled() 处静默 return）。
    // 现在真实状态 = prefs && 系统放行，二者缺一都显示为关。
    //
    // 用 areNotificationsEnabled() 而不只是 checkSelfPermission：用户还可能在系统设置里
    // 关掉本应用的通知（权限仍是 granted），那种情况同样一条都发不出去。
    val readNotifAllowed = { NotificationManagerCompat.from(context).areNotificationsEnabled() }
    var notifAllowed by remember { mutableStateOf(readNotifAllowed()) }

    // 分类开启数：总闸开着但一个分类都没开 = 一条通知都不会来，必须在摘要里说出来。
    var categoriesOn by remember {
        mutableIntStateOf(NotificationCenter.CATEGORY_KEYS.count { prefs.getBoolean(it, false) })
    }
    var dndOn by remember { mutableStateOf(prefs.getBoolean(NotificationCenter.KEY_DND_ENABLED, false)) }
    var dndStart by remember {
        mutableIntStateOf(
            prefs.getInt(NotificationCenter.KEY_DND_START_HOUR, NotificationCenter.DEFAULT_DND_START_HOUR)
        )
    }
    var dndEnd by remember {
        mutableIntStateOf(prefs.getInt(NotificationCenter.KEY_DND_END_HOUR, NotificationCenter.DEFAULT_DND_END_HOUR))
    }

    // ── 严重事件兜底（2026-09-10）──
    //
    // 真源是 core 的 `NotificationConfig.critical_override_enabled`，**默认开**。
    // 读值走 NotifyPrefs.switchOn 而不是 prefs.getBoolean：默认值只在
    // [NotificationCenter.DEFAULT_CRITICAL_OVERRIDE] 定义一处，本页抄一个 false 就是假开关。
    var criticalOverrideOn by remember {
        mutableStateOf(
            NotifyPrefs.switchOn(
                context,
                NotificationCenter.KEY_CRITICAL_OVERRIDE,
                NotificationCenter.DEFAULT_CRITICAL_OVERRIDE
            )
        )
    }

    // ── 「设备告警」摘要：真正在检测的类型数 ──
    //
    // 判据必须与 AlertSettingsScreen 那几行的 `checked` 完全一致，否则摘要说"6 类开启"、
    // 点进去只看到 3 个开着。所以：`perType[type] == true`（缺键 = 关）**且**闸门放行 ——
    // 后者借 [alertTypeGateEnabled]（全仓一份，三个带二级闸门的分类靠它）。
    //
    // 那两个闸门存在 prefs 里，不是可观察状态；用一个自增戳在 ON_RESUME 时触发重算，
    // 比在本页再抄一遍 KEY_TRAFFIC_80_NOTIF / KEY_DEVICE_EVENTS_NOTIF 的映射安全。
    var alertGateStamp by remember { mutableIntStateOf(0) }
    val alertTypesOn = remember(alertConfig, alertGateStamp) {
        val perType = alertConfig?.perType ?: emptyMap()
        ALERT_TYPE_KEYS.count { perType[it] == true && alertTypeGateEnabled(prefs, it) }
    }

    // 从系统设置或二级页返回后必须重检：否则开关与摘要停在进页面那一刻的快照上。
    rememberResumeRefresh {
        notifAllowed = readNotifAllowed()
        masterOn = prefs.getBoolean(NotificationCenter.KEY_NOTIFY_MASTER, false)
        categoriesOn = NotificationCenter.CATEGORY_KEYS.count { prefs.getBoolean(it, false) }
        alertGateStamp++
        dndOn = prefs.getBoolean(NotificationCenter.KEY_DND_ENABLED, false)
        dndStart = prefs.getInt(
            NotificationCenter.KEY_DND_START_HOUR,
            NotificationCenter.DEFAULT_DND_START_HOUR
        )
        dndEnd = prefs.getInt(NotificationCenter.KEY_DND_END_HOUR, NotificationCenter.DEFAULT_DND_END_HOUR)
        criticalOverrideOn = NotifyPrefs.switchOn(
            context,
            NotificationCenter.KEY_CRITICAL_OVERRIDE,
            NotificationCenter.DEFAULT_CRITICAL_OVERRIDE
        )
    }

    /**
     * 通知是否真的能送达 = 本机总闸 **且** 系统放行。
     *
     * Hero 的开关显示态、下面两个入口的可用性都用它 —— 一份判据，界面不会自相矛盾。
     */
    val gateOn = masterOn && notifAllowed

    /** 打开系统「应用通知」设置页 —— 权限被二次拒绝后，这是唯一的修复入口。 */
    val openSystemNotifSettings = {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            )
        }
    }

    /**
     * 打开总闸：写本机 prefs → 推镜像快照 → 重排守护 → 下发 core。四步都不能少。
     *
     * - prefs 必须先写（`startKeepAlive` 与 `syncSchedule` 都要读它）
     * - 快照必须推：`:ufi_notify` 读开关只认自己那份 mirror_ 副本
     * - `startKeepAlive` 的闸门在函数内部（只看 guard_foreground_keepalive_enabled），
     *   保活没开时是 no-op；调用点保留是为了覆盖"保活早就开着、但当时因缺权限没起来"
     * - `syncSchedule` 必须在这里显式调：总闸参与周期任务的排期条件，而下发 core 之后的
     *   回显走 `applyRemote`，那里开头有 `if (local == remote) return` —— 本机 pref 已经先写好，
     *   回显值与本地相同就会提前 return，指望它补是不行的。
     */
    val enableMaster = {
        prefs.edit().putBoolean(NotificationCenter.KEY_NOTIFY_MASTER, true).apply()
        masterOn = true
        notifAllowed = readNotifAllowed()
        NotificationConfigClient.startKeepAlive(context)
        GuardScheduler.syncSchedule(context)
        NotifyDispatchReceiver.dispatchSwitchSnapshot(context)
        viewModel.tools.updateNotificationConfig(mapOf("master_enabled" to true))
    }

    // Android 13+ 通知运行时权限。授权成功这条路径同样是"打开了全局通知总闸"，
    // 也要下发 core —— 少写一次，别端看到的开关状态就和这里不一样。
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            // 只写总闸：分类开关是用户自己的选择，顺手替他打开等于
            // "我以为只开了总闸，结果开始收到某类通知"。
            enableMaster()
        } else {
            // 用户拒绝后开关必须弹回关并明说原因：Android 13 二次拒绝会**立即**回调 denied
            // （系统不再弹框），不给提示就表现成"点开关完全没反应"。
            notifAllowed = readNotifAllowed()
            android.widget.Toast.makeText(
                context,
                "未授予通知权限，通知无法送达；请在系统「应用通知」中开启",
                android.widget.Toast.LENGTH_LONG
            ).show()
            openSystemNotifSettings()
        }
    }

    UfiScreenScaffold(title = "通知与守护", navController = navController, showBack = true) { padding ->
        UfiPageBackground(modifier = Modifier.padding(padding)) {

            // ── Hero：全局通知总闸 + 三项状态。第一屏就回答「它现在在工作吗」 ──
            NotificationsHeroCard(
                masterOn = masterOn,
                notifAllowed = notifAllowed,
                categoriesOn = categoriesOn,
                onRowClick = { if (masterOn && !notifAllowed) openSystemNotifSettings() },
                onCheckedChange = { on ->
                    if (on) {
                        val hasPerm = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                            ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.POST_NOTIFICATIONS
                            ) == PackageManager.PERMISSION_GRANTED
                        if (!hasPerm) {
                            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            return@NotificationsHeroCard
                        }
                        // 权限有了但系统里把本应用通知关了（权限 granted ≠ 允许发通知），
                        // 这种只能去系统设置改，运行时权限框不会再出现。
                        if (!readNotifAllowed()) {
                            notifAllowed = false
                            android.widget.Toast.makeText(
                                context,
                                "系统已禁止本应用通知，请在系统「应用通知」中开启",
                                android.widget.Toast.LENGTH_LONG
                            ).show()
                            openSystemNotifSettings()
                            return@NotificationsHeroCard
                        }
                        enableMaster()
                    } else {
                        // 只写总闸：分类保持用户自己的选择，下次打开总闸时不用重新勾一遍。
                        //
                        // 这里**不能**顺手 stopKeepAlive —— 那是拿 A 开关去停 B 开关授权的服务：
                        // 用户还开着「前台服务保活」，常驻通知却消失，且 `:ufi_notify` 负责的
                        // 短信 / 验证码实时推送会一起失效（它是那条 WS 频道的唯一订阅方）。
                        prefs.edit().putBoolean(NotificationCenter.KEY_NOTIFY_MASTER, false).apply()
                        masterOn = false
                        // 总闸参与后台守护周期任务的排期条件：不重排一次，Worker 会继续
                        // 每 15/30/60 分钟联网拉 50 条告警，然后在 notify 的总闸处被整条丢掉。
                        // 用户此时也进不去「后台守护」页关它（那个入口已随总闸置灰）。
                        GuardScheduler.syncSchedule(context)
                        NotifyDispatchReceiver.dispatchSwitchSnapshot(context)
                        viewModel.tools.updateNotificationConfig(mapOf("master_enabled" to false))
                    }
                },
                categorySummary = "$categoriesOn/${NotificationCenter.CATEGORY_KEYS.size}",
                dndSummary = if (dndOn) formatDndWindowShort(dndStart, dndEnd) else "关闭",
                guardSummary = if (guardState.enabled) "${guardState.intervalMinutes} 分钟" else "关闭"
            )

            // ── 严重事件兜底（2026-09-10）──
            //
            // ## 为什么放在这一页，而不是「通知管理」或「设备告警引擎」
            //
            // 这一页的职责是 L1：**跨渠道的总闸**。而兜底恰恰是跨渠道、跨分类的一道口子 ——
            // 它同时放宽状态栏、邮件、Webhook、本机短信四处的免打扰 / 场景 / 最低级别判定。
            //  - 「通知管理」是 L3（按分类勾选 + 免打扰时段），兜底比那一层高，放进去会被读成
            //    "只影响状态栏通知"；
            //  - 「设备告警引擎」（AlertSettingsScreen）管的是 `AlertConfig` —— 设备**要不要产生**
            //    告警，与本字段（`NotificationConfig`）是两份真源。把一个 NotificationConfig
            //    的字段摆进那一页，等于告诉用户"关掉它设备就不报警了"，那是错的。
            //
            // 它和总闸是相邻的两句话（"总闸关了一条都不发" / "总闸开着时严重事件多一条通路"），
            // 所以就贴在 Hero 下面，而不是塞进任何一个二级页。
            //
            // 置灰条件同三个入口：总闸关着（或系统没放行）时兜底一条也送不出去 ——
            // 承诺里写明了"不穿透总开关"，让它在那种状态下可点就是给用户一个空转的旋钮。
            UfiSettingsRowCard {
                UfiSettingsItem(
                    title = "严重事件兜底",
                    description = when {
                        !gateOn -> "总开关关闭时兜底也不发送：严重事件不穿透「全局通知」"
                        criticalOverrideOn ->
                            "级别为「严重」的事件会穿透免打扰时段、渠道未勾选的场景与渠道的最低级别门槛；" +
                                "但不穿透总开关、渠道配置不完整与当日配额已用尽"
                        else ->
                            "已关闭：严重事件与其它事件一样，受免打扰时段、场景勾选与最低级别约束"
                    },
                    enabled = gateOn,
                    trailing = {
                        UfiSwitch(
                            checked = criticalOverrideOn,
                            enabled = gateOn,
                            onCheckedChange = { on ->
                                prefs.edit()
                                    .putBoolean(NotificationCenter.KEY_CRITICAL_OVERRIDE, on)
                                    .apply()
                                criticalOverrideOn = on
                                // `:ufi_notify` 是状态栏通知的唯一发射者，它的免打扰突破判据
                                // 读的是自己那份 mirror_ 副本 —— 不推快照就会继续按旧值响铃。
                                NotifyDispatchReceiver.dispatchSwitchSnapshot(context)
                                // 三条推送渠道的兜底判定在 core，那边才是真源
                                viewModel.tools.updateNotificationConfig(
                                    mapOf("critical_override_enabled" to on)
                                )
                            }
                        )
                    }
                )
            }

            // ── 四个独立入口：本页唯一职责 ──
            //
            // 一张卡一个入口，不再套同一个 UfiSettingsGroup：它们是并列的几个领域
            // （检测什么 / 提醒什么 / 往哪送 / 后台），装进一张卡会读成"同一组设置的几行"。
            //
            // 顺序 = 通知的生命周期：设备先产生告警 → 决定提醒哪些 → 往哪送 → 谁在后台盯着。
            //
            // 依赖关系直接画出来：总闸关着（或系统没放行）时，中间两个入口点进去改什么都不生效，
            // 所以置灰 + 不可点，摘要位写明卡在哪一环 —— 这比"能点进去、改完没反应"诚实。
            // **「设备告警」是例外，它不跟着总闸置灰**（理由见文件头注释：告警引擎的产物首先是
            // 设备侧事件流与监控中心列表，通知只是其中一条出口）。
            UfiSettingsRowCard {
                UfiSettingsValue(
                    title = "设备告警",
                    description = "设备检测哪几类异常 · 各类告警的阈值",
                    // 只有真读到 AlertConfig 才敢报数字：读不到时说"0 类"会让用户以为
                    // 自己的勾选丢了，转头去重新配一遍已经配好的东西。
                    value = when {
                        alertConfig == null -> "加载中"
                        !alertConfig.enabled -> "引擎已关闭"
                        alertTypesOn == 0 -> "未选类型"
                        else -> "$alertTypesOn/${ALERT_TYPE_KEYS.size} 类检测"
                    },
                    onClick = { navController.navigate(Routes.DETAIL_ALERT_SETTINGS) }
                )
            }

            UfiSettingsRowCard {
                UfiSettingsValue(
                    title = "通知管理",
                    description = "提醒哪些内容 · 免打扰 · 通知记录",
                    value = when {
                        !gateOn -> "总开关未开"
                        categoriesOn == 0 -> "未选类型"
                        else -> "$categoriesOn 类开启"
                    },
                    enabled = gateOn,
                    onClick = { navController.navigate(Routes.DETAIL_NOTIFY_MANAGE) }
                )
            }

            // 2026-09-09：原「邮件通知」入口改为「推送渠道」总览。
            //
            // 为什么是替换而不是并列再加一张卡：推送渠道页里的第一行就是「邮件」，
            // 点进去仍是同一个 EmailNotifyScreen —— 两个入口通向同一个目标，用户只会疑惑
            // 「这两个有什么区别」。而渠道会继续加（邮件 / Webhook / 以后可能更多），
            // 一渠道一张卡的话这页会被撑成配置大杂烩，本页的职责（提醒什么 / 往哪送 / 后台）
            // 也会被打散。
            UfiSettingsRowCard {
                UfiSettingsValue(
                    title = "推送渠道",
                    description = "通知往哪里送：邮件 · Webhook · 实时推送",
                    // 只有真读到配置才敢说数字：读失败时报"0 个已启用"会误导用户重新配一遍
                    value = summarizeChannels(
                        gateOn = gateOn,
                        mailLoaded = mailState.config != null,
                        mailEnabled = mailState.config?.enabled == true,
                        webhookLoaded = webhookState.loaded && webhookState.config != null,
                        webhookEnabled = webhookState.config?.enabled == true
                    ),
                    // 两条可配置渠道（邮件 / Webhook）都被总闸挡死：邮件的判定在 core 的
                    // `mailAllowed`、Webhook 的在 `NotificationDispatcher.emit`，两者第一条
                    // 都是 master_enabled。实时推送那一行只是说明文字，不构成"能点进去改"的理由。
                    enabled = gateOn,
                    onClick = { navController.navigate(Routes.DETAIL_PUSH_CHANNELS) }
                )
            }

            UfiSettingsRowCard {
                UfiSettingsValue(
                    title = "后台守护",
                    // 2026-09-08：改为跟着总闸置灰（原来保持可点 + 副文案说明）。
                    // 理由：守护的**唯一产物**就是通知，总闸关着它拉回来的告警一条也发不出去，
                    // 那这一页里没有任何设置是"现在有意义"的 —— 让它可点等于请用户去调一个空转的旋钮。
                    description = if (gateOn) {
                        "退到后台或被系统结束后仍周期拉取并推送"
                    } else {
                        "后台守护只负责把告警推成通知，总开关关闭时拉取到的内容不会发出"
                    },
                    value = when {
                        !gateOn -> "总开关未开"
                        guardState.enabled -> "每 ${guardState.intervalMinutes} 分钟"
                        else -> "已关闭"
                    },
                    enabled = gateOn,
                    onClick = { navController.navigate(Routes.DETAIL_BACKGROUND_GUARD) }
                )
            }

            Spacer(Modifier.height(Spacing.Large))
        }
    }
}

/**
 * 告警 perType 的类型键 —— 直接取 core contract 的 `Alerts.Type.ALL`（与 [AlertSettingsScreen]
 * 的每一行一一对应）。不在本仓抄一份字面量：抄的那份曾漏了引擎实际会检测的
 * `traffic_limit` / `device_online` / `device_offline`，摘要的分母因此长期偏小。
 */
internal val ALERT_TYPE_KEYS = Alerts.Type.ALL

/**
 * 「推送渠道」入口行的右侧摘要。
 *
 * 口径：**只有真读到配置才敢报数字**。任一渠道还没读到时说"N 个已启用"会漏报，
 * 而漏报的方向恰好是"看起来更少 / 更安全"—— 用户于是去重新配一遍已经配好的渠道。
 * 所以只要有一份没落地就退化成"加载中"。
 *
 * 实时推送（WebSocket）不计入分母：它没有开关，随 core 常开，算进"已启用 x/y"
 * 会让分母永远差一格解释不清。
 */
private fun summarizeChannels(
    gateOn: Boolean,
    mailLoaded: Boolean,
    mailEnabled: Boolean,
    webhookLoaded: Boolean,
    webhookEnabled: Boolean
): String = when {
    !gateOn -> "总开关未开"
    !mailLoaded || !webhookLoaded -> "加载中"
    else -> {
        val on = listOf(mailEnabled, webhookEnabled).count { it }
        if (on == 0) "均未启用" else "$on 项已启用"
    }
}

/**
 * 免打扰时段文案。`start > end` 表示跨零点，必须写出「次日」——
 * 只写「23:00 - 07:00」会被读成"当天 7 点就结束了"，跨天信息丢了。
 * `start == end` 是零长度窗口（恒不静默，见 `NotificationCenter.isDndActive`）。
 */
internal fun formatDndWindow(startHour: Int, endHour: Int): String = when {
    startHour == endHour -> "未设置时段（起止相同 = 不静默）"
    startHour > endHour -> "%02d:00 - 次日 %02d:00".format(startHour, endHour)
    else -> "%02d:00 - %02d:00".format(startHour, endHour)
}

/** Hero 卡状态数字用的紧凑写法（`23-07`）——那格只有三分之一屏宽，放不下完整文案。 */
private fun formatDndWindowShort(startHour: Int, endHour: Int): String =
    if (startHour == endHour) "未设置" else "%02d-%02d".format(startHour, endHour)

/**
 * Hero 状态卡：全局通知总闸行 + 一行三个 [UfiStatItem]。
 *
 * 这是全仓**唯一**的 `master_enabled` 开关入口，也是全仓唯一会弹 POST_NOTIFICATIONS
 * 权限框的地方 —— 所以它不能被 `configLoaded` 之类的远端状态锁住：授权是本机的事，
 * 不该由能不能连上设备决定（否则设备未配对时用户永远走不到授权那一步）。
 */
@Composable
private fun NotificationsHeroCard(
    masterOn: Boolean,
    notifAllowed: Boolean,
    categoriesOn: Int,
    onRowClick: () -> Unit,
    onCheckedChange: (Boolean) -> Unit,
    categorySummary: String,
    dndSummary: String,
    guardSummary: String
) {
    UfiSettingsGroup {
        MasterNotifySwitchRow(
            masterOn = masterOn,
            notifAllowed = notifAllowed,
            categoriesOn = categoriesOn,
            onRowClick = onRowClick,
            onCheckedChange = onCheckedChange
        )
        UfiDivider()
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.Medium),
            verticalAlignment = Alignment.CenterVertically
        ) {
            UfiStatItem(value = categorySummary, label = "通知类型", modifier = Modifier.weight(1f))
            UfiStatItem(value = dndSummary, label = "免打扰", modifier = Modifier.weight(1f))
            UfiStatItem(value = guardSummary, label = "后台守护", modifier = Modifier.weight(1f))
        }
    }
}

/**
 * 总闸行本体：圆形图标 + 标题 + 当前状态副文案 + [UfiSwitch]。不自带卡片容器。
 *
 * 显示态 = `masterOn && notifAllowed`：缺一就显示为关并说明原因，
 * 绝不出现"写着已开启但一条都发不出去"。
 */
@Composable
private fun MasterNotifySwitchRow(
    masterOn: Boolean,
    notifAllowed: Boolean,
    categoriesOn: Int,
    onRowClick: () -> Unit,
    onCheckedChange: (Boolean) -> Unit
) {
    val palette = LocalResolvedPalette.current
    val on = masterOn && notifAllowed
    val tint = if (on) palette.accent else palette.textSecondary

    Row(
        modifier = Modifier
            .fillMaxWidth()
            // 权限被系统挡住时整行可点，跳系统设置 —— 那是唯一的修复入口
            .then(if (masterOn && !notifAllowed) Modifier.clickable { onRowClick() } else Modifier)
            .padding(vertical = Spacing.Small),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(tint.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (on) Icons.Default.NotificationsActive else Icons.Default.NotificationsOff,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(22.dp)
            )
        }
        Spacer(Modifier.width(Spacing.Large))
        Column(Modifier.weight(1f)) {
            Text(text = "全局通知", style = UfiTextStyles.cardTitle, color = palette.textPrimary)
            Text(
                text = when {
                    masterOn && !notifAllowed -> "系统已关闭本应用的通知权限，点击前往开启"
                    !masterOn -> "已关闭，不会收到任何通知（状态栏与邮件都停）"
                    categoriesOn == 0 -> "已开启，还需在「通知管理」里选择要接收的类型"
                    else -> "已开启，接收已选中的 $categoriesOn 类通知"
                },
                style = MaterialTheme.typography.bodySmall,
                color = palette.textSecondary,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
        Spacer(Modifier.width(Spacing.Medium))
        UfiSwitch(checked = on, onCheckedChange = onCheckedChange)
    }
}
