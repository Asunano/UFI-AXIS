package com.ufi_axis.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.IntRect
import androidx.navigation.NavHostController
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlin.math.roundToInt
import com.ufi_axis.ui.components.common.*
import com.ufi_axis.ui.navigation.Routes
import com.ufi_axis.util.FormatUtils
import com.ufi_axis.viewmodel.MainViewModel
import com.ufi_axis.viewmodel.state.DownloadState
import com.ufi_axis.viewmodel.state.DuplicateInfo
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import com.ufi_axis.ui.navigation.ufiNavigateOnce

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadScreen(viewModel: MainViewModel, navController: NavHostController) {
    val state by viewModel.downloadState.collectAsState()
    var showNewDialog by remember { mutableStateOf(false) }
    var toastMessage by remember { mutableStateOf<ToastMessage?>(null) }
    // 批量清除确认
    var showBatchDeleteConfirm by remember { mutableStateOf(false) }

    // 批量操作所需分组（过滤口径与 DownloadTasksUi 一致）
    val failedTasks = state.tasks.filter { it.status == "error" }
    val completedTasks = state.tasks.filter { it.status == "completed" }
    val hasCompleted = completedTasks.isNotEmpty()

    // 前台可见性：仅在 ON_RESUME 期间轮询，切到后台立即停止（门控方式与 MonitorScreen 一致）
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

    // 实时轮询：存在活跃任务时 2s，否则 6s 心跳。
    // 心跳必须无条件发送：若在「没有 downloading/pending/meta 任务」时不发请求，由后端
    // 驱动的 meta→downloading→completed 跃迁在界面上不可见（表现为长期停留在「获取种子
    // 信息中」，随后直接显示 100%）。
    //
    // 两个调用点的 force 分工见 `DownloadModule.loadDownloads` 的 KDoc：
    // ① 进入页面的首帧刻意不 force —— 它正是要被 10s 新鲜窗口拦下的那一次。
    //    页面内已无加载动画，剩余可感知的刷新来自该次无条件重拉带来的整页状态写入；
    //    10s 内往返进出时数据由轮询刚拉取，重拉无收益；超过 10s 或本进程首次进入时窗口
    //    自动放行，不会出现空白页。
    // ② 轮询必须 force —— 2s 周期短于 10s 新鲜窗口，不绕过窗口时多数轮次会被判定为数据
    //    仍新鲜而直接返回，实际刷新间隔退化为 10s，下载进度的实时可见性随之失效。
    LaunchedEffect(lifecycleResumed) {
        if (!lifecycleResumed) return@LaunchedEffect
        viewModel.downloads.loadDownloads()
        while (isActive) {
            val hasActive = state.tasks.any { it.status in listOf("downloading", "pending", "meta", "verifying") }
            delay(if (hasActive) 2000L else 6000L)
            viewModel.downloads.loadDownloads(silent = true, force = true)
        }
    }

    // 更多菜单状态与锚点（与文件管理 FileToolbar 同款 UfiPopupMenu）
    var moreExpanded by remember { mutableStateOf(false) }
    var moreAnchorBounds by remember { mutableStateOf(IntRect.Zero) }

    UfiScreenScaffold(
        title = "下载管理",
        navController = navController,
        showBack = true,
        actions = {
            // 溢出菜单：批量操作（重试失败 / 清除已完成），与文件管理使用同一个 UfiPopupMenu
            Box(
                modifier = Modifier.onGloballyPositioned { coords ->
                    val r = coords.boundsInWindow()
                    moreAnchorBounds = IntRect(
                        r.left.roundToInt(),
                        r.top.roundToInt(),
                        r.right.roundToInt(),
                        r.bottom.roundToInt()
                    )
                }
            ) {
                IconButton(onClick = { moreExpanded = !moreExpanded }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "更多操作")
                }
                UfiPopupMenu(
                    visible = moreExpanded,
                    onDismiss = { moreExpanded = false },
                    anchorBounds = moreAnchorBounds,
                    options = listOf(
                        UfiPopupOption(
                            id = "retry",
                            label = "重试失败",
                            icon = Icons.Default.Refresh,
                            onClick = {
                                val count = failedTasks.size
                                failedTasks.forEach { viewModel.downloads.retryDownload(it.id) }
                                toastMessage = ToastMessage("已重试 $count 个失败任务", ToastType.SUCCESS)
                            }
                        ),
                        UfiPopupOption(
                            id = "clear",
                            label = "清除已完成",
                            icon = Icons.Default.DeleteSweep,
                            onClick = { showBatchDeleteConfirm = true }
                        )
                    )
                )
            }
            // 顶栏不提供「刷新」按钮：本页有 2s/6s 常驻轮询，批量操作走溢出菜单的
            //「重试失败」；额外的整页重拉按钮会使用户误认为数据不会自动更新。
            IconButton(onClick = { navController.ufiNavigateOnce(Routes.DETAIL_DOWNLOAD_SETTINGS) }) {
                Icon(Icons.Default.Settings, contentDescription = "下载设置")
            }
        }
    ) { padding ->
        Box(Modifier.fillMaxSize()) {
            // 不使用下拉刷新：`PullToRefreshBox(isRefreshing = state.isLoading)` 形态下，
            // 本页有 2s/6s 常驻轮询，`isRefreshing` 接 isLoading 时任何一次非静默重载都会在
            // 顶部显示转圈；下拉手势还与列表纵向滚动竞争同一手势通道。
            // 轮询走 `loadDownloads(silent = true)`，因此不产生转圈；本页不设手动刷新入口，
            // 不应重新引入。
            Box(Modifier.padding(padding).fillMaxSize()) {
                Column(Modifier.fillMaxSize()) {
                    state.errorMessage?.let { error -> UfiErrorBanner(message = error) }
                    // 首屏不使用骨架屏，也不替换为其他加载动画。
                    //
                    // 骨架屏闪烁的成因：
                    // ① 进入页面时 `LaunchedEffect(lifecycleResumed)` 首先调用
                    //    `loadDownloads()`（非 silent），DownloadModule 中第一行即
                    //    `isLoading = true`；
                    // ② core 为本机 HTTP，`GET /api/downloads` 通常在 100~300ms 内返回，
                    //    随后 isLoading=false 且 tasks 非空 —— 骨架存活时间仅为数帧至数百
                    //    毫秒；
                    // ③ 骨架与列表是互斥的两棵子树：骨架挂载 / 卸载的同时 DownloadTasksTab
                    //    （分段 Tab + LazyColumn）整棵卸载 / 挂载，构成一次硬切换。已有任务时
                    //    列表经历 列表→骨架→列表 两次子树替换，闪烁更明显。
                    // 移除后 DownloadTasksTab 从第一帧起常驻组合树，首屏只表现为其内部暂无
                    // item，不存在子树替换。
                    //
                    // 首屏无数据时：DownloadTasksTab 中的空态 item 恒定挂载，仅文案按
                    // downloadEmptyPhase 分档 —— 加载未完成时显示「正在读取下载任务」，加载
                    // 完成后显示「还没有下载任务」。若空态的挂载条件包含 `!state.isLoading`，
                    // 空态会随加载态挂载 / 卸载，产生可见闪烁。
                    //
                    // 页面内不存在任何入场 / 加载动效与子树互斥切换。仍可见的整屏动效来自
                    // 公共层两处，改动会影响全站所有页面：
                    //   ① 路由转场：本页为 DETAIL 路由（AppScreens.kt:
                    //      Routes.DETAIL_DOWNLOADS），MainNavGraph 为所有 DETAIL 页统一挂
                    //      `detailSharedAxisEnter` = slideInHorizontally { it }（整屏，无
                    //      fade），时长跟随「外观」页设置（默认 380ms）；进入页面必然播放
                    //      一次。
                    //   ② 标题栏：UfiScreenScaffold → UfiHeader 的标题有 350ms RenderEffect
                    //      模糊入场（UfiScaffold.kt:105-112，HEADER_TITLE_BLUR_MS）。
                    DownloadTasksTab(state, viewModel)
                }
            }
            // 新建下载：右下角 FAB（列表底部已预留 88dp 留白避免遮挡最后一张卡片）
            // padding 加大，避免过于贴近屏幕右下角
            UfiFloatingActionButton(
                icon = Icons.Default.Add,
                onClick = { showNewDialog = true },
                contentDescription = "新建下载",
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 40.dp, bottom = 40.dp)
                    .navigationBarsPadding()
            )
            UfiToastHost(toastMessage = toastMessage, onDismiss = { toastMessage = null })

            if (showBatchDeleteConfirm) {
                UfiConfirmDialog(
                    title = "清除已完成",
                    text = "确定删除所有 ${completedTasks.size} 个已完成任务${
                        if (completedTasks.any { it.savePath.isNotBlank() })
                            "及下载文件？\n如需保留文件，请在单个任务中删除"
                        else "？"
                    }",
                    confirmText = "全部删除",
                    onConfirm = {
                        showBatchDeleteConfirm = false
                        val count = completedTasks.size
                        viewModel.downloads.clearCompletedDownloads()
                        toastMessage = ToastMessage("已清除 $count 个任务", ToastType.SUCCESS)
                    },
                    onDismiss = { showBatchDeleteConfirm = false },
                    destructive = true
                )
            }
        }
    }

    if (showNewDialog) {
        NewDownloadDialog(
            config = state.config,
            // 目录选择器的取数：公共件在 :app:ui 不能碰 viewmodel，能力从这里注入。
            browse = viewModel.downloads::browseDirs,
            onDismiss = { showNewDialog = false },
            onConfirm = { url, fileName, savePath, speedLimit, connections ->
                viewModel.downloads.createDownload(url, fileName, savePath, speedLimit, connections)
                showNewDialog = false
            }
        )
    }

    state.duplicateInfo?.let { info ->
        UfiAlertDialog(
            title = "重复下载",
            text = "已有相同下载记录「${info.existingTask.fileName.ifBlank { info.existingTask.url.substringAfterLast("/") }}」" +
                "（${info.existingTask.status}，${FormatUtils.formatBytes(info.existingTask.totalSize)}），" +
                "是否将文件另存为「${info.suggestedFileName}」继续下载？",
            confirmText = "另存为",
            onConfirm = {
                viewModel.downloads.createDownloadForce(
                    url = info.newUrl, fileName = info.suggestedFileName,
                    savePath = info.savePath, speedLimit = info.speedLimit, connections = info.connections
                )
            },
            onDismiss = { viewModel.downloads.dismissDuplicate() }
        )
    }

}
