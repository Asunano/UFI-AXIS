package com.ufi_axis.ui.screens

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.ufi_axis.data.model.NotifyLevelOptionDto
import com.ufi_axis.ui.components.common.UfiButton
import com.ufi_axis.ui.components.common.UfiButtonVariant
import com.ufi_axis.ui.components.common.UfiCustomDialog
import com.ufi_axis.ui.components.common.UfiDialogBody
import com.ufi_axis.ui.components.common.UfiDialogField
import com.ufi_axis.ui.components.common.UfiDialogNote
import com.ufi_axis.ui.components.common.UfiDigitField
import com.ufi_axis.ui.components.common.UfiMultiChipSelector
import com.ufi_axis.ui.components.common.UfiPopupAnchor
import com.ufi_axis.ui.components.common.UfiPopupOption
import com.ufi_axis.ui.components.common.UfiSettingsRowCard
import com.ufi_axis.ui.components.common.UfiSettingsValue
import com.ufi_axis.ui.theme.LocalResolvedPalette

// ════════════════════════════════════════════════════
// 三条渠道共用的「投递规则」旋钮（2026-09-10）
// ════════════════════════════════════════════════════
//
// core 侧把三条渠道（邮件 / Webhook / 本机短信）的规则做成了同构的：每条都有
// **最低级别** + **每日上限**，config 响应也回同一组字段
// （min_level / daily_limit / sent_today / quota_remaining / levels / daily_limit_min|max）。
//
// 界面因此必须长成同一种。旋钮各写一份的下场已经见过一次：本机短信页先落地了这两项，
// 措辞（"每日条数上限"）与另两页将来各自发明的说法必然不一样，用户在三页之间来回时
// 得重新认一遍同一个设置。所以行、弹窗、文案全部收在本文件，三页只传自己的值。
//
// **刻意保留的差异只有一处**：[min]（= core 的 `daily_limit_min`）。邮件 / Webhook 是 0
// （0 = 不限），本机短信是 1 —— 那条渠道按条计费，"不限"不是合法诉求。这一点在
// [NotifyDailyLimitRow] 的副文案与 [NotifyDailyLimitDialog] 的说明里都要说出来，
// 不能让用户先填一个 0 再去撞 core 的 400。
//
// ## 量词为什么三条渠道都是「条」（2026-09-10 裁决）
//
// 邮件一度按"封"计数。取消了：core 的报错文案已经统一成「每日上限（daily_limit）」不带量词，
// web 那侧统一用"条"，app 再给邮件单独换一个量词，同一个旋钮就会在
// app / web / core 报错三处出现三种叫法。量词因此收成本文件的 [NOTIFY_LIMIT_UNIT] 一份，
// **不做成参数** —— 只剩一个取值的参数只是让调用方多传一次同样的字符串。
//
// 值域（levels / daily_limit_min|max）一律从 GET 响应传进来，本文件不写第二份 ——
// 抄一份的表现是"界面允许、设备拒收"。

/**
 * 级别的**线上口径小写名**（core `NotifyLevel.wireName`）。
 *
 * 只用于"当前选中的是哪一档"与本地代价提示的分档，**可选值与中文名的真源都是响应里的
 * `levels`**。提交时也必须是这三个小写名之一，传别的 core 回 400。
 */
internal const val NOTIFY_LEVEL_INFO = "info"
internal const val NOTIFY_LEVEL_WARNING = "warning"
internal const val NOTIFY_LEVEL_CRITICAL = "critical"

/** `daily_limit = 0` / `quota_remaining = null` 的用户可见说法。三页逐字一致。 */
internal const val NOTIFY_LIMIT_UNLIMITED = "不限"

/**
 * 每日上限 / 今日用量的计数量词。
 *
 * **三条渠道共用一个**（含邮件）。不做成参数、也不按渠道分支：core 的报错文案不带量词、
 * web 那侧统一"条"，app 再给某条渠道换一个说法，同一个旋钮就会在三处出现三种叫法。
 */
private const val NOTIFY_LIMIT_UNIT = "条"

/**
 * 设备端没给级别取值域时的说明（老固件的 config 不回 `levels`）。
 *
 * 为什么必须有这一句：app 侧刻意**不留兜底级别名表** —— 手抄的那份在 core 加一档或改措辞时
 * 不会报错，只会静默显示错的名字（这次就是这么分叉的：app 写「一般（info）」而 web 写
 * 「一般及以上（全部通知）」）。代价是那种版本的设备上这一行选不动，而「看得见旋钮却选不了、
 * 又没有任何说明」比干脆没有这个旋钮更糟。文案与 web 的 `LEVELS_UNAVAILABLE_NOTE` 逐字一致。
 *
 * 只影响级别这一项：每日上限的取值域有本地兜底，老固件上照样能改。
 */
