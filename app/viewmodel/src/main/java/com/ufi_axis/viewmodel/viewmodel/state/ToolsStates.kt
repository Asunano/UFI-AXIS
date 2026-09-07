package com.ufi_axis.viewmodel.state

import com.ufi_axis.data.model.*

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
    val verificationCodesLoading: Boolean = false,
    /**
     * 「通知」页签角标数：`msgId` 大于已读水位（[com.ufi_axis.util.AppPreferences.smsCodeSeenMsgId]）
     * 的验证码条数。**不是总条数** —— 总条数看过也不会变，角标会永久挂着。
     * 进入通知页签会把水位推到当前最大 msgId，此值随之归 0。
     */
    val verificationCodesUnread: Int = 0,
    val smsCodeEnabled: Boolean = false,
    val smsCodeOptInShown: Boolean = false,
    val smsCodeCleanupHours: Int = 24,
    val navigateToPhone: String = "",                 // 非空时 SmsScreen 跳转到该联系人对话
    /** 跟 [navigateToPhone] 一起下发的定位目标消息 id（0 = 不定位）；随 navigateToPhone 一起清空 */
    val navigateToMsgId: Long = 0L
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
    /** `GET /api/sms-forward/diagnose` 的结果；null = 还没成功读到过（UI 不画诊断区）。 */
    val diagnose: SmsForwardDiagnose? = null,
    val isLoading: Boolean = false,
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
