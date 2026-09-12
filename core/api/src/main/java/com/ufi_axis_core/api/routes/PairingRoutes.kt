// F31：PairingRoutes 免 Bearer 鉴权为设计意图（设备首次配对尚无 Token，必须开放）。勿改为需 Token。
// 但破坏性操作（unpair / change-password）必须校验配对密码——免 Token ≠ 免鉴权。
// 配对密码在存储层的符号名沿用 `devicePassword*`（`settings.devicePasswordSet` 等）：
// 那是持久化 key 的一部分，改名会让存量设备读不出已设置的密码，所以只统一注释口径。
package com.ufi_axis_core.api.routes

import android.os.Build
import android.os.Environment
import com.ufi_axis_core.api.ResponseHelper.ok
import com.ufi_axis_core.api.ResponseHelper.toJsonElement
import com.ufi_axis_core.api.pairing.PairingManager
import com.ufi_axis_core.api.pairing.PairingManager.ChangePwdResult
import com.ufi_axis_core.api.pairing.PairingManager.PairingConfirmResult
import com.ufi_axis_core.contract.ErrorCode
import com.ufi_axis_core.util.AppLogger
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
 * 请求是否从隧道（frpc / cloudflared）转发进来。
 *
 * 判据由两个条件**同时**成立构成：
 * 1. [tunnelActive]：设备上确实有隧道进程在运行。没有隧道时不存在隧道来源，这一条把下面
 *    那个不精确的地址判据整体关掉；
 * 2. 服务端侧的接收地址是回环地址。局域网客户端连的是设备的 LAN IP，而隧道是在设备本机
 *    连 `127.0.0.1:8088`（见 app 侧 frpc 模板的 localIP/localPort）。
 *
 * 不能拿 `remoteAddress` 判：隧道转发之后它恒为 127.0.0.1，正好满足 [isLocalSubnet]，
 * 这是「同网段限制」未能拦住公网来源的原因。
 *
 * 为什么必须加第 1 条（2026-09-11 修）：本机直连同样落在回环地址上 —— app 与 core 装在
 * 同一台设备时，或在设备上用 `http://127.0.0.1:8088` 打开 Web 面板时。只看地址会把这些
 * 局域网/本机操作误判为公网来源，表现为「在局域网内也提示远程连接」。
 *
 * 剩余的不精确之处：隧道正在运行期间，本机直连仍会被判为隧道来源。这个方向是保守的
 * （多拦不少拦），且此时确实无法从连接本身区分两者，故保留。
 */
