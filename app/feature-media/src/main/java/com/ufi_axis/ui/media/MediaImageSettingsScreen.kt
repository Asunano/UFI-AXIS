package com.ufi_axis.ui.media

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import coil3.SingletonImageLoader
import com.ufi_axis.data.model.MEDIA_TYPE_IMAGE
import com.ufi_axis.ui.components.common.UfiButton
import com.ufi_axis.ui.components.common.UfiButtonSize
import com.ufi_axis.ui.components.common.UfiButtonVariant
import com.ufi_axis.ui.components.common.UfiDropdown
import com.ufi_axis.ui.components.common.UfiPageBackground
import com.ufi_axis.ui.components.common.UfiScreenScaffold
import com.ufi_axis.ui.components.common.UfiSettingsItem
import com.ufi_axis.ui.components.common.UfiSettingsRowCard
import com.ufi_axis.ui.theme.Spacing
import com.ufi_axis.util.AppPreferences
import com.ufi_axis.viewmodel.MainViewModel
import kotlinx.coroutines.launch

/**
 * 图片页设置（右上角齿轮进来）。2026-09-20 新增，2026-10-21 补齐缓存、显示与下载入口。
 *
 * ## 现在有四件事（按"改一次立刻能看出效果"的顺序）
 * · **扫描范围**：扫描目录 + 重新扫描（共用件 [MediaScanScopeSection]，与音乐/视频逐字一致）；
 * · **网格列数**：时间轴每行 2~5 格（[UfiDropdown]，与外观页那些数值字段同一个控件）；
 * · **缩略图缓存**：清空设备侧缩略图 + 本机图片加载缓存（两层都要清，理由见那一行）；
 * · **下载任务**：队列 + 历史入口，描述里带条数，单独一张卡。
 *
 * ## 2026-10-21 按用户意见删掉的两项
 * · **按日期分段开关**：按时间排序就分段本来就是设计如此（按名称/大小排序时段头毫无意义），
 *   做成开关等于让用户以为"排序与分段的绑定"可以关掉；真实判据只有 `sort == date`。
 * · **下载落点目录**：只读说明，不能改也没有任何操作 —— 一行噪音。落点规则由下载任务的
 *   toast 与页内说明承担。
 *
 * ## 为什么没有「排除列表」
 * 音乐那边有「从音乐库移除」+ 管理页，但 core 的排除名单**只对音频生效**
 * （`MediaRoutes.kt` 里 `if (kind == Kind.AUDIO) exclusions?.excluded()`），
 * 图片既没有"移出图库"这个动作，名单也不会过滤图片 —— 加一行入口只会得到一个永远空的页面。
 *
 * ## 版式
 * 一项一张 `UfiSettingsRowCard`、间距走 `Spacing.SettingsCardGap`，与视频/音乐设置页同构。
 * [UfiPageBackground] 自带滚动，页面里不要再套 `verticalScroll`。
 */
@Composable
fun MediaImageSettingsScreen(
    viewModel: MainViewModel,
    navController: NavHostController
) {
    val context = LocalContext.current
    val media = viewModel.media
    val scope = rememberCoroutineScope()

    // 进页先拿一次状态：扫描目录存在 core，本页显示的是设备侧的真实配置而不是本机记忆
    LaunchedEffect(Unit) { media.loadStatus() }
    // 下载任务那一行的条数要用队列与历史

    // 网格列数（与 MediaImageScreen 读的是同一个键）
    val appPrefs = remember { AppPreferences(context) }
    var columns by remember { mutableStateOf(appPrefs.mediaImageColumns()) }

    /** 清缓存要打一次网络（设备侧那层），按钮得有在忙的状态，否则会被连点。 */
    var clearingCache by remember { mutableStateOf(false) }

    UfiScreenScaffold(title = "图片设置", navController = navController, showBack = true) { padding ->
        // UfiPageBackground 自带 verticalScroll，页面里不能再套一层滚动容器
        UfiPageBackground(modifier = Modifier.padding(padding)) {
            // 卡间距与网络设置页一致（10dp / Spacing.SettingsCardGap）：共用件一次吐两张单行卡，
            // 它们都是这层 Column 的直接子项，间距由这里统一给
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.SettingsCardGap)) {
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

                // ── 显示：网格列数（下拉单选的取值/回调都与下面那些设置项同构）──
                UfiSettingsRowCard {
                    UfiSettingsItem(
                        title = "网格列数",
                        description = "设置时间轴每行的列数；列数减少时缩略图相应增大。",
                        icon = Icons.Default.GridView,
                        trailing = {
                            UfiDropdown(
                                selectedValue = columns,
                                options = IMAGE_COLUMN_OPTIONS,
                                onValueSelected = { picked ->
                                    columns = picked
                                    appPrefs.setMediaImageColumns(picked)
                                },
                                modifier = Modifier.width(104.dp),
                                unitSuffix = "列"
                            )
                        }
                    )
                }

                // ── 缩略图缓存 ──
                UfiSettingsRowCard {
                    UfiSettingsItem(
                        title = "缩略图缓存",
                        description = "清除设备端与本机的缩略图缓存；清除后下次浏览将重新生成。",
                        icon = Icons.Default.Image,
                        trailing = {
                            UfiButton(
                                text = if (clearingCache) "清理中…" else "清空",
                                onClick = {
                                    if (clearingCache) return@UfiButton
                                    clearingCache = true
                                    scope.launch {
                                        media.clearRemoteThumbnails(MEDIA_TYPE_IMAGE)
                                        /*
                                         * 本机这一层也要清：Coil 的缓存键按 URL 命中，而缩略图 URL
                                         * 只含 (type,id) 且 core 给 max-age=86400 —— 只清设备侧
                                         * 的话，一天内本机还会继续拿旧图，用户会以为没生效。
                                         */
                                        SingletonImageLoader.get(context).let { loader ->
                                            loader.memoryCache?.clear()
                                            loader.diskCache?.clear()
                                        }
                                        clearingCache = false
                                    }
                                },
                                variant = UfiButtonVariant.Subtle,
                                size = UfiButtonSize.Small
                            )
                        }
                    )
                }

            }

            Spacer(Modifier.height(Spacing.Large))
        }
    }
}

/**
 * 网格列数可选档位。
 *
 * 下限 **3**（2026-10-21 去掉 2 列：手机上两列时单张过大、一屏只放得下四张，反而更难挑）；
 * 上限 5（6 英寸屏上 5 列时每格不到 60dp，缩略图会糊到认不出）。
 */
private val IMAGE_COLUMN_OPTIONS = listOf(3, 4, 5)
