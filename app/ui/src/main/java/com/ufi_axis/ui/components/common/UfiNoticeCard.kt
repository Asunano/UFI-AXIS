// [F24] STABLE-UI-API：公共组件签名已冻结，请勿在无向后兼容前提下修改；实验性组件请使用 @UfiExperimentalApi（见 UfiStableApi.kt / UfiExperimentalApi.kt）。
package com.ufi_axis.ui.components.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import com.ufi_axis.ui.theme.LocalResolvedPalette
import com.ufi_axis.ui.theme.Spacing
import com.ufi_axis.ui.theme.UfiCardDefaults
import com.ufi_axis.ui.theme.UfiTextStyles

/** [UfiNoticeCard] 的语气档位。只有两档：普通说明与需要留意的事项。 */
enum class UfiNoticeSeverity {
    /** 中性说明（`accentContainer` 淡底、无描边）。 */
    INFO,

    /** 需要留意（`warning` 淡底 + 淡描边）。仅用于风险提示：滥用会让真正的警告失去分量。 */
    WARNING
}

/**
 * 页面内联的提示卡：占布局空间，随页面滚动。
 *
 * ## 与另外三个提示族的分工
 * - [UfiErrorBanner]：挂 Popup 悬浮在屏幕顶部，用于请求失败这类瞬时错误；
 * - [UfiDialogNote] / [UfiDialogWarning]：仅用于弹窗 body（横向内距由 UfiDialogShell 提供）；
 * - [UfiServiceStoppedNotice]：`fillMaxSize` 的整页替换，由 ServiceGate 驱动；
 * - 本组件：页面内的常驻说明或提醒，与 [UfiSettingsRowCard] 平级排布。
 *
 * ## 新增原因
 * 2026-09-11：此前这一形态在两个 feature 模块各有一份私有实现
 * （`feature-download` 的待重启提示、`feature-monitor` 的告警引擎未开启卡）。
 * 第三处若继续复制，淡底透明度、描边宽度、图标尺寸这些取值会散落在三个文件。
 * 现收进公共层，取色统一走 [LocalResolvedPalette]，随主题一起变化。
 *
 * 不要用 `UfiSettingsRowCard { UfiSettingsItem(icon = …, iconTint = warning, …) }` 代替：
 * 设置行的语义是「可点击进入」或「带控件」，把图标染成警告色会让用户以为该行可以点击。
 *
 * ## 边距
 * 自带 [Spacing.CardHorizontalMargin] 横向外边距，与 [UfiSettingsRowCard] / [UfiSettingsGroup]
 * 对齐 —— 直接放进 `UfiPageBackground` 的 Column 即可，外层不需要再叠 padding。
 *
 * @param message 正文段落。
 * @param title 可选标题；为空时只有一行图标 + 段落。
 * @param severity 语气档位，见 [UfiNoticeSeverity]。
 * @param icon 覆盖默认图标（默认按 [severity] 取 WarningAmber / Info）。
 */
@Composable
fun UfiNoticeCard(
    message: String,
    modifier: Modifier = Modifier,
    title: String? = null,
    severity: UfiNoticeSeverity = UfiNoticeSeverity.INFO,
    icon: ImageVector? = null
) {
    val palette = LocalResolvedPalette.current
    val tint = when (severity) {
        UfiNoticeSeverity.INFO -> palette.accent
        UfiNoticeSeverity.WARNING -> palette.warning
    }
    val effectiveIcon = icon ?: when (severity) {
        UfiNoticeSeverity.INFO -> Icons.Default.Info
        UfiNoticeSeverity.WARNING -> Icons.Default.WarningAmber
    }
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.CardHorizontalMargin),
        shape = UfiCardDefaults.shape,
        // 透明度沿用 feature-monitor 那份实现（8% 底 / 40% 描边），观感保持一致。
        // INFO 档不描边：中性说明无需额外框出。
        color = tint.copy(alpha = 0.08f),
        border = if (severity == UfiNoticeSeverity.WARNING) {
            BorderStroke(Spacing.NoticeBorderWidth, tint.copy(alpha = 0.4f))
        } else null
    ) {
        Column(
            modifier = Modifier.padding(Spacing.InnerPadding),
            verticalArrangement = Arrangement.spacedBy(Spacing.Small)
        ) {
            if (title.isNullOrBlank()) {
                Row(verticalAlignment = Alignment.Top) {
                    Icon(
                        imageVector = effectiveIcon,
                        contentDescription = null,
                        modifier = Modifier.size(Spacing.IconSizeSmall),
                        tint = tint
                    )
                    Spacer(Modifier.width(Spacing.Small))
                    Text(
                        text = message,
                        style = UfiTextStyles.note,
                        color = palette.textSecondary
                    )
                }
                return@Column
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = effectiveIcon,
                    contentDescription = null,
                    modifier = Modifier.size(Spacing.IconSizeSmall),
                    tint = tint
                )
                Spacer(Modifier.width(Spacing.Small))
                Text(
                    text = title,
                    style = UfiTextStyles.sectionTitle,
                    color = palette.textPrimary
                )
            }
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = palette.textSecondary
            )
        }
    }
}
