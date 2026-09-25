// 公共更新弹窗（2026-08-13 从 DeviceUpdateScreen 抽取）：
// - UnifiedUpdateDialog（2026-09-06）：**全局唯一**的更新提示弹窗，App 项 + Core 项各一块，
//   跟随 UpdatePromptModule 的聚合 state 展示（含 App 下载进度与 Core 更新/重启进度）；
// - UpdateSettingsDialog：镜像源单选 + 启动时自动检查更新开关（受控组件，持久化由调用方负责）。
// 供「关于页 AboutDeviceScreen」与「更新中心 DeviceUpdateScreen」复用，消除重复实现。
package com.ufi_axis.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Update
import com.ufi_axis.data.model.UpdateStatusResponse
import com.ufi_axis.ui.animation.page.LocalUfiReduceMotion
import com.ufi_axis.ui.components.common.*
import com.ufi_axis.ui.theme.LocalResolvedPalette
import com.ufi_axis.ui.theme.Spacing
import com.ufi_axis.ui.theme.UfiTextStyles
import com.ufi_axis.util.UpdateSource
import com.ufi_axis.viewmodel.state.FrontendUpdateState
import com.ufi_axis.viewmodel.state.UpdatePromptState
import com.ufi_axis.ui.theme.UfiMotion
import kotlin.math.sqrt

// 2026-09-06：原 UpdateCheckDialog（只讲 App 自更新的那个弹窗）已删除，由本文件末尾的
// [UnifiedUpdateDialog] 取代 —— 它把 App 项与 Core 项放进同一个弹窗，只有一项有更新时
// 就只渲染那一项，所以"单独用"和"合并用"共用同一个组件，不需要再留一个只管 App 的版本。

/**
 * 更新过程的进度展示：**居中的公共环形进度 [UfiRingProgress] + 环下方的阶段文案**。
 *
 * 2026-09-04：替换原先"小转圈 + 一行说明"（以及下载态的 LinearProgressIndicator）——
 * 更新是个多阶段、要等挺久的流程，横条进度和 18dp 转圈在弹窗里的存在感太弱，
 * 用户看不出"到哪一步了、还剩多少"。环形进度是项目里既有的公共组件（首页指标环同款），
 * 换过来同时解决了"观感不统一"和"信息量不足"两件事。
 *
 * @param progress 真实进度 0f..1f；**传 null 表示该阶段拿不到百分比**（检查更新 / 打开安装器 /
 *   设备端安装）。公共 [UfiRingProgress] 没有不确定态，此时让环停在 0（只剩轨道）并在
 *   中心放一颗小转圈 —— 完全静止的空环会被当成"卡死了"。
 * @param caption 环下方的阶段说明（第一行，正文字号）。
 * @param subCaption 可选的补充说明（第二行，小字次要色），如"请保持网络连接"。
 */
@Composable
private fun UpdateProgressRing(
    progress: Float?,
    caption: String,
    subCaption: String? = null
) {
    val palette = LocalResolvedPalette.current
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.Medium),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (progress == null) {
            // 不确定态：**复用同一个环**当加载动画 —— 让环自转即可。
            // 2026-09-04：原来是"环停在 0 + 中心塞一个 UfiLoadingIndicator"，等于一个弹窗里
            // 出现两个圆形指示器（外面一圈空槽 + 里面一个小转圈），观感很杂。
            // 现在只有一个环在转：progress 固定 25%（一段弧），整体绕中心匀速旋转，
            // 中心留空（传空 lambda 覆盖组件默认的百分比数字——25% 是画弧用的，不是真实进度）。
            //
            // 2026-09-26：接上降低动效。无限旋转是典型的前庭刺激源，而且它会一直挂着一个
            // 永不结束的动画帧回调；系统开了「移除动画」还在转等于没接这条通道。
            // 为真时不起 rememberInfiniteTransition，静态画同一段 25% 弧（仍然看得出
            // "这是个未完成的环"，只是不动），不要退化成空环——那会被当成卡死。
            val reduceMotion = LocalUfiReduceMotion.current
            val angle = if (reduceMotion) {
                0f
            } else {
                val spin = rememberInfiniteTransition(label = "ringSpin")
                spin.animateFloat(
                    initialValue = 0f,
                    targetValue = 360f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(UfiMotion.Duration.Languid, easing = LinearEasing)
                    ),
                    label = "ringSpinAngle"
                ).value
            }
            UfiRingProgress(
                progress = 0.25f,
                modifier = Modifier.graphicsLayer { rotationZ = angle },
                size = 116.dp,
                strokeWidth = 8.dp,
                // animate=false：进度是常量，没必要再跑一遍 800ms 补间
                animate = false,
                centerContent = {}
            )
        } else {
            UfiRingProgress(
                progress = progress,
                size = 116.dp,
                strokeWidth = 8.dp
            )
        }
        // 2026-09-04：环与下方文案的间距 Medium(8dp) → XLarge(16dp)。116dp 的大环视觉重量很沉，
        // 8dp 让文案像"贴"在环上；间距要跟元素体量走，不能沿用行间距那一档。
        Spacer(Modifier.height(Spacing.XLarge))
        Text(
            text = caption,
            style = MaterialTheme.typography.bodyMedium,
            color = palette.textPrimary,
            textAlign = TextAlign.Center
        )
        if (!subCaption.isNullOrBlank()) {
            Spacer(Modifier.height(Spacing.Small))
            Text(
                text = subCaption,
                style = MaterialTheme.typography.bodySmall,
                color = palette.textSecondary,
                textAlign = TextAlign.Center
            )
        }
    }
}

