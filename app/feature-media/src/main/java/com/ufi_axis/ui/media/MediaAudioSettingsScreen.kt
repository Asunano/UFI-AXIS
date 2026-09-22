package com.ufi_axis.ui.media

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import com.ufi_axis.data.model.MEDIA_TYPE_AUDIO
import com.ufi_axis.ui.components.common.UfiPageBackground
import com.ufi_axis.ui.components.common.UfiScreenScaffold
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

    // 进页先拿一次状态：扫描目录存在 core，本页显示的是设备侧的真实配置而不是本机记忆
    LaunchedEffect(Unit) { media.loadStatus() }

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

            Spacer(Modifier.height(Spacing.Large))
        }
    }
}
