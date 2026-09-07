package com.ufi_axis.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import com.ufi_axis.ui.components.common.*
import com.ufi_axis.ui.theme.Spacing
import com.ufi_axis.ui.theme.UfiCardDefaults
import com.ufi_axis.ui.theme.UfiTextStyles
import com.ufi_axis.ui.theme.ufiStandardCard
import com.ufi_axis.ui.theme.LocalResolvedPalette
import com.ufi_axis.ui.theme.StatusRamp
import com.ufi_axis.util.FormatUtils
import com.ufi_axis.viewmodel.MainViewModel
import com.ufi_axis.viewmodel.state.DownloadEmptyPhase
import com.ufi_axis.viewmodel.state.DownloadState
import com.ufi_axis.viewmodel.state.DownloadTaskItem
import com.ufi_axis.viewmodel.state.downloadEmptyPhase

/** 任务列表分类筛选维度。"未完成"包含所有非已完成的任务（下载中/暂停/等待/失败/获取种子）。 */
private enum class DownloadCategory { ALL, ACTIVE, COMPLETED }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DownloadTasksTab(
    state: DownloadState,
    viewModel: MainViewModel
) {
    var selectedCategory by remember { mutableStateOf(DownloadCategory.ALL) }

    val allCount = state.tasks.size
    val activeCount = state.tasks.count { it.status != "completed" }
    val completedCount = state.tasks.count { it.status == "completed" }

    val filteredTasks = when (selectedCategory) {
        DownloadCategory.ALL -> state.tasks
        DownloadCategory.ACTIVE -> state.tasks.filter { it.status != "completed" }
        DownloadCategory.COMPLETED -> state.tasks.filter { it.status == "completed" }
    }

    Column(Modifier.fillMaxSize()) {
        // 筛选：截图同款滑块分段 Tab（全部 / 未完成 / 已完成）
        UfiScrollableTabRow(
            modifier = Modifier.padding(horizontal = Spacing.CardHorizontalMargin, vertical = Spacing.Medium),
            selectedTabIndex = selectedCategory.ordinal,
            onTabSelected = { index -> selectedCategory = DownloadCategory.entries[index] },
            tabs = listOf("全部 $allCount", "未完成 $activeCount", "已完成 $completedCount")
        )

        // ── 懒加载 = 渐进渲染（不是真分页）────────────────────────────────────────
        // 依据：core 的 `GET /api/downloads`（DownloadRoutes.kt）一次性回全部任务，
        // 没有 offset/limit；app 侧 `UfiAxisApi.getDownloads()` 也是无参调用。
        // 要做真分页得改服务端契约，本次不动 core，所以在客户端分批渲染同一份数据。
        //
        // 这一页尤其需要：任务卡里有进度条、速度、状态徽标等好几处每 2s 就会变的字段，
        // 挂在布局树上的卡片越多，每次轮询要重组的节点就越多。限制同时挂载的卡片数，
        // 等于把轮询的重组成本也一起压下来了 —— 这点是列表页里独有的收益。
        //
        // 2026-09-04（本轮）：`remember(selectedCategory)` 的 key 保留。
        // 它只重置"已渲染到第几批"这个纯客户端游标，不会卸载任何容器；
        // 切分类后从第一批重新铺，避免"在已完成里翻到第 60 条，切到未完成还挂着 60 张卡"。
        var renderLimit by remember(selectedCategory) { mutableStateOf(TASK_RENDER_PAGE) }
        val renderedTasks = filteredTasks.take(renderLimit)

        val listState = rememberLazyListState()
        LaunchedEffect(listState, filteredTasks.size) {
            snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
                .collect { lastVisible ->
                    if (lastVisible >= 0 &&
                        lastVisible >= renderLimit - TASK_RENDER_LOAD_AHEAD &&
                        renderLimit < filteredTasks.size
                    ) {
                        renderLimit = (renderLimit + TASK_RENDER_PAGE)
                            .coerceAtMost(filteredTasks.size)
                    }
                }
        }

        // 2026-09-04：删掉本页最后一处"整块子树互斥切换"。
        //
        // 原结构是 `if (filteredTasks.isEmpty() && !isLoading) 空态 else { LazyColumn }`。
        // 这是"进页面闪一下"的机械原因，也是删掉骨架后用户仍然说"还有动画"的真正剩余项：
        // 空态与 LazyColumn 是**两棵互斥子树**，任何一次 空↔有数据 的翻转都会把整棵
        // LazyColumn（含 listState、已复用的 item 节点）卸掉再挂一棵新的 —— 不是"内容原地更新"，
        // 而是一次硬替换。进页面时必然发生一次：首帧 tasks 为空 → 100~300ms 后数据到 → 换树。
        // 切分类到一个空分类、或清空已完成任务时同样会发生。
        //
        // 现在容器恒定：LazyColumn 从第一帧就挂在树上，永不卸载；
        // "没数据"表达为**它里面只有一条空态 item**，而不是换一棵树。
        // 于是数据到达只是 item 的增删/内容更新，Compose 复用已有节点，没有可闪的东西。
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = Spacing.CardHorizontalMargin,
                end = Spacing.CardHorizontalMargin,
                top = 4.dp,
                bottom = 88.dp
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // key = task.id（稳定业务 id，非下标）：轮询回来后同一任务仍映射到同一个 item 节点，
            // Compose 只更新它内部变化的字段（进度/速度/徽标），不重建整列。
            // contentType 固定为 "task"：让 Lazy 的复用池知道这些 item 结构同型，
            // 追加下一批时直接复用离屏卡片的节点，而不是新建。
            items(
                renderedTasks,
                key = { it.id },
                contentType = { "task" }
            ) { task ->
                DownloadTaskCard(task, viewModel, isCompleted = task.status == "completed")
            }
            // 空态降级为列表里的一条 item（撑满可视区，观感与原来的整屏空态一致）。
            //
            // 2026-09-05：挂载条件里原来还有 `&& !state.isLoading`，这是"进下载管理时中间那块
            // 图标和描述闪一下、添加任务后就完全正常"的直接原因 —— 加载期间空态被摘掉、
            // 回包后再挂回来。列表非空时空态本来就不挂，所以只有空列表会闪。
            // 现在条件只剩 `filteredTasks.isEmpty()`：**item 恒定挂载，永不因加载态挂/卸**，
            // 与上面"LazyColumn 恒定挂载、不做互斥子树硬切换"是同一条口径的延伸。
            // 首屏还没拉到数据这件事改由文案分档表达（[downloadEmptyPhase]），
            // 换的是一行 Text 的内容，图标与版式原地不动。
            if (filteredTasks.isEmpty()) {
                item(key = "empty", contentType = "empty") {
                    DownloadEmptyState(
                        category = selectedCategory,
                        phase = downloadEmptyPhase(
                            hasLoadedOnce = state.hasLoadedOnce,
                            isLoading = state.isLoading,
                            hasError = state.errorMessage != null
                        ),
                        modifier = Modifier.fillParentMaxHeight()
                    )
                }
            }
            // 2026-09-04：这里原来放一条 `UfiSkeletonListItem()` 当"加载中"尾占位。
            // 删掉，且不换任何动画占位 —— 它是本页最后一处骨架动画，而下一批其实是
            // **本地已有数据的渲染**（core 的 GET /api/downloads 一次性回全量，见上方说明），
            // 追加只差一帧，摆个闪动的灰块只会让人以为还在等网络。
            // 也不需要它来触发加载：上面的 snapshotFlow 看的是 visibleItemsInfo 的末位下标，
            // 与有没有 footer item 无关（末位下标 = renderLimit-1 >= renderLimit-LOAD_AHEAD 恒成立）。
        }
    }
}

