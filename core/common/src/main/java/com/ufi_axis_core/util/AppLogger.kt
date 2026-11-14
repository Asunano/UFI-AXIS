package com.ufi_axis_core.util

import android.util.Log
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * 统一日志管理器：Logcat 输出与文件持久化（按天滚动）。
 *
 * 日志文件存储路径：`/sdcard/Download/UFI-AXIS/log/core/<yyyy-MM-dd>/`
 * - app.log   应用日志
 * - at.log    AT 指令日志
 * - error.log 错误日志
 *
 * 日志不做轮转，也没有总量上限（见 [MAX_LOG_FILE_BYTES] 的说明）；外部存储不可用时
 * 回退到私有目录 `<dataDir>/logs`。
 *
 * 文件写入复用 BufferedWriter，不按行创建 FileWriter / PrintWriter。
 *
 * 体积控制由以下机制承担：
 * 1. 错误去重：相同 (message + 堆栈指纹) 在 [ERROR_DEDUP_WINDOW_MS] 内只记录一次
 *    （后续仅累计计数），避免高频采集任务反复抛出相同的 ENOENT / ConnectException
 *    使错误日志快速增长（曾达 30MB/天）。
 * 2. 按级别落盘：DEBUG / INFO 仅在详细日志开启时落盘，WARN / ERROR 始终落盘。
 *    该约束是必要的：HTTP 访问日志为每个请求一行 DEBUG，若不受 debug_mode 约束，
 *    磁盘增长将直接与请求量挂钩。
 * 3. 跨天清理：`cleanOldLogs()` 在跨天写入时自动执行；仅在 [init] 调用一次
 *    不足以保证长期运行的进程得到清理。
 *
 * 开关分三层，从粗到细：
 * - [setLogEnabled]（`log_enabled`）—— 总开关；
 * - [setCoreLogEnabled]（`core_log_enabled`）—— 只作用于 core 这一侧
 *   （app 侧对应 `DebugLog.appEnabled`）；
 * - [setDebugMode]（`debug_mode`）—— 详细级别，控制 DEBUG / INFO 是否进入缓冲与磁盘。
 *
 * 三者的唯一真源都是 core 的 [AppSettings]（app 与 web 都经 `GET/PUT /api/config` 读写），
 * 进程启动时必须用 [restoreSwitches] 一次性恢复；漏恢复其中任何一个都会导致
 * 日志开关的实际状态与持久化值不一致。
 *
 * WARN / ERROR 无视总开关，始终落地；INFO / DEBUG 行为不变。判定收敛到 [shouldEmit]，
 * [log] 与 [e] 的提前返回都经过它。
 */
object AppLogger {

    private const val TAG = "UFI-AXIS-Core"
    // ═══ 日志保留策略：不设内部上限 ═══
    //
    // core 日志是定位图表断层的唯一证据来源。按天 / 单文件 / 目录总量三级上限在长时间
    // 采集时会把关键记录直接轮转掉，因此内部不设上限：
    // · 单文件轮转 → 覆盖 .log.1，同一分钟内的高频日志只保留最新一段，起始部分丢失；
    // · 目录总量上限 → enforceTotalBudget() 按修改时间删除最旧文件，同样会删除当天的记录。
    //
    // 代价：日志目录只受设备存储容量约束，长期开启日志的设备上会持续增长。
    // 如需收紧策略，应采用「按天保留 + 可查询的占用提示」，而不是截断写入内容。
    private const val MAX_LOG_FILES = 3650  // 约 10 年；等价于不删除，仅避免清理时删除近期目录
    private const val MAX_BUFFER_SIZE = 500  // 内存缓冲区最大条目数
    private const val ERROR_DEDUP_WINDOW_MS = 5 * 60 * 1000L  // 错误去重窗口：5 分钟
    // 单文件轮转已关闭（见 MAX_LOG_FILES 上方说明）
    private const val MAX_LOG_FILE_BYTES = Long.MAX_VALUE
    // 目录总预算已关闭（同上）
    private const val MAX_LOG_DIR_BYTES = Long.MAX_VALUE

    /**
     * 低磁盘保护阈值。
     *
     * 不设保留上限不等于允许写满存储：可用空间低于此值时停止写入文件，
     * 只保留 logcat 与内存缓冲（崩溃 dump 依赖后者），并通过 [isDegraded] 暴露给 UI。
     * 检查按 [FREE_SPACE_CHECK_INTERVAL_MS] 限频，不在每条日志上执行 syscall。
     */
    private const val MIN_FREE_BYTES = 256L * 1024 * 1024
    private const val FREE_SPACE_CHECK_INTERVAL_MS = 30_000L

    /** 落盘 flush 间隔：不逐条 flush，由后台定时器按该间隔刷新。 */
    private const val FLUSH_INTERVAL_MS = 1_000L

    /** 错误去重表上限：达到上限时整体清空（与 repeatGate 相同的策略）。 */
    private const val ERROR_DEDUP_MAX = 256

    /**
     * 详细日志关闭时，内存缓冲仍然收录的最低级别。
     *
     * 取 WARN 而不是 ERROR：崩溃现场 dump 靠的就是这个缓冲，而压垮进程前的关键线索
     * 往往是一串 WARN（重试、降级、句柄失败），只留 ERROR 会把因果链掐掉。
     * 500 条上限恒定，WARN 频率远低于 DEBUG，内存代价可忽略。
     */
    private val BUFFER_MIN_LEVEL_QUIET = LogLevel.WARN

    /** 崩溃现场 dump 的最大条目数（见 [dumpRecentForCrash]）。 */
    private const val CRASH_DUMP_LINES = 200

    /**
     * 用户可直接查看的日志根目录。
     *
     * 位于公共 Download 目录下，与 `DownloadLog` 的 `error.log` / `launcher.log` 同一个父目录；
     * app 私有目录（0700）在无 root 的设备上无法被文件管理器访问，只能经 API 读取。
     *
     * 布局：`<root>/<yyyy-MM-dd>/{app,at,error}.log`（以及轮转出的 `.1`）——
     * 先按日期分目录，再按类型分文件。
     *
     * 路径取自 [LogPaths]（唯一真源）：该目录同时被 DownloadLog / GoformSessionLog /
     * UpdateManager / InstallService 与两个 shell 脚本使用，多处写字面量会导致路径漂移。
     */
    private val USER_LOG_ROOT = File(LogPaths.dir(LogPaths.Component.CORE))

