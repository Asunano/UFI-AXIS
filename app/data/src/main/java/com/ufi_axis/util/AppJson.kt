package com.ufi_axis.util

import kotlinx.serialization.json.Json

/**
 * 全局共享 kotlinx.serialization Json 实例。
 *
 * 等价于原 Gson 配置 `setLenient()`（T9 迁移后统一使用 kotlinx.serialization）：
 *  - [Json.isLenient]：容忍 goform 透传的非严格 JSON（如字符串数字 `"rsrp":"99"`、重复 key 等）。
 *  - [Json.ignoreUnknownKeys]：忽略后端多返回的未知字段，避免模型升级时解析失败。
 *  - [Json.coerceInputValues]：缺失/非法输入回落到字段默认值，保证健壮性。
 *  - [Json.encodeDefaults]：**显式设为 true**——否则像 `ScheduledTask.enabled=true` 这种
 *    与默认值相等的字段会被省略，导致 PUT body 缺失关键字段，后端
 *    `p["enabled"] ?: existing.enabled` 兜底后状态不变（开关永远回弹）。
 *    定时任务模块 2026-08-18 复现：用户点 Switch 把 disabled→enabled，body 不带
 *    `enabled` 字段，后端复用 existing.enabled=false，开关卡死。
 *
 * 用于 app/data 自有模型的（反）序列化；Retrofit 转换器见 [com.ufi_axis.data.api.AppJsonConverterFactory]。
 */
val AppJson = Json {
    isLenient = true
    ignoreUnknownKeys = true
    coerceInputValues = true
    encodeDefaults = true
}
