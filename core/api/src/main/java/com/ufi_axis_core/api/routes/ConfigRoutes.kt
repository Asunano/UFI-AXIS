package com.ufi_axis_core.api.routes

import com.ufi_axis_core.api.ResponseHelper.toJsonElement
import com.ufi_axis_core.contract.ConfigLimits
import com.ufi_axis_core.contract.ErrorCode
import com.ufi_axis_core.lib_api.BuildConfig
import com.ufi_axis_core.util.AppLogger
import com.ufi_axis_core.util.AppSettings
import com.ufi_axis_core.util.GoformQoS
import com.ufi_axis_core.util.ShellQoS
import io.ktor.http.*
import io.ktor.server.application.call
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.*

/**
 * 配置管理路由
 *
 * 提供后端配置项的读取和修改接口，供前端 UI 对接。
 * 修改认证或端口配置后需重启服务才能生效。
 *
 * GET  /api/config          - 获取全部配置
 * PUT  /api/config          - 更新配置
 * POST /api/config/reset    - 恢复默认配置
 */
class ConfigRoutes(
    private val settings: AppSettings,
    /**
     * 写入 `goform_password` 前的**验证式落盘**（2026-10-30 越权审查 §2.3）。
     *
     * 入参是新口令；实现方应「把新口令交给传输层 → 让它真的登录一次 → 失败则把传输层
     * 恢复成旧口令」，返回值 = 设备是否接受这个口令。只有 true 时本类才把口令写进
     * `AppSettings`。这样一次笔误不会把 core→设备的凭据写坏（写坏的表现是所有
     * goform 请求 503，流量/信号/短信/仪表盘一起挂，且没有读回明文的路径）。
     *
     * 可空是为了不破坏既有构造点（`ConfigRoutes(settings)` 的单测与局部装配）：
     * 为空时按旧行为直接落盘，但会打一条 WARN —— 生产装配由 `ComponentFactory` 注入，
     * 由装配守卫测试守着。
     */
    private val verifyGoformPassword: (suspend (String) -> Boolean)? = null,
    /**
     * 短信拦截相关开关变更后的生效钩子（重载 `SmsRuleStore` 的内存快照）。
     *
     * 必须有：判定读的是 `@Volatile` 快照而不是 prefs，只写 `AppSettings` 等于
     * 「界面上关了豁免，引擎照旧豁免」—— 又一个假开关。
     * 用可选回调注入是因为 `:core:api` 这一层拿不到 store 的构造依赖（Room DAO）。
     */
    private val onSmsFilterConfigChanged: (suspend () -> Unit)? = null
) {
    fun register(route: Route) {
        route.route("/config") {

            // 版本信息（P0-1：与 UpdateManager.currentVersionName() 读同一来源 BuildConfig.VERSION_NAME，
            // 保证 /version 与更新比对基准一致，根治版本恒 0.1）
            get("/version") {
                call.respond(toJsonElement(mapOf(
                    "version" to BuildConfig.VERSION_NAME,
                    "min_client_version" to "1.0",
                    "update_url" to settings.updateUrl
                )))
            }

            // 获取全部配置（goform_password 只以脱敏形式出现在这里；
            // AppSettings.toMap() 刻意不含该键，见其 KDoc）
            get {
                val masked = settings.toMap().toMutableMap()
                masked["goform_password"] = maskSecret(settings.goformPassword)
                call.respond(toJsonElement(masked))
            }

            // 更新配置
            // C03：越界/空串/脱敏回写不再"静默丢弃 + success:true"，一律进 rejected_fields 并带原因与允许区间。
            // 兼容性：updated_fields / needs_restart / hint 语义不变，旧客户端不改代码也能跑。
            put {
                val body = call.receiveJsonObject()
                val updated = mutableListOf<String>()
                val rejected = mutableListOf<Map<String, Any?>>()

                // 条件性主人密码门（2026-11-14 门禁审计补回，旧版见 10a2f14:ConfigRoutes.kt:98-121）。
                //
                // 两类改动要密码，**且只在值真的变了时才要** —— 整份配置回写不会被误判，
                // 设置页日常保存不受影响：
                // · 「重指向」：`goform_ip` / `goform_port` 改完，core 会把**已存好的设备口令**
                //   发到那个地址。任何配对客户端都能把地址指到自己的服务器，再触发一次读请求，
                //   就把设备口令拿走了 —— 这条比「改错 IP 设备失联」严重得多。
                //   `update_url` 是整个 FOTA / 组件 / Web 更新链的根 URL，改它等于换供应链入口。
                // · 「打开危险开关」：`goform_command_enabled`（裸写通道）与 `ttyd_enabled`（真 PTY），
                //   两者默认关，打开等于把「执行代码」的口子重新开出来。
                val reqText = { f: String -> (body[f] as? JsonPrimitive)?.contentOrNull }
                val reqBool = { f: String -> (body[f] as? JsonPrimitive)?.booleanOrNull }
                val turningOnDangerous = listOf(
                    "goform_command_enabled" to settings.goformCommandEnabled,
                    "ttyd_enabled" to settings.ttydEnabled,
                ).any { (field, current) -> !current && reqBool(field) == true }
                val repointing =
                    reqText("goform_ip")?.let { it != settings.goformIp } == true ||
                        reqText("goform_port")?.toIntOrNull()
                            ?.let { it != settings.goformPort } == true ||
                        reqText("update_url")?.let { it != settings.updateUrl } == true
                // [owner] PUT /api/config（条件门：重指向 / 打开危险开关时才要密码）
                if ((turningOnDangerous || repointing) && !call.requireOwner(body.ownerPassword())) {
                    return@put
                }

                fun reject(field: String, reason: String, range: IntRange? = null) {
                    rejected.add(buildMap {
                        put("field", field)
                        put("reason", reason)
                        if (range != null) {
                            put("min", range.first)
                            put("max", range.last)
                        }
                    })
                }

                /** 整型字段：区间外报 OUT_OF_RANGE，键存在但不是整数报 WRONG_TYPE。 */
                fun intField(field: String, range: IntRange, apply: (Int) -> Unit) {
                    val raw = body[field] ?: return
                    if (raw is JsonNull) return
                    // 用 as? 而不是 .jsonPrimitive：对象/数组值会让后者抛异常 → StatusPages 500
                    val v = (raw as? JsonPrimitive)?.intOrNull
                    if (v == null) {
                        reject(field, ErrorCode.WRONG_TYPE, range)
                        return
                    }
                    if (v in range) {
                        apply(v)
                        updated.add(field)
                    } else {
                        reject(field, ErrorCode.OUT_OF_RANGE, range)
                    }
                }

                /** 长整型字段（2026-10-19：已读水位用 Long，id 来自 Room 自增，超过 Int 也不会在可预见范围内，但类型要对齐）。 */
                fun longField(field: String, apply: (Long) -> Unit) {
                    val raw = body[field] ?: return
                    if (raw is JsonNull) return
                    val v = (raw as? JsonPrimitive)?.longOrNull
                    if (v == null) {
                        reject(field, ErrorCode.WRONG_TYPE)
                        return
                    }
                    apply(v)
                    updated.add(field)
                }

                fun boolField(field: String, apply: (Boolean) -> Unit) {
                    val raw = body[field] ?: return
                    if (raw is JsonNull) return
                    val v = (raw as? JsonPrimitive)?.booleanOrNull
                    if (v == null) {
                        reject(field, ErrorCode.WRONG_TYPE)
                        return
                    }
                    apply(v)
                    updated.add(field)
                }


                // 2026-10-30（越权审查 §2.3）：goform_password 改为**验证式写入**。
                //
                // 原先这里是裸 `secretField` 落盘：一次只改日志级别的批量 PUT 里夹带一个错值，
                // 就把 core→设备的口令写坏，整条 goform 链路随后 503（流量/信号/短信/仪表盘
                // 一起挂），且没有任何回滚路径。
                //
                // 为什么不是"直接禁止"：Web 设置页（`GeneralPanel.vue:182`）与配对改密
                // （`PairingRoutes` 的 change-password 走另一条路）都用这个键 —— 直接拒会让
                // 现有页面保存失败。所以保留入口，但**先让设备认证一次**：老口令错了/新口令不对，
                // 设备会拒绝登录，这里回 `rejected_fields` 且不落盘。
                //
                // 三条与旧实现一致的值域判据（空串 / 脱敏值回写），保留原错误码，
                // 客户端不必改解析逻辑。
                val candidate = (body["goform_password"] as? JsonPrimitive)?.contentOrNull
                if (candidate != null) {
                    when {
                        candidate.isBlank() -> reject("goform_password", ErrorCode.BLANK_VALUE)
                        candidate.contains("***") -> reject("goform_password", ErrorCode.MASKED_VALUE)
                        else -> {
                            // 局部 val 而不是直接读构造参数：`if (verifier != null)` 之后
                            // Kotlin 才会把它智能转换成非空函数类型，`when` 的多分支条件
                            // 之间不做智能转换。
                            val verifier = verifyGoformPassword
                            if (verifier == null) {
                                AppLogger.w(
                                    "ConfigRoutes",
                                    "未装配 goform 口令探活，直接落盘（仅测试/局部装配；生产必须由 ComponentFactory 注入）"
                                )
                                settings.goformPassword = candidate
                                updated.add("goform_password")
                            } else if (runCatching { verifier(candidate) }.getOrDefault(false)) {
                                settings.goformPassword = candidate
                                updated.add("goform_password")
                            } else {
                                // 设备不认这个口令（打错 / 设备已被别处改过）：
                                // 不落盘，并让客户端看到具体原因而不是"保存成功但权限全丢了"。
                                reject("goform_password", ErrorCode.INVALID_GOFORM_CONFIG)
                            }
                        }
                    }
                }

                intField("port", ConfigLimits.PORT) { settings.port = it }
                intField("goform_port", ConfigLimits.GOFORM_PORT) { settings.goformPort = it }

                // 2026-10-19：跨设备的已读水位（验证码通知 / 已拦截角标）。
                // 只前推不回退：客户端并发的旧快照不该把水位拨回去，否则已读的内容会重新点亮角标。
                longField("sms_code_seen_msg_id") {
                    settings.smsCodeSeenMsgId = maxOf(settings.smsCodeSeenMsgId, it)
                }
                longField("sms_blocked_seen_msg_id") {
                    settings.smsBlockedSeenMsgId = maxOf(settings.smsBlockedSeenMsgId, it)
                }

                // 2026-10-05 R4-13 修复：goform_ip / update_url 格式校验。此前走宽松 textField
                // 任意串落盘：非法 goform_ip 令 goform 全链路 503；update_url 是整个 FOTA/组件/Web
                // 更新链的根 URL，无 scheme 白名单等于供应链面敞开。校验规则与 PairingManager
                // 的 GOFORM_HOST_PATTERN（长度 ≤253）对齐，复用现成判据。
                fun hostField(field: String, apply: (String) -> Unit) {
                    val v = (body[field] as? JsonPrimitive)?.contentOrNull ?: return
                    val GOFORM_HOST_PATTERN = Regex("^[a-zA-Z0-9]([a-zA-Z0-9.\\-]*[a-zA-Z0-9])?$")
                    when {
                        v.isBlank() -> reject(field, ErrorCode.BLANK_VALUE)
                        v.length > 253 || !GOFORM_HOST_PATTERN.matches(v) -> reject(field, ErrorCode.WRONG_TYPE)
                        else -> { apply(v); updated.add(field) }
                    }
                }
                fun urlField(field: String, apply: (String) -> Unit) {
                    val v = (body[field] as? JsonPrimitive)?.contentOrNull ?: return
                    if (v.isBlank() || (!v.startsWith("http://") && !v.startsWith("https://"))) {
                        reject(field, ErrorCode.WRONG_TYPE)
                    } else {
                        apply(v); updated.add(field)
                    }
                }
                hostField("goform_ip") { settings.goformIp = it }
                urlField("update_url") { settings.updateUrl = it }

                boolField("auto_start_on_boot") { settings.autoStartOnBoot = it }
                // 日志三层开关：总闸 → 两侧子开关 → 详细级别
                boolField("log_enabled") {
                    settings.logEnabled = it
                    com.ufi_axis_core.util.AppLogger.setLogEnabled(it)
                }
                boolField("core_log_enabled") {
                    settings.coreLogEnabled = it
                    com.ufi_axis_core.util.AppLogger.setCoreLogEnabled(it)
                }
                // app 侧开关 core 只负责保管真源（app 靠 GET /api/config 回读生效）
                boolField("app_log_enabled") { settings.appLogEnabled = it }
                boolField("debug_mode") {
                    settings.debugMode = it
                    com.ufi_axis_core.util.AppLogger.setDebugMode(it)
                }


                // 设备原始 dump 端点开关（计划书 9.3，默认关；关闭时 /api/device/goform 回 403）
                boolField("goform_dump_enabled") { settings.goformDumpEnabled = it }
                // 裸 goform 命令通道开关（默认关；关闭时 /api/device/goform/query|set 回 403）
                // set 会绕过 profile 的 WriteSpec 值域校验，返回值也不脱敏，只在排障时临时打开
                boolField("goform_command_enabled") { settings.goformCommandEnabled = it }
                // 真 PTY 终端开关（ttyd 反代，默认关；关闭时 /api/terminal/pty-ticket 403）。
                // 与 goform_command_enabled 同档位：打开等于把设备 shell 交给持 token 的客户端。
                boolField("ttyd_enabled") { settings.ttydEnabled = it }
                // 远程屏幕开关（2026-10-31，默认关）。关着时 /api/screen/* 全 403，
                // 且已有会话的 WS 会在下一次握手被拒（不在跑的会话不会被主动 kill）。
                boolField("screen_enabled") { settings.screenEnabled = it }
                // 字段归一化总开关（决策 D7 的排障回退开关，默认开）。2026-09-22 补上写入口：
                // 之前只有 KEY + 属性 + BACKUP_FIELDS，PUT 没登记、toMap() 也没登记，
                // 结果只有「导入备份」改得动 —— web/app 做不出真开关。
                //
                // **与上面两个开关的关键差异：这一项不热生效。**
                // goform_dump_enabled / goform_command_enabled 是每次请求现读 prefs
                // （DeviceRoutes.kt:72 / :117），PUT 完下一个请求就生效；
                // 而 fieldNormalizationEnabled 只在构造组件图时读一次
                // （ComponentFactory.resolveDeviceProfile()，
                //  core/src/main/java/com/ufi_axis_core/service/ComponentFactory.kt:802，
                //  唯一调用点 ComponentFactory.kt:152），
                // 之后 profile 被固化进各设备客户端与 SignalCollector。所以这里的 PUT 只改 prefs，
                // **必须重启后台服务才会变**（计划书 §11.10「运行期不允许热换插件」，
                // docs/device-plugin-framework-plan.md:1587-1592）。别照着上面两行类推成即时生效。
                //
                // 另注：下面 needsRestart 的清单只覆盖认证/端口（hint 文案也只提这两类），
                // 没有把本键算进去 —— 客户端不能靠 needs_restart 判断这一项，要由 UI 自己提示重启。
                boolField("field_normalization_enabled") { settings.fieldNormalizationEnabled = it }

                // QoS 参数
                boolField("qos_enabled") { settings.qosEnabled = it }
                intField("qos_shell_max_concurrent", ConfigLimits.QOS_SHELL_MAX_CONCURRENT) {
                    settings.qosShellMaxConcurrent = it
                    ShellQoS.updateRootPermits(it)
                }
                intField("qos_cache_ttl_ms", ConfigLimits.QOS_CACHE_TTL_MS) {
                    settings.qosCacheTtlMs = it
                    ShellQoS.updateCacheTtl(it.toLong())
                    GoformQoS.updateCacheTtl(it.toLong())
                }
                intField("qos_goform_query_max", ConfigLimits.QOS_GOFORM_QUERY_MAX) {
                    settings.qosGoformQueryMax = it
                    GoformQoS.adaptiveAdjust(it, settings.qosGoformSetMax)
                }
                intField("qos_goform_set_max", ConfigLimits.QOS_GOFORM_SET_MAX) {
                    settings.qosGoformSetMax = it
                    GoformQoS.adaptiveAdjust(settings.qosGoformQueryMax, it)
                }

                // SMS 验证码解析
                boolField("sms_code_enabled") { settings.smsCodeEnabled = it }
                intField("sms_code_cleanup_hours", ConfigLimits.SMS_CODE_CLEANUP_HOURS) {
                    settings.smsCodeCleanupHours = it
                }
                boolField("sms_code_auto_copy") { settings.smsCodeAutoCopy = it }

                // 媒体封面预热（2026-10-05 G6，FFmpeg 接入计划书 §4.2）。每次循环迭代现读 prefs，
                // PUT 完下一轮就生效，无需重启。
                boolField("thumb_prewarm_enabled") { settings.thumbPrewarmEnabled = it }
                boolField("thumb_prewarm_wifi_only") { settings.thumbPrewarmWifiOnly = it }

                // SMS 拦截（黑名单 + 关键词）的两个开关。规则本身走 /api/sms/rules，不在这里。
                boolField("sms_filter_exempt_verification_code") {
                    settings.smsFilterExemptVerificationCode = it
                }
                boolField("sms_filter_store_full_body") { settings.smsFilterStoreFullBody = it }
                // 豁免开关写进 prefs 之后必须让判定快照重载，否则改了不生效。
                // storeFullBody 是每次写记录时才读的，本来就不需要重载，但一起走这个钩子更难忘。
                if (updated.any { it.startsWith("sms_filter_") }) {
                    onSmsFilterConfigChanged?.invoke()
                }

                // update_mirror_base 允许设为空串 = 直连（因此不能用 textField）；
                // contentOrNull 对 JsonNull/数字/布尔返回 null，只有显式传字符串才会进入此分支。
                val mirrorBaseWritten = body["update_mirror_base"]?.jsonPrimitive?.contentOrNull?.let {
                    settings.updateMirrorBase = it
                    updated.add("update_mirror_base")
                    it
                }

                // ── 下载方式（2026-09-22：core 成为唯一决策方）──
                // 取值非法时进 rejected_fields 而不是静默回落：客户端写错了值必须能被发现。
                val modeWritten = body["update_source_mode"]?.jsonPrimitive?.contentOrNull?.let { raw ->
                    val v = raw.trim().lowercase()
                    if (v in AppSettings.UPDATE_MODES) {
                        settings.updateSourceMode = v
                        updated.add("update_source_mode")
                        v
                    } else {
                        reject("update_source_mode", ErrorCode.OUT_OF_RANGE)
                        null
                    }
                }

                // 老客户端兼容：只写了 update_mirror_base、没写 update_source_mode 时推导模式。
                // 老 app 的 direct 写空串、auto 与 mirror 都写同一个前缀；不推导的话，
                // 老 app 上用户选的「直连」会被新 core 当成 auto，在国内变成走镜像。
                // auto 而不是 mirror：老 app 的 auto / mirror 不可区分，取更安全的那个。
                if (modeWritten == null && mirrorBaseWritten != null) {
                    val derived = if (mirrorBaseWritten.isBlank()) {
                        AppSettings.UPDATE_MODE_DIRECT
                    } else {
                        AppSettings.UPDATE_MODE_AUTO
                    }
                    settings.updateSourceMode = derived
                    updated.add("update_source_mode")
                    AppLogger.i(
                        "ConfigRoutes",
                        "老客户端只下发了 update_mirror_base，已推导 update_source_mode=$derived"
                    )
                }



                val needsRestart = updated.any { it in listOf("port", "goform_ip", "goform_port", "goform_password") }

                call.respond(toJsonElement(mapOf(
                    "success" to true,
                    "updated_fields" to updated,
                    "rejected_fields" to rejected,
                    "needs_restart" to needsRestart,
                    "hint" to if (needsRestart) "修改了认证或端口配置，需重启服务生效" else ""
                )))
            }


            /**
             * 恢复默认配置。
             *
             * 0.1.0 起这里就要配对密码；本版把判定统一收到 `OwnerGate`（同一套节流与锁定），
             * 由 `owner_gate_enabled` 开关决定是否需要 —— 默认关，关着时只要求「已配对身份」。
             * 身份与凭据键不会被清掉（见 `AppSettings.PRESERVED_ON_RESET`），
             * 所以即使全量重置也不会把设备打回可接管的出厂态。
             */
            post("/reset") {
                val body = runCatching { call.receiveJsonObject() }.getOrNull()
                // [owner] POST /api/config/reset
                if (!call.requireOwner(body.ownerPassword())) return@post
                // 2026-10-30（审查 §2.5）：支持按域重置。
                //
                // 不传 `scopes` → 全量（与旧行为逐字一致，旧客户端不改代码也能跑）。
                // 传了 → 只清这几个域，并在响应里回 `cleared_scopes` / `kept_scopes`，
                // 让客户端能在确认框里如实写"会丢什么"。
                // 在此之前这里只会清全量，而用户看到的是一句"已恢复默认配置"——
                // 通知令牌、媒体扫描目录、隧道看护设置被一起清掉却毫不知情。
                val known = settings.resetScopeNames()
                val requested: List<String>? = (body?.get("scopes") as? JsonArray)
                    ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim() }
                    ?.filter { it.isNotEmpty() }
                if (requested == null) {
                    settings.resetAll()
                    call.respond(toJsonElement(mapOf(
                        "success" to true,
                        "scope" to "all",
                        "cleared_scopes" to known.sorted(),
                        "kept_scopes" to emptyList<String>(),
                        "message" to "已恢复默认配置（配对信息与配对密码保持不变），需重启服务生效"
                    )))
                    return@post
                }

                val unknown = requested.filterNot { it in known }
                if (unknown.isNotEmpty()) {
                    call.respondFail(
                        HttpStatusCode.BadRequest,
                        ErrorCode.BAD_REQUEST,
                        "未知的配置域：${unknown.joinToString("、")}（可用：${known.sorted().joinToString("、")}）"
                    )
                    return@post
                }
                if (requested.isEmpty()) {
                    call.respondFail(
                        HttpStatusCode.BadRequest,
                        ErrorCode.BAD_REQUEST,
                        "scopes 不能是空数组；要恢复全部配置请省略该参数"
                    )
                    return@post
                }

                settings.resetScopes(requested.toSet())
                call.respond(toJsonElement(mapOf(
                    "success" to true,
                    "scope" to "partial",
                    "cleared_scopes" to requested.sorted(),
                    "kept_scopes" to known.filterNot { it in requested }.sorted(),
                    "message" to "已恢复所选的 ${requested.size} 个配置域（配对信息与配对密码保持不变），需重启服务生效"
                )))
            }
        }
    }

    /**
     * 脱敏占位符：**定长**，与真实值的长度、内容都无关。
     *
     * 2026-09-08：原实现是 `take(2) + "*" * (len-4) + takeLast(2)`（如 `ad****in`）——
     * 等于把 goform 后台密码的**准确长度**加首尾各两位，明文送给任何已鉴权客户端。
     * 已配对客户端不等于可以拿到设备后台口令（配对口令与 goform 口令是两把不同的钥匙），
     * 这条 GET /config 的返回本来只该表达「有没有设过」。
     *
     * 客户端侧不依赖具体掩码串：web 的 GeneralPanel 读回后**无条件**把密码框清成空串
     * （web/src/views/settings/panels/GeneralPanel.vue:184-187），app 只判 isNotBlank
     * 当作「已设置」（ToolsModule:1080）。掩码串固定为 8 个星号，保留 `***` 特征只是为了
     * 让任何"看起来像掩码"的值一眼可辨。
     *
     * 2026-10-30（审查 §2.3）：`PUT /api/config` 对 `goform_password` 改成**验证式写入**
     * （先让设备用新口令登录一次，成功才落盘，见构造参数 `verifyGoformPassword`），
     * 所以「把掩码回写」依然要拦 —— 它必然登录失败，但提前判掉能给出更准确的原因
     * （`MASKED_VALUE` 而不是 `INVALID_GOFORM_CONFIG`）。
     */
    private fun maskSecret(secret: String): String =
        if (secret.isEmpty()) "" else MASK_PLACEHOLDER

    private companion object {
        /**
         * 定长脱敏占位符（8 个星号）。
         *
         * 定长 + 含 `***` 是刻意的：长度不泄漏真实口令长度，`***` 让任何"看起来像掩码"
         * 的值一眼可辨（客户端据此避免把掩码当成真实值提交）。
         */
        const val MASK_PLACEHOLDER = "********"
    }
}


