package com.ufi_axis_core.util

import android.content.Context
import android.net.LocalSocket
import android.net.LocalSocketAddress
import jcifs.context.SingletonContext
import jcifs.smb.SmbFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter

/**
 * Samba root shell —— 借 ZTE F50 固件 `smbd` 的 `root preexec` 拿 **uid 0**。
 *
 * ## 原理（2026-11-05 真机取证，不是推测）
 *
 * `smbd` 以 root 运行，`root preexec` 是 Samba 的**服务级**参数：客户端 TREE_CONNECT
 * 到某个共享时，smbd 以 root fork 执行这条命令。实测日志：
 *
 * ```
 * preexec fired uid=0 user=root
 * uid=0(root) gid=0(root) groups=0(root) context=u:r:su:s0
 * ```
 *
 * 之所以能改 `/data/samba/etc/smb.conf`，是因为固件把它放在 777 目录、且 SELinux 为
 * permissive —— **不是**攻破了 smbd。
 *
 * ## 三条必须守住的实测结论
 *
 * 1. **脚本必须 `exit 0`**：root preexec 非 0 时 Samba 判定**该次连接失败**
 *    （源码里的 `"root preexec gave %d - failing connection"`）。脚本报错会把用户的
 *    SMB 共享一起弄挂。
 * 2. **socket 要显式 chmod**：socket 由 root 创建、连接方是 app uid，默认 755 会让
 *    `LocalSocket.connect()` 报 EACCES。
 * 3. **不需要重启 smbd**：Samba 每次连接都重读配置，写完下一次连接即生效。
 *
 * ## 路径为什么在 app 私有目录（2026-11-05 重写）
 *
 * 旧实现把 socat/脚本/socket 放在固定的 `/data/local/tmp/ufi_axis`。那个路径**跨安装共享**：
 * 重装后 uid 从 10101 变成 10110，目录仍是 `drwx------ u0_a101`，新进程和 adb shell(2000)
 * 都进不去 → `deploy()` 在拷贝阶段就 `return`，`smb.conf` 永远拿不到 `root preexec`，
 * 整条链静默失效（真机现场就是这种状态）。
 *
 * 挪进 `filesDir/root_shell` 一次解决三个问题：
 * - 不再和任何一次旧安装撞车（卸载即清理）；
 * - app 必然能读写自己的目录，不需要 adb/root 帮忙；
 * - socket 落在 0700 的应用目录里，**同机其它应用无法遍历到**，比放在 777 的
 *   `/data/local/tmp` 更安全。
 *
 * 代价：依赖 `/data` 的 FBE 已解锁（无锁屏的 CPE 开机即解锁），以及 smbd 的 root 进程
 * 能读到应用私有目录（permissive 下成立）。
 *
 * ## 现状
 *
 * 本类目前**没有生产调用方消费 socket**：`pm install -g` 走的是 `UpdateManager` 的脚本
 * 通道，[installViaRootShell] 暂无调用点。保活循环仍每 60s 检查一次，保证通道可用。
 */
object SambaRootShell {

    private const val TAG = "SambaRootShell"

    /** 部署目录名（相对 `filesDir`）。 */
    const val DIR_NAME = "root_shell"
    const val SOCAT_NAME = "socat"
    const val SCRIPT_NAME = "samba_exec.sh"
    const val SOCKET_NAME = "root.sock"

    /** 固件 Samba 配置。 */
    private const val SMB_CONF = "/data/samba/etc/smb.conf"

    /** INI 段名。 */
    private val SECTION_RE = Regex("""^\s*\[([^\]]+)]\s*$""")

    /** 这几个段不是可连接的共享，不能拿来触发 preexec。 */
    private val RESERVED_SECTIONS = setOf("global", "homes", "printers", "print$", "ipc$")

    // ─────────── 路径 ───────────

    fun baseDir(context: Context): File = File(context.filesDir, DIR_NAME)

