package com.ufi_axis.ui.media

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
 * 音乐页（工具 → 音乐）。2026-09-16 从"媒体中心"三栏之一拆成独立页。
 *
 * 页壳在 [MediaLibraryPage]；本文件决定三件事：分几页、每页画什么、点开去哪
 * （[MediaAudioPlayerScreen]，播放本身跑在 [UfiAudioPlaybackService] 里）。
 *
 * ## 五页切换（照视频页那套）
 * 全部 / 专辑 / 歌手 / 文件夹 / 歌单。切页状态用 `rememberSaveable` —— 从播放页或详情页返回时
 * 它随 back stack entry 还原，不会莫名跳回"全部"。
 *
 * 2026-09-21 加入「歌单」：它与前四页并列而不是另开一条路由，理由同下 ——
 * 看的是同一个音乐库，只是聚合方式不同（歌单是用户自己排的那种"聚合"）。
 *
 * 2026-09-20：这些 Tab 从屏幕底部移到**内容区上方**（页壳的 `topContent` 槽）。底部现在
 * 只留迷你控制条，它已改成通栏贴底条。
 *
 * 做成页内切页而不是五条路由：各页看的是同一个库、共用同一条排序与扫描范围，
 * 只是**聚合方式**不同。多条路由要把这些状态各存一份，用户来回切还会一路堆返回栈。
 * 分组结果缓存在 `MediaLibraryState.audioGroups`、歌单在 `MediaLibraryState.playlists`，
 * 来回切不重拉。
 *
 * ## 切页为什么不闪（2026-09-20 修）
 * 三处合起来才成立，缺一处就会看到"空白 / 空态 / 骨架"闪一下：
 * 1. **懒加载在数据层**：`loadGroups(by)` / `loadPlaylists()` 自带 `loadedOnce` 守卫，
 *    只有第一次切到某页才真的打网络；这里刻意**不**预拉。
 * 2. **滚动位置提到切页之上**（[paneListStates]）：切页换的是一棵子树，
 *    子树里的 `rememberLazyListState()` 会跟着一起销毁，切回来就回到顶部。
 * 3. **骨架/空态的判据改看 `loadedOnce`**（见 [MediaAudioGroupList] / [MediaPlaylistList]）：
 *    原来的 `isLoading && !loadedOnce` 在"请求还没发出去"的那一帧两个条件都不成立，
 *    于是先闪一帧空态。
 *
 * 为什么**没有**改用 `UfiPageSwitcher`（那才是本项目的"切页现成件"）：它是 Pager 后端，
 * 为了手势跟手会把相邻页一起组合，于是各页的 `LaunchedEffect` 会同时触发 ——
 * 恰好把第 1 条懒加载毁掉；而它的交叉淡入在这里也帮不上忙，真正要修的是
 * "新页第一帧显示什么"，那是判据问题，不是动画问题。
 *
 * ## 歌单的写操作都在这一页
 * 新建 / 重命名 / 删除歌单，以及「加入歌单」，入口全在本页（长按一行 → 面板）。
 * 歌单详情页只管"这个歌单里的曲目"，不重复一套管理入口 —— 两处各有一份的话，
 * 文案与确认语义迟早分叉。
 *
 * ## 右上角
 * 齿轮 = 扫描目录与重新扫描（[Routes.MEDIA_AUDIO_SETTINGS]，"配一次就不动"的东西）。
 * 歌单页额外多一颗「新建歌单」—— 只在那一页出现：在"全部"页摆一颗新建歌单没有语境。
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

    /** 长按一首歌之后待「加入歌单」的那一首。null = 面板不显示。 */
    var addTarget by remember { mutableStateOf<MediaLibraryItem?>(null) }

    /** 「新建歌单」输入框（右上角那颗按钮的入口，与面板里的"新建并加入"是两条路）。 */
    var creatingPlaylist by remember { mutableStateOf(false) }

    /** 长按一个歌单之后待选操作的那一个。 */
    var playlistMenuTarget by remember { mutableStateOf<PlaylistEntry?>(null) }
    var renameTarget by remember { mutableStateOf<PlaylistEntry?>(null) }
    var deleteTarget by remember { mutableStateOf<PlaylistEntry?>(null) }

    /*
     * 五页各一份滚动位置，**按下标一一对应** [MEDIA_AUDIO_PANES]。
     *
     * 必须建在这里（切页之上）：内容区是 `if (pane == …)` 换子树，建在子树里的
     * `rememberLazyListState()` 会随子树销毁。
     *
     * 用 `key(index)` 包一层是 `rememberSaveable` 在循环里的硬要求 ——
     * 不给 key 的话多次调用落在同一个自动生成的槽位上，进程重建后还原会撞键。
     * 列表长度恒定（[MEDIA_AUDIO_PANES] 是编译期常量表），所以逐个 remember 是安全的。
     */
    val paneListStates = MEDIA_AUDIO_PANES.indices.map { index ->
        key(index) { rememberLazyListState() }
    }

    /*
     * 歌单操作的提示（"已加入 3 首" / "已有同名歌单"）几秒后自动消失。
     *
     * 不做自动消失的话它会一直挂在 Tab 下面：下一次操作才会被覆盖，而用户早就读完了，
     * 于是那行字变成永久占位。清理放在这里而不是各个写操作里 ——
     * 写操作不知道"用户看到了没有"。
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
        // 行里的封面是方形（不是圆形头像），骨架要占成同样的形状
        listSkeletonLeadingWidth = MEDIA_AUDIO_THUMB_SIZE,
        listSkeletonLeadingHeight = MEDIA_AUDIO_THUMB_SIZE,
        // showViewToggle 保持 false：音乐页只有列表一种画法，摆一颗按不动的视图按钮就是假按钮
        // 目录入口只留在设置页一处，工具条上不再重复
        showDirAction = false,
        actions = {
            // 只在歌单页露出来：其它页没有"新建歌单"的语境，常驻一颗会让人以为它对当前列表生效
            if (pane == PANE_PLAYLIST) {
                IconButton(onClick = { creatingPlaylist = true }) {
                    Icon(
                        Icons.Default.PlaylistAdd,
                        contentDescription = "新建歌单",
                        tint = palette.textSecondary
                    )
                }
            }
            IconButton(onClick = { navController.navigate(Routes.MEDIA_AUDIO_SETTINGS) }) {
                Icon(
                    Icons.Default.Settings,
                    contentDescription = "音乐设置",
                    tint = palette.textSecondary
                )
            }
        },
        topContent = {
            /*
             * 分类 Tab 从底部搬到了工具条正下方（2026-09-20）。
             *
             * 原来它与迷你控制条挤在屏幕最底部，两条横向控件上下叠着：切分类时眼睛在底部、
             * 读列表时眼睛在中上部，每切一次都要来回扫一趟。放到内容区上方后它与工具条
             * （范围 caption + 排序/重扫）连成一块"这一屏在看什么"的控制区，底部只剩播放控制。
             *
             * 左右内边距要自己写：本槽在页壳的 padding 之外，而内容区是
             * `padding(horizontal = Spacing.Medium)`，不补就会比列表宽出两边。
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
                 * 歌单操作的反馈挂在这里而**不是**只在歌单页里：「加入歌单」是从"全部"或
                 * 分组页长按触发的，结果提示必须出现在触发它的那一页上。
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
             * 底部只剩迷你控制条，且它自己是通栏贴底的（见 [MediaAudioMiniBar]）——
             * 所以这里**不再**包一层带左右留白的 Column：那会在通栏条两侧留出两道
             * 与页面底色不同的缝。手势条避让也交给它内部处理，避免叠两次。
             */
            MediaAudioMiniBar(navController)
        }
    ) { tab ->
        when {
            pane == PANE_ALL -> MediaAudioList(
                items = tab.items,
                total = tab.total,
                thumbUrl = { media.thumbnailUrl(MEDIA_TYPE_AUDIO, it.id) },
                onNearEnd = { media.loadMore(MEDIA_TYPE_AUDIO) },
                // 长列表里手快连点两行是常态，两次 push 会叠出两个播放页（返回要按两次）。
                // 守卫按"目标是不是当前目的地"判，理由见 ufiNavigateOnce
                // 这一页就是整库，**显式**带上 scope=all：不带 scope 的语义已经改成
                // "别动队列"（给迷你条 / 标题栏挂件 / 通知栏用），见 mediaAudioRouteOf
                onClick = {
                    navController.ufiNavigateOnce(
                        mediaAudioRouteOf(it.path, scopeKind = AUDIO_SCOPE_ALL)
                    )
                },
                listState = paneListStates[PANE_ALL],
                // 长按 = 加入歌单。列表页只有这一个长按动作，所以直接开歌单选择面板，
                // 不再套一层只有一项的操作菜单
                onLongClick = { addTarget = it }
            )

            pane == PANE_PLAYLIST -> {
                // 切到歌单页才拉；重复切换不会重复打网络（loadPlaylists 自带 loadedOnce 守卫）
                LaunchedEffect(Unit) { media.loadPlaylists() }
                MediaPlaylistList(
                    state = state.playlists,
                    coverUrl = { entry ->
                        // cover_id 为 0 = 空歌单或首曲已失效，此时不拼 URL（见 MediaPlaylistList）
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
                // 切到某个维度才去拉它 —— 三个维度都是全库聚合，进页就预拉三份是白跑两次。
                // 重复切换不会重复打网络：loadGroups 自带 loadedOnce 守卫（force 才重来）。
                LaunchedEffect(by) { media.loadGroups(by) }
                MediaAudioGroupList(
                    state = state.group(by),
                    coverUrl = { entry ->
                        // cover_id 为 0 = 这一组一张封面都没有，此时不拼 URL（见 MediaAudioGroupList）
                        if (entry.cover_id > 0) {
                            media.thumbnailUrl(MEDIA_TYPE_AUDIO, entry.cover_id)
                        } else {
                            null
                        }
                    },
                    onClick = { entry ->
                        // 同上：分组行也是长列表里的一行，连点同样会叠出两层分组详情页
                        navController.ufiNavigateOnce(mediaAudioGroupRouteOf(by, entry.key))
                    },
                    // 三个维度共用本组件这一个调用点，所以滚动位置必须按 pane 分开传，
                    // 否则在专辑页滚到一半再切歌手，歌手页会停在同一个偏移上
                    listState = paneListStates[pane]
                )
            }
        }
    }

    // ── 歌单相关的弹窗（都挂在页面这一层，与列表子树的存亡无关）──

    MediaAddToPlaylistHost(media = media, target = addTarget, onDone = { addTarget = null })


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

    deleteTarget?.let { entry ->
        UfiConfirmDialog(
            title = "删除歌单",
            // 说清"不删文件"：歌单里存的是路径，用户很容易以为删歌单会连歌一起删
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
 * 路径部分从 [Routes.MEDIA_AUDIO_GROUP] 切出来而不是手写字面量：改路由常量时这里会跟着走。
 *
 * 编码用 [encodeUriComponent]（空格编 `%20`，不留裸 `+`），取参处**只靠 Navigation 那一次
 * `Uri.decode`，不要再解第二次** —— 双重解码会吃掉值里的 `+`。
 * folder 维度的 key 是绝对路径，不编码的话里面的 `/` 会被 Navigation 当成路径分隔符。
 */
internal fun mediaAudioGroupRouteOf(by: String, key: String): String =
    Routes.MEDIA_AUDIO_GROUP.substringBefore('?') +
        "?by=" + encodeUriComponent(by) +
        "&key=" + encodeUriComponent(key)

/**
 * 构造歌单详情页路由。
 *
 * id 是 core 生成的 8 位十六进制串，本身不含需要转义的字符；仍然照 [mediaAudioGroupRouteOf]
 * 那套编一次 —— 整个媒体域的路由拼法保持一致，比"这一条特殊"更不容易日后踩坑。
 */
internal fun mediaPlaylistRouteOf(id: String): String =
    Routes.MEDIA_AUDIO_PLAYLIST.substringBefore('?') + "?id=" + encodeUriComponent(id)


/** "全部"那一页的下标。之后三页按顺序对应 [MEDIA_GROUP_KINDS]，即 `pane - 1`。 */
private const val PANE_ALL = 0

/**
 * 分类切页的标签。
 *
 * 中间三个名字取自 [MEDIA_GROUP_KINDS]（维度与中文名的唯一来源），这里不再写第二份 ——
 * 否则加一个维度要改两处，而漏改的那处只会在界面上少一页、编译不会报错。
 * 「歌单」排在最后：它不是"按标签聚合"，与前面三个不同类。
 */
private val MEDIA_AUDIO_PANES: List<String> =
    listOf("全部") + MEDIA_GROUP_KINDS.map { it.second } + listOf("歌单")

/** 歌单页的下标。跟着 [MEDIA_AUDIO_PANES] 走，不写死数字。 */
private val PANE_PLAYLIST = MEDIA_AUDIO_PANES.lastIndex

/** 歌单行长按菜单的动作标识（只在本文件内用，不需要进契约）。 */
private const val PLAYLIST_ACTION_RENAME = "rename"
private const val PLAYLIST_ACTION_DELETE = "delete"

/** 歌单操作提示的存留时长。够读完一句"已加入 N 首"，又不至于长期占位。 */
private const val PLAYLIST_NOTICE_MS = 4000L
