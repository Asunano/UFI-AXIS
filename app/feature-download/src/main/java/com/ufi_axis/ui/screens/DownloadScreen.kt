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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadScreen(viewModel: MainViewModel, navController: NavHostController) {
    val state by viewModel.downloadState.collectAsState()
    var showNewDialog by remember { mutableStateOf(false) }
    var toastMessage by remember { mutableStateOf<ToastMessage?>(null) }
    // 批量清除确认
    var showBatchDeleteConfirm by remember { mutableStateOf(false) }

    // 批量操作所需分组（与原 DownloadTasksUi 的过滤逻辑保持一致）
    val failedTasks = state.tasks.filter { it.status == "error" }
    val completedTasks = state.tasks.filter { it.status == "completed" }
    val hasCompleted = completedTasks.isNotEmpty()

    // 前台可见性：只在 ON_RESUME 期间轮询，切后台立刻停（与 MonitorScreen 同款门控）
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

    // 实时轮询：有活跃任务 2s，否则 6s 心跳。
    // 注意：旧实现在"没有 downloading/pending/meta 任务"时一次请求都不发，
    // 于是 meta→downloading→completed 这类由后端驱动的跃迁在界面上完全看不见
    // （典型表现：一直显示"获取种子信息中"，再看已经 100%）。心跳必须无条件发。
    //
    // 2026-09-05（新鲜度闸门落地后）两个调用点的 force 分工，改动前请先读
    // `DownloadModule.loadDownloads` 的 KDoc：
    // ① 进页面首帧**刻意不 force** —— 它正是要被 10s 新鲜窗口挡掉的那一次。
    //    用户反馈"从工具页进下载管理会自动播刷新动画"，页面内的 loading 动画早已删净，
    //    真正还在发生的就是这次无条件重拉带来的整页状态写。10s 内来回进出时数据本来就是
    //    轮询刚拉的，重拉纯属白付；超过 10s 或本进程首次进入时闸门自动放行，不会空页面。
    // ② 轮询**必须 force** —— 2s 周期比 10s 新鲜窗口短，不绕过闸门的话每 5 次里有 4 次
    //    会被判成"数据还新鲜"而直接 return，实际刷新间隔退化成 10s，
    //    等于把"下载进度实时可见"降级掉（进度条会看起来卡住不动）。
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
            // 溢出菜单：批量操作（重试失败 / 清除已完成）—— 与文件管理同款 UfiPopupMenu
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
                                toastMessage = ToastMessage("已重试 $count 个失败任务", ToastType.INFO)
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
            // 手动刷新：下拉手势删掉后，这里是唯一的主动刷新入口。
            // 不传 silent → 理论上会翻 isLoading；但列表区已经不再因 isLoading 换成骨架
            // （2026-09-04 删除，见下方 content 里的说明），且 2026-09-05 起 isLoading
            // 只在"本进程从未成功加载过"时才写 —— 手动刷新必然已经加载过，所以这里
            // 一个字都不写；空态 item 也不再看 isLoading，点它不会闪任何东西。
            // force = true：用户显式动作，必须立刻打一次请求 —— 被 10s 新鲜窗口挡掉的话
            // 这个按钮就成了"点了没反应"的假按钮。
            IconButton(onClick = { viewModel.downloads.loadDownloads(force = true) }) {
                Icon(Icons.Default.Refresh, contentDescription = "刷新")
            }
            // 下载设置：跳转新界面
            IconButton(onClick = { navController.navigate(Routes.DETAIL_DOWNLOAD_SETTINGS) }) {
                Icon(Icons.Default.Settings, contentDescription = "下载设置")
            }
        }
    ) { padding ->
        Box(Modifier.fillMaxSize()) {
            // 2026-09-04 去掉下拉刷新：原来这里是
            // `PullToRefreshBox(isRefreshing = state.isLoading, onRefresh = { loadDownloads() })`。
            // 删的理由不只是观感统一 —— 本页有 2s/6s 的常驻轮询，`isRefreshing` 接 isLoading 时，
            // 任何一次非静默重载都会在顶部甩出转圈；历史上「每 2s 闪一次刷新圈」就是这么来的
            // （靠 loadDownloads(silent = true) 修掉，那条链路本次一行未动）。
            // 手势本身也和列表滚动抢通道。手动刷新入口已上移到顶栏（见 actions）。
            Box(Modifier.padding(padding).fillMaxSize()) {
                Column(Modifier.fillMaxSize()) {
                    state.errorMessage?.let { error -> UfiErrorBanner(message = error) }
                    // 2026-09-04：首屏骨架（`if (state.isLoading && state.tasks.isEmpty()) UfiSkeletonList(rows = 5)`）
                    // 整段删除，且**不换任何加载动画**——用户明确不要动画。
                    //
                    // 那个骨架为什么会"闪一下"（机械原因，删掉才算彻底解决）：
                    // ① 进页面的 `LaunchedEffect(lifecycleResumed)` 第一件事就是 `loadDownloads()`（非 silent），
                    //    DownloadModule 里第一行便是 `isLoading = true`；
                    // ② core 是本机 HTTP，`GET /api/downloads` 通常 100~300ms 就回来，随后 isLoading=false
                    //    且 tasks 非空 —— 骨架的存活期就是这几帧到几百毫秒，典型的"一闪而过"；
                    // ③ 更糟的是它和列表是**互斥的两棵子树**：骨架挂/卸的同时 DownloadTasksTab 整棵
                    //    （分段 Tab + LazyColumn）跟着卸/挂，一次硬切换。已有任务时 tasks 从"上一次的列表"
                    //    经历过渡再回到列表，于是看到的是 列表→骨架→列表 两次子树替换，闪得更明显。
                    // 删掉之后 DownloadTasksTab 从第一帧就挂在树上，首屏只是"它里面还没有 item"，
                    // 没有子树替换，也就没有任何可闪的东西。
                    //
                    // 首屏无数据时画什么（2026-09-05 更新）：DownloadTasksTab 里那条空态 item
                    // **恒定挂载**，只有文案按 downloadEmptyPhase 分档 —— 首屏未加载完说"正在读取
                    // 下载任务"，加载过之后才说"还没有下载任务"。原来的判定带 `&& !state.isLoading`，
                    // 空态会随加载态挂/卸，那正是用户说的"中间那块图标和描述闪一下"。
                    //
                    // 2026-09-04 补充（第二轮：用户反馈"还有全屏刷新动画"）：
                    // 本页**页面内**已不存在任何入场/加载动效与子树互斥切换 ——
                    // DownloadTasksTab 里最后一处 `if (空) 空态 else LazyColumn` 也已改为
                    // 「LazyColumn 恒定挂载 + 空态降级为一条 item」，见该文件内说明。
                    // 仍然看得见的"整屏动效"只剩下**公共层**两处，按约定不在本次改动范围内：
                    //   ① 路由转场：本页是 DETAIL 路由（AppScreens.kt: Routes.DETAIL_DOWNLOADS），
                    //      MainNavGraph 给所有 DETAIL 页统一挂 `detailEnter()` =
                    //      slideInHorizontally(initialOffsetX = { it }，**整屏**) + fadeIn（Navigation.kt:210-218），
                    //      时长跟随「外观」页设置（默认 380ms）。2026-09-05 位移由半屏改成整屏以与退出对称，
                    //      观感就是"整页从右边刷进来"。进页面必播一次。
                    //   ② 标题栏：UfiScreenScaffold → UfiHeader 里标题有 350ms 的
                    //      RenderEffect 模糊入场（UfiScaffold.kt:105-112，HEADER_TITLE_BLUR_MS）。
                    // 这两处改了会影响全站所有页面，需要用户决策，故只在汇报里指出。
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
