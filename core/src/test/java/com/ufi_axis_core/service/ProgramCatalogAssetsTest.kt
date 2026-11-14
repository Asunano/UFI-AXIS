package com.ufi_axis_core.service

import com.ufi_axis_core.controller.program.CloudreveAdapter
import com.ufi_axis_core.controller.program.DufsAdapter
import com.ufi_axis_core.controller.program.OpenListAdapter
import com.ufi_axis_core.controller.program.ProgramAdapterRegistry
import com.ufi_axis_core.controller.program.ProgramManifestCodec
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * 内置程序清单（`assets/programs/catalog.json`）的守卫。
 *
 * ## 为什么值得单独测这个 JSON
 *
 * 这份文件是**断网时商店的全部内容**，而且它坏了不会有任何报错：
 * `ComponentFactory` 里整段解析被 `runCatching` 包着，坏一条就静默丢一条，
 * 表现是"商店里少了个程序"，用户和日志都看不出来为什么。
 *
 * 所以这里把两件事钉死：
 * 1. 它必须能被 [ProgramManifestCodec] 完整解析（`bad` 必须为空）；
 * 2. 清单里声明的 `first_run.adapter` 必须在 `ComponentFactory` 里**真的注册过** ——
 *    未注册的适配器会让该条目在校验阶段被整条丢弃（`validate` 的
 *    "未注册的适配器"分支），这是最容易漏的一步：清单加了、代码忘了。
 *
 * 用源码扫描而不是启动整张组件图，理由与 [ActionExecutorWiringGuardTest] 相同。
 */
class ProgramCatalogAssetsTest {

    /**
     * 解析前必须先把适配器注册进注册表：`validate()` 会把"未注册的适配器"判成坏条目并整条丢弃。
     *
     * 这也是这条测试真正守住的东西 —— 运行时 `ComponentFactory` 先注册、再读 assets，
     * 顺序一旦反了（或漏注册），商店就会静默少一个程序而不是报错。
     */
    @Before
    fun registerAdapters() {
        ProgramAdapterRegistry.clearForTest()
        ProgramAdapterRegistry.register(OpenListAdapter())
        ProgramAdapterRegistry.register(CloudreveAdapter())
        ProgramAdapterRegistry.register(DufsAdapter())
    }

    @After
    fun cleanUp() {
        ProgramAdapterRegistry.clearForTest()
    }

    private fun find(relative: String): File? =
        listOf(File(relative), File("core/$relative"), File("../core/$relative"))
            .firstOrNull { it.exists() }

    private fun catalogFile(): File =
        find("src/main/assets/programs/catalog.json")
            ?: error("找不到内置清单（cwd=${File("").absolutePath}）")

    @Test
    fun `内置清单必须整体通过校验`() {
        val parsed = ProgramManifestCodec.parseCatalog(catalogFile().readText()).getOrThrow()

        assertTrue("解析失败的条目：${parsed.bad}", parsed.bad.isEmpty())
        assertTrue("内置清单不该为空", parsed.ok.isNotEmpty())

        val ids = parsed.ok.map { it.id }
        assertEquals("id 不能重复", ids.size, ids.distinct().size)
        assertTrue("OpenList 是内置兜底，不能丢", ids.contains("openlist"))
        assertTrue("Syncthing 应随内置清单下发", ids.contains("syncthing"))
        assertTrue("Cloudreve 应随内置清单下发", ids.contains("cloudreve"))
        assertTrue("ddns-go 应随内置清单下发", ids.contains("ddns-go"))
    }

    @Test
    fun `ddns-go 必须使用官方 android_arm64 构建`() {
        val parsed = ProgramManifestCodec.parseCatalog(catalogFile().readText()).getOrThrow()
        val ddns = parsed.ok.firstOrNull { it.id == "ddns-go" } ?: error("内置清单里没有 ddns-go")
        assertTrue(
            "必须用 android_arm64（linux_arm64 是 glibc 动态链接，Android 上跑不起来）",
            ddns.artifact.url.contains("android_arm64")
        )
        assertEquals("android-dynamic", ddns.artifact.linkage)
    }

    @Test
    fun `loopback 程序的 argv 不得硬编码 127_0_0_1`() {
        val parsed = ProgramManifestCodec.parseCatalog(catalogFile().readText()).getOrThrow()

        parsed.ok.filter { it.run.bind == "loopback" }.forEach { p ->
            assertTrue(
                "${p.id} 的 argv 硬编码了 127.0.0.1：direct 模式会监听回环、浏览器打不开；请用 {BIND}",
                p.run.argv.none { it.contains("127.0.0.1") },
            )
        }
    }

    @Test
    fun `清单里的适配器都必须在 ComponentFactory 注册`() {
        val parsed = ProgramManifestCodec.parseCatalog(catalogFile().readText()).getOrThrow()
        val factory = find("src/main/java/com/ufi_axis_core/service/ComponentFactory.kt")
            ?: error("找不到 ComponentFactory.kt（cwd=${File("").absolutePath}）")
        val source = factory.readText()

        val adapters = parsed.ok.mapNotNull { it.firstRun?.adapter }.distinct()
        assertTrue("内置清单里没有适配器，这条测试就失去意义", adapters.isNotEmpty())

        // 不假设类名 = id 首字母大写："openlist" 对应的类是 OpenListAdapter（内部还有大写字母），
        // 所以取"注册表里出现过的所有 *Adapter() 调用"，再按前缀配对。
        val registered = Regex("""([A-Za-z0-9_]+Adapter)\(""").findAll(source)
            .map { it.groupValues[1] }
            .toSet()
        assertTrue("ComponentFactory 里一个适配器都没注册？", registered.isNotEmpty())

        for (adapter in adapters) {
            val hit = registered.any { it.lowercase().startsWith(adapter.lowercase()) }
            assertTrue(
                "清单声明了适配器「$adapter」，但 ComponentFactory 里没有对应注册（已注册：$registered）—— " +
                    "该条目会在启动时被整条丢弃",
                hit
            )
        }
    }
}