/** 终态动画里「环收口」占整段进度的比例；剩下的 45% 留给中心符号的笔画描出。 */
private const val RESULT_RING_CLOSE_FRACTION = 0.55f

/**
 * 更新流程的**终态**展示：环收口 + 中心自绘符号（成功打勾 / 失败画叉）+ 环下方文案。
 *
 * 2026-09-26：此前 done / failed 只有一行 Text。前面几十秒一直是个 116dp 的大环在动，
 * 结束瞬间整块塌成一行小字 —— 用户反馈"不知道到底成没成"。这里让**同一个环**接着演下去：
 * 先把它扫成一个完整的圆（"加载收口了"），再在圆心描出结论符号，视线不需要重新找落点。
 *
 * 为什么不复用 [UfiRingProgress]：它的签名被 `[F24] STABLE-UI-API` 冻结，而这里要的是
 * "环的扫角 + 中心笔画"两段共用一条时间轴，塞进公共组件就得给它加终态参数。自绘一份更便宜。
 *
 * 为什么不用 Material 的 `Icons.Default.CheckCircle` / `Error`：实心圆底 + 白符号在弹窗里
 * 看着像贴了个 emoji ✅/❌，与全站线性图标风格不一致（同一条决策见 [ApkPushDialog] 的成功态）。
 *
 * @param success true = 成功（打勾 / `palette.success`），false = 失败（打叉 / `palette.error`）。
 * @param caption 环下方的结论文案（第一行，正文字号）。
 * @param subCaption 可选的补充说明（第二行，小字次要色），如失败后的处置建议。
 */
@Composable
private fun UpdateResultRing(
    success: Boolean,
    caption: String,
    subCaption: String? = null
) {
    val palette = LocalResolvedPalette.current
    // 主色跟语义走；轨道圈沿用 UfiRingProgress 的口径，终态与进度态叠在同一条基线上。
    val mainColor = if (success) palette.success else palette.error
    val trackColor = palette.divider.copy(alpha = 0.15f)

    // 一条时间轴管两段（环收口 → 描符号）：两个独立动画对不齐相位，
    // 会出现"符号已经画完、环还差一截"。
    val reduceMotion = LocalUfiReduceMotion.current
    val anim = remember(success) { Animatable(0f) }
    LaunchedEffect(success, reduceMotion) {
        // 降低动效：直接给终态。这一段是"从无到有描出来"的自绘动画，跳过即可，
        // 不需要退化成别的过渡 —— 结论本身由符号与文案表达，不依赖运动。
        if (reduceMotion) anim.snapTo(1f)
        else anim.animateTo(
            targetValue = 1f,
            // Reveal(600ms) 就是"图表绘入"那一档，与本处的笔画描出同类
            animationSpec = tween(UfiMotion.Duration.Reveal, easing = UfiMotion.Easing.Standard)
        )
    }

    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.Medium),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Canvas(
            modifier = Modifier
                .size(116.dp)
                // 自绘图形对无障碍是透明的，必须显式给语义，否则读屏只能念到下面那行 caption
                .semantics { contentDescription = if (success) "更新完成" else "更新失败" }
        ) {
            val t = anim.value
            val strokePx = 8.dp.toPx()
            val stroke = Stroke(width = strokePx, cap = StrokeCap.Round)
            // 几何与 UpdateProgressRing 一致：直径内缩一个线宽，圆弧才不会被裁掉半条边
            val diameter = size.minDimension - strokePx
            val arcTopLeft = Offset(strokePx / 2, strokePx / 2)
            val arcSize = Size(diameter, diameter)

            drawArc(
                color = trackColor,
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = arcTopLeft,
                size = arcSize,
                style = stroke
            )

            // ── 阶段 A：环从 0 扫到整圈 ──
            val ringSweep = 360f * (t / RESULT_RING_CLOSE_FRACTION).coerceIn(0f, 1f)
            if (ringSweep > 0f) {
                drawArc(
                    color = mainColor,
                    startAngle = -90f,
                    sweepAngle = ringSweep,
                    useCenter = false,
                    topLeft = arcTopLeft,
                    size = arcSize,
                    style = stroke
                )
            }

            // ── 阶段 B：在圆心区域按笔画顺序描符号 ──
            val symbolT = ((t - RESULT_RING_CLOSE_FRACTION) / (1f - RESULT_RING_CLOSE_FRACTION))
                .coerceIn(0f, 1f)
            if (symbolT > 0f) {
                // 单位坐标 × 边长：符号跟着环的尺寸缩放，改 116dp 不用重算一遍坐标
                val side = size.minDimension
                fun pt(x: Float, y: Float) = Offset(x * side, y * side)
                fun lerp(a: Offset, b: Offset, f: Float) =
                    Offset(a.x + (b.x - a.x) * f, a.y + (b.y - a.y) * f)
                fun len(a: Offset, b: Offset) =
                    sqrt((b.x - a.x) * (b.x - a.x) + (b.y - a.y) * (b.y - a.y))
                fun seg(a: Offset, b: Offset) =
                    drawLine(color = mainColor, start = a, end = b, strokeWidth = strokePx, cap = StrokeCap.Round)

                if (success) {
                    // 对勾两段折线。进度按**实际长度比例**切分而不是各占一半 ——
                    // 短的那一撇只有长撇的一半长，平分会让笔尖在拐角处明显减速。
                    val p1 = pt(0.30f, 0.52f)
                    val p2 = pt(0.44f, 0.67f)
                    val p3 = pt(0.71f, 0.36f)
                    val l1 = len(p1, p2)
                    val split = l1 / (l1 + len(p2, p3))
                    if (symbolT <= split) {
                        seg(p1, lerp(p1, p2, symbolT / split))
                    } else {
                        seg(p1, p2)
                        seg(p2, lerp(p2, p3, (symbolT - split) / (1f - split)))
                    }
                } else {
                    // 叉两条对角线：等长，所以前后各半，不需要按长度切
                    val a1 = pt(0.35f, 0.35f)
                    val a2 = pt(0.65f, 0.65f)
                    val b1 = pt(0.65f, 0.35f)
                    val b2 = pt(0.35f, 0.65f)
                    if (symbolT <= 0.5f) {
                        seg(a1, lerp(a1, a2, symbolT / 0.5f))
                    } else {
                        seg(a1, a2)
                        seg(b1, lerp(b1, b2, (symbolT - 0.5f) / 0.5f))
                    }
                }
            }
        }
        // 环下文案排版与 UpdateProgressRing 同一份：终态与进度态切换时文案不跳位
        Spacer(Modifier.height(Spacing.XLarge))
        Text(
            text = caption,
            style = MaterialTheme.typography.bodyMedium,
            color = palette.textPrimary,
            textAlign = TextAlign.Center
        )
        if (!subCaption.isNullOrBlank()) {
            Spacer(Modifier.height(Spacing.Small))
            Text(
                text = subCaption,
                style = MaterialTheme.typography.bodySmall,
                color = palette.textSecondary,
                textAlign = TextAlign.Center
            )
        }
    }
}

