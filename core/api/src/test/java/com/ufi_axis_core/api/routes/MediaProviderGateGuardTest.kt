package com.ufi_axis_core.api.routes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 「媒体缩略图不得调用系统解码」的回归护栏（2026-10-13，退役系统解码计划）。
 *
 * ## 被守护的事故
 * `android.process.media` 内存涨到 471MB 且不回落，重启 core 才释放。
 *
 * 根因是 `contentResolver.loadThumbnail()`：系统没有该文件的缩略图缓存时，MediaProvider 会
 * 当场打开文件软解一帧（视频还要 demux），并把解码产物留在自己进程的缓存里 ——
 * 那份缓存按**文件**留存、不按访问淘汰。列表滑动 = 几十个文件同时要图，
 * 几十份解码缓冲叠起来就是几百 MB。
 *
 * 2026-10-13 的第一版修复把闸门加在了**调用点**上（只包了 `thumbnailBytes` 那一处），
 * 漏了 `audioCoverBytes` —— 而后者恰好是音频列表的热路径（`/cover` 按行请求）
 * 且恒定请求 1024px。于是"修完 471MB 照旧"。
 *
 * ## 为什么编译期抓不到
 * 两处调用都是合法 Kotlin：`contentResolver.loadThumbnail(uri, size, null)` 在任何位置都能编过。
 * 漏掉一处锁**不会**产生任何编译错误、警告或运行期异常 —— 只会让系统进程悄悄涨内存。
 *
 * ## 本轮的最终做法：不是"给调用点加锁"，而是**把那条调用整个删掉**
 * ffmpeg 已接入且稳定，图片与音频封面本来就不需要 VPU（前者纯软件解码，后者只是读容器
 * 头部的字节结构）。所以系统解码两级（`loadThumbnail` + `MediaMetadataRetriever` 抽帧）
 * 被整体退役，本测试把"不许回来"钉死。
 *
 * 注释会被剥掉再断言 —— 本文件的 KDoc 里反复出现这些名字，
 * 不剥的话断言就变成"注释里提过几次"，护栏等于没生效。
 */
class MediaProviderGateGuardTest {

    @Test
    fun systemDecode_mustNotComeBack() {
        val code = executableCode(source(MediaRoutesPath))

        assertEquals(
            "`loadThumbnail(` 必须出现 0 次（实际 ${count(code, "loadThumbnail")} 次）。" +
                "它是 MediaProvider 同步解码的入口，解码缓存按文件留存且不淘汰 —— " +
                "几十个并发就是 471MB，且不会自己回落。ffmpeg 已稳定，没有理由再调它。",
            0, count(code, "loadThumbnail")
        )
        assertEquals(
            "`getFrameAtTime` 必须出现 0 次（实际 ${count(code, "getFrameAtTime")} 次）。" +
                "MMR 抽帧与 loadThumbnail 是同一套坏掉的系统 codec 栈，在本机必然失败；" +
                "更糟的是它返回**全分辨率**帧（4K ≈ 33MB）且当时没有任何并发闸门 —— " +
                "只是靠「本机必然失败」侥幸没事，那不是设计。",
            0, count(code, "getFrameAtTime")
        )
        assertFalse(
            "`videoFrameThumbnail` 不应再存在（MMR 抽帧已整体退役）",
            code.contains("videoFrameThumbnail")
        )
    }

