package com.ufi_axis_core.api.routes

import kotlinx.serialization.json.Json
import com.ufi_axis_core.notify.GateVerdict
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
        // 2026-09-08 新增的三项同样默认关：全局总闸、隧道分类、邮件是否遵守免打扰
        assertFalse(d.master_enabled)
        assertFalse(d.tunnel_enabled)
        assertFalse(d.mail_respect_dnd)
        // 保留条数默认 500：必须与 app `NotificationCenter.DEFAULT_HISTORY_MAX_ROWS`、
        // `SmsForwardController.DEFAULT_MAIL_HISTORY_ROWS`、web 本地初值逐字一致 ——
        // 分叉会出现「设置页显示 500、实际按别的值裁剪」。
        assertEquals(500, d.history_max_rows)
        // 保留天数默认 30；0 是"不限"的合法值，但默认不给 0 —— 历史无上限增长不是好默认。
        assertEquals(30, d.history_max_age_days)
        // 2026-09-10：CRITICAL 兜底是**唯一默认开启**的一项。它挡的不是通知而是
        // "关键通知被免打扰/场景开关静默"这件事 —— 默认关掉等于把这道兜底交给用户去发现。
        assertTrue("CRITICAL 兜底必须默认开启", d.critical_override_enabled)
    }

    /**
     * CRITICAL 兜底能被 PUT 单独关掉，且不顺带改别的开关。
     *
     * 它是本模型里唯一默认 true 的字段，所以"字段级合并把 false 写进去"这条路径必须真的通 ——
     * 一个只在默认值上成立的开关就是假开关。
     */
    @Test
    fun `CRITICAL 兜底可以被单独关闭`() {
        val merged = NotificationRoutes.merge(
            NotificationConfig(),
            patch("""{"critical_override_enabled":false}""")
        )
        assertFalse(merged.critical_override_enabled)
        assertNull(NotificationRoutes.validate(merged))
        assertFalse("只传兜底时不该顺带打开别的分类", merged.alert_enabled)
    }

    /** 三态闸门：总开关关着报 BLOCKED_BY_MASTER，免打扰时段报 BLOCKED_BY_DND，其余 ALLOW。 */
    @Test
    fun `闸门三态各自可分辨`() {
        val inWindow = NotificationConfig(
            master_enabled = true,
            dnd_enabled = true, dnd_start_hour = 23, dnd_end_hour = 7
        )
        assertEquals(
            GateVerdict.BLOCKED_BY_MASTER,
            NotificationRoutes.notifyVerdict(NotificationConfig(), 12, respectDnd = false)
        )
        assertEquals(
            "总开关优先：关着的时候连'是不是半夜'都不必问",
            GateVerdict.BLOCKED_BY_MASTER,
            NotificationRoutes.notifyVerdict(inWindow.copy(master_enabled = false), 2, respectDnd = true)
        )
        assertEquals(
            GateVerdict.BLOCKED_BY_DND,
            NotificationRoutes.notifyVerdict(inWindow, 2, respectDnd = true)
        )
        assertEquals(
            "不遵守免打扰的渠道半夜也放行",
            GateVerdict.ALLOW,
            NotificationRoutes.notifyVerdict(inWindow, 2, respectDnd = false)
        )
        assertEquals(
            GateVerdict.ALLOW,
            NotificationRoutes.notifyVerdict(inWindow, 12, respectDnd = true)
        )
    }

    /**
     * 保留条数是**真设置项**（两侧裁剪时现取当前值），越界必须被拒。
     *
     * 下限存在的理由比上限更硬：0 或负数会让 `trimTo` 那条 DELETE 把整张表清空 ——
     * 一个"改小一点"的操作变成"清空历史"。
     */
    @Test
    fun `history_max_rows 越界被拒`() {
        assertNull(NotificationRoutes.validate(NotificationConfig(history_max_rows = 100)))
        assertNull(NotificationRoutes.validate(NotificationConfig(history_max_rows = 5000)))
        assertNotNull(NotificationRoutes.validate(NotificationConfig(history_max_rows = 99)))
        assertNotNull(NotificationRoutes.validate(NotificationConfig(history_max_rows = 5001)))
        assertNotNull(NotificationRoutes.validate(NotificationConfig(history_max_rows = 0)))
        assertNotNull(NotificationRoutes.validate(NotificationConfig(history_max_rows = -1)))
    }

    /**
     * 保留天数：**0 是合法值**（不按时间清理），负数与超过一年被拒。
     *
     * 0 必须放行 —— 用户真正的诉求是"别删"，让他去猜"填 3650 算不算永久"
     * 是把实现细节推给用户。
     */
    @Test
    fun `history_max_age_days 允许 0 且越界被拒`() {
        assertNull(NotificationRoutes.validate(NotificationConfig(history_max_age_days = 0)))
        assertNull(NotificationRoutes.validate(NotificationConfig(history_max_age_days = 1)))
        assertNull(NotificationRoutes.validate(NotificationConfig(history_max_age_days = 365)))
        assertNotNull(NotificationRoutes.validate(NotificationConfig(history_max_age_days = -1)))
        assertNotNull(NotificationRoutes.validate(NotificationConfig(history_max_age_days = 366)))
    }

    @Test
    fun `guard_interval_minutes 越界被拒`() {
        assertNull(NotificationRoutes.validate(NotificationConfig(guard_interval_minutes = 15)))
        assertNull(NotificationRoutes.validate(NotificationConfig(guard_interval_minutes = 60)))
        assertNotNull(NotificationRoutes.validate(NotificationConfig(guard_interval_minutes = 14)))
        assertNotNull(NotificationRoutes.validate(NotificationConfig(guard_interval_minutes = 61)))
    }

    /**
     * 隧道有 `tunnel_enabled` 字段**不是**第二份真源（2026-09-08 起）。
     *
     * 本测试原来断言"不含 tunnel 字段"，理由是 `AppSettings.tunnelNotifyOnFailure` 已在 core。
     * 但那两者管的是不同的事：前者是**本机要不要弹状态栏**（app 侧 `tunnel_notification_enabled`），
     * 后者是**设备端要不要推**。此前 app 侧隧道借用 `alert_enabled`，
     * "只关隧道提醒、留阈值告警"做不到，所以拆出了本字段。两者串联，不是同一概念。
     */
    @Test
    fun `隧道分类字段与设备端推送开关并存`() {
        // 用非默认值编码：这里的 Json 实例会省略等于默认值的字段（原测试正是利用这一点断言"不含"）。
        val json = Json.encodeToString(
            NotificationConfig.serializer(),
            NotificationConfig(tunnel_enabled = true)
        )
        assertTrue(json.contains("\"tunnel_enabled\":true"))
        // 也要能通过 PUT 的字段级合并单独设置
        val merged = NotificationRoutes.merge(
            NotificationConfig(),
            Json.parseToJsonElement("""{"tunnel_enabled":true}""").jsonObject
        )
        assertTrue(merged.tunnel_enabled)
        assertFalse("只传隧道时不该顺带打开别的分类", merged.alert_enabled)
    }

    // ──────────── 通知渠道闸门（notifyAllowed / inDndWindow 纯函数） ────────────
    //
    // 2026-09-09：`mailAllowed(cfg, hour)` 改名 `notifyAllowed(cfg, hour, respectDnd)` ——
    // 判据与邮件无关（Webhook 也用它），而"免打扰管不管我"是每渠道各自的配置。
    // 邮件侧行为逐字不变：邮件传的就是 `cfg.mail_respect_dnd`。

    @Test
    fun `邮件闸只看总闸 渠道是否启用由 SMTP 配置自己短路`() {
        assertFalse(NotificationRoutes.notifyAllowed(NotificationConfig(), 12, respectDnd = false))
        assertTrue(
            NotificationRoutes.notifyAllowed(
                NotificationConfig(master_enabled = true), 12, respectDnd = false
            )
        )
    }

    @Test
    fun `邮件默认不受免打扰影响 打开开关后才受约束`() {
        // 静默窗口 23→7，判定时刻 2 点（窗口内）
        val inWindow = NotificationConfig(
            master_enabled = true,
            dnd_enabled = true, dnd_start_hour = 23, dnd_end_hour = 7
        )
        assertTrue(
            "mail_respect_dnd 默认 false → 邮件不受静默时段影响",
            NotificationRoutes.notifyAllowed(inWindow, 2, inWindow.mail_respect_dnd)
        )
        assertFalse(NotificationRoutes.notifyAllowed(inWindow, 2, respectDnd = true))
        // 窗口外（12 点）即使遵守免打扰也放行
        assertTrue(NotificationRoutes.notifyAllowed(inWindow, 12, respectDnd = true))
        // dnd 总开关没开时，遵守与否都不拦
        assertTrue(
            NotificationRoutes.notifyAllowed(
                inWindow.copy(dnd_enabled = false), 2, respectDnd = true
            )
        )
    }

    /**
     * Webhook 的 `respectDnd` 默认 true（它跟状态栏一样会响铃），所以同一份
     * `NotificationConfig` 下两个渠道的放行结果**可以不同** —— 这正是闸门谓词要带渠道参数的理由。
     */
    @Test
    fun `同一份配置下两个渠道的免打扰结论可以不同`() {
        val inWindow = NotificationConfig(
            master_enabled = true,
            dnd_enabled = true, dnd_start_hour = 23, dnd_end_hour = 7
        )
        assertTrue("邮件 mail_respect_dnd=false → 半夜照发", NotificationRoutes.notifyAllowed(inWindow, 2, false))
        assertFalse("Webhook respectDnd=true → 半夜静默", NotificationRoutes.notifyAllowed(inWindow, 2, true))
    }


    @Test
    fun `静默窗口允许跨天且首尾相等视为不静默`() {
        // 跨零点：23→7
        assertTrue(NotificationRoutes.inDndWindow(23, 7, 23))
        assertTrue(NotificationRoutes.inDndWindow(23, 7, 0))
        assertTrue(NotificationRoutes.inDndWindow(23, 7, 6))
        assertFalse("右端开区间：7 点不再静默", NotificationRoutes.inDndWindow(23, 7, 7))
        assertFalse(NotificationRoutes.inDndWindow(23, 7, 12))
        // 同日区间：9→18
        assertTrue(NotificationRoutes.inDndWindow(9, 18, 9))
        assertTrue(NotificationRoutes.inDndWindow(9, 18, 17))
        assertFalse(NotificationRoutes.inDndWindow(9, 18, 18))
        assertFalse(NotificationRoutes.inDndWindow(9, 18, 8))
        // 零长度窗口 = 不静默（用户把两个滑块拖到同一格）
        for (h in 0..23) assertFalse(NotificationRoutes.inDndWindow(5, 5, h))
    }
}
