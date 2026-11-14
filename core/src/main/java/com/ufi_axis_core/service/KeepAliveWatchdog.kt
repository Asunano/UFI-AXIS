package com.ufi_axis_core.service

import android.content.Context
import com.ufi_axis_core.util.AppLogger
import com.ufi_axis_core.util.SambaRootShell
import com.ufi_axis_core.util.ShellExecutor
import kotlinx.coroutines.delay
import java.io.File
import java.security.MessageDigest

/**
 * shell 看门狗（`assets/shell/ufi_keepalive.sh`）的部署与存活判定（2026-09-04 抽出）。
 *
 * 为什么要单独一个对象：部署点有两个 —— [BootReceiver]（开机，先于 Core 起来，解决鸡生蛋）
 * 与 [BackendService]（ADB 通道就绪后补一次 + 定期自愈）。两边原本各写一份「锁存在就跳过」
 * 的判定，而那条判定本身是错的（见 [isRunning]），改一处修不干净。
 *
 * 权限模型：脚本放 `/data/local/tmp`（世界可写），app 直接写文件 + 放开 world r/x。
 * 执行通道有两条，按优先级：
 *   1. **root socket（uid 0）** —— 由 [SambaRootShell] 提供（smbd 的 root preexec）。
 *      2026-11-05 真机实测：本机**只有这条**真正生效。
 *   2. [ShellExecutor.executeAsRoot]（ADB shell uid 2000）—— ADB 不通时会静默降级成
 *      app uid，脚本随即以 `refuse to run as uid=…` 自退。所以拉起后必须验证进程。
 * 两条都不需要 su。
 */
object KeepAliveWatchdog {

    private const val TAG = "KeepAliveWatchdog"

    /** 脚本文件名（落在 app 私有目录，见 [scriptFile]）。 */
    const val SCRIPT_NAME = "ufi_keepalive.sh"

    /**
     * 旧落点：`/data/local/tmp/ufi_keepalive.sh`（777，世界可写）。
     *
     * 2026-11-05 起**只给 ADB(uid 2000) 通道兜底用**。root 通道（uid 0）一律执行
     * [scriptFile] 里那份 —— 那个目录只有本 app 和 root 能写，**不存在"别人换掉脚本、
     * 我们以 root 执行"这条路**。
     *
     * 为什么保留它：ADB 通道跑在 uid 2000，读不到 app 私有目录。两者不能共用一份文件。
     */
    const val LEGACY_SCRIPT_PATH = "/data/local/tmp/ufi_keepalive.sh"

    /** 锁目录。两份脚本共用同一把锁，防止 root / uid2000 各跑一只。 */
    const val LOCK_PATH = "/data/local/tmp/ufi_keepalive.lock"
    private const val PID_PATH = "$LOCK_PATH/pid"

    private const val SHA256_HEX_LENGTH = 64
    private const val HEX_DIGITS = "0123456789abcdef"

    /** 定期自愈的间隔：看门狗被 LMK / phantom process killer 带走后最迟这么久被重新拉起。 */
    const val SELF_HEAL_INTERVAL_MS = 5 * 60 * 1000L

    /** root 通道执行的脚本路径：`filesDir/ufi_keepalive.sh`。 */
    fun scriptFile(context: Context): File = File(context.filesDir, SCRIPT_NAME)

    /**
     * 看门狗**真的**在跑吗。
     *
     * 不能只看锁目录存在：`/data/local/tmp` 跨重启保留，而脚本的 `trap ... EXIT` 在
     * SIGKILL / 掉电时不会执行 —— 锁会永久留下。原实现两个部署点都以「锁存在 = 在跑」跳过启动，
     * 于是一次 SIGKILL 之后看门狗**再也起不来**，而日志里还写着 "already running"。
     * 现在以锁内 pid 是否仍是一个活着的 keepalive 进程为准。
     */
    fun isRunning(): Boolean {
        val pid = runCatching { File(PID_PATH).readText().trim() }.getOrNull()
        if (pid.isNullOrEmpty() || !File("/proc/$pid").exists()) return false
        val cmdline = runCatching {
            File("/proc/$pid/cmdline").readBytes().toString(Charsets.UTF_8).replace('\u0000', ' ')
        }.getOrDefault("")
        return cmdline.contains("ufi_keepalive")
    }

