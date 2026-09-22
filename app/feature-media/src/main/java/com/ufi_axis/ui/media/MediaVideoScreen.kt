package com.ufi_axis.ui.media

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.ufi_axis.data.download.MediaDownloadQueue
import com.ufi_axis.data.download.MediaDownloadWorker
import com.ufi_axis.data.model.MEDIA_TYPE_VIDEO
import com.ufi_axis.data.model.MediaLibraryItem
import com.ufi_axis.ui.components.common.ToastMessage
import com.ufi_axis.ui.components.common.ToastType
import com.ufi_axis.ui.components.common.UfiButton
import com.ufi_axis.ui.components.common.UfiButtonSize
import com.ufi_axis.ui.components.common.UfiButtonVariant
import com.ufi_axis.ui.components.common.UfiConfirmDialog
import com.ufi_axis.ui.components.common.UfiInputDialog
import com.ufi_axis.ui.components.common.UfiListEmptyState
import com.ufi_axis.ui.components.common.UfiPopupMenu
import com.ufi_axis.ui.components.common.UfiScreenScaffold
import com.ufi_axis.ui.components.common.UfiScrollableTabRow
import com.ufi_axis.ui.components.common.UfiToastHost
import com.ufi_axis.ui.navigation.Routes
import com.ufi_axis.ui.theme.LocalResolvedPalette
import com.ufi_axis.ui.theme.Spacing
import com.ufi_axis.ui.theme.UfiTextStyles
import com.ufi_axis.ui.theme.ufiStandardCard
import com.ufi_axis.util.AppPreferences
import com.ufi_axis.util.encodeUriComponent
import com.ufi_axis.viewmodel.MainViewModel
import com.ufi_axis.viewmodel.state.AUDIO_SCOPE_ALL
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File


/**
 * 视频页（工具 → 视频）。2026-09-16 第三版：**一页两栏**。
 *
 * ## 为什么是页内两栏，而不是两个路由
 * 「首页」与「媒体库」看的是同一份数据、共用同一套排序/扫描范围与同一个设置入口，
 * 只是**呈现方式**不同（海报墙 vs 文件夹）。做成两个路由就要把这些状态在两处各存一份，
 * 而且用户在两者之间来回切会一路堆返回栈。切页状态用 `rememberSaveable` ——
 * 从播放页返回时它随 back stack entry 还原，不会莫名跳回首页。
 *
 * ## 两栏各自的职责
 * · **首页** [MediaVideoHome]：只有视频，不显示文件夹；顶部一条"最近播放"（横向，最多 6 条）。
 *   这是"想看点什么"的入口，所以按最近修改时间平铺整库。
 * · **媒体库** [MediaVideoLibraryPane]：照文件管理器那样一层层走 —— 子目录和视频混排。
 *   这是"我知道它在哪"的入口。
 *
 * ## 右上角设置
 * 扫描目录、抽帧开关、封面缓存、下载历史这些"不是每次都要碰"的东西全部收进
 * [Routes.MEDIA_VIDEO_SETTINGS]，页面本体只留浏览与播放。
 *
 * ## 两栏切换在**顶部**（2026-09-20 改）
 * 之前它在屏幕底部。改上来的理由与音乐页同一条：这两个 Tab 回答的是"我在看哪一类"，
 * 属于导航，放在标题栏下方与它连成一片"这一屏在看什么"的控制区；留在底部则会与
 * 播放控制（这一页将来的迷你控制条、以及系统手势条）抢同一块地，而切一次分类
 * 就要把视线从列表中上部拽到屏幕最下沿再拽回来。
 *
 * ## 切栏为什么不丢滚动位置（2026-09-20 同轮修）
 * 两栏的滚动状态（[homeGridState] / [libraryListState]）建在**切栏之上**：内容区是
 * `when` 换子树，建在子树里的 `rememberLazyGridState()` 会随子树一起销毁，切回来就回到顶部。
 * 骨架判据同时从 `isEmpty && isLoading` 收紧成 `!loadedOnce`（见 [MediaVideoHome] 与
 * [MediaVideoLibraryPane]）—— 前者在"请求还没由 LaunchedEffect 发出去"的那一帧
 * 两个条件都不成立，于是空态会先闪一帧。
 */

