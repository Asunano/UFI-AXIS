package com.ufi_axis_core.controller.sms

import android.content.Context
import android.os.BatteryManager
import com.ufi_axis_core.collector.system.SystemCollector
import com.ufi_axis_core.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Properties
import javax.mail.Authenticator
import javax.mail.Message
import javax.mail.PasswordAuthentication
import javax.mail.Session
import javax.mail.Transport
import javax.activation.CommandMap
import javax.activation.MailcapCommandMap
import javax.mail.MessagingException
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeBodyPart
import javax.mail.internet.MimeMessage
import javax.mail.internet.MimeMultipart

/**
 * 邮件通知（原「短信转发」）。
 *
 * 2026-08-29 收敛为**只走 SMTP 邮件**：原来还有 curl Webhook 与钉钉机器人两条支路，
 * 但三条支路共用一个 `method` 单选，意味着永远只有一条在生效，配置面板却要维护三套字段；
 * 而钉钉/Webhook 的实际使用者是零。删掉之后 `enabled` + SMTP 五项就是全部配置。
 * （类名与 `/api/sms-forward/…` 路径保留：web 端与 API 手册都按这个名字引用，
 * 为了改个名去动跨端契约不值得。）
 *
 * 两类进入邮件的内容：
 * - [forwardSms]：设备收到的新短信（BackendService 轮询 + 电池事件沿用此入口）；
 * - [sendSceneNotification]：**app 端的通知**（新短信/验证码/告警/离线…），
 *   由 app 在弹出系统通知后回传给 core（core 才有 SMTP 能力）。按 [SmsForwardConfig.scenes]
 *   逐场景放行 —— 场景开关的唯一真源在这里，app 只负责上报，避免两端各存一份开关。
 */
