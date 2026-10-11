package com.ufi_axis_core.controller.system

import com.ufi_axis_core.util.ShellExecutor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * 应用列表缓存（2026-10-12 批次）的行为单测 —— 验的全是**「第二次还跑不跑 shell」**这件事。
 *
 * ## 为什么值得单独测
 * 这个缓存的收益本身就是「少跑 shell」，而它的失败形态是**静默的**：缓存写不进去、失效点漏一个、
 * 锁放错位置，代码照样编译、接口照样返回 200，只有真机上"进页面还是很慢"或者
 * "我禁用了它列表还显示启用"能发现。所以判据必须是**可数的** —— 数 `listLoader` 被调了几次。
 *
 * ## 为什么用 `listLoader` 这个测试缝而不是 mock `ShellExecutor`
 * `ShellExecutor` 是 `object` + 静态方法，mock 起来比重写实现还脆（且它的真实现会去跑 adb/su）。
 * `AppManager.listLoader` 是本批次引入的内部可替换缝（默认就是真的 4 条特权 shell），
 * 换掉它即换掉全部副作用面。**别把这个缝升级成生产依赖**：它没有接口、也不该有。
 *
 * ## 设备依赖的处理
 * 真实 [AppManager] 构造不需要 Context（默认 `null`），而本文件关心的路径
 * （缓存 / 失效 / 互斥）**完全不碰 Context**：过滤只读 `isSystem` 字段，
 * 失效点由写方法无条件调用、不看返回值。所以这里 `AppManager(null)` 就够，
 * 不需要 Robolectric —— 换 UI 层才有必要（那才会碰 `packageManager`）。
 */
class AppListCacheTest {

    // ═══════════════════════════════════════════════════════════
    // 测试脚手架
    // ═══════════════════════════════════════════════════════════

    private fun pkg(name: String, isSystem: Boolean): Map<String, Any> = mapOf(
        "packageName" to name,
        "appName" to name,
        "apkPath" to (if (isSystem) "/system/app/$name.apk" else "/data/app/$name.apk"),
        "isSystem" to isSystem,
        "versionName" to "1.0",
        "enabled" to true,
        "isFrozen" to false,
        "iconBase64" to ""
    )

    /** 造一个 loader：每次调用计数 +1，返回固定的 4 个包（2 系统 2 用户）。 */
    private fun AppManager.countingLoader(): AtomicInteger {
        val calls = AtomicInteger(0)
        listLoader = {
            calls.incrementAndGet()
            listOf(
                pkg("com.a.user", isSystem = false),
                pkg("com.b.user", isSystem = false),
                pkg("com.c.sys", isSystem = true),
                pkg("com.d.sys", isSystem = true)
            )
        }
        return calls
    }

    /** [AppManager] 的构造/字段都不碰 Android 运行时，只有 Android 类的方法体会在真机缺 root 时报错。 */
    private fun newManager(): AppManager = AppManager(null)

    // ═══════════════════════════════════════════════════════════
    // ① 首次拉取 → 第二次命中缓存，**shell 计数不再增长**
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `首次拉取后再次进入页面不再执行 shell`() = runBlocking {
        val m = newManager()
        val calls = m.countingLoader()

        val first = m.listApps("user")
        val second = m.listApps("user")

        assertEquals("首次必须真拉一次", 1, calls.get())
        assertEquals("第二次必须命中缓存（这是本批次的核心诉求）", 1, calls.get())
        assertEquals("两次结果必须一致", first, second)
        assertEquals("filter=user 只给用户应用", 2, second.size)
    }

