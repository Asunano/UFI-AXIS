package com.ufi_axis_core.util

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 配对设备记录。
 *
 * 主键 = fingerprint，**由服务端据 [pubKey] 计算**（`DeviceAuth.fingerprintOf`），
 * 绝不采信客户端自报的指纹字符串 —— 否则指纹与公钥可以不匹配，攻击者能拿自己的密钥
 * 冒充别人的记录。
 *
 * @property hwId 客户端上报的**硬件级稳定标识**（App=ANDROID_ID 派生；Web 恒为空串）。
 *   **仅用于合并同一台硬件换指纹后产生的重复记录，不参与任何安全判定**。
 *   它是客户端可任意伪造的明文字段，2026-08-28 已移除它对配对配额检查的旁路。
 *   默认空串：Web 与旧记录都是空串，空串永不参与匹配（见 [PairedDeviceStore.findByHwId]）。
 * @property pubKey 设备身份公钥，X.509 SPKI DER 的 base64。每请求签名的验签依据。
 *   空串 = 旧记录（部署严格设备独立性之前配对的），**无法通过鉴权，必须重新配对**。
 * @property tokenHash 该设备独占 token 的 SHA-256 十六进制小写。
 *   token 明文不落盘：即便 `paired_devices.json` 泄漏也拿不到可用凭据。
 *   空串 = 尚未签发（同上，需重新配对）。
 */
@Serializable
data class PairedDeviceRecord(
    val fingerprint: String,
    val deviceName: String = "",
    val lastSeen: Long = 0,
    val createdAt: Long = 0,
    val hwId: String = "",
    val pubKey: String = "",
    val tokenHash: String = ""
)

/**
 * 配对设备存储：应用私有目录 `context.filesDir/paired_devices.json`。
 *
 * - 提供记录级结构（设备名/时间戳），替代旧的逗号分隔 fingerprint 字符串。
 * - 迁移：首次构造且文件不存在、但 [AppSettings.pairedFingerprints] 有旧数据时，
 *   为每个 fingerprint 生成记录（deviceName=fingerprint），随后以文件为准。
 * - 写入统一收敛到 [PairingManager]（AppSettings 指纹列表双写兼容），本类不直接依赖管理器。
 * - 文件损坏时按空列表兜底并记日志，避免服务启动失败。
 */
