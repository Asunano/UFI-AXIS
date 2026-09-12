package com.ufi_axis.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.FilterAlt
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.navigation.NavHostController
import com.ufi_axis.data.model.SmsRule
import com.ufi_axis.data.model.SmsRuleMatch
import com.ufi_axis.data.model.SmsRuleScope
import com.ufi_axis.ui.components.common.*
import com.ufi_axis.ui.theme.LocalResolvedPalette
import com.ufi_axis.ui.theme.Spacing
import com.ufi_axis.viewmodel.MainViewModel
import kotlinx.coroutines.launch

// ════════════════════════════════════════════════════
// 拦截规则页 — 号码黑名单与关键词的增删改查
// ════════════════════════════════════════════════════
//
// app **不做任何拦截判定**：core 命中就不发邮件、不推通知、不入验证码库、不进列表与计数，
// app 天然收不到那条短信。本页只编辑规则。
//
// 2026-09-08 从 `SmsFilterScreen.kt`（一页两 Tab：拦截规则 / 拦截记录）拆出。
// 拆的理由是那两块内容彼此独立 —— 顶栏动作要按 Tab 分派（+新增 / 清空）、
// 角标只属于记录那一半、两个列表各要一份骨架闩锁与滚动位置：
// 同一个页面里维护两套互不相干的状态，读代码和用界面都要先问"我现在在哪一半"。
// 拆完两页各自是普通的「一个列表 + 一个顶栏动作」。

/**
 * 规则行的说明文案。
 *
 * **命中次数放在这里而不是 trailing 徽标**：trailing 已经排了「开关 + 删除」两个控件，
 * 再塞一个徽标会把 `pattern` 标题挤到两行（号码和关键词本身可以很长）。
 * 而且命中次数与作用域/匹配方式同属「这条规则是什么、管过几次用」的说明性信息，
 * 放同一行读起来是一句话，做成徽标反而像状态告警。
 */
private fun ruleDescription(rule: SmsRule): String = buildString {
    append(scopeLabel(rule.scope))
    append(" · ")
    append(matchLabel(rule.matchType))
    if (rule.hitCount > 0) {
        append(" · 命中 ")
        append(rule.hitCount)
        append(" 次")
    }
    val note = rule.note.trim()
    if (note.isNotEmpty()) {
        append(" · ")
        append(note)
    }
}

/** 新增 / 编辑规则弹窗的草稿。id <= 0 = 新增。 */
private data class SmsRuleDraft(
    val id: Long,
    val pattern: String,
    val scope: String,
    val matchType: String,
    val note: String
)

private fun SmsRule.toDraft() = SmsRuleDraft(
    id = id,
    pattern = pattern,
    scope = scope,
    matchType = matchType,
    note = note
)

/**
 * 拦截规则页（2026-09-08）。
 *
 * 壳必须用 [UfiPageBackgroundBox]（Box 版）：列表是 `LazyColumn`，
 * 而 [UfiPageBackground] 自带 `verticalScroll`，把 LazyColumn 套进去会收到 infinity
 * 最大高度直接抛异常。
 */
