package com.ufi_axis.ui.media

import com.ufi_axis.ui.components.common.rememberLastNonNull
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import com.ufi_axis.data.model.MEDIA_GROUP_FOLDER
import com.ufi_axis.data.model.MEDIA_TYPE_AUDIO
import com.ufi_axis.data.model.MediaLibraryItem
import com.ufi_axis.data.model.PlaylistEntry
import com.ufi_axis.ui.components.common.UfiChoiceSheet
import com.ufi_axis.ui.components.common.UfiConfirmDialog
import com.ufi_axis.ui.components.common.UfiInputDialog
import com.ufi_axis.ui.components.common.UfiScrollableTabRow
import com.ufi_axis.ui.navigation.Routes
import com.ufi_axis.ui.navigation.ufiNavigateOnce
import com.ufi_axis.ui.theme.LocalResolvedPalette
import com.ufi_axis.ui.theme.Spacing
import com.ufi_axis.ui.theme.UfiTextStyles
import com.ufi_axis.util.encodeUriComponent
import com.ufi_axis.viewmodel.MainViewModel
import com.ufi_axis.viewmodel.state.AUDIO_SCOPE_ALL
import com.ufi_axis.viewmodel.state.MEDIA_GROUP_KINDS
import kotlinx.coroutines.delay

/**
 * 音乐页（工具 → 音乐）。
 *
 * 页壳在 [MediaLibraryPage]；本文件决定分页方式、每页内容与点击后的去向
 * （[MediaAudioPlayerScreen]，播放本身运行在 [UfiAudioPlaybackService] 中）。
 *
 * ## 五页切换
 * 全部 / 专辑 / 歌手 / 文件夹 / 歌单。切页状态使用 `rememberSaveable`：从播放页或详情页
 * 返回时随 back stack entry 还原，不会回到「全部」。
 *
 * 「歌单」与前四页并列而不是独立路由：各页查看的是同一个音乐库，区别仅在聚合方式
 *（歌单是用户自建的聚合）。分类 Tab 位于内容区上方的页壳 `topContent` 槽，底部只保留
 * 迷你控制条，后者为通栏贴底条。
 *
 * 采用页内切页而非五条路由：各页共用同一条排序与扫描范围，多路由需要各自保存这些状态，
 * 且来回切换会堆积返回栈。分组结果缓存在 `MediaLibraryState.audioGroups`，歌单缓存在
 * `MediaLibraryState.playlists`，切换不重新拉取。
 *
 * ## 切页不闪烁的条件
 * 三点同时成立，缺一处会出现空白 / 空态 / 骨架闪烁：
 * 1. 懒加载在数据层：`loadGroups(by)` / `loadPlaylists()` 自带 `loadedOnce` 守卫，
 *    只有首次切换到某页才发起网络请求；此处刻意不预拉。
 * 2. 滚动位置提升到切页之上（[paneListStates]）：切页替换整棵子树，子树内的
 *    `rememberLazyListState()` 会随子树销毁，切回时滚动位置归零。
 * 3. 骨架 / 空态判据使用 `loadedOnce`（见 [MediaAudioGroupList] / [MediaPlaylistList]）：
 *    若使用 `isLoading && !loadedOnce`，在「请求尚未发出」的那一帧两个条件均不成立，
 *    会先显示一帧空态。
 *
 * 不使用 `UfiPageSwitcher`：它基于 Pager，为跟手手势会组合相邻页，各页的 `LaunchedEffect`
 * 会同时触发，破坏上述第 1 点；其交叉淡入也无法解决「新页第一帧显示什么」，后者是判据
 * 问题而非动画问题。
 *
 * ## 歌单的写操作集中在本页
 * 新建 / 重命名 / 删除歌单与「加入歌单」的入口均在本页（长按一行 → 面板）。歌单详情页
 * 只负责展示该歌单内的曲目，不重复管理入口：两处各有一份时，文案与确认语义会不一致。
 *
 * ## 右上角
 * 齿轮 = 扫描目录与重新扫描（[Routes.MEDIA_AUDIO_SETTINGS]，配置完成后很少改动）。
 * 歌单页额外提供「新建歌单」：仅在该页出现，出现在「全部」页缺少语境。
 */
