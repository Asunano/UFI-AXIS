package com.ufi_axis_core.controller.sim

import android.content.Context
import android.telephony.SmsManager
import android.util.Base64
import com.ufi_axis_core.collector.at.ATChannel
import com.ufi_axis_core.controller.goform.GoformClient
import com.ufi_axis_core.core.database.AppDatabase
import com.ufi_axis_core.core.database.SmsRecord
import com.ufi_axis_core.util.AppLogger
import kotlinx.serialization.json.*

/**
 * SIM 控制器
 * 短信收发 + USSD 查询
 */
class SimController(
    private val context: Context,
    private val atChannel: ATChannel,
    private val database: AppDatabase,
    private val goformClient: GoformClient? = null
) {
    private val tag = "SimController"

    /**
     * 发送短信
     * 优先使用 Android SmsManager，失败后 fallback 到 AT 指令
     */
    suspend fun sendSms(phoneNumber: String, message: String): Boolean {
        AppLogger.i(tag, "Sending SMS to $phoneNumber")

        // 方式1: Android SmsManager
        try {
            val smsManager = context.getSystemService(SmsManager::class.java)
            val parts = smsManager.divideMessage(message)
            smsManager.sendMultipartTextMessage(phoneNumber, null, parts, null, null)

            // 记录到数据库
            database.smsDao().insert(SmsRecord(
                direction = "sent",
                phoneNumber = phoneNumber,
                content = message
            ))
            return true
        } catch (e: Exception) {
            AppLogger.w(tag, "SmsManager failed, trying AT command: ${e.message}")
        }

        // 方式2: AT 指令
        return try {
            val success = atChannel.sendSms(phoneNumber, message)
            if (success) {
                database.smsDao().insert(SmsRecord(
                    direction = "sent",
                    phoneNumber = phoneNumber,
                    content = message
                ))
            }
            success
        } catch (e: Exception) {
            AppLogger.e(tag, "Failed to send SMS", e)
            false
        }
    }

    /**
     * 查询 SIM 卡信息
     */
    suspend fun getSimInfo(): Map<String, Any?> {
        val result = mutableMapOf<String, Any?>()
        val atInfo = atChannel.getSimInfo()
        result["imei"] = atInfo["imei"]
        result["imsi"] = atInfo["imsi"]
        val imsiVal = atInfo["imsi"]?.toString()?.takeIf { it != "null" && it.isNotEmpty() }
        result["sim_state"] = if (imsiVal != null) "READY" else if (atInfo.containsKey("imei")) "NO_SIM" else "UNKNOWN"
        result["phone_type"] = "GSM"
        return result
    }

    /**
     * 发送 USSD 查询（如查余额 *100#）
     */
    suspend fun sendUssd(code: String): String? {
        AppLogger.i(tag, "Sending USSD: $code")
        val response = atChannel.sendCommand("AT+CUSD=1,\"$code\",15", 15000)
        if (response != null) {
            val match = Regex("\\+CUSD:\\s*\\d+,\"([^\"]+)\"").find(response)
            return match?.groupValues?.get(1)
        }
        return null
    }

    suspend fun deleteSms(msgId: String): Boolean {
        return goformClient?.deleteSms(msgId) ?: false
    }

    suspend fun markSmsRead(msgId: String): Boolean {
        return goformClient?.markSmsRead(msgId) ?: false
    }

    /**
     * 获取短信列表
     * 优先使用 Goform API，失败后 fallback 到 AT 通道
     */
    suspend fun getSmsList(maxCount: Int = 20): List<Map<String, String>> {
        // 方式1: Goform API（获取设备端短信）
        goformClient?.let { goform ->
            try {
                val smsData = goform.getSmsList(perPage = maxCount)
                if (smsData != null) {
                    val messages = smsData["messages"]?.jsonArray
                    if (messages != null && messages.isNotEmpty()) {
                        return messages.mapNotNull { element ->
                            try {
                                val obj = element.jsonObject
                                val id = obj["id"]?.jsonPrimitive?.content ?: return@mapNotNull null
                                val number = obj["number"]?.jsonPrimitive?.content ?: ""
                                val contentBase64 = obj["content"]?.jsonPrimitive?.content ?: ""
                                val date = obj["date"]?.jsonPrimitive?.content ?: ""
                                val encodeType = obj["encode_type"]?.jsonPrimitive?.contentOrNull ?: obj["tag"]?.jsonPrimitive?.contentOrNull ?: "0"

                                // 根据 encode_type 选择正确的字符集解码
                                // encode_type=0: 7bit GSM (ASCII)
                                // encode_type=1: 8bit
                                // encode_type=2: UCS2 (UTF-16BE, 用于中文短信)
                                val content = try {
                                    val decoded = Base64.decode(contentBase64, Base64.DEFAULT)
                                    when (encodeType) {
                                        "2" -> String(decoded, Charsets.UTF_16BE)
                                        else -> {
                                            // 先尝试 UTF-8，失败后回退到 UTF-16BE
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
                    }
                }
            } catch (e: Exception) {
                AppLogger.w(tag, "Goform SMS failed, trying AT: ${e.message}")
            }
        }

        // 方式2: AT 通道
        val result = mutableListOf<Map<String, String>>()
        val response = atChannel.sendCommand("AT+CMGL=\"ALL\"", 10000)
        if (response != null) {
            val lines = response.lines()
            var i = 0
            while (i < lines.size && result.size < maxCount) {
                val headerMatch = Regex("\\+CMGL:\\s*(\\d+),\"([^\"]*)\",\"([^\"]*)\",,\"([^\\\"]+)\"").find(lines[i])
                if (headerMatch != null && i + 1 < lines.size) {
                    result.add(mapOf(
                        "index" to headerMatch.groupValues[1],
                        "status" to headerMatch.groupValues[2],
                        "sender" to headerMatch.groupValues[3],
                        "content" to lines[i + 1],
                        "date" to headerMatch.groupValues[4],
                        "source" to "at"
                    ))
                    i += 2
                } else {
                    i++
                }
            }
        }

        return result
    }
}