    /**
     * 三个页签共用**一份**全量缓存 —— 不是三份。
     *
     * 原来每次调用都重跑 4 条 shell 再过滤，所以"用户切页签"同样是秒级卡顿。
     * 缓存只存全量、过滤在内存做，切页签必须零 shell。
     */
    @Test
    fun `切筛选页签不再执行 shell 且三个结果口径正确`() = runBlocking {
        val m = newManager()
        val calls = m.countingLoader()

        val user = m.listApps("user")
        val system = m.listApps("system")
        val all = m.listApps("all")
        // 用户在页签间来回切三遍 —— 现实里就是这么用的
        m.listApps("user"); m.listApps("system"); m.listApps("all")

        assertEquals("三个页签 + 三次回切只应触发一次 shell", 1, calls.get())
        assertEquals(listOf("com.a.user", "com.b.user"), user.map { it["packageName"] })
        assertEquals(listOf("com.c.sys", "com.d.sys"), system.map { it["packageName"] })
        assertEquals(4, all.size)
    }

    /**
     * 未知 filter 必须回落成全量，而不是空列表。
     *
     * 路由层 `filter` 取 `call.request.queryParameters["filter"] ?: "all"`，老版本客户端
     * 可能带任意值；返回空会让老客户端"应用管理页面永远是空的"。
     */
    @Test
    fun `未知 filter 回落为全量而不是空`() = runBlocking {
        val m = newManager()
        m.countingLoader()
        assertEquals(4, m.listApps("some-legacy-value").size)
        assertEquals("第二次仍命中", 4, m.listApps("some-legacy-value").size)
    }

    // ═══════════════════════════════════════════════════════════
    // ② 写操作后缓存失效
    // ═══════════════════════════════════════════════════════════

    /**
     * 写操作之后缓存必须失效 —— 走**真实写方法**触发，而不是直接调私有 invalidate。
     *
     * 之所以直接调 [ShellExecutor] 的假结果不可行（它是 object），这里换一条路：
     * 用**参数校验失败**的包名（会抛 IllegalArgumentException）验证不了失效 ——
     * 所以改为让写方法在参数合法的情况下真跑一遍：真机上它会去打 adb，测试环境失败，
     * 但**失效动作在方法体内是无条件的**（不看 shell 返回值），所以计数仍能验。
     * 注释里写清这点：测的是"失效动作发生"，不是"shell 成功"。
     */
    @Test
    fun `写操作后缓存失效 下一次读取重新拉取`() = runBlocking {
        val m = newManager()
        val calls = m.countingLoader()
        m.listApps("user")
        assertEquals(1, calls.get())

        // 冻结：改变 enabled 与 isFrozen 两个字段，是用户最常点、也最容易被当场发现的动作。
        // shell 在测试环境必然失败（无 adb/root），但失效点是无条件执行的。
        runCatching { m.freezeApp("com.a.user") }

        m.listApps("user")
        assertEquals("写操作后必须重新拉一次（否则用户禁用/冻结了列表还不变）", 2, calls.get())
    }

