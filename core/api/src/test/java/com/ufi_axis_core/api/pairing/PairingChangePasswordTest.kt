package com.ufi_axis_core.api.pairing

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.ufi_axis_core.util.AppSettings
import com.ufi_axis_core.util.PairedDeviceStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 改密是否同时吊销已配对设备（2026-10-19）。
 *
 * ## 为什么要有这个开关
 * 默认行为是"改密码**不**踢掉已配对设备"（token 仍有效）——很多人会误以为改密就掉线，
 * 结果改完密码发现旧手机还能用。反过来，如果默认就踢，远程改密会把用户自己的设备一起踢下线，
 * 反而制造事故。所以做成**显式可选**，并由这条测试把两种行为都钉住。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PairingChangePasswordTest {

    private lateinit var context: Context
    private lateinit var settings: AppSettings
    private lateinit var pairedStore: PairedDeviceStore
    private lateinit var manager: PairingManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        settings = AppSettings(context)
        settings.resetAll()
        settings.setDevicePassword("oldpass")
        pairedStore = PairedDeviceStore(context, settings)
        pairedStore.clear()
        manager = PairingManager(settings, pairedStore)
    }

    private fun pairedDevice(fingerprint: String) {
        settings.pairedFingerprints = listOf(fingerprint)
        pairedStore.upsert(
            fingerprint = fingerprint,
            deviceName = "已配对设备",
            lastSeen = System.currentTimeMillis()
        )
    }

    @Test
    fun `默认改密不吊销已配对设备`() {
        pairedDevice("fp-keep")

        val result = manager.changePassword("oldpass", "newpass1", "127.0.0.1")

        assertTrue("应改密成功，实际=$result", result is PairingManager.ChangePwdResult.Success)
        assertEquals("默认必须保留已配对设备", 1, settings.pairedFingerprints.size)
        assertEquals("配对记录也要保留", 1, pairedStore.list().size)
    }

    @Test
    fun `显式开启后改密会清空全部配对`() {
        pairedDevice("fp-revoke")

        val result = manager.changePassword(
            oldPw = "oldpass",
            newPw = "newpass1",
            ip = "127.0.0.1",
            revokeAllDevices = true
        )

        assertTrue("应改密成功，实际=$result", result is PairingManager.ChangePwdResult.Success)
        assertEquals("指纹列表必须清空", 0, settings.pairedFingerprints.size)
        assertEquals("配对记录必须清空", 0, pairedStore.list().size)
        assertTrue("密码本身仍应已更新", settings.verifyDevicePassword("newpass1"))
    }
}
