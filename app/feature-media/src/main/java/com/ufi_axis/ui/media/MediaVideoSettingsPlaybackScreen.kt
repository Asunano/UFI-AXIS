package com.ufi_axis.ui.media

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavHostController
import com.ufi_axis.data.model.MEDIA_TYPE_VIDEO
import com.ufi_axis.ui.components.common.UfiButton
import com.ufi_axis.data.model.MEDIA_TYPE_VIDEO
import com.ufi_axis.ui.components.common.UfiButtonSize
import com.ufi_axis.data.model.MEDIA_TYPE_VIDEO
import com.ufi_axis.ui.components.common.UfiButtonVariant
import com.ufi_axis.data.model.MEDIA_TYPE_VIDEO
import com.ufi_axis.ui.components.common.UfiCompactProgressBar
import com.ufi_axis.data.model.MEDIA_TYPE_VIDEO
import com.ufi_axis.ui.components.common.UfiPageBackground
import com.ufi_axis.data.model.MEDIA_TYPE_VIDEO
import com.ufi_axis.ui.components.common.UfiScreenScaffold
import com.ufi_axis.data.model.MEDIA_TYPE_VIDEO
import com.ufi_axis.ui.components.common.UfiSettingsItem
import com.ufi_axis.data.model.MEDIA_TYPE_VIDEO
import com.ufi_axis.ui.components.common.UfiSettingsRowCard
import com.ufi_axis.data.model.MEDIA_TYPE_VIDEO
import com.ufi_axis.ui.components.common.UfiSettingsToggle
import com.ufi_axis.data.model.MEDIA_TYPE_VIDEO
import com.ufi_axis.ui.theme.Spacing
import com.ufi_axis.util.AppPreferences
import com.ufi_axis.viewmodel.MainViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 视频设置 → **播放与预热**（2026-10-14 从 `MediaVideoSettingsScreen` 拆出）。
 *
 * 三张卡：
 *  · **设备后台预热封面（实验性）**：core 侧 ffmpeg 预热开关（闲时后台把整库封面铺满，列表秒出）。
 *    真源在 core，`null` = 老版本 core 没这个键，开关禁用（不拿默认值冒充真值）；
 *  · **封面预热**：状态 + 「立即预热」+ 进度条；
 *  · **最近播放**：首页那条横向列表，最多 6 条，记在本机（[AppPreferences]），不上传设备。
 *
 * ## 2026-10-14 重写（用户报「设置项没有固定」）
 * 原来「预热状态」那一格有三处会让版式**逐帧变化**，读起来不像一个稳定的设置项：
 *   ① 标题随状态改写（"预热中 3/9" / "上次预热：…" / "预热状态：暂无记录"）；
 *   ② 按钮文案在「立即预热 ⇄ 已触发」之间切换，按钮宽度跟着跳；
 *   ③ 进度条只在有 `total` 时出现，卡片高度随之增减。
 * 现在三处全部**固定**：标题恒为「封面预热」；按钮恒为「立即预热」，用**禁用态**表达
 * "已受理 / 不可用"；进度条**常驻**（无记录时就是 0%，不再消失）。
 * 会变的信息全部收进 `description` —— 它是唯一允许改写的区域，且三种状态文案结构一致。
 * 「最近播放」同理：条数从按钮文案（`清空（3）` → 清空后 `清空（0）`）挪进 description，
 * 按钮恒为「清空」。
 *
 * ## 本页自己的数据加载
 * `loadThumbPrewarm()` + `thumbPrewarmCurrent()`（开关真值）、`refreshPrewarmState()` 的
 * 3s 兜底轮询（只在本页存活期间跑，`running` 为假时一次即止）、本机最近播放条数。
 * 本机抽帧已于 2026-10-07 删除，所以这里没有"本机抽帧目录"这一类东西要清。
 *
 * ## 版式
 * 一项一张 `UfiSettingsRowCard`，间距走 [Spacing.SettingsCardGap]；**不要**用 `UfiGroupHeader`
 * 把这几项并回一张卡。`UfiPageBackground` 自带 `verticalScroll`，页面里不要再套滚动容器，
 * 也不要再加水平内距（卡片自带 16dp）。
 */
