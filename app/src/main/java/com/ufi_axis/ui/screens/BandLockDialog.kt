package com.ufi_axis.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ufi_axis.ui.components.common.*
import com.ufi_axis.ui.theme.*
import com.ufi_axis.viewmodel.MainViewModel

/** 频段锁定弹窗 — 直接复用 BandLockSection */
@Composable
fun BandLockDialog(
    viewModel: MainViewModel,
    visible: Boolean,
    onDismiss: () -> Unit
) {
    val state by viewModel.networkState.collectAsState()

    LaunchedEffect(visible) {
        if (visible) viewModel.network.loadBandStatus()
    }

    UfiCustomDialog(
        visible = visible,
        onDismiss = onDismiss,
        title = "频段锁定"
    ) {
        BandLockSection(viewModel, state)

        Spacer(Modifier.height(8.dp))
    }
}