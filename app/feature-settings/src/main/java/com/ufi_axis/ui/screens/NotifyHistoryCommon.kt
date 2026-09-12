package com.ufi_axis.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.ufi_axis.ui.components.common.UfiLoadingIndicator
import com.ufi_axis.ui.theme.Spacing

// ════════════════════════════════════════════════════
// 记录页的共用件
// ════════════════════════════════════════════════════
//
// 2026-09-08：原来「通知历史」是一个双 Tab 页，现已拆成两个独立页 ——
// [SystemNotifyHistoryScreen]（本机状态栏通知）与 [DeliveryHistoryScreen]（设备端投递记录），
// 入口分别落在「通知管理」和各条渠道的配置页里，各自与自己那半的设置放在一起。
//
// 2026-09-10：投递记录页改为按渠道进入（邮件 / Webhook / 本机短信各一个入口，同一个 composable），
// 所以共用件又多了「入口行摘要」一项 —— 三个入口的右侧文案必须逐字一致，
// 各写一遍就会分叉成三种口径。
//
// 差异部分（图标 / 标题 / 筛选口径）留在各自的页面文件里，不硬凑成一个"通用列表组件"。

/** 触底预加载提前量：倒数第 3 条进入视口就去拉下一页。两个列表一致。 */
internal const val HISTORY_LOAD_MORE_AHEAD = 3

/** 一行说明最多几行（时间 + 内容 + 结果三段，内容可能折行）。 */
internal const val HISTORY_DESCRIPTION_MAX_LINES = 4

/**
 * 「不过滤」那一档 chip 的 value。
 *
 * 两个记录页都需要一个"全部"档，而它在两边都**不是**接口/DAO 认得的取值 ——
 * 各自的映射函数负责把它翻成 null / `Filter.ALL`。
 */
internal const val HISTORY_FILTER_ALL = "all"

/**
 * 投递渠道 id（core 的 `channel` 列 / `channel` 查询参数）。
 *
 * 放共用件里而不是各页面各写一份字面量：这三个值同时出现在四个文件里
 * （记录页 + 三个渠道配置页的入口卡），写错一个的表现是"入口点进去列表是空的"，
 * 而空列表在这一页是完全正常的状态，肉眼分不出来。
 */
internal const val DELIVERY_CHANNEL_MAIL = "mail"
internal const val DELIVERY_CHANNEL_WEBHOOK = "webhook"
internal const val DELIVERY_CHANNEL_LOCAL_SMS = "local_sms"

/** 三条渠道入口卡的标题与副文案：同一件事在三处必须是同一句话。 */
internal const val DELIVERY_HISTORY_ENTRY_TITLE = "最近投递"
internal const val DELIVERY_HISTORY_ENTRY_DESCRIPTION = "这条渠道每次投递的结果，含未发出的原因"

/**
 * 投递记录入口行的右侧摘要（三条渠道共用）。
 *
 * 先说没发出、再说被跳过、最后才说总数：用户点进那一页多半是在查"哪条没送到"，
 * 总条数回答不了这个问题。失败与跳过**分两档而不是相加** ——
 * 跳过是闸门按用户自己的配置拦下的，并进失败数会让人去排一个不存在的故障。
 *
 * @param ready state 里装的就是本渠道的数据且已完成过一次加载。三条渠道共用同一个 state 槽位，
 *   为 false 时只能说"加载中"，否则会把上一个渠道的数字显示成本渠道的。
 */
internal fun deliveryHistorySummary(
    ready: Boolean,
    total: Int,
    failedTotal: Int,
    skippedTotal: Int
): String = when {
    !ready -> "加载中"
    failedTotal > 0 -> "$failedTotal 条没发出"
    skippedTotal > 0 -> "$skippedTotal 条已跳过"
    else -> "共 $total 条"
}

// 场景 id → 中文名不在本文件：唯一那张词表在 `NotifySceneLabels.kt`（[sceneLabel]）。
// 2026-09-11 删掉了这里原有的第二张 map —— 它与那张已经分叉（sms「新短信」vs「短信正文」、
// download「下载完成」vs「下载结束」、tunnel「内网穿透」vs「隧道异常」），
// 而且缺 battery / test 两档，于是电池投递记录在这一页显示成裸的 `battery`。

/** 底部加载指示：这是真 IO 分页，不给反馈用户会以为列表到底了。 */
internal fun LazyListScope.historyLoadMoreRow() {
    item(key = "history-load-more") {
        Box(
            modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.Large),
            contentAlignment = Alignment.Center
        ) {
            UfiLoadingIndicator(modifier = Modifier.size(Spacing.IconSizeLarge))
        }
    }
}

/**
 * 「最多 N 条 · 最近 M 天」——两道保留上限的一句话摘要。
 *
 * 入口行的右侧摘要与保留上限弹窗顶部共用同一份文案：两处写法不一致时，
 * 用户会以为自己看到的是两个不同的设置。`ageDays <= 0` 必须说成"不限时长"，
 * 不能省略 —— 省了就没人知道时间那道闸是关着的。
 */
internal fun historyRetentionSummary(rows: Int, ageDays: Int): String =
    if (ageDays <= 0) "最多 $rows 条 · 不限时长" else "最多 $rows 条 · 最近 $ageDays 天"
