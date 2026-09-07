package com.ufi_axis_core.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.ufi_axis_core.controller.system.AppManager
import com.ufi_axis_core.util.AdbShellExecutor
import com.ufi_axis_core.util.AppLogger
import com.ufi_axis_core.util.AppSettings
import com.ufi_axis_core.util.AssetExtractor
import com.ufi_axis_core.util.ShellExecutor
import kotlinx.coroutines.*
import java.io.File

/**
 * 开机广播接收器
 *
 * 职责：
 * 1. 独立部署并启动 keepalive 看门狗（不依赖 BackendService，解决鸡生蛋问题）
 * 2. 根据配置决定是否自动启动 BackendService
 *
 * 保活部署链路（修复鸡生蛋）：
 *   Boot → BootReceiver.onReceive()
 *     → deployAndLaunchKeepalive()    ← 直接从 assets 部署，无需 BackendService
 *     → BackendService.start()        ← 然后才启动 Core
 *   即使 BackendService 启动崩溃，keepalive 已独立运行，可重新拉起 Core。
 */
class BootReceiver : BroadcastReceiver() {

    private val tag = "BootReceiver"

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED -> {
                val settings = AppSettings.getInstance(context)
                if (settings.autoStartOnBoot) {
                    AppLogger.i(tag, "Boot completed (${intent.action}), deploying keepalive + starting Core...")

                    // ① 先独立部署 keepalive 看门狗（不依赖 BackendService）
                    val pendingResult = goAsync()
                    CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
                        // 启动前先拉起 ADB 自连接特权通道（应用管理 / 高级控制台 共用的公共服务）。
                        // 否则 executeAsRoot 会因 AdbShellExecutor 尚未初始化而回退 app uid，
                        // 本文件原 su -c 路径也会整体失效。拉起后即统一走 ADB shell (uid 2000)，
                        // 彻底去掉 su 依赖；带重试以容忍 BOOT_COMPLETED 时 adbd 尚未监听 5555。
                        try {
                            ensureAdbReadyAtBoot(context.applicationContext)
                        } catch (e: Exception) {
                            AppLogger.w(tag, "ADB bootstrap at boot failed (non-fatal): ${e.message}")
                        }
                        try {
                            // ① 检查是否有 pending install（Core 在 PackageInstaller commit 时被杀的情况）
                            recoverPendingInstall(context)
                        } catch (e: Exception) {
                            AppLogger.e(tag, "Pending install recovery failed: ${e.message}")
                        }

                        try {
                            deployAndLaunchKeepalive(context)
                        } catch (e: Exception) {
                            AppLogger.e(tag, "Keepalive deploy failed (non-fatal): ${e.message}")
                        }

                        // ② 然后启动 BackendService
                        try {
                            BackendService.start(context)
                            AppLogger.i(tag, "BackendService start requested")
                        } catch (e: Exception) {
                            AppLogger.e(tag, "BackendService start failed: ${e.message}")
                        }

                        pendingResult.finish()
                    }
                } else {
                    AppLogger.i(tag, "Boot completed, auto-start disabled")
                }
            }
        }
    }

    /**
     * 开机时提前拉起 ADB 自连接特权通道（复用 ShellExecutor.executeAsRoot 的公共服务，替代原 su -c）。
     * - 必要时补提取 adb 二进制（首次开机尚未由 BackendService 提取时）；
     * - 重试 adb connect localhost:5555，容忍 BOOT_COMPLETED 瞬间 adbd 尚未监听 5555。
     * 不抛异常：失败则 executeAsRoot 回退 app uid（与无 su 时原 fallback 行为一致），不阻断启动。
     */
    private suspend fun ensureAdbReadyAtBoot(ctx: Context) {
        val adbPath = AssetExtractor.getPath(ctx, "adb")
        if (!AssetExtractor.isExtracted(ctx, "adb")) {
            AppLogger.i(tag, "Extracting shell assets (adb) at boot...")
            AssetExtractor.extractAll(ctx, "shell")
        }
        repeat(3) { attempt ->
            if (AdbShellExecutor.init(adbPath, ctx)) {
                AppLogger.i(tag, "ADB shell ready at boot (attempt ${attempt + 1})")
                return
            }
            AppLogger.w(tag, "ADB connect at boot failed (attempt ${attempt + 1}), retrying in 2s...")
            delay(2000L)
        }
        AppLogger.w(tag, "ADB shell not available at boot after retries (executeAsRoot will fall back to app uid)")
    }

    /**
     * 从 assets 部署 keepalive 脚本到 `/data/local/tmp/` 并启动（幂等）。
     *
     * 具体实现见 [KeepAliveWatchdog]。2026-09-04 抽出去的原因：这里与
     * `BackendService.ensureKeepAliveWatchdog()` 各写了一份判定，而两份都把
     * 「锁目录存在」当成「看门狗在跑」—— 锁在 SIGKILL / 掉电后会残留，
     * 于是看门狗一旦被杀就再也起不来。判定逻辑只留一份，见 [KeepAliveWatchdog.isRunning]。
     */
    private suspend fun deployAndLaunchKeepalive(context: Context) {
        KeepAliveWatchdog.ensureRunning(context)
    }

    /**
     * 恢复 pending install（2026-08-21）。
     *
     * 场景：PackageInstaller.commit() 会杀掉 Core 进程（自更新），导致：
     *   - 后台线程的 launchUpdateScript() 未执行
     *   - 前端永远卡在 installing
     *
     * 解决：安装前保存参数到 /data/local/tmp/ufi_pending_install.txt，
     * BootReceiver 开机时检查此文件，存在则执行更新脚本。
     *
     * 文件格式（4行）：
     *   <apk_path>
     *   <target_ver>
     *   <apk_sha256>
     *   <mode>  (v6.3 起恒为 2=watchdog；老 pending 的 0/1 也强制按 2 处理)
     */
    private suspend fun recoverPendingInstall(context: Context) {
        val pendingFile = File("/data/local/tmp/ufi_pending_install.txt")
        if (!pendingFile.exists()) return

        AppLogger.i(tag, "Found pending install file, recovering...")
        val lines = try {
            pendingFile.readLines().filter { it.isNotBlank() }
        } catch (e: Exception) {
            AppLogger.e(tag, "Failed to read pending install file: ${e.message}")
            return
        }

        if (lines.size < 3) {
            AppLogger.w(tag, "Pending install file incomplete (${lines.size} lines)")
            pendingFile.delete()
            return
        }

        val apkPath = lines[0]
        val targetVer = lines[1]
        val apkSha = lines[2]
        val pendingMode = if (lines.size > 3) lines[3] else "2"

        // 【安全】pending 文件位于 /data/local/tmp（世界可写），其内容属于**不可信输入**：
        // 任何本机进程都能改写它，而 apkPath 会被插值进下面那条特权 shell 命令的单引号里
        // （`sh $scriptPath '$apkPath' ...`）——一个 `'` 就能闭合引号追加任意命令。
        // 因此消费前逐字段校验，任何一项不合法就丢弃整个 pending（删文件后返回），不做"尽力而为"。
        if (!isSafeShellToken(apkPath) || !apkPath.endsWith(".apk")) {
            AppLogger.e(tag, "Pending install rejected: unsafe apk path")
            pendingFile.delete()
            return
        }
        val apkFile = File(apkPath)
        if (!apkFile.isFile) {
            AppLogger.e(tag, "Pending install rejected: apk path is not an existing regular file")
            pendingFile.delete()
            return
        }
        if (!isSafeShellToken(targetVer) || !apkSha.matches(SHA256_PATTERN)) {
            AppLogger.e(tag, "Pending install rejected: unsafe version/sha field")
            pendingFile.delete()
            return
        }

        AppLogger.i(tag, "Recovering: apk=$apkPath target=$targetVer pendingMode=$pendingMode (forced mode=2)")

        // 读取 update script 模板
        val scriptTemplate = try {
            context.assets.open("shell/ufi_update.sh").bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            AppLogger.e(tag, "Update script asset not found: ${e.message}")
            return
        }

        if (scriptTemplate.isBlank()) {
            AppLogger.w(tag, "Update script asset is empty")
            return
        }

        // 写入脚本到 /data/local/tmp/（in-app 直接写，替代原 su -c cat >，彻底去掉 su 依赖）
        val scriptPath = "/data/local/tmp/ufi_update.sh"
        // 清理 stale lock（开机恢复场景，旧锁一定是上次异常残留）；直接 in-app 删除（/data/local/tmp 世界可写）
        File("/data/local/tmp/ufi_update.lock").deleteRecursively()
        File("/data/local/tmp/ufi_install_result.txt").delete()
        val scriptFile = File(scriptPath)
        val writeOk = try {
            scriptFile.writeText(scriptTemplate)
            scriptFile.setReadable(true, false)
            scriptFile.setExecutable(true, false)
            AppLogger.i(tag, "Update script written via app (no su)")
            true
        } catch (e: Exception) {
            AppLogger.e(tag, "Update script write failed: ${e.message}")
            false
        }
        if (!writeOk) {
            AppLogger.e(tag, "Failed to write update script")
            return
        }

        // 执行脚本
        // v6.3：脚本仅保留 mode=2 watchdog。无论 pending 第4行是 0/1/2，均强制按 mode=2 处理
        // （mode=2 纯 watchdog 不安装，仅等待安装完成；本函数 ② 已主动重新触发安装）。
        // watchdog 在 150s 未见 _TRIGGERED 标志且无结果文件时早退（ENV_FAIL:install_not_triggered），
        // 完整等待上限 300s，超时判 INSTALL_FAILED:watchdog_timeout 并清理 pending。
        // mode=2 需要第 5 参 baseline（安装前 versionName:versionCode），开机恢复时自行计算
        val mode = "2"
        val baseline = try {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            "${info.versionName ?: "unknown"}:${info.versionCode}"
        } catch (e: Exception) {
            AppLogger.w(tag, "recoverPendingInstall: baseline 计算失败: ${e.message}")
            "0"
        }
        // ① 写「安装已触发」标志（/data/local/tmp/ufi_install_triggered）。
        //   实时流程中该标志由 UpdateManager.launchUpdateScript 写入；开机恢复路径此前漏写，
        //   导致 watchdog 在 150s 未见标志、且无结果文件时早退（ENV_FAIL:install_not_triggered），
        //   安装从未真正发起。此处补齐，确保 watchdog 全程等待（最长 300s）而非早退。
        try {
            java.io.File("/data/local/tmp/ufi_install_triggered").writeText("boot_recovery:${System.currentTimeMillis()}")
            AppLogger.i(tag, "install_triggered flag written (boot recovery)")
        } catch (e: Exception) {
            AppLogger.w(tag, "write install_triggered flag failed (non-fatal): ${e.message}")
        }

        // ② 主动重新触发安装（此前开机恢复只拉起 watchdog、不触发安装，是设计缺口）。
        //   与实时流程对齐：watchdog 仅等待安装完成→重启 Core→写 RESULT；真正执行安装的是
        //   AppManager.installApk（主通道 adb connect localhost:5555 && adb install -r；
        //   adb 不可用时自动回退 PackageInstaller API），自更新传 isSelfUpdate=true。
        //   用独立 CoroutineScope 异步触发，不阻塞后续 BackendService.start 的启动；
        //   若安装经 PackageInstaller commit 杀掉进程，已 nohup setsid 分离的 watchdog 会负责重启 Core。
        CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            try {
                val appManager = AppManager(context.applicationContext)
                val res = appManager.installApk(apkPath, isSelfUpdate = true)
                AppLogger.i(tag, "Boot recovery install result: success=${res.success} (${res.message})")
            } catch (e: Exception) {
                AppLogger.e(tag, "Boot recovery install failed: ${e.message}")
            }
        }

        // 启动 watchdog：走 ADB shell (uid 2000) 公共服务，无 su。
        // 经 AdbShellExecutor 自连接 localhost:5555 执行；adbd 不可用时 executeAsRoot 回退 app uid
        // （与原 sh fallback 行为一致），不阻断启动。
        //
        // 【安全】脚本放在 /data/local/tmp 是必需的（app 私有目录 0700，uid 2000 的 shell 读不到），
        // 代价是「写入 → 执行」之间存在被替换的窗口（TOCTOU）。执行前再比对一次内容：
        // 不一致说明有人动过，直接重写回模板，把窗口压到微秒级。
        if (runCatching { scriptFile.readText() }.getOrNull() != scriptTemplate) {
            AppLogger.w(tag, "Update script content changed after write, rewriting before launch")
            runCatching {
                scriptFile.writeText(scriptTemplate)
                scriptFile.setReadable(true, false)
                scriptFile.setExecutable(true, false)
            }
        }
        val runCmd = "nohup setsid sh $scriptPath '$apkPath' '$targetVer' '$apkSha' '$mode' '$baseline' >/dev/null 2>&1 &"
        try {
            val result = ShellExecutor.executeAsRoot(runCmd, 10_000L)
            AppLogger.i(tag, "Update script launched via executeAsRoot (rc=${result.exitCode})")
        } catch (e: Exception) {
            AppLogger.e(tag, "Failed to launch update script: ${e.message}")
        }

        // 清理 pending 文件
        pendingFile.delete()
        AppLogger.i(tag, "Pending install recovery complete")
    }

    /**
     * 判定一个来自不可信来源的字段能否安全地插值进 shell 命令。
     *
     * 允许空格（APK 文件名常含空格），拒绝一切能改变命令结构的字符——尤其是**引号**：
     * 调用处把参数包在单引号里，`'` 可闭合引号后追加任意命令。
     */
    private fun isSafeShellToken(value: String): Boolean {
        if (value.isBlank()) return false
        if (value.any { it == '\n' || it == '\r' || it == '\u0000' }) return false
        return !UNSAFE_SHELL_CHARS.containsMatchIn(value)
    }

    private companion object {
        private val UNSAFE_SHELL_CHARS = Regex("""["'$`;|&<>(){}\\*?]""")
        private val SHA256_PATTERN = Regex("^[a-fA-F0-9]{64}$")
    }
}
