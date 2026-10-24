package com.ufi_axis.ui.media

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.ufi_axis.data.model.MEDIA_TYPE_VIDEO
import com.ufi_axis.ui.components.common.UfiButton
import com.ufi_axis.ui.components.common.UfiButtonSize
import com.ufi_axis.ui.components.common.UfiButtonVariant
import com.ufi_axis.ui.components.common.UfiConfirmDialog
import com.ufi_axis.ui.components.common.UfiDropdown
import com.ufi_axis.ui.components.common.UfiListEmptyState
import com.ufi_axis.ui.components.common.UfiListRowCard
import com.ufi_axis.ui.components.common.UfiPageBackground
import com.ufi_axis.ui.components.common.UfiPageBackgroundBox
import com.ufi_axis.ui.components.common.UfiScreenScaffold
import com.ufi_axis.ui.components.common.UfiSectionHeader
import com.ufi_axis.ui.components.common.UfiSettingsItem
import com.ufi_axis.ui.components.common.UfiSettingsRowCard
import com.ufi_axis.ui.components.common.UfiSettingsToggle
import com.ufi_axis.ui.navigation.Routes
import com.ufi_axis.ui.navigation.ufiNavigateOnce
import com.ufi_axis.ui.theme.LocalResolvedPalette
import com.ufi_axis.ui.theme.Spacing
import com.ufi_axis.ui.theme.UfiTextStyles
import com.ufi_axis.util.AppPreferences
import com.ufi_axis.viewmodel.MainViewModel

/**
 * 视频设置补的两页（2026-10-21）：**显示与字幕** 与 **最近播放**。
 *
 * ## 为什么补
 * · 播放页早就支持外挂字幕（`setSubtitleConfigurations` + 齿轮里的字幕菜单），但**一项字幕设置都没有**：
 *   字号只能用写死的 1.25 倍、不能去掉/加上底框、有字幕就强制显示（第一条被标成 DEFAULT）。
 *   而这三件事是"看剧"场景里最常被抱怨的。
 * · 海报墙列数写死在 `MediaVideoHome`（`POSTER_COLUMNS = 2`），平板/大屏没法调 —— 与图片页那项同源。
 * · 最近播放只能在「播放与预热」里清空、看不到列表，也没有"点一行接着看"的出口。
 *
 * 两页都只用本机偏好（`AppPreferences`），不碰 core、不新增依赖。
 */

@Composable
fun MediaVideoSettingsDisplayScreen(navController: NavHostController) {
    val context = LocalContext.current
    val prefs = remember(context) { AppPreferences(context) }

    var columns by remember { mutableStateOf(prefs.videoPosterColumns) }
    var subtitleAuto by remember { mutableStateOf(prefs.videoSubtitleAuto) }
    var subtitleSize by remember { mutableStateOf(prefs.videoSubtitleSize) }
    var subtitleBackground by remember { mutableStateOf(prefs.videoSubtitleBackground) }

    UfiScreenScaffold(title = "显示与字幕", navController = navController, showBack = true) { padding ->
        UfiPageBackground(modifier = Modifier.padding(padding)) {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.SettingsCardGap)) {
                UfiSettingsRowCard {
                    UfiSettingsItem(
                        title = "海报墙列数",
                        description = "设置视频首页每行的海报数量；列数增加时单张尺寸相应减小。",
                        trailing = {
                            UfiDropdown(
                                selectedValue = columns,
                                options = listOf(2, 3, 4),
                                onValueSelected = {
                                    columns = it
                                    prefs.videoPosterColumns = it
                                },
                                modifier = Modifier.width(96.dp),
                                unitSuffix = "列"
                            )
                        }
                    )
                }

                UfiSettingsRowCard {
                    UfiSettingsToggle(
                        title = "自动显示字幕",
                        description = "存在外挂字幕时自动选中第一条（同名文件优先）。" +
                            "关闭后需在播放页设置中手动选择。",
                        checked = subtitleAuto,
                        onCheckedChange = {
                            subtitleAuto = it
                            prefs.videoSubtitleAuto = it
                        }
                    )
                }

                UfiSettingsRowCard {
                    UfiSettingsItem(
                        title = "字幕字号",
                        description = "设置字幕字号相对播放器默认值的比例。",
                        trailing = {
                            UfiDropdown(
                                selectedValue = subtitleSize,
                                options = SUBTITLE_SIZES.map { it.first },
                                onValueSelected = {
                                    subtitleSize = it
                                    prefs.videoSubtitleSize = it
                                },
                                modifier = Modifier.width(96.dp),
                                optionLabel = { key ->
                                    SUBTITLE_SIZES.firstOrNull { it.first == key }?.second ?: key
                                }
                            )
                        }
                    )
                }

                UfiSettingsRowCard {
                    UfiSettingsToggle(
                        title = "字幕半透明底",
                        description = "在字幕下方增加半透明底色，提升亮场景下的可读性。默认关闭（白字黑描边已足够清晰）。",
                        checked = subtitleBackground,
                        onCheckedChange = {
                            subtitleBackground = it
                            prefs.videoSubtitleBackground = it
                        }
                    )
                }
            }
            Spacer(Modifier.height(Spacing.Large))
        }
    }
}

