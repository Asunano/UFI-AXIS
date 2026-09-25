// [F24] STABLE-UI-API：公共组件签名已冻结，请勿在无向后兼容前提下修改；实验性组件请使用 @UfiExperimentalApi（见 UfiStableApi.kt / UfiExperimentalApi.kt）。
package com.ufi_axis.ui.components.common

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.SettingsEthernet
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ufi_axis.ui.theme.LocalResolvedPalette
import com.ufi_axis.ui.theme.UfiCardDefaults
import com.ufi_axis.ui.theme.UfiTextStyles

/**
 * 一台在线客户端的列表行（图标 + 设备名 / 接入方式·IP·MAC + 右侧动作）。
 *
 * ## 原来在哪、为什么搬到这里
 * 2026-09-25 之前是 `app/feature-network/.../OnlineDevicesScreen.kt` 里的 private
 * `OnlineDeviceRow`。仪表盘 hero 卡「已连接设备」新增的在线设备弹窗要的就是同一种行，
 * 而 `:app:feature-dashboard` 不依赖 `:app:feature-network` —— 抄一份的话两处视觉
 * 从第二次改动起就会分叉。搬到 `app/ui` 之后两个调用点共用同一份实现。
 *
 * ## 为什么是「哑组件」
 * 入参是四个裸值（[hostname] / [ip] / [mac] / [viaLan]）而不是 `OnlineStation`：
 * `app/ui` **看不到** `:app:data`（模块边界，见 app/ui/build.gradle.kts 没有 data 依赖），
 * 拿不到那个数据类。映射（`station.hostname` → 本参数）留在各自的 feature 侧。
 *
 * ## 两个调用点的差异只由 [blocked] + [onUnblock] 表达
 * - 在线设备页：列表里**包含**已拉黑的设备（它们可能还挂在名单上），所以要能回传
 *   `blocked = true` 并给一个「解除」入口 ⇒ 传 [onUnblock]。
 * - 仪表盘弹窗：列表在调用点就把已拉黑的过滤掉了，永远只有 `blocked = false` 的行
 *   ⇒ [onUnblock] 传 null（默认值）。
 * 视觉上没有任何其它差异，所以没有多余的开关参数。
 *
 * @param viaLan true = 走 LAN（USB / 网线）接入。**LAN 侧不给拉黑按钮**：拉黑走的是
 *   WiFi 接入控制名单（`/api/wifi/acl`），挡不住 LAN 进来的设备，给了就是假按钮。
 *   这一档改为显示一个「LAN」标签，让用户知道"这行没有按钮"是有原因的。
 * @param actionEnabled 右侧动作是否可点。调用方用它表达两件事：MAC 为空（名单以 MAC 为
 *   主键，没 MAC 没法下发）与「这一行的请求正在飞」（`aclPendingMac`）。
 * @param onUnblock null（默认）= 本调用点不存在"已拉黑的行"，[blocked] 为 true 时右侧留空。
 */
@Composable
fun UfiOnlineDeviceRow(
    hostname: String,
    ip: String,
    mac: String,
    viaLan: Boolean,
    blocked: Boolean,
    actionEnabled: Boolean,
    onBlock: () -> Unit,
    onUnblock: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val palette = LocalResolvedPalette.current
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Surface(shape = CircleShape, color = palette.accent.copy(alpha = 0.12f), modifier = Modifier.size(34.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    if (viaLan) Icons.Default.SettingsEthernet else Icons.Default.PhoneAndroid,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = palette.accent
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = hostname.ifEmpty { "未知设备" },
                style = UfiTextStyles.bodyEmphasis,
                color = palette.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            // 接入方式并到第二行：右侧要留给拉黑按钮，再摆一个 WiFi/LAN 标签会挤掉 IP/MAC。
            Text(
                text = listOf(
                    if (viaLan) "LAN" else "WiFi",
                    ip,
                    mac.uppercase()
                ).filter { it.isNotEmpty() }.joinToString("  ·  "),
                style = MaterialTheme.typography.labelSmall,
                color = palette.textSecondary.copy(alpha = 0.8f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
        Spacer(Modifier.width(8.dp))
        // 拉黑只作用于 WiFi 接入，LAN（USB/网线）侧设备挡不住，所以那一行不给按钮。
        if (!viaLan) {
            when {
                blocked && onUnblock != null -> {
                    // 行内按钮用 Subtle + Small：Small 档默认 fillWidth = false，放进 Row 不会撑满整行。
                    UfiButton(
                        variant = UfiButtonVariant.Subtle, size = UfiButtonSize.Small,
                        text = "解除",
                        onClick = onUnblock,
                        enabled = actionEnabled
                    )
                }
                // 已拉黑但调用点没给解除入口（仪表盘弹窗把已拉黑的过滤掉了，理论上到不了这里）：
                // 什么都不画，绝不退化成「拉黑」按钮 —— 那会让用户对已在名单里的设备再发一次写入。
                blocked -> Unit
                else -> UfiButton(
                    size = UfiButtonSize.Small,
                    text = "拉黑",
                    onClick = onBlock,
                    modifier = Modifier,
                    enabled = actionEnabled
                )
            }
        } else {
            Surface(
                color = palette.accent.copy(alpha = 0.1f),
                shape = UfiCardDefaults.microShape
            ) {
                Text(
                    text = "LAN",
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = palette.accent
                )
            }
        }
    }
}
