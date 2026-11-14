package com.ufi_axis_core.controller.goform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * 守门测试：**`GoformTransport` 不许跨出 `core/goform`**。
 *
 * ## 为什么需要一条扫源码的测试
 *
 * 阶段 1 的设计目标是「上层只认 [DeviceTransport] 的 14 个方法」。
 * 本来想用语言层面挡住 —— 把 [GoformTransport] 标 `internal`。
 * **2026-09-23 实测走不通**：6 个客户端（`GoformSignalClient` 等）是 public class、
 * 构造点在 `:core` 模块的 `ComponentFactory.buildNetworkGraph`，Kotlin 会报
 * `'public' function exposes its 'internal' parameter type 'GoformTransport'`；
 * 唯一能编过的绕法是 `@Suppress("EXPOSED_PARAMETER_TYPE")`，而编译器对它明确不背书
 * （"the compiler behavior is UNSPECIFIED and WILL NOT BE PRESERVED"）——
 * 那种东西不许留在主线上。
 *
 * 于是接口改成 `public`，把「不对外」从**语言约束**降级成**纪律**，
 * 再用本测试把纪律钉住。挡不住编译器的，就挡在 CI 上。
 *
 * ## 什么时候可以删掉本类
 *
 * 阶段 2 把 6 个客户端连同传输层一起收进插件 module、它们整体变 `internal` 之后，
 * [GoformTransport] 就能真正收窄成 `internal`，本类与 `GoformTransport` KDoc 里那段说明一起删。
 *
 * ## 为什么用 `assumeTrue` 而不是直接失败
 *
 * 本类依赖「能从测试工作目录找到仓库根」。Gradle 默认把 test 的工作目录设成 module 目录，
 * 所以正常情况下找得到；但换构建方式 / 在别的环境跑时可能找不到 ——
 * 那种情况下**跳过**比**假失败**好：一条永远红的守门测试，最后一定被人加 `@Ignore`。
 *
 * ## ⚠ 已知局限：它不会在别的 module 改动时自动重跑
 *
 * 本类扫的是**别的 module 的源码文件**，而 Gradle 只把 `:core:goform` 自己的源码/依赖当作
 * `test` 任务的输入。所以「有人在 `:core` 或 `:core:api` 里写了越界引用」这件事，
 * 在 `:core:goform` 没变的情况下 `:core:goform:test` 会报 `UP-TO-DATE`，**一声不响地不跑**。
 * 2026-09-23 就踩过一次：阶段 1.5 在 `ComponentFactory` 的 KDoc 里写了这个符号名，
 * 当轮 `:core:goform:test` 显示全绿，其实是没重跑（该文件不在输入里）。
 * **纪律**：阶段收尾时用 `--rerun-tasks` 强制跑一次本类，别只看增量结果。
 */
class GoformTransportVisibilityGuardTest {

    /** 本 module 自己的源码根（允许引用 `GoformTransport` 的唯一范围）。 */
    private val ownModulePathMarkers = listOf(
        "${File.separator}core${File.separator}goform${File.separator}",
    )

    private fun repoRoot(): File? {
        var dir: File? = File("").absoluteFile
        repeat(8) {
            val d = dir ?: return null
            if (File(d, "settings.gradle.kts").isFile) return d
            dir = d.parentFile
        }
        return null
    }

    /**
     * `core` 目录下所有参与编译的 Kotlin 源码（排除 build 产物与历史残留 `build.old` 前缀目录）。
     *
     * ⚠ 注意：KDoc 里不要写 `core` 加斜杠加两个星号那种通配写法 —— Kotlin 的块注释**可以嵌套**，
     * 斜杠加星号会在注释里再开一层，行尾的星号加斜杠只关掉内层，外层注释吃掉后面整个文件
     * （本文件第一版就是这么编译失败的：`Missing '}'` + `Unclosed comment`）。
     */
    private fun coreKotlinSources(root: File): List<File> =
        File(root, "core").walkTopDown()
            .onEnter { d ->
                val n = d.name
                n != "build" && !n.startsWith("build.old") && n != ".gradle"
            }
            .filter { it.isFile && it.extension == "kt" }
            .toList()

