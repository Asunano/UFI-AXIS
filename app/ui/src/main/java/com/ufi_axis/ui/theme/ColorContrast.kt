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
 * - [MIN_SURFACE_DELTA_L_DARK] = 6.0 —— 深色态专用的表面层次下限（2026-09-24 新增），
 *   理由是 2.0 那条在深色态从来没拦住过任何东西，见它自己的 KDoc。
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
 * **深色态专用**的表面层次下限：卡面与页面底的 CIE L\* 差 ≥ 6.0（2026-09-24 新增）。
 *
 * ## 为什么深色态需要单独一条
 * [MIN_SURFACE_DELTA_L] = 2.0 是按**浅色态**的锚点定的（`#F8F8F8` vs `#FFFFFF` 实测 2.42），
 * 它在深色态**从来没拦住过任何东西**：本次审计前 7 套预设深色态的实测最小值是玫红 **5.59**，
 * 也就是 2.8 倍余量 —— 而用户当天反馈的正是"玫红深色模式背景不美观"，
 * 根因就是这个 5.59（全表最小，卡片浮不起来）。**缺陷存在、测试全绿**，
 * 说明 2.0 这条在深色态不是"宽松"，而是彻底失效。
 *
 * 失效的原因不是数取小了，而是判据的适用区间不同：L\* 虽然是感知均匀的，
 * 但深色端同样的 ΔL\* 对应的物理亮度差极小（L\* 5.3 → 12.9 只是 Y 0.006 → 0.015），
 * 真机上还要再叠一层"暗环境 + 屏幕自带的低亮度非线性"。浅色态靠 2.42 就能看出卡片边界，
 * 深色态要靠"卡面明显亮一档"才能把卡片托起来，两者不该共用一个数。
 *
 * ## 6.0 这个数的锚点
 * 沿用 [MIN_SURFACE_DELTA_L] 当年的定法 —— **锚点取"现有可接受观感"**：
 * 本次修复后 7 套预设深色态的 ΔL\* 分布是
 * 宝蓝 **6.46** < 金黄 7.17 < 紫色 7.50 < 玫红 7.58 < 默认 7.80 < 柠绿 9.12 < 翠绿 9.16。
 * 最紧的宝蓝（本批**没有**调整、用户也没反馈过它的层次问题）就是锚点，门槛取 6.0，余量 7%。
 * 同时 6.0 会把修复前的玫红 5.59 与紫色 5.73 判成红灯 ——
 * 这是选值的第二个硬条件：**这条阈值必须真的能抓住当初那个缺陷**，否则它只是又一条摆设。
 *
 * ## ⚠ 红灯的正确处置
 * 抬 `cardBgDark` 的明度（per-preset 覆盖，或按 L\* 反解重算整条推导式），
 * **不是放宽本阈值**。深色态的层次只能由"面"表达：
 * 加重分隔线 / 描边只会让卡片更花而不是更立体（那正是 2026-09-24 A 项要解决的问题，
 * 见 [DIVIDER_ALPHA_DARK]）。
 *
 * ## 适用范围
 * 目前由 `ColorTest` 对 7 套**预设**逐套断言。自定义皮肤与动态取色仍走
 * [MIN_SURFACE_DELTA_L]：它们的深色两面本来就是按 L\* 常量档定的
 * （`CustomPalette.kt` 的 `L_PAGE_DARK` / `L_CARD_DARK`，实测 ΔL\* 6.30~8.26），
 * 不存在"统一混色系数在某些色相上产出明度偏低"这个病根 —— 那恰好是预设侧待做的 D 项。
 */
const val MIN_SURFACE_DELTA_L_DARK: Double = 6.0

