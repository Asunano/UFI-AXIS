package com.ufi_axis_core.controller.adb

import android.os.SystemClock
import com.ufi_axis_core.controller.goform.GoformClient
import com.ufi_axis_core.controller.goform.GoformDeviceClient
import com.ufi_axis_core.util.AppLogger
import com.ufi_axis_core.util.ShellExecutor
import kotlinx.coroutines.*

class AdbController(
    private val goformClient: GoformClient? = null,
    private val deviceClient: GoformDeviceClient? = null
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
                    deviceClient?.setUsbPortSwitch(false)  // 先关闭再开启，确保生效
                    delay(500)
                    deviceClient?.setUsbPortSwitch(true)
                    AppLogger.i(tag, "ADB enabled via Goform USB_PORT_SETTING")
                }
            }
        } catch (e: Exception) {
            AppLogger.w(tag, "Goform ADB enable failed, trying setprop: ${e.message}")
        }

        // 方案2: setprop 方式 (fallback) — batch: 3 → 1
        try {
            ShellExecutor.batchExecuteAsRoot(listOf(
                "setprop persist.service.adb.tcp.port $port",
                "setprop service.adb.tcp.port $port",
                "start adbd"
            ))
        } catch (e: Exception) {
            AppLogger.w(tag, "setprop adb failed: ${e.message}")
        }

        delay(1000)

        // 验证 adbd 是否启动成功
        val running = checkAdbdRunning()
        if (!running) {
            AppLogger.w(tag, "ADB start verification failed: adbd not running")
            return false
        }

        // 启动 adb 客户端并建立 localhost 连接 (对应 REF 的 ensureAdbAlive)
        try {
            ShellExecutor.execute("adb start-server 2>/dev/null")
            delay(1500)

            // 先清理可能已存在的僵死连接
            ensureAdbConnection()
        } catch (e: Exception) {
            AppLogger.w(tag, "adb client init failed: ${e.message}")
        }

        isEnabled = true
        isConnected = checkAdbConnected()
        lastPingMs = SystemClock.elapsedRealtime()
        startKeepAlive()
        AppLogger.i(tag, "ADB WiFi started on port $port, connected=$isConnected")
        return true
    }

    suspend fun stop(): Boolean {
        AppLogger.i(tag, "Stopping ADB WiFi")
        stopKeepAlive()
        try {
            // 先断连 adb 客户端
            try { ShellExecutor.execute("adb kill-server 2>/dev/null") } catch (_: Exception) {}
            // 再停止 adbd
            deviceClient?.let { dc ->
                try { dc.setUsbPortSwitch(false) } catch (_: Exception) {}
            }
            ShellExecutor.batchExecuteAsRoot(listOf(
                "stop adbd",
                "setprop service.adb.tcp.port ''"
            ))
            isEnabled = false; isConnected = false
            return true
        } catch (e: Exception) {
            AppLogger.e(tag, "Failed to stop ADB", e); return false
        }
    }

    suspend fun ping(): Boolean {
        // 先检查 adbd 进程状态
        val running = checkAdbdRunning()
        if (!running) {
            isConnected = false
            return false
        }

        // 检查 adb 客户端是否已连接 (不使用 grep，直接 Kotlin 检查)
        try {
            val result = ShellExecutor.execute("adb devices 2>/dev/null")
            if (result.isSuccess && result.stdout.contains("localhost:$wifiPort") &&
                result.stdout.contains("\tdevice")) {
                lastPingMs = SystemClock.elapsedRealtime()
                isConnected = true
                return true
            }
        } catch (_: Exception) {}

        isConnected = false
        return false
    }

    suspend fun getStatus(): Map<String, Any> {
        val running = ping()
        return mapOf("enabled" to isEnabled, "connected" to running, "port" to wifiPort, "last_ping_ms" to lastPingMs)
    }

    /**
     * 确保 adb 客户端与本地 adbd 连接
     * 首次连接或断连后重新建立 localhost 连接
     */
    private suspend fun ensureAdbConnection() {
        val connected = checkAdbConnected()
        if (!connected) {
            AppLogger.i(tag, "adb client not connected to localhost, connecting...")
            // 可能有僵死的 adb 进程，先杀
            killAdbClientProcess()
            delay(500)
            // adb start-server 会隐式启动 client daemon
            ShellExecutor.execute("adb start-server 2>/dev/null")
            delay(1000)
            val connectResult = ShellExecutor.execute("adb connect localhost:$wifiPort 2>/dev/null")
            AppLogger.i(tag, "adb connect result: ${connectResult.stdout.trim().take(100)}")
            delay(1000)
        }
    }

    private suspend fun checkAdbConnected(): Boolean {
        try {
            val result = ShellExecutor.execute("adb devices 2>/dev/null")
            return result.isSuccess &&
                result.stdout.contains("localhost:$wifiPort") &&
                result.stdout.contains("\tdevice")
        } catch (_: Exception) { return false }
    }

    private suspend fun checkAdbdRunning(): Boolean {
        val result = ShellExecutor.executeAsRoot("getprop init.svc.adbd")
        return result.isSuccess && result.stdout.trim() == "running"
    }

    /**
     * 杀掉僵死的 adb 客户端进程 (非 adbd)
     */
    private suspend fun killAdbClientProcess() {
        try {
            // 只杀 client 进程，不杀 adbd daemon
            ShellExecutor.executeAsRoot("killall adb 2>/dev/null || true")
        } catch (_: Exception) {}
    }

    private fun startKeepAlive() {
        stopKeepAlive()
        keepAliveJob = scope.launch {
            while (isActive) {
                delay(15_000L)
                try {
                    if (!ping() && isEnabled) {
                        AppLogger.w(tag, "ADB lost, reconnecting...")
                        // 杀掉僵死 adb 客户端进程后重建连接
                        killAdbClientProcess()
                        delay(500)
                        ShellExecutor.executeAsRoot("start adbd")
                        delay(2000)
                        // 使用 execute (非 tryExecute) 确保命令一定执行
                        ShellExecutor.execute("adb start-server 2>/dev/null")
                        delay(1000)
                        ShellExecutor.execute("adb connect localhost:$wifiPort 2>/dev/null")
                        delay(500)
                        ping()
                    } else if (isEnabled) {
                        // 定期 adb connect 保持连接，使用 execute 确保执行
                        val result = ShellExecutor.execute("adb connect localhost:$wifiPort 2>/dev/null")
                        if (!result.stdout.contains("already connected") &&
                            result.stdout.contains("connected")) {
                            AppLogger.d(tag, "ADB keep-alive: reconnected")
                        }
                    }
                } catch (e: CancellationException) {
                    throw e  // 协程取消不应被吞掉
                } catch (e: Exception) {
                    AppLogger.w(tag, "ADB keep-alive error: ${e.message}")
                }
            }
        }
    }

    private fun stopKeepAlive() { keepAliveJob?.cancel(); keepAliveJob = null }
    fun destroy() { stopKeepAlive(); scope.cancel() }
}