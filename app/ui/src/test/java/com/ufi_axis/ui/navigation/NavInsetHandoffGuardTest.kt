package com.ufi_axis.ui.navigation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 转场 / 安全区 / 底部胶囊三组不变量的**回归护栏**。
 *
 * 1. **转场容器满屏化 + 安全区移交**（2026-09-04 任务 1）
 *    `MainNavGraph` 的 `Scaffold` 必须把 `contentWindowInsets` 清零，安全区改由页面侧的
 *    `UfiScreenScaffold` 消费。任何一头被改回去都会复活「inset 边界一条静止横带」
 *    （改回默认 contentWindowInsets）或「内容顶到状态栏下 / 被手势条压住」
 *    （壳里少了 statusBarsPadding）。
 *    配套：`UfiScreenScaffold` 必须**先铺底色再收 inset**，否则那两条带子不属于本页像素，
 *    转场平移时会露出窗口底色 —— 就是 2026-09-05 用户报的「上下各一道白框」。
 *
 * 2. **切页转场模糊必须在线**（2026-09-05）
 *    Tab 切页的过渡模糊是用户明确要求保留的观感。宿主与策略侧必须同时保有
 *    `createBlurEffect` / `applyTransitionBlur` / `blurProfile` / `blurIntensity` /
 *    `LocalUfiBlurEnabled`，且 `MainNavGraph` 要把设置页开关接上去。
 *    （它曾在排查抽搐/卡顿时被整体误删；真实根因是另外三处，已单独修复。）
 *    配套（2026-09-05）：开关本身必须**实时生效** —— `ThemeManager._blurEnabled` 走 companion
 *    共享 flow（实例级会让设置页写入对 MainActivity 那份不可见），宿主侧经
 *    `rememberUpdatedState` 在 draw 期读取（组合期读裸 Boolean 会被图层 lambda 按值捕获）。
 *    两处任一退回旧形态，都会复活「改了必须杀进程重启才生效」。


 *
 * 3. **胶囊与页面不得错位**（2026-09-05）
 *    两条已回退的"优化"不许回来：
 *    - 胶囊 `Dialog` 的挂载**不得**被 `delay` 推迟到转场之后（挂载期错开会拉长胶囊
 *      内部 `remember` 初值与 `mainTabIndex` 的分叉窗口）；
 *    - `beyondViewportPageCount` **不得**随导航转场动态变化（转场途中改 Pager 入参会
 *      触发 LazyLayout 重测量，撞上 `animateScrollToPage` 会让页面停在两页之间）。
 *
 * 4. **返回时不再有「阴影闪烁」**（2026-09-04 任务 3）
 *    - 下层缩放图层「缩放期间离屏合成、静止时退回 Auto」（卡片阴影逐帧重光栅化）；
 *    - `CapsuleInsetHolder.bottomInset` 测量完成前不发布（页面在转场途中多重排一次）。
 *
 * ## 为什么是源码级断言
 * 与 `CapsuleRegressionGuardTest` 同一理由：这些不变量都没有运行期出口
 * （`contentWindowInsets` 是 Scaffold 的入参、`beyondViewportPageCount` 是 Pager 的入参，
 * 都要真实 Compose 运行时 + Robolectric 才能观测），而 `:app:ui` 只声明了
 * `testImplementation(libs.junit)`。这里退一步做**源码不变量护栏**：纯 JVM 文件读取 + 正则，
 * 零依赖、毫秒级，目的是锁死回归并在红灯时附上原因。
 */
class NavInsetHandoffGuardTest {

    // ── 被守护的源文件 ──────────────────────────────────────────────────────

    private val navGraphPath =
        "src/main/java/com/ufi_axis/ui/navigation/MainNavGraph.kt"

    private val navigationPath =
        "src/main/java/com/ufi_axis/ui/navigation/Navigation.kt"

    private val scaffoldPath =
        "src/main/java/com/ufi_axis/ui/components/common/UfiScaffold.kt"

    private val serviceStoppedPath =
        "src/main/java/com/ufi_axis/ui/components/common/UfiServiceStoppedNotice.kt"

    private val switcherHostPath =
        "src/main/java/com/ufi_axis/ui/animation/page/UfiPageSwitcherHost.kt"

    private val builtInTransitionsPath =
        "src/main/java/com/ufi_axis/ui/animation/page/BuiltInTransitions.kt"

    private val transitionContractPath =
        "src/main/java/com/ufi_axis/ui/animation/page/UfiPageTransition.kt"

    private val capsuleHostPath =
        "src/main/java/com/ufi_axis/ui/components/common/UfiCapsuleBlurHost.kt"

    private val themeManagerPath =
        "src/main/java/com/ufi_axis/ui/theme/ThemeManager.kt"

    private val settingsItemPath =
        "src/main/java/com/ufi_axis/ui/components/common/UfiSettingsItem.kt"

    /**
     * 「设置 → 外观」页。**注意这是跨模块路径**（`:app:feature-settings`），
     * 而本测试住在 `:app:ui` —— [source] 会从当前目录上溯到仓库根再拼这个相对路径，
     * 所以写成从仓库根出发的形式。
     */
    private val appearanceScreenPath =
        "app/feature-settings/src/main/java/com/ufi_axis/ui/screens/AppearanceSettingsScreen.kt"

    /** `MainActivity`（同样是跨模块路径，理由同上）。 */
    private val mainActivityPath =
        "app/src/main/java/com/ufi_axis/MainActivity.kt"



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
     * 这几个文件把「为什么不能这么做」写进了大段注释（`statusBarsPadding` /
     * `contentWindowInsets` / `beyondViewportPageCount` 都在注释里出现多次），
     * 直接全文搜索必然误报，必须先去注释再断言。
     */
    private fun executableCode(text: String): String =
        text.replace(Regex("""/\*[\s\S]*?\*/"""), "")
            .lines()
            .joinToString("\n") { it.substringBefore("//") }

    /** 取 `fun <name>(` 之后到下一个顶层 `@Composable` / `fun ` 之前的代码片段。 */
    private fun functionBody(code: String, name: String): String {
        val start = code.indexOf("fun $name(")
        assertTrue("在源码中找不到 fun $name(（可能已被重命名或删除）", start >= 0)
        val rest = code.substring(start)
        val next = Regex("""\n@Composable""").find(rest, startIndex = 1)?.range?.first
        return if (next != null) rest.substring(0, next) else rest
    }

    // ── 转场容器满屏化 ───────────────────────────────────────────────────────

    /**
     * `MainNavGraph` 的 `Scaffold` 必须显式清零 `contentWindowInsets`。
     *
     * 留默认值（`ScaffoldDefaults.contentWindowInsets` = safeDrawing）会让 `innerPadding`
     * 的上下量非零 —— `NavHost` 被内缩到安全区内，而状态栏 / 底部那两条带子由
     * `containerColor` 静态绘制、转场期间完全不动，inset 边界上就出现一条
     * 「动画到此为止」的横线（用户截图已确认）。
     */
    @Test
    fun mainNavGraph_scaffoldMustZeroContentWindowInsets() {
        val code = executableCode(source(navGraphPath))
        assertTrue(
            "MainNavGraph 的 Scaffold 必须写 `contentWindowInsets = WindowInsets(0, 0, 0, 0)`：" +
                "留默认值会让状态栏与底部那两条静止色带把转场区切断。",
            Regex("""contentWindowInsets\s*=\s*WindowInsets\(\s*0\s*,\s*0\s*,\s*0\s*,\s*0\s*\)""")
                .containsMatchIn(code)
        )
    }

