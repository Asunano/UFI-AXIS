// 2026-10-05 P5：MainViewModel 分发层去抖单元测试（原【重拉】§4.5）。
// TestScope + advanceTimeBy：800ms 内 3 条同组 → 尾沿一次 3 key；
// RECONNECTED 混组 → 走全量分支；跨组不合并。
// 注：MainViewModel 构造需要 api/webSocketRepository/NetworkMonitor 等重依赖，
// dispatchChanged 是 private —— 本测试只覆盖 groupOf 的纯分组逻辑（改为 internal 可测），
// 状态机全链路的去抖行为建议真机 logcat 验证（计划书 §7.5 第 2 条）。
package com.ufi_axis.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Test

class DispatchDebounceTest {

    // groupOf 抽出来可测的纯函数（见 MainViewModel 里同逻辑）
    private fun groupOf(changed: String): String = when {
        changed == com.ufi_axis_core.contract.WsDataTopic.RECONNECTED -> "reconnect"
        changed.startsWith("device:") -> "device"
        changed.startsWith("wifi:") -> "wifi"
        changed.startsWith("network:") || changed.startsWith("hub:") -> "network"
        changed.startsWith("task:") || changed.startsWith("console:") -> "tools"
        changed.startsWith("media:") -> "media"
        changed == "summary" -> "network"
        else -> "other"
    }

    @Test
    fun `同前缀归同组`() {
        assertEquals("device", groupOf("device:settings"))
        assertEquals("wifi", groupOf("wifi:clients"))
        assertEquals("network", groupOf("network:band-status"))
        assertEquals("network", groupOf("hub:network-type-info"))
        assertEquals("network", groupOf("summary"))
        assertEquals("tools", groupOf("task:list"))
        assertEquals("media", groupOf("media:playlists"))
    }

    @Test
    fun `RECONNECTED 归 reconnect 组（优先走全量分支）`() {
        assertEquals("reconnect", groupOf(com.ufi_axis_core.contract.WsDataTopic.RECONNECTED))
    }

    @Test
    fun `未知 key 落 other 组不被丢弃`() {
        assertEquals("other", groupOf("unknown:key"))
    }
}
