package com.ufi_axis_core.api.routes

import com.ufi_axis_core.api.ResponseHelper.toJsonElement
import com.ufi_axis_core.contract.DeviceFields
import kotlinx.serialization.json.*

/**
 * 流量限额 canonical 字段 → API 响应的唯一映射入口。
 *
 * 存在的理由：`GET /api/device/traffic-limit` 和 `GET /api/dashboard/summary` 的
 * `traffic_limit` 原本各写了一份解析，两边判定逻辑漂移过（dashboard 侧 `auto_clear`
 * 恒为 false、`enabled` 不回退 `flux_*`），前端两个页面显示不一致。现在统一走这里。
 *
 * ## 输入已经是 canonical
 *
 * 设备侧的坑（`data_volume_limit_size` 是 `"数值_乘数"` 复合串；`data_volume_limit_unit`
 * 恒为 `"MB"` 没有意义；开关有 `"1"/"on"` 两套编码；部分固件只填 `flux_*` 前缀）全部在
 * `GoformSignalClient.getDataUsage()` 的归一化里抹平了，见 `ZteGoformProfile` 的
 * `splitDataVolumeLimit`。**本文件不认识任何设备字段名**，只负责：
 * 把 `"1"/"0"` 转成 JSON Boolean、给缺失字段补默认值、算 `used_bytes`、
 * 以及把「配置」与「实时用量」切成两半（分开缓存，见 [withFreshUsage]）。
 */
internal object TrafficLimitMapper {

    private const val DEFAULT_UNIT = "GB"
    private const val DEFAULT_ALERT_PERCENT = "80"
    private const val DEFAULT_CLEAR_DATE = "1"

    /**
     * 配置部分（可安全缓存）。用量字段不在这里 —— 见 [withFreshUsage]。
     */
    fun buildConfig(normalized: JsonObject): JsonElement = toJsonElement(mapOf(
        DeviceFields.TrafficLimit.ENABLED to truthy(str(normalized, DeviceFields.TrafficLimit.ENABLED)),
        DeviceFields.TrafficLimit.LIMIT_VALUE to (str(normalized, DeviceFields.TrafficLimit.LIMIT_VALUE) ?: ""),
        // 归一化在限额未设置时省略这两个 key（"缺失 = 省略"），这里补成前端一直吃的形状：
        // 单位给 GB、字节给 0，前端按 limit_bytes == 0 判断"未设置限额"。
        DeviceFields.TrafficLimit.LIMIT_UNIT_DISPLAY to
            (str(normalized, DeviceFields.TrafficLimit.LIMIT_UNIT_DISPLAY) ?: DEFAULT_UNIT),
        DeviceFields.TrafficLimit.LIMIT_BYTES to long(normalized, DeviceFields.TrafficLimit.LIMIT_BYTES),
        DeviceFields.TrafficLimit.ALERT_PERCENT to
            (str(normalized, DeviceFields.TrafficLimit.ALERT_PERCENT) ?: DEFAULT_ALERT_PERCENT),
        DeviceFields.TrafficLimit.AUTO_CLEAR to truthy(str(normalized, DeviceFields.TrafficLimit.AUTO_CLEAR)),
        DeviceFields.TrafficLimit.CLEAR_DATE to
            (str(normalized, DeviceFields.TrafficLimit.CLEAR_DATE) ?: DEFAULT_CLEAR_DATE)
    ))

    /**
     * 把本次查询的实时用量合并进（可能来自缓存的）配置。
     *
     * `monthly_*` 是实时计数器，不能和配置共用缓存条目 —— 否则「本月已用」会滞后整个
     * 缓存 TTL，和设备实际值对不上。
     */
    fun withFreshUsage(config: JsonElement, normalized: JsonObject): JsonElement {
        val rx = long(normalized, DeviceFields.TrafficLimit.MONTHLY_RX_BYTES)
        val tx = long(normalized, DeviceFields.TrafficLimit.MONTHLY_TX_BYTES)
        val usage = toJsonElement(mapOf(
            DeviceFields.TrafficLimit.MONTHLY_RX_BYTES to rx,
            DeviceFields.TrafficLimit.MONTHLY_TX_BYTES to tx,
            DeviceFields.TrafficLimit.MONTHLY_TIME to long(normalized, DeviceFields.TrafficLimit.MONTHLY_TIME),
            // 已用 = rx + tx，在这里算好，避免每个前端各加一遍还可能漏掉一项
            DeviceFields.TrafficLimit.USED_BYTES to (rx + tx)
        )).jsonObject
        return JsonObject(config.jsonObject + usage)
    }

    /** 一步到位：不需要分开缓存配置的调用方（如 dashboard summary 自身已整体缓存）用这个。 */
    fun build(normalized: JsonObject): JsonElement = withFreshUsage(buildConfig(normalized), normalized)

    private fun str(obj: JsonObject, key: String) = obj[key]?.jsonPrimitive?.contentOrNull

    private fun long(obj: JsonObject, key: String) =
        obj[key]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L

    /** 归一化后布尔是 `"1"`/`"0"` 字符串；响应里历史上给的是 JSON Boolean，保持不变。 */
    private fun truthy(v: String?) = v == "1"
}

