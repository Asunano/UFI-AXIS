package com.ufi_axis.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.ufi_axis.ui.components.common.*
import com.ufi_axis.ui.navigation.Routes
import com.ufi_axis.ui.theme.Spacing
import com.ufi_axis.viewmodel.MainViewModel
import com.ufi_axis.ui.navigation.ufiNavigateOnce

/**
 * 界面小功能（设置 → 界面小功能，2026-09-17；2026-09-18 入口 + 开关合一）。
 *
 * 收「不属于服务器/网络设置、也不是每天都要开」的零散功能。目前四项：
 * 今日天气、今日诗词（标题栏挂件）、DLNA 服务、话费账单。
 *
 * 之所以不塞进「外观」：那页管的是主题与配色（纯本机、无网络依赖），
 * 而这里的东西要连 core 拉数据、有刷新周期与配额，两类混在一页会越攒越乱。
 *
 * 之所以不留在工具页：工具页是"打开就用"的入口（文件/媒体/下载/短信），
 * 这四项都是**隔几天看一眼**或**设一次就不管**的，混在一起只会稀释工具页的密度。
 * 判定口径很简单 —— "需不需要每天从工具页顺手点开"。
 *
 * 每行两个热区：行主体点进二级页（城市 / 单位 / 预览 / 立即刷新都在那儿），
 * 行尾 [UfiSwitch] 直接开关 —— 只想开一下不必先进页面。
 * 天气还有一条例外：没设过城市时"开"是开不出东西的，那一下改成把人送到天气页选城市。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UiExtrasSettingsScreen(viewModel: MainViewModel, navController: NavHostController) {
    val weather by viewModel.weather.state.collectAsState()
    val poetry by viewModel.poetry.state.collectAsState()

    // 两份配置都读一遍：行上的开关态与状态字要靠它们，否则第一次进来两行都是关的。
    LaunchedEffect(Unit) {
        viewModel.weather.loadConfig()
        viewModel.poetry.loadConfig()
    }

    // 城市没设过（经纬度都是 0）时天气拿不到任何数据，开关也不能显示成开。
    val citySet = weather.config.latitude != 0.0 || weather.config.longitude != 0.0

    UfiScreenScaffold(title = "小功能", navController = navController, showBack = true) { padding ->
        UfiPageBackground(modifier = Modifier.padding(padding)) {
            // 注意：UfiPageBackground 内部已经是 Column + verticalScroll，这里**不能**再套一层
            // 滚动容器 —— 嵌套滚动会让内层收到 Infinity 高度约束，直接崩（2026-09-17 已踩）。
            Column(
                Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                UfiSettingsRowCard {
                    UfiSettingsItem(
                        icon = Icons.Default.WbSunny,
                        title = "今日天气",
                        description = if (citySet) {
                            weather.config.city.ifBlank { "已选城市" }
                        } else {
                            "未设置城市"
                        },
                        onClick = { navController.ufiNavigateOnce(Routes.DETAIL_WEATHER) },
                        trailing = {
                            UfiSwitch(
                                checked = weather.config.enabled && citySet,
                                onCheckedChange = { want ->
                                    // 没城市就没数据：这一下不置开，先把人送去选城市。
                                    if (want && !citySet) {
                                        navController.ufiNavigateOnce(Routes.DETAIL_WEATHER)
                                    } else {
                                        viewModel.weather.setEnabled(want)
                                    }
                                }
                            )
                        }
                    )
                }

                UfiSettingsRowCard {
                    UfiSettingsItem(
                        icon = Icons.Default.AutoStories,
                        title = "今日诗词",
                        description = "显示在首页标题栏",
                        onClick = { navController.ufiNavigateOnce(Routes.DETAIL_POETRY) },
                        trailing = {
                            // 诗词不需要任何配置，开了就有词，直接置开。
                            UfiSwitch(
                                checked = poetry.config.enabled,
                                onCheckedChange = { viewModel.poetry.setEnabled(it) }
                            )
                        }
                    )
                }

                // DLNA 服务（2026-10-20 从设置主列表搬到本页，用户要求"小功能"里）：
                // 它和天气/诗词一样是设备侧的一项附加能力，不属于服务器/网络设置。
                UfiSettingsRowCard {
                    UfiSettingsItem(
                        icon = Icons.Default.Cast,
                        title = "DLNA 服务",
                        description = "局域网媒体共享 · 电视直接播放",
                        onClick = { navController.ufiNavigateOnce(Routes.DETAIL_DLNA) },
                        trailing = { UfiSettingsChevron() }
                    )
                }

                // 话费账单（2026-11-05 从工具页搬来，用户要求）：
                // 与 DLNA 同一个理由 —— 隔几天看一眼的独立入口，不是"从工具页顺手打开"的东西。
                UfiSettingsRowCard {
                    UfiSettingsItem(
                        icon = Icons.Default.ReceiptLong,
                        title = "话费账单",
                        description = "余额 · 流量 · 到期",
                        onClick = { navController.ufiNavigateOnce(Routes.DETAIL_BILL) },
                        trailing = { UfiSettingsChevron() }
                    )
                }

                Spacer(Modifier.height(Spacing.Large))
            }
        }
    }
}
