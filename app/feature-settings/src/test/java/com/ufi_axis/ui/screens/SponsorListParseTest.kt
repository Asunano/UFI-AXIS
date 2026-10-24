package com.ufi_axis.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 赞助名单的**校验**与**排序**（2026-10-14）。
 *
 * 为什么这两条值得单测：它们都是"错了也不会崩、只会静默显示错东西"的纯逻辑 ——
 *  · [parseSponsors] 的规则是**整份丢弃**（不合规的名单必须整块不渲染，而不是渲染半截）；
 *  · [sortedNamedSponsors] 的规则是"远端数组顺序变化时名单不跳"（少了次级键就会跳）。
 * 两者一旦漂移，界面照常渲染，没有任何异常可看。
 *
 * 用例覆盖三层：**真实源文件的形状**（仓库根 sponsors.json）、core 响应形态（无 `version`）、
 * 以及每一条校验规则的**反面**（都必须整份丢弃）。
 */
class SponsorListParseTest {

    /** 仓库根 `sponsors.json` 的真实形状（含 `_note` 这种与本页无关的键，解析必须忽略它）。 */
    private val bundledJson = """
        {
          "_note": "赞助感谢名单（唯一源文件）",
          "version": 1,
          "updatedAt": "2026-10",
          "sponsors": [
            { "name": "云季清", "at": "2026-10" },
            { "name": "阿维和阿虹", "at": "2026-09" },
            { "name": "高*.", "at": "2026-07" }
          ]
        }
    """.trimIndent()

    /** `GET /api/sponsors` 的响应形态：**没有 `version`**（core 在服务端已校验过一次）。 */
    private val remoteJson = """
        {
          "source": "remote",
          "updatedAt": "2026-10",
          "sponsors": [
            { "name": "云季清", "at": "2026-10" },
            { "anonymous": true, "at": "2026-10" }
          ]
        }
    """.trimIndent()

    @Test
    fun `仓库根那份真实形状可以解析`() {
        val entries = parseSponsors(bundledJson, requireVersion = true)!!
        assertEquals(3, entries.size)
        assertEquals("云季清", entries[0].name)
        assertEquals("2026-10", entries[0].at)
        assertEquals("阿维和阿虹", entries[1].name)
        assertEquals("高*.", entries[2].name)
        assertEquals("2026-07", entries[2].at)
        // 2026-10 起真实名单已无匿名条目：匿名解析能力仍由下面 core 响应那条用例覆盖。
        assertTrue("真实名单不应再有匿名条目", entries.none { it.anonymous })
    }

    @Test
    fun `core 响应没有 version 也能解析`() {
        // 这一条是**防回归的关键**：core 的响应不带 version，若这里继续强制要求它，
        // 更新层会永久静默失效（页面照常显示保底名单，没有任何信号）。
        val entries = parseSponsors(remoteJson, requireVersion = false)!!
        assertEquals(2, entries.size)
    }

    @Test
    fun `内置那份缺 version 时整份丢弃`() {
        val withoutVersion = """{ "sponsors": [ { "name": "甲", "at": "2026-10" } ] }"""
        assertNull(parseSponsors(withoutVersion, requireVersion = true))
    }

    @Test
    fun `version 不等于支持值时整份丢弃`() {
        val futureVersion = """{ "version": 2, "sponsors": [ { "name": "甲", "at": "2026-10" } ] }"""
        assertNull(parseSponsors(futureVersion, requireVersion = true))
    }

    @Test
    fun `name 与 anonymous 同时给出时整份丢弃`() {
        val both = """{ "version": 1, "sponsors": [ { "name": "甲", "anonymous": true, "at": "2026-10" } ] }"""
        assertNull(parseSponsors(both, requireVersion = true))
    }

    @Test
    fun `既没有 name 也没有 anonymous 时整份丢弃`() {
        val neither = """{ "version": 1, "sponsors": [ { "at": "2026-10" } ] }"""
        assertNull(parseSponsors(neither, requireVersion = true))
    }

