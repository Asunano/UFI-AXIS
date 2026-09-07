package com.ufi_axis_core.util

import android.content.Context
import android.net.LocalSocket
import android.net.LocalSocketAddress
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import jcifs.context.SingletonContext
import jcifs.smb.SmbFile

/**
 * Samba root shell — 利用 ZTE F50 Samba root preexec 机制获取 root shell。
 *
 * 原理：
 * 1. BackendService 启动时，将 socat 二进制和 samba_exec.sh 部署到 /data/local/tmp/ufi_axis/
 * 2. 修改 /data/samba/etc/smb.conf 添加 root preexec 指向我们的脚本
 * 3. Samba 客户端连接时，smbd 以 root 身份执行脚本 → 启动 socat Unix socket 监听器
 * 4. App 通过 LocalSocket 连接 socket → 获得 root shell → 执行 pm install -r -g
 *
 * 部署路径：
 * - socat:       /data/local/tmp/ufi_axis/socat
 * - 脚本:        /data/local/tmp/ufi_axis/samba_exec.sh
 * - socket:      /data/local/tmp/ufi_axis/root.sock
 * - smb.conf:    /data/samba/etc/smb.conf（追加 root preexec）
 *
 * 参考：UFI-TOOLS (com.minikano.f50_sms) 的 RootShell + samba_exec.sh 实现。
 */
object SambaRootShell {

    private const val TAG = "SambaRootShell"

    /** 共享目录（ADB shell 可写，root 可读，App 可读） */
    const val BASE_DIR = "/data/local/tmp/ufi_axis"

    /** socat 二进制路径 */
    const val SOCAT_PATH = "$BASE_DIR/socat"

    /** root shell socket 路径 */
    const val SOCKET_PATH = "$BASE_DIR/root.sock"

    /** UFI-TOOLS 的 root shell socket 路径（同一 Samba 机制，可复用） */
    private const val UFI_TOOLS_SOCKET_PATH = "/data/data/com.minikano.f50_sms/files/kano_root_shell.sock"

    /** 部署脚本路径 */
    const val SCRIPT_PATH = "$BASE_DIR/samba_exec.sh"

    /** Samba 配置文件路径 */
    private const val SMB_CONF = "/data/samba/etc/smb.conf"

    /**
     * 部署 Samba root shell 基础设施：
     * 1. 从 assets 提取 socat + samba_exec.sh 到 app filesDir/shell/
     * 2. 通过 ADB shell 复制到 /data/local/tmp/ufi_axis/
     * 3. 修改 smb.conf 添加 root preexec
     *
     * 须在 ADB 通道就绪后调用（ShellExecutor.executeAsRoot 走 ADB shell）。
     */
    suspend fun deploy(context: Context) {
        try {
            // ① 从 assets 提取到 filesDir/shell/（AssetExtractor 会覆盖已有文件）
            AssetExtractor.extractAll(context, "shell")
            val localSocat = File(context.filesDir, "shell/socat")
            val localScript = File(context.filesDir, "shell/samba_exec.sh")

            if (!localSocat.exists()) {
                AppLogger.w(TAG, "socat binary not found in assets: ${localSocat.absolutePath}")
                return
            }
            if (!localScript.exists()) {
                AppLogger.w(TAG, "samba_exec.sh not found in assets: ${localScript.absolutePath}")
                return
            }

            // ② 创建共享目录 + 复制文件（ADB shell uid 2000 可写 /data/local/tmp/）
            // 复制 socat（Samba root shell 用）+ samba_exec.sh（root preexec 脚本）+ adb（脚本 pm install 用）
            val localAdb = File(context.filesDir, "shell/adb")
            var copyCmd = "mkdir -p $BASE_DIR && " +
                "cp -f '${localSocat.absolutePath}' '$SOCAT_PATH' && " +
                "cp -f '${localScript.absolutePath}' '$SCRIPT_PATH' && " +
                "chmod 755 '$SOCAT_PATH' && " +
                "chmod 755 '$SCRIPT_PATH'"
            if (localAdb.exists()) {
                copyCmd += " && cp -f '${localAdb.absolutePath}' '$BASE_DIR/adb' && chmod 755 '$BASE_DIR/adb'"
            }
            val copyResult = ShellExecutor.executeAsRoot(copyCmd, timeoutMs = 15_000L)
            if (!copyResult.isSuccess) {
                AppLogger.w(TAG, "File deploy FAILED (exit=${copyResult.exitCode}): ${copyResult.stderr.ifBlank { copyResult.stdout }}. " +
                    "Likely ADB unavailable → executeAsRoot fell back to uid=10101 which cannot write $BASE_DIR")
                return
            }
            // 验证文件确实存在
            val verify = ShellExecutor.executeAsRoot("ls -la $SOCAT_PATH $SCRIPT_PATH 2>&1", 5_000L)
            if (!verify.isSuccess || verify.stdout.contains("No such file")) {
                AppLogger.w(TAG, "File deploy verification FAILED: socat/script not found at $BASE_DIR after copy")
                return
            }
            AppLogger.i(TAG, "Files deployed to $BASE_DIR (verified)")

            // ③ 修改 smb.conf 添加 root preexec
            writeSmbConf()

            // ④ 自动触发 Samba 连接 → root preexec → socat socket
            // 使用 jCIFS 从设备自身连接 Samba 共享，smbd 以 root 身份执行 root preexec
            Thread {
                try {
                    triggerSambaConnection()
                } catch (e: Exception) {
                    AppLogger.w(TAG, "Samba auto-trigger failed (non-fatal): ${e.message}")
                }
            }.start()

            AppLogger.i(TAG, "Samba root shell infrastructure deployed, auto-trigger started")
        } catch (e: Exception) {
            AppLogger.w(TAG, "deploy failed: ${e.message}")
        }
    }

