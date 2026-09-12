package com.ufi_axis_core.util

import android.content.Context
import android.content.SharedPreferences
import com.ufi_axis_core.util.AppLogger
import java.security.SecureRandom

/**
 * 应用配置管理器
 *
 * 持久化存储后端服务的关键配置项，支持运行时修改。
 * 修改认证或端口配置后需重启服务才能生效。
 */
class AppSettings(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val PREFS_NAME = "ufi_axis_settings"

        // Keys
        private const val KEY_PORT = "server_port"
        private const val KEY_AUTO_START = "auto_start_on_boot"
        private const val KEY_BG_SERVICE_ENABLED = "background_service_enabled"
        private const val KEY_GOFORM_IP = "goform_ip"
        private const val KEY_GOFORM_PORT = "goform_port"
        private const val KEY_GOFORM_PASSWORD = "goform_password"
        private const val KEY_DEVICE_PROFILE_ID = "device_profile_id"
        private const val KEY_FIELD_NORMALIZATION = "field_normalization_enabled"
        private const val KEY_GOFORM_DUMP_ENABLED = "goform_dump_enabled"
        private const val KEY_GOFORM_COMMAND_ENABLED = "goform_command_enabled"
        private const val KEY_DEBUG_MODE = "debug_mode"
        private const val KEY_LOG_ENABLED = "log_enabled"
        private const val KEY_CORE_LOG_ENABLED = "core_log_enabled"
        private const val KEY_APP_LOG_ENABLED = "app_log_enabled"
        private const val KEY_QOS_ENABLED = "qos_enabled"
        private const val KEY_QOS_SHELL_MAX = "qos_shell_max_concurrent"
        private const val KEY_QOS_CACHE_TTL = "qos_cache_ttl_ms"
        private const val KEY_QOS_GOFORM_QUERY_MAX = "qos_goform_query_max"
        private const val KEY_QOS_GOFORM_SET_MAX = "qos_goform_set_max"
        private const val KEY_ADB_AUTO_START = "adb_auto_start_on_boot"
        private const val KEY_ALERT_CONFIG = "alert_config"
        private const val KEY_TRAFFIC_DAILY_BASELINE = "traffic_daily_baseline"
        private const val KEY_TRAFFIC_AUTO_OFF_CONFIG = "traffic_auto_off_config"
        private const val KEY_TRAFFIC_AUTO_OFF_STATE = "traffic_auto_off_state"
        private const val KEY_MONITOR_PREFERENCES = "monitor_preferences"
        private const val KEY_NOTIFICATION_CONFIG = "notification_config"
        private const val KEY_ARIA2_RPC_SECRET = "aria2_rpc_secret"

        // ── 更新通道（2026-08-10：后端自拉取更新）──
        private const val KEY_UPDATE_URL = "update_url"

        // ── 更新镜像前缀（2026-08-12：可配置，空串=直连）──
        private const val KEY_UPDATE_MIRROR_BASE = "update_mirror_base"

        // ── 更新状态持久化（2026-08-10 P0-4/P0-2：RESULT 机制最小版）──
        private const val KEY_PENDING_UPDATE = "pending_update"
        private const val KEY_PENDING_LOCAL_INSTALL_LUT = "pending_local_install_lut"
        private const val KEY_LAST_UPDATE_RESULT = "last_update_result"
        private const val KEY_LAST_SIG_FINGERPRINT = "last_sig_fingerprint"

        // ── SMS 已读状态（本地管理）──
        private const val KEY_SMS_HIGH_WATER_MARK = "sms_high_water_mark"
        private const val KEY_SMS_INITIALIZED = "sms_initialized"

        // ── SMS 验证码解析 ──
        private const val KEY_SMS_CODE_ENABLED = "sms_code_enabled"
        private const val KEY_SMS_CODE_CLEANUP_HOURS = "sms_code_cleanup_hours"
        private const val KEY_SMS_CODE_AUTO_COPY = "sms_code_auto_copy"

        // ── SMS 拦截（黑名单 + 关键词）──
        private const val KEY_SMS_FILTER_EXEMPT_VC = "sms_filter_exempt_verification_code"
        private const val KEY_SMS_FILTER_STORE_FULL_BODY = "sms_filter_store_full_body"

        // ── 配对模式（Onboarding 重构）──
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_PAIRED = "paired"
        private const val KEY_PAIRING_CODE = "pairing_code"
        private const val KEY_PAIRED_APP_FP = "paired_app_fingerprint"
        private const val KEY_PAIRED_AT = "paired_at"
        private const val KEY_PAIRING_ENABLED = "pairing_enabled"
        private const val KEY_PAIRING_MAX_DEVICES = "pairing_max_devices"

        // ── 配对密码（配对认证，2026-08-11）──
        // 字段名与 key 沿用 `device_password*` / `KEY_DEVICE_PASSWORD_*`：它们是持久化 key 的一部分，
        // 改名会让存量设备读不出已设置的密码（等于把人锁在门外），所以只统一注释口径。
        private const val KEY_DEVICE_PASSWORD_HASH = "device_password_hash"
        private const val KEY_DEVICE_PASSWORD_SET = "device_password_set"
        // 2026-09-08：PBKDF2 化（见 setDevicePassword / verifyDevicePassword）。
        private const val KEY_DEVICE_PASSWORD_SALT = "device_password_salt"
        private const val KEY_DEVICE_PASSWORD_VERSION = "device_password_version"

        /**
         * [resetAll] 不会清除的键 —— 设备身份与凭据。
         *
         * 「恢复默认配置」只该恢复配置。把这几个键一起清掉等于让设备退回出厂默认密码态、
         * 重新对局域网散发配对码（详见 [resetAll] 的注释）。
         *
         * `aria2_rpc_secret` 也在列：它清掉后已在跑的 aria2 进程会立刻鉴权失败，
         * 而它本身不是用户可见的配置项，没有"恢复默认"的意义。
         */
        private val PRESERVED_ON_RESET = setOf(
            KEY_DEVICE_ID,
            KEY_PAIRED,
            KEY_PAIRING_CODE,
            KEY_PAIRED_APP_FP,
            KEY_PAIRED_AT,
            KEY_DEVICE_PASSWORD_HASH,
            KEY_DEVICE_PASSWORD_SET,
            KEY_DEVICE_PASSWORD_SALT,
            KEY_DEVICE_PASSWORD_VERSION,
            KEY_ARIA2_RPC_SECRET
        )

        /**
         * 可备份的配置键白名单。
         *
         * **进包的判据**：它是"用户设定的配置"，且换一台设备仍然成立。
         *
         * 因此以下几类刻意不在列（每一条都是"还原了反而出错"）：
         * - **身份与凭据**：`device_id` / 配对指纹 / 配对密码哈希与盐 / `pairing_code`
         *   —— 备份包不该能把设备所有权搬走；`aria2_rpc_secret` 是本机运行凭据，
         *   还原过去只会让正在跑的 aria2 立刻鉴权失败。
         * - **运行态**：`pending_update` 系列、`traffic_auto_off_state`、
         *   `traffic_daily_baseline`、`sms_high_water_mark`、`last_seen_bundled_web_version`
         *   —— 它们描述"这台设备现在进行到哪了"，跨设备还原等于把进度倒回去。
         * - **崩溃标记**：`last_crash_*`，还原过去等于伪造一次别的设备的崩溃。
         * - **一次性迁移标记**：`sms_initialized` —— 还原它会让迁移被跳过。
         *
         * 敏感字段（`goform_password`、`tunnel_cf_token`）**在列**：备份的价值就在于
         * 恢复后能直接用，而保护它们的手段是整包加密，不是从包里剔除。
         */
        internal val BACKUP_FIELDS: List<BackupField> = listOf(
            // 服务
            BackupField(KEY_PORT, BackupValueType.INT, 1024, 65535),
            BackupField(KEY_AUTO_START, BackupValueType.BOOL),
            BackupField(KEY_BG_SERVICE_ENABLED, BackupValueType.BOOL),
            // 设备后台（goform）
            BackupField(KEY_GOFORM_IP, BackupValueType.STRING),
            BackupField(KEY_GOFORM_PORT, BackupValueType.INT, 1, 65535),
            BackupField(KEY_GOFORM_PASSWORD, BackupValueType.STRING, sensitive = true),
            BackupField(KEY_DEVICE_PROFILE_ID, BackupValueType.STRING),
            BackupField(KEY_FIELD_NORMALIZATION, BackupValueType.BOOL),
            BackupField(KEY_GOFORM_DUMP_ENABLED, BackupValueType.BOOL),
            BackupField(KEY_GOFORM_COMMAND_ENABLED, BackupValueType.BOOL),
            // 日志
            BackupField(KEY_DEBUG_MODE, BackupValueType.BOOL),
            BackupField(KEY_LOG_ENABLED, BackupValueType.BOOL),
            BackupField(KEY_CORE_LOG_ENABLED, BackupValueType.BOOL),
            BackupField(KEY_APP_LOG_ENABLED, BackupValueType.BOOL),
            // QoS
            BackupField(KEY_QOS_ENABLED, BackupValueType.BOOL),
            BackupField(KEY_QOS_SHELL_MAX, BackupValueType.INT, 1, 10),
            BackupField(KEY_QOS_CACHE_TTL, BackupValueType.LONG, 500, 30_000),
            BackupField(KEY_QOS_GOFORM_QUERY_MAX, BackupValueType.INT, 1, 8),
            BackupField(KEY_QOS_GOFORM_SET_MAX, BackupValueType.INT, 1, 4),
            BackupField(KEY_ADB_AUTO_START, BackupValueType.BOOL),
            // 告警 / 监控 / 通知（整份 JSON，由各自的 DTO 校验）
            BackupField(KEY_ALERT_CONFIG, BackupValueType.STRING),
            BackupField(KEY_TRAFFIC_AUTO_OFF_CONFIG, BackupValueType.STRING),
            BackupField(KEY_MONITOR_PREFERENCES, BackupValueType.STRING),
            BackupField(KEY_NOTIFICATION_CONFIG, BackupValueType.STRING),
            // 更新源
            BackupField(KEY_UPDATE_URL, BackupValueType.STRING),
            BackupField(KEY_UPDATE_MIRROR_BASE, BackupValueType.STRING),
            // 短信
            BackupField(KEY_SMS_CODE_ENABLED, BackupValueType.BOOL),
            BackupField(KEY_SMS_CODE_CLEANUP_HOURS, BackupValueType.INT, 1, 720),
            BackupField(KEY_SMS_CODE_AUTO_COPY, BackupValueType.BOOL),
            BackupField(KEY_SMS_FILTER_EXEMPT_VC, BackupValueType.BOOL),
            BackupField(KEY_SMS_FILTER_STORE_FULL_BODY, BackupValueType.BOOL),
            // 配对配额（不是凭据，是策略）
            BackupField(KEY_PAIRING_ENABLED, BackupValueType.BOOL),
            BackupField(KEY_PAIRING_MAX_DEVICES, BackupValueType.INT, 0, 100),
            // 隧道
            BackupField(KEY_TUNNEL_AUTO_RECONNECT, BackupValueType.BOOL),
            BackupField(KEY_TUNNEL_RECONNECT_INTERVAL, BackupValueType.INT, 10, 120),
            BackupField(KEY_TUNNEL_NOTIFY_ON_FAILURE, BackupValueType.BOOL),
            BackupField(KEY_TUNNEL_FRP_ACTIVE, BackupValueType.STRING),
            BackupField(KEY_TUNNEL_CF_ACTIVE, BackupValueType.STRING),
            BackupField(KEY_TUNNEL_FRP_DESIRED, BackupValueType.STRING),
            BackupField(KEY_TUNNEL_CF_DESIRED, BackupValueType.STRING),
            BackupField(KEY_TUNNEL_CF_TOKEN, BackupValueType.STRING, sensitive = true),
            // 采集调度（范围与各自 getter 的夹取一致）
            BackupField(KEY_MON_RETENTION_DAYS, BackupValueType.INT, 1, 90),
            BackupField(KEY_MON_FLUSH_SEC, BackupValueType.INT, 5, 300),
            BackupField(KEY_MON_ALERT_SCAN_SEC, BackupValueType.INT, 5, 300),
            BackupField(KEY_MON_IDLE_SEC, BackupValueType.INT, 10, 600),
            BackupField(KEY_MON_THERMAL_WARN_C, BackupValueType.INT, 50, 90),
            BackupField(KEY_MON_THERMAL_CRIT_C, BackupValueType.INT, 55, 100),
            BackupField(KEY_MON_THERMAL_PAUSE_SEC, BackupValueType.INT, 5, 300),
            BackupField(KEY_MON_TRAFFIC_LIMIT_CHECK_SEC, BackupValueType.INT, 60, 3600),
            BackupField(KEY_MON_DEVICE_EVENT_CHECK_SEC, BackupValueType.INT, 15, 600)
        )

        private val BACKUP_FIELDS_BY_KEY: Map<String, BackupField> =
            BACKUP_FIELDS.associateBy { it.key }

        /** 备份包里出现的敏感键，供 UI 提示"这个包含密码与令牌"。 */
        val SENSITIVE_BACKUP_KEYS: Set<String> =
            BACKUP_FIELDS.filter { it.sensitive }.map { it.key }.toSet()

        /** 旧算法：裸 SHA-256、无 salt、单轮。只在校验时兼容，校验通过即就地升级。 */
        private const val PASSWORD_VERSION_LEGACY_SHA256 = 1
        /** 当前算法：PBKDF2-HMAC-SHA256 + 每密码随机 salt。 */
        private const val PASSWORD_VERSION_PBKDF2 = 2

        // ── Web 前端独立更新（版本追踪）──
        // 2026-08-28：旧 key last_seen_bundled_web_build_time 已弃用（buildTime 每次构建都变，
        // 会误删用户上传的 override），改用内置 version 字段 + 新 key。
        private const val KEY_LAST_SEEN_BUNDLED_WEB_VERSION = "last_seen_bundled_web_version"

        // ── 内网穿透（2026-08-20：FRP + Cloudflare Tunnel）──
        private const val KEY_TUNNEL_FRP_ACTIVE = "tunnel_frp_active"
        private const val KEY_TUNNEL_CF_ACTIVE = "tunnel_cf_active"
        private const val KEY_TUNNEL_CF_TOKEN = "tunnel_cf_token"
        private const val KEY_TUNNEL_AUTO_RECONNECT = "tunnel_auto_reconnect"
        private const val KEY_TUNNEL_RECONNECT_INTERVAL = "tunnel_reconnect_interval_sec"
        private const val KEY_TUNNEL_NOTIFY_ON_FAILURE = "tunnel_notify_on_failure"
        private const val KEY_TUNNEL_FRP_DESIRED = "tunnel_frp_desired"
        private const val KEY_TUNNEL_CF_DESIRED = "tunnel_cf_desired"

        // ── core 崩溃标记（2026-09-04：崩溃重启后通知前端）──
        private const val KEY_LAST_CRASH_AT = "last_crash_at"
        private const val KEY_LAST_CRASH_SUMMARY = "last_crash_summary"
        private const val KEY_LAST_CRASH_FILE = "last_crash_file"

        // ── 采集调度（2026-09-03：原来全是 DataScheduler 里的编译期常量，用户改不了）──
        private const val KEY_MON_RETENTION_DAYS = "monitor_retention_days"
        private const val KEY_MON_FLUSH_SEC = "monitor_flush_interval_sec"
        private const val KEY_MON_ALERT_SCAN_SEC = "monitor_alert_scan_sec"
        private const val KEY_MON_IDLE_SEC = "monitor_idle_interval_sec"
        private const val KEY_MON_THERMAL_WARN_C = "monitor_thermal_warn_c"
        private const val KEY_MON_THERMAL_CRIT_C = "monitor_thermal_critical_c"
        private const val KEY_MON_THERMAL_PAUSE_SEC = "monitor_thermal_pause_sec"
        // 2026-09-08 补齐：这两个是 2026-09-03 那次改造漏掉的最后两个编译期常量
        private const val KEY_MON_TRAFFIC_LIMIT_CHECK_SEC = "monitor_traffic_limit_check_sec"
        private const val KEY_MON_DEVICE_EVENT_CHECK_SEC = "monitor_device_event_check_sec"


        // Defaults
        const val DEFAULT_PORT = 8088
        const val DEFAULT_AUTO_START = true
        const val DEFAULT_GOFORM_IP = "192.168.0.1"
        const val DEFAULT_GOFORM_PORT = 8080
        // 设备 modem 真实默认口令，非 UFI-AXIS 私钥；不可随机化，建议经设置页修改（接受风险）
        private const val DEFAULT_GOFORM_PASSWORD = "admin"
        // 设备配对默认密码（仅用于 hasDefaultPassword 判定与旧版 App 兼容兜底；绝不回显/落明文）
        private const val DEFAULT_DEVICE_PASSWORD = "admin"
        const val DEFAULT_DEBUG_MODE = false

        /**
         * 日志总开关默认 **false**（2026-09-04 由 true 改）。
         *
         * 「所有日志都只在用户显式开启后才记录」—— 出厂状态下两端一条业务日志都不写，
         * 不写 logcat、不落盘、不进内存缓冲。用户到日志页把总开关打开才开始记。
         *
         * **崩溃日志不受此开关约束**：`UfiAxisCoreApplication` 的未捕获异常处理器与 app 侧
         * `CrashHandler` 都是直接写文件（不经 `AppLogger`/`DebugLog` 的闸门），所以关掉日志
         * 依然留得下崩溃证据。代价是崩溃文件里的「日志现场」段会是空的（内存缓冲同样被关掉），
         * 只剩堆栈 + 本进程 logcat —— 这是用户明确接受的取舍。
         */
        const val DEFAULT_LOG_ENABLED = false

        // 两个子开关仍默认 true：它们与总闸是「与」关系，总闸关着时开不开都不产生日志；
        // 保持 true 是为了用户打开总闸后两侧立即都有日志，而不是还要再点两下。
        const val DEFAULT_CORE_LOG_ENABLED = true
        const val DEFAULT_APP_LOG_ENABLED = true
        const val DEFAULT_QOS_ENABLED = true
        const val DEFAULT_QOS_SHELL_MAX = 3
        const val DEFAULT_QOS_CACHE_TTL = 2000
        const val DEFAULT_QOS_GOFORM_QUERY_MAX = 4
        const val DEFAULT_QOS_GOFORM_SET_MAX = 2

        /** 崩溃摘要落 prefs 的长度上限（前端只用来一句话提示，详情看崩溃文件）。 */
        private const val CRASH_SUMMARY_MAX = 500

        /** 默认更新镜像前缀（与旧写死行为一致：设备在国内访问 GitHub 时自动加速） */
        const val DEFAULT_UPDATE_MIRROR_BASE = "https://mirror.drxian.qzz.io/"

        // 默认 goform 口令的只读访问器（非 const val，避免被调用方字节码内联）。
        val defaultGoformPassword: String get() = DEFAULT_GOFORM_PASSWORD

        @Volatile
        private var instance: AppSettings? = null

        fun getInstance(context: Context): AppSettings {
            return instance ?: synchronized(this) {
                instance ?: AppSettings(context.applicationContext).also { instance = it }
            }
        }
    }

    // --- Auth ---
    //
    // 全局 token/secret 已废除：鉴权改为「每设备独占 token + 每请求非对称签名」，
    // token 明文只在配对响应里出现一次，服务端只存哈希（见 PairedDeviceStore）。
    // 这里不再有任何全局共享凭据，因此也没有轮换/默认值兜底的概念。

    // ── 配对模式（Onboarding 重构）──
    // 首次启动（initialize 后）默认未配对（KEY_PAIRED=false）即进入配对模式。
    // pairing_code 为一次性临时码（≠ 业务 Token），confirm 成功后失效。

    /** 稳定设备标识，用于配对展示与网关探测提示（首次访问时生成并持久化）。 */
    var deviceId: String
        get() {
            val existing = prefs.getString(KEY_DEVICE_ID, "") ?: ""
            if (existing.isNotBlank()) return existing
            synchronized(this) {
                val again = prefs.getString(KEY_DEVICE_ID, "") ?: ""
                if (again.isNotBlank()) return again
                val id = generateDeviceId()
                prefs.edit().putString(KEY_DEVICE_ID, id).apply()
                return id
            }
        }
        set(value) = prefs.edit().putString(KEY_DEVICE_ID, value).apply()

    /** 是否已成功配对（有至少一个设备配对即为 true）。 */
    var paired: Boolean
        get() = pairedFingerprints.isNotEmpty()
        set(value) {
            // 向后兼容：set(false) 清空所有配对
            if (!value) pairedFingerprints = emptyList()
        }

    /** 当前配对码（一次性）。配对成功或解除配对后置空。 */
    var pairingCode: String
        get() = prefs.getString(KEY_PAIRING_CODE, "") ?: ""
        set(value) = prefs.edit().putString(KEY_PAIRING_CODE, value).apply()

    /** 已配对 App 的指纹列表（逗号分隔）。支持多设备配对。 */
    var pairedFingerprints: List<String>
        get() {
            val raw = prefs.getString(KEY_PAIRED_APP_FP, "") ?: ""
            return if (raw.isBlank()) emptyList() else raw.split(",").map { it.trim() }.filter { it.isNotBlank() }
        }
        set(value) = prefs.edit().putString(KEY_PAIRED_APP_FP, value.joinToString(",")).apply()

    /** 已配对 App 的指纹（向后兼容，返回第一个）。 */
    var pairedAppFingerprint: String
        get() = pairedFingerprints.firstOrNull() ?: ""
        set(value) = prefs.edit().putString(KEY_PAIRED_APP_FP, value).apply()

    /** 配对成功时的 Unix 时间戳（毫秒），未配对时为 0。 */
    var pairedAt: Long
        get() = prefs.getLong(KEY_PAIRED_AT, 0L)
        set(value) = prefs.edit().putLong(KEY_PAIRED_AT, value).apply()

    /** 是否启用配对限制（false = 不限制配对数量，允许任意多设备配对）。 */
    var pairingEnabled: Boolean
        get() = prefs.getBoolean(KEY_PAIRING_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_PAIRING_ENABLED, value).apply()

    /** 最大配对设备数，0 表示无限制。仅在 pairingEnabled=true 时生效。 */
    var pairingMaxDevices: Int
        get() = prefs.getInt(KEY_PAIRING_MAX_DEVICES, 0)
        set(value) = prefs.edit().putInt(KEY_PAIRING_MAX_DEVICES, value.coerceAtLeast(0)).apply()

    // ── 配对密码（配对认证，2026-08-11）──
    // 哈希存 SharedPreferences，明文永不落库/回显。
    // 首次 confirm 时（devicePasswordSet=false）将请求密码直接落库=“设置密码”；
    // 之后 confirm / 删除设备 / 改密 一律校验密码。
    //
    // 2026-09-08 算法升级（v1 → v2）：原来是 `HashUtil.sha256(pw)` —— 单轮、无 salt。
    // 单轮 SHA-256 在消费级 GPU 上是百亿量级/秒，无 salt 又能被彩虹表直接命中；
    // 而这台设备本身跑着 root 工具链，「攻击者能读到 SharedPreferences」并不是苛刻前提。
    // 现在改成 PBKDF2-HMAC-SHA256 + 每密码随机 salt（平台自带，无新依赖）。
    // 旧哈希不强制用户改密：校验旧密码成功的那一刻手里就有明文，就地重算成 v2 写回。

    /** 配对密码派生结果（hex）。未设置过时为空串。格式由 [KEY_DEVICE_PASSWORD_VERSION] 决定。 */
    private var devicePasswordHash: String
        get() = prefs.getString(KEY_DEVICE_PASSWORD_HASH, "") ?: ""
        set(value) = prefs.edit().putString(KEY_DEVICE_PASSWORD_HASH, value).apply()

    /** 是否已设置过配对密码（首次 confirm 设置后为 true）。 */
    var devicePasswordSet: Boolean
        get() = prefs.getBoolean(KEY_DEVICE_PASSWORD_SET, false)
        set(value) = prefs.edit().putBoolean(KEY_DEVICE_PASSWORD_SET, value).apply()

    /** 是否仍为默认密码：从未设置 或 哈希等于 sha256("admin")。 */
    // 2026-08-11 修正：仅判断"是否设过密码"，去掉 hash==sha256("admin") 兼容兜底
    // 之前 bug：用户首次配对用 fallback admin 落库 → passwordSet=true + hash=admin → hasDefaultPassword=true
    //         → App 端走"设置密码"分支（错误）；正确逻辑：设备已设过密码（即使是 admin）→ App 端应"输入密码"。
    // 修正后：devicePasswordSet=true → 已设过（任意密码）→ hasDefaultPassword=false（App 端走"输入密码"）
    val hasDefaultPassword: Boolean
        get() = !devicePasswordSet

    /** 是否已配置配对密码（与 [hasDefaultPassword] 互补；首次设置后恒 true）。 */
    val devicePasswordConfigured: Boolean
        get() = devicePasswordSet

    /** 设置配对密码：随机 salt + PBKDF2 派生后落盘，并置已设置标志与算法版本。 */
    fun setDevicePassword(pw: String) {
        val salt = HashUtil.randomSalt()
        val derived = HashUtil.pbkdf2(pw, salt)
        prefs.edit().apply {
            putString(KEY_DEVICE_PASSWORD_HASH, derived)
            putString(KEY_DEVICE_PASSWORD_SALT, salt.toHex())
            putInt(KEY_DEVICE_PASSWORD_VERSION, PASSWORD_VERSION_PBKDF2)
            putBoolean(KEY_DEVICE_PASSWORD_SET, true)
            apply()
        }
    }

    /**
     * 校验配对密码（常量时间比较，防时序侧信道）。未设置过时恒 false。
     *
     * v1（裸 SHA-256）仍可校验，且校验通过后**就地升级**成 v2：
     * 这是唯一能拿到明文的时机，错过就只能强制用户改密。升级对调用方完全透明。
     */
    fun verifyDevicePassword(pw: String): Boolean {
        val stored = devicePasswordHash
        if (stored.isEmpty()) return false
        // 缺 version 键的一律按 v1（升级前写入的记录没有这个键）
        return when (prefs.getInt(KEY_DEVICE_PASSWORD_VERSION, PASSWORD_VERSION_LEGACY_SHA256)) {
            PASSWORD_VERSION_PBKDF2 -> {
                val salt = HashUtil.hexToBytes(prefs.getString(KEY_DEVICE_PASSWORD_SALT, "") ?: "")
                if (salt.isEmpty()) {
                    // 版本说是 v2 却读不出 salt：无法校验。不静默当成 false 之外还要留痕，
                    // 否则表现成「密码突然全错」而查不到原因。
                    AppLogger.e("AppSettings", "device password marked v2 but salt is missing/corrupt")
                    return false
                }
                HashUtil.constantTimeEquals(stored, HashUtil.pbkdf2(pw, salt))
            }
            else -> {
                val ok = HashUtil.constantTimeEquals(stored, HashUtil.sha256(pw))
                if (ok) {
                    AppLogger.i("AppSettings", "device password rehashed from v1(sha256) to v2(pbkdf2)")
                    setDevicePassword(pw)
                }
                ok
            }
        }
    }

    /**
     * aria2 RPC 随机 secret（每台设备唯一、持久化）。
     * 替代原硬编码固定串，避免同设备其他进程冒用本地 aria2 RPC。
     * 首次访问时随机生成 32 字节 hex 并持久化；之后每次返回同一值（跨进程重启一致），
     * 保证 RPC 鉴权握手（token:$secret）在进程重启后仍可用。
     */
    var aria2RpcSecret: String
        get() {
            val existing = prefs.getString(KEY_ARIA2_RPC_SECRET, "") ?: ""
            if (existing.isNotBlank()) return existing
            synchronized(this) {
                val again = prefs.getString(KEY_ARIA2_RPC_SECRET, "") ?: ""
                if (again.isNotBlank()) return again
                val rng = SecureRandom()
                val bytes = ByteArray(32)
                rng.nextBytes(bytes)
                val secret = bytes.toHex()
                prefs.edit().putString(KEY_ARIA2_RPC_SECRET, secret).apply()
                AppLogger.i("AppSettings", "已随机生成并持久化 aria2 RPC secret")
                return secret
            }
        }
        set(value) = prefs.edit().putString(KEY_ARIA2_RPC_SECRET, value).apply()

    // ── 配对模式状态机 ──

    /**
     * 进入/刷新配对模式：确保配对码存在。
     * 多设备模式下始终生成/保留配对码，不再因已配对而跳过。
     */
    fun enterPairingMode() {
        synchronized(this) {
            val code = if (pairingCode.isBlank()) generatePairingCode() else pairingCode
            prefs.edit().apply {
                putString(KEY_PAIRING_CODE, code)
                apply()
            }
            AppLogger.i("AppSettings", "配对模式就绪，当前已配对 ${pairedFingerprints.size} 台设备")
        }
    }

    /**
     * 校验配对码并完成配对（支持多设备）。
     * @return [PairingConfirmResult]：已达上限 / 码无效 / 成功
     *
     * 成功不再携带凭据：token 由 PairingManager 为该设备单独生成，此处只负责
     * 「配对码一次性消耗 + 指纹入册」这两件状态机的事。
     *
     * 限制逻辑：
     * - pairingEnabled=false → 不限制，始终允许配对
     * - pairingEnabled=true 且 maxDevices=0 → 不限制
     * - pairingEnabled=true 且 maxDevices>0 且已达上限 → 拒绝
     */
    fun confirmPairing(code: String, appFingerprint: String): PairingConfirmResult {
        synchronized(this) {
            val current = pairingCode
            if (current.isBlank() || current != code.trim()) {
                return PairingConfirmResult.InvalidCode
            }
            // 限制检查
            if (pairingEnabled && pairingMaxDevices > 0 && pairedFingerprints.size >= pairingMaxDevices) {
                return PairingConfirmResult.AlreadyPaired
            }
            // 防止同一指纹重复配对
            val fps = pairedFingerprints.toMutableList()
            if (appFingerprint in fps) {
                // 已配对过，直接返回成功（刷新时间戳）
                prefs.edit().putLong(KEY_PAIRED_AT, System.currentTimeMillis()).apply()
                return PairingConfirmResult.Success
            }
            fps.add(appFingerprint)
            prefs.edit().apply {
                putString(KEY_PAIRED_APP_FP, fps.joinToString(","))
                putLong(KEY_PAIRED_AT, System.currentTimeMillis())
                remove(KEY_PAIRING_CODE) // 一次性码失效
                apply()
            }
            AppLogger.i("AppSettings", "配对成功，已绑定 app_fingerprint（共 ${fps.size} 台），失效配对码")
            return PairingConfirmResult.Success
        }
    }

    /**
     * 密码登录模式完成配对（登录=自动配对）。
     *
     * 适用：设备已完成首次初始化（密码已设置）、一次性配对码已消耗或未携带，
     * 客户端凭配对密码登录。密码校验由 PairingManager.confirm 前置完成，
     * 此处跳过配对码校验直接绑定指纹。
     * 不消耗/生成配对码（首次配对语义仍由 [confirmPairing] 承担）。
     */
    fun loginPair(appFingerprint: String): PairingConfirmResult {
        synchronized(this) {
            val fps = pairedFingerprints.toMutableList()
            if (appFingerprint in fps) {
                // 已配对指纹：刷新配对时间即可
                prefs.edit().putLong(KEY_PAIRED_AT, System.currentTimeMillis()).apply()
                return PairingConfirmResult.Success
            }
            fps.add(appFingerprint)
            prefs.edit().apply {
                putString(KEY_PAIRED_APP_FP, fps.joinToString(","))
                putLong(KEY_PAIRED_AT, System.currentTimeMillis())
                apply()
            }
            AppLogger.i("AppSettings", "密码登录成功，已绑定 app_fingerprint（共 ${fps.size} 台）")
            return PairingConfirmResult.Success
        }
    }

    /** 解除全部配对：清空所有配对设备并重新进入配对模式。 */
    fun unpair() {
        synchronized(this) {
            prefs.edit().apply {
                remove(KEY_PAIRED_APP_FP)
                remove(KEY_PAIRED_AT)
                apply()
            }
            enterPairingMode()
            AppLogger.i("AppSettings", "已解除全部配对，重新进入配对模式")
        }
    }

    /** 解除指定设备的配对。 */
    fun unpairFingerprint(fingerprint: String) {
        synchronized(this) {
            val fps = pairedFingerprints.toMutableList()
            fps.remove(fingerprint)
            prefs.edit().apply {
                putString(KEY_PAIRED_APP_FP, fps.joinToString(","))
                if (fps.isEmpty()) remove(KEY_PAIRED_AT)
                apply()
            }
            AppLogger.i("AppSettings", "已解除设备配对: $fingerprint（剩余 ${fps.size} 台）")
        }
    }

    /** 配对确认结果。Success 不携带凭据——token 由 PairingManager 按设备单独签发。 */
    sealed class PairingConfirmResult {
        object AlreadyPaired : PairingConfirmResult()
        object InvalidCode : PairingConfirmResult()
        object Success : PairingConfirmResult()
    }

    private fun generateDeviceId(): String {
        val rng = SecureRandom()
        val bytes = ByteArray(4)
        rng.nextBytes(bytes)
        return bytes.toHex()
    }

    private fun generatePairingCode(): String {
        val rng = SecureRandom()
        val bytes = ByteArray(8)
        rng.nextBytes(bytes)
        return bytes.toHex()
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    // --- Server ---

    var port: Int
        get() = prefs.getInt(KEY_PORT, DEFAULT_PORT)
        set(value) = prefs.edit().putInt(KEY_PORT, value.coerceIn(1024, 65535)).apply()

    // --- Service ---

    var autoStartOnBoot: Boolean
        get() = prefs.getBoolean(KEY_AUTO_START, DEFAULT_AUTO_START)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_START, value).apply()

    /**
     * 后台采集服务开关（`/api/service/start|stop` 的真源，默认开）。
     * 与 [autoStartOnBoot] 的区别：本开关不影响进程/HTTP 服务，只决定 `DataScheduler`
     * 的采集循环是否运行；持久化的目的是让"用户显式停掉采集"在服务重启后仍然生效
     * （此前 `/api/monitor/control` 只写内存，重启即回 true）。
     */
    var backgroundServiceEnabled: Boolean
        get() = prefs.getBoolean(KEY_BG_SERVICE_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_BG_SERVICE_ENABLED, value).apply()

    // --- Goform ---

    var goformIp: String
        get() = prefs.getString(KEY_GOFORM_IP, DEFAULT_GOFORM_IP) ?: DEFAULT_GOFORM_IP
        set(value) = prefs.edit().putString(KEY_GOFORM_IP, value).apply()

    var goformPort: Int
        get() = prefs.getInt(KEY_GOFORM_PORT, DEFAULT_GOFORM_PORT)
        set(value) = prefs.edit().putInt(KEY_GOFORM_PORT, value.coerceIn(1, 65535)).apply()

    var goformPassword: String
        get() = prefs.getString(KEY_GOFORM_PASSWORD, DEFAULT_GOFORM_PASSWORD) ?: DEFAULT_GOFORM_PASSWORD
        set(value) = prefs.edit().putString(KEY_GOFORM_PASSWORD, value).apply()

    // --- 设备适配层（计划书 3.2） ---

    /**
     * 设备 profile 的 id（`DeviceProfile.id`，如 `"zte-goform"`）。
     *
     * 空 = 用注册表默认值。填了但注册表里没有 → 也回落默认值并打 WARN
     * （型号不认识不能导致整个不工作）。**只在服务启动构造组件图时读一次**，
     * 改完要重启后台服务才生效 —— 这样也避免了"切换 profile 后缓存里还躺着上一种形状"
     * 的问题（组件图重建时缓存本来就是空的）。
     */
    var deviceProfileId: String
        get() = prefs.getString(KEY_DEVICE_PROFILE_ID, "") ?: ""
        set(value) = prefs.edit().putString(KEY_DEVICE_PROFILE_ID, value.trim()).apply()

    /**
     * 字段归一化总开关（回退用，决策 D7）。
     *
     * false = 设备客户端层原样透传设备字段（`profile = null`），用于"归一化改坏了某个页面"
     * 时紧急恢复。**代价是对外字段名会变回设备原名**，web/app 会读不到 canonical 键，
     * 所以这是排障开关，不是长期配置。
     *
     * 写入侧不受影响：没有命令表就发不出请求（见 `GoformSettingWriter`）。
     * `SignalCollector` 也不受影响：它的第 1 层本身就是归一化，关掉等于信号数据全空，
     * 而 WS `signal` 频道与 REST 共用这份输出。
     */
    var fieldNormalizationEnabled: Boolean
        get() = prefs.getBoolean(KEY_FIELD_NORMALIZATION, true)
        set(value) = prefs.edit().putBoolean(KEY_FIELD_NORMALIZATION, value).apply()

    /**
     * `GET /api/device/goform`（设备原始 dump，75+ 字段）的显式开关，**默认关**（计划书 9.3）。
     *
     * 这个端点的用途是排障时看"设备后台到底有什么字段"，不是业务接口 ——
     * 长期开着等于把整个设备后台暴露在 API 上。关闭时端点回 403 `FORBIDDEN`。
     *
     * 打开后响应仍然是脱敏的（PII 与凭据按 `Sensitivity` + 字段名兜底打码，见 9.2），
     * 开关管的是"要不要给这份 dump"，脱敏管的是"给了也不能带真值"，两者不互相替代。
     */
    var goformDumpEnabled: Boolean
        get() = prefs.getBoolean(KEY_GOFORM_DUMP_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_GOFORM_DUMP_ENABLED, value).apply()

    /**
     * `POST /api/device/goform/query|set`（裸 goform 命令通道）的开关，**默认关**。
     *
     * 这是本仓库开放度最高的端点：`set` 直接把 `goformId` + 任意参数发给设备，
     * **绕过 profile 的 `WriteSpec.validate` 值域校验**（正常写接口靠它挡非法值和注入），
     * 也绕过 `SettingKey` 白名单。等于把设备后台的全部写入面暴露成一个 HTTP 接口。
     *
     * 三重防护，缺一不可：
     * 1. 端点在 `/api` 下 → `AuthMiddleware` 的 Bearer 鉴权（未授权直接 444，与其它接口一致）；
     * 2. 本开关默认 false → 即便 token 泄漏，没打开也只回 403；
     * 3. 每次调用都记 WARN 日志（`set` 只记 `goformId` 与参数**键名**，不记值，避免密码进日志）。
     *
     * 与 [goformDumpEnabled] 是两个开关：dump 只读且脱敏，这个能写且**不脱敏**（排障要看真值）。
     */
    var goformCommandEnabled: Boolean
        get() = prefs.getBoolean(KEY_GOFORM_COMMAND_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_GOFORM_COMMAND_ENABLED, value).apply()

    // --- Debug ---

    /**
     * 日志总开关（2026-08-27）：false = **两端**都完全不记录任何日志
     * （内存缓冲 / logcat / 文件 / Download 镜像 / goform 会话日志全停），用于省电与省 IO。
     *
     * 开关三层：本项是总闸，[coreLogEnabled] / [appLogEnabled] 分别管两侧，
     * [debugMode] 管详细级别。都是「与」关系 —— 总闸关掉后下面三个无论真假都不产生日志。
     */
    var logEnabled: Boolean
        get() = prefs.getBoolean(KEY_LOG_ENABLED, DEFAULT_LOG_ENABLED)
        set(value) = prefs.edit().putBoolean(KEY_LOG_ENABLED, value).apply()

    /**
     * core 端日志子开关（2026-08-27）。core 常驻写盘，是日志体积增长的那一侧，
     * 因此需要能「只关后端、保留手机端」。由 `AppLogger.setCoreLogEnabled` 生效。
     */
    var coreLogEnabled: Boolean
        get() = prefs.getBoolean(KEY_CORE_LOG_ENABLED, DEFAULT_CORE_LOG_ENABLED)
        set(value) = prefs.edit().putBoolean(KEY_CORE_LOG_ENABLED, value).apply()

    /**
     * app 端日志子开关（2026-08-27）。**core 自己不用这个值**，只是替 app 保管真源 ——
     * 放在这里 web 才能一起控制，且多台手机连同一台设备时开关一致（对齐「core 是唯一真源」）。
     * app 侧由 `AppPreferences.appLogEnabled` → `DebugLog.appEnabled` 落地。
     */
    var appLogEnabled: Boolean
        get() = prefs.getBoolean(KEY_APP_LOG_ENABLED, DEFAULT_APP_LOG_ENABLED)
        set(value) = prefs.edit().putBoolean(KEY_APP_LOG_ENABLED, value).apply()

    var debugMode: Boolean
        get() = prefs.getBoolean(KEY_DEBUG_MODE, DEFAULT_DEBUG_MODE)
        set(value) = prefs.edit().putBoolean(KEY_DEBUG_MODE, value).apply()

    // --- core 崩溃标记（2026-09-04）---

    /**
     * 上一次 core 崩溃的时间戳（毫秒，0 = 从未崩溃 / 已被清空）。
     *
     * 存在的理由：core 崩溃后由 keepalive / START_STICKY 自动拉起，**前端完全无感**——
     * 用户只看到"某个时刻数据断了一下"。前端拿这个时间戳与本地"已提示过的时间戳"比对，
     * 不相等就弹一次窗。**故意不做服务端 ack**：app 与 web 是两个独立的展示端，
     * 谁先 ack 另一端就永远看不到了；去重放在各自本地才是对的。
     */
    val lastCrashAt: Long get() = prefs.getLong(KEY_LAST_CRASH_AT, 0L)

    /** 崩溃摘要（线程 + 异常类名 + message，单行，已脱敏）。 */
    val lastCrashSummary: String get() = prefs.getString(KEY_LAST_CRASH_SUMMARY, "") ?: ""

    /** 崩溃详情文件的绝对路径（用户可自行去文件管理器翻）。 */
    val lastCrashFile: String get() = prefs.getString(KEY_LAST_CRASH_FILE, "") ?: ""

    /**
     * 记录一次崩溃。**必须用 `commit()` 而不是 `apply()`** —— 调用点在未捕获异常处理器里，
     * 进程马上就要死，`apply()` 的异步落盘会被一起带走，标记就丢了。
     */
    fun recordCrash(timestamp: Long, summary: String, file: String) {
        prefs.edit()
            .putLong(KEY_LAST_CRASH_AT, timestamp)
            .putString(KEY_LAST_CRASH_SUMMARY, summary.take(CRASH_SUMMARY_MAX))
            .putString(KEY_LAST_CRASH_FILE, file)
            .commit()
    }

    // --- Update（2026-08-10：后端自拉取更新，update_url 指向版本清单 JSON）---

    /** 更新源 URL：指向仓库根 version.json（`{"frontend":{...},"backend":{"version":"0.2","apkUrl":"https://...","apkSha256":"..."}}`），后端 GET 后比对版本并下载 apkUrl */
    var updateUrl: String
        get() = prefs.getString(KEY_UPDATE_URL, null) ?: "https://raw.githubusercontent.com/Asunano/UFI-AXIS/main/version.json"
        set(value) = prefs.edit().putString(KEY_UPDATE_URL, value).apply()

    /**
     * 更新镜像前缀（gh-proxy 风格，直接拼接在 GitHub 完整 URL 前；2026-08-12 由写死常量改为可配置）。
     * 默认 `https://mirror.drxian.qzz.io/` 维持现有自动镜像行为；设为空串 = 直连 GitHub。
     * 仅对 GitHub 相关域名生效（见 UpdateManager.GITHUB_HOSTS），非 GitHub 域名不受影响。
     */
    var updateMirrorBase: String
        get() = prefs.getString(KEY_UPDATE_MIRROR_BASE, DEFAULT_UPDATE_MIRROR_BASE) ?: DEFAULT_UPDATE_MIRROR_BASE
        set(value) = prefs.edit().putString(KEY_UPDATE_MIRROR_BASE, value).apply()

    // ── 更新状态持久化（2026-08-10 P0-4：RESULT 机制最小版）──
    // pendingUpdate：安装前置位（目标版本）。安装中被杀/断电后，新进程启动时据此恢复状态：
    //   当前版本 == pending → 写回 DONE；否则写回 FAILED。
    // lastUpdateResult：最近一次更新结果（OK:<ver> / FAILED:<原因> / WARN:SIG_CHANGED:<ver>）。
    // lastSigFingerprint：装后签名指纹基线；下次更新装后对比，不一致报警（签名变更检测，不阻断）。

    /** 待生效更新目标版本（null = 无待恢复更新） */
    var pendingUpdate: String?
        get() = prefs.getString(KEY_PENDING_UPDATE, null)
        set(value) {
            val editor = prefs.edit()
            if (value != null) editor.putString(KEY_PENDING_UPDATE, value) else editor.remove(KEY_PENDING_UPDATE)
            editor.apply()
        }

    /**
     * 本地推送更新发起时的 APK lastUpdateTime 基线（epoch ms，0=无）。
     * 恢复判定用：安装真正生效（含同版本重装）会改变 lastUpdateTime；
     * 不变则说明推送从未安装（旧版会误报 DONE，掩盖安装失败）。
     */
    var pendingLocalInstallLut: Long
        get() = prefs.getLong(KEY_PENDING_LOCAL_INSTALL_LUT, 0L)
        set(value) = prefs.edit().putLong(KEY_PENDING_LOCAL_INSTALL_LUT, value).apply()

    /** 最近一次更新结果（OK:<ver> / FAILED:<原因> / WARN:SIG_CHANGED:<ver>） */
    var lastUpdateResult: String?
        get() = prefs.getString(KEY_LAST_UPDATE_RESULT, null)
        set(value) {
            val editor = prefs.edit()
            if (value != null) editor.putString(KEY_LAST_UPDATE_RESULT, value) else editor.remove(KEY_LAST_UPDATE_RESULT)
            editor.apply()
        }

    /** 最近一次安装后的签名指纹（变更检测基线） */
    var lastSigFingerprint: String?
        get() = prefs.getString(KEY_LAST_SIG_FINGERPRINT, null)
        set(value) {
            val editor = prefs.edit()
            if (value != null) editor.putString(KEY_LAST_SIG_FINGERPRINT, value) else editor.remove(KEY_LAST_SIG_FINGERPRINT)
            editor.apply()
        }

    // --- QoS ---

    var qosEnabled: Boolean
        get() = prefs.getBoolean(KEY_QOS_ENABLED, DEFAULT_QOS_ENABLED)
        set(value) = prefs.edit().putBoolean(KEY_QOS_ENABLED, value).apply()

    var qosShellMaxConcurrent: Int
        get() = prefs.getInt(KEY_QOS_SHELL_MAX, DEFAULT_QOS_SHELL_MAX)
        set(value) = prefs.edit().putInt(KEY_QOS_SHELL_MAX, value.coerceIn(1, 10)).apply()

    var qosCacheTtlMs: Int
        get() = prefs.getInt(KEY_QOS_CACHE_TTL, DEFAULT_QOS_CACHE_TTL)
        set(value) = prefs.edit().putInt(KEY_QOS_CACHE_TTL, value.coerceIn(500, 30000)).apply()

    var qosGoformQueryMax: Int
        get() = prefs.getInt(KEY_QOS_GOFORM_QUERY_MAX, DEFAULT_QOS_GOFORM_QUERY_MAX)
        set(value) = prefs.edit().putInt(KEY_QOS_GOFORM_QUERY_MAX, value.coerceIn(1, 8)).apply()

    var qosGoformSetMax: Int
        get() = prefs.getInt(KEY_QOS_GOFORM_SET_MAX, DEFAULT_QOS_GOFORM_SET_MAX)
        set(value) = prefs.edit().putInt(KEY_QOS_GOFORM_SET_MAX, value.coerceIn(1, 4)).apply()

    // --- ADB Auto-Start ---

    var adbAutoStartOnBoot: Boolean
        get() = prefs.getBoolean(KEY_ADB_AUTO_START, false)
        set(value) = prefs.edit().putBoolean(KEY_ADB_AUTO_START, value).apply()

    // 2026-08-22：adbUsbToggleBootCount 已随 USB_PORT_SETTING 自动切换功能一并删除。

    // --- Alert Config ---

    var alertConfigJson: String?
        get() = prefs.getString(KEY_ALERT_CONFIG, null)
        set(value) {
            val editor = prefs.edit()
            if (value != null) editor.putString(KEY_ALERT_CONFIG, value)
            else editor.remove(KEY_ALERT_CONFIG)
            editor.apply()
        }

    // --- 今日流量基线 ---
    //
    // goform 只给「当月累计」（monthly_rx/tx_bytes），没有任何「今日」字段，
    // 所以「今日已用」是本项目自算的：今日 = 当月累计 − 当日基线。
    //
    // 基线必须持久化，否则 core / 服务 / 设备重启后今日归零；
    // 又必须按日期作废，否则永远停在第一次记录的那天。因此存
    // {"date":"2026-08-28","rx":123,"tx":456} 单 key（与 alertConfigJson 同构），
    // 由 DataScheduler 在每次拿到新的月累计时对比 date 决定是否重建。
    // 置 null 即为重置（下一次采样会用当时的月累计重建基线，今日从此刻起算）。
    var trafficDailyBaselineJson: String?
        get() = prefs.getString(KEY_TRAFFIC_DAILY_BASELINE, null)
        set(value) {
            val editor = prefs.edit()
            if (value != null) editor.putString(KEY_TRAFFIC_DAILY_BASELINE, value)
            else editor.remove(KEY_TRAFFIC_DAILY_BASELINE)
            editor.apply()
        }

    // --- 到达限额自动关闭移动数据（core 自制功能，不是设备字段） ---
    //
    // 拆成两个 key 是刻意的：**配置由客户端写、运行状态由 core 写**。
    // 合成一个 key 的话，客户端一次 PUT 就会把"本周期已经关过网"的状态抹掉，
    // 于是同一个月能被反复触发。
    //
    // config: {"enabled":false,"restore_on_reset":false}
    //   enabled          = 达到告警阈值后自动关闭移动数据（关网前先发邮件，发信成功才关）
    //   restore_on_reset = 流量清零后自动重新打开（关掉则只关一次，等用户手动开）
    var trafficAutoOffConfigJson: String?
        get() = prefs.getString(KEY_TRAFFIC_AUTO_OFF_CONFIG, null)
        set(value) {
            val editor = prefs.edit()
            if (value != null) editor.putString(KEY_TRAFFIC_AUTO_OFF_CONFIG, value)
            else editor.remove(KEY_TRAFFIC_AUTO_OFF_CONFIG)
            editor.apply()
        }

    // state: {"cycle":"2026-09","turned_off":true,"at":1756...}
    //   cycle 是触发时的计费周期（年-月），用量回落或跨周期即作废 —— 这是"本周期只关一次"的凭据，
    //   必须持久化，否则 core 重启后会再关一次（还会再发一封邮件）。
    var trafficAutoOffStateJson: String?
        get() = prefs.getString(KEY_TRAFFIC_AUTO_OFF_STATE, null)
        set(value) {
            val editor = prefs.edit()
            if (value != null) editor.putString(KEY_TRAFFIC_AUTO_OFF_STATE, value)
            else editor.remove(KEY_TRAFFIC_AUTO_OFF_STATE)
            editor.apply()
        }

    // --- Monitor Preferences（监控中心个性化，两端共享的设备配置） ---
    //
    // 与 alertConfigJson 同构：单 key 存整份 JSON。放在 core 是因为这些项原先只存在 app 的
    // SharedPreferences（`monitor_settings_v1`）里，web 端完全看不到 —— 同一台设备从 app 和
    // web 进去会看到两套不同的监控偏好。
    // 注意：采集总开关 `collectEnabled` **不在这里**，它的真源是 backgroundServiceEnabled。
    var monitorPreferencesJson: String?
        get() = prefs.getString(KEY_MONITOR_PREFERENCES, null)
        set(value) {
            val editor = prefs.edit()
            if (value != null) editor.putString(KEY_MONITOR_PREFERENCES, value)
            else editor.remove(KEY_MONITOR_PREFERENCES)
            editor.apply()
        }

    // --- Notification Config（客户端通知渠道开关，两端共享的设备配置） ---
    //
    // 与 monitorPreferencesJson 同构：单 key 存整份 JSON。
    // 【边界】这里是「客户端要不要投递通知」，与 `AlertConfig.perType`（服务端要不要产生告警）
    // 是两件事，不要互相当对方的开关用。
    // 【不含】隧道失败通知 —— 它的真源是 tunnelNotifyOnFailure（`PUT /api/tunnel/settings`），
    // 放进来会造成同一概念两份真源。
    var notificationConfigJson: String?
        get() = prefs.getString(KEY_NOTIFICATION_CONFIG, null)
        set(value) {
            val editor = prefs.edit()
            if (value != null) editor.putString(KEY_NOTIFICATION_CONFIG, value)
            else editor.remove(KEY_NOTIFICATION_CONFIG)
            editor.apply()
        }

    // --- SMS 已读状态（本地管理） ---

    /** 已处理的最大 goform 消息 ID，用于检测新短信 */
    var smsHighWaterMark: Long
        get() = prefs.getLong(KEY_SMS_HIGH_WATER_MARK, 0L)
        set(value) = prefs.edit().putLong(KEY_SMS_HIGH_WATER_MARK, value).apply()

    /** 是否已完成 SMS 已读状态首次初始化 */
    var smsInitialized: Boolean
        get() = prefs.getBoolean(KEY_SMS_INITIALIZED, false)
        set(value) = prefs.edit().putBoolean(KEY_SMS_INITIALIZED, value).apply()

    /** 是否启用短信验证码解析（DataScheduler 据此决定是否扫描新消息） */
    var smsCodeEnabled: Boolean
        get() = prefs.getBoolean(KEY_SMS_CODE_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_SMS_CODE_ENABLED, value).apply()

    /** 验证码缓存自动清理间隔（小时），0 表示永不清理 */
    var smsCodeCleanupHours: Int
        get() = prefs.getInt(KEY_SMS_CODE_CLEANUP_HOURS, 24)
        set(value) = prefs.edit().putInt(KEY_SMS_CODE_CLEANUP_HOURS, value).apply()

    /** 自动复制验证码到剪贴板（需先开启系统无障碍服务，否则不生效） */
    var smsCodeAutoCopy: Boolean
        get() = prefs.getBoolean(KEY_SMS_CODE_AUTO_COPY, false)
        set(value) = prefs.edit().putBoolean(KEY_SMS_CODE_AUTO_COPY, value).apply()

    /**
     * 验证码短信是否豁免**关键词**拦截规则。**默认 true**。
     *
     * 语义（`SmsFilter.evaluate` 是唯一实现）：开启时被判定为验证码的短信跳过
     * `scope=body` 的规则，但 `scope=sender` 的号码黑名单、以及 `scope=both` 的 sender 侧
     * 仍然生效 —— 拉黑一个号码是明确意图，不该被「它这次发的是验证码」绕过；
     * 关键词是模糊匹配，误伤验证码的代价最高。
     *
     * 改这个开关之后必须让 `SmsRuleStore` 重载快照，否则是个假开关。
     */
    var smsFilterExemptVerificationCode: Boolean
        get() = prefs.getBoolean(KEY_SMS_FILTER_EXEMPT_VC, true)
        set(value) = prefs.edit().putBoolean(KEY_SMS_FILTER_EXEMPT_VC, value).apply()

    /**
     * 拦截记录是否留存短信**全文**。默认 false（只存 120 字 snippet）。
     *
     * 拦截记录是短信正文第一次成规模落进 core 的 DB（此前只有验证码存正文），
     * 所以全文留存做成显式开关且默认关 —— 想看全文的人自己打开，不替所有人做决定。
     */
    var smsFilterStoreFullBody: Boolean
        get() = prefs.getBoolean(KEY_SMS_FILTER_STORE_FULL_BODY, false)
        set(value) = prefs.edit().putBoolean(KEY_SMS_FILTER_STORE_FULL_BODY, value).apply()

    // --- Web 前端独立更新（版本追踪） ---

    /**
     * 上次启动时检测到的 APK 内置 web `version.json` 的 **version**，
     * 用于判断内置面板是否换了版本（换了才清除用户上传的 override）。
     *
     * 2026-08-28：从 buildTime 改为 version，并换了新 key。buildTime 由 vite 每次构建
     * 用 `new Date()` 生成，等于"每份新 core 包都不一样"，于是换一次 core 就把用户上传的
     * 面板连备份一起删掉 —— 用户看到的就是"设备/服务一重启就恢复成内置版本"。
     * 换新 key 是为了让升级后的第一次启动读到 null 走"只记录不清除"分支，
     * 不会因为新旧值格式不同而误删一次。
     */
    var lastSeenBundledWebVersion: String?
        get() = prefs.getString(KEY_LAST_SEEN_BUNDLED_WEB_VERSION, null)
        set(value) {
            val editor = prefs.edit()
            if (value != null) editor.putString(KEY_LAST_SEEN_BUNDLED_WEB_VERSION, value)
            else editor.remove(KEY_LAST_SEEN_BUNDLED_WEB_VERSION)
            editor.apply()
        }

    // --- Helpers ---

    // ── 内网穿透（2026-08-20：FRP + Cloudflare Tunnel）──

    /**
     * 当前选中的 FRP 通道名（空=未选中）。
     * 通道列表本身以 filesDir 下 frp 目录里的 configs 子目录（每通道一个 .toml 文件）为唯一事实来源，
     * 这里只记住"选中了哪个"，指向已删除通道时由 TunnelManager.activeFrpConfig() 自愈。
     */
    var tunnelFrpActiveConfig: String
        get() = prefs.getString(KEY_TUNNEL_FRP_ACTIVE, "") ?: ""
        set(value) = prefs.edit().putString(KEY_TUNNEL_FRP_ACTIVE, value).apply()

    /**
     * 当前选中的 CF 隧道名（空=未选中）。
     * 隧道列表以 filesDir 下 cloudflared 目录里的 tunnels 子目录（每隧道一个 .token 文件）为唯一
     * 事实来源，这里只记住"选中了哪个"，指向已删除隧道时由 TunnelManager.activeCfTunnel() 自愈。
     */
    var tunnelCfActiveTunnel: String
        get() = prefs.getString(KEY_TUNNEL_CF_ACTIVE, "") ?: ""
        set(value) = prefs.edit().putString(KEY_TUNNEL_CF_ACTIVE, value).apply()

    /**
     * 旧版单隧道 CF token —— **仅用于一次性迁移**（迁移进 tunnels/default.token 后清空），
     * 新代码不要再读写它。
     */
    var tunnelCfToken: String
        get() = prefs.getString(KEY_TUNNEL_CF_TOKEN, "") ?: ""
        set(value) = prefs.edit().putString(KEY_TUNNEL_CF_TOKEN, value).apply()

    /**
     * 隧道看护：断开自动重连 + 服务启动时恢复上次在跑的实例。
     * 关闭时看护协程什么都不做（进程退出就退出，不会被拉起）。
     */
    var tunnelAutoReconnect: Boolean
        get() = prefs.getBoolean(KEY_TUNNEL_AUTO_RECONNECT, true)
        set(value) = prefs.edit().putBoolean(KEY_TUNNEL_AUTO_RECONNECT, value).apply()

    /** 看护巡检间隔（秒），10..120；过小会把设备的 CPU 耗在无谓的 /proc 扫描上 */
    var tunnelReconnectIntervalSec: Int
        get() = prefs.getInt(KEY_TUNNEL_RECONNECT_INTERVAL, 30).coerceIn(10, 120)
        set(value) = prefs.edit().putInt(KEY_TUNNEL_RECONNECT_INTERVAL, value.coerceIn(10, 120)).apply()

    /** 隧道启动失败 / 意外退出时是否提醒（由 app 端读取后发通知，后端只负责持久化，保证多端一致） */
    var tunnelNotifyOnFailure: Boolean
        get() = prefs.getBoolean(KEY_TUNNEL_NOTIFY_ON_FAILURE, true)
        set(value) = prefs.edit().putBoolean(KEY_TUNNEL_NOTIFY_ON_FAILURE, value).apply()

    /**
     * 期望运行的 FRP 通道名（逗号分隔）—— 用户点过启动且没有主动停止的那些。
     * 看护协程据此决定"该拉起谁"；进程被杀/后端重启后也靠它恢复。
     * 名字本身不允许含逗号（`FrpEngine.sanitizeName` 把逗号列为非法字符，`isValidName` 据此拦下），所以 CSV 足够。
     */
    var tunnelFrpDesired: String
        get() = prefs.getString(KEY_TUNNEL_FRP_DESIRED, "") ?: ""
        set(value) = prefs.edit().putString(KEY_TUNNEL_FRP_DESIRED, value).apply()

    /** 期望运行的 CF 隧道名（逗号分隔），语义同 [tunnelFrpDesired] */
    var tunnelCfDesired: String
        get() = prefs.getString(KEY_TUNNEL_CF_DESIRED, "") ?: ""
        set(value) = prefs.edit().putString(KEY_TUNNEL_CF_DESIRED, value).apply()

    // ── 采集调度（2026-09-03）──
    // 这一组原本是 DataScheduler 里的 private const，用户无从调整；现在提到设置里，
    // 由 DataScheduler 在**每轮循环内**读取，所以改完立即生效，不需要重启采集。
    // getter 也做钳制：历史越界值或别的入口写脏都不会把设备打挂。

    /** 监控历史保留天数，1..90。原先硬编码 3 天，手动清理却写 7 天，两处说法不一致 */
    var monitorRetentionDays: Int
        get() = prefs.getInt(KEY_MON_RETENTION_DAYS, 7).coerceIn(1, 90)
        set(value) = prefs.edit().putInt(KEY_MON_RETENTION_DAYS, value.coerceIn(1, 90)).apply()

    /** 采集缓冲区刷写间隔（秒），5..300。越大越省 DB 写入，代价是断电丢的数据更多 */
    var monitorFlushIntervalSec: Int
        get() = prefs.getInt(KEY_MON_FLUSH_SEC, 30).coerceIn(5, 300)
        set(value) = prefs.edit().putInt(KEY_MON_FLUSH_SEC, value.coerceIn(5, 300)).apply()

    /** 本地告警扫描间隔（秒），5..300。与前端是否在线无关 */
    var monitorAlertScanSec: Int
        get() = prefs.getInt(KEY_MON_ALERT_SCAN_SEC, 15).coerceIn(5, 300)
        set(value) = prefs.edit().putInt(KEY_MON_ALERT_SCAN_SEC, value.coerceIn(5, 300)).apply()

    /** 无前端连接时的采集间隔（秒），10..600。有连接时走 QoS 自适应，不受这里影响 */
    var monitorIdleIntervalSec: Int
        get() = prefs.getInt(KEY_MON_IDLE_SEC, 60).coerceIn(10, 600)
        set(value) = prefs.edit().putInt(KEY_MON_IDLE_SEC, value.coerceIn(10, 600)).apply()

    /** 温控预警阈值（摄氏度），50..90。到这个温度开始降频采集 */
    var monitorThermalWarnC: Int
        get() = prefs.getInt(KEY_MON_THERMAL_WARN_C, 70).coerceIn(50, 90)
        set(value) = prefs.edit().putInt(KEY_MON_THERMAL_WARN_C, value.coerceIn(50, 90)).apply()

    /** 温控熔断阈值（摄氏度），55..100。到这个温度直接暂停采集 [monitorThermalPauseSec] */
    var monitorThermalCriticalC: Int
        get() = prefs.getInt(KEY_MON_THERMAL_CRIT_C, 80).coerceIn(55, 100)
        set(value) = prefs.edit().putInt(KEY_MON_THERMAL_CRIT_C, value.coerceIn(55, 100)).apply()

    /** 温控熔断后的暂停时长（秒），5..300 */
    var monitorThermalPauseSec: Int
        get() = prefs.getInt(KEY_MON_THERMAL_PAUSE_SEC, 20).coerceIn(5, 300)
        set(value) = prefs.edit().putInt(KEY_MON_THERMAL_PAUSE_SEC, value.coerceIn(5, 300)).apply()

    // ── 2026-09-08 补齐的两项 ──
    // 2026-09-03 那次把 flush/alertScan/idle/thermal 提到设置里时，漏了 DataScheduler 的
    // TRAFFIC_LIMIT_CHECK_INTERVAL_MS 与 DEVICE_EVENT_CHECK_INTERVAL_MS，于是「套餐限额预警」
    // 与「设备接入/离开提醒」这三类告警的实时性完全不可调 —— 用户把告警扫描间隔调到 5 秒，
    // 它们还是 5 分钟 / 60 秒。现在两个常量都删了，真源就在这里。

    /**
     * 套餐限额百分比的检查间隔（秒），60..3600，默认 300。
     *
     * 下限给到 60 秒而不是 5 秒：每次检查要向设备发一次 goform `getDataUsage`，
     * 那是跟设备官方 Web UI 抢同一个会话的请求，调太密会互相踢登录态。
     */
    var monitorTrafficLimitCheckSec: Int
        get() = prefs.getInt(KEY_MON_TRAFFIC_LIMIT_CHECK_SEC, 300).coerceIn(60, 3600)
        set(value) = prefs.edit()
            .putInt(KEY_MON_TRAFFIC_LIMIT_CHECK_SEC, value.coerceIn(60, 3600)).apply()

    /**
     * WiFi 客户端接入/离开的比对间隔（秒），15..600，默认 60。
     *
     * 接入与离开共用同一次 `station_list` 查询，所以这一个值同时决定两类提醒的延迟。
     * 下限 15 秒同样是为了不跟设备 Web UI 抢会话。注意首轮只建基线不报事件，
     * 所以刚打开开关后第一条提醒最迟要等两个周期。
     */
    var monitorDeviceEventCheckSec: Int
        get() = prefs.getInt(KEY_MON_DEVICE_EVENT_CHECK_SEC, 60).coerceIn(15, 600)
        set(value) = prefs.edit()
            .putInt(KEY_MON_DEVICE_EVENT_CHECK_SEC, value.coerceIn(15, 600)).apply()


    /**
     * 将全部配置导出为 Map，供 API 返回。
     *
     * **不含 `goform_password`**：这是唯一的明文凭据字段，放进通用导出 Map 里意味着
     * 任何一个新调用方只要忘了脱敏就会把它吐出去。需要它的一方（`ConfigRoutes` 的 GET）
     * 自己 put 脱敏值。
     */
    fun toMap(): Map<String, Any> = mapOf(
        "port" to port,
        "auto_start_on_boot" to autoStartOnBoot,
        "goform_ip" to goformIp,
        "goform_port" to goformPort,
        "debug_mode" to debugMode,
        "log_enabled" to logEnabled,
        "core_log_enabled" to coreLogEnabled,
        "app_log_enabled" to appLogEnabled,
        "goform_dump_enabled" to goformDumpEnabled,
        "goform_command_enabled" to goformCommandEnabled,
        "qos_enabled" to qosEnabled,
        "qos_shell_max_concurrent" to qosShellMaxConcurrent,
        "qos_cache_ttl_ms" to qosCacheTtlMs,
        "qos_goform_query_max" to qosGoformQueryMax,
        "qos_goform_set_max" to qosGoformSetMax,
        "adb_auto_start_on_boot" to adbAutoStartOnBoot,
        "sms_code_enabled" to smsCodeEnabled,
        "sms_code_cleanup_hours" to smsCodeCleanupHours,
        "sms_code_auto_copy" to smsCodeAutoCopy,
        // 两个拦截相关开关必须出现在这里：客户端靠 GET /api/config 回读当前值，
        // web 的差量提交（settingsShared.buildChangedPayload）也是拿 GET 的结果做基线 ——
        // 漏一个键就等于「界面上的开关永远显示默认值，改了也提交不出去」。
        "sms_filter_exempt_verification_code" to smsFilterExemptVerificationCode,
        "sms_filter_store_full_body" to smsFilterStoreFullBody,
        "update_url" to updateUrl,
        "update_mirror_base" to updateMirrorBase
    )

    // ────────────────────────────────────────────────────────────
    // 备份导出 / 导入
    // ────────────────────────────────────────────────────────────

    /**
     * 导出可备份的配置键。
     *
     * 只导出**实际存在**的键：没设过的键不出现在包里，这样 `merge` 恢复时不会用
     * 「导出那台设备的默认值」去覆盖目标设备上用户改过的值。
     */
    fun exportBackupFields(): Map<String, Any> {
        val out = LinkedHashMap<String, Any>()
        BACKUP_FIELDS.forEach { field ->
            if (!prefs.contains(field.key)) return@forEach
            val value: Any? = when (field.type) {
                BackupValueType.BOOL -> prefs.getBoolean(field.key, false)
                BackupValueType.INT -> prefs.getInt(field.key, 0)
                BackupValueType.LONG -> prefs.getLong(field.key, 0L)
                BackupValueType.STRING -> prefs.getString(field.key, null)
            }
            if (value != null) out[field.key] = value
        }
        return out
    }

    /**
     * 导入配置键。
     *
     * @param values 键 → 值，值只接受 Boolean / Int / Long / String（路由层负责把 JSON 转成这些）
     * @param replace true = 先把白名单内的键全部清掉（回默认）再写入；false = 只覆盖包里有的键
     *
     * 每个值都按 [BACKUP_FIELDS] 的类型与范围校验后才写入。**不能跳过校验直接写 prefs**：
     * 部分键只在 setter 里夹取（例如 `server_port`），绕过 setter 写入一个越界值会让服务
     * 下次启动就崩在端口绑定上，而备份文件是可以被手工编辑的。
     */
    fun importBackupFields(values: Map<String, Any?>, replace: Boolean): BackupImportReport {
        val applied = mutableListOf<String>()
        val rejected = LinkedHashMap<String, String>()
        val editor = prefs.edit()

        if (replace) {
            // 只清白名单内的键：身份凭据、运行态、崩溃标记都不在白名单里，天然不受影响。
            BACKUP_FIELDS.forEach { editor.remove(it.key) }
        }

        values.forEach { (key, raw) ->
            val field = BACKUP_FIELDS_BY_KEY[key]
            if (field == null) {
                rejected[key] = "不是可备份的配置项"
                return@forEach
            }
            if (raw == null) {
                rejected[key] = "值为空"
                return@forEach
            }
            when (field.type) {
                BackupValueType.BOOL -> {
                    val v = raw as? Boolean
                    if (v == null) rejected[key] = "需要布尔值" else editor.putBoolean(key, v)
                }
                BackupValueType.INT -> {
                    val v = (raw as? Number)?.toInt()
                    when {
                        v == null -> rejected[key] = "需要整数"
                        field.min != null && v < field.min -> rejected[key] = "小于下限 ${field.min}"
                        field.max != null && v > field.max -> rejected[key] = "大于上限 ${field.max}"
                        else -> editor.putInt(key, v)
                    }
                }
                BackupValueType.LONG -> {
                    val v = (raw as? Number)?.toLong()
                    when {
                        v == null -> rejected[key] = "需要整数"
                        field.min != null && v < field.min -> rejected[key] = "小于下限 ${field.min}"
                        field.max != null && v > field.max -> rejected[key] = "大于上限 ${field.max}"
                        else -> editor.putLong(key, v)
                    }
                }
                BackupValueType.STRING -> {
                    val v = raw as? String
                    if (v == null) rejected[key] = "需要字符串" else editor.putString(key, v)
                }
            }
            if (!rejected.containsKey(key)) applied += key
        }
        editor.apply()

        return BackupImportReport(
            applied = applied,
            rejected = rejected,
            // 这两个键只在服务启动时读一次（端口绑定 / 组件图构造），改了不重启等于没改
            needsRestart = applied.any { it == KEY_PORT || it == KEY_DEVICE_PROFILE_ID }
        )
    }

    /**
     * 恢复默认配置。
     *
     * **不清除身份与凭据键**（[PRESERVED_ON_RESET]）。
     *
     * 2026-09-10：原实现是 `prefs.edit().clear()`，会把 `device_password_hash` / `salt` /
     * `device_password_set` / 配对指纹 / `device_id` 一起抹掉，而 `paired_devices.json` 不在
     * 这份 prefs 里、不受影响 —— 结果是「已配对客户端照常能用，但设备退回出厂默认密码态，
     * `/pairing/info` 重新向局域网散发配对码」，任何人都能重新初始化并设一个新密码。
     * 而 `/api/config/reset` 只要求已配对身份、不要求配对密码，成本远低于物理重置。
     *
     * 「恢复默认配置」的语义是恢复**配置**，不包含放弃设备所有权。
     */
    fun resetAll() {
        val editor = prefs.edit()
        prefs.all.keys.filterNot { it in PRESERVED_ON_RESET }.forEach { editor.remove(it) }
        editor.apply()
    }
}

