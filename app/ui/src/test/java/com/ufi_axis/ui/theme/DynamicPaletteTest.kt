package com.ufi_axis.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 动态取色（Material You）的**纯函数**单测：`ColorContrast.kt` + `DynamicPalette.kt`。
 *
 * 为什么能做成纯 JVM 单测：[buildDynamicPalette] 只吃一个 [DynamicSourceColors]
 * 数据类（十个 `Color`），读平台资源那一步被单独隔在 `readDynamicSourceColors(context)` 里。
 * 于是"壁纸色 → 17 个色槽"的全部判断（含对比度兜底）都可以在这里造极端输入去验，
 * 不需要 Robolectric、也不需要真机换壁纸。
 *
 * 覆盖：
 * - 对比度算法本身（与 WCAG 的已知值对齐）
 * - 「两个表面肉眼可分」的 L\* 判据
 * - [onColorFor] 的兜底方向：亮 accent → 深字，暗 accent → 浅字
 * - [buildDynamicPalette] 的取色纪律：前景槽只来自 neutral，不来自 accent
 * - 两套极端壁纸下，动态 palette 仍满足与 7 套静态皮肤**同一套**对比度硬指标
 */
class DynamicPaletteTest {

    // ══════════════ 对比度算法本身 ══════════════

    @Test
    fun `contrast ratio matches known WCAG anchors`() {
        // 黑白极值 = 21:1，这是 WCAG 定义的上界，算错伽马就会偏离
        assertEquals(21.0, contrastRatio(Color.White, Color.Black), 0.01)
        // 同色 = 1:1
        assertEquals(1.0, contrastRatio(Color.White, Color.White), 1e-9)
        // 与顺序无关
        assertEquals(
            contrastRatio(Color.White, Color(0xFF767676)),
            contrastRatio(Color(0xFF767676), Color.White),
            1e-9
        )
        // #767676 是 WCAG 教科书例子：对白正好 4.54:1（AA 正文的临界灰）
        assertTrue(contrastRatio(Color(0xFF767676), Color.White) >= MIN_TEXT_CONTRAST)
        // 再浅一档（#777777）就掉到 4.5 以下 —— 说明阈值判定不是"约等于"糊过去的
        assertFalse(contrastRatio(Color(0xFF787878), Color.White) >= MIN_TEXT_CONTRAST)
    }

    @Test
    fun `surface distinguishability uses perceptual lightness not contrast ratio`() {
        val white = Color(0xFFFFFFFF)
        val defaultPageBg = Color(0xFFF8F8F8)
        // 出厂默认皮肤浅色态的这一对：WCAG 对比度只有约 1.06:1，
        // 光看对比度会误判成"完全没区别"，但真机上卡片边界清晰可见。
        assertTrue(
            "WCAG 对比度在近白区间极不敏感，所以表面可分性不能用它判",
            contrastRatio(defaultPageBg, white) < 1.1
        )
        assertTrue(
            "#F8F8F8 与 #FFFFFF 必须被判为可分（ΔL* ≈ 2.42，这是判据的锚点）",
            surfacesAreDistinguishable(defaultPageBg, white)
        )
        assertFalse(
            "只差 1 个 8bit 台阶的两个近白色必须被判为不可分",
            surfacesAreDistinguishable(Color(0xFFFEFEFE), white)
        )
        assertEquals(100.0, perceivedLightness(white), 0.01)
        assertEquals(0.0, perceivedLightness(Color.Black), 0.01)
    }

    // ══════════════ 前景兜底 ══════════════

    @Test
    fun `onColorFor flips between ink and paper by contrast not by luma threshold`() {
        val ink = Color(0xFF1B1B16)
        val paper = Color(0xFFF5F5F0)
        // 荧光黄：白字在它上面完全不可读，必须选深字
        assertEquals(ink, onColorFor(Color(0xFFFFEB3B), dark = ink, light = paper))
        // 深靛蓝：反过来
        assertEquals(paper, onColorFor(Color(0xFF303F9F), dark = ink, light = paper))
        // 选出来的那个必须真的达标 —— 这是本函数存在的意义，不只是"选一个"
        for (bg in listOf(Color(0xFFFFEB3B), Color(0xFF303F9F), Color(0xFF9FA8DA), Color(0xFF22D3EE))) {
            val fg = onColorFor(bg, dark = ink, light = paper)
            assertTrue(
                "onColorFor 在 $bg 上选出的前景对比度只有 ${"%.2f".format(contrastRatio(fg, bg))}:1",
                contrastRatio(fg, bg) >= MIN_NON_TEXT_CONTRAST
            )
        }
    }

    // ══════════════ 色板 → ThemePalette ══════════════

    /**
     * 前景槽的**取色纪律**：`textPrimary` / `textSecondary` / `iconTint` 一律取自 neutral 梯度，
     * 绝不来自动态 accent。
     *
     * 这条是当年删掉 `dynamic` 的直接理由（accent 由壁纸决定 ⇒ 静态无法保证任何对比度），
     * 现在用一组**故意刺眼**的 accent 把它钉住：只要有人把某个前景槽接回 accent，
     * 下面的 assertNotEquals 立刻红灯。
     */
    @Test
    fun `dynamic palette never sources foreground slots from the wallpaper accent`() {
        val src = neonYellowWallpaper
        val p = buildDynamicPalette(src)

        assertEquals(src.neutral1_900, p.textPrimaryLight)
        assertEquals(src.neutral1_50, p.textPrimaryDark)
        assertEquals(src.neutral2_700, p.textSecondaryLight)
        assertEquals(src.neutral2_200, p.textSecondaryDark)
        assertEquals(src.neutral1_900, p.iconTintLight)
        assertEquals(src.neutral1_50, p.iconTintDark)

        for (slot in listOf(
            p.textPrimaryLight, p.textPrimaryDark,
            p.textSecondaryLight, p.textSecondaryDark,
            p.iconTintLight, p.iconTintDark
        )) {
            assertNotEquals("前景槽不许取自动态 accent（浅色态主色）", src.accent1_600, slot)
            assertNotEquals("前景槽不许取自动态 accent（深色态主色）", src.accent1_200, slot)
            assertNotEquals("前景槽不许取自动态次强调色", src.accent2_700, slot)
            assertNotEquals("前景槽不许取自动态次强调色", src.accent2_200, slot)
        }
    }

