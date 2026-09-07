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

/**
 * 网络功能页面（从弹窗转为独立页面）
 * FOTA / SAMBA
 */
@Composable
fun NetworkFeaturesScreen(viewModel: MainViewModel, navController: NavHostController) {
    val deviceSettingsState by viewModel.deviceSettingsState.collectAsState()

    // FOTA 与 SAMBA 都从 GET /api/device/settings 回显（FOTA 对应设备侧 UpgMode）。
    // fotaAutoUpdateOn 为 null = 设备没报这个字段，此时按「未禁用」显示。
    val fotaOff = deviceSettingsState.settings?.fotaAutoUpdateOn?.let { !it } ?: false
    var sambaOn by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.network.loadDeviceSettings()
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
                UfiSettingsToggle(
                    title = "禁用 FOTA",
                    description = "阻止运营商推送更新",
                    checked = fotaOff,
                    onCheckedChange = { viewModel.network.setFotaDisabled(it) }
                )
                UfiDivider()

                // SAMBA
                UfiSettingsToggle(
                    title = "文件共享 (SAMBA)",
                    description = "SMB 局域网文件共享",
                    checked = sambaOn,
                    onCheckedChange = { sambaOn = it; viewModel.network.setSambaSetting(it) }
                )
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}
