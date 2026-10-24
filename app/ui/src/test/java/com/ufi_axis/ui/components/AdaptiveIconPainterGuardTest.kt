package com.ufi_axis.ui.components

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 启动页图标的**崩溃守卫**（2026-10-22）。
 *
 * ## 守什么
 * `UfiStartupOverlay` 曾在**首帧组合**里写 `painterResource(R.mipmap.ic_launcher)`。
 * 本工程的 `mipmap-anydpi/ic_launcher.xml` 是 `<adaptive-icon>`（全仓也没有位图 mipmap），
 * 而 Compose 的 `painterResource` 只支持 VectorDrawable 与 PNG/JPG/WEBP —— 遇到自适应图标 XML
 * 会抛：
 *
 * ```
 * java.lang.IllegalArgumentException: Only VectorDrawables and rasterized asset types are supported
 *   ex. PNG, JPG, WEBP
 * ```
 *
 * 它发生在 `onAttachedToWindow` 的首次组合里，所以是**必崩**（Android 16 实测，
 * 见 `crash_01-42-48.log`）。这类错误编译期完全看不出来，只能靠源码守卫。
 *
 * ## 为什么规则只针对 mipmap
 * `mipmap/` 是给**桌面图标**用的资源目录，`adaptive-icon` XML 只可能出现在那里；
 * `drawable/` 下的矢量（如 `ic_launcher_foreground`）用 `painterResource` 是正确用法。
 * 一条能直接照做的规则，比"请检查所有 painterResource"有用。
 */
class AdaptiveIconPainterGuardTest {

    /** 从模块目录（app/ui）逐级上溯到仓库根。 */
    private fun repoRoot(): File {
        var dir: File? = File(".").absoluteFile.normalize()
        while (dir != null) {
            if (File(dir, "settings.gradle.kts").isFile) return dir
            dir = dir.parentFile
        }
        throw AssertionError("定位不到仓库根（起点：${File(".").absolutePath}）")
    }

    private fun source(relative: String): String {
        var dir: File? = File(".").absoluteFile.normalize()
        while (dir != null) {
            for (candidate in listOf(File(dir, relative), File(dir, "app/ui/$relative"))) {
                if (candidate.isFile) return candidate.readText()
            }
            dir = dir.parentFile
        }
        throw AssertionError("定位不到源文件 '$relative'（起点：${File(".").absolutePath}）")
    }

    private fun code(text: String): String =
        text.replace(Regex("""/\*[\s\S]*?\*/"""), "").replace(Regex("""//[^\n]*"""), "")

    /** `painterResource(R.mipmap.…)` —— 唯一会崩的写法（自适应图标 XML 不可绘制）。 */
    private val banned = Regex("""painterResource\s*\(\s*R\s*\.\s*mipmap\s*\.\s*\w+""")

    @Test
    fun `不许用 painterResource 加载 mipmap —— 自适应图标 XML 会让首帧组合直接崩`() {
        val root = repoRoot()
        val offenders = mutableListOf<String>()
        listOf("app", "core").forEach { top ->
            File(root, top).walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .filter { it.path.contains("${File.separator}src${File.separator}main${File.separator}") }
                .forEach { file ->
                    val stripped = code(file.readText())
                    if (banned.containsMatchIn(stripped)) {
                        offenders += file.relativeTo(root).path.replace(File.separator, "/")
                    }
                }
        }
        assertTrue(
            "这些文件用 painterResource 加载了 mipmap：$offenders —— " +
                "mipmap/ic_launcher.xml 是 adaptive-icon（不是 VectorDrawable），" +
                "painterResource 会抛 IllegalArgumentException 并在首帧组合里闪退（2026-10-22 事故）。" +
                "要画图标请用 drawable/ 下的矢量（如 R.drawable.ic_launcher_foreground）。",
            offenders.isEmpty()
        )
    }

    @Test
    fun `启动页图标必须引用前景层矢量`() {
        val src = code(source("app/src/main/java/com/ufi_axis/app/startup/UfiStartupOverlay.kt"))
        assertTrue(
            "启动页图标必须用 R.drawable.ic_launcher_foreground（矢量，可被 painterResource 绘制）",
            "painterResource(R.drawable.ic_launcher_foreground)" in src
        )
    }
}
