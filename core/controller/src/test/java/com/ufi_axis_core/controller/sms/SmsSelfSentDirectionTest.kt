package com.ufi_axis_core.controller.sms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * 「设备**自己发出去**的短信永远不会被判成 `received`」——**本机短信渠道的防自激循环底座**。
 *
 * ## 这个测试在防什么
 *
 * 2026-09-09 起 core 会主动发短信（`LocalSmsChannel`，通知渠道之一）。
 * 设备发出的短信会**落回自己的信箱**，而三条「发现新短信」链路全靠 `direction == "received"`
 * 把它排除掉：
 *
 * | 链路 | 判据 |
 * |---|---|
 * | `BackendService.forwardLatestSmsIfNew`（邮件） | `latest.direction != "received"` → 只记 id、不发信 |
 * | `DataScheduler.collectSmsCache`（WS 推送） | `latest.filter { it.direction == "received" && … }` |
 * | `DataScheduler.scanVerificationCodes`（验证码入库） | `if (m.direction != "received") continue` |
 *
 * 一旦下面这两个映射把本机发出的短信判成 `received`，那条通知短信就会被当成"新短信"
 * → 再触发一次通知 → 再发一条短信 → **无限循环烧话费**。
 * 所以这不是格式化细节的测试，它红了就意味着话费会开始漏。
 *
 * （兜底闸仍然在：`LocalSmsConfig.dailyLimit` 一天最多几条。但那是最后一道，不该指望它。）
 */
class SmsSelfSentDirectionTest {

    /** goform 信箱：tag=2 已发送、tag=3 发送失败 —— 两者都是**本机发出的**。 */
    @Test
    fun `goform 信箱 tag 2 与 3 都判为本机发出`() {
        assertEquals("tag=2（已发送）", "sent", SmsController.smsDirectionFromTag("2"))
        assertEquals("tag=3（发送失败，同样是本机发出的）", "sent", SmsController.smsDirectionFromTag("3"))
    }

    /** tag=0/1 是收到的短信（未读 / 已读）；认不出的 tag 也按"收到"处理（保守：宁可多通知）。 */
    @Test
    fun `goform 信箱 tag 0 与 1 判为收到`() {
        assertEquals("received", SmsController.smsDirectionFromTag("0"))
        assertEquals("received", SmsController.smsDirectionFromTag("1"))
        assertEquals("认不出的 tag 按收到处理", "received", SmsController.smsDirectionFromTag("4"))
        assertEquals("received", SmsController.smsDirectionFromTag(""))
    }

    /** 系统 SMS Provider：`type == 2`（MESSAGE_TYPE_SENT）是本机发出的。 */
    @Test
    fun `系统 Provider type 2 判为本机发出`() {
        assertEquals("sent", SmsController.smsDirectionFromType(2))
    }

    @Test
    fun `系统 Provider 其余 type 判为收到`() {
        for (type in listOf(0, 1, 3, 4, 5, 6)) {
            assertEquals("type=$type", "received", SmsController.smsDirectionFromType(type))
        }
    }

    /**
     * 两个方向常量本身也钉一下：三条链路比对的是**字符串字面量** `"received"`，
     * 改这两个常量的值等于同时改坏那三处判据，而编译器一个字都不会说。
     */
    @Test
    fun `方向常量的字面量不能改`() {
        assertEquals("received", SmsController.DIRECTION_RECEIVED)
        assertEquals("sent", SmsController.DIRECTION_SENT)
        assertNotEquals(SmsController.DIRECTION_RECEIVED, SmsController.DIRECTION_SENT)
    }
}