@Composable
fun MediaAudioScreen(
    viewModel: MainViewModel,
    navController: NavHostController
) {
    val palette = LocalResolvedPalette.current
    val media = viewModel.media
    val state by media.state.collectAsState()

    var pane by rememberSaveable { mutableIntStateOf(PANE_ALL) }

    /** 长按一首歌后弹出的动作表的目标。null = 不显示（见 MediaTrackActionHost）。 */
    var actionTarget by remember { mutableStateOf<MediaLibraryItem?>(null) }


    /** 「新建歌单」输入框的可见性（右上角按钮的入口，与面板内的「新建并加入」是两条独立路径）。 */
    var creatingPlaylist by remember { mutableStateOf(false) }

    /** 长按一个歌单后待选操作的目标。 */
    var playlistMenuTarget by remember { mutableStateOf<PlaylistEntry?>(null) }
    var renameTarget by remember { mutableStateOf<PlaylistEntry?>(null) }
    var deleteTarget by remember { mutableStateOf<PlaylistEntry?>(null) }

    /*
     * 五页各一份滚动位置，按下标与 [MEDIA_AUDIO_PANES] 一一对应。
     *
     * 必须建立在此处（切页之上）：内容区按 `if (pane == …)` 替换子树，建立在子树内的
     * `rememberLazyListState()` 会随子树销毁。
     *
     * 使用 `key(index)` 包装是 `rememberSaveable` 在循环中的要求：不提供 key 时多次调用
     * 落在同一个自动生成的槽位上，进程重建后还原会冲突。
     * 列表长度恒定（[MEDIA_AUDIO_PANES] 是编译期常量表），因此逐个 remember 是安全的。
     */
    val paneListStates = MEDIA_AUDIO_PANES.indices.map { index ->
        key(index) { rememberLazyListState() }
    }

    /*
     * 歌单操作提示（「已加入 3 首」/「已有同名歌单」）在数秒后自动消失。
     *
     * 若无自动消失，提示会持续显示在 Tab 下方直到下一次操作覆盖；用户早已读完，该行文字
     * 成为长期占位。清理逻辑放在此处而非各写操作内：写操作无法知道用户是否已读。
     */
    val playlistNotice = state.playlists.message ?: state.playlists.errorMessage
    LaunchedEffect(playlistNotice) {
        if (playlistNotice != null) {
            delay(PLAYLIST_NOTICE_MS)
            media.clearPlaylistMessage()
        }
    }

    MediaLibraryPage(
        title = "音乐",
        type = MEDIA_TYPE_AUDIO,
        viewModel = viewModel,
        navController = navController,
        // 行内封面为方形（非圆形头像），骨架需占成相同形状
        listSkeletonLeadingWidth = MEDIA_AUDIO_THUMB_SIZE,
        listSkeletonLeadingHeight = MEDIA_AUDIO_THUMB_SIZE,
        // showViewToggle 保持 false：音乐页只有列表一种呈现方式，不可用的视图切换按钮属于假入口
        // 目录入口只保留在设置页一处，工具条不重复提供
        showDirAction = false,
        actions = {
            // 仅在歌单页显示：其他页没有「新建歌单」的语境，常驻按钮会被理解为作用于当前列表
            if (pane == PANE_PLAYLIST) {
                IconButton(onClick = { creatingPlaylist = true }) {
                    Icon(
                        Icons.Default.PlaylistAdd,
                        contentDescription = "新建歌单",
                        tint = palette.textSecondary
                    )
                }
            }
            IconButton(onClick = { navController.ufiNavigateOnce(Routes.MEDIA_AUDIO_SETTINGS) }) {
                Icon(
                    Icons.Default.Settings,
                    contentDescription = "音乐设置",
                    tint = palette.textSecondary
                )
            }
        },
        topContent = {
            /*
             * 分类 Tab 位于工具条正下方。
             *
             * 与迷你控制条同处屏幕底部时，两条横向控件上下叠加：切分类与读列表的视线区域
             * 分离，每次切换都需要重新定位。移至内容区上方后，它与工具条（范围 caption +
             * 排序 / 重扫）构成「当前屏幕展示什么」的控制区，底部只保留播放控制。
             *
             * 左右内边距需自行设置：本槽位于页壳 padding 之外，而内容区使用
             * `padding(horizontal = Spacing.Medium)`，不补充时宽度会超出列表两侧。
             */
            Column {
                UfiScrollableTabRow(
                    selectedTabIndex = pane,
                    onTabSelected = { pane = it },
                    tabs = MEDIA_AUDIO_PANES,
                    modifier = Modifier.padding(
                        horizontal = Spacing.Medium,
                        vertical = Spacing.Small
                    )
                )
                /*
                 * 歌单操作的反馈挂在此处而非仅歌单页：「加入歌单」由「全部」页或分组页长按
                 * 触发，结果提示必须出现在触发它的页面上。
                 */
                playlistNotice?.let { msg ->
                    Text(
                        msg,
                        style = UfiTextStyles.note,
                        color = palette.textSecondary,
                        modifier = Modifier.padding(
                            horizontal = Spacing.Medium,
                            vertical = Spacing.Small
                        )
                    )
                }
            }
        },
        bottomBar = {
            /*
             * 底部只保留迷你控制条，且该条自身通栏贴底（见 [MediaAudioMiniBar]）：
             * 此处不再包一层带左右留白的 Column，否则通栏条两侧会出现与页面底色不同的条带。
             * 手势条避让由该组件内部处理，避免重复设置。
             */
            MediaAudioMiniBar(navController)
        },
        // 上滑跟手升起的播放面板（见 MediaAudioRisePanel.kt）：挂在页壳的整屏浮层槽上
        overlay = { MediaAudioRisePanel(viewModel, navController) }
    ) { tab ->
        when {
            pane == PANE_ALL -> MediaAudioList(
                items = tab.items,
                total = tab.total,
                thumbUrl = { media.thumbnailUrl(MEDIA_TYPE_AUDIO, it.id) },
                onNearEnd = { media.loadMore(MEDIA_TYPE_AUDIO) },
                // 长列表内快速连续点击两行是常见操作，两次 push 会叠加出两个播放页（需返回两次）。
                // 守卫按「目标是否为当前目的地」判断，理由见 ufiNavigateOnce。
                // 本页即整库，显式带上 scope=all：不带 scope 的语义为「不改动队列」
                //（供迷你条 / 标题栏挂件 / 通知栏使用），见 mediaAudioRouteOf。
                onClick = {
                    navController.ufiNavigateOnce(
                        mediaAudioRouteOf(it.path, scopeKind = AUDIO_SCOPE_ALL)
                    )
                },
                listState = paneListStates[PANE_ALL],
                // 长按 = 打开动作表（加入歌单 / 歌曲信息 / 下载到手机）。
                // 动作增至多个后必须先经过动作表，不再由长按直接弹出歌单选择面板。
                onLongClick = { actionTarget = it }

            )

            pane == PANE_PLAYLIST -> {
                // 切到歌单页才拉取；重复切换不会重复发起网络请求（loadPlaylists 自带 loadedOnce 守卫）
                LaunchedEffect(Unit) { media.loadPlaylists() }
                MediaPlaylistList(
                    state = state.playlists,
                    coverUrl = { entry ->
                        // cover_id 为 0 = 空歌单或首曲已失效，此时不拼接 URL（见 MediaPlaylistList）
                        if (entry.cover_id > 0) {
                            media.thumbnailUrl(MEDIA_TYPE_AUDIO, entry.cover_id)
                        } else {
                            null
                        }
                    },
                    onClick = { entry ->
                        navController.ufiNavigateOnce(mediaPlaylistRouteOf(entry.id))
                    },
                    onLongClick = { playlistMenuTarget = it },
                    listState = paneListStates[PANE_PLAYLIST]
                )
            }

            else -> {
                val by = MEDIA_GROUP_KINDS[pane - 1].first
                // 切到某个维度才拉取该维度：三个维度均为全库聚合，进入页面即预拉三份会产生
                // 两次无收益请求。重复切换不会重复发起网络请求：loadGroups 自带 loadedOnce
                // 守卫（force 才重新拉取）。
                LaunchedEffect(by) { media.loadGroups(by) }
                MediaAudioGroupList(
                    state = state.group(by),
                    coverUrl = { entry ->
                        // 文件夹维度一律绘制文件夹图标（与文件管理器一致），不使用组内某首歌的
                        // 封面作为缩略图 —— 与视频、图片两处口径相同。
                        if (by == MEDIA_GROUP_FOLDER) {
                            null
                        } else if (entry.cover_id > 0) {
                            media.thumbnailUrl(MEDIA_TYPE_AUDIO, entry.cover_id)
                        } else {
                            null
                        }
                    },
                    onClick = { entry ->
                        // 同上：分组行也是长列表中的一行，连续点击同样会叠加出两层分组详情页
                        navController.ufiNavigateOnce(mediaAudioGroupRouteOf(by, entry.key))
                    },
                    // 三个维度共用这一个调用点，滚动位置必须按 pane 分别传入，
                    // 否则在专辑页滚动后切至歌手页，会停在同一偏移上。
                    listState = paneListStates[pane]
                )
            }
        }
    }

    // ── 歌单相关弹窗（均挂在页面层，与列表子树的存亡无关）──

    MediaTrackActionHost(
        viewModel = viewModel,
        target = actionTarget,
        // 整库列表：条目不在任何歌单内，因此没有「移出歌单」
        actionsOf = {
            mediaTrackActionsFor(it, inPlaylist = false, canEditLibrary = true, canPlayNext = true)
        },
        onDone = { actionTarget = null }
    )



    if (creatingPlaylist) {
        UfiInputDialog(
            title = "新建歌单",
            hint = "歌单名",
            onConfirm = {
                creatingPlaylist = false
                media.createPlaylist(it.trim())
            },
            onDismiss = { creatingPlaylist = false },
            validator = { if (it.isBlank()) "歌单名不能为空" else null }
        )
    }

    playlistMenuTarget?.let { entry ->
        UfiChoiceSheet(
            title = entry.name,
            options = listOf(
                PLAYLIST_ACTION_RENAME to "重命名",
                PLAYLIST_ACTION_DELETE to "删除歌单"
            ),
            onDismiss = { playlistMenuTarget = null },
            onSelect = { action ->
                when (action) {
                    PLAYLIST_ACTION_RENAME -> renameTarget = entry
                    PLAYLIST_ACTION_DELETE -> deleteTarget = entry
                }
            }
        )
    }

    renameTarget?.let { entry ->
        UfiInputDialog(
            title = "重命名歌单",
            initialValue = entry.name,
            onConfirm = {
                renameTarget = null
                media.renamePlaylist(entry.id, it.trim())
            },
            onDismiss = { renameTarget = null },
            validator = { if (it.isBlank()) "歌单名不能为空" else null }
        )
    }

    val dialogDeleteTarget = rememberLastNonNull(deleteTarget)
    if (dialogDeleteTarget != null) {
        val entry = dialogDeleteTarget
        UfiConfirmDialog(
        visible = deleteTarget != null,
            title = "删除歌单",
            // 说明「不删文件」：歌单中保存的是路径，用户容易误认为删除歌单会同时删除歌曲
            text = "删除歌单「${entry.name}」？里面的 ${entry.count} 首歌不会被删除。",
            confirmText = "删除",
            destructive = true,
            onConfirm = {
                deleteTarget = null
                media.deletePlaylist(entry.id)
            },
            onDismiss = { deleteTarget = null }
        )
    }
}