/**
 * 更新设置弹窗：镜像源单选（GitHub 官方直连 / 国内镜像）+ 启动时自动检查更新开关。
 *
 * 受控组件——弹窗内的 [sourceMode] / [autoCheck] 由调用方持有状态（读自 [com.ufi_axis.util.AppPreferences]），
 * 变更通过回调回传，由调用方负责持久化（写入 AppPreferences.updateSourceMode / autoCheckUpdate）。
 *
 * @param sourceMode 当前更新源模式（UpdateSource.MODE_AUTO / MODE_MIRROR / MODE_DIRECT）。
 * @param onSourceModeChange 模式变更回调（写入 AppPreferences.updateSourceMode）。
 * @param lastCountry core 给出的出网国家码（只读展示；检测由 core 启动时自动做，app 不再自己测）。
 * @param autoCheck 启动时自动检查更新开关状态。
 * @param onAutoCheckChange 开关变更回调（写入 AppPreferences.autoCheckUpdate）。
 * @param onPushApk 点击「选择 APK」触发 SAF 文件选择（调用方负责）。
 * @param onDismiss 关闭弹窗。调用方在此回调内**按需**同步更新源到设备 ——
 *   仅当镜像前缀与打开弹窗时的快照不同才下发，避免"点开看一眼再关"也打一次设备写请求。
 */
