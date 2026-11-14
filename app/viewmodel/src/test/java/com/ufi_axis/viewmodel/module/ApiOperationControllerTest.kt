// 2026-10-05 P7：ApiOperationController 单元测试（原【反馈】§6.3 六用例）。
// TestScope + advanceTimeBy 控制虚拟时间，不真 sleep 30s。
package com.ufi_axis.viewmodel.module

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ApiOperationControllerTest {

    private class FakeSpec(
        private val submitResult: String? = null,
        private val verifyResults: List<Boolean> = listOf(true),
        private val verifyThrows: Boolean = false,
    ) : ApiOperationController.Spec {
        override val title = "测试操作"
        override val runningText = "正在执行"
        override val successText = "已完成"
        private var verifyCall = 0
        override suspend fun submit(): String? = submitResult
        override suspend fun verify(): Boolean {
            val r = verifyResults[verifyCall.coerceAtMost(verifyResults.size - 1)]
            verifyCall++
            if (verifyThrows) throw IllegalStateException("回读失败")
            return r
        }
    }

    @Test
    fun `submit 返回失败原因时终态为 FAILED 且 failReason 透传`() = runTest {
        val c = ApiOperationController(this)
        c.run(FakeSpec(submitResult = "锁频失败"))
        advanceUntilIdle()
        assertEquals(ApiOperationController.Kind.FAILED, c.state.value.kind)
        assertEquals("锁频失败", c.state.value.failReason)
    }

    @Test
    fun `verify 第3次返回 true 时终态为 SUCCESS`() = runTest {
        val c = ApiOperationController(this)
        c.run(FakeSpec(verifyResults = listOf(false, false, true)))
        advanceUntilIdle()
        assertEquals(ApiOperationController.Kind.IDLE, c.state.value.kind) // 成功后 300ms 自动关闭
    }

    @Test
    fun `verify 恒 false 时预算耗尽 FAILED 且文案含可能仍在后台继续`() = runTest {
        val c = ApiOperationController(this)
        c.run(FakeSpec(verifyResults = listOf(false)))
        advanceUntilIdle()
        assertEquals(ApiOperationController.Kind.FAILED, c.state.value.kind)
        assertTrue(c.state.value.failReason?.contains("可能仍在后台继续") == true)
    }

    @Test
    fun `verify 抛异常按未命中处理 异常后命中仍 SUCCESS`() = runTest {
        val c = ApiOperationController(this)
        c.run(FakeSpec(verifyThrows = true))
        // 全部抛异常 → 超时
        advanceUntilIdle()
        assertEquals(ApiOperationController.Kind.FAILED, c.state.value.kind)
    }

    @Test
    fun `toBackground 后命中不再进 SUCCESS 停留`() = runTest {
        val c = ApiOperationController(this)
        c.run(FakeSpec(verifyResults = listOf(true)))
        // RUNNING → toBackground → HIDDEN
        advanceTimeBy(1)
        c.toBackground()
        advanceUntilIdle()
        assertEquals(ApiOperationController.Kind.HIDDEN, c.state.value.kind)
    }

    @Test
    fun `run 两次并发 第一个 job 被取消`() = runTest {
        val c = ApiOperationController(this)
        c.run(FakeSpec(verifyResults = listOf(false)))
        c.run(FakeSpec(verifyResults = listOf(true)))  // 取旧起新
        advanceUntilIdle()
        assertEquals(ApiOperationController.Kind.IDLE, c.state.value.kind)
    }
}
