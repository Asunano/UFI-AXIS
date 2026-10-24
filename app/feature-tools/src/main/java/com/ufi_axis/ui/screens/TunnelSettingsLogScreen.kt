// [F24] STABLE-UI-API：公共组件签名已冻结，请勿在无向后兼容前提下修改；实验性组件请使用 @UfiExperimentalApi（见 UfiStableApi.kt / UfiExperimentalApi.kt）。
package com.ufi_axis.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import com.ufi_axis.ui.components.common.ToastMessage
import com.ufi_axis.ui.components.common.ToastType
import com.ufi_axis.ui.components.common.UfiButton
import com.ufi_axis.ui.components.common.UfiButtonSize
import com.ufi_axis.ui.components.common.UfiConfirmDialog
import com.ufi_axis.ui.components.common.UfiInfoRow
import com.ufi_axis.ui.components.common.UfiPageBackground
import com.ufi_axis.ui.components.common.UfiScreenScaffold
import com.ufi_axis.ui.components.common.UfiSettingsItem
import com.ufi_axis.ui.components.common.UfiSettingsRowCard
import com.ufi_axis.ui.components.common.UfiToastHost
import com.ufi_axis.ui.theme.Spacing
import com.ufi_axis.viewmodel.MainViewModel

/**
 * 内网穿透设置 → **日志与路径**（2026-10-14 从 `TunnelSettingsScreen` 拆出，内容原样搬运）。
 *
 * 收两件"只读为主"的事：
 *  · **日志**：运行期全量写入 `logs/<隧道名>.log`，停止该隧道时整份删除；此处只提供清除按钮。
 *    「清除日志」原来是「日志」分组标题（`UfiGroupHeader`）右侧的组级动作，一项一张卡之后
 *    独立成一张行卡，动作进 `UfiSettingsItem` 的 trailing 槽。
 *  · **隧道类型与路径**：FRP / Cloudflare Tunnel 两种引擎各自把配置放在哪儿（只读说明）。
 *    这几行与「日志文件」那一行同属"这台设备把东西放在哪"，拆到两页反而要来回翻。
 *
 * ## 本页不拉任何数据
 * 页面上没有任何需要从 core 读取的设置项（清除日志是动作，其余是常量说明），
 * 所以这里没有 `LaunchedEffect` —— 不是漏了，是确实没有可拉的东西。
 */
@Composable
fun TunnelSettingsLogScreen(viewModel: MainViewModel, navController: NavHostController) {
    var toastMessage by remember { mutableStateOf<ToastMessage?>(null) }
    var showClearLogDialog by remember { mutableStateOf(false) }

    UfiScreenScaffold(
        title = "日志与路径",
        navController = navController,
        showBack = true
    ) { padding ->
        UfiPageBackground(modifier = Modifier.padding(padding)) {
            // 一项一张 UfiSettingsRowCard（与网络设置页同构），卡间距统一走 Spacing.SettingsCardGap。
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.SettingsCardGap)) {
                UfiSettingsRowCard {
                    UfiSettingsItem(
                        title = "清除日志",
                        description = "清空 FRP 与 Cloudflare Tunnel 的全部日志缓冲，无法恢复。",
                        trailing = {
                            UfiButton(size = UfiButtonSize.Small, text = "清除日志", onClick = { showClearLogDialog = true })
                        }
                    )
                }
                UfiSettingsRowCard {
                    UfiSettingsItem(
                        title = "保留策略",
                        description = "运行期全量写入日志文件，停止该隧道时整份删除"
                    )
                }
                UfiSettingsRowCard {
                    UfiSettingsItem(
                        title = "查看方式",
                        description = "隧道页默认显示最近 200 行；点“查看完整”读日志文件（最多尾部 256KB）"
                    )
                }
                UfiSettingsRowCard {
                    UfiInfoRow(label = "日志文件", value = "files/<引擎>/logs/<隧道名>.log")
                }

                UfiSettingsRowCard {
                    UfiSettingsItem(
                        title = "隧道类型",
                        description = "FRP · Cloudflare Tunnel（可同时运行多条）"
                    )
                }
                UfiSettingsRowCard {
                    UfiInfoRow(label = "FRP 配置", value = "files/frp/configs/<隧道名>.toml")
                }
                UfiSettingsRowCard {
                    UfiInfoRow(label = "CF token", value = "files/cloudflared/tunnels/<隧道名>.token")
                }
            }

            Spacer(Modifier.height(Spacing.Medium))
        }

        UfiToastHost(toastMessage = toastMessage, onDismiss = { toastMessage = null })
    }

    UfiConfirmDialog(
        visible = showClearLogDialog,
        title = "确认清除",
        text = "将清除 FRP 与 Cloudflare Tunnel 的全部日志缓冲，无法恢复。继续？",
        confirmText = "清除",
        destructive = true,
        onConfirm = {
            viewModel.tunnel.clearLogs()
            showClearLogDialog = false
            toastMessage = ToastMessage("日志已清除", ToastType.SUCCESS)
        },
        onDismiss = { showClearLogDialog = false }
    )
}
