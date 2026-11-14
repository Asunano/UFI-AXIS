package com.ufi_axis.data.api

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 「主人密码」的跨层桥（破坏性操作保护）。
 *
 * ## 解决的是什么
 *
 * core 的 `OwnerGate` 开关打开后，不可逆操作（恢复出厂 / 恢复备份 / root shell /
 * 自定义 shell 任务 / 删全部日志 / 移除配对设备 / 解封黑名单）会回
 * `401 MISSING_PASSWORD`。密码在请求发出之前谁也不该知道，所以必须「发一次 → 收 401 →
 * 问用户 → 带密码重发」。
 *
 * 拦截器在 `:app:data`、弹窗在 Compose（`:app` 根部），两者不能互相依赖 —— 与
 * `UfiPairingApprovalDialog` 同一条边界：**状态放这里，UI 只读状态**。
 *
 * ## 为什么拦截器要阻塞等用户
 *
 * OkHttp 的拦截器不是 suspend 的，而这里是"必须等一个人打字"的同步点。等待发生在 OkHttp
 * 自己的网络线程上（Retrofit suspend 调用与 AppHttpClient 都不在主线程），不会卡 UI；
 * 仓库里已有的同类做法见 `RetrofitClient.proceedWithAuth` 的重试（同一位置 `Thread.sleep`）。
 * 加了 [PROMPT_TIMEOUT_MS] 兜底：没装 UI（预览、测试、通知进程）时不至于把线程永久挂住。
 *
 * ## 密码只放内存
 *
 * 不落 SharedPreferences：它是**设备主人身份**，写盘只会扩大泄漏面。进程重启后重新问一次
 * 是可接受的代价 —— 这与 core 侧 2026-10-30 那版票据的取舍一致（票据后来没实现，见 `OwnerGate`）。
 */
object OwnerPasswordGate {

    /** 请求头名，与 core `HEADER_OWNER_PASSWORD` 逐字一致。 */
    const val HEADER = "X-Owner-Password"

    /**
     * 等用户输入的上限。
     *
     * 取 2 分钟：够看清提示、找到密码、打字；又不至于让一次忘记关掉的弹窗把网络线程挂死。
     * 超时按「用户取消」处理 —— 调用方拿回原始 401，界面上表现为这次操作没成功。
     */
    private const val PROMPT_TIMEOUT_MS = 120_000L

    /** 弹窗要展示的一句话（`null` = 当前不需要密码）。 */
    data class Prompt(val hint: String)

    private val _prompt = MutableStateFlow<Prompt?>(null)

    /** Compose 侧订阅它决定要不要弹框。 */
    val prompt: StateFlow<Prompt?> = _prompt.asStateFlow()

    /**
     * 用户在弹窗里**勾选"5 分钟内不再询问"**之后的密码。
     *
     * 2026-11-14 之前是无条件缓存且永不过期 —— 输过一次密码后，只要 App 进程还活着，
     * 再关「不可逆操作保护」完全不弹框，保护对用户"隐身"。现在默认每次都问，
     * 只有显式勾选才缓存，且 [CACHE_TTL_MS] 到点失效。
     */
    @Volatile
    private var cached: String? = null

    @Volatile
    private var cachedAt: Long = 0L

    /** 可注入的时钟（测试用）。 */
    @Volatile
    internal var clock: () -> Long = { System.currentTimeMillis() }

    /** 勾选"不再询问"后的缓存有效期：5 分钟。到点自动重新问。 */
    internal const val CACHE_TTL_MS: Long = 300_000L

    private fun cachedValid(): String? {
        val v = cached ?: return null
        return if (clock() - cachedAt < CACHE_TTL_MS) v else null
    }

    /**
     * 当前等待中的一次询问。**同一个** deferred 给所有并发调用方 ——
     * 三个请求同时撞上 401 时只弹一个框，一次输入答复全部（它们要的是同一个密码）。
     */
    @Volatile
    private var pending: CompletableDeferred<String?>? = null

    /** 同时等待这次询问的调用方数量。最后一个离开的人才允许收掉弹窗。 */
    private val waiters = java.util.concurrent.atomic.AtomicInteger(0)

    /**
     * 取一次主人密码：有缓存直接用，否则等用户输入。
     *
     * @param hint core 返回的 `error` 文案，缺省用通用说法
     * @return `null` = 用户取消或超时（调用方应把原始 401 抛给用户）
     */
    suspend fun obtain(hint: String?): String? {
        cachedValid()?.let { return it }
        val deferred = pendingOrNew(hint)
        waiters.incrementAndGet()
        try {
            return withTimeoutOrNull(PROMPT_TIMEOUT_MS) { deferred.await() }
        } finally {
            // **只有最后一个等待者离开时**才收掉这次询问。
            //
            // 原先任何一个调用方超时就 `clearPending`，会把别人正在看的弹窗一起关掉
            // （三个请求并发、其中一个先超时 → 另外两个的输入框消失）。
            if (waiters.decrementAndGet() == 0 && !deferred.isCompleted) {
                clearPending(deferred)
            }
        }
    }

    /**
     * 弹窗提交。
     *
     * @param password `null`/空串 = 取消，不写缓存（用户可能只是手滑关了框）。
     * @param remember 用户**显式勾选**"5 分钟内不再询问"才缓存。
     *
     * 2026-11-14 之前是不管用户愿不愿意都缓存：输过一次密码后，只要进程活着，
     * 关「不可逆操作保护」就再也不弹框 —— 机主根本看不到保护还在不在。
     * 现在默认**每次都问**，把"少问几次"的选择权交给用户。
     */
    fun submit(password: String?, remember: Boolean = false) {
        val value = password?.takeIf { it.isNotBlank() }
        if (value != null && remember) {
            cached = value
            cachedAt = clock()
        }
        val deferred = pending
        pending = null
        _prompt.value = null
        deferred?.complete(value)
    }

    /** 校验失败（`INVALID_PASSWORD`）或换设备/退出登录时调用。 */
    fun clear() {
        cached = null
        cachedAt = 0L
    }

    /**
     * 仅测试用：把"现在"往后挪，用来验证缓存过期。
     */
    internal fun advanceClockForTests(deltaMs: Long) {
        val base = clock()
        clock = { base + deltaMs }
    }

    /** 仅测试用：回到干净状态（不留悬挂的 deferred）。 */
    fun resetForTests() {
        cached = null
        cachedAt = 0L
        clock = { System.currentTimeMillis() }
        pending?.complete(null)
        pending = null
        waiters.set(0)
        _prompt.value = null
    }

    @Synchronized
    private fun pendingOrNew(hint: String?): CompletableDeferred<String?> {
        pending?.let { return it }
        val deferred = CompletableDeferred<String?>()
        pending = deferred
        _prompt.value = Prompt(hint?.takeIf { it.isNotBlank() } ?: DEFAULT_HINT)
        return deferred
    }

    @Synchronized
    private fun clearPending(deferred: CompletableDeferred<String?>) {
        if (pending === deferred) {
            pending = null
            _prompt.value = null
        }
    }

    private const val DEFAULT_HINT = "该操作已开启主人密码保护，请输入配对密码。"
}

/**
 * 用户**取消**了主人密码输入（或没装 UI 超时）。
 *
 * 拦截器收到它就直接抛出去，而不是把原始的 401 原样交给调用方 ——
 * 否则界面会显示「保存失败：HTTP 401 Unauthorized」，用户以为是程序出错，
 * 实际上这是"这次操作被保护门挡下来了"。
 */
class OwnerPasswordRequiredException :
    java.io.IOException("该操作需要配对密码，本次未执行")
