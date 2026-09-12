package com.ufi_axis.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「自定义」皮肤（`CustomPalette.kt`）的**属性式**单测。
 *
 * ## 为什么必须是属性式（对全域种子扫描），而不是抽几个色试试
 * 7 套预设的对比度由 `ColorTest.every preset meets the contrast floor in both modes` 逐套体检 ——
 * 那条测试**遍历 `allPresets`**，而自定义皮肤根本不在 `allPresets` 里（它没有固定色值），
 * 所以那条测试对它是零覆盖。更重要的是：预设只有 7 组固定输入，自定义的输入是
 * **用户在取色器里能拖出来的任意颜色**，抽样测试证明不了任何事 ——
 * 唯一有意义的断言形式是"对**全部**可能的种子色，四条硬指标都成立"。
 *
 * 本文件因此做两件事：
 * 1. [custom palette meets the contrast floor for a dense sweep of the whole HSL domain] ——
 *    在整个 HSL 立方体上密集扫描（约 3.3 万个种子 × 明暗两态），逐个跑与预设**完全相同**
 *    的判据（[paletteContrastViolations]）。这是本任务最重要的一条测试。
 * 2. [custom palette meets the contrast floor for named extreme seeds] ——
 *    对点名的极端种子（纯黑 / 纯白 / 纯灰 / 极低饱和 / 极高饱和 / 六个主色相 …）再验一遍，
 *    并把**实测余量**打印出来。密集扫描能保证"没有反例"，但读不出"哪一项最紧"；
 *    命名用例负责后者 —— 调推导常数的人需要知道自己在往哪个方向挤余量。
 */
class CustomPaletteTest {

    // ══════════════ HSL 转换本身 ══════════════

    @Test
    fun `hsl round trip preserves the color`() {
        for (h in 0 until 360 step 7) {
            for (s in 0..10) {
                for (l in 1..9) {
                    val original = hslColor(h.toFloat(), s / 10f, l / 10f)
                    val hsl = original.toHsl()
                    val again = hslColor(hsl)
                    assertEquals("h=$h s=$s l=$l 的 red 通道", original.red, again.red, 1e-4f)
                    assertEquals("h=$h s=$s l=$l 的 green 通道", original.green, again.green, 1e-4f)
                    assertEquals("h=$h s=$s l=$l 的 blue 通道", original.blue, again.blue, 1e-4f)
                }
            }
        }
    }

    /**
     * 灰阶的色相在数学上未定义，[Color.toHsl] 必须给出一个**能塞进滑块**的值而不是 NaN。
     * 这条不是形式主义：取色器会把 hue 直接接到滑块的 `value` 上，NaN 会让滑块跳出轨道。
     *
     * 明度用 [CHANNEL_STEP] 容差比：`androidx.compose.ui.graphics.Color` 的 sRGB 实例
     * 是**每通道 8bit 打包**的（不是浮点原值），所以 `Color(red = 0.25f, …).red` 会回来
     * `64/255 = 0.2509804`。这个量化对本仓所有配色都成立 —— 推导公式的余量必须容得下它，
     * 见密集扫描那条测试里的"持久化往返"分支。
     */
    @Test
    fun `grayscale seeds report zero saturation and a usable hue`() {
        for (v in listOf(0f, 0.25f, 0.5f, 0.75f, 1f)) {
            val hsl = Color(red = v, green = v, blue = v, alpha = 1f).toHsl()
            assertEquals("灰阶饱和度必须是 0", 0f, hsl.saturation, 1e-6f)
            assertEquals("灰阶明度就是通道值（容 8bit 量化）", v, hsl.lightness, CHANNEL_STEP)
            assertFalse("色相不许是 NaN", hsl.hue.isNaN())
            assertTrue("色相必须落在 [0, 360)", hsl.hue >= 0f && hsl.hue < 360f)
        }
    }

