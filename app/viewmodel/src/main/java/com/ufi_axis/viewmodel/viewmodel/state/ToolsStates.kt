package com.ufi_axis.viewmodel.state

import com.ufi_axis.data.model.*
import com.ufi_axis.data.notification.NotifyHistoryEntity
import com.ufi_axis.data.notification.NotifyHistoryStore

import java.util.concurrent.atomic.AtomicLong
import kotlinx.serialization.Serializable

// ========== 高级控制台：聊天式命令/返回消息模型 ==========

/** 消息角色：用户指令 / 基带或 Shell 返回 / 执行错误 */
@Serializable
enum class ConsoleRole { USER, ASSISTANT, ERROR }

/**
 * 单条控制台消息（用于「高级控制台」聊天式展示）。
 * @param id 消息唯一 id（默认按创建时间，保证 LazyColumn key 稳定）
 * @param role 消息角色（决定气泡样式）
 * @param text 消息正文
 * @param timestamp 创建时间戳（毫秒），用于气泡下方时间标签
 */
@Serializable
data class ConsoleMessage(
    val id: Long = nextMessageId(),
    val role: ConsoleRole,
    val text: String,
    val timestamp: Long = System.currentTimeMillis()
) {
    companion object {
        // 单调递增序列，避免同一毫秒内连续创建的消息（用户指令 + 返回）id 相撞
        // 导致 LazyColumn key 重复而崩溃（IllegalArgumentException: Key already used）。
        private val seq = AtomicLong(System.currentTimeMillis())
        fun nextMessageId(): Long = seq.incrementAndGet()

        fun seedIdAbove(id: Long) {
            if (id > seq.get()) seq.set(id)
        }
    }
}

// ========== Tools ==========

