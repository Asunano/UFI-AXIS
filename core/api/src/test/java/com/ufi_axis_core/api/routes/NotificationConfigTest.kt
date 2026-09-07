package com.ufi_axis_core.api.routes

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `PUT /api/notifications/config` 的合并与校验语义（T40-6）。
 *
 * 这些开关原先是 app 本地 11 个散装 pref key，两端各说各话；
 * 迁到 core 后，字段级合并是「web 改一个开关不会顺手把 app 专有项重置成默认值」的唯一保证。
 */
class NotificationConfigTest {

    private fun patch(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject

    @Test
    fun `patch 只覆盖出现的键`() {
        val current = NotificationConfig(
            alert_enabled = true,
            sms_enabled = false,
            device_events_enabled = true,
            dnd_enabled = true,
            guard_enabled = true,
            guard_interval_minutes = 60,
            guard_foreground_keepalive_enabled = true
        )
        val merged = NotificationRoutes.merge(current, patch("""{"dnd_enabled":false}"""))

        assertFalse(merged.dnd_enabled)
        // 其余全部保持原值
        assertTrue(merged.alert_enabled)
        assertFalse(merged.sms_enabled)
        assertTrue(merged.device_events_enabled)
        assertTrue(merged.guard_enabled)
        assertEquals(60, merged.guard_interval_minutes)
        assertTrue(merged.guard_foreground_keepalive_enabled)
    }

    @Test
    fun `空 patch 与显式 null 都不改动现值`() {
        val current = NotificationConfig(alert_enabled = true, guard_interval_minutes = 15)

        assertEquals(current, NotificationRoutes.merge(current, patch("{}")))
        assertEquals(
            current,
            NotificationRoutes.merge(current, patch("""{"alert_enabled":null,"guard_enabled":null}"""))
        )
    }

    @Test
    fun `默认值全部合法且通知一律默认关`() {
        val d = NotificationConfig()
        assertNull(NotificationRoutes.validate(d))
        // 2026-09-07：通知一律不默认开启，与 app 侧 switchOn(key, false)、
        // NotifyScene.defaultEnabled、web AlertConfigPanel 的本地初值逐字一致
        assertFalse(d.alert_enabled)
        assertFalse(d.device_events_enabled)
        assertFalse(d.connectivity_enabled)
        assertFalse(d.sms_enabled)
        assertFalse(d.verification_enabled)
        assertFalse(d.download_enabled)
        assertFalse(d.traffic_80_enabled)
    }

    @Test
    fun `guard_interval_minutes 越界被拒`() {
        assertNull(NotificationRoutes.validate(NotificationConfig(guard_interval_minutes = 15)))
        assertNull(NotificationRoutes.validate(NotificationConfig(guard_interval_minutes = 60)))
        assertNotNull(NotificationRoutes.validate(NotificationConfig(guard_interval_minutes = 14)))
        assertNotNull(NotificationRoutes.validate(NotificationConfig(guard_interval_minutes = 61)))
    }

    @Test
    fun `不含隧道通知字段`() {
        // 隧道失败通知的真源是 AppSettings.tunnelNotifyOnFailure（PUT /api/tunnel/settings），
        // 这里若出现同名字段就是给同一概念造第二份真源。
        val json = Json.encodeToString(NotificationConfig.serializer(), NotificationConfig())
        assertFalse(json.contains("tunnel"))
    }
}