    /**
     * 安全区的唯一落点在 `UfiScreenScaffold`：
     * - 根 `Column` 消费 statusBars（header 紧贴状态栏下沿）；
     * - 内容 `Box` 消费 navigationBars（内容避开手势条，header 仍沉浸）。
     */
    @Test
    fun ufiScreenScaffold_mustConsumeInsetsItself() {
        val body = functionBody(executableCode(source(scaffoldPath)), "UfiScreenScaffold")
        assertTrue(
            "UfiScreenScaffold 的根 Column 必须 `statusBarsPadding()`：宿主 Scaffold 的 " +
                "contentWindowInsets 已清零，少了这一句所有页面标题都会顶到状态栏底下。",
            body.contains("statusBarsPadding()")
        )
        assertTrue(
            "UfiScreenScaffold 的内容区必须 `windowInsetsPadding(WindowInsets.navigationBars)`：" +
                "少了这一句列表底部会被手势条压住。",
            body.contains("windowInsetsPadding(WindowInsets.navigationBars)")
        )
    }

    /**
     * ★ 顺序铁律（2026-09-05「转场中上下各一道白框」）：
     * `background(palette.pageBg)` 必须排在 `statusBarsPadding()` **之前**。
     *
     * 反过来（或干脆不画背景，让祖先兜底）会让状态栏 / 手势条那两条带子不属于本页像素：
     * 转场中页面整体平移 + `ufiNavRecedeLayer` 的 `CompositingStrategy.Offscreen` 把内容裁到
     * 图层边界，那两条带子露出的就是图层外的窗口底色 —— 真机是「上下各一道白框」，
     * 卡片阴影让它更显眼。
     */
    @Test
    fun ufiScreenScaffold_mustPaintPageBgBeforeInsetPadding() {
        val body = functionBody(executableCode(source(scaffoldPath)), "UfiScreenScaffold")
        val flat = body.replace(Regex("""\s+"""), "")
        val bgAt = flat.indexOf(".background(palette.pageBg)")
        val insetAt = flat.indexOf(".statusBarsPadding()")
        assertTrue(
            "UfiScreenScaffold 的根 Column 必须自己 `.background(palette.pageBg)`：" +
                "靠祖先兜底时，状态栏那条带子不是本页像素，转场平移/离屏裁剪会露出窗口白底。",
            bgAt >= 0
        )
        assertTrue("找不到 statusBarsPadding()，本条护栏的前提已不成立。", insetAt >= 0)
        assertTrue(
            "顺序错了：`background(palette.pageBg)` 必须排在 `statusBarsPadding()` 之前 —— " +
                "先把底色铺到屏幕物理边缘，再用 inset 收内容。当前 background@$bgAt / inset@$insetAt。",
            bgAt < insetAt
        )
    }


    /**
     * `UfiHeader` 自己**不得**再加 statusBars 避让 —— 那会与 `UfiScreenScaffold` 的
     * 那一句叠成两倍状栏高（2026-08-20 issue #3 修过一次，别再回头）。
     */
    @Test
    fun ufiHeader_mustNotDoubleApplyStatusBarInset() {
        val body = functionBody(executableCode(source(scaffoldPath)), "UfiHeader")
        assertFalse(
            "UfiHeader 不得自带 statusBarsPadding：安全区由 UfiScreenScaffold 消费一次，" +
                "两处都加会把标题往下推两倍状栏高（issue #3）。",
            body.contains("statusBarsPadding")
        )
    }

    /**
     * `UfiServiceStoppedNotice` 是唯一绕过 `UfiScreenScaffold` 的常规目的地内容
     * （`ServiceGate` 会直接替换页面），必须自带安全区。
     */
    @Test
    fun serviceStoppedNotice_mustPadItsOwnSafeArea() {
        val code = executableCode(source(serviceStoppedPath))
        assertTrue(
            "UfiServiceStoppedNotice 绕过了 UfiScreenScaffold（ServiceGate 直接替换页面内容），" +
                "必须自己 `safeDrawingPadding()`，否则标题会压在状态栏下、按钮被手势条盖住。",
            code.contains("safeDrawingPadding()")
        )
    }

    // ── 切页转场模糊必须在线（2026-09-05） ────────────────────────────────────

    /**
     * 页面切换宿主的**可执行代码**中必须保有完整的过渡模糊管线。
     *
     * 整套由四段构成，缺任一段「过渡模糊」就是个改了没反应的假开关：
     * 1. `LocalUfiBlurEnabled` —— 「设置 → 外观 → 切换动画」里的开关经 CompositionLocal 下传；
     * 2. `UfiPageTransition.blurProfile` / `blurIntensity` —— 策略侧的模糊曲线与强度倍率；
     * 3. 宿主的 `applyTransitionBlur` / `RenderEffect.createBlurEffect` —— 把归一化系数换算成
     *    实际半径并逐帧写进 `graphicsLayer`；
     * 4. `MainNavGraph` 里 `LocalUfiBlurEnabled provides blurEnabled` 的接线 —— 开关到宿主的
     *    唯一通路，断了这条则设置页改了也不生效。
     *
     * ## 为什么要正向守住
     * 这套曾在 2026-09-05 排查「切页抽搐 / 卡顿」时被整体误删。真实根因另在三处
     * （胶囊索引双数据源、`UfiScreenScaffold` 缺 `background()`、胶囊窗口 relayout 重放），
     * 都已单独修复，模糊本身不是根因。护栏在此，避免下次排查时再被顺手删掉。
     *
     * ⚠ 本条只管**切页转场**。窗口级真模糊（`UfiCapsuleBlurHost` 的 `FLAG_BLUR_BEHIND` /
     * `setBackgroundBlurRadius`）是另一回事，因真机 bug 永久禁用，由
     * `CapsuleRegressionGuardTest` 反向守着，两条互不冲突。
     */
    @Test
    fun pageSwitcher_mustBlurDuringTransition() {
        val host = executableCode(source(switcherHostPath))
        listOf(
            "createBlurEffect" to "宿主不再给页面图层挂高斯模糊 RenderEffect",
            "applyTransitionBlur" to "过渡模糊的施加函数被删除",
            "LocalUfiBlurEnabled" to "过渡模糊开关（CompositionLocal）被删除",
            "TRANSITION_BLUR_MAX_RADIUS_DP" to "模糊峰值半径令牌被删除，半径无从换算",
        ).forEach { (token, why) ->
            assertTrue(
                "UfiPageSwitcherHost 的可执行代码里找不到 `$token` —— $why。" +
                    "Tab 切页的过渡模糊是用户明确要求保留的观感，别再当成卡顿根因删掉。",
                host.contains(token)
            )
        }

        val contract = executableCode(source(transitionContractPath))
        val builtIns = executableCode(source(builtInTransitionsPath))
        listOf("blurProfile", "blurIntensity").forEach { token ->
            assertTrue(
                "UfiPageTransition 契约里找不到 `$token` —— 策略侧的模糊接口被删除，" +
                    "宿主拿不到曲线/强度，模糊会整体退化。",
                contract.contains(token)
            )
            assertTrue(
                "BuiltInTransitions 里找不到 `$token` —— 内置策略（Fade 曲线 / Slide 强度）" +
                    "的模糊参数被删除。",
                builtIns.contains(token)
            )
        }

        val navGraph = executableCode(source(navGraphPath))
        assertTrue(
            "MainNavGraph 里找不到 `LocalUfiBlurEnabled provides` —— 「设置 → 外观」的开关" +
                "到宿主的唯一接线断了，改了开关不会有任何反应（按仓内口径就是假开关）。",
            navGraph.contains("LocalUfiBlurEnabled provides")
        )
        assertTrue(
            "MainNavGraph 不再读 `themeManager.blurEnabled` —— 开关状态没有来源，" +
                "下传的值会恒为 CompositionLocal 默认值。",
            navGraph.contains("themeManager.blurEnabled")
        )
    }

