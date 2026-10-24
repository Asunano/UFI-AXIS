package com.ufi_axis_core.api.pairing

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.ufi_axis_core.api.TestDeviceIdentity
import com.ufi_axis_core.util.AppSettings
import com.ufi_axis_core.util.PairedDeviceStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * L1 拦截（配对 / 密码登录）的测试（2026-10-15）。
 *
 * ## 为什么这一层是黑名单的全部
 * 配对与登录是**同一条链路**（都打 `POST /pairing/confirm`，`PairingManager` 内部才分叉），
 * 所以这一处判定同时覆盖了"不能重新配对"与"不能用密码登录"两件事。
 *
 * ## 最重要的一条断言：命中黑名单**不消耗一次性挑战**
 * 拦截点必须放在 `claimChallenge` 之前。否则被拉黑设备的每一次尝试都会白烧一个挑战 ——
 * 那等于给攻击者一个廉价的服务端资源消耗点。测试用"解除拉黑后**同一个挑战**还能配对成功"
 * 来证明挑战确实没被碰过（若被消耗，第二次会返回 `InvalidChallenge`）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PairingBlacklistGateTest {

    private lateinit var context: Context
    private lateinit var settings: AppSettings
    private lateinit var pairedStore: PairedDeviceStore
    private lateinit var blacklist: PairedDeviceBlacklistStore
    private lateinit var attemptLimiter: PairingAttemptLimiter
    private lateinit var manager: PairingManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        settings = AppSettings(context)
        settings.resetAll()
        settings.setDevicePassword("secret123")

        pairedStore = PairedDeviceStore(context, settings)
        pairedStore.clear()

        context.getSharedPreferences("paired_blacklist", Context.MODE_PRIVATE)
            .edit().clear().commit()
        blacklist = PairedDeviceBlacklistStore(context)

        manager = PairingManager(
            settings,
            pairedStore,
            blacklist = blacklist,
            attemptLimiter = PairingAttemptLimiter().also { attemptLimiter = it }
        )
    }

    private fun block(fp: String, hwId: String = "") = runBlocking {
        blacklist.block(
            BlockedDevice(
                fingerprint = fp,
                hwId = hwId,
                name = "被拉黑的设备",
                reason = "测试",
                blockedAt = System.currentTimeMillis(),
                blockedBy = "self"
            )
        )
    }

    /** 直接调 confirm（不走 TestDeviceIdentity.pair 的"自带挑战"版本），便于复用同一个挑战。 */
    private fun confirmWith(device: TestDeviceIdentity, challenge: String, hwId: String? = null) =
        manager.confirm(
            code = settings.pairingCode,
            pubKeySpki = device.publicKeySpki,
            challenge = challenge,
            signature = device.sign(challenge),
            password = "secret123",
            deviceName = device.deviceName,
            ip = "127.0.0.1",
            hwId = hwId
        )

    @Test
    fun `未拉黑设备配对成功 —— 空名单时行为与改动前一致`() {
        val device = TestDeviceIdentity("正常设备")
        val result = device.pair(manager, settings, "secret123")
        assertTrue("空黑名单不该影响任何设备，实际=$result", result is PairingManager.PairingConfirmResult.Success)
    }

    @Test
    fun `被拉黑设备配对被拒（Blocked）`() {
        val device = TestDeviceIdentity("被拉黑的设备")
        block(device.fingerprint)
        val result = device.pair(manager, settings, "secret123")
        assertTrue("应返回 Blocked，实际=$result", result is PairingManager.PairingConfirmResult.Blocked)
    }

    @Test
    fun `被拉黑设备即便密码正确也被拒 —— 覆盖密码登录这条分支`() {
        // 先让设备配对成功（占住"已配对"这条分支），再拉黑它、用密码重新登录
        val device = TestDeviceIdentity("先配对再拉黑")
        assertTrue(device.pair(manager, settings, "secret123") is PairingManager.PairingConfirmResult.Success)
        block(device.fingerprint)

        settings.enterPairingMode()
        val challenge = manager.issueChallenge()
        val result = confirmWith(device, challenge)
        assertTrue(
            "已配对设备的密码登录同样要被 L1 拦住，实际=$result",
            result is PairingManager.PairingConfirmResult.Blocked
        )
    }

    @Test
    fun `命中黑名单不消耗一次性挑战 —— 解除后同一挑战仍可配对成功`() {
        val device = TestDeviceIdentity("挑战不该被烧掉")
        block(device.fingerprint)

        settings.enterPairingMode()
        val challenge = manager.issueChallenge()

        val blocked = confirmWith(device, challenge)
        assertTrue("首次应被拦，实际=$blocked", blocked is PairingManager.PairingConfirmResult.Blocked)

        runBlocking { blacklist.unblock(device.fingerprint) }

        val after = confirmWith(device, challenge)
        assertTrue(
            "解除拉黑后**同一个挑战**必须仍可用（证明拦截发生在 claimChallenge 之前），实际=$after",
            after is PairingManager.PairingConfirmResult.Success
        )
    }

    @Test
    fun `按 hwId 拦截 —— 清 App 数据换了密钥的新设备同样被拒`() {
        val hw = "hw-android-1"
        // 老设备被拉黑（记录里有它的 hwId）
        block("fp-老设备", hwId = hw)

        // 清 App 数据后：新密钥 = 新指纹，但 hwId 不变
        val fresh = TestDeviceIdentity("清数据后的同一台手机")
        val result = run {
            settings.enterPairingMode()
            val challenge = manager.issueChallenge()
            confirmWith(fresh, challenge, hwId = hw)
        }
        assertTrue("应被 hwId 命中，实际=$result", result is PairingManager.PairingConfirmResult.Blocked)
    }

    @Test
    fun `解除拉黑后可以正常配对`() {
        val device = TestDeviceIdentity("先拉黑再解除")
        block(device.fingerprint)
        assertTrue(device.pair(manager, settings, "secret123") is PairingManager.PairingConfirmResult.Blocked)

        runBlocking { blacklist.unblock(device.fingerprint) }

        val result = device.pair(manager, settings, "secret123")
        assertTrue("解除后应能配对，实际=$result", result is PairingManager.PairingConfirmResult.Success)
    }

    @Test
    fun `删除已配对设备不会解除拉黑`() {
        val device = TestDeviceIdentity("移除后仍被拉黑")
        assertTrue(device.pair(manager, settings, "secret123") is PairingManager.PairingConfirmResult.Success)
        block(device.fingerprint)

        val removed = manager.removeDevice(device.fingerprint, "secret123", "127.0.0.1")
        assertTrue("前置：设备应被成功移除，实际=$removed", removed is PairingManager.RemoveResult.Success)
        assertTrue(
            "移除设备只是撤销授权，不该顺手取消拉黑",
            runBlocking { blacklist.isBlocked(device.fingerprint) }
        )

        val result = device.pair(manager, settings, "secret123")
        assertTrue("移除后仍应被 L1 拦住，实际=$result", result is PairingManager.PairingConfirmResult.Blocked)
    }

    @Test
    fun `拉黑一台设备不影响其他设备`() {
        val blockedDevice = TestDeviceIdentity("被拉黑的")
        val otherDevice = TestDeviceIdentity("正常的")
        block(blockedDevice.fingerprint)

        assertTrue(
            "被拉黑设备应被拒",
            blockedDevice.pair(manager, settings, "secret123") is PairingManager.PairingConfirmResult.Blocked
        )
        assertTrue(
            "其他设备必须不受影响",
            otherDevice.pair(manager, settings, "secret123") is PairingManager.PairingConfirmResult.Success
        )
        assertEquals("名单里仍只有一条", 1, blacklist.entries().size)
    }

    // ══════════════ 双键退避的接线（2026-10-15） ══════════════

    /**
     * 失败累加、成功清零 —— 而且**命中黑名单不计数**。
     *
     * 计数写在 `confirm` 的**单一出口**上（不是九个失败分支里），这条测试守的就是那个出口：
     * 少了它，"换 IP 无限爆破"就回来了，而任何单元测试都不会红。
     */
    @Test
    fun `失败累加计数、成功清零、黑名单命中不计数`() {
        val device = TestDeviceIdentity("退避计数")
        val hw = "hw-backoff"

        // 1) 失败（错误密码）→ 计数上升
        settings.enterPairingMode()
        val challenge1 = manager.issueChallenge()
        val wrong = manager.confirm(
            code = settings.pairingCode,
            pubKeySpki = device.publicKeySpki,
            challenge = challenge1,
            signature = device.sign(challenge1),
            password = "wrong-password",
            deviceName = device.deviceName,
            ip = "127.0.0.1",
            hwId = hw
        )
        assertTrue("前置：应是密码错误，实际=$wrong", wrong is PairingManager.PairingConfirmResult.InvalidPassword)
        assertTrue(
            "失败后应有退避计数（指纹与 hwId 至少一条）",
            attemptLimiter.failureCount(device.fingerprint, hw) > 0
        )
        assertTrue("并且下一次尝试要真的等待", manager.attemptDelayMsFor(device.publicKeySpki, hw) > 0)

        // 2) 成功 → 清零（必须带同一个 hwId：双键各自计数，漏传一条就清不干净）
        val ok = device.pair(manager, settings, "secret123", hwId = hw)
        assertTrue("前置：应配对成功，实际=$ok", ok is PairingManager.PairingConfirmResult.Success)
        assertEquals("成功后必须清零", 0, attemptLimiter.failureCount(device.fingerprint, hw))
        assertEquals("成功后不再延时", 0L, manager.attemptDelayMsFor(device.publicKeySpki, hw))

        // 3) 黑名单命中 → 不计数（终态，计数只会让日志更吵）
        block(device.fingerprint)
        assertTrue(
            device.pair(manager, settings, "secret123", hwId = hw) is PairingManager.PairingConfirmResult.Blocked
        )
        assertEquals(
            "命中黑名单不该累加退避计数（它已经被直接拒了）",
            0,
            attemptLimiter.failureCount(device.fingerprint, hw)
        )
    }

    /**
     * 2026-10-19：拉黑"与某台已配对设备同 hwId"的记录必须被拒。
     *
     * 为什么：`isBlocked` 是"指纹 **或** hwId 任一命中即算"，所以拉黑一条同 hwId 的旧记录
     * 会顺手把那台仍然有效的已配对设备一起判黑；而解除拉黑要 Bearer —— 被一起判黑的设备
     * 恰好就是持有 Bearer 的那台，于是谁也解不开。这是"自我拉黑"的变体。
     */
    @Test
    fun `拉黑与已配对设备同 hwId 的记录会被拒绝`() = runBlocking {
        val self = TestDeviceIdentity("self-device")
        pairedStore.upsert(
            fingerprint = self.fingerprint,
            deviceName = "本机",
            lastSeen = System.currentTimeMillis(),
            hwId = "HW-COMMON"
        )

        val result = manager.blockDevice(
            fingerprint = "some-old-record-fp",
            hwId = "HW-COMMON",
            name = null,
            reason = null,
            password = "secret123",
            ip = "127.0.0.1",
            selfFingerprint = null // 指纹不同，只有 hwId 相同
        )
        assertTrue(
            "同 hwId 会连带打死已配对设备，必须拒绝，实际=$result",
            result is PairingManager.BlacklistResult.SelfBlock
        )
        assertEquals("不该真的写进名单", 0, blacklist.entries().size)
    }
}
