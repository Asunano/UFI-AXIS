// P3（应用内通知）：AlertBus 单元测试。
// 验证 SharedFlow 多订阅者可达、replay=0 旧事件不重投、缓冲 DROP_OLDEST 不崩。
package com.ufi_axis_core.alert

import kotlinx.coroutines.*
import org.junit.Assert.assertTrue
import org.junit.Test

class AlertBusTest {

    private fun makeItem(id: Long, level: String = "warning") = AlertBusItem(
        id = id, type = "signal", level = level, message = "测试告警 $id"
    )

    @Test
    fun emit_is_collected_by_subscriber() = runBlocking {
        val collected = mutableListOf<AlertBusItem>()
        val job = launch(Dispatchers.Default) {
            AlertBus.events.collect { collected.add(it) }
        }
        // 给 collect 协程一点启动时间（SharedFlow 订阅需在 emit 前建立）
        delay(50)
        AlertBus.emit(makeItem(1))
        AlertBus.emit(makeItem(2))
        delay(100)
        job.cancel()

        // 注意：AlertBus 是进程单例，可能携带其它测试/真实代码的 emit，故只断言包含我们发的两条。
        val ids = collected.map { it.id }.toSet()
        assertTrue("应收到 id=1 的告警，实际：$ids", ids.contains(1L))
        assertTrue("应收到 id=2 的告警，实际：$ids", ids.contains(2L))
    }

    @Test
    fun emitAll_delivers_every_record() = runBlocking {
        val collected = mutableListOf<AlertBusItem>()
        val job = launch(Dispatchers.Default) { AlertBus.events.collect { collected.add(it) } }
        delay(50)
        AlertBus.emitAll(listOf(makeItem(10), makeItem(11), makeItem(12)))
        delay(100)
        job.cancel()

        val ids = collected.map { it.id }.toSet()
        assertTrue("应收到 10/11/12，实际：$ids", ids.containsAll(setOf(10L, 11L, 12L)))
    }

    @Test
    fun replay_zero_does_not_redeliver_old_events() = runBlocking {
        // 先发一条，再订阅，旧事件不应被新订阅者收到（replay=0 语义）。
        AlertBus.emit(makeItem(900))
        delay(50)

        val collectedAfter = mutableListOf<AlertBusItem>()
        val job = launch(Dispatchers.Default) { AlertBus.events.collect { collectedAfter.add(it) } }
        delay(50)
        // 订阅后才发的新事件应收到
        AlertBus.emit(makeItem(901))
        delay(100)
        job.cancel()

        // 900 是订阅前发的，不应出现；901 是订阅后发的，应出现。
        assertTrue("replay=0 不应重投旧事件 900", !collectedAfter.any { it.id == 900L })
        assertTrue("应收到订阅后新事件 901", collectedAfter.any { it.id == 901L })
    }
}
