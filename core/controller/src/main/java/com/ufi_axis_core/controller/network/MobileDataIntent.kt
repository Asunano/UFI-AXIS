package com.ufi_axis_core.controller.network

/**
 * 「移动数据开关」的状态机：**用户意图** vs **设备链路**。
 *
 * ## 为什么需要它（2026-11-02 真机反馈）
 *
 * `/api/network/status` 的 `mobile_data` 一直是 `ppp_status` 的即时投影
 * （`DeviceFields.Connection.isPppConnected`）。但切换网络制式 / 锁频 / 关闭飞行模式都会让
 * 设备**先拆链再重拨**，这十几到几十秒里 `ppp_status` 必然是断的 —— 于是 web 与 app 上那个
 * 开关翻成「关闭」，等设备重拨完成才自己变回来。用户看到的是「明明是开的，却显示关」。
 *
 * 设备侧没有更好的字段可读：2026-11-02 对真机做过 96 字段全量 dump，并核对了厂商 Web UI
 * 引用的 203 个字段名 —— **这台固件的 goform 没有 data switch 一类的字段**，厂商 UI 的
 * 那个开关同样是从 `ppp_status` 推的。开关只有两条写命令（`CONNECT_NETWORK` /
 * `DISCONNECT_NETWORK`，`notCallback=true`），**没有配套的读命令**。
 *
 * 所以「开关是开是关」只能由 core 自己记住：谁发起的写、用户想要的是开还是关。
 *
 * ## 判据（四条，缺一条就会出一个方向的错）
 *
 * 1. **链路通 ⇒ 开关必然开着**：设备自己拨上了，说明数据开关没被关（这条同时把外部改动
 *    拉回来，比如厂商 UI 里拨号成功）。
 * 2. **显式写优先**：`setMobileData` / `/connect` / `/disconnect` 成功后记下意图。
 *    「关」要在 [WRITE_SETTLE_MS] 内压过链路 —— 设备拆链要几秒，这几秒里链路还是通的，
 *    不能据此把刚关掉的开关翻回开。
 * 3. **已知拆链的操作有豁免窗口**（[beginLinkTransition]，见下）：换制式、锁频重启网络栈、
 *    拨号，这三件事**注定**要断一会儿。窗口内的断链一律按「开着」报，不视作断开。
 * 4. **来路不明的断链只容忍 [LINK_DOWN_SETTLE_MS]**：没有已知操作却断了，多半是信号抖动
 *    或厂商 UI 里被手动断开；几轮轮询后仍断就认定确实关了（同时把意图同步成关，
 *    否则显示会永远停在开）。
 *
 * ## 豁免窗口为什么要和通用容忍分开
 *
 * 早期版本只有一条 120s 的宽限期，对所有断链生效 —— 那等于「厂商 UI 里手动断开」也要
 * 最多两分钟才显示出来。2026-11-02 用户明确要求：**切制式肯定会断开一会，但不能视作断开**。
 * 所以拆成两条：**知道原因的**给足 120s，**不知道原因的** 30s 内必须收敛。
 *
 * ## 边界
 *
 * - **纯内存**：core 重启后意图归零，第一次读按链路初始化（断链 → 关）。重启是低频事件，
 *   不为此落盘 —— 落盘要处理"跨重启的旧意图把真实断开盖住"，代价大于收益。
 * - 本类不碰任何设备接口，只做状态判定，便于单测（时钟可注入）。
 */
