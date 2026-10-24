package com.ufi_axis.ui.media

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BluetoothAudio
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Lyrics
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MusicOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavHostController
import com.ufi_axis.data.media.UfiAudioLyrics
import com.ufi_axis.data.model.MEDIA_TYPE_AUDIO
import com.ufi_axis.ui.components.common.UfiButton
import com.ufi_axis.ui.components.common.UfiButtonSize
import com.ufi_axis.ui.components.common.UfiButtonVariant
import com.ufi_axis.ui.components.common.UfiPageBackground
import com.ufi_axis.ui.components.common.UfiScreenScaffold
import com.ufi_axis.ui.components.common.UfiSettingsChevron
import com.ufi_axis.ui.components.common.UfiSettingsItem
import com.ufi_axis.ui.components.common.UfiSettingsToggle
import com.ufi_axis.ui.components.common.UfiSettingsRowCard
import com.ufi_axis.ui.navigation.Routes
import com.ufi_axis.ui.theme.Spacing
import com.ufi_axis.util.AppPreferences
import com.ufi_axis.viewmodel.MainViewModel
import com.ufi_axis.viewmodel.state.MEDIA_GROUP_KINDS

/**
 * 音乐页设置（右上角齿轮进来）。
 *
 * ## 为什么只有"扫描范围"一组
 * 音乐页不需要封面抽帧（音频封面是内嵌的，设备直接给得出）、也没有下载队列，
 * 视频设置页里那几组对它全是空的。这一页只收「配一次就不动」的东西 ——
 * 原来它们是音乐页工具条上的两颗按钮，而工具条那块地现在要留给列表内容与分类切页。
 *
 * 2026-09-23 多了一行「已从音乐库移除的歌曲」：长按菜单里的「从音乐库移除」写的是 core 侧的
 * 排除名单，没有一个看得见的入口就等于不可撤销，所以那个动作与这一行是一起上的。
 *
 * ## 页壳用的是全站标准设置页那一套
 * `UfiScreenScaffold` + [UfiPageBackground] + 一项一张 `UfiSettingsRowCard`，与外观 /
 * 告警 / 后台守护 / 监控四页同构。本页此前手写 `Column + verticalScroll +
 * padding(horizontal = 8dp) + spacedBy(8dp)`，结果卡片左右比标准页多缩 8dp、卡间距少 8dp ——
 * 同一个 App 里这一页就是"看着不太一样"。[UfiPageBackground] 自带滚动，卡间距由内容最外层
 * 这层 `Column` 用 `Spacing.SettingsCardGap`（10dp）统一给（与网络设置一致），
 * **页面里不要再套 `verticalScroll`**，也不要再加水平内距（卡片自带 16dp）。
 */
