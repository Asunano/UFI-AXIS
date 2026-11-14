package com.ufi_axis_core.api.pairing

import com.ufi_axis_core.util.AppLogger
import com.ufi_axis_core.util.AppSettings
import com.ufi_axis_core.util.DeviceAuth
import com.ufi_axis_core.util.PairedDeviceRecord
import com.ufi_axis_core.util.PairedDeviceStore
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/**
 * 配对编排服务：集中实现配对 / 密码 / 设备管理的一致性逻辑，路由层只做 HTTP 转换。
 *
 * ## 设备身份
 * 设备身份不是客户端自报的字符串，而是设备本地不可导出私钥对应的公钥：
 * - App：Android Keystore（EC P-256，优先 StrongBox）
 * - Web：WebCrypto `extractable=false` + IndexedDB
 *
 * 配对为三步握手，使「持有密码即可无限配对或冒充已配对指纹」不可行：
 * 1. `POST /pairing/challenge` → 服务端下发一次性 nonce（[issueChallenge]）
 * 2. 客户端用私钥对 nonce 签名
 * 3. `POST /pairing/confirm` 携带 `device_pubkey` + `signature` → 服务端验签（[confirm]）
 *
 * 指纹由服务端依据公钥计算（`DeviceAuth.fingerprintOf`），不采信客户端自报的指纹；每台设备
 * 签发独占 token，仅保存哈希，删除该设备记录即等于吊销其凭据。
 *
 * 职责：
 * - info / status 载荷组装（device_name 经 [deviceNameProvider] seam 注入）
 * - challenge 下发与消费
 * - confirm 验签 + 密码首设 / 校验 + 配对记录双写
 * - change-password / 设备列表 / 重命名 / 移除
 * - 密码错误计数（每 IP + 全局双层，见 [PasswordAttemptLimiter]）
 *
 * 密码明文与 token 明文永不落库、不回显；错误响应沿用 `{error, code}` 格式。
 */
