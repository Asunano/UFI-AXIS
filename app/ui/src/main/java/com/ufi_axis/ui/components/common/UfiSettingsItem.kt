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
    // 未指定 iconTint 时的默认色 = [ResolvedPalette.textPrimary]。
    //
    // 2026-09-05：原先默认 `palette.accent`，深色下不可读 —— `default` 预设的
    // `accentDark` 是 0xFF555555，压在 `cardBgDark`（0xFF2A2A2A）上 WCAG 对比度只有
    // 约 1.9:1，远低于非文本图形 3:1 的下限，用户看到的就是"深灰图标糊在深灰卡上"。
    //
    // 为什么改这里而不是把 `accentDark` 调成近白：`accent` 在本仓的语义是**实底色块**
    // （主按钮 / FAB / 渐变 Hero / 进度条已填充段 / 选中态填充 / switch 滑块 /
    // 聚焦输入框描边 / chip 选中底），全仓约 330 处消费。把它整体提到近白会同时
    // 破坏那些位置：`onAccent` 默认是白色，近白 accent 底上的白字直接消失；
    // 浅色卡上的近白进度条也会糊掉。图标 tint 只是它的**少数派用途**，不该由它定调。
    //
    // textPrimary 才是语义正确的槽：设置行的前导图标与同一行的标题是同一层级的信息，
    // 标题走的就是 textPrimary，两者同色本身也更整齐。
    // 对比度（对承载它的卡面）：深色 0xFFEEEEEE / 0xFF2A2A2A ≈ 12.4:1，
    // 浅色 0xFF111111 / 0xFFFFFFFF ≈ 18.9:1，均远超 3:1（见 ColorTest 的钉桩测试）。
    val effectiveIconTint = if (iconTint == Color.Unspecified) {
        palette.textPrimary
    } else iconTint
    val titleColor = if (enabled) palette.textPrimary else palette.textSecondary.copy(alpha = 0.5f)
    val descriptionColor =
        if (enabled) palette.textSecondary else palette.textSecondary.copy(alpha = 0.35f)
    // 纵向 10dp：批 6 统一两套设置行时取自原 UfiSettingsRow（16sp 标题需要略多的呼吸空间）。
    val mod = if (onClick != null) {
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

@Composable
fun UfiSettingsValue(
    title: String,
    description: String? = null,
    value: String,
    onClick: (() -> Unit)? = null
) {
    val palette = LocalResolvedPalette.current
    UfiSettingsItem(
        title = title,
        description = description,
        onClick = onClick,
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = value,
                    style = UfiTextStyles.body,
                    color = palette.textSecondary
                )
                if (onClick != null) {
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