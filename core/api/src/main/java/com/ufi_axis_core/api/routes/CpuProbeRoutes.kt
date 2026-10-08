package com.ufi_axis_core.api.routes

import com.ufi_axis_core.util.ShellExecutor
import com.ufi_axis_core.util.ShellQoS
import io.ktor.http.ContentType
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

/**
 * CPU 控制能力探测（2026-10-08，为「处理器管理/智能调度」功能做的真机前置勘察）。
 *
 * 为什么单独一个端点而不是让 app 通过 /api/shell/exec 逐条发命令：
 * - 探测要发 6~10 条命令且互相有先后语义（先 ls 看节点在不在、再试写、再读回验证），
 *   app 逐条 HTTP 往返既慢又会把中间态暴露给 UI；
 * - 结果直接在 core 侧组装成结构化 JSON，app 只负责渲染；
 * - 全部命令**只读或写回原值**（试写 scaling_max_freq 用当前值写回），对设备零影响；
 * - 复用 [ShellQoS.executeAsRoot]（ADB shell uid 2000 → 无 root 回退普通 shell），
 *   与既有特权通道同一套并发限流，不会压垮 shell。
 *
 * 探测矩阵（对每个 cpufreq policy 与每个 cpu 的 online 节点）：
 *   1. 节点存在性        ls /sys/devices/system/cpu/cpuN/cpufreq
 *   2. 可读性            cat scaling_cur_freq / scaling_max_freq / scaling_min_freq /
 *                        scaling_governor / scaling_available_governors /
 *                        scaling_available_frequencies / related_cpus / cpuinfo_max_freq
 *   3. 可写性            往 scaling_max_freq 写**当前值**（无副作用）再 cat 验证
 *   4. online 节点       /sys/devices/system/cpu/cpuN/online 存在性 + 可写性（同样写回 1/0 原值：
 *                        对 online 的核写 1 是 no-op，绝不去写 0 —— 不下线任何核）
 *   5. governor 可选值   scaling_available_governors 原文透出
 *   6. 当前生效值        每个 policy 的 cur/min/max/governor 快照
 *
 * 结果模型：policies[{cpus, cur, min, max, governor, governors[], freqs[], writable}],
 *           online[{cpu, value, node_exists, writable}], kernel, board
 */
class CpuProbeRoutes {

    private suspend fun sh(cmd: String, timeoutMs: Long = 8_000L): ShellExecutor.ShellResult? =
        try {
            ShellQoS.executeAsRoot(cmd, timeoutMs)
        } catch (e: Exception) {
            null
        }

    private fun ShellExecutor.ShellResult?.text(): String = this?.stdout?.trim().orEmpty()

    fun register(route: Route) {
        route.route("/cpu-probe") {
            // 探测可能发 20+ 条 shell，总时长 5~15s，超时给足
            get {
                val result = withContext(Dispatchers.IO) {
                    try {
                        probe()
                    } catch (e: Exception) {
                        buildJsonObject {
                            put("ok", false)
                            put("error", e.message ?: e.javaClass.simpleName)
                        }
                    }
                }
                call.respondText(result.toString(), ContentType.Application.Json)
            }
        }
    }

