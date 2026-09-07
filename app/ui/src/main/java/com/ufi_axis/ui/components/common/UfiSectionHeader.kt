// [F24] STABLE-UI-API：公共组件签名已冻结，请勿在无向后兼容前提下修改；实验性组件请使用 @UfiExperimentalApi（见 UfiStableApi.kt / UfiExperimentalApi.kt）。
package com.ufi_axis.ui.components.common

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ufi_axis.ui.theme.LocalResolvedPalette
import com.ufi_axis.ui.theme.UfiTextStyles

/**
 * 区块标题（卡片外的分组标题，可带右侧操作槽）。
 *
 * 2026-09-02 批 6：标题样式从 `tagStrong`（13sp Bold / 字距 0.3）改为
 * [UfiTextStyles.cardTitle]（14sp SemiBold），与 [UfiGroupHeader]、[UfiSectionGroupTitle]
 * 统一——原先三套分组标题字号/字重各不相同，同一页混用时观感割裂
 * （如 EmailNotifyScreen 上下两张卡分别用了 SectionHeader 与 GroupHeader）。
 */
@Composable
fun UfiSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    trailing: @Composable (() -> Unit)? = null
) {
    val palette = LocalResolvedPalette.current
    Row(
        modifier = modifier.fillMaxWidth().padding(start = 4.dp, bottom = 4.dp, top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = title,
            style = UfiTextStyles.cardTitle,
            color = palette.accent
        )
        trailing?.invoke()
    }
}