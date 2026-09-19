package com.ufi_axis.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationCity
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.ufi_axis.data.model.WeatherCity
import com.ufi_axis.ui.components.common.*
import com.ufi_axis.ui.theme.LocalResolvedPalette
import com.ufi_axis.ui.theme.Spacing
import com.ufi_axis.ui.theme.UfiTextStyles
import com.ufi_axis.ui.theme.UfiWeight
import com.ufi_axis.viewmodel.MainViewModel
import com.ufi_axis.viewmodel.module.WeatherModule


/**
 * 今日天气（设置 → 界面小功能 → 今日天气，2026-09-18）。
 *
 * 本页的内容原先直接躺在 [UiExtrasSettingsScreen] 里。第二个小挂件（今日诗词）落地后，
 * 那一页必须同时装两套「开关 + 数据预览 + 刷新」，一屏放不下、也分不清哪个开关管哪个挂件 ——
 * 于是「界面小功能」降级成入口页，天气与诗词各自独立成页。
 *
 * 独立成页的另一个好处：天气这套东西自己就有四件事要配（开关 / 城市 / 单位 / 预览），
 * 其中城市还带一个搜索弹窗，本身已经够一页的分量。
 *
 * 真源在 core 的 `/api/weather/config`，本页只是它的编辑界面 —— 手机换一台、
 * 或用 web 面板看，城市都是同一个。没设城市时"开天气"先弹城市选择（开了也没数据可显示）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WeatherSettingsScreen(viewModel: MainViewModel, navController: NavHostController) {
    val palette = LocalResolvedPalette.current
    val state by viewModel.weather.state.collectAsState()
    var showCityPicker by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { viewModel.weather.loadConfig() }

    val citySet = state.config.latitude != 0.0 || state.config.longitude != 0.0

    UfiScreenScaffold(title = "今日天气", navController = navController, showBack = true) { padding ->
        UfiPageBackground(modifier = Modifier.padding(padding)) {
            // 注意：UfiPageBackground 内部已经是 Column + verticalScroll，这里**不能**再套一层
            // 滚动容器 —— 嵌套滚动会让内层收到 Infinity 高度约束，直接崩（2026-09-17 已踩）。
            Column(
                Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                UfiSettingsRowCard(contentPadding = PaddingValues(16.dp)) {
                    // 没城市就没数据：这时"开"改成先弹城市选择，不做成一个按了没反应的开关。
                    UfiSettingsToggle(
                        icon = Icons.Default.WbSunny,
                        title = "显示天气",
                        description = if (citySet) null else "先选择城市",
                        checked = state.config.enabled && citySet,
                        onCheckedChange = { want ->
                            if (want && !citySet) {
                                showCityPicker = true
                            } else {
                                viewModel.weather.setEnabled(want)
                            }
                        }
                    )

                    UfiDivider()

                    UfiSettingsItem(
                        icon = Icons.Default.LocationCity,
                        title = "城市",
                        description = state.config.city.ifBlank { "未设置" },
                        onClick = { showCityPicker = true },
                        trailing = { UfiSettingsChevron() }
                    )

                    UfiDivider()

                    // 单位只有两档，点一下就切；为两个值单开一个选择弹窗不划算。
                    val fahrenheit = state.config.unit == "fahrenheit"
                    UfiSettingsItem(
                        icon = Icons.Default.Thermostat,
                        title = "温度单位",
                        description = if (fahrenheit) "华氏度 ℉" else "摄氏度 ℃",
                        onClick = {
                            viewModel.weather.setUnit(if (fahrenheit) "celsius" else "fahrenheit")
                        },
                        trailing = {
                            Text(
                                text = if (fahrenheit) "℉" else "℃",
                                style = UfiTextStyles.body.copy(fontWeight = UfiWeight.Emphasis),
                                color = palette.accent
                            )
                        }
                    )
                }

                // 预览：拿到数据就显示一份完整读数，省得为了确认设置对不对而反复退回首页。
                state.now?.takeIf { it.configured }?.let { now ->
                    UfiSettingsRowCard(contentPadding = PaddingValues(16.dp)) {
                        UfiSectionHeader(title = "当前读数")
                        PreviewRow("城市", now.city.ifBlank { "—" })
                        PreviewRow("天气", now.description)
                        val unitSuffix = if (now.unit == "fahrenheit") "℉" else "℃"
                        PreviewRow("温度", "${"%.1f".format(now.temperature)}$unitSuffix")
                        PreviewRow("体感", "${"%.1f".format(now.apparent_temperature)}$unitSuffix")
                        PreviewRow(
                            "今日",
                            "${now.temp_min.toInt()}° / ${now.temp_max.toInt()}°"
                        )
                        PreviewRow("湿度", "${now.humidity}%")
                        PreviewRow("风速", "${"%.1f".format(now.wind_speed)} km/h")
                        if (now.sunrise.isNotBlank()) {
                            PreviewRow(
                                "日出 / 日落",
                                "${now.sunrise.substringAfter('T')} / ${now.sunset.substringAfter('T')}"
                            )
                        }
                        Spacer(Modifier.height(Spacing.Medium))
                        UfiButtonRow {
                            UfiButton(
                                text = if (state.loading) "刷新中…" else "立即刷新",
                                size = UfiButtonSize.Small,
                                enabled = !state.loading,
                                onClick = { viewModel.weather.refresh(force = true) }
                            )
                        }
                    }
                }

                state.errorMessage?.let { msg ->
                    UfiSettingsRowCard(contentPadding = PaddingValues(16.dp)) {
                        Text(msg, style = UfiTextStyles.caption, color = palette.error)
                    }
                }

                Spacer(Modifier.height(Spacing.Large))
            }
        }
    }

    // 直接把状态传给弹窗（不再用 `if (showCityPicker) { ... }` 硬挂载）：
    // shell 的离场时序要先把 backdrop 播回清晰再卸载窗口，硬挂载会当帧把窗口拆掉。
    WeatherCityPickerDialog(
        visible = showCityPicker,
        onDismiss = { showCityPicker = false },
        onSearch = { viewModel.weather.searchCity(it) },
        // 只管"选中之后做什么"：关弹窗由 UfiSearchDialog 给的 dismiss 负责。
        onPick = { city -> viewModel.weather.setCity(city) }
    )
}

@Composable
private fun PreviewRow(label: String, value: String) {
    val palette = LocalResolvedPalette.current
    Row(
        Modifier.fillMaxWidth().padding(horizontal = Spacing.InnerPadding, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, modifier = Modifier.weight(1f), style = UfiTextStyles.body)
        Text(
            value,
            style = UfiTextStyles.body.copy(fontWeight = UfiWeight.Emphasis),
            color = palette.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * 城市选择：输入词 → 点搜索 → 选一条。
 *
 * 2026-09-19：搜索框 / 搜索按钮 / 结果与空态的状态机全部搬到公共组件 [UfiSearchDialog]，
 * 这里只剩"数据源是 core 的地理编码、每行长什么样、选中之后做什么"三件事 ——
 * 也就是只有天气才知道的部分。
 */
@Composable
private fun WeatherCityPickerDialog(
    visible: Boolean,
    onDismiss: () -> Unit,
    onSearch: suspend (String) -> List<WeatherCity>,
    onPick: (WeatherCity) -> Unit
) {
    val palette = LocalResolvedPalette.current
    UfiSearchDialog(
        visible = visible,
        onDismiss = onDismiss,
        title = "选择城市",
        placeholder = "中文或英文均可，如 北京 / Tokyo",
        onSearch = onSearch
    ) { city, dismiss ->
        Row(
            Modifier
                .fillMaxWidth()
                .clickable {
                    onPick(city)
                    dismiss()
                }
                // 间距统一到 UfiDialogBody（12dp）：横向内距只由弹窗外壳提供，这里不再叠
                .padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    WeatherModule.displayName(city),
                    style = UfiTextStyles.body,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                // 同名城市不少（全球有十几个 Springfield），坐标是唯一能分辨的东西
                Text(
                    "%.3f, %.3f".format(city.latitude, city.longitude),
                    style = UfiTextStyles.caption,
                    color = palette.textSecondary
                )
            }
        }
    }
}
