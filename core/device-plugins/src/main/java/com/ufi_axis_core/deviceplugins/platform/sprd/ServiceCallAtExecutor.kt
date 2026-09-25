package com.ufi_axis_core.deviceplugins.platform.sprd

import android.os.Build
import com.ufi_axis_core.devicespi.AtTransport
import com.ufi_axis_core.util.AppLogger
import com.ufi_axis_core.util.decodeServiceCallText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * AT 通道 —— 直接调 `/system/bin/service call`，不再依赖外部 `sendat` 二进制。
 *
 * 2026-09-24（阶段 4 批 F）从 `:core:collector` 的 `at/` 包**整体搬到这里**：
 * 它是**展锐平台的设备知识**（服务名、事务码、API 等级分档全是展锐 HAL 的事实），
 * 不是我们的策略。策略层 `ATChannel`（全局互斥、500ms 最小间隔、指数退避、20 次熔断）
 * **留在 collector**，它现在通过注入拿到本类的实例，不再自己 `new`。
 * 搬迁时**一行逻辑都没改**，`name` 取值尤其没动（见下）。
 *
 * ## 为什么自己写
 * 原先打包的 `sendat` 是 UFI-TOOLS 的 Go 二进制（UPX 压缩），它内部做的事就是
 * `sh -c "/system/bin/service call ..."` 再把 hex dump 解码成文本 —— 没有任何我们做不到的东西。
 * 而它带来两个实际问题：
 * 1. **许可不清**：上游仓库没有 LICENSE，打进公开分发的 APK 缺依据（见
 *    `core/src/main/assets/shell/THIRD-PARTY-NOTICES.md`）；
 * 2. 多一层 700KB 二进制 + 一次资源释放 + 一次进程 exec。
 *
 * 这里是**功能等价的独立实现**：调用约定（服务名、事务码、参数顺序）来自 Android 平台既定的
 * `service` 命令用法与展锐 HAL 接口定义，hex→UTF-16LE 解码见 [decodeServiceCallText]。
 *
 * ## 两套接口按 API 等级切换
 * 展锐把这个能力挪过窝，所以要分档（等级判据用 [Build.VERSION.SDK_INT]，不必再 fork 一次
 * `getprop` 去问系统）：
 * - **API > 33（Android 14+）**：`vendor.sprd.hardware.tool.IToolControl/default`
 *   事务码 3 = `sendAtCmd(int phoneId, String cmd)`；
 * - **API ≤ 33（Android 13-）**：`vendor.sprd.hardware.log.ILogControl/default`
 *   事务码 1，第一个参数固定 `miscserver`，第二个参数是 `sendAt <slot> <cmd>` 这种拼接串。
 *
 * ## 为什么不经 `sh -c`
 * 直接把 argv 交给 [ProcessBuilder]：AT 指令来自 web / app 的输入框，一旦拼进 shell 字符串，
 * 引号与 `;`、`$()` 就成了注入面。argv 形式下这些字符只是普通字节。
 *
 * ## 为什么仍然是"起独立进程"
 * `ATChannel` 的历史注释记着：在 app 进程里直连这套 HAL 会撞 `sprd_ipc_probe` 驱动竞态，
 * 要 30s 防抖；改成独立进程后问题消失。这里执行的 `service` 命令**本身就是独立进程**，
 * 与 sendat 的差别只是少了一层 Go wrapper（sendat 自己也是 fork `service`），竞态特性一致。
 *
 * ## 登记：[sdkInt] 这个默认参数是本类唯一的 Android 依赖（本批刻意不改）
 * 阶段 4 开工前的盘点建议把它改成从 `ProbeEnv.androidBuild.sdkInt` 取 ——
 * 那样本类就成了纯 JVM 可测的类，`buildArgv()` 的两套分档终于能写单测。
 * **本批不动**：`ProbeEnv` 的采集实现要到阶段 5.1 才有，现在改会把 probe 链路一起牵动，
 * 而本批的判据是「搬迁 + 定义，不改行为」。这条机会登记在此，做 5.1 时一并处理。
 */