/**
 * 渐进渲染批大小。取 20 而不是文件管理那边的 30：下载任务卡比文件行高得多（进度条 + 两行元信息），
 * 20 张就已经超过两屏。
 */
private const val TASK_RENDER_PAGE = 20

/** 距尾部还剩几条就追加下一批。 */
private const val TASK_RENDER_LOAD_AHEAD = 5

// ==================== Filter / Empty ==================

/** 空状态（简洁静态，无任何动效）。
 * 浅色圆形图标 + 分类化标题/副标题 + 支持协议小字。
 *
 * 2026-09-04 新增 [modifier]：本组件已改为挂在 LazyColumn 里的一条 item（容器恒定，见 [DownloadTasksTab]），
 * 而 LazyColumn 的 item 拿到的最大高度是"内容自适应"，内部再写 fillMaxSize() 会塌成一行高。
 * 高度必须由调用方用 `fillParentMaxHeight()` 从外面给进来。
 *
 * 2026-09-05 新增 [phase]：这条 item 现在恒定挂载（不再随 `isLoading` 挂/卸，那是"图标和描述
 * 闪一下"的成因），"首屏还没拉到 / 首屏拉失败 / 真的没任务"三种情况改由**文案**区分。
 * 图标、圆形底、版式与那行协议小字在三档之间**完全不变** —— 只换两行文字，
 * 既没有子树增删也没有高度跳变。 */
