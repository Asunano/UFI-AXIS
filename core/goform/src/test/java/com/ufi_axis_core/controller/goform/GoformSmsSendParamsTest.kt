package com.ufi_axis_core.controller.goform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `GoformSmsClient` 里**留在客户端**的那部分逻辑。
 *
 * ## 为什么只剩一条用例
 *
 * 原来这里有四条断言 `SEND_SMS` 参数表 / UCS2 正文 / `sms_time` 格式的用例 —— 那三件事
 * 已经搬进 `ZteSmsSpec`（设备事实），等价断言在 `ZteSmsSpecTest` 里，且两边的期望值
 * 在搬之前逐字对齐过。在这里再留一份就是"同一个事实两处维护"，删掉的是重复，不是覆盖率。
 *
 * 文件保留、不合并到别处：[GoformSmsClient.maskNumber] 是客户端自己的日志脱敏
 * （不是设备事实），它的测试就该待在 goform 模块里；下一轮客户端再长出可断言的纯逻辑
 * （如回读判定拆成纯函数）也落在这个文件。
 */
class GoformSmsSendParamsTest {

    @Test
    fun `日志里的号码不出现完整号码`() {
        val masked = GoformSmsClient.maskNumber("13800138000")
        assertTrue(masked.startsWith("138"))
        assertTrue(masked.endsWith("00"))
        assertFalse(masked.contains("13800138000"))
        assertEquals("***", GoformSmsClient.maskNumber("123"))
    }
}
