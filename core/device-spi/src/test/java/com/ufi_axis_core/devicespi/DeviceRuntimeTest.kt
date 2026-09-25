package com.ufi_axis_core.devicespi

import android.content.Context
import com.ufi_axis_core.contract.Capability
import com.ufi_axis_core.deviceschema.DeviceProfile
import com.ufi_axis_core.deviceschema.FieldGroup
import com.ufi_axis_core.deviceschema.FieldSpec
import com.ufi_axis_core.deviceschema.SettingKey
import com.ufi_axis_core.deviceschema.WriteSpec
import com.ufi_axis_core.devicespi.adapter.DeviceAdapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.runBlocking

/**
 * [DeviceRuntime.resolve] 的选型规则测试（阶段 2 批 B，阶段 5 的 5.2 起加 probe 打分）。
 *
 * ## 为什么全用假插件、不用 `ZteF50Plugin`
 *
 * 真插件在 `:core:device-plugins`，而那个模块**依赖**本模块 —— 反向依赖会成环。
 * 所以这里自己写最小假实现。假实现刻意复刻了真实的 id 关系
 * （插件 id `zte-f50` ≠ profile id `zte-goform`），这正是「旧口径兼容」那条规则要守的东西。
 *
 * 假 profile 的思路与 `core/device-schema` 测试里的 `MockAltProfile` 相同，但**不跨模块引用它的
 * test 源集**（跨模块 test 依赖不开这个口子），字段全空即可 —— 本类只关心选了谁，不关心字段表。
 *
 * ## 2026-09-24（阶段 5 的 5.2）：既有 11 条**一个断言都没改**
 *
 * `resolve()` 变成 `suspend` 且多收一份 [ProbeEnv]、并新增了「配置为空 → probe 打分」那条路径。
 * 既有用例继续绿的方式是：
 *
 * 1. [FakePlugin.probe] 的默认返回值仍然是 **0**（`probeScore` 的默认值），
 *    于是 `defaultPlugin` / `altPlugin` 打分全 0 → 走的是「probe 无结果 → DEFAULT」那条，
 *    与改造前的「配置为空 → DEFAULT」**同一个结果、同样不打日志**；
 * 2. 新签名被 [resolve] 这个私有辅助函数吃掉（`runBlocking` + 新参数全给默认值），
 *    所以 11 条用例的函数体一个字都没动。
 *
 * ⚠ 那三条断言「不打日志」的用例（`归一化开 + 空 id...` / `归一化关时...` / `空白 id...`）
 * 现在**同时**钉住了「probe 全 0 这条路径刻意不打日志」这一条决定
 * （判据写在 [DeviceRuntime.resolve] 的 KDoc 第 4 条最后一项）。
 * 将来要给那条路径补 WARN 的人：改这三条断言是**有意的行为变更**，请一并更新那段 KDoc。
 */
class DeviceRuntimeTest {

    // ── 假实现 ──────────────────────────────────────────────────────────────

    private class FakeProfile(
        override val id: String,
        override val displayName: String,
    ) : DeviceProfile {
        override fun readSpecs(): List<FieldSpec> = emptyList()
        override fun cmdsFor(group: FieldGroup): List<String> = emptyList()
        override fun writeSpec(key: SettingKey): WriteSpec? = null
    }

