// 告警 → 普通 Toast 的映射规则单测（原 UfiAlertBannerTest）。
//
// 2026-08-30：告警不再有独立的应用内 banner，改走 UfiToastOverlay（见 UfiAlertToastBridge）。
// 原来的两条规则随之变化：
//   - shouldAutoDismiss（critical 永不自动收）→ durationMs（critical 停留更久，仍会自动收）；
//   - isMuted（上滑关闭后的静音窗口）→ 已删除，静音入口回归通知配置的 alert_enabled。
package com.ufi_axis.ui.components.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UfiAlertToastRulesTest {

    @Test
    fun critical_is_recognized_case_insensitively() {
        assertTrue("critical 应识别", UfiAlertToastRules.isCritical("critical"))
        assertTrue("大写 CRITICAL 也应识别", UfiAlertToastRules.isCritical("CRITICAL"))
        assertFalse("warning 不是 critical", UfiAlertToastRules.isCritical("warning"))
        assertFalse("空字符串按非 critical 处理", UfiAlertToastRules.isCritical(""))
    }

    @Test
    fun critical_stays_longer() {
        assertEquals(6000L, UfiAlertToastRules.durationMs("critical"))
        assertEquals(3000L, UfiAlertToastRules.durationMs("warning"))
        assertEquals(3000L, UfiAlertToastRules.durationMs(""))
    }

    @Test
    fun level_maps_to_toast_type() {
        assertEquals(ToastType.ERROR, UfiAlertToastRules.toastType("critical"))
        assertEquals(ToastType.WARNING, UfiAlertToastRules.toastType("warning"))
        assertEquals(ToastType.INFO, UfiAlertToastRules.toastType("normal"))
        assertEquals(ToastType.INFO, UfiAlertToastRules.toastType(""))
    }

    @Test
    fun unknown_alert_type_falls_back_to_raw_value() {
        assertEquals("CPU 温度", UfiAlertToastRules.alertTypeLabel("cpu_temp"))
        assertEquals("some_new_type", UfiAlertToastRules.alertTypeLabel("some_new_type"))
    }
}