@Composable
fun UpdateSettingsDialog(
    visible: Boolean,
    sourceMode: String,
    onSourceModeChange: (String) -> Unit,
    lastCountry: String,
    autoCheck: Boolean,
    onAutoCheckChange: (Boolean) -> Unit,
    onPushApk: () -> Unit,
    onDismiss: () -> Unit
) {
    val palette = LocalResolvedPalette.current
    UfiCustomDialog(
        visible = visible,
        onDismiss = onDismiss,
        title = "更新设置",
        icon = rememberVectorPainter(Icons.Filled.Update),
        showCloseButton = false,
        // 更新相关弹窗一律禁止点外部关闭（2026-09-18）：这页有多个"改完立刻生效"的开关与
        // 输入框，误触空白处关掉会让用户以为没保存；与下面两个更新弹窗保持同一口径。
        dismissOnClickOutside = false
    ) {
        // 间距统一到 UfiDialogBody（12dp）
        UfiDialogBody {
            // 关闭动作交给 shell 排时序：离场 backdrop（逐渐清晰）要播完才卸载窗口，
            // 见 LocalUfiDialogClose。这里在 content 顶部读一次，供下方「选择 APK」使用
            // （它会先关掉本弹窗再拉起 SAF）。
            val close = LocalUfiDialogClose.current
            // ── 分层（2026-08-10 设置项过多）：更新源 / 守护与操作 两个页签 ──
            var settingsTab by remember { mutableStateOf(0) }
            UfiScrollableTabRow(
                selectedTabIndex = settingsTab,
                onTabSelected = { settingsTab = it },
                tabs = listOf("更新源", "守护与操作")
            )
            // ── 页签内容切换动画（方向感知：复用公共组件 UfiAnimatedTabContent）──
            UfiAnimatedTabContent(targetState = settingsTab) { tab ->
                when (tab) {
                    0 -> {
                        // ═══ Tab1 更新源：模式（自动/镜像/直连，按国家检测智能选镜像） ═══
                        Text(
                            text = "更新源",
                            style = MaterialTheme.typography.labelMedium,
                            color = palette.textSecondary
                        )
                        UfiScrollableTabRow(
                            selectedTabIndex = when (sourceMode) {
                                UpdateSource.MODE_AUTO -> 0
                                UpdateSource.MODE_MIRROR -> 1
                                else -> 2
                            },
                            onTabSelected = { idx ->
                                onSourceModeChange(
                                    when (idx) {
                                        0 -> UpdateSource.MODE_AUTO
                                        1 -> UpdateSource.MODE_MIRROR
                                        else -> UpdateSource.MODE_DIRECT
                                    }
                                )
                            },
                            tabs = listOf("自动", "镜像", "直连")
                        )
                        Text(
                            text = "自动按国家选源；其他模式强制生效。",
                            style = MaterialTheme.typography.bodySmall,
                            color = palette.textSecondary
                        )
                    }
                    else -> {
                        // ═══ Tab2 守护与操作：国家展示 + 自动检查 + 推送 APK ═══
                        // 「当前国家/地区」是**只读**的：检测在 core（启动时自动做一次，
                        // 结果存设备侧），app 这边没有"重新检测"按钮也没有"检测中"状态。
                        UfiSettingsItem(
                            title = "当前国家/地区",
                            description = when (lastCountry) {
                                "CN" -> "中国大陆（自动模式将走镜像）"
                                "US" -> "美国（自动模式直连）"
                                "" -> "未检测（自动模式默认直连）"
                                else -> lastCountry
                            }
                        )
                        UfiSettingsItem(
                            title = "启动时自动检查更新",
                            description = "进入更新中心时自动检查前端更新（24h 节流）",
                            trailing = {
                                UfiSwitch(
                                    checked = autoCheck,
                                    onCheckedChange = onAutoCheckChange
                                )
                            }
                        )
                        // 同步到设备精简：移除「同步」按钮行——关闭弹窗时由调用方按需同步（改过才发）
                        // 推送 APK 更新（兜底）
                        UfiSettingsItem(
                            title = "推送 APK 更新（备用方式）",
                            description = "选择本地 APK 手动推送到设备安装（后端无法自下载时）",
                            trailing = {
                                UfiButton(variant = UfiButtonVariant.Subtle, size = UfiButtonSize.Small, text = "选择 APK", onClick = { close(onPushApk) })
                            }
                        )
                    }
                }
            }

            // 底部标准操作区：取消（描边）+ 完成（主色填充），等宽双按钮（完成时调用方自动同步到设备）
            UfiDialogActions(
                onDismiss = onDismiss,
                onConfirm = onDismiss,
                confirmText = "完成",
                dismissText = "取消"
            )
        }
    }
}

/**
 * 带按压动画的行内描边按钮已提升为公共组件（UfiButton.kt，2026-08-10；本文件不再保留私有实现）。
 * 2026-09-04（P4c）：该组件已并入 [UfiButton]，现写法为
 * `UfiButton(variant = UfiButtonVariant.Subtle, size = UfiButtonSize.Small, ...)`。
 */

/**
 * 推送 APK 更新进度弹窗（2026-08-10：替代原 Toast 反馈，改用正常弹窗显示上传/安装进度与结果）。
 *
 * 2026-08-27 修复：底部操作区原来写在 `UfiCustomDialog { ... }` 的 **content lambda 之外**，
 * 等于直接 compose 进调用方页面的 Column —— 关于页无条件调用本组件（`visible=false` 也调用），
 * 于是「取消 / 后台运行」两个按钮常驻在关于页底部。现在按钮走 shell 的
 * `confirmButton` / `dismissButton` 槽位，`visible=false` 时整块都不渲染。
 *
 * 同时去掉了假的「取消」：它的 onClick 也是 onDismiss，并不会真的中断上传/安装，
 * 进行中只保留一个「后台运行」（关弹窗、任务继续）。
 *
 * @param visible 是否可见（调用方在 SAF 选完 APK 后置 true；用户关闭后置 false）
 * @param state 后端设备更新状态（viewModel.tools.updateDeviceState），null 表示初始/空闲
 * @param onDismiss 关闭弹窗
 */
