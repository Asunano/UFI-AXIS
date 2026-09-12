package com.ufi_axis_core.api.routes

import com.ufi_axis_core.api.ResponseHelper.toJsonElement
import com.ufi_axis_core.contract.ErrorCode
import com.ufi_axis_core.controller.notify.WebhookChannel
import com.ufi_axis_core.controller.notify.WebhookConfig
import com.ufi_axis_core.controller.notify.WebhookConfigStore
import com.ufi_axis_core.controller.notify.WebhookPreset
import com.ufi_axis_core.notify.ChannelRules
import com.ufi_axis_core.notify.DeliveryOutcome
import com.ufi_axis_core.notify.Notifier
import com.ufi_axis_core.notify.NotifyEvent
import com.ufi_axis_core.notify.NotifyLevel
import com.ufi_axis_core.notify.NotifyScenes
import com.ufi_axis_core.notify.SkipReason
import io.ktor.http.*
import io.ktor.server.application.call
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.*

/**
 * 通用 Webhook 渠道的配置与测试端点（阶段 2）。
 *
 * ```
 * GET  /api/notify/webhook/config   读全量（含预设表与占位符清单，供 UI 直接渲染）
 * PUT  /api/notify/webhook/config   字段级合并（口径同 PUT /api/notifications/config）
 * POST /api/notify/webhook/test     发一条测试通知，响应带 HTTP 状态码与响应体摘要
 * ```
 *
 * ## 为什么 GET 里要回预设表
 *
 * 七个预设的默认模板如果让 app 与 web 各抄一份，就又多了两份**手抄镜像**
 * （本仓已经为 `DeviceFields` 的手抄付过代价，那份还得靠 `verify-api-contract.mjs` 兜住）。
 * 预设是 core 的数据，客户端只渲染。
 *
 * ## headers 为什么不脱敏
 *
 * `Authorization` 这类头部的值是凭据，但**它必须能被回显**：用户要在界面上改它，
 * 而"用星号回显、原值保留"那套（`smtp_pass_set`）只在单个固定字段上说得通 ——
 * headers 是任意键值对，回显成星号再提交就把真值覆盖掉了。而且 Bark / Server 酱
 * 的凭据本来就在 URL 里，只脱敏 headers 是自欺欺人。**日志与投递记录里仍然脱敏**
 * （`WebhookDelivery.maskHeaders` / `originOf`）—— 那两处是会被导出、被贴出来的。
 *
 * ## PUT 的预设语义（配置改成按预设各存一份之后）
 *
 * 存储是两层的：顶层跨预设共享，而 url / method / headers / body_template / content_type /
 * timeout_ms **每个预设各存一份**（键分层与理由见 `WebhookPrefsKeys`）。于是 PUT 有三种形状：
 *
 * - patch 里**只带** `preset` = **切换**：只改顶层那个"当前选中"，per-preset 字段一个都不写 ——
 *   目标预设那一份原样躺着，切回来读到的还是上次填的那份。
 * - patch 里**同时带** `preset` 与 per-preset 字段（客户端一次提交"切到 bark 并填好 device key"）：
 *   那些字段写到**新** preset 那一份下。所以合并的"底"取的是 `store.load(目标预设)` ——
 *   拿当前那份当底会把旧预设的 url / 模板 copy 进新预设的槽位，那正是改造前"切一次清一次"的老毛病。
 * - patch 里不带 `preset`：改的就是当前预设那一份，与从前一样。
 *
 * 认不出的 `preset` 名一律 **400**（不回落 CUSTOM，理由见 [presetOrThrow]）。
 *
 * GET 回"当前预设那一份 + 预设表"，`configured` / `sent_today` / `quota_remaining` 口径不变。
 *
 * **`levels` 的形状变了**（2026-09-11）：从 `["info","warning","critical"]` 变成
 * `[{"name":"info","label":"提示"}, …]` —— 中文名的真源在 `NotifyLevel.label` 上，
 * 客户端不该再抄一张映射表。三条渠道的 `/config` 同步改，理由见 [levelOptions]。
 */

