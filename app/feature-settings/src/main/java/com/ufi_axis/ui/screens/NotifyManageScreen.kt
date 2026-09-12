package com.ufi_axis.ui.screens

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.NotificationManagerCompat
import androidx.navigation.NavHostController
import com.ufi_axis.data.notification.NotificationCenter
import com.ufi_axis.data.notification.NotifyDispatchReceiver
import com.ufi_axis.data.notification.NotifyPrefs
import com.ufi_axis.ui.components.common.*
import com.ufi_axis.ui.navigation.Routes
import com.ufi_axis.ui.theme.LocalResolvedPalette
import com.ufi_axis.ui.theme.Spacing
import com.ufi_axis.ui.theme.UfiTextStyles
import com.ufi_axis.viewmodel.MainViewModel
import kotlin.math.roundToInt

/**
 * 「通知管理」——通知类开关 + 告警类设置 + 系统通知记录。
 *
 * ## 为什么单独一页（2026-09-08）
 * 上一版「通知与守护」把两个都长得像总开关的东西摆在同一屏：
 * - Hero 卡的「告警总开关」其实是 **`AlertConfig.enabled`**（设备端告警引擎：产不产生告警）
 * - 下面的「全局通知」才是 **`master_enabled`**（客户端投递总闸：产生了要不要提醒你）
 *
 * 两者不同层，却一个在顶一个在中间，右侧入口摘要还写着"总开关已关闭"（说的是引擎）。
 * 现在层级摊平成：**通知与守护只留全局通知总闸 + 三个入口**，本页承载"提醒什么"，
 * 引擎开关改名「设备告警引擎」下放到本页的告警分组 —— 它管的是设备端要不要产生告警，
 * 与"提醒不提醒"是两件事，放在一起但不再冒充总开关。
 *
 * ## 层级
 * L1 全局通知（上一页 Hero）→ L3 分类（本页「告警类通知」+「日常通知」页的 7 项）。
 * 分类行的置灰条件与 L1 的显示态同一口径：总闸关着时分类即使为 true 也一条都不会发
 * （判定在 `NotificationCenter.notify`），不置灰就是"能开但没用"的假开关。
 */
