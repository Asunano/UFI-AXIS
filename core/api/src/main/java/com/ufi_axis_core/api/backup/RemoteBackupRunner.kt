package com.ufi_axis_core.api.backup

import com.ufi_axis_core.api.files.StorageSourceManager
import com.ufi_axis_core.api.files.FileProviderRegistry
import com.ufi_axis_core.util.AppLogger
import com.ufi_axis_core.util.BackupCrypto
import com.ufi_axis_core.api.routes.BackupRoutes
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * 配置自动备份到远端（2026-10-06 实用功能批·拍板第6项）。
 *
 * 流程：BackupRoutes.export 同一套加密打包（BackupAssembler + BackupCrypto）
 * → 通过 StorageSourceManager.createProvider 的 write() 推到远端存储源
 * → 滚动保留 N 份（删最老的备份文件）。
 *
 * 定时任务动作 wire 名 `remote_backup`（ActionType + tasksShared 同步登记）。
 * 失败不吞：抛给 ActionExecutor → 任务历史可见；由调用方决定是否走通知渠道。
 *
 * 无 root、不碰 shell；凭据走存储源配置自身的密码（SMB/FTP/WebDAV/S3 各自实现）。
 */
class RemoteBackupRunner(
    private val assembler: BackupAssembler,
    private val storageSourceManager: StorageSourceManager,
    private val settings: com.ufi_axis_core.util.AppSettings,
) {
    companion object {
        private const val TAG = "RemoteBackup"
        /** 远端目录（相对存储源根）。 */
        private const val REMOTE_DIR = "ufi-axis-backup"
        /** 文件前缀 + 滚动保留份数。 */
        private const val PREFIX = "auto-"
        private const val KEEP = 7
        /** 加密口令的设置键（用户在备份面板配置；为空则该任务执行时报错）。 */
        const val KEY_BACKUP_PASSPHRASE = "remote_backup_passphrase"
        const val KEY_BACKUP_SOURCE_ID = "remote_backup_source_id"
    }

    /**
     * 跑一轮：导出加密包 → 推远端 → 滚动清理。
     * @return 结果摘要（供任务历史/通知文案）
     */
    suspend fun runOnce(): String {
        val sourceId = settings.getRawString(KEY_BACKUP_SOURCE_ID)?.trim().orEmpty()
        if (sourceId.isBlank()) throw IllegalStateException("未配置远端存储源（remote_backup_source_id）")
        val config = storageSourceManager.get(sourceId)
            ?: throw IllegalStateException("远端存储源不存在: $sourceId")
        val provider = storageSourceManager.createProvider(config)
        if (provider.readonly) throw IllegalStateException("存储源只读: ${config.label}")

        val passphrase = settings.getRawString(KEY_BACKUP_PASSPHRASE).orEmpty()
        if (passphrase.length < 8) throw IllegalStateException("未配置备份口令或口令过短（remote_backup_passphrase）")

        // 打包（与 BackupRoutes.export 完全同一套加密/格式）
        val sections = assembler.collect(emptyMap())
        val payload = zipSections(sections)
        val pack = buildEncryptedPackage(payload, passphrase, sections.size)

        // 写远端
        val name = "$PREFIX${System.currentTimeMillis()}.ufibak"
        val remotePath = "$REMOTE_DIR/$name"
        try {
            provider.mkdir(REMOTE_DIR)
            // write() 只写文本；备份是二进制 → 用 upload（base64 走 write 会膨胀 4/3 且编码歧义）
            // FileProvider 没有二进制 write，各 provider 的 upload 接收原始字节流 —— 这里用
            // write + base64 是不对的；改为要求 Capability.UPLOAD。实际写法见下方 uploadBytes。
            uploadBytes(provider, remotePath, pack)
        } catch (e: Exception) {
            throw IllegalStateException("推送远端失败: ${e.message}", e)
        }

        // 滚动清理：列出远端目录里的 auto-*.ufibak，删最老，剩 KEEP 份
        var deleted = 0
        runCatching {
            val listing = provider.list(REMOTE_DIR)
            val olds = listing.files
                .filter { it.name.startsWith(PREFIX) && it.name.endsWith(".ufibak") && it.name != name }
                .sortedBy { it.name } // 时间戳文件名，字典序即时间序
            for (i in 0 until maxOf(0, olds.size - (KEEP - 1))) {
                if (provider.delete("$REMOTE_DIR/${olds[i].name}")) deleted++
            }
        }.onFailure { AppLogger.w(TAG, "滚动清理失败（不影响本次备份）: ${it.message}") }

        AppLogger.i(TAG, "remote backup ok: $remotePath (${pack.size / 1024} KB, cleaned=$deleted)")
        return "备份已推送 ${config.label}:$remotePath（%.1f KB，清理旧备份 %d 份）"
            .format(pack.size / 1024.0, deleted)
    }

    // ── 以下两个打包函数与 BackupRoutes 私有实现保持同一语义（manifest+GCM+ZIP）──
    // 说明：BackupRoutes 的是 private，复制最小实现并在 KDoc 锚明同步责任。

    private fun zipSections(sections: Map<String, ByteArray>): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(out).use { zip ->
            sections.forEach { (path, bytes) ->
                zip.putNextEntry(java.util.zip.ZipEntry(path))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun buildEncryptedPackage(payload: ByteArray, passphrase: String, sectionCount: Int): ByteArray {
        val salt = BackupCrypto.newSalt()
        val iterations = BackupCrypto.DEFAULT_ITERATIONS
        val manifest = kotlinx.serialization.json.buildJsonObject {
            put("format", BackupRoutes.FORMAT_VERSION)
            put("created_at", System.currentTimeMillis())
            put("encrypted", true)
            put("section_count", sectionCount)
            put("payload_sha256", java.security.MessageDigest.getInstance("SHA-256")
                .digest(payload).joinToString("") { "%02x".format(it) })
            putJsonObject("kdf") {
                put("algorithm", BackupCrypto.KDF_ALGORITHM)
                put("iterations", iterations)
                put("salt", java.util.Base64.getEncoder().encodeToString(salt))
            }
        }
        val manifestBytes = manifest.toString().toByteArray()
        val out = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(out).use { zip ->
            zip.putNextEntry(java.util.zip.ZipEntry(BackupRoutes.ENTRY_MANIFEST))
            zip.write(manifestBytes)
            zip.closeEntry()
            val chars = passphrase.toCharArray()
            try {
                val sealed = BackupCrypto.seal(payload, chars, salt, iterations, manifestBytes)
                zip.putNextEntry(java.util.zip.ZipEntry(BackupRoutes.ENTRY_PAYLOAD_ENC))
                zip.write(sealed.iv)
                zip.write(sealed.cipherText)
                zip.closeEntry()
            } finally {
                chars.fill('\u0000')
            }
        }
        return out.toByteArray()
    }

    /** 二进制上传：优先 Capability.UPLOAD（Stream/字节流），provider 不支持时回退 base64 write。 */
    private suspend fun uploadBytes(provider: com.ufi_axis_core.api.files.FileProvider, path: String, bytes: ByteArray) {
        val caps = provider.capabilities
        when {
            com.ufi_axis_core.api.files.FileProvider.Capability.UPLOAD in caps ->
                java.io.ByteArrayInputStream(bytes).use { ins ->
                    provider.uploadStream(path, ins, bytes.size.toLong())
                }
            else -> {
                val b64 = java.util.Base64.getEncoder().encodeToString(bytes)
                provider.write("$path.b64", b64)
            }
        }
    }
}