internal fun isTunnelOrigin(localAddress: String, tunnelActive: Boolean): Boolean =
    tunnelActive && runCatching {
        InetAddress.getByName(localAddress).isLoopbackAddress
    }.getOrDefault(false)


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
 * 4. 隧道来源降级（见 [isTunnelOrigin]）——第 2 条拦不住隧道，这条才是公网面的门：
 *    - `/info`：只回 `device_name` + `has_default_password`，不给 `pairing_code` / `device_id` / `storage_status`；
 *    - `/confirm`：设备**尚未设置配对密码**时拒绝（这条路径没有密码门，配对码对了就能拿走设备）；
 *    - `/change-password`：`hasDefaultPassword=true` 时拒绝（此时"旧密码"就是出厂默认值，等于没有门）；
 *    - `/unpair`：一律拒绝（破坏性最强，远端没有正当需求）；
 *    - `/challenge`：放行（只发随机 nonce，不含秘密，拦了远端就完全无法登录）。
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
     * 设备上是否有隧道进程在运行（见 [isTunnelOrigin] 的第 1 条判据）。
     * 默认 `true` 是保守取值：忘记接线时行为退回到本轮之前的「只看地址」，不会放松门禁。
     */
    private val tunnelActive: () -> Boolean = { true },
    /**
     * 可测试性 seam：判定请求是否从隧道转发进来（见 [isTunnelOrigin]）。
     * 测试宿主的 `local.localAddress` 不可控，所以以确定性值注入。
     */
    private val tunnelChecker: (ApplicationCall) -> Boolean = { call ->
        isTunnelOrigin(call.request.local.localAddress, tunnelActive())
    },
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
        /** 配对密码：解除全部配对是破坏性操作，必须证明操作者是设备主人。 */
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

    /**
     * 隧道来源不允许执行"初始化 / 破坏性"操作时的统一响应。
     * 文案直接说清"要在局域网内做"，否则用户只会看到一个没头没尾的 403。
     */
    private suspend fun respondLocalOnly(call: ApplicationCall, action: String) {
        AppLogger.w(TAG, "Rejected $action from tunnel origin (local=${call.request.local.localAddress})")
        call.respondFail(
            HttpStatusCode.Forbidden,
            ErrorCode.FORBIDDEN,
            "$action 只能在设备所在的局域网内完成"
        )
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
        val payload = pairingManager.infoPayload(deviceNameProvider(), hasRoot, isEsm)
        // 隧道来源只回登录页真正用得上的两项：pairing_code 是接管未初始化设备的全部条件，
        // device_id / storage_status 一个是设备指纹材料、一个是本机才能处理的授权状态，
        // 远端都没有用途。已初始化设备的 pairing_code 本来就是空串，密码登录不受影响。
        val safe = if (tunnelChecker(call)) {
            payload.filterKeys { it == "device_name" || it == "has_default_password" }
        } else {
            payload
        }
        call.respond(toJsonElement(safe))
    }

    /**
     * POST /pairing/challenge —— 下发一次性挑战。
     *
     * 免鉴权是必然的（客户端此刻还没有凭据），因此挑战本身不能是任何秘密：
     * 它只是随机数，攻击者拿到也只能证明"我持有我自己的私钥"，而后续 confirm 仍要过
     * 配对码/配对密码/配额三道门。挑战的唯一作用是把「持有私钥」变成可验证的事实。
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
        // 首次初始化（设备还没有配对密码）这条路径**没有密码门** —— 配对码对了就能拿走设备，
        // 所以只允许局域网。已初始化设备的密码登录不受影响（远端 Web 仍要能登录）。
        if (!settings.devicePasswordSet && tunnelChecker(call)) {
            respondLocalOnly(call, "首次配对")
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
        // 已配置设备允许空码（一次性码已消耗且服务未重启时码为空）→ 凭配对密码登录（PairingManager 内判定）。
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
                // 日志保留英文原文：便于与历史日志/`code` 对照排查，界面文案改中文后不再能靠 error 串搜日志。
                AppLogger.w(TAG, "Invalid device password (pairing/confirm) ip=$ip")
                call.respondFail(HttpStatusCode.Unauthorized, ErrorCode.INVALID_PASSWORD, "配对密码错误")
            }
            is PairingConfirmResult.PasswordLocked -> {
                call.respondFail(HttpStatusCode.TooManyRequests, ErrorCode.PASSWORD_LOCKED, "Too many failed password attempts, retry later")
            }
            is PairingConfirmResult.PasswordRequired -> {
                AppLogger.w(TAG, "Device password required (pairing/confirm) ip=$ip")
                call.respondFail(HttpStatusCode.BadRequest, ErrorCode.PASSWORD_REQUIRED, "请输入配对密码")
            }
            is PairingConfirmResult.InvalidGoformConfig -> {
                AppLogger.w(TAG, "Invalid goform config (pairing/confirm) ip=$ip")
                call.respondFail(HttpStatusCode.BadRequest, ErrorCode.INVALID_GOFORM_CONFIG, "设备后台配置无效（IP/端口/密码格式错误）")
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
        // hasDefaultPassword=true 时"旧密码"就是出厂默认值，这道门等于不存在 ——
        // 首次设置密码只允许局域网。之后的正常改密（要提供真旧密码）远端可用。
        if (settings.hasDefaultPassword && tunnelChecker(call)) {
            respondLocalOnly(call, "首次设置配对密码")
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
                AppLogger.w(TAG, "Invalid goform config (pairing/change-password) ip=$ip")
                call.respondFail(HttpStatusCode.BadRequest, ErrorCode.INVALID_GOFORM_CONFIG, "设备后台配置无效（IP/端口/密码格式错误）")
            }
        }
    }

    private suspend fun handleUnpair(call: ApplicationCall) {
        val ip = clientIp(call)
        if (!subnetChecker(ip)) {
            call.respondFail(HttpStatusCode.Forbidden, ErrorCode.FORBIDDEN, "Forbidden: not on local subnet")
            return
        }
        // 清空全部配对并重回配对模式是最强的破坏性操作，远端没有正当需求：
        // 一旦被执行，设备会重新进入"任何局域网客户端都能初始化"的状态。
        if (tunnelChecker(call)) {
            respondLocalOnly(call, "解除全部配对")
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
                AppLogger.w(TAG, "Password required (pairing/unpair) ip=$ip")
                call.respondFail(HttpStatusCode.Unauthorized, ErrorCode.MISSING_PASSWORD, "请输入配对密码")
            }
            is PairingManager.UnpairResult.InvalidPassword -> {
                AppLogger.w(TAG, "Invalid device password (pairing/unpair) ip=$ip")
                call.respondFail(HttpStatusCode.Unauthorized, ErrorCode.INVALID_PASSWORD, "配对密码错误")
            }
            is PairingManager.UnpairResult.PasswordLocked -> {
                call.respondFail(HttpStatusCode.TooManyRequests, ErrorCode.PASSWORD_LOCKED, "Too many failed password attempts, retry later")
            }
        }
    }

    private companion object {
        /** 与 PairingManager 共用同一日志标签，配对全流程可一次过滤出来。 */
        private const val TAG = "Pairing"
    }
}
