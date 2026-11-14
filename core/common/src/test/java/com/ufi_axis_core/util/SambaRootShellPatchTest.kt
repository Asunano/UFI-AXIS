package com.ufi_axis_core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SambaRootShell 的 smb.conf 处理逻辑（2026-11-05 重写时补）。
 *
 * 这两条纯函数是这次修复的核心，出错的后果都很重：
 *
 * - [SambaRootShell.patchConf] 写坏了 → smbd 读不了配置，**用户的 SMB 共享直接挂掉**；
 * - 旧行没清干净 → preexec 指向不存在的脚本 → smbd 判定 `root preexec gave %d - failing
 *   connection` → **每次连接都失败**；
 * - [SambaRootShell.sharesOf] 解析错 → 触发不到 TREE_CONNECT → root 通道永远起不来
 *   （这正是真机上"共享名叫 F50、代码却硬编码 internal_storage"那个 bug）。
 */
class SambaRootShellPatchTest {

    /** 真机 F50 固件的原始配置（2026-11-05 抓取，308 字节）。 */
    private val realConf = """
        [global]
        	workgroup = SAMBA
        	netbios name = Android
        	server string = Android Samber Server
        	security = user
        	passdb backend = smbpasswd:/data/samba/etc/smbpasswd
        	map to guest = bad user

        [F50]
        	comment = Android Server
        	path = /mnt/sdcard/DCIM
        	browseable = yes
        	writable = yes
        	public = yes
        	guest ok = yes
    """.trimIndent()

    @Test
    fun `注入到真实共享段而不是 global`() {
        val out = SambaRootShell.patchConf(realConf, "/data/data/x/files/root_shell/samba_exec.sh")

        val lines = out.split("\n")
        val f50 = lines.indexOfFirst { it.trim() == "[F50]" }
        val preexec = lines.indexOfFirst { it.trim().startsWith("root preexec") }

        assertTrue("必须注入了 preexec", preexec >= 0)
        assertEquals("preexec 必须紧跟在 [F50] 之后", f50 + 1, preexec)
        assertTrue(
            "pre-exec 的路径必须指向新脚本",
            lines[preexec].contains("/data/data/x/files/root_shell/samba_exec.sh")
        )
        assertEquals("不能动原有配置内容", realConf, out.replace(lines[preexec] + "\n", ""))
    }

    @Test
    fun `清理旧安装留下的 preexec 行`() {
        // 旧实现把脚本放在 /data/local/tmp/ufi_axis/。重装后那个文件已经不存在，
        // 留着会让每次 SMB 连接都执行失败。
        val stale = realConf.replace(
            "[F50]",
            "[F50]\n   root preexec = /system/bin/sh /data/local/tmp/ufi_axis/samba_exec.sh"
        )

        val out = SambaRootShell.patchConf(stale, "/data/data/x/files/root_shell/samba_exec.sh")

        assertEquals(
            "旧行必须被清掉，只留一条新的",
            1,
            out.split("\n").count { it.trim().startsWith("root preexec") }
        )
        assertFalse(out.contains("/data/local/tmp/ufi_axis"))
    }

    @Test
    fun `幂等：重复注入得到同一份内容`() {
        val once = SambaRootShell.patchConf(realConf, "/p/samba_exec.sh")
        val twice = SambaRootShell.patchConf(once, "/p/samba_exec.sh")
        assertEquals("第二次必须无变化，否则每次开机都在重写系统配置", once, twice)
    }

    @Test
    fun `只有 global 时退回 global 段`() {
        val conf = "[global]\n\tworkgroup = SAMBA\n"
        val out = SambaRootShell.patchConf(conf, "/p/samba_exec.sh")
        val lines = out.split("\n")

        assertEquals("[global]", lines[0].trim())
        assertTrue("global 下也要能注入", lines[1].trim().startsWith("root preexec"))
    }

    @Test
    fun `空配置也能生成可用结构`() {
        val out = SambaRootShell.patchConf("", "/p/samba_exec.sh")
        assertTrue(out.contains("[global]"))
        assertTrue(out.contains("root preexec"))
    }

    @Test
    fun `解析共享名：排除 global 与系统段`() {
        val conf = """
            [global]
            [F50]
            [homes]
            [printers]
            [IPC${'$'}]
            [Media]
        """.trimIndent()

        assertEquals(listOf("F50", "Media"), SambaRootShell.sharesOf(conf))
    }

    @Test
    fun `真实配置解析出 F50`() {
        // 旧实现硬编码 internal_storage/share/sdcard —— 正是这条导致了真机永远触发不到
        assertEquals(listOf("F50"), SambaRootShell.sharesOf(realConf))
    }

    @Test
    fun `段名前后空格与大小写都不影响`() {
        val conf = "[ F50 ]\n[GLOBAL]\n[MEDIA]\n"
        assertEquals(listOf("F50", "MEDIA"), SambaRootShell.sharesOf(conf))
    }
}
