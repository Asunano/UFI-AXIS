// [F24] STABLE-UI-API：公共组件签名已冻结，请勿在无向后兼容前提下修改；实验性组件请使用 @UfiExperimentalApi（见 UfiStableApi.kt / UfiExperimentalApi.kt）。
package com.ufi_axis.ui.components.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ufi_axis.ui.theme.LocalResolvedPalette
import com.ufi_axis.ui.theme.Spacing
import com.ufi_axis.ui.theme.UfiTextStyles

/**
 * 设置列表项 —— 全站**唯一**的设置行实现（2026-09-02 批 6 起）。
 *
 * 图标（可选）+ 标题 + 说明（可选）+ 右侧控件槽。标题走 [UfiTextStyles.listItemTitle]
 * （16sp Normal），说明走 [UfiTextStyles.note]（12sp）。
 *
 * 原先还有一个 `UfiSettingsRow`（标题 14sp Medium）与本组件（16sp Normal）并存，
 * 同一页甚至同一个 [UfiSettingsGroup] 里混用 —— 肉眼就是"设置列表字体不一样"。
 * 批 6 已把它的 29 个调用点全部迁到本组件并删除该文件。
 *
 * @param modifier 行容器修饰符（纵向留白已内置）。
 * @param enabled false 时标题/说明降为禁用色；trailing 控件的启用态由调用方自行控制。
 * @param titleMaxLines 标题默认最多 2 行（超出 Ellipsis）——设置项标题超过两行说明文案该改，
 *        而不该把行撑高把控件挤走。
 * @param descriptionMaxLines 说明默认不限行数：说明常常是一整句解释，截断等于丢信息。
 */
@Composable
fun UfiSettingsItem(
    title: String,
    description: String? = null,
    onClick: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
    icon: ImageVector? = null,
    iconTint: Color = Color.Unspecified,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    titleMaxLines: Int = 2,
    descriptionMaxLines: Int = Int.MAX_VALUE
) {
    val palette = LocalResolvedPalette.current
    // 未指定 iconTint 时的默认色 = [ResolvedPalette.accent]。
    //
    // 2026-09-08：改回 accent。它曾在 2026-09-05 被单点改成 textPrimary 以绕开
    // 「默认皮肤深色态 accentDark(#555555) 对卡面仅 1.9:1」的问题；那个根因已在
    // ThemePresets.Default 修掉（accent 现在恒定落在当前明暗档的可读侧，深色态 6.6:1），
    // 绕道反而造成「工具页/入口卡图标是主题色、设置页入口图标是白/黑」的不一致 ——
    // 换成红、蓝等彩色皮肤时尤其明显：同一个 App 里两类入口一个跟随主题、一个不跟随。
    //
    // 需要某一行不跟随主题（例如危险项走 error、状态项走 success）时由调用方显式传 iconTint。
    val effectiveIconTint = if (iconTint == Color.Unspecified) {
        palette.accent
    } else iconTint
    val titleColor = if (enabled) palette.textPrimary else palette.textSecondary.copy(alpha = 0.5f)
    val descriptionColor =
        if (enabled) palette.textSecondary else palette.textSecondary.copy(alpha = 0.35f)
    // 纵向 10dp：批 6 统一两套设置行时取自原 UfiSettingsRow（16sp 标题需要略多的呼吸空间）。
    //
    // 2026-09-08：`enabled = false` 时必须同时吞掉点击。此前只淡化了文字颜色而 clickable 照挂，
    // 于是"置灰的入口还能点进去"——那正是本仓禁止的假开关的另一种形态（看起来不可用、实际可用）。
    val mod = if (enabled && onClick != null) {
        modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp)
    } else {
        modifier.fillMaxWidth().padding(vertical = 10.dp)
    }
    Row(modifier = mod, verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = effectiveIconTint,
                modifier = Modifier.size(24.dp)
            )
            Spacer(Modifier.width(12.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = UfiTextStyles.listItemTitle,
                color = titleColor,
                maxLines = titleMaxLines,
                overflow = TextOverflow.Ellipsis
            )
            if (description != null) {
                Text(
                    text = description,
                    style = UfiTextStyles.note,
                    color = descriptionColor,
                    maxLines = descriptionMaxLines,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(Spacing.Medium))
            trailing()
        }
    }
}

