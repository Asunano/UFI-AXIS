// [F24] STABLE-UI-API：公共组件签名已冻结，请勿在无向后兼容前提下修改；实验性组件请使用 @UfiExperimentalApi（见 UfiStableApi.kt / UfiExperimentalApi.kt）。
package com.ufi_axis.ui.components.common

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ufi_axis.ui.animation.ufiPressScale
import com.ufi_axis.ui.theme.LocalResolvedPalette
import com.ufi_axis.ui.theme.Spacing
import com.ufi_axis.ui.theme.UfiCardDefaults
import com.ufi_axis.ui.theme.UfiTextStyles
// 本包内有一个同名的 @Deprecated 转发壳 `UfiMotion`（Ufi.kt，P3a 留给旧调用点）。
// 不加别名的话同名短引用会优先命中包内那个壳 → 无谓的废弃告警，且读代码的人分不清用的是哪一层。
// 与 Ufi.kt / UfiDropdown.kt 同一写法：统一走 ThemeMotion 别名。
import com.ufi_axis.ui.theme.UfiMotion as ThemeMotion

/**
 * 总页数：向上取整；`totalItems <= 0` 或 `pageSize <= 0` 时返回 0（表示"没有分页"）。
 *
 * ## 为什么需要这个函数
 * 页数口径必须全局只有一份。2026-09-04 事件中心的 bug 正是"同一屏两套口径"：
 * 明细 500 条（25 页）与聚合 6 行（1 页）各自算页数，而分页栏消费的是明细那份 ——
 * 聚合模式下分页栏显示 "1 / 25" 且能翻到第 25 页，切片从第 2 页起恒为空，
 * 用户看到一片纯白（空态判据用的也是明细口径，所以连"暂无数据"都不显示）。
 *
 * 返回 0 而不是 1 是刻意的：`0` 表示"没有分页"，调用点用 `pageCount > 1` 判定分页栏可见性，
 * 空列表因此既不显示分页栏也不会出现 "1 / 1"。
 */
fun ufiPageCount(totalItems: Int, pageSize: Int): Int {
    if (totalItems <= 0 || pageSize <= 0) return 0
    return (totalItems + pageSize - 1) / pageSize
}

/**
 * 把页码夹进 `[0, pageCount - 1]`；`pageCount <= 0` 时返回 0。
 *
 * ## 为什么需要这个函数
 * 页码是持有态，总页数是派生态，后者随时会变小：轮询刷新拉到更少的行、用户删了告警、
 * 切换聚合/明细模式、改每页条数。任何一种都会把 `currentPage` 留在已不存在的页上，
 * 结果是列表空白 + 分页栏因 `pageCount <= 1` 隐藏 —— 用户被困在空页里翻不回来。
 *
 * 所以每个 `pageCount` 变化点都要过一次这个夹取（`LaunchedEffect(pageCount) { page = ufiClampPage(page, pageCount) }`）。
 */
fun ufiClampPage(page: Int, pageCount: Int): Int {
    if (pageCount <= 0) return 0
    return page.coerceIn(0, pageCount - 1)
}

/** [UfiPagination] 的两种形态。 */
enum class UfiPaginationVariant {
    /** 悬浮底栏（页面级）：带页码切换动画与"点页码跳页"。 */
    Floating,

    /** 弹窗内固定底栏：更紧凑、无跳页。 */
    Inline
}

/**
 * 统一分页栏：`[‹ 上一页] [n / m] [下一页 ›]`。
 *
 * 2026-09-04 收口：此前监控页浮动分页条、总览 Tab 分页条、聚合明细弹窗底部分页条各写了一份，
 * 三者形状（pillShape vs buttonShape）、配色（accent 实底 vs TextButton 无底）、排版
 * （MaterialTheme.typography vs UfiTextStyles）、按压反馈（有/无）全不一致。现在三处同一份实现。
 *
 * 组件内**不含**容器/阴影/入场动画/翻页脉冲缩放 —— 全部由调用点决定
 * （浮动那两处自己套 `AnimatedVisibility` + `graphicsLayer` 脉冲，弹窗那处套在 bottomContent 里）。
 * 两种 variant 都只是「一个 Row + 三粒按钮」的裸结构，外部 `modifier` 直接落在 Row 上。
 *
 * 2026-09-04（可见性修复）：按钮底色 `accentContainer` 是半透明的，浮在滚动列表上会与下面的
 * 内容混色，所以用 `compositeOver(cardBg)` 压成实色（见函数体内注释）——可见性只靠这一步，
 * 组件自身不再额外加浮动卡片容器。
 *
 * @param currentPage 0-based 当前页
 * @param pageCount 总页数（见 [ufiPageCount]）
 * @param onJumpClick 非空时中间页码可点（用于跳页弹窗）；为 null 时页码是不可点的纯文本
 */