@Composable
fun SmsFilterRulesScreen(viewModel: MainViewModel, navController: NavHostController) {
    val palette = LocalResolvedPalette.current
    val toolsState by viewModel.toolsState.collectAsState()
    // 写入是 suspend（要拿到成功/失败才能提示），用页面级协程跑，不进 ViewModel 状态。
    val actionScope = rememberCoroutineScope()

    // 列表滚动位置：拆页之后天然就是屏幕级持有，页面在返回栈上时状态跟着 NavBackStackEntry 保留。
    val listState = rememberLazyListState()

    var draft by remember { mutableStateOf<SmsRuleDraft?>(null) }
    var draftError by remember { mutableStateOf<String?>(null) }
    var pendingDeleteRule by remember { mutableStateOf<SmsRule?>(null) }
    var toastMessage by remember { mutableStateOf<ToastMessage?>(null) }

    // 首屏骨架判据：ViewModel 给的「已完成过一次加载尝试」单调信号，成功/失败两条路都置位。
    // **不要**改成嗅 loading 的边沿 —— 2026-09-08 验证码页正是那么写的，
    // 边沿被 snapshot 合并掉之后骨架永久挂死、空态成了不可达代码。
    val firstLoadPending = rememberFirstLoadPending { toolsState.smsRulesLoaded }

    LaunchedEffect(Unit) {
        viewModel.tools.loadSmsRules()
    }

    UfiScreenScaffold(
        title = "拦截规则",
        navController = navController,
        showBack = true,
        actions = {
            IconButton(onClick = {
                // 新建默认 scope=body / match=contains：这是「关键词」的常用形态；
                // 号码黑名单主要从短信页会话行长按进来，那条路径已经把 scope 定死成 sender。
                draft = SmsRuleDraft(
                    id = 0L,
                    pattern = "",
                    scope = SmsRuleScope.BODY,
                    matchType = SmsRuleMatch.CONTAINS,
                    note = ""
                )
                draftError = null
            }) {
                Icon(Icons.Default.Add, contentDescription = "新增规则", tint = palette.accent)
            }
        }
    ) { padding ->
        UfiPageBackgroundBox(modifier = Modifier.fillMaxSize().padding(padding)) {
            when {
                toolsState.smsRules.isNotEmpty() -> LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    // 横向内距由 UfiSettingsRowCard 自带，这里只给上下呼吸空间，否则会缩两遍。
                    contentPadding = PaddingValues(vertical = Spacing.Large),
                    verticalArrangement = Arrangement.spacedBy(Spacing.Medium)
                ) {
                    items(toolsState.smsRules, key = { it.id }) { rule ->
                        // 行用 [UfiSettingsRowCard] + [UfiSettingsItem]（trailing 是自由槽）而
                        // **不是** [UfiEntryCard] —— 后者的 trailing 固定是「徽标 + 右箭头」，塞不进开关。
                        UfiSettingsRowCard {
                            UfiSettingsItem(
                                title = rule.pattern,
                                description = ruleDescription(rule),
                                // 号码黑名单用禁止符、关键词用漏斗：一眼区分两类规则，
                                // 因为 pattern 本身（"10086" / "中奖"）不总能看出是哪种。
                                icon = if (rule.scope == SmsRuleScope.SENDER) {
                                    Icons.Default.Block
                                } else {
                                    Icons.Default.FilterAlt
                                },
                                onClick = {
                                    draft = rule.toDraft()
                                    draftError = null
                                },
                                enabled = rule.enabled,
                                trailing = {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(Spacing.Medium)
                                    ) {
                                        // 行内开关永远放 Row 最右侧之前、删除之后 —— 全站惯例是
                                        // 「状态控件靠右、破坏性动作最右」，这里删除是最右一个。
                                        UfiSwitch(
                                            checked = rule.enabled,
                                            onCheckedChange = { enabled ->
                                                actionScope.launch {
                                                    if (!viewModel.tools.setSmsRuleEnabled(rule.id, enabled)) {
                                                        toastMessage =
                                                            ToastMessage("开关保存失败，已还原", ToastType.ERROR)
                                                    }
                                                }
                                            }
                                        )
                                        IconButton(onClick = { pendingDeleteRule = rule }) {
                                            Icon(
                                                Icons.Default.DeleteOutline,
                                                contentDescription = "删除规则",
                                                tint = palette.error,
                                                modifier = Modifier.size(Spacing.IconSizeSmall)
                                            )
                                        }
                                    }
                                }
                            )
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
                    icon = Icons.Default.FilterAlt,
                    message = "暂无拦截规则",
                    hint = "点击右上角 + 添加号码黑名单或正文关键词"
                )
            }
        }

        // ── 新增 / 编辑规则 ──
        draft?.let { current ->
            // 容器用 [UfiScrollableDialog] 而不是 [UfiCustomDialog]：
            // 「按钮固定在底部 + 内容超高才滚动」这套只有它有（`actions` 槽位 + footer 高度预算），
            // 而本表单有 4 个字段 + 2 组 chip，小屏上确实会超高。
            // 全站同形态的表单弹窗（DhcpSettingsDialog / WifiSettingsDialog / ServerConfigScreen）
            // 也都是 UfiScrollableDialog + actions。
            UfiScrollableDialog(
                visible = true,
                onDismiss = { draft = null; draftError = null },
                title = if (current.id > 0) "编辑规则" else "新增规则",
                icon = rememberVectorPainter(Icons.Default.FilterAlt),
                showCloseButton = false,
                // 按钮走 `actions` 槽位，**不能**塞进下面的 UfiDialogBody（FIX-24 的约定）：
                // 放进 content 会踩两个坑 ——
                // 1) 间距叠加：UfiDialogBody 的 `spacedBy(Large)` 12dp 会再加上 UfiDialogActions
                //    自带的 18dp 前置 Spacer，备注框与按钮之间凭空多出 12dp；
                // 2) 按钮会跟着内容一起滚：content 外面是 verticalScroll，
                //    字段一多"保存"就滚出可视区，而 actions 槽位是固定在底部的。
                // 顺带 footerFixed 的高度预算也只有在走槽位时才按 120dp 算（塞 content 时按 32dp）。
                actions = {
                    UfiDialogActions(
                        onDismiss = { draft = null; draftError = null },
                        onConfirm = {
                            val pattern = current.pattern.trim()
                            if (pattern.isEmpty()) {
                                // 空 pattern 必须在本地就拦下：`contains ""` 会命中**每一条**短信，
                                // 等于一键静默全部消息。core 侧还有同样的守卫，
                                // 这一道只是为了不让用户白发一次请求再看红字。
                                draftError = "匹配内容不能为空"
                            } else {
                                actionScope.launch {
                                    val ok = viewModel.tools.saveSmsRule(
                                        id = current.id,
                                        pattern = pattern,
                                        ruleScope = current.scope,
                                        matchType = current.matchType,
                                        note = current.note.trim()
                                    )
                                    if (ok) {
                                        draft = null
                                        draftError = null
                                        toastMessage = ToastMessage("规则已保存", ToastType.SUCCESS)
                                    } else {
                                        toastMessage = ToastMessage("规则保存失败，请重试", ToastType.ERROR)
                                    }
                                }
                            }
                        },
                        confirmText = "保存",
                        enabled = !toolsState.smsRulesBusy,
                        loading = toolsState.smsRulesBusy
                    )
                }
            ) {
                UfiDialogBody {
                    UfiDialogTextField(
                        label = "匹配内容",
                        value = current.pattern,
                        onValueChange = {
                            draft = current.copy(pattern = it)
                            // 一开始打字就把错误清掉：错误红字挂在用户正在修的字段上是噪音。
                            draftError = null
                        },
                        placeholder = "号码如 10086，关键词如 中奖",
                        isError = draftError != null,
                        errorMessage = draftError
                    )
                    UfiDialogChipSelector(
                        label = "匹配范围",
                        options = SCOPE_OPTIONS,
                        selectedValue = current.scope,
                        onSelect = { draft = current.copy(scope = it) }
                    )
                    UfiDialogChipSelector(
                        label = "匹配方式",
                        options = MATCH_OPTIONS,
                        selectedValue = current.matchType,
                        onSelect = { draft = current.copy(matchType = it) }
                    )
                    UfiDialogTextField(
                        label = "备注（可选）",
                        value = current.note,
                        onValueChange = { draft = current.copy(note = it) },
                        placeholder = "写给自己看，如「运营商推广」"
                    )
                }
            }
        }

        // ── 删除规则 ──
        pendingDeleteRule?.let { rule ->
            UfiConfirmDialog(
                visible = true,
                title = "删除规则",
                text = "删除「${rule.pattern}」后符合该规则的短信将恢复正常提醒与转发。已产生的拦截记录不受影响，仍可查看。",
                confirmText = "删除",
                destructive = true,
                onDismiss = { pendingDeleteRule = null },
                onConfirm = {
                    pendingDeleteRule = null
                    actionScope.launch {
                        if (!viewModel.tools.deleteSmsRule(rule.id)) {
                            toastMessage = ToastMessage("删除失败，已还原", ToastType.ERROR)
                        }
                    }
                }
            )
        }

        UfiToastHost(toastMessage = toastMessage, onDismiss = { toastMessage = null })
    }
}
