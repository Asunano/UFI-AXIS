package com.ufi_axis_core.controller.system

import com.ufi_axis_core.controller.goform.GoformDeviceClient
import com.ufi_axis_core.util.AdbShellExecutor
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
     * 优先 Goform，失败后 fallback 到 reboot 命令（通过 ADB shell）
     */
    suspend fun reboot(): Boolean {
        AppLogger.i(tag, "Rebooting device")

        // 方式1: Goform
        if (deviceClient.rebootDevice()) {
            return true
        }

        // 方式2: Shell reboot (via ADB shell)
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
     * 获取特权 shell 状态（替代原 Magisk 检测）
     * 报告 ADB shell 可用性和当前 uid
     */
    suspend fun getPrivilegedShellStatus(): Map<String, Any> {
        val result = mutableMapOf<String, Any>()
        result["adb_shell"] = AdbShellExecutor.isAvailable
        result["method"] = if (AdbShellExecutor.isAvailable) "adb_shell" else "none"

        val idResult = ShellExecutor.executeAsRoot("id")
        result["uid"] = if (idResult.isSuccess) idResult.stdout else "unavailable"

        return result
    }

    /**
     * 兼容旧接口：返回特权 shell 状态（原 getMagiskStatus）
     */
    suspend fun getMagiskStatus(): Map<String, Any> {
        return getPrivilegedShellStatus()
    }
}
