package com.ufi_axis.ui.screens

import android.content.Context
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavHostController
import com.ufi_axis.data.notification.NotificationCenter
import com.ufi_axis.ui.components.common.*
import com.ufi_axis.ui.theme.LocalResolvedPalette
import com.ufi_axis.ui.theme.Spacing
import com.ufi_axis.viewmodel.MainViewModel

/**
 * 「日常通知」二级页（2026-08-30 从 [AlertSettingsScreen] 拆出）。
 *
 * 为什么单独成页：这 6 个场景管的是「本机要不要弹某一类系统通知」，与阈值、与
 * 「哪些告警要检测」无关。原先它们作为「日常通知」分组挤在告警设置页第 5 张卡里，
 * 既拉长了那一页，又让人误以为关掉告警总开关这些也会停。
 *
 * **但前三项确实与告警引擎相连**（2026-09-07 澄清，别再把本页当成"与告警无关"）：
 * 连通性 / 套餐限额 / 设备事件对应 core 的告警类型 `connectivity` / `traffic_limit` /
 * `device_online|device_offline`，它们照样入 alerts 表、照样受 `AlertConfig.enabled` 与
 * `perType` 约束。其中 `traffic_80_enabled` / `device_events_enabled` 还被
 * `ComponentFactory` 当**取数闸门**用（关着连 goform 都不查），所以这两行关掉即停，
 * 但打开还需要在「告警与阈值」里把对应分类也打开 —— 副文案已写明，[AlertSettingsScreen]
 * 那三行则一次写全两道闸门。
 *
 * **但它们确实走通知引擎**：投递路径与告警完全一致 ——
 * `NotificationCenter.notify(scene, ...)` → [NotifyScene] 注册表（channel / importance /
 * 免打扰突破 / 限频）→ `sceneEnabledKey(scene)` 读本页写的这些 key 做闸门。
 * 这里的 6 个开关一对一映射 CONNECTIVITY / TRAFFIC_80 / DEVICE_EVENTS / SMS /
 * VERIFICATION_CODE / DOWNLOAD 六个场景，不存在"绕过引擎直接发通知"的旁路。
 *
 * 真源在 core（`PUT /api/notifications/config`）：本地 prefs 只是让 UI 立刻响应的缓存，
 * 每次改动都必须把对应 `coreField` 下发，否则 web 端或另一台设备看到的还是旧值。
 * 页面进入时先 [MainViewModel.tools].refreshNotificationConfig() 回读，失败静默沿用缓存。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DailyNotifyScreen(
    viewModel: MainViewModel,
    navController: NavHostController
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("ufi_axis_prefs", Context.MODE_PRIVATE) }

    // L1 全局通知总闸（2026-09-08）：本页每一项都在它下游 —— 总闸关着时这些分类
    // 即使为 true 也一条都不会发（判定在 NotificationCenter.notify）。所以统一置灰，
    // 不置灰就是"能开但没用"。真源与「通知与守护」页同一个键，那页负责改它。
    val masterOn = remember { prefs.getBoolean(NotificationCenter.KEY_NOTIFY_MASTER, false) }

    LaunchedEffect(Unit) { viewModel.tools.refreshNotificationConfig() }

    /** 字段级下发：本地 prefs 已写完再推 core，只传改动的那一个键。 */
    val syncNotifyField: (String, Boolean) -> Unit = { field, value ->
        viewModel.tools.updateNotificationConfig(mapOf(field to value))
    }

    UfiScreenScaffold(title = "日常通知", navController = navController, showBack = true) { padding ->
        UfiPageBackground(modifier = Modifier.padding(padding)) {
            // 2026-08-30：删掉了页首那段「走通知引擎但不经告警引擎」的说明 ——
            // 这是实现细节，普通用户看不懂也不需要判断，说明保留在本文件 KDoc 里给维护者看。

            UfiSettingsGroup {
                UfiGroupHeader("设备与网络")
                NotificationScenarioSwitch(
                    prefs = prefs,
                    title = "设备离线/上线",
                    subtitle = "断开超过 1 分钟才提醒，恢复后补一条静默通知",
                    key = NotificationCenter.KEY_CONNECTIVITY_NOTIF,
                    defaultEnabled = false,
                    coreField = "connectivity_enabled",
                    onSync = syncNotifyField,
                    gateOn = masterOn
                )
                NotificationScenarioSwitch(
                    prefs = prefs,
                    title = "流量限额预警",
                    subtitle = "关闭后立即停止；开启后还需在「告警与阈值」中打开「套餐限额预警」",
                    key = NotificationCenter.KEY_TRAFFIC_80_NOTIF,
                    defaultEnabled = false,
                    coreField = "traffic_80_enabled",
                    onSync = syncNotifyField,
                    gateOn = masterOn
                )
                NotificationScenarioSwitch(
                    prefs = prefs,
                    title = "设备事件",
                    subtitle = "关闭后立即停止；开启后还需在「告警与阈值」中打开设备接入 / 离开提醒",
                    key = NotificationCenter.KEY_DEVICE_EVENTS_NOTIF,
                    defaultEnabled = false,
                    coreField = "device_events_enabled",
                    onSync = syncNotifyField,
                    gateOn = masterOn
                )
            }

            UfiSettingsGroup {
                UfiGroupHeader("短信")
                NotificationScenarioSwitch(
                    prefs = prefs,
                    title = "新短信通知",
                    subtitle = "新短信到达时显示号码与摘要",
                    key = NotificationCenter.KEY_SMS_NOTIF,
                    defaultEnabled = false,
                    coreField = "sms_enabled",
                    onSync = syncNotifyField,
                    gateOn = masterOn
                )
                NotificationScenarioSwitch(
                    prefs = prefs,
                    title = "验证码提取",
                    // 2026-09-08：副文案原写"依附短信通知"，那是投递路径查错键的产物
                    //（notifyVerificationCode 当时读 KEY_SMS_NOTIF）。现已各自成闸。
                    subtitle = "识别验证码并单独提示，与「新短信通知」互不影响",
                    key = NotificationCenter.KEY_VERIFICATION_NOTIF,
                    defaultEnabled = false,
                    coreField = "verification_enabled",
                    onSync = syncNotifyField,
                    gateOn = masterOn
                )
            }

            UfiSettingsGroup {
                UfiGroupHeader("下载")
                NotificationScenarioSwitch(
                    prefs = prefs,
                    title = "下载完成/失败",
                    subtitle = "任务结束时通知，点击进入下载管理",
                    key = NotificationCenter.KEY_DOWNLOAD_NOTIF,
                    defaultEnabled = false,
                    coreField = "download_enabled",
                    onSync = syncNotifyField,
                    gateOn = masterOn
                )
            }

            UfiSettingsGroup {
                UfiGroupHeader("内网穿透")
                NotificationScenarioSwitch(
                    prefs = prefs,
                    title = "隧道失败通知",
                    // 与设备端那一侧串联：core 的 tunnel_notify_on_failure 决定"要不要推"，
                    // 本行决定"本机要不要弹"。2026-09-08 才有独立的键，此前借用告警键。
                    subtitle = "隧道启动失败或意外断开时提醒；还需在「隧道设置」中开启失败通知",
                    key = NotificationCenter.KEY_TUNNEL_NOTIF,
                    defaultEnabled = false,
                    coreField = "tunnel_enabled",
                    onSync = syncNotifyField,
                    gateOn = masterOn
                )
            }

            Spacer(Modifier.height(Spacing.Large))
        }
    }
}

