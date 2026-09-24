package com.ufi_axis_core.controller.goform

import com.ufi_axis_core.deviceschema.DeviceProfile
import com.ufi_axis_core.deviceschema.FieldGroup
import com.ufi_axis_core.deviceschema.FieldSpec
import com.ufi_axis_core.deviceschema.SettingKey
import com.ufi_axis_core.deviceschema.WriteSpec
import com.ufi_axis_core.deviceschema.profile.ZteGoformProfile
import io.ktor.client.statement.HttpResponse
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「WiFi 二维码的文件名由 profile 给」这条改造的行为断言（阶段 2 任务 2.10 / 计划书 §15 的 P1-5）。
 *
 * ## 两件事在这里被钉住
 *
 * 1. **客户端按 profile 给的顺序、逐字地请求那些名字**，自己不补候选也不重排。
 *    断言里刻意**不写文件名字面量** —— 期望值直接由 `ZteGoformProfile.qrCodeFileNames(...)` 算出来。
 *    那两个名字的逐字冻结在 `ZteGoformProfileTest.二维码文件名候选逐字冻结` 里（唯一真源在 profile），
 *    在 `core/goform` 再抄一份就又有了第二份设备知识 —— 2.10 要消掉的正是这个。
 * 2. **profile 返回空列表时不抛异常**：一条文件请求都不发、返回 null、`lastQrCodeFailure` 是空串，
 *    与改造前「所有候选都取不到」那条路径完全一致。
 *
 * ## 为什么不用真 HTTP 也能测到这条路径
 *
 * 假传输层的 [RecordingTransport.httpGet] 记下 url 之后**抛异常**：`getWifiQrCode` 的 catch
 * 会把失败记进 `failures` 并**继续下一个候选**，于是一次调用就把整条候选顺序录了下来。
 * 这比造一个假的 [HttpResponse] 干净得多 —— 那个类只能由一次真 ktor 调用产生，
 * 硬造就变成联网测试了。
 *
 * ## 本用例**不覆盖** WARN 文案
 *
 * 理由与 `GoformAllBandsMaskTest` 那条完全一样：`AppLogger.active` 在单测进程里初值为 false，
 * `AppLogger.w()` 第一行就 return，根本走不到 `android.util.Log`。所以「空列表时那行 WARN
 * 是否如实打出」只能靠真机 / logcat 确认，这里断言的是**返回值与请求次数**。
 */
class GoformQrCodeFileNamesTest {

    /** 假的 base url：只要能拼出可比对的完整 url 就够了，本用例一个字节都不发出去。 */
    private val base = "http://192.168.0.1"

    /**
     * 只记 url、不联网的假传输层。
     *
     * [baseUrl] / [ensureBaseUrlResolved] / [httpGet] 是 `getWifiQrCode` 会走到的三个成员，
     * 其余全是「走到就说明测错了」。
     */
    private class RecordingTransport(private val base: String) : GoformTransport {

        /** 被请求过的完整 url，按发生顺序。 */
        val requested = mutableListOf<String>()

        private fun nope(): Nothing = error("本用例不该触达这个成员")

        override fun baseUrl(): String = base

        override suspend fun ensureBaseUrlResolved() {
            // 生产里这里去解析真实 base url；本用例已经直接给定，什么都不用做。
        }

        override suspend fun httpGet(url: String): HttpResponse {
            requested += url
            // 抛异常而不是回一个假响应：客户端的 catch 会记下失败并继续下一个候选，
            // 于是一次调用就能把候选顺序整条录下来（见类 KDoc）。
            throw IllegalStateException("fake: 本用例不联网")
        }

        override suspend fun ensureLogin(): Boolean = nope()
        override fun invalidateSession() = nope()
        override fun resetLogin() = nope()
        override fun updateGoformPassword(newPwd: String) = nope()
        override suspend fun read(commands: List<String>): JsonObject? = nope()
        override suspend fun readOne(command: String): JsonElement? = nope()
        override suspend fun write(params: Map<String, String>): String? = nope()
        override suspend fun logout(): Boolean = nope()
        override fun decodeDeviceText(input: String): String = nope()
        override fun adjustQoS(permits: Int) = nope()
        override fun getQosStatus(): Map<String, Any> = nope()
        override fun setQosEnabled(enabled: Boolean) = nope()
        override fun close() = nope()
        override fun parseJson(body: String): JsonObject? = nope()
        override fun isAuthFailure(body: String): Boolean = nope()
        override suspend fun writeIdempotent(params: Map<String, String>): GoformWriteResult = nope()
        override fun isSuccess(body: String?): Boolean = nope()
        override fun sha256Hex(input: String): String = nope()
    }

    /**
     * 最小假 profile：`qrCodeFileNames` 刻意**不覆写** —— 断言的就是 [DeviceProfile] 的默认空列表。
     *
     * 仓里现有的唯一 profile（`ZteGoformProfile`）一定返回非空候选，所以「空列表」这条分支
     * 只能靠假 profile 覆盖（同 `GoformAllBandsMaskTest` 的 `NoBandMaskProfile`）。
     */
    private object NoQrCodeProfile : DeviceProfile {
        override val id: String = "no-qrcode"
        override val displayName: String = "不提供二维码文件的设备（虚构，仅用于本用例）"
        override fun readSpecs(): List<FieldSpec> = emptyList()
        override fun cmdsFor(group: FieldGroup): List<String> = emptyList()
        override fun writeSpec(key: SettingKey): WriteSpec? = null
    }

    private fun clientOf(transport: GoformTransport, profile: DeviceProfile) =
        GoformWifiClient(transport, profile, profile)

    @Test
    fun `请求的文件名与顺序逐字来自 profile`() {
        val transport = RecordingTransport(base)
        val client = clientOf(transport, ZteGoformProfile)

        val result = runBlocking { client.getWifiQrCode("chip2", 1) }

        assertNull("假传输层每次都失败，必须返回 null 而不是抛异常", result)
        val expected = ZteGoformProfile.qrCodeFileNames("chip2", 1)
            .map { "$base/goform/goform_get_file_process/$it" }
        assertEquals("ZTE 在 5G 上是「主候选 + 兜底」两张", 2, expected.size)
        assertEquals("客户端不许改名字、也不许改顺序", expected, transport.requested)
        // 全部候选失败时真因要留给 route（/api/wifi/qrcode 靠它回 503 的说明）
        assertTrue("全部候选失败要记下真因", client.lastQrCodeFailure.isNotBlank())
    }

    @Test
    fun `chip1 加第 1 个 SSID 时候选重名，只发一次请求`() {
        val transport = RecordingTransport(base)
        val client = clientOf(transport, ZteGoformProfile)

        assertNull(runBlocking { client.getWifiQrCode("chip1", 1) })

        // 去重的责任在 profile（契约规定客户端不做过滤），所以这里同时钉住两侧：
        assertEquals("profile 应已去重", 1, ZteGoformProfile.qrCodeFileNames("chip1", 1).size)
        assertEquals("同一个文件不许发两次", 1, transport.requested.size)
    }

    @Test
    fun `profile 没登记文件名时不抛异常、一条请求都不发`() {
        val transport = RecordingTransport(base)
        val client = clientOf(transport, NoQrCodeProfile)

        val result = runBlocking { client.getWifiQrCode() }

        assertNull("空列表要走「所有候选都取不到」那条路，不许抛异常", result)
        assertTrue("一条文件请求都不该发出去", transport.requested.isEmpty())
        assertEquals(
            "没有任何候选就没有失败原因 —— 与改造前 failures 为空时一致（route 回不带真因的 503）",
            "",
            client.lastQrCodeFailure
        )
    }
}