package com.ufi_axis.ui.media

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
import com.ufi_axis.ui.components.common.UfiListEmptyState
import com.ufi_axis.ui.components.common.UfiScreenScaffold
import com.ufi_axis.ui.components.common.UfiScrollableTabRow
import com.ufi_axis.ui.components.common.UfiToastHost
import com.ufi_axis.ui.navigation.Routes
import com.ufi_axis.ui.theme.LocalResolvedPalette
import com.ufi_axis.ui.theme.Spacing
import com.ufi_axis.ui.theme.UfiTextStyles
import com.ufi_axis.ui.theme.ufiStandardCard
import com.ufi_axis.util.AppPreferences
import com.ufi_axis.viewmodel.MainViewModel
import java.io.File
import java.net.URLEncoder

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
 */
@Composable
fun MediaVideoScreen(
    viewModel: MainViewModel,
    navController: NavHostController
) {
    val palette = LocalResolvedPalette.current
    val media = viewModel.media
    val context = LocalContext.current
    val state by media.state.collectAsState()
    val tab = state.tab(MEDIA_TYPE_VIDEO)

    // 进页先拿授权状态（决定是列表还是引导），再拉第一页
    LaunchedEffect(Unit) { media.loadStatus() }
    LaunchedEffect(Unit) { media.loadFirstPage(MEDIA_TYPE_VIDEO) }
    LaunchedEffect(Unit) { MediaDownloadQueue.ensureLoaded(context) }

    var pane by rememberSaveable { mutableIntStateOf(0) }

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

                    pane == 0 -> MediaVideoHome(
                        tab = tab,
                        thumbUrl = { media.thumbnailUrl(MEDIA_TYPE_VIDEO, it.id) },
                        onNearEnd = { media.loadMore(MEDIA_TYPE_VIDEO) },
                        onOpen = onOpen,
                        onThumbMissing = buildThumb,
                        onOpenRecent = { path, name, id ->
                            onOpen(MediaLibraryItem(id = id, name = name, path = path))
                        }
                    )

                    else -> MediaVideoLibraryPane(
                        viewModel = viewModel,
                        onOpen = onOpen,
                        onDownload = onDownload,
                        onThumbMissing = buildThumb
                    )
                }
            }

            // 底栏：两栏切换。放在内容下方、避让手势条 —— 用户明确要求底栏而不是顶栏。
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = Spacing.Medium, vertical = Spacing.Small)
            ) {
                UfiScrollableTabRow(
                    selectedTabIndex = pane,
                    onTabSelected = { pane = it },
                    tabs = listOf("首页", "媒体库")
                )
            }
        }
    }

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

/** 三个播放/查看页的参数形状一样（都只带 path），编码规则收在一处，免得三处各写一遍 encode。 */
internal fun mediaRouteOf(route: String, path: String): String =
    "$route?path=" + URLEncoder.encode(path, "UTF-8")