class SmsForwardController(
    private val context: Context,
    private val systemCollector: SystemCollector? = null
) {
    private val tag = "SmsForwardController"
    private val prefs = context.getSharedPreferences("sms_forward", Context.MODE_PRIVATE)

    init {
        migrateScenesV2()
    }

    /**
     * 场景语义升级（2026-08-31）：短信正文与验证码变成 [SmsForwardConfig.scenes] 里的两个开关。
     *
     * 升级前 `forwardSms` 不看 scenes，设备短信一律转发；直接改成"按 scenes 放行"会让老用户
     * （scenes 里当然没有 sms / verification）突然收不到短信邮件。所以一次性把这两个 id 补进去，
     * 保持升级前后行为一致；补完落标记，用户之后取消勾选不会被再补回来。
     */
    private fun migrateScenesV2() {
        if (prefs.getBoolean(KEY_SCENES_V2_MIGRATED, false)) return
        val scenes = (prefs.getStringSet("scenes", emptySet()) ?: emptySet()).toMutableSet()
        scenes.add("sms")
        scenes.add("verification")
        prefs.edit()
            .putStringSet("scenes", scenes)
            .putBoolean(KEY_SCENES_V2_MIGRATED, true)
            .apply()
    }


    data class SmsForwardConfig(
        val enabled: Boolean = false,
        val smtpHost: String = "",
        val smtpPort: Int = 465,
        val smtpUser: String = "",
        val smtpPass: String = "",
        val smtpFrom: String = "",
        val smtpTo: String = "",
        val forwardDevInfo: Boolean = false,
        val blacklist: List<String> = emptyList(),
        /**
         * 允许转发邮件的场景 id（对应 app 侧 `NotifyScene.sceneId`：
         * sms / verification / alert / connectivity / download / traffic80 / events / tunnel）。
         *
         * **设备短信也在这里管**：正文走 `sms`、抓到验证码走 `verification`（2026-08-31 起，
         * 见 `migrateScenesV2`）。内部触发（电池事件 `SYSTEM`、`/test`）不受约束。
         */
        val scenes: Set<String> = emptySet()

    )

    fun loadConfig(): SmsForwardConfig = SmsForwardConfig(
        enabled = prefs.getBoolean("enabled", false),
        smtpHost = prefs.getString("smtp_host", "") ?: "",
        smtpPort = prefs.getInt("smtp_port", 465),
        smtpUser = prefs.getString("smtp_user", "") ?: "",
        smtpPass = prefs.getString("smtp_pass", "") ?: "",
        smtpFrom = prefs.getString("smtp_from", "") ?: "",
        smtpTo = prefs.getString("smtp_to", "") ?: "",
        forwardDevInfo = prefs.getBoolean("forward_dev_info", false),
        blacklist = (prefs.getStringSet("blacklist", emptySet()) ?: emptySet()).toList(),
        scenes = prefs.getStringSet("scenes", emptySet()) ?: emptySet()
    )

    fun saveConfig(config: SmsForwardConfig) {
        prefs.edit().apply {
            putBoolean("enabled", config.enabled)
            putString("smtp_host", config.smtpHost)
            putInt("smtp_port", config.smtpPort)
            putString("smtp_user", config.smtpUser)
            putString("smtp_pass", config.smtpPass)
            putString("smtp_from", config.smtpFrom)
            putString("smtp_to", config.smtpTo)
            putBoolean("forward_dev_info", config.forwardDevInfo)
            putStringSet("blacklist", config.blacklist.toSet())
            putStringSet("scenes", config.scenes)
        }.commit()  // 同步写入，确保后续读取能拿到最新值
    }

    /**
     * 发信计数（app 端「总发送/成功/失败」统计卡的数据源）。
     *
     * 只统计**真正发起过 SMTP 投递**的次数：黑名单拦截、场景未勾选、配置不全
     * 都不是"发送失败"，计进去会让失败数虚高、用户误以为 SMTP 有问题。
     */
    data class MailStats(
        val success: Int = 0,
        val failed: Int = 0,
        val lastSentAt: Long = 0L,
        val lastError: String = ""
    ) {
        val total: Int get() = success + failed
    }

    /**
     * 已投递过的最新短信 id（-1 = 还没有基线）。
     *
     * 2026-08-31 从 `BackendService` 的内存字段挪到这里持久化：内存版在服务重建/进程被杀后
     * 归零，只能靠"短信新鲜度窗口"防止把历史短信重发一遍，而窗口同时也会把**发现得晚**的
     * 新短信一起判成历史 → 邮件永久丢失。id 落盘之后判重不再依赖时钟，窗口只留一个很宽的
     * 上限兜住"换 id 体系/首次运行"。
     */
    var lastForwardedSmsId: Long
        get() = prefs.getLong(KEY_LAST_FORWARDED_SMS_ID, -1L)
        set(value) { prefs.edit().putLong(KEY_LAST_FORWARDED_SMS_ID, value).apply() }

    fun loadStats(): MailStats = MailStats(
        success = prefs.getInt(KEY_STAT_SUCCESS, 0),
        failed = prefs.getInt(KEY_STAT_FAILED, 0),
        lastSentAt = prefs.getLong(KEY_STAT_LAST_AT, 0L),
        lastError = prefs.getString(KEY_STAT_LAST_ERROR, "") ?: ""
    )

    private fun recordSuccess() {
        prefs.edit()
            .putInt(KEY_STAT_SUCCESS, prefs.getInt(KEY_STAT_SUCCESS, 0) + 1)
            .putLong(KEY_STAT_LAST_AT, System.currentTimeMillis())
            .putString(KEY_STAT_LAST_ERROR, "")
            .apply()
    }

    private fun recordFailure(reason: String) {
        prefs.edit()
            .putInt(KEY_STAT_FAILED, prefs.getInt(KEY_STAT_FAILED, 0) + 1)
            .putString(KEY_STAT_LAST_ERROR, reason.take(200))
            .apply()
    }

    /**
     * 把异常链摊平成一行。
     *
     * JavaMail 的外层异常经常毫无信息量 —— `MessagingException: IOException while sending message`
     * 只说明"写流的时候炸了"，真正原因（`UnsupportedDataTypeException: no object DCH for MIME
     * type multipart/alternative`、`SSLHandshakeException`、`SocketTimeoutException`…）
     * 挂在 `nextException` 或 `cause` 上。只记外层等于把根因扔了。
     */
    private fun describeFailure(e: Throwable): String {
        val parts = mutableListOf<String>()
        var cur: Throwable? = e
        var depth = 0
        while (cur != null && depth < 4) {
            parts.add("${cur.javaClass.simpleName}: ${cur.message ?: "-"}")
            cur = (cur as? MessagingException)?.nextException ?: cur.cause
            depth++
        }
        return parts.joinToString(" <- ")
    }


    /** 配置是否可用于发信（缺任一必填项都发不出去，调用方据此提前退出/提示）。 */
    fun isSendable(cfg: SmsForwardConfig = loadConfig()): Boolean =
        cfg.enabled && cfg.smtpHost.isNotBlank() && cfg.smtpUser.isNotBlank() &&
            cfg.smtpPass.isNotEmpty() && cfg.smtpTo.isNotBlank()

    suspend fun forwardSms(from: String, body: String, timestamp: Long): Boolean {
        val cfg = loadConfig()
        if (!isSendable(cfg)) return false
        val code = MailTemplate.extractCode(body)
        // 短信正文 / 验证码是两个独立开关（场景 id 与 app 的 NotifyScene 对齐）：
        // 抓到验证码走 verification，否则走 sms。SYSTEM 这类内部触发（电池事件等）
        // 不是设备短信，不受这两个开关约束。
        if (from != SYSTEM_SOURCE && from != TEST_SOURCE) {

            val scene = if (code != null) "verification" else "sms"
            if (scene !in cfg.scenes) {
                AppLogger.i(tag, "SMS mail skipped: scene '$scene' not enabled")
                return false
            }
        }
        if (cfg.blacklist.isNotEmpty() && cfg.blacklist.any {
                it.isNotBlank() && (from.contains(it, ignoreCase = true) || body.contains(it, ignoreCase = true))
            }) {
            AppLogger.i(tag, "SMS blocked by blacklist: $from")
            return false
        }
        return withContext(Dispatchers.IO) {
            try {
                val mail = MailTemplate.Mail(
                    // 抓到验证码就按验证码场景渲染（紫色 + 大号高亮块），否则按新短信
                    scene = if (code != null) "verification" else "sms",
                    title = if (code != null) "验证码 $code" else "来自 $from 的短信",
                    meta = listOf(
                        "发件人" to from,
                        "接收时间" to formatTime(timestamp)
                    ),
                    body = body,
                    highlight = code,
                    deviceInfo = if (cfg.forwardDevInfo) buildDeviceInfo() else ""
                )
                // 验证码短信直接把码放进主题：收件箱列表只显示主题，截断正文会把码挤掉
                // （"【抖音】你的账号正在新设备上登录，验证码2028，请勿转发…" 前 37 字未必含码）。
                // 非验证码短信仍取正文摘要，放"新短信"这种固定词等于没信息。
                val subject = when {
                    code != null -> "验证码：$code"
                    body.length > 37 -> body.substring(0, 37) + "..."
                    else -> body
                }
                sendMail(cfg, subject, mail)
            } catch (e: Exception) {
                AppLogger.e(tag, "SMS forward failed", e); false
            }
        }
    }

    /**
     * 转发一条 **app 通知**到邮箱。
     *
     * @param scene app 侧 `NotifyScene.sceneId`；不在 [SmsForwardConfig.scenes] 里直接丢弃。
     * @return true=已发出；false=未启用/场景未勾选/配置不全/发送失败（都不抛，调用方不该因此报错）
     */
    suspend fun sendSceneNotification(scene: String, title: String, body: String): Boolean {
        val cfg = loadConfig()
        if (!isSendable(cfg)) return false
        if (scene !in cfg.scenes) return false
        return withContext(Dispatchers.IO) {
            try {
                val mail = MailTemplate.Mail(
                    scene = scene,
                    title = title,
                    meta = listOf("触发时间" to formatTime(System.currentTimeMillis())),
                    body = body,
                    deviceInfo = if (cfg.forwardDevInfo) buildDeviceInfo() else ""
                )
                sendMail(cfg, "[UFI-AXIS] $title", mail)
            } catch (e: Exception) {
                AppLogger.w(tag, "scene notification mail failed: ${e.message}")
                false
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

    /**
     * 唯一的发信实现（短信转发与场景通知共用）。
     *
     * 发 `multipart/alternative`：先挂纯文本再挂 HTML —— 顺序不能反，
     * 邮件客户端取的是**最后一个**它能渲染的分段，反了就永远显示纯文本。
     *
     * 失败抛异常，由调用方决定要不要吞；成功/失败都会记进 [MailStats]。
     */
    private fun sendMail(cfg: SmsForwardConfig, subject: String, mail: MailTemplate.Mail): Boolean {
        // 投递期间自己持锁：本方法的调用方五花八门（短信 observer、电池事件、告警引擎、
        // app 回传的 HTTP 请求），指望上层都记得持锁不现实；而 core 唯一那把保活 WakeLock
        // 只在前端连接时续期，前端断开后 SMTP 握手随时可能被 CPU 深睡打断。
        // 锁带超时兜底，finally 释放，取不到锁也照常发（只是可能被打断）。
        val wakeLock = try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "UfiAxisCore::MailSmtp").apply {
                setReferenceCounted(false)
                acquire(SMTP_WAKELOCK_TIMEOUT_MS)
            }
        } catch (e: Exception) {
            AppLogger.w(tag, "SMTP WakeLock 获取失败（投递可能被深睡打断）: ${e.message}")
            null
        }
        try {
            return sendMailLocked(cfg, subject, mail)
        } finally {
            try {
                if (wakeLock != null && wakeLock.isHeld) wakeLock.release()
            } catch (_: Exception) {}
        }
    }

    private fun sendMailLocked(cfg: SmsForwardConfig, subject: String, mail: MailTemplate.Mail): Boolean {

        ensureMailcap()
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

        val msg = MimeMessage(session).apply {
            setFrom(InternetAddress(cfg.smtpFrom.ifBlank { cfg.smtpUser }))
            setRecipients(Message.RecipientType.TO, InternetAddress.parse(cfg.smtpTo))
            setSubject(subject, "UTF-8")
            setContent(MimeMultipart("alternative").apply {
                addBodyPart(MimeBodyPart().apply {
                    setText(MailTemplate.text(mail), "UTF-8")
                })
                addBodyPart(MimeBodyPart().apply {
                    setContent(MailTemplate.html(mail), "text/html; charset=UTF-8")
                })
            })
        }
        try {
            Transport.send(msg)
            AppLogger.i(tag, "mail sent to ${cfg.smtpTo}")
            recordSuccess()
            return true
        } catch (e: Exception) {
            val detail = describeFailure(e)
            AppLogger.e(tag, "SMTP send failed: $detail", e)
            recordFailure(detail)
            throw e  // 让上层 catch 记录完整异常
        }
    }

    /**
     * 注册 JavaMail 的 DataContentHandler（Android 上必须手动做，一次性）。
     *
     * `MimeMessage.setContent(Multipart)` / `text/html` 分段在写流时会通过
     * `javax.activation.CommandMap` 反查 handler。桌面 JDK 靠 jar 里的
     * `META-INF/mailcap` 自动注册，但 APK 打包会把各依赖的 META-INF 合并/丢弃，
     * 查不到 handler 就抛 `UnsupportedDataTypeException`，被 JavaMail 包成
     * **`MessagingException: IOException while sending message`** —— 外层报文完全看不出根因。
     *
     * 这也是 2026-08-30 换 HTML 模板后发信开始失败的原因：之前用 `setText()`（纯 text/plain）
     * 走的是不需要查表的快路径，改成 multipart + text/html 后才踩到。
     */
    private fun ensureMailcap() {
        if (mailcapRegistered) return
        synchronized(SmsForwardController::class.java) {
            if (mailcapRegistered) return
            val mc = (CommandMap.getDefaultCommandMap() as? MailcapCommandMap) ?: MailcapCommandMap()
            mc.addMailcap("text/plain;; x-java-content-handler=com.sun.mail.handlers.text_plain")
            mc.addMailcap("text/html;; x-java-content-handler=com.sun.mail.handlers.text_html")
            mc.addMailcap("multipart/*;; x-java-content-handler=com.sun.mail.handlers.multipart_mixed")
            mc.addMailcap("message/rfc822;; x-java-content-handler=com.sun.mail.handlers.message_rfc822")
            CommandMap.setDefaultCommandMap(mc)
            mailcapRegistered = true
        }
    }

    private fun formatTime(ts: Long): String {
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
        return sdf.format(java.util.Date(ts))
    }

    private companion object {
        const val KEY_STAT_SUCCESS = "stat_success"
        const val KEY_STAT_FAILED = "stat_failed"
        const val KEY_STAT_LAST_AT = "stat_last_at"
        const val KEY_STAT_LAST_ERROR = "stat_last_error"
        const val KEY_LAST_FORWARDED_SMS_ID = "last_forwarded_sms_id"
        const val KEY_SCENES_V2_MIGRATED = "scenes_migrated_v2"

        /** 内部触发（电池事件等）的伪发件人：不是设备短信，不受 sms/verification 场景开关约束。 */
        const val SYSTEM_SOURCE = "SYSTEM"
        /** `POST /api/sms-forward/test` 的伪发件人，同样绕过场景开关。 */
        const val TEST_SOURCE = "test"


        /** 单次 SMTP 投递的持锁上限（连接/读超时各 10s，留足重试余量，超时自动释放兜住泄漏）。 */
        const val SMTP_WAKELOCK_TIMEOUT_MS = 60_000L



        /** CommandMap 是进程级全局的，注册一次即可（见 [ensureMailcap]）。 */
        @Volatile
        var mailcapRegistered = false
    }
}
