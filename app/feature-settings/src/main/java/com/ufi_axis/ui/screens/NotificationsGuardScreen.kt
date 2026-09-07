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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavHostController
import com.ufi_axis.data.notification.NotificationCenter
import com.ufi_axis.data.notification.NotificationConfigClient
import com.ufi_axis.data.notification.NotifyDispatchReceiver
import com.ufi_axis.ui.components.common.*
import com.ufi_axis.ui.navigation.Routes
import com.ufi_axis.ui.theme.LocalResolvedPalette
import com.ufi_axis.ui.theme.Spacing
import com.ufi_axis.ui.theme.UfiTextStyles
import com.ufi_axis.viewmodel.MainViewModel
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * 「通知与守护」聚合入口页。
 *
 * 2026-08-30 重构（方案 B · 入口聚合）：本页不再承载任何具体设置项。
 *
 * 旧结构的问题：页面顶部固定放「总开关卡 + 邮件通知卡 + 2 项 TabRow」，Tab 内容却是
 * 两个**完整的** [UfiScreenScaffold]（各自还带 `UfiPageBackground` 的 verticalScroll），
 * 于是外层固定区永久占屏、真正可滚的只剩一条窄缝；告警 Tab 里又叠了 5 张同款分组卡、
 * 三层标题（Tab → GroupHeader → SectionHeader）和几十行长副标，整页就是一堵文字墙。
 *
 * 现在：Hero 状态卡（总开关 + 三项状态数字）+ 4 条带状态摘要的入口行，
 * 细节全部下沉到各自的二级页（都是已有路由，本页只负责导航）：
 * - 告警与阈值 → [Routes.DETAIL_ALERT_SETTINGS]（通知渠道 / 告警类型 / 阈值 / 免打扰）
 * - 日常通知   → [Routes.DETAIL_DAILY_NOTIFY]（从告警页拆出的 6 个非告警场景）
 * - 后台守护   → [Routes.DETAIL_BACKGROUND_GUARD]
 * - 邮件通知   → [Routes.DETAIL_EMAIL_NOTIFY]
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
    val scope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences("ufi_axis_prefs", Context.MODE_PRIVATE) }

    val alertPrefs by viewModel.alertPrefs.configFlow.collectAsState()
    val guardState by viewModel.backgroundGuard.state.collectAsState()
    val mailState by viewModel.smsForwardState.collectAsState()

    // 2026-09-04「假开关」复审：镜像未加载时兜底改为 **false**。
    // 原来是 `?: true` —— 配置还没拉到就先显示"引擎正在检测并推送告警"，而此时 core 的真实
    // 状态**未知**，开关又是 disabled 的，用户看到一个"已开启但点不动"的开关。
    // 状态未知时一律显示为关（副文案说明是在加载），宁可少承诺也不要假承诺。
    val masterEnabled = alertPrefs?.enabled ?: false
    val configLoaded = alertPrefs != null

    // 摘要需要的三份数据都不是本页自己的开关，进来时各拉一次。
    // 全部允许失败（各自内部静默兜底）—— 摘要读不到就退化成占位文案，不阻塞导航。
    LaunchedEffect(Unit) {
        viewModel.tools.loadAlerts()
        viewModel.tools.refreshNotificationConfig()
        viewModel.tools.loadSmsForwardConfig()
        viewModel.backgroundGuard.refresh()
    }

    // 5 类告警的开启数（2026-09-07：perType 缺键按**默认关闭**算，与 AlertSettingsScreen
    // 的 `?: false` 及 core `AlertEngine.typeEnabled` 的 `== true` 逐字一致）
    val alertTypesOn = ALERT_TYPE_KEYS.count { alertPrefs?.perType?.get(it) ?: false }
    // 日常通知开启数：key/default 从 DailyNotifyScreen 单一来源取，避免分母对不上
    val dailyOn = DAILY_NOTIFY_SCENES.count { (key, default) -> prefs.getBoolean(key, default) }
    var dndOn by remember { mutableStateOf(prefs.getBoolean(NotificationCenter.KEY_DND_ENABLED, false)) }
    var dndStart by remember {
        mutableStateOf(prefs.getInt(NotificationCenter.KEY_DND_START_HOUR, NotificationCenter.DEFAULT_DND_START_HOUR))
    }
    var dndEnd by remember {
        mutableStateOf(prefs.getInt(NotificationCenter.KEY_DND_END_HOUR, NotificationCenter.DEFAULT_DND_END_HOUR))
    }
    var dndDialogOpen by remember { mutableStateOf(false) }
    // 2026-09-04 修「所有类型的系统通知都不推」：这里原来读 default=**true**，
    // 而真正的闸门全部读 default=false（`NotificationCenter.maybeNotifyNewAlerts` /
    // `notifyDeviceConnectivity` / `NotifyService.shouldRun` / `BackgroundGuardWorker`）。
    // 于是从未碰过这个开关的用户：界面显示"已开启"→ 不会去点它 → Android 13+ 的
    // POST_NOTIFICATIONS 永远不申请（全仓只有本页这一处会弹权限框）→ 通知一条都发不出来，
    // 且 `notify()` 静默 return，日志里什么都看不到。默认值必须与闸门一致。
    var systemNotifOn by remember { mutableStateOf(prefs.getBoolean(NotificationCenter.KEY_ALERT_NOTIF, false)) }

    // ── 「假开关」防线（2026-09-04 复审后补齐）──
    //
    // 上一版的教训：开关只反映 prefs，与**系统是否真的放行**零关联。于是出现
    // 「界面写着已开启、实际一条都发不出去」——`notify()` 在 areNotificationsEnabled()
    // 处静默 return，用户无从知道。现在真实状态 = prefs && 系统放行，二者缺一都显示为关。
    //
    // 为什么用 areNotificationsEnabled() 而不只是 checkSelfPermission：用户还可能在
    // 系统设置里关掉本应用的通知（权限仍是 granted），那种情况同样一条都发不出去。
    // 这个函数把两种情况一起覆盖，也是 `notify()` 真正使用的判据。
    val readNotifAllowed = { NotificationManagerCompat.from(context).areNotificationsEnabled() }
    var notifAllowed by remember { mutableStateOf(readNotifAllowed()) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                // 从系统设置返回后必须重检：否则开关停在进页面那一刻的快照上
                // （BackgroundGuardScreen 的无障碍开关 2026-08-20 已经踩过同一个坑）。
                notifAllowed = readNotifAllowed()
                // 这几个 prefs 值同样可能被 core 回显（refreshNotificationConfig）或
                // 别的页面改掉，一起重读，避免页面显示与真源分叉。
                systemNotifOn = prefs.getBoolean(NotificationCenter.KEY_ALERT_NOTIF, false)
                dndOn = prefs.getBoolean(NotificationCenter.KEY_DND_ENABLED, false)
                dndStart = prefs.getInt(
                    NotificationCenter.KEY_DND_START_HOUR,
                    NotificationCenter.DEFAULT_DND_START_HOUR
                )
                dndEnd = prefs.getInt(NotificationCenter.KEY_DND_END_HOUR, NotificationCenter.DEFAULT_DND_END_HOUR)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    /** 打开系统「应用通知」设置页 —— 权限被二次拒绝后，这是唯一的修复入口。 */
    val openSystemNotifSettings = {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            )
        }
    }

    /** 通知开关的字段级下发：本地 prefs 已写完再推 core，只传改动的那一个键。 */
    val syncNotifyField: (String, Boolean) -> Unit = { field, value ->
        viewModel.tools.updateNotificationConfig(mapOf(field to value))
    }

    // Android 13+ 通知运行时权限。授权成功这条路径同样是「打开了告警通知」，
    // 也要下发 core —— 少写一次，别端看到的开关状态就和这里不一样。
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            prefs.edit().putBoolean(NotificationCenter.KEY_ALERT_NOTIF, true).apply()
            systemNotifOn = true
            notifAllowed = readNotifAllowed()
            // 同下方开关分支：闸门在 startKeepAlive 内部，保活没开时是 no-op；
            // 保活早就开着（当时因缺权限没起来）时由这行补上。
            NotificationConfigClient.startKeepAlive(context)
            // 总闸刚变 true，把快照推给 `:ufi_notify` 刷新它那份 mirror_
            NotifyDispatchReceiver.dispatchSwitchSnapshot(context)

            syncNotifyField("alert_enabled", true)
        } else {
            // 2026-09-04：原来没有 else 分支 —— 用户拒绝后开关弹回关、没有任何提示；
            // 而 Android 13 二次拒绝会**立即**回调 denied（系统不再弹框），
            // 表现成"点开关完全没反应"。所以拒绝时必须明说并给出系统设置入口。
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

            // ── Hero：总开关 + 三项状态。第一屏就回答「它现在在工作吗」 ──
            NotificationsHeroCard(
                enabled = masterEnabled,
                configLoaded = configLoaded,
                onCheckedChange = { checked ->
                    val base = alertPrefs
                    // T13：镜像为空时不可写 —— 否则会用本地默认值 + version=1 覆盖别端配置
                    if (base != null) {
                        scope.launch { viewModel.tools.updateAlertConfig(base.copy(enabled = checked)) }
                    }
                },
                alertTypesSummary = if (configLoaded) "$alertTypesOn/${ALERT_TYPE_KEYS.size}" else "--",
                guardSummary = if (guardState.enabled) "${guardState.intervalMinutes} 分钟" else "关闭",
                dndSummary = if (dndOn) formatDndWindowShort(dndStart, dndEnd) else "关闭"
            )

            // ── 通知（2026-08-30 从「告警设置」页上移）──
            // 这三项管的是"通道通不通 / 什么时候允许响"，对**所有**通知生效，不只告警，
            // 所以属于总体设置，紧跟 Hero 卡；告警页只留"报哪些类型、阈值多少"。
            UfiSettingsGroup {
                UfiGroupHeader("通知")

                // 系统通知推送：本机弹不弹（prefs + 系统放行）+ 服务端投递总闸
                // （AlertConfig.notifyEnabled）。前两者是本机能力，最后一项是 core 配置。
                //
                // 2026-09-04 复审后的两条硬规则：
                // ① **不受 configLoaded 约束**。原来 `enabled = configLoaded` 把全仓唯一的
                //    POST_NOTIFICATIONS 申请入口锁住了 —— 设备未配对 / core 不可达时开关点不动，
                //    用户永远走不到授权那一步，通知功能整体失效且无从修复。
                //    授权是本机的事，不该由能不能连上设备决定；core 那一份用 syncNotifyField
                //    尽力下发，失败也不影响本地生效。
                // ② **显示状态 = prefs && 系统放行**。缺一就显示为关并说明原因，
                //    绝不出现"写着已开启但一条都发不出去"。
                UfiSettingsItem(
                    title = "系统通知推送",
                    description = when {
                        systemNotifOn && !notifAllowed -> "系统已禁止本应用通知，点击前往系统设置开启"
                        !systemNotifOn -> "关闭中：告警 / 短信 / 验证码都不会出现在状态栏"
                        !configLoaded -> "本机已开启；设备配置未连上，稍后自动同步"
                        masterEnabled -> "通知触发时在状态栏显示"
                        else -> "告警总开关已关闭，告警类推送无效"
                    },
                    modifier = if (systemNotifOn && !notifAllowed) {
                        Modifier.clickable { openSystemNotifSettings() }
                    } else {
                        Modifier
                    },
                    trailing = {
                        UfiSwitch(
                            checked = systemNotifOn && notifAllowed,
                            onCheckedChange = { on ->
                                if (on) {
                                    val hasPerm = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                                        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
                                    if (!hasPerm) {
                                        permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                        return@UfiSwitch
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
                                        return@UfiSwitch
                                    }
                                    notifAllowed = true
                                    prefs.edit().putBoolean(NotificationCenter.KEY_ALERT_NOTIF, true).apply()
                                    systemNotifOn = true
                                    // 2026-09-05：这里原来的注释是「开关即守护」，并**无条件**拉起 FGS ——
                                    // 那是「没开保活却有常驻通知」的成因之一。现在闸门收口在
                                    // NotificationConfigClient.startKeepAlive 内部（只看
                                    // guard_foreground_keepalive_enabled），保活没开时这行自然是 no-op。
                                    // 调用点保留：覆盖「保活早就开着，但当时因缺权限没起来」的情况。
                                    // 顺序也不能反：prefs 必须先写。
                                    NotificationConfigClient.startKeepAlive(context)

                                } else {
                                    prefs.edit().putBoolean(NotificationCenter.KEY_ALERT_NOTIF, false).apply()
                                    systemNotifOn = false
                                    // 2026-09-05 下午：这里原来调 `stopKeepAlive` —— 那是拿 A 开关去停
                                    // B 开关授权的服务。用户明明还开着「前台服务保活」（那一页也还显示为开），
                                    // 常驻通知却消失，而且 `:ufi_notify` 负责的**短信 / 验证码实时推送**
                                    // 会一起失效（它是那条 WS 频道的唯一订阅方）。
                                    // 这个总闸只决定「发不发告警类通知」，不决定「守护进程要不要活着」
                                    //（判据见 com.ufi_axis.data.notification.KeepAliveGate）。
                                }
                                // 两个方向都要推一次快照：`:ufi_notify` 读开关只认自己那份 mirror_ 副本，
                                // 不推的话它会继续按旧的总闸值发（或不发）告警通知。
                                // 接收器在 Manifest 里声明于该进程，不在线也会被拉起，且不会顺带起服务。
                                NotifyDispatchReceiver.dispatchSwitchSnapshot(context)
                                // 两处都要写：`notifyEnabled` 是服务端侧的投递总闸（alerts 配置），
                                // `alert_enabled` 是客户端要不要在本机弹通知（notifications 配置）。
                                syncNotifyField("alert_enabled", on)
                                alertPrefs?.let { base ->
                                    scope.launch { viewModel.tools.updateAlertConfig(base.copy(notifyEnabled = on)) }
                                }
                            }
                        )
                    }
                )

                // 免打扰：全局静默时段，时段可自定义。「后台守护」页也有同一个开关
                // （都写 KEY_DND_ENABLED + core 的 dnd_enabled），是两个入口指向同一真源。
                // 交互与告警类型行一致：右侧开关管开关，点整行改时段。
                UfiSettingsItem(
                    title = "免打扰时段",
                    description = if (dndOn) {
                        "${formatDndWindow(dndStart, dndEnd)} 静默（仅 critical 告警可突破）"
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
                                // 只写 prefs 不够：`:ufi_notify` 进程读的是自己那份 mirror_ 副本，
                                // 走 core 下发后由回显 applyRemote 补 AIDL 推送，两个进程才一致。
                                syncNotifyField("dnd_enabled", it)
                            }
                        )
                    }
                )

                // 通道自检：绕过所有开关 / 限频 / 免打扰直接发一条，
                // 用来区分"没配好"和"配好了但系统没放行"。
                UfiSettingsItem(
                    title = "发送测试通知",
                    description = "立即验证通知通道是否畅通（不受开关 / 限频 / 免打扰影响）",
                    modifier = Modifier.clickable {
                        val message = when (val result = NotificationCenter(context).sendTestNotification()) {
                            is NotificationCenter.TestResult.Success -> "已发送测试通知 · 下拉状态栏查看"
                            is NotificationCenter.TestResult.PermissionDenied -> "通知权限未授予，请在系统「应用通知」中开启 UFI-AXIS"
                            is NotificationCenter.TestResult.Failed -> "发送失败：${result.error}"
                        }
                        android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_LONG).show()
                    },
                    trailing = {
                        Text(
                            text = "点击试发",
                            style = UfiTextStyles.label,
                            color = LocalResolvedPalette.current.accent
                        )
                    }
                )
            }


            // ── 入口列表：本页唯一职责。右侧摘要 = 不点进去也能看出状态 ──
            UfiSettingsGroup {
                UfiGroupHeader("告警")
                UfiSettingsValue(
                    title = "告警与阈值",
                    description = "通知渠道 · 告警类型 · 阈值 · 免打扰",
                    value = when {
                        !configLoaded -> "加载中"
                        !masterEnabled -> "总开关已关闭"
                        else -> "$alertTypesOn/${ALERT_TYPE_KEYS.size} 类开启"
                    },
                    onClick = { navController.navigate(Routes.DETAIL_ALERT_SETTINGS) }
                )
                UfiSettingsValue(
                    title = "日常通知",
                    description = "离线上线 · 流量预警 · 短信 · 下载 · 设备事件",
                    value = "$dailyOn/${DAILY_NOTIFY_SCENES.size} 项开启",
                    onClick = { navController.navigate(Routes.DETAIL_DAILY_NOTIFY) }
                )
            }

            UfiSettingsGroup {
                UfiGroupHeader("投递与守护")
                UfiSettingsValue(
                    title = "后台守护",
                    description = "退到后台或进程被杀后仍周期拉取并推送",
                    value = if (guardState.enabled) "每 ${guardState.intervalMinutes} 分钟" else "已关闭",
                    onClick = { navController.navigate(Routes.DETAIL_BACKGROUND_GUARD) }
                )
                UfiSettingsValue(
                    title = "邮件通知",
                    description = "把新短信与选中的通知场景发送到邮箱",
                    // 只有真读到配置才敢说"未配置"：读失败时说未配置会误导用户重新填一遍
                    value = when {
                        mailState.config == null -> "加载中"
                        mailState.config?.enabled == true -> "已启用"
                        else -> "未启用"
                    },
                    onClick = { navController.navigate(Routes.DETAIL_EMAIL_NOTIFY) }
                )
            }

            Spacer(Modifier.height(Spacing.Large))
        }

        // 免打扰时段编辑弹窗：暂存-确认（拖动只改 draft，点「确认」才下发）——
        // 每拖一格就 PUT 一次会打出几十个请求，且中途状态（如 start==end）本来是无效值。
        if (dndDialogOpen) {
            DndWindowDialog(
                startHour = dndStart,
                endHour = dndEnd,
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
    }
}

