package com.ufi_axis.data.model

import com.ufi_axis.data.api.FileItem
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonTransformingSerializer
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// =========================================================================
// T9 — Gson → kotlinx.serialization 统一迁移：模式 B 结构化响应模型
//
// 全部使用 `AppJson`（isLenient=true / ignoreUnknownKeys=true / coerceInputValues=true）
// 配置进行序列化（isLenient / ignoreUnknownKeys / coerceInputValues），可无缝替换原 Gson 解析。
// 这些模型由 `AppJsonConverterFactory` 的 kotlinx 分支直接反序列化，
// 不再经过 Gson 反射。
// =========================================================================

// ========== Dashboard ==========

/**
 * 仪表盘聚合响应（#15 api/dashboard/summary）。
 * 内嵌复用已存在的 `@Serializable` 模型，全部可空以容忍后端缺字段（lenient 解析）。
 */
@Serializable
data class DashboardSummaryResponse(
    val device_info: DeviceInfoResponse? = null,
    val battery: BatteryInfo? = null,
    val storage: StorageInfo? = null,
    val uptime: UptimeInfo? = null,
    val traffic_summary: TrafficSummary? = null,
    val traffic_limit: TrafficLimitConfig? = null,
    val network_status: NetworkStatusResponse? = null
)

// ========== SMS Forward ==========

/** #17 debug-logs 响应。 */
@Serializable
data class DebugLogsResponse(
    val logs: List<String> = emptyList()
)

/** core 落盘日志文件条目（`GET /api/debug-logs/files`）。 */
@Serializable
data class DebugLogFileItem(
    val name: String = "",
    val size: Long = 0,
    val modified: Long = 0
)

/**
 * `GET /api/debug-logs/files` 响应。
 *
 * 日志文件在 core 的私有目录（0700）下，未 root 的设备用文件管理器读不到，
 * 只能经这个接口列目录、再用 `/files/{name}` 读尾部。
 */
@Serializable
data class DebugLogFilesResponse(
    val files: List<DebugLogFileItem> = emptyList(),
    val total_bytes: Long = 0,
    /** core 侧日志目录绝对路径，UI 上直接显示，用户可拿文件管理器去同一路径查看。 */
    val dir: String = ""
)

/** #11 sms-forward/test 响应。 */
@Serializable
data class SmsForwardTestResponse(
    val success: Boolean = false,
    val error: String? = null,
    /**
     * 全局通知总闸 + 免打扰此刻放不放行**自动**通知。
     *
     * 与 [success] 是两件事：测试信走 manual 口径不受闸门约束，所以完全可能
     * `success = true` 而这里是 false —— 那说明"SMTP 通了，但自动通知是关的"。
     * 缺省 false 只是解析兜底（老 core 不回这个键），不代表闸门真的关着。
     */
    val auto_notify_enabled: Boolean = false
)

/**
 * sms-forward/diagnose 响应。
 *
 * 全是**派生屏蔽位**（`*_set`）而不是原值 —— core 不回传凭据。
 * [sendable] 是 core 内部真正的发信闸门：`enabled` 且服务器/用户名/密码/收件地址四项齐全；
 * 它为 false 时无论怎么点「测试发送」都不会发出，所以比逐项 `*_set` 更值得优先展示。
 */
@Serializable
data class SmsForwardDiagnose(
    val config_enabled: Boolean = false,
    val smtp_host_set: Boolean = false,
    val smtp_port: Int = 0,
    val smtp_user_set: Boolean = false,
    val smtp_pass_set: Boolean = false,
    val smtp_to_set: Boolean = false,
    val sendable: Boolean = false,
    val scene_count: Int = 0,
    /**
     * 发信计数（core 侧累计，跨重启保留）。
     *
     * 只计**真正发起过 SMTP 投递**的次数：黑名单拦截、场景未勾选、配置不全都不计
     * —— 那些不是"发送失败"，混进来会让失败数虚高。
     * `sent_total` 由 core 用 success+failed 派生，app 直接用，不要自己再加一遍。
     */
    val sent_total: Int = 0,
    val sent_success: Int = 0,
    val sent_failed: Int = 0,
    /** 最近一次**成功**发出的时间戳（毫秒）；0 = 从未成功过。 */
    val last_sent_at: Long = 0L,
    /** 最近一次失败原因（`异常类名: message`，已截断到 200 字）；空串 = 无失败或已被成功覆盖。 */
    val last_error: String = ""
)