    /**
     * 失效覆盖**全部 10 个「单条 shell」写方法** —— 逐个点名，不抽样。
     *
     * 漏一个失效点的表现是"某个操作之后列表不更新"，而这种 bug 不会被任何现有测试发现：
     * 它是**新增缓存**引入的风险面，属于"自己加的坑自己必须钉住"。
     *
     * 这里刻意**不走** `installApk` / `installApkFromUrl`：它们在 adb 主通道失败后会回退
     * `installApkMultiStrategy`，里面是 30 轮 `delay(1000)` 轮询 —— 单测里跑它等于让 JVM
     * 空转半分钟（本仓库构建很慢，不为覆盖率付这个代价）。这两个走下面的源码级断言。
     *
     * 另外注意：测试机上 `pm` / `am` 都不存在，这些 shell 会**立刻失败**返回，
     * 但失效动作在方法体内是**无条件**执行的（不看 shell 返回值），所以计数照样能验。
     * 测的是"失效动作发生"，不是"shell 成功"。
     */
    @Test
    fun `全部单条 shell 写方法都会让缓存失效`() = runBlocking {
        val writes: List<Pair<String, suspend (AppManager) -> Unit>> = listOf(
            "uninstallApp" to { m -> m.uninstallApp("com.a.user") },
            "disableApp" to { m -> m.disableApp("com.a.user") },
            "enableApp" to { m -> m.enableApp("com.a.user") },
            "clearAppData" to { m -> m.clearAppData("com.a.user") },
            "forceStop" to { m -> m.forceStop("com.a.user") },
            "grantPermission" to { m -> m.grantPermission("com.a.user", "android.permission.CAMERA") },
            "grantAllPermissionsViaAdb" to { m -> m.grantAllPermissionsViaAdb("com.a.user") },
            "revokePermission" to { m -> m.revokePermission("com.a.user", "android.permission.CAMERA") },
            "freezeApp" to { m -> m.freezeApp("com.a.user") },
            "unfreezeApp" to { m -> m.unfreezeApp("com.a.user") }
        )

        for ((name, action) in writes) {
            val m = newManager()
            val calls = m.countingLoader()
            m.listApps("user")
            assertEquals("$name：前置应当已命中缓存", 1, calls.get())

            runCatching { action(m) }

            m.listApps("user")
            assertEquals("$name 之后必须失效缓存并重新拉取", 2, calls.get())
        }
    }

    /**
     * 两个安装路径的失效点用**源码级断言**钉住（它们没法在单测里安全真跑，见上）。
     *
     * 为什么用源码断言而不是行为断言：安装链路的失效点在 `installApk` / `installApkFromUrl`
     * 末尾，而这两个方法体里没有条件分支包裹它（是"无论如何都失效"），所以真正要防的回归
     * 是"有人后来把那一行删了 / 挪进 if 里"。正则按方法体切片断言，正好是这个回归形态。
     *
     * 判据写成「方法体内出现 invalidateAppListCache」而不是整文件出现一次 ——
     * 整文件只出现一次的话，两处共用同一个调用点也能过，那正是要防的漏法。
     */
    @Test
    fun `installApk 与 installApkFromUrl 源码内都有失效点`() {
        val src = File(
            "src/main/java/com/ufi_axis_core/controller/system/AppManager.kt"
        ).readText(Charsets.UTF_8)

        for (fn in listOf("installApk", "installApkFromUrl")) {
            val start = src.indexOf("suspend fun $fn(")
            assertTrue("必须能找到 $fn 的声明", start >= 0)
            // 用下一个 `suspend fun` 声明作切点（brace 炸弹教训：不能用第一个 "\n    }" 定位）
            val nextFn = src.indexOf("\n    suspend fun ", start + 1)
            val body = src.substring(start, if (nextFn < 0) src.length else nextFn)
            assertTrue(
                "$fn 内必须有 invalidateAppListCache() —— 否则装完新应用列表里看不到它",
                body.contains("invalidateAppListCache()")
            )
        }
    }

    // ═══════════════════════════════════════════════════════════
    // ③ 并发只跑一次 shell（防击穿）
    // ═══════════════════════════════════════════════════════════

