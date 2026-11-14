package com.ufi_axis_core.service

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 装配守卫（2026-10-30 越权审查 §2.2）。
 *
 * ## 守的是什么
 *
 * `ActionExecutorImpl` 的「更新进行中」判据是**装配层注入**的：漏注入时默认
 * `{ false }`，定时任务就能在 OTA 写盘期间重启设备，与 route 侧 `rejectIfUpdating`
 * 的口径分叉。本类扫 `ComponentFactory` 的源码断言那处注入在。
 *
 * 用源码扫描而不是构造整张组件图，理由与 `PairingBlacklistWiringGuardTest`
 * 相同 —— 构造整图需要 Android Context + 真实存储，代价远高于这条断言本身。
 */
class ActionExecutorWiringGuardTest {

    @Test
    fun `定时任务执行器注入了更新互斥判据`() {
        val text = componentFactorySource()

        assertTrue(
            "ActionExecutorImpl 必须注入 updateBusy：漏装等于「OTA 进行中定时任务仍能重启设备」，" +
                "与 route 侧 rejectIfUpdating 的口径不一致（审查 §2.2）",
            text.contains("updateBusy = {")
        )
    }

    /**
     * 定位 `ComponentFactory.kt`。
     *
     * 候选路径覆盖三种工作目录：Gradle 的 `Test` 任务（模块目录 `core/`）、
     * 仓库根、以及 IDE 单跑时可能多退一级的情况。找不到直接失败 ——
     * 与 `CapabilityGateTest.routeSources()` 同口径：扫不到文件必须报错，
     * 不许把「扫到 0 个」当成通过。
     */
    private fun componentFactorySource(): String {
        val relative = "src/main/java/com/ufi_axis_core/service/ComponentFactory.kt"
        val candidates = listOf(
            File(relative),
            File("core/$relative"),
            File("../core/$relative"),
        )
        val file = candidates.firstOrNull { it.isFile }
            ?: error(
                "找不到 ComponentFactory.kt（cwd=${File("").absolutePath}）。" +
                    "本类靠扫源码守装配，扫不到必须报错"
            )
        return file.readText(Charsets.UTF_8)
    }
}