/** 字号档位：值（存盘）→ 显示文案。 */
private val SUBTITLE_SIZES = listOf(
    "small" to "小",
    "medium" to "中",
    "large" to "大"
)

@Composable
fun MediaVideoRecentScreen(viewModel: MainViewModel, navController: NavHostController) {
    val context = LocalContext.current
    val prefs = remember(context) { AppPreferences(context) }
    val palette = LocalResolvedPalette.current

    // 每次进页重读：播放随时在发生，缓存一份会在回来时看到旧列表
    var recents by remember { mutableStateOf(prefs.mediaRecentPlays(MEDIA_TYPE_VIDEO)) }
    var confirmClear by remember { mutableStateOf(false) }

    UfiScreenScaffold(title = "最近播放", navController = navController, showBack = true) { padding ->
        UfiPageBackgroundBox(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (recents.isEmpty()) {
                UfiListEmptyState(
                    text = "还没有播放记录。打开一个视频，它就会出现在这里。",
                    icon = Icons.Default.History
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        horizontal = Spacing.CardHorizontalMargin,
                        vertical = Spacing.Large
                    ),
                    verticalArrangement = Arrangement.spacedBy(Spacing.SettingsCardGap)
                ) {
                    item {
                        UfiSectionHeader(
                            title = "最近 ${recents.size} 条",
                            trailing = {
                                UfiButton(
                                    text = "清空",
                                    onClick = { confirmClear = true },
                                    variant = UfiButtonVariant.Subtle,
                                    size = UfiButtonSize.Small
                                )
                            }
                        )
                    }
                    items(recents.size) { index ->
                        val (path, name, _) = recents[index]
                        UfiListRowCard(
                            title = name.ifBlank { path.substringAfterLast('/') },
                            subtitle = path,
                            // 点一行 = 直接播这个视频（与首页那条横向卡同一去处）
                            onClick = { navController.ufiNavigateOnce(mediaRouteOf("media/video", path)) }
                        )
                    }
                    item {
                        Text(
                            text = "记录只在本机，最多保留 6 条。",
                            style = UfiTextStyles.caption,
                            color = palette.textSecondary,
                            modifier = Modifier.padding(top = Spacing.Small, start = Spacing.Small)
                        )
                    }
                }
            }
        }
    }

    UfiConfirmDialog(
        visible = confirmClear,
        title = "清空最近播放？",
        text = "只清这份记录，不影响播放进度与文件本身。",
        confirmText = "清空",
        onConfirm = {
            confirmClear = false
            prefs.clearMediaRecentPlays(MEDIA_TYPE_VIDEO)
            recents = emptyList()
        },
        onDismiss = { confirmClear = false }
    )
}

