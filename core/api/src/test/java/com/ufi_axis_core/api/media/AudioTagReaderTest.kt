package com.ufi_axis_core.api.media

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.Charset

/**
 * `AudioTagReader` 的**特征化测试**（2026-10-13，退役系统解码计划的 Phase 0）。
 *
 * ## 为什么先写它
 * 这个类是"退役系统解码"整条链路上**唯一没有测试**的组件，而它恰好又是最难改对的：
 * ID3v2 三个版本的帧头长度规则各不相同（v2.2 是 3 字符 ID + 3 字节大端长度，
 * v2.3 是 4 字节**大端**，v2.4 是 4 字节 **synchsafe**），FLAC 是块链。
 * 而 Phase 1 要往它里面加封面解析、还要把遍历改成按需 seek —— 没有回归网就是盲改。
 *
 * 本文件锁定的是**当前行为**（不是"理想行为"）：一旦 Phase 1 的重构改变了任何一条，
 * 这些用例会立刻失败，从而把"重构"和"改坏"区分开。
 *
 * ## 例外：两条用例是"修 bug"而不是"特征化"
 * 「声明了超出实际长度的帧长时不崩溃」与「FLAC 条目声明超大长度时不崩溃且保留已读到的标签」
 * 断言的是**修复后**的行为。写这两条时发现 `AudioTagReader` 对损坏文件不安全：
 * 帧长/条目长度都是从文件读的裸 32 位，`body + size` 在 Int 上**溢出成负数**，
 * 于是 `> end` 的边界检查恒为 false，紧接着就越界取值抛
 * `ArrayIndexOutOfBoundsException` / `StringIndexOutOfBoundsException`。
 * 而 `MediaRoutes.audioTagsOf` 调用它时没有 runCatching 兜底 ——
 * 音乐库里一个损坏（或故意构造）的文件就能把 `GET /api/media/tags` 打成 500。
 * 两处溢出已改为 Long 运算，`vorbisTags` 那个"读不出长度仍空转 21 亿次"的循环也一并收住。
 *
 * ## 为什么不用 Robolectric
 * `AudioTagReader` 只依赖 `java.io.File` / `java.nio` / `Charset`，不碰任何 Android API。
 * 纯 JVM 测试跑得快得多，也没有 shadow 行为带来的不确定性。
 *
 * ## 关于"文件名冒充曲名"
 * MediaStore 在没有标签时会把**文件名**填进 `TITLE`，那一步的甄别在 `MediaRoutes.realTitleOf`，
 * **不在本类**。所以这里的用例只断言"读到了文件里的字节"，不对文件名做任何假设。
 */
class AudioTagReaderTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ═══════════════════════════════════════════════════════════════════
    //  FLAC：fLaC 魔数 + METADATA_BLOCK 链
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `FLAC VORBIS_COMMENT 的 title artist album 被解析`() {
        val file = writeFlac(
            FLAC_BLOCK_VORBIS_COMMENT to vorbisComment(
                "TITLE=海阔天空", "ARTIST=Beyond", "ALBUM=乐与怒"
            )
        )
        val tags = AudioTagReader.read(file)
        assertEquals("海阔天空", tags.title)
        assertEquals("Beyond", tags.artist)
        assertEquals("乐与怒", tags.album)
    }

    @Test
    fun `FLAC 缺少 artist 时回退 albumartist 与 album artist`() {
        val a = AudioTagReader.read(
            writeFlac(
                FLAC_BLOCK_VORBIS_COMMENT to vorbisComment(
                    "TITLE=T", "ALBUMARTIST=Various Artists", "ALBUM=A"
                )
            )
        )
        assertEquals("Various Artists", a.artist)

        // 带空格的那种写法（有些转码工具只写这个）
        val b = AudioTagReader.read(
            writeFlac(
                FLAC_BLOCK_VORBIS_COMMENT to vorbisComment(
                    "TITLE=T", "ALBUM ARTIST=Some One", "ALBUM=A"
                )
            )
        )
        assertEquals("Some One", b.artist)
    }

    @Test
    fun `FLAC 键名大小写不敏感且只取第一次出现`() {
        val file = writeFlac(
            FLAC_BLOCK_VORBIS_COMMENT to vorbisComment(
                "title=小写键", "TITLE=第二次应被忽略"
            )
        )
        assertEquals("小写键", AudioTagReader.read(file).title)
    }

    @Test
    fun `FLAC 走完块链找到靠后的 VORBIS_COMMENT`() {
        // 前面垫一个 PADDING（type 1）块，确认遍历是"沿着链走"而不是"只看第一块"
        val file = writeFlac(
            FLAC_BLOCK_PADDING to ByteArray(16),
            FLAC_BLOCK_VORBIS_COMMENT to vorbisComment("TITLE=靠后的标签")
        )
        assertEquals("靠后的标签", AudioTagReader.read(file).title)
    }

    @Test
    fun `FLAC 没有 VORBIS_COMMENT 时回空标签`() {
        val file = writeFlac(FLAC_BLOCK_PADDING to ByteArray(8))
        assertTrue(AudioTagReader.read(file).isEmpty)
    }

    /**
     * 与 ID3 那条同源的**整数溢出**（2026-10-13 修复）：
     * Vorbis 条目的长度也是从文件读的裸 32 位，`pos + len` 在 Int 上会溢出成负数，
     * 于是 `> end` 的边界检查失效，紧接着 `String(bytes, pos, len)` 越界抛异常。
     *
     * 这条是**确定性**判据（不靠计时）：修复前必抛 StringIndexOutOfBoundsException，
     * 修复后应停在坏条目上、把前面已读到的标签保留下来。
     */
    @Test
    fun `FLAC 条目声明超大长度时不崩溃且保留已读到的标签`() {
        val body = ByteArrayOutputStream().apply {
            fun le32(v: Int) {
                write(v and 0xFF); write((v ushr 8) and 0xFF)
                write((v ushr 16) and 0xFF); write((v ushr 24) and 0xFF)
            }
            val vendor = "v".toByteArray(Charsets.UTF_8)
            le32(vendor.size); write(vendor)
            le32(2)                                    // 声明两条
            val first = "TITLE=好标签".toByteArray(Charsets.UTF_8)
            le32(first.size); write(first)
            le32(0x7FFFFFFF)                           // 第二条声明 2GB，实际一个字节都没有
        }.toByteArray()
        val file = writeFlac(FLAC_BLOCK_VORBIS_COMMENT to body)

        // 修复前：溢出让边界检查恒为 false → String(bytes, pos, 2GB) 越界
        assertEquals("好标签", AudioTagReader.read(file).title)
    }

    // ═══════════════════════════════════════════════════════════════════
    //  ID3v2：三个版本的帧头规则各不相同
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `ID3v2_3 用 4 字节大端帧长`() {
        val file = writeId3(
            3,
            id3Frame(3, "TIT2", id3Text(3, "标题三")),
            id3Frame(3, "TPE1", id3Text(3, "歌手三")),
            id3Frame(3, "TALB", id3Text(3, "专辑三"))
        )
        val tags = AudioTagReader.read(file)
        assertEquals("标题三", tags.title)
        assertEquals("歌手三", tags.artist)
        assertEquals("专辑三", tags.album)
    }

    @Test
    fun `ID3v2_4 用 synchsafe 帧长`() {
        // 帧长 > 127 才会让 synchsafe 与大端产生差异，所以刻意把文本做长
        val long = "长".repeat(100)   // UTF-8 下 300 字节，synchsafe 与大端在这里必然不同
        val file = writeId3(
            4,
            id3Frame(4, "TIT2", id3Text(3, long)),
            id3Frame(4, "TPE1", id3Text(3, "v24 歌手"))
        )
        val tags = AudioTagReader.read(file)
        assertEquals("v2.4 的 synchsafe 帧长被误按大端解析时会读错位置", long, tags.title)
        assertEquals("v24 歌手", tags.artist)
    }

    @Test
    fun `ID3v2_2 用 3 字符 ID 与 3 字节帧长`() {
        val file = writeId3(
            2,
            id3Frame(2, "TT2", id3Text(3, "标题二")),
            id3Frame(2, "TP1", id3Text(3, "歌手二")),
            id3Frame(2, "TAL", id3Text(3, "专辑二"))
        )
        val tags = AudioTagReader.read(file)
        assertEquals("标题二", tags.title)
        assertEquals("歌手二", tags.artist)
        assertEquals("专辑二", tags.album)
    }

    @Test
    fun `ID3v2 忽略非文本帧并继续往后读`() {
        // APIC（封面）夹在中间：遍历必须跳过它而不是中断
        val file = writeId3(
            3,
            id3Frame(3, "APIC", ByteArray(64)),
            id3Frame(3, "TIT2", id3Text(3, "跳过 APIC 之后"))
        )
        assertEquals("跳过 APIC 之后", AudioTagReader.read(file).title)
    }

    @Test
    fun `ID3v2 部分字段缺失时只回已读到的字段`() {
        val file = writeId3(3, id3Frame(3, "TIT2", id3Text(3, "只有标题")))
        val tags = AudioTagReader.read(file)
        assertEquals("只有标题", tags.title)
        assertEquals("", tags.artist)
        assertEquals("", tags.album)
    }

    // ═══════════════════════════════════════════════════════════════════
    //  文本编码：0=Latin-1(实为 GBK/UTF-8 混用) 1=UTF-16BOM 2=UTF-16BE 3=UTF-8
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `编码标志 3 走 UTF-8`() {
        val file = writeId3(3, id3Frame(3, "TIT2", id3Text(3, "中文标题")))
        assertEquals("中文标题", AudioTagReader.read(file).title)
    }

    @Test
    fun `编码标志 1 走带 BOM 的 UTF-16LE`() {
        val file = writeId3(3, id3Frame(3, "TIT2", id3Text(1, "BOM 标题")))
        assertEquals("BOM 标题", AudioTagReader.read(file).title)
    }

    @Test
    fun `编码标志 2 走 UTF-16BE`() {
        val file = writeId3(3, id3Frame(3, "TIT2", id3Text(2, "大端标题")))
        assertEquals("大端标题", AudioTagReader.read(file).title)
    }

    /**
     * 这一条是**最容易被后人"修坏"**的：规范说编码 0 是 ISO-8859-1，
     * 但国产打标工具普遍在里面塞 GBK。`sniffLegacy` 的 UTF-8 → GBK → Latin-1
     * 三级嗅探就是为它存在的，必须锁住。
     */
    @Test
    fun `编码标志 0 声称 Latin-1 实为 GBK 时被嗅探出来`() {
        val gbk = "中文".toByteArray(Charset.forName("GBK"))
        // 先确认这串字节**不是**合法 UTF-8，否则这个用例证明不了嗅探（会走 UTF-8 分支）
        val utf8Decodable = runCatching {
            Charset.forName("UTF-8").newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(gbk)).toString()
        }.isSuccess
        assertTrue("fixture 选得不对：这串 GBK 字节恰好是合法 UTF-8，用例失去意义", !utf8Decodable)

        val file = writeId3(3, id3Frame(3, "TIT2", id3Text(0, String(gbk, Charsets.ISO_8859_1))))
        assertEquals("中文", AudioTagReader.read(file).title)
    }

    @Test
    fun `纯 ASCII 的编码 0 不被嗅探影响`() {
        val file = writeId3(3, id3Frame(3, "TIT2", id3Text(0, "Plain ASCII")))
        assertEquals("Plain ASCII", AudioTagReader.read(file).title)
    }

    @Test
    fun `文本帧尾部的 NUL 填充被削掉`() {
        val body = id3Text(3, "带填充") + ByteArray(4)   // 尾部补 NUL
        val file = writeId3(3, id3Frame(3, "TIT2", body))
        assertEquals("带填充", AudioTagReader.read(file).title)
    }

    // ═══════════════════════════════════════════════════════════════════
    //  异常与边界：绝不能抛
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `不是音频文件时回空标签`() {
        val file = tmp.newFile("random.bin").apply { writeBytes(ByteArray(256) { 0x41 }) }
        assertTrue(AudioTagReader.read(file).isEmpty)
    }

    @Test
    fun `空文件回空标签`() {
        val file = tmp.newFile("empty.mp3")
        assertTrue(AudioTagReader.read(file).isEmpty)
    }

    @Test
    fun `不存在的文件回空标签`() {
        assertTrue(AudioTagReader.read(File(tmp.root, "nope.mp3")).isEmpty)
    }

    @Test
    fun `截断的 ID3 头不崩溃`() {
        val file = tmp.newFile("trunc.mp3").apply {
            // "ID3" + 只给 3 个字节（头部要 10 字节）
            writeBytes(byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), 3, 0, 0))
        }
        assertTrue(AudioTagReader.read(file).isEmpty)
    }

    @Test
    fun `声明了超出实际长度的帧长时不崩溃`() {
        val out = ByteArrayOutputStream()
        out.write("ID3".toByteArray(Charsets.ISO_8859_1))
        out.write(3); out.write(0); out.write(0)
        // tag 体声明 200 字节
        out.write(0); out.write(0); out.write(1); out.write(72)
        // 一个帧头：ID 合法、但声明的帧长是 0x7FFFFFFF（远超文件）
        out.write("TIT2".toByteArray(Charsets.ISO_8859_1))
        // write(int) 只取低 8 位，所以这里直接给 Int 字面量（给 Byte 会没有匹配的重载）
        out.write(0x7F); out.write(0xFF); out.write(0xFF); out.write(0xFF)
        out.write(0); out.write(0)   // flags
        val bytes = out.toByteArray()
        // 前置条件：必须真的能进到帧遍历循环里（否则这条用例什么都没验证）
        assertTrue("fixture 太短，进不了帧循环", bytes.size > 16)

        val file = tmp.newFile("lying.mp3").apply { writeBytes(bytes) }
        assertTrue(AudioTagReader.read(file).isEmpty)
    }

    @Test
    fun `截断的 FLAC 块不崩溃`() {
        val file = tmp.newFile("trunc.flac").apply {
            // 块头声明 999 字节，实际只有 4 字节
            writeBytes(
                byteArrayOf(
                    'f'.code.toByte(), 'L'.code.toByte(), 'a'.code.toByte(), 'C'.code.toByte(),
                    0x84.toByte(), 0x00, 0x03, 0xE7.toByte(),
                    0, 0, 0, 0
                )
            )
        }
        assertTrue(AudioTagReader.read(file).isEmpty)
    }

    // ═══════════════════════════════════════════════════════════════════
    //  内嵌封面：ID3v2 APIC / PIC
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `ID3v2_3 APIC 读出 JPEG 封面`() {
        val art = jpeg(600)
        val file = writeId3(
            3,
            id3Frame(3, "TIT2", id3Text(3, "带封面的歌")),
            id3Frame(3, "APIC", apicBody(3, "image/jpeg", 3, "", art))
        )
        val cover = AudioTagReader.readCover(file)
        assertEquals("image/jpeg", cover?.mime)
        assertArrayEquals(art, cover?.bytes)
        // 同一次写入的文本标签也要照常读得到（两条路径共用一个文件）
        assertEquals("带封面的歌", AudioTagReader.read(file).title)
    }

    @Test
    fun `ID3v2_3 APIC 读出 PNG 封面且类型以魔数为准`() {
        val art = png(700)
        // 故意把声明的 MIME 写错成 jpeg —— 真实文件里很常见，必须以魔数纠正
        val file = writeId3(3, id3Frame(3, "APIC", apicBody(3, "image/jpeg", 3, "", art)))
        val cover = AudioTagReader.readCover(file)
        assertEquals("声明值写错时必须以魔数纠正", "image/png", cover?.mime)
        assertArrayEquals(art, cover?.bytes)
    }

    @Test
    fun `ID3v2_4 的 synchsafe 帧长下 APIC 也能读出`() {
        // 封面刻意做大到 > 127 字节，让 synchsafe 与大端产生差异
        val art = jpeg(600)
        val file = writeId3(
            4,
            id3Frame(4, "TIT2", id3Text(3, "v24")),
            id3Frame(4, "APIC", apicBody(3, "image/jpeg", 3, "", art))
        )
        assertArrayEquals(art, AudioTagReader.readCover(file)?.bytes)
    }

    @Test
    fun `ID3v2_2 的 PIC 帧（3 字符格式）也能读出`() {
        val art = png(600)
        val file = writeId3(2, id3Frame(2, "PIC", apicBody(3, "image/png", 3, "", art, v22 = true)))
        val cover = AudioTagReader.readCover(file)
        assertEquals("image/png", cover?.mime)
        assertArrayEquals(art, cover?.bytes)
    }

    @Test
    fun `多张 APIC 时优先 front cover`() {
        val back = jpeg(600)     // 600 字节
        val front = jpeg(900)    // 900 字节，用大小区分是哪一张
        val file = writeId3(
            3,
            id3Frame(3, "APIC", apicBody(3, "image/jpeg", 4, "back", back)),
            id3Frame(3, "APIC", apicBody(3, "image/jpeg", 3, "front", front))
        )
        assertArrayEquals("pictureType==3 是 front cover，必须优先", front, AudioTagReader.readCover(file)?.bytes)
    }

    @Test
    fun `没有 front cover 时退回第一张能解的图`() {
        val only = jpeg(640)
        val file = writeId3(3, id3Frame(3, "APIC", apicBody(3, "image/jpeg", 4, "back", only)))
        assertArrayEquals(only, AudioTagReader.readCover(file)?.bytes)
    }

    /**
     * APIC 里最容易写错的一处：encoding 1/2 的 description 终止符是**两字节的 0x0000**，
     * 且要按 2 字节步进。按 1 字节扫的话，UTF-16 编码里满地的 `0x00` 会让它提前停在
     * 半个字符上，之后整个偏移全错 —— 表现是魔数校验不过、封面"莫名读不出来"。
     *
     * 这条用例的描述刻意非空，否则空描述（`00 00`）两种实现都对，测不出差异。
     */
    @Test
    fun `APIC 的 UTF-16 描述按 2 字节步进跳过`() {
        val art = jpeg(600)
        val file = writeId3(
            3,
            id3Frame(3, "APIC", apicBody(1, "image/jpeg", 3, "cover art", art))
        )
        assertArrayEquals(
            "UTF-16 描述必须按 2 字节步进找 0x0000，否则偏移会落在半个字符上",
            art, AudioTagReader.readCover(file)?.bytes
        )
    }

    @Test
    fun `APIC 的 UTF-8 描述也能跳过`() {
        val art = png(600)
        val file = writeId3(
            3,
            id3Frame(3, "APIC", apicBody(3, "image/png", 3, "中文描述", art))
        )
        assertArrayEquals(art, AudioTagReader.readCover(file)?.bytes)
    }

    // ═══════════════════════════════════════════════════════════════════
    //  内嵌封面：FLAC PICTURE
    // ═══════════════════════════════════════════════════════════════════

    /**
     * 这条同时钉住一个**真实存在的坑**：`VORBIS_COMMENT`(4) 通常排在 `PICTURE`(6) **前面**。
     * 文本路径 `readFlac` 是"找到 VORBIS_COMMENT 就 return"，封面路径**绝不能**照抄 ——
     * 照抄的话封面永远读不到。这里把两个块按真实顺序放，并同时断言文本与封面都能拿到。
     */
    @Test
    fun `FLAC 封面排在 VORBIS_COMMENT 之后仍能读到`() {
        val art = jpeg(600)
        val file = writeFlac(
            FLAC_BLOCK_VORBIS_COMMENT to vorbisComment("TITLE=带封面的无损"),
            FLAC_BLOCK_PICTURE to flacPictureBody(3, "image/jpeg", "cover", art)
        )
        assertEquals("带封面的无损", AudioTagReader.read(file).title)
        val cover = AudioTagReader.readCover(file)
        assertEquals("image/jpeg", cover?.mime)
        assertArrayEquals(art, cover?.bytes)
    }

    @Test
    fun `FLAC 多张 PICTURE 时优先 front cover`() {
        val back = png(600)
        val front = png(900)
        val file = writeFlac(
            FLAC_BLOCK_PICTURE to flacPictureBody(4, "image/png", "back", back),
            FLAC_BLOCK_PICTURE to flacPictureBody(3, "image/png", "front", front)
        )
        assertArrayEquals(front, AudioTagReader.readCover(file)?.bytes)
    }

    @Test
    fun `FLAC PICTURE 的宽高色深字段被正确跳过`() {
        val art = jpeg(700)
        val file = writeFlac(FLAC_BLOCK_PICTURE to flacPictureBody(3, "image/jpeg", "", art, width = 1920, height = 1080))
        assertArrayEquals(art, AudioTagReader.readCover(file)?.bytes)
    }

    // ═══════════════════════════════════════════════════════════════════
    //  封面：拒绝路径（这些决定"会不会被一个畸形文件顶爆内存"）
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `没有封面帧时回 null`() {
        val mp3 = writeId3(3, id3Frame(3, "TIT2", id3Text(3, "只有文本")))
        assertEquals(null, AudioTagReader.readCover(mp3))
        val flac = writeFlac(FLAC_BLOCK_VORBIS_COMMENT to vorbisComment("TITLE=只有文本"))
        assertEquals(null, AudioTagReader.readCover(flac))
    }

    @Test
    fun `过小的封面被拒（不是图）`() {
        val file = writeId3(3, id3Frame(3, "APIC", apicBody(3, "image/jpeg", 3, "", jpeg(300))))
        assertEquals("300 字节低于下限，应被拒", null, AudioTagReader.readCover(file))
    }

    @Test
    fun `魔数不是 JPEG 或 PNG 时被拒`() {
        // GIF 头 + 足够长度：长度过关、魔数不过关
        val gif = byteArrayOf('G'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte(), '8'.code.toByte()) +
            ByteArray(600)
        val file = writeId3(3, id3Frame(3, "APIC", apicBody(3, "image/gif", 3, "", gif)))
        assertEquals(null, AudioTagReader.readCover(file))
    }

    /**
     * 声明超大封面时必须**不读、不分配**就跳过 —— 否则一个声称自己有 1GB 封面的构造文件
     * 就能把这台 1.5GB 的设备顶爆。判据是"能正常返回 null 而不是 OOM / 卡住"。
     */
    @Test
    fun `声明超大封面时不分配内存直接放弃`() {
        // FLAC PICTURE 块体本身很小（只有几十字节元数据），但 dataLen 字段声称 1GB。
        // 块体长度（= 元数据 + 实际字节）在块头里是真实的，所以 readFlacCover 的
        // "块体长度 <= 上限" 那一关能过；拦住它的是 parseFlacPicture 里的 dataLen 上限。
        val pic = ByteArrayOutputStream().apply {
            fun be32(v: Int) {
                write((v ushr 24) and 0xFF); write((v ushr 16) and 0xFF)
                write((v ushr 8) and 0xFF); write(v and 0xFF)
            }
            be32(3)                                          // pictureType
            val mime = "image/jpeg".toByteArray(Charsets.ISO_8859_1)
            be32(mime.size); write(mime)
            be32(0)                                          // 空描述
            be32(0); be32(0); be32(0); be32(0)               // 宽高色深颜色数
            be32(1 shl 30)                                   // 声明 1GB 图像数据
            write(ByteArray(64))                             // 实际只给 64 字节
        }.toByteArray()
        val file = writeFlac(FLAC_BLOCK_PICTURE to pic)

        assertEquals("声明长度超上限时应直接放弃，不得按声明值分配", null, AudioTagReader.readCover(file))
    }

    @Test
    fun `不是音频文件时回 null`() {
        val file = tmp.newFile("random2.bin").apply { writeBytes(ByteArray(1024) { 0x41 }) }
        assertEquals(null, AudioTagReader.readCover(file))
    }

    @Test
    fun `封面路径对截断文件不崩溃`() {
        // ID3 头声明 tag 很长，但文件在第一个帧头中间就断了
        val out = ByteArrayOutputStream()
        out.write("ID3".toByteArray(Charsets.ISO_8859_1))
        out.write(3); out.write(0); out.write(0)
        out.write(0); out.write(0); out.write(2); out.write(0)   // 声明 256 字节
        out.write("APIC".toByteArray(Charsets.ISO_8859_1))       // 只给 4 字节，帧头要 10
        val file = tmp.newFile("trunccover.mp3").apply { writeBytes(out.toByteArray()) }
        assertEquals(null, AudioTagReader.readCover(file))
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Fixture 构造器（封面部分）
    // ═══════════════════════════════════════════════════════════════════

    /** 一张"看起来像 JPEG"的图：魔数正确 + 够长（下限 512 字节）。 */
    private fun jpeg(size: Int = 600): ByteArray =
        byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) +
            ByteArray(size - 4) { (it % 251).toByte() }

    /** 一张"看起来像 PNG"的图。 */
    private fun png(size: Int = 600): ByteArray =
        byteArrayOf(
            0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(),
            0x0D, 0x0A, 0x1A, 0x0A
        ) + ByteArray(size - 8) { (it % 251).toByte() }

    /**
     * ID3v2 `APIC` / `PIC` 的帧体：
     * `encoding(1) + MIME + pictureType(1) + description + NUL 终止 + 图像数据`。
     *
     * @param v22 true 时按 v2.2 的 `PIC` 写：MIME 位置是 3 字符的图像格式（"JPG"/"PNG"）。
     */
    private fun apicBody(
        encoding: Int,
        mime: String,
        pictureType: Int,
        description: String,
        image: ByteArray,
        v22: Boolean = false
    ): ByteArray = ByteArrayOutputStream().apply {
        write(encoding)
        if (v22) {
            // v2.2 的 PIC 用 3 字符图像格式，不是 MIME 串
            write((if (mime.contains("png")) "PNG" else "JPG").toByteArray(Charsets.ISO_8859_1))
        } else {
            write(mime.toByteArray(Charsets.ISO_8859_1)); write(0)
        }
        write(pictureType)
        when (encoding) {
            1 -> {
                write(0xFF); write(0xFE)                       // UTF-16LE BOM
                write(description.toByteArray(Charsets.UTF_16LE))
            }
            2 -> write(description.toByteArray(Charsets.UTF_16BE))
            3 -> write(description.toByteArray(Charsets.UTF_8))
            else -> write(description.toByteArray(Charsets.ISO_8859_1))
        }
        // 描述终止符：UTF-16 系是两字节的 0x0000，其余是单字节 0x00
        if (encoding == 1 || encoding == 2) { write(0); write(0) } else write(0)
        write(image)
    }.toByteArray()

    /** FLAC `PICTURE` 块体（全部大端）。 */
    private fun flacPictureBody(
        pictureType: Int,
        mime: String,
        description: String,
        image: ByteArray,
        width: Int = 0,
        height: Int = 0
    ): ByteArray = ByteArrayOutputStream().apply {
        fun be32(v: Int) {
            write((v ushr 24) and 0xFF); write((v ushr 16) and 0xFF)
            write((v ushr 8) and 0xFF); write(v and 0xFF)
        }
        be32(pictureType)
        val mimeBytes = mime.toByteArray(Charsets.ISO_8859_1)
        be32(mimeBytes.size); write(mimeBytes)
        val descBytes = description.toByteArray(Charsets.UTF_8)
        be32(descBytes.size); write(descBytes)
        be32(width); be32(height); be32(24); be32(0)          // 宽 / 高 / 色深 / 颜色数
        be32(image.size); write(image)
    }.toByteArray()

    // ═══════════════════════════════════════════════════════════════════
    //  Fixture 构造器（容器部分）
    // ═══════════════════════════════════════════════════════════════════

    private fun writeFlac(vararg blocks: Pair<Int, ByteArray>): File {        val out = ByteArrayOutputStream()
        out.write("fLaC".toByteArray(Charsets.ISO_8859_1))
        blocks.forEachIndexed { i, (type, body) ->
            val isLast = i == blocks.lastIndex
            out.write(if (isLast) 0x80 or type else type)
            out.write((body.size ushr 16) and 0xFF)
            out.write((body.size ushr 8) and 0xFF)
            out.write(body.size and 0xFF)
            out.write(body)
        }
        return tmp.newFile("t${counter++}.flac").apply { writeBytes(out.toByteArray()) }
    }

    /** Vorbis Comment 体：vendor + 条目数 + 逐条 `KEY=value`，长度全是**小端** 32bit。 */
    private fun vorbisComment(vararg entries: String, vendor: String = "ufi-test"): ByteArray {
        val out = ByteArrayOutputStream()
        fun le32(v: Int) {
            out.write(v and 0xFF); out.write((v ushr 8) and 0xFF)
            out.write((v ushr 16) and 0xFF); out.write((v ushr 24) and 0xFF)
        }
        val vendorBytes = vendor.toByteArray(Charsets.UTF_8)
        le32(vendorBytes.size); out.write(vendorBytes)
        le32(entries.size)
        entries.forEach {
            val b = it.toByteArray(Charsets.UTF_8)
            le32(b.size); out.write(b)
        }
        return out.toByteArray()
    }

    private fun writeId3(major: Int, vararg frames: ByteArray): File {
        val body = ByteArrayOutputStream().apply { frames.forEach { write(it) } }.toByteArray()
        val out = ByteArrayOutputStream()
        out.write("ID3".toByteArray(Charsets.ISO_8859_1))
        out.write(major); out.write(0); out.write(0)
        // tag 体长度：synchsafe（每字节只用低 7 位）
        out.write((body.size ushr 21) and 0x7F)
        out.write((body.size ushr 14) and 0x7F)
        out.write((body.size ushr 7) and 0x7F)
        out.write(body.size and 0x7F)
        out.write(body)
        return tmp.newFile("t${counter++}.mp3").apply { writeBytes(out.toByteArray()) }
    }

    /** 一个 ID3v2 帧：v2.2 = 3 字符 ID + 3 字节大端长；v2.3 = 4 + 4 大端 + 2 flag；v2.4 = 4 + 4 synchsafe + 2 flag。 */
    private fun id3Frame(major: Int, id: String, body: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(id.toByteArray(Charsets.ISO_8859_1))
        if (major <= 2) {
            out.write((body.size ushr 16) and 0xFF)
            out.write((body.size ushr 8) and 0xFF)
            out.write(body.size and 0xFF)
        } else {
            if (major == 3) {
                out.write((body.size ushr 24) and 0xFF); out.write((body.size ushr 16) and 0xFF)
                out.write((body.size ushr 8) and 0xFF); out.write(body.size and 0xFF)
            } else {
                out.write((body.size ushr 21) and 0x7F); out.write((body.size ushr 14) and 0x7F)
                out.write((body.size ushr 7) and 0x7F); out.write(body.size and 0x7F)
            }
            out.write(0); out.write(0)   // flags
        }
        out.write(body)
        return out.toByteArray()
    }

    /** 文本帧体：1 字节编码标志 + 内容。 */
    private fun id3Text(encoding: Int, text: String): ByteArray {
        val payload = when (encoding) {
            1 -> byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + text.toByteArray(Charsets.UTF_16LE)
            2 -> text.toByteArray(Charsets.UTF_16BE)
            3 -> text.toByteArray(Charsets.UTF_8)
            else -> text.toByteArray(Charsets.ISO_8859_1)
        }
        return ByteArrayOutputStream().apply { write(encoding); write(payload) }.toByteArray()
    }

    private companion object {
        /** FLAC 元数据块类型。 */
        const val FLAC_BLOCK_PADDING = 1
        const val FLAC_BLOCK_VORBIS_COMMENT = 4
        const val FLAC_BLOCK_PICTURE = 6

        var counter = 0
    }
}
