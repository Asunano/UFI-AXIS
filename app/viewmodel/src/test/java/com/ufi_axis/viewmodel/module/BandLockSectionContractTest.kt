package com.ufi_axis.viewmodel.module

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 频段锁定 UI 的语义护栏（2026-11-08 重写）。
 *
 * 背景：设备 `band-status` 用 `"0"` / `"all"` 表示“没有限制”，客户端归一化成空集。
 * 空集在设备语义里是**全部频段都允许**，不是“一个都不允许”。旧 UI 把空集画成
 * “全部取消勾选”，于是「全部解锁」看起来像“全都不选”；用户明确要求改成**全选**。
 *
 * 这些断言是源码级的，因为真正要钉的是“空集 → 全选”的映射和 core 写入口径，
 * 不是某个像素；Compose 行为本身需要真机验证。
 */
class BandLockSectionContractTest {

    @Test
    fun emptyDeviceLock_mustMapToAllBandsSelected() {
        val code = source()
        assertTrue(
            "LTE 空集必须映射成全选（空集 = 不限制 = 全部允许）",
            code.contains("selectedLte = status.lteBands.ifEmpty { supportLte.toSet() }")
        )
        assertTrue(
            "NR 空集必须映射成全选",
            code.contains("selectedNr = status.nrBands.ifEmpty { supportNr.toSet() }")
        )
    }

    @Test
    fun unlockAll_mustSelectAllAndCallUnlockAll() {
        val code = source()
        val unlockFn = code.substringAfter("fun unlockAll()").substringBefore("fun lockSelected()")
        assertTrue(
            "全部解锁必须先勾满 LTE",
            unlockFn.contains("selectedLte = supportLte.toSet()")
        )
        assertTrue(
            "全部解锁必须先勾满 NR",
            unlockFn.contains("selectedNr = supportNr.toSet()")
        )
        assertTrue(
            "全部解锁调用 core 的 unlockAll 分支（null, null）",
            unlockFn.contains("lockBands(null, null)")
        )
        assertFalse(
            "不能再把全部解锁写成清空勾选",
            unlockFn.contains("selectedLte = emptySet()") || unlockFn.contains("selectedNr = emptySet()")
        )
    }

    @Test
    fun lockSelected_mustTreatAllGroupAsUnrestricted() {
        val code = source()
        val lockFn = code.substringAfter("fun lockSelected()").substringBefore("Column(verticalArrangement")
        assertTrue(
            "两组全选时走 unlockAll，避免下发 15 个数字",
            lockFn.contains("if (allLte && allNr)") && lockFn.contains("lockBands(null, null)")
        )
        assertTrue(
            "单组全选不能传全集：设备回读会归一化成空集，verify 会永远不相等",
            lockFn.contains("selectedLte.takeIf { !allLte && it.isNotEmpty() }") &&
                lockFn.contains("selectedNr.takeIf { !allNr && it.isNotEmpty() }")
        )
        assertTrue(
            "0 选中时直接返回，绝不能把空选择变成 unlock",
            lockFn.contains("if (total == 0) return")
        )
    }

    @Test
    fun allSelected_mustDistinguishAppliedFromDraft() {
        val code = source()
        assertTrue(
            "设备已无限制时，全选态显示「未限制」",
            code.contains("allSelected && deviceUnrestricted -> \"全部频段（未限制）\"")
        )
        assertTrue(
            "手选全 15 个但未应用时，显示「待应用」",
            code.contains("allSelected -> \"全部频段（待应用）\"")
        )
        assertTrue(
            "设备已无限制且全选时，锁定按钮应禁用，避免空操作",
            code.contains("!(allSelected && deviceUnrestricted)")
        )
    }

    @Test
    fun buttons_mustBeUnlockLeftLockRight_andLockHasNoCount() {
        val code = source()
        val unlock = code.indexOf("text = \"全部解锁\"")
        val lock = code.indexOf("text = \"锁定所选\"")
        assertTrue("两个按钮都必须存在", unlock >= 0 && lock >= 0)
        assertTrue("全部解锁必须在左边（先声明）", unlock < lock)
        assertFalse(
            "锁定按钮不能带 ·N 这种数量",
            code.contains("锁定所选 ·") || code.contains("锁定所选 \\u00b7")
        )
    }

    @Test
    fun fullSelectSlot_mustBeReservedAndAnimated() {
        val code = source()
        assertTrue(
            "「全选」槽位必须固定 52×36，不能按 allSelected 条件增删",
            code.contains("Modifier.width(52.dp).height(36.dp)")
        )
        assertTrue(
            "「全选」按钮必须在固定槽位内淡入/缩放",
            code.contains("AnimatedVisibility") && code.contains("visible = !allSelected")
        )
        assertTrue("卡片高度变化要有 animateContentSize", code.contains("animateContentSize"))
        assertTrue(
            "数字/模式/进度/状态点都要有动画",
            code.contains("AnimatedContent") &&
                code.contains("animateFloatAsState") &&
                code.contains("animateColorAsState")
        )
    }

    @Test
    fun bandChips_mustBeLargerThanTheDefaultSelector() {
        val code = source()
        assertTrue(
            "频段 chip 高度必须从默认 36dp 提升到 44dp",
            code.contains("chipHeight = 44.dp")
        )
        assertTrue(
            "频段 chip 横向 padding 必须从默认 8dp 提升到 18dp",
            code.contains("chipPaddingH = 18.dp")
        )
        assertTrue(
            "频段 chip 文字用 12sp 的 label（默认是 11sp captionEmphasis）",
            code.contains("chipTextStyle = UfiTextStyles.label")
        )
    }

    private fun source(): String {
        var dir: File? = File(".").absoluteFile.normalize()
        val relative = "app/feature-network/src/main/java/com/ufi_axis/ui/screens/BandLockSection.kt"
        while (dir != null) {
            val candidate = File(dir, relative)
            if (candidate.isFile) return candidate.readText()
            dir = dir.parentFile
        }
        throw AssertionError("定位不到源文件 '$relative'（起点：${File(".").absolutePath}）")
    }
}