// ========== 三条投递渠道共用：级别取值域 ==========

/**
 * `levels[]` 的单个元素：级别的**线上口径小写名** + 给用户看的**中文名**。
 *
 * 真源是 core 的 `NotifyLevel`（`wireName` / `label`），由三条渠道的 `/config` 一起下发
 * （`GET /api/notify/webhook/config`、`/api/notify/sms/config`、`/api/sms-forward/config`）。
 * app **不维护第二张 `"warning" -> "警告"` 的映射表** —— 那张表加一档新级别时不会报错，
 * 只会在界面上显示成空白或原样的英文名。口径与同一份响应里的 `placeholders`
 * （`[{"name":..,"desc":..}]`）一致。
 *
 * [label] 为空时调用方回落显示 [name]：老 core 只回字符串数组（见
 * [NotifyLevelOptionsSerializer]），那种情况下拿不到中文名，显示 wire 名也比空白有用。
 */
@Serializable
data class NotifyLevelOptionDto(
    val name: String = "",
    val label: String = ""
)

/**
 * `levels` 的**外部响应版本容错**：把老 core 的 `["info","warning","critical"]`
 * 提升成新形状 `[{"name":"info"}, …]` 再交给正常的反序列化。
 *
 * 为什么必须有：2026-09-11 core 把这三条 `/config` 的 `levels` 从字符串数组改成了对象数组。
 * app 直接声明 `List<NotifyLevelOptionDto>` 时，**旧固件**回的字符串数组会让
 * kotlinx.serialization 抛 `JsonDecodingException`（`ignoreUnknownKeys` 只管多出来的键、
 * 管不了类型不匹配），表现是三个渠道页整页读不出配置 —— 而 app 与 core 是分别升级的，
 * 用户手上完全可能是旧固件配新 app。
 *
 * **删除条件**：仓内 core 已全量下发对象数组，等到不再需要兼容 2026-09-11 之前的固件
 * （即最低支持的 core 版本 ≥ 该改动）时，删掉本类并把三处 `@Serializable(with = …)` 一并去掉。
 */
object NotifyLevelOptionsSerializer : JsonTransformingSerializer<List<NotifyLevelOptionDto>>(
    ListSerializer(NotifyLevelOptionDto.serializer())
) {
    override fun transformDeserialize(element: JsonElement): JsonElement {
        val array = element as? JsonArray ?: return element
        if (array.none { it is JsonPrimitive }) return element
        return JsonArray(
            array.map { item ->
                // 只有裸字符串那一档需要提升；对象元素原样透传（新老混排也不会炸）。
                if (item is JsonPrimitive) buildJsonObject { put("name", item.content) } else item
            }
        )
    }
}

// ========== Webhook 通知渠道（/api/notify/webhook） ==========
//
// 字段名与 core 的 JSON 逐字一致（真源 `WebhookRoutes.configPayload`）。
// 全部带默认值：老 core 没有这条路由时解析不会炸，缺字段也退化成"未启用/空"。
//
// 为什么 GET 的响应里带 presets / placeholders / configured：
// 那三样是 core 的数据（预设默认模板、占位符清单、"能不能投"的判据），
// app 只渲染。手抄一份进 app 就必然与 core 分叉 —— 表现是"照着界面填完却发不出去"。

/**
 * 单个内置预设的默认值（`presets[]` 的元素）。
 *
 * [url] 里带 `<占位>` 的部分不是可用地址，[user_fills] 就是告诉用户"这段要自己换掉"。
 *
 * [secret_label] / [secret_marker] / [secret_target] 是同一件事的**机器可读**声明：
 * 七个预设里用户真正要填的只有一样，但位置不同（多数在 URL 里，PushPlus 在请求体模板里）。
 * 客户端据此只渲染一个输入框、其余字段收进「高级设置」。这三样**不许 app 手抄** ——
 * marker 抄错的表现是密钥没被替换进请求，界面上看不出原因，只会一条通知都收不到。
 *
 * @param secret_label 那个唯一输入框的标签（如「PushPlus Token」「Device Key」）；无需填写时为空串。
 * @param secret_marker 默认值里代表它的标记，如 `<token>`；无需填写时为空串。
 * @param secret_target 标记在哪一处：见 [WebhookSecretTarget]。
 */
