package com.ufi_axis.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.ufi_axis.ui.components.common.UfiPageBackground
import com.ufi_axis.ui.components.common.UfiScreenScaffold
import com.ufi_axis.ui.components.common.UfiSettingsChevron
import com.ufi_axis.ui.components.common.UfiSettingsItem
import com.ufi_axis.ui.components.common.UfiSettingsRowCard
import com.ufi_axis.ui.navigation.Routes
import com.ufi_axis.ui.theme.Spacing
import com.ufi_axis.viewmodel.MainViewModel

/**
 * 「服务器」二级入口主页（v2 2026-08-23：去掉顶部 Tab，改成 3 张入口卡）。
 *
 * 历史：原 v1 在页面顶部 UfiScrollableTabRow「服务器配置 / 设备控制 / 配对管理」三 Tab，
 * 各 Tab 内嵌调用对应 Screen（showHeader=false）。现按用户偏好去掉 Tab，
 * 把 3 个二级页平铺成 3 张入口卡（图标 + 标题 + 副标题 + chevron），
 * 点击后 navigate 到 DETAIL_SERVER_CONFIG / DETAIL_DEVICE_CONTROL / DETAIL_PAIRING
 * 三个独立二级页（路由已在 NavScreens.kt 定义，AppScreens.kt 已挂载）。
 *
 * 容器与行布局全部走公共组件 [UfiSettingsRowCard] + [UfiSettingsItem]
 * （2026-08-30：原来这里手搓 rowModifier 四链 + Row/Icon/Column/Text，各页各写一份）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerScreen(
    viewModel: MainViewModel,
    onServerConfigChanged: () -> Unit,
    navController: NavHostController
) {
    UfiScreenScaffold(title = "服务器", navController = navController, showBack = true) { padding ->
        UfiPageBackground(modifier = Modifier.padding(padding)) {
            Column(
                Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // 入口 1：服务器配置（Core 连接 / 网关密码 / QoS）
                UfiSettingsRowCard {
                    UfiSettingsItem(
                        icon = Icons.Default.Router,
                        title = "服务器配置",
                        description = "Core 连接 · 网关密码 · QoS 性能",
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

                // 入口 3：配对管理
                UfiSettingsRowCard {
                    UfiSettingsItem(
                        icon = Icons.Default.Smartphone,
                        title = "配对管理",
                        description = "已配对设备列表与密码管理",
                        onClick = { navController.navigate(Routes.DETAIL_PAIRING) },
                        trailing = { UfiSettingsChevron() }
                    )
                }

                Spacer(Modifier.height(Spacing.Large))
            }
        }
    }
}
