package com.ufi_axis_core.deviceschema

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier

/**
 * [FieldNormalizer] 的语义基线。
 *
 * 这里断言的三条是**契约**，不是实现细节：
 *   1. allowlist（未登记字段不输出）；
 *   2. 字段缺失 = 省略 key（绝不输出 null）；
 *   3. 别名链按顺序取第一个"存在且 decode 成功"的。
 *
 * 用一个最小 fake profile 而不是 ZteGoformProfile —— 真 profile 的断言在
 * [com.ufi_axis_core.deviceschema.profile.ZteGoformProfileTest]，两者关注点分开：
 * 这个文件测归一化引擎，那个文件测映射表内容。
 */
class FieldNormalizerTest {

    /** canonical 与 source 全部故意起成不同的名字，方便看出输出到底来自哪一侧。 */
    private object FakeProfile : DeviceProfile {
        override val id = "fake"
        override val displayName = "fake"

        val plain = fieldOf("out_plain", FieldGroup.DEVICE_SETTINGS, "dev_plain")

        /** 三级别名链，用来验证优先级与 decode 失败时的回退。 */
        val chained = fieldOf(
            "out_chained", FieldGroup.DEVICE_SETTINGS,
            "dev_first", "dev_second", "dev_third",
            decode = Decoders.NON_BLANK,
        )

        val flag = fieldOf("out_flag", FieldGroup.DEVICE_SETTINGS, "dev_flag", decode = Decoders.BOOL_01)

        /** 不同分组，用来验证分组隔离。 */
        val other = fieldOf("out_other", FieldGroup.LAN_SETTINGS, "dev_other")

        val masked = fieldOf(
            "out_masked", FieldGroup.IDENTITY, "dev_masked",
            sensitivity = Sensitivity.MASKED,
        )

        val secret = fieldOf(
            "out_secret", FieldGroup.IDENTITY, "dev_secret_a", "dev_secret_b",
            sensitivity = Sensitivity.SECRET,
        )

        /** canonical 恰好等于 [plain] 的 source —— 验证 KEEP_PRESENT 不会用原值覆盖已写入的 canonical。 */
        val collides = fieldOf("dev_plain", FieldGroup.WIFI_SETTINGS, "dev_collides_src", decode = Decoders.BOOL_01)

        override fun readSpecs() = listOf(plain, chained, flag, other, masked, secret, collides)

        override fun cmdsFor(group: FieldGroup) = emptyList<String>()

        override fun writeSpec(key: SettingKey): WriteSpec? = null
    }

    private fun norm(
        raw: JsonObject?,
        group: FieldGroup = FieldGroup.DEVICE_SETTINGS,
        legacy: FieldNormalizer.LegacyAliases = FieldNormalizer.LegacyAliases.KEEP_PRESENT,
    ) = FieldNormalizer.normalize(raw, FakeProfile, group, legacy)

    private fun obj(vararg pairs: Pair<String, String>) = buildJsonObject {
        pairs.forEach { (k, v) -> put(k, JsonPrimitive(v)) }
    }

    // ───────────────────────── allowlist ─────────────────────────

    @Test
    fun `未登记的设备字段不输出`() {
        val out = norm(obj("dev_plain" to "v", "brand_new_firmware_field" to "leak"))
        assertEquals(JsonPrimitive("v"), out["out_plain"])
        assertFalse("固件新增字段不能自动泄漏到 API", out.containsKey("brand_new_firmware_field"))
    }

    @Test
    fun `只处理指定分组的 spec`() {
        val raw = obj("dev_plain" to "a", "dev_other" to "b")
        val settings = norm(raw, FieldGroup.DEVICE_SETTINGS)
        assertTrue(settings.containsKey("out_plain"))
        assertFalse("跨分组字段不能串到本端点", settings.containsKey("out_other"))

        val lan = norm(raw, FieldGroup.LAN_SETTINGS)
        assertTrue(lan.containsKey("out_other"))
        assertFalse(lan.containsKey("out_plain"))
    }

    // ─────────────────── 缺失 = 省略 key，不是 null ───────────────────

    @Test
    fun `字段缺失时省略 key 而不是输出 null`() {
        val out = norm(obj("dev_plain" to "v"))
        assertFalse(out.containsKey("out_chained"))
        assertFalse(out.containsKey("out_flag"))
        assertNull(out["out_chained"])
    }

    @Test
    fun `设备侧显式 null 视为缺失`() {
        val out = norm(buildJsonObject { put("dev_plain", JsonNull) })
        assertFalse(out.containsKey("out_plain"))
        assertEquals(JsonObject(emptyMap()), out)
    }

