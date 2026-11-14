package com.ufi_axis.util

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 日志导出（2026-10-30 M1）。
 *
 * ## 为什么单独抽一个纯函数文件
 *
 * 导出内容要可测：文件头里**每一条**信息（版本、来源、筛选、条数、时间范围）都是排障时
 * 的判据，写错字段不会崩，只会让人拿着错误上下文排查。抽成不依赖 Android 的纯函数后，
 * `LogExportTest` 可以固定时钟与时区逐字断言；UI 只负责 SAF 与写文件。
 *
 * ## 范围（用户 2026-10-30 的决定）
 *
 * 默认导出**记录**（当前选中 / 当前筛选结果），不导出落盘文件。落盘文件导出是后续阶段
 * （见 `dev-docs/日志导出改造计划-2026-10-30.md` 的 M2）。
 *
 * ## 已知限制：行内没有日期
 *
 * [LogEntry] 只有 `HH:mm:ss.SSS`，没有 epoch；对 core 行硬加“导出日期”会在跨零点时
 * 伪造时间。因此日期只出现在文件头，并明确写出这条限制 —— 宁可少一点信息，
 * 也不制造看似精确的错误数据。行内日期留给 M2（`LogEntry.epochMs`）。
 */
data class LogExportMeta(
    /** 导出时刻（epoch millis）。 */
    val exportedAt: Long,
    /** App 版本名，如 `1.2.3`；取不到传 `unknown`。 */
    val appVersionName: String,
    /** 记录来自哪一侧（手机 APP / 设备 core）。 */
    val source: LogSource,
    /** 设备地址；手机侧留空（空则不写这一行）。 */
    val deviceBaseUrl: String = "",
    /** 设备型号；null/空则省略该行（取不到不能阻塞导出）。 */
    val deviceModel: String? = null,
    /** 类型筛选；null = 全部。 */
    val kind: LogKind? = null,
    /** 级别筛选；null = 全部。 */
    val level: LogLevel? = null,
    /** 关键字筛选；空 = 无。 */
    val query: String = "",
    /** 本次导出的记录（**调用方负责快照**，导出过程中列表变化不影响结果）。 */
    val entries: List<LogEntry>,
    /** true = 多选导出（文件名用 `selected-N`），false = 当前筛选全部。 */
    val isSelection: Boolean = false,
)

/** 文件头与日志行之间的分隔线（40 个 U+2500）。 */
private const val SEPARATOR = "────────────────────────────────────────"

private const val HEADER_TIME_PATTERN = "yyyy-MM-dd HH:mm:ss XXX"
private const val FILE_STAMP_PATTERN = "yyyyMMdd-HHmmss"

/**
 * 拼出导出文本（UTF-8、`\n`、末尾带换行）。
 *
 * 空 [LogExportMeta.entries] **也会返回合法头部**：UI 在调用前就该拦掉空导出
 *（“当前筛选没有日志”），这里保持纯函数语义，方便测试与将来复用。
 */
fun buildLogExportText(meta: LogExportMeta, zone: ZoneId = ZoneId.systemDefault()): String = buildString {
    val headerTime = DateTimeFormatter.ofPattern(HEADER_TIME_PATTERN, Locale.US).withZone(zone)
    appendLine("# UFI-AXIS 日志导出")
    appendLine("导出时间：${headerTime.format(Instant.ofEpochMilli(meta.exportedAt))}")
    appendLine("App 版本：${meta.appVersionName.ifBlank { "unknown" }}")
    appendLine("来源：${if (meta.source == LogSource.APP) "手机 APP" else "设备 core"}")
    if (meta.deviceBaseUrl.isNotBlank()) appendLine("设备地址：${meta.deviceBaseUrl}")
    meta.deviceModel?.trim()?.takeIf { it.isNotEmpty() }?.let { appendLine("设备型号：$it") }
    appendLine(
        "筛选：类型=${meta.kind?.label ?: "全部"}；" +
            "级别=${meta.level?.name ?: "全部"}；" +
            "搜索=${meta.query.ifBlank { "无" }}"
    )
    appendLine("条数：${meta.entries.size}")
    val times = meta.entries.mapNotNull { it.time.takeIf(String::isNotBlank) }
    appendLine("时间范围：${if (times.isEmpty()) "—" else "${times.first()} ~ ${times.last()}"}")
    appendLine("行格式：HH:mm:ss.SSS [LEVEL] [tag] message")
    appendLine("时间说明：行首为时刻（不含日期）；跨天请以落盘日志的日期目录为准。")
    appendLine("隐私提示：日志可能包含设备标识、IP、号码等；已按脱敏规则处理。")
    appendLine(SEPARATOR)
    meta.entries.forEach { appendLine(it.format()) }
}