@Serializable
data class WebhookPresetDto(
    val name: String = "CUSTOM",
    val display_name: String = "",
    val user_fills: String = "",
    val secret_label: String = "",
    val secret_marker: String = "",
    val secret_target: String = WebhookSecretTarget.NONE,
    val url: String = "",
    val method: String = "POST",
    val headers: Map<String, String> = emptyMap(),
    val content_type: String = "",
    val body_template: String = ""
)

/**
 * [WebhookPresetDto.secret_target] 的三个线上口径值（真源是 core 的 `SecretTarget.wireName`）。
 *
 * 不做成 enum：认不出的值必须能安全落地成"没有简易输入框"（= 全手填），
 * 而 `@Serializable` 的 enum 遇到未知名字会抛 —— 那会让整个配置页读不出来。
 */
object WebhookSecretTarget {
    /** 无需单独填（`CUSTOM`：URL 与模板全手填）。 */
    const val NONE = "none"

    /** 标记在预设的 `url` 里。 */
    const val URL = "url"

    /** 标记在预设的 `body_template` 里（PushPlus）。 */
    const val BODY = "body"
}

/**
 * 单个可用占位符（`placeholders[]` 的元素）。
 *
 * [desc] 由 core 给（真源 `WebhookDelivery.PLACEHOLDERS`）：光有名字用户分不出
 * `message` 与 `meta`、`level` 与 `level_label`。**app 侧不维护第二张说明表** ——
 * 抄一份的表现是说明与实际取值对不上，而界面上看不出是谁错了。
 */
@Serializable
data class WebhookPlaceholderDto(
    val name: String = "",
    val desc: String = ""
)

/**
 * `GET /api/notify/webhook/config` 响应（也是 PUT 回显里的 `config`）。
 *
 * [configured] 读的就是 core 渠道那一份 `isConfigured()`（开关 + scheme + 预设占位是否填完），
 * **不要**在 app 侧再算一遍"能不能投" —— 两套判定必然在某个边界上给出相反答案。
 *
 * 2026-09-10「规则同构」：补上 [min_level] / [daily_limit] 两个旋钮与只读伴生位，
 * 与 [LocalSmsConfigResponse] 和 `SmsForwardConfig` 形状一致（界面共用同一批行）。
 * Webhook 的区间是 0..1000、默认不限 —— 值域读 [daily_limit_min] / [daily_limit_max]，
 * 别在 app 里抄第二份。
 */
@Serializable
data class WebhookConfigResponse(
    val enabled: Boolean = false,
    /** 预设枚举名（`CUSTOM` / `BARK` / …）。只用于回显"当初选的是哪个"，不参与投递。 */
    val preset: String = "CUSTOM",
    val url: String = "",
    val method: String = "POST",
    val headers: Map<String, String> = emptyMap(),
    val body_template: String = "",
    val content_type: String = "",
    val timeout_ms: Long = 0L,
    val scenes: List<String> = emptyList(),
    val respect_dnd: Boolean = true,
    /** 线上口径小写名：`info` / `warning` / `critical`。默认 `info`（与 core 一致）。 */
    val min_level: String = "info",
    /** 每日请求数上限，**0 = 不限**（Webhook 走数据网、不计费，"不限"是合法诉求）。 */
    val daily_limit: Int = 0,
    /** 今天已发出几条（只读，按设备本地日期跨天重置）。 */
    val sent_today: Int = 0,
    /**
     * 今天还剩几条（只读）。**[daily_limit] = 0 时 core 回 null**，界面渲染成「不限」。
     *
     * 必须是 `Int?`：`AppJson` 开了 `coerceInputValues`，非空 Int 遇到 null 会被悄悄
     * 当成 0 —— 那就把"不限"显示成"配额已用尽"，方向正好反了。
     */
    val quota_remaining: Int? = null,
    val configured: Boolean = false,
    /**
     * 可用占位符清单：**对象数组**（`[{"name":"title","desc":"通知标题"}, …]`），顺序即展示顺序。
     *
     * 类型必须与 core 逐字对齐：`List<String>` 遇到对象会抛 `JsonDecodingException`
     * （`ignoreUnknownKeys` 只管多出来的键，管不了类型不匹配），表现是整个配置页读不出来。
     */
    val placeholders: List<WebhookPlaceholderDto> = emptyList(),
    val presets: List<WebhookPresetDto> = emptyList(),
    /** 级别取值域：**对象数组**（见 [NotifyLevelOptionDto]），中文名由 core 下发。 */
    @Serializable(with = NotifyLevelOptionsSerializer::class)
    val levels: List<NotifyLevelOptionDto> = emptyList(),
    val daily_limit_min: Int = 0,
    val daily_limit_max: Int = 1000
)

