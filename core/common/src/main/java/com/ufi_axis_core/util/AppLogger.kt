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
 * 统一日志管理器
 * 支持 Logcat 输出 + 文件持久化（按天滚动）
 *
 * 日志文件存储路径: /sdcard/Download/UFI-AXIS/log/core/<yyyy-MM-dd>/
 * - app.log   应用日志
 * - at.log    AT 指令日志
 * - error.log 错误日志
 * （单文件超 5MB 轮转为 `*.log.1`；外部存储不可用时回退到私有目录 `<dataDir>/logs`）
 *
 * 性能优化：文件写入复用 BufferedWriter，避免每行日志都 new FileWriter/PrintWriter
 *
 * 2026-08-11：错误日志限速 + 大小轮转
 * - 错误去重：相同 (message + 堆栈指纹) 在 5 分钟内只记 1 次（首次记，后续静默，但计数累计），
 *   解决 SystemCollector 等高频采集任务反复抛相同 ENOENT/ConnectException 致 error 日志爆炸（30MB/天）
 *
 * 2026-08-27：**日志体积治理**（用户反馈 core 日志目录体积不断变大）。三个原因都已修：
 * 1. **只有 error 日志有 5MB 上限**，`app_*.log` / `at_*.log` 完全无上限，只等 7 天清理 —— 现在
 *    全部类型统一走 [MAX_LOG_FILE_BYTES] 轮转。
 * 2. **文件落盘此前不受 debug_mode 约束**：`setDebugMode(false)` 只停内存缓冲，DEBUG/INFO 照写磁盘。
 *    加上新增的 HTTP 访问日志（每个请求一行 DEBUG）后，磁盘增长直接与请求量挂钩。
 *    现在 DEBUG/INFO **只在 debug_mode 开启时落盘**，WARN/ERROR 始终落盘。
 * 3. **`cleanOldLogs()` 只在 [init] 调用一次**：core 常驻数月不重启就永远不再清理。
 *    现在跨天写入时自动清一次。
 *
 * 开关分三层，从粗到细：
 * - [setLogEnabled]（`log_enabled`）—— 总开关，关掉后两端一条不记；
 * - [setCoreLogEnabled]（`core_log_enabled`）—— 只管 core 这一侧（app 侧对应 `DebugLog.appEnabled`）；
 * - [setDebugMode]（`debug_mode`）—— 详细级别，控制 DEBUG/INFO 是否进缓冲与磁盘。
 *
 * 三者的**唯一真源都是 core 的 [AppSettings]**（app 与 web 都经 `GET/PUT /api/config` 读写），
 * 进程启动时必须用 [restoreSwitches] 一次性恢复 —— 见该方法上的注释，
 * 「日志自动开启」的根因就是曾经漏恢复其中一个。
 */
object AppLogger {

    private const val TAG = "UFI-AXIS-Core"
    private const val MAX_LOG_FILES = 7  // 保留最近 7 天
    private const val MAX_BUFFER_SIZE = 500  // 内存缓冲区最大条目数
    private const val ERROR_DEDUP_WINDOW_MS = 5 * 60 * 1000L  // 错误去重窗口：5 分钟
    private const val MAX_LOG_FILE_BYTES = 5L * 1024 * 1024  // 单个日志文件上限 5MB（所有类型）
    private const val MAX_LOG_DIR_BYTES = 40L * 1024 * 1024  // 整个 logs 目录总量上限 40MB

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
     * 用户可直接查看的日志根目录（2026-08-28）。
     *
     * 之前写在 app 私有目录 `filesDir/ufiaxis/logs`（0700）：这台 F50 没有 root，
     * 用户拿 MT 管理器根本进不去，只能靠 API 读——这次直接换到公共 Download 下，
     * 与 `DownloadLog` 的 `error.log`/`launcher.log` 同一个父目录。
     *
     * 布局：`<root>/<yyyy-MM-dd>/{app,at,error}.log`（+ 轮转出的 `.1`）——
     * 先按日期分目录、再按类型分文件，比原来 `app_2026-08-28.log` 平铺一堆更好翻。
     */
    private val USER_LOG_ROOT = File("/sdcard/Download/UFI-AXIS/log/core")