    /**
     * 幂等：把脚本落到 app 私有目录（root 通道）与 `/data/local/tmp`（ADB 通道），没在跑就拉起来。
     *
     * 脚本内容每次都覆盖写 —— 升级后 assets 里的脚本会变，而磁盘上那份是上个版本的；
     * 覆盖是无副作用的（正在运行的 sh 已经把文件读完了，下一次启动才用新内容）。
     *
     * 2026-09-05（P3）：exec 前多一道 SHA-256 复核，见 [ensureDigestMatches]。
     *
     * ## 为什么落点仍是固定文件名（不加随机后缀）
     * 评审时提过"换成一次性随机文件名，降低被预置同名文件抢占的机会"。读完整条链路后
     * **不采纳**，理由是它会破坏本方法赖以幂等的那个前提：
     * - 本方法每 [SELF_HEAL_INTERVAL_MS]（5 分钟）被调一次，且**先写盘、后判存活**；
     *   看门狗在跑时直接 `return false`，那次写盘只是覆盖同一个文件。改成随机名后，
     *   每一次复查都会在 `/data/local/tmp` 留下一份**永不被 exec、也永不被回收**的新脚本
     *   —— 一天 288 个文件，而这个目录没有任何人负责清理。
     * - 想清理就得知道"上一次用的是哪个名字"。名字只存在于内存里，而部署点有两个
     *   （[BootReceiver] 与 [BackendService]，不同时机、可能不同进程），进程被 LMK
     *   回收后这个名字就丢了；退而用 `rm -f ufi_keepalive_*.sh` 通配清理，又会删掉
     *   另一个正准备 exec 的实例刚写好的那份。
     * - 而"抢占同名文件"这件事本身并不构成新的攻击面：能在 `/data/local/tmp` 预置文件的
     *   主体已经是 shell/root（见 [ensureDigestMatches] 的威胁模型），随机名对它毫无阻碍。
     * 所以本轮只做摘要校验 —— 它把"内容被换掉"变成一条日志 + 一次自愈，收益明确、无副作用。
     *
     * @return true = 本次拉起了一只新的看门狗
     */
    suspend fun ensureRunning(context: Context): Boolean {
        val template = try {
            context.assets.open("shell/ufi_keepalive.sh").bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            AppLogger.w(TAG, "keepalive asset not found: ${e.message}")
            return false
        }
        if (template.isBlank()) {
            AppLogger.w(TAG, "keepalive asset is empty")
            return false
        }

        // 落盘内容的期望摘要（assets 里那份的 SHA-256）。写盘用 writeText ⇒ UTF-8，
        // 所以这里也按 UTF-8 取字节，与 shell 侧 sha256sum 读到的字节流一致。
        val expectedDigest = sha256Hex(template.toByteArray())

        if (!writeScript(context, template)) return false

        // ── 已经在跑了？用**root 通道**判定 ──
        //
        // 不能用 app 自己的 isRunning()：本机 /proc 挂的是 hidepid=2，untrusted_app
        // 看不到别的 uid 的进程，`/proc/<pid>` 对它根本不存在 —— isRunning() 恒 false。
        // 真机后果很直接：旧那份（/data/local/tmp）明明在跑，代码却以为没跑，于是再拉起
        // 一只、被脚本的锁挡回（"already running, exit"），迁移永远不触发。
        val priv = scriptFile(context).absolutePath
        val counts = runningCounts(context)
        when {
            // ① 拿不到 root 通道：退回 pid 文件判定（只能看见同 uid 的进程，聊胜于无）
            counts == null -> if (isRunning()) return false
            // ② 私有目录那份已在跑 —— 目标状态
            counts.privateCopy > 0 -> return false
            // ③ 跑着的是旧落点（世界可写）→ 杀掉，改从私有目录拉起
            counts.legacy > 0 -> {
                // 用 kill -9 而不是 pkill：SIGKILL 不会给旧实例跑 trap 的机会，
                // 结果确定；残留的锁由下面一并清掉，否则新实例会以为"already running"。
                AppLogger.i(TAG, "keepalive 正在跑旧落点那份（${counts.legacy} 个），迁移到 app 私有目录")
                SambaRootShell.runAsRoot(
                    context,
                    "for p in \$(pgrep -f '$LEGACY_SCRIPT_PATH'); do kill -9 \$p 2>/dev/null; done; " +
                        "rm -rf '$LOCK_PATH'; true"
                )
                delay(500L)
                val left = runningCounts(context)
                if (left == null || left.legacy > 0) {
                    AppLogger.w(TAG, "keepalive 旧实例未能终止（root 通道不可用？），保留现状")
                    return false
                }
            }
        }

        // 注意：这里**不要**预清残留锁。脚本自己（以 uid 2000 运行）的 acquire_lock 会用
        // 「先删内部 pid、再 rmdir 目录名」两步 unlink 接管任意属主/权限的残留锁 —— 它比
        // app 进程更有权限（app 读不到别的 uid 的 /proc/<pid>/cmdline，isRunning() 可能误判
        // 为「没在跑」）。若在 app 侧抢先删锁，反而会把「脚本原子锁」这道真正的防重入闸门
        // 拆掉，可能同时跑起两只看门狗。

        return try {
            // setsid + nohup：与调用方进程脱钩，Core 被整体回收时它还在。
            // 私有目录那份没起来时**不要直接放弃** —— 继续往下走 ADB 分支，
            // 至少保证设备还有人看着（可用性优先于"只跑安全那份"）。
            if (launchViaRootSocket(context) && awaitRunning(context, 4_000L)) return true
            AppLogger.w(TAG, "私有目录那份未起来（root 通道异常？），退回旧落点兜底")

            // root socket 不可用（smbd 没跑 / smb.conf 未注入）→ 退回 ADB 通道（uid 2000）。
            // 注意这里只能用**旧落点**：uid 2000 读不到 app 私有目录。
            // 纵深防御也只对这一份做 —— 旧落点世界可写，是唯一可能被人换掉的执行目标；
            // app 私有目录那份只有本 app 和 root 能写，不存在被替换的前提。
            if (!ensureDigestMatches(expectedDigest, template)) return false
            val result = ShellExecutor.executeAsRoot(
                "nohup setsid sh $LEGACY_SCRIPT_PATH >/dev/null 2>&1 &", 10_000L
            )
            AppLogger.i(TAG, "keepalive watchdog launched via adb-ish channel (rc=${result.exitCode})")
            if (awaitRunning(context, 4_000L)) return true
            AppLogger.w(
                TAG,
                "keepalive 拉起后未检测到进程：ADB 通道大概率降级成了 app uid，" +
                    "脚本会以 'refuse to run as uid=...' 自退（见 keepalive.log）"
            )
            false
        } catch (e: Exception) {
            AppLogger.w(TAG, "keepalive launch failed: ${e.message}")
            false
        }
    }

