package com.ufi_axis.ui.screens.filemanager

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import com.ufi_axis.ui.components.common.UfiPopupMenu
import com.ufi_axis.viewmodel.state.FileViewMode
import kotlin.math.roundToInt
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.InstallMobile
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.automirrored.filled.Launch
import androidx.navigation.NavHostController
import com.ufi_axis.data.api.FileItem
import com.ufi_axis.ui.components.common.UfiScreenScaffold
import com.ufi_axis.ui.components.common.UfiPopupOption
import com.ufi_axis.ui.components.common.UfiPageBackgroundBox
import com.ufi_axis.ui.components.common.UfiSkeletonListItem
import com.ufi_axis.ui.components.common.UfiToastHost
import com.ufi_axis.ui.components.common.ToastMessage
import com.ufi_axis.ui.components.common.ToastType
import com.ufi_axis.ui.screens.filemanager.components.FileEmptyState
import com.ufi_axis.ui.screens.filemanager.components.FileErrorState
import com.ufi_axis.ui.screens.filemanager.components.FileLoadingState
import com.ufi_axis.ui.screens.filemanager.components.BatchActionBar
import com.ufi_axis.ui.screens.filemanager.components.TransferBar
import com.ufi_axis.ui.screens.filemanager.components.FileRowCard
import com.ufi_axis.ui.screens.filemanager.components.FileIcon
import com.ufi_axis.ui.screens.filemanager.components.VolumeCard
import com.ufi_axis.ui.screens.filemanager.dialogs.ApkInstallDialog
import com.ufi_axis.ui.screens.filemanager.dialogs.DeleteConfirmDialog
import com.ufi_axis.ui.screens.filemanager.dialogs.FileInfoDialog
import com.ufi_axis.ui.screens.filemanager.dialogs.NewFolderDialog
import com.ufi_axis.ui.screens.filemanager.dialogs.RenameDialog
import com.ufi_axis.ui.screens.filemanager.dialogs.SortSheet
import com.ufi_axis.ui.screens.filemanager.dialogs.StoragePermissionDialog
import com.ufi_axis.ui.screens.filemanager.dialogs.SearchDialog
import com.ufi_axis.ui.theme.Spacing
import com.ufi_axis.ui.theme.UfiCardDefaults
import com.ufi_axis.ui.theme.UfiTextStyles
import com.ufi_axis.ui.theme.UfiMotion
import com.ufi_axis.ui.theme.UfiWeight
import com.ufi_axis.ui.theme.LocalResolvedPalette
import com.ufi_axis.ui.theme.ufiStandardCard
import com.ufi_axis.util.FormatUtils
import com.ufi_axis.ui.animation.blurEntrance
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import com.ufi_axis.viewmodel.module.FileManagerModule
import java.net.URLEncoder

