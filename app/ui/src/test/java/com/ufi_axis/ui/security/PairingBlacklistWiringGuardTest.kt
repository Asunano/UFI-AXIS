package com.ufi_axis.ui.security

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 配对设备黑名单的**装配守卫**（2026-10-15）。
 *
 * ## 守什么
 * 这套功能的失效方式全都不是"编译不过"，而是**静默失效**：
 * - `ComponentFactory` 忘了把 store 注入 `PairingManager` → 拦截形同虚设（可空参数默认 null，
 *   编译完全通过，测试也不会红，因为测试是自己 new 的 manager）；
 * - `ComponentFactory` 忘了把路由传给 `HttpServer`、或 `HttpServer` 忘了 register →
 *   管理入口 404，用户以为"功能没做"；
 * - 拦截点被挪到 `claimChallenge` **之后** → 被拉黑设备每次尝试都白烧一个一次性挑战；
 * - UI 忘了隐藏本机行的「拉黑」按钮 → 机主一点就把自己踢下线，而且无人能解除。
 *
 * 这四件事都没有编译期或运行期信号，只能靠源码守卫。与 `NavInsetHandoffGuardTest`
 * 同一套做法：从模块目录逐级上溯定位源文件，剥掉注释后断言可执行代码。
 */
class PairingBlacklistWiringGuardTest {

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

    /** 剥掉块注释与行注释：这几处注释里就写着"必须放在 claimChallenge 之前"之类的说明，直接全文搜会误报。 */
    private fun code(text: String): String =
        text.replace(Regex("""/\*[\s\S]*?\*/"""), "")
            .replace(Regex("""//[^\n]*"""), "")

    /** 仓库根（用于全仓扫描）。 */
    private fun repoRoot(): File {
        var dir: File? = File(".").absoluteFile.normalize()
        while (dir != null) {
            if (File(dir, "settings.gradle.kts").isFile) return dir
            dir = dir.parentFile
        }
        throw AssertionError("定位不到仓库根（起点：${File(".").absolutePath}）")
    }

    /**
     * 媒体与文件传输安全守卫（方案 §14，2026-10-15）。
     *
     * ## 为什么必须有这一条
     * 退避计数与冷却**只能**用在配对/登录这条链路上。一旦有人把 `PairingAttemptLimiter`
     * 接到普通请求链路（鉴权中间件、媒体路由…）上，表现就是：
     * **播放途中一次签名抖动被算作"失败"，设备被推进退避 → 听歌/看电影时被踢出去。**
     *
     * （注意：本注释里不能写「斜杠 + 星号」组成的路径通配 —— Kotlin 的块注释**会嵌套**，
     *   那个组合会把整个文件后半段吞进注释里，报出来的却是"第 51 行缺右括号"。）
     *
     * 而这种改动**编译通过、现有单测也不会红**（所有测试都是自己 new 一个 manager 直接调），
     * 只有这条"引用范围"的源码守卫能挡住它。
     *
     * 允许出现的地方就四个：两个限流类自身、配对管理器、装配层。
     * 路由层要用也只该经 `pairingManager.attemptDelayMsFor(...)` 这种间接调用。
     */
    @Test
    fun `退避与冷却不得被配对域之外的代码引用`() {
        val symbols = listOf("PairingAttemptLimiter", "PairingBlockCooldown")
        val allowed = setOf(
            "core/api/src/main/java/com/ufi_axis_core/api/pairing/PairingAttemptLimiter.kt",
            "core/api/src/main/java/com/ufi_axis_core/api/pairing/PairingBlockCooldown.kt",
            "core/api/src/main/java/com/ufi_axis_core/api/pairing/PairingManager.kt",
            "core/src/main/java/com/ufi_axis_core/service/ComponentFactory.kt"
        )
        val root = repoRoot()
        val offenders = mutableListOf<String>()
        File(root, "core").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { it.path.contains("${File.separator}src${File.separator}main${File.separator}") }
            .forEach { file ->
                val rel = file.relativeTo(root).path.replace(File.separator, "/")
                if (rel in allowed) return@forEach
                val text = code(file.readText())
                val hit = symbols.firstOrNull { it in text }
                if (hit != null) offenders += "$rel ($hit)"
            }

        assertTrue(
            "退避/冷却被配对域之外的代码引用了：$offenders —— " +
                "把失败计数接到普通请求上会让**播放中的设备被踢出去**（方案 §14 的硬约束）。" +
                "若确实需要，请改成经 PairingManager 的间接调用，并同步更新本测试的允许清单与理由。",
            offenders.isEmpty()
        )
    }

