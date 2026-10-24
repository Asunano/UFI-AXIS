package com.ufi_axis.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * WiFi 设置「二次预警」护栏。
 *
 * ## 背景（2026-10-12 用户实测报 bug）
 * 用户反馈：「二次预警弹窗失效。WiFi 设置中改了之后没有正常弹出预警弹窗，
 * 而且直接发送更改」。
 *
 * 根因不是弹窗坏了，而是**触发条件写得太窄**：确认框只挂在 `bandChanged`（换频段）上，
 * 改 SSID / 密码 / 加密方式 / 隐藏 SSID / 最大连接数一律直接下发到设备。
 * 用户裁决：WiFi 设置里**任何一项**改动都会导致已连设备断联，所以都要先确认。
 * （最大连接数按用户判断计入 —— **降低**上限会把超出上限的已连设备挤下去。）
 *
 * ## 住在这个模块而不是 feature-network
 * `:app:feature-network` 没配 JUnit（build.gradle.kts 里没有 `testImplementation(libs.junit)`），
 * 为一个护栏去改它的 build 配置属于扩大改动范围。`:app:ui` 本来就有 test 环境，
 * 且已经住着同类的源码结构护栏（NavInsetHandoffGuardTest / DialogShellGuardTest）。
 * 代价是源码路径要写成跨模块的相对路径。
 *
 * ## 为什么用源码断言而不是行为单测
 * [WifiSettingsDialog] 里的变更判定读的是 `wifi`（设备当前值），要构造"草稿 ≠ 设备值"
 * 就得把整个 Composable 的依赖链搭起来 —— 而这套护栏的真正目标是**防止有人把某个字段
 * 重新漏出确认范围**，那是源码结构问题。这里断言"六个字段逐个出现在变更判定里、
 * 且保存按钮的判据与弹框判据是同一个"，比跑 Composable 更直接也更难被绕过。
 */
class WifiChangeConfirmGuardTest {

    /**
     * 跨模块定位源文件。
     *
     * Gradle 单测的工作目录默认是**模块目录**（`app/ui`），但 IDE / 其他 runner 可能从
     * 仓库根启动，因此从当前目录逐级上溯并逐层尝试 `app/ui/` 前缀，两种布局都能命中。
     * （与 NavInsetHandoffGuardTest.source 同款范式，不写死相对层级。）
     *
     * 2026-10-12：第一版写成 `../../feature-network/...` 是**错的** —— 工作目录已经是
     * `app/ui`，多退了两级，四条用例全部报「找不到源文件」。教训：跨模块路径不要手算层级。
     */
    private fun source(relative: String): String {
        var dir: File? = File(".").absoluteFile.normalize()
        while (dir != null) {
            for (candidate in listOf(File(dir, relative), File(dir, "app/ui/$relative"))) {
                if (candidate.isFile) return candidate.readText()
            }
            dir = dir.parentFile
        }
        throw AssertionError(
            "定位不到源文件 '$relative'（起点：${File(".").absolutePath}）。" +
                "若模块路径发生变化，请同步更新本测试中的相对路径。"
        )
    }

    private fun wifiSource(): String =
        source("feature-network/src/main/java/com/ufi_axis/ui/screens/WifiSettingsDialog.kt")

    /**
     * 程序性收起不能被当成「用户关闭设置」。
     *
     * `suspendedForConfirm=true` 让 visible 转 false 时，shell 会照常走一遍离场并回调
     * onDismiss —— 那是程序自己想收起。不挡掉的话，点「保存配置」会连带把整个
     * WiFi 设置弹窗也关掉（用户反馈过的"被误关闭"形态）。
     */
    @Test
    fun programmaticSuspendMustNotCloseWholeDialog() {
        val src = wifiSource()
        // 2026-10-22：二次确认改走公共设施 UfiDialogSuspendState + UfiConfirmHost，
        // 不再在本文件手写 suspendedForConfirm / closeRequested 两个 flag。
        // 不变量不变：程序性收起不能被当成「用户关闭设置」。
        assertTrue(
            "UfiScrollableDialog 的 onDismiss 必须区分「程序性收起」与「真实关闭」",
            src.contains("confirmSuspend.consumeProgrammaticDismiss()") &&
                Regex("""consumeProgrammaticDismiss\(\)\)\s*return@UfiScrollableDialog""")
                    .containsMatchIn(src)
        )
        assertTrue(
            "确认框上场前必须先收起本弹窗（confirmSuspend.suspend()）",
            src.contains("confirmSuspend.suspend()")
        )
        assertTrue(
            "点「暂不执行」必须恢复本弹窗（confirmSuspend.resume()），否则设置页再也打不开",
            src.contains("confirmSuspend.resume()")
        )
        assertTrue(
            "程序性收起之后仍要调用 onDismiss（真实关闭路径）",
            Regex("""consumeProgrammaticDismiss\(\)\)\s*return@UfiScrollableDialog[\s\S]{0,120}?onDismiss\(\)""")
                .containsMatchIn(src)
        )
    }

