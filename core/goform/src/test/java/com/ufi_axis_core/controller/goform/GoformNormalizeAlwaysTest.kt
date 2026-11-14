package com.ufi_axis_core.controller.goform

import com.ufi_axis_core.deviceschema.FieldGroup
import com.ufi_axis_core.deviceschema.FieldNormalizer
import com.ufi_axis_core.deviceschema.profile.ZteGoformProfile
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 守门测试：排障开关（`field_normalization_enabled=false`）下 **TRAFFIC_LIMIT 仍然归一化，
 * 其余组仍然原样透传**。
 *
 * ## 这里在钉什么
 *
 * `GoformFieldMapper.NORMALIZE_ALWAYS` 给闸门开了一个口子。口子有两个方向会坏：
 *
 * 1. **口子没生效** → 关掉归一化时 TRAFFIC_LIMIT 退回设备原名，于是
 *    `TrafficLimitMapper` 全填默认值（`limit_bytes=0`），流量预警与到阈值自动关网
 *    静默失效；更糟的是 `DataScheduler.recordHourlyUsage()` 把**上下行颠倒**的月累计落库
 *    （设备的 `monthly_rx/tx` 是反的，靠 profile 掰正），开关改回 true 也修不回来。
 * 2. **口子扩散** → 别的组也跟着豁免，那这个排障开关就没有可观察效果了，等于废掉。
 *
 * 所以下面每一条都成对出现：豁免组要**真的归一化**，非豁免组要**逐字等于入参**。
 *
 * ## 为什么断言里写字符串字面量而不是 `DeviceFields.TrafficLimit.*`
 *
 * `:core:goform` 不依赖 `:core:contract`（它只经 `:core:device-schema` 间接用到 canonical 名，
 * 而那是 `implementation` 依赖，传不过来）。这里刻意写死字面量：canonical 键名本身就是冻结的
 * 对外契约，写死它等于同时钉住「豁免路径输出的是契约键名」。
 */
class GoformNormalizeAlwaysTest {

    /**
     * 真机夹具（设备原始 goform 响应形状）：`switch=1` / `size=470_1024` / `percent=90` /
     * `clear_date=5`，与 `:core:device-schema` 的 `ZteGoformRawCaptureTest` 共用同一份文件。
     *
     * **只读不改**。它不在本模块的 test resources 里（跨模块 test 资源不进 classpath），
     * 所以按路径从当前工作目录逐级向上找仓库根 —— 复制一份到本模块会让两份夹具漂移，
     * 而夹具漂移的表现恰恰是「两个模块的断言各自都绿、合起来是错的」。
     */
    private val trafficRaw: JsonObject by lazy { loadTrafficRaw() }