    /**
     * `ThemeManager._blurEnabled` 必须指向 **companion 共享** 的 flow，不得是实例级 flow。
     *
     * 2026-09-01 已经为 `pageTransition` / `transitionDurationMs` 做过同一件事，理由写在
     * `ThemeManager` 里：本类会被 new 出多份（MainActivity / 设置页 / 胶囊各一份），设置页
     * 那份是 `observeExternal = false`（只写不听），实例级 flow 之间只能靠 SharedPreferences
     * 回调同步 —— 那条链实测不可靠，表现就是「设置页改了，必须杀进程重进才生效」。
     * `blurEnabled` 当时被漏下，2026-09-05 补齐。别再改回 `MutableStateFlow(prefs.getBoolean(...))`。
     */
    @Test
    fun themeManager_blurEnabledMustBeProcessShared() {
        val code = executableCode(source(themeManagerPath))
        assertTrue(
            "ThemeManager._blurEnabled 必须写成 `= sharedBlurEnabled`（companion 级共享 flow）：" +
                "实例级 flow 会让设置页的写入对 MainActivity 那份实例不可见，" +
                "「过渡模糊」开关就会退化成**改了必须杀进程重启才生效**。",
            Regex("""_blurEnabled\s*:\s*MutableStateFlow<Boolean>\s*=\s*sharedBlurEnabled""")
                .containsMatchIn(code)
        )
        assertFalse(
            "ThemeManager._blurEnabled 又变回 `MutableStateFlow(prefs.getBoolean(...))` 的实例级形态 —— " +
                "这正是「改了必须杀进程重启才生效」的成因，与 pageTransition / transitionDurationMs 同一病根。",
            Regex("""_blurEnabled\s*=\s*MutableStateFlow\(""").containsMatchIn(code)
        )
        assertTrue(
            "companion 里找不到 `sharedBlurEnabled` 的声明 —— 共享 flow 被删除。",
            Regex("""private\s+val\s+sharedBlurEnabled\s*=\s*MutableStateFlow\(""")
                .containsMatchIn(code)
        )
        assertTrue(
            "init 里必须有 `blurSeeded` 播种守卫：Boolean 没有可当哨兵的非法值" +
                "（true/false 都合法），少了它后建的实例会用磁盘旧值把用户刚改的内存值盖回去。",
            code.contains("blurSeeded")
        )
    }

