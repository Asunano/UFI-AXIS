package com.ufi_axis.installer.remoteadb

import android.content.Context
import com.ufi_axis.adbcore.AdbClient
import com.ufi_axis.adbcore.AdbConnectException
import com.ufi_axis.adbcore.AdbCrypto
import com.ufi_axis.adbcore.AdbException
import com.ufi_axis.adbcore.PortProbe
import com.ufi_axis.installer.goform.GoformClient
import com.ufi_axis.installer.goform.GoformLog
import com.ufi_axis.installer.goform.GoformWriteResult
import com.ufi_axis.installer.logging.InstallLogger
import com.ufi_axis.installer.state.InstallEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * 开启远程 ADB 引导流程编排器（单例）。
 *
 * 流程：
 *  1. 用户输入设备后台地址（默认 192.168.0.1:8080）+ 密码（默认 admin）；
 *  2. 前置弹窗：请确认有线连接（用户确认后继续）；
 *  3. goform 登录 → 下发 USB_PORT_SETTING（开启 USB/ADB 端口）；
 *  4. 检测 adb connect <ip>:5555（5s 窗口）：
 *       - 连通 → 完成；
 *       - 未通 → 弹窗询问是否重启：
 *           · 取消 → 失败（用户取消）；
 *           · 确认 → 下发 REBOOT_DEVICE → 重启后等待并重试连接；
 *               - 连通 → 完成；
 *               - 仍不通 → 重新登录 goform → 再下发 → 再检测；
 *                   · 连通 → 完成；· 仍不通 → 开启失败。
 *
 * 注意：连上 5555 之后**不再**发 `tcpip:5555`。设备既然已在 5555 监听，
 * 再发一次只会让 adbd 重启并踢掉刚建立的连接，没有收益。
 *
 * **已知风险（未修）**：整个引导跑在自建的 `CoroutineScope(SupervisorJob() + Dispatchers.IO)` 上，
 * 没有前台服务承载，也没有 WakeLock。而 REBOOT_DEVICE 之后的 [waitForAdb] 最长要等 90s，
 * 用户此时切后台或锁屏，进程可能被冻结/回收，流程会静默中断（界面回来后只看到停住的进度）。
 * 后续方案：把这个 scope 挪到前台服务上（可参考 InstallerService 的
 * `startForegroundCompat` + `acquireWakeLock`），并在等待窗口期间持一把 PARTIAL_WAKE_LOCK。
 * 本轮没有直接复用 InstallerService：它的生命周期与通知文案都绑在 InstallEngine 的状态上
 * （`observeState` 一见 `finished` 就 stopSelf），塞进第二个引擎需要改服务的退出条件与通知映射，
 * 改动面超出「低风险」范围。
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

    /** 用户点了取消：阻塞的探测循环靠它提前退出 */
    private val cancelRequested = AtomicBoolean(false)

    // 写在 IO 协程、读在主线程（confirmWire / confirmReboot / cancel / isAwaitingInteraction），
    // 普通 var 没有 happens-before，主线程可能读到 null 把用户回应丢掉，只能拖到超时才失败
    private val wireSignal = AtomicReference<CompletableDeferred<Boolean>?>()
    private val rebootSignal = AtomicReference<CompletableDeferred<Boolean>?>()

    fun attach(context: Context) {
        appContext = context.applicationContext
        // goform 的 WARN/ERROR 同步进安装日志，否则登录失败原因只在 logcat 里
        GoformLog.sink = { level, text ->
            when (level) {
                GoformLog.Level.ERROR -> InstallLogger.error("goform $text")
                else -> InstallLogger.warn("goform $text")
            }
        }
    }

    fun isRunning(): Boolean = busy.get()

    /** 当前是否在等待用户回应弹窗（界面销毁时据此决定要不要中止流程） */
    fun isAwaitingInteraction(): Boolean =
        wireSignal.get()?.isActive == true || rebootSignal.get()?.isActive == true

    fun start(goformHost: String, goformPort: Int, password: String) {
        if (InstallEngine.isRunning()) {
            // 两个引擎都会动设备，远程 ADB 还可能重启设备，绝不能和安装并发
            failFast("安装正在进行中，请等安装结束后再开启远程 ADB")
            return
        }
        if (!busy.compareAndSet(false, true)) {
            InstallLogger.warn("RemoteAdb: 已有任务在运行，忽略重复 start")
            return
        }
        val ctx = appContext ?: run {
            busy.set(false)
            InstallLogger.error("RemoteAdb: 尚未 attach 上下文")
            return
        }
        cancelRequested.set(false)
        patch { RemoteAdbState() }
        val newScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = newScope
        job = newScope.launch {
            try {
                InstallLogger.startSession(ctx)
                InstallLogger.info("RemoteAdb: 开始开启远程 ADB（后台 $goformHost:$goformPort）")
                runFlow(ctx, goformHost, goformPort, password)
            } catch (e: CancellationException) {
                markCancelled()
            } catch (e: Throwable) {
                if (cancelRequested.get()) {
                    markCancelled()
                } else {
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
                }
            } finally {
                wireSignal.set(null)
                rebootSignal.set(null)
                busy.set(false)
                InstallLogger.endSession(_state.value.success, _state.value.errorMessage.ifEmpty { null })
                // 用完即弃：不 cancel 的话每次 start 都会漏一个 scope
                newScope.cancel()
                if (scope === newScope) scope = null
            }
        }
        job?.invokeOnCompletion { busy.set(false) }
    }

    /** 还没进入协程就被拒的情况：直接给出失败态，别让界面停在进度页 */
    private fun failFast(reason: String) {
        InstallLogger.warn("RemoteAdb: $reason")
        patch {
            RemoteAdbState(
                stage = RemoteAdbStage.FAILED,
                statusText = "开启失败",
                errorMessage = reason,
                finished = true,
                success = false
            )
        }
    }

    private fun markCancelled() {
        InstallLogger.warn("RemoteAdb: 用户取消")
        patch {
            copy(
                stage = RemoteAdbStage.FAILED,
                statusText = "已取消",
                errorMessage = "用户取消了操作",
                finished = true,
                success = false
            )
        }
    }

    /**
     * 取消。阻塞的 socket 探测无法被协程取消打断，所以除了 cancel 协程，
     * 还要置 [cancelRequested] 让轮询循环在下一次迭代前退出。
     */
    fun cancel() {
        if (!busy.get()) {
            // 界面卡在进度页但引擎其实没在跑：直接收敛成失败态，别让「取消」点了没反应
            if (!_state.value.showResult) markCancelled()
            return
        }
        cancelRequested.set(true)
        patch { copy(statusText = "正在取消…") }
        wireSignal.get()?.complete(false)
        rebootSignal.get()?.complete(false)
        job?.cancel()
    }

    fun confirmWire() { wireSignal.get()?.complete(true) }
    fun confirmReboot() { rebootSignal.get()?.complete(true) }
    fun rejectReboot() { rebootSignal.get()?.complete(false) }
    fun reset() { if (busy.get()) return; patch { RemoteAdbState() } }

    private suspend fun runFlow(ctx: Context, goformHost: String, goformPort: Int, password: String) {
        val adbIp = goformHost // 同 IP，adb 走 5555
        patch { copy(adbAddress = "$adbIp:$ADB_PORT") }

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
            enableUsbPort(goform)
            InstallLogger.ok("RemoteAdb: USB_PORT_SETTING 已下发")
            logUsbPortState(goform)

            // 检测①：下发命令后设备要过几秒才真正 listen 5555，
            // 这里必须**轮询等待**而不是打一次 5s 连接就判失败——否则用户看到的就是
            // 「刚开完端口立刻被要求重启」。
            val c1 = waitForAdb(ctx, adbIp, RemoteAdbStage.WAIT_PORT, PORT_OPEN_WAIT_MS)
            if (c1 != null) { finishConnected(c1); return }

            // 等满窗口仍不通 → 弹窗2（重启确认）。重启是破坏性动作，只能由用户点确认，
            // 引擎绝不自动下发。
            setStage(RemoteAdbStage.REBOOT_CONFIRM)
            patch { copy(interaction = RemoteAdbInteraction.REBOOT_CONFIRM, progress = -1) }
            InstallLogger.warn(
                "RemoteAdb: 等待 ${PORT_OPEN_WAIT_MS / 1000}s 后 $ADB_PORT 仍未连通，等待用户决定是否重启设备"
            )
            val reboot = awaitReboot()
            patch { copy(interaction = RemoteAdbInteraction.NONE) }
            if (!reboot) {
                // 用户选择不重启：这不是「未知失败」，给出可自查的清单
                throw RemoteAdbException(
                    "已开启 USB/ADB 端口，但 $ADB_PORT 在 ${PORT_OPEN_WAIT_MS / 1000}s 内没有连通，且你选择了不重启。\n" +
                        "可自行检查：① 设备与手机是否在同一局域网 ② 设备是否已用数据线连上电脑" +
                        "（部分机型 ADB 端口依赖 USB 连接）③ 是否有其他 adb 客户端占用设备。\n" +
                        "确认后可重新开始，或在提示时选择重启设备。"
                )
            }

            // 步骤4：重启设备。REBOOT_DEVICE 非幂等，必须走不重发的 goformPost
            setStage(RemoteAdbStage.REBOOT, "正在发送重启命令…")
            if (goform.goformPost(
                    mapOf("goformId" to "REBOOT_DEVICE", "isTest" to "false")
                ) == null
            ) throw RemoteAdbException("发送重启命令失败（设备未确认执行）")
            InstallLogger.ok("RemoteAdb: REBOOT_DEVICE 已下发（用户已确认）")

            // 重启后等待并重试连接（检测②）
            val c2 = waitForAdb(ctx, adbIp, RemoteAdbStage.DETECT_AFTER_REBOOT, REBOOT_WAIT_MS)
            if (c2 != null) { finishConnected(c2); return }
        } finally {
            try { goform.close() } catch (_: Exception) { }
        }

        // 仍不通 → 回步骤2：重新登录（会话已失效）+ 再下发 + 检测③
        val goform2 = GoformClient(deviceIp = adbIp, port = goformPort, password = password)
        try {
            setStage(RemoteAdbStage.RELINK, "正在重新登录设备后台…")
            if (!goform2.ensureLogin()) throw RemoteAdbException("重启后重新登录失败（密码或地址错误）")
            setStage(RemoteAdbStage.USB_SETTING, "正在重新下发端口开启命令…")
            enableUsbPort(goform2)
            logUsbPortState(goform2)
            val c3 = waitForAdb(ctx, adbIp, RemoteAdbStage.DETECT, PORT_OPEN_WAIT_MS)
            if (c3 != null) { finishConnected(c3); return }
        } finally {
            try { goform2.close() } catch (_: Exception) { }
        }

        throw RemoteAdbException("多次重试仍未连通 $ADB_PORT 端口，开启失败")
    }

    /**
     * 下发 USB_PORT_SETTING。这是幂等的「设置成同一取值」，走 idempotent 入口，
     * 并按 [GoformWriteResult] 区分失败原因——都压成 null 的话，网络不可达会被
     * 误报成「设备拒绝命令」。
     */
    private suspend fun enableUsbPort(goform: GoformClient) {
        val result = goform.goformPostIdempotent(
            mapOf(
                "goformId" to "USB_PORT_SETTING",
                "isTest" to "false",
                "usb_port_switch" to "1"
            )
        )
        when (result) {
            is GoformWriteResult.Accepted -> Unit
            is GoformWriteResult.SessionLost ->
                throw RemoteAdbException("开启 USB/ADB 端口失败：设备后台会话失效，请确认密码是否正确")
            is GoformWriteResult.Unreachable ->
                throw RemoteAdbException("开启 USB/ADB 端口失败：设备后台不可达（${result.reason}）")
            is GoformWriteResult.Rejected ->
                throw RemoteAdbException(
                    "开启 USB/ADB 端口失败：设备拒绝了命令${result.body?.let { "（$it）" } ?: ""}"
                )
        }
    }

    /**
     * 回读 `usb_port_switch` 只为**诊断**：能区分「命令没生效」和「生效了但端口还没起」。
     * 不同固件字段名可能不同，读不到只记一行日志，绝不参与流程判定。
     */
    private suspend fun logUsbPortState(goform: GoformClient) {
        val value = try {
            goform.queryFieldText("usb_port_switch")
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        InstallLogger.info(
            "RemoteAdb: 回读 usb_port_switch=${value ?: "未返回（部分固件不支持该查询，仅供参考）"}"
        )
    }

    private fun finishConnected(client: AdbClient) {
        // 顺手尝试持久化：tcpip / USB_PORT_SETTING 打开的监听都不跨重启，
        // 只有 persist.adb.tcp.port 才会。部分固件不允许 shell 写它，失败属正常，
        // 但必须让用户知道「这次开的连接重启后还在不在」。
        val persistent = try {
            val r = client.ensurePersistentTcpPort(ADB_PORT)
            r.diagnostics.forEach { InstallLogger.info("RemoteAdb: $it") }
            if (r.persisted) {
                InstallLogger.ok("RemoteAdb: 设备重启后仍会监听 $ADB_PORT（持久属性已就位）")
            } else {
                InstallLogger.warn("RemoteAdb: 设备重启后需要重新开启一次远程 ADB")
            }
            r.persisted
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            InstallLogger.warn("RemoteAdb: 持久化检查失败：${humanize(e)}")
            false
        }
        try { client.close() } catch (_: Exception) { }
        InstallLogger.ok("RemoteAdb: $ADB_PORT 已连通且设备能执行命令，远程 ADB 可用")
        patch { copy(progress = 100, finished = true, success = true, persistent = persistent) }
        setStage(RemoteAdbStage.DONE, "远程 ADB 已开启")
    }

    /**
     * 连接是否「真的可用」：握手成功只说明 TCP + 鉴权帧走通了，
     * 再跑一条 `echo` 才能排除「未授权 / 已被其他 adb 客户端占用 / adbd 刚起没准备好」。
     */
    private fun verifyReady(client: AdbClient): Boolean = when (val r = client.checkReady()) {
        is AdbClient.DeviceReadiness.Ready -> {
            InstallLogger.ok("RemoteAdb: 设备已响应命令（echo 探活通过）")
            true
        }
        is AdbClient.DeviceReadiness.Unauthorized -> {
            InstallLogger.warn("RemoteAdb: 设备未授权调试：${r.detail}")
            false
        }
        is AdbClient.DeviceReadiness.Offline -> {
            InstallLogger.warn("RemoteAdb: 设备离线：${r.detail}")
            false
        }
        is AdbClient.DeviceReadiness.Error -> {
            InstallLogger.warn("RemoteAdb: 设备无有效响应：${r.detail}")
            false
        }
    }

    private fun tryConnect(ctx: Context, host: String, timeoutMs: Int): AdbClient? {
        if (cancelRequested.get()) return null
        return try {
            val client = AdbClient(AdbCrypto(ctx.filesDir))
            client.connect(
                host = host,
                port = ADB_PORT,
                connectTimeoutMs = timeoutMs,
                onAwaitingAuth = { InstallLogger.warn("RemoteAdb: 设备要求授权，请到设备上点「允许」") }
            )
            client
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            InstallLogger.warn("RemoteAdb: 连接 $host:$ADB_PORT 失败：${humanize(e)}")
            null
        }
    }

    /**
     * 在 [windowMs] 窗口内轮询等待 5555 真正可连接。
     *
     * 为什么不能只打一次连接：goform 下发 `USB_PORT_SETTING` 后，设备侧要几秒到十几秒
     * 才会真正 listen 5555。单次 5s 连接必然失败，于是流程会立刻跳到「要不要重启」，
     * 用户看到的就是「开完端口就被要求重启」——这正是本轮要修的行为。
     *
     * 每轮先做轻量 TCP 探测（[PROBE_TIMEOUT_MS]），只有端口可达才花代价做完整 ADB 握手；
     * 「端口可达但握手没过」（例如设备要求授权、被其他 adb 客户端占用）也继续等下一轮。
     */
    private suspend fun waitForAdb(
        ctx: Context,
        host: String,
        stage: RemoteAdbStage,
        windowMs: Long
    ): AdbClient? {
        val start = System.currentTimeMillis()
        val deadline = start + windowMs
        var unreachableRounds = 0
        setStage(stage)
        while (true) {
            if (cancelRequested.get()) return null

            val now = System.currentTimeMillis()
            val remainSec = ((deadline - now) / 1000).coerceAtLeast(0)
            val pct = (((now - start) * 100) / windowMs).toInt().coerceIn(0, 99)
            patch { copy(progress = pct, statusText = "${stage.runningText}（剩余 ${remainSec}s）") }

            val probe = PortProbe.probeAdb(host, ADB_PORT, PROBE_TIMEOUT_MS)
            if (probe.reachable) {
                InstallLogger.info("RemoteAdb: $ADB_PORT 端口已可达（${probe.elapsedMs}ms），正在建立 adb 连接…")
                val c = tryConnect(ctx, host, CONNECT_TIMEOUT_MS)
                if (c != null) {
                    // 握手过了还不算「连上」：再跑一条 echo 确认设备真的能执行命令，
                    // 否则「未授权 / 被占用」也会被当成成功，结果给用户一条连不上的命令。
                    if (verifyReady(c)) return c
                    try { c.close() } catch (_: Exception) { }
                }
                InstallLogger.warn("RemoteAdb: 端口可达但设备尚未可用，继续等待…")
            } else {
                unreachableRounds++
                // 每轮都打会刷屏，只在首轮和每 5 轮记一次
                if (unreachableRounds == 1 || unreachableRounds % 5 == 0) {
                    InstallLogger.info(
                        "RemoteAdb: $ADB_PORT 尚未打开（${probe.error ?: "不可达"}），已等待 ${(now - start) / 1000}s"
                    )
                }
            }

            if (System.currentTimeMillis() + PROBE_INTERVAL_MS >= deadline) break
            delay(PROBE_INTERVAL_MS)
        }
        patch { copy(progress = -1) }
        return null
    }

    /**
     * 等用户回应弹窗。**必须带超时**：界面被销毁后没人 complete，
     * 没有超时就会永久挂起并把 busy 永远锁住，整个功能只能杀进程恢复。
     */
    private suspend fun awaitWire(): Boolean = awaitDecision("有线连接确认") { wireSignal.set(it) }

    private suspend fun awaitReboot(): Boolean = awaitDecision("重启确认") { rebootSignal.set(it) }

    private suspend fun awaitDecision(
        what: String,
        register: (CompletableDeferred<Boolean>?) -> Unit
    ): Boolean {
        val s = CompletableDeferred<Boolean>()
        register(s)
        return try {
            withTimeout(INTERACTION_TIMEOUT_MS) { s.await() }
        } catch (_: TimeoutCancellationException) {
            InstallLogger.warn("RemoteAdb: 等待$what 超时（${INTERACTION_TIMEOUT_MS / 1000}s），已中止")
            throw RemoteAdbException("等待$what 超时，请重新开始")
        } finally {
            register(null)
        }
    }

    /** 界面销毁且正在等弹窗回应时调用：没有 UI 就没人能回应，直接中止 */
    fun abortIfAwaitingInteraction() {
        if (busy.get() && isAwaitingInteraction()) {
            InstallLogger.warn("RemoteAdb: 界面已关闭，终止等待中的引导流程")
            cancel()
        }
    }

    private fun humanize(e: Throwable): String = when (e) {
        is RemoteAdbException -> e.message ?: "开启失败"
        is AdbConnectException -> "无法连接设备 $ADB_PORT 端口：${e.message ?: ""}"
        is AdbException -> e.message ?: "ADB 错误"
        else -> "${e.javaClass.simpleName}: ${e.message ?: "未知错误"}"
    }

    class RemoteAdbException(message: String) : Exception(message)

    /** 传统 ADB over TCP 端口 */
    const val ADB_PORT = 5555

    /** 单次 adb 握手超时。比探测宽松一些：握手要收发多个报文 */
    private const val CONNECT_TIMEOUT_MS = 8_000
    private const val PROBE_TIMEOUT_MS = 2_000
    private const val PROBE_INTERVAL_MS = 2_000L

    /** 下发端口开启命令后等待 5555 就绪的秒数（弹窗文案要用，故对外可见） */
    const val PORT_OPEN_WAIT_SECONDS = 30

    /** 下发端口开启命令后等待 5555 就绪的窗口 */
    private const val PORT_OPEN_WAIT_MS = PORT_OPEN_WAIT_SECONDS * 1000L

    /** 重启后等待设备起来的窗口（比开端口慢得多） */
    private const val REBOOT_WAIT_MS = 90_000L

    /** 弹窗等待上限 */
    private const val INTERACTION_TIMEOUT_MS = 5 * 60 * 1000L
}