    @Test
    fun `seed argb packing is opaque and round trips`() {
        for (seed in namedSeeds.values) {
            val argb = customSeedToArgb(seed)
            assertEquals(
                "alpha 必须被强制成 0xFF —— ThemeManager.CUSTOM_SEED_UNSET(0) 靠这一点当哨兵",
                0xFF,
                (argb ushr 24) and 0xFF
            )
            val decoded = Color(argb)
            // 8bit 量化后允许半个台阶的误差
            assertEquals(seed.red, decoded.red, 1f / 255f)
            assertEquals(seed.green, decoded.green, 1f / 255f)
            assertEquals(seed.blue, decoded.blue, 1f / 255f)
        }
    }

    // ══════════════ 核心：对比度硬指标 ══════════════

    /**
     * **整个 HSL 立方体**上的密集扫描：色相每 5°、饱和度每 5%、明度每 5%
     * = 72 × 21 × 21 = 31 752 个种子，每个再验明暗两态、七条判据。
     *
     * 判据直接调 [paletteContrastViolations] —— 与取色器"确认"按钮的闸门是**同一份实现**。
     * 所以这条测试同时证明了两件事：配色本身合规，且运行时闸门不会误判成不合规（否则
     * 用户会遇到"确认按钮永远是灰的"）。
     *
     * 顺便也扫一遍**8bit 量化后**的路径：真实种子色是从 SharedPreferences 的 ARGB Int
     * 解出来的，不是浮点。量化会让每个通道抖动最多 1/255，而最紧的一项余量只有 12%
     * （见命名用例的打印），所以这一步不能省。
     */
    @Test
    fun `custom palette meets the contrast floor for a dense sweep of the whole HSL domain`() {
        var checked = 0
        for (h in 0 until 360 step 5) {
            for (si in 0..20) {
                for (li in 0..20) {
                    val exact = hslColor(h.toFloat(), si / 20f, li / 20f)
                    // 浮点路径 + 持久化往返（8bit 量化）路径都要过
                    for (seed in listOf(exact, Color(customSeedToArgb(exact)))) {
                        val violations = paletteContrastViolations(buildCustomPalette(seed))
                        assertTrue(
                            "种子 hsl(${h}, ${si * 5}%, ${li * 5}%) = ${seed.hex()} 推出的配色不合格：" +
                                violations.joinToString("；") +
                                "。这是硬指标，不许放宽 —— 请改 CustomPalette.kt 的推导常数。",
                            violations.isEmpty()
                        )
                        checked++
                    }
                }
            }
        }
        assertEquals("扫描规模变了，先确认是有意的（步长改小会显著拖慢单测）", 63_504, checked)
    }