    private class FakePlugin(
        override val id: String,
        override val displayName: String,
        private val deviceProfile: DeviceProfile,
        /**
         * [probe] 的返回值。**默认 0**：既有用例全部靠这个默认值继续走
         * 「probe 无结果 → [DeviceRuntime.Selection.DEFAULT]」那条路径。
         */
        private val probeScore: Int = 0,
        /** true = [probe] 抛异常，用来测「一个插件炸了不许中断整轮选型」。 */
        private val probeThrows: Boolean = false,
    ) : DevicePlugin {
        override fun profile(): DeviceProfile = deviceProfile

        /**
         * 空能力集：本类只测**选型**，而选型规则一个字都不看能力集。
         *
         * 刻意不填几项进来 —— 填了就得解释「为什么这台假设备支持它们」，
         * 而「声明了某项就必须真能做到」那条断言在 `PluginContractTest`（真插件那一侧）。
         */
        override val capabilities: Set<Capability> = emptySet()


        /** 本类永远不装配传输层：真造起来要 HTTP 客户端，而选型与传输无关。 */
        override fun createTransport(cfg: TransportConfig): DeviceTransport =
            throw UnsupportedOperationException("选型测试不构造传输层")

        /**
         * 同上：选型规则一个字都不看六个域的实现（2026-09-25 批 A3 新增的成员）。
         *
         * 真要在这里交出一个 [DeviceAdapter]，就得为六个域各写一份假实现 ——
         * 而「adapter 是不是插件自己交的、id / capabilities 有没有对齐」那条断言
         * 在 `PluginContractTest`（`:core:device-plugins`，真插件那一侧）。
         */
        override fun createAdapter(
            transport: DeviceTransport,
            commandProfile: DeviceProfile,
            normalizeProfile: DeviceProfile?,
        ): DeviceAdapter =
            throw UnsupportedOperationException("选型测试不构造 DeviceAdapter")

        /**
         * 同上：选型规则一个字都不看平台适配层。
         *
         * 而且它的参数是 `android.content.Context` —— 本模块的单测是**不起 Android 的
         * JUnit4 本地单测**，真要在这里造一个平台适配层只会拿到 `RuntimeException: Stub!`。
         */
        override fun platform(ctx: Context): PlatformAdapter =
            throw UnsupportedOperationException("选型测试不构造平台适配层")

        override fun tuning(): DeviceTuning = DeviceTuning(
            downloadThrottleWarnC = 1f,
            downloadThrottleCriticalC = 2f,
            downloadThrottleForcePauseOffsetC = 3f,
            thermalJitterC = 4f,
            bootGraceMs = 5L,
        )

        /**
         * 调用次数。用来钉住「**有配置时 probe 一次都不许被发**」——
         * 只断言选中了谁的话，那条优先级其实是测不出来的
         * （配置命中的插件恰好也可能是打分最高的那个）。
         */
        var probeCalls = 0
            private set

        override suspend fun probe(env: ProbeEnv): Int {
            probeCalls++
            if (probeThrows) throw IllegalStateException(PROBE_BOOM_MESSAGE)
            return probeScore
        }
    }

    /** 假插件抛异常时的 message。WARN 文案**不许**包含它（见那条用例）。 */
    private companion object {
        const val PROBE_BOOM_MESSAGE = "假插件的 probe 故意炸了"
    }

    /** 复刻真实的「插件 id ≠ profile id」关系（`ZteF50Plugin` / `ZteGoformProfile`）。 */
    private val defaultProfile = FakeProfile("zte-goform", "ZTE goform（F50 等）")
    private val defaultPlugin = FakePlugin("zte-f50", "ZTE F50", defaultProfile)

    /** 第二个插件：两种 id 都与默认插件不同，用来证明「按 id 真的选中了它」而不是碰巧回落。 */
    private val altProfile = FakeProfile("acme-rest", "ACME REST（虚构）")
    private val altPlugin = FakePlugin("acme-ufi", "ACME UFI（虚构）", altProfile)

    private val plugins = listOf(defaultPlugin, altPlugin)

    /**
     * 探测指纹的最小样例。
     *
     * **取值全是「什么都没探到」**，且本类的假插件打分与 [ProbeEnv] 的内容**完全无关**
     * （分数由 `FakePlugin.probeScore` 直接给）—— 选型规则不关心指纹里有什么，
     * 那是**插件**的判据（`ZteF50Plugin.probe` 与它的 `PluginContractTest`）。
     * 在这里按真机取值去构造指纹，只会把插件的判据复制到选型测试里，两边都得改。
     */
    private val probeEnv = ProbeEnv(
        cpuInfoPlatform = null,
        androidBuild = BuildInfo(brand = "", model = "", device = "", manufacturer = "", sdkInt = 0),
        goformLdReachable = false,
    )

    private val warns = mutableListOf<String>()
    private val infos = mutableListOf<String>()

    /**
     * 既有 11 条用例的调用形态**一个字没变**：新增的参数全有默认值，
     * `suspend` 由 `runBlocking` 在这里吃掉（`resolve()` 只会调假插件的 `probe()`，零真 I/O）。
     */
    private fun resolve(
        configuredId: String,
        normalizationEnabled: Boolean = true,
        candidates: List<DevicePlugin> = plugins,
        fallback: DevicePlugin = defaultPlugin,
    ): DeviceRuntime = runBlocking {
        DeviceRuntime.resolve(
            plugins = candidates,
            default = fallback,
            configuredId = configuredId,
            normalizationEnabled = normalizationEnabled,
            probeEnv = probeEnv,
            warn = { warns += it },
            info = { infos += it },
        )
    }


