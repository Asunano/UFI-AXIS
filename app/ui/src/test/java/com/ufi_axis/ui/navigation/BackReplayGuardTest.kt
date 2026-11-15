package com.ufi_axis.ui.navigation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「被吞下的返回要不要补发」的判据。
 *
 * 守的是 2026-11-15 那个线上 bug：二级页连按两次返回会**直接结束 App**。
 *
 * 机制：返回连击守卫把转场窗口内的返回吞进 `pendingBack`，落定后补发一次
 * `popBackStack()`。但如果转场窗口内那第二下已被 NavHost 自己处理掉（目的地已经变了），
 * 补发就会**多退一层** —— MAIN 被退掉、返回栈清空，再按一次系统返回直接 finish。
 */
class BackReplayGuardTest {

    @Test
    fun `仍停在被吞的那一页时补发`() {
        assertTrue(
            ufiBackReplayAllowed("detail/cellular-advanced", "detail/cellular-advanced")
        )
    }

    @Test
    fun `期间已经返回过了就不再补发`() {
        // 人已经回到网络页：此时再 pop 一次 = 多退一层
        assertFalse(ufiBackReplayAllowed("detail/cellular-advanced", Routes.NETWORK))
    }

    @Test
    fun `在 Tab 宿主上吞下的返回一律不补发`() {
        assertFalse(ufiBackReplayAllowed(Routes.MAIN, Routes.MAIN))
    }

    @Test
    fun `目的地未知时不补发`() {
        assertFalse(ufiBackReplayAllowed(null, "detail/x"))
        assertFalse(ufiBackReplayAllowed("detail/x", null))
        assertFalse(ufiBackReplayAllowed(null, null))
    }

    @Test
    fun `换个二级页也不补发`() {
        // 人从 A 页退掉又进了 B 页：这次补发会把他从 B 页弹出去
        assertFalse(ufiBackReplayAllowed("detail/a", "detail/b"))
    }
}
