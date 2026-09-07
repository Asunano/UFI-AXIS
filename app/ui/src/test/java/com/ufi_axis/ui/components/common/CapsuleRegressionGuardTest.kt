package com.ufi_axis.ui.components.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 悬浮胶囊导航栏「整屏模糊 + 无切换动画」两个真机 bug 的**回归护栏**。
 *
 * ## 为什么是源码级断言，而不是行为断言
 * 被修复的三个不变量分别是：
 * 1. `RAPID_THRESHOLD_MS` —— `private const val`，无任何运行期出口；
 * 2. `SELECTED_ICON_SCALE` 的**缺席**（选中放大已被着色滑块取代）—— 同样没有运行期出口；
 * 3. 「Popup 路径绝不写 `FLAG_BLUR_BEHIND`」—— 需要真实 `WindowManager` 才能观测。
 *
 * 要在行为层面验证它们，必须引入 Compose UI Test + Robolectric；而 `:app:ui` 模块当前
 * 只声明了 `testImplementation(libs.junit)`（见 `app/ui/build.gradle.kts`），为一次
 * bugfix 回归而扩张构建依赖不划算，也会把本次改动面从 3 个文件放大到构建脚本。
 *
 * 因此这里退一步做**源码不变量护栏**：纯 JVM 文件读取 + 正则，零依赖、毫秒级，
 * 目的不是"证明动画好看"，而是**锁死回归**——一旦有人把阈值调回 1000ms、把图标缩放
 * 调回 1.05f，或在 Popup 分支重新引入 `FLAG_BLUR_BEHIND`，CI 立刻红灯并附带原因说明。
 *
 * 若将来 `:app:ui` 接入了 Compose UI Test，应把本文件替换为真正的交互测试
 * （`composeTestRule.onNodeWithContentDescription(...).performClick()` + 帧推进断言）。
 */
class CapsuleRegressionGuardTest {

    // ── 被守护的源文件 ──────────────────────────────────────────────────────

    private val tabBarPath =
        "src/main/java/com/ufi_axis/ui/components/common/UfiCapsuleTabBar.kt"

    private val blurHostPath =
        "src/main/java/com/ufi_axis/ui/components/common/UfiCapsuleBlurHost.kt"

    private val navGraphPath =
        "src/main/java/com/ufi_axis/ui/navigation/MainNavGraph.kt"

    /**
     * 胶囊 Dialog 的**窗口主题**所在文件（`:app` 模块，Activity 主题 `Theme.UFIAXIS`）。
     *
     * 本轮 bugfix 的命门全在 `android:dialogTheme` 指到的 `UfiDialogWindowTheme` 上：
     * 缺 `windowIsTranslucent=true` 会让 `Window.setBackgroundBlurRadius` 被平台静默清零
     * （BCR=111 却零模糊），缺 `windowBackground=transparent` / `windowElevation=0dp` 会露出
     * 浅色外框与底部黑影。该文件若被改动，模糊会**静默死亡**——故单独守卫。
     */
    private val themePath =
        "src/main/res/values/themes.xml"