    /**
     * 修改 /data/samba/etc/smb.conf，添加 root preexec 指向我们的脚本。
     * 处理 chattr +i 锁定（UFI-TOOLS 或其他工具可能已锁定）。
     */
    private suspend fun writeSmbConf() {
        // 先尝试解锁（可能被 UFI-TOOLS 的 chattr +i 锁定）
        ShellExecutor.executeAsRoot(
            "chattr -i $SMB_CONF 2>/dev/null; chmod 666 $SMB_CONF 2>/dev/null",
            timeoutMs = 5_000L
        )

        // 检查是否已有我们的 root preexec
        val check = ShellExecutor.executeAsRoot(
            "grep -c 'ufi_axis/samba_exec.sh' $SMB_CONF 2>/dev/null",
            timeoutMs = 5_000L
        )
        if (check.stdout.trim().toIntOrNull() ?: 0 > 0) {
            AppLogger.i(TAG, "smb.conf already has our root preexec")
            return
        }

        // 追加 root preexec 到 smb.conf 的 [global] 段
        // 使用 sed 在 [global] 段末尾（下一个 [ 之前）插入
        val preexecLine = "   root preexec = /system/bin/sh $SCRIPT_PATH"
        val result = ShellExecutor.executeAsRoot(
            // 方案1：如果 smb.conf 存在且有 [global] 段，在 [global] 后插入
            "if [ -f '$SMB_CONF' ] && grep -q '\\[global\\]' $SMB_CONF 2>/dev/null; then " +
                "sed -i '/^\\[global\\]/a\\$preexecLine' $SMB_CONF 2>/dev/null; " +
            // 方案2：如果 smb.conf 存在但没有 [global]，追加到文件开头
            "elif [ -f '$SMB_CONF' ]; then " +
                "echo '$preexecLine' > /tmp/ufi_smb_new.conf 2>/dev/null; " +
                "cat $SMB_CONF >> /tmp/ufi_smb_new.conf 2>/dev/null; " +
                "mv /tmp/ufi_smb_new.conf $SMB_CONF 2>/dev/null; " +
            // 方案3：smb.conf 不存在，创建完整配置
            "else " +
                "mkdir -p /data/samba/etc 2>/dev/null; " +
                "echo '[global]' > $SMB_CONF 2>/dev/null; " +
                "echo '   workgroup = SAMBA' >> $SMB_CONF 2>/dev/null; " +
                "echo '   security = user' >> $SMB_CONF 2>/dev/null; " +
                "echo '   map to guest = bad user' >> $SMB_CONF 2>/dev/null; " +
                "echo '   guest account = nobody' >> $SMB_CONF 2>/dev/null; " +
                "echo '$preexecLine' >> $SMB_CONF 2>/dev/null; " +
                "echo '' >> $SMB_CONF 2>/dev/null; " +
                "echo '[share]' >> $SMB_CONF 2>/dev/null; " +
                "echo '   path = /sdcard' >> $SMB_CONF 2>/dev/null; " +
                "echo '   browseable = yes' >> $SMB_CONF 2>/dev/null; " +
                "echo '   writable = yes' >> $SMB_CONF 2>/dev/null; " +
                "echo '   guest ok = yes' >> $SMB_CONF 2>/dev/null; " +
                "echo '   public = yes' >> $SMB_CONF 2>/dev/null; " +
            "fi",
            timeoutMs = 10_000L
        )

        if (result.isSuccess) {
            AppLogger.i(TAG, "smb.conf updated with root preexec")
        } else {
            AppLogger.w(TAG, "smb.conf update failed: exit=${result.exitCode} stderr=${result.stderr}")
        }
    }