    fun socatFile(context: Context): File = File(baseDir(context), SOCAT_NAME)

    fun scriptFile(context: Context): File = File(baseDir(context), SCRIPT_NAME)

    fun socketFile(context: Context): File = File(baseDir(context), SOCKET_NAME)

    /** 部署结果：失败时 [stage] 指出卡在哪一步，便于直接显示给用户或写诊断日志。 */
    data class DeployResult(
        val ok: Boolean,
        /** `prepare` / `patch` / `trigger` / `verify` */
        val stage: String,
        val detail: String
    )

    // ─────────── 部署 ───────────

    /**
     * 部署并尝试启用 root 通道。**不依赖 ADB**：全部走 app 自己的文件操作 + jCIFS。
     *
     * 幂等：目录/文件已存在会覆盖，`smb.conf` 已是目标状态则跳过写入。
     */
    suspend fun deploy(context: Context): DeployResult = withContext(Dispatchers.IO) {
        // ── ① 目录与二进制 ──
        val dir = baseDir(context)
        if (!dir.exists() && !dir.mkdirs()) {
            return@withContext DeployResult(false, "prepare", "无法创建目录 $dir")
        }
        if (!dir.canWrite()) {
            return@withContext DeployResult(false, "prepare", "目录不可写：$dir")
        }

        AssetExtractor.extractAll(context, "shell")
        val srcSocat = File(AssetExtractor.getPath(context, SOCAT_NAME))
        val srcScript = File(AssetExtractor.getPath(context, SCRIPT_NAME))
        if (!srcSocat.exists() || srcSocat.length() == 0L) {
            return@withContext DeployResult(false, "prepare", "socat 资产缺失：$srcSocat")
        }
        if (!srcScript.exists() || srcScript.length() == 0L) {
            return@withContext DeployResult(false, "prepare", "脚本资产缺失：$srcScript")
        }

        val socat = socatFile(context)
        val script = scriptFile(context)
        runCatching { srcSocat.copyTo(socat, overwrite = true) }
            .onFailure { return@withContext DeployResult(false, "prepare", "拷贝 socat 失败：${it.message}") }
        runCatching { srcScript.copyTo(script, overwrite = true) }
            .onFailure { return@withContext DeployResult(false, "prepare", "拷贝脚本失败：${it.message}") }

        // root preexec 以 `/system/bin/sh <script>` 调用，脚本只需可读；
        // socat 需要真 exec（permissive 下 app_data_file 才允许 execute_no_trans）。
        script.setReadable(true, false)
        socat.setExecutable(true, true)
        socat.setReadable(true, false)
        if (!socat.canExecute()) {
            return@withContext DeployResult(
                false, "prepare",
                "socat 不可执行（enforcing SELinux 下会这样，见 NativeExecProbe）：$socat"
            )
        }

        // ── ② 注入 root preexec ──
        //
        // 2026-11-07：开机瞬间 `smb.conf` 可能还没被固件写出来（真机见过：core 比 smbd 早几秒
        // 起来，此时文件不存在）。deploy 只在启动时跑一次，读到 null 就直接放弃了 ——
        // 结果整条 root 通道要到下次重启才有机会。这里给 3 次重试（共约 6 秒）。
        var current: String? = null
        repeat(3) { attempt ->
            current = readConf(context)
            if (current == null && attempt < 2) delay(2_000L)
        }
        val conf = current
            ?: return@withContext DeployResult(false, "patch", "读不到 $SMB_CONF（固件不是 ZTE Samba？）")
        val patched = patchConf(conf, script.absolutePath)
        if (patched != conf) {
            // chattr +i 只有 root 能解；解不掉也不致命，写入会自己失败并报出来
            ShellExecutor.executeAsRoot("chattr -i '$SMB_CONF' 2>/dev/null", 3_000L)
            writeConf(context, patched)?.let { err ->
                return@withContext DeployResult(false, "patch", err)
            }
            AppLogger.i(TAG, "smb.conf 已注入 root preexec → ${script.absolutePath}")
        } else {
            AppLogger.i(TAG, "smb.conf 已是目标状态，跳过写入")
        }

        // ── ③ 触发一次 TREE_CONNECT ──
        val shares = sharesOf(conf)
        if (shares.isEmpty()) {
            return@withContext DeployResult(false, "trigger", "smb.conf 里没有可连接的共享段")
        }
        val triggered = triggerSambaConnection(context, shares)

        // ── ④ 确认 socket ──
        return@withContext if (isSocketAvailable(context)) {
            AppLogger.i(TAG, "root 通道就绪：${socketFile(context)}")
            DeployResult(true, "verify", "socket ready：${socketFile(context).absolutePath}")
        } else {
            val why = if (triggered) "已连上共享但 socket 未出现" else "所有 host×share 组合都没连上"
            AppLogger.w(TAG, "root 通道未就绪：$why（shares=$shares）")
            DeployResult(false, "verify", "$why（shares=$shares）")
        }
    }

