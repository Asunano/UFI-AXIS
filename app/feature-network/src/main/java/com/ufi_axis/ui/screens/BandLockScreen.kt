package com.ufi_axis.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.ufi_axis.ui.components.common.*
import com.ufi_axis.ui.theme.Spacing
import com.ufi_axis.viewmodel.MainViewModel

/**
 * 频段锁定 — 独立页面（路由 detail/band-lock）。
 * 由 NetworkScreen「频段锁定」导航进入，自带返回按钮（UfiScreenScaffold）。
 * 复用 BandLockSection 组件（定义在 NetworkScreen.kt，同 package 可见）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BandLockScreen(viewModel: MainViewModel, navController: NavHostController) {
    val state by viewModel.networkState.collectAsState()

    // 进入即加载频段状态
    LaunchedEffect(Unit) {
        viewModel.network.loadBandStatus()
    }

    UfiScreenScaffold(
        title = "频段锁定",
        navController = navController,
        showBack = true,
        actions = {
            IconButton(onClick = { viewModel.network.loadBandStatus() }) {
                Icon(Icons.Default.Refresh, null)
            }
        }
    ) { padding ->
        UfiPageBackground(modifier = Modifier.padding(padding)) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.CardHorizontalMargin)
            ) {
                Spacer(Modifier.height(8.dp))
                BandLockSection(viewModel, state)
                Spacer(Modifier.height(32.dp))
            }
        }
    }
}
