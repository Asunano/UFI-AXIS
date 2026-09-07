package com.ufi_axis_core.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import com.ufi_axis_core.util.AppLogger

/**
 * 电池事件通知接收器
 * 监听电池状态变化，在特定事件发生时触发回调
 * 一次性触发：条件清除后重置，可再次触发
 *
 * 事件：
 * - 低电量 (<=10%, 放电中)
 * - 极低电量 (<=5%, 放电中)
 * - 充满 (100% 或 BATTERY_STATUS_FULL)
 * - 开始充电 (非充满)
 */
class BatteryNotifier(
    private val onLowBattery: (Int) -> Unit,
    private val onVeryLowBattery: (Int) -> Unit,
    private val onFullBattery: () -> Unit,
    private val onChargeStart: () -> Unit
) : BroadcastReceiver() {

    private val tag = "BatteryNotifier"

    private var triggeredLow = false
    private var triggeredVeryLow = false
    private var triggeredFull = false
    private var triggeredCharge = false

    override fun onReceive(context: Context?, intent: Intent?) {
        intent ?: return

        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)

        if (level < 0 || scale <= 0) return

        val batteryPct = level * 100 / scale
        val isDischarging = status == BatteryManager.BATTERY_STATUS_DISCHARGING
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
        val isFull = status == BatteryManager.BATTERY_STATUS_FULL ||
                (batteryPct >= 100 && isCharging)

        // 低电量 (10%)
        if (batteryPct <= 10 && batteryPct > 5 && isDischarging && !triggeredLow) {
            triggeredLow = true
            AppLogger.i(tag, "Low battery: $batteryPct%")
            onLowBattery(batteryPct)
        }

        // 极低电量 (5%)
        if (batteryPct <= 5 && isDischarging && !triggeredVeryLow) {
            triggeredVeryLow = true
            AppLogger.i(tag, "Very low battery: $batteryPct%")
            onVeryLowBattery(batteryPct)
        }

        // 充满
        if (isFull && !triggeredFull) {
            triggeredFull = true
            AppLogger.i(tag, "Battery full")
            onFullBattery()
        }

        // 开始充电（非充满）
        if (isCharging && !triggeredCharge && !isFull) {
            triggeredCharge = true
            AppLogger.i(tag, "Charging started: $batteryPct%")
            onChargeStart()
        }

        // 重置触发器
        if (batteryPct > 15) triggeredLow = false
        if (batteryPct > 10) triggeredVeryLow = false
        if (!isFull) triggeredFull = false
        if (!isCharging) triggeredCharge = false
    }
}
