package com.ufi_axis_core.controller.sim

import android.content.Context
import android.telephony.SmsManager
import android.util.Base64
import com.ufi_axis_core.controller.goform.GoformSignalClient
import com.ufi_axis_core.controller.goform.GoformSmsClient
import com.ufi_axis_core.core.database.AppDatabase
import com.ufi_axis_core.core.database.SmsRecord
import com.ufi_axis_core.util.AppLogger
import kotlinx.serialization.json.*

/**
 * SIM 控制器
 * 短信收发（Goform + Android SmsManager）
 */
class SimController(
    private val context: Context,
    private val database: AppDatabase,
    private val smsClient: GoformSmsClient? = null,
    private val signalClient: GoformSignalClient? = null
) {
    private val tag = "SimController"

    /**
     * 发送短信（Android SmsManager）
     */
    suspend fun sendSms(phoneNumber: String, message: String): Boolean {
        AppLogger.i(tag, "Sending SMS to $phoneNumber")
        return try {
            val smsManager = context.getSystemService(SmsManager::class.java)
            val parts = smsManager.divideMessage(message)
            smsManager.sendMultipartTextMessage(phoneNumber, null, parts, null, null)

            database.smsDao().insert(SmsRecord(
                direction = "sent",
                phoneNumber = phoneNumber,
                content = message
            ))
            true
        } catch (e: Exception) {
            AppLogger.e(tag, "Failed to send SMS: ${e.message}")
            false
        }
    }

    /**
     * 查询 SIM 卡信息
     * 使用 Goform (HTTP)
     */
    suspend fun getSimInfo(): Map<String, Any?> {
        val result = mutableMapOf<String, Any?>()

        val goformData = try { signalClient?.getDeviceInfo() } catch (_: Exception) { null }
        val imei = goformData?.get("imei")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() }
        val imsi = goformData?.get("imsi")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() }

        result["imei"] = imei ?: ""
        result["imsi"] = imsi ?: ""
        result["sim_state"] = if (imei != null) (if (imsi != null) "READY" else "UNKNOWN") else "UNKNOWN"
        result["phone_type"] = "GSM"

        return result
    }

    suspend fun deleteSms(msgId: String): Boolean {
        return smsClient?.deleteSms(msgId) ?: false
    }

    suspend fun markSmsRead(msgId: String): Boolean {
        return smsClient?.markSmsRead(msgId) ?: false
    }

    /**
     * 获取短信列表（Goform API）
     */
    suspend fun getSmsList(maxCount: Int = 20): List<Map<String, String>> {
        val goform = smsClient ?: return emptyList()
        return try {
            val smsData = goform.getSmsList(perPage = maxCount)
            val messages = smsData?.get("messages")?.jsonArray
            if (messages == null || messages.isEmpty()) return emptyList()

            messages.mapNotNull { element ->
                try {
                    val obj = element.jsonObject
                    val id = obj["id"]?.jsonPrimitive?.content ?: return@mapNotNull null
                    val number = obj["number"]?.jsonPrimitive?.content ?: ""
                    val contentBase64 = obj["content"]?.jsonPrimitive?.content ?: ""
                    val date = obj["date"]?.jsonPrimitive?.content ?: ""
                    val encodeType = obj["encode_type"]?.jsonPrimitive?.contentOrNull ?: obj["tag"]?.jsonPrimitive?.contentOrNull ?: "0"

                    val content = try {
                        val decoded = Base64.decode(contentBase64, Base64.DEFAULT)
                        when (encodeType) {
                            "2" -> String(decoded, Charsets.UTF_16BE)
                            else -> {
                                val utf8 = String(decoded, Charsets.UTF_8)
                                if (utf8.any { it == '\uFFFD' || (it.code in 1..31 && it != '\n' && it != '\r' && it != '\t') }) {
                                    String(decoded, Charsets.UTF_16BE)
                                } else {
                                    utf8
                                }
                            }
                        }
                    } catch (e: Exception) {
                        contentBase64
                    }

                    mapOf(
                        "id" to id,
                        "sender" to number,
                        "content" to content,
                        "date" to date,
                        "source" to "goform"
                    )
                } catch (e: Exception) {
                    null
                }
            }
        } catch (e: Exception) {
            AppLogger.w(tag, "Goform SMS list failed: ${e.message}")
            emptyList()
        }
    }
}