internal const val NOTIFY_LEVELS_UNAVAILABLE_NOTE =
    "设备端服务版本较旧，未提供可选级别，该项暂不可用；升级设备端服务后可设置。"

/**
 * 级别的显示名：**在下发的取值域里按 wire name 查 core 给的 `label`**。
 *
 * app 侧没有级别名映射表 —— core 的 `NotifyLevel.label` 是唯一真源，它的注释也明确要求
 * 客户端别各维护一张。查不到（老固件不回 `levels`、或 core 加了一档 app 还没见过）就
 * **原样显示 wire name**：至少还能对着代码查，显示"未知"就断了线索。
 */
internal fun notifyLevelLabel(levels: List<NotifyLevelOptionDto>, wire: String): String =
    levels.firstOrNull { it.name == wire }?.label?.takeIf { it.isNotBlank() } ?: wire

/**
 * 选中级别之后"会收到什么"的一句话 —— **本地的代价提示，不是级别名**。
 *
 * 这部分刻意留在 app：core 的 `label` 只回答"这一档叫什么"，而"选它之后条数会变多/变少、
 * 要不要多花钱"是界面该替用户算的那笔账，不是 core 的职责。措辞不复述级别名
 * （那就又变成第二张级别表了），只说这一档收得多还是收得少。
 *
 * @param metered 这条渠道每次投递是否要付钱（只有本机短信是）。为 true 时在同一句话后面
 *   接一句代价提示 —— 句子结构与另两条渠道保持一致，只是多了这一节，
 *   而不是给花钱的渠道另写一套说法。
 */
internal fun notifyLevelHint(wire: String, metered: Boolean): String {
    val base = when (wire) {
        NOTIFY_LEVEL_INFO ->
            "一般通知也会发送：新短信、验证码、下载完成等，条数最多"
        NOTIFY_LEVEL_WARNING ->
            "只发警告与严重：断连、流量预警会到，日常波动不会到"
        NOTIFY_LEVEL_CRITICAL ->
            "只发最严重的几类：断网、套餐用尽、自动关网"
        else -> "设备当前使用的级别"
    }
    if (!metered) return base
    val cost = when (wire) {
        NOTIFY_LEVEL_INFO -> "这一档费用最高"
        NOTIFY_LEVEL_WARNING -> "费用中等"
        NOTIFY_LEVEL_CRITICAL -> "这一档费用最低"
        else -> "按条计费"
    }
    return "$base · $cost"
}

/** 每日上限的右侧取值：0 一律显示成 [NOTIFY_LIMIT_UNLIMITED]，不能显示成"0 条"。 */
internal fun notifyDailyLimitValue(dailyLimit: Int): String =
    if (dailyLimit <= 0) NOTIFY_LIMIT_UNLIMITED else "$dailyLimit $NOTIFY_LIMIT_UNIT"

/**
 * 今日用量的右侧取值（`已发 / 上限`）。
 *
 * 上限为 0 时分母写 [NOTIFY_LIMIT_UNLIMITED] —— 写成 `2/0` 会被读成"超额了"。
 */
internal fun notifyQuotaValue(sentToday: Int, dailyLimit: Int): String =
    "$sentToday/${if (dailyLimit <= 0) NOTIFY_LIMIT_UNLIMITED else dailyLimit.toString()}"

/**
 * 「严重事件兜底」在免打扰 / 场景勾选旁边的一句提示。
 *
 * 为什么必须有：兜底默认开着，于是"设了免打扰却半夜被叫醒""没勾这个场景却收到了"
 * 都是**正常行为**。不写出来，用户只会判定免打扰失灵。
 *
 * @param enabled 本机镜像里的 `critical_override_enabled`。关着的时候要说关着 ——
 *   照抄"严重事件会穿透"就是在承诺一件当前不会发生的事。
 */
internal fun notifyCriticalOverrideNote(enabled: Boolean): String = if (enabled) {
    "「严重」级别的事件会穿透免打扰时段、未勾选的场景与最低级别门槛；" +
        "可在「通知与守护 → 严重事件兜底」中关闭"
} else {
    "「严重事件兜底」当前已关闭：严重事件同样受免打扰时段、场景勾选与最低级别约束"
}

