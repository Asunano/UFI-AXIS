package com.ufi_axis.ui.components.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ufi_axis.ui.theme.LocalResolvedPalette
import com.ufi_axis.ui.theme.Spacing
import com.ufi_axis.ui.theme.UfiWeight

/**
 * 「后端服务已停止」占位页（2026-09-03）。
 *
 * 用户在设置页点了「停止服务」后，core 侧除 HTTP 之外的一切自主活动都停了：采集、告警、
 * 定时任务、短信转发、下载轮询、隧道看护。此时把依赖设备实时数据的页面整体换成这一页，
 * 而不是让它们继续显示上一次的残留数据或转圈 —— 一是告诉用户"这不是坏了，是你自己关的"，
 * 二是页面不被组合，那些 2s/5s/10s 的轮询协程自然不会启动（这比逐个界面加门控更可靠）。
 *
 * 纯 UI，不认识 ViewModel：状态与动作由调用方（:app 的 ServiceGate）注入。
 *
 * @param onEnable  点「启动服务」，实现方为 `NetworkModule.setBackgroundService(true)`
 * @param onRefresh 点「刷新状态」，实现方为 `NetworkModule.loadServiceStatus()`
 * @param busy      开关请求在途（按钮转圈并禁用）
 * @param errorMessage 上一次操作/读取的错误，非空时红字提示
 */
@Composable
fun UfiServiceStoppedNotice(
    onEnable: () -> Unit,
    onRefresh: () -> Unit,
    busy: Boolean = false,
    errorMessage: String? = null,
    modifier: Modifier = Modifier
) {
    val palette = LocalResolvedPalette.current
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(palette.pageBg)
            // 2026-09-04（转场区满屏化）：本页是**唯一绕过 UfiScreenScaffold** 的常规目的地内容
            // —— `ServiceGate` 直接用它**替换**非白名单页面，因此拿不到那层壳的
            // statusBars/navigationBars 消费。主导航 Scaffold 的 contentWindowInsets 清零后，
            // 不自己加安全区就会出现「标题压在状态栏下、底部按钮被手势条盖住」。
            // 放在 background 之后：底色仍然满屏铺到边缘，只把**内容**收进安全区。
            .safeDrawingPadding(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 360.dp)
                .padding(horizontal = Spacing.XLarge),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .background(palette.warning.copy(alpha = 0.14f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Outlined.PowerSettingsNew,
                    contentDescription = null,
                    modifier = Modifier.size(34.dp),
                    tint = palette.warning
                )
            }
            Spacer(Modifier.height(Spacing.XLarge))
            Text(
                "后端服务已停止",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = UfiWeight.Strong,
                color = palette.textPrimary
            )
            Spacer(Modifier.height(Spacing.Medium))
            Text(
                "你在设置页停止了后端服务，设备侧的数据采集、告警、定时任务、短信转发、下载与内网穿透看护都已暂停，" +
                    "本页依赖实时数据，因此暂不可用。",
                style = MaterialTheme.typography.bodyMedium,
                color = palette.textSecondary,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(Spacing.Medium))
            Text(
                "重新开启后一切自动恢复：被暂停的下载会断点续传，定时任务重新排期。",
                style = MaterialTheme.typography.bodySmall,
                color = palette.textSecondary.copy(alpha = 0.75f),
                textAlign = TextAlign.Center
            )
            if (!errorMessage.isNullOrBlank()) {
                Spacer(Modifier.height(Spacing.Medium))
                Text(
                    errorMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.error,
                    textAlign = TextAlign.Center
                )
            }
            Spacer(Modifier.height(Spacing.SectionSpacing))
            UfiButton(
                text = "启动服务",
                onClick = onEnable,
                loading = busy
            )
            Spacer(Modifier.height(Spacing.Medium))
            UfiButton(
                variant = UfiButtonVariant.Secondary,
                text = "刷新状态",
                onClick = onRefresh,
                enabled = !busy
            )
            Spacer(Modifier.height(Spacing.Large))
            Text(
                "也可以到「我的 → 服务控制」里开启，那一页在服务停止时始终可用。",
                style = MaterialTheme.typography.bodySmall,
                color = palette.textSecondary.copy(alpha = 0.6f),
                textAlign = TextAlign.Center
            )
        }
    }
}
