package com.ufi_axis.viewmodel.module

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 网络制式弹窗的顶部布局（2026-11-08 用户裁决）。
 *
 * ## 新布局
 * ```
 * [        5G/4G/3G        ]  ← displayMedium 45sp，整行居中，不缩字号
 * [      信号 良好        ]  ← 信号徽章
 * [    PCI     |   频点    ]  ← 原右栏 2×2 网格挪到信号质量下方并整体居中
 * [    SINR    |   RSRQ    ]
 * [ chips... ]
 * ```
 *
 * ## 被替换的旧方案
 * 2026-11-08 早先曾用 `TextAutoSize.StepBased(22..45sp)` 让制式名在半栏内自动缩小，
 * 用户明确否决（“不缩小了，缩小更奇怪”），改为整行居中 + 网格下移。
 * 本测试同时钉住「不要退回自动缩小」和「网格必须在信号徽章之后」。
 */
class NetworkModeDialogLayoutTest {

    @Test
    fun modeLabel_mustBeFullWidthCenteredWithoutAutoShrink() {
        val code = source("app/feature-network/src/main/java/com/ufi_axis/ui/screens/NetworkModeDialog.kt")

        assertFalse(
            "用户明确要求不缩小：不能再接 TextAutoSize / autoSize",
            code.contains("autoSize =")
        )
        assertTrue(
            "制式名必须整行铺开并居中（不再被半栏宽度挤断）",
            code.contains("textAlign = TextAlign.Center") &&
                code.contains("modifier = Modifier.fillMaxWidth()")
        )
        assertTrue(
            "仍保持 displayMedium 大字（不因布局调整变小）",
            code.contains("MaterialTheme.typography.displayMedium")
        )
        assertTrue(
            "锁单行；不折行",
            code.contains("maxLines = 1")
        )
        assertTrue(
            "极端窄屏才允许 ellipsis 兜底",
            code.contains("overflow = TextOverflow.Ellipsis")
        )
    }

    @Test
    fun networkInfo_mustMoveBelowSignalBadge() {
        val code = source("app/feature-network/src/main/java/com/ufi_axis/ui/screens/NetworkModeDialog.kt")

        val signalBadge = code.indexOf("UfiBadge(")
        val pciCell = code.indexOf("CenteredInfoCell(\"PCI\"")
        assertTrue("信号徽章必须存在", signalBadge >= 0)
        assertTrue("PCI / 频点网格必须存在（居中版 CenteredInfoCell）", pciCell >= 0)
        assertTrue(
            "2×2 网格必须在信号徽章之后（原来的右栏已删除）",
            pciCell > signalBadge
        )
        assertTrue(
            "第二行 SINR / RSRQ 必须存在",
            code.contains("CenteredInfoCell(\"SINR\"") && code.contains("CenteredInfoCell(\"RSRQ\"")
        )
        assertTrue(
            "网格整体收窄到 86% 并交给父 Column 居中",
            code.contains("Modifier.fillMaxWidth(0.86f)")
        )
        assertTrue(
            "格子内容也必须居中（CenteredInfoCell 内部 CenterHorizontally + TextAlign.Center）",
            code.contains("private fun CenteredInfoCell") &&
                code.contains("horizontalAlignment = Alignment.CenterHorizontally") &&
                code.contains("textAlign = TextAlign.Center")
        )
    }

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
}