    @Test
    fun `raw 为 null 或空对象时返回空对象`() {
        assertEquals(JsonObject(emptyMap()), norm(null))
        assertEquals(JsonObject(emptyMap()), norm(JsonObject(emptyMap())))
    }

    // ───────────────────────── 别名链 ─────────────────────────

    @Test
    fun `别名链取第一个命中的 source`() {
        val out = norm(obj("dev_second" to "B", "dev_third" to "C", "dev_first" to "A"))
        assertEquals("顺序由 sources 决定，与 raw 里的键序无关", JsonPrimitive("A"), out["out_chained"])
    }

    @Test
    fun `decode 返回 null 时继续尝试后续 source`() {
        // dev_first 是空串 → NON_BLANK 判为缺失 → 回退到 dev_second
        val out = norm(obj("dev_first" to "   ", "dev_second" to "B"))
        assertEquals(JsonPrimitive("   "), out["dev_first"]) // 过渡期原名仍按原值透出
        assertEquals(JsonPrimitive("B"), out["out_chained"])
    }

    @Test
    fun `全部 source 都未命中时整个字段消失`() {
        val out = norm(obj("dev_first" to "", "dev_second" to "", "dev_third" to ""))
        assertFalse(out.containsKey("out_chained"))
    }

    @Test
    fun `resolve 报告命中的是哪个 source`() {
        val hit = FieldNormalizer.resolve(obj("dev_second" to "B"), FakeProfile.chained)
        assertEquals("dev_second", hit?.source)
        assertEquals(JsonPrimitive("B"), hit?.value)
        assertNull(FieldNormalizer.resolve(obj("unrelated" to "x"), FakeProfile.chained))
    }

    // ───────────────────────── BOOL_01 ─────────────────────────

    @Test
    fun `BOOL_01 把多种布尔编码统一成 1 与 0 字符串`() {
        listOf("1", "on", "ON", "true", "TRUE", "yes", "SERVER").forEach {
            assertEquals("真值编码 $it", JsonPrimitive("1"), norm(obj("dev_flag" to it))["out_flag"])
        }
        listOf("0", "off", "OFF", "false", "no").forEach {
            assertEquals("假值编码 $it", JsonPrimitive("0"), norm(obj("dev_flag" to it))["out_flag"])
        }
    }

    @Test
    fun `BOOL_01 输出的是字符串而不是 JSON Boolean`() {
        // 冻结契约：web 写 String(v) === '1'，app 写 == "1"，换成 Boolean 两端同时失效
        val v = norm(obj("dev_flag" to "1"))["out_flag"] as JsonPrimitive
        assertTrue("必须是 JSON 字符串", v.isString)
        assertEquals("1", v.content)
    }

    @Test
    fun `BOOL_01 遇到无法识别的值视为缺失`() {
        assertFalse(norm(obj("dev_flag" to "maybe")).containsKey("out_flag"))
    }

    // ───────────────────── 过渡期别名策略 ─────────────────────

    @Test
    fun `KEEP_PRESENT 同时输出 canonical 与命中的设备原名`() {
        val out = norm(obj("dev_plain" to "v", "dev_second" to "B", "dev_flag" to "on"))
        assertEquals(JsonPrimitive("v"), out["out_plain"])
        assertEquals(JsonPrimitive("B"), out["dev_second"])
        // 原名保持设备原始值（未经 decode）——老客户端读的就是原始格式
        assertEquals("原名不能被 decode 后的值替换", JsonPrimitive("on"), out["dev_flag"])
        assertEquals(JsonPrimitive("1"), out["out_flag"])
    }

    @Test
    fun `KEEP_PRESENT 仍然是 allowlist`() {
        val out = norm(obj("dev_plain" to "v", "unregistered" to "x"))
        assertFalse(out.containsKey("unregistered"))
    }

    @Test
    fun `KEEP_PRESENT 不透出未出现在 raw 里的别名`() {
        val out = norm(obj("dev_second" to "B"))
        assertFalse(out.containsKey("dev_first"))
        assertFalse(out.containsKey("dev_third"))
    }

    @Test
    fun `DROP 只输出 canonical`() {
        val out = norm(
            obj("dev_plain" to "v", "dev_second" to "B"),
            legacy = FieldNormalizer.LegacyAliases.DROP,
        )
        assertEquals(setOf("out_plain", "out_chained"), out.keys)
    }

