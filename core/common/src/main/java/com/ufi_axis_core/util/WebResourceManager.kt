package com.ufi_axis_core.util

import android.content.Context
import kotlinx.serialization.json.*
import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * Web 前端资源管理器
 *
 * 实现 override 目录 + APK assets 回退机制：
 * - 有 override 时从文件系统读取 web 文件（支持独立更新）
 * - 无 override 时回退到 APK 内置 assets（默认行为）
 *
 * 目录结构（context.filesDir 下）：
 * - web/          活跃的 override 目录
 * - web_staging/  安装过程中的临时目录（启动时自动清理）
 * - web_backup/   回滚用，保留上一版 override
 */
class WebResourceManager(private val context: Context) {

    private val webDir = File(context.filesDir, "web")
    private val stagingDir = File(context.filesDir, "web_staging")
    private val backupDir = File(context.filesDir, "web_backup")
    private val lock = Any()

    companion object {
        private const val TAG = "WebResourceManager"
        private const val MAX_ZIP_SIZE = 50L * 1024 * 1024  // 50MB
    }

    // ── 查询 ──

    /** 是否有活跃的 override（version.json + index.html 都存在才算有效） */
    fun hasOverride(): Boolean {
        return File(webDir, "version.json").exists() && File(webDir, "index.html").exists()
    }

    /**
     * 读取 web 资源：先查 override，再查 APK assets。
     * 返回 null 表示两处都找不到。
     */
    fun readAsset(path: String): ByteArray? {
        // 1. 先查 override（路径穿越防护：canonicalPath 必须在 webDir 内）
        if (hasOverride()) {
            val file = File(webDir, path)
            if (file.exists() && file.isFile) {
                val canonicalWebDir = webDir.canonicalPath
                val canonicalFile = file.canonicalPath
                // 前缀比对必须带分隔符，否则 `<webDir>_evil/x` 会被当成 webDir 内的文件
                // （同一文件 unzip 处的边界判定就是正确写法，这里对齐它）
                if (canonicalFile == canonicalWebDir ||
                    canonicalFile.startsWith(canonicalWebDir + File.separator)
                ) {
                    return try {
                        file.readBytes()
                    } catch (_: Exception) {
                        null
                    }
                }
            }
        }
        // 2. 回退到 APK assets
        return try {
            context.assets.open("web/$path").use { it.readBytes() }
        } catch (_: Exception) {
            null
        }
    }

    /** 获取当前版本信息（供 API 返回） */
    fun getVersionInfo(): Map<String, Any?> {
        val bundled = readBundledVersionJson()
        return if (hasOverride()) {
            val override = readOverrideVersionJson()
            mapOf(
                "mode" to "override",
                "version" to (override?.get("version") as? JsonPrimitive)?.content,
                "buildTime" to (override?.get("buildTime") as? JsonPrimitive)?.content,
                "bundledVersion" to (bundled?.get("version") as? JsonPrimitive)?.content,
                "bundledBuildTime" to (bundled?.get("buildTime") as? JsonPrimitive)?.content,
                "hasBackup" to File(backupDir, "version.json").exists(),
            )
        } else {
            mapOf(
                "mode" to "bundled",
                "version" to (bundled?.get("version") as? JsonPrimitive)?.content,
                "buildTime" to (bundled?.get("buildTime") as? JsonPrimitive)?.content,
                "hasBackup" to false,
            )
        }
    }

    // ── 安装 ──