    // ─────────── 触发 ───────────

    /**
     * 连一次共享，让 smbd 执行 root preexec。
     *
     * @param shares 为空时从 smb.conf 现读。**必须用真实段名**：旧实现硬编码
     *   `internal_storage/share/sdcard`，而本机固件的段名是 `F50`，于是永远触发不到。
     * @return 是否在 [timeoutMs] 内等到 socket
     */
    suspend fun triggerSambaConnection(
        context: Context,
        shares: List<String>? = null,
        timeoutMs: Long = 5_000L
    ): Boolean = withContext(Dispatchers.IO) {
        val names = shares?.takeIf { it.isNotEmpty() } ?: sharesOf(readConf(context).orEmpty())
        if (names.isEmpty()) {
            AppLogger.w(TAG, "smb.conf 里没有可连接的共享段，无法触发 root preexec")
            return@withContext false
        }

        // 192.168.0.1 是 F50 作为热点时的自身地址；127.0.0.1 兜底。
        val hosts = listOf("192.168.0.1", "127.0.0.1")
        for (host in hosts) {
            for (share in names) {
                val url = "smb://$host/$share/"
                val reachable = runCatching {
                    SmbFile(url, SingletonContext.getInstance()).exists()
                }.getOrElse { e ->
                    AppLogger.d(TAG, "连接 $url 失败：${e.message?.take(80)}")
                    false
                }
                if (!reachable) continue

                AppLogger.i(TAG, "SMB 连接成功（已触发 root preexec）：$url")
                if (awaitSocket(context, timeoutMs)) return@withContext true
            }
        }

        AppLogger.w(TAG, "触发失败：hosts=$hosts，shares=$names")
        false
    }

