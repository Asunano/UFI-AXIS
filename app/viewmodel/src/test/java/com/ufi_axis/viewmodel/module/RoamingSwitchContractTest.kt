package com.ufi_axis.viewmodel.module

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 数据漫游开关的端到端契约（2026-11-08）。
 *
 * ## 被守护的问题
 * 用户问「数据漫游开关有实际功能吗」。代码链路上它确实是一条真实写操作，但有两个
 * 容易造成「没功能」错觉的点：
 * 1. **写编码必须对**：漫游走 `SET_CONNECTION_MODE`，值要用 `on`/`off`（不是 `1`/`0`），
 *    而且 `roam_setting_option` 与 `dial_roam_setting_option` 两个字段都要带。编码错了
 *    设备返回 200 但值不变 —— 看起来就是「开关没反应」。
 * 2. **成功提示必须等回读**：原来走通用的 `deviceSettingSucceeded()`，下发成功立刻弹
 *    「已开启」，500ms 后回读把开关弹回去，用户看到的是假成功。现在必须用
 *    `getDeviceSettingsFresh()` 回读确认（`?fresh=1` 穿透 core 的 5 分钟缓存），
 *    只有设备真的报出目标状态才提示成功。
 *
 * 本测试是源码级护栏：纯函数/真实网络交互无法在本机复现设备固件的静默忽略行为，
 * 但上面两条的**代码形态**非常具体，正则就能钉死。
 */
class RoamingSwitchContractTest {

    @Test
    fun writeSpec_mustEncodeBothRoamFieldsAsOnOff() {
        val profile = executableCode(
            source("core/device-schema/src/main/kotlin/com/ufi_axis_core/deviceschema/profile/ZteGoformProfile.kt")
        )
        assertTrue(
            "漫游必须登记 WriteSpec（否则 writer 直接忽略本次写入）",
            profile.contains("SettingKey.ROAM to WriteSpec(")
        )
        assertTrue(
            "写入必须带 roam_setting_option",
            profile.contains("\"roam_setting_option\" to v")
        )
        assertTrue(
            "部分固件只认 dial_roam_setting_option，两个都要带",
            profile.contains("\"dial_roam_setting_option\" to v")
        )
    }

    @Test
    fun setRoaming_mustVerifyReadbackWithFreshRead() {
        val network = source(
            "src/main/java/com/ufi_axis/viewmodel/viewmodel/module/NetworkModule.kt"
        )
        val block = network
            .substringAfter("fun setRoamingEnabled(enabled: Boolean)")
            .substringBefore("fun setWifiSleep")

        assertFalse(
            "不能在下发成功后立刻弹成功：必须先回读确认设备真的变了",
            block.contains("deviceSettingSucceeded(")
        )
        assertTrue(
            "回读必须穿透 core 的 5 分钟 device:settings 缓存",
            block.contains("getDeviceSettingsFresh()")
        )
        assertTrue(
            "必须以回读值判定成功/失败",
            block.contains("finalSettings.roamingEnabled == enabled")
        )
    }

    @Test
    fun api_mustOfferFreshDeviceSettingsRead() {
        val api = source("app/data/src/main/java/com/ufi_axis/data/api/UfiAxisApi.kt")
        assertTrue(
            "写后回读需要 ?fresh=1 端点（core 侧 DeviceRoutes 已支持）",
            api.contains("@GET(\"api/device/settings?fresh=1\")")
        )
    }

    @Test
    fun ui_mustNotDescribeSettingAsCurrentRoamingState() {
        val ui = source(
            "app/feature-network/src/main/java/com/ufi_axis/ui/screens/CellularAdvancedScreen.kt"
        )
        assertFalse(
            "roamingEnabled 是「允许漫游」的配置，不是「当前正在漫游」",
            ui.contains("\"漫游中\"")
        )
        assertTrue(
            "描述应表达开关状态",
            ui.contains("description = if (roamingEnabled) \"已开启\" else \"已关闭\"")
        )
    }

    // ── 源码定位辅助（与 ForegroundRefreshFreshnessTest 同一手法）──────────────

    private fun source(relative: String): String {
        var dir: File? = File(".").absoluteFile.normalize()
        while (dir != null) {
            for (candidate in listOf(File(dir, relative), File(dir, "app/viewmodel/$relative"))) {
                if (candidate.isFile) return candidate.readText()
            }
            dir = dir.parentFile
        }
        throw AssertionError(
            "定位不到源文件 '$relative'（起点：${File(".").absolutePath}）。" +
                "若模块路径发生变化，请同步更新本测试中的相对路径。"
        )
    }

    private fun executableCode(text: String): String =
        text.replace(Regex("""/\*[\s\S]*?\*/"""), "")
            .lines()
            .joinToString("\n") { it.substringBefore("//") }
}