@Composable
fun MediaAudioSettingsScreen(
    viewModel: MainViewModel,
    navController: NavHostController
) {
    val media = viewModel.media
    val state by media.state.collectAsState()

    // 进页先拿一次状态：扫描目录存在 core，本页显示的是设备侧的真实配置而不是本机记忆
    LaunchedEffect(Unit) { media.loadStatus() }

    // 排除名单只为了在入口行上显示条数。force = false：进出这一页不该每次都打一次网络，
    // 真正需要新鲜数据的是管理页自己（它进页就 force 重拉）
    LaunchedEffect(Unit) { media.loadExcludedMedia() }

    /*
     * 两行入口的条数：
     * · 最近播放读本机偏好（探针写入，见 `UfiAudioNowPlayingProbe`）；
     * · 下载记录读文件链那份历史（与文件管理器共用），进页拉一次即可。
     */
    val context = LocalContext.current
    val appPrefs = remember(context) { AppPreferences(context) }
    val recentCount = remember { appPrefs.mediaRecentPlays(MEDIA_TYPE_AUDIO).size }
    val filesState by viewModel.files.state.collectAsState()
    val downloadCount = remember(filesState.phoneDownloadHistory) {
        filesState.phoneDownloadHistory.count { it.isAudioDownload() }
    }
    LaunchedEffect(Unit) { viewModel.files.loadPhoneDownloadHistory() }
    // 歌词磁盘缓存条数（清空后要立刻反映到描述上，所以放 state）
    var lyricCacheCount by remember { mutableStateOf(UfiAudioLyrics.diskCacheCount()) }

    // 外接设备两项开关（与播放服务读的是同一组键；改完立刻生效，不需要重启服务）
    var externalControl by remember { mutableStateOf(appPrefs.audioExternalControl) }
    var btLyrics by remember { mutableStateOf(appPrefs.audioBtLyrics) }

    UfiScreenScaffold(title = "音乐设置", navController = navController, showBack = true) { padding ->
        UfiPageBackground(modifier = Modifier.padding(padding)) {
            // 卡间距与网络设置页一致（10dp / Spacing.SettingsCardGap）：共用件一次吐两张单行卡，
            // 它们都是这层 Column 的直接子项，间距由这里统一给
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.SettingsCardGap)) {
                MediaScanScopeSection(
                    type = MEDIA_TYPE_AUDIO,
                    typeLabel = mediaTypeLabel(MEDIA_TYPE_AUDIO),
                    viewModel = viewModel,
                    onDirsChanged = {
                        /*
                         * 三个维度的分组结果一并重算：分组是对**扫描范围内**的曲目做聚合，
                         * 换了目录之后专辑数、歌手数、文件夹树没有一个还成立。
                         * 只刷当前那一个维度会留下两份看着正常、实际已经过期的缓存。
                         *
                         * 视频页那边的收尾是"重拉文件夹视图"——两者各自都对，不要统一。
                         */
                        MEDIA_GROUP_KINDS.forEach { (by, _) -> media.loadGroups(by, force = true) }
                    }
                )

                UfiSettingsRowCard {
                    UfiSettingsItem(
                        title = "已从音乐库移除的歌曲",
                        // 0 首也照常显示这一行：它是"移除还能撤销"这件事的唯一说明处，
                        // 按条数隐藏的话，用户第一次用长按菜单时找不到任何后路
                        description = if (state.excluded.total == 0) {
                            "长按歌曲选「从音乐库移除」后会记在这里，可随时恢复"
                        } else {
                            "${state.excluded.total} 首，可恢复"
                        },
                        icon = Icons.Default.MusicOff,
                        trailing = { UfiSettingsChevron() },
                        onClick = { navController.navigate(Routes.MEDIA_AUDIO_EXCLUDED) }
                    )
                }

                /*
                 * ── 最近播放 / 下载记录（2026-10-21）──
                 *
                 * 两件事此前都"做了但没有出口"：播放记录只写在本机偏好里，下载记录写进了文件链的
                 * 历史却没有任何页面读。这里各给一行入口，描述里带条数 —— 不带信息的死入口
                 * 与带信息的入口在界面上分不出来（视频设置页同一条规矩）。
                 */
                UfiSettingsRowCard {
                    UfiSettingsItem(
                        title = "最近播放",
                        description = if (recentCount == 0) {
                            "本机记录最近播放的曲目，可逐条重新播放。"
                        } else {
                            "已记录 ${recentCount} 首，可逐条重新播放。"
                        },
                        icon = Icons.Default.History,
                        trailing = { UfiSettingsChevron() },
                        onClick = { navController.navigate(Routes.MEDIA_AUDIO_RECENT) }
                    )
                }

                UfiSettingsRowCard {
                    UfiSettingsItem(
                        title = "下载记录",
                        description = if (downloadCount == 0) {
                            "记录已下载到手机的曲目。"
                        } else {
                            "已记录 ${downloadCount} 条下载记录。"
                        },
                        icon = Icons.Default.Download,
                        trailing = { UfiSettingsChevron() },
                        onClick = { navController.navigate(Routes.MEDIA_AUDIO_DOWNLOADS) }
                    )
                }
                /*
                 * ── 外接设备（2026-10-21）──
                 *
                 * 两项都在"外接设备"这个话题下，但语义不同，所以各一张卡：
                 * · 控制：能不能用别人的按键控制播放（蓝牙耳机 / 音响 / 车机 / 手表 / 线控）；
                 * · 歌词：要不要把当前这句歌词推到通知的第二行（部分车机与手表会显示）。
                 *
                 * 为什么没有"设备列表 / 当前设备名"：那需要 `BLUETOOTH_CONNECT` 运行时权限，
                 * 而这两项本身都不需要它 —— 为一个只读的设备名多一个权限弹窗不划算。
                 */
                UfiSettingsRowCard {
                    UfiSettingsToggle(
                        title = "外部设备控制",
                        description = "允许外接蓝牙与线控设备控制播放与暂停。" +
                            "关闭后，此类设备的按键不再生效；通知栏与锁屏控制不受影响。",
                        checked = externalControl,
                        icon = Icons.Default.BluetoothAudio,
                        onCheckedChange = {
                            externalControl = it
                            appPrefs.audioExternalControl = it
                        }
                    )
                }

                /*
                 * ── 歌词缓存（2026-10-21）──
                 *
                 * 为什么值得单独一行：取一次歌词要问 core / 拉旁挂 `.lrc` / 扫内嵌标签，
                 * 是最慢的一类请求；缓存到磁盘之后冷启动与切回老歌都是秒出。
                 * 给一个手动清空出口 —— 用户换了 `.lrc` 文件、或者想看"重新拉一遍"的效果时用。
                 */
                UfiSettingsRowCard {
                    UfiSettingsItem(
                        title = "歌词缓存",
                        description = if (lyricCacheCount == 0) {
                            "歌词缓存保存在本机，可减少重复读取。"
                        } else {
                            "已缓存 ${lyricCacheCount} 首；清空后再次播放将重新获取。"
                        },
                        icon = Icons.Default.Lyrics,
                        trailing = {
                            UfiButton(
                                text = "清空",
                                onClick = {
                                    UfiAudioLyrics.clearDiskCache()
                                    lyricCacheCount = UfiAudioLyrics.diskCacheCount()
                                },
                                variant = UfiButtonVariant.Subtle,
                                size = UfiButtonSize.Small
                            )
                        }
                    )
                }
                UfiSettingsRowCard {
                    UfiSettingsToggle(
                        title = "蓝牙歌词",
                        description = "在媒体通知的第二行显示当前歌词。" +
                            "蓝牙协议不含歌词字段，实际显示取决于外接设备的支持情况。",
                        checked = btLyrics,
                        icon = Icons.Default.Lyrics,
                        onCheckedChange = {
                            btLyrics = it
                            appPrefs.audioBtLyrics = it
                        }
                    )
                }
            }

            Spacer(Modifier.height(Spacing.Large))
        }
    }
}
