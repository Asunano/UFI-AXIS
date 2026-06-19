package com.ufi_axis_core.controller.sms

import android.content.Context
import android.os.BatteryManager
import com.ufi_axis_core.collector.system.SystemCollector
import com.ufi_axis_core.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Properties
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.mail.Authenticator
import javax.mail.Message
import javax.mail.PasswordAuthentication
import javax.mail.Session
import javax.mail.Transport
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeMessage

class SmsForwardController(
    private val context: Context,
    private val systemCollector: SystemCollector? = null
) {
    private val tag = "SmsForwardController"
    private val prefs = context.getSharedPreferences("sms_forward", Context.MODE_PRIVATE)

    companion object {
        const val METHOD_DISABLED = "disabled"
        const val METHOD_SMTP = "smtp"
        const val METHOD_CURL = "curl"
        const val METHOD_DINGTALK = "dingtalk"
    }

    data class SmsForwardConfig(
        val enabled: Boolean = false,
        val method: String = METHOD_DISABLED,
        val smtpHost: String = "",
        val smtpPort: Int = 465,
        val smtpUser: String = "",
        val smtpPass: String = "",
        val smtpFrom: String = "",
        val smtpTo: String = "",
        val curlUrl: String = "",
        val curlTemplate: String = "from={{sms-from}}&content={{sms-body}}&time={{sms-time}}",
        val dingtalkToken: String = "",
        val dingtalkSecret: String = "",
        val forwardDevInfo: Boolean = false,
        val blacklist: List<String> = emptyList()
    )

    fun loadConfig(): SmsForwardConfig = SmsForwardConfig(
        enabled = prefs.getBoolean("enabled", false),
        method = prefs.getString("method", METHOD_DISABLED) ?: METHOD_DISABLED,
        smtpHost = prefs.getString("smtp_host", "") ?: "",
        smtpPort = prefs.getInt("smtp_port", 465),
        smtpUser = prefs.getString("smtp_user", "") ?: "",
        smtpPass = prefs.getString("smtp_pass", "") ?: "",
        smtpFrom = prefs.getString("smtp_from", "") ?: "",
        smtpTo = prefs.getString("smtp_to", "") ?: "",
        curlUrl = prefs.getString("curl_url", "") ?: "",
        curlTemplate = prefs.getString("curl_template",
            "from={{sms-from}}&content={{sms-body}}&time={{sms-time}}") ?: "",
        dingtalkToken = prefs.getString("dingtalk_token", "") ?: "",
        dingtalkSecret = prefs.getString("dingtalk_secret", "") ?: "",
        forwardDevInfo = prefs.getBoolean("forward_dev_info", false),
        blacklist = (prefs.getStringSet("blacklist", emptySet()) ?: emptySet()).toList()
    )

    fun saveConfig(config: SmsForwardConfig) {
        prefs.edit().apply {
            putBoolean("enabled", config.enabled)
            putString("method", config.method)
            putString("smtp_host", config.smtpHost)
            putInt("smtp_port", config.smtpPort)
            putString("smtp_user", config.smtpUser)
            putString("smtp_pass", config.smtpPass)
            putString("smtp_from", config.smtpFrom)
            putString("smtp_to", config.smtpTo)
            putString("curl_url", config.curlUrl)
            putString("curl_template", config.curlTemplate)
            putString("dingtalk_token", config.dingtalkToken)
            putString("dingtalk_secret", config.dingtalkSecret)
            putBoolean("forward_dev_info", config.forwardDevInfo)
            putStringSet("blacklist", config.blacklist.toSet())
        }.commit()  // 同步写入，确保后续读取能拿到最新值
    }

    suspend fun forwardSms(from: String, body: String, timestamp: Long): Boolean {
        val cfg = loadConfig()
        if (!cfg.enabled || cfg.method == METHOD_DISABLED) return false
        if (cfg.blacklist.isNotEmpty() && cfg.blacklist.any {
                it.isNotBlank() && (from.contains(it, ignoreCase = true) || body.contains(it, ignoreCase = true))
            }) {
            AppLogger.i(tag, "SMS blocked by blacklist: $from")
            return false
        }
        return withContext(Dispatchers.IO) {
            try {
                val devInfo = if (cfg.forwardDevInfo) buildDeviceInfo() else ""
                when (cfg.method) {
                    METHOD_SMTP -> forwardViaSmtp(cfg, from, body, timestamp, devInfo)
                    METHOD_CURL -> forwardViaCurl(cfg, from, body, timestamp, devInfo)
                    METHOD_DINGTALK -> forwardViaDingtalk(cfg, from, body, timestamp, devInfo)
                    else -> false
                }
            } catch (e: Exception) {
                AppLogger.e(tag, "SMS forward failed", e); false
            }
        }
    }

    /**
     * 构建设备状态信息（参考项目 KanoUtils.buildStatusSmsMsg）
     */
    private suspend fun buildDeviceInfo(): String {
        return try {
            val sb = StringBuilder()
            // 电池信息
            val battery = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            val level = battery?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
            if (level >= 0) sb.appendLine("Battery: ${level}%")

            // CPU / 内存（如果有 SystemCollector）
            systemCollector?.let { sc ->
                try {
                    val mem = sc.getMemoryInfo()
                    val usedMb = mem.used / 1048576
                    val totalMb = mem.total / 1048576
                    sb.appendLine("Memory: ${"%.0f".format(mem.usage_percent)}% used (${usedMb}MB / ${totalMb}MB)")
                } catch (_: Exception) {}
                try {
                    val cpu = sc.getCpuInfo()
                    sb.appendLine("CPU: ${"%.1f".format(cpu.usage_percent)}%")
                } catch (_: Exception) {}
                try {
                    val uptime = sc.getUptime()
                    sb.appendLine("Uptime: ${uptime["uptime_display"] ?: "N/A"}")
                } catch (_: Exception) {}
            }
            sb.toString().trimEnd()
        } catch (e: Exception) {
            AppLogger.w(tag, "buildDeviceInfo failed: ${e.message}")
            ""
        }
    }

    private fun forwardViaSmtp(cfg: SmsForwardConfig, from: String, body: String, ts: Long, devInfo: String): Boolean {
        val props = Properties().apply {
            put("mail.smtp.host", cfg.smtpHost)
            put("mail.smtp.port", cfg.smtpPort.toString())
            put("mail.smtp.auth", "true")
            put("mail.debug", "true")
            if (cfg.smtpPort == 465) {
                // SSL (port 465)
                put("mail.smtp.ssl.enable", "true")
                put("mail.smtp.socketFactory.port", "465")
                put("mail.smtp.socketFactory.class", "javax.net.ssl.SSLSocketFactory")
                put("mail.smtp.ssl.protocols", "TLSv1.2")
            } else {
                // STARTTLS (port 587 etc.)
                put("mail.smtp.starttls.enable", "true")
            }
            put("mail.smtp.connectiontimeout", "10000")
            put("mail.smtp.timeout", "10000")
        }
        val session = Session.getInstance(props, object : Authenticator() {
            override fun getPasswordAuthentication() = PasswordAuthentication(cfg.smtpUser, cfg.smtpPass)
        })

        // 将 JavaMail 协议级调试日志重定向到 AppLogger，可在 app 内查看完整 SMTP 对话
        session.setDebugOut(java.io.PrintStream(object : java.io.OutputStream() {
            private val buffer = StringBuilder()
            override fun write(b: Int) {
                if (b == '\n'.code) {
                    val line = buffer.toString().trim()
                    if (line.isNotEmpty()) AppLogger.d("$tag/SMTP", line)
                    buffer.clear()
                } else {
                    buffer.append(b.toChar())
                }
            }
        }, true))

        AppLogger.i(tag, "SMTP config: host=${cfg.smtpHost}, port=${cfg.smtpPort}, user=${cfg.smtpUser}, " +
                "passLen=${cfg.smtpPass.length}, from=${cfg.smtpFrom.ifBlank { cfg.smtpUser }}, to=${cfg.smtpTo}")

        val subject = if (body.length > 37) body.substring(0, 37) + "..." else body
        val textBody = buildString {
            appendLine("From: $from")
            appendLine("Time: ${formatTime(ts)}")
            appendLine()
            appendLine(body)
            if (devInfo.isNotBlank()) {
                appendLine()
                appendLine("--- Device Info ---")
                appendLine(devInfo)
            }
        }

        val msg = MimeMessage(session).apply {
            setFrom(InternetAddress(cfg.smtpFrom.ifBlank { cfg.smtpUser }))
            setRecipients(Message.RecipientType.TO, InternetAddress.parse(cfg.smtpTo))
            setSubject(subject, "UTF-8")
            setText(textBody, "UTF-8")
        }
        try {
            Transport.send(msg)
            AppLogger.i(tag, "SMS forwarded via SMTP to ${cfg.smtpTo}")
            return true
        } catch (e: Exception) {
            AppLogger.e(tag, "SMTP send failed: ${e.javaClass.simpleName}: ${e.message}", e)
            throw e  // 让上层 catch 记录完整异常
        }
    }

    private suspend fun forwardViaCurl(cfg: SmsForwardConfig, from: String, body: String, ts: Long, devInfo: String): Boolean {
        var payload = cfg.curlTemplate
            .replace("{{sms-from}}", from)
            .replace("{{sms-body}}", body)
            .replace("{{sms-time}}", formatTime(ts))
        if (devInfo.isNotBlank()) {
            payload = payload.replace("{{device-info}}", devInfo)
        }
        return withContext(Dispatchers.IO) {
            try {
                val process = ProcessBuilder("curl", "-s", "-o", "/dev/null", "-w", "%{http_code}",
                    "--data", payload, "--max-time", "10", cfg.curlUrl)
                    .redirectErrorStream(true)
                    .start()
                val httpCode = process.inputStream.bufferedReader().readText().trim()
                val exitCode = process.waitFor()
                exitCode == 0 && httpCode.startsWith("2")
            } catch (e: Exception) {
                AppLogger.e(tag, "curl forward failed", e)
                false
            }
        }
    }

    private fun forwardViaDingtalk(cfg: SmsForwardConfig, from: String, body: String, ts: Long, devInfo: String): Boolean {
        var webhookUrl = "https://oapi.dingtalk.com/robot/send?access_token=${cfg.dingtalkToken}"

        // HMAC-SHA256 签名（参考项目 KanoDingTalk.kt）
        if (cfg.dingtalkSecret.isNotBlank()) {
            val timestamp = System.currentTimeMillis()
            val stringToSign = "$timestamp\n${cfg.dingtalkSecret}"
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(cfg.dingtalkSecret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
            val sign = URLEncoder.encode(
                android.util.Base64.encodeToString(mac.doFinal(stringToSign.toByteArray(Charsets.UTF_8)), android.util.Base64.NO_WRAP),
                "UTF-8"
            )
            webhookUrl += "&timestamp=$timestamp&sign=$sign"
        }

        val content = buildString {
            append("SMS from ${from}")
            append("\nTime: ${formatTime(ts)}")
            append("\n\n${body}")
            if (devInfo.isNotBlank()) {
                append("\n\n--- Device Info ---\n")
                append(devInfo)
            }
        }

        val jsonPayload = buildString {
            append("{\"msgtype\":\"text\",\"text\":{\"content\":\"")
            append(jsonEscape(content))
            append("\"}}")
        }

        val conn = URL(webhookUrl).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 10000
            conn.readTimeout = 10000
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(jsonPayload) }
            val code = conn.responseCode
            AppLogger.i(tag, "DingTalk response: $code")
            return code == 200
        } finally {
            conn.disconnect()
        }
    }

    /** JSON 字符串转义：处理反斜杠、引号、换行等控制字符 */
    private fun jsonEscape(s: String): String = buildString(s.length) {
        for (c in s) {
            when (c) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                else -> if (c.code < 0x20) append("\\u%04x".format(c.code)) else append(c)
            }
        }
    }

    private fun formatTime(ts: Long): String {
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
        return sdf.format(java.util.Date(ts))
    }
}
