package com.ufi_axis.ui.theme

import androidx.compose.ui.graphics.Color
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 「自定义」皮肤：用户只给**一个种子色**，本文件把它推成完整的 [ThemePalette]（17 个色槽）。
 *
 * ## 为什么需要一套专门的推导，而不是"把种子色塞进 accent 就完事"
 * 7 套预设皮肤的色值来自设计稿的**三个**输入（p = 主色 / d = 深色 / l = 浅色，
 * 对应 Tailwind 的 500 / 900 / 300 档），17 个槽由它们混出来。自定义只有一个 `p`，
 * 而且这个 `p` 是**任意**的 —— 可能是纯黑、纯白、纯灰、荧光黄、也可能是 4% 饱和的脏绿。
 * 「任意输入下仍要保证对比度」这个问题与 Material You 动态取色**完全同类**，
 * 而那边已经有解法（见 `DynamicPalette.kt` 的类 KDoc），所以这里**照抄它的纪律**：
 *
 * - `pageBg` / `cardBg` / `textPrimary` / `textSecondary` / `divider` / `iconTint`
 *   六个槽的**明度由公式钉死**，只允许带极轻的种子色染色（≤ 6% 饱和，见 [NEUTRAL_TINT_MAX]）。
 *   于是它们之间的对比度**与用户选什么颜色无关** —— 这是全部正文类硬指标的保证来源。
 * - `onAccent` / `onGradient` / `gradientMuted`（accent 实底之上的前景）在**运行时**算：
 *   从"近黑 / 近白"两个候选里挑对比度更高的那个（[onColorFor] 的口径），见 [pickOnColor]。
 * - `accent` / `accentSecondary` 是唯二真正跟随种子色的槽。它们**不承载正文**，
 *   所以可以放心跟色；accent 的明度只做一次**可用区间夹取**（见 [CUSTOM_ACCENT_LIGHT_L_MIN]）。
 *
 * ## 用哪个色彩空间：HSL
 * 推导全程在 **HSL** 里做（[toHsl] / [hslColor]），不用 OkLab / HSV，理由是**可判定性**：
 * - 本文件要做的事情只有两件 —— "把明度钉到某一档"和"把饱和度压到某一档"。
 *   HSL 的 `L` 直接就是"取值范围的中点"，`hslColor(h, s, 0.38f)` 的三个通道
 *   必然落在 `[0.38(1-s), 0.38(1+s)]` 这个**闭区间**里（`L ≤ 0.5` 时），
 *   于是给定 `s` 上界就能**解析地**算出该槽亮度的最坏取值，进而算出对比度下界。
 *   这正是 `CustomPaletteTest` 能声称"对全域种子成立"而不是"抽样没抽到坏例"的原因。
 * - OkLab 的 `L` 感知更均匀，但同一 `L` 下不同色相的**可表示 chroma 上限**不同，
 *   越界后要 gamut-map 回 sRGB，映射会同时改动 L —— "明度被钉死"这个前提就不成立了。
 * - HSV 的 `V` 是通道最大值，`V` 固定时纯色与白色的亮度差近 3 倍，根本钉不住明度。
 *
 * HSL 的代价是它不感知均匀（同一 `L` 下黄比蓝亮得多），所以**不能**拿 `L` 当对比度判据。
 * 本文件的做法是：用 HSL 把取值锁进一个窄区间，再用 WCAG 的 [contrastRatio] / [perceivedLightness]
 * 做真正的判据（[paletteContrastViolations]）—— 前者负责"可控"，后者负责"合规"。
 *
 * ## 边界输入为什么也成立
 * - **纯灰 / 零饱和**（`s = 0`）：染色倍率一律乘在 `s` 上，`s = 0` 时全部中性槽退化成纯灰阶，
 *   明度档位不变 ⇒ 对比度与彩色种子完全一致。accent 变成一片中性灰（≈ 出厂默认皮肤的观感）。
 * - **极暗 / 极亮**（`l → 0` 或 `l → 1`）：中性槽的 `l` 根本不读种子的 `l`（是常量档位），
 *   所以只有 accent 受影响，而 accent 的 `l` 被夹进可用区间
 *   （浅色态 [CUSTOM_ACCENT_LIGHT_L_MIN]~[CUSTOM_ACCENT_LIGHT_L_MAX]）。
 *   纯黑种子 ⇒ accent `#4D4D4D`，纯白种子 ⇒ accent `#B3B3B3`，都还是"看得见的实底色块"。
 * - **极低饱和**（如 4%）：`s` 小 ⇒ 染色更轻 ⇒ 更接近纯灰阶，是上面那条的连续过渡，无跳变。
 * - **中等明度**（accent 亮度落在 `Y ≈ 0.10~0.17` 那一段）：这是唯一有陷阱的区间 ——
 *   近黑候选对 accent 实底不够、近白候选对渐变最亮点不够。处置见 [pickOnColor]。
 */

