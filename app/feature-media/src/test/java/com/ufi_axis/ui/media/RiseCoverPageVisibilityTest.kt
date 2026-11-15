package com.ufi_axis.ui.media

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「歌词页不画飞行封面」的开关。
 *
 * 守的是 2026-11-15 那个问题：播放页有两页（封面 / 歌词），飞行封面按**最后一次上报的
 * 封面槽**绘制，与"此刻画面里有没有封面"无关。切到歌词页后封面已翻到屏幕外，
 * 但槽位还留着 —— 此时下拉关闭会看到一张封面凭空闪一下再跟着滑。
 *
 * 这里只锁状态机：默认可见、歌词页置假、`reset()` 复位（下次升起总落在封面页）。
 */
class RiseCoverPageVisibilityTest {

    @After
    fun tearDown() {
        // 单例状态是进程级的，别把假值留给别的用例
        UfiAudioRisePanel.reportCoverPageVisible(true)
    }

    @Test
    fun `默认可见（面板第一次升起总在封面页）`() {
        assertTrue(UfiAudioRisePanel.coverPageVisible)
    }

    @Test
    fun `切到歌词页后不可见`() {
        UfiAudioRisePanel.reportCoverPageVisible(false)
        assertFalse(UfiAudioRisePanel.coverPageVisible)
    }

    @Test
    fun `切回封面页恢复可见`() {
        UfiAudioRisePanel.reportCoverPageVisible(false)
        UfiAudioRisePanel.reportCoverPageVisible(true)
        assertTrue(UfiAudioRisePanel.coverPageVisible)
    }
}
