package com.ufi_axis.viewmodel.module

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 「真 PTY（ttyd）入口暂时下线」的回归护栏（2026-10-10）。
 *
 * ## 被守护的决定
 * 用户决定：真 PTY 整套功能（帧协议、app 端渲染）尚未完工，先把 **app 与 web 两侧的
 * 界面、设置开关、入口全部隐藏**，后续再重写。core 侧的 `ttyd_enabled` 配置项、
 * `/ws/terminal` 反代、`TtydManager` 等**一律不动** —— 恢复时只改 UI。
 *
 * ## 为什么要护栏
 * 「暂时隐藏」最容易被无意破坏：某次重构清理未使用代码时把注释行删了、
 * 或者有人看到「这个入口没人用」就顺手补回去，都会让半成品重新暴露给用户。
 * 而这类回归**没有任何编译期或运行期信号** —— 入口一直在那儿，只是没人注意到。
 *
 * ## 检查方式
 * 源码级断言：逐个文件确认「UI 触点那几行处于注释状态」，且**恢复所需的代码仍在**
 * （组件文件、路由常量、配置 key 都不能删）。两类错误都会让测试失败：
 * - 入口被加回来（该注释的没注释）
 * - 恢复素材被顺手删掉（注释掉的同时把实现也清了）
 *
 * 覆盖的触点（两端末尾共 6 处）：
 * - app `ToolsExtraScreen`：进阶工具页入口卡
 * - app `AppScreens`：路由注册（防深链绕过）
 * - app `DeviceControlScreen`：设置开关卡片 + 四个状态变量 + 首帧 status 拉取
 * - web `TerminalView`：「真 PTY」tab + `<PtyPane>` 面板
 * - web `GeneralPanel`：设置开关 ToggleRow
 */
class PtyEntryHiddenGuardTest {

    @Test
    fun appToolsExtraScreen_entryMustStayHidden() {
        val code = executableCode(source(ToolsExtraPath))
        assertTrue(
            "进阶工具页的「真 PTY 终端」入口卡出现在**可执行代码**里 —— " +
                "整套功能未完工（2026-10-10 用户决定下线），别顺手补回来",
            !code.contains("真 PTY 终端")
        )
        val full = source(ToolsExtraPath)
        assertTrue(
            "进阶工具页里连注释掉的入口行都没了 —— 恢复点丢了。" +
                "「暂时隐藏」≠「删除」，请保留注释形式的那一行",
            full.contains("// ToolEntry(\"真 PTY 终端\"")
        )
    }

    @Test
    fun appScreens_routeRegistrationMustStayHidden() {
        val code = executableCode(source(AppScreensPath))
        assertTrue(
            "app 路由表里 DETAIL_PTY_TERMINAL 出现在**可执行代码**里 —— " +
                "只藏 UI 入口不够：路由注册留着的话，深链/外部 Intent 仍能直接打开这页",
            !code.contains("Routes.DETAIL_PTY_TERMINAL to")
        )
        assertTrue(
            "路由注册那行连注释都没了，DeepLink 兜底也一并去掉才对（恢复点）",
            source(AppScreensPath).contains("// Routes.DETAIL_PTY_TERMINAL to")
        )
    }

    @Test
    fun appDeviceControlScreen_switchAndStateMustStayHidden() {
        val full = source(DeviceControlPath)
        val code = executableCode(full)

        assertTrue(
            "设备控制页的 ttyd 开关卡片出现在**可执行代码**里（UI 触点）",
            !code.contains("真 PTY 终端（ttyd）")
        )
        assertTrue(
            "ttyd 的四个状态变量（ttydEnabled/ttydRunning/ttydBusy/ttydNote）出现在可执行代码里 —— " +
                "它们必须跟着开关卡片一起藏：留着就是四个无人读写的死状态",
            !code.contains("var ttydEnabled by remember")
        )
        assertTrue(
            "设备控制页仍在**每次进页**拉一次 ttydStatus() —— 开关都藏了，这个请求是纯白耗。" +
                "LaunchedEffect 必须跟着一起注释",
            !Regex("""viewModel\.tools\.ttydStatus\(\)""").containsMatchIn(code)
        )
        assertTrue(
            "恢复点丢了：设备控制页里找不到注释掉的开关卡片",
            full.contains("// title = \"真 PTY 终端（ttyd）\"")
        )
    }

    @Test
    fun webTerminalView_ptyTabAndPaneMustStayHidden() {
        val full = source(WebTerminalViewPath)
        val code = executableCode(full)

        assertTrue(
            "web 终端页的「真 PTY」tab 出现在**可执行代码**里 —— 未完工，先藏",
            !code.contains("""tab="真 PTY"""")
        )
        assertTrue(
            "<PtyPane> 面板出现在可执行代码里 —— tab 撤了它虽不可达，但那是「运行上不可达」" +
                "而非「藏干净」，两个一起注释掉",
            !code.contains("<PtyPane")
        )
        assertTrue(
            "恢复点丢了：找不到注释掉的 <n-tab-pane name=\"pty\"> 或 <PtyPane>",
            full.contains("""<!-- <n-tab-pane name="pty" """) && full.contains("<!-- <PtyPane")
        )
        // 同页 AT 命令 / Shell 两个 tab 与 ttyd 无关，绝不能跟着被藏。
        assertTrue(
            "「AT 命令」tab 被误藏了 —— 它与 ttyd 无关，是独立的逐条 exec 功能，必须保留",
            code.contains("""tab="AT 命令"""")
        )
        assertTrue(
            "「Shell」tab 被误藏了 —— 同上，必须保留",
            code.contains("""tab="Shell"""")
        )
    }

    @Test
    fun webGeneralPanel_switchMustStayHidden() {
        val full = source(WebGeneralPanelPath)
        val code = executableCode(full)

        assertTrue(
            "web 设置里的 ttyd 开关出现在**可执行代码**里（ToggleRow）",
            !code.contains("""label="真 PTY 终端（ttyd，默认关，改动立即生效）"""")
        )
        assertTrue(
            "恢复点丢了：找不到注释掉的 ToggleRow",
            full.contains("<!-- <ToggleRow")
        )
    }

    // ── 测试脚手架（同 CoreUpdateExemptionGuardTest） ────────────────────────

    private companion object {
        const val ToolsExtraPath =
            "app/feature-tools/src/main/java/com/ufi_axis/ui/screens/ToolsExtraScreen.kt"
        const val AppScreensPath =
            "app/src/main/java/com/ufi_axis/app/navigation/AppScreens.kt"
        const val DeviceControlPath =
            "app/feature-settings/src/main/java/com/ufi_axis/ui/screens/DeviceControlScreen.kt"
        const val WebTerminalViewPath = "web/src/views/terminal/TerminalView.vue"
        const val WebGeneralPanelPath = "web/src/views/settings/panels/GeneralPanel.vue"
    }

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

    /**
     * 剥掉注释，只留**可执行代码**。
     *
     * HTML 注释与 Kotlin/TS 注释语法不同（`<!-- -->` / `//` / `/* */`），这里三种都剥，
     * 且 `//` 只在行首（缩进后）或前面是空白时剥 —— 否则会误伤 URL 里的 `//`。
     */
    private fun executableCode(text: String): String =
        text.replace(Regex("""<!--[\s\S]*?-->"""), "")
            .replace(Regex("""/\*[\s\S]*?\*/"""), "")
            .lines()
            .joinToString("\n") { line ->
                val t = line.trimStart()
                if (t.startsWith("//")) "" else line
            }
}