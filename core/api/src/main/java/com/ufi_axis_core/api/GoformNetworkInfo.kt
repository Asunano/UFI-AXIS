package com.ufi_axis_core.api

/**
 * 网络类型信息 — DataHub 统一返回格式
 *
 * 供 NetworkRoutes /status 和 DeviceRoutes /info 共用，
 * 通过 DataHub.getNetworkTypeInfo() 获取（内含 **30s** TTL 缓存）。
 *
 * ## [pppStatus] 是链路态，不是「移动数据开关」
 *
 * 2026-11-02 起明确拆开：切制式 / 锁频 / 关飞行模式都会让设备**先拆链再重拨**，
 * 这段时间里本字段必然是断的。30s 的 TTL 又会把这个瞬态钉住，于是拿它当开关的客户端
 * 会看到「明明是开的却显示关，过一会刷新才变回来」。
 *
 * - 需要**链路**：走 [DataHub.getLivePppStatus]（不吃 30s 缓存）。
 * - 需要**开关**：走 `NetworkController.resolveMobileDataSwitch`（用户意图 + 宽限期）。
 */
data class GoformNetworkInfo(
    val networkType: String,
    val networkProvider: String,
    val pppStatus: String
)
