package com.ufi_axis_core.api.routes

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `PUT /api/monitor/preferences` 的合并与校验语义（T40-5）。
 *
 * 这两条是「app 设为 A、web 打开变成 B」的直接防线：
 * - 合并必须是**字段级**的，客户端漏传一个键不能把它重置成默认值；
 * - 校验必须在服务端做，否则越界值会以「已保存」的样子写进真源。
 */
class MonitorPreferencesTest {

    private fun patch(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject

    @Test
    fun `patch 只覆盖出现的键`() {
        val current = MonitorPreferences(
            defaultHours = 6,
            refreshIntervalSec = 60,
            fixedYAxis = true,
            fillAlpha = 0.5f,
            exportZip = true
        )
        val merged = MonitorRoutes.mergePreferences(current, patch("""{"refreshIntervalSec":30}"""))

        assertEquals(30, merged.refreshIntervalSec)
        // 其余全部保持原值 —— 这是本用例的全部意义
        assertEquals(6, merged.defaultHours)
        assertTrue(merged.fixedYAxis)
        assertEquals(0.5f, merged.fillAlpha, 0f)
        assertTrue(merged.exportZip)
        assertEquals(current.enabledTypes, merged.enabledTypes)
    }

    @Test
    fun `空 patch 与显式 null 都不改动现值`() {
        val current = MonitorPreferences(defaultHours = 168, exportZip = true)

        assertEquals(current, MonitorRoutes.mergePreferences(current, patch("{}")))
        assertEquals(
            current,
            MonitorRoutes.mergePreferences(current, patch("""{"defaultHours":null,"exportZip":null}"""))
        )
    }

    @Test
    fun `enabledTypes 是完整集合语义`() {
        val current = MonitorPreferences()
        val merged = MonitorRoutes.mergePreferences(current, patch("""{"enabledTypes":["cpu","battery"]}"""))

        assertEquals(setOf("cpu", "battery"), merged.enabledTypes)
    }

    @Test
    fun `默认值全部合法`() {
        assertNull(MonitorRoutes.validatePreferences(MonitorPreferences()))
    }

    @Test
    fun `defaultHours 只接受 1 6 24 168`() {
        for (h in listOf(1, 6, 24, 168)) {
            assertNull(MonitorRoutes.validatePreferences(MonitorPreferences(defaultHours = h)))
        }
        assertNotNull(MonitorRoutes.validatePreferences(MonitorPreferences(defaultHours = 12)))
        assertNotNull(MonitorRoutes.validatePreferences(MonitorPreferences(defaultHours = 0)))
    }

    @Test
    fun `refreshIntervalSec 越界被拒`() {
        assertNull(MonitorRoutes.validatePreferences(MonitorPreferences(refreshIntervalSec = 10)))
        assertNull(MonitorRoutes.validatePreferences(MonitorPreferences(refreshIntervalSec = 3600)))
        assertNotNull(MonitorRoutes.validatePreferences(MonitorPreferences(refreshIntervalSec = 9)))
        assertNotNull(MonitorRoutes.validatePreferences(MonitorPreferences(refreshIntervalSec = 3601)))
    }

    @Test
    fun `fillAlpha 越界被拒`() {
        assertNull(MonitorRoutes.validatePreferences(MonitorPreferences(fillAlpha = 0f)))
        assertNull(MonitorRoutes.validatePreferences(MonitorPreferences(fillAlpha = 1f)))
        assertNotNull(MonitorRoutes.validatePreferences(MonitorPreferences(fillAlpha = -0.1f)))
        assertNotNull(MonitorRoutes.validatePreferences(MonitorPreferences(fillAlpha = 1.1f)))
    }

    @Test
    fun `未知指标 key 被拒`() {
        val reason = MonitorRoutes.validatePreferences(
            MonitorPreferences(enabledTypes = setOf("cpu", "gpu"))
        )
        assertNotNull(reason)
        assertTrue(reason!!.contains("gpu"))
    }

    // ── 2026-09-08 新增的两个调度参数 ──
    // 下限刻意抬高（60 / 15 而不是 5）：这两个周期每轮都要向设备发 goform 请求，
    // 与设备自带 Web UI 抢同一个会话。用例把这个边界钉住，避免以后有人"顺手对齐成 5"。

    @Test
    fun `trafficLimitCheckSec 越界被拒`() {
        assertNull(MonitorRoutes.validatePreferences(MonitorPreferences(trafficLimitCheckSec = 60)))
        assertNull(MonitorRoutes.validatePreferences(MonitorPreferences(trafficLimitCheckSec = 3600)))
        assertNotNull(MonitorRoutes.validatePreferences(MonitorPreferences(trafficLimitCheckSec = 59)))
        assertNotNull(MonitorRoutes.validatePreferences(MonitorPreferences(trafficLimitCheckSec = 3601)))
    }

    @Test
    fun `deviceEventCheckSec 越界被拒`() {
        assertNull(MonitorRoutes.validatePreferences(MonitorPreferences(deviceEventCheckSec = 15)))
        assertNull(MonitorRoutes.validatePreferences(MonitorPreferences(deviceEventCheckSec = 600)))
        assertNotNull(MonitorRoutes.validatePreferences(MonitorPreferences(deviceEventCheckSec = 14)))
        assertNotNull(MonitorRoutes.validatePreferences(MonitorPreferences(deviceEventCheckSec = 601)))
    }

    @Test
    fun `新增调度字段同样走字段级合并`() {
        val current = MonitorPreferences(trafficLimitCheckSec = 900, deviceEventCheckSec = 30)
        val merged = MonitorRoutes.mergePreferences(current, patch("""{"deviceEventCheckSec":120}"""))

        assertEquals(120, merged.deviceEventCheckSec)
        // 漏传的那个不能被重置成默认 300
        assertEquals(900, merged.trafficLimitCheckSec)
    }
}
