package com.ufi_axis.ui.screens

import androidx.compose.foundation.background
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
import com.ufi_axis.ui.theme.UfiCardDefaults
import com.ufi_axis.ui.theme.UfiTextStyles
import com.ufi_axis.ui.theme.UfiWeight
import com.ufi_axis.util.FormatUtils
import com.ufi_axis.viewmodel.MainViewModel
import com.ufi_axis.viewmodel.module.WeatherModule


/**
 * 今日天气（设置 → 小功能 → 今日天气，2026-09-18）。
 *
 * 本页的内容原先直接躺在 [UiExtrasSettingsScreen] 里。第二个小挂件（今日诗词）落地后，
 * 那一页必须同时装两套「开关 + 数据预览 + 刷新」，一屏放不下、也分不清哪个开关管哪个挂件 ——
 * 于是「小功能」降级成入口页，天气与诗词各自独立成页。
 *
 * 独立成页的另一个好处：天气这套东西自己就有四件事要配（开关 / 城市 / 单位 / 预览），
 * 其中城市还带一个搜索弹窗，本身已经够一页的分量。
 *
 * 真源在 core 的 `/api/weather/config`，本页只是它的编辑界面 —— 手机换一台、
 * 或用 web 面板看，城市都是同一个。没设城市时"开天气"先弹城市选择（开了也没数据可显示）。
 *
 * ## 版式（2026-09-22）
 * 读数摘要卡 [WeatherSummary] 排最上，其下三项设置各一张卡。原来是"一张三项卡 + 一张
 * 八行读数卡"：读数在下面、要滚过设置才看得到，而这一页的用途恰恰是核对读数。
 * 设置项拆成一项一卡，也让本页与媒体设置页的卡片节奏一致（全站不再用卡外区块标题）。
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
                // 摘要卡排在最上：这一页存在的理由就是"确认挂件显示得对不对"，
                // 那就让进页第一眼看到读数本身，而不是三个开关。没数据时整卡不出现
                // （占位空卡只会让人以为坏了），设置项照常可用。
                state.now?.takeIf { it.configured }?.let { now ->
                    UfiSettingsRowCard(contentPadding = PaddingValues(16.dp)) {
                        WeatherSummary(
                            now = now,
                            loading = state.loading,
                            onRefresh = { viewModel.weather.refresh(force = true) }
                        )
                    }
                }

                UfiSettingsRowCard {
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
                }

                UfiSettingsRowCard {
                    UfiSettingsItem(
                        icon = Icons.Default.LocationCity,
                        title = "城市",
                        description = state.config.city.ifBlank { "未设置" },
                        onClick = { showCityPicker = true },
                        trailing = { UfiSettingsChevron() }
                    )
                }

                UfiSettingsRowCard {
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

/**
 * 摘要卡内容：大字温度 + 手绘图标 + 一排次要读数 chip + 刷新。
 *
 * ## 为什么把读数放最上、还做成这个形态
 * 原来是「当前读数」卡里 8 行 `label —— value`，排在两张设置卡下面。两个问题：
 * 想确认挂件对不对得先滚过设置项；8 行竖排把"立即刷新"顶得很低。
 * 现在主读数（温度 / 天气 / 城市）用大小字号分层一眼可读，其余压成一行 chip，
 * 整卡高度不到原来一半。
 *
 * 图标直接复用标题栏那套手绘 [WeatherGlyph]（同一个 `weather_code` → 同一张图），
 * 这一页就是它的"预览"，两处不一致才是 bug。尺寸给 44dp 而不是标题栏的
 * [WEATHER_GLYPH_SIZE]：这里是主视觉，与 28sp 的温度同排。
 *
 * 刷新做成右下角小按钮而不是通栏主按钮：它是这张卡的次要操作，
 * 通栏会抢走大字温度的视觉重心。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WeatherSummary(
    now: com.ufi_axis.data.model.WeatherNowResponse,
    loading: Boolean,
    onRefresh: () -> Unit
) {
    val palette = LocalResolvedPalette.current
    val unitSuffix = if (now.unit == "fahrenheit") "℉" else "℃"

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f)) {
            Text(
                text = "${"%.1f".format(now.temperature)}°",
                style = UfiTextStyles.metricValue,
                color = palette.textPrimary
            )
            if (now.description.isNotBlank()) {
                Text(
                    text = now.description,
                    style = UfiTextStyles.bodyEmphasis,
                    color = palette.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                // 更新时刻用相对时间：用户关心的是"这份数据新不新"，不是几点几分。
                text = listOfNotNull(
                    now.city.ifBlank { null },
                    now.updated_at.takeIf { it > 0 }?.let { FormatUtils.formatRelativeTime(it) }
                ).joinToString(" · ").ifBlank { "—" },
                style = UfiTextStyles.caption,
                color = palette.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        WeatherGlyph(
            weatherCode = now.weather_code,
            isDay = now.is_day,
            size = 44.dp,
            modifier = Modifier.padding(start = Spacing.Medium)
        )
    }

    Spacer(Modifier.height(Spacing.Large))

    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.Small),
        verticalArrangement = Arrangement.spacedBy(Spacing.Small)
    ) {
        SummaryChip("体感 ${"%.1f".format(now.apparent_temperature)}$unitSuffix")
        SummaryChip("${now.temp_min.toInt()}° / ${now.temp_max.toInt()}°")
        SummaryChip("湿度 ${now.humidity}%")
        SummaryChip("风 ${"%.1f".format(now.wind_speed)} km/h")
        if (now.sunrise.isNotBlank()) {
            SummaryChip(
                "${now.sunrise.substringAfter('T')} · ${now.sunset.substringAfter('T')}"
            )
        }
    }

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        UfiButton(
            text = if (loading) "刷新中…" else "立即刷新",
            size = UfiButtonSize.Small,
            variant = UfiButtonVariant.Subtle,
            enabled = !loading,
            onClick = onRefresh
        )
    }
}

/** 摘要卡里的次要读数标签。只读、不可点，所以不用 `UfiActionChipRow` 那套带点击的 chip。 */
@Composable
private fun SummaryChip(text: String) {
    val palette = LocalResolvedPalette.current
    Text(
        text = text,
        style = UfiTextStyles.caption,
        color = palette.textSecondary,
        modifier = Modifier
            .background(palette.accent.copy(alpha = 0.08f), UfiCardDefaults.chipShape)
            .padding(horizontal = 9.dp, vertical = 4.dp)
    )
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