    /** 六个可下发字段**每一个**都必须出现在变更判定里。
     *
     * 数量 + 内容双重断言：少一个字段就漏掉一类破坏性操作（原始 bug 的形态），
     * 而只判 `contains` 会被"字段出现在别处（比如注释/文案）"骗过。
     */
    @Test
    fun everyDisruptiveFieldIsCoveredByChangeDetection() {
        val detection = wifiSource()
            .substringAfter("val changedItems = buildList {")
            .substringBefore("val hasAnyChange")
        // 逐个字段的判据：缺任一条 = 该字段的改动绕过确认框直接下发。
        val required = listOf(
            "WifiChangeItem.BAND",
            "WifiChangeItem.SSID",
            "WifiChangeItem.PASSWORD",
            "WifiChangeItem.AUTH",
            "WifiChangeItem.HIDE_SSID",
            "WifiChangeItem.MAX_STA"
        )
        required.forEach { item ->
            assertTrue(
                "变更判定缺少 $item —— 该字段改动会绕过二次确认直接下发",
                detection.contains(item)
            )
        }
    }

    /**
     * 保存按钮与确认框**必须用同一个判据**。
     *
     * 历史上是两套（`willDisconnect || bandChanged` vs `bandChanged`），
     * 会漂移成「弹了框但按钮不是红的」。这里断言 `confirmDestructive` 与拦截判断同源。
     */
    @Test
    fun confirmButtonAndDialogShareOnePredicate() {
        val src = wifiSource()
        assertTrue(
            "确认按钮的危险色判据应直接用 hasAnyChange，不另写一套",
            src.contains("confirmDestructive = hasAnyChange")
        )
        // 只断言「hasAnyChange 后面跟着确认框上场（confirmSuspend.suspend()）」，
        // 不写死整行 —— 2026-10-22 改走 UfiConfirmHost 后旧判据名 bandConfirmVisible
        // 已不存在，契约是「判据名 + 确认动作」同源，格式不是。
        assertTrue(
            "保存时必须先过 hasAnyChange 拦截（hasAnyChange 与 confirmSuspend.suspend() 必须同现）",
            Regex("""hasAnyChange[\s\S]{0,200}?confirmSuspend\.suspend\(\)""").containsMatchIn(src)
        )
    }

    /**
     * 「确认并保存」只能给**频段真的变了**的提交开频段开关。
     *
     * 原始代码写死 `submit(switchBand = true)`：只改密码点确认，也会向设备发一条
     * 「重启 WiFi 模块、踢掉所有客户端」的命令 —— 正是这个确认框要防的后果。
     */
    @Test
    fun bandSwitchIsOnlySentWhenBandActuallyChanged() {
        val src = wifiSource()
        assertTrue(
            "确认后的 submit 必须按实际变更决定是否换频段，不能恒为 true",
            src.contains("submit(switchBand = changedItems.contains(WifiChangeItem.BAND))")
        )
        assertTrue(
            "submit(switchBand = true) 恒真写法不应残留",
            !src.contains("submit(switchBand = true)")
        )
    }

    /** 枚举必须覆盖全部 6 项，且每项都要有中文文案（否则正文会漏词）。 */
    @Test
    fun changeItemEnumCoversSixItemsWithLabels() {
        val enumBody = wifiSource()
            .substringAfter("private enum class WifiChangeItem {")
            .substringBefore("\n}")
        val items = listOf("BAND", "SSID", "PASSWORD", "AUTH", "HIDE_SSID", "MAX_STA")
        items.forEach { item ->
            // 末项（MAX_STA）后面跟的是 `;` 而不是 `,`，所以两种分隔符都要认 ——
            // 2026-10-12：这里最初只判 `"$item,"`，MAX_STA 是唯一漏网的，护栏自己红了。
            assertTrue(
                "WifiChangeItem.$item 缺少枚举项声明",
                enumBody.contains("$item,") || enumBody.contains("$item;")
            )
            assertTrue("WifiChangeItem.$item 缺少中文文案 label", enumBody.contains("$item ->"))
        }
        assertEquals("变更项枚举必须恰好 6 项", 6, items.size)
    }
}