@Composable
fun MediaVideoScreen(
    viewModel: MainViewModel,
    navController: NavHostController
) {
    val palette = LocalResolvedPalette.current
    val media = viewModel.media
    /*
     * 文件写操作走**文件管理器那条链**（`viewModel.files`，不是 `viewModel.fileManager`）。
     *
     * `MediaModule` 里一个写操作都没有：它只读 MediaStore + 存配置。重命名/删除本质上是
     * 文件系统操作，core 侧也只有 `/api/files/rename`、`/api/files/delete` 这一族接口 ——
     * 在 media 侧再开一个
     * "改媒体文件"的接口等于同一件事两个入口。
     */
    val fileOps = viewModel.files
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by media.state.collectAsState()
    val tab = state.tab(MEDIA_TYPE_VIDEO)

    // 进页先拿授权状态（决定是列表还是引导），再拉第一页
    LaunchedEffect(Unit) { media.loadStatus() }
    LaunchedEffect(Unit) { media.loadFirstPage(MEDIA_TYPE_VIDEO) }
    LaunchedEffect(Unit) { MediaDownloadQueue.ensureLoaded(context) }

    var pane by rememberSaveable { mutableIntStateOf(PANE_HOME) }

    /*
     * 两栏各自的滚动位置，**建在切栏之上**。
     *
     * 两栏画的是两种容器（首页是网格、媒体库是列表），所以是两个类型不同的状态，
     * 不能像音乐页那样放进一个 list 按下标取。
     */
    val homeGridState = rememberLazyGridState()
    val libraryListState = rememberLazyListState()


    /*
     * core 拿不到缩略图时（随身 WiFi 的定制 ROM 解不出视频画面）由**手机自己抽一帧**，
     * 抽完顺手回传 core，这样 Web 端与第二台手机也能命中同一张图。
     * 只在远端 404 之后触发，不主动预抽 —— 每张要拉几 MB 视频头部。
     */
    val buildThumb: suspend (MediaLibraryItem) -> File? = { item ->
        MediaThumbnailBuilder.build(context, media, MEDIA_TYPE_VIDEO, item)
    }

    // 抽帧是"看不见的活"：不说一声，用户只会觉得列表卡了几秒又莫名多出几张图。
    //
    // 刻意**不用** `isLoading = true` 的 toast：那种是"持久型"，UfiToastOverlay 对它不注册
    // 自动移除（见其 show() 末尾），而抽帧是一批零散短任务、activeCount 反复 0↔n，
    // 很容易留下一条永不消失的转圈提示。这里只需要"说一声"，走普通定时 toast。
    val everUsed by MediaThumbnailBuilder.everUsed.collectAsState()
    val activeCount by MediaThumbnailBuilder.activeCount.collectAsState()
    var noticeDismissed by remember {
        mutableStateOf(
            runCatching { AppPreferences(context).mediaLocalThumbNoticeDismissed }
                .getOrDefault(false)
        )
    }
    var toast by remember { mutableStateOf<ToastMessage?>(null) }
    LaunchedEffect(activeCount > 0) {
        if (activeCount > 0) {
            toast = ToastMessage(
                text = "正在用本机生成视频缩略图",
                type = ToastType.INFO,
                subtitle = "设备端解不出画面，改由手机抽帧并回传"
            )
        }
    }

    val onOpen: (MediaLibraryItem) -> Unit = { item ->
        runCatching {
            AppPreferences(context).addMediaRecentPlay(item.path, item.name, item.id)
        }
        navController.navigate(mediaRouteOf("media/video", item.path))
    }

    /** 下载：入队 + 唤起 Worker。子目录按"相对当前根"镜像，避免全平铺互相覆盖。 */
    val onDownload: (MediaLibraryItem, String) -> Unit = { item, subDir ->
        val added = MediaDownloadQueue.enqueue(
            context,
            listOf(
                MediaDownloadQueue.Task(
                    path = item.path,
                    name = item.name,
                    size = item.size,
                    subDir = subDir
                )
            )
        )
        MediaDownloadWorker.kick(context)
        toast = ToastMessage(
            text = if (added > 0) "已加入下载队列" else "已经在队列里了",
            type = if (added > 0) ToastType.SUCCESS else ToastType.INFO,
            subtitle = "落点：${MediaDownloadQueue.RELATIVE_DIR}"
        )
    }

    /*
     * ───────── 长按菜单 ─────────
     *
     * 菜单与三个弹窗的状态全部建在**页壳**上，不在行里：行会随滚动被回收，
     * 状态建在行内会在菜单/弹窗还开着时被销毁。两栏只负责上报"谁被长按了"
     * （[MediaVideoMenuTarget]），选项表由 `mediaVideoItemMenuOptions` 统一造一份。
     */
    var menuTarget by remember { mutableStateOf<MediaVideoMenuTarget?>(null) }
    var infoTarget by remember { mutableStateOf<MediaLibraryItem?>(null) }
    var renameTarget by remember { mutableStateOf<MediaLibraryItem?>(null) }
    var deleteTarget by remember { mutableStateOf<MediaLibraryItem?>(null) }

    /**
     * 改完文件之后把视频列表重新拉一遍。
     *
     * ## 为什么要等一会儿
     * [com.ufi_axis.viewmodel.module.FileManagerModule] 的 `renameFile` / `deleteFileOrDir`
     * 都是"发出去就不管了"（结果只写进它自己的 state，而且紧接着的 `refreshFileList()`
     * 会把那条 `operationMessage` 又清成 null，拿不到可靠回执）。立刻重拉必然拿到改动前的
     * 记录，所以这里等 [FILE_OP_REFRESH_DELAY_MS] 再拉。
     *
     * ## 为什么两栏都拉，而不只拉当前那栏
     * 两栏看的是同一份数据的两种呈现。只拉当前那栏，切过去就会看到一条已经不存在的记录。
     * 两个请求都是单发的（`/list` 第一页 + `/browse` 当前层），不是重扫。
     *
     * ## 为什么不调 `rescan`
     * `rescan` 是"请系统重新收录"：它会把扫描目录整棵树走一遍、把上千个路径塞给
     * `MediaScannerConnection`，慢到几十秒；而且对**删掉的**文件毫无作用 ——
     * 那个文件已经不在目录里，遍历根本看不到它，它在 MediaStore 里的旧记录不会被清掉。
     * 所以重扫留给用户手动触发（媒体库那一栏工具栏上就有），不在这里自动放一发。
     *
     * ## 说清限制：刷新不等于一定对
     * 列表数据来自**系统媒体库**（core 的 `/list`、`/browse` 都是查 MediaStore，
     * 不是自己列目录），而 core 的 `/api/files/rename` 只做了一次 `Files.move`、
     * 没有通知扫描器。Android 11+ 有"所有文件访问"时，文件操作经 FUSE 通常会被
     * MediaProvider 顺带同步，所以大多数情况下这一拉就能看到新名字；但这**不是保证**，
     * 随身 WiFi 的定制 ROM 上尤其可能慢一拍 —— 那时列表里仍是旧名字（或删掉的文件还在）。
     * 因此提示文案一律写"已提交"，并告诉用户列表没跟上时用「重扫」催一下，
     * 不写"已重命名/已删除"去替系统打包票。
     */
    val refreshAfterFileChange: () -> Unit = {
        scope.launch {
            delay(FILE_OP_REFRESH_DELAY_MS)
            media.loadFirstPage(MEDIA_TYPE_VIDEO, force = true)
            // path 传 null：browse 在 force 时会沿用它自己记的当前层，不必在这里再取一遍
            media.browse(MEDIA_TYPE_VIDEO, null, force = true)
        }
    }


    UfiScreenScaffold(
        title = "视频",
        navController = navController,
        showBack = true,
        actions = {
            IconButton(onClick = { navController.navigate(Routes.MEDIA_VIDEO_SETTINGS) }) {
                Icon(
                    Icons.Default.Settings,
                    contentDescription = "视频设置",
                    tint = palette.textSecondary
                )
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (everUsed && !noticeDismissed) {
                Box(modifier = Modifier.padding(horizontal = Spacing.Medium)) {
                    MediaLocalThumbNotice(
                        onDismiss = {
                            runCatching {
                                AppPreferences(context).mediaLocalThumbNoticeDismissed = true
                            }
                            noticeDismissed = true
                        }
                    )
                }
                Spacer(Modifier.height(Spacing.Small))
            }

            /*
             * 两栏切换：内容区**上方**（2026-09-20 从底部搬上来，理由见本文件 KDoc）。
             *
             * 不再包 `navigationBarsPadding()`：手势条避让只在贴底时才需要，而页壳
             * [UfiScreenScaffold] 的内容 Box 已经统一做过一次 navigationBars 避让。
             * 左右内边距写在 TabRow 自己的 modifier 上（与音乐页同一写法），
             * 这样它与下方内容区的 `Spacing.Medium` 对齐。
             */
            UfiScrollableTabRow(
                selectedTabIndex = pane,
                onTabSelected = { pane = it },
                tabs = MEDIA_VIDEO_PANES,
                modifier = Modifier.padding(
                    horizontal = Spacing.Medium,
                    vertical = Spacing.Small
                )
            )

            Box(modifier = Modifier.weight(1f)) {
                when {
                    // 未授权就明说，不显示空列表 —— 那等于告诉用户"设备里没有视频"
                    !tab.granted -> UfiListEmptyState(
                        text = "设备端还没有授予「视频」的读取权限。\n" +
                            "请在设备上授予媒体读取权限（或「所有文件访问」），然后回来重新检查。",
                        icon = mediaTypeIcon(MEDIA_TYPE_VIDEO),
                        action = {
                            UfiButton(
                                text = "重新检查",
                                onClick = {
                                    media.loadStatus()
                                    media.loadFirstPage(MEDIA_TYPE_VIDEO, force = true)
                                },
                                variant = UfiButtonVariant.Subtle,
                                size = UfiButtonSize.Small
                            )
                        }
                    )

                    pane == PANE_HOME -> MediaVideoHome(
                        tab = tab,
                        thumbUrl = { media.thumbnailUrl(MEDIA_TYPE_VIDEO, it.id) },
                        onNearEnd = { media.loadMore(MEDIA_TYPE_VIDEO) },
                        onOpen = onOpen,
                        onThumbMissing = buildThumb,
                        onOpenRecent = { path, name, id ->
                            onOpen(MediaLibraryItem(id = id, name = name, path = path))
                        },
                        gridState = homeGridState,
                        onLongPress = { menuTarget = it }
                    )

                    else -> MediaVideoLibraryPane(
                        viewModel = viewModel,
                        onOpen = onOpen,
                        onDownload = onDownload,
                        onThumbMissing = buildThumb,
                        listState = libraryListState,
                        onLongPress = { menuTarget = it }
                    )
                }
            }
        }
    }


    /*
     * 长按菜单本体。
     *
     * 挂在页壳这一层（而不是行内）仍然弹得准：[UfiPopupMenu] 用调用方给的窗口坐标定位，
     * 它的 `anchor` 优先于 Compose 回调里的 bounds，正是为这种"菜单不在锚点层级里"的用法。
     *
     * 不用 `menuTarget?.let { 菜单 }` 包起来：那会在关闭时把菜单直接卸载、退场淡出播不出来。
     * 选项的 onClick 在组件内部先于 dismiss() 执行，所以回调里读 `menuTarget` 仍是被长按那一项。
     */
    val menuAnchor = menuTarget
    UfiPopupMenu(
        visible = menuAnchor != null,
        onDismiss = { menuTarget = null },
        anchorBounds = menuAnchor?.anchorBounds ?: IntRect.Zero,
        anchorPoint = menuAnchor?.anchorPoint,
        options = mediaVideoItemMenuOptions(
            onPlay = { menuTarget?.let { onOpen(it.item) } },
            onDownload = { menuTarget?.let { onDownload(it.item, it.downloadSubDir) } },
            onRename = { renameTarget = menuTarget?.item },
            onCopyPath = {
                menuTarget?.let {
                    fileOps.copyPathToClipboard(it.item.path)
                    /*
                     * copyPathToClipboard 顺手往文件管理器的 state 里写了一条
                     * operationMessage（那是给文件管理器页面弹 toast 用的）。本页自己弹，
                     * 所以立刻清掉 —— 不清的话用户下次进文件管理器会莫名看到一条"已复制路径"。
                     * 这个动作是同步的，所以清得掉；重命名/删除是异步的，清不了，
                     * 因此那两个也不拿它的消息当回执（见 refreshAfterFileChange）。
                     */
                    fileOps.clearFileOperationMessage()
                    toast = ToastMessage("已复制路径", ToastType.SUCCESS, subtitle = it.item.path)
                }
            },
            onInfo = { infoTarget = menuTarget?.item },
            onDelete = { deleteTarget = menuTarget?.item }
        )
    )

    MediaVideoInfoDialog(
        visible = infoTarget != null,
        item = infoTarget,
        onDismiss = { infoTarget = null }
    )

    val renameItem = renameTarget
    UfiInputDialog(
        visible = renameItem != null,
        title = "重命名",
        initialValue = renameItem?.name ?: "",
        hint = "新文件名",
        validator = { name ->
            when {
                name.isBlank() -> "名称不能为空"
                // core 侧按 parent + 新名拼全路径，带 / 会越出本目录
                name.contains('/') -> "名称不能包含 /"
                name == renameItem?.name -> "名称未改变"
                else -> null
            }
        },
        onConfirm = { newName ->
            renameTarget = null
            // 入参是**新文件名**，不是新全路径 —— parent 由 FileManagerModule 内部拼
            renameItem?.let { fileOps.renameFile(it.path, newName) }
            refreshAfterFileChange()
            toast = ToastMessage(
                text = "已提交重命名",
                type = ToastType.SUCCESS,
                subtitle = "列表要等系统媒体库更新；没跟上时到「媒体库」点「重扫」"
            )
        },
        onDismiss = { renameTarget = null }
    )

    val deleteItem = deleteTarget
    UfiConfirmDialog(
        visible = deleteItem != null,
        title = "删除这个视频？",
        // 说清"删到哪去了"：设备上是直接删，没有回收站可以捞回来
        text = "「${deleteItem?.name.orEmpty()}」将从设备上直接删除，不进回收站，无法撤销。",
        confirmText = "删除",
        destructive = true,
        onConfirm = {
            deleteTarget = null
            deleteItem?.let { fileOps.deleteFileOrDir(it.path) }
            refreshAfterFileChange()
            toast = ToastMessage(
                text = "已提交删除",
                type = ToastType.SUCCESS,
                subtitle = "列表要等系统媒体库更新；没跟上时到「媒体库」点「重扫」"
            )
        },
        onDismiss = { deleteTarget = null }
    )

    UfiToastHost(toastMessage = toast, onDismiss = { toast = null })
}

/**
 * 一次性说明：为什么缩略图是"慢慢长出来"的。
 *
 * 只在**真的**走过本机抽帧之后才出现（[MediaThumbnailBuilder.everUsed]）——
 * 对解码正常的设备提示这个纯属噪音。关掉之后不再出现（记在本地 prefs）；
 * 但每次开始抽帧仍会有 toast，那说的是"现在正在干活"，与"为什么这么干"是两件事。
 */
@Composable
private fun MediaLocalThumbNotice(onDismiss: () -> Unit) {
    val palette = LocalResolvedPalette.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .ufiStandardCard(elevation = 2.dp)
            .padding(horizontal = Spacing.Medium, vertical = Spacing.Small),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Default.Info,
            contentDescription = null,
            tint = palette.accent,
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.width(Spacing.Small))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                "设备端无法生成视频缩略图",
                style = UfiTextStyles.bodyEmphasis,
                color = palette.textPrimary
            )
            Text(
                "已改为由本机抽帧并回传设备（走局域网，不消耗蜂窝流量）。每个视频只需抽一次，之后网页端也能看到。",
                style = UfiTextStyles.note,
                color = palette.textSecondary
            )
        }
        Spacer(Modifier.width(Spacing.Small))
        IconButton(onClick = onDismiss) {
            Icon(
                Icons.Default.Close,
                contentDescription = "不再提示",
                tint = palette.textSecondary,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

/** 媒体库那一栏行内的缩略图尺寸（16:9）。骨架与真实行共用这两个常量，不各写一份字面量。 */
internal val MEDIA_VIDEO_THUMB_WIDTH = 72.dp
internal val MEDIA_VIDEO_THUMB_HEIGHT = 44.dp

/** "首页"（海报墙）那一栏的下标；另一栏是媒体库（文件夹）。 */
private const val PANE_HOME = 0

/**
 * 重命名 / 删除之后，等多久再去重拉列表。
 *
 * 这不是"动画时长"，而是给**局域网一次写请求 + MediaStore 跟上**留的余量：
 * 写请求本身通常几十毫秒，同步是系统那边的事。取 800ms 是权衡 ——
 * 太短必然拉到旧记录，太长会让用户觉得"点了没反应"。
 */
private const val FILE_OP_REFRESH_DELAY_MS = 800L

/** 两栏切换的标签，顺序即下标（[PANE_HOME] 对应第 0 个）。 */
private val MEDIA_VIDEO_PANES: List<String> = listOf("首页", "媒体库")


/**
 * 三个播放/查看页的参数形状一样（都只带 path），编码规则收在一处，免得三处各写一遍 encode。
 *
 * ## [route] 传**模式**还是**裸路径**都可以（2026-09-21 修"点了歌播不出来"）
 * `Routes.MEDIA_AUDIO` 这类常量本身是**带占位符的模式**
 * （`media/audio?path={path}&scope={scope}&scopeKey={scopeKey}`）。原来这里直接
 * `"$route?path=..."`，传模式进来就会拼出
 * `media/audio?path={path}&scope={scope}&scopeKey={scopeKey}?path=%2Fstorage%2F...`
 * —— `path` 出现两次、`scope` 的值是字面量 `{scope}`。后果是一条都不剩：
 * 页面拿到的 `path` 是字符串 `"{path}"`（于是取流请求变成
 * `/api/files/stream?path=%7Bpath%7D` → 400，歌放不出来），`scope` 非法回落成整库
 * （专辑 / 歌手 / 文件夹 / 歌单的播放范围全部失效）。
 *
 * 现在先把 `?` 之后的模式尾巴切掉。不改成"只许传裸路径"是因为调用点两种写法都已存在
 * （迷你条传 `"media/audio"`，[mediaAudioRouteOf] 传常量），而**传常量才是更该被鼓励的那种**
 * —— 手写字面量意味着改路由时这里不会跟着变。
 *
 * ## 编码用 [encodeUriComponent]，取参处**不要再解一次**（2026-09-21 修"专辑里没有歌"）
 * 原来是 `URLEncoder.encode` 配取参处的 `URLDecoder.decode`。这一对在**值里含 `+`** 时会丢字符：
 * Navigation 自己会先做一次 `Uri.decode`（把 `%2B` 解成 `+`），紧接着 `URLDecoder.decode`
 * 又把 `+` 当成空格 —— 专辑名 `万岁2001 新曲+精选` 就这样变成 `万岁2001 新曲 精选`，
 * 拿它去查曲目一条都查不到。详细推导见 [encodeUriComponent]。
 */
internal fun mediaRouteOf(route: String, path: String): String =
    route.substringBefore('?') + "?path=" + encodeUriComponent(path)

/**
 * 音乐播放页的路由（2026-09-21）：在 [mediaRouteOf] 之上补一对**播放范围**参数。
 *
 * ## 「不带 scope」与「scope=all」是两件事
 * - **不带**（[scopeKind] 为空）= "只是打开播放页看一眼"，队列**不要动**。
 *   迷你条、标题栏挂件、通知栏深链接走的都是这条 —— 它们只知道"正在播这首歌"，
 *   不知道当前队列是按什么范围装的，没有资格要求重建队列。
 * - **`scope=all`** = "请按整库重建队列"，由「全部」页点歌时显式给出。
 *
 * 这个区分是必须的：把"不带"当成整库的那一版，从迷你条点回播放页会把歌单/专辑队列
 * 静默换成整库 —— 歌还在放同一首，但「下一首」已经跑到别处去了。
 *
 * [scopeKey] 只有分组与歌单需要（整库没有 key）。folder 维度的 key 是绝对路径，
 * 编码规则与 path 完全一致（见 [mediaRouteOf] 的说明）。
 */
internal fun mediaAudioRouteOf(
    path: String,
    scopeKind: String = "",
    scopeKey: String = ""
): String {
    val base = mediaRouteOf(Routes.MEDIA_AUDIO, path)
    if (scopeKind.isBlank()) return base
    // 整库没有 key；其余维度 key 为空时无从回查，退回"不带 scope"比装个空队列合理
    if (scopeKind != AUDIO_SCOPE_ALL && scopeKey.isBlank()) return base
    return base +
        "&scope=" + encodeUriComponent(scopeKind) +
        "&scopeKey=" + encodeUriComponent(scopeKey)
}

