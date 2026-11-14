package com.ufi_axis_core.controller.program

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

/**
 * [CloudreveAdapter] 的 preflight 单测（真文件系统 + 真软链）。
 *
 * 这里测的不是"格式对不对"（那是 [CloudreveConfTest]），而是**落点对不对**：
 * conf 在 `{CONF}`、库在 `{DATA}`、`bin/data` 是指向 `{DATA}/data` 的软链。
 * 这三条任何一条不成立，用户看到的就是"库/上传在二进制目录里"或"备份出来是空站点"，
 * 而且都是静默的 —— 没有报错，只有事后才发现。
 */
class CloudreveAdapterTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val adapter = CloudreveAdapter()

    private fun context(internalPort: Int = 5212, accessMode: String = "direct"): ProgramContext {
        val root = tmp.newFolder("cloudreve")
        val binDir = File(root, "bin").apply { mkdirs() }
        val dataDir = File(root, "data").apply { mkdirs() }
        val confDir = File(root, "conf").apply { mkdirs() }
        val runDir = File(root, "run").apply { mkdirs() }
        // 内容目录故意放在 root 之外，模拟"内容在 SD 卡"：软链必须能跨目录指过去
        val contentDir = tmp.newFolder("sdcard-content", "cloudreve")
        return ProgramContext(
            id = "cloudreve",
            binPath = File(binDir, "cloudreve"),
            dataDir = dataDir,
            confDir = confDir,
            runDir = runDir,
            contentDir = contentDir,
            internalPort = internalPort,
            accessMode = accessMode
        )
    }

    @Test
    fun `首次 preflight 把 conf 放进 CONF、库放进 DATA、并建好 data 软链`() {
        val ctx = context()
        adapter.preflight(ctx).getOrThrow()

        val conf = File(ctx.confDir, "conf.ini")
        assertTrue("conf.ini 必须落在 {CONF}", conf.exists())
        val text = conf.readText()
        assertTrue(text.contains("Listen = :5212"))
        assertTrue("库必须是 {DATA} 下的绝对路径", text.contains("DBFile = ${File(ctx.dataDir, "cloudreve.db").absolutePath}"))

        assertFalse("conf 不该再落在 {DATA}", File(ctx.dataDir, "conf.ini").exists())

        val link = File(ctx.binPath.parentFile, "data")
        assertTrue("bin/data 必须是软链", Files.isSymbolicLink(link.toPath()))
        assertEquals(
            File(ctx.dataDir, "data").canonicalPath,
            Files.readSymbolicLink(link.toPath()).toFile().canonicalPath
        )
    }

    @Test
    fun `gateway 模式只监听回环，避免绕过 Gateway`() {
        val ctx = context(accessMode = "gateway")
        adapter.preflight(ctx).getOrThrow()

        val text = File(ctx.confDir, "conf.ini").readText()
        assertTrue("gateway 必须是 127.0.0.1:5212：$text", text.contains("Listen = 127.0.0.1:5212"))
    }

    @Test
    fun `上传目录被指到内容目录（内容可与数据分开存 SD 卡）`() {
        val ctx = context()
        adapter.preflight(ctx).getOrThrow()

        val uploads = File(ctx.dataDir, "data/uploads")
        assertTrue("uploads 必须是软链", Files.isSymbolicLink(uploads.toPath()))
        assertEquals(
            File(ctx.contentDir, "").canonicalPath,
            Files.readSymbolicLink(uploads.toPath()).toFile().canonicalPath
        )
        assertTrue("内容目录必须被建出来", ctx.contentDir.isDirectory)
    }

    @Test
    fun `重复 preflight 不产生任何变更`() {
        val ctx = context()
        adapter.preflight(ctx).getOrThrow()
        val conf = File(ctx.confDir, "conf.ini")
        val before = conf.readText()
        val beforeStamp = conf.lastModified()

        Thread.sleep(10)
        adapter.preflight(ctx).getOrThrow()

        assertEquals("第二遍必须 no-op", before, conf.readText())
        assertEquals("不该重写文件", beforeStamp, conf.lastModified())
        assertTrue(Files.isSymbolicLink(File(ctx.binPath.parentFile, "data").toPath()))
    }

    @Test
    fun `老版本放在 DATA 的 conf 会被迁到 CONF 且不换 SessionSecret`() {
        val ctx = context()
        val legacy = File(ctx.dataDir, "conf.ini")
        legacy.writeText(
            """
            [System]
            Listen = :5212
            SessionSecret = KEEPME

            [Database]
            Type = sqlite3
            DBFile = data/cloudreve.db
            """.trimIndent()
        )

        adapter.preflight(ctx).getOrThrow()

        val conf = File(ctx.confDir, "conf.ini")
        assertTrue(conf.exists())
        assertFalse("旧文件必须消失，否则两份配置互相打架", legacy.exists())
        val text = conf.readText()
        assertTrue("SessionSecret 不能被换掉（换了所有登录态失效）", text.contains("SessionSecret = KEEPME"))
        assertTrue(text.contains("DBFile = ${File(ctx.dataDir, "cloudreve.db").absolutePath}"))
    }

    @Test
    fun `老的 bin-data 真实目录里的内容会先并进 DATA 再换成软链`() {
        val ctx = context()
        val legacyDir = File(ctx.binPath.parentFile, "data").apply { mkdirs() }
        val uploaded = File(legacyDir, "uploads/1/photo.jpg").apply {
            parentFile.mkdirs()
            writeText("payload")
        }

        adapter.preflight(ctx).getOrThrow()

        val link = File(ctx.binPath.parentFile, "data")
        assertTrue("必须变成软链", Files.isSymbolicLink(link.toPath()))
        val migrated = File(ctx.dataDir, "data/uploads/1/photo.jpg")
        assertTrue("老数据必须被搬过去，不能随目录一起删掉", migrated.exists())
        assertEquals("payload", migrated.readText())
        // 注意：不能再断言 uploaded.exists()==false —— bin/data 现在是指向 {DATA}/data 的软链，
        // 老路径会解析到同一个文件，存在才是对的。这里要证的是"只有一份"：
        assertEquals(
            "老目录与软链必须指向同一份数据",
            migrated.canonicalPath,
            uploaded.canonicalPath
        )
    }
}
