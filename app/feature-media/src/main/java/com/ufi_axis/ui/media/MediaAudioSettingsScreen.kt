package com.ufi_axis.ui.media

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import com.ufi_axis.data.model.MEDIA_TYPE_AUDIO
import com.ufi_axis.ui.components.common.UfiPageBackground
import com.ufi_axis.ui.components.common.UfiScreenScaffold
import com.ufi_axis.ui.components.common.UfiSettingsChevron
import com.ufi_axis.ui.components.common.UfiSettingsItem
import com.ufi_axis.ui.components.common.UfiSettingsRowCard
import com.ufi_axis.ui.navigation.Routes
import com.ufi_axis.ui.theme.Spacing
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
 * 同一个 App 里这一页就是"看着不太一样"。[UfiPageBackground] 自带滚动与这两个量，
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

    UfiScreenScaffold(title = "音乐设置", navController = navController, showBack = true) { padding ->
        UfiPageBackground(modifier = Modifier.padding(padding)) {
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

            Spacer(Modifier.height(Spacing.Large))
        }
    }
}
