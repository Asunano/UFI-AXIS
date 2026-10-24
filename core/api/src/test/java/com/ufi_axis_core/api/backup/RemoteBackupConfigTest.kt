package com.ufi_axis_core.api.backup

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.ufi_axis_core.util.AppSettings
import com.ufi_axis_core.util.BackupCrypto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 远端自动备份配置（2026-10-24）。
 *
 * 回归点：`remote_backup` 动作此前没有任何配置入口，且 source 与 passphrase 可以
 * 落成"半套状态"（有源没口令），任务执行必然失败。这里把校验/落盘顺序钉住。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class RemoteBackupConfigTest {

    private lateinit var settings: AppSettings

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        settings = AppSettings(context)
        settings.resetAll()
    }

    @Test
    fun `保存存储源与口令后可以读回`() {
        val pass = "a".repeat(BackupCrypto.MIN_PASSPHRASE_LENGTH)
        assertNull(RemoteBackupConfig.update(settings, "src-1", pass, clearPassphrase = false))

        val state = RemoteBackupConfig.read(settings)
        assertEquals("src-1", state.sourceId)
        assertTrue(state.passphraseSet)
        // 口令本身永远不出现在 read() 的返回值里。
        assertEquals(pass, settings.getRawString(RemoteBackupRunner.KEY_BACKUP_PASSPHRASE))
    }

    @Test
    fun `有存储源但没有口令时拒绝保存`() {
        val error = RemoteBackupConfig.update(settings, "src-1", null, clearPassphrase = false)

        assertNotNull(error)
        assertEquals("", RemoteBackupConfig.read(settings).sourceId)
        assertFalse(RemoteBackupConfig.read(settings).passphraseSet)
    }

    @Test
    fun `口令过短时拒绝且不落盘`() {
        val error = RemoteBackupConfig.update(settings, "src-1", "short", clearPassphrase = false)

        assertNotNull(error)
        assertEquals("", RemoteBackupConfig.read(settings).sourceId)
        assertFalse(RemoteBackupConfig.read(settings).passphraseSet)
    }

    @Test
    fun `存储源仍开着时不能清空口令`() {
        val pass = "b".repeat(BackupCrypto.MIN_PASSPHRASE_LENGTH)
        assertNull(RemoteBackupConfig.update(settings, "src-1", pass, clearPassphrase = false))

        val error = RemoteBackupConfig.update(settings, null, null, clearPassphrase = true)

        assertNotNull("先清口令再报 source 还开着会留下半套状态", error)
        assertTrue("拒绝时口令必须原样保留", RemoteBackupConfig.read(settings).passphraseSet)
    }

    @Test
    fun `先关闭存储源再清口令是允许的`() {
        val pass = "c".repeat(BackupCrypto.MIN_PASSPHRASE_LENGTH)
        assertNull(RemoteBackupConfig.update(settings, "src-1", pass, clearPassphrase = false))

        assertNull(RemoteBackupConfig.update(settings, "" , null, clearPassphrase = false))
        assertNull(RemoteBackupConfig.update(settings, null, null, clearPassphrase = true))

        val state = RemoteBackupConfig.read(settings)
        assertEquals("", state.sourceId)
        assertFalse(state.passphraseSet)
    }
}