    /**
     * 合法日志相对路径（防 `/api/debug-logs/files/{name}` 目录穿越）。
     *
     * 除按天分目录的三类运行日志外，另放行两类排障证据：
     * `crash/crash_<ms>.txt`（崩溃现场）与 `update/update.log`（自更新关键事件）。
     * 目标设备通常没有可用的 adb 或屏幕，文件 API 是取回这些记录的唯一远程通道。
     */
    private val LOG_FILE_NAME = Regex(
        """^(?:\d{4}-\d{2}-\d{2}/(?:app|at|error)\.log(?:\.1)?|crash/crash_\d+\.txt|update/update\.log)$"""
    )


    enum class LogLevel { DEBUG, INFO, WARN, ERROR }
    enum class LogType(val prefix: String) {
        APP("app"), AT("at"), ERROR("error")
    }

    private var logDir: File? = null
    private val logBuffer = ConcurrentLinkedQueue<String>()

    /**
     * 详细级别（`debug_mode`）的进程内镜像。
     *
     * 初值必须等于 [AppSettings.DEFAULT_DEBUG_MODE]（false），不得为 true。
     * 该约束的来源：若此处置为 true，则「进程启动 → `ComponentFactory.build()` 调用
     * [setDebugMode]」这段窗口内详细日志恒为开启；当 `autoStartOnBoot=false`，
     * 或进程仅由 BootReceiver / InstallReceiver 拉起时 build() 不会执行，
     * 整个进程生命周期内详细日志都保持开启，DEBUG / INFO 全量落盘，
     * 表现为关闭开关并重启 core 后日志仍持续写入。
     *
     * 规则：进程内初值不得比持久化默认值更宽松；真实值一律由
     * [restoreSwitches] 在 Application.onCreate 中恢复。
     */
    @Volatile
    private var verboseEnabled: Boolean = AppSettings.DEFAULT_DEBUG_MODE

    /**
     * 进入内存缓冲的最低级别。
     *
     * 缓冲的写入条件只能由本字段决定，不得与 `debug_mode` 相与：若缓冲入口同时要求
     * 详细日志开启，则关闭详细日志后缓冲恒为空，`GET /api/debug-logs` 返回空数组，
     * web 的调试日志面板恒显示「暂无日志」—— 而这恰是排障最需要日志的状态。
     *
     * 语义：
     * - 详细日志开 → [LogLevel.DEBUG]，全量进入缓冲；
     * - 详细日志关 → [BUFFER_MIN_LEVEL_QUIET]（WARN），错误链始终保留在内存中，
     *   既可供 web 面板展示，也是崩溃时 dump 现场的唯一上下文来源。
     */
    @Volatile
    private var bufferMinLevel: LogLevel =
        if (AppSettings.DEFAULT_DEBUG_MODE) LogLevel.DEBUG else BUFFER_MIN_LEVEL_QUIET