    /**
     * 定位模块源码文件。
     *
     * Gradle 单测的工作目录默认是**模块目录**（`app/ui`），但 IDE / 其他 runner 可能从
     * 仓库根启动，因此从当前目录逐级上溯，并同时尝试 `app/ui/` 前缀，两种布局都能命中。
     */
    private fun source(relative: String): String {
        var dir: File? = File(".").absoluteFile.normalize()
        while (dir != null) {
            for (candidate in listOf(File(dir, relative), File(dir, "app/ui/$relative"))) {
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
     * 剥掉块注释与行注释，只留**可执行代码**。
     *
     * 这三个文件里 `FLAG_BLUR_BEHIND` / `fillMaxWidth` 都被大量写进"为什么不能这么做"的
     * 注释里，直接全文搜索必然误报；必须先去注释再断言。
     */
    private fun executableCode(text: String): String =
        text.replace(Regex("""/\*[\s\S]*?\*/"""), "")
            .lines()
            .joinToString("\n") { it.substringBefore("//") }

    private fun matchOne(code: String, pattern: String, what: String): String {
        val m = Regex(pattern).find(code)
            ?: throw AssertionError("在源码中找不到 $what（正则：$pattern），该常量可能已被重命名或删除。")
        return m.groupValues[1]
    }

    /**
     * 截取某个**顶层函数**的函数体（从签名之后到收尾 `}` 之前），把断言限定在单个作用域内。
     *
     * 为什么需要它：像「不得出现 `return 0`」这类断言，如果对整文件搜索必然误伤其它函数
     * （例如 `queryCrossWindowBlurEnabled` 里的兜底 return）。
     *
     * 约定：Kotlin 顶层函数的收尾 `}` 一定顶格在第 0 列，据此判定作用域结束。
     * 传入的 [code] 必须是 [executableCode] 处理过的（已去注释），否则 KDoc 里的示例
     * 会被误当成函数体。
     */
    private fun functionBody(code: String, signaturePrefix: String): String {
        val after = code.substringAfter(signaturePrefix, "")
        if (after.isEmpty()) {
            throw AssertionError("在源码中找不到函数 `$signaturePrefix`，它可能已被重命名或删除。")
        }
        return after.lineSequence()
            .takeWhile { it.trimEnd() != "}" }
            .joinToString("\n")
    }

    /**
     * 剥掉 XML 注释（`<!- - … - ->`），只留**标签内容**。
     *
     * `themes.xml` 的 `<item>` 声明在注释里被反复引用（解释为什么必须 translucent），
     * 直接全文搜索 `windowIsTranslucent` 必然误报；必须先去注释再断言。
     */
    private fun xmlExecutable(text: String): String =
        text.replace(Regex("""<!--[\s\S]*?-->"""), "")

    // ── Bug 2：切 Tab 没有动画 ──────────────────────────────────────────────

    /**
     * 选中态只能靠**着色滑块 + 图标着色**表达，图标自身不得再做缩放。
     *
     * 2026-09-03：滑块（`CapsuleSelectionSlider`）落地后，1.15× 的选中放大成了纯负担 ——
     * 放大后的图标会顶出滑块边界，出现"图标溢出着色框"的观感。本条同时封死旧常量，
     * 因为改回去只需加一行 `scale(...)`，评审时极易被当成"顺手补的动效"放过。
     */
    @Test
    fun selectedIcon_mustRelyOnSliderNotScaling() {
        val code = executableCode(source(tabBarPath))

        assertFalse(
            "UfiCapsuleTabBar 里又出现了 SELECTED_ICON_SCALE —— 选中放大会让图标顶出着色滑块",
            code.contains("SELECTED_ICON_SCALE")
        )
        assertTrue(
            "找不到 CapsuleSelectionSlider —— 选中态的唯一视觉载体（着色滑块）被移除了",
            code.contains("CapsuleSelectionSlider")
        )
        assertTrue(
            "滑块位移没有走 indicatorPos × tabStepPx —— 它必须跟着选中项/手势进度横向滑动",
            Regex("""indicatorPos\.value\s*\*\s*tabStepPx""").containsMatchIn(code)
        )
    }

    /**
     * 图标缩放 / 颜色 / 透明度必须是**连续过渡**，不能退化成瞬变。
     *
     * 实现形态已换代：原来是三个时长常量（`ICON_SCALE_MS` / `COLOR_TRANSITION_MS` /
     * `ICON_ALPHA_MS`）各驱动一个 `animateXxxAsState` 的 tween；现在三者统一由
     * `selectionFactor()`（Pager 的连续页进度）线性插值驱动 —— 不再有"动画时长"这回事，
     * 手指滑到哪就渲染到哪，可打断、可反向。
     *
     * 因此断言从「时长 > 0」改为「三条通道都真的挂在 factor 上」：
     * 只要有人把其中任何一条写成 `if (selected) A else B` 的硬切，本条立刻红灯。
     * 另外守住 `EXPAND_SPRING` 不得被换成 `snap()`（展开/收起若瞬变，整体收缩交互失去意义）。
     */
    @Test
    fun iconTransitions_mustBeDrivenByContinuousFactor() {
        val code = executableCode(source(tabBarPath))

        assertTrue(
            "图标颜色没有按 factor 做 lerp —— 选中色会硬切，滑动过程中不再实时联动",
            // 2026-09-03：滑块改成实心 accent 后，选中色由 accent 换成 onAccent（反白），
            // 变量随之改名 accentColor → selectedColor。本条守的是"必须按 factor 插值"，
            // 不是守某个具体颜色名。
            Regex("""lerp\(\s*unselectedColor\s*,\s*selectedColor\s*,\s*factor\s*\)""")
                .containsMatchIn(code)
        )
        assertTrue(
            "图标透明度没有按 factor 插值（应为 ICON_ALPHA_UNSEL + (1f - ICON_ALPHA_UNSEL) * factor）",
            Regex("""1f\s*-\s*ICON_ALPHA_UNSEL\s*\)\s*\*\s*factor""").containsMatchIn(code)
        )
        assertTrue(
            "找不到 selectionFactor —— 连续选中进度是上面三条通道的唯一驱动源",
            code.contains("selectionFactor")
        )
        assertTrue(
            "EXPAND_SPRING 必须是 spring(...)；换成 snap() 会让展开/收起瞬变，整体收缩交互失去意义",
            Regex("""EXPAND_SPRING[^\n=]*=\s*[\s\S]{0,40}?spring\(""").containsMatchIn(code)
        )
    }

    // ── Bug 1：整屏模糊泄漏 ─────────────────────────────────────────────────

    /**
     * 胶囊模糊宿主的**可执行代码**中绝不能出现 `FLAG_BLUR_BEHIND` / `blurBehindRadius`。
     *
     * 回归场景：这两个 API 的语义是「模糊窗口背后的**整个屏幕**」（官方文档明确区分于
     * `Window.setBackgroundBlurRadius` 的"仅窗口矩形内"），正是真机截图里整页被糊的根因。
     * 注意：文件注释里保留了大量相关说明，所以必须只对去注释后的代码断言。
     */
    @Test
    fun capsuleBlurHost_mustNeverUseWholeScreenBlurApis() {
        val code = executableCode(source(blurHostPath))

        assertFalse(
            "UfiCapsuleBlurHost 的可执行代码中出现了 FLAG_BLUR_BEHIND —— 它会模糊整屏，正是本次修复的 bug",
            code.contains("FLAG_BLUR_BEHIND")
        )
        assertFalse(
            "UfiCapsuleBlurHost 的可执行代码中出现了 blurBehindRadius —— 同样是整屏模糊语义",
            code.contains("blurBehindRadius")
        )
        assertFalse(
            "模糊已彻底移除，UfiCapsuleBlurHost 的可执行代码中不应再出现 setBackgroundBlurRadius",
            code.contains("setBackgroundBlurRadius")
        )
    }

    /**
     * Popup 分支（`applyWindowBlur` 的最后一条 return）必须恒返回 false。
     *
     * Popup 没有 `Window` 对象，公开 API 拿不到"只糊窗口内"的能力；这里返回 false 让
     * [UfiCapsuleTabBar] 走视觉磨砂降级，而不是退回整屏模糊。
     */
    @Test
    fun applyWindowBlur_popupBranch_mustBailOutWithFalse() {
        val code = executableCode(source(blurHostPath))
        val body = code.substringAfter("private fun applyWindowBlur", "")
        assertTrue("找不到 applyWindowBlur 函数，签名可能已变更", body.isNotEmpty())

        // 函数体最后一条语句必须是 `return false`（Popup 兜底分支）。
        val lastReturn = body.lines()
            .map { it.trim() }
            .last { it.startsWith("return ") }
        assertEquals(
            "applyWindowBlur 的 Popup 兜底分支必须 `return false`（放弃真模糊），当前是：$lastReturn",
            "return false",
            lastReturn
        )
    }

    // ── 触摸穿透红线：胶囊不得撑满宽度 ──────────────────────────────────────

    /**
     * 胶囊本体不得 `fillMaxWidth()`。
     *
     * 它活在 Popup 里，撑满宽度会让 Popup 窗口横贯整屏、吞掉整条底部触摸区域 ——
     * 「胶囊之外的点击必须穿透到下方页面」是产品红线。
     */
    @Test
    fun capsule_mustNotFillMaxWidth_soTouchesPassThrough() {
        val code = executableCode(source(tabBarPath))
        assertFalse(
            "UfiCapsuleTabBar 的可执行代码中出现了 fillMaxWidth()，会让 Popup 窗口横贯整屏并吞掉底部点击",
            code.contains("fillMaxWidth")
        )
    }

    // ── 公开 API 红线：签名不得变更 ─────────────────────────────────────────

    /** [UfiCapsuleTabBar] / [CapsuleBlurHost] / [CapsuleTabItem] 的公开签名必须保持稳定。 */
    @Test
    fun publicApi_signatures_mustRemainStable() {
        val tabBar = source(tabBarPath)
        val blurHost = source(blurHostPath)

        assertTrue(
            "UfiCapsuleTabBar 的参数列表被改动了（应为 tabs / selectedIndex / onTabSelected / modifier = Modifier）",
            Regex(
                """fun\s+UfiCapsuleTabBar\s*\(\s*tabs:\s*List<CapsuleTabItem>\s*,\s*""" +
                    """selectedIndex:\s*Int\s*,\s*onTabSelected:\s*\(Int\)\s*->\s*Unit\s*,\s*""" +
                    """modifier:\s*Modifier\s*=\s*Modifier\s*\)"""
            ).containsMatchIn(tabBar)
        )
        assertTrue(
            "CapsuleTabItem 的字段被改动了（应为 key / label / icon）",
            Regex(
                """data\s+class\s+CapsuleTabItem\s*\(\s*val\s+key:\s*String\s*,\s*""" +
                    """val\s+label:\s*String\s*,\s*val\s+icon:\s*ImageVector\s*\)"""
            ).containsMatchIn(tabBar)
        )
        assertTrue(
            "CapsuleBlurHost 的签名被改动了（应为 content: @Composable () -> Unit）",
            Regex("""fun\s+CapsuleBlurHost\s*\(\s*content:\s*@Composable\s*\(\)\s*->\s*Unit\s*\)""")
                .containsMatchIn(blurHost)
        )
    }

    // ── 整体收缩 / 展开状态机（本轮交互改造）────────────────────────────────

    /**
     * 自动收起延迟必须是 1500ms。
     *
     * 这是产品明确定的交互契约：「点击后展开，1.5 秒无操作自动收起」。
     * 调小会让用户还没看完标签就缩回去，调大则胶囊长时间占着放大态遮挡内容。
     */
    @Test
    fun collapseDelay_mustBe1500ms() {
        val code = executableCode(source(tabBarPath))
        val delayMs = matchOne(
            code, """COLLAPSE_DELAY_MS\s*=\s*(\d+)L""", "COLLAPSE_DELAY_MS"
        ).toLong()
        assertEquals("自动收起延迟应为 1500ms（产品定义的交互契约）", 1500L, delayMs)
    }

    /**
     * 收起态的整体缩放必须「看得出来」又「看得清」。
     *
     * 低于 0.80f：20dp 图标被缩到 16dp 以下，边缘发糊且点击目标偏小；
     * 高于 0.92f：与展开态差异不足 8%，用户分辨不出收起/展开两个状态，改造等于白做。
     */
    @Test
    fun collapsedScale_mustBePerceptibleButLegible() {
        val code = executableCode(source(tabBarPath))
        val scale = matchOne(
            code, """COLLAPSED_SCALE\s*=\s*([\d.]+)f""", "COLLAPSED_SCALE"
        ).toFloat()

        assertTrue(
            "COLLAPSED_SCALE($scale) 低于 0.80f 时图标发糊、点击目标过小",
            scale >= 0.80f
        )
        assertTrue(
            "COLLAPSED_SCALE($scale) 高于 0.92f 时收起态与展开态肉眼无差别，整体收缩交互失去意义",
            scale <= 0.92f
        )
    }

    /**
     * 收起倒计时必须**每次点击都重新计时**。
     *
     * 实现手段（2026-09-05 第四版起）：`resetToken` 是自动收起计时器判据快照
     * `CapsuleCollapseProbe` 的一个字段，点击时无条件自增 —— 新值到达即让 `collectLatest`
     * 取消上一次 body、让 `delay(COLLAPSE_DELAY_MS)` 从头开始。
     *
     * ## 为什么本护栏被改写（原来断言的是 effect 的 key）
     * 旧实现是 `LaunchedEffect(expanded, resetToken, isScrolling, isDragging)`，护栏因此
     * 直接检查 key 列表里有 `expanded` 与 `resetToken`。但那份 key 里的 `isScrolling`
     * 是**组合期读**，横滑一次翻转两下就是 `UfiCapsuleTabBar` 整体重组两次（跨窗口渲染，
     * 且正好落在 settle 后仍有可见运动的窗口里）—— 见
     * [settleDrivers_mustNotReadIsScrollingDuringComposition]。判据整组挪进协程后，
     * key 已不再是这条不变量的载体，**探针字段 + collectLatest** 才是。
     * 被守护的行为一字未改：任何一次点击都必须让倒计时重新开始。
     *
     * 回归场景：若有人把探针里的 `resetToken` 删掉（"反正 expanded 已经在里面了"），
     * 展开态下的再次点击就不会产生新值，倒计时继续跑，胶囊会在用户还在连续点 Tab 时突然缩回去。
     */
    @Test
    fun expandTimer_mustResetOnEveryTap() {
        val code = executableCode(source(tabBarPath))

        assertTrue(
            "找不到 resetToken —— 「1.5s 内再次点击则重新计时」的实现载体被移除了",
            code.contains("resetToken")
        )

        // `(?<!class\s)`：跳过 `private data class CapsuleCollapseProbe(...)` 那个**声明**，
        // 只看真正的构造点（声明里的字段名同样含 expanded / resetToken，会误命中）。
        val probe = Regex("""(?<!class\s)CapsuleCollapseProbe\(([\s\S]*?)\)""")
            .findAll(code)
            .firstOrNull { m ->
                val fields = m.groupValues[1]
                fields.contains("expanded") && fields.contains("resetToken")
            }
        assertNotNull(
            "自动收起计时器的判据快照 CapsuleCollapseProbe 必须同时含 expanded 与 resetToken，" +
                "否则展开态下的再次点击不会产生新值、delay 不会重新开始计时",
            probe
        )

        // 该判据必须真的驱动一次「可被打断的倒计时」：
        // distinctUntilChanged ≡ 原来的「key 是否变化」，collectLatest ≡「key 变了就取消重启」。
        val body = code.substring(probe!!.range.last)
            .substringBefore("LaunchedEffect")
        assertTrue(
            "CapsuleCollapseProbe 之后没有 `distinctUntilChanged()` —— 判据没变也会重跑倒计时。",
            body.contains("distinctUntilChanged()")
        )
        assertTrue(
            "CapsuleCollapseProbe 之后没有 `collectLatest` —— 少了它，新值到达时上一次的 " +
                "delay 不会被取消，「再次点击重新计时」就失效了（旧实现靠 effect 重启达到同样效果）。",
            body.contains("collectLatest")
        )
        assertTrue(
            "自动收起的判据链里没有 delay(COLLAPSE_DELAY_MS)，倒计时并未生效",
            body.contains("delay(COLLAPSE_DELAY_MS)")
        )
        assertTrue(
            "收起条件必须读探针字段 `probe.expanded` —— 读闭包捕获的组合值会一直停在" +
                "首次组合那一份（本 effect 不再随组合重启）。",
            body.contains("probe.expanded")
        )
    }

    /**
     * 旧的「每个 Tab 各自一套展开进度」机制必须彻底清除。
     *
     * 新交互是**整个胶囊**一个布尔状态；旧机制（按选中项逐个驱动 `Animatable`、
     * 用时间戳判定连点、只给选中项显示标签）与之语义冲突，两套并存必然打架。
     */
    @Test
    fun legacyPerTabExpansionMachinery_mustBeGone() {
        val code = executableCode(source(tabBarPath))

        listOf(
            "LABEL_HOLD_MS" to "已由 COLLAPSE_DELAY_MS 取代",
            "RAPID_THRESHOLD_MS" to "整体状态机不需要连点 snapTo 兜底，弹簧被打断即可平滑重定向",
            "updateTabExpansion" to "per-tab 进度批量驱动函数已废弃",
            "showLabel" to "标签可见性现在只由 expanded 决定（展开态 5 个标签全显）"
        ).forEach { (symbol, why) ->
            assertFalse(
                "UfiCapsuleTabBar 里仍残留旧机制符号 `$symbol` —— $why",
                code.contains(symbol)
            )
        }
    }

    // ── Popup → Dialog 迁移后的窗口层红线 ───────────────────────────────────

    /**
     * 承载胶囊的 Dialog 窗口必须保持触摸穿透。
     *
     * `FLAG_NOT_FOCUSABLE`：不抢焦点，否则会吞掉返回键并弹出/收起输入法；
     * `FLAG_NOT_TOUCH_MODAL`：窗口矩形之外的触摸事件下发给下层页面 ——
     * 「胶囊之外的点击必须穿透」是产品红线，Dialog 默认是模态的，不显式清除必然回归。
     */
    @Test
    fun dialogHost_mustKeepTouchPassThroughFlags() {
        val code = executableCode(source(blurHostPath))

        assertTrue(
            "Dialog 宿主缺少 FLAG_NOT_FOCUSABLE —— 胶囊窗口会抢焦点并吞掉返回键",
            code.contains("FLAG_NOT_FOCUSABLE")
        )
        assertTrue(
            "Dialog 宿主缺少 FLAG_NOT_TOUCH_MODAL —— 胶囊之外的点击将无法穿透到下方页面",
            code.contains("FLAG_NOT_TOUCH_MODAL")
        )
    }

    /**
     * 必须显式覆盖 Dialog 的窗口背景。
     *
     * Dialog 默认带一层半透明 dim + 系统 windowBackground，不 `setBackgroundDrawable`
     * 覆盖掉，胶囊后面会糊上一块灰底，真模糊也就无从谈起。
     */
    @Test
    fun dialogHost_mustOverrideTransparentWindowBackground() {
        val code = executableCode(source(blurHostPath))
        assertTrue(
            "Dialog 宿主没有调用 setBackgroundDrawable —— 系统默认窗口背景会在胶囊后面留下灰底",
            code.contains("setBackgroundDrawable")
        )
    }

    /** 胶囊窗口必须锚定在底部居中，否则会跑到屏幕中央（Dialog 的默认 gravity）。 */
    @Test
    fun dialogHost_mustAnchorBottomCenter() {
        val code = executableCode(source(blurHostPath))

        assertTrue(
            "Dialog 宿主缺少 Gravity.BOTTOM —— 胶囊会停在屏幕中央",
            code.contains("Gravity.BOTTOM")
        )
        assertTrue(
            "Dialog 宿主缺少 Gravity.CENTER_HORIZONTAL —— 胶囊不会水平居中",
            code.contains("Gravity.CENTER_HORIZONTAL")
        )
    }

    /**
     * 胶囊窗口必须保持 wrap-content，且不得退回 Popup。
     *
     * `usePlatformDefaultWidth = false` 是关键：不设它，Dialog 会被强制拉到平台默认宽度
     * （接近整屏宽），窗口横贯底部并吞掉整条触摸区域 —— 与 `fillMaxWidth` 是同一个 bug 的
     * 另一种触发方式。同时 `Popup(` 不得回归：Popup 没有 Window 对象，拿不到窗口内背景模糊。
     */
    @Test
    fun capsuleWindow_mustStayWrapContent() {
        val code = executableCode(source(navGraphPath))

        assertTrue(
            "胶囊不再由 Dialog 承载 —— Popup 路径拿不到窗口内背景模糊，真模糊会永久失效",
            code.contains("Dialog(")
        )
        assertTrue(
            "缺少 usePlatformDefaultWidth = false —— Dialog 会被拉到平台默认宽度，" +
                "窗口横贯底部并吞掉整条触摸区域",
            Regex("""usePlatformDefaultWidth\s*=\s*false""").containsMatchIn(code)
        )
        assertFalse(
            "MainNavGraph 里又出现了 Popup( —— 已迁移到 Dialog，不得回退",
            code.contains("Popup(")
        )
    }

    /**
     * 把窗口背景清成 null 之后必须显式 `setFormat(PixelFormat.TRANSLUCENT)`。
     *
     * 回归场景：上一版把背景改为 null 去 1px 边，但没显式钉像素格式，导致部分 ROM 把
     * 「无背景 drawable」的浮窗 Surface 渲染成不透明黑色（用户截图里胶囊底部的纯黑矩形、
     * 盖住下方红色按钮）。此处只做源码级字符串匹配（去注释后的可执行代码里必须出现
     * `setFormat(PixelFormat.TRANSLUCENT)`），不引入反射 / 真机，避免脆弱。
     */
    @Test
    fun dialogHost_mustSetTranslucentPixelFormatAfterClearingBackground() {
        val code = executableCode(source(blurHostPath))
        assertTrue(
            "applyWindowBlur 清掉背景后没有显式 setFormat(PixelFormat.TRANSLUCENT) —— " +
                "部分 ROM 会把无背景浮窗渲染成黑色、盖住下方内容",
            code.contains("setFormat(PixelFormat.TRANSLUCENT)")
        )
    }

    // ── 跨设备底部定位：测量源 + 响应式状态机红线 ────────────────────────────
    //
    // 背景：胶囊在不同机型 / 导航模式下位置漂移（贴死底边、压手势条、横屏悬空）。
    // 三条根因分别由下面三个测试锁死；第四个测试守 inset 派发链路不被吃掉。

    /**
     * 底部预留区的测量必须**同时**覆盖 `navigationBars` 与 `systemGestures`。
     *
     * 守护根因 R2：手势导航下 `navigationBars().bottom` 只描述小白条那条**绘制带**，
     * 而系统真正拦截上滑手势的**热区**（`systemGestures().bottom`）通常更高。只避开前者，
     * 胶囊底部就会落进手势热区里 —— 点不动、或一点就触发返回桌面。
     *
     * 回归场景：有人觉得 `systemGestures` 是"冗余的一路读数"顺手删掉，胶囊立刻回到
     * 压手势条的状态，而这在纯手势机型上才复现，代码评审很难发现。
     */
    @Test
    fun bottomInsetSource_mustCoverGestureArea() {
        val code = executableCode(source(blurHostPath))

        assertTrue(
            "UfiCapsuleBlurHost 的可执行代码里找不到 Type.navigationBars() —— " +
                "底部预留区必须至少覆盖系统导航栏",
            code.contains("Type.navigationBars()")
        )
        assertTrue(
            "UfiCapsuleBlurHost 的可执行代码里找不到 Type.systemGestures() —— 根因 R2 回归：" +
                "手势导航下手势热区高于 navigationBars，只读后者会让胶囊落进热区（点不动 / 误触返回桌面）。" +
                "预留区必须取 max(navigationBars, systemGestures)。",
            code.contains("Type.systemGestures()")
        )
    }

    /**
     * `readBottomReservedPx` 的**任何**兜底分支都不得回 0，必须回落到保底常量。
     *
     * 守护根因 R1：旧实现 `readActivityNavigationBarHeightPx` 在「解析不到宿主 Activity」
     * 与「rootWindowInsets 为 null」两处都 `?: return 0`。视图尚未 attach、或部分 ROM 在
     * 手势模式下把 inset 报成 0 时，胶囊 y 偏移直接塌成 0 —— 贴死屏幕底边 / 压在手势条上，
     * 正是「跨设备定位不一致」最刺眼的那一档。
     *
     * 断言被限定在函数作用域内（见 [functionBody]），避免误伤文件里其它函数的
     * `return 0` / `return false` 兜底。
     */
    @Test
    fun bottomInset_mustNotFallBackToZero() {
        val code = executableCode(source(blurHostPath))
        val body = functionBody(code, "private fun readBottomReservedPx")

        assertFalse(
            "readBottomReservedPx 里出现了 `return 0` —— 根因 R1 回归：" +
                "读不到 inset 时回 0 会让胶囊贴死屏幕底边 / 压在手势条上。" +
                "所有兜底分支必须回 GESTURE_BOTTOM_INSET_FLOOR。",
            Regex("""return\s+0\b""").containsMatchIn(body)
        )
        assertTrue(
            "readBottomReservedPx 里找不到 GESTURE_BOTTOM_INSET_FLOOR —— 保底下限被移除，" +
                "ROM 把 inset 报小 / 视图未 attach 时胶囊会重新贴死底边（根因 R1）",
            body.contains("GESTURE_BOTTOM_INSET_FLOOR")
        )
    }

    /**
     * 预留区状态必须**允许下降**，且必须是响应式的。
     *
     * 守护根因 R3：旧实现用 `if (real > navBarHeightPx) navBarHeightPx = real` 做单调守卫，
     * 值只增不减。于是竖屏转横屏（预留区变小）、三键切手势（48dp → 24dp）之后，
     * 胶囊永远悬在历史最大值上，与实际手势条之间空出一大截。正确写法是
     * `if (next != reservedPx) reservedPx = next`。
     *
     * 同时强制保留 `setOnApplyWindowInsetsListener`：没有它，预留区退化成「组合期读一次」
     * 的静态值，旋转 / 导航模式切换后根本不会更新。
     */
    @Test
    fun bottomInsetState_mustBeReducible() {
        val code = executableCode(source(blurHostPath))

        // 匹配 `if (x > reservedPx)` / `if (reservedPx > x)` 两种书写方向的单调守卫。
        val monotonicGuard = Regex(
            """if\s*\(\s*\w+\s*>\s*(?:navBarHeightPx|reservedPx)\s*\)""" +
                """|if\s*\(\s*(?:navBarHeightPx|reservedPx)\s*>\s*\w+\s*\)"""
        )
        assertFalse(
            "UfiCapsuleBlurHost 里又出现了 `if (x > reservedPx)` 形态的单调守卫 —— 根因 R3 回归：" +
                "只增不减会让胶囊在横屏 / 切换导航模式后悬在历史最大值上。" +
                "必须改成 `if (next != reservedPx)`，允许下降。",
            monotonicGuard.containsMatchIn(code)
        )
        assertTrue(
            "UfiCapsuleBlurHost 里找不到 setOnApplyWindowInsetsListener —— 根因 R3 回归：" +
                "预留区退回「组合期读一次」的静态取值，旋转 / 导航模式切换后不会更新",
            code.contains("setOnApplyWindowInsetsListener")
        )
    }

    /**
     * inset 监听器必须**原样透传** insets，绝不 `CONSUMED`；且取值必须走宿主 Activity。
     *
     * 守护红线 L2：listener 返回 `WindowInsetsCompat.CONSUMED` 会把 inset 在 Dialog 的
     * decorView 处截断，Dialog 内部所有 Compose 布局（`WindowInsets.*`）全部读到 0，
     * 子 view 也再收不到派发 —— 一个装饰性的定位监听会静默破坏整棵子树的 inset 语义。
     *
     * 守护红线 L1：listener 只当「inset 变了」的**信号**，取值必须回 [readBottomReservedPx]
     * （宿主 Activity 的全屏 rootWindowInsets）。若直接采信回调参数 `insets`，就形成
     * 「窗口位置 → 自身 inset 读数 → 再改窗口位置」的闭环，真机表现为胶囊上下抖动。
     */
    @Test
    fun insetListener_mustNotConsume() {
        val code = executableCode(source(blurHostPath))

        val registerAt = code.indexOf("ViewCompat.setOnApplyWindowInsetsListener")
        assertTrue(
            "找不到 ViewCompat.setOnApplyWindowInsetsListener 的注册点 —— 响应式 inset 监听已被移除",
            registerAt >= 0
        )
        // lambda 体：从注册点起，截到 onDispose 里「摘除监听（第二参数传 null）」之前。
        val lambdaScope = code.substring(registerAt)
            .substringBefore("setOnApplyWindowInsetsListener(signalTarget, null)")

        assertFalse(
            "inset listener 内出现了 CONSUMED —— 红线 L2 回归：" +
                "吃掉 inset 会让 Dialog 内所有 Compose 布局读到 0、下游 view 收不到派发。" +
                "必须原样 `return insets`。",
            lambdaScope.contains("CONSUMED")
        )
        assertFalse(
            "UfiCapsuleBlurHost 的可执行代码中出现了 WindowInsetsCompat.CONSUMED（红线 L2）",
            code.contains("WindowInsetsCompat.CONSUMED")
        )
        assertTrue(
            "inset listener 内没有调用 readBottomReservedPx —— 红线 L1 回归：" +
                "取值必须回宿主 Activity 的全屏 rootWindowInsets；直接用回调参数 insets 取值会形成" +
                "「窗口位置 → 自身 inset → 再改窗口位置」的自激振荡，胶囊上下抖动。",
            lambdaScope.contains("readBottomReservedPx")
        )
    }

    // ── 本轮 bugfix 的命门：胶囊 Dialog 的窗口主题（资源侧）────────────────────

    /**
     * 胶囊 Dialog 的窗口主题必须声明 `windowIsTranslucent=true` 与 `windowIsFloating=true`。
     *
     * 这是 `Window.setBackgroundBlurRadius` 在平台侧的**硬性前提**（见 `DecorView
     * .updateBackgroundBlurRadius` 的 `mWindow.isTranslucent()` 门槛）。不满足时所有 API
     * 调用都返回成功（BCR=111），但像素层零模糊——正是真机「胶囊外圈白框 + 底部黑影 + 不糊」
     * 三症状的同一个根因。该前提若被改回 false / 被删除，模糊会**静默死亡**，所以单独守护。
     */
    @Test
    fun capsuleDialogTheme_mustDeclareTranslucentAndFloating() {
        val code = xmlExecutable(source(themePath))

        assertTrue(
            "Theme.UFIAXIS 必须声明 android:dialogTheme，否则胶囊 Dialog 落到默认" +
                "Theme.Material.Light.Dialog（非 translucent）导致模糊静默失效",
            code.contains("android:dialogTheme")
        )
        assertTrue(
            "android:dialogTheme 必须指向 UfiDialogWindowTheme（透明窗口主题，模糊硬性前提）",
            Regex("""name="android:dialogTheme">@style/UfiDialogWindowTheme""")
                .containsMatchIn(code)
        )
        assertTrue(
            "UfiDialogWindowTheme 必须声明 windowIsTranslucent=true（窗口内背景模糊的硬性前提）",
            Regex("""name="android:windowIsTranslucent">true</item>""").containsMatchIn(code)
        )
        assertTrue(
            "UfiDialogWindowTheme 必须声明 windowIsFloating=true（第二个硬性前提）",
            Regex("""name="android:windowIsFloating">true</item>""").containsMatchIn(code)
        )
        assertTrue(
            "UfiDialogWindowTheme 必须声明 windowBackground=@null（用 null 而非透明 ColorDrawable，避免系统给透明 drawable 描 1px 边）",
            Regex("""name="android:windowBackground">@null</item>""")
                .containsMatchIn(code)
        )
        assertTrue(
            "UfiDialogWindowTheme 必须声明 windowElevation=0dp（消除底部黑影）",
            Regex("""name="android:windowElevation">0dp</item>""").containsMatchIn(code)
        )
    }

    // ── 本轮视觉改造：圆角 / 收缩锚点 / 内容层底色 / 视觉抬高 ────────────────
    //
    // 这四项改动的共同特征是「一行就能被改回去，且回归后只在真机上肉眼可见」——
    // 编译不会失败、行为测试也测不出来，最容易在后续重构中被静默还原。
    // 因此逐条用源码级断言锁死，并成对设计（"必须是 X" + "不得是旧的 Y"），
    // 让回退在 CI 上立刻红灯并附带原因。

    /**
     * 胶囊圆角必须同步到 **18dp**。
     *
     * `CAPSULE_WINDOW_CORNER_RADIUS` 是胶囊圆角在窗口侧的**单一真源**（当前只服务于
     * `applyWindowBlur` 里那条已 dead 的真模糊分支），内容层的 `CAPSULE_CORNER`(18)
     * 必须与之同值 —— 两处漂移会在真模糊分支复活时形成「窗口圆角 ≠ 内容圆角」的双层错边。
     *
     * 18dp 是产品拍板的折中值：全局卡片圆角 10dp 对高 ≈56dp 的胶囊过于方正，
     * 满药丸（≈28dp）又与页面卡片语言脱节。
     */
    @Test
    fun bottomInsetCorner_mustSyncTo18dp() {
        val code = executableCode(source(blurHostPath))

        assertTrue(
            "UfiCapsuleBlurHost 里找不到 CAPSULE_WINDOW_CORNER_RADIUS —— " +
                "胶囊圆角的单一真源被重命名或删除了，内容层 CAPSULE_CORNER 将失去比对基准",
            code.contains("CAPSULE_WINDOW_CORNER_RADIUS")
        )
        assertTrue(
            "CAPSULE_WINDOW_CORNER_RADIUS 必须取 18.dp（产品拍板的折中圆角），" +
                "且必须与 UfiCapsuleTabBar 的 CAPSULE_CORNER(18) 保持同一数值",
            Regex("""CAPSULE_WINDOW_CORNER_RADIUS[^\n=]*=\s*18\.dp""").containsMatchIn(code)
        )
    }

    /**
     * 胶囊圆角**不得**回退成药丸半径（30dp）。
     *
     * 与 [bottomInsetCorner_mustSyncTo18dp] 成对：前者钉死新值，本条封死旧值。
     * 30dp ≈ 胶囊半高 + 冗余，会把胶囊描成一颗完整药丸 —— 那是被产品明确否掉的形态，
     * 而且改回去只需动一个字面量、评审时极易被当成"无害的常量微调"放过。
     */
    @Test
    fun bottomInsetCorner_mustNotBePill() {
        val code = executableCode(source(blurHostPath))
        assertFalse(
            "CAPSULE_WINDOW_CORNER_RADIUS 又被改回 30.dp —— 那是被否掉的满药丸半径，" +
                "当前视觉要求是 18dp 的「圆润但不满圆」卡片感",
            Regex("""CAPSULE_WINDOW_CORNER_RADIUS[^\n=]*=\s*30\.dp""").containsMatchIn(code)
        )
    }

    /**
     * 收起/展开的缩放锚点必须是**几何中心**。
     *
     * 产品要求：收起时胶囊四周同时向**自身中心**收拢（整颗变小），
     * 而不是吸附在底边上只往上收。实现手段是根 Box `graphicsLayer` 的
     * `transformOrigin = TransformOrigin.Center`（等价写法 `TransformOrigin(0.5f, 0.5f)` 也放行）。
     *
     * 回归场景：`TransformOrigin` 只是一行赋值，改回底边中点后编译照过、单测照绿，
     * 只有在真机上盯着收起动画才看得出差别。
     */
    @Test
    fun collapseOrigin_mustBeCenter() {
        val code = executableCode(source(tabBarPath))
        assertTrue(
            "UfiCapsuleTabBar 的 graphicsLayer 里找不到 TransformOrigin.Center（或等价的 " +
                "TransformOrigin(0.5f, 0.5f)）—— 收起动画必须以几何中心为锚点等比收拢",
            code.contains("TransformOrigin.Center") ||
                Regex("""TransformOrigin\(\s*0\.5f\s*,\s*0\.5f\s*\)""").containsMatchIn(code)
        )
    }

    /**
     * 收起锚点**不得**退回底边中点。
     *
     * 与 [collapseOrigin_mustBeCenter] 成对锁死：只断言"必须有 Center"不够 ——
     * 若有人新增一处 `TransformOrigin(0.5f, 1f)` 并让它后写生效，前一条仍会绿。
     * `TransformOrigin(0.5f, 1f)` 是被本轮改造替换掉的旧锚点（胶囊底边纹丝不动、
     * 只往上收），与新视觉冲突。
     */
    @Test
    fun collapseOrigin_mustNotBeBottom() {
        val code = executableCode(source(tabBarPath))
        assertFalse(
            "UfiCapsuleTabBar 的可执行代码里又出现了 TransformOrigin(0.5f, 1f) —— " +
                "那是被替换掉的底边中点锚点，会让胶囊收起时只往上收而非向中心收拢",
            Regex("""TransformOrigin\(\s*0\.5f\s*,\s*1(?:\.0)?f\s*\)""").containsMatchIn(code)
        )
    }

    /**
     * 胶囊的淡色底必须画在 **Compose 内容层**，而不是窗口 drawable。
     *
     * 实现形态是根 Box 内、`graphicsLayer` 之内的一个 Box：`Modifier.clip(RoundedCornerShape(CAPSULE_CORNER.dp))`
     * 再填底 —— 填底允许 `.background(capsuleSurfaceColor())`，也允许
     * `.drawBehind { drawRect(…) }`（2026-09-04 起为按展开进度调底色不透明度而改用后者）。
     * 「clip + RoundedCornerShape + 二者之一的填底」是这一形态的指纹 ——
     * 一旦有人"顺手"把底色挪回 `Window.setBackgroundDrawable` 或主题 `windowBackground`，
     * 指纹会从本文件消失，本条立刻红灯。
     *
     * 那条回退路径会复活两个已修复的真机 bug：系统沿 drawable 形状描出的**胶囊外圈 1px
     * 细线**，以及部分 ROM 把浮窗 Surface 退回**不透明黑底**（盖住下方内容）。
     * 窗口背景必须恒为 `null` + `setFormat(PixelFormat.TRANSLUCENT)`。
     *
     * 刻意**不**断言具体颜色字面量：浅色/深色取色属于可调的产品参数，
     * 锁死它会让每次微调配色都要改测试，护栏就会被当成噪音关掉。
     */
    @Test
    fun capsuleBackground_mustBeContentLayer() {
        val code = executableCode(source(tabBarPath))

        listOf(
            ".clip(" to "圆角裁剪（clip 必须在填底之前，保证子内容也被裁进圆角）",
            "RoundedCornerShape(" to "圆角形状（应为 RoundedCornerShape(CAPSULE_CORNER.dp)）"
        ).forEach { (token, what) ->
            assertTrue(
                "UfiCapsuleTabBar 的可执行代码里找不到 `$token` —— 缺少$what。" +
                    "胶囊底色必须活在 Compose 内容层（graphicsLayer 之内、Layout 之外的那层 Box）；" +
                    "若被挪回 Window.setBackgroundDrawable / 主题 windowBackground，" +
                    "会复活「外圈 1px 细线」与「浮窗黑底」两个真机 bug。",
                code.contains(token)
            )
        }

        // 填底本身有两种合法形态，都在内容层，护栏接受任一种：
        // - `.background(capsuleSurfaceColor())`：原始形态；
        // - `.drawBehind { … drawRect(…) }`：2026-09-04 起为了随展开进度调底色不透明度
        //   （CAPSULE_SURFACE_ALPHA_COLLAPSED）而改用的形态 —— 不能用整层 alpha，
        //   那会把图标/文字/滑块一起变透。
        // 若两种都消失，说明底色被挪出了 Compose 内容层，本条立刻红灯。
        assertTrue(
            "UfiCapsuleTabBar 的可执行代码里既没有 `.background(` 也没有 `drawBehind` + `drawRect(` —— " +
                "胶囊底色不再画在 Compose 内容层。它必须活在 graphicsLayer 之内、Layout 之外的那层 Box；" +
                "若被挪回 Window.setBackgroundDrawable / 主题 windowBackground，" +
                "会复活「外圈 1px 细线」与「浮窗黑底」两个真机 bug。",
            code.contains(".background(") ||
                (code.contains("drawBehind") && code.contains("drawRect("))
        )
    }

    /**
     * 视觉抬高量 `CAPSULE_LIFT` 必须真的接进窗口 y 偏移。
     *
     * 只声明常量不接线是最隐蔽的回归形态：常量还在、注释还在，真机上胶囊却没抬起来，
     * 而且 IDE 只会给一个灰色的 unused 提示。因此这里既查**声明**，也查它是否出现在
     * `bottomOffsetPx` 的**赋值表达式**里。
     *
     * 同时守 R3：定位基线三项（`reservedPx` + `capsuleGapFor(...)` − `CAPSULE_SHADOW_ROOM`）
     * 必须原样保留 —— [CAPSULE_LIFT] 是**追加项**，不是替换项。把安全间距 8dp 直接调大
     * 来实现抬高，会把「已验证的跨设备安全下限」和「产品审美参数」混成一个数，
     * 日后无从区分，也无法单独回归。
     */
    @Test
    fun capsuleLift_mustBeWired() {
        val code = executableCode(source(blurHostPath))

        assertTrue(
            "UfiCapsuleBlurHost 里找不到 CAPSULE_LIFT 的声明 —— 产品向的视觉抬高量被删除了",
            Regex("""val\s+CAPSULE_LIFT\s*:\s*Dp\s*=""").containsMatchIn(code)
        )

        val assignAt = code.indexOf("bottomOffsetPx =")
        assertTrue(
            "找不到 bottomOffsetPx 的赋值点（CapsuleWindowMetrics 的构造调用），" +
                "窗口 y 偏移的计算表达式可能已被重写",
            assignAt >= 0
        )
        // 赋值表达式 = 从 `bottomOffsetPx =` 起，到构造调用的下一个具名参数 `cornerRadiusPx` 之前。
        val expression = code.substring(assignAt).substringBefore("cornerRadiusPx")

        assertTrue(
            "CAPSULE_LIFT 已声明却没有出现在 bottomOffsetPx 的表达式里 —— 常量没接线，" +
                "真机上胶囊不会被抬高（当前表达式：${expression.trim()}）",
            expression.contains("CAPSULE_LIFT")
        )

        listOf(
            "reservedPx" to "底部系统预留区高度（跨设备定位的基准）",
            "capsuleGapFor" to "按导航模式分发的安全间距（已验证的 8dp 下限）",
            "CAPSULE_SHADOW_ROOM" to "投影留白扣减项"
        ).forEach { (token, what) ->
            assertTrue(
                "bottomOffsetPx 的表达式里缺少 `$token`（$what）—— 红线 R3 回归：" +
                    "CAPSULE_LIFT 只能**追加**在定位基线三项之上，不得替换其中任何一项，" +
                    "否则跨设备安全间距与产品审美抬高会被混成同一个数",
                expression.contains(token)
            )
        }
    }

    /**
     * 胶囊「实时追踪」接线护栏：MainNavGraph 必须把 Pager 连续进度经
     * onSelectionProgressChange 接回 LocalUfiCapsuleSelectionProgress。
     */
    @Test
    fun capsuleSelectionProgress_mustBeWired() {
        val code = executableCode(source(navGraphPath))
        assertTrue(
            "MainNavGraph 缺少 onSelectionProgressChange 接线 —— 胶囊实时追踪被移除，滑动时不会实时联动",
            code.contains("onSelectionProgressChange")
        )
        assertTrue(
            "MainNavGraph 缺少 LocalUfiCapsuleSelectionProgress provides —— 连续进度未传给胶囊",
            code.contains("LocalUfiCapsuleSelectionProgress provides")
        )
    }

    /**
     * 胶囊窗口的 `WindowManager.LayoutParams` 只许在**一处**被整体赋值，
     * 且赋值前必须有**相等性守卫**。
     *
     * ## 守的是什么（2026-09-05：返回时底部阴影闪烁 / 抽搐）
     * `applyWindowBlur` 会被重放三次（立即 / `view.post` / `delay(100ms)`）。AOSP 里
     * `Window.addFlags` / `clearFlags` / `setDimAmount` / `setLayout` / `attributes=` /
     * `setElevation` / `setFormat` **全部无条件**调用 `dispatchWindowAttributesChanged`，
     * 一路走到 `WindowManager.updateViewLayout` → `ViewRootImpl.scheduleTraversals`。
     * 同一次调用里的多次写入会被合并成一帧，但三次重放落在三个不同的帧上 ⇒
     * pop 期间稳定 3 次 relayout，每次重建窗口 Surface 的投影区，用户就看到阴影抽搐。
     *
     * 因此不变量是：
     * 1. 全文件**只有一处** `.attributes =`（在 `applyCapsuleWindowParams` 里）；
     * 2. 那一处之前必须 `if (settled) return`（同值不写）；
     * 3. 那些"无条件派发"的单属性 setter 不得回归 —— 一旦被"顺手"加回来，相等性守卫就被
     *    绕过，而且 IDE 与评审都看不出问题。
     *
     * ⚠ 修 relayout **不许**改用延后挂载（`delay` + 延迟 `showCapsule`）：那会让胶囊高亮
     * 与实际页面错位，已完整回退，见 `MainNavGraph` 里 `showCapsule` 的说明。
     */
    @Test
    fun capsuleWindowParams_mustBeWrittenOnceWithEqualityGuard() {
        val code = executableCode(source(blurHostPath))

        val wholeWrites = Regex("""\.attributes\s*=(?!=)""").findAll(code).count()
        assertEquals(
            "胶囊窗口的 LayoutParams 必须**只在一处**被整体赋值（applyCapsuleWindowParams），" +
                "当前有 $wholeWrites 处。多点写入 = 多次无条件 attributes 派发 = pop 期间多次 relayout，" +
                "真机表现为返回时底部阴影闪烁 / 抽搐。",
            1,
            wholeWrites
        )

        val body = functionBody(code, "private fun applyCapsuleWindowParams(")
        assertTrue(
            "applyCapsuleWindowParams 里找不到唯一的 `window.attributes =` 整体赋值 —— " +
                "LayoutParams 的单一写入点被挪走了。",
            body.contains("window.attributes =")
        )
        assertTrue(
            "applyCapsuleWindowParams 缺少 `if (settled) return` 相等性守卫 —— " +
                "同值重复写照样触发 WindowManager relayout，三次重放就是三次阴影重绘。",
            body.contains("if (settled) return")
        )

        listOf(
            "addFlags(" to "Window.setFlags 无条件派发 attributes，flags 必须并进那一次整体赋值",
            "clearFlags(" to "同上（清 FLAG_DIM_BEHIND 走 CAPSULE_WINDOW_FLAGS_OFF）",
            "setDimAmount(" to "dimAmount 必须并进那一次整体赋值",
            "setLayout(" to "窗口宽高必须并进那一次整体赋值"
        ).forEach { (token, why) ->
            assertFalse(
                "UfiCapsuleBlurHost 的可执行代码里又出现了 `$token` —— $why。" +
                    "它绕过了 applyCapsuleWindowParams 的相等性守卫，pop 期间的 relayout 会回归。",
                code.contains(token)
            )
        }

        assertTrue(
            "decorFitsSystemWindows 缺少一次性闸门（decorFitsApplied）—— 该 setter 没有 getter " +
                "可比对，每次调用都会强制一次 inset 重派发 + traversal，三次重放就是三次多余 relayout。",
            code.contains("decorFitsApplied")
        )
    }

    // ── 归位时序红线：先等权威追上，再归位（2026-09-05 第二版）────────────────
    //
    // 背景（真机现象）：横滑切页时着色滑块「先到目标格 → 往回抽一下 → 再回目标」。
    // 根因是两个「何时算滑完」的判据用了不同的时钟：
    // - 胶囊判"不滑了"看连续进度是否落进整数 ±0.01 带，这一刻 Pager 的 settle 还没结束；
    // - 而 `mainTabIndex` 的回吐被硬性要求在 `pagerState.isScrollInProgress == false` 之后，
    //   再加一次 MainNavGraph → Dialog 子树重组才抵达胶囊。
    // 间隔常 > IDLE_SETTLE_CONFIRM_MS(80ms) ⇒ 归位分支在 latestIndex 还是旧格时就
    // `animateTo(旧格)`，回吐抵达后再 `animateTo(新格)` —— 那一下反向动画就是"抽"。

    /**
     * 两条归位驱动（滑块位置 / 图标着色）都必须**先等权威追上**再取归位目标。
     *
     * 回归形态非常具体：`delay(IDLE_SETTLE_CONFIRM_MS)` 后直接
     * `settledIndexOf(latestIndex.value, …)`。这就是本 bug 的原始写法 ——
     * 它只是"靠等"，等不够就拿旧下标归位。因此这里用两条互补的断言钉死：
     * 1. 每个 `settledIndexOf(latestIndex.value` 调用点的紧邻上文必须有
     *    `awaitSettleAuthority(`，且**不得**有裸的 `delay(IDLE_SETTLE_CONFIRM_MS)`；
     * 2. 全文件的 `delay(IDLE_SETTLE_CONFIRM_MS)` 只允许出现一次 —— 就是
     *    `awaitSettleAuthority` 内部那个防抖确认窗。多出来的一定是绕过等待的旁路。
     *
     * 同时守死「进度不得当作归位目标」：`settledIndexOf(selectionProgressProvider…` 这种
     * 写法就是 2026-09-05 第一次修的那个 bug（第二个真相源 → 进过二级页后胶囊锁死）。
     */
    @Test
    fun settleBranches_mustWaitForAuthorityBeforeSettling() {
        val code = executableCode(source(tabBarPath))

        val callSites = Regex("""settledIndexOf\(\s*latestIndex\.value""")
            .findAll(code)
            .map { it.range.first }
            .toList()
        assertEquals(
            "归位目标的调用点应恰好两处（滑块位置 + 图标着色 selectionFactors），当前 ${callSites.size} 处。" +
                "两者必须共用同一套归位时机，否则会出现「滑块已到位、图标还在抽」的半修好状态。",
            2,
            callSites.size
        )

        callSites.forEach { at ->
            val before = code.substring(maxOf(0, at - 300), at)
            assertTrue(
                "归位分支在取 `settledIndexOf(latestIndex.value, …)` 之前没有调用 " +
                    "`awaitSettleAuthority(` —— 红线回归：归位只能在「权威 index 已经反映了" +
                    "这次手势的结果」之后执行，否则会拿旧下标把滑块/着色往回拽（横滑时肉眼可见的「抽一下」）。",
                before.contains("awaitSettleAuthority(")
            )
            assertFalse(
                "归位分支里出现了裸的 `delay(IDLE_SETTLE_CONFIRM_MS)` + 直接取 latestIndex 的旧形态 —— " +
                    "确认窗只是防抖，不是「等权威」；间隔常常超过它，必须改用 awaitSettleAuthority。",
                before.contains("delay(IDLE_SETTLE_CONFIRM_MS)")
            )
        }

        val confirmDelays = Regex("""delay\(IDLE_SETTLE_CONFIRM_MS\)""").findAll(code).count()
        assertEquals(
            "`delay(IDLE_SETTLE_CONFIRM_MS)` 只允许出现在 awaitSettleAuthority 内部（唯一的防抖确认窗），" +
                "当前有 $confirmDelays 处。多出来的那处必然是绕过「等权威追上」的旁路。",
            1,
            confirmDelays
        )

        assertFalse(
            "又出现了 `settledIndexOf(selectionProgressProvider…` —— 2026-09-05 已修 bug 回归：" +
                "Pager 连续进度是第二个真相源，进过二级页后它停在旧值上，会把胶囊永久锁在旧格" +
                "（「胶囊显示 a、实际是 b」）。归位目标恒取 mainTabIndex（latestIndex）。",
            Regex("""settledIndexOf\(\s*selectionProgressProvider""").containsMatchIn(code)
        )
    }

    /**
     * 「等权威追上」必须带**兜底**，否则会复活「进过二级页面后胶囊永久锁死在旧格子」。
     *
     * 三条必须存在的兜底：
     * 1. **NaN 立刻放行** —— `LocalUfiCapsuleSelectionProgress` 的默认值是 `{ Float.NaN }`
     *    （宿主没挂载 / 从未发布过进度）。此时"一致"永远不会成立，必须立刻按 latestIndex 归位。
     *    断言要求 `isNaN()` 是判据的**第一个析取项**（短路 ⇒ 零等待）。
     * 2. **超时** —— 进度是过期值（宿主刚重建、还没发第一帧）时，等待必须有上限，
     *    到点直接按 latestIndex 归位。断言要求 `withTimeoutOrNull(AUTHORITY_CATCHUP_TIMEOUT_MS)`。
     * 3. **超时常量不得是裸字面量** —— 必须来自全站时长梯度（`UfiMotion.Duration`）+ 现有确认窗，
     *    这样改转场时长时上限自动跟上，不会悄悄退化成"等不够"。
     */
    @Test
    fun authorityCatchUp_mustHaveNaNAndTimeoutFallbacks() {
        val code = executableCode(source(tabBarPath))

        assertTrue(
            "找不到 authorityCaughtUp —— 「权威是否已反映这次手势结果」的判据被删除了，" +
                "归位会退回「靠等」，横滑抽搐立刻回归",
            Regex("""fun\s+authorityCaughtUp\s*\(""").containsMatchIn(code)
        )
        assertTrue(
            "authorityCaughtUp 里 NaN 不再是第一个析取项 —— 兜底红线回归：" +
                "LocalUfiCapsuleSelectionProgress 的默认值就是 { Float.NaN }（宿主未挂载 / 未发布），" +
                "此时「进度取整 == 下标」永远不成立，必须立刻按 latestIndex 归位，否则胶囊永久锁死在旧格。",
            Regex("""authorityCaughtUp[\s\S]{0,240}?progress\.isNaN\(\)\s*\|\|""")
                .containsMatchIn(code)
        )

        val body = functionBody(code, "private suspend fun awaitSettleAuthority")
        assertTrue(
            "awaitSettleAuthority 缺少 withTimeoutOrNull(AUTHORITY_CATCHUP_TIMEOUT_MS) —— 兜底红线回归：" +
                "宿主刚重建 / 进度是过期值时「一致」可能永远等不到，无上限的等待会让滑块与图标着色" +
                "永久停在旧格（2026-09-05 已修 bug）。",
            Regex("""withTimeoutOrNull\(\s*AUTHORITY_CATCHUP_TIMEOUT_MS\s*\)""")
                .containsMatchIn(body)
        )
        assertTrue(
            "awaitSettleAuthority 内没有 `.first {` —— 等待必须是「等到判据成立」而不是固定睡一段时间，" +
                "否则点击路径也会被无谓地拖慢。",
            body.contains(".first {")
        )

        val timeoutDecl = Regex("""AUTHORITY_CATCHUP_TIMEOUT_MS\s*:\s*Long\s*=([^\n]*(?:\n\s{4}[^\n]*)?)""")
            .find(code)
        assertNotNull(
            "找不到 AUTHORITY_CATCHUP_TIMEOUT_MS 的声明 —— 等待上限被移除，胶囊锁死风险回归",
            timeoutDecl
        )
        assertTrue(
            "AUTHORITY_CATCHUP_TIMEOUT_MS 必须由令牌推导（UfiMotion.Duration.* + IDLE_SETTLE_CONFIRM_MS），" +
                "不得写成裸字面量 —— 当前是：${timeoutDecl!!.groupValues[1].trim()}",
            timeoutDecl.groupValues[1].contains("UfiMotion.Duration") &&
                timeoutDecl.groupValues[1].contains("IDLE_SETTLE_CONFIRM_MS")
        )
    }

    // ── 即点即达：点击路径不得付防抖窗（2026-09-05 第三版）────────────────────

    /**
     * `delay(IDLE_SETTLE_CONFIRM_MS)` 必须是**有条件**的，且条件必须走
     * [settleNeedsConfirmWindow]（`followsMotion || !authorityCaughtUpAtEntry`）。
     *
     * 背景（真机反馈）：点胶囊「不跟手、没有即点即达」。链路上有 80ms 是白等的 ——
     * 防抖窗的用途只是"手势收尾时 isScrolling 会在整数带附近抖"，而点击路径压根没有中途态
     * （宿主会 `capsuleProgress.snapTo(目标)`，判据当帧成立）。
     *
     * 三条互补断言，任何一条红灯都说明"点击零延迟"这条链被改坏了：
     * 1. 唯一那处 `delay(IDLE_SETTLE_CONFIRM_MS)` 的紧邻上文必须是
     *    `if (settleNeedsConfirmWindow(` —— 无条件 delay 就是回归；
     * 2. 两条归位驱动都必须把 `motionSettlePending` 传进 `awaitSettleAuthority`
     *    （**同一份快照状态**，两条驱动读到的值必须相同，否则又会错开 80ms）；
     * 3. `motionSettlePending` 的写入点齐全：点击清零、滑动/拖拽置真。少了置真那半边，
     *    横滑收尾就会丢掉防抖保护、「滑块抽搐」回归。
     */
    @Test
    fun settleConfirmWindow_mustBeSkippedOnClickPath() {
        val code = executableCode(source(tabBarPath))

        val delayAt = Regex("""delay\(IDLE_SETTLE_CONFIRM_MS\)""").find(code)
        assertNotNull(
            "找不到 `delay(IDLE_SETTLE_CONFIRM_MS)` —— 手势收尾的防抖确认窗被整体删除了，" +
                "横滑落定时滑块会与恢复的连续进度打架（抽搐）。它只该被**有条件**跳过，不是删掉。",
            delayAt
        )
        val beforeDelay = code.substring(maxOf(0, delayAt!!.range.first - 220), delayAt.range.first)
        assertTrue(
            "`delay(IDLE_SETTLE_CONFIRM_MS)` 前面没有 `if (settleNeedsConfirmWindow(` —— " +
                "防抖窗又变成无条件支付：点击路径本来当帧就满足归位判据，白等 80ms 的观感" +
                "正是用户说的「胶囊不跟手 / 没有即点即达」。",
            Regex("""if\s*\(\s*settleNeedsConfirmWindow\(""").containsMatchIn(beforeDelay)
        )

        val callSites = Regex(
            """awaitSettleAuthority\(\s*latestIndex\s*,\s*selectionProgressProvider\s*,\s*motionSettlePending\s*\)"""
        ).findAll(code).count()
        assertEquals(
            "两条归位驱动（滑块位置 + 图标着色）都必须把 motionSettlePending 传进 awaitSettleAuthority，" +
                "当前 $callSites 处。两者必须读**同一份**标记，否则一条零延迟、另一条等 80ms，" +
                "又回到「滑块已到位、图标还在抽」的半修好状态。",
            2,
            callSites
        )

        assertEquals(
            "`motionSettlePending = false` 应恰好一处（CapsuleTab 的 onClick）—— " +
                "它就是「本次归位是点击、没有中途态」的唯一声明点。",
            1,
            Regex("""motionSettlePending\s*=\s*false""").findAll(code).count()
        )
        assertTrue(
            "`motionSettlePending = true` 少于两处 —— 置真的两个入口（页面横滑的 isScrolling 收集器、" +
                "抓住滑块拖动的 onDragStarted）缺了一个，那条路径会误走「点击零延迟」分支，" +
                "手势收尾的防抖保护失效 ⇒ 滑块抽搐回归。",
            Regex("""motionSettlePending\s*=\s*true""").findAll(code).count() >= 2
        )
    }

    /**
     * 滑块**常规归位**的 spec 必须与页面转场同源（时长 + 曲线），不得退回 spring。
     *
     * 页面走 `tween(navTransitionMs, CubicBezierEasing(0.4, 0, 0.2, 1))`（`MainNavGraph`），
     * 滑块原来走 `spring(0.8, 500)`：时长不同、曲线不同、到位时刻也不同 ——
     * 这种不同步本身就会被感知成「不跟手」。
     *
     * 断言四条：
     * 1. `indicatorSettleSpec` 存在，且由 `navTransitionMs` + [UfiMotion.Easing.Standard] 推导
     *    （不得写裸字面量时长，也不得换别的曲线）；
     * 2. 时长来源必须是 `ufiNavTransitionDurationMs(`（与 `MainNavGraph` 同一个归一化函数，
     *    含"关闭档 0"与"未播种 -1"的处理）；
     * 3. 常规归位分支的 `animateTo` 不得再用 `INDICATOR_SPEC`；
     * 4. `INDICATOR_SPEC` 本身仍是 `spring(`：它还服务于**长按拖动松手**那一下（C2 分支），
     *    那是手势释放，速度连续性正是弹簧的长处，且此刻没有页面动画需要对齐。
     */
    @Test
    fun indicatorSettle_mustShareTimelineWithPageTransition() {
        val code = executableCode(source(tabBarPath))

        assertTrue(
            "找不到 `fun indicatorSettleSpec(` —— 滑块归位的同源 spec 被删除，" +
                "滑块又会用一条与页面无关的时间轴。",
            Regex("""fun\s+indicatorSettleSpec\s*\(""").containsMatchIn(code)
        )
        assertTrue(
            "indicatorSettleSpec 不再是「navTransitionMs + UfiMotion.Easing.Standard」的 tween —— " +
                "页面与滑块的时间轴又分叉了（页面是 tween(navTransitionMs, 0.4/0/0.2/1)，" +
                "Standard 就是这条曲线）。",
            Regex(
                """fun\s+indicatorSettleSpec[\s\S]{0,200}?tween\(\s*durationMillis\s*=\s*""" +
                    """navTransitionMs\.coerceAtLeast\(1\)\s*,\s*easing\s*=\s*UfiMotion\.Easing\.Standard\s*\)"""
            ).containsMatchIn(code)
        )
        assertTrue(
            "胶囊不再读 `ufiNavTransitionDurationMs(` —— 时长必须与 MainNavGraph 走同一个归一化函数，" +
                "否则「关闭转场(0)」「设置未播种(-1)」这两档会在两边给出不同结果。",
            code.contains("ufiNavTransitionDurationMs(")
        )
        assertFalse(
            "常规归位又用回了 `animateTo(targetF, INDICATOR_SPEC)`（spring）—— " +
                "与页面 tween 不同步，「不跟手」回归。应为 `settleSpecState.value`。",
            Regex("""animateTo\(\s*targetF\s*,\s*INDICATOR_SPEC\s*\)""").containsMatchIn(code)
        )
        assertTrue(
            "常规归位分支没有用 `settleSpecState.value` —— 它是「此刻的同源 spec」的唯一取用方式" +
                "（effect 的 key 里没有时长，必须靠 rememberUpdatedState 读到最新值）。",
            Regex("""animateTo\(\s*targetF\s*,\s*settleSpecState\.value\s*\)""").containsMatchIn(code)
        )
        assertTrue(
            "INDICATOR_SPEC 不再是 spring(...) —— 它现在只服务于「长按拖动松手后钉到目标格」，" +
                "那是手势释放，需要弹簧的速度连续性；换成 tween 会失去「被甩出去」的手感。",
            Regex("""INDICATOR_SPEC[^\n=]*=\s*[\s\S]{0,40}?spring\(""").containsMatchIn(code) ||
                Regex("""INDICATOR_SPEC[^\n=]*=\s*UfiMotion\.tabSlider\(\)""").containsMatchIn(code)
        )
    }

    // ── 组合期读红线：isScrolling 不得当 effect 的 key（2026-09-05 第四版）────────
    //
    // 背景（真机现象）：横滑切主界面后"顿一下"变轻但没消失。上一轮修掉了 static local
    // 强制整树重组与 PagerBackend 体的多余重组，剩下的其中一条是本条：
    // `isScrolling` 原来是 `var … by remember`（**组合期读**），同时是三条 effect 的 key
    // （自动收起 / 图标着色归位 / 滑块归位）。横滑一次它必然翻转两下
    // （起手 false→true、落定 true→false），每一下都让 UfiCapsuleTabBar **整体重组**；
    // 这个 composable 活在独立 Dialog 窗口 + 跨窗口渲染里，一次重组要重测量、重绘、
    // 再提交那个窗口，而两下恰好落在 settle 前后仍有可见运动（滑块 / 着色归位）的窗口里。
    // 点击路径 `isScrolling` 全程为 false，所以这两次重组只存在于横滑路径。

    /**
     * `isScrolling` 只能以 `State` 容器形态存在，且**不得**出现在任何 `LaunchedEffect` 的 key 里。
     *
     * 回归形态极其容易发生：把 `isScrollingState.value` 重新解糖成 `var isScrolling by remember`
     * 只需一行，评审时看起来还"更简洁"。四条互补断言：
     * 1. 任何 `LaunchedEffect(...)` 的 key 列表里不得出现 `isScrolling`（`\b` 边界，
     *    所以 `isScrollingState` 不会误伤）；
     * 2. 必须存在 `isScrollingState` 这个 `remember { mutableStateOf(false) }` 容器，
     *    且不得再有 `var isScrolling` 的委托写法；
     * 3. 两条归位驱动必须各自构造一次 [CapsuleSettleProbe]（滑块位置 + 图标着色），
     *    并走 `distinctUntilChanged()`（≡ 原来的「key 是否变化」）；
     * 4. 分支选择必须读探针字段而不是闭包捕获的组合值 —— `probe.dragging` /
     *    `probe.scrolling` / `probe.settleIndex` 三者齐全，否则四分支互斥语义已被改写。
     */
    @Test
    fun settleDrivers_mustNotReadIsScrollingDuringComposition() {
        val code = executableCode(source(tabBarPath))

        Regex("""LaunchedEffect\(([^)]*)\)""").findAll(code).forEach { m ->
            val keys = m.groupValues[1]
            assertFalse(
                "`LaunchedEffect($keys)` 的 key 里又出现了 `isScrolling` —— 组合期读红线回归：" +
                    "横滑一次它翻转两下，就是 UfiCapsuleTabBar 在 settle 前后整体重组两次" +
                    "（跨窗口渲染，代价高且正好落在可见运动窗口里）⇒ 「横滑落定顿一下」回归。" +
                    "所有读取必须推迟到协程内（snapshotFlow + 探针）。",
                Regex("""\bisScrolling\b""").containsMatchIn(keys)
            )
        }

        assertTrue(
            "找不到 `isScrollingState` 的 State 容器声明 —— 它是「滑动中」判据的唯一存放形态，" +
                "被改回组合期读的委托写法就意味着重组回归。",
            Regex("""val\s+isScrollingState\s*:\s*MutableState<Boolean>\s*=\s*remember\s*\{\s*mutableStateOf\(false\)\s*\}""")
                .containsMatchIn(code)
        )
        assertFalse(
            "又出现了 `var isScrolling by remember { … }` 的委托写法 —— 那是组合期读的源头。",
            Regex("""var\s+isScrolling\b""").containsMatchIn(code)
        )

        val probeSites = Regex("""(?<!class\s)CapsuleSettleProbe\(""").findAll(code).count()
        assertEquals(
            "归位判据探针的构造点应恰好两处（滑块位置 + 图标着色两条驱动各一处），当前 $probeSites 处。" +
                "两条驱动必须读**同一组**判据，否则会回到「滑块已到位、图标还在抽」的半修好状态。",
            2,
            probeSites
        )
        assertTrue(
            "探针流缺少 `distinctUntilChanged()` —— 它才是「key 是否变化」的等价物；" +
                "少了它，判据没变也会重跑一次分支体（例如把正在跑的归位动画掐掉重来）。",
            Regex("""distinctUntilChanged\(\)""").findAll(code).count() >= 3
        )

        listOf("probe.dragging", "probe.scrolling", "probe.settleIndex").forEach { field ->
            assertTrue(
                "四分支互斥的判据里找不到 `$field` —— 分支选择又改回读闭包捕获的组合值了。" +
                    "探针化之后 `LaunchedEffect` 不再随组合重启，闭包里的值会一直停在首次组合那一份。",
                code.contains(field)
            )
        }
    }

    /**
     * 胶囊的可执行代码里**不得**出现 `isSystemInDarkTheme()`。
     *
     * 2026-09-05：胶囊曾有两处直接读它 ——
     * - `capsuleSurfaceColor()`：收起/展开态底色的 alpha 档（深 0.92 / 浅 0.90）；
     * - `CapsuleSelectionSlider`：选中药丸的上缘镜面高光强度（深 0.12 / 浅 0.22）。
     *
     * 这两处**绕过了「设置 → 外观 → 外观模式」**：用户选「强制浅色」而系统处于深色时，
     * 胶囊的 `cardBg` / `accent` 已经是浅色那一套（它们来自 `LocalResolvedPalette`，
     * 由 `UFIAXISTheme(darkTheme = …)` 解析），而 alpha 档却仍按系统的深色走 ——
     * 两个来源打架，真机表现是胶囊通透度、高光与页面明暗不一致（浅底上高光几乎看不见）。
     *
     * 唯一正确的明暗判据是 [ResolvedPalette.isDark]：它就是 `themeMode` 解析后的那个真值，
     * 与页面底色、卡片、文字色同源。
     *
     * 全仓 `isSystemInDarkTheme()` 的**合法**调用点只有三处，都不在本文件：
     * `Theme.kt` 与 `ThemeLocals.kt` 的默认参数、`MainActivity` 里 AUTO 档的实现本体。
     */
    @Test
    fun capsule_mustNotReadSystemDarkThemeDirectly() {
        val code = executableCode(source(tabBarPath))
        assertFalse(
            "UfiCapsuleTabBar 的可执行代码里又出现了 `isSystemInDarkTheme()` —— 它绕过" +
                "「设置 → 外观 → 外观模式」：强制浅色 / 强制深色时，胶囊底色 alpha 与上缘" +
                "高光会跟着**系统**明暗走，而 cardBg / accent 已经按用户选择解析过了。" +
                "改读 `LocalResolvedPalette.current.isDark`（与页面同源的那一个真值）。",
            Regex("""\bisSystemInDarkTheme\s*\(""").containsMatchIn(code)
        )
        assertFalse(
            "连 import 都不该留：`androidx.compose.foundation.isSystemInDarkTheme` 还在" +
                "导入列表里，说明只是把调用点挪了位置而不是真的换掉了判据。",
            code.contains("import androidx.compose.foundation.isSystemInDarkTheme")
        )
        assertTrue(
            "胶囊里找不到 `isDark` 的读取 —— 明暗档位的判据被整体删掉了，" +
                "底色 alpha 与高光强度会退化成单一档（深浅共用一个值）。",
            Regex("""palette\.isDark|LocalResolvedPalette\.current\.isDark""")
                .containsMatchIn(code)
        )
    }
}