/**
 * 文件管理器主屏（ticket T5 — 主屏布局骨架）。
 *
 * 将 T1 的占位占位 [androidx.compose.material3.Text] 占位替换为真实的、可编译的
 * 屏幕骨架：从 [FileManagerModule.state] 收集状态，并行触发初始数据拉取，
 * 并组合 [BreadcrumbBar] + [FileToolbar] + 主体列表（存储卷列表 / 文件列表）。
 *
 * 设计系统约束：
 *  - 颜色统一走 [com.ufi_axis.ui.theme.LocalResolvedPalette.current]，禁止 `colorScheme.tertiary`。
 *  - 滚动容器使用 [com.ufi_axis.ui.components.common.UfiPageBackgroundBox]（非滚动 Box，
 *    因为列表自身由 [LazyColumn] 滚动）。
 *  - [UfiScreenScaffold] 的 content 是 `(PaddingValues) -> Unit`，**不是 BoxScope**，
 *    因此此处禁止 `Modifier.align()`，布局一律用 [Column] 组织。
 *
 * 注意：[navController] 继续透传给 [UfiScreenScaffold] 以保留返回手势，但子屏路由
 * 暂未接通（TODO(T7)）。
 *
 * @param viewModel 文件管理器 ViewModel（直接持有 [FileManagerModule]）
 * @param navController 导航控制器
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileManagerRoot(viewModel: FileManagerModule, navController: NavHostController) {
    val state by viewModel.state.collectAsState()
    val palette = LocalResolvedPalette.current
    val errorMessage = state.errorMessage
    var showNewFolder by remember { mutableStateOf(false) }

    // T12 — 手机端（本应用）存储权限设置跳转启动器：仅用于打开**本机**设置页（合法同设备）。
    // 设备端（Core）授权绝不跨设备 startActivity，只靠「重试」重新查询状态。
    val context = LocalContext.current
    val storageLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        viewModel.checkStorageAccess()
    }

    // T7 — 上传文件选择器：选文件后调用 viewModel.uploadFileToServer，目标目录取回调时最新 currentPath。
    val uploadLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? -> uri?.let { viewModel.uploadFileToServer(it, state.currentPath) } }

    // T8b — 排序 / 操作面板 / 各弹窗的 holder（目标文件用 FileItem? 持有，确保关闭时仍可安全回调）。
    var showSort by remember { mutableStateOf(false) }
    var showRename by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<FileItem?>(null) }
    var showDelete by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<FileItem?>(null) }
    var showInfo by remember { mutableStateOf(false) }
    var infoTarget by remember { mutableStateOf<FileItem?>(null) }
    var showInstall by remember { mutableStateOf(false) }
    var installTarget by remember { mutableStateOf<FileItem?>(null) }

    var showSearch by remember { mutableStateOf(false) }

    // 一次性 toast 提示（复制/剪切/粘贴/删除等操作的反馈），替代常驻 PasteBanner。
    var toastMessage by remember { mutableStateOf<ToastMessage?>(null) }

    // 预览悬浮窗目标：图片/视频/音频点开时不再 navigate 进独立页，而是把目标文件存这里，
    // 由 FilePreviewOverlay 在文件管理器之上叠加显示。文本类仍走 navigate 进编辑器。
    var previewTarget by remember { mutableStateOf<FileItem?>(null) }

    // 视频全屏状态由 FilePreviewOverlay 回传；全屏时后台文件列表的 RenderEffect 模糊归零，
    // 避免与 SurfaceView 视频解码抢 GPU 带宽导致卡顿/掉帧。
    var videoFullscreen by remember { mutableStateOf(false) }

    // 预览悬浮窗打开时，把文件管理器主内容做模糊，营造磨砂背景（替代纯黑遮罩）。
    // minSdk=31，RenderEffect 模糊在 31+ 可用；0.dp 时等于无操作。
    // 视频全屏时强制关闭模糊，把 GPU 资源全让给视频层。
    // 打开时平滑模糊（磨砂观感），关闭时立即归零（snap）：关闭路径上若仍走 250ms tween，
    // 每帧都要重算 RenderEffect，与视频退出动画/解码抢 GPU，是「关闭掉帧」的次因；
    // 关闭瞬间浮层仍在淡出、背景基本被遮挡，snap 到 0 在视觉上无感。
    val previewBgBlurred = previewTarget != null && !videoFullscreen
    val bgBlur by animateDpAsState(
        targetValue = if (previewBgBlurred) 14.dp else 0.dp,
        animationSpec = if (previewBgBlurred) tween(UfiMotion.Duration.Fluid) else snap(),
        label = "fileManagerBgBlur"
    )

    // 列表/网格视图现在以 state.viewMode 为单一数据源（由 FileManagerModule 持有并持久化）。

    // T8b — 文件操作分派：行点击直接打开；三点按钮弹出 UfiPopupMenu 操作菜单（见 FileRowCard）。
    // 点击分发。判据来自 FileKind 这份唯一真源 —— 与列表图标、菜单文案共用同一张表，
    // 所以不会再出现「显示了视频图标却点不开」这种矛盾。没有内置预览的类别落到详情弹窗。
    fun resolveOpenRoute(f: FileItem) {
        if (f.isDirectory) {
            viewModel.navigateToDir(f.path)
            return
        }
        val encoded = URLEncoder.encode(f.path, "UTF-8")
        when (fileKindOf(f.name)) {
            // 图片/视频/音频：不再进独立导航页，改为在文件管理器之上叠加预览悬浮窗。
            FileKind.IMAGE -> previewTarget = f
            FileKind.VIDEO -> previewTarget = f
            FileKind.AUDIO -> previewTarget = f
            FileKind.TEXT -> navController.navigate("file/editor?path=" + encoded)
            else -> { infoTarget = f; showInfo = true }
        }
    }

    fun handleAction(action: String, f: FileItem) {
        when (action) {
            "open" -> resolveOpenRoute(f)
            "edit" -> navController.navigate("file/editor?path=" + URLEncoder.encode(f.path, "UTF-8"))
            "download" -> viewModel.downloadFileToPhone(f.path, f.name)
            "info" -> { infoTarget = f; showInfo = true }
            "copy" -> { viewModel.copyToClipboard(f.path, isCut = false) }
            "cut" -> { viewModel.copyToClipboard(f.path, isCut = true) }
            "rename" -> { renameTarget = f; showRename = true }
            "delete" -> { deleteTarget = f; showDelete = true }
            "install" -> { installTarget = f; showInstall = true }
        }
    }

    // 并行预拉取：只触发、不 await。下列方法均存在于 FileManagerModule。
    LaunchedEffect(Unit) {
        viewModel.loadDiskUsage()
        viewModel.checkStorageAccess()
    }

    // 操作结果一次性 toast：operationMessage 变化时弹出，随后清空以免重复触发。
    LaunchedEffect(state.operationMessage) {
        state.operationMessage?.let {
            toastMessage = ToastMessage(it, ToastType.SUCCESS)
            viewModel.clearFileOperationMessage()
        }
    }

    UfiScreenScaffold(
        title = "文件管理器",
        navController = navController,
        showHeader = false
    ) { padding ->
        UfiPageBackgroundBox(modifier = Modifier.fillMaxSize()) {
            // 嵌套 Box 提供 BoxScope，使底部悬浮条 / 顶部 Toast 的 Modifier.align() 合法。
            // 预览打开时只对「文件管理器内容」做模糊（bgBlur），作为悬浮窗的磨砂背景；
            // 浮层（FilePreviewOverlay）是本 Box 的兄弟节点、不在此 blur 作用域内，故保持清晰。
            Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .blur(bgBlur)
        ) {
            // 自绘标题栏（替代 UfiScreenScaffold 内置 UfiHeader，使其与文件列表处于同一模糊层）：
            // 预览打开时「文件管理器」标题与「退出」随内容一起磨砂，作为悬浮窗背景。
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp, bottom = 8.dp)
                    .padding(horizontal = Spacing.HeaderPaddingH)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .heightIn(min = 48.dp)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = LocalIndication.current
                        ) { navController.popBackStack() }
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                ) {
                    Text(
                        text = "退出",
                        color = palette.accent,
                        style = UfiTextStyles.bodyLead.copy(fontWeight = UfiWeight.Medium)
                    )
                }
                Text(
                    text = "文件管理器",
                    style = UfiTextStyles.headerTitle,
                    color = palette.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.align(Alignment.Center)
                )
            }

                // 地址栏与工具栏分离：BreadcrumbBar 单独一行展示可点击路径，
                // FileToolbar 只负责核心操作图标，避免多重路径导致布局异常。
                BreadcrumbBar(
                    currentPath = state.currentPath,
                    volumes = state.storageVolumes,
                    onNavigate = { viewModel.navigateToDir(it) },
                    onRoot = { viewModel.navigateToRoot() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.CardHorizontalMargin, vertical = 2.dp)
                )

                val canBack = state.currentPath.isNotEmpty() && state.currentPath != state.storageRoot
                // 系统返回键（手势 / 物理键）：还能向上时返回上一级目录（如 /Android/media → /Android）；
                // 到根目录才不拦截，交给导航栈 popBackStack 退出文件管理器。
                // 预览悬浮窗打开时本返回键让位给浮层的关闭逻辑（见下方 BackHandler），避免冲突。
                BackHandler(enabled = canBack && previewTarget == null) { viewModel.navigateToParent() }
                // 预览悬浮窗打开时：系统返回键优先关闭浮层。
                BackHandler(enabled = previewTarget != null) { previewTarget = null }
                FileToolbar(
                    viewMode = state.viewMode,
                    canBack = canBack,
                    onBack = { viewModel.navigateToParent() },
                    onToggleViewMode = {
                        viewModel.setViewMode(
                            if (state.viewMode == FileViewMode.GRID) FileViewMode.LIST else FileViewMode.GRID
                        )
                    },
                    onSort = { showSort = true },
                    onSearch = { showSearch = true },
                    canPaste = state.clipboard != null,
                    onPaste = { viewModel.pasteFromClipboard(state.currentPath) },
                    // 「更多」菜单每项都带图标：UfiPopupMenu 在 icon == null 时留一个 18dp 空位，
                    // 于是"有图标的项"和"没图标的项"文字仍然对齐但视觉上缺一块，
                    // 今天新加的「刷新」正是这种情况。图标沿用长按菜单同一套 Icons.Filled.*。
                    moreMenuOptions = buildList {
                        add(UfiPopupOption("multiselect", "多选", icon = Icons.Filled.Checklist, onClick = { viewModel.toggleMultiSelectMode() }))
                        add(UfiPopupOption("newfolder", "新建文件夹", icon = Icons.Filled.CreateNewFolder, onClick = { showNewFolder = true }))
                        add(UfiPopupOption("upload", "上传", icon = Icons.Filled.FileUpload, onClick = { uploadLauncher.launch("*/*") }))
                        add(UfiPopupOption("refresh", "刷新", icon = Icons.Default.Refresh, onClick = { viewModel.refreshFileList() }))
                        add(UfiPopupOption("storage", "存储权限", icon = Icons.Filled.Storage, onClick = { viewModel.checkStorageAccess() }))
                    }
                )

                // T14 — 搜索结果横幅：searchResults 非空时挂载，提供「清除」回到普通列表。
                val results = state.searchResults
                if (results != null) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Spacing.CardHorizontalMargin, vertical = 8.dp)
                            .ufiStandardCard()
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "搜索结果（${results.size}）",
                                style = MaterialTheme.typography.bodyMedium,
                                color = palette.textPrimary,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(onClick = { viewModel.clearSearchResults() }) {
                                Text("清除", color = palette.accent)
                            }
                        }
                    }
                }

                // 加载 / 错误 / 空态与列表主体（由 T6 的 FileStates 组件统一承载）。
                //
                // 2026-09-04 去掉下拉刷新：这里原来是 `PullToRefreshBox(isRefreshing = state.isLoading)`，
                // 现在换成普通 Box。两个理由：
                // ① 下拉手势和列表自身的纵向滚动抢同一个手势通道，长目录里往回滚一下就误触一次
                //    全量重载；② `isRefreshing` 直接接了 isLoading，任何一次进目录都会在顶部
                //    甩出一个转圈，与下面的骨架屏叠成两层加载态。
                // 手动刷新入口没有丢：顶栏「更多」菜单里的「刷新」项（见上方 moreMenuOptions）
                // 走的是同一个 loadFileList，删掉手势不影响用户主动刷新。
                Box(modifier = Modifier.weight(1f)) {
                    Column(Modifier.fillMaxSize()) {
                when {
                    // 首屏骨架：仅「正在加载且当前一条数据都没有」时出现。
                    // 已有列表时的刷新**不**换骨架 —— 否则每次刷新都把已渲染内容整块替换成灰块，
                    // 观感就是闪一下；数据原地更新反而更稳。
                    state.isLoading && state.files.isEmpty() && state.searchResults == null -> {
                        FileLoadingState(modifier = Modifier.weight(1f))
                    }
                    errorMessage != null -> {
                        FileErrorState(
                            message = errorMessage,
                            onRetry = { viewModel.loadFileList(state.currentPath) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                    else -> {
                        // 虚拟根（多卷 landing）：展示存储卷列表。
                        // 单卷时 storageRoot == mountPath 且直接进入文件列表，因此额外要求 size > 1，
                        // 否则单卷进入后 currentPath 永远等于 storageRoot，会卡在卷列表无法进入文件。
                        // T14 — 列表派生：搜索结果优先；否则普通文件列表，再按 filterType 分类筛选。
                        val baseList = state.searchResults ?: state.files

                        // ── 懒加载 = 渐进渲染（不是真分页）────────────────────────────────
                        // 依据：core 的 `GET /api/files/list` 只读一个 `path` 查询参数
                        // （FileRoutes.kt `get("/list")`），没有 offset/limit；app 侧 Retrofit
                        // `listFiles(@Query("path"))` 同样只透传 path。要做真分页得改服务端契约，
                        // 本次不动 core，所以在客户端分批**渲染**同一份已到手的数据：
                        // 先出前 RENDER_PAGE 条，滚到接近底部再追加一批。
                        // 收益全在首帧 —— 一个装了几百个文件的目录，item 的组合/测量被摊到多帧，
                        // 不再全压在"进目录"那一帧上（那是最容易被感知为卡顿的时刻）。
                        // 语义上没有任何数据丢失：baseList 一直是完整的，只是没全部挂到布局树上。
                        // 分页进度：按路径分槽持久化（rememberSaveable）。打开文本编辑页（独立 NavHost
                        // 目的地）再返回会让 FileManagerRoot 整体重组，普通 remember 会归零；用 saveable
                        // 后停留在原位置，不会异常弹回列表顶部。
                        var renderLimit by rememberSaveable(state.currentPath, state.searchResults, state.viewMode) {
                            mutableIntStateOf(FILE_RENDER_PAGE)
                        }
                        val displayFiles = baseList.take(renderLimit)
                        val hasMoreToRender = renderLimit < baseList.size

                        // 列表态与网格态各自一份滚动状态：判定"接近底部"要读各自的 layoutInfo。
                        // 两份都在这里无条件创建（而不是各自放进下面的 if 分支里）：
                        // 放进分支的话切换视图模式会让 remember 槽位随分支一起销毁，
                        // 切回来时滚动位置归零 —— 用户切个视图就被弹回列表顶部。
                        // 关键修复：滚动位置用 rememberSaveable 按路径分槽保存，进入文本编辑页返回后
                        // 重组不再丢失滚动位置（修复「退出编辑页异常跳回列表顶部」）。
                        val savedListIndex = rememberSaveable(state.currentPath, state.searchResults) { mutableIntStateOf(0) }
                        val savedListOffset = rememberSaveable(state.currentPath, state.searchResults) { mutableIntStateOf(0) }
                        val savedGridIndex = rememberSaveable(state.currentPath, state.searchResults) { mutableIntStateOf(0) }
                        val savedGridOffset = rememberSaveable(state.currentPath, state.searchResults) { mutableIntStateOf(0) }
                        val fileListState = rememberLazyListState(
                            initialFirstVisibleItemIndex = savedListIndex.intValue,
                            initialFirstVisibleItemScrollOffset = savedListOffset.intValue
                        )
                        val fileGridState = rememberLazyGridState(
                            initialFirstVisibleItemIndex = savedGridIndex.intValue,
                            initialFirstVisibleItemScrollOffset = savedGridOffset.intValue
                        )
                        // 实时把滚动位置写回持久化槽，供返回时恢复。
                        LaunchedEffect(fileListState) {
                            snapshotFlow {
                                fileListState.firstVisibleItemIndex to fileListState.firstVisibleItemScrollOffset
                            }.collect { (i, o) -> savedListIndex.intValue = i; savedListOffset.intValue = o }
                        }
                        LaunchedEffect(fileGridState) {
                            snapshotFlow {
                                fileGridState.firstVisibleItemIndex to fileGridState.firstVisibleItemScrollOffset
                            }.collect { (i, o) -> savedGridIndex.intValue = i; savedGridOffset.intValue = o }
                        }

                        // 进入新文件夹/搜索结果变化后自动回到顶部（路径不变时此处不触发，保留原位置）。
                        LaunchedEffect(state.currentPath, state.searchResults) {
                            fileListState.scrollToItem(0)
                            fileGridState.scrollToItem(0)
                        }

                        // 触发条件：最后一个可见 item 距列表尾部不足 RENDER_LOAD_AHEAD 条。
                        // 用 snapshotFlow 而不是在组合里直接读 layoutInfo：后者每帧滚动都会
                        // 让整个分支重组，白白抖掉一堆帧。
                        LaunchedEffect(fileListState, fileGridState, baseList.size, state.viewMode) {
                            snapshotFlow {
                                val info = if (state.viewMode == FileViewMode.GRID) {
                                    fileGridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index
                                } else {
                                    fileListState.layoutInfo.visibleItemsInfo.lastOrNull()?.index
                                }
                                info ?: -1
                            }.collect { lastVisible ->
                                if (lastVisible >= 0 &&
                                    lastVisible >= renderLimit - FILE_RENDER_LOAD_AHEAD &&
                                    renderLimit < baseList.size
                                ) {
                                    renderLimit = (renderLimit + FILE_RENDER_PAGE)
                                        .coerceAtMost(baseList.size)
                                }
                            }
                        }

                        val isVirtualRoot = state.storageVolumes.isNotEmpty() &&
                            (state.currentPath.isEmpty() || state.currentPath == state.storageRoot) &&
                            state.storageVolumes.size > 1 &&
                            state.searchResults == null

                        if (isVirtualRoot) {
                            LazyColumn(
                                modifier = Modifier.weight(1f).blurEntrance(triggerKey = state.currentPath),
                                contentPadding = PaddingValues(
                                    horizontal = Spacing.CardHorizontalMargin,
                                    vertical = 8.dp
                                ),
                                verticalArrangement = Arrangement.spacedBy(Spacing.CardBottomMargin)
                            ) {
                                items(state.storageVolumes) { vol ->
                                    VolumeCard(
                                        volume = vol,
                                        onEnter = { viewModel.navigateToDir(vol.mountPath) }
                                    )
                                }
                            }
                        } else if (displayFiles.isEmpty()) {
                            FileEmptyState(modifier = Modifier.weight(1f))
                        } else if (state.viewMode == FileViewMode.GRID) {
                            // T17: 固定 4 列，卡片固定高度，避免 Adaptive 列宽/文字换行导致大小参差。
                            LazyVerticalGrid(
                                columns = GridCells.Fixed(4),
                                state = fileGridState,
                                modifier = Modifier.weight(1f).blurEntrance(triggerKey = state.currentPath),
                                contentPadding = PaddingValues(
                                    horizontal = Spacing.CardHorizontalMargin,
                                    vertical = 8.dp
                                ),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                // key 用 path（目录内唯一且稳定）而不是下标：排序切换 / 删除单项后
                                // 下标会整体错位，带下标 key 的 item 会被判定为"全换了一遍"。
                                items(displayFiles, key = { it.path }) { item ->
                                    val fileOptions = buildList {
                                        add(UfiPopupOption("open", openActionLabel(item), icon = Icons.AutoMirrored.Filled.Launch) { handleAction("open", item) })
                                        add(UfiPopupOption("download", "下载", icon = Icons.Filled.Download) { handleAction("download", item) })
                                        add(UfiPopupOption("info", "信息", icon = Icons.Filled.Info) { handleAction("info", item) })
                                        add(UfiPopupOption("copy", "复制", icon = Icons.Filled.ContentCopy) { handleAction("copy", item) })
                                        add(UfiPopupOption("cut", "剪切", icon = Icons.Filled.ContentCut) { handleAction("cut", item) })
                                        add(UfiPopupOption("rename", "重命名", icon = Icons.Filled.DriveFileRenameOutline) { handleAction("rename", item) })
                                        add(UfiPopupOption("delete", "删除", icon = Icons.Filled.Delete, isDestructive = true) { handleAction("delete", item) })
                                        if (item.name.endsWith(".apk", ignoreCase = true)) {
                                            add(UfiPopupOption("install", "安装APK", icon = Icons.Filled.InstallMobile) { handleAction("install", item) })
                                        }
                                    }
                                    FileGridItem(
                                        item = item,
                                        isSelected = state.selectedPaths.contains(item.path),
                                        showCheckbox = state.multiSelectMode,
                                        onOpen = { handleAction("open", item) },
                                        onToggleSelect = { viewModel.toggleFileSelection(item.path) },
                                        moreOptions = fileOptions
                                    )
                                }
                                // 尾部渲染占位：只在"还有没渲染的条目"时挂一行骨架，整行跨列
                                // （maxLineSpan），不然会被塞进 4 列网格的一格里。
                                if (hasMoreToRender) {
                                    item(span = { GridItemSpan(maxLineSpan) }) { FileRenderFooter() }
                                }
                            }
                        } else {
                            LazyColumn(
                                state = fileListState,
                                modifier = Modifier.weight(1f).blurEntrance(triggerKey = state.currentPath),
                                contentPadding = PaddingValues(
                                    horizontal = Spacing.CardHorizontalMargin,
                                    vertical = 8.dp
                                ),
                                verticalArrangement = Arrangement.spacedBy(Spacing.CardBottomMargin)
                            ) {
                                items(displayFiles, key = { it.path }) { item ->
                                    val fileOptions = buildList {
                                    add(UfiPopupOption("open", openActionLabel(item), icon = Icons.AutoMirrored.Filled.Launch) { handleAction("open", item) })
                                    add(UfiPopupOption("download", "下载", icon = Icons.Filled.Download) { handleAction("download", item) })
                                    add(UfiPopupOption("info", "信息", icon = Icons.Filled.Info) { handleAction("info", item) })
                                    add(UfiPopupOption("copy", "复制", icon = Icons.Filled.ContentCopy) { handleAction("copy", item) })
                                    add(UfiPopupOption("cut", "剪切", icon = Icons.Filled.ContentCut) { handleAction("cut", item) })
                                    add(UfiPopupOption("rename", "重命名", icon = Icons.Filled.DriveFileRenameOutline) { handleAction("rename", item) })
                                    add(UfiPopupOption("delete", "删除", icon = Icons.Filled.Delete, isDestructive = true) { handleAction("delete", item) })
                                    if (item.name.endsWith(".apk", ignoreCase = true)) {
                                        add(UfiPopupOption("install", "安装APK", icon = Icons.Filled.InstallMobile) { handleAction("install", item) })
                                    }
                                }
                                FileRowCard(
                                        item = item,
                                        isSelected = state.selectedPaths.contains(item.path),
                                        showCheckbox = state.multiSelectMode,
                                        onOpen = { handleAction("open", item) },
                                        onToggleSelect = { viewModel.toggleFileSelection(item.path) },
                                        moreOptions = fileOptions
                                    )
                                }
                                if (hasMoreToRender) {
                                    item(key = "render-footer") { FileRenderFooter() }
                                }
                            }
                        }
                    }
                }
                    }
                }

            // T9 / T11 — 批量操作条与传输任务横幅改为底部悬浮面板，见下方 UfiPageBackgroundBox 的 Box 作用域。
            }

            // 操作结果 toast：一次性提示，顶部居中，不遮挡列表。
            UfiToastHost(
                toastMessage = toastMessage,
                modifier = Modifier.align(Alignment.TopCenter),
                onDismiss = { toastMessage = null }
            )

            // 批量操作条：多选模式下底部悬浮（不再推挤列表）。
            if (state.multiSelectMode) {
                BatchActionBar(
                    selectedCount = state.selectedPaths.size,
                    onSelectAll = { viewModel.selectAllFiles() },
                    onCopy = { viewModel.batchCopySelected() },
                    onCut = { viewModel.batchCutSelected() },
                    onDelete = { viewModel.batchDeleteSelected() },
                    onCancel = { viewModel.toggleMultiSelectMode() },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 16.dp)
                )
            }

            // 传输任务横幅：上传/下载进行中或下载暂停时底部悬浮。
            val transferActive = state.isUploading || state.uploadProgress >= 0f ||
                state.isDownloading || state.downloadStatus == "paused" || state.downloadProgress >= 0f
            if (transferActive) {
                TransferBar(
                    isUploading = state.isUploading,
                    uploadProgress = state.uploadProgress,
                    uploadFileName = state.uploadFileName,
                    isDownloading = state.isDownloading,
                    downloadProgress = state.downloadProgress,
                    downloadFileName = state.downloadFileName,
                    downloadStatus = state.downloadStatus,
                    onCancelDownload = { viewModel.cancelDownload() },
                    onResumeDownload = { viewModel.resumeDownload(state.downloadPath, state.downloadFileName) },
                    modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = if (state.multiSelectMode) 84.dp else 16.dp)
                )
            }

            // 预览悬浮窗：图片/视频/音频直接在文件管理器之上叠加，不进新界面。
            // 作为嵌套 Box 的最后一个子节点，z 序最高、覆盖全屏（含顶部退出栏与底部浮条）。
            FilePreviewOverlay(
                target = previewTarget,
                onDismiss = {
                    previewTarget = null
                    // 关闭预览时强制复位全屏状态：全屏下直接关闭时 FilePreviewOverlay 的
                    // videoFullscreen 仍停在 true（仅被 AnimatedVisibility 隐藏），不会回调
                    // onFullscreenChange(false)，否则此处状态卡死导致 bgBlur 恒为 0。
                    videoFullscreen = false
                },
                onFullscreenChange = { videoFullscreen = it }
            )

            }

            NewFolderDialog(
                visible = showNewFolder,
                onConfirm = { name ->
                    val cleanName = name.trim('/')
                    val parent = state.currentPath.trimEnd('/')
                    val target = if (parent.isEmpty()) {
                        // 虚拟根（多卷 landing）：currentPath 为空，无当前目录，
                        // 改建到第一个卷的 mountPath，避免建到文件系统根 "/"。
                        val volRoot = state.storageVolumes.firstOrNull()?.mountPath
                        if (volRoot.isNullOrEmpty()) null else "$volRoot/$cleanName"
                    } else {
                        "$parent/$cleanName"
                    }
                    target?.let { viewModel.createDirectory(it) }
                    showNewFolder = false
                },
                onDismiss = { showNewFolder = false }
            )

            // T8b — 排序面板（ChoiceSheet，内部自带 if(visible) 守卫，故 visible 透传 true）。
            SortSheet(
                visible = showSort,
                currentSort = state.sortBy,
                onSelect = { viewModel.setSortBy(it); showSort = false },
                onDismiss = { showSort = false }
            )

            RenameDialog(
                visible = showRename,
                initialName = renameTarget?.name ?: "",
                onConfirm = { newName ->
                    renameTarget?.let { viewModel.renameFile(it.path, newName) }
                    showRename = false
                    renameTarget = null
                },
                onDismiss = { showRename = false; renameTarget = null }
            )

            DeleteConfirmDialog(
                visible = showDelete,
                targetLabel = deleteTarget?.name ?: "",
                onConfirm = {
                    deleteTarget?.let { viewModel.deleteFileOrDir(it.path) }
                    showDelete = false
                    deleteTarget = null
                },
                onDismiss = { showDelete = false; deleteTarget = null }
            )

            infoTarget?.let { f ->
                FileInfoDialog(
                    visible = true,
                    file = f,
                    onDismiss = { showInfo = false; infoTarget = null }
                )
            }

            ApkInstallDialog(
                visible = showInstall,
                fileName = installTarget?.name ?: "",
                onConfirm = {
                    installTarget?.let { viewModel.installApk(it.path) }
                    showInstall = false
                    installTarget = null
                },
                onDismiss = { showInstall = false; installTarget = null }
            )

            // T14 — 搜索弹窗：关键词 + 搜索范围（1/2/3 层），确认后触发 viewModel.searchFiles 并关闭。
            SearchDialog(
                visible = showSearch,
                currentDepth = state.searchDepth,
                onSearch = { query, depth ->
                    viewModel.searchFiles(query, depth)
                    showSearch = false
                },
                onDismiss = { showSearch = false }
            )

            // T12 — 双域存储权限引导：showStoragePermissionDialog 由 checkStorageAccess 置位时挂载。
            // 设备端未授权只能「重试」；手机端未授权可「前往手机设置」打开本机设置页。
            StoragePermissionDialog(
                visible = state.showStoragePermissionDialog,
                coreGranted = state.coreStorageGranted,
                phoneGranted = state.phoneStorageGranted,
                onRetry = { viewModel.checkStorageAccess() },
                onOpenPhoneSettings = {
                    // 修复：MANAGE_ALL_FILES_ACCESS_PERMISSION 是系统级设置页，不应带 package URI；
                    // 部分 ROM 下 Dialog context.packageName 为空，改用 applicationContext 并加兜底。
                    val packageName = context.applicationContext.packageName
                    val intent = try {
                        Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                    } catch (_: Exception) {
                        null
                    }
                    val fallback = Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", packageName, null)
                    )
                    try {
                        if (intent != null) {
                            storageLauncher.launch(intent)
                        } else {
                            storageLauncher.launch(fallback)
                        }
                    } catch (_: ActivityNotFoundException) {
                        try {
                            storageLauncher.launch(fallback)
                        } catch (_: ActivityNotFoundException) {
                            Toast.makeText(context, "无法打开系统设置页", Toast.LENGTH_SHORT).show()
                        }
                    }
                },
                onDismiss = { viewModel.dismissStoragePermissionDialog() }
            )
        }
    }
}

