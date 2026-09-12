package com.ufi_axis.util

import android.content.Context
import android.os.Environment
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * APP 侧「日志 / 导出物落在哪」的**唯一一处**约定（2026-09-11 新增）。
 *
 * ## 为什么要有这个文件
 * 这套目录约定在仓库里早就是成文的（`core/src/main/assets/shell/ufi_update.sh` 头部：
 * 「所有日志统一归到 Download/UFI-AXIS/log 下，按组件分子目录」），
 * 但 app 侧把它**手抄了三份**：[AppFileLogger] 抄一份根目录、[ApiErrorLogger] 抄一份
 * `UFI-AXIS` + `log` 拼接、`CrashHandler` 再抄一份。三份各自演化的结果就是：
 * 崩溃 dump 落在 `UFI-AXIS/<日期>/`（污染品牌根目录，正是 core
 * `ComponentFactory.migrateLegacyLogsOnce()` 要清的那种形态），
 * api-error 躺在 `log/` 根，导出物直接扔进 `Download` 根。
 * 现在三处都从本文件取路径，改约定只改这里。
 *
 * ## 完整布局
 * ```
 * /sdcard/Download/UFI-AXIS/
 * ├── log/
 * │   ├── core/                                     ← 设备端 core 的地盘，app 一律不碰
 * │   ├── watchdog/                                 ← ufi_update.sh 写
 * │   ├── keepalive/                                ← ufi_keepalive.sh 写
 * │   ├── install/                                  ← 安装脚本写
 * │   ├── _archive/                                 ← core 的旧日志归档
 * │   ├── app/
 * │   │   ├── <yyyy-MM-dd>/{runtime,net,error}.log(.1)   [AppFileLogger]
 * │   │   ├── crash/<yyyy-MM-dd>/crash_HH-mm-ss.log      CrashHandler
 * │   │   └── api-error/<yyyy-MM-dd>/api_error*.log      [ApiErrorLogger]
 * │   └── export/                                   ← 用户主动导出的日志文本
 * │       ├── app/<yyyy-MM-dd>/{kind}_{level}_{HHmmss}.txt
 * │       ├── core/<yyyy-MM-dd>/{kind}_{level}_{HHmmss}.txt
 * │       └── app/crash/<yyyy-MM-dd>/<原崩溃文件名>
 * └── export/
 *     └── monitor/<yyyy-MM-dd>/                     ← 监控 CSV / zip（不是日志，所以不在 log/ 下）
 * ```
 *
 * ## ⚠️ 改根目录时必须一起改的其它三处
 * 本文件只是**app 侧**的真源，同一套路径另有三份独立副本，它们不 import 本文件：
 * 1. **core 侧**：`core/common/src/main/java/com/ufi_axis_core/util/LogPaths.kt`
 *    （core 自己的路径真源，含 `ComponentFactory.migrateLegacyLogsOnce()` 用的 `/log/_archive`）；
 * 2. `core/src/main/assets/shell/ufi_update.sh`（`LOG_DIR=…/log/watchdog`）；
 * 3. `core/src/main/assets/shell/ufi_keepalive.sh`（`LOG_DIR=…/log/keepalive`）。
 *
 * app 与 core 各持一份是刻意的（两个进程、两个模块，app 不该依赖 core 的实现类），
 * 但代价就是**根目录只能同步改**：动 [BRAND_DIR] / [LOG_DIR] 而不同步改那三处，
 * 结果不是编译失败而是**日志静静地分家** —— 一半在新根、一半在旧根，谁都不报错。
 * 写下这段的唯一目的就是让下一个人在改之前看见它。

 *
 * ## 回退
 * 外部存储不可写（未授权 / 被拔卡 / 厂商 ROM 限制）时，**只有落盘日志**回退到
 * `filesDir/logs/<log 以下的相对路径>` —— 沿用 [AppFileLogger] 原有的那一套，不另造第二套。
 * **导出物刻意不回退**：导出的意义就是让用户拿文件管理器去拿，落进应用私有目录等于没导出。
 * 所以 [exportDir] / [monitorExportDir] 失败就是失败，调用方必须报错给用户看。
 */
object UfiLogPaths {

    private const val TAG = "UfiLogPaths"

    // ── 目录名（唯一定义处）─────────────────────────────────────────────────────
    /** Download 下的品牌根目录名。旧版本曾用 `UFI`（见 `MonitorExport` 的历史路径），已废弃。 */
    const val BRAND_DIR = "UFI-AXIS"

    /** 日志根：品牌根下的 `log`，按组件分子目录。 */
    const val LOG_DIR = "log"

    /** app 自己的日志子目录名，也是导出物里代表「手机 APP」这一侧的 source 名。 */
    const val APP_DIR = "app"

    /** 设备端 core 的子目录名，也是导出物里代表「设备 core」这一侧的 source 名。 */
    const val CORE_DIR = "core"

    /** 崩溃 dump 子目录名（`log/app/crash/`）。 */
    const val CRASH_DIR = "crash"

