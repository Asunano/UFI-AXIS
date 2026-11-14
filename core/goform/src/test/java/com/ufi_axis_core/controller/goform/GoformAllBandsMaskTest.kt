package com.ufi_axis_core.controller.goform

import com.ufi_axis_core.deviceschema.DeviceProfile
import com.ufi_axis_core.deviceschema.FieldGroup
import com.ufi_axis_core.deviceschema.FieldSpec
import com.ufi_axis_core.deviceschema.SettingKey
import com.ufi_axis_core.deviceschema.WriteSpec
import io.ktor.client.statement.HttpResponse
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 「profile 没给频段全集掩码」这条分支的行为断言（阶段 2 批 D1，任务 2.9）。
 *
 * ## 为什么值得有
 *
 * [DeviceProfile.lteAllBandsMask] / [DeviceProfile.nrAllBandsMask] 的默认实现返回 `null`，
 * 语义是「该设备不需要 / 不支持显式下发全频段掩码」。而 `null` 与**空串**在对外语义上是
 * 两件不同的事：空串 = 「不发限制」（`validateBandList` 把空串当解锁放过）。
 * 这里钉住的是「`null` 折叠到与空串**同一侧**、且不抛异常」——
 * 换成抛异常会让用户点一下「解锁全部频段」直接崩在写路径上；
 * 换成「空串当全集下发」则是把「空串 = 不发限制」偷偷改成「空串 = 下发全频段」，
 * 那是对外语义变更（计划书 §15 的 P0-1 已就这一点裁决过）。
 *
 * 仓里现有的唯一 profile（`ZteGoformProfile`）两个掩码都返回非空串，所以这条分支
 * **只能靠假 profile 覆盖**；假对象风格照 `GoformWifiBandParamsTest` 的 `FakeChipReader`。
 *
 * ## 本用例走的是**真实例路径**，但它**不覆盖 WARN**
 *
 * 构造真 [GoformNetworkClient] 并调 `lteAllBands()` 在本地单测里**不会**踩
 * `android.util.Log` 的 `Stub!`（实测通过），原因不是 android.jar 被 mock 了，而是
 * `AppLogger.active = loggingEnabled && coreLogEnabled`，其中 `loggingEnabled` 的进程内
 * 初值是 `AppSettings.DEFAULT_LOG_ENABLED` = **false**，单测里没人调 `restoreSwitches`，
 * 于是 `AppLogger.log()` 在第一行就 `return`，**根本走不到 `Log.w`**。
 *
 * 结论有两条，别混淆：
 * 1. 返回值（空串 / 不抛异常）在这里被真正断言了；
 * 2. 「WARN 文案是否如实打出」**本用例证明不了** —— 它只能靠真机 / logcat 确认
 *    （或者哪天 `GoformNetworkClient` 像 `GoformSettingWriter` 那样把日志出口做成可注入的
 *    lambda，那时才能断言。现在**不为了测试去改生产代码的日志方式**）。
 */

class GoformAllBandsMaskTest {

    /**
     * 最小假 profile：两个掩码都返回 `null`（= 走 [DeviceProfile] 的默认实现），
     * 其余成员是本用例走不到的最小实现。
     */
    private object NoBandMaskProfile : DeviceProfile {
        override val id: String = "no-band-mask"
        override val displayName: String = "无频段全集掩码（虚构，仅用于本用例）"
        override fun readSpecs(): List<FieldSpec> = emptyList()
        override fun cmdsFor(group: FieldGroup): List<String> = emptyList()
        override fun writeSpec(key: SettingKey): WriteSpec? = null
        // lteAllBandsMask() / nrAllBandsMask() 刻意不覆写：断言的就是默认的 null。
    }

    /** 本用例一条请求都不发，所有成员都是「走到就说明测错了」。 */
    private object UnusedTransport : GoformTransport {
        private fun nope(): Nothing = error("本用例不该触达传输层")
        override fun baseUrl(): String = nope()
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
        override suspend fun ensureBaseUrlResolved() = nope()
        override suspend fun httpGet(url: String): HttpResponse = nope()
        override fun parseJson(body: String): JsonObject? = nope()
        override fun isAuthFailure(body: String): Boolean = nope()
        override suspend fun writeIdempotent(params: Map<String, String>): GoformWriteResult = nope()
        override suspend fun writeSessionSafe(params: Map<String, String>): GoformWriteResult = nope()
        override fun isSuccess(body: String?): Boolean = nope()
        override fun sha256Hex(input: String): String = nope()
    }

    private val client = GoformNetworkClient(UnusedTransport, NoBandMaskProfile)

    @Test
    fun `掩码为 null 时 LTE 全集取空串而不是抛异常`() {
        assertEquals("", client.lteAllBands())
    }

    @Test
    fun `掩码为 null 时 NR 全集取空串而不是抛异常`() {
        assertEquals("", client.nrAllBands())
    }

    @Test
    fun `掩码为 null 时重复取值稳定`() {
        // 这两个方法是纯读取（每次都问 profile），不许出现「第一次空串、之后变别的」这种状态。
        assertEquals("", client.lteAllBands())
        assertEquals("", client.lteAllBands())
        assertEquals("", client.nrAllBands())
        assertEquals("", client.nrAllBands())
    }

}
