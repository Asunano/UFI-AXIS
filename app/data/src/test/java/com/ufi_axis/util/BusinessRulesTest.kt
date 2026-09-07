package com.ufi_axis.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F16 — 业务规则纯函数单测（阈值 / 流量聚合 / 在线离线判定）。
 *
 * 被测纯函数位于 [FormatUtils]（部分由调用方抽取而来，调用方签名不变）：
 *  - [FormatUtils.formatBytes]        ：字节格式化，边界含 TB 溢出（原实现仅到 GB）。
 *  - [FormatUtils.monthlyTrafficTotal]：月度流量聚合（rx + tx）。
 *  - [FormatUtils.isTrafficAlertExceeded]：阈值告警判定（边界值）。
 *  - [FormatUtils.isCellularConnected]：ZTE ppp_status 在线/离线判定。
 */
class BusinessRulesTest {

    // ========== formatBytes：TB 溢出（原实现上限为 GB，会显示超大 GB 数字） ==========

    @Test
    fun `formatBytes - exactly 1 TB boundary`() {
        assertEquals("1.0 TB", FormatUtils.formatBytes(1099511627776L)) // 1024 GB
    }

    @Test
    fun `formatBytes - just below 1 TB stays GB`() {
        assertEquals("1024.0 GB", FormatUtils.formatBytes(1099511627776L - 1))
    }

    @Test
    fun `formatBytes - over 1 TB`() {
        assertEquals("1.5 TB", FormatUtils.formatBytes((1099511627776L * 1.5).toLong()))
    }

    @Test
    fun `formatBytes - PB fallback for huge values`() {
        assertEquals("1.0 PB", FormatUtils.formatBytes(1099511627776L * 1024L))
    }

    // ========== 流量聚合 ==========

    @Test
    fun `monthlyTrafficTotal aggregates rx and tx`() {
        assertEquals(150L, FormatUtils.monthlyTrafficTotal(100, 50))
        assertEquals(0L, FormatUtils.monthlyTrafficTotal(0, 0))
        // 大数不溢出（Long 足以容纳 TB 级累计）
        assertEquals(123456789012L + 9876543210L,
            FormatUtils.monthlyTrafficTotal(123456789012L, 9876543210L))
    }

    // ========== 阈值判定（边界） ==========

    @Test
    fun `isTrafficAlertExceeded - exactly at alert percent triggers`() {
        // 80GB 已用 / 100GB 限额，alert=80% → 恰好达到 → 触发
        assertTrue(FormatUtils.isTrafficAlertExceeded(usedBytes = 80L * 1024 * 1024 * 1024,
            limitBytes = 100L * 1024 * 1024 * 1024, alertPercent = 80))
    }

    @Test
    fun `isTrafficAlertExceeded - just below alert percent does not trigger`() {
        assertFalse(FormatUtils.isTrafficAlertExceeded(usedBytes = 79L * 1024 * 1024 * 1024,
            limitBytes = 100L * 1024 * 1024 * 1024, alertPercent = 80))
    }

    @Test
    fun `isTrafficAlertExceeded - no limit configured never triggers`() {
        assertFalse(FormatUtils.isTrafficAlertExceeded(usedBytes = Long.MAX_VALUE,
            limitBytes = 0, alertPercent = 1))
    }

    // ========== 在线/离线判定（ppp_status） ==========

    @Test
    fun `isCellularConnected - ppp_connected is online`() {
        assertTrue(FormatUtils.isCellularConnected("ppp_connected"))
    }

    @Test
    fun `isCellularConnected - ppp_disconnected is offline`() {
        assertFalse(FormatUtils.isCellularConnected("ppp_disconnected"))
    }

    @Test
    fun `isCellularConnected - empty or unknown is offline`() {
        assertFalse(FormatUtils.isCellularConnected(""))
        assertFalse(FormatUtils.isCellularConnected("ppp_connecting"))
    }
}
