package com.ufi_axis.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * 配色对比度的**纯函数**工具箱（无 Android 依赖，可在 JVM 单测里直接跑）。
 *
 * ## 为什么要有这个文件
 * 2026-09-05 之前，WCAG 对比度计算只存在于 `ColorTest` 的 private 扩展里。那时全仓只有一套
 * 皮肤、且没有任何**运行时**需要判对比度的地方，所以"只有测试会算"是成立的。
 * 本轮两件事同时打破了这个前提：
 *
 * 1. 皮肤从 1 套变成 9 套 —— 对比度判据要在 JVM 单测里**遍历** `ThemePresets.allPresets`
 *    逐套逐态验，判据实现必须只有一份（两份必然漂移）；
 * 2. Material You 动态取色 —— accent 完全由用户壁纸决定，**静态无法保证任何对比度**，
 *    所以 accent 之上的前景色必须在**运行时**算（见 [onColorFor]）。
 *
 * 运行时和测试共用同一份实现，是"UI 显示状态必须与判据一致"的前提。
 *
 * ## 三个阈值的出处
 * - [MIN_TEXT_CONTRAST] = 4.5 —— WCAG 2.1 §1.4.3 正文字号的 AA 门槛。**不放宽到 3:1**：
 *   3:1 只适用于 18pt 以上的大字，而 `textSecondary` 承载的是 12sp 的说明文案。
 * - [MIN_NON_TEXT_CONTRAST] = 3.0 —— WCAG 2.1 §1.4.11 非文本图形。图标是实心图形，
 *   不承担逐字辨认的负担。
 * - [MIN_SURFACE_DELTA_L] = 2.0 —— 这条**不是** WCAG 的东西，见 [surfacesAreDistinguishable]。
 */

/** 正文级前景色的最低对比度（WCAG 2.1 AA，§1.4.3）。 */
const val MIN_TEXT_CONTRAST: Double = 4.5

/** 图标 / 非文本图形的最低对比度（WCAG 2.1 §1.4.11）。 */
const val MIN_NON_TEXT_CONTRAST: Double = 3.0

/**
 * 两个**大面积表面**（卡面 vs 页面底）"肉眼可分"的判据：CIE L\* 差 ≥ 2.0。
 *
 * 为什么不用 WCAG 对比度：对比度是给"前景压在背景上"设计的，比值在近白区间极不敏感 ——
 * `#F8F8F8` 对 `#FFFFFF` 只有 1.06:1，看数字像是"完全没区别"，但真机上卡片边界清晰可见。
 * L\* 是**感知均匀**的明度标尺（0~100），大面积色块的 JND（刚可辨差异）约 1，
 * 取 2.0 留了一倍余量。锚点：出厂默认皮肤的浅色态 `#F8F8F8` vs `#FFFFFF` 实测 ΔL\* = 2.42，
 * 也就是说这条阈值刚好把"现有可接受观感"划在合格线内侧，不是凭空拍的数。
 */
const val MIN_SURFACE_DELTA_L: Double = 2.0

/**
 * WCAG 2.1 相对亮度：每通道先做 sRGB → 线性光的电光转换，再按 Rec.709 加权。
 *
 * 必须线性化 —— 直接用 sRGB 分量加权（也就是常见的"luma"）算出来的对比度会明显偏高，
 * 拿去和 3:1 / 4.5:1 这种**规范阈值**比较就成了自欺欺人。
 */
fun Color.wcagRelativeLuminance(): Double {
    fun channel(c: Float): Double {
        val v = c.toDouble()
        return if (v <= 0.03928) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4)
    }
    return 0.2126 * channel(red) + 0.7152 * channel(green) + 0.0722 * channel(blue)
}

/**
 * WCAG 对比度：`(亮 + 0.05) / (暗 + 0.05)`，结果恒 ≥ 1，且与两色顺序无关。
 *
 * 注意入参必须是**不透明实色**：半透明色的 alpha 不参与计算，
 * 带 alpha 的色要先 composite 到承载它的表面上（这正是 [ThemePresets] 里
 * `textSecondary` / `divider` 必须存实色而不是 alpha 的原因）。
 */
fun contrastRatio(a: Color, b: Color): Double {
    val la = a.wcagRelativeLuminance()
    val lb = b.wcagRelativeLuminance()
    return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
}

/**
 * CIE L\*（D65 白点下的感知明度，0 = 黑、100 = 白）。
 *
 * 只用于 [surfacesAreDistinguishable]。分段常数取 CIE 标准的 903.3 / 0.008856。
 */
fun perceivedLightness(color: Color): Double {
    val y = color.wcagRelativeLuminance()
    return if (y > 0.008856) 116.0 * Math.cbrt(y) - 16.0 else 903.3 * y
}

/** 两个大面积表面是否"肉眼可分"（判据见 [MIN_SURFACE_DELTA_L]）。 */
fun surfacesAreDistinguishable(a: Color, b: Color): Boolean =
    kotlin.math.abs(perceivedLightness(a) - perceivedLightness(b)) >= MIN_SURFACE_DELTA_L

