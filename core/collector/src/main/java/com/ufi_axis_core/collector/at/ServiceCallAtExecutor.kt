package com.ufi_axis_core.collector.at

import android.os.Build
import com.ufi_axis_core.util.AppLogger
import com.ufi_axis_core.util.decodeServiceCallText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * AT 通道 —— 直接调 `/system/bin/service call`，不再依赖外部 `sendat` 二进制。
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
 */
class ServiceCallAtExecutor(
    private val sdkInt: Int = Build.VERSION.SDK_INT
) : AtTransport {

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
            process.inputStream.bufferedReader().readText()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.e(tag, "执行失败: ${argv.firstOrNull()}", e)
            null
        } finally {
            process?.destroyForcibly()
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
    }
}
