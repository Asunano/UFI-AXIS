package com.ufi_axis_core.controller.carrier

import kotlinx.serialization.Serializable
import java.security.MessageDigest
import java.util.UUID

/**
 * 多账号存储的数据模型与**纯逻辑**（2026-11-05，P0）。
 *
 * ## 为什么把纯逻辑单独拎出来
 * `CarrierSessionStore` 依赖 Android Context / Keystore / SharedPreferences，
 * 而 `:core:controller` 的测试只有 junit + mockk（没有 Robolectric）。把「id 怎么算」
 * 「索引怎么合并」「旧数据该迁移还是该重试」这三件容易写错、又完全不依赖 Android 的事
 * 放进这里，就能用普通单测钉死；留给 Store 的只剩读写文件。
 *
 * ## 三条设计约束
 * 1. **id 绝不用手机号明文**，且**不能假设手机号一定存在** —— 联通扫码登录后运营商
 *    可能不回号（`CarrierBillService` 里 `phone = phone.orEmpty()` 就是这条的现场证词）。
 *    所以有号时用 hash、无号时用随机串，两者都带前缀，便于日志里分辨。
 * 2. **索引与明细分开**：列表只读索引（脱敏号 + 别名 + 运营商），明细按需读；
 *    账号数不设上限，列表不能因为"号很多"而变重。
 * 3. **迁移决策与迁移执行分开**：决策是纯函数（[planLegacyMigration]），执行才碰磁盘。
 *    这样"解密失败必须保留重试、不能沿用旧的'解密失败即删'"这条能在单测里断言。
 */

/**
 * 单账号时代的会话结构（2026-10-31）。
 *
 * 2026-11-05 多账号之后它仍然保留，理由是**它是兼容层的汇率**：
 * `CarrierBillService`、`CarrierAutoLoginManager` 与已有的
 * `CarrierSessionSerializationTest` 都按这个形状工作，P0 不碰它们；
 * 多账号结构 [StoredCarrierAccount] 与它之间由 [toSession] / `save()` 互转。
 * 等 P1 把服务层改成账号维度之后，这个类才会退出历史舞台（届时迁移也已完成）。
 */
@Serializable
data class StoredCarrierSession(
    val carrier: String,
    /** 完整手机号（移动登录前已知；联通扫码后可能是脱敏号或空串）。 */
    val phone: String,
    val cookies: List<CarrierCookie> = emptyList(),
    val savedAt: Long = 0,
    val lastFetchedAt: Long = 0,
)

/** 一个运营商账号的完整数据（含 Cookie），加密落盘。 */
@Serializable
data class StoredCarrierAccount(
    val id: String,
    val carrier: String,
    /**
     * `carrier`（普通运营商）/ `iot_card`（物联卡）。
     *
     * 2026-11-08：账号维度增加“接入方式”。默认 carrier，旧数据反序列化不受影响。
     * 物联卡仍复用同一份加密存储/索引/活动指针，只是 UI 按这个字段分组。
     */
    val accessMethod: String = "carrier",
    /**
     * 物联卡站点提供商：`cloud`（云上卡）/ `xj`（小鲸卡）。普通运营商为 `cloud` 占位。
     *
     * 2026-11-09：同一张充值号可能同时属于两个站点，账号 id 必须把 provider 算进去
     * （见 `CarrierBillService.upsertAccount`），否则两张卡会互相覆盖。
     */
    val iotProvider: String = "cloud",
    /** 完整手机号；联通扫码后可能为空；物联卡为完整充值号。 */
    val phone: String,
    /** 用户别名；为空时 UI 显示脱敏手机号 / 卡号。 */
    val label: String = "",
    val cookies: List<CarrierCookie> = emptyList(),
    val savedAt: Long = 0,
    val lastFetchedAt: Long = 0,
    val createdAt: Long = 0,
    /**
     * 联通服务密码登录拿到的 `token_online` 与当次 `appId`（2026-11-07）。
     *
     * 存它只为**续期**：会话过期时 `onLine.htm` 能拿新 Cookie，不必再问用户密码。
     * 移动不走这条路，字段留空。默认值保证旧数据反序列化不受影响。
     */
    val tokenOnline: String = "",
    val appId: String = "",
)

/** 索引项：只放列表需要的字段（不含 Cookie、不含完整手机号）。 */
@Serializable
data class CarrierAccountIndexEntry(
    val id: String,
    val carrier: String,
    val accessMethod: String = "carrier",
    /** 物联卡提供商；普通运营商为空串。 */
    val iotProvider: String = "",
    val phoneMasked: String,
    val label: String = "",
    val createdAt: Long = 0,
)

/** 索引整体。包一层是为了将来加字段（比如排序偏好）时不用改存储 key。 */
@Serializable
data class CarrierAccountIndex(
    val entries: List<CarrierAccountIndexEntry> = emptyList(),
)

/** 账号 id 生成。 */
object CarrierAccountIds {

