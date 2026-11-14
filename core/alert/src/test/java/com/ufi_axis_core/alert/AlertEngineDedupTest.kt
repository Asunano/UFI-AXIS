package com.ufi_axis_core.alert

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.ufi_axis_core.api.websocket.WebSocketManager
import com.ufi_axis_core.core.database.AlertDao
import com.ufi_axis_core.core.database.AlertRecord
import com.ufi_axis_core.core.database.TypeCount
import com.ufi_axis_core.core.database.LevelCount
import com.ufi_axis_core.util.AppSettings
import com.ufi_axis_core.notify.NotificationDispatcher
import com.ufi_axis_core.notify.PushChannel
import com.ufi_axis_core.util.NotificationPushService
import com.ufi_axis_core.util.PushNotification
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicLong

/**
 * P1-9 AlertEngine 去重改造单元测试
 *
 * 覆盖：
 * 1. 边沿触发：稳态不重复入库（温度持续超标仅 1 条）
 * 2. 级别跃迁：normal→warning→critical 各产生独立行
 * 3. 恢复语义：类型回到 normal → markResolved 标记 resolvedAt
 * 4. 环形上限：trimTo 保留最近 maxRows
 * 5. enabled 短路：关闭总开关后所有 check* 不入库
 * 6. 推送负载携带 aggregated 标志（新行 "false" / 聚合 "true"）
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AlertEngineDedupTest {

    private companion object {
        /** 本类用到的告警类型（perType 需显式打开，缺键 = 关闭）。 */
        val ALERT_TYPES = listOf("temperature", "battery", "traffic", "signal", "connectivity")
    }

    /** 内存版 AlertDao 假实现，复刻核心 SQL 语义（bump/markResolved/trimTo）。 */
    private class FakeAlertDao : AlertDao {
        val rows = mutableListOf<AlertRecord>()
        private val idGen = AtomicLong(1)

        override suspend fun insert(record: AlertRecord): Long {
            val id = idGen.getAndIncrement()
            rows.add(record.copy(id = id))
            return id
        }

        override suspend fun update(record: AlertRecord) {
            val idx = rows.indexOfFirst { it.id == record.id }
            if (idx >= 0) rows[idx] = record
        }

        override suspend fun getRecentAlerts(limit: Int): List<AlertRecord> =
            rows.sortedByDescending { it.timestamp }.take(limit)

        override suspend fun getCount(): Int = rows.size

        override suspend fun getAlertsBetween(startTime: Long, endTime: Long): List<AlertRecord> =
            rows.filter { it.timestamp in startTime..endTime }.sortedByDescending { it.timestamp }

        override suspend fun getUnacknowledgedAlerts(): List<AlertRecord> =
            rows.filter { !it.acknowledged }

        override suspend fun getLatestByType(type: String): AlertRecord? =
            rows.filter { it.type == type }.maxByOrNull { it.timestamp }

        override suspend fun deleteOlderThan(cutoff: Long): Int {
            val before = rows.size
            rows.removeIf { it.timestamp < cutoff }
            return before - rows.size
        }

        override suspend fun acknowledge(id: Long) {
            val idx = rows.indexOfFirst { it.id == id }
            if (idx >= 0) rows[idx] = rows[idx].copy(acknowledged = true)
        }

        override suspend fun deleteById(id: Long): Int {
            val removed = rows.removeIf { it.id == id }
            return if (removed) 1 else 0
        }

        // 2026-08-26 补：AlertDao 新增成员。triggerAlert 聚合路径先取现有未确认行，
        // 把行 id 一起推给前端做精确去重；语义 = 同 (type,level) 未确认行中最新一条。
        override suspend fun getUnacknowledged(type: String, level: String): AlertRecord? =
            rows.filter { it.type == type && it.level == level && !it.acknowledged }
                .maxByOrNull { it.timestamp }

        override suspend fun bumpExisting(type: String, level: String, now: Long): Int {
            val target = rows.firstOrNull { it.type == type && it.level == level && !it.acknowledged }
            return if (target == null) {
                0
            } else {
                val idx = rows.indexOf(target)
                rows[idx] = target.copy(count = target.count + 1, timestamp = now, resolvedAt = null)
                1
            }
        }

        override suspend fun markResolved(type: String, now: Long): Int {
            val target = rows.filter { it.type == type && !it.acknowledged }
                .maxByOrNull { it.timestamp }
            return if (target == null) {
                0
            } else {
                val idx = rows.indexOf(target)
                rows[idx] = target.copy(resolvedAt = now)
                1
            }
        }

        override suspend fun trimTo(maxRows: Int) {
            if (rows.size > maxRows) {
                val keep = rows.sortedByDescending { it.timestamp }.take(maxRows).map { it.id }.toSet()
                rows.removeIf { it.id !in keep }
            }
        }

        override suspend fun getPaged(
            level: String?, type: String?, unreadOnly: Int,
            start: Long?, end: Long?, cTs: Long?, cId: Long?, limit: Int
        ): List<AlertRecord> = rows.take(limit)

        override suspend fun getCountFiltered(
            level: String?, type: String?, unreadOnly: Int, start: Long?, end: Long?
        ): Int = rows.size

        override suspend fun getCountByType(): List<TypeCount> =
            rows.groupBy { it.type }.map { TypeCount(it.key, it.value.size) }

        override suspend fun getUnreadCountByLevel(): List<LevelCount> =
            rows.filter { !it.acknowledged }.groupBy { it.level }.map { LevelCount(it.key, it.value.size) }

        override suspend fun getUnreadCount(): Int = rows.count { !it.acknowledged }

        override suspend fun ackByIds(ids: List<Long>) {
            rows.forEachIndexed { i, r -> if (r.id in ids) rows[i] = r.copy(acknowledged = true) }
        }

        override suspend fun ackByFilter(type: String?, level: String?, unreadOnly: Int): Int {
            var n = 0
            rows.forEachIndexed { i, r ->
                if ((type == null || r.type == type) && (level == null || r.level == level)) {
                    rows[i] = r.copy(acknowledged = true); n++
                }
            }
            return n
        }

        override suspend fun ackResolved(minAgeSec: Long?): Int {
            var n = 0
            rows.forEachIndexed { i, r ->
                if (r.resolvedAt != null) { rows[i] = r.copy(acknowledged = true); n++ }
            }
            return n
        }
    }

    /**
     * 记录型推送桩：NotificationPushService 是接口，这里只把 push() 的负载存进列表，
     * 不做任何 I/O（不建 WS 连接、不弹系统通知），故对被测逻辑零副作用。
     * 用它替代原先对 AlertEngine.lastBroadcast 的观测（见最后一个用例注释）。
     */
    private class RecordingPushService : NotificationPushService {
        val pushed = mutableListOf<PushNotification>()
        override fun push(notification: PushNotification, mirrorToAlertTopic: Boolean) { pushed.add(notification) }
    }

    /** 静默 WebSocketManager 实例（final class 不可继承，仅持有字段、无连接时无副作用）。 */
    private lateinit var ws: WebSocketManager

    private lateinit var dao: FakeAlertDao
    private lateinit var push: RecordingPushService
    private lateinit var engine: AlertEngine

    @Before
    fun setup() {
        dao = FakeAlertDao()
        ws = WebSocketManager(null)
        push = RecordingPushService()
        val settings = AppSettings(ApplicationProvider.getApplicationContext<Application>())
        engine = AlertEngine(dao, ws, settings)
        // 2026-09-08 阶段 1：投递改走分发器。这里注册一个只有 push 渠道的分发器，
        // 断言仍观测 `push.pushed`（推送负载才是对外真实语义），顺带覆盖
        // 「聚合更新只走 push、不走邮件」那条 channels 限定 —— 因为这里没注册 mail 渠道，
        // 一旦哪天聚合路径改成投全部渠道，本类的断言不会变，但 NotificationDispatcherTest 会红。
        val dispatcher = NotificationDispatcher()
        dispatcher.register(PushChannel(push))
        engine.attachNotifier { event -> dispatcher.emit(event) }
        // 2026-09-07：告警默认不开启（enabled=false，且 perType 缺键视为关闭）。
        // 本类测的是去重/聚合/环形上限语义，故显式把要用到的类型全部打开；
        // 「总开关关闭」的短路语义由 `enabled false shorts all checks` 单独覆盖。
        engine.updateConfig(
            engine.getConfig().copy(
                enabled = true,
                perType = ALERT_TYPES.associateWith { true }
            )
        )
    }

    @Test
    fun `edge trigger - steady-state temperature over-threshold only inserts once`() = runTest {
        val critical = engine.getConfig().temperatureCritical
        repeat(288) {
            engine.checkTemperature(critical + 5.0) // 模拟持续严重超标 288 次采样
        }
        val temps = dao.rows.filter { it.type == "temperature" }
        assertEquals("稳态持续超标不应反复入库，仅 1 条", 1, temps.size)
        assertEquals(1, temps.first().count)
    }

    // 2026-09-21：温度用例改为**从配置推导**测试值，不再写死 50 / 60。
    // 上一版写死的是当时的默认阈值（45/55），阈值一改（现为 65/75）整批用例就红 ——
    // 测的是「跃迁语义」而不是「阈值取值」，取值必须跟着 getConfig() 走。
    // 回差（leveledWithHysteresis）也要照顾到：回落必须低于 `warning - band`。
    private fun warnTemp() = engine.getConfig().temperatureWarning + 1.0
    private fun critTemp() = engine.getConfig().temperatureCritical + 1.0
    private fun normalTemp() = engine.getConfig().temperatureWarning - 20.0

    @Test
    fun `edge trigger - level transition normal to warning then critical produces 2 rows`() = runTest {
        engine.checkTemperature(normalTemp()) // normal
        engine.checkTemperature(warnTemp())  // warning 跃迁
        engine.checkTemperature(critTemp())  // critical 跃迁
        val temps = dao.rows.filter { it.type == "temperature" }
        assertEquals(2, temps.size)
        assertTrue(temps.any { it.level == "warning" })
        assertTrue(temps.any { it.level == "critical" })
    }

    @Test
    fun `recovery - type back to normal marks resolvedAt`() = runTest {
        engine.checkTemperature(critTemp())   // critical 跃迁
        engine.checkTemperature(normalTemp()) // normal 恢复
        val crit = dao.rows.first { it.type == "temperature" && it.level == "critical" }
        assertNotNull("恢复后原 critical 行应标记 resolvedAt", crit.resolvedAt)
    }

    // 回差带宽来自构造参数，不是类内写死的常量（2026-09-25 / 计划书阶段 4 的 4.5）。
    //
    // 装配层从 `DeviceTuning.thermalJitterC` 取值递进来。**没有这条用例，接线被删掉也不会有
    // 任何测试变红** —— F50 的 3f 与旧常量 3.0 相同，行为会悄悄退回写死值，而下一台设备
    // 填的带宽从此被静默忽略。所以这里用「宽带宽 + 阴性对照」把读参数这件事钉住。
    @Test
    fun `温度回差带宽由构造参数决定`() = runTest {
        val wideDao = FakeAlertDao()
        val wide = AlertEngine(
            wideDao, ws,
            AppSettings(ApplicationProvider.getApplicationContext<Application>()),
            temperatureHysteresisC = 20.0
        )
        wide.updateConfig(
            wide.getConfig().copy(enabled = true, perType = ALERT_TYPES.associateWith { true })
        )
        val warning = wide.getConfig().temperatureWarning
        // 先显式落到 normal：边沿状态会持久化到 SharedPreferences，不先压平就要依赖
        // 「上一个用例留下了什么」，那是测试之间的隐式耦合。
        wide.checkTemperature(warning - 30.0)
        wide.checkTemperature(warning + 1.0)
        assertEquals("warning", wideDao.rows.first { it.type == "temperature" }.level)
        // 回落到 warning-5：带宽 20°C 时仍在回差区间内（要低于 warning-20 才算恢复）。
        wide.checkTemperature(warning - 5.0)
        assertNull(
            "带宽 20°C 时 warning-5 仍在回差区间内，不该判成恢复",
            wideDao.rows.first { it.type == "temperature" }.resolvedAt
        )

        // 阴性对照：同一串输入喂给默认带宽（3°C）的实例必须判成恢复。
        // 两边都成立才说明读的是参数而不是常量 —— 只验一边的话，把参数忽略掉也能过。
        engine.checkTemperature(warning - 30.0)
        engine.checkTemperature(warning + 1.0)
        engine.checkTemperature(warning - 5.0)
        assertNotNull(
            "默认带宽 3°C 时 warning-5 应判成恢复",
            dao.rows.first { it.type == "temperature" }.resolvedAt
        )
    }

    @Test
    fun `ring buffer - trimTo keeps only most recent maxRows`() = runTest {
        // 预置行必须是**已确认**：triggerAlert 会先找同 (type,level) 的未确认行做聚合，
        // 若预置行未确认则走聚合分支（不插入、不裁剪），测不到环形上限。
        repeat(2500) { i ->
            dao.insert(AlertRecord(type = "signal", level = "warning", message = "x$i", value = "", threshold = "", acknowledged = true, timestamp = i.toLong()))
        }
        // 触发一次 trim：通过 checkSignal 跃迁（normal→warning）
        engine.checkSignal(-120)
        // insert 新行(1) + trimTo(2000) 保留最近 2000 条（含新行），最终 = 2000
        assertTrue("环形上限后总量应≤2000", dao.rows.size <= 2000)
        assertEquals(2000, dao.rows.size)
        // 最旧的被裁掉：保留集合的最小 timestamp 应 > 0（原 0..499 被淘汰）
        val minTs = dao.rows.minOf { it.timestamp }
        assertTrue("最旧的 500 条应已被环形淘汰，最小 timestamp 应 > 0", minTs > 0)
    }

    @Test
    fun `enabled false shorts all checks - no insert`() = runTest {
        engine.updateConfig(engine.getConfig().copy(enabled = false))
        engine.checkTemperature(60.0)
        engine.checkBattery(5, isCharging = false)
        engine.checkTraffic(99999L)
        engine.checkSignal(-130)
        engine.checkConnectivity(false, "WiFi")
        assertTrue("enabled=false 时不应有任何入库", dao.rows.isEmpty())
    }

    /**
     * 断言改动说明（2026-08-26）：
     * 原用例读 `engine.lastBroadcast`（Map<String,Any?>）断言 `payload["aggregated"] == false`。
     * 主源码已把告警下发改道到公共组件 NotificationPushService.pushAlert()，
     * aggregated 以字符串 "false"/"true" 放进 PushNotification.extra。
     * 因此断言改为观测推送负载 —— 这是当前主源码真实的对外语义。
     * （`lastBroadcast` 字段已于 2026-09-21 从主源码删除：它恒为 null，是第四个死钩子。）
     */
    @Test
    fun `push payload carries aggregated flag on new alert`() = runTest {
        engine.checkTemperature(critTemp()) // 新行
        assertEquals("新告警应推送 1 条", 1, push.pushed.size)
        val notification = push.pushed.first()
        assertEquals("temperature", notification.type)
        assertEquals("critical", notification.level)
        assertEquals("false", notification.extra["aggregated"])
        assertNotNull("推送应携带告警行 id", notification.extra["id"])
    }

    /**
     * 聚合路径：同 (type,level) 未确认行已存在时，triggerAlert 走 bumpExisting 分支，
     * 不插新行且推送 aggregated="true"。这里绕过边沿触发（直接预置一行）来覆盖该分支。
     */
    @Test
    fun `push payload carries aggregated true when existing row is bumped`() = runTest {
        dao.insert(AlertRecord(type = "temperature", level = "critical", message = "old", value = "", threshold = ""))
        engine.checkTemperature(critTemp()) // normal→critical 跃迁，但已有未确认 critical 行 → 聚合
        assertEquals("聚合路径不应插入新行", 1, dao.rows.size)
        assertEquals(2, dao.rows.first().count)
        assertEquals("true", push.pushed.single().extra["aggregated"])
    }
}