@Composable
private fun DownloadEmptyState(
    category: DownloadCategory,
    phase: DownloadEmptyPhase,
    modifier: Modifier = Modifier
) {
    val palette = LocalResolvedPalette.current
    val (title, subtitle) = when (phase) {
        DownloadEmptyPhase.LOADING -> "正在读取下载任务" to "首次进入需要向本机服务取一次列表"
        DownloadEmptyPhase.LOAD_FAILED -> "暂时读不到下载任务" to "原因见页面顶部提示，可点右上角刷新重试"
        DownloadEmptyPhase.EMPTY -> when (category) {
            DownloadCategory.ALL -> "还没有下载任务" to "点击右下角按钮，开启你的第一次下载"
            DownloadCategory.ACTIVE -> "还没有未完成任务" to "切换其他分类查看"
            DownloadCategory.COMPLETED -> "还没有已完成的任务" to "切换其他分类查看"
        }
    }

    Box(
        modifier.fillMaxWidth().padding(horizontal = Spacing.CardHorizontalMargin),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                Modifier
                    .size(80.dp)
                    .clip(CircleShape)
                    .background(palette.surfaceMuted),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.CloudDownload,
                    contentDescription = null,
                    tint = palette.accent.copy(alpha = 0.5f),
                    modifier = Modifier.size(36.dp)
                )
            }

            Spacer(Modifier.height(20.dp))
            Text(
                text = title,
                style = UfiTextStyles.panelTitle,
                color = palette.textPrimary
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = palette.textSecondary
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = "支持 HTTP · BT · 磁力链接",
                style = MaterialTheme.typography.labelSmall,
                color = palette.textSecondary.copy(alpha = 0.5f)
            )
        }
    }
}

// ==================== Task Card ====================

