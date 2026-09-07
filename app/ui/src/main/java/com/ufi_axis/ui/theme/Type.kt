package com.ufi_axis.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

/**
 * 字体集（可整体替换）。当前默认 = 系统字体（设备默认，中文走系统回退）。
 *
 * 演进说明：早期版本用 Space Grotesk / Manrope / JetBrains Mono 自定义 ttf，并在注释中禁用系统字体；
 * 实际界面出现"部分 Roboto、部分系统"的混排，故统一收敛为系统字体，三个 ttf 资源已删除。
 *
 * **替换时机限制：仅启动期可替换，运行时无效。**
 * [Typography] 是单次构建的顶层 `val`，第一次被触达时就把当前字体集固化了进去。因此 [setFontSet]
 * 必须在首屏 `UFIAXISTheme` 组合**之前**调用（例如 `Application.onCreate`），此后调用不会有任何效果。
 * 若日后确实需要运行时切换字体，得把 [Typography] 改成读 [currentFontSet] 的 `get()` 重建
 * —— 代价是每次访问都重建 Typography，需先确认调用频次可接受。
 *
 * 接入自定义字体（**不局限于系统字体**）：实现 [FontSet]，用
 * `Font(R.font.xxx)`（资源 ttf）或 `Font(android.graphics.Typeface.createFromFile(path))`（动态下载）
 * 装载 display/body/mono 三族，再调用 [setFontSet] 注入即可 —— 各调用点无需改动。
 */
interface FontSet {
    val display: FontFamily
    val body: FontFamily
    val mono: FontFamily
}

object SystemFontSet : FontSet {
    override val display: FontFamily = FontFamily.Default
    override val body: FontFamily = FontFamily.Default
    override val mono: FontFamily = FontFamily.Monospace
}

/** 当前生效字体集（默认系统字体）。自定义字体通过 [setFontSet] 注入。 */
var currentFontSet: FontSet = SystemFontSet
    private set

/**
 * 注入自定义字体集（不局限于系统字体）。
 *
 * **必须在首屏 `UFIAXISTheme` 组合之前调用**（推荐 `Application.onCreate`）。
 * [Typography] 首次被触达即固化字体集，之后调用本函数不会有任何效果 —— 详见 [FontSet] 的说明。
 */
fun setFontSet(set: FontSet) {
    currentFontSet = set
}

private val DisplayFont: FontFamily get() = currentFontSet.display
private val BodyFont: FontFamily get() = currentFontSet.body

/** 等宽字体，用于信号 dBm / IP / 速率 / 时间戳等遥测数字（当前 = 系统等宽，可经 FontSet 替换）。 */
val MonoFont: FontFamily get() = currentFontSet.mono

val Typography = Typography(
    headlineLarge = TextStyle(
        fontFamily = DisplayFont,
        fontWeight = FontWeight.Bold,
        fontSize = 28.sp,
        lineHeight = 34.sp
    ),
    headlineMedium = TextStyle(
        fontFamily = DisplayFont,
        fontWeight = FontWeight.Bold,
        fontSize = 24.sp,
        lineHeight = 30.sp
    ),
    // 2026-09-01：原先没定义 headlineSmall，但 NetworkModeScreen 在用它 —— 会回退到 M3 默认样式，
    // 绕开本文件的字号阶梯。补成 22sp（填 titleLarge 20 与 headlineMedium 24 之间的空档），
    // 该处字号由 24 → 22。（原注释提到的"禁用 Roboto"约定已随统一系统字体废止。）
    headlineSmall = TextStyle(
        fontFamily = DisplayFont,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 28.sp
    ),
    titleLarge = TextStyle(
        fontFamily = DisplayFont,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 26.sp
    ),    titleMedium = TextStyle(
        fontFamily = DisplayFont,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp,
        letterSpacing = 0.1.sp
    ),
    titleSmall = TextStyle(
        fontFamily = DisplayFont,
        fontWeight = FontWeight.Bold,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = BodyFont,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = BodyFont,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp
    ),
    bodySmall = TextStyle(
        fontFamily = BodyFont,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.2.sp
    ),
    labelLarge = TextStyle(
        fontFamily = DisplayFont,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.3.sp
    ),
    labelMedium = TextStyle(
        fontFamily = BodyFont,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.3.sp
    ),
    labelSmall = TextStyle(
        fontFamily = BodyFont,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.3.sp
    )
)

/**
 * 语义字重阶梯。业务代码**不要写 `FontWeight.Bold` 这类字面量**，写这里的语义名 ——
 * 以后想把"全站强调文字"从 Bold 调成 SemiBold，只改这一处。
 *
 * 2026-09-01 批 4 建立。原状是全库 208 处 `fontWeight = FontWeight.X` 散落在调用点。
 */
