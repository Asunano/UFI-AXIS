package com.ufi_axis.ui.media

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.ufi_axis.data.model.MediaLibraryItem
import com.ufi_axis.data.model.PlaylistEntry
import com.ufi_axis.ui.components.common.UfiChoiceSheet
import com.ufi_axis.ui.components.common.UfiInputDialog
import com.ufi_axis.viewmodel.module.MediaModule

/**
 * 「加入歌单」底部面板（无状态：`visible + onDismiss/onPick/onCreateAndAdd`）。
 *
 * ## 为什么直接复用 [UfiChoiceSheet]
 * 这一步本质就是"从几个里挑一个"，与编码 / 排序那些面板同一形态。为它另画一个列表只会得到
 * 第二套选中态与间距，而那两样在 [UfiChoiceSheet] 里已经统一过（整行可点 + 选中强调）。
 *
 * ## 「新建歌单并加入」为什么在同一个面板里
 * 第一次用歌单功能时一个歌单都没有 —— 如果这里只列已有歌单，用户会看到一个空面板，
 * 得先退出去、切到歌单页、建一个、再回来重新长按。所以列表末尾永远挂一条
 * [NEW_PLAYLIST_VALUE]，选它就弹输入框，确认后由 `MediaModule.createPlaylist(name, thenAddPaths)`
 * 一次完成"建 + 加"（拆成两步的话中途失败会留下一个空歌单，而用户以为歌加进去了）。
 *
 * ## 两段状态为什么能接上
 * [UfiChoiceSheet] 在 `onSelect` 之后会**自己调一次** `onDismiss`，于是选完"新建"那一瞬
 * 外部的 `visible` 就变 false 了。输入框的显隐因此不能看 `visible`，而要看本组件内部的
 * `creatingName` —— 它活在本组件里，不受 `visible` 影响。
 *
 * @param songTitle 面板标题里显示的曲名（多选时由调用方给"N 首"这类文案）。
 * @param onPick 选中了已有歌单，入参是歌单 id。
 * @param onCreateAndAdd 选了"新建歌单"并填好了名字。
 */
@Composable
internal fun MediaAddToPlaylistSheet(
    visible: Boolean,
    songTitle: String,
    playlists: List<PlaylistEntry>,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
    onCreateAndAdd: (String) -> Unit
) {
    var creating by remember { mutableStateOf(false) }

    if (visible && !creating) {
        UfiChoiceSheet(
            title = if (songTitle.isBlank()) "加入歌单" else "加入歌单 · $songTitle",
            // 条数写进标签：同名歌单不允许存在，所以"名字 + 条数"足以让人认出是哪一个
            options = playlists.map { it.id to "${it.name}（${it.count} 首）" } +
                listOf(NEW_PLAYLIST_VALUE to NEW_PLAYLIST_LABEL),
            onDismiss = onDismiss,
            onSelect = { value ->
                if (value == NEW_PLAYLIST_VALUE) creating = true else onPick(value)
            }
        )
    }

    if (creating) {
        UfiInputDialog(
            title = "新建歌单",
            hint = "歌单名",
            onConfirm = { name ->
                creating = false
                onCreateAndAdd(name.trim())
            },
            onDismiss = {
                // 只关输入框，不回到选择面板：用户已经表达过"我要新建"，
                // 把他弹回上一层会让"取消"看起来像没生效
                creating = false
                onDismiss()
            },
            validator = { if (it.isBlank()) "歌单名不能为空" else null }
        )
    }
}

/**
 * 「新建歌单」那一条的 value。
 *
 * 用一个不可能与歌单 id 相同的串：core 生成的 id 是 8 位十六进制（UUID 截断），
 * 不含下划线，所以这个值永远不会撞上真实歌单。
 */
private const val NEW_PLAYLIST_VALUE = "__new_playlist__"

private const val NEW_PLAYLIST_LABEL = "＋ 新建歌单…"

/**
 * [MediaAddToPlaylistSheet] 的带状态外壳：三个入口（音乐列表、分组详情、播放页）共用。
 *
 * 把"拉歌单列表 + 接线到 `MediaModule`"收在这里，是因为每个入口都要做同样的三件事：
 * 打开面板时补拉一次歌单（用户可能从没进过歌单页，否则面板里只剩"新建"）、
 * 选中已有歌单就加、选"新建"就建完再加。三份手写副本迟早有一份忘了补拉。
 *
 * @param target 长按选中的那一首。null = 面板不显示。
 * @param onDone 面板关闭（取消或已提交）时调用，调用方据此把 target 置回 null。
 */
@Composable
internal fun MediaAddToPlaylistHost(
    media: MediaModule,
    target: MediaLibraryItem?,
    onDone: () -> Unit
) {
    val state by media.state.collectAsState()

    // 面板是在长按那一刻打开的，此时歌单列表可能还没拉过
    LaunchedEffect(target) { if (target != null) media.loadPlaylists() }

    MediaAddToPlaylistSheet(
        visible = target != null,
        songTitle = target?.let { audioDisplayTitle(it) }.orEmpty(),
        playlists = state.playlists.playlists,
        onDismiss = onDone,
        onPick = { playlistId ->
            target?.let { media.addToPlaylist(playlistId, listOf(it.path)) }
            onDone()
        },
        onCreateAndAdd = { name ->
            // 一次调用完成"建 + 加"：拆成两步的话中途失败会留下一个空歌单
            target?.let { media.createPlaylist(name, listOf(it.path)) }
            onDone()
        }
    )
}