    /**
     * 把注释剥掉，只留**真代码**（字符串字面量保留）。
     *
     * ## 为什么必须剥注释
     *
     * 第一版直接 `readText().contains("GoformTransport")`，结果**把自己绊倒了**：
     * `ComponentFactory.createTransport()` 的 KDoc 里写着「本文件**不许**直接写 `GoformTransport`
     * 这个类型名」—— 一句解释为什么不许用的话，被纯文本扫描当成了「用了」。
     * 注释里提到符号名不仅正常，而且是必要的（不写下来，下一个人就不知道这条边界是刻意的）。
     * 判据要盯的是**代码引用**（import / 类型标注 / 调用），不是文字出现。
     *
     * Kotlin 的块注释**可以嵌套**，所以这里按深度计数，不能见到第一个星号加斜杠就收工。
     * 字符串字面量**故意保留**：`"..."` 里出现符号名的场合（日志、错误消息）虽然不是引用，
     * 但也不值得为它再加一层解析 —— 真出现了让它红一次、人来判断更安全。
     */
    private fun stripComments(src: String): String {
        val out = StringBuilder(src.length)
        var i = 0
        var blockDepth = 0
        while (i < src.length) {
            val c = src[i]
            val next = if (i + 1 < src.length) src[i + 1] else ' '
            when {
                blockDepth > 0 -> when {
                    c == '/' && next == '*' -> { blockDepth++; i += 2 }
                    c == '*' && next == '/' -> { blockDepth--; i += 2 }
                    else -> i++
                }
                c == '/' && next == '*' -> { blockDepth = 1; i += 2 }
                c == '/' && next == '/' -> { while (i < src.length && src[i] != '\n') i++ }
                src.startsWith("\"\"\"", i) -> {
                    val end = src.indexOf("\"\"\"", i + 3)
                    val stop = if (end < 0) src.length else end + 3
                    out.append(src, i, stop)
                    i = stop
                }
                c == '"' -> {
                    out.append(c)
                    i++
                    while (i < src.length) {
                        val ch = src[i]
                        out.append(ch)
                        i++
                        when {
                            ch == '\\' && i < src.length -> { out.append(src[i]); i++ }
                            ch == '"' || ch == '\n' -> break
                        }
                    }
                }
                else -> { out.append(c); i++ }
            }
        }
        return out.toString()
    }

    /** 该文件的**代码**（剥掉注释后）是否引用了 `GoformTransport`。 */
    private fun referencesGoformTransport(f: File): Boolean =
        stripComments(f.readText()).contains("GoformTransport")

    @Test
    fun `GoformTransport 在 core-goform 之外零引用`() {
        val root = repoRoot()
        assumeTrue("找不到仓库根（settings.gradle.kts），跳过跨模块扫描", root != null)
        val sources = coreKotlinSources(root!!)

        // 自检：扫描器本身得真的扫到东西，否则下面的断言是恒真式空转。
        assertTrue("core 目录下扫到的 .kt 文件数异常偏少：${sources.size}", sources.size >= 50)

        val offenders = sources
            .filterNot { f -> ownModulePathMarkers.any { f.path.contains(it) } }
            .filter { referencesGoformTransport(it) }
            .map { it.relativeTo(root).path }

        assertEquals(
            "该符号只许在 core/goform 内部使用（上层一律只认 DeviceTransport 的 14 个方法）；" +
                "越界文件：$offenders",
            emptyList<String>(),
            offenders,
        )
    }

    @Test
    fun `core-goform 内部确实在用 GoformTransport（防止测到空气）`() {
        val root = repoRoot()
        assumeTrue("找不到仓库根（settings.gradle.kts），跳过跨模块扫描", root != null)

        val users = coreKotlinSources(root!!)
            .filter { f -> ownModulePathMarkers.any { f.path.contains(it) } }
            .filter { referencesGoformTransport(it) }

        // 接口自身 + 6 个客户端 + GoformSettingWriter + GoformClient = 9 个文件（本测试的字符串也算一个）。
        // 这条不是为了冻结数量，而是为了保证上一条测的是真约束：
        // 哪天符号被改名而上一条仍然「通过」，这一条会把它抓出来。
        assertTrue(
            "core/goform 内部引用该符号的文件数异常偏少：${users.size}（符号被改名了？）",
            users.size >= 9,
        )
    }
}