/**
 * 构造分组详情页路由。
 *
 * 路径部分从 [Routes.MEDIA_AUDIO_GROUP] 切出而非手写字面量：修改路由常量时此处随之更新。
 *
 * 编码使用 [encodeUriComponent]（空格编为 `%20`，不产生裸 `+`）；取参处只依赖 Navigation
 * 的那一次 `Uri.decode`，不得再次解码 —— 二次解码会吃掉值中的 `+`。
 * folder 维度的 key 是绝对路径，不编码时其中的 `/` 会被 Navigation 当作路径分隔符。
 */
internal fun mediaAudioGroupRouteOf(by: String, key: String): String =
    Routes.MEDIA_AUDIO_GROUP.substringBefore('?') +
        "?by=" + encodeUriComponent(by) +
        "&key=" + encodeUriComponent(key)

/**
 * 构造歌单详情页路由。
 *
 * id 是 core 生成的 8 位十六进制串，本身不含需要转义的字符；仍按 [mediaAudioGroupRouteOf]
 * 的方式编码一次，以保持媒体域路由拼法一致，避免出现特例。
 */
internal fun mediaPlaylistRouteOf(id: String): String =
    Routes.MEDIA_AUDIO_PLAYLIST.substringBefore('?') + "?id=" + encodeUriComponent(id)


