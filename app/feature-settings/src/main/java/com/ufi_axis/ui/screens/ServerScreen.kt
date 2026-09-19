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
 * - 本入口显式调用 [onRepairRequested]（MainActivity：清 token + reset 密钥 + 回 SetupScreen）。
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
    var showSwitchConfirm by remember { mutableStateOf(false) }
    var displayEndpoint by remember { mutableStateOf("${prefs.serverIp}:${prefs.serverPort}") }

    UfiScreenScaffold(title = "服务器", navController = navController, showBack = true) { padding ->
        UfiPageBackground(modifier = Modifier.padding(padding)) {
            Column(
                Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // 入口 1：服务器配置（连接地址 / 设备后台 / Web 面板）
                UfiSettingsRowCard {
                    UfiSettingsItem(
                        icon = Icons.Default.Router,
                        title = "服务器配置",
                        description = "连接地址 · 设备后台 · Web 面板",
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

                // 入口 3：配对与访问
                UfiSettingsRowCard {
                    UfiSettingsItem(
                        icon = Icons.Default.Smartphone,
                        title = "配对与访问",
                        description = "已配对设备 · 配对密码 · 数量限制",
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
                        description = "当前 $displayEndpoint · 清除本机凭据后重新配对",
                        onClick = { showSwitchConfirm = true },
                        trailing = { UfiSettingsChevron() }
                    )
                }

                Spacer(Modifier.height(Spacing.Large))
            }
        }

        if (showSwitchConfirm) {
            UfiConfirmDialog(
                visible = true,
                title = "切换设备 / 退出",
                text = "将清除本机保存的连接凭据与设备身份密钥，并回到配对引导页。\n\n" +
                    "· 换到另一台 Core：需重新输入配对密码\n" +
                    "· 仍连当前设备：重新配对即可，服务端配对记录不会自动删除\n" +
                    "· 本机缓存的仪表盘数据会一并清空",
                confirmText = "退出并重新配对",
                dismissText = "取消",
                destructive = true,
                onDismiss = { showSwitchConfirm = false },
                onConfirm = {
                    showSwitchConfirm = false
                    displayEndpoint = ""
                    onRepairRequested()
                }
            )
        }
    }
}