data class ToolsState(
    val smsList: List<SmsRecord> = emptyList(),
    val smsContacts: List<SmsContact> = emptyList(),
    /**
     * `GET /api/sms/count` 的结果；null = 还没成功读到过（UI 不画容量行）。
     *
     * 为什么不拿 [smsContacts] 的 unread 求和：联系人列表只覆盖有会话记录的号码，求和会偏小；
     * `/api/sms/list` 的 `total` 又是「当次查询条件下的条数」。全局口径只有这一个端点。
     */
    val smsCount: SmsCountResponse? = null,
    /**
     * 联系人列表**已经完成过一次加载尝试**（成功或失败都算），首屏骨架的唯一判据。
     *
     * 2026-09-08：不能让 UI 去嗅 [isLoading] 的 true→false 边沿 —— `snapshotFlow` 会把
     * "发起请求"和"结果回来"两次变更合并成一次，边沿一丢闩锁就永远合不上、骨架挂死；
     * 零条会话的用户更是永远看不到空态。这里给一个**只前进不回退**的落地信号，
     * 静默刷新也照写（它不改变"已经落地过"这个事实，因此不会让骨架重放）。
     */
    val smsContactsLoaded: Boolean = false,
    val atMessages: List<ConsoleMessage> = emptyList(),
    val shellMessages: List<ConsoleMessage> = emptyList(),
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    // ── 短信对话分页 ──
    val conversationPhone: String = "",
    val conversationMessages: List<SmsRecord> = emptyList(),
    val conversationOffset: Int = 0,
    val conversationHasMore: Boolean = true,
    val conversationLoading: Boolean = false,
    val conversationTotal: Int = 0,
    /**
     * 对话打开后要**定位并高亮**的那条消息 id（0 = 不定位，落到最新一条）。
     *
     * 目前唯一来源是验证码详情弹窗的「查看原对话」：只把号码带过去的话，对话只会停在
     * 最新消息，用户还得自己往上翻找那条验证码。由 [conversationPhone] 一起清理。
     */
    val conversationTargetMsgId: Long = 0L,
    // ── 删除动画 ──
    val deletingMessageIds: Set<Long> = emptySet(),
    // ── SMS Tab + 验证码 ──
    val smsTab: Int = 0,                              // 0=消息, 1=通知
    val verificationCodes: List<com.ufi_axis.data.model.VerificationCode> = emptyList(),
    /**
     * 验证码列表**已经完成过一次加载尝试**（成功或失败都算），首屏骨架的唯一判据。
     *
     * 2026-09-08 事故：验证码页首屏永久停在骨架。原来 UI 靠 `verificationCodesLoading` 的边沿
     * 判断"落地过没有"，而那个字段自 2026-08-29 改无感刷新后**从来没有被置成 true**，
     * 加载失败又被 catch 静默吞掉（不写 [errorMessage]），三个信号全不动 →
     * 闩锁永远合不上，`UfiEmptyState`（"暂无验证码通知"）成了不可达代码。
     * 所以判据换成这个显式落地信号：成功与失败两条路都写 true，且只前进不回退 ——
     * 后续静默刷新不会让骨架再闪一次。
     */
    val verificationCodesLoaded: Boolean = false,
    /**
     * 「通知」页签角标数：`msgId` 大于已读水位（[com.ufi_axis.util.AppPreferences.smsCodeSeenMsgId]）
     * 的验证码条数。**不是总条数** —— 总条数看过也不会变，角标会永久挂着。
     * 进入通知页签会把水位推到当前最大 msgId，此值随之归 0。
     */
    val verificationCodesUnread: Int = 0,
    val smsCodeEnabled: Boolean = false,
    val smsCodeOptInShown: Boolean = false,
    val smsCodeCleanupHours: Int = 24,
    val smsCodeAutoCopy: Boolean = false,
    val navigateToPhone: String = "",                 // 非空时 SmsScreen 跳转到该联系人对话
    /** 跟 [navigateToPhone] 一起下发的定位目标消息 id（0 = 不定位）；随 navigateToPhone 一起清空 */
    val navigateToMsgId: Long = 0L,
    // ── 短信拦截（规则 + 记录）──
    // 判定全在 core：命中就不发邮件、不推通知、不入验证码库、不进列表与计数。
    // app 这一段状态**只用于渲染与编辑规则**，不含任何拦截判定。
    val smsRules: List<SmsRule> = emptyList(),
    /**
     * 规则列表**已经完成过一次加载尝试**（成功或失败都算），首屏骨架的唯一判据。
     *
     * 与 [smsContactsLoaded] / [verificationCodesLoaded] 同一套口径 ——
     * 2026-09-08 事故的结论是「不要嗅 loading 的边沿」：`snapshotFlow` 会把
     * 「发起请求」和「结果回来」两次变更合并成一次，边沿一丢骨架就永久挂死、空态不可达。
     * 这个信号只 false→true、绝不回退，静默刷新也照写（它不改变「已经落地过」这个事实）。
     */
    val smsRulesLoaded: Boolean = false,
    /**
     * 规则正在写入（新增 / 改 / 删 / 切开关）。用于禁用重复提交，**不驱动骨架**。
     *
     * 与 [smsRulesLoaded] 分开：那个是单调闩锁（管首屏），这个是可回落的忙碌态（管按钮）。
     */
    val smsRulesBusy: Boolean = false,
    val smsBlocked: List<SmsBlockedLog> = emptyList(),
    /** 拦截记录已完成过一次加载尝试，判据口径同 [smsRulesLoaded]。 */
    val smsBlockedLoaded: Boolean = false,
    /** 正在加载下一页（滚到底触发）。有它才能避免同一个游标被连发好几次。 */
    val smsBlockedLoadingMore: Boolean = false,
    /** keyset 游标：下一页的 `cursor_ts`（null = 从头开始）。 */
    val smsBlockedCursorTs: Long? = null,
    /** keyset 游标：下一页的 `cursor_id`（与 [smsBlockedCursorTs] 成对使用，单独一个会漏/重）。 */
    val smsBlockedCursorId: Long? = null,
    val smsBlockedHasMore: Boolean = false,
    /** 记录总条数（core 侧环形上限写死 500）。 */
    val smsBlockedTotal: Int = 0,
    /**
     * 「已拦截」角标数：id 大于已读水位（[com.ufi_axis.util.AppPreferences.smsBlockedSeenId]）的记录条数。
     * **不是总条数** —— 总条数看过也不会变，角标会永久挂着。
     */
    val smsBlockedUnviewed: Int = 0,
    /**
     * 验证码豁免关键词拦截（core 真源，默认 **true**）。
     *
     * 默认值必须与 core 的 `AppSettings.smsFilterExemptVerificationCode` 一致，
     * 否则开关一进页面就显示成关、用户「打开」它其实什么都没改 —— 那就是假开关。
     */
    val smsFilterExemptVerificationCode: Boolean = true,

    // ── 通知历史（2026-09-08）──
    // 两份数据源两套字段：系统通知历史读**本机 Room**（`notify_history`，写入方是 `:ufi_notify`），
    // 投递记录读**设备端 API**（`GET /api/sms-forward/history`）。放同一个 state 里是因为
    // 它们同属一族"记录"页，且两份都要在页面重回前台时重查一次。
    val notifyHistory: List<NotifyHistoryEntity> = emptyList(),
    /** 已完成过一次加载尝试，判据口径同 [smsRulesLoaded]。 */
    val notifyHistoryLoaded: Boolean = false,
    /** 正在加载（首页或下一页）。兼作并发闸门 —— 首页与翻页同时跑会互相盖掉游标。 */
    val notifyHistoryLoadingMore: Boolean = false,
    /** 本机历史总条数（不受当前筛选影响）。 */
    val notifyHistoryTotal: Int = 0,
    val notifyHistoryHasMore: Boolean = false,
    /** keyset 游标：下一页的 `(ts,id)`（成对使用，缺一个就当首页）。 */
    val notifyHistoryCursorTs: Long? = null,
    val notifyHistoryCursorId: Long? = null,
    /** 当前结果筛选（全部 / 已送达 / 被拦下）。换筛选要重新从第一页拉。 */
    val notifyHistoryFilter: NotifyHistoryStore.Filter = NotifyHistoryStore.Filter.ALL,

    // ── 投递记录（三渠道共用一份 state 槽位）──
    // 列表本身在 core 就是三渠道共用一张表（`channel` 列区分），所以这里也只有一份数据。
    // 同屏只会看一条渠道，因此不按渠道拆成三份 —— 拆了就要维护三套游标与三套忙碌位。
    val deliveryHistory: List<MailSendRecord> = emptyList(),
    val deliveryHistoryLoaded: Boolean = false,
    /** 正在加载（首页或下一页）。兼作并发闸门，语义同 [notifyHistoryLoadingMore]。 */
    val deliveryHistoryLoadingMore: Boolean = false,
    /** keyset 游标（与 [smsBlockedCursorTs] 同样的成对语义）。 */
    val deliveryHistoryCursorTs: Long? = null,
    val deliveryHistoryCursorId: Long? = null,
    val deliveryHistoryHasMore: Boolean = false,
    val deliveryHistoryTotal: Int = 0,
    /** 全表失败条数（`outcome == "failed"`）；只数当前页会随翻页变动。 */
    val deliveryHistoryFailedTotal: Int = 0,
    /**
     * 全表跳过条数（`outcome == "skipped"`）。
     *
     * 与 [deliveryHistoryFailedTotal] 分开存、**不相加**：跳过是闸门按用户配置拦下的，
     * 投递从未发起，并进失败数就等于把"按设置没发"报成"发送出了故障"。
     */
    val deliveryHistorySkippedTotal: Int = 0,
    /**
     * 当前结果筛选：null = 不过滤，其余取接口的 `result` 原值
     * （`"success"` / `"failed"` / `"skipped"`）。服务端过滤，见 `getMailHistory`。
     *
     * 存线上口径的字符串而不是布尔：结果是三态，布尔装不下第三档。
     */
    val deliveryHistoryResult: String? = null,
    /**
     * 当前装的是哪个渠道的记录（`"mail"` / `"webhook"` / `"local_sms"`，null = 全部渠道）。
     *
     * 三条渠道页共用同一个槽位，各自的入口行摘要必须先比对这个字段 ——
     * 不比就会把上一个渠道的失败数显示成本渠道的。
     */
    val deliveryHistoryChannel: String? = null
)