/**
 * 自定义皮肤的持久化 id。
 *
 * 与 [DYNAMIC_THEME_ID] 的关键区别：`"dynamic"` **从不持久化**（动态取色是独立开关），
 * 而 `"custom"` 是一个**真正会被写进 SharedPreferences 的皮肤 id**
 * —— 它就是用户在皮肤网格里选的那一格。所以 `ThemePresets.normalizeThemeId("custom")`
 * 返回 `"custom"` 而不是折叠成默认，见 `ThemePresets.SELECTABLE_IDS`。
 */
const val CUSTOM_THEME_ID: String = "custom"

/** 自定义皮肤的显示名（皮肤网格里那一格的标题）。 */
const val CUSTOM_THEME_NAME: String = "自定义"

// ══════════════════════════════════════════════════════════════════════════════
// HSL：取色器的三个滑块直接映射到这三个分量
// ══════════════════════════════════════════════════════════════════════════════

/**
 * HSL 三分量。[hue] ∈ [0, 360)，[saturation] / [lightness] ∈ [0, 1]。
 *
 * 做成数据类而不是 `Triple<Float, Float, Float>`：取色器要把三个值分别接到三个滑块上，
 * `first / second / third` 在调用点完全读不出语义，接错两个分量的表现是"拖饱和度结果变明度"。
 */
data class Hsl(val hue: Float, val saturation: Float, val lightness: Float)

/**
 * sRGB → HSL。灰阶（三通道相等）时 [Hsl.hue] 取 0、[Hsl.saturation] 取 0。
 *
 * 灰阶的色相在数学上是**未定义**的（不是"等于 0"）。取 0 而不是 `NaN` 是刻意的：
 * 取色器会把这个值直接塞进色相滑块，`NaN` 会让滑块跳到轨道外；而 `s = 0` 时色相
 * 取什么都不影响颜色，所以 0 是一个安全的占位。副作用是"纯灰种子进取色器后
 * 色相滑块停在红端"，可接受 —— 用户一拖饱和度就会看到真实色相。
 */
fun Color.toHsl(): Hsl {
    val r = red
    val g = green
    val b = blue
    val max = maxOf(r, g, b)
    val min = minOf(r, g, b)
    val l = (max + min) / 2f
    val d = max - min
    if (d == 0f) return Hsl(hue = 0f, saturation = 0f, lightness = l)
    // 分母 (1 - |2L - 1|) 在 d != 0 时必然 > 0：全黑/全白都落进上面的 d == 0 分支
    val s = (d / (1f - abs(2f * l - 1f))).coerceIn(0f, 1f)
    var h = when (max) {
        r -> 60f * (((g - b) / d) % 6f)
        g -> 60f * ((b - r) / d + 2f)
        else -> 60f * ((r - g) / d + 4f)
    }
    if (h < 0f) h += 360f
    return Hsl(hue = h % 360f, saturation = s, lightness = l)
}

/** HSL → sRGB（不透明）。入参越界一律夹取，所以取色器不必自己限幅。 */
fun hslColor(hue: Float, saturation: Float, lightness: Float): Color {
    val s = saturation.coerceIn(0f, 1f)
    val l = lightness.coerceIn(0f, 1f)
    val c = (1f - abs(2f * l - 1f)) * s
    val hp = (((hue % 360f) + 360f) % 360f) / 60f
    val x = c * (1f - abs(hp % 2f - 1f))
    val (r1, g1, b1) = when (hp.toInt() % 6) {
        0 -> Triple(c, x, 0f)
        1 -> Triple(x, c, 0f)
        2 -> Triple(0f, c, x)
        3 -> Triple(0f, x, c)
        4 -> Triple(x, 0f, c)
        else -> Triple(c, 0f, x)
    }
    val m = l - c / 2f
    return Color(
        red = (r1 + m).coerceIn(0f, 1f),
        green = (g1 + m).coerceIn(0f, 1f),
        blue = (b1 + m).coerceIn(0f, 1f),
        alpha = 1f
    )
}

