package com.ufi_axis_core.controller.carrier

import android.content.Context
import com.ufi_axis_core.controller.carrier.mobile.MobileBillParser
import com.ufi_axis_core.controller.carrier.mobile.MobileCarrierClient
import com.ufi_axis_core.controller.carrier.mobile.MobileCarrierException
import com.ufi_axis_core.controller.carrier.unicom.UnicomAuthRequiredException
import com.ufi_axis_core.controller.carrier.unicom.UnicomBillParser
import com.ufi_axis_core.controller.carrier.unicom.UnicomCarrierException
import com.ufi_axis_core.controller.carrier.unicom.UnicomCarrierClient
import com.ufi_axis_core.controller.carrier.cloud.CloudCardApiException
import com.ufi_axis_core.controller.carrier.cloud.CloudCardAuthRequiredException
import com.ufi_axis_core.controller.carrier.cloud.CloudCardClient
import com.ufi_axis_core.controller.carrier.cloud.CloudCardNeedVerifyException
import com.ufi_axis_core.controller.carrier.cloud.CloudCardRateLimitedException
import com.ufi_axis_core.controller.carrier.cloud.IotProvider
import com.ufi_axis_core.controller.carrier.cloud.mapCloudCardBill
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/** 账单页状态；由 `CarrierRoutes` 序列化给 App/Web。 */
sealed interface CarrierStatus {
    data object NotConfigured : CarrierStatus
    data class LoginRequired(
        val carrier: CarrierKind?,
        val phoneMasked: String?,
        val message: String? = null,
    ) : CarrierStatus
    data class CodeSent(
        val phoneMasked: String,
        val expiresInSeconds: Int,
        val retryAfterSeconds: Int,
    ) : CarrierStatus
    data class QrPending(val status: String, val expiresInSeconds: Int) : CarrierStatus
    data class Ready(
        val carrier: CarrierKind,
        val phoneMasked: String,
        val lastFetchedAt: Long?,
    ) : CarrierStatus
    data class Error(val message: String) : CarrierStatus
}

data class UnicomQrStart(val png: ByteArray, val expiresInSeconds: Int)

data class UnicomQrPoll(val state: String, val message: String? = null)

class CarrierLoginRequiredException(message: String = "需要登录运营商账号") : Exception(message)

class CarrierCodeExpiredException(message: String = "验证码已过期，请重新获取") : Exception(message)

class CarrierRateLimitedException(
    val retryAfterSeconds: Long,
    val reason: String = "发送太频繁",
    /** true = 设备每日总额度用完，文案不带「请 N 秒后再试」（N 可能为小时级）。 */
    val dailyQuota: Boolean = false,
) : Exception(if (dailyQuota) reason else "$reason，请 ${retryAfterSeconds} 秒后再试")

class CarrierUpstreamException(message: String) : Exception(message)

/** 账号不存在（已删除 / id 拼写错误）。路由映射为 404，不得映射为 409「未登录」。 */
class CarrierAccountNotFoundException(message: String = "账号不存在") : Exception(message)

/**
 * 账号列表里的一项（脱敏）。
 *
 * `state` 与 `CarrierStatus` 系列对齐，用字符串是因为它要直接进 JSON：
 * `ready` / `login_required` / `not_configured` / `error`。
 */
data class CarrierAccountSummary(
    val id: String,
    val carrier: CarrierKind?,
    val phoneMasked: String,
    val label: String,
    val state: String,
    val createdAt: Long,
    /** `carrier` / `iot_card`。放在末尾并给默认值，保持旧位置参数构造兼容。 */
    val accessMethod: String = "carrier",
    /** 物联卡站点：`cloud` / `xj`；普通运营商为空串。 */
    val iotProvider: String = "",
)

/**
 * 账单结果与缓存元数据。
 *
 * [bill] 可能来自实时拉取，也可能是磁盘/内存中的最近一次成功数据：
 * - [loginRequired] = 当前没有有效会话，返回的是缓存；UI 必须同时显示数据与「需要登录」；
 * - [stale] = 实时刷新失败（网络/上游），返回的是旧缓存；
 * - [cachedAt] = 这份数据的拉取时刻（epoch ms）；
 * - [error] = stale 时的上游错误，用于提示。
 */
data class CarrierBillResult(
    val bill: CarrierBill,
    val loginRequired: Boolean = false,
    val stale: Boolean = false,
    val cachedAt: Long? = null,
    val error: String? = null,
)

/**
 * 运营商账单能力接口。
 *
 * 抽出接口的目的是使路由可测：`CarrierBillService` 依赖 Android Context/Keystore，
 * `CarrierRoutesTest` 不应为测试 HTTP 语义而启动 Android 运行时。
 *
 * ## 单账号方法与账号维度方法并存
 *
 * 上半部分（`status/switchAccount/.../logout`）为单账号语义，固定操作「当前账号」，
 * 旧 App / Web 继续调用，行为保持不变。
 *
 * 下半部分为账号维度方法，全部提供默认实现（抛 `UnsupportedOperationException`）：
 * 仅实现旧方法的 `FakeCarrierService` 与第三方实现无需修改即可编译，
 * 并自然降级为「多账号未装配」。
 */
interface CarrierService {
    // ── 旧接口（语义 = 当前账号）──────────────────────────────────────────
    fun status(): CarrierStatus
    suspend fun switchAccount(carrier: CarrierKind, phone: String)
    suspend fun requestMobileCode(phone: String): CarrierStatus.CodeSent
    suspend fun verifyMobileCode(code: String): CarrierStatus.Ready
    /**
     * 拉二维码。
     *
     * @param force true = 用户主动刷新，必须重新出码；
     *   false = 进入扫码页/切回扫码方式，有效期内复用已下发的那张。
     */
    suspend fun startUnicomQr(force: Boolean = false): UnicomQrStart
    suspend fun pollUnicomQr(): UnicomQrPoll

    /**
     * 联通服务密码登录。方法语义 = 当前/新建的联通账号。
     *
     * @throws com.ufi_axis_core.controller.carrier.unicom.UnicomPasswordException
     *   密码错误、或账号被联通强制短信登录（`forcedSms = true`）。
     */
    suspend fun loginUnicomWithPassword(phone: String, password: String): CarrierStatus =
        throw UnsupportedOperationException("多账号未装配")
    suspend fun bill(force: Boolean = false): CarrierBill
    fun logout()

    // ── 账号维度 ─────────────────────────────────────────────────────────
    fun accounts(): List<CarrierAccountSummary> =
        throw UnsupportedOperationException("多账号未装配")

    /**
     * 今日已发验证码条数（设备合计，手动发码 + 自动登录）。
     *
     * 默认 0 保持旧 Fake / 第三方实现兼容；`CarrierBillService` 用它做设备总额度展示。
     */
    fun smsSentToday(): Int = 0

    fun activeAccountId(): String? = null

    suspend fun activateAccount(accountId: String): CarrierStatus =
        throw UnsupportedOperationException("多账号未装配")

    suspend fun addAccount(
        carrier: CarrierKind,
        phone: String,
        label: String = "",
    ): CarrierAccountSummary = throw UnsupportedOperationException("多账号未装配")

    /**
     * 新增物联卡账号。卡号仅用于创建账号，随后需调用 [loginCloudCardFor]。
     * 默认实现抛 UnsupportedOperationException，旧 Fake/第三方实现无需修改。
     */
    suspend fun addIotAccount(
        cardNo: String,
        label: String = "",
        /** `cloud`（云上卡）/ `xj`（小鲸卡）；默认云上卡，保持旧调用兼容。 */
        provider: String = "cloud",
    ): CarrierAccountSummary = throw UnsupportedOperationException("物联卡未装配")

