package com.ufi_axis_core.service

import android.content.Context
import com.ufi_axis_core.util.AppLogger
import com.ufi_axis_core.util.ShellExecutor
import java.io.File
import java.security.MessageDigest

/**
 * shell 看门狗（`assets/shell/ufi_keepalive.sh`）的部署与存活判定（2026-09-04 抽出）。
 *
 * 为什么要单独一个对象：部署点有两个 —— [BootReceiver]（开机，先于 Core 起来，解决鸡生蛋）
 * 与 [BackendService]（ADB 通道就绪后补一次 + 定期自愈）。两边原本各写一份「锁存在就跳过」
 * 的判定，而那条判定本身是错的（见 [isRunning]），改一处修不干净。
 *
 * 权限模型：脚本放 `/data/local/tmp`（世界可写），app 直接写文件 + 放开 world r/x，
 * 由 `ShellExecutor.executeAsRoot`（实际是 ADB shell uid 2000）执行。全程不需要 su。
 */
object KeepAliveWatchdog {

    private const val TAG = "KeepAliveWatchdog"

    const val SCRIPT_PATH = "/data/local/tmp/ufi_keepalive.sh"
    const val LOCK_PATH = "/data/local/tmp/ufi_keepalive.lock"
    private const val PID_PATH = "$LOCK_PATH/pid"

    private const val SHA256_HEX_LENGTH = 64
    private const val HEX_DIGITS = "0123456789abcdef"

    /** 定期自愈的间隔：看门狗被 LMK / phantom process killer 带走后最迟这么久被重新拉起。 */
    const val SELF_HEAL_INTERVAL_MS = 5 * 60 * 1000L

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
     * 幂等：把脚本写到 [SCRIPT_PATH]，没在跑就拉起来。
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

        if (!writeScript(template)) return false

        if (isRunning()) return false

        // ── 纵深防御：exec 前复核落盘内容 ──
        if (!ensureDigestMatches(expectedDigest, template)) return false

        return try {
            // setsid + nohup：与调用方进程脱钩，Core 被整体回收时它还在
            val result = ShellExecutor.executeAsRoot(
                "nohup setsid sh $SCRIPT_PATH >/dev/null 2>&1 &", 10_000L
            )
            AppLogger.i(TAG, "keepalive watchdog launched (rc=${result.exitCode})")
            true
        } catch (e: Exception) {
            AppLogger.w(TAG, "keepalive launch failed: ${e.message}")
            false
        }
    }

    /** 把 assets 里的脚本覆盖写到 [SCRIPT_PATH] 并放开 world r/x。 */
    private fun writeScript(template: String): Boolean = try {
        val scriptFile = File(SCRIPT_PATH)
        scriptFile.writeText(template)
        // 必须 world-readable + executable：执行方是 adb shell（uid 2000），
        // 只给执行位不给读位会 Permission denied（sh 需要 open 文件）。
        scriptFile.setReadable(true, false)
        scriptFile.setExecutable(true, false)
        true
    } catch (e: Exception) {
        AppLogger.e(TAG, "keepalive script write failed: ${e.message}")
        false
    }

    /**
     * exec 前复核 [SCRIPT_PATH] 的内容摘要（纵深防御，2026-09-05 P3）。
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
        if (!writeScript(template)) return false

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
     * 读 shell 侧看到的 [SCRIPT_PATH] 摘要。
     *
     * 刻意走 shell 而不是在 app 里再 read 一遍文件：要校验的正是**执行方看到的那份字节**，
     * app 侧自己读一遍只能证明"我刚写的还在"，证明不了 uid 2000 打开它时读到的是什么。
     *
     * @return 64 位小写 hex；命令失败 / 输出不是合法摘要时返回 null（调用方按 fail-open 处理）。
     */
    private suspend fun readDiskDigest(): String? {
        val result = runCatching {
            ShellExecutor.executeAsRoot("sha256sum $SCRIPT_PATH 2>/dev/null", 5_000L)
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
