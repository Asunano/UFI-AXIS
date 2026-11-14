package com.ufi_axis.adbcore

import java.io.File

/**
 * 高层 ADB 门面：把底层协议封装成安装流程需要的一个个动作。
 *
 * 所有方法线程安全前提：调用方需在单一线程（或串行协程）内顺序调用，
 * 底层写路径本身有锁，但业务上这些操作天然串行。
 */
class AdbClient(
    private val crypto: AdbCrypto
) {

    private var connection: AdbConnection? = null

    /**
     * 底层连接。暴露给同模块的测试使用，业务代码不应直接依赖。
     */
    internal val rawConnection: AdbConnection?
        get() = connection

    /** 连接状态 */
    val isConnected: Boolean get() = connection?.isConnected == true

    /** 对端 banner，形如 `device::xxxx` */
    val peerBanner: String? get() = connection?.peerBanner

    /**
     * 建立连接并完成握手。
     * @param onAwaitingAuth 需要用户到设备上点「允许」时回调（应在主线程更新 UI）
     */
    fun connect(
        host: String,
        port: Int = AdbConnection.DEFAULT_PORT,
        connectTimeoutMs: Int = 10_000,
        onAwaitingAuth: (() -> Unit)? = null
    ) {
        close()
        val conn = AdbConnection(
            host = host,
            port = port,
            crypto = crypto,
            connectTimeoutMs = connectTimeoutMs,
            onAwaitingAuth = onAwaitingAuth
        )
        conn.connect()
        connection = conn
    }

    private fun requireConnection(): AdbConnection =
        connection?.takeIf { it.isConnected }
            ?: throw AdbConnectionClosedException("尚未连接到设备")

    // ------------------------------------------------------------------
    // 设备状态
    // ------------------------------------------------------------------

    /**
     * `get-state` 语义：连接成功即 `device`。
     * 这里直接返回握手是否成功；「设备此刻是否还能执行命令」由 [checkReady] 负责。
     */
    fun getState(): String = if (isConnected) "device" else "offline"

    // ------------------------------------------------------------------
    // 网络 ADB 切换
    // ------------------------------------------------------------------

    /**
     * 让设备端 adbd 切换到 TCP 监听模式，等价于在 PC 上执行 `adb tcpip <port>`。
     *
     * 通过打开设备侧 `tcpip:<port>` 服务实现：设备会返回形如
     * `restarting in TCP mode port: 5555` 的文本后关闭通道。调用前需已建立连接。
     *
     * **什么时候该用**：只有当前连接**不是**走该端口时（典型是 USB 通道）才有意义。
     * 在已经从 `<port>` 连入的 TCP 连接上调用它是有害的——adbd 会重启监听并踢掉当前连接，
     * 却什么也没改变。本安装器只有 TCP 通道，所以它的引导流程不调用这个方法。
     *
     * **它不提供持久化**：`tcpip` 写的是 `service.adb.tcp.port`（非 persist 属性），
     * 设备重启后失效。要让重启后仍可无线连，见 [persistTcpPort]。
     */
    fun tcpip(port: Int = AdbConnection.DEFAULT_PORT): String {
        val conn = requireConnection()
        val stream = conn.openStream("tcpip:$port")
        return try {
            val out = java.io.ByteArrayOutputStream()
            val deadline = System.currentTimeMillis() + 15_000L
            while (true) {
                val remain = deadline - System.currentTimeMillis()
                if (remain <= 0) break
                if (stream.available() == 0) {
                    if (stream.isClosed) break
                    if (!stream.awaitData(remain)) break
                }
                out.write(stream.readAvailable())
                if (stream.isClosed && stream.available() == 0) break
            }
            String(out.toByteArray(), Charsets.UTF_8).trim()
        } finally {
            try { stream.close() } catch (_: Exception) { }
        }
    }

    // ------------------------------------------------------------------
    // 设备就绪检测
    // ------------------------------------------------------------------

    /**
     * 尽力让「无线调试」在设备重启后仍然生效：写 `persist.adb.tcp.port`。
     *
     * 为什么不是 [tcpip]：`tcpip` 写的是 `service.adb.tcp.port`，重启即失效；
     * goform 的 `USB_PORT_SETTING` 同样不跨重启。只有 `persist.*` 会被 property service 落盘，
     * 开机时 adbd 读它来决定是否监听 TCP——所以**写完不需要重启 adbd**（重启只会踢掉当前连接）。
     *
     * 这是**尽力而为**：adb shell 是 uid 2000，部分 ROM 的 SELinux 策略不允许它写
     * `persist.adb.*`，失败很正常。判定不看 exit code（shell v1 通道拿不到），
     * 而是把属性**回读**出来比对。
     *
     * 同时收集几项只读属性，方便在不同固件上定位差异（例如 `service.adb.tcp.port` 为空
     * 却能连上 5555，说明监听不是由该属性驱动的）。
     */
    fun ensurePersistentTcpPort(port: Int = AdbConnection.DEFAULT_PORT): PersistPortResult {
        val conn = requireConnection()
        val diagnostics = mutableListOf<String>()

        fun getProp(name: String): String? = try {
            AdbShell.exec(conn, "getprop $name", 15_000).stdout.trim().ifEmpty { null }
        } catch (e: Exception) {
            diagnostics += "读取 $name 失败：${e.message ?: e.javaClass.simpleName}"
            null
        }

        val model = getProp("ro.product.model")
        val release = getProp("ro.build.version.release")
        val runtime = getProp("service.adb.tcp.port")
        val persistBefore = getProp("persist.adb.tcp.port")

        diagnostics += "设备：${model ?: "未知"}（Android ${release ?: "未知"}）"
        diagnostics += "service.adb.tcp.port=${runtime ?: "<空>"}（运行态，重启即失效）"
        diagnostics += "persist.adb.tcp.port=${persistBefore ?: "<空>"}（持久态，开机时 adbd 读它）"

        if (persistBefore == port.toString()) {
            diagnostics += "持久属性已经是 $port，无需改写"
            return PersistPortResult(true, diagnostics)
        }

        try {
            AdbShell.exec(conn, "setprop persist.adb.tcp.port $port", 15_000)
        } catch (e: Exception) {
            diagnostics += "setprop 执行异常：${e.message ?: e.javaClass.simpleName}"
        }
        val persistAfter = getProp("persist.adb.tcp.port")
        val ok = persistAfter == port.toString()
        diagnostics += if (ok) {
            "已写入 persist.adb.tcp.port=$port（回读确认）"
        } else {
            "写入 persist.adb.tcp.port 未生效（回读=${persistAfter ?: "<空>"}），" +
                "多数固件不允许 shell 写 persist.adb.*"
        }
        return PersistPortResult(ok, diagnostics)
    }

    /**
     * [ensurePersistentTcpPort] 的结果。
     * @param persisted 回读确认持久属性已是目标端口，即重启后仍会监听
     * @param diagnostics 可直接逐行写进日志的诊断信息
     */
    data class PersistPortResult(
        val persisted: Boolean,
        val diagnostics: List<String>
    )

    /**
     * 设备就绪判定。
     *
     * 连接建立成功只说明握手过了，并不能保证设备此刻仍在线、已授权、能响应命令
     * （例如用户在「确认安装」弹窗期间撤销了授权，或设备掉线）。
     * 这里用一条无害的 `echo` 探一下设备是否真的能执行命令。
     */
    sealed interface DeviceReadiness {
        /** 在线、已授权、可响应命令 */
        object Ready : DeviceReadiness

        /** 设备已撤销调试授权，需要用户重新点允许 */
        data class Unauthorized(val detail: String) : DeviceReadiness

        /** 连接已断开 / 设备离线 */
        data class Offline(val detail: String) : DeviceReadiness

        /** 其他异常（空响应、命令失败等） */
        data class Error(val detail: String) : DeviceReadiness
    }

    /**
     * 检测设备是否就绪。未连接直接返回 [DeviceReadiness.Offline]。
     */
    fun checkReady(): DeviceReadiness {
        if (!isConnected) {
            return DeviceReadiness.Offline("连接已断开，请重新连接设备")
        }
        return try {
            val r = AdbShell.exec(requireConnection(), "echo __ufi_ready__", 15_000)
            if (r.exitCode == 0 && r.stdout.contains(READINESS_MARKER)) {
                DeviceReadiness.Ready
            } else {
                DeviceReadiness.Error("设备无有效响应：${r.combined().ifBlank { "空响应" }}")
            }
        } catch (e: AdbAuthRejectedException) {
            DeviceReadiness.Unauthorized(e.message ?: "设备未授权")
        } catch (e: AdbConnectionClosedException) {
            DeviceReadiness.Offline(e.message ?: "连接已断开")
        } catch (e: Exception) {
            DeviceReadiness.Error(e.message ?: e.javaClass.simpleName)
        }
    }

    /** 列出已安装包。onlyThirdParty=true 对应 `-3`（仅第三方应用）。 */
    fun listPackages(onlyThirdParty: Boolean = true): List<String> {
        val cmd = if (onlyThirdParty) "pm list packages -3" else "pm list packages"
        val result = AdbShell.exec(requireConnection(), cmd)
        return result.stdout.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("package:") }
            .map { it.removePrefix("package:").trim() }
            .filter { it.isNotEmpty() }
            .toList()
    }

    /** 查询包名对应的 apk 路径；未安装返回 null */
    fun pmPath(pkg: String): String? {
        val result = AdbShell.exec(requireConnection(), "pm path $pkg")
        val line = result.stdout.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith("package:") }
        return line?.removePrefix("package:")?.trim()
    }

    /** 判断包是否已安装 */
    fun isPackageInstalled(pkg: String): Boolean = pmPath(pkg) != null

    // ------------------------------------------------------------------
    // 安装
    // ------------------------------------------------------------------

    /**
     * 安装 APK。
     *
     * 安装策略（按优先级回退，确保尽量成功）：
     * 1. **主路径**：推送到 `/data/local/tmp` 再 `pm install -r -d`（与 bat 脚本一致）。
     *    实测推送阶段稳定成功，失败只发生在 pm 的 `restorecon` 步骤。
     * 2. 仅当失败原因是 restorecon / MEDIA_UNAVAILABLE 时才回退
     *    **流式安装 `pm install -S <size>`**：把 APK 经 stdin 直接喂给 pm，由 pm 自身
     *    把文件落到 staging 区并打上正确的 SELinux 上下文，从根上绕开 restorecon
     *    （依赖 shell v2 的 stdin 帧，设备不支持则跳过这一级）。
     * 3. 再回退：推送到 `/sdcard/` 再 `pm install`（部分设备 SELinux 策略不同）。
     *
     * 其它安装失败（如 `INSTALL_FAILED_OLDER_SDK`）不回退，原样抛出。
     *
     * @param onProgress 推送/流式写入进度回调
     * @param onNotice   回退决策的可读说明，供上层写进安装日志（否则回退原因会被静默丢弃）
     */
    fun installApk(
        apkFile: File,
        remoteName: String = DEFAULT_REMOTE_NAME,
        extraArgs: List<String> = listOf("-r", "-d"),
        onProgress: ((sent: Long, total: Long) -> Unit)? = null,
        onNotice: ((String) -> Unit)? = null
    ): ShellResult {
        val conn = requireConnection()
        // 远端文件名会被拼进设备侧 shell（rm -f / pm install），只靠单引号包裹挡不住
        // 文件名里的单引号和换行，这里先按白名单洗一遍
        val safeName = sanitizeRemoteName(remoteName)

        try {
            return installViaPush(conn, apkFile, "$TMP_DIR/$safeName", extraArgs, onProgress)
        } catch (e: AdbCommandException) {
            val msg = e.message ?: ""
            val restorecon = msg.contains("restorecon", ignoreCase = true) ||
                msg.contains("MEDIA_UNAVAILABLE", ignoreCase = true)
            if (!restorecon) throw e

            val notes = mutableListOf("主路径（$TMP_DIR）失败：$msg")
            onNotice?.invoke(notes.last())

            if (supportsShellV2()) {
                try {
                    onNotice?.invoke("回退到流式安装 pm install -S")
                    return installStreaming(conn, apkFile, extraArgs, onProgress)
                } catch (se: AdbCommandException) {
                    notes += "流式安装失败：${se.message}"
                    onNotice?.invoke(notes.last())
                }
            } else {
                notes += "设备未上报 shell_v2，跳过流式安装"
                onNotice?.invoke(notes.last())
            }

            try {
                onNotice?.invoke("回退到 /sdcard 推送安装")
                return installViaPush(conn, apkFile, "/sdcard/$safeName", extraArgs, onProgress)
            } catch (pe: AdbCommandException) {
                // 把三级回退的原因全部带上，否则用户只看到最后一条，无法判断卡在哪
                throw AdbCommandException(
                    pe.exitCode,
                    pe.stdout,
                    pe.stderr,
                    "${pe.message}\n回退过程：${notes.joinToString("；")}"
                )
            }
        }
    }

    /**
     * 把调用方给的远端文件名洗成设备侧安全的名字。
     *
     * 只保留 `[A-Za-z0-9._-]`，其余（含单引号、换行、路径分隔符）一律换成 `_`；
     * 洗空了就退回 [DEFAULT_REMOTE_NAME]。正常的 APK 名（如 `UFI-AXIS-Core-v1.0.apk`）
     * 全在白名单内，行为不变。
     */
    private fun sanitizeRemoteName(name: String): String =
        name.replace(REMOTE_NAME_UNSAFE_REGEX, "_").trim('_').ifBlank { DEFAULT_REMOTE_NAME }

    /** 设备是否支持 shell v2（stdin 流式安装依赖） */
    private fun supportsShellV2(): Boolean = connection?.supportsShellV2() == true

    /** 流式安装：把 APK 经 stdin 喂给 `pm install -S <size>`（流式读取，不整包进内存）。 */
    private fun installStreaming(
        conn: AdbConnection,
        apkFile: File,
        extraArgs: List<String>,
        onProgress: ((sent: Long, total: Long) -> Unit)?
    ): ShellResult {
        val size = apkFile.length()
        val args = extraArgs.joinToString(" ")
        val cmd = "pm install -S $size $args"
        val result = apkFile.inputStream().buffered().use { ins ->
            AdbShell.execWithStdin(conn, cmd, ins, size, AdbStream.INSTALL_TIMEOUT_MS, onProgress)
        }
        onProgress?.invoke(size, size)
        if (!isInstallSuccess(result)) {
            throw AdbCommandException(result.exitCode, result.stdout, result.stderr, buildInstallError(result))
        }
        return result
    }

    /** 推送式安装：把 APK 推到 remotePath 再 `pm install`。 */
    private fun installViaPush(
        conn: AdbConnection,
        apkFile: File,
        remotePath: String,
        extraArgs: List<String>,
        onProgress: ((sent: Long, total: Long) -> Unit)?
    ): ShellResult {
        // 0. 先清掉可能残留的同名文件（上一次失败可能遗留错误 SELinux 上下文的文件，
        //    导致再次 restorecon 仍失败），保证每次都是全新推送。
        try {
            AdbShell.exec(conn, "rm -f '$remotePath'", 10_000)
        } catch (_: Exception) {
        }

        // 1. 推送
        AdbSync.push(conn, apkFile, remotePath, onProgress = onProgress)

        // 2. 安装
        val args = extraArgs.joinToString(" ")
        val cmd = "pm install $args '$remotePath'"
        val result = AdbShell.exec(conn, cmd, AdbStream.INSTALL_TIMEOUT_MS)

        // 3. 清理临时文件（失败也不影响主流程）
        try {
            AdbShell.exec(conn, "rm -f '$remotePath'", 10_000)
        } catch (_: Exception) {
        }

        if (!isInstallSuccess(result)) {
            throw AdbCommandException(
                result.exitCode,
                result.stdout,
                result.stderr,
                buildInstallError(result)
            )
        }
        return result
    }

    /** 判断 pm install 是否成功：优先看 exit code，其次看 stdout 的 Success */
    private fun isInstallSuccess(result: ShellResult): Boolean {
        val out = result.stdout
        return out.contains("Success", ignoreCase = true) && !out.contains("Failure", ignoreCase = true)
    }

    /** 把 INSTALL_FAILED_* 原文提取成可读错误 */
    private fun buildInstallError(result: ShellResult): String {
        val text = result.combined()
        val marker = text.lineSequence().firstOrNull { it.contains("Failure") }
        val detail = marker?.trim() ?: text.trim().ifEmpty { "未知错误" }
        return "APK 安装失败：$detail"
    }

    // ------------------------------------------------------------------
    // 权限与启动
    // ------------------------------------------------------------------

    /** 读取设备属性；空值返回 null。失败（连接异常等）也返回 null。 */
    fun getProp(name: String): String? = try {
        AdbShell.exec(requireConnection(), "getprop $name", 15_000).stdout.trim().ifEmpty { null }
    } catch (_: Exception) {
        null
    }

    /** 设备 API level（`ro.build.version.sdk`）；读不到返回 null。 */
    fun sdkInt(): Int? = getProp("ro.build.version.sdk")?.toIntOrNull()

    /**
     * 授予权限；单个失败不抛异常，返回是否成功。
     *
     * @param appopsName appops 的操作名。多数权限与权限短名一致，但**有例外**：
     *        `PACKAGE_USAGE_STATS` 对应的 appop 是 `GET_USAGE_STATS`。传 null 表示同名。
     * @param tryPmGrant 是否先试 `pm grant`。仅 appop 控制的权限
     *        （MANAGE_EXTERNAL_STORAGE / REQUEST_INSTALL_PACKAGES / PACKAGE_USAGE_STATS 等）
     *        `pm grant` 必然失败，直接走 appops 可以少一次无效调用和一条误导性日志。
     */
    fun grantPermission(
        pkg: String,
        permission: String,
        appopsName: String? = null,
        tryPmGrant: Boolean = true
    ): Boolean {
        val conn = requireConnection()
        val full = if (permission.startsWith("android.permission.")) permission
        else "android.permission.$permission"

        if (tryPmGrant) {
            val grant = AdbShell.exec(conn, "pm grant $pkg $full", 20_000)
            if (isCommandOk(grant)) return true
        }
        // 回退 appops（部分权限如 MANAGE_EXTERNAL_STORAGE 只能走 appops）
        val op = appopsName ?: permission.removePrefix("android.permission.")
        val appops = AdbShell.exec(conn, "appops set $pkg $op allow", 20_000)
        return isCommandOk(appops)
    }

    /**
     * 判断一条「成功时静默」的命令是否成功。
     *
     * shell v1 通道拿不到 exit code（[ShellResult.exitCodeKnown] = false），
     * 此时只能看输出文本：`pm grant` / `appops set` 失败一定会打印
     * SecurityException / Error / Failure，成功则没有任何输出。
     * 不做这层区分的话，v1 回退路径下失败的授权会被当成成功。
     */
    private fun isCommandOk(r: ShellResult): Boolean {
        val text = r.combined()
        val hasError = listOf("Exception", "Error", "Failure", "not allowed", "Unknown")
            .any { text.contains(it, ignoreCase = true) }
        if (hasError) return false
        return if (r.exitCodeKnown) r.exitCode == 0 else text.isBlank()
    }

    /** 解析启动入口组件，形如 `com.pkg/.MainActivity`；失败返回 null */
    fun resolveLauncherActivity(pkg: String): String? {
        val conn = requireConnection()
        val result = AdbShell.exec(conn, "cmd package resolve-activity --brief $pkg", 20_000)
        return result.stdout.lineSequence()
            .map { it.trim() }
            .firstOrNull { LAUNCHER_COMPONENT_REGEX.matches(it) }
    }

    /** 启动应用：优先用解析到的组件，失败回退 monkey */
    fun launchApp(pkg: String): LaunchMethod {
        val conn = requireConnection()
        val component = resolveLauncherActivity(pkg)
        if (component != null) {
            val r = AdbShell.exec(conn, "am start -n $component", 30_000)
            val ok = !r.combined().contains("Error", ignoreCase = true) &&
                !r.combined().contains("Exception", ignoreCase = true)
            if (ok) return LaunchMethod.AM_START
        }
        // 回退 monkey
        val m = AdbShell.exec(
            conn,
            "monkey -p $pkg -c android.intent.category.LAUNCHER 1",
            30_000
        )
        if (m.stdout.contains("Events injected", ignoreCase = true)) return LaunchMethod.MONKEY
        // 即使判断失败也算发出了指令，交由后续健康检查定论
        return if (component != null) LaunchMethod.AM_START else LaunchMethod.MONKEY
    }

    /** 进程是否在运行（健康检查前的辅助判断） */
    fun isProcessRunning(pkg: String): Boolean {
        val result = AdbShell.exec(requireConnection(), "pidof $pkg", 15_000)
        return result.stdout.trim().isNotEmpty() && result.exitCode == 0
    }

    fun close() {
        try {
            connection?.close()
        } finally {
            connection = null
        }
    }

    enum class LaunchMethod { AM_START, MONKEY }

    companion object {
        /** 壳用户可写的临时目录 */
        const val TMP_DIR = "/data/local/tmp"

        /** 推送安装时用的默认远端文件名，也是名字被洗空后的兜底 */
        const val DEFAULT_REMOTE_NAME = "ufi_core.apk"

        /** 远端文件名白名单之外的字符：会被换成 `_`，避免破坏设备侧 shell 的引号闭合 */
        private val REMOTE_NAME_UNSAFE_REGEX = Regex("[^A-Za-z0-9._-]")

        const val TARGET_PACKAGE = "com.ufi_axis_core"
        const val HEALTH_PORT = 8088

        /** 设备就绪探测用的回显标记 */
        const val READINESS_MARKER = "__ufi_ready__"

        private val LAUNCHER_COMPONENT_REGEX = Regex("^[A-Za-z0-9_.]+/[A-Za-z0-9_.$]+$")
    }
}
