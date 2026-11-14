package com.ufi_axis_core.core.scheduler

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * 守门测试：[DataScheduler] 里两处 `withTimeout` 的 **catch 顺序**不许写反。
 *
 * ## 这条顺序为什么值得一条测试
 *
 * `TimeoutCancellationException` 是 `CancellationException` 的**子类**，而 Kotlin
 * **不会**因为「子类 catch 写在父类后面」报错（Java 会报 `exception has already been caught`，
 * Kotlin 只是让后面那条永远不命中）。所以写反之后编译器一声不响，行为却整体变样：
 *
 *  - 顺序正确（超时在前）：`withTimeout` 的超时 ⇒ 本轮按「没数据」处理；外部取消 ⇒ 原样抛出，
 *    `stop()` 能等到子协程真正结束；
 *  - 顺序写反（取消在前）：**超时也会被当成外部取消抛出去**，把整条调度循环打断 ——
 *    而超时本该只是「这一轮少一份数据」。
 *
 * 2026-09-26 补 `CancellationException` 重抛时踩的就是这个点，所以顺序钉在测试里。
 *
 * ## 已知局限（不要指望它做行为验证）
 *
 * 本类**扫源码**，不跑 [DataScheduler]。后者的构造签名吃 10+ 个协作者（Room 数据库、
 * WebSocket、PlatformAdapter…），`checkDeviceEvents` / `collectSignal` 又都是 private，
 * 裸 JVM 单测里既构造不出来也调不到 —— 强行造一份"等价实现"来跑，测到的是复制品而不是生产代码。
 * 所以这里只守**顺序**这一条能从源码上判死的性质，行为侧由真机验证兜（日志关键字见提交说明）。
 */
class CancellationOrderGuardTest {

    /** 期望的三段式：超时 → 真取消 → 其它异常。 */
    private val expected = listOf("TimeoutCancellationException", "CancellationException", "Exception")

    private fun repoRoot(): File? {
        var dir: File? = File("").absoluteFile
        repeat(8) {
            val d = dir ?: return null
            if (File(d, "settings.gradle.kts").isFile) return d
            dir = d.parentFile
        }
        return null
    }

    private fun dataSchedulerSource(): String? {
        val root = repoRoot() ?: return null
        val f = File(
            root,
            "core/scheduler/src/main/java/com/ufi_axis_core/core/scheduler/DataScheduler.kt"
        )
        return if (f.isFile) f.readText() else null
    }

    /**
     * 按出现顺序取出 `catch (x: Type)` 里的 `Type`。
     *
     * 只认真正的 catch 子句形状，所以注释里提到这些类名（本批补的注释里提了好几次）不会误算。
     */
    private fun catchTypes(region: String): List<String> =
        Regex("""catch \((?:_|e): (\w+)\)""").findAll(region).map { it.groupValues[1] }.toList()

    @Test
    fun `checkDeviceEvents 的 catch 顺序是 超时 - 取消 - 其它`() {
        val src = dataSchedulerSource()
        assumeTrue("找不到 DataScheduler.kt（换构建方式了？），跳过源码扫描", src != null)

        val start = src!!.indexOf("private suspend fun checkDeviceEvents()")
        val end = src.indexOf("private suspend fun collectSignal()")
        assertTrue("定位不到 checkDeviceEvents / collectSignal（被改名了？）", start in 0 until end)

        assertEquals(
            "TimeoutCancellationException 必须排在 CancellationException 之前：" +
                "写反会把 withTimeout(8s) 的超时当成外部取消抛出去，打断整条分钟级调度循环",
            expected,
            catchTypes(src.substring(start, end)),
        )
    }

    @Test
    fun `collectSignal 取 goform 数据那段的 catch 顺序是 超时 - 取消 - 其它`() {
        val src = dataSchedulerSource()
        assumeTrue("找不到 DataScheduler.kt（换构建方式了？），跳过源码扫描", src != null)

        val start = src!!.indexOf("val goformData = try {")
        assertTrue("定位不到 collectSignal 里取 goform 数据那段（被改写了？）", start >= 0)

        assertEquals(
            "同 checkDeviceEvents：写反会把 withTimeout(5s) 的超时当成外部取消抛出去，" +
                "打断整条秒级采集循环",
            expected,
            catchTypes(src.substring(start)).take(expected.size),
        )
    }
}
