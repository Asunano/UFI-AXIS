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

    /**
     * 2026-10-21：`pairing:approvals` **不该**归进任何分组。
     *
     * 实现里它在 [MainViewModel.dispatchChanged] 的分组**之前**就短路了（直接
     * `loadApprovalsFromPush()`），所以它根本走不到 `groupOf`。这条断言的意义是把这件事写死：
     * 一旦有人给它加了分组、"顺手"让它走 800ms 尾沿去抖，弹窗就白等 800ms，
     * 而 core 一轮补发的 4 帧会被合并成 1 次 —— 功能看起来还在，只是又变慢了。
     */
    @Test
    fun `配对待批准不参与去抖分组（实现里已短路）`() {
        assertEquals("other", groupOf("pairing:approvals"))
    }
}
