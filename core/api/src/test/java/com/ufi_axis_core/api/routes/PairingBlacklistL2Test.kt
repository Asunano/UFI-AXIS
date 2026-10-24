package com.ufi_axis_core.api.routes

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.ufi_axis_core.api.TestDeviceIdentity
import com.ufi_axis_core.api.middleware.AuthMiddleware
import com.ufi_axis_core.api.pairing.BlockedDevice
import com.ufi_axis_core.api.pairing.PairedDeviceBlacklistStore
import com.ufi_axis_core.api.pairing.PairingManager
import com.ufi_axis_core.util.AppSettings
import com.ufi_axis_core.util.DeviceRequestVerifier
import com.ufi_axis_core.util.PairedDeviceStore
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * L2 在线吊销的端到端测试（2026-10-15）。
 *
 * 链路：`DeviceRequestVerifier.verify` 判 `Blocked` → `AuthMiddleware` 映射 **444**。
 *
 * 为什么这条要端到端测而不是只测 verifier：444 是**客户端行为约定**的载体
 *（`RetrofitClient` 见到连续 444 才判定"凭据确证作废"并清 token 回配对引导）。
 * 如果只把 verifier 的返回值测对、而 middleware 映射成了 401，客户端会一直重试、
 * 永远不退到配对页 —— 功能看起来"生效了"，实际没踢下线。
 *
 * 另一条同样重要：**没被拉黑的设备必须一点感觉都没有**（200）。黑名单最怕的就是误伤。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PairingBlacklistL2Test {

    private lateinit var context: Context
    private lateinit var settings: AppSettings
    private lateinit var pairedStore: PairedDeviceStore
    private lateinit var blacklist: PairedDeviceBlacklistStore
    private lateinit var manager: PairingManager
    private lateinit var authMiddleware: AuthMiddleware

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

        manager = PairingManager(settings, pairedStore, blacklist = blacklist)
        // 与生产装配一致：verifier 拿到的是"查黑名单"的 lambda（见 ComponentFactory），
        // 中间件拿到的是"命中时上报事件"的回调。
        authMiddleware = AuthMiddleware(
            DeviceRequestVerifier(
                pairedStore,
                blockedDevices = { fp, hw -> blacklist.isBlocked(fp, hw) }
            ),
            onBlockedDevice = { device -> blockedCallbacks += device.fingerprint }
        )
    }

    /** 记录 L2 回调收到的设备指纹（生产里这个回调会写事件中心）。 */
    private val blockedCallbacks = mutableListOf<String>()

    private fun Application.registerApi() {
        install(ContentNegotiation) { json() }
        routing {
            authMiddleware.install(this)
            route("/api") { PairedDevicesRoutes(manager).register(this) }
        }
    }

    private fun pairDevice(name: String): TestDeviceIdentity {
        val device = TestDeviceIdentity(name)
        val result = device.pair(manager, settings, "secret123")
        assertTrue("预置设备应配对成功，实际=$result", result is PairingManager.PairingConfirmResult.Success)
        return device
    }

    private suspend fun HttpClient.getSigned(uri: String, device: TestDeviceIdentity): HttpResponse =
        get(uri) { device.authorize(this, "GET", uri) }

    private fun block(fingerprint: String) = runBlocking {
        blacklist.block(
            BlockedDevice(
                fingerprint = fingerprint,
                name = "被拉黑的设备",
                blockedAt = System.currentTimeMillis(),
                blockedBy = "self"
            )
        )
    }

    @Test
    fun `被拉黑设备的已鉴权请求返回 444 —— 拉黑即刻掉线`() {
        val device = pairDevice("被拉黑的设备")
        block(device.fingerprint)

        testApplication {
            application { registerApi() }
            val resp = client.getSigned("/api/pairing/devices", device)
            assertEquals(
                "必须是 444（客户端据此清 token 回配对引导），而不是 401（会一直重试）",
                444,
                resp.status.value
            )
        }
    }

    @Test
    fun `未被拉黑的设备完全不受影响`() {
        val blocked = pairDevice("被拉黑的")
        val normal = pairDevice("正常的")
        block(blocked.fingerprint)

        testApplication {
            application { registerApi() }
            val resp = client.getSigned("/api/pairing/devices", normal)
            assertEquals("黑名单最怕误伤：正常设备必须照常 200", 200, resp.status.value)
        }
    }

    @Test
    fun `解除拉黑后已鉴权请求恢复`() {
        val device = pairDevice("先拉黑再解除")
        block(device.fingerprint)
        runBlocking { blacklist.unblock(device.fingerprint) }

        testApplication {
            application { registerApi() }
            val resp = client.getSigned("/api/pairing/devices", device)
            assertEquals(200, resp.status.value)
        }
    }

    /**
     * L2 命中必须把"是哪台设备"交给回调（生产里它写事件中心）。
     *
     * 只断言"回了 444"是不够的：一个 object 结果也能让中间件回 444，但上层就拿不到
     * 设备信息，写出来的事件只能是"有人被拒"这种读不出所以然的一行。
     */
    @Test
    fun `L2 命中把被拒设备交给回调（事件留痕的数据来源）`() {
        val device = pairDevice("要留痕的设备")
        block(device.fingerprint)

        testApplication {
            application { registerApi() }
            val resp = client.getSigned("/api/pairing/devices", device)
            assertEquals(444, resp.status.value)
        }

        assertEquals("回调应恰好收到一次", 1, blockedCallbacks.size)
        assertEquals("回调必须带上被拒设备的指纹", device.fingerprint, blockedCallbacks.single())
    }
}
