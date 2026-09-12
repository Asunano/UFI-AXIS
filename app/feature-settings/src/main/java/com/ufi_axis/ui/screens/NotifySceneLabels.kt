package com.ufi_axis.ui.screens

import com.ufi_axis.data.notification.NotifyScene

/**
 * 场景 id → 中文标签的**唯一映射**。
 *
 * 2026-09-09 从 [EmailNotifyScreen] 的私有 `MAIL_SCENES` 提出来：Webhook 渠道
 * （`WebhookConfig.scenes`）勾的是同一套场景 id，两个页面各留一份的话，
 * 加一个场景就必然只改了一处 —— 表现是"邮件页能勾、Webhook 页看不到"。
 *
 * 2026-09-11：投递记录页（`NotifyHistoryCommon.kt`）曾经另有一张同用途的 map，
 * 两张表已经分叉（sms「新短信」vs「短信正文」、download「下载完成」vs「下载结束」、
 * tunnel「内网穿透」vs「隧道异常」），且那张表缺 `battery` / `test` 两档 ——
 * 电池投递记录因此显示成裸 id。现在只留本表一份，取法与 web 的 `SCENE_LABELS` 一致：
 * **词表一张，「哪一条渠道能勾哪几个」由 [NOTIFY_SCENE_CHIP_IDS] 单独决定**。
 *
 * 这些字符串必须与 core `NotifyScenes` 里的常量逐字一致（那才是真源）。
 * 能借 [NotifyScene] 的就借（app 侧枚举本身就是那份词表的镜像），只有
 * [NOTIFY_SCENE_BATTERY] 与 [NOTIFY_SCENE_TEST] 例外 —— 见它们自己的注释。
 */
private val NOTIFY_SCENE_LABEL_BY_ID: Map<String, String> = mapOf(
    NotifyScene.SMS.sceneId to "短信正文",
    NotifyScene.VERIFICATION_CODE.sceneId to "验证码",
    NotifyScene.ALERT.sceneId to "阈值告警",
    NotifyScene.CONNECTIVITY.sceneId to "离线/上线",
    NotifyScene.TRAFFIC_80.sceneId to "流量预警",
    NotifyScene.DOWNLOAD.sceneId to "下载结束",
    NotifyScene.TUNNEL.sceneId to "隧道异常",
    NotifyScene.DEVICE_EVENTS.sceneId to "设备事件",
    NOTIFY_SCENE_BATTERY to "电池状态",
    NOTIFY_SCENE_TEST to "手动测试"
)

/**
 * 勾选栏里出现的场景 id，**顺序即 chip 顺序**。
 *
 * 与 [NOTIFY_SCENE_LABEL_BY_ID] 的差别只有一个：这里**不含** [NOTIFY_SCENE_TEST]。
 * 测试信一律带 `manual = true`，分发器对 manual 事件跳过场景判定 ——
 * 把它摆进勾选栏等于给用户一个不起作用的勾。但它会出现在投递记录里，
 * 所以词表里必须有它，否则那一行显示成裸的 `test`。
 */
private val NOTIFY_SCENE_CHIP_IDS: List<String> = listOf(
    NotifyScene.SMS.sceneId,
    NotifyScene.VERIFICATION_CODE.sceneId,
    NotifyScene.ALERT.sceneId,
    NotifyScene.CONNECTIVITY.sceneId,
    NotifyScene.TRAFFIC_80.sceneId,
    NotifyScene.DOWNLOAD.sceneId,
    NotifyScene.TUNNEL.sceneId,
    NotifyScene.DEVICE_EVENTS.sceneId,
    NOTIFY_SCENE_BATTERY
)

/** 场景勾选 chip 的 `id to 标签`（三条渠道共用，顺序即展示顺序）。 */
internal val NOTIFY_SCENE_LABELS: List<Pair<String, String>> =
    NOTIFY_SCENE_CHIP_IDS.map { it to sceneLabel(it) }

/**
 * 场景 id → 中文名。投递记录页与系统通知记录页都用它。
 *
 * 认不出的 id **原样显示**：记录里可能出现新版 core 才有的场景，
 * 显示 id 至少还能对着代码查，显示"未知"就断了线索。
 */
internal fun sceneLabel(sceneId: String): String = NOTIFY_SCENE_LABEL_BY_ID[sceneId] ?: sceneId

/**
 * 设备电池事件的场景 id（core `NotifyScenes.BATTERY`）。
 *
 * 只在这里以字面量出现，因为 app 侧**没有**对应的 [NotifyScene]：那个枚举的语义是
 * "本机要不要弹状态栏通知"，而电池事件不进推送渠道（理由见 core 的 `NotifyScenes.BATTERY`
 * 注释：app 的推送分流认不出这个 type，会把它当成阈值告警处理）。
 * 硬给它造一个通知场景枚举项才是假开关。
 */
internal const val NOTIFY_SCENE_BATTERY = "battery"

/**
 * 手动测试信的场景 id（core `NotifyScenes.TEST`）。
 *
 * 同样没有对应的 [NotifyScene]：它不是"一类会自动发生的事"，而是用户按下「发送测试」
 * 那一下。只用于**投递记录**的行标题，不进 [NOTIFY_SCENE_CHIP_IDS]。
 */
internal const val NOTIFY_SCENE_TEST = "test"

/**
 * 场景勾选弹窗共用的补充说明。
 *
 * 三条渠道（邮件 / Webhook / 本机短信）的场景弹窗都要说这一句，所以放这里 ——
 * 与 [NOTIFY_SCENE_LABELS] 同一个理由：各写一份必然分叉，改一处漏两处。
 */
internal const val NOTIFY_SCENE_BATTERY_NOTE =
    "「电池状态」通过邮件、Webhook 与本机短信发送，不在手机通知栏显示。"
