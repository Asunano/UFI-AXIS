package com.ufi_axis_core.notify

/**
 * 通知级别。
 *
 * 与 `PushNotification.level`（线上契约，小写字符串）之间的转换由 [PushChannel] 负责 ——
 * 触发源用枚举，出口才落到字符串，避免每个触发源各写一次 `"warning"` 这样的字面量。
 *
 * @param label 给人看的中文级别名（Webhook 的 `{{level_label}}`、界面上的级别标签都取它）。
 *   放在枚举上而不是让 app / web / 模板各维护一张 `"warning" -> "警告"` 的映射表：
 *   那几张表加一档新级别时**不会报错**，只会在界面上显示成空白或原样的英文枚举名
 *   （口径同 [SkipReason.label]）。日志与线上契约仍然走 [wireName]，
 *   本仓纪律是文案给人看、日志给排查看。
 */
enum class NotifyLevel(val label: String) {
    INFO("提示"), WARNING("警告"), CRITICAL("严重");

    /** 契约口径的小写名（`PushNotification.level` 一直是小写，app 与 web 都按小写解析）。 */
    val wireName: String get() = name.lowercase()

    companion object {
        /**
         * 从契约口径的小写名反解。
         *
         * 认不出来一律回落 [INFO]：级别只影响展示强度，一个拼错的字符串不该让整条通知发不出去。
         * `AlertEngine` 内部的级别是字符串（`"info"` / `"warning"` / `"critical"`，
         * 与 `alert_records.level` 列同一套），emit 时经这里转成枚举。
         */
        fun fromWire(name: String): NotifyLevel =
            entries.firstOrNull { it.wireName == name.lowercase() } ?: INFO
    }
}


/**
 * 一条待投递的通知。
 *
 * 这是 12 个触发源与全部投递渠道之间**唯一**的中间表示：触发源只构造它，
 * 渠道只消费它。渠道因此不需要知道事件来自告警还是下载，触发源也不需要知道
 * 装了几个渠道 —— 在此之前每加一个渠道都要改 6 个触发源加装配层 6 条 lambda。
 */