/**
 * 在 [background] 之上挑一个前景色：从 [dark] / [light] 两个候选里选**对比度更高**的那个。
 *
 * 这是动态取色（Material You）的**对比度兜底**。动态 accent 由用户壁纸生成，
 * 可能是深靛蓝也可能是荧光黄，任何静态选择都会在另一半情况下不可读；
 * 而"哪个候选对比度更高"是可以在运行时算准的。
 *
 * 为什么不是"算亮度然后 if (luma > 0.5) 黑 else 白"那种写法：亮度阈值法在临界区
 * （中等明度的品红 / 青）会给出对比度不足的答案，而这里直接比较**最终判据本身**，
 * 不存在临界区问题。
 *
 * 候选刻意做成参数而不是写死黑白：调用点会传"近黑 / 近白"而不是纯黑纯白
 * （纯黑压在彩色实底上偏硬），把具体色留给调用点，本函数只负责裁决。
 *
 * @return [dark] 或 [light] 中对 [background] 对比度更高者；相等时返回 [dark]（确定性优先）。
 */
fun onColorFor(background: Color, dark: Color, light: Color): Color =
    if (contrastRatio(dark, background) >= contrastRatio(light, background)) dark else light

// ══════════════════════════════════════════════════════════════════════════════
// 混色：判据与渐变阶梯共用的唯一一份实现
// ══════════════════════════════════════════════════════════════════════════════

/**
 * Hero 渐变在**浅色态**的最亮停止点：accent 往白混 22%。
 *
 * 三处消费者（7 套预设 / 自定义皮肤 / 动态取色）与四张 Hero 卡的渐变**必须同一个数**：
 * 前者用它算 `onGradient` 的对比度判据，后者用它画实际像素。
 * 之前这个常量在 `CustomPalette.kt` 与 `DynamicPalette.kt` 各有一份拷贝，
 * 改一个漏一个会让"判据"和"画出来的东西"静默分叉，所以收在这里只留一份。
 *
 * 见 [heroGradientBrightestStop]（判据侧）与 [ufiShade]（渲染侧）。
 */
internal const val GRADIENT_TOP_LIGHTEN: Float = 0.22f

/** 分隔线的透明度档（7 套预设 / 自定义 / 动态取色同值，composite 成实色后存入色槽）。 */
internal const val DIVIDER_ALPHA: Float = 0.15f

/**
 * sRGB 分量线性混合，**结果恒不透明**（`alpha = 1f`）。
 *
 * 刻意不用 `androidx.compose.ui.graphics.lerp(Color, Color, Float)`：那个在 Oklab 空间插值，
 * 结果无法由十六进制值直接推算，评审和回归对色时不好核对；而这里要的只是"往某个方向混一档"，
 * 分量混合已足够，且与黑色混合恰好等价于 HSV 的 V 缩放（H、S 不变），行为可预测。
 *
 * ⚠️ **与 `ThemePalette.kt` 的 `srgbMix` 语义不同，不要合并**：那个保留 `from.alpha`
 * （`accentStrong` / `accentMuted` 要能作用在半透明输入上），本函数强制 `alpha = 1f`
 * （消费者是**色槽实色**与**渐变底色**，带 alpha 的色不参与 [contrastRatio] 计算，
 * 存进色槽就等于绕过全部判据）。两者恰好在不透明输入下结果相同，
 * 但那是巧合而不是契约 —— 合并会让"色槽必须是实色"这条约束悄悄消失。
 */
internal fun blendSrgb(from: Color, to: Color, ratio: Float): Color = Color(
    red = from.red + (to.red - from.red) * ratio,
    green = from.green + (to.green - from.green) * ratio,
    blue = from.blue + (to.blue - from.blue) * ratio,
    alpha = 1f
)

/**
 * Hero 渐变的**阶梯色**：正比例往白提亮、负比例往黑压暗，结果恒不透明。
 *
 * 四张 Hero 卡（首页 `HomeConnectionCard` / 网络 / 流量管理 / 监控 `MonitorOverview`）
 * 用同一组停止点：深色态 `-0.14 / -0.04 / 0`，浅色态 `0 / 0.12 / 0.22`。
 * 之前这段逻辑在四个 feature 模块里各有一份私有拷贝，其中 `MonitorOverview` 那份用的是
 * `androidx.compose.ui.graphics.lerp`（**Oklab 插值**）—— 同一组比例在 Oklab 与 sRGB 下
 * 算出的像素并不相同，于是"四张卡视觉统一"这个前提实际上早就不成立了。
 * 提到主题层只留一份之后，四卡的渐变才真的是同一条。
 *
 * 为什么放在 `ui/theme` 而不是某个 feature 模块：浅色态最亮停止点
 * （[GRADIENT_TOP_LIGHTEN]）同时是 `onGradient` 的**对比度判据输入**
 * （见 [heroGradientBrightestStop] 与 `ColorTest` / `CustomPaletteTest` 的断言），
 * "运行时画的"和"测试判的"必须是同一份实现。
 *
 * @param ratio ∈ `[-1, 1]`。`>= 0` 往 [Color.White] 混，`< 0` 往 [Color.Black] 混；
 *   绝对值即混合比例（越界夹取）。`0f` 返回原色的不透明版本。
 */
fun Color.ufiShade(ratio: Float): Color = blendSrgb(
    from = this,
    to = if (ratio >= 0f) Color.White else Color.Black,
    ratio = kotlin.math.abs(ratio).coerceIn(0f, 1f)
)

