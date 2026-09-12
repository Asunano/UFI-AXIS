// [F24] STABLE-UI-API：公共组件签名已冻结，请勿在无向后兼容前提下修改；实验性组件请使用 @UfiExperimentalApi（见 UfiStableApi.kt / UfiExperimentalApi.kt）。
package com.ufi_axis.ui.components.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import com.ufi_axis.ui.theme.LocalResolvedPalette
import com.ufi_axis.ui.theme.Spacing
import com.ufi_axis.ui.theme.UfiTextStyles

/**
 * 全站唯一空态版式。
 *
 * 2026-09-08 改版：版式取自定时任务页原来手搓的那份（96dp accentContainer 圆底 + 48dp accent
 * 图标 + screenTitle 标题 + bodyMedium 副标题），并把那两处手搓实现删掉改为调用本组件 ——
 * 全站空态从此只有一套。
 *
 * 改版前是「48dp 灰图标（textSecondary@40%）+ bodyMedium 灰标题」，图标和文字同为弱化灰、
 * 字号也只有正文大小，整块在页面中央糊成一团，读者要凑近才知道那是"空"还是"没加载出来"。
 * 现在图标用 accent 实色配浅色圆底，标题升到 screenTitle，主次分明。
 *
 * @param icon 圆形底里的图标，选与页面主体同一语义的那个（短信用气泡、验证码用铃铛）
 * @param message 一句话说明「空的是什么」，不要写成"加载中"（加载中该用 [UfiSkeletonList]）
 * @param hint 副标题：告诉用户下一步做什么，或为什么这里是空的
 * @param action 底部动作槽（2026-09-08 新增，默认 null = 无按钮，纯追加不影响既有调用点）。
 *   **只在页面上没有其它同等入口时才传** —— 短信页右下角已有 FAB、拦截规则页右上角已有 +，
 *   空态里再放一颗按钮就是同一个动作出现两次。
 */
@Composable
fun UfiEmptyState(
    icon: ImageVector,
    message: String,
    hint: String? = null,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null
) {
    val palette = LocalResolvedPalette.current
    // 外层 Box + Center：调用方给了高度（fillMaxSize / fillParentMaxHeight）时整块居中，
    // 没给时退化为按内容高度包裹，两种用法都不用调用方再包一层。
    Box(
        modifier = modifier.fillMaxWidth().padding(Spacing.SectionSpacing),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier
                    .size(Spacing.EmptyStateIconBox)
                    .clip(CircleShape)
                    .background(palette.accentContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(Spacing.EmptyStateIconSize),
                    tint = palette.accent
                )
            }
            Spacer(Modifier.height(Spacing.EmptyStateIconToTitle))
            Text(
                message,
                style = UfiTextStyles.screenTitle,
                color = palette.textPrimary,
                textAlign = TextAlign.Center
            )
            if (hint != null) {
                Spacer(Modifier.height(Spacing.Medium))
                Text(
                    hint,
                    style = MaterialTheme.typography.bodyMedium,
                    color = palette.textSecondary,
                    textAlign = TextAlign.Center
                )
            }
            if (action != null) {
                Spacer(Modifier.height(Spacing.SectionSpacing))
                action()
            }
        }
    }
}
