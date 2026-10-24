package com.ufi_axis_core.api.pairing

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.ufi_axis_core.api.TestDeviceIdentity
import com.ufi_axis_core.util.AppSettings
import com.ufi_axis_core.util.PairedDeviceStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 新设备批准的**接线**测试（2026-10-15）。
 *
 * 三条必须成立的行为：
 * 1. 开关关（默认）→ 与今天逐字一致（升级零感知）；
 * 2. 开关开 + 已有已配对设备 → 未批准的指纹被拒（403 的来源）；
 * 3. **一台已配对设备都没有时不做批准** —— 否则机主删光设备后就再也进不去（防死锁）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PairingApprovalGateTest {

    private lateinit var context: Context
    private lateinit var settings: AppSettings
    private lateinit var pairedStore: PairedDeviceStore
    private lateinit var approvalStore: PairedDeviceApprovalStore
    private lateinit var manager: PairingManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        settings = AppSettings(context)
        settings.resetAll()
        settings.setDevicePassword("secret123")

        pairedStore = PairedDeviceStore(context, settings)
        pairedStore.clear()

        approvalStore = PairedDeviceApprovalStore()
        manager = PairingManager(settings, pairedStore, approvalStore = approvalStore)
    }

    private fun attempt(device: TestDeviceIdentity, sid: String? = null) =
        device.pair(manager, settings, "secret123", sid = sid)

    @Test
    fun `开关默认关闭 —— 行为与改动前一致`() {
        assertTrue("默认必须是关的（升级零感知）", !settings.pairingApprovalRequired)
        val device = TestDeviceIdentity("普通设备")
        assertTrue(
            "默认关时新设备应直接配对成功",
            attempt(device) is PairingManager.PairingConfirmResult.Success
        )
    }

    @Test
    fun `开关打开且已有已配对设备时，未批准的新设备被拒`() {
        // 先让一台设备进来（此时还没有任何已配对设备 → 不触发批准）
        val owner = TestDeviceIdentity("机主的设备")
        assertTrue(attempt(owner) is PairingManager.PairingConfirmResult.Success)

        settings.pairingApprovalRequired = true

        val newcomer = TestDeviceIdentity("新设备")
        val result = attempt(newcomer)
        assertTrue("未批准的新设备应被拒，实际=$result", result is PairingManager.PairingConfirmResult.ApprovalRequired)
    }

    @Test
    fun `App 批准后同一指纹可以配对`() {
        val owner = TestDeviceIdentity("机主的设备")
        assertTrue(attempt(owner) is PairingManager.PairingConfirmResult.Success)
        settings.pairingApprovalRequired = true

        val newcomer = TestDeviceIdentity("新设备")
        // 请求方发起批准（真实流程里 sid 由 Web 端生成并轮询）
        val pending = manager.requestApproval(
            sid = "sid-newcomer",
            pubKeySpki = newcomer.publicKeySpki,
            deviceName = newcomer.deviceName,
            source = "192.168.***.***"
        )
        assertTrue("应登记成功", pending != null)
        assertTrue("机主点允许", manager.approveDevice("sid-newcomer"))

        assertTrue(
            "批准后应能配对（confirm 要带上同一个 sid，否则服务端不知道这次请求已被批准）",
            attempt(newcomer, sid = "sid-newcomer") is PairingManager.PairingConfirmResult.Success
        )
    }

    @Test
    fun `回传正确配对码等价于批准`() {
        val owner = TestDeviceIdentity("机主的设备")
        assertTrue(attempt(owner) is PairingManager.PairingConfirmResult.Success)
        settings.pairingApprovalRequired = true

        val newcomer = TestDeviceIdentity("新设备")
        val pending = manager.requestApproval(
            sid = "sid-code",
            pubKeySpki = newcomer.publicKeySpki,
            deviceName = newcomer.deviceName,
            source = "隧道"
        )!!

        // 走真实握手：一次性码 + 挑战 + 签名 + sid + 通知里的码
        settings.enterPairingMode()
        val challenge = manager.issueChallenge()
        val result = manager.confirm(
            code = settings.pairingCode,
            pubKeySpki = newcomer.publicKeySpki,
            challenge = challenge,
            signature = newcomer.sign(challenge),
            password = "secret123",
            deviceName = newcomer.deviceName,
            ip = "127.0.0.1",
            sid = "sid-code",
            approvalCode = pending.code
        )
        assertTrue(
            "机主从外部通知里读到码并回传 → 应放行，实际=$result",
            result is PairingManager.PairingConfirmResult.Success
        )
    }

    /** 码不对时照样被拒（否则"回传码"就成了万能钥匙）。 */
    @Test
    fun `回传错误的配对码仍然被拒`() {
        val owner = TestDeviceIdentity("机主的设备")
        assertTrue(attempt(owner) is PairingManager.PairingConfirmResult.Success)
        settings.pairingApprovalRequired = true

        val newcomer = TestDeviceIdentity("新设备")
        manager.requestApproval(
            sid = "sid-wrong",
            pubKeySpki = newcomer.publicKeySpki,
            deviceName = newcomer.deviceName,
            source = "隧道"
        )

        settings.enterPairingMode()
        val challenge = manager.issueChallenge()
        val result = manager.confirm(
            code = settings.pairingCode,
            pubKeySpki = newcomer.publicKeySpki,
            challenge = challenge,
            signature = newcomer.sign(challenge),
            password = "secret123",
            deviceName = newcomer.deviceName,
            ip = "127.0.0.1",
            sid = "sid-wrong",
            approvalCode = "AAAAAA"
        )
        assertTrue(
            "错误的码不该放行，实际=$result",
            result is PairingManager.PairingConfirmResult.ApprovalRequired
        )
    }

    /** ★ 防死锁：一台已配对设备都没有时，批准必须整体跳过。 */
    @Test
    fun `没有任何已配对设备时不做批准 —— 防止把自己锁在门外`() {
        settings.pairingApprovalRequired = true
        assertTrue("前置：确实没有任何已配对设备", settings.pairedFingerprints.isEmpty())

        val first = TestDeviceIdentity("第一台设备")
        assertTrue(
            "开关开着也不能拦第一台设备（否则没人能批准它）",
            attempt(first) is PairingManager.PairingConfirmResult.Success
        )
    }

    /** 请求方本来就是已配对设备（老设备重连/换 token）时不该被批准流程拦住。 */
    @Test
    fun `已配对设备重连不需要批准`() {
        val owner = TestDeviceIdentity("机主的设备")
        assertTrue(attempt(owner) is PairingManager.PairingConfirmResult.Success)
        settings.pairingApprovalRequired = true

        assertTrue(
            "老设备重连应直接放行",
            attempt(owner) is PairingManager.PairingConfirmResult.Success
        )
        assertEquals("不该产生待批准记录", 0, approvalStore.pendingList().size)
    }

    // ── 2026-10-24：批准开关关闭后的生命周期 ─────────────────────────────

    @Test
    fun `批准关闭时不登记待批准请求`() {
        assertTrue("前置：默认关", !settings.pairingApprovalRequired)
        val device = TestDeviceIdentity("普通设备")

        val pending = manager.requestApproval(
            sid = "sid-off",
            pubKeySpki = device.publicKeySpki,
            deviceName = device.deviceName,
            source = "局域网"
        )

        assertNull("关闭期间旧页面来登记必须被拒", pending)
        assertEquals("不能留下任何待批准记录", 0, approvalStore.pendingList().size)
        assertEquals(
            "状态查询应直接回 expired",
            PairedDeviceApprovalStore.STATUS_EXPIRED,
            manager.approvalStatus("sid-off")
        )
    }

    @Test
    fun `关闭批准后旧请求立即作废并清除`() {
        settings.pairingApprovalRequired = true
        val device = TestDeviceIdentity("新设备")
        assertNotNull(
            manager.requestApproval(
                sid = "sid-revoke",
                pubKeySpki = device.publicKeySpki,
                deviceName = device.deviceName,
                source = "局域网"
            )
        )
        assertEquals(1, approvalStore.pendingList().size)

        // 模拟 PUT /api/pairing/config 把开关关掉
        settings.pairingApprovalRequired = false
        assertEquals("策略落地时应清掉 1 条 pending", 1, manager.enforceApprovalPolicy())

        assertTrue("App 拉列表必须为空", manager.pendingApprovals().isEmpty())
        assertEquals("store 里也不能再留着", 0, approvalStore.pendingList().size)
        assertEquals(
            "请求方轮询应看到 expired，而不是永远 pending",
            PairedDeviceApprovalStore.STATUS_EXPIRED,
            manager.approvalStatus("sid-revoke")
        )
    }
}