/**
 * 渐进渲染的批大小：首屏先挂 30 条。
 *
 * 30 是"够铺满两屏还有余量"的量级 —— 再小的话用户一滚就撞到追加边界（追加本身要重组一次），
 * 再大就失去了摊帧的意义。目录里文件不足 30 个时这套机制等于不存在，零额外成本。
 */
private const val FILE_RENDER_PAGE = 30

/** 距列表尾部还剩几条时就追加下一批：留 5 条缓冲，让追加发生在用户看到底部**之前**。 */
private const val FILE_RENDER_LOAD_AHEAD = 5

/**
 * 列表尾部的渐进渲染占位（只在还有未渲染条目时挂载）。
 *
 * 放一条骨架行（而不是转圈）：与首屏骨架同一套观感，且它占的高度恰好预示"下面还会出现一行"，
 * 用户往下滚不会停在一个突兀的硬边界上。
 *
 * 2026-09-04：删掉了原先"渲染完则收成一行「没有更多（共 N 项）」"的尾部说明 ——
 * 滚到底自然停住本身就是最清楚的到底信号，多一行灰字既占位置又容易被当成故障提示。
 */
@Composable
private fun FileRenderFooter() {
    UfiSkeletonListItem(modifier = Modifier.padding(top = Spacing.CardBottomMargin))
}

