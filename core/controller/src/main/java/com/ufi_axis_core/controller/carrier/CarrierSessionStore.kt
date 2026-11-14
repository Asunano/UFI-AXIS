package com.ufi_axis_core.controller.carrier

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import kotlinx.serialization.Serializable
import com.ufi_axis_core.util.AppLogger
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.security.KeyStore
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 运营商账号 + Cookie 的加密存储。
 *
 * ## 2026-11-05：单账号 → 多账号（P0）
 *
 * 对外**行为完全不变**：`load()/save()/clearSession()/clear()` 仍然只面对「当前账号」，
 * 所以 `CarrierBillService`、路由、App、Web 一行都不用改。变的是底下：
 *
 * ```
 * ufi_carrier（沿用同一个 prefs 文件）
 * ├── account_<id>   加密的 StoredCarrierAccount —— 一个账号一条，互不影响
 * ├── account_index  加密的 CarrierAccountIndex —— 列表只读它（脱敏号 + 别名）
 * ├── active_id      当前账号 id（无隐私，明文）
 * ├── auto_<id>      该账号的自动登录配置（加密）
 * ├── schema         int，1 = 已迁移到多账号结构
 * └── session / auto_login  旧单账号数据，迁移成功后删除
 * ```
 *
 * **为什么复用 `ufi_carrier` 而不新建 prefs 文件**：`BackupPrefsCoverageTest` 会扫 core 里每个
 * `getSharedPreferences` 调用里的 prefs 文件名并要求它显式表态（进备份白名单或进排除表）。
 * ⚠ 注意本行**不能**写成带引号的调用样式：那个测试是**扫文本**的，注释里的假调用也会被当成
 * 真名字收进去（2026-11-05 踩过：注释里写了一次，测试就报"漏登记 …"）。这份数据本来
 * 就属同一个隐私域，复用现有文件既不用动那张排除表，迁移的读写也天然就近。
 *
 * ## 迁移的三条铁律
 * 1. **解密失败不算数据坏**：旧实现 `load()` 一旦解密失败就 `remove(KEY_SESSION)` ——
 *    那等于把一次可恢复的读取失败升级成"用户所有会话永久失效"。现在改为保留原数据、
 *    本次按未登录运行、下次启动重试（见 [LegacyMigrationPlan.Retry]）。
 * 2. **可重入**：写账号 → 写索引 → 写活动指针 → 写自动登录 → 删旧 key → 最后写 schema。
 *    中途被杀，下次启动会从头再跑一遍；同一份数据算出的 id 恒定，重复写入幂等。
 * 3. **先写新的、后删旧的**：任何时刻磁盘上都至少有一份可读数据。
 *
 * 密钥别名 `ufi-carrier-session`；清除应用数据会一起删掉，等价于所有账号退出登录。
 */
class CarrierSessionStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    /** 进程内只迁移一次；迁移失败（[LegacyMigrationPlan.Retry]）时保持 false，下次再试。 */
    @Volatile
    private var migrated = false

    /** 迁移失败只记一条日志，避免每次 `load()` 都刷屏。 */
    private val migrationWarned = AtomicBoolean(false)

    private val lock = Any()

    // ─────────────────────── 兼容层（老 API，语义 = 当前账号）───────────────────────

    fun load(): StoredCarrierSession? {
        ensureMigrated()
        val id = activeId() ?: return null
        return account(id)?.toSession()
    }

    /**
     * 写入会话。
     *
     * - 与当前账号同一个（carrier + phone 相同）→ 更新 Cookie 与时间戳，保留别名与 id；
     * - 换了号（`switchAccount` 场景）→ **新建一个账号并设为当前**，老账号留在存储里
     *   （P0 的 UI 看不到它；P1 起它会出现在账号列表里，这正是多账号要的效果）。
     */
    fun save(session: StoredCarrierSession) {
        ensureMigrated()
        synchronized(lock) {
            val now = System.currentTimeMillis()
            val active = activeId()?.let { account(it) }
            val sameAccount = active != null &&
                active.carrier.equals(session.carrier, ignoreCase = true) &&
                active.phone == session.phone

            val target = if (sameAccount) {
                active.copy(
                    cookies = session.cookies,
                    savedAt = session.savedAt,
                    lastFetchedAt = session.lastFetchedAt,
                )
            } else {
                StoredCarrierAccount(
                    id = CarrierAccountIds.of(session.carrier, session.phone),
                    carrier = session.carrier,
                    phone = session.phone,
                    cookies = session.cookies,
                    savedAt = session.savedAt,
                    lastFetchedAt = session.lastFetchedAt,
                    createdAt = now,
                )
            }
            writeAccount(target)
            putActive(target.id)
            AppLogger.w("CarrierSession", "会话已落盘：id=${target.id} cookies=${target.cookies.size}")
        }
    }

    /** 只清 Cookie、保留账号（退出登录后 UI 仍能显示号码）。 */
    fun clearSession() {
        ensureMigrated()
        synchronized(lock) {
            val id = activeId() ?: return
            val current = account(id) ?: return
            writeAccount(current.copy(cookies = emptyList(), savedAt = System.currentTimeMillis()))
        }
    }

    /** 删除当前账号（含索引与自动登录配置）。路由的 `logout` 走的是 [clearSession]，不是这里。 */
    fun clear() {
        ensureMigrated()
        activeId()?.let { remove(it) }
    }

    /** 指定账号的自动登录配置（P1；老的无参版本 = 当前账号）。 */
    fun loadAutoConfigOf(accountId: String): CarrierAutoLoginConfig? {
        ensureMigrated()
        return readAutoConfig(accountId)
    }

    fun saveAutoConfigOf(accountId: String, config: CarrierAutoLoginConfig) {
        ensureMigrated()
        synchronized(lock) { writeAutoConfig(accountId, config) }
    }

    // ─────────────────────── 设备级账单策略 ───────────────────────

    /** 设备策略 key 是否存在（用于区分"没设置过"和"读取失败"）。 */
    fun hasBillSettings(): Boolean = prefs.contains(KEY_BILL_SETTINGS)

    /** 多账号迁移是否已完成；未完成时不要写任何"默认策略"，否则会覆盖待迁移数据。 */
    fun isMigrated(): Boolean {
        ensureMigrated()
        return prefs.getInt(KEY_SCHEMA, 0) >= SCHEMA_VERSION
    }

    /** 账单刷新 / 自动登录的设备策略；没有落盘（首次升级）返回 null，由 store 迁移。 */
    fun loadBillSettings(): CarrierBillSettings? {
        ensureMigrated()
        val encoded = prefs.getString(KEY_BILL_SETTINGS, null) ?: return null
        return runCatching {
            json.decodeFromString<CarrierBillSettings>(decrypt(encoded))
        }.onFailure {
            AppLogger.w("CarrierSession", "读取账单设置失败（本次不写盘）：${it.javaClass.simpleName}")
        }.getOrNull()
    }

    fun saveBillSettings(settings: CarrierBillSettings) {
        ensureMigrated()
        synchronized(lock) {
            val ok = prefs.edit()
                .putString(
                    KEY_BILL_SETTINGS,
                    encrypt(json.encodeToString(CarrierBillSettings.serializer(), settings)),
                )
                .commit()
            check(ok) { "账单设置写入失败" }
        }
    }

    fun loadAutoConfig(): CarrierAutoLoginConfig? {
        ensureMigrated()
        activeId()?.let { id -> readAutoConfig(id)?.let { return it } }
        // 兼容：还没有任何账号时（全新安装、用户先去配自动登录），旧 key 仍然可读。
        val legacy = prefs.getString(KEY_AUTO_LOGIN, null) ?: return null
        return runCatching {
            json.decodeFromString<CarrierAutoLoginConfig>(decrypt(legacy))
        }.getOrElse {
            AppLogger.w("CarrierSession", "读取自动登录配置失败，按默认关闭处理：${it.javaClass.simpleName}")
            null
        }
    }

    fun saveAutoConfig(config: CarrierAutoLoginConfig) {
        ensureMigrated()
        synchronized(lock) {
            val id = activeId()
            if (id != null) {
                writeAutoConfig(id, config)
            } else {
                val ok = prefs.edit()
                    .putString(
                        KEY_AUTO_LOGIN,
                        encrypt(json.encodeToString(CarrierAutoLoginConfig.serializer(), config)),
                    )
                    .commit()
                check(ok) { "自动登录配置写入失败" }
            }
        }
    }

    // ─────────────────────── 多账号 API（P1 起由路由使用）───────────────────────

    /** 账号列表（索引，按创建时间升序）。索引坏了会按 key 前缀重建。 */
    fun accounts(): List<CarrierAccountIndexEntry> {
        ensureMigrated()
        return index().entries
    }

    fun account(id: String): StoredCarrierAccount? {
        ensureMigrated()
        val encoded = prefs.getString(KEY_ACCOUNT_PREFIX + id, null) ?: return null
        return runCatching {
            json.decodeFromString<StoredCarrierAccount>(decrypt(encoded))
        }.getOrElse {
            // 单个账号坏了只影响它自己 —— 这正是"一个账号一条 key"的目的。
            AppLogger.w("CarrierSession", "账号 $id 解密失败：${it.javaClass.simpleName}")
            null
        }
    }

    fun activeId(): String? = prefs.getString(KEY_ACTIVE_ID, null)?.takeIf { it.isNotBlank() }

    fun setActive(id: String) {
        ensureMigrated()
        if (account(id) == null) return
        putActive(id)
    }

    /** 写入/覆盖一个账号并更新索引（幂等）。 */
    fun put(account: StoredCarrierAccount) {
        ensureMigrated()
        synchronized(lock) {
            writeAccount(account)
            writeIndex(index().upsert(account.toIndexEntry()))
        }
    }

    fun setLabel(id: String, label: String) {
        ensureMigrated()
        synchronized(lock) {
            val current = account(id) ?: return
            put(current.copy(label = label))
        }
    }

    /** 删除账号：账号体、索引、自动登录配置、活动指针一起清。 */
    fun remove(id: String) {
        ensureMigrated()
        synchronized(lock) {
            val wasActive = activeId() == id
            prefs.edit()
                .remove(KEY_ACCOUNT_PREFIX + id)
                .remove(KEY_AUTO_PREFIX + id)
                .remove(KEY_BILL_PREFIX + id)
                .commit()
            val next = index().without(id)
            writeIndex(next)
            if (wasActive) {
                // 活动指针顺延到列表下一个；没有就清空（回到未登录空态）。
                val fallback = next.entries.firstOrNull()?.id
                if (fallback != null) prefs.edit().putString(KEY_ACTIVE_ID, fallback).commit()
                else prefs.edit().remove(KEY_ACTIVE_ID).commit()
            }
        }
    }

    // ───────────────────────────── 迁移 ─────────────────────────────

    private fun ensureMigrated() {
        if (migrated) return
        synchronized(lock) {
            if (migrated) return
            if (prefs.getInt(KEY_SCHEMA, 0) >= SCHEMA_VERSION) {
                migrated = true
                return
            }
            val plan = planLegacyMigration(
                legacyEncrypted = prefs.getString(KEY_SESSION, null),
                legacyAutoEncrypted = prefs.getString(KEY_AUTO_LOGIN, null),
                decrypt = { encoded -> runCatching { decrypt(encoded) }.getOrNull() },
                decodeSession = { plain ->
                    runCatching { json.decodeFromString<StoredCarrierSession>(plain) }.getOrNull()
                },
                decodeAuto = { plain ->
                    runCatching { json.decodeFromString<CarrierAutoLoginConfig>(plain) }.getOrNull()
                },
            )
            when (plan) {
                LegacyMigrationPlan.Empty -> markSchema()
                LegacyMigrationPlan.Corrupt -> {
                    AppLogger.w("CarrierSession", "旧会话内容损坏，已清理（无可用数据）")
                    prefs.edit().remove(KEY_SESSION).remove(KEY_AUTO_LOGIN).commit()
                    markSchema()
                }
                LegacyMigrationPlan.Retry -> {
                    // 关键：**不写 schema、不删旧数据**，本次按未登录运行，下次启动重试。
                    if (migrationWarned.compareAndSet(false, true)) {
                        AppLogger.w("CarrierSession", "旧会话暂时解不开（Keystore 可能未就绪），保留数据待下次重试")
                    }
                    return
                }
                is LegacyMigrationPlan.Migrate -> applyMigration(plan)
            }
            migrated = true
        }
    }

    private fun applyMigration(plan: LegacyMigrationPlan.Migrate) {
        val session = plan.session
        val account = StoredCarrierAccount(
            id = CarrierAccountIds.of(session.carrier, session.phone),
            carrier = session.carrier,
            phone = session.phone,
            cookies = session.cookies,
            savedAt = session.savedAt,
            lastFetchedAt = session.lastFetchedAt,
            // 用旧会话的落盘时间当创建时间：升级后列表顺序与用户印象一致。
            createdAt = session.savedAt.takeIf { it > 0 } ?: System.currentTimeMillis(),
        )
        // 顺序固定：账号 → 索引 → 活动指针 → 自动登录 → 删旧 key → schema。
        // 中途被杀时旧 key 还在，下次从头再跑，重复写入幂等。
        writeAccount(account)
        writeIndex(index().upsert(account.toIndexEntry()))
        putActive(account.id)
        plan.autoConfig?.let { writeAutoConfig(account.id, it) }
        prefs.edit().remove(KEY_SESSION).remove(KEY_AUTO_LOGIN).commit()
        markSchema()
        AppLogger.w("CarrierSession", "已迁移为多账号结构：id=${account.id} cookies=${account.cookies.size}")
    }

    private fun markSchema() {
        prefs.edit().putInt(KEY_SCHEMA, SCHEMA_VERSION).commit()
    }

    // ─────────────────────────── 读写原子件 ───────────────────────────

    private fun index(): CarrierAccountIndex {
        val encoded = prefs.getString(KEY_INDEX, null) ?: return CarrierAccountIndex()
        return runCatching {
            json.decodeFromString<CarrierAccountIndex>(decrypt(encoded))
        }.getOrElse {
            // 索引坏了不能把账号一起判死刑：账号体还在，扫描前缀重建即可。
            AppLogger.w("CarrierSession", "账号索引损坏，按 key 前缀重建：${it.javaClass.simpleName}")
            rebuildIndex()
        }
    }

    private fun rebuildIndex(): CarrierAccountIndex {
        val ids = prefs.all.keys
            // account_index 本身也以 account_ 开头；不排除会把索引当成一个 id=index 的账号。
            .filter { it.startsWith(KEY_ACCOUNT_PREFIX) && it != KEY_INDEX }
            .map { it.removePrefix(KEY_ACCOUNT_PREFIX) }
        val entries = ArrayList<CarrierAccountIndexEntry>(ids.size)
        for (id in ids) {
            val account = account(id)
            if (account == null) {
                // 读不出的账号不能静默丢掉：旧索引虽然坏了，但至少还列着它。
                // 本次返回空索引（页面会显示无账号），保留旧数据，下次启动再试。
                AppLogger.w("CarrierSession", "账号索引重建中止：$id 读取失败，保留旧索引")
                return CarrierAccountIndex()
            }
            entries += account.toIndexEntry()
        }
        val rebuilt = CarrierAccountIndex(entries.sortedWith(compareBy({ it.createdAt }, { it.id })))
        writeIndex(rebuilt)
        AppLogger.w("CarrierSession", "账号索引已重建：${entries.size} 个账号")
        return rebuilt
    }

    private fun writeAccount(account: StoredCarrierAccount) {
        val plain = json.encodeToString(StoredCarrierAccount.serializer(), account)
        val ok = prefs.edit()
            .putString(KEY_ACCOUNT_PREFIX + account.id, encrypt(plain))
            .commit()
        check(ok) { "运营商账号写入失败" }
    }

    private fun writeIndex(index: CarrierAccountIndex) {
        val plain = json.encodeToString(CarrierAccountIndex.serializer(), index)
        val ok = prefs.edit().putString(KEY_INDEX, encrypt(plain)).commit()
        check(ok) { "运营商账号索引写入失败" }
    }

    private fun putActive(id: String) {
        prefs.edit().putString(KEY_ACTIVE_ID, id).commit()
    }

    private fun readAutoConfig(id: String): CarrierAutoLoginConfig? {
        val encoded = prefs.getString(KEY_AUTO_PREFIX + id, null) ?: return null
        return runCatching {
            json.decodeFromString<CarrierAutoLoginConfig>(decrypt(encoded))
        }.getOrElse {
            AppLogger.w("CarrierSession", "账号 $id 的自动登录配置读取失败：${it.javaClass.simpleName}")
            null
        }
    }

    // ─────────────────────── 账单缓存 ───────────────────────

    /** 最近一次成功拉取的账单；没有/解密失败返回 null（按"无缓存"处理，不影响实时拉取）。 */
    fun cachedBill(accountId: String): CachedCarrierBill? {
        ensureMigrated()
        val encoded = prefs.getString(KEY_BILL_PREFIX + accountId, null) ?: return null
        return runCatching {
            json.decodeFromString<CachedCarrierBill>(decrypt(encoded))
        }.onFailure {
            AppLogger.w("CarrierSession", "读取账单缓存失败($accountId)：${it.javaClass.simpleName}")
        }.getOrNull()
    }

    fun saveCachedBill(accountId: String, cached: CachedCarrierBill) {
        // 缓存是**增强**，不能反过来把实时账单拉取搞失败：
        // Keystore 暂时不可用/序列化异常都只记日志，调用方照常返回刚拉到的账单。
        runCatching {
            ensureMigrated()
            val plain = json.encodeToString(CachedCarrierBill.serializer(), cached)
            val ok = prefs.edit()
                .putString(KEY_BILL_PREFIX + accountId, encrypt(plain))
                .commit()
            if (!ok) AppLogger.w("CarrierSession", "账单缓存写入失败($accountId)")
        }.onFailure {
            AppLogger.w("CarrierSession", "账单缓存写入异常($accountId)：${it.javaClass.simpleName}: ${it.message}")
        }
    }

    fun removeCachedBill(accountId: String) {
        prefs.edit().remove(KEY_BILL_PREFIX + accountId).commit()
    }

    private fun writeAutoConfig(id: String, config: CarrierAutoLoginConfig) {
        val plain = json.encodeToString(CarrierAutoLoginConfig.serializer(), config)
        val ok = prefs.edit().putString(KEY_AUTO_PREFIX + id, encrypt(plain)).commit()
        check(ok) { "自动登录配置写入失败" }
    }

    // ─────────────────────────── 加解密 ───────────────────────────

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = cipher.iv
        val body = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(iv + body)
    }

    private fun decrypt(encoded: String): String {
        val all = Base64.getDecoder().decode(encoded)
        require(all.size > IV_BYTES) { "carrier session too short" }
        val iv = all.copyOfRange(0, IV_BYTES)
        val body = all.copyOfRange(IV_BYTES, all.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BITS, iv))
        return String(cipher.doFinal(body), Charsets.UTF_8)
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    private companion object {
        const val PREFS = "ufi_carrier"

        /** 旧单账号 key（迁移后删除）。 */
        const val KEY_SESSION = "session"
        const val KEY_AUTO_LOGIN = "auto_login"

        /** 多账号结构。 */
        const val KEY_SCHEMA = "schema"
        const val KEY_INDEX = "account_index"
        const val KEY_ACTIVE_ID = "active_id"
        const val KEY_ACCOUNT_PREFIX = "account_"
        const val KEY_AUTO_PREFIX = "auto_"
        const val KEY_BILL_PREFIX = "bill_"

        /** 设备级账单策略（刷新 / 自动登录 / 每日短信）。 */
        const val KEY_BILL_SETTINGS = "bill_settings"

        /** 1 = 多账号结构。将来再加字段就 +1，并在 `ensureMigrated` 里补一段。 */
        const val SCHEMA_VERSION = 1

        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "ufi-carrier-session"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}
