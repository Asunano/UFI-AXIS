package com.ufi_axis.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.FormatQuote
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.ufi_axis.ui.components.common.*
import com.ufi_axis.ui.theme.LocalResolvedPalette
import com.ufi_axis.ui.theme.Spacing
import com.ufi_axis.ui.theme.UfiTextStyles
import com.ufi_axis.viewmodel.MainViewModel

/**
 * 今日诗词（设置 → 界面小功能 → 今日诗词，2026-09-18）。
 *
 * 与 [WeatherSettingsScreen] 并列的第二个「标题栏小挂件」。独立成页而不是和天气挤在
 * 「界面小功能」那一屏：两者各自都有开关 + 一张内容预览卡 + 一个刷新按钮，放在一页里
 * 会出现两个长得一样的「立即刷新」和两条「当前…」卡片，用户得先认哪个管哪个。
 *
 * 本页只是 core `/api/poetry/config` 的编辑界面，**不做任何"挑诗"的逻辑** ——
 * 选哪句由上游按地区、天气、时辰与农历自动匹配（见 [com.ufi_axis.viewmodel.module.PoetryModule]），
 * 命中的标签就是「当前诗句」里那行「推荐依据」。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PoetrySettingsScreen(viewModel: MainViewModel, navController: NavHostController) {
    val palette = LocalResolvedPalette.current
    val state by viewModel.poetry.state.collectAsState()

    LaunchedEffect(Unit) { viewModel.poetry.loadConfig() }

    UfiScreenScaffold(title = "今日诗词", navController = navController, showBack = true) { padding ->
        UfiPageBackground(modifier = Modifier.padding(padding)) {
            // 注意：UfiPageBackground 内部已经是 Column + verticalScroll，这里**不能**再套一层
            // 滚动容器 —— 嵌套滚动会让内层收到 Infinity 高度约束，直接崩（2026-09-17 已踩）。
            Column(
                Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                UfiSettingsRowCard(contentPadding = PaddingValues(16.dp)) {
                    UfiSettingsToggle(
                        icon = Icons.Default.AutoStories,
                        title = "显示今日诗词",
                        checked = state.config.enabled,
                        onCheckedChange = { viewModel.poetry.setEnabled(it) }
                    )

                    UfiDivider()

                    UfiSettingsToggle(
                        icon = Icons.Default.FormatQuote,
                        title = "显示出处",
                        checked = state.config.show_origin,
                        onCheckedChange = { viewModel.poetry.setShowOrigin(it) }
                    )
                }

                // 当前诗句：整首、出处、推荐依据、翻译都摆出来。
                // 标题栏那行小字只放得下一句，想读全篇就得来这里。
                UfiSettingsRowCard(contentPadding = PaddingValues(16.dp)) {
                    UfiSectionHeader(title = "当前诗句")
                    val poem = state.poem
                    if (poem == null || poem.content.isBlank()) {
                        Text(
                            text = if (state.loading) "正在取诗句…" else "还没有取到诗句",
                            style = UfiTextStyles.caption,
                            color = palette.textSecondary
                        )
                    } else {
                        Text(
                            text = poem.content,
                            style = UfiTextStyles.bodyLeadStrong,
                            color = palette.textPrimary
                        )
                        if (poem.originLine.isNotBlank()) {
                            Spacer(Modifier.height(Spacing.Small))
                            Text(
                                text = poem.originLine,
                                style = UfiTextStyles.caption,
                                color = palette.textSecondary
                            )
                        }
                        if (poem.match_tags.isNotEmpty()) {
                            Spacer(Modifier.height(Spacing.Small))
                            Text(
                                text = "推荐依据：" + poem.match_tags.joinToString(" · "),
                                style = UfiTextStyles.caption,
                                color = palette.accent
                            )
                        }
                        if (poem.full_content.isNotEmpty()) {
                            Spacer(Modifier.height(Spacing.Medium))
                            UfiDivider()
                            Spacer(Modifier.height(Spacing.Medium))
                            Text("全篇", style = UfiTextStyles.captionEmphasis, color = palette.textSecondary)
                            Spacer(Modifier.height(4.dp))
                            poem.full_content.forEach { line ->
                                Text(line, style = UfiTextStyles.body, color = palette.textPrimary)
                            }
                        }
                        if (poem.translate.isNotEmpty()) {
                            Spacer(Modifier.height(Spacing.Medium))
                            UfiDivider()
                            Spacer(Modifier.height(Spacing.Medium))
                            Text("翻译", style = UfiTextStyles.captionEmphasis, color = palette.textSecondary)
                            Spacer(Modifier.height(4.dp))
                            poem.translate.forEach { line ->
                                Text(line, style = UfiTextStyles.note, color = palette.textSecondary)
                            }
                        }
                    }
                    Spacer(Modifier.height(Spacing.Medium))
                    UfiButtonRow {
                        UfiButton(
                            text = if (state.loading) "刷新中…" else "立即刷新",
                            size = UfiButtonSize.Small,
                            enabled = !state.loading,
                            onClick = { viewModel.poetry.refresh(force = true) }
                        )
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
}
