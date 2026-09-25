package com.ufi_axis.app.startup

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ufi_axis.ui.components.common.UfiButton
import com.ufi_axis.ui.components.common.UfiButtonSize
import com.ufi_axis.ui.components.common.UfiButtonVariant
import com.ufi_axis.ui.components.common.UfiLoadingIndicator
import com.ufi_axis.ui.theme.LocalResolvedPalette
import com.ufi_axis.ui.theme.Spacing
import com.ufi_axis.ui.theme.UfiMotion
import com.ufi_axis.ui.theme.UfiTextStyles
import com.ufi_axis.viewmodel.ConnectivityUiState
import com.ufi_axis.viewmodel.PreloadProgress

/**
 * 启动页（2026-09-22）。**同时是「连不上设备」的唯一展示面。**
 *
 * ## 两态一页
 * - [problem] 为 null：**加载中** —— 呼吸弧 + 当前步骤 + `x / 7` 计数。
 *   首屏预加载在背后拉三个 Tab 的数据（见 `PreloadCoordinator`），这段时间总得给个交代。
 * - [problem] 非 null：**连不上** —— 病因图标 + 标题 + 下一步指引 + 动作按钮。
 *
 * 第二态原来是一个模态弹窗（`connectivityDialogState`）。改到这里来是按用户要求：
 * 「启动页直接替换健康检查弹窗，我更需要这个来显示」。理由站得住 —— 冷启动连不上时，
 * 弹窗底下那一层是个还没有数据的空界面，用一整页写清病因和下一步比盖一个弹窗清楚。
 *
 * 文案全部来自 [ConnectivityUiState]：`title` 是一句话病因（手机未连接网络 / 未配置设备地址 /
 * 无法连接到设备），`detail` 是下一步动作的指引。**不要**再写死"无法访问后端服务（健康检查
 * 接口无响应）"那种只对一种病因成立的句子 —— 那是旧弹窗的遗留，手机没联网时它是错的。
 *
 * ## 为什么是叠在上面的浮层，而不是替换导航图
 * 替换的话导航图要等浮层消失才开始组合，各页的 `LaunchedEffect` 也才开始跑 ——
 * 预加载省下来的时间又还回去了。现在导航图在浮层**背后**正常组合与取数，
 * 浮层淡出时下面已经是有数据的界面。
 *
 * ⚠ 底部胶囊导航栏**盖不住**：它活在独立的 Dialog 窗口里，窗口层级高于 Activity 主窗口。
 * 必须由调用方给 `MainNavGraph(suppressCapsule = ...)` 关掉，光靠这个浮层没用。
 *
 * ## 谁负责让它消失
 * 不是这个组件 —— 是 `MainViewModel.startupOverlayVisible`（两个挡的理由）与
 * `startupGate`（"加载中"那一态的三条终局）。**每条路都必须有终局**，否则这一页会永远
 * 停在加载中：参考实现（UFITOOLS-Widget）的 ViewModel 注释专门记过这个坑。
 *
 * @param visible 由 `MainViewModel.startupOverlayVisible` 驱动。
 * @param progress 由 `MainViewModel.preloadProgress` 驱动，仅「加载中」态使用。
 * @param problem 由 `MainViewModel.startupProblem` 驱动；非 null 即切到「连不上」态。
 * @param onRetry 重新探活。**不会**让页面消失 —— 探活结果才是答案，通了页面自己会走。
 * @param onOpenServerConfig 去服务器设置页（调用方需先让页面让开，否则会被盖住）。
 * @param onSetup 去配对流程（仅 `needsSetup` 时出现）。
 * @param onSkip 「先进入应用」：放弃本轮提示，把屏幕交还给主界面。
 */
@Composable
fun UfiStartupOverlay(
    visible: Boolean,
    progress: PreloadProgress,
    problem: ConnectivityUiState?,
    onRetry: () -> Unit,
    onOpenServerConfig: () -> Unit,
    onSetup: () -> Unit,
    onSkip: () -> Unit
) {
    val palette = LocalResolvedPalette.current
    AnimatedVisibility(
        visible = visible,
        // 入场不做动画（它是冷启动第一帧，淡入只会让人觉得"白了一下"）；
        // 离场淡出，与下面已经渲染好的界面交叠。
        enter = fadeIn(tween(0)),
        exit = fadeOut(tween(UfiMotion.Duration.Gentle))
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                // 必须是不透明底：浮层下面是已经组合好的导航图，半透明会露出内容在跳。
                .background(palette.pageBg),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                if (problem == null) {
                    LoadingContent(progress = progress)
                } else {
                    ProblemContent(
                        problem = problem,
                        onRetry = onRetry,
                        onOpenServerConfig = onOpenServerConfig,
                        onSetup = onSetup,
                        onSkip = onSkip
                    )
                }
            }
        }
    }
}

