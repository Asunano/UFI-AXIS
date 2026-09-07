package com.ufi_axis_core.controller.adb

import android.content.Context
import android.os.SystemClock
import com.ufi_axis_core.util.AdbShellExecutor
import com.ufi_axis_core.util.AppLogger
import com.ufi_axis_core.util.AssetExtractor
import kotlinx.coroutines.*

/**
 * ADB 控制器 — 管理 ADB 自连接生命周期
 *
 * 职责：
 * - 初始化/重建 AdbShellExecutor 的 adb 自连接与持久 shell 会话
 * - keep-alive 周期检测连接状态，断线时重新 init
 *
 * 2026-08-22：移除 Goform USB_PORT_SETTING 自动切换（功能已废弃）。
 * 旧实现会在 adbd 未监听 TCP 时自动 off→on 切换 USB 端口——这会重启 adbd，
 * 打断用户正在进行的 adb 操作（投屏/安装），且服务每次重启都重复触发。
 * 现在是否开启 adbd TCP 监听完全由用户在设备侧控制；未监听时 AdbShellExecutor
 * 连接失败，keep-alive 周期重试，adbd 可用后自动恢复特权通道。
 */
class AdbController(
    private val context: Context? = null
) {
    private val tag = "AdbController"
    private var keepAliveJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Volatile var isEnabled: Boolean = false; private set
    @Volatile var isConnected: Boolean = false; private set
    @Volatile var wifiPort: Int = 5555; private set
    @Volatile var lastPingMs: Long = 0L; private set

    suspend fun start(port: Int = 5555): Boolean {
        AppLogger.i(tag, "Starting ADB channel on port $port")
        wifiPort = port

        // 初始化 AdbShellExecutor（建立自连接 + 持久 shell）
        val adbPath = context?.let { AssetExtractor.getPath(it, "adb") } ?: "adb"
        val ok = AdbShellExecutor.init(adbPath, context)
        isEnabled = true   // 已请求启用，keep-alive 负责在 adbd 就绪后恢复连接
        isConnected = ok
        if (ok) lastPingMs = SystemClock.elapsedRealtime()
        // 无论首次是否成功都启动 keep-alive：开机早期 adbd 可能尚未就绪，
        // 特权通道是系统关键，必须在后台持续重试直到恢复。
        startKeepAlive()
        AppLogger.i(tag, "ADB channel started on port $port, connected=$ok")
        return ok
    }

    suspend fun stop(): Boolean {
        AppLogger.i(tag, "Stopping ADB channel (soft stop)")
        stopKeepAlive()
        // 架构变更：ADB 自连接现在是系统级特权执行通道（ComponentFactory 启动时初始化，
        // 所有 executeAsRoot 依赖它）。因此 /adb/stop 只做软停止——停止 keep-alive 并更新
        // 本地状态，不再关闭 adbd 或 AdbShellExecutor，否则 pm/settings/reboot 等全部特权
        // 操作会失效。AdbShellExecutor 的生命周期由 BackendService 销毁时统一管理。
        isEnabled = false; isConnected = false
        return true
    }

    suspend fun ping(): Boolean {
        val available = AdbShellExecutor.isAvailable
        if (available) {
            lastPingMs = SystemClock.elapsedRealtime()
            isConnected = true
        } else {
            isConnected = false
        }
        return available
    }

    suspend fun getStatus(): Map<String, Any> {
        val running = ping()
        return mapOf(
            "enabled" to isEnabled,
            "connected" to running,
            "port" to wifiPort,
            "last_ping_ms" to lastPingMs,
            "method" to "adb_self_connection"
        )
    }

    private fun startKeepAlive() {
        stopKeepAlive()
        keepAliveJob = scope.launch {
            while (isActive) {
                // 2026-08-20：保活间隔从 15s 放宽到 60s —— 减少 adbd 负载，
                // 配合 AdbShellExecutor 的 30s 空闲超时，避免频繁创建/销毁 shell 冲击 adbd
                delay(60_000L)
                try {
                    if (isEnabled && !AdbShellExecutor.isAvailable) {
                        AppLogger.w(tag, "ADB shell lost, reconnecting...")
                        // 仅重建 shell 连接，不触碰 adbd 的监听状态（用户在设备侧自行控制）
                        val adbPath = context?.let { AssetExtractor.getPath(it, "adb") } ?: "adb"
                        AdbShellExecutor.init(adbPath, context)
                        isConnected = AdbShellExecutor.isAvailable
                        if (isConnected) lastPingMs = SystemClock.elapsedRealtime()
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AppLogger.w(tag, "ADB keep-alive error: ${e.message}")
                }
            }
        }
    }

    private fun stopKeepAlive() { keepAliveJob?.cancel(); keepAliveJob = null }
    fun destroy() { stopKeepAlive(); scope.cancel() }
}