    /**
     * 从 ZIP 流安装 web 前端。
     * 原子性保证：先解压到 staging，校验通过后原子替换。
     */
    fun installFromZip(inputStream: InputStream): Result<Map<String, Any?>> = synchronized(lock) {
        // 1. 清理 staging
        stagingDir.deleteRecursively()
        stagingDir.mkdirs()

        // 2. 解压到 staging（带安全检查）
        try {
            unzipChecked(inputStream, stagingDir)
        } catch (e: Exception) {
            AppLogger.e(TAG, "ZIP 解压失败: ${e.message}")
            stagingDir.deleteRecursively()
            return Result.failure(e)
        }

        // 3. 校验：必须包含 index.html 和 version.json
        if (!File(stagingDir, "index.html").exists()) {
            stagingDir.deleteRecursively()
            return Result.failure(IllegalArgumentException("ZIP 缺少 index.html"))
        }
        if (!File(stagingDir, "version.json").exists()) {
            stagingDir.deleteRecursively()
            return Result.failure(IllegalArgumentException("ZIP 缺少 version.json"))
        }

        // 4. 原子替换：当前 override → backup
        backupDir.deleteRecursively()
        if (webDir.exists()) {
            if (!webDir.renameTo(backupDir)) {
                // rename 失败（跨文件系统），尝试 copy + delete
                webDir.copyRecursively(backupDir, overwrite = true)
                webDir.deleteRecursively()
            }
        }

        // 5. staging → web
        if (!stagingDir.renameTo(webDir)) {
            // rename 失败，回滚 backup → web
            AppLogger.e(TAG, "staging → web rename 失败，回滚")
            backupDir.copyRecursively(webDir, overwrite = true)
            backupDir.deleteRecursively()
            stagingDir.deleteRecursively()
            return Result.failure(IllegalStateException("安装失败：文件系统操作异常"))
        }

        // 6. 清理
        stagingDir.deleteRecursively()

        AppLogger.i(TAG, "Web 前端更新成功: ${readOverrideVersionJson()}")
        return Result.success(getVersionInfo())
    }

    // ── 回滚 ──

    /** 回滚到上一版本（backup 目录） */
    fun rollback(): Result<Map<String, Any?>> = synchronized(lock) {
        if (!File(backupDir, "version.json").exists() || !File(backupDir, "index.html").exists()) {
            return Result.failure(IllegalStateException("没有可回滚的备份版本"))
        }
        webDir.deleteRecursively()
        if (!backupDir.renameTo(webDir)) {
            backupDir.copyRecursively(webDir, overwrite = true)
            backupDir.deleteRecursively()
        }
        AppLogger.i(TAG, "Web 前端已回滚: ${readOverrideVersionJson()}")
        return Result.success(getVersionInfo())
    }

    // ── 清除 ──

    /**
     * 清除 override（恢复为 APK 内置版本）。
     *
     * @return true 表示清理后 [hasOverride] 已为 false，即确实回到了内置版本。
     *
     * 为什么要返回值：`deleteRecursively()` 可能因文件被占用而部分失败，若 index.html 与
     * version.json 恰好幸存，[hasOverride] 会继续为 true。旧实现丢弃三个删除结果、
     * 调用方一律按成功处理，就会出现「提示已恢复内置版本、实际还在用坏的 override」。
     */
    fun clearOverride(): Boolean = synchronized(lock) {
        val webOk = webDir.deleteRecursively()
        val backupOk = backupDir.deleteRecursively()
        val stagingOk = stagingDir.deleteRecursively()
        val cleared = !hasOverride()
        if (cleared) {
            AppLogger.i(TAG, "Web override 已清除，将使用 APK 内置版本")
        } else {
            AppLogger.e(
                TAG,
                "Web override 清除不彻底 (web=$webOk backup=$backupOk staging=$stagingOk)，override 仍然有效"
            )
        }
        return cleared
    }

    // ── 启动时维护 ──

    /** 清理残留的 staging 目录（上次安装崩溃可能导致） */
    fun cleanupStaging() {
        if (stagingDir.exists()) {
            stagingDir.deleteRecursively()
            AppLogger.i(TAG, "已清理残留 staging 目录")
        }
    }