    /** 通信报错子目录名（`log/app/api-error/`）。 */
    const val API_ERROR_DIR = "api-error"

    /** 导出目录名。日志导出在 `log/export/`，监控 CSV 在品牌根的 `export/`。 */
    const val EXPORT_DIR = "export"

    /** 监控 CSV 导出子目录名（`export/monitor/`）。 */
    const val MONITOR_DIR = "monitor"

    /** 导出文件名里「这一维没有筛选」的占位。 */
    const val UNFILTERED = "all"

    /** 外部存储不可写时的私有回退根（`filesDir/logs`），与 log/ 以下的相对路径拼接。 */
    private const val FALLBACK_DIR = "logs"

    private const val DATE_PATTERN = "yyyy-MM-dd"

    /** 日期目录名：`2026-09-11`。既是新目录的命名，也是识别旧崩溃目录的判据。 */
    private val DATE_DIR_NAME = Regex("""^\d{4}-\d{2}-\d{2}$""")

    /** 崩溃 dump 文件名：新旧形态都以 `crash_` 开头、`.log` 结尾。 */
    private const val CRASH_FILE_PREFIX = "crash_"

    /** api-error 滚动文件名（旧版本直接躺在 `log/` 根）。 */
    const val API_ERROR_ROLLING_FILE = "api_error.log"

    /** api-error 个体文件名前缀。 */
    const val API_ERROR_FILE_PREFIX = "api_error_"

    private const val LOG_SUFFIX = ".log"

    // ── 纯拼接：不碰文件系统，可直接单测 ────────────────────────────────────────

    /** 当天的日期目录名。 */
    fun today(now: Date = Date()): String =
        SimpleDateFormat(DATE_PATTERN, Locale.US).format(now)

    /** 以 [BRAND_DIR] 开头的相对路径（相对公共 Download 目录），分隔符固定 `/`。 */
    fun relative(vararg segments: String): String =
        (listOf(BRAND_DIR) + segments).joinToString("/")

    /** app 运行时日志根：`UFI-AXIS/log/app`。日期子目录由 [AppFileLogger] 自己建。 */
    fun appLogRelative(): String = relative(LOG_DIR, APP_DIR)

    /** 崩溃 dump 目录：`UFI-AXIS/log/app/crash/<date>`。 */
    fun appCrashRelative(date: String): String = relative(LOG_DIR, APP_DIR, CRASH_DIR, date)

    /** 崩溃 dump 根（跨日期，列举/清空用）：`UFI-AXIS/log/app/crash`。 */
    fun appCrashRootRelative(): String = relative(LOG_DIR, APP_DIR, CRASH_DIR)

    /** 通信报错目录：`UFI-AXIS/log/app/api-error/<date>`。 */
    fun apiErrorRelative(date: String): String = relative(LOG_DIR, APP_DIR, API_ERROR_DIR, date)

    /**
     * 日志导出目录：`UFI-AXIS/log/export/<source>[/<sub>]/<date>`。
     *
     * @param source 日志来自哪一侧，[APP_DIR] 或 [CORE_DIR]。
     * @param sub 可选子类，目前只有崩溃 dump 用（[CRASH_DIR]）—— 崩溃是单独的文件形态，
     *   和逐行导出的日志文本混在同一个日期目录里会让用户分不清哪个是哪个。
     */
    fun exportRelative(source: String, date: String, sub: String? = null): String =
        if (sub == null) relative(LOG_DIR, EXPORT_DIR, source, date)
        else relative(LOG_DIR, EXPORT_DIR, source, sub, date)

    /**
     * 监控 CSV 导出目录：`UFI-AXIS/export/monitor/<date>`。
     *
     * 刻意**不在 `log/` 下** —— 监控 CSV 是业务数据而不是日志，放进日志树会被日志的
     * 保留天数/体积预算那套清理规则误伤。
     */
    fun monitorExportRelative(date: String): String = relative(EXPORT_DIR, MONITOR_DIR, date)

    /** 日志导出文件名：`{类型}_{级别}_{HHmmss}.txt`，未筛选的那一维传 [UNFILTERED]。 */
    fun exportFileName(kind: String, level: String, stamp: String): String =
        "${kind}_${level}_$stamp.txt"

    /**
     * 把绝对路径裁成用户看得懂的相对路径（从 [BRAND_DIR] 起算）。
     *
     * toast 里报「已导出到 Downloads/xxx」是没用的 —— 用户拿着文件管理器不知道往哪翻。
     * 落在私有回退目录（路径里没有品牌目录）时原样返回绝对路径，至少是可 adb pull 的信息。
     */
    fun displayPath(absolutePath: String): String {
        val normalized = absolutePath.replace('\\', '/')
        val marker = "/$BRAND_DIR/"
        val idx = normalized.indexOf(marker)
        return if (idx >= 0) normalized.substring(idx + 1) else normalized
    }

    // ── 一次性搬迁的判据（纯谓词，可直接单测）──────────────────────────────────