    private val componentFactory =
        "core/src/main/java/com/ufi_axis_core/service/ComponentFactory.kt"
    private val httpServer =
        "core/network/src/main/java/com/ufi_axis_core/core/server/HttpServer.kt"
    private val pairingManager =
        "core/api/src/main/java/com/ufi_axis_core/api/pairing/PairingManager.kt"
    private val pairingScreen =
        "app/feature-settings/src/main/java/com/ufi_axis/ui/screens/PairingConfigScreen.kt"
    private val networkModule =
        "app/viewmodel/src/main/java/com/ufi_axis/viewmodel/viewmodel/module/NetworkModule.kt"
    private val pairingRoutes =
        "core/api/src/main/java/com/ufi_axis_core/api/routes/PairingRoutes.kt"
    private val mainActivity =
        "app/src/main/java/com/ufi_axis/MainActivity.kt"
    private val mainViewModel =
        "app/viewmodel/src/main/java/com/ufi_axis/viewmodel/viewmodel/MainViewModel.kt"
    private val pairedApprovalRoutes =
        "core/api/src/main/java/com/ufi_axis_core/api/routes/PairedDeviceApprovalRoutes.kt"

    @Test
    fun `ComponentFactory 必须把黑名单 store 注入 PairingManager`() {
        val src = code(source(componentFactory))
        assertTrue(
            "装配层必须创建 PairedDeviceBlacklistStore",
            "PairedDeviceBlacklistStore(" in src
        )
        assertTrue(
            "装配层必须把它作为 blacklist 参数传给 PairingManager —— 漏传等于 L1 拦截静默失效" +
                "（blacklist 可空默认 null，编译不会报错）",
            Regex("""PairingManager\([^)]*blacklist\s*=\s*pairedBlacklistStore""").containsMatchIn(src)
        )
    }

    @Test
    fun `ComponentFactory 与 HttpServer 必须接上黑名单路由`() {
        val factory = code(source(componentFactory))
        assertTrue(
            "装配层必须构造 PairedDeviceBlacklistRoutes",
            "PairedDeviceBlacklistRoutes(" in factory
        )
        assertTrue(
            "装配层必须把路由传给 HttpServer（否则管理入口 404）",
            Regex("""pairedBlacklistRoutes\s*=\s*pairedBlacklistRoutes""").containsMatchIn(factory)
        )
        val server = code(source(httpServer))
        assertTrue(
            "HttpServer 必须注册该路由",
            Regex("""pairedBlacklistRoutes\?\.register\(this\)""").containsMatchIn(server)
        )
    }

    @Test
    fun `L1 拦截必须发生在 claimChallenge 之前`() {
        val src = code(source(pairingManager))
        val blockedAt = src.indexOf("blacklist?.isBlocked")
        val claimAt = src.indexOf("claimChallenge(trimmedChallenge)")
        assertTrue("PairingManager 里必须有黑名单判定", blockedAt >= 0)
        assertTrue("PairingManager 里必须有 claimChallenge", claimAt >= 0)
        assertTrue(
            "黑名单判定必须在 claimChallenge **之前**：放在后面会让被拉黑设备的每次尝试" +
                "都白烧一个一次性挑战（服务端资源的廉价消耗点）",
            blockedAt < claimAt
        )
    }

    @Test
    fun `设备行必须对本机隐藏拉黑按钮`() {
        val src = code(source(pairingScreen))
        assertTrue(
            "「拉黑」按钮必须包在 `item.fingerprint != selfFingerprint` 判定里 —— " +
                "拉黑本机等于把自己踢下线，且解除拉黑需要已鉴权请求，没有任何设备能救它",
            "item.fingerprint != selfFingerprint" in src
        )
    }

