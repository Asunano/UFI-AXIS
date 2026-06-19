package com.ufi_axis_core.util

import android.content.Context
import java.io.File

/**
 * APK assets 提取器
 * 将 assets/shell/ 下的二进制和脚本提取到 filesDir/shell/，设置可执行权限
 */
object AssetExtractor {

    private const val TAG = "AssetExtractor"

    /**
     * 提取 assets 子目录中的所有文件到 filesDir
     * @param context Application context
     * @param assetSubDir assets 中的子目录名（默认 "shell"）
     * @return 提取目标目录
     */
    fun extractAll(context: Context, assetSubDir: String = "shell"): File {
        val destDir = File(context.filesDir, assetSubDir)
        if (!destDir.exists()) destDir.mkdirs()

        val files = context.assets.list(assetSubDir) ?: return destDir
        for (name in files) {
            val destFile = File(destDir, name)
            try {
                context.assets.open("$assetSubDir/$name").use { input ->
                    destFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                destFile.setExecutable(true, false)
                destFile.setReadable(true, false)
                AppLogger.d(TAG, "Extracted: $name (${destFile.length()} bytes)")
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed to extract $name", e)
            }
        }
        return destDir
    }

    /**
     * 获取已提取文件的绝对路径
     */
    fun getPath(context: Context, fileName: String, subDir: String = "shell"): String {
        return File(context.filesDir, "$subDir/$fileName").absolutePath
    }

    /**
     * 检查文件是否已提取
     */
    fun isExtracted(context: Context, fileName: String, subDir: String = "shell"): Boolean {
        val f = File(context.filesDir, "$subDir/$fileName")
        return f.exists() && f.length() > 0
    }

    /**
     * 确保 uploads 目录存在（用于 boot image 等文件下载）
     */
    fun ensureUploadsDir(context: Context): File {
        val dir = File(context.filesDir, "uploads")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }
}
