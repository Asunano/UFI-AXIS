package com.ufi_axis.installer.state

import android.content.Context
import com.ufi_axis.adbcore.AdbClient
import com.ufi_axis.adbcore.AdbCommandException
import com.ufi_axis.adbcore.AdbConnectionClosedException
import com.ufi_axis.adbcore.AdbConnectException
import com.ufi_axis.adbcore.AdbAuthRejectedException
import com.ufi_axis.adbcore.AdbTlsRequiredException
import com.ufi_axis.adbcore.AdbCrypto
import com.ufi_axis.adbcore.PortProbe
import com.ufi_axis.adbcore.AdbException
import com.ufi_axis.adbcore.AdbStream
import com.ufi_axis.adbcore.AdbTimeoutException
import com.ufi_axis.installer.core.AddressParser
import com.ufi_axis.installer.core.AppLauncher
import com.ufi_axis.installer.core.AssetApkProvider
import com.ufi_axis.installer.core.HealthChecker
import com.ufi_axis.installer.core.NotificationHelper
import com.ufi_axis.installer.core.PermissionGranter
import com.ufi_axis.installer.logging.InstallLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 安装引擎：整个 App 的大脑。
 *
 * 设计要点：
 * - 单例 + 前台服务持有，保证旋转屏幕 / 退到后台后流程不断
 * - 所有 ADB 操作都在 [Dispatchers.IO] 上串行执行
 * - 状态通过 [StateFlow] 单向流出，界面只订阅不修改
 * - 需要用户输入（重试 / 确认 / 改地址）时，用 [CompletableDeferred] 挂起等待
 */
object InstallEngine {

    // ------------------------------------------------------------------
    // 对外状态
    // ------------------------------------------------------------------

    private val _state = MutableStateFlow(InstallState())
    val state: StateFlow<InstallState> = _state.asStateFlow()

    private fun update(block: (InstallState) -> InstallState) {
        _state.value = block(_state.value)
    }

    private fun setStage(
        stage: InstallStage,
        statusText: String = stage.runningText,
        progress: Int = -1
    ) {
        update { it.copy(stage = stage, statusText = statusText, progress = progress) }
    }

    // ------------------------------------------------------------------
    // 内部运行时
    // ------------------------------------------------------------------

    private var scope: CoroutineScope? = null
    private var job: Job? = null

    /** 应用 Context，由 [attach] 注入（前台服务 onCreate 时调用） */
    private var appContext: Context? = null

    /** 当前 ADB 客户端，一次安装流程内复用 */
    private var client: AdbClient? = null

    /** 用户点击「重试」/「取消」的应答 */
    private var confirmSignal: CompletableDeferred<Boolean>? = null

    /** 用户选择的地址（确认弹窗里可能改过） */
    private var resolvedAddress: AddressParser.Parsed? = null

    /** 用户选定的 APK */
    private var selectedApk: AssetApkProvider.AssetApk? = null

    /** 正在运行中 */
    private val busy = AtomicBoolean(false)

    /** 连接阶段是否已经给过「请到设备上允许」的提示，避免刷屏 */
    private var authHintShown = false

    // ------------------------------------------------------------------
    // 生命周期
    // ------------------------------------------------------------------

    /** 绑定应用 Context。重复调用无副作用。 */
    fun attach(context: Context) {
        appContext = context.applicationContext
    }

    /** 当前是否正在安装 */
    fun isRunning(): Boolean = busy.get()

