package com.ufi_axis_core.api.backup

import com.ufi_axis_core.util.AppSettings
import com.ufi_axis_core.util.BackupCrypto

/** 远端自动备份的公开配置（**不含口令本身**）。 */
data class RemoteBackupConfigState(
    /** 目标存储源 id；空串 = 未配置/已关闭。 */
    val sourceId: String,
    /** 是否已保存口令。只用于 UI 显示"已设置"，绝不回显口令。 */
    val passphraseSet: Boolean,
)

/**
 * 远端自动备份的配置读写（2026-10-24）。
 *
 * ## 为什么要有这一层
 * `RemoteBackupRunner` 只认两个 raw key（`remote_backup_source_id` /
 * `remote_backup_passphrase`），但 app 与 web 此前都没有写入入口 —— 定时任务里能选
 * 「远端备份」，执行时却永远报"未配置远端存储源"。这里把校验与读写收成一处，
 * 端点只负责收 JSON，测试可以直接打它。
 *
 * ## 口令绝不回显
 * [read] 只回 `passphraseSet`。口令一旦回显，任何能打开备份面板的会话都能把它读走；
 * 它只应存在于设备本地设置与用户在输入框里主动输入的那一次。
 *
 * ## 关闭与清空口令的约束
 * 目标存储源非空时必须已有一份口令，否则任务执行必然失败。因此：
 *  · 清空口令时若 source 仍配置着 → 直接拒绝，不做"先清了再报错"；
 *  · 设置 source 时若既没有新口令、旧口令也为空 → 拒绝。
 * 想彻底关闭远端备份，应把 source 置空（允许空口令）。
 */
object RemoteBackupConfig {

    fun read(settings: AppSettings): RemoteBackupConfigState = RemoteBackupConfigState(
        sourceId = settings.getRawString(RemoteBackupRunner.KEY_BACKUP_SOURCE_ID)?.trim().orEmpty(),
        passphraseSet = !settings.getRawString(RemoteBackupRunner.KEY_BACKUP_PASSPHRASE)
            .isNullOrBlank(),
    )

    /**
     * 更新配置。
     *
     * @param sourceId null = 本次不改；空串 = 关闭远端备份（清空目标源）。
     * @param passphrase null/空串 = 本次不改；非空 = 新口令（按 [BackupCrypto] 的强度规则校验）。
     * @param clearPassphrase 显式清空已保存口令；与 [sourceId] 非空同时出现时会被拒绝。
     * @return null = 成功；否则是给人看的中文原因（端点回 400）。
     */
    fun update(
        settings: AppSettings,
        sourceId: String?,
        passphrase: String?,
        clearPassphrase: Boolean,
    ): String? {
        val current = read(settings)
        val newSource = sourceId?.trim() ?: current.sourceId
        val newPassphrase = when {
            clearPassphrase -> ""
            passphrase.isNullOrEmpty() -> null // null = 保留旧值
            else -> passphrase
        }

        if (newPassphrase != null && newPassphrase.isNotEmpty()) {
            BackupCrypto.validatePassphrase(newPassphrase)?.let { return it }
        }

        val hasPassphraseAfter = when {
            newPassphrase == null -> current.passphraseSet
            else -> newPassphrase.isNotEmpty()
        }
        if (clearPassphrase && newSource.isNotBlank()) {
            return "存储源仍配置时不能清空备份口令；请先把存储源留空保存"
        }
        if (newSource.isNotBlank() && !hasPassphraseAfter) {
            return "配置远端存储源前请先设置备份口令（至少 ${BackupCrypto.MIN_PASSPHRASE_LENGTH} 位）"
        }

        // 校验全部通过后才落盘：避免"先清了口令、再报 source 还开着"的半套状态。
        if (sourceId != null) {
            settings.setRawString(RemoteBackupRunner.KEY_BACKUP_SOURCE_ID, newSource)
        }
        if (newPassphrase != null) {
            settings.setRawString(RemoteBackupRunner.KEY_BACKUP_PASSPHRASE, newPassphrase)
        }
        return null
    }
}
