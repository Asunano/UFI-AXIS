package com.ufi_axis.ui.screens

import com.ufi_axis.data.model.SmsRuleMatch
import com.ufi_axis.data.model.SmsRuleScope

// ════════════════════════════════════════════════════
// 拦截规则的作用域 / 匹配方式中文化 —— 规则页与记录页共用
// ════════════════════════════════════════════════════
//
// 2026-09-08：原来这四项住在 `SmsFilterScreen.kt` 里（那时规则与记录是同一页的两个 Tab）。
// 拆成 [SmsFilterRulesScreen] 与 [SmsBlockedScreen] 两个独立页面后它们仍是两边共用的：
// 规则行的说明文案要它，拦截记录的「命中规则：xx（号码 · 包含）」也要它。
// 单独提一个文件而不是复制两份 —— 文案漂移是"同一个 scope 在两页显示成不同中文"的直接来源。
//
// 可见性用 `internal`：Kotlin 的 `private` 是文件级，同包不同文件调不到。

/** 规则作用域的中文化 + 选项表（新增/编辑弹窗的 chip 与列表行的说明共用同一份）。 */
internal val SCOPE_OPTIONS = listOf(
    SmsRuleScope.SENDER to "号码",
    SmsRuleScope.BODY to "正文",
    SmsRuleScope.BOTH to "号码或正文"
)

/** 匹配方式的中文化 + 选项表。**没有正则** —— core 只认这四种。 */
internal val MATCH_OPTIONS = listOf(
    SmsRuleMatch.CONTAINS to "包含",
    SmsRuleMatch.EQUALS to "等于",
    SmsRuleMatch.PREFIX to "开头是",
    SmsRuleMatch.SUFFIX to "结尾是"
)

internal fun scopeLabel(scope: String): String =
    SCOPE_OPTIONS.firstOrNull { it.first == scope }?.second ?: scope

internal fun matchLabel(matchType: String): String =
    MATCH_OPTIONS.firstOrNull { it.first == matchType }?.second ?: matchType
