package com.ufi_axis_core.api

import com.ufi_axis_core.contract.Capability
import com.ufi_axis_core.contract.ErrorCode
import io.ktor.http.HttpStatusCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 能力门禁的守门测试（计划书 §7 的 3.7）。
 *
 * 两件事：
 *
 * 1. **[requireCapability] 的行为**：缺能力 → 抛 [CapabilityMissing]，且它带的是
 *    501 + [ErrorCode.NOT_SUPPORTED]（不是 503、不是 500）。
 * 2. **「定了不用」的防线**：[Capability] 的每一项都必须**至少被一处** route 门禁引用。
 *
 * ## 第 2 条为什么用「扫源码」实现
 *
 * 要断言的命题是「route 里真的调了门禁」，而 route 的 handler 只有在起 Ktor 测试应用、
 * 把 `RouteContext` 的十几个依赖都 mock 出来之后才能执行 —— 逐端点起一遍的代价，
 * 换来的仍然是「我测过的那几个端点有门禁」，覆盖不了「新加的端点忘了加」。
 * 直接扫 `requireCapability(Capability.X)` 的调用点反而**正面命中命题**：
 * 它是这个门禁唯一的入口（[requireCapability] 是 `Set<Capability>` 上唯一的扩展函数），
 * 绕过它就等于自己在 handler 里写 `if`，那在 code review 里是显式违规。
 *
 * 正则只认**调用形式**（`requireCapability(Capability.X)`），所以 KDoc 里提到某个
 * `Capability.X` 不会被算成一处门禁 —— 注释写得再好也不等于代码里拦了。
 */
class CapabilityGateTest {

    /**
     * **豁免表：暂时没有写 route、因此不做门禁的 Capability。**
     *
     * 现在是**空的** —— 第一批 10 个域逐项都核过「有 `WriteSpec`（或 `smsSpec`）+ 有写 route」
     * 两条（对照表记在 `ZteF50Plugin.capabilities` 的 KDoc 上）。
     *
     * ## 往这里加项的规矩
     *
     * 1. **只许因为「core 侧真的没有那个写 route」而豁免**，不许因为「加门禁麻烦 / 测试红了」而豁免；
     * 2. 每加一项必须在这里写清**为什么没有 route**，以及补上 route 的条件；
     * 3. **有上限**（[MAX_EXEMPT]）：豁免表变成垃圾桶的第一步就是「反正能加」。
     *    超了就红 —— 那时该问的是「这批 Capability 是不是定早了」，而不是把上限调大。
     *
     * **不许为了让测试绿而硬造 route**：一个只为通过断言而存在的端点，
     * 比一条豁免记录危险得多（它会被当成真能用的功能接进 UI）。
     */
    private val exempt: Set<Capability> = emptySet()

    /** `requireCapability(Capability.X)` 的调用点。KDoc 里的提及不算。 */
    private val gateCall = Regex("""requireCapability\(\s*Capability\.([A-Z_0-9]+)\s*\)""")

    // ───────────────────────── 门禁行为 ─────────────────────────

    @Test
    fun `缺能力时抛 CapabilityMissing 并带 501 与 NOT_SUPPORTED`() {
        val available = setOf(Capability.SMS)

        val thrown = try {
            available.requireCapability(Capability.SAMBA)
            null
        } catch (e: CapabilityMissing) {
            e
        }

        val missing = requireNotNull(thrown) { "能力集里没有 samba，必须抛 CapabilityMissing" }
        assertEquals(Capability.SAMBA, missing.capability)
        // 501：不可恢复。回 503 会让客户端退避重试一个永远不会成功的请求；
        // 回 500 会让用户看到「服务器内部错误」。三者必须分开（§11.6）。
        assertEquals(HttpStatusCode.NotImplemented, missing.status)
        assertEquals(ErrorCode.NOT_SUPPORTED, missing.errorCode)
        // 文案里必须带 wire 名：前端靠它定位到该灰掉的那个开关
        assertTrue(
            "用户文案要带上 wire 名：${missing.userMessage}",
            missing.userMessage.contains(Capability.SAMBA.wire),
        )
    }

    @Test
    fun `能力在集合里时门禁放行`() {
        // 放行路径不许有任何副作用，也不许抛 —— 它在每个被门禁的 handler 第一行执行
        setOf(Capability.SMS, Capability.SAMBA).requireCapability(Capability.SAMBA)
    }

    // ───────────────────────── 「定了不用」防线 ─────────────────────────