@Composable
fun UfiSettingsToggle(
    title: String,
    description: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    icon: ImageVector? = null,
    onClick: (() -> Unit)? = null
) {
    UfiSettingsItem(
        title = title,
        description = description,
        icon = icon,
        // 默认整行点击 = 切换开关；传了 onClick 就走它（如「点行进设置页、点开关开热点」）
        onClick = onClick ?: { onCheckedChange(!checked) },
        trailing = {
            // 2026-08-30 去重：原来这里直接用 M3 Switch（52×32dp），而全站另有 13 处用自绘
            // [UfiSwitch]（Spacing.SwitchTrackWidth 42×24dp）—— 两种几何混在相邻页面里。
            // 统一到 UfiSwitch：Spacing.Switch* token 本就是为这套尺寸定义的，且它带
            // Role.Switch 无障碍语义与 spring 动画。设置行的开关因此比原来略小。
            UfiSwitch(
                checked = checked,
                onCheckedChange = onCheckedChange
            )
        }
    )
}

/**
 * 设置项行尾的导航箭头（[UfiSettingsItem] 的 trailing 槽用）。
 *
 * 尺寸与颜色跟 [UfiSettingsValue] 里那个箭头保持同一套，避免各页面自己写
 * `Icon(KeyboardArrowRight, tint = ...)` 时深浅不一。
 */
@Composable
fun UfiSettingsChevron() {
    val palette = LocalResolvedPalette.current
    Icon(
        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
        contentDescription = null,
        tint = palette.textSecondary.copy(alpha = 0.3f),
        modifier = Modifier.size(Spacing.IconSizeSmall)
    )
}

/**
 * 「值 + 右箭头」设置行 —— 二级页入口与"点开选一个档位"的标准写法。
 *
 * @param icon 前置图标（可选），直接透传给 [UfiSettingsItem]。
 *        2026-09-08 新增：同一个 [UfiSettingsGroup] 里 [UfiSettingsToggle] 早就能带图标，
 *        本组件不能 —— 于是「开关行有图标、值行没有」的分组左边缘对不齐（短信设置的验证码分组
 *        三行就是这个样子）。带默认值的新增参数，全部既有调用点不受影响（[F24] 向后兼容口径）。
 * @param enabled false 时整行置灰**且不可点击**（点击由 [UfiSettingsItem] 吞掉），
 *        右侧箭头一并隐藏 —— 用于"上级开关关着，这个入口点进去也没用"的依赖关系。
 *        2026-09-08 新增，理由：通知总闸关着时下面几个入口全是无效设置，
 *        既然点进去改了也不生效，就不该让人点进去改。
 */
@Composable
fun UfiSettingsValue(
    title: String,
    description: String? = null,
    value: String,
    onClick: (() -> Unit)? = null,
    icon: ImageVector? = null,
    enabled: Boolean = true
) {
    val palette = LocalResolvedPalette.current
    UfiSettingsItem(
        title = title,
        description = description,
        icon = icon,
        onClick = onClick,
        enabled = enabled,
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = value,
                    style = UfiTextStyles.body,
                    color = if (enabled) {
                        palette.textSecondary
                    } else {
                        palette.textSecondary.copy(alpha = 0.35f)
                    }
                )
                // 箭头是"可以点进去"的视觉承诺：禁用时必须一并撤掉，否则灰着还带箭头，
                // 用户只会以为是渲染问题而反复戳它。
                if (onClick != null && enabled) {
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = palette.textSecondary.copy(alpha = 0.3f),
                        modifier = Modifier.size(Spacing.IconSizeSmall)
                    )
                }
            }
        }
    )
}