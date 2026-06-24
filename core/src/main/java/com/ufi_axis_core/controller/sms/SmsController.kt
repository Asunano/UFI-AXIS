package com.ufi_axis_core.controller.sms

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import com.ufi_axis_core.controller.goform.GoformSmsClient
import com.ufi_axis_core.util.AppLogger
import com.ufi_axis_core.util.ShellExecutor
import kotlinx.serialization.json.*

class SmsController(
    private val context: Context? = null,
    private val smsClient: GoformSmsClient? = null
) {
    private val tag = "SmsController"
    private val smsInboxUri = Uri.parse("content://sms/inbox")
    private val smsSentUri = Uri.parse("content://sms/sent")
    private val smsAllUri = Uri.parse("content://sms")

    data class SmsMessage(val id: Long, val address: String, val body: String, val date: Long, val read: Boolean, val direction: String)
    data class SendResult(val success: Boolean, val message: String)

    suspend fun getAll(limit: Int = 50, offset: Int = 0, phone: String? = null): List<SmsMessage> = readSms(null, limit, offset, phone)
    suspend fun getInbox(limit: Int = 50, offset: Int = 0, phone: String? = null): List<SmsMessage> = readSms("inbox", limit, offset, phone)
    suspend fun getSent(limit: Int = 50, offset: Int = 0, phone: String? = null): List<SmsMessage> = readSms("sent", limit, offset, phone)

    /** 获取按号码过滤的总数（ContentResolver优先，goform回退） */
    suspend fun getFilteredCount(phone: String?): Int {
        if (phone.isNullOrBlank()) return getTotalCount()
        // ContentResolver 优先
        try {
            context?.let { ctx ->
                val cursor = ctx.contentResolver.query(smsAllUri, arrayOf("_id"), "address=?", arrayOf(phone), null)
                cursor?.use { if (it.count > 0) return it.count }
            }
        } catch (_: Exception) {}
        // goform 回退：拉取消息并过滤计数
        try {
            smsClient?.let { gc ->
                val smsData = gc.getSmsList(page = 0, perPage = 100)
                if (smsData != null) {
                    val arr = smsData["messages"]?.jsonArray
                    if (arr != null) {
                        return arr.count { el ->
                            (el.jsonObject["number"]?.jsonPrimitive?.contentOrNull) == phone
                        }
                    }
                }
            }
        } catch (_: Exception) {}
        return 0
    }

    /**
     * 获取按号码聚合的联系人列表（goform 缓存优先，ContentResolver 回退）
     * 返回每个联系人：phoneNumber, total, unread, latestMsg, latestTimestamp, latestDirection
     */
    suspend fun getContactList(dataScheduler: com.ufi_axis_core.core.scheduler.DataScheduler? = null): List<Map<String, Any>> {
        // 优先使用 DataScheduler 中 goform 缓存的联系人列表
        val cached = dataScheduler?.getCachedSmsContacts()
        if (cached != null && cached.isNotEmpty()) {
            return cached
        }
        // 回退1: goform 直接查询
        try {
            smsClient?.let { gc ->
                val smsData = gc.getSmsList(page = 0, perPage = 100)
                if (smsData != null) {
                    val arr = smsData["messages"]?.jsonArray
                    if (arr != null && arr.isNotEmpty()) {
                        val msgs = arr.mapNotNull { el ->
                            try {
                                val obj = el.jsonObject
                                val number = obj["number"]?.jsonPrimitive?.contentOrNull ?: ""
                                val content = decodeB64(obj["content"]?.jsonPrimitive?.contentOrNull ?: "",
                                    obj["encode_type"]?.jsonPrimitive?.contentOrNull ?: "0")
                                val dateStr = obj["date"]?.jsonPrimitive?.contentOrNull ?: ""
                                val tag = obj["tag"]?.jsonPrimitive?.contentOrNull ?: "0"
                                SmsMessage(0L, number, content, parseSmsDate(dateStr),
                                    tag != "1", when (tag) { "2", "3" -> "sent"; else -> "received" })
                            } catch (_: Exception) { null }
                        }
                        return msgs.groupBy { it.address }
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
                }
            }
        } catch (_: Exception) {}

        // 回退2: ContentResolver
        val all = getAll(limit = 5000, offset = 0) ?: emptyList()
        if (all.isEmpty()) return emptyList()

        return all.groupBy { it.address }
            .mapValues { (_, msgs) ->
                val latest = msgs.maxByOrNull { it.date }
                mapOf<String, Any>(
                    "phoneNumber" to (latest?.address ?: "unknown"),
                    "total" to msgs.size,
                    "unread" to msgs.count { !it.read && it.direction == "received" },
                    "latestMsg" to (latest?.body ?: ""),
                    "latestTimestamp" to (latest?.date ?: 0L),
                    "latestDirection" to (latest?.direction ?: "received")
                )
            }
            .values
            .sortedByDescending { it["latestTimestamp"] as Long }
    }

    /**
     * 获取最新一条短信（参考项目 SmsPoll.getLatestSms）
     * 用于轮询检测新短信
     */
    suspend fun getLatest(): SmsMessage? {
        return readSms(null, 1).firstOrNull()
    }

    suspend fun getById(id: Long): SmsMessage? {
        try {
            context?.let { ctx ->
                val cursor = ctx.contentResolver.query(
                    smsAllUri, arrayOf("_id","address","body","date","read","type"),
                    "_id=?", arrayOf(id.toString()), null
                )
                cursor?.use {
                    if (it.moveToFirst()) return parseCursorRow(it)
                }
            }
        } catch (_: Exception) {}
        // fallback: Goform
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
                        val tag = obj["tag"]?.jsonPrimitive?.contentOrNull ?: "0"
                        return SmsMessage(id, number, content, date, smsReadFromTag(tag), smsDirectionFromTag(tag))
                    }
                }
            }
        } catch (_: Exception) {}
        return null
    }

    suspend fun delete(id: Long): Boolean {
        try {
            smsClient?.let { gc ->
                val r = gc.deleteSms(id.toString()); if (r) return true
            }
        } catch (_: Exception) {}
        try {
            context?.let { ctx ->
                val r = ctx.contentResolver.delete(smsAllUri, "_id=?", arrayOf(id.toString()))
                return r > 0
            }
        } catch (_: Exception) {}
        val result = ShellExecutor.executeAsRoot("content delete --uri content://sms --where \"_id=$id\" 2>/dev/null")
        return result.isSuccess && !result.stdout.contains("0 rows deleted")
    }

    suspend fun markAsUnread(id: Long): Boolean = markAsRead(false, id)

    suspend fun markAsRead(id: Long): Boolean = markAsRead(true, id)

    private suspend fun markAsRead(read: Boolean, id: Long): Boolean {
        val readVal = if (read) 1 else 0
        try {
            smsClient?.let { gc ->
                if (read) { val r = gc.markSmsRead(id.toString()); if (r) return true }
            }
        } catch (_: Exception) {}
        try {
            context?.let { ctx ->
                val cv = ContentValues().apply { put("read", readVal) }
                val r = ctx.contentResolver.update(smsAllUri, cv, "_id=?", arrayOf(id.toString()))
                return r > 0
            }
        } catch (_: Exception) {}
        ShellExecutor.executeAsRoot("content update --uri content://sms --bind read:i:$readVal --where \"_id=$id\" 2>/dev/null")
        return true
    }

    suspend fun send(phoneNumber: String, message: String): SendResult {
        // 方案1: Goform API
        try {
            smsClient?.let { gc ->
                if (gc.sendSms(phoneNumber, message)) return SendResult(true, "已通过 Goform 发送")
            }
        } catch (_: Exception) {}
        // 方案2: ContentResolver 写入发件箱
        try {
            context?.let { ctx ->
                val cv = ContentValues().apply {
                    put("address", phoneNumber); put("body", message)
                    put("date", System.currentTimeMillis()); put("read", 1); put("type", 2)
                }
                ctx.contentResolver.insert(smsSentUri, cv)
                return SendResult(true, "已写入发件箱")
            }
        } catch (_: Exception) {}
        // 方案3: Shell content insert
        val em = message.replace("'", "\\'"); val ep = phoneNumber.replace("'", "\\'")
        val insert = ShellExecutor.executeAsRoot("content insert --uri content://sms/sent --bind address:s:$ep --bind body:s:$em --bind date:l:${System.currentTimeMillis()} --bind read:i:1 --bind type:i:2 2>/dev/null")
        if (insert.isSuccess) return SendResult(true, "已写入发件箱")
        // 方案4: service call
        val cmd = "service call isms 7 s16 \"$ep\" s16 \"$em\" 2>/dev/null"
        val result = ShellExecutor.executeAsRoot(cmd, 30000)
        return if (result.isSuccess) SendResult(true, "发送成功")
        else SendResult(false, "发送失败")
    }

    suspend fun getUnreadCount(): Int {
        try {
            context?.let { ctx ->
                val cursor = ctx.contentResolver.query(smsInboxUri, arrayOf("_id"), "read=0", null, null)
                cursor?.use { return it.count }
            }
        } catch (_: Exception) {}
        val r = ShellExecutor.executeAsRoot("content query --uri content://sms/inbox --projection _id:read --where \"read=0\" 2>/dev/null | grep -c 'Row:'")
        return r.stdout.trim().toIntOrNull() ?: 0
    }

    suspend fun getTotalCount(): Int {
        try {
            context?.let { ctx ->
                val cursor = ctx.contentResolver.query(smsAllUri, arrayOf("_id"), null, null, null)
                cursor?.use { return it.count }
            }
        } catch (_: Exception) {}
        val r = ShellExecutor.executeAsRoot("content query --uri content://sms --projection _id 2>/dev/null | grep -c 'Row:'")
        return r.stdout.trim().toIntOrNull() ?: 0
    }

    private suspend fun readSms(folder: String?, limit: Int, offset: Int = 0, phone: String? = null): List<SmsMessage> {
        // 方案1: ContentResolver (最可靠)
        try {
            context?.let { ctx ->
                val uri = when (folder) {
                    "inbox" -> smsInboxUri; "sent" -> smsSentUri; else -> smsAllUri
                }
                val selection = if (!phone.isNullOrBlank()) "address=?" else null
                val selectionArgs = if (!phone.isNullOrBlank()) arrayOf(phone) else null
                val cursor = ctx.contentResolver.query(uri, arrayOf("_id","address","body","date","read","type"), selection, selectionArgs, "date DESC")
                cursor?.use {
                    val msgs = mutableListOf<SmsMessage>()
                    while (it.moveToNext()) { msgs.add(parseCursorRow(it)) }
                    if (msgs.isNotEmpty()) {
                        AppLogger.d(tag, "readSms via ContentResolver: ${msgs.size} messages")
                        return msgs.drop(offset).take(limit)
                    }
                    AppLogger.d(tag, "readSms via ContentResolver: 0 messages (empty)")
                }
            }
        } catch (e: Exception) {
            AppLogger.d(tag, "readSms ContentResolver failed: ${e.message}")
        }

        // 方案2: Goform API
        try {
            smsClient?.let { gc ->
                val smsData = gc.getSmsList(perPage = limit.coerceAtLeast(200))
                if (smsData != null) {
                    val arr = smsData["messages"]?.jsonArray
                    if (arr != null && arr.isNotEmpty()) {
                        val msgs = arr.mapNotNull { el ->
                            try {
                                val obj = el.jsonObject
                                val id = obj["id"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null
                                val number = obj["number"]?.jsonPrimitive?.contentOrNull ?: ""
                                val content = decodeB64(obj["content"]?.jsonPrimitive?.contentOrNull ?: "", obj["encode_type"]?.jsonPrimitive?.contentOrNull ?: "0")
                                val dateStr = obj["date"]?.jsonPrimitive?.contentOrNull ?: ""
                                val date = parseSmsDate(dateStr)
                                val tag = obj["tag"]?.jsonPrimitive?.contentOrNull ?: "0"
                                SmsMessage(id, number, content, date, smsReadFromTag(tag), smsDirectionFromTag(tag))
                            } catch (_: Exception) { null }
                        }
                        AppLogger.d(tag, "readSms via goform: ${msgs.size} messages")
                        val filtered = if (!phone.isNullOrBlank()) msgs.filter { it.address == phone } else msgs
                        return filtered.sortedByDescending { it.date }.drop(offset).take(limit)
                    }
                    AppLogger.d(tag, "readSms via goform: messages array empty or null (keys=${smsData.keys.joinToString()})")
                } else {
                    AppLogger.d(tag, "readSms via goform: getSmsList returned null (login failed?)")
                }
            } ?: AppLogger.d(tag, "readSms: smsClient is null")
        } catch (e: Exception) {
            AppLogger.d(tag, "readSms goform failed: ${e.message}")
        }

        // 方案3: Shell content query
        val uri = if (folder != null) "content://sms/$folder" else "content://sms"
        val cmd = "content query --uri $uri --projection _id:address:body:date:read:type 2>/dev/null"
        val result = ShellExecutor.executeAsRoot(cmd, 15000)
        if (!result.isSuccess || result.stdout.isBlank()) {
            AppLogger.d(tag, "readSms shell: no data (success=${result.isSuccess})")
            return emptyList()
        }
        val all = parseRow(result.stdout)
        AppLogger.d(tag, "readSms via shell: ${all.size} messages")
        val filtered = if (!phone.isNullOrBlank()) all.filter { it.address == phone } else all
        return filtered.sortedByDescending { it.date }.drop(offset).take(limit)
    }

    private fun parseCursorRow(cursor: android.database.Cursor): SmsMessage {
        val id = cursor.getLong(cursor.getColumnIndexOrThrow("_id"))
        val address = cursor.getString(cursor.getColumnIndexOrThrow("address")) ?: ""
        val body = cursor.getString(cursor.getColumnIndexOrThrow("body")) ?: ""
        val date = cursor.getLong(cursor.getColumnIndexOrThrow("date"))
        val read = cursor.getInt(cursor.getColumnIndexOrThrow("read")) == 1
        val type = cursor.getInt(cursor.getColumnIndexOrThrow("type"))
        val direction = when (type) { 1 -> "received"; 2 -> "sent"; else -> "unknown" }
        return SmsMessage(id, address, body, date, read, direction)
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

    /** tag映射方向: 0/1=received, 2/3=sent */
    private fun smsDirectionFromTag(tag: String): String =
        when (tag) { "2", "3" -> "sent"; else -> "received" }

    /** tag映射已读: 1=unread, 其他=read */
    private fun smsReadFromTag(tag: String): Boolean = tag != "1"

    private fun parseRow(output: String): List<SmsMessage> {
        val msgs = mutableListOf<SmsMessage>()
        val re = Regex("""Row:\s*\d+\s+_id=(\d+),\s*address=([^,]*),\s*body=(.*?),\s*date=(\d+),\s*read=(\d+),\s*type=(\d+)""", RegexOption.DOT_MATCHES_ALL)
        for (line in output.lines()) {
            if (!line.startsWith("Row:")) continue
            try {
                val m = re.find(line) ?: continue
                msgs.add(SmsMessage(m.groupValues[1].toLong(), m.groupValues[2], m.groupValues[3],
                    m.groupValues[4].toLongOrNull() ?: 0L, m.groupValues[5] == "1",
                    when (m.groupValues[6].toIntOrNull()) { 1 -> "received"; 2 -> "sent"; else -> "unknown" }))
            } catch (_: Exception) {}
        }
        return msgs
    }
}