    private fun loadTrafficRaw(): JsonObject {
        val rel = "core/device-schema/src/test/resources/zte_f50_goform_traffic_raw.json"
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val f = File(dir, rel)
            if (f.isFile) return Json.parseToJsonElement(f.readText()).jsonObject
            dir = dir.parentFile
        }
        throw IllegalStateException("找不到真机夹具 $rel（从 ${File("").absolutePath} 起逐级向上找）")
    }

    /** 收集 WARN 的假日志出口：生产默认是 `AppLogger.w`，JVM 单测里碰不得（见构造参数注释）。 */
    private class WarnSink {
        val lines = mutableListOf<String>()
        fun sink(): (String, String) -> Unit = { _, message -> lines += message }
    }

    /** 排障开关关掉归一化：`normalizeProfile = null`，命令表照旧非空（0.4b 的口径）。 */
    private fun mapperWithNormalizationOff(warn: WarnSink) =
        GoformFieldMapper(
            normalizeProfile = null,
            commandProfile = ZteGoformProfile,
            warn = warn.sink(),
        )

    /** 正常模式：归一化开着。 */
    private fun mapperWithNormalizationOn() =
        GoformFieldMapper(normalizeProfile = ZteGoformProfile, commandProfile = ZteGoformProfile)

    // ─────────── 1. 豁免组：关掉归一化后仍然输出 canonical ───────────

    /**
     * 核心正面用例：`normalizeProfile = null` + `TRAFFIC_LIMIT` → 输出是**归一化后的** canonical，
     * 不是设备原名。
     *
     * 三类断言各有针对：
     * - `enabled` / `alert_percent` / `clear_date`：canonical 键名 + 值编码（`BOOL_01`）；
     * - `limit_bytes` / `limit_value` / `limit_unit_display`：`splitDataVolumeLimit` 从复合串
     *   `"470_1024"` 拆出来的**派生键，设备上不存在** —— 这三条只可能来自归一化；
     * - `monthly_rx/tx_bytes`：方向被掰正（夹具里设备值 rx=123456789012 / tx=9876543210，
     *   输出必须互换）。这一条守的是落库路径：颠倒的值一旦进库就修不回来。
     */
    @Test
    fun `关掉归一化后 TRAFFIC_LIMIT 仍然输出 canonical 键`() {
        val warn = WarnSink()
        // 批 B1：`normalize()` 现在返回 NormalizedFields（「过了归一化层」的类型化凭据），
        // 这里 `.values` 解包成 JsonObject —— 下面每一条断言都**一字未改**，
        // 那正是「只换中间传的类型、对外 JSON 一个字节不变」的可执行证据。
        val out = checkNotNull(
            mapperWithNormalizationOff(warn).normalize(FieldGroup.TRAFFIC_LIMIT, trafficRaw)
        ).values


        assertEquals("开关关掉也必须给 canonical 的 enabled", JsonPrimitive("1"), out["enabled"])
        assertEquals(JsonPrimitive("0"), out["auto_clear"])
        assertEquals(JsonPrimitive("90"), out["alert_percent"])
        assertEquals(JsonPrimitive("5"), out["clear_date"])

        // 派生键：设备响应里没有任何一个字段叫这三个名字，兜底也拿不到
        assertEquals(JsonPrimitive("470"), out["limit_value"])
        assertEquals(JsonPrimitive("GB"), out["limit_unit_display"])
        assertEquals(
            "limit_bytes 缺失 → TrafficLimitMapper 填 0 → checkTrafficLimitThrottled 直接 return（预警静默失效）",
            JsonPrimitive(504658657280L), out["limit_bytes"],
        )

        // 上下行交叉绑定被掰正（设备把两者报反，见 ZteGoformProfile 的实测注释）
        assertEquals(
            "canonical rx（下载）必须来自设备的 monthly_tx_bytes",
            JsonPrimitive("9876543210"), out["monthly_rx_bytes"],
        )
        assertEquals(
            "canonical tx（上传）必须来自设备的 monthly_rx_bytes",
            JsonPrimitive("123456789012"), out["monthly_tx_bytes"],
        )
        assertEquals(JsonPrimitive("1717000000"), out["monthly_time"])

        // 豁免走的是完整归一化，所以 allowlist 与"复合串不进契约"这两条照旧成立
        assertFalse("未登记字段不许透出", out.containsKey("unknown_firmware_field"))
        assertFalse("复合串不进对外契约", out.containsKey("data_volume_limit_size"))
    }

    /**
     * 豁免路径与「归一化开着」的输出**完全一致** —— 豁免不许引入第二种行为。
     *
     * 两者都取 `ZteGoformProfile`（关掉时是 `commandProfile`，开着时是 `normalizeProfile`），
     * 所以这条断言同时证明了「豁免复用的是同一份登记表」，而不是在这里另写一套解析。
     */
    @Test
    fun `豁免路径的输出与开着归一化逐字一致`() {
        val warn = WarnSink()
        val off = mapperWithNormalizationOff(warn).normalize(FieldGroup.TRAFFIC_LIMIT, trafficRaw)?.values
        val on = mapperWithNormalizationOn().normalize(FieldGroup.TRAFFIC_LIMIT, trafficRaw)?.values
        assertEquals("豁免引入了第二种行为 —— 排障模式与正常模式的流量出口必须逐字同形", on, off)
    }

    /** `null` 进 `null` 出的语义在豁免路径上也不变（上层用 null 区分"查询失败"与"查到但为空"）。 */
    @Test
    fun `豁免路径仍然 null 进 null 出`() {
        val warn = WarnSink()
        assertNull(mapperWithNormalizationOff(warn).normalize(FieldGroup.TRAFFIC_LIMIT, null))
    }

    // ─────────── 2. 非豁免组：一个都没跟着豁免 ───────────

    /**
     * 点名钉住 `WIFI_SETTINGS` 与 `CELL_INFO`：关掉归一化后输出必须**逐字等于入参**
     * （而且是同一个对象 —— 走的就是 `return raw` 那条短路）。
     *
     * 这两组是纯透传出口，正是排障开关要服务的对象：关掉后能在响应里直接看到设备原名。
     */
    @Test
    fun `关掉归一化后 WIFI_SETTINGS 与 CELL_INFO 仍然原样透传`() {
        val warn = WarnSink()
        val mapper = mapperWithNormalizationOff(warn)

        val wifiRaw = buildJsonObject {
            put("wifi_chip1_ssid1_ssid", "UFI-AXIS")
            put("wifi_chip1_ssid1_max_sta_num", "10")
        }
        val wifiOut = mapper.normalize(FieldGroup.WIFI_SETTINGS, wifiRaw)?.values
        assertSame("WIFI_SETTINGS 必须原样透传（同一个对象）", wifiRaw, wifiOut)
        assertTrue("设备原名必须还在 —— 这就是排障开关的用途", wifiOut!!.containsKey("wifi_chip1_ssid1_ssid"))
        assertFalse("不该冒出 canonical 键", wifiOut.containsKey("ssid"))

        val cellRaw = buildJsonObject {
            put("Lte_pci", "123")
            put("lte_rsrp", "-95")
        }
        val cellOut = mapper.normalize(FieldGroup.CELL_INFO, cellRaw)?.values
        assertSame("CELL_INFO 必须原样透传（同一个对象）", cellRaw, cellOut)

        assertTrue("非豁免组不该打豁免 WARN：${warn.lines}", warn.lines.isEmpty())
    }

    /**
     * 兜住「以后有人往清单里多加一组」：除 `TRAFFIC_LIMIT` 外的**每一组**都必须原样透传。
     *
     * 这条红了不一定是 bug，但一定是**排障开关可观察面变小**了 —— 按 `NORMALIZE_ALWAYS`
     * 第 5 节的门槛重新判定：新加的那组是不是「按 canonical 重组的派生出口」？
     * 是就更新本测试的豁免清单，不是就回滚。
     */
    @Test
    fun `豁免没有扩散到其它组`() {
        val warn = WarnSink()
        val mapper = mapperWithNormalizationOff(warn)
        val probe = buildJsonObject { put("__device_raw_key__", "keep-me") }
        for (group in FieldGroup.entries) {
            if (group == FieldGroup.TRAFFIC_LIMIT) continue
            assertSame(
                "$group 跟着豁免了 —— 排障开关在这一组上失去了可观察效果",
                probe, mapper.normalize(group, probe)?.values,
            )
        }
        assertTrue("一组都没豁免时不该有任何 WARN，实际：${warn.lines}", warn.lines.isEmpty())
    }

    // ─────────── 3. WARN：真的打了，但只打一次 ───────────

    /**
     * 豁免生效时打一条 WARN，**且按组只打一次**。
     *
     * 为什么必须只打一次：这条路在 `DataScheduler` 的 15s 流量循环上
     * （`collectGoformTraffic` → `getTrafficStats` → `normalize`），每次都打会灌满 `app.log`，
     * 还会把 `AppLogger` 那 500 条内存缓冲（崩溃 dump 的唯一现场）挤干。
     */
    @Test
    fun `豁免生效时打一条 WARN 且只打一次`() {
        val warn = WarnSink()
        val mapper = mapperWithNormalizationOff(warn)
        repeat(5) { mapper.normalize(FieldGroup.TRAFFIC_LIMIT, trafficRaw) }
        mapper.normalize(FieldGroup.TRAFFIC_LIMIT, null)

        assertEquals("豁免 WARN 必须是一次性的（这条路 15s 走一次）", 1, warn.lines.size)
        val line = warn.lines.single()
        assertTrue("WARN 要点明是哪个组：$line", "TRAFFIC_LIMIT" in line)
        assertTrue("WARN 要点明开关名：$line", "field_normalization_enabled" in line)
        assertTrue("WARN 要说清理由（派生出口）：$line", "派生出口" in line)
    }

    /** 归一化开着时这条 WARN 一条都不许有 —— 它描述的是"豁免正在生效"，正常模式下无豁免可言。 */
    @Test
    fun `归一化开着时不打豁免 WARN`() {
        val warn = WarnSink()
        val mapper = GoformFieldMapper(
            normalizeProfile = ZteGoformProfile,
            commandProfile = ZteGoformProfile,
            warn = warn.sink(),
        )
        repeat(3) { mapper.normalize(FieldGroup.TRAFFIC_LIMIT, trafficRaw) }
        assertTrue("正常模式下不该有豁免 WARN：${warn.lines}", warn.lines.isEmpty())
    }

    // ─────────── 4. /api/diagnose 的三条验收判据不许被豁免带偏 ───────────

    /**
     * `enabled` / `profileId` / `coverageReport()` 在豁免后**行为不变**。
     *
     * 这三条是 `/api/diagnose` 的验收判据：`normalization_enabled` 是从
     * `profileId != null` 一路推出来的（`GoformFieldMapper → GoformSignalClient → DataHub →
     * HttpServer`，三层纯委托无兜底）。豁免只改 `normalize()` 一个方法，所以：
     * - `enabled` 仍是 false、`profileId` 仍是 null —— 否则排障开关会永远报 `true`；
     * - `coverageReport()` 仍然短路回那句 hint，且**一条设备查询都不发**
     *   （改成拿 `commandProfile` 兜底的话，排障模式下 `/api/diagnose?fields=1` 会开始真打设备）。
     */
    @Test
    fun `豁免不改变 enabled 与 profileId 与 coverageReport`() {
        val warn = WarnSink()
        val mapper = mapperWithNormalizationOff(warn)
        // 先真的走一次豁免路径，确保断言看到的是"豁免已经生效之后"的状态
        checkNotNull(mapper.normalize(FieldGroup.TRAFFIC_LIMIT, trafficRaw))

        assertFalse("normalizeProfile=null 就该报「归一化已关」，豁免不改这个口径", mapper.enabled)
        assertNull("profileId 一旦非 null，/api/diagnose 会永远报 normalization_enabled=true", mapper.profileId)

        var queries = 0
        val report: JsonObject = runBlocking { mapper.coverageReport { queries++; null } }
        assertEquals("coverageReport 必须仍然短路，不向设备发查询", 0, queries)
        assertEquals("false", report["normalization_enabled"]?.jsonPrimitive?.content)
        assertTrue("短路时要给出原因，不能回一个空壳", report.containsKey("hint"))
        assertFalse("短路时不该出现逐组结果", report.containsKey("groups"))
    }

    /** `maskDump()` 也不在豁免范围内：原始 dump 就该保持设备原样，那是排障时唯一能对上设备后台的出口。 */
    @Test
    fun `豁免不影响原始 dump 的透传`() {
        val warn = WarnSink()
        val dump = buildJsonObject { put("data_volume_limit_size", "470_1024") }
        assertSame(
            "maskDump 必须仍然原样透传 —— 要看设备原名就得靠它",
            dump, mapperWithNormalizationOff(warn).maskDump(dump),
        )
    }

    // ─────────── 5. 批 B1：换类型没换内容 ───────────

    /**
     * 端到端：`normalize()` 的产出**解包后**与直接调 `FieldNormalizer.normalize()` **逐字节一致**。
     *
     * 这条是批 B1 的验收判据本体 —— 那一批只把「中间传的类型」从裸 `JsonObject?` 换成
     * `NormalizedFields?`，最终序列化出去的内容必须一个字节都不变。
     *
     * 为什么连 `toString()` 都比一遍：`JsonObject` 是 `Map`，`equals` 只比内容**不比键序**，
     * 而对外 JSON 的字节序列取决于插入顺序（`limit_value` 在 `limit_bytes` 前面还是后面，
     * 前端不 care，但「一个字节都不能变」这句话 care）。两条一起断言才真的钉住"逐字节"。
     */
    @Test
    fun `解包后的内容与直接调 FieldNormalizer 逐字节一致`() {
        val expected = FieldNormalizer.normalize(
            trafficRaw,
            ZteGoformProfile,
            FieldGroup.TRAFFIC_LIMIT,
            FieldNormalizer.LegacyAliases.DROP,
        )
        val actual = checkNotNull(
            mapperWithNormalizationOn().normalize(FieldGroup.TRAFFIC_LIMIT, trafficRaw)
        ).values

        assertEquals("解包后的字段集合变了 —— 对外 JSON 已经不是同一份", expected, actual)
        assertEquals("键序变了 → 序列化出去的字节序列变了", expected.toString(), actual.toString())
    }

    /**
     * 透传分支解包后是**同一个对象**（不是内容相等的拷贝）。
     *
     * 钉的是 `NormalizedFields` 的语义边界：它保证「过了归一化闸门」，**不保证**字段名是
     * canonical —— 排障开关关掉时闸门原样透传，包在里面的就是设备那个实例。
     * 哪天有人把透传分支改成「复制一份 / 顺手清理一下键」，这条会红，而那正是行为变更。
     */
    @Test
    fun `透传分支包着的就是设备原始那个对象`() {
        val warn = WarnSink()
        val raw = buildJsonObject { put("Lte_pci", "123") }
        val out = checkNotNull(mapperWithNormalizationOff(warn).normalize(FieldGroup.CELL_INFO, raw))
        assertSame("透传分支必须原样包住入参实例 —— 类型只是凭据，不改数据", raw, out.values)
        assertTrue(
            "设备原名必须还在：NormalizedFields 的语义是「过了闸门」，不是「一定 canonical」",
            out.values.containsKey("Lte_pci"),
        )
    }
}

