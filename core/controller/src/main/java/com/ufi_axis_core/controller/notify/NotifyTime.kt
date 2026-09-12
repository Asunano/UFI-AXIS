package com.ufi_axis_core.controller.notify

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 通知里"人读的时间"统一格式化。
 *
 * 存在的理由：邮件正文的「接收时间」meta 行与 Webhook 模板的 `{{time}}` 必须是同一个格式 ——
 * 同一条通知从两个渠道到达，时间长得不一样只会让人怀疑其中一个是错的。
 * 格式串因此只有这里一份（`SmsForwardController.formatTime` 也走它）。
 *
 * 每次新建 [SimpleDateFormat]：它不是线程安全的，而通知投递会从多个协程进来 ——
 * 共享一个实例换来的那点开销不值得一个偶发的错乱时间。
 */
internal object NotifyTime {

    /** 本地时区、秒级精度。改这里会同时改变邮件正文与 Webhook 的 `{{time}}`。 */
    private const val PATTERN = "yyyy-MM-dd HH:mm:ss"

    fun format(ts: Long): String = SimpleDateFormat(PATTERN, Locale.getDefault()).format(Date(ts))
}