    @Test
    fun `ComponentFactory 必须把双键退避限流器注入 PairingManager`() {
        val src = code(source(componentFactory))
        assertTrue(
            "装配层必须创建 PairingAttemptLimiter",
            "PairingAttemptLimiter(" in src
        )
        assertTrue(
            "必须作为 attemptLimiter 参数传入 —— 漏传等于「换 IP 就能无限爆破」又回来了" +
                "（可空参数默认 null，编译与单测都不会红）",
            Regex("""attemptLimiter\s*=\s*""").containsMatchIn(src)
        )
        assertTrue(
            "装配层还必须创建 PairingBlockCooldown 并传入 —— 漏传等于「解除拉黑即被重新爆破」",
            "PairingBlockCooldown(" in src && Regex("""blockCooldown\s*=\s*""").containsMatchIn(src)
        )
        assertTrue(
            "装配层还必须创建 PairedDeviceApprovalStore 并传入 —— 漏传等于「开关打开了但没人能批准」",
            "PairedDeviceApprovalStore(" in src && Regex("""approvalStore\s*=\s*""").containsMatchIn(src)
        )
    }

    @Test
    fun `批准流程的公开与鉴权端点都必须注册`() {
        val factory = code(source(componentFactory))
        assertTrue(
            "装配层必须构造 PairedDeviceApprovalRoutes",
            "PairedDeviceApprovalRoutes(" in factory
        )
        assertTrue(
            "装配层必须把路由传给 HttpServer",
            Regex("""pairedApprovalRoutes\s*=\s*pairedApprovalRoutes""").containsMatchIn(factory)
        )
        val server = code(source(httpServer))
        assertTrue(
            "公开端点（request-approval / approval-status）必须注册 —— 请求方此刻还没有凭据",
            Regex("""pairedApprovalRoutes\?\.registerPublic\(this\)""").containsMatchIn(server)
        )
        assertTrue(
            "管理端点（列表 / 允许 / 拒绝）必须注册在 /api 内",
            Regex("""pairedApprovalRoutes\?\.registerAuthed\(this\)""").containsMatchIn(server)
        )
    }

    @Test
    fun `旧 core 的 404 必须走静默降级而不是报错`() {
        val src = code(source(networkModule))
        assertTrue(
            "loadBlacklist 必须识别 404（旧 core 没有这组端点）并降级为 blacklistAvailable=false",
            "isEndpointMissing()" in src && "blacklistAvailable = false" in src
        )
        assertTrue(
            "loadApprovals 必须同款降级（approvalAvailable=false）—— 新 App + 旧 core 是必须支持的组合",
            "approvalAvailable = false" in src
        )
    }

    @Test
    fun `待批准列表必须加载且批准弹窗必须挂在 App 根部`() {
        val src = code(source(pairingScreen))
        assertTrue(
            "配对页必须拉取待批准列表 —— 进页面刷一次，与页面上的状态保持一致",
            "loadApprovals()" in src
        )

        // 2026-10-15：弹窗从"页面内"挪到了 App 根部。这条守的是那个决定本身 ——
        // 批准请求可能在机主**任何页面**上到来，而"必须给出结论"的状态不该要求他
        // 先摸到「设置 → 安全中心」才看得到。
        val activity = code(source(mainActivity))
        assertTrue(
            "批准弹窗必须挂在 App 根部（MainActivity）",
            "UfiPairingApprovalDialog(" in activity
        )
        assertTrue(
            "根部仍必须保留「拉一次待批准列表」的兜底轮询 —— 覆盖旧 core 不推送、WS 一直断着" +
                "这两种推送必然失效的情况（快路径是 core 主动推，见下一条守卫）",
            "loadApprovals()" in activity
        )
    }