@Composable
fun NotifyManageScreen(
    viewModel: MainViewModel,
    navController: NavHostController
) {
    val context = LocalContext.current
    val palette = LocalResolvedPalette.current
    val prefs = remember { context.getSharedPreferences("ufi_axis_prefs", Context.MODE_PRIVATE) }

    val alertPrefs by viewModel.alertPrefs.configFlow.collectAsState()
    val toolsState by viewModel.toolsState.collectAsState()

    // 状态未知时一律显示为关（副文案说明在加载）：宁可少承诺也不要假承诺。
    val engineEnabled = alertPrefs?.enabled ?: false
    val configLoaded = alertPrefs != null

    /** L1 总闸与系统放行：本页只读它们做置灰，开关本体在上一页 Hero。 */
    val readNotifAllowed = { NotificationManagerCompat.from(context).areNotificationsEnabled() }
    var masterOn by remember { mutableStateOf(prefs.getBoolean(NotificationCenter.KEY_NOTIFY_MASTER, false)) }
    var notifAllowed by remember { mutableStateOf(readNotifAllowed()) }
    /** L3 告警分类（阈值告警：温度 / 电量 / 流量 / 信号）。默认与闸门同为 false。 */
    var alertNotifOn by remember { mutableStateOf(prefs.getBoolean(NotificationCenter.KEY_ALERT_NOTIF, false)) }

    var dndOn by remember { mutableStateOf(prefs.getBoolean(NotificationCenter.KEY_DND_ENABLED, false)) }
    var dndStart by remember {
        mutableIntStateOf(
            prefs.getInt(NotificationCenter.KEY_DND_START_HOUR, NotificationCenter.DEFAULT_DND_START_HOUR)
        )
    }
    var dndEnd by remember {
        mutableIntStateOf(prefs.getInt(NotificationCenter.KEY_DND_END_HOUR, NotificationCenter.DEFAULT_DND_END_HOUR))
    }
    var dndDialogOpen by remember { mutableStateOf(false) }

    /**
     * 提示条状态。
     *
     * 走 [UfiToastHost]（平台层 DecorView 单例）而不是 `android.widget.Toast`：
     * 原生 Toast 会跟随系统主题与 OEM 定制，且**不受本应用的主题/遮罩/转场约束** ——
     * 在这一页已经铺满卡片与弹窗的场景下，它可能被弹窗盖住或与页面错位。
     * 全站提示统一由一个宿主渲染，这是项目的既有约定。
     */
    var toastMessage by remember { mutableStateOf<ToastMessage?>(null) }

    // 「严重事件兜底」只读镜像：开关本体在上一页（「通知与守护」）。
    // 本页需要它是因为免打扰那一行必须说清"严重事件会不会穿透" ——
    // 不说的话用户会把兜底带来的半夜提醒判定成免打扰失灵。
    var criticalOverrideOn by remember {
        mutableStateOf(
            NotifyPrefs.switchOn(
                context,
                NotificationCenter.KEY_CRITICAL_OVERRIDE,
                NotificationCenter.DEFAULT_CRITICAL_OVERRIDE
            )
        )
    }

    // 两道记录保留上限：真源在 core，本机镜像在 prefs（`:ufi_notify` 只认镜像）。
    var maxRows by remember {
        mutableIntStateOf(
            NotifyPrefs.switchInt(
                context,
                NotificationCenter.KEY_HISTORY_MAX_ROWS,
                NotificationCenter.DEFAULT_HISTORY_MAX_ROWS
            )
        )
    }
    var maxAgeDays by remember {
        mutableIntStateOf(
            NotifyPrefs.switchInt(
                context,
                NotificationCenter.KEY_HISTORY_MAX_AGE_DAYS,
                NotificationCenter.DEFAULT_HISTORY_MAX_AGE_DAYS
            )
        )
    }
    var retentionDialogOpen by remember { mutableStateOf(false) }

    val dailyOn = DAILY_NOTIFY_SCENES.count { (key, default) -> prefs.getBoolean(key, default) }

    LaunchedEffect(Unit) {
        // 告警配置只为「告警类通知」那行的副文案（分类开着但引擎没开时要说出来）；
        // 引擎开关与阈值本身已移交监控中心，本页不写它。
        viewModel.tools.loadAlerts()
        viewModel.tools.refreshNotificationConfig()
        // 记录入口的条数摘要（本机 Room，一次 SELECT COUNT）
        viewModel.tools.loadNotifyHistory()
    }

    // 回到前台重读：这些 prefs 可能被 core 回显、被上一页的总闸或系统设置改掉，
    // 不重读就会出现"页面显示与真源分叉"。
    rememberResumeRefresh {
        notifAllowed = readNotifAllowed()
        masterOn = prefs.getBoolean(NotificationCenter.KEY_NOTIFY_MASTER, false)
        alertNotifOn = prefs.getBoolean(NotificationCenter.KEY_ALERT_NOTIF, false)
        dndOn = prefs.getBoolean(NotificationCenter.KEY_DND_ENABLED, false)
        dndStart = prefs.getInt(
            NotificationCenter.KEY_DND_START_HOUR,
            NotificationCenter.DEFAULT_DND_START_HOUR
        )
        dndEnd = prefs.getInt(NotificationCenter.KEY_DND_END_HOUR, NotificationCenter.DEFAULT_DND_END_HOUR)
        maxRows = NotifyPrefs.switchInt(
            context,
            NotificationCenter.KEY_HISTORY_MAX_ROWS,
            NotificationCenter.DEFAULT_HISTORY_MAX_ROWS
        )
        maxAgeDays = NotifyPrefs.switchInt(
            context,
            NotificationCenter.KEY_HISTORY_MAX_AGE_DAYS,
            NotificationCenter.DEFAULT_HISTORY_MAX_AGE_DAYS
        )
        criticalOverrideOn = NotifyPrefs.switchOn(
            context,
            NotificationCenter.KEY_CRITICAL_OVERRIDE,
            NotificationCenter.DEFAULT_CRITICAL_OVERRIDE
        )
        viewModel.tools.loadNotifyHistory()
    }

    /** 通知开关的字段级下发：本地 prefs 已写完再推 core，只传改动的那一个键。 */
    val syncNotifyField: (String, Boolean) -> Unit = { field, value ->
        viewModel.tools.updateNotificationConfig(mapOf(field to value))
    }

    val gateOn = masterOn && notifAllowed

    UfiScreenScaffold(title = "通知管理", navController = navController, showBack = true) { padding ->
        UfiPageBackground(modifier = Modifier.padding(padding)) {

            // ── 每一项独立成卡（2026-09-08）──
            //
            // 原来按「提醒内容 / 提醒方式 / 设备端告警 / 记录」分了四组，但这一页本来就只有
            // 六项，分组头比内容还多；而且"免打扰算内容还是方式"这种归类只在写的人脑子里成立。
            // 现在一项一卡、顺序即优先级：先选内容 → 再管时段与自检 → 最后查记录。
            UfiSettingsRowCard {
                UfiSettingsItem(
                    title = "告警类通知",
                    description = when {
                        !gateOn -> "请先在上一页开启「全局通知」"
                        alertNotifOn && !engineEnabled -> "已开启，但设备端告警引擎没开，暂时不会有提醒"
                        alertNotifOn -> "温度、电量、流量、信号超出阈值时提醒"
                        else -> "已关闭，不再接收阈值告警提醒"
                    },
                    trailing = {
                        UfiSwitch(
                            checked = alertNotifOn,
                            enabled = gateOn,
                            onCheckedChange = { on ->
                                prefs.edit().putBoolean(NotificationCenter.KEY_ALERT_NOTIF, on).apply()
                                alertNotifOn = on
                                // `:ufi_notify` 只认自己那份 mirror_ 副本，不推快照它会继续按旧值发
                                NotifyDispatchReceiver.dispatchSwitchSnapshot(context)
                                syncNotifyField("alert_enabled", on)
                            }
                        )
                    }
                )

            }

            UfiSettingsRowCard {
                UfiSettingsValue(
                    title = "日常通知",
                    description = "离线上线 · 流量预警 · 短信 · 下载 · 设备事件 · 内网穿透",
                    value = "$dailyOn/${DAILY_NOTIFY_SCENES.size} 项开启",
                    enabled = gateOn,
                    onClick = { navController.navigate(Routes.DETAIL_DAILY_NOTIFY) }
                )
            }

            UfiSettingsRowCard {
                // 免打扰：全局静默时段。「后台守护」页也有同一个开关（都写 KEY_DND_ENABLED
                // + core 的 dnd_enabled），是两个入口指向同一真源。
                // 交互：右侧开关管开关，点整行改时段。
                UfiSettingsItem(
                    title = "免打扰时段",
                    description = if (dndOn) {
                        "${formatDndWindow(dndStart, dndEnd)} 静默 · " +
                            notifyCriticalOverrideNote(criticalOverrideOn)
                    } else {
                        "已关闭 · 点击设置时段（当前 ${formatDndWindow(dndStart, dndEnd)}）"
                    },
                    modifier = Modifier.clickable { dndDialogOpen = true },
                    trailing = {
                        UfiSwitch(
                            checked = dndOn,
                            onCheckedChange = {
                                dndOn = it
                                prefs.edit().putBoolean(NotificationCenter.KEY_DND_ENABLED, it).apply()
                                // 只写 prefs 不够：`:ufi_notify` 读的是自己那份 mirror_ 副本，
                                // 走 core 下发后由回显 applyRemote 补 AIDL 推送，两个进程才一致。
                                syncNotifyField("dnd_enabled", it)
                            }
                        )
                    }
                )
            }

            UfiSettingsRowCard {
                // 通道自检：绕过所有开关 / 限频 / 免打扰直接发一条，
                // 用来区分"没配好"和"配好了但系统没放行"。
                UfiSettingsItem(
                    title = "发送测试通知",
                    description = "立即验证通知通道是否畅通（不受开关 / 限频 / 免打扰影响）",
                    modifier = Modifier.clickable {
                        // 试发结果直接进提示条：文案说原因，类型决定配色。
                        // 全站提示统一由 UfiToastHost 渲染（平台层 DecorView 单例），
                        // 不用 android.widget.Toast —— 后者不受本应用主题与遮罩约束，
                        // 在这一页已铺满卡片/弹窗的场景下会被盖住或错位。
                        toastMessage = when (val result = NotificationCenter(context).sendTestNotification()) {
                            is NotificationCenter.TestResult.Success ->
                                ToastMessage("已发送测试通知 · 下拉状态栏查看", ToastType.SUCCESS, durationMs = 4000L)
                            is NotificationCenter.TestResult.PermissionDenied ->
                                ToastMessage(
                                    "通知权限未授予，请在系统「应用通知」中开启 UFI-AXIS",
                                    ToastType.WARNING,
                                    durationMs = 5000L
                                )
                            is NotificationCenter.TestResult.Failed ->
                                ToastMessage("发送失败：${result.error}", ToastType.ERROR, durationMs = 5000L)
                        }
                    },
                    trailing = {
                        Text(text = "点击试发", style = UfiTextStyles.label, color = palette.accent)
                    }
                )
            }

            // 「设备告警引擎 / 告警与阈值」不在本页（2026-09-08 移交监控中心）：
            // 那两项管的是**设备要不要产生告警**，产物是监控中心的事件流；关掉它监控中心就没事件了。
            // 放在通知页会读成"通知的一部分"，也解释不了"关掉之后监控中心为什么空了"。

            UfiSettingsRowCard {
                UfiSettingsValue(
                    title = "系统通知记录",
                    description = "每条提醒的结果，包括没提醒成功的原因",
                    value = if (toolsState.notifyHistoryLoaded) "共 ${toolsState.notifyHistoryTotal} 条" else "加载中",
                    onClick = { navController.navigate(Routes.DETAIL_NOTIFY_HISTORY) }
                )
            }

            UfiSettingsRowCard {
                // 保留上限只在这里出现一次：同一份配置同时管本机通知记录与设备端邮件记录，
                // 在两个记录页各放一个可写入口会让人以为是两个独立设置。
                UfiSettingsValue(
                    title = "记录保留上限",
                    description = "同时作用于系统通知记录与邮件发送记录",
                    value = historyRetentionSummary(maxRows, maxAgeDays),
                    onClick = { retentionDialogOpen = true }
                )
            }

            Spacer(Modifier.height(Spacing.Large))
        }

        if (dndDialogOpen) {
            DndWindowDialog(
                startHour = dndStart,
                endHour = dndEnd,
                criticalOverrideOn = criticalOverrideOn,
                onDismiss = { dndDialogOpen = false },
                onConfirm = { start, end ->
                    dndStart = start
                    dndEnd = end
                    prefs.edit()
                        .putInt(NotificationCenter.KEY_DND_START_HOUR, start)
                        .putInt(NotificationCenter.KEY_DND_END_HOUR, end)
                        .apply()
                    // core 是真源；回显的 applyRemote 会补 AIDL 推送，:ufi_notify 才看得到新时段
                    viewModel.tools.updateNotificationConfig(
                        mapOf("dnd_start_hour" to start, "dnd_end_hour" to end)
                    )
                    dndDialogOpen = false
                }
            )
        }

        if (retentionDialogOpen) {
            RetentionDialog(
                currentRows = maxRows,
                currentAgeDays = maxAgeDays,
                onDismiss = { retentionDialogOpen = false },
                onConfirm = { rows, ageDays ->
                    maxRows = rows
                    maxAgeDays = ageDays
                    // 先写本机真源，再把快照推给 `:ufi_notify` —— 本机记录的裁剪发生在那个进程里，
                    // 它读开关只认自己那份 mirror_ 副本，不推的话会一直按旧上限裁。
                    NotifyPrefs.putSwitch(context, NotificationCenter.KEY_HISTORY_MAX_ROWS, rows)
                    NotifyPrefs.putSwitch(context, NotificationCenter.KEY_HISTORY_MAX_AGE_DAYS, ageDays)
                    NotifyDispatchReceiver.dispatchSwitchSnapshot(context)
                    // core 是跨端真源（邮件记录那半按它裁），回显的 applyRemote 会再落一次本地
                    viewModel.tools.updateNotificationConfig(
                        mapOf("history_max_rows" to rows, "history_max_age_days" to ageDays)
                    )
                    retentionDialogOpen = false
                }
            )
        }

        // 提示条宿主放在转场内容之外、Scaffold 之内：试发通知的结果不该随页面内容一起滚走。
        UfiToastHost(toastMessage = toastMessage, onDismiss = { toastMessage = null })
    }
}

