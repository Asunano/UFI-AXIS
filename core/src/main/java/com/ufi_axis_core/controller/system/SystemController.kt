package com.ufi_axis_core.controller.system

import com.ufi_axis_core.controller.goform.GoformDeviceClient
import com.ufi_axis_core.util.AppLogger
import com.ufi_axis_core.util.ShellExecutor

/**
 * 系统控制器
 * 设备重启、关机等系统级操作
 */
class SystemController(
    private val deviceClient: GoformDeviceClient
) {
    private val tag = "SystemController"

    /**
     * 重启设备
     * 优先 Goform，失败后 fallback 到 reboot 命令
     */
    suspend fun reboot(): Boolean {
        AppLogger.i(tag, "Rebooting device")

        // 方式1: Goform
        if (deviceClient.rebootDevice()) {
            return true
        }

        // 方式2: Shell reboot
        val result = ShellExecutor.executeAsRoot("reboot")
        return result.isSuccess
    }

    /**
     * 获取设备型号信息
     */
    suspend fun getDeviceModel(): Map<String, String> {
        return mapOf(
            "brand" to (android.os.Build.BRAND ?: "Unknown"),
            "model" to (android.os.Build.MODEL ?: "Unknown"),
            "device" to (android.os.Build.DEVICE ?: "Unknown"),
            "manufacturer" to (android.os.Build.MANUFACTURER ?: "Unknown"),
            "android_version" to (android.os.Build.VERSION.RELEASE ?: "Unknown"),
            "sdk_version" to android.os.Build.VERSION.SDK_INT.toString(),
            "build_id" to (android.os.Build.DISPLAY ?: "Unknown")
        )
    }

    /**
     * 获取系统内核版本
     */
    suspend fun getKernelVersion(): String {
        val result = ShellExecutor.execute("uname -r")
        return if (result.isSuccess) result.stdout else "Unknown"
    }

    /**
     * 获取 Magisk 状态
     */
    suspend fun getMagiskStatus(): Map<String, Any> {
        val result = mutableMapOf<String, Any>()

        val magiskVersion = ShellExecutor.executeAsRoot("magisk --version")
        result["installed"] = magiskVersion.isSuccess
        result["version"] = if (magiskVersion.isSuccess) magiskVersion.stdout else "Not installed"

        return result
    }
}