/** 「全部」页的下标。其后三页按顺序对应 [MEDIA_GROUP_KINDS]，即 `pane - 1`。 */
private const val PANE_ALL = 0

/**
 * 分类切页的标签。
 *
 * 中间三个名称取自 [MEDIA_GROUP_KINDS]（维度与中文名的唯一来源），此处不另写一份：
 * 否则新增维度需改两处，漏改只表现为界面少一页，编译不会报错。
 *「歌单」位于最后：它不是按标签聚合，与前面三个维度不同类。
 */
private val MEDIA_AUDIO_PANES: List<String> =
    listOf("全部") + MEDIA_GROUP_KINDS.map { it.second } + listOf("歌单")

/** 歌单页的下标。随 [MEDIA_AUDIO_PANES] 推导，不硬编码数字。 */
private val PANE_PLAYLIST = MEDIA_AUDIO_PANES.lastIndex

/** 歌单行长按菜单的动作标识（仅本文件使用，不进入契约）。 */
private const val PLAYLIST_ACTION_RENAME = "rename"
private const val PLAYLIST_ACTION_DELETE = "delete"

/** 歌单操作提示的显示时长（ms）。足以读完一句「已加入 N 首」，且不会长期占位。 */
private const val PLAYLIST_NOTICE_MS = 4000L
