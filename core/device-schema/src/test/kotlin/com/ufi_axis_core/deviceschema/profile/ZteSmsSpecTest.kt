package com.ufi_axis_core.deviceschema.profile

import com.ufi_axis_core.deviceschema.DeviceProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import java.util.TimeZone

/**
 * `ZteSmsSpec` —— 短信 profile 契约的实现。
 *
 * ## 本测试最重要的作用：证明「搬运没有改变发出去的字节」
 *
 * `sendParams` 的期望值与 goform 侧
 * `core/goform/src/test/java/com/ufi_axis_core/controller/goform/GoformSmsSendParamsTest.kt`
 * **逐字一致**（含 `isTest` / `encode_type` / `sms_time` 那几条）。
 * 下一轮删掉 `GoformSmsClient` 里的 `buildSendParams` / `toUcs2Hex` / `formatSmsTime`
 * 之后，「两份实现曾经产出同样的参数表」这件事有据可查 —— 否则那次删除就是无法验证的。
 *
 * 短信「能收不能发」的根因一直落在这张参数表上（缺 `sms_time`、`encode_type` 写成数值 `0`、
 * 少 `notCallback`），这三条都是**发不出去但读取毫无影响**的错误，只有断言拦得住回退。
 */
class ZteSmsSpecTest {

    // ───────────────────────── sendParams（与 goform 侧逐字对齐） ─────────────────────────

    @Test
    fun `参数表与 ZTE 固件要求逐项对齐`() {
        val p = ZteSmsSpec.sendParams("13800138000", "hello", SEND_MILLIS, GMT8)
        // 下面 8 条的期望值与 GoformSmsSendParamsTest 同名用例逐字一致
        assertEquals("false", p["isTest"])
        assertEquals("SEND_SMS", p["goformId"])
        assertEquals("true", p["notCallback"])
        assertEquals("13800138000", p["Number"])
        assertEquals("-1", p["ID"])
        // 正文按 UCS2 编，encode_type 必须是 UNICODE；写 "0"/"2" 这类数值固件不认
        assertEquals("UNICODE", p["encode_type"])
        assertEquals("26;09;01;23;19;39;+8", p["sms_time"])
        // AD 由 GoformClient 统一追加，不能出现在参数表里（否则会被编码一次）
        assertFalse(p.containsKey("AD"))
    }

    @Test
    fun `参数表就是这 8 个键且顺序不变`() {
        // 顺序是照抄现状的事实之一（个别固件对表单字段顺序敏感），所以连键序一起冻结
        val p = ZteSmsSpec.sendParams("13800138000", "hello", SEND_MILLIS, GMT8)
        assertEquals(
            listOf("isTest", "goformId", "notCallback", "Number", "sms_time", "MessageBody", "ID", "encode_type"),
            p.keys.toList(),
        )
        assertEquals(
            mapOf(
                "isTest" to "false",
                "goformId" to "SEND_SMS",
                "notCallback" to "true",
                "Number" to "13800138000",
                "sms_time" to "26;09;01;23;19;39;+8",
                "MessageBody" to "00680065006c006c006f", // "hello"
                "ID" to "-1",
                "encode_type" to "UNICODE",
            ),
            p,
        )
    }

    @Test
    fun `正文用的就是 encodeBody 的输出`() {
        // 编码方式与 encode_type 是配套的一对，不许一处改一处不改
        val p = ZteSmsSpec.sendParams("13800138000", "你好", SEND_MILLIS, GMT8)
        assertEquals(ZteSmsSpec.encodeBody("你好"), p["MessageBody"])
        assertEquals("4f60597d", p["MessageBody"])
    }

    // ───────────────────────── 正文编码 ─────────────────────────

    @Test
    fun `正文是 UTF-16BE 裸 hex 无 BOM`() {
        // "hi" → 0068 0069
        assertEquals("00680069", ZteSmsSpec.toUcs2Hex("hi"))
        // "你好" → 4F60 597D
        assertEquals("4f60597d", ZteSmsSpec.toUcs2Hex("你好"))
        assertFalse("不能带 BOM", ZteSmsSpec.toUcs2Hex("hi").startsWith("feff"))
    }

    @Test
    fun `emoji 按 UTF-16 代理对编两个码元`() {
        // U+1F600 → D83D DE00（4 字节 / 8 个 hex 字符），不是 3 字节的 UTF-8
        assertEquals("d83dde00", ZteSmsSpec.encodeBody("\uD83D\uDE00"))
    }

