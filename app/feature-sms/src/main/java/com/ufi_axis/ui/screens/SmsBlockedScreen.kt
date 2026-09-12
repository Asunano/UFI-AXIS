package com.ufi_axis.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.RemoveCircleOutline
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import com.ufi_axis.data.model.SmsBlockedLog
import com.ufi_axis.ui.components.common.*
import com.ufi_axis.ui.theme.LocalResolvedPalette
import com.ufi_axis.ui.theme.Spacing
import com.ufi_axis.util.FormatUtils
import com.ufi_axis.viewmodel.MainViewModel
import kotlinx.coroutines.launch

// ════════════════════════════════════════════════════
// 已拦截 — core 记下的拦截结果，只读 + 自查动作
// ════════════════════════════════════════════════════
//
// 拦截是「静默丢消息」的功能，本页存在的唯一理由是让用户能回答"我是不是漏了什么"。
// 判定全在 core，本页一行判定逻辑都没有。
//
// 2026-09-08 从 `SmsFilterScreen.kt` 的第二个 Tab 拆出（拆分理由见 SmsFilterRulesScreen 顶部）。

/** 触底预加载提前量：倒数第 3 条进入视口就去拉下一页，避免滚到底才等一个来回。 */
private const val BLOCKED_LOAD_MORE_AHEAD = 3

/** 拦截记录说明的最大行数（时间/路径、正文预览、命中规则各一行，命中规则可能折行 → 给 4）。 */
private const val BLOCKED_DESCRIPTION_MAX_LINES = 4

/**
 * `blocked_path` 中文化：core 给的是逗号拼接的 `mail` / `push` / `vc` 组合。
 *
 * 不认识的段直接丢掉而不是原样显示：core 以后新增路径名时，这里宁可少显示一项，
 * 也不要在界面上蹦出一个英文缩写让用户猜。
 */
private fun blockedPathLabel(path: String): String = path
    .split(',')
    .mapNotNull {
        when (it.trim()) {
            "mail" -> "邮件"
            "push" -> "推送"
            "vc" -> "验证码"
            else -> null
        }
    }
    .distinct()
    .joinToString("·")
    .ifEmpty { "未记录" }

/**
 * 拦截记录行的说明文案。
 *
 * 命中规则一律读**快照** [SmsBlockedLog.rulePattern]，不拿 [SmsBlockedLog.ruleId] 回查规则表 ——
 * 规则删了记录还得读得懂「当时是被哪条规则拦的」，这份冗余是 core 刻意存的。
 *
 * @param ruleAlive 命中的规则是否还在。为 false 时把原因写进文案，
 *        因为行尾那个「不再按此规则拦截」按钮此时是灰的，光变灰不解释等于让人对着一个坏按钮发呆。
 */
private fun blockedDescription(log: SmsBlockedLog, ruleAlive: Boolean): String = buildString {
    append(FormatUtils.formatRelativeTime(log.blockedAt))
    append(" · ")
    append(blockedPathLabel(log.blockedPath))
    appendLine()
    append(log.snippet.ifBlank { "（无正文预览）" })
    appendLine()
    append("命中规则：")
    append(log.rulePattern.ifBlank { "—" })
    append("（")
    append(scopeLabel(log.ruleScope))
    append(" · ")
    append(matchLabel(log.ruleMatch))
    append("）")
    if (!ruleAlive) append(" · 规则已删除")
}

/**
 * 已拦截页（2026-09-08）。
 *
 * 壳必须用 [UfiPageBackgroundBox]（Box 版）：列表是 `LazyColumn`，
 * 而 [UfiPageBackground] 自带 `verticalScroll`，把 LazyColumn 套进去会收到 infinity
 * 最大高度直接抛异常。
 *
 * 未查看角标不在本页 —— Tab 栏拆掉之后它挂在短信设置页那张「已拦截」入口卡上
 * （`UfiEntryCard.badgeText`）。本页只负责把水位推上去（[MainViewModel] 的 markSmsBlockedSeen）。
 */