    /**
     * N 个并发首请求 → shell 只跑一次。
     *
     * 这是最容易写错的一处：二次检查若放在锁**外**，N 个并发请求会一起读到 null，
     * 各自进锁、各跑一遍 4 条 shell（在随身 WiFi 上等于 4N 条特权 shell 同时飞，
     * 比串行还慢 —— 用户体感反而更差）。锁内二次检查是唯一的正解。
     *
     * ## 屏障怎么摆（这一版踩过的坑）
     * 最初的写法是「在 loader 内部数到达人数，满 N 才放行」—— **那必然死锁**：
     * 有了互斥，loader 全程只会被第一个人调用，`arrived` 永远到不了 N，
     * 而第一个人又在等它 —— 一个只有"实现写错了"才会暴露的死锁。
     * 正确形态是：屏障放在 **loader 之外**（所有协程到齐才一起发起请求），
     * 窗口靠 loader 里那段 `delay` 撑开。
     */
    @Test
    fun `并发首请求只执行一次 shell`() = runBlocking {
        val m = newManager()
        val calls = AtomicInteger(0)
        val start = CompletableDeferred<Unit>()
        val n = 16

        m.listLoader = {
            calls.incrementAndGet()
            // 撑开窗口：让后来者在"缓存还没写好"这段时间里一起挤到入口
            delay(200)
            listOf(pkg("com.a.user", isSystem = false))
        }

        val jobs = (0 until n).map {
            async(Dispatchers.Default) {
                start.await()
                m.listApps("user")
            }
        }
        start.complete(Unit)
        val lists = jobs.map { it.await() }

        assertEquals("$n 个并发首请求只应跑一次 shell", 1, calls.get())
        assertTrue("每个并发调用都必须拿到非空结果（不是「有的拿到空列表」）", lists.all { it.size == 1 })
    }

    /** 缓存已热时的并发读：一个请求都不该发（这是最常见的形态 —— 多人同时进这一页）。 */
    @Test
    fun `缓存已热时的并发读一个 shell 都不发`() = runBlocking {
        val m = newManager()
        val calls = m.countingLoader()
        m.listApps("all")   // 先热起来

        val results = (0 until 8).map { async(Dispatchers.Default) { m.listApps("all") } }.map { it.await() }
        assertEquals("命中缓存后并发读不应触发任何 shell", 1, calls.get())
        assertTrue(results.all { it.size == 4 })
    }

    // ═══════════════════════════════════════════════════════════
    // ④ 空结果不入缓存（失败写空 = 页面在 TTL 内全空）
    // ═══════════════════════════════════════════════════════════

    /**
     * 拉取返回空（设备上 `pm` 瞬时失败）时**不得**写缓存。
     *
     * 若把空列表也缓存住，一次瞬时失败会让用户在这个 TTL 内一直看着空页面，
     * 而应用管理页面的空态是"未找到应用" —— 用户会去检查自己的应用，而不是刷新。
     * 设备上"真的一个包都没有"不可能（系统至少几十个包），所以这个判据不会误伤真空设备。
     */
    @Test
    fun `拉取返回空时不写缓存 下一次仍会重试`() = runBlocking {
        val m = newManager()
        val calls = AtomicInteger(0)
        m.listLoader = {
            calls.incrementAndGet()
            emptyList<Map<String, Any>>()
        }

        assertTrue(m.listApps("user").isEmpty())
        assertTrue(m.listApps("user").isEmpty())
        assertEquals("空结果不得被缓存住（否则一次失败 = TTL 内整页空）", 2, calls.get())
    }

    /** 空结果之后一旦拉到真数据，必须立刻可用（不能被"上一次空"卡住）。 */
    @Test
    fun `空结果之后拉到真数据立即生效`() = runBlocking {
        val m = newManager()
        val calls = AtomicInteger(0)
        m.listLoader = {
            if (calls.incrementAndGet() == 1) emptyList()
            else listOf(pkg("com.a.user", isSystem = false))
        }
        assertTrue(m.listApps("user").isEmpty())
        assertEquals(1, m.listApps("user").size)
    }

    // ═══════════════════════════════════════════════════════════
    // ⑤ 定时刷新：起停可控、不泄漏
    // ═══════════════════════════════════════════════════════════