// ========== Alerts ==========

data class AlertsState(
    val config: AlertConfig? = null,
    val alerts: List<AlertRecord> = emptyList(),
    val isLoading: Boolean = false,
    val errorMessage: String? = null
)

// ========== Tasks ==========

data class TasksState(
    val tasks: List<ScheduledTask> = emptyList(),
    val taskLogs: Map<String, List<ExecutionLog>> = emptyMap(),
    // 条件规则（当…就…），与 tasks 共用本 state 便于双 Tab 同屏切换
    val rules: List<AutomationRule> = emptyList(),
    // 历史遗留字段名 taskLogs；规则日志单独存，避免与 task 日志混淆
    val ruleLogs: Map<String, List<ExecutionLog>> = emptyMap(),
    val isLoading: Boolean = false,
    val errorMessage: String? = null
)

// ========== SMS Forward ==========

data class SmsForwardState(
    val config: SmsForwardConfig? = null,
    /**
     * 已完成过一次配置加载尝试（成功 / 失败都置位），**单调闩锁，只 false→true**。
     *
     * 形状与 [WebhookState.loaded] / [LocalSmsState.loaded] 一致：没有它的话
     * [config] `== null` 同时代表"还在读"与"读失败了"，界面只能一直显示「加载中」——
     * 推送渠道总览页的邮件那一行就这么永久停在「加载中」过。
     */
    val loaded: Boolean = false,
    /** `GET /api/sms-forward/diagnose` 的结果；null = 还没成功读到过（UI 不画诊断区）。 */
    val diagnose: SmsForwardDiagnose? = null,
    val isLoading: Boolean = false,
    /**
     * 最近一次「测试发送」返回的 `auto_notify_enabled`；null = 本次会话还没测过。
     *
     * 与"测试是否成功"是两件事：测试信走 manual 口径不受闸门约束，所以完全可能
     * 测试成功而这里是 false —— 那说明 SMTP 通了、**自动**通知却一条都不会发。
     * 不读它就会出现"按了测试看到成功，实际收不到任何自动通知"的假成功。
     */
    val lastTestAutoNotifyEnabled: Boolean? = null,
    val errorMessage: String? = null
)