    /**
     * 启动一次安装流程。
     *
     * @param address 用户输入的设备地址（可空，空则用默认 192.168.0.1）
     * @param scope   承载协程的作用域，一般传入前台服务的 scope
     */
    fun start(address: String?, scope: CoroutineScope) {
        if (!busy.compareAndSet(false, true)) {
            InstallLogger.warn("已有安装任务在运行，忽略本次请求")
            return
        }
        val ctx = appContext
        if (ctx == null) {
            busy.set(false)
            InstallLogger.error("InstallEngine 尚未 attach(Context)")
            return
        }
        this.scope = scope

        InstallLogger.startSession(ctx)
        update { InstallState() }

        job = scope.launch {
            var success = false
            var failReason: String? = null
            try {
                runFlow(ctx, address)
                success = true
            } catch (e: CancellationException) {
                failReason = "用户取消"
                InstallLogger.warn("安装已被取消")
                update { s ->
                    s.copy(
                        stage = InstallStage.FAILED,
                        statusText = "已取消",
                        errorMessage = "用户取消了安装",
                        finished = true,
                        success = false
                    )
                }
            } catch (e: Throwable) {
                failReason = humanize(e)
                InstallLogger.error(failReason)
                update { s ->
                    s.copy(
                        stage = InstallStage.FAILED,
                        statusText = "安装失败",
                        errorMessage = failReason!!,
                        finished = true,
                        success = false
                    )
                }
            } finally {
                cleanup()
                busy.set(false)
                InstallLogger.endSession(success, failReason)
                finishNotification(ctx, success, failReason)
            }
        }
    }

    /** 用户取消 */
    fun cancel() {
        if (!busy.get()) return
        job?.cancel()
        confirmSignal?.complete(false)
    }

    /** 用户在确认弹窗点「确认安装」 */
    fun confirmInstall() {
        confirmSignal?.complete(true)
    }

    /** 用户在确认弹窗点「取消」 */
    fun rejectInstall() {
        confirmSignal?.complete(false)
    }

    /** 用户点「重新开始」：清理上一轮状态 */
    fun reset() {
        if (busy.get()) return
        update { InstallState(statusText = "准备就绪") }
    }

    /** 更新地址（界面上改了输入框） */
    fun updateAddressInput(address: String) {
        if (busy.get()) return
        update { it.copy(address = address) }
    }

    private fun cleanup() {
        try {
            client?.close()
        } catch (_: Exception) {
        }
        client = null
        confirmSignal = null
        resolvedAddress = null
        selectedApk = null
        authHintShown = false
    }

    private fun finishNotification(ctx: Context, success: Boolean, reason: String?) {
        try {
            NotificationHelper.finish(
                ctx,
                if (success) "UFI-AXIS 安装完成" else "UFI-AXIS 安装失败",
                if (success) "核心服务已就绪" else (reason ?: "未知错误")
            )
        } catch (_: Exception) {
        }
    }

    // ------------------------------------------------------------------
    // 主流程（严格对齐 bat 脚本）
    // ------------------------------------------------------------------

