package com.ufi_axis_core.controller.notify

import android.content.Context
import android.content.SharedPreferences
import com.ufi_axis_core.notify.ChannelRules
import com.ufi_axis_core.notify.DeliveryOutcome
import com.ufi_axis_core.notify.NotifyLevel
import com.ufi_axis_core.notify.NotifyScenes
import com.ufi_axis_core.util.AppLogger
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.time.LocalDate

/**
 * 通用 Webhook 渠道的配置。
 *
 * ## 为什么独立存一份 prefs 而不进 `NotificationConfig`
 *
 * `NotificationConfig`（`NotificationRoutes.kt:92-94`）明确写了「渠道启用位不放这里」：
 * 那份模型是**客户端要不要投递通知**的开关组，一个渠道自己的 URL / 模板 / 超时塞进去
 * 会让它变成"所有渠道配置的大杂烩"，而且每加一个渠道都要改那份跨端契约。
 * 做法沿用邮件（`SmsForwardController.kt:61` 的 `sms_forward` prefs）：**渠道配置归渠道自己**。
 *
 * ## `respectDnd` 与 `respectsMasterGate` 是两件事
 *
 * - [WebhookChannel.respectsMasterGate] 是**渠道级常量**（恒 true）：Webhook 和邮件一样是
 *   设备主动发出去的终态投递，用户关了总开关就是不想收。
 * - [respectDnd] 是**用户配置**：免打扰时段要不要连 Webhook 一起静默。默认 **true** ——
 *   Webhook 的落点（Bark / ntfy / 企业微信）跟状态栏一样会响铃，半夜静音的诉求对它成立；
 *   邮件默认 false 是因为邮件是"事后可查"的渠道（见 `NotificationConfig.mail_respect_dnd`）。
 */