object UfiWeight {
    /** 正文常规 */
    val Regular = FontWeight.Normal
    /** 轻度强调（列表项主文案） */
    val Medium = FontWeight.Medium
    /** 中度强调（卡片标题、被选中项） */
    val Emphasis = FontWeight.SemiBold
    /** 强强调（页面标题、关键读数） */
    val Strong = FontWeight.Bold
    /** 超强（仪表盘主数字） */
    val Hero = FontWeight.ExtraBold
    /** 极限（仅装饰性徽标） */
    val Max = FontWeight.Black
}

/**
 * 语义排版集：把「M3 样式 + 字重 + 特殊字号」打包成"用在什么地方"的名字。
 *
 * 用法：`Text(text, style = UfiTextStyles.cardTitle)`，调用点不再出现 `fontWeight` / `fontSize`。
 * 所有样式都从上面的 [Typography] 派生（`get()` 而非 `val` 缓存），
 * 所以**改 [Typography] 的字号 / 字体 / 行高，全站自动跟随**。
 *
 * 长尾（全库只出现 1~2 次的奇异组合）不必在这里加名字，用
 * `UfiTextStyles.note.copy(fontWeight = UfiWeight.Strong)` 即可 —— 字重仍受 [UfiWeight] 统一控制。
 *
 * 2026-09-01 批 4 建立（方案甲）。命名规则：无后缀 = 该族默认字重，
 * `…Emphasis` = 中度，`…Strong` = 强，`…Hero` = 超强。
 */
object UfiTextStyles {

    // ---- 辅助说明（labelSmall 11sp，全站最高频，约 123 处）----
    /** 辅助说明文字：单位、副标、状态描述 */
    val caption: TextStyle get() = Typography.labelSmall
    /** 需要强调的小字：状态值、数量 */
    val captionEmphasis: TextStyle get() = Typography.labelSmall.copy(fontWeight = UfiWeight.Emphasis)
    /** 强调小字（原 labelSmall + Bold） */
    val captionStrong: TextStyle get() = Typography.labelSmall.copy(fontWeight = UfiWeight.Strong)
    /** 徽标数字（更小一档，10sp） */
    val captionTiny: TextStyle get() = Typography.labelSmall.copy(fontSize = 10.sp)
    /** 极小徽标（9sp 超粗，仅角标） */
    val badgeTiny: TextStyle get() = Typography.labelSmall.copy(fontSize = 9.sp, fontWeight = UfiWeight.Hero)

    // ---- 次要正文（bodySmall 12sp，约 100 处）----
    /** 次要正文 / 提示行 */
    val note: TextStyle get() = Typography.bodySmall
    /** 次要正文强调 */
    val noteEmphasis: TextStyle get() = Typography.bodySmall.copy(fontWeight = UfiWeight.Emphasis)
    /** 紧凑次要正文（11sp，密集列表用） */
    val noteCompact: TextStyle get() = Typography.bodySmall.copy(fontSize = 11.sp)
    /** 放大次要正文（13sp，介于 note 与 body 之间的历史档位） */
    val noteLead: TextStyle get() = Typography.bodySmall.copy(fontSize = 13.sp)

    // ---- 标准正文（bodyMedium 14sp，约 120 处）----
    /** 标准正文 */
    val body: TextStyle get() = Typography.bodyMedium
    /** 正文轻强调：列表主文案 */
    val bodyEmphasis: TextStyle get() = Typography.bodyMedium.copy(fontWeight = UfiWeight.Medium)
    /** 正文强强调 */
    val bodyStrong: TextStyle get() = Typography.bodyMedium.copy(fontWeight = UfiWeight.Strong)

    // ---- 引导正文（bodyLarge 16sp）----
    /** 引导 / 空态说明正文 */
    val bodyLead: TextStyle get() = Typography.bodyLarge
    /** 引导正文强调 */
    val bodyLeadStrong: TextStyle get() = Typography.bodyLarge.copy(fontWeight = UfiWeight.Strong)

    // ---- 标签（labelMedium 12sp / labelLarge 13sp）----
    /** 表单标签、图例 */
    val label: TextStyle get() = Typography.labelMedium
    /** 标签强调（选中态） */
    val labelStrong: TextStyle get() = Typography.labelMedium.copy(fontWeight = UfiWeight.Strong)
    /** 胶囊 / chip 文字（13sp） */
    val tag: TextStyle get() = Typography.labelLarge
    /** 胶囊文字强调 */
    val tagStrong: TextStyle get() = Typography.labelLarge.copy(fontWeight = UfiWeight.Strong)

