package com.ufi_axis_core.controller.notify

/**
 * Webhook 内置预设：**只是"帮用户把模板填好"的默认值表**。
 *
 * ## 为什么不做成运行时分支
 *
 * 存储里存的是**最终的** url / method / headers / body（用户可以随便改），[WebhookConfig.preset]
 * 只用于 UI 回显"当初选的是哪一个"。如果按 preset 在投递路径上分支，七个预设就是七套
 * 各自的投递代码 —— 用户改了模板之后行为还会跟 UI 显示的不一致（他改的是模板，
 * 生效的是分支里写死的那份）。所以 [WebhookChannel.deliver] 里**没有** `when (preset)`。
 *
 * ## 占位符
 *
 * 模板里可用 [WebhookDelivery.PLACEHOLDERS] 那几个。JSON 类 body 的值会先做 JSON 转义
 * 再替换（正文里的引号 / 换行不会把 JSON 撑破，见 [WebhookDelivery.renderBody]）。
 *
 * ## 「用户要填的那一样东西」
 *
 * 七个预设里用户真正要填的只有一样（device key / topic / SENDKEY / token…），但它的位置
 * 不同：多数在 URL 里，PushPlus 在**请求体**里。[secretLabel] / [secretMarker] /
 * [secretTarget] 把这件事声明成机器可读的元数据，客户端据此只渲染**一个**输入框、
 * 其余字段收进「高级设置」。
 *
 * **这三个字段只给 UI 与 REST 响应用，不进投递路径** —— 投递读的仍然是存储里那份最终
 * url / method / headers / body（理由见上一节）。
 *
 * @param displayName 给 UI 的名字。
 * @param userFills 用户必须自己填的东西，**给人读的一句话**（[secretLabel] 是同一件事的
 *   机器可读版本：那个只有输入框的名字，这个还能带上"服务器地址也可以换"这类补充）。
 * @param secretLabel 用户要填的那一样东西的标签（UI 上输入框的名字），无需填写时为空串。
 * @param secretMarker 默认值里代表它的标记，例如 `"<token>"`；无需填写时为空串。
 * @param secretTarget 那个标记在哪一处：URL 里还是请求体模板里。
 * @param defaultUrl 带 `<占位>` 的示例地址。**不是可用地址** —— [WebhookDelivery.isConfigured]
 *   会因为它含 `<` 而判定未配置齐全，逼用户真的去填。
 */
