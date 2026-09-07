package com.ufi_axis.ui.animation.page

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 「切 Tab 顿一下」的**回归护栏**：宿主层不许在**组合期**读逐次翻转的状态。
 *
 * ## 被守护的三个不变量（2026-09-05 修复）
 * 1. `LocalUfiPageActive` 只能 provide **身份稳定的容器**（`State<Boolean>`），
 *    不得 provide 裸 `Boolean`。它是 `staticCompositionLocalOf` —— **没有细粒度读追踪**，
 *    provide 的值一变，provider 以下**整棵子树被强制重组**（连 skippable 的子 composable
 *    都不许跳过）。`beyondViewportPageCount = 1` 时在场三页里有**两页**的值同刻翻转
 *    ⇒ 两棵完整页子树被强制重组，恰好砸在 settle 那一帧 ⇒ 真机观感「横滑落定顿一下」
 *    （同一笔开销在点击路径上表现为「不跟手」）。
 *    与 `MainNavGraph` 里已修过的 `LocalCapsuleBottomInset`（`() -> Dp`）是同一个机制。
 * 2. `isDragged` / `programmaticAnim` 不得在组合期读（含**当 `LaunchedEffect` 的 key**）。
 *    它们一次切页要翻转 2~4 次，每次都重组整个 `PagerBackend` 函数体 →
 *    重建 `HorizontalPager` 的 item lambda → 重跑 5 个页槽的 item 组合。
 * 3. `isUfiPageForeground()` 必须**在组合期把 `.value` 读出来**并返回 `Boolean`。
 *    这是正确性红线：各 Tab 页把它的返回值当 `LaunchedEffect` / `DisposableEffect` 的 key
 *    （`DashboardScreen.startAutoRefresh` 的启停、`NetworkScreen` 首次加载、`MonitorScreen`
 *    首屏拉取）。改成返回 lambda 会让 key 恒定不变 ⇒ 轮询永远不随前后台切换启停。
 *
 * ## 为什么是源码级断言
 * 这三条都是「组合发生了多少次」这种**过程性**性质，`:app:ui` 目前只有 `testImplementation(junit)`，
 * 没有 Compose UI Test / Robolectric，无法观测重组次数。做法与 `CapsuleRegressionGuardTest`
 * 一致：纯 JVM 文件读取 + 正则，零依赖、毫秒级，目的是**锁死回归**而不是证明观感。
 */
class PageSwitcherCompositionReadGuardTest {

    private val pageActivePath =
        "src/main/java/com/ufi_axis/ui/animation/page/UfiPageActive.kt"

    private val switcherHostPath =
        "src/main/java/com/ufi_axis/ui/animation/page/UfiPageSwitcherHost.kt"

    /** 定位模块源码：Gradle 从模块目录启动，IDE 可能从仓库根启动，两种布局都试。 */
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

    /** 剥掉块注释与行注释，只留可执行代码 —— 这两个文件的注释里大量出现被断言的符号。 */
    private fun executableCode(text: String): String =
        text.replace(Regex("""/\*[\s\S]*?\*/"""), "")
            .lines()
            .joinToString("\n") { it.substringBefore("//") }

    /** 抓出全部 `LaunchedEffect(...)` 的 key 列表文本。 */
    private fun launchedEffectKeys(code: String): List<String> =
        Regex("""LaunchedEffect\(([^)]*)\)""")
            .findAll(code)
            .map { it.groupValues[1] }
            .toList()

    // ── 不变量 1：LocalUfiPageActive 只能 provide 容器 ──────────────────────────