    /**
     * 「外观」页其余四项设置也必须走 **companion 共享 flow**：
     * `themeMode` / `selectedThemeId` / `uiScalePercent` / `dynamicEnabled`。
     *
     * 2026-09-05：`themeMode` 是本轮的真 bug —— 用户报「深色/浅色选了没反应，只能停在自动」。
     * 链路断点很具体：设置页那份实例（`observeExternal = false`）把 `ThemeMode.DARK` 写进
     * **自己**的 MutableStateFlow 并落盘，而 `MainActivity` 是从**它自己那份实例**的另一个
     * flow 对象 `collectAsState()` 出来算 `isDark` 的。两个对象之间只有 SharedPreferences
     * 回调这一条桥，而那条桥在 2026-09-01 就已判定不可靠（见上一条测试与 ThemeManager 注释）。
     * 「只能停在自动」恰好是这个结论的反证：AUTO 走 `isSystemInDarkTheme()`，是唯一一档
     * **不需要** flow 传递的取值，所以只有它看起来"正常"。
     *
     * 同批把 `selectedThemeId` / `uiScalePercent` 一起搬过来（同一失效模式）。
     * 2026-09-05 下午新增的 `dynamicEnabled`（Material You 动态取色开关）**第一天就按这个
     * 范式写**：它与上面几项在同一个设置页、走同一条跨实例链路，没有理由认为它会例外。
     *
     * 四种哨兵刻意各不相同，分别对应四种取值域，别互相抄错：
     *   • String → 空串；• enum → 独立标记位；• Int → -1；• Boolean → 独立标记位。
     */
    @Test
    fun themeManager_appearanceFlowsMustBeProcessShared() {
        val code = executableCode(source(themeManagerPath))

        // ── 四个实例字段必须指向 companion 的共享 flow ──
        listOf(
            Triple("_themeMode", "ThemeMode", "sharedThemeMode")
                to "外观模式（深色/浅色/自动）会退化成「选了没反应，只能停在自动」",
            Triple("_selectedThemeId", "String", "sharedSelectedThemeId")
                to "皮肤切换会退化成「改了必须杀进程重进才生效」",
            Triple("_uiScalePercent", "Int", "sharedUiScalePercent")
                to "界面缩放会退化成「拖完滑块没反应」",
            Triple("_dynamicEnabled", "Boolean", "sharedDynamicEnabled")
                to "动态取色会退化成「拨了开关要重启才变色」",
        ).forEach { (spec, why) ->
            val (field, type, shared) = spec
            assertTrue(
                "ThemeManager.$field 必须写成 `: MutableStateFlow<$type> = $shared`" +
                    "（companion 级共享 flow）：实例级 flow 会让设置页的写入对 MainActivity" +
                    "那份实例不可见，$why。",
                Regex("""$field\s*:\s*MutableStateFlow<$type>\s*=\s*$shared""")
                    .containsMatchIn(code)
            )
            assertFalse(
                "ThemeManager.$field 又变回 `MutableStateFlow(prefs.get…)` 的实例级形态 —— $why。",
                Regex("""$field\s*=\s*MutableStateFlow\(""").containsMatchIn(code)
            )
            assertTrue(
                "companion 里找不到 `$shared` 的声明 —— 共享 flow 被删除。",
                Regex("""private\s+val\s+$shared\s*=\s*MutableStateFlow\(""")
                    .containsMatchIn(code)
            )
        }

        // ── 播种哨兵：三种取值域三种写法，缺一个就会被磁盘旧值回灌 ──
        assertTrue(
            "init 里必须有 `themeModeSeeded` 播种守卫：ThemeMode 三个值全合法，" +
                "没有可借用的非法哨兵。**不要**改成加第四个 UNSEEDED 枚举值 —— " +
                "那会污染 MainActivity 里 `when (themeMode)` 的穷尽分支。",
            code.contains("themeModeSeeded")
        )
        assertFalse(
            "ThemeMode 里出现了 `UNSEEDED` 枚举项：播种状态不该进枚举，用独立标记位表达。",
            Regex("""enum\s+class\s+ThemeMode\s*\{[^}]*UNSEEDED""").containsMatchIn(code)
        )
        assertTrue(
            "init 里必须按空串哨兵播种 `sharedSelectedThemeId`（空串不是合法皮肤 id）。",
            Regex("""sharedSelectedThemeId\.value\.isEmpty\(\)""").containsMatchIn(code)
        )
        assertTrue(
            "companion 里必须有 `UI_SCALE_UNSEEDED`（= -1）哨兵：合法区间 90~120，" +
                "负数不可能是真实取值。",
            Regex("""UI_SCALE_UNSEEDED\s*=\s*-1""").containsMatchIn(code)
        )
        assertTrue(
            "init 里必须有 `dynamicSeeded` 播种守卫：Boolean 的 true/false 都合法，" +
                "没有可借用的非法哨兵（同 blurSeeded）。少了它，后建的实例会用磁盘旧值" +
                "把用户刚拨的开关盖回去。",
            code.contains("dynamicSeeded")
        )
        assertTrue(
            "动态取色的播种必须过一遍能力闸门 `isDynamicColorAvailable(...)`：" +
                "盘上存着 true 但当前设备取不到动态色资源时，内存值必须落回 false —— " +
                "否则 UI 上开关是开的、palette 却回落到静态皮肤，就是本仓明令禁止的假开关。",
            Regex("""isDynamicColorAvailable""").containsMatchIn(code)
        )

        // ── 老用户迁移：出厂默认曾是 "aurora"，磁盘上的旧 id 必须被规整 ──
        assertTrue(
            "init 的皮肤播种必须过一遍 `ThemePresets.normalizeThemeId(...)`：" +
                "出厂默认曾是 \"aurora\"，那套预设已删除，不规整就会让老用户拿到未知 id。",
            code.contains("ThemePresets.normalizeThemeId")
        )
        assertFalse(
            "ThemeManager 里又出现了 \"aurora\" 作为默认皮肤 —— 该预设已删除，" +
                "写回去等于把默认皮肤指到一个不存在的 id 上。",
            code.contains("\"aurora\"")
        )

        // ── prefListener 必须**不再**处理这五个键：留着会让磁盘旧值把内存新值盖回去 ──
        listOf(
            "KEY_THEME_MODE" to "外观模式",
            "KEY_THEME_ID" to "皮肤 id",
            "KEY_UI_SCALE_PERCENT" to "界面缩放",
            "KEY_DYNAMIC_ENABLED" to "动态取色",
            "KEY_CUSTOM_SEED" to "自定义皮肤的种子色",
        ).forEach { (key, what) ->
            assertFalse(
                "prefListener 里仍有 `$key ->` 分支（$what）。这几项已改走共享 flow，" +
                    "回调分支不但多余还有害：磁盘旧值会在某些时序下把内存里的新值盖回去，" +
                    "正是 blurSeeded 注释里点名要避免的回灌。",
                Regex("""$key\s*->""").containsMatchIn(code)
            )
        }

        // ── 已删除的死代码不许**按原样**复活 ──
        //
        // 2026-09-05 下午：`enableDynamic` / `dynamicEnabled` / `KEY_DYNAMIC_ENABLED`
        // **已从本清单移除** —— 动态取色按正式设计稿回归了，而且是按 companion 共享 flow
        // 的范式重写的（上面那组断言在管它）。留在黑名单里会把正确实现判成回归。
        //
        // 2026-09-05 傍晚：「自定义」也回归了，但下面这三个 token **继续禁止**，因为
        // 回归的不是它们。当年那套的语义是"自定义**强调色**"（只覆盖 accent 一个槽、
        // 全仓零调用点、UI 不可达）；现在的实现是"自定义**种子色**"
        // （`setCustomSeedColor` / `customSeedColor` / `KEY_CUSTOM_SEED`，17 个槽由
        // `buildCustomPalette` 推导，有真正的取色 UI）。名字不同不是巧合，是为了让
        // "写回老名字"这件事继续红灯 —— 老键的数据语义与新实现不兼容。
        listOf(
            "setCustomAccent" to "只改 accent 一个槽的旧「自定义强调色」写入口",
            "customAccentColor" to "旧「自定义强调色」的 flow",
            "KEY_CUSTOM_ACCENT" to "旧「自定义强调色」的 prefs 键",
        ).forEach { (token, what) ->
            assertFalse(
                "ThemeManager 的可执行代码里又出现了 `$token`（$what）。" +
                    "「自定义」皮肤已于 2026-09-05 傍晚回归，但走的是**新的一套**：" +
                    "setCustomSeedColor / customSeedColor / KEY_CUSTOM_SEED + buildCustomPalette。" +
                    "老 token 的语义只覆盖 accent 一个槽，与新实现不兼容，不许混用。",
                code.contains(token)
            )
        }

        // ── 动态取色的关键接线（正向断言，与上面的黑名单互补）──
        assertTrue(
            "ThemeManager 必须提供 `setDynamicEnabled(` —— 它是「设置 → 外观」那个开关" +
                "的唯一写入口。",
            code.contains("fun setDynamicEnabled(")
        )
        assertTrue(
            "`getCurrentPalette()` 必须在动态取色开启时返回 `dynamicPaletteOrNull(...)`，" +
                "否则开关拨了但 palette 还是静态皮肤（假开关）。",
            Regex("""_dynamicEnabled\.value[\s\S]{0,200}dynamicPaletteOrNull""")
                .containsMatchIn(code)
        )

        // ── 自定义皮肤的关键接线（与动态取色同构的一组正向断言）──
        assertTrue(
            "ThemeManager 必须提供 `setCustomSeedColor(` —— 它是取色器的唯一写入口。",
            code.contains("fun setCustomSeedColor(")
        )
        assertTrue(
            "种子色必须是 companion 共享 flow（`sharedCustomSeedColor`）+ 独立播种标记位" +
                "（`customSeedSeeded`）。用实例级 flow 的表现是「取色器里选完颜色要杀进程" +
                "重进才生效」—— 这个坑本仓已经踩过七次。",
            code.contains("sharedCustomSeedColor") && code.contains("customSeedSeeded")
        )
        assertTrue(
            "`getCurrentPalette()` 必须在皮肤 id 是 custom 且**种子色存在**时返回 " +
                "`buildCustomPalette(...)`；种子色缺失（CUSTOM_SEED_UNSET）时要落到 findById 兜底。",
            Regex("""CUSTOM_THEME_ID[\s\S]{0,160}CUSTOM_SEED_UNSET[\s\S]{0,160}buildCustomPalette""")
                .containsMatchIn(code)
        )
    }

