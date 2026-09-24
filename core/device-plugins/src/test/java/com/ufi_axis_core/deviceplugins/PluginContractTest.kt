package com.ufi_axis_core.deviceplugins

import com.ufi_axis_core.contract.Capability
import com.ufi_axis_core.deviceschema.SettingKey
import com.ufi_axis_core.devicespi.BuildInfo
import com.ufi_axis_core.devicespi.DevicePlugin
import com.ufi_axis_core.devicespi.ProbeEnv
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 插件契约守门测试（计划书 §6 的 2.7 + §7 的 3.7）—— 对 [PluginRegistry.ALL] 里的**每一个**
 * 插件逐条查。
 *
 * 守的是「加第二台设备时容易悄悄写错、而且写错了别的测试不会红」的那几条：
 * 注册表自洽（id 唯一、[PluginRegistry.DEFAULT] 真的在 [PluginRegistry.ALL] 里）、
 * `profile()` 不在每次调用时新建对象、`tuning()` 的阈值没填反、`probe()` 是纯函数，
 * 以及 `capabilities` **声明了就必须真能做到**。
 *
 * ## 能力集这一段只做**单向**断言（2026-09-24 用户拍板）
 *
 * 断言方向是：**声明了某个 [Capability] → 必须能在该插件的 profile 里找到对应的写能力**
 * （`WriteSpec` 或 `smsSpec()`）。防的是「定了不用 / 定了做不到」。
 *
 * **不加反向断言**（「有 `WriteSpec` 就必须有 capability」）：计划书 §6 的 2.7 原来那句
 * 已经作废 —— Capability 是**功能域**（10 个），`SettingKey` 是**写入项**（29 个），
 * 照反向断言写出来一上线必红，而且它逼着人给每个 key 编一个域，
 * 那才是真正把能力集变成垃圾桶的做法。没有 capability 的写操作照旧不拦。
 *
 * ## 本批仍然刻意**不测**的一条
 *
 * **`createTransport()` 不在单测里调** —— 它会 `new GoformClient`，而那个构造一上来就起
 * Ktor client、`AppLogger` 又依赖 `android.util.Log`（同一个坑记在
 * `GoformBase64CharsetTest` 的文件头）。JVM 单测里调它只会拿到一条
 * `RuntimeException: Stub!` —— 与「插件契约对不对」毫无关系的失败。
 * 这一条靠装配层的真机冒烟覆盖（`ComponentFactory.buildNetworkGraph` 造完就要连设备）。
 *
 * 「每个 Capability 至少被一处 route 门禁引用」那一条在 `:core:api` 的
 * `CapabilityGateTest`（route 源码在那个模块，这里看不见）。
 *
 * 旧注册表 `DeviceProfiles` 那一侧的同类断言在 `ProfileContractTest`
 * （`:core:device-schema`），它随 2.6 收尾一起删。
 */
class PluginContractTest {

    private val plugins: List<DevicePlugin> = PluginRegistry.ALL

    /**
     * 「声明了这个域 → profile 里至少要有这里列的 `SettingKey` 之一」。
     *
     * 为什么是 `List`（任一成立即可）而不是单个 key：有的域天生对应多条设备命令
     * （频段锁定分 LTE / NR，基站锁定分 lock / unlock），而「这台设备有没有 NR 频段」
     * 不该由能力集回答。要求全部命中会把「只有 LTE 的设备」判成不支持频段锁定。
     *
     * [Capability.SMS] **不在这张表里**：短信不走 `SettingKey` + `WriteSpec`
     * （计划书 §11.2），它的判据是 `profile.smsSpec() != null`，单独一条用例。
     */
    private val writeKeysOf: Map<Capability, List<SettingKey>> = mapOf(
        Capability.SIM_SLOT_SWITCH to listOf(SettingKey.SIM_SLOT),
        Capability.BAND_LOCK to listOf(SettingKey.BAND_LOCK_LTE, SettingKey.BAND_LOCK_NR),
        Capability.CELL_LOCK to listOf(SettingKey.CELL_LOCK, SettingKey.CELL_UNLOCK),
        Capability.NETWORK_MODE to listOf(SettingKey.NETWORK_MODE),
        Capability.SAMBA to listOf(SettingKey.SAMBA),
        Capability.USB_DEBUG to listOf(SettingKey.USB_PORT),
        Capability.FOTA to listOf(SettingKey.FOTA_AUTO_UPDATE),
        Capability.PERFORMANCE_MODE to listOf(SettingKey.PERFORMANCE_MODE),
        Capability.TRAFFIC_LIMIT to listOf(SettingKey.TRAFFIC_LIMIT),
    )