// ========== Webhook 通知渠道 ==========

/**
 * 通用 Webhook 渠道的页面状态（`/api/notify/webhook/…`）。
 *
 * 三个 in-flight 位刻意分开，**不复用一个 `isLoading`**：邮件那侧就是共用一位，
 * 于是"刚进页面自动加载"把测试按钮置灰、"点测试"让保存按钮转圈
 * （教训写在 `EmailNotifyScreen` 文件头）。这里读配置 / 写配置 / 发测试各自一位。
 */
data class WebhookState(
    /** null = 还没成功读到过配置。读不到时 UI 不许用默认值假装"已加载"。 */
    val config: WebhookConfigResponse? = null,
    /**
     * 已完成过一次加载尝试（成功 / 失败都置位），**单调闩锁，只 false→true**。
     *
     * 首屏"加载中"与"未启用"靠它区分。口径同 [ToolsState.smsRulesLoaded] ——
     * 不要改成嗅 [loading] 的边沿：边沿会被 snapshot 合并掉，那条路已经踩过。
     */
    val loaded: Boolean = false,
    /** 正在读配置。只用于下拉/重试的转圈，**不驱动首屏判据**。 */
    val loading: Boolean = false,
    /** 正在写配置（任一字段）。用于禁用重复提交。 */
    val saving: Boolean = false,
    /** 正在发测试。 */
    val testing: Boolean = false,
    /**
     * 保存动作的结算计数：每次 PUT 有结果（成功或失败）就 +1。
     *
     * UI 拿它当 `LaunchedEffect` 的 key 报一次结果。用递增计数而不是嗅 [saving] 的下降沿，
     * 理由同 [loaded]：布尔边沿会被合并掉，而每次 +1 的 Int 一定触发重组。
     */
    val saveTick: Int = 0,
    /** 最近一次保存的失败原因；null = 上次保存成功。与 [saveTick] 成对读。 */
    val saveError: String? = null,
    /** 测试动作的结算计数，口径同 [saveTick]。 */
    val testTick: Int = 0,
    /** 最近一次测试的完整响应（状态码 + 响应体摘要都在里面）。 */
    val lastTest: WebhookTestResponse? = null,
    /** 读配置失败的原因，常驻 banner 展示。写配置的失败走 [saveError]（一次性 toast）。 */
    val errorMessage: String? = null
)

