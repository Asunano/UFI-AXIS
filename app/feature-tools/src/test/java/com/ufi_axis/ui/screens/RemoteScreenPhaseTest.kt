package com.ufi_axis.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 远程屏幕「遮罩三态」的判据（2026-11-04）。
 *
 * ## 守的是哪一个 bug
 * 症状（用户原话）："点连接后没有加载动画，误导以为可以直接点击，其实要等一会才能连上"。
 *
 * 旧判据是 `connected -> CONNECTED`，而 `connected` 就是 `session != 0` —— 它在**点击那一刻**
 * 就变真（`connect()` 里 `session += 1`）。于是：
 * - `MASK_CONNECTING`（圆环那一屏）**是不可达的死代码**；
 * - 用户点完立刻看到控制条，画面却还要等取票 + 建 WS + 等关键帧（1–2 秒）；
 * - 那 1–2 秒里所有控制按钮都在，但点什么都没用 —— 正是"看着能点、其实在等"。
 *
 * 现在只有**首帧真的上屏**（[streamReady]）才允许进控制条。这个测试把这条不变量钉死：
 * 只要有人再把判据写回 `sessionStarted`，第一个用例就会红。
 */
class RemoteScreenPhaseTest {

    @Test
    fun sessionStarted_withoutFirstFrame_mustStayConnecting() {
        assertEquals(
            "会话已发起但首帧还没到，必须停在「连接中」（圆环）—— 这正是用户投诉的 1–2 秒空白。",
            ScreenPhase.MASK_CONNECTING,
            screenPhaseOf(sessionStarted = true, streamReady = false),
        )
    }

    @Test
    fun firstFrame_reachesConnected() {
        assertEquals(
            ScreenPhase.CONNECTED,
            screenPhaseOf(sessionStarted = true, streamReady = true),
        )
    }

    @Test
    fun noSession_showsOpenMask() {
        assertEquals(
            ScreenPhase.MASK_OPEN,
            screenPhaseOf(sessionStarted = false, streamReady = false),
        )
    }

    @Test
    fun readyFlagAlone_cannotShowConnected() {
        // streamReady 是"上一轮会话可能遗留"的标记（切模式/重开时会被显式复位）。
        // 万一它没被复位，也不能在没有会话的情况下画出控制条。
        assertEquals(
            ScreenPhase.MASK_OPEN,
            screenPhaseOf(sessionStarted = false, streamReady = true),
        )
    }
}
