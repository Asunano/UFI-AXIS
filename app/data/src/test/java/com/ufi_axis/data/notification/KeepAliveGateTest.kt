package com.ufi_axis.data.notification

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [KeepAliveGate.shouldRun] 的真值表（2026-09-05）。
 *
 * 这个判据被 4 个入口共用（`NotifyService.startKeepAlive` / `NotifyService.onStartCommand` 自查 /
 * `NotificationConfigClient.startKeepAlive` / `NotifyDispatchReceiver` 转发连接参数），
 * 而它们全都需要 `Context` + `SharedPreferences` + 进程名，纯 JVM 测不了。
 * 所以判据本体抽成了无依赖的纯函数，这里把**四种组合**逐一钉住 ——
 * 上午那次「常驻通知开不起来」的回归，本质就是判据被悄悄改成了 AND 而没有任何断言拦住。
 */
class KeepAliveGateTest {

    /** 保活开 + 总闸开 → 跑。 */
    @Test
    fun bothOn_shouldRun() {
        assertTrue(KeepAliveGate.shouldRun(keepAliveEnabled = true, alertNotifEnabled = true))
    }

    /**
     * 保活开 + 总闸关 → **仍然要跑**。
     *
     * 这一条就是这次回归的反例。`:ufi_notify` 是短信 / 验证码推送的唯一订阅方，
     * 那两条通知只看 `sms_notification_enabled`（默认 true），不受告警总闸约束；
     * 而「系统通知推送」默认 false —— 把它 AND 进来的直接后果是：
     * 用户在「后台守护」页打开保活，开关显示为开，服务却起不来（本仓禁止的「假开关」）。
     */
    @Test
    fun keepAliveOnly_shouldStillRun() {
        assertTrue(
            "「系统通知推送」不得参与保活闸门：它只决定服务跑起来之后发不发告警类通知，" +
                "不决定守护进程要不要活着。",
            KeepAliveGate.shouldRun(keepAliveEnabled = true, alertNotifEnabled = false)
        )
    }

    /**
     * 保活关 + 总闸开 → **不许跑**。
     *
     * 常驻通知（id 9001 / channel `ufi_notify_keepalive`）与额外耗电是用户可见代价，
     * 必须由「前台服务保活」开关授权。少了这一条就是「没开保活却有一条关不掉的固定通知」
     * （2026-09-05 上午之前的实测事故）。
     */
    @Test
    fun alertOnlyWithoutKeepAlive_mustNotRun() {
        assertFalse(
            "没开「前台服务保活」时任何路径都不许有那条常驻通知。",
            KeepAliveGate.shouldRun(keepAliveEnabled = false, alertNotifEnabled = true)
        )
    }

    /** 两个都关 → 不许跑。 */
    @Test
    fun bothOff_mustNotRun() {
        assertFalse(KeepAliveGate.shouldRun(keepAliveEnabled = false, alertNotifEnabled = false))
    }

    /**
     * 判据**只由保活键决定** —— 总闸取任何值都不能改变结论。
     *
     * 单独一条：上面四个用例是点，这一条是「不变量」，改语义时它会先红。
     */
    @Test
    fun gate_mustDependOnlyOnKeepAliveSwitch() {
        for (keepAlive in listOf(true, false)) {
            val withAlertOn = KeepAliveGate.shouldRun(keepAlive, alertNotifEnabled = true)
            val withAlertOff = KeepAliveGate.shouldRun(keepAlive, alertNotifEnabled = false)
            assertTrue(
                "keepAliveEnabled=$keepAlive 时，结论不得随「系统通知推送」变化" +
                    "（当前 总闸开=$withAlertOn / 总闸关=$withAlertOff）。",
                withAlertOn == withAlertOff
            )
            assertTrue(
                "keepAliveEnabled=$keepAlive 时结论必须等于它本身。",
                withAlertOn == keepAlive
            )
        }
    }
}
