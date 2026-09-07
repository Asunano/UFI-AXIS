package com.ufi_axis_core.util

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * AppSettings 单元测试：配对状态机与 deviceId。
 *
 * 2026-08-28：原先这里还测「首次启动随机生成 token/secret」，那套**全局共享凭据**
 * 已随严格设备独立性整体删除（token 现在按设备签发、只存哈希，见 PairedDeviceStore），
 * 相关用例连同 `initialize()` / `rotateCredentials()` 一起移除，而不是改断言。
 * 每设备 token 与请求验签的覆盖在 AuthMiddlewareTest / DeviceAuthTest。
 *
 * 需要 Android Context + SharedPreferences，使用 Robolectric 提供内存版 SharedPreferences，
 * 不依赖真机/模拟器。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AppSettingsTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        AppSettings(context).resetAll()
    }

    @Test
    fun `toMap does not expose any global credential field`() {
        // 回归防线：全局 token/secret 已删除，配置导出里再出现这两个键说明凭据模型被改回去了。
        val map = AppSettings(context).toMap()
        assertFalse("toMap 不应含 token", map.containsKey("token"))
        assertFalse("toMap 不应含 secret", map.containsKey("secret"))
    }

    // ───────────────────────────────────────────────────────────
    // 配对状态机（Onboarding 重构）测试
    // 执行环境：本地可跑（Robolectric 提供内存版 SharedPreferences，无需真机/模拟器）。
    // ───────────────────────────────────────────────────────────

    private fun freshPairingSettings(): AppSettings {
        val s = AppSettings(context)
        s.resetAll()
        return s
    }

    @Test
    fun `enterPairingMode produces non-blank pairing code and leaves paired false`() {
        val s = freshPairingSettings()
        s.enterPairingMode()
        assertTrue("enterPairingMode 后 pairingCode 应非空", s.pairingCode.isNotBlank())
        assertFalse("enterPairingMode 后 paired 应为 false", s.paired)
    }

    @Test
    fun `confirmPairing with correct code binds fingerprint and clears code`() {
        val s = freshPairingSettings()
        s.enterPairingMode()
        val code = s.pairingCode
        val fp = "fp-test-001"
        val result = s.confirmPairing(code, fp)

        assertTrue("正确码应返回 Success", result is AppSettings.PairingConfirmResult.Success)
        assertTrue("确认成功后 paired 应为 true", s.paired)
        assertTrue("确认成功后 pairingCode 应被清空", s.pairingCode.isBlank())
        assertEquals("应绑定 app_fingerprint", fp, s.pairedAppFingerprint)
    }

    @Test
    fun `confirmPairing with wrong code returns InvalidCode and stays unpaired`() {
        val s = freshPairingSettings()
        s.enterPairingMode()
        val result = s.confirmPairing("deadbeefdeadbeef", "fp-x")
        assertTrue("错误码应返回 InvalidCode", result is AppSettings.PairingConfirmResult.InvalidCode)
        assertFalse("错误码不应改变配对态", s.paired)
    }

    @Test
    fun `confirmPairing second device with refreshed code succeeds and consumed code fails`() {
        val s = freshPairingSettings()
        s.enterPairingMode()
        s.confirmPairing(s.pairingCode, "fp-1")
        // 多设备语义（配额默认不限制）：AlreadyPaired 仅在 pairingEnabled 且达到 maxDevices 时返回。
        // 一次性配对码消耗后（pairingCode 已空）：旧码不可再用 → InvalidCode；
        // 重新 enterPairingMode 生成新码后第二台设备可正常配对。
        val consumed = s.confirmPairing(s.pairingCode, "fp-2")
        assertTrue("已消耗的配对码应返回 InvalidCode", consumed is AppSettings.PairingConfirmResult.InvalidCode)
        s.enterPairingMode()
        val second = s.confirmPairing(s.pairingCode, "fp-2")
        assertTrue("刷新配对码后第二台应配对成功", second is AppSettings.PairingConfirmResult.Success)
        assertEquals("应同时绑定两台设备指纹", listOf("fp-1", "fp-2"), s.pairedFingerprints)
    }

    @Test
    fun `loginPair binds fingerprint without consuming the pairing code`() {
        val s = freshPairingSettings()
        s.enterPairingMode()
        val code = s.pairingCode
        val result = s.loginPair("fp-login")
        assertTrue("密码登录应返回 Success", result is AppSettings.PairingConfirmResult.Success)
        assertEquals("应绑定指纹", listOf("fp-login"), s.pairedFingerprints)
        assertEquals("免码绑定不应消耗配对码", code, s.pairingCode)
    }

    @Test
    fun `unpair returns to unpaired state with a fresh non-blank code`() {
        val s = freshPairingSettings()
        s.enterPairingMode()
        val oldCode = s.pairingCode
        s.confirmPairing(oldCode, "fp")
        assertTrue("确认后应为已配对", s.paired)

        s.unpair()
        assertFalse("解除配对后 paired 应为 false", s.paired)
        assertTrue("解除配对后应重新生成非空配对码", s.pairingCode.isNotBlank())
        assertNotEquals("新配对码应与旧码不同（一次性失效）", oldCode, s.pairingCode)
    }

    @Test
    fun `deviceId is stable across repeated reads and persists across instances`() {
        val s = freshPairingSettings()
        val id1 = s.deviceId
        assertTrue("deviceId 应非空", id1.isNotBlank())
        assertEquals("同实例重复读取应一致", id1, s.deviceId)

        // 跨实例（同一 SharedPreferences）应读到相同持久化值
        val s2 = AppSettings(context)
        assertEquals("跨实例应持久化一致", id1, s2.deviceId)
    }
}