    /**
     * 自动触发 Samba 连接 — 使用 jCIFS 从设备自身连接 Samba 共享。
     *
     * 原理：ZTE F50 的 Samba 服务配置了 root preexec，每次有客户端连接时以 root 执行脚本。
     * 通过 jCIFS 库从 app 进程连接 localhost 的 Samba 共享 → smbd 执行 root preexec →
     * samba_exec.sh 启动 socat 监听 Unix socket → root shell 可用。
     *
     * 参考：UFI-TOOLS 的 SmbThrottledRunner 使用相同机制。
     */
    fun triggerSambaConnection() {
        // ZTE F50 作为 WiFi 热点，自身 IP 为 192.168.0.1
        // 尝试多个 share 名称（不同 ROM 版本可能不同）
        val hosts = listOf("192.168.0.1", "127.0.0.1")
        val shares = listOf("internal_storage", "share", "sdcard")

        for (host in hosts) {
            for (share in shares) {
                val url = "smb://$host/$share/"
                try {
                    AppLogger.i(TAG, "Trying SMB connect: $url")
                    val ctx = SingletonContext.getInstance()
                    val smbFile = SmbFile(url, ctx)
                    if (smbFile.exists()) {
                        AppLogger.i(TAG, "SMB auto-trigger SUCCESS: $url (root preexec triggered)")
                        return
                    }
                } catch (e: Exception) {
                    AppLogger.w(TAG, "SMB connect failed: $url — ${e.message?.take(80)}")
                }
            }
        }
        AppLogger.w(TAG, "SMB auto-trigger: all hosts/shares failed")
    }

    /**
     * 检查 root shell socket 是否可用。
     * 先检查我们自己的 socket，再检查 UFI-TOOLS 的 socket（同一 Samba 机制，可复用）。
     * @return true 如果任一 socket 文件存在且可连接
     */
    fun isSocketAvailable(): Boolean {
        // 先检查我们自己的 socket
        if (checkSocket(SOCKET_PATH)) return true
        // 再检查 UFI-TOOLS 的 socket（如果设备装了 UFI-TOOLS，它的 Samba root preexec 已启动 socat）
        if (checkSocket(UFI_TOOLS_SOCKET_PATH)) return true
        return false
    }