@Composable
fun ApkPushDialog(
    visible: Boolean,
    state: UpdateStatusResponse?,
    onDismiss: () -> Unit,
    onRetry: () -> Unit = {}
) {
    // 2026-09-26：本函数不再直接用调色板（done/failed 两态的取色已随 [UpdateResultRing] 内移），
    // 故删掉原来的 `val palette = LocalResolvedPalette.current`。
    val s = state?.state ?: "uploading"
    val title = when (s) {
        "uploading" -> "正在上传 APK"
        "installing" -> "正在安装"
        "done" -> "安装成功"
        "failed" -> "推送失败"
        else -> "推送 APK 更新"
    }
    UfiCustomDialog(
        visible = visible,
        onDismiss = onDismiss,
        title = title,
        icon = rememberVectorPainter(Icons.Filled.Cloud),
        // 禁止点外部关闭（2026-09-18）：推送过程中误触空白处会把进度弹窗关掉，
        // 用户以为推送被取消了（其实还在传）。要离开请按「后台运行」。
        dismissOnClickOutside = false,
        confirmButton = {
            // 关闭动作交给 shell 排时序（见 LocalUfiDialogClose）。「重试」不包：
            // 它重新发起一次推送，弹窗要留着继续显示进度。
            val close = LocalUfiDialogClose.current
            when (s) {
                "done" -> UfiButton(text = "完成", onClick = { close(onDismiss) })
                "failed" -> UfiButton(text = "重试", onClick = onRetry)
                else -> UfiButton(text = "后台运行", onClick = { close(onDismiss) })
            }
        },
        // 只有失败态需要第二个按钮（重试 / 知道了）；进行中与成功态单按钮即可
        dismissButton = if (s == "failed") {
            {
                val close = LocalUfiDialogClose.current
                UfiButton(variant = UfiButtonVariant.Secondary, text = "知道了", onClick = { close(onDismiss) })
            }
        } else {
            null
        }
    ) {
        UfiDialogBody {
            when (s) {
                "uploading" -> {
                    // 上传有真实百分比（后端 progress 0..100）→ 环里显示数字，环下方只讲阶段。
                    // 2026-09-04：caption 原来直接用 state.message，而后端/ViewModel 生成的 message 是
                    // "正在上传 APK… 42%" —— 百分比和环里的数字重复了。改成固定文案，百分比只由环承担。
                    UpdateProgressRing(
                        progress = (state?.progress ?: 0).coerceIn(0, 100) / 100f,
                        caption = "正在上传安装包",
                        subCaption = "请保持与设备的连接"
                    )
                }

                "installing" -> {
                    // 安装由设备端 pm/PI 完成，拿不到进度 → 不确定态（环停在 0 + 中心转圈）。
                    UpdateProgressRing(
                        progress = null,
                        caption = state?.message?.takeIf { it.isNotBlank() } ?: "正在安装…"
                    )
                }

                "done" -> {
                    // 2026-09-04：去掉前置的 Icons.Default.CheckCircle —— 实心圆底 + 白勾在弹窗里
                    // 看着像贴了个 emoji ✅，与全站线性图标风格不一致。
                    // 2026-09-26：改用 [UpdateResultRing] —— 上传态那个 116dp 的环收口成完整圆
                    // 再描出勾，与前一帧的进度环是同一个元素，不再"整块塌成一行小字"。
                    UpdateResultRing(
                        success = true,
                        caption = state?.message?.takeIf { it.isNotBlank() }
                            ?: "APK 已推送安装，设备将自动重启生效"
                    )
                }

                "failed" -> {
                    // 错误分类：原因 + 建议（2026-08-10 增强错误反馈）
                    // 2026-09-26：原来是「静态 Icons.Default.Error + 两行文案」的横排。
                    // 静态实心图标同样违背上面那条线性风格决策，且失败与成功两个终态长得完全
                    // 不像同一套 UI。现在两行落到环的 caption / subCaption，图标由环自绘的叉承担。
                    val (cause, hint) = classifyPushError(state?.message)
                    UpdateResultRing(
                        success = false,
                        caption = "原因：$cause",
                        subCaption = "建议：$hint"
                    )
                }

                else -> {}
            }
            // 2026-09-04：这里原来手写了 `Spacer(Spacing.Medium)` 补内容与按钮之间的间距。
            // 现在 UfiDialogBody 统一给了 12dp（与标题→内容同值），手写的这一段会叠成 20dp，故删。
        }
    }
}


/**
 * 推送失败原因分类（2026-08-10：增强错误反馈）
 * 根据 state.message 关键字匹配，返回 (原因描述, 建议操作) 供 ApkPushDialog 失败区双行展示。
 */