    /**
     * 合法日志相对路径（防 `/api/debug-logs/files/{name}` 目录穿越）。
     * 形如 `2026-08-28/app.log` 或 `2026-08-28/error.log.1`。
     */
    private val LOG_FILE_NAME = Regex("""^\d{4}-\d{2}-\d{2}/(app|at|error)\.log(\.1)?$""")


    enum class LogLevel { DEBUG, INFO, WARN, ERROR }
    enum class LogType(val prefix: String) {
        APP("app"), AT("at"), ERROR("error")
    }

    private var logDir: File? = null
    private val logBuffer = ConcurrentLinkedQueue<String>()

    /**
     * 详细级别（`debug_mode`）的进程内镜像。
     *
     * **初值必须等于 [AppSettings.DEFAULT_DEBUG_MODE]（false），不能是 true。**
     * 2026-09-04 修「日志总是自动开启」：这里原来硬编码 `true`，而 `debug_mode` 的持久化默认值是
     * false，且只有 `ComponentFactory.build()` 会调 [setDebugMode] 回灌 —— 于是
     * 「进程启动 → build() 之前」这段窗口里详细日志恒为开；更糟的是 `autoStartOnBoot=false`
     * 或进程只被 BootReceiver / InstallReceiver 拉起时 build() 根本不跑，
     * **整个进程生命周期内详细日志都是开的**，DEBUG/INFO 全量落盘。
     * 用户关掉开关、core 重启后又自己写起来，就是从这来的。
     *
     * 规则：**进程内初值不得比持久化默认值更宽松**，真实值一律由
     * [restoreSwitches] 在 Application.onCreate 里恢复。
     */
    @Volatile
    private var verboseEnabled: Boolean = AppSettings.DEFAULT_DEBUG_MODE

    /**
     * 进内存缓冲的最低级别。
     *
     * 2026-09-04：**这里以前是死代码**。缓冲写入条件曾是
     * `if (bufferEnabled && level.ordinal >= bufferMinLevel.ordinal)` —— 而 `bufferEnabled`
     * 就是 `debug_mode`，关掉详细日志后第一个条件恒 false，`bufferMinLevel = ERROR`
     * 这一支永远走不到。后果：**关掉详细日志 → 缓冲永远空 → `GET /api/debug-logs` 返回
     * 空数组 → web 的调试日志卡片恒显示「暂无日志」**，而这恰恰是排障最需要日志的状态。
     *
     * 现在缓冲只由本字段闸门（与 [verboseEnabled] 解耦）：
     * - 详细日志开 → [LogLevel.DEBUG]，全量进缓冲；
     * - 详细日志关 → [BUFFER_MIN_LEVEL_QUIET]（WARN），错误链**始终**留在内存里，
     *   既能给 web 卡片看，也是崩溃时 dump 现场的唯一上下文来源。
     */
    @Volatile
    private var bufferMinLevel: LogLevel =
        if (AppSettings.DEFAULT_DEBUG_MODE) LogLevel.DEBUG else BUFFER_MIN_LEVEL_QUIET