    // ── 用例 ────────────────────────────────────────────────────────────────

    @Test
    fun `归一化开 + 空 id 用默认插件且不打日志`() {
        val rt = resolve("")

        assertSame(defaultPlugin, rt.plugin)
        assertNotNull(rt.profile)
        assertSame(defaultProfile, rt.profile)
        assertEquals(DeviceRuntime.Selection.DEFAULT, rt.selection)
        // 原实现在这条路径上也不打任何日志，保持一致。
        assertEquals(emptyList<String>(), warns)
        assertEquals(emptyList<String>(), infos)
    }

    @Test
    fun `归一化关时 profile 为 null 但命令表仍然非空`() {
        val rt = resolve("", normalizationEnabled = false)

        // 6 个客户端吃的是这一份：null 才能让 /api/diagnose 的 normalization_enabled 报 false。
        assertNull(rt.profile)
        // 命令表那一份必须非空，且必须来自**选中的插件**。
        assertSame(defaultProfile, rt.commandProfile)
        // 插件照样选（这是与原实现唯一的结构差异）。
        assertSame(defaultPlugin, rt.plugin)
        assertEquals(DeviceRuntime.Selection.DEFAULT, rt.selection)
        // 文案与原 resolveDeviceProfile() 逐字一致。
        assertEquals(
            listOf("字段归一化已关闭（排障开关），设备字段将原样透传，对外字段名会变回设备原名"),
            warns,
        )
    }

    @Test
    fun `归一化关 + 旧口径 id 时命令表来自选中插件而不是默认插件`() {
        val rt = resolve("acme-rest", normalizationEnabled = false)

        assertNull(rt.profile)
        assertSame(altPlugin, rt.plugin)
        // 关键不变量：排障开关不许把命令表悄悄换成默认设备的。
        assertSame(altProfile, rt.commandProfile)
        assertEquals(DeviceRuntime.Selection.CONFIGURED, rt.selection)
    }

    @Test
    fun `填 plugin id 按新口径命中`() {
        val rt = resolve("zte-f50")

        assertSame(defaultPlugin, rt.plugin)
        assertSame(defaultProfile, rt.profile)
        assertEquals(DeviceRuntime.Selection.CONFIGURED, rt.selection)
        assertEquals(emptyList<String>(), warns)
        // 命中即打一条 INFO，文案与原实现一致（`设备 profile: <profile id>（<显示名>）`）。
        assertEquals(listOf("设备 profile: zte-goform（ZTE goform（F50 等））"), infos)
    }

    @Test
    fun `填 profile id 按旧口径命中并提示映射关系`() {
        val rt = resolve("zte-goform")

        assertSame(defaultPlugin, rt.plugin)
        assertSame(defaultProfile, rt.profile)
        assertEquals(DeviceRuntime.Selection.CONFIGURED, rt.selection)
        // 旧口径不是错误配置 —— 只打 info，不打 warn。
        assertEquals(emptyList<String>(), warns)
        assertEquals(1, infos.size)
        assertTrue("INFO 应保留原文案前缀：${infos[0]}", infos[0].startsWith("设备 profile: zte-goform（"))
        assertTrue("INFO 应说明映射到了哪个插件：${infos[0]}", infos[0].contains("zte-f50"))
    }

    @Test
    fun `旧口径匹配与原实现同样 trim 且大小写不敏感`() {
        val rt = resolve("  ZTE-Goform  ")

        assertSame(defaultPlugin, rt.plugin)
        assertEquals(DeviceRuntime.Selection.CONFIGURED, rt.selection)
        assertEquals(emptyList<String>(), warns)
    }

    @Test
    fun `空白 id 视同未配置`() {
        val rt = resolve("   ")

        assertEquals(DeviceRuntime.Selection.DEFAULT, rt.selection)
        assertSame(defaultPlugin, rt.plugin)
        assertEquals(emptyList<String>(), warns)
        assertEquals(emptyList<String>(), infos)
    }

