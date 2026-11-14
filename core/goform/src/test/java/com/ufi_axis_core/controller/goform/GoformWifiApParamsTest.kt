package com.ufi_axis_core.controller.goform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WiFi 热点三个写入口的「current + 入参 → canonical params」合并逻辑。
 *
 * ## 为什么值得逐字断言
 *
 * 设备侧 `setAccessPointInfo` 是**整表替换**：多放一个键、少放一个键，都会改掉 AP 的某一项配置。
 * 仓库已经为此出过两次事故（改 SSID 把口令写成明文串、"改配置不传口令" 把口令清空）。
 * 三个入口（改整份配置 / 只改 SSID / 只改口令）的差异**全部**体现在放哪几个键上，
 * 所以这里断言的是 canonical params 的**完整 map**，不是"包含某个键"。
 *
 * ## 边界只到 canonical params
 *
 * 再往下（canonical → 设备键、base64、`OPEN` 强制 `NONE`、`ApIsolate` / `AccessPointIndex`
 * 两个固定值）是 `SettingKey.WIFI_AP_CONFIG` 的 encode 的职责，由 device-schema 侧的
 * `ZteGoformProfileTest` 覆盖。本测试**刻意不依赖 profile**：合并逻辑与编码规则各自独立可测，
 * 混在一起时一条断言失败会指不出是哪一层错了。
 *
 * canonical 侧的约定（测试与实现共用的同一份事实）：
 * - 键不放 == 放 null → 不发对应设备键；放**空串**不等价（空串会照发）
 * - `passphrase` 只要键在就会发 `Password`，所以"不想动口令"必须**不放**这个键
 */
class GoformWifiApParamsTest {

    /** 读回成功、每一项都有值的设备现状。`ApMaxStationNumber` 故意给了值 —— 三个入口都不该用它。 */
    private val fullCurrent = mapOf(
        "AuthMode" to "WPA2PSK",
        "EncrypType" to "CCMP",
        "SSID" to "UFI-AXIS",
        // getCurrentWifiConfig 返回的 Password 已经是**明文**（含非 ASCII，验证不做二次编码）
        "Password" to "口令-pw1",
        "ChipIndex" to "0",
        "ApMaxStationNumber" to "10",
        "ApBroadcastDisabled" to "1",
    )

    /** 开放热点（无口令）的设备现状。 */
    private val openCurrent = mapOf(
        "AuthMode" to "OPEN",
        // 设备在 OPEN 下**仍然会回**一个非 NONE 的 EncrypType，这就是那处刻意行为变更的输入
        "EncrypType" to "CCMP",
        "SSID" to "UFI-OPEN",
        "ChipIndex" to "0",
        "ApBroadcastDisabled" to "0",
    )

    private fun config(
        current: Map<String, String>,
        ssid: String? = null,
        authMode: String? = null,
        encrypType: String? = null,
        passphrase: String? = null,
        maxStaNum: Int? = null,
        broadcastDisabled: Int? = null,
        chipIndex: String? = null,
    ): Map<String, Any?> = GoformWifiClient.mergeApConfigParams(
        current = current,
        ssid = ssid,
        authMode = authMode,
        encrypType = encrypType,
        passphrase = passphrase,
        maxStaNum = maxStaNum,
        broadcastDisabled = broadcastDisabled,
        chipIndex = chipIndex,
    )

    // ───────────── setWifiConfig ─────────────

    @Test
    fun `setWifiConfig 只给 SSID 时其余全部沿用读回的当前值`() {
        // 这是最危险的一条路径：不传口令改配置。passphrase 必须是 current 的**明文**原值，
        // 既不能缺（缺了设备侧口令会被清空）也不能是解码后的垃圾。
        assertEquals(
            mapOf(
                "ssid" to "new-ssid",
                "auth_mode" to "WPA2PSK",
                "encrypt_type" to "CCMP",
                "passphrase" to "口令-pw1",
                "broadcast_disabled" to 1,
                "chip_index" to "0",
            ),
            // SSID 两侧的空格由调用方 trim（profile 不猜）
            config(fullCurrent, ssid = "  new-ssid  ")
        )
    }

