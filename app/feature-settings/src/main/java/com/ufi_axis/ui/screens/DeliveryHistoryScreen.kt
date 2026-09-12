package com.ufi_axis.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Http
import androidx.compose.material.icons.filled.MailOutline
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavHostController
import com.ufi_axis.data.model.DeliveryOutcome
import com.ufi_axis.data.model.MailSendRecord
import com.ufi_axis.data.model.deliveryOutcome
import com.ufi_axis.ui.components.common.*
import com.ufi_axis.ui.theme.LocalResolvedPalette
import com.ufi_axis.ui.theme.Spacing
import com.ufi_axis.util.FormatUtils
import com.ufi_axis.viewmodel.MainViewModel
import kotlinx.coroutines.launch

/**
 * 「最近投递」——某一条通知渠道每次投递的结果。
 *
 * 一个 composable 服务三条渠道（邮件 / Webhook / 本机短信），渠道由路由参数带进来：
 * 三条渠道的列表形态、筛选口径、翻页逻辑完全一样，复制三份的唯一产物是三处会各自跑偏的文案。
 * 入口分别落在各渠道自己的配置页里 —— 记录要跟产生它的那套配置放在一起才找得到。
 *
 * 数据源是设备端 `GET /api/sms-forward/history`（core 的 `mail_send_records` 表）。
 * 结果是**三态**，不是"成功/失败"两态；判定走 [com.ufi_axis.data.model.deliveryOutcome]
 * （全 app 唯一一处，也是它兜住"设备上的 core 还没升到 DB v12"的地方）：
 *
 * | 结论 | 含义 | 行渲染 |
 * |------|------|--------|
 * | SENT | 已发出 | accent |
 * | FAILED | 发起了投递但失败，`error` 是异常链 | error |
 * | SKIPPED | 没发起，`error` 是中文跳过原因 | 次要色 |
 *
 * 跳过原因（总开关关着 / 场景没勾 / 级别不够 / 配额用尽 / 配置不完整）由 core 写成中文直接显示，
 * app 侧不维护第二张映射表 —— 抄一份的结果是 core 新增一种原因、这里显示成空白。
 *
 * 壳必须用 [UfiPageBackgroundBox]（Box 版）：本页是 LazyColumn，而 [UfiPageBackground]
 * 自带 verticalScroll，把 LazyColumn 套进去会收到 infinity 最大高度直接抛异常。
 *
 * @param channel 渠道 id（`mail` / `webhook` / `local_sms`）。它同时决定标题、图标与查询参数。
 */