/**
 * 通知场景开关行：标题 + 副标 + Switch。
 *
 * 本地 prefs 只是缓存（立即写、UI 立刻响应），真源在 core：
 * 写完必须调 [onSync] 把 `coreField` 下发到 `PUT /api/notifications/config`（T40-6），
 * 否则从 web 或另一台设备进来看到的还是旧值。
 */
@Composable
internal fun NotificationScenarioSwitch(
    prefs: android.content.SharedPreferences,
    title: String,
    subtitle: String,
    key: String,
    defaultEnabled: Boolean,
    coreField: String,
    onSync: (String, Boolean) -> Unit,
    /**
     * 上游闸门是否放行（2026-09-08 新增）。false → 置灰 + 副文案说明原因。
     *
     * 为什么必须有：本行写的是 L3 分类键，而实际投递还要过 L1 全局总闸
     * （`NotificationCenter.notify`）。不置灰时用户能把它打开却收不到任何通知，
     * 且界面没有任何提示 —— 这正是本轮在清的那类"假开关"。
     */
    gateOn: Boolean = true
) {
    val palette = LocalResolvedPalette.current
    var enabled by remember { mutableStateOf(prefs.getBoolean(key, defaultEnabled)) }
    UfiSettingsItem(
        title = title,
        description = if (gateOn) subtitle else "请先在「通知与守护」中开启「全局通知」",
        trailing = {
            // 2026-08-31：M3 Switch → 公共 UfiSwitch（配色 token 相同，几何统一到 42×24dp）
            UfiSwitch(
                checked = enabled,
                enabled = gateOn,
                onCheckedChange = {
                    enabled = it
                    prefs.edit().putBoolean(key, it).apply()
                    onSync(coreField, it)
                }
            )
        }
    )
}

/**
 * 「日常通知」全部场景的 prefs key → 默认值。
 *
 * 供 [NotificationsGuardScreen] 算入口行的「N / 7 项开启」摘要，
 * 与上面 7 个 [NotificationScenarioSwitch] 的 key/default 必须一一对应 ——
 * 加了新场景只改一处会让摘要分母对不上。
 */
internal val DAILY_NOTIFY_SCENES: List<Pair<String, Boolean>> = listOf(
    NotificationCenter.KEY_CONNECTIVITY_NOTIF to false,
    NotificationCenter.KEY_TRAFFIC_80_NOTIF to false,
    NotificationCenter.KEY_DEVICE_EVENTS_NOTIF to false,
    NotificationCenter.KEY_SMS_NOTIF to false,
    NotificationCenter.KEY_VERIFICATION_NOTIF to false,
    NotificationCenter.KEY_DOWNLOAD_NOTIF to false,
    NotificationCenter.KEY_TUNNEL_NOTIF to false
)