    /**
     * 启动时检查内置 web 是否换了版本，若换了则清除旧 override。
     * 必须在 HttpServer.start() 之前调用。
     *
     * 判据是内置 `version.json` 的 **version** 字段，不是 buildTime。
     * 原来用 buildTime 导致「上传的面板一重启就没了」：buildTime 由 vite 在每次
     * `npm run build` 时用 `new Date()` 重新生成（web/vite.config.ts），而 assets/web
     * 是构建期从 web/dist 拷进来的，所以**每份新编出来的 core 包 buildTime 都不同** ——
     * 换过一次 core 就会把用户上传的 override 连备份一起删掉，表现为"恢复成内置版本"。
     * version 只在前端真正发版时才变，正好对应"内置面板换了、旧 override 可能不兼容"。
     *
     * 另外两条保护：
     *  · 读不到内置 version（返回 null）时**什么都不做**，不清 override 也不动基线，
     *    避免一次 assets 读失败就把基线擦掉、下次启动误判成"版本变了"；
     *  · 上次记录为 null（首次运行 / 刚从 buildTime 方案迁移过来）时只记录、不清除，
     *    否则升级到本版本的第一次启动会白白删掉用户已上传的资源。
     *
     * @return 内置 version.json 的 version（读不到则 null，调用方应保留原基线）
     */
    fun checkAndClearStaleOverride(lastSeenVersion: String?): String? {
        val bundled = readBundledVersionJson()
        val bundledVersion = (bundled?.get("version") as? JsonPrimitive)?.content
        if (bundledVersion == null) {
            AppLogger.w(TAG, "读不到内置 web version，跳过陈旧 override 检查")
            return null
        }

        when {
            lastSeenVersion == null ->
                AppLogger.d(TAG, "首次记录内置 web version=$bundledVersion，不清除 override")
            lastSeenVersion != bundledVersion -> {
                if (hasOverride()) {
                    AppLogger.i(TAG, "内置 web 版本已变化 ($lastSeenVersion → $bundledVersion)，清除旧 override")
                    clearOverride()
                } else {
                    AppLogger.d(TAG, "内置 web 版本已变化 ($lastSeenVersion → $bundledVersion)，无 override 需清除")
                }
            }
            else -> AppLogger.d(TAG, "内置 web version=$bundledVersion 未变化，保留 override")
        }

        return bundledVersion
    }

    // ── 内部工具 ──

    /** 从 APK assets 读取 version.json */
    private fun readBundledVersionJson(): JsonObject? {
        return try {
            val bytes = context.assets.open("web/version.json").use { it.readBytes() }
            Json.parseToJsonElement(String(bytes)).jsonObject
        } catch (_: Exception) {
            null
        }
    }

    /** 从 override 目录读取 version.json */
    private fun readOverrideVersionJson(): JsonObject? {
        val file = File(webDir, "version.json")
        if (!file.exists()) return null
        return try {
            Json.parseToJsonElement(file.readText()).jsonObject
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 安全检查的 ZIP 解压：防止路径穿越攻击和符号链接。
     *
     * entry 名会先做分隔符归一化。原因：Windows PowerShell 5.1 的 `Compress-Archive`
     * 用反斜杠写 entry 名（违反 ZIP 规范 APPNOTE 4.4.17.1，规范要求 `/`）。
     * 反斜杠在 Linux/Android 上是合法文件名字符，不归一化就会在 web/ 根目录生成一个
     * 字面名为 `assets\index-xxx.js` 的单文件、assets/ 目录压根不存在；之后
     * [readAsset] 查 `assets/index-xxx.js` 全部落空，HttpServer 的 SPA fallback
     * 把 index.html 当作 .js 返回 → 前端白屏，而安装过程一路报成功。
     */
    private fun unzipChecked(input: InputStream, destDir: File) {
        val destPath = destDir.canonicalPath
        var totalSize = 0L

        ZipInputStream(input).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val rawName = entry.name
                // 必须先归一化再查穿越：`..\..\x` 在归一化前只是个字面文件名，检查不出来
                val entryName = rawName.replace('\\', '/').trimStart('/')
                if (entryName.isEmpty() || entryName.split('/').any { it == ".." }) {
                    throw SecurityException("ZIP entry 路径非法: $rawName")
                }
                val outFile = File(destDir, entryName)

                // 路径穿越防护（符号链接等情况靠 canonicalPath 兜住）
                val canonical = outFile.canonicalPath
                if (canonical != destPath && !canonical.startsWith(destPath + File.separator)) {
                    throw SecurityException("ZIP entry 路径穿越: $rawName")
                }

                // 反斜杠目录条目（如 `assets\`）在归一化前 isDirectory 为 false，需补判
                if (entry.isDirectory || entryName.endsWith("/")) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    outFile.outputStream().use { os ->
                        val copied = zis.copyTo(os)
                        totalSize += copied
                        if (totalSize > MAX_ZIP_SIZE) {
                            throw IllegalStateException("ZIP 解压后总大小超过 ${MAX_ZIP_SIZE / 1024 / 1024}MB 限制")
                        }
                    }
                }
                entry = zis.nextEntry
            }
        }
    }
}
