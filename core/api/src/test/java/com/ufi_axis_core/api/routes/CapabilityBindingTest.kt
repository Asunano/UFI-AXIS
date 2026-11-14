package com.ufi_axis_core.api.routes

import com.ufi_axis_core.contract.Capability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 「端点 ↔ 能力」绑定的守门测试（2026-10-30 越权审查 §2.6）。
 *
 * ## 与 `CapabilityGateTest` 的分工
 *
 * - `CapabilityGateTest`：**每个 Capability 至少被引用一次**（防「定了不用」）+ 豁免表有上限；
 * - 本类：**每个端点用的是不是它该用的那一个**，以及**标记了更新互斥的端点真的调了**。
 *
 * 两者都不能省：前者管「能力集不膨胀/不空转」，后者管「门禁没串域」。
 * 在 2026-10-30 之前只有前者 —— 把 `/device/samba` 的门禁误写成 `Capability.SMS`
 * 不会有任何测试变红，而这正是「操作 A 功能误拦/误放 B」的典型形态。
 *
 * ## 为什么还是扫源码而不是起 Ktor 测应用
 *
 * 逐端点起一遍要 mock `RouteContext` 的十几个依赖，测到的仍然只是「我测过的那几个」。
 * 扫源码能一次覆盖全部 12 条，且**正面命中命题**：门禁必须写在 handler 开头 25 行内
 * （这本身也是 `CapabilityGate.kt` 里写的「放在 handler 第一行，读请求体之前」）。
 */
class CapabilityBindingTest {

    /** 表里的每一条都要能在源码里定位到，并紧跟同名的门禁。 */
    @Test
    fun `每个受门禁的写端点都声明了正确的能力`() {
        WRITE_ENDPOINT_BINDINGS.forEach { binding ->
            val text = source(binding.file)
            val declaration = routeDeclaration(binding, text)

            val window = text.substring(declaration)
                .lineSequence()
                .take(WINDOW_LINES)
                .joinToString("\n")

            val gate = Regex("""requireCapability\(\s*Capability\.${binding.capability.name}\s*\)""")
            assertTrue(
                "${binding.file} 的 ${binding.method}(\"${binding.path}\") 必须在开头 $WINDOW_LINES 行内" +
                    "调用 requireCapability(Capability.${binding.capability.name})。\n" +
                    "实际开头：\n$window",
                gate.containsMatchIn(window)
            )
        }
    }

    /** 标了更新互斥的端点必须真的调 `rejectIfUpdating()`。 */
    @Test
    fun `标记为更新互斥的端点确实调了 rejectIfUpdating`() {
        WRITE_ENDPOINT_BINDINGS.filter { it.rejectWhenUpdating }.forEach { binding ->
            val text = source(binding.file)
            val declaration = routeDeclaration(binding, text)
            val window = text.substring(declaration)
                .lineSequence()
                .take(WINDOW_LINES)
                .joinToString("\n")

            assertTrue(
                "${binding.file} 的 ${binding.method}(\"${binding.path}\") 标了 rejectWhenUpdating，" +
                    "但开头 $WINDOW_LINES 行内没有 rejectIfUpdating()",
                window.contains("rejectIfUpdating()")
            )
            // 表里标 true 却没有能力门禁以外的配套（比如漏了 `return@post`）是更隐蔽的错：
            // `if (call.rejectIfUpdating()) return@post` 少了后半句等于没拦。
            assertTrue(
                "${binding.file} 的 ${binding.method}(\"${binding.path}\") 的 rejectIfUpdating() " +
                    "缺少 `return@post` —— 少了它等于没拦（响应已发，handler 继续执行）",
                Regex("""rejectIfUpdating\(\)\)\s*return@""").containsMatchIn(window)
            )
        }
    }

    /**
     * 表必须覆盖全部非豁免的 Capability。
     *
     * 豁免与 `CapabilityGateTest.exempt` 同口径（目前只有 [Capability.BATTERY]：
     * 纯读侧，本质不该有写 route）。两处各写一份是有意的：它们守的是不同的命题，
     * 共用一份会让「加豁免」变成只改一个地方就能同时放松两条纪律。
     */
    @Test
    fun `表覆盖了全部非豁免能力`() {
        val exempt = setOf(Capability.BATTERY)
        val bound = WRITE_ENDPOINT_BINDINGS.map { it.capability }.toSet()
        val missing = Capability.entries.filter { it !in exempt && it !in bound }
        assertEquals(
            "这些 Capability 没有任何端点绑定在 WRITE_ENDPOINT_BINDINGS 里：" +
                "${missing.map { it.wire }}。补绑定，或按 CapabilityGateTest 的规矩登记豁免",
            emptyList<Capability>(),
            missing,
        )
    }

    /** 反向：表里不许出现一个源码里根本不存在的路由（防「表是死的」）。 */
    @Test
    fun `表里的每条绑定都能在源码里唯一定位`() {
        WRITE_ENDPOINT_BINDINGS.forEach { binding ->
            val text = source(binding.file)
            val pattern = declarationPattern(binding)
            val hits = pattern.findAll(text).count()
            assertEquals(
                "${binding.file} 里 `${binding.method}(\"${binding.path}\")` 命中 $hits 处，期望恰好 1 处。" +
                    "同一文件里同名字面路由出现多次时，绑定无法唯一定位 —— 请改用更具体的路径",
                1,
                hits,
            )
        }
    }

    // ───────────────────────── 辅助 ─────────────────────────

    /** 路由声明在文件中的起始下标。找不到直接失败（不是「跳过」）。 */
    private fun routeDeclaration(binding: WriteEndpointBinding, text: String): Int {
        val match = declarationPattern(binding).find(text)
            ?: error(
                "在 ${binding.file} 里找不到 `${binding.method}(\"${binding.path}\")`。" +
                    "路由改名/挪文件后必须同步 WRITE_ENDPOINT_BINDINGS"
            )
        return match.range.first
    }

    private fun declarationPattern(binding: WriteEndpointBinding): Regex =
        Regex("""(?m)^\s*${binding.method}\("${Regex.escape(binding.path)}"\)""")

    private fun source(fileName: String): String {
        val dir = routesDir()
        val file = File(dir, fileName)
        assertTrue("route 源码不存在：${file.absolutePath}", file.isFile)
        return file.readText(Charsets.UTF_8)
    }

    /**
     * route 源码目录。候选与 `CapabilityGateTest.routeSources()` 完全一致
     * （Gradle 的 Test 任务工作目录是模块目录，IDE 单跑时可能是仓库根）——
     * 找不到就报错，不许静默当成「0 个文件」让断言空转。
     */
    private fun routesDir(): File {
        val relative = "src/main/java/com/ufi_axis_core/api/routes"
        val candidates = listOf(
            File(relative),
            File("core/api/$relative"),
            File("../../core/api/$relative"),
        )
        return candidates.firstOrNull { it.isDirectory }
            ?: error(
                "找不到 route 源码目录（cwd=${File("").absolutePath}）。" +
                    "本类靠扫源码守「端点 ↔ 能力」绑定，扫不到文件必须报错而不是当成 0 处"
            )
    }

    private companion object {
        /** 门禁必须出现在 handler 开头多少行内。现有 12 条都在前 5 行内。 */
        const val WINDOW_LINES = 25
    }
}