    @Test
    fun `canonical 与别名同名时保留 canonical 的值`() {
        // 这条是 KEEP_PRESENT 专属语义（别名回显走 putIfAbsent），所以显式指定策略：
        // collides 的 canonical 是 "dev_plain"，同时它又是 plain 的 source
        val out = FieldNormalizer.normalize(
            obj("dev_collides_src" to "on", "dev_plain" to "raw"),
            FakeProfile,
            FieldGroup.WIFI_SETTINGS,
            FieldNormalizer.LegacyAliases.KEEP_PRESENT,
        )
        assertEquals("canonical 已写入后不能被 putIfAbsent 的原值覆盖", JsonPrimitive("1"), out["dev_plain"])
        assertEquals(JsonPrimitive("on"), out["dev_collides_src"])
    }

    // ───────────────────────── 结构型字段 ─────────────────────────

    @Test
    fun `AS_IS 原样保留数组与双重编码字符串`() {
        val arr = buildJsonArray { add(JsonPrimitive("a")) }
        val spec = fieldOf("out_arr", FieldGroup.WIFI_CLIENTS, "dev_arr")
        val hit = FieldNormalizer.resolve(buildJsonObject { put("dev_arr", arr) }, spec)
        assertEquals("数组形态是契约的一部分，不能被字符串化", arr, hit?.value)

        val doubleEncoded = JsonPrimitive("""[{"mac_addr":"00:11"}]""")
        val hit2 = FieldNormalizer.resolve(buildJsonObject { put("dev_arr", doubleEncoded) }, spec)
        assertEquals("双重编码形态同样原样透出", doubleEncoded, hit2?.value)
    }

    // ───────────────────────── 覆盖率诊断 ─────────────────────────

    @Test
    fun `coverage 列出每个字段命中的 source`() {
        val cov = FieldNormalizer.coverage(
            obj("dev_second" to "B"),
            FakeProfile,
            FieldGroup.DEVICE_SETTINGS,
        ).associateBy { it.canonical }

        assertEquals(setOf("out_plain", "out_chained", "out_flag"), cov.keys)
        assertEquals("dev_second", cov["out_chained"]?.hitSource)
        assertNull("未命中就是 null，用来生成适配新设备的 TODO 清单", cov["out_plain"]?.hitSource)
        assertEquals(listOf("dev_first", "dev_second", "dev_third"), cov["out_chained"]?.sources)
    }

    @Test
    fun `coverage 在 raw 为 null 时全部未命中`() {
        val cov = FieldNormalizer.coverage(null, FakeProfile, FieldGroup.DEVICE_SETTINGS)
        assertEquals(3, cov.size)
        assertTrue(cov.all { it.hitSource == null })
    }

    // ───────────────────────── 脱敏 ─────────────────────────

    @Test
    fun `maskSensitive 打码 MASKED 与 SECRET 保留 PUBLIC`() {
        val normalized = norm(
            obj("dev_masked" to "13800138000", "dev_secret_a" to "token"),
            FieldGroup.IDENTITY,
        )
        val masked = FieldNormalizer.maskSensitive(normalized, FakeProfile)

        assertEquals(JsonPrimitive("***"), masked["out_masked"])
        assertEquals("别名也要打码，否则过渡期原名会漏真值", JsonPrimitive("***"), masked["dev_masked"])
        assertEquals(JsonPrimitive("***"), masked["out_secret"])
        assertEquals(JsonPrimitive("***"), masked["dev_secret_a"])
    }

    @Test
    fun `maskSensitive 不动 PUBLIC 字段也不新增字段`() {
        val normalized = norm(obj("dev_plain" to "v"))
        val masked = FieldNormalizer.maskSensitive(normalized, FakeProfile)
        assertEquals(normalized, masked)
    }

    @Test
    fun `secretKeys 只包含 SECRET 字段及其别名`() {
        val keys = FieldNormalizer.secretKeys(FakeProfile)
        assertEquals(setOf("out_secret", "dev_secret_a", "dev_secret_b"), keys)
    }

    @Test
    fun `maskDump 在登记表之外按字段名兜底打码凭据`() {
        // 原始 dump（不过 allowlist）：既有登记过的敏感字段，也有固件自带、profile 里没有的凭据字段
        val raw = obj(
            "dev_masked" to "13800138000",
            "wifi_chip1_ssid1_password_encode" to "c2VjcmV0",
            "AuthToken" to "abc",
            "dev_plain" to "v",
        )
        val masked = FieldNormalizer.maskDump(raw, FakeProfile)

        assertEquals("登记过的照 Sensitivity 打码", JsonPrimitive("***"), masked["dev_masked"])
        assertEquals(
            "未登记但名字里有 password —— dump 端点没有 allowlist 保护，只能靠名字兜底",
            JsonPrimitive("***"), masked["wifi_chip1_ssid1_password_encode"],
        )
        assertEquals("名字匹配不分大小写", JsonPrimitive("***"), masked["AuthToken"])
        assertEquals("普通字段保持原值", JsonPrimitive("v"), masked["dev_plain"])
        assertEquals("只改值不动 key 集合", raw.keys, masked.keys)
    }


