package com.ufi_axis.installer.remoteadb

import android.content.Context
import com.ufi_axis.adbcore.AdbClient
import com.ufi_axis.adbcore.AdbConnectException
import com.ufi_axis.adbcore.AdbCrypto
import com.ufi_axis.adbcore.AdbException
import com.ufi_axis.adbcore.PortProbe
import com.ufi_axis.installer.goform.GoformClient
import com.ufi_axis.installer.logging.InstallLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 开启远程 ADB 引导流程编排器（单例）。
 *
 * 流程（对应 v5 流程图）：
 *  1. 用户输入设备后台地址（默认 192.168.0.1:8080）+ 密码（默认 admin）；
 *  2. 前置弹窗：请确认有线连接（用户确认后继续）；
 *  3. goform 登录 → 下发 USB_PORT_SETTING（开启 USB/ADB 端口）；
 *  4. 检测 adb connect <ip>:5555（5s 窗口）：
 *       - 连通 → 发送 adb tcpip 5555 → 完成；
 *       - 未通 → 弹窗询问是否重启：
 *           · 取消 → 失败（用户取消）；
 *           · 确认 → 下发 REBOOT_DEVICE → 重启后等待并重试连接；
 *               - 连通 → adb tcpip 5555 → 完成；
 *               - 仍不通 → 重新登录 goform → 再下发 → 再检测；
 *                   · 连通 → 完成；· 仍不通 → 开启失败。
 */
object RemoteAdbEngine {

    private val _state = MutableStateFlow(RemoteAdbState())
    val state: StateFlow<RemoteAdbState> = _state.asStateFlow()

    private fun patch(block: RemoteAdbState.() -> RemoteAdbState) {
        _state.value = _state.value.block()
    }

    private fun setStage(stage: RemoteAdbStage, statusText: String = stage.runningText) {
        patch { copy(stage = stage, statusText = statusText) }
    }

    private var appContext: Context? = null
    private var scope: CoroutineScope? = null
    private var job: Job? = null
    private val busy = AtomicBoolean(false)

    private var wireSignal: CompletableDeferred<Boolean>? = null
    private var rebootSignal: CompletableDeferred<Boolean>? = null

    fun attach(context: Context) { appContext = context.applicationContext }

    fun isRunning(): Boolean = busy.get()

    fun start(goformHost: String, goformPort: Int, password: String) {
        if (!busy.compareAndSet(false, true)) {
            InstallLogger.warn("RemoteAdb: 已有任务在运行，忽略重复 start")
            return
        }
        val ctx = appContext ?: run {
            busy.set(false)
            InstallLogger.error("RemoteAdb: 尚未 attach 上下文")
            return
        }
        patch { RemoteAdbState() }
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        job = scope!!.launch {
            try {
                withContext(Dispatchers.IO) {
                    runFlow(ctx, goformHost, goformPort, password)
                }
            } catch (e: CancellationException) {
                patch {
                    copy(
                        stage = RemoteAdbStage.FAILED,
                        statusText = "已取消",
                        errorMessage = "用户取消了操作",
                        finished = true,
                        success = false
                    )
                }
                InstallLogger.warn("RemoteAdb: 用户取消")
            } catch (e: Throwable) {
                val msg = humanize(e)
                InstallLogger.error("RemoteAdb 失败：$msg")
                patch {
                    copy(
                        stage = RemoteAdbStage.FAILED,
                        statusText = "开启失败",
                        errorMessage = msg,
                        finished = true,
                        success = false
                    )
                }
            } finally {
                busy.set(false)
            }
        }
    }

    fun cancel() {
        if (!busy.get()) return
        job?.cancel()
        wireSignal?.complete(false)
        rebootSignal?.complete(false)
    }

    fun confirmWire() { wireSignal?.complete(true) }
    fun confirmReboot() { rebootSignal?.complete(true) }
    fun rejectReboot() { rebootSignal?.complete(false) }
    fun reset() { if (busy.get()) return; patch { RemoteAdbState() } }