/**
 * "线不许比面响"：`divider` 对 `cardBg` 的 ΔL\* 不得超过 `cardBg` 对 `pageBg` 的 ΔL\* 的 1.0 倍。
 *
 * ## 这条不变式是哪来的
 * 2026-09-24 审计"玫红深色模式不美观"时发现的真实病灶是**表面层次太弱、而线条过响**：
 * 玫红深色态 `ΔL*(divider, cardBg)` = 14.74，是 `ΔL*(cardBg, pageBg)` = 5.59 的 **2.64 倍**；
 * 同期默认皮肤这个比值是 **0.54**（线比面轻）—— 而默认皮肤恰恰是唯一没被抱怨过的一套。
 * 也就是说"干净"与"花"的区别不在某一个色值上，而在这个**比值**上：
 * 卡片要靠面的明度差浮起来，线只是分区提示；线比面重时，眼睛看到的是一堆格子而不是一张卡。
 *
 * 取 1.0（而不是直接照默认皮肤的 0.54）是因为 0.54 会把 6 套彩色皮肤全部判红 ——
 * 它们的 `divider` 是从卡面混白推出来的，同一个 alpha 在不同色相上落点不同。
 * 1.0 是"线不重于面"这句话的字面表达，是**方向性下限**而不是审美目标；
 * 修复后 7 套的实测比值是 0.50~0.92（最紧是宝蓝 0.92），仍有余量。
 *
 * ⚠ 红灯的处置与 [MIN_SURFACE_DELTA_L_DARK] 一致：调小 [DIVIDER_ALPHA_DARK] 或抬 `cardBgDark`，
 * 不是放宽这个比值 —— 放宽它等于把"线比面响"重新合法化。
 */
const val MAX_LINE_TO_SURFACE_DELTA_RATIO: Double = 1.0


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

/**
 * 两个表面的 CIE L\* 差（恒 ≥ 0）—— [surfacesAreDistinguishable] 的数值版。
 *
 * 2026-09-24 抽出来：深色态的两条新判据（[MIN_SURFACE_DELTA_L_DARK] 与
 * [MAX_LINE_TO_SURFACE_DELTA_RATIO]）需要的是**差值本身**而不是布尔结论
 * （前者要进报错信息、后者要参与比值），而调用点各自写
 * `abs(perceivedLightness(a) - perceivedLightness(b))` 会让"层次判据的算法"散成三四份。
 */
fun surfaceDeltaL(a: Color, b: Color): Double =
    kotlin.math.abs(perceivedLightness(a) - perceivedLightness(b))

/** 两个大面积表面是否"肉眼可分"（判据见 [MIN_SURFACE_DELTA_L]）。 */
fun surfacesAreDistinguishable(a: Color, b: Color): Boolean =
    surfaceDeltaL(a, b) >= MIN_SURFACE_DELTA_L

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