    @Test
    fun `hex 是小写`() {
        val hex = ZteSmsSpec.encodeBody("你好\uD83D\uDE00")
        assertEquals(hex.lowercase(), hex)
    }

    @Test
    fun `空正文编成空串`() {
        assertEquals("", ZteSmsSpec.encodeBody(""))
    }

    // ───────────────────────── sms_time ─────────────────────────

    @Test
    fun `sms_time 是两位补零的分号串加小时时区`() {
        // 1788276110000 = 2026-09-01T15:21:50Z → GMT+8 的 23:21:50
        val t = ZteSmsSpec.formatSmsTime(FIXED_MILLIS, TimeZone.getTimeZone("GMT+8"))
        assertEquals("26;09;01;23;21;50;+8", t)
        assertEquals(7, t.split(";").size)
    }

    @Test
    fun `UTC 的偏移写成正零`() {
        assertEquals("26;09;01;15;21;50;+0", ZteSmsSpec.formatSmsTime(FIXED_MILLIS, TimeZone.getTimeZone("UTC")))
    }

    @Test
    fun `半小时制时区给小数 负偏移给负号`() {
        assertEquals("+5.5", ZteSmsSpec.formatSmsTime(FIXED_MILLIS, TimeZone.getTimeZone("GMT+5:30")).split(";").last())
        assertEquals("-3", ZteSmsSpec.formatSmsTime(FIXED_MILLIS, TimeZone.getTimeZone("GMT-3")).split(";").last())
        // 真实的半小时制时区（印度，无夏令时）与 GMT+5:30 必须一致
        assertEquals(
            ZteSmsSpec.formatSmsTime(FIXED_MILLIS, TimeZone.getTimeZone("GMT+5:30")),
            ZteSmsSpec.formatSmsTime(FIXED_MILLIS, TimeZone.getTimeZone("Asia/Kolkata")),
        )
        assertEquals("26;09;01;20;51;50;+5.5", ZteSmsSpec.formatSmsTime(FIXED_MILLIS, TimeZone.getTimeZone("Asia/Kolkata")))
    }

    @Test
    fun `跨年边界的年份取后两位`() {
        // NEW_YEAR_MILLIS = 2026-12-31T16:00:00Z：在 GMT+8 已经是 2027-01-01 零点，在 UTC 还是 2026-12-31
        assertEquals("27;01;01;00;00;00;+8", ZteSmsSpec.formatSmsTime(NEW_YEAR_MILLIS, GMT8))
        assertEquals("26;12;31;16;00;00;+0", ZteSmsSpec.formatSmsTime(NEW_YEAR_MILLIS, TimeZone.getTimeZone("UTC")))
    }

    @Test
    fun `同一时刻不同时区渲染不同 结果与运行环境无关`() {
        // 固定 millis + 显式 zone 是「没有设备也能断言」的前提（计划书 §11.2 第 1 条）
        val a = ZteSmsSpec.formatSmsTime(FIXED_MILLIS, TimeZone.getTimeZone("GMT+8"))
        val b = ZteSmsSpec.formatSmsTime(FIXED_MILLIS, TimeZone.getTimeZone("GMT-3"))
        assertEquals("26;09;01;23;21;50;+8", a)
        assertEquals("26;09;01;12;21;50;-3", b)
    }

    // ───────────────────────── 查询 / 删除 / 标已读 ─────────────────────────

    @Test
    fun `listQuery 逐字就是现状那串 query`() {
        assertEquals(
            mapOf(
                "cmd" to "sms_data_total",
                "page" to "0",
                "data_per_page" to "50",
                "mem_store" to "1",
                "tags" to "10",
                // 值里的 + 是 query string 里的空格，调用方不要再 urlEncode 一次
                "order_by" to "order+by+id+desc",
            ),
            ZteSmsSpec.listQuery(0, 50),
        )
    }

    @Test
    fun `listQuery 只有分页两项随入参变`() {
        // getSmsMeta 那处用的是 page=0 / data_per_page=1，其余键值与 getSmsList 完全一致
        val list = ZteSmsSpec.listQuery(0, 50)
        val meta = ZteSmsSpec.listQuery(0, 1)
        assertEquals(list.keys, meta.keys)
        assertEquals(list - "page" - "data_per_page", meta - "page" - "data_per_page")
        assertEquals("1", meta["data_per_page"])
        assertEquals("2", ZteSmsSpec.listQuery(2, 20)["page"])
    }

    @Test
    fun `listQuery 不含传输层通用参数`() {
        // isTest / multi_data / 防缓存的 _ 由客户端统一追加；_ 依赖时钟，进来就不是纯函数了
        val q = ZteSmsSpec.listQuery(0, 50)
        listOf("isTest", "multi_data", "_").forEach { assertFalse(it, q.containsKey(it)) }
    }

