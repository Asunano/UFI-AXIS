package com.ufi_axis_core.controller.carrier

import android.content.Context
import com.ufi_axis_core.util.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * 移动自动登录调度器。
 *
 * 仅处理「移动 SIM + 显式开启」场景：
 * 定时检查账单 → 会话缺失/过期/到达重登间隔 → 发送验证码 → 等待 10086 短信 → 自动登录 → 取账单。
 *
 * ## 多账号约束
 *
 * 1. **策略是设备级**：检查/重登间隔与每日短信额度来自 [CarrierBillSettings]，所有账号共用；
 *    账号侧只保留 `enabled` 参与位。`nextCheckAt`、尝试次数、上次成功时间仍按 accountId 分槽。
 * 2. **全局串行**：[CarrierSmsCodeWatcher] 只按「发件人 10086 / 正文含中国移动 + 请求时间」
 *    取码，无法识别号码归属。因此同一时刻只允许一个账号处于等码状态，
 *    由全局 `Mutex` + `runningAccountId` 实现，而非每账号一把锁。
 *    间隔与日上限必须是设备级，也源于此：多账号只能排队，逐账号间隔无法兑现。
 * 3. **不做 SIM 号码回退**：手机号只取账号自身的号码。账号无号 → `phone_required`，
 *    不回退到 SIM —— 多账号下这会表现为用 A 号续会话却登录 B 号。
 * 4. **不支持联通**：`CarrierAutoLoginConfig` 与 watcher 均为移动专用。
 *    联通账号的 `supported=false`，接口返回明确原因。
 *
 * 旧接口（`snapshot()` / `updateConfig()` / `trigger()`）语义固定为「当前账号」，
 * 未升级的 App 可继续使用；其中 `updateConfig()` 的间隔字段写入设备策略（见方法注释）。
 */