    /**
     * [UfiSettingsItem] 的默认图标色**必须**跟随主题（`palette.accent`）。
     *
     * 本测试 2026-09-05 建立时是**反向**的（"不得是 accent"）：当时默认皮肤把 `accentDark`
     * 写成 0xFF555555，压在 `cardBgDark`（0xFF2A2A2A）上只有约 1.9:1，深色下设置项图标一片灰，
     * 于是单点把 tint 改成 `textPrimary` 绕开。
     *
     * 2026-09-08 根因已修：默认皮肤的三个 accent 槽改成"深色态取亮、浅色态取深"
     * （accentDark → 0xFFB0B0B0，对卡面 6.6:1），accent 当前景全站合法，绕道随之作废。
     * 绕道的实际代价是不一致 —— 工具页 / 入口卡图标跟随主题色，设置页入口图标却恒为黑白，
     * 换红、蓝等彩色皮肤时尤其明显。现在锁的是"跟随主题"这个不变量。
     *
     * 具体色值的对比度由 `ColorTest` 的「7 套预设 × 明暗」不变量量化钉住；
     * 这里只锁取色来源，因为 tint 的来源没有运行期出口（要 Compose 运行时才能观测）。
     */
    @Test
    fun settingsItem_defaultIconTintMustFollowTheme() {
        val code = executableCode(source(settingsItemPath))
        assertTrue(
            "UfiSettingsItem 的 `effectiveIconTint` 必须回落到 `palette.accent`：" +
                "设置页入口图标要与工具页 / UfiEntryCard 的图标同口径跟随主题皮肤。",
            Regex("""effectiveIconTint[\s\S]{0,160}palette\.accent""")
                .containsMatchIn(code)
        )
    }


    /**
     * 「外观 → 主题皮肤」入口的三条不变量（2026-09-05 下午，随彩色皮肤 + 动态取色开关）。
     *
     * 1. **皮肤入口必须存在且可选**：皮肤从 1 套变回多套，入口不能停在"只读一行"
     *    或干脆缺失（那两种在仓内口径里都是假开关）。皮肤网格必须遍历
     *    `ThemePresets.allPresets` —— 写死列表会让"加 / 删皮肤"变成改两处，漏改就是死代码
     *    （2026-09-05 傍晚删橙 / 青时这条的价值刚被验证过一次：预设表改完 UI 零改动）。
     * 2. **动态取色开启时皮肤入口必须真的禁用**：`enabled = !dynamicEnabled` **且**
     *    `onClick` 置 null。只给 enabled 只会把文字变灰、行照样能点进去弹窗
     *    （`UfiSettingsItem` 的 `enabled` 只影响文字色，不影响 clickable），
     *    那就成了"看起来禁用、按下去照样能改"的假禁用 —— 两套取色源冲突正是要防的。
     * 3. **置灰必须带原因**：副标题要写明"动态取色已开启，皮肤由壁纸决定"，
     *    不能只变灰让用户猜。
     *
     * 为什么是源码级断言：这三条都没有运行期出口（`enabled` / `onClick` 是 Composable 的
     * 入参，要 Compose 运行时 + Robolectric 才能观测），而 `:app:ui` 只有 junit。
     * 同 `CapsuleRegressionGuardTest` 的口径。
     */
    @Test
    fun appearanceScreen_themeSkinEntryMustBeDisabledWhenDynamicColorIsOn() {
        val code = executableCode(source(appearanceScreenPath))

        // ── 1. 入口存在，且皮肤网格遍历预设表 ──
        assertTrue(
            "外观页找不到「主题皮肤」入口 —— 多套皮肤没有选择入口就全是死代码。",
            code.contains("\"主题皮肤\"")
        )
        assertTrue(
            "皮肤网格必须遍历 `ThemePresets.allPresets`（不许写死 id 列表）：" +
                "写死等于「加皮肤要改两处」，漏改的表现是新皮肤在 UI 上不可达。",
            Regex("""ThemePresets\.allPresets\s*\.\s*map""").containsMatchIn(code)
        )
        assertTrue(
            "皮肤选择必须复用既有的 `UfiOptionGrid`（弹窗内双栏选项网格），不要新造选择器。",
            code.contains("UfiOptionGrid(")
        )
        assertTrue(
            "皮肤网格的确认动作必须落到 `themeManager.setTheme(` —— 否则选了不生效。",
            code.contains("themeManager.setTheme(")
        )

        // ── 2. 动态取色开启时真的禁用（enabled + onClick 双管）──
        assertTrue(
            "「主题皮肤」入口必须写 `enabled = !dynamicEnabled`：动态取色开着的时候" +
                "两套取色源会冲突，皮肤入口必须置灰。",
            Regex("""enabled\s*=\s*!dynamicEnabled""").containsMatchIn(code)
        )
        assertTrue(
            "光有 `enabled = false` 不够 —— UfiSettingsItem 的 enabled 只改文字色，" +
                "行仍然可点。必须同时把 onClick 置 null：`onClick = if (dynamicEnabled) null else …`。",
            Regex("""onClick\s*=\s*if\s*\(dynamicEnabled\)\s*null""").containsMatchIn(code)
        )

        // ── 3. 置灰必须说明原因 ──
        assertTrue(
            "置灰必须给出原因文案（例如「动态取色已开启，皮肤由壁纸决定」），" +
                "只变灰不解释等于让用户自己猜为什么点不动。",
            code.contains("动态取色已开启")
        )

        // ── 附带：动态取色开关本身也不许是假开关 ──
        assertTrue(
            "「动态取色」开关必须按能力探测结果置灰（`enabled = dynamicSupported`），" +
                "并写明原因（需要 Android 12 及以上）。",
            Regex("""enabled\s*=\s*dynamicSupported""").containsMatchIn(code)
        )
        assertTrue(
            "开关不可用时必须写明版本要求，不能静默失效。",
            code.contains("Android 12")
        )
        assertTrue(
            "开关的写入口必须是 `themeManager.setDynamicEnabled(`。",
            code.contains("themeManager.setDynamicEnabled(")
        )
    }

