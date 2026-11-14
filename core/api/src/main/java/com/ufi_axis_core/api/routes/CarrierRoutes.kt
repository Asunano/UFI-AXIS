package com.ufi_axis_core.api.routes

import com.ufi_axis_core.contract.ErrorCode
import com.ufi_axis_core.controller.carrier.CarrierAutoLoginConfig
import com.ufi_axis_core.controller.carrier.CarrierAutoLoginManager
import com.ufi_axis_core.controller.carrier.CarrierAutoLoginState
import com.ufi_axis_core.controller.carrier.CarrierAccountNotFoundException
import com.ufi_axis_core.controller.carrier.CarrierAccountSummary
import com.ufi_axis_core.controller.carrier.CarrierBill
import com.ufi_axis_core.controller.carrier.CarrierBillResult
import com.ufi_axis_core.controller.carrier.CarrierBillSettingsSnapshot
import com.ufi_axis_core.controller.carrier.CarrierKeepAliveAccountState
import com.ufi_axis_core.controller.carrier.CarrierService
import com.ufi_axis_core.controller.carrier.CarrierCodeExpiredException
import com.ufi_axis_core.controller.carrier.CarrierKind
import com.ufi_axis_core.controller.carrier.CarrierLoginRequiredException
import com.ufi_axis_core.controller.carrier.CarrierRateLimitedException
import com.ufi_axis_core.controller.carrier.CarrierStatus
import com.ufi_axis_core.controller.carrier.CarrierUpstreamException
import com.ufi_axis_core.controller.carrier.UnicomQrPoll
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.Base64

/**
 * 运营商账单 API。
 *
 * 全部端点位于现有 `/api` 鉴权内。发送验证码会产生真实短信费用，客户端在触发前自行确认。
 * 会话、Cookie 与完整手机号均不下发。
 *
 * - 移动：`POST /mobile/login/request` → `POST /mobile/login/verify`
 * - 联通：`POST /unicom/login/qr` → 轮询 `GET /unicom/login/status`
 * - 通用：`GET /status`、`GET /bill?refresh=1`、`POST /logout`、`POST /account`（换号）
 */