class PairingManager(
    private val settings: AppSettings,
    private val store: PairedDeviceStore,
    private val deviceNameProvider: () -> String = {
        store.systemDeviceName().ifBlank { settings.deviceId }
    },
    private val passwordAttemptLimiter: PasswordAttemptLimiter = PasswordAttemptLimiter(),
    /**
     * 配对设备黑名单。可空以保持既有构造点兼容：为空时等同于「未启用黑名单」，行为不变。
     * 生产装配在 `ComponentFactory` 中传入实际实例；该处漏传会使功能静默失效，
     * 因此装配层必须显式传入。
     */
    private val blacklist: PairedDeviceBlacklistStore? = null,
    /**
     * 配对 / 登录失败的双键指数退避（见 [PairingAttemptLimiter]）。
     *
     * 与 [blacklist] 同样可空：既有构造点不受影响，为空时等同于「未启用退避」。
     * 生产装配必须传入；漏传会使更换 IP 即可无限次尝试。
     */
    private val attemptLimiter: PairingAttemptLimiter? = null,
    /**
     * 黑名单命中后的冷却（见 [PairingBlockCooldown]）。
     *
     * 与 [blacklist] 分开传入：冷却描述的是「解除拉黑之后的窗口」，两份状态互不依赖。
     * 解除拉黑不清除冷却，否则「解除」这一动作本身即构成可利用的窗口。
     */
    private val blockCooldown: PairingBlockCooldown? = null,
    /**
     * 新设备待批准请求（见 [PairedDeviceApprovalStore]）。
     *
     * 可空同前：既有构造点不受影响，为空时 `pairingApprovalRequired` 打开也不生效；
     * 生产装配必须传入。
     */
    private val approvalStore: PairedDeviceApprovalStore? = null
) {

    private val random = SecureRandom()

    /** 未消费的配对挑战：nonce → 下发时刻。一次性使用，过期自动清理。 */
    private val challenges = ConcurrentHashMap<String, Long>()

    // 挑战采用两阶段消费：先 claim（原子地从 challenges 移除，防重放语义不变），全部校验通过
    // 后 commit 彻底销毁；任一校验失败则 release 回灌（保留原 issuedAt，不延长总 TTL）。避免
    // 「密码输错或密码锁定即消耗一次性挑战」导致配对反复失败。
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

    /** 解析当前设备名（Settings.Global.DEVICE_NAME，缺失时回退 deviceId）。 */
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
     * @param deviceName 由路由经 deviceNameProvider seam 计算后传入。
     * @param hasRoot 由路由的 rootChecker seam 注入。
     * @param isExternalStorageManager 由路由的 Environment seam 注入。
     *
     * `pairing_code` 仅在设备尚未初始化（`hasDefaultPassword=true`）时下发：此时配对码是完成
     * 初始化的必要条件，客户端必须能自动取得。配对密码一旦设置，后续客户端走「密码登录」分支
     * （见 [confirm] 第 1 步的 `loginByPassword`），不再需要配对码；对免鉴权请求继续回显配对码
     * 只会向扫描者泄露一个凭据。
     *
     * 注：配对密码相关的符号名沿用 `devicePassword*`（`AppSettings.devicePasswordSet` /
     * `setDevicePassword` / `verifyDevicePassword`），该名称是持久化 key 的一部分；改名会使存量
     * 设备读不到已设置的密码，故保留该命名。
     */
    fun infoPayload(
        deviceName: String,
        hasRoot: Boolean,
        isExternalStorageManager: Boolean
    ): Map<String, Any?> = mapOf(
        "device_id" to settings.deviceId,
        "device_name" to deviceName,
        "pairing_code" to if (settings.hasDefaultPassword) settings.pairingCode else "",
        // 已配对记录数。App 的「本机是唯一已配对设备 → 清除旧配对」自救入口需要它来区分
        // 「设备上确有多台设备」与「只剩一条本机的陈旧记录」。只返回数量，不返回指纹：
        // 局域网端点，数量本身不构成可利用信息。
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
     * 两种模式：
     * - 配对（首次初始化）：设备从未设置密码（devicePasswordSet=false）→ 严格校验一次性配对
     *   码；请求中的密码作为配对密码落库（「设置密码」语义），可同时写入 Goform 配置。
     * - 登录（普通连接）：设备已初始化（devicePasswordSet=true）→ 凭配对密码即可连接；配对码
     *   已消耗或未携带时不再阻断（[InvalidCode]），密码正确即绑定指纹并下发 token。
     *   已配对指纹免码刷新；上限检查仍然生效。
     *
     * 校验顺序（任何一步失败都不产生副作用）：
     * 0. 设备身份：公钥可解析 → 服务端计算指纹；挑战存在且未过期（一次性消费）；用该公钥
     *    验证挑战签名。三者缺一即 [PairingConfirmResult.InvalidDeviceKey]。
     * 1. 配对码（只读校验，不落库）
     * 2. 上限 / 重复（已配对指纹允许刷新）
     * 3. 密码：首次将请求密码直接落库（设置密码）；之后校验失败 → 401 INVALID_PASSWORD；
     *    每 IP 15min 5 次 / 全局 15min 20 次失败 → 429 PASSWORD_LOCKED
     * 3.5 硬件去重：按 [hwId] 合并同一台设备清数据 / 重装后换指纹产生的重复记录
     *    （见 [consolidateDuplicateHw]）
     * 4. 成功 → 签发该设备独占 token（仅存哈希）+ 写 AppSettings 指纹 + PairedDeviceStore 记录
     *
     * @param pubKeySpki 设备身份公钥，X.509 SPKI DER 的 base64。指纹由此计算，不接受客户端自报
     *   指纹。
     * @param challenge [issueChallenge] 下发的一次性挑战原文。
     * @param signature 用设备私钥对 [challenge] 原文（不做额外拼装）的 ECDSA-SHA256 签名，
     *   DER 或 raw r||s 均可（`DeviceAuth` 会归一化）。
     * @param hwId 客户端上报的硬件级稳定标识（`device_hwid`）。仅用于合并重复记录，不参与任何
     *   安全判定：它是可伪造的明文，已从配额检查中移除旁路。
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

        // 0.5 黑名单：配对与登录是同一条链路（步骤 1 才分叉），因此此处的判定同时覆盖
        //     「不能配对」与「不能密码登录」。
        //
        //     必须位于 claimChallenge 之前：被拉黑的设备不应消耗一次性挑战，否则相当于为攻击者
        //     提供一个低成本的服务端资源消耗点。命中即拒绝，不消耗挑战、不落库、不签发 token。
        //
        //     判定使用「指纹 + hwId」两条键：指纹权威但清除应用数据即改变，hwId 跨重装稳定但
        //     可伪造。两者并用仅提高绕过门槛，不承诺绝对拦截（见 store 类注释）。
        if (blacklist?.isBlocked(fp, hwId) == true) {
            // 命中即进入冷却：首次 10 分钟，连续命中至 5 次升至 1 小时。
            // 冷却不随解除拉黑清除，否则「解除」这一动作本身即构成可利用的窗口。
            blockCooldown?.recordHit(fp, hwId)
            return PairingConfirmResult.Blocked(retryAfterSeconds = cooldownSecondsLeft(fp, hwId))
        }
        // 0.6 冷却：黑名单已解除但冷却未结束，同样拒绝。
        // 顺序为先黑名单（权威，且需记录一次命中）再冷却；两者均返回 Blocked，但不细分错误码：
        // 对外少透露「被拉黑还是仍在冷却」，即少一分试探依据。仅返回还需等待多久，该信息不为
        // 试探者提供额外信息，同时让用户知道应等待 5 分钟还是 1 小时。
        val cooldownLeft = cooldownSecondsLeft(fp, hwId)
        if (cooldownLeft > 0L) {
            return PairingConfirmResult.Blocked(retryAfterSeconds = cooldownLeft)
        }

        // 0.7 新设备批准。默认关闭（`pairingApprovalRequired=false`）时整段跳过，行为不变。
        //
        // 防死锁规则：一台已配对设备都没有时不做批准 —— 否则机主删除全部设备后将无法再进入
        // （没有设备可以批准）。同理，请求方本身是已配对设备时（老设备重连 / 换 token）也不拦截。
        if (settings.pairingApprovalRequired &&
            settings.pairedFingerprints.isNotEmpty() &&
            fp !in settings.pairedFingerprints &&
            !isApproved(sid, fp, approvalCode)
        ) {
            return PairingConfirmResult.ApprovalRequired
        }

        // 挑战采用两阶段消费：先 claim，全部校验通过才 commit；失败路径 release 回灌（保留原
        // issuedAt）。claim 的原子 remove 保证同一挑战只能被一个并发请求占用（防重放）。
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
            // 双键退避计数：在唯一出口处判定成功 / 失败，不在各失败分支分别记录 —— 分散记录
            // 必然遗漏某条分支，而遗漏的分支即成为可爆破的入口。
            // 命中黑名单的路径在步骤 0.5 已返回，不会到达此处（该结果为终态，不计数）。
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
     * 本次 confirm 应施加的等待毫秒数（双键退避，见 [PairingAttemptLimiter]）。
     *
     * 由路由层在调用 [confirm] 之前 `delay` 掉（与 [passwordThrottleDelayMs] 相同）：本类不在
     * 协程上下文中，直接 `Thread.sleep` 会阻塞 Ktor 工作线程并拖慢无关请求。
     */
    fun attemptDelayMsFor(pubKeySpki: String?, hwId: String?): Long {
        val limiter = attemptLimiter ?: return 0
        val fp = DeviceAuth.fingerprintOf(pubKeySpki.orEmpty().trim())
        return limiter.delayMsFor(fp, hwId)
    }

    /**
     * 拉黑冷却的剩余秒数（向上取整；0 表示没有冷却）。
     *
     * 该值出现在 403 的 `retry_after_seconds` 中，供客户端显示倒计时。冷却策略（首次 10 分钟、
     * 连续命中升至 1 小时）本身是公开信息，隐藏剩余时间对攻击者价值有限，却会让用户无法判断
     * 应等待 5 分钟还是 1 小时。
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
        //    - 已初始化设备：凭密码登录，不因配对码缺失或失效（一次性码仅在服务重启时由
        //      enterPairingMode 重新生成）而阻断；否则新客户端（换设备即新密钥）在后端未重启
        //      期间将永远无法连接。
        val codeMatches = settings.pairingCode.isNotBlank() && settings.pairingCode == trimmedCode
        val fpAlreadyPaired = fp in settings.pairedFingerprints
        val loginByPassword = settings.devicePasswordSet && !codeMatches && !fpAlreadyPaired
        if (!codeMatches && !fpAlreadyPaired && !loginByPassword) {
            releaseChallenge(trimmedChallenge)
            return PairingConfirmResult.InvalidCode
        }

        // 2. 上限 / 重复（与 AppSettings.confirmPairing 语义一致：已配对指纹允许刷新）
        //    不提供 hwId 旁路：它使攻击者伪造 device_hwid 即可绕过配额，而换来的仅是「清除应用
        //    数据后不占新槽位」的便利。设备身份现由密钥对决定，清除数据即新密钥、即新设备，
        //    本应占用新槽位（用户可在配对界面删除旧记录）。
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
            // 首次配对：请求密码即新密码（「设置密码」语义）
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
                // 全部校验通过，commit 彻底销毁挑战
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
     * 按硬件标识合并重复设备记录，避免清除应用数据后重新配对使设备列表无限增长。
     *
     * 后端以 `app_fingerprint` 为设备主键，而前端指纹存于应用私有 SharedPreferences：用户清除
     * 应用数据后指纹丢失并重新生成，后端会将同一台手机视为新设备不断追加。前端上报硬件派生的
     * 稳定 `device_hwid`，此处据其识别并收敛重复项。
     *
     * 规则：
     * - [hwId] 为 null 或空白（旧版客户端未上报）→ 不做合并，返回原指纹（保持旧行为）；
     * - 找到 hwId 相同但指纹不同的旧记录 → 同一台硬件更换了指纹：解绑旧指纹并删除旧记录，
     *   随后由调用方以新指纹重新绑定，设备总数不变；
     * - 找到的记录指纹与本次相同 → 常规刷新，无需处理。
     *
     * 注意：仅清理一条匹配记录（[PairedDeviceStore.findByHwId] 返回首条）。同一 hwId 理论上
     * 不会出现多条，因为每次配对都会先执行本合并逻辑。
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
     * 新密码长度 <4 或 >64 → 400；旧密码错误 → 401 WRONG_OLD_PASSWORD；锁定 → 429。
     *
     * @param revokeAllDevices 改密后是否同时踢出所有已配对设备。默认 false，保持既有行为
     *   （只改密码，已配对设备的 token 继续有效）；需要「改密即失效」时由调用方显式开启。
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
            // 可选「改密即失效」。置于密码落库之后：若此前抛出异常，设备仍保留旧密码，
            // 不会出现「密码未改成、配对记录却被清空」的中间状态。
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
     * 移除配对设备（挂 /api 鉴权块内，只要求 Bearer + 设备签名）。
     *
     * token 已按设备下发（记录里存 tokenHash），删除记录即吊销该设备的 token，
     * 无需再轮换全局凭据 —— 其余设备的 token 与公钥不受影响。
     * 对方若想回来仍需配对密码，所以这里不再额外要一次密码。
     */
    fun removeDevice(fingerprint: String): RemoveResult {
        if (store.list().none { it.fingerprint == fingerprint }) {
            return RemoveResult.DeviceNotFound
        }
        if (!store.remove(fingerprint)) {
            return RemoveResult.DeviceNotFound
        }
        settings.unpairFingerprint(fingerprint)
        // 这是路由实际走的路径（`DELETE /api/pairing/devices/{fp}`）。少了这句，
        // "删配对"只改了存储，对方已经建好的 WS 会继续收数据（2026-11-14 实测）。
        onDeviceRevoked?.invoke(fingerprint)
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

    // ══════════════ 配对设备黑名单 ══════════════
    //
    // 黑名单放在 PairingManager 而非独立 Manager：拉黑判定（本机不可拉黑、hwId 冲突）需要
    // 已配对记录（`store`），黑名单属于配对域的一部分。
    //
    // 鉴权热路径不经过这里：该路径直接查询 store（见 confirm 的 0.5 步）。

    /** 当前黑名单（按拉黑时间倒序）。 */
    fun listBlocked(): List<BlockedDevice> = blacklist?.entries().orEmpty()

    /** 黑名单是否可用（装配层漏传 store 时为 false）。 */
    fun blacklistAvailable(): Boolean = blacklist != null

    /**
     * 拉黑一台设备（挂 /api 鉴权块内，只要求 Bearer + 设备签名）。
     *
     * @param selfFingerprint 发起请求的设备指纹（鉴权中间件放在 `call.attributes` 里）。
     *   用于拒绝拉黑本机：否则机主在唯一一台设备上操作一次即把自己踢下线，且没有其他设备
     *   可以解除拉黑（解除拉黑需要已鉴权请求）。
     */
    suspend fun blockDevice(
        fingerprint: String,
        hwId: String?,
        name: String?,
        reason: String?,
        selfFingerprint: String?
    ): BlacklistResult {
        val fp = fingerprint.trim()
        if (fp.isEmpty()) return BlacklistResult.InvalidTarget
        if (selfFingerprint != null && fp == selfFingerprint) return BlacklistResult.SelfBlock

        // hwId 同样需要拦截：拉黑一条与某台已配对设备同 hwId 的旧记录，会因 isBlocked 的任一
        // 命中规则连带把该已配对设备判黑；而解除拉黑需要 Bearer，等于使机主无法恢复。
        // 已配对记录保存了 hwId（PairedDeviceRecord.hwId），因此可以在此判定。
        val targetHw = hwId?.trim().orEmpty()
        if (targetHw.isNotEmpty()) {
            val conflicts = store.list().any { it.hwId.equals(targetHw, ignoreCase = true) && it.fingerprint != fp }
            if (conflicts) return BlacklistResult.SelfBlock
        }

        val storeRef = blacklist ?: return BlacklistResult.Unavailable
        if (storeRef.isFull()) return BlacklistResult.Full

        // 名称快照：优先使用调用方提供的名称，其次取自已配对记录，最后回退为指纹前缀，
        // 以避免列表中只显示一条指纹。
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
            // 拉黑也要当场踢下线：否则对方那条已建好的 WS 会继续收数据（HTTP 已被拒，
            // 但它只靠 WS 取数时界面上看不出任何异常）。
            onDeviceRevoked?.invoke(fp)
            if (added) BlacklistResult.Success() else BlacklistResult.AlreadyBlocked
        } catch (e: IllegalStateException) {
            // store 在锁内二次判定上限（并发下 isFull 可能已过期）
            BlacklistResult.Full
        }
    }

    /** 解除拉黑。 */
    suspend fun unblockDevice(fingerprint: String): BlacklistResult {
        val fp = fingerprint.trim()
        if (fp.isEmpty()) return BlacklistResult.InvalidTarget
        val storeRef = blacklist ?: return BlacklistResult.Unavailable
        return if (storeRef.unblock(fp)) BlacklistResult.Success() else BlacklistResult.NotFound
    }

    /** 全部解除。 */
    suspend fun clearBlacklist(): BlacklistResult {
        val storeRef = blacklist ?: return BlacklistResult.Unavailable
        return BlacklistResult.Success(storeRef.clear())
    }

    // ══════════════ 新设备批准 ══════════════
    //
    // 请求方（通常是网页端）无法取得配对码：配对码只发送给已配对设备与机主配置的外部渠道，
    // 因此请求方无法自行批准自己的请求；这是该机制的核心约束。

    /**
     * 批准策略当前是否生效。
     *
     * 仅由 `pairingApprovalRequired` 决定。`pairingEnabled` 的语义是「是否启用配对数量限制」
     * （false 表示不限数量、仍允许配对），不能用作批准开关。
     */
    fun approvalRequired(): Boolean = settings.pairingApprovalRequired

    /**
     * 将批准策略落到待批准表。
     *
     * 策略关闭时清空所有 pending，返回清除的条数，供配置端点决定是否广播 `pairing:approvals`
     * 使 App 立即关闭弹窗。
     *
     * 必须真正删除而非仅过滤：pending 条目会驱动 App 弹窗与外部通知；仅过滤时，重新打开批准
     * 开关会使旧请求重新出现，且内存中持续保留无人处理的请求。
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
            // 关闭期间旧页面或旧客户端仍可能发起登记。此时不得产生会展示给机主的请求，同时
            // 清除历史 pending（与配置端点的清理形成双重保障）。
            approvalStore?.cancelPending()
            return null
        }
        val store = approvalStore ?: return null
        val fp = DeviceAuth.fingerprintOf(pubKeySpki.orEmpty().trim()) ?: return null
        return store.request(
            sid = sid,
            fingerprint = fp,
            // 设备名由请求方自报，且该端点免鉴权：不截断时一条请求即可在内存中留下一个巨大
            // 字符串（待批准条目最长保留 15 分钟）。弹窗只显示前两个字
            // （PairingNotificationMask），32 个字符足够。
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
            // App 每次进入页面或回到前台都会请求该列表；此处一并清空，覆盖「开关在 core 本机
            // 设置页关闭、未经过 PUT /api/pairing/config」的路径。
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
     * 该来源是否还能发起新的批准请求（限速）。
     *
     * 调用方只应在「确实是一条新请求」时查询：幂等命中已有 sid（Web 刷新页面）不应消耗配额，
     * 否则用户刷新几次页面即触发限速。
     */
    fun allowApprovalRequest(ip: String): Boolean = approvalStore?.allowNewRequest(ip) ?: true

    /** 现在还能不能发一条批准通知（全局限速：防陌生人靠不停发起请求刷屏机主）。 */
    fun allowApprovalNotification(): Boolean = approvalStore?.allowNotification() ?: false

    /**
     * 该来源是否还能查询一次待批准状态。
     *
     * 该端点免鉴权，请求方在等待期间依靠它轮询；该限制仅用于防止失控的循环，阈值较宽
     * （见 store 的实现）。
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
     * 本次 confirm 是否已获批准。
     *
     * 两条判据任一满足即可：请求已被 App 批准 / 回传的配对码正确。
     * （「请求方本身是已配对设备」在调用点判定，不在此处 —— 它不需要批准记录。）
     */
    private fun isApproved(sid: String?, fingerprint: String, code: String?): Boolean {
        // 批准策略关闭时 confirm 不会到达此处（见 confirm 的 0.7 段）；此处再判定一次，避免
        // 判定顺序变更后残留的 approved 记录绕过策略。
        if (!settings.pairingApprovalRequired) return false
        val store = approvalStore ?: return false
        val key = sid?.trim().orEmpty()
        if (key.isEmpty()) return false
        val pending = store.get(key) ?: return false
        // 必须是对此指纹的批准：防止使用其他设备的 sid 获得批准
        if (pending.fingerprint != fingerprint) return false
        if (pending.approved) return true
        return store.verifyCode(key, code)
    }

    /** 管理操作（移除设备 / 拉黑 / 解除拉黑）共用的密码校验，三处口径一致。 */
    private fun verifyAdminPassword(password: String?, ip: String): AdminAuth {
        if (password.isNullOrEmpty()) return AdminAuth.Missing
        if (passwordAttemptLimiter.isLocked(ip)) return AdminAuth.Locked
        if (!settings.verifyDevicePassword(password)) {
            return if (passwordAttemptLimiter.recordFailure(ip)) AdminAuth.Locked else AdminAuth.Wrong
        }
        passwordAttemptLimiter.reset(ip)
        return AdminAuth.Ok
    }

    /**
     * 破坏性操作的主人密码校验（供 `OwnerGate` 装配使用）。
     *
     * 与 [removeDevice] / [unpairAllWithPassword] / 拉黑管理**同一套判定与节流**：同一个
     * [PasswordAttemptLimiter]、同一个 `settings.verifyDevicePassword`。抽成 public 的唯一理由
     * 是让 route 层的「恢复出厂 / 恢复备份 / root shell / 删日志」也走这条路。
     *
     * 「设备没设过密码时放行」「总开关没打开时放行」都由调用方（`OwnerGate`）判，本方法不重复判
     * `devicePasswordSet`：判据只能有一处，否则两边分叉就会出现「设了密码仍放行」的洞。
     */
    fun verifyOwnerPassword(password: String?, ip: String): OwnerAuth =
        when (verifyAdminPassword(password, ip)) {
            AdminAuth.Ok -> OwnerAuth.Ok
            AdminAuth.Missing -> OwnerAuth.Missing
            AdminAuth.Wrong -> OwnerAuth.Wrong
            AdminAuth.Locked -> OwnerAuth.Locked
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
        // 批准策略与外部通知渠道，供客户端渲染开关。三个通知开关默认 false。
        "pairing_approval_required" to settings.pairingApprovalRequired,
        "pairing_notify_system" to settings.pairingNotifySystem,
        "pairing_notify_email" to settings.pairingNotifyEmail,
        "pairing_notify_webhook" to settings.pairingNotifyWebhook,
        // 破坏性操作是否额外要配对密码（OwnerGate）。默认 false；见 AppSettings.ownerGateEnabled。
        "owner_gate_enabled" to settings.ownerGateEnabled,
        "devices" to store.list().map { recordToPayload(it) }
    )

    /** 解除全部配对（旧端点兼容：清空指纹 + 记录 + 重新进入配对模式；不轮换 token，保持旧行为）。 */
    /**
     * 某台设备的配对被移除 / 被拉黑时的回调，参数是被移除的指纹（**null = 全部**）。
     *
     * 由装配层接到 `WebSocketManager.closeDeviceSessions`：删配对只改存储，
     * 已经建好的实时连接不会自己断 —— 被删的那台设备会一直"在线"收数据
     * （2026-11-14 实测：删掉模拟器的配对后，App 仍停在仪表盘、数据照常刷新）。
     */
    var onDeviceRevoked: ((String?) -> Unit)? = null

    fun unpairAll() {
        settings.unpair()
        store.clear()
        onDeviceRevoked?.invoke(null)
    }

    /**
     * 解除全部配对（带密码校验）。
     *
     * 与 [removeDevice] 同一套双因素判定：密码证明操作者是设备主人。缺少密码校验时，同一 WiFi
     * 下的任何客户端都能清空全部配对并使设备重新进入配对模式，属于未鉴权的破坏性操作。
     *
     * 计数器仅在密码校验成功后重置（[PasswordAttemptLimiter.reset]），否则攻击者可用不含密码的
     * unpair 请求将自己的失败计数清零，反而放大爆破空间。
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
     * 解除指定设备的配对（旧端点兼容：不校验密码、不轮换 token）。
     *
     * 仅 [unpairFingerprintWithPassword] 可用于 HTTP 路由：「踢掉一台」的门槛不应低于「踢掉
     * 全部」，免密码的旧端点会使双因素校验被绕过。本方法保留给内部调用与测试，路由层不得直接
     * 使用。
     */
    fun unpairFingerprint(fingerprint: String) {
        settings.unpairFingerprint(fingerprint)
        store.remove(fingerprint)
        onDeviceRevoked?.invoke(fingerprint)
    }

    /**
     * 解除指定设备的配对（带配对密码）。
     *
     * 与 [unpairAllWithPassword] 使用同一套判定：密码证明操作者是设备主人。用于取代免密码的旧
     * 端点：破坏性操作的两种入口必须具备一致的门槛。
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
     * [Success] 只带 token 与服务端计算出的指纹。不存在共享 HMAC 密钥：签名由设备私钥产生，
     * 服务端使用记录中的公钥验证。回显 [Success.fingerprint] 使客户端无需自行实现指纹算法
     * （客户端自算会成为第二个真相源）。
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
         * 本设备已被机主拉黑。
         *
         * 路由层映射为 403 + `DEVICE_BLOCKED`（而非 401）：401 会被客户端「密码错误 → 重试」的
         * 逻辑处理，用户只能看到无明确原因的失败；403 加独立错误码才能提示「本设备已被拉黑」。
         *
         * @param retryAfterSeconds 还需等待的秒数。0 表示未知或没有冷却。
         *   解除拉黑不清除冷却（最长 1 小时）；冷却策略本身公开，隐藏剩余时间只会让用户无法
         *   判断应等待 5 分钟还是 1 小时，并反复重试。
         */
        data class Blocked(val retryAfterSeconds: Long = 0L) : PairingConfirmResult()
        /**
         * 新设备需要机主批准。
         *
         * 路由层映射为 403 + `APPROVAL_REQUIRED`：请求方应提示「请在已配对设备上批准，或输入外部
         * 通知中的配对码」。仅在 `pairingApprovalRequired` 打开且已有已配对设备时出现。
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
    }

    /** 解除全部配对结果。 */
    sealed class UnpairResult {
        object Success : UnpairResult()
        object InvalidPassword : UnpairResult()
        object PasswordLocked : UnpairResult()
        object MissingPassword : UnpairResult()
    }

    /**
     * 黑名单写操作结果。
     *
     * 与 [RemoveResult] 分开而非复用：黑名单另有 [SelfBlock] / [Full] / [AlreadyBlocked] /
     * [Unavailable] 四种语义，复用会使「移除设备」的调用点被迫处理无关分支。
     */
    sealed class BlacklistResult {
        /** @param removed 仅「全部解除」使用：本次移除了几条；单条操作恒为 0。 */
        data class Success(val removed: Int = 0) : BlacklistResult()
        /** 已在名单里（幂等：只刷新名称/理由，不改变拉黑时间）。 */
        object AlreadyBlocked : BlacklistResult()
        object InvalidTarget : BlacklistResult()
        /** 拒绝拉黑本机：否则机主一次操作即把自己踢下线，且没有其他设备可以解除。 */
        object SelfBlock : BlacklistResult()
        object Full : BlacklistResult()
        object NotFound : BlacklistResult()
        /** 装配层未注入 store（功能未启用），不应发生。 */
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

/**
 * 主人密码校验的四种结果。
 *
 * 为什么不直接复用私有的 `AdminAuth`：它要跨出本文件，被 `:core:api` 的 route 层
 * （`OwnerGate`）与装配层（`ComponentFactory`）看见。四态与 HTTP 的对应关系只在
 * `OwnerGate` 一处定义（401 / 401 / 429），本类型只表达「判定结论」，不携带状态码。
 */
sealed interface OwnerAuth {
    /** 密码正确（计数已重置）。 */
    data object Ok : OwnerAuth

    /** 没带密码。 */
    data object Missing : OwnerAuth

    /** 密码错误（未达锁定阈值）。 */
    data object Wrong : OwnerAuth

    /** 该 IP 在窗口内错误次数已达上限，或本次错误触发了上限。 */
    data object Locked : OwnerAuth
}
