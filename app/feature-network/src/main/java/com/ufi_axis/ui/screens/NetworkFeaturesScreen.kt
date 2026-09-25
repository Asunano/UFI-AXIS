package com.ufi_axis.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.ufi_axis.ui.components.common.*
import com.ufi_axis.viewmodel.MainViewModel
import com.ufi_axis.viewmodel.state.deviceUnsupportedNote
import com.ufi_axis_core.contract.Capability

/**
 * 网络功能页面（从弹窗转为独立页面）
 * FOTA / SAMBA
 */
@Composable
fun NetworkFeaturesScreen(viewModel: MainViewModel, navController: NavHostController) {
    val deviceSettingsState by viewModel.deviceSettingsState.collectAsState()

    // 设备能力集（批 O / 3.5）：core 对 `/api/device/fota` 与 `/api/device/samba` 各有一处
    // route 门禁，缺能力回 501 NOT_SUPPORTED。这里把判定前置到 UI，开关在点之前就灰掉。
    //
    // ⚠ 能力集还没拉到 / 拉失败时 supports() 恒为 true —— 即"保持现状，全部可点"。
    // 详见 DeviceCapabilityState 的说明。
    val capabilities by viewModel.network.capabilityState.collectAsState()
    val fotaSupported = capabilities.supports(Capability.FOTA)
    val sambaSupported = capabilities.supports(Capability.SAMBA)

    // FOTA 与 SAMBA 都从 GET /api/device/settings 回显（FOTA 对应设备侧 UpgMode）。
    // fotaAutoUpdateOn 为 null = 设备没报这个字段，此时按「未禁用」显示。
    val fotaOff = deviceSettingsState.settings?.fotaAutoUpdateOn?.let { !it } ?: false
    var sambaOn by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.network.loadDeviceSettings()
        // 能力集自带"本进程只成功拉一次"的闸门，这里无条件调不会每次进页面都发请求。
        viewModel.network.loadDeviceCapabilities()
    }

    LaunchedEffect(deviceSettingsState.settings) {
        deviceSettingsState.settings?.let { sambaOn = it.sambaOn }
    }

    UfiScreenScaffold(
        title = "网络功能",
        navController = navController,
        showBack = true
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Spacer(Modifier.height(8.dp))

            UfiSettingsGroup {
                // FOTA
                // 置灰时把原说明保留、原因另起一行追加 —— 开关显示的 checked 仍是设备真值
                // （不支持不等于关着，本仓禁止假开关）。
                UfiSettingsToggle(
                    title = "禁用 FOTA",
                    description = if (fotaSupported) {
                        "阻止运营商推送更新"
                    } else {
                        "阻止运营商推送更新\n" + deviceUnsupportedNote("运营商自动升级（FOTA）")
                    },
                    checked = fotaOff,
                    onCheckedChange = { viewModel.network.setFotaDisabled(it) },
                    enabled = fotaSupported
                )
                UfiDivider()

                // SAMBA
                UfiSettingsToggle(
                    title = "文件共享 (SAMBA)",
                    description = if (sambaSupported) {
                        "SMB 局域网文件共享"
                    } else {
                        "SMB 局域网文件共享\n" + deviceUnsupportedNote("文件共享（SAMBA）")
                    },
                    checked = sambaOn,
                    onCheckedChange = { sambaOn = it; viewModel.network.setSambaSetting(it) },
                    enabled = sambaSupported
                )
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}
