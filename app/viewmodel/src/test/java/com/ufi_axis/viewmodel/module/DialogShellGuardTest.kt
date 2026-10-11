package com.ufi_axis.viewmodel.module

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    /**
     * 2026-10-11：`NetworkModeDialog` 不得用 [UfiSingleChipSelector]。
     *
     * 它的名字里有 "Chip"，实际是**分段控件**：轨道底色 + `UfiSegment` + 滑块动画，
     * 滑块几何由 `onSizeChanged` 量出的段宽算出（UfiChipSelector.kt:135-147）。
     * 6 个制式档位在 FlowRow 里宽度各异 → 几何失效、滑块飞掉，表现就是「界面错乱」。
     * 真正的 chip 是 `CategoryChip`（描边 + 淡底 + 选中填色），不依赖几何计算。
     */
    @Test
    fun networkModeDialog_mustNotUseSegmentedControlAsChip() {
        val code = executableCode(source("feature-network/src/main/java/com/ufi_axis/ui/screens/NetworkModeDialog.kt"))
        assertFalse(
            "网络制式档位必须用 CategoryChip 平铺，不能用分段控件 UfiSingleChipSelector",
            code.contains("UfiSingleChipSelector")
        )
        assertTrue(
            "应改用真正的 chip 组件 CategoryChip",
            code.contains("CategoryChip(")
        )
    }

    /**
     * 2026-10-11：制式名必须 `maxLines = 1`，否则长文案折行会让左栏高度跳变，
     * 与右边恒为两行的 2×2 网格对不齐 —— 用户报的「没有固定布局」。
     */
    @Test
    fun networkModeDialog_modeLabel_mustStaySingleLine() {
        val code = executableCode(source("feature-network/src/main/java/com/ufi_axis/ui/screens/NetworkModeDialog.kt"))
        val modeText = code.substringAfter("text = modeLabel").take(400)
        assertTrue(
            "制式名必须锁单行（maxLines = 1），否则布局高度随文案长度跳变",
            modeText.contains("maxLines = 1")
        )
        assertFalse(
            "制式名不能允许折行（maxLines = 2）",
            modeText.contains("maxLines = 2")
        )
    }

    /**
     * 2026-10-11：信号等级**不得**在本文件里手写 dBm 阈值。
     *
     * `signalLevelText` / `signalBars` 是唯一分档真源（`UfiSignalBars.kt:20-42`，
     * 5 档：-80/-90/-100/-115），其 KDoc 明确写「与外部调用点同源，别分叉」。
     * 本轮曾手写一套 `-90/-105`，结果把「差」错标成 INFO —— 与真源分档不一致，
     * 同一个 RSRP 在别处显示「差」而这里显示「一般」。
     */
    @Test
    fun networkModeDialog_mustNotForkSignalThresholds() {
        val code = executableCode(
            source("feature-network/src/main/java/com/ufi_axis/ui/screens/NetworkModeDialog.kt")
        )
        assertFalse(
            "信号等级必须走 signalLevelText / signalBars，不得手写 dBm 阈值",
            Regex("rsrp\\s*>=\\s*-\\d+").containsMatchIn(code)
        )
        assertTrue(
            "应复用公共分档函数 signalLevelText",
            code.contains("signalLevelText(")
        )
    }

    /**
     * 2026-10-11：`CellularAdvancedScreen` 的根 Column **不得**再加
     * `padding(horizontal = CardHorizontalMargin)`。
     *
     * [UfiSettingsRowCard] 内部**自带**同样的外边距（`UfiSettingsGroup.kt:64`），
     * 两者叠加成 32dp —— 表现就是「DHCP / 禁用 FOTA / 文件共享 左右间隔过大」。
     * 而 [UfiEntryCard] 走的是**内**边距（`padding(Spacing.InnerPadding)`）、不带外边距，
     * 所以根 Column 加外边距会让两种卡左右边界天然不对齐。
     * 边距只能由各卡片组件自己负责，根 Column 只管纵向节奏。
     */
    @Test
    fun cellularAdvancedScreen_rootColumn_mustNotDoublePadHorizontally() {
        val code = executableCode(
            source("feature-network/src/main/java/com/ufi_axis/ui/screens/CellularAdvancedScreen.kt")
        )
        assertFalse(
            "根 Column 不得横向加 CardHorizontalMargin——UfiSettingsRowCard 自带，会叠成 32dp",
            Regex("padding\\(horizontal = Spacing\\.CardHorizontalMargin\\)").containsMatchIn(code)
        )
    }

    /**
     * 2026-10-11：四个二级入口必须与**网络设置页**同形态：各自独立一张
     * [UfiSettingsRowCard] + [UfiSettingsItem] + [UfiSettingsChevron]，不共用一张卡壳。
     *
     * 曾一度误改成 `UfiEntryCard` —— 它是**列表入口卡**（Surface 卡壳 + 自带右箭头 +
     * 徽标，隧道/通道列表用），而全站设置类页面的入口行口径是 UfiSettingsItem +
     * UfiSettingsChevron（见 NetworkScreen）。同一类东西必须同一个组件。
     */
    @Test
    fun cellularAdvancedScreen_entries_mustUseEntryCard() {
        val code = executableCode(
            source("feature-network/src/main/java/com/ufi_axis/ui/screens/CellularAdvancedScreen.kt")
        )
        // 2026-10-11 修正：入口行的正确形态是 UfiSettingsRowCard + UfiSettingsItem +
        // UfiSettingsChevron，与 NetworkScreen 的「蜂窝网络高级设置 / 网速测试 / 在线设备」
        // 三行完全一致。上一轮我断言 UfiEntryCard 是错的 —— 那套是隧道/通道列表用的
        // Surface 卡壳 + 自带箭头，和全站设置类页面的入口行不是同一个东西。
        assertFalse(
            "入口行不该用 UfiEntryCard（那是隧道/通道列表的 Surface 卡壳）；" +
                "应与 NetworkScreen 一致：UfiSettingsItem + UfiSettingsChevron",
            code.contains("UfiEntryCard(")
        )
        // 必须**四个入口都**带 Chevron：上一轮只断言 contains()，结果注入删掉一行的
        // 场景仍然通过（还剩三行），护栏等于没生效。这里改成数量断言。
        val chevrons = Regex("UfiSettingsChevron\\(\\)").findAll(code).count()
        assertEquals(
            "四个二级入口都应带 UfiSettingsChevron（实际 $chevrons 处）",
            4, chevrons
        )
        val entryRows = Regex("UfiSettingsItem\\(").findAll(code).count()
        assertTrue("应至少 4 个 UfiSettingsItem 入口行（实际 $entryRows）", entryRows >= 4)
        // 手搓卡壳（曾用于把四个入口挤在一张卡里）不应再出现
        assertFalse(
            "四个入口已各自独立成卡，不该再手搓合一卡壳 Box(ufiStandardCard()...)",
            code.contains("ufiStandardCard()")
        )
    }

    /** 剥掉注释，只留可执行代码（KDoc 里反复出现 `UfiCustomDialog` 这些名字）。 */
    private fun executableCode(text: String): String =
        text.replace(Regex("""/\*[\s\S]*?\*/"""), "")
            .lines()
            .joinToString("\n") { it.substringBefore("//") }
}