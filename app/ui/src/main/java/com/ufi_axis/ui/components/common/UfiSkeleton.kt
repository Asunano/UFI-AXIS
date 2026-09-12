package com.ufi_axis.ui.components.common

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ufi_axis.ui.animation.page.LocalUfiReduceMotion
import com.ufi_axis.ui.animation.page.UfiPageSwitcherDefaults
import com.ufi_axis.ui.theme.LocalResolvedPalette
import com.ufi_axis.ui.theme.UfiCardDefaults
import com.ufi_axis.ui.theme.ufiStandardCard
import com.ufi_axis.ui.theme.UfiMotion

/**
 * 骨架屏是否降级为静态（不跑扫光）。
 *
 * 判定口径**刻意与 [com.ufi_axis.ui.animation.page.UfiPageSwitcher] 完全一致**（系统「降低动效」
 * 或低端机），而不是自己再造一套：一套判定两处实现，迟早会出现「转场降级了但骨架还在闪」
 * 这种半降级状态。低端机结果在进程内恒定，按 context 缓存一次。
 *
 * 为什么要降级：shimmer 是**无限循环**动画，只要页面挂着就一直重绘。对开了「降低动效」的
 * 用户是明确的无障碍诉求（前庭失调 / 注意力干扰），对低端机则是首屏最吃紧的时候还额外
 * 占一份合成开销 —— 而骨架屏的核心价值是「预示布局」，静态灰块已经完整提供了这个价值。
 */
@Composable
private fun skeletonStatic(): Boolean {
    val context = LocalContext.current
    val isLowEndDevice = remember(context) { UfiPageSwitcherDefaults.isLowEndDevice(context) }
    return LocalUfiReduceMotion.current || isLowEndDevice
}

@Composable
private fun shimmerBrush(): Brush {
    val palette = LocalResolvedPalette.current
    val baseColor = palette.divider.copy(alpha = 0.15f)
    val highlightColor = palette.divider.copy(alpha = 0.35f)

    // 降级态取两档的中间值：单用 base 会淡到几乎看不出有占位块，
    // 单用 highlight 又比真实内容还抢眼。
    if (skeletonStatic()) return SolidColor(palette.divider.copy(alpha = 0.25f))

    val translateAnim by rememberInfiniteTransition(label = "shimmer").animateFloat(
        initialValue = 0f,
        targetValue = 1000f,
        animationSpec = infiniteRepeatable(
            animation = tween(UfiMotion.Duration.Ambient, easing = UfiMotion.Easing.Linear),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmerTranslate"
    )

    return Brush.linearGradient(
        colors = listOf(baseColor, highlightColor, baseColor),
        start = Offset(translateAnim - 200f, translateAnim - 200f),
        end = Offset(translateAnim, translateAnim)
    )
}

@Composable
fun UfiSkeletonLine(
    modifier: Modifier = Modifier,
    height: Dp = 14.dp,
    widthFraction: Float = 1f,
    cornerRadius: Dp = 4.dp
) {
    Box(
        modifier = modifier
            .fillMaxWidth(widthFraction)
            .height(height)
            .clip(RoundedCornerShape(cornerRadius))
            .background(shimmerBrush())
    )
}

@Composable
fun UfiSkeletonCircle(
    modifier: Modifier = Modifier,
    size: Dp = 40.dp
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(shimmerBrush())
    )
}

/**
 * 骨架卡：一张卡 + 若干条闪烁占位行。
 *
 * 2026-09-03 修阴影：原先用的是 M3 `Card` + `UfiCardDefaults.cardElevation()` + `cardBorder()`，
 * 是全库**唯一**走 M3 Card 阴影的组件，画出来是 M3 默认的硬投影，与全站柔阴影
 * （`ufiStandardCard`：#9CA4AC@30%，见 UfiCardDefaults）明显不是一套 —— 骨架屏本该比真实卡片
 * 更"轻"，结果反而更抢眼。现在与 [UfiSettingsGroup] / [UfiSettingsRowCard] 同源，
 * 并降到 2dp（占位态不该比内容态更浮）。
 */
@Composable
fun UfiSkeletonCard(
    modifier: Modifier = Modifier,
    lineCount: Int = 3,
    showAvatar: Boolean = false
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                start = UfiCardDefaults.horizontalMargin,
                end = UfiCardDefaults.horizontalMargin,
                bottom = UfiCardDefaults.padding
            )
            .ufiStandardCard(elevation = 2.dp)
            .padding(UfiCardDefaults.padding)
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (showAvatar) {
                UfiSkeletonCircle(size = 40.dp)
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                repeat(lineCount) { index ->
                    val fraction = if (index == lineCount - 1) 0.6f else 1f
                    UfiSkeletonLine(widthFraction = fraction)
                }
            }
        }
    }
}