@Composable
fun DeliveryHistoryScreen(
    viewModel: MainViewModel,
    navController: NavHostController,
    channel: String
) {
    val palette = LocalResolvedPalette.current
    val toolsState by viewModel.toolsState.collectAsState()
    val actionScope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    var showClearConfirm by remember { mutableStateOf(false) }
    var toastMessage by remember { mutableStateOf<ToastMessage?>(null) }

    // 进页面按渠道拉第一页；回到前台再拉一次（记录是设备侧产生的，离开这段时间会变）。
    LaunchedEffect(channel) { viewModel.tools.loadDeliveryHistory(channel) }
    rememberResumeRefresh {
        viewModel.tools.loadDeliveryHistory(channel, toolsState.deliveryHistoryResult)
    }

    LaunchedEffect(listState, toolsState.deliveryHistory.size) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
            .collect { lastVisible ->
                if (lastVisible >= 0 &&
                    lastVisible >= toolsState.deliveryHistory.size - HISTORY_LOAD_MORE_AHEAD
                ) {
                    viewModel.tools.loadMoreDeliveryHistory()
                }
            }
    }

    UfiScreenScaffold(
        title = deliveryHistoryTitle(channel),
        navController = navController,
        showBack = true,
        actions = {
            IconButton(
                onClick = { showClearConfirm = true },
                enabled = toolsState.deliveryHistory.isNotEmpty()
            ) {
                Icon(
                    Icons.Default.DeleteSweep,
                    contentDescription = "清空记录",
                    tint = palette.textSecondary
                )
            }
        }
    ) { padding ->
        UfiPageBackgroundBox(modifier = Modifier.fillMaxSize().padding(padding)) {
            Column(Modifier.fillMaxSize()) {
                // 过滤走服务端 `result` 参数：只看没发出时用户想翻的是**全表**记录，
                // 本地筛当前页会出现「明明统计里有 12 条没发出，列表只显示 2 条」。
                UfiSingleChipSelector(
                    options = DELIVERY_HISTORY_FILTERS,
                    selectedValue = toolsState.deliveryHistoryResult ?: HISTORY_FILTER_ALL,
                    onSelect = { value ->
                        viewModel.tools.loadDeliveryHistory(
                            channel = channel,
                            result = value.takeIf { it != HISTORY_FILTER_ALL }
                        )
                    },
                    modifier = Modifier.padding(
                        horizontal = Spacing.CardHorizontalMargin,
                        vertical = Spacing.Medium
                    )
                )
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    DeliveryHistoryList(
                        records = toolsState.deliveryHistory,
                        channel = channel,
                        listState = listState,
                        firstLoadPending = !toolsState.deliveryHistoryLoaded,
                        hasMore = toolsState.deliveryHistoryHasMore,
                        filtered = toolsState.deliveryHistoryResult != null
                    )
                }
            }
        }

        if (showClearConfirm) {
            UfiConfirmDialog(
                visible = true,
                title = "清空记录",
                // 文案与实际下发的 channel 参数对齐：只清当前这一条渠道，另两条不动。
                text = "删除设备上保存的「${deliveryHistoryTitle(channel)}」，" +
                    "其他渠道的记录不受影响。已经发出的通知本身也不受影响。",
                confirmText = "清空",
                destructive = true,
                onDismiss = { showClearConfirm = false },
                onConfirm = {
                    showClearConfirm = false
                    actionScope.launch {
                        toastMessage = if (viewModel.tools.clearDeliveryHistory()) {
                            ToastMessage("已清空", ToastType.SUCCESS)
                        } else {
                            ToastMessage("清空失败，请重试", ToastType.ERROR)
                        }
                    }
                }
            )
        }

        UfiToastHost(toastMessage = toastMessage, onDismiss = { toastMessage = null })
    }
}

/**
 * 结果筛选。value 直接就是接口的 `result` 取值，[HISTORY_FILTER_ALL] 那一档翻成"不传"。
 *
 * 「没发出」与「已跳过」必须是两档：前者是投递出了故障（要去排 SMTP / HTTP / 信令），
 * 后者是闸门按用户自己的配置拦下的（要去改设置）。合成一档等于把两种处置方式混在一起。
 */
private val DELIVERY_HISTORY_FILTERS = listOf(
    HISTORY_FILTER_ALL to "全部",
    RESULT_SENT to "已发出",
    RESULT_FAILED to "没发出",
    RESULT_SKIPPED to "已跳过"
)

@Composable
private fun DeliveryHistoryList(
    records: List<MailSendRecord>,
    channel: String,
    listState: LazyListState,
    firstLoadPending: Boolean,
    hasMore: Boolean,
    filtered: Boolean
) {
    val palette = LocalResolvedPalette.current
    val icon = deliveryChannelIcon(channel)
    when {
        records.isNotEmpty() -> LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = Spacing.Large),
            verticalArrangement = Arrangement.spacedBy(Spacing.Medium)
        ) {
            items(records, key = { it.id }) { record ->
                UfiSettingsRowCard {
                    UfiSettingsItem(
                        title = record.subject.ifBlank { sceneLabel(record.scene) },
                        description = deliveryDescription(record),
                        descriptionMaxLines = HISTORY_DESCRIPTION_MAX_LINES,
                        icon = icon,
                        // 跳过用次要色而不是 error：它不是故障，涂成红的会让人去排一个不存在的问题。
                        iconTint = when (record.deliveryOutcome) {
                            DeliveryOutcome.SENT -> palette.accent
                            DeliveryOutcome.SKIPPED -> palette.textSecondary
                            DeliveryOutcome.FAILED -> palette.error
                        }
                    )
                }
            }
            if (hasMore) historyLoadMoreRow()
        }
        firstLoadPending -> UfiSkeletonList(
            modifier = Modifier.padding(
                horizontal = Spacing.CardHorizontalMargin,
                vertical = Spacing.Large
            )
        )
        else -> UfiEmptyState(
            icon = icon,
            message = if (filtered) "这个筛选下没有记录" else "还没有投递记录",
            hint = if (filtered) {
                "「全部」档包含已发出、没发出与已跳过三类记录"
            } else {
                // 不能说"只有真的发起过发送才会记一条"：三态之后，被闸门拦下的也会记一条。
                "渠道配置完整后，每次触发都会记一条，含未实际发出的那些"
            }
        )
    }
}

