package com.ufi_axis_core.api.routes

import com.ufi_axis_core.api.ResponseHelper.toJsonElement
import com.ufi_axis_core.contract.ConfigLimits
import com.ufi_axis_core.contract.ErrorCode
import com.ufi_axis_core.lib_api.BuildConfig
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

                /** 密钥类字符串：空串与脱敏值都拒绝（脱敏回写会覆盖真实密钥）。 */
                fun secretField(field: String, apply: (String) -> Unit) {
                    val v = (body[field] as? JsonPrimitive)?.contentOrNull ?: return
                    when {
                        v.isBlank() -> reject(field, ErrorCode.BLANK_VALUE)
                        isMaskedValue(v) -> reject(field, ErrorCode.MASKED_VALUE)
                        else -> {
                            apply(v)
                            updated.add(field)
                        }
                    }
                }

                /** 非空字符串字段。 */
                fun textField(field: String, apply: (String) -> Unit) {
                    val v = (body[field] as? JsonPrimitive)?.contentOrNull ?: return
                    if (v.isBlank()) {
                        reject(field, ErrorCode.BLANK_VALUE)
                    } else {
                        apply(v)
                        updated.add(field)
                    }
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


                secretField("goform_password") { settings.goformPassword = it }

                intField("port", ConfigLimits.PORT) { settings.port = it }
                intField("goform_port", ConfigLimits.GOFORM_PORT) { settings.goformPort = it }

                textField("goform_ip") { settings.goformIp = it }
                textField("update_url") { settings.updateUrl = it }

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
                body["update_mirror_base"]?.jsonPrimitive?.contentOrNull?.let {
                    settings.updateMirrorBase = it
                    updated.add("update_mirror_base")
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
             * **需要配对密码**（设备已设过密码时）。原实现只要求「已配对身份」，
             * 而它调的 `resetAll()` 当时会清掉密码哈希 —— 一台借出去用过、配对记录还没删的
             * 旧手机就能远程把设备打回可接管的出厂态。同一份代码里语义更轻的 `unpair` /
             * `removeDevice` 都要密码，这里没有理由更松。
             *
             * `resetAll()` 现在也不再清除身份与凭据键（见 `AppSettings.PRESERVED_ON_RESET`），
             * 两道改动是互补的：一道防越权触发，一道限制影响范围。
             */
            post("/reset") {
                val body = runCatching { call.receiveJsonObject() }.getOrNull()
                val password = body?.get("password")?.jsonPrimitive?.contentOrNull ?: ""
                if (settings.devicePasswordSet && !settings.verifyDevicePassword(password)) {
                    call.respondFail(
                        HttpStatusCode.Unauthorized,
                        ErrorCode.INVALID_PASSWORD,
                        "配对密码错误，无法恢复默认配置"
                    )
                    return@post
                }
                settings.resetAll()
                call.respond(toJsonElement(mapOf(
                    "success" to true,
                    "message" to "已恢复默认配置（配对信息与配对密码保持不变），需重启服务生效"
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
     * 当作「已设置」（ToolsModule:1080）；「不许回写脱敏值」的识别在 core 自己的
     * [isMaskedValue]（含 3 个以上连续星号），所以固定掩码必须保留 `***` 特征。
     */
    private fun maskSecret(secret: String): String =
        if (secret.isEmpty()) "" else MASK_PLACEHOLDER

    /**
     * 检测值是否为脱敏占位符（含 3+ 连续星号），
     * 防止前端将 GET 返回的脱敏值回写覆盖真实密钥
     */
    private fun isMaskedValue(value: String): Boolean =
        value.contains("***")

    private companion object {
        /** 定长脱敏占位符。含 `***`，因此回写时会被 [isMaskedValue] 拦下。 */
        const val MASK_PLACEHOLDER = "********"
    }
}