    /** 两份脚本各有几个实例在跑。`null` = root 通道不可用，调用方需自行降级。 */
    private data class RunningCounts(val legacy: Int, val privateCopy: Int)

    /**
     * 用 root 通道查实例数。
     *
     * 为什么必须绕 root：`/proc` 是 `hidepid=2`，app 进程连 `/proc/<其它 uid 的 pid>`
     * 都看不见（真机 `mount | grep /proc` 确认）。只有 uid 0 能看全。
     */
    private suspend fun runningCounts(context: Context): RunningCounts? {
        val priv = scriptFile(context).absolutePath
        val cmd = "echo L=\$(pgrep -f '$LEGACY_SCRIPT_PATH' | wc -l) " +
            "P=\$(pgrep -f '$priv' | wc -l)"
        val out = SambaRootShell.runAsRoot(context, cmd) ?: return null
        val l = Regex("""L=(\d+)""").find(out)?.groupValues?.get(1)?.toIntOrNull()
        val p = Regex("""P=(\d+)""").find(out)?.groupValues?.get(1)?.toIntOrNull()
        return if (l != null && p != null) RunningCounts(l, p) else null
    }

    /**
     * 经 root socket（uid 0）拉起看门狗。
     *
     * ## 为什么需要这条路（2026-11-05 真机实测）
     *
     * 脚本开头有一道守卫：不是 uid 2000 或 0 就拒绝执行。而本机的 ADB 自连通道实测不生效，
     * `ShellExecutor.executeAsRoot` 全程走 fallback 到 **app uid**（10110），
     * 于是看门狗每次都写一行 `refuse to run as uid=10110` 然后退出 —— 它从来没真正跑起来过，
     * 而调用方只看到 `rc=0`，以为成功了。
     *
     * root socket 是 uid 0，守卫直接放行。它由 `SambaRootShell` 提供（smbd 的 root preexec），
     * 与 ADB 通道完全独立。
     *
     * @return true = 命令已送达且有回显（**不代表**进程一定起来了，见 [awaitRunning]）
     */
    private suspend fun launchViaRootSocket(context: Context): Boolean {
        if (!SambaRootShell.isSocketAvailable(context)) return false
        val out = SambaRootShell.runAsRoot(
            context,
            "nohup setsid sh '${scriptFile(context).absolutePath}' >/dev/null 2>&1 & echo UFI_KEEPALIVE_LAUNCHED",
            10_000
        ) ?: return false
        if (!out.contains("UFI_KEEPALIVE_LAUNCHED")) {
            AppLogger.w(TAG, "root socket 拉起命令无回显：${out.take(120)}")
            return false
        }
        AppLogger.i(TAG, "keepalive watchdog 由 root socket 拉起（uid=0）")
        return true
    }

