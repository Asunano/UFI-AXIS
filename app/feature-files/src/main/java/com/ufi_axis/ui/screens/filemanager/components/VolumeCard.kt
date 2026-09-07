package com.ufi_axis.ui.screens.filemanager.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ufi_axis.ui.theme.LocalResolvedPalette
import com.ufi_axis.ui.theme.Spacing
import com.ufi_axis.ui.theme.UfiTextStyles
import com.ufi_axis.ui.theme.ufiStandardCard
import com.ufi_axis.viewmodel.state.StorageVolume

/**
 * 存储卷卡片（无状态展示组件）。
 *
 * 布局：在 [com.ufi_axis.ui.theme.ufiStandardCard] 的 Box 内展示
 * 卷标签、已用/总容量文本，以及一条表示占用率的进度条。
 *
 * 进度条进度由 [StorageVolume.usePercent] 解析得到（去掉百分号后 /100，
 * 并约束在 0f..1f；解析失败时回退为 0f）。
 *
 * 颜色全部来自 [com.ufi_axis.ui.theme.LocalResolvedPalette]，不硬编码。
 *
 * @param volume 存储卷数据
 * @param onEnter 点击进入卷的回调
 * @param modifier 修饰符
 */
@Composable
fun VolumeCard(
    volume: StorageVolume,
    onEnter: () -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = LocalResolvedPalette.current
    val usePercentPct = parseUsePercent(volume.usePercent)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .ufiStandardCard()
            .clickable(onClick = onEnter)
            .padding(horizontal = Spacing.CardPadding, vertical = 14.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = volume.label,
                style = UfiTextStyles.panelTitleStrong,
                color = palette.textPrimary
            )
            Spacer(Modifier.height(6.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "已用 ${volume.usedSize} / 共 ${volume.totalSize}",
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.textSecondary
                )
                Text(
                    text = volume.usePercent,
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.textSecondary
                )
            }
            Spacer(Modifier.height(10.dp))
            LinearProgressIndicator(
                progress = { usePercentPct },
                // 设计令牌为 palette.primary，但 ResolvedPalette 未提供 primary，
                // 使用品牌主色 accent 作为进度/指示色（等价于原 primary 语义）。
                color = palette.accent,
                trackColor = palette.divider,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

/**
 * 解析占用率字符串为 0f..1f 的浮点进度。
 *
 * 例如 "46%" → 0.46f；非法输入（无法解析或非数字）回退为 0f。
 *
 * @param usePercent 形如 "46%" 的字符串
 * @return 约束在 [0f, 1f] 的进度值
 */
private fun parseUsePercent(usePercent: String): Float {
    val cleaned = usePercent.replace("%", "").trim()
    val value = cleaned.toFloatOrNull() ?: 0f
    return (value / 100f).coerceIn(0f, 1f)
}
