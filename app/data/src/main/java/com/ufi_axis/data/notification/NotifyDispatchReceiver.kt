package com.ufi_axis.data.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.ufi_axis.data.model.AlertRecord
import com.ufi_axis.util.AppJson
import com.ufi_axis.util.DebugLog
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

/**
 * 系统通知跨进程转交接收器 —— 运行在 `:ufi_notify` 进程（Manifest 声明 `android:process`）。
 *
 * 背景：系统状态栏通知的去重游标存在 `SharedPreferences`（`MODE_PRIVATE`），
 * 各进程各持一份内存缓存且**不跨进程 reload**。此前主进程（UI 轮询 / WS 推送 / WorkManager）
 * 与 `:ufi_notify` 都会调用 [NotificationCenter.maybeNotifyNewAlerts] 推进同一游标，导致：
 * - 同一条告警在两个进程各自判定为"新" → 重复响铃/震动（通知 id 固定 1000，视觉上只覆盖一条）；
 * - 并发写同一 key 时 last-writer-wins → 游标被回退或跳过 → **漏通知**。
 *
 * 收敛方案：系统通知只在本进程发射。主进程调用 [dispatch] 把告警转交过来；
 * 其余场景（短信 / 验证码 / 下载 / 测试等）经 [dispatchScene] / [dispatchTest] 转交，
 * 由 [NotificationCenter.notify] 的进程门统一收口。
 * 由于是显式 Intent 指向本应用组件，不受 Android 8+ 隐式广播限制；
 * 且 Manifest 声明的接收器会在必要时**自动拉起** `:ufi_notify` 进程 —— 守护服务被杀时通知依然可达。
 *
 * 该广播**不会**反向拉起主进程：所有处理都在本接收器内完成。
 */
class NotifyDispatchReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        intent ?: return

        // ⓿ 开关镜像：主进程随每次通信下发最新开关快照，规避 MODE_PRIVATE 读过期（见 NotifyPrefs）
        NotifyPrefs.applySnapshot(context, intent.getStringExtra(EXTRA_SWITCH_SNAPSHOT))

        // ⓪b 跨进程场景通知 / 测试通知（主进程 → :ufi_notify 唯一系统发射）
        val sceneJson = intent.getStringExtra(EXTRA_NOTIFY_SCENE)
        if (!sceneJson.isNullOrEmpty()) {
            handleSceneRequest(context, sceneJson)
            return
        }
        if (intent.getBooleanExtra(EXTRA_NOTIFY_TEST, false)) {
            DebugLog.i(TAG, "收到测试通知转交，在 ${android.app.Application.getProcessName()} 内发射")
            NotificationCenter(context).sendTestNotification()
            return
        }

        // ① 连接参数变更（重新配对 / 改 IP 端口）：转发给 NotifyService 重建 WS 与 API
        val newBaseUrl = intent.getStringExtra(EXTRA_BASE_URL)
        if (!newBaseUrl.isNullOrBlank()) {
            // 2026-09-05：这里原来**无条件** startForegroundService —— 它不走 startKeepAlive，
            // 完全绕过闸门，于是每次重新配对 / 改 IP 端口都会把常驻通知复活一次
            //（原注释写「守护服务未开启……忽略」是错的：异常只在系统拒绝启动时抛，
            //  正常情况下服务已经被拉起了）。现在先过同一道闸门。
            if (!NotifyPrefs.keepAliveShouldRun(context)) {
                DebugLog.i(TAG, "保活未开启，不为连接参数变更拉起守护服务（下次按开关启动时会读到新参数）")
                return
            }
            val token = intent.getStringExtra(EXTRA_TOKEN) ?: ""
            DebugLog.i(TAG, "收到连接参数变更，转发给 NotifyService")
            val svc = Intent()
                .setClassName(context.packageName, SERVICE_CLASS_NAME)
                .putExtra(EXTRA_BASE_URL, newBaseUrl)
                .putExtra(EXTRA_TOKEN, token)
                // 顺带下发快照：服务的 onStartCommand 会据此再判一次闸门（见 NotifyService）
                .putExtra(EXTRA_SWITCH_SNAPSHOT, intent.getStringExtra(EXTRA_SWITCH_SNAPSHOT))
            try {
                context.startForegroundService(svc)
            } catch (e: Exception) {
                // 后台 FGS 启动受限（Android 12+/14+）：忽略，下次前台启动时会补上
                DebugLog.w(TAG, "转发连接参数失败: ${e.message}")
            }
            return
        }



        // ② 系统通知转交
        val json = intent.getStringExtra(NotificationCenter.EXTRA_ALERTS_JSON) ?: return
        val alerts = try {
            AppJson.decodeFromString<List<AlertRecord>>(json)
        } catch (e: Exception) {
            DebugLog.w(TAG, "解析转交告警失败: ${e.message}")
            return
        }
        if (alerts.isEmpty()) return
        DebugLog.d(TAG, "收到主进程转交的 ${alerts.size} 条告警，在 ${android.app.Application.getProcessName()} 内发射系统通知")
        NotificationCenter(context).consumeDispatchedAlerts(alerts)
    }

    private fun handleSceneRequest(context: Context, json: String) {
        val req = try {
            AppJson.decodeFromString<CrossProcessSceneRequest>(json)
        } catch (e: Exception) {
            DebugLog.w(TAG, "解析跨进程场景通知失败: ${e.message}")
            return
        }
        val scene = NotifyScene.entries.find { it.sceneId == req.sceneId }
        if (scene == null) {
            DebugLog.w(TAG, "未知通知场景: ${req.sceneId}")
            return
        }
        DebugLog.d(TAG, "在 ${android.app.Application.getProcessName()} 发射场景通知 ${req.sceneId}")
        val center = NotificationCenter(context)
        center.notify(
            scene = scene,
            payload = NotifyPayload(
                title = req.title,
                message = req.message,
                bigText = req.bigText,
                notificationId = req.notificationId,
                deDupKey = req.deDupKey,
                deDupValue = req.deDupValue,
                silent = req.silent,
                groupKey = req.groupKey,
                category = req.category,
                priority = req.priority,
                autoCancel = req.autoCancel,
                tapType = req.tapType,
                smsPhone = req.smsPhone,
                onTap = center.resolveOnTap(req.tapType, req.smsPhone)
            )
        )
    }

    companion object {
        private const val TAG = "NotifyDispatch"

        /** NotifyService 完整类名（位于 :app 模块，本类在 :app:data，用字符串避免循环依赖）。 */
        private const val SERVICE_CLASS_NAME = "com.ufi_axis.notification.NotifyService"

        /** 连接参数变更 extra。 */
        const val EXTRA_BASE_URL = "extra_conn_base_url"
        const val EXTRA_TOKEN = "extra_conn_token"

        /** 开关快照 extra（主进程 → `:ufi_notify` 镜像，见 [NotifyPrefs.snapshot]）。 */
        const val EXTRA_SWITCH_SNAPSHOT = "extra_switch_snapshot"

        /** 跨进程场景通知 JSON（[CrossProcessSceneRequest]）。 */
        const val EXTRA_NOTIFY_SCENE = "extra_notify_scene"

        /** 测试通知转交标记。 */
        const val EXTRA_NOTIFY_TEST = "extra_notify_test"


        /**
         * 主进程 → `:ufi_notify` 转交系统通知。
         *
         * 只取最新的 [NotificationCenter.DISPATCH_MAX_ALERTS] 条：Intent 走 Binder 事务，
         * 载荷过大会抛 `TransactionTooLargeException`；更旧的告警即使漏发也已被游标判定为非新。
         */
        fun dispatch(context: Context, alerts: List<AlertRecord>) {
            if (alerts.isEmpty()) return
            val payload = alerts
                .sortedByDescending { it.timestamp }
                .take(NotificationCenter.DISPATCH_MAX_ALERTS)
            val json = try {
                AppJson.encodeToString(payload)
            } catch (e: Exception) {
                DebugLog.w(TAG, "序列化转交告警失败: ${e.message}")
                return
            }
            val intent = Intent(context, NotifyDispatchReceiver::class.java)
                .putExtra(NotificationCenter.EXTRA_ALERTS_JSON, json)
                .putExtra(EXTRA_SWITCH_SNAPSHOT, NotifyPrefs.snapshot(context))

            try {
                context.sendBroadcast(intent)
            } catch (e: Exception) {
                DebugLog.w(TAG, "转交广播发送失败: ${e.message}")
            }
        }

        /**
         * 主进程 → `:ufi_notify` 转交通用场景通知（[NotificationCenter.notify] 的进程门出口）。
         *
         * @return 广播是否发出（发射结果在目标进程内异步完成，本返回值只表示「已交给系统」）。
         */
        fun dispatchScene(
            context: Context,
            scene: NotifyScene,
            payload: NotifyPayload
        ): Boolean {
            val req = CrossProcessSceneRequest(
                sceneId = scene.sceneId,
                title = payload.title,
                message = payload.message,
                bigText = payload.bigText,
                notificationId = payload.notificationId,
                deDupKey = payload.deDupKey,
                deDupValue = payload.deDupValue,
                silent = payload.silent,
                groupKey = payload.groupKey,
                category = payload.category,
                priority = payload.priority,
                autoCancel = payload.autoCancel,
                tapType = payload.tapType,
                smsPhone = payload.smsPhone
            )
            val json = try {
                AppJson.encodeToString(req)
            } catch (e: Exception) {
                DebugLog.w(TAG, "序列化场景通知失败: ${e.message}")
                return false
            }
            val intent = Intent(context, NotifyDispatchReceiver::class.java)
                .putExtra(EXTRA_NOTIFY_SCENE, json)
                .putExtra(EXTRA_SWITCH_SNAPSHOT, NotifyPrefs.snapshot(context))
            return try {
                context.sendBroadcast(intent)
                true
            } catch (e: Exception) {
                DebugLog.w(TAG, "场景通知转交失败: ${e.message}")
                false
            }
        }

        /** 主进程 → `:ufi_notify` 转交测试通知（设置页「发送测试」）。 */
        fun dispatchTest(context: Context): Boolean {
            val intent = Intent(context, NotifyDispatchReceiver::class.java)
                .putExtra(EXTRA_NOTIFY_TEST, true)
                .putExtra(EXTRA_SWITCH_SNAPSHOT, NotifyPrefs.snapshot(context))
            return try {
                context.sendBroadcast(intent)
                true
            } catch (e: Exception) {
                DebugLog.w(TAG, "测试通知转交失败: ${e.message}")
                false
            }
        }

        /**
         * 主进程 → `:ufi_notify` 只下发开关快照（无告警、无连接参数）。
         *
         * 用途：**关掉「前台服务保活」时把镜像同步过去**。本接收器在 Manifest 里声明于
         * `:ufi_notify`，即使那个进程当前不在线，系统也会为这条显式广播把它拉起（一次性、
         * 短命，不需要 WakeLock / 闹钟）。少了这一步就留下唯一一个「关掉之后又自己回来」
         * 的窗口：进程不在线时关开关 → 镜像里还是上次的 `true` → 之后任何启动路径
         * （START_STICKY 重建 / 开机 / 重新配对）自查都会通过，常驻通知复活。
         *
         * [onReceive] 里 ⓿ 会无条件 `applySnapshot`，随后因为既无 baseUrl 也无 alerts 而返回 ——
         * 不会顺带把服务拉起来。
         */
        fun dispatchSwitchSnapshot(context: Context) {
            val intent = Intent(context, NotifyDispatchReceiver::class.java)
                .putExtra(EXTRA_SWITCH_SNAPSHOT, NotifyPrefs.snapshot(context))
            try {
                context.sendBroadcast(intent)
            } catch (e: Exception) {
                DebugLog.w(TAG, "开关快照广播发送失败: ${e.message}")
            }
        }

        /**
         * 主进程 → `:ufi_notify` 通报连接参数变更（重新配对后 token 轮换 / 改服务端 IP 端口）。
         *
         * 必须显式传值：`:ufi_notify` 读不到主进程刚写入的 prefs（`MODE_PRIVATE` 无跨进程 reload）。
         */
        fun dispatchConnectionChange(context: Context, baseUrl: String, token: String) {
            if (baseUrl.isBlank()) return
            val intent = Intent(context, NotifyDispatchReceiver::class.java)
                .putExtra(EXTRA_BASE_URL, baseUrl)
                .putExtra(EXTRA_TOKEN, token)
                .putExtra(EXTRA_SWITCH_SNAPSHOT, NotifyPrefs.snapshot(context))

            try {
                context.sendBroadcast(intent)
            } catch (e: Exception) {
                DebugLog.w(TAG, "连接参数变更广播发送失败: ${e.message}")
            }
        }
    }
}

/**
 * 主进程 → `:ufi_notify` 的场景通知载荷（可序列化；PendingIntent 不跨进程，
 * 点击行为用 [tapType]/[smsPhone] 语义在目标进程内重建）。
 */
@Serializable
data class CrossProcessSceneRequest(
    val sceneId: String,
    val title: String,
    val message: String,
    val bigText: String? = null,
    val notificationId: Int,
    val deDupKey: String? = null,
    val deDupValue: String? = null,
    val silent: Boolean = false,
    val groupKey: String? = null,
    val category: String? = null,
    val priority: Int? = null,
    val autoCancel: Boolean = true,
    val tapType: String? = null,
    val smsPhone: String? = null
)