/** 便捷重载：直接吃 [Hsl]。 */
fun hslColor(hsl: Hsl): Color = hslColor(hsl.hue, hsl.saturation, hsl.lightness)

/**
 * 种子色 → 持久化用的 ARGB Int（alpha 强制 `0xFF`）。
 *
 * 手动打包而不是用 `Color.toArgb()`：后者会做色彩空间转换（本仓的 Color 都是 sRGB，
 * 转换是恒等的，但那是一个**隐含前提**），而且它在 `:app:ui` 的 JVM 单测里依赖
 * ui-graphics 的桌面实现。手算三行，行为在单测与真机上逐位一致。
 *
 * alpha 恒 `0xFF` 同时给了持久化层一个免费的哨兵：`0`（alpha = 0）不可能是合法种子，
 * 于是 `ThemeManager.CUSTOM_SEED_UNSET = 0` 可以表示"用户从未选过颜色"。
 */
fun customSeedToArgb(color: Color): Int {
    fun ch(v: Float): Int = (v.coerceIn(0f, 1f) * 255f).roundToInt().coerceIn(0, 255)
    return (0xFF shl 24) or (ch(color.red) shl 16) or (ch(color.green) shl 8) or ch(color.blue)
}

// ══════════════════════════════════════════════════════════════════════════════
// 推导常数
//
// 全部是 HSL 的**明度档位**或**混合比例**，逐条都能算出它对应的对比度下界
// （下界的实测值由 CustomPaletteTest 的全域扫描给出，写在每条后面）。
// 改任何一个之前请先跑 CustomPaletteTest —— 它会把全域最坏值重新算一遍。
// ══════════════════════════════════════════════════════════════════════════════

/**
 * accent 在**浅色态**的明度可用下限 / 上限。
 *
 * 为什么要夹取：accent 的职责是**实底色块**（主按钮 / FAB / 进度条已填充段 / Hero 渐变底），
 * 它必须能与承载它的表面分开。浅色态卡面是纯白（L\* = 100），种子色若是 `#FDFDFD`
 * 那块按钮就等于消失了；反过来 `l → 0` 的种子在深色页面上也一样消失。
 *
 * 取 0.30 / 0.70 的依据：L = 0.70 的灰 L\* ≈ 72.8，对白卡 ΔL\* ≈ 27（判据要求 ≥ 2.0，见
 * [MIN_SURFACE_DELTA_L]），余量 13 倍；再放宽到 0.80 时 ΔL\* 掉到 ~17，仍合规但按钮
 * 边界已经开始"发虚"，所以停在一个观感与判据都宽裕的档位而不是贴着及格线。
 *
 * 夹取是**公开可见**的（取色器会在预览里显示夹取后的真实 accent，并在越界时给出提示），
 * 不是静默改用户的颜色 —— 这正是"预览必须诚实"那条要求的落点。
 */
const val CUSTOM_ACCENT_LIGHT_L_MIN: Float = 0.30f

/** accent 浅色态明度上限，理由见 [CUSTOM_ACCENT_LIGHT_L_MIN]。 */
const val CUSTOM_ACCENT_LIGHT_L_MAX: Float = 0.70f

/**
 * accent 在**深色态**的明度可用下限。
 *
 * 比浅色态整体上移（0.42 vs 0.30）：深色态卡面是 L\* ≈ 13 的近黑，accent 要压在它上面
 * 才显得出来。L = 0.42 的灰 L\* ≈ 45，对深卡 ΔL\* ≈ 32。
 *
 * 这也是 7 套预设的做法在自定义里的对应物 —— 预设的深色态 accent 直接沿用 `p`
 * （Tailwind 500 档，明度天然落在 0.45~0.60），只有出厂默认那套刻意把它调亮了一档。
 */
