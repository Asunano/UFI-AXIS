package com.ufi_axis.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 天气图标的**分派**与**数量**。
 *
 * 这两件事错了都不会崩，只会表现成"大雨看起来和小雨一样""雷暴画成了下雨"——
 * UI 上极难回归，所以用纯函数锁住。
 *
 * 覆盖口径 = core `WeatherRoutes.describe()` 里的全部 **28** 个 WMO 码
 * （0/1/2/3、45/48、51/53/55/56/57、61/63/65/66/67、71/73/75/77、80/81/82、85/86、95/96/99）。
 */
class WeatherGlyphDispatchTest {

    // ── 分派：每个码落到哪一种动画 ──

    @Test
    fun `晴与晴间多云按昼夜分太阳和月亮`() {
        assertEquals(WeatherKind.SUN, weatherKindOf(0, isDay = true))
        assertEquals(WeatherKind.SUN, weatherKindOf(1, isDay = true))
        assertEquals(WeatherKind.MOON, weatherKindOf(0, isDay = false))
        assertEquals(WeatherKind.MOON, weatherKindOf(1, isDay = false))
    }

    @Test
    fun `多云白天是太阳加云 夜里只有云`() {
        assertEquals(WeatherKind.PARTLY, weatherKindOf(2, isDay = true))
        assertEquals(WeatherKind.CLOUD, weatherKindOf(2, isDay = false))
        assertEquals(WeatherKind.CLOUD, weatherKindOf(3, isDay = true))
    }

    @Test
    fun `雾与雾凇共用雾带`() {
        assertEquals(WeatherKind.FOG, weatherKindOf(45, isDay = true))
        assertEquals(WeatherKind.FOG, weatherKindOf(48, isDay = false))
    }

    @Test
    fun `雨 雪 雷暴各自归位`() {
        listOf(51, 53, 55, 56, 57, 61, 63, 65, 66, 67, 80, 81, 82).forEach {
            assertEquals("code=$it 应为雨", WeatherKind.RAIN, weatherKindOf(it, true))
        }
        listOf(71, 73, 75, 77, 85, 86).forEach {
            assertEquals("code=$it 应为雪", WeatherKind.SNOW, weatherKindOf(it, true))
        }
        listOf(95, 96, 99).forEach {
            assertEquals("code=$it 应为雷暴", WeatherKind.STORM, weatherKindOf(it, true))
        }
    }

    @Test
    fun `未知码兜底为云 不画成下雨`() {
        assertEquals(WeatherKind.CLOUD, weatherKindOf(-1, true))
        assertEquals(WeatherKind.CLOUD, weatherKindOf(1000, true))
    }

    /** 27 个码一个不漏：任何一个掉进兜底都说明映射表漏了一行。 */
    @Test
    fun `core 的 28 个码全部有专属动画`() {
        val known = listOf(
            0, 1, 2, 3, 45, 48,
            51, 53, 55, 56, 57,
            61, 63, 65, 66, 67,
            71, 73, 75, 77,
            80, 81, 82,
            85, 86,
            95, 96, 99,
        )
        assertEquals(28, known.size)
        known.forEach { code ->
            val kind = weatherKindOf(code, isDay = true)
            assertTrue("code=$code 没有专属动画（落到 CLOUD 兜底）", kind != WeatherKind.CLOUD || code == 3)
        }
    }

    // ── 数量：强度越高，线/片越多 ──

    @Test
    fun `雨丝条数按强度 2 3 5`() {
        listOf(51, 56, 57, 61, 80).forEach { assertEquals("code=$it", 2, rainDropCount(it)) }
        listOf(53, 63, 66, 67, 81).forEach { assertEquals("code=$it", 3, rainDropCount(it)) }
        listOf(55, 65, 82).forEach { assertEquals("code=$it", 5, rainDropCount(it)) }
    }

    @Test
    fun `雪花片数按强度 2 3 5`() {
        listOf(71, 85).forEach { assertEquals("code=$it", 2, snowFlakeCount(it)) }
        listOf(73, 77, 86).forEach { assertEquals("code=$it", 3, snowFlakeCount(it)) }
        assertEquals(5, snowFlakeCount(75))
    }

    @Test
    fun `非雨非雪返回 0 不会画出多余的线`() {
        listOf(0, 3, 45, 95).forEach {
            assertEquals(0, rainDropCount(it))
            assertEquals(0, snowFlakeCount(it))
        }
    }

    @Test
    fun `大雨大雪下落更快`() {
        listOf(55, 65, 82, 75).forEach { assertTrue("code=$it 应为强降水", isHeavyPrecip(it)) }
        listOf(61, 63, 71, 81).forEach { assertFalse("code=$it 不应为强降水", isHeavyPrecip(it)) }
    }
}