    /**
     * 「自定义」皮肤取色器的四条不变量（2026-09-05 傍晚）。
     *
     * 1. **自定义必须真的能选到**：皮肤网格里要有一格 `CUSTOM_THEME_ID`，点它进取色器。
     *    只加 `buildCustomPalette` 而没有入口，就是 2026-09-05 上午那次删除的原因重演
     *    （"全仓零调用、UI 不可达"）。
     * 2. **确认必须同时落两个键**：`setCustomSeedColor` 写种子色 + `setTheme` 把皮肤切到
     *    custom。只写前者的表现是"选完颜色没反应"（皮肤还停在旧的那套）。
     * 3. **必须有对比度闸门**：确认按钮由 `paletteContrastViolations` 把关。
     *    这条对应硬规则"不许静默给出一个不合格的配色"。
     * 4. **预览必须诚实**：预览要显示推导后的整套配色（至少 accent 实底 + 卡面 + 正文），
     *    而不是只显示种子色 —— 种子色只决定 accent，而且还会被明度夹取。
     *
     * 另外**刻意不为自定义再加一道动态取色置灰断言**：自定义是皮肤网格里的一格，
     * 网格在弹窗里，弹窗的唯一入口是上一条测试已经钉住的那条被置灰的「主题皮肤」行。
     * 下面第五组断言把这个推理钉住 —— 若将来有人把取色器提成一个独立的顶层入口，
     * 那道闸门就绕过去了，这条会红灯。
     */
    @Test
    fun appearanceScreen_customColorPickerMustBeReachableGatedAndHonest() {
        val code = executableCode(source(appearanceScreenPath))

        // ── 1. 网格里有自定义那一格，点它进取色器 ──
        assertTrue(
            "皮肤网格必须追加一格 `CUSTOM_THEME_ID` —— 没有入口的 buildCustomPalette 就是死代码。",
            Regex("""UfiOptionItem\([\s\S]{0,120}value\s*=\s*CUSTOM_THEME_ID""").containsMatchIn(code)
        )
        assertTrue(
            "点自定义那一格必须切到取色器弹窗（`AppearanceDialog.CUSTOM_COLOR`），" +
                "而不是只把草稿设成 custom —— 没有种子色的 custom 是一个确认了也没颜色的半套状态。",
            Regex("""CUSTOM_THEME_ID\s*\)\s*\{[\s\S]{0,160}AppearanceDialog\.CUSTOM_COLOR""")
                .containsMatchIn(code)
        )

        // ── 2. 确认时两个键都要写 ──
        assertTrue(
            "取色器确认必须调 `themeManager.setCustomSeedColor(`（写种子色）。",
            code.contains("themeManager.setCustomSeedColor(")
        )
        assertTrue(
            "取色器确认必须紧接着把皮肤切到 custom（`themeManager.setTheme(CUSTOM_THEME_ID)`），" +
                "只写种子色的表现是「选完颜色没反应」——皮肤还停在旧的那套。",
            Regex("""setCustomSeedColor\([\s\S]{0,200}setTheme\(\s*CUSTOM_THEME_ID\s*\)""")
                .containsMatchIn(code)
        )

        // ── 3. 对比度闸门 ──
        assertTrue(
            "取色器必须用 `paletteContrastViolations(` 算硬指标 —— 这是「不许静默给出" +
                "不合格配色」那条硬规则的落点。",
            code.contains("paletteContrastViolations(")
        )
        assertTrue(
            "不合格时必须**禁用**确认按钮（`enabled = violations.isEmpty()`），" +
                "不是只提示一句然后照样放行。",
            Regex("""enabled\s*=\s*violations\.isEmpty\(\)""").containsMatchIn(code)
        )

        // ── 4. 预览诚实：整套配色都要出现在预览里 ──
        assertTrue(
            "预览必须基于 `buildCustomPalette(` 的结果渲染，而不是直接画种子色。",
            code.contains("buildCustomPalette(")
        )
        listOf("accent", "cardBg", "pageBg", "textPrimary", "textSecondary", "onAccent").forEach { slot ->
            assertTrue(
                "预览里必须出现 `p.$slot` —— 只显示种子色会误导用户：种子色只决定 accent，" +
                    "而且还会被明度夹取（见 CUSTOM_ACCENT_LIGHT_L_MIN）。",
                Regex("""p\.$slot\b""").containsMatchIn(code)
            )
        }
        assertTrue(
            "明度被夹取时必须给一句解释（`customSeedLightnessIsClamped`），" +
                "否则用户看到的是「滑块还在动、色块不再变」= 一个坏了的滑块。",
            code.contains("customSeedLightnessIsClamped(")
        )

        // ── 5. 自定义不许有独立于「主题皮肤」行的顶层入口 ──
        assertFalse(
            "取色器弹窗只许从「主题皮肤」的皮肤网格进（那条行已被动态取色置灰）。" +
                "出现第二个打开它的地方（例如外观页顶层再放一行）就绕过了置灰闸门，" +
                "表现是「皮肤入口灰了但自定义还能点」。",
            Regex("""AppearanceDialog\.CUSTOM_COLOR""").findAll(code).count() > 3
        )
    }

    /**
     * `MainActivity` 建 palette 的 `remember` key 必须**覆盖 getCurrentPalette 的全部输入**。
     *
     * `ThemeManager.getCurrentPalette()` 是**非 Composable** 的 `.value` 直读，它自己不会
     * 因为 flow 变化而重算 —— 重算完全靠 `remember` 的 key 变化触发。它现在读**三个**输入：
     * `selectedThemeId`（皮肤 id）、`dynamicEnabled`（壁纸取色开关）、
     * `customSeedColor`（自定义皮肤的种子色，2026-09-05 傍晚新增）。
     *
     * 漏掉任何一个的表现都非常具体，而且都不像 bug 像"要重启才生效"：
     * - 漏 `dynamicEnabled` = **拨了动态取色开关整页颜色不动**；
     * - 漏 `customSeedColor` = **在取色器里换了颜色、确认了，整页颜色不动**。
     *
     * 两者的成因都不是 flow 不共享（那条已经修过七次了），而是 key 漏了 —— 所以这条
     * 单独钉住：加任何新的 palette 输入都必须同时进这个 key，否则本测试红灯。
     */
    @Test
    fun mainActivity_paletteRememberKeyMustCoverDynamicColor() {
        val code = executableCode(source(mainActivityPath))
        assertTrue(
            "MainActivity 必须写成 " +
                "`remember(selectedThemeId, dynamicEnabled, customSeedColor) { themeManager.getCurrentPalette() }`：" +
                "getCurrentPalette 不是 Composable，key 漏了哪一项就等于那一项不生效。",
            Regex(
                """remember\(\s*selectedThemeId\s*,\s*dynamicEnabled\s*,\s*customSeedColor\s*\)\s*\{""" +
                    """\s*themeManager\.getCurrentPalette\(\)\s*\}"""
            ).containsMatchIn(code)
        )
        assertTrue(
            "MainActivity 必须订阅 `themeManager.dynamicEnabled`，否则上面那个 key 恒定不变。",
            code.contains("themeManager.dynamicEnabled")
        )
        assertTrue(
            "MainActivity 必须订阅 `themeManager.customSeedColor`，否则改了自定义色不生效。",
            code.contains("themeManager.customSeedColor")
        )
    }

    /**
     * `UfiPageSwitcherHost` 的模糊开关必须经 `rememberUpdatedState` 拿到，在 draw 期读 `.value`。
     *
     * 与同文件里 `transitionState` 完全同一手法、同一理由：`graphicsLayer {}` 的 lambda 建立在
     * `HorizontalPager` / `AnimatedContent` 的**子组合**里，其跳过与复用不由宿主控制。组合期读
     * 一次的裸 `Boolean` 会被 lambda **按值捕获**，item 不重组时就永远用首次组合的开关值
     * （`LocalUfiBlurEnabled` 还是 `staticCompositionLocalOf`，对子组合的穿透本身也不保证重组）。
     * 包成身份稳定的 `State` 后，`.value` 是 draw/layer 阶段的快照读，开关一变即触发图层失效重跑。
     */
    @Test
    fun pageSwitcher_blurSwitchMustBeReadAtDrawTime() {
        val code = executableCode(source(switcherHostPath))
        assertTrue(
            "UfiPageSwitcherHost 里必须有 `rememberUpdatedState(LocalUfiBlurEnabled.current)`：" +
                "组合期读裸 Boolean 会被图层 lambda 按值捕获，开关改了图层不会重跑。",
            Regex("""rememberUpdatedState\(\s*LocalUfiBlurEnabled\.current\s*\)""")
                .containsMatchIn(code)
        )
        assertFalse(
            "又出现了组合期读裸 Boolean 的 `val blurEnabled: Boolean = LocalUfiBlurEnabled.current` —— " +
                "这个值会被 graphicsLayer 的 lambda 按值闭包捕获，复活「关了开关还在模糊、" +
                "杀进程重进才生效」。",
            Regex("""val\s+blurEnabled\s*:\s*Boolean\s*=\s*LocalUfiBlurEnabled\.current""")
                .containsMatchIn(code)
        )
        val drawTimeReads = Regex("""blurEnabled\s*=\s*blurEnabledState\.value""")
            .findAll(code).count()
        assertTrue(
            "`applyTransitionBlur(blurEnabled = …)` 的三个调用点（Pager 每页 / 远程跳转出场层 / " +
                "AnimatedContent 槽位）都必须传 `blurEnabledState.value`，当前只有 $drawTimeReads 处。",
            drawTimeReads == 3
        )
        assertTrue(
            "`applyTransitionBlur` 在 `!blurEnabled` 分支必须**显式** `renderEffect = null`，" +
                "不得依赖 ReusableGraphicsLayerScope.reset() 这条运行时外部约定。",
            code.replace(Regex("""\s+"""), " ")
                .contains("} else { renderEffect = null } } else { renderEffect = null } }")
        )
    }




