package com.ufi_axis.ui.screens

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.navigation.NavHostController
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.ufi_axis.data.notification.GuardState
import com.ufi_axis.data.notification.NotificationCenter
import com.ufi_axis.data.notification.NotificationConfigClient
import com.ufi_axis.ui.components.common.*
import com.ufi_axis.ui.theme.*
import com.ufi_axis.viewmodel.MainViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 「后台守护」设置二级页（notifications-fix-plan Part 3.3 ②）。
 *
 * - 后台轮询总开关 + 间隔单选（15/30/60min，WorkManager 平台下限 15min）
 * - 前台服务保活（可选增强，默认关；开启后经 AIDL 启动 :ufi_notify 进程 NotifyService 前台服务）
 * - 免打扰时段开关（23:00-07:00，仅 critical 告警突破）
 * - 后台任务状态卡（当前状态 / 上次运行 / 下次运行 / 上次结果）
 *
 * 跨进程：本页所有开关在写本地偏好（ufi_axis_prefs，两进程共享文件）的同时，经
 * NotificationConfigClient（AIDL）异步同步到 :ufi_notify 通知进程（绑定失败静默跳过）。
 *
 * 布局：UfiScreenScaffold + UfiPageBackground + UfiSettingsGroup 分组卡。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackgroundGuardScreen(
    viewModel: MainViewModel,
    navController: NavHostController
) {
    val context = LocalContext.current
    val notificationCenter = remember { NotificationCenter(context) }
    // AIDL 客户端：UI 进程 ↔ :ufi_notify 通知进程（本页内绑定/解绑，避免 ViewModel 复杂化）
    val configClient = remember { NotificationConfigClient() }
    val palette = LocalResolvedPalette.current

    // 状态流：GuardScheduler.state（enabled/interval/lastRunAt/nextRunAt/lastResult）
    val guardState by viewModel.backgroundGuard.state.collectAsState()

    // 本地可编辑状态（开关直接写偏好 + 刷新 scheduler state）
    var dndEnabled by remember { mutableStateOf(notificationCenter.isDndEnabled()) }
    val dndWindow = remember { notificationCenter.dndWindow() }
    var keepAliveEnabled by remember { mutableStateOf(viewModel.backgroundGuard.isForegroundKeepAlive()) }
    // 全局 Toast 反馈（UfiToastHost）
    var toastMessage by remember { mutableStateOf<ToastMessage?>(null) }

    // ── 「假开关」防线（2026-09-05）──
    //
    // 「前台服务保活」是能力型开关：常驻通知发不出去时 startForeground 根本起不来
    //（本页 onCheckedChange 自己也承认这一点：无权限时只弹 Toast、不启动服务）。
    // 所以显示状态 = 本地开关 AND 系统放行，缺一显示为关并在描述里说明原因 + 给系统设置入口。
    // 写法严格照抄同页无障碍开关 2026-08-20 的修法（mutableStateOf + ON_RESUME 重检）——
    // remember{} 一次性快照会让用户从系统设置返回后看到旧状态，那个坑当时只修了无障碍这一项。
    val readNotifAllowed = { NotificationManagerCompat.from(context).areNotificationsEnabled() }
    var notifAllowed by remember { mutableStateOf(readNotifAllowed()) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                notifAllowed = readNotifAllowed()
                // prefs 也要重读：core 回显（refreshNotificationConfig）、「通知与守护」页、
                // 别端都可能改掉它，只重检权限会让页面与真源分叉。
                keepAliveEnabled = viewModel.backgroundGuard.isForegroundKeepAlive()
                dndEnabled = notificationCenter.isDndEnabled()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    /** 打开系统「应用通知」设置页 —— 系统侧关掉本应用通知后，这是唯一的修复入口。 */
    val openSystemNotifSettings = {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            )
        }
        Unit
    }


    // 返回页面/开关切换后刷新（Worker 运行结果回填）；异步绑定通知进程（失败静默，不影响 UI）
    LaunchedEffect(Unit) {
        // T40-6：看护配置真源在 core。先回读（失败静默沿用本地缓存），再 refresh 让
        // guardState 反映回读后的值 —— 顺序反了会先显示旧值再跳一次。
        // 传 backgroundGuard 本身（它就是 GuardScheduler），远端改了间隔要能重调度 WorkManager。
        viewModel.tools.refreshNotificationConfig(viewModel.backgroundGuard)
        viewModel.backgroundGuard.refresh()
        configClient.bind(context)
    }

    /** 看护相关开关的字段级下发（本地已写完再推 core，只传改动的键）。 */
    val syncNotifyField: (String, Any) -> Unit = { field, value ->
        viewModel.tools.updateNotificationConfig(mapOf(field to value), viewModel.backgroundGuard)
    }
    DisposableEffect(Unit) {
        onDispose { configClient.unbind(context) }
    }

    UfiScreenScaffold(title = "后台守护", navController = navController, showBack = true) { padding ->
        UfiPageBackground(modifier = Modifier.padding(padding)) {

            // ═══════════ ① 后台轮询 ═══════════
            UfiSettingsGroup {
                UfiGroupHeader("后台轮询")
                UfiSettingsItem(
                    title = "后台轮询",
                    description = "App 退到后台或进程被杀后，周期拉取设备告警并推送通知",
                    trailing = {
                        UfiSwitch(
                            checked = guardState.enabled,
                            onCheckedChange = { enabled ->
                                if (enabled && !NotificationManagerCompat.from(context).areNotificationsEnabled()) {
                                    // 2026-08-10 权限补强：后台轮询依赖系统通知，未授权则引导去开启（不静默开启）
                                    toastMessage = ToastMessage("后台轮询依赖通知权限，请先开启", ToastType.WARNING)
                                    context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
                                    return@UfiSwitch
                                }
                                viewModel.backgroundGuard.setEnabled(enabled)
                                // 经 AIDL 同步到通知进程（未绑定则静默跳过；prefs 已写，进程重启后会读取）
                                configClient.setGuardEnabled(enabled)
                                syncNotifyField("guard_enabled", enabled)
                            }
                        )
                    }
                )
                UfiSettingsItem(
                    title = "轮询间隔",
                    description = when (guardState.intervalMinutes) {
                        15 -> "15 分钟（推荐）"
                        60 -> "60 分钟（最省电）"
                        else -> "30 分钟（平衡）"
                    },
                    enabled = guardState.enabled,
                    trailing = {
                        // 2026-08-31：3 个手绘 FilterChip → 公共 UfiSingleChipSelector
                        //（新增的 enabled 参数负责整组置灰；chip 间距 6dp → Spacing.Small 4dp）
                        UfiSingleChipSelector(
                            options = listOf("15" to "15", "30" to "30", "60" to "60"),
                            selectedValue = guardState.intervalMinutes.toString(),
                            onSelect = { value ->
                                val minutes = value.toInt()
                                viewModel.backgroundGuard.setInterval(minutes)
                                configClient.setGuardIntervalMinutes(minutes)
                                syncNotifyField("guard_interval_minutes", minutes)
                            },
                            wrapContent = true,
                            enabled = guardState.enabled
                        )
                    }
                )
            }

            // ═══════════ ② 增强 ═══════════
            UfiSettingsGroup {
                UfiGroupHeader("增强")
                UfiSettingsItem(
                    title = "前台服务保活",
                    // 2026-09-05：关闭保活是**有代价**的，文案必须说清，不能静默降级。
                    // 保活关掉后 `:ufi_notify` 的 WebSocket 与 60s/5min 兜底轮询都不在了，
                    // 后台告警只剩 WorkManager 那条周期任务（平台下限 15min，且要「后台轮询」开着）。
                    description = when {
                        keepAliveEnabled && !notifAllowed ->
                            "系统已禁止本应用通知，保活无法生效，点击前往系统设置开启"
                        keepAliveEnabled ->
                            "已开启：通知进程前台常驻（状态栏有一条「通知守护」常驻通知），告警实时到达"
                        guardState.enabled ->
                            "已关闭：无常驻通知、更省电；代价是后台告警退回「后台轮询」的 " +
                                "${guardState.intervalMinutes} 分钟兜底，不再实时"
                        else ->
                            "已关闭：「后台轮询」也没开 —— App 退到后台后收不到任何告警通知"
                    },

                    modifier = if (keepAliveEnabled && !notifAllowed) {
                        Modifier.clickable { openSystemNotifSettings() }
                    } else {
                        Modifier
                    },
                    trailing = {
                        UfiSwitch(
                            // 显示状态 = 本地开关 AND 系统放行（见上方「假开关」防线注释）
                            checked = keepAliveEnabled && notifAllowed,
                            onCheckedChange = { enabled ->
                                if (enabled) {
                                    // Android 13+ 需通知权限展示常驻通知；无权限则引导开启，不启动服务
                                    if (readNotifAllowed()) {
                                        notifAllowed = true
                                        // ★ 顺序：先写 prefs 再启动 —— NotificationConfigClient.startKeepAlive
                                        //   的闸门读的正是这个 pref，反过来会被自己刚要打开的开关挡掉。
                                        keepAliveEnabled = true
                                        viewModel.backgroundGuard.setForegroundKeepAlive(true)
                                        NotificationConfigClient.startKeepAlive(context)
                                        syncNotifyField("guard_foreground_keepalive_enabled", true)
                                        // 通知进程启动后立即同步当前开关/间隔（best-effort，未绑定则静默）
                                        configClient.setGuardEnabled(guardState.enabled)
                                        configClient.setGuardIntervalMinutes(guardState.intervalMinutes)
                                        configClient.reloadConfig()
                                    } else {
                                        notifAllowed = false
                                        toastMessage = ToastMessage("请先开启通知权限再启用保活", ToastType.WARNING)
                                        openSystemNotifSettings()
                                    }
                                } else {
                                    keepAliveEnabled = false
                                    viewModel.backgroundGuard.setForegroundKeepAlive(false)
                                    // 顺序有意义：先经 AIDL 让通知进程把 mirror_ 刷成 false 并**当场**
                                    // stopForeground(STOP_FOREGROUND_REMOVE)——本页 configClient 正处于
                                    // BIND_AUTO_CREATE 绑定中，单靠 stopService 不会销毁服务，
                                    // 那条常驻通知会挂到离页 onDispose 才消失；再 stopService 收尾。
                                    configClient.setForegroundKeepAlive(false)
                                    NotificationConfigClient.stopKeepAlive(context)
                                    syncNotifyField("guard_foreground_keepalive_enabled", false)
                                }
                            }
                        )
                    }
                )

                // 2026-08-10 无障碍保活：系统级保活（国产 ROM 杀全家豁免）。点击检查服务启用状态，
                // 未启用则跳系统「无障碍设置」引导用户授权；状态实时回显。
                // 2026-08-20 修复：旧实现用 remember {} 一次性计算，用户从系统设置返回后状态不刷新。
                // 改为 mutableStateOf + Lifecycle ON_RESUME 重检，确保实时反映开启/关闭变化。
                val a11yServiceName = "com.ufi_axis.notification.UfiNotifyAccessibilityService"
                var a11yEnabled by remember { mutableStateOf(checkAccessibilityEnabled(context, a11yServiceName)) }
                DisposableEffect(lifecycleOwner) {
                    val observer = LifecycleEventObserver { _, event ->
                        if (event == Lifecycle.Event.ON_RESUME) {
                            a11yEnabled = checkAccessibilityEnabled(context, a11yServiceName)
                        }
                    }
                    lifecycleOwner.lifecycle.addObserver(observer)
                    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
                }
                UfiSettingsItem(
                    title = "无障碍保活",
                    description = if (a11yEnabled) "已开启：系统级保活生效，通知进程几乎不被杀"
                    else "系统级保活（国产 ROM 推荐）：需在系统设置授权，点击开启",
                    trailing = {
                        if (a11yEnabled) {
                            Text(
                                text = "已开启",
                                style = MaterialTheme.typography.labelSmall,
                                color = palette.success,
                                modifier = Modifier.padding(end = 4.dp)
                            )
                        } else {
                            UfiButton(
                                size = UfiButtonSize.Small,
                                text = "去开启",
                                onClick = {
                                    context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                                    toastMessage = ToastMessage("在无障碍列表中找到「UFI 通知守护」并开启", ToastType.INFO, durationMs = 5000L)
                                }
                            )
                        }
                    }
                )
                UfiSettingsItem(
                    title = "免打扰时段",
                    // 时段本身在「通知与守护」页改（点那一行开弹窗）；本页只留开关，
                    // 副标读同一份 prefs，免得两页显示的时段不一样。
                    description = "${formatDndWindow(dndWindow.first, dndWindow.second)} 静默，仅严重（critical）告警可突破",
                    trailing = {
                        UfiSwitch(
                            checked = dndEnabled,
                            onCheckedChange = { enabled ->
                                notificationCenter.setDndEnabled(enabled)
                                configClient.setDndEnabled(enabled)
                                dndEnabled = enabled
                                syncNotifyField("dnd_enabled", enabled)
                            }
                        )
                    }
                )
            }

            // ═══════════ ③ 后台任务状态 ═══════════
            UfiSettingsGroup {
                UfiGroupHeader("后台任务状态")
                StatusRow(label = "当前状态", value = guardStatusLabel(guardState))
                StatusRow(label = "上次运行", value = formatTime(guardState.lastRunAt))
                StatusRow(label = "下次运行", value = formatTime(guardState.nextRunAt))
                StatusRow(label = "上次结果", value = guardState.lastResult)
            }

            Spacer(Modifier.height(Spacing.Large))
        }
        UfiToastHost(toastMessage = toastMessage, onDismiss = { toastMessage = null })
    }
}

/** 状态卡行：左标签 + 右值。 */
@Composable
private fun StatusRow(label: String, value: String) {
    val palette = LocalResolvedPalette.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = UfiTextStyles.body,
            color = palette.textSecondary,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = value,
            style = UfiTextStyles.bodyEmphasis,
            color = palette.textPrimary
        )
    }
}

/** 当前状态文案。 */
private fun guardStatusLabel(state: GuardState): String = when {
    !state.enabled -> "已暂停"
    state.lastRunAt == 0L -> "等待首次执行"
    else -> "运行中"
}

/** epoch ms → "MM-dd HH:mm"，0 → "从未运行/—"。 */
private fun formatTime(ms: Long): String {
    if (ms <= 0L) return "—"
    return SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(ms))
}

/** 检查本应用的无障碍服务是否已启用 */
private fun checkAccessibilityEnabled(context: Context, serviceName: String): Boolean {
    val am = context.getSystemService(AccessibilityManager::class.java) ?: return false
    return am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        .any { it.resolveInfo.serviceInfo.packageName == context.packageName &&
            it.resolveInfo.serviceInfo.name == serviceName }
}