    // ---- 标题族 ----
    /** 卡片标题（14sp SemiBold） */
    val cardTitle: TextStyle get() = Typography.titleSmall.copy(fontWeight = UfiWeight.Emphasis)
    /** 分组标题（14sp Bold，titleSmall 默认字重） */
    val sectionTitle: TextStyle get() = Typography.titleSmall
    /** 面板标题（16sp SemiBold，titleMedium 默认字重） */
    val panelTitle: TextStyle get() = Typography.titleMedium
    /** 面板标题强调（16sp Bold） */
    val panelTitleStrong: TextStyle get() = Typography.titleMedium.copy(fontWeight = UfiWeight.Strong)
    /** 页面 / 弹窗大标题（20sp Bold） */
    val screenTitle: TextStyle get() = Typography.titleLarge.copy(fontWeight = UfiWeight.Strong)
    /** 页面大标题·中度（20sp SemiBold，titleLarge 默认字重） */
    val screenTitleEmphasis: TextStyle get() = Typography.titleLarge
    /** 弹窗主标题（22sp SemiBold） */
    val dialogTitle: TextStyle get() = Typography.headlineSmall

    // ---- 顶栏（原 Spacing.HeaderTitleSize / HeaderSubtitleSize，2026-09-01 从 Spacing 迁来）----
    // 注意：这两个刻意沿用 bodyLarge 派生，与迁移前逐像素一致；
    // 若日后想让顶栏用展示族（需先让 FontSet 的 display/body 真正不同），改这两行即可。
    /** 顶栏标题（22sp Bold，字距 0.56） */
    val headerTitle: TextStyle
        get() = Typography.bodyLarge.copy(
            fontSize = 22.sp,
            fontWeight = UfiWeight.Strong,
            letterSpacing = 0.56.sp
        )
    /** 顶栏副标题（14sp 常规） */
    val headerSubtitle: TextStyle get() = Typography.bodyLarge.copy(fontSize = 14.sp)

    // ---- 读数（仪表盘 / 首页大数字）----
    /** 大号读数（24sp Bold） */
    val heroValue: TextStyle get() = Typography.headlineMedium
    /** 仪表盘主读数（28sp 超粗） */
    val metricValue: TextStyle get() = Typography.titleLarge.copy(fontSize = 28.sp, fontWeight = UfiWeight.Hero)
    /** 主读数次级尺寸（24sp Bold） */
    val metricValueLarge: TextStyle get() = Typography.titleLarge.copy(fontSize = 24.sp, fontWeight = UfiWeight.Strong)
    /** 主读数紧凑尺寸（19sp Bold，窄卡片用） */
    val metricValueCompact: TextStyle get() = Typography.titleLarge.copy(fontSize = 19.sp, fontWeight = UfiWeight.Strong)
    /** 数值/标题强调（titleLarge 原生字号 + Bold） */
    val valueStrong: TextStyle get() = Typography.titleLarge.copy(fontWeight = UfiWeight.Strong)

    // ---- 文本按钮 / 分页操作 ----
    /** 文本操作（bodyLarge 派生 14sp） */
    val action: TextStyle get() = Typography.bodyLarge.copy(fontSize = 14.sp)
    /** 文本操作·强调（14sp Bold） */
    val actionStrong: TextStyle get() = Typography.bodyLarge.copy(fontSize = 14.sp, fontWeight = UfiWeight.Strong)
    /** 面包屑路径段（17sp，文件管理器路径栏；刻意比 bodyLarge 大 1sp 保留原观感） */
    val pathSegment: TextStyle get() = Typography.bodyLarge.copy(fontSize = 17.sp)

    // ---- 列表项（设置行 / 入口卡）----
    /**
     * 列表项标题（bodyLarge 16sp Normal）。
     *
     * 2026-09-02 批 6 定为**全站列表项标题的唯一档位**：设置行、入口卡、二级开关行都用它。
     * 选正文族而非展示族 —— 原意是靠字体族本身区分层级（展示族留给分组标题、页面标题与读数）。
     * 注意：统一系统字体后 display 与 body 同为 `FontFamily.Default`，该层级现在**只由字号与字重承担**；
     * 若要恢复"字体族区分层级"，需先让 [FontSet] 的 display/body 返回不同字体族。
     */
    val listItemTitle: TextStyle get() = Typography.bodyLarge

