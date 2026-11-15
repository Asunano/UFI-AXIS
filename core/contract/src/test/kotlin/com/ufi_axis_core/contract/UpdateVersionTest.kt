package com.ufi_axis_core.contract

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 更新判定：版本名优先、同名看 versionCode、不降级。 */
class UpdateVersionTest {

    @Test
    fun `同名重发靠 versionCode 识别`() {
        // 2026-11-15 线上问题：0.1.1 / code 6 已发布，但已装 0.1.1 / code 5 的用户
        // 点检查更新永远显示"已是最新"——因为旧实现只比版本名。
        assertTrue(hasUpdate("0.1.1", 6, "0.1.1", 5))
        assertFalse(hasUpdate("0.1.1", 5, "0.1.1", 6))
        assertFalse(hasUpdate("0.1.1", 5, "0.1.1", 5))
    }

    @Test
    fun `版本名不同时以名字为准`() {
        assertTrue(hasUpdate("0.1.2", 1, "0.1.1", 9))
        assertFalse(hasUpdate("0.1.0", 9, "0.1.1", 1))
    }

    @Test
    fun `清单缺 versionCode 时退回纯版本名比较`() {
        assertTrue(hasUpdate("0.2.0", 0, "0.1.1", 5))
        assertFalse(hasUpdate("0.1.1", 0, "0.1.1", 5))
    }

    @Test
    fun `版本名读不到时任何比较都不成立`() {
        assertFalse(hasUpdate("0.1.1", 6, "", 5))
        assertFalse(hasUpdate("", 6, "0.1.1", 5))
    }

    @Test
    fun `版本名解析不做降级误判`() {
        assertEquals(0, compareVersionNames("v1.2", "1.2"))
        assertEquals(0, compareVersionNames("1.2.0", "1.2"))
        assertTrue(compareVersionNames("1.10", "1.9") > 0)
        assertTrue(compareVersionNames("1.2.1", "1.2") > 0)
    }
}