@Composable
fun UfiPagination(
    currentPage: Int,
    pageCount: Int,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
    variant: UfiPaginationVariant = UfiPaginationVariant.Floating,
    onJumpClick: (() -> Unit)? = null,
) {
    val palette = LocalResolvedPalette.current
    // 三段同一套配色：accentContainer 淡底 + accent 文字（与页内模式切换器选中态同一语言），
    // 而不是"两侧半透明实底 + 中间 accent 满实底"的三块重色。
    //
    // 2026-09-04 修"按钮几乎看不见"：accentContainer 是 `accent.copy(alpha = 0.2f/0.1f)`（ThemePalette.kt:262），
    // 半透明。浮动分页条悬在滚动列表上时，这层 10% 会与下面滑过的正文/卡片混色，
    // 结果按钮底与背景几乎同色。这里用 compositeOver 先把它压到本组件自己的底色（cardBg）上，
    // 拿到一个**不透明**的实色 —— 观感与原设计一致（还是很淡的 accent 底），但不再受下层内容影响。
    val buttonContainer = palette.accentContainer.compositeOver(palette.cardBg)
    val colors = ButtonDefaults.buttonColors(
        containerColor = buttonContainer,
        contentColor = palette.accent,
        // disabled 也必须是实色：先把 alpha 砍半（更淡）再压到 cardBg 上，而不是给已压好的实色再加 alpha
        //（后者会重新变成半透明，回到"混色看不见"）。
        disabledContainerColor = palette.accentContainer
            .copy(alpha = palette.accentContainer.alpha * 0.5f)
            .compositeOver(palette.cardBg),
        disabledContentColor = palette.accent.copy(alpha = 0.35f)
    )

    val contentPadding = when (variant) {
        UfiPaginationVariant.Floating ->
            PaddingValues(horizontal = Spacing.Large, vertical = Spacing.Medium)
        UfiPaginationVariant.Inline ->
            PaddingValues(horizontal = Spacing.Medium, vertical = Spacing.Small)
    }
    // 每个可点区各自一份 InteractionSource：共享会让点一个另一个也跟着缩。
    val prevInteraction = remember { MutableInteractionSource() }
    val nextInteraction = remember { MutableInteractionSource() }
    val pageInteraction = remember { MutableInteractionSource() }

    val pageLabel: @Composable (Int) -> Unit = { page ->
        Text(
            text = "${page + 1} / $pageCount",
            style = UfiTextStyles.bodyEmphasis,
            color = palette.accent,
            textAlign = TextAlign.Center,
            maxLines = 1
        )
    }

    // 组件本身不含容器/阴影/内边距：两种 variant 都只是一个 Row + 三粒按钮，
    // 外部 modifier 直接作用在 Row 上。可见性靠按钮自己的实色底（buttonContainer）解决，
    // 不再套一层浮动卡片（那层在两种 variant 上都显得多余：Floating 变成"卡里卡"，Inline 会双层底）。
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.Medium)
    ) {
        Button(
            onClick = onPrev,
            enabled = currentPage > 0,
            interactionSource = prevInteraction,
            // 按压走 ufiPressScale 的事件驱动编排：短按抬手时也会先补播完"缩到位"再弹回。
            modifier = Modifier.ufiPressScale(
                interactionSource = prevInteraction,
                pressedScale = ThemeMotion.PressScale.Button,
                spec = ThemeMotion.buttonPress()
            ),
            shape = UfiCardDefaults.buttonShape,
            contentPadding = contentPadding,
            elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp),
            colors = colors
        ) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                contentDescription = "上一页",
                modifier = Modifier.size(Spacing.IconSizeSmall)
            )
            Spacer(Modifier.width(Spacing.Small))
            Text("上一页", style = UfiTextStyles.bodyEmphasis)
        }

        if (onJumpClick != null) {
            Button(
                onClick = onJumpClick,
                interactionSource = pageInteraction,
                modifier = Modifier.ufiPressScale(
                    interactionSource = pageInteraction,
                    pressedScale = ThemeMotion.PressScale.Button,
                    spec = ThemeMotion.buttonPress()
                ),
                shape = UfiCardDefaults.buttonShape,
                contentPadding = contentPadding,
                elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp),
                colors = colors
            ) {
                if (variant == UfiPaginationVariant.Floating) {
                    AnimatedContent(
                        targetState = currentPage,
                        transitionSpec = {
                            val dir = if (targetState > initialState) 1 else -1
                            (slideInVertically { h -> dir * h } +
                                fadeIn(tween(ThemeMotion.Duration.Standard)))
                                .togetherWith(
                                    slideOutVertically { h -> -dir * h } +
                                        fadeOut(tween(ThemeMotion.Duration.Standard))
                                )
                        },
                        label = "ufiPageText"
                    ) { page -> pageLabel(page) }
                } else {
                    pageLabel(currentPage)
                }
            }
        } else {
            // 纯文本页码：不用 `enabled = false` 的 Button —— 那会套上 disabled 配色（淡一半），
            // 看起来像"坏掉的按钮"。这里保持与两侧同底同形状，只是不可点。
            // 底色用与两侧按钮同一份已压实的 buttonContainer（不是半透明的 accentContainer）。
            Box(
                modifier = Modifier
                    .clip(UfiCardDefaults.buttonShape)
                    .background(buttonContainer)
                    .padding(contentPadding),
                contentAlignment = Alignment.Center
            ) {
                pageLabel(currentPage)
            }
        }

        Button(
            onClick = onNext,
            enabled = currentPage < pageCount - 1,
            interactionSource = nextInteraction,
            modifier = Modifier.ufiPressScale(
                interactionSource = nextInteraction,
                pressedScale = ThemeMotion.PressScale.Button,
                spec = ThemeMotion.buttonPress()
            ),
            shape = UfiCardDefaults.buttonShape,
            contentPadding = contentPadding,
            elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp),
            colors = colors
        ) {
            Text("下一页", style = UfiTextStyles.bodyEmphasis)
            Spacer(Modifier.width(Spacing.Small))
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = "下一页",
                modifier = Modifier.size(Spacing.IconSizeSmall)
            )
        }
    }
}
