package com.ufi_axis_core.devicespi.adapter

import com.ufi_axis_core.contract.Capability

/**
 * 设备适配接口（2026-09-25 批 A1）。
 *
 * 一台设备一份实现：goform 系是 `:core:device-plugins` 里的 `ZteGoformAdapter`（委派给现有
 * `Goform*Client`），非 goform 设备（飞猫等）另写一份。上层（route / collector / scheduler /
 * controller）只认 [DeviceHub]，换设备时改的只有装配层那一行。
 *
 * ## 为什么 [sim] 是非空的
 *
 * 「这台设备支不支持切卡」的唯一判据是 [capabilities]：route 层的能力门禁
 * （`requireCapability(Capability.SIM_SLOT_SWITCH)`）在请求到达 adapter 之前就会回 501
 * `NOT_SUPPORTED`。再用一个可空的 `sim` 字段表达同一件事，就是**两份判据**——
 * 一份在能力集、一份在字段是否为 null，早晚分叉（能力集说支持、字段却是 null，
 * 或反过来），到时候没人说得清哪一份是真的。所以域字段一律非空，
 * 「不支持」只由 [capabilities] 表达。
 *
 * ## 域接口是一批一个地长出来的
 *
 * 六个域到批 C1 **全部到齐**：`sim`（批 A1）、`device`（批 A2a）、`network` / `wifi`（批 A2b）、
 * `signal`（批 B2）、`sms`（批 C1）。每批加一个、连同它的实现与调用点一起落地，
 * **不预先声明空成员** —— 声明了没人实现的成员就是死代码：实现方要写一堆 `TODO()`，
 * 调用方看不出哪个域真能用。再加第七个域时沿用这条口径。
 */
interface DeviceAdapter {

    /** 适配器标识，与 [com.ufi_axis_core.devicespi.DevicePlugin.id] 同源（装配层原样传入）。 */
    val id: String

    /**
     * 这台设备的能力集，与 [com.ufi_axis_core.devicespi.DevicePlugin.capabilities] 同源。
     *
     * 它是「某个域的某个操作能不能做」的**唯一**判据（见类 KDoc 关于 [sim] 非空的说明）。
     */
    val capabilities: Set<Capability>

    /** SIM 域。非空，理由见类 KDoc。 */
    val sim: SimControl

    /** device 域：设备本体的控制（重启 / 关机 / 恢复出厂 / 指示灯 / DHCP / 基站锁定等）。非空，理由见类 KDoc。 */
    val device: DeviceControl

    /** network 域：移动数据 / 制式 / 拨号 / 频段锁定 / 流量 / 漫游。非空，理由见类 KDoc。 */
    val network: NetworkControl

    /** wifi 域：热点配置 / 开关 / 频段 / 功率 / 休眠 + 读侧（状态 / 已连客户端 / 二维码 / 接入控制名单）。非空，理由见类 KDoc。 */
    val wifi: WifiControl

    /**
     * signal 域：**只有读操作**（信号 / 身份 / 流量统计 / 小区 / LAN / 设置查询 / 排障 dump）。
     * 非空，理由见类 KDoc。
     *
     * ⚠ 本域有一处与其它域相反的不对称：signal 的第 1 层字段映射在**消费方**
     * （`SignalCollector` 拿 profile 做），不在实现侧 —— 理由见 [SignalSource] 的类 KDoc。
     */
    val signal: SignalSource

    /**
     * sms 域：信箱查询 / 发送 / 删除 / 已读标记 / 条数统计。非空，理由见类 KDoc。
     *
     * 「这台设备能不能发短信」同样只由 [capabilities]（`Capability.SMS`）回答 ——
     * route 层的 `/api/sms/send` 门禁在请求到达 adapter 之前就会回 501。
     */
    val sms: SmsControl
}
