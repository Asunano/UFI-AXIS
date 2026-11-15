package com.ufi_axis.ui.components.common

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 弹窗串行闸。
 *
 * 守的是 2026-11-15 那个现象：App 与 Core 同时有更新时，Core 弹窗关闭的一瞬间
 * App 弹窗顶上来，两个 Dialog 窗口叠在同一帧，后者的窗口级 backdrop 写进正在销毁的窗口
 * ⇒ 第二个弹窗背景是花的。
 */
class UfiDialogArbiterTest {

    private val first = Any()
    private val second = Any()

    @After
    fun tearDown() {
        // 单例状态是进程级的，别把占用留给别的用例
        UfiDialogArbiter.release(first)
        UfiDialogArbiter.release(second)
    }

    @Test
    fun `没有占用时第一个弹窗能拿到`() {
        assertTrue(UfiDialogArbiter.tryAcquire(first))
    }

    @Test
    fun `第二个弹窗必须排队`() {
        assertTrue(UfiDialogArbiter.tryAcquire(first))
        assertFalse("前一个还在场时，后一个不许显示", UfiDialogArbiter.tryAcquire(second))
    }

    @Test
    fun `持有者重复申请是幂等的`() {
        assertTrue(UfiDialogArbiter.tryAcquire(first))
        assertTrue("同一个弹窗重复重组不该被自己的占用挡住", UfiDialogArbiter.tryAcquire(first))
    }

    @Test
    fun `非持有者释放无效`() {
        UfiDialogArbiter.tryAcquire(first)
        UfiDialogArbiter.release(second)
        assertFalse("别人释放不该解开闸门", UfiDialogArbiter.tryAcquire(second))
    }

    @Test
    fun `持有者离场后第二个才能进来`() {
        UfiDialogArbiter.tryAcquire(first)
        UfiDialogArbiter.release(first)
        assertTrue(UfiDialogArbiter.tryAcquire(second))
    }

    @Test
    fun `释放会推进 generation（排队者据此重试）`() {
        UfiDialogArbiter.tryAcquire(first)
        val before = UfiDialogArbiter.generation
        UfiDialogArbiter.release(first)
        assertEquals(before + 1, UfiDialogArbiter.generation)
        // 非持有者释放不推进：否则排队者会被无意义地反复唤醒
        UfiDialogArbiter.tryAcquire(first)
        val mid = UfiDialogArbiter.generation
        UfiDialogArbiter.release(second)
        assertEquals(mid, UfiDialogArbiter.generation)
    }
}
