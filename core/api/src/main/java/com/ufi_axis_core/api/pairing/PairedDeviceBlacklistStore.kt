package com.ufi_axis_core.api.pairing

import android.content.Context
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

/**
 * 一条被拉黑的设备记录。
 *
 * @param fingerprint 设备公钥指纹（服务端算的 `base64url(SHA-256(SPKI))`）—— **主键**。
 *   不可伪造，跨 IP / 重连稳定；代价是清 App 数据后会变成一把新指纹（那是刻意设计，
 *   见 `DeviceKeyStore` 的说明：不可伪造与"清数据后仍认得出"天然互斥）。
 * @param hwId 客户端自报的硬件标识（`device_hwid`，ANDROID_ID 派生）—— **辅助键**。
 *   它跨重装稳定，但**是可伪造的明文**，所以只用来"提高绕过门槛"，**绝不能当授权依据**。
 *   Web 端恒为空串，空值不建索引（否则所有 Web 会话会撞在一起）。
 */
data class BlockedDevice(
    val fingerprint: String,
    val hwId: String = "",
    val name: String = "",
    val reason: String = "",
    val blockedAt: Long = 0L,
    val blockedBy: String = ""
)

/**
 * 配对设备黑名单（2026-10-15）。
 *
 * ## 它解决什么
 * 机主想把某台设备**彻底挡在外面**：不能重新配对、不能用密码登录、在线也要被踢掉。
 * 配对与登录是同一条链路（都打 `POST /pairing/confirm`），所以"两个不能"只需一个拦截点。
 *
 * ## 为什么另起一份 store，而不是塞进 PairedDeviceStore
 * 两者语义正交、生命周期也不同：
 * - `PairedDeviceStore` 记的是"**谁被允许进来**"，删除一条 = 撤销授权；
 * - 本 store 记的是"**谁被明确拒绝**"，它的存在本身就是结论。
 * 把拉黑做成"已配对记录上的一个标记"会有一个致命问题：**移除设备时会把拉黑一起删掉**，
 * 于是"踢掉它"顺手就把"禁止它回来"取消了 —— 那不是机主的意思。
 * 所以两份数据独立，删除设备**不会**解除拉黑（解除必须显式操作）。
 *
 * ## 为什么读是同步的
 * 消费者是鉴权热路径（`PairingManager.confirm` / `DeviceRequestVerifier.verify`），
 * 那里不能 suspend。所以维护一份内存快照：写入时更新，读取直接拿 —— 与
 * [com.ufi_axis_core.api.media.MediaExclusionStore] 同一套做法。
 *
 * ## 为什么有上限
 * [MAX_ENTRIES] = 200 是**产品约束**：名单不该无限膨胀（真到 200 台，该考虑的是
 * 换密码 / 关掉配对，而不是继续点拉黑）。超限时新条目被丢弃，接口会回"名单已满"。
 *
 * ## 为什么键是"指纹 + hwId"两条
 * 单靠指纹：清 App 数据就能绕过（新指纹 = 新设备）。
 * 单靠 hwId：它是明文自报、可任意伪造，且 Web 端没有。
 * 两条一起用：**普通用户清数据后仍被挡住**（hwId 命中），**改包伪造 hwId 的人仍被指纹挡住**
 * （如果它没换密钥）。这是"提高门槛"，不是"绝对拦得住"——文案里不许承诺后者。
 */
class PairedDeviceBlacklistStore(context: Context) {

