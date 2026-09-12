package com.ufi_axis.installer.core

import android.content.Context
import java.io.File
import java.io.IOException

/**
 * 内置 APK 的发现与落地。
 *
 * APK 由 UFI-AXIS 仓库的 release 工作流注入到 `assets/ufi-axis-core/`，
 * 随安装器一起分发。assets 里的文件不是真实 File，必须先拷到 cacheDir 才能推送。
 */
object AssetApkProvider {

    /** assets 下的子目录，release 工作流把 core APK 放这里 */
    const val ASSET_DIR = "ufi-axis-core"

    /** 目标包名固定为 core */
    const val CORE_PACKAGE = "com.ufi_axis_core"

    data class AssetApk(
        val name: String,
        val assetPath: String,
        val sizeBytes: Long
    )

    /**
     * 列出 assets 中所有 APK。
     *
     * 注意：aapt 会丢弃空目录，因此源码里保留了 README.txt 占位。
     * 这里必须容错，assets 为空时返回空列表而不是抛异常。
     */
    fun listApks(context: Context): List<AssetApk> {
        val names = try {
            context.assets.list(ASSET_DIR) ?: emptyArray()
        } catch (e: IOException) {
            return emptyList()
        }

        return names
            .filter { it.endsWith(".apk", ignoreCase = true) }
            .map { name ->
                val path = "$ASSET_DIR/$name"
                AssetApk(name, path, assetSize(context, path))
            }
            .sortedBy { it.name }
    }

    /**
     * 从候选列表中挑出 core APK。
     *
     * 优先级（与需求一致，仅在 assets 里多放文件时才会用到后两条）：
     * 1. 只有一个 → 就是它
     * 2. 文件名含 `core`（忽略大小写）
     * 3. 文件名含 `ufi` 或 `axis`
     * 4. 仍不唯一 → 返回 null，由界面让用户选择
     */
    fun pickCore(apks: List<AssetApk>): AssetApk? = when {
        apks.isEmpty() -> null
        apks.size == 1 -> apks[0]
        else -> {
            apks.filter { it.name.contains("core", ignoreCase = true) }
                .let { it.singleOrNull() ?: pickByKeywords(it.ifEmpty { apks }) }
        }
    }

    private fun pickByKeywords(apks: List<AssetApk>): AssetApk? {
        val byUfi = apks.filter {
            it.name.contains("ufi", ignoreCase = true) || it.name.contains("axis", ignoreCase = true)
        }
        return byUfi.singleOrNull()
    }

    /**
     * 把 assets 中的 APK 复制到 cacheDir，得到可读的真实 [File]。
     *
     * 每次任务开始前应调用 [clearCache]，避免残留旧版本 APK 被误装。
     */
    fun materializeToCache(context: Context, apk: AssetApk): File {
        val dir = File(context.cacheDir, ASSET_DIR)
        if (!dir.exists() && !dir.mkdirs()) {
            throw IOException("无法创建缓存目录：${dir.absolutePath}")
        }
        val target = File(dir, apk.name)
        context.assets.open(apk.assetPath).use { input ->
            target.outputStream().use { output ->
                input.copyTo(output, DEFAULT_BUFFER_SIZE)
            }
        }
        if (target.length() <= 0L) {
            throw IOException("APK 复制结果为空：${apk.name}，assets 可能未正确打包")
        }
        return target
    }

    /** 清空缓存目录 */
    fun clearCache(context: Context) {
        val dir = File(context.cacheDir, ASSET_DIR)
        if (dir.isDirectory) dir.listFiles()?.forEach { it.delete() }
    }

    /** 读取 assets 条目大小；无法获取时返回 -1 */
    private fun assetSize(context: Context, path: String): Long = try {
        context.assets.openFd(path).use { it.length }
    } catch (_: Exception) {
        // 未配置 noCompress 时 openFd 会失败，退化为流方式统计
        try {
            context.assets.open(path).use { input ->
                val buf = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    total += n
                }
                total
            }
        } catch (_: Exception) {
            -1L
        }
    }
}
