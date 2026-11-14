package com.ufi_axis.ui.screens

import com.ufi_axis.data.model.CarrierBillSettingsState
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 账单设置页/自动登录页共用的数值项。
 *
 * 每个字段只提交自己那一个键；core 对缺失字段保持原值，避免整份覆盖。
 */
internal enum class BillSettingField(
    val title: String,
    val hint: String,
    val range: IntRange,
) {
    RefreshInterval("刷新间隔（分钟）", "5–240 分钟", 5..240),
    CheckInterval("检查间隔（分钟）", "1–60 分钟", 1..60),
    ReloginInterval("重登间隔（分钟）", "10–720 分钟", 10..720),
    DailySms("每日短信上限（条/日）", "1–20 条/日（设备总额度）", 1..20),
    ;

    fun currentValue(state: CarrierBillSettingsState): Int = when (this) {
        RefreshInterval -> state.refresh.intervalMinutes
        CheckInterval -> state.keepalive.checkIntervalMinutes
        ReloginInterval -> state.keepalive.reloginIntervalMinutes
        DailySms -> state.keepalive.dailySmsLimit
    }

    fun applyTo(state: CarrierBillSettingsState, value: Int): CarrierBillSettingsState = when (this) {
        RefreshInterval -> state.copy(refresh = state.refresh.copy(intervalMinutes = value))
        CheckInterval -> state.copy(keepalive = state.keepalive.copy(checkIntervalMinutes = value))
        ReloginInterval -> state.copy(keepalive = state.keepalive.copy(reloginIntervalMinutes = value))
        DailySms -> state.copy(keepalive = state.keepalive.copy(dailySmsLimit = value))
    }

    fun patch(value: Int): JsonObject = when (this) {
        RefreshInterval -> buildJsonObject {
            put("refresh", buildJsonObject { put("interval_minutes", value) })
        }

        CheckInterval -> buildJsonObject {
            put("keepalive", buildJsonObject { put("check_interval_minutes", value) })
        }

        ReloginInterval -> buildJsonObject {
            put("keepalive", buildJsonObject { put("relogin_interval_minutes", value) })
        }

        DailySms -> buildJsonObject {
            put("keepalive", buildJsonObject { put("daily_sms_limit", value) })
        }
    }
}
