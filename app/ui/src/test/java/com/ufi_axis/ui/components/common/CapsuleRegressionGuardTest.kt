package com.ufi_axis.ui.components.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import com.ufi_axis.ui.theme.UfiMotion

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
 *
 * ## ★ 2026-09-24：悬浮胶囊 → 贴底通栏低栏（迁移阶段 2.5）
 * 底部导航的形态已从「悬浮胶囊」（wrap-content 的独立 Dialog 窗口、`LayoutParams.y` 抬离底边、
 * 收起/展开状态机、横向拖拽切页）换成「贴底通栏低栏」（`MATCH_PARENT` 宽、`gravity=BOTTOM`、
 * `y=0`、上圆角 22dp、内容高 58dp、标签常显、安全区靠栏内 Spacer 让位）。
 * 计划与逐条决策见 `docs/bottom-dock-migration-plan.md`（§4 护栏处置、§5 边缘情况）。
 *
 * 本批对每条护栏只问一个问题：**它当年防的那个真机 bug，在通栏形态下还会不会发生？**
 * - 还会 ⇒ 断言改写到新形态的等价机制上，方法名同步改准（例如触摸穿透、安全区让位、进出场锚点）；
 * - 不可能再发生（前提随形态消失）⇒ 删除，并在原位留一段**墓碑注释**说明原委。
 *
 * ⚠ 守护对象有两类，不要混：
 * - `UfiBottomDock.kt` / `UfiCapsuleBlurHost.kt` / `MainNavGraph.kt` 是**在线**形态，
 *   它们的护栏是真正在守当前产品；
 * - `UfiCapsuleTabBar.kt` 自 `USE_BOTTOM_DOCK = true` 起**已不再挂载**，只等阶段 3 整体删除。
 *   本批刻意**不动**那些只读 tabBar 的护栏（选中态插值 / 归位时序 / 组合期读三组）：
 *   它们仍然全绿，且在阶段 3 删除旧组件时正好充当「别顺手改错东西」的对照。
 *   阶段 3 删 tabBar 时这些护栏一并退休，不要那时才临时决定去留。
 */
class CapsuleRegressionGuardTest {

    // ── 被守护的源文件 ──────────────────────────────────────────────────────

    private val tabBarPath =
        "src/main/java/com/ufi_axis/ui/components/common/UfiCapsuleTabBar.kt"

    /**
     * 贴底通栏低栏的本体（2026-09-24 起的**在线**形态，见 `MainNavGraph.USE_BOTTOM_DOCK`）。
     *
     * 它承接了原来分散在窗口层与胶囊内部的三件事：底色铺满（含安全区）、安全区靠栏内
     * `Spacer` 让位、选中药丸只画在内容区。这三条各自对应一条新护栏。
     */
    private val bottomDockPath =
        "src/main/java/com/ufi_axis/ui/components/common/UfiBottomDock.kt"

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
     *
     * ★ 已删除第 4 条断言（2026-09-24，迁移阶段 2.5）：原来还要求
     *   `EXPAND_SPRING` 后 40 字符内含 `spring(`（「展开/收起不得瞬变」）。
     *   通栏低栏没有收起态，`EXPAND_SPRING` 属于阶段 3 的删除清单，这条断言守的
     *   「整体收缩交互」在新形态下不存在。前 3 条保留：颜色 lerp / alpha 插值 /
     *   `selectionFactor` 是**选中态**动画的护栏，与收起无关。
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

    // ── 触摸穿透红线：栏以上区域必须穿透到页面 ──────────────────────────────

