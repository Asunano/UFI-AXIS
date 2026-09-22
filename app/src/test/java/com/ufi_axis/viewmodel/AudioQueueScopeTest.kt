package com.ufi_axis.viewmodel

import com.ufi_axis.data.model.MEDIA_GROUP_ALBUM
import com.ufi_axis.viewmodel.state.AUDIO_SCOPE_ALL
import com.ufi_axis.viewmodel.state.AUDIO_SCOPE_PLAYLIST
import com.ufi_axis.viewmodel.state.AudioQueueScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 播放作用域的路由参数还原。
 *
 * ## 守的是什么
 * `AudioQueueScope.of` 是「路由参数 → 队列该装哪些歌」的唯一入口，而它的取值在
 * `MediaAudioPlayerScreen` 里决定**要不要重建播放队列**。判定写错的后果不是崩溃，
 * 而是队列悄悄被换掉 —— 用户从歌单点第一首，「下一首」跑去别的歌，
 * 而这在编译期与界面上都看不出来。
 *
 * 两条语义各对应一个真实事故，都必须钉住：
 * 1. `playlist` / 分组不能被当成分组以外的东西（否则回查用错的过滤参数，得到空队列）；
 * 2. **「没传 scope」必须回 null，不能回整库** —— 回整库那一版会让"从迷你条点回播放页"
 *    把歌单队列静默重装成整库（2026-09-21 实测）。
 */
class AudioQueueScopeTest {

    @Test
    fun playlistScopeKeepsIdAndIsNotAGroup() {
        val scope = AudioQueueScope.of(AUDIO_SCOPE_PLAYLIST, "a1b2c3d4")
        assertEquals(AUDIO_SCOPE_PLAYLIST, scope?.kind)
        assertEquals("a1b2c3d4", scope?.key)
        assertTrue("歌单必须被识别为歌单", scope?.isPlaylist == true)
        // 这两条是关键：isGroup 为真会让 queueItemsOf 去查 /api/media/list 的过滤参数，
        // 而歌单 id 不是专辑名，结果是一个空队列
        assertFalse("歌单不是分组维度", scope?.isGroup == true)
        assertFalse("歌单不是整库", scope?.isAll == true)
    }

    /**
     * **没传 scope = 没指定范围**，不是整库。
     *
     * 迷你条 / 标题栏挂件 / 通知栏深链接走的都是这条：它们只知道"正在播这首歌"，
     * 不知道当前队列是按歌单还是专辑装的。回 null 才能让播放页"别动队列"。
     */
    @Test
    fun missingScopeMeansUnspecifiedNotWholeLibrary() {
        assertNull(AudioQueueScope.of(null, null))
        assertNull(AudioQueueScope.of("", ""))
        // 非法 kind 同样按"没指定"处理：宁可保留当前队列，也不要按看不懂的参数重建
        assertNull(AudioQueueScope.of("bogus", "x"))
    }

    /** 「全部」页点歌会显式带 `scope=all`，它**没有 key**，必须仍然解成整库。 */
    @Test
    fun explicitAllScopeNeedsNoKey() {
        val scope = AudioQueueScope.of(AUDIO_SCOPE_ALL, "")
        assertTrue("scope=all 必须解成整库", scope?.isAll == true)
    }

    /** key 为空的分组无从回查（会被 core 当成"没传过滤参数"= 整库），按"没指定"处理。 */
    @Test
    fun groupWithBlankKeyIsUnspecified() {
        assertNull(AudioQueueScope.of(MEDIA_GROUP_ALBUM, ""))
        assertNull(AudioQueueScope.of(AUDIO_SCOPE_PLAYLIST, ""))
    }

    /** 加了 playlist 之后分组维度必须仍然只认自己那一档。 */
    @Test
    fun groupScopeIsNotMistakenForPlaylist() {
        val scope = AudioQueueScope.of(MEDIA_GROUP_ALBUM, "点水")
        assertTrue(scope?.isGroup == true)
        assertFalse(scope?.isPlaylist == true)
    }
}