    /** 物联卡充值号登录；小鲸卡必须带 verify（本卡是绑定手机号后四位）。 */
    suspend fun loginCloudCardFor(
        accountId: String,
        cardNo: String,
        verify: String? = null,
    ): CarrierStatus = throw UnsupportedOperationException("物联卡未装配")

    suspend fun renameAccount(accountId: String, label: String): Unit =
        throw UnsupportedOperationException("多账号未装配")

    suspend fun removeAccount(accountId: String): Unit =
        throw UnsupportedOperationException("多账号未装配")

    fun statusOf(accountId: String): CarrierStatus =
        throw UnsupportedOperationException("多账号未装配")

    suspend fun requestMobileCodeFor(
        accountId: String,
        phone: String? = null,
    ): CarrierStatus.CodeSent = throw UnsupportedOperationException("多账号未装配")

    suspend fun verifyMobileCodeFor(accountId: String, code: String): CarrierStatus.Ready =
        throw UnsupportedOperationException("多账号未装配")

    suspend fun startUnicomQrFor(accountId: String, force: Boolean = false): UnicomQrStart =
        throw UnsupportedOperationException("多账号未装配")

    suspend fun pollUnicomQrFor(accountId: String): UnicomQrPoll =
        throw UnsupportedOperationException("多账号未装配")

    suspend fun loginUnicomWithPasswordFor(accountId: String, phone: String, password: String): CarrierStatus =
        throw UnsupportedOperationException("多账号未装配")

    suspend fun billOf(accountId: String, force: Boolean = false): CarrierBill =
        throw UnsupportedOperationException("多账号未装配")

    /**
     * 账单 + 缓存/登录状态。默认实现直接包装 [billOf]，旧 Fake/第三方实现无需修改；
     * `CarrierBillService` 覆盖它，用于「优先返回缓存、会话失效时仍返回最近数据」。
     */
    suspend fun billSnapshotOf(accountId: String, force: Boolean = false): CarrierBillResult =
        CarrierBillResult(billOf(accountId, force))

    fun logoutOf(accountId: String): Unit =
        throw UnsupportedOperationException("多账号未装配")
}

/**
 * 运营商账单编排：登录状态、发码/扫码、缓存、解析。
 *
 * 会话仅存在于 core：手机号/Cookie 加密落盘，客户端只获得状态与脱敏账单。
 * 移动与联通共用此入口；具体协议在各自 client/parser 中。
 *
 * ## 可变状态按账号分槽
 *
 * - **锁按账号切分**（`lockOf`）：同账号串行 —— 同一份 Cookie 并发刷营业厅会触发风控；
 *   跨账号并行 —— A 号取账单时 B 号登录无需排队。
 * - **pending / 缓存 / 内存镜像均按 accountId 分槽**：A 号扫码未完成时切换到 B 号，
 *   A 的 pending 不会被 B 覆盖。
 * - **旧方法一律委托到当前账号**：`status()` = `statusOf(activeId)`，
 *   `bill()` = `billOf(activeId)`，语义与单账号实现逐条对应。
 */