    /**
     * 点名的极端种子 + **实测余量表**。
     *
     * 密集扫描证明"没有反例"，这条负责回答"最紧的是哪一项、还剩多少余量"——
     * 改推导常数的人需要这个数字，否则只能靠反复试错。失败信息里会带上完整的余量表。
     */
    @Test
    fun `custom palette meets the contrast floor for named extreme seeds`() {
        val report = StringBuilder("自定义皮肤 · 极端种子的对比度实测（门槛：正文 4.5 / 非文本 3.0 / ΔL* 2.0）\n")
        var worstMargin = Double.MAX_VALUE
        var worstWhat = ""
        for ((name, seed) in namedSeeds) {
            val palette = buildCustomPalette(seed)
            val violations = paletteContrastViolations(palette)
            assertTrue("种子「$name」(${seed.hex()}) 不合格：${violations.joinToString("；")}", violations.isEmpty())
            for (isDark in listOf(false, true)) {
                val p = palette.resolve(isDark)
                val mode = if (isDark) "深" else "浅"
                val items = listOf(
                    Triple("正文/卡面", contrastRatio(p.textPrimary, p.cardBg), MIN_TEXT_CONTRAST),
                    Triple("副文案/卡面", contrastRatio(p.textSecondary, p.cardBg), MIN_TEXT_CONTRAST),
                    Triple("图标/卡面", contrastRatio(p.iconTint, p.cardBg), MIN_NON_TEXT_CONTRAST),
                    Triple("次强调/卡面", contrastRatio(p.accentSecondary, p.cardBg), MIN_NON_TEXT_CONTRAST),
                    Triple("实底前景/accent", contrastRatio(p.onAccent, p.accent), MIN_NON_TEXT_CONTRAST),
                    Triple(
                        "渐变前景/最亮点",
                        contrastRatio(p.onGradient, heroGradientBrightestStop(p.accent, isDark)),
                        MIN_NON_TEXT_CONTRAST
                    ),
                    Triple(
                        "ΔL*(卡/页)",
                        kotlin.math.abs(perceivedLightness(p.cardBg) - perceivedLightness(p.pageBg)),
                        MIN_SURFACE_DELTA_L
                    )
                )
                report.append("  ${name.padEnd(14)} $mode  accent=${p.accent.hex()}  ")
                for ((label, actual, floor) in items) {
                    report.append("$label=${"%.2f".format(actual)} ")
                    val margin = actual / floor
                    if (margin < worstMargin) {
                        worstMargin = margin
                        worstWhat = "$name($mode) $label ${"%.2f".format(actual)} / 门槛 $floor"
                    }
                }
                report.append('\n')
            }
        }
        report.append("  最紧的一项：$worstWhat（余量 ${"%.1f".format((worstMargin - 1) * 100)}%）\n")
        // 余量下界一起钉住：低于 1.05（5%）说明有人把推导常数挤到了及格线上，
        // 那时哪怕单测还是绿的，也已经没有任何抗量化 / 抗微调的空间了。
        assertTrue(report.toString(), worstMargin >= 1.05)
        println(report)
    }

    // ══════════════ 取色纪律（照 DynamicPaletteTest 的同名断言） ══════════════

    /**
     * 前景槽的**取色纪律**：正文 / 副文案 / 图标一律来自中性梯度，绝不等于 accent 或次强调色。
     *
     * 这是全部正文类硬指标的**结构性**保证：只要这六个槽的明度由公式钉死，
     * 用户选什么颜色都影响不到它们。有人把某个前景槽接回 accent 的话，
     * 密集扫描那条会在某些色相上红灯，但错误信息会指向"某个具体色相不合格"，
     * 读起来像是"调一下常数就好"；本条则直接指出**结构**被破坏了。
     */
    @Test
    fun `custom palette never sources foreground slots from the seed accent`() {
        for ((name, seed) in namedSeeds) {
            val p = buildCustomPalette(seed)
            for (slot in listOf(
                p.textPrimaryLight, p.textPrimaryDark,
                p.textSecondaryLight, p.textSecondaryDark,
                p.iconTintLight, p.iconTintDark
            )) {
                // 纯灰种子例外：accent 本身就是中性灰，撞色是巧合而不是"接回 accent"，
                // 用饱和度足够高的种子去验才有意义。
                if (seed.toHsl().saturation < 0.2f) continue
                assertNotEquals("「$name」的前景槽取自 accent（浅色态）", p.accentLight, slot)
                assertNotEquals("「$name」的前景槽取自 accent（深色态）", p.accentDark, slot)
                assertNotEquals("「$name」的前景槽取自次强调色", p.accentSecondaryLight, slot)
                assertNotEquals("「$name」的前景槽取自次强调色", p.accentSecondaryDark, slot)
            }
        }
    }

