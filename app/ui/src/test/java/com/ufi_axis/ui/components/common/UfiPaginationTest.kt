package com.ufi_axis.ui.components.common

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 分页口径纯函数护栏（[ufiPageCount] / [ufiClampPage]）。
 *
 * 这两个函数是全站分页的唯一口径来源：监控页事件中心、总览 Tab、聚合明细弹窗都调它们。
 * 下面除了边界用例，还钉了两条 2026-09-04 真实 bug 的回归。
 */
class UfiPaginationTest {

    // ===== ufiPageCount：向上取整 + 空/非法输入返回 0 =====

    @Test
    fun `整除时页数为商`() {
        assertEquals(5, ufiPageCount(100, 20))
    }

    @Test
    fun `有余数时向上取整`() {
        assertEquals(6, ufiPageCount(101, 20))
    }

    @Test
    fun `不足一页也算一页`() {
        assertEquals(1, ufiPageCount(3, 20))
    }

    /** 0 条必须是 0 页而不是 1 页：调用点用 `pageCount > 1` 判分页栏可见性，空列表不该出现 "1 / 1"。 */
    @Test
    fun `零条数据返回零页`() {
        assertEquals(0, ufiPageCount(0, 20))
    }

    /** pageSize=0 会让 `(n + size - 1) / size` 直接除零崩溃，这里必须早退。 */
    @Test
    fun `每页条数为零时返回零页`() {
        assertEquals(0, ufiPageCount(100, 0))
    }

    @Test
    fun `负数输入返回零页`() {
        assertEquals(0, ufiPageCount(-1, 20))
        assertEquals(0, ufiPageCount(100, -20))
        assertEquals(0, ufiPageCount(-1, -20))
    }

    // ===== ufiClampPage：夹进 [0, pageCount - 1] =====

    @Test
    fun `范围内页码保持不变`() {
        assertEquals(3, ufiClampPage(3, 10))
        assertEquals(0, ufiClampPage(0, 10))
        assertEquals(9, ufiClampPage(9, 10))
    }

    @Test
    fun `超出上界夹到最后一页`() {
        assertEquals(9, ufiClampPage(42, 10))
    }

    @Test
    fun `负页码夹到第一页`() {
        assertEquals(0, ufiClampPage(-5, 10))
    }

    /** pageCount=0（空列表）时没有"最后一页"可夹，只能落回 0，且不能算出 -1。 */
    @Test
    fun `零页时返回第一页`() {
        assertEquals(0, ufiClampPage(7, 0))
        assertEquals(0, ufiClampPage(0, 0))
        assertEquals(0, ufiClampPage(-3, -1))
    }

    // ===== 回归用例 =====

    /**
     * 回归：聚合/明细口径错配。
     *
     * 守的是这个 bug —— 事件中心明细 500 条（每页 20 → 25 页），按类型聚合后只有 6 行（1 页），
     * 但分页栏/跳页弹窗/可见性判定全部消费明细口径的页数。于是聚合模式下分页栏仍显示 "1 / 25"、
     * 能翻到第 25 页，而聚合切片从第 2 页起恒为空 → 渲染出空白（连空态提示都没有，
     * 因为空态判据用的也是明细切片，它非空）。
     *
     * 正确行为：两种模式各自用自己的行数算页数，切模式时页码必须按新口径夹回去 ——
     * 从明细第 12 页（index 11）切到只有 1 页的聚合视图，必须落回第 1 页（index 0）。
     */
    @Test
    fun `回归_聚合与明细页数口径互不污染且切模式落回首页`() {
        val detailPageCount = ufiPageCount(500, 20)
        val aggregatePageCount = ufiPageCount(6, 20)
        assertEquals(25, detailPageCount)
        assertEquals(1, aggregatePageCount)
        assertEquals(0, ufiClampPage(11, aggregatePageCount))
    }

    /**
     * 回归：列表缩小导致页码越界。
     *
     * 守的是这个 bug —— 用户停在第 25 页时轮询刷新/删除告警把行数砍到只剩 3 页，
     * `currentPage` 留在 24 → 切片为空、列表空白，同时分页栏还可能因页数变化而隐藏，
     * 用户被困在空页里翻不回来。任何 pageCount 变化都要过一次夹取。
     */
    @Test
    fun `回归_列表缩小后页码夹回最后一页`() {
        assertEquals(2, ufiClampPage(24, 3))
    }
}