/**
 * Hero 渐变在**深色态**的最亮停止点：accent 往白混 16%。
 *
 * ## 沿革：这一档 2026-09-24 之前是"往黑混"，不是"往白混"
 * 深色态原先的三个停止点是 `-0.14 / -0.04 / 0`（往黑混 14% / 4% / 0），当天上午按真机反馈
 * （"深色模式下渐变太灰太暗"）先收到 `-0.06 / -0.02 / 0`，**最亮端仍是 accent 本身**。
 * 那一步刻意没有越过 accent，原因不是保守而是硬约束：`onGradient`（Hero 卡内**所有**
 * 文字与图标的颜色）的对比度判据 [heroGradientBrightestStop] 当时在深色分支**直接返回
 * accent**，理由正是"往黑混 ⇒ 最亮点就是 accent 自己"。一旦渐变往白提亮超过 accent，
 * 判据算的就不再是真实最亮点，而卡内文字恰恰是在**最亮**那一端最容易失守 ——
 * 表现是"单测全绿、真机上某几套皮肤的 Hero 卡文字糊在底上"。
 *
 * ## 这次为什么可以往白提亮
 * 因为判据**同步改了**：[heroGradientBrightestStop] 的深色分支现在返回
 * `accent.ufiShade(GRADIENT_TOP_LIGHTEN_DARK)`，与四张 Hero 卡真正画出来的最亮像素同源；
 * `buildCustomPalette` / `buildDynamicPalette` 的深色态 `on*` 选择也一起走这条判据
 * （自定义皮肤那侧还把提亮后的最亮点**加进了 `pickOnColor` 的背景列表**，
 * 见 `CustomPalette.kt`）。也就是说"画的"和"判的"仍然只有一份实现。
 *
 * ## 16% 这个数是怎么定的
 * 用户定稿口径是"明显变亮但不失去 accent 的识别度"，三档停止点因此是
 * `0 / GRADIENT_TOP_LIGHTEN_DARK/2 (8%) / GRADIENT_TOP_LIGHTEN_DARK (16%)`：
 * 最暗端就是 accent 本身（不再掺黑，这是"发灰发暗"的根治），最亮端 accent 混 16% 白。
 * 刻意**比浅色态的 22% 小**：深色态 accent 本身就落在明暗档的可读侧（偏亮），
 * 再按浅色态的幅度提亮会把白字皮肤（玫红 / 宝蓝 / 紫色）直接推到 3:1 边缘。
 * 16% 下**深色态**最紧的一项是玫红：`onGradient`（白）对最亮停止点 **3.37:1**
 * （门槛 3.0，余量 12%；全表最紧的仍是玫红**浅色态**的同一项 3.12:1）。
 * 对照：这一档若照浅色态取 22%，玫红深色态会掉到 3.12:1（余量 4%），没有抗量化的空间。
 * 自定义皮肤侧命名极端种子的实测下界是 **3.70:1**（中灰 33% 深色态，accent `#6B6B6B`），
 * 全域密集扫描无反例（见 `CustomPaletteTest`）。
 *
 * ## ⚠ 想再往上调必须一起动的三处
 * 1. 本常量；
 * 2. [heroGradientBrightestStop] 的深色分支（判据侧，否则判据与像素立刻分叉）；
 * 3. `ColorTest.every preset meets the contrast floor in both modes` 与
 *    `CustomPaletteTest` 的两条渐变断言 —— 它们守的是 `onGradient ≥ 3:1` 这条红线，
 *    **红灯的正确处置是把本常量调小，不是放宽阈值**。
 *
 * 渲染侧见 [ufiShade]（四张 Hero 卡与判据共用同一份混色实现）。
 */
const val GRADIENT_TOP_LIGHTEN_DARK: Float = 0.16f

/**
 * Hero 渐变在深色态的**中间**停止点：[GRADIENT_TOP_LIGHTEN_DARK] 的一半（8%）。
 *
 * 写成"最亮档的一半"而不是又一个字面量：三档停止点是等距的（0 / 8% / 16%），
 * 调最亮档时中间档必须跟着动，两个独立字面量迟早会漂移成不等距的阶梯。
 */
const val GRADIENT_MID_LIGHTEN_DARK: Float = GRADIENT_TOP_LIGHTEN_DARK / 2f

/**
 * 分隔线的透明度档 —— **浅色态**：往 ink（近黑）混 15%（7 套预设 / 自定义 / 动态取色同值，
 * composite 成实色后存入色槽）。
 *
 * 2026-09-24 从原来的单常量 `DIVIDER_ALPHA = 0.15f` **拆成深浅两个**，本档保持 15% 不变。
 *
 * ## 为什么必须拆（不要再合回去）
 * 两态的分隔线是往**相反方向**混的：浅色态往黑混、深色态往白混。同一个比例在两个方向上
 * 产出的 ΔL\* 完全不是一回事 ——
 * - 浅色态：`#FFFFFF` 卡面混 15% 黑 ⇒ ΔL\*(divider, cardBg) ≈ 9.9，而 ΔL\*(cardBg, pageBg) = 2.42，
 *   线**必须**比面响，否则白卡在近白页面底上根本没有边界（这是浅色态卡片唯一的界定手段）；
 * - 深色态：卡面本身就比页面底亮一档（ΔL\* 6.5~9.2），层次由"面"表达，
 *   线再响就变成一堆格子 —— 15% 白在玫红上做出 ΔL\* 14.74，是面差 5.59 的 **2.64 倍**。
 *
 * 也就是说这两处共用一个常量时，"改这个数"在两态里是两种相反的诉求：
 * 深色态要往下调，浅色态一动就会让白卡失去边界。合并等于把两个独立判据绑在一起。
 * 深色档见 [DIVIDER_ALPHA_DARK]。
 */
