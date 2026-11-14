package com.ufi_axis_core.devicespi

/**
 * 构造 [DeviceTransport] 需要的连接参数。
 *
 * 三个字段就是现在装配层构造传输层时传的那三个（设备 IP、后台端口、后台密码）。
 *
 * ## 为什么符号名是中立的，取值却沿用 `goform*` 配置键
 *
 * 取值来自 `AppSettings` 里 `goform*` 那几个配置键（历史名字，已经进了配置文件与
 * 前端设置页，改名会破坏存量部署）；但**类型名与字段名必须中立** ——
 * 这个 data class 是 SPI 的一部分，第二个协议的插件也要吃它，
 * 名字里带 goform 就等于把协议味道钉进契约层。
 *
 * 所以：**配置键叫 goform，契约字段叫 device / port / password。**
 * 两者的对应关系只在装配层那一处（阶段 6 接线时落）。
 */
data class TransportConfig(
    /** 设备后台 IP（如 `192.168.0.1`）。 */
    val deviceIp: String,
    /** 设备后台端口（如 `8080`）。 */
    val port: Int,
    /** 设备后台密码**明文**。怎么哈希、怎么参与握手是传输层实现的事，契约层不关心。 */
    val password: String,
)