/**
 * 页面标题（也是清空弹窗里指代"清哪一份"的那句话）。
 *
 * 整句存在这一张表里而不是"渠道名 + 后缀"拼出来：Webhook 那一档按中文排版惯例要在拉丁词后
 * 留一个空格，拼接方案得为这一个特例写判断。标题与弹窗共用同一个取值，
 * 两处各写一遍会分叉成「标题说邮件、弹窗说全部」这种自相矛盾的一屏。
 *
 * 认不出的渠道退化成"投递记录"，不显示 id。
 */
private val DELIVERY_HISTORY_TITLES = mapOf(
    DELIVERY_CHANNEL_MAIL to "邮件投递记录",
    DELIVERY_CHANNEL_WEBHOOK to "Webhook 投递记录",
    DELIVERY_CHANNEL_LOCAL_SMS to "本机短信投递记录"
)

private fun deliveryHistoryTitle(channel: String): String =
    DELIVERY_HISTORY_TITLES[channel] ?: "投递记录"

/**
 * 行图标与空态图标按渠道选。
 *
 * Webhook 用 [Icons.Default.Http]，与「推送渠道」总览页那一行同一个图标 ——
 * 同一条渠道在两处长得不一样会让人以为是两个功能。
 */
private fun deliveryChannelIcon(channel: String): ImageVector = when (channel) {
    DELIVERY_CHANNEL_WEBHOOK -> Icons.Default.Http
    DELIVERY_CHANNEL_LOCAL_SMS -> Icons.Default.Sms
    else -> Icons.Default.MailOutline
}

/**
 * 一行的说明：时间 · 场景 → 目标 → 结果。
 *
 * 第三段的三态措辞刻意分开："发送失败"是投递出了故障，"未发送"是根本没发起 ——
 * 两句话对应两种处置（一个去排链路，一个去改设置）。
 * 原因文案取 core 的 `error` 原样显示，app 侧不译第二遍。
 */
private fun deliveryDescription(record: MailSendRecord): String {
    val head = "${FormatUtils.formatTimestamp(record.sentAt)} · ${sceneLabel(record.scene)}"
    val to = record.recipient.takeIf { it.isNotBlank() }?.let { "发往 $it" }
    val reason = record.error.ifBlank { "原因未记录" }
    val tail = when (record.deliveryOutcome) {
        DeliveryOutcome.SENT -> "已发出"
        DeliveryOutcome.SKIPPED -> "未发送：$reason"
        DeliveryOutcome.FAILED -> "发送失败：$reason"
    }
    return listOfNotNull(head, to, tail).joinToString("\n")
}

/**
 * 筛选参数的三态取值（接口的 `result`）。
 *
 * 与记录行的 `outcome` **不是同一套**：已发出那一档在 `result` 里叫 `success`、在 `outcome`
 * 里叫 `sent`。行的分类不在这里做，走 [com.ufi_axis.data.model.deliveryOutcome]（全 app 唯一一处）。
 */
private const val RESULT_SENT = "success"
private const val RESULT_FAILED = "failed"
private const val RESULT_SKIPPED = "skipped"
