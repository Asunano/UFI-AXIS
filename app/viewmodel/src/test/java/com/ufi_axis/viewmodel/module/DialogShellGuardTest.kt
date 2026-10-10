package com.ufi_axis.viewmodel.module

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 「弹窗必须真正挂上公共弹窗外壳」的回归护栏（2026-10-10）。
 *
 * ## 被守护的事故
 * 重写 `NetworkModeDialog` 时，**把 `UfiCustomDialog` / `UfiDialogBody` 两层外壳整个漏掉了** ——
 * 新内容直接写在 `@Composable` 函数体里。结果：`visible` 与 `onDismiss` 两个参数**完全没被使用**，
 * 弹窗根本不弹，制式/信号那些控件裸着渲染在宿主页面上。
 *
 * ## 为什么编译期抓不到
 * 这是本次最值得记的一点：
 * - Kotlin 对**未使用的函数参数**不报错也不警告（只对未使用的局部变量/私有成员给提示）；
 * - `visible: Boolean` 是合法签名，`UfiDialogBody` 等组件也都是合法调用；
 * - 于是 `compileDebugKotlin` **BUILD SUCCESSFUL**，一路正常出包 —— 编译、签名、发布全绿，
 *   运行时才发现弹窗不见了。
 *
 * 唯一能判别的是「`visible` 有没有被接到弹窗开关上」，而那正是本测试断言的东西。
 */
class DialogShellGuardTest {

    @Test
    fun networkModeDialog_mustUseUfiCustomDialogShell() {
        val code = executableCode(source(NetworkModeDialogPath))

        assertTrue(
            "NetworkModeDialog 里找不到 `UfiCustomDialog(` —— " +
                "弹窗外壳被漏掉了：内容会直接渲染在宿主页面上，弹窗根本不会弹。" +
                "2026-10-10 已真实发生过一次（且 compileDebugKotlin 是 SUCCESSFUL，" +
                "因为 Kotlin 不报未使用的函数参数）",
            code.contains("UfiCustomDialog(")
        )
        assertTrue(
            "NetworkModeDialog 里找不到 `UfiDialogBody` —— " +
                "外壳在但内容没进 body，布局间距与弹窗壳的节奏约定会失效",
            code.contains("UfiDialogBody")
        )
    }

    @Test
    fun networkModeDialog_visibleAndOnDismiss_mustBeWiredToShell() {
        val code = executableCode(source(NetworkModeDialogPath))
        val body = functionBody(code, "fun NetworkModeDialog(")

        assertTrue(
            "`visible` 参数没有被传给弹窗（找不到 `visible = visible`）—— " +
                "UfiCustomDialog 的 visible 是弹窗显示与否的**唯一真源**，不接它弹窗就不出现。" +
                "未使用的参数 Kotlin 不报错，所以只能靠本测试钉住",
            Regex("""visible\s*=\s*visible\b""").containsMatchIn(body)
        )
        assertTrue(
            "`onDismiss` 参数没有被传给弹窗（找不到 `onDismiss = onDismiss`）—— " +
                "不接它则点遮罩/返回键关不掉弹窗",
            Regex("""onDismiss\s*=\s*onDismiss\b""").containsMatchIn(body)
        )
    }

    // ── 测试脚手架（同 CoreUpdateExemptionGuardTest / PtyEntryHiddenGuardTest） ──

    private companion object {
        const val NetworkModeDialogPath =
            "app/feature-network/src/main/java/com/ufi_axis/ui/screens/NetworkModeDialog.kt"
    }

    /** 从 `fun xxx(` 起截取配平花括号的方法体（含签名行）。 */
    private fun functionBody(code: String, signature: String): String {
        val start = code.indexOf(signature)
        if (start < 0) throw AssertionError("源码里找不到 `$signature`")
        val open = code.indexOf('{', start)
        if (open < 0) throw AssertionError("`$signature` 后面找不到 '{'")
        var depth = 0
        var i = open
        while (i < code.length) {
            when (code[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return code.substring(start, i + 1)
                }
            }
            i++
        }
        throw AssertionError("`$signature` 的花括号没配平")
    }

    private fun source(relative: String): String {
        var dir: File? = File(".").absoluteFile.normalize()
        while (dir != null) {
            for (candidate in listOf(File(dir, relative), File(dir, "../$relative"))) {
                if (candidate.isFile) return candidate.readText()
            }
            dir = dir.parentFile
        }
        throw AssertionError(
            "定位不到源文件 '$relative'（起点：${File(".").absolutePath}）。" +
                "若模块路径发生变化，请同步更新本测试中的相对路径。"
        )
    }

    /** 剥掉注释，只留可执行代码（KDoc 里反复出现 `UfiCustomDialog` 这些名字）。 */
    private fun executableCode(text: String): String =
        text.replace(Regex("""/\*[\s\S]*?\*/"""), "")
            .lines()
            .joinToString("\n") { it.substringBefore("//") }
}