class CarrierAutoLoginManager(
    context: Context,
    private val service: CarrierService,
    private val sessionStore: CarrierSessionStore = CarrierSessionStore(context),
    private val autoStore: CarrierAutoLoginStore = CarrierAutoLoginStore(context),
    /** 设备级账单策略（刷新 / 自动登录 / 每日短信）。与刷新器共享同一个实例。 */
    private val billSettingsStore: CarrierBillSettingsStore = CarrierBillSettingsStore(context),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    /**
     * 账单后台刷新器。与自动登录共用后台总闸：start/stop 一起走。
     * 默认 null 保持既有测试/旧装配不受影响。
     */
    private val billRefresher: CarrierBillRefresher? = null,
) {
    private val detector = CarrierSimDetector(context)
    private val watcher = CarrierSmsCodeWatcher(context)

    /** 全局串行：等码期间其它账号一律排队（见类注释第 2 条）。 */
    private val mutex = Mutex()

    /**
     * 设置写入专用锁。
     *
     * 与 [mutex] 分离：`attemptLocked` 持有 [mutex] 等待验证码最长 90s，
     * 设备策略/参与位的 PUT 不应排在其后（客户端读超时约 120s，超时回滚后 core 仍会写入）。
     */
    private val settingsMutex = Mutex()

    private val configs = ConcurrentHashMap<String, CarrierAutoLoginConfig>()
    private val states = ConcurrentHashMap<String, CarrierAutoLoginState>()
    private val nextCheckAt = ConcurrentHashMap<String, Long>()

    /** 非空 = 当前正在等码/验码的账号。 */
    @Volatile
    private var runningAccountId: String? = null

    private var job: Job? = null

    /** 手动「立即登录」的在途任务；stop 时必须一并取消，否则停止后台后仍会继续发送验证码。 */
    private val triggerJobs = ConcurrentHashMap<String, Job>()

    init {
        // 冷启动把已存配置读入内存；账号删除后遗留的配置读不到，自然失效。
        // 旧配置没有 participationExplicit：把旧 enabled 视为显式选择，迁移为 explicit，
        // 避免总开关开启时把旧 enabled=false 的账号自动纳入。
        sessionStore.accounts().forEach { entry ->
            val stored = sessionStore.loadAutoConfigOf(entry.id) ?: return@forEach
            val migrated = if (stored.participationExplicit == null) {
                stored.copy(participationExplicit = true).also { value ->
                    runCatching { sessionStore.saveAutoConfigOf(entry.id, value) }
                        .onFailure {
                            AppLogger.w(TAG, "迁移参与位失败($entry.id): ${it.javaClass.simpleName}")
                        }
                }
            } else {
                stored
            }
            configs[entry.id] = migrated
        }
        // 触发一次迁移/读取，保证设备策略在第一个 tick 之前就落盘。
        billSettingsStore.get()
    }

    fun start() {
        if (job?.isActive == true) return
        billRefresher?.start()
        job = scope.launch {
            while (isActive) {
                runCatching { tick() }.onFailure {
                    if (it is kotlinx.coroutines.CancellationException) throw it
                    AppLogger.w(TAG, "auto-login tick failed: ${it.javaClass.simpleName}: ${it.message}")
                }
                delay(30_000L)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        triggerJobs.values.forEach { it.cancel() }
        triggerJobs.clear()
        billRefresher?.stop()
        CarrierAutoLoginRuntime.end()
    }

    // ─────────────────────── 设备级账单策略 ───────────────────────

    fun settings(): CarrierBillSettings = billSettingsStore.get()

    /**
     * 设置页所需的完整快照：设备策略 + 今日总短信数 + 各账号的参与位与状态。
     *
     * 只列运营商账号；物联卡不走 SIM 验证码，纳入后只会产生「不支持」状态。
     */
    fun settingsSnapshot(): CarrierBillSettingsSnapshot {
        val settings = settings()
        val accounts = sessionStore.accounts()
            .filter { it.accessMethod == "carrier" }
            .map { entry ->
                val state = snapshotOf(entry.id)
                CarrierKeepAliveAccountState(
                    id = entry.id,
                    label = entry.label,
                    phoneMasked = entry.phoneMasked,
                    carrier = entry.carrier,
                    participating = configOf(entry.id).enabled,
                    supported = state.supported,
                    phase = state.phase,
                    message = state.message,
                    attemptsToday = state.attemptsToday,
                    lastLoginAt = state.lastLoginAt,
                    nextCheckAt = state.nextCheckAt,
                )
            }
        // 设备总额度：必须包含手动发码，所以读发码器统一计数，而不是 autoStore（只记自动登录）。
        val attemptsToday = service.smsSentToday()
        val nextCheckAt = accounts
            .filter { it.participating && it.supported }
            .mapNotNull { it.nextCheckAt }
            .minOrNull()
        return CarrierBillSettingsSnapshot(
            settings = settings,
            attemptsToday = attemptsToday,
            nextCheckAt = nextCheckAt,
            accounts = accounts,
        )
    }

    /**
     * 保存设备策略（补丁式）。
     *
     * [transform] 在 [settingsMutex] 内对当前值求值，避免两个客户端各自读取旧快照后整份覆盖。
     * 仅自动登录开关/间隔变化才重置 `nextCheckAt`；只修改刷新配置不会触发一轮登录。
     */
    suspend fun updateBillSettings(
        transform: (CarrierBillSettings) -> CarrierBillSettings,
    ): CarrierBillSettings = settingsMutex.withLock {
        val before = billSettingsStore.get()
        val next = billSettingsStore.save(transform(before))
        val keepAliveChanged = before.keepAliveEnabled != next.keepAliveEnabled ||
            before.checkIntervalMinutes != next.checkIntervalMinutes ||
            before.reloginIntervalMinutes != next.reloginIntervalMinutes ||
            // 额度从「用完」调大后需允许当天继续尝试，不能被午夜退避阻塞。
            before.dailySmsLimit != next.dailySmsLimit
        if (keepAliveChanged) {
            nextCheckAt.keys.forEach { nextCheckAt[it] = 0L }
        }
        if (before.refreshEnabled != next.refreshEnabled ||
            before.refreshIntervalMinutes != next.refreshIntervalMinutes
        ) {
            billRefresher?.restart()
        }
        next
    }

    /**
     * 只修改单个账号的参与位（设置页开关）。
     *
     * 与 `updateConfigOf` 分离：此处不涉及设备策略，避免切换开关时改动其他账号的间隔。
     */
    suspend fun setParticipating(
        accountId: String,
        participating: Boolean,
    ): CarrierAutoLoginState = settingsMutex.withLock {
        val account = sessionStore.account(accountId)
            ?: throw IllegalArgumentException("账号不存在")
        val cap = detector.detect()
        val phone = account.phone.filter(Char::isDigit).take(11)
        if (participating) {
            if (runCatching { CarrierKind.valueOf(account.carrier.uppercase()) }.getOrNull() != CarrierKind.MOBILE) {
                throw IllegalArgumentException("自动登录仅支持中国移动账号")
            }
            if (!cap.supported) throw IllegalArgumentException(cap.reason ?: "当前 SIM 不支持自动登录")
            if (!CarrierSimMatch.simReadable(cap.phone)) {
                throw IllegalArgumentException("设备读不到当前 SIM 的手机号，无法确认账号归属")
            }
            if (!CarrierSimMatch.matches(account.phone, cap.phone)) {
                throw IllegalArgumentException("当前 SIM 与账号手机号不一致，无法自动登录")
            }
            if (!CarrierPhone.isMobile(phone)) {
                throw IllegalArgumentException("该账号没有可用的 11 位手机号，无法自动登录")
            }
        }
        val settings = settings()
        val next = configOf(accountId).copy(
            enabled = participating,
            participationExplicit = true,
            phone = phone,
            checkIntervalMinutes = settings.checkIntervalMinutes,
            reloginIntervalMinutes = settings.reloginIntervalMinutes,
            maxAttemptsPerDay = settings.dailySmsLimit,
        )
        sessionStore.saveAutoConfigOf(accountId, next)
        configs[accountId] = next
        nextCheckAt[accountId] = 0L
        if (participating && autoStore.lastLoginAt(accountId) == 0L &&
            service.statusOf(accountId) is CarrierStatus.Ready
        ) {
            autoStore.setLastLoginAt(accountId, System.currentTimeMillis())
        }
        val result = snapshot(
            accountId = accountId,
            phase = if (participating && settings.keepAliveEnabled) "idle" else "disabled",
            cap = cap,
        )
        states[accountId] = result
        result
    }

    // ─────────────────────── 旧接口：当前账号 ───────────────────────

    fun snapshot(): CarrierAutoLoginState =
        sessionStore.activeId()?.let { snapshotOf(it) }
            ?: snapshotOf(accountId = "")

    suspend fun updateConfig(input: CarrierAutoLoginConfig): CarrierAutoLoginState {
        val id = sessionStore.activeId()
            ?: throw IllegalArgumentException("请先添加一个中国移动账号，再开启自动登录")
        return updateConfigOf(id, input)
    }

    fun trigger() {
        val id = sessionStore.activeId()
        if (id == null) {
            AppLogger.w(TAG, "trigger ignored: 没有当前账号")
            return
        }
        triggerOf(id)
    }

    // ─────────────────────── 账号维度 ───────────────────────

    fun snapshotOf(accountId: String): CarrierAutoLoginState {
        val now = System.currentTimeMillis()
        val config = configOf(accountId)
        val cap = detector.detect()
        val account = sessionStore.account(accountId)
        val supported = account != null &&
            runCatching { CarrierKind.valueOf(account.carrier.uppercase()) }.getOrNull() == CarrierKind.MOBILE &&
            cap.supported &&
            CarrierPhone.isMobile(account.phone) &&
            CarrierSimMatch.matches(account.phone, cap.phone)
        val globalEnabled = settings().keepAliveEnabled
        val phase = when {
            account == null -> "unsupported"
            !supported -> "unsupported"
            !globalEnabled || !config.enabled -> "disabled"
            else -> (states[accountId]?.phase ?: "idle")
        }
        // 不支持的原因必须可读：三种情况分别为「账号不存在」「非移动账号」「SIM 不可用」。
        // 前两种在此判断；第三种（未插卡 / 无权限 / SIM 未就绪）使用检测器给出的原因。
        val unsupportedReason = when {
            // 空 id 表示调用方为旧接口（`snapshot()`），且当前没有 active 账号。
            // 此时返回「账号不存在或已删除」会产生误导：实际尚未登录或未选择账号。
            accountId.isBlank() -> "当前没有选中的账号，请先登录"
            account == null -> "账号不存在或已删除"
            runCatching { CarrierKind.valueOf(account.carrier.uppercase()) }.getOrNull() != CarrierKind.MOBILE ->
                "自动登录仅支持中国移动账号"
            !cap.supported -> cap.reason ?: "当前设备不支持自动登录（请确认已插入中国移动 SIM）"
            !CarrierPhone.isMobile(account.phone) -> "账号没有可用的 11 位手机号，无法自动登录"
            !CarrierSimMatch.simReadable(cap.phone) ->
                "设备读不到当前 SIM 的手机号，无法确认账号归属"
            !CarrierSimMatch.matches(account.phone, cap.phone) ->
                "当前 SIM 与账号手机号不一致，无法自动登录"
            else -> null
        }
        return snapshot(
            accountId = accountId,
            phase = phase,
            message = unsupportedReason ?: states[accountId]?.message,
            cap = cap,
            now = now,
        )
    }

    suspend fun updateConfigOf(
        accountId: String,
        input: CarrierAutoLoginConfig,
    ): CarrierAutoLoginState = settingsMutex.withLock {
        val account = sessionStore.account(accountId)
            ?: throw IllegalArgumentException("账号不存在")
        val cap = detector.detect()
        // 只写单账号的旧客户端（App / Web）总会携带 5/25/8 三个默认值。
        // 不得把其间隔字段写入设备策略：那会静默覆盖设置页中配置的 30/60/3。
        // 旧接口只承担两件事：
        //   ① enabled = 该账号的参与位；
        //   ② enabled=true 时打开设备总开关。
        // 间隔与每日额度只能通过 /bill/settings 修改。
        val currentSettings = billSettingsStore.get()
        val nextSettings = currentSettings.copy(
            keepAliveEnabled = if (input.enabled) true else currentSettings.keepAliveEnabled,
        ).normalized()
        val normalized = input.copy(
            participationExplicit = true,
            checkIntervalMinutes = nextSettings.checkIntervalMinutes,
            reloginIntervalMinutes = nextSettings.reloginIntervalMinutes,
            maxAttemptsPerDay = nextSettings.dailySmsLimit,
            // 手机号只取账号自身的号码；手填值仅作为「账号尚未绑定号码」时的显式补充，
            // 不回退 SIM。
            phone = account.phone.filter(Char::isDigit).take(11)
                .ifBlank { input.phone.filter(Char::isDigit).take(11) },
        )
        if (normalized.enabled) {
            if (runCatching { CarrierKind.valueOf(account.carrier.uppercase()) }.getOrNull() != CarrierKind.MOBILE) {
                throw IllegalArgumentException("自动登录仅支持中国移动账号")
            }
            if (!cap.supported) throw IllegalArgumentException(cap.reason ?: "当前 SIM 不支持自动登录")
            if (!CarrierSimMatch.simReadable(cap.phone)) {
                throw IllegalArgumentException("设备读不到当前 SIM 的手机号，无法确认账号归属")
            }
            if (!CarrierSimMatch.matches(account.phone, cap.phone)) {
                throw IllegalArgumentException("当前 SIM 与账号手机号不一致，无法自动登录")
            }
            if (!CarrierPhone.isMobile(normalized.phone)) {
                throw IllegalArgumentException("该账号没有可用的 11 位手机号，无法自动登录")
            }
        }
        billSettingsStore.save(nextSettings)
        sessionStore.saveAutoConfigOf(accountId, normalized)
        configs[accountId] = normalized
        nextCheckAt[accountId] = 0L
        if (normalized.enabled && autoStore.lastLoginAt(accountId) == 0L &&
            service.statusOf(accountId) is CarrierStatus.Ready
        ) {
            // 已有会话时不得把「刚启用」误判为「已到重登间隔」。
            autoStore.setLastLoginAt(accountId, System.currentTimeMillis())
        }
        val result = snapshot(
            accountId = accountId,
            phase = if (normalized.enabled && nextSettings.keepAliveEnabled) "idle" else "disabled",
            cap = cap,
        )
        states[accountId] = result
        result
    }

    /**
     * 手动「立即执行」：异步启动一次，同时同步返回可判断的初始状态。
     *
     * 未开启/未参与 → phase=disabled；已入队 → phase=checking。
     * 调用方可据此区分「已入队」与「未执行」。
     */
    fun triggerOf(accountId: String): CarrierAutoLoginState {
        val config = configOf(accountId)
        if (!config.enabled || !settings().keepAliveEnabled) {
            val state = snapshot(accountId, phase = "disabled", message = "自动登录未开启")
            states[accountId] = state
            return state
        }
        val queued = snapshot(accountId, phase = "checking", message = "已请求，正在检查")
        states[accountId] = queued
        val trigger = scope.launch {
            mutex.withLock {
                attemptLocked(accountId, detector.detect(), "手动触发")
            }
        }
        triggerJobs[accountId]?.cancel()
        triggerJobs[accountId] = trigger
        trigger.invokeOnCompletion { triggerJobs.remove(accountId, trigger) }
        return queued
    }

    // ─────────────────────── 调度 ───────────────────────

    private suspend fun tick() = mutex.withLock {
        if (runningAccountId != null) return@withLock
        val settings = settings()
        if (!settings.keepAliveEnabled) return@withLock
        val now = System.currentTimeMillis()
        val cap = detector.detect()
        if (!cap.supported) return@withLock

        // 只挑选「参与 + 到达检查时间 + 当前 SIM 与账号手机号一致」的账号，最久未检查者优先；
        // 一次只处理一个（全局串行）。遍历账号索引而非 configs：无显式配置的账号
        // 默认跟随设备总开关，新增账号无需先写入配置即可被调度。
        val due = sessionStore.accounts()
            .asSequence()
            .filter { it.accessMethod == "carrier" }
            .mapNotNull { entry -> sessionStore.account(entry.id)?.let { entry.id to it } }
            .filter { (id, account) ->
                configOf(id).enabled &&
                    CarrierPhone.isMobile(account.phone) &&
                    CarrierSimMatch.matches(account.phone, cap.phone)
            }
            .filter { (id, _) -> (nextCheckAt[id] ?: 0L) <= now }
            .sortedBy { (id, _) -> nextCheckAt[id] ?: 0L }
            .firstOrNull() ?: return@withLock

        val accountId = due.first
        nextCheckAt[accountId] = now + settings.checkIntervalMinutes * 60_000L
        states[accountId] = snapshot(accountId, phase = "checking", cap = cap, now = now)

        val status = service.statusOf(accountId)
        if (status !is CarrierStatus.Ready) {
            attemptLocked(accountId, cap, "会话不存在或已失效")
            return@withLock
        }
        val lastLogin = autoStore.lastLoginAt(accountId)
        if (lastLogin > 0 && now - lastLogin >= settings.reloginIntervalMinutes * 60_000L) {
            attemptLocked(accountId, cap, "到达重登间隔")
            return@withLock
        }
        try {
            service.billOf(accountId, force = false)
            states[accountId] = snapshot(accountId, phase = "ready", cap = cap, now = now)
        } catch (_: CarrierLoginRequiredException) {
            attemptLocked(accountId, cap, "会话已过期")
        } catch (e: CarrierRateLimitedException) {
            states[accountId] = snapshot(accountId, phase = "failed", message = e.message, cap = cap, now = now)
            nextCheckAt[accountId] = now + e.retryAfterSeconds * 1000L
        } catch (e: Exception) {
            states[accountId] = snapshot(
                accountId, phase = "failed", message = e.message ?: "检查会话失败", cap = cap, now = now
            )
        }
    }

    private suspend fun attemptLocked(
        accountId: String,
        cap: CarrierSimCapability,
        reason: String,
    ) {
        if (runningAccountId != null) return
        // 二次校验：即使配置在换卡前已开启，SIM 变更后也不得用另一张卡的短信通道登录该账号。
        val account = sessionStore.account(accountId)
        if (account == null || !CarrierPhone.isMobile(account.phone) ||
            !CarrierSimMatch.simReadable(cap.phone)
        ) {
            states[accountId] = snapshot(
                accountId,
                phase = "failed",
                message = "账号号码不完整或设备读不到当前 SIM 的手机号，无法自动登录",
                cap = cap,
            )
            return
        }
        if (!CarrierSimMatch.matches(account.phone, cap.phone)) {
            states[accountId] = snapshot(
                accountId,
                phase = "failed",
                message = "当前 SIM 与账号手机号不一致，无法自动登录",
                cap = cap,
            )
            return
        }
        val config = configOf(accountId)
        val settings = settings()
        val now = System.currentTimeMillis()
        // 设备总额度由 CarrierBillService.requestMobileCodeFor 统一检查（手动与自动共用），
        // 此处不重复统计 autoStore，避免两套口径。

        // 手机号只取该账号自身的号码，不回退 SIM。
        val phone = config.phone.takeIf { PHONE.matches(it) }
            ?: sessionStore.account(accountId)?.phone?.filter(Char::isDigit)?.take(11)
                ?.takeIf { PHONE.matches(it) }
        if (phone == null) {
            states[accountId] = snapshot(
                accountId,
                phase = "phone_required",
                message = "该账号没有可用的手机号，无法自动登录",
                cap = cap,
                now = now,
            )
            return
        }

        runningAccountId = accountId
        states[accountId] = snapshot(
            accountId, phase = "waiting_code", message = "$reason：正在发送验证码", cap = cap, now = now
        )
        CarrierAutoLoginRuntime.begin(150_000L)
        try {
            service.requestMobileCodeFor(accountId, phone)
            autoStore.recordAttempt(accountId, now)
            states[accountId] = snapshot(
                accountId, phase = "verifying", message = "验证码已发出，等待短信", cap = cap, now = now
            )
            val code = watcher.awaitCode(now, 90_000L)
            service.verifyMobileCodeFor(accountId, code)
            service.billOf(accountId, force = true)
            autoStore.setLastLoginAt(accountId, System.currentTimeMillis())
            nextCheckAt[accountId] = System.currentTimeMillis() + settings.checkIntervalMinutes * 60_000L
            states[accountId] = snapshot(accountId, phase = "ready", message = "自动登录成功", cap = cap)
            AppLogger.w(TAG, "auto-login success: account=$accountId ${maskCarrierPhone(phone)}")
        } catch (e: kotlinx.coroutines.CancellationException) {
            // 停止服务或取消手动触发不属于登录失败；原样抛出，由 finally 收尾。
            throw e
        } catch (e: CarrierRateLimitedException) {
            nextCheckAt[accountId] = System.currentTimeMillis() + e.retryAfterSeconds * 1000L
            states[accountId] = snapshot(accountId, phase = "failed", message = e.message, cap = cap)
            AppLogger.w(TAG, "auto-login rate limited: account=$accountId retry=${e.retryAfterSeconds}s")
        } catch (e: Exception) {
            // 失败同样退避到下一个检查周期，避免验证码未到达时连续发送。
            nextCheckAt[accountId] = System.currentTimeMillis() + settings.checkIntervalMinutes * 60_000L
            states[accountId] = snapshot(accountId, phase = "failed", message = e.message ?: "自动登录失败", cap = cap)
            AppLogger.w(
                TAG,
                "auto-login failed: account=$accountId ${e.javaClass.simpleName}: ${e.message}"
            )
        } finally {
            CarrierAutoLoginRuntime.end()
            runningAccountId = null
        }
    }

    /**
     * 账号删除后清除调度器内存态与发码计数。
     *
     * accountId 由 carrier+phone 哈希得到，删除后重新添加同一号码会得到相同 id；
     * 不清除会使新账号继承旧的参与位、last_login_at 与「今日已发」计数。
     */
    fun forgetAccount(accountId: String) {
        triggerJobs.remove(accountId)?.cancel()
        configs.remove(accountId)
        states.remove(accountId)
        nextCheckAt.remove(accountId)
        autoStore.clear(accountId)
    }

    private fun configOf(accountId: String): CarrierAutoLoginConfig =
        configs[accountId]
            ?: sessionStore.loadAutoConfigOf(accountId)?.also { configs[accountId] = it }
            // 默认不参与。总开关只调度明确选择过的账号；
            // 无配置的新账号必须在设置页显式开启，避免「开总开关即向所有号码发码」。
            ?: CarrierAutoLoginConfig()


    private fun snapshot(
        accountId: String,
        phase: String,
        message: String? = null,
        cap: CarrierSimCapability = detector.detect(),
        now: Long = System.currentTimeMillis(),
    ): CarrierAutoLoginState {
        val account = sessionStore.account(accountId)
        val settings = settings()
        val config = if (accountId.isBlank()) CarrierAutoLoginConfig() else configOf(accountId)
        val phone = account?.phone?.takeIf { it.isNotBlank() }?.filter(Char::isDigit)
        return CarrierAutoLoginState(
            supported = account != null &&
                runCatching { CarrierKind.valueOf(account.carrier.uppercase()) }.getOrNull() == CarrierKind.MOBILE &&
                cap.supported &&
                CarrierPhone.isMobile(account.phone) &&
                CarrierSimMatch.matches(account.phone, cap.phone),
            carrier = cap.carrier,
            simOperator = cap.simOperator.takeIf { it.isNotBlank() },
            operatorName = cap.operatorName.takeIf { it.isNotBlank() },
            enabled = account != null && config.enabled && settings.keepAliveEnabled,
            phoneMasked = phone?.let { maskCarrierPhone(it) },
            phase = phase,
            message = message,
            lastLoginAt = autoStore.lastLoginAt(accountId).takeIf { it > 0 },
            nextCheckAt = nextCheckAt[accountId]?.takeIf { it > 0 },
            attemptsToday = autoStore.attemptsToday(accountId, now).size,
            participating = account != null && config.enabled,
            checkIntervalMinutes = settings.checkIntervalMinutes,
            reloginIntervalMinutes = settings.reloginIntervalMinutes,
            maxAttemptsPerDay = settings.dailySmsLimit,
        )
    }

    private companion object {
        const val TAG = "CarrierAutoLogin"
        val PHONE = Regex("""^1[3-9]\d{9}$""")
    }
}
