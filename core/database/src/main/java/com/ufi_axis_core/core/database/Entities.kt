package com.ufi_axis_core.core.database

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * 流量记录实体
 * 记录每次采样的实时流量数据
 */
@Serializable
@Entity(tableName = "traffic_records", indices = [Index(value = ["timestamp"])])
data class TrafficRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val rxBytes: Long,          // 接收字节数
    val txBytes: Long,          // 发送字节数
    val rxSpeed: Long,          // 接收速度 (bytes/s)
    val txSpeed: Long,          // 发送速度 (bytes/s)
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * 信号历史实体
 * 记录信号质量变化历史
 */
@Serializable
@Entity(tableName = "signal_history", indices = [Index(value = ["timestamp"])])
data class SignalRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val rsrp: Int,              // 参考信号接收功率 (dBm)
    val sinr: Int,              // 信噪比 (dB)
    val rsrq: Int,              // 参考信号接收质量 (dB)
    val rssi: Int,              // 接收信号强度 (dBm)
    val rat: String,            // 网络制式 (4G/5G)
    val operator: String,       // 运营商
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * 告警记录实体
 *
 * 2026-08-23 P1 去重改造新增字段：
 * - count:      同 (type,level) 未确认累计次数（聚合更新替代反复 insert）
 * - firstSeenAt: 首次出现时间（保留最旧，列表展示"持续 N 次/自 X 起"）
 * - resolvedAt: 恢复时间（条件回到 normal 时标记，用于"已恢复"语义）
 */
@Serializable
@Entity(
    tableName = "alert_records",
    indices = [
        Index(value = ["timestamp"]),
        Index(value = ["acknowledged"]),
        Index(value = ["type", "level"])
    ]
)
data class AlertRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: String,           // 告警类型 (temperature/battery/traffic/signal/connectivity)
    val level: String,          // 告警级别 (info/warning/critical)
    val message: String,        // 告警消息
    val value: String,          // 触发值
    val threshold: String,      // 阈值
    val acknowledged: Boolean = false,
    val timestamp: Long = System.currentTimeMillis(),
    val count: Int = 1,
    val firstSeenAt: Long = timestamp,
    val resolvedAt: Long? = null
)

/**
 * CPU 历史实体
 * 记录 CPU 使用率和各核频率变化历史
 */
@Serializable
@Entity(tableName = "cpu_history", indices = [Index(value = ["timestamp"])])
data class CpuHistoryRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val usagePercent: Double,
    val coreCount: Int,
    val maxFreqMhz: Double,
    val temperature: Double,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * 短信记录实体
 */
@Serializable
@Entity(tableName = "sms_records")
data class SmsRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val direction: String,      // sent / received
    val phoneNumber: String,
    val content: String,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * 短信已读状态实体
 * 独立于设备 goform tag 字段，由软件本地管理已读状态。
 * msg_id 对应 goform 消息 ID（唯一标识一条短信）。
 */
@Serializable
@Entity(tableName = "sms_read_state")
data class SmsReadState(
    @PrimaryKey val msg_id: Long,
    val read: Boolean = true,
    val first_seen: Long = System.currentTimeMillis(),
    val phone: String = ""
)

/**
 * 短信验证码缓存实体
 * DataScheduler 实时扫描新短信提取验证码，结果持久化到此表。
 * 过期清理间隔由 smsCodeCleanupHours 配置控制，0 表示永不清理。
 *
 * [snippet] 与 [body] 是两份不同用途的正文：
 * - snippet：80 字截断预览，列表/通知里直接用，不用担心把长短信整段塞进 UI；
 * - body：**原短信全文**（v8 新增）。详情页要能看全内容，光有 snippet 做不到
 *   （曾经的做法是详情页再回查一次 /api/sms/list，多一次请求还会让弹窗内容闪一下）。
 *   v8 之前入库的旧数据该列为空串，读取方需回退到 snippet。
 */
