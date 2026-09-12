package com.ufi_axis_core.notify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [NotifyLevel] 的两条对外口径：线上契约用的英文小写名、给人看的中文名。
 *
 * 为什么值得一条单测：两者都是**跨端消费**的。[NotifyLevel.wireName] 是线上契约
 * （app / web 按小写解析），[NotifyLevel.label] 是 Webhook 的 `{{level_label}}` 与界面标签的
 * 唯一来源 —— 新增一档级别时忘了给中文名，表现是用户收到的通知里级别那一格是空白，
 * 而不会有任何报错。
 */
class NotifyLevelTest {

    /** 三档的中文名都得有，且不能互相重复（重复等于用户分不出级别）。 */
    @Test
    fun `每档级别都有非空的中文名`() {
        for (level in NotifyLevel.entries) {
            assertTrue("${level.name} 的 label 是空的", level.label.isNotBlank())
        }
        assertEquals(
            "级别的中文名不能重复",
            NotifyLevel.entries.size,
            NotifyLevel.entries.map { it.label }.toSet().size
        )
    }

    /** 中文名就是这三个（Webhook 的 `{{level_label}}` 渲染出来的就是它们）。 */
    @Test
    fun `中文名逐档钉住`() {
        assertEquals("提示", NotifyLevel.INFO.label)
        assertEquals("警告", NotifyLevel.WARNING.label)
        assertEquals("严重", NotifyLevel.CRITICAL.label)
    }

    /** 线上契约仍是英文小写（label 的加入不能动它 —— 那是 app / web 的解析口径）。 */
    @Test
    fun `线上口径仍是英文小写名`() {
        assertEquals("info", NotifyLevel.INFO.wireName)
        assertEquals("warning", NotifyLevel.WARNING.wireName)
        assertEquals("critical", NotifyLevel.CRITICAL.wireName)
        assertEquals(NotifyLevel.WARNING, NotifyLevel.fromWire("warning"))
        assertEquals("认不出的名字回落 INFO", NotifyLevel.INFO, NotifyLevel.fromWire("不存在的级别"))
    }
}
