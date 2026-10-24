package com.ufi_axis.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.ufi_axis.ui.components.common.UfiConfirmDialog
import com.ufi_axis.ui.components.common.UfiPageBackground
import com.ufi_axis.ui.components.common.UfiScreenScaffold
import com.ufi_axis.ui.components.common.UfiSettingsChevron
import com.ufi_axis.ui.components.common.UfiSettingsItem
import com.ufi_axis.ui.components.common.UfiSettingsRowCard
import com.ufi_axis.ui.navigation.Routes
import com.ufi_axis.ui.theme.LocalResolvedPalette
import com.ufi_axis.ui.theme.Spacing
import com.ufi_axis.util.AppPreferences
import com.ufi_axis.viewmodel.MainViewModel

/**
 * 「服务器」二级入口主页。
 *
 * 2026-09 增加「切换设备 / 退出」：
 * - token 与本机 Keystore 绑定，换另一台 Core **不能**只改 IP，必须清凭据重新配对；
 * - 本入口显式调用 [onRepairRequested]，由 MainActivity 弹**一个**两步确认弹窗（警告 → 密码），\n *   避免"这一页一个确认 + 那边又一个密码弹窗"两个窗口叠在一起（2026-10-22）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerScreen(
    @Suppress("UNUSED_PARAMETER") viewModel: MainViewModel,
    @Suppress("UNUSED_PARAMETER") onServerConfigChanged: () -> Unit,
    navController: NavHostController,
    onRepairRequested: () -> Unit = {}
) {
    val context = LocalContext.current
    val prefs = remember { AppPreferences(context) }
    val palette = LocalResolvedPalette.current

    var displayEndpoint by remember { mutableStateOf("${prefs.serverIp}:${prefs.serverPort}") }

    UfiScreenScaffold(title = "服务器", navController = navController, showBack = true) { padding ->
        UfiPageBackground(modifier = Modifier.padding(padding)) {
            Column(
                Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // 入口 1：服务器配置（连接地址 / 设备后台 / 配对密码）
                UfiSettingsRowCard {
                    UfiSettingsItem(
                        icon = Icons.Default.Router,
                        title = "服务器配置",
                        // 2026-10-24：Web 面板已迁到资源中心，入口描述里不再列它。
                        description = "连接地址 · 设备后台",
                        onClick = { navController.navigate(Routes.DETAIL_SERVER_CONFIG) },
                        trailing = { UfiSettingsChevron() }
                    )
                }

                // 入口 2：设备控制（指示灯 / 性能模式 / WiFi 休眠 / 定时重启）
                UfiSettingsRowCard {
                    UfiSettingsItem(
                        icon = Icons.Default.Devices,
                        title = "设备控制",
                        description = "指示灯 · 性能模式 · WiFi 休眠 · 定时重启",
                        onClick = { navController.navigate(Routes.DETAIL_DEVICE_CONTROL) },
                        trailing = { UfiSettingsChevron() }
                    )
                }

                // 入口 3：安全中心（2026-10-15 由「配对与访问」升级而来）
                //
                // 为什么改名而不是新开一页：这一页本来就已经装着准入相关的东西
                //（配对信息 / 已配对设备 / 数量限制 / 解除全部配对），
                // 这一轮又加了黑名单、批准策略与它的通知渠道 —— 内容早就不止"配对与访问"。
                // 再开一页只会把同一件事拆到两处，用户得猜哪个在哪。
                // 2026-10-24：配对密码的修改入口已移到「服务器配置 → 连接配置」，
                // 描述里不再列它；本页只剩配对密码作为破坏性操作的门禁。
                UfiSettingsRowCard {
                    UfiSettingsItem(
                        icon = Icons.Default.Smartphone,
                        title = "安全中心",
                        description = "配对设备 · 黑名单 · 批准",
                        onClick = { navController.navigate(Routes.DETAIL_PAIRING) },
                        trailing = { UfiSettingsChevron() }
                    )
                }

                // 入口 4：切换设备 / 退出（危险操作，二次确认）
                UfiSettingsRowCard {
                    UfiSettingsItem(
                        icon = Icons.Default.Logout,
                        iconTint = palette.error,
                        title = "切换设备 / 退出",
                        description = "当前 $displayEndpoint · 只解除本机配对，不影响其他设备",
                        onClick = { onRepairRequested() },
                        trailing = { UfiSettingsChevron() }
                    )
                }

                Spacer(Modifier.height(Spacing.Large))
            }
        }

    }
}