/** 告警 perType 的 5 个类型键（与 [AlertSettingsScreen] 里「设备」「网络」两段的并集一致）。 */
private val ALERT_TYPE_KEYS = listOf("temperature", "battery", "traffic", "signal", "connectivity")

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
 * 免打扰时段编辑弹窗：两个整点滑块（0..23）+ 实时文案。
 *
 * 为什么是两个单值滑块而不是一个区间滑块：区间滑块只能表达 `start <= end`，
 * 而免打扰最常用的就是跨零点的 23→7，用区间滑块根本选不出来。
 *
 * 暂存-确认：拖动只改本地 draft，点「确认」才回调下发。
 */
@Composable
private fun DndWindowDialog(
    startHour: Int,
    endHour: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int, Int) -> Unit
) {
    val palette = LocalResolvedPalette.current
    var start by remember { mutableStateOf(startHour) }
    var end by remember { mutableStateOf(endHour) }

    UfiCustomDialog(
        visible = true,
        onDismiss = onDismiss,
        title = "免打扰时段",
        // 确认位保持 size = Standard 而不是 Small：后者高度是 SmallButtonHeight，
        // 与 dismiss 位（Secondary + Standard = ButtonHeight）不等高，看起来像"取消高/确认矮"。
        // 全仓弹窗统一 variant = Primary（确认）+ Secondary（取消），都用默认 Standard 档。
        confirmButton = {
            UfiButton(text = "确认", onClick = { onConfirm(start, end) })
        },
        dismissButton = {
            UfiButton(variant = UfiButtonVariant.Secondary, text = "取消", onClick = onDismiss)
        }
    ) {
        UfiDialogBody {
            Text(
                text = formatDndWindow(start, end) +
                    if (start != end) " · 共 ${dndDurationHours(start, end)} 小时" else "",
                style = UfiTextStyles.cardTitle,
                color = palette.accent
            )
            Text(
                text = "时段内所有通知静默（不响铃不震动），仅 critical 级告警可突破。",
                style = MaterialTheme.typography.bodySmall,
                color = palette.textSecondary
            )
            UfiDialogField(label = "开始 · %02d:00".format(start)) {
                HourSlider(hour = start, onHourChange = { start = it })
            }
            UfiDialogField(label = "结束 · %02d:00".format(end)) {
                HourSlider(hour = end, onHourChange = { end = it })
            }
        }
    }
}

