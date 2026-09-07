package com.ufi_axis_core.controller.sms

/**
 * 邮件 HTML 模板（2026-08-30 新增）。
 *
 * 原来发的是纯文本，`From: xxx\nTime: xxx\n\n正文` 这种格式在手机邮件客户端里
 * 会被压成一段没有层次的灰字，验证码要靠人眼从正文里找。
 *
 * ## 为什么是"表格 + 内联样式"这种老写法
 * 邮件客户端不是浏览器：Gmail 会剥掉 `<style>` 块里的一部分规则，Outlook（Word 渲染内核）
 * 不支持 flex / grid / 大部分 `border-radius`，QQ 邮箱会重写 body 背景。所以这里只用：
 * - `<table>` 做布局（不用 div+flex）
 * - 全部样式**内联**在标签上（不依赖 `<style>` 存活）
 * - 只用 web 安全字体栈，不引外部字体/图片（外链图片默认被拦，还会泄露阅读行为）
 * - 颜色写死十六进制，不用 CSS 变量
 *
 * 同时仍然发送纯文本副本（`multipart/alternative`）：纯文本客户端与"禁止显示 HTML"
 * 的收件箱会退回文本版，不会看到一堆标签源码。
 */
internal object MailTemplate {

    /** 场景主色：邮件里唯一的视觉分类线索（收件箱按标题排序，颜色帮人一眼分辨类型）。 */
    private val SCENE_COLORS = mapOf(
        "sms" to "#2563eb",
        "verification" to "#7c3aed",
        "alert" to "#dc2626",
        "connectivity" to "#ef4444",
        "traffic80" to "#f59e0b",
        "download" to "#0891b2",
        "tunnel" to "#db2777",
        "events" to "#64748b"
    )

    private val SCENE_LABELS = mapOf(
        "sms" to "新短信",
        "verification" to "验证码",
        "alert" to "阈值告警",
        "connectivity" to "连接状态",
        "traffic80" to "流量预警",
        "download" to "下载任务",
        "tunnel" to "隧道异常",
        "events" to "设备事件"
    )

    private const val ACCENT_DEFAULT = "#2563eb"

    /**
     * 设备状态项的中文名。key 是 `SmsForwardController.buildDeviceInfo` 写出的英文键。
     *
     * 翻译放在模板层而不是采集层：采集出来的 `键: 值` 文本同时喂给纯文本版，
     * 而且 core 其它地方（日志/调试）也读同一份文本，改采集层会牵连更多。
     * 未命中的键原样显示，新增采集项不会因为忘记登记而丢字段。
     */
    private val DEVICE_LABELS = mapOf(
        "Battery" to "电池",
        "Memory" to "内存",
        "CPU" to "CPU",
        "Uptime" to "运行时长"
    )

    /** 匹配「主值 (细节)」，用来把 `78% used (1234MB / 3456MB)` 拆成两行显示。 */
    private val DEVICE_VALUE_NOTE = Regex("""^(.*?)[（(]([^）)]*)[）)]\s*$""")

    /**
     * 一封邮件的渲染输入。
     *
     * @param scene 场景 id（决定主色与徽标文案）；空串走默认蓝
     * @param title 卡片主标题
     * @param meta 有序键值对，渲染成标题下方的元信息行（发件人 / 时间 / 设备…）
     * @param body 正文纯文本；换行保留
     * @param highlight 需要突出的短字符串（如验证码），null 则不渲染高亮块
     * @param deviceInfo `键: 值` 每行一条的设备状态文本；空则不渲染该区块
     */
    data class Mail(
        val scene: String,
        val title: String,
        val meta: List<Pair<String, String>>,
        val body: String,
        val highlight: String? = null,
        val deviceInfo: String = ""
    )