    /** 动态 palette 不是皮肤：不许出现在预设表里，且作为 id 必须被迁移逻辑折叠掉。 */
    @Test
    fun `dynamic palette is not a preset`() {
        assertEquals(DYNAMIC_THEME_ID, buildDynamicPalette(neonYellowWallpaper).id)
        assertFalse(
            "动态取色是独立开关，不该混进 allPresets（混进去用户会在皮肤网格里看到一个假格子）",
            ThemePresets.allPresets.any { it.id == DYNAMIC_THEME_ID }
        )
        assertEquals(
            "作为持久化 id，\"dynamic\" 必须被折叠回 default（老用户盘上可能存着它）",
            ThemePresets.DEFAULT_ID,
            ThemePresets.normalizeThemeId(DYNAMIC_THEME_ID)
        )
    }

    /**
     * 两套极端壁纸下，动态 palette 必须满足与 7 套静态皮肤**同一套**对比度硬指标。
     *
     * 判据与 `ColorTest.every preset meets the contrast floor in both modes` 逐条对齐 ——
     * 动态取色不是"因为算不准所以放宽标准"的例外，它是"用运行时兜底换取同样的标准"。
     */
    @Test
    fun `dynamic palette meets the same contrast floor as static presets`() {
        for ((name, src) in listOf("荧光黄壁纸" to neonYellowWallpaper, "深靛蓝壁纸" to deepIndigoWallpaper)) {
            val palette = buildDynamicPalette(src)
            for (isDark in listOf(false, true)) {
                val p = palette.resolve(isDark)
                val tag = "$name(isDark=$isDark)"
                fun floorAtLeast(actual: Double, floor: Double, what: String) = assertTrue(
                    "$tag $what 实测 ${"%.2f".format(actual)}:1，低于 $floor:1",
                    actual >= floor
                )
                floorAtLeast(contrastRatio(p.textPrimary, p.cardBg), MIN_TEXT_CONTRAST, "textPrimary 对 cardBg")
                floorAtLeast(contrastRatio(p.textSecondary, p.cardBg), MIN_TEXT_CONTRAST, "textSecondary 对 cardBg")
                floorAtLeast(contrastRatio(p.iconTint, p.cardBg), MIN_NON_TEXT_CONTRAST, "iconTint 对 cardBg")
                floorAtLeast(contrastRatio(p.onAccent, p.accent), MIN_NON_TEXT_CONTRAST, "onAccent 对 accent 实底")
                floorAtLeast(contrastRatio(p.onGradient, p.accent), MIN_NON_TEXT_CONTRAST, "onGradient 对 accent")
                assertTrue(
                    "$tag cardBg 与 pageBg 的 CIE L* 差不足 $MIN_SURFACE_DELTA_L，卡片与页面底会糊成一片",
                    surfacesAreDistinguishable(p.cardBg, p.pageBg)
                )
            }
        }
    }

    // ══════════════ 测试用的极端色板 ══════════════
    //
    // 数值不必与真机 Monet 的输出一致 —— 这里要的恰恰是**比真机更极端**的输入：
    // 只要极端输入都能过判据，真机那些温和的壁纸色自然也能过。

    /** 荧光黄壁纸：主色亮到白字必然失守，用来验证前景兜底会翻成深字。 */
    private val neonYellowWallpaper = DynamicSourceColors(
        accent1_600 = Color(0xFFFFEB3B),
        accent1_200 = Color(0xFFFFF59D),
        accent2_700 = Color(0xFF4E4B00),
        accent2_200 = Color(0xFFE6E3A0),
        neutral1_0 = Color(0xFFFFFFFF),
        neutral1_50 = Color(0xFFF5F5F0),
        neutral1_800 = Color(0xFF2E2E28),
        neutral1_900 = Color(0xFF1B1B16),
        neutral2_700 = Color(0xFF4A4A42),
        neutral2_200 = Color(0xFFC9C9BF)
    )

    /** 深靛蓝壁纸：浅色态主色够深（白字可用），而深色态主色是浅色（必须翻成深字）—— 明暗两态取值不同。 */
    private val deepIndigoWallpaper = DynamicSourceColors(
        accent1_600 = Color(0xFF303F9F),
        accent1_200 = Color(0xFF9FA8DA),
        accent2_700 = Color(0xFF2A2E5C),
        accent2_200 = Color(0xFFB8BCE0),
        neutral1_0 = Color(0xFFFFFFFF),
        neutral1_50 = Color(0xFFF3F3F7),
        neutral1_800 = Color(0xFF2A2A31),
        neutral1_900 = Color(0xFF17171C),
        neutral2_700 = Color(0xFF46464F),
        neutral2_200 = Color(0xFFC6C6CF)
    )
}