/** 保留条数滑块的步长：100 条一格（区间 100..5000 → 49 格）。 */
private const val ROWS_STEP = 100

/** 保留天数的候选值（0 = 不限）。用离散档位而不是 0..365 的滑块 —— 365 格没人拖得准。 */
private val AGE_DAY_OPTIONS = listOf(
    "0" to "不限",
    "7" to "7 天",
    "30" to "30 天",
    "90" to "90 天",
    "365" to "1 年"
)

/** 记录保留上限弹窗：暂存-确认（改动只进 draft，点确认才写盘 + 下发）。 */
@Composable
private fun RetentionDialog(
    currentRows: Int,
    currentAgeDays: Int,
    onDismiss: () -> Unit,
    onConfirm: (rows: Int, ageDays: Int) -> Unit
) {
    val palette = LocalResolvedPalette.current
    var rows by remember { mutableIntStateOf(currentRows) }
    // core 允许 0..365 任意天数（web 那侧是数字输入，可以填 45），chip 只列常用档位。
    // 落到非档位值时哪个 chip 都不高亮 —— 顶部摘要那行会写出真实天数，不会显示成"不限"。
    var ageDays by remember { mutableIntStateOf(currentAgeDays) }
    UfiCustomDialog(
        visible = true,
        onDismiss = onDismiss,
        title = "记录保留上限",
        confirmButton = { UfiButton(text = "确认", onClick = { onConfirm(rows, ageDays) }) },
        dismissButton = {
            UfiButton(variant = UfiButtonVariant.Secondary, text = "取消", onClick = onDismiss)
        }
    ) {
        UfiDialogBody {
            Text(
                text = historyRetentionSummary(rows, ageDays),
                style = UfiTextStyles.cardTitle,
                color = palette.accent
            )
            Text(
                text = "两道上限先到者生效，同时作用于本机的系统通知记录与设备上的邮件记录。" +
                    "清理按批触发（每写入 20 条一次），实际留存可能略多于设定值。",
                style = MaterialTheme.typography.bodySmall,
                color = palette.textSecondary
            )
            UfiDialogField(label = "最多 $rows 条") {
                UfiValueSlider(
                    value = rows.toFloat(),
                    valueRange = NotificationCenter.MIN_HISTORY_MAX_ROWS.toFloat()..
                        NotificationCenter.MAX_HISTORY_MAX_ROWS.toFloat(),
                    // 49 格 × 100 条：给到条级精度只会让人拖半天，量级够用。
                    steps = (NotificationCenter.MAX_HISTORY_MAX_ROWS -
                        NotificationCenter.MIN_HISTORY_MAX_ROWS) / ROWS_STEP - 1,
                    onValueChange = { rows = (it / ROWS_STEP).roundToInt() * ROWS_STEP }
                )
            }
            UfiDialogChipSelector(
                label = "保留时长",
                options = AGE_DAY_OPTIONS,
                selectedValue = ageDays.toString(),
                onSelect = { ageDays = it.toIntOrNull() ?: ageDays }
            )
        }
    }
}