@Serializable
@Entity(tableName = "sms_verification_codes")
data class SmsVerificationCode(
    @PrimaryKey val msg_id: Long,
    val code: String,
    val source: String,
    val snippet: String,
    val timestamp: Long,
    val keyword: String,
    val created_at: Long = System.currentTimeMillis(),
    val body: String = ""
)

/**
 * 短信拦截规则（号码黑名单 + 正文关键词，同一张表的不同 [scope]）。
 *
 * 2026-09-08 取代 `SmsForwardConfig.blacklist`：旧字段把号码和关键词混在一个 List 里，
 * 每一条同时匹配发件人**和**正文 —— 想屏蔽运营商号码 `10086`，正文里写着
 * 「请回复 10086 查询」的正常短信也一起被拦。拆出 [scope] 之后两种意图才分得开。
 *
 * **不做正则**（决策已定）：`contains/equals/prefix/suffix` 的行为对用户可预测，
 * 「我的规则有没有生效」由 [hit_count] + `sms_blocked_log` 回答，比试算更贴近真实使用。
 *
 * 字段名用 snake_case，与 `sms_read_state` / `sms_verification_codes` 家族一致。
 * 用 `match_type` 而不是 `match`：后者是 Kotlin 软关键字，虽可用但读起来像 when 表达式。
 */
@Serializable
@Entity(tableName = "sms_rule", indices = [Index(value = ["enabled"])])
data class SmsRule(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val enabled: Boolean = true,
    /** `"sender"` | `"body"` | `"both"`，取值见 `SmsFilter.SCOPES`。 */
    val scope: String,
    /** `"contains"` | `"equals"` | `"prefix"` | `"suffix"`，取值见 `SmsFilter.MATCH_TYPES`。 */
    val match_type: String,
    val pattern: String,
    val note: String = "",
    /** 命中次数。内存累加 + 增量 UPDATE flush（见 `SmsRuleStore`），不做全量重写。 */
    val hit_count: Int = 0,
    val last_hit_at: Long = 0,
    val created_at: Long = System.currentTimeMillis()
)

/**
 * 短信拦截记录（自查入口的数据源）。
 *
 * 拦截是「静默丢消息」的功能，没有这张表用户无法回答「我是不是漏了什么」。
 * 只由三条**写路径**产生记录（邮件 / WS 推送 / 验证码入库）；列表与计数这类读路径
 * 只做过滤、不写记录，否则每次下拉刷新都会刷出一堆重复行。
 *
 * [rule_pattern] / [rule_scope] / [rule_match] 是**规则快照**：全仓 entity 都没有外键，
 * 规则被删之后这条记录还得读得懂「当时是被哪条规则拦的」。这份冗余是刻意的。
 *
 * 隐私：默认只存 [snippet]（120 字），[body] 留空；全文留存由
 * `AppSettings.smsFilterStoreFullBody` 控制，默认关。
 */
@Serializable
@Entity(
    tableName = "sms_blocked_log",
    indices = [
        Index(value = ["blocked_at"]),
        Index(value = ["msg_id"])
    ]
)
data class SmsBlockedLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 设备侧短信 id；邮件路径拿不到 id 时为 0（那时按 sender + 60s 窗口去重）。 */
    val msg_id: Long = 0,
    val sender: String,
    val snippet: String,
    val body: String = "",
    val rule_id: Long,
    val rule_pattern: String,
    val rule_scope: String,
    val rule_match: String,
    /** 命中路径，逗号拼接去重：`"mail"` / `"push"` / `"vc"` / `"mail,push"`…… */
    val blocked_path: String,
    val blocked_at: Long = System.currentTimeMillis()
)

