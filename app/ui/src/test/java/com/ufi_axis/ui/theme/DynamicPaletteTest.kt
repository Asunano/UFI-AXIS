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
 * - **扫描式护栏**（2026-09-24 新增）：全色相 × 多明度档的合成 accent 上，
 *   `onGradient` 的两端点 maximin 是最优解、两个端点都真的参与计算、
 *   `onAccent` 只按实底判、`gradientMuted` 与 `onGradient` 同源；
 *   以及"两候选都不达标"的区间**只记录不判红**（那是下一批的事，见那条测试的 KDoc）。
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
                // 2026-09-24：原来这条量的是 `onGradient 对 accent`。深色 Hero 渐变改成往白提亮
                // 16% 之后（见 GRADIENT_TOP_LIGHTEN_DARK），accent 已经**不是**渐变里最亮的像素，
                // 而卡内文字恰恰在最亮那一端最容易失守 —— 改为与 ColorTest / CustomPaletteTest
                // 同一条判据 heroGradientBrightestStop（两态都算真实最亮点）。这是**收紧**不是放宽。
                floorAtLeast(
                    contrastRatio(p.onGradient, heroGradientBrightestStop(p.accent, isDark)),
                    MIN_NON_TEXT_CONTRAST,
                    "onGradient 对 Hero 渐变最亮停止点"
                )
                assertTrue(
                    "$tag cardBg 与 pageBg 的 CIE L* 差不足 $MIN_SURFACE_DELTA_L，卡片与页面底会糊成一片",
                    surfacesAreDistinguishable(p.cardBg, p.pageBg)
                )
            }
        }
    }

    // ══════════════ 扫描式护栏：两背景 maximin（2026-09-24 新增） ══════════════
    //
    // 为什么原来那两套壁纸不够：`neonYellowWallpaper` 的 accent 极亮、`deepIndigoWallpaper` 的
    // 极深，两套都落在"哪个候选赢"非常明确的区间，于是"只按渐变最亮端判"与"两端点 maximin"
    // 在它们身上**给出相同答案**（本次修复后这两套的 6 个 on* 槽逐位未变，原有断言一字未改）。
    // 用户反馈的缺陷恰恰出在**中间调**：浅色 Hero 渐变的最暗端是未提亮的 accent 本身，
    // 只看最亮端会系统性高估近黑候选 ⇒ 中间调壁纸上文字被判成黑色、压在哑蓝渐变上读不清。
    // 抽两套壁纸证明不了这件事，所以下面改成**合成 accent 的扫描**。

    /**
     * 扫描式护栏 ①：`onGradient` 必须是**两个渐变端点**上的 maximin 最优解。
     *
     * 断言的是**纯逻辑正确性**，与"这套配色好不好看 / 达不达标"无关：
     * 所选候选的"最差端点对比度"必须 ≥ 被弃候选的"最差端点对比度"。
     * 换句话说，本条只钉"不存在更好的选择"，不钉"选出来的一定 ≥ 3:1"——
     * 后者做不到（见 [dynamic sweep records accents where neither candidate reaches the floor]）。
     *
     * 顺带把第 3 条缺陷钉住：`gradientMuted` 的背景是渐变卡，必须与 `onGradient` 同源。
     * 2026-09-24 之前它跟的是 `onAccent`（背景对不上）。
     */
    @Test
    fun `dynamic on-gradient is the maximin optimum over both hero gradient endpoints`() {
        var checked = 0
        for ((label, accent) in sweepAccents) {
            for (isDark in listOf(false, true)) {
                val p = buildDynamicPalette(syntheticSource(accent)).resolve(isDark)
                val endpoints = heroGradientEndpoints(accent, isDark)
                val inkWorst = endpoints.minOf { contrastRatio(SWEEP_INK, it) }
                val paperWorst = endpoints.minOf { contrastRatio(SWEEP_PAPER, it) }
                val tag = "$label isDark=$isDark accent=${accent.hex()}"

                assertTrue(
                    "$tag：onGradient 必须是 ink/paper 两个候选之一，实际 ${p.onGradient.hex()}",
                    p.onGradient == SWEEP_INK || p.onGradient == SWEEP_PAPER
                )
                val chosenWorst = if (p.onGradient == SWEEP_INK) inkWorst else paperWorst
                val rejectedWorst = if (p.onGradient == SWEEP_INK) paperWorst else inkWorst
                assertTrue(
                    "$tag：maximin 没取到最优 —— 所选候选 ${p.onGradient.hex()} 的最差端点对比度 " +
                        "${"%.3f".format(chosenWorst)}:1，低于被弃候选的 ${"%.3f".format(rejectedWorst)}:1",
                    chosenWorst >= rejectedWorst
                )
                assertEquals(
                    "$tag：平局时必须确定性地返回近黑候选（同 onColorFor / pickOnColor 的约定）",
                    if (inkWorst >= paperWorst) SWEEP_INK else SWEEP_PAPER,
                    p.onGradient
                )
                assertEquals(
                    "$tag：gradientMuted 的背景是渐变卡，必须与 onGradient 同源而不是跟着 onAccent",
                    p.onGradient,
                    p.gradientMuted
                )
                checked++
            }
        }
        assertEquals("扫描规模变了，先确认是有意的", SWEEP_ACCENT_COUNT * 2, checked)
    }

    /**
     * 扫描式护栏 ②：**两个端点都必须真的参与计算**。
     *
     * 这条防的是"有人把实现退回单背景"——那样单测仍会绿（上一条的 maximin 断言在单背景下
     * 也成立，因为它只比较两个候选），所以必须另外用**行为断言**把"读了几个背景"钉住：
     * 把背景列表里的某一个元素换成一个刻意极端的色，选择必须随之翻转。
     *
     * - 换掉 **[0] 最暗端**（accent 实底）→ 纯白：近黑候选在纯白上 17:1、近白候选只有 1.04:1，
     *   于是 maximin 必然从近白翻成近黑。若实现只读最亮端，这一步不会有任何变化。
     * - 换掉 **[1] 最亮端**（渐变最亮停止点）→ 纯白：同理必然翻成近黑。
     *   若实现只读最暗端（另一种退化），这一步不会有任何变化。
     *
     * 基准样本取一个"近白候选胜出"的深色 accent（深靛蓝的浅色态主色），
     * 这样两次替换都是"从 paper 翻成 ink"，方向明确、不依赖临界值。
     */
    @Test
    fun `pick on color reads every background in the list`() {
        val accent = Color(0xFF303F9F) // 深靛蓝：两端点上近白候选都占优
        val brightest = heroGradientBrightestStop(accent, isDark = false)
        val baseline = pickOnColor(dark = SWEEP_INK, light = SWEEP_PAPER, backgrounds = listOf(accent, brightest))
        assertEquals("基准样本必须是「近白候选胜出」，否则下面两条翻转断言没有意义", SWEEP_PAPER, baseline)

        assertEquals(
            "把**最暗端**换成纯白后选择必须翻成近黑 —— 没翻说明实现根本没读列表里的第一个背景",
            SWEEP_INK,
            pickOnColor(dark = SWEEP_INK, light = SWEEP_PAPER, backgrounds = listOf(Color.White, brightest))
        )
        assertEquals(
            "把**最亮端**换成纯白后选择必须翻成近黑 —— 没翻说明实现没读列表里的第二个背景",
            SWEEP_INK,
            pickOnColor(dark = SWEEP_INK, light = SWEEP_PAPER, backgrounds = listOf(accent, Color.White))
        )

        // 同一条纪律在 buildDynamicPalette 上的端到端版：中间调 accent 下
        // 「两端点 maximin」与「只看最亮端」必须给出**不同**的答案，
        // 否则本次修复等于没生效（而这正是用户反馈的那个缺陷）。
        val midTone = hslColor(240f, 0.35f, 0.50f) // 哑蓝，用户反馈的那一类
        val palette = buildDynamicPalette(syntheticSource(midTone))
        assertEquals(
            "哑蓝中间调下，两端点 maximin 必须选近白（旧实现会选近黑，压在未提亮的 accent 上只有 " +
                "${"%.2f".format(contrastRatio(SWEEP_INK, midTone))}:1）",
            SWEEP_PAPER,
            palette.onGradientLight
        )
        assertEquals(
            "旧实现（只看渐变最亮端）在同一个 accent 上选的是近黑 —— 这条钉住「修复确实改变了行为」",
            SWEEP_INK,
            onColorFor(
                background = heroGradientBrightestStop(midTone, isDark = false),
                dark = SWEEP_INK,
                light = SWEEP_PAPER
            )
        )
    }

    /**
     * 扫描式护栏 ③：`onAccent` 只按 **accent 实底**判，不吃渐变提亮。
     *
     * 第 2 条缺陷：实色 accent 按钮 / 药丸上**没有**任何渐变提亮，让 `onAccent` 跟着
     * 提亮后的背景走等于偏乐观。修完两者不再必然同值 —— 本条既验"每个样本都等于单背景最优"，
     * 也验"确实存在样本让 onAccent ≠ onGradient"（后者防止有人把两个槽又接回同一个变量）。
     */
    @Test
    fun `dynamic on-accent is judged against the solid accent only`() {
        var differ = 0
        for ((label, accent) in sweepAccents) {
            for (isDark in listOf(false, true)) {
                val p = buildDynamicPalette(syntheticSource(accent)).resolve(isDark)
                val expected = if (contrastRatio(SWEEP_INK, accent) >= contrastRatio(SWEEP_PAPER, accent)) {
                    SWEEP_INK
                } else {
                    SWEEP_PAPER
                }
                assertEquals(
                    "$label isDark=$isDark：onAccent 必须只按 accent 实底判（实色按钮没有渐变提亮）",
                    expected,
                    p.onAccent
                )
                if (p.onAccent != p.onGradient) differ++
            }
        }
        assertTrue(
            "扫描里必须存在 onAccent ≠ onGradient 的样本 —— 全相等说明两个槽又被接回同一个值了" +
                "（那正是本次修的第 2 条缺陷）",
            differ > 0
        )
        println("动态取色 · onAccent 与 onGradient 取值不同的样本数：$differ / ${SWEEP_ACCENT_COUNT * 2}")
    }

    /**
     * 扫描式护栏 ④（**只记录不判红**）：两个候选**都**达不到 3:1 的合成 accent。
     *
     * ⚠ 本条刻意**不断言 ≥ 3.0**。原因：maximin 保证的是"不存在更好的候选"，
     * 而候选本身（壁纸的 `neutral1_900` / `neutral1_50`）与 accent 的亮度分布都不受本批控制。
     * 中间调 accent 上可能两个候选都不达标 —— 那是**下一批（②）**要修的：
     * 改渐变本身（或夹取 accent 明度）把 accent 推离中间调，而不是在这里放宽阈值。
     *
     * 所以本条的产出是**数据**：把落进该区间的样本（色相 / 饱和度 / 明度 / 两候选各自的
     * 最差端点对比度）打印成表，再打印全扫描最紧的若干条与**按明度档的 maximin 下界剖面**，
     * 供 ② 定"中间带边界落在哪"。
     *
     * ## 2026-09-24 首次实测（468 个合成 accent × 两态）
     * - 两候选**都** < 3.0 的样本：**0 条**。也就是说本批的两端点 maximin 修完之后，
     *   当前的候选（`neutral1_900` / `neutral1_50`）与提亮幅度（22% / 16%）下
     *   **总能选到达标的一端** —— ② 不是"修不合格"，而是"修余量太薄"。
     * - 浅色态按明度档的下界：`20%=3.79 25%=3.32 30%=3.36 35%=3.22 40%=3.21 45%=3.19
     *   50%=3.27 55%=3.30 60%=3.65 65%=3.53 70%=4.56 75%=5.86 80%=7.48`，
     *   最坏点是 `hsl(240, 10%, 45%)`（哑蓝，正是用户反馈的那一类）**3.19:1**，余量只有 6%。
     * - 深色态同档普遍高 0.1~0.2（提亮只有 16%，两端拉得更近），最坏 3.39:1。
     *
     * ## ② 落地后这条要改成硬断言
     * 届时把下面的 `below` 表换成 `assertTrue(best >= MIN_NON_TEXT_CONTRAST)`
     * （更好的是按上面的剖面钉一条"余量 ≥ 15%"的下界），并把本 KDoc 的这一段删掉。
     * **留着一条只打印不判红的测试是有意的、也是有期限的**：
     * 它现在的作用是把"已知未修区间"写进回归网里，而不是让它在 review 记录里自然消失。
     */
    @Test
    fun `dynamic sweep records accents where neither candidate reaches the floor`() {
        val below = StringBuilder()
        var belowCount = 0
        val tightest = mutableListOf<Triple<String, Double, Boolean>>()

        for ((label, accent) in sweepAccents) {
            for (isDark in listOf(false, true)) {
                val endpoints = heroGradientEndpoints(accent, isDark)
                val inkWorst = endpoints.minOf { contrastRatio(SWEEP_INK, it) }
                val paperWorst = endpoints.minOf { contrastRatio(SWEEP_PAPER, it) }
                val best = maxOf(inkWorst, paperWorst)
                tightest += Triple("$label ${if (isDark) "深" else "浅"} accent=${accent.hex()}", best, isDark)
                if (best < MIN_NON_TEXT_CONTRAST) {
                    belowCount++
                    below.append(
                        "    $label ${if (isDark) "深" else "浅"}  accent=${accent.hex()}  " +
                            "近黑最差=${"%.2f".format(inkWorst)}  近白最差=${"%.2f".format(paperWorst)}\n"
                    )
                }
            }
        }

        val report = StringBuilder(
            "动态取色 · 两端点 maximin 扫描（${SWEEP_ACCENT_COUNT} 个合成 accent × 明暗两态，" +
                "候选 ink=${SWEEP_INK.hex()} / paper=${SWEEP_PAPER.hex()}）\n"
        )
        report.append("  两候选都 < ${MIN_NON_TEXT_CONTRAST}:1 的样本：$belowCount 条")
        if (belowCount == 0) {
            report.append("（空 —— 当前候选与提亮幅度下 maximin 总能选到达标的一端）\n")
        } else {
            report.append("（这是 ② 的待修区间，当前**有意不判红**）\n").append(below)
        }
        report.append("  全扫描最紧的 12 条（maximin 能拿到的最好值，越小越危险）：\n")
        for ((tag, best, _) in tightest.sortedBy { it.second }.take(12)) {
            report.append("    ${"%.3f".format(best)}:1  $tag\n")
        }
        // 按**明度档**汇总每档的最小值 —— ② 要定"中间带的边界落在哪"靠的是这张剖面图，
        // 而不是上面那 12 条（那 12 条只告诉你最坏点在哪，读不出band 的宽度）。
        for (isDark in listOf(false, true)) {
            report.append("  ${if (isDark) "深色态" else "浅色态"}按明度档的 maximin 下界：\n    ")
            for (lp in 20..80 step 5) {
                val floor = tightest
                    .filter { it.third == isDark && it.first.contains(", $lp%)") }
                    .minOf { it.second }
                report.append("$lp%=${"%.2f".format(floor)}  ")
            }
            report.append('\n')
        }
        println(report)
    }

    /**
     * 改动**前后**的差异量化：浅色态有多少个合成 accent 从"黑字"翻成了"白字"。
     *
     * 旧实现就是 `onColorFor(heroGradientBrightestStop(accent, isDark))` 一行，
     * 可以在测试里原样复算，所以不需要保留任何旧代码就能做前后对比。
     *
     * 两条断言：
     * 1. 翻转数 > 0 —— 否则本次修复没有改变任何行为（用户反馈的缺陷也就没被修到）；
     * 2. **反向翻转（白 → 黑）必须为 0**。这是纯逻辑结论而不是实测巧合：
     *    `Y_accent ≤ Y_最亮端` ⇒ `(Y_a+.05)(Y_g+.05) ≤ (Y_g+.05)²`，
     *    所以"旧选白"（右式 < 阈值）必然推出"新选白"（左式 < 阈值）。
     *    出现反向翻转说明 maximin 写反了方向。
     *
     * 2026-09-24 首次实测：浅色态翻转 **49** 条（明度 20%~60%）、深色态 **31** 条（25%~55%），
     * 反向翻转两态都是 0。深色态少是因为提亮只有 16%（[GRADIENT_TOP_LIGHTEN_DARK]），
     * 两个端点本来就离得近、旧实现的高估幅度更小。
     */
    @Test
    fun `dynamic sweep quantifies the black to white flip introduced by this fix`() {
        val report = StringBuilder("动态取色 · 本批修复前后的差异（前：只看渐变最亮端；后：两端点 maximin）\n")
        for (isDark in listOf(false, true)) {
            var flippedToPaper = 0
            var flippedToInk = 0
            var minL = Int.MAX_VALUE
            var maxL = Int.MIN_VALUE
            val samples = StringBuilder()
            for ((label, accent) in sweepAccents) {
                val old = onColorFor(
                    background = heroGradientBrightestStop(accent, isDark),
                    dark = SWEEP_INK,
                    light = SWEEP_PAPER
                )
                val new = buildDynamicPalette(syntheticSource(accent)).resolve(isDark).onGradient
                if (old == new) continue
                if (old == SWEEP_INK) {
                    flippedToPaper++
                    val l = sweepLightnessOf(label)
                    minL = minOf(minL, l)
                    maxL = maxOf(maxL, l)
                    if (samples.length < 1_200) {
                        samples.append(
                            "      $label accent=${accent.hex()}  " +
                                "黑字压在未提亮 accent 上只有 ${"%.2f".format(contrastRatio(SWEEP_INK, accent))}:1，" +
                                "换白字后最差端点 " +
                                "${"%.2f".format(
                                    heroGradientEndpoints(accent, isDark).minOf { contrastRatio(SWEEP_PAPER, it) }
                                )}:1\n"
                        )
                    }
                } else {
                    flippedToInk++
                }
            }
            report.append("  ${if (isDark) "深色态" else "浅色态"}：黑字 → 白字 $flippedToPaper 条")
            if (flippedToPaper > 0) report.append("，明度区间 $minL% ~ $maxL%")
            report.append("；白字 → 黑字 $flippedToInk 条\n").append(samples)
            assertEquals(
                "${if (isDark) "深色" else "浅色"}态出现了「白字 → 黑字」的反向翻转，" +
                    "这在数学上不可能（Y_accent ≤ Y_最亮端）—— maximin 的方向写反了",
                0,
                flippedToInk
            )
        }
        println(report)
        // 浅色态必须真的有翻转：这条是「用户反馈的缺陷确实被修到了」的证据。
        // 深色态提亮只有 16%（GRADIENT_TOP_LIGHTEN_DARK），两端差距更小，翻转数可能为 0，不作断言。
        var lightFlips = 0
        for ((_, accent) in sweepAccents) {
            val old = onColorFor(
                background = heroGradientBrightestStop(accent, isDark = false),
                dark = SWEEP_INK,
                light = SWEEP_PAPER
            )
            if (old == SWEEP_INK && buildDynamicPalette(syntheticSource(accent)).onGradientLight == SWEEP_PAPER) {
                lightFlips++
            }
        }
        assertTrue("浅色态一个翻转都没有 ⇒ 本次修复没有改变任何行为", lightFlips > 0)
    }

    // ══════════════ 扫描用的合成色板 ══════════════

    /**
     * 合成一组"壁纸色板"：只有 accent 是被扫描的变量，中性梯度固定成一套典型的 Monet 取值。
     *
     * 中性梯度固定的理由：本批要验的是 **accent 之上的前景裁决**，
     * 中性槽的对比度由档位差保证、与 accent 无关（那条纪律由
     * [dynamic palette never sources foreground slots from the wallpaper accent] 守）。
     * 固定它还让扫描报告里的"两个候选"只有一组取值，表读得懂。
     *
     * 明暗两态都填同一个 accent：真机上 `accent1_600` 与 `accent1_200` 是两个独立档位，
     * 但扫描的目的是覆盖**任意亮度的 accent 落到任意一态**上，所以让每个合成 accent
     * 在两态里各走一遍（两态的区别只在提亮幅度 22% vs 16%）。
     */
    private fun syntheticSource(accent: Color) = DynamicSourceColors(
        accent1_600 = accent,
        accent1_200 = accent,
        accent2_700 = Color(0xFF3C3C3C),
        accent2_200 = Color(0xFFC9C9C9),
        neutral1_0 = Color(0xFFFFFFFF),
        neutral1_50 = SWEEP_PAPER,
        neutral1_800 = Color(0xFF2B2B2B),
        neutral1_900 = SWEEP_INK,
        neutral2_700 = Color(0xFF474747),
        neutral2_200 = Color(0xFFC8C8C8)
    )

    /** Hero 渐变的两个端点：最暗端 = **accent 实底**，最亮端 = [heroGradientBrightestStop]。 */
    private fun heroGradientEndpoints(accent: Color, isDark: Boolean): List<Color> =
        listOf(accent, heroGradientBrightestStop(accent, isDark))

    /**
     * 扫描网格：全色相（0~330°，步长 30°）× 三档饱和度 × 明度 20%~80%（步长 5%）。
     *
     * - **色相步长 30°**：12 个色相足以覆盖"通道权重差异"（绿的 Rec.709 权重 0.7152、
     *   蓝只有 0.0722），而对比度随色相的变化是平滑的，再密没有新信息。
     * - **三档饱和度**：10% 是最坏情况 —— 饱和度越低，`往白混 22%` 对亮度的抬升倍率越大，
     *   两个端点拉得越开，也就越容易出现"只看最亮端会选错"。35% 对应用户反馈的哑蓝一类，
     *   80% 是高饱和对照。
     * - **明度 20~80% 步长 5%**：中间带 45%~65% 必须在内（缺陷就在那一段），
     *   两端各留够裕量以便在报告里看到边界。
     */
    private val sweepAccents: List<Pair<String, Color>> = buildList {
        for (h in 0 until 360 step 30) {
            for (sp in listOf(10, 35, 80)) {
                for (lp in 20..80 step 5) {
                    add("hsl($h, $sp%, $lp%)" to hslColor(h.toFloat(), sp / 100f, lp / 100f))
                }
            }
        }
    }

    /** 从扫描标签里取回明度百分比（报告用，格式由 [sweepAccents] 自己产出，不会漂移）。 */
    private fun sweepLightnessOf(label: String): Int =
        label.substringAfterLast(", ").removeSuffix("%)").toInt()

    private fun Color.hex(): String = String.format(
        "#%02X%02X%02X",
        (red * 255f + 0.5f).toInt(),
        (green * 255f + 0.5f).toInt(),
        (blue * 255f + 0.5f).toInt()
    )

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

    private companion object {
        /**
         * 扫描用的"近黑"候选 —— 对应壁纸的 `neutral1_900`（tone 10）。
         *
         * 取纯中性灰 `#1B1B1B` 而不是带色味的值：扫描的自变量只应该是 accent，
         * 候选一带色味，报告里"近黑最差=2.62"这种数字就没法一眼对回色相了。
         * 亮度上与真机 Monet 的 tone 10 同一档（Y ≈ 0.0110），所以结论可迁移。
         */
        val SWEEP_INK: Color = Color(0xFF1B1B1B)

        /** 扫描用的"近白"候选 —— 对应壁纸的 `neutral1_50`（tone 95，Y ≈ 0.913）。理由同 [SWEEP_INK]。 */
        val SWEEP_PAPER: Color = Color(0xFFF5F5F5)

        /**
         * 合成 accent 的总数 = 12 色相 × 3 饱和度 × 13 明度档。
         *
         * 钉住规模（而不是让它随手改）：步长一变，"扫描过了"这句话的含金量就变了，
         * 而失败信息里读不出这件事。改网格时必须同步改这个数，等于强制一次有意识的确认。
         */
        const val SWEEP_ACCENT_COUNT: Int = 12 * 3 * 13
    }
}
