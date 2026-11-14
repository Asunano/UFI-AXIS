package com.ufi_axis_core.deviceschema.profile

import com.ufi_axis_core.deviceschema.SmsSpec
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs

/**
 * ZTE goform 系设备（F50 等）的短信规则（计划书 §11.2）。
 *
 * ## 单独一个文件的理由
 *
 * [ZteGoformProfile] 已经 1300+ 行，而短信这套规则自成一体（一张参数表 + 一套编码 + 两个 tag），
 * 与字段映射表没有共用的常量。拆开之后「短信怎么发」这件事能一屏看完。
 *
 * ## 本轮是**刻意的暂时重复**
 *
 * 这里的每一行都是从 `core/goform/.../GoformSmsClient.kt` 逐字抄来的，抄的时候
 * **没有删掉那边的实现** —— 那是另一个模块的文件，本轮只负责把契约立起来并证明
 * 「两份实现产出同样的字节」（见 `ZteSmsSpecTest`，它的期望值与 goform 侧
 * `GoformSmsSendParamsTest` 逐字一致）。
 *
 * **下一轮接线时删掉 goform 里的那份**：`buildSendParams` / `toUcs2Hex` / `formatSmsTime`
 * 三个 `internal` 方法与 `TAG_SENT` / `TAG_SEND_FAILED` 两个常量，改成走
 * `profile.smsSpec()`。在那之前，两份实现同时存在是预期状态，不是漏改。
 *
 * ## 为什么 [sendParams] 里保留了 `isTest`
 *
 * 写侧的 [com.ufi_axis_core.deviceschema.WriteSpec.encode] 一律**不发** `isTest`
 * —— `GoformCodec.buildSetFormBody` 会统一补上并去重，所以在 encode 里写它是重复的。
 * 短信走的是**另一条路径**：`GoformSmsClient` 直接调 `goformPost`，不经过 writer/codec，
 * 所以 `isTest=false` 本来就在 `buildSendParams` 的输出里，照抄现状**保留**。
 * 这不是漏改，也不是与写侧口径不一致 —— 是两条路径各自补齐参数的位置不同。
 * 下一轮如果把短信也收进 codec，这一项要与去重逻辑一起处理（见待办池）。
 */
object ZteSmsSpec : SmsSpec {

    /**
     * `SEND_SMS` 的参数表。
     *
     * 四条设备事实（缺一条就是「能收不能发」）：
     * 1. `sms_time` 固件必填，缺了直接判失败，格式见 [formatSmsTime]；
     * 2. `encode_type=UNICODE` 与 UCS2 正文配套 —— 固件只认 `UNICODE` / `GSM7_default`
     *    这两个枚举，早期写成数值 `0` 属于无效值；
     * 3. `notCallback=true`，与删除 / 标记已读保持一致；
     * 4. `ID=-1` 表示新建（不是改草稿）。
     *
     * 用 `linkedMapOf`：顺序有意义（照抄现状，不重排）。
     *
     * `AD` 不在这里 —— 它由 `GoformClient.computeAd` 统一追加（与参数值无关），
     * 出现在这张表里会被多编码一次。
     */
    override fun sendParams(
        number: String,
        message: String,
        atMillis: Long,
        zone: TimeZone,
    ): Map<String, String> = linkedMapOf(
        "isTest" to "false",
        "goformId" to "SEND_SMS",
        "notCallback" to "true",
        "Number" to number,
        "sms_time" to formatSmsTime(atMillis, zone),
        "MessageBody" to encodeBody(message),
        "ID" to "-1",
        "encode_type" to "UNICODE",
    )

    /**
     * 信箱查询：`cmd=sms_data_total` + 分页 + 存储位 + tag 过滤 + 排序。
     *
     * `mem_store=1` / `tags=10` / `order_by` 三项是抄下来的固定值，真机上一直这么发
     * （`getSmsList` 与 `getSmsMeta` 两处 URL 完全一致，只有 page / data_per_page 不同）。
     *
     * ## `order_by` 的值里有 `+`，**调用方不要再做一次 URL 编码**
     *
     * 现状是直接拼进 query string 的字面量 `order+by+id+desc`（`+` 在 query 里就是空格）。
     * 下一轮接线时若改成「参数 map → 逐个 urlEncode → 拼 URL」，`+` 会被编成 `%2B`，
     * 设备收到的就是字面加号而不是空格 —— 那是静默的行为变更。要么原样拼接，
     * 要么把这里改成真空格并确认编码路径（改之前先真机验一次）。
     *
     * 通用的 `isTest=false` / `multi_data=1` / 防缓存的 `_=时间戳` 不在这里：
     * 它们对 goform 的每一次 GET 读取都一样（见 `GoformClient` 里其它读取点），
     * 且 `_` 依赖时钟，放进来就不是纯函数了。
     */
    override fun listQuery(page: Int, perPage: Int): Map<String, String> = linkedMapOf(
        "cmd" to "sms_data_total",
        "page" to page.toString(),
        "data_per_page" to perPage.toString(),
        "mem_store" to "1",
        "tags" to "10",
        "order_by" to "order+by+id+desc",
    )