/** `PUT /api/notify/webhook/config` 响应：立即回读，`config` 是存进去之后的真值。 */
@Serializable
data class WebhookConfigSaveResponse(
    val success: Boolean = false,
    val config: WebhookConfigResponse? = null
)

/**
 * `PUT /api/notify/webhook/config` 请求体：**字段级 patch**。
 *
 * 全部可空且默认 null。`AppJson.encodeDefaults = true` 会把 null 写成 `"field": null`，
 * 而 core 的 `merge` 用 `takeIf { it !is JsonNull }` 取值 —— JsonNull 等于"没传"，保留服务端现值。
 * 于是"只切一个开关"不会把 URL / 模板顺手清空（口径同 [SmsRuleRequest]）。
 *
 * [headers] 是唯一的例外：core 收到就**整体替换**。这是刻意的 —— 按键合并的话用户永远
 * 删不掉一个头部，而"改完发现旧的 Authorization 还在"是安全问题。所以传 headers 时必须传全量。
 */
@Serializable
data class WebhookConfigPatch(
    val enabled: Boolean? = null,
    val preset: String? = null,
    val url: String? = null,
    val method: String? = null,
    val headers: Map<String, String>? = null,
    val body_template: String? = null,
    val content_type: String? = null,
    val timeout_ms: Long? = null,
    val scenes: List<String>? = null,
    val respect_dnd: Boolean? = null,
    /** 小写 wire name（`info` / `warning` / `critical`）；认不出时 core 回 400。 */
    val min_level: String? = null,
    /** 0..1000，**0 = 不限**；越界时 core 回 400。 */
    val daily_limit: Int? = null
)

/**
 * `POST /api/notify/webhook/test` 响应。
 *
 * [status_code] 与 [response_body] 是排 Webhook 时**唯一**有用的信息
 * （`{"code":40001,"msg":"invalid token"}` 这种）：投递结果里没有它们，
 * core 专门从渠道的 `lastAttempt` 里捞出来回给 UI。成功和失败都要显示。
 *
 * [auto_notify_enabled] 与 [success] 是两件事：测试走 manual 口径不受闸门约束，
 * 所以完全可能"发出去了，但自动通知是关的"。
 */
@Serializable
data class WebhookTestResponse(
    val success: Boolean = false,
    val error: String? = null,
    val auto_notify_enabled: Boolean = false,
    /** null = 请求没走完（超时 / 连不上 / DNS / TLS），此时看 [error]。 */
    val status_code: Int? = null,
    val response_body: String? = null,
    val attempted_at: Long? = null
)

// ========== 本机短信回发渠道（/api/notify/sms） ==========
//
// 字段名与 core 的 JSON 逐字一致（真源 `LocalSmsRoutes.configPayload`）。
// 全部带默认值：老 core 没有这条路由时解析不会炸，缺字段也退化成"未启用/空"。
//
// **这条渠道会产生短信费用**，所以 DTO 里带了三个只读位（configured / sent_today /
// quota_remaining）—— UI 上那行"今日 2/5"与"配置未填完"读的都是 core 的判据，
// app 侧不自己算第二套。

/**
 * `GET /api/notify/sms/config` 响应（也是 PUT 回显里的 `config`）。
 *
 * [configured] 读的就是 core 渠道那一份 `isConfigured()`（开关 + 号码合法）。
 * [levels] / [daily_limit_min] / [daily_limit_max] 是 core 给的可选值域，
 * app 拿来渲染下拉与校验输入 —— 手抄一份就会出现"界面允许、设备拒收"。
 */