/** [AppSettings.BACKUP_FIELDS] 里一项的值类型。 */
enum class BackupValueType { BOOL, INT, LONG, STRING }

/**
 * 一个可备份的配置键。
 *
 * @param min / [max] 数值型的合法区间（含端点）；null = 不限。**必须与对应 setter/getter 的
 *        夹取范围一致** —— 备份文件是可以被手工编辑的，导入是唯一能挡住越界值的地方。
 * @param sensitive 是否是密码 / 令牌类字段。只用于让 UI 提示"此包含敏感信息"，
 *        不影响是否进包（保护手段是整包加密）。
 */
class BackupField(
    val key: String,
    val type: BackupValueType,
    val min: Long? = null,
    val max: Long? = null,
    val sensitive: Boolean = false
) {
    constructor(
        key: String,
        type: BackupValueType,
        min: Int,
        max: Int,
        sensitive: Boolean = false
    ) : this(key, type, min.toLong(), max.toLong(), sensitive)
}

/**
 * 导入结果。
 *
 * @param applied 实际写入的键
 * @param rejected 键 → 拒绝原因（类型不符 / 越界 / 不在白名单），面向用户展示
 * @param needsRestart 是否改动了只在启动时读取的键（端口、设备档位）
 */
class BackupImportReport(
    val applied: List<String>,
    val rejected: Map<String, String>,
    val needsRestart: Boolean
)