    /**
     * 返回 Samba 部署状态的诊断字符串（用于写入 update log）。
     * 检查：socat/script/adb 文件是否存在、smb.conf 是否包含 root preexec、socket 是否可用。
     */
    suspend fun deployDiagnostic(): String {
        val parts = mutableListOf<String>()
        // 检查 /data/local/tmp/ufi_axis/ 下的文件
        val baseFiles = listOf("socat" to SOCAT_PATH, "samba_exec.sh" to SCRIPT_PATH, "adb" to "$BASE_DIR/adb")
        for ((name, path) in baseFiles) {
            val f = File(path)
            parts.add("$name=${if (f.exists()) "OK" else "MISSING"}($path)")
        }
        // 检查 socket
        parts.add("socket=${if (isSocketAvailable()) "READY" else "NOT_READY"}(${availableSocketPath() ?: "none"})")
        // 检查 smb.conf 是否有 root preexec（通过 ShellExecutor）
        try {
            val check = ShellExecutor.executeAsRoot(
                "grep -c 'ufi_axis/samba_exec.sh' $SMB_CONF 2>/dev/null",
                timeoutMs = 3_000L
            )
            val count = check.stdout.trim().toIntOrNull() ?: 0
            parts.add("smbConf=${if (count > 0) "PATCHED" else "NOT_PATCHED"}")
        } catch (e: Exception) {
            parts.add("smbConf=CHECK_FAILED(${e.message?.take(40)})")
        }
        return parts.joinToString(", ")
    }

    /** 当前可用的 socket 路径（我们自己的或 UFI-TOOLS 的） */
    fun availableSocketPath(): String? {
        if (checkSocket(SOCKET_PATH)) return SOCKET_PATH
        if (checkSocket(UFI_TOOLS_SOCKET_PATH)) return UFI_TOOLS_SOCKET_PATH
        return null
    }

    private fun checkSocket(path: String): Boolean {
        return try {
            val socket = LocalSocket()
            val address = LocalSocketAddress(path, LocalSocketAddress.Namespace.FILESYSTEM)
            socket.connect(address)
            socket.close()
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 通过 root shell socket 执行 pm install -r -g。
     *
     * @param apkPath APK 文件路径（须是 root 可读的路径，如 /data/local/tmp/ 或 /sdcard/）
     * @return Pair<是否成功, 日志信息>
     */
    fun installViaRootShell(apkPath: String): Pair<Boolean, String> {
        val sockPath = availableSocketPath() ?: return false to "root shell socket not available (需启用 Samba 并从电脑连接一次共享)"

        // 先验证 socket 确实是 root
        val whoami = sendCommand("whoami", sockPath) ?: return false to "socket connect failed"
        if (!whoami.contains("root")) {
            AppLogger.w(TAG, "socket is not root: whoami=$whoami")
            return false to "socket is not root (uid=${whoami.trim()})"
        }

        // 通过 root shell 执行 pm install
        val installCmd = "pm install -r -g '$apkPath' 2>&1"
        val result = sendCommand(installCmd, sockPath, timeoutMs = 120_000)

        if (result == null) {
            return false to "root shell command timeout"
        }

        val output = result.trim()
        AppLogger.i(TAG, "pm install via root shell ($sockPath): $output")

        // pm install 成功输出 "Success"
        val success = output.contains("Success", ignoreCase = true)
        return success to output
    }

    /**
     * 通过 root shell socket 发送命令并读取输出。
     *
     * @param command 要执行的 shell 命令
     * @param socketPath socket 文件路径
     * @param timeoutMs 超时毫秒数
     * @return 命令输出，失败返回 null
     */
    private fun sendCommand(command: String, socketPath: String, timeoutMs: Int = 30_000): String? {
        val socket = LocalSocket()
        val address = LocalSocketAddress(socketPath, LocalSocketAddress.Namespace.FILESYSTEM)
        return try {
            socket.connect(address)
            socket.soTimeout = timeoutMs

            val writer = BufferedWriter(OutputStreamWriter(socket.outputStream))
            val reader = BufferedReader(InputStreamReader(socket.inputStream))

            val endTag = "__UFI_AXIS_END_${java.util.UUID.randomUUID()}__"

            // 发送命令 + 结束标记
            writer.write(command)
            writer.write("\n")
            writer.write("printf '%s\\n' '$endTag'\n")
            writer.flush()

            // 读取直到结束标记
            val result = StringBuilder()
            while (true) {
                val line = reader.readLine() ?: break
                val endIdx = line.indexOf(endTag)
                if (endIdx >= 0) {
                    val before = line.substring(0, endIdx)
                    if (before.isNotEmpty()) result.append(before)
                    break
                }
                result.appendLine(line)
            }

            result.toString().trimEnd()
        } catch (e: Exception) {
            AppLogger.w(TAG, "sendCommand error: ${e.message}")
            null
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
    }
}