    /** 渲染 HTML 版本。 */
    fun html(mail: Mail): String {
        val accent = SCENE_COLORS[mail.scene] ?: ACCENT_DEFAULT
        val badge = SCENE_LABELS[mail.scene] ?: "通知"

        val metaRows = mail.meta.filter { it.second.isNotBlank() }.joinToString("") { (k, v) ->
            """
            <tr>
              <td style="padding:3px 0;width:76px;color:#8a9099;font-size:12px;line-height:18px;vertical-align:top;">${esc(k)}</td>
              <td style="padding:3px 0;color:#3c4149;font-size:12px;line-height:18px;">${esc(v)}</td>
            </tr>
            """.trimIndent()
        }

        val highlightBlock = mail.highlight?.takeIf { it.isNotBlank() }?.let { code ->
            """
            <tr><td style="padding:0 24px 4px 24px;">
              <div style="background:${accent}0f;border:1px solid ${accent}33;border-radius:10px;padding:14px 16px;text-align:center;">
                <div style="color:#8a9099;font-size:11px;letter-spacing:1px;margin-bottom:6px;">验证码</div>
                <div style="color:$accent;font-size:30px;font-weight:700;letter-spacing:6px;font-family:'SFMono-Regular',Consolas,'Courier New',monospace;">${esc(code)}</div>
              </div>
            </td></tr>
            """.trimIndent()
        } ?: ""

        val deviceBlock = mail.deviceInfo.takeIf { it.isNotBlank() }?.let { info ->
            val cells = info.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.map { line ->
                val idx = line.indexOf(':')
                val rawKey = if (idx > 0) line.substring(0, idx).trim() else line
                val rawValue = if (idx > 0) line.substring(idx + 1).trim() else ""
                val label = DEVICE_LABELS[rawKey] ?: rawKey
                // 「78% used (1234MB / 3456MB)」→ 主值 + 括号细节，合并成同一行显示。
                val m = DEVICE_VALUE_NOTE.find(rawValue)
                // 先 trim 再削 "used"：正则的第 1 组是 `62% used `（带尾空格），
                // 不先 trim 的话 removeSuffix("used") 匹配不上。
                val main = (m?.groupValues?.get(1) ?: rawValue).trim().removeSuffix("used").trim()
                val note = m?.groupValues?.get(2)?.trim().orEmpty()
                val noteText = if (note.isEmpty()) "" else
                    """<span style="color:#a0a6ad;">（${esc(note)}）</span>"""
                // 2026-08-30：从"指标卡"退回纯文本。卡片版（浅底+描边+16px 大数字）体积太大，
                // 把视觉重点从正文/验证码抢走了 —— 设备状态只是附注，不该比正文更抢眼。
                // 每格恒定单行（细节并入同一行），2×2 天然对称，不需要固定高度。
                """
                <td width="50%" valign="top" style="padding:3px 0;font-size:12px;line-height:20px;white-space:nowrap;">
                  <span style="color:#8a9099;">${esc(label)}</span>
                  <span style="color:#3c4149;font-weight:600;">&nbsp;${esc(main)}</span>
                  $noteText
                </td>
                """.trimIndent()
            }.toList()
            // 两列纯文本：chunked(2) 后补空单元格，否则奇数条最后一格会被拉满整行宽度
            val rows = cells.chunked(2).joinToString("") { pair ->
                "<tr>" + pair.joinToString("") + (if (pair.size == 1) "<td width=\"50%\"></td>" else "") + "</tr>"
            }
            """
            <tr><td style="padding:10px 24px 0 24px;">
              <div style="border-top:1px solid #edeff2;padding-top:10px;">
                <div style="color:#8a9099;font-size:11px;line-height:16px;padding-bottom:2px;">设备状态</div>
                <table width="100%" cellpadding="0" cellspacing="0" role="presentation">$rows</table>
              </div>
            </td></tr>
            """.trimIndent()
        } ?: ""

        val bodyBlock = mail.body.takeIf { it.isNotBlank() }?.let { text ->
            """
            <tr><td style="padding:4px 24px 0 24px;">
              <div style="background:#f7f8fa;border-radius:10px;padding:14px 16px;color:#1f2328;font-size:14px;line-height:22px;word-break:break-word;">${nl2br(esc(text))}</div>
            </td></tr>
            """.trimIndent()
        } ?: ""

        return """
<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>${esc(mail.title)}</title>
</head>
<body style="margin:0;padding:0;background:#f1f3f5;">
<!-- 预览文字：收件箱列表里显示在标题后面的那一小段，不放会被抓正文首行的标签残渣 -->
<div style="display:none;max-height:0;overflow:hidden;opacity:0;">${esc(mail.body.take(80))}</div>
<table width="100%" cellpadding="0" cellspacing="0" role="presentation" style="background:#f1f3f5;">
  <tr><td align="center" style="padding:24px 12px;">
    <table width="100%" cellpadding="0" cellspacing="0" role="presentation"
           style="max-width:560px;background:#ffffff;border:1px solid #e6e8eb;border-radius:14px;overflow:hidden;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI','PingFang SC','Microsoft YaHei',sans-serif;">
      <!-- 顶部色条：场景主色，Outlook 不支持渐变，所以用纯色 -->
      <tr><td style="height:4px;background:$accent;line-height:4px;font-size:0;">&nbsp;</td></tr>
      <tr><td style="padding:18px 24px 10px 24px;">
        <span style="display:inline-block;background:${accent}14;color:$accent;font-size:11px;font-weight:600;padding:3px 9px;border-radius:20px;letter-spacing:.5px;">${esc(badge)}</span>
        <div style="margin-top:10px;color:#1f2328;font-size:17px;font-weight:600;line-height:24px;word-break:break-word;">${esc(mail.title)}</div>
      </td></tr>
      <tr><td style="padding:0 24px 12px 24px;">
        <table width="100%" cellpadding="0" cellspacing="0" role="presentation">$metaRows</table>
      </td></tr>
$highlightBlock
$bodyBlock
$deviceBlock
      <tr><td style="padding:18px 24px 20px 24px;">
        <div style="border-top:1px solid #edeff2;padding-top:12px;color:#a0a6ad;font-size:11px;line-height:17px;">
          由 UFI-AXIS 自动发送 · 请勿直接回复<br>
          如需停止此类邮件，在 App「通知与守护 → 邮件通知」中调整转发范围
        </div>
      </td></tr>
    </table>
  </td></tr>
</table>
</body>
</html>
        """.trim()
    }