internal const val DIVIDER_ALPHA_LIGHT: Float = 0.15f

/**
 * 分隔线的透明度档 —— **深色态**：往 paper（近白）混 **6%**（原 15%，2026-09-24 按用户定稿下调）。
 *
 * ## 原值为什么是 15%
 * 2026-09-05 设计稿给的是"分隔线 = 白 15% 叠在卡面上"，浅色态的 15% 黑与它是同一个数，
 * 当时就直接两态共用了一个常量。浅色态那一档是对的（见 [DIVIDER_ALPHA_LIGHT]），
 * 深色态则从一开始就偏重，只是没有任何判据在守"线不许比面响"，所以没人发现。
 *
 * ## 这次为什么改
 * 用户反馈"玫红配色深色模式背景不美观"。实测推翻了"对比度不够"这个前提
 * （深色态 `textPrimary` 对 `pageBg` 达 17.6~18.8:1，AAA 门槛只要 7.0），真正的病灶是
 * **面太弱、线太响**：
 * - `ΔL*(divider, cardBg) / ΔL*(cardBg, pageBg)`：玫红 14.74 / 5.59 = **2.64**，
 *   宝蓝 2.35、紫色 2.61、金黄 2.04、柠绿 1.53、翠绿 1.52；
 * - 而唯一没被抱怨的**默认皮肤**这个比值是 **0.54**（`dividerDark` = `#333333` 相对
 *   `cardBgDark` = `#2A2A2A` 只等效 4.2% 白）—— 默认皮肤"看着干净"的原因就在这里。
 *
 * ## 6% 这个数是怎么定的（用户批准的口径是 7%，实测后收到 6%）
 * 判据是 [MAX_LINE_TO_SURFACE_DELTA_RATIO]：比值必须 < 1。按 7% 算，
 * 本批**未调整卡面**的宝蓝会落在 **1.12**（它的面差只有 6.46，是修复后全表最小的一套）——
 * 也就是 7% 达不到用户自己定的"1 倍以下"这个目标。6% 下全表比值是
 * 默认 0.54 / 翠绿 0.60 / 柠绿 0.61 / 玫红 0.70 / 紫色 0.77 / 金黄 0.85 / **宝蓝 0.92**，
 * 全部合格且仍能看见分隔线（最小 ΔL\* = 玫红 5.30，远高于大面积色块的 JND ≈ 1）。
 * 想回到 7% 的前提是先把宝蓝的 `cardBgDark` 一起抬上去（属于 D 项：按 L\* 反解卡面明度）。
 *
 * ⚠ **只改了深色档**。浅色态那档一律不动 —— 理由见 [DIVIDER_ALPHA_LIGHT]，
 * 浅色态的白卡靠线界定，动它会让卡片失去边界。
 */
internal const val DIVIDER_ALPHA_DARK: Float = 0.06f

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
 * 用同一组停止点：深色态 `0 / +0.08 / +0.16`（2026-09-24 定稿，原为 `-0.14 / -0.04 / 0`，
 * 系数来自 [GRADIENT_MID_LIGHTEN_DARK] / [GRADIENT_TOP_LIGHTEN_DARK]，
 * 改动理由见那两个常量的 KDoc），浅色态 `0 / 0.12 / 0.22`。
 * 之前这段逻辑在四个 feature 模块里各有一份私有拷贝，其中 `MonitorOverview` 那份用的是
 * `androidx.compose.ui.graphics.lerp`（**Oklab 插值**）—— 同一组比例在 Oklab 与 sRGB 下
 * 算出的像素并不相同，于是"四张卡视觉统一"这个前提实际上早就不成立了。
 * 提到主题层只留一份之后，四卡的渐变才真的是同一条。
 *
 * 为什么放在 `ui/theme` 而不是某个 feature 模块：两态的最亮停止点
 * （[GRADIENT_TOP_LIGHTEN] / [GRADIENT_TOP_LIGHTEN_DARK]）同时是 `onGradient` 的
 * **对比度判据输入**
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

