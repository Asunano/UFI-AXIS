package com.ufi_axis_core.controller.program

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [CloudreveConf] 的单测：只测纯字符串变换，不碰文件系统。
 *
 * 这里的每一条都对应一个真实的坏结局：
 * - 少补 `[Database] DBFile` → 库继续落在 `bin/data/`，配额统计形同虚设；
 * - 每次启动都重写 → 用户手工配置被反复拍平；
 * - `Listen` 不跟着清单走 → 健康检查探不到端口，程序被看护反复重启；
 * - 覆盖用户自己的 `DBFile` → 一打开是空网盘（"数据没了"）。
 */
class CloudreveConfTest {

    private val dbPath = "/data/data/com.ufi_axis_core/files/programs/cloudreve/cloudreve.db"

    /** Cloudreve 4.19.1 首次启动实际写出来的内容（2026-11-04 实测） */
    private val upstreamGenerated = """
        [System]
        Debug = false
        Mode = master
        Listen = :5212
        SessionSecret = VlelcUMebl1wt1U5D0ue7svC0WP1P7HOgz4ACit1eLKoRj2xLeXgKNtVosWifcmw
        HashIDSalt = {HashIDSalt}
    """.trimIndent()

    @Test
    fun `render 生成可用的最小配置`() {
        val text = CloudreveConf.render(":18005", "secret42", dbPath)
        assertTrue(text.contains("[System]"))
        assertTrue(text.contains("Listen = :18005"))
        assertTrue(text.contains("SessionSecret = secret42"))
        assertTrue(text.contains("[Database]"))
        assertTrue(text.contains("Type = sqlite3"))
        assertTrue(text.contains("DBFile = $dbPath"))
    }

    @Test
    fun `给上游生成的配置补上 Database 段且保留原有键`() {
        val r = CloudreveConf.patch(upstreamGenerated, ":18005", null, dbPath)

        assertTrue(r.changed)
        assertTrue(r.dbFileIntroduced)
        assertTrue(r.text.contains("DBFile = $dbPath"))
        assertTrue("SessionSecret 不能被改掉", r.text.contains("SessionSecret = VlelcUMebl1wt1U5D0ue7svC0WP1P7HOgz4ACit1eLKoRj2xLeXgKNtVosWifcmw"))
        assertTrue("未知键必须原样保留", r.text.contains("HashIDSalt = {HashIDSalt}"))
        assertTrue("Listen 必须对齐清单端口", r.text.contains("Listen = :18005"))
    }

    @Test
    fun `补过一次之后再 patch 必须没有任何变更`() {
        val once = CloudreveConf.patch(upstreamGenerated, ":18005", null, dbPath).text
        val twice = CloudreveConf.patch(once, ":18005", null, dbPath)

        assertFalse("第二遍还改文件 = 每次启动都在拍平用户配置", twice.changed)
        assertFalse(twice.dbFileIntroduced)
        assertEquals(once, twice.text)
    }

    @Test
    fun `用户自己指定的 DBFile 不被覆盖，只给提示`() {
        val custom = upstreamGenerated + "\n[Database]\nType = sqlite3\nDBFile = /sdcard/Android/data/foo/cloudreve.db\n"
        val r = CloudreveConf.patch(custom, ":5212", null, dbPath)

        assertFalse(r.dbFileIntroduced)
        assertTrue("不该改别人的库位置", r.text.contains("DBFile = /sdcard/Android/data/foo/cloudreve.db"))
        assertTrue("要给出提示", r.notes.any { it.contains("/sdcard/Android/data/foo/cloudreve.db") })
    }

    @Test
    fun `上游默认的相对 DBFile 视为尚未接管，改成绝对路径`() {
        val defaultRelative = upstreamGenerated + "\n[Database]\nType = sqlite3\nDBFile = data/cloudreve.db\n"
        val r = CloudreveConf.patch(defaultRelative, ":5212", null, dbPath)

        assertTrue(r.changed)
        assertTrue(r.dbFileIntroduced)
        assertTrue(r.text.contains("DBFile = $dbPath"))
        assertFalse(r.text.contains("DBFile = data/cloudreve.db"))
    }

    @Test
    fun `端口被改过时按清单重写`() {
        val r = CloudreveConf.patch(upstreamGenerated, ":18005", null, dbPath)
        assertTrue(r.text.contains("Listen = :18005"))
        assertFalse(r.text.contains("Listen = :5212"))
    }

    @Test
    fun `其它段与行尾注释都不动`() {
        val text = """
            [System]
            Listen = :5212  ; 手工注释
            Foo = bar

            [Log]
            Level = warn
        """.trimIndent()
        val r = CloudreveConf.patch(text, ":18005", null, dbPath)

        assertTrue(r.text.contains("[Log]"))
        assertTrue(r.text.contains("Level = warn"))
        assertTrue(r.text.contains("Foo = bar"))
        assertTrue("行尾注释要保留", r.text.contains("Listen = :18005  ; 手工注释"))
    }

    @Test
    fun `SessionSecret 缺失时按需补上`() {
        val bare = "[System]\nListen = :5212\n"
        val withSecret = CloudreveConf.patch(bare, ":5212", "abc123", dbPath)
        assertTrue(withSecret.text.contains("SessionSecret = abc123"))

        val without = CloudreveConf.patch(bare, ":5212", null, dbPath)
        assertFalse("调用方不给就不该凭空生成", without.text.contains("SessionSecret"))
    }

    @Test
    fun `补进去的键必须落在对应的段里`() {
        val text = "[System]\nListen = :5212\n\n[Other]\nA = 1\n"
        val r = CloudreveConf.patch(text, ":5212", null, dbPath)
        val lines = r.text.lines()

        val systemAt = lines.indexOf("[System]")
        val otherAt = lines.indexOf("[Other]")
        val dbAt = lines.indexOf("[Database]")
        val dbFileAt = lines.indexOfFirst { it.startsWith("DBFile") }

        assertTrue("Database 段必须在文件里", dbAt > 0)
        assertTrue("DBFile 必须落在 Database 段之后", dbFileAt > dbAt)
        assertFalse("不能把键塞进 Other 段", dbFileAt in (otherAt + 1) until dbAt)
        assertTrue(systemAt in 0 until otherAt)
    }
}