data class WebhookConfig(
    val enabled: Boolean = false,
    /**
     * 当前选中的预设。**不参与投递判定**（投递读的永远是下面那份最终 url / 模板，见 [WebhookPreset]），
     * 但它决定**每预设那一份字段存到哪一组键下**（见 [WebhookPrefsKeys]）：
     * `preset = BARK` 时 [url] / [bodyTemplate] 等读写的是 `bark.*` 那组。
     */
    val preset: WebhookPreset = WebhookPreset.CUSTOM,
    val url: String = "",
    /** POST / GET / PUT 之一（真源 [ALLOWED_METHODS]）。 */
    val method: String = DEFAULT_METHOD,
    val headers: Map<String, String> = emptyMap(),
    val bodyTemplate: String = WebhookPreset.CUSTOM.defaultBody,
    val contentType: String = JSON_CONTENT_TYPE,
    val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    /**
     * 最低投递级别。**默认 [NotifyLevel.INFO]** = 什么都放行。
     *
     * 2026-09-10「第二步：规则同构」补的旋钮（三条渠道形状一样，见 [ChannelRules]）。
     * 默认取 INFO 而不是抄本机短信那份 CRITICAL：这次改造**不能改变存量用户观察到的行为**，
     * 而 Webhook 此前没有级别门槛 —— 默认给个 CRITICAL 等于让所有人升级后突然收不到通知。
     * 想只收关键事件的用户自己把它调上去（这条渠道不花钱，默认收全比默认漏掉更安全）。
     */
    val minLevel: NotifyLevel = NotifyLevel.INFO,
    /**
     * 每日条数上限；**默认 [ChannelRules.UNLIMITED]** = 不限（理由同 [minLevel]：保持现有行为）。
     *
     * 计数器是本渠道独占的（`notify_webhook` 的 `quota_day` / `quota_count`），
     * 与邮件、本机短信那两份物理隔离 —— 共享计数器意味着一条渠道发多了会吃掉另一条的额度。
     */
    val dailyLimit: Int = ChannelRules.UNLIMITED,
    /** 勾选的场景 id（与邮件同一套词表 [NotifyScenes]）。 */
    val scenes: Set<String> = emptySet(),
    val respectDnd: Boolean = true
) {
    companion object {
        /** 允许的请求方法。真源在 [HttpNotifier]（它才是真正把名字翻成 HttpMethod 的那一处）。 */
        val ALLOWED_METHODS: Set<String> = HttpNotifier.METHOD_NAMES

        /**
         * 模板里可用的占位符 **名 → 一句话说明**。真源是 [WebhookDelivery.PLACEHOLDERS]
         * （渲染时真正替换的那一份），顺序即展示顺序。
         *
         * 在这里再露一次是因为 `WebhookDelivery` 是 `internal`（纯判定、只在本模块内测），
         * 而 `:core:api` 的 `GET /api/notify/webhook/config` 要把这份清单回给 UI ——
         * 客户端不该手抄这些名字与说明（抄错的表现是"填了占位符却不替换"、
         * 或说明与实际取值对不上，界面上都看不出原因）。
         */
        val PLACEHOLDERS: Map<String, String> = WebhookDelivery.PLACEHOLDERS



        const val DEFAULT_METHOD = "POST"

        /**
         * 默认超时 10s，与 SMTP 的单次尝试预算同一量级
         * （`SmsForwardController.SMTP_TIMEOUT_MS`）—— 通知不是延迟敏感路径，
         * 但也不能让一个不响应的目标把整轮重试拖到几分钟。
         */
        const val DEFAULT_TIMEOUT_MS = 10_000L

        /**
         * 超时允许区间。
         *
         * 下限 1s：再短连 TLS 握手都做不完，等于配了个必然失败的渠道。
         * 上限 60s：三次尝试 × 60s + 退避 8s 已经接近 3 分钟，而这段时间里
         * `TrafficAutoOffGuard` 那类"等通报到位再动作"的链路会一直悬着。
         */
        const val MIN_TIMEOUT_MS = 1_000L
        const val MAX_TIMEOUT_MS = 60_000L

        /**
         * 每日条数上限的允许区间。**0 = 不限**（[ChannelRules.UNLIMITED]，也是默认值）。
         *
         * 上限 1000：HTTP 不花钱，这道闸防的是**下游被刷** —— Bark / ntfy / 企业微信机器人
         * 都有自己的频率限制，被判成滥用之后用户看到的是"整条渠道突然全失效"。
         * 1000 条/天已经是平均每 1.4 分钟一条，再多就不是通知而是日志流，
         * 该走 WS 推送而不是逐条 POST。
         *
         * 与本机短信那份（1..50，下限 1）的差别就在这个 0：短信"一天只允许一条"是合理诉求、
         * "不限"是账单事故；Webhook 恰好相反。
         */
        const val MIN_DAILY_LIMIT = ChannelRules.UNLIMITED
        const val MAX_DAILY_LIMIT = 1000

        /** 级别的线上口径小写名（`info` / `warning` / `critical`），供 REST 回给客户端渲染下拉。 */
        val LEVEL_NAMES: List<String> = NotifyLevel.entries.map { it.wireName }


        /**
         * 返回第一条约束违规说明；全部合法返回 null。口径与 `NotificationRoutes.validate` 一致。
         *
         * URL 为空**是合法的**（= 还没配完，[WebhookDelivery.isConfigured] 会判它不可投），
         * 但填了就必须是 http(s)：这道校验让"存下去之后才发现永远发不出去"提前到 PUT 时报错。
         */
        fun validate(c: WebhookConfig): String? = when {
            c.url.isNotBlank() && !HttpNotifier.isAllowedUrl(c.url) ->
                "url 必须以 http:// 或 https:// 开头，收到 ${c.url.take(URL_ECHO_CHARS)}"
            c.method.uppercase() !in ALLOWED_METHODS ->
                "method 只支持 ${ALLOWED_METHODS.joinToString("/")}，收到 ${c.method}"
            c.timeoutMs !in MIN_TIMEOUT_MS..MAX_TIMEOUT_MS ->
                "timeout_ms 必须在 $MIN_TIMEOUT_MS..$MAX_TIMEOUT_MS 之间，收到 ${c.timeoutMs}"
            c.dailyLimit !in MIN_DAILY_LIMIT..MAX_DAILY_LIMIT ->
                "每日条数上限（daily_limit）需在 $MIN_DAILY_LIMIT..$MAX_DAILY_LIMIT 之间" +
                    "（$MIN_DAILY_LIMIT = 不限），收到 ${c.dailyLimit}"
            c.contentType.isBlank() -> "content_type 不能为空"
            c.headers.keys.any { it.isBlank() || it.any(::isLineBreak) } ->
                "header 名不能为空、不能含换行"
            c.headers.values.any { v -> v.any(::isLineBreak) } ->
                "header 值不能含换行（换行会被下游解析成额外的头部）"
            // 场景 id 打错字的话界面上勾了却永远不触发，而日志里看不出任何异常 —— 提前拒掉。
            (c.scenes - NotifyScenes.ALL).isNotEmpty() ->
                "scenes 含未知场景：${(c.scenes - NotifyScenes.ALL).sorted().joinToString(",")}"
            else -> null
        }

        private fun isLineBreak(ch: Char): Boolean = ch == '\r' || ch == '\n'

        /** 报错里回显 URL 的长度上限（URL 里常带 token，不整条打出来）。 */
        private const val URL_ECHO_CHARS = 40
    }
}

