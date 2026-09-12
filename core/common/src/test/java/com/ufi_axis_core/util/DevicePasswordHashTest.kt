package com.ufi_axis_core.util

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 配对密码派生算法（v1 裸 SHA-256 → v2 PBKDF2）的回归防线。
 * （类名与被测符号沿用 `DevicePassword*`：那是持久化 key 的一部分，改名会让存量配置读不出来。）
 *
 * 2026-09-08：升级前是 `HashUtil.sha256(pw)` —— 单轮、无 salt，离线爆破百亿量级/秒，
 * 且能被彩虹表直接命中。现在是 PBKDF2-HMAC-SHA256 + 每密码随机 salt。
 *
 * 这里要钉住的**不是**算法细节，而是两条会造成用户被锁在外面的失效模式：
 * 1. 升级后旧密码必须仍然能登录（否则所有存量设备的用户都进不来，且没有任何提示）；
 * 2. 校验旧密码成功时必须就地升级成 v2（这是唯一能拿到明文的时机，错过就只能强制改密）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DevicePasswordHashTest {

    private lateinit var context: Context
    private lateinit var settings: AppSettings

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        settings = AppSettings(context)
        settings.resetAll()
    }

    /** 直接写入升级前的存储形态：只有 hash + set 标志，没有 salt、没有 version 键。 */
    private fun seedLegacyPassword(pw: String) {
        context.getSharedPreferences("ufi_axis_settings", Context.MODE_PRIVATE)
            .edit()
            .putString("device_password_hash", HashUtil.sha256(pw))
            .putBoolean("device_password_set", true)
            .remove("device_password_salt")
            .remove("device_password_version")
            .apply()
    }

    private fun rawPrefs() =
        context.getSharedPreferences("ufi_axis_settings", Context.MODE_PRIVATE)

    @Test
    fun `new password is stored as pbkdf2 with salt and never as bare sha256`() {
        settings.setDevicePassword("secret123")

        val stored = rawPrefs().getString("device_password_hash", "") ?: ""
        assertEquals("应标记为 v2", 2, rawPrefs().getInt("device_password_version", -1))
        assertTrue("应写入 salt", (rawPrefs().getString("device_password_salt", "") ?: "").isNotEmpty())
        assertNotEquals("绝不能再落裸 sha256", HashUtil.sha256("secret123"), stored)
        assertTrue(settings.verifyDevicePassword("secret123"))
        assertFalse(settings.verifyDevicePassword("secret124"))
    }

    @Test
    fun `salt is random per password so same password yields different hash`() {
        settings.setDevicePassword("samepass")
        val firstHash = rawPrefs().getString("device_password_hash", "")
        val firstSalt = rawPrefs().getString("device_password_salt", "")

        settings.setDevicePassword("samepass")
        val secondHash = rawPrefs().getString("device_password_hash", "")
        val secondSalt = rawPrefs().getString("device_password_salt", "")

        assertNotEquals("salt 必须每次重新生成", firstSalt, secondSalt)
        assertNotEquals("同一密码两次派生结果不应相同（否则等于无 salt）", firstHash, secondHash)
        assertTrue("重设后仍可校验", settings.verifyDevicePassword("samepass"))
    }

    @Test
    fun `legacy v1 password still verifies`() {
        seedLegacyPassword("oldpass")

        assertTrue("存量用户的旧密码必须仍然能登录", settings.verifyDevicePassword("oldpass"))
        assertFalse(settings.verifyDevicePassword("wrongpass"))
    }

    @Test
    fun `legacy v1 password is rehashed to v2 in place after successful verify`() {
        seedLegacyPassword("oldpass")
        assertEquals("前置：应是 v1（无 version 键）", -1, rawPrefs().getInt("device_password_version", -1))

        assertTrue(settings.verifyDevicePassword("oldpass"))

        assertEquals("校验成功后应就地升级成 v2", 2, rawPrefs().getInt("device_password_version", -1))
        assertTrue("应补上 salt", (rawPrefs().getString("device_password_salt", "") ?: "").isNotEmpty())
        assertNotEquals(
            "旧的裸 sha256 应已被覆盖",
            HashUtil.sha256("oldpass"),
            rawPrefs().getString("device_password_hash", "")
        )
        assertTrue("升级后同一密码继续可用", settings.verifyDevicePassword("oldpass"))
        assertFalse(settings.verifyDevicePassword("oldpass2"))
    }

    @Test
    fun `wrong legacy password does not trigger rehash`() {
        seedLegacyPassword("oldpass")

        assertFalse(settings.verifyDevicePassword("nope"))

        assertEquals("校验失败不应升级（此时手里没有正确明文）", -1, rawPrefs().getInt("device_password_version", -1))
        assertEquals(
            "哈希应保持原样",
            HashUtil.sha256("oldpass"),
            rawPrefs().getString("device_password_hash", "")
        )
    }

    @Test
    fun `never set password always fails verification`() {
        assertFalse(settings.devicePasswordSet)
        assertTrue(settings.hasDefaultPassword)
        assertFalse("从未设过密码时任何输入都不该通过", settings.verifyDevicePassword("admin"))
        assertFalse(settings.verifyDevicePassword(""))
    }

    @Test
    fun `v2 marked but salt missing fails closed instead of falling back to sha256`() {
        settings.setDevicePassword("secret123")
        // 模拟存储被外部破坏：version 说是 v2，salt 却没了
        rawPrefs().edit().remove("device_password_salt").apply()

        assertFalse("不能回退到弱算法，也不能放行", settings.verifyDevicePassword("secret123"))
    }
}
