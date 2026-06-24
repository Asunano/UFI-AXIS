package com.ufi_axis.ui.util

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.delay

/**
 * 跟踪当前 Composable 对应的 Activity/Fragment 是否在前台。
 * 用于控制自动刷新启停，避免后台页面持续拉取数据。
 */
@Composable
fun rememberIsActive(): State<Boolean> {
    var isActive by remember { mutableStateOf(true) }
    val context = LocalContext.current
    val lifecycleOwner = remember { (context as? androidx.lifecycle.LifecycleOwner) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            isActive = event == Lifecycle.Event.ON_RESUME
        }
        lifecycleOwner?.lifecycle?.addObserver(observer)
        onDispose { lifecycleOwner?.lifecycle?.removeObserver(observer) }
    }
    return rememberUpdatedState(isActive)
}

/**
 * 页面可见时按指定间隔自动执行刷新动作。
 *
 * 用法：
 * ```
 * val isActive = rememberIsActive()
 * useAutoRefresh(isActive, intervalMs = 10_000) {
 *     viewModel.dashboard.refreshDashboard()
 * }
 * ```
 */
@Composable
fun useAutoRefresh(
    enabled: Boolean,
    intervalMs: Long = 10_000L,
    onRefresh: suspend () -> Unit
) {
    LaunchedEffect(enabled) {
        if (!enabled) return@LaunchedEffect
        while (true) {
            delay(intervalMs)
            onRefresh()
        }
    }
}

/**
 * 一次性加载 + 自动刷新组合 Hook
 */
@Composable
fun useRefreshable(
    enabled: Boolean = true,
    intervalMs: Long = 10_000L,
    onLoad: suspend () -> Unit,
    onRefresh: suspend () -> Unit = onLoad
) {
    val isActive = rememberIsActive()

    // 首次加载
    LaunchedEffect(Unit) {
        onLoad()
    }

    // 定时刷新（仅前台）
    useAutoRefresh(enabled && isActive.value, intervalMs, onRefresh)
}