    @Test
    fun `认不出的 id 回落默认插件并 WARN`() {
        val rt = resolve("nope-9000")

        assertSame(defaultPlugin, rt.plugin)
        assertSame(defaultProfile, rt.profile)
        assertEquals(DeviceRuntime.Selection.FALLBACK, rt.selection)
        assertEquals(1, warns.size)
        assertTrue("WARN 应带上填错的取值：${warns[0]}", warns[0].contains("nope-9000"))
        assertTrue("WARN 应说明回落到了哪个 profile：${warns[0]}", warns[0].contains("回落 zte-goform"))
        // 「可选」清单要同时列出两种可写的 id。
        assertTrue("WARN 的可选清单要含 plugin id：${warns[0]}", warns[0].contains("zte-f50"))
        assertTrue("WARN 的可选清单要含 profile id：${warns[0]}", warns[0].contains("acme-rest"))
        assertEquals(emptyList<String>(), infos)
    }

    @Test
    fun `多插件场景按 id 选中的是目标插件而不是默认插件`() {
        val byPluginId = resolve("acme-ufi")
        assertSame(altPlugin, byPluginId.plugin)
        assertSame(altProfile, byPluginId.profile)
        assertEquals(DeviceRuntime.Selection.CONFIGURED, byPluginId.selection)

        warns.clear()
        infos.clear()

        val byProfileId = resolve("acme-rest")
        assertSame(altPlugin, byProfileId.plugin)
        assertSame(altProfile, byProfileId.profile)
        assertEquals(DeviceRuntime.Selection.CONFIGURED, byProfileId.selection)
    }

    @Test
    fun `本批不产生 PROBED`() {
        // 2026-09-24（阶段 5 的 5.2）起 PROBED 已经有代码路径会返回它，本条的含义随之变成：
        // 「本类的假插件打分全 0（FakePlugin.probeScore 的默认值）→ 这七种输入都不该出 PROBED」。
        // 断言与用例名一个字没改 —— 它守的是「probe 无正分时绝不谎称探到了设备」，
        // 而「probe 有正分 → PROBED」由下面新增的那几条覆盖。
        val selections = listOf("", "   ", "zte-f50", "zte-goform", "acme-ufi", "acme-rest", "nope")
            .map { resolve(it).selection }
        assertTrue(
            "本批不应出现 PROBED：$selections",
            selections.none { it == DeviceRuntime.Selection.PROBED },
        )
    }


    /**
     * 守的是**对外 wire 契约**，不是内部枚举名。
     *
     * `Selection.wire` 是 `GET /api/diagnose` 的 `device_profile.selection` 直接下发的字符串
     * （装配层 `ComponentFactory` 把 `runtime.selection.wire` 交给 `DataHub`，`HttpServer` 原样塞进 JSON）。
     * 所以这四个取值属于 **API 面**：枚举名怎么重构都无所谓，但这四个字符串一动就是一次接口变更。
     *
     * 期望值**逐个写死**，刻意不写 `name.lowercase()` —— 那样枚举改名时期望值会跟着一起变，
     * 测试永远绿、什么都守不住。
     *
     * 最后那条 `entries.size == 4` 是给「将来加第五个值」的人准备的：它会红，
     * 提醒他对外值域扩张要 core / app / web 三端一起改，而不是悄悄多下发一个客户端不认识的取值。
     */
    @Test
    fun `对外 wire 契约 - selection 的四个取值写死不许动`() {
        val why = "这是 /api/diagnose 的 device_profile.selection 对外取值，" +
            "改它等于改 API，app / web 都要同步"

        assertEquals(why, "configured", DeviceRuntime.Selection.CONFIGURED.wire)
        assertEquals(why, "probed", DeviceRuntime.Selection.PROBED.wire)
        assertEquals(why, "default", DeviceRuntime.Selection.DEFAULT.wire)
        assertEquals(why, "fallback", DeviceRuntime.Selection.FALLBACK.wire)

        // 两个值撞同一个 wire，客户端就分不出「命中配置」和「已回落」——比取值写错更难发现。
        val wires = DeviceRuntime.Selection.entries.map { it.wire }
        assertEquals(
            "四个 wire 必须互不相同（$wires）—— $why",
            wires.size,
            wires.distinct().size,
        )

        // 值域大小本身也是契约：加值请连 app / web 一起改，不要只改 core。
        assertEquals(
            "selection 的值域固定为 4 个（当前：$wires）—— $why",
            4,
            DeviceRuntime.Selection.entries.size,
        )
    }

