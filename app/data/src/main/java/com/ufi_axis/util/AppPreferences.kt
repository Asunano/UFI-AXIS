package com.ufi_axis.util

import android.content.Context
import android.content.SharedPreferences
import android.provider.Settings

class AppPreferences(private val context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("ufi_axis_prefs", Context.MODE_PRIVATE)

    var serverIp: String
        get() = prefs.getString(KEY_SERVER_IP, "") ?: ""
        set(value) {
            prefs.edit().putString(KEY_SERVER_IP, value).apply()
            notifyConnectionChanged()
        }

    var serverPort: Int
        get() = prefs.getInt(KEY_SERVER_PORT, 8088)
        set(value) {
            prefs.edit().putInt(KEY_SERVER_PORT, value).apply()
            notifyConnectionChanged()
        }

    var token: String
        get() = prefs.getString(KEY_TOKEN, "") ?: ""
        set(value) {
            prefs.edit().putString(KEY_TOKEN, value).apply()
            notifyConnectionChanged()
        }

    /**
     * 连接参数（IP / 端口 / token）变更后通报通知守护进程。
     *
     * 必需性：`:ufi_notify` 与主进程各持一份 `MODE_PRIVATE` SharedPreferences 内存缓存，
     * **不跨进程 reload**。重新配对后主进程写入新 token，守护进程仍用旧 token → 401/444 →
     * WebSocket 与兜底轮询双双失效，用户表现为"通知突然再也不来了"。
     *
     * 只在主进程、且用户已开启告警通知时通报（避免给关掉通知的用户凭空拉起常驻服务）。
     */
    private fun notifyConnectionChanged() {
        if (android.app.Application.getProcessName().contains(":")) return
        if (!prefs.getBoolean(com.ufi_axis.data.notification.NotificationCenter.KEY_ALERT_NOTIF, false)) return
        try {
            com.ufi_axis.data.notification.NotifyDispatchReceiver
                .dispatchConnectionChange(context, baseUrl, token)
        } catch (_: Exception) {
            // 通报失败不影响本次写入
        }
    }

    /**
     * 设备硬件标识（hwId）：**清除应用数据后依然保持不变**的稳定设备身份。
     *
     * 派生规则（按优先级）：
     * 1. prefs 已有缓存 → 直接返回（避免每次读 ContentResolver）；
     * 2. [Settings.Secure.ANDROID_ID] —— 同一 App 签名 + 同一用户下，清除应用数据/重装
     *    App 均不会改变（仅恢复出厂设置才变），因此清数据后 getter 会重新派生出**同一值**；
     * 3. 回退：ANDROID_ID 为 null/空/经典模拟器哨兵值（[EMULATOR_ANDROID_ID]）时，
     *    退化为**持久化的随机 UUID**。该场景下只能保证“尽量稳定”（清数据后仍会变化），
     *    此时依赖后端 hwId 去重会失效，退回旧行为。
     *
     * 后端据此字段（`device_hwid`）识别“同一台硬件换了指纹”并合并重复配对记录，
     * 修复「清除应用数据后重新配对导致 pairedFingerprints 无限增长」的缺陷。
     */
    var deviceHwid: String
        get() {
            val cached = prefs.getString(KEY_DEVICE_HWID, "") ?: ""
            if (cached.isNotBlank()) return cached
            // Settings.Secure.getString 在极端 ROM 上可能抛异常，统一按“取不到”处理并回退
            val androidId: String = runCatching {
                Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            }.getOrNull()?.trim().orEmpty()
            val hwid: String = if (androidId.isBlank() || androidId == EMULATOR_ANDROID_ID) {
                java.util.UUID.randomUUID().toString()
            } else {
                androidId
            }
            prefs.edit().putString(KEY_DEVICE_HWID, hwid).apply()
            return hwid
        }
        set(value) {
            prefs.edit().putString(KEY_DEVICE_HWID, value).apply()
        }

    /**
     * 本机设备指纹 = `base64url(SHA-256(设备公钥 SPKI))`，由 [DeviceKeyStore] 现算。
     *
     * 2026-08-28（严格设备独立性）：不再持久化、也不再由客户端上报。
     * - 旧实现委托 `deviceHwid`（ANDROID_ID 派生）：一个**可读出、可复制、可伪造**的字符串，
     *   谁拿到都能冒充这台设备，配对上限形同虚设。
     * - 现在指纹由密钥对唯一决定，私钥在 Keystore 里不可导出；服务端在 `/pairing/confirm`
     *   自己据上报公钥算指纹，**从不采信客户端自报值**。这里保留 getter 只为本地 UI
     *   （设备列表里高亮"本机"）。
     *
     * 没有 setter：指纹是密钥的函数，不是可写状态。换指纹的唯一方式是 [DeviceKeyStore.reset]。
     */
    val appFingerprint: String
        get() = DeviceKeyStore.fingerprint()

    var isSetupComplete: Boolean
        get() = prefs.getBoolean(KEY_SETUP_COMPLETE, false)
        set(value) = prefs.edit().putBoolean(KEY_SETUP_COMPLETE, value).apply()

    var autoRefresh: Boolean
        get() = prefs.getBoolean(KEY_AUTO_REFRESH, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_REFRESH, value).apply()

    /**
     * 日志总开关。关闭后 app 端不写任何日志（Logcat / 内存缓冲 / 落盘全部旁通），
     * 与 core 的 `log_enabled` 同义，两端通过 `PUT /api/config` 同步。
     *
     * **默认 false**（2026-09-04 由 true 改，与 core `AppSettings.DEFAULT_LOG_ENABLED` 保持一致）：
     * 所有日志只在用户显式开启后才记录。崩溃日志不受影响 —— `CrashHandler` 直接写文件，
     * 不经这里的闸门。
     */
    var logEnabled: Boolean
        get() = prefs.getBoolean(KEY_LOG_ENABLED, false)
        set(value) {
            prefs.edit().putBoolean(KEY_LOG_ENABLED, value).apply()
            DebugLog.masterEnabled = value
        }

    /**
     * app 端日志子开关（对应 core 配置 `app_log_enabled`，真源在 core 便于 web 一起控制）。
     * 与 [logEnabled] 是「与」关系；core 侧对应 `core_log_enabled`。默认 true。
     */
    var appLogEnabled: Boolean
        get() = prefs.getBoolean(KEY_APP_LOG_ENABLED, true)
        set(value) {
            prefs.edit().putBoolean(KEY_APP_LOG_ENABLED, value).apply()
            DebugLog.appEnabled = value
        }

    /**
     * core 端日志子开关的本地缓存（真源在 core 的 `core_log_enabled`）。
     * app 自己不用它做判断，只用于日志页开关回填与下发对比。默认 true。
     */
    var coreLogEnabled: Boolean
        get() = prefs.getBoolean(KEY_CORE_LOG_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_CORE_LOG_ENABLED, value).apply()

    var debugMode: Boolean
        get() = prefs.getBoolean(KEY_DEBUG_MODE, false)
        set(value) {
            prefs.edit().putBoolean(KEY_DEBUG_MODE, value).apply()
            DebugLog.enabled = value
        }

    /**
     * 已经向用户提示过的 **core 崩溃时间戳**（0 = 从未提示，2026-09-04）。
     *
     * core 崩溃后由 keepalive / START_STICKY 自动拉起，app 此前完全无感。现在连上设备后拉一次
     * `GET /api/service/crash`，`timestamp` 与本项不同就弹一次窗，弹完写回。
     *
     * 去重放在**本地**而不是让 core 记 ack：app 与 web 是两个独立展示端，
     * 谁先 ack 另一端就永远看不到这次崩溃。
     */
    var coreCrashShownAt: Long
        get() = prefs.getLong(KEY_CORE_CRASH_SHOWN_AT, 0L)
        set(value) = prefs.edit().putLong(KEY_CORE_CRASH_SHOWN_AT, value).apply()

    /**
     * 「调试入口已解锁」——**纯本机 UI 门禁**（关于页连点版本号 5 次后显形调试卡片），默认 false。
     *
     * 2026-09-04 从 [debugMode] 拆出来。原来连点解锁直接写 `debugMode = true`，而 `debug_mode`
     * 是**日志详细级别**、真源在 core：
     * - 解锁只写本地不下发 → app 与 core 分叉；
     * - 下一次 `refreshDeviceConfig()` 用 core 的 false 覆盖回来 → 解锁状态莫名消失；
     * - 反过来说，只想看看调试页的用户被顺手打开了全量 DEBUG/INFO 落盘。
     * 一个共享的业务开关不能同时当本机 UI 状态用。
     */
    var debugEntryUnlocked: Boolean
        get() = prefs.getBoolean(KEY_DEBUG_ENTRY_UNLOCKED, false)
        set(value) = prefs.edit().putBoolean(KEY_DEBUG_ENTRY_UNLOCKED, value).apply()

    var gatewayIp: String
        get() = prefs.getString(KEY_GATEWAY_IP, "192.168.0.1") ?: "192.168.0.1"
        set(value) = prefs.edit().putString(KEY_GATEWAY_IP, value).apply()

    /**
     * goform 密码是否已在 core 端设置。
     *
     * 2026-08-26（T40-4）：本地**不再保存密码明文**。原来 app 存一份、core 存一份，
     * 而 `GET /api/config` 返回的是脱敏值、无法回读真值 → 两端必然对不上；且明文躺在
     * SharedPreferences 里本身也没必要。现在密码只在「用户输入的那一次」直接 PUT 给 core，
     * 本地只留这个布尔用于 UI 显示「已设置 / 留空则不修改」。
     */
    var goformPasswordSet: Boolean
        get() = prefs.getBoolean(KEY_GOFORM_PASSWORD_SET, false)
        set(value) = prefs.edit().putBoolean(KEY_GOFORM_PASSWORD_SET, value).apply()

    var goformPort: Int
        get() = prefs.getInt(KEY_GOFORM_PORT, 8080)
        set(value) = prefs.edit().putInt(KEY_GOFORM_PORT, value).apply()

    /** 文件管理器视图模式（LIST / GRID），跨进程重启持久化网格视图偏好 */
    var fileViewMode: String
        get() = prefs.getString(KEY_FILE_VIEW_MODE, "LIST") ?: "LIST"
        set(value) = prefs.edit().putString(KEY_FILE_VIEW_MODE, value).apply()

    // ── 更新源 / 镜像源设置（2026-08-10：前端直连 GitHub + 镜像加速） ──

    /** 更新源模式："auto"（按国家自动切换）/ "mirror"（强制镜像）/ "direct"（GitHub 直连），默认 auto */
    var updateSourceMode: String
        get() = prefs.getString(KEY_UPDATE_SOURCE_MODE, "auto") ?: "auto"
        set(value) = prefs.edit().putString(KEY_UPDATE_SOURCE_MODE, value).apply()

    /** 镜像源下标（UpdateSource.MIRROR_PREFIXES 列表索引），默认 0 */
    var updateMirrorIndex: Int
        get() = prefs.getInt(KEY_UPDATE_MIRROR_INDEX, 0)
        set(value) = prefs.edit().putInt(KEY_UPDATE_MIRROR_INDEX, value.coerceIn(0, 2)).apply()

    /** 自定义镜像前缀（URL 前直接拼接；非空时优先于 updateMirrorIndex 使用），默认空 */
    var updateMirrorCustom: String
        get() = prefs.getString(KEY_UPDATE_MIRROR_CUSTOM, "") ?: ""
        set(value) {
            val v = value.trim().trimEnd('/')
            prefs.edit().putString(KEY_UPDATE_MIRROR_CUSTOM, if (v.isEmpty()) "" else "$v/").apply()
        }

    /** 最近一次国家检测结果缓存（"CN"/"US"/"" 表示未知），默认 "" */
    var lastCountry: String
        get() = prefs.getString(KEY_LAST_COUNTRY, "") ?: ""
        set(value) = prefs.edit().putString(KEY_LAST_COUNTRY, value).apply()

    /** 启动/进入更新页时自动检查前端更新（2026-08-12，对齐旧项目「启动时自动检查更新」开关），默认 true */
    var autoCheckUpdate: Boolean
        get() = prefs.getBoolean(KEY_AUTO_CHECK_UPDATE, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_CHECK_UPDATE, value).apply()

    /** 最近一次自动检查前端更新的时间（epoch ms；24h 节流），默认 0 = 从未自动检查 */
    var lastAutoCheckTime: Long
        get() = prefs.getLong(KEY_LAST_AUTO_CHECK_TIME, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_AUTO_CHECK_TIME, value).apply()

    /**
     * 短信页「通知」页签角标的已读水位：用户看过的最大验证码 msgId，默认 0 = 全部未看。
     *
     * 为什么要有它：角标原来直接取验证码总条数，而 `/api/sms/verification-codes` 返回的是
     * **缓存全量**（清理周期内的所有验证码），所以角标恒等于总数、看过也不消失。
     * 现在角标 = `msgId > 本水位` 的条数，进入通知页签即把水位推到当前最大 msgId → 角标清零，
     * 之后只有更新的验证码才会重新点亮。
     *
     * 与 NotificationCenter 的通知去重游标是**两个独立水位**：那个管「系统通知推没推过」，
     * 这个管「用户在 App 里看没看过」，合用会导致「推过通知就等于看过」。
     */
    var smsCodeSeenMsgId: Long
        get() = prefs.getLong(KEY_SMS_CODE_SEEN_MSG_ID, 0L)
        set(value) = prefs.edit().putLong(KEY_SMS_CODE_SEEN_MSG_ID, value).apply()

    /**
     * 拼接 baseUrl。
     * serverIp 为空时回退到设备网关地址 [gatewayIp]（默认 192.168.0.1），而非 127.0.0.1——
     * 后端运行在随身 WiFi 设备自身，手机 App 必须通过设备网关 IP 访问；回退 127.0.0.1 会让
     * App 连到自己的 localhost 而永远连不上后端（"前后端通信失败" 的常见诱因）。
     * [gatewayIp] 默认值 192.168.0.1 与设备默认管理地址一致，且仍可被手动配置覆盖。
     */
    val baseUrl: String
        get() {
            val ip = serverIp.ifBlank { gatewayIp }
            return "http://$ip:$serverPort/"
        }

    /**
     * 直接拼 URL 时使用的有效主机（不含 scheme / 端口），语义与 [baseUrl] 一致：
     * serverIp 为空时回退到网关 [gatewayIp]，避免拼出非法的 `http://:8088/...` 导致
     * 文件流 / 上传 / 安装等直连请求连不上后端。
     */
    val effectiveHost: String
        get() = serverIp.ifBlank { gatewayIp }

    val isConfigValid: Boolean
        get() = serverIp.isNotBlank()

    companion object {
        private const val KEY_SERVER_IP = "server_ip"
        private const val KEY_SERVER_PORT = "server_port"
        private const val KEY_TOKEN = "token"
        private const val KEY_SETUP_COMPLETE = "setup_complete"
        private const val KEY_AUTO_REFRESH = "auto_refresh"
        private const val KEY_LOG_ENABLED = "log_enabled"
        private const val KEY_APP_LOG_ENABLED = "app_log_enabled"
        private const val KEY_CORE_LOG_ENABLED = "core_log_enabled"
        private const val KEY_DEBUG_MODE = "debug_mode"
        /** 本机调试入口解锁标记（与 core 的 `debug_mode` 无关，见 [debugEntryUnlocked]）。 */
        private const val KEY_DEBUG_ENTRY_UNLOCKED = "debug_entry_unlocked"
        /** 已提示过的 core 崩溃时间戳（见 [coreCrashShownAt]）。 */
        private const val KEY_CORE_CRASH_SHOWN_AT = "core_crash_shown_at"
        private const val KEY_GATEWAY_IP = "gateway_ip"
        private const val KEY_GOFORM_PASSWORD_SET = "goform_password_set"
        private const val KEY_GOFORM_PORT = "goform_port"
        private const val KEY_FILE_VIEW_MODE = "file_view_mode"

        // ── 更新源 / 镜像源设置（2026-08-10） ──
        private const val KEY_UPDATE_SOURCE_MODE = "update_source_mode"
        private const val KEY_UPDATE_MIRROR_INDEX = "update_mirror_index"
        private const val KEY_UPDATE_MIRROR_CUSTOM = "update_mirror_custom"
        private const val KEY_LAST_COUNTRY = "last_country"

        // ── 自动检查更新（2026-08-12） ──
        private const val KEY_AUTO_CHECK_UPDATE = "auto_check_update"
        private const val KEY_LAST_AUTO_CHECK_TIME = "last_auto_check_time"
        private const val KEY_SMS_CODE_SEEN_MSG_ID = "sms_code_seen_msg_id"

        // ── 配对模式（Onboarding 重构）──

        /** 设备硬件标识缓存键（ANDROID_ID 派生，清应用数据后可重新派生出同一值）。 */
        private const val KEY_DEVICE_HWID = "device_hwid"

        /**
         * 经典 Android 模拟器 / 早期批次设备共用的 ANDROID_ID 哨兵值。
         * 命中该值说明标识不具备唯一性，必须回退到持久化随机 UUID。
         */
        private const val EMULATOR_ANDROID_ID = "9774d56d682e549c"
    }
}
