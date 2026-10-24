package com.ufi_axis.ui.media

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import com.ufi_axis.data.model.MEDIA_TYPE_VIDEO
import com.ufi_axis.ui.components.common.UfiPageBackground
import com.ufi_axis.ui.components.common.UfiScreenScaffold
import com.ufi_axis.ui.theme.Spacing
import com.ufi_axis.viewmodel.MainViewModel

/**
 * 视频设置 → **扫描范围**（2026-10-14 从 `MediaVideoSettingsScreen` 拆出，内容原样搬运）。
 *
 * 内容就是共用的 [MediaScanScopeSection]（扫描目录 + 重新扫描两张卡）。它与音乐设置页
 * （`MediaAudioSettingsScreen`）是同一个组件、同一套版式，只有提交目录后的收尾动作不同：
 * 视频页要重拉**文件夹视图**（目录变了，文件夹树与首层列表都不再成立），音乐页要作废
 * 三个维度的分组缓存 —— 两者各自都对，**不要统一**。
 *
 * ## 本页自己的数据加载
 * `loadStatus()`：扫描目录存在 core（按类型各存一份），本页显示的是设备侧的真实配置
 * 而不是本机记忆。少了它，「扫描目录」那一行永远是"整个媒体库"。
 */
@Composable
fun MediaVideoSettingsScanScreen(
    viewModel: MainViewModel,
    navController: NavHostController
) {
    val media = viewModel.media

    LaunchedEffect(Unit) { media.loadStatus() }

    UfiScreenScaffold(title = "扫描范围", navController = navController, showBack = true) { padding ->
        UfiPageBackground(modifier = Modifier.padding(padding)) {
            // 共用件一次吐两张单行卡，它们都是这层 Column 的直接子项，间距由这里统一给
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.SettingsCardGap)) {
                MediaScanScopeSection(
                    type = MEDIA_TYPE_VIDEO,
                    typeLabel = mediaTypeLabel(MEDIA_TYPE_VIDEO),
                    viewModel = viewModel,
                    onDirsChanged = {
                        // 视频页的收尾是重拉**文件夹视图**：目录变了，文件夹树与首层列表都不再成立。
                        media.browse(MEDIA_TYPE_VIDEO, null, force = true)
                    }
                )
            }

            Spacer(Modifier.height(Spacing.Large))
        }
    }
}
