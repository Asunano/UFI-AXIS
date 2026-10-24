// [F24] STABLE-UI-API：公共组件签名已冻结，请勿在无向后兼容前提下修改；实验性组件请使用 @UfiExperimentalApi（见 UfiStableApi.kt / UfiExperimentalApi.kt）。
package com.ufi_axis.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavHostController
import com.ufi_axis.ui.components.common.UfiButton
import com.ufi_axis.ui.components.common.UfiButtonSize
import com.ufi_axis.ui.components.common.UfiCompactProgressBar
import com.ufi_axis.ui.components.common.UfiConfirmDialog
import com.ufi_axis.ui.components.common.UfiErrorBanner
import com.ufi_axis.ui.components.common.UfiLoadingIndicator
import com.ufi_axis.ui.components.common.UfiPageBackground
import com.ufi_axis.ui.components.common.UfiScreenScaffold
import com.ufi_axis.ui.components.common.UfiSettingsItem
import com.ufi_axis.ui.components.common.UfiSettingsRowCard
import com.ufi_axis.ui.components.common.UfiToastHost
import com.ufi_axis.ui.components.common.ToastMessage
import com.ufi_axis.ui.components.common.ToastType
import com.ufi_axis.ui.theme.LocalResolvedPalette
import com.ufi_axis.ui.theme.Spacing
import com.ufi_axis.ui.theme.UfiTextStyles
import com.ufi_axis.viewmodel.MainViewModel
import com.ufi_axis.viewmodel.state.ComponentInfo
import kotlinx.coroutines.delay

/**
 * 内网穿透设置 → **组件管理**（2026-10-14 从 `TunnelSettingsScreen` 拆出，内容原样搬运）。
 *
 * > ## ⚠ 2026-10-14：本页已被「资源中心」取代，界面上不再有入口
 * > 取代它的是 `Routes.DETAIL_RESOURCES` → `com.ufi_axis.ui.resources.ResourcesScreen`
 * > （模块 `:app:feature-resources`）。原因：本页做的"查状态 → 下载安装 → 卸载 → 轮询进度"
 * > 与 Web 前端 ZIP 的管理是**同一套形状**，收进注册表驱动的一页之后，
 * > 新增一类可下载资源只写一条 `ResourceDescriptor`，不必再复制一遍这 260 行。
 * >
 * > 本文件**刻意保留**（连同 `Routes.DETAIL_TUNNEL_SETTINGS_COMPONENTS` 常量与
 * > `AppScreens.kt` 的注册），原因有二：
 * >  1. 本次改动的写权限只覆盖 `TunnelSettingsScreen.kt` 的入口行卡，不含本文件；
 * >  2. 本文件是工作区里**未提交的新文件**（`git status` 显示为 untracked），
 * >     删掉等于抹掉同事尚未提交的工作。
 * > 触发它的入口已改成跳资源中心，因此本页**不可达**；清理（删本文件 + 删常量 +
 * > 删注册 + 从 `ServiceGate.SERVICE_INDEPENDENT_ROUTES` 摘掉）留给后续单独一次改动。
 * > 它下方那三条 lifecycle 门控轮询的写法，正是资源中心抄的样板。
 *
 * 本页是原「内网穿透设置」页的第一组：远端清单（最新版本 / 下载体积 / 是否有新版）、
 * 每个组件的下载安装 / 更新 / 卸载、安装进度条。
 *
 * - **核心组件**：frpc / cloudflared 是隧道能不能起来的硬依赖，只是不随 APK 分发
 *   （解压后合计约 50MB），需要用户从上游官方 release 下载；core 侧做 SHA-256 校验 +
 *   arm64 ELF 体检后原子安装。本地上传兜底只在 Web 面板提供 —— app 端要走 SAF 选文件 +
 *   几十 MB 流式上传，收益不足。
 * - **检查更新**：原来是「核心组件」分组标题（`UfiGroupHeader`）右侧的组级动作，一项一张卡
 *   之后没有分组标题可挂，改为独立的一张行卡，动作进 `UfiSettingsItem` 的 trailing 槽。
 *
 * ## 本页自己的数据加载（原页那三条 `LaunchedEffect` 里属于组件的那部分）
 *  · `loadComponents()`：清单 + 已装状态（进页面必拉一次）；
 *  · 清单懒加载：远端清单由 core 在后台拉，这里每 1.5s 回读一次直到拿到 ——
 *    用户不需要手动点「检查更新」才看到内容；
 *  · 安装进度：只在有任务时 1s 轮询，任务落终态后 `refreshComponentTask` 会自己补拉列表。
 * 两个轮询都带 lifecycle 门控（原页同款）：退到后台就别再打网络。
 */
