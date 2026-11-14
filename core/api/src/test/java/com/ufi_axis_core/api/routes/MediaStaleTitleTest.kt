package com.ufi_axis_core.api.routes

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * MediaStore 陈旧标题的识别（2026-10-13）。
 *
 * 真机样本：
 *   磁盘文件名： `阳光下的星星-金海心.flac`
 *   MediaStore TITLE：`阳光下的星星 -金海心.flac.29894902._CEujVT6XmHgTkI2`
 *
 * `.29894902` 是下载分片的字节数、`_CEujVT6XmHgTkI2` 是网易云音乐的临时文件标记。
 * 所以这不是编码乱码，而是**文件改名后 MediaStore 没重扫**，库里留着下载期的临时标题。
 *
 * 本测试锁定的是判据（「陈旧」的特征），不是某个具体文件名：
 * 若日后把判据放宽到会误伤真曲名，这组断言会先失败。
 */
class MediaStaleTitleTest {

    /** 与 `MediaRoutes.realTitleOf` 同口径的判定，抽成函数以便直接测。 */
    private fun isStale(title: String, base: String): Boolean {
        val key = { s: String -> s.trim().lowercase().replace('\u3000', ' ').replace(Regex("\\s+"), "") }
        val t = key(title)
        val b = key(base)
        return b.isNotEmpty() && t.length > b.length && t.startsWith(b)
    }

    @Test
    fun `陈旧下载临时名 -真机样本 -应判为陈旧`() {
        assertEquals(
            true,
            isStale("阳光下的星星 -金海心.flac.29894902._CEujVT6XmHgTkI2", "阳光下的星星-金海心")
        )
    }

    @Test
    fun `字节数不同也要拦住 -不能只匹配固定后缀`() {
        // 同一个下载器每次的分片大小都不同，只认死后缀的话换个文件就漏。
        assertEquals(
            true,
            isStale("阳光下的星星 -金海心.flac.29894902._CEujVT6XmHgTkI2", "阳光下的星星-金海心")
        )
        assertEquals(
            true,
            isStale("带我走.flac.1048576._8XyZqLmNpQrStUvW", "带我走")
        )
    }

    @Test
    fun `真曲名不能被误伤 -中文歌名带一个点仍应保留`() {
        assertEquals(
            false,
            isStale("Mr. Blue Sky", "Mr. Blue Sky.flac")
        )
    }

    @Test
    fun `真曲名不能被误伤 -带两个点的英文歌名仍应保留`() {
        // 这条是「点数 ≥ 2 就判陈旧」那条宽规则的反例，也是把它放弃掉的原因：
        // `Dr. A. Livingstone` 归一化后有两个点，按宽规则会被丢掉 —— 而它是真曲名。
        assertEquals(
            false,
            isStale("Dr. A. Livingstone", "Dr. A. Livingstone")
        )
    }
}