enum class WebhookPreset(
    val displayName: String,
    val userFills: String,
    val secretLabel: String,
    val secretMarker: String,
    val secretTarget: SecretTarget,
    val defaultUrl: String,
    val defaultMethod: String,
    val defaultHeaders: Map<String, String>,
    val defaultContentType: String,
    val defaultBody: String
) {

    /**
     * 全手填。默认模板只摊**常用的**六个占位符 —— 十个全塞进去这份默认体就没法读了，
     * 其余几个靠 UI 上的占位符插入按钮（清单与说明由 [WebhookDelivery.PLACEHOLDERS] 回给客户端）。
     */
    CUSTOM(
        displayName = "自定义",
        userFills = "完整 URL 与请求体模板",
        secretLabel = "",
        secretMarker = "",
        secretTarget = SecretTarget.NONE,
        defaultUrl = "",
        defaultMethod = "POST",
        defaultHeaders = emptyMap(),
        defaultContentType = JSON_CONTENT_TYPE,
        defaultBody = CUSTOM_DEFAULT_BODY
    ),


    /** Bark（iOS）。`level` 值 Bark 认不出时按 active 处理，不影响送达。 */
    BARK(
        displayName = "Bark",
        userFills = "服务器地址 + device key（URL 末段）",
        secretLabel = "Device Key",
        secretMarker = "<device_key>",
        secretTarget = SecretTarget.URL,
        defaultUrl = "https://api.day.app/<device_key>",
        defaultMethod = "POST",
        defaultHeaders = emptyMap(),
        defaultContentType = JSON_CONTENT_TYPE,
        defaultBody = """{"title":"{{title}}","body":"{{message}}","level":"{{level}}"}"""
    ),

    /**
     * ntfy。**唯一一个纯文本 body 的预设**：ntfy 把请求体原样当消息正文，标题走 `Title` 头。
     *
     * `Priority` 默认给固定值 `default` 而不是 `{{level}}`：ntfy 只认
     * `min/low/default/high/max`（或 1..5），塞 `warning` / `critical` 会被 400 拒掉 ——
     * 那样预设一开箱就是坏的。级别改放 `Tags`（ntfy 的标签是自由字符串，会显示在消息上）。
     */
    NTFY(
        displayName = "ntfy",
        userFills = "服务器地址 + topic（URL 末段）",
        secretLabel = "Topic",
        secretMarker = "<topic>",
        secretTarget = SecretTarget.URL,
        defaultUrl = "https://ntfy.sh/<topic>",
        defaultMethod = "POST",
        defaultHeaders = mapOf(
            "Title" to "{{title}}",
            "Priority" to "default",
            "Tags" to "{{level}}"
        ),
        defaultContentType = TEXT_CONTENT_TYPE,
        defaultBody = "{{message}}"
    ),

    /** 企业微信群机器人。标题与正文之间用 `\n`（JSON 转义的换行），机器人只认一个 content 字段。 */
    WECOM(
        displayName = "企业微信群机器人",
        userFills = "机器人 Webhook 地址（含 key 参数）",
        secretLabel = "机器人 Key",
        secretMarker = "<key>",
        secretTarget = SecretTarget.URL,
        defaultUrl = "https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=<key>",
        defaultMethod = "POST",
        defaultHeaders = emptyMap(),
        defaultContentType = JSON_CONTENT_TYPE,
        defaultBody = """{"msgtype":"text","text":{"content":"{{title}}\n{{message}}"}}"""
    ),

    /** 飞书群机器人。 */
    FEISHU(
        displayName = "飞书群机器人",
        userFills = "机器人 Webhook 地址（含 token 末段）",
        secretLabel = "机器人 Token",
        secretMarker = "<token>",
        secretTarget = SecretTarget.URL,
        defaultUrl = "https://open.feishu.cn/open-apis/bot/v2/hook/<token>",
        defaultMethod = "POST",
        defaultHeaders = emptyMap(),
        defaultContentType = JSON_CONTENT_TYPE,
        defaultBody = """{"msg_type":"text","content":{"text":"{{title}}\n{{message}}"}}"""
    ),

    /**
     * PushPlus。token 在 body 里，所以 URL 是固定的、不需要用户改。
     *
     * URL 用 **https**（官方站点就是 https；`HttpNotifier.isAllowedUrl` 两种 scheme 都放行，
     * 所以这只是别让默认值把用户推到明文链路上）。**已存配置不受影响** ——
     * 存储里是用户自己那份 url，预设表只在"选一次预设"时被抄进去。
     */
    PUSHPLUS(
        displayName = "PushPlus",
        userFills = "请求体模板里的 PushPlus Token",
        secretLabel = "PushPlus Token",
        secretMarker = "<token>",
        secretTarget = SecretTarget.BODY,
        defaultUrl = "https://www.pushplus.plus/send",
        defaultMethod = "POST",
        defaultHeaders = emptyMap(),
        defaultContentType = JSON_CONTENT_TYPE,
        defaultBody = """{"token":"<token>","title":"{{title}}","content":"{{message}}"}"""
    ),

    /** Server 酱。正文字段名是 `desp`（不是 message），SENDKEY 在 URL 里。 */
    SERVERCHAN(
        displayName = "Server 酱",
        userFills = "SENDKEY（URL 末段）",
        secretLabel = "SENDKEY",
        secretMarker = "<SENDKEY>",
        secretTarget = SecretTarget.URL,
        defaultUrl = "https://sctapi.ftqq.com/<SENDKEY>.send",
        defaultMethod = "POST",
        defaultHeaders = emptyMap(),
        defaultContentType = JSON_CONTENT_TYPE,
        defaultBody = """{"title":"{{title}}","desp":"{{message}}"}"""
    );

    companion object {
        /** 认不出的名字回落 [CUSTOM]：preset 只影响 UI 回显，不该让整份配置读不出来。 */
        fun fromName(name: String?): WebhookPreset =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: CUSTOM

        /**
         * 自校验：[secretMarker] 真的出现在它声明的那一处默认值里吗？返回**不合格**的预设。
         *
         * 存在的理由：这张 marker 表与默认 url / body 是**两处手写的东西**。改了默认 URL
         * 却忘了改 marker，客户端就会把用户填的密钥替换到一个不存在的标记上 ——
         * 界面上完全看不出原因（输入框填了、配置也存下了），表现只是"一条通知都收不到"。
         *
         * 为什么是"返回清单 + 单测钉住"而不是 `init { require(...) }`：判据只由常量决定，
         * 编译期就定死了，单测一定会红；而 init 里抛异常会让一个笔误升级成
         * **首次访问预设表时整个进程崩**（通知配置页、投递路径都要读它）。
         */
        fun secretMarkerMismatches(): List<WebhookPreset> = entries.filter { p ->
            when (p.secretTarget) {
                SecretTarget.NONE -> false
                SecretTarget.URL -> !p.defaultUrl.contains(p.secretMarker)
                SecretTarget.BODY -> !p.defaultBody.contains(p.secretMarker)
            }
        }
    }
}