/**
 * 通知投递记录（2026-09-08）。
 *
 * 「我这封邮件到底发出去了没有」此前只能靠 `MailStats` 的三个计数器
 * （成功数 / 失败数 / 最后一条错误）回答 —— 知道"失败过 3 次"，但不知道是哪三封、
 * 什么时候、什么原因，最后一条错误还会被下一次失败覆盖。
 *
 * ## 三态而不是两态（DB v12，2026-09-10）
 *
 * v10/v11 只在**真正发起过投递**时写一行，`Skipped` 一律不落库。那个口径答不了排查里
 * 最常问的一句：「我明明开着通知，为什么这条没收到」—— 没发起投递的那几种情况
 * （免打扰、场景没勾、级别不够、配额烧完）在表里**完全没有痕迹**，只在 INFO 日志里，
 * 而 release 构建不留 INFO。所以 v12 起 [outcome] 是三态，`skipped` 也落库。
 *
 * 与之配套的两条纪律：
 * - **[outcome] 是唯一真源**，[success] 只是 `outcome == "sent"` 的副本（见 [success]）；
 * - `skipped` **只在渠道已配置时才写**（判定在 `NotificationDispatcher`）：SMTP 没填的用户
 *   每来一条事件都记一行"未配置完整"，几十条就把环形缓冲冲满，真正的失败反而被挤掉。
 *
 * [error] 是摊平后的异常链（`MailDelivery.describeAttempts`，开头带尝试次数）；
 * `skipped` 时是跳过原因的中文说明（`SkipReason.label`），成功时为空串。
 * 不存正文：邮件正文可能含验证码与短信全文，历史记录只需要回答"发了什么主题、成没成"。
 *
 * **表名 `mail_send_records` 保留不改**（2026-09-08 加 [channel] 列时的决定）：
 * 它已经是 DB v10 的既有表名，改名要写一次"建新表 + 搬数据 + 删旧表"的迁移，
 * 而唯一收益是名字好看一点。历史包袱，认了。
 */
@Serializable
@Entity(
    tableName = "mail_send_records",
    indices = [Index(value = ["sent_at"]), Index(value = ["channel"]), Index(value = ["outcome"])]
)
data class MailSendRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /**
     * 投递渠道 id（`MailChannel.ID` = `"mail"`、`PushChannel.ID` = `"push"`…）。
     *
     * 默认 `'mail'` 是给 DB 10 → 11 的存量行用的：v10 时这张表只有邮件一个写入方，
     * 所以旧数据全部属于 mail，用默认值回填等价于事实。
     * 当前只有 `recordsHistory = true` 的渠道（就 mail 一个）会写，push 不写。
     */
    val channel: String = "mail",
    /** 触发场景 id（`NotifyScenes` 里的常量；与 app `NotifyScene.sceneId` 对齐）。 */
    val scene: String,
    val subject: String,
    /** 收件地址（`smtp_to` 原样，可能是逗号分隔的多个地址）。 */
    val recipient: String,
    /**
     * 结果三态：[OUTCOME_SENT] / [OUTCOME_FAILED] / [OUTCOME_SKIPPED]（DB v12 起）。
     *
     * **这一列是唯一真源**，[success] 由它算出来。默认 `'sent'` + 迁移里按 `success` 回填：
     * v11 及以前只有"发起过投递"的行，所以旧数据必然是 sent/failed 二态。
     */
    val outcome: String = OUTCOME_SENT,
    /**
     * `outcome == "sent"`。
     *
     * **不要拿它当第二个状态位读，也不要单独写。** 它留在表里只因为删一个 NOT NULL 列要写
     * 一次"建新表 + 搬数据 + 删旧表"的迁移，而唯一收益是少一列（同 [MailSendRecord] 表名
     * 那笔历史包袱）。写入口只有 `SmsForwardController.recordChannelHistory` 一处，
     * 它**自己**从 [outcome] 算这个布尔 —— 调用方拿不到把两者写成矛盾值的机会。
     */
    val success: Boolean,
    /**
     * 失败原因（异常链摊平，最多 200 字）/ 跳过原因（`SkipReason.label` 的中文说明）；
     * 成功时空串。
     */
    val error: String = "",
    val sent_at: Long = System.currentTimeMillis()
) {
    companion object {
        /** 投出去了（有送达确认的渠道 = 对方真收下了）。 */
        const val OUTCOME_SENT = "sent"

        /** 发起过投递但没成功（重试耗尽后的终态）。 */
        const val OUTCOME_FAILED = "failed"

        /**
         * 没发起投递（免打扰 / 场景未勾 / 级别不够 / 配额用尽）。
         *
         * **不算失败** —— 列表页与 `failed_total` 都必须把它和 [OUTCOME_FAILED] 分开，
         * 否则用户会以为自己有一堆发信失败（沿用 `MailStats` 的既有口径）。
         */
        const val OUTCOME_SKIPPED = "skipped"
    }
}