private fun classifyPushError(message: String?): Pair<String, String> {
    val msg = message?.trim().orEmpty()
    if (msg.isEmpty()) return "未知错误" to "请重试或检查 Core 端日志"
    return when {
        msg.contains("HTTP 413") || msg.contains("Entity Too Large") -> "APK 文件超过后端请求体上限" to
            "确认 Core 版本支持动态上传限制（commit 7c26ee3+），否则检查 APK 体积或更新 Core"
        msg.contains("HTTP 500") || msg.contains("HTTP 502") || msg.contains("HTTP 503") -> "设备端 Core 服务器错误" to
            "请稍后重试；若持续失败请检查设备端 Core 日志"
        msg.contains("HTTP 404") -> "设备端接口路径不存在" to
            "确认设备端 Core 版本与前端 App 兼容"
        msg.contains("HTTP 401") || msg.contains("HTTP 403") || msg.contains("Unauthorized") -> "设备配对鉴权失败" to
            "检查设备端 Core 配对 token 是否与前端一致"
        msg.contains("ConnectException") || msg.contains("无法连接") || msg.contains("timeout", true) ||
            msg.contains("Failed to connect") || msg.contains("ECONNREFUSED") -> "无法连接设备 Core" to
            "检查设备 IP/端口是否可达、Core 是否在线、是否在同一局域网"
        msg.contains("脚本启动失败") || msg.contains("守护脚本") || msg.contains("ADB") -> "更新脚本启动失败" to
            "无法写入/执行守护脚本，请检查 ADB 通道（设备开发者模式+USB调试授权）后重试"
        msg.contains("磁盘") || msg.contains("space") || msg.contains("ENOSPC") -> "设备磁盘空间不足" to
            "清理设备端 /sdcard/UFI/update 目录下的旧 APK 文件后重试"
        msg.contains("FileNotFound") || msg.contains("NoSuchFile") || msg.contains("无法读取") -> "APK 文件读取失败" to
            "确认所选 APK 文件存在且可读，重新选择文件后重试"
        msg.contains("APK SHA-256") || msg.contains("校验失败") -> "APK 签名/SHA-256 校验失败" to
            "检查 APK 文件完整性或重新下载官方版本"
        msg.contains("file size") || msg.contains("体积") -> "APK 体积不符合预期" to
            "确认 APK 是官方 release 包，未被截断"
        else -> msg to "请重试或查看设备端 Core 日志（adb logcat / UFI/log）"
    }
}

// ══════════════════════════════════════════════════════════════
// 统一更新弹窗（2026-09-06）
// ══════════════════════════════════════════════════════════════
//
// 为什么放在本文件而不是 app 模块的 UpdateDialog.kt：
// 这个组件要同时被 MainActivity（模块 :app，自动提示）和 AboutDeviceScreen
// （模块 :app:feature-settings，手动检查）调用。:app 依赖 :app:feature-settings，
// 反向不成立 —— 放 :app 里关于页就看不见它。而且下载态复用的
// [UpdateProgressRing]、错误分类 [classifyPushError] 都在本文件里。
// 原 app/src/.../UpdateDialog.kt（只会「前往下载」跳浏览器的那个）已随本次改动删除。

/** 更新日志折叠时显示的行数。超过就给一个「展开」，不做限高嵌套滚动（外层弹窗本身就能滚）。 */
private const val CHANGELOG_COLLAPSED_LINES = 5

/**
 * 统一更新提示弹窗：**同一时刻只有这一个更新弹窗**。
 *
 * App 与 Core **顺序独立**展示：通过 [UpdatePromptState] 的阶段标记同一时刻只渲染其一（core 优先），
 * 双方都有更新时先弹 Core、Core 完成/跳过后再弹 App，避免同时更新互相打架；
 * 只有一项有更新时就只渲染那一项 —— "单独用"和"顺序合并"共用同一个组件，没有两套 UI。
 *
 * 可见性来自 [UpdatePromptState.visible]（唯一真源在 `UpdatePromptModule`），
 * **调用方不要再自己 remember 一个布尔开关** —— 那正是原来两个弹窗会叠加的原因。
 *
 * @param state 聚合状态（App 状态机 + Core 版本/进度 + visible）。
 * @param onDismiss 关闭（「稍后」/ 右上 × / 返回键 / 点外部）。
 * @param onDownloadApp App 项「下载」→ `ToolsModule.downloadFrontendApk()`。
 * @param onInstallApp App 项「安装」→ `ToolsModule.installFrontendApk()`。
 * @param onCoreUpdateConfirmed Core 项**二次确认后**→ `ToolsModule.triggerDeviceUpdate()`
 *   （`POST /api/update/check`，core 自己下载+安装+重启）。确认前的警示文案由本组件出。
 */