    private suspend fun runFlow(ctx: Context, addressInput: String?) = withContext(Dispatchers.IO) {

        // ---------- 步骤 1：检查本地环境 ----------
        setStage(InstallStage.CHECK_ENV)
        InstallLogger.info("检查内置 APK…")
        AssetApkProvider.clearCache(ctx)

        val apks = AssetApkProvider.listApks(ctx)
        if (apks.isEmpty()) {
            throw InstallException(
                "assets/${AssetApkProvider.ASSET_DIR}/ 下没有找到任何 APK。" +
                    "请确认 release 工作流已把 core APK 打进安装器。"
            )
        }
        apks.forEach { InstallLogger.info("发现 APK：${it.name}（${formatSize(it.sizeBytes)}）") }

        val core = AssetApkProvider.pickCore(apks)
            ?: throw InstallException("无法从多个 APK 中确定 core：${apks.joinToString { it.name }}")
        selectedApk = core
        InstallLogger.ok("选定：${core.name}")

        // ---------- 步骤 2：解析地址 ----------
        setStage(InstallStage.PARSE_ADDR)
        val parsed = AddressParser.parse(addressInput)
        resolvedAddress = parsed
        update { it.copy(address = parsed.hostPort, packageName = "") }
        InstallLogger.info("设备地址：${parsed.hostPort}（健康检查 http://${parsed.host}:${AddressParser.DEFAULT_HEALTH_PORT}/health）")

        // ---------- 步骤 2.5：连接前探测（端口可达性） ----------
        setStage(InstallStage.PREFLIGHT)
        InstallLogger.info("连接前探测端口 ${parsed.host}:${parsed.port} …")
        val adbProbe = PortProbe.probeAdb(parsed.host, parsed.port)
        if (!adbProbe.reachable) {
            throw InstallException(
                "无法连接设备端口 ${parsed.hostPort}（${adbProbe.error ?: "端口不可达"}）。\n" +
                    "请确认：① 设备已开启 ADB over TCP / 无线调试 ② 手机与设备在同一局域网 ③ IP 与端口正确。"
            )
        }
        InstallLogger.ok("ADB 端口 ${parsed.port} 可达（${adbProbe.elapsedMs}ms）")
        // 健康检查端口（8088）只有 core 装好后才起来，这里只做提示性探测
        val healthProbe = PortProbe.probeHealth(parsed.host)
        if (healthProbe.reachable) {
            InstallLogger.info("健康检查端口 ${AdbClient.HEALTH_PORT} 已开放（设备可能已装过 core）")
        } else {
            InstallLogger.info("健康检查端口 ${AdbClient.HEALTH_PORT} 当前未开放（安装 core 后会起来，属正常）")
        }

        // ---------- 步骤 3：连接设备（最多 5 次） ----------
        connectWithRetry(ctx, parsed)

        // ---------- 步骤 4：确认安装 ----------
        setStage(InstallStage.CONFIRM, "等待确认安装", -1)
        update {
            it.copy(
                pendingApkName = core.name,
                pendingApkSize = core.sizeBytes
            )
        }
        InstallLogger.info("等待用户确认安装 ${core.name}")

        val approved = awaitConfirm()
        if (!approved) {
            throw InstallException("用户取消了安装")
        }
        InstallLogger.ok("用户已确认，开始安装")

        // ---------- 步骤 4.5：设备就绪检测 ----------
        // 用户在「确认安装」弹窗停留期间，设备可能掉线或撤销了授权，
        // 安装前先确认设备仍然在线且可响应命令，避免推了一半才失败。
        setStage(InstallStage.READY)
        val readyConn = client ?: throw InstallException("连接意外断开")
        when (val r = readyConn.checkReady()) {
            is AdbClient.DeviceReadiness.Ready ->
                InstallLogger.ok("设备就绪，可继续安装")
            is AdbClient.DeviceReadiness.Unauthorized ->
                throw InstallException("设备已撤销调试授权：${r.detail}\n请到设备上重新点「允许 USB 调试」后重试。")
            is AdbClient.DeviceReadiness.Offline ->
                throw InstallException("设备已离线：${r.detail}\n连接已断开，请检查设备后重新连接。")
            is AdbClient.DeviceReadiness.Error ->
                throw InstallException("设备就绪检测未通过：${r.detail}")
        }

        // ---------- 步骤 5：推送 + 安装 ----------
        setStage(InstallStage.INSTALL, "正在推送 APK…", 0)
        val localApk = AssetApkProvider.materializeToCache(ctx, core)
        InstallLogger.info("已解出 APK 到 ${localApk.absolutePath}（${formatSize(localApk.length())}）")

        val conn = client ?: throw InstallException("连接意外断开")
        var lastPercent = -1
        conn.installApk(
            apkFile = localApk,
            remoteName = core.name,
            onProgress = { sent, total ->
                val pct = if (total > 0) ((sent * 100) / total).toInt() else -1
                if (pct != lastPercent && pct % 5 == 0) {
                    lastPercent = pct
                    update { it.copy(progress = pct, statusText = "正在推送 APK… $pct%") }
                    // 推送很大，日志只记整十百分比，避免刷屏
                }
            }
        )
        InstallLogger.ok("APK 安装成功（pm install）")
        update { it.copy(progress = -1, statusText = "APK 安装完成") }

        // ---------- 步骤 6：识别包名 ----------
        setStage(InstallStage.RESOLVE_PKG)
        val pkg = resolvePackageName(conn, core)
        update { it.copy(packageName = pkg) }
        InstallLogger.ok("包名：$pkg")

        // ---------- 步骤 7：授予权限 ----------
        setStage(InstallStage.GRANT, "正在授予权限…", 0)
        update { it.copy(totalPermissions = PermissionGranter.PERMISSIONS.size) }
        val grantResults = PermissionGranter.grantAll(conn, pkg) { index, total, perm, ok ->
            if (ok) InstallLogger.ok("[$index/$total] $perm")
            else InstallLogger.warn("[$index/$total] $perm（失败，已跳过）")
            update {
                it.copy(
                    grantedCount = index,
                    progress = index * 100 / total,
                    statusText = "正在授予权限 $index/$total"
                )
            }
        }
        val grantedOk = grantResults.count { it.granted }
        InstallLogger.info("权限授予完成：$grantedOk/${grantResults.size} 成功")
        update { it.copy(progress = -1) }

        // ---------- 步骤 8：启动应用 ----------
        setStage(InstallStage.LAUNCH)
        AppLauncher.launch(conn, pkg)
        InstallLogger.info("等待 5 秒让服务完成初始化…")
        delay(LAUNCH_SETTLE_MS)

        // ---------- 步骤 9：健康检查（最多 6 次 × 5 秒） ----------
        setStage(InstallStage.HEALTH, "正在检查服务状态…", 0)
        update { it.copy(healthMaxAttempts = HealthChecker.MAX_ATTEMPTS) }
        healthCheckWithRetry(parsed, conn, pkg)

        // ---------- 完成 ----------
        setStage(InstallStage.DONE, "安装完成，服务运行正常")
        update { it.copy(progress = 100, finished = true, success = true) }
    }