    /**
     * 中性槽的明度**不读种子的明度**——这是上一条的量化版。
     *
     * 同一个色相、明度从 5% 拖到 95%，六对中性槽必须逐个落在 [CHANNEL_STEP] 之内
     * （不是逐位相等：`Color` 是每通道 8bit 打包的，而 `hslColor` 的入参先经过一次
     * 反解，两次量化会带来最多一个台阶的抖动）。有人把某个中性槽接上 `p.lightness`，
     * 差值会是几十个台阶，这条立刻红灯。
     *
     * 饱和度固定取 1.0：染色系数是 `min(s × 0.08, 0.06)`，`s ≥ 0.75` 时**已被上限截断**，
     * 于是 `s` 的量化抖动完全不影响结果，只留下色相 / 明度这一个变量。
     *
     * 明度**刻意不取 0 与 1**：sRGB 里纯黑与纯白**不携带色相与饱和度**
     * （`hslColor(210, 0.8, 0)` 就是 `#000000`，反解回来是 `hsl(0, 0, 0)`）。
     * 那是色彩空间的性质，不是推导公式的问题 —— 用户在取色器里把明度拖到 0 时，
     * 拿到的确实是一套纯灰阶配色，而且它同样合规（见密集扫描）。
     */
    @Test
    fun `neutral slots are independent of the seed lightness`() {
        for (h in listOf(0f, 60f, 120f, 180f, 240f, 300f)) {
            val reference = buildCustomPalette(hslColor(h, 1f, 0.5f))
            for (li in 1..19) {
                val p = buildCustomPalette(hslColor(h, 1f, li / 20f))
                val tag = "hsl($h, 1.0, ${li / 20f})"
                fun same(what: String, a: Color, b: Color) {
                    assertEquals("$tag $what.red", a.red, b.red, CHANNEL_STEP)
                    assertEquals("$tag $what.green", a.green, b.green, CHANNEL_STEP)
                    assertEquals("$tag $what.blue", a.blue, b.blue, CHANNEL_STEP)
                }
                same("pageBgLight", reference.pageBgLight, p.pageBgLight)
                same("pageBgDark", reference.pageBgDark, p.pageBgDark)
                same("cardBgLight", reference.cardBgLight, p.cardBgLight)
                same("cardBgDark", reference.cardBgDark, p.cardBgDark)
                same("textPrimaryLight", reference.textPrimaryLight, p.textPrimaryLight)
                same("textPrimaryDark", reference.textPrimaryDark, p.textPrimaryDark)
                same("textSecondaryLight", reference.textSecondaryLight, p.textSecondaryLight)
                same("textSecondaryDark", reference.textSecondaryDark, p.textSecondaryDark)
                same("dividerLight", reference.dividerLight, p.dividerLight)
                same("dividerDark", reference.dividerDark, p.dividerDark)
                same("iconTintLight", reference.iconTintLight, p.iconTintLight)
                same("iconTintDark", reference.iconTintDark, p.iconTintDark)
            }
        }
    }

    /** 纯灰种子必须得到一套**纯灰阶**中性槽（染色倍率乘在饱和度上，`s = 0` 时整条链归零）。 */
    @Test
    fun `a fully desaturated seed yields a neutral gray ramp`() {
        val p = buildCustomPalette(Color(red = 0.5f, green = 0.5f, blue = 0.5f, alpha = 1f))
        for (slot in listOf(
            p.pageBgLight, p.pageBgDark, p.cardBgLight, p.cardBgDark,
            p.textPrimaryLight, p.textPrimaryDark, p.textSecondaryLight, p.textSecondaryDark,
            p.iconTintLight, p.iconTintDark, p.accentLight, p.accentDark
        )) {
            assertEquals("纯灰种子的槽 ${slot.hex()} 三通道必须相等", slot.red, slot.green, 1e-5f)
            assertEquals("纯灰种子的槽 ${slot.hex()} 三通道必须相等", slot.green, slot.blue, 1e-5f)
        }
    }