    @Test
    fun `at 缺失或形状对语义错时整份丢弃`() {
        val missingAt = """{ "version": 1, "sponsors": [ { "name": "甲" } ] }"""
        val badMonth = """{ "version": 1, "sponsors": [ { "name": "甲", "at": "2026-13" } ] }"""
        // 月份必须零填充：2026-7 在 JSON 里看着像"七月"，但不匹配 ^\d{4}-(0[1-9]|1[0-2])$，
        // 校验会整份丢弃。2026-10-24 的真实源文件就一度写成 2026-7，这里钉住这个坑。
        val unpaddedMonth = """{ "version": 1, "sponsors": [ { "name": "甲", "at": "2026-7" } ] }"""
        val badDay = """{ "version": 1, "sponsors": [ { "name": "甲", "at": "2026-10-32" } ] }"""
        val slashDate = """{ "version": 1, "sponsors": [ { "name": "甲", "at": "2026/10" } ] }"""
        assertNull(parseSponsors(missingAt, requireVersion = true))
        assertNull(parseSponsors(badMonth, requireVersion = true))
        assertNull(parseSponsors(unpaddedMonth, requireVersion = true))
        assertNull(parseSponsors(badDay, requireVersion = true))
        assertNull(parseSponsors(slashDate, requireVersion = true))
    }

    @Test
    fun `at 允许 YYYY-MM 与 YYYY-MM-DD 两种写法`() {
        val month = """{ "version": 1, "sponsors": [ { "name": "甲", "at": "2026-10" } ] }"""
        val day = """{ "version": 1, "sponsors": [ { "name": "甲", "at": "2026-10-14" } ] }"""
        assertEquals(1, parseSponsors(month, requireVersion = true)!!.size)
        assertEquals(1, parseSponsors(day, requireVersion = true)!!.size)
    }

    @Test
    fun `name 为空或超过 32 字时整份丢弃`() {
        val blank = """{ "version": 1, "sponsors": [ { "name": "   ", "at": "2026-10" } ] }"""
        val tooLong = """{ "version": 1, "sponsors": [ { "name": "${"甲".repeat(33)}", "at": "2026-10" } ] }"""
        val justRight = """{ "version": 1, "sponsors": [ { "name": "${"甲".repeat(32)}", "at": "2026-10" } ] }"""
        assertNull(parseSponsors(blank, requireVersion = true))
        assertNull(parseSponsors(tooLong, requireVersion = true))
        assertEquals(1, parseSponsors(justRight, requireVersion = true)!!.size)
    }

    @Test
    fun `sponsors 不是数组或顶层不是对象时整份丢弃`() {
        assertNull(parseSponsors("""{ "version": 1, "sponsors": {} }""", requireVersion = true))
        assertNull(parseSponsors("""[ { "name": "甲", "at": "2026-10" } ]""", requireVersion = true))
        assertNull(parseSponsors("不是 JSON", requireVersion = true))
    }

    @Test
    fun `空名单是合法的空列表而不是丢弃`() {
        // 与"不合规"必须分开：空名单 = 这一页只画赞赏码（不画"暂无赞助者"）；
        // null = 整块不渲染、并保留上一层。两者的界面表现相近，但对"要不要用这一层"的结论相反。
        val empty = """{ "version": 1, "sponsors": [] }"""
        assertEquals(0, parseSponsors(empty, requireVersion = true)!!.size)
    }

    @Test
    fun `排序是 at 倒序 同级按 name 升序`() {
        val sorted = sortedNamedSponsors(
            listOf(
                SponsorEntry("乙", anonymous = false, at = "2026-10"),
                SponsorEntry("甲", anonymous = false, at = "2026-10"),
                SponsorEntry("丙", anonymous = false, at = "2026-09"),
                SponsorEntry("", anonymous = true, at = "2026-12"),
            )
        )
        // 匿名那条 `at` 最新，但它**不参与排序、也不出现在结果里**。
        // 同级（2026-10）的 乙 / 甲 按码位升序：乙(U+4E59) < 甲(U+7532) —— 见 sortedNamedSponsors
        // 的注释：次级键刻意不用 Collator（那会让顺序随设备 Locale 变，与"稳定"的目的相反）。
        assertEquals(listOf("乙", "甲", "丙"), sorted.map { it.name })
    }

    @Test
    fun `同级 name 升序让远端数组顺序变化时名单不跳`() {
        val a = listOf(
            SponsorEntry("甲", false, "2026-10"),
            SponsorEntry("乙", false, "2026-10"),
        )
        assertEquals(sortedNamedSponsors(a), sortedNamedSponsors(a.reversed()))
    }
}
