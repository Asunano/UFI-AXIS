package com.ufi_axis_core.collector.at

/**
 * AT 下发通道的统一形状。
 *
 * [ATChannel] 只依赖这个接口，换实现（service call / 未来的串口）不动上层。
 * 限流、退避、熔断统一由 [ATChannel] 负责，实现类**不要**再各做一套 —— 两层限流叠在一起
 * 只会让"最小命令间隔"变成一个没人说得清的值（已删除的 sendat 执行器就是前车之鉴）。
 */
interface AtTransport {

    /** 通道标识，用于日志与 `/api/at/status` 的 `method` 字段。 */
    val name: String

    /**
     * 这台设备上是否可用。
     *
     * **探测代价必须低**：不许在这里真发一条 AT —— init() 在服务启动路径上，
     * 一次 modem 往返（可能好几秒）会拖慢整个 core 起步。
     */
    suspend fun probe(): Boolean

    /**
     * 下发一条 AT 指令。
     *
     * @param command 完整指令（如 `AT+CSQ`）
     * @param slot SIM 卡槽（0 / 1）
     * @param timeoutMs 单次调用的硬超时
     * @return 响应文本；失败返回 null（由上层计入熔断）
     */
    suspend fun sendCommand(command: String, slot: Int = 0, timeoutMs: Long = 5000): String?

    /** 清掉实现内部可能存在的失败计数。 */
    fun reset()
}
