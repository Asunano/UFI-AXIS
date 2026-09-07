package com.ufi_axis.ui.theme

import androidx.compose.ui.unit.dp

object Spacing {
    // === 圆角：全站唯一基准（2026-09-01 批 4 收敛）===
    // 原状是 8 个独立 val 都写着 10.dp（CardCorner / CardCornerRadius / LargeCardCornerRadius /
    // SmallCornerRadius / ToastCornerRadius / ButtonCornerRadius / InputCornerRadius / ChipCornerRadius），
    // "全站圆角 10 改 12" 要改 8 处。现在只有下面 3 个基准值，语义形状全部由
    // UfiCardDefaults 派生（业务只用 UfiCardDefaults.shape / chipShape / dialogShape … ）。
    /** 基准圆角：卡片 / 按钮 / 输入框 / chip / toast / 小组件共用（=12dp，对齐规范 cornerM）。改这一行即全站生效。 */
    val CornerBase = 12.dp
    /**
     * 弹窗圆角。
     *
     * 当前与 [CornerBase] 同值（12dp），即弹窗与卡片圆角一致。规范建议弹窗用更大的 cornerL(16dp)
     * 以拉开层级（弹窗浮在卡片之上），但改动会影响所有弹窗观感，需单独评审后再动 —— 保留独立常量
     * 就是为了让那次改动只需改这一行。
     */
    val CornerDialog = 12.dp
    /** 微圆角：徽章、进度轨道等小面积元素。 */
    val CornerMicro = 6.dp

    // --- 圆角梯度（业务里历史存在的其它档位，2026-09-01 批 4 从 94 处字面量收敛而来）---
    // 这些值刻意保留原观感，不强行抹平成 CornerBase；调整时改这里即全站同档位跟随。
    /** 细条轨道 / 微型分隔块（原 2~4dp 字面量）。 */
    val CornerTrack = 4.dp
    /** 紧凑标签、行内小块（原 5dp 字面量）。 */
    val CornerTag = 5.dp
    /** 小面板、卡内二级容器（原 8dp 字面量）。 */
    val CornerSmall = 8.dp
    /** 中圆角：图标容器、稍大的卡内容器（原 14dp 字面量）。 */
    val CornerMedium = 14.dp
    /** 大圆角：应用图标、装饰性大方块（原 16dp 字面量）。 */
    val CornerLarge = 16.dp
    /** 胶囊按钮（原 24dp 字面量）。 */
    val CornerCapsule = 24.dp
    /** 控制台会话气泡主圆角（原 25dp 字面量）。 */
    val CornerBubble = 25.dp
    /**
     * 短信聊天气泡主圆角。
     *
     * 2026-09-03 从 [CornerDialog] 解耦出来：原先 `UfiCardDefaults.chatBubbleShape` 直接读
     * [CornerDialog]，导致"改弹窗圆角"会连带改短信气泡。值保持 12dp 不变，纯解耦无观感变化。
     *
     * ⚠️ 待决：短信气泡 12dp 与控制台气泡 [CornerBubble] 25dp 差了一倍，两处都是"聊天气泡"却观感不同，
     * 属组件一致性待收敛项（见 docs/theme-migration-plan.md 的 P4e）。
     */
    val CornerChatBubble = 12.dp

    // === Legacy spacing (kept for backward compatibility) ===
    val PagePadding = 12.dp
    val GroupSpacing = 8.dp
    val InnerPadding = 12.dp
    val SectionTop = 12.dp
    val Small = 4.dp
    val Medium = 8.dp
    val Large = 12.dp
    val XLarge = 16.dp

    // === Modern Rounded Design System ===
    // 统一克制小圆角（见 CornerBase）+ 柔阴影 + 呼吸感间距

    // Card metrics
    val CardPadding = 20.dp
    val CardHorizontalMargin = 16.dp
    val CardBottomMargin = 16.dp