class ServiceCallAtExecutor(
    private val sdkInt: Int = Build.VERSION.SDK_INT
) : AtTransport {

    /**
     * ⚠ **对外可见的字符串，不许改**：它是 `/api/at/status` 与 `/api/at/platform` 的
     * `method` 字段（`ATChannel.getPlatformInfo()` 直接取 `transport?.name`），
     * app 侧 `getPlatformInfo()` 在消费。当前全仓只有本类一个 [AtTransport] 实现，
     * 所以该字段恒为 `"service-call"`。
     */
    override val name: String = "service-call"

    private val tag = "ServiceCallAt"

    /** 目标服务名：随 API 等级切换，见类注释。 */
    private val serviceName: String
        get() = if (sdkInt > SDK_TOOL_CONTROL_MIN) TOOL_CONTROL_SERVICE else LOG_CONTROL_SERVICE

    /**
     * 探测：`service check <name>` —— 只问服务在不在，不触碰 modem。
     *
     * 输出形如 `Service vendor.sprd...: found` / `... : not found`。
     * 必须先判 "not found"：它把 "found" 也包含在内，顺序写反就恒为可用。
     */
    override suspend fun probe(): Boolean = withContext(Dispatchers.IO) {
        val out = runCommand(listOf(SERVICE_BIN, "check", serviceName), PROBE_TIMEOUT_MS)
        if (out == null) {
            AppLogger.w(tag, "service check 无输出，判定 AT 通道不可用")
            return@withContext false
        }
        val available = !out.contains("not found") && out.contains("found")
        AppLogger.i(tag, "probe($serviceName) => $available (sdk=$sdkInt)")
        available
    }

    override suspend fun sendCommand(
        command: String,
        slot: Int,
        timeoutMs: Long
    ): String? = withContext(Dispatchers.IO) {
        val argv = buildArgv(command, slot)
        val raw = runCommand(argv, timeoutMs) ?: return@withContext null

        // 服务不存在 / 事务码不对时 `service` 不会用退出码报错，只在 stdout 里说明，必须显式识别。
        if (raw.contains("does not exist") || raw.contains("Unknown")) {
            AppLogger.w(tag, "service call 被拒: ${raw.trim().take(120)}")
            return@withContext null
        }

        val text = decodeServiceCallText(raw)
        if (text.isBlank()) {
            AppLogger.w(tag, "service call 无可解码内容: ${raw.trim().take(120)}")
            return@withContext null
        }

        AppLogger.at("TX", command)
        AppLogger.at("RX", command, text)
        text
    }

    /** 无内部状态，没有要清的东西；接口要求实现，故留空。 */
    override fun reset() = Unit

    private fun buildArgv(command: String, slot: Int): List<String> =
        if (sdkInt > SDK_TOOL_CONTROL_MIN) {
            listOf(
                SERVICE_BIN, "call", TOOL_CONTROL_SERVICE, TOOL_CONTROL_CODE.toString(),
                "i32", slot.toString(),
                "s16", command
            )
        } else {
            listOf(
                SERVICE_BIN, "call", LOG_CONTROL_SERVICE, LOG_CONTROL_CODE.toString(),
                "s16", LOG_CONTROL_TARGET,
                "s16", "sendAt $slot $command"
            )
        }

    /**
     * 起进程、等超时、读输出。
     *
     * 顺序刻意是"先 waitFor 再读"：先读到 EOF 的写法会在子进程挂住时
     * 无限阻塞，超时参数形同虚设。`service` 的输出只有几十字节，远小于管道缓冲区，
     * 不存在"进程因为没人读而写不下去"的风险。
     */
    private fun runCommand(argv: List<String>, timeoutMs: Long): String? {
        var process: Process? = null
        return try {
            process = ProcessBuilder(argv).redirectErrorStream(true).start()
            val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!finished) {
                AppLogger.w(tag, "超时 ${timeoutMs}ms: ${argv.joinToString(" ")}")
                return null
            }
            process.inputStream.bufferedReader().use { it.readText() }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.e(tag, "执行失败: ${argv.firstOrNull()}", e)
            null
        } finally {
            process?.let {
                it.destroyForcibly()
                // 强杀是异步的，短等一下让 fd 有确定的回收时机；等不到就放手，不拖住调用方
                try {
                    it.waitFor(KILL_WAIT_MS, TimeUnit.MILLISECONDS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
        }
    }

    companion object {
        private const val SERVICE_BIN = "/system/bin/service"

        /** > 33 才有 IToolControl（Android 14 起）。 */
        private const val SDK_TOOL_CONTROL_MIN = 33

        private const val TOOL_CONTROL_SERVICE = "vendor.sprd.hardware.tool.IToolControl/default"

        /** `sendAtCmd(int phoneId, String cmd)` 在 IToolControl 里的事务码。 */
        private const val TOOL_CONTROL_CODE = 3

        private const val LOG_CONTROL_SERVICE = "vendor.sprd.hardware.log.ILogControl/default"
        private const val LOG_CONTROL_CODE = 1

        /** 旧接口的固定第一参数（转发目标进程名）。 */
        private const val LOG_CONTROL_TARGET = "miscserver"

        /** 探测只是问 servicemanager 要一次列表，1s 足够；卡住就当不可用。 */
        private const val PROBE_TIMEOUT_MS = 1_000L

        /** destroyForcibly() 之后等子进程真正收尸的上限。 */
        private const val KILL_WAIT_MS = 200L
    }
}