/**
 * SAF 默认文件名：`ufi-axis-logs_<app|core>_<scope>_<yyyyMMdd-HHmmss>.txt`。
 *
 * - 只用 ASCII（用户决定）：`-` / `_` / `.`，避免各 ROM 文件管理器对中文与空格的处理差异；
 * - `scope`：多选 → `selected-<N>`；否则 `<kind>-<level>`（未筛选为 `all`）；
 * - 时间到秒：同一秒内重复导出由 SAF 自身去重，或用户改名；不会覆盖已有文件。
 */
fun defaultLogExportFileName(meta: LogExportMeta, zone: ZoneId = ZoneId.systemDefault()): String {
    val source = if (meta.source == LogSource.APP) "app" else "core"
    val scope = if (meta.isSelection) {
        "selected-${meta.entries.size}"
    } else {
        "${kindSlug(meta.kind)}-${levelSlug(meta.level)}"
    }
    val stamp = DateTimeFormatter.ofPattern(FILE_STAMP_PATTERN, Locale.US)
        .withZone(zone)
        .format(Instant.ofEpochMilli(meta.exportedAt))
    return "ufi-axis-logs_${sanitize(source)}_${sanitize(scope)}_$stamp.txt"
}

/**
 * 单个落盘文件的导出文本（2026-10-30 M2）。
 *
 * [truncated] 为 true 时头部明确写「只包含尾部」——core 接口与查看器都只给尾部，
 * 不说明的话，用户会以为文件就这么短。
 */
fun buildLogFileExportText(
    fileName: String,
    exportedAt: Long,
    appVersionName: String,
    sourceLabel: String,
    content: String,
    truncated: Boolean,
    zone: ZoneId = ZoneId.systemDefault(),
): String = buildString {
    val headerTime = DateTimeFormatter.ofPattern(HEADER_TIME_PATTERN, Locale.US).withZone(zone)
    appendLine("# UFI-AXIS 日志文件导出")
    appendLine("导出时间：${headerTime.format(Instant.ofEpochMilli(exportedAt))}")
    appendLine("App 版本：${appVersionName.ifBlank { "unknown" }}")
    appendLine("来源：$sourceLabel")
    appendLine("文件：$fileName")
    appendLine(
        if (truncated) "内容范围：仅文件尾部（单次读取上限）" else "内容范围：完整文件"
    )
    appendLine("隐私提示：日志可能包含设备标识、IP、号码等；请确认接收方可信。")
    appendLine(SEPARATOR)
    append(content)
}

/**
 * 把多个落盘文件打成 ZIP（2026-10-30 M2）。
 *
 * 包内结构：
 * ```
 * README.txt          导出来源/时间/App 版本/文件数/清单
 * logs/<相对路径>     各文件内容（调用方已按上限截取尾部）
 * ```
 *
 * 为什么用 ZIP 而不是把内容拼成一个 txt：文件名本身就是日期/类型信息
 *（`2026-10-30/app.log`、`crash/crash_123.txt`），拼起来会丢掉这层结构。
 */