    // ------------------------------------------------------------------
    // 步骤实现
    // ------------------------------------------------------------------

    /**
     * 连接设备，失败最多重试 5 次。
     * 每次重试前都会重新问一遍用户「重试 / 改地址」。
     */
    private suspend fun connectWithRetry(ctx: Context, initial: AddressParser.Parsed) {
        var current = initial
        var attempt = 0

        while (true) {
            attempt++
            setStage(InstallStage.CONNECT, "正在连接 ${current.hostPort}…（第 $attempt 次）")
            InstallLogger.info("连接设备 ${current.hostPort}…")

            try {
                val c = AdbClient(AdbCrypto(ctx.filesDir))
                client?.close()
                c.connect(
                    host = current.host,
                    port = current.port,
                    onAwaitingAuth = {
                        if (!authHintShown) {
                            authHintShown = true
                            InstallLogger.warn("请在设备上点击「允许 USB 调试」以继续")
                            update { it.copy(statusText = "请在设备上点击「允许」授权") }
                        }
                    }
                )
                client = c
                InstallLogger.ok("已连接：${c.peerBanner ?: current.hostPort}")
                update { it.copy(address = current.hostPort) }
                return
            } catch (e: AdbTlsRequiredException) {
                // 设备要求「无线调试」配对：重试 5555 直连没有意义，直接给出明确引导
                InstallLogger.error("设备要求 TLS 配对（无线调试模式）：${e.message}")
                throw InstallException(
                    "设备要求「无线调试」配对（TLS），本工具仅支持传统 5555 直连。\n" +
                        "请到设备的「开发者选项 → 无线调试」中完成配对（或执行 adb tcpip 5555 改用传统调试端口），" +
                        "然后用配对后显示的 IP:端口重新连接。"
                )
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                val reason = humanize(e)
                InstallLogger.error("连接失败：$reason")

                if (attempt >= MAX_CONNECT_ATTEMPTS) {
                    throw InstallException("连接失败次数已达上限（$MAX_CONNECT_ATTEMPTS 次）：$reason")
                }

                update { it.copy(statusText = "连接失败，请选择下一步操作") }
                InstallLogger.warn("请在界面上选择「重试」或「修改地址」")
                update { it.copy(interaction = InstallInteraction.CONNECT_DECISION) }
                val action = awaitConnectAction()
                update { it.copy(interaction = InstallInteraction.NONE) }
                when (action) {
                    ConnectAction.RETRY -> Unit
                    ConnectAction.CHANGE_ADDRESS -> {
                        attempt = 0
                        current = awaitAddressChange()
                    }
                    ConnectAction.CANCEL -> throw InstallException("用户取消了连接")
                }
            }
        }
    }

