package com.ufi_axis_core.controller.carrier

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 多账号存储的**纯逻辑**（2026-11-05，P0）。
 *
 * 这里不碰 Android：`CarrierSessionStore` 需要 Context / Keystore，而 `:core:controller`
 * 的测试只有 junit。所以把三件容易写错的事抽出来单测：
 * 1. **id 怎么算** —— 必须稳定（同号恒等）、去重（同号不产生两个账号）、且在手机号为空时
 *    仍然可用（联通扫码后运营商可能不回号）；
 * 2. **索引怎么合并** —— 同 id 覆盖而不是重复追加，顺序按创建时间稳定；
 * 3. **旧数据怎么迁移** —— 尤其"解密失败"必须重试、**不能**当成数据损坏删掉。
 */
class CarrierAccountStoreTest {

    // ───────────────────────── id 生成 ─────────────────────────

    @Test
    fun `同运营商同号码的 id 恒定且相等`() {
        val a = CarrierAccountIds.of("mobile", "13800138000")
        val b = CarrierAccountIds.of("mobile", "13800138000")
        assertEquals("同一个号重复登录必须落到同一个 id（否则列表里会出现重复账号）", a, b)
    }

    @Test
    fun `不同运营商同号码是两个账号`() {
        // 携号转网的场景：号码不变、运营商变了 ⇒ 两个 id。
        // 是否合并由上层在"新增账号"时提示，存储层不做猜测。
        val mobile = CarrierAccountIds.of("mobile", "13800138000")
        val unicom = CarrierAccountIds.of("unicom", "13800138000")
        assertNotEquals(mobile, unicom)
    }

    @Test
    fun `号码里的空格和横线不影响 id`() {
        assertEquals(
            CarrierAccountIds.of("mobile", "13800138000"),
            CarrierAccountIds.of("mobile", "138 0013 8000"),
        )
        assertEquals(
            CarrierAccountIds.of("mobile", "13800138000"),
            CarrierAccountIds.of("mobile", "138-0013-8000"),
        )
    }

    @Test
    fun `手机号为空时退回随机 id 且带 r 前缀`() {
        // 联通扫码登录后运营商可能不回号（CarrierBillService 里 phone = phone.orEmpty()）。
        val a = CarrierAccountIds.of("unicom", "", random = { "aaaaaaaaaaaa" })
        val b = CarrierAccountIds.of("unicom", "", random = { "bbbbbbbbbbbb" })
        assertEquals("raaaaaaaaaaaa", a)
        assertNotEquals(a, b)
    }

    @Test
    fun `哈希 id 带 h 前缀且不含号码明文`() {
        val id = CarrierAccountIds.of("mobile", "13800138000")
        assertTrue("id 应以 h 开头，便于和随机兜底的 r 区分", id.startsWith("h"))
        assertFalse("SharedPreferences 的 key 明文可见，id 里不能出现手机号", id.contains("13800138000"))
        assertEquals(17, id.length) // 'h' + 16 位 hex
    }

    // ───────────────────────── 索引合并 ─────────────────────────

    @Test
    fun `索引 upsert 同一个 id 是覆盖而不是追加`() {
        val index = CarrierAccountIndex()
            .upsert(entry("h1", createdAt = 100))
            .upsert(entry("h2", createdAt = 200))
            .upsert(entry("h1", createdAt = 100, label = "主卡"))

        assertEquals(2, index.entries.size)
        assertEquals("主卡", index.entries.first { it.id == "h1" }.label)
    }

    @Test
    fun `索引按创建时间升序，同刻按 id 兜底保证稳定`() {
        val index = CarrierAccountIndex()
            .upsert(entry("h2", createdAt = 100))
            .upsert(entry("h1", createdAt = 100))
            .upsert(entry("h3", createdAt = 50))
        assertEquals(listOf("h3", "h1", "h2"), index.entries.map { it.id })
    }

    @Test
    fun `从索引里摘掉账号`() {
        val index = CarrierAccountIndex().upsert(entry("h1")).upsert(entry("h2"))
        assertEquals(listOf("h2"), index.without("h1").entries.map { it.id })
        assertEquals("摘不存在的 id 不应改变内容", 2, index.without("nope").entries.size)
    }

    // ───────────────────────── 模型转换 ─────────────────────────