    @Test
    fun `setWifiConfig 入参齐全时逐项照发`() {
        assertEquals(
            mapOf(
                "ssid" to "s1",
                "auth_mode" to "WPA2PSK",
                "encrypt_type" to "TKIP",
                "passphrase" to "pw-new",
                "max_sta_num" to 8,
                "broadcast_disabled" to 0,
                "chip_index" to "1",
            ),
            config(
                current = emptyMap(),
                ssid = "s1",
                authMode = "WPA2PSK",
                encrypType = "TKIP",
                passphrase = "pw-new",
                maxStaNum = 8,
                broadcastDisabled = 0,
                chipIndex = "1",
            )
        )
    }

    @Test
    fun `setWifiConfig 读回为空时只留能算出来的键`() {
        // current 为空（设备没回 / 查询失败）：auth、encryp、chip、ssid 都放不出来，
        // 走 profile 缺省。broadcast_disabled 是唯一"算不出也要发"的键，缺省 0。
        assertEquals(
            mapOf("broadcast_disabled" to 0),
            config(emptyMap())
        )
    }

    @Test
    fun `setWifiConfig 在 authMode 与 ssid 都给了时不读回 所以不带口令`() {
        // 这条路径 current 恒为 emptyMap（调用方不查询），所以没有口令可继承 ——
        // passphrase 键不放，设备侧口令保持原值。
        val params = config(
            current = emptyMap(),
            ssid = "both-given",
            authMode = "WPA2PSK",
        )
        assertFalse("不读回时不该凭空造出 passphrase", params.containsKey("passphrase"))
        assertEquals(
            mapOf(
                "ssid" to "both-given",
                "auth_mode" to "WPA2PSK",
                "broadcast_disabled" to 0,
            ),
            params
        )
    }

    @Test
    fun `setWifiConfig 入参 OPEN 时不放口令`() {
        val params = config(fullCurrent, authMode = "OPEN")
        assertFalse(params.containsKey("passphrase"))
        assertEquals(
            // encrypt_type 照放 current 原值 —— OPEN 时强制 NONE 是 profile 的职责，不在这里判
            mapOf(
                "ssid" to "UFI-AXIS",
                "auth_mode" to "OPEN",
                "encrypt_type" to "CCMP",
                "broadcast_disabled" to 1,
                "chip_index" to "0",
            ),
            params
        )
    }

    @Test
    fun `setWifiConfig 读回到 OPEN 时不放口令`() {
        // 判据是「最终会生效的值」：auth 来自 current 也算 OPEN。
        val params = config(openCurrent, ssid = "x")
        assertFalse(params.containsKey("passphrase"))
    }

    @Test
    fun `setWifiConfig 入参 encryp NONE 时不放口令`() {
        // effectiveEncryp == NONE 同样拦住口令（原实现的第二个条件，逐字保住）
        assertFalse(config(fullCurrent, encrypType = "NONE").containsKey("passphrase"))
    }

    @Test
    fun `setWifiConfig 当前无口令时不放 passphrase 键`() {
        // 无密码设备：放 null 与不放等价，必须落到"不放"——
        // 放一个空串会被 profile 照发成 Password=（空口令），那是改坏配置
        val noPwd = fullCurrent - "Password"
        assertFalse(config(noPwd, ssid = "x").containsKey("passphrase"))
    }

    @Test
    fun `setWifiConfig 的 broadcast_disabled 优先入参 再读回 最后缺省 0`() {
        assertEquals(0, config(fullCurrent, ssid = "x", broadcastDisabled = 0)["broadcast_disabled"])
        assertEquals(1, config(fullCurrent, ssid = "x")["broadcast_disabled"])
        assertEquals(0, config(fullCurrent - "ApBroadcastDisabled", ssid = "x")["broadcast_disabled"])
    }

    // ───────────── setWifiSSID ─────────────

    @Test
    fun `setWifiSSID 只换 SSID 其余沿用读回值`() {
        assertEquals(
            mapOf(
                "ssid" to "renamed",
                "auth_mode" to "WPA2PSK",
                "encrypt_type" to "CCMP",
                "passphrase" to "口令-pw1",
                "broadcast_disabled" to 1,
                "chip_index" to "0",
            ),
            GoformWifiClient.mergeApSsidParams(fullCurrent, "  renamed  ")
        )
    }

    @Test
    fun `setWifiSSID 读回为空时只发 SSID`() {
        // 其余项一个都放不出来 → 全走 profile 缺省。这里**不放** broadcast_disabled：
        // 改造前这个入口发的是 current 值或 "0"，profile 缺省同样是 "0"，报文不变。
        assertEquals(
            mapOf<String, Any?>("ssid" to "only"),
            GoformWifiClient.mergeApSsidParams(emptyMap(), "only")
        )
    }

