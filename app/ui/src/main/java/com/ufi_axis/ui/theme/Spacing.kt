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
     * 属组件一致性待收敛项。
     */
    val CornerChatBubble = 12.dp

    // --- 2026-09-07：补齐 UfiCardDefaults 里最后 3 档就地写死的圆角（见该文件 §「圆角全部取自 Spacing」自述）---
    /** 聊天气泡的"尖角"（贴向说话方的那一角）。值与 [CornerTrack] 相同，但语义不同：那是轨道，这是气泡指向。 */
    val CornerBubbleTip = 4.dp
    /** 聊天输入框圆角（原 `UfiCardDefaults.chatInputCornerRadius` 就地写死的 20dp）。 */
    val CornerChatInput = 20.dp
    /** 细线轨道圆角（原 `UfiCardDefaults.hairlineCornerRadius` 就地写死的 1dp）。 */
    val CornerHairline = 1.dp

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

    /**
     * 平级「单行卡」之间的纵向间距（UfiSettingsRowCard 堆叠时页面用 spacedBy(SettingsCardGap)）。
     *
     * 2026-09-10 从各页面的裸 `Arrangement.spacedBy(10.dp)` 提上来：UfiSettingsRowCard 的
     * KDoc 早就把 10dp 写成了这套布局的约定，但值散落在每个调用页，改一次要翻遍所有设置页。
     * 比 [Medium]（8dp）大一档：卡与卡之间需要比卡内行距更明显的分隔，才看得出是两张卡。
     */
    val SettingsCardGap = 10.dp

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

    /**
     * 行内窄数字输入框的宽度（UfiDigitField 配 fillMaxWidth = false 时用）。
     *
     * 2026-09-10 从配对管理页的裸 `Modifier.width(90.dp)` 提上来：这类"标题在左、
     * 输入框+保存按钮在右"的设置行不只一处（下载设置的做种率也是同一形态），
     * 宽度写在调用点就会各页各写一个数，右侧控件组对不齐。
     * 90dp 的依据：4 位数字 + 「数量」浮动标签在 M3 OutlinedTextField 里刚好不被截断。
     */
    val InlineDigitFieldWidth = 90.dp

    // Chip metrics
    val ChipHeight = 36.dp
    val ChipPaddingH = 16.dp
    /**
     * 分段控件（`UfiSingleChipSelector`）里单个「段」的高度。
     *
     * 2026-09-08 由 24dp 调到 32dp：轨道总高 = 32 + 上下各 [Small]（4dp）= **40dp**，
     * 与 [InputHeight]（50dp）、[ChipHeight]（36dp）同一量级，不再比同排控件明显矮一截。
     * 原值 24dp 让轨道只有 32dp（= M3 `FilterChipDefaults.Height`，是该组件改成分段控件之前的
     * 高度），点击区偏小、上下留白视觉上"挤"。
     *
     * ⚠ 与 [SwitchTrackHeight] 无关，**不要互相借用**（本文件的「一个 token 只服务
     * 一个语义」原则：改开关轨道高度不该连带改分段控件）。
     */
    val SegmentHeight = 32.dp

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

    // Empty state metrics（2026-09-08）
    // 从 TaskScreen 两处手搓空态提上来 —— 那套版式现在是全站空态的标准，
    // 由公共组件 UfiEmptyState 独家实现，尺寸档位必须在令牌层才改得动一处生效全站。
    /** 空态图标的圆形底直径。 */
    val EmptyStateIconBox = 96.dp
    /** 空态图标本身的尺寸（圆形底内居中，两者比例 2:1）。 */
    val EmptyStateIconSize = 48.dp
    /** 空态图标底与标题之间的间距。比 [XLarge] 大一档：图标块很大，16dp 会显得标题贴着它。 */
    val EmptyStateIconToTitle = 20.dp

    // Donut breakdown metrics（2026-09-08）
    // 多段圆环构成图（UfiDonutChart）的几何。放令牌层而不是组件里写死：
    // checkLiteralBaseline 的 dp 基线只允许下降，新组件在业务侧写字面量会顶穿基线；
    // 而这三个值本就是"图表环有多粗、图例点有多大"这类应当全站统一的尺寸档。
    /** 圆环整体直径。168dp = 卡内宽度的约一半，两侧还留得下图例文字的呼吸空间。 */
    val DonutSize = 168.dp
    /** 圆环线宽。20dp 让相邻分段的颜色有足够面积被认出来，再细就只剩一圈细线。 */
    val DonutStroke = 20.dp
    /** 图例色点直径。与 [IconSizeSmall] 拉开差距，避免被读成一个图标。 */
    val DonutLegendDot = 10.dp

    // Notice card metrics（2026-09-11）
    // 页面内联提示卡（UfiNoticeCard）的两个几何档位。放令牌层而不是组件里写死：
    // checkLiteralBaseline 的 dp 基线只许下降，组件里写字面量会顶穿基线；
    // 而"提示卡描边多细"本身就该全站一致。
    /**
     * 提示卡（WARNING 档）的描边宽度。
     *
     * 与 [ButtonBorderWidth] 同值但**不复用**：本文件的"一个 token 只服务一个语义"原则 ——
     * 改按钮描边不该连带改提示卡的框。
     */
    val NoticeBorderWidth = 1.dp

    // Text editor metrics（2026-09-11 文本预览/编辑重写）
    // 行号列的宽度是**算出来的**（位数 × 字宽 + 间距），不是写死值 ——
    // 原实现固定 48sp，1000 行以内浪费，10 万行时行号被挤掉。
    /** 行号列宽度下限。3 位行号以内都按这个宽度走，避免小文件行号列忽宽忽窄。 */
    val EditorGutterMinWidth = 32.dp
    /**
     * 行号每一位数字的估算宽度。
     *
     * `monoNote` 是 12sp 等宽体，约 6.6dp/字符，取 7dp 留一点余量：
     * 宁可宽 0.4dp 也不能让四位行号被截成三位半。
     */
    val EditorGutterDigitWidth = 7.dp
    /** 行号列与正文之间的间距。要够宽以免行号读成正文的一部分，又不能撑掉正文可视宽度。 */
    val EditorGutterGap = 8.dp
    /** 正文区（含行号列）的内边距。 */
    val EditorContentPadding = 10.dp

    // Chart Y-axis width（2026-09-09）
    // `UfiMonitorChart` 的 Y 轴列宽按最宽刻度文本实测自适应，再用这里的下限夹一下；
    // 而绘图区左右外距都由这个宽度推导（`symmetricHorizontal` 时左右各来一次）。
    // 于是**下限决定了多张图能不能对齐**：都撞下限才对齐，谁的标签超了谁就自己缩进更多。
    /** Y 轴列宽下限（默认）。历史值，单指标页面沿用。 */
    val ChartYAxisMinWidth = 32.dp
    /**
     * 多张图纵向排列且要求 X 轴对齐时用的下限。
     *
     * 取 40dp 的依据：`monoCaption` 11sp 等宽字体约 6.6dp/字符，5 字符 ≈ 33dp，加 4dp 内距 ≈ 37dp。
     * 监控中心把所有轴标签的字符数都压到 ≤5（百分比 `100%`、信号 `-105`、速率 `1023K`/`99.9M`），
     * 于是 8 张图全部撞在这个下限上 —— 对齐是"都被夹平"得来的，不是巧合。
     * 谁要改轴标签格式，先确认字符数上界仍 ≤5，否则那张图会重新错开。
     */
    val ChartYAxisAlignedWidth = 40.dp
}
