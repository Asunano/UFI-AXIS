// [F24] STABLE-UI-API：公共组件签名已冻结，请勿在无向后兼容前提下修改；实验性组件请使用 @UfiExperimentalApi（见 UfiStableApi.kt / UfiExperimentalApi.kt）。
package com.ufi_axis.ui.components.common

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import com.ufi_axis.ui.theme.LocalResolvedPalette
import com.ufi_axis.ui.theme.Spacing
import com.ufi_axis.ui.theme.UfiCardDefaults
import com.ufi_axis.ui.theme.UfiTextStyles

/**
 * 单选底部面板（编码 / 换行符 / 排序方式 等「从几个里挑一个」都用它）。
 *
 * ## 2026-09-11：重写以贴合整体 UI
 * 旧版是「RadioButton + 一行正文」拼出来的，既不在整行可点（只有小圆点能戳），
 * 选中态也没有任何视觉强调，和设置列表（[UfiSettingsItem]）的观感对不上。新版：
 * - **整行可点**：`clickable` 铺满整行，戳哪都生效，和小屏单手操作更友好；
 * - **选中态有强调**：选中行染 `surfaceMuted` 浅底 + 标题转 `accent` + 右侧 `Check` 图标，
 *   一眼能看出当前选的是哪个（旧版只靠一个小圆点，远处看不清）；
 * - 标题走 [UfiTextStyles.listItemTitle]（16sp），与全站列表项同一套字号；
 * - 顶部加**拖拽手柄**（[Spacing] 令牌约束尺寸，不引入裸 dp），符合底部面板的通用形态；
 * - 形状 / 配色全部走 [UfiCardDefaults] 与 [LocalResolvedPalette]，切换皮肤时整体跟随。
 *
 * 公共签名保持冻结（[F24]）：`title / options / selectedValue / onDismiss / onSelect` 一个没动，
 * 编码、换行符、文件排序、短信设置四个调用方都不用改。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UfiChoiceSheet(
    title: String,
    options: List<Pair<String, String>>,
    selectedValue: String? = null,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit
) {
    val palette = LocalResolvedPalette.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        shape = UfiCardDefaults.bottomSheetTopShape,
        containerColor = palette.cardBg,
        dragHandle = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = Spacing.Small),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(Spacing.IconSizeSmall, Spacing.CornerHairline)
                        .clip(RoundedCornerShape(percent = 50))
                        .background(palette.textSecondary.copy(alpha = 0.25f))
                )
            }
        }
    ) {
        Text(
            title,
            style = UfiTextStyles.panelTitle,
            color = palette.textPrimary,
            modifier = Modifier.padding(
                horizontal = Spacing.DialogPaddingH,
                vertical = Spacing.Medium
            )
        )
        options.forEach { (value, label) ->
            val selected = value == selectedValue
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        onSelect(value)
                        onDismiss()
                    }
                    .background(if (selected) palette.surfaceMuted else palette.cardBg)
                    .padding(
                        horizontal = Spacing.DialogPaddingH,
                        vertical = Spacing.Medium
                    ),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = label,
                    style = UfiTextStyles.listItemTitle,
                    color = if (selected) palette.accent else palette.textPrimary,
                    modifier = Modifier.weight(1f)
                )
                if (selected) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = null,
                        tint = palette.accent,
                        modifier = Modifier.size(Spacing.IconSizeSmall)
                    )
                }
            }
        }
        Spacer(Modifier.height(Spacing.Large))
    }
}