/**
 * `notify_webhook` prefs 的**键分层**：哪些键跨预设共享、哪些键每个预设各存一份。
 *
 * ## 分界线：「这条渠道要不要发、什么时候发」 vs 「发到哪、怎么发」
 *
 * - **顶层（跨预设共享）**：[PRESET] / [ENABLED] / [SCENES] / [RESPECT_DND] / [MIN_LEVEL] /
 *   [DAILY_LIMIT] —— 这些是"这条渠道要不要发、什么时候发"。用户勾了六个场景、把级别调到
 *   warning，换个目标之后这些诉求一个字都没变，跟着预设各存一份只会让他每换一次重勾一遍。
 * - **每预设一份（键名带 `<预设名小写>.` 前缀）**：[URL] / [METHOD] / [HEADERS] / [BODY] /
 *   [CONTENT_TYPE] / [TIMEOUT_MS] —— 这些是"发到哪、怎么发"，**换目标就是换这一整组**。
 *   Bark 的 device key 和企业微信的 key 之间没有任何关系，共用一份存储的唯一效果就是
 *   切一次预设覆盖一次（用户原话：「不要切换一次就清除一次」）。
 *
 * [TIMEOUT_MS] 刻意归后者：不同目标的响应速度不是同一件事（自建 ntfy 在内网、Server 酱在公网），
 * 它和 url 一起构成"这个目标怎么连"。
 *
 * 配额计数器（`quota_day` / `quota_count`）留在顶层：那是**渠道级**额度。跟着预设分家的话
 * 用户换个预设就能重开一天的量 —— 等于把"每日条数上限"这道闸做成假开关。
 */
internal object WebhookPrefsKeys {

    // ── 顶层（跨预设共享） ──
    const val PRESET = "preset"
    const val ENABLED = "enabled"
    const val SCENES = "scenes"
    const val RESPECT_DND = "respect_dnd"
    const val MIN_LEVEL = "min_level"
    const val DAILY_LIMIT = "daily_limit"

    /** 配额的"哪一天"（`yyyy-MM-dd`，本地日期）与"那天发了几条"。**本渠道独占这一对键。** */
    const val QUOTA_DAY = "quota_day"
    const val QUOTA_COUNT = "quota_count"

    // ── 每预设一份（真键名是 `<预设名小写>.` + 下面这些字段名） ──
    const val URL = "url"
    const val METHOD = "method"
    const val HEADERS = "headers_json"
    const val BODY = "body_template"
    const val CONTENT_TYPE = "content_type"
    const val TIMEOUT_MS = "timeout_ms"

    /** 每预设字段里的 String 型五个（[TIMEOUT_MS] 是 Long，单独处理 —— prefs 是强类型读的）。 */
    val PER_PRESET_STRING_FIELDS: List<String> =
        listOf(URL, METHOD, HEADERS, BODY, CONTENT_TYPE)

    /**
     * 每预设字段的全集（六个）。**这些字段名同时就是老版本的扁平键名** ——
     * [needsFlatMigration] 的判据成立就靠这一点。
     */
    val PER_PRESET_FIELDS: List<String> = PER_PRESET_STRING_FIELDS + TIMEOUT_MS

    /** 顶层键的全集，只用于自校验"前缀不会撞上顶层键"（见单测）。 */
    val TOP_LEVEL_KEYS: Set<String> = setOf(
        PRESET, ENABLED, SCENES, RESPECT_DND, MIN_LEVEL, DAILY_LIMIT, QUOTA_DAY, QUOTA_COUNT
    )