    // ── 胶囊与页面不得错位（2026-09-05） ──────────────────────────────────────

    /**
     * 胶囊 `Dialog` 的挂载**不得**被延迟到导航转场之后。
     *
     * 上一轮为了压「返回时底部阴影闪一下」，在 `LaunchedEffect(showBottomBar)` 里插过一条
     * `delay(navTransitionMs + CAPSULE_REMOUNT_SETTLE_MS)` 再 `showCapsule = true`。已回退：
     * 那段等待期里胶囊窗口不存在，而 `mainTabIndex` / Pager 仍可被改动（重定向壳、深链接、
     * 页面内 `LocalUfiMainTabController`、横滑手势），胶囊却是在等待结束后才**首次组合**的 ——
     * 它内部所有 `remember` 初值都取那一刻的组合值，分叉窗口被整整拉长一个转场时长，
     * 真机表现就是「胶囊显示 a、实际是 b，点了也不动」。
     *
     * 正确性优先于那点闪烁：胶囊必须与宿主同一帧出现，两者读同一个 `mainTabIndex`。
     */
    @Test
    fun capsuleDialog_mustMountWithoutDelay() {
        val code = executableCode(source(navGraphPath))
        assertFalse(
            "MainNavGraph 里又出现了 CAPSULE_REMOUNT_SETTLE_MS —— 胶囊挂载被重新延后，" +
                "会复活「胶囊与实际页面错位」。",
            code.contains("CAPSULE_REMOUNT_SETTLE_MS")
        )
        assertFalse(
            "胶囊挂载前又出现了按转场时长的 delay —— 同上，不得延后挂载。",
            Regex("""delay\([^)]*navTransitionMs""").containsMatchIn(code)
        )
        assertTrue(
            "`showCapsule.value = true` 必须在 `showBottomBar` 为真时**立即**执行" +
                "（与宿主同帧出现），不得被任何挂起点隔开。",
            Regex("""if\s*\(\s*showBottomBar\s*\)\s*showCapsule\.value\s*=\s*true""")
                .containsMatchIn(code)
        )
    }

    /**
     * `beyondViewportPageCount` 必须只由「策略 / 保活 / 页数」决定，**不得**随导航转场变化。
     *
     * 上一轮为了压 pop 首帧卡顿加过 `neighborsReady`（转场中降到 0、落定后回 1）。已回退：
     * 这个入参一变，`HorizontalPager` 底层的 LazyLayout 会重建 item provider 并重新测量，
     * 而这一刻程序化切页驱动很可能正在 `animateScrollToPage` 中途 —— 重测量会让
     * `currentPage` / `currentPageOffsetFraction` 停在非整数位，驱动却已按 `SETTLE_EPSILON`
     * 判定「到位」退出，页面卡在两页之间而胶囊早已落到目标格。
     */
    @Test
    fun pagerBackend_beyondViewportMustNotTrackNavTransition() {
        val code = executableCode(source(switcherHostPath))
        assertFalse(
            "PagerBackend 又出现了 neighborsReady —— beyondViewportPageCount 不得在转场途中变化。",
            code.contains("neighborsReady")
        )
        assertFalse(
            "PagerBackend 又读起了 LocalUfiNavTransitionActive —— 该 CompositionLocal 已删除，" +
                "Pager 的预加载页数不该感知外层导航转场。",
            code.contains("LocalUfiNavTransitionActive")
        )
        assertTrue(
            "baseBeyond 必须只 remember 在 `transition / keepPagesAlive / pages.size` 上。",
            Regex("""remember\(\s*transition\s*,\s*keepPagesAlive\s*,\s*pages\.size\s*\)""")
                .containsMatchIn(code)
        )
    }


    // ── 返回时的「阴影闪烁」（2026-09-04） ────────────────────────────────────

    /**
     * 下层的缩放图层必须在**缩放期间**离屏合成一次，且**静止时退回 `Auto`**。
     *
     * - 少了 `Offscreen`：子树里几十张 `ufiCardShadow` 卡片的阴影会随 `scale 0.96→1`
     *   逐帧按新比例重新光栅化，边缘亮度抖动 —— 用户实测能看到的「阴影闪烁」。
     * - 写成无条件 `Offscreen`：宿主页 99% 的时间静止，却常驻一张≈屏幕大小的离屏纹理
     *   （1080×2340 ARGB8888 ≈ 10MB 显存），纯浪费。
     */
    @Test
    fun ufiNavRecedeLayer_mustCompositeOffscreenOnlyWhileScaling() {
        val code = executableCode(source(navigationPath))
        assertTrue(
            "ufiNavRecedeLayer 的缩放图层必须在缩放期间用 CompositingStrategy.Offscreen：" +
                "否则子树里的 ufiCardShadow 阴影会逐帧重光栅化，真机可见阴影闪烁。",
            code.contains("CompositingStrategy.Offscreen")
        )
        assertTrue(
            "静止（scale == 1f）时必须退回 CompositingStrategy.Auto：常开离屏会白占约 10MB 显存。",
            Regex("""scale\s*==\s*1f\s*\)?\s*CompositingStrategy\.Auto""")
                .containsMatchIn(code.replace(Regex("""\s+"""), " "))
        )
    }

    /**
     * `UfiNavFrameGate` 只剩帧闸门这一件事，但那件事不能丢。
     *
     * 它曾经还 provide 一个 `LocalUfiNavTransitionActive`（供 Pager 推迟相邻页预加载），
     * 那条链路已随 `neighborsReady` 一并回退；帧闸门本身仍必须在。
     */
    @Test
    fun ufiNavFrameGate_mustStillRaiseFrameGate() {
        val code = executableCode(source(navigationPath))
        assertTrue(
            "UfiNavFrameGate 必须举起 UiFrameGate（压住 WS 推送引发的整屏重组）。",
            code.contains("UiFrameGate.begin()") && code.contains("UiFrameGate.end()")
        )
        assertFalse(
            "Navigation.kt 又出现了 LocalUfiNavTransitionActive —— 该 CompositionLocal 已删除。",
            code.contains("LocalUfiNavTransitionActive")
        )
    }