/** 整点滑块：0..23，每格 1 小时，刻度标签在拖动时淡入。 */
@Composable
private fun HourSlider(hour: Int, onHourChange: (Int) -> Unit) {
    UfiValueSlider(
        value = hour.toFloat(),
        valueRange = 0f..23f,
        steps = 23,
        tickStep = 1f,
        tickLabelFormatter = { "%02d".format(it.roundToInt()) },
        onValueChange = { onHourChange(it.roundToInt()) }
    )
}

/** 静默时长（小时）；跨零点按加 24 算。 */
private fun dndDurationHours(startHour: Int, endHour: Int): Int =
    if (endHour > startHour) endHour - startHour else endHour + 24 - startHour


/**
 * Hero 状态卡：告警总开关行 + 一行三个 [UfiStatItem]。
 *
 * 2026-08-30：这是全仓**唯一**的 `AlertConfig.enabled` 开关入口 —— 告警设置页原先也有
 * 一张同款总开关卡，两处摆同一个开关只会让人怀疑哪个才算数，已删。
 */
@Composable
private fun NotificationsHeroCard(
    enabled: Boolean,
    configLoaded: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    alertTypesSummary: String,
    guardSummary: String,
    dndSummary: String
) {
    UfiSettingsGroup {
        AlertMasterSwitchRow(enabled, configLoaded, onCheckedChange)
        UfiDivider()
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.Medium),
            verticalAlignment = Alignment.CenterVertically
        ) {
            UfiStatItem(value = alertTypesSummary, label = "告警类型", modifier = Modifier.weight(1f))
            UfiStatItem(value = guardSummary, label = "后台守护", modifier = Modifier.weight(1f))
            UfiStatItem(value = dndSummary, label = "免打扰", modifier = Modifier.weight(1f))
        }
    }
}