    /**
     * 等**私有目录那份**真的起来。
     *
     * 同样走 root 通道（hidepid=2 下 app 看不见），没有 root 通道时退回 [isRunning]。
     */
    private suspend fun awaitRunning(context: Context, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val counts = runningCounts(context)
            if (counts != null) {
                if (counts.privateCopy > 0) return true
            } else if (isRunning()) {
                return true
            }
            delay(300L)
        }
        val finalCounts = runningCounts(context)
        return if (finalCounts != null) finalCounts.privateCopy > 0 else isRunning()
    }

    /**
     * 把脚本落到两个位置。
     *
     * ## 主落点：`filesDir/ufi_keepalive.sh`（root 通道用）
     *
     * 2026-11-05 变更。执行方是 **uid 0**（root socket），而 app 私有目录只有本 app 和 root
     * 能写 —— 于是"别人换掉脚本、我们以 root 执行"这条路被彻底堵死。之前脚本放在
     * `/data/local/tmp`（777），任何主体都能改，配上 root 执行就是一条提权捷径。
     *
     * ## 兼容落点：`/data/local/tmp/ufi_keepalive.sh`（ADB uid2000 通道用）
     *
     * uid 2000 读不到 app 私有目录，所以这条兜底路径必须保留一份。它**只在 root 通道
     * 不可用时才会被执行**，执行身份是 uid 2000（不是 root），风险与本次改动之前一致；
     * 而且 exec 前还有 [ensureDigestMatches] 那道摘要校验。
     *
     * 写盘一律「临时文件 + rename」原子替换：看门狗是一个正在增量读取该文件的 mksh 进程，
     * 原地截断重写会让它读到半截内容并崩掉（2026-10-13 真机复现过）。
     *
     * @return true = **主落点**写成功（兼容落点失败只记日志，不影响 root 通道）
     */
    private suspend fun writeScript(context: Context, template: String): Boolean {
        val primary = runCatching {
            val target = scriptFile(context)
            val tmp = File(target.parentFile, target.name + ".tmp")
            tmp.writeText(template)
            // 收紧到 owner-only：执行方是 root，不需要 world 权限。
            // （目录本身是 0700，但文件权限也一并收口，避免将来目录权限被放宽时漏出去）
            tmp.setReadable(true, true)
            tmp.setWritable(true, true)
            if (!tmp.renameTo(target)) {
                // rename 失败（跨挂载点等罕见情况）：退化为原地覆盖
                AppLogger.w(TAG, "keepalive 私有目录 rename 失败，改原地覆盖")
                target.writeText(template)
            }
            true
        }.getOrElse { e ->
            AppLogger.e(TAG, "keepalive 私有目录写入失败（root 通道将不可用）: ${e.message}")
            false
        }

        writeLegacyScript(template)
        return primary
    }

    /**
     * 写兼容落点 `/data/local/tmp/ufi_keepalive.sh`（ADB uid2000 通道兜底）。
     *
     * 两级落盘：
     *   1. 直接写（历史上 ADB shell 特权下可行，uid 2000 同组写）；
     *   2. 失败则走 [ShellExecutor.executeAsRoot]（ADB 优先，普通 shell 兜底）用
     *      `base64 -d` 写入 —— 模板经 base64 编码传输，规避引号/换行/EOF 标记注入问题。
     * 必须放开 world r/x：执行方是 uid 2000，只给执行位不给读位会 Permission denied
     * （sh 需要 open 文件）。
     */
    private suspend fun writeLegacyScript(template: String) {
        val direct = try {
            val tmp = File("$LEGACY_SCRIPT_PATH.tmp")
            tmp.writeText(template)
            tmp.setReadable(true, false)
            tmp.setExecutable(true, false)
            if (!tmp.renameTo(File(LEGACY_SCRIPT_PATH))) {
                AppLogger.w(TAG, "keepalive legacy rename failed, falling back to in-place write")
                val f = File(LEGACY_SCRIPT_PATH)
                f.writeText(template)
                f.setReadable(true, false)
                f.setExecutable(true, false)
            }
            true
        } catch (e: Exception) {
            AppLogger.w(TAG, "keepalive legacy direct write failed (will try shell): ${e.message}")
            false
        }
        if (direct) return

        try {
            val b64 = android.util.Base64.encodeToString(
                template.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP
            )
            val result = ShellExecutor.executeAsRoot(
                "echo '$b64' | base64 -d > $LEGACY_SCRIPT_PATH.tmp && chmod 755 $LEGACY_SCRIPT_PATH.tmp " +
                    "&& mv -f $LEGACY_SCRIPT_PATH.tmp $LEGACY_SCRIPT_PATH",
                10_000L
            )
            if (result.isSuccess) {
                AppLogger.i(TAG, "keepalive script written via shell fallback")
            } else {
                // 无 root / ADB 未就绪时这是**预期结果**，不是故障：降级到 WARN，
                // 否则每 5 分钟自愈都会往 error.log 塞一条噪音。
                AppLogger.w(
                    TAG,
                    "keepalive legacy write failed（兼容落点，属预期降级）: " +
                        "rc=${result.exitCode} ${result.stderr.take(120)}"
                )
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "keepalive shell write exception: ${e.message}")
        }
    }

    /**
     * exec 前复核 [LEGACY_SCRIPT_PATH] 的内容摘要（纵深防御，2026-09-05 P3）。
     *
     * ## 要防的是什么
     * 脚本落在 `/data/local/tmp` 且放开了 world r/x，写盘与 exec 之间（以及两次
     * [SELF_HEAL_INTERVAL_MS] 自愈之间）存在一个窗口：若这期间文件被换掉，
     * 我们就会以 adb shell（uid 2000）的身份去执行别人的内容。
     *
     * ## 为什么这是"纵深防御"而不是"修漏洞"
     * 评审结论是**理论风险**：该目录由 SELinux 域限制，能往里写的主体很少
     *（本 app 之所以能写，靠的是 Core 自己那套 ADB/shell 特权通道），
     * 而真正能改写它的主体本身就已具备任意执行能力，绕过这道校验易如反掌。
     * 所以这里的价值是**把静默篡改变成一条日志 + 一次自我修复**，不是安全边界。
     *
     * ## 为什么不改成落 `context.filesDir`
     * 执行方是 uid 2000 的 adb shell，读不到 app 私有目录 —— 换过去会直接把看门狗打死
     * （见本文件顶部「权限模型」注释）。落点必须留在 `/data/local/tmp`。
     *
     * ## 校验失败时怎么办
     * 先重写一次再复核；仍不匹配才放弃 exec（说明有人在持续按住这个文件，
     * 此时执行它比不执行更危险）。而**取不到摘要**时 fail-open 放行并只记一条 warn：
     * 看门狗是"Core 别死"的最后一道保险，不能因为 `sha256sum` 在某台设备上不可用
     * （toybox 裁剪 / 通道未就绪）就把主功能一起停掉。
     *
     * @return true = 可以继续 exec
     */
    private suspend fun ensureDigestMatches(expectedDigest: String, template: String): Boolean {
        val onDisk = readDiskDigest()
        if (onDisk == null) {
            AppLogger.w(TAG, "keepalive digest check skipped (sha256sum unavailable), launching anyway")
            return true
        }
        if (onDisk == expectedDigest) return true

        AppLogger.w(TAG, "keepalive script digest mismatch (disk=$onDisk expected=$expectedDigest), rewriting")
        writeLegacyScript(template)

        val afterRewrite = readDiskDigest()
        if (afterRewrite == null) {
            AppLogger.w(TAG, "keepalive digest recheck unavailable after rewrite, launching anyway")
            return true
        }
        if (afterRewrite == expectedDigest) return true

        AppLogger.e(TAG, "keepalive script still tampered after rewrite (disk=$afterRewrite), refusing to exec")
        return false
    }

    /**
     * 读 shell 侧看到的 [LEGACY_SCRIPT_PATH] 摘要。
     *
     * 刻意走 shell 而不是在 app 里再 read 一遍文件：要校验的正是**执行方看到的那份字节**，
     * app 侧自己读一遍只能证明"我刚写的还在"，证明不了 uid 2000 打开它时读到的是什么。
     *
     * @return 64 位小写 hex；命令失败 / 输出不是合法摘要时返回 null（调用方按 fail-open 处理）。
     */
    private suspend fun readDiskDigest(): String? {
        val result = runCatching {
            ShellExecutor.executeAsRoot("sha256sum $LEGACY_SCRIPT_PATH 2>/dev/null", 5_000L)
        }.getOrNull() ?: return null
        if (!result.isSuccess) return null
        // sha256sum 输出形如 `<hex>  <path>`，取第一段。
        val token = result.stdout.trim().substringBefore(' ').lowercase()
        return token.takeIf { it.length == SHA256_HEX_LENGTH && it.all { c -> c in HEX_DIGITS } }
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
}