    /** accent 的明度夹取：越界种子被收进可用区间，并且 [customSeedLightnessIsClamped] 如实报告。 */
    @Test
    fun `accent lightness is clamped into the usable band and the clamp is reported`() {
        for (li in 0..20) {
            val seed = hslColor(210f, 0.8f, li / 20f)
            // 判据用**种子自己反解出来的**明度，而不是名义上的 li/20f：Color 是 8bit 打包的，
            // 名义值与实际值可能差一个台阶，拿名义值比会得到一个与实现无关的假失败。
            val seedL = seed.toHsl().lightness
            val p = buildCustomPalette(seed)
            val lightL = p.accentLight.toHsl().lightness
            val darkL = p.accentDark.toHsl().lightness
            assertTrue(
                "seedL=$seedL 的浅色 accent 明度 $lightL 落在可用区间外",
                lightL >= CUSTOM_ACCENT_LIGHT_L_MIN - CHANNEL_STEP &&
                    lightL <= CUSTOM_ACCENT_LIGHT_L_MAX + CHANNEL_STEP
            )
            assertTrue(
                "seedL=$seedL 的深色 accent 明度 $darkL 落在可用区间外",
                darkL >= CUSTOM_ACCENT_DARK_L_MIN - CHANNEL_STEP &&
                    darkL <= CUSTOM_ACCENT_DARK_L_MAX + CHANNEL_STEP
            )
            val clampedSomewhere = kotlin.math.abs(lightL - seedL) > CHANNEL_STEP * 2f ||
                kotlin.math.abs(darkL - seedL) > CHANNEL_STEP * 2f
            assertEquals(
                "seedL=$seedL：customSeedLightnessIsClamped 的答案必须与实际是否夹取一致 —— " +
                    "取色器靠它决定要不要给用户一句解释，报错了就成了「滑块坏了」",
                clampedSomewhere,
                customSeedLightnessIsClamped(seed)
            )
        }
        // 落在两个区间交集（0.42~0.70）里的种子必须**原样**成为 accent，不许被动手
        val untouched = hslColor(210f, 0.8f, 0.5f)
        val p = buildCustomPalette(untouched)
        for ((what, actual) in listOf("浅色 accent" to p.accentLight, "深色 accent" to p.accentDark)) {
            assertEquals("区间内的种子必须原样成为 $what（red）", untouched.red, actual.red, CHANNEL_STEP)
            assertEquals("区间内的种子必须原样成为 $what（green）", untouched.green, actual.green, CHANNEL_STEP)
            assertEquals("区间内的种子必须原样成为 $what（blue）", untouched.blue, actual.blue, CHANNEL_STEP)
        }
        assertFalse("区间内的种子不该被报告为已夹取", customSeedLightnessIsClamped(untouched))
    }

    /** 纯函数：同一种子必然得到逐位相同的 palette；alpha 被忽略。 */
    @Test
    fun `buildCustomPalette is deterministic and ignores the seed alpha`() {
        val seed = hslColor(275f, 0.62f, 0.48f)
        assertEquals(buildCustomPalette(seed), buildCustomPalette(seed))
        assertEquals(
            "种子的 alpha 必须被忽略 —— 带 alpha 的色槽会绕过 contrastRatio 的全部判据",
            buildCustomPalette(seed),
            buildCustomPalette(seed.copy(alpha = 0.3f))
        )
    }

    /** 自定义 palette 的身份：id / name 正确，且不混进预设表。 */
    @Test
    fun `custom palette carries the custom id and stays out of the preset table`() {
        val p = buildCustomPalette(hslColor(12f, 0.7f, 0.5f))
        assertEquals(CUSTOM_THEME_ID, p.id)
        assertEquals(CUSTOM_THEME_NAME, p.name)
        assertFalse(
            "自定义皮肤不许混进 allPresets（混进去就要给它编一组假色值，而它的色值来自用户）",
            ThemePresets.allPresets.any { it.id == CUSTOM_THEME_ID }
        )
    }