/**
 * 「最低级别」行（三条渠道同一个组件）。
 *
 * 选项与显示名全部来自 [levels]（core 的 `levels`，对象数组）。它为空时（老固件的 config
 * 不回这张表）这一行**退化成只读**：只列当前值，并在副文案里说明原因
 * （[NOTIFY_LEVELS_UNAVAILABLE_NOTE]）—— [UfiPopupAnchor] 在选项为空时整块不渲染，
 * 直接传空表会让这一行凭空消失。
 *
 * @param configLoaded 配置回读是否已完成。用来把"还在读"与"读完了但设备端没给取值域"
 *   分开：前者只是暂时没有选项，后者才该亮出那句版本说明。
 * @param onSelect 只在真的换了一档时才回调，避免"点了当前项"也打一次 PUT。
 */
@Composable
internal fun NotifyMinLevelRow(
    levels: List<NotifyLevelOptionDto>,
    current: String,
    metered: Boolean,
    onSelect: (String) -> Unit,
    configLoaded: Boolean = true
) {
    UfiSettingsRowCard {
        val levelsUnavailable = configLoaded && levels.isEmpty()
        val options = levels.ifEmpty { listOf(NotifyLevelOptionDto(name = current)) }
        UfiPopupAnchor(
            options = options.map { option ->
                UfiPopupOption(
                    id = option.name,
                    label = option.label.takeIf { it.isNotBlank() } ?: option.name,
                    isSelected = option.name == current,
                    onClick = { if (option.name != current) onSelect(option.name) }
                )
            }
        ) { toggle ->
            UfiSettingsValue(
                title = "最低级别",
                description = if (levelsUnavailable) {
                    NOTIFY_LEVELS_UNAVAILABLE_NOTE
                } else {
                    notifyLevelHint(current, metered)
                },
                value = notifyLevelLabel(levels, current),
                onClick = toggle
            )
        }
    }
}

/**
 * 「每日上限」行（三条渠道同一个组件）。
 *
 * @param min core 的 `daily_limit_min`。为 0 表示这条渠道允许"不限"；
 *   大于 0 时副文案必须写明**不支持不限**，否则用户会先填 0 再撞一个 400。
 */
@Composable
internal fun NotifyDailyLimitRow(
    dailyLimit: Int,
    min: Int,
    max: Int,
    onClick: () -> Unit
) {
    UfiSettingsRowCard {
        UfiSettingsValue(
            title = "每日上限",
            description = "按设备本地日期跨天重置，不是 24 小时滑动窗口。" +
                notifyDailyLimitRangeNote(min, max),
            value = notifyDailyLimitValue(dailyLimit),
            onClick = onClick
        )
    }
}

/**
 * 「今日用量」行（三条渠道同一个组件，只读）。
 *
 * @param quotaRemaining core 的 `quota_remaining`；`daily_limit = 0` 时它是 **null**，
 *   此处必须走"不限"那一支。当成 0 处理就会把"没设上限"说成"配额用尽了"。
 */
@Composable
internal fun NotifyQuotaRow(
    sentToday: Int,
    dailyLimit: Int,
    quotaRemaining: Int?
) {
    UfiSettingsRowCard {
        UfiSettingsValue(
            title = "今日用量",
            description = when {
                dailyLimit <= 0 || quotaRemaining == null ->
                    "未设每日上限，达不到配额限制"
                quotaRemaining <= 0 ->
                    "配额已用尽，今天不再发送；未发出的通知会记入投递记录"
                else -> "今天还能发 $quotaRemaining $NOTIFY_LIMIT_UNIT"
            },
            value = notifyQuotaValue(sentToday, dailyLimit)
        )
    }
}

/**
 * 「每日上限」弹窗（暂存-确认，三条渠道同一个组件）。
 *
 * 区间在这里也校验一遍。**core 仍是唯一强制点**（web 与 curl 也能写），
 * 这里只影响"保存按钮灰不灰" —— 等 PUT 回来才报错等于让用户白填一次。
 */
