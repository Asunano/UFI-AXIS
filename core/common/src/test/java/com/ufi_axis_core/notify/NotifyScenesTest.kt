package com.ufi_axis_core.notify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier

/**
 * [NotifyScenes.ALL] 与常量表的一致性。
 *
 * `ALL` 本身没有生产调用方，它的**唯一用途就是被本测试和 `MailTemplateSceneCoverageTest` 读**：
 * 新增一个场景常量却忘了登记，后果是邮件渲染成默认色 + "通知"徽标（与短信长得一模一样），
 * 而这在日志里看不出任何异常。所以"没人用的集合"不是死代码，它是这条检查的锚点 ——
 * 前提是检查真的存在（重构注释里承诺过、但一直没写）。
 *
 * 用**反射**而不是手写清单：手写清单只是把同一份遗漏抄两遍，加常量时照样会漏。
 */
class NotifyScenesTest {

    /**
     * 反射取 [NotifyScenes] 里所有 `String` 常量。
     *
     * Kotlin 的 `const val` 编译成宿主类上的 `public static final String`，
     * 而 `ALL`（`Set`）与 `INSTANCE` 类型不同，靠类型过滤自然排除。
     */
    private fun declaredSceneConstants(): Map<String, String> =
        NotifyScenes::class.java.declaredFields
            .filter { Modifier.isStatic(it.modifiers) && it.type == String::class.java }
            .associate { it.name to (it.get(null) as String) }

    @Test
    fun `每个场景常量都登记进了 ALL`() {
        val constants = declaredSceneConstants()
        assertTrue("反射一个常量都没拿到，说明这个测试本身失效了", constants.isNotEmpty())
        val missing = constants.filterValues { it !in NotifyScenes.ALL }
        assertTrue("这些常量没登记进 NotifyScenes.ALL: $missing", missing.isEmpty())
    }

    @Test
    fun `ALL 里没有多出来的场景 id`() {
        val values = declaredSceneConstants().values.toSet()
        val extra = NotifyScenes.ALL - values
        assertTrue("ALL 里有不属于任何常量的场景 id（常量删了没同步删）: $extra", extra.isEmpty())
    }

    /** 常量数与 [NotifyScenes.ALL] 的大小必须相等，顺带钉住"两个常量写了同一个字符串"。 */
    @Test
    fun `场景 id 不重复`() {
        val constants = declaredSceneConstants()
        assertEquals(
            "有两个常量指向同一个场景 id: $constants",
            constants.size,
            constants.values.toSet().size
        )
    }
}