const val CUSTOM_ACCENT_DARK_L_MIN: Float = 0.42f

/** accent 深色态明度上限，理由见 [CUSTOM_ACCENT_DARK_L_MIN]。 */
const val CUSTOM_ACCENT_DARK_L_MAX: Float = 0.78f

/**
 * 中性槽的染色强度：`tint = min(种子饱和度 × [NEUTRAL_TINT_GAIN], [NEUTRAL_TINT_MAX])`。
 *
 * 乘在种子饱和度上（而不是取常数）是为了让"低饱和种子 → 更接近纯灰阶"这条连续成立，
 * 纯灰种子（`s = 0`）时 tint 恰好为 0，中性槽退化成标准灰阶，没有跳变。
 *
 * 上限 6% 的依据：某个槽的 HSL 明度为 `L`、饱和度为 `s` 时，三个通道全部落在
 * `L ± s·min(L, 1-L)` 之内。`s ≤ 0.096`（= 6% × 最大倍率 1.6）时最深的正文槽
 * （`L` = [L_TEXT_SECONDARY_LIGHT] = 0.38）通道区间只有 `0.344~0.416`，
 * 亮度浮动 ±10%，把 4.5:1 的判据推到最坏也还有 5.5:1（全域扫描实测值）。
 * 再放宽到 12% 就会让"黄色系种子的副文案"掉到 4.5 以下 —— 这条不是估的，是扫出来的。
 */
private const val NEUTRAL_TINT_GAIN: Float = 0.08f

/** 中性槽染色饱和度的硬上限，理由见 [NEUTRAL_TINT_GAIN]。 */
private const val NEUTRAL_TINT_MAX: Float = 0.06f

/** 染色倍率：前景 / 分隔线（正文与图标要带一点色味才不像"贴上去的灰"）。 */
private const val TINT_SCALE_INK: Float = 1.6f

/** 染色倍率：近白纸面（页面底 + 深色态正文）。压得比 ink 轻，近白区间一点染色就很明显。 */
private const val TINT_SCALE_PAPER: Float = 0.6f

/** 染色倍率：深色态两个表面。放大到 2.2 —— 近黑区间的染色几乎看不出来，不放大就是纯黑。 */
private const val TINT_SCALE_DARK_SURFACE: Float = 2.2f

/** 染色倍率：`on*` 的近白候选。取最轻的一档，实底之上的前景越中性越稳。 */
private const val TINT_SCALE_ON_PAPER: Float = 0.4f

/** 浅色态正文 / 图标（"深墨"）的明度档。对白卡实测 ≥ 14.10:1。 */
private const val L_INK: Float = 0.145f

/** 近白纸面：浅色态页面底 + 深色态正文 / 图标。对深卡实测 ≥ 14.10:1；对白卡 ΔL\* ≥ 3.12。 */
private const val L_PAPER: Float = 0.965f

/** 浅色态副文案的明度档。对白卡实测 ≥ 5.50:1（门槛 4.5）。 */
private const val L_TEXT_SECONDARY_LIGHT: Float = 0.38f

/** 深色态副文案的明度档。对深卡实测 ≥ 7.38:1。 */
private const val L_TEXT_SECONDARY_DARK: Float = 0.70f

/** 深色态页面底的明度档。 */
private const val L_PAGE_DARK: Float = 0.072f

/** 深色态卡面的明度档。与 [L_PAGE_DARK] 的 ΔL\* 实测 6.30~8.26（门槛 2.0）。 */
private const val L_CARD_DARK: Float = 0.132f

/** `on*` 的近黑候选明度档。比 [L_INK] 更深 —— 它要对付的是 accent 实底，不是白卡。 */
private const val L_ON_INK: Float = 0.08f

/** `on*` 的近白候选明度档。比 [L_PAPER] 更亮，理由同 [L_ON_INK]。 */
private const val L_ON_PAPER: Float = 0.99f

// 分隔线的透明度档 [DIVIDER_ALPHA] 在 `ColorContrast.kt`（internal，7 套预设 / 自定义 /
// 动态取色同值）—— 本文件与 `DynamicPalette.kt` 曾各自复制一份，改一个漏一个会让
// 自定义皮肤与动态取色的分隔线静默分叉。