    // ── probe 选型（阶段 5 的 5.2 / 5.3 / 5.4 新增） ────────────────────────

    @Test
    fun `配置为空时 probe 命中就选它并标 PROBED`() {
        val hit = FakePlugin("probed-one", "Probed One", FakeProfile("probed-profile", "Probed"), probeScore = 60)

        val rt = resolve("", candidates = listOf(hit))

        assertSame(hit, rt.plugin)
        assertEquals(DeviceRuntime.Selection.PROBED, rt.selection)
        // profile 只由排障开关决定，与「是配置选的还是探测选的」无关。
        assertSame(hit.profile(), rt.profile)
        assertEquals(emptyList<String>(), warns)
        assertEquals(1, infos.size)
        assertTrue("INFO 要写明选中了谁：${infos[0]}", infos[0].contains("probed-one"))
        assertTrue("INFO 要写明分数：${infos[0]}", infos[0].contains("60"))
        // 排障时最容易搞混的就是「这是探测结果还是我配的」，所以这句必须在。
        assertTrue("INFO 要说明这是探测结果不是配置：${infos[0]}", infos[0].contains("探测结果"))
        assertTrue("INFO 要说明这是探测结果不是配置：${infos[0]}", infos[0].contains("不是配置"))
    }

    @Test
    fun `多插件时取 probe 分数最高的那个`() {
        val low = FakePlugin("low", "Low", FakeProfile("low-profile", "Low"), probeScore = 10)
        val high = FakePlugin("high", "High", FakeProfile("high-profile", "High"), probeScore = 80)

        val lowFirst = resolve("", candidates = listOf(low, high))
        assertSame("高分在后也要被选中", high, lowFirst.plugin)
        assertEquals(DeviceRuntime.Selection.PROBED, lowFirst.selection)
        // 分数不同就不是并列，不许打那条并列 WARN。
        assertEquals(emptyList<String>(), warns)

        warns.clear()
        infos.clear()

        // 反过来再来一遍：证明选的是「最高分」，不是「第一个拿到正分的」。
        val highFirst = resolve("", candidates = listOf(high, low))
        assertSame(high, highFirst.plugin)
        assertEquals(DeviceRuntime.Selection.PROBED, highFirst.selection)
        assertEquals(emptyList<String>(), warns)
    }

    @Test
    fun `probe 并列同分取声明顺序靠前的那个并 WARN`() {
        val first = FakePlugin("tie-a", "Tie A", FakeProfile("tie-a-profile", "Tie A"), probeScore = 60)
        val second = FakePlugin("tie-b", "Tie B", FakeProfile("tie-b-profile", "Tie B"), probeScore = 60)

        val rt = resolve("", candidates = listOf(first, second))
        assertSame("并列取 plugins 里声明顺序靠前的那个", first, rt.plugin)
        assertEquals(DeviceRuntime.Selection.PROBED, rt.selection)
        assertEquals("并列必须留痕：只打一条 WARN", 1, warns.size)
        assertTrue("WARN 要列出并列的两个 id：${warns[0]}", warns[0].contains("tie-a"))
        assertTrue("WARN 要列出并列的两个 id：${warns[0]}", warns[0].contains("tie-b"))
        assertTrue("WARN 要写明并列分数：${warns[0]}", warns[0].contains("60"))

        warns.clear()
        infos.clear()

        // 把声明顺序倒过来，选中的也跟着倒过来 —— 证明裁决依据是**列表顺序**，
        // 不是 id 字典序、也不是「后来者覆盖」。
        val reversed = resolve("", candidates = listOf(second, first))
        assertSame(second, reversed.plugin)
        assertEquals(DeviceRuntime.Selection.PROBED, reversed.selection)
        assertEquals(1, warns.size)
    }