/**
 * 免打扰时段编辑弹窗：**一个区间滑块**，两个 thumb 圈出"会提醒的时段"，两端着色 = 静默。
 *
 * ## 为什么是反相着色（2026-09-08 改）
 * 上一版用两个单值滑块（开始 / 结束各一条），拖两次才设一个时段。改成区间滑块后遇到硬约束：
 * 标准区间滑块两个 thumb 不能交叉，而免打扰最常用的恰恰是 **23:00 → 次日 07:00**
 * （`start > end`），根本表达不出来。
 *
 * 于是反过来：thumb 圈住的中间段是「会提醒」，两端着色段是「静默」——
 * 跨零点成了这个形态的自然结果，一个滑块搞定，且"选中的两边就是免打扰"一眼可读。
 *
 * ## 取舍（必须知道）
 * 这个形态**只能表达跨零点的静默**。不跨零点的静默（如午休 13:00-15:00）表达不了；
 * 真要支持得再加一个"静默不跨天"的形态开关，当前需求里没有这种用法。
 *
 * ## 值映射
 * 滑块值域 0..24（整点），`values = 提醒时段 [a, b]`：
 * - `dnd_start = b`（静默从提醒结束时开始）、`dnd_end = a`（静默到提醒开始时结束）
 * - `b = 24` 存成 `0`（同一时刻的两种写法，core 只认 0..23）
 * - `a = 0 && b = 24` → `start == end` → `isDndActive` 判定为"不静默"，即全天都提醒 ✓
 *
 * 暂存-确认：拖动只改本地 draft，点「确认」才回调下发 —— 每拖一格就 PUT 一次会打出几十个请求。
 */
