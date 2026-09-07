package com.ufi_axis_core.util

import android.content.Context
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 自带原生二进制的「能不能 exec」自检（2026-09-06）。
 *
 * 背景：`assets/shell/` 里的 aria2c / ttyd / socat / jq / curl / adb 被 [AssetExtractor] 释放到
 * `filesDir/shell/`，运行时靠 `ProcessBuilder` 直接执行。该目录下文件的 SELinux 标签是
 * `app_data_file`，而自 Android 10 起 `untrusted_app` 域对它的 `execute_no_trans` 已被 neverallow
 * 掉 —— 也就是说**只有 SELinux 处于 permissive 的设备才跑得起来**（目标 UFI 机型出厂多为
 * permissive，日志里能看到 `avc: denied { execute_no_trans } ... permissive=1`）。
 *
 * 换到 enforcing 设备上，`ProcessBuilder.start()` 会抛 IOException(EACCES)，而调用方大多是
 * `catch (_: Exception) {}`，最终只表现为"下载不动、终端打不开"，排查得靠翻 avc 日志。
 * 这里在启动期做一次显式探测并缓存，由 `GET /api/service/status` 的 `native_exec` 段暴露出来。
 *
 * 注意：探测的是"能否执行 filesDir 下的文件"这一件事，与 root/ADB 通道无关 ——
 * 走 `ShellExecutor.executeAsRoot` 的路径不受此限制。
 */
object NativeExecProbe {

    private const val TAG = "NativeExecProbe"

    /** 探针取 assets/shell 里体积最小、加个参数就立刻退出的那个二进制 */
    private const val PROBE_BINARY = "jq"

    /** selinuxfs 的开关文件：1=enforcing，0=permissive；读不到就是 unknown */
    private const val SELINUX_ENFORCE_PATH = "/sys/fs/selinux/enforce"

    /**
     * @param executable filesDir 下的二进制能否 exec（进程起得来即为 true，退出码不参与判定）
     * @param selinux `enforcing` / `permissive` / `unknown`
     * @param detail 给人看的一句话，失败时带上原始异常信息
     */
    data class Result(
        val executable: Boolean,
        val selinux: String,
        val detail: String
    )

    @Volatile
    private var cached: Result? = null

    /** 已探测过则返回结果，否则 null（未跑过自检，调用方按"未知"处理，不要在此触发 exec） */
    fun cachedResult(): Result? = cached

    /** 探测并缓存；[force] 为 true 时忽略缓存重跑（覆盖安装后二进制会重新释放） */
    fun probe(context: Context, force: Boolean = false): Result {
        cached?.let { if (!force) return it }
        val selinux = readSelinuxMode()
        val probe = File(AssetExtractor.getPath(context, PROBE_BINARY))
        val result = when {
            !probe.exists() -> Result(
                executable = false,
                selinux = selinux,
                detail = "探针二进制未释放：${probe.absolutePath}（assets 提取失败？）"
            )
            else -> runExecProbe(probe, selinux)
        }
        cached = result
        if (result.executable) {
            AppLogger.i(TAG, "native exec OK (selinux=$selinux)")
        } else {
            AppLogger.w(TAG, "native exec 不可用: ${result.detail}")
        }
        return result
    }

    private fun runExecProbe(probe: File, selinux: String): Result {
        return try {
            val p = ProcessBuilder(probe.absolutePath, "--version")
                .redirectErrorStream(true)
                .start()
            // 输出必须读掉：管道写满会让子进程阻塞在 write 上，waitFor 永不返回
            val out = p.inputStream.bufferedReader().use { it.readText() }.trim()
            if (!p.waitFor(5, TimeUnit.SECONDS)) p.destroyForcibly()
            Result(
                executable = true,
                selinux = selinux,
                detail = "exec 正常（$PROBE_BINARY: ${out.take(64).ifBlank { "无输出" }}）"
            )
        } catch (e: Exception) {
            // enforcing 下这里是 IOException: ... error=13, Permission denied
            Result(
                executable = false,
                selinux = selinux,
                detail = buildString {
                    append("无法执行 ${probe.absolutePath}：${e.message}")
                    if (selinux == "enforcing") {
                        append("；设备 SELinux 为 enforcing，filesDir 下的二进制被内核拦截，")
                        append("下载引擎 / 网页终端 / Samba 等依赖自带二进制的功能不可用")
                    }
                }
            )
        }
    }

    private fun readSelinuxMode(): String = runCatching {
        when (File(SELINUX_ENFORCE_PATH).readText().trim()) {
            "1" -> "enforcing"
            "0" -> "permissive"
            else -> "unknown"
        }
    }.getOrDefault("unknown")
}