class CarrierRoutes(
    private val service: CarrierService,
    private val autoLogin: CarrierAutoLoginManager? = null,
) {

    fun register(route: Route) {
        route.route("/carrier") {
            get("/status") {
                // 旧客户端只读 state/carrier/phone；新客户端额外读取 accounts。
                // 新增字段不改变既有语义，两种客户端可同时工作（兼容性约束）。
                call.respond(service.status().toJson().withAccounts())
            }

            // ── 账号维度 ──────────────────────────────────────────────────
            //
            // 上方 /status、/bill、/mobile/login/* 语义固定为「当前账号」，行为保持不变；
            // 本组接口与之并列存在，路径带 /accounts/{id}/ 前缀。
            //
            // 兼容探测：不支持账号维度的旧 core 对这些路由返回 404，
            // App 据此降级为单账号模式。

            get("/accounts") {
                // 与 status 的 withAccounts() 同口径：单个账号状态异常（未知 provider、索引重建失败）
                // 不该让整个列表 500 —— 那会让客户端以为「一个账号都没有」而显示空页。
                call.respond(
                    runCatching {
                        buildJsonObject {
                            put("active_id", service.activeAccountId())
                            put("accounts", buildJsonArray {
                                service.accounts().forEach { add(it.toJson()) }
                            })
                        }
                    }.getOrElse { e ->
                        com.ufi_axis_core.util.AppLogger.w(
                            "CarrierRoutes",
                            "GET /carrier/accounts 部分账号状态异常，按空列表降级：${e.javaClass.simpleName}"
                        )
                        buildJsonObject {
                            put("active_id", null as String?)
                            put("accounts", buildJsonArray { })
                        }
                    }
                )
            }

            post("/accounts") {
                val body = call.receiveJsonObject()
                val label = body["label"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                val accessMethod = body["access_method"]?.jsonPrimitive?.contentOrNull
                    ?.trim()?.lowercase() ?: "carrier"
                if (accessMethod in setOf("iot", "iot_card")) {
                    val cardNo = body["card"]?.jsonPrimitive?.contentOrNull
                        ?: body["phone"]?.jsonPrimitive?.contentOrNull
                    val provider = body["provider"]?.jsonPrimitive?.contentOrNull
                        ?: body["iot_provider"]?.jsonPrimitive?.contentOrNull
                    runCatching {
                        service.addIotAccount(
                            cardNo.orEmpty().trim(),
                            label,
                            provider.orEmpty().ifBlank { "cloud" },
                        )
                    }
                        .onSuccess { call.respond(it.toJson()) }
                        .onFailure { call.respondCarrierError(it) }
                } else {
                    val carrier = runCatching { carrierOf(body) }.getOrElse {
                        return@post call.respondCarrierError(it)
                    }
                    val phone = body["phone"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                    runCatching { service.addAccount(carrier, phone, label) }
                        .onSuccess { call.respond(it.toJson()) }
                        .onFailure { call.respondCarrierError(it) }
                }
            }

            post("/accounts/{id}/activate") {
                val id = call.parameters["id"].orEmpty()
                runCatching { service.activateAccount(id) }
                    .onSuccess { call.respond(it.toJson()) }
                    .onFailure { call.respondCarrierError(it) }
            }

            patch("/accounts/{id}") {
                val id = call.parameters["id"].orEmpty()
                val label = call.receiveJsonObject()["label"]
                    ?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                runCatching { service.renameAccount(id, label) }
                    .onSuccess { call.respond(buildJsonObject { put("success", true) }) }
                    .onFailure { call.respondCarrierError(it) }
            }

            delete("/accounts/{id}") {
                val id = call.parameters["id"].orEmpty()
                runCatching { service.removeAccount(id) }
                    .onSuccess {
                        // 清除调度器内存态与自动登录持久化计数：同一号码重新添加时不继承旧的参与位与次数。
                        autoLogin?.forgetAccount(id)
                        call.respond(buildJsonObject { put("success", true) })
                    }
                    .onFailure { call.respondCarrierError(it) }
            }

            post("/accounts/{id}/login/mobile/request") {
                val id = call.parameters["id"].orEmpty()
                val phone = call.receiveJsonObject()["phone"]
                    ?.jsonPrimitive?.contentOrNull?.trim()
                runCatching { service.requestMobileCodeFor(id, phone) }
                    .onSuccess { call.respond(it.toJson()) }
                    .onFailure { call.respondCarrierError(it) }
            }

            post("/accounts/{id}/login/mobile/verify") {
                val id = call.parameters["id"].orEmpty()
                val code = call.receiveJsonObject()["code"]
                    ?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                if (code.length != 6 || code.any { !it.isDigit() }) {
                    return@post call.respondFail(
                        HttpStatusCode.BadRequest,
                        ErrorCode.BAD_REQUEST,
                        "验证码为 6 位数字"
                    )
                }
                runCatching { service.verifyMobileCodeFor(id, code) }
                    .onSuccess { call.respond(it.toJson()) }
                    .onFailure { call.respondCarrierError(it) }
            }

            post("/accounts/{id}/login/unicom/qr") {
                val id = call.parameters["id"].orEmpty()
                val force = call.request.queryParameters["refresh"] == "1"
                runCatching { service.startUnicomQrFor(id, force) }
                    .onSuccess { start ->
                        call.respond(buildJsonObject {
                            put("state", "qr_pending")
                            put("qr_png_base64", Base64.getEncoder().encodeToString(start.png))
                            put("expires_in", start.expiresInSeconds)
                            put("poll_after_ms", 2000)
                        })
                    }
                    .onFailure { call.respondCarrierError(it) }
            }

            post("/accounts/{id}/login/unicom/password") {
                val id = call.parameters["id"].orEmpty()
                val body = call.receiveJsonObject()
                val phone = body["phone"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                val password = body["password"]?.jsonPrimitive?.contentOrNull.orEmpty()
                runCatching { service.loginUnicomWithPasswordFor(id, phone, password) }
                    .onSuccess { call.respond(it.toJson()) }
                    .onFailure { call.respondCarrierError(it) }
            }

            get("/accounts/{id}/login/unicom/status") {
                val id = call.parameters["id"].orEmpty()
                runCatching { service.pollUnicomQrFor(id) }
                    .onSuccess { call.respond(it.toJson()) }
                    .onFailure { call.respondCarrierError(it) }
            }

            post("/accounts/{id}/login/cloud") {
                val id = call.parameters["id"].orEmpty()
                val body = call.receiveJsonObject()
                val cardNo = body["card"]?.jsonPrimitive?.contentOrNull
                    ?: body["phone"]?.jsonPrimitive?.contentOrNull
                    ?: ""
                val verify = body["verify"]?.jsonPrimitive?.contentOrNull
                runCatching { service.loginCloudCardFor(id, cardNo.trim(), verify) }
                    .onSuccess { call.respond(it.toJson()) }
                    .onFailure { call.respondCarrierError(it) }
            }

            get("/accounts/{id}/auto-login") {
                val manager = autoLogin
                val id = call.parameters["id"].orEmpty()
                if (manager == null) {
                    call.respondFail(HttpStatusCode.NotImplemented, ErrorCode.NOT_SUPPORTED, "自动登录未装配")
                } else {
                    call.respond(manager.snapshotOf(id).toJson())
                }
            }

            put("/accounts/{id}/auto-login") {
                val manager = autoLogin
                val id = call.parameters["id"].orEmpty()
                if (manager == null) {
                    return@put call.respondFail(
                        HttpStatusCode.NotImplemented, ErrorCode.NOT_SUPPORTED, "自动登录未装配"
                    )
                }
                val input = autoConfigOf(call.receiveJsonObject())
                runCatching { manager.updateConfigOf(id, input) }
                    .onSuccess { call.respond(it.toJson()) }
                    .onFailure { call.respondCarrierError(it) }
            }

            post("/accounts/{id}/auto-login/run") {
                val manager = autoLogin
                val id = call.parameters["id"].orEmpty()
                if (manager == null) {
                    return@post call.respondFail(
                        HttpStatusCode.NotImplemented, ErrorCode.NOT_SUPPORTED, "自动登录未装配"
                    )
                }
                call.respond(manager.triggerOf(id).toJson())
            }

            get("/accounts/{id}/bill") {
                val id = call.parameters["id"].orEmpty()
                val force = call.request.queryParameters["refresh"] in setOf("1", "true")
                runCatching { service.billSnapshotOf(id, force) }
                    .onSuccess { result -> call.respond(billPayload(result)) }
                    .onFailure { call.respondCarrierError(it) }
            }

            post("/account") {
                val body = call.receiveJsonObject()
                val carrier = runCatching { carrierOf(body) }.getOrElse {
                    return@post call.respondCarrierError(it)
                }
                val phone = body["phone"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                runCatching { service.switchAccount(carrier, phone) }
                    .onSuccess { call.respond(service.status().toJson()) }
                    .onFailure { call.respondCarrierError(it) }
            }

            post("/mobile/login/request") {
                val phone = call.receiveJsonObject()["phone"]
                    ?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                runCatching { service.requestMobileCode(phone) }
                    .onSuccess { call.respond(it.toJson()) }
                    .onFailure { call.respondCarrierError(it) }
            }

            post("/mobile/login/verify") {
                val code = call.receiveJsonObject()["code"]
                    ?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                if (code.length != 6 || code.any { !it.isDigit() }) {
                    return@post call.respondFail(
                        HttpStatusCode.BadRequest,
                        ErrorCode.BAD_REQUEST,
                        "验证码为 6 位数字"
                    )
                }
                runCatching { service.verifyMobileCode(code) }
                    .onSuccess { call.respond(it.toJson()) }
                    .onFailure { call.respondCarrierError(it) }
            }

            post("/unicom/login/qr") {
                val force = call.request.queryParameters["refresh"] == "1"
                runCatching { service.startUnicomQr(force) }
                    .onSuccess { start ->
                        call.respond(buildJsonObject {
                            put("state", "qr_pending")
                            put("qr_png_base64", Base64.getEncoder().encodeToString(start.png))
                            put("expires_in", start.expiresInSeconds)
                            put("poll_after_ms", 2000)
                        })
                    }
                    .onFailure { call.respondCarrierError(it) }
            }

            post("/unicom/login/password") {
                val body = call.receiveJsonObject()
                val phone = body["phone"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                val password = body["password"]?.jsonPrimitive?.contentOrNull.orEmpty()
                runCatching { service.loginUnicomWithPassword(phone, password) }
                    .onSuccess { call.respond(it.toJson()) }
                    .onFailure { call.respondCarrierError(it) }
            }

            get("/unicom/login/status") {
                runCatching { service.pollUnicomQr() }
                    .onSuccess { call.respond(it.toJson()) }
                    .onFailure { call.respondCarrierError(it) }
            }

            get("/auto-login") {
                val manager = autoLogin
                if (manager == null) {
                    call.respondFail(HttpStatusCode.NotImplemented, ErrorCode.NOT_SUPPORTED, "自动登录未装配")
                } else {
                    call.respond(manager.snapshot().toJson())
                }
            }

            put("/auto-login") {
                val manager = autoLogin
                if (manager == null) {
                    return@put call.respondFail(HttpStatusCode.NotImplemented, ErrorCode.NOT_SUPPORTED, "自动登录未装配")
                }
                val input = autoConfigOf(call.receiveJsonObject())
                runCatching { manager.updateConfig(input) }
                    .onSuccess { call.respond(it.toJson()) }
                    .onFailure { call.respondCarrierError(it) }
            }

            post("/auto-login/run") {
                val manager = autoLogin
                if (manager == null) {
                    return@post call.respondFail(HttpStatusCode.NotImplemented, ErrorCode.NOT_SUPPORTED, "自动登录未装配")
                }
                manager.trigger()
                call.respond(manager.snapshot().toJson())
            }

            // ── 设备级账单策略 ────────────────────────────────────────────
            // 刷新 / 自动保活 / 每日短信额度为设备级单份配置；账号侧仅保留「参与位」。
            get("/bill/settings") {
                val manager = autoLogin
                if (manager == null) {
                    call.respondFail(HttpStatusCode.NotImplemented, ErrorCode.NOT_SUPPORTED, "自动登录未装配")
                } else {
                    call.respond(manager.settingsSnapshot().toJson())
                }
            }

            put("/bill/settings") {
                val manager = autoLogin
                if (manager == null) {
                    return@put call.respondFail(
                        HttpStatusCode.NotImplemented, ErrorCode.NOT_SUPPORTED, "自动登录未装配"
                    )
                }
                runCatching {
                    val body = call.receiveJsonObject()
                    // 解析放进 updateBillSettings 的锁内：两个客户端并发改不同字段时，
                    // 后进锁的请求基于"上一个请求写后的当前值"合并，不会用旧快照整体覆盖。
                    manager.updateBillSettings { current ->
                        val refresh = body["refresh"]?.jsonObject
                        val keepalive = body["keepalive"]?.jsonObject
                        current.copy(
                            refreshEnabled = refresh?.get("enabled")?.jsonPrimitive?.booleanOrNull
                                ?: current.refreshEnabled,
                            refreshIntervalMinutes = refresh?.get("interval_minutes")
                                ?.jsonPrimitive?.intOrNull ?: current.refreshIntervalMinutes,
                            keepAliveEnabled = keepalive?.get("enabled")?.jsonPrimitive?.booleanOrNull
                                ?: current.keepAliveEnabled,
                            checkIntervalMinutes = keepalive?.get("check_interval_minutes")
                                ?.jsonPrimitive?.intOrNull ?: current.checkIntervalMinutes,
                            reloginIntervalMinutes = keepalive?.get("relogin_interval_minutes")
                                ?.jsonPrimitive?.intOrNull ?: current.reloginIntervalMinutes,
                            dailySmsLimit = keepalive?.get("daily_sms_limit")
                                ?.jsonPrimitive?.intOrNull ?: current.dailySmsLimit,
                        )
                    }
                }
                    .onSuccess { call.respond(manager.settingsSnapshot().toJson()) }
                    .onFailure { call.respondCarrierError(it) }
            }

            put("/bill/settings/accounts/{id}") {
                val manager = autoLogin
                if (manager == null) {
                    return@put call.respondFail(
                        HttpStatusCode.NotImplemented, ErrorCode.NOT_SUPPORTED, "自动登录未装配"
                    )
                }
                val id = call.parameters["id"].orEmpty()
                val participating = call.receiveJsonObject()["participating"]
                    ?.jsonPrimitive?.booleanOrNull
                if (participating == null) {
                    return@put call.respondFail(
                        HttpStatusCode.BadRequest, ErrorCode.BAD_REQUEST, "participating 必须是 true / false"
                    )
                }
                runCatching { manager.setParticipating(id, participating) }
                    .onSuccess { call.respond(manager.settingsSnapshot().toJson()) }
                    .onFailure { call.respondCarrierError(it) }
            }

            get("/bill") {
                val force = call.request.queryParameters["refresh"] in setOf("1", "true")
                runCatching {
                    // 多账号实现有 activeId → 走带缓存的账号维度；
                    // 仅实现单账号的旧装配（或测试 fake）没有 activeId → 退回 bill() 语义。
                    val id = service.activeAccountId()
                    if (id != null) {
                        service.billSnapshotOf(id, force)
                    } else {
                        CarrierBillResult(service.bill(force))
                    }
                }
                    .onSuccess { result -> call.respond(billPayload(result)) }
                    .onFailure { call.respondCarrierError(it) }
            }

            post("/logout") {
                service.logout()
                call.respond(buildJsonObject { put("state", "login_required") })
            }
        }
    }

    /** 自动登录配置的 JSON 解析（老 PUT 与账号维度 PUT 共用）。 */
    private fun autoConfigOf(body: JsonObject): CarrierAutoLoginConfig = CarrierAutoLoginConfig(
        enabled = body["enabled"]?.jsonPrimitive?.booleanOrNull ?: false,
        phone = body["phone"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty(),
        checkIntervalMinutes = body["check_interval_minutes"]?.jsonPrimitive?.intOrNull ?: 5,
        reloginIntervalMinutes = body["relogin_interval_minutes"]?.jsonPrimitive?.intOrNull ?: 25,
        maxAttemptsPerDay = body["max_attempts_per_day"]?.jsonPrimitive?.intOrNull ?: 8,
    )

    private fun carrierOf(body: JsonObject): CarrierKind {
        val raw = body["carrier"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase() ?: "mobile"
        return when (raw) {
            "unicom" -> CarrierKind.UNICOM
            "telecom" -> CarrierKind.TELECOM
            "broadnet", "cbn" -> CarrierKind.BROADNET
            "mobile", "" -> CarrierKind.MOBILE
            else -> throw IllegalArgumentException("carrier 只能是 mobile / unicom / telecom / broadnet")
        }
    }

    private suspend fun ApplicationCall.respondCarrierError(error: Throwable) {
        // 同时输出到 logcat：日志文件写入可能被关闭。
        // 仅记录异常类型与上游消息，不含 Cookie/手机号/验证码。
        com.ufi_axis_core.util.AppLogger.w(
            "CarrierRoutes",
            "carrier API failed: ${error.javaClass.simpleName}: ${error.message}"
        )
        when (error) {
            is CarrierAccountNotFoundException -> respondFail(
                HttpStatusCode.NotFound, ErrorCode.NOT_FOUND, error.message ?: "账号不存在"
            )
            is CarrierLoginRequiredException -> respondFail(
                HttpStatusCode.Conflict, "CARRIER_LOGIN_REQUIRED", error.message ?: "需要登录运营商账号"
            )
            is CarrierCodeExpiredException -> respondFail(
                HttpStatusCode.Conflict, "CARRIER_CODE_EXPIRED", error.message ?: "验证码已过期"
            )
            is CarrierRateLimitedException -> respondFail(
                HttpStatusCode.TooManyRequests,
                ErrorCode.TOO_MANY_REQUESTS,
                error.message ?: "发送太频繁",
                mapOf("retry_after" to error.retryAfterSeconds)
            )
            is com.ufi_axis_core.controller.carrier.unicom.UnicomPasswordException -> respondFail(
                HttpStatusCode.Unauthorized,
                "CARRIER_PASSWORD_REJECTED",
                error.message ?: "服务密码错误",
                mapOf("forced_sms" to error.forcedSms)
            )
            is CarrierUpstreamException -> respondFail(
                HttpStatusCode.BadGateway, ErrorCode.UNAVAILABLE, error.message ?: "运营商接口失败"
            )
            is IllegalArgumentException -> respondFail(
                HttpStatusCode.BadRequest, ErrorCode.BAD_REQUEST, error.message ?: "参数错误"
            )
            else -> respondFail(
                HttpStatusCode.BadGateway,
                ErrorCode.UNAVAILABLE,
                "运营商接口失败：${error.message ?: error.javaClass.simpleName}"
            )
        }
    }

    /**
     * 为 `GET /status` 的响应追加账号列表（新增字段，旧客户端忽略）。
     *
     * 附加在 status 而非要求客户端先调用 `/accounts`：进入页面时单个请求即可同时获得
     * 当前账号状态与账号列表，减少一次往返。`/accounts` 仍独立存在，用于仅刷新列表的场景。
     */
    private fun JsonObject.withAccounts(): JsonObject = buildJsonObject {
        this@withAccounts.forEach { (k, v) -> put(k, v) }
        runCatching {
            buildJsonObject {
                put("active_id", service.activeAccountId())
                put("accounts", buildJsonArray {
                    service.accounts().forEach { add(it.toJson()) }
                })
            }
        }.getOrNull()?.forEach { (k, v) -> put(k, v) }
    }

    private fun CarrierAccountSummary.toJson(): JsonObject = buildJsonObject {
        put("id", id)
        put("access_method", accessMethod)
        if (accessMethod == "iot_card") put("iot_provider", iotProvider)
        carrier?.let { put("carrier", it.name.lowercase()) }
        put("phone", phoneMasked)
        put("label", label)
        put("state", state)
        put("created_at", createdAt)
    }

    /**
     * 账单响应体：旧 `/bill` 与 `/accounts/{id}/bill` 共用，避免两处实现漂移。
     *
     * `state=login_required` 时 `bill` 仍可能存在 —— 来自磁盘缓存中的最近一次数据；
     * 只识别 409 的旧客户端会忽略 `bill` 并继续显示登录卡片。
     */
    private fun billPayload(result: CarrierBillResult): JsonObject = buildJsonObject {
        put("state", if (result.loginRequired) "login_required" else "ready")
        put("fetched_at", System.currentTimeMillis())
        put("login_required", result.loginRequired)
        put("stale", result.stale)
        putNullable("cached_at", result.cachedAt)
        result.error?.takeIf { it.isNotBlank() }?.let { put("message", it) }
        put("bill", result.bill.toJson())
        put("warnings", buildJsonArray {
            result.bill.warnings.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) }
            result.error?.takeIf { it.isNotBlank() }?.let {
                add(kotlinx.serialization.json.JsonPrimitive(it))
            }
        })
    }

    private fun CarrierStatus.toJson(): JsonObject = buildJsonObject {
        when (this@toJson) {
            CarrierStatus.NotConfigured -> put("state", "not_configured")
            is CarrierStatus.LoginRequired -> {
                put("state", "login_required")
                this@toJson.carrier?.let { put("carrier", it.name.lowercase()) }
                this@toJson.phoneMasked?.let { put("phone", it) }
                this@toJson.message?.let { put("message", it) }
            }
            is CarrierStatus.CodeSent -> {
                put("state", "code_sent")
                put("carrier", "mobile")
                put("phone", this@toJson.phoneMasked)
                put("expires_in", this@toJson.expiresInSeconds)
                put("retry_after", this@toJson.retryAfterSeconds)
            }
            is CarrierStatus.QrPending -> {
                put("state", "qr_pending")
                put("carrier", "unicom")
                put("status", this@toJson.status)
                put("expires_in", this@toJson.expiresInSeconds)
            }
            is CarrierStatus.Ready -> {
                put("state", "ready")
                put("carrier", this@toJson.carrier.name.lowercase())
                put("phone", this@toJson.phoneMasked)
                this@toJson.lastFetchedAt?.let { put("last_fetched_at", it) }
            }
            is CarrierStatus.Error -> {
                put("state", "error")
                put("message", this@toJson.message)
            }
        }
    }

    private fun CarrierStatus.CodeSent.toJson(): JsonObject = buildJsonObject {
        put("state", "code_sent")
        put("carrier", "mobile")
        put("phone", phoneMasked)
        put("expires_in", expiresInSeconds)
        put("retry_after", retryAfterSeconds)
    }

    private fun CarrierStatus.Ready.toJson(): JsonObject = buildJsonObject {
        put("state", "ready")
        put("carrier", carrier.name.lowercase())
        put("phone", phoneMasked)
        lastFetchedAt?.let { put("last_fetched_at", it) }
    }

    private fun UnicomQrPoll.toJson(): JsonObject = buildJsonObject {
        put("state", state)
        message?.let { put("message", it) }
        put("retry_after_ms", 2000)
    }

    private fun CarrierAutoLoginState.toJson(): JsonObject = buildJsonObject {
        put("supported", supported)
        carrier?.let { put("carrier", it.name.lowercase()) }
        simOperator?.let { put("sim_operator", it) }
        operatorName?.let { put("operator_name", it) }
        put("enabled", enabled)
        phoneMasked?.let { put("phone", it) }
        put("phase", phase)
        message?.let { put("message", it) }
        lastLoginAt?.let { put("last_login_at", it) }
        nextCheckAt?.let { put("next_check_at", it) }
        put("attempts_today", attemptsToday)
        // 设备策略的只读回显。旧客户端忽略未知字段；新客户端用这三个值初始化输入框，
        // 避免出现显示 5/25/8 而保存时覆盖真实值的问题。
        put("participating", participating)
        put("check_interval_minutes", checkIntervalMinutes)
        put("relogin_interval_minutes", reloginIntervalMinutes)
        put("max_attempts_per_day", maxAttemptsPerDay)
    }

    private fun CarrierBillSettingsSnapshot.toJson(): JsonObject = buildJsonObject {
        put("refresh", buildJsonObject {
            put("enabled", settings.refreshEnabled)
            put("interval_minutes", settings.refreshIntervalMinutes)
        })
        put("keepalive", buildJsonObject {
            put("enabled", settings.keepAliveEnabled)
            put("check_interval_minutes", settings.checkIntervalMinutes)
            put("relogin_interval_minutes", settings.reloginIntervalMinutes)
            put("daily_sms_limit", settings.dailySmsLimit)
            put("attempts_today", attemptsToday)
            putNullable("next_check_at", nextCheckAt)
        })
        put("accounts", buildJsonArray {
            accounts.forEach { add(it.toJson()) }
        })
    }

    private fun CarrierKeepAliveAccountState.toJson(): JsonObject = buildJsonObject {
        put("id", id)
        put("label", label)
        put("phone", phoneMasked)
        put("carrier", carrier)
        put("participating", participating)
        put("supported", supported)
        put("phase", phase)
        putNullable("message", message)
        put("attempts_today", attemptsToday)
        putNullable("last_login_at", lastLoginAt)
        putNullable("next_check_at", nextCheckAt)
    }

    private fun CarrierBill.toJson(): JsonObject = buildJsonObject {
        put("carrier", carrier.name.lowercase())
        put("access_method", accessMethod)
        put("phone", phoneMasked)
        putNullable("plan_name", planName)
        putNullable("owner_name", ownerName)
        putNullable("tenure_months", tenureMonths)
        putNullable("star_level", starLevel)
        putNullable("balance_yuan", balanceYuan)
        putNullable("last_month_bill_yuan", lastMonthBillYuan)
        putNullable("current_bill_yuan", currentBillYuan)
        putNullable("current_bill_period", currentBillPeriod)
        putNullable("current_bill_as_of", currentBillAsOf)
        putNullable("points", points)
        put("flow", buildJsonObject {
            put("general_used_gb", flow.generalUsedGb)
            put("general_total_gb", flow.generalTotalGb)
            put("general_remaining_gb", flow.generalRemainingGb)
            put("directed_used_gb", flow.directedUsedGb)
            put("directed_total_gb", flow.directedTotalGb)
            put("directed_remaining_gb", flow.directedRemainingGb)
            flow.regionalUsedGb?.let { put("regional_used_gb", it) }
            flow.regionalTotalGb?.let { put("regional_total_gb", it) }
            flow.regionalRemainingGb?.let { put("regional_remaining_gb", it) }
            put("general_unlimited", flow.generalUnlimited)
        })
        putNullable("monthly_used_gb", monthlyUsedGb)
        voice?.let {
            put("voice", buildJsonObject {
                put("used", it.used); put("total", it.total); put("remaining", it.remaining)
            })
        }
        sms?.let {
            put("sms", buildJsonObject {
                put("used", it.used); put("total", it.total); put("remaining", it.remaining)
            })
        }
        cloudCard?.let { cloud ->
            put("cloud_card", buildJsonObject {
                put("provider_id", cloud.providerId)
                put("provider_name", cloud.providerName)
                put("card_no", cloud.cardNoMasked)
                putNullable("iccid", cloud.iccid)
                putNullable("series_name", cloud.seriesName)
                putNullable("expires_at", cloud.expiresAt)
                putNullable("days_left", cloud.daysLeft)
                putNullable("status_text", cloud.statusText)
                putNullable("real_name_text", cloud.realNameText)
                putNullable("real_used_gb", cloud.realUsedGb)
                putNullable("real_total_gb", cloud.realTotalGb)
                putNullable("real_remaining_gb", cloud.realRemainingGb)
                putNullable("refreshed_at", cloud.refreshedAt)
                put("orders", buildJsonArray {
                    cloud.orders.forEach { order ->
                        add(buildJsonObject {
                            put("name", order.name)
                            putNullable("amount_yuan", order.amountYuan)
                            putNullable("paid_yuan", order.paidYuan)
                            put("status_text", order.statusText)
                            putNullable("pay_type_text", order.payTypeText)
                            putNullable("created_at", order.createdAt)
                            putNullable("effective_time", order.effectiveTime)
                            putNullable("expiration_time", order.expirationTime)
                        })
                    }
                })
            })
        }
        put("packages", buildJsonArray {
            packages.forEach { pkg ->
                add(buildJsonObject {
                    put("kind", when (pkg.kind) {
                        com.ufi_axis_core.controller.carrier.CarrierPackageKind.GENERAL -> "general"
                        com.ufi_axis_core.controller.carrier.CarrierPackageKind.DIRECTED -> "directed"
                        com.ufi_axis_core.controller.carrier.CarrierPackageKind.REGIONAL -> "regional"
                    })
                    put("name", pkg.name)
                    put("remaining_gb", pkg.remainingGb)
                    put("total_gb", pkg.totalGb)
                    putNullable("valid_from", pkg.validFrom)
                    putNullable("valid_to", pkg.validTo)
                })
            }
        })
    }

    private fun JsonObjectBuilder.putNullable(key: String, value: String?) {
        if (value == null) put(key, JsonNull) else put(key, value)
    }

    private fun JsonObjectBuilder.putNullable(key: String, value: Number?) {
        if (value == null) put(key, JsonNull) else put(key, value)
    }
}
