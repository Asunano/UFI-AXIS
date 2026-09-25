package com.ufi_axis_core.devicespi.adapter

import com.ufi_axis_core.contract.Capability

/**
 * 集中处理器（2026-09-25 批 A1）。
 *
 * ## 它现在做什么：只有转发
 *
 * 全部成员都是对构造进来的 [DeviceAdapter] 的直接委派，没有缓存、没有重试、没有日志、
 * 没有能力校验（能力门禁在 route 层，判据见 [DeviceAdapter] 的 KDoc）。
 * 不要以为它现在还干了别的。
 *
 * ## 那它为什么存在
 *
 * 1. **接缝**：上层（route / collector / scheduler / controller）只依赖本类这一个类型。
 *    换 adapter（接一台非 goform 设备）时，改动落在装配层构造本类的那一处，上层零改动。
 *    此前上层直接消费 `:core:goform` 里的七个具体类，于是「插件能换传输层」这个接缝
 *    与「上层消费什么」这个接缝**不重合** —— 换掉传输层没人用得上。
 * 2. **横切关注点的落点**：后续跨设备的公共口径（写操作的日志口径、能力集的对外出口等）
 *    有一个地方可落，不必在每个域实现里各写一遍。
 *
 * 在这两件事都还没发生之前，它就是个转发壳 —— 这是刻意的：接缝要先立起来，
 * 才谈得上往上加东西。
 */
class DeviceHub(private val adapter: DeviceAdapter) {

    /** 当前 adapter 的标识（= [DeviceAdapter.id]）。 */
    val adapterId: String get() = adapter.id

    /** 当前设备的能力集，原样透出（[DeviceHub] 不加工它）。 */
    val capabilities: Set<Capability> get() = adapter.capabilities

    /** SIM 域入口。 */
    val sim: SimControl get() = adapter.sim

    /** device 域入口（设备本体控制）。 */
    val device: DeviceControl get() = adapter.device

    /** network 域入口（移动数据 / 制式 / 拨号 / 频段 / 流量 / 漫游）。 */
    val network: NetworkControl get() = adapter.network

    /** wifi 域入口（**读写都走这里**；批 A2b 迁入写侧，批 C2 迁入读侧与 `setAccessControlList`）。 */
    val wifi: WifiControl get() = adapter.wifi

    /**
     * signal 域入口（**只有读操作**：信号 / 身份 / 流量统计 / 小区 / LAN / 设置查询 / 排障 dump）。
     *
     * ⚠ 第 1 层字段映射在消费方（`SignalCollector`）而不是实现侧，见 [SignalSource] 的 KDoc。
     */
    val signal: SignalSource get() = adapter.signal

    /** sms 域入口（信箱查询 / 发送 / 删除 / 已读标记 / 条数统计）。 */
    val sms: SmsControl get() = adapter.sms
}
