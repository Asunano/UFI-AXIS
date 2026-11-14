package com.ufi_axis.data.api

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 主人密码桥（`OwnerPasswordGate`）的行为测试。
 *
 * 这套用例盯的是**打扰次数**，不是安全强度：
 *
 * 1. 三个请求同时撞上 401 时只能弹**一个**框 —— 弹三个框比不让操作更烦人；
 * 2. 成功过一次之后同一会话不再重复问（连续做几个破坏性操作只答一次）；
 * 3. 取消不写缓存 —— 用户手滑关掉弹窗，不该把上次的正确密码顶掉；
 * 4. 改过密码（core 回 `INVALID_PASSWORD`）后缓存被清，下一次重新问。
 *
 * 安全靠的是 core 侧的 `OwnerGate`（密码 + 节流 + 5 次锁定），这里只管"别烦人"。
 */
class OwnerPasswordGateTest {

    @After
    fun tearDown() {
        OwnerPasswordGate.resetForTests()
    }

    @Test
    fun `勾选记住后同一会话不再弹框`() = runTest {
        // 第一次：有 prompt，答复后拿到密码
        val first = async { OwnerPasswordGate.obtain(null) }
        yield()
        assertNotNull("首次应当弹框", OwnerPasswordGate.prompt.value)
        OwnerPasswordGate.submit("secret123", remember = true)
        assertEquals("secret123", first.await())

        // 第二次：prompt 不再置位，直接返回缓存
        val second = async { OwnerPasswordGate.obtain(null) }
        yield()
        assertNull("已有缓存不该再弹框", OwnerPasswordGate.prompt.value)
        assertEquals("secret123", second.await())
    }

    @Test
    fun `默认不记住密码，下一次仍弹框`() = runTest {
        val first = async { OwnerPasswordGate.obtain(null) }
        yield()
        // 不勾选「5 分钟内不再询问」
        OwnerPasswordGate.submit("secret123", remember = false)
        assertEquals("secret123", first.await())

        val second = async { OwnerPasswordGate.obtain(null) }
        yield()
        assertNotNull("没勾选就不该缓存，必须再问一次", OwnerPasswordGate.prompt.value)
        OwnerPasswordGate.submit(null)
        second.await()
    }

    @Test
    fun `勾选记住后缓存 5 分钟到期失效`() = runTest {
        val first = async { OwnerPasswordGate.obtain(null) }
        yield()
        OwnerPasswordGate.submit("secret123", remember = true)
        assertEquals("secret123", first.await())

        // 有效期内：直接复用
        val second = async { OwnerPasswordGate.obtain(null) }
        yield()
        assertNull(OwnerPasswordGate.prompt.value)
        assertEquals("secret123", second.await())

        // 超过 5 分钟：重新问
        OwnerPasswordGate.advanceClockForTests(OwnerPasswordGate.CACHE_TTL_MS + 1_000)
        val third = async { OwnerPasswordGate.obtain(null) }
        yield()
        assertNotNull("缓存过期后必须重新询问", OwnerPasswordGate.prompt.value)
        OwnerPasswordGate.submit(null)
        third.await()
    }

    @Test
    fun `并发请求只弹一个框，一次输入答复全部`() = runTest {
        val a = async { OwnerPasswordGate.obtain(null) }
        val b = async { OwnerPasswordGate.obtain(null) }
        val c = async { OwnerPasswordGate.obtain(null) }
        yield()

        assertNotNull(OwnerPasswordGate.prompt.value)
        OwnerPasswordGate.submit("secret123")

        assertEquals("secret123", a.await())
        assertEquals("secret123", b.await())
        assertEquals("secret123", c.await())
    }

    @Test
    fun `取消不写缓存，下一次仍会弹框`() = runTest {
        val first = async { OwnerPasswordGate.obtain(null) }
        yield()
        OwnerPasswordGate.submit(null)
        assertNull("取消应返回 null", first.await())

        val second = async { OwnerPasswordGate.obtain(null) }
        yield()
        assertNotNull("取消后下一次仍该弹框", OwnerPasswordGate.prompt.value)
        OwnerPasswordGate.submit("secret123")
        assertEquals("secret123", second.await())
    }

    @Test
    fun `空密码按取消处理`() = runTest {
        val d = async { OwnerPasswordGate.obtain(null) }
        yield()
        OwnerPasswordGate.submit("   ")
        assertNull(d.await())
        assertNull(OwnerPasswordGate.prompt.value)
    }

    @Test
    fun `clear 之后重新弹框`() = runTest {
        val first = async { OwnerPasswordGate.obtain(null) }
        yield()
        OwnerPasswordGate.submit("secret123")
        assertEquals("secret123", first.await())

        // 模拟 core 回 INVALID_PASSWORD（改过密码）
        OwnerPasswordGate.clear()

        val second = async { OwnerPasswordGate.obtain(null) }
        yield()
        assertNotNull("清缓存后应重新弹框", OwnerPasswordGate.prompt.value)
        OwnerPasswordGate.submit("newpass")
        assertEquals("newpass", second.await())
    }

    @Test
    fun `hint 用 core 的文案，空则回落默认`() = runTest {
        val d = async { OwnerPasswordGate.obtain("配对密码已变更，请重新输入") }
        yield()
        assertEquals("配对密码已变更，请重新输入", OwnerPasswordGate.prompt.value?.hint)
        OwnerPasswordGate.submit(null)
        d.await()

        val e = async { OwnerPasswordGate.obtain("   ") }
        yield()
        assertTrue(
            "空白 hint 应回落到默认文案",
            OwnerPasswordGate.prompt.value?.hint?.contains("主人密码保护") == true
        )
        OwnerPasswordGate.submit(null)
        e.await()
    }

    @Test
    fun `resetForTests 收掉悬挂的等待者`() = runTest {
        val d = CompletableDeferred<String?>()
        val pending = async { OwnerPasswordGate.obtain(null) }
        yield()
        OwnerPasswordGate.resetForTests()
        assertNull(OwnerPasswordGate.prompt.value)
        // 悬挂的调用方会被 complete(null) 放走，不会把后续用例卡死
        assertNull(pending.await())
        d.complete(null)
    }
}