    @Test
    fun pageActiveLocal_mustBeStateContainerNotBareBoolean() {
        val local = executableCode(source(pageActivePath))

        assertTrue(
            "LocalUfiPageActive 的类型不再是 ProvidableCompositionLocal<State<Boolean>> —— " +
                "退回裸 Boolean 会让这个 staticCompositionLocalOf 在选中页翻转时" +
                "强制重组 provider 以下整棵页子树（在场三页里有两页同刻翻转 = 两棵完整子树），" +
                "真机观感就是横滑落定「顿一下」。",
            Regex(
                """val\s+LocalUfiPageActive\s*:\s*ProvidableCompositionLocal<State<Boolean>>"""
            ).containsMatchIn(local)
        )
        assertFalse(
            "LocalUfiPageActive 又变回 `staticCompositionLocalOf { false }`（裸 Boolean 默认值）—— " +
                "默认值必须是恒为 false 的**容器**，否则类型本身就退化了。",
            Regex("""LocalUfiPageActive[\s\S]{0,120}?staticCompositionLocalOf\s*\{\s*false\s*\}""")
                .containsMatchIn(local)
        )

        val host = executableCode(source(switcherHostPath))
        val provides = Regex("""LocalUfiPageActive\s+provides\s+([^\n,]+)""")
            .findAll(host)
            .map { it.groupValues[1].trim() }
            .toList()
        assertEquals(
            "`LocalUfiPageActive provides` 应恰好两处（Pager 后端 + AnimatedContent 后端），" +
                "当前 ${provides.size} 处。两个后端必须给出一致的语义。",
            2,
            provides.size
        )
        provides.forEach { provided ->
            assertFalse(
                "宿主又在 provide **值**而不是容器（`$provided`）—— 这正是被修掉的根因：" +
                    "static local 的值一变就强制整树重组。必须 provide 每页一个、身份稳定的 State。",
                provided.contains("==") || provided == "isIncoming" || provided == "true" ||
                    provided == "false"
            )
        }
        assertTrue(
            "Pager 后端不再有 `pageActiveStates` —— 每页一个身份稳定容器的清单被删掉了。" +
                "⚠ 它必须建在 PagerBackend 作用域（由 selectedIndex 直接驱动），" +
                "不能挪进 Pager 的 item lambda：item 子组合的跳过/复用策略不由宿主控制，" +
                "选中页翻转时可能根本没人更新它 ⇒ 轮询永不启停（比顿一下严重得多）。",
            host.contains("pageActiveStates")
        )
    }

    // ── 不变量 3：isUfiPageForeground 必须在组合期读 .value ────────────────────

    @Test
    fun isUfiPageForeground_mustReadValueAtCompositionSoEffectKeysFlip() {
        val local = executableCode(source(pageActivePath))

        assertTrue(
            "isUfiPageForeground() 没有读 `LocalUfiPageActive.current.value` —— " +
                "必须在组合期把 Boolean 读出来：这次快照读把调用方 composable 订阅进容器，" +
                "前后台一翻转它就失效重组、新的 Boolean 成为新的 effect key，轮询才会正确启停。",
            local.contains("LocalUfiPageActive.current.value")
        )
        assertTrue(
            "isUfiPageForeground() 的返回类型不再是 Boolean（疑似被改成 `() -> Boolean` / State）—— " +
                "正确性红线：各 Tab 页拿它当 LaunchedEffect / DisposableEffect 的 key，" +
                "返回 lambda 会让 key 恒定 ⇒ startAutoRefresh 永远不随前后台切换启停。",
            Regex("""fun\s+isUfiPageForeground\s*\(\s*\)\s*:\s*Boolean""").containsMatchIn(local)
        )
    }

    // ── 不变量 2：isDragged / programmaticAnim 不得在组合期读 ──────────────────

    @Test
    fun pagerBackend_mustNotReadDragStateAtComposition() {
        val host = executableCode(source(switcherHostPath))

        assertFalse(
            "又出现了 `by …collectIsDraggedAsState()`（`by` 委托 = **组合期读**）—— " +
                "按下/抬手各翻转一次，每次都重组整个 PagerBackend 函数体、重跑 5 个页槽的 item 组合，" +
                "抬手那次恰好落在 settle 前后 ⇒ 「顿一下」。应保留 State 容器、在协程里读 .value。",
            Regex("""by\s+pagerState\.interactionSource\.collectIsDraggedAsState\(\)""")
                .containsMatchIn(host)
        )
        assertTrue(
            "找不到 `val isDraggedState: State<Boolean> = pagerState.interactionSource" +
                ".collectIsDraggedAsState()` —— 拖拽状态必须以容器形态持有，读取推迟到 effect 内。",
            Regex(
                """val\s+isDraggedState\s*:\s*State<Boolean>\s*=\s*pagerState\.interactionSource""" +
                    """\.collectIsDraggedAsState\(\)"""
            ).containsMatchIn(host)
        )

        launchedEffectKeys(host).forEach { keys ->
            assertFalse(
                "`LaunchedEffect($keys)` 把拖拽状态当 key —— key 是组合期读，等于把" +
                    "「整个 PagerBackend 重组」绑在了每次按下/抬手上。" +
                    "改成 `LaunchedEffect(pagerState)` + snapshotFlow(判据快照) + collectLatest。",
                keys.contains("isDragged")
            )
        }
    }

