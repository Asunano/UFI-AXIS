package com.ufi_axis.ui.screens

import androidx.compose.runtime.*
import com.ufi_axis.ui.components.common.ToggleItem
import com.ufi_axis.ui.components.common.UfiToggleDialog
import com.ufi_axis.viewmodel.MainViewModel

/** 网络功能弹窗 — FOTA / USB 网络共享 / SAMBA / NFC */
@Composable
fun NetworkFeaturesDialog(
    viewModel: MainViewModel,
    visible: Boolean,
    onDismiss: () -> Unit
) {
    val deviceSettingsState by viewModel.deviceSettingsState.collectAsState()

    var fotaOff by remember { mutableStateOf(false) }
    var tetherOn by remember { mutableStateOf(false) }
    var sambaOn by remember { mutableStateOf(false) }
    var nfcOn by remember { mutableStateOf(false) }

    LaunchedEffect(visible) {
        if (visible) viewModel.network.loadDeviceSettings()
    }

    LaunchedEffect(deviceSettingsState.settings) {
        deviceSettingsState.settings?.asJsonObject?.let { json ->
            json.get("samba_switch")?.asString?.let { sambaOn = it == "1" }
            json.get("web_wifi_nfc_switch")?.asString?.let { nfcOn = it == "1" }
        }
    }

    UfiToggleDialog(
        visible = visible,
        onDismiss = onDismiss,
        title = "网络功能",
        description = "FOTA · USB 共享 · SAMBA · NFC",
        items = listOf(
            ToggleItem("禁用 FOTA", "阻止运营商推送更新", fotaOff,
                { fotaOff = it; viewModel.network.setFotaDisabled(it) }),
            ToggleItem("USB 网络共享", "通过 USB 共享网络", tetherOn,
                { tetherOn = it; viewModel.network.setUsbTethering(it) }),
            ToggleItem("文件共享 (SAMBA)", "SMB 局域网文件共享", sambaOn,
                { sambaOn = it; viewModel.network.setSambaSetting(it) }),
            ToggleItem("WiFi NFC", "NFC 触碰连接", nfcOn,
                { nfcOn = it; viewModel.network.setWifiNfc(it) })
        )
    )
}