    private suspend fun runFlow(ctx: Context, goformHost: String, goformPort: Int, password: String) {
        val adbIp = goformHost // 同 IP，adb 走 5555

        // 弹窗1（前置）：有线连接提醒
        setStage(RemoteAdbStage.WIRE_CONFIRM, "请确认有线连接")
        patch { copy(interaction = RemoteAdbInteraction.WIRE_CONFIRM) }
        val wireOk = awaitWire()
        patch { copy(interaction = RemoteAdbInteraction.NONE) }
        if (!wireOk) throw CancellationException("用户取消")

        // 步骤2 登录 + 步骤3 开启 USB/ADB 端口
        val goform = GoformClient(deviceIp = adbIp, port = goformPort, password = password)
        try {
            setStage(RemoteAdbStage.LOGIN, "正在登录设备后台…")
            if (!goform.ensureLogin()) throw RemoteAdbException("登录设备后台失败（密码或地址错误）")
            InstallLogger.ok("RemoteAdb: 登录成功")

            setStage(RemoteAdbStage.USB_SETTING, "正在下发端口开启命令…")
            if (goform.goformPost(
                    mapOf(
                        "goformId" to "USB_PORT_SETTING",
                        "isTest" to "false",
                        "usb_port_switch" to "1"
                    )
                ) == null
            ) throw RemoteAdbException("开启 USB/ADB 端口失败（设备拒绝命令）")
            InstallLogger.ok("RemoteAdb: USB_PORT_SETTING 已下发")

            patch { copy(address = "$adbIp:$goformPort", adbAddress = "$adbIp:5555") }

            // 检测①：adb connect 5s
            setStage(RemoteAdbStage.DETECT, "正在检测 5555 端口…")
            val c1 = tryConnect(ctx, adbIp, 5000)
            if (c1 != null) { finishViaTcpIp(c1); return }

            // 检测①未通 → 弹窗2（重启确认）
            patch { copy(interaction = RemoteAdbInteraction.REBOOT_CONFIRM, statusText = "连接未建立，请选择是否重启") }
            val reboot = awaitReboot()
            patch { copy(interaction = RemoteAdbInteraction.NONE) }
            if (!reboot) throw CancellationException("用户取消重启")

            // 步骤4：重启设备
            setStage(RemoteAdbStage.REBOOT, "正在发送重启命令…")
            if (goform.goformPost(
                    mapOf("goformId" to "REBOOT_DEVICE", "isTest" to "false")
                ) == null
            ) throw RemoteAdbException("发送重启命令失败（设备拒绝命令）")
            InstallLogger.ok("RemoteAdb: REBOOT_DEVICE 已下发")

            // 重启后等待并重试连接（检测②）
            setStage(RemoteAdbStage.DETECT_AFTER_REBOOT, "设备重启中，正在等待并重试连接…")
            val c2 = waitRebootThenConnect(ctx, adbIp)
            if (c2 != null) { finishViaTcpIp(c2); return }
        } finally {
            try { goform.close() } catch (_: Exception) { }
        }

        // 仍不通 → 回步骤2：重新登录（会话已失效）+ 再下发 + 检测③
        val goform2 = GoformClient(deviceIp = adbIp, port = goformPort, password = password)
        try {
            setStage(RemoteAdbStage.RELINK, "正在重新登录设备后台…")
            if (!goform2.ensureLogin()) throw RemoteAdbException("重启后重新登录失败（密码或地址错误）")
            setStage(RemoteAdbStage.USB_SETTING, "正在重新下发端口开启命令…")
            goform2.goformPost(
                mapOf(
                    "goformId" to "USB_PORT_SETTING",
                    "isTest" to "false",
                    "usb_port_switch" to "1"
                )
            )
            setStage(RemoteAdbStage.DETECT, "正在再次检测 5555 端口…")
            val c3 = tryConnect(ctx, adbIp, 5000)
            if (c3 != null) { finishViaTcpIp(c3); return }
        } finally {
            try { goform2.close() } catch (_: Exception) { }
        }

        throw RemoteAdbException("多次重试仍未连通 5555 端口，开启失败")
    }

    private suspend fun finishViaTcpIp(client: AdbClient) {
        try {
            setStage(RemoteAdbStage.DETECT, "已连接，正在发送 adb tcpip 5555…")
            val resp = client.tcpip(5555)
            InstallLogger.ok("RemoteAdb: adb tcpip 5555 已发送（${resp.ifBlank { "无返回" }}）")
        } catch (e: Throwable) {
            // 已建立连接，tcpip 失败可容忍（部分设备返回后立即关闭通道）
            InstallLogger.warn("RemoteAdb: tcpip 发送异常（已连接，可忽略）：${humanize(e)}")
        } finally {
            try { client.close() } catch (_: Exception) { }
        }
        patch { copy(progress = 100, finished = true, success = true) }
        setStage(RemoteAdbStage.DONE, "远程 ADB 已开启")
    }

    private suspend fun tryConnect(ctx: Context, host: String, timeoutMs: Int): AdbClient? {
        return try {
            val client = AdbClient(AdbCrypto(ctx.filesDir))
            client.connect(
                host = host,
                port = 5555,
                connectTimeoutMs = timeoutMs,
                onAwaitingAuth = { }
            )
            client
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            InstallLogger.warn("RemoteAdb: 连接 $host:5555 失败：${humanize(e)}")
            null
        }
    }

    /** 重启后轮询端口可达（最多 ~60s），一旦可达即尝试完整 adb 连接（5s）。 */
    private suspend fun waitRebootThenConnect(ctx: Context, host: String): AdbClient? {
        val deadline = System.currentTimeMillis() + 60_000L
        while (System.currentTimeMillis() < deadline) {
            val probe = PortProbe.probeAdb(host, 5555, 2000)
            if (probe.reachable) {
                val c = tryConnect(ctx, host, 5000)
                if (c != null) return c
            }
            delay(2000)
        }
        return tryConnect(ctx, host, 5000)
    }

    private suspend fun awaitWire(): Boolean {
        val s = CompletableDeferred<Boolean>()
        wireSignal = s
        return try { s.await() } finally { wireSignal = null }
    }

    private suspend fun awaitReboot(): Boolean {
        val s = CompletableDeferred<Boolean>()
        rebootSignal = s
        return try { s.await() } finally { rebootSignal = null }
    }

    private fun humanize(e: Throwable): String = when (e) {
        is RemoteAdbException -> e.message ?: "开启失败"
        is AdbConnectException -> "无法连接设备 5555 端口：${e.message ?: ""}"
        is AdbException -> e.message ?: "ADB 错误"
        else -> "${e.javaClass.simpleName}: ${e.message ?: "未知错误"}"
    }

    class RemoteAdbException(message: String) : Exception(message)
}
