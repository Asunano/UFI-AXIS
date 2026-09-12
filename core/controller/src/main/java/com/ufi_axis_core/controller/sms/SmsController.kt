package com.ufi_axis_core.controller.sms

import android.content.Context
import android.net.Uri
import com.ufi_axis_core.controller.goform.GoformSmsClient
import com.ufi_axis_core.core.database.SmsReadState
import com.ufi_axis_core.core.database.SmsReadStateDao
import com.ufi_axis_core.core.database.SmsVerificationCode
import com.ufi_axis_core.core.database.SmsVerificationCodeDao
import com.ufi_axis_core.util.AppLogger
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*


/**
 * 短信控制器 —— 读取走 ContentResolver（学 UFI-TOOLS），写操作走 Goform 短会话。
 *
 * 2026-08-22 数据源架构（修复"登录官方后台被挤下线"）：
 * - 读取（轮询/列表/联系人/计数）：ContentResolver（content://sms + READ_SMS 系统权限）
 *   为第一数据源——零 goform 请求、零会话，短信轮询（20-60s 周期）不再登录设备后台；
 * - goform 裸读（免鉴权、不登录）仅作 ContentResolver 不可用时的兜底；
 * - 发送/删除：goform 写操作（短会话 LOGIN→写→LOGOUT，用户显式操作，槽位占用毫秒级）；
 * - 已读状态：本地 sms_read_state 表为唯一状态源（同原架构）。
 *
 * ID 体系：ContentResolver 路径返回系统 _id；goform 兜底路径返回 goform id。
 * 删除跨体系时按 address+date 在 goform 列表中匹配定位。
 */
