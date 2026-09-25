package com.ufi_axis.viewmodel.module

import com.ufi_axis.viewmodel.state.DownloadEmptyPhase
import com.ufi_axis.viewmodel.state.downloadEmptyPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 「前台化时的数据新鲜度」判据单测 + 三个 Module 的**源码级回归护栏**（2026-09-05）。
 *
 * ## 被守护的问题
 * 横滑切主界面后"顿一下"。settle 之后 0~500ms 内原本有 3~6 次**整页**重组：
 * `pageForeground` 翻转重启两条副作用，`viewModelScope`（`Dispatchers.Main.immediate` +
 * `CoroutineStart.DEFAULT`）让协程体在主线程**同步内联**开跑，于是当帧就写了两次
 * `isLoading = true`；随后 getTrafficLimit / getWifiSettings+getWifiClients /
 * dashboard-summary / deviceVersion 的回包又各写一次。而横滑落定时屏幕上还有可见运动
 * （胶囊滑块与图标着色归位，+80ms ~ +460ms），每一次整页重组都是可见的 hitch。
 * 点击路径这些写全部落在 t=0 首帧、此刻眼睛没有可追踪的运动，所以感知不到 —— 这正是
 * 「点击不顿、横滑顿」的路径差异。
 *
 * ## 两条不变量
 * 1. **有数据时不写 loading 态**（骨架屏判据都是 `isLoading && 数据 == null`，
 *    有数据时翻转它没有任何 UI 效果，纯粹白付一次整页重组）；
 * 2. **数据新鲜就别重拉**，且新鲜度必须用单调时钟 `SystemClock.elapsedRealtime()`。
 *
 * ## 为什么护栏是源码级断言
 * 这两条的行为验证需要真实 `Retrofit` + 主线程调度 + 可控时钟，成本远高于收益；
 * 而回归形态非常具体（把 `when` 改回无条件 `copy(isLoading = true)`、把
 * `elapsedRealtime()` 换成 `currentTimeMillis()`、给某个前台化调用点补上 `force = true`），
 * 正则就能钉死并附带原因说明。纯函数部分则是真正的单测。
 */
class ForegroundRefreshFreshnessTest {

    // ── 纯函数：新鲜度判据 ──────────────────────────────────────────────────

    @Test
    fun freshness_neverFetched_mustRefreshImmediately() {
        assertEquals(
            "从未成功拉取过（时间戳 0）时必须立刻请求，否则首屏永远没数据",
            0L,
            foregroundRefreshDelayMs(lastSuccessElapsedMs = 0L, nowElapsedMs = 500_000L)
        )
        assertFalse(
            "从未成功拉取过不能算「新鲜」",
            isForegroundDataFresh(lastSuccessElapsedMs = 0L, nowElapsedMs = 500_000L)
        )
    }

    @Test
    fun freshness_justFetched_mustHoldOffFullWindow() {
        assertEquals(
            "刚成功（age = 0）时应等满整个新鲜窗口再发第一次请求",
            FOREGROUND_REFRESH_INTERVAL_MS,
            foregroundRefreshDelayMs(lastSuccessElapsedMs = 100_000L, nowElapsedMs = 100_000L)
        )
    }

    @Test
    fun freshness_partiallyAged_mustReturnRemainingWindow() {
        // age = 3s，窗口 10s ⇒ 还剩 7s。返回「剩余时长」而不是 true/false 是关键：
        // 调用方 delay(它) 之后进入轮询循环，节奏就与「上次成功时刻」对齐，
        // 既跳过 settle 窗口里那次重拉，也不会把刷新间隔拉长成 10s + 10s。
        assertEquals(
            7_000L,
            foregroundRefreshDelayMs(lastSuccessElapsedMs = 100_000L, nowElapsedMs = 103_000L)
        )
        assertTrue(isForegroundDataFresh(lastSuccessElapsedMs = 100_000L, nowElapsedMs = 103_000L))
    }

    @Test
    fun freshness_atWindowEdge_mustBeStale() {
        // 边界取「>= 窗口即过期」：与轮询周期同源时，第 10s 那一刻本来就该发请求。
        assertEquals(
            0L,
            foregroundRefreshDelayMs(lastSuccessElapsedMs = 100_000L, nowElapsedMs = 110_000L)
        )
        assertFalse(isForegroundDataFresh(lastSuccessElapsedMs = 100_000L, nowElapsedMs = 110_000L))
    }

    @Test
    fun freshness_expired_mustRefreshImmediately() {
        assertEquals(
            0L,
            foregroundRefreshDelayMs(lastSuccessElapsedMs = 100_000L, nowElapsedMs = 999_000L)
        )
    }

    @Test
    fun freshness_negativeAge_mustFailOpen() {
        // elapsedRealtime 单调不回退，负年龄只可能来自实现错误（时间戳不是同一个时钟量的）。
        // 必须保守按「已过期」处理 —— 否则一个坏时间戳会把数据永久钉成"新鲜"，页面再也不刷新。
        assertEquals(
            0L,
            foregroundRefreshDelayMs(lastSuccessElapsedMs = 200_000L, nowElapsedMs = 100_000L)
        )
    }

    @Test
    fun freshness_nonPositiveWindow_mustDisableGate() {
        assertEquals(
            "窗口 <= 0 视为「不启用新鲜度」，必须恒立刻请求",
            0L,
            foregroundRefreshDelayMs(
                lastSuccessElapsedMs = 100_000L,
                nowElapsedMs = 100_000L,
                freshWindowMs = 0L
            )
        )
    }

    @Test
    fun freshness_windowMustMatchAutoRefreshInterval() {
        assertEquals(
            "新鲜度窗口必须与首页自动刷新周期同源：窗口更短 ⇒ 前台化必然重拉（等于没做）；" +
                "窗口更长 ⇒ 出现「轮询已经刷过、前台化却认为还不新鲜」的自相矛盾。",
            10_000L,
            FOREGROUND_REFRESH_INTERVAL_MS
        )
    }

    // ── 源码护栏 ────────────────────────────────────────────────────────────

    private val dashboardPath = "src/main/java/com/ufi_axis/viewmodel/viewmodel/module/DashboardModule.kt"
    private val toolsPath = "src/main/java/com/ufi_axis/viewmodel/viewmodel/module/ToolsModule.kt"
    private val networkPath = "src/main/java/com/ufi_axis/viewmodel/viewmodel/module/NetworkModule.kt"
    private val freshnessPath = "src/main/java/com/ufi_axis/viewmodel/viewmodel/module/DataFreshness.kt"
    private val downloadPath = "src/main/java/com/ufi_axis/viewmodel/viewmodel/module/DownloadModule.kt"
    private val appManagerPath = "src/main/java/com/ufi_axis/viewmodel/viewmodel/module/AppManagerModule.kt"
    private val tunnelPath = "src/main/java/com/ufi_axis/viewmodel/viewmodel/module/TunnelModule.kt"
    private val fileManagerPath = "src/main/java/com/ufi_axis/viewmodel/viewmodel/module/FileManagerModule.kt"

