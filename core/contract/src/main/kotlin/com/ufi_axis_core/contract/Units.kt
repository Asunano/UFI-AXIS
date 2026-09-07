package com.ufi_axis_core.contract

/**
 * 单位与哨兵值约定 —— 注释即文档。
 *
 * 这里登记的每一条都对应过一个已发生的真实 bug（时间戳被再乘 1000、速率被当成 bit/s、
 * 进度 `-1` 被画成 -100%…）。**改动任何一条都必须同时改 app 与 web。**
 */
object Units {

    // ───────────────────────── 时间 ─────────────────────────

    /**
     * 全链路时间戳一律是**毫秒**（core 侧 `System.currentTimeMillis()`，WS 帧的
     * `timestamp` 同样是 ms）。客户端拿到后**不要**再 `* 1000`。
     */
    const val TIMESTAMP_UNIT = "ms"

    /** `api/alerts/list` 的 `start_time` / `end_time` 也是 ms，且 `start_time` 为**闭区间**。 */
    const val ALERT_TIME_RANGE_UNIT = "ms"

    // ───────────────────────── 流量 ─────────────────────────

    /**
     * `rx_speed` / `tx_speed`：**字节/秒**（不是 bit/s、不是 KB/s）。
     * 展示成 Mbps 需要 `* 8 / 1_000_000`。
     */
    const val TRAFFIC_SPEED_UNIT = "bytes/s"

    /** `rx_bytes` / `tx_bytes` / 月度用量：**字节**。 */
    const val TRAFFIC_TOTAL_UNIT = "bytes"

    // ───────────────────────── 下载 ─────────────────────────

    /**
     * 下载进度是 **0f..1f 的归一化 Float**（不是 0..100）。
     * 唯一定义点：`DownloadManager` 的 `(completedLen / totalLen).coerceIn(0f, 1f)`。
     */
    const val DOWNLOAD_PROGRESS_RANGE = "0f..1f"

    /**
     * 进度哨兵：`-1f` = 元数据获取阶段（BT/磁力链尚未拿到总大小），**进度未知**。
     * UI 必须走"未知"分支，不能按 -100% 渲染。
     */
    const val DOWNLOAD_PROGRESS_UNKNOWN = -1f

    /** 总大小哨兵：`-1L` = 总大小未知（与 [DOWNLOAD_PROGRESS_UNKNOWN] 相关但不等价）。 */
    const val DOWNLOAD_TOTAL_SIZE_UNKNOWN = -1L

    /**
     * 下载状态字符串**暂不进 contract**：core 侧 7 个取值（pending/meta/downloading/
     * paused/completed/error/removed）既无枚举也无常量，且 `removed` 语义自相矛盾
     * （aria2 侧输入会被归一化成 `error`，内部永不产生 `removed`，但去重判断又拿它当内部状态）。
     * 先在 core 修掉这一处再搬，否则 contract 会把一个 bug 固化成"标准"。
     */
    const val DOWNLOAD_STATUS_NOTE = "见 KDoc：状态集合待 core 修正 removed 语义后再进 contract"

    // ───────────────────────── 温度 / 电量 ─────────────────────────

    /**
     * 设备温度：core 读 `thermal_zone` 的**毫摄氏度**后已除以 1000，
     * 对外暴露的 `temperature` 是**摄氏度 Double**。客户端不要再换算。
     */
    const val TEMPERATURE_UNIT = "°C"

    /** 电池温度：原始值是 0.1°C 单位，core 已除以 10 → 对外是 °C。 */
    const val BATTERY_TEMPERATURE_UNIT = "°C"

    /** 电池电压：原始 mV，core 已除以 1000 → 对外是 V。 */
    const val BATTERY_VOLTAGE_UNIT = "V"

    /** 电量百分比哨兵：`-1` = 读取失败/未知。 */
    const val BATTERY_PERCENT_UNKNOWN = -1

    /** CPU 频率：`freq_mhz` 是 **MHz Double**（core 已从 kHz 除以 1000）。 */
    const val CPU_FREQ_UNIT = "MHz"

    // ───────────────────────── 其它 ─────────────────────────

    /**
     * 测速：`ckSize` 是**1 MiB 块的个数**（core 侧 buffer 固定 `1024 * 1024`），
     * 默认 10，钳制 `1..4096`。即最大约 4 GiB。
     */
    const val SPEEDTEST_CHUNK_BYTES = 1024 * 1024
    const val SPEEDTEST_CHUNKS_DEFAULT = 10
    val SPEEDTEST_CHUNKS_RANGE = 1..4096

    /** `api/debug-logs` 返回的是**字符串数组**，不是对象数组。 */
    const val DEBUG_LOGS_SHAPE = "Array<String>"
}