@Composable
internal fun NotifyDailyLimitDialog(
    current: Int,
    min: Int,
    max: Int,
    saving: Boolean,
    onDismiss: () -> Unit,
    onSave: (Int) -> Unit
) {
    val palette = LocalResolvedPalette.current
    var text by remember { mutableStateOf(current.toString()) }

    val value = text.toIntOrNull()
    val error = if (value == null || value !in min..max) {
        if (min <= 0) "请填 $min–$max（0 = $NOTIFY_LIMIT_UNLIMITED）" else "请填 $min–$max 之间的数"
    } else null

    UfiCustomDialog(
        visible = true,
        onDismiss = onDismiss,
        title = "每日上限",
        confirmButton = {
            UfiButton(
                text = "保存",
                enabled = error == null && !saving,
                onClick = { if (value != null) onSave(value) }
            )
        },
        dismissButton = {
            UfiButton(variant = UfiButtonVariant.Secondary, text = "取消", onClick = onDismiss)
        }
    ) {
        UfiDialogBody {
            UfiDialogField(label = "上限（$NOTIFY_LIMIT_UNIT / 天）") {
                UfiDigitField(
                    value = text,
                    onValueChange = { text = it },
                    label = "",
                    isError = error != null,
                    errorMessage = error
                )
            }
            Text(
                text = notifyDailyLimitRangeNote(min, max) +
                    "达到上限后当天不再发送，被跳过的通知会记入投递记录。",
                style = MaterialTheme.typography.bodySmall,
                color = palette.textSecondary
            )
        }
    }
}

/**
 * 「触发场景」勾选弹窗（暂存-确认，三条渠道同一个组件）。
 *
 * 2026-09-11 从三个渠道页各自的私有实现合并而来：那三份逐行同构（同一套 chip、同一份
 * [NOTIFY_SCENE_LABELS]、同两句共用说明、同样的暂存-确认），**只有第一句说明不同**。
 * 各留一份的下场与级别/上限那三行一样 —— 加一句共用说明只会改到一处，
 * 而另两页在界面上看不出漏了什么。
 *
 * 渠道差异只剩两个入参：
 * - [title]：邮件那页叫「邮件转发范围」（它勾的是"哪些内容要发一封邮件"），
 *   另两条叫「触发场景」。沿用各页原有的叫法，不趁这次统一 —— 那是文案决策，不是重复代码。
 * - [leadNote]：第一句说明。邮件说"短信正文与验证码是两个独立开关"，
 *   本机短信要先说清"每一条都花钱"，Webhook 说"未勾选时只有手动测试会发"。
 *
 * @param criticalOverrideOn 「严重事件兜底」当前状态。必须说一句：兜底开着时 critical 事件
 *   **不看这些勾**也会发，否则"没勾这个场景怎么还发了"会被当成 bug。
 */
@Composable
internal fun NotifyScenesDialog(
    title: String,
    leadNote: String,
    selected: List<String>,
    criticalOverrideOn: Boolean,
    onDismiss: () -> Unit,
    onSave: (List<String>) -> Unit
) {
    val draft = remember { mutableStateListOf<String>().apply { addAll(selected) } }

    UfiCustomDialog(
        visible = true,
        onDismiss = onDismiss,
        title = title,
        confirmButton = { UfiButton(text = "保存", onClick = { onSave(draft.toList()) }) },
        dismissButton = {
            UfiButton(variant = UfiButtonVariant.Secondary, text = "取消", onClick = onDismiss)
        }
    ) {
        UfiDialogBody {
            UfiDialogNote(leadNote)
            UfiDialogNote(notifyCriticalOverrideNote(criticalOverrideOn))
            UfiDialogNote(NOTIFY_SCENE_BATTERY_NOTE)

            UfiMultiChipSelector(
                options = NOTIFY_SCENE_LABELS,
                selectedValues = draft.toSet(),
                onToggle = { id -> if (draft.contains(id)) draft.remove(id) else draft.add(id) }
            )
        }
    }
}

/**
 * 区间说明。行的副文案与弹窗里用**同一句**：两处写法不一致时，
 * 用户会以为自己看到的是两个不同的规则。
 */
private fun notifyDailyLimitRangeNote(min: Int, max: Int): String = if (min <= 0) {
    "可填 $min–$max $NOTIFY_LIMIT_UNIT，填 0 表示$NOTIFY_LIMIT_UNLIMITED。"
} else {
    // 花钱的渠道刻意不给"不限"。把最小值写出来，别让用户去撞校验错误。
    "可填 $min–$max $NOTIFY_LIMIT_UNIT，本渠道最少 $min $NOTIFY_LIMIT_UNIT、" +
        "不支持$NOTIFY_LIMIT_UNLIMITED。"
}
