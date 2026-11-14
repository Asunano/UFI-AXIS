package com.ufi_axis.ui.media

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import com.ufi_axis.data.model.MEDIA_TYPE_IMAGE
import com.ufi_axis.ui.components.common.UfiPageBackground
import com.ufi_axis.ui.components.common.UfiScreenScaffold
import com.ufi_axis.ui.theme.Spacing
import com.ufi_axis.viewmodel.MainViewModel

/**
 * 图片页设置（右上角齿轮进来）。2026-09-20 新增。
 *
 * ## 为什么只有"扫描范围"一组
 * 图片不需要抽帧（缩略图由系统媒体库直接给）、没有下载队列、也没有播放相关偏好，
 * 视频设置页里那几组对它全是空的。这一页只收「配一次就不动」的东西 ——
 * 原来图片页工具条上那颗「目录」就此撤掉（`showDirAction = false`），
 * 与音乐页对齐：同一件配置只留一个入口。
 *
 * ## 这一组走共用件
 * 曾经在这里内联过一份（当时共用件还没落地）。共用件 [MediaScanScopeSection] 到位后即换成
 * 一次调用：三页的「扫描目录 / 重新扫描」必须文案、图标、描述逐字一致，
 * 而两份复制品的必然结局是"改了一页忘了另一页"——这正是上一轮返工的起因。
 * 弹窗、写回、失败提示全部在共用件里，这里只给类型和收尾动作。
 */
@Composable
fun MediaImageSettingsScreen(
    viewModel: MainViewModel,
    navController: NavHostController
) {
    val media = viewModel.media

    // 进页先拿一次状态：扫描目录存在 core，本页显示的是设备侧的真实配置而不是本机记忆
    LaunchedEffect(Unit) { media.loadStatus() }

    UfiScreenScaffold(title = "图片设置", navController = navController, showBack = true) { padding ->
        // UfiPageBackground 自带 verticalScroll，页面里不能再套一层滚动容器
        UfiPageBackground(modifier = Modifier.padding(padding)) {
            MediaScanScopeSection(
                type = MEDIA_TYPE_IMAGE,
                typeLabel = mediaTypeLabel(MEDIA_TYPE_IMAGE),
                viewModel = viewModel,
                onDirsChanged = {
                    /*
                     * 文件夹那一栏也要重列：它显示的是扫描根下的目录树，换了扫描目录之后
                     * 原来那棵树连根都不在了。只刷时间轴会留下一份看着正常、实际已过期的目录视图。
                     */
                    media.browse(MEDIA_TYPE_IMAGE, null, force = true)
                }
            )

            Spacer(Modifier.height(Spacing.Large))
        }
    }
}
