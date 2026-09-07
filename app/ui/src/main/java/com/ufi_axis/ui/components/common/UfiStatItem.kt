// [F24] STABLE-UI-API：公共组件签名已冻结，请勿在无向后兼容前提下修改；实验性组件请使用 @UfiExperimentalApi（见 UfiStableApi.kt / UfiExperimentalApi.kt）。
package com.ufi_axis.ui.components.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.ufi_axis.ui.theme.LocalResolvedPalette
import com.ufi_axis.ui.theme.Spacing
import com.ufi_axis.ui.theme.UfiCardDefaults
import com.ufi_axis.ui.theme.UfiTextStyles
import com.ufi_axis.ui.theme.UfiWeight

@Composable
fun UfiStatItem(
    value: String,
    label: String,
    valueColor: Color = Color.Unspecified,
    modifier: Modifier = Modifier
) {
    val palette = LocalResolvedPalette.current
    val resolvedColor = if (valueColor == Color.Unspecified) palette.textPrimary else valueColor
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            value,
            style = UfiTextStyles.panelTitle,
            color = resolvedColor
        )
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = palette.textSecondary
        )
    }
}

/**
 * 流量管理 2x2 数据 tile（D 方案·仪表盘）。
 *
 * 白卡片 + 0.5px 描边 + 10dp 圆角，内部：图标 + 标签（一行）+ 数值（大字）。
 * 用于「下载 / 上传 / 总流量 / 连接时长」四块等宽指标。
 *
 * FIX-26（2026-08-24）：新增 [embedded] 模式（默认 false，向后兼容）。embedded=true 时
 * 跳过自身的 border / background，由调用方提供外层容器（典型场景：2x2 四块合并到单张
 * 外层卡，内部用 hairline 分隔，参考仪表盘 HomeMetricsCard 扁平风格）。同时 padding
 * 由 14dp → 12dp，进一步压紧视觉密度。
 *
 * @param icon 行首小图标（语义色，默认 accent）
 * @param label 指标名
 * @param value 指标值（大字）
 * @param valueColor 数值颜色（默认 textPrimary；下载蓝 / 上传橙可传入 TrafficDown / TrafficUp）
 * @param modifier 布局修饰（通常 weight(1f)）
 * @param embedded 是否嵌入式（去外框，给外层 Box 让出画笔）
 */
@Composable
fun UfiTrafficTile(
    icon: ImageVector,
    label: String,
    value: String,
    valueColor: Color = Color.Unspecified,
    modifier: Modifier = Modifier,
    embedded: Boolean = false
) {
    val palette = LocalResolvedPalette.current
    val resolvedValueColor = if (valueColor == Color.Unspecified) palette.textPrimary else valueColor
    val outerMod = if (embedded) {
        // 嵌入式：仅填充宽度 + 12dp 内边距，外框交给 caller
        modifier.fillMaxWidth().padding(12.dp)
    } else {
        // 独立卡片：原样保留白卡 + 描边 + 14dp 内边距
        modifier
            .fillMaxWidth()
            .clip(UfiCardDefaults.shape)
            .background(palette.cardBg)
            .border(0.5.dp, palette.cardBorder)
            .padding(14.dp)
    }
    Box(modifier = outerMod) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = palette.textSecondary,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = palette.textSecondary
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = value,
                style = UfiTextStyles.screenTitleEmphasis.copy(fontWeight = UfiWeight.Medium),
                color = resolvedValueColor
            )
        }
    }
}