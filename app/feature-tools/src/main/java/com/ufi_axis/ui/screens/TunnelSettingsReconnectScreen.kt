// [F24] STABLE-UI-API：公共组件签名已冻结，请勿在无向后兼容前提下修改；实验性组件请使用 @UfiExperimentalApi（见 UfiStableApi.kt / UfiExperimentalApi.kt）。
package com.ufi_axis.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.ufi_axis.data.notification.NotificationCenter
import com.ufi_axis.data.notification.NotifyPrefs
import com.ufi_axis.ui.components.common.ToastMessage
import com.ufi_axis.ui.components.common.ToastType
import com.ufi_axis.ui.components.common.UfiDropdown
import com.ufi_axis.ui.components.common.UfiInfoRow
import com.ufi_axis.ui.components.common.UfiPageBackground
import com.ufi_axis.ui.components.common.UfiScreenScaffold
import com.ufi_axis.ui.components.common.UfiSettingsItem
import com.ufi_axis.ui.components.common.UfiSettingsRowCard
import com.ufi_axis.ui.components.common.UfiSettingsToggle
import com.ufi_axis.ui.components.common.UfiToastHost
import com.ufi_axis.ui.theme.LocalResolvedPalette
import com.ufi_axis.ui.theme.Spacing
import com.ufi_axis.viewmodel.MainViewModel

/**
 * 内网穿透设置 → **连接与重连**（2026-10-14 从 `TunnelSettingsScreen` 拆出，内容原样搬运）。
 *
 * 三项都由后端 `AppSettings` 持久化，经 `GET/PUT /api/tunnel/settings` 读写，本页只负责
 * 显示与提交（失败回滚）：
 *
 * - **启动失败通知**：app 端在 `/status` 出现 非Error→Error 边沿时经 NotificationCenter 发出
 *   （只在 app 正轮询隧道状态时检测，后台不常驻）；
 * - **断开自动重连**：core `TunnelManager` 看护协程按巡检间隔拉起"期望在跑但没在跑"的实例；
 *   手动停止的不拉起，后端重启会恢复上次在跑的实例，连续失败达上限后停手；
 * - **巡检间隔**：自动重连的检查周期（10~120 秒，每 10 秒一档）。2026-10-14 由滑块改成下拉框 ——
 *   12 个枚举值摊在轨道上既难对准、又要单独占一行刻度；标题与选择器同处一行更省版面。
 *
 * 末尾的「看护中」只读行来自 `/api/tunnel/settings` 的 `frp_desired` / `cf_desired`，
 * 让用户能解释"它为什么自己起来了"。
 *
 * ## 本页自己的数据加载
 * `loadTunnelSettings()` 是这份设定的真源（带新鲜度闸门，重复进入由闸门吸收），
 * 同时它也是 `tunnelGuardedFrp` / `tunnelGuardedCf` 的写入点 —— 少了它，「看护中」那行永远是空的。
 */
@Composable
fun TunnelSettingsReconnectScreen(viewModel: MainViewModel, navController: NavHostController) {
    val state by viewModel.tunnelState.collectAsState()
    val palette = LocalResolvedPalette.current
    val context = LocalContext.current
    var toastMessage by remember { mutableStateOf<ToastMessage?>(null) }

    LaunchedEffect(Unit) {
        viewModel.tunnel.clearError()
        viewModel.tunnel.loadTunnelSettings()
    }

    /** 后端"期望在跑"的实例（看护依据） */
    val guardedNames = state.tunnelGuardedFrp + state.tunnelGuardedCf

    UfiScreenScaffold(
        title = "连接与重连",
        navController = navController,
        showBack = true
    ) { padding ->
        UfiPageBackground(modifier = Modifier.padding(padding)) {
            // 一项一张 UfiSettingsRowCard（与网络设置页同构），卡间距统一走 Spacing.SettingsCardGap。
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.SettingsCardGap)) {
                UfiSettingsRowCard {
                    UfiSettingsToggle(
                        title = "启动失败通知",
                        description = "打开隧道页面时检测到某条隧道从运行中变为失败，就发一条系统通知（每次断开只发一条）。" +
                            "app 没在轮询隧道状态时不会检测。受设置→通知与守护里的「系统通知推送」总开关约束。",
                        checked = state.tunnelNotifyOnFailure,
                        onCheckedChange = { on ->
                            viewModel.tunnel.setNotifyOnFailure(on)
                            // 只检测全局总闸；通知权限本身已由「系统通知推送」那一处统一接管，不在这里重复检测。
                            // 2026-09-08：判据由 KEY_ALERT_NOTIF 改为 KEY_NOTIFY_MASTER —— 隧道通知
                            // 现在有自己的分类键（KEY_TUNNEL_NOTIF），不再借用告警键。
                            if (on && !NotifyPrefs.switchOn(
                                    context, NotificationCenter.KEY_NOTIFY_MASTER, false
                                )
                            ) {
                                toastMessage = ToastMessage(
                                    text = "「系统通知推送」总开关未开启，隧道通知发不出来",
                                    type = ToastType.WARNING,
                                    durationMs = 5000L,
                                    subtitle = "去 设置 → 通知与守护 → 告警设置 打开"
                                )
                            }
                        }
                    )
                }
                UfiSettingsRowCard {
                    UfiSettingsToggle(
                        title = "断开自动重连",
                        description = "隧道意外中断后按下方间隔自动重连；设备端服务重启后也会恢复上次运行中的隧道。" +
                            "连续失败 ${state.tunnelMaxReconnectAttempts} 次后停止重试，需手动恢复。" +
                            "手动停止过的隧道不会自动重连。",
                        checked = state.tunnelAutoReconnect,
                        onCheckedChange = { viewModel.tunnel.setAutoReconnect(it) }
                    )
                }
                // 「巡检间隔」2026-10-14：滑块 → 下拉框。
                //
                // 它是 10~120 秒、每 10 秒一档的 **12 个枚举值**，滑块在这件事上有两个毛病：
                // ① 12 档挤在几十 dp 的轨道里，想落到 60 要试两次；② 量程两端得单独占一行刻度。
                // 下拉框一次点准，值本身就在触发器上，刻度行也一并删掉。
                //
                // `enabled` 跟着上面那个开关走：自动重连关着时这个值不起作用，不能让它看起来能改
                //（本仓禁止假控件，见 UfiSettingsToggle 的说明）。
                UfiSettingsRowCard {
                    UfiSettingsItem(
                        title = "巡检间隔",
                        description = "多久检查一次隧道是否正常",
                        enabled = state.tunnelAutoReconnect,
                        trailing = {
                            UfiDropdown(
                                selectedValue = state.tunnelReconnectIntervalSec,
                                options = (10..120 step 10).toList(),
                                onValueSelected = { viewModel.tunnel.setReconnectInterval(it) },
                                modifier = Modifier.width(120.dp),
                                unitSuffix = "秒",
                                enabled = state.tunnelAutoReconnect
                            )
                        }
                    )
                }
                if (guardedNames.isNotEmpty()) {
                    UfiSettingsRowCard {
                        UfiInfoRow(label = "看护中", value = guardedNames.joinToString("、"))
                    }
                }
            }

            Spacer(Modifier.height(Spacing.Medium))
        }

        UfiToastHost(toastMessage = toastMessage, onDismiss = { toastMessage = null })
    }
}
