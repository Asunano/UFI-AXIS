package com.ufi_axis_core.controller.notify

/**
 * 投递记录的写入口。
 *
 * 存在的理由：`mail_send_records` 那张表（DB v11 起带 `channel` 列）是**多渠道共用**的，
 * 但 insert + 环形裁剪的实现全仓只有一份（`SmsForwardController.recordChannelHistory`，
 * 它持有 DAO 与两道保留上限的取值口）。让 [WebhookChannel] 直接依赖那个控制器等于
 * 「Webhook 要先认识邮件」—— 所以中间隔一个函数接口，装配层负责接线。
 *
 * @param target 投到哪儿了。邮件是收件地址；Webhook 只写 **scheme + host**
 *   （`WebhookDelivery.originOf`）—— path/query 里带着 device key 这类凭据。
 * @param outcome 三态之一：`MailSendRecord.OUTCOME_SENT` / `OUTCOME_FAILED` /
 *   `OUTCOME_SKIPPED`。**不收 `success: Boolean`** 是刻意的：DB v12 起结果是三态，
 *   布尔表达不了"跳过"，而留着布尔参数就等于允许调用方写出 `success=false` 的跳过行 ——
 *   那正是会被计进失败数的那种脏数据。
 * @param detail 落进 `error` 列的说明：失败时是摊平的异常链，跳过时是
 *   `SkipReason.label`，成功时空串。
 */
fun interface DeliveryHistoryRecorder {
    fun record(
        channel: String,
        scene: String,
        subject: String,
        target: String,
        outcome: String,
        detail: String
    )
}
