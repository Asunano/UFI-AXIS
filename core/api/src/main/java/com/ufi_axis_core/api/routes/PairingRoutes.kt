// F31：PairingRoutes 免 Bearer 鉴权为设计意图（设备首次配对尚无 Token，必须开放）。勿改为需 Token。
// 但破坏性操作（unpair / change-password）必须校验设备密码——免 Token ≠ 免鉴权。
package com.ufi_axis_core.api.routes

import android.os.Build
import android.os.Environment
import com.ufi_axis_core.api.ResponseHelper.ok
import com.ufi_axis_core.api.ResponseHelper.toJsonElement
import com.ufi_axis_core.api.pairing.PairingManager
import com.ufi_axis_core.api.pairing.PairingManager.ChangePwdResult
import com.ufi_axis_core.api.pairing.PairingManager.PairingConfirmResult
import com.ufi_axis_core.contract.ErrorCode
import com.ufi_axis_core.util.ShellExecutor
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap

/**
 * 判定 IP 是否属于本地子网（回环 / 站点本地 RFC1918 / 链路本地）。
 * 独立为文件级私有函数，使其可在主构造器默认参数（subnetChecker）的作用域中解析。
 */
private fun isLocalSubnet(ip: String): Boolean {
    return runCatching {
        val addr = InetAddress.getByName(ip)
        addr.isLoopbackAddress || addr.isSiteLocalAddress || addr.isLinkLocalAddress
    }.getOrDefault(false)
}

/**
 * 配对模式端点（免鉴权，且独立于 /api 鉴权块）。
 *
 * - GET  /pairing/info              返回 device_id / device_name / pairing_code / storage_status / has_default_password
 *                                   （pairing_code 仅在设备未初始化时下发，见 PairingManager.infoPayload）
 * - POST /pairing/challenge         无请求体 -> {challenge}（一次性 nonce，2 分钟有效）
 * - POST /pairing/confirm           {pairing_code, device_pubkey, challenge, signature, password, device_name}
 *                                   -> {token, fingerprint}
 *                                   首次（passwordSet=false）将 password 落库=设置密码；之后校验密码。
 * - POST /pairing/unpair            {password} -> 清空配对态并重新进入配对模式
 *                                   2026-09-02：补密码校验。此前仅做同网段判断，同一 WiFi 下
 *                                   任何人都能清空全部配对（破坏性 + 未鉴权）。
 * - POST /pairing/change-password   {old_password, new_password} -> 200/400/401/429（旧密码即管理权限门禁）
 *
 * ## 三步握手（2026-08-28，严格设备独立性）
 * 1. `POST /pairing/challenge` 取 nonce；
 * 2. 客户端用**本地不可导出私钥**（App=Keystore / Web=WebCrypto extractable:false）对 nonce 签名；
 * 3. `POST /pairing/confirm` 带 `device_pubkey`(SPKI DER base64) + `challenge` + `signature`。
 *
 * 设备指纹由服务端据公钥计算并在响应里回显（`fingerprint`），**不接受客户端自报指纹**——
 * 这是「Web 端所有浏览器共用一个固定指纹导致配对上限失效」的根因修复。
 *
 * 防护：
 * 1. 速率限制：每 IP 每 [rateLimitMs]（默认 500ms）仅放行一次（info/challenge/confirm/change-password 独立计数器）；
 * 2. 子网限制：仅响应本网段（RFC1918 / 回环 / 链路本地）地址；
 * 3. 密码错误锁定：每 IP 15min 5 次 + 全局 15min 20 次失败 → 429 PASSWORD_LOCKED（PairingManager 内内存计数）。
 */
