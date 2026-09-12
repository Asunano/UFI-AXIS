package com.ufi_axis_core.notify

/**
 * 一条渠道的**路由规则与配额计数器**。
 *
 * ## 为什么三条渠道形状完全一样（同构）
 *
 * 邮件 / Webhook / 本机短信在用户眼里是三个"通知渠道"，可它们此前各有各的旋钮：
 * 只有本机短信有最低级别与每日上限，另两条什么都没有。后果是用户每进一个渠道页
 * 都要重新猜"这个渠道有没有这道闸" —— 而"猜错"的表现是**收不到通知**或**收到一堆通知**，
 * 两种都要等到出事才发现。
 *
 * 同构之后这三个旋钮在任一渠道页上都是同一组：级别门槛、每日上限、场景勾选。
 * 判定顺序也只有一份（[NotificationDispatcher.deliverTo]），不再"哪条渠道自己判哪几道"。
 *
 * ## 每条渠道各持一份取值与**各自独立**的计数器，绝不共享
 *
 * 三条渠道各读自己的配置、各记自己的"今天发了几条"，物理上落在三份不同的存储里
 * （邮件 `sms_forward`、Webhook `notify_webhook`、本机短信 `notify_local_sms`，
 * 各自一对 `quota_day` / `quota_count` 键）。
 *
 * 共享一个全局计数器意味着**邮件发多了会吃掉短信的额度** —— 用户配的"短信一天 5 条"
 * 会因为白天几十封邮件而在真出事的那一刻已经归零。那正是"某天突然收不到关键短信"
 * 这类最难排查的故障：三条链路都没报错，账面上也没超过任何一条渠道自己设的上限。
 *
 * ## 判定同构在分发器，记账各自在渠道
 *
 * 分发器只**读**本接口（够不够级别、还有没有配额），**消耗**配额留在渠道里 ——
 * 只有渠道知道"这次到底算不算发出去了"：
 * - 本机短信的口径是**排除不了已经发出去**（`LocalSmsDelivery.countsTowardQuota`：
 *   只有"设备明确拒收"那一档不计，而它恰好是唯一可重试的一档，于是重试永远不会重复扣配额）；
 * - 邮件与 Webhook 只在 [DeliveryOutcome.Sent] 时计一条（SMTP 250 应答 / HTTP 2xx
 *   是确定结论，不存在"可能发出去了"这种中间态）。
 *
 * 把消耗也搬到分发器就必须让它认识这三套结论映射，而它连 `GoformSmsClient` 都不该知道。
 *
 * ## 为什么是方法而不是属性
 *
 * 每次**现读**配置与计数器。缓存一份取值（构造时读一次存下来）等于"改了上限但要重启
 * core 才生效" —— 本仓明令禁止的假开关，而用户随时可以在设置页改这两个值。
 */
interface ChannelRules {

    /**
     * 本渠道的最低投递级别：低于它的事件拿 `Skipped(`[SkipReason.LEVEL_TOO_LOW]`)`。
     *
     * 严重度序就是 [NotifyLevel] 的声明序（INFO < WARNING < CRITICAL）。
     */
    fun minLevel(): NotifyLevel

    /** 每日条数上限；[UNLIMITED] = 不限。 */
    fun dailyLimit(): Int

    /**
     * 今天已用几条（**本地日期跨天重置**）。
     *
     * 按本地日期而不是"24 小时滑动窗口"：用户脑子里的模型就是"今天还剩几条"，
     * 滑动窗口会出现"明明一整天没发却说超额"（昨天 23:50 那条还在窗口里）。
     */
    fun sentToday(): Int

    companion object {
        /**
         * 不限条数。
         *
         * 用 0 而不是 -1 或 `Int.MAX_VALUE`：0 是"一条都不许发"在语义上唯一说不通的取值
         * （那种诉求用渠道开关表达），所以拿它当"不限"不会与任何真实上限撞车。
         */
        const val UNLIMITED = 0
    }
}

/**
 * 配额还有余量吗。**只读，不消耗** —— 消耗由渠道在拿到结论之后自己做（见 [ChannelRules]）。
 *
 * [ChannelRules.UNLIMITED] 恒为 true：邮件与 Webhook 的默认值就是不限，
 * 这条分支保证这次改造不会让存量用户突然收不到通知。
 */
fun ChannelRules.hasQuota(): Boolean {
    val limit = dailyLimit()
    return limit == ChannelRules.UNLIMITED || sentToday() < limit
}

/**
 * 今天还剩几条。
 *
 * 钳到 0 是因为用户可以把上限从 20 改到 5，此时"已用 12"会算出 -7 ——
 * 界面上"今日 12/5"已经够怪了，别再来个负数。
 *
 * [ChannelRules.UNLIMITED] 时返回 [Int.MAX_VALUE] 而**不是** 0：0 的含义是"已经用尽"，
 * 与"不限"恰好相反，任何按 `remaining() > 0` 判断的地方都会得到反向结论。
 * 需要展示给用户时先看 [ChannelRules.dailyLimit]（REST 层把这一档回成 `null`）。
 */
fun ChannelRules.remaining(): Int {
    val limit = dailyLimit()
    if (limit == ChannelRules.UNLIMITED) return Int.MAX_VALUE
    return (limit - sentToday()).coerceAtLeast(0)
}
