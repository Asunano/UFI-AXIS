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
        // goform_password 是明文凭据，只允许 ConfigRoutes 的 GET 自己 put 脱敏值
        assertFalse("toMap 不应含 goform_password", map.containsKey("goform_password"))
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

    // ══════════════ 配对码失败即轮换（2026-10-15） ══════════════

    /**
     * 轮换后**旧码立即失效、新码可用** —— 这是"暴力猜码彻底失效"的依据：
     * 攻击者每失败一次，靶子就被换掉，它手上的旧码再也对不上。
     */
    @Test
    fun `rotatePairingCode invalidates the old code and the new one works`() {
        val s = freshPairingSettings()
        s.enterPairingMode()
        val oldCode = s.pairingCode
        assertTrue("前置：应有配对码", oldCode.isNotBlank())

        val newCode = s.rotatePairingCode()
        assertTrue("应返回非空新码", newCode.isNotBlank())
        assertNotEquals("新码必须与旧码不同", oldCode, newCode)
        assertEquals("pairingCode 应已换成新码", newCode, s.pairingCode)
        assertTrue(
            "旧码必须立即失效",
            s.confirmPairing(oldCode, "fp-attacker") is AppSettings.PairingConfirmResult.InvalidCode
        )
        assertTrue(
            "新码应可用",
            s.confirmPairing(newCode, "fp-owner") is AppSettings.PairingConfirmResult.Success
        )
    }

    /**
     * 设备已初始化（设过密码）后没有配对码可轮换：返回空串且不写 prefs。
     *
     * 这条守的是"别凭空造一个永远不会被读到的码" —— 已初始化设备的 `infoPayload`
     * 根本不下发 pairing_code。
     */
    @Test
    fun `rotatePairingCode is a no-op once the device is initialized`() {
        val s = freshPairingSettings()
        s.setDevicePassword("secret123")
        val before = s.pairingCode
        val rotated = s.rotatePairingCode()
        assertEquals("已初始化时应返回空串（没有码可轮换）", "", rotated)
        assertEquals("不应改动 pairingCode", before, s.pairingCode)
    }

    // ══════════════ 恢复默认 vs 恢复出厂（2026-10-19） ══════════════

    /**
     * 两条"重置"路径的语义必须**互不越界**：
     * - [AppSettings.resetAll] 只恢复配置、**保留**身份与凭据（远程 `/api/config/reset` 用它，
     *   否则一次远程重置就把设备变成"谁都能重新初始化"）；
     * - [AppSettings.factoryReset] 是**仅本机设置页**才能触发的"放弃所有权"，必须连密码一起清。
     *
     * 守的是"别把两者合并"：合并任意方向都会造成安全事故（远程能清所有权）
     * 或留下残渣（本地恢复出厂后密码还在、别人仍能登录）。
     */
    @Test
    fun `resetAll keeps credentials but factoryReset clears them`() {
        val s = freshPairingSettings()
        s.setDevicePassword("secret123")
        val idBefore = s.deviceId
        assertTrue("前置：已设置密码", s.devicePasswordSet)

        s.resetAll()
        assertTrue("resetAll 必须保留密码（远程重置不等于放弃所有权）", s.verifyDevicePassword("secret123"))
        assertEquals("resetAll 必须保留 device_id", idBefore, s.deviceId)

        s.factoryReset()
        assertFalse("factoryReset 必须清掉密码", s.verifyDevicePassword("secret123"))
        assertFalse("factoryReset 必须清掉『已设置』标记", s.devicePasswordSet)
        assertNotEquals("factoryReset 必须换掉 device_id", idBefore, s.deviceId)
    }

    // ══════════════ 启动失败计数 / 安全模式（2026-10-19） ══════════════

    /**
     * 这两个键是**运行态**（不是用户配置）：默认必须是"没有失败、没进安全模式"，
     * 且 `resetAll()` 必须清掉 —— 否则用户"恢复默认"之后设备还停在安全模式里（强制默认端口、关后台），
     * 表现就是"恢复了默认但功能不全"。
     */
    @Test
    fun `startup counters default to clean state and resetAll clears them`() {
        val s = freshPairingSettings()
        assertEquals("默认失败计数应为 0", 0, s.startupFailureCount)
        assertFalse("默认不应处于安全模式", s.safeModeActive)

        s.startupFailureCount = 5
        s.safeModeActive = true
        assertEquals(5, s.startupFailureCount)
        assertTrue(s.safeModeActive)

        s.resetAll()
        assertEquals("恢复默认必须清掉失败计数", 0, s.startupFailureCount)
        assertFalse("恢复默认必须清掉安全模式", s.safeModeActive)
    }

    // ══════════════ 备份导入的字符串格式校验（2026-10-19） ══════════════

    /**
     * 备份包可以被手工编辑，而 STRING 之前**只校验类型**：写入一个非法 `goform_ip`
     * 会让 core 永远连不上设备后台（只能靠本地页改回），非法 `update_url` 会让更新链路失效。
     * 这条测试钉住两个"改错就失联"的键，同时确认正常的键不受影响。
     */
    @Test
    fun `导入备份时非法 goform_ip 与 update_url 会被拒`() {
        val s = freshPairingSettings()
        val report = s.importBackupFields(
            mapOf(
                "goform_ip" to "http://evil/../x",
                "update_url" to "ftp://nope",
                "server_port" to 8081
            ),
            replace = false
        )

        assertTrue("非法 goform_ip 应被拒", "goform_ip" in report.rejected)
        assertTrue("非法 update_url 应被拒", "update_url" in report.rejected)
        assertTrue("合法项应被接受", "server_port" in report.applied)
        assertEquals("合法项要真的落库", 8081, s.port)
        assertNotEquals("非法值不能落库", "http://evil/../x", s.goformIp)

        // 空串是"清空/回默认"的合法语义，不应被格式校验拦住
        val cleared = s.importBackupFields(mapOf("goform_ip" to ""), replace = false)
        assertTrue("空串应放行", "goform_ip" in cleared.applied)
    }

    /**
     * 预览导入（dry-run，2026-10-19）：只算报告，**不写任何东西**。
     *
     * 备份导入是 60+ 键的批量写入口，客户端必须先预览再确认；如果预览偷偷写盘，
     * "先看一眼"就变成了"直接恢复"，等于没有确认步骤。
     */
    @Test
    fun `预览导入只出报告不落盘`() {
        val s = freshPairingSettings()
        val portBefore = s.port

        val report = s.previewBackupFields(
            mapOf("server_port" to 9099, "goform_ip" to "bad host!"),
            replace = false
        )

        assertTrue("应报告 server_port 会被应用", "server_port" in report.applied)
        assertTrue("应报告 goform_ip 会被拒绝", "goform_ip" in report.rejected)
        assertEquals("预览绝不能改配置", portBefore, s.port)

        // 真导入才会落盘
        val real = s.importBackupFields(mapOf("server_port" to 9099), replace = false)
        assertTrue("server_port" in real.applied)
        assertEquals("真导入应落盘", 9099, s.port)
    }
}
