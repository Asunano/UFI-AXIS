package com.ufi_axis.ui.screens

import android.content.Context
import android.os.Environment
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
 * 目录结构：`Downloads/UFI/Monitor/{yyyy-MM-dd}/` —— 按当天日期隔离子目录，
 * 不再需要 `_2/_3` 重名后缀；CSV 文件名含 start/end 时间戳（yyyyMMdd-HHmm），天然唯一。
 * - `zip=false`：每文件散写 `File(dir, fileName).writeText(content)`（content 已含 BOM，UTF-8 with BOM）；
 * - `zip=true`：写单个 `UFI_Monitor_{yyyyMMdd}.zip`，zip 内部条目名用各 csv 文件名
 *   （含 apiKey 后缀天然不冲突，单个 zip 内 8 个条目）。
 *
 * 纯 Android 工具（Context + Environment + File + Zip），不依赖 Compose，可单元测试。
 * 本函数为**挂起函数（suspend fun）**，内部执行同步 IO，调用方需在协程体内调用
 * （如 `scope.launch(Dispatchers.IO)`，天然兼容）；
 * [onResult] 为 **suspend 回调**，回调内可调用 suspend（如 `withContext(Dispatchers.Main)` 回主线程 Toast），
 * 调用方在 Dispatchers.IO 协程内调用本函数。
 */
object MonitorExport {

    /** Downloads 下的专属根目录名：Downloads/UFI/Monitor/{yyyy-MM-dd}/ */
    const val EXPORT_DIR_NAME = "UFI"

    /**
     * 导出 CSV 文件（散文件或 zip 打包）。
     *
     * @param context 上下文（定位公共 Downloads 目录；内部取 applicationContext）
     * @param files 待导出文件列表（相对文件名 + 内容；CSV 内容已含 BOM）
     * @param zip true=打包单 zip（内部条目为各 csv 文件名）；false=散文件
     * @param onResult suspend 结果回调：成功返回导出目录/zip 路径，失败返回原因（调用方负责 Toast）；
     *   回调可在协程体内调用 suspend（如 withContext(Main) 回主线程 Toast）；调用方在 Dispatchers.IO 协程内调用本函数
     */
    suspend fun exportCsvFiles(
        context: Context,
        files: List<ExportFile>,
        zip: Boolean,
        onResult: suspend (String) -> Unit
    ) {
        try {
            val now = Date()
            val dirName = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(now)
            val zipDate = SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(now)
            val downloadsDir =
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val dir = File(downloadsDir, "$EXPORT_DIR_NAME/Monitor/$dirName")
            if (!dir.exists() && !dir.mkdirs()) {
                onResult("导出失败: 无法创建目录 $dir")
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
                onResult("已导出到 Downloads/UFI/Monitor/$dirName/UFI_Monitor_$zipDate.zip")
            } else {
                files.forEach { file ->
                    File(dir, file.fileName).writeText(file.content)
                }
                onResult("已导出到 Downloads/UFI/Monitor/$dirName/")
            }
        } catch (e: Exception) {
            onResult("导出失败: ${e.message}")
        }
    }
}
