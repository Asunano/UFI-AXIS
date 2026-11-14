package com.ufi_axis_core.contract

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `ppp_status` 判据（2026-10-31）。
 *
 * 这一条判据同时决定三件事，写错一件都是用户能直接看到的错：
 *   · 链路态（`/api/network/status` 的 `ppp_status`、`dataHub.getLivePppStatus()`）；
 *   · 制式切换后要不要恢复移动数据（`NetworkController.setNetworkMode`）；
 *   · 告警与自动化里「蜂窝网络断开」的判定。
 *
 * 注意它**不**决定 web / app 上那个「移动数据」开关：2026-11-02 起 `mobile_data` 是用户意图
 * （`MobileDataIntent`），因为换制式时设备必然先拆链，链路态会让开关假关闭。
 *
 * 重点是 `"disconnected"` 里**也含** `"connected"` —— 先认 connected、再排除 disconnected，
 * 顺序反了会恒为 false；用 equals 判则换个固件前缀就全挂。
 */
class PppStatusPredicateTest {

    @Test
    fun `真机取值 ppp_connected 算已连接`() {
        assertTrue(DeviceFields.Connection.isPppConnected("ppp_connected"))
    }

    @Test
    fun `真机取值 ppp_disconnected 不算已连接`() {
        assertFalse(
            "disconnected 里含 connected，必须先排除 —— 这里错了会把断网报成连着",
            DeviceFields.Connection.isPppConnected("ppp_disconnected"),
        )
    }

    @Test
    fun `大小写与前后缀都不敏感`() {
        assertTrue(DeviceFields.Connection.isPppConnected("PPP_CONNECTED"))
        assertTrue(DeviceFields.Connection.isPppConnected("connected"))
        assertTrue(DeviceFields.Connection.isPppConnected("  dial_connected  "))
    }

    @Test
    fun `空值与 null 一律不算已连接`() {
        assertFalse(DeviceFields.Connection.isPppConnected(""))
        assertFalse(DeviceFields.Connection.isPppConnected("   "))
        assertFalse(DeviceFields.Connection.isPppConnected(null))
    }

    @Test
    fun `既不是 connected 也不是 disconnected 的取值不算已连接`() {
        // 设备在重注册期间会回 "connecting" / "idle" 一类中间态：那不是「连着」
        assertFalse(DeviceFields.Connection.isPppConnected("connecting"))
        assertFalse(DeviceFields.Connection.isPppConnected("idle"))
    }
}