    /**
     * 是否是日期目录名。
     *
     * 这也是崩溃 dump 搬迁**避开 core 地盘**的关键：旧崩溃躺在品牌根的日期目录里，
     * 而品牌根下还有 `log`（core / watchdog / keepalive / _archive 全在里面）。
     * 只认 `yyyy-MM-dd` 形态的目录，`log` 天然不匹配，不需要再写一条排除名单。
     */
    fun isDateDirName(name: String): Boolean = DATE_DIR_NAME.matches(name)

    /** 是否是崩溃 dump 文件（新旧命名共用此判据）。 */
    fun isCrashFileName(name: String): Boolean =
        name.startsWith(CRASH_FILE_PREFIX) && name.endsWith(LOG_SUFFIX)

    /**
     * 是否是**旧版 api-error 文件**（改造前直接躺在 `log/` 根）。
     *
     * 搬迁时只按这个名字挑文件，并且只看 `log/` 的直接子**文件** ——
     * `log/core/`、`log/app/`、`log/_archive/` 这些目录一律不进搬迁范围。
     */
    fun isLegacyApiErrorFileName(name: String): Boolean =
        name == API_ERROR_ROLLING_FILE ||
            (name.startsWith(API_ERROR_FILE_PREFIX) && name.endsWith(LOG_SUFFIX))

    // ── 文件系统 ────────────────────────────────────────────────────────────────

    /** 公共 Download 目录；取不到（极端 ROM）返回 null。 */
    fun downloadsDir(): File? = runCatching {
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
    }.getOrNull()

    /** 外部存储上的目标目录，**不创建**。用于展示与搬迁判定。 */
    fun externalDir(relative: String): File? = downloadsDir()?.let { File(it, relative) }

    /**
     * 准备一个**落盘日志**目录：优先外部存储，不可写时回退私有目录。
     *
     * @param relative 由上面那组 `*Relative()` 生成，必须在 `UFI-AXIS/log/` 之下。
     * @return 可写目录；连回退都准备不出来（外部不可用且 [context] 为 null）时返回 null，
     *   调用方跳过文件写盘即可，绝不能因此影响业务。
     */
    fun ensureLogDir(context: Context?, relative: String): File? {
        externalDir(relative)?.let { dir ->
            if (dir.exists() || dir.mkdirs()) return dir
        }
        if (context == null) return null
        Log.w(TAG, "external log dir unavailable, fallback to private dir: $relative")
        return privateFallbackDir(context, relative).takeIf { it.exists() || it.mkdirs() }
    }

    /**
     * 私有回退目录：`filesDir/logs/<log 以下的相对路径>`。
     *
     * 与 [AppFileLogger] 改造前的 `filesDir/logs` 同源，只是把 `app/`、`app/crash/` 这层
     * 结构也带了进来 —— 否则崩溃 dump 和 api-error 会和运行日志的日期目录混在一层。
     */
    fun privateFallbackDir(context: Context, relative: String): File {
        val underLog = relative.removePrefix("$BRAND_DIR/$LOG_DIR/")
        return File(File(context.filesDir, FALLBACK_DIR), underLog)
    }

    /** app 运行时日志根（含回退）。 */
    fun appLogDir(context: Context?): File? = ensureLogDir(context, appLogRelative())

    /** 崩溃 dump 当日目录（含回退）。 */
    fun appCrashDir(context: Context?, date: String = today()): File? =
        ensureLogDir(context, appCrashRelative(date))

    /** 崩溃 dump 根目录（跨日期，列举/清空用）。外部与私有两处都要看，所以返回两个候选。 */
    fun appCrashRoots(context: Context?): List<File> = buildList {
        externalDir(appCrashRootRelative())?.let { add(it) }
        if (context != null) add(privateFallbackDir(context, appCrashRootRelative()))
    }

    /** 通信报错当日目录（含回退）。 */
    fun apiErrorDir(context: Context?, date: String = today()): File? =
        ensureLogDir(context, apiErrorRelative(date))

    /**
     * 日志导出目录。**不回退私有目录**（理由见类 KDoc），准备失败返回 null，
     * 调用方必须给出明确的失败提示而不是静默。
     */
    fun exportDir(source: String, date: String = today(), sub: String? = null): File? =
        externalDir(exportRelative(source, date, sub))?.takeIf { it.exists() || it.mkdirs() }

    /** 监控 CSV 导出目录。同样不回退私有目录。 */
    fun monitorExportDir(date: String = today()): File? =
        externalDir(monitorExportRelative(date))?.takeIf { it.exists() || it.mkdirs() }

    /** 品牌根目录（**不创建**）。只有一次性搬迁需要它 —— 旧崩溃 dump 就散在这一层。 */
    fun legacyBrandRoot(): File? = downloadsDir()?.let { File(it, BRAND_DIR) }

    /** 日志根目录（**不创建**）。只有一次性搬迁需要它 —— 旧 api-error 就躺在这一层。 */
    fun legacyLogRoot(): File? = downloadsDir()?.let { File(it, relative(LOG_DIR)) }
}