    /** 渲染纯文本副本（HTML 被拦时的降级视图，内容与 HTML 版一致）。 */
    fun text(mail: Mail): String = buildString {
        appendLine(mail.title)
        mail.meta.filter { it.second.isNotBlank() }.forEach { (k, v) -> appendLine("$k: $v") }
        mail.highlight?.takeIf { it.isNotBlank() }?.let {
            appendLine()
            appendLine("验证码: $it")
        }
        if (mail.body.isNotBlank()) {
            appendLine()
            appendLine(mail.body)
        }
        if (mail.deviceInfo.isNotBlank()) {
            appendLine()
            appendLine("--- 设备状态 ---")
            appendLine(mail.deviceInfo)
        }
        appendLine()
        append("由 UFI-AXIS 自动发送 · 请勿直接回复")
    }

    /**
     * 从短信正文里抓验证码：4-8 位纯数字，且前后不能再接数字。
     *
     * 只在有"验证码/校验码/动态码/code"等提示词时才抓 —— 否则「余额 123456 元」
     * 这种也会被当成验证码顶到高亮块里。
     */
    fun extractCode(body: String): String? {
        val hinted = listOf("验证码", "校验码", "动态码", "口令", "code", "OTP")
            .any { body.contains(it, ignoreCase = true) }
        if (!hinted) return null
        return Regex("""(?<!\d)(\d{4,8})(?!\d)""").find(body)?.groupValues?.get(1)
    }

    private fun esc(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    private fun nl2br(s: String): String = s.replace("\r\n", "\n").replace("\n", "<br>")
}
