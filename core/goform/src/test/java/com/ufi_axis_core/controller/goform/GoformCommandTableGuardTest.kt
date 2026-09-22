package com.ufi_axis_core.controller.goform

import com.ufi_axis_core.deviceschema.DeviceProfile
import com.ufi_axis_core.deviceschema.FieldGroup
import com.ufi_axis_core.deviceschema.FieldSpec
import com.ufi_axis_core.deviceschema.SettingKey
import com.ufi_axis_core.deviceschema.WriteSpec
import com.ufi_axis_core.deviceschema.profile.ZteGoformProfile
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 守门测试：**客户端的 fallback 命令表与 `ZteGoformProfile.cmdsFor()` 必须一致**。
 *
 * ## 为什么需要这条测试（阶段 0.4a 的核心价值）
 *
 * 读命令表现在有**两份**：
 *
 * - 客户端里的 fallback（[GoformSignalClient.FALLBACK_CMDS]）——
 *   排障开关 `field_normalization_enabled=false` 时**它是唯一的命令来源**；
 * - profile 的 `cmdsFor(group)` —— 归一化开着时用的那份。
 *
 * 两份分叉的后果不是编译错误，而是**同一个端点在排障模式与正常模式下向设备发不同的 cmd**，
 * 于是响应差异会被误判成「设备变了」。0.4b 要把 `cmds()` 切到 `commandProfile` 并删掉 fallback，
 * 而「切过去不改变行为」的**前提**就是两份表逐字一致 —— 这条测试就是那个前提的看门人。
 *
 * ## 断言形状（照 `ZteGoformProfileTest` 的「重复必须是刻意的」白名单手法）
 *
 * ```
 * 实测不一致的组 == 已登记的例外集合
 * ```
 *
 * 双向相等，所以：① 新增分叉立刻红；② 已知那两处是**显式登记**而不是被掩盖；
 * ③ 例外被修好之后测试会红，提醒把它从集合里删掉 —— 删完仍绿就是 0.4b 可以安全删 fallback 的信号。
 */
class GoformCommandTableGuardTest {

    /**
     * 客户端侧 `WIFI_SETTINGS` 的命令表。
     *
     * 这一组**不走** `fields.cmds(group, fallback)`：`GoformWifiClient` 的
     * `getWifiSettings()`（12 个扁平字段）与 `getWifiModuleInfo()`（2 个容器命令）各自持有字面量，
     * 由 `getWifiSettingsMerged()` 合并后一起过 `WIFI_SETTINGS` 的归一化 —— 所以「客户端这一组
     * 实际会发的 cmd」就是这两份的并集，顺序与合并顺序一致（先扁平、后 module-info）。
     *
     * **这里是手抄的一份拷贝**：0.4a 的范围只允许把 `GoformSignalClient` 的 6 处 fallback 提成常量
     * （`GoformWifiClient` 本轮仅改 mapper 的构造调用），所以没法像那 6 组一样直接引用常量。
     * 把 `GoformWifiClient` 的两个字面量也提成 companion 常量、让本测试引用真身，是待办池里的一条
     * （手抄拷贝会过期；现在它至少被本测试与 `cmdsFor()` 双向钉住了）。
     */
    private val wifiSettingsClientCmds = listOf(
        // GoformWifiClient.getWifiSettings()
        "wifi_chip1_ssid1_ssid", "wifi_onoff_state", "wifi_access_sta_num",
        "wifi_chip1_ssid1_access_sta_num", "wifi_5g_enable", "wifi_enable",
        "wifi_chip1_ssid1_passphrase", "wifi_chip",
        "wifi_chip1_ssid1_auth_mode", "wifi_chip1_ssid1_encryp_type",
        "wifi_chip1_ssid1_max_sta_num", "wifi_chip1_ssid1_broadcast_ssid",
        // GoformWifiClient.getWifiModuleInfo()
        "queryWiFiModuleSwitch", "queryAccessPointInfo",
    )

    /** 参与比对的全部分组：6 处 `fields.cmds` 的 fallback + WiFi 那一组的并集。 */
    private val clientCmdTables: Map<FieldGroup, List<String>> =
        GoformSignalClient.FALLBACK_CMDS + mapOf(FieldGroup.WIFI_SETTINGS to wifiSettingsClientCmds)