    /**
     * `MediaMetadataRetriever` 只允许两处使用，且都必须是**提取器**语义（读元数据 / 读封面原子），
     * 不得用于解码画面：
     *  1. `audioTagsOf` —— m4a 等容器读文本标签的兜底；
     *  2. `audioCoverBytes` —— 读内嵌封面（MP4/M4A 的 `covr`），
     *     因为 `AudioTagReader` 目前不解析 MP4 原子，没有它 M4A 封面就没有来路。
     */
    @Test
    fun MediaMetadataRetriever_onlyForExtractorDuties() {
        val code = executableCode(source(MediaRoutesPath))
        assertEquals(
            "`MediaMetadataRetriever(` 只允许 2 处（audioTagsOf 的标签兜底 + " +
                "audioCoverBytes 的封面兜底），实际 ${count(code, "MediaMetadataRetriever(")} 处。" +
                "新增一处意味着多了一条系统媒体栈依赖，请先确认它确实只是「读头部」而不是「解码」。",
            2, count(code, "MediaMetadataRetriever(")
        )
    }

    @Test
    fun audioCover_mustUseCoreByteParser_andLightGate() {
        val code = executableCode(source(MediaRoutesPath))
        val body = functionBody(code, "private fun audioCoverBytes(")

        assertTrue(
            "audioCoverBytes 必须用 AudioTagReader.readCover 解容器头部字节 —— " +
                "这是 core 自有的封面主路径，不依赖任何 codec",
            body.contains("AudioTagReader.readCover(")
        )
        assertFalse(
            "audioCoverBytes 内部**不得**取闸门：调用方已经取了 MediaDecodeGate.light，" +
                "而 Semaphore 不可重入，两处都取会永久挂住",
            body.contains("MediaDecodeGate")
        )
        assertTrue(
            "封面必须先查落盘缓存（coverCacheRead）—— /cover 原来是纯实时的，" +
                "每换一台设备、每清一次客户端缓存都要重算一遍",
            body.contains("coverCacheRead(") && body.contains("coverCacheWrite(")
        )
    }

    @Test
    fun thumbnailBytes_routesEachKindToItsOwnDecoder_underTheRightGate() {
        val code = executableCode(source(MediaRoutesPath))
        val body = functionBody(code, "private suspend fun thumbnailBytes(")

        assertTrue("视频必须走 ffmpeg", body.contains("ffmpegThumbnail("))
        assertTrue("图片必须走 core 自己的降采样解码", body.contains("downscaledImageThumbnail("))
        assertTrue("音频必须走内嵌封面解析", body.contains("audioCoverBytes("))
        assertTrue(
            "图片与音频必须过 MediaDecodeGate.light（调用点在这一层取，被调函数内部不再取）",
            body.contains("MediaDecodeGate.light")
        )
        assertFalse(
            "thumbnailBytes **不得**再取 heavy —— FfmpegThumbnailService 内部已经取了，" +
                "同一协程二次取同一把 Semaphore 会永久挂住",
            body.contains("MediaDecodeGate.heavy")
        )
    }

    @Test
    fun ffmpegService_mustHoldTheHeavyGate() {
        val code = executableCode(source(FfmpegServicePath))
        assertTrue(
            "FfmpegThumbnailService 必须持有 MediaDecodeGate.heavy（唯一真源）。" +
                "自己另起一把 Semaphore 会让前台请求与后台预热各管一摊、谁也不认识谁。",
            code.contains("MediaDecodeGate.heavy")
        )
    }

    @Test
    fun listAndBrowse_mustReadIndexSnapshot_notQueryMediaStoreDirectly() {
        val code = executableCode(source(MediaRoutesPath))

        for (endpoint in listOf("""get("/list")""", """get("/browse")""")) {
            val body = functionBody(code, endpoint)
            assertTrue(
                "$endpoint 必须从索引快照读（indexItems），不得现场查 MediaStore ——" +
                    "「不要实时调用 android.process.media」就是这条",
                body.contains("indexItems(")
            )
        }
    }

    // ── 测试脚手架（同 app 侧的 DialogShellGuardTest / PtyEntryHiddenGuardTest） ──

    /** 剥掉注释，只留可执行代码。 */
    private fun executableCode(text: String): String =
        text.replace(Regex("""/\*[\s\S]*?\*/"""), "")
            .lines()
            .joinToString("\n") { it.substringBefore("//") }

    private fun count(code: String, needle: String): Int =
        Regex(Regex.escape(needle)).findAll(code).count()

    /** 从 `fun xxx(` 起截取配平花括号的方法体（含签名行）。 */
    private fun functionBody(code: String, signature: String): String {
        val start = code.indexOf(signature)
        if (start < 0) throw AssertionError("源码里找不到 `$signature`")
        val open = code.indexOf('{', start)
        if (open < 0) throw AssertionError("`$signature` 后面找不到 '{'")
        var depth = 0
        var i = open
        while (i < code.length) {
            when (code[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return code.substring(start, i + 1)
                }
            }
            i++
        }
        throw AssertionError("`$signature` 的花括号没配平")
    }

    /** 从测试工作目录逐级上溯定位源文件（Gradle 的 CWD 可能是模块目录也可能是仓库根）。 */
    private fun source(relative: String): String {
        var dir: File? = File(".").absoluteFile.normalize()
        while (dir != null) {
            for (candidate in listOf(File(dir, relative), File(dir, "../$relative"))) {
                if (candidate.isFile) return candidate.readText()
            }
            dir = dir.parentFile
        }
        throw AssertionError(
            "定位不到源文件 '$relative'（起点：${File(".").absolutePath}）。" +
                "若模块路径发生变化，请同步更新本测试中的相对路径。"
        )
    }

    private companion object {
        const val MediaRoutesPath =
            "core/api/src/main/java/com/ufi_axis_core/api/routes/MediaRoutes.kt"
        const val FfmpegServicePath =
            "core/api/src/main/java/com/ufi_axis_core/media/FfmpegThumbnailService.kt"
    }
}