    private suspend fun healthCheckWithRetry(
        parsed: AddressParser.Parsed,
        conn: AdbClient,
        pkg: String
    ) {
        for (i in 1..HealthChecker.MAX_ATTEMPTS) {
            setStage(
                InstallStage.HEALTH,
                "正在检查服务状态（$i/${HealthChecker.MAX_ATTEMPTS}）…",
                (i - 1) * 100 / HealthChecker.MAX_ATTEMPTS
            )
            update { it.copy(healthAttempt = i) }

            val r = HealthChecker.checkOnce(parsed.host)
            if (r.ok) {
                InstallLogger.ok("健康检查通过（第 $i 次）")
                return
            }
            InstallLogger.warn("健康检查未通过（第 $i 次）：${r.detail}")

            // 失败时顺手看一眼进程，给用户更有用的诊断信息
            try {
                val running = conn.isProcessRunning(pkg)
                if (!running) InstallLogger.warn("目标进程 $pkg 当前未运行")
            } catch (_: Exception) {
            }

            if (i < HealthChecker.MAX_ATTEMPTS) {
                delay(HealthChecker.RETRY_DELAY_MS)
            }
        }
        throw InstallException(
            "健康检查失败：已重试 ${HealthChecker.MAX_ATTEMPTS} 次仍未就绪。\n" +
                "请确认设备上 ${parsed.host} 的 8088 端口可访问，或查看日志排查。"
        )
    }

    /**
     * 识别包名。优先级：
     * 1. 固定包名 `com.ufi_axis_core`（`pm path` 验证）
     * 2. 安装前后包列表做差
     * 3. 都失败则让用户手动输入
     */
    private suspend fun resolvePackageName(
        conn: AdbClient,
        apk: AssetApkProvider.AssetApk
    ): String {
        // 1) 固定包名
        val fixed = AssetApkProvider.CORE_PACKAGE
        try {
            if (conn.isPackageInstalled(fixed)) {
                InstallLogger.info("命中固定包名：$fixed")
                return fixed
            }
            InstallLogger.warn("固定包名 $fixed 未安装，改用差集方式识别")
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            InstallLogger.warn("验证固定包名失败：${humanize(e)}")
        }

        // 2) 差集
        try {
            val candidates = conn.listPackages(onlyThirdParty = true)
                .filter { it != fixed }
            // 差集只能给出「可能的候选」，无法精确定位，这里取最近安装的一个
            val newest = candidates.lastOrNull()
            if (newest != null && looksLikeCore(newest)) {
                InstallLogger.info("差集识别到候选包名：$newest")
                return newest
            }
            InstallLogger.warn("差集未找到匹配 ${apk.name} 的包名")
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            InstallLogger.warn("列包失败：${humanize(e)}")
        }

        // 3) 手动输入
        InstallLogger.warn("请在界面上手动输入包名")
        update { it.copy(statusText = "请手动输入包名") }
        update { it.copy(interaction = InstallInteraction.MANUAL_PACKAGE) }
        val manual = awaitManualPackage()
        update { it.copy(interaction = InstallInteraction.NONE) }
        if (manual.isBlank()) throw InstallException("未能确定包名")
        InstallLogger.info("用户输入包名：$manual")
        return manual
    }

    private fun looksLikeCore(pkg: String): Boolean {
        val lower = pkg.lowercase()
        return lower.contains("ufi") || lower.contains("axis")
    }

    // ------------------------------------------------------------------
    // 人工交互（挂起等待界面回应）
    // ------------------------------------------------------------------

    private suspend fun awaitConfirm(): Boolean {
        val signal = CompletableDeferred<Boolean>()
        confirmSignal = signal
        return try {
            signal.await()
        } finally {
            confirmSignal = null
        }
    }

    private val connectActionSignal = java.util.concurrent.atomic.AtomicReference<CompletableDeferred<ConnectAction>?>()
    private val addressChangeSignal = java.util.concurrent.atomic.AtomicReference<CompletableDeferred<String>?>()
    private val manualPackageSignal = java.util.concurrent.atomic.AtomicReference<CompletableDeferred<String>?>()

    private suspend fun awaitConnectAction(): ConnectAction {
        val signal = CompletableDeferred<ConnectAction>()
        connectActionSignal.set(signal)
        return try {
            signal.await()
        } finally {
            connectActionSignal.set(null)
        }
    }