    /**
     * `CapsuleInsetHolder.bottomInset` 不得在胶囊测量完成前发布。
     *
     * 首帧 `naturalSize` 为 0，无条件写入会先发布一个矮 ~50dp 的假值，
     * 于是所有读 `LocalCapsuleBottomInset` 的长列表在返回动画途中要多重排一次，
     * 底部卡片上下跳 ⇒ 阴影再闪一次。
     */
    @Test
    fun capsuleInset_mustNotPublishBeforeMeasured() {
        val code = executableCode(source(capsuleHostPath))
        assertTrue(
            "写 CapsuleInsetHolder.bottomInset 前必须判 `naturalSize.value.height > 0`：" +
                "测量前发布的是矮 ~50dp 的假值，会让页面在转场途中多重排一次。",
            Regex("""naturalSize\.value\.height\s*>\s*0""").containsMatchIn(code)
        )
    }

    // ── 二级页退出动画（2026-09-05 P0 / P1） ──────────────────────────────────

    /**
     * ★ P0：`MainNavGraph` **不得**再出现 `visibleEntries`。
     *
     * 旧判据 `visibleEntries.value.lastOrNull()?.id == entry.id` 被当成「本页是不是前景页」，
     * 但 navigation-runtime 2.8.0 `NavController.kt:1192 populateVisibleEntries()` 的排序是
     * **先** `transitionsInProgress` 里 `maxLifecycle < STARTED` 的 entry（含**已经出栈、
     * 正在跑退场动画**的那页）、**再** `backQueue` 里 `>= STARTED` 的。库 KDoc
     * （`NavController.kt:134-151`）原文："CREATED entries are listed first ... can include
     * entries that have been popped off" + "The last entry in the list is the topmost entry"。
     *
     * 所以 `.last()` 的真实语义是「**进场页**」，不是「前景页」，两者只在 push 方向重合：
     * - push：`[host(CREATED), detail(STARTED)]` → `.last()` = detail（进场页，恰好也是前景页）；
     * - pop： `[detail(CREATED, 已出栈), host(STARTED)]` → `.last()` = **host**（进场页，但它是下层）。
     *
     * 于是 pop 期间角色整个判反：正整屏右滑出去的 detail 吃 `scale 1→0.96` + scrim，
     * 宿主则完全不变换、复位动画根本没播 —— 用户报的「退出偏快 / 卡顿跳帧 / 下层表现不对 /
     * 收尾没滑完就消失」都在这里。现在角色由**方向**决定（`UfiNavRecedeRole`：
     * `detailExit` 记 `initialState.id`、`detailPopEnter` 记 `targetState.id`），
     * 不再读 `visibleEntries`，顺带把手势返回「松手瞬间角色互换」也一并修掉。
     */
    @Test
    fun mainNavGraph_mustNotJudgeRoleByVisibleEntries() {
        val code = executableCode(source(navGraphPath))
        assertFalse(
            "MainNavGraph 又出现了 `visibleEntries` —— 它的 `.last()` 语义是「**进场页**」" +
                "而不是「前景页」（populateVisibleEntries 先收已出栈、正在退场的 entry），" +
                "pop 方向会把「谁是下层」判反，返回动画重新变成「detail 缩放 + 宿主不动」。" +
                "角色必须由方向决定，见 Navigation.kt 的 UfiNavRecedeRole。",
            code.contains("visibleEntries")
        )
        assertTrue(
            "MainNavGraph 必须持有 `UfiNavRecedeRole`（按方向登记「谁是下层」的唯一判据）。",
            Regex("""remember\s*\{\s*UfiNavRecedeRole\(\)\s*\}""").containsMatchIn(code)
        )
        val navigation = executableCode(source(navigationPath))
        assertTrue(
            "detailExit 必须把 push 方向的下层（留在后面的旧页 = initialState）记进 role。",
            Regex("""role\.recedingEntryId\s*=\s*initialState\.id""").containsMatchIn(navigation)
        )
        assertTrue(
            "detailPopEnter 必须把 pop 方向的下层（回来的旧页 = targetState）记进 role。",
            Regex("""role\.recedingEntryId\s*=\s*targetState\.id""").containsMatchIn(navigation)
        )
        assertFalse(
            "ufiNavRecedeLayer 的形参又叫回 `isFront` —— 语义已取反为 `isReceding`（本页是否是下层），" +
                "两套命名混用必然再判反一次。",
            navigation.contains("isFront")
        )
    }

    /**
     * ★ P1：`LocalCapsuleBottomInset` 的 provide 处**不得在组合期解引用** `.value`。
     *
     * 它是 `staticCompositionLocalOf` —— **没有细粒度读追踪**，`provides` 的值一变，
     * provider 以下整棵树无条件重组；而它的 provider 在 `MainNavGraph` 里包着整个 `NavHost`。
     * 旧写法 `val capsuleBottomInset = CapsuleInsetHolder.bottomInset.value` 把 MainNavGraph
     * 订阅进了那条 MutableState，时间线正好砸在返回动画开头：
     * 离开 MAIN 写回兜底 88dp → pop 胶囊重挂 → 首帧不发布 → **第 2~3 帧发布真值**
     * → NavHost 整子树重组 + 所有读它的页面重排。88dp 与真值差约 50dp ⇒ 既卡顿也位置跳。
     *
     * 修法：下发「**读值的方式**」而不是值 —— `staticCompositionLocalOf<() -> Dp>` +
     * 一个 `remember` 住的 lambda（实例永不变 ⇒ 永不触发整树重组），
     * 快照读推迟到消费侧的 layout 阶段（`Modifier.ufiCapsuleBottomInset`）。
     */
    @Test
    fun capsuleInset_providerMustNotDereferenceAtComposition() {
        val host = executableCode(source(capsuleHostPath))
        assertTrue(
            "LocalCapsuleBottomInset 必须是 `staticCompositionLocalOf<() -> Dp>`（下发读值的方式）：" +
                "下发 Dp 值时，static local 的值一变就会让 NavHost 整子树无条件重组。",
            Regex("""LocalCapsuleBottomInset\s*=\s*staticCompositionLocalOf<\(\)\s*->\s*Dp>""")
                .containsMatchIn(host)
        )
        assertTrue(
            "必须保留 layout 阶段读 inset 的消费入口 `Modifier.ufiCapsuleBottomInset`：" +
                "消费方若在组合期取值，重组只是从 NavHost 挪到了页面根节点，问题没消失。",
            Regex("""fun\s+Modifier\.ufiCapsuleBottomInset\(""").containsMatchIn(host)
        )

        val navGraph = executableCode(source(navGraphPath))
        assertFalse(
            "MainNavGraph 又在组合期解引用 `CapsuleInsetHolder.bottomInset.value` 并绑到局部 val ——" +
                "这会把整个 NavHost 子树订阅进那条 MutableState，复活「pop 第 2~3 帧整树重组」。",
            Regex("""val\s+\w+\s*=\s*CapsuleInsetHolder\.bottomInset\.value""")
                .containsMatchIn(navGraph)
        )
        assertTrue(
            "provide 给 LocalCapsuleBottomInset 的必须是一个 `remember` 住的 `() -> Dp`：" +
                "实例永不变 ⇒ static local 永不触发整树重组，快照读留给消费方的 layout 阶段。",
            Regex("""remember\s*\{\s*\{\s*CapsuleInsetHolder\.bottomInset\.value\s*\}\s*\}""")
                .containsMatchIn(navGraph)
        )
    }
}
