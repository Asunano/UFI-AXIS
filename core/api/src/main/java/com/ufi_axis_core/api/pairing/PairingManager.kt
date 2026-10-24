package com.ufi_axis_core.api.pairing

import com.ufi_axis_core.util.AppLogger
import com.ufi_axis_core.util.AppSettings
import com.ufi_axis_core.util.DeviceAuth
import com.ufi_axis_core.util.PairedDeviceRecord
import com.ufi_axis_core.util.PairedDeviceStore
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/**
 * 配对编排服务：收敛配对 / 密码 / 设备管理的一致性逻辑，路由层只做 HTTP 转换。
 *
 * ## 严格设备独立性（2026-08-28）
 * 设备身份不再是客户端自报的字符串，而是**设备本地不可导出私钥**对应的公钥：
 * - App：Android Keystore（EC P-256，StrongBox 优先）
 * - Web：WebCrypto `extractable=false` + IndexedDB
 *
 * 配对流程变成三步握手，杜绝"知道密码就能无限配对/冒充已配对指纹"：
 * 1. `POST /pairing/challenge` → 服务端下发一次性 nonce（[issueChallenge]）
 * 2. 客户端用私钥对 nonce 签名
 * 3. `POST /pairing/confirm` 带 `device_pubkey` + `signature` → 服务端验签（[confirm]）
 *
 * 指纹由服务端据公钥计算（`DeviceAuth.fingerprintOf`），**绝不采信客户端自报的指纹**；
 * 每台设备签发独占 token，仅存哈希，移除该设备记录即等于吊销其凭据。
 *
 * 职责：
 * - info / status 载荷组装（device_name 经 [deviceNameProvider] seam 注入）
 * - challenge 下发与消费
 * - confirm 验签 + 密码首设 / 校验 + 配对记录双写
 * - change-password / 设备列表 / 重命名 / 移除
 * - 密码错误计数（每 IP + 全局双层，见 [PasswordAttemptLimiter]）
 *
 * 密码明文与 token 明文永不落库/回显；错误响应码沿用 `{error, code}` 格式。
 */