    @Test
    fun `setWifiSSID 在当前是 OPEN 时不放口令`() {
        val params = GoformWifiClient.mergeApSsidParams(openCurrent, "open-renamed")
        assertFalse("OPEN 热点没有口令可带", params.containsKey("passphrase"))
        assertEquals(
            // encrypt_type 仍然照发 current 的 "CCMP"：2026-09-21 用户裁决（方案 A）由 profile
            // 把 OPEN 强制成 NONE，这是本次唯一一处刻意的设备侧报文差异
            mapOf(
                "ssid" to "open-renamed",
                "auth_mode" to "OPEN",
                "encrypt_type" to "CCMP",
                "broadcast_disabled" to 0,
                "chip_index" to "0",
            ),
            params
        )
    }

    @Test
    fun `setWifiSSID 当前无口令时不放 passphrase 键`() {
        val noPwd = fullCurrent - "Password"
        assertFalse(GoformWifiClient.mergeApSsidParams(noPwd, "x").containsKey("passphrase"))
    }

    // ───────────── setWifiPassword ─────────────

    @Test
    fun `setWifiPassword 只换口令 其余沿用读回值`() {
        assertEquals(
            mapOf(
                "ssid" to "UFI-AXIS",
                "auth_mode" to "WPA2PSK",
                "encrypt_type" to "CCMP",
                "passphrase" to "brand-new-pw",
                "broadcast_disabled" to 1,
                "chip_index" to "0",
            ),
            GoformWifiClient.mergeApPasswordParams(fullCurrent, "brand-new-pw")
        )
    }

    @Test
    fun `setWifiPassword 读回为空时只发口令`() {
        assertEquals(
            mapOf<String, Any?>("passphrase" to "pw"),
            GoformWifiClient.mergeApPasswordParams(emptyMap(), "pw")
        )
    }

    @Test
    fun `setWifiPassword 在 OPEN 下照样发口令`() {
        // 与另两个入口刻意不同：用户点的就是"改口令"，不能因为当前是开放热点就把他输入的值丢掉。
        // 这正是"口令条件不能搬进 profile encode"的原因。
        val params = GoformWifiClient.mergeApPasswordParams(openCurrent, "pw-on-open")
        assertEquals("pw-on-open", params["passphrase"])
    }

    @Test
    fun `setWifiPassword 传空串也照发 与不放键不等价`() {
        // 清空口令是这个入口的合法动作，空串必须落到 params 里（profile 会发 Password=）
        val params = GoformWifiClient.mergeApPasswordParams(fullCurrent, "")
        assertTrue(params.containsKey("passphrase"))
        assertEquals("", params["passphrase"])
    }

    // ───────────── 三个入口共同的边界 ─────────────

    @Test
    fun `三个入口都不放设备侧键与固定值`() {
        // isTest / goformId 由 GoformCodec 与 writer 统一补，ApIsolate / AccessPointIndex 在
        // profile 的 encode 里恒发。canonical params 里出现任何一个都说明这一层又混进了设备知识。
        val forbidden = listOf(
            "isTest", "goformId", "ApIsolate", "AccessPointIndex",
            "SSID", "AuthMode", "EncrypType", "Password", "ChipIndex",
            "ApMaxStationNumber", "ApBroadcastDisabled",
        )
        val all = listOf(
            config(fullCurrent, ssid = "x", maxStaNum = 4),
            GoformWifiClient.mergeApSsidParams(fullCurrent, "x"),
            GoformWifiClient.mergeApPasswordParams(fullCurrent, "x"),
        )
        for (params in all) {
            for (key in forbidden) {
                assertFalse("$key 不该出现在 canonical params 里：$params", params.containsKey(key))
            }
        }
    }

    @Test
    fun `max_sta_num 只在 setWifiConfig 且入参非 null 时出现`() {
        assertEquals(4, config(fullCurrent, ssid = "x", maxStaNum = 4)["max_sta_num"])
        assertFalse(config(fullCurrent, ssid = "x").containsKey("max_sta_num"))
        // 另两个入口即使 current 里有 ApMaxStationNumber 也不发 —— 与改造前一致
        assertFalse(GoformWifiClient.mergeApSsidParams(fullCurrent, "x").containsKey("max_sta_num"))
        assertFalse(GoformWifiClient.mergeApPasswordParams(fullCurrent, "x").containsKey("max_sta_num"))
    }
}