    /**
     * 某个预设那一份里某个字段的真键名，例如 `bark.url` / `pushplus.body_template`。
     *
     * 用小写预设名而不是 `ordinal`：prefs 是要被人打开看的（`adb shell run-as … cat`），
     * 而序号一旦有人调整枚举顺序就会把 Bark 的 key 读成 ntfy 的 topic。
     */
    fun scoped(preset: WebhookPreset, field: String): String = "${preset.name.lowercase()}.$field"

    /**
     * 要不要执行**一次性**的扁平键搬迁。
     *
     * 判据只能是"旧键在、新键不在"：prefs 里没有版本号可依赖（这份 prefs 从来没存过 schema 版本），
     * 而这两个条件合起来恰好只在"老版本装过、还没搬过"时同时成立。
     * 搬完扁平键就被删掉，第二次进来 [hasFlatUrl] 为 false —— 幂等由此得到，不需要额外的标记位。
     *
     * 只看 `url` 一个键：老版本的 `save()` 是六个键一起写的，`url` 在就是那六个都在，
     * 而它也是唯一"没配过就一定不存在"的键（其余几个都有默认值、看不出是不是用户填的）。
     */
    fun needsFlatMigration(hasFlatUrl: Boolean, hasScopedUrl: Boolean): Boolean =
        hasFlatUrl && !hasScopedUrl
}

/**
 * [WebhookConfig] 的持久化 **+ 每日配额计数器**（独立 prefs，仿 `SmsForwardController` 的 `sms_forward`）。
 *
 * ## 存储是两层的
 *
 * 顶层跨预设共享、per-preset 字段每个预设各存一份，键分层与理由见 [WebhookPrefsKeys]。
 * 直接后果：**切换预设 = 只改顶层 `preset`**，别的预设那几份原样躺着 ——
 * 切回来读到的还是上次填的那份，从没配过就读该预设的默认值。
 * app / web 那个「切换会覆盖当前配置」的确认弹窗因此失去了存在理由（core 这侧不再有覆盖行为）。
 *
 * headers 是唯一需要序列化的复合字段：`SharedPreferences` 没有 Map 类型，
 * 存成 JSON 而不是"两个平行的 StringSet"（那种存法一旦条数不一致就会静默错位）。
 *
 * ## 配额为什么也放这里
 *
 * 与 [LocalSmsConfigStore] 逐条对齐：配额是"这个渠道今天还能不能发"，与渠道配置同生命周期、
 * 同一个文件读写。**这一对键（`quota_day` / `quota_count`）是本渠道独占的** ——
 * 三条渠道各一份、物理隔离，谁也吃不掉谁的额度（理由见 `ChannelRules`）。
 */
class WebhookConfigStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 配额读写的互斥。只护 `quota_day` + `quota_count` 这一对（同 [LocalSmsConfigStore]）。 */
    private val quotaLock = Any()

    /**
     * 配置读写的互斥。**刻意与 [quotaLock] 分开**：两者护的是不相干的键组，
     * 共用一把会让 `GET /config` 去等一次配额写盘完成（投递路径每发一条就写一次）。
     *
     * 需要它是因为 [load] 里带着一次性搬迁（读-改-删三步），而 [load] 会被 REST 与投递路径并发调。
     */
    private val configLock = Any()

    /**
     * 「当前预设那一份」的快照，**按写入失效**（不是按时间过期）。
     *
     * ## 为什么需要它
     *
     * 一次投递判定要读这份配置 **4~5 遍**：`WebhookChannel.rules` 的三个取值口各一次、
     * `isConfigured()` 一次、`accepts()` 一次。而 [load] 的每一遍都要
     * 抢 [configLock] + 跑一次迁移探测（两次 `prefs.contains`）+ 解一次 headers JSON。
     * 同一次判定里这些活干 5 遍，其中 4 遍的结果与第 1 遍逐字相同。
     *
     * ## 为什么它不是"假开关"
     *
     * 失效条件是**这份 prefs 被写过**，不是超时。两道一起上，因为这份 prefs 有两类写入者：
     * - [save] / [saveShared] / 真的执行了搬迁的 [migrateFlatKeysLocked] —— 本类自己写，
     *   **同步**作废，PUT 一落盘下一次 [load] 就是重新读的；
     * - **备份恢复**（`BackupAssembler.apply` 直接 `getSharedPreferences("notify_webhook").edit()`，
     *   而且 prefs 段恢复**不要求重启服务**）—— 它绕过本类，所以还挂了
     *   [invalidateOnWrite] 那个监听器兜住"任何外部写入"。少了它，用户恢复一份备份之后
     *   Webhook 会继续用旧 URL 投递，直到下一次 PUT —— 那正是本仓不许出现的假开关。
     *
     * 用 `@Volatile` 的裸引用而不是带过期时间的缓存：只需要"写了之后别人立刻看得见"，
     * 而 [WebhookConfig] 是不可变数据类，读到旧引用与读到新引用之间没有中间态。
     */
    @Volatile
    private var snapshot: WebhookConfig? = null

    /**
     * 任何键被写入就把 [snapshot] 作废。
     *
     * 必须**持一个强引用**：`SharedPreferences` 只弱引用监听器，写成匿名表达式直接注册的话
     * 它会在某次 GC 之后静默失效 —— 而失效的表现是"恢复备份后配置不生效"，没有任何报错。
     */
    private val invalidateOnWrite =
        SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> snapshot = null }

    init {
        prefs.registerOnSharedPreferenceChangeListener(invalidateOnWrite)
    }


    /** 读当前预设那一份（顶层 + `<当前 preset>.*`）。命中 [snapshot] 时连锁都不用抢。 */
    fun load(): WebhookConfig = snapshot ?: synchronized(configLock) {
        snapshot ?: loadLocked(null).also { snapshot = it }
    }


    /**
     * 读**指定**预设那一份（顶层字段照常是共享的那份）。
     *
     * 唯一调用点是 `PUT /api/notify/webhook/config` 里"patch 要求切预设"的分支：
     * 字段级合并需要一个"底"，而切换时那个底必须是**目标预设**那一份 ——
     * 拿当前预设的底去 copy，旧预设的 url / 模板就会被写进新预设的槽位，
     * 那正是这次要根治的"切一次覆盖一次"。
     */
    fun load(preset: WebhookPreset): WebhookConfig = synchronized(configLock) { loadLocked(preset) }

    fun save(config: WebhookConfig) = synchronized(configLock) {
        prefs.edit().apply {
            putShared(config)
            putPresetFields(config)
        }.apply()
        // 快照按写入失效（理由见 snapshot）：PUT 落盘之后，下一次 load() 必须是重新读的。
        snapshot = null
    }


    /**
     * 只写顶层那几个共享字段（含"当前选中的预设"），**per-preset 那一份一个键都不碰**。
     *
     * 这就是"切换预设"的全部动作。用它而不是 [save] 是为了保住"从未配过"这个状态：
     * 走 [save] 的话切一次预设就会把目标预设的默认值当成"用户配的"落盘，
     * 于是将来改了那个预设的默认模板，只是点过一下的用户永远收不到新默认值
     * （而界面上看不出任何原因）。
     */
    fun saveShared(config: WebhookConfig) = synchronized(configLock) {
        prefs.edit().apply { putShared(config) }.apply()
        // 顶层字段（含"当前选中的预设"）也在快照里，切换预设之后必须重新读。
        snapshot = null
    }


    /** 顶层：跨预设共享的那一组（"这条渠道要不要发、什么时候发"）。 */
    private fun SharedPreferences.Editor.putShared(config: WebhookConfig) {
        putString(WebhookPrefsKeys.PRESET, config.preset.name)
        putBoolean(WebhookPrefsKeys.ENABLED, config.enabled)
        putString(WebhookPrefsKeys.MIN_LEVEL, config.minLevel.wireName)
        putInt(WebhookPrefsKeys.DAILY_LIMIT, config.dailyLimit)
        putStringSet(WebhookPrefsKeys.SCENES, config.scenes)
        putBoolean(WebhookPrefsKeys.RESPECT_DND, config.respectDnd)
    }

    /** 每预设一份：写到 `config.preset` 的前缀下，别的预设那几份不碰（"发到哪、怎么发"）。 */
    private fun SharedPreferences.Editor.putPresetFields(config: WebhookConfig) {
        val p = config.preset
        putString(scoped(p, WebhookPrefsKeys.URL), config.url.trim())
        putString(scoped(p, WebhookPrefsKeys.METHOD), config.method.uppercase())
        putString(scoped(p, WebhookPrefsKeys.HEADERS), encodeHeaders(config.headers))
        putString(scoped(p, WebhookPrefsKeys.BODY), config.bodyTemplate)
        putString(scoped(p, WebhookPrefsKeys.CONTENT_TYPE), config.contentType)
        putLong(scoped(p, WebhookPrefsKeys.TIMEOUT_MS), config.timeoutMs)
    }

    /**
     * @param override 非 null = 读这个预设那一份（[load] 的重载用）；null = 读顶层记着的当前预设。
     */
    private fun loadLocked(override: WebhookPreset?): WebhookConfig {
        migrateFlatKeysLocked()
        val preset = override ?: WebhookPreset.fromName(prefs.getString(WebhookPrefsKeys.PRESET, null))
        return WebhookConfig(
            enabled = prefs.getBoolean(WebhookPrefsKeys.ENABLED, false),
            preset = preset,
            // 这一份的某个键**没存过 → 用该预设的默认值，并且不写盘**：
            // "从未配过"和"配成默认值"必须能区分，否则将来改了预设默认值就会把用户存的旧值悄悄冒名顶替
            //（表现是"我明明填过，升级后变成别的了"，而日志里什么都没有）。
            url = scopedString(preset, WebhookPrefsKeys.URL, preset.defaultUrl),
            method = scopedString(preset, WebhookPrefsKeys.METHOD, preset.defaultMethod),
            headers = prefs.getString(scoped(preset, WebhookPrefsKeys.HEADERS), null)
                ?.let(::decodeHeaders) ?: preset.defaultHeaders,
            bodyTemplate = scopedString(preset, WebhookPrefsKeys.BODY, preset.defaultBody),
            contentType = scopedString(
                preset, WebhookPrefsKeys.CONTENT_TYPE, preset.defaultContentType
            ),
            timeoutMs = prefs.getLong(
                scoped(preset, WebhookPrefsKeys.TIMEOUT_MS), WebhookConfig.DEFAULT_TIMEOUT_MS
            ),
            // 认不出的级别名回落 INFO（与本渠道默认值一致）：一份读坏的配置不该让通知静默消失。
            // 本机短信那侧刻意回落 CRITICAL —— 那条渠道读坏配置时应该退化成"最省钱"的一档。
            minLevel = levelOf(prefs.getString(WebhookPrefsKeys.MIN_LEVEL, null)),
            dailyLimit = prefs.getInt(WebhookPrefsKeys.DAILY_LIMIT, ChannelRules.UNLIMITED),
            scenes = prefs.getStringSet(WebhookPrefsKeys.SCENES, emptySet()) ?: emptySet(),
            respectDnd = prefs.getBoolean(WebhookPrefsKeys.RESPECT_DND, true)
        )
    }

    /**
     * 老版本的扁平配置（`url` / `method` / `headers_json` / `body_template` / `content_type` /
     * `timeout_ms` 直接摆在根上）搬到**当前预设**的前缀下。**一次性数据迁移，不是兼容 shim。**
     *
     * 为什么搬到"当前预设"而不是 `custom`：那六个值就是用户当时正在用的那一份配置，
     * 而顶层 `preset` 记着他当时选的是哪个预设 —— 塞进 `custom` 会让一个用 Bark 的用户
     * 升级后打开设置页看到"Bark 未配置"，而他的 device key 躺在自定义那一栏里。
     *
     * 判据与幂等见 [WebhookPrefsKeys.needsFlatMigration]。**搬完扁平键即删** ——
     * 等到不再需要支持"从本次改造之前的版本直接升级"（例如下一个大版本、或确认存量全部已升级）时，
     * 连这个函数一起删掉即可，删掉之后不会留下任何需要照顾的旧键。
     */
    private fun migrateFlatKeysLocked() {
        val preset = WebhookPreset.fromName(prefs.getString(WebhookPrefsKeys.PRESET, null))
        val needed = WebhookPrefsKeys.needsFlatMigration(
            hasFlatUrl = prefs.contains(WebhookPrefsKeys.URL),
            hasScopedUrl = prefs.contains(scoped(preset, WebhookPrefsKeys.URL))
        )
        if (!needed) return

        val editor = prefs.edit()
        for (field in WebhookPrefsKeys.PER_PRESET_STRING_FIELDS) {
            // 老版本没写过的键就别在新键那边凭空造一个（"没存过"要保持没存过，见 loadLocked）
            prefs.getString(field, null)?.let { editor.putString(scoped(preset, field), it) }
            editor.remove(field)
        }
        if (prefs.contains(WebhookPrefsKeys.TIMEOUT_MS)) {
            editor.putLong(
                scoped(preset, WebhookPrefsKeys.TIMEOUT_MS),
                prefs.getLong(WebhookPrefsKeys.TIMEOUT_MS, WebhookConfig.DEFAULT_TIMEOUT_MS)
            )
        }
        editor.remove(WebhookPrefsKeys.TIMEOUT_MS)
        editor.apply()
        // 搬迁改了 prefs，快照必须跟着失效（这次调用是从 loadLocked 里进来的，
        // 它拿到的仍是搬完之后的值，所以只影响"下一次读"）。
        snapshot = null
        AppLogger.i(TAG, "notify_webhook 的扁平配置已搬到预设 ${preset.name} 名下（一次性搬迁）")

    }

    private fun scoped(preset: WebhookPreset, field: String): String =
        WebhookPrefsKeys.scoped(preset, field)

    private fun scopedString(preset: WebhookPreset, field: String, default: String): String =
        prefs.getString(scoped(preset, field), default) ?: default

    // ══════════ 每日配额（口径与 LocalSmsConfigStore 逐条一致） ══════════

    /**
     * 今天已发出几条。**按本地日期判跨天** —— 存 `yyyy-MM-dd` 字符串而不是时间戳，
     * 于是"今天"与用户日历上的今天永远一致（时间戳 + 86400s 的算法会跟着时区/夏令时漂）。
     */
    fun sentToday(today: String = currentDay()): Int = synchronized(quotaLock) {
        if (prefs.getString(WebhookPrefsKeys.QUOTA_DAY, null) != today) {
            0
        } else {
            prefs.getInt(WebhookPrefsKeys.QUOTA_COUNT, 0)
        }
    }

    /**
     * 计一条，返回计入后的今日已用数。
     *
     * 调用点只有一处：[WebhookChannel.deliver] 拿到 [DeliveryOutcome.Sent] 之后。
     * 只在 `Sent` 时计是因为 HTTP 2xx 是**确定结论**（目标收下了），
     * 不像本机短信那样存在"可能已经发出去了"的中间态 —— 那侧的口径因此是
     * "排除不了已经发出去"（`LocalSmsDelivery.countsTowardQuota`）。
     */
    fun consume(today: String = currentDay()): Int = synchronized(quotaLock) {
        val sameDay = prefs.getString(WebhookPrefsKeys.QUOTA_DAY, null) == today
        val next = (if (sameDay) prefs.getInt(WebhookPrefsKeys.QUOTA_COUNT, 0) else 0) + 1
        prefs.edit()
            .putString(WebhookPrefsKeys.QUOTA_DAY, today)
            .putInt(WebhookPrefsKeys.QUOTA_COUNT, next)
            .apply()
        next
    }

    private fun encodeHeaders(headers: Map<String, String>): String =
        HeaderJson.encodeToString(HeaderSerializer, headers)

    /** 解析失败回落空 map：一份坏掉的 headers 不该让整个渠道配置读不出来。 */
    private fun decodeHeaders(raw: String?): Map<String, String> {
        if (raw.isNullOrBlank()) return emptyMap()
        return try {
            HeaderJson.decodeFromString(HeaderSerializer, raw)
        } catch (e: Exception) {
            AppLogger.w(TAG, "Webhook headers 解析失败（按空处理）：${e.javaClass.simpleName}")
            emptyMap()
        }
    }

    private companion object {
        const val TAG = "WebhookConfigStore"

        /** 独立 prefs 文件名（与邮件的 `sms_forward` 平级）。键名与分层在 [WebhookPrefsKeys]。 */
        const val PREFS_NAME = "notify_webhook"

        fun currentDay(): String = LocalDate.now().toString()

        fun levelOf(name: String?): NotifyLevel =
            NotifyLevel.entries.firstOrNull { it.wireName == name?.lowercase() } ?: NotifyLevel.INFO

        val HeaderJson = Json { ignoreUnknownKeys = true }
        val HeaderSerializer = MapSerializer(String.serializer(), String.serializer())
    }
}