    // Dialog metrics — 圆角见 CornerDialog
    val DialogPaddingH = 18.dp
    val DialogPaddingV = 24.dp
    val DialogButtonHeight = 48.dp
    // P2-2：弹窗底部操作区下方留白（替代魔法数字 22.dp / 16.dp）
    val DialogActionsBottom = 22.dp
    val DialogActionsWarningGap = 16.dp

    // Toast metrics
    val ToastPaddingH = 18.dp
    val ToastPaddingV = 14.dp

    // Switch metrics (UFITOOLSWidget custom toggle)
    val SwitchTrackWidth = 42.dp
    val SwitchTrackHeight = 24.dp
    val SwitchThumbSize = 18.dp
    val SwitchThumbMargin = 3.dp

    // Header metrics — 左对齐现代风格（字号见 Type.kt: UfiTextStyles.headerTitle / headerSubtitle）
    val HeaderHeight = 44.dp
    val HeaderPaddingH = 16.dp

    // Button metrics
    val ButtonHeight = 48.dp
    val SmallButtonHeight = 36.dp
    // 2026-09-04（P4c 按钮收敛）：以下 4 个从 UfiButton.kt 的裸字面量提上来。
    // 五个旧按钮合并成一个 UfiButton(variant, size) 后，这些尺寸从"某个组件的私事"
    // 变成了"所有 variant × size 共用的档位"，必须放在令牌层才改得动一处生效全站。
    /** `UfiButtonSize.Small` 档的水平内距（旧 UfiSmallButton / UfiOutlinedActionButton 各写一遍 16dp）。 */
    val SmallButtonPaddingH = 16.dp
    /** `UfiButtonSize.Small` 档的垂直内距：0 —— 高度已由 [SmallButtonHeight] 锁死，再加内距会顶破 36dp。 */
    val SmallButtonPaddingV = 0.dp
    /** 描边按钮（accent 描边 / dialogBorder 弱描边）的边框宽度。 */
    val ButtonBorderWidth = 1.dp
    /** 按钮 loading 态转圈的直径（比 [IconSizeSmall] 独立，改图标尺寸不该动按钮里的转圈）。 */
    val ButtonLoadingIndicatorSize = 18.dp

    // Input field metrics
    val InputHeight = 50.dp

    // Chip metrics
    val ChipHeight = 36.dp
    val ChipPaddingH = 16.dp
    /**
     * 分段控件（`UfiSingleChipSelector`）里单个「段」的高度。
     *
     * 24dp + 轨道上下各 [Small]（4dp）= 32dp，正好等于 M3 `FilterChipDefaults.Height`
     * —— 也就是该组件改成分段控件之前的高度。5 个以上调用点（监控页两处模式切换、外观设置、
     * 后台守护巡检间隔、调试日志级别筛选…）都把它当"一行 32dp 的控件"排版，占位高度不能变。
     *
     * ⚠ 与 [SwitchTrackHeight] 同值纯属巧合，**不要互相借用**（本文件的「一个 token 只服务
     * 一个语义」原则：改开关轨道高度不该连带改分段控件）。
     */
    val SegmentHeight = 24.dp

    // Navigation bar metrics (no longer used for bottom nav, kept for compatibility)
    val NavBarCornerRadius = 0.dp

    // Icon sizes
    val IconSizeSmall = 18.dp
    val IconSizeMedium = 20.dp
    val IconSizeLarge = 22.dp
    val IconSizeDialog = 22.dp

    /**
     * 手绘 ImageVector 的画布基准（`defaultWidth` / `defaultHeight`）。
     *
     * 24dp 是 Material 图标栅格的标准边长，与 `viewportWidth/Height = 24f` 成对出现。
     * 它**不是**显示尺寸——调用点一律用 IconSize* 覆盖；这里只决定矢量的固有比例。
     */
    val IconCanvas = 24.dp

    // Section spacing — 分组间距
    val SectionSpacing = 24.dp
    val SectionTitleToCard = 12.dp
}