    companion object {
        /** 名单上限。见类注释「为什么有上限」。 */
        const val MAX_ENTRIES = 200

        private const val PREFS_NAME = "paired_blacklist"
        private const val KEY_ENTRIES = "blocked_devices_json"

        /** 指纹在列表里的展示前缀长度（日志/事件文案用，避免整串指纹刷屏）。 */
        const val FINGERPRINT_PREFIX_LEN = 4
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * 写入互斥。加它是因为"读-改-写"：两个管理端同时拉黑不同设备，
     * 没有锁就会有一份改动被另一份的全量覆盖吃掉（与 `MediaExclusionStore` 同一条理由）。
     */
    private val mutex = Mutex()

    /** 内存快照。见类注释「为什么读是同步的」。 */
    @Volatile
    private var snapshot: List<BlockedDevice> = load()

    /** 指纹索引（`@Volatile` 保证与 [snapshot] 一起被看见）。 */
    @Volatile
    private var byFingerprint: Set<String> = snapshot.map { it.fingerprint }.toSet()

    /** hwId 索引；空串不入索引。 */
    @Volatile
    private var byHwId: Set<String> = snapshot.map { it.hwId }.filter { it.isNotBlank() }.toSet()

    private var onChanged: (suspend () -> Unit)? = null

    /**
     * 这台设备是否被拉黑。**鉴权热路径同步调用**。
     *
     * 判定顺序：指纹优先（权威），再兜 hwId（辅助，只在非空时）。
     * 任一中即视为命中 —— 见类注释「为什么键是指纹 + hwId 两条」。
     */
    fun isBlocked(fingerprint: String?, hwId: String? = null): Boolean {
        if (!fingerprint.isNullOrBlank() && fingerprint in byFingerprint) return true
        val hw = hwId?.trim().orEmpty()
        return hw.isNotEmpty() && hw in byHwId
    }

    /** 当前名单（按拉黑时间倒序，最新的在前）。 */
    fun entries(): List<BlockedDevice> = snapshot

    /** 名单是否已满。UI 据此提示，而不是让用户点了之后发现没生效。 */
    fun isFull(): Boolean = snapshot.size >= MAX_ENTRIES

    /**
     * 拉黑一台设备（幂等）。
     *
     * @return true = 新增了一条；false = 已在名单里（此时只刷新 name/reason，不改变拉黑时间）。
     * @throws IllegalStateException 名单已满（调用方映射成 409 + 明确文案）。
     */
    suspend fun block(device: BlockedDevice): Boolean = mutex.withLock {
        val existing = snapshot.firstOrNull { it.fingerprint == device.fingerprint }
        if (existing != null) {
            // 已拉黑：只更新展示信息，**不动 blockedAt**（否则"拉黑时间"会随每次点击漂移）
            val updated = existing.copy(
                name = device.name.ifBlank { existing.name },
                reason = device.reason.ifBlank { existing.reason }
            )
            if (updated != existing) persist(snapshot.map { if (it.fingerprint == device.fingerprint) updated else it })
            return@withLock false
        }
        if (snapshot.size >= MAX_ENTRIES) throw IllegalStateException("blacklist_full")
        persist(snapshot + device)
        true
    }

    /** 解除拉黑。@return true = 确实移除了一条。 */
    suspend fun unblock(fingerprint: String): Boolean = mutex.withLock {
        val next = snapshot.filterNot { it.fingerprint == fingerprint }
        if (next.size == snapshot.size) return@withLock false
        persist(next)
        true
    }

    /** 全部解除。@return 被移除的条数。 */
    suspend fun clear(): Int = mutex.withLock {
        val size = snapshot.size
        if (size > 0) persist(emptyList())
        size
    }

    /**
     * 名单变化时的广播钩子（WebSocket `data_changed`）。
     * 与 `MediaExclusionStore.attachChangeBroadcaster` 同一套做法：store 不认识 wsManager，
     * 由组装层把回调塞进来。
     */
    fun attachChangeBroadcaster(block: suspend () -> Unit) {
        onChanged = block
    }

    /** 落盘 + 刷新快照 + 广播。写盘在锁内（理由同 `MediaExclusionStore.persist`）。 */
    private suspend fun persist(value: List<BlockedDevice>) {
        val arr = JSONArray()
        value.forEach { d ->
            arr.put(
                JSONObject().apply {
                    put("fingerprint", d.fingerprint)
                    put("hw_id", d.hwId)
                    put("name", d.name)
                    put("reason", d.reason)
                    put("blocked_at", d.blockedAt)
                    put("blocked_by", d.blockedBy)
                }
            )
        }
        prefs.edit().putString(KEY_ENTRIES, arr.toString()).apply()
        applySnapshot(value)
        onChanged?.invoke()
    }

    private fun applySnapshot(value: List<BlockedDevice>) {
        // 排序只在这一处：内存快照与落盘后重新加载的顺序必须一致，否则"列表顺序"
        // 会随"这次是刚写的还是刚读的"而变（首测就踩到：写完再读顺序不同）。
        val ordered = value.sortedByDescending { it.blockedAt }
        snapshot = ordered
        byFingerprint = ordered.map { it.fingerprint }.toSet()
        byHwId = ordered.map { it.hwId }.filter { it.isNotBlank() }.toSet()
    }

    private fun load(): List<BlockedDevice> {
        val raw = prefs.getString(KEY_ENTRIES, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length())
                .mapNotNull { i ->
                    val o = arr.optJSONObject(i) ?: return@mapNotNull null
                    val fp = o.optString("fingerprint").trim()
                    if (fp.isEmpty()) return@mapNotNull null
                    BlockedDevice(
                        fingerprint = fp,
                        hwId = o.optString("hw_id").trim(),
                        name = o.optString("name").trim(),
                        reason = o.optString("reason").trim(),
                        blockedAt = o.optLong("blocked_at", 0L),
                        blockedBy = o.optString("blocked_by").trim()
                    )
                }
                .take(MAX_ENTRIES)
                .sortedByDescending { it.blockedAt }
        }.getOrDefault(emptyList())
    }
}
