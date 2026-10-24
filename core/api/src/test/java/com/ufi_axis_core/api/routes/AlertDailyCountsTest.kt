package com.ufi_axis_core.api.routes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * `daily_counts` 的区间解析（2026-10-23）。
 *
 * 只测这条接口唯一的业务分支：参数校验与区间构造。SQL 分组正确性由手工验证，
 * 不为它单独搭 Room 测试基建（见 DailyCounts 计划）。
 */
class AlertDailyCountsTest {

    @Test
    fun `missing start is rejected`() {
        assertNull(parseDailyCountsRange(null, "2000"))
    }

    @Test
    fun `missing end is rejected`() {
        assertNull(parseDailyCountsRange("1000", null))
    }

    @Test
    fun `non numeric values are rejected`() {
        assertNull(parseDailyCountsRange("abc", "2000"))
        assertNull(parseDailyCountsRange("1000", ""))
    }

    @Test
    fun `end must be strictly after start`() {
        assertNull(parseDailyCountsRange("2000", "2000"))
        assertNull(parseDailyCountsRange("2000", "1000"))
    }

    @Test
    fun `valid range is parsed`() {
        assertEquals(1000L to 2000L, parseDailyCountsRange("1000", "2000"))
        // 前后空白容忍（query 参数常带空格）
        assertEquals(1000L to 2000L, parseDailyCountsRange(" 1000 ", "2000 "))
    }
}