    /**
     * 栏必须是**通栏**（`MATCH_PARENT` 宽 + `gravity=BOTTOM` + `y=0`），
     * 同时「**栏以上**区域的触摸仍必须穿透到页面」。
     *
     * ## 本条是改写（2026-09-24，迁移阶段 2.5）
     * 原名 `capsule_mustNotFillMaxWidth_soTouchesPassThrough`，断言是
     * 「`UfiCapsuleTabBar` 不得出现 `fillMaxWidth`」。理由是：悬浮胶囊的窗口是
     * wrap-content，内容一撑满宽度窗口矩形就横贯整屏，而 `FLAG_NOT_TOUCH_MODAL`
     * 只放行窗口矩形**之外**的触摸 ⇒ 胶囊左右两侧的页面内容点不动。
     *
     * 通栏低栏把这个前提反转了：窗口**就是** `MATCH_PARENT` 宽，占满那条带子是设计目标。
     * 但原护栏守的那个**真实风险仍然存在** —— 只是搬了家：
     * 现在能吞掉页面触摸的不再是"宽度"，而是**窗口高度**。窗口一旦变成全屏高
     * （有人"顺手"把 height 也写成 `MATCH_PARENT`，或让 `naturalSize.height` 那条链路失效
     * 退化成某个大值），整页的点击就全被这个 Dialog 圈进窗口矩形，
     * `FLAG_NOT_TOUCH_MODAL` 再也无从放行 —— 表现是「除了底栏，整个 App 点不动」。
     *
     * 所以三件套必须齐全（§2.1 表 + §5.4）：
     * 1. 几何：width = `MATCH_PARENT`、`Gravity.BOTTOM`、`y = 0`（少一项就不是"贴底通栏"）；
     * 2. 窗口高度**只能**来自内容测量（`naturalSize.height`，未测得退 `WRAP_CONTENT`）——
     *    窗口只有栏那么高，栏以上区域才在窗口矩形之外；
     * 3. `FLAG_NOT_TOUCH_MODAL` 在线，且二级页整块不吃触摸的 `FLAG_NOT_TOUCHABLE` gate 在线。
     *
     * 刻意**不**用 `constraints.maxWidth` 之类的绕法改写内容层断言 —— 那只是把护栏
     * 留成噪音；触摸能不能穿透是**窗口层**的事实，断言就该落在窗口层。
     */
    @Test
    fun dockWindow_mustBeFullWidthBottomAnchored_andPassTouchesAboveIt() {
        val code = executableCode(source(blurHostPath))

        assertTrue(
            "窗口宽度不再是 MATCH_PARENT —— 贴底通栏的前提没了（应为 " +
                "`val desiredWidth: Int = ViewGroup.LayoutParams.MATCH_PARENT`）。",
            Regex("""desiredWidth\s*:\s*Int\s*=\s*ViewGroup\.LayoutParams\.MATCH_PARENT""")
                .containsMatchIn(code)
        )
        assertTrue(
            "窗口 gravity 不是 Gravity.BOTTOM —— 配合 FLAG_LAYOUT_NO_LIMITS 它才把参考系钉在" +
                "屏幕真实底边；改掉之后栏会离开底边（通栏就不通了）。",
            Regex("""desiredGravity\s*:\s*Int\s*=\s*Gravity\.BOTTOM""").containsMatchIn(code)
        )
        assertTrue(
            "窗口 y 不再恒 0 —— 任何 y > 0 都会在栏与屏幕底边之间露出一条页面内容。" +
                "安全区的让位是**栏内 Spacer** 的职责，不是窗口偏移（见 " +
                "bottomSafeArea_mustBeConsumedInsideDock_notByWindowY）。",
            Regex("""desiredY\s*:\s*Int\s*=\s*0\b""").containsMatchIn(code)
        )

        assertTrue(
            "窗口高度不再由 `naturalSize.value.height` 决定 —— 它必须只有**栏那么高**：" +
                "窗口矩形一旦长过栏（尤其被写成 MATCH_PARENT），栏以上区域的点击就全被这个 " +
                "Dialog 圈走，FLAG_NOT_TOUCH_MODAL 只放行窗口**之外**的触摸，" +
                "真机表现是「除了底栏，整个 App 点不动」。",
            Regex("""desiredHeight\s*:\s*Int\s*=\s*if\s*\(\s*naturalSize\.value\.height\s*>\s*0\s*\)""")
                .containsMatchIn(code)
        )
        assertFalse(
            "窗口高度的表达式里出现了 MATCH_PARENT —— 那是全屏高的浮窗，等于把整页触摸吞掉（同上）。",
            code.substringAfter("desiredHeight: Int =", "")
                .substringBefore("desiredGravity")
                .contains("MATCH_PARENT")
        )

        assertTrue(
            "缺少 FLAG_NOT_TOUCH_MODAL —— Dialog 默认是模态的，不显式清除，栏以上区域的" +
                "触摸一律被吃掉（产品红线：栏之外的点击必须穿透到页面）。",
            code.contains("FLAG_NOT_TOUCH_MODAL")
        )
        assertTrue(
            "二级页的 FLAG_NOT_TOUCHABLE gate 不见了 —— 通栏后窗口矩形更宽，二级页里" +
                "底部那条带子若仍吃触摸，用户会发现「返回详情页后底部点不动」（计划 §5.4）。",
            code.contains("FLAG_NOT_TOUCHABLE")
        )
        assertTrue(
            "窗口层不再读 `CapsuleTouchGate.interactive` —— FLAG_NOT_TOUCHABLE 的唯一驱动源被" +
                "切断，gate 形同虚设（二级页底部依旧吃触摸）。",
            Regex("""CapsuleTouchGate\.interactive""").containsMatchIn(code)
        )
        assertTrue(
            "MainNavGraph 不再写 `CapsuleTouchGate.interactive.value = showBottomBar` —— " +
                "gate 没有写入方：栏窗口从第一次进入 MAIN 起常驻（挂载后不再卸载），" +
                "二级页里它仍然圈着底部那条更宽的矩形，点击穿不下去（§5.4）。",
            Regex("""CapsuleTouchGate\.interactive\.value\s*=""")
                .containsMatchIn(executableCode(source(navGraphPath)))
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

    // ── 整体收缩 / 展开状态机（已随悬浮胶囊形态退休）────────────────────────────
    //
    // ★★ 墓碑（2026-09-24，贴底通栏低栏迁移阶段 2.5）★★
    //
    // 这一节原有三条护栏，全部随「收起 / 展开状态机」一起删除：
    //
    // 1. `collapseDelay_mustBe1500ms`
    //    守的是产品交互契约「点击后展开，1.5s 无操作自动收起」（断言 COLLAPSE_DELAY_MS == 1500L）。
    //    前提消失：通栏低栏**没有收起态**，标签常显、格宽等分、整条不缩放，
    //    "多久之后自动收回去"这个问题本身不存在。常量属于阶段 3 的删除清单。
    //
    // 2. `collapsedScale_mustBePerceptibleButLegible`
    //    守的是收起态整体缩放必须落在 [0.80f, 0.92f]：低于下限图标发糊、点击目标过小，
    //    高于上限两个状态肉眼无差别。前提消失：同上，没有第二个状态可比。
    //
    // 3. `expandTimer_mustResetOnEveryTap`
    //    守的是「1.5s 内再次点击必须重新计时」，6 条断言围绕自动收起链路
    //    （`resetToken` / `CapsuleCollapseProbe` / `distinctUntilChanged` / `collectLatest` /
    //    `delay(COLLAPSE_DELAY_MS)` / `probe.expanded`）。防的真机 bug 是「用户连续点 Tab 时
    //    胶囊突然缩回去」。前提消失：没有倒计时，也就没有"重新计时"。
    //
    // ⚠ 为什么是删除而不是改写：这三条守的不是某个通用不变量，而是**收起态这个形态本身**。
    //   通栏低栏里找不到语义对应物（不是"换个写法实现同一意图"，而是意图消失）。
    //   与它们相对，同一批里「触摸穿透」「安全区不能丢」「进出场锚点」都是**改写**而非删除
    //   —— 那三个 bug 在新形态下依然可能发生，只是机制换了。
    //
    // ⚠ 删除时机说明：本批**并未**删除 `UfiCapsuleTabBar` 的收起/展开实现（那是阶段 3），
    //   所以这三条如果留着此刻仍会绿。删掉的理由不是"它会红"，而是它守的产品形态已下线 ——
    //   留着会让后人以为「自动收起」仍是现行契约，在通栏栏上重新实现一遍。

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

    /**
     * 栏窗口必须锚定屏幕**底边**，且不得再带水平居中。
     *
     * ## 本条是改写（2026-09-24，迁移阶段 2.5）
     * 原名 `dialogHost_mustAnchorBottomCenter`，断言 `Gravity.BOTTOM` **与**
     * `Gravity.CENTER_HORIZONTAL` 同时在线 —— 后者是 wrap-content 小窗时代的必需品
     * （窄窗口得自己居中，否则会贴到屏幕左边）。
     *
     * 通栏后窗口宽度是 `MATCH_PARENT`，"水平居中"已无余量可居中：留着它不会有视觉后果，
     * 但会误导后人以为窗口还是个可左右摆放的小窗（也就可能顺手把 width 改回 wrap-content）。
     * 所以新不变量是「只有 BOTTOM，不许再出现 CENTER_HORIZONTAL」。
     *
     * `Gravity.BOTTOM` 本身**仍是红线**：Dialog 的默认 gravity 是屏幕中央，
     * 少了它栏会飘到屏幕正中间。
     */
    @Test
    fun dialogHost_mustAnchorBottomWithoutHorizontalCentering() {
        val code = executableCode(source(blurHostPath))

        assertTrue(
            "Dialog 宿主缺少 Gravity.BOTTOM —— Dialog 默认 gravity 是屏幕中央，栏会停在屏幕正中间",
            code.contains("Gravity.BOTTOM")
        )
        assertFalse(
            "Dialog 宿主又出现了 Gravity.CENTER_HORIZONTAL —— 窗口已是 MATCH_PARENT 宽，" +
                "水平居中没有任何余量可居中；它是 wrap-content 小窗时代的残留，" +
                "留着会让人误以为窗口还能左右摆放（进而把 width 改回 wrap-content，通栏就断了）。",
            code.contains("Gravity.CENTER_HORIZONTAL")
        )
    }

    /**
     * 底栏必须由 **Dialog** 承载，且平台默认宽度约束必须被解除（不得退回 Popup）。
     *
     * ## 本条只改了名字与注释（2026-09-24，迁移阶段 2.5）
     * 原名 `capsuleWindow_mustStayWrapContent` —— 名字是错的、而且现在会误导人：
     * 三条断言其实一条都不查 wrap-content，查的是 `Dialog(` / `usePlatformDefaultWidth = false`
     * / 无 `Popup(`；而通栏迁移正是要把窗口宽度改成 `MATCH_PARENT`。
     * 留着旧名字，下一个人读到「mustStayWrapContent 还是绿的」会以为窗口仍是小窗。
     *
     * 三条断言本身**一字未改**，它们守的是两件与形态无关的事：
     * 1. `Dialog(` —— 只有 Dialog 才有 `Window` 对象，才能在窗口层统一施加触摸穿透 flag、
     *    底边锚定、透明背景。Popup 拿不到 Window（当年还牵着窗口内背景模糊）。
     * 2. `usePlatformDefaultWidth = false` —— 不设它，平台会把 Dialog 拉到默认宽度
     *    （接近整屏但**不等于**整屏，且由平台说了算）。宽度必须由我们自己在 LayoutParams 里
     *    决定：2026-09-24 之前是 wrap-content，之后是 `MATCH_PARENT`。
     *    写成 true 就等于把窗口矩形的控制权交回平台 —— 两种形态都会出错。
     */
    @Test
    fun bottomBarWindow_mustBeDialogWithPlatformWidthOverridden() {
        val code = executableCode(source(navGraphPath))

        assertTrue(
            "底栏不再由 Dialog 承载 —— Popup 没有 Window 对象，窗口层的触摸穿透 flag、" +
                "底边锚定与透明背景全都无从施加",
            code.contains("Dialog(")
        )
        assertTrue(
            "缺少 usePlatformDefaultWidth = false —— 窗口宽度会被交回平台决定，" +
                "我们对窗口矩形（通栏形态下 = MATCH_PARENT）的控制权就没了",
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
     *
     * ## ⚠ 2026-09-24 通栏迁移：本条**刻意保持 18dp 不变**
     * 迁移计划 §4.1 曾提议把它改成 22dp（通栏低栏的上圆角）。复核后**不改**，理由：
     * - 通栏低栏的圆角真源是 `UfiBottomDock` 自己的 `DOCK_SHAPE`（上 22dp / 下 0），
     *   它与窗口侧这个常量**不构成一对** —— 窗口背景恒 null，两者之间没有"错边"可言；
     * - 本条守的"窗口侧 18 ≡ 内容侧 18"这一对**仍然成立**：另一半是
     *   `UfiCapsuleTabBar.CAPSULE_CORNER`，那个文件要到阶段 3 才删；
     * - 真模糊那条 dead 分支（含本常量）该不该一起删，计划 §5.10 明确要求**单独决策、
     *   单独一次改动**，混进本批会让 diff 无法审查。
     * 阶段 3 删 `UfiCapsuleTabBar` 时，本条与 [bottomInsetCorner_mustNotBePill] 一并重新决策：
     * 要么随 dead 分支删掉，要么改成守 `DOCK_SHAPE` 的 22dp。
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

    // ★★ 墓碑（2026-09-24，贴底通栏低栏迁移阶段 2.5）★★
    //
    // 已删除：`collapseOrigin_mustBeCenter`
    // 它断言 `UfiCapsuleTabBar` 的 `graphicsLayer` 里有 `TransformOrigin.Center`
    // （或等价的 `TransformOrigin(0.5f, 0.5f)`），守的是产品要求「收起时胶囊四周同时向
    // **自身中心**收拢，而不是吸附在底边上只往上收」。
    //
    // 前提消失：通栏低栏没有收起态，那个做整体缩放的 `graphicsLayer` 在阶段 3 会整块删掉，
    // 届时文件里根本不存在 transformOrigin，断言只会变成「找不到 ⇒ 红灯」的噪音。
    //
    // 与之成对的 `collapseOrigin_mustNotBeBottom` **没有删**，而是改写到了新形态上 ——
    // 见下面 [dockEnterExit_mustSlideVerticallyNotScaleFromBottom]：
    // 「底边中点锚点」这个东西在通栏形态下依然存在、依然会坏事，只是它搬去了
    // `MainNavGraph` 的**进出场**动画里（原来那条护栏只检查 tabBar，够不到那里）。

    /**
     * 底栏的**进出场**必须是整条纵向滑动，不得是「从底部中心缩放」。
     *
     * ## 本条是改写（2026-09-24，迁移阶段 2.5）
     * 原名 `collapseOrigin_mustNotBeBottom`，检查的是 `UfiCapsuleTabBar` 里不得出现
     * `TransformOrigin(0.5f, 1f)`（被替换掉的收起锚点：胶囊底边纹丝不动、只往上收）。
     *
     * 那条护栏此刻已经没有守护对象了：
     * - 它只读 `UfiCapsuleTabBar.kt`，而该组件自 `USE_BOTTOM_DOCK = true` 起不再挂载；
     * - 而真正**在线**的「底部锚点缩放」恰恰在 `MainNavGraph` 的进出场 `graphicsLayer` 里
     *   —— 旧实现就是 `scaleX/scaleY = p` + `transformOrigin = TransformOrigin(0.5f, 1f)`，
     *   老护栏够不到那个文件（旧注释里明确写着"护栏不检查该文件"）。
     *
     * 所以断言整体搬家，守的 bug 也从"收起动画锚点错"换成新形态下的等价错误：
     * 贴底通栏是一条横贯整屏、下边与屏幕底边重合的带子，按底部中心缩放会把它缩成屏幕
     * 正下方一小块、两侧露出页面 —— 出场看起来像"从地板中间长出一块方糖"。
     * 整条上下滑进滑出（`translationY` 从栏总高到 0）才是底栏的语言（§5.5）。
     *
     * 顺带钉死滑动距离的来源：必须是「安全区 + 内容区高」这个**栏总高**。
     * 迁移前那里是 `safeDrawing.getBottom(density) + 38.dp`，那个 38 照抄的是胶囊的
     * 「抬高 30 + 间距 8」—— 贴底之后它会让栏从屏幕外 38dp 处开始动（滑进来时"先慢半拍"）。
     */
    @Test
    fun dockEnterExit_mustSlideVerticallyNotScaleFromBottom() {
        val code = executableCode(source(navGraphPath))

        assertTrue(
            "进出场不再是 `translationY = (1f - p) * dockTotalHeightPx` —— 贴底通栏的进出场" +
                "必须是整条纵向滑动（p→0 整条滑到屏幕底边之下，p→1 滑回原位）。",
            Regex("""translationY\s*=\s*\(\s*1f\s*-\s*p\s*\)\s*\*\s*dockTotalHeightPx""")
                .containsMatchIn(code)
        )
        assertFalse(
            "MainNavGraph 的可执行代码里又出现了 TransformOrigin —— 「从底部中心缩放」是悬浮" +
                "胶囊的进出场语言；通栏用它会缩成屏幕正下方一小块、两侧漏出页面。",
            code.contains("TransformOrigin")
        )
        listOf("scaleX", "scaleY").forEach { token ->
            assertFalse(
                "MainNavGraph 的可执行代码里又出现了 `$token` —— 底栏进出场不得做缩放（同上）。",
                code.contains(token)
            )
        }
        assertTrue(
            "滑动距离不再由「安全区 + 内容区高」推导 —— 它必须是**栏总高**：" +
                "`WindowInsets.safeDrawing.getBottom(density) + DOCK_CONTENT_MIN_HEIGHT`。" +
                "迁移前这里是 `+ 38.dp`（照抄胶囊的抬高 30 + 间距 8），贴底后栏会从屏幕外 " +
                "38dp 处开始动。也不要在这里另写一个 58：常量必须复用通栏组件那一份。",
            Regex("""safeDrawing\.getBottom\(density\)[\s\S]{0,80}?DOCK_CONTENT_MIN_HEIGHT""")
                .containsMatchIn(code)
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
     *
     * ⚠ 2026-09-24：本条只读 `UfiCapsuleTabBar.kt`（已不再挂载，等阶段 3 删除）。
     *   **在线**形态的同一条红线由 [dockSurface_mustBeContentLayer_andWindowBackgroundStayNull]
     *   守着（指纹相同，另加窗口侧的 `setBackgroundDrawable(null)` + `wantBlur = false`）。
     *   阶段 3 删旧组件时，本条一并删除，不要以为红线跟着没了。
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
     * 底部安全区必须由**栏内 Spacer** 让位，而不是窗口 y 偏移。
     *
     * ## 本条是改写（2026-09-24，迁移阶段 2.5）
     * 原名 `capsuleLift_mustBeWired`，4 条断言：`CAPSULE_LIFT` 声明存在、且
     * `bottomOffsetPx` 的赋值表达式里同时含 `CAPSULE_LIFT` / `reservedPx` /
     * `capsuleGapFor` / `CAPSULE_SHADOW_ROOM`（视觉抬高只能**追加**在定位基线三项之上）。
     *
     * 那四个概念**整条退休**：通栏低栏的窗口 `y` 恒 0（抬高与"贴底"直接矛盾），
     * `bottomOffsetPx` / `capsuleGapFor` / `CAPSULE_SHADOW_ROOM` / `CAPSULE_LIFT` 都已删除。
     *
     * 但那条护栏真正守的东西**一点没变**：**安全区不能丢**。
     * 它只是换了消费方 —— 从「窗口 y 把整条栏抬到手势热区之上」变成
     * 「栏自己在内部留一段只有底色的 `Spacer`，图标不进那条带子」（§3 的两层分离）。
     * 丢掉它的真机后果与当年一模一样：图标/标签压在手势小白条上，点不动、或一点就回桌面。
     *
     * 因此断言改写成新链路的三段（缺一段安全区就丢了）：
     * 1. 窗口侧：`y` 恒 0，且旧的抬高概念不得复活（`CAPSULE_LIFT` / `capsuleGapFor` /
     *    `bottomOffsetPx` 一个都不许回来 —— 它们与"贴底"互斥）；
     * 2. 下发侧：宿主必须把算好的 `reservedPx` 经 `LocalCapsuleBottomReserved` 交给栏内容
     *    （**不能**让栏自己去读 WindowInsets：取值口径与防自激振荡的那套逻辑全在
     *    `readBottomReservedPx` 里，见 [insetListener_mustNotConsume]）；
     * 3. 消费侧：栏内必须真的有一段高 = 安全区的 `Spacer` —— 只下发不消费是最隐蔽的回归
     *    （值还在、注释还在，真机上图标却压着小白条，IDE 只会给一个灰色 unused 提示）。
     */
    @Test
    fun bottomSafeArea_mustBeConsumedInsideDock_notByWindowY() {
        val host = executableCode(source(blurHostPath))

        assertTrue(
            "窗口 y 不再恒 0（应为 `val desiredY: Int = 0`）—— 通栏形态下任何 y > 0 都会在栏与" +
                "屏幕底边之间露出一条页面内容。",
            Regex("""desiredY\s*:\s*Int\s*=\s*0\b""").containsMatchIn(host)
        )
        listOf(
            "CAPSULE_LIFT" to "悬浮胶囊的视觉抬高量（把整条栏抬离底边）",
            "capsuleGapFor" to "「预留区顶边 → 胶囊底边」的呼吸间距",
            "bottomOffsetPx" to "窗口 y 偏移的计算式"
        ).forEach { (token, what) ->
            assertFalse(
                "UfiCapsuleBlurHost 里又出现了 `$token`（$what）—— 它属于**悬浮**形态，" +
                    "与「贴底通栏」的前提直接矛盾：窗口 y 必须恒 0，安全区改由栏内 Spacer 让位。",
                host.contains(token)
            )
        }

        assertTrue(
            "宿主没有把算好的 reservedPx 经 `LocalCapsuleBottomReserved provides` 下发 —— " +
                "栏内的安全区 Spacer 会恒高 0（默认值），图标直接压在手势小白条上。" +
                "⚠ 不要让栏自己去读 WindowInsets：取宿主 Activity 全屏 insets、取 " +
                "max(navigationBars, systemGestures)、手势导航兜底 24dp、允许下降这几条" +
                "都封在 readBottomReservedPx 里，是踩过自激振荡的单一真源。",
            Regex("""LocalCapsuleBottomReserved\s+provides\s+reservedPx""").containsMatchIn(host)
        )

        val dock = executableCode(source(bottomDockPath))
        assertTrue(
            "UfiBottomDock 不再读 `LocalCapsuleBottomReserved.current` —— 安全区的取值链路断了。",
            Regex("""LocalCapsuleBottomReserved\.current""").containsMatchIn(dock)
        )
        assertTrue(
            "UfiBottomDock 里找不到「让位给系统栏本体」的那段 Spacer（应为 " +
                "`Spacer(modifier = Modifier.fillMaxWidth().height(bottomPadDp))`）—— " +
                "沉浸的实现就是这一句：底色铺到屏幕真实底边，**内容**靠这段 Spacer 让位。" +
                "只下发不消费时真机上图标会压在手势小白条上（点不动 / 误触返回桌面）。",
            Regex("""Spacer\([\s\S]{0,120}?height\(\s*bottomPadDp\s*\)""").containsMatchIn(dock)
        )
        assertTrue(
            "预留区的**上半段** Spacer 不见了（应为 `height(topPadDp)`）—— " +
                "2026-09-24 起预留区被拆成上下两段：下方只让开系统栏本体（navigationBars），" +
                "手势热区超出的那部分挪到内容区上方。少了上半段，栏总高会比页面 inset " +
                "少算一截（页面最后一行会被栏压住）。",
            Regex("""Spacer\([\s\S]{0,120}?height\(\s*topPadDp\s*\)""").containsMatchIn(dock)
        )
        assertTrue(
            "上下两段的和不再恒等于 reservedDp（应为 `val topPadDp: Dp = reservedDp - bottomPadDp`）" +
                "—— 这条恒等式是「栏总高不变」的全部依据：页面底部 inset、进出场滑动距离、" +
                "窗口高度三处都按 reservedDp 算，拆分只许改**内容的位置**，不许改总高。",
            Regex("""topPadDp\s*:\s*Dp\s*=\s*reservedDp\s*-\s*bottomPadDp""").containsMatchIn(dock)
        )
        assertTrue(
            "下半段不再取「系统栏本体」（应为 `LocalCapsuleBottomSystemBar.current` 并 " +
                "`coerceAtMost(reservedDp)`）—— 若改回整段 reservedPx，图标会重新被手势热区" +
                "顶高约 (systemGestures − navigationBars)（真机 112 vs 56 px），" +
                "就是「图标离屏幕下部分太远」那条反馈。",
            Regex("""LocalCapsuleBottomSystemBar\.current""").containsMatchIn(dock) &&
                Regex("""coerceAtMost\(\s*reservedDp\s*\)""").containsMatchIn(dock)
        )
    }

    /**
     * 胶囊「实时追踪」接线护栏：MainNavGraph 必须把 Pager 连续进度经
     * onSelectionProgressChange 接回 LocalUfiCapsuleSelectionProgress。
     *
     * ⚠ 2026-09-24：通栏低栏**暂不消费**这条进度（药丸只按 `selectedIndex` 走
     *   `animateFloatAsState`），当前它只服务于尚未删除的 `UfiCapsuleTabBar`。
     *   本条保持原样：把「手指滑到哪、指示器就到哪」的实时联动移植到通栏栏上时，
     *   这条线是唯一的数据源；现在拆掉、将来还得重接，且中间的窗口期无人守。
     *   阶段 3 删旧组件时一并决策：移植 ⇒ 断言不动；确定不做 ⇒ 连线与本条一起删。
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
     * 3. `motionSettlePending` 的写入点齐全：点击清零、滑动置真。少了置真那半边，
     *    横滑收尾就会丢掉防抖保护、「滑块抽搐」回归。
     *
     * ## ★ 2026-09-24（迁移阶段 2.5）：置真的入口从两处变一处
     * 原断言是「`motionSettlePending = true` **至少两处**」（页面横滑的 isScrolling 收集器 +
     * 抓住滑块拖动的 `onDragStarted`）。横向拖拽整套已删除（计划 §5.2），
     * 于是置真只剩**横滑**这一个入口。本条改为断言**恰好一处**：
     * 少了它（0 处）横滑收尾丢防抖保护，滑块抽搐回归；多出来（≥2 处）说明有人给点击路径
     * 也补了置真 —— 那正好把「点击零延迟」这条链堵死，回到"胶囊不跟手"。
     * 两个方向都得守，所以用 `assertEquals` 而不是 `>=`。
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
        assertEquals(
            "`motionSettlePending = true` 应恰好一处 —— 横向拖拽删除后（2026-09-24，计划 §5.2）" +
                "置真只剩「页面横滑的 isScrolling 收集器」这一个入口。" +
                "0 处 ⇒ 横滑收尾丢掉防抖保护、滑块抽搐回归；" +
                "≥2 处 ⇒ 大概率是给点击路径也补了置真，那会把「点击零延迟」堵死（胶囊不跟手）。",
            1,
            Regex("""motionSettlePending\s*=\s*true""").findAll(code).count()
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
     * 4. 分支选择必须读探针字段而不是闭包捕获的组合值 —— `probe.scrolling` /
     *    `probe.settleIndex` 齐全，否则分支互斥语义已被改写。
     *
     * ## ★ 2026-09-24（迁移阶段 2.5）：两处随横向拖拽退休
     * - 第 4 条原来还要求 `probe.dragging`。拖拽整套已删除（计划 §5.2：贴底后栏的左右边缘
     *   约 24dp 是系统返回手势热区，拖着切 tab 会被判成返回），`dragging` 的唯一来源
     *   `isDragging` 没了，判据恒为 false，字段已从探针里删除 ⇒ 断言随之删除。
     *   分支数也从四分支降为三分支（settling / scrolling / 常规归位）。
     * - 同时**新增**一条：`settleIndex` 必须仍在探针里、且必须在**点击**时被赋值。
     *   它极易被当成"拖拽的配套状态"一起删掉，但它防的是 **pager 的逐页扫场**：
     *   pager 收到跨多页切换请求时 `selectionProgress` 是从起点一路扫过来的，
     *   滑块若去跟它，观感就是「被拽回原处再追一遍」。而**点击跨多页同样触发逐页扫场**
     *   （点第 1 格直接跳第 4 格），拖拽只是当年最容易复现的入口，不是唯一入口。
     *   删拖拽后它只是把赋值时机从「松手」改到「点击」。
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

        listOf("probe.scrolling", "probe.settleIndex").forEach { field ->
            assertTrue(
                "分支互斥的判据里找不到 `$field` —— 分支选择又改回读闭包捕获的组合值了。" +
                    "探针化之后 `LaunchedEffect` 不再随组合重启，闭包里的值会一直停在首次组合那一份。",
                code.contains(field)
            )
        }

        // ★ settleIndex 的存在性与赋值时机（2026-09-24 新增，见上方 KDoc）。
        assertTrue(
            "`CapsuleSettleProbe` 里不再有 `settleIndex` —— 它防的不是拖拽而是 **pager 逐页扫场**：" +
                "跨多页切换时连续进度从起点一路扫来，滑块跟着它就表现为「先被拽回原处、再追一遍」。" +
                "点击跨多页（点第 1 格直接跳第 4 格）同样会触发，所以拖拽删掉后它必须留下。",
            Regex("""class\s+CapsuleSettleProbe\([\s\S]{0,200}?settleIndex\s*:\s*Int\?""")
                .containsMatchIn(code)
        )
        assertTrue(
            "找不到「点击时登记 settleIndex」（应为 CapsuleTab.onClick 里的 `settleIndex = index`）—— " +
                "拖拽删除后点击是它**唯一**的赋值入口；漏掉它等于 settleIndex 恒 null，" +
                "跨多页点击时滑块会去跟 pager 的逐页扫场。",
            Regex("""settleIndex\s*=\s*index\b""").containsMatchIn(code)
        )
        listOf(
            "probe.dragging" to "探针字段（来源 isDragging）",
            ".draggable(" to "横向拖拽手势本体",
            "onDragStopped" to "松手吸附回调"
        ).forEach { (token, what) ->
            assertFalse(
                "UfiCapsuleTabBar 的可执行代码里又出现了 `$token`（$what）—— 横向拖拽已于 " +
                    "2026-09-24 整套删除（计划 §5.2）：贴底后栏的左右边缘约 24dp 是系统返回手势" +
                    "热区，拖着切 tab 会被判成返回；重新引入还需要 setSystemGestureExclusionRects()。",
                code.contains(token)
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

    // ── 贴底通栏低栏的新红线（2026-09-24，迁移阶段 2.5 新增）────────────────────
    //
    // 这几条守的都是**通栏形态独有**的不变量：它们在悬浮胶囊时代不存在（没有"铺满安全区的
    // 底色"、没有"内容区与安全区分层"、也没有"常显标签"），所以不是改写而是新增。
    // 共同特征与上面那批一致：一行就能改回去、回归后只在真机上肉眼可见。

    /**
     * 栏底色必须画在 **Compose 内容层**，窗口背景必须恒为 `null`（红线 R1 在通栏形态下的版本）。
     *
     * `capsuleBackground_mustBeContentLayer` 守的是同一条红线，但它只读
     * `UfiCapsuleTabBar.kt` —— 那个组件已不再挂载。在线形态的底色在 `UfiBottomDock` 里，
     * 所以这一条把指纹挪过来，并把窗口侧的另一半一起钉死：
     *
     * - 内容层指纹：`clip(` + `RoundedCornerShape(` + `drawBehind { drawRect(…) }`；
     *   通栏的底色是竖向渐变 + 上缘高光 + 顶边发丝线三层，都必须在 `drawBehind` 里
     *   （且 `clip` 必须在它之前，否则三者不会被 22dp 上圆角裁住，§5.8）。
     * - 窗口层：`setBackgroundDrawable(null)` + `setFormat(PixelFormat.TRANSLUCENT)`，
     *   且真模糊那条分支必须保持 `val wantBlur = false`（唯一会挂 drawable 的分支就是它）。
     *
     * 一旦有人把底色挪回 `Window.setBackgroundDrawable` 或主题 `windowBackground`，
     * 会复活两个已修复的真机 bug：系统沿 drawable 形状描出的**外圈 1px 细线**，
     * 以及部分 ROM 把浮窗 Surface 退回**不透明黑底**（盖住下方内容）。
     *
     * 刻意**不**断言具体颜色字面量：取色是可调的产品参数，锁死它会让每次微调配色都要改测试。
     */
    @Test
    fun dockSurface_mustBeContentLayer_andWindowBackgroundStayNull() {
        val dock = executableCode(source(bottomDockPath))

        listOf(
            ".clip(" to "圆角裁剪（必须在 drawBehind 之前，否则底色/高光/发丝线不被上圆角裁住）",
            "RoundedCornerShape(" to "圆角形状（DOCK_SHAPE：上 22dp、下 0）",
            "drawBehind" to "内容层绘制入口",
            "drawRect(" to "底色本体（竖向渐变 + 上缘高光 + 顶边发丝线都走它）"
        ).forEach { (token, what) ->
            assertTrue(
                "UfiBottomDock 的可执行代码里找不到 `$token` —— 缺少$what。" +
                    "栏底色必须活在 Compose 内容层；若被挪回 Window.setBackgroundDrawable / " +
                    "主题 windowBackground，会复活「外圈 1px 细线」与「浮窗黑底」两个真机 bug。",
                dock.contains(token)
            )
        }
        // clip 必须在 drawBehind 之前（§5.8）—— 顺序错了编译照过，真机上是圆角处漏出直角。
        // ⚠ 用 `drawBehind {`（带花括号）定位调用点：裸的 `drawBehind` 会先命中 import 行。
        val clipAt = dock.indexOf(".clip(")
        val drawBehindAt = Regex("""drawBehind\s*\{""").find(dock)?.range?.first ?: -1
        assertTrue("找不到 `drawBehind {` 的调用点。", drawBehindAt >= 0)
        assertTrue(
            "UfiBottomDock 里 `.clip(` 出现在 `drawBehind {` **之后** —— 底色、上缘高光、" +
                "顶边发丝线就不会被 22dp 上圆角裁住，栏的两个上角会漏出直角。",
            clipAt in 0 until drawBehindAt
        )

        val host = executableCode(source(blurHostPath))
        assertTrue(
            "窗口背景不再被显式清成 null（`setBackgroundDrawable(null)`）—— 红线 R1：" +
                "窗口一旦挂上 drawable，系统会沿它的形状描出外圈 1px 细线。",
            Regex("""setBackgroundDrawable\(\s*null\s*\)""").containsMatchIn(host)
        )
        assertTrue(
            "清掉背景后没有 `setFormat(PixelFormat.TRANSLUCENT)` —— 部分 ROM 会把无背景浮窗" +
                "渲染成不透明黑色，盖住下方内容（红线 R1 的另一半）。",
            host.contains("setFormat(PixelFormat.TRANSLUCENT)")
        )
        assertTrue(
            "真模糊分支不再是恒关（应保留 `val wantBlur = false`）—— 那是全文件**唯一**会给窗口" +
                "挂上 drawable 的分支（setBackgroundDrawable(capsuleBackground)）。" +
                "要重新启用真模糊必须单独立项，连带复核外圈细线与黑底两个 bug。",
            Regex("""val\s+wantBlur\s*=\s*false""").containsMatchIn(host)
        )
    }

    /**
     * 安全区只能是「有底色、无内容」的一段，**选中药丸不得覆盖它**。
     *
     * 通栏形态的两层结构（§3）：
     * ```
     * Column(clip + drawBehind{ 底色铺满整个 Column，含安全区 })
     *   ├ Box{ Row(heightIn(min=58dp), drawBehind{ 选中药丸 }) }   ← 内容区
     *   └ Spacer(height = 安全区)                                  ← 只有底色
     * ```
     * 药丸画在**内容区**的 `drawBehind` 里，垂直范围天然只覆盖那 58dp。
     * 回归形态是「顺手」把药丸挪到根 `Column` 的 `drawBehind`（那里已经有底色绘制代码，
     * 看起来更"集中"）—— 三键导航下安全区约 48dp，栏总高 106dp，药丸会按 106dp 垂直居中，
     * 肉眼就是**明显偏下、压进手势/按键区**（§5.1）。
     *
     * 断言用源码顺序表达这个层级：底色绘制 → 内容区限高 → 药丸 → 安全区 Spacer。
     */
    @Test
    fun dockSelectionPill_mustBeDrawnInsideContentAreaOnly() {
        val dock = executableCode(source(bottomDockPath))

        val surfaceAt = dock.indexOf("drawRect(color = dockSurface")
        val contentAt = dock.indexOf("heightIn(min = DOCK_CONTENT_MIN_HEIGHT)")
        val pillAt = dock.indexOf("drawSelectionPill(")
        val safeAreaAt = dock.indexOf("height(bottomPadDp)")

        assertTrue(
            "找不到「底色铺满（含安全区）」的那句 `drawRect(color = dockSurface)` —— " +
                "沉浸的前提是底色铺到屏幕真实底边。" +
                "（2026-09-24 起底色是**实色**，原来是 `Brush.verticalGradient`：" +
                "两端只差 4% 亮度、真机读不出渐变，只读出「上下不一样干净」，用户定稿改实色。）",
            surfaceAt >= 0
        )
        assertTrue(
            "找不到内容区的 `heightIn(min = DOCK_CONTENT_MIN_HEIGHT)` —— 内容区与安全区的分层没了。",
            contentAt >= 0
        )
        assertTrue("找不到 `drawSelectionPill(` —— 选中态的唯一视觉载体被移除了。", pillAt >= 0)
        assertTrue("找不到安全区 Spacer 的 `height(bottomPadDp)`。", safeAreaAt >= 0)

        assertTrue(
            "底色不再画在内容区**之前**（应在根 Column 的 drawBehind 里）—— " +
                "底色必须铺满整条栏含安全区，只覆盖内容区的话安全区那段会透出页面像素。",
            surfaceAt < contentAt
        )
        assertTrue(
            "`drawSelectionPill(` 不在内容区的 drawBehind 里（当前出现在 " +
                "`heightIn(min = DOCK_CONTENT_MIN_HEIGHT)` 之前）—— 药丸一旦画到根 Column 上，" +
                "垂直居中就会按**栏总高**算：三键导航（安全区≈48dp）下药丸明显偏下、压进按键区（§5.1）。",
            contentAt < pillAt
        )
        assertTrue(
            "安全区 Spacer 不在药丸之后 —— 结构已被改写；药丸的垂直范围必须只由内容区决定。",
            pillAt < safeAreaAt
        )
    }

    /**
     * 通栏低栏内部**不得**出现 `fillMaxHeight` / `fillMaxSize`。
     *
     * ## 真机现象：整个屏幕都变成导航栏
     * 2026-09-24 首次装包就撞上。`DockTab` 当时写的是 `Modifier.fillMaxHeight()`，而 tabs 行
     * 只有 `heightIn(min = 58dp)` —— 那是**下限**，maxHeight 没有上界：底栏 Dialog 的窗口高是
     * `WRAP_CONTENT` / 实测高，传给内容的 maxHeight 仍然是整屏高度。于是单格撑满整屏，
     * 一路把 Row → Box → 根 `Column` 撑满，根 Column 的 `drawBehind` 底色随之铺满整个屏幕。
     *
     * 悬浮胶囊时代**不会**暴露这个写法：那时窗口是 wrap-content 的小窗，maxHeight 本身就只有
     * 胶囊那么高，`fillMaxHeight` 填出来的正好是胶囊高度。通栏把窗口放大到整屏宽、
     * 高度交给内容决定之后，同一句 modifier 的含义就彻底变了 —— 这是"形态迁移改变了既有写法的语义"
     * 的典型案例，光看那一行代码看不出问题，所以必须用护栏钉住。
     *
     * 正确写法是给**下限**（`defaultMinSize(minHeight = …)` / `heightIn(min = …)`），
     * 上限永远交给内容，让栏总高由「内容 + 安全区 Spacer」决定。
     */
    @Test
    fun dockMustNotFillAvailableHeight_orTheBarEatsTheWholeScreen() {
        val dock = executableCode(source(bottomDockPath))

        assertFalse(
            "`UfiBottomDock` 里出现了 `fillMaxHeight` —— 通栏窗口传下来的 maxHeight 是**整屏高度**，" +
                "填满它会让底色铺满整个屏幕（真机现象：整屏都是导航栏）。高度只能给下限，" +
                "上限交给内容：用 `defaultMinSize(minHeight = …)` 或 `heightIn(min = …)`。",
            dock.contains("fillMaxHeight")
        )
        assertFalse(
            "`UfiBottomDock` 里出现了 `fillMaxSize` —— 同上，纵向会吃掉整屏。",
            dock.contains("fillMaxSize")
        )
        assertTrue(
            "格高的下限约束不见了（应为 `defaultMinSize(minHeight = …)`）—— " +
                "少了它单格只有图标+标签那么高，触摸目标会跌破 48dp（§5.20）。",
            dock.contains("defaultMinSize(minHeight =")
        )
    }

    /**
     * 通栏低栏**不得**长出收起态那一套，标签必须常显且单行。
     *
     * 承接两条老护栏的意图（它们只读 `UfiCapsuleTabBar`，够不到新组件）：
     * - `legacyPerTabExpansionMachinery_mustBeGone` 的 `showLabel` 警戒 ——
     *   标签常显时极容易顺手起这个名字，然后就有人给它接一个"滑动时隐藏标签"的动画；
     * - 已删除的 `collapseDelay_mustBe1500ms` / `collapsedScale_*` 的形态约束。
     *
     * 另外钉死 §5.11 / §5.13 两条真机约束：
     * - 内容区必须是 `heightIn(min = …)` 而不是固定 `height(…)`：系统字体 1.5× 时 10sp 标签
     *   渲染成 15sp，固定高度会把内容压扁 / 裁切；
     * - 标签必须 `maxLines = 1` + `Ellipsis`：一换行就把内容区顶破 58dp，
     *   栏总高与页面 inset 一起跳（5 tab × 360dp 窄屏每格只有 72dp）。
     */
    @Test
    fun dockMustNotGrowCollapseMachinery_andLabelsStayVisibleSingleLine() {
        val dock = executableCode(source(bottomDockPath))

        listOf(
            "showLabel" to "标签常显，不存在「要不要显示标签」这个状态",
            "labelReveal" to "标签淡入淡出进度（收起态的产物）",
            "COLLAPSE_DELAY_MS" to "自动收起倒计时",
            "COLLAPSED_SCALE" to "收起态整体缩放",
            "expandProgress" to "展开进度（通栏的几何全部是常量，不随任何进度插值）"
        ).forEach { (symbol, why) ->
            assertFalse(
                "UfiBottomDock 里出现了 `$symbol` —— $why。通栏低栏**没有收起态**：" +
                    "标签常显、格宽等分、整条不缩放。把收起那一套搬回来等于把两种形态的" +
                    "几何假设混在一起（迁移计划 §2.4 / §4.2）。",
                dock.contains(symbol)
            )
        }

        assertTrue(
            "内容区不再是 `heightIn(min = DOCK_CONTENT_MIN_HEIGHT)` —— 固定 `height(58.dp)` 在" +
                "系统字体 1.5× 时会把内容压扁 / 裁切（§5.13）。必须允许长高，栏总高随之变大。",
            dock.contains("heightIn(min = DOCK_CONTENT_MIN_HEIGHT)")
        )
        assertFalse(
            "内容区被改成了固定高 `height(DOCK_CONTENT_MIN_HEIGHT)` —— 同上（§5.13）。" +
                "注意 `heightIn(` 不在本条管辖内：它与 `height(` 是两个不同的 token。",
            Regex("""height\(\s*DOCK_CONTENT_MIN_HEIGHT""").containsMatchIn(dock)
        )
        assertTrue(
            "标签缺少 `maxLines = 1` —— 换行会把内容区顶破 58dp，栏总高与页面 inset 一起跳" +
                "（5 tab × 360dp 窄屏每格仅 72dp，很容易触发，§5.11）。",
            Regex("""maxLines\s*=\s*1\b""").containsMatchIn(dock)
        )
        assertTrue(
            "标签缺少 `TextOverflow.Ellipsis` —— 放不下时必须省略，而不是换行或裁半个字。",
            dock.contains("TextOverflow.Ellipsis")
        )
    }

    /**
     * 页面底部 inset 必须是「内容区高 + 安全区」，且 58 这个数只能有**一份**。
     *
     * 通栏形态下栏自己覆盖了安全区那条带子（背景铺满 + 栏内 Spacer 让位），
     * 所以页面要避开的是「内容区高 + 安全区」这一整块 —— 既没有悬浮时代的 8dp 间隙，
     * 也没有抬升量。算少了页面最后一行被栏压住，算多了底部空一条。
     *
     * 两条断言：
     * 1. 计算式必须是 `DOCK_CONTENT_MIN_HEIGHT + reservedPx.toDp()`（复用通栏组件的常量）；
     * 2. `UfiCapsuleBlurHost` 里**不得**另写一个 `58.dp`。
     *    两处各写一份数字迟早漂移 —— 这正是当年把 `CAPSULE_SHADOW_ROOM` 收成单一真源的理由。
     *
     * 配套的「测量完成前不发布」闸门由 `NavInsetHandoffGuardTest` 那两条守（语义不变）。
     */
    @Test
    fun dockPageInset_mustBeContentHeightPlusSafeArea() {
        val host = executableCode(source(blurHostPath))
        val dock = executableCode(source(bottomDockPath))

        assertTrue(
            "页面底部 inset 的计算式不再是「内容区高 + 安全区」—— 应为 " +
                "`DOCK_CONTENT_MIN_HEIGHT + with(density) { reservedPx.toDp() }`。" +
                "少算会让页面最后一行被栏压住，多算会在底部空出一条。",
            Regex("""DOCK_CONTENT_MIN_HEIGHT\s*\+\s*with\(density\)\s*\{\s*reservedPx\.toDp\(\)""")
                .containsMatchIn(host)
        )
        assertFalse(
            "UfiCapsuleBlurHost 里又出现了字面量 `58.dp` —— 内容区高只能有一份真源" +
                "（`UfiBottomDock.DOCK_CONTENT_MIN_HEIGHT`）。两处各写一份数字迟早漂移。",
            host.contains("58.dp")
        )

        val contentHeight = matchOne(
            dock, """DOCK_CONTENT_MIN_HEIGHT\s*:\s*Dp\s*=\s*([\d.]+)\.dp""",
            "DOCK_CONTENT_MIN_HEIGHT"
        )
        assertEquals(
            "内容区高应为 58dp（§1 参数表定稿值 = 图标 24 + 间距 3 + 标签 10sp≈14 + 上下 8.5×2）。" +
                "改它要同时复核：触摸目标 ≥48dp、药丸 inset 4dp 后的高度、以及页面 inset。",
            "58",
            contentHeight
        )
        assertTrue(
            "`DOCK_CONTENT_MIN_HEIGHT` 不再是 `internal` —— 页面 inset 的计算式（在 " +
                "UfiCapsuleBlurHost）要复用它；收回 private 会逼着那边另写一个 58。",
            Regex("""internal\s+val\s+DOCK_CONTENT_MIN_HEIGHT""").containsMatchIn(dock)
        )
    }

    /**
     * 栏底色必须是**一个 palette token**（且只有一份），并且手势小白条的明暗必须由它推出来。
     *
     * ## 演进（两次改判都留在这里，别再走回头路）
     * - 初版：`if (palette.isDark) lerp(cardBg, Color.Black, 0.10f) else lerp(cardBg, textPrimary, 0.06f)`
     *   —— 本意是让栏比卡片稍深好跟页面分开。当时护栏钉的是「两支必须分开写」，因为
     *   合成一条 `lerp(cardBg, textPrimary, x)` 在深色主题下会把栏**提亮**（textPrimary 是浅色），
     *   方向正好反掉。这个坑本身仍然真实 —— 只是现在**根本不做这种混色**了。
     * - 2026-09-24（用户定稿"改成白底"）：底色直接就是 `palette.cardBg`。栏与卡片同色，
     *   分界交给顶边发丝线；深浅两套主题不需要各写一套系数，因为 palette 本身已按主题解析。
     *
     * ## 为什么必须是 `internal` 的单一函数
     * 手势小白条的明暗**不是**平台自动按背后像素反色的（§5.3 的原假设已被真机证伪），
     * 它只看窗口的 `isAppearanceLightNavigationBars`。栏贴底之后小白条正好压在这块颜色上，
     * 所以 `UfiCapsuleBlurHost` 必须拿**同一个**颜色算亮度。两个文件各算一份的后果是
     * 静默的：颜色一改、小白条就在某些皮肤下隐形，而编译与其它测试全绿。
     */
    @Test
    fun dockSurfaceColor_mustBeSinglePaletteToken_andDriveGestureHandleAppearance() {
        val dock = executableCode(source(bottomDockPath))
        val blurHost = executableCode(source(blurHostPath))

        assertFalse(
            "UfiBottomDock 里出现了 `isSystemInDarkTheme()` —— 它绕过「设置 → 外观 → 外观模式」：" +
                "强制浅色 / 强制深色时，栏底色会跟着**系统**明暗走，而 cardBg / accent 已经" +
                "按用户选择解析过了。唯一正确的判据是 `LocalResolvedPalette.current`。",
            Regex("""\bisSystemInDarkTheme\s*\(""").containsMatchIn(dock)
        )
        assertTrue(
            "`dockSurfaceColor` 不再是 `internal fun … = palette.cardBg` —— 它是跨文件的单一真源：" +
                "窗口层要用同一个颜色去定手势小白条的明暗。改成 private、或在这里自己混色，" +
                "都会让两处口径漂移（后果是小白条在某些皮肤下隐形，且没有任何编译错误）。",
            Regex("""internal\s+fun\s+dockSurfaceColor\s*\([^)]*\)\s*:\s*Color\s*=\s*palette\.cardBg""")
                .containsMatchIn(dock)
        )
        assertTrue(
            "`UfiCapsuleBlurHost` 没有用 `dockSurfaceColor(palette).luminance()` 推 " +
                "`lightNavHandleSurface` —— 判据必须来自栏**实际画的那块颜色**，" +
                "不能用 `palette.isDark`（皮肤里存在偏亮的深色底 / 偏暗的浅色底）。",
            Regex("""lightNavHandleSurface\s*=\s*dockSurfaceColor\(palette\)\.luminance\(\)\s*>""")
                .containsMatchIn(blurHost)
        )
        assertTrue(
            "底栏 Dialog 窗口没有设 `isAppearanceLightNavigationBars` —— 手势小白条**不会**" +
                "自己按背后像素反色（§5.3 原假设已被真机证伪）。不设它就取默认的「深背景」，" +
                "小白条恒为白，压在白底栏上直接看不见。",
            Regex("""isAppearanceLightNavigationBars\s*=\s*metrics\.lightNavHandleSurface""")
                .containsMatchIn(blurHost)
        )
        assertTrue(
            "药丸内的图标/文字颜色不再按 accent 亮度择一 —— 实色 accent 配白字，在偏亮的配色" +
                "（浅黄绿 / 浅青）上对比度不足 3:1，直接不可读（§5.14）。" +
                "判据应是 `palette.accent.luminance() > …`。",
            Regex("""accent\.luminance\(\)\s*>""").containsMatchIn(dock)
        )
    }

    /**
     * 选中药丸的横向内缩必须**引用** `Spacing.CardHorizontalMargin`，不许写成数字。
     *
     * ## 2026-09-25（用户定稿）：药丸的左右边缘要和上方卡片对在同一条竖线上
     * 这条约束表达的是「跟卡片对齐」这个**关系**，不是「内缩 16dp」这个数字。卡片横向外边距的
     * 唯一真源是 `Spacing.CardHorizontalMargin`（`UfiCardDefaults.horizontalMargin` 与
     * `UfiBannerDefaults.sideMargin` 都从它取）。写成 `16.dp` 字面量的后果是**静默失配**：
     * 将来有人把卡片外边距调成 20dp，药丸还停在 16dp —— 编译绿、全部单测绿，
     * 只有真机上能看出两条竖线错开 4dp。这与「页面 inset 里那个第二份 58」是同类问题
     * （见 `dockPageInset_mustBeContentHeightPlusSafeArea`）。
     *
     * 第二条断言封的是绕路：有人可能先写一个本地 `private val DOCK_PILL_SIDE = 16.dp` 再引用它，
     * 形式上"用了常量"，实际仍是第二份真源。所以直接禁掉本文件里的 `16.dp` 字面量 ——
     * 本文件里 16 这个数只有一个含义，就是这条对齐边距。
     *
     * ⚠ 本条**不**管图标与标签的位置。它们由「等分格 + 格内居中」决定（`(格宽 − 内容宽) / 2`，
     * 393dp 屏上约 24~29dp），用户明确表示那个布局已经很完美、只要药丸对齐卡片。
     * 「药丸对齐卡片」与「图标对齐卡片」是两件事，不要把这条护栏读成后者。
     */
    @Test
    fun dockPillInset_mustReferenceCardHorizontalMargin_notALiteral() {
        val dock = executableCode(source(bottomDockPath))

        assertTrue(
            "`PILL_INSET_H` 的定义式不再是 `Spacing.CardHorizontalMargin - DOCK_ROW_H_PADDING`。" +
                "药丸外缘压在卡片那条竖线上，靠的是这条恒等式：" +
                "`DOCK_ROW_H_PADDING + PILL_INSET_H ≡ Spacing.CardHorizontalMargin`" +
                "（屏边 → 8dp 行内边距 → 格0左缘 → 8dp 药丸内缩 → 药丸左缘 = 16dp）。" +
                "把它写成独立数值，两个数一改就错开，而且编译绿、单测绿，只有真机能看出来。" +
                "想调药丸宽窄请改 `DOCK_ROW_H_PADDING`（内缩会自动跟着变），别单独改本常量。",
            Regex(
                """val\s+PILL_INSET_H\s*:\s*Dp\s*=\s*""" +
                    """Spacing\.CardHorizontalMargin\s*-\s*DOCK_ROW_H_PADDING"""
            ).containsMatchIn(dock)
        )
        assertTrue(
            "tabs 行没有 `padding(horizontal = DOCK_ROW_H_PADDING)` —— 上面那条恒等式的另一半。" +
                "少了它，药丸外缘会从 16dp 跑到 8dp（格0左缘就是屏幕左缘），与卡片错开一半。",
            Regex("""padding\(\s*horizontal\s*=\s*DOCK_ROW_H_PADDING\s*\)""").containsMatchIn(dock)
        )
        assertFalse(
            "UfiBottomDock 的可执行代码里出现了 `16.dp` 字面量 —— 本文件里 16 这个数只有一个含义：" +
                "「与卡片对齐的药丸横向内缩」，它必须来自 `Spacing.CardHorizontalMargin`。" +
                "先定义一个本地 `private val … = 16.dp` 再引用它，同样是绕过单一真源 " +
                "（形式上用了常量，实际是第二份数字），本条一并封死。",
            dock.contains("16.dp")
        )
    }
}
