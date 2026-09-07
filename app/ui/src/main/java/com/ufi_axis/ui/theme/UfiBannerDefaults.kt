package com.ufi_axis.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 屏幕顶部**悬浮告知**（错误提示卡 / toast 一类）的尺寸令牌。
 *
 * 这些值原先是 `UfiErrorBanner.kt` 的文件级 `private val`。搬到令牌层的理由与全站其它
 * `*Defaults` 相同：它们描述的是「顶部悬浮层停在哪、多大」这一**跨组件**的版式约定，
 * 而不是某个 composable 的私事 —— 平台层 toast 与应用内错误卡各停一处，
 * 同一次操作的两条反馈就会错位半屏（这正是 [topMargin] 注释里那条对齐要求）。
 *
 * 只放尺寸，不放配色：颜色一律走 [ResolvedPalette] 语义槽（`cardBg` / `error` / `textPrimary`）。
 */
@Immutable
object UfiBannerDefaults {

    /**
     * 悬浮卡的顶部外边距。
     *
     * 与平台层 toast 的 `UfiToastOverlay.TOP_MARGIN_DP = 48` 对齐 —— 两者是同一类
     * 「屏幕顶部悬浮告知」，各停一处会让同一次操作的两条反馈错位半屏。
     * 改这一行即两类浮层同时跟随（toast 侧那个常量是 `Int` dp，供 WindowManager 用）。
     */
    val topMargin: Dp = 48.dp

    /**
     * 悬浮卡左右各留的安全边距（同时决定卡片最大宽度 = 屏宽 - 2×本值）。
     *
     * 取卡片横向外边距同一档：悬浮卡的左右边界应当与页面里的卡片对齐，
     * 否则浮层出现时会显得比正文"宽出来一截"。
     */
    val sideMargin: Dp = Spacing.CardHorizontalMargin

    /** 入场时从上方落下的距离（配合 alpha 一起跑，见 `UfiErrorBanner` 的 graphicsLayer）。 */
    val rise: Dp = 10.dp

    /** 左侧警示图标的圆形底托直径。 */
    val iconBadgeSize: Dp = 22.dp

    /**
     * 左侧警示图标本身的尺寸。
     *
     * 比 [Spacing.IconSizeSmall]（18dp）更小：它要装进 [iconBadgeSize] 的圆底里并留出一圈留白，
     * 所以是这一档独有的尺寸，不与页面里的图标共用档位。
     */
    val iconSize: Dp = 14.dp

    /** 图标底托与正文之间的间隙（介于 [Spacing.Medium] 与 [Spacing.Large] 之间的专用档）。 */
    val iconTextGap: Dp = 10.dp
}