class SmsController(
    private val context: Context? = null,
    private val smsClient: GoformSmsClient? = null,
    private val smsReadStateDao: SmsReadStateDao? = null,
    private val vcDao: SmsVerificationCodeDao? = null,
    /**
     * 拦截判定（2026-09-08）。读路径**只过滤、不写拦截记录** ——
     * 记录只由三条写路径（邮件 / WS 推送 / 验证码入库）产生，否则每次下拉刷新列表
     * 都会刷出一批重复记录，命中次数也会变成「你刷了几次列表」。
     *
     * null（降级装配）= 不过滤，与拦截功能上线前的行为一致。
     */
    private val ruleStore: SmsRuleStore? = null
) {
    private val tag = "SmsController"

    data class SmsMessage(val id: Long, val address: String, val body: String, val date: Long, val read: Boolean, val direction: String)
    data class SendResult(val success: Boolean, val message: String)

    // ═══════════ ContentResolver 读取（主路径，零 goform 请求） ═══════════

    /**
     * 系统 SMS Provider 读取。返回 null 表示不可用（无权限/异常），由调用方走 goform 兜底；
     * 返回空列表表示确实无短信（不再兜底）。
     */
    private fun readSmsLocal(folder: String?, limit: Int, offset: Int = 0, phone: String? = null): List<SmsMessage>? {
        val ctx = context ?: return null
        return try {
            val uri = when (folder) {
                "inbox" -> Uri.parse("content://sms/inbox")
                "sent" -> Uri.parse("content://sms/sent")
                else -> Uri.parse("content://sms")
            }
            val selection = if (phone.isNullOrBlank()) null else "address=?"
            val args = if (phone.isNullOrBlank()) null else arrayOf(phone)
            val msgs = ArrayList<SmsMessage>()
            ctx.contentResolver.query(
                uri, arrayOf("_id", "address", "body", "date", "type"),
                selection, args, "date DESC"
            )?.use { c ->
                var skipped = 0
                while (c.moveToNext() && msgs.size < limit) {
                    if (skipped < offset) { skipped++; continue }
                    msgs.add(
                        SmsMessage(
                            id = c.getLong(0),
                            address = c.getString(1) ?: "",
                            body = c.getString(2) ?: "",
                            date = c.getLong(3),
                            read = true, // 已读状态统一由本地 DB merge（与 goform 路径一致）
                            direction = smsDirectionFromType(c.getInt(4))
                        )
                    )
                }
            } ?: return null
            msgs
        } catch (e: Exception) {
            AppLogger.w(tag, "ContentResolver SMS read failed: ${e.message}")
            null
        }
    }

    /** 系统短信计数（null=不可用）。 */
    private fun countSmsLocal(phone: String? = null): Int? {
        val ctx = context ?: return null
        return try {
            val selection = if (phone.isNullOrBlank()) null else "address=?"
            val args = if (phone.isNullOrBlank()) null else arrayOf(phone)
            ctx.contentResolver.query(Uri.parse("content://sms"), arrayOf("_id"), selection, args, null)
                ?.use { it.count }
        } catch (e: Exception) {
            AppLogger.w(tag, "ContentResolver SMS count failed: ${e.message}")
            null
        }
    }

    /**
     * 系统短信里**被拦截规则命中**的条数（null=不可用）。
     *
     * 为什么需要它：`getUnreadCount` 能靠 DB 解决（被拦的短信入库时就标了已读），
     * 但「总数」的真源是系统 Provider，而关键词规则必须有正文才能判 —— DB 里没有正文。
     * 所以只能真的扫一遍 `address + body` 两列。
     *
     * 代价可控：只在**存在启用规则时**才调用（[getTotalCount] / [getFilteredCount] 先短路），
     * 而这两个方法挂在 `/api/sms/count` 这类按需 REST 上，不在 5s 轮询路径里。
     */
    private fun countBlockedLocal(phone: String? = null): Int? {
        val ctx = context ?: return null
        val store = ruleStore ?: return 0
        return try {
            val selection = if (phone.isNullOrBlank()) null else "address=?"
            val args = if (phone.isNullOrBlank()) null else arrayOf(phone)
            ctx.contentResolver.query(
                Uri.parse("content://sms"), arrayOf("address", "body"), selection, args, null
            )?.use { c ->
                var blocked = 0
                while (c.moveToNext()) {
                    if (store.isBlocked(c.getString(0) ?: "", c.getString(1) ?: "")) blocked++
                }
                blocked
            }
        } catch (e: Exception) {
            AppLogger.w(tag, "ContentResolver blocked-count failed: ${e.message}")
            null
        }
    }

    suspend fun getAll(limit: Int = 50, offset: Int = 0, phone: String? = null): List<SmsMessage> = readSms(null, limit, offset, phone)
    suspend fun getInbox(limit: Int = 50, offset: Int = 0, phone: String? = null): List<SmsMessage> = readSms("inbox", limit, offset, phone)
    suspend fun getSent(limit: Int = 50, offset: Int = 0, phone: String? = null): List<SmsMessage> = readSms("sent", limit, offset, phone)

    /**
     * **不经拦截过滤**的原始读取，供三条**写**路径使用（`DataScheduler` 的未读标记 /
     * WS 推送 / 验证码扫描）。
     *
     * 为什么必须有这个口子：写路径的职责恰恰是「看到被拦的短信，然后决定不推、不入库、
     * 标成已读，并写一条拦截记录」。如果它们也走过滤后的列表，被拦的短信对它们**根本不存在** ——
     * 表现是拦截确实生效了，但「已拦截」列表永远是空的，用户完全无法自查自己漏了什么。
     * （这个坑很隐蔽：过滤加在唯一读聚合点上看起来最干净，却顺手把记录来源也一起掐了。）
     *
     * 展示与计数路径**不要**用它 —— 用 [getAll] / [getInbox] / [getSent] / [getContactList]。
     *
     * @param fallbackOnEmpty ContentResolver **读到空列表**时是否再问一次 goform。
     *   默认 false（列表/联系人等读路径保持原样：空就是空，绝不为此登录设备后台）；
     *   邮件转发的扫窗口链路传 true —— 纯 goform 设备（短信只存在于 modem、不进系统
     *   Provider）上 Provider 永远是空的，不兜底就等于邮件功能完全不工作。
     */
    suspend fun getAllUnfiltered(limit: Int = 50, fallbackOnEmpty: Boolean = false): List<SmsMessage> =
        readSmsRaw(null, limit, fallbackOnEmpty = fallbackOnEmpty)

    /**
     * 按号码过滤的总数。
     *
     * 与 [readSms] **同口径**（都扣掉被拦截的）：不同口径的表现是界面上
     * 「总数 50 却只列出 48 条」，用户会以为分页坏了。
     */
    suspend fun getFilteredCount(phone: String?): Int {
        if (phone.isNullOrBlank()) return getTotalCount()
        countSmsLocal(phone)?.let { total ->
            if (ruleStore?.hasActiveRules() != true) return total
            return (total - (countBlockedLocal(phone) ?: 0)).coerceAtLeast(0)
        }
        // goform 兜底
        try {
            smsClient?.let { gc ->
                val smsData = gc.getSmsList(page = 0, perPage = 200) ?: return@let
                val arr = smsData["messages"]?.jsonArray ?: return@let
                val store = ruleStore
                return arr.count { el ->
                    val obj = el.jsonObject
                    if ((obj["number"]?.jsonPrimitive?.contentOrNull) != phone) return@count false
                    if (store?.hasActiveRules() != true) return@count true
                    // 与读列表同口径：拦截判定要正文，所以这里也得解一次 base64
                    val content = decodeB64(
                        obj["content"]?.jsonPrimitive?.contentOrNull ?: "",
                        obj["encode_type"]?.jsonPrimitive?.contentOrNull ?: "0"
                    )
                    !store.isBlocked(phone, content)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.w(
                tag,
                "getFilteredCount goform fallback failed, returning 0: " +
                    "${e.javaClass.simpleName}: ${e.message}"
            )

        }
        return 0
    }


    /**
     * 联系人聚合列表（ContentResolver 优先，已读状态从本地 DB 合并）。
     *
     * 拦截过滤由 [readSms] 统一完成 —— 这里不能不过滤：被拉黑的号码如果还留在联系人列表里，
     * 会变成一个「点进去什么都没有」的空会话，比不过滤更让人困惑。
     * 而 `unread` 是对过滤后的消息做内存聚合，所以联系人未读数与总未读数天然一致。
     */
    suspend fun getContactList(): List<Map<String, Any>> {
        try {
            val msgs = readSms(null, 100, 0, null)
            if (msgs.isNotEmpty()) {
                val merged = mergeReadState(msgs)
                return merged.groupBy { it.address }
                    .mapValues { (_, ms) ->
                        val latest = ms.maxByOrNull { it.date }
                        mapOf<String, Any>(
                            "phoneNumber" to (latest?.address ?: "unknown"),
                            "total" to ms.size,
                            "unread" to ms.count { !it.read && it.direction == "received" },
                            "latestMsg" to (latest?.body ?: ""),
                            "latestTimestamp" to (latest?.date ?: 0L),
                            "latestDirection" to (latest?.direction ?: "received")
                        )
                    }
                    .values.sortedByDescending { it["latestTimestamp"] as Long }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.w(tag, "getContactList failed, returning empty list: ${e.javaClass.simpleName}: ${e.message}")
        }
        return emptyList()
    }


    suspend fun getById(id: Long): SmsMessage? {
        // ① ContentResolver 单条查询（id = 系统 _id）
        try {
            val ctx = context
            if (ctx != null) {
                ctx.contentResolver.query(
                    Uri.parse("content://sms"), arrayOf("_id", "address", "body", "date", "type"),
                    "_id=?", arrayOf(id.toString()), null
                )?.use { c ->
                    if (c.moveToFirst()) {
                        val msg = SmsMessage(
                            id = c.getLong(0),
                            address = c.getString(1) ?: "",
                            body = c.getString(2) ?: "",
                            date = c.getLong(3),
                            read = true,
                            direction = smsDirectionFromType(c.getInt(4))
                        )
                        return mergeReadState(listOf(msg)).firstOrNull()
                    }
                }
            }
        } catch (e: Exception) {
            AppLogger.w(tag, "getById via ContentResolver failed: ${e.message}")
        }
        // ② goform 兜底（id = goform 消息 id）
        try {
            smsClient?.let { gc ->
                val sms = gc.getSmsList(perPage = 200) ?: return@let
                val arr = sms["messages"]?.jsonArray ?: return@let
                for (el in arr) {
                    val obj = el.jsonObject
                    val mid = obj["id"]?.jsonPrimitive?.longOrNull ?: continue
                    if (mid == id) {
                        val number = obj["number"]?.jsonPrimitive?.contentOrNull ?: ""
                        val content = decodeB64(obj["content"]?.jsonPrimitive?.contentOrNull ?: "", obj["encode_type"]?.jsonPrimitive?.contentOrNull ?: "0")
                        val dateStr = obj["date"]?.jsonPrimitive?.contentOrNull ?: ""
                        val date = parseSmsDate(dateStr)
                        val tg = obj["tag"]?.jsonPrimitive?.contentOrNull ?: "0"
                        // read 从本地 DB 获取
                        val read = getLocalReadState(mid)
                        return SmsMessage(id, number, content, date, read, smsDirectionFromTag(tg))
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.w(tag, "getById goform fallback failed for id=$id: ${e.javaClass.simpleName}: ${e.message}")
        }
        return null
    }


    suspend fun delete(id: Long): Boolean {
        // ① ContentResolver 直删（Android 4.4+ 非默认短信应用通常被拒，静默降级）
        try {
            val ctx = context
            if (ctx != null && ctx.contentResolver.delete(Uri.parse("content://sms/$id"), null, null) > 0) {
                try { vcDao?.deleteByMsgId(id) } catch (e: CancellationException) { throw e } catch (e: Exception) {
                    AppLogger.w(
                        tag,
                        "delete: vc cascade cleanup failed for id=$id: " +
                            "${e.javaClass.simpleName}: ${e.message}"
                    )
                }
                return true
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 非默认短信应用被 Provider 拒绝是预期路径，这里只是把「为什么被拒」留个痕，随后照旧走 goform。
            AppLogger.w(
                tag,
                "delete via ContentResolver failed for id=$id, falling back to goform: " +
                    "${e.javaClass.simpleName}: ${e.message}"
            )
        }

        // ② goform 删除：id 可能是系统 _id（ContentResolver 路径），先解析出 goform
        //    消息 id 再删（短会话写操作，用户显式动作）
        try {
            smsClient?.let { gc ->
                val goformId = resolveGoformSmsId(id) ?: return@let
                if (gc.deleteSms(goformId.toString())) {
                    // 级联清理验证码缓存
                    try { vcDao?.deleteByMsgId(id) } catch (e: CancellationException) { throw e } catch (e: Exception) {
                        AppLogger.w(
                            tag,
                            "delete: vc cascade cleanup failed for id=$id: " +
                                "${e.javaClass.simpleName}: ${e.message}"
                        )
                    }
                    return true
                }

            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.w(tag, "delete via goform failed for id=$id: ${e.javaClass.simpleName}: ${e.message}")
        }
        return false
    }


    /**
     * 跨 ID 体系定位：id 为 goform id 时原样返回；为系统 _id 时按 address+date
     * 在 goform 列表中匹配出 goform 消息 id。
     */
    private suspend fun resolveGoformSmsId(id: Long): Long? {
        val gc = smsClient ?: return null
        val list = gc.getSmsList(perPage = 200) ?: return null
        val arr = list["messages"]?.jsonArray ?: return null
        // 先按 goform id 直接命中
        for (el in arr) {
            if (el.jsonObject["id"]?.jsonPrimitive?.longOrNull == id) return id
        }
        // 再按 address+date 匹配（ContentResolver _id 场景）
        val local = context?.contentResolver?.let {
            try {
                it.query(Uri.parse("content://sms"), arrayOf("address", "date"), "_id=?", arrayOf(id.toString()), null)?.use { c ->
                    if (c.moveToFirst()) (c.getString(0) ?: "") to c.getLong(1) else null
                }
            } catch (_: Exception) { null }
        } ?: return null
        for (el in arr) {
            val obj = el.jsonObject
            val number = obj["number"]?.jsonPrimitive?.contentOrNull ?: ""
            val date = parseSmsDate(obj["date"]?.jsonPrimitive?.contentOrNull ?: "")
            if (number == local.first && date == local.second) {
                return obj["id"]?.jsonPrimitive?.longOrNull
            }
        }
        return null
    }

    // 已读状态的四个写入口 + 未读计数以前全是 `catch (_: Exception) {}`：
    // DAO 写失败只表现为「点了已读，红点不消失」，日志里一个字都没有，
    // 分不清是 Room 出错还是 dao 没接上。统一补 WARN（不改控制流：仍然 return false / 0）。
    // 注意：都是 suspend 路径，CancellationException 必须原样抛出，
    // 否则协程取消会被当成「标记失败」吞掉，调用方看到的是假的业务失败。

    /** 标记单条已读（本地 DB） */

    suspend fun markAsRead(id: Long): Boolean {
        try {
            smsReadStateDao?.markRead(id)
            return true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.w(tag, "markAsRead failed for id=$id: ${e.javaClass.simpleName}: ${e.message}")
        }
        return false
    }

    /** 标记单条未读（本地 DB） */
    suspend fun markAsUnread(id: Long): Boolean {
        try {
            smsReadStateDao?.markUnread(id)
            return true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.w(tag, "markAsUnread failed for id=$id: ${e.javaClass.simpleName}: ${e.message}")
        }
        return false
    }

    /** 按号码批量标记已读（打开对话时调用） */
    suspend fun markConversationRead(phone: String): Boolean {
        try {
            smsReadStateDao?.markConversationRead(phone)
            return true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.w(tag, "markConversationRead failed: ${e.javaClass.simpleName}: ${e.message}")
        }
        return false
    }

    /** 全部标记已读 */
    suspend fun markAllRead(): Boolean {
        try {
            smsReadStateDao?.markAllRead()
            return true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.w(tag, "markAllRead failed: ${e.javaClass.simpleName}: ${e.message}")
        }
        return false
    }


    /**
     * 发送短信。唯一通道是 goform `SEND_SMS`（见 `GoformSmsClient.sendSms`）——
     * 读取走 ContentResolver、发送走 goform 是刻意的不对称，理由见类注释。
     *
     * 结果直接采用客户端**回读设备信箱**后的结论：固件回 `success` 只表示进了发送队列，
     * 真发失败时信箱行 `tag=3`，那种情况必须报错，不能让 UI 显示假的"发送成功"。
     */
    suspend fun send(phoneNumber: String, message: String): SendResult {
        val gc = smsClient ?: run {
            AppLogger.e(tag, "send aborted: goform SMS client not wired")
            return SendResult(false, "发送失败（goform 通道未初始化）")
        }
        return try {
            val outcome = gc.sendSms(phoneNumber, message)
            when (outcome.verdict) {
                GoformSmsClient.SendVerdict.SENT -> SendResult(true, "已发送")
                // 未确认不等于失败（设备可能只是慢），按成功回但把状态说清楚
                GoformSmsClient.SendVerdict.PENDING -> SendResult(true, "已提交设备：${outcome.detail}")
                GoformSmsClient.SendVerdict.FAILED -> SendResult(false, outcome.detail)
                // 两档"没发成功"对手动发送是同一个结果（失败 + 说明），只是原因不同：
                // REJECTED 是设备明确拒收、NO_RESPONSE 是拿不到设备表态（可能已发出，别盲目重发）。
                GoformSmsClient.SendVerdict.REJECTED,
                GoformSmsClient.SendVerdict.NO_RESPONSE -> SendResult(false, "发送失败：${outcome.detail}")
            }
        } catch (e: Exception) {
            AppLogger.e(tag, "send failed", e)
            SendResult(false, "发送失败（${e.javaClass.simpleName}: ${e.message ?: "-"}）")
        }
    }

    /** 未读数（本地 DB） */
    suspend fun getUnreadCount(): Int {
        try {
            return smsReadStateDao?.getUnreadCount() ?: 0
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.w(tag, "getUnreadCount failed, reporting 0: ${e.javaClass.simpleName}: ${e.message}")
        }
        return 0
    }


    /**
     * 总数（ContentResolver 优先，goform 兜底）。与 [readSms] / [getFilteredCount] 同口径。
     *
     * 已知边界：ContentResolver 不可用时退回设备侧 `getSmsMeta().total`，那份元数据**没有正文**，
     * 无法应用拦截过滤，所以纯 goform 设备上「总数」会包含被拦短信。
     * 不为此改成「读一遍完整列表再数」——那会让每次 `/api/sms/count` 都登录一次设备后台。
     */
    suspend fun getTotalCount(): Int {
        countSmsLocal()?.let { total ->
            if (ruleStore?.hasActiveRules() != true) return total
            return (total - (countBlockedLocal() ?: 0)).coerceAtLeast(0)
        }
        return smsClient?.getSmsMeta()?.total ?: 0
    }

    /**
     * **唯一读聚合点**：全部读路径（列表 / 最新一条 / 联系人聚合）都从这里出。
     *
     * 拦截过滤放在这里而不是各个调用点，就是为了让「列表」和「计数」不可能出现两套口径 ——
     * 界面上「总数 50 却只列出 48 条」的典型来源就是过滤散落在多处。
     *
     * 注意：过滤在**取够 limit 条之后**发生，所以命中拦截时返回条数会少于 limit。
     * 这里刻意不做「补齐到 limit」：补齐要循环加深 offset 反复查 ContentResolver，
     * 而调用方（列表分页）本来就按「返回条数 < limit 视为可能还有下一页」处理。
     */
    private suspend fun readSms(
        folder: String?,
        limit: Int,
        offset: Int = 0,
        phone: String? = null,
        fallbackOnEmpty: Boolean = false
    ): List<SmsMessage> {
        val raw = readSmsRaw(folder, limit, offset, phone, fallbackOnEmpty)
        val store = ruleStore ?: return raw
        if (raw.isEmpty() || !store.hasActiveRules()) return raw
        val kept = raw.filterNot { store.isBlocked(it.address, it.body) }
        if (kept.size != raw.size) {
            AppLogger.d(tag, "readSms: 拦截规则滤掉 ${raw.size - kept.size} 条")
        }
        return kept
    }

    private suspend fun readSmsRaw(
        folder: String?,
        limit: Int,
        offset: Int = 0,
        phone: String? = null,
        fallbackOnEmpty: Boolean = false
    ): List<SmsMessage> {
        // ① ContentResolver（主路径，学 UFI-TOOLS）：系统 API，零 goform 请求、零会话——
        //    短信轮询（20-60s 周期）绝不登录设备后台，从根源上消除与官方后台互踢。
        val local = readSmsLocal(folder, limit, offset, phone)
        if (local != null && (local.isNotEmpty() || !fallbackOnEmpty)) {
            val merged = mergeReadState(local)
            AppLogger.d(tag, "readSms via ContentResolver: ${merged.size} messages")
            return merged
        }

        
        // ② goform 裸读兜底（仅在 ContentResolver 不可用、或调用方显式允许"空列表也兜底"时）
        AppLogger.w(tag, "ContentResolver ${if (local == null) "unavailable" else "empty"}, falling back to Goform (triggers session)")
        try {
            smsClient?.let { gc ->
                val smsData = gc.getSmsList(perPage = limit.coerceAtLeast(200)) ?: return@let
                val arr = smsData["messages"]?.jsonArray ?: return@let
                val msgs = arr.mapNotNull { el ->
                    try {
                        val obj = el.jsonObject
                        val id = obj["id"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null
                        val number = obj["number"]?.jsonPrimitive?.contentOrNull ?: ""
                        val content = decodeB64(obj["content"]?.jsonPrimitive?.contentOrNull ?: "", obj["encode_type"]?.jsonPrimitive?.contentOrNull ?: "0")
                        val dateStr = obj["date"]?.jsonPrimitive?.contentOrNull ?: ""
                        val date = parseSmsDate(dateStr)
                        val tag = obj["tag"]?.jsonPrimitive?.contentOrNull ?: "0"
                        SmsMessage(id, number, content, date, true, smsDirectionFromTag(tag))
                    } catch (_: Exception) { null }
                }
                // 批量合并本地已读状态
                val merged = mergeReadState(msgs)
                AppLogger.d(tag, "readSms via goform fallback: ${merged.size} messages")
                val folderFiltered = when (folder) {
                    "inbox" -> merged.filter { it.direction == "received" }
                    "sent" -> merged.filter { it.direction == "sent" }
                    else -> merged
                }
                val phoneFiltered = if (!phone.isNullOrBlank()) folderFiltered.filter { it.address == phone } else folderFiltered
                return phoneFiltered.sortedByDescending { it.date }.drop(offset).take(limit)
            } ?: AppLogger.d(tag, "readSms: smsClient is null")
        } catch (e: Exception) {
            AppLogger.d(tag, "readSms goform failed: ${e.message}")
        }
        AppLogger.d(tag, "readSms: 无数据（ContentResolver 与 goform 均不可用/为空）")
        return emptyList()
    }

    // ── 已读状态合并 ──

    // ── 验证码解析 ──

    /**
     * 从缓存读取全部验证码解析结果（供 API 端点调用）。
     *
     * 顺带**回填缺失的 body**：DB v8 的迁移只能给旧记录补 `''`，而 [extractCode] 只对新收到的
     * 短信写全文，所以升级前入库的验证码全都没有 body，客户端详情页只能退回 80 字的 snippet
     * ——看起来就像正文被截断。这里对空 body 的记录按 msg_id 回查原短信补齐并写回 DB，
     * 之后再读就直接命中。原短信已被删除（回查不到）时保持空串，客户端仍回退 snippet。
     */
    suspend fun getCachedVerificationCodes(): List<SmsVerificationCode> {
        return try {
            val cached = vcDao?.getAll() ?: emptyList()
            if (cached.none { it.body.isBlank() }) return cached
            cached.map { vc ->
                if (vc.body.isNotBlank()) return@map vc
                val full = try { getById(vc.msg_id)?.body } catch (_: Exception) { null }
                if (full.isNullOrBlank()) {
                    vc
                } else {
                    try { vcDao?.updateBody(vc.msg_id, full) } catch (_: Exception) { }
                    vc.copy(body = full)
                }
            }
        } catch (_: Exception) { emptyList() }
    }

    /**
     * 从单条短信中提取验证码，命中则包装成可落库的 [SmsVerificationCode]。
     *
     * 匹配判据全部在 [SmsCodeExtractor] 里 —— 邮件路径（`MailTemplate.extractCode`）
     * 用的是同一份，别在这里另加/另减提示词或位数，否则「这条算不算验证码」会随
     * 调用路径漂移（那正是 2026-09-08 统一之前的故障形态，详见 SmsCodeExtractor 头注释）。
     *
     * @return 提取结果，无匹配返回 null
     */
    fun extractCode(number: String, content: String, msgId: Long, timestamp: Long): SmsVerificationCode? {
        val match = SmsCodeExtractor.find(content) ?: return null
        // snippet = 列表/通知用的 80 字预览；body = 原文全量（详情页要看全内容）。
        // 两份都存：只留 snippet 会逼客户端为了看全文再回查一次短信列表。
        val snippet = if (content.length > 80) content.take(80) + "..." else content
        return SmsVerificationCode(
            msg_id = msgId,
            code = match.code,
            source = number,
            snippet = snippet,
            timestamp = timestamp,
            keyword = match.keyword,
            body = content
        )
    }

    /** 批量查询本地 DB 并合并到消息列表 */
    private suspend fun mergeReadState(msgs: List<SmsMessage>): List<SmsMessage> {
        if (msgs.isEmpty() || smsReadStateDao == null) return msgs
        val ids = msgs.map { it.id }
        val states = try {
            smsReadStateDao.getReadStates(ids).associate { it.msg_id to it.read }
        } catch (_: Exception) {
            emptyMap()
        }
        // 未在 DB 中找到的消息默认已读（兼容初始化遗漏）
        return msgs.map { msg -> msg.copy(read = states[msg.id] ?: true) }
    }

    /** 查询单条消息的本地已读状态 */
    private suspend fun getLocalReadState(msgId: Long): Boolean {
        if (smsReadStateDao == null) return true
        return try {
            val states = smsReadStateDao.getReadStates(listOf(msgId))
            states.firstOrNull { it.msg_id == msgId }?.read ?: true
        } catch (_: Exception) {
            true
        }
    }

    private fun decodeB64(contentB64: String, encodeType: String): String {
        return try {
            val decoded = android.util.Base64.decode(contentB64, android.util.Base64.DEFAULT)
            when (encodeType) { "2" -> String(decoded, Charsets.UTF_16BE); else -> String(decoded, Charsets.UTF_8) }
        } catch (_: Exception) { contentB64 }
    }

    /**
     * 解析 Goform 短信日期格式: "YY,MM,DD,HH,mm,ss,+TZ"
     * 示例: "26,06,10,21,19,13,+0800" → 2026-06-10 21:19:13 GMT+8
     */
    private fun parseSmsDate(dateStr: String): Long {
        val parts = dateStr.split(",")
        if (parts.size < 6) return 0L
        val year = 2000 + (parts[0].toIntOrNull() ?: return 0L)
        val month = parts[1].toIntOrNull() ?: return 0L
        val day = parts[2].toIntOrNull() ?: return 0L
        val hour = parts[3].toIntOrNull() ?: return 0L
        val minute = parts[4].toIntOrNull() ?: return 0L
        val second = parts[5].toIntOrNull() ?: return 0L
        val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("GMT+8"))
        cal.set(year, month - 1, day, hour, minute, second)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    /**
     * 「这条短信是收到的还是本机发出的」——**全仓两处方向判据的唯一实现**。
     *
     * 抽成 companion 里的纯函数是为了能在没有设备的情况下断言
     * （见 `SmsSelfSentDirectionTest`，做法同 `GoformSmsClient.buildSendParams`）。
     *
     * ## 为什么这两个函数值得一条专门的测试
     *
     * 三条「发现新短信」链路全都靠 `direction == "received"` 排除**设备自己发出去的**短信：
     * - `BackendService.forwardLatestSmsIfNew`（邮件）
     * - `DataScheduler.collectSmsCache`（WS 推送）
     * - `DataScheduler.scanVerificationCodes`（验证码入库）
     *
     * 而 2026-09-09 起 core 自己会**主动发短信**（`LocalSmsChannel`，通知渠道之一）。
     * 一旦这里把自己发出的短信判成 `received`，那条短信就会被当成"新短信"再触发一次通知
     * → 再发一条短信 → **无限循环烧话费**。所以这两个映射不是格式化细节，是一道刹车。
     */
    companion object {
        /** goform 信箱行的 tag：0/1 = 收到，2 = 已发送，3 = 发送失败（后两者都是本机发出的）。 */
        internal fun smsDirectionFromTag(tag: String): String =
            when (tag) { "2", "3" -> DIRECTION_SENT; else -> DIRECTION_RECEIVED }

        /** 系统 SMS Provider 的 `type` 列：2 = MESSAGE_TYPE_SENT，其余按收到处理。 */
        internal fun smsDirectionFromType(type: Int): String =
            if (type == PROVIDER_TYPE_SENT) DIRECTION_SENT else DIRECTION_RECEIVED

        /** `SmsMessage.direction` 的两个取值。三条新短信链路比对的就是这两个字符串。 */
        internal const val DIRECTION_SENT = "sent"
        internal const val DIRECTION_RECEIVED = "received"

        /** `Telephony.Sms.MESSAGE_TYPE_SENT`。写常量是为了让上面那个 `== 2` 有名字。 */
        private const val PROVIDER_TYPE_SENT = 2
    }
}