class PairingRoutes(
    private val ctx: RouteContext,
    private val pairingManager: PairingManager,
    /**
     * 可测试性 seam：返回设备是否具备 root 权限。默认走 [ShellExecutor.hasRootAccess]（suspend）。
     * 测试中以确定性值注入，剥离 Android 运行时依赖，使路由可在纯 JVM 运行。
     */
    private val rootChecker: suspend () -> Boolean = {
        runCatching { ShellExecutor.hasRootAccess() }.getOrDefault(false)
    },
    /**
     * 可测试性 seam：判定请求来源是否属于本地子网。默认走 [isLocalSubnet]。
     * 测试中以确定性值注入，避免依赖测试宿主上报的 remoteAddress。
     */
    private val subnetChecker: (String) -> Boolean = { ip -> isLocalSubnet(ip) },
    /**
     * 可测试性 seam：解析设备名（Settings.Global.DEVICE_NAME → deviceId 回退）。
     * 默认委托 [PairingManager.resolveDeviceName]（读 Settings.Global）；测试注入固定值。
     */
    private val deviceNameProvider: () -> String = { pairingManager.resolveDeviceName() },
    /**
     * 可测试性 seam：速率限制窗口（毫秒）。默认 500ms；测试可传 0 关闭以验证密码锁定逻辑。
     * 2026-08-20：从 10s 降至 3s —— 局域网场景无需激进防刷，10s 窗口在正常
     * 登录流程（info→返回→重试 confirm）中极易误伤，前端频繁出现"操作过于频繁"。
     * 2026-08-22：3s → 1s —— 局域网高频操作仍会偶发 429（登录重试/多端同时配对）。
     * 2026-08-25：1s → 500ms —— 用户反馈 429 过于严格，放宽至 500ms（每秒 2 次）。
     * 暴力破解防护不依赖此窗口：密码错误锁定（15min 5 次）+ 子网限制已足够，
     * 此处仅拦截明显的脚本级洪泛。
     */
    private val rateLimitMs: Long = 500L
) {

    private val settings get() = ctx.settings

    // IP -> 上次放行时间戳（速率限制）。info / confirm / change-password 各持独立计数器，
    // 避免"先 GET /pairing/info 再 POST /pairing/confirm"的合法握手被同一计数器误杀为 429。
    private val infoLastAccess = ConcurrentHashMap<String, Long>()
    private val challengeLastAccess = ConcurrentHashMap<String, Long>()
    private val confirmLastAccess = ConcurrentHashMap<String, Long>()
    private val changePasswordLastAccess = ConcurrentHashMap<String, Long>()
    private val unpairLastAccess = ConcurrentHashMap<String, Long>()

    /**
     * 清除指定 IP 的速率限制记录。
     * 在 unpair 操作**密码校验成功后**调用，允许客户端立即重新配对而无需等待冷却。
     */
    fun clearRateLimits(ip: String) {
        infoLastAccess.remove(ip)
        challengeLastAccess.remove(ip)
        confirmLastAccess.remove(ip)
        changePasswordLastAccess.remove(ip)
        unpairLastAccess.remove(ip)
    }

    fun register(route: Route) {
        route.apply {
            get("/info") { handleInfo(call) }
            post("/challenge") { handleChallenge(call) }
            post("/confirm") { handleConfirm(call) }
            post("/unpair") { handleUnpair(call) }
            post("/change-password") { handleChangePassword(call) }
        }
    }

    @Serializable
    private data class ConfirmBody(
        val pairing_code: String = "",
        /** 设备身份公钥：X.509 SPKI DER 的 base64（标准或 url 字母表均可）。服务端据此算指纹。 */
        val device_pubkey: String = "",
        /** `POST /pairing/challenge` 下发的一次性挑战原文。 */
        val challenge: String = "",
        /** 用设备私钥对 [challenge] 原文做的 ECDSA-SHA256 签名（DER 或 raw r||s，base64/base64url）。 */
        val signature: String = "",
        val password: String? = null,
        val device_name: String? = null,
        val goform_ip: String? = null,
        val goform_port: Int? = null,
        val goform_password: String? = null,
        /**
         * 硬件级稳定标识（App 侧 ANDROID_ID 派生；Web 侧不上报）。
         * **仅**用于合并「同一台设备换了密钥后」产生的重复记录，防止设备列表无限增长，
         * 不参与任何安全判定（它是可伪造的明文）。
         */
        val device_hwid: String? = null
    )

    @Serializable
    private data class ChangePasswordBody(
        val old_password: String? = null,
        val new_password: String? = null,
        val goform_ip: String? = null,
        val goform_port: Int? = null,
        val goform_password: String? = null
    )

    @Serializable
    private data class UnpairBody(
        /** 设备密码：解除全部配对是破坏性操作，必须证明操作者是设备主人。 */
        val password: String? = null
    )

    private fun clientIp(call: ApplicationCall): String = call.request.local.remoteAddress

    private fun isExternalStorageManager(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching { Environment.isExternalStorageManager() }.getOrDefault(false)
        } else {
            true
        }
    }

    private fun rateLimited(ip: String, store: ConcurrentHashMap<String, Long>): Boolean {
        val now = System.currentTimeMillis()
        val last = store[ip]
        if (last != null && now - last < rateLimitMs) return true
        store[ip] = now
        return false
    }

    /**
     * 全局失败节流：仅在**近期密码失败次数异常**时才产生延时，正常情况恒为 0。
     * 换 IP 能绕开每 IP 硬锁，但绕不开这个全局计数；用延时而非拒绝，
     * 保证设备主人永远不会被锁在门外（见 [PasswordAttemptLimiter] 类文档）。
     */
    private suspend fun throttlePassword() {
        val wait = pairingManager.passwordThrottleDelayMs()
        if (wait > 0) delay(wait)
    }

    private suspend fun handleInfo(call: ApplicationCall) {
        val ip = clientIp(call)
        if (!subnetChecker(ip)) {
            call.respondFail(HttpStatusCode.Forbidden, ErrorCode.FORBIDDEN, "Forbidden: not on local subnet")
            return
        }
        if (rateLimited(ip, infoLastAccess)) {
            call.respondFail(HttpStatusCode.TooManyRequests, ErrorCode.TOO_MANY_REQUESTS, "Too many requests, retry later")
            return
        }
        val hasRoot = try { rootChecker() } catch (e: Exception) { false }
        val isEsm = isExternalStorageManager()
        call.respond(toJsonElement(pairingManager.infoPayload(deviceNameProvider(), hasRoot, isEsm)))
    }

    /**
     * POST /pairing/challenge —— 下发一次性挑战。
     *
     * 免鉴权是必然的（客户端此刻还没有凭据），因此挑战本身不能是任何秘密：
     * 它只是随机数，攻击者拿到也只能证明"我持有我自己的私钥"，而后续 confirm 仍要过
     * 配对码/设备密码/配额三道门。挑战的唯一作用是把「持有私钥」变成可验证的事实。
     */
    private suspend fun handleChallenge(call: ApplicationCall) {
        val ip = clientIp(call)
        if (!subnetChecker(ip)) {
            call.respondFail(HttpStatusCode.Forbidden, ErrorCode.FORBIDDEN, "Forbidden: not on local subnet")
            return
        }
        if (rateLimited(ip, challengeLastAccess)) {
            call.respondFail(HttpStatusCode.TooManyRequests, ErrorCode.TOO_MANY_REQUESTS, "Too many requests, retry later")
            return
        }
        call.respond(toJsonElement(mapOf("challenge" to pairingManager.issueChallenge())))
    }

    private suspend fun handleConfirm(call: ApplicationCall) {
        val ip = clientIp(call)
        if (!subnetChecker(ip)) {
            call.respondFail(HttpStatusCode.Forbidden, ErrorCode.FORBIDDEN, "Forbidden: not on local subnet")
            return
        }
        if (rateLimited(ip, confirmLastAccess)) {
            call.respondFail(HttpStatusCode.TooManyRequests, ErrorCode.TOO_MANY_REQUESTS, "Too many requests, retry later")
            return
        }
        val body = try { call.receive<ConfirmBody>() } catch (e: Exception) { null }
        val code = body?.pairing_code?.trim().orEmpty()
        val pubKey = body?.device_pubkey?.trim().orEmpty()
        val challenge = body?.challenge?.trim().orEmpty()
        val signature = body?.signature?.trim().orEmpty()
        // 配对码必填仅针对全新设备（devicePasswordSet=false，首次配对=初始化语义）；
        // 已配置设备允许空码（一次性码已消耗且服务未重启时码为空）→ 凭设备密码登录（PairingManager 内判定）。
        if (code.isEmpty() && !settings.devicePasswordSet) {
            call.respondFail(HttpStatusCode.BadRequest, ErrorCode.BAD_REQUEST, "pairing_code required")
            return
        }
        if (pubKey.isEmpty() || challenge.isEmpty() || signature.isEmpty()) {
            call.respondFail(
                HttpStatusCode.BadRequest,
                ErrorCode.BAD_REQUEST,
                "device_pubkey, challenge and signature required"
            )
            return
        }
        throttlePassword()
        val result = pairingManager.confirm(
            code = code,
            pubKeySpki = pubKey,
            challenge = challenge,
            signature = signature,
            password = body?.password,
            deviceName = body?.device_name,
            ip = ip,
            goformIp = body?.goform_ip,
            goformPort = body?.goform_port,
            goformPassword = body?.goform_password,
            hwId = body?.device_hwid
        )
        when (result) {
            is PairingConfirmResult.Success -> {
                // 若初次配对同时提交了 Goform 密码，热更新运行中的客户端（IP/端口需重启生效）
                body?.goform_password?.takeIf { it.isNotBlank() }?.let { pw ->
                    runCatching { ctx.goformClient.updateGoformPassword(pw) }
                }
                // fingerprint 回显：客户端不再自己算指纹，配对界面据此高亮"本机"。
                call.respond(toJsonElement(mapOf("token" to result.token, "fingerprint" to result.fingerprint)))
            }
            is PairingConfirmResult.InvalidCode -> {
                call.respondFail(HttpStatusCode.Unauthorized, ErrorCode.INVALID_CODE, "Invalid or expired pairing code")
            }
            is PairingConfirmResult.AlreadyPaired -> {
                call.respondFail(HttpStatusCode.Conflict, ErrorCode.ALREADY_PAIRED, "Device already paired")
            }
            is PairingConfirmResult.InvalidPassword -> {
                call.respondFail(HttpStatusCode.Unauthorized, ErrorCode.INVALID_PASSWORD, "Invalid device password")
            }
            is PairingConfirmResult.PasswordLocked -> {
                call.respondFail(HttpStatusCode.TooManyRequests, ErrorCode.PASSWORD_LOCKED, "Too many failed password attempts, retry later")
            }
            is PairingConfirmResult.PasswordRequired -> {
                call.respondFail(HttpStatusCode.BadRequest, ErrorCode.PASSWORD_REQUIRED, "Device password required")
            }
            is PairingConfirmResult.InvalidGoformConfig -> {
                call.respondFail(HttpStatusCode.BadRequest, ErrorCode.INVALID_GOFORM_CONFIG, "Goform 后台配置无效（IP/端口/密码格式错误）")
            }
            is PairingConfirmResult.InvalidDeviceKey -> {
                call.respondFail(
                    HttpStatusCode.Unauthorized,
                    ErrorCode.INVALID_DEVICE_KEY,
                    "device_pubkey 无效或挑战签名校验失败"
                )
            }
            is PairingConfirmResult.InvalidChallenge -> {
                // 挑战一次性且 2 分钟过期：客户端应重新 POST /pairing/challenge 后重试。
                call.respondFail(
                    HttpStatusCode.Unauthorized,
                    ErrorCode.INVALID_CHALLENGE,
                    "挑战不存在或已过期，请重新获取"
                )
            }
        }
    }

    private suspend fun handleChangePassword(call: ApplicationCall) {
        val ip = clientIp(call)
        if (!subnetChecker(ip)) {
            call.respondFail(HttpStatusCode.Forbidden, ErrorCode.FORBIDDEN, "Forbidden: not on local subnet")
            return
        }
        if (rateLimited(ip, changePasswordLastAccess)) {
            call.respondFail(HttpStatusCode.TooManyRequests, ErrorCode.TOO_MANY_REQUESTS, "Too many requests, retry later")
            return
        }
        val body = try { call.receive<ChangePasswordBody>() } catch (e: Exception) { null }
        val oldPw = body?.old_password.orEmpty()
        val newPw = body?.new_password.orEmpty()
        throttlePassword()
        when (val result = pairingManager.changePassword(oldPw, newPw, ip, body?.goform_ip, body?.goform_port, body?.goform_password)) {
            is ChangePwdResult.Success -> {
                // 若同时提交了 Goform 密码，热更新运行中的客户端（IP/端口需重启生效）
                body?.goform_password?.takeIf { it.isNotBlank() }?.let { pw ->
                    runCatching { ctx.goformClient.updateGoformPassword(pw) }
                }
                call.respond(ok("has_default_password" to result.hasDefaultPassword))
            }
            is ChangePwdResult.InvalidNewPassword -> {
                call.respondFail(HttpStatusCode.BadRequest, ErrorCode.INVALID_NEW_PASSWORD, "new_password must be 4-64 characters")
            }
            is ChangePwdResult.WrongOldPassword -> {
                call.respondFail(
                    HttpStatusCode.Unauthorized,
                    ErrorCode.WRONG_OLD_PASSWORD,
                    "Wrong old password",
                    mapOf("has_default_password" to settings.hasDefaultPassword)
                )
            }
            is ChangePwdResult.PasswordLocked -> {
                call.respondFail(HttpStatusCode.TooManyRequests, ErrorCode.PASSWORD_LOCKED, "Too many failed password attempts, retry later")
            }
            is ChangePwdResult.InvalidGoformConfig -> {
                call.respondFail(HttpStatusCode.BadRequest, ErrorCode.INVALID_GOFORM_CONFIG, "Goform 后台配置无效（IP/端口/密码格式错误）")
            }
        }
    }

    private suspend fun handleUnpair(call: ApplicationCall) {
        val ip = clientIp(call)
        if (!subnetChecker(ip)) {
            call.respondFail(HttpStatusCode.Forbidden, ErrorCode.FORBIDDEN, "Forbidden: not on local subnet")
            return
        }
        if (rateLimited(ip, unpairLastAccess)) {
            call.respondFail(HttpStatusCode.TooManyRequests, ErrorCode.TOO_MANY_REQUESTS, "Too many requests, retry later")
            return
        }
        val body = try { call.receive<UnpairBody>() } catch (e: Exception) { null }
        throttlePassword()
        when (pairingManager.unpairAllWithPassword(body?.password, ip)) {
            is PairingManager.UnpairResult.Success -> {
                // 仅在密码校验通过后才清速率限制：让主人能立即重新配对。
                clearRateLimits(ip)
                call.respond(ok("message" to "Device unpaired, re-entered pairing mode"))
            }
            is PairingManager.UnpairResult.MissingPassword -> {
                call.respondFail(HttpStatusCode.Unauthorized, ErrorCode.MISSING_PASSWORD, "Password required")
            }
            is PairingManager.UnpairResult.InvalidPassword -> {
                call.respondFail(HttpStatusCode.Unauthorized, ErrorCode.INVALID_PASSWORD, "Invalid device password")
            }
            is PairingManager.UnpairResult.PasswordLocked -> {
                call.respondFail(HttpStatusCode.TooManyRequests, ErrorCode.PASSWORD_LOCKED, "Too many failed password attempts, retry later")
            }
        }
    }
}
