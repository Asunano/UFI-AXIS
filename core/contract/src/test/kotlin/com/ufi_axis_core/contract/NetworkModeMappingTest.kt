package com.ufi_axis_core.contract

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 网络模式别名 ↔ BearerPreference 双向映射。
 *
 * App 的 `awaitNetworkModeApplied` 用 `fromBearer(settings.networkMode) == 目标别名`
 * 做切换确认；漏映射一档真机取值就会「写入成功却一直提示未完成切换」。
 */
class NetworkModeMappingTest {

    @Test
    fun `fromBearer 覆盖 Only_5G 与 NR5G_ONLY 等价写法`() {
        assertEquals(NetworkMode.ONLY_5G, NetworkMode.fromBearer("Only_5G"))
        assertEquals(NetworkMode.ONLY_5G, NetworkMode.fromBearer("NR5G_ONLY"))
        assertEquals(NetworkMode.ONLY_5G, NetworkMode.fromBearer("only_5g"))
        assertEquals(NetworkMode.ONLY_5G, NetworkMode.fromBearer("5G_ONLY"))
    }

    @Test
    fun `fromBearer 覆盖 LTE+NR 组合`() {
        assertEquals(NetworkMode.LTE_AND_5G, NetworkMode.fromBearer("LTE_AND_5G"))
        assertEquals(NetworkMode.LTE_AND_5G, NetworkMode.fromBearer("LTE_NR5G"))
        assertEquals(NetworkMode.LTE_AND_5G, NetworkMode.fromBearer("LTE_AND_NR5G"))
        assertEquals(NetworkMode.LTE_AND_5G, NetworkMode.fromBearer("NR5G_NSA"))
        assertEquals(NetworkMode.LTE_AND_5G, NetworkMode.fromBearer("5G_NSA"))
    }

    @Test
    fun `fromBearer 覆盖其余档位与老字段写法`() {
        assertEquals(NetworkMode.AUTO, NetworkMode.fromBearer("WL_AND_5G"))
        assertEquals(NetworkMode.ONLY_LTE, NetworkMode.fromBearer("Only_LTE"))
        assertEquals(NetworkMode.ONLY_LTE, NetworkMode.fromBearer("LTE_ONLY"))
        assertEquals(NetworkMode.ONLY_WCDMA, NetworkMode.fromBearer("Only_WCDMA"))
        assertEquals(NetworkMode.WCDMA_AND_LTE, NetworkMode.fromBearer("LTE_WCDMA"))
    }

    @Test
    fun `toBearer 对设备值幂等并接受回读别名`() {
        assertEquals(NetworkMode.Bearer.ONLY_5G, NetworkMode.toBearer("5G_ONLY"))
        assertEquals(NetworkMode.Bearer.ONLY_5G, NetworkMode.toBearer("Only_5G"))
        assertEquals(NetworkMode.Bearer.ONLY_5G, NetworkMode.toBearer("NR5G_ONLY"))
        assertEquals(NetworkMode.Bearer.LTE_AND_5G, NetworkMode.toBearer("LTE_NR5G"))
        assertEquals(NetworkMode.Bearer.WL_AND_5G, NetworkMode.toBearer("AUTO"))
    }

    @Test
    fun `UI 六档 fromBearer toBearer 往返稳定`() {
        for (alias in NetworkMode.UI_OPTIONS) {
            val bearer = NetworkMode.toBearer(alias)
            assertEquals(alias, NetworkMode.fromBearer(bearer))
        }
    }

    @Test
    fun `未识别取值原样返回不塌成 AUTO`() {
        assertEquals("SOMETHING_ELSE", NetworkMode.fromBearer("SOMETHING_ELSE"))
        assertEquals("SOMETHING_ELSE", NetworkMode.toBearer("SOMETHING_ELSE"))
    }

    @Test
    fun `中文标签与真机档位一致且顺序正确`() {
        assertEquals("5G/4G/3G", NetworkMode.label(NetworkMode.AUTO))
        assertEquals("5G NSA", NetworkMode.label(NetworkMode.LTE_AND_5G))
        assertEquals("5G SA", NetworkMode.label(NetworkMode.ONLY_5G))
        assertEquals("4G/3G", NetworkMode.label(NetworkMode.WCDMA_AND_LTE))
        assertEquals("仅4G", NetworkMode.label(NetworkMode.ONLY_LTE))
        assertEquals("仅3G", NetworkMode.label(NetworkMode.ONLY_WCDMA))
        assertEquals(
            listOf(
                NetworkMode.AUTO, NetworkMode.LTE_AND_5G, NetworkMode.ONLY_5G,
                NetworkMode.WCDMA_AND_LTE, NetworkMode.ONLY_LTE, NetworkMode.ONLY_WCDMA
            ),
            NetworkMode.UI_OPTIONS
        )
        assertEquals("5G SA", NetworkMode.labelFromBearer("NR5G_ONLY"))
    }
}
