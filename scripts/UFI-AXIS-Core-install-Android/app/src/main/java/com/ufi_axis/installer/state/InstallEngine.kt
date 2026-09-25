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
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.isActive
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

    private var job: Job? = null

    /** 应用 Context，由 [attach] 注入（前台服务 onCreate 时调用） */
    private var appContext: Context? = null

    /** 当前 ADB 客户端，一次安装流程内复用 */
    private var client: AdbClient? = null

    /** 用户点击「确认安装」/「取消」的应答。与其它 signal 一致用原子引用，跨线程可见 */
    private val confirmSignal =
        java.util.concurrent.atomic.AtomicReference<CompletableDeferred<Boolean>?>()

    /** 向导页已经点过「确认安装」：引擎直接跳过 CONFIRM 等待 */
    private val preConfirmed = AtomicBoolean(false)

    /** 用户选定的 APK */
    private var selectedApk: AssetApkProvider.AssetApk? = null

    /** 正在运行中 */
    private val busy = AtomicBoolean(false)

    /**
     * 用户主动请求了取消。
     *
     * [cancel] 除了 cancel 协程还会直接关掉 ADB 连接来打断阻塞 I/O，
     * 被打断的调用会抛出 `AdbConnectionClosedException` 而不是 `CancellationException`，
     * 所以要靠这个标记把它归类成「已取消」而不是「连接断开」。
     */
    private val cancelRequested = AtomicBoolean(false)

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
        if (!scope.isActive) {
            // 服务正在销毁时收到 START：协程体不会执行，若不在这里复位 busy
            // 就会永久卡在「已有安装任务在运行」，只能杀进程恢复
            busy.set(false)
            InstallLogger.error("安装服务已停止，无法启动安装，请重新打开应用再试")
            return
        }

        cancelRequested.set(false)
        update { InstallState() }

        job = scope.launch {
            var success = false
            var failReason: String? = null
            var cancelled = false
            try {
                // 建目录 + 打开文件写入器是磁盘 I/O，必须离开主线程
                withContext(Dispatchers.IO) { InstallLogger.startSession(ctx) }
                runFlow(ctx, address)
                success = true
            } catch (e: CancellationException) {
                cancelled = true
                failReason = "用户取消"
                markCancelled()
                throw e
            } catch (e: Throwable) {
                if (cancelRequested.get()) {
                    // 取消时主动关闭了连接，被打断的阻塞 I/O 抛出的是连接异常，
                    // 这里按「已取消」收敛，避免给用户报成莫名的连接错误
                    cancelled = true
                    failReason = "用户取消"
                    markCancelled()
                } else {
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
                }
            } finally {
                cleanup(ctx)
                busy.set(false)
                InstallLogger.endSession(success, failReason)
                finishNotification(ctx, success, if (cancelled) "已取消" else failReason)
            }
        }
        // 兜底：协程因作用域取消而从未执行时，finally 不会跑，这里保证 busy 一定复位
        job?.invokeOnCompletion { busy.set(false) }
    }

    private fun markCancelled() {
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
    }

    /**
     * 用户取消。
     *
     * 仅 `job.cancel()` 是不够的：推送 / shell / HTTP 都是不可中断的阻塞调用，
     * 最坏要等到 `INSTALL_TIMEOUT_MS`（180s）才退出。这里同时关掉 ADB 连接，
     * 让阻塞的 socket 立刻抛错，取消才是「立即生效」的。
     */
    fun cancel() {
        if (!busy.get()) return
        cancelRequested.set(true)
        InstallLogger.warn("收到取消请求，正在中断与设备的连接…")
        update { it.copy(statusText = "正在取消…") }
        confirmSignal.get()?.complete(false)
        job?.cancel()
        try {
            client?.close()
        } catch (_: Exception) {
        }
    }

    /** 向导页点了「确认安装」：引擎据此跳过 CONFIRM 等待，[cleanup] / [reset] 会复位 */
    fun markPreConfirmed() {
        preConfirmed.set(true)
    }

    /** 用户在确认弹窗点「确认安装」 */
    fun confirmInstall() {
        confirmSignal.get()?.complete(true)
    }

    /** 用户在确认弹窗点「取消」 */
    fun rejectInstall() {
        confirmSignal.get()?.complete(false)
    }

    /** 用户点「重新开始」：清理上一轮状态 */
    fun reset() {
        if (busy.get()) return
        preConfirmed.set(false)
        update { InstallState(statusText = "准备就绪") }
    }

    /** 更新地址（界面上改了输入框） */
    fun updateAddressInput(address: String) {
        if (busy.get()) return
        update { it.copy(address = address) }
    }

    private fun cleanup(ctx: Context) {
        try {
            client?.close()
        } catch (_: Exception) {
        }
        client = null
        confirmSignal.set(null)
        preConfirmed.set(false)
        selectedApk = null
        authHintShown = false
        // 解出的 APK 有几十 MB，装完立刻删，不留到下一次安装才清
        try {
            AssetApkProvider.clearCache(ctx)
        } catch (_: Exception) {
        }
    }

    private fun finishNotification(ctx: Context, success: Boolean, reason: String?) {
        try {
            NotificationHelper.finish(
                ctx,
                if (success) "UFI-AXIS 安装完成" else "UFI-AXIS 安装失败",
                if (success) "核心服务已就绪" else brief(reason)
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
        update { it.copy(address = parsed.hostPort, packageName = "") }
        // 端口非法等「已被容错处理」的输入必须说出来，不能静默替用户改掉
        parsed.warning?.let { InstallLogger.warn("地址输入提示：$it") }
        InstallLogger.info("设备地址：${parsed.hostPort}（健康检查 ${parsed.healthUrl()}）")

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
        // 注册与发布的先后顺序由 awaitConfirm 内部保证，这里不要再提前 setStage(CONFIRM)
        val approved = awaitConfirm(core)
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

        // 安装前先记下第三方包清单，装完做差集才能真正定位新包
        val packagesBefore = try {
            conn.listPackages(onlyThirdParty = true).toSet()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            InstallLogger.warn("安装前列包失败，稍后将无法用差集识别包名：${humanize(e)}")
            emptySet()
        }

        var lastPercent = -1
        conn.installApk(
            apkFile = localApk,
            remoteName = core.name,
            onProgress = { sent, total ->
                val pct = if (total > 0) ((sent * 100) / total).toInt() else -1
                // 按 5% 步长节流：不能写成 pct % 5 == 0，大文件下百分比会跳过 5 的倍数
                if (pct >= 0 && (pct >= lastPercent + PROGRESS_STEP || pct == 100)) {
                    lastPercent = pct
                    update { it.copy(progress = pct, statusText = "正在推送 APK… $pct%") }
                }
            },
            onNotice = { InstallLogger.warn("安装回退：$it") }
        )
        InstallLogger.ok("APK 安装成功（pm install）")
        update { it.copy(progress = -1, statusText = "APK 安装完成") }

        // ---------- 步骤 6：识别包名 ----------
        setStage(InstallStage.RESOLVE_PKG)
        val pkg = resolvePackageName(conn, core, packagesBefore)
        update { it.copy(packageName = pkg) }
        InstallLogger.ok("包名：$pkg")

        // ---------- 步骤 7：授予权限 ----------
        setStage(InstallStage.GRANT, "正在授予权限…", 0)
        update { it.copy(totalPermissions = PermissionGranter.PERMISSIONS.size) }
        val grantResults = PermissionGranter.grantAll(conn, pkg) { index, total, perm, outcome, detail ->
            when (outcome) {
                PermissionGranter.Outcome.GRANTED -> InstallLogger.ok("[$index/$total] $perm")
                PermissionGranter.Outcome.NOT_APPLICABLE ->
                    InstallLogger.info("[$index/$total] $perm（本机不适用，跳过${detail?.let { "：$it" } ?: ""}）")
                PermissionGranter.Outcome.FAILED ->
                    InstallLogger.warn("[$index/$total] $perm（失败，已跳过${detail?.let { "：$it" } ?: ""}）")
            }
            update {
                it.copy(
                    grantedCount = index,
                    progress = index * 100 / total,
                    statusText = "正在授予权限 $index/$total"
                )
            }
        }
        val grantedOk = grantResults.count { it.outcome == PermissionGranter.Outcome.GRANTED }
        val skipped = grantResults.count { it.outcome == PermissionGranter.Outcome.NOT_APPLICABLE }
        val failed = grantResults.count { it.outcome == PermissionGranter.Outcome.FAILED }
        // 「不适用」和「失败」必须分开报，否则用户无法判断有没有真问题
        InstallLogger.info(
            "权限授予完成：成功 $grantedOk / 本机不适用 $skipped / 失败 $failed（共 ${grantResults.size}）"
        )
        if (failed > 0) {
            InstallLogger.warn(
                "失败项：${grantResults.filter { it.outcome == PermissionGranter.Outcome.FAILED }
                    .joinToString { it.permission }}"
            )
        }
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
                // 先关掉上一次的连接再建新的，顺序不能反，否则 connect 抛错时
                // client 字段还指向已经关闭的旧实例
                client?.close()
                client = null
                val c = AdbClient(AdbCrypto(ctx.filesDir))
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
                "请确认设备上 ${parsed.host} 的 ${AddressParser.DEFAULT_HEALTH_PORT} 端口可访问，或查看日志排查。"
        )
    }

    /**
     * 识别包名。优先级：
     * 1. 固定包名 `com.ufi_axis_core`（`pm path` 验证）
     * 2. 安装前后第三方包列表做差集（[packagesBefore] 是安装前的快照）
     * 3. 都失败则让用户手动输入
     */
    private suspend fun resolvePackageName(
        conn: AdbClient,
        apk: AssetApkProvider.AssetApk,
        packagesBefore: Set<String>
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

        // 2) 差集：安装后新增的第三方包
        try {
            val after = conn.listPackages(onlyThirdParty = true)
            // 包名随后会被拼进设备侧 shell（pm grant / appops / am start），
            // 这里就按白名单过滤，含空格或 `; $()` 的行一律不当候选
            val added = after.filter { it !in packagesBefore && it != fixed && isValidPackageName(it) }
            when {
                added.size == 1 -> {
                    InstallLogger.info("差集识别到新增包名：${added[0]}")
                    return added[0]
                }
                added.isEmpty() ->
                    InstallLogger.warn("差集为空（安装前后包列表无变化，可能是覆盖安装）")
                else -> {
                    val hit = added.firstOrNull { looksLikeCore(it) }
                    if (hit != null) {
                        InstallLogger.info("差集有多个新增包，按关键字选中：$hit（候选：${added.joinToString()}）")
                        return hit
                    }
                    InstallLogger.warn("差集有多个新增包且无法区分：${added.joinToString()}")
                }
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
        // 双保险：submitManualPackage 已拦过一次，这里是「进设备 shell 前」的最后一道
        if (!isValidPackageName(manual)) throw InstallException("包名「$manual」不是合法包名，已终止安装")
        InstallLogger.info("用户输入包名：$manual")
        return manual
    }

    /**
     * 包名白名单。包名会被裸拼进设备侧 shell（`pm grant` / `appops set` / `pm path` / `am start`），
     * 含空格或 `; $()` 就会被设备 shell 当成额外命令执行，所以只放行合法包名字符。
     */
    private fun isValidPackageName(pkg: String): Boolean = PACKAGE_NAME_REGEX.matches(pkg)

    private fun looksLikeCore(pkg: String): Boolean {
        val lower = pkg.lowercase()
        return lower.contains("ufi") || lower.contains("axis")
    }

    // ------------------------------------------------------------------
    // 人工交互（挂起等待界面回应）
    // ------------------------------------------------------------------

    /**
     * 等用户确认安装。
     *
     * 顺序是关键：**先注册 signal，再发布 CONFIRM 阶段**。反过来的话，界面在主线程
     * 一收到 CONFIRM 就会立刻调 [confirmInstall]（向导页已确认时是自动放行），
     * 那一刻 signal 还没赋值，complete 是空操作，这里的 await 就再也没人唤醒，
     * 安装会永久停在「等待确认安装」。
     *
     * 两层兜底：① 向导页已经点过「确认安装」（[markPreConfirmed]）就不再等；
     * ② 等待超时（界面已销毁、没人回应）按已确认继续——安装任务本来就只能由
     * 向导页的「确认安装」按钮发起，不存在「用户没同意就装」的情况。
     */
    private suspend fun awaitConfirm(core: AssetApkProvider.AssetApk): Boolean {
        update { it.copy(pendingApkName = core.name, pendingApkSize = core.sizeBytes) }
        if (preConfirmed.get()) {
            InstallLogger.info("向导页已确认安装 ${core.name}，跳过确认等待")
            return true
        }
        val signal = CompletableDeferred<Boolean>()
        confirmSignal.set(signal)
        setStage(InstallStage.CONFIRM, "等待确认安装", -1)
        InstallLogger.info("等待用户确认安装 ${core.name}")
        return try {
            withTimeoutOrNull(CONFIRM_TIMEOUT_MS) { signal.await() } ?: run {
                InstallLogger.warn("等待确认安装超时（${CONFIRM_TIMEOUT_MS / 1000}s），按已确认继续")
                true
            }
        } finally {
            confirmSignal.set(null)
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
        update { it.copy(address = parsed.hostPort) }
        parsed.warning?.let { InstallLogger.warn("地址输入提示：$it") }
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

    /** 界面提交手动输入的包名。非法包名直接拒收并要求重输，不往设备 shell 里送。 */
    fun submitManualPackage(pkg: String) {
        val trimmed = pkg.trim()
        if (!isValidPackageName(trimmed)) {
            InstallLogger.error("包名「$trimmed」不合法（只允许字母、数字、下划线和点，且不能以点开头），请重新输入")
            update { it.copy(statusText = "包名不合法，请重新输入") }
            return
        }
        manualPackageSignal.get()?.complete(trimmed)
    }

    // ------------------------------------------------------------------
    // 错误翻译
    // ------------------------------------------------------------------

    private fun humanize(e: Throwable): String = when (e) {
        is InstallException -> e.message ?: "安装失败"
        is AddressParser.InvalidAddressException -> "设备地址不合法：${e.message ?: "请检查输入"}"
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

    /** 推送进度上报的最小步长（百分比） */
    private const val PROGRESS_STEP = 5

    /** 等待「确认安装」的上限：超时按已确认继续，不让安装永久停在这一步 */
    private const val CONFIRM_TIMEOUT_MS = 60_000L

    /** 合法包名字符：首字符不能是点，其余只允许字母、数字、下划线和点 */
    private val PACKAGE_NAME_REGEX = Regex("^[A-Za-z0-9_][A-Za-z0-9_.]*$")
}