    /**
     * 批准弹窗的**快路径**：core 主动推 + App 去抖前短路（2026-10-21）。
     *
     * ## 为什么必须守
     * 这条链路失效的样子全都是「功能还在，只是慢回 30 秒」——编译通过、单测全绿、没人报错。
     * 具体有三个静默失效点：
     * 1. 装配层漏传 `onApprovalPending` → 推送根本没发生（可空参数，默认 null）；
     * 2. 路由层把「幂等命中」也当新请求推 → Web 每刷新一次页面就给机主多一轮唤醒；
     * 3. `MainViewModel` 把 `PAIRING_APPROVALS` 交给 800ms 尾沿去抖 → 白白多等 800ms，
     *    一轮 4 帧还会被合并成 1 次（看似正常，实际把"快速重发"的意义抹掉）。
     */
    @Test
    fun `批准请求必须有 core 主动推送且 App 在去抖前短路`() {
        val factory = code(source(componentFactory))
        assertTrue(
            "装配层必须创建 ApprovalPushNudger",
            "ApprovalPushNudger(" in factory
        )
        assertTrue(
            "必须把推送回调传给 PairedDeviceApprovalRoutes（漏传 = 只剩 30s 轮询）",
            Regex("""onApprovalPending\s*=\s*\{""").containsMatchIn(factory)
        )

        val routes = code(source(pairedApprovalRoutes))
        assertTrue(
            "登记成功后必须调用推送回调",
            "onApprovalPending?.invoke" in routes
        )
        assertTrue(
            "推送只能在**新** sid 上触发：幂等命中（Web 刷页面）重推会把机主手机刷屏",
            Regex("""if\s*\(\s*!\s*isExisting\s*\)""").containsMatchIn(routes)
        )

        val vm = code(source(mainViewModel))
        val topicAt = vm.indexOf("PAIRING_APPROVALS")
        val debounceAt = vm.indexOf("delay(DISPATCH_DEBOUNCE_MS)")
        assertTrue("MainViewModel 必须认识 WsDataTopic.PAIRING_APPROVALS", topicAt >= 0)
        assertTrue("dispatchChanged 里必须有 800ms 尾沿去抖（基线没变）", debounceAt >= 0)
        assertTrue(
            "配对待批准必须**在去抖之前**短路：放在 delay 之后就等于白送 800ms，" +
                "一轮 4 帧还会被合并成 1 次",
            topicAt < debounceAt
        )
        assertTrue(
            "短路必须走推送专用的带合并入口（loadApprovalsFromPush），" +
                "否则一轮 4 帧会打出 4 个并发 GET",
            "loadApprovalsFromPush()" in vm
        )
        assertTrue(
            "App 回前台必须补拉一次 —— 后台时推送可能已被系统裁掉，不补就又要等 30s",
            Regex("""fun onAppForegrounded\(\)\s*\{[\s\S]{0,400}?loadApprovals\(\)""").containsMatchIn(vm)
        )

        val network = code(source(networkModule))
        assertTrue(
            "推送触发的拉取必须带在途合并（loadApprovalsFromPush）",
            "fun loadApprovalsFromPush()" in network
        )
        assertTrue(
            "WS 重连（RECONNECTED）必须补拉一次 —— 断线窗口里的推送已经丢了",
            Regex("""RECONNECTED[\s\S]{0,600}?loadApprovals\(\)""").containsMatchIn(network)
        )
    }

    /**
     * 配对码失败即轮换（2026-10-15）必须留在 confirm 的**失败出口**上。
     *
     * 这条守的是"轮换被删掉/被挪进某一个分支"：轮换写在 `when (result)` 之后、对所有非
     * Success 生效 —— 一旦有人把它挪进某个分支，剩下的八条失败路径就重新变成可爆破的口子。
     */
    @Test
    fun `confirm 失败必须轮换配对码且覆盖所有失败分支`() {
        val src = code(source(pairingRoutes))
        assertTrue(
            "confirm 失败出口必须调用 rotatePairingCode()",
            "rotatePairingCode()" in src
        )
        assertTrue(
            "轮换必须写成\"非 Success 即轮换\"（而不是塞进某个分支里）",
            Regex("""if\s*\(\s*result\s*!is\s*PairingConfirmResult\.Success\s*\)""").containsMatchIn(src)
        )
    }
}