    /** 探测指纹的最小样例；单测只关心 `probe()` 读它的方式，字段取值本身不是契约。 */
    private fun probeEnv(
        goformLdReachable: Boolean,
        cpuInfoPlatform: String? = "sprd-ums9620",
    ) = ProbeEnv(
        cpuInfoPlatform = cpuInfoPlatform,
        androidBuild = BuildInfo(
            brand = "brand",
            model = "model",
            device = "device",
            manufacturer = "manufacturer",
            sdkInt = 33,
        ),
        goformLdReachable = goformLdReachable,
    )

    // ───────────────────────── 注册表 ─────────────────────────

    @Test
    fun `注册表非空`() {
        // 空注册表 = 装配层永远拿不到插件，后端起不来；这条是所有其它断言的前提。
        assertTrue("PluginRegistry.ALL 不能为空", plugins.isNotEmpty())
    }

    @Test
    fun `每个插件的 id 与 displayName 非空且 id 全局唯一`() {
        plugins.forEach { plugin ->
            assertTrue("插件 id 不能为空", plugin.id.isNotEmpty())
            assertTrue("插件 id 去空白后不能为空：'${plugin.id}'", plugin.id.trim().isNotEmpty())
            assertTrue("${plugin.id} 的 displayName 不能为空", plugin.displayName.isNotEmpty())
            assertTrue(
                "${plugin.id} 的 displayName 去空白后不能为空",
                plugin.displayName.trim().isNotEmpty(),
            )
        }
        // id 进日志、进配置项（device_profile_id），重复就意味着 byId() 的结果取决于登记顺序
        val ids = plugins.map { it.id }
        assertEquals("插件 id 不能重复", ids.distinct(), ids)
    }

    @Test
    fun `DEFAULT 必须是 ALL 里的那个对象而不是另 new 的`() {
        // 用 identity 判定：另 new 一个等值对象也能过 equals，但那样「默认插件」与
        // 「注册表里的同名插件」就是两个实例，装配层按 DEFAULT 造的东西与按 ALL 选的不是一回事。
        assertTrue(
            "PluginRegistry.DEFAULT 必须是 ALL 里的元素",
            plugins.any { it === PluginRegistry.DEFAULT },
        )
    }

    @Test
    fun `byId 对每个已登记 id 返回同一个对象，未登记 id 返回 null`() {
        plugins.forEach { plugin ->
            assertSame("byId(${plugin.id}) 必须返回注册表里的那个对象", plugin, PluginRegistry.byId(plugin.id))
        }
        // 认不出时返回 null，由调用方决定兜底并打 WARN（DeviceRuntime.resolve → FALLBACK）
        assertNull(PluginRegistry.byId("acme-rest"))
        assertNull(PluginRegistry.byId(""))
    }

    // ───────────────────────── profile ─────────────────────────

    @Test
    fun `profile 的 id 非空且多次调用返回同一个对象`() {
        plugins.forEach { plugin ->
            val first = plugin.profile()
            assertTrue("${plugin.id} 的 profile id 不能为空", first.id.trim().isNotEmpty())
            // 插件是 object、profile 也是 object —— 这条钉住「别在 profile() 里 new」：
            // 每次 new 一份的话，装配层拿到的 profile 与 `DeviceRuntime.commandProfile` 兜的
            // 就不是同一个对象，任何按 identity 比较 profile 的断言/缓存都会静默失效。
            assertSame("${plugin.id}.profile() 每次都必须返回同一个对象", first, plugin.profile())
        }
    }

    // ───────────────────────── tuning ─────────────────────────

    @Test
    fun `tuning 的值域自洽`() {
        plugins.forEach { plugin ->
            val t = plugin.tuning()
            // 防的是「换设备时把两个温控阈值填反」：warn >= critical 会让告警永远先按 critical 判
            assertTrue(
                "${plugin.id}: thermalWarnC(${t.thermalWarnC}) 必须小于 thermalCriticalC(${t.thermalCriticalC})",
                t.thermalWarnC < t.thermalCriticalC,
            )
            // 零回差 + 边沿触发 = 阈值附近微抖导致的告警风暴（判据见 DeviceTuning.thermalJitterC）
            assertTrue("${plugin.id}: thermalJitterC 必须为正", t.thermalJitterC > 0f)
            // 预热期 <= 0 等于没有预热期，开机瞬间的抖动会直接入库并判告警
            assertTrue("${plugin.id}: bootGraceMs 必须为正", t.bootGraceMs > 0L)
            // 许可数 <= 0 会让所有 root shell 直接排队到死
            assertTrue("${plugin.id}: rootShellPermits 必须为正", t.rootShellPermits > 0)
        }
    }

