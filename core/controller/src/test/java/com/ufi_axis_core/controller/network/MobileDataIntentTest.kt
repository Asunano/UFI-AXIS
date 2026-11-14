package com.ufi_axis_core.controller.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 「移动数据开关」意图状态机的边界（2026-11-02）。
 *
 * 被修的用户可见问题是：切完制式，web 与 app 上的移动数据开关显示「关闭」，实际是开的，
 * 过一会儿刷新又变回来。根因是那个字段直接取 `ppp_status`（链路态），而切制式必然先拆链。
 *
 * 这里钉的规则（错一条就会出一个方向的假状态）：
 *   1. 链路通 ⇒ 开关开着（顺手把厂商 UI 里的外部改动拉回来）；
 *   2. 显式写优先 —— 刚点「关」的几秒里链路还没拆完，不能据此翻回开；
 *   3. **已知会拆链的操作**（切制式 / 锁频重启网络栈 / 拨号）有 120s 豁免窗口，
 *      窗口内断链一律按开报 —— 这是用户 2026-11-02 明确要求的「豁免」；
 *   4. **来路不明**的断链只容忍 30s，超时就认定确实关了（不能把厂商 UI 手动断开藏两分钟）；
 *   5. 链路读不到 ≠ 断：不能因为一次查询超时就把开关报成关。
 */
class MobileDataIntentTest {

    /** 可推进的假时钟，单位 ms。 */
    private var now = 0L
    private fun intent() = MobileDataIntent { now }

    @Test
    fun `首次判断按链路初始化 - 通为开，断为关`() {
        assertEquals(true, intent().resolve(true, 0))
        assertEquals(false, intent().resolve(false, 0))
    }

    @Test
    fun `链路读不到时回不知道 - 不能当关`() {
        // 进程内第一次判断、链路也读不到：交给调用方退回 shell 判断，不要编一个 false
        assertNull(intent().resolve(null, 0))
    }

    @Test
    fun `切制式的豁免窗口内，断链不视作关闭`() {
        val state = intent()
        assertEquals(true, state.resolve(true, 0))      // 切换前：连着，开关开
        now = 1_000
        state.beginLinkTransition()                     // 下发 SET_BEARER_PREFERENCE
        now = 2_000
        assertEquals("设备开始拆链", true, state.resolve(false))
        now = 30_000
        assertEquals("重新注册中", true, state.resolve(false))
        now = 120_000
        assertEquals("豁免窗口内（截止前 1s）", true, state.resolve(false))
        now = 121_001
        assertEquals("窗口已过、断链也没恢复 —— 才认定是关", false, state.resolve(false))
    }

    @Test
    fun `没有已知拆链操作时，断链 30s 就收敛为关`() {
        val state = intent()
        assertEquals(true, state.resolve(true, 0))
        now = 10_000
        assertEquals("刚断，先当抖动", true, state.resolve(false))
        now = 39_000
        assertEquals("29s，仍在容忍内", true, state.resolve(false))
        now = 40_001
        assertEquals("超过 30s：认定确实关了", false, state.resolve(false))
    }

    @Test
    fun `豁免窗口只延长不缩短`() {
        val state = intent()
        state.resolve(true, 0)
        now = 0
        state.beginLinkTransition()
        now = 100_000
        state.beginLinkTransition()          // 第二次操作把截止时刻推到 220s
        now = 150_000
        assertEquals("第一次的窗口已过，但第二次的还在", true, state.resolve(false))
    }

    @Test
    fun `显式打开数据自带豁免 - 拨号期间不显示关`() {
        val state = intent()
        state.onExplicitWrite(true)          // 用户点了开
        now = 500
        assertEquals("刚点开、还在拨号", true, state.resolve(false))
        now = 60_000
        assertEquals("弱信号下拨了 1 分钟", true, state.resolve(false))
        now = 121_000
        assertEquals("豁免到期仍未拨上", false, state.resolve(false))
    }

    @Test
    fun `刚点关的几秒里链路还通 - 不能翻回开`() {
        val state = intent()
        state.onExplicitWrite(true)
        now = 1_000
        state.onExplicitWrite(false)
        now = 5_000
        assertEquals("设备拆链还没走完", false, state.resolve(true))
        // 拆链窗口过去后链路若仍通，说明那次关没生效，按链路纠正
        now = MobileDataIntent.WRITE_SETTLE_MS + 1_000
        assertEquals(true, state.resolve(true))
    }

    @Test
    fun `关掉之后链路断 - 保持关`() {
        val state = intent()
        state.onExplicitWrite(false)
        now = 2_000
        assertEquals(false, state.resolve(false))
        now = 200_000
        assertEquals(false, state.resolve(false))
    }

    @Test
    fun `显式关会清掉之前打开的豁免窗口`() {
        val state = intent()
        state.onExplicitWrite(true)          // 开：带 120s 豁免
        now = 1_000
        state.onExplicitWrite(false)         // 立刻又关：豁免必须作废，否则会多显示两分钟开
        now = 2_000
        assertEquals(false, state.resolve(false))
    }

    @Test
    fun `链路读不到时保留已知意图`() {
        val state = intent()
        state.onExplicitWrite(true)
        now = 30_000
        assertEquals(true, state.resolve(null))
        state.onExplicitWrite(false)
        now = 60_000
        assertEquals(false, state.resolve(null))
    }

    @Test
    fun `豁免窗口内链路恢复 - 开关保持开`() {
        val state = intent()
        state.onExplicitWrite(true)
        now = 20_000
        assertEquals(true, state.resolve(false))
        now = 25_000
        assertEquals(true, state.resolve(true))
    }
}