class PairedDeviceStore(
    private val context: Context,
    private val settings: AppSettings
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val lock = Any()

    private val file: File
        get() = File(context.filesDir, FILE_NAME)

    // 内存缓存：读操作先命中缓存，写操作更新缓存并落盘
    private var records: List<PairedDeviceRecord> = emptyList()

    /** [touch] 上次落盘时刻（落盘节流，见 [touch]）。 */
    private var lastTouchFlushMs: Long = 0

    /**
     * [touch] 的异步落盘线程（单线程 + daemon）。
     * 只服务 touch：其余写操作（upsert/remove/rename/clear）都是用户可感知的动作，
     * 必须同步落盘保证「操作完成即已持久化」。
     */
    private val flushExecutor: java.util.concurrent.ExecutorService =
        java.util.concurrent.Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "PairedDeviceStore-flush").apply { isDaemon = true }
        }

    init {
        synchronized(lock) {
            records = loadInternal()
            // 迁移旧版 pairedFingerprints（仅当文件不存在且旧数据非空；文件存在则以文件为准）
            if (!file.exists() && settings.pairedFingerprints.isNotEmpty()) {
                migrateFromSettings()
            }
        }
    }

    /** 从磁盘重新加载（一般仅在测试/外部改动后调用）。 */
    fun load() {
        synchronized(lock) {
            records = loadInternal()
        }
    }

    /** 强制落盘当前缓存。 */
    fun save() {
        synchronized(lock) {
            saveInternal()
        }
    }

    /** 返回全部配对记录（副本，避免外部修改内部缓存）。 */
    fun list(): List<PairedDeviceRecord> = synchronized(lock) { records.toList() }

    /**
     * 写入/刷新一条记录；已存在则保留 createdAt 并刷新 deviceName/lastSeen。
     *
     * 空串参数一律表示「本次不更新该字段」，保留已有值。这条规则对 [pubKey] / [tokenHash]
     * 尤其重要：像 [rename] 这类只改一个字段的调用不能把设备凭据抹掉。
     *
     * @param deviceName 设备名；传空串**保留**已有值，无已有值时回退为 fingerprint。
     * @param hwId 硬件标识；传空串**保留**已有值，避免一次不带 hwId 的登录把已知标识抹成空、
     *   导致后续 [findByHwId] 去重失效。
     * @param pubKey 设备身份公钥（SPKI base64）；空串保留原值。
     * @param tokenHash 该设备 token 的 SHA-256；空串保留原值。
     */
    fun upsert(
        fingerprint: String,
        deviceName: String = "",
        lastSeen: Long,
        hwId: String = "",
        pubKey: String = "",
        tokenHash: String = ""
    ) {
        synchronized(lock) {
            val existing = records.firstOrNull { it.fingerprint == fingerprint }
            val record = PairedDeviceRecord(
                fingerprint = fingerprint,
                deviceName = deviceName.ifBlank { existing?.deviceName.orEmpty() }.ifBlank { fingerprint },
                lastSeen = lastSeen,
                createdAt = existing?.createdAt?.takeIf { it > 0 } ?: lastSeen,
                hwId = hwId.ifBlank { existing?.hwId.orEmpty() },
                pubKey = pubKey.ifBlank { existing?.pubKey.orEmpty() },
                tokenHash = tokenHash.ifBlank { existing?.tokenHash.orEmpty() }
            )
            records = (records.filterNot { it.fingerprint == fingerprint } + record)
                .sortedBy { it.createdAt }
            saveInternal()
        }
    }

    /**
     * 刷新设备活跃时间（鉴权成功后每请求调用）。
     *
     * 与 [upsert] 的区别是**落盘节流 + 异步落盘**：`lastSeen` 只用于配对界面展示"最近活跃"，
     * 每个 API 请求都写一次 `paired_devices.json` 会在手机闪存上造成完全无意义的写放大。
     * 这里内存立即更新，磁盘最多每 [TOUCH_FLUSH_INTERVAL_MS] 写一次，且**不在调用线程上写** ——
     * 这条路径跑在 Netty 的单 worker 线程上，任何阻塞 IO 都会直接变成所有请求的延迟抖动。
     * 进程被杀导致最后几十秒的 lastSeen 丢失是完全可接受的。
     */
    fun touch(fingerprint: String, nowMs: Long = System.currentTimeMillis()) {
        var needFlush = false
        synchronized(lock) {
            val index = records.indexOfFirst { it.fingerprint == fingerprint }
            if (index < 0) return
            val mutable = records.toMutableList()
            mutable[index] = mutable[index].copy(lastSeen = nowMs)
            records = mutable
            if (nowMs - lastTouchFlushMs >= TOUCH_FLUSH_INTERVAL_MS) {
                lastTouchFlushMs = nowMs
                needFlush = true
            }
        }
        if (needFlush) {
            flushExecutor.execute { synchronized(lock) { saveInternal() } }
        }
    }

    /**
     * 按 token 哈希查找设备 —— 每请求鉴权的入口（[com.ufi_axis_core.api.middleware.AuthMiddleware]）。
     *
     * 空/空白 tokenHash 一律不匹配：旧记录的 tokenHash 是空串，若参与匹配，
     * 一个不带 Authorization 头的请求就会"匹配"上旧记录。
     */
    fun findByTokenHash(tokenHash: String): PairedDeviceRecord? = synchronized(lock) {
        if (tokenHash.isBlank()) return@synchronized null
        records.firstOrNull { it.tokenHash == tokenHash && it.tokenHash.isNotBlank() }
    }

    /** 按指纹查找。 */
    fun findByFingerprint(fingerprint: String): PairedDeviceRecord? = synchronized(lock) {
        if (fingerprint.isBlank()) return@synchronized null
        records.firstOrNull { it.fingerprint == fingerprint }
    }

    /**
     * 按硬件标识查找记录（去重合并入口）。
     *
     * 空/空白 hwId 一律不匹配——历史记录（部署本特性前配对的设备）hwId 为空串，
     * 若参与匹配会把所有旧记录误判为“同一台硬件”而错误合并。
     *
     * @return 首条 hwId 相同且非空的记录；无匹配返回 null。
     */
    fun findByHwId(hwId: String): PairedDeviceRecord? = synchronized(lock) {
        if (hwId.isBlank()) return@synchronized null
        records.firstOrNull { it.hwId == hwId && it.hwId.isNotBlank() }
    }

    /** 删除一条记录；存在并删除返回 true。 */
    fun remove(fingerprint: String): Boolean = synchronized(lock) {
        val before = records.size
        records = records.filterNot { it.fingerprint == fingerprint }
        val removed = records.size != before
        if (removed) saveInternal()
        removed
    }

    /** 重命名一条记录；存在返回 true。 */
    fun rename(fingerprint: String, newName: String): Boolean = synchronized(lock) {
        val index = records.indexOfFirst { it.fingerprint == fingerprint }
        if (index < 0) return@synchronized false
        val updated = records[index].copy(deviceName = newName.ifBlank { fingerprint })
        val mutable = records.toMutableList()
        mutable[index] = updated
        records = mutable
        saveInternal()
        true
    }

    /** 全部指纹列表。 */
    fun fingerprints(): List<String> = synchronized(lock) { records.map { it.fingerprint } }

    /** 是否没有任何配对记录。 */
    fun isEmpty(): Boolean = synchronized(lock) { records.isEmpty() }

    /** 清空全部记录（解除全部配对时调用）。 */
    fun clear() {
        synchronized(lock) {
            records = emptyList()
            saveInternal()
        }
    }

    /** 读取系统设备名（Settings.Global.DEVICE_NAME），读不到/为空返回空串（由调用方回退 deviceId）。 */
    fun systemDeviceName(): String {
        return runCatching {
            android.provider.Settings.Global.getString(
                context.contentResolver,
                android.provider.Settings.Global.DEVICE_NAME
            )
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: ""
    }

    private fun loadInternal(): List<PairedDeviceRecord> {
        if (!file.exists()) return emptyList()
        return try {
            val text = file.readText()
            if (text.isBlank()) emptyList() else json.decodeFromString<List<PairedDeviceRecord>>(text)
        } catch (e: Exception) {
            AppLogger.w(TAG, "paired_devices.json 损坏，按空列表兜底: ${e.message}")
            emptyList()
        }
    }

    private fun saveInternal() {
        try {
            val text = json.encodeToString(records)
            file.parentFile?.mkdirs()
            file.writeText(text)
        } catch (e: Exception) {
            AppLogger.e(TAG, "保存 paired_devices.json 失败: ${e.message}", e)
        }
    }

    private fun migrateFromSettings() {
        val now = System.currentTimeMillis()
        val pairedAt = settings.pairedAt
        val base = if (pairedAt > 0) pairedAt else now
        val migrated = settings.pairedFingerprints.map { fp ->
            PairedDeviceRecord(
                fingerprint = fp,
                deviceName = fp,
                lastSeen = base,
                createdAt = base
            )
        }
        if (migrated.isNotEmpty()) {
            records = migrated
            saveInternal()
            AppLogger.i(TAG, "已从旧 pairedFingerprints 迁移 ${migrated.size} 台设备记录到 paired_devices.json")
        }
    }

    companion object {
        private const val TAG = "PairedDeviceStore"
        private const val FILE_NAME = "paired_devices.json"

        /** [touch] 的落盘节流间隔。 */
        private const val TOUCH_FLUSH_INTERVAL_MS = 30_000L
    }
}
