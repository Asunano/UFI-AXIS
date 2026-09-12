package com.ufi_axis_core.alert

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.ufi_axis_core.api.websocket.WebSocketManager
import com.ufi_axis_core.core.database.AlertDao
import com.ufi_axis_core.core.database.AlertRecord
import com.ufi_axis_core.core.database.TypeCount
import com.ufi_axis_core.core.database.LevelCount
import com.ufi_axis_core.util.AppSettings
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicLong

/**
 * P2-8 多端同步单元测试
 *
 * 覆盖：
 * 1. 版本守门：旧 configVersion 的 replaceConfig 被拒绝（返回当前，version 不变）
 * 2. 正确版本 replaceConfig 成功（version 自增 1）
 * 3. broadcastConfigChanged 不抛异常（WS 无连接时无副作用）
 * 4. C02 局部合并语义（少传字段 = 不改）
 * 5. T40-13：`perType` 分类开关真的门控引擎（含 connectivity 这条不走 evaluate 的路径）
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AlertConfigSyncTest {

    /** 内存版 AlertDao 假实现（完整接口，复刻核心 SQL 语义）。 */
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
        override suspend fun getRecentAlerts(limit: Int): List<AlertRecord> = rows.sortedByDescending { it.timestamp }.take(limit)
        override suspend fun getCount(): Int = rows.size
        override suspend fun getAlertsBetween(startTime: Long, endTime: Long): List<AlertRecord> = rows.filter { it.timestamp in startTime..endTime }.sortedByDescending { it.timestamp }
        override suspend fun getUnacknowledgedAlerts(): List<AlertRecord> = rows.filter { !it.acknowledged }
        override suspend fun getLatestByType(type: String): AlertRecord? = rows.filter { it.type == type }.maxByOrNull { it.timestamp }
        override suspend fun deleteOlderThan(cutoff: Long): Int { val b = rows.size; rows.removeIf { it.timestamp < cutoff }; return b - rows.size }
        override suspend fun acknowledge(id: Long) { val i = rows.indexOfFirst { it.id == id }; if (i >= 0) rows[i] = rows[i].copy(acknowledged = true) }
        override suspend fun deleteById(id: Long): Int = if (rows.removeIf { it.id == id }) 1 else 0
        // 2026-08-26 补：AlertDao 新增成员（triggerAlert 聚合路径需先取现有行 id 推给前端做精确去重）
        override suspend fun getUnacknowledged(type: String, level: String): AlertRecord? =
            rows.filter { it.type == type && it.level == level && !it.acknowledged }.maxByOrNull { it.timestamp }
        override suspend fun bumpExisting(type: String, level: String, now: Long): Int {
            val t = rows.firstOrNull { it.type == type && it.level == level && !it.acknowledged } ?: return 0
            val i = rows.indexOf(t); rows[i] = t.copy(count = t.count + 1, timestamp = now, resolvedAt = null); return 1
        }
        override suspend fun markResolved(type: String, now: Long): Int {
            val t = rows.filter { it.type == type && !it.acknowledged }.maxByOrNull { it.timestamp } ?: return 0
            val i = rows.indexOf(t); rows[i] = t.copy(resolvedAt = now); return 1
        }
        override suspend fun trimTo(maxRows: Int) { if (rows.size > maxRows) { val keep = rows.sortedByDescending { it.timestamp }.take(maxRows).map { it.id }.toSet(); rows.removeIf { it.id !in keep } } }
        override suspend fun getPaged(level: String?, type: String?, unreadOnly: Int, start: Long?, end: Long?, cTs: Long?, cId: Long?, limit: Int): List<AlertRecord> = rows.take(limit)
        override suspend fun getCountFiltered(level: String?, type: String?, unreadOnly: Int, start: Long?, end: Long?): Int = rows.size
        override suspend fun getCountByType(): List<TypeCount> = rows.groupBy { it.type }.map { TypeCount(it.key, it.value.size) }
        override suspend fun getUnreadCountByLevel(): List<LevelCount> = rows.filter { !it.acknowledged }.groupBy { it.level }.map { LevelCount(it.key, it.value.size) }
        override suspend fun getUnreadCount(): Int = rows.count { !it.acknowledged }
        override suspend fun ackByIds(ids: List<Long>) { rows.forEachIndexed { i, r -> if (r.id in ids) rows[i] = r.copy(acknowledged = true) } }
        override suspend fun ackByFilter(type: String?, level: String?, unreadOnly: Int): Int { var n = 0; rows.forEachIndexed { i, r -> if ((type == null || r.type == type) && (level == null || r.level == level)) { rows[i] = r.copy(acknowledged = true); n++ } }; return n }
        override suspend fun ackResolved(minAgeSec: Long?): Int { var n = 0; rows.forEachIndexed { i, r -> if (r.resolvedAt != null) { rows[i] = r.copy(acknowledged = true); n++ } }; return n }
    }

    private lateinit var engine: AlertEngine
    private lateinit var dao: FakeAlertDao

    @Before
    fun setup() {
        val settings = AppSettings(ApplicationProvider.getApplicationContext<Application>())
        dao = FakeAlertDao()
        // 2026-09-08 阶段 1：引擎不再持有 pushService（投递统一走 attachNotifier）。
        // 这里**故意不 attach**：本类测的是配置合并 / 版本守门 / perType 门控，断言全部落在
        // FakeAlertDao.rows 上。没接分发器时 emitAlert 直接返回 —— 正好钉住
        // 「投递发不出去不影响告警入库/广播」这条既有语义。
        engine = AlertEngine(dao, WebSocketManager(null), settings)
    }

    @Test
    fun `fresh engine default configVersion is 1`() = runTest {
        assertEquals(1L, engine.getConfig().configVersion)
        // 2026-09-07：默认不开启告警（与 app 侧 AlertConfig.enabled 默认值一致）
        assertFalse(engine.getConfig().enabled)
    }

    @Test
    fun `replaceConfig rejects stale version and keeps current version`() = runTest {
        // 先做一次成功 PUT：v1 → v2（模拟设备 A 改了设置）
        val v1 = engine.getConfig()
        engine.replaceConfig(v1.copy(enabled = true, configVersion = v1.configVersion))
        val current = engine.getConfig()
        assertEquals(v1.configVersion + 1, current.configVersion) // 现在是 v2

        // 另一设备（B）拿的是旧 v1 副本来 PUT → 应被拒绝
        val stale = v1.copy(enabled = false, configVersion = v1.configVersion)
        val result = engine.replaceConfig(stale)
        // 拒绝：返回当前（version 仍为 v2，enabled 仍 true，未被旧副本覆盖）
        assertEquals(current.configVersion, result.configVersion)
        assertTrue(result.enabled)
        // 本地未被覆盖
        assertTrue(engine.getConfig().enabled)
        assertEquals(current.configVersion, engine.getConfig().configVersion)
    }

    @Test
    fun `replaceConfig accepts matching version and bumps to next`() = runTest {
        val current = engine.getConfig()
        val incoming = current.copy(enabled = false, configVersion = current.configVersion)
        val result = engine.replaceConfig(incoming)
        // 接受：version 自增，enabled 被覆盖
        assertEquals(current.configVersion + 1, result.configVersion)
        assertFalse(result.enabled)
        assertFalse(engine.getConfig().enabled)
        assertEquals(current.configVersion + 1, engine.getConfig().configVersion)
    }

    @Test
    fun `broadcastConfigChanged does not throw when ws unconnected`() = runTest {
        // 无连接时 broadcast 应静默无副作用
        engine.broadcastConfigChanged()
    }

    // ──────────────── C02：局部合并语义 ────────────────

    private fun patch(json: String) =
        Json.parseToJsonElement(json).jsonObject

    @Test
    fun `mergeConfigPatch keeps unspecified fields`() = runTest {
        // 先让当前配置带上"别端设置过"的非默认值
        val base = engine.getConfig().copy(
            perType = mapOf("temperature" to false),
            minIntervalSec = 60
        )
        // 只传 {enabled, configVersion}（旧语义下会把上面几个字段全部重置为默认值）
        val merged = AlertEngine.mergeConfigPatch(base, patch("""{"enabled":false,"configVersion":1}"""))

        assertFalse(merged.enabled)                                  // 显式字段生效
        assertEquals(mapOf("temperature" to false), merged.perType)   // 未传字段保持原值
        assertEquals(60, merged.minIntervalSec)
    }

    @Test
    fun `mergeConfigPatch treats explicit null and empty body as not provided`() = runTest {
        val base = engine.getConfig().copy(perType = mapOf("battery" to false), minIntervalSec = 90)

        val withNull = AlertEngine.mergeConfigPatch(base, patch("""{"perType":null,"enabled":false}"""))
        assertEquals(mapOf("battery" to false), withNull.perType)   // JSON null 不当成"清空"
        assertFalse(withNull.enabled)

        val empty = AlertEngine.mergeConfigPatch(base, patch("{}"))
        assertEquals(base, empty)                                    // 空 body 完全不改动
    }

    @Test
    fun `mergeConfigPatch full object behaves like overwrite`() = runTest {
        // 回归：两端现有的"完整对象 PUT"写法结果必须与旧覆盖语义一致。
        // 必须用 ConfigJson（encodeDefaults=true）编码 —— app 侧 AppJson 也是这个口径；
        // 若客户端用省略默认值的编码器，被省略的键在合并语义下等于"不改"（这是设计，不是 bug）。
        val base = engine.getConfig().copy(perType = mapOf("signal" to false))
        val full = base.copy(perType = emptyMap(), temperatureWarning = 50.0)
        val fullJson = AlertEngine.ConfigJson
            .encodeToJsonElement(AlertEngine.AlertConfig.serializer(), full).jsonObject

        assertEquals(full, AlertEngine.mergeConfigPatch(base, fullJson))
        // 显式传空 map 时确实清空（与 JSON null 区分开）
        assertTrue(AlertEngine.mergeConfigPatch(base, patch("""{"perType":{}}""")).perType.isEmpty())
    }

    @Test
    fun `mergeConfigPatch rejects wrong value type`() = runTest {
        // 路由把这个异常转成 400（而非 500）；此处只断言"确实抛"
        val base = engine.getConfig()
        try {
            AlertEngine.mergeConfigPatch(base, patch("""{"enabled":"yes","configVersion":1}"""))
            fail("类型不合法的补丁应当抛异常")
        } catch (_: Exception) { /* expected */ }
    }

    // ──────────────── T40-13：perType 分类开关必须真的门控引擎 ────────────────

    @Test
    fun `perType false stops that type from being detected while others keep working`() = runTest {
        engine.updateConfig(
            engine.getConfig().copy(
                enabled = true,
                perType = mapOf("temperature" to false, "battery" to true)
            )
        )

        engine.checkTemperature(99.0)                 // 关掉的类型：不检测、不入库
        assertTrue(dao.rows.none { it.type == "temperature" })

        engine.checkBattery(level = 5, isCharging = false)  // 显式打开的类型：照常入库
        assertEquals(1, dao.rows.count { it.type == "battery" })
    }

    @Test
    fun `missing perType key means disabled`() = runTest {
        // 2026-09-07 改口径：缺省即**关闭**（与 app 侧 UI 兜底 `?: false`、
        // core `typeEnabled` 的 `perType[type] == true` 同口径）。
        // 若门控退回 `!= false`，从未显式打开过的类型会全部静默检测入库。
        engine.updateConfig(engine.getConfig().copy(enabled = true))
        assertTrue(engine.getConfig().perType.isEmpty())
        engine.checkSignal(-130)
        assertTrue(dao.rows.none { it.type == "signal" })

        // 显式打开后才检测
        engine.updateConfig(engine.getConfig().copy(perType = mapOf("signal" to true)))
        engine.checkSignal(-130)
        assertEquals(1, dao.rows.count { it.type == "signal" })
    }

    @Test
    fun `perType false also gates connectivity which bypasses evaluate`() = runTest {
        // connectivity 不走 evaluate()，直接调 triggerAlert，必须单独门控
        engine.updateConfig(
            engine.getConfig().copy(enabled = true, perType = mapOf("connectivity" to false))
        )
        engine.checkConnectivity(isConnected = true, networkType = "5G")   // 首次：状态初始化
        engine.checkConnectivity(isConnected = false, networkType = "")    // 状态跃迁：本应告警
        assertTrue(dao.rows.none { it.type == "connectivity" })
    }

    @Test
    fun `master switch off still blocks every type regardless of perType`() = runTest {
        engine.updateConfig(engine.getConfig().copy(enabled = false, perType = mapOf("temperature" to true)))
        engine.checkTemperature(99.0)
        assertTrue(dao.rows.isEmpty())
    }
}
