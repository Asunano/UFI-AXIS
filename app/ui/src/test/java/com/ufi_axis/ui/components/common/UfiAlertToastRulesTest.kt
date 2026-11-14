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
        // 已登记的 type 走中文标签。
        assertEquals("设备温度", UfiAlertToastRules.alertTypeLabel("temperature"))
        // 未登记的一律回落成 type 本身。
        //
        // `cpu_temp` 在这里是**有意**的用例：它曾经有中文标签，但 2026-09-21 对齐
        // AlertEngine 实际的 8 个 type 时被删掉（引擎里从来没产出过这个 type，是早期设计残留）。
        // 本条断言原来写的是 `alertTypeLabel("cpu_temp") == "CPU 温度"`，删除后一直是红的 ——
        // 它断言的是已经不正确的旧行为，不是发现了 bug。
        assertEquals("cpu_temp", UfiAlertToastRules.alertTypeLabel("cpu_temp"))
        assertEquals("some_new_type", UfiAlertToastRules.alertTypeLabel("some_new_type"))
    }
}