    @Test
    fun `某个插件的 probe 抛异常时按 0 分算且不中断整轮选型`() {
        val boom = FakePlugin("boom", "Boom", FakeProfile("boom-profile", "Boom"), probeThrows = true)
        val good = FakePlugin("good", "Good", FakeProfile("good-profile", "Good"), probeScore = 60)

        // 抛异常的那个排在前面：它若中断整轮，后面的 good 就永远选不上。
        val rt = resolve("", candidates = listOf(boom, good))

        assertSame("一个插件炸了不许影响其余插件参与选型", good, rt.plugin)
        assertEquals(DeviceRuntime.Selection.PROBED, rt.selection)
        assertEquals("抛异常的那个插件也要被调用过", 1, boom.probeCalls)
        assertEquals(1, good.probeCalls)
        assertEquals(1, warns.size)
        assertTrue("WARN 要带上出问题的插件 id：${warns[0]}", warns[0].contains("boom"))
        // 文案里**不许**带异常 message：AppLogger 的 repeatGate 按完整消息折叠，
        // 带上 message 会让折叠基数发散（同一条纪律见 ProbeEnvCollector 的类 KDoc 第 3 条）。
        assertFalse(
            "WARN 不许带异常 message（repeatGate 按完整消息折叠）：${warns[0]}",
            warns[0].contains(PROBE_BOOM_MESSAGE),
        )
    }

    @Test
    fun `全部插件 probe 都不大于 0 时回落 default 且是 DEFAULT 不是 FALLBACK`() {
        val zeroA = FakePlugin("zero-a", "Zero A", FakeProfile("zero-a-profile", "Zero A"))
        val zeroB = FakePlugin("zero-b", "Zero B", FakeProfile("zero-b-profile", "Zero B"))

        val rt = resolve("", candidates = listOf(zeroA, zeroB))

        // 兜底用的是 default 参数，不是候选列表里的某一个 —— 认不出设备不能导致整个不工作。
        assertSame(defaultPlugin, rt.plugin)
        assertSame(defaultProfile, rt.profile)
        assertEquals(DeviceRuntime.Selection.DEFAULT, rt.selection)
        // 这条分界是对外的：FALLBACK 的含义是「有人配错了字」，而这里没有任何配置。
        assertNotEquals(
            "probe 全 0 不是配置错误，不许报 FALLBACK",
            DeviceRuntime.Selection.FALLBACK,
            rt.selection,
        )
        assertEquals("两个插件都要被问过", 1, zeroA.probeCalls)
        assertEquals(1, zeroB.probeCalls)
        // 这条路径刻意不打日志（判据见 DeviceRuntime.resolve 的 KDoc 第 4 条最后一项）。
        assertEquals(emptyList<String>(), warns)
        assertEquals(emptyList<String>(), infos)
    }

    @Test
    fun `有配置时 probe 一次都不许被调用`() {
        // 打 999 分的插件：只要 probe 被调用过一次，它就会抢走选型结果。
        val eager = FakePlugin("eager", "Eager", FakeProfile("eager-profile", "Eager"), probeScore = 999)

        val hit = resolve("zte-f50", candidates = listOf(defaultPlugin, eager))
        assertSame("配置永远是最高优先级", defaultPlugin, hit.plugin)
        assertEquals(DeviceRuntime.Selection.CONFIGURED, hit.selection)
        assertEquals("配置命中时不许发 probe", 0, eager.probeCalls)
        assertEquals(0, defaultPlugin.probeCalls)

        warns.clear()
        infos.clear()

        // 配置填错也不许偷偷 probe：否则「我明明填了 X，跑起来却是 Y」会变成无法归因的现象。
        val fallback = resolve("nope-9000", candidates = listOf(defaultPlugin, eager))
        assertSame(defaultPlugin, fallback.plugin)
        assertEquals(DeviceRuntime.Selection.FALLBACK, fallback.selection)
        assertEquals("配置认不出时也不许发 probe", 0, eager.probeCalls)
        assertEquals(0, defaultPlugin.probeCalls)
    }

    @Test
    fun `排障开关关掉归一化时 probe 照样参与选型且那条 WARN 仍在最前`() {
        val hit = FakePlugin("probed-one", "Probed One", FakeProfile("probed-profile", "Probed"), probeScore = 60)

        val rt = resolve("", normalizationEnabled = false, candidates = listOf(hit))

        // 排障开关只管 profile 是否为 null，不许影响选型。
        assertNull(rt.profile)
        assertSame(hit, rt.plugin)
        assertSame(hit.profile(), rt.commandProfile)
        assertEquals(DeviceRuntime.Selection.PROBED, rt.selection)
        // 文案逐字不变，且**仍然是第一条** WARN（日志顺序不变）。
        assertEquals(
            listOf("字段归一化已关闭（排障开关），设备字段将原样透传，对外字段名会变回设备原名"),
            warns,
        )
        assertEquals(1, infos.size)
        assertTrue(infos[0].contains("probed-one"))
    }
}


