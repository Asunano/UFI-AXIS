package com.ufi_axis_core.util

import kotlinx.serialization.json.Json

/**
 * 持久化配置读写统一用的 [Json] 实例。
 *
 * 三个特性都是"配置"这一场景必需的，缺一个就会出问题：
 * - `encodeDefaults = true` —— 全新设备 GET 必须返回完整默认值，否则两端各自兜默认值，
 *   又回到「各说各话」；
 * - `ignoreUnknownKeys = true` —— 新旧版本字段不一致时不能整份解析失败；
 * - `isLenient = true` —— 容忍手写/历史配置里的非严格 JSON。
 *
 * 2026-09-02：此前 AlertEngine / MonitorRoutes / NotificationRoutes 各自持有一份
 * 完全相同的配置（连注释都是抄的），任何一处调整都会悄悄和另两处分叉。
 */
val ConfigJson: Json = Json {
    encodeDefaults = true
    ignoreUnknownKeys = true
    isLenient = true
}