    // ───────────────────────── spec 自身约束 ─────────────────────────

    @Test(expected = IllegalArgumentException::class)
    fun `canonical 为空时构造失败`() {
        FieldSpec(" ", FieldGroup.DEVICE_SETTINGS, listOf("x"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `sources 为空时构造失败`() {
        FieldSpec("out", FieldGroup.DEVICE_SETTINGS, emptyList())
    }

    // ───────────────────────── 其它 Decoders ─────────────────────────

    @Test
    fun `NUMERIC 输出数字非数字视为缺失`() {
        val spec = fieldOf("out_num", FieldGroup.LAN_SETTINGS, "dev_num", decode = Decoders.NUMERIC)
        val hit = FieldNormalizer.resolve(obj("dev_num" to " 1500 "), spec)
        assertEquals(JsonPrimitive(1500L), hit?.value)
        assertNull(FieldNormalizer.resolve(obj("dev_num" to "auto"), spec))
    }

    @Test
    fun `TRIMMED 去空白空值视为缺失`() {
        val spec = fieldOf("out_t", FieldGroup.LAN_SETTINGS, "dev_t", decode = Decoders.TRIMMED)
        assertEquals(JsonPrimitive("v"), FieldNormalizer.resolve(obj("dev_t" to " v "), spec)?.value)
        assertNull(FieldNormalizer.resolve(obj("dev_t" to "  "), spec))
    }

    @Test
    fun `mapValues 翻译值域表外的值原样透出`() {
        val spec = fieldOf(
            "out_m", FieldGroup.CONNECTION, "dev_m",
            decode = Decoders.mapValues(mapOf("20" to "5G")),
        )
        assertEquals(JsonPrimitive("5G"), FieldNormalizer.resolve(obj("dev_m" to "20"), spec)?.value)
        assertEquals(JsonPrimitive("99"), FieldNormalizer.resolve(obj("dev_m" to "99"), spec)?.value)
    }

    @Test
    fun `空映射表等价于原样透出`() {
        val spec = fieldOf("out_m", FieldGroup.CONNECTION, "dev_m", decode = Decoders.mapValues(emptyMap()))
        assertEquals(JsonPrimitive("20"), FieldNormalizer.resolve(obj("dev_m" to "20"), spec)?.value)
    }

    // ─────────── NormalizedFields（批 B1）：归一化是它唯一的来路 ───────────

    /**
     * **「绕不过归一化」这件事怎么保证的** —— 这条测试是那个保证的说明书，读它比读断言重要。
     *
     * 机制是三层，**没有一层靠纪律**：
     *
     * 1. [NormalizedFields] 的构造函数是 `private` —— 连同一个包里的 [FieldNormalizer]
     *    都碰不到（Kotlin 的 `private` 不是 Java 的 package-private）。
     * 2. 唯一的构造入口是它伴生对象里的 `internal fun of(...)`，而 `internal` 的作用域是
     *    **`:core:device-schema` 这一个 Gradle 模块**。所有消费方（`:core:goform` /
     *    `:core:api` / `:core:scheduler` / `:core`）都在模块外，**编译期**就被拒绝 ——
     *    所以「在飞猫适配里 new 一个 NormalizedFields 交差」这条路根本走不通，
     *    不是"能写但不该写"，是写了编译不过。
     * 3. 模块内唯一的调用点是 [FieldNormalizer.normalizeToFields]，也就是归一化本身。
     *
     * **为什么这条测试只能断言第 1 层**：第 2 层是 Kotlin 编译器的可见性检查，一个「模块外
     * 造不出来」的反例在本模块的 test 源集里**写不出来**（同模块，`internal` 可见），
     * 硬写就成了自己给自己放行。跨模块的编译期失败要靠新建一个假模块才能测，代价远大于收益。
     * 所以这里断言「JVM 层面没有能从 Kotlin 调到的构造函数」+ 上面这段说清机制，
     * 第 2 层由 Kotlin 的语言规则兜着。
     *
     * ## 实测到的一处 JVM 细节（2026-09-25，第一版断言就是被它照出来的）
     *
     * `private constructor` + 伴生对象工厂，Kotlin 会为「伴生对象访问私有构造函数」多生成一个
     * **合成桥构造函数**，字节码里它是 `public NormalizedFields(JsonObject, DefaultConstructorMarker)`。
     * 所以「所有声明的构造函数都非 public」这条断言**会红**，但那不是后门：
     * 末参 `kotlin.jvm.internal.DefaultConstructorMarker` 是 Kotlin 运行时的内部标记类型，
     * Kotlin 侧看不到这个重载也调不了它（本仓全 Kotlin）。
     * 因此这里改成钉住真正要防的形状：**不许存在 `NormalizedFields(JsonObject)` 这种
     * 一参公开构造函数**，而任何 public 构造函数都必须是带 marker 的那个合成桥。
     * 注意这条只挡 Kotlin —— 理论上 Java 侧硬传一个 `null` marker 能绕过去，
     * 本仓没有 Java 生产代码，不为此再加一层。
     *
     * [NormalizedFields.EMPTY] 不破坏这个保证：它不吃任何入参，
     * 因此没法用它把未归一化的数据偷带进来（空对象里没有字段名可言）。
     */
    @Test
    fun `NormalizedFields 没有能从 Kotlin 调到的构造函数`() {
        val ctors = NormalizedFields::class.java.declaredConstructors
        assertTrue("构造函数一个都没有？那这个类型没法被归一化层产出", ctors.isNotEmpty())
        for (c in ctors.filter { Modifier.isPublic(it.modifiers) }) {
            assertTrue(
                "public 构造函数 $c 不是「伴生对象访问私有构造函数」的合成桥 —— 后门开了",
                c.parameterTypes.any { it.name == "kotlin.jvm.internal.DefaultConstructorMarker" },
            )
        }
        assertNull(
            "出现了 NormalizedFields(JsonObject) 这种公开构造函数 = 任何模块都能凭空造出「已归一化」的凭据",
            ctors.firstOrNull {
                Modifier.isPublic(it.modifiers) &&
                    it.parameterTypes.size == 1 &&
                    it.parameterTypes[0] == JsonObject::class.java
            },
        )
    }


    /** [FieldNormalizer.normalizeToFields] 解包后与 [FieldNormalizer.normalize] **逐字一致**（换类型不换内容）。 */
    @Test
    fun `normalizeToFields 解包后等于 normalize`() {
        val raw = obj("dev_plain" to "v", "dev_flag" to "on", "brand_new_firmware_field" to "leak")
        val expected = FieldNormalizer.normalize(
            raw, FakeProfile, FieldGroup.DEVICE_SETTINGS, FieldNormalizer.LegacyAliases.DROP,
        )
        val actual = FieldNormalizer.normalizeToFields(
            raw, FakeProfile, FieldGroup.DEVICE_SETTINGS, FieldNormalizer.LegacyAliases.DROP,
        )
        assertEquals(expected, actual?.values)
        assertEquals("键序也要一致 —— 对外 JSON 的字节序列取决于它", expected.toString(), actual?.values.toString())
        assertFalse("allowlist 照旧", actual!!.values.containsKey("brand_new_firmware_field"))
    }

    /**
     * profile 为 `null`（排障开关关掉归一化）时**原样包住入参那个实例**。
     *
     * 这就是 [NormalizedFields] 的语义边界：它保证「过了归一化层」，**不保证**字段名是
     * canonical —— 这条路上包着的就是设备原名。改成「复制一份」或「顺手过一遍 allowlist」
     * 都会让这条红，而那两种都是行为变更（排障开关就是要看设备原名）。
     */
    @Test
    fun `profile 为 null 时原样包住设备响应`() {
        val raw = obj("dev_plain" to "v")
        val out = FieldNormalizer.normalizeToFields(raw, null, FieldGroup.DEVICE_SETTINGS)
        assertSame("透传分支不许拷贝也不许改写", raw, out?.values)
        assertTrue("设备原名必须还在", out!!.values.containsKey("dev_plain"))
        assertFalse("关掉归一化就不该冒出 canonical 键", out.values.containsKey("out_plain"))
    }

    /** `null` 进 `null` 出：上层用 null 区分「查询失败」与「查到了但为空」，两种 profile 状态下都一样。 */
    @Test
    fun `normalizeToFields 的 null 进 null 出`() {
        assertNull(FieldNormalizer.normalizeToFields(null, FakeProfile, FieldGroup.DEVICE_SETTINGS))
        assertNull(FieldNormalizer.normalizeToFields(null, null, FieldGroup.DEVICE_SETTINGS))
    }

    /** [NormalizedFields.EMPTY] 就是 `{}`（非空返回契约的那个位置用它，见 `GoformWifiClient.getWifiSettingsMerged`）。 */
    @Test
    fun `EMPTY 是空对象`() {
        assertEquals(JsonObject(emptyMap()), NormalizedFields.EMPTY.values)
    }
}

