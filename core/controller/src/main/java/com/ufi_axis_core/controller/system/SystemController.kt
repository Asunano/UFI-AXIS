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
     * 关机
     * 优先 Goform，失败后 fallback 到 `svc power shutdown`（通过 ADB shell）。
     *
     * 为什么用 `svc power shutdown` 而不是 `reboot -p`：
     * `svc power` 走的是 PowerManager（ADB shell 的 uid 2000 有权限），
     * 而 `reboot -p` 需要真正的 root，本项目已不依赖 su（见 ShellExecutor.executeAsRoot 注释）。
     *
     * 兜底命中与否都打日志：goform 侧失败会塌缩成 false（超时 / 会话失效 / 设备回登录页
     * 都是同一个 false），没有日志就分不清"设备没收到指令"和"收到了但没关"。
     */
    suspend fun shutdown(): Boolean {
        AppLogger.i(tag, "Shutting down device")

        // 方式1: Goform
        if (deviceClient.shutdownDevice()) {
            AppLogger.i(tag, "Shutdown issued via goform")
            return true
        }
        AppLogger.w(tag, "Goform shutdown failed, falling back to shell")

        // 方式2: svc power shutdown (via ADB shell)
        val result = ShellExecutor.executeAsRoot("svc power shutdown")
        if (result.isSuccess) {
            AppLogger.i(tag, "Shutdown issued via svc power")
        } else {
            AppLogger.e(tag, "Shutdown failed: exit=${result.exitCode} err=${result.stderr.take(200)}")
        }
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
