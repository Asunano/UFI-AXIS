package com.ufi_axis.ui.components.common

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 信号等级文案（[signalLevelText]）的回归护栏。
 *
 * ## 为什么这个测试是 2026-10-14 补建的
 * 这个函数原先叫 `FormatUtils.getSignalLevel`、住在 `:app` 的 `FormatUtils` 里。
 * 2026-10-11「措辞改正式」（极好/好/一般/差/极差 → 优秀/良好/一般/较差/极差）时它被一并
 * 迁到了本模块，**但它的单测没跟着走** —— `FormatUtilsTest` 里那 6 条仍在调已删除的
 * `FormatUtils.getSignalLevel`。后果不是「少一个类」，而是 `:app:testDebugUnitTest`
 * **整条测试任务编译不过**，从那天起该模块的单测全部失效。
 *
 * 现在把用例放到函数真正所在的模块，并按**现行措辞**断言。
 *
 * ## 比原用例多出来的一层
 * 原用例只取档内中值（-75 / -85 / -95 / -110 / -120），档位**边界**写错时一条都抓不住
 * （把 `>= -90` 误写成 `> -90`，五个中值全部照过）。所以这里补了 [档位边界逐条钉死]。
 */
class SignalLevelTextTest {

    @Test
    fun `null 读数给出未知`() {
        assertEquals("未知", signalLevelText(null))
    }

    @Test
    fun `五档措辞与英文域名一一对应`() {
        // Excellent / Good / Fair / Poor / Dead —— 中文档位必须与这套英文域名对齐，
        // 否则同一套数据在中英文语境下读起来是两件事（改动理由见 UfiSignalBars.kt 的注释）。
        assertEquals("优秀", signalLevelText(-75))
        assertEquals("良好", signalLevelText(-85))
        assertEquals("一般", signalLevelText(-95))
        assertEquals("较差", signalLevelText(-110))
        assertEquals("极差", signalLevelText(-120))
    }

    @Test
    fun `档位边界逐条钉死`() {
        // 判据是 `>=`，所以边界值本身属于**更好的那一档**。
        assertEquals("优秀", signalLevelText(-80))
        assertEquals("良好", signalLevelText(-81))
        assertEquals("良好", signalLevelText(-90))
        assertEquals("一般", signalLevelText(-91))
        assertEquals("一般", signalLevelText(-100))
        assertEquals("较差", signalLevelText(-101))
        assertEquals("较差", signalLevelText(-115))
        assertEquals("极差", signalLevelText(-116))
    }
}