/**
 * Dynamic label for the "open" action, chosen by file extension.
 * Folders open; known image/video/audio/text types map to view/play/edit;
 * anything else falls back to the detail dialog.
 */
private fun openActionLabel(item: FileItem): String =
    fileKindOf(item.name, item.isDirectory).openActionLabel

// ===== 网格视图项（列表/网格切换） =====

/**
 * 网格视图单个文件/目录项：图标容器 48dp 居中，文件名两行截断居中，副信息居中。
 * 点击打开；长按整项呼出 UfiPopupMenu 提供与列表行一致的操作。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileGridItem(
    item: FileItem,
    isSelected: Boolean,
    showCheckbox: Boolean = false,
    onOpen: () -> Unit,
    onToggleSelect: () -> Unit,
    moreOptions: List<UfiPopupOption>,
    modifier: Modifier = Modifier
) {
    val palette = LocalResolvedPalette.current
    var expanded by remember { mutableStateOf(false) }
    var moreAnchorBounds by remember { mutableStateOf(IntRect.Zero) }
    var longPressPoint by remember { mutableStateOf(IntOffset.Zero) }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(112.dp)
            .clip(UfiCardDefaults.shape)
            .background(if (isSelected) palette.accent.copy(alpha = 0.08f) else palette.cardBg)
            .then(if (isSelected) Modifier.border(1.dp, palette.accent.copy(alpha = 0.5f)) else Modifier)
            .onGloballyPositioned { coords ->
                val r = coords.boundsInWindow()
                moreAnchorBounds = IntRect(
                    r.left.roundToInt(),
                    r.top.roundToInt(),
                    r.right.roundToInt(),
                    r.bottom.roundToInt()
                )
            }
            .combinedClickable(
                onClick = onOpen,
                onLongClick = if (showCheckbox) null else {
                    {
                        // combinedClickable 不暴露长按 offset，退而用卡片几何中心作为长按点
                        longPressPoint = IntOffset(
                            (moreAnchorBounds.left + moreAnchorBounds.right) / 2,
                            (moreAnchorBounds.top + moreAnchorBounds.bottom) / 2
                        )
                        expanded = true
                    }
                }
            )
            .padding(10.dp)
    ) {
        Column(
            modifier = Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(UfiCardDefaults.shape)
                    .background(
                        if (item.isDirectory) palette.accent.copy(alpha = 0.08f)
                        else palette.accentSecondary.copy(alpha = 0.12f)
                    ),
                contentAlignment = Alignment.Center
            ) {
                FileIcon(
                    name = item.name,
                    isDirectory = item.isDirectory,
                    modifier = Modifier.size(28.dp)
                )
            }
            Text(
                text = item.name,
                style = UfiTextStyles.note.copy(fontWeight = UfiWeight.Strong),
                color = palette.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
            Text(
                text = if (item.isDirectory) "文件夹" else FormatUtils.formatSize(item.size),
                style = MaterialTheme.typography.labelSmall,
                color = palette.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
        }
        if (showCheckbox) {
            Checkbox(
                checked = isSelected,
                onCheckedChange = { onToggleSelect() },
                modifier = Modifier.align(Alignment.TopEnd),
                colors = CheckboxDefaults.colors(
                    checkedColor = palette.accent,
                    checkmarkColor = palette.onAccent
                )
            )
        }

        // 长按呼出的操作菜单（跟随长按点位置出现）。
        if (!showCheckbox) {
            UfiPopupMenu(
                visible = expanded,
                onDismiss = { expanded = false },
                anchorBounds = moreAnchorBounds,
                anchorPoint = longPressPoint,
                options = moreOptions
            )
        }
    }
}

/**
 * 2026-08-31：私有 formatSize 已删除 —— 全库 4 份逐字节相同的拷贝
 *（本文件 / FileRowCard / FileInfoDialog / TextEditorScreen）统一收敛到
 * [com.ufi_axis.util.FormatUtils.formatSize]。
 */

