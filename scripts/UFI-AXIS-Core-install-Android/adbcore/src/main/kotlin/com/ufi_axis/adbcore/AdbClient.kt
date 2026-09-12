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
     * `get-state` 语义：连接成功即 stadevice。
     * 这里直接返回握手是否成功，用于对齐 bat 脚本的检查点。
     */
    fun getState(): String = if (isConnected) "device" else "offline"

    // ------------------------------------------------------------------
    // 设备就绪检测
    // ------------------------------------------------------------------

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
     * 1. **流式安装 `pm install -S <size>`**：把 APK 经 stdin 直接喂给 pm，由 pm 自身
     *    把文件落到 staging 区并打上正确的 SELinux 上下文。**这能规避**把 APK 推到
     *    `/data/local/tmp` 后 pm 做 `restorecon` 失败的问题
     *    （即 `Failure [INSTALL_FAILED_MEDIA_UNAVAILABLE: Failed to restorecon]`）。
     * 2. 回退：推送到 `/sdcard/` 再 `pm install`（部分设备 SELinux 策略不同）。
     * 3. 再回退：推送到 `/data/local/tmp/` 再 `pm install`（原始行为）。
     *
     * 与 bat 脚本一致使用 `pm install -r -d`（允许覆盖安装、允许降级）。
     *
     * @param onProgress 推送/流式写入进度回调
     */
    fun installApk(
        apkFile: File,
        remoteName: String = "ufi_core.apk",
        extraArgs: List<String> = listOf("-r", "-d"),
        onProgress: ((sent: Long, total: Long) -> Unit)? = null
    ): ShellResult {
        val conn = requireConnection()

        // 1) 主路径：推送到 /data/local/tmp 再 pm install（与 bat 脚本、历史测试一致）。
        //    实测中推送阶段稳定成功，失败只发生在 pm 的 restorecon 步骤。
        try {
            return installViaPush(conn, apkFile, "$TMP_DIR/$remoteName", extraArgs, onProgress)
        } catch (e: AdbCommandException) {
            val msg = e.message ?: ""
            val restorecon = msg.contains("restorecon", ignoreCase = true) ||
                msg.contains("MEDIA_UNAVAILABLE", ignoreCase = true)
            // 其它安装失败（如 INSTALL_FAILED_OLDER_SDK）不回退，原样抛出。
            if (!restorecon) throw e

            // 2) restorecon 失败：优先用流式安装 `pm install -S <size>`，把 APK 经 stdin 交给 pm，
            //    由 pm 自己把文件落到 staging 区并打上正确的 SELinux 上下文，从而规避
            //    `Failure [INSTALL_FAILED_MEDIA_UNAVAILABLE: Failed to restorecon]`。
            //    仅当设备支持 shell v2 时流式才可行（stdin 需要 v2 帧）。
            if (supportsShellV2()) {
                try {
                    return installStreaming(conn, apkFile, extraArgs, onProgress)
                } catch (_: AdbCommandException) {
                    // 流式也失败，继续走下面的 /sdcard 回退
                }
            }
            // 3) 再回退：推到 /sdcard 再安装（部分旧 SELinux 策略设备上可行）
            return installViaPush(conn, apkFile, "/sdcard/$remoteName", extraArgs, onProgress)
        }
    }

    /** 设备是否支持 shell v2（stdin 流式安装依赖） */
    private fun supportsShellV2(): Boolean = connection?.supportsShellV2() == true

    /** 流式安装：把 APK 经 stdin 喂给 `pm install -S <size>`。 */
    private fun installStreaming(
        conn: AdbConnection,
        apkFile: File,
        extraArgs: List<String>,
        onProgress: ((sent: Long, total: Long) -> Unit)?
    ): ShellResult {
        val bytes = apkFile.readBytes()
        val size = bytes.size
        val args = extraArgs.joinToString(" ")
        val cmd = "pm install -S $size $args"
        val result = AdbShell.execWithStdin(conn, cmd, bytes, AdbStream.INSTALL_TIMEOUT_MS, onProgress)
        onProgress?.invoke(size.toLong(), size.toLong())
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

    /** 授予运行时权限；单个失败不抛异常，返回是否成功 */
    fun grantPermission(pkg: String, permission: String): Boolean {
        val conn = requireConnection()
        val full = if (permission.startsWith("android.permission.")) permission
        else "android.permission.$permission"

        // 先试 pm grant
        val grant = AdbShell.exec(conn, "pm grant $pkg $full", 20_000)
        if (grant.exitCode == 0 && !grant.stderr.contains("Exception", ignoreCase = true)) {
            return true
        }
        // 回退 appops（部分权限如 MANAGE_EXTERNAL_STORAGE 只能走 appops）
        val appops = AdbShell.exec(conn, "appops set $pkg $permission allow", 20_000)
        return appops.exitCode == 0 && !appops.stderr.contains("Exception", ignoreCase = true)
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

        const val TARGET_PACKAGE = "com.ufi_axis_core"
        const val HEALTH_PORT = 8088

        /** 设备就绪探测用的回显标记 */
        const val READINESS_MARKER = "__ufi_ready__"

        private val LAUNCHER_COMPONENT_REGEX = Regex("^[A-Za-z0-9_.]+/[A-Za-z0-9_.$]+$")
    }
}