    @Test
    fun `每个 Capability 都至少被一处 route 门禁引用`() {
        val gated: Map<String, Int> = countGateCalls()

        val notGated = Capability.entries.filter { it !in exempt && gated[it.name] == null }
        assertEquals(
            "这些 Capability 定了却没有任何 route 门禁引用（「定了不用」）：" +
                "${notGated.map { it.wire }}；" +
                "要么补门禁，要么按 exempt 的规矩登记豁免 —— 不许为了让本用例绿而硬造 route",
            emptyList<Capability>(),
            notGated,
        )

        // 为什么是「至少」而不是「恰好」（2026-09-24 用户裁决）：
        // Capability 是**功能域**，一个域可以有**多个写入口**（/network/mode 与 /network/bearer、
        // /device/cell-lock 与 /device/cell-unlock），而**每个入口都必须拦**。
        // 拦的不是设备命令（那两对确实各自共用一条命令），而是「这台设备支不支持这个功能域」；
        // 放行其中任何一个入口，就等于给前端留了一条绕过门禁、把请求打到设备再失败的路。
        //
        // 所以这里**刻意不设上限**：门禁调用点的数量不该成为「加写入口时顺手也加门禁」的绊脚石。
        // 真正要防的「撒」是「拦在不属于该域的端点上」，那靠 code review 与各门禁处的注释，
        // 不靠计数 —— 计数管不住语义，还会在加入口时逼人改测试。
        val total = gated.values.sum()
        assertTrue(
            "门禁调用点总数 $total 少于 Capability 的项数 ${Capability.entries.size}（明细：$gated）—— " +
                "每个域至少一处，域内有多个写入口时应当多于项数",
            total >= Capability.entries.size - exempt.size,
        )
    }

    @Test
    fun `豁免表本身受约束`() {
        // 上限：防止豁免表变成垃圾桶
        assertTrue(
            "豁免表已有 ${exempt.size} 项，超过上限 $MAX_EXEMPT —— " +
                "这时候该问的是「这批 Capability 是不是定早了」，不是把上限调大",
            exempt.size <= MAX_EXEMPT,
        )
        // 豁免项不许同时又在门禁里：那说明豁免记录早就过期了，留着只会误导下一个人
        val gated = countGateCalls().keys
        val staleExempt = exempt.filter { it.name in gated }
        assertEquals(
            "这些项已经有门禁了，请从豁免表里删掉：${staleExempt.map { it.wire }}",
            emptyList<Capability>(),
            staleExempt,
        )
    }

    // ───────────────────────── 辅助 ─────────────────────────

    /** 枚举名 → 门禁调用次数（只统计真有调用的项）。 */
    private fun countGateCalls(): Map<String, Int> {
        val counts = mutableMapOf<String, Int>()
        routeSources().forEach { file ->
            gateCall.findAll(file.readText(Charsets.UTF_8)).forEach { m ->
                val name = m.groupValues[1]
                counts[name] = (counts[name] ?: 0) + 1
            }
        }
        return counts
    }

    /**
     * 全部 route 源码文件。
     *
     * 目录用几个候选去找而不是写死一条相对路径：Gradle 的 `Test` 任务默认工作目录是
     * **模块目录**（`core/api`），但从仓库根或 IDE 里单跑时不一定。找不到就**直接失败**，
     * 不许静默当成「扫到 0 个文件」—— 那样本类的两条断言会全部空转变成永远绿。
     */
    private fun routeSources(): List<File> {
        val relative = "src/main/java/com/ufi_axis_core/api/routes"
        val candidates = listOf(
            File(relative),
            File("core/api/$relative"),
            File("../../core/api/$relative"),
        )
        val dir = candidates.firstOrNull { it.isDirectory }
            ?: error(
                "找不到 route 源码目录（cwd=${File("").absolutePath}）。" +
                    "本类靠扫源码守「每个 Capability 至少被一处门禁引用」，" +
                    "扫不到文件必须报错而不是当成 0 处门禁"
            )
        val files = dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue("route 源码目录是空的：${dir.absolutePath}", files.isNotEmpty())
        return files
    }

    private companion object {
        /**
         * 豁免表上限。
         *
         * 定 2 而不是 0：留一点余地给「第二台设备接进来时发现某个域在 core 侧确实还没有写
         * route」这种真实情况；但 10 个域里超过 2 个没 route，就说明这批能力定得太早了。
         */
        const val MAX_EXEMPT = 2
    }
}
