package com.ufi_axis_core.api.pairing

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.ufi_axis_core.api.TestDeviceIdentity
import com.ufi_axis_core.util.AppSettings
import com.ufi_axis_core.util.PairedDeviceStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 黑名单命中冷却的**接线**测试（2026-10-15）。
 *
 * 核心断言只有一条，但它就是这套机制存在的理由：
 * **解除拉黑不解除冷却** —— 解除的那一刻，被拉黑者如果正在反复尝试就能立刻重新爆破，
 * 冷却把这条窗口焊死。
 *
 * 冷却时长注入成毫秒级，用例不 sleep 十分钟。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PairingCooldownGateTest {

    /** 冷却：150ms 短档 / 3 次升到 300ms；衰减窗口给大，避免 24h 规则干扰。 */
    private fun fastCooldown() = PairingBlockCooldown(
        firstCooldownMs = 150L,
        escalateAfter = 3,
        escalatedCooldownMs = 300L,
        countResetMs = 60_000L
    )

    private lateinit var context: Context
    private lateinit var settings: AppSettings
    private lateinit var pairedStore: PairedDeviceStore
    private lateinit var blacklist: PairedDeviceBlacklistStore
    private lateinit var cooldown: PairingBlockCooldown
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
        cooldown = fastCooldown()

        manager = PairingManager(
            settings,
            pairedStore,
            blacklist = blacklist,
            blockCooldown = cooldown
        )
    }

    private fun block(fp: String) = runBlocking {
        blacklist.block(
            BlockedDevice(
                fingerprint = fp,
                name = "被拉黑的设备",
                blockedAt = System.currentTimeMillis(),
                blockedBy = "self"
            )
        )
    }

    private fun attempt(device: TestDeviceIdentity) = device.pair(manager, settings, "secret123")

    @Test
    fun `命中黑名单后进入冷却`() {
        val device = TestDeviceIdentity("命中一次")
        block(device.fingerprint)
        assertTrue(attempt(device) is PairingManager.PairingConfirmResult.Blocked)
        assertTrue("命中后应留下冷却记录", cooldown.remainingMs(device.fingerprint, null) > 0)
        assertEquals("应记为一次命中", 1, cooldown.hitCount(device.fingerprint, null))
    }

    /** ★ 这套机制存在的理由。 */
    @Test
    fun `解除拉黑不解除冷却 —— 冷却期内仍然配不上`() {
        val device = TestDeviceIdentity("解除也进不去")
        block(device.fingerprint)
        assertTrue("前置：命中一次以建立冷却", attempt(device) is PairingManager.PairingConfirmResult.Blocked)

        runBlocking { blacklist.unblock(device.fingerprint) }

        assertTrue(
            "解除拉黑后冷却仍在 → 必须继续被拒（否则「解除」这个动作本身就是可利用的窗口）",
            attempt(device) is PairingManager.PairingConfirmResult.Blocked
        )
    }

    /**
     * 403 要带 `retry_after_seconds`（2026-10-21 决策 D4）。
     *
     * 冷却策略本身是公开的，藏着剩余时间只会让用户不知道该等 5 分钟还是 1 小时 ——
     * 而每次重试都在刷新冷却计数，等于越试越久。
     */
    @Test
    fun `被拦时返回剩余等待秒数`() {
        val device = TestDeviceIdentity("告诉我等多久")
        block(device.fingerprint)

        val first = attempt(device)
        assertTrue("前置：应被拦", first is PairingManager.PairingConfirmResult.Blocked)
        val firstWait = (first as PairingManager.PairingConfirmResult.Blocked).retryAfterSeconds
        assertTrue("首次命中必须给出剩余秒数，实际=$firstWait", firstWait > 0)

        // 解除拉黑不清冷却：这条路上也要继续给出剩余时间（用户最需要它的时候）
        runBlocking { blacklist.unblock(device.fingerprint) }
        val still = attempt(device)
        assertTrue("解除拉黑后仍应被拦", still is PairingManager.PairingConfirmResult.Blocked)
        assertTrue(
            "冷却期内的拒绝同样要带剩余秒数",
            (still as PairingManager.PairingConfirmResult.Blocked).retryAfterSeconds > 0
        )
    }

    @Test
    fun `冷却过期后可以正常配对`() {
        val device = TestDeviceIdentity("等冷却过去")
        block(device.fingerprint)
        assertTrue(attempt(device) is PairingManager.PairingConfirmResult.Blocked)
        runBlocking { blacklist.unblock(device.fingerprint) }

        Thread.sleep(260L) // > firstCooldownMs(150)

        val result = attempt(device)
        assertTrue("冷却过后应能配对，实际=$result", result is PairingManager.PairingConfirmResult.Success)
    }

    @Test
    fun `未被命中的设备完全不受冷却影响`() {
        val blockedDevice = TestDeviceIdentity("被拉黑的")
        val other = TestDeviceIdentity("正常的")
        block(blockedDevice.fingerprint)
        assertTrue(attempt(blockedDevice) is PairingManager.PairingConfirmResult.Blocked)

        assertEquals("另一台设备不该有冷却", 0L, cooldown.remainingMs(other.fingerprint, null))
        assertTrue(
            "另一台设备必须能正常配对",
            attempt(other) is PairingManager.PairingConfirmResult.Success
        )
    }
}