@Composable
fun UnifiedUpdateDialog(
    state: UpdatePromptState,
    onDismiss: () -> Unit,
    onDownloadApp: () -> Unit,
    onInstallApp: () -> Unit,
    onCoreUpdateConfirmed: () -> Unit
) {
    val palette = LocalResolvedPalette.current
    // Core 的二次确认**在同一个弹窗内**切换，不另开一个 UfiConfirmDialog ——
    // 再开一个就又是一层独立平台 Window，等于把刚消掉的叠加问题重新引回来。
    var coreConfirming by remember { mutableStateOf(false) }
    LaunchedEffect(state.visible) { if (!state.visible) coreConfirming = false }

    UfiScrollableDialog(
        visible = state.visible,
        onDismiss = onDismiss,
        title = when {
            state.showCoreSection -> "Core 有新版本"
            state.showAppSection -> "App 有新版本"
            else -> "检查更新"
        },
        icon = rememberVectorPainter(Icons.Filled.SystemUpdate),
        // 禁止点外部关闭（2026-09-18，用户反馈"更新 core 时弹窗被点掉导致更新异常"）：
        // 「更新 Core」按下之后 core 会自己下载→安装→重启，这个弹窗是唯一的进度与状态出口；
        // 误触空白处把它关掉，用户既看不到进度、也可能在设备重启期间重复触发更新。
        // 要离开只能按「稍后」（它同时是取消入口）。返回键仍然放行 —— 那是 Android 的
        // 系统级退出手势，屏蔽它会让人以为界面卡死。
        dismissOnClickOutside = false,
        dismissButton = {
            // 关闭动作交给 shell 排时序（见 LocalUfiDialogClose）。内容里的
            // 「下载 / 安装 / 更新 Core / 确认更新 Core」都在弹窗内继续展示进度，所以都不包。
            val close = LocalUfiDialogClose.current
            UfiButton(
                variant = UfiButtonVariant.Secondary,
                text = "稍后",
                onClick = { close(onDismiss) }
            )
        }
    ) {
        UfiDialogBody {
            if (!state.hasAnyUpdate) {
                Text(
                    text = "当前 App 与 Core 均已是最新版本",
                    style = MaterialTheme.typography.bodyMedium,
                    color = palette.textPrimary
                )
            }

            if (state.showAppSection) {
                UpdateTargetBlock(
                    name = "UFI-AXIS App（手机端）",
                    currentVersion = state.app.currentVersion,
                    latestVersion = state.app.latestVersion,
                    changelog = state.app.changelog
                ) {
                    AppUpdateAction(
                        state = state.app,
                        onDownload = onDownloadApp,
                        onInstall = onInstallApp
                    )
                }
            }

            if (state.showCoreSection) {
                UpdateTargetBlock(
                    name = "UFI-AXIS Core（设备端）",
                    currentVersion = state.coreCurrentVersion,
                    latestVersion = state.coreLatestVersion,
                    changelog = state.coreChangelog
                ) {
                    CoreUpdateAction(
                        state = state,
                        confirming = coreConfirming,
                        onRequestConfirm = { coreConfirming = true },
                        onCancelConfirm = { coreConfirming = false },
                        onConfirmed = {
                            coreConfirming = false
                            onCoreUpdateConfirmed()
                        }
                    )
                }
            }
        }
    }
}
/**
 * 单个更新目标的展示块：名称 + 「当前 → 最新」+ 可折叠 changelog + 该项自己的操作区。
 *
 * 名称行用公共 [UfiDialogSectionTitle]（小标题 + 分隔线），与其它弹窗里的分组同一条基线。
 */
@Composable
private fun UpdateTargetBlock(
    name: String,
    currentVersion: String,
    latestVersion: String,
    changelog: String,
    action: @Composable () -> Unit
) {
    val palette = LocalResolvedPalette.current
    Column(Modifier.fillMaxWidth()) {
        UfiDialogSectionTitle(name)
        Spacer(Modifier.height(Spacing.Medium))
        Text(
            // 版本号可能拿不到（App 侧 PackageManager 读失败 / Core 侧设备不在线），
            // 显式写「未知」而不是留空 —— 空着会让「x → y」变成半截箭头。
            text = "${currentVersion.ifBlank { "未知" }} → ${latestVersion.ifBlank { "未知" }}",
            style = UfiTextStyles.bodyEmphasis,
            color = palette.textPrimary
        )
        if (changelog.isNotBlank()) {
            Spacer(Modifier.height(Spacing.Small))
            var expanded by remember { mutableStateOf(false) }
            val lines = remember(changelog) { changelog.trim().lines() }
            Text(
                text = if (expanded) lines.joinToString("\n")
                else lines.take(CHANGELOG_COLLAPSED_LINES).joinToString("\n"),
                style = MaterialTheme.typography.bodySmall,
                color = palette.textSecondary
            )
            if (lines.size > CHANGELOG_COLLAPSED_LINES) {
                Spacer(Modifier.height(Spacing.Small))
                UfiButton(
                    variant = UfiButtonVariant.Subtle,
                    size = UfiButtonSize.Small,
                    text = if (expanded) "收起更新日志" else "展开更新日志",
                    onClick = { expanded = !expanded }
                )
            }
        }
        Spacer(Modifier.height(Spacing.Medium))
        action()
    }
}

/**
 * App 项的操作区：完全沿用既有链路
 * `downloadFrontendApk()` → `installFrontendApk()`（[FrontendUpdateState] 状态机）。
 *
 * 下载进度用公共 [UpdateProgressRing]（116dp 圆环 + 中心百分比），与本地推送
 * [ApkPushDialog]、Core 项统一同一套视觉。顺序弹窗下同时只渲染一块，无两环溢出风险。
 */