    private suspend fun probe(): JsonObject = buildJsonObject {
        put("ok", true)
        put("ts", System.currentTimeMillis())

        // ── 内核/平台信息 ──
        val kernel = sh("uname -r").text()
        put("kernel", kernel)
        put("board", sh("getprop ro.board.platform").text()
            .ifBlank { sh("getprop ro.hardware").text() })
        put("cores_declared", sh("nproc").text().toIntOrNull() ?: 0)
        put("shell_uid", sh("id").text().take(60))

        // ── policy 发现：related_cpus 把同一调频域的核归到一起（4+4 平台一般是 0-3 / 4-7 两组）──
        val relatedRaw = sh("cat /sys/devices/system/cpu/cpu0/cpufreq/related_cpus 2>/dev/null; " +
            "for c in 0 4 6; do cat /sys/devices/system/cpu/cpu\$c/cpufreq/related_cpus 2>/dev/null; done").text()
        // kernel 4.x 静态 related_cpus 包含 offline 核；用 effective 相关性去重保序
        val policyCpuLists = linkedMapOf<Int, List<Int>>()
        relatedRaw.lines().map { it.trim() }.filter { it.matches(Regex("[0-9 ]+")) }.forEach { line ->
            val cpus = line.split(Regex("\\s+")).mapNotNull { it.toIntOrNull() }
            if (cpus.isNotEmpty()) {
                val first = cpus.first()
                policyCpuLists.putIfAbsent(first, cpus)
            }
        }
        if (policyCpuLists.isEmpty()) {
            // related_cpus 不可读时退化为逐核独立探测
            val n = sh("nproc").text().toIntOrNull() ?: 0
            for (c in 0 until n) policyCpuLists[c] = listOf(c)
        }

        val policies = buildJsonArray {
            for ((_, cpus) in policyCpuLists) {
                val base = cpus.first()
                val dir = "/sys/devices/system/cpu/cpu$base/cpufreq"
                suspend fun read(node: String) = sh("cat $dir/$node 2>/dev/null").text()

                val cur = read("scaling_cur_freq")
                val min = read("scaling_min_freq")
                val max = read("scaling_max_freq")
                val gov = read("scaling_governor")
                val governors = read("scaling_available_governors").split(Regex("\\s+")).filter { it.isNotBlank() }
                val freqs = read("scaling_available_frequencies").split(Regex("\\s+")).filter { it.isNotBlank() }
                val hwMax = read("cpuinfo_max_freq")
                val hwMin = read("cpuinfo_min_freq")

                // 可写性：写当前值再读回（scaling_max_freq 写当前 max 是 no-op）
                val writeProbe = if (max.isNotBlank()) {
                    val r = sh("echo '$max' > $dir/scaling_max_freq 2>&1 && cat $dir/scaling_max_freq")
                    val echoed = r?.stdout?.trim()
                    (r != null && r.isSuccess && echoed == max)
                } else false

                add(buildJsonObject {
                    put("cpus", JsonArray(cpus.map { JsonPrimitive(it) }))
                    put("cur_freq", cur)
                    put("min_freq", min)
                    put("max_freq", max)
                    put("hw_max_freq", hwMax)
                    put("hw_min_freq", hwMin)
                    put("governor", gov)
                    put("available_governors", JsonArray(governors.map { JsonPrimitive(it) }))
                    put("available_freqs", JsonArray(freqs.map { JsonPrimitive(it) }))
                    put("freq_writable", writeProbe)
                    put("gov_writable", if (governors.isNotEmpty()) {
                        // governor 试写也用当前值（no-op）
                        val r = sh("echo '$gov' > $dir/scaling_governor 2>&1 && cat $dir/scaling_governor")
                        r != null && r.isSuccess && r.stdout?.trim() == gov
                    } else false)
                })
            }
        }
        put("policies", policies)

        // ── per-cpu online 节点探测（绝不下线，只写回原值）──
        val nCpus = sh("nproc").text().toIntOrNull() ?: 0
        val onlineArr = buildJsonArray {
            for (c in 0 until nCpus) {
                val node = "/sys/devices/system/cpu/cpu$c/online"
                val existsOut = sh("[ -e $node ] && echo yes || echo no")
                val exists = existsOut?.stdout?.trim() == "yes"
                // cpu0 的 online 节点在多数内核上不可写/不存在（boot core），如实报告
                val curVal = if (exists) sh("cat $node")?.stdout?.trim().orEmpty() else ""
                val writable = if (exists && curVal.isNotEmpty()) {
                    val r = sh("echo '$curVal' > $node 2>&1 && echo w-ok")
                    r != null && r.isSuccess && r.stdout?.contains("w-ok") == true
                } else false
                add(buildJsonObject {
                    put("cpu", c)
                    put("node_exists", exists)
                    put("online", curVal == "1")
                    put("writable", writable)
                })
            }
        }
        put("online_nodes", onlineArr)

        // ── thermal 参考（智能调度要用）──
        put("thermal_zones", buildJsonArray {
            val zones = sh("for z in /sys/class/thermal/thermal_zone*; do " +
                "echo \"\$(cat \$z/type 2>/dev/null)=\$(cat \$z/temp 2>/dev/null)\"; done").text()
            zones.lines().filter { it.contains("=") && !it.startsWith("=") }.forEach { line ->
                val (t, v) = line.split("=", limit = 2)
                add(buildJsonObject { put("type", t); put("temp", v) })
            }
        })
    }
}