@Composable
fun TunnelSettingsComponentsScreen(viewModel: MainViewModel, navController: NavHostController) {
    val state by viewModel.tunnelState.collectAsState()
    val palette = LocalResolvedPalette.current
    var toastMessage by remember { mutableStateOf<ToastMessage?>(null) }
    var pendingUninstall by remember { mutableStateOf<String?>(null) }

    // ── lifecycle 门控（2026-09-21）──
    var lifecycleResumed by remember { mutableStateOf(true) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            lifecycleResumed = event == Lifecycle.Event.ON_RESUME
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            lifecycleResumed = false
        }
    }

    LaunchedEffect(Unit) {
        viewModel.tunnel.clearError()
        viewModel.tunnel.loadComponents()
    }
    // 懒加载：远端清单（最新版本 / 下载体积 / 是否有新版）由 core 在后台拉，
    // 这里每 1.5s 回读一次直到拿到 —— 用户不需要手动点「检查更新」才看到内容。
    LaunchedEffect(state.componentManifestLoading, lifecycleResumed) {
        while (state.componentManifestLoading && lifecycleResumed) {
            delay(1500)
            viewModel.tunnel.loadComponents()
        }
    }
    // 安装进度：只在有任务时 1s 轮询，任务落终态后 refreshComponentTask 会自己补拉列表
    LaunchedEffect(state.componentTask.active, lifecycleResumed) {
        while (state.componentTask.active && lifecycleResumed) {
            delay(1000)
            viewModel.tunnel.refreshComponentTask()
        }
    }

    val task = state.componentTask
    val manifestLoading = state.componentManifestLoading

    UfiScreenScaffold(
        title = "组件管理",
        navController = navController,
        showBack = true
    ) { padding ->
        UfiPageBackground(modifier = Modifier.padding(padding)) {
            // 一项一张 UfiSettingsRowCard（与网络设置页同构），卡间距统一走 Spacing.SettingsCardGap。
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.SettingsCardGap)) {

                if (state.componentManifestError.isNotBlank()) {
                    UfiErrorBanner(
                        message = "更新源不可用：${state.componentManifestError}（缺少 frpc / cloudflared 时隧道无法启动，可在 Web 面板用本地上传安装）",
                        onRetry = { viewModel.tunnel.loadComponents(refresh = true) }
                    )
                }
                UfiSettingsRowCard {
                    UfiSettingsItem(
                        title = "检查组件更新",
                        description = "从上游官方 release 拉取最新版本清单；已安装的组件会显示可更新版本与下载体积。",
                        trailing = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (manifestLoading) {
                                    UfiLoadingIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2f)
                                    Spacer(Modifier.width(6.dp))
                                }
                                UfiButton(
                                    size = UfiButtonSize.Small,
                                    text = "检查更新",
                                    onClick = { viewModel.tunnel.loadComponents(refresh = true) },
                                    enabled = !task.active && !manifestLoading
                                )
                            }
                        }
                    )
                }
                if (state.components.isEmpty()) {
                    // 清单为空时的提示：原来贴在分组卡里，一项一张卡之后单独成卡，文字不裸在卡外。
                    // 卡里放的不是设置行（一行 note 文案），按 UfiSettingsRowCard 的 KDoc 覆盖内距为
                    // PaddingValues(Spacing.XLarge)，贴近原分组卡的 20dp 内距。
                    UfiSettingsRowCard(contentPadding = PaddingValues(Spacing.XLarge)) {
                        Text(
                            // 2026-10-13：方位跟着按钮走 —— 「检查更新」原来挂在分组标题右侧
                            //（那时才叫"右上角"），现在拆成独立行卡，所以改成"上方"。
                            if (manifestLoading) "正在获取组件清单…" else "没读到组件信息，可点上方「检查更新」重试；未安装 frpc / cloudflared 前对应隧道无法启动。",
                            style = UfiTextStyles.note,
                            color = palette.textSecondary
                        )
                    }
                }
                state.components.forEach { c ->
                    UfiSettingsRowCard {
                        UfiSettingsItem(
                            title = c.name,
                            description = buildComponentDetail(c, manifestLoading),
                            // 操作按钮回到 UfiSettingsItem 的 trailing 槽（与本页其它设置行一致）：
                            // size = Small 的按钮本身很窄，「更新」+「卸载」两颗用 spacedBy(6.dp) 并排也塞得下。
                            trailing = {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    if (!c.installed || c.updateAvailable) {
                                        UfiButton(
                                            size = UfiButtonSize.Small,
                                            text = if (c.installed) "更新" else "下载安装",
                                            onClick = {
                                                viewModel.tunnel.installComponent(c.id)
                                                toastMessage =
                                                    ToastMessage("已开始下载，可离开本页", ToastType.INFO)
                                            },
                                            enabled = c.available && !task.active
                                        )
                                    }
                                    if (c.installed) {
                                        UfiButton(
                                            size = UfiButtonSize.Small,
                                            text = "卸载",
                                            onClick = { pendingUninstall = c.id },
                                            enabled = !task.active
                                        )
                                    }
                                }
                            }
                        )
                        // 进度条与状态消息只在"这个组件正在安装"时出现：空闲态不留占位行，
                        // 行高才和同页其它 UfiSettingsItem 对齐；装完列表会自己刷新版本信息。
                        if (task.id == c.id && task.active) {
                            if (task.message.isNotBlank()) {
                                Text(
                                    task.message,
                                    style = UfiTextStyles.note,
                                    color = palette.accent,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Spacer(Modifier.height(6.dp))
                            UfiCompactProgressBar(
                                progress = task.percent / 100f,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(Spacing.Medium))
        }

        UfiToastHost(toastMessage = toastMessage, onDismiss = { toastMessage = null })
    }

    pendingUninstall?.let { id ->
        UfiConfirmDialog(
            visible = true,
            title = "卸载 $id",
            text = "会删除该二进制与元数据。隧道配置不受影响，但在重新安装前无法启动。",
            confirmText = "卸载",
            destructive = true,
            onConfirm = {
                viewModel.tunnel.uninstallComponent(id)
                pendingUninstall = null
            },
            onDismiss = { pendingUninstall = null }
        )
    }
}

/**
 * 组件副标题：已装看占用与来源，未装看需下载多少。
 *
 * @param manifestLoading 远端清单还在后台拉：此时"更新源无可用版本"只是"还没拿到"，
 *   不能直接报无可用版本，否则用户以为坏了。
 */
private fun buildComponentDetail(c: ComponentInfo, manifestLoading: Boolean = false): String {
    val parts = mutableListOf<String>()
    if (c.installed) {
        parts += "已安装 " + c.installedVersion.ifBlank { "版本未知" }
        if (c.installedSize > 0) parts += com.ufi_axis.util.FormatUtils.formatBytes(c.installedSize)
        when (c.source) {
            "manual" -> parts += "本地上传"
            "legacy" -> parts += "旧版迁移"
        }
        if (c.updateAvailable) parts += "有新版 ${c.latestVersion}"
        else if (manifestLoading) parts += "正在检查新版本…"
    } else {
        parts += "未安装"
        if (c.downloadSize > 0) parts += "需下载约 " + com.ufi_axis.util.FormatUtils.formatBytes(c.downloadSize)
        if (!c.available) parts += if (manifestLoading) "正在获取最新版本…" else "更新源无可用版本"
    }
    return parts.joinToString(" · ")
}