// ========== 本机短信回发渠道 ==========

/**
 * 本机短信渠道的页面状态（`/api/notify/sms/…`）。
 *
 * 形状与 [WebhookState] 一致（三个 in-flight 位分开、`loaded` 单调闩锁、两个结算计数），
 * 理由见那边的注释 —— 两个页面的交互节奏相同，状态形状不同只会让人以为其中一个有特殊之处。
 *
 * 唯一的额外之处：[lastTest] 里带着**配额结算**。这条渠道按条计费，
 * "刚才那一下算不算进今天的 5 条"是用户按下测试后最想知道的事。
 */
data class LocalSmsState(
    /** null = 还没成功读到过配置。读不到时 UI 不许用默认值假装"已加载"（一保存就覆盖真配置）。 */
    val config: LocalSmsConfigResponse? = null,
    /** 已完成过一次加载尝试（成功 / 失败都置位），**单调闩锁**。首屏"加载中"与"未启用"靠它区分。 */
    val loaded: Boolean = false,
    val loading: Boolean = false,
    val saving: Boolean = false,
    val testing: Boolean = false,
    /** 保存动作的结算计数：每次 PUT 有结果就 +1。UI 拿它当 `LaunchedEffect` 的 key。 */
    val saveTick: Int = 0,
    val saveError: String? = null,
    /** 测试动作的结算计数，口径同 [saveTick]。 */
    val testTick: Int = 0,
    /** 最近一次测试的完整响应（固件结论 + 配额结算都在里面）。 */
    val lastTest: LocalSmsTestResponse? = null,
    val errorMessage: String? = null
)

// ========== Debug Log ==========

/**
 * 日志页状态（2026-08-27 重构）。
 *
 * 只承载 **core 侧**日志：APP 侧日志由 `AppLogBuffer` 直接提供给 UI（进程内内存，
 * 过一遍 ViewModel 只是白拷贝一次 500 元素的列表）。
 *
 * 级别 / 类型 / 关键字筛选**不在这里**：那是纯展示态，放 UI 本地即可，
 * 也避免像重构前那样「切一次级别就重新发一次 HTTP」。
 */
data class DebugLogState(
    val coreEntries: List<com.ufi_axis.util.LogEntry> = emptyList(),
    val isLoading: Boolean = false,
    val errorMessage: String? = null
)

/**
 * core 落盘日志文件浏览态（2026-08-28）。
 *
 * 单独一个 state 而不是并进 [DebugLogState]：这里的列表/正文只在弹窗打开时按需拉一次，
 * 和实时日志 2s 轮询的生命周期完全不同。
 *
 * 存在的理由是 core 的 logs 目录是 app 私有目录（0700），未 root 的设备用文件管理器
 * 看不到内容——只能经 `/api/debug-logs/files` 读。
 */
data class CoreLogFilesState(
    val files: List<com.ufi_axis.data.model.DebugLogFileItem> = emptyList(),
    val totalBytes: Long = 0,
    /** core 侧日志目录绝对路径（同一路径在设备的文件管理器里可直接打开）。 */
    val dir: String = "",
    /** 正在查看的文件名；null = 只在看列表。 */
    val viewingName: String? = null,
    /** 已拉回的文件尾部正文。 */
    val viewingText: String? = null,
    val isLoading: Boolean = false,
    val errorMessage: String? = null
)

// ========== 日志开关（2026-09-04） ==========

/**
 * 日志四层开关在 app 侧的**只读镜像**，唯一真源是 core 的 `AppSettings`
 * （`GET/PUT /api/config` 的 `log_enabled` / `core_log_enabled` / `app_log_enabled` / `debug_mode`）。
 *
 * 为什么要有这个 state、而不是让 UI 直接读 `AppPreferences`：
 * 原来日志页的四个开关初值取自本地 `AppPreferences`，点一下先写本地、再 fire-and-forget 地
 * PUT 给 core，**PUT 失败只记一行 WARN**。于是「手机端显示已关闭 / core 仍然是开 / web 读 core
 * 显示开启」会永久分叉，且用户没有任何提示。现在改成：**PUT 成功才更新这份镜像和本地缓存**，
 * 失败就把 [errorMessage] 抛给 UI，开关自然停在旧值上（因为镜像没变）。
 *
 * [loaded] 用来区分「还没从 core 拉到」与「core 说全是 false」：未加载时 UI 禁用开关，
 * 避免用户在未知状态上点一下、把本地默认值当成用户意图写给 core。
 */
