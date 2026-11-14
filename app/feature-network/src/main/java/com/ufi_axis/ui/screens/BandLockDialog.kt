package com.ufi_axis.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.ui.graphics.vector.rememberVectorPainter
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
        title = "频段锁定",
        icon = rememberVectorPainter(Icons.Filled.SignalCellularAlt),
        showCloseButton = false
    ) {
        UfiDialogBody {
            // 2026-11-08：A1 状态卡 + 频段卡内的操作栏比旧版高；小屏上弹窗高度
            // 会被 shell 的 82% 上限截断。这里让内容自己滚动，卡片底部的两个按钮
            // 仍跟随频段卡（不是固定在弹窗底部）。
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                BandLockSection(viewModel, state)
            }
        }

        Spacer(Modifier.height(8.dp))
    }
}