    @Test
    fun pagerBackend_mustNotReadProgrammaticAnimAtComposition() {
        val host = executableCode(source(switcherHostPath))

        launchedEffectKeys(host).forEach { keys ->
            assertFalse(
                "`LaunchedEffect($keys)` 把 programmaticAnim 当 key —— 一次切页它至少翻转两次" +
                    "（while 前置真、finally 复位），每次都重组整个 PagerBackend 函数体。",
                keys.contains("programmaticAnim")
            )
        }
        assertFalse(
            "又出现了组合期派生的 `val transitionRunning = …` —— 它读 programmaticAnim /" +
                "isScrollInProgress / remoteJump，是把「闸门判据」变成「整体重组触发器」的旧形态。" +
                "闸门应由一条长驻协程按 snapshotFlow 驱动。",
            Regex("""val\s+transitionRunning""").containsMatchIn(host)
        )
    }

    // ── 帧闸门语义：仍然是「跑起来举、停下来拖尾巴再放」 ────────────────────────

    @Test
    fun frameGate_mustStayEffectDrivenWithTail() {
        val host = executableCode(source(switcherHostPath))

        assertTrue(
            "帧闸门的 begin/end 不见了 —— 转场期间必须压住 WS 推送引发的整屏重组。",
            host.contains("UiFrameGate.begin()") && host.contains("UiFrameGate.end()")
        )
        assertTrue(
            "闸门放下不再等 FRAME_GATE_TAIL_MS —— 尾巴是 2026-09-02「快结束时糊一下然后抽一下」" +
                "的修复：攒下的 WS 状态一次性刷入绝不能和动画最后几帧同帧。",
            Regex("""delay\(FRAME_GATE_TAIL_MS\)""").containsMatchIn(host)
        )
        assertTrue(
            "闸门判据不再走 snapshotFlow —— 那就意味着它又回到组合期读（见另两条断言）。",
            Regex("""snapshotFlow\s*\{[\s\S]{0,200}?isScrollInProgress[\s\S]{0,200}?programmaticAnim""")
                .containsMatchIn(host)
        )
    }

    // ── 兜底判据的纯函数单测 ───────────────────────────────────────────────────

    @Test
    fun stuckRecovery_neverFiresWhileSomethingIsMoving() {
        // 位置与目标不符，但「有人在动」的三种情形都必须放行（绝不打断正常交互）。
        assertFalse(pagerNeedsStuckRecovery(0, 0f, 2, scrolling = true, dragged = false, programmatic = false))
        assertFalse(pagerNeedsStuckRecovery(0, 0f, 2, scrolling = false, dragged = true, programmatic = false))
        assertFalse(pagerNeedsStuckRecovery(0, 0f, 2, scrolling = false, dragged = false, programmatic = true))
    }

    @Test
    fun stuckRecovery_firesWhenIdleAndPageMismatch() {
        assertTrue(pagerNeedsStuckRecovery(0, 0f, 2, scrolling = false, dragged = false, programmatic = false))
    }

    @Test
    fun stuckRecovery_firesWhenIdleAndStuckBetweenPages() {
        // 页号对了，但停在非整数偏移上（Pager 卡在两页之间）也要补位。
        assertTrue(pagerNeedsStuckRecovery(2, 0.4f, 2, scrolling = false, dragged = false, programmatic = false))
        assertTrue(pagerNeedsStuckRecovery(2, -0.4f, 2, scrolling = false, dragged = false, programmatic = false))
    }

    @Test
    fun stuckRecovery_toleratesFloatResidue() {
        // 收尾帧常见的 1e-7 级残值必须视为「已归位」，否则每次切页都会白补一次瞬移。
        assertFalse(pagerNeedsStuckRecovery(2, 1e-7f, 2, scrolling = false, dragged = false, programmatic = false))
        assertFalse(pagerNeedsStuckRecovery(2, -0.009f, 2, scrolling = false, dragged = false, programmatic = false))
        // 刚好达到 SETTLE_EPSILON(0.01) 即视为未归位（判据是 >=）。
        assertTrue(pagerNeedsStuckRecovery(2, 0.01f, 2, scrolling = false, dragged = false, programmatic = false))
    }
}