    /**
     * `DELETE_SMS`。
     *
     * id 拼法见 [joinIds]：单条时输出与现状 `"$msgId;"` **逐字相同**。
     */
    override fun deleteParams(ids: List<String>): Map<String, String> = linkedMapOf(
        "isTest" to "false",
        "goformId" to "DELETE_SMS",
        "msg_id" to joinIds(ids),
        "notCallback" to "true",
    )

    /**
     * `SET_MSG_READ`。
     *
     * 两点与 [deleteParams] 不同，都是照抄现状、**不要顺手对齐**：
     * 1. 这条命令**没有** `notCallback` —— `GoformSmsClient.markSmsRead` 里就没发。
     *    删除有、标已读没有，看着像漏了，但没有实测依据说明补上是安全的；
     * 2. `tag` 是已读状态本身：`0` = 已读、`1` = 未读（与信箱行 tag 的 `2`=已发送 /
     *    `3`=发送失败不是同一套值域，只是共用了 `tag` 这个键名）。
     */
    override fun markReadParams(ids: List<String>, read: Boolean): Map<String, String> = linkedMapOf(
        "isTest" to "false",
        "goformId" to "SET_MSG_READ",
        "msg_id" to joinIds(ids),
        "tag" to if (read) "0" else "1",
    )

    /** ZTE 信箱行的 tag 语义（0/1=收到，2=已发送，3=发送失败，4=草稿）。 */
    override fun sentTag(): String = TAG_SENT

    /** 见 [sentTag]：`3` 是设备侧发送失败（常见原因是 SMSC 未设置 / SIM 未开通短信 / 被运营商拦截）。 */
    override fun failedTag(): String = TAG_SEND_FAILED

    /** UCS2：UTF-16BE 大端字节的小写 hex，无 BOM、无长度前缀。 */
    override fun encodeBody(message: String): String = toUcs2Hex(message)

    /**
     * id 拼接：分号分隔，**末尾也带一个分号**。
     *
     * 单条 = `"5;"`，与现状 `"$msgId;"` 逐字相同 —— 这是唯一有实测依据的形态
     * （`SmsController` 至今是一条一条删的，`markSmsRead` 同样只收单个 id）。
     * 多条按同一规则续写（`"5;6;"`）：契约签名收 `List<String>` 是为了将来批量操作，
     * 但**多条形态未经真机验证**，第一次真用批量之前先抓一次包。
     *
     * 空列表输出空串（不抛异常，保持纯函数）；调用方有责任不发空的 `msg_id`。
     */
    private fun joinIds(ids: List<String>): String = ids.joinToString("") { "$it;" }

    /** 见 [sentTag]。常量留在这里是为了让两个 tag 的取值挨着写，方便和真机截图对照。 */
    private const val TAG_SENT = "2"
    private const val TAG_SEND_FAILED = "3"

    /** UCS2：UTF-16BE 大端字节的小写 hex，无 BOM、无长度前缀。 */
    internal fun toUcs2Hex(message: String): String =
        message.toByteArray(Charsets.UTF_16BE).joinToString("") { "%02x".format(it) }

    /**
     * `sms_time` = `yy;MM;dd;HH;mm;ss;+TZ`，TZ 是相对 UTC 的**小时**偏移（东八区 → `+8`）。
     *
     * 设备时区无从得知，调用方取本机时区（core 跑在这台设备上，和固件同一个时钟源）；
     * 半小时制时区给成 `+5.5` 这种小数形式。
     *
     * **没有默认参数**：原实现的 `formatSmsTime(millis = System.currentTimeMillis(), zone = TimeZone.getDefault())`
     * 带默认值，那是给客户端用的便利；profile 这一侧必须由调用方显式传入，否则整张参数表
     * 又变回不可断言的（计划书 §11.2 第 1 条）。
     */
    internal fun formatSmsTime(millis: Long, zone: TimeZone): String {
        val cal = Calendar.getInstance(zone).apply { timeInMillis = millis }
        val offsetMin = (cal.get(Calendar.ZONE_OFFSET) + cal.get(Calendar.DST_OFFSET)) / 60_000
        val sign = if (offsetMin < 0) "-" else "+"
        val absMin = abs(offsetMin)
        val tz = if (absMin % 60 == 0) "$sign${absMin / 60}"
        else sign + String.format(Locale.US, "%.1f", absMin / 60.0)
        return String.format(
            Locale.US, "%02d;%02d;%02d;%02d;%02d;%02d;%s",
            cal.get(Calendar.YEAR) % 100,
            cal.get(Calendar.MONTH) + 1,
            cal.get(Calendar.DAY_OF_MONTH),
            cal.get(Calendar.HOUR_OF_DAY),
            cal.get(Calendar.MINUTE),
            cal.get(Calendar.SECOND),
            tz,
        )
    }
}