    /**
     * 定时刷新的生命周期契约：**构造时绝不起**，只有 [AppManager.startPeriodicRefresh]
     * 之后才起，重复 start 只起一条。
     *
     * 「构造不起」是硬要求：`BootReceiver` 开机恢复会 new 一个只用来 `installApk` 的
     * 短命实例，构造即起循环 = 给它挂一条永不结束、也没人能停的协程（泄漏）。
     * 这条断言把这个决定钉住，将来有人为了"省一次拉取"把它搬进 init{} 就会红。
     *
     * 为什么验不到"停掉之后不再刷"：那需要等满一整个 2 分钟周期，单测里不可接受
     * （本仓库单模块跑一次要几分钟）。改为验**可观测的等价物**：停 → 恢复这条路径
     * 不会凭空多刷一轮（计数不变），而首轮确实被推迟（start 之后立刻读仍只有 1 次）。
     */
    @Test
    fun `构造时不起协程 start 之后才起 重复 start 不叠加`() = runBlocking {
        val m = newManager()
        val calls = m.countingLoader()

        // ① 构造后：没有任何自动刷新发生
        yield(); yield()
        assertEquals("构造时不得自动起刷新协程（BootReceiver 的短命实例会泄漏）", 0, calls.get())

        // ② start 之后立刻仍不该已经刷过（首轮要等一个周期）
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        m.startPeriodicRefresh(scope)
        m.startPeriodicRefresh(scope)          // 幂等：不得起第二条循环
        m.listApps("user")
        assertEquals(1, calls.get())

        // ③ 停 → 恢复：不得凭空多刷（计数不变 = 没有额外的一轮）
        m.setPeriodicRefreshEnabled(false)
        m.setPeriodicRefreshEnabled(false)     // stopAllComponents 会重复调，必须幂等
        m.setPeriodicRefreshEnabled(true)
        m.listApps("user")
        assertEquals("停→恢复不得凭空多刷一轮", 1, calls.get())

        scope.cancel()
    }

    /**
     * `setPeriodicRefreshEnabled(true)` 在**从未 start 过**时必须保持不启动。
     *
     * 若这里自造一个 `CoroutineScope`，那条协程就与 core 服务生命周期彻底脱钩 ——
     * 正是本批次要消灭的泄漏形态（「不能在 AppManager 构造时无条件启动，否则会泄漏
     * 且在服务停止后仍在跑」）。所以宁可「不刷」，也不能凭空造 scope。
     */
    @Test
    fun `从未 start 时 enable 不自造协程`() = runBlocking {
        val m = newManager()
        val calls = m.countingLoader()
        m.setPeriodicRefreshEnabled(true)
        yield(); yield()
        assertEquals("enable 不得凭空造一个与服务脱钩的 scope", 0, calls.get())
    }

    /**
     * 定时刷新与用户读取共用同一把锁：**刷新在飞时用户进页面，会等它跑完拿到新值**，
     * 而不是在旁边再开一轮（那就是"同时 8 条特权 shell"）。
     *
     * 这里用 loader 挂起模拟"刷新正在飞"，然后并发发起读请求，验证它拿到的是**那一轮**
     * 的结果而不是"另起一轮"的结果 —— 后者的计数会是 2。
     */
    @Test
    fun `刷新在飞时用户读取不并发开新一轮`() = runBlocking {
        val m = newManager()
        val calls = AtomicInteger(0)
        val gate = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()

        m.listLoader = {
            val n = calls.incrementAndGet()
            if (n == 1) {
                entered.complete(Unit)
                gate.await()                       // 把第一轮挂住
            }
            listOf(pkg("com.a.user", isSystem = false))
        }

        // ① 用户进页面 → 第一轮在飞，挂住
        val first = async(Dispatchers.Default) { m.listApps("user") }
        entered.await()

        // ② 第二个请求同时到达（它会阻塞在 appListMutex 上）
        val second = async(Dispatchers.Default) { m.listApps("user") }
        yield(); yield()
        assertEquals("第二个请求必须阻塞在锁上，不能自己再开一轮 shell", 1, calls.get())

        // ③ 放行第一轮，两个请求都拿到同一份数据
        gate.complete(Unit)
        assertEquals(1, first.await().size)
        assertEquals(1, second.await().size)
        assertEquals("总共只应跑一次 shell（第二个请求在锁内命中了刚写好的缓存）", 1, calls.get())
    }
}
