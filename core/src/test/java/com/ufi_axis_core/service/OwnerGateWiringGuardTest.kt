package com.ufi_axis_core.service

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 装配守卫：`OwnerGate`（破坏性操作的主人密码门）必须在 `ComponentFactory` 里装上。
 *
 * ## 守的是什么
 *
 * `OwnerGate` 的判据全部是**装配层注入**的回调（开关、密码是否已设、校验器、节流）。
 * 未装配时默认值是「关 + 放行」—— 这是刻意的（单元测试与局部装配不该被迫先接配对管理器），
 * 代价就是**漏装等于整个门静默失效**：`requireOwner` 每次都返回 true，
 * 界面上开关照常能点、`owner_gate_enabled` 照常落库，但没有任何一条路径会真的拦人。
 *
 * 这种失效不报错、不抛异常，只有把开关打开、真的去点一次「恢复出厂」才会发现，
 * 所以必须有一条断言盯着它。用源码扫描而不是构造整张组件图，理由与
 * `ActionExecutorWiringGuardTest` 相同 —— 构造整图要 Android Context + 真实存储，
 * 代价远高于断言本身。
 */
class OwnerGateWiringGuardTest {

    @Test
    fun `ComponentFactory 装上了 OwnerGate 并读取总开关`() {
        val text = componentFactorySource()

        assertTrue(
            "ComponentFactory 必须调用 OwnerGate.install(...)：漏装等于这门静默失效" +
                "（requireOwner 恒放行，但设置页的开关看起来是生效的）",
            text.contains("OwnerGate.install(")
        )
        assertTrue(
            "OwnerGate 的 enabled 必须读 settings.ownerGateEnabled —— 写死 true 会让默认关的" +
                "约定失效，写死 false 会让开关变成摆设",
            text.contains("enabled = { settings.ownerGateEnabled }")
        )
        assertTrue(
            "校验器必须复用 pairingManager.verifyOwnerPassword —— 另写一套会绕开" +
                "PasswordAttemptLimiter 的 5 次锁定与全局递增节流",
            text.contains("verifyOwnerPassword(password, ip)")
        )
    }

    /**
     * 定位 `ComponentFactory.kt`。
     *
     * 候选路径覆盖三种工作目录：Gradle 的 `Test` 任务（模块目录 `core/`）、仓库根、
     * 以及 IDE 单跑时可能多退一级的情况。找不到直接失败 —— 扫到 0 个不许当成通过。
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