@Composable
private fun DndWindowDialog(
    startHour: Int,
    endHour: Int,
    criticalOverrideOn: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (Int, Int) -> Unit
) {
    val palette = LocalResolvedPalette.current
    // 入参是静默窗口 (start=静默开始, end=静默结束)；滑块要的是提醒时段 [end, start]。
    // 静默 start==end（不静默）时展开成全天提醒 0..24，而不是让两个 thumb 叠在一起。
    val initialAllow = remember(startHour, endHour) {
        if (startHour == endHour) 0f..24f else endHour.toFloat()..startHour.toFloat()
    }
    var allow by remember { mutableStateOf(initialAllow) }

    val silentStart = allow.endInclusive.roundToInt() % 24
    val silentEnd = allow.start.roundToInt() % 24

    UfiCustomDialog(
        visible = true,
        onDismiss = onDismiss,
        title = "免打扰时段",
        // 确认位保持 size = Standard 而不是 Small：后者高度是 SmallButtonHeight，
        // 与 dismiss 位（Secondary + Standard = ButtonHeight）不等高，看起来像"取消高/确认矮"。
        confirmButton = { UfiButton(text = "确认", onClick = { onConfirm(silentStart, silentEnd) }) },
        dismissButton = {
            UfiButton(variant = UfiButtonVariant.Secondary, text = "取消", onClick = onDismiss)
        }
    ) {
        UfiDialogBody {
            Text(
                text = if (silentStart == silentEnd) {
                    "全天都会提醒（没有静默时段）"
                } else {
                    "静默 ${formatDndWindow(silentStart, silentEnd)} · 共 ${dndDurationHours(silentStart, silentEnd)} 小时"
                },
                style = UfiTextStyles.cardTitle,
                color = palette.accent
            )
            Text(
                text = "滑块中间是会提醒的时段，两端着色部分静默（不响铃不震动）。" +
                    notifyCriticalOverrideNote(criticalOverrideOn),
                style = MaterialTheme.typography.bodySmall,
                color = palette.textSecondary
            )
            UfiDialogField(
                label = "提醒时段 · %02d:00 - %02d:00".format(
                    allow.start.roundToInt(),
                    allow.endInclusive.roundToInt()
                )
            ) {
                UfiRangeSlider(
                    values = allow,
                    valueRange = 0f..24f,
                    // 24 段 = 每格 1 小时；免打扰不需要分钟级精度
                    steps = 24,
                    tickStep = 1f,
                    tickLabelFormatter = { "%02d".format(it.roundToInt()) },
                    // 两端着色 = 静默段，正是用户在这个弹窗里要设的东西
                    invertedTrack = true,
                    onValuesChange = { allow = it }
                )
            }
        }
    }
}

/** 静默时长（小时）；跨零点按加 24 算。 */
private fun dndDurationHours(startHour: Int, endHour: Int): Int =
    if (endHour > startHour) endHour - startHour else endHour + 24 - startHour