    // ───────────────────────── capabilities ─────────────────────────

    @Test
    fun `每个插件的 capabilities 非空且多次读返回同一个集合`() {
        plugins.forEach { plugin ->
            // 空能力集 = 这台设备上 10 个域全部回 501、两端全部灰掉。
            // 真要有这种设备，那它就不该作为插件登记进来。
            assertTrue(
                "${plugin.id} 的 capabilities 不能为空（空 = 10 个功能域全部回 501）",
                plugin.capabilities.isNotEmpty(),
            )
            // 与 profile() 那条同一个判据：别在 getter 里 new。
            // 每次 new 一份的话，装配层递给 DataHub 的那份与后来读到的就不是同一个对象，
            // 「运行期不变」这条约束会静默失效。
            assertSame(
                "${plugin.id}.capabilities 每次都必须返回同一个集合",
                plugin.capabilities,
                plugin.capabilities,
            )
        }
    }

    @Test
    fun `声明了某个 capability 就必须能在 profile 里找到对应的写能力`() {
        plugins.forEach { plugin ->
            val profile = plugin.profile()
            plugin.capabilities.forEach { cap ->
                if (cap == Capability.SMS) {
                    // 短信不走 SettingKey（§11.2），判据是 smsSpec()
                    assertTrue(
                        "${plugin.id} 声明了 ${cap.wire}，但 profile.smsSpec() 是 null —— " +
                            "声明的能力必须真能做到，否则就是「开关能点、点了没反应」",
                        profile.smsSpec() != null,
                    )
                    return@forEach
                }
                val keys = writeKeysOf[cap]
                assertTrue(
                    "对照表 writeKeysOf 缺少 ${cap.wire} —— 新增 Capability 时要把它的写侧判据补进来",
                    keys != null,
                )
                assertTrue(
                    "${plugin.id} 声明了 ${cap.wire}，但 profile(${profile.id}) 里 " +
                        "${keys!!.joinToString { it.name }} 一条 WriteSpec 都没有登记",
                    keys.any { profile.writeSpec(it) != null },
                )
            }
        }
    }

    @Test
    fun `对照表覆盖 Capability 的全部取值`() {
        // 这条是给「将来加第 11 个 Capability」的人准备的：
        // 不补对照表，上面那条用例只会在**恰好有插件声明了新域**时才红 ——
        // 也就是说漏补可能几个月都不暴露。这里让它立刻红。
        val covered = writeKeysOf.keys + Capability.SMS
        assertEquals(
            "Capability 与对照表必须逐项对齐（缺：${(Capability.entries - covered).map { it.wire }}）",
            Capability.entries.toSet(),
            covered,
        )
    }

    // ───────────────────────── probe ─────────────────────────

    @Test
    fun `goform 后台不可达时 probe 必须返回 0`() = runBlocking {
        // 0 = 不适用。全部插件都 0 时由 PluginRegistry.DEFAULT 兜底，
        // 所以返回 0 不会导致「认不出设备 = 整个不工作」。
        plugins.forEach { plugin ->
            assertEquals(
                "${plugin.id}: goformLdReachable=false 必须返回 0（不适用）",
                0,
                plugin.probe(probeEnv(goformLdReachable = false)),
            )
        }
    }

    @Test
    fun `同一个 ProbeEnv 多次调用 probe 返回值相同`() = runBlocking {
        // probe() 的硬约束是「只读 ProbeEnv、零 I/O」，所以它必须是纯函数。
        // 不稳定的打分会让选型结果随调用次数变化，且这种 bug 在真机上极难归因。
        plugins.forEach { plugin ->
            val env = probeEnv(goformLdReachable = true)
            val first = plugin.probe(env)
            assertEquals("${plugin.id}: probe 对同一个 env 必须返回同一个分数", first, plugin.probe(env))

            val envNoPlatform = probeEnv(goformLdReachable = true, cpuInfoPlatform = null)
            val firstNoPlatform = plugin.probe(envNoPlatform)
            assertEquals(
                "${plugin.id}: probe 对同一个 env（平台串缺失）必须返回同一个分数",
                firstNoPlatform,
                plugin.probe(envNoPlatform),
            )
        }
    }
}
