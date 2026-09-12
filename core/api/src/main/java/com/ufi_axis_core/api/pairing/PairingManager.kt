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
    private val passwordAttemptLimiter: PasswordAttemptLimiter = PasswordAttemptLimiter()
) {

    private val random = SecureRandom()

    /** 未消费的配对挑战：nonce → 下发时刻。一次性使用，过期自动清理。 */
    private val challenges = ConcurrentHashMap<String, Long>()

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
        hwId: String? = null
    ): PairingConfirmResult {
        val trimmedCode = code.trim()

        // 0. 设备身份验证：公钥 → 指纹（服务端计算），挑战一次性消费，再验签。
        //    顺序很重要：先算指纹（廉价）再消费挑战，避免公钥格式错误也白耗一个挑战。
        val fp = DeviceAuth.fingerprintOf(pubKeySpki.trim())
            ?: return PairingConfirmResult.InvalidDeviceKey
        if (!consumeChallenge(challenge.trim())) {
            return PairingConfirmResult.InvalidChallenge
        }
        if (!DeviceAuth.verifySignature(pubKeySpki.trim(), challenge.trim(), signature.trim())) {
            AppLogger.w(TAG, "配对验签失败 fp=$fp ip=$ip")
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
            return PairingConfirmResult.AlreadyPaired
        }

        // 2.5 初次配对可同时写入 Goform 后台连接配置（IP/端口/密码）；校验前置，避免部分落库
        when (validateGoformSettings(goformIp, goformPort, goformPassword)) {
            is GoformApplyResult.Failure -> return PairingConfirmResult.InvalidGoformConfig
            is GoformApplyResult.Success -> {}
        }

        // 3. 密码校验
        if (passwordAttemptLimiter.isLocked(ip)) {
            return PairingConfirmResult.PasswordLocked
        }
        val pw = password
        if (pw.isNullOrEmpty()) {
            return PairingConfirmResult.PasswordRequired
        }
        if (!settings.devicePasswordSet) {
            // 首次配对：请求密码即新密码（“设置密码”语义）
            // 对齐 change-password 的长度校验（防绕过前端直接调 API 设置超长密码）
            if (pw.length < MIN_PASSWORD_LENGTH || pw.length > MAX_PASSWORD_LENGTH) {
                return PairingConfirmResult.InvalidPassword
            }
            settings.setDevicePassword(pw)
        } else if (!settings.verifyDevicePassword(pw)) {
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
            is AppSettings.PairingConfirmResult.InvalidCode -> PairingConfirmResult.InvalidCode
            is AppSettings.PairingConfirmResult.AlreadyPaired -> PairingConfirmResult.AlreadyPaired
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
     */
    fun changePassword(
        oldPw: String,
        newPw: String,
        ip: String,
        goformIp: String? = null,
        goformPort: Int? = null,
        goformPassword: String? = null
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


    /** 解除指定设备配对（旧端点兼容：双写；不校验密码/不轮换 token，保持旧行为）。 */
    fun unpairFingerprint(fingerprint: String) {
        settings.unpairFingerprint(fingerprint)
        store.remove(fingerprint)
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
        data class Success(val token: String, val fingerprint: String) : PairingConfirmResult()
        object InvalidPassword : PairingConfirmResult()
        object PasswordLocked : PairingConfirmResult()
        object PasswordRequired : PairingConfirmResult()
        object InvalidGoformConfig : PairingConfirmResult()
        /** 公钥无法解析，或用该公钥验签挑战失败 */
        object InvalidDeviceKey : PairingConfirmResult()
        /** 挑战不存在 / 已被使用 / 已过期 —— 客户端应重新走 /pairing/challenge */
        object InvalidChallenge : PairingConfirmResult()
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