    /**
     * **已知不一致的组 —— 唯一允许的例外，移除条件必须写清。**
     *
     * `CELL_INFO`（待办池 P0-3）：客户端 fallback 末项是小写 `lte_snr`，
     * `ZteGoformProfile.cmdsFor(CELL_INFO)` 是大写 `Lte_snr`。
     *
     * - **已确认的事实**：`ZteGoformProfile.kt` 的 SIGNAL 段注释断言设备原名是 `Lte_snr`
     *   （`lte_snr` 是 core 自有的小写 canonical，看着像设备原名而已）；
     *   `CELL_INFO` 的 `LTE_SNR` 别名链两个名字都登记了（`"lte_snr", "Lte_snr"`），
     *   所以**读侧解析**两种大小写都能命中，这一处分叉今天不会造成功能缺陷。
     * - **未证的猜测**：设备对 **cmd 名**是否大小写敏感。
     * - 移除条件：真机用 `POST /api/device/goform/query` 分别发 `lte_snr` 与 `Lte_snr` 各一次定性，
     *   再统一成设备真正接受的那个。**定性之前不许统一**（用户裁决：保持原有 goform 命令不变）。
     *   统一之后本测试会红 —— 那时把这一项从集合里删掉，测试仍绿就是「0.4b 可以安全删 fallback」
     *   的信号。
     *
     * `WIFI_SETTINGS` **曾经在这个集合里**（`cmdsFor()` 把响应键 `WiFiModuleSwitch` 当成 cmd、
     * 少 5 个真 cmd），同一轮由另一个代理在 `core/device-schema` 侧修好了，实测已逐字一致，
     * 因此不再登记为例外 —— 它现在由「逐字一致的那几组」那条断言看着。
     */
    private val knownDivergentGroups = setOf(FieldGroup.CELL_INFO)

    // ───────────────────────── 1. 两份表的一致性 ─────────────────────────

    /**
     * 比对清单本身要被钉住：新加一处 `fields.cmds(group, fallback)` 却忘了登记进
     * `FALLBACK_CMDS`，那一组的分叉就不会被任何测试发现。
     */
    @Test
    fun `参与比对的分组清单必须是登记过的那 7 组`() {
        assertEquals(
            "新增/删除 fields.cmds 调用点时必须同步 GoformSignalClient.FALLBACK_CMDS",
            setOf(
                FieldGroup.IDENTITY,
                FieldGroup.CELL_INFO,
                FieldGroup.LAN_SETTINGS,
                FieldGroup.DEVICE_SETTINGS,
                FieldGroup.BAND_STATUS,
                FieldGroup.TRAFFIC_LIMIT,
            ),
            GoformSignalClient.FALLBACK_CMDS.keys,
        )
        assertEquals(7, clientCmdTables.size)
    }

    @Test
    fun `实测不一致的组必须恰好等于已登记的例外`() {
        val divergent = clientCmdTables
            .filter { (group, clientCmds) -> clientCmds != ZteGoformProfile.cmdsFor(group) }
            .keys
        assertEquals(
            "客户端 fallback 与 cmdsFor() 出现了未登记的分叉（或已登记的那处已修好）—— " +
                "分叉的后果是排障模式与正常模式向设备发不同的 cmd，见本类注释",
            knownDivergentGroups,
            divergent,
        )
    }

    @Test
    fun `逐字一致的那几组必须继续逐字一致`() {
        // 不用「不在例外集合里就跳过」的写法：逐组给出可读的差异，红的时候一眼看出是哪一组
        for ((group, clientCmds) in clientCmdTables) {
            if (group in knownDivergentGroups) continue
            assertEquals("$group 的客户端 fallback 与 cmdsFor() 不一致", ZteGoformProfile.cmdsFor(group), clientCmds)
        }
    }

    /**
     * 把 `CELL_INFO` 的分叉**钉到字符级**：只许差这一个大小写。
     *
     * 只登记「这一组不一致」是不够的 —— 那样在这一组里再抄错一个 cmd 名也不会红。
     */
    @Test
    fun `CELL_INFO 的分叉只许是 lte_snr 的大小写`() {
        val client = GoformSignalClient.CELL_INFO_FALLBACK_CMDS
        val profile = ZteGoformProfile.cmdsFor(FieldGroup.CELL_INFO)
        assertEquals("lte_snr", client.last())
        assertEquals("Lte_snr", profile.last())
        assertEquals(
            "CELL_INFO 除 lte_snr/Lte_snr 的大小写之外不许有别的差异",
            profile,
            client.dropLast(1) + "Lte_snr",
        )
    }

    // ───────────────── 2. 关掉归一化不会把只读面打瘫 ─────────────────