@Composable
private fun AppUpdateAction(
    state: FrontendUpdateState,
    onDownload: () -> Unit,
    onInstall: () -> Unit
) {
    val palette = LocalResolvedPalette.current
    when (state.state) {
        "available" -> UfiButton(
            size = UfiButtonSize.Small,
            text = "下载",
            onClick = onDownload
        )

        "downloading" -> UpdateProgressRing(
            progress = state.downloadProgress.coerceIn(0, 100) / 100f,
            caption = "正在下载安装包",
            subCaption = "请保持网络连接"
        )

        "downloaded" -> UfiButton(
            size = UfiButtonSize.Small,
            text = "安装",
            onClick = onInstall
        )

        "installing" -> Text(
            text = "已打开系统安装器，请按提示完成安装",
            style = MaterialTheme.typography.bodySmall,
            color = palette.textSecondary
        )

        // 2026-09-26：终态从「双 Text 的 Column」换成 [UpdateResultRing] —— 下载态一直是个
        // 116dp 的环，失败时塌成两行小红字，用户容易以为界面只是"没反应"。
        "error" -> UpdateResultRing(
            success = false,
            caption = state.errorMessage?.takeIf { it.isNotBlank() } ?: "App 更新失败",
            subCaption = "建议在「更新设置」中切换镜像源后重试"
        )

        else -> {}
    }
}
/**
 * Core 项的操作区，三段式：**待确认 → 二次确认 → 更新中/结果**。
 *
 * 触发走的是 `ToolsModule.triggerDeviceUpdate()`（`POST /api/update/check`）——
 * 这个方法一直存在但在本次改动前**全仓没有任何调用方**，App 端更新 core 只剩"手动选 APK 推送"。
 *
 * 必须二次确认：这条接口不是"检查"，是 core 自己下载 + 校验 + 安装 + **重启服务**一条龙，
 * 期间 8088 不可达十几秒。所以点一下不能直接发，先把后果讲清楚。
 *
 * 触发后展示真实进度而不是直接"完成"：`triggerDeviceUpdate()` 内部会
 * `startDeviceUpdatePolling()`（每 2s 打 `GET /api/update/status`），进度经
 * `updateDeviceState` 回到 [UpdatePromptState.coreStatus]。重启窗口里轮询会失败，
 * ToolsModule 已把连续失败翻译成 `reconnecting=true` 的过渡态，这里照它渲染，
 * 不把"暂时连不上"显示成失败。
 */
@Composable
private fun CoreUpdateAction(
    state: UpdatePromptState,
    confirming: Boolean,
    onRequestConfirm: () -> Unit,
    onCancelConfirm: () -> Unit,
    onConfirmed: () -> Unit
) {
    // 2026-09-26：本函数不再直接用调色板（done/failed 的取色已随 [UpdateResultRing] 内移），
    // 故删掉原来的 `val palette = LocalResolvedPalette.current`。
    when {
        // ── 第三段：已触发，展示进度/结果 ──
        state.coreTriggered -> {
            // 全部经局部变量取值：coreStatus 可空（请求刚发出、core 还没写状态），
            // 在 when 分支里直接点 status.xxx 拿不到智能转换。
            val status = state.coreStatus
            val progress = (status?.progress ?: 0).coerceIn(0, 100)
            val message = status?.message?.takeIf { it.isNotBlank() }
            val reconnecting = status?.reconnecting == true
            when (status?.state) {
                // 有真实百分比（下载/校验/上传）→ 圆环 + 中心百分比；百分比已由环承担，
                // caption 不再重复（与 ApkPushDialog 的上传态同款处理）。
                "downloading", "verifying", "uploading" -> UpdateProgressRing(
                    progress = progress / 100f,
                    caption = "Core 正在下载并校验新版本",
                    subCaption = "请保持设备供电与网络"
                )

                // 安装/重启拿不到进度 → 不确定态（环自转），与本地推送的安装态同款。
                "installing" -> UpdateProgressRing(
                    progress = null,
                    caption = if (reconnecting) "Core 重启中，等待恢复…（接口会中断十几秒）"
                    else message ?: "Core 正在安装并即将重启…"
                )

                // 2026-09-26：两个终态从一行 Text 换成 [UpdateResultRing]。Core 更新要等十几秒
                // 到一分钟（下载→安装→重启），全程都是这个环在动；结束只给一行字，用户分不清
                // "已经结束了"还是"还在跑"。现在环收口 + 描出勾/叉，结论落在同一个视线落点上。
                "done" -> UpdateResultRing(
                    success = true,
                    caption = message ?: "Core 已更新完成并重启"
                )

                "failed" -> UpdateResultRing(
                    success = false,
                    caption = message ?: "Core 更新失败"
                )

                // null / idle：请求刚发出、core 还没写出状态。**明确说尚未完成**，
                // 不要因为"请求成功返回"就显示成已更新。
                else -> UpdateProgressRing(
                    progress = null,
                    caption = "已触发更新，Core 正在后台下载并安装，完成后会自动重启（尚未完成）"
                )
            }
        }

        // ── 第二段：二次确认（就地切换，不再开一个独立弹窗）──
        confirming -> Column(Modifier.fillMaxWidth()) {
            UfiDialogWarning(
                "Core 会自行下载安装并重启后端服务，期间接口会中断十几秒（App 会短暂显示离线）。" +
                    "请保持设备供电与网络，不要在此期间断电。"
            )
            Spacer(Modifier.height(Spacing.Medium))
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.Medium)) {
                UfiButton(
                    variant = UfiButtonVariant.Subtle,
                    size = UfiButtonSize.Small,
                    text = "取消",
                    onClick = onCancelConfirm
                )
                UfiButton(
                    size = UfiButtonSize.Small,
                    text = "确认更新 Core",
                    onClick = onConfirmed
                )
            }
        }

        // ── 第一段：待确认 ──
        else -> UfiButton(
            size = UfiButtonSize.Small,
            text = "更新 Core",
            onClick = onRequestConfirm
        )
    }
}