package com.ufi_axis.viewmodel.state

import com.ufi_axis.data.model.UpdateStatusResponse
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * core 更新段的两个派生状态：进行中 / 可取消。
 *
 * 直接决定弹窗给不给「取消」、内容区换不换成进度环 —— 判错了要么是假按钮，
 * 要么把「安装中」也当成可取消（打断安装会把设备留在装了半个包的状态）。
 */
class UpdatePromptCoreRunningTest {

    private fun state(triggered: Boolean, coreState: String?) = UpdatePromptState(
        coreTriggered = triggered,
        coreStatus = coreState?.let { UpdateStatusResponse(state = it) }
    )

    @Test
    fun `下载与校验算进行中 也允许取消`() {
        listOf("downloading", "verifying", "DOWNLOADING").forEach { s ->
            val st = state(triggered = true, coreState = s)
            assertTrue("$s 应为进行中", st.coreRunning)
            assertTrue("$s 应可取消", st.coreCancelable)
        }
    }

    @Test
    fun `安装中算进行中 但不允许取消`() {
        val st = state(triggered = true, coreState = "installing")
        assertTrue(st.coreRunning)
        assertFalse("安装开始后不能取消", st.coreCancelable)
    }

    @Test
    fun `终态不再算进行中`() {
        listOf("done", "failed", "idle", "need_push").forEach { s ->
            val st = state(triggered = true, coreState = s)
            assertFalse("$s 不应算进行中", st.coreRunning)
            assertFalse("$s 不应可取消", st.coreCancelable)
        }
    }

    @Test
    fun `没触发过就谈不上进行中`() {
        val st = state(triggered = false, coreState = "downloading")
        assertFalse(st.coreRunning)
        assertFalse(st.coreCancelable)
    }

    @Test
    fun `触发了但还没读到状态 不算进行中（避免一上来就闪进度环）`() {
        val st = state(triggered = true, coreState = null)
        assertFalse(st.coreRunning)
        assertFalse(st.coreCancelable)
    }
}