    private suspend fun awaitAddressChange(): AddressParser.Parsed {
        val signal = CompletableDeferred<String>()
        addressChangeSignal.set(signal)
        update { it.copy(interaction = InstallInteraction.ADDRESS_CHANGE) }
        val input = try {
            signal.await()
        } finally {
            addressChangeSignal.set(null)
            update { it.copy(interaction = InstallInteraction.NONE) }
        }
        val parsed = AddressParser.parse(input)
        resolvedAddress = parsed
        update { it.copy(address = parsed.hostPort) }
        InstallLogger.info("地址已改为：${parsed.hostPort}")
        return parsed
    }

    private suspend fun awaitManualPackage(): String {
        val signal = CompletableDeferred<String>()
        manualPackageSignal.set(signal)
        return try {
            signal.await()
        } finally {
            manualPackageSignal.set(null)
        }
    }

    // ---- 界面回调入口 ----

    fun submitConnectAction(action: ConnectAction) {
        connectActionSignal.get()?.complete(action)
    }

    fun submitAddressChange(input: String) {
        addressChangeSignal.get()?.complete(input)
    }

    fun submitManualPackage(pkg: String) {
        manualPackageSignal.get()?.complete(pkg.trim())
    }

    /** 界面当前是否需要用户回应连接失败 */
    fun needsConnectDecision(): Boolean = _state.value.stage == InstallStage.CONNECT && connectActionSignal.get() != null

    // ------------------------------------------------------------------
    // 错误翻译
    // ------------------------------------------------------------------

    private fun humanize(e: Throwable): String = when (e) {
        is InstallException -> e.message ?: "安装失败"
        is AdbConnectException ->
            "无法连接到设备（${e.message ?: "网络不可达"}）。\n" +
                "请检查：① 设备无线调试已开启 ② 手机与设备在同一局域网 ③ 设备已允许本机调试"
        is AdbAuthRejectedException ->
            "设备拒绝了调试授权。请到设备上确认「允许调试」弹窗，或删除设备端已保存的授权记录后重试。"
        is AdbTimeoutException -> "操作超时：${e.message ?: "设备无响应"}"
        is AdbConnectionClosedException -> "连接已断开：${e.message ?: "设备关闭了连接"}"
        is AdbCommandException ->
            "设备命令执行失败（exit=${e.exitCode}）：${e.message ?: ""}"
        is AdbTlsRequiredException ->
            "设备要求「无线调试」配对（TLS），本工具仅支持传统 5555 直连。\n" +
                "请在设备「开发者选项 → 无线调试」中完成配对（或执行 adb tcpip 5555），" +
                "再用配对后显示的 IP:端口重新连接。"
        is AdbException -> e.message ?: "ADB 协议错误"
        is java.net.SocketTimeoutException -> "网络超时，设备无响应"
        is java.net.ConnectException -> "无法建立 TCP 连接，端口 5555 未监听"
        is java.net.UnknownHostException -> "无法解析设备地址"
        else -> "${e.javaClass.simpleName}: ${e.message ?: "未知错误"}"
    }

    private fun formatSize(bytes: Long): String = when {
        bytes < 0 -> "未知大小"
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> String.format(java.util.Locale.US, "%.1f KB", bytes / 1024.0)
        else -> String.format(java.util.Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0)
    }

    /** 最近一次失败的第一行原因，供通知使用 */
    private fun brief(reason: String?): String =
        reason?.lineSequence()?.firstOrNull()?.take(60) ?: "未知错误"

    enum class ConnectAction { RETRY, CHANGE_ADDRESS, CANCEL }

    /** 业务异常：不需要翻译，直接展示 */
    class InstallException(message: String) : Exception(message)

    // ------------------------------------------------------------------
    // 常量
    // ------------------------------------------------------------------

    /** 连接失败的最大重试次数，对应 bat 的 5 次 */
    const val MAX_CONNECT_ATTEMPTS = 5

    /** 启动后等待服务初始化的时间，对应 bat 的 `timeout /t 5` */
    const val LAUNCH_SETTLE_MS = 5_000L

    @Suppress("unused")
    private val KEEP_REFS: Any = AdbStream.INSTALL_TIMEOUT_MS to brief(null)
}
