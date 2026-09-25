package com.ufi_axis.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FormatQuote
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.ufi_axis.data.model.PoetryResponse
import com.ufi_axis.ui.components.common.*
import com.ufi_axis.ui.theme.LocalResolvedPalette
import com.ufi_axis.ui.theme.Spacing
import com.ufi_axis.ui.theme.UfiTextStyles
import com.ufi_axis.viewmodel.MainViewModel

/**
 * 今日诗词（设置 → 小功能 → 今日诗词，2026-09-18）。
 *
 * 与 [WeatherSettingsScreen] 并列的第二个「标题栏小挂件」。独立成页而不是和天气挤在
 * 「小功能」那一屏：两者各自都有开关 + 一张内容预览卡 + 一个刷新按钮，放在一页里
 * 会出现两个长得一样的「立即刷新」和两条「当前…」卡片，用户得先认哪个管哪个。
 *
 * 本页只是 core `/api/poetry/config` 的编辑界面，**不做任何"挑诗"的逻辑** ——
 * 选哪句由上游按地区、天气、时辰与农历自动匹配（见 [com.ufi_axis.viewmodel.module.PoetryModule]），
 * 命中的标签就是诗句下面那行推荐依据。
 *
 * ## 版式（2026-09-22，与 [WeatherSettingsScreen] 同一套）
 * 诗句摘要卡 [PoemHero] 排最上，两个开关各一张卡，全篇与翻译收进折叠卡。
 * 原来是"一张双开关卡 + 一张全展开的长内容卡"：诗句在开关下面、全篇与翻译一直展开，
 * 长诗能把刷新按钮顶出两屏。
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
                // 诗句摘要排最上：这一页存在的理由就是"看今天这句是什么"，
                // 那就别让它排在两个开关下面。字号放大居中，出处与推荐依据紧随其后。
                UfiSettingsRowCard(contentPadding = PaddingValues(16.dp)) {
                    PoemHero(
                        poem = state.poem,
                        loading = state.loading,
                        onRefresh = { viewModel.poetry.refresh(force = true) }
                    )
                }

                UfiSettingsRowCard {
                    UfiSettingsToggle(
                        icon = Icons.Default.AutoStories,
                        title = "显示今日诗词",
                        checked = state.config.enabled,
                        onCheckedChange = { viewModel.poetry.setEnabled(it) }
                    )
                }

                UfiSettingsRowCard {
                    UfiSettingsToggle(
                        icon = Icons.Default.FormatQuote,
                        title = "显示出处",
                        checked = state.config.show_origin,
                        onCheckedChange = { viewModel.poetry.setShowOrigin(it) }
                    )
                }

                // 全篇与翻译折叠：平时都不看，展开着会把上面的开关顶出屏幕。
                // 只有真有这两段内容时才出这张卡 —— 空的折叠行点开什么也没有。
                state.poem?.takeIf { it.full_content.isNotEmpty() || it.translate.isNotEmpty() }
                    ?.let { poem ->
                        UfiSettingsRowCard(contentPadding = PaddingValues(16.dp)) {
                            if (poem.full_content.isNotEmpty()) {
                                FoldSection(title = "全篇") {
                                    poem.full_content.forEach { line ->
                                        Text(
                                            line,
                                            style = UfiTextStyles.body,
                                            color = palette.textPrimary
                                        )
                                    }
                                }
                            }
                            if (poem.full_content.isNotEmpty() && poem.translate.isNotEmpty()) {
                                UfiDivider()
                            }
                            if (poem.translate.isNotEmpty()) {
                                FoldSection(title = "翻译") {
                                    poem.translate.forEach { line ->
                                        Text(
                                            line,
                                            style = UfiTextStyles.note,
                                            color = palette.textSecondary
                                        )
                                    }
                                }
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
}

/**
 * 诗句摘要卡：诗句 + 出处 + 推荐依据 + 刷新，整体居中。
 *
 * 居中而不是左对齐：诗句是短句、两三行，左对齐时右侧会留一大片空白，
 * 居中反而更像"一幅字"。这是本页唯一一处居中排版 —— 下面的开关照旧左对齐，
 * 因为它们是设置行、要和全站其它设置页对齐。
 *
 * 取不到诗句时只显示一行状态文字，不出空卡：这张卡是页面主角，
 * 摆一张空的比不摆更让人以为坏了。
 */
@Composable
private fun PoemHero(
    poem: PoetryResponse?,
    loading: Boolean,
    onRefresh: () -> Unit
) {
    val palette = LocalResolvedPalette.current
    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (poem == null || poem.content.isBlank()) {
            Text(
                text = if (loading) "正在取诗句…" else "还没有取到诗句",
                style = UfiTextStyles.caption,
                color = palette.textSecondary
            )
        } else {
            Text(
                text = poem.content,
                style = UfiTextStyles.bodyLead,
                color = palette.textPrimary,
                textAlign = TextAlign.Center
            )
            if (poem.originLine.isNotBlank()) {
                Spacer(Modifier.height(Spacing.Medium))
                Text(
                    text = poem.originLine,
                    style = UfiTextStyles.caption,
                    color = palette.textSecondary,
                    textAlign = TextAlign.Center
                )
            }
            if (poem.match_tags.isNotEmpty()) {
                Spacer(Modifier.height(Spacing.Small))
                Text(
                    text = poem.match_tags.joinToString(" · "),
                    style = UfiTextStyles.caption,
                    color = palette.accent,
                    textAlign = TextAlign.Center
                )
            }
        }
        Spacer(Modifier.height(Spacing.Large))
        UfiButton(
            text = if (loading) "刷新中…" else "换一句",
            size = UfiButtonSize.Small,
            variant = UfiButtonVariant.Subtle,
            enabled = !loading,
            onClick = onRefresh
        )
    }
}

/**
 * 可折叠的小节（标题行 + 箭头 + 内容）。
 *
 * 用公共件 [UfiExpandSection] 承担展开动画（它会等收起动画播完才停止发子节点，
 * 不留幽灵间距），标题行在这里自己搭 —— 那个组件刻意不带标题，
 * 因为各处的标题行形态不同。
 */
@Composable
private fun FoldSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    val palette = LocalResolvedPalette.current
    var expanded by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(vertical = Spacing.Medium),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                style = UfiTextStyles.captionEmphasis,
                color = palette.textSecondary,
                modifier = Modifier.weight(1f)
            )
            Icon(
                imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = if (expanded) "收起$title" else "展开$title",
                tint = palette.textSecondary,
                modifier = Modifier.size(18.dp)
            )
        }
        UfiExpandSection(expanded = expanded, spacing = Spacing.Small, content = content)
    }
}

