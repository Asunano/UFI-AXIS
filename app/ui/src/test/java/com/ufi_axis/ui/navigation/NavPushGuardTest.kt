package com.ufi_axis.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 「所有用户触发的 push 都必须走 [ufiNavigateOnce]」的源码护栏（2026-11-03）。
 *
 * ## 守的是什么
 * 竞态的形态：两个入口（两根手指 / 一帧内两个 onClick）各自调用 `navigate()`，
 * 目的地不同 ⇒ 目的地去重挡不住，返回栈里叠两页。修法是把时间窗闸门接到**每一个**
 * 用户入口上；只要有一个入口漏接，那个入口就还能复现"同时进入两个页面"。
 *
 * ## 判定口径
 * - 扫 `app/feature-*` 与 `app/src/main/java` 下的全部 Kotlin 源码，去掉注释后
 *   不允许出现 `navController.navigate(` / `navController?.navigate(`；
 * - 唯一豁免是 `MainNavGraph.kt` 里的两条**程序触发深链接**（短信通知 / 告警通知），
 *   它们不该被用户上一次点击的 350ms 去抖吃掉。豁免数量写死为 2 —— 新增裸调用会让
 *   这条断言失败，逼人回来看一眼这里。
 *
 * 这个仓库的习惯是用源码扫描守住"约定"，而不是等真机复现（同
 * `PageSwitcherCompositionReadGuardTest` / `NavInsetHandoffGuardTest`）。
 */
class NavPushGuardTest {

    private val repoRoot: File by lazy {
        var dir: File? = File(".").absoluteFile.normalize()
        while (dir != null && !File(dir, "settings.gradle.kts").isFile) {
            dir = dir.parentFile
        }
        requireNotNull(dir) { "定位不到仓库根目录（settings.gradle.kts）" }
    }

    private fun ktFiles(relativeRoot: String): List<File> {
        val root = File(repoRoot, relativeRoot)
        if (!root.isDirectory) return emptyList()
        return root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .toList()
    }

    /** 剥掉块注释与行注释，只留可执行代码 —— 注释里大量讨论 `navigate()`。 */
    private fun executableCode(text: String): String =
        text.replace(Regex("""/\*[\s\S]*?\*/"""), "")
            .lines()
            .joinToString("\n") { it.substringBefore("//") }

    private val rawNavigate = Regex("""navController\??\.navigate\(""")

    @Test
    fun everyUserPushEntryPoint_goesThroughGuard() {
        // 动态枚举所有 feature 模块，避免以后新增模块时忘了往名单里加（名单一漏就等于没护栏）。
        val roots = File(repoRoot, "app")
            .listFiles { f -> f.isDirectory && f.name.startsWith("feature-") }
            ?.map { it.relativeTo(repoRoot).path }
            .orEmpty() + "app/src/main/java"
        val offenders = roots
            .flatMap { ktFiles(it) }
            .filter { rawNavigate.containsMatchIn(executableCode(it.readText())) }
            .map { it.relativeTo(repoRoot).path }

        // `:app:ui` 自己也扫，只豁免两条：守卫实现本身（内部要调 navigate）与
        // MainNavGraph 的两条深链接（由下一条测试单独计数）。
        val uiOffenders = ktFiles("app/ui/src/main/java")
            .filterNot { it.name == "UfiNavGuard.kt" || it.name == "MainNavGraph.kt" }
            .filter { rawNavigate.containsMatchIn(executableCode(it.readText())) }
            .map { it.relativeTo(repoRoot).path }

        val all = offenders + uiOffenders

        assertTrue(
            "以下文件仍在使用裸 `navController.navigate(...)` 做用户跳转：\n" +
                all.joinToString("\n") +
                "\n\n改成 `navController.ufiNavigateOnce(...)`（同签名，带 NavOptions 重载）。" +
                "理由见 UfiNavGuard.kt：不同入口在同一帧触发时，只有进程级时间窗能挡住第二次。",
            all.isEmpty(),
        )
    }

    @Test
    fun onlyProgrammaticDeepLinks_mayBypassGuard() {
        val path = "app/ui/src/main/java/com/ufi_axis/ui/navigation/MainNavGraph.kt"
        val code = executableCode(File(repoRoot, path).readText())
        val raw = rawNavigate.findAll(code).count()
        assertEquals(
            "MainNavGraph 里的裸 navigate 只允许是短信 / 告警两条程序深链接；" +
                "数量变了说明新增了绕过闸门的入口（或者深链接被误改成了 ufiNavigateOnce）。",
            2,
            raw,
        )
    }

    @Test
    fun guardStillExposesAdmissionLogicToTests() {
        // 判据被内联回扩展函数里也能编译，但 NavPushGuardTest/UfiNavGuardTest 会失去覆盖；
        // 这里只做一个轻量提醒，真正的行为测试在 UfiNavGuardTest。
        val path = "app/ui/src/main/java/com/ufi_axis/ui/navigation/UfiNavGuard.kt"
        val code = File(repoRoot, path).readText()
        assertTrue(
            "UfiNavGuard.kt 里找不到纯函数 ufiNavAdmission(...) —— 判据又被内联回 Android 依赖里了？",
            code.contains("internal fun ufiNavAdmission("),
        )
    }
}