    /**
     * 排障开关关掉归一化（`normalizeProfile = null`）时：
     * 诊断口径如实反映「已关」，但 `cmds()` 仍然返回非空的 fallback。
     *
     * 这就是计划书 §4 那段「不要直接删 fallback」的可执行版本 ——
     * 删了 fallback 这条测试会直接红（cmds 返回空 = 一条查询都发不出去）。
     */
    @Test
    fun `关掉归一化时 cmds 仍然返回 fallback`() {
        val mapper = GoformFieldMapper(normalizeProfile = null, commandProfile = ZteGoformProfile)
        assertFalse("normalizeProfile=null 就是「归一化已关」", mapper.enabled)
        assertNull("profileId 必须跟着报 null，否则 /api/diagnose 会永远报 true", mapper.profileId)
        for ((group, fallback) in GoformSignalClient.FALLBACK_CMDS) {
            val cmds = mapper.cmds(group, fallback)
            assertTrue("$group 关归一化后拿不到 cmd = 该端点直接哑掉", cmds.isNotEmpty())
            assertEquals("$group 关归一化后必须原样用客户端 fallback", fallback, cmds)
        }
    }

    /**
     * 归一化开着时 `cmds()` 取 profile 的登记表 —— 本轮**取值行为一字不变**。
     *
     * `CELL_INFO` 这一组特意断言拿到的是**大写** `Lte_snr`：这正是「现在就把 cmds() 切到
     * commandProfile 也不会被发现」的那处静默差异，钉在这里，0.4b 动手时必须正面处理它。
     */
    @Test
    fun `开着归一化时 cmds 取 profile 的登记表`() {
        val mapper = GoformFieldMapper(
            normalizeProfile = ZteGoformProfile,
            commandProfile = ZteGoformProfile,
        )
        for ((group, fallback) in GoformSignalClient.FALLBACK_CMDS) {
            assertEquals(ZteGoformProfile.cmdsFor(group), mapper.cmds(group, fallback))
        }
        assertEquals(
            "Lte_snr",
            mapper.cmds(FieldGroup.CELL_INFO, GoformSignalClient.CELL_INFO_FALLBACK_CMDS).last(),
        )
    }

    /**
     * `commandProfile` 本轮**不参与** `cmds()` 的取值 —— 0.4a 是纯结构准备，零行为变化。
     *
     * 传一个能被识别出来的假 profile 进 `commandProfile`：它的 cmd 一次都不该出现在结果里。
     */
    @Test
    fun `commandProfile 本轮不参与 cmds 的取值`() {
        val marker = MarkerProfile()
        val withNormalize = GoformFieldMapper(normalizeProfile = ZteGoformProfile, commandProfile = marker)
        val withoutNormalize = GoformFieldMapper(normalizeProfile = null, commandProfile = marker)
        val fallback = GoformSignalClient.BAND_STATUS_FALLBACK_CMDS
        assertEquals(
            ZteGoformProfile.cmdsFor(FieldGroup.BAND_STATUS),
            withNormalize.cmds(FieldGroup.BAND_STATUS, fallback),
        )
        assertEquals(fallback, withoutNormalize.cmds(FieldGroup.BAND_STATUS, fallback))
        assertFalse(
            "commandProfile 的 cmd 出现在结果里 = cmds() 已经切过去了，那是 0.4b 的事",
            MarkerProfile.MARKER_CMD in withoutNormalize.cmds(FieldGroup.BAND_STATUS, fallback),
        )
    }

    /**
     * 关掉归一化时 `coverageReport()` 的短路必须保留：**一条查询都不许发**。
     *
     * 这个方法会逐组向设备发查询，改成拿 `commandProfile` 兜底的话，排障模式下
     * `/api/diagnose?fields=1` 会开始真打设备 —— 那是行为变更（今天它只回一句 hint）。
     */
    @Test
    fun `关掉归一化时 coverageReport 不向设备发查询`() {
        val mapper = GoformFieldMapper(normalizeProfile = null, commandProfile = ZteGoformProfile)
        var queries = 0
        val report: JsonObject = runBlocking {
            mapper.coverageReport { queries++; null }
        }
        assertEquals("关归一化时 coverageReport 必须短路，不能向设备发查询", 0, queries)
        assertEquals("false", report["normalization_enabled"]?.jsonPrimitive?.content)
        assertTrue("短路时要给出原因，不能回一个空壳", report.containsKey("hint"))
        assertFalse("短路时不该出现逐组结果", report.containsKey("groups"))
    }

    /**
     * 只为「区分两个 profile」而存在的假 profile：`cmdsFor` 一律返回一个不可能来自真设备的 cmd。
     * 其余成员都是 [DeviceProfile] 的必填项，按最小实现填。
     */
    private class MarkerProfile : DeviceProfile {
        override val id: String = "marker-profile"
        override val displayName: String = "仅用于测试的标记 profile"
        override fun readSpecs(): List<FieldSpec> = emptyList()
        override fun cmdsFor(group: FieldGroup): List<String> = listOf(MARKER_CMD)
        override fun writeSpec(key: SettingKey): WriteSpec? = null

        companion object {
            const val MARKER_CMD = "__marker_cmd__"
        }
    }
}