    /**
     * 反向锚点：[paletteContrastViolations] 真的会**报不合格**，而不是永远返回空表。
     *
     * 没有这条，上面所有 `violations.isEmpty()` 的断言都可能是因为判据实现坏了而恒真。
     *
     * 对照组取 [ThemePresets.Rose]：它自出厂起就是全项合格的一套。
     * 2026-09-08 起 [ThemePresets.Default] 也全项合格（三个 accent 槽的明暗方向修正之后），
     * 下面最后一个断言把这件事钉住 —— 它同时是「深色取色发灰」根因已修的回归护栏。
     */
    @Test
    fun `the contrast auditor actually rejects a bad palette`() {
        val bad = ThemePresets.Rose.copy(
            textSecondaryLight = Color(0xFFCCCCCC), // 压在白卡上约 1.6:1
            onAccentLight = Color(0xFFEE5577)       // 压在玫红实底上约 1.2:1
        )
        val violations = paletteContrastViolations(bad)
        assertTrue("刻意做坏的 palette 必须被判不合格", violations.isNotEmpty())
        assertTrue(
            "报告里必须点出是哪一项：$violations",
            violations.any { it.contains("副文案对卡面") }
        )
        assertTrue(
            "对照组（玫红）必须是全项合格的，否则这条测试没有意义：" +
                paletteContrastViolations(ThemePresets.Rose),
            paletteContrastViolations(ThemePresets.Rose).isEmpty()
        )
        val defaultViolations = paletteContrastViolations(ThemePresets.Default)
        assertTrue(
            "出厂默认皮肤现在必须全项合格。2026-09-08 之前它在「次强调色对卡面」上明暗各挂一项" +
                "（accentSecondaryLight `#E5E5E5` 对白卡 1.26:1、accentSecondaryDark `#555555` 对 " +
                "`#2A2A2A` 1.93:1）—— 与「深色模式下图标文字发灰」是同一个根因：三个 accent 槽的" +
                "明暗方向写反了。现已重标定为深色态取亮灰、浅色态取深灰。实际：$defaultViolations",
            defaultViolations.isEmpty()
        )
    }

    // ══════════════ 命名种子表 ══════════════
    //
    // 覆盖要求：纯黑 / 纯白 / 纯灰 / 极低饱和 / 极高饱和 / 六个主要色相各一个，
    // 外加三个"看着不像极端但实际最紧"的：中等明度灰（onColorFor 的死角所在）、
    // 极暗高饱和、极亮粉彩。

    private val namedSeeds: Map<String, Color> = linkedMapOf(
        "纯黑" to Color(0xFF000000),
        "纯白" to Color(0xFFFFFFFF),
        "纯灰 50%" to Color(0xFF808080),
        "中灰 33%" to Color(0xFF545454),
        "极低饱和 4%" to hslColor(150f, 0.04f, 0.5f),
        "极高饱和 红" to hslColor(0f, 1f, 0.5f),
        "色相 60 黄" to hslColor(60f, 1f, 0.5f),
        "色相 120 绿" to hslColor(120f, 1f, 0.5f),
        "色相 180 青" to hslColor(180f, 1f, 0.5f),
        "色相 240 蓝" to hslColor(240f, 1f, 0.5f),
        "色相 300 品红" to hslColor(300f, 1f, 0.5f),
        "极暗高饱和" to hslColor(210f, 1f, 0.06f),
        "极亮粉彩" to hslColor(330f, 1f, 0.94f),
        "低饱和暖灰" to Color(0xFF6E7480)
    )

    private fun Color.hex(): String = String.format(
        "#%02X%02X%02X",
        (red * 255f + 0.5f).toInt(),
        (green * 255f + 0.5f).toInt(),
        (blue * 255f + 0.5f).toInt()
    )

    private companion object {
        /**
         * 一个 8bit 通道台阶（`1/255`），用作全部"应该相等"断言的容差。
         *
         * `androidx.compose.ui.graphics.Color` 的 sRGB 实例是**每通道 8bit 打包**的
         * （不是保存浮点原值），所以 `hslColor(...)` 的输出、`toHsl()` 的反解、
         * 以及 ARGB 持久化往返各会引入最多半个台阶的误差，串起来最多一个台阶。
         *
         * 拿它当容差是安全的：本文件所有"应该相等"的断言，真出错时的差值都是几十个台阶
         * （例如某个中性槽被接到了种子明度上），一个台阶的容差挡不住任何真实回归。
         */
        const val CHANNEL_STEP: Float = 1f / 255f
    }
}
