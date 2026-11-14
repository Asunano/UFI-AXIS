package com.ufi_axis_core.api.routes

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「主人密码门」与源码一致性的守卫测试。
 *
 * ## 为什么需要它
 *
 * 这道门是**逐个 handler 手写**的（密码在请求体里，Ktor 请求体只能消费一次，做不成拦截器），
 * 于是历史上反复出现同一类事故：
 *
 * - 新增一个同能力的入口时没人记得加门（黑名单的**批量解封**就是这么漏的：单条解封进了门、
 *   清空没进）；
 * - 加了门但没登记，下一个人就以为那个端点不需要门；
 * - 登记写的是 A，门却加在 B。
 *
 * ## 四条断言
 *
 * 1. **锚唯一且存在**：每个表项的 `anchor` 在它自己的文件里恰好出现一次 `// [owner] <anchor>`；
 * 2. **没有无主的门**：`core/api` 的路由与 `HttpServer` 里，每一处 `call.requireOwner*`
 *    的上一行必须是一条 `[owner]` 锚 —— 这条同时守住两个**条件门**
 *    （`PUT /api/config` 的重指向、`PUT /api/pairing/config` 的关总开关），它们不在表里；
 * 3. **路径字面量存在**：表里每条路径确实写在源码里（端点改名/删掉后没人清理会红）；
 * 4. **安装/更新类不在门内**：这是本版最关键的产品口径，被谁"顺手补上"就立刻红。
 *
 * 第 2 条取代了早先的「数调用点数量」：数量对上不代表对得上号，锚才对得上。
 */
class OwnerGateCoverageTest {

    private fun repoRoot(): File {
        var dir: File? = File(".").absoluteFile.normalize()
        while (dir != null) {
            if (File(dir, "settings.gradle.kts").isFile) return dir
            dir = dir.parentFile
        }
        throw AssertionError("定位不到仓库根（起点：${File(".").absolutePath}）")
    }

    private val routesPath = "core/api/src/main/java/com/ufi_axis_core/api/routes"

    private fun routesDir(): File = File(repoRoot(), routesPath)

    /** 所有可能写门的源文件：core/api 的路由 + core/network 的 HttpServer。 */
    private fun gateSources(): List<File> {
        val dir = routesDir()
        val routes = dir.listFiles { f -> f.isFile && f.extension == "kt" }
            ?.filter { it.name != "OwnerGate.kt" }
            .orEmpty()
        val server = File(
            repoRoot(),
            "core/network/src/main/java/com/ufi_axis_core/core/server/HttpServer.kt"
        )
        return (routes + listOfNotNull(server.takeIf { it.isFile })).sortedBy { it.name }
    }

    @Test
    fun `表里的锚在各自文件里恰好出现一次`() {
        val dir = routesDir()
        val problems = mutableListOf<String>()
        DESTRUCTIVE_ENDPOINTS.forEach { e ->
            val file = File(dir, e.file)
            if (!file.isFile) {
                problems += "${e.file} 不存在（表里登记了 ${e.method} ${e.path ?: "(无路径)"}）"
                return@forEach
            }
            val text = file.readText()
            val marker = "// [owner] ${e.anchor}"
            val count = Regex(Regex.escape(marker)).findAll(text).count()
            if (count != 1) problems += "${e.file} 里锚 \"$marker\" 出现 $count 次（应为 1）"
        }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    @Test
    fun `表里的锚互不重复`() {
        val anchors = DESTRUCTIVE_ENDPOINTS.map { it.anchor }
        val dup = anchors.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        assertTrue("锚必须唯一，重复：$dup", dup.isEmpty())
    }

    @Test
    fun `没有无主的主人密码门`() {
        val problems = mutableListOf<String>()
        gateSources().forEach { file ->
            val lines = file.readLines()
            lines.forEachIndexed { i, line ->
                // 跳过注释行本身（KDoc 里的用法示例）
                val trimmed = line.trimStart()
                if (trimmed.startsWith("*") || trimmed.startsWith("//")) return@forEachIndexed
                if (!line.contains("call.requireOwner")) return@forEachIndexed
                val prev = lines.getOrNull(i - 1)?.trimStart().orEmpty()
                if (!prev.startsWith("// [owner]")) {
                    problems += "${file.name}:${i + 1} 有 requireOwner 但没有 // [owner] 锚"
                }
            }
        }
        assertTrue(
            "每一处门都必须带锚（新增门请同时登记进 DESTRUCTIVE_ENDPOINTS）：\n" +
                problems.joinToString("\n"),
            problems.isEmpty()
        )
    }

    @Test
    fun `表里每个路径字面量确实写在源码里`() {
        val dir = routesDir()
        val problems = mutableListOf<String>()
        DESTRUCTIVE_ENDPOINTS.forEach { e ->
            val file = File(dir, e.file)
            if (!file.isFile) return@forEach
            val text = file.readText()
            val path = e.path
            if (path != null && !text.contains("\"$path\"")) {
                problems += "${e.file} 里找不到字面量 \"$path\"（${e.note}）"
            }
            if (!Regex("""\b${e.method}\s*[({]""").containsMatchIn(text)) {
                problems += "${e.file} 里没有 ${e.method} 声明"
            }
        }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    @Test
    fun `安装与更新类端点不在门内`() {
        // 本版最关键的产品口径：装 APK / 装程序 / 装组件 / 更新包 / 隧道配置一律不拦。
        // 万一以后有人"顺手补上"，这条会先红，逼人重新想一遍值不值得打扰用户。
        // 注意 `/{id}/upload` **不在**这个名单里：上传的字节直接来自客户端、装完就能 start，
        // 等于绕开 shell/exec 的 root 执行能力，所以它要密码（2026-11-14 安全评审）。
        // 从清单安装（`/{id}/install`）仍然不要 —— 用户口径是"装程序不打扰"。
        val mustNotBeGated = listOf(
            "/install", "/install-url", "/{id}/install",
            "/update", "/install-local", "/config/{name}", "/tunnel/{name}",
        )
        val gated = DESTRUCTIVE_ENDPOINTS.mapNotNull { it.path }.toSet()
        val overlap = mustNotBeGated.filter { it in gated }
        assertTrue("这些端点不该要求主人密码：$overlap", overlap.isEmpty())
    }

    @Test
    fun `表项数量与代码里的锚总数一致`() {
        val dir = routesDir()
        val total = dir.listFiles { f -> f.isFile && f.extension == "kt" }
            ?.filter { it.name != "OwnerGate.kt" && it.name != "DestructiveEndpoints.kt" }
            ?.sumOf { f -> Regex("// \\[owner\\] ").findAll(f.readText()).count() }
            ?: 0
        // 条件门（`PUT /api/config`）也在 core/api 里，但不进表；它由「没有无主的门」那条守。
        val conditionalInRoutes = 1
        assertEquals(
            "表项 = 无条件门的数量；条件门不计入",
            DESTRUCTIVE_ENDPOINTS.size + conditionalInRoutes,
            total,
        )
    }
}