/**
 * `accentSecondaryLight` = accent 往 ink 混这么多。
 *
 * 0.50 不是随手取的整数：`accentSecondary` 有当**图标 tint** 的消费点
 * （`FileIcon` 的文件图标），压在白卡上必须 ≥ 3:1。混合结果的亮度上界出现在
 * "accent 是纯白"时 = `mix(白, ink, 0.50)` ≈ 0.54 的灰，对白卡 3.46:1；
 * 混 0.45 时上界降到 2.98:1 —— **正好过不了**。所以这一档是被判据反推出来的。
 */
private const val ACCENT_SECONDARY_INK_MIX: Float = 0.50f

/** `accentSecondaryDark` = accent 往 paper 混这么多。上界（accent 为纯黑时）对深卡 3.17:1。 */
private const val ACCENT_SECONDARY_PAPER_MIX: Float = 0.45f

// Hero 渐变最亮停止点的比例 [GRADIENT_TOP_LIGHTEN] 与 sRGB 混色 [blendSrgb] 都在
// `ColorContrast.kt`（internal，本包共用）。渲染侧的同一口径是 [ufiShade]：
// 浅色态最亮停止点 = `accent.ufiShade(GRADIENT_TOP_LIGHTEN)`，判据与像素只有一份实现。



// ══════════════════════════════════════════════════════════════════════════════
// 判据侧：Hero 渐变最亮停止点 + palette 体检
// ══════════════════════════════════════════════════════════════════════════════

/**
 * Hero 渐变的**最亮停止点** —— `on*` 类判据必须按它算，而不是按 accent 本身算。
 *
 * 口径与四张 Hero 卡真正画出来的像素同源（都走 [ufiShade]）：
 * - **浅色态**：`accent` 往白混 [GRADIENT_TOP_LIGHTEN]（22%），那是白字最容易失守的位置；
 * - **深色态**：那四处渐变是往黑混 4% / 14%，所以最亮点就是 `accent` 自己。
 *
 * 提升为生产代码（而不是继续留在 `ColorTest` 的 private 扩展里）的理由与
 * `ColorContrast.kt` 当初的提升完全相同：取色器的**确认闸门**要在运行时算这条判据，
 * 而"运行时判的"和"测试判的"必须是同一份实现 —— 两份必然漂移，
 * 漂移的表现是"单测全绿但真机上那套自定义配色不合格"。
 */
fun heroGradientBrightestStop(accent: Color, isDark: Boolean): Color =
    if (isDark) accent else accent.ufiShade(GRADIENT_TOP_LIGHTEN)


/**
 * 在**两个背景**上都要可读时挑前景色 —— [onColorFor] 的两背景版。
 *
 * ## 为什么不能直接用 [onColorFor]
 * 动态取色那边只按渐变最亮点算一次就够了，因为壁纸生成的 accent 亮度分布温和。
 * 自定义种子是**任意**的，会落进一个 [onColorFor] 处理不了的死角：
 *
 * `accent` 亮度 `Y ≈ 0.09`（约 `#545454` 这一档中灰）时，它的渐变最亮点被提亮到
 * `Y ≈ 0.19` —— 单看最亮点，近黑候选赢（`3.4:1` vs 近白的 `3.3:1`），于是选近黑；
 * 可近黑压在**未提亮的 accent 实底**上只有 `2.40:1`，`onAccent vs accent` 直接不合格。
 * 也就是说"对最亮点最优"的选择会把**另一个**背景牺牲掉。
 *
 * ## 处置
 * 改为最大化**两个背景上对比度的最小值**（maximin）：
 * 对每个候选取它在两个背景上的较差值，然后选较差值更大的那个候选。
 * 判据仍然是 [contrastRatio]，与 [onColorFor] 同一把尺子，只是把"一个背景"换成"取最坏"。
 *
 * ## 为什么这样一定能算出合格解
 * 近黑候选（[L_ON_INK]）亮度 `Y_ink ≈ 0.0072`、近白候选（[L_ON_PAPER]）`Y_paper ≈ 0.977`。
 * 记 accent 亮度 `Y_a`、最亮停止点 `Y_g`（`Y_g ≥ Y_a`），则
 * 近黑的最坏对比度 = `(Y_a + 0.05) / 0.0572`，近白的最坏对比度 = `1.027 / (Y_g + 0.05)`。
 * 两者同时低于 3:1 需要 `Y_a < 0.123` 且 `Y_g > 0.292`；而"往白混 22%"这个映射
 * 在 `Y_a = 0.123` 时把 `Y_g` 最多推到 **0.232**（最坏色相是纯灰，彩色更低），
 * 离 0.292 还差一截 ⇒ 那个交集是**空的**，至少一个候选必然 ≥ 3:1。
 * 全域扫描实测下界 3.35:1（见 `CustomPaletteTest`），与上面的解析结论一致。
 *
 * @return 两个候选中"最坏情况对比度"更高者；相等时返回 [dark]（确定性优先，同 [onColorFor]）。
 *
 * 背景用 `List<Color>` 而不是 `vararg`：`Color` 是 `@JvmInline value class`，
 * Kotlin 禁止值类作为 vararg 元素类型（`Prohibited vararg parameter type 'Color'`）。
 */
