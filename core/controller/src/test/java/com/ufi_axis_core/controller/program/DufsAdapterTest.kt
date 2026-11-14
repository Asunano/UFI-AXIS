package com.ufi_axis_core.controller.program

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * [DufsAdapter]：口令生成、幂等、重置。
 *
 * 这里最要紧的一条是**口令必须稳定**：适配器每次启动都会跑 preflight，
 * 如果每次重新生成，用户每次重启 core 就被踢下线 —— 而这种 bug 只会被当成"dufs 老是登不上"。
 */
class DufsAdapterTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val adapter = DufsAdapter()

    private fun context(internalPort: Int = 18110, accessMode: String = "direct"): ProgramContext {
        val root = tmp.newFolder()
        val binDir = File(root, "bin").apply { mkdirs() }
        val dataDir = File(root, "data").apply { mkdirs() }
        val confDir = File(root, "conf").apply { mkdirs() }
        val runDir = File(root, "run").apply { mkdirs() }
        return ProgramContext(
            id = "dufs",
            binPath = File(binDir, "dufs"),
            dataDir = dataDir,
            confDir = confDir,
            runDir = runDir,
            contentDir = tmp.newFolder(),
            contentEnabled = true,
            internalPort = internalPort,
            accessMode = accessMode
        )
    }

    @Test
    fun `首次 preflight 生成带口令的配置并把服务目录钉在 CONTENT`() {
        val ctx = context()
        adapter.preflight(ctx).getOrThrow()

        val conf = File(ctx.confDir, "dufs.yaml")
        assertTrue(conf.exists())
        val text = conf.readText()
        assertTrue("服务目录必须是 {CONTENT}", text.contains("serve-path: '${ctx.contentDir.absolutePath}'"))
        assertTrue("端口必须跟清单一致", text.contains("port: 18110"))
        assertTrue("默认允许上传", text.contains("allow-upload: true"))
        assertTrue("默认禁止删除", text.contains("allow-delete: false"))

        val secret = adapter.bootstrapSecret("dufs")
        assertTrue("必须能取到 user:pass（当前=$secret）", secret != null && secret.contains(':'))
        assertTrue("口令要出现在配置里", text.contains("$secret@/:rw"))
    }

    @Test
    fun `gateway 模式只绑回环，direct 才绑所有网卡`() {
        val direct = context(accessMode = "direct")
        adapter.preflight(direct).getOrThrow()
        assertTrue(File(direct.confDir, "dufs.yaml").readText().contains("bind: 0.0.0.0"))

        val gateway = context(accessMode = "gateway")
        adapter.preflight(gateway).getOrThrow()
        assertTrue(File(gateway.confDir, "dufs.yaml").readText().contains("bind: 127.0.0.1"))
    }

    @Test
    fun `重复 preflight 不改口令`() {
        val ctx = context()
        adapter.preflight(ctx).getOrThrow()
        val first = File(ctx.confDir, "dufs.yaml").readText()
        val secret = adapter.bootstrapSecret("dufs")

        adapter.preflight(ctx).getOrThrow()

        assertEquals("重启一次换一次口令，用户就永远登不上", first, File(ctx.confDir, "dufs.yaml").readText())
        assertEquals(secret, adapter.bootstrapSecret("dufs"))
    }

    @Test
    fun `重置口令会换一个且写回配置`() {
        val ctx = context()
        adapter.preflight(ctx).getOrThrow()
        val before = adapter.bootstrapSecret("dufs")

        val reset = adapter.resetBootstrapSecret(ctx, null).getOrThrow()

        assertNotEquals(before, reset)
        assertTrue(File(ctx.confDir, "dufs.yaml").readText().contains("$reset@/:rw"))
        assertEquals(reset, adapter.bootstrapSecret("dufs"))
    }

    @Test
    fun `手改过的端口会在下次启动被拉回清单值`() {
        val ctx = context()
        adapter.preflight(ctx).getOrThrow()
        val conf = File(ctx.confDir, "dufs.yaml")
        conf.writeText(conf.readText().replace("port: 18110", "port: 5999"))

        adapter.preflight(ctx).getOrThrow()

        assertTrue("端口必须回到清单值", conf.readText().contains("port: 18110"))
    }
}