    @Test
    fun `deleteParams 单条与现状逐字相同`() {
        assertEquals(
            mapOf(
                "isTest" to "false",
                "goformId" to "DELETE_SMS",
                "msg_id" to "5;", // 现状是 "$msgId;"
                "notCallback" to "true",
            ),
            ZteSmsSpec.deleteParams(listOf("5")),
        )
    }

    @Test
    fun `deleteParams 多条按分号续写`() {
        // 多条形态未经真机验证（现有调用点是一条一条删的），这里只把规则钉住
        assertEquals("5;6;7;", ZteSmsSpec.deleteParams(listOf("5", "6", "7"))["msg_id"])
        assertEquals("", ZteSmsSpec.deleteParams(emptyList())["msg_id"])
    }

    @Test
    fun `markReadParams 标已读与现状逐字相同`() {
        assertEquals(
            mapOf(
                "isTest" to "false",
                "goformId" to "SET_MSG_READ",
                "msg_id" to "5;",
                "tag" to "0",
            ),
            ZteSmsSpec.markReadParams(listOf("5")),
        )
    }

    @Test
    fun `markReadParams 标未读发 tag 1`() {
        // 对外 POST /api/sms/read 支持 read=false（app 的 ToolsModule 在用），不能丢
        assertEquals("1", ZteSmsSpec.markReadParams(listOf("5"), read = false)["tag"])
    }

    @Test
    fun `SET_MSG_READ 没有 notCallback`() {
        // 删除有、标已读没有 —— 照抄现状，不要顺手对齐（补上没有实测依据）
        assertFalse(ZteSmsSpec.markReadParams(listOf("5")).containsKey("notCallback"))
        assertEquals("true", ZteSmsSpec.deleteParams(listOf("5"))["notCallback"])
    }

    // ───────────────────────── tag 判据 ─────────────────────────

    @Test
    fun `tag 判据是 2 与 3 且两者不同`() {
        assertEquals("2", ZteSmsSpec.sentTag())
        assertEquals("3", ZteSmsSpec.failedTag())
        // 合成一档就分不出「失败」和「还没出结果」——上层拿它决定要不要重试/计费
        assertFalse(ZteSmsSpec.sentTag() == ZteSmsSpec.failedTag())
    }

    // ───────────────────────── 契约默认值 ─────────────────────────

    @Test
    fun `不支持短信的设备返回 null`() {
        // MockAltProfile 没有实现 smsSpec()，走接口默认实现
        assertNull(MockAltProfile.smsSpec())
        assertSame(ZteSmsSpec, ZteGoformProfile.smsSpec())
    }

    @Test
    fun `默认实现挂在 DeviceProfile 上而不是各 profile 自己写 null`() {
        // 新写一个 profile 不提 smsSpec 就应当是「没有这个能力」，不该编译失败也不该抛异常
        val bare = object : DeviceProfile {
            override val id: String = "bare"
            override val displayName: String = "bare"
            override fun readSpecs() = emptyList<com.ufi_axis_core.deviceschema.FieldSpec>()
            override fun cmdsFor(group: com.ufi_axis_core.deviceschema.FieldGroup) = emptyList<String>()
            override fun writeSpec(key: com.ufi_axis_core.deviceschema.SettingKey): com.ufi_axis_core.deviceschema.WriteSpec? = null
        }
        assertNull(bare.smsSpec())
    }

    private companion object {
        val GMT8: TimeZone = TimeZone.getTimeZone("GMT+8")

        /** 固定时刻，避免断言依赖运行时的当前时间：2026-09-01T15:21:50Z（与 goform 侧同一个常量）。 */
        const val FIXED_MILLIS = 1_788_276_110_000L

        /**
         * 与 goform 侧 `GoformSmsSendParamsTest` 硬编码的 `sms_time`（`26;09;01;23;19;39;+8`）
         * 对应的时刻 = 2026-09-01T15:19:39Z，比 [FIXED_MILLIS] 早 131 秒。
         * 那边的测试直接传字符串（因为它测的是"原样透传"），这边必须真算一遍，
         * 才能证明两侧最终发出的 `sms_time` 一致。
         */
        const val SEND_MILLIS = 1_788_275_979_000L

        /** 2026-12-31T16:00:00Z —— GMT+8 下已跨到 2027-01-01 00:00:00。 */
        const val NEW_YEAR_MILLIS = 1_798_732_800_000L
    }
}
