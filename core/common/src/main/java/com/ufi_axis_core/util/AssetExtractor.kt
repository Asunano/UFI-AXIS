package com.ufi_axis_core.util

import android.content.Context
import java.io.File

/**
 * APK assets 提取器
 * 将 assets/shell/ 下的二进制和脚本提取到 filesDir/shell/，设置可执行权限
 */
object AssetExtractor {

    private const val TAG = "AssetExtractor"

    /** 解压完成标记，内容是构建指纹（见 [buildStamp]） */
    private const val MARKER_NAME = ".extracted"

    /**
     * 提取 assets 子目录中的所有文件到 filesDir
     * @param context Application context
     * @param assetSubDir assets 中的子目录名（默认 "shell"）
     * @return 提取目标目录
     */
    fun extractAll(context: Context, assetSubDir: String = "shell"): File {
        val destDir = File(context.filesDir, assetSubDir)
        if (!destDir.exists()) destDir.mkdirs()

        // 构建指纹一致就跳过：assets/shell 有 ~13MB 二进制，原来每次 build()（即每次服务启动/重启）
        // 都要整份重写一遍，白占几秒启动时间，还要在每个常驻二进制上做 rename 替换。
        // 指纹带 lastUpdateTime，所以覆盖安装/重装后必然重新解压。
        val stamp = buildStamp(context)
        val marker = File(destDir, MARKER_NAME)
        if (stamp != null && marker.exists() &&
            runCatching { marker.readText().trim() }.getOrNull() == stamp
        ) {
            AppLogger.d(TAG, "assets already extracted for this build, skip")
            return destDir
        }

        val files = context.assets.list(assetSubDir) ?: return destDir
        var failed = false
        for (name in files) {
            val destFile = File(destDir, name)
            // 写入临时文件再 rename 覆盖目标：避免 ETXTBSY (Text file busy)。
            // 若目标文件正在被执行（如运行时覆盖 adb/socat 等常驻二进制），
            // 直接 outputStream() 覆盖会抛 ETXTBSY；rename 仅替换目录项、
            // 运行中的进程仍持有旧 inode，故可成功。临时文件放同目录保证同文件系统 rename 原子。
            val tmpFile = File(destDir, "$name.tmp")
            try {
                context.assets.open("$assetSubDir/$name").use { input ->
                    tmpFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                tmpFile.setExecutable(true, false)
                tmpFile.setReadable(true, false)
                if (!tmpFile.renameTo(destFile)) {
                    // rename 失败（极少数跨设备场景）：退化为直接拷贝
                    tmpFile.copyTo(destFile, overwrite = true)
                    destFile.setExecutable(true, false)
                    destFile.setReadable(true, false)
                    tmpFile.delete()
                }
                AppLogger.d(TAG, "Extracted: $name (${destFile.length()} bytes)")
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed to extract $name", e)
                runCatching { tmpFile.delete() }
                failed = true
            }
        }
        // 只有全部成功才落指纹：任一文件失败时不写，下次启动会重试（否则会永久跳过一份坏文件）
        if (!failed && stamp != null) runCatching { marker.writeText(stamp) }
        return destDir
    }

    /** 构建指纹：版本名 + versionCode + APK 安装时间，任一变化都要重新解压。 */
    private fun buildStamp(context: Context): String? = runCatching {
        val pi = context.packageManager.getPackageInfo(context.packageName, 0)
        "${pi.versionName}:${pi.longVersionCode}:${pi.lastUpdateTime}"
    }.getOrNull()

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
