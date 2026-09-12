package com.ufi_axis.ui.screens

import com.ufi_axis.util.UfiLogPaths
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** 待导出文件：相对文件名（CSV 用 UFI_Monitor_{apiKey}_{start}_{end}.csv）+ 完整内容（已含 BOM） */
data class ExportFile(
    val fileName: String,
    val content: String
)

/**
 * 监控 CSV 导出落盘工具（P5e）：主页「全部导出」与详情页「单类导出」共用。
 *
 * 目录：`Download/UFI-AXIS/export/monitor/{yyyy-MM-dd}/`，来自 [UfiLogPaths.monitorExportDir]。
 * - 2026-09-11 之前是 `Downloads/UFI/Monitor/{date}/` —— 顶层用的是**早已废弃的旧品牌名
 *   `UFI`**，于是同一个应用在 Download 下占两个根目录，用户找导出物要猜是哪一个。
 * - 旧文件**刻意不搬迁**：那是监控业务数据不是日志，留在老位置不影响任何功能，
 *   而搬动用户数据的风险比收益大。
 * - 按当天日期隔离子目录，不需要 `_2/_3` 重名后缀；CSV 文件名含 start/end 时间戳
 *   （yyyyMMdd-HHmm），天然唯一。
 * - `zip=false`：每文件散写 `File(dir, fileName).writeText(content)`（content 已含 BOM，UTF-8 with BOM）；
 * - `zip=true`：写单个 `UFI_Monitor_{yyyyMMdd}.zip`，zip 内部条目名用各 csv 文件名
 *   （含 apiKey 后缀天然不冲突，单个 zip 内 8 个条目）。
 *
 * 纯工具（File + Zip），不依赖 Compose，可单元测试。
 * 本函数为**挂起函数（suspend fun）**，内部执行同步 IO，调用方需在协程体内调用
 * （如 `scope.launch(Dispatchers.IO)`，天然兼容）；
 * [onResult] 为 **suspend 回调**，回调内可调用 suspend（如 `withContext(Dispatchers.Main)` 回主线程 Toast），
 * 调用方在 Dispatchers.IO 协程内调用本函数。
 */
object MonitorExport {

    /**
     * 导出 CSV 文件（散文件或 zip 打包）。
     *
     * @param files 待导出文件列表（相对文件名 + 内容；CSV 内容已含 BOM）
     * @param zip true=打包单 zip（内部条目为各 csv 文件名）；false=散文件
     * @param onResult suspend 结果回调：成功返回**完整相对路径**（如
     *   `UFI-AXIS/export/monitor/2026-09-11/`），失败返回原因（调用方负责 Toast）；
     *   回调可在协程体内调用 suspend（如 withContext(Main) 回主线程 Toast）
     */
    suspend fun exportCsvFiles(
        files: List<ExportFile>,
        zip: Boolean,
        onResult: suspend (String) -> Unit
    ) {
        try {
            val now = Date()
            val date = UfiLogPaths.today(now)
            val zipDate = SimpleDateFormat("yyyyMMdd", Locale.US).format(now)
            // 导出物不回退应用私有目录（理由见 UfiLogPaths）：建不出目录就是失败，明确报出来
            val dir = UfiLogPaths.monitorExportDir(date)
            if (dir == null) {
                onResult("导出失败: 无法创建目录 ${UfiLogPaths.monitorExportRelative(date)}")
                return
            }

            if (zip) {
                val zipFile = File(dir, "UFI_Monitor_$zipDate.zip")
                ZipOutputStream(zipFile.outputStream().buffered()).use { zos ->
                    files.forEach { file ->
                        zos.putNextEntry(ZipEntry(file.fileName))
                        zos.write(file.content.toByteArray(Charsets.UTF_8))
                        zos.closeEntry()
                    }
                }
                onResult("已导出到 ${UfiLogPaths.displayPath(zipFile.absolutePath)}")
            } else {
                files.forEach { file ->
                    File(dir, file.fileName).writeText(file.content)
                }
                onResult("已导出到 ${UfiLogPaths.displayPath(dir.absolutePath)}/")
            }
        } catch (e: Exception) {
            onResult("导出失败: ${e.message}")
        }
    }
}