data class NotifyEvent(
    /**
     * 场景 id。与 app 的 `NotifyScene.sceneId`、邮件 `SmsForwardConfig.scenes` 是**同一套字符串**，
     * 不新造第二套 —— 场景开关的真源在 core 的 scenes 集合里，两套 id 一分叉就会出现
     * 「界面上关了但还在发」。
     */
    val scene: String,
    /**
     * 推送契约里的类型名（`PushNotification.type`）。**缺省与 [scene] 相同**。
     *
     * 为什么不能只有 scene：这是**两套粒度不同的分类**，且都在线上。
     * - `scene` 是「用户在邮件设置里勾的那一格」，告警全归 `alert` 一格（否则 8 种阈值告警
     *   要勾 8 次）；
     * - `type` 是告警的**具体类型**（`temperature` / `battery` / `traffic` / `signal` /
     *   `connectivity` / `device_online` / `device_offline` / `traffic_limit`），app 的
     *   `:ufi_notify` 守护进程靠它重建 `AlertRecord`、web 靠它筛选与配色。
     *
     * 把两者合并成一个字段，推送里所有告警的 type 都会变成 `alert` —— 客户端从此分不出
     * 是温度还是断网。这不是理论风险：合并版本在 `AlertEngineDedupTest` 上直接红了
     * （`expected:<temperature> but was:<alert>`）。
     */
    val type: String = scene,
    val level: NotifyLevel,
    val title: String,
    /**
     * 推送契约里的标题（`PushNotification.title`）。**null = 用 [title]**。
     *
     * 为什么要两个：两个出口对"标题"的要求相反，而其中一个是**线上契约**。
     * - 邮件主题要有信息量（`MailChannel.subjectOf` 拿 [title] 拼出 `[UFI-AXIS] 设备温度过高`），
     *   放固定词等于收件箱里一排一模一样的标题；
     * - 推送的 title 在告警链路上历史取值是固定串「设备告警」。app 的 `NotifyService` 确实
     *   不读它（它从 type/level/message/extra 重建 `AlertRecord`），但 `alert` topic 上还有
     *   web 与 app 主进程 UI 在消费同一份 payload，是否渲染 title 未逐一核实 ——
     *   所以由触发源显式声明"推送那边保持原样"，而不是让它跟着邮件主题一起变。
     *
     * 只有告警会给这个字段（`AlertEngine.PUSH_TITLE`）；其余场景两处标题本来就一致。
     */
    val pushTitle: String? = null,
    val body: String,

    /** 邮件模板的 meta 行（`"发件人" to "10086"`）。纯文本类渠道把它拼进正文。 */
    val meta: List<Pair<String, String>> = emptyList(),
    /** 需要高亮的短值（验证码）。邮件渲染成大号块，其它渠道可以放标题。 */
    val highlight: String? = null,
    /** 沿用 `PushNotification.extra`：id / value / threshold / aggregated / task_id 等。 */
    val extra: Map<String, String> = emptyMap(),
    val timestamp: Long = System.currentTimeMillis(),
    /**
     * 手动触发（`POST /api/sms-forward/test`）。
     *
     * true 时**跳过总开关与免打扰**，但仍要求渠道配置齐全。
     * 理由：用户刚按下"发送测试"，静默什么都不发比发出去更难排查；端点响应里会
     * 明确回一个 `auto_notify_enabled` 告知总开关当前状态，不做无声跳过。
     */
    val manual: Boolean = false,
    /**
     * 渠道**白名单**："只投这几个"；**null = 不做白名单限定**。
     *
     * 存在的唯一理由：有几条链路只有**一个**渠道该收，而且那个渠道是**指定的哪一个**：
     * - 告警**聚合更新**（同 type+level 只累加 count）只该推送、**不该再发一封邮件** ——
     *   这是 `AlertEngine.triggerAlert` 的既有语义（稳态超标否则会变成邮件轰炸），
     *   不能因为接了分发器就丢掉；
     * - 各渠道的"发送测试"端点（`/api/sms-forward/test`、`/api/notify/{渠道}/test`）只该走
     *   **被测的那一条**链路，投给别人等于按一次测试收到三种通知。
     *
     * 不认识的渠道 id 会被忽略（不报错）：白名单是"想投给谁"，不是"必须存在谁"。
     *
     * **想表达"除了某一个渠道，其它都投"时用 [exclude]，不要在这里列渠道 id** ——
     * 那种清单每加一条新渠道都要回来补，漏补的表现就是"用户勾了却不触发"的假开关。
     */
    val channels: Set<String>? = null,
    /**
     * 渠道**黑名单**："除这几个之外，所有已注册渠道都投"；**null / 空 = 不排除任何渠道**。
     *
     * 与 [channels] 的分工：[channels] 是白名单（"只投这些"），本字段是黑名单
     * （"除了这些都投"）。**两者不应同时给值** —— 同时给值时以 [channels] 为准，
     * 并由 `NotificationDispatcher.emit` 打一条 WARN（不静默：两个都写上说明触发源
     * 对自己想投给谁没想清楚，静默采纳一个等于把这个歧义埋起来）。
     *
     * 为什么需要黑名单而不是让触发源把渠道列全：**新增渠道时不必回来改触发源**。
     * 用白名单表达"除推送之外都投"的话，那份清单会随渠道数量增长，
     * 而漏补一个渠道的表现是"用户在那条渠道里勾了这个场景却永远不触发" ——
     * 那正是 2026-09-10 这次修掉的假开关。
     *
     * 目前两处用它，理由都是**同一条事件在推送侧另有触发源**（两边同时投会重复推送）：
     * - 设备新短信 / 验证码（`SmsForwardController.forwardSms`）—— 推送侧由
     *   `DataScheduler` 用"每轮只推最新一条"的去重键单独 emit；
     * - 电池事件（`BackendService`）—— 推送侧客户端没有对应的分流分支，
     *   走推送会被当成阈值告警处理，见 [NotifyScenes.BATTERY]。
     *
     * 被排除的渠道**不产生 [DeliveryOutcome.Skipped] 结果、也不打日志**：它不是"没发出去"，
     * 而是"这条投递路径不负责它"。混进 `Skipped` 里会让排查时以为通知漏了。
     */
    val exclude: Set<String>? = null
)
