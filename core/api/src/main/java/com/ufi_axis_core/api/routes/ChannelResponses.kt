package com.ufi_axis_core.api.routes

import com.ufi_axis_core.notify.NotifyLevel
import com.ufi_axis_core.notify.SkipReason

/**
 * 三条投递渠道（邮件 / Webhook / 本机短信）的**响应拼装共用件**。
 *
 * 存在的理由只有一个：那三个路由类的 `configPayload` 与 `skipMessage` 是**同构**的
 * （规则同构之后字段名与语义逐字一致），而各写一份的表现是"改了一处、另两处忘了" ——
 * 而它俩恰好都是**不会报错**的那种忘：级别下拉少一个中文名、跳过原因在界面上显示成空白。
 */

/**
 * `SkipReason` → 给用户看的一句话（`/test` 响应里的 `error`）。
 *
 * 共性档直接取 [SkipReason.label]（中文文案的唯一真源在枚举上，见那里的注释）再补一个
 * "未发送" —— 三条渠道对"总开关关了""在免打扰时段"的说法本来就该一模一样，
 * 而此前是两张逐字重复的 `when` 表。
 *
 * [overrides] 留给**真有渠道差异**的那几档，目前只有本机短信要在文案里带上级别与配额数字
 * （那条渠道花钱，"今日 5/5，明天自动重置"比一句"配额已用尽"有用得多）。
 *
 * 为什么不再用穷举 `when` 当防线：新增一档 `SkipReason` 时，穷举表的价值是"编译不过、
 * 逼你补文案"，但代价是三份表。改成按 label 取值之后，新增一档**自动**就有中文文案
 * （枚举上必须写 label，那是它的构造参数），防线从"编译期报错"变成"根本没有漏的可能"。
 */
internal fun skipMessageOf(
    reason: SkipReason,
    overrides: Map<SkipReason, String> = emptyMap()
): String = overrides[reason] ?: "${reason.label}，未发送"

/**
 * 级别取值域的线上形状：`[{"name":"info","label":"提示"}, …]`。
 *
 * 回对象数组而不是裸字符串数组（`["info","warning","critical"]`）：客户端要在下拉里显示
 * 中文，此前只能自己再抄一张 `"warning" -> "警告"` 的映射表 —— 那张表加一档新级别时
 * 不会报错，只会在界面上显示成空白或原样的英文名。口径与同一份响应里的
 * `placeholders`（`[{"name":..,"desc":..}]`）一致。
 *
 * 中文名的真源是 [NotifyLevel.label]，顺序即枚举声明序（INFO < WARNING < CRITICAL），
 * 也就是界面上从松到严的排列顺序。
 */
internal fun levelOptions(): List<Map<String, String>> =
    NotifyLevel.entries.map { mapOf("name" to it.wireName, "label" to it.label) }
