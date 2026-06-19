package com.ufi_axis_core.controller.adb

import com.ufi_axis_core.controller.goform.GoformClient
import com.ufi_axis_core.util.AppLogger
import com.ufi_axis_core.util.ShellQoS
import kotlinx.coroutines.*

class AdbController(
    private val goformClient: GoformClient? = null
) {
    private val tag = "AdbController"
    private var keepAliveJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Volatile var isEnabled: Boolean = false; private set
    @Volatile var isConnected: Boolean = false; private set
    @Volatile var wifiPort: Int = 5555; private set
    @Volatile var lastPingMs: Long = 0L; private set

    suspend fun start(port: Int = 5555): Boolean {
        AppLogger.i(tag, "Starting ADB WiFi on port $port")
        wifiPort = port

        // 方案1: 通过 Goform API 启用 ADB (USB_PORT_SETTING)
        try {
            goformClient?.let { gc ->
                val loginOk = gc.ensureLogin()
                if (loginOk) {
                    gc.setUsbPortSwitch(false)  // 先关闭再开启，确保生效
                    delay(500)
                    gc.setUsbPortSwitch(true)
                    AppLogger.i(tag, "ADB enabled via Goform USB_PORT_SETTING")
                }
            }
        } catch (e: Exception) {
            AppLogger.w(tag, "Goform ADB enable failed, trying setprop: ${e.message}")
        }

        // 方案2: setprop 方式 (fallback) — batch: 3 → 1
        try {
            ShellQoS.batchExecuteAsRoot(listOf(
                "setprop persist.service.adb.tcp.port $port",
                "setprop service.adb.tcp.port $port",
                "start adbd"
            ))
        } catch (e: Exception) {
            AppLogger.w(tag, "setprop adb failed: ${e.message}")
        }

        delay(1000)

        // 验证 ADB 是否启动成功
        val running = checkAdbdRunning()
        if (running) {
            isEnabled = true
            isConnected = true
            lastPingMs = System.currentTimeMillis()
            startKeepAlive()
            AppLogger.i(tag, "ADB WiFi started on port $port")
            return true
        }
        AppLogger.w(tag, "ADB start verification failed")
        return false
    }

    suspend fun stop(): Boolean {
        AppLogger.i(tag, "Stopping ADB WiFi")
        stopKeepAlive()
        try {
            goformClient?.let { gc ->
                try { gc.setUsbPortSwitch(false) } catch (_: Exception) {}
            }
            // Batch: 2 → 1
            ShellQoS.batchExecuteAsRoot(listOf("stop adbd", "setprop service.adb.tcp.port ''"))
            isEnabled = false; isConnected = false
            return true
        } catch (e: Exception) {
            AppLogger.e(tag, "Failed to stop ADB", e); return false
        }
    }

    suspend fun ping(): Boolean {
        // 先检查 adbd 进程状态
        val running = checkAdbdRunning()
        if (running) { lastPingMs = System.currentTimeMillis(); isConnected = true; return true }

        // 尝试通过 adb connect 验证
        try {
            val test = ShellQoS.executeCached("adb devices 2>/dev/null | grep 'localhost:5555'")
            if (test.isSuccess && test.stdout.contains("device")) {
                lastPingMs = System.currentTimeMillis(); isConnected = true; return true
            }
        } catch (_: Exception) {}
        isConnected = false; return false
    }

    suspend fun getStatus(): Map<String, Any> {
        val running = ping()
        return mapOf("enabled" to isEnabled, "connected" to running, "port" to wifiPort, "last_ping_ms" to lastPingMs)
    }

    private suspend fun checkAdbdRunning(): Boolean {
        val result = ShellQoS.executeAsRootCached("getprop init.svc.adbd")
        return result.isSuccess && result.stdout.trim() == "running"
    }

    private fun startKeepAlive() {
        stopKeepAlive()
        keepAliveJob = scope.launch {
            while (isActive) {
                delay(15_000L)
                try {
                    if (!ping() && isEnabled) {
                        AppLogger.w(tag, "ADB lost, reconnecting...")
                        ShellQoS.tryExecuteAsRoot("start adbd")
                        delay(2000)
                        // 尝试 adb connect (如 adb 可用)
                        ShellQoS.tryExecute("adb connect localhost 2>/dev/null")
                        delay(500)
                        ping()
                    } else if (isEnabled) {
                        // 定期 adb connect 保持连接
                        ShellQoS.tryExecute("adb connect localhost 2>/dev/null")
                    }
                } catch (_: Exception) {}
            }
        }
    }

    private fun stopKeepAlive() { keepAliveJob?.cancel(); keepAliveJob = null }
    fun destroy() { stopKeepAlive(); scope.cancel() }
}