/**
 * 骨架卡组：整页 / 首屏首次加载的默认占位（业务侧首选入口）。
 *
 * 2026-09-03 新增 [showAvatar]（**带默认值，既有调用不受影响**）：列表型页面（如应用管理）
 * 每行左侧都有图标，不透出圆形占位的话骨架与真实版式差太远，就失去了「预示布局」的意义。
 */
@Composable
fun UfiSkeletonGroup(
    modifier: Modifier = Modifier,
    cardCount: Int = 3,
    linesPerCard: Int = 3,
    showAvatar: Boolean = false
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        repeat(cardCount) {
            UfiSkeletonCard(lineCount = linesPerCard, showAvatar = showAvatar)
        }
    }
}

// ══════════════════════════════════════════════════════════════════════════════
// 列表页首屏骨架（2026-09-04）：文件管理 / 短信 / 下载管理去掉下拉刷新后，
// 首屏加载态由「转圈」改为骨架屏，三页共用同一套占位形状。
//
// 为什么不复用上面的 [UfiSkeletonCard]：那套是**卡片组**语义（自带页边距 + 卡阴影 +
// 整卡内边距），画出来是「三张独立的卡」，对着仪表盘那种分区版式；列表页真实版式是
// 「连续等高的行」，用卡片组会让骨架和真实列表的行高、间距、左图标位置全对不上，
// 数据落位时整块跳一次 —— 骨架屏预示布局的价值就没了。
// ══════════════════════════════════════════════════════════════════════════════

/**
 * 单个占位块：骨架屏的最小构件，尺寸完全由调用方决定。
 *
 * 与 [UfiSkeletonLine] 的分工：那个按「文字行」语义封装（宽度用 fraction、高度默认 14dp），
 * 这个是通用矩形（显式 [height] + [cornerRadius]），用来拼图标块、缩略图、进度条等
 * 非文字占位。两者共用同一支 `shimmerBrush()`，观感一致。
 *
 * @param height       占位块高度
 * @param cornerRadius 圆角半径；传 0.dp 即直角
 */
@Composable
fun UfiSkeletonBlock(
    modifier: Modifier = Modifier,
    height: Dp = 14.dp,
    cornerRadius: Dp = 4.dp
) {
    Box(
        modifier = modifier
            .height(height)
            .clip(RoundedCornerShape(cornerRadius))
            .background(shimmerBrush())
    )
}

/**
 * 一条列表项骨架：左侧图标圆块 + 右侧两行文字条。
 *
 * 比例是对着三页真实行版式取的公约数（文件行 / 会话行 / 下载任务卡都是
 * 「40dp 左图标 + 主标题 + 次要信息」）：主行 45% 宽、次行 28% 宽 —— 不写满宽是因为
 * 真实标题几乎不会顶到行尾，写满会让骨架看起来像一堵灰墙而不是一份列表。
 *
 * 高度不写死：由内容（图标 40dp + 上下 14dp 内边距）自然撑出，和真实行同源，
 * 这样数据到位后行高不变、列表不跳。
 */
@Composable
fun UfiSkeletonListItem(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .ufiStandardCard(elevation = 2.dp)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        UfiSkeletonCircle(size = 40.dp)
        Spacer(Modifier.width(12.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            UfiSkeletonLine(widthFraction = 0.45f, height = 12.dp)
            UfiSkeletonLine(widthFraction = 0.28f, height = 10.dp)
        }
    }
}

/**
 * 列表页首屏骨架：[rows] 条 [UfiSkeletonListItem] 纵向排列。
 *
 * 默认 6 条：够铺满常见机型的一屏（不足会露出大片空白、多了纯属浪费组合开销），
 * 且刻意**不**做成"按可用高度算条数" —— 那要 BoxWithConstraints 多测一遍，
 * 而骨架屏只活几百毫秒，不值得为它引入一层额外布局。
 *
 * 不用 LazyColumn：条数固定且很小，Lazy 的复用池反而是净开销。
 */
@Composable
fun UfiSkeletonList(
    rows: Int = 6,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(UfiCardDefaults.padding)
    ) {
        repeat(rows) { UfiSkeletonListItem() }
    }
}
