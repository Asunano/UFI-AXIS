package com.ufi_axis.viewmodel.state

import com.ufi_axis_core.contract.Capability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// 设备能力集判定逻辑的单测（批 O / 计划书 §7 阶段 3 的 3.5）。
//
// ── 为什么这批必须靠单测而不是真机 ──
// ZteF50Plugin 声明了**全部 10 项**能力，所以置灰分支在手上这台真机上**永远不会触发**。
// 真机能验的只有一件事：「全部支持时 UI 与改动前一字不差」（对应下面
// [f50 全部声明时每一项都判定为支持] 这条，以及页面上开关照旧可点）。
// 「不支持时灰掉」与「拉取失败时降级」这两条只能在这里验。
//
// 这也是判定逻辑被抽成纯函数 [DeviceCapabilityState.supports] 的原因 ——
// 它不碰 Retrofit、不碰 Compose、不碰 Android，纯 JVM 就能跑完。
class DeviceCapabilityStateTest {

    // ZTE F50 实际声明的 10 项（core 的 `GET /api/device/capabilities` 原样下发这些 wire 名）。
    private val f50Wires = listOf(
        "sms", "sim_slot_switch", "band_lock", "cell_lock", "network_mode",
        "samba", "usb_debug", "fota", "performance_mode", "traffic_limit"
    )

    @Test
    fun `f50 全部声明时每一项都判定为支持`() {
        val state = DeviceCapabilityState.fromWires(f50Wires)
        assertTrue("成功解析过一次就必须是 loaded", state.loaded)
        assertEquals(10, state.supported.size)
        assertTrue("不该有认不出的 wire 名", state.unknownWires.isEmpty())
        for (cap in state.supported) {
            assertTrue("F50 声明了 ${cap.wire}，判定必须是支持", state.supports(cap))
        }
    }

    // ⚠ 本批最关键的一条：拉取失败必须降级成「全部支持」，绝不许「全部不支持」。
    // 反了的话一次网络抖动就能把整个设置页灰掉 —— 而设备能力与请求成不成功无关。
    @Test
    fun `拉取失败时全部域都判定为支持`() {
        val failed = DeviceCapabilityState.UNKNOWN
        assertFalse("没成功拉到过就不能是 loaded", failed.loaded)
        assertTrue("失败态不该带任何已支持项", failed.supported.isEmpty())
        for (cap in Capability.entries) {
            assertTrue(
                "拉取失败时 ${cap.wire} 必须按支持处理（保持现状行为）",
                failed.supports(cap)
            )
        }
    }

    // 「还没拉」与「拉失败了」对 UI 是同一个结论，所以默认构造出来的值必须等于 UNKNOWN。
    @Test
    fun `默认构造等于未知态`() {
        assertEquals(DeviceCapabilityState.UNKNOWN, DeviceCapabilityState())
        assertTrue(DeviceCapabilityState().supports(Capability.SAMBA))
    }

    @Test
    fun `缺某一项时只有那一项判定为不支持`() {
        val state = DeviceCapabilityState.fromWires(f50Wires - "samba")
        assertFalse("没声明 samba，必须判定为不支持", state.supports(Capability.SAMBA))
        assertTrue(state.supports(Capability.FOTA))
        assertTrue(state.supports(Capability.CELL_LOCK))
        assertTrue(state.supports(Capability.NETWORK_MODE))
        assertTrue(state.supports(Capability.PERFORMANCE_MODE))
    }

    // 空数组 + 请求成功 = 「这台设备一个域都不支持」，是合法表达，不是失败。
    // 它与 UNKNOWN 的区别正是 loaded 这个标志存在的全部意义。
    @Test
    fun `成功拿到空能力集时全部域都判定为不支持`() {
        val state = DeviceCapabilityState.fromWires(emptyList())
        assertTrue("请求成功了就是 loaded，哪怕数组是空的", state.loaded)
        for (cap in Capability.entries) {
            assertFalse("空能力集里 ${cap.wire} 必须判定为不支持", state.supports(cap))
        }
    }

    // 新 core 配旧 app：下发了本版枚举里没有的 wire 名。按冻结区口径忽略，
    // 但收进 unknownWires 以便排查，且不能影响认识的那些项。
    @Test
    fun `认不出的 wire 名被收集且不影响已知项判定`() {
        val state = DeviceCapabilityState.fromWires(listOf("samba", "raw_goform", "fota"))
        assertEquals(listOf("raw_goform"), state.unknownWires)
        assertTrue(state.supports(Capability.SAMBA))
        assertTrue(state.supports(Capability.FOTA))
        assertFalse(state.supports(Capability.CELL_LOCK))
    }

    // 置灰原因的口径（§7 / §11.6）：只说「设备不支持」，
    // 不许出现「功能未开启」「权限不足」这类把用户引向别处的说法。
    @Test
    fun `置灰原因文案只说设备不支持`() {
        val note = deviceUnsupportedNote("文件共享（SAMBA）")
        assertEquals("这台设备不支持文件共享（SAMBA）", note)
        assertFalse(note.contains("未开启"))
        assertFalse(note.contains("权限"))
    }
}