/** 总开关行本体：圆形图标 + 标题 + 当前状态副文案 + [UfiSwitch]。不自带卡片容器。 */
@Composable
private fun AlertMasterSwitchRow(
    enabled: Boolean,
    configLoaded: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    val palette = LocalResolvedPalette.current
    // 未加载 / 已关闭都用次级色，只有真正在生效时才点亮 accent
    val tint = if (configLoaded && enabled) palette.accent else palette.textSecondary

    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.Small),
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
                imageVector = if (configLoaded && enabled) {
                    Icons.Default.NotificationsActive
                } else {
                    Icons.Default.NotificationsOff
                },
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(22.dp)
            )
        }
        Spacer(Modifier.width(Spacing.Large))
        Column(Modifier.weight(1f)) {
            Text(
                text = "告警总开关",
                style = UfiTextStyles.cardTitle,
                color = palette.textPrimary
            )
            Text(
                text = when {
                    !configLoaded -> "配置加载中，暂不可修改"
                    enabled -> "引擎正在检测并推送告警"
                    else -> "已关闭：停止检测、停止入库、停止推送"
                },
                style = MaterialTheme.typography.bodySmall,
                color = palette.textSecondary,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
        Spacer(Modifier.width(Spacing.Medium))
        UfiSwitch(
            // 未加载时显示为关（`enabled` 此时已是 false 兜底，这里再显式一次，
            // 表达"状态未知 → 不承诺已开启"这个意图）
            checked = configLoaded && enabled,
            enabled = configLoaded,
            onCheckedChange = onCheckedChange
        )
    }
}