class WebhookRoutes(
    private val store: WebhookConfigStore,
    /**
     * 只为读 [WebhookChannel.isConfigured]（"能不能投"的判据只有渠道那一份，不在这里重算）。
     *
     * **不再从渠道回读投递快照** —— 状态码与响应体现在随 `DeliveryReport` 一起回来
     * （见 `DeliveryAttempt`）。
     */
    private val channel: WebhookChannel,

    /** 通知分发器入口（装配层接到 `NotificationDispatcher::emit`）。 */
    private val notifier: Notifier,
    /**
     * 闸门**只读取值口**（装配层注入的那同一个 lambda，已按 webhook 渠道绑好 `respectDnd`）。
     *
     * 不拦任何投递（强制点只有 `NotificationDispatcher.emit`），唯一用途是 `/test` 响应里的
     * `auto_notify_enabled` —— 让用户知道"这条测试发出去了，自动通知却是关的"。
     */
    private val gate: () -> Boolean
) {
    fun register(route: Route) {
        route.route("/notify/webhook") {

            get("/config") {
                call.respond(toJsonElement(configPayload(store.load())))
            }

            /**
             * 字段级合并：body 是配置字段的任意子集，未出现的键保留服务端现值。
             *
             * `headers` 例外 —— 传了就**整体替换**。按键合并的话用户没法删掉一个头部
             * （少传即保留 = 永远删不掉），而"改完发现旧的 Authorization 还在"是安全问题。
             *
             * `preset` 例外 —— 它换的是"per-preset 字段写到哪一份"，见类注释里的三种形状。
             */
            put("/config") {
                val patch = call.receiveJsonObject()
                val merged = try {
                    val current = store.load()
                    // 换预设时，合并的底必须是**目标预设**那一份：顶层字段照样取现值（它们是共享的），
                    // 而 patch 没提到的 per-preset 字段应当保留**目标预设自己**上次存的值，
                    // 不是把当前预设的 url / 模板搬过去（那就是"切一次清一次"）。
                    val target = presetOf(patch)
                    val base = if (target != null && target != current.preset) {
                        store.load(target)
                    } else {
                        current
                    }
                    merge(base, patch)
                } catch (e: Exception) {
                    call.respondFail(
                        HttpStatusCode.BadRequest, ErrorCode.BAD_REQUEST,
                        "Webhook 配置字段类型不合法：${e.message}"
                    )
                    return@put
                }
                WebhookConfig.validate(merged)?.let { reason ->
                    call.respondFail(HttpStatusCode.BadRequest, ErrorCode.BAD_REQUEST, reason)
                    return@put
                }
                // patch 碰到 per-preset 字段了才写那一份；没碰就只写顶层 ——
                // "切换预设"因此真的只改一个键，目标预设那一份连默认值都不会被落盘
                // （"从未配过"要保持"从未配过"，理由见 WebhookConfigStore.saveShared）。
                if (touchesPresetFields(patch)) store.save(merged) else store.saveShared(merged)
                // 立即回读：与 POST /api/sms-forward/config 同做法，把"存进去没有"当场验掉。
                call.respond(toJsonElement(mapOf(
                    "success" to true,
                    "config" to configPayload(store.load())
                )))
            }

            /**
             * 手动测试。**不受总开关、免打扰与场景勾选约束**（`manual = true`），
             * 但仍要求配置齐全 —— URL 没填的"测试"发不出去是事实。
             *
             * 只投 webhook 渠道：这个按钮在 Webhook 设置页上，测的是这条 HTTP 链路通不通。
             * 不限定的话手机上还会多弹一条系统通知、邮箱里多一封信。
             *
             * 场景用 [NotifyScenes.TEST]（与邮件测试同口径）：投递记录里"用户点了几次测试"
             * 要能和真实通知分开。
             */
            post("/test") {
                val cfg = store.load()
                val result = mutableMapOf<String, Any?>(
                    "success" to false,
                    "auto_notify_enabled" to gate()
                )
                if (!cfg.enabled) {
                    result["error"] = "Webhook 通知未启用"
                } else if (!channel.isConfigured()) {
                    result["error"] = WebhookConfig.validate(cfg)
                        ?: "请求地址未填写完整（预设中的 <占位> 需替换为真实值）"
                } else {
                    try {
                        val report = notifier(
                            NotifyEvent(
                                scene = NotifyScenes.TEST,
                                level = NotifyLevel.INFO,
                                title = "Webhook 通知测试",
                                body = TEST_BODY,
                                meta = listOf("触发方式" to "手动测试"),
                                manual = true,
                                channels = setOf(WebhookChannel.ID)
                            )
                        )
                        val outcome = report[WebhookChannel.ID]
                        result["success"] = outcome is DeliveryOutcome.Sent
                        when (outcome) {
                            is DeliveryOutcome.Failed -> result["error"] = outcome.error
                            is DeliveryOutcome.Skipped -> result["error"] = skipMessage(outcome)
                            null -> result["error"] = "设备端未提供 Webhook 渠道"
                            else -> {}
                        }
                        // 状态码与响应体只有渠道那侧知道（DeliveryOutcome 里没有这两样），
                        // 而它们恰恰是排 Webhook 时唯一有用的信息（`{"code":40001,"msg":"invalid token"}`）。
                        //
                        // ## 契约：`attempted_at` 出现 ⇔ 本轮走到过 `deliver()`
                        //
                        // 这三个字段（`status_code` / `response_body` / `attempted_at`）**整组同进同出**，
                        // 来源是同一个 `WebhookChannel.Attempt` 对象，不可能只出现一部分。
                        // 于是客户端可以拿 `attempted_at` 是否存在当作"本轮到底有没有发起过投递"的判据：
                        // - 有 → 请求真的发出去了，`status_code` 为 null 说明请求没走完（超时/DNS/TLS）；
                        // - 无 → 一次都没投（`Skipped` 的六档、渠道未注册、`notifier` 抛异常）。
                        //
                        // core 侧保证这条**双向**成立，逐条核实过：
                        // 1. `Skipped` → `NotificationDispatcher.deliverTo` 直接 return，诊断给 null；
                        // 2. `Sent` / `Failed`（含渠道内部抛异常被兜成 Failed）→ 诊断一定非 null；
                        // 3. 整轮预算用尽 → 判在**尝试之间**、不打断在飞的那一次，所以带回的是
                        //    上一次已完成尝试的诊断（`CHANNEL_ROUND_BUDGET_MS` 的注释里写明了
                        //    为什么不能用 withTimeout 拦腰砍：那正好会造出"投过但没诊断"）；
                        // 4. 渠道未注册 → `report[ID]` 为 null，两者一起缺。
                        // 不变量由 `NotificationDispatcherTest` 的「诊断与 deliver 同进同出」钉住。
                        //
                        // 从**本次结果**里取而不是回读渠道上的可变快照：`Skipped` 时回读会把上一次的
                        // 状态码贴到这次响应上（于是出现 success=false 配 HTTP 200），
                        // 并发投递还会让两次结果互相覆盖。理由见 `DeliveryAttempt`。
                        (report.diagnosticsOf(WebhookChannel.ID) as? WebhookChannel.Attempt)?.let { attempt ->

                            result["status_code"] = attempt.statusCode
                            result["response_body"] = attempt.bodySummary
                            result["attempted_at"] = attempt.at
                        }
                    } catch (e: Exception) {
                        result["error"] = "${e.javaClass.simpleName}: ${e.message}"
                    }
                }
                call.respond(HttpStatusCode.OK, toJsonElement(result))
            }
        }
    }

    /**
     * `Skipped` 的人话解释。
     *
     * 共性档走 [skipMessageOf]（取 `SkipReason.label`，三条渠道同一句话），
     * 只有"配置未填完"这一档要说清楚是**哪个**渠道没填完 —— 用户可能同时配着三条。
     * 枚举本身仍是判据真源，这里只做展示层映射。
     */
    private fun skipMessage(outcome: DeliveryOutcome.Skipped): String = skipMessageOf(
        outcome.reason,
        mapOf(SkipReason.NOT_CONFIGURED to "Webhook 配置未填完，未发送")
    )


    /** GET 与 PUT 的响应共用一份形状 —— 两处各拼一次必然会分叉。 */
    private fun configPayload(c: WebhookConfig): Map<String, Any?> = mapOf(
        "enabled" to c.enabled,
        "preset" to c.preset.name,
        "url" to c.url,
        "method" to c.method,
        "headers" to c.headers,
        "body_template" to c.bodyTemplate,
        "content_type" to c.contentType,
        "timeout_ms" to c.timeoutMs,
        // 规则同构的两个旋钮（2026-09-10）：三条渠道的字段名与语义完全一致。
        "min_level" to c.minLevel.wireName,
        "daily_limit" to c.dailyLimit,
        "scenes" to c.scenes.toList().sorted(),
        "respect_dnd" to c.respectDnd,
        // 客户端不必自己复现"能不能投"的判据（开关 + scheme + 预设占位没填完）——
        // 这里读的就是渠道那一份 isConfigured()，不是第二套判定。
        "configured" to channel.isConfigured(),
        // 用量摆在配置里，口径与本机短信那份一致（UI 上那行"今日 2/5"读的就是这两个字段）。
        "sent_today" to store.sentToday(),
        // **不限时回 null** 而不是 0：0 的含义是"已经用尽"，与"不限"恰好相反。
        "quota_remaining" to quotaRemaining(c),
        // 取值域由 core 给（客户端手抄就多一份会分叉的镜像）。
        // 回**对象数组**（`[{"name":"info","label":"提示"}, …]`）：中文名的真源在
        // NotifyLevel.label 上，客户端不该再抄一张映射表（理由见 levelOptions）。
        "levels" to levelOptions(),

        "daily_limit_min" to WebhookConfig.MIN_DAILY_LIMIT,
        "daily_limit_max" to WebhookConfig.MAX_DAILY_LIMIT,
        // 占位符回**对象数组**（`[{"name":"title","desc":"通知标题"}, …]`）：客户端要把它渲染成
        // 可点击插入的按钮，光有名字用户分不出 message 与 meta、level 与 level_label。
        // 说明文案的唯一真源是 WebhookDelivery.PLACEHOLDERS，顺序即展示顺序。
        "placeholders" to WebhookConfig.PLACEHOLDERS.map { (name, desc) ->
            mapOf("name" to name, "desc" to desc)
        },
        "presets" to WebhookPreset.entries.map { p ->
            mapOf(
                "name" to p.name,
                "display_name" to p.displayName,
                "user_fills" to p.userFills,
                // 「用户要填的那一样东西」的机器可读声明：客户端据此只渲染一个输入框
                // （其余字段收进高级设置），并知道该把它替换到 url 还是 body_template 里。
                // 这张表**不许客户端手抄** —— marker 抄错的表现是密钥没被替换进请求，
                // 界面上看不出原因，只会收不到通知。
                "secret_label" to p.secretLabel,
                "secret_marker" to p.secretMarker,
                "secret_target" to p.secretTarget.wireName,
                "url" to p.defaultUrl,
                "method" to p.defaultMethod,
                "headers" to p.defaultHeaders,
                "content_type" to p.defaultContentType,
                "body_template" to p.defaultBody
            )
        }

    )

    /** 今天还剩几条；`daily_limit = 0`（不限）时回 null —— 那一档没有"剩余"这个数。 */
    private fun quotaRemaining(c: WebhookConfig): Int? =
        if (c.dailyLimit == ChannelRules.UNLIMITED) {
            null
        } else {
            (c.dailyLimit - store.sentToday()).coerceAtLeast(0)
        }

    /** 字段级合并：只有 patch 里显式出现且非 null 的键会覆盖。 */
    private fun merge(current: WebhookConfig, patch: JsonObject): WebhookConfig {
        fun key(name: String): JsonElement? = patch[name]?.takeIf { it !is JsonNull }
        return current.copy(
            enabled = key("enabled")?.jsonPrimitive?.boolean ?: current.enabled,
            preset = presetOf(patch) ?: current.preset,
            url = key("url")?.jsonPrimitive?.content?.trim() ?: current.url,
            method = key("method")?.jsonPrimitive?.content?.uppercase() ?: current.method,
            headers = key("headers")?.jsonObject
                ?.mapValues { (_, v) -> v.jsonPrimitive.content } ?: current.headers,
            bodyTemplate = key("body_template")?.jsonPrimitive?.content ?: current.bodyTemplate,
            contentType = key("content_type")?.jsonPrimitive?.content ?: current.contentType,
            timeoutMs = key("timeout_ms")?.jsonPrimitive?.long ?: current.timeoutMs,
            // 严格解析：认不出的级别名**抛出**（→ 400），不走 NotifyLevel.fromWire 的"回落 INFO"。
            // 口径同本机短信那侧：静默回落会让用户以为自己设的门槛生效了。
            minLevel = key("min_level")?.jsonPrimitive?.content?.let(::levelOrThrow) ?: current.minLevel,
            dailyLimit = key("daily_limit")?.jsonPrimitive?.int ?: current.dailyLimit,
            scenes = key("scenes")?.jsonArray?.map { it.jsonPrimitive.content }?.toSet()
                ?: current.scenes,
            respectDnd = key("respect_dnd")?.jsonPrimitive?.boolean ?: current.respectDnd
        )
    }

    /**
     * patch 里显式给出的目标预设；没给（或给了 null）返回 null = "不换预设"。
     *
     * 单独抽出来是因为它有两个用途，而两处必须用同一个判据：**选哪一份配置当合并的底**
     * 与**合并结果里的 preset 取值**。两处各写一遍的话就会出现"底取了 bark、
     * 存回去写成 custom"这种把 bark 的 key 写进自定义槽位的错位。
     */
    private fun presetOf(patch: JsonObject): WebhookPreset? =
        patch["preset"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content?.let(::presetOrThrow)

    /**
     * patch 里有没有"发到哪、怎么发"那一组字段（[PRESET_FIELD_KEYS]）。
     *
     * 决定这次 PUT 要不要动**目标预设那一份**：只带 `preset`（可能再带几个顶层字段）就是
     * 纯切换，一个 per-preset 键都不该写；带了才写。
     *
     * 判据是"键出现过"而不是"值和现值不同"：显式传 `"url": null` 在本接口的口径里是
     * "这个字段我没意见"（见 [merge]），所以那种 patch 也不该把那一份落盘。
     */
    private fun touchesPresetFields(patch: JsonObject): Boolean =
        PRESET_FIELD_KEYS.any { patch[it].let { v -> v != null && v !is JsonNull } }

    /**
     * 严格解析预设名：认不出就**抛出**（→ 400），不走 `WebhookPreset.fromName` 的"回落 CUSTOM"。
     *
     * 为什么在这一层严、那一层松（口径同 [levelOrThrow] 与 `NotifyLevel.fromWire` 的分工）：
     * - `fromName` 的回落是给 [WebhookConfigStore.load] 用的 —— 读到一份坏掉的 prefs 时，
     *   一个认不出的预设名不该让整份配置读不出来（既有纪律，那个函数不动）。
     * - 而 PUT 的入参回落是**有害的**：配置按预设分开存之后，客户端把 `preset` 打成 `Barkk`
     *   会静默落进 `custom.*` 那一份 —— 用户以为切到了 Bark，看到的却是"我配好的 Bark 不见了"，
     *   而日志里没有任何异常。宁可多一个 400。
     *
     * 大小写不敏感（与 `fromName` 一致）：客户端发 `bark` 还是 `BARK` 都认，只拒真正不存在的名字。
     */
    private fun presetOrThrow(name: String): WebhookPreset =
        WebhookPreset.entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
            ?: throw IllegalArgumentException(
                "preset 只支持 ${WebhookPreset.entries.joinToString("/") { it.name }}，收到 $name"
            )

    private fun levelOrThrow(name: String): NotifyLevel =
        NotifyLevel.entries.firstOrNull { it.wireName == name.lowercase() }
            ?: throw IllegalArgumentException(
                "min_level 只支持 ${WebhookConfig.LEVEL_NAMES.joinToString("/")}，收到 $name"
            )

    private companion object {
        /**
         * 「发到哪、怎么发」那一组的**线上字段名**（即每预设各存一份的那六个）。
         *
         * 注意 `headers` —— 线上名与存储键名（`headers_json`）不同，
         * 这里要的是**客户端提交的那个名字**。抄错的表现是"填了 headers 却没保存"。
         */
        val PRESET_FIELD_KEYS = setOf(
            "url", "method", "headers", "body_template", "content_type", "timeout_ms"
        )

        /**
         * 测试通知的正文。写清楚"这是测试"—— 用户按下按钮之后收到的那条消息
         * 会同时出现在群机器人里，别让同事以为设备真出事了。
         */
        const val TEST_BODY = "这是一条来自 UFI-AXIS 的 Webhook 测试通知"
    }
}