    // ---- 等宽遥测（dBm / IP / 速率 / 时间戳）----
    /** 等宽读数（14sp）：速率、IP、dBm */
    val monoReadout: TextStyle get() = Typography.bodyMedium.copy(fontFamily = MonoFont, fontWeight = UfiWeight.Medium)
    /** 等宽小字（11sp）：时间戳、日志行号 */
    val monoCaption: TextStyle get() = Typography.labelSmall.copy(fontFamily = MonoFont)
    /** 等宽极小字（10sp / 行高 13sp）：整文件日志预览，密度优先 */
    val monoTiny: TextStyle
        get() = Typography.labelSmall.copy(fontFamily = MonoFont, fontSize = 10.sp, lineHeight = 13.sp)
    /** 等宽次要文本（12sp）：代码输入框、行号栏 */
    val monoNote: TextStyle get() = Typography.bodySmall.copy(fontFamily = MonoFont)
    /** 等宽代码正文（13sp / 行高 18sp）：文本编辑器主体 */
    val monoCodeBlock: TextStyle
        get() = Typography.bodySmall.copy(fontFamily = MonoFont, fontSize = 13.sp, lineHeight = 18.sp)
    /** 等宽大读数（28sp）：测速主数字 */
    val monoMetric: TextStyle get() = Typography.titleLarge.copy(fontFamily = MonoFont, fontSize = 28.sp, fontWeight = UfiWeight.Hero)
    /** 等宽验证码（24sp Bold）：短信验证码等需逐字辨识的字符串 */
    val monoCode: TextStyle get() = Typography.headlineMedium.copy(fontFamily = MonoFont)

    // ---- 特例 ----
    /**
     * 胶囊导航标签。**不含字号**：字号由 `ThemeManager.capsuleLabelTextSp` 的用户设置决定，
     * 调用点仍传 `fontSize`。
     *
     * 刻意不复用 `caption`：那会连带 11sp 字号、Medium 字重与 14sp 行高，
     * 而胶囊栏的图标纵向偏移是按**实测文字高度**算的，行高一变几何跟着变。
     * 字体族走 [BodyFont]（当前 = 系统字体，统一收敛，避免与展示族混排）。
     * 字重保持 Normal，与改动前一致。
     */
    val capsuleLabel: TextStyle get() = TextStyle(fontFamily = BodyFont, fontWeight = UfiWeight.Regular)
}

/**
 * 大读数 / 长文案的**自适应缩放字号阶梯**（"一行装不下就降一档"用的候选字号表）。
 *
 * 与 [UfiTextStyles] 的区别：那边一个名字对应一个**确定**的样式；这里一个名字对应一串
 * **从大到小**的候选字号 —— 调用点按实测宽度（[androidx.compose.ui.text.TextMeasurer]）
 * 或按字符数逐档试，取第一个塞得进可用宽度的档位。
 *
 * 2026-09-04 建立。起因：监控页 hero 状态行在"屏幕窄 / 系统字体缩放大"的机型上折行，
 * 修法是加自适应降档，但那份档位表当时直接写在 `MonitorOverview.kt` 里 —— 5 个裸 sp 落在
 * theme 目录外，既踩了字面量治理红线，也让"字阶"这件事出现了第二个来源。
 * 它本质上就是字阶的一部分（首档必须与某个 [UfiTextStyles] 档位对齐，尾档必须与副文案拉开层级），
 * 所以搬到这里集中管理：以后调 [Typography] 的读数字号，跟着调这里的首档即可。
 */
object UfiTextSizeLadder {

    /**
     * [UfiTextStyles.metricValueLarge]（24sp Bold 主读数次级尺寸）的自适应降档表。
     *
     * 用在**一行文案里混着关键数字、且不允许被省略号吃掉**的场景 ——
     * 当前使用者是监控页事件汇总卡（`EventSummaryCard`）顶部的 hero 状态行
     * （"3 项未读告警 · 阈值告警" / "严重阈值告警" / "运行正常"）。
     *
     * ## 为什么是 5 档、每档什么时候生效
     * 降档条件与档数都由「最长文案 × 可用宽度」推出来，不是随手取的：
     * - **24sp**：与 [UfiTextStyles.metricValueLarge] 同值。宽屏 + 系统默认字体缩放时必然命中这一档，
     *   所以对绝大多数设备**观感与不做自适应时完全一致**。
     * - **22 / 20 / 18sp**：中间三档。最长文案约 11 个汉字加数字，窄屏或 1.15x~1.5x 字体缩放下
     *   逐档收缩；步长取 2sp —— 再粗（如 4sp）会出现"24 装不下、20 又明显变小"的突兀跳变，
     *   再细（1sp）则多出一倍的测量次数却看不出差别。
     * - **16sp**：下限。等于 `bodyLarge`（副文案档位），再往下 hero 就和它下面那行说明文字一样大，
     *   主次层级消失 —— 所以宁可在这一档交给省略号兜底，也不继续缩。
     *
     * ⚠️ 顺序**必须从大到小**：调用点取「第一个塞得下的档位」，乱序会直接选出偏小的字号。
     *
     * 注：`SpeedTestScreen` 的盘内读数（按字符数在 42/36/30sp 间降档）是同一模式的另一处实现，
     * 属存量代码；日后若要收敛，可在本对象里加一条 `monoMetricAutoShrink` 并让那边引用。
     */
    val metricValueLargeAutoShrink: List<TextUnit> = listOf(24.sp, 22.sp, 20.sp, 18.sp, 16.sp)
}
