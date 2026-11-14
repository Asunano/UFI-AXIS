package com.ufi_axis_core.contract

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * [Capability] 的**对外 wire 契约**守门测试（计划书 §7 的 3.7）。
 *
 * 口径与 `DeviceRuntimeTest` 里 `Selection.wire` 那条完全一致：守的是**对外字符串**，
 * 不是内部枚举名。这 11 个取值**可能**出现在 `GET /api/device/capabilities` 的 `capabilities`
 * 数组里（实际下发哪几个取决于当前插件声明了哪几项），app 与 web 照它决定「开关能不能点」——
 * 枚举名怎么重构都无所谓，
 * 但这些字符串一动就是一次接口变更（且旧客户端只会静默把它当成不认识的能力忽略）。
 *
 * 期望值**逐个写死**，刻意不写 `name.lowercase()` —— 那样枚举改名时期望值会跟着一起变，
 * 测试永远绿、什么都守不住。
 *
 * 两端镜像一致性（计划书 3.8：contract 的 wire 名 ↔ `web/src/api/contract.ts` 的字符串
 * 联合类型）**本批不做** —— 那要跟着 app / web 那批 UI 改动一起落。本文件只保证 core
 * 这一侧的取值不会被谁顺手改掉。
 */
class CapabilityWireTest {

    private val why = "这是 /api/device/capabilities 的对外取值，改它等于改 API，app / web 都要同步"

    @Test
    fun `十一个 wire 取值写死不许动`() {
        assertEquals(why, "sms", Capability.SMS.wire)
        assertEquals(why, "sim_slot_switch", Capability.SIM_SLOT_SWITCH.wire)
        assertEquals(why, "band_lock", Capability.BAND_LOCK.wire)
        assertEquals(why, "cell_lock", Capability.CELL_LOCK.wire)
        assertEquals(why, "network_mode", Capability.NETWORK_MODE.wire)
        assertEquals(why, "samba", Capability.SAMBA.wire)
        assertEquals(why, "usb_debug", Capability.USB_DEBUG.wire)
        assertEquals(why, "fota", Capability.FOTA.wire)
        assertEquals(why, "performance_mode", Capability.PERFORMANCE_MODE.wire)
        assertEquals(why, "traffic_limit", Capability.TRAFFIC_LIMIT.wire)
        // 3B（批 L）新增的纯读侧项。它不进任何插件的默认声明（F50 无电池），
        // 但 wire 名一旦下发过就同样是冻结区的一部分。
        assertEquals(why, "battery", Capability.BATTERY.wire)
    }

    @Test
    fun `值域大小固定为 11`() {
        // 值域大小本身也是契约：加一项请连 app / web 一起改（冻结区只增不改，见 §11.4）。
        // 这条红了不代表做错了 —— 它是提醒「你刚扩了一次对外值域」。
        //
        // 2026-09-24（批 L）10 → 11：加了 BATTERY。**这次是刻意改本断言** ——
        // 它存在的全部意义就是「值域一变必须有人显式来改测试」，改它即是完成了那道确认。
        // ⚠ 值域 11 项不等于 /api/device/capabilities 会下发 11 个：
        //   那个数组只含当前插件**声明了**的项，F50 不声明 BATTERY，所以仍是 10 个。
        assertEquals(
            "$why（当前：${Capability.entries.map { it.wire }}）",
            11,
            Capability.entries.size,
        )
    }

    @Test
    fun `wire 名互不相同且是小写 snake`() {
        val wires = Capability.entries.map { it.wire }
        // 两项撞同一个 wire，客户端就分不出是哪个域不支持 —— 比取值写错更难发现
        assertEquals("wire 必须互不相同（$wires）—— $why", wires.size, wires.distinct().size)
        // 风格与 device_profile 块现有键、Selection.wire 一致：小写 + 下划线
        wires.forEach { wire ->
            assertEquals("$wire 必须是小写", wire.lowercase(), wire)
            assertEquals(
                "$wire 只许用 a-z 与下划线（连字符/驼峰会让两端镜像更容易抄错）",
                wire,
                wire.filter { it in 'a'..'z' || it == '_' },
            )
        }
    }

    @Test
    fun `fromWire 对每个取值回同一个枚举项，认不出回 null`() {
        Capability.entries.forEach { cap ->
            assertSame("fromWire(${cap.wire}) 必须回 ${cap.name}", cap, Capability.fromWire(cap.wire))
        }
        // 认不出返回 null（不抛异常）：将来两端镜像对不齐时，读侧要能自己决定忽略还是报错
        assertNull(Capability.fromWire("no_such_capability"))
        assertNull(Capability.fromWire(""))
        // 大小写敏感：对外取值就是小写，"SMS" 不是合法的 wire 名
        assertNull(Capability.fromWire("SMS"))
    }
}