    /**
     * 生成稳定 id。
     *
     * - 手机号已知：`h` + `sha256(carrier:phone)` 前 16 位。同一个号重复登录恒等，
     *   因此天然去重，也**不会**把号码写进 key 名（SharedPreferences 的 key 明文可见）。
     * - 手机号未知（联通扫码后未回号）：`r` + 随机 12 位。之后拿到真号时由调用方
     *   用 [CarrierAccountIds.of] 重新算 id 并合并（见计划 §3.1）。
     *
     * 前缀 `h`/`r` 不是装饰：排查"为什么列表里有两个同一张卡"时，一眼能看出
     * 这条是 hash 出来的还是随机兜底的。
     */
    fun of(
        carrier: String,
        phone: String,
        random: () -> String = { UUID.randomUUID().toString().replace("-", "").take(12) },
    ): String {
        val p = phone.filter { it.isDigit() }
        // 2026-11-08：物联卡充值号可能只有 4~5 位；低于 4 位才随机兜底。
        return if (p.isBlank() || p.length < 4) {
            "r" + random()
        } else {
            "h" + sha256Hex(carrier.lowercase() + ":" + p).take(16)
        }
    }

    private fun sha256Hex(text: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}

/** 索引合并：同 id 覆盖，其余按创建时间排序（稳定排序，同刻按 id 兜底）。 */
fun CarrierAccountIndex.upsert(entry: CarrierAccountIndexEntry): CarrierAccountIndex =
    CarrierAccountIndex(
        entries = (entries.filterNot { it.id == entry.id } + entry)
            .sortedWith(compareBy({ it.createdAt }, { it.id }))
    )

/** 从索引里摘掉一个 id。 */
fun CarrierAccountIndex.without(id: String): CarrierAccountIndex =
    CarrierAccountIndex(entries.filterNot { it.id == id })

/** 账号 → 索引项（脱敏）。 */
fun StoredCarrierAccount.toIndexEntry(): CarrierAccountIndexEntry =
    CarrierAccountIndexEntry(
        id = id,
        carrier = carrier,
        accessMethod = accessMethod,
        iotProvider = if (accessMethod == "iot_card") iotProvider else "",
        phoneMasked = if (accessMethod == "iot_card") maskIotCardNo(phone) else maskCarrierPhone(phone),
        label = label,
        createdAt = createdAt,
    )

/** 账号 → 老的单账号结构（兼容层用）。 */
fun StoredCarrierAccount.toSession(): StoredCarrierSession =
    StoredCarrierSession(
        carrier = carrier,
        phone = phone,
        cookies = cookies,
        savedAt = savedAt,
        lastFetchedAt = lastFetchedAt,
    )

/**
 * 旧单账号数据该怎么处理。
 *
 * 单独建模的理由：这三种结局在磁盘上的动作**完全不同**，混在一个 `runCatching` 里
 * 正是老代码"解密失败就把会话删了"的来源。
 */
sealed interface LegacyMigrationPlan {
    /** 没有旧数据（全新安装 / 已迁移过）：只把 schema 标记写上。 */
    data object Empty : LegacyMigrationPlan

    /** 旧数据可读：迁移成账号 + 索引 + 活动指针，然后删旧 key。 */
    data class Migrate(
        val session: StoredCarrierSession,
        val autoConfig: CarrierAutoLoginConfig?,
    ) : LegacyMigrationPlan

    /**
     * 旧数据存在但**解不开**（Keystore 密钥失效、文件损坏）。
     *
     * 结局必须是「保留原数据 + 本次按未登录运行 + 下次启动再试」，**不能删**：
     * 删掉就把一次可恢复的读取失败升级成"用户所有账号永久失效"。
     */
    data object Retry : LegacyMigrationPlan

    /**
     * 密文解得开、但内容不是合法会话（JSON 坏 / 字段缺失）：数据已无救。
     * 这种才允许清掉旧 key —— 否则每次启动都失败、永远卡在未迁移状态。
     */
    data object Corrupt : LegacyMigrationPlan
}

/**
 * 迁移决策（纯函数）。
 *
 * @param legacyEncrypted 旧的 `session` 密文；null = 没有旧数据
 * @param legacyAutoEncrypted 旧的 `auto_login` 密文；可空
 * @param decrypt 解密函数；失败返回 null（**不要抛**，由调用方包 runCatching）
 * @param decodeSession JSON → 会话；失败返回 null
 * @param decodeAuto JSON → 自动登录配置；失败返回 null（配置坏了不影响会话迁移）
 */
fun planLegacyMigration(
    legacyEncrypted: String?,
    legacyAutoEncrypted: String?,
    decrypt: (String) -> String?,
    decodeSession: (String) -> StoredCarrierSession?,
    decodeAuto: (String) -> CarrierAutoLoginConfig?,
): LegacyMigrationPlan {
    if (legacyEncrypted == null) return LegacyMigrationPlan.Empty

    val plain = decrypt(legacyEncrypted) ?: return LegacyMigrationPlan.Retry
    val session = decodeSession(plain) ?: return LegacyMigrationPlan.Corrupt

    // 自动登录配置坏了不影响会话：迁移时丢掉它，用户重新开一次即可。
    val auto = legacyAutoEncrypted?.let { encrypted ->
        decrypt(encrypted)?.let(decodeAuto)
    }
    return LegacyMigrationPlan.Migrate(session, auto)
}