class MobileDataIntent(
    private val clock: () -> Long = System::currentTimeMillis
) {
    /** 用户意图：true = 开 / false = 关；null = 本进程还没见过任何一次明确的开关动作。 */
    @Volatile
    private var intent: Boolean? = null

    /** 最近一次显式写的时刻。用于「刚点关、链路还没拆完」的短暂压制。 */
    @Volatile
    private var lastWriteAt: Long = Long.MIN_VALUE / 2

    /** 链路从「通」变成「断」的时刻；null = 当前没有观察到断链窗口。 */
    @Volatile
    private var linkDownSince: Long? = null

    /** 豁免窗口的截止时刻：此刻之前观察到的断链都算「已知的、预期内的」。 */
    @Volatile
    private var transitionUntil: Long = Long.MIN_VALUE / 2

    /**
     * 记一次**由 core 发起**的开关动作（[NetworkController.setMobileData] /
     * `connectNetwork` / `disconnectNetwork` 成功时调用）。
     *
     * 只在写**成功**后调用：设备拒了的那次不该改变开关显示。
     *
     * 打开数据同时会开一个豁免窗口 —— 拨号本身要十几秒，这期间链路是断的，
     * 不能因为「刚点开就显示关」再把用户吓一次。
     */
    fun onExplicitWrite(enabled: Boolean) {
        val now = clock()
        intent = enabled
        lastWriteAt = now
        if (enabled) {
            linkDownSince = now
            beginLinkTransition(now = now)
        } else {
            linkDownSince = null
            // 关数据是明确的"不要连"，没有豁免可言
            transitionUntil = Long.MIN_VALUE / 2
        }
    }

    /**
     * 标记「接下来这段时间的断链是预期内的」。
     *
     * 调用点（都在 [NetworkController]）：
     * - `setNetworkMode`：`SET_BEARER_PREFERENCE` 必然拆 PDP/PPP；
     * - `restartNetworkStack`：`AT+SFUN` 重启协议栈，锁频那一路会走到；
     * - [onExplicitWrite] 的「开」分支：拨号过程本身。
     *
     * 窗口**只延长不缩短**：同一串操作里（切制式 → 恢复数据 → 重注册）谁先谁后不确定，
     * 以最后一个截止时刻为准最稳。窗口内的断链不会把开关翻成关，也不会重置意图。
     *
     * @param windowMs 豁免时长，默认 [TRANSITION_WINDOW_MS]
     */
    fun beginLinkTransition(
        windowMs: Long = TRANSITION_WINDOW_MS,
        now: Long = clock()
    ) {
        if (now + windowMs > transitionUntil) transitionUntil = now + windowMs
    }

    /**
     * 结合一次链路读数，给出**要对外报的开关状态**。
     *
     * @param linkConnected 设备当前链路：true 通 / false 断 / **null 读不到**（读不到不能当断）
     * @return true / false；**null = 连意图都没有**（core 刚起且链路也读不到），调用方自行兜底
     */
    fun resolve(linkConnected: Boolean?, now: Long = clock()): Boolean? {
        val known = intent

        // ① 链路通 ⇒ 开关开着。唯一的例外是「刚点关、设备还没拆完链路」的那几秒。
        if (linkConnected == true) {
            linkDownSince = null
            if (known == false && now - lastWriteAt < WRITE_SETTLE_MS) return false
            intent = true
            return true
        }

        // ② 链路读不到：只能靠意图。没有意图就如实回"不知道"，由调用方退回 shell 判断。
        if (linkConnected == null) return known

        // ③ 链路断。
        if (known == null) {
            // 进程内第一次判断且链路是断的：按设备真值初始化（与改造前行为一致）。
            intent = false
            return false
        }
        if (!known) {
            linkDownSince = null
            return false
        }

        // ④ 意图"开" + 链路断：先看是不是我们自己造的（豁免窗口），再看抖动容忍。
        val since = linkDownSince ?: now.also { linkDownSince = it }
        if (now < transitionUntil) return true
        if (now - since < LINK_DOWN_SETTLE_MS) return true

        intent = false
        linkDownSince = null
        transitionUntil = Long.MIN_VALUE / 2
        return false
    }

    companion object {
        /**
         * **已知**拆链操作的豁免时长（切制式 / 锁频重启网络栈 / 拨号）。
         *
         * 取 120s：真机换制式后重新注册要十几到二十几秒（`docs/UFI-AXIS-Core-API-Reference.md`
         * 的 `/api/network/mode` 一节），弱信号下更久；再叠加一次读失败的 3s 超时与两轮 10s 轮询，
         * 60s 会把"等设备拨上来"误判成"用户关了"。宁可多显示两分钟开，也不要再来一次假关闭 ——
         * 链路真断时 `network.is_connected` 与 `ppp_status` 仍是实时的，界面不会失去判断依据。
         */
        const val TRANSITION_WINDOW_MS = 120_000L

        /**
         * **来路不明**的断链容忍时长。
         *
         * 没有已知拆链操作时的断链（信号抖动 / 厂商 UI 手动断开 / 拨号失败）。
         * 取 30s：够覆盖两三轮 10s 轮询，又不至于让"确实被关了"藏太久。
         */
        const val LINK_DOWN_SETTLE_MS = 30_000L

        /**
         * 显式写之后，链路读数在多长时间内不许覆盖它。
         *
         * 「关」在设备侧要走拆链流程（`DISCONNECT_NETWORK` 到 ppp 真断有几秒），
         * 这段时间链路读出来还是通的。15s 覆盖住这段，同时不至于让"关失败"这件事藏太久。
         */
        const val WRITE_SETTLE_MS = 15_000L
    }
}