fun buildLogBundleZip(
    sourceLabel: String,
    exportedAt: Long,
    appVersionName: String,
    files: List<Pair<String, ByteArray>>,
    zone: ZoneId = ZoneId.systemDefault(),
): ByteArray {
    val headerTime = DateTimeFormatter.ofPattern(HEADER_TIME_PATTERN, Locale.US).withZone(zone)
    val readme = buildString {
        appendLine("# UFI-AXIS 日志打包")
        appendLine("导出时间：${headerTime.format(Instant.ofEpochMilli(exportedAt))}")
        appendLine("App 版本：${appVersionName.ifBlank { "unknown" }}")
        appendLine("来源：$sourceLabel")
        appendLine("文件数：${files.size}")
        appendLine("说明：每个文件为日志尾部（单文件受读取上限约束）；文件名即日期/类型。")
        appendLine("隐私提示：日志可能包含设备标识、IP、号码等；请确认接收方可信。")
        appendLine(SEPARATOR)
        files.forEachIndexed { index, (name, bytes) ->
            appendLine("${index + 1}. $name  (${bytes.size} B)")
        }
    }.toByteArray(Charsets.UTF_8)

    val out = java.io.ByteArrayOutputStream()
    java.util.zip.ZipOutputStream(out).use { zip ->
        fun put(name: String, bytes: ByteArray) {
            zip.putNextEntry(java.util.zip.ZipEntry(name))
            zip.write(bytes)
            zip.closeEntry()
        }
        put("README.txt", readme)
        files.forEach { (name, bytes) -> put("logs/" + name.trimStart('/'), bytes) }
    }
    return out.toByteArray()
}

/** ZIP 默认文件名：`ufi-axis-logs_<app|core>_bundle_<yyyyMMdd-HHmmss>.zip`。 */
fun defaultLogBundleFileName(
    source: LogSource,
    exportedAt: Long,
    zone: ZoneId = ZoneId.systemDefault(),
): String {
    val stamp = DateTimeFormatter.ofPattern(FILE_STAMP_PATTERN, Locale.US)
        .withZone(zone)
        .format(Instant.ofEpochMilli(exportedAt))
    val side = if (source == LogSource.APP) "app" else "core"
    return "ufi-axis-logs_${sanitize(side)}_bundle_$stamp.zip"
}

/**
 * 崩溃 dump 的导出文本（2026-10-30 M1.5）。
 *
 * 与日志导出同一套头部风格；正文是**原样的 dump**，不做任何裁剪 —— 崩溃现场的价值
 * 就在于“原样”。`崩溃时间` 用文件修改时间而不是文件名里的时间：文件名可能被重命名过。
 */
fun buildCrashExportText(
    fileName: String,
    fileModifiedAt: Long,
    exportedAt: Long,
    appVersionName: String,
    content: String,
    zone: ZoneId = ZoneId.systemDefault(),
): String = buildString {
    val headerTime = DateTimeFormatter.ofPattern(HEADER_TIME_PATTERN, Locale.US).withZone(zone)
    appendLine("# UFI-AXIS 崩溃日志导出")
    appendLine("导出时间：${headerTime.format(Instant.ofEpochMilli(exportedAt))}")
    appendLine("App 版本：${appVersionName.ifBlank { "unknown" }}")
    appendLine("来源：手机 APP 崩溃 dump")
    appendLine("文件：$fileName")
    if (fileModifiedAt > 0) {
        appendLine("崩溃写入时间：${headerTime.format(Instant.ofEpochMilli(fileModifiedAt))}")
    }
    appendLine("隐私提示：崩溃 dump 可能包含设备标识、IP、界面文本；请确认接收方可信。")
    appendLine(SEPARATOR)
    append(content)
}

private fun kindSlug(kind: LogKind?): String = when (kind) {
    LogKind.NETWORK -> "net"
    LogKind.RUNTIME -> "runtime"
    null -> "all"
}

private fun levelSlug(level: LogLevel?): String = level?.name?.lowercase(Locale.US) ?: "all"

/** 只保留 `[a-z0-9._-]`；其它字符一律折成 `-`（文件名要跨 ROM 稳定）。 */
private fun sanitize(segment: String): String =
    segment.lowercase(Locale.US)
        .replace(Regex("[^a-z0-9._-]"), "-")
        .trim('-')
        .ifBlank { "all" }