private data class DownloadStatusMeta(
    val badgeType: UfiBadgeType,
    val label: String,
    val icon: ImageVector,
    val bgColor: Color,
    val tint: Color
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DownloadTaskCard(
    task: DownloadTaskItem,
    viewModel: MainViewModel,
    isCompleted: Boolean
) {
    val palette = LocalResolvedPalette.current
    val meta = when (task.status) {
        "downloading" -> DownloadStatusMeta(UfiBadgeType.INFO, "下载中", Icons.Default.Download,
            palette.accent.copy(alpha = 0.12f), palette.accent)
        "meta" -> DownloadStatusMeta(UfiBadgeType.WARNING, "获取种子", Icons.Default.CloudDownload,
            StatusRamp.warn(palette).copy(alpha = 0.12f), StatusRamp.warn(palette))
        // 校验（hash check）：续传或重加已有数据时 aria2 先核对分片，进度走 verifiedLength。
        // 单独一档才不会被误读成"卡在下载中"（2026-09-03）。
        "verifying" -> DownloadStatusMeta(UfiBadgeType.INFO, "校验中", Icons.Default.FactCheck,
            palette.accent.copy(alpha = 0.12f), palette.accent)
        "paused" -> DownloadStatusMeta(UfiBadgeType.DEFAULT, "已暂停", Icons.Default.Pause,
            palette.textSecondary.copy(alpha = 0.12f), palette.textSecondary)
        "pending" -> DownloadStatusMeta(UfiBadgeType.DEFAULT, "等待中", Icons.Default.Schedule,
            palette.textSecondary.copy(alpha = 0.12f), palette.textSecondary)
        "error" -> DownloadStatusMeta(UfiBadgeType.ERROR, "失败", Icons.Default.ErrorOutline,
            palette.error.copy(alpha = 0.12f), palette.error)
        "completed" -> DownloadStatusMeta(UfiBadgeType.SUCCESS, "已完成", Icons.Default.CheckCircle,
            palette.success.copy(alpha = 0.12f), palette.success)
        else -> DownloadStatusMeta(UfiBadgeType.DEFAULT, task.status, Icons.AutoMirrored.Filled.HelpOutline,
            palette.textSecondary.copy(alpha = 0.12f), palette.textSecondary)
    }
    val isActive = task.status in listOf("downloading", "paused", "pending", "meta", "verifying")
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var deleteWithFile by remember { mutableStateOf(false) }
    var showRename by remember { mutableStateOf(false) }

    // 长按菜单状态 + 锚点（容器窗口坐标 + 长按点窗口坐标，供 UfiPopupMenu 定位）
    var menuExpanded by remember { mutableStateOf(false) }
    var menuAnchorBounds by remember { mutableStateOf(IntRect.Zero) }
    var menuLongPressPoint by remember { mutableStateOf(IntOffset.Zero) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .ufiStandardCard()
            .onGloballyPositioned { coords ->
                val r = coords.boundsInWindow()
                menuAnchorBounds = IntRect(
                    r.left.roundToInt(), r.top.roundToInt(),
                    r.right.roundToInt(), r.bottom.roundToInt()
                )
            }
            .combinedClickable(
                onClick = { },
                onLongClick = {
                    // combinedClickable 不暴露长按 offset，用卡片几何中心作为长按点
                    menuLongPressPoint = IntOffset(
                        (menuAnchorBounds.left + menuAnchorBounds.right) / 2,
                        (menuAnchorBounds.top + menuAnchorBounds.bottom) / 2
                    )
                    menuExpanded = true
                }
            )
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 左侧文件类型图标（对齐文件管理器行卡：40dp 圆角容器 + 文件类型图标）
            FileTypeIconBox(name = task.fileName.ifBlank { task.url.substringAfterLast("/") })
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                // 文件名 + 状态徽章
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = task.fileName.ifBlank { task.url.substringAfterLast("/").take(40) },
                        style = UfiTextStyles.bodyLeadStrong,
                        color = palette.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(8.dp))
                    UfiBadge(text = meta.label, type = meta.badgeType)
                }

                // 进度（仅活跃任务）
                if (isActive) {
                    Spacer(Modifier.height(6.dp))
                    if (task.status == "meta" || task.status == "verifying") {
                        // 2026-09-04：这里原来是 `CircularProgressIndicator(12.dp)` + 文案。
                        // 删掉转圈：它是 rememberInfiniteTransition 驱动的**永不停止**的动画，
                        // 而 meta/verifying 可能持续几十秒到几分钟（BT 抓元数据、大文件 hash 校验），
                        // 也就是说列表里只要有一个这类任务，就一直有东西在转 —— 用户说的"还有动画"
                        // 在有磁链任务时首先看到的就是它。信息量并没有丢：状态徽标已经写着
                        // "获取种子/校验中"，下面这行文案又说了一遍，转圈只是重复表达。
                        Text(
                            if (task.status == "meta") "获取种子信息中..." else "校验已有数据中...",
                            style = MaterialTheme.typography.labelSmall, color = meta.tint
                        )
                        Spacer(Modifier.height(4.dp))
                    }
                    if (task.progress >= 0f && task.totalSize > 0) {
                        // 确定态进度条：Material3 的 determinate LinearProgressIndicator 直接按
                        // progress() 绘制，没有内置补间动画 —— 它是**数据的直接呈现**（下载了多少），
                        // 不是入场/加载动效，所以保留。轮询更新它只会改这一处的绘制，不触碰布局。
                        LinearProgressIndicator(
                            progress = { task.progress.coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth().height(6.dp).clip(UfiCardDefaults.pillShape),
                            color = meta.tint,
                            trackColor = palette.divider.copy(alpha = 0.3f)
                        )
                    } else {
                        // 2026-09-04：进度未知（还没拿到 totalSize）时原来放的是**不确定态**
                        // LinearProgressIndicator —— 那是一条无限循环左右扫动的滚动条，
                        // 与"删掉所有动画"直接冲突。换成一条静态空轨道：
                        // ① 不动；② 高度与原来一致，拿到 totalSize 切到确定态时不会跳行
                        //（若直接删掉这一支，卡片会在进度出现的那一帧突然变高，比动画更扰人）。
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(4.dp)
                                .clip(UfiCardDefaults.pillShape)
                                .background(palette.divider.copy(alpha = 0.3f))
                        )
                    }
                }

                Spacer(Modifier.height(6.dp))
                // 副标题：大小 / 路径，分两行展示
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = buildString {
                            append(FormatUtils.formatBytes(task.downloadedBytes))
                            if (task.totalSize > 0) append(" / ${FormatUtils.formatBytes(task.totalSize)}")
                            if (task.progress >= 0f && task.totalSize > 0) append(" (${(task.progress * 100).toInt()}%)")
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    val dir = task.savePath.substringBeforeLast("/")
                    if (dir.isNotBlank()) {
                        Text(
                            text = dir,
                            style = MaterialTheme.typography.bodySmall,
                            color = palette.textSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                if (task.status == "downloading" && (task.speed > 0 || task.uploadSpeed > 0)) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = buildString {
                            if (task.speed > 0) append("${FormatUtils.formatBytes(task.speed)}/s ↓")
                            if (task.uploadSpeed > 0) {
                                if (isNotEmpty()) append("  ")
                                append("${FormatUtils.formatBytes(task.uploadSpeed)}/s ↑")
                            }
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = if (task.speed > 0) palette.accent else StatusRamp.warn(palette)
                    )
                }

                task.error?.let { errorText ->
                    Spacer(Modifier.height(4.dp))
                    Text(
                        errorText,
                        style = MaterialTheme.typography.labelSmall,
                        color = palette.error,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        if (showDeleteConfirm) {
            UfiConfirmDialog(
                title = if (deleteWithFile) "删除任务及文件" else "删除任务",
                text = if (deleteWithFile) "确定要删除「${task.fileName.ifBlank { task.url.take(30) }}」及其下载文件吗？此操作不可撤销。"
                    else "确定要删除下载任务「${task.fileName.ifBlank { task.url.take(30) }}」吗？",
                confirmText = "删除",
                onConfirm = {
                    viewModel.downloads.deleteDownload(task.id, deleteFile = deleteWithFile)
                    showDeleteConfirm = false
                    deleteWithFile = false
                },
                onDismiss = { showDeleteConfirm = false; deleteWithFile = false },
                destructive = true
            )
        }

        if (showRename) {
            RenameDownloadDialog(
                currentName = task.fileName.ifBlank { task.url.substringAfterLast("/") },
                onDismiss = { showRename = false },
                onConfirm = { newName ->
                    viewModel.downloads.renameDownload(task.id, newName)
                    showRename = false
                }
            )
        }

        // 长按菜单：与文件管理器一致，菜单跟随长按点出现
        UfiPopupMenu(
            visible = menuExpanded,
            onDismiss = { menuExpanded = false },
            anchorBounds = menuAnchorBounds,
            anchorPoint = menuLongPressPoint,
            options = buildDownloadMenuOptions(
                task = task,
                isCompleted = isCompleted,
                viewModel = viewModel,
                onRename = { showRename = true },
                onDelete = { showDeleteConfirm = true },
                onDeleteWithFile = { deleteWithFile = true; showDeleteConfirm = true }
            )
        )
    }
}

/** 构造下载任务长按菜单选项（暂停/继续/重试/复制链接/重命名/删除/删除文件）。 */
private fun buildDownloadMenuOptions(
    task: DownloadTaskItem,
    isCompleted: Boolean,
    viewModel: MainViewModel,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onDeleteWithFile: () -> Unit
): List<UfiPopupOption> = buildList {
    when (task.status) {
        "downloading", "meta", "verifying" ->
            add(UfiPopupOption("pause", "暂停", Icons.Default.Pause) { viewModel.downloads.pauseDownload(task.id) })
        "paused", "error" ->
            add(UfiPopupOption("resume", "继续", Icons.Default.PlayArrow) { viewModel.downloads.resumeDownload(task.id) })
    }
    if (task.status == "error") {
        add(UfiPopupOption("retry", "重试", Icons.Default.Refresh) { viewModel.downloads.retryDownload(task.id) })
    }
    add(UfiPopupOption("copy", "复制链接", Icons.Default.ContentCopy) { viewModel.downloads.copyLinkToClipboard(task.url) })
    add(UfiPopupOption("rename", "重命名", Icons.Default.Edit) { onRename() })
    // 危险操作分组（删除 / 删除文件），前置分割线
    add(UfiPopupOption.divider())
    add(UfiPopupOption("delete", "删除", Icons.Default.Delete, isDestructive = true) { onDelete() })
    if (isCompleted) {
        add(UfiPopupOption("delete_file", "删除文件", Icons.Default.DeleteForever, isDestructive = true) { onDeleteWithFile() })
    }
}

@Composable
private fun FileTypeIconBox(name: String) {
    val palette = LocalResolvedPalette.current
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(UfiCardDefaults.shape)
            .background(palette.accentSecondary.copy(alpha = 0.12f)),
        contentAlignment = Alignment.Center
    ) {
        Icon(imageVector = fileTypeIcon(name), contentDescription = null, modifier = Modifier.size(22.dp), tint = palette.accentSecondary)
    }
}

/** 按文件名扩展名返回文件类型图标（与文件管理器 FileIcon 的映射一致）。 */
private fun fileTypeIcon(name: String): ImageVector {
    val ext = name.substringAfterLast('.', "").lowercase()
    return when (ext) {
        "jpg", "jpeg", "png", "gif", "bmp", "webp", "svg", "ico" -> Icons.Filled.Image
        "mp4", "mkv", "avi", "mov", "webm", "flv", "wmv", "3gp", "m4v" -> Icons.Filled.VideoFile
        "mp3", "wav", "flac", "ogg", "aac", "m4a", "wma", "opus", "amr" -> Icons.Filled.AudioFile
        "pdf", "doc", "docx", "odt", "xls", "xlsx", "csv" -> Icons.Filled.PictureAsPdf
        "zip", "tar", "gz", "rar", "7z" -> Icons.Filled.FolderZip
        "apk" -> Icons.Filled.Android
        "sh", "bash", "zsh", "py", "js", "kt", "java", "c", "cpp", "h", "go", "rs" -> Icons.Filled.Terminal
        "json", "xml", "yaml", "yml", "toml", "ini", "conf", "cfg" -> Icons.Filled.Settings
        "txt", "log", "md", "html", "htm", "css" -> Icons.AutoMirrored.Filled.Article
        else -> Icons.AutoMirrored.Filled.InsertDriveFile
    }
}