/** 「加载中」态：呼吸弧 + 当前步骤 + 计数。 */
@Composable
private fun LoadingContent(progress: PreloadProgress) {
    val palette = LocalResolvedPalette.current
    Text(
        text = "UFI-AXIS",
        style = UfiTextStyles.headerTitle,
        color = palette.textPrimary
    )
    Spacer(Modifier.height(Spacing.Medium))
    Text(
        text = "正在连接设备",
        style = UfiTextStyles.noteLead,
        color = palette.textSecondary
    )

    Spacer(Modifier.height(44.dp))

    UfiLoadingIndicator(modifier = Modifier.size(44.dp))

    Spacer(Modifier.height(Spacing.XLarge))

    Text(
        text = progress.label,
        style = UfiTextStyles.note,
        color = palette.textSecondary,
        textAlign = TextAlign.Center
    )
    Spacer(Modifier.height(Spacing.Small))
    Text(
        // 等宽：步数每跳一格宽度都一样，不会让上面那行文字左右抖
        text = "${progress.issued} / ${progress.total}",
        style = UfiTextStyles.monoCaption,
        color = palette.textSecondary
    )
}

/**
 * 「连不上」态：病因图标 + 标题 + 指引 + 动作。
 *
 * 动作分两套，按 `needsSetup` 分流 —— 从没配过地址的用户点「重试」毫无意义，
 * 该把他送进配对流程；已经配过的用户则是「重试」优先、改地址次之。
 * 底下那行「先进入应用」两套都有：不给出口就等于把用户锁在这一页上
 * （手机没联网 / 要去改 WiFi，都不是在这一页能解决的）。
 */
@Composable
private fun ProblemContent(
    problem: ConnectivityUiState,
    onRetry: () -> Unit,
    onOpenServerConfig: () -> Unit,
    onSetup: () -> Unit,
    onSkip: () -> Unit
) {
    val palette = LocalResolvedPalette.current
    // 只用 material-icons-**core** 里的图标：`:app` 模块没有依赖 material-icons-extended
    //（那是各 feature 模块才有的），用 CloudOff / WifiOff / Link 会直接编译不过。
    // 「没配地址」是去设置的事，其余两种都是异常，所以就这两个图标。
    val icon = if (problem.needsSetup) Icons.Default.Settings else Icons.Default.Warning

    Box(
        modifier = Modifier
            .size(72.dp)
            .background(palette.error.copy(alpha = 0.10f), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = palette.error,
            modifier = Modifier.size(32.dp)
        )
    }

    Spacer(Modifier.height(Spacing.XLarge))

    Text(
        text = problem.title ?: "无法连接到设备",
        style = UfiTextStyles.screenTitle,
        color = palette.textPrimary,
        textAlign = TextAlign.Center
    )
    Spacer(Modifier.height(Spacing.Medium))
    Text(
        // detail 已经按病因给出指引；兜底文案刻意保持中立，不提"健康检查接口"
        // —— 手机没联网时那句话是错的（根本没发出请求）。
        text = problem.detail ?: "检查设备是否开机、与手机在同一网络，然后重试。",
        style = UfiTextStyles.note,
        color = palette.textSecondary,
        textAlign = TextAlign.Center
    )

    Spacer(Modifier.height(32.dp))

    if (problem.needsSetup) {
        UfiButton(text = "去配对", onClick = onSetup, modifier = Modifier.fillMaxWidth())
    } else {
        UfiButton(text = "重试", onClick = onRetry, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(Spacing.Medium))
        UfiButton(
            text = "服务器设置",
            variant = UfiButtonVariant.Secondary,
            onClick = onOpenServerConfig,
            modifier = Modifier.fillMaxWidth()
        )
    }

    Spacer(Modifier.height(Spacing.Medium))

    UfiButton(
        text = "先进入应用",
        variant = UfiButtonVariant.Subtle,
        size = UfiButtonSize.Small,
        onClick = onSkip
    )
}