    private val dateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.getDefault())
    private val timeFormat = DateTimeFormatter.ofPattern("HH:mm:ss.SSS", Locale.getDefault())

    // 文件写入器缓存 (key = "prefix_date"，自动按天切换)
    private val writerCache = ConcurrentHashMap<String, BufferedWriter>()

    // 落盘串行化：getOrPut、轮转（close/remove/重建）、write/flush 必须在同一临界区里，
    // 否则多协程并发会写出交错行，或对轮转时已关闭的流写入（Stream closed）。
    private val writerLock = Any()

    // 最近一次落盘所属日期，用于跨天触发 cleanOldLogs()（core 常驻不重启也能清理过期文件）
    @Volatile
    private var lastWriteDate: String? = null

    // ── 低磁盘保护与定时 flush 的运行态 ──
    @Volatile private var lastFreeCheckAt: Long = 0L
    @Volatile private var lowDisk: Boolean = false
    @Volatile private var degraded: Boolean = false
    @Volatile private var flushTimer: java.util.Timer? = null


    // 错误去重缓存：key = message + 堆栈指纹前 200 字符；value = 最后记录时间戳
    // 5 分钟内同一错误只记录 1 次（防止高频重复异常使日志量失控）
    private val errorDedupMap = ConcurrentHashMap<String, Long>()
    // 错误去重统计：key 同上；value = 累计被抑制的次数（首次满窗后写一行"重复 N 次"统计）
    private val errorDedupStats = ConcurrentHashMap<String, Int>()

    // ── 重复行折叠 ──
    // errorDedup 只作用于 ERROR。日志体积主要来自周期任务中逐 tick 重复的同一条 DEBUG / INFO：
    // `Alert xxx level unchanged, skip`（信号 3s 一条）、
    // `checkConnectivity state unchanged: ...`（3s）、`Flushed buffers: cpu=1, mem=1, ...`（10s）、
    // `Cache TTL updated to 3000ms`（30s）等，稳态下为 25-34MB/天；出现协程取消死循环时
    // 单日可达 304MB。
    // 因此对所有级别增加一层通用闸门：同一 (级别, tag, 完整消息) 在窗口内只放行 1 条，
    // 窗口结束后补一行「重复 N 次已折叠」，信息不丢失且体积上限恒定。
    private const val REPEAT_WINDOW_MS = 60_000L
    private const val REPEAT_MAP_MAX = 512

    private class RepeatState(var windowStart: Long, var count: Int)

    private val repeatMap = ConcurrentHashMap<String, RepeatState>()


    /**
     * 初始化日志目录。
     *
     * 优先用 [USER_LOG_ROOT]（公共 Download 目录，文件管理器可直接看）；外部存储不可用时
     * 回退到 `baseDir/logs`（app 私有目录）——回退不是为了兼容旧路径，而是保证
     * 「存储没挂载/无权限时日志仍有地方写」，否则排障最需要日志的场景反而一条都没有。
     */
    fun init(baseDir: File) {
        logDir = if (USER_LOG_ROOT.exists() || USER_LOG_ROOT.mkdirs()) {
            USER_LOG_ROOT
        } else {
            Log.w("$TAG/Logger", "user-visible log dir unavailable, fallback to private dir")
            File(baseDir, "logs").also { it.mkdirs() }
        }
        lastWriteDate = LocalDate.now().format(dateFormat)
        cleanOldLogs()
    }


    /**
     * 日志总开关（真源 `AppSettings.logEnabled`，持久化默认值 false）。
     *
     * false 的语义是省电与省 IO 的硬开关：不进入内存缓冲、不写 logcat、不写文件。
     *
     * 存在一个例外：WARN / ERROR 无视本开关（见 [shouldEmit]）。
     * INFO / DEBUG 仍完整受本开关约束 —— 这两级才是体积与功耗的来源。
     */
    @Volatile
    private var loggingEnabled = AppSettings.DEFAULT_LOG_ENABLED

    /**
     * core 端子开关（真源 `AppSettings.coreLogEnabled`，默认 true）。
     *
     * 与 [loggingEnabled] 为「与」关系：总开关是前置条件，本开关只关闭 core 这一侧。
     * app 侧对应 `com.ufi_axis.util.DebugLog.appEnabled`（真源 `AppSettings.appLogEnabled`），
     * 因此可以只关闭后端日志而保留手机端日志 —— core 常驻写盘，是体积增长的那一侧。
     *
     * 该开关与 [loggingEnabled] 语义不同，WARN / ERROR 无视总开关的规则不适用于它：
     * 本开关表达「按侧静音」的主动选择，而总开关是「全局静音」且持久化默认即为关闭。
     * 后者会让从未操作过开关的用户拿不到任何排障线索，前者不会
     * （用户至少主动关闭过一次，且手机端日志仍然存在）。见 [shouldEmit]。
     */
    @Volatile
    private var coreLogEnabled = AppSettings.DEFAULT_CORE_LOG_ENABLED

    /** core 实际是否记录日志：总开关与 core 子开关都开才算。 */
    private val active: Boolean get() = loggingEnabled && coreLogEnabled

    /**
     * 无视 [loggingEnabled] 的最低级别（见 [shouldEmit]）。
     *
     * 取 WARN 而不是 ERROR：排障判据级事件（「未登记写入项，忽略本次写入」「未提供频段全集掩码」
     * 「未知 deviceProfileId，回落」「主命令未成功，改发备用命令」）全部为 WARN，
     * 「不得静默失败」的判据要求这些事件必须留下一行日志。只放行 ERROR 会使该判据失效。
     */
    private val MASTER_BYPASS_MIN_LEVEL = LogLevel.WARN

    /**
     * 单条日志是否应当落地（logcat、文件、内存缓冲三条路径共用的总闸判定）。
     *
     * ## WARN / ERROR 为什么无视日志总开关
     *
     * 「不得静默失败」是全局约束：解不出字段、写入项未登记、profile 回落，都必须留下一行日志。
     * 但 [loggingEnabled] 的持久化默认值是 false（[AppSettings.DEFAULT_LOG_ENABLED]），
     * 而 [log] 的第一步即按总闸提前返回 —— 于是默认部署下排障级 WARN 一条都不会落地，
     * 该约束等于不成立；排障必须先开启总开关、重启 core、再复现一次，
     * 而「掩码缺失」「写入项未登记」这类事件往往在复现之前就已经过去。
     *
     * 因此 WARN / ERROR（[MASTER_BYPASS_MIN_LEVEL] 及以上）不看 [loggingEnabled]。
     *
     * ## INFO 为什么不在放行范围内
     *
     * 噪音与存储。INFO 包含 HTTP 访问日志（每个请求一行）、AT 指令日志（[at] 全部走 INFO）、
     * WebSocket 连接生命周期等，落盘量直接与请求量挂钩（日志体积治理前为 25-34MB/天，
     * 极端情况 304MB）。总开关对用户的承诺「关闭后不再增长」必须继续成立，
     * 而 WARN / ERROR 的量级与 INFO 不同（见下文的频率约束）。
     *
     * ## 放行范围是全部三条路，不只是 logcat
     *
     * 目标设备没有 root，用户无法获取 logcat；只放行 logcat 等于排障线索仍然取不回来，
     * 「默认部署下排障 WARN 落地」的判据不成立。文件路径约束的是频率而不是总量：
     * - [repeatGate]：同一 (级别, tag, 完整消息) 每 [REPEAT_WINDOW_MS] 只放行 1 条 ——
     *   周期任务中逐 tick 重复的 WARN（离线时的 `checkTrafficLimit failed` 一类）
     *   最多 1 条/分钟/文本；
     * - [e] 的错误去重：相同 (message + 堆栈指纹) 每 [ERROR_DEDUP_WINDOW_MS]（5 分钟）1 条；
     * - 不存在「第三条硬上限」：[MAX_LOG_FILE_BYTES]（单文件轮转）与 [MAX_LOG_DIR_BYTES]
     *   （目录总量）均为 `Long.MAX_VALUE`，[MAX_LOG_FILES] 为 3650 天。
     *   上述两条约束的是频率而不是总量，日志占用只受设备存储容量约束。
     *   不得据此推断「日志体积存在硬上限」（见 [MAX_LOG_FILE_BYTES] 上方的说明）。
     *
     * ## [coreLogEnabled] 不在放行范围内
     *
     * 见该字段的注释：它是「按侧静音」的主动选择且默认 true，不存在「用户未操作过开关
     * 因而拿不到线索」的问题。两者都无视会使「只关后端日志」这一功能失去意义。
     *
     * @param level 本条日志的级别
     * @param loggingEnabled 日志总开关（`log_enabled`）
     * @param coreLogEnabled core 侧子开关（`core_log_enabled`）
     */
    internal fun shouldEmit(level: LogLevel, loggingEnabled: Boolean, coreLogEnabled: Boolean): Boolean {
        if (!coreLogEnabled) return false
        if (loggingEnabled) return true
        return level.ordinal >= MASTER_BYPASS_MIN_LEVEL.ordinal
    }

    /** [shouldEmit] 的当前状态重载：读两个 `@Volatile` 镜像，调用点只需给级别。 */
    private fun shouldEmit(level: LogLevel): Boolean =
        shouldEmit(level, loggingEnabled, coreLogEnabled)


    /**
     * 一次性恢复三层开关（唯一真源 [AppSettings]）。
     *
     * 聚合入口的必要性：三个开关分属三个 setter，任何新增的进程入口都必须调用全部三个。
     * 若 `UfiAxisCoreApplication.onCreate` 只恢复 `log_enabled` / `core_log_enabled`，
     * 而 `debug_mode` 仅在 `ComponentFactory.build()` 中恢复，则不走 build 的启动路径
     * （`autoStartOnBoot=false`、由 BootReceiver / InstallReceiver 拉起）详细日志恒为开启。
     * 所有启动路径统一调用本方法后，遗漏一项会表现为编译错误而不是运行期缺陷。
     *
     * 语义是「持久化值优先」：调用方传入的必须是 [AppSettings] 的 getter 结果；
     * getter 仅在从未设置过时回退到 `DEFAULT_*`，因此升级与重启不会覆盖用户的选择。
     */
    fun restoreSwitches(logEnabled: Boolean, coreLogEnabled: Boolean, debugMode: Boolean) {
        loggingEnabled = logEnabled
        this.coreLogEnabled = coreLogEnabled
        setDebugMode(debugMode)
        if (!active) stopWriting()
    }

    fun setLogEnabled(enabled: Boolean) {
        loggingEnabled = enabled
        if (!active) stopWriting()
    }

    fun setCoreLogEnabled(enabled: Boolean) {
        coreLogEnabled = enabled
        if (!active) stopWriting()
    }

    /**
     * 两个开关是否都开着。
     *
     * 不得用本方法判断单条日志是否落地 —— 该判定属于 [shouldEmit]（WARN / ERROR 无视总开关）。
     * 这里保留「与」语义，是因为调用方询问的是另一个问题：`GoformSessionLog` 用它决定
     * 是否开启会话诊断文件（`log/core/goform/goform.log`，全量请求与响应，与级别无关），
     * 该文件的体积口径必须继续跟随总开关。
     */
    fun isLogEnabled(): Boolean = active

    /**
     * 清空缓冲并释放文件句柄（writerCache 清空后重新启用时会自动重建）。
     *
     * WARN / ERROR 不受总开关约束（[shouldEmit]），因此本方法的效果是「清除此刻已有的现场」，
     * 随后 WARN / ERROR 会重新建立缓冲与文件句柄，而不是「此后一条不写」。
     * 该行为保持不变：切换开关是用户的显式动作，而「从未操作过开关的默认部署」不会走到这里。
     */
    private fun stopWriting() {

        logBuffer.clear()
        repeatMap.clear()
        closeWriters()
    }

    /**
     * 设置调试模式（详细级别）：
     * - 开启：缓冲收录全部级别日志（DEBUG 及以上），DEBUG / INFO 同时落盘；
     * - 关闭：缓冲只保留 WARN / ERROR（历史 DEBUG / INFO 行被丢弃），DEBUG / INFO 不再写文件。
     *
     * 本开关必须同时约束落盘与缓冲：若只管内存缓冲，DEBUG / INFO 仍会落盘，
     * 叠加每请求一行的 HTTP 访问日志后，`app_*.log` 的增长将直接与请求量挂钩。
     * WARN / ERROR 不受本开关影响（关闭日志仍需保留错误痕迹），也不受
     * [setLogEnabled] 约束（见 [shouldEmit]），只有 [setCoreLogEnabled] 能拦截它们。
     *
     * 关闭时只丢弃低于 [BUFFER_MIN_LEVEL_QUIET] 的行，不清空整个缓冲：
     * 整体清空会连同错误链一起清除，使 `GET /api/debug-logs` 返回空且崩溃 dump 无现场。
     */
    fun setDebugMode(enabled: Boolean) {
        verboseEnabled = enabled
        bufferMinLevel = if (enabled) LogLevel.DEBUG else BUFFER_MIN_LEVEL_QUIET
        if (!enabled) {
            dropBufferedBelowMinLevel()
        }
    }

    /**
     * 丢弃缓冲中低于 [bufferMinLevel] 的历史行。
     *
     * 按级别标签做字符串判断，而不是把缓冲改为结构化元素：`/api/debug-logs` 的响应形状是
     * `Array<String>`，被 app 与 web 双端解析（见 `contract/Units.kt#DEBUG_LOGS_SHAPE`），
     * 改变缓冲元素类型会连带要求修改契约与两端解析。
     *
     * 必须使用原地 [MutableCollection.removeIf]，不得采用 `filter` → `clear()` → `addAll()`
     * 的三步重建：后者在 `clear()` 与 `addAll()` 之间存在空窗，期间其他线程经 [log] 写入的行
     * 会落入已清空的队列，随后被历史行覆盖，导致新行丢失或时序错乱。
     * 本方法在切换详细日志开关时执行，而同一份缓冲又是崩溃 dump 的现场来源
     * （见 [getBufferedLogs] / [dumpRecentForCrash]）。[ConcurrentLinkedQueue] 的 `removeIf`
     * 逐元素摘除，全程不存在「整个队列为空」的中间态。
     *
     * 判据：保留「行内含不低于 [bufferMinLevel] 的 `[LEVEL]` 标签」的条目，
     * 即删除「一个都不含」的条目（`keep.none {}` 等价于 `!keep.any {}`）。
     */
    private fun dropBufferedBelowMinLevel() {
        val keep = LogLevel.entries.filter { it.ordinal >= bufferMinLevel.ordinal }.map { "[${it.name}]" }
        logBuffer.removeIf { entry -> keep.none { it in entry } }
    }

    fun d(tag: String, message: String) = log(LogLevel.DEBUG, LogType.APP, tag, message)
    fun i(tag: String, message: String) = log(LogLevel.INFO, LogType.APP, tag, message)
    fun w(tag: String, message: String) = log(LogLevel.WARN, LogType.APP, tag, message)
    fun e(tag: String, message: String, throwable: Throwable? = null) {
        // 不该落地时提前返回：否则仍会做堆栈字符串化 + 指纹计算（这是 e() 里最贵的一步）。
        // 判定与 [log] 同源（[shouldEmit]）—— ERROR 只在 core 子开关关掉时才被拦住。
        if (!shouldEmit(LogLevel.ERROR)) return


        // 错误去重：相同 (message + 堆栈指纹) 在 5 分钟内只记 1 次（首次记，后续计数累计，窗口结束时追加"重复 N 次"汇总行）
        val fingerprint = errorFingerprint(message, throwable)
        val now = System.currentTimeMillis()
        val last = errorDedupMap[fingerprint]
        if (last != null && now - last < ERROR_DEDUP_WINDOW_MS) {
            // 窗口内重复 → 计数 +1
            errorDedupStats.merge(fingerprint, 1) { a, b -> a + b }
            return
        }
        // 窗口外或首次 → 记录
        val suppressed = errorDedupStats.remove(fingerprint) ?: 0
        if (suppressed > 0) {
            log(LogLevel.WARN, LogType.ERROR, tag, "[已抑制 $suppressed 次相同错误] $message")
        }
        // 该表没有自然过期机制，长期运行的进程会随「唯一错误」缓慢增长内存。
        // 达到上限时整体清空：代价只是下一轮少折叠几条，换取实现简单且无锁竞争。
        if (errorDedupMap.size >= ERROR_DEDUP_MAX) {
            errorDedupMap.clear()
            errorDedupStats.clear()
        }
        errorDedupMap[fingerprint] = now
        log(LogLevel.ERROR, LogType.ERROR, tag, message)
        throwable?.let {
            log(LogLevel.ERROR, LogType.ERROR, tag, it.stackTraceToString())
        }
    }

    /** 错误指纹：message + 异常堆栈前 200 字符（同类异常共享指纹） */
    private fun errorFingerprint(message: String, throwable: Throwable?): String {
        val stackHead = throwable?.stackTraceToString()?.take(200) ?: ""
        return "$message|$stackHead"
    }

    /**
     * 记录 AT 指令日志
     */
    fun at(direction: String, command: String, response: String = "") {
        val entry = "[$direction] $command${if (response.isNotEmpty()) " -> $response" else ""}"
        log(LogLevel.INFO, LogType.AT, "AT", entry)
    }

    /**
     * 网络日志：HTTP 服务端访问日志，以及 core 自身发出的 HTTP 客户端请求。
     *
     * tag 会被加上 [NET_TAG] 前缀，行内形如 `[NET/HTTP]` / `[NET/goform]`。App 与 web 的日志页
     * 据此把「网络日志」与「运行日志」分开筛选 —— 这是约定而非独立字段，因为
     * `/api/debug-logs` 的响应形状（`Array<String>`）被 app 与 web 双端解析，
     * 改为结构化对象会同时影响两端（见 `contract/Units.kt#DEBUG_LOGS_SHAPE`）。
     *
     * 级别沿用调用方给的值：请求失败用 WARN / ERROR，正常访问用 DEBUG
     * （关闭详细日志后 `bufferMinLevel` 为 WARN，常态下不会占满缓冲区）。
     */
    fun net(level: LogLevel, tag: String, message: String) =
        log(level, if (level == LogLevel.ERROR) LogType.ERROR else LogType.APP, "$NET_TAG/$tag", message)

    /** 网络日志 tag 前缀，与 app 侧 `com.ufi_axis.util.LogKind.NET_TAG` 必须一致。 */
    const val NET_TAG = "NET"


    /**
     * 获取内存缓冲区中的日志条目
     * @param level 按级别过滤 (DEBUG/INFO/WARN/ERROR)，null 表示不过滤
     * @param limit 返回最大条目数
     */
    fun getBufferedLogs(level: String? = null, limit: Int = 200): List<String> {
        // 按行首级别标签精确匹配。不得使用子串匹配（`level in entry`）：
        // `level=WARN` 会命中消息体含 "WARN" 的行，典型例子是访问日志自身
        // （`GET /api/debug-logs?level=WARN → 200`）。
        val wanted = level?.trim()?.uppercase(Locale.US)?.takeIf { it in LEVEL_NAMES }
        // 负数会使 takeLast 抛出 IllegalArgumentException（→ 500）；此处夹到 [0, 缓冲上限]。
        val safeLimit = limit.coerceIn(0, MAX_BUFFER_SIZE)

        val result = mutableListOf<String>()
        val iter = logBuffer.iterator()
        while (iter.hasNext()) {
            val entry = iter.next()
            if (wanted == null || entry.contains("[$wanted]")) {
                result.add(entry)
            }
        }
        return result.takeLast(safeLimit)
    }

    /** 日志级别的 wire 名（用于过滤参数校验）。 */
    private val LEVEL_NAMES: Set<String> = LogLevel.entries.map { it.name }.toSet()

    /**
     * 清空内存缓冲区
     */
    fun clearBuffer() { logBuffer.clear() }

    /**
     * 崩溃现场：内存缓冲末尾 [CRASH_DUMP_LINES] 条，供未捕获异常处理器写入 crash 文件。
     *
     * 崩溃记录不受日志开关约束，因此此处也不看 [active] / [verboseEnabled]：
     * 缓冲中有什么就 dump 什么。缺少该上下文时，崩溃文件只包含设备信息与堆栈，
     * 无法反映堆栈上游发生了什么。关闭详细日志时缓冲内容为 WARN / ERROR 链，
     * 见 [bufferMinLevel]。
     */
    fun dumpRecentForCrash(): List<String> = getBufferedLogs(limit = CRASH_DUMP_LINES)

    /**
     * 敏感字段脱敏：将日志中的 token / secret / password / bearer 等值替换为 ***，
     * 避免凭据泄漏到 logcat 与日志文件。支持 `"token":"..."`、`Bearer xxx`、`password=xxx` 等形态，
     * 保留关键字前缀（如 token），仅对值打码。AT 指令日志若不含上述关键字，格式不受影响。
     *
     * 正则与 `app/data` 的 `com.ufi_axis.util.DebugLog` 必须逐字一致。两条约束不可放宽：
     * - 值字符类不得排除引号：若按 `["']?[^"'\r\n\s,}]{1,128}` 匹配，JSON 形态 `"token":"abc"`
     *   只会匹配到 `token":`，输出 `"token***"abc"`，凭据原文进入 logcat 与日志文件。
     *   分隔层（引号 / `:` / `=` / 空格）需单独匹配，再由 `"[^"']*` 分支将引号内的值整体匹配。
     * - 值字符类必须排除 `&` / `;`：否则 `secret=def&token=ghi` 会被整段匹配为 `secret***`，
     *   造成过度打码并丢失整行日志。
     */
    private val SENSITIVE_PATTERN = Regex(
        "(?i)(token|secret|password|bearer|ticket|pairing_code|approval_code)\\b[ \\t]*[\"']?[ \\t]*:?[ \\t]*=?[ \\t]*" +
            "(?:\"[^\"']*|'[^ \"']*|[^\"'\r\n\\s,;&}]{1,128})"
    )


    /**
     * 对外公开：崩溃处理器等**直接落盘**的调用方（不经 AppLogger 的 e/w/d）必须先过一遍这里，
     * 否则凭据会绕过脱敏写进文件。正则与 `com.ufi_axis.util.DebugLog` 逐字一致。
     */
    fun desensitize(msg: String): String {
        if (msg.isEmpty()) return msg
        return SENSITIVE_PATTERN.replace(msg) { match -> "${match.groupValues[1]}***" }
    }

    /**
     * 号码打码：只保留末 4 位。
     *
     * 日志中出现手机号时必须经过此处 —— 号码本身是 PII，而排障只需要判断「是否为同一个号码」，
     * 末 4 位足以人工核对，也足以与截图比对。
     */
    fun maskPhone(raw: String): String {
        val value = raw.trim()
        if (value.isEmpty()) return ""
        if (value.length <= 4) return "***"
        return "***" + value.takeLast(4)
    }

    /**
     * 短信正文摘要：只记录长度与 SHA-256 前 8 位，不记录原文。
     *
     * 不得记录正文前缀：验证码通常位于开头，而日志可被任何已配对客户端读取
     * （`/api/debug-logs` 只校验 Bearer）。摘要足以判断「两次是否为同一条」，
     * 且不会把验证码本身写入文件。
     */
    fun bodyDigest(body: String): String {
        if (body.isEmpty()) return "len=0"
        val hash = java.security.MessageDigest.getInstance("SHA-256")
            .digest(body.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return "len=${body.length} sha=${hash.take(8)}"
    }

    private fun log(level: LogLevel, type: LogType, tag: String, message: String) {
        // 开关：不该落地就直接返回，连脱敏正则和时间格式化都不做（这两项在高频日志下才是真开销）。
        // WARN/ERROR 无视日志总开关，见 [shouldEmit]。
        if (!shouldEmit(level)) return


        // 重复行折叠：周期任务里逐 tick 重复的同一条消息只放行 1 条/窗口
        val gated = repeatGate(level, tag, message) ?: return

        val safeMessage = desensitize(gated)

        val time = LocalTime.now().format(timeFormat)
        val entry = "$time [${level.name}] [$tag] $safeMessage"

        // Logcat 输出。
        //
        // 此处必须捕获异常。裸 JVM 单测中 `android.util.Log` 是抛出
        // `RuntimeException("Method w ... not mocked")` 的桩，而 WARN / ERROR 不受日志总开关
        // 拦截，因此任何不使用 Robolectric 的单测只要经过一条 WARN，异常就会从被测业务代码中
        // 抛出（`NotificationDispatcherTest` 的 6 个用例即因栈顶为 `AppLogger.log` 而失败）。
        // 真机上 `Log.*` 不抛异常，该 catch 在生产中不会触发；即使触发，
        // 下方的文件与内存缓冲两条路径仍会收到该行，线索不会丢失。
        try {
            when (level) {
                LogLevel.DEBUG -> Log.d("$TAG/$tag", safeMessage)
                LogLevel.INFO -> Log.i("$TAG/$tag", safeMessage)
                LogLevel.WARN -> Log.w("$TAG/$tag", safeMessage)
                LogLevel.ERROR -> Log.e("$TAG/$tag", safeMessage)
            }
        } catch (_: Throwable) {
            // 有意留空：日志设施不得使调用方崩溃，也不得在此再次调用 Log。
        }


        // 文件持久化（复用 BufferedWriter）。
        //
        // INFO 的落盘闸门是 `verboseEnabled || coreLogEnabled`：`debug_mode` 与
        // `core_log_enabled` 是两个独立开关，且默认均为 false。若 INFO 只认 `verboseEnabled`，
        // 用户在设置中开启「core 日志」后 `verboseEnabled` 仍为 false，INFO 不会写入文件，
        // 且界面没有提示还需要开启另一个开关，表现为诊断日志缺失。
        //
        // 「core 日志」这一开关的语义即为「需要 core 的运行日志」，INFO 属于运行日志，
        // 不应再受 debug_mode 约束。DEBUG 仍然只认 debug_mode ——
        // 每个 HTTP 请求一行 DEBUG，放开它是日志目录增长的主要原因。
        //
        // [shouldEmit] 已先判定 coreLogEnabled，此处无需再判其为 false 的情况。
        val persist = level >= LogLevel.WARN ||
            (level == LogLevel.INFO && (verboseEnabled || coreLogEnabled)) ||
            (level == LogLevel.DEBUG && verboseEnabled)
        if (persist) {
            writeToFile(type, entry)
        }

        // 内存环形缓冲区：只看 bufferMinLevel，不与 verboseEnabled 相与
        // （相与会使 bufferMinLevel 失效，见该字段注释）。
        if (level.ordinal >= bufferMinLevel.ordinal) {
            logBuffer.add(entry)
            while (logBuffer.size > MAX_BUFFER_SIZE) logBuffer.poll()
        }
    }

    /**
     * 重复行折叠闸门。
     *
     * @return null = 应抑制；非 null = 应输出的消息（窗口翻页时带上被折叠次数的后缀）。
     *
     * 只按「完全相同的整条消息」折叠，因此带变量的日志（`cpu=12%`）不会被误折叠；
     * 造成日志体积增长的主要是完全相同的固定文本。
     */
    private fun repeatGate(level: LogLevel, tag: String, message: String): String? {
        val now = System.currentTimeMillis()
        val key = "${level.name}|$tag|$message"
        val existing = repeatMap[key]
        if (existing == null) {
            // 上限保护：tag/消息基数理论无界（如带 UUID 的消息），满了整体清空而不是 LRU，
            // 代价只是下一轮少折叠几条，换来实现简单且无锁竞争。
            if (repeatMap.size >= REPEAT_MAP_MAX) repeatMap.clear()
            repeatMap[key] = RepeatState(now, 0)
            return message
        }
        synchronized(existing) {
            if (now - existing.windowStart < REPEAT_WINDOW_MS) {
                existing.count++
                return null
            }
            val suppressed = existing.count
            existing.windowStart = now
            existing.count = 0
            return if (suppressed > 0) {
                "$message  <上一窗口内重复 $suppressed 次已折叠>"
            } else {
                message
            }
        }
    }

    private fun writeToFile(type: LogType, entry: String) {
        val root = logDir ?: return

        // 低磁盘保护：磁盘写满会连带导致 Room / goform / 更新失败，
        // 因此此处只停止写入文件，logcat 与内存缓冲照常工作。
        refreshDiskState(root)
        if (lowDisk) return

        val date = LocalDate.now().format(dateFormat)
        val key = "${type.prefix}_$date"

        synchronized(writerLock) {
            // 跨天：先关闭非当天的 writer（旧 writer 不回收会累积文件描述符，且目录被删除后
            // 仍会写入已删除的 inode），再清理一次过期文件。
            // 仅在 init() 调用一次的清理不足以覆盖长期运行的进程。
            if (lastWriteDate != date) {
                val previousDate = lastWriteDate
                // 先写 lastWriteDate 再补告警：告警本身会走 log() → writeToFile()，
                // 不先更新就会在这个分支里递归。
                lastWriteDate = date
                writerCache.keys.filterNot { it.endsWith("_$date") }.forEach { staleKey ->
                    writerCache.remove(staleKey)?.let { stale ->
                        runCatching { stale.close() }
                    }
                }
                cleanOldLogs()
                // 设备没有电池，重启校时前系统日期可能回拨。
                // 日志会写入「新」目录（可接受），但必须在 logcat 与日志中留一行说明，
                // 否则会出现「同一天两个目录」或「旧目录不再增长」的疑问。
                if (previousDate != null && date < previousDate) {
                    log(
                        LogLevel.WARN,
                        LogType.APP,
                        "LogClock",
                        "检测到系统日期回拨：$previousDate → $date，之后的日志写入新目录"
                    )
                }
            }
            try {
                val dayDir = File(root, date)
                if (!dayDir.exists() && !dayDir.mkdirs()) return
                val currentFile = File(dayDir, "${type.prefix}.log")
                val writer = writerCache.getOrPut(key) {
                    BufferedWriter(FileWriter(currentFile, true), 4096)
                }
                // ⚠️ 该 rotate 分支不会执行：MAX_LOG_FILE_BYTES 为 Long.MAX_VALUE，
                // `length() > Long.MAX_VALUE` 恒为 false，因此不会产生 .log.1。
                // 保留该分支是为了恢复上限时只需修改常量；不得据此认为单文件仍有 5MB 上限。
                if (currentFile.exists() && currentFile.length() > MAX_LOG_FILE_BYTES) {
                    try {
                        writer.flush()
                        writer.close()
                    } catch (e: Exception) {
                        Log.e("$TAG/Logger", "rotate close failed: ${e.message}")
                    }
                    writerCache.remove(key)
                    val rotated = File(dayDir, "${type.prefix}.log.1")
                    if (rotated.exists()) rotated.delete()
                    currentFile.renameTo(rotated)
                    val fresh = BufferedWriter(FileWriter(currentFile, false), 4096)
                    writerCache[key] = fresh
                    fresh.write(entry)
                    fresh.newLine()
                    fresh.flush()
                    // 轮转是目录增长的节点，在此校验一次总量预算
                    enforceTotalBudget()
                    return
                }
                writer.write(entry)
                writer.newLine()
                // 不逐条 flush：BufferedWriter 自身按 4KB 刷新，另由后台定时器按
                // [FLUSH_INTERVAL_MS] 刷新一次，把「每行一次 syscall」降为「每秒一次」。
                // 崩溃现场不依赖此处的落盘（`dumpRecentForCrash` 读取内存缓冲）。
                ensureFlushTimer()
            } catch (e: Exception) {
                // 写日志文件失败时移除缓存 writer，下次重新创建
                writerCache.remove(key)
                Log.e("$TAG/Logger", "Failed to write log file: ${e.message}")
            }
        }
    }

    /**
     * 低磁盘检查：按 [FREE_SPACE_CHECK_INTERVAL_MS] 限频。
     *
     * 读取失败时（裸 JVM 单测 / 没有 Context 的调用方）按「磁盘充足」处理 ——
     * 日志设施不能因一次 StatFs 失败就停止工作。
     */
    private fun refreshDiskState(root: File) {
        val now = System.currentTimeMillis()
        if (now - lastFreeCheckAt < FREE_SPACE_CHECK_INTERVAL_MS) return
        lastFreeCheckAt = now
        lowDisk = runCatching {
            android.os.StatFs(root.absolutePath).availableBytes < MIN_FREE_BYTES
        }.getOrDefault(false)
        degraded = lowDisk
        if (lowDisk) {
            try {
                Log.w("$TAG/Logger", "低磁盘：暂停写日志文件（可用 < ${MIN_FREE_BYTES / 1024 / 1024}MB）")
            } catch (_: Throwable) {
                // 单测里的 Log 桩会抛，忽略
            }
        }
    }

    /** 启动后台 flush 定时器。重复调用无副作用。 */
    private fun ensureFlushTimer() {
        if (flushTimer != null) return
        synchronized(writerLock) {
            if (flushTimer != null) return
            flushTimer = java.util.Timer("ufi-log-flush", true).apply {
                scheduleAtFixedRate(object : java.util.TimerTask() {
                    override fun run() {
                        synchronized(writerLock) {
                            writerCache.values.forEach { runCatching { it.flush() } }
                        }
                    }
                }, FLUSH_INTERVAL_MS, FLUSH_INTERVAL_MS)
            }
        }
    }

    /** 落盘是否因存储不足而降级（`GET /api/debug-logs/files` 与 UI 使用）。 */
    fun isDegraded(): Boolean = degraded

    /** 日志目录所在卷的可用字节数；读不到返回 -1。 */
    fun diskFreeBytes(): Long {
        val root = logDir ?: return -1L
        return runCatching { android.os.StatFs(root.absolutePath).availableBytes }.getOrDefault(-1L)
    }

    /**
     * 关闭所有文件写入器（进程退出前调用）
     */
    fun closeWriters() {
        synchronized(writerLock) {
            // 定时 flush 线程先停：否则关闭后它还会对已清空的 cache 做无意义遍历。
            flushTimer?.cancel()
            flushTimer = null
            writerCache.values.forEach {
                try {
                    it.close()
                } catch (e: Exception) {
                    Log.e("$TAG/Logger", "close failed: ${e.message}")
                }
            }
            writerCache.clear()
        }
    }

    /**
     * 清理过期日志（按日期目录保留最近 [MAX_LOG_FILES] 天），并约束日志根目录总量不超过
     * [MAX_LOG_DIR_BYTES]（超出时从最旧的文件开始删除）。
     *
     * 当前两项约束均未生效：[MAX_LOG_FILES] 为 3650 天（约 10 年，等价于不删除），
     * [MAX_LOG_DIR_BYTES] 为 `Long.MAX_VALUE`（[enforceTotalBudget] 在第一个 `if` 处直接返回，
     * 其删除循环不会执行）。本函数因此实际不删除任何文件，仅保留结构以便将来恢复策略。
     *
     * 作用范围：只清理 `log/core/` 下目录名可解析为日期的目录，
     * 因此 `crash/`、`update/`、`launcher/`、`goform/` 以及 `log/install|watchdog|keepalive/`
     * 不受本函数管理。
     */
    private fun cleanOldLogs() {
        val root = logDir ?: return
        val keepFrom = LocalDate.now().minusDays(MAX_LOG_FILES.toLong())
        root.listFiles()?.forEach { child ->
            if (!child.isDirectory) return@forEach
            // 目录名就是日期；解析不了的目录（人为放进来的）一律不动
            val date = runCatching { LocalDate.parse(child.name) }.getOrNull() ?: return@forEach
            if (date.isBefore(keepFrom)) {
                child.listFiles()?.forEach { it.delete() }
                child.delete()
            }
        }
        enforceTotalBudget()
    }

    /** 日志根目录下所有日志文件（展平日期子目录）。 */
    private fun allLogFiles(): List<File> {
        val root = logDir ?: return emptyList()
        return root.listFiles()
            ?.filter { it.isDirectory }
            ?.flatMap { day -> day.listFiles()?.filter { it.isFile }?.toList() ?: emptyList() }
            ?: emptyList()
    }

    /**
     * 日期目录之外的排障证据文件：`crash/crash_*.txt` 与 `update/update.log`。
     *
     * 刻意不包含 `goform/goform.log`：该会话日志含请求参数，只能经日志接口的诊断路径读取，
     * 不纳入「列目录 + 下载」这条权限更宽的通道。
     */
    private fun auxiliaryLogFiles(): List<File> {
        val root = logDir ?: return emptyList()
        val crashFiles = File(root, "crash")
            .listFiles { f -> f.isFile && f.name.startsWith("crash_") && f.name.endsWith(".txt") }
            ?.toList()
            .orEmpty()
        val updateFile = File(File(root, "update"), "update.log").takeIf { it.isFile }
        return crashFiles + listOfNotNull(updateFile)
    }

    /** `<日期目录>/<文件名>`，即对外暴露给 `/api/debug-logs/files/{name}` 的相对路径。 */
    private fun relativeName(f: File): String = "${f.parentFile?.name}/${f.name}"

    /** 总量超预算时，按修改时间从旧到新删除，直到回到预算内（当天正在写的文件最后才动）。 */
    private fun enforceTotalBudget() {
        val files = allLogFiles().sortedBy { it.lastModified() }
        var total = files.sumOf { it.length() }
        if (total <= MAX_LOG_DIR_BYTES) return
        // 关 writer + 删文件与落盘走同一把锁：否则可能在别的线程正写这个 writer 时把它关掉
        synchronized(writerLock) {
            for (f in files) {
                if (total <= MAX_LOG_DIR_BYTES) break
                val size = f.length()
                // 正在写的 writer 必须先关掉，否则删除后 writer 仍指向已删除的 inode，日志静默丢失
                val key = "${f.name.removeSuffix(".log.1").removeSuffix(".log")}_${f.parentFile?.name}"
                writerCache.remove(key)?.let {
                    try { it.close() } catch (_: Exception) {}
                }
                if (f.delete()) total -= size
            }
        }
        Log.w("$TAG/Logger", "log dir over budget, trimmed to ${total / 1024}KB")
    }

    /**
     * 列出日志根目录下的日志文件（新 → 旧）。给 `/api/debug-logs/files` 用。
     *
     * `name` 是相对路径（`<yyyy-MM-dd>/app.log`），因为日志已按日期分目录。
     */
    fun listLogFiles(): List<Map<String, Any>> = (allLogFiles() + auxiliaryLogFiles())
        .sortedByDescending { it.lastModified() }
        .map { mapOf("name" to relativeName(it), "size" to it.length(), "modified" to it.lastModified()) }

    /** 当前日志目录的绝对路径（随 `/api/debug-logs/files` 一并返回，便于在文件管理器中定位）。 */
    fun logDirPath(): String = logDir?.absolutePath ?: ""

    /**
     * 日志占用摘要（UI 展示的占用信息）。
     *
     * ## 统计口径（UI 侧不得重复计算）
     *
     * - `total_bytes`：全部落盘日志（按天运行日志 + `crash/` + `update/`），与
     *   `listLogFiles()` 的集合一致，否则列表与提示的数字会不一致；
     * - `today_bytes`：仅统计今天对应的日期目录；
     * - `days` / `oldest_day`：日期目录数量与最早一天。日志不按天删除，
     *   这两个值只用于展示时间跨度，不参与任何清理决策；
     * - `disk_free_bytes` / `degraded`：低磁盘保护状态（见 `refreshDiskState`）。
     *
     * 调用频率为打开日志页或手动刷新，不在热路径上；此处实时计算，不维护缓存。
     */
    fun usageSummary(): Map<String, Any> {
        val root = logDir
        val files = allLogFiles() + auxiliaryLogFiles()
        val today = LocalDate.now().format(dateFormat)
        val dayDirs = root?.listFiles()
            ?.filter { it.isDirectory && runCatching { LocalDate.parse(it.name) }.isSuccess }
            ?.sortedBy { it.name }
            .orEmpty()
        return mapOf(
            "total_bytes" to files.sumOf { it.length() },
            "today_bytes" to files.filter { it.parentFile?.name == today }.sumOf { it.length() },
            "days" to dayDirs.size,
            "oldest_day" to (dayDirs.firstOrNull()?.name ?: ""),
            "disk_free_bytes" to diskFreeBytes(),
            "degraded" to degraded,
        )
    }

    /**
     * 读取指定日志文件的**末尾** [maxBytes] 字节（默认 256KB）。
     *
     * 只读尾部而不是整个文件：单个日志文件可以有几十 MB，整读会直接 OOM，
     * 而排障需要的几乎总是最新的那一段。
     *
     * @param name 相对路径，必须匹配 [LOG_FILE_NAME]（防目录穿越）
     * @return 文件内容尾部；文件不存在或名字非法返回 null
     */
    fun readLogTail(name: String, maxBytes: Int = 256 * 1024): String? {
        val root = logDir ?: return null
        if (!LOG_FILE_NAME.matches(name)) return null
        val f = File(root, name)
        if (!f.isFile) return null
        return try {
            java.io.RandomAccessFile(f, "r").use { raf ->
                val len = raf.length()
                val from = (len - maxBytes).coerceAtLeast(0L)
                raf.seek(from)
                val buf = ByteArray((len - from).toInt())
                raf.readFully(buf)
                // UTF-8 解码遇到被截断的多字节序列会得到替换字符；先解码再丢弃首个不完整行，
                // 保证返回的文本从行首开始。
                val text = String(buf, Charsets.UTF_8)
                if (from > 0) {
                    val firstNewline = text.indexOf('\n')
                    // 整段不含换行 = 只截到了某条超长行的中间，返回空串而不是不完整的行
                    if (firstNewline < 0) "" else text.substring(firstNewline + 1)
                } else {
                    text
                }
            }
        } catch (e: Exception) {
            Log.e("$TAG/Logger", "readLogTail failed: ${e.message}")
            null
        }
    }

    /**
     * 删除 core 全部落盘日志（含正在写入的文件），返回释放的字节数。
     * 用于 `DELETE /api/debug-logs/files` —— 只作用于 core 这一侧；手机 APP 的日志文件
     * 由 app 自身的 `AppFileLogger.deleteAll()` 负责，两侧互不影响。
     */
    fun deleteAllLogFiles(): Long {
        val root = logDir ?: return 0L
        closeWriters()
        var freed = 0L
        // 与 listLogFiles() 保持同一集合：列出来的都能删掉，否则「删除全部」名不副实。
        (allLogFiles() + auxiliaryLogFiles()).forEach {
            val size = it.length()
            if (it.delete()) freed += size
        }
        // 清除已空的日期目录，避免在文件管理器中留下空目录
        root.listFiles()?.filter { it.isDirectory && it.listFiles().isNullOrEmpty() }?.forEach { it.delete() }
        return freed
    }
}