    @Test
    fun `索引项只带脱敏号，不带完整号码`() {
        val account = StoredCarrierAccount(
            id = "h1",
            carrier = "mobile",
            phone = "13800138000",
            label = "主卡",
            createdAt = 7,
        )
        val entry = account.toIndexEntry()
        assertEquals("138****8000", entry.phoneMasked)
        assertFalse("索引是一份会被反复读的明文结构，不能出现完整号码", entry.phoneMasked.contains("0013"))
        assertEquals("主卡", entry.label)
    }

    @Test
    fun `账号与旧会话结构互转字段不丢`() {
        val account = StoredCarrierAccount(
            id = "h1",
            carrier = "unicom",
            phone = "",
            label = "备用",
            cookies = listOf(
                CarrierCookie(
                    name = "S", value = "v", domain = "d", path = "/",
                    expiresAt = -1, secure = true, httpOnly = true,
                )
            ),
            savedAt = 11,
            lastFetchedAt = 22,
            createdAt = 33,
        )
        val session = account.toSession()
        assertEquals("unicom", session.carrier)
        assertEquals("", session.phone)
        assertEquals(1, session.cookies.size)
        assertEquals(11, session.savedAt)
        assertEquals(22, session.lastFetchedAt)
    }

    // ───────────────────────── 迁移决策 ─────────────────────────

    private val session = StoredCarrierSession(carrier = "mobile", phone = "13800138000")
    private val decodeOk: (String) -> StoredCarrierSession? = { session }
    private val decodeAutoOk: (String) -> CarrierAutoLoginConfig? = {
        CarrierAutoLoginConfig(enabled = true, phone = "13800138000")
    }

    @Test
    fun `没有旧数据时只写 schema`() {
        val plan = planLegacyMigration(
            legacyEncrypted = null,
            legacyAutoEncrypted = null,
            decrypt = { null },
            decodeSession = decodeOk,
            decodeAuto = decodeAutoOk,
        )
        assertEquals(LegacyMigrationPlan.Empty, plan)
    }

    @Test
    fun `解密失败必须重试，不能当成数据损坏`() {
        // 这是本次改造最重要的一条：旧实现 load() 解密失败就 remove(KEY_SESSION)，
        // 一次 Keystore 抖动就能把用户所有会话永久抹掉。
        val plan = planLegacyMigration(
            legacyEncrypted = "garbage",
            legacyAutoEncrypted = null,
            decrypt = { null },
            decodeSession = decodeOk,
            decodeAuto = decodeAutoOk,
        )
        assertEquals(LegacyMigrationPlan.Retry, plan)
    }

    @Test
    fun `解开了但 JSON 坏掉才算损坏`() {
        val plan = planLegacyMigration(
            legacyEncrypted = "cipher",
            legacyAutoEncrypted = null,
            decrypt = { "{not json" },
            decodeSession = { null },
            decodeAuto = decodeAutoOk,
        )
        assertEquals(LegacyMigrationPlan.Corrupt, plan)
    }

    @Test
    fun `正常迁移带出自动登录配置`() {
        val plan = planLegacyMigration(
            legacyEncrypted = "cipher",
            legacyAutoEncrypted = "auto-cipher",
            decrypt = { "plain" },
            decodeSession = decodeOk,
            decodeAuto = decodeAutoOk,
        )
        assertTrue(plan is LegacyMigrationPlan.Migrate)
        plan as LegacyMigrationPlan.Migrate
        assertEquals(session, plan.session)
        assertEquals(true, plan.autoConfig?.enabled)
    }

    @Test
    fun `自动登录配置坏掉不影响会话迁移`() {
        val plan = planLegacyMigration(
            legacyEncrypted = "cipher",
            legacyAutoEncrypted = "broken-auto",
            decrypt = { encoded -> if (encoded == "broken-auto") null else "plain" },
            decodeSession = decodeOk,
            decodeAuto = decodeAutoOk,
        )
        assertTrue("配置坏了只是丢一次配置，不该连账号一起丢", plan is LegacyMigrationPlan.Migrate)
        assertEquals(null, (plan as LegacyMigrationPlan.Migrate).autoConfig)
    }

    private fun entry(id: String, createdAt: Long = 0, label: String = "") = CarrierAccountIndexEntry(
        id = id,
        carrier = "mobile",
        phoneMasked = "138****8000",
        label = label,
        createdAt = createdAt,
    )
}