    /**
     * `DownloadScreen` 不在本模块内，[source] 会一路上溯到仓库根再按这个相对路径找。
     * 之所以要跨模块断言：进页面首帧 / 2s 轮询 / 顶栏手动刷新这三个 `loadDownloads` 调用点
     * 的 `force` 分工写在**界面侧**，判错任何一个的后果都比模块内部的写法回归严重
     * （见 [downloadCallSites_forceSplitMustHold]）。
     */
    private val downloadScreenPath =
        "app/feature-download/src/main/java/com/ufi_axis/ui/screens/DownloadScreen.kt"

    /**
     * 同样跨模块：空态 item 的**挂载条件**是本轮"图标和描述闪一下"的另一半，
     * 判据写在界面侧（见 [downloadEmptyState_mustStayMountedRegardlessOfLoading]）。
     */
    private val downloadTasksUiPath =
        "app/feature-download/src/main/java/com/ufi_axis/ui/screens/DownloadTasksUi.kt"



    /**
     * 定位模块源码文件。
     *
     * Gradle 单测的工作目录默认是**模块目录**（`app/viewmodel`），但 IDE / 其他 runner 可能
     * 从仓库根启动，因此从当前目录逐级上溯，并同时尝试 `app/viewmodel/` 前缀。
     */
    private fun source(relative: String): String {
        var dir: File? = File(".").absoluteFile.normalize()
        while (dir != null) {
            for (candidate in listOf(File(dir, relative), File(dir, "app/viewmodel/$relative"))) {
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
     * 这几个文件把「为什么不能无条件写 isLoading」「为什么不能用 currentTimeMillis」
     * 大段写进了注释里，直接全文搜索必然误报。
     */
    private fun executableCode(text: String): String =
        text.replace(Regex("""/\*[\s\S]*?\*/"""), "")
            .lines()
            .joinToString("\n") { it.substringBefore("//") }

    /**
     * dashboard/summary 的刷新**不得**无条件写 `isLoading = true`。
     *
     * `DashboardState` 是那三个大对象里最贵的一个：`DashboardScreen` 与 `MonitorScreen`
     * （2000+ 行）都 `collectAsState` 了它，且都没有 `key()` / `derivedStateOf` 兜着 ——
     * 实例一变就是「整页 + 3 张大卡 + 整个监控页」一起重组。
     * 而它唯一的可见用途是骨架屏（判据 `state.isLoading && state.deviceInfo == null`），
     * 有数据时翻转它**没有任何 UI 效果**。
     */
    @Test
    fun dashboardRefresh_mustNotWriteLoadingWhenDataExists() {
        val code = executableCode(source(dashboardPath))

        assertFalse(
            "又出现了 `_dashboardState.update { it.copy(isLoading = true …) }` 的无条件写法 —— " +
                "回归红线：这是**主线程同步**的整页写（viewModelScope 是 Main.immediate + " +
                "CoroutineStart.DEFAULT，主线程调用时协程体内联开跑），横滑落定那一帧就会付一次" +
                "「DashboardScreen 整页 + 3 卡 + MonitorScreen」重组，正是用户说的\"顿一下\"。",
            Regex("""_dashboardState\.update\s*\{\s*it\.copy\(\s*isLoading\s*=\s*true""")
                .containsMatchIn(code)
        )
        assertTrue(
            "找不到「没数据才写 loading」的分支（`current.deviceInfo == null -> current.copy(isLoading = true`）—— " +
                "首次加载的骨架屏反馈必须保留（DashboardScreen 的判据是 isLoading && deviceInfo == null），" +
                "所以这条分支不能删，只能收窄。",
            Regex("""current\.deviceInfo\s*==\s*null\s*->\s*current\.copy\(\s*isLoading\s*=\s*true""")
                .containsMatchIn(code)
        )
    }

    /**
     * 三个数据源的新鲜度闸门必须存在，且判据一律走**单调时钟**。
     */
    @Test
    fun foregroundRefresh_mustBeGatedByMonotonicFreshness() {
        val dashboard = executableCode(source(dashboardPath))
        val tools = executableCode(source(toolsPath))
        val network = executableCode(source(networkPath))

        assertTrue(
            "DashboardModule.startAutoRefresh 里找不到 `foregroundRefreshDelayMs(` —— " +
                "前台化时的「数据新鲜就只补定时器、不发这次请求」被删掉了，" +
                "横滑回到首页又会在 settle 窗口里落一次 REST 回包整页写。",
            dashboard.contains("foregroundRefreshDelayMs(")
        )
        assertTrue(
            "ToolsModule.loadTrafficLimit 缺少新鲜度闸门（isForegroundDataFresh(trafficLimitSuccessElapsed …)）",
            Regex("""isForegroundDataFresh\(\s*trafficLimitSuccessElapsed""").containsMatchIn(tools)
        )
        assertTrue(
            "NetworkModule.refreshWifi 缺少新鲜度闸门（isForegroundDataFresh(wifiSuccessElapsed …)）",
            Regex("""isForegroundDataFresh\(\s*wifiSuccessElapsed""").containsMatchIn(network)
        )

        // 单调时钟：三个基准时间戳都必须由 elapsedRealtime 写入，且不得出现 currentTimeMillis 版本。
        listOf(
            Triple("dashboardSuccessElapsed", dashboard, "DashboardModule"),
            Triple("trafficLimitSuccessElapsed", tools, "ToolsModule"),
            Triple("wifiSuccessElapsed", network, "NetworkModule"),
        ).forEach { (field, code, where) ->
            assertTrue(
                "$where 的 `$field` 没有用 `SystemClock.elapsedRealtime()` 写入 —— " +
                    "新鲜度是一段**时长**，必须用单调时钟。`System.currentTimeMillis()` 可被用户 / NTP " +
                    "随时改：往前跳会把陈旧数据判成新鲜，往后跳会让新鲜数据被判成过期甚至算出负年龄。",
                Regex("""$field\s*=\s*SystemClock\.elapsedRealtime\(\)""").containsMatchIn(code)
            )
            assertFalse(
                "$where 的 `$field` 又改回 `System.currentTimeMillis()` 了 —— 单调时钟红线回归。",
                Regex("""$field\s*=\s*System\.currentTimeMillis\(\)""").containsMatchIn(code)
            )
        }

        assertFalse(
            "DataFreshness.kt 的可执行代码里出现了 `currentTimeMillis` —— 判据必须与时钟无关" +
                "（只做减法比较），时钟由调用方以 elapsedRealtime 提供。",
            executableCode(source(freshnessPath)).contains("currentTimeMillis")
        )
    }

    /**
     * 「值刚变了」的路径必须显式 `force = true`，否则新鲜度闸门会让 UI 显示旧值 /
     * 让在线设备页的 5s 轮询被节流成 10s。
     */
    @Test
    fun writeBackAndPollingPaths_mustBypassFreshnessGate() {
        val tools = executableCode(source(toolsPath))
        val network = executableCode(source(networkPath))

        assertTrue(
            "`loadTrafficLimit(force = false)` 的默认闸门必须存在（签名带 force 形参）",
            Regex("""fun loadTrafficLimit\(\s*force:\s*Boolean\s*=\s*false\s*\)""").containsMatchIn(tools)
        )
        val toolsForced = Regex("""loadTrafficLimit\(force\s*=\s*true\)""").findAll(tools).count()
        assertTrue(
            "`loadTrafficLimit(force = true)` 少于 3 处 —— 写后回读（saveDataLimit / calibrateFlow）" +
                "与 core 推送（smartRefresh: device:traffic-limit）三条路径的前提都是「值刚变了」，" +
                "吃缓存会让 UI 停在旧值上。当前 $toolsForced 处。",
            toolsForced >= 3
        )

        assertTrue(
            "`refreshWifi(force = false)` 的默认闸门必须存在（签名带 force 形参）",
            Regex("""fun refreshWifi\(\s*force:\s*Boolean\s*=\s*false\s*\)""").containsMatchIn(network)
        )
        val networkForced = Regex("""refreshWifi\(force\s*=\s*true\)""").findAll(network).count()
        assertTrue(
            "NetworkModule 内 `refreshWifi(force = true)` 少于 2 处 —— 写后回读（setWifiEnabled / ACL 写入）" +
                "必须绕过闸门，否则开关或名单刚改完却回读到旧状态。当前 $networkForced 处。",
            networkForced >= 2
        )
    }

    /**
     * 流量限额的 loading 态也只在「没配置」时写（骨架屏判据 `isLoading && cfg == null`）。
     */
    @Test
    fun trafficLimit_mustNotWriteLoadingWhenConfigExists() {
        val code = executableCode(source(toolsPath))

        assertFalse(
            "又出现了 `_trafficManagementState.value = _trafficManagementState.value.copy(isLoading = true …)` " +
                "的无条件写法 —— 首页横滑回来时它是一次白付的整页重组" +
                "（TrafficManagementScreen 的骨架屏判据是 `state.isLoading && cfg == null`）。",
            Regex(
                """_trafficManagementState\.value\s*=\s*_trafficManagementState\.value\.copy\(\s*isLoading\s*=\s*true"""
            ).containsMatchIn(code)
        )
        assertTrue(
            "找不到「没配置才写 loading」的分支（`if (s.limitConfig == null)`）—— " +
                "首次加载的骨架屏反馈必须保留。",
            Regex("""if\s*\(\s*s\.limitConfig\s*==\s*null\s*\)""").containsMatchIn(code)
        )
    }

    // ── 2026-09-05 第二批：下载 / 应用管理 / 内网穿透 ────────────────────────

    /**
     * 下载列表的 loading 态**只允许真首屏**（本进程从未成功拉到过）写。
     *
     * ## 被守护的 bug：空态"图标和描述闪一下"（2026-09-05 第二轮）
     * 用户反馈"进下载管理时中间那块图标和描述会闪一下，添加任务后就完全正常"。
     * 机械链路：`DownloadTasksUi` 的空态 item 当时挂载条件带 `&& !state.isLoading`，
     * 而本模块的 loading 判据是 `tasks.isEmpty()` ⇒ **每次**进入空列表页都写一次
     * `isLoading = true`，空态 item 被摘掉、回包后再挂回来 = 闪一下；有任务时不写 loading，
     * 所以"添加任务后就正常"。两侧都改了：判据换成"从未成功加载过"（本测试），
     * 空态 item 改成恒定挂载（[downloadEmptyState_mustStayMountedRegardlessOfLoading]）。
     *
     * 判据必须是 `downloadsSuccessElapsed == 0L`，不能退回 `tasks.isEmpty()`：
     * "一个下载任务都没有"是本页合法的稳定状态，用它当"首屏"会让每次进入空列表页都翻一次
     * loading —— 与新鲜度闸门那一侧（同一理由，见 `loadDownloads` 的 KDoc）也就前后不一致了。
     */
    @Test
    fun downloadRefresh_mustWriteLoadingOnlyOnFirstScreen() {
        val code = executableCode(source(downloadPath))

        assertFalse(
            "又出现了 `if (!silent) _state.value = _state.value.copy(isLoading = true)` 的无条件写法 —— " +
                "回归红线：进页面首帧就是走这一句，它是用户说的\"自动播刷新动画\"里唯一还属于本模块的部分。",
            Regex("""if\s*\(!silent\)\s*_state\.value\s*=\s*_state\.value\.copy\(\s*isLoading\s*=\s*true""")
                .containsMatchIn(code)
        )
        assertFalse(
            "loading 判据又改回 `tasks.isEmpty()` 了 —— 这正是**空态闪烁**的成因：" +
                "空列表是本页合法的稳定状态，用它当\"首屏\"判据 ⇒ 每次进入空列表页都写一次 " +
                "isLoading = true ⇒ 空态那块图标和描述闪一下（有任务时不写，所以\"添加任务后就正常\"）。" +
                "判据只能是\"本进程从未成功加载过\"（downloadsSuccessElapsed == 0L）。",
            Regex("""!silent\s*&&\s*_state\.value\.tasks\.isEmpty\(\)""").containsMatchIn(code)
        )
        assertTrue(
            "找不到「非静默且本进程从未成功加载过才写 loading」的判据" +
                "（`if (!silent && downloadsSuccessElapsed == 0L)`）—— " +
                "首屏的视觉反馈要靠它（空态文案在 DownloadEmptyPhase.LOADING 档说\"正在读取下载任务\"），" +
                "所以这条分支不能删，只能保持这个口径。",
            Regex("""if\s*\(!silent\s*&&\s*downloadsSuccessElapsed\s*==\s*0L\s*\)""")
                .containsMatchIn(code)
        )
        assertTrue(
            "成功分支没有把 `hasLoadedOnce = true` 一并写进那次 copy —— " +
                "空态文案的分档全靠它（见 DownloadState.hasLoadedOnce / downloadEmptyPhase）；" +
                "它必须随现有的成功 copy 一起写，单独再发一次状态就是白付一次整页重组。",
            Regex("""hasLoadedOnce\s*=\s*true""").containsMatchIn(code)
        )
    }


    /**
     * 下载列表的新鲜度闸门必须存在，且判据走单调时钟。
     */
    @Test
    fun downloadRefresh_mustBeGatedByMonotonicFreshness() {
        val code = executableCode(source(downloadPath))

        assertTrue(
            "`loadDownloads` 的签名必须同时带 `silent` 与 `force` 两个形参 —— " +
                "silent 管\"要不要写 loading 态\"，force 管\"要不要绕过新鲜度闸门\"，两者不是一回事：" +
                "2s 轮询是 silent = true **且** force = true。",
            Regex("""fun loadDownloads\(\s*silent:\s*Boolean\s*=\s*false\s*,\s*force:\s*Boolean\s*=\s*false\s*\)""")
                .containsMatchIn(code)
        )
        assertTrue(
            "DownloadModule 缺少新鲜度闸门（isForegroundDataFresh(downloadsSuccessElapsed …)）",
            Regex("""isForegroundDataFresh\(\s*downloadsSuccessElapsed""").containsMatchIn(code)
        )
        assertTrue(
            "`downloadsSuccessElapsed` 没有用 `SystemClock.elapsedRealtime()` 写入 —— " +
                "新鲜度是一段**时长**，必须用单调时钟。",
            Regex("""downloadsSuccessElapsed\s*=\s*SystemClock\.elapsedRealtime\(\)""").containsMatchIn(code)
        )
        assertFalse(
            "`downloadsSuccessElapsed` 改成 `System.currentTimeMillis()` 了 —— 单调时钟红线回归。",
            Regex("""downloadsSuccessElapsed\s*=\s*System\.currentTimeMillis\(\)""").containsMatchIn(code)
        )
        assertFalse(
            "新鲜度闸门被加上了 `tasks.isNotEmpty()` 这类\"必须已有数据\"的前置条件 —— " +
                "\"一个下载任务都没有\"是本页完全合法的稳定状态，加了它空列表就永远吃不到闸门、" +
                "每次进页面都白拉一次。\"从未成功拉过\"已由 `downloadsSuccessElapsed == 0L` 判成不新鲜。",
            Regex("""tasks\.isNotEmpty\(\)\s*&&\s*\r?\n?\s*isForegroundDataFresh""").containsMatchIn(code)
        )

        val forced = Regex("""loadDownloads\(force\s*=\s*true\)""").findAll(code).count()
        assertTrue(
            "DownloadModule 内 `loadDownloads(force = true)` 少于 11 处 —— 创建/强制创建/暂停/恢复/" +
                "删除/重试/重命名/清空已完成/改配置/Tracker 刷新/Tracker 保存，这 11 条**写后回读**的前提" +
                "都是「值刚被本 App 改过」，吃缓存会让 UI 停在旧状态上（比如点了暂停却还显示下载中）。" +
                "当前 $forced 处。",
            forced >= 11
        )
    }

    /**
     * 应用列表：`isLoading` 只在「本进程还没成功读到过这个 filter 的列表」时写。
     *
     * `AppScreen` 的 `if (state.isLoading) UfiSkeletonGroup(...)` 会把整块内容区换成骨架，
     * 与列表 / `UfiEmptyState("未找到应用")` 是互斥子树 —— 翻一次就是一次硬子树替换。
     * 两条都要保住：首次加载（几百个包要好几秒）必须有骨架；切页签时也必须遮住上一个
     * filter 的列表，否则会出现"点了系统应用却继续列着用户应用"。
     * 判据 `loadedFilter != filter` 一条就同时覆盖这两种。
     *
     * 判据**不得**退回 `apps.isEmpty()`：那样"该 filter 下确实没有应用"（设备上没有第三方
     * 应用、或某个筛选结果为空）会让每次进页面 / 手动刷新 / 写后回读都重新写 loading ⇒
     * 空态被骨架顶掉再挂回来 = **空态闪一下**，与下载页那个 bug 同形
     * （见 [downloadRefresh_mustWriteLoadingOnlyOnFirstScreen]）。
     */
    @Test
    fun appList_mustWriteLoadingOnlyWhenFilterNotLoadedYet() {
        val code = executableCode(source(appManagerPath))

        assertFalse(
            "又出现了 `copy(isLoading = true, filter = filter …)` 的无条件写法 —— " +
                "回归红线：同一 filter 下的手动刷新 / 写后回读会把整列表换成骨架再换回来。",
            Regex("""copy\(\s*isLoading\s*=\s*true\s*,\s*filter\s*=\s*filter""").containsMatchIn(code)
        )
        assertFalse(
            "判据又用上了 `apps.isEmpty()` —— 这会让\"该筛选下确实一个应用都没有\"的合法空结果" +
                "每次都被骨架顶一下（空态闪烁），和下载页那个 bug 是同一个形态。" +
                "只能按\"这个 filter 有没有成功加载过\"判断。",
            Regex("""apps\.isEmpty\(\)""").containsMatchIn(code)
        )
        assertTrue(
            "找不到「这个 filter 还没成功加载过才写 loading」的判据（`loadedFilter != filter`）。",
            Regex("""loadedFilter\s*!=\s*filter""").containsMatchIn(code)
        )
        assertTrue(
            "`loadedFilter` 不是在**成功之后**才写 —— 失败也记的话下一次进来会以为已经有列表，" +
                "空着的内容区连骨架都不给。",
            Regex("""loadedFilter\s*=\s*filter""").containsMatchIn(code)
        )
    }


    /**
     * 空态 item 必须**恒定挂载**，不得再被任何加载态摘掉。
     *
     * 这是"进下载管理时中间那块图标和描述闪一下"的另一半（模块侧见
     * [downloadRefresh_mustWriteLoadingOnlyOnFirstScreen]）。只修模块侧不够：
     * `&& !state.isLoading` 这个条件本身就是隐患 —— 任何将来会写 loading 的路径
     * （手动刷新、写后回读、重试）都会让空态挂/卸一次，也就再闪一次。
     * 与 2026-09-04 已生效的决定同一口径：本页不做互斥子树硬切换，
     * "没数据"表达为列表里有一条空态 item，而不是换一棵树/摘一条 item。
     *
     * 首屏未加载完改由**文案分档**表达（[downloadEmptyPhase]），图标与版式原地不动。
     */
    @Test
    fun downloadEmptyState_mustStayMountedRegardlessOfLoading() {
        val code = executableCode(source(downloadTasksUiPath))

        assertFalse(
            "空态 item 的挂载条件里又出现了 `!state.isLoading` —— 回归红线：" +
                "加载期间空态被摘掉、加载结束再挂回来，就是用户看到的\"图标和描述闪一下\"" +
                "（有任务时空态本来就不挂，所以只有空列表暴露它，添加任务后现象消失）。" +
                "加载中要表达什么请改文案（downloadEmptyPhase），不要动挂载条件。",
            Regex("""filteredTasks\.isEmpty\(\)\s*&&\s*!\s*state\.isLoading""").containsMatchIn(code)
        )
        assertTrue(
            "找不到 `if (filteredTasks.isEmpty()) {` 这条**只看有没有数据**的挂载条件 —— " +
                "空态 item 必须恒定挂载（容器与 item 都不做互斥子树切换）。",
            Regex("""if\s*\(filteredTasks\.isEmpty\(\)\)\s*\{""").containsMatchIn(code)
        )
        assertTrue(
            "空态文案没有走 `downloadEmptyPhase(hasLoadedOnce = state.hasLoadedOnce …)` —— " +
                "首屏反馈只允许通过文案分档表达；直接读 isLoading 决定\"挂不挂\"就是本 bug 的回归。",
            Regex("""downloadEmptyPhase\(\s*hasLoadedOnce\s*=\s*state\.hasLoadedOnce""")
                .containsMatchIn(code)
        )
    }

    // ── 纯函数：空态文案分档 ────────────────────────────────────────────────

    @Test
    fun emptyPhase_afterFirstSuccess_mustIgnoreLoadingForever() {
        // 本条是防闪烁的核心不变量：成功加载过之后，无论有没有请求在飞、有没有错误，
        // 空态都恒定是 EMPTY ⇒ 手动刷新 / 写后回读 / 重试都改不动它的外观。
        assertEquals(
            DownloadEmptyPhase.EMPTY,
            downloadEmptyPhase(hasLoadedOnce = true, isLoading = false, hasError = false)
        )
        assertEquals(
            "已成功加载过之后 isLoading 不得再影响空态文案 —— 否则手动刷新会让空态闪一下",
            DownloadEmptyPhase.EMPTY,
            downloadEmptyPhase(hasLoadedOnce = true, isLoading = true, hasError = false)
        )
        assertEquals(
            DownloadEmptyPhase.EMPTY,
            downloadEmptyPhase(hasLoadedOnce = true, isLoading = true, hasError = true)
        )
    }

    @Test
    fun emptyPhase_firstScreen_mustSayLoading() {
        assertEquals(
            "首屏请求在飞时必须给\"正在读取\"的文案，否则用户看到的是\"还没有下载任务\"，" +
                "数据到了又变成列表 —— 那是一次说错话的闪动",
            DownloadEmptyPhase.LOADING,
            downloadEmptyPhase(hasLoadedOnce = false, isLoading = true, hasError = false)
        )
        assertEquals(
            "进页面首帧（LaunchedEffect 还没跑到 loadDownloads，isLoading 仍是 false）" +
                "必须已经是 LOADING 档，否则首帧先说\"还没有下载任务\"、下一帧改口",
            DownloadEmptyPhase.LOADING,
            downloadEmptyPhase(hasLoadedOnce = false, isLoading = false, hasError = false)
        )
    }

    @Test
    fun emptyPhase_firstScreenFailed_mustNotFakeLoading() {
        assertEquals(
            "首屏失败且从未成功过时不能继续说\"正在读取\"（假加载），要指向顶部错误横幅",
            DownloadEmptyPhase.LOAD_FAILED,
            downloadEmptyPhase(hasLoadedOnce = false, isLoading = false, hasError = true)
        )
        assertEquals(
            "失败后重试（isLoading = true）期间应回到\"正在读取\"",
            DownloadEmptyPhase.LOADING,
            downloadEmptyPhase(hasLoadedOnce = false, isLoading = true, hasError = true)
        )
    }

    /**
     * 隧道 `/status`：`isLoading` 只在本进程还没成功读到过状态时写。
     *
     * `loadStatus` 是 `TunnelScreen` / `FrpChannelScreen` / `CfTunnelScreen` 的 **5s 轮询**入口，
     * `TunnelState` 被 6 个隧道页面 `collectAsState`。原实现每轮要付 3 次整页重组
     * （true → 数据 → false），只有中间那次带来真实变化。
     *
     * 判据刻意用 `statusSuccessElapsed == 0L` 而不是某个 state 字段：`frpVersion` /
     * `cfInstances` 在"两个组件都没装"这种正常情况下会长期全空，拿它们当"没数据"
     * 会让每轮轮询又开始写 loading。
     *
     * 2026-09-05 顺带核查：6 个隧道页面**一处都没有读** `TunnelState.isLoading`，
     * 空态（`FrpDetailScreen` / `CfDetailScreen` 的"暂无隧道"）只看 `items.isEmpty()`，
     * 所以不存在下载页那种"空态被 isLoading 摘掉"的结构，无需改动。
     */
    @Test
    fun tunnelStatus_mustNotWriteLoadingWhenAlreadyLoaded() {
        val code = executableCode(source(tunnelPath))

        assertTrue(
            "找不到「本进程还没成功读到过状态才写 loading」的判据（`if (statusSuccessElapsed == 0L)`）—— " +
                "5s 轮询每轮白付两次整页重组的回归。",
            Regex("""if\s*\(\s*statusSuccessElapsed\s*==\s*0L\s*\)""").containsMatchIn(code)
        )
        assertTrue(
            "`statusSuccessElapsed` 没有用 `SystemClock.elapsedRealtime()` 写入。",
            Regex("""statusSuccessElapsed\s*=\s*SystemClock\.elapsedRealtime\(\)""").containsMatchIn(code)
        )
        assertFalse(
            "`loadStatus` 里给 `/status` 加了新鲜度闸门 —— **不能加**：这是实时运行状态，" +
                "5s 轮询短于 10s 窗口，加了就会把隧道的启动/断开/重连压成 10s 才可见。",
            Regex("""isForegroundDataFresh\(\s*statusSuccessElapsed""").containsMatchIn(code)
        )
    }

    /**
     * 隧道**看护设置**（用户设定项，与 `getTrafficLimit` 同类）必须吃缓存。
     */
    @Test
    fun tunnelSettings_mustBeGatedByMonotonicFreshness() {
        val code = executableCode(source(tunnelPath))

        assertTrue(
            "`loadTunnelSettings(force = false)` 的默认闸门必须存在（签名带 force 形参）",
            Regex("""fun loadTunnelSettings\(\s*force:\s*Boolean\s*=\s*false\s*\)""").containsMatchIn(code)
        )
        assertTrue(
            "TunnelModule.loadTunnelSettings 缺少新鲜度闸门" +
                "（isForegroundDataFresh(tunnelSettingsSuccessElapsed …)）—— " +
                "TunnelSettingsScreen 每次进页面都调它，而这是一份用户设定项，不会自己变。",
            Regex("""isForegroundDataFresh\(\s*tunnelSettingsSuccessElapsed""").containsMatchIn(code)
        )
        assertTrue(
            "`tunnelSettingsSuccessElapsed` 没有用 `SystemClock.elapsedRealtime()` 写入。",
            Regex("""tunnelSettingsSuccessElapsed\s*=\s*SystemClock\.elapsedRealtime\(\)""")
                .containsMatchIn(code)
        )
    }

    /**
     * 文件管理的 30s 目录列表 TTL 缓存也必须用单调时钟。
     *
     * 只钉 `CachedListing(...)` 这一处：本模块另有几处 `System.currentTimeMillis()` 是
     * 临时文件名与「手机端下载历史」的展示用 epoch 时间戳，那些**应该**用墙上时钟。
     */
    // ── 2026-09-22：首屏预加载 + 切页不等待 ──────────────────────────────────

    private val mainViewModelPath = "src/main/java/com/ufi_axis/viewmodel/viewmodel/MainViewModel.kt"
    private val preloadPath = "src/main/java/com/ufi_axis/viewmodel/viewmodel/PreloadCoordinator.kt"

    /** 跨模块：启动页与掉线弹窗的可见反馈都写在 Activity 侧。 */
    private val mainActivityPath = "app/src/main/java/com/ufi_axis/MainActivity.kt"


    @Test
    fun networkAllFreshWindow_mustBeLongerThanPollingWindow() {
        assertTrue(
            "网络页的新鲜度窗口必须明显长于首页轮询窗口：这一页**没有轮询**，数据是配置类字段" +
                "（网络模式 / 频段锁定 / LAN 段 / 本机号码），用 10s 窗口等于每次切过去都重发 6 个请求。",
            NETWORK_ALL_FRESH_MS > FOREGROUND_REFRESH_INTERVAL_MS
        )
        assertEquals(
            "窗口取 30s 是对着 core 端最短的那档响应缓存 TTL（CELL_INFO 20s）定的；" +
                "再短就只是在打一个必定命中 core 缓存的请求。",
            30_000L,
            NETWORK_ALL_FRESH_MS
        )
        // 窗口参数确实被 foregroundRefreshDelayMs 接受并生效（判据与默认窗口同一份实现）
        assertEquals(
            25_000L,
            foregroundRefreshDelayMs(
                lastSuccessElapsedMs = 100_000L,
                nowElapsedMs = 105_000L,
                freshWindowMs = NETWORK_ALL_FRESH_MS
            )
        )
        assertFalse(
            "满窗即过期",
            isForegroundDataFresh(
                lastSuccessElapsedMs = 100_000L,
                nowElapsedMs = 100_000L + NETWORK_ALL_FRESH_MS,
                freshWindowMs = NETWORK_ALL_FRESH_MS
            )
        )
    }

    /**
     * 网络页那 6 个请求的扇出必须留在 module 里、且带新鲜度闸门。
     *
     * 回归形态很具体：把扇出搬回 `NetworkScreen` 的私有函数。那样判据（新鲜度戳）就有两份 ——
     * 预加载协调器一份、页面一份 —— 而戳存在 composable 的 `remember` 里换页往复就丢了，
     * 等于"切页不等待"这个功能被静默关掉，没有任何编译错误提示。
     */
    @Test
    fun networkAll_fanOutMustLiveInModuleWithFreshnessGate() {
        val code = executableCode(source(networkPath))

        assertTrue(
            "`NetworkModule.loadNetworkAll(force, silent)` 不见了 —— 网络页那 6 个请求的扇出" +
                "必须在 module 里（判据与戳只能有一份，且要跨组合存活）。",
            Regex("""fun loadNetworkAll\(\s*force:\s*Boolean\s*=\s*false\s*,\s*silent:\s*Boolean\s*=\s*false\s*\)""")
                .containsMatchIn(code)
        )
        assertTrue(
            "`loadNetworkAll` 缺少新鲜度闸门（isForegroundDataFresh(networkAllSuccessElapsed …)）—— " +
                "缺了它每次 pageForeground 翻 true 都要重发 6 个请求，预加载就白做了。",
            Regex("""isForegroundDataFresh\(\s*\r?\n?\s*networkAllSuccessElapsed""").containsMatchIn(code)
        )
        assertTrue(
            "闸门没有用 NETWORK_ALL_FRESH_MS 这个窗口 —— 默认 10s 窗口对这一页太短。",
            code.contains("NETWORK_ALL_FRESH_MS")
        )
        assertTrue(
            "`networkAllSuccessElapsed` 没有用 `SystemClock.elapsedRealtime()` 写入 —— 单调时钟红线。",
            Regex("""networkAllSuccessElapsed\s*=\s*SystemClock\.elapsedRealtime\(\)""").containsMatchIn(code)
        )
        assertFalse(
            "`networkAllSuccessElapsed` 改成 `System.currentTimeMillis()` 了 —— 单调时钟红线回归。",
            Regex("""networkAllSuccessElapsed\s*=\s*System\.currentTimeMillis\(\)""").containsMatchIn(code)
        )
        assertTrue(
            "找不到「全部成功才打戳」的判据（`if (failures == null)`）—— " +
                "部分失败时字段是缺的，打了戳会让下一次前台化跳过重拉、把缺字段固化成\"新鲜\"。",
            Regex("""if\s*\(failures\s*==\s*null\)""").containsMatchIn(code)
        )
        assertTrue(
            "换设备时必须能丢掉新鲜度基准（`fun resetFreshness()`）—— " +
                "只清 Room 不清戳，新设备的页面会因为旧设备的戳还在窗口内而跳过请求。",
            Regex("""fun resetFreshness\(\)""").containsMatchIn(code)
        )
    }

    /**
     * `refreshNetwork` 也要守「有数据时不写 loading 态」这条不变量。
     */
    @Test
    fun refreshNetwork_mustNotWriteLoadingWhenDataExists() {
        val code = executableCode(source(networkPath))

        assertFalse(
            "又出现了 `_networkState.value = _networkState.value.copy(isLoading = true, errorMessage = null)` " +
                "的无条件写法 —— 网络页一处都没读 isLoading，有数据时翻转它纯粹白付一次整页重组。",
            Regex("""_networkState\.value\s*=\s*_networkState\.value\.copy\(\s*isLoading\s*=\s*true""")
                .containsMatchIn(code)
        )
        assertTrue(
            "找不到「没数据才写 loading」的分支（`current.signalInfo == null -> current.copy(isLoading = true`）。",
            Regex("""current\.signalInfo\s*==\s*null\s*->\s*current\.copy\(\s*isLoading\s*=\s*true""")
                .containsMatchIn(code)
        )
    }

    /**
     * 服务状态读取的重复调用必须被闸门挡住，写后回读必须绕过它。
     */
    @Test
    fun serviceStatus_mustBeGatedButWriteBackMustForce() {
        val code = executableCode(source(networkPath))

        assertTrue(
            "`loadServiceStatus(force, silent)` 的签名不对 —— 冷启动时它至少被 Dashboard 与 Settings " +
                "各调一次，加上预加载就是三次，必须有闸门。",
            Regex("""fun loadServiceStatus\(\s*force:\s*Boolean\s*=\s*false\s*,\s*silent:\s*Boolean\s*=\s*false\s*\)""")
                .containsMatchIn(code)
        )
        assertTrue(
            "缺少新鲜度闸门（isForegroundDataFresh(serviceStatusSuccessElapsed …)）",
            Regex("""isForegroundDataFresh\(\s*serviceStatusSuccessElapsed""").containsMatchIn(code)
        )
        val forced = Regex("""loadServiceStatus\(force\s*=\s*true\)""").findAll(code).count()
        assertTrue(
            "NetworkModule 内 `loadServiceStatus(force = true)` 少于 2 处 —— " +
                "setBackgroundService / setServiceAutoStart 失败后的回滚回读前提是「值刚被改过」，" +
                "吃缓存会让徽标停在旧状态上。当前 $forced 处。",
            forced >= 2
        )
    }

    /**
     * 预加载与页面自身的刷新**不得互相取消**。
     *
     * `refreshDashboard()` 无条件 `refreshJob?.cancel()` 是"手动刷新"该有的语义；
     * 但仪表盘 Tab 与监控 Tab 都保活、都会在前台化时进到刷新效应里，用取消版就是
     * 互相掐掉对方的聚合请求，来回浪费。
     */
    @Test
    fun dashboardPreloadPath_mustNotCancelInFlightRefresh() {
        val code = executableCode(source(dashboardPath))

        assertTrue(
            "`fun refreshDashboardIfStale()` 不见了 —— 预加载与前台化都必须走这个不取消的入口。",
            Regex("""fun refreshDashboardIfStale\(\)""").containsMatchIn(code)
        )
        assertTrue(
            "`refreshDashboardIfStale` 里找不到「已经有人在拉就让它拉完」的判据" +
                "（`refreshJob?.isActive == true) return`）。",
            Regex("""refreshJob\?\.isActive\s*==\s*true\s*\)\s*return""").containsMatchIn(code)
        )
        assertTrue(
            "`refreshDashboardIfStale` 没有新鲜度闸门（isForegroundDataFresh(dashboardSuccessElapsed …)）",
            Regex("""isForegroundDataFresh\(\s*dashboardSuccessElapsed""").containsMatchIn(code)
        )
        assertTrue(
            "换设备时必须能丢掉仪表盘的新鲜度基准（`fun resetFreshness()`）。",
            Regex("""fun resetFreshness\(\)""").containsMatchIn(code)
        )
    }

    /**
     * 首屏预加载的三条硬约束（分批 / 静默 / 只在确证在线时启动）。
     *
     * 第三条是最容易被删又后果最难看的：`HealthModule` 的掉线判据是 3 秒内 2 次失败确证，
     * 而每个传输层失败都会驱动一次探活。设备不可达时丢出十来个必定失败的请求，
     * **正好自己把"掉线"坐实** —— 开机就弹「无法连接后端服务」。
     */
    @Test
    fun preload_mustBeBatchedSilentAndOnlineGated() {
        val preload = executableCode(source(preloadPath))
        val vm = executableCode(source(mainViewModelPath))

        assertTrue(
            "预加载没有分批（找不到 `delay(BATCH_GAP_MS)`）—— OkHttp 默认 maxRequestsPerHost = 5，" +
                "一把梭十几个请求只会排队，还会跟 /health 与首屏 summary 抢槽位。",
            preload.contains("delay(BATCH_GAP_MS)")
        )
        assertTrue(
            "预加载没走静默变体（找不到 `silent = true`）—— 预加载发生在启动页背后，" +
                "失败写进 errorMessage 会经 rawGlobalError 冒成全局 Toast，" +
                "表现为\"刚开 app 就莫名弹一句加载失败\"。",
            preload.contains("silent = true")
        )
        assertTrue(
            "`step()` 里没有把 CancellationException 原样上抛 —— reset() 取消 job 时" +
                "会被吃掉，协程继续跑完剩余批次。",
            Regex("""catch\s*\(e:\s*CancellationException\)\s*\{\s*\r?\n?\s*throw e""")
                .containsMatchIn(preload)
        )
        assertTrue(
            "预加载不是「一次性」的（找不到 `compareAndSet(false, true)`）—— " +
                "start() 有两个调用点（启动页闸门 / connectivity 转 ONLINE），必须幂等。",
            preload.contains("compareAndSet(false, true)")
        )

        // TOTAL_STEPS 必须与实际步数一致，否则启动页的 "x / N" 会停在 6/7 之类的假进度
        val declaredTotal = Regex("""TOTAL_STEPS\s*=\s*(\d+)""").find(preload)
            ?.groupValues?.get(1)?.toInt()
        val actualSteps = Regex("""\bstep\(""").findAll(preload).count() - 1 // 减去 step() 自身的定义
        assertEquals(
            "TOTAL_STEPS 与 start() 里 step( 的调用次数不一致 —— 启动页的进度计数会永远差几格，" +
                "看起来像卡住了。声明 $declaredTotal，实际 $actualSteps。",
            declaredTotal,
            actualSteps
        )

        assertTrue(
            "MainViewModel 里找不到「ONLINE 才启动预加载」的闸门 —— " +
                "离线时发这一批必定失败的请求会自我坐实掉线判据，开机就弹掉线窗。",
            Regex("""ConnectivityStatus\.ONLINE""").containsMatchIn(vm) &&
                Regex("""preload\.start\(\)""").containsMatchIn(vm)
        )
        assertTrue(
            "启动页闸门里找不到「确证不可达就直接放行」那条路" +
                "（`settled.status != ConnectivityStatus.ONLINE` → return）—— " +
                "缺了它，离线冷启动会一直停在加载页。",
            Regex("""settled\.status\s*!=\s*ConnectivityStatus\.ONLINE""").containsMatchIn(vm)
        )
        val resets = Regex("""preload\.reset\(\)""").findAll(vm).count()
        assertTrue(
            "`preload.reset()` 少于 2 处 —— prepareDeviceSwitch 与 onServerEndpointChanged(hostChanged) " +
                "两条换设备路径都必须复位，否则新设备的数据没人预加载。当前 $resets 处。",
            resets >= 2
        )
    }

    /**
     * 监控中心必须被预热，而且必须**复用同一个 `MonitorTimeRange` 实例**。
     *
     * `MonitorTimeRange.today()` 的 `endMs` 按分钟向下取整，所以每次 new 出来的实例跨分钟就是
     * 另一个记账 key（`DashboardModule.monitorRangeKey`）。预热时若自己 new 一个传给
     * `loadMonitorTypes`，`MonitorScreen` 进页面时用的是 state 里那个 —— 两个 key 不同，
     * `syncMonitorRangeKey` 会清掉记账并把这 6 类**重拉一遍**，预热等于白做。
     */
    @Test
    fun preload_mustWarmMonitorOverviewWithTheSameRangeInstance() {
        val preload = executableCode(source(preloadPath))

        assertTrue(
            "预加载没有拉监控曲线（找不到 `loadMonitorTypes(`）—— 切到监控中心时总览那 6 格" +
                "仍要现场等 6 条序列，这一页就等于没接入预加载。",
            preload.contains("loadMonitorTypes(")
        )
        assertTrue(
            "找不到总览用到的指标清单 OVERVIEW_METRIC_KEYS。",
            preload.contains("OVERVIEW_METRIC_KEYS")
        )
        assertTrue(
            "`loadMonitorTypes` 的 range 不是从 state 里取的" +
                "（应为 `dashboard.monitorState.value.selectedRange`）—— 见本测试的 KDoc：" +
                "自己 new 一个 today() 会让记账 key 对不上，MonitorScreen 进页面时全部重拉。",
            preload.contains("dashboard.monitorState.value.selectedRange")
        )
        assertTrue(
            "监控设置必须排在拉曲线**之前**：enabledTypes 决定拉哪几类，" +
                "collectEnabled = false 时 loadMonitorTypes 直接空转。",
            preload.indexOf("refreshMonitorSettingsFromBackend") <
                preload.indexOf("loadMonitorTypes(")
        )
    }

    /**
     * 启动页必须有**兜底终局**，且收尾动作不能写在超时块里面。
     *
     * 参考实现（UFITOOLS-Widget）的 ViewModel 注释专门记过这个坑：某条分支静默返回 ⇒
     * 调用方无从得知刷新已结束 ⇒ 加载动画永远停在"请稍候"。这里的红线是
     * `_startupPhaseDone.value = true` 必须在 `withTimeoutOrNull(...)` **之后**执行 ——
     * 写进块里就意味着超时路径不收尾，用户被永久锁在加载页。
     */
    @Test
    fun startupGate_mustAlwaysHaveATerminalExit() {
        val vm = executableCode(source(mainViewModelPath))

        assertTrue(
            "启动页闸门没有超时兜底（找不到 `withTimeoutOrNull(STARTUP_GATE_TIMEOUT_MS)`）—— " +
                "某一步挂住（OkHttp readTimeout 是 120s）就会把用户永久锁在加载页。",
            vm.contains("withTimeoutOrNull(STARTUP_GATE_TIMEOUT_MS)")
        )
        val timeoutAt = vm.indexOf("withTimeoutOrNull(STARTUP_GATE_TIMEOUT_MS)")
        val releaseAt = vm.indexOf("_startupPhaseDone.value = true")
        assertTrue(
            "找不到收尾动作 `_startupPhaseDone.value = true`。",
            releaseAt >= 0
        )
        assertTrue(
            "收尾动作写在了超时块**之前/之内** —— 必须在 withTimeoutOrNull 之后无条件执行，" +
                "否则超时路径不收尾，加载页永远停在\"加载中\"。",
            releaseAt > timeoutAt
        )
        assertTrue(
            "启动页没有最短展示时间（找不到 STARTUP_GATE_MIN_MS）—— 局域网上预加载几百毫秒就完事，" +
                "不设下限会\"闪一下\"，比不显示更难看。",
            vm.contains("STARTUP_GATE_MIN_MS")
        )
        assertTrue(
            "启动页的可见性不在 ViewModel 里（找不到 startupOverlayVisible）—— " +
                "放在 Activity 的 remember 里，旋转屏幕 / 从最近任务回来会再看一遍启动页。",
            vm.contains("val startupOverlayVisible")
        )
    }

    /**
     * 启动页接管「连不上设备」之后，必须留一个让开的出口。
     *
     * 三种病因里有两种不是在这一页能解决的：「手机未连接网络」要去系统 WiFi 设置、
     * 「未配置设备地址」要走配对流程。没有出口就等于把用户锁在一个按不动的整屏页面上 ——
     * 比它取代的那个模态弹窗还糟（那个至少是可关闭的）。
     */
    @Test
    fun startupProblem_mustBeEscapableAndCoreUpdateExempt() {
        val vm = executableCode(source(mainViewModelPath))
        val activity = executableCode(source(mainActivityPath))

        assertTrue(
            "找不到 `startupProblem` —— 「连不上设备」那一态的数据源没了，" +
                "启动页会一直转圈而不告诉用户原因。",
            vm.contains("val startupProblem")
        )
        assertTrue(
            "找不到让开的出口 `fun skipConnectionNotice()` —— " +
                "见本测试 KDoc：没有出口会把用户锁在整屏提示上。",
            Regex("""fun skipConnectionNotice\(\)""").containsMatchIn(vm)
        )
        assertTrue(
            "`skipConnectionNotice` 的标记没有在恢复 ONLINE 时复位 —— " +
                "让开过一次之后这台设备再也不会用启动页报连不上。",
            Regex("""_connectionNoticeSkipped\.value = false""").containsMatchIn(vm)
        )
        assertTrue(
            "`startupProblem` 没有 Core 更新豁免（找不到 isCoreUpdateInterruptingBackend）—— " +
                "升级 Core 时 8088 必然断联约 1 分钟，那是预期结果，不该拿一整页去报。",
            Regex("""isCoreUpdateInterruptingBackend\(\)""").containsMatchIn(vm)
        )
        assertFalse(
            "旧的模态弹窗状态 `connectivityDialogState` 又回来了 —— " +
                "它已被启动页取代（2026-09-22），两个一起在就是同一件事讲两遍。",
            vm.contains("connectivityDialogState")
        )

        assertTrue(
            "Activity 侧没有把「去服务器设置 / 去配对」与让开动作绑在一起 —— " +
                "启动页是全屏不透明浮层，不先让开就会把目标页整个盖住。",
            Regex("""viewModel\.skipConnectionNotice\(\)""").containsMatchIn(activity)
        )
        assertTrue(
            "启动页期间没有抑制底部胶囊（找不到 `suppressCapsule`）—— " +
                "胶囊活在独立 Dialog 窗口里，窗口层级高于 Activity 主窗口，" +
                "主窗口的全屏浮层**盖不住它**，加载页上会浮着一条胶囊。",
            activity.contains("suppressCapsule")
        )
    }

    /**
     * 「连不上」那一态的重试必须给可见反馈。
     *
     * 它刻意**不让页面消失**（探活结果才是答案），于是点下去屏幕上什么都不动 ——
     * 而 `/health` 最长要 15s 才出结论，这段时间里按钮看起来就是坏的。
     */
    @Test
    fun connectivityRetry_mustGiveVisibleFeedback() {
        val code = executableCode(source(mainActivityPath))

        assertTrue(
            "「重试」没有 Toast 反馈 —— 它不让页面消失、探活最长 15s，" +
                "没有反馈时用户只会以为按钮坏了。",
            code.contains("\"正在重新连接设备…\"")
        )
        assertTrue(
            "找不到 `viewModel.retryConnectivity()` —— 反馈给了但没真的去探活。",
            code.contains("viewModel.retryConnectivity()")
        )
    }
}