/**
 * 终端命令历史（DB v13，2026-09-10）。
 *
 * 此前两端各存一份且互不可见：app 在 `filesDir/console_history.json`、web 在
 * `localStorage['ufi.console.history']`，手机上执行过的命令换到网页端查不到。
 * 这张表把它收成唯一真源，两端都只读 core（与 [MailSendRecord] / [SmsBlockedLog] 同一范式）。
 *
 * 顺带补一个审计缺口：`ShellRoutes` 的 `TAG = "ShellAudit"` 只写 logcat，release 构建
 * 捞不回来 —— 现在「谁在什么时候执行了什么、结果如何」落库可查。被安全黑名单拦下的
 * 命令**同样入库**（[exit_code] = null、[ok] = false），否则审计里恰好缺了最该看的那部分。
 *
 * 隐私与体积：[stdout] / [stderr] 各截断到 [MAX_OUTPUT_CHARS]，截断过就置 [truncated]。
 * 刻意**不提供**「留存全文」开关（短信拦截记录有那个开关）—— shell 输出可能含密码、
 * token、私钥，敏感度比短信正文更难预判，不给用户一个容易误开的旋钮。
 */
@Serializable
@Entity(
    tableName = "console_history",
    indices = [
        Index(value = ["created_at"]),
        Index(value = ["channel"])
    ]
)
data class ConsoleHistoryRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** [CHANNEL_SHELL] / [CHANNEL_AT]。 */
    val channel: String,
    val command: String,
    /** Shell 是否以 root 执行；AT 通道恒 false。 */
    val as_root: Boolean = false,
    /**
     * 退出码。AT 通道与「被安全策略拦下」的记录没有退出码，落 null。
     *
     * **不要用 0 代替 null**：0 是「执行成功」的真值，混在一起就分不清
     * 「成功返回 0」和「压根没执行」。
     */
    val exit_code: Int? = null,
    val stdout: String = "",
    val stderr: String = "",
    /** 是否成功。Shell 看 `exitCode == 0`，AT 看接口的 `success` 字段。 */
    val ok: Boolean,
    /** [stdout] 或 [stderr] 被截断过。 */
    val truncated: Boolean = false,
    /** 端到端耗时（毫秒），在路由层量的 —— `ShellResult` 本身不带耗时。 */
    val duration_ms: Long = 0,
    /** 发起端：[SOURCE_APP] / [SOURCE_WEB] / [SOURCE_UNKNOWN]。客户端自报，仅用于展示。 */
    val source: String = SOURCE_UNKNOWN,
    val created_at: Long = System.currentTimeMillis()
) {
    companion object {
        const val CHANNEL_SHELL = "shell"
        const val CHANNEL_AT = "at"

        const val SOURCE_APP = "app"
        const val SOURCE_WEB = "web"
        const val SOURCE_UNKNOWN = "unknown"

        /**
         * 单条 stdout / stderr 的留存上限。
         *
         * `ShellExecutor.MAX_OUTPUT_SIZE` 允许 1MB 输出，一条 `logcat -d` 就能把它填满；
         * 500 条 × 1MB 会把设备上的库撑到无法接受。4096 字符足够回答「这条命令干了什么」。
         */
        const val MAX_OUTPUT_CHARS = 4096

        /** 每通道保留条数，与两端旧实现的 500/tab 对齐，升级后观感不变。 */
        const val MAX_ROWS_PER_CHANNEL = 500

        /** 按 [MAX_OUTPUT_CHARS] 截断，返回「截断后的文本 + 是否截断过」。 */
        fun clip(text: String): Pair<String, Boolean> =
            if (text.length <= MAX_OUTPUT_CHARS) text to false
            else text.take(MAX_OUTPUT_CHARS) to true
    }
}