@Composable
fun SmsBlockedScreen(viewModel: MainViewModel, navController: NavHostController) {
    val palette = LocalResolvedPalette.current
    val toolsState by viewModel.toolsState.collectAsState()
    // 写入是 suspend（要拿到成功/失败才能提示），用页面级协程跑，不进 ViewModel 状态。
    val actionScope = rememberCoroutineScope()

    // 列表滚动位置：拆页之后天然就是屏幕级持有。
    val listState = rememberLazyListState()

    var pendingDeleteLog by remember { mutableStateOf<SmsBlockedLog?>(null) }
    var pendingDisableLog by remember { mutableStateOf<SmsBlockedLog?>(null) }
    var showClearConfirm by remember { mutableStateOf(false) }
    var toastMessage by remember { mutableStateOf<ToastMessage?>(null) }

    // 首屏骨架判据：ViewModel 给的「已完成过一次加载尝试」单调信号，成功/失败两条路都置位。
    // **不要**改成嗅 loading 的边沿（教训见 rememberFirstLoadPending 的注释）。
    val firstLoadPending = rememberFirstLoadPending { toolsState.smsBlockedLoaded }

    LaunchedEffect(Unit) {
        viewModel.tools.loadSmsBlocked()
        // 规则列表也要：行尾「不再按此规则拦截」要判断命中的规则是否还在（aliveRuleIds）。
        // 走静默口径 —— 它是本页的辅助数据，读不到只该让那个按钮变灰，不该弹错误。
        viewModel.tools.loadSmsRules(silent = true)
    }

    // 进页面就把「已拦截」角标的水位推上去（新数据到达时再推一次）。
    LaunchedEffect(toolsState.smsBlocked) {
        viewModel.tools.markSmsBlockedSeen()
    }

    // 触底加载下一页（keyset 游标）。
    // 选「滚到底加载更多」而不是 UfiPagination 的页码器：core 的分页是游标式的，
    // 拿不到「第 N 页」的起点，页码器点 3 还得先翻 1、2；而告警列表（同款 keyset 端点）
    // 的既有交互也是加载更多，两处保持一致。
    // 并发闸门在 ViewModel 里（hasMore / loadingMore / 游标非空三重判断），这里只管发信号。
    LaunchedEffect(listState, toolsState.smsBlocked.size) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
            .collect { lastVisible ->
                if (lastVisible >= 0 &&
                    lastVisible >= toolsState.smsBlocked.size - BLOCKED_LOAD_MORE_AHEAD
                ) {
                    viewModel.tools.loadMoreSmsBlocked()
                }
            }
    }

    UfiScreenScaffold(
        title = "已拦截",
        navController = navController,
        showBack = true,
        actions = {
            // 没有记录时**不渲染**这颗按钮，而不是 `enabled = false`：
            // 图标的 tint 是显式给的 palette.textSecondary，M3 的 disabled 着色被覆盖掉了 ——
            // 于是空列表时按钮看起来和可点时一模一样，按下去毫无反应（用户报的就是这个）。
            // 全站同类动作（TaskScreen 的清除全部、TunnelScreen 的停止全部）也是条件渲染。
            if (toolsState.smsBlocked.isNotEmpty()) {
                IconButton(onClick = { showClearConfirm = true }) {
                    Icon(
                        Icons.Default.DeleteSweep,
                        contentDescription = "清空拦截记录",
                        tint = palette.textSecondary
                    )
                }
            }
        }
    ) { padding ->
        UfiPageBackgroundBox(modifier = Modifier.fillMaxSize().padding(padding)) {
            // 当前还存在的规则 id 集合。用集合而不是每行遍历一次规则列表：
            // 规则和记录都可能上百条，逐行 `any {}` 是 O(n·m)。
            val aliveRuleIds = toolsState.smsRules.mapTo(HashSet()) { it.id }
            when {
                toolsState.smsBlocked.isNotEmpty() -> LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = Spacing.Large),
                    verticalArrangement = Arrangement.spacedBy(Spacing.Medium)
                ) {
                    items(toolsState.smsBlocked, key = { it.id }) { log ->
                        val ruleAlive = log.ruleId in aliveRuleIds
                        UfiSettingsRowCard {
                            UfiSettingsItem(
                                title = log.sender.ifBlank { "未知号码" },
                                description = blockedDescription(log, ruleAlive),
                                icon = Icons.Default.Block,
                                // 说明是三行（时间/路径、正文预览、命中规则），显式给上限：
                                // 默认不限行数，遇到长 snippet 会把一行卡片撑成半屏。
                                descriptionMaxLines = BLOCKED_DESCRIPTION_MAX_LINES,
                                trailing = {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(Spacing.Medium)
                                    ) {
                                        // 规则已删时置灰并不可点：此时没有规则可停用，
                                        // 原因已经写进 description（「规则已删除」）。
                                        IconButton(
                                            onClick = { pendingDisableLog = log },
                                            enabled = ruleAlive
                                        ) {
                                            Icon(
                                                Icons.Default.RemoveCircleOutline,
                                                contentDescription = "不再按此规则拦截",
                                                tint = if (ruleAlive) palette.warning else palette.textSecondary,
                                                modifier = Modifier.size(Spacing.IconSizeSmall)
                                            )
                                        }
                                        IconButton(onClick = { pendingDeleteLog = log }) {
                                            Icon(
                                                Icons.Default.DeleteOutline,
                                                contentDescription = "删除这条记录",
                                                tint = palette.error,
                                                modifier = Modifier.size(Spacing.IconSizeSmall)
                                            )
                                        }
                                    }
                                }
                            )
                        }
                    }
                    if (toolsState.smsBlockedHasMore) {
                        // 底部加载指示：这是**真 IO 分页**（keyset 游标要等一个来回），
                        // 与会话列表那种纯本地渐进渲染不同，不给反馈用户会以为列表到底了。
                        item(key = "blocked-load-more") {
                            Box(
                                modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.Large),
                                contentAlignment = Alignment.Center
                            ) {
                                UfiLoadingIndicator(modifier = Modifier.size(Spacing.IconSizeLarge))
                            }
                        }
                    }
                }
                firstLoadPending -> UfiSkeletonList(
                    modifier = Modifier.padding(
                        horizontal = Spacing.CardHorizontalMargin,
                        vertical = Spacing.Large
                    )
                )
                else -> UfiEmptyState(
                    icon = Icons.Default.Block,
                    message = "还没有被拦下的短信",
                    hint = "规则命中后这里会记下发件人与正文预览"
                )
            }
        }

        // ── 「不再按此规则拦截」= 把命中的那条规则 enabled 置 false ──
        // 刻意**不做**「恢复这条短信」：邮件已经没发、推送已经没推，事后补不回来，
        // 提供一个做不到的动作比不提供更糟。
        pendingDisableLog?.let { log ->
            UfiConfirmDialog(
                visible = true,
                title = "不再按此规则拦截",
                text = "将停用规则「${log.rulePattern}」，之后符合它的短信恢复正常提醒与转发。" +
                    "这条已被拦下的短信无法恢复 —— 邮件没有发出、通知没有推送，事后补不回来。",
                confirmText = "停用规则",
                onDismiss = { pendingDisableLog = null },
                onConfirm = {
                    pendingDisableLog = null
                    actionScope.launch {
                        val ok = viewModel.tools.setSmsRuleEnabled(log.ruleId, false)
                        toastMessage = if (ok) ToastMessage("规则已停用", ToastType.SUCCESS)
                        else ToastMessage("停用失败，已还原", ToastType.ERROR)
                    }
                }
            )
        }

        // ── 删除单条记录 ──
        pendingDeleteLog?.let { log ->
            UfiConfirmDialog(
                visible = true,
                title = "删除这条记录",
                text = "只删除这条自查记录，不影响规则本身。",
                confirmText = "删除",
                destructive = true,
                onDismiss = { pendingDeleteLog = null },
                onConfirm = {
                    pendingDeleteLog = null
                    actionScope.launch {
                        if (!viewModel.tools.deleteSmsBlocked(log.id)) {
                            toastMessage = ToastMessage("删除失败，已还原", ToastType.ERROR)
                        }
                    }
                }
            )
        }

        // ── 清空记录 ──
        if (showClearConfirm) {
            UfiConfirmDialog(
                visible = true,
                title = "清空拦截记录",
                text = "清空后无法再回查「哪些短信被拦下过」，规则与命中次数不受影响。",
                confirmText = "清空",
                destructive = true,
                onDismiss = { showClearConfirm = false },
                onConfirm = {
                    showClearConfirm = false
                    actionScope.launch {
                        val ok = viewModel.tools.clearSmsBlocked()
                        toastMessage = if (ok) ToastMessage("已清空拦截记录", ToastType.SUCCESS)
                        else ToastMessage("清空失败，请重试", ToastType.ERROR)
                    }
                }
            )
        }

        UfiToastHost(toastMessage = toastMessage, onDismiss = { toastMessage = null })
    }
}