private fun pickOnColor(dark: Color, light: Color, backgrounds: List<Color>): Color {
    val darkWorst = backgrounds.minOf { contrastRatio(dark, it) }
    val lightWorst = backgrounds.minOf { contrastRatio(light, it) }
    return if (darkWorst >= lightWorst) dark else light
}

// ══════════════════════════════════════════════════════════════════════════════
// 种子色 → ThemePalette
// ══════════════════════════════════════════════════════════════════════════════

/**
 * 单个种子色 → 完整 [ThemePalette] 的**纯函数**（无 Android 依赖，可在 JVM 单测直接调）。
 *
 * 槽位来源一览（`p` = 种子色的 HSL、`tint` = `min(p.s × 0.08, 0.06)`）：
 *
 * | 槽 | 浅色态 | 深色态 |
 * |---|---|---|
 * | `accent` | `hsl(p.h, p.s, clamp(p.l, .30, .70))` | `hsl(p.h, p.s, clamp(p.l, .42, .78))` |
 * | `accentSecondary` | `mix(accent, ink, 50%)` | `mix(accent, paper, 45%)` |
 * | `pageBg` | `paper` = `hsl(p.h, tint×0.6, .965)` | `hsl(p.h, tint×2.2, .072)` |
 * | `cardBg` | `#FFFFFF` | `hsl(p.h, tint×2.2, .132)` |
 * | `textPrimary` / `iconTint` | `ink` = `hsl(p.h, tint×1.6, .145)` | `paper` |
 * | `textSecondary` | `hsl(p.h, tint×1.6, .38)` | `hsl(p.h, tint×1.6, .70)` |
 * | `divider` | `mix(cardBg, ink, 15%)` | `mix(cardBg, paper, 15%)` |
 * | `onAccent` / `onGradient` / `gradientMuted` | [pickOnColor] 运行时算 | 同 |
 *
 * 关键点：中间那五行**完全不读 `p.l`**，只读 `p.h` / `p.s`（而 `p.s` 只用来算 tint，
 * 上界 6%）。所以"用户选了什么颜色"影响不到任何正文类对比度 —— 这就是
 * `CustomPaletteTest` 能对全域种子成立的结构性原因，不是靠调参碰出来的。
 *
 * 未覆盖的槽（`error` / `warning` / `success` / `switchTrackOff` …）沿用 [ThemePalette]
 * 的默认值，与动态取色一致：那些是**语义色**，不该跟着用户的审美走
 * （把"错误"渲染成用户挑的粉色是一个可用性问题，不是个性化）。
 *
 * @param seed 用户选的种子色。alpha 被忽略（推导出的所有槽都是不透明实色 ——
 *   带 alpha 的色不参与 [contrastRatio] 计算，存进色槽就等于绕过全部判据）。
 */