class CarrierBillService(
    context: Context,
    private val now: () -> Long = System::currentTimeMillis,
    /** 设备级账单策略；null = 测试/旧装配，不启用每日短信总额度。 */
    private val billSettings: CarrierBillSettingsStore? = null,
) : CarrierService, CarrierBillRefreshSource {
    private val store = CarrierSessionStore(context)
    private val limiter = CarrierRateLimiter(context)
    private val autoLoginStore = CarrierAutoLoginStore(context)

    private data class PendingMobile(
        val accountId: String,
        val phone: String,
        val sx: String,
        val client: MobileCarrierClient,
        val sentAt: Long,
        var attempts: Int = 0,
    )

    private data class PendingUnicom(
        val accountId: String,
        val client: UnicomCarrierClient,
        val startedAt: Long,
        /** 已下发的二维码 PNG：切换页面/登录方式时直接复用，不重新出码。 */
        val png: ByteArray,
    )

    /** 所有内存状态的护栏。锁的粒度只保护 Map 本身，真正的串行在 [lockOf]。 */
    private val stateGuard = Any()

    /** 每账号一把锁：同账号串行、跨账号并行。 */
    private val accountLocks = HashMap<String, Mutex>()

    private val pendingMobile = HashMap<String, PendingMobile>()
    private val pendingUnicom = HashMap<String, PendingUnicom>()

    /**
     * 账号内存镜像。
     *
     * 页面刷新或路由重进会重新调用 `status()`；若每次都只读 SharedPreferences，
     * 落盘延迟或读取失败会表现为「刚登录后又被要求登录」。镜像保证同一 core 进程内
     * 复用已建立的会话，磁盘仅承担跨进程重启后的恢复。
     */
    private val memoryAccounts = HashMap<String, StoredCarrierAccount>()

    private val cached = HashMap<String, CarrierBill>()
    private val cachedAt = HashMap<String, Long>()
    private val lastFetchAt = HashMap<String, Long>()

    /**
     * 清掉过期/放弃的挂起登录。
     *
     * 用户拉完码就关页面是最常见的行为，而 `PendingMobile` / `PendingUnicom` 里各攥着一个
     * client 实例（含 CookieJar）与整张二维码 PNG。原先只在「再次使用且发现过期」或删账号时
     * 才清，等于放弃扫码后它们常驻进程。这里在每次新发起登录时顺带扫一遍。
     */
    private fun prunePending(nowMs: Long) {
        synchronized(stateGuard) {
            pendingMobile.entries.removeIf { nowMs - it.value.sentAt > CODE_TTL_SECONDS * 1000L }
            pendingUnicom.entries.removeIf { nowMs - it.value.startedAt > QR_TTL_SECONDS * 1000L }
        }
    }

    private fun lockOf(accountId: String): Mutex = synchronized(stateGuard) {
        accountLocks.getOrPut(accountId) { Mutex() }
    }

    /**
     * 账号「代际号」：**删除 / 退出登录时递增**。
     *
     * 一次账单刷新要串行打好几个上游接口（移动 8 个，最坏上百秒）。这期间用户完全可能
     * 点「退出登录」或删号 —— 而回写用的是请求开始时抓到的账号快照，直接落盘就会把刚清掉的
     * Cookie 写回去：已注销的会话重新变 Ready，已删除的账号带着 Cookie 复活。
     *
     * 所以长流程开始时抓一份代际号，回写前比对；不相等就**只把结果返回给调用方、不落盘**。
     */
    private val accountEpoch = HashMap<String, Long>()

    private fun epochOf(accountId: String): Long = synchronized(stateGuard) {
        accountEpoch[accountId] ?: 0L
    }

    private fun bumpEpoch(accountId: String) {
        synchronized(stateGuard) { accountEpoch[accountId] = (accountEpoch[accountId] ?: 0L) + 1L }
    }

    private fun carrierOf(raw: String): CarrierKind? =
        runCatching { CarrierKind.valueOf(raw.uppercase()) }.getOrNull()

    private fun readAccount(accountId: String): StoredCarrierAccount? {
        synchronized(stateGuard) { memoryAccounts[accountId] }?.let { return it }
        val loaded = store.account(accountId) ?: return null
        synchronized(stateGuard) { memoryAccounts[accountId] = loaded }
        return loaded
    }

    private fun writeAccount(account: StoredCarrierAccount) {
        synchronized(stateGuard) { memoryAccounts[account.id] = account }
        store.put(account)
    }

    private fun requireAccount(accountId: String): StoredCarrierAccount =
        readAccount(accountId) ?: throw CarrierAccountNotFoundException()

    /** 只改某个账号的字段，id 不变（id 漂移会让客户端手里的 id 失效）。 */
    private fun mutateAccount(accountId: String, transform: (StoredCarrierAccount) -> StoredCarrierAccount) {
        val current = store.account(accountId) ?: return
        writeAccount(transform(current))
    }

    /** 鉴权失败：只清 Cookie，保留账号用于显示脱敏号码。 */
    private fun clearCookies(accountId: String) {
        mutateAccount(accountId) {
            it.copy(cookies = emptyList(), savedAt = now())
        }
        synchronized(stateGuard) {
            cached.remove(accountId)
            cachedAt.remove(accountId)
            lastFetchAt.remove(accountId)
        }
    }

    // ─────────────────────── 旧接口：全部委托到当前账号 ───────────────────────

    override fun status(): CarrierStatus {
        val id = store.activeId() ?: return CarrierStatus.NotConfigured
        return statusOf(id)
    }

    override suspend fun switchAccount(carrier: CarrierKind, phone: String) {
        requirePhone(phone)
        // 换号 = 查找或创建该号码对应的账号并设为当前。旧账号保留在存储中，
        // 账号列表接口因此仍会返回它（多账号的预期行为）。
        val account = upsertAccount(carrier, phone, label = "")
        store.setActive(account.id)
        synchronized(stateGuard) {
            pendingMobile.clear()
            pendingUnicom.clear()
        }
    }

    override suspend fun requestMobileCode(phone: String): CarrierStatus.CodeSent {
        requirePhone(phone)
        val account = upsertAccount(CarrierKind.MOBILE, phone, label = "")
        store.setActive(account.id)
        return requestMobileCodeFor(account.id, phone)
    }

    override suspend fun verifyMobileCode(code: String): CarrierStatus.Ready {
        val id = store.activeId() ?: throw CarrierCodeExpiredException()
        return verifyMobileCodeFor(id, code)
    }

    override suspend fun startUnicomQr(force: Boolean): UnicomQrStart {
        // 旧语义：更换/重登同一联通号。当前账号已是联通时复用（重登）；
        // 否则创建无号账号并设为当前。
        val active = store.activeId()?.let { readAccount(it) }
        val account = if (active != null && carrierOf(active.carrier) == CarrierKind.UNICOM) {
            active
        } else {
            upsertAccount(CarrierKind.UNICOM, phone = "", label = "").also { store.setActive(it.id) }
        }
        return startUnicomQrFor(account.id, force)
    }

    override suspend fun loginUnicomWithPassword(phone: String, password: String): CarrierStatus {
        requirePhone(phone)
        val active = store.activeId()?.let { readAccount(it) }
        val account = if (active != null && carrierOf(active.carrier) == CarrierKind.UNICOM) {
            active
        } else {
            upsertAccount(CarrierKind.UNICOM, phone = "", label = "").also { store.setActive(it.id) }
        }
        return loginUnicomWithPasswordFor(account.id, phone, password)
    }

    override suspend fun pollUnicomQr(): UnicomQrPoll {
        val id = store.activeId() ?: return UnicomQrPoll("expired", "二维码已过期，请刷新")
        return pollUnicomQrFor(id)
    }

    override suspend fun bill(force: Boolean): CarrierBill {
        val id = store.activeId() ?: throw CarrierLoginRequiredException()
        return billOf(id, force)
    }

    override fun logout() {
        store.activeId()?.let { logoutOf(it) }
    }

    // ─────────────────────── 账号维度 ───────────────────────

    override fun smsSentToday(): Int = limiter.sendsToday(now())

    override fun accounts(): List<CarrierAccountSummary> =
        store.accounts().map { entry ->
            val status = statusOf(entry.id)
            CarrierAccountSummary(
                id = entry.id,
                carrier = carrierOf(entry.carrier),
                accessMethod = entry.accessMethod,
                iotProvider = entry.iotProvider,
                phoneMasked = entry.phoneMasked,
                label = entry.label,
                state = when (status) {
                    is CarrierStatus.Ready -> "ready"
                    is CarrierStatus.LoginRequired -> "login_required"
                    CarrierStatus.NotConfigured -> "not_configured"
                    else -> "error"
                },
                createdAt = entry.createdAt,
            )
        }

    override fun activeAccountId(): String? = store.activeId()

    /**
     * 后台刷新目标：只挑**当前有会话**的账号。
     *
     * 没有 Cookie 的账号刷新也只会拿到"需要登录"；跳过可以避免每轮无意义的上游调用。
     * 缓存本身仍然保留，App 打开时由 [billSnapshotOf] 返回。
     */
    override fun refreshTargets(): List<String> =
        store.accounts()
            .map { it.id }
            .filter { id -> store.account(id)?.cookies?.isNotEmpty() == true }

    override suspend fun activateAccount(accountId: String): CarrierStatus {
        if (store.account(accountId) == null) throw CarrierAccountNotFoundException()
        store.setActive(accountId)
        return statusOf(accountId)
    }

    override suspend fun addIotAccount(
        cardNo: String,
        label: String,
        provider: String,
    ): CarrierAccountSummary {
        val digits = cardNo.filter(Char::isDigit)
        require(digits.length in 4..32) { "充值号格式不正确" }
        val p = IotProvider.of(provider)
        val account = upsertAccount(
            carrier = CarrierKind.IOT_CARD,
            phone = digits,
            label = label,
            accessMethod = "iot_card",
            iotProvider = p.id,
            // 老云上卡账号的 id 是 sha256("iot_card:<card>")，必须保持原 seed 才能认领旧数据；
            // 新站点用独立 seed，避免同一充值号在两个站点互相覆盖。
            idSeed = if (p == IotProvider.CLOUD) "iot_card" else "iot_${p.id}",
        )
        store.setActive(account.id)
        return summaryOf(account)
    }

    override suspend fun loginCloudCardFor(
        accountId: String,
        cardNo: String,
        verify: String?,
    ): CarrierStatus = lockOf(accountId).withLock {
        val account = requireAccount(accountId)
        require(account.accessMethod == "iot_card") { "该账号不是物联卡" }
        val digits = cardNo.filter(Char::isDigit)
        val target = digits.ifBlank { account.phone }
        require(target.length in 4..32) { "充值号格式不正确" }
        require(account.phone.isBlank() || account.phone == target) {
            "充值号与账号不一致；如需换卡请重新添加账号"
        }

        val provider = IotProvider.of(account.iotProvider)
        val client = CloudCardClient(baseUrl = provider.baseUrl)
        try {
            client.login(target, verify?.trim()?.takeIf { it.isNotEmpty() })
        } catch (e: CloudCardNeedVerifyException) {
            val what = when (e.verifyType) {
                0 -> "安全密码"
                1 -> "短信验证码"
                2 -> "手机号后四位"
                else -> "验证信息"
            }
            throw CarrierUpstreamException(
                if (verify.isNullOrBlank()) {
                    val hint = e.maskedPhone?.takeIf { it.isNotBlank() }?.let { "（绑定号 $it）" }.orEmpty()
                    "${provider.displayName}需要$what 验证$hint，请输入后重试"
                } else {
                    "${provider.displayName}验证失败：$what 不正确"
                }
            )
        } catch (e: CloudCardRateLimitedException) {
            throw CarrierUpstreamException("${provider.displayName}登录过于频繁，请稍后再试")
        } catch (e: CloudCardApiException) {
            throw CarrierUpstreamException("${provider.displayName}登录失败：${e.message}")
        }
        val cookies = client.cookieJar.snapshot()
        if (cookies.isEmpty()) {
            throw CarrierUpstreamException("${provider.displayName}登录成功但没有拿到会话 Cookie，请重试")
        }
        writeAccount(
            account.copy(
                phone = target,
                accessMethod = "iot_card",
                carrier = CarrierKind.IOT_CARD.name.lowercase(),
                iotProvider = provider.id,
                cookies = cookies,
                savedAt = now(),
                // 重新登录后下一次取账单必须重新拉，不能沿用旧账号的 lastFetchedAt。
                lastFetchedAt = 0,
            )
        )
        synchronized(stateGuard) {
            cached.remove(accountId)
            cachedAt.remove(accountId)
            lastFetchAt.remove(accountId)
        }
        CarrierStatus.Ready(
            CarrierKind.IOT_CARD,
            maskIotCardNo(target),
            null,
        )
    }

    override suspend fun addAccount(
        carrier: CarrierKind,
        phone: String,
        label: String,
    ): CarrierAccountSummary {
        val digits = phone.filter(Char::isDigit)
        require(digits.isBlank() || PHONE_PATTERN.matches(digits)) { "手机号格式不正确" }
        val account = upsertAccount(carrier, digits, label)
        store.setActive(account.id)
        return summaryOf(account)
    }

    override suspend fun renameAccount(accountId: String, label: String) {
        requireAccount(accountId)
        store.setLabel(accountId, label.trim())
        synchronized(stateGuard) { memoryAccounts.remove(accountId) }
    }

    override suspend fun removeAccount(accountId: String) {
        // 先递增代际号：在途刷新随后的回写会被丢弃，不会把账号连带 Cookie 重建出来。
        bumpEpoch(accountId)
        // 只清该账号的分钟/小时/天计数；send_global 保留（今天真的发过，总额度不能回退）。
        limiter.clear(accountId)
        store.remove(accountId)
        synchronized(stateGuard) {
            pendingMobile.remove(accountId)
            pendingUnicom.remove(accountId)
            memoryAccounts.remove(accountId)
            cached.remove(accountId)
            cachedAt.remove(accountId)
            lastFetchAt.remove(accountId)
            // 锁**不能**在还有人在用时移除：在途协程仍持有旧 Mutex，同名 id 重建后会拿到
            // 新 Mutex，同一个账号就被允许并发打上游（运营商侧按号码风控）。
            accountLocks[accountId]?.let { if (!it.isLocked) accountLocks.remove(accountId) }
        }
    }

    override fun statusOf(accountId: String): CarrierStatus {
        val account = readAccount(accountId) ?: return CarrierStatus.NotConfigured
        // 先看有没有**进行中**的登录：发码/出码不再清空旧 Cookie（见 requestMobileCodeFor 的注释），
        // 所以状态不能只看 cookies —— 否则重新登录的过程中会被显示成「已就绪」。
        val nowMs = now()
        val pendingCode = synchronized(stateGuard) { pendingMobile[accountId] }
        if (pendingCode != null && nowMs - pendingCode.sentAt < CODE_TTL_SECONDS * 1000L) {
            return CarrierStatus.CodeSent(maskCarrierPhone(pendingCode.phone), CODE_TTL_SECONDS, 0)
        }
        val pendingQr = synchronized(stateGuard) { pendingUnicom[accountId] }
        if (pendingQr != null && nowMs - pendingQr.startedAt < QR_TTL_SECONDS * 1000L) {
            val remain = ((QR_TTL_SECONDS * 1000L - (nowMs - pendingQr.startedAt)) / 1000L).toInt().coerceAtLeast(1)
            return CarrierStatus.QrPending("waiting", remain)
        }
        if (account.accessMethod == "iot_card") {
            val masked = maskIotCardNo(account.phone)
            val provider = IotProvider.of(account.iotProvider)
            return if (account.cookies.isEmpty()) {
                CarrierStatus.LoginRequired(
                    CarrierKind.IOT_CARD,
                    masked,
                    message = "${provider.displayName}会话已失效，请重新登录",
                )
            } else {
                CarrierStatus.Ready(
                    CarrierKind.IOT_CARD,
                    masked,
                    account.lastFetchedAt.takeIf { it > 0 },
                )
            }
        }
        val carrier = carrierOf(account.carrier)
        val masked = account.phone.takeIf { it.isNotBlank() }?.let { maskCarrierPhone(it) }
        if (account.cookies.isEmpty()) {
            return if (carrier == null && masked == null) {
                CarrierStatus.NotConfigured
            } else {
                CarrierStatus.LoginRequired(
                    carrier,
                    masked,
                    message = if (account.phone.isNotBlank()) "运营商会话已失效，请重新登录" else null,
                )
            }
        }
        carrier ?: return CarrierStatus.Error("未知运营商：${account.carrier}")
        return CarrierStatus.Ready(
            carrier,
            maskCarrierPhone(account.phone),
            account.lastFetchedAt.takeIf { it > 0 },
        )
    }

    override suspend fun requestMobileCodeFor(
        accountId: String,
        phone: String?,
    ): CarrierStatus.CodeSent = lockOf(accountId).withLock {
        val account = requireAccount(accountId)
        val target = phone?.filter(Char::isDigit)?.takeIf { it.isNotBlank() } ?: account.phone
        requirePhone(target)

        // "先建号后填号"（例如先按联通建了空号账号、再走移动登录）时把号补进账号，
        // 但**不改 id** —— id 漂移会让客户端手里的 id 失效。
        if (account.phone != target) {
            writeAccount(account.copy(phone = target))
        }

        // 设备总额度：手动发码与自动登录共用同一计数（CarrierRateLimiter 的 send_global）。
        val dailyLimit = billSettings?.get()?.dailySmsLimit
        if (dailyLimit != null && limiter.sendsToday(now()) >= dailyLimit) {
            throw CarrierRateLimitedException(
                secondsUntilLocalMidnight(now()),
                "今日短信额度已用完（上限 $dailyLimit 条/日）",
                dailyQuota = true,
            )
        }
        val wait = limiter.checkSending(accountId, now())
        if (wait > 0) throw CarrierRateLimitedException(wait)
        limiter.recordSending(accountId, now())

        prunePending(now())
        val client = MobileCarrierClient()
        val sx = client.sendCode(target)
        synchronized(stateGuard) {
            pendingMobile[accountId] = PendingMobile(accountId, target, sx, client, now())
        }
        // **不再清空旧 Cookie**：发码只是"开始重新登录"，用户很可能中途放弃（没收到码、
        // 改主意、手滑点了按钮）。之前在这里就清 Cookie，等于一次误触就把现有会话弄丢，
        // 且没有任何恢复路径。旧会话保留，状态由上面的 pendingMobile 覆盖成 code_sent；
        // 真正换会话发生在 verifyMobileCodeFor 成功之后。
        CarrierStatus.CodeSent(maskCarrierPhone(target), CODE_TTL_SECONDS, SEND_COOLDOWN_SECONDS)
    }

    override suspend fun verifyMobileCodeFor(
        accountId: String,
        code: String,
    ): CarrierStatus.Ready = lockOf(accountId).withLock {
        val pending = synchronized(stateGuard) { pendingMobile[accountId] }
            ?: throw CarrierCodeExpiredException()
        if (now() - pending.sentAt > CODE_TTL_SECONDS * 1000L) {
            synchronized(stateGuard) { pendingMobile.remove(accountId) }
            throw CarrierCodeExpiredException()
        }
        if (pending.attempts >= MAX_CODE_ATTEMPTS) {
            synchronized(stateGuard) { pendingMobile.remove(accountId) }
            throw CarrierCodeExpiredException("验证码错误次数过多，请重新获取")
        }
        pending.attempts += 1

        pending.client.login(pending.phone, code, pending.sx)

        val cookies = pending.client.cookieJar.snapshot()
        if (cookies.isEmpty()) {
            synchronized(stateGuard) { pendingMobile.remove(accountId) }
            throw MobileCarrierException(
                null, "登录接口成功但没有拿到会话 Cookie，请重试"
            )
        }
        val current = requireAccount(accountId)
        writeAccount(
            current.copy(
                phone = pending.phone,
                cookies = cookies,
                savedAt = now(),
                lastFetchedAt = 0,
            )
        )
        synchronized(stateGuard) {
            cached.remove(accountId)
            cachedAt.remove(accountId)
            lastFetchAt.remove(accountId)
            pendingMobile.remove(accountId)
        }
        // 手动验证码登录也算一次成功登录：否则"重登间隔"对这类账号永远不生效。
        autoLoginStore.setLastLoginAt(accountId, now())
        CarrierStatus.Ready(CarrierKind.MOBILE, maskCarrierPhone(pending.phone), null)
    }

    override suspend fun startUnicomQrFor(accountId: String, force: Boolean): UnicomQrStart = lockOf(accountId).withLock {
        val account = requireAccount(accountId)
        if (carrierOf(account.carrier) != CarrierKind.UNICOM) {
            throw IllegalArgumentException("该账号不是联通账号")
        }
        val nowMs = now()
        // 缓存复用：有效期内重复进入扫码页或切换登录方式，均复用已下发的二维码。
        // 重新出码会使旧码立即失效，此前完成的扫码步骤随之作废。
        // force=true 仅来自「刷新二维码」，此时必须生成新码，不使用缓存。
        val cached = if (force) null else synchronized(stateGuard) { pendingUnicom[accountId] }
        if (cached != null) {
            val age = nowMs - cached.startedAt
            if (age in 0 until QR_TTL_SECONDS * 1000L) {
                val remain = ((QR_TTL_SECONDS * 1000L - age) / 1000L).toInt().coerceAtLeast(1)
                return@withLock UnicomQrStart(cached.png, remain)
            }
            synchronized(stateGuard) { pendingUnicom.remove(accountId) }
        }
        prunePending(nowMs)
        val client = UnicomCarrierClient()
        val png = client.startQr()
        synchronized(stateGuard) {
            pendingUnicom[accountId] = PendingUnicom(accountId, client, nowMs, png)
        }
        // 同 requestMobileCodeFor：出码不代表登录成功，不清旧会话。
        UnicomQrStart(png, QR_TTL_SECONDS)
    }

    override suspend fun pollUnicomQrFor(accountId: String): UnicomQrPoll = lockOf(accountId).withLock {
        val pending = synchronized(stateGuard) { pendingUnicom[accountId] }
            ?: return@withLock UnicomQrPoll("expired", "二维码已过期，请刷新")
        if (now() - pending.startedAt > QR_TTL_SECONDS * 1000L) {
            synchronized(stateGuard) { pendingUnicom.remove(accountId) }
            return@withLock UnicomQrPoll("expired", "二维码已过期，请刷新")
        }
        val code = pending.client.pollQr()
        when (code) {
            "00" -> UnicomQrPoll("waiting")
            "10" -> UnicomQrPoll("scanned", "已扫码，请在手机上确认")
            "11" -> {
                val phone = pending.client.completeLogin()
                val cookies = pending.client.cookieJar.snapshot()
                if (cookies.isEmpty()) {
                    synchronized(stateGuard) { pendingUnicom.remove(accountId) }
                    return@withLock UnicomQrPoll("error", "扫码成功但没有拿到会话 Cookie，请重试")
                }
                val current = requireAccount(accountId)
                writeAccount(
                    current.copy(
                        // 运营商可能不回号：保持空串，UI 以别名 /「联通账号」作为回退显示。
                        phone = phone.orEmpty().filter(Char::isDigit),
                        cookies = cookies,
                        savedAt = now(),
                        lastFetchedAt = 0,
                    )
                )
                synchronized(stateGuard) {
                    cached.remove(accountId)
                    cachedAt.remove(accountId)
                    lastFetchAt.remove(accountId)
                    pendingUnicom.remove(accountId)
                }
                UnicomQrPoll("ready")
            }
            "02" -> {
                synchronized(stateGuard) { pendingUnicom.remove(accountId) }
                UnicomQrPoll("expired", "等待授权超时，请刷新二维码")
            }
            "14" -> {
                synchronized(stateGuard) { pendingUnicom.remove(accountId) }
                UnicomQrPoll("error", "该账号需先在联通 App 登录后再扫码")
            }
            "-1" -> {
                synchronized(stateGuard) { pendingUnicom.remove(accountId) }
                UnicomQrPoll("error", "扫码登录失败，请刷新重试")
            }
            else -> UnicomQrPoll("waiting")
        }
    }

    /**
     * 联通服务密码登录。
     *
     * 与扫码的差异：得到的是 App 域会话（`m.client.10010.com`），不含 JUT/商城票。
     * 因此写入账号时同时保存 `token_online` 与 `appId`，会话过期后可静默续期。
     */
    override suspend fun loginUnicomWithPasswordFor(
        accountId: String,
        phone: String,
        password: String,
    ): CarrierStatus = lockOf(accountId).withLock {
        val account = requireAccount(accountId)
        if (carrierOf(account.carrier) != CarrierKind.UNICOM) {
            throw IllegalArgumentException("该账号不是联通账号")
        }
        // 明细页重登录时客户端可能不回填完整手机号；phone 为空时使用账号中缓存的号码。
        val normalized = phone.filter(Char::isDigit).take(11).takeIf { it.length == 11 }
            ?: account.phone.filter(Char::isDigit).take(11)
        require(normalized.length == 11) { "请输入 11 位手机号" }
        require(password.isNotBlank()) { "请输入服务密码" }

        val client = UnicomCarrierClient()
        client.loginWithPassword(normalized, password)
        val cookies = client.cookieJar.snapshot()
        if (cookies.isEmpty()) throw UnicomCarrierException("登录成功但没有拿到会话 Cookie")

        writeAccount(
            account.copy(
                phone = normalized,
                cookies = cookies,
                savedAt = now(),
                lastFetchedAt = 0,
                tokenOnline = client.tokenOnline(),
                appId = client.appId(),
            )
        )
        synchronized(stateGuard) {
            cached.remove(accountId)
            cachedAt.remove(accountId)
            lastFetchAt.remove(accountId)
        }
        CarrierStatus.Ready(CarrierKind.UNICOM, maskCarrierPhone(normalized), null)
    }

    override suspend fun billOf(accountId: String, force: Boolean): CarrierBill =
        lockOf(accountId).withLock {
            val epoch = epochOf(accountId)
            val account = requireAccount(accountId)
            if (account.cookies.isEmpty()) throw CarrierLoginRequiredException()
            if (account.accessMethod == "iot_card") {
                return@withLock cloudBillOf(accountId, account, force)
            }

            val nowMs = now()
            val cachedBill = synchronized(stateGuard) { cached[accountId] }
            val cachedTime = synchronized(stateGuard) { cachedAt[accountId] ?: 0L }
            val lastFetch = synchronized(stateGuard) { lastFetchAt[accountId] ?: 0L }
            if (!force && cachedBill != null && nowMs - cachedTime < CACHE_TTL_SECONDS * 1000L) {
                return@withLock cachedBill
            }
            if (force && cachedBill != null && nowMs - lastFetch < MIN_REFRESH_SECONDS * 1000L) {
                return@withLock cachedBill
            }

            val carrier = carrierOf(account.carrier)
                ?: throw CarrierLoginRequiredException("未知运营商")

            var updatedCookies = account.cookies
            var updatedUnicomToken: String? = null
            var updatedUnicomAppId: String? = null
            val bill = when (carrier) {
                CarrierKind.MOBILE -> {
                    val client = MobileCarrierClient()
                    client.cookieJar.restore(account.cookies)
                    val raw = client.fetch(account.phone)
                    val authError = firstAuthFailure(raw)
                    // 单个接口返回鉴权码不足以清理整份 Cookie：移动这 8 个接口中，
                    // 积分/账单/套餐等非核心接口单独鉴权失败较常见，整号下线后需重新收短信。
                    // 仅当所有接口均失败时才判定会话失效；
                    // 单接口失败交由 parser 作为 warning 处理，下一轮后台刷新再修正。
                    val allEndpointsFailed = raw.isNotEmpty() &&
                        raw.values.all { it["__error"] != null }
                    if (authError != null && allEndpointsFailed) {
                        clearCookies(accountId)
                        throw CarrierLoginRequiredException("移动会话已失效，请重新登录")
                    }
                    // app-plan 是主数据源；其失败时返回余额/空流量会被误读为「0 GB」，
                    // 因此宁可让页面显示错误并允许重试，也不把空数据当作成功。
                    (raw["app-plan"]?.get("__error") as? kotlinx.serialization.json.JsonPrimitive)
                        ?.contentOrNull?.let { planError ->
                            throw CarrierUpstreamException("流量余量获取失败：$planError")
                        }
                    if (raw.isNotEmpty() && raw.values.all { it["__error"] != null }) {
                        val first = raw.values.firstNotNullOfOrNull {
                            (it["__error"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
                        }
                        throw CarrierUpstreamException("移动接口全部失败：${first ?: "未知原因"}")
                    }
                    updatedCookies = client.cookieJar.snapshot()
                    val parsed = MobileBillParser.parse(raw, account.phone)
                    if (parsed.flow.totalGb == 0.0 && parsed.packages.isEmpty() && parsed.balanceYuan == null) {
                        // 空数据不等同于会话失效：部分账户确实没有可展示的流量包/余额，
                        // 部分为单接口临时失败。此处不清 Cookie，避免把可恢复的上游波动
                        // 升级为「需要重新登录」；调用方应收到可重试的上游错误。
                        throw CarrierUpstreamException("移动未返回账单数据，请稍后重试")
                    }
                    parsed
                }
                CarrierKind.UNICOM -> {
                    val client = UnicomCarrierClient()
                    client.cookieJar.restore(account.cookies)
                    client.restoreToken(account.tokenOnline, account.appId)
                    // 已验证的保活方式：每次取数前先用 token_online 调用一次
                    // onLine.htm（轻量续期），而非等待 cookie 过期后被动续期。
                    // 无 token 的纯扫码账号无法静默续期，只能重新扫码。
                    if (account.tokenOnline.isNotBlank()) {
                        val renewed = runCatching { client.renew() }.getOrDefault(false)
                        if (renewed) {
                            // 先落盘再查询：续期已获得新 Cookie/token，
                            // 后续查询即使网络失败，也不应丢弃本次续期结果。
                            writeAccount(
                                (readAccount(accountId) ?: account).copy(
                                    cookies = client.cookieJar.snapshot(),
                                    tokenOnline = client.tokenOnline(),
                                    appId = client.appId(),
                                    savedAt = now(),
                                )
                            )
                        }
                    }
                    val (pkg, balance) = try {
                        client.fetch()
                    } catch (_: UnicomAuthRequiredException) {
                        // 主动续期可能失败或换票尚未生效；此处再提供一次续期机会。
                        val renewed = account.tokenOnline.isNotBlank() &&
                            runCatching { client.renew() }.getOrDefault(false)
                        if (renewed) {
                            // 同上：被动续期成功后先保存，再发第二次查询。
                            writeAccount(
                                (readAccount(accountId) ?: account).copy(
                                    cookies = client.cookieJar.snapshot(),
                                    tokenOnline = client.tokenOnline(),
                                    appId = client.appId(),
                                    savedAt = now(),
                                )
                            )
                            runCatching { client.fetch() }.getOrElse {
                                clearCookies(accountId)
                                throw CarrierLoginRequiredException("联通会话已失效，请重新登录")
                            }
                        } else {
                            clearCookies(accountId)
                            throw CarrierLoginRequiredException("联通会话已失效，请重新扫码或使用服务密码登录")
                        }
                    }
                    updatedCookies = client.cookieJar.snapshot()
                    updatedUnicomToken = client.tokenOnline()
                    updatedUnicomAppId = client.appId()
                    UnicomBillParser.parse(pkg, balance, account.phone.ifBlank { "联通账号" })
                }
                // 电信/广电已在运营商枚举中，但协议尚未接入：此处显式返回「接入中」，
                // 避免分支遗漏或误用其他运营商的协议。
                CarrierKind.TELECOM, CarrierKind.BROADNET ->
                    throw CarrierUpstreamException(
                        "${CarrierSimCatalog.displayName(carrier)}账单接入中，暂不支持查询"
                    )
                // 物联卡在 billOf 开头按 accessMethod 分流；到达此分支说明账号数据不一致。
                CarrierKind.IOT_CARD ->
                    throw CarrierUpstreamException("物联卡账号缺少 access_method，无法查询")
            }

            // 只记形状不记金额：WARN 恒落盘、可经 /api/debug-logs 取走，余额/流量属财务数据。
            com.ufi_axis_core.util.AppLogger.d(
                "CarrierBill",
                "parsed account=$accountId carrier=${bill.carrier} " +
                    "hasBalance=${bill.balanceYuan != null} hasCurrent=${bill.currentBillYuan != null} " +
                    "packages=${bill.packages.size} hasVoice=${bill.voice != null} hasSms=${bill.sms != null} " +
                    "warnings=${bill.warnings.size}"
            )
            // 这期间账号可能已被删除/注销（代际号变了）：只把结果返回给调用方，不落盘。
            if (epochOf(accountId) != epoch) {
                com.ufi_axis_core.util.AppLogger.i(
                    "CarrierBill",
                    "刷新期间账号已被删除/注销，跳过回写 account=$accountId"
                )
                return bill
            }
            synchronized(stateGuard) {
                cached[accountId] = bill
                cachedAt[accountId] = nowMs
                lastFetchAt[accountId] = nowMs
            }
            writeAccount(
                (readAccount(accountId) ?: account).copy(
                    cookies = updatedCookies,
                    // 联通 renew 可能在响应中返回新的 token_online；仅落 Cookie 而丢弃新 token
                    // 会使下一次续期仍使用旧 token，导致会话很快失效。
                    tokenOnline = if (carrier == CarrierKind.UNICOM) {
                        updatedUnicomToken ?: account.tokenOnline
                    } else {
                        account.tokenOnline
                    },
                    appId = if (carrier == CarrierKind.UNICOM) {
                        updatedUnicomAppId ?: account.appId
                    } else {
                        account.appId
                    },
                    lastFetchedAt = nowMs,
                )
            )
            // 磁盘缓存：后台刷新/手动刷新成功后，下次打开先给这份数据。
            // 与内存缓存不同，它跨 core 重启；登录失效时也靠它显示"最近一次数据"。
            store.saveCachedBill(accountId, CachedCarrierBill(bill, nowMs))
            bill
        }

    /**
     * 账单 + 缓存/登录状态。
     *
     * 打开页面（force=false）时优先返回磁盘缓存，不等待营业厅/物联卡接口：
     * - 有会话：直接返回缓存；实时刷新由后台 `CarrierBillRefresher` 负责；
     * - 无会话：仍返回缓存，`loginRequired=true`，UI 同时显示数据与「需要登录」；
     * - 无缓存：才执行实时拉取（首次使用/刚添加账号）。
     *
     * force=true（下拉/手动刷新）：执行实时拉取；会话失效或上游失败时回退到缓存，
     * 分别标记 `loginRequired` / `stale`，不将整页变为错误。
     *
     * `stale` 仅表示实时刷新失败；缓存年龄由 `cachedAt` 表达，两者不可混用。
     */
    override suspend fun billSnapshotOf(accountId: String, force: Boolean): CarrierBillResult {
        val account = readAccount(accountId) ?: throw CarrierAccountNotFoundException()
        val cached = store.cachedBill(accountId)

        if (!force && cached != null) {
            // 打开页面直接返回缓存；缓存年龄不计入 stale —— stale 仅表示
            // 实时刷新失败，UI 用 cachedAt 展示「x 前更新」。
            return CarrierBillResult(
                bill = cached.bill,
                loginRequired = account.cookies.isEmpty(),
                cachedAt = cached.cachedAt,
            )
        }

        if (account.cookies.isEmpty()) {
            if (cached != null) {
                return CarrierBillResult(
                    bill = cached.bill,
                    loginRequired = true,
                    cachedAt = cached.cachedAt,
                )
            }
            throw CarrierLoginRequiredException(
                if (account.accessMethod == "iot_card") {
                    "${IotProvider.of(account.iotProvider).displayName}会话已失效，请重新登录"
                } else {
                    "运营商会话已失效，请重新登录"
                }
            )
        }

        return try {
            val bill = billOf(accountId, force)
            val at = now()
            // billOf 命中内存缓存时不会执行「新拉取」分支中的保存；此处补充写一次，
            // 保证缓存缺失（被清除/旧版本升级）后也能重建。
            store.saveCachedBill(accountId, CachedCarrierBill(bill, at))
            CarrierBillResult(bill = bill, cachedAt = at)
        } catch (e: CarrierLoginRequiredException) {
            if (cached != null) {
                CarrierBillResult(
                    bill = cached.bill,
                    loginRequired = true,
                    cachedAt = cached.cachedAt,
                    error = e.message,
                )
            } else {
                throw e
            }
        } catch (e: CarrierUpstreamException) {
            if (cached != null) {
                CarrierBillResult(
                    bill = cached.bill,
                    stale = true,
                    cachedAt = cached.cachedAt,
                    error = e.message,
                )
            } else {
                throw e
            }
        }
    }

    /**
     * 物联卡账单：card/get + 生效套餐。云上卡 refresh 仅在 force 且距上次 ≥5 分钟时调用；
     * 小鲸卡的上游 refresh 持续返回 code=23，直接跳过。调用方已持有该账号的锁。
     */
    private suspend fun cloudBillOf(
        accountId: String,
        account: StoredCarrierAccount,
        force: Boolean,
    ): CarrierBill {
        val epoch = epochOf(accountId)
        val nowMs = now()
        val cachedBill = synchronized(stateGuard) { cached[accountId] }
        val cachedTime = synchronized(stateGuard) { cachedAt[accountId] ?: 0L }
        val lastFetch = maxOf(
            synchronized(stateGuard) { lastFetchAt[accountId] ?: 0L },
            account.lastFetchedAt,
        )
        if (!force && cachedBill != null && nowMs - cachedTime < CACHE_TTL_SECONDS * 1000L) {
            return cachedBill
        }
        if (force && cachedBill != null && nowMs - lastFetch < MIN_REFRESH_SECONDS * 1000L) {
            return cachedBill
        }

        val provider = IotProvider.of(account.iotProvider)
        val client = CloudCardClient(baseUrl = provider.baseUrl)
        client.cookieJar.restore(account.cookies)
        val warnings = mutableListOf<String>()
        // 小鲸卡 refresh 持续返回 code=23（上游系统忙），直接跳过，不将其转为每页一条告警。
        if (provider.supportsRefresh && force && nowMs - lastFetch >= CLOUD_REFRESH_MIN_MS) {
            try {
                client.refresh()
            } catch (e: CloudCardAuthRequiredException) {
                clearCookies(accountId)
                throw CarrierLoginRequiredException("${provider.displayName}会话已失效，请重新登录")
            } catch (e: CloudCardRateLimitedException) {
                warnings += "上游刷新限流，当前显示最近一次同步的数据"
            } catch (e: CloudCardApiException) {
                warnings += "上游刷新失败：${e.message ?: "未知错误"}"
            }
        }

        try {
            val snapshot = client.cardInfo()
            val plans = runCatching { client.plans() }.getOrElse { e ->
                warnings += "生效套餐读取失败：${e.message ?: "未知错误"}"
                emptyList()
            }
            val orders = runCatching { client.orders() }.getOrElse { e ->
                warnings += "订单读取失败：${e.message ?: "未知错误"}"
                emptyList()
            }
            val realOrders = runCatching { client.realOrders() }.getOrElse { e ->
                warnings += "真实额度读取失败：${e.message ?: "未知错误"}"
                emptyList()
            }
            val bill = mapCloudCardBill(
                series = null,
                snapshot = snapshot,
                plans = plans,
                orders = orders,
                realOrders = realOrders,
                warnings = warnings,
                provider = provider,
            )
            // 这期间账号可能已被删除/注销（代际号变了）：只把结果返回给调用方，不落盘。
            if (epochOf(accountId) != epoch) {
                com.ufi_axis_core.util.AppLogger.i(
                    "CarrierBill",
                    "刷新期间账号已被删除/注销，跳过回写 account=$accountId"
                )
                return bill
            }
            synchronized(stateGuard) {
                cached[accountId] = bill
                cachedAt[accountId] = nowMs
                lastFetchAt[accountId] = nowMs
            }
            writeAccount(
                account.copy(
                    iotProvider = provider.id,
                    cookies = client.cookieJar.snapshot(),
                    lastFetchedAt = nowMs,
                )
            )
            store.saveCachedBill(accountId, CachedCarrierBill(bill, nowMs))
            return bill
        } catch (e: CloudCardAuthRequiredException) {
            clearCookies(accountId)
            throw CarrierLoginRequiredException("${provider.displayName}会话已失效，请重新登录")
        } catch (e: CloudCardRateLimitedException) {
            throw CarrierUpstreamException("${provider.displayName}刷新过于频繁，请稍后再试")
        } catch (e: CloudCardApiException) {
            throw CarrierUpstreamException("${provider.displayName}查询失败：${e.message}")
        }
    }

    override fun logoutOf(accountId: String) {
        bumpEpoch(accountId)
        synchronized(stateGuard) {
            pendingMobile.remove(accountId)
            pendingUnicom.remove(accountId)
            cached.remove(accountId)
            cachedAt.remove(accountId)
            lastFetchAt.remove(accountId)
        }
        clearCookies(accountId)
    }

    // ─────────────────────── 内部 ───────────────────────

    /** 找到或新建一个账号（不改活动指针）。id 由 carrier+phone 确定性算出。 */
    private fun upsertAccount(
        carrier: CarrierKind,
        phone: String,
        label: String,
        accessMethod: String = "carrier",
        iotProvider: String = "cloud",
        /**
         * 参与 id 哈希的 carrier 字符串。
         *
         * 云上卡必须继续用 `iot_card`（旧账号 id 不变）；小鲸卡用 `iot_xj`，
         * 否则同一个充值号在两个站点会算出同一个 id、互相覆盖。
         */
        idSeed: String = carrier.name.lowercase(),
    ): StoredCarrierAccount {
        val digits = phone.filter { it.isDigit() }
        // 号码还没有时（旧接口的扫码/密码登录，登录成功才知道号），id 只能用占位种子算。
        // 之前是**每次调用都随机生成一个 id**，于是每次重新登录都多出一个空壳账号，
        // 索引只增不减、每个还带自己的锁与 pending client。这里改为复用该运营商已有的
        // 匿名壳（最多一个），登录成功拿到号码后由 writeAccount(phone=…) 补上。
        val anonymous = if (digits.isBlank()) {
            // 索引项里只有掩码手机号，掩码为空即"还没登录成功的空壳"。
            store.accounts()
                .firstOrNull { it.carrier == idSeed && it.phoneMasked.isBlank() }
        } else {
            null
        }
        val id = anonymous?.id ?: CarrierAccountIds.of(idSeed, digits)
        val existing = store.account(id)
        val account = existing?.copy(
            label = label.ifBlank { existing.label },
            accessMethod = if (existing.accessMethod.isBlank()) accessMethod else existing.accessMethod,
            iotProvider = if (accessMethod == "iot_card") iotProvider else existing.iotProvider,
        ) ?: StoredCarrierAccount(
            id = id,
            carrier = carrier.name.lowercase(),
            accessMethod = accessMethod,
            iotProvider = if (accessMethod == "iot_card") iotProvider else "cloud",
            phone = digits,
            label = label,
            createdAt = now(),
        )
        writeAccount(account)
        return account
    }

    private fun summaryOf(account: StoredCarrierAccount): CarrierAccountSummary {
        val status = statusOf(account.id)
        return CarrierAccountSummary(
            id = account.id,
            carrier = carrierOf(account.carrier),
            accessMethod = account.accessMethod,
            iotProvider = if (account.accessMethod == "iot_card") account.iotProvider else "",
            phoneMasked = if (account.accessMethod == "iot_card") {
                maskIotCardNo(account.phone)
            } else {
                maskCarrierPhone(account.phone)
            },
            label = account.label,
            state = when (status) {
                is CarrierStatus.Ready -> "ready"
                is CarrierStatus.LoginRequired -> "login_required"
                CarrierStatus.NotConfigured -> "not_configured"
                else -> "error"
            },
            createdAt = account.createdAt,
        )
    }

    /** 返回首个命中鉴权失败特征码的接口错误；是否据此判定会话失效由调用方决定（需全部接口失败）。 */
    private fun firstAuthFailure(raw: Map<String, JsonObject>): String? =
        raw.values.firstNotNullOfOrNull { body ->
            val err = (body["__error"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
                ?: return@firstNotNullOfOrNull null
            if (AUTH_CODES.any { err.contains(it) } || err.contains("401") || err.contains("not login", true)) {
                err
            } else {
                null
            }
        }

    private fun requirePhone(phone: String) {
        require(PHONE_PATTERN.matches(phone)) { "手机号格式不正确" }
    }

    companion object {
        private const val CODE_TTL_SECONDS = 300
        private const val QR_TTL_SECONDS = 600
        private const val SEND_COOLDOWN_SECONDS = 60
        private const val MAX_CODE_ATTEMPTS = 5
        private const val CACHE_TTL_SECONDS = 300
        private const val MIN_REFRESH_SECONDS = 30
        private const val CLOUD_REFRESH_MIN_MS = 5 * 60 * 1000L
        private val PHONE_PATTERN = Regex("""^1[3-9]\d{9}$""")
        private val AUTH_CODES = setOf("400006", "410000", "400001", "8051", "8052", "6001")
    }
}

/**
 * 发码频率限制（按账号 + 全局限速）。
 *
 * 移动发码会产生真实短信费用；阈值集中在此处，不分散到路由层。
 *
 * ## 两级限制
 * - **单账号**限制（1 分钟 / 5 小时 / 10 天）：防止单个号码被反复触发（误操作、自动登录失控）。
 * - **全局限速 1 条/分钟**：防止运营商风控 —— 短时间连续发送多条最易触发短信登录临时锁定，
 *   而全天分散发送数十条本身无害。因此限制的是速率，不是日总量。
 *
 * 键按账号分：`send_<accountId>`；全局使用 `send_global`。账号 id 为 hash/随机串，
 * 不含手机号明文。
 */
class CarrierRateLimiter(context: Context) {
    private val prefs = context.getSharedPreferences("ufi_carrier_limits", Context.MODE_PRIVATE)

    /**
     * @return 0 = 可以发；>0 = 需要等待的秒数。
     */
    fun checkSending(accountId: String, now: Long = System.currentTimeMillis()): Long =
        CarrierSendPolicy.waitSeconds(
            now = now,
            globalSends = read(KEY_GLOBAL),
            accountSends = read(keyOf(accountId)),
        )

    fun recordSending(accountId: String, now: Long = System.currentTimeMillis()) {
        val key = keyOf(accountId)
        write(key, (read(key) + now).filter { it > now - 86_400_000 })
        write(KEY_GLOBAL, (read(KEY_GLOBAL) + now).filter { it > now - 86_400_000 })
    }

    /**
     * 今日已发条数（设备合计，手动 + 自动）。
     *
     * 用**本地自然日**而不是滚动 24h：UI 写的是"每日上限"，跨零点应该归零。
     * 读 `send_global`，所以手动发码（同样走 [recordSending]）也被计入。
     */
    fun sendsToday(now: Long = System.currentTimeMillis()): Int {
        val dayStart = startOfLocalDay(now)
        return read(KEY_GLOBAL).count { it >= dayStart }
    }

    /** 删除账号时清除该账号的分钟/小时/天计数；全局合计保留（已发送的短信不可回退）。 */
    fun clear(accountId: String) {
        prefs.edit().remove(keyOf(accountId)).commit()
    }

    private fun keyOf(accountId: String): String = "send_$accountId"

    private fun read(key: String): List<Long> = prefs.getString(key, "")
        .orEmpty().split(',').mapNotNull { it.toLongOrNull() }

    private fun write(key: String, values: List<Long>) {
        prefs.edit().putString(key, values.joinToString(",")).commit()
    }

    private companion object {
        const val KEY_GLOBAL = "send_global"
    }
}

/** 设备本地自然日 00:00 的 epoch ms（跨零点归零，不是滚动 24h）。 */
internal fun startOfLocalDay(now: Long): Long {
    val zone = java.time.ZoneId.systemDefault()
    return java.time.Instant.ofEpochMilli(now)
        .atZone(zone)
        .toLocalDate()
        .atStartOfDay(zone)
        .toInstant()
        .toEpochMilli()
}

/** 距离下一个本地零点还有多少秒（至少 1），用于"今日额度已用完"的 retry_after。 */
internal fun secondsUntilLocalMidnight(now: Long): Long {
    val zone = java.time.ZoneId.systemDefault()
    val today = java.time.Instant.ofEpochMilli(now).atZone(zone)
    val tomorrow = today.toLocalDate().plusDays(1).atStartOfDay(zone)
    return java.time.Duration.between(java.time.Instant.ofEpochMilli(now), tomorrow.toInstant())
        .seconds.coerceAtLeast(1)
}

/**
 * 发码频率的纯决策逻辑（便于单元测试）。
 *
 * `CarrierRateLimiter` 依赖 Context/SharedPreferences，而 `:core:controller` 的测试
 * 未引入 Robolectric；将「读出的时间戳是否放行」抽为纯函数后，可用普通单测覆盖
 * 「全局 1 条/分钟」与「单账号 1/分、5/时、10/天」的叠加关系。
 *
 * 传入的两份时间戳不做窗口过滤（由本函数计算）；异常数据（负数、未来时间）不影响判断：
 * 未来时间按「刚刚发送」处理，保守地拒绝放行 —— 宁可多等 60 秒，也不因系统时间调整而连续发码。
 */
internal object CarrierSendPolicy {

    private const val MINUTE = 60_000L
    private const val HOUR = 3_600_000L
    private const val DAY = 86_400_000L

    fun waitSeconds(now: Long, globalSends: List<Long>, accountSends: List<Long>): Long {
        // 1) 全局限速：跨账号合计 1 条/分钟（防运营商风控）。
        globalSends.filter { it > now - MINUTE }.maxOrNull()?.let {
            return ((it + MINUTE - now) / 1000).coerceAtLeast(1)
        }

        // 2) 单账号分钟窗。
        accountSends.filter { it > now - MINUTE }.maxOrNull()?.let {
            return ((it + MINUTE - now) / 1000).coerceAtLeast(1)
        }

        // 3) 单账号小时窗：满 5 条后等到最早那条过期。
        val inHour = accountSends.filter { it > now - HOUR }
        if (inHour.size >= 5) {
            return ((inHour.min() + HOUR - now) / 1000).coerceAtLeast(1)
        }

        // 4) 单账号天窗：满 10 条后等到最早那条过期。
        val inDay = accountSends.filter { it > now - DAY }
        if (inDay.size >= 10) {
            return ((inDay.min() + DAY - now) / 1000).coerceAtLeast(1)
        }

        return 0
    }
}