class PairingManager(
    private val settings: AppSettings,
    private val store: PairedDeviceStore,
    private val deviceNameProvider: () -> String = {
        store.systemDeviceName().ifBlank { settings.deviceId }
    },
    private val passwordAttemptLimiter: PasswordAttemptLimiter = PasswordAttemptLimiter(),
    /**
     * 配对设备黑名单（2026-10-15）。可空是为了**不破坏既有构造点**（大量测试直接 new
     * `PairingManager(settings, store)`）：为空时等同于"没有黑名单"，行为与改动前一致。
     * 生产装配在 `ComponentFactory` 里传入真实实例 —— 那里漏传等于功能静默失效，
     * 所以有一条单测守着"装配层必须传"（见 `PairingBlacklistWiringTest`）。
     */
    private val blacklist: PairedDeviceBlacklistStore? = null,
    /**
     * 配对 / 登录失败的**双键指数退避**（2026-10-15，见 [PairingAttemptLimiter]）。
     *
     * 与 [blacklist] 同样可空：既有构造点（大量测试）不受影响，为空时等同于"没有退避"。
     * 生产装配必须传 —— 漏传等于"换 IP 就能无限爆破"，由装配守卫测试守着。
     */
    private val attemptLimiter: PairingAttemptLimiter? = null,
    /**
     * 黑名单命中后的冷却（2026-10-15，见 [PairingBlockCooldown]）。
     *
     * 与 [blacklist] 分开传：冷却管的是"解除拉黑之后那段窗口"，两份状态互不依赖 ——
     * 解除拉黑不会解除冷却（否则"解除"这个动作本身就成了可利用的窗口）。
     */
    private val blockCooldown: PairingBlockCooldown? = null,
    /**
     * 新设备待批准请求（2026-10-15，见 [PairedDeviceApprovalStore]）。
     *
     * 可空同前两个：既有构造点不受影响，为空时 `pairingApprovalRequired` 打开也不会生效
     *（装配层必须传 —— 由装配守卫测试守着）。
     */
    private val approvalStore: PairedDeviceApprovalStore? = null
) {

    private val random = SecureRandom()

    /** 未消费的配对挑战：nonce → 下发时刻。一次性使用，过期自动清理。 */
    private val challenges = ConcurrentHashMap<String, Long>()

    // 2026-10-05 R1-9 修复：两阶段消费——先 claim（原子 remove 出 challenges，防重放不变），
    // 校验全部通过后再 commit 彻底烧毁；任一校验失败则 release 回灌（保留原 issuedAt，
    // 总 TTL 不延长）。避免「输错一次密码/密码锁定就烧掉一次性挑战」导致配对反复失败。
    private val pendingClaims = ConcurrentHashMap<String, Long>()

    private fun claimChallenge(nonce: String): Boolean {
        if (nonce.isBlank()) return false
        val issuedAt = challenges.remove(nonce) ?: return false
        val now = System.currentTimeMillis()
        if (now - issuedAt > CHALLENGE_TTL_MS) return false
        pendingClaims[nonce] = issuedAt
        return true
    }

    private fun commitChallenge(nonce: String) {
        pendingClaims.remove(nonce)
    }

    private fun releaseChallenge(nonce: String) {
        val issuedAt = pendingClaims.remove(nonce) ?: return
        // 未过 TTL 才回灌；issuedAt 用原值，不重置过期起点
        if (System.currentTimeMillis() - issuedAt <= CHALLENGE_TTL_MS) {
            challenges[nonce] = issuedAt
        }
    }

    /** 解析当前设备名（Settings.Global.DEVICE_NAME → deviceId 回退）。 */
    fun resolveDeviceName(): String = deviceNameProvider()

    /**
     * 校验密码前应等待的毫秒数（全局失败节流，见 [PasswordAttemptLimiter]）。
     *
     * 由路由层（suspend 上下文）`delay` 掉，而不是在本类里 `Thread.sleep`——
     * 阻塞 Ktor 工作线程会连带拖慢无关请求。正常情况下恒为 0。
     */
    fun passwordThrottleDelayMs(): Long = passwordAttemptLimiter.throttleDelayMs()


    /**
     * 下发一次性配对挑战。
     *
     * 挑战的作用是把「持有私钥」变成可验证的事实：没有它，客户端只需上报一个公钥就能声称
     * 身份，攻击者可以拿别人的公钥来占位（虽然之后无法用其签名通过鉴权，但足以恶意占满配额）。
     *
     * @return 32 字节随机数的 base64url
     */
    fun issueChallenge(): String {
        pruneChallenges()
        val bytes = ByteArray(CHALLENGE_BYTES)
        random.nextBytes(bytes)
        val nonce = DeviceAuth.base64UrlNoPad(bytes)
        challenges[nonce] = System.currentTimeMillis()
        return nonce
    }

    /**
     * 消费挑战：存在且未过期才返回 true，且**同一挑战只能成功一次**。
     * 用 `remove` 的返回值做原子判定，避免并发下同一挑战被两次使用。
     */
    private fun consumeChallenge(nonce: String): Boolean {
        if (nonce.isBlank()) return false
        val issuedAt = challenges.remove(nonce) ?: return false
        return System.currentTimeMillis() - issuedAt <= CHALLENGE_TTL_MS
    }

    private fun pruneChallenges() {
        if (challenges.size < CHALLENGE_MAX) return
        val now = System.currentTimeMillis()
        challenges.entries.removeAll { now - it.value > CHALLENGE_TTL_MS }
        if (challenges.size >= CHALLENGE_MAX) {
            AppLogger.w(TAG, "配对挑战缓存超限（${challenges.size}），整体清空")
            challenges.clear()
        }
    }


    /**
     * GET /pairing/info 载荷。
     * @param deviceName 路由经 deviceNameProvider seam 计算后传入（可测试性）。
     * @param hasRoot / @param isExternalStorageManager 由路由 rootChecker/Environment seam 注入。
     *
     * `pairing_code` 只在**设备尚未初始化**（`hasDefaultPassword=true`）时下发：
     * 此时配对码是完成初始化的必要条件，客户端必须能自动取到。一旦配对密码已设置，
     * 后续客户端走「密码登录」分支（见 [confirm] 第 1 步的 `loginByPassword`），
     * 不再需要配对码——继续对免鉴权请求回显它只会白送给扫描者一个凭据。
     *
     * 注：这里说的配对密码，符号名沿用 `devicePassword*`（`AppSettings.devicePasswordSet` /
     * `setDevicePassword` / `verifyDevicePassword`）——那是持久化 key 的一部分，
     * 改名会让存量设备读不出已设置的密码，所以只统一注释口径。
     */
    fun infoPayload(
        deviceName: String,
        hasRoot: Boolean,
        isExternalStorageManager: Boolean
    ): Map<String, Any?> = mapOf(
        "device_id" to settings.deviceId,
        "device_name" to deviceName,
        "pairing_code" to if (settings.hasDefaultPassword) settings.pairingCode else "",
        // 2026-10-22：已配对记录数。App 的"这台手机是唯一已配对设备 → 清除旧配对"自救入口
        // 需要它才能区分「设备上真有多台设备」与「只剩一条本机的陈旧记录」。
        // 只回数量，不回指纹 —— 局域网端点，数量本身不构成可利用信息。
        "paired_count" to settings.pairedFingerprints.size,
        "storage_status" to mapOf(
            "hasRoot" to hasRoot,
            "isExternalStorageManager" to isExternalStorageManager
        ),
        "has_default_password" to settings.hasDefaultPassword,
        "expires_at" to null
    )

    /**
     * 配对确认 / 密码登录（含密码校验与记录写回）。
     *
     * 两种模式（2026-08-22 语义拆分）：
     * - 配对（首次初始化）：设备从未设置密码（devicePasswordSet=false）→ 严格校验一次性
     *   配对码；请求密码即被落库为配对密码（“设置密码”语义），可同时写入 Goform 配置。
     * - 登录（普通连接）：设备已初始化（devicePasswordSet=true）→ 凭配对密码即可连接；
     *   配对码已消耗或未携带时不再阻断（[InvalidCode]），密码正确即绑定指纹并下发 token。
     *   已配对指纹免码刷新；上限检查仍然生效。
     *
     * 校验顺序（任何一步失败都不产生副作用）：
     * 0. **设备身份**：公钥可解析 → 服务端算指纹；挑战存在且未过期（一次性消费）；
     *    用该公钥验签挑战。三者缺一即 [PairingConfirmResult.InvalidDeviceKey]。
     * 1. 配对码（只读校验，不落库）
     * 2. 上限 / 重复（已配对指纹允许刷新）
     * 3. 密码：首次将请求密码直接落库=“设置密码”；之后校验失败 → 401 INVALID_PASSWORD；
     *    每 IP 15min 5 次 / 全局 15min 20 次失败 → 429 PASSWORD_LOCKED
     * 3.5 硬件去重：按 [hwId] 合并“同一台设备清数据/重装后换指纹”产生的重复记录
     *    （见 [consolidateDuplicateHw]）
     * 4. 成功 → 签发该设备独占 token（仅存哈希）+ 写 AppSettings 指纹 + PairedDeviceStore 记录
     *
     * @param pubKeySpki 设备身份公钥，X.509 SPKI DER 的 base64。指纹由此计算，
     *   **不接受客户端自报指纹**。
     * @param challenge [issueChallenge] 下发的一次性挑战原文。
     * @param signature 用设备私钥对 [challenge] **原文**（不做额外拼装）的 ECDSA-SHA256 签名，
     *   DER 或 raw r||s 均可（`DeviceAuth` 会归一化）。
     * @param hwId 客户端上报的硬件级稳定标识（`device_hwid`）。**仅用于合并重复记录，
     *   不参与任何安全判定**——它是可伪造的明文，2026-08-28 已移除它对配额检查的旁路。
     */
    fun confirm(
        code: String,
        pubKeySpki: String,
        challenge: String,
        signature: String,
        password: String?,
        deviceName: String?,
        ip: String,
        goformIp: String? = null,
        goformPort: Int? = null,
        goformPassword: String? = null,
        hwId: String? = null,
        /** 批准流程的会话随机数（Web 端生成，见 `request-approval`）。 */
        sid: String? = null,
        /** 机主从外部通知里读到的配对码；回传正确即视为已批准。 */
        approvalCode: String? = null
    ): PairingConfirmResult {
        val trimmedCode = code.trim()

        // 0. 设备身份验证：公钥 → 指纹（服务端计算），挑战一次性消费，再验签。
        //    顺序很重要：先算指纹（廉价）再消费挑战，避免公钥格式错误也白耗一个挑战。
        val fp = DeviceAuth.fingerprintOf(pubKeySpki.trim())
            ?: return PairingConfirmResult.InvalidDeviceKey

        // 0.5 黑名单（2026-10-15）：配对与登录**是同一条链路**（下面 1. 才分叉），
        //     所以这一处判定就同时覆盖了"不能配对"与"不能密码登录"。
        //
        //     ★ 必须放在 claimChallenge **之前**：被拉黑的设备不该白耗一个一次性挑战
        //       （否则等于给攻击者一个廉价的服务端资源消耗点）。命中即拒，不消耗挑战、
        //       不落库、不签发 token —— 被拉黑设备的一次尝试对服务端是零成本。
        //
        //     判定用"指纹 + hwId"两条键：指纹权威但清 App 数据即换，hwId 跨重装但可伪造。
        //     两者并用只是**提高绕过门槛**，不承诺"绝对拦得住"（见 store 类注释）。
        if (blacklist?.isBlocked(fp, hwId) == true) {
            // 命中即进入冷却（2026-10-15）：首次 10 分钟，连击到 5 次升到 1 小时。
            // 冷却**不随解除拉黑清除** —— 否则"解除"这个动作本身就是一个可利用的窗口。
            blockCooldown?.recordHit(fp, hwId)
            return PairingConfirmResult.Blocked(retryAfterSeconds = cooldownSecondsLeft(fp, hwId))
        }
        // 0.6 冷却（2026-10-15）：黑名单已解除、但冷却还没过 —— 照样拒。
        // 顺序是先黑名单（权威，且要记一次命中）再冷却；两者都返回 Blocked，
        // 但**不细分错误码**：对外少透露一分"你被拉黑还是还在冷却"，就少一分试探的依据。
        // （2026-10-21 决策 D4：只透露**还要等多久**，这既不给试探者额外信息，
        //   又能让用户知道该等 5 分钟还是 1 小时 —— 之前只能盲猜并反复重试。）
        val cooldownLeft = cooldownSecondsLeft(fp, hwId)
        if (cooldownLeft > 0L) {
            return PairingConfirmResult.Blocked(retryAfterSeconds = cooldownLeft)
        }

        // 0.7 新设备批准（2026-10-15）。默认关（`pairingApprovalRequired=false`）→ 这一段整体跳过，
        //     存量行为逐字不变。
        //
        // ★ 防死锁（方案 §5.1 的批准者规则）：**一台已配对设备都没有时不做批准** ——
        //   否则机主删光设备后就再也进不去了（没人能批准）。同理，请求方本来就是已配对设备时
        //   （老设备重连/换 token）也不拦。
        if (settings.pairingApprovalRequired &&
            settings.pairedFingerprints.isNotEmpty() &&
            fp !in settings.pairedFingerprints &&
            !isApproved(sid, fp, approvalCode)
        ) {
            return PairingConfirmResult.ApprovalRequired
        }

        // 2026-10-05 R1-9 修复：挑战改为两阶段消费——先 claim，全部校验通过才 commit；
        // 失败路径 release 回灌（保留原 issuedAt），不再「一次失败烧掉一次性挑战」。
        // claim 的 remove 仍保证同一挑战只能被一个并发请求占用（防重放语义不变）。
        val trimmedChallenge = challenge.trim()
        if (!claimChallenge(trimmedChallenge)) {
            return PairingConfirmResult.InvalidChallenge
        }
        return try {
            val result = confirmClaimed(
                trimmedCode, pubKeySpki, challenge, signature, password,
                deviceName, ip, fp, trimmedChallenge,
                goformIp, goformPort, goformPassword, hwId
            )
            // 双键退避计数（2026-10-15）：在**一个出口**上判成功/失败，而不是往九个失败分支里
            // 各记一次 —— 逐个加必然漏一个，而漏掉的那条就是留给爆破的口子。
            // 命中黑名单的那条路在上面的 0.5 步就返回了，根本到不了这里（那是终态，不计数）。
            attemptLimiter?.let { limiter ->
                if (result is PairingConfirmResult.Success) {
                    limiter.reset(fp, hwId)
                } else if (result !is PairingConfirmResult.Blocked) {
                    limiter.recordFailure(fp, hwId)
                }
            }
            result
        } catch (t: Throwable) {
            releaseChallenge(trimmedChallenge)
            throw t
        }
    }

    /**
     * 这次 confirm 应施加的等待毫秒数（双键退避，见 [PairingAttemptLimiter]）。
     *
     * 由路由层在调用 [confirm] **之前** `delay` 掉（与 [passwordThrottleDelayMs] 同一手法）：
     * 本类不在协程上下文里，直接 `Thread.sleep` 会阻塞 Ktor 工作线程、连带拖慢无关请求。
     */
    fun attemptDelayMsFor(pubKeySpki: String?, hwId: String?): Long {
        val limiter = attemptLimiter ?: return 0
        val fp = DeviceAuth.fingerprintOf(pubKeySpki.orEmpty().trim())
        return limiter.delayMsFor(fp, hwId)
    }

    /**
     * 拉黑冷却的剩余秒数（向上取整；0 = 没有冷却）。
     *
     * 2026-10-21（决策 D4）：这个值会出现在 403 的 `retry_after_seconds` 里，让客户端显示倒计时。
     * 冷却策略（首次 10 分钟、连击升到 1 小时）本来就是公开的，藏剩余时间对攻击者没多少价值，
     * 对用户却是"不知道该等 5 分钟还是 1 小时"。
     */
    private fun cooldownSecondsLeft(fp: String, hwId: String?): Long {
        val remaining = blockCooldown?.remainingMs(fp, hwId) ?: 0L
        if (remaining <= 0L) return 0L
        return (remaining + 999L) / 1000L
    }

    private fun confirmClaimed(
        trimmedCode: String,
        pubKeySpki: String,
        challenge: String,
        signature: String,
        password: String?,
        deviceName: String?,
        ip: String,
        fp: String,
        trimmedChallenge: String,
        goformIp: String?,
        goformPort: Int?,
        goformPassword: String?,
        hwId: String?
    ): PairingConfirmResult {
        if (!DeviceAuth.verifySignature(pubKeySpki.trim(), challenge.trim(), signature.trim())) {
            AppLogger.w(TAG, "配对验签失败 fp=$fp ip=$ip")
            releaseChallenge(trimmedChallenge)
            return PairingConfirmResult.InvalidDeviceKey
        }

        // 1. 配对码校验
        //    - 全新设备（devicePasswordSet=false，首次初始化）：严格校验一次性配对码。
        //    - 已初始化设备：凭密码登录，不因配对码缺失/失效（一次性码仅在服务重启时
        //      由 enterPairingMode 重新生成）阻断——否则新客户端（换设备=新密钥）在
        //      后端未重启期间永远无法连接（InvalidCode 死锁）。
        val codeMatches = settings.pairingCode.isNotBlank() && settings.pairingCode == trimmedCode
        val fpAlreadyPaired = fp in settings.pairedFingerprints
        val loginByPassword = settings.devicePasswordSet && !codeMatches && !fpAlreadyPaired
        if (!codeMatches && !fpAlreadyPaired && !loginByPassword) {
            releaseChallenge(trimmedChallenge)
            return PairingConfirmResult.InvalidCode
        }

        // 2. 上限 / 重复（与 AppSettings.confirmPairing 语义对齐：已配对指纹允许刷新）
        //    2026-08-28：移除原先的 hwId 旁路。它让攻击者只要伪造 device_hwid 就能绕过配额，
        //    而换来的只是"清应用数据后不占新槽位"这点便利。设备身份现在由密钥对决定，
        //    清数据=新密钥=新设备，本就该占新槽位（用户可在配对界面删除旧记录）。
        val fps = settings.pairedFingerprints
        if (settings.pairingEnabled && settings.pairingMaxDevices > 0 &&
            fps.size >= settings.pairingMaxDevices && fp !in fps
        ) {
            releaseChallenge(trimmedChallenge)
            return PairingConfirmResult.AlreadyPaired
        }

        // 2.5 初次配对可同时写入 Goform 后台连接配置（IP/端口/密码）；校验前置，避免部分落库
        when (validateGoformSettings(goformIp, goformPort, goformPassword)) {
            is GoformApplyResult.Failure -> {
                releaseChallenge(trimmedChallenge)
                return PairingConfirmResult.InvalidGoformConfig
            }
            is GoformApplyResult.Success -> {}
        }

        // 3. 密码校验
        if (passwordAttemptLimiter.isLocked(ip)) {
            releaseChallenge(trimmedChallenge)
            return PairingConfirmResult.PasswordLocked
        }
        val pw = password
        if (pw.isNullOrEmpty()) {
            releaseChallenge(trimmedChallenge)
            return PairingConfirmResult.PasswordRequired
        }
        if (!settings.devicePasswordSet) {
            // 首次配对：请求密码即新密码（“设置密码”语义）
            // 对齐 change-password 的长度校验（防绕过前端直接调 API 设置超长密码）
            if (pw.length < MIN_PASSWORD_LENGTH || pw.length > MAX_PASSWORD_LENGTH) {
                releaseChallenge(trimmedChallenge)
                return PairingConfirmResult.InvalidPassword
            }
            settings.setDevicePassword(pw)
        } else if (!settings.verifyDevicePassword(pw)) {
            releaseChallenge(trimmedChallenge)
            return if (passwordAttemptLimiter.recordFailure(ip)) {
                PairingConfirmResult.PasswordLocked
            } else {
                PairingConfirmResult.InvalidPassword
            }
        }
        passwordAttemptLimiter.reset(ip)

        // 3.5 硬件去重：同一台硬件（hwId 相同）若换了密钥，清掉旧指纹与旧记录，
        //      避免 pairedFingerprints 无限增长。纯便利功能，不影响上面的配额判定。
        consolidateDuplicateHw(fp, deviceName, hwId)

        // 4. 落库（AppSettings 状态机）+ 配对记录双写 + Goform 连接配置写回
        persistGoformSettings(goformIp, goformPort, goformPassword)
        // 码匹配 → 一次性配对语义（消耗配对码）；其余（已配对刷新/密码登录）→ 免码绑定
        val persistResult = if (codeMatches) {
            settings.confirmPairing(trimmedCode, fp)
        } else {
            settings.loginPair(fp)
        }
        return when (persistResult) {
            is AppSettings.PairingConfirmResult.Success -> {
                // 2026-10-05 R1-9 修复：校验全部通过，commit 彻底烧毁挑战
                commitChallenge(trimmedChallenge)
                // 每台设备独占 token：明文只在本次响应里出现一次，落盘只有哈希。
                // 重新配对（含同指纹刷新）会轮换该设备 token，旧 token 立即失效。
                val token = newDeviceToken()
                store.upsert(
                    fingerprint = fp,
                    deviceName = deviceName?.takeIf { it.isNotBlank() } ?: fp,
                    lastSeen = System.currentTimeMillis(),
                    hwId = hwId?.trim().orEmpty(),
                    pubKey = pubKeySpki.trim(),
                    tokenHash = DeviceAuth.sha256Hex(token.toByteArray(Charsets.UTF_8))
                )
                AppLogger.i(TAG, "配对成功 fp=$fp name=${deviceName.orEmpty()} 已配对=${settings.pairedFingerprints.size}")
                PairingConfirmResult.Success(token = token, fingerprint = fp)
            }
            is AppSettings.PairingConfirmResult.InvalidCode -> {
                releaseChallenge(trimmedChallenge)
                PairingConfirmResult.InvalidCode
            }
            is AppSettings.PairingConfirmResult.AlreadyPaired -> {
                releaseChallenge(trimmedChallenge)
                PairingConfirmResult.AlreadyPaired
            }
        }
    }

    /** 生成设备 token：32 字节强随机的 base64url。明文不落盘。 */
    private fun newDeviceToken(): String {
        val bytes = ByteArray(TOKEN_BYTES)
        random.nextBytes(bytes)
        return DeviceAuth.base64UrlNoPad(bytes)
    }


    /**
     * 按硬件标识合并重复设备记录（修复「清除应用数据后重新配对导致设备列表无限增长」）。
     *
     * 背景：后端以 `app_fingerprint` 为设备主键，而前端指纹存于应用私有 SharedPreferences。
     * 用户「清除应用数据」后指纹丢失并重新生成，后端便把同一台手机当作新设备不断追加。
     * 前端现改为上报硬件派生的稳定 `device_hwid`，此处据其识别并收敛重复项。
     *
     * 规则：
     * - [hwId] 为 null/空白（旧版客户端未上报）→ 不做任何合并，返回原指纹（保持旧行为）；
     * - 找到 hwId 相同但指纹不同的旧记录 → 同一台硬件换了指纹：解绑旧指纹 + 删除旧记录，
     *   随后由调用方以新指纹重新绑定，设备总数保持不变；
     * - 找到的记录指纹与本次相同 → 常规刷新，无需处理。
     *
     * 注意：只清理**一条**匹配记录（[PairedDeviceStore.findByHwId] 返回首条）。同一 hwId
     * 理论上不会出现多条，因为每次配对都会先执行本合并逻辑。
     *
     * @return 后续应使用的有效指纹（当前实现恒为传入的 [fingerprint]）。
     */
    private fun consolidateDuplicateHw(
        fingerprint: String,
        deviceName: String?,
        hwId: String?
    ): String {
        val hw = hwId?.trim().orEmpty()
        if (hw.isBlank()) return fingerprint

        val dup = store.findByHwId(hw)
        if (dup != null && dup.fingerprint != fingerprint) {
            settings.unpairFingerprint(dup.fingerprint)
            store.remove(dup.fingerprint)
            AppLogger.i(
                TAG,
                "合并重复设备 hwId=$hw 旧指纹=${dup.fingerprint} -> 新指纹=$fingerprint" +
                    "（设备名=${deviceName ?: dup.deviceName}）"
            )
        }
        return fingerprint
    }

    /**
     * 修改配对密码（旧密码即管理权限证明，端点免 Bearer）。
     * 新密码长度 <4 或 >64 → 400；旧密码错 → 401 WRONG_OLD_PASSWORD；锁定 → 429。
     *
     * @param revokeAllDevices 2026-10-19：改密后是否**同时踢出所有已配对设备**。
     *   默认 false = 保持既有行为（只改密码，已配对设备的 token 继续有效）——
     *   这一点很多人会误以为"改密码就掉线"，实际不是。需要"改密即失效"时由调用方显式开启。
     */
    fun changePassword(
        oldPw: String,
        newPw: String,
        ip: String,
        goformIp: String? = null,
        goformPort: Int? = null,
        goformPassword: String? = null,
        revokeAllDevices: Boolean = false
    ): ChangePwdResult {
        if (newPw.length < MIN_PASSWORD_LENGTH || newPw.length > MAX_PASSWORD_LENGTH) {
            return ChangePwdResult.InvalidNewPassword
        }
        if (passwordAttemptLimiter.isLocked(ip)) {
            return ChangePwdResult.PasswordLocked
        }
        if (!settings.verifyDevicePassword(oldPw)) {
            return if (passwordAttemptLimiter.recordFailure(ip)) {
                ChangePwdResult.PasswordLocked
            } else {
                ChangePwdResult.WrongOldPassword
            }
        }
        // Goform 后台连接配置（IP/端口/密码）校验，置于设置配对密码之前，避免部分落库
        when (val g = validateGoformSettings(goformIp, goformPort, goformPassword)) {
            is GoformApplyResult.Failure -> return ChangePwdResult.InvalidGoformConfig
            is GoformApplyResult.Success -> {}
        }
        passwordAttemptLimiter.reset(ip)
        settings.setDevicePassword(newPw)
        persistGoformSettings(goformIp, goformPort, goformPassword)
        if (revokeAllDevices) {
            // 2026-10-19：可选"改密即失效"。放在密码落库**之后**：万一前面抛异常，
            // 设备仍保留旧密码，不会落到"密码没改成、配对却被清空"的半状态。
            store.clear()
            settings.unpair()
        }
        return ChangePwdResult.Success(hasDefaultPassword = settings.hasDefaultPassword)
    }

    /**
     * Goform 后台连接配置（调制解调器原生管理界面）校验，仅校验不落库。
     * 三个字段任一为 null 表示前端未传 → 忽略；全部为空表示无需更新。
     */
    private fun validateGoformSettings(ip: String?, port: Int?, password: String?): GoformApplyResult {
        if (ip == null && port == null && password == null) return GoformApplyResult.Success
        if (!ip.isNullOrBlank()) {
            val trimmed = ip.trim()
            if (trimmed.length > 253 || !GOFORM_HOST_PATTERN.matches(trimmed)) {
                return GoformApplyResult.Failure("goform_ip 格式不正确")
            }
        }
        if (port != null && (port < 1 || port > 65535)) {
            return GoformApplyResult.Failure("goform_port 需在 1-65535 之间")
        }
        if (!password.isNullOrBlank() && password.length > 128) {
            return GoformApplyResult.Failure("goform_password 过长")
        }
        return GoformApplyResult.Success
    }

    /** Goform 配置落库（调用前应已通过 [validateGoformSettings]）。 */
    private fun persistGoformSettings(ip: String?, port: Int?, password: String?) {
        if (!ip.isNullOrBlank()) settings.goformIp = ip.trim()
        if (port != null) settings.goformPort = port
        if (!password.isNullOrBlank()) settings.goformPassword = password
    }

    /** Goform 后台连接配置校验结果。 */
    private sealed class GoformApplyResult {
        object Success : GoformApplyResult()
        data class Failure(val error: String) : GoformApplyResult()
    }

    /** 已配对设备列表（记录级）。 */
    fun listDevices(): List<PairedDeviceRecord> = store.list()

    /**
     * 移除配对设备：校验密码（双因素：Bearer 证明已配对客户端 + 密码证明操作者权限）。
     *
     * token 已按设备下发（记录里存 tokenHash），删除记录即吊销该设备的 token，
     * 无需再轮换全局凭据 —— 其余设备的 token 与公钥不受影响。
     */
    fun removeDevice(fingerprint: String, password: String?, ip: String): RemoveResult {
        if (password.isNullOrEmpty()) {
            return RemoveResult.MissingPassword
        }
        if (passwordAttemptLimiter.isLocked(ip)) {
            return RemoveResult.PasswordLocked
        }
        if (!settings.verifyDevicePassword(password)) {
            return if (passwordAttemptLimiter.recordFailure(ip)) {
                RemoveResult.PasswordLocked
            } else {
                RemoveResult.InvalidPassword
            }
        }
        passwordAttemptLimiter.reset(ip)

        val devices = store.list()
        if (devices.none { it.fingerprint == fingerprint }) {
            return RemoveResult.DeviceNotFound
        }
        if (!store.remove(fingerprint)) {
            return RemoveResult.DeviceNotFound
        }
        settings.unpairFingerprint(fingerprint)
        return RemoveResult.Success
    }

    /** 重命名配对设备（设备名 1-32 字符）。 */
    fun renameDevice(fingerprint: String, newName: String): RenameResult {
        val name = newName.trim()
        if (name.isEmpty() || name.length > MAX_DEVICE_NAME_LENGTH) {
            return RenameResult.InvalidName
        }
        return if (store.rename(fingerprint, name)) {
            RenameResult.Success(deviceName = name)
        } else {
            RenameResult.DeviceNotFound
        }
    }

    // ══════════════ 配对设备黑名单（2026-10-15）══════════════
    //
    // 为什么放在 PairingManager 而不是另起一个 Manager：写操作要复用**同一套**密码校验
    // 与失败节流（`passwordAttemptLimiter` 是私有的）。另起一个类要么复制这套逻辑、
    // 要么把它暴露出去 —— 两条路都比"放在配对域里"差。黑名单本来就是配对域的一部分。
    //
    // 读侧（鉴权热路径）不经过这里：那条路直接问 store（见 confirm 的 0.5 步）。

    /** 当前黑名单（按拉黑时间倒序）。 */
    fun listBlocked(): List<BlockedDevice> = blacklist?.entries().orEmpty()

    /** 黑名单是否可用（装配层漏传 store 时为 false —— 由装配守卫测试守着）。 */
    fun blacklistAvailable(): Boolean = blacklist != null

    /**
     * 拉黑一台设备。写操作与"移除设备"同一套双因素：Bearer（已配对客户端）+ 配对密码。
     *
     * @param selfFingerprint 发起请求的设备指纹（鉴权中间件放在 `call.attributes` 里）。
     *   **用于拒绝拉黑本机**：否则机主在唯一一台设备上一点就把自己踢下线，而且没有别的
     *   设备能救它（解除拉黑需要已鉴权请求）。这是"必须处理的边界"里最要命的一条。
     */
    suspend fun blockDevice(
        fingerprint: String,
        hwId: String?,
        name: String?,
        reason: String?,
        password: String?,
        ip: String,
        selfFingerprint: String?
    ): BlacklistResult {
        val fp = fingerprint.trim()
        if (fp.isEmpty()) return BlacklistResult.InvalidTarget
        if (selfFingerprint != null && fp == selfFingerprint) return BlacklistResult.SelfBlock

        // 2026-10-19：hwId 也要挡。拉黑一条"与某台已配对设备同 hwId"的旧记录，会连带把那台
        // 已配对设备一起判黑（`isBlocked` 任一命中即算），而解除拉黑又要 Bearer —— 等于自锁。
        // 已配对记录里存了 hwId（PairedDeviceRecord.hwId），所以这里能判定。
        val targetHw = hwId?.trim().orEmpty()
        if (targetHw.isNotEmpty()) {
            val conflicts = store.list().any { it.hwId.equals(targetHw, ignoreCase = true) && it.fingerprint != fp }
            if (conflicts) return BlacklistResult.SelfBlock
        }

        val storeRef = blacklist ?: return BlacklistResult.Unavailable
        if (storeRef.isFull()) return BlacklistResult.Full

        when (val auth = verifyAdminPassword(password, ip)) {
            is AdminAuth.Ok -> Unit
            is AdminAuth.Missing -> return BlacklistResult.MissingPassword
            is AdminAuth.Wrong -> return BlacklistResult.InvalidPassword
            is AdminAuth.Locked -> return BlacklistResult.PasswordLocked
        }

        // 名称快照：优先用调用方给的，否则从已配对记录里取，最后退回指纹前缀 ——
        // 列表里显示"未命名设备 ab12"总比一行裸指纹强。
        val resolvedName = name?.trim().orEmpty().ifBlank {
            store.list().firstOrNull { it.fingerprint == fp }?.deviceName.orEmpty()
        }.ifBlank { "未命名设备 ${fp.take(PairedDeviceBlacklistStore.FINGERPRINT_PREFIX_LEN)}" }

        return try {
            val added = storeRef.block(
                BlockedDevice(
                    fingerprint = fp,
                    hwId = hwId?.trim().orEmpty(),
                    name = resolvedName,
                    reason = reason?.trim().orEmpty(),
                    blockedAt = System.currentTimeMillis(),
                    blockedBy = selfFingerprint.orEmpty()
                )
            )
            if (added) BlacklistResult.Success() else BlacklistResult.AlreadyBlocked
        } catch (e: IllegalStateException) {
            // store 在锁内二次判定上限（并发下 isFull 可能已过期）
            BlacklistResult.Full
        }
    }

    /** 解除拉黑。同样要配对密码。 */
    suspend fun unblockDevice(fingerprint: String, password: String?, ip: String): BlacklistResult {
        val fp = fingerprint.trim()
        if (fp.isEmpty()) return BlacklistResult.InvalidTarget
        val storeRef = blacklist ?: return BlacklistResult.Unavailable
        when (verifyAdminPassword(password, ip)) {
            is AdminAuth.Ok -> Unit
            is AdminAuth.Missing -> return BlacklistResult.MissingPassword
            is AdminAuth.Wrong -> return BlacklistResult.InvalidPassword
            is AdminAuth.Locked -> return BlacklistResult.PasswordLocked
        }
        return if (storeRef.unblock(fp)) BlacklistResult.Success() else BlacklistResult.NotFound
    }

    /** 全部解除。 */
    suspend fun clearBlacklist(password: String?, ip: String): BlacklistResult {
        val storeRef = blacklist ?: return BlacklistResult.Unavailable
        when (verifyAdminPassword(password, ip)) {
            is AdminAuth.Ok -> Unit
            is AdminAuth.Missing -> return BlacklistResult.MissingPassword
            is AdminAuth.Wrong -> return BlacklistResult.InvalidPassword
            is AdminAuth.Locked -> return BlacklistResult.PasswordLocked
        }
        return BlacklistResult.Success(storeRef.clear())
    }

    // ══════════════ 新设备批准（2026-10-15）══════════════
    //
    // 请求方（通常是网页端）**拿不到配对码**：码只发给已配对设备与机主配置的外部渠道。
    // 这样它就无法自己批准自己 —— 这套机制的全部意义就在这里。

    /**
     * 批准策略当前是否生效。
     *
     * 只由 `pairingApprovalRequired` 决定 —— 注意 `pairingEnabled` 的语义是"是否启用配对
     * **数量限制**"（false = 不限数量、仍允许配对），不是"关掉配对"，所以不能拿它当批准开关。
     */
    fun approvalRequired(): Boolean = settings.pairingApprovalRequired

    /**
     * 把批准策略落到待批准表（2026-10-24）。
     *
     * 策略关闭时清空所有 pending；返回清掉的条数，供配置端点决定要不要广播
     * `pairing:approvals` 让 App 立刻收起弹窗。
     *
     * 为什么必须真的删而不是只过滤：pending 条目会驱动 App 弹窗与外部通知；只过滤的话，
     * 用户重新打开批准开关时旧请求会"复活"，而且内存里一直挂着没人处理的请求。
     */
    fun enforceApprovalPolicy(): Int {
        if (settings.pairingApprovalRequired) return 0
        return approvalStore?.cancelPending() ?: 0
    }

    /**
     * 登记一次待批准请求。`sid` 由请求方生成（Web 端的会话随机数），同一 sid 幂等。
     *
     * @return 新建/命中的请求；未装配批准 store、公钥无法解析、或**批准策略已关闭**时返回 null
     */
    fun requestApproval(
        sid: String,
        pubKeySpki: String?,
        deviceName: String?,
        source: String
    ): PairedDeviceApprovalStore.Pending? {
        if (!settings.pairingApprovalRequired) {
            // 关闭期间旧页面/旧客户端仍可能来登记。不能让它再产生一条会弹到机主面前的请求；
            // 顺手清掉历史 pending（与配置端点的清理形成双保险）。
            approvalStore?.cancelPending()
            return null
        }
        val store = approvalStore ?: return null
        val fp = DeviceAuth.fingerprintOf(pubKeySpki.orEmpty().trim()) ?: return null
        return store.request(
            sid = sid,
            fingerprint = fp,
            // 2026-10-21（审计 §C3）：设备名是**请求方自报**的，端点免鉴权 —— 不截断的话
            // 一条请求就能在内存里留下一个巨大的字符串（待批准条目最长留 15 分钟）。
            // 弹窗只显示前两个字（PairingNotificationMask），32 个字符远远够用。
            deviceName = deviceName?.trim()?.take(MAX_DEVICE_NAME_LENGTH).orEmpty(),
            source = source
        )
    }

    /** 请求方轮询状态（`pending`/`approved`/`rejected`/`expired`，**不含码**）。 */
    fun approvalStatus(sid: String): String {
        if (!settings.pairingApprovalRequired) {
            approvalStore?.cancelPending()
            return PairedDeviceApprovalStore.STATUS_EXPIRED
        }
        return approvalStore?.statusOf(sid) ?: PairedDeviceApprovalStore.STATUS_EXPIRED
    }

    /** 已配对设备看到的待批准列表（**含码**，供 App 弹窗显示）。 */
    fun pendingApprovals(): List<PairedDeviceApprovalStore.Pending> {
        if (!settings.pairingApprovalRequired) {
            // App 每次进页面/回前台都会拉这份列表；这里顺手清空，覆盖"开关是在 core
            // 本机设置页关掉、没有走 PUT /api/pairing/config"的那条路径。
            approvalStore?.cancelPending()
            return emptyList()
        }
        return approvalStore?.pendingList().orEmpty()
    }

    /** 机主点"允许"。策略已关时不允许再改状态（列表此时也应为空）。 */
    fun approveDevice(sid: String): Boolean {
        if (!settings.pairingApprovalRequired) {
            approvalStore?.cancelPending()
            return false
        }
        return approvalStore?.approve(sid) ?: false
    }

    /**
     * 这个来源还能不能发起**新的**批准请求（限速，方案 §6.4）。
     *
     * 注意：调用方只在"确实是一条新请求"时才该问它 —— 幂等命中已有 sid（Web 刷新页面）
     * 不该消耗配额，否则用户刷几次页面就把自己限住了。
     */
    fun allowApprovalRequest(ip: String): Boolean = approvalStore?.allowNewRequest(ip) ?: true

    /** 现在还能不能发一条批准通知（全局限速：防陌生人靠不停发起请求刷屏机主）。 */
    fun allowApprovalNotification(): Boolean = approvalStore?.allowNotification() ?: false

    /**
     * 这个来源还能不能问一次待批准状态（2026-10-21，审计 §C4）。
     *
     * 免鉴权端点，但请求方等待期就靠它轮询；只防跑飞的循环，阈值很宽（见 store 的实现）。
     */
    fun allowApprovalStatusQuery(ip: String): Boolean = approvalStore?.allowStatusQuery(ip) ?: true

    /** 机主点"拒绝"。策略已关时不允许再改状态（列表此时也应为空）。 */
    fun rejectDevice(sid: String): Boolean {
        if (!settings.pairingApprovalRequired) {
            approvalStore?.cancelPending()
            return false
        }
        return approvalStore?.reject(sid) ?: false
    }

    /**
     * 这次 confirm 是否已被批准。
     *
     * 两条判据任一满足即可：请求已被 App 批准 / 回传的配对码正确。
     *（"请求方本来就是已配对设备"那条在调用点判，不在这里 —— 它不需要任何批准记录。）
     */
    private fun isApproved(sid: String?, fingerprint: String, code: String?): Boolean {
        // 批准策略关闭时 confirm 根本不会走到这里（见 confirm 的 0.7 段）；这里再挡一次，
        // 避免将来有人把判定顺序改掉后，靠一条残留的 approved 记录绕过策略。
        if (!settings.pairingApprovalRequired) return false
        val store = approvalStore ?: return false
        val key = sid?.trim().orEmpty()
        if (key.isEmpty()) return false
        val pending = store.get(key) ?: return false
        // 必须是对**这个指纹**的批准：防止拿别人的 sid 蹭批准
        if (pending.fingerprint != fingerprint) return false
        if (pending.approved) return true
        return store.verifyCode(key, code)
    }

    /** 管理操作（移除设备 / 拉黑 / 解除）共用的密码校验。抽出来是为了三处口径完全一致。 */
    private fun verifyAdminPassword(password: String?, ip: String): AdminAuth {
        if (password.isNullOrEmpty()) return AdminAuth.Missing
        if (passwordAttemptLimiter.isLocked(ip)) return AdminAuth.Locked
        if (!settings.verifyDevicePassword(password)) {
            return if (passwordAttemptLimiter.recordFailure(ip)) AdminAuth.Locked else AdminAuth.Wrong
        }
        passwordAttemptLimiter.reset(ip)
        return AdminAuth.Ok
    }

    private sealed class AdminAuth {
        object Ok : AdminAuth()
        object Missing : AdminAuth()
        object Wrong : AdminAuth()
        object Locked : AdminAuth()
    }

    /** GET /api/pairing/status 增强载荷（兼容旧字段 + device_name / has_default_password / devices）。 */
    fun statusPayload(): Map<String, Any?> = mapOf(
        "paired" to settings.paired,
        "device_id" to settings.deviceId,
        "device_name" to deviceNameProvider(),
        "has_default_password" to settings.hasDefaultPassword,
        "paired_fingerprints" to settings.pairedFingerprints,
        "paired_count" to settings.pairedFingerprints.size,
        "paired_at" to settings.pairedAt,
        "pairing_code" to settings.pairingCode,
        "pairing_enabled" to settings.pairingEnabled,
        "pairing_max_devices" to settings.pairingMaxDevices,
        // 2026-10-15（安全中心）：批准策略与外部通知渠道。
        // 客户端据此渲染开关；三个通知开关默认 false（用户 2026-10-15 决定）。
        "pairing_approval_required" to settings.pairingApprovalRequired,
        "pairing_notify_system" to settings.pairingNotifySystem,
        "pairing_notify_email" to settings.pairingNotifyEmail,
        "pairing_notify_webhook" to settings.pairingNotifyWebhook,
        "devices" to store.list().map { recordToPayload(it) }
    )

    /** 解除全部配对（旧端点兼容：清空指纹 + 记录 + 重新进入配对模式；不轮换 token，保持旧行为）。 */
    fun unpairAll() {
        settings.unpair()
        store.clear()
    }

    /**
     * 解除全部配对（带密码校验）。
     *
     * 与 [removeDevice] 同一套双因素判定：密码证明操作者是设备主人。
     * 之前 `POST /pairing/unpair` 仅做同网段判断即可清空全部配对——同一 WiFi 下
     * 任何人都能把所有客户端踢下线并让设备重新进入配对模式，属于未鉴权的破坏性操作。
     *
     * 计数器只在**校验成功**后重置（[PasswordAttemptLimiter.reset]），
     * 否则攻击者可以用无密码的 unpair 请求把自己的失败计数清零，反过来放大爆破。
     */
    fun unpairAllWithPassword(password: String?, ip: String): UnpairResult {
        if (password.isNullOrEmpty()) {
            return UnpairResult.MissingPassword
        }
        if (passwordAttemptLimiter.isLocked(ip)) {
            return UnpairResult.PasswordLocked
        }
        if (!settings.verifyDevicePassword(password)) {
            return if (passwordAttemptLimiter.recordFailure(ip)) {
                UnpairResult.PasswordLocked
            } else {
                UnpairResult.InvalidPassword
            }
        }
        passwordAttemptLimiter.reset(ip)
        unpairAll()
        return UnpairResult.Success
    }


    /**
     * 解除指定设备的配对（**旧端点兼容**：不校验密码、不轮换 token）。
     *
     * ⚠ 2026-10-21（审计 §B1）：只有 [unpairFingerprintWithPassword] 才该被 HTTP 路由使用 ——
     * 「踢掉一台」比「踢掉全部」理应门槛相同或更低，两个旧端点免密码曾让双因素被绕过。
     * 本方法保留给内部/测试调用，路由层不要再直接用它。
     */
    fun unpairFingerprint(fingerprint: String) {
        settings.unpairFingerprint(fingerprint)
        store.remove(fingerprint)
    }

    /**
     * 解除指定设备的配对（带配对密码）。
     *
     * 与 [unpairAllWithPassword] 同一套判定：密码证明操作者是设备主人。
     * 2026-10-21 新增，用于取代免密码的旧端点（审计 §B1：破坏性操作的两种门槛必须一致）。
     */
    fun unpairFingerprintWithPassword(fingerprint: String, password: String?, ip: String): UnpairResult {
        val fp = fingerprint.trim()
        when (val auth = verifyAdminPassword(password, ip)) {
            is AdminAuth.Ok -> Unit
            is AdminAuth.Missing -> return UnpairResult.MissingPassword
            is AdminAuth.Wrong -> return UnpairResult.InvalidPassword
            is AdminAuth.Locked -> return UnpairResult.PasswordLocked
        }
        // 空指纹不做事，但也不该回失败：路由层已经对空路径参数先回 400。
        if (fp.isNotEmpty()) unpairFingerprint(fp)
        return UnpairResult.Success
    }

    private fun recordToPayload(record: PairedDeviceRecord): Map<String, Any?> = mapOf(
        "fingerprint" to record.fingerprint,
        "device_name" to record.deviceName,
        "last_seen" to record.lastSeen,
        "created_at" to record.createdAt
    )

    /**
     * 配对确认结果。
     *
     * [Success] 只带 token + 服务端算出的指纹：HMAC secret 已随「每请求非对称签名」一并废除
     * （签名用设备私钥，服务端用记录里的公钥验，不再需要共享密钥）。
     * 回显 [Success.fingerprint] 是为了让客户端知道自己在设备列表里的身份，
     * 而不必在客户端重复实现一遍指纹算法（客户端自算=又一个可分叉的真相源）。
     */
    sealed class PairingConfirmResult {
        object AlreadyPaired : PairingConfirmResult()
        object InvalidCode : PairingConfirmResult()
        data class Success(val token: String, val fingerprint: String) : PairingConfirmResult()        object InvalidPassword : PairingConfirmResult()
        object PasswordLocked : PairingConfirmResult()
        object PasswordRequired : PairingConfirmResult()
        object InvalidGoformConfig : PairingConfirmResult()
        /** 公钥无法解析，或用该公钥验签挑战失败 */
        object InvalidDeviceKey : PairingConfirmResult()
        /** 挑战不存在 / 已被使用 / 已过期 —— 客户端应重新走 /pairing/challenge */
        object InvalidChallenge : PairingConfirmResult()
        /**
         * 本设备已被机主拉黑（2026-10-15）。
         *
         * 路由层映射为 **403 + `DEVICE_BLOCKED`**（不是 401）：401 会被客户端"密码错→重试"
         * 的逻辑吃掉，用户只看到没头没尾的失败；403 + 独立错误码才能弹出"本设备已被拉黑"。
         *
         * @param retryAfterSeconds 还需等多久（秒）。0 = 不知道 / 没有冷却。
         *   2026-10-21（决策 D4）：解除拉黑**不清冷却**（最长 1 小时），而冷却策略本身是公开的，
         *   藏着剩余时间只会让用户不知道该等 5 分钟还是 1 小时、反复重试把冷却越刷越长。
         */
        data class Blocked(val retryAfterSeconds: Long = 0L) : PairingConfirmResult()
        /**
         * 新设备需要机主批准（2026-10-15）。
         *
         * 路由层映射为 **403 + `APPROVAL_REQUIRED`**：请求方应提示"请在已配对设备上批准，
         * 或输入外部通知里的配对码"。只在 `pairingApprovalRequired` 打开且**已有**已配对设备时出现。
         */
        object ApprovalRequired : PairingConfirmResult()
    }

    /** 修改密码结果。 */
    sealed class ChangePwdResult {
        data class Success(val hasDefaultPassword: Boolean) : ChangePwdResult()
        object WrongOldPassword : ChangePwdResult()
        object PasswordLocked : ChangePwdResult()
        object InvalidNewPassword : ChangePwdResult()
        object InvalidGoformConfig : ChangePwdResult()
    }

    /** 移除设备结果。 */
    sealed class RemoveResult {
        object Success : RemoveResult()
        object DeviceNotFound : RemoveResult()
        object InvalidPassword : RemoveResult()
        object PasswordLocked : RemoveResult()
        object MissingPassword : RemoveResult()
    }

    /** 解除全部配对结果。 */
    sealed class UnpairResult {
        object Success : UnpairResult()
        object InvalidPassword : UnpairResult()
        object PasswordLocked : UnpairResult()
        object MissingPassword : UnpairResult()
    }

    /**
     * 黑名单写操作结果（2026-10-15）。
     *
     * 与 [RemoveResult] 分开而不是复用：黑名单多了 [SelfBlock] / [Full] /
     * [AlreadyBlocked] / [Unavailable] 四种语义，硬塞进 RemoveResult 会让"移除设备"的
     * 调用点被迫处理一堆与它无关的分支。
     */
    sealed class BlacklistResult {
        /** @param removed 仅"全部解除"用：本次移除了几条；单条操作恒为 0。 */
        data class Success(val removed: Int = 0) : BlacklistResult()
        /** 已在名单里（幂等：只刷新名称/理由，不改变拉黑时间）。 */
        object AlreadyBlocked : BlacklistResult()
        object InvalidTarget : BlacklistResult()
        /** 拒绝拉黑本机 —— 否则机主一点就把自己踢下线，且没有别的设备能解除。 */
        object SelfBlock : BlacklistResult()
        object Full : BlacklistResult()
        object NotFound : BlacklistResult()
        object MissingPassword : BlacklistResult()
        object InvalidPassword : BlacklistResult()
        object PasswordLocked : BlacklistResult()
        /** 装配层没注入 store（功能未启用）—— 由装配守卫测试守着不该发生。 */
        object Unavailable : BlacklistResult()
    }

    /** 重命名结果。 */
    sealed class RenameResult {
        data class Success(val deviceName: String) : RenameResult()
        object DeviceNotFound : RenameResult()
        object InvalidName : RenameResult()
    }

    companion object {
        private const val TAG = "Pairing"
        const val MIN_PASSWORD_LENGTH = 4
        const val MAX_PASSWORD_LENGTH = 64
        const val MAX_DEVICE_NAME_LENGTH = 32
        private val GOFORM_HOST_PATTERN = Regex("^[a-zA-Z0-9]([a-zA-Z0-9.\\-]*[a-zA-Z0-9])?$")

        /** 配对挑战随机字节数。 */
        private const val CHALLENGE_BYTES = 32

        /**
         * 挑战有效期。取 2 分钟：足够覆盖「取挑战 → 用户输密码 → 提交」的正常节奏，
         * 又不给攻击者留出批量囤挑战的窗口。过期后客户端重新取即可，不影响正常用户。
         */
        private const val CHALLENGE_TTL_MS = 2 * 60 * 1000L

        /** 未消费挑战的容量上限，防止 /pairing/challenge 被刷导致内存膨胀。 */
        private const val CHALLENGE_MAX = 512

        /** 设备 token 随机字节数（256 bit）。 */
        private const val TOKEN_BYTES = 32
    }
}