@Composable
fun MediaVideoSettingsPlaybackScreen(
    viewModel: MainViewModel,
    navController: NavHostController
) {
    val context = LocalContext.current
    val media = viewModel.media
    val scope = rememberCoroutineScope()

    // core 侧封面预热开关。真源在 core；null = 老版本 core 没这个键，开关禁用。
    var prewarm by remember { mutableStateOf<Boolean?>(null) }
    var prewarmSaving by remember { mutableStateOf(false) }
    // 「立即预热」的短时禁用：受理后 2s 内不允许连点。
    // 刻意用**禁用态**而不是改文案（"已触发"）—— 改文案会让按钮宽度跳动，那正是这次要修的问题。
    var triggering by remember { mutableStateOf(false) }

    // 只收一次。原先同一个 flow 在卡片体内被 collectAsState() 了两遍。
    val progress by media.thumbPrewarmProgress.collectAsState()

    var recentCount by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        media.loadThumbPrewarm()
        prewarm = media.thumbPrewarmCurrent()
        recentCount = runCatching { AppPreferences(context).mediaRecentPlays(MEDIA_TYPE_VIDEO).size }.getOrDefault(0)
    }

    // 预热进度兜底轮询：只在本页存活期间跑；`running` 为假时**一次即止**（不等 3s）。
    // 提到页面级而不是塞在卡片体内 —— 卡片只负责渲染，不该拥有轮询生命周期。
    LaunchedEffect(Unit) {
        while (true) {
            media.refreshPrewarmState()
            if (media.thumbPrewarmProgress.value?.running != true) break
            delay(3_000)
        }
    }

    UfiScreenScaffold(title = "播放与预热", navController = navController, showBack = true) { padding ->
        UfiPageBackground(modifier = Modifier.padding(padding)) {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.SettingsCardGap)) {

                // ── 开关：设备后台预热封面 ──
                UfiSettingsRowCard {
                    UfiSettingsToggle(
                        title = "设备后台预热封面（实验性）",
                        description = if (prewarm == null) {
                            "当前设备版本的 core 不支持此开关。"
                        } else {
                            "开启后设备在充电或电量充足（>30%）时用 ffmpeg 逐个补齐视频封面，" +
                                "打开列表时全部秒出。软解较吃 CPU，低配设备建议保持关闭。"
                        },
                        checked = prewarm == true,
                        icon = Icons.Default.History,
                        enabled = prewarm != null && !prewarmSaving,
                        onCheckedChange = { checked ->
                            prewarmSaving = true
                            scope.launch {
                                val err = media.setThumbPrewarm(checked)
                                prewarm = media.thumbPrewarmCurrent()
                                prewarmSaving = false
                                err?.let { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
                            }
                        }
                    )
                }

                // ── 状态 + 动作：封面预热（标题 / 按钮文案 / 进度条三者恒在） ──
                UfiSettingsRowCard {
                    val p = progress
                    val statusText = if (p == null) {
                        "还没有跑过预热。点右侧「立即预热」跑一轮。"
                    } else if (p.running) {
                        if (p.currentName.isNotBlank()) {
                            "正在处理：${p.currentName}（${p.done}/${p.total}）"
                        } else {
                            "正在处理…（${p.done}/${p.total}）"
                        }
                    } else {
                        "上次完成：新增 ${p.done} · 失败 ${p.failed} · 共 ${p.total}"
                    }
                    val barProgress = p?.let {
                        if (it.total > 0) (it.done + it.failed).toFloat() / it.total else 0f
                    } ?: 0f

                    UfiSettingsItem(
                        title = "封面预热",
                        description = statusText,
                        icon = Icons.Default.Info,
                        trailing = {
                            UfiButton(
                                text = "立即预热",
                                onClick = {
                                    if (!triggering) {
                                        triggering = true
                                        scope.launch {
                                            val (ok, msg) = media.runPrewarmNow()
                                            if (!ok) {
                                                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                                            } else {
                                                // 受理后立刻拉一次快照，让 UI 马上切到「预热中」
                                                media.refreshPrewarmState()
                                            }
                                            delay(2_000)
                                            triggering = false
                                        }
                                    }
                                },
                                variant = UfiButtonVariant.Subtle,
                                size = UfiButtonSize.Small,
                                // 开关没开（或老 core 无此键）时不可点；受理后 2s 内防连点。
                                enabled = prewarm == true && !triggering
                            )
                        }
                    )
                    UfiCompactProgressBar(
                        progress = barProgress,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                // ── 最近播放 ──
                UfiSettingsRowCard {
                    UfiSettingsItem(
                        title = "最近播放",
                        description = "首页那条横向列表，最多 6 条；记在本机，不上传设备。" +
                            "当前 $recentCount 条",
                        icon = Icons.Default.History,
                        trailing = {
                            UfiButton(
                                text = "清空",
                                onClick = {
                                    runCatching { AppPreferences(context).clearMediaRecentPlays(MEDIA_TYPE_VIDEO) }
                                    recentCount = 0
                                },
                                variant = UfiButtonVariant.Subtle,
                                size = UfiButtonSize.Small,
                                enabled = recentCount > 0
                            )
                        }
                    )
                }
            }

            Spacer(Modifier.height(Spacing.Large))
        }
    }
}