    private suspend fun awaitSocket(context: Context, timeoutMs: Long): Boolean {
        val path = socketFile(context).absolutePath
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (checkSocket(path)) return true
            delay(200L)
        }
        return checkSocket(path)
    }

    // ─────────── socket ───────────

    /**
     * 只看**我们自己的** socket。
     *
     * 2026-11-07 安全修正：原实现会在自己的 socket 不可用时回落到 UFI-TOOLS 的
     * `/data/data/com.minikano.f50_sms/files/kano_root_shell.sock`。那是一个**别的应用**
     * 提供的通道 —— 谁能起那个 socket，谁就能冒充 core 的 root 通道，而 [runAsRoot] 拿到
     * 的返回值没有任何身份校验。既然本机自己的通道已经能稳定工作，这个回落只有风险没有收益，
     * 直接删掉。
     */
    fun isSocketAvailable(context: Context): Boolean =
        checkSocket(socketFile(context).absolutePath)

    /** 当前可用的 socket 路径（只有我们自己的那份）。 */
    fun availableSocketPath(context: Context): String? {
        val own = socketFile(context).absolutePath
        return if (checkSocket(own)) own else null
    }

    private fun checkSocket(path: String): Boolean = try {
        val socket = LocalSocket()
        socket.connect(LocalSocketAddress(path, LocalSocketAddress.Namespace.FILESYSTEM))
        socket.close()
        true
    } catch (_: Exception) {
        false
    }

    // ─────────── 通过 root shell 执行命令 ───────────

    /**
     * 通过 root socket 执行一条命令，返回输出；socket 不可用或超时返回 null。
     *
     * 这是当前**唯一**能让 core 稳定拿到 uid 0 的通道（ADB 自连在本机实测不生效，
     * 见 `SambaRootShell` 类注释）。调用方需自行判断返回内容 —— 本方法只负责把命令送进去。
     *
     * 注意：命令由 socket 那头的常驻 `sh` 执行，**不会**继承 app 的 cwd/环境变量，
     * 也不要在命令里依赖交互式输入。
     */
    suspend fun runAsRoot(context: Context, command: String, timeoutMs: Int = 30_000): String? {
        val sockPath = availableSocketPath(context) ?: return null
        // 先确认这确实是一个 uid 0 的 shell，再把调用方的命令送进去。
        // 每次调用都验：socket 文件可被替换，缓存等于只防一次（见下方说明）。
        if (!verifySocketIsRoot(sockPath)) return null
        return sendCommand(command, sockPath, timeoutMs)
    }

    // 2026-11-14：**不缓存**校验结果。
    //
    // 原先这里记一个 `verifiedSocketPath`，同一条路径只验一次。可这道校验存在的全部理由
    // 就是"socket 文件可能被 unlink 后重建"—— 缓存在路径上，等于攻击者只要等我们验过一次，
    // 之后再替换就能一路畅通，防线只生效一次。每次用之前重验，`id -u` 是一次本地 socket
    // 往返，代价可以忽略。

    /**
     * 确认 [sockPath] 那头是 root。
     *
     * 为什么需要：socket 是我们起的，但 socket 文件本身可被替换/预置（同一个文件系统上
     * 有写权限的主体都能 `unlink` 后重建一个同名 socket）。之前只有 [installViaRootShell]
     * 做了 whoami 校验，[runAsRoot] 没有 —— 等于给了一条"命令送进别人 shell"的路。
     */
    private suspend fun verifySocketIsRoot(sockPath: String): Boolean {
        val uid = sendCommand("id -u", sockPath, 5_000)?.trim()
        return if (uid == "0") {
            true
        } else {
            AppLogger.e(TAG, "root socket 身份校验失败（id -u=$uid），拒绝使用：$sockPath")
            false
        }
    }

    /**
     * 通过 root shell socket 执行 `pm install -r -g`。
     *
     * 当前无生产调用方（更新走 `UpdateManager` 的脚本通道），保留作为既有能力。
     */
    suspend fun installViaRootShell(context: Context, apkPath: String): Pair<Boolean, String> {
        val sockPath = availableSocketPath(context)
            ?: return false to "root shell socket not available（需 Samba 在跑且已注入 root preexec）"

        val whoami = sendCommand("whoami", sockPath) ?: return false to "socket 连接失败"
        if (!whoami.contains("root")) {
            AppLogger.w(TAG, "socket 返回的不是 root：whoami=$whoami")
            return false to "socket is not root（uid=${whoami.trim()}）"
        }

        val out = sendCommand("pm install -r -g '$apkPath' 2>&1", sockPath, 120_000)
            ?: return false to "root shell 命令超时"
        val text = out.trim()
        AppLogger.i(TAG, "pm install via root shell（$sockPath）：$text")
        return text.contains("Success", ignoreCase = true) to text
    }

    private suspend fun sendCommand(
        command: String,
        socketPath: String,
        timeoutMs: Int = 30_000
    ): String? = withContext(Dispatchers.IO) {
        val socket = LocalSocket()
        val address = LocalSocketAddress(socketPath, LocalSocketAddress.Namespace.FILESYSTEM)
        try {
            socket.connect(address)
            socket.soTimeout = timeoutMs

            val writer = BufferedWriter(OutputStreamWriter(socket.outputStream))
            val reader = BufferedReader(InputStreamReader(socket.inputStream))

            // 用随机结束标记界定输出边界：socket 那头是常驻 sh，不会 EOF。
            val endTag = "__UFI_AXIS_END_${java.util.UUID.randomUUID()}__"
            writer.write(command)
            writer.write("\n")
            writer.write("printf '%s\\n' '$endTag'\n")
            writer.flush()

            val result = StringBuilder()
            while (true) {
                val line = reader.readLine() ?: break
                val idx = line.indexOf(endTag)
                if (idx >= 0) {
                    if (idx > 0) result.append(line.substring(0, idx))
                    break
                }
                result.appendLine(line)
            }
            result.toString().trimEnd()
        } catch (e: Exception) {
            AppLogger.w(TAG, "sendCommand 失败：${e.message}")
            null
        } finally {
            runCatching { socket.close() }
        }
    }

    // ─────────── smb.conf ───────────

    /**
     * 读配置。三级降级：直接读 → **root socket（uid 0）** → ADB（uid 2000）。
     *
     * 2026-11-07 补 root 通道：一旦按"加固"建议把 `/data/samba` 收成 0700 root
     *（现在是 777 + permissive，等于任何 app 都能改），前两级都会失效，只剩 uid 0 能读。
     * 没有这一级的话，收紧权限后的下一次 deploy 会读不到配置 → root 通道永久失联。
     */
    private suspend fun readConf(context: Context): String? {
        runCatching { File(SMB_CONF).readText() }
            .getOrNull()?.takeIf { it.isNotBlank() }
            ?.let { return it }

        runAsRoot(context, "cat '$SMB_CONF' 2>/dev/null")
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }

        val r = ShellExecutor.executeAsRoot("cat '$SMB_CONF' 2>/dev/null", 3_000L)
        return r.stdout.takeIf { it.isNotBlank() }
    }

    /**
     * 写配置。**原地写**（保留 inode/属主/权限）—— 若换成 `mv` 覆盖，文件属主会变成 app，
     * 之后 adb shell(2000) 就再也改不动它了。
     *
     * @return null 表示成功；否则是给人看的中文原因
     */
    private suspend fun writeConf(context: Context, text: String): String? {
        val file = File(SMB_CONF)

        // ① 直接写（777 + permissive 下可行，保留 inode/属主/权限）
        val directErr = runCatching { file.writeText(text) }.exceptionOrNull()
        if (directErr == null && readConfEquals(text)) return null

        // ② 退 root socket（uid 0）—— 权限收紧后**唯一**还能写的通道。
        //    临时文件放 app 私有目录（root 读得到），不放 /data/local/tmp：
        //    那条路径世界可写，是攻击面。
        val privTmp = File(context.cacheDir, "smb.conf.new")
        if (runCatching { privTmp.writeText(text) }.isSuccess) {
            val out = runAsRoot(
                context,
                "cat '${privTmp.absolutePath}' > '$SMB_CONF' && rm -f '${privTmp.absolutePath}' && echo UFI_CONF_WRITTEN",
                8_000
            )
            if (out?.contains("UFI_CONF_WRITTEN") == true && readConfEquals(text)) return null
            runCatching { privTmp.delete() }
        }

        // ③ 退 ADB（uid 2000）。临时文件必须落到 /data/local/tmp —— uid 2000 读不到 app 私有目录。
        val pubTmp = File("/data/local/tmp/ufi_smb_conf.tmp")
        if (runCatching { pubTmp.writeText(text) }.isSuccess) {
            val r = ShellExecutor.executeAsRoot(
                "cat '${pubTmp.absolutePath}' > '$SMB_CONF' && rm -f '${pubTmp.absolutePath}'",
                8_000L
            )
            if (r.isSuccess && readConfEquals(text)) return null
        }

        return "写 $SMB_CONF 失败" +
            (directErr?.let { "（直接写：${it.message}）" } ?: "（直接写后内容校验不符）") +
            "；root socket 与 ADB 通道都没成功。是否被 chattr +i 锁定？"
    }

    private fun readConfEquals(text: String): Boolean =
        runCatching { File(SMB_CONF).readText() }.getOrNull() == text

    /**
     * 生成打了 root preexec 的配置。纯函数，可单测。
     *
     * 两件事：
     * 1. **先删掉本机制留下的旧行** —— 旧安装会写 `/data/local/tmp/ufi_axis/samba_exec.sh`，
     *    那个文件早就不存在了。留着的话 smbd 每次连接都会执行失败，进而**让连接失败**。
     * 2. 插到**第一个真实共享段**里（不是 `[global]`）：`root preexec` 是服务级参数，
     *    写进具体段最准确；写在 `[global]` 只能当"所有共享的默认值"用。
     */
    internal fun patchConf(raw: String, scriptPath: String): String {
        val lines = raw.split("\n").toMutableList()
        lines.removeAll { line ->
            val t = line.trim()
            t.startsWith("root preexec") && t.contains(SCRIPT_NAME)
        }

        val preexec = "   root preexec = /system/bin/sh $scriptPath"
        val shareIdx = lines.indexOfFirst { isRealShare(it) }
        val globalIdx = lines.indexOfFirst { it.trim().equals("[global]", ignoreCase = true) }

        when {
            shareIdx >= 0 -> lines.add(shareIdx + 1, preexec)
            globalIdx >= 0 -> lines.add(globalIdx + 1, preexec)
            else -> {
                lines.add(0, "[global]")
                lines.add(1, preexec)
            }
        }
        return lines.joinToString("\n")
    }

    /** 从配置里取可连接的共享段名。 */
    internal fun sharesOf(raw: String): List<String> =
        raw.split("\n")
            .mapNotNull { sectionNameOf(it) }
            .filter { it.lowercase() !in RESERVED_SECTIONS }
            .distinct()

    private fun sectionNameOf(line: String): String? =
        SECTION_RE.find(line)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }

    private fun isRealShare(line: String): Boolean {
        val name = sectionNameOf(line) ?: return false
        return name.lowercase() !in RESERVED_SECTIONS
    }

    // ─────────── 诊断 ───────────

    /** 一行式诊断，写进 update log 或排障页。 */
    suspend fun deployDiagnostic(context: Context): String {
        val dir = baseDir(context)
        val socat = socatFile(context)
        val script = scriptFile(context)
        val conf = readConf(context)

        val parts = mutableListOf<String>()
        parts += "dir=${if (dir.isDirectory) "OK" else "MISSING"}($dir)"
        parts += "dirWritable=${dir.canWrite()}"
        parts += "socat=${if (socat.canExecute()) "OK" else "MISSING"}(${socat.absolutePath})"
        parts += "script=${if (script.isFile) "OK" else "MISSING"}(${script.absolutePath})"
        parts += when {
            conf == null -> "smbConf=UNREADABLE($SMB_CONF)"
            conf.contains(script.absolutePath) -> "smbConf=PATCHED"
            else -> "smbConf=NOT_PATCHED"
        }
        parts += "shares=${conf?.let { sharesOf(it) } ?: emptyList<String>()}"
        parts += "socket=${if (isSocketAvailable(context)) "READY" else "NOT_READY"}" +
            "(${availableSocketPath(context) ?: "none"})"
        parts += "adb=${if (AdbShellExecutor.isAvailable) "UP" else "DOWN"}"
        return parts.joinToString(", ")
    }
}