/**
 * 预设里"用户要填的那一样东西"藏在哪一处。
 *
 * 客户端据此决定那个唯一的输入框改哪个字段：[URL] 档替换 `url` 里的
 * [WebhookPreset.secretMarker]，[BODY] 档替换 `body_template` 里的它。
 * 不用"要不要填"的布尔：布尔分不出这两处，而替换错地方的表现是密钥根本没进请求。
 */
enum class SecretTarget {
    /** 无需单独填（[WebhookPreset.CUSTOM]：URL 与模板全手填）。 */
    NONE,

    /** 标记在 [WebhookPreset.defaultUrl] 里。 */
    URL,

    /** 标记在 [WebhookPreset.defaultBody] 里（PushPlus）。 */
    BODY;

    /** REST 响应里的小写口径（`secret_target`）。与 `NotifyLevel.wireName` 同做法。 */
    val wireName: String get() = name.lowercase()
}


/** JSON 类 body 的 Content-Type。占位符替换会走 JSON 转义分支（判据见 [WebhookDelivery.isJson]）。 */
internal const val JSON_CONTENT_TYPE = "application/json; charset=utf-8"

/** 纯文本 body 的 Content-Type（ntfy）。 */
internal const val TEXT_CONTENT_TYPE = "text/plain; charset=utf-8"

/**
 * [WebhookPreset.CUSTOM] 的默认请求体（六个常用占位符的单行 JSON）。
 *
 * 抽成常量只为不超行宽 —— 拼接出来的仍然是**一行**（JSON 里换行虽然合法，但那会让用户
 * 打开设置页看到一个多行模板，与他自己填的单行模板长得不一样）。所以这里是字符串拼接，
 * 不是多行 raw string。放在文件顶层而不是伴生对象里：枚举项的构造参数在伴生对象初始化
 * 之前就要取值，只有编译期常量能安全引用（同 [JSON_CONTENT_TYPE] 的既有做法）。
 */
private const val CUSTOM_DEFAULT_BODY =
    """{"title":"{{title}}","message":"{{message}}","level":"{{level}}",""" +
        """"scene":"{{scene}}","type":"{{type}}","time":"{{time}}"}"""