    private val dateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.getDefault())
    private val timeFormat = DateTimeFormatter.ofPattern("HH:mm:ss.SSS", Locale.getDefault())

    // 文件写入器缓存 (key = "prefix_date"，自动按天切换)
    private val writerCache = ConcurrentHashMap<String, BufferedWriter>()

    // 最近一次落盘所属日期，用于跨天触发 cleanOldLogs()（core 常驻不重启也能清理过期文件）
    @Volatile
    private var lastWriteDate: String? = null


    // 错误去重缓存：key = message + 堆栈指纹前 200 字符；value = 最后记录时间戳
    // 5 分钟内同错误只记 1 次（防止高频重复异常致日志爆炸）
    private val errorDedupMap = ConcurrentHashMap<String, Long>()
    // 错误去重统计：key 同上；value = 累计被抑制的次数（首次满窗后写一行"重复 N 次"统计）
    private val errorDedupStats = ConcurrentHashMap<String, Int>()

    // ── 重复行折叠（2026-08-28）──
    // 上面的 errorDedup 只保护 ERROR。实测 core 的体积几乎全部来自**周期任务里逐 tick
    // 重复的同一条 DEBUG/INFO**：`Alert xxx level unchanged, skip`（信号 3s 一条）、
    // `checkConnectivity state unchanged: ...`（3s）、`Flushed buffers: cpu=1, mem=1, ...`（10s）、
    // `Cache TTL updated to 3000ms`（30s）等，稳态下每天 25-34MB；再叠加一次协程取消
    // 死循环就写出了单日 304MB。
    // 因此在所有级别上加一层通用闸门：同一 (级别, tag, 完整消息) 在窗口内只放行 1 条，
    // 窗口结束后补一行 "重复 N 次已折叠"，信息不丢但体积上限恒定。
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
     * 日志总开关（2026-08-27 新增，真源 `AppSettings.logEnabled`，默认 true）。
     *
     * false = **两端都完全不记录任何日志**：不进内存缓冲、不写 logcat、不写文件、不镜像到 Download。
     * 这是为省电/省 IO 提供的硬开关。
     */
    @Volatile
    private var loggingEnabled = AppSettings.DEFAULT_LOG_ENABLED

    /**
     * core 端子开关（真源 `AppSettings.coreLogEnabled`，默认 true）。
     *
     * 与 [loggingEnabled] 是「与」关系：总开关是前置条件，这一个只关 core 这一侧，
     * app 侧对应的是 `com.ufi_axis.util.DebugLog.appEnabled`（真源 `AppSettings.appLogEnabled`）。
     * 这样用户可以「只关后端日志、保留手机端日志」——core 常驻写盘，是体积增长的那一侧。
     */
    @Volatile
    private var coreLogEnabled = AppSettings.DEFAULT_CORE_LOG_ENABLED

    /** core 实际是否记录日志：总开关与 core 子开关都开才算。 */
    private val active: Boolean get() = loggingEnabled && coreLogEnabled

    /**
     * 一次性恢复三层开关（唯一真源 [AppSettings]）。
     *
     * 为什么要有这个聚合入口：三个开关分三个 setter，任何**新增的进程入口**都必须记得调
     * 全部三个。2026-09-04 的「日志自动开启」缺陷正是漏了第三个 ——
     * `UfiAxisCoreApplication.onCreate` 只恢复了 `log_enabled` / `core_log_enabled`，
     * `debug_mode` 只在 `ComponentFactory.build()` 里恢复，于是不走 build 的进程启动路径
     * （autoStartOnBoot=false、BootReceiver / InstallReceiver 拉起）详细日志恒为开。
     * 现在所有启动路径统一调这一个方法，漏一个的可能性从"记不记得"变成"编译不过"。
     *
     * 语义是「持久化值优先」：调用方传的必须是 [AppSettings] 的 getter 结果，
     * getter 只在**从未设置过**时才回退到 `DEFAULT_*`，所以升级 / 重启不会覆盖用户的选择。
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

    fun isLogEnabled(): Boolean = active

    /** 关闭后清空缓冲并释放文件句柄（writerCache 清空后重新开启会自动重建）。 */
    private fun stopWriting() {
        logBuffer.clear()
        repeatMap.clear()
        closeWriters()
    }

    /**
     * 设置调试模式（详细级别）：
     * - 开启: 缓冲区缓存全部级别日志 (DEBUG+)，且 DEBUG/INFO 也落盘
     * - 关闭: 缓冲区只留 WARN/ERROR（历史 DEBUG/INFO 行被丢弃），**DEBUG/INFO 不再写文件**
     *
     * 2026-08-27：以前这里只管内存缓冲，DEBUG/INFO 照样落盘 —— 叠加每请求一行的 HTTP
     * 访问日志后，`app_*.log` 的增长直接与请求量挂钩，这是 core 日志目录变大的主因之一。
     * WARN/ERROR 不受本开关影响（关掉日志也要留错误痕迹），但受 [setLogEnabled] /
     * [setCoreLogEnabled] 约束。
     *
     * 2026-09-04：关闭时不再 `logBuffer.clear()`，改为**只丢弃低于 [BUFFER_MIN_LEVEL_QUIET]
     * 的行**。整个清空会连同错误链一起抹掉，于是「关掉详细日志」等于「/api/debug-logs 空」
     * 且「崩溃 dump 无现场」——正是要修的那个缺陷。
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
     * 按级别标签做字符串判断而不是给缓冲换成结构化元素：`/api/debug-logs` 的响应形状是
     * `Array<String>`，被 app 与 web 双端解析（见 `contract/Units.kt#DEBUG_LOGS_SHAPE`），
     * 缓冲元素类型改了就得连带改契约与两端解析。
     *
     * 2026-09-05（P3）：改为**原地** [MutableCollection.removeIf]，不再 `filter` → `clear()`
     * → `addAll()` 三步重建。旧写法在 `clear()` 与 `addAll()` 之间留了一个空窗：这期间别的
     * 线程从 [log] 写进来的行会落进已被清空的队列，随后被 `addAll` 的历史行"追加在后面"
     * —— 结果是新行丢失或时序错乱。而本方法恰好在用户拨动详细日志开关时执行，
     * 同一份缓冲又是崩溃 dump 的现场来源（见 [getBufferedLogs] / [dumpRecentForCrash]）。
     * [ConcurrentLinkedQueue] 的 `removeIf` 逐元素摘除，全程没有"整个队列为空"的中间态。
     *
     * 判据语义与旧实现**逐字等价**：保留「行内含有 ≥ [bufferMinLevel] 的 `[LEVEL]` 标签」的条目，
     * 即删除「一个都不含」的条目（`keep.none {}` = `!keep.any {}`）。
     */
    private fun dropBufferedBelowMinLevel() {
        val keep = LogLevel.entries.filter { it.ordinal >= bufferMinLevel.ordinal }.map { "[${it.name}]" }
        logBuffer.removeIf { entry -> keep.none { it in entry } }
    }

    fun d(tag: String, message: String) = log(LogLevel.DEBUG, LogType.APP, tag, message)
    fun i(tag: String, message: String) = log(LogLevel.INFO, LogType.APP, tag, message)
    fun w(tag: String, message: String) = log(LogLevel.WARN, LogType.APP, tag, message)
    fun e(tag: String, message: String, throwable: Throwable? = null) {
        // 开关关闭时提前返回：否则仍会做堆栈字符串化 + 指纹计算（这是 e() 里最贵的一步）
        if (!active) return

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
     * 网络日志（2026-08-27 新增）：HTTP 服务端访问日志、以及 core 自己发出的 HTTP 客户端请求。
     *
     * tag 会被加上 [NET_TAG] 前缀，行内形如 `[NET/HTTP]` / `[NET/goform]`。App 与 web 的日志页
     * 据此把「网络日志」和「运行日志」分开筛选 —— 这是约定而非独立字段，因为
     * `/api/debug-logs` 的响应形状（`Array<String>`）被 app + web 双端解析，
     * 改成结构化对象会同时打断两端（见 `contract/Units.kt#DEBUG_LOGS_SHAPE`）。
     *
     * 级别沿用调用方给的值：请求失败用 WARN/ERROR，正常访问用 DEBUG（关闭调试模式后
     * `bufferMinLevel = ERROR`，所以常态下不会把缓冲区刷满）。
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
        // 用 iterator 遍历避免 toList() 全量拷贝
        val result = mutableListOf<String>()
        val iter = logBuffer.iterator()
        while (iter.hasNext()) {
            val entry = iter.next()
            if (level == null || level in entry) {
                result.add(entry)
            }
        }
        return result.takeLast(limit)
    }

    /**
     * 清空内存缓冲区
     */
    fun clearBuffer() { logBuffer.clear() }

    /**
     * 崩溃现场：内存缓冲末尾 [CRASH_DUMP_LINES] 条，供未捕获异常处理器拼进 crash 文件。
     *
     * 2026-09-04：这是「release 默认关日志、但闪退必须留证据」的落地点。此前崩溃只写
     * 「设备信息 + 堆栈」，堆栈上游发生了什么一概没有；而**崩溃是不受日志开关约束的**，
     * 所以这里也不看 [active]/[verboseEnabled] —— 缓冲里有什么就 dump 什么
     * （关掉详细日志时缓冲里就是 WARN/ERROR 链，见 [bufferMinLevel]）。
     */
    fun dumpRecentForCrash(): List<String> = getBufferedLogs(limit = CRASH_DUMP_LINES)

    /**
     * 敏感字段脱敏：将日志中的 token / secret / password / bearer 等值替换为 ***，
     * 避免凭据泄漏到 logcat 与日志文件。支持 "token":"..."、Bearer xxx、password=xxx 等形态，
     * 同时保留关键字前缀（如 token）仅对值打码。AT 指令日志若不含上述关键字则格式不受影响。
     *
     * T16（2026-08-26）修复两个缺陷，正则与 `app/data` 的 `com.ufi_axis.util.DebugLog` 保持逐字一致：
     * - 旧写法 `["']?[^"'\r\n\s,}]{1,128}` 的**值字符类排除了引号**，JSON 形态 `"token":"abc"`
     *   只吃掉 `token":`，实测输出 `"token***"abc"` —— **凭据原文进 logcat / 日志文件**。
     *   修法：分隔层（引号 / `:` / `=` / 空格）单独匹配，再由 `"[^"']*` 分支把引号内的值整体吞掉。
     * - 值字符类未排除 `&` / `;`，`secret=def&token=ghi` 被整段吞成 `secret***`（过度打码、日志整行丢失）。
     */
    private val SENSITIVE_PATTERN = Regex(
        "(?i)(token|secret|password|bearer)\\b[ \\t]*[\"']?[ \\t]*:?[ \\t]*=?[ \\t]*" +
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

    private fun log(level: LogLevel, type: LogType, tag: String, message: String) {
        // 开关：关闭后直接返回，连脱敏正则和时间格式化都不做（这两项在高频日志下才是真开销）
        if (!active) return

        // 重复行折叠：周期任务里逐 tick 重复的同一条消息只放行 1 条/窗口
        val gated = repeatGate(level, tag, message) ?: return

        val safeMessage = desensitize(gated)

        val time = LocalTime.now().format(timeFormat)
        val entry = "$time [${level.name}] [$tag] $safeMessage"

        // Logcat 输出
        when (level) {
            LogLevel.DEBUG -> Log.d("$TAG/$tag", safeMessage)
            LogLevel.INFO -> Log.i("$TAG/$tag", safeMessage)
            LogLevel.WARN -> Log.w("$TAG/$tag", safeMessage)
            LogLevel.ERROR -> Log.e("$TAG/$tag", safeMessage)
        }

        // 文件持久化（复用 BufferedWriter）。
        // DEBUG/INFO 只在 debug_mode 开启时落盘 —— 否则每个 HTTP 请求一行 DEBUG 访问日志
        // 会让 app_*.log 的增长与请求量成正比（这是 core 日志目录变大的主因）。
        if (level >= LogLevel.WARN || verboseEnabled) {
            writeToFile(type, entry)
        }

        // 内存环形缓冲区：只看 bufferMinLevel，**不再与 verboseEnabled 相与**
        // （相与就是 bufferMinLevel 变成死代码的原因，见该字段注释）。
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
     * 只按「完全相同的整条消息」折叠，所以带变量的日志（`cpu=12%`）不会被误折叠 ——
     * 而真正压垮磁盘的恰恰是那些一字不差的固定文本。
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
        val date = LocalDate.now().format(dateFormat)
        val key = "${type.prefix}_$date"

        // 跨天：清一次过期文件。原来 cleanOldLogs() 只在 init() 调一次，core 常驻数月不重启
        // 就永远不再清理，7 天保留策略形同虚设。
        if (lastWriteDate != date) {
            lastWriteDate = date
            cleanOldLogs()
        }

        try {
            val dayDir = File(root, date)
            if (!dayDir.exists() && !dayDir.mkdirs()) return
            val currentFile = File(dayDir, "${type.prefix}.log")
            val writer = writerCache.getOrPut(key) {
                BufferedWriter(FileWriter(currentFile, true), 4096)
            }
            // 写入前检查：单文件超过 5MB 时 rotate 到 .log.1（覆盖最旧），新建当前文件继续。
            // 2026-08-27：此前只有 error 类型有上限，app.log / at.log 可以无限长。
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
                // 轮转是目录变大的自然节点，顺手校一次总量预算
                enforceTotalBudget()
                return
            }
            writer.write(entry)
            writer.newLine()
            writer.flush()
        } catch (e: Exception) {
            // 写日志文件失败时移除缓存 writer，下次重新创建
            writerCache.remove(key)
            Log.e("$TAG/Logger", "Failed to write log file: ${e.message}")
        }
    }


    /**
     * 关闭所有文件写入器（进程退出前调用）
     */
    fun closeWriters() {
        writerCache.values.forEach {
            try {
                it.close()
            } catch (e: Exception) {
                Log.e("$TAG/Logger", "close failed: ${e.message}")
            }
        }
        writerCache.clear()
    }

    /**
     * 清理过期日志（按日期目录保留最近 [MAX_LOG_FILES] 天），并强制整个日志根目录总量不超过
     * [MAX_LOG_DIR_BYTES] —— 超了就从最旧的文件开始删。
     *
     * 2026-08-28 加总量上限的原因：只有"每天一个文件 + 单文件 5MB 轮转"的话，
     * 3 种类型 × 7 天 × (当前 + .1) 理论上仍能堆到 200MB+。对一个随身 UFI 来说这不可接受。
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

    /** `<日期目录>/<文件名>`，即对外暴露给 `/api/debug-logs/files/{name}` 的相对路径。 */
    private fun relativeName(f: File): String = "${f.parentFile?.name}/${f.name}"

    /** 总量超预算时，按修改时间从旧到新删除，直到回到预算内（当天正在写的文件最后才动）。 */
    private fun enforceTotalBudget() {
        val files = allLogFiles().sortedBy { it.lastModified() }
        var total = files.sumOf { it.length() }
        if (total <= MAX_LOG_DIR_BYTES) return
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
        Log.w("$TAG/Logger", "log dir over budget, trimmed to ${total / 1024}KB")
    }

    /**
     * 列出日志根目录下的日志文件（新 → 旧）。给 `/api/debug-logs/files` 用。
     *
     * `name` 是相对路径（`2026-08-28/app.log`），因为日志已按日期分目录。
     */
    fun listLogFiles(): List<Map<String, Any>> = allLogFiles()
        .sortedByDescending { it.lastModified() }
        .map { mapOf("name" to relativeName(it), "size" to it.length(), "modified" to it.lastModified()) }

    /** 当前日志目录的绝对路径（给 `/api/debug-logs/files` 一并返回，用户照着去文件管理器找）。 */
    fun logDirPath(): String = logDir?.absolutePath ?: ""

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
                val text = String(buf, Charsets.UTF_8)
                // 从中间截断时首行可能是半行，丢掉它避免展示乱码
                if (from > 0) text.substringAfter('\n', text) else text
            }
        } catch (e: Exception) {
            Log.e("$TAG/Logger", "readLogTail failed: ${e.message}")
            null
        }
    }

    /**
     * 删除 core 全部落盘日志（含正在写的），返回释放的字节数。
     * 用于 `DELETE /api/debug-logs/files` —— **只动 core 这一侧**，手机 APP 的日志文件
     * 由 app 自己的 `AppFileLogger.deleteAll()` 负责，两侧互不影响。
     */
    fun deleteAllLogFiles(): Long {
        val root = logDir ?: return 0L
        closeWriters()
        var freed = 0L
        allLogFiles().forEach {
            val size = it.length()
            if (it.delete()) freed += size
        }
        // 清掉空掉的日期目录，别在文件管理器里留一堆空文件夹
        root.listFiles()?.filter { it.isDirectory && it.listFiles().isNullOrEmpty() }?.forEach { it.delete() }
        return freed
    }
}