fun buildCustomPalette(seed: Color): ThemePalette {
    val p = seed.toHsl()
    val tint = (p.saturation * NEUTRAL_TINT_GAIN).coerceAtMost(NEUTRAL_TINT_MAX)

    val accentLight = hslColor(
        p.hue, p.saturation,
        p.lightness.coerceIn(CUSTOM_ACCENT_LIGHT_L_MIN, CUSTOM_ACCENT_LIGHT_L_MAX)
    )
    val accentDark = hslColor(
        p.hue, p.saturation,
        p.lightness.coerceIn(CUSTOM_ACCENT_DARK_L_MIN, CUSTOM_ACCENT_DARK_L_MAX)
    )

    // ── 中性梯度：明度全部是常量档，只带 tint 级别的色味 ──
    val ink = hslColor(p.hue, tint * TINT_SCALE_INK, L_INK)
    val paper = hslColor(p.hue, tint * TINT_SCALE_PAPER, L_PAPER)
    val cardLight = Color.White
    val pageDark = hslColor(p.hue, tint * TINT_SCALE_DARK_SURFACE, L_PAGE_DARK)
    val cardDark = hslColor(p.hue, tint * TINT_SCALE_DARK_SURFACE, L_CARD_DARK)

    // ── accent 实底之上的前景：唯一在运行时算的一组 ──
    val onInk = hslColor(p.hue, tint, L_ON_INK)
    val onPaper = hslColor(p.hue, tint * TINT_SCALE_ON_PAPER, L_ON_PAPER)
    // 浅色态要同时对付 accent 实底与被提亮 22% 的渐变最亮点，见 pickOnColor 的 KDoc。
    val onAccentLight = pickOnColor(
        dark = onInk,
        light = onPaper,
        backgrounds = listOf(
            accentLight,
            heroGradientBrightestStop(accentLight, isDark = false)
        )
    )
    // 深色态渐变往黑混，最亮点就是 accent 本身 ⇒ 只有一个背景。
    val onAccentDark = pickOnColor(dark = onInk, light = onPaper, backgrounds = listOf(accentDark))

    return ThemePalette(
        id = CUSTOM_THEME_ID,
        name = CUSTOM_THEME_NAME,
        accentLight = accentLight,
        accentDark = accentDark,
        accentSecondaryLight = blendSrgb(accentLight, ink, ACCENT_SECONDARY_INK_MIX),
        accentSecondaryDark = blendSrgb(accentDark, paper, ACCENT_SECONDARY_PAPER_MIX),
        pageBgLight = paper,
        pageBgDark = pageDark,
        cardBgLight = cardLight,
        cardBgDark = cardDark,
        textPrimaryLight = ink,
        textPrimaryDark = paper,
        textSecondaryLight = hslColor(p.hue, tint * TINT_SCALE_INK, L_TEXT_SECONDARY_LIGHT),
        textSecondaryDark = hslColor(p.hue, tint * TINT_SCALE_INK, L_TEXT_SECONDARY_DARK),
        dividerLight = blendSrgb(cardLight, ink, DIVIDER_ALPHA),
        dividerDark = blendSrgb(cardDark, paper, DIVIDER_ALPHA),
        iconTintLight = ink,
        iconTintDark = paper,
        onAccentLight = onAccentLight,
        onAccentDark = onAccentDark,
        onGradientLight = onAccentLight,
        onGradientDark = onAccentDark,
        gradientMutedLight = onAccentLight,
        gradientMutedDark = onAccentDark
    )
}

/**
 * 种子色的明度是否会被 [buildCustomPalette] 夹取（[CUSTOM_ACCENT_LIGHT_L_MIN] 那一段）。
 *
 * 取色器用它决定要不要显示"已把明度收进可用区间"的提示 —— 夹取本身是必要的
 * （理由见 [CUSTOM_ACCENT_LIGHT_L_MIN]），但**不能静默发生**：用户把明度滑块拖到底
 * 却看到预览色块不再变化，如果没有一句解释，那就是一个"坏了"的滑块。
 */
fun customSeedLightnessIsClamped(seed: Color): Boolean {
    val l = seed.toHsl().lightness
    return l < CUSTOM_ACCENT_LIGHT_L_MIN || l > CUSTOM_ACCENT_LIGHT_L_MAX ||
        l < CUSTOM_ACCENT_DARK_L_MIN || l > CUSTOM_ACCENT_DARK_L_MAX
}

// ══════════════════════════════════════════════════════════════════════════════
// 对比度体检：运行时闸门与单测共用同一份实现
// ══════════════════════════════════════════════════════════════════════════════

