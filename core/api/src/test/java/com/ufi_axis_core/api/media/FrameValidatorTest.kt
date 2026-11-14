package com.ufi_axis_core.media

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.util.Random

/**
 * G8（FFmpeg 接入计划书 §6.1）：`FrameValidator` 四判据逐条测试。
 *
 * 判据与常量的来源见 [FrameValidator] 的 KDoc——这里锁定的是**行为**：
 * 每种历史上真实出现过的问题帧（全零帧 F3 / 1×1 占位 F4 / 过小文件 / 正常图）
 * 必须落到各自对应的 [FrameValidator.Verdict] 分支，防止后续改动悄悄放宽判据。
 *
 * ## 为什么图像要叠噪声
 * Robolectric NATIVE 模式的 JPEG 编码器对**纯色大图**压缩得极狠（320×240 纯色仅 ~1.3KB，
 * 实测），会误触判据 1 的 `MIN_BYTES` 下限。给图像叠随机噪声后熵变大、压缩率正常，
 * 文件尺寸才反映真实的"够不够大"，各判据才能被独立触达。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
// 必须开 NATIVE：默认 shadow BitmapFactory 不真解码——返回固定 100×100 全 0 像素的假图，
// 于是每张图都判成 Blank（判据 3），尺寸/亮度判据全部失去区分度，测试变成自欺。
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FrameValidatorTest {

    /** 生成 width×height、基准亮度 [level]、叠加噪声的 JPEG（噪声保证压缩后体积够大）。 */
    private fun noisyJpeg(width: Int, height: Int, level: Int): ByteArray {
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        Canvas(bmp).drawColor(Color.rgb(level, level, level))
        val rnd = Random(42)
        val maxDelta = if (level > 100) 6 else 3 // 亮图可以多抖一点，暗图别抖过 DARK_THRESHOLD
        for (y in 0 until height step 2) {
            for (x in 0 until width step 2) {
                val d = rnd.nextInt(maxDelta * 2 + 1) - maxDelta
                val v = (level + d).coerceIn(0, 255)
                bmp.setPixel(x, y, Color.rgb(v, v, v))
            }
        }
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 95, out)
        bmp.recycle()
        return out.toByteArray()
    }

    // ── 判据 1：字节数下限（全零帧 JPEG 实测 ~1.6KB）──

    @Test
    fun `null 输入判 Undecodable`() {
        assertTrue(FrameValidator.validate(null) is FrameValidator.Verdict.Undecodable)
    }

    @Test
    fun `小于 MIN_BYTES 的输入判 Undecodable`() {
        // 1×1 黑 JPEG 实际 ~300B，同时踩判据 1 与判据 2；先锁定判据 1 的行为
        val tiny = noisyJpeg(1, 1, 0)
        assertTrue(tiny.size < FrameValidator.MIN_BYTES)
        assertTrue(FrameValidator.validate(tiny) is FrameValidator.Verdict.Undecodable)
    }

    @Test
    fun `非 JPEG 字节流被拒绝（实测落 TooSmall，不是 Undecodable）`() {
        // 锁定实现的真实判定顺序（不是 bug，是判据顺序的自然结果）：
        // 字节数过关后先跑 inJustDecodeBounds —— BitmapFactory 对无法识别的字节流
        // 返回 outWidth=0，于是先撞判据 2 的尺寸下限，走不到后面的 Undecodable。
        // [Verdict.Undecodable] 只在"bounds 可读但完整解码返回 null"时触达。
        // 两者都是"拒绝入库"，这里如实锁定以便将来改判定顺序时会被测试拦住。
        val garbage = ByteArray(FrameValidator.MIN_BYTES + 100) { (it % 251).toByte() }
        assertTrue(FrameValidator.validate(garbage) is FrameValidator.Verdict.TooSmall)
    }

    // ── 判据 2：尺寸下限（1×1 占位图防线）──

    @Test
    fun `尺寸小于 MIN_DIM 判 TooSmall`() {
        // 窄条 15×800：面积够大（噪声下体积远超 MIN_BYTES，过判据 1），
        // 但宽度 15 < MIN_DIM —— 只能被判据 2 拦下（15×15 那种小图体积本身就不够，
        // 会先撞判据 1，测不到判据 2）。
        val narrow = noisyJpeg(FrameValidator.MIN_DIM - 1, 800, 255)
        assertTrue("窄条应超过 MIN_BYTES，实际 ${narrow.size}", narrow.size >= FrameValidator.MIN_BYTES)
        assertTrue(FrameValidator.validate(narrow) is FrameValidator.Verdict.TooSmall)
    }

    // ── 判据 3：全零帧（亮度为 0）──

    @Test
    fun `全黑大图判 Blank（F3 全零帧等价物）`() {
        // 足够大、能解码、但亮度为 0 —— 与 HEVC Ma10p 产出的全零 JPEG 行为一致。
        // 纯黑 JPEG 压缩后小，用大尺寸把它顶过 MIN_BYTES。
        val black = noisyJpeg(640, 480, 0)
        assertTrue("黑图应超过 MIN_BYTES，实际 ${black.size}", black.size >= FrameValidator.MIN_BYTES)
        assertTrue(FrameValidator.validate(black) is FrameValidator.Verdict.Blank)
    }

    // ── 判据 4：暗图与 allowDark ──

    @Test
    fun `暗图在 allowDark=true 时判 Valid（暗场电影给暗图可以）`() {
        val dark = noisyJpeg(320, 240, 14)
        assertTrue(FrameValidator.validate(dark, allowDark = true) is FrameValidator.Verdict.Valid)
    }

    @Test
    fun `暗图在 allowDark=false 时判 TooDark（多候选场景挑最亮）`() {
        val dark = noisyJpeg(320, 240, 14)
        val verdict = FrameValidator.validate(dark, allowDark = false)
        assertTrue("实际 ${verdict}", verdict is FrameValidator.Verdict.TooDark)
        // TooDark 带回亮度值，供调用方比较"最亮候选"
        assertTrue((verdict as FrameValidator.Verdict.TooDark).luma in 1 until FrameValidator.DARK_THRESHOLD)
    }

    // ── 正常路径 ──

    @Test
    fun `正常亮度大图判 Valid`() {
        val good = noisyJpeg(320, 240, 200)
        assertTrue(FrameValidator.validate(good) is FrameValidator.Verdict.Valid)
    }
}