@Serializable
data class LocalSmsConfigResponse(
    val enabled: Boolean = false,
    val target_number: String = "",
    /** 线上口径小写名：`info` / `warning` / `critical`。默认 `critical`（最省钱的一档）。 */
    val min_level: String = "critical",
    val daily_limit: Int = 5,
    val scenes: List<String> = emptyList(),
    val respect_dnd: Boolean = true,
    val configured: Boolean = false,
    /** 今天已发出几条（按设备本地日期跨天重置）。 */
    val sent_today: Int = 0,
    /**
     * 今天还剩几条。
     *
     * 类型与另两条渠道逐字一致（`Int?`）：契约上 `daily_limit = 0` 时 core 回 null，
     * 而本渠道 [daily_limit_min] = 1 —— **这一条渠道拿不到 null**（"不限"对花钱的渠道
     * 不是合法诉求）。仍然用可空类型是为了让三页共用同一批渲染函数，
     * 而不是在这里造一个"只有本渠道是非空"的例外。
     */
    val quota_remaining: Int? = null,
    /** 级别取值域：**对象数组**（见 [NotifyLevelOptionDto]），中文名由 core 下发。 */
    @Serializable(with = NotifyLevelOptionsSerializer::class)
    val levels: List<NotifyLevelOptionDto> = emptyList(),
    val daily_limit_min: Int = 1,
    val daily_limit_max: Int = 50
)

/** `PUT /api/notify/sms/config` 响应：立即回读，`config` 是存进去之后的真值。 */
@Serializable
data class LocalSmsConfigSaveResponse(
    val success: Boolean = false,
    val config: LocalSmsConfigResponse? = null
)

/**
 * `PUT /api/notify/sms/config` 请求体：**字段级 patch**。
 *
 * 全部可空且默认 null；`AppJson.encodeDefaults = true` 会把 null 写成 `"field": null`，
 * 而 core 的 `merge` 把 JsonNull 当成"没传"→ 保留服务端现值（口径同 [WebhookConfigPatch]）。
 */
@Serializable
data class LocalSmsConfigPatch(
    val enabled: Boolean? = null,
    val target_number: String? = null,
    val min_level: String? = null,
    val daily_limit: Int? = null,
    val scenes: List<String>? = null,
    val respect_dnd: Boolean? = null
)

/**
 * `POST /api/notify/sms/test` 响应。
 *
 * 与另两条渠道的测试端点最大的不同：**这一次真的发出了一条短信、真的花了钱**。
 * 所以 [counted_toward_quota] 与 [quota_remaining] 必须显示出来 ——
 * 不显示的话用户会反复点，而每一次都是一条话费。
 *
 * [verdict] 是固件结论（`SENT` / `FAILED` / `PENDING` / `REJECTED` / `EXCEPTION`）：
 * `PENDING` 表示"设备受理了但没在几秒内给最终状态"，那既不是成功也不该重试。
 */
@Serializable
data class LocalSmsTestResponse(
    val success: Boolean = false,
    val error: String? = null,
    val auto_notify_enabled: Boolean = false,
    val verdict: String? = null,
    val detail: String? = null,
    val counted_toward_quota: Boolean = false,
    val sent_today: Int = 0,
    val quota_remaining: Int = 0,
    val daily_limit: Int = 0,
    val attempted_at: Long? = null
)

// ========== File Manager ==========

/**
 * #19 files/search 响应。复用 `FileItem`（定义在 data/api/UfiAxisApi.kt）。
 */
@Serializable
data class SearchFilesResponse(
    val files: List<FileItem> = emptyList(),
    val path: String? = null,
    val error: String? = null
)

/**
 * 磁盘卷原始结构（#20 files/disk-usage 的内嵌元素）。
 * 后端 key：label / mount / size / used / available / usePercent（均为字符串）。
 */
@Serializable
data class StorageVolumeRaw(
    val label: String = "",
    val mount: String = "",
    val size: String = "",
    val used: String = "",
    val available: String = "",
    val usePercent: String = ""
)

/** #20 files/disk-usage 响应。 */
@Serializable
data class DiskUsageResponse(
    val disks: List<StorageVolumeRaw> = emptyList()
)

/** #21 files/status 响应：Core 后端的存储管理权限状态。 */
@Serializable
data class StorageStatusResponse(
    val isExternalStorageManager: Boolean = false
)