/**
 * 逐条量一遍**与 7 套预设完全相同**的对比度硬指标，返回所有**不合格项**的说明。
 * 返回空列表 = 全部达标。
 *
 * 五条判据与它们的出处（与 `ColorTest.every preset meets the contrast floor in both modes`
 * 逐条对齐 —— 自定义皮肤**不是**"因为算不准所以放宽标准"的例外）：
 * 1. `textPrimary` vs `cardBg` ≥ [MIN_TEXT_CONTRAST]（4.5，WCAG 2.1 §1.4.3 正文 AA）
 * 2. `textSecondary` vs `cardBg` ≥ [MIN_TEXT_CONTRAST] —— 刻意不放宽到 3:1，
 *    副文案走 12sp 的 `UfiTextStyles.note`，不属于"大字"豁免
 * 3. `iconTint` vs `cardBg` ≥ [MIN_NON_TEXT_CONTRAST]（3.0，§1.4.11 非文本图形）
 * 4. `cardBg` vs `pageBg` 的 CIE L\* 差 ≥ [MIN_SURFACE_DELTA_L]（2.0，**不是** WCAG 对比度，
 *    理由见 [surfacesAreDistinguishable] 的 KDoc）
 * 5. `onAccent` vs accent 实底、`onGradient` vs [heroGradientBrightestStop] 都 ≥ 3.0
 *
 * ## 为什么运行时还要再算一遍
 * [buildCustomPalette] 的结构已经保证了全部五条对任意种子成立（见该函数与 [pickOnColor]
 * 的 KDoc，以及 `CustomPaletteTest` 的全域扫描）。这个函数**不是**为了补那个洞，
 * 它是取色器"确认"按钮的闸门：本仓的硬规则是**不许静默给出一个不合格的配色**，
 * 而"我证明过所以不用检查"在推导常数被人改动之后就不再成立了。
 * 一旦有人调了某个 `L_*` 档位而没跑单测，用户至少会在取色器里看到"确认"是灰的 + 原因，
 * 而不是拿到一套读不清的界面。
 *
 * 顺带也量 `textPrimary` vs `pageBg`（有些设置行直接铺在页面底上）与
 * `accentSecondary` vs `cardBg`（`FileIcon` 的文件图标 tint），两条都按 3:1 判。
 */
fun paletteContrastViolations(palette: ThemePalette): List<String> {
    val violations = mutableListOf<String>()
    for (isDark in listOf(false, true)) {
        val p = palette.resolve(isDark)
        val mode = if (isDark) "深色" else "浅色"
        fun check(actual: Double, floor: Double, what: String) {
            if (actual < floor) {
                violations += "$mode 态 $what ${"%.2f".format(actual)}:1，低于 ${"%.1f".format(floor)}:1"
            }
        }
        check(contrastRatio(p.textPrimary, p.cardBg), MIN_TEXT_CONTRAST, "正文对卡面")
        check(contrastRatio(p.textSecondary, p.cardBg), MIN_TEXT_CONTRAST, "副文案对卡面")
        check(contrastRatio(p.iconTint, p.cardBg), MIN_NON_TEXT_CONTRAST, "图标对卡面")
        check(contrastRatio(p.textPrimary, p.pageBg), MIN_NON_TEXT_CONTRAST, "正文对页面底")
        check(contrastRatio(p.accentSecondary, p.cardBg), MIN_NON_TEXT_CONTRAST, "次强调色对卡面")
        check(contrastRatio(p.onAccent, p.accent), MIN_NON_TEXT_CONTRAST, "实底前景对强调色")
        check(
            contrastRatio(p.onGradient, heroGradientBrightestStop(p.accent, isDark)),
            MIN_NON_TEXT_CONTRAST,
            "渐变前景对渐变最亮点"
        )
        val deltaL = abs(perceivedLightness(p.cardBg) - perceivedLightness(p.pageBg))
        if (!surfacesAreDistinguishable(p.cardBg, p.pageBg)) {
            violations += "$mode 态 卡面与页面底的 CIE L* 差 ${"%.2f".format(deltaL)}，" +
                "低于 ${"%.1f".format(MIN_SURFACE_DELTA_L)}"
        }
    }
    return violations
}