data class LogSwitchState(
    // 与 core `AppSettings.DEFAULT_*` 逐字对齐：总闸默认关（日志只在用户开启后才记），
    // 两个子开关默认开（总闸打开后两侧立即都有日志）。
    val logEnabled: Boolean = false,
    val coreLogEnabled: Boolean = true,
    val appLogEnabled: Boolean = true,
    val debugMode: Boolean = false,
    /** 是否已从 core 回读成功过（false = 当前展示的是本地缓存，仅供参考）。 */
    val loaded: Boolean = false,
    /** 正在下发中：期间禁用开关，防止连点产生互相覆盖的并发 PUT。 */
    val isSaving: Boolean = false,
    val errorMessage: String? = null
)

/**
 * 一次**尚未向用户提示过**的 core 崩溃（2026-09-04）。
 *
 * core 崩溃后由 keepalive 脚本 / START_STICKY 自动拉起，HTTP 很快恢复，用户只看到数据断了一下；
 * 这里把它显式提示出来。[timestamp] 同时是去重键（写回 `AppPreferences.coreCrashShownAt`）。
 */
data class CoreCrashNotice(
    val timestamp: Long,
    val summary: String,
    /** 崩溃详情文件在**设备**上的路径（app 不读它，只告诉用户去哪找）。 */
    val file: String
)

// ========== 运行诊断（2026-08-30） ==========
/**
 * 「运行诊断」页的聚合状态：`/api/diagnose` + `/api/qos/status` + `/api/cache/stats` +
 * `/api/system/root-check` + `/api/shell/root`。
 *
 * 全部是**只读诊断**，所以：
 * - 每一项独立可空，某个端点挂了不影响其它区块显示（不做「一个失败整页空白」）；
 * - [errorMessage] 只用于**动作**（清缓存/失效缓存）失败，读取失败只打日志 —— 否则一个
 *   旧版本 core 缺某个端点就会让页面顶部常驻一条红条。
 * - [fieldCoverageRequested] 记住用户是否点过「检测字段覆盖率」：那条路径会逐分组向设备发查询
 *   （最多 10 组），绝不能随页面刷新自动带上。
 */
data class DiagnoseState(
    val diagnose: DiagnoseResponse? = null,
    val qos: QosStatusResponse? = null,
    val cache: CacheStatsResponse? = null,
    val rootCheck: RootCheckResponse? = null,
    val shellRoot: ShellRootResponse? = null,
    val isLoading: Boolean = false,
    val isBusy: Boolean = false,
    val fieldCoverageRequested: Boolean = false,
    val errorMessage: String? = null
)

// ========== Traffic Management ==========
data class TrafficManagementState(
    val limitConfig: TrafficLimitConfig? = null,
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val errorMessage: String? = null,
    val successMessage: String? = null
)

// ========== Frontend App Update（2026-08-10 C5：前端 App 自更新） ==========

/**
 * 前端 App 更新状态机：
 * idle → checking → available | no_update
 * available → downloading → downloaded → installing
 * 任意失败 → error（errorMessage 说明原因）
 */
data class FrontendUpdateState(
    val state: String = "idle",               // idle/checking/available/no_update/downloading/downloaded/installing/error
    val currentVersion: String = "",          // 当前已安装 App 版本（PackageManager 读取）
    val latestVersion: String = "",           // 清单最新版本
    val changelog: String = "",               // 更新日志（多行 markdown 文本）
    val latestApkUrl: String = "",            // 清单 frontend.apkUrl（直连下载源，2026-08-12 raw version.json）
    val latestSha256: String = "",            // 清单 frontend.apkSha256（下载后校验）
    val downloadProgress: Int = 0,            // 0-100
    val downloadedApkPath: String? = null,    // 下载完成后的 APK 路径（filesDir/update/app-update.apk）
    val errorMessage: String? = null
)
