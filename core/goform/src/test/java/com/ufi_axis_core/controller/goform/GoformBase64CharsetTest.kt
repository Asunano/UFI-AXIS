package com.ufi_axis_core.controller.goform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.Charset
import java.util.Base64

/**
 * 设备文本字符集判据的单测（2026-09-22 修「写 UTF-8 / 读 GBK」不对称）。
 *
 * 测的是 [GoformClient.decodeDeviceText] 与 [GoformClient.base64DecodeOrEmpty] 这两个
 * companion 纯函数 —— 实例方法 `base64Decode` 只是给它们套了一层 `AppLogger.e`，
 * 而 [GoformClient] 一构造就起 Ktor client、`AppLogger` 又依赖 android.util.Log，
 * JVM 单测里不能构造。**不为可测性改生产类型**，所以测委托目标。
 *
 * 中文测试数据一律写成 `\uXXXX` 转义：这个仓库有过「整文件 GBK 入库、Kotlin 编译器静默容忍」
 * 的事故（计划书 §14.1 的 UTF-8 守卫），转义能保证断言的字节不受文件编码影响。
 */
class GoformBase64CharsetTest {

    private val gbk: Charset = Charset.forName("GBK")

    /** 密码 */
    private val cnShort = "\u5BC6\u7801"

    /** 中文口令 */
    private val cnLong = "\u4E2D\u6587\u53E3\u4EE4"

    /**
     * 写侧的编码公式，与 `GoformClient.base64Encode`（`GoformClient.kt:867`）逐字一致：
     * `Base64.getEncoder().encodeToString(input.toByteArray(Charsets.UTF_8))`。
     * base64Encode 是实例方法（不能在单测里构造这个类），这里复制公式而不是改生产代码。
     */
    private fun encodeLikeProduction(input: String): String =
        Base64.getEncoder().encodeToString(input.toByteArray(Charsets.UTF_8))

    @Test
    fun `ASCII 往返无损，且与改前的 GBK 解码逐字一致`() {
        val plain = "admin123!@#"
        val encoded = encodeLikeProduction(plain)

        assertEquals(plain, GoformClient.base64DecodeOrEmpty(encoded))
        // ASCII 段 UTF-8 与 GBK 编码相同 —— 这是「改判据不影响存量设备」的依据
        assertEquals(
            String(plain.toByteArray(Charsets.UTF_8), gbk),
            GoformClient.decodeDeviceText(plain.toByteArray(Charsets.UTF_8))
        )
    }

    @Test
    fun `设备存 UTF-8 中文时解出正确中文（改前会被 GBK 解错）`() {
        val encoded = encodeLikeProduction(cnLong)

        assertEquals(cnLong, GoformClient.base64DecodeOrEmpty(encoded))
        // 改前的实现：无条件 String(bytes, GBK) —— 记录它确实解错，防止判据被改回去
        assertFalse(cnLong == String(cnLong.toByteArray(Charsets.UTF_8), gbk))
    }

    @Test
    fun `设备存 GBK 中文时回落 GBK，与改前行为一致`() {
        val gbkBytes = cnLong.toByteArray(gbk)
        // 前提：GBK 的这串字节不是合法 UTF-8，否则本用例测不到回落分支
        assertFalse(
            "GBK 字节恰好也是合法 UTF-8，换一组测试数据",
            String(gbkBytes, Charsets.UTF_8).toByteArray(Charsets.UTF_8).contentEquals(gbkBytes)
        )

        assertEquals(cnLong, GoformClient.decodeDeviceText(gbkBytes))
        assertEquals(cnLong, GoformClient.base64DecodeOrEmpty(Base64.getEncoder().encodeToString(gbkBytes)))
    }

    @Test
    fun `非法 base64 保持既有语义：返回空串并报一次错`() {
        var reported = 0
        val decoded = GoformClient.base64DecodeOrEmpty("not a base64 string!!!") { reported++ }

        assertEquals("", decoded)
        assertEquals(1, reported)
        // 默认不报错的重载也必须是空串（不抛异常、不返回 null）
        assertEquals("", GoformClient.base64DecodeOrEmpty("*"))
    }

    @Test
    fun `读回再写回对 UTF-8 输入无损 —— 这正是被修的那条路径`() {
        for (plain in listOf("admin123", cnShort, cnLong, "pass_\u4E2D_mix", "")) {
            val deviceStored = encodeLikeProduction(plain)
            val decoded = GoformClient.base64DecodeOrEmpty(deviceStored)

            assertEquals("解出的明文与原文不符: $plain", plain, decoded)
            // base64Encode(base64Decode(x)) == x
            assertEquals("回写的 base64 与设备原值不符: $plain", deviceStored, encodeLikeProduction(decoded))
        }
    }

    @Test
    fun `判据用无损往返而不是替换字符：真正的 U+FFFD 不被误判成 GBK`() {
        val plain = "\uFFFD"
        val bytes = plain.toByteArray(Charsets.UTF_8) // EF BF BD，合法 UTF-8

        assertEquals(plain, GoformClient.decodeDeviceText(bytes))
        // 若判据写成「解出来含 U+FFFD 就算失败」，这里会回落 GBK 并解出别的字符
        assertTrue(String(bytes, gbk) != plain)
    }

    @Test
    fun `空字节与空串不触发回落`() {
        assertEquals("", GoformClient.decodeDeviceText(ByteArray(0)))
        assertEquals("", GoformClient.base64DecodeOrEmpty(""))
    }
}
