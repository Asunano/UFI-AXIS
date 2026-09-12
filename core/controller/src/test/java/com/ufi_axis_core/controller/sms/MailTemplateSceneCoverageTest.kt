package com.ufi_axis_core.controller.sms

import com.ufi_axis_core.notify.NotifyScenes
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 邮件模板的场景登记完整性：[NotifyScenes.ALL] 里每个场景都必须有主色与徽标文案。
 *
 * 漏登记不会报错也不会崩，只会让新场景的邮件落到默认蓝 + "通知"徽标 ——
 * 与短信邮件长得一模一样，用户在收件箱里没法一眼区分，而这在日志里查不出来。
 * 这类"静默降级"必须由测试拦住，靠人肉记得改两张表是不成立的。
 *
 * 与 `:core:common` 的 `NotifyScenesTest`（每个常量都在 `ALL` 里）配套：
 * 那个保证 `ALL` 是全集，这个保证全集里每一项都在模板里登记过。
 */
class MailTemplateSceneCoverageTest {

    @Test
    fun `每个场景都有主色`() {
        val missing = NotifyScenes.ALL - MailTemplate.registeredColorScenes
        assertTrue("这些场景没登记邮件主色，会落到默认蓝: $missing", missing.isEmpty())
    }

    @Test
    fun `每个场景都有徽标文案`() {
        val missing = NotifyScenes.ALL - MailTemplate.registeredLabelScenes
        assertTrue("这些场景没登记徽标文案，会显示成默认的「通知」: $missing", missing.isEmpty())

    }

    /**
     * 反过来也查一遍：模板里登记了 [NotifyScenes.ALL] 之外的场景 = 场景常量被删了却没清模板，
     * 留着的是永远不会命中的死条目。
     */
    @Test
    fun `模板里没有多余的场景条目`() {
        val extraColors = MailTemplate.registeredColorScenes - NotifyScenes.ALL
        val extraLabels = MailTemplate.registeredLabelScenes - NotifyScenes.ALL
        assertTrue("主色表里有已不存在的场景: $extraColors", extraColors.isEmpty())
        assertTrue("徽标表里有已不存在的场景: $extraLabels", extraLabels.isEmpty())
    }
}
