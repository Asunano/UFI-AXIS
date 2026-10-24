// [F24] STABLE-UI-API：公共组件签名已冻结，请勿在无向后兼容前提下修改；实验性组件请使用 @UfiExperimentalApi（见 UfiStableApi.kt / UfiExperimentalApi.kt）。
package com.ufi_axis.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.ufi_axis.ui.components.common.*
import com.ufi_axis.ui.navigation.Routes
import com.ufi_axis.ui.theme.LocalResolvedPalette
import com.ufi_axis.ui.theme.Spacing
import com.ufi_axis.ui.theme.UfiCardDefaults
import com.ufi_axis.ui.theme.UfiTextStyles
import com.ufi_axis.viewmodel.MainViewModel
import kotlinx.coroutines.delay

/**
 * FIX-9（2026-08-23）：内网穿透主页改为入口卡片式列表（两个独立入口，无通用设置卡）。
 *
 * - **不再使用 TabRow**：FRP / Cloudflare Tunnel 各自独立详情页（DETAIL_TUNNEL_FRP / DETAIL_TUNNEL_CF）。
 * - **主页两张入口卡片**：左侧品牌色块圆 + 大图标（Hub / Cloud），右侧标题 + 版本或未安装状态。
 * - **顶部工具栏右上角「停止全部」按钮**：有任意实例在运行时显示，一键停掉两个引擎的全部实例。
 * - **5s 自动刷新**：保留原行为，loadStatus() 周期轮询。
 *
 * 2026-09-02：组件管理整块移到「内网穿透设置」页（Routes.DETAIL_COMPONENTS，常驻入口），本页不再
 * 拉组件清单也不再轮询安装任务；入口卡片只保留「标题 + 版本/未安装」，去掉产品介绍类副标题
 * 与隧道计数详情行 —— 隧道数量在各自的列表页里一眼可见，主页重复一遍只是噪声。
 */
@Composable
fun TunnelScreen(viewModel: MainViewModel, navController: NavHostController) {
    val state by viewModel.tunnelState.collectAsState()
    val palette = LocalResolvedPalette.current
    var toastMessage by remember { mutableStateOf<ToastMessage?>(null) }

    LaunchedEffect(Unit) {
        viewModel.tunnel.loadStatus()
        viewModel.tunnel.loadConfigs()
    }
    // 5s 自动刷新（保留原行为）
    LaunchedEffect(Unit) {
        while (true) {
            delay(5000)
            viewModel.tunnel.loadStatus()
        }
    }

    UfiScreenScaffold(
        title = "内网穿透",
        navController = navController,
        showBack = true,
        actions = {
            if (state.anyRunning) {
                IconButton(onClick = {
                    viewModel.tunnel.stopAll()
                    // 不能在这里报"已停止"：强杀失败时后端会回 success=false，
                    // 真实结果由 errorMessage 横幅 / 下一轮 loadStatus 呈现
                    toastMessage = ToastMessage("正在停止所有隧道…", ToastType.INFO)
                }) {
                    Icon(Icons.Default.Stop, contentDescription = "停止全部")
                }
            }
            // 通用设置（通知/重连/日志）此前没有任何入口，路由注册了却进不去
            IconButton(onClick = { navController.navigate(Routes.DETAIL_TUNNEL_SETTINGS) }) {
                Icon(Icons.Default.Settings, contentDescription = "隧道设置")
            }
        }
    ) { padding ->
        // 2026-10-13：本页**不再**给 `contentHPadding`（原为 PagePadding = 12dp）。
        // 横向留白改由组件自带：TunnelEntryCard 已内建 CardHorizontalMargin（16dp）。
        // 两种口径混排会左右差 4dp（16 vs 12），所以必须整页一起换。
        // 注意 UfiErrorBanner **不需要**调用点补任何横向 padding：它是 Popup，横向位置由
        // UfiBannerDefaults.sideMargin 独立算出，传了反而会让长文案左右不对称（见其 KDoc）。
        UfiPageBackground(modifier = Modifier.padding(padding)) {
            // 平级入口卡的纵向间距走 Spacing.SettingsCardGap（与网络设置页/内网穿透设置页同档），
            // 原来是就地写死的 12.dp。
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = Spacing.Large),
                verticalArrangement = Arrangement.spacedBy(Spacing.SettingsCardGap)
            ) {
                state.errorMessage?.let { msg -> UfiErrorBanner(message = msg) }

                // ── FRP 入口卡片 ──
                TunnelEntryCard(
                    title = "FRP",
                    icon = Icons.Default.Hub,
                    iconTint = palette.accent,
                    running = state.frpRunningCount > 0,
                    installed = state.frpInstalled,
                    version = state.frpVersion,
                    onClick = { navController.navigate(Routes.DETAIL_TUNNEL_FRP) },
                    // 2026-10-14：未安装时的快捷安装入口（跳到资源中心）。
                    // 从前这里只写一个"未安装"就没了，而整张卡的点击**一律**进隧道详情页 ——
                    // 详情页里能新建/改配置，却没有任何一处能装二进制，用户只能自己猜到
                    // 「隧道设置 → 组件管理」去。现在这一层就把路指出来。
                    onInstallClick = { navController.navigate(Routes.DETAIL_RESOURCES) }
                )

                // ── Cloudflare Tunnel 入口卡片 ──
                TunnelEntryCard(
                    title = "Cloudflare Tunnel",
                    icon = Icons.Default.Cloud,
                    iconTint = palette.accentSecondary,
                    running = state.cfRunningCount > 0,
                    installed = state.cfInstalled,
                    version = state.cfVersion,
                    onClick = { navController.navigate(Routes.DETAIL_TUNNEL_CF) },
                    onInstallClick = { navController.navigate(Routes.DETAIL_RESOURCES) }
                )

                Spacer(Modifier.height(Spacing.Medium))
                UfiToastHost(toastMessage = toastMessage, onDismiss = { toastMessage = null })
            }
        }
    }
}

/**
 * 内网穿透入口卡片：左侧品牌色块圆 + 大图标，右侧标题 + 版本/未安装，末尾操作箭头。
 *
 * 图标恒定用 [iconTint] 着色（不随运行状态变灰）：两个入口靠颜色区分身份，
 * 运行状态由标题右侧的「运行中」徽标表达，两者职责分开。
 *
 * ## 2026-10-13 收敛（此前是三处"全仓库独一份"）
 *  · `shape` 用的是 `UfiCardDefaults.dialogShape` —— 全库**唯一**拿弹窗圆角当列表卡圆角的
 *    地方（其余列表卡一律 `UfiCardDefaults.shape`），已改回 `shape`；
 *  · 内距与图标间距是裸 `14.dp`，已改 `Spacing.InnerPadding` / `Spacing.Large`（都 12dp），
 *    与同族的 `UfiEntryCard` 对齐；
 *  · 标题↔版本行之间原为裸 `3.dp`，已改 `Spacing.TextStackGap`（2dp）——
 *    同一语义在 `UfiStatusCard`/`UfiEntryCard`/`UfiListRowCard` 里都是 2dp，3dp 是孤例。
 *
 * 仍是裸字面量的三处（**没有**语义 token，不是漏改）：品牌色块 `48.dp`、块内图标 `26.dp`、
 * 「运行中」药丸的内距 `horizontal = 7.dp, vertical = 2.dp`。
 *
 * 2026-10-13：自带 [Spacing.CardHorizontalMargin]（16dp）横向外边距，与 `UfiSettingsRowCard` /
 * `UfiSettingsGroup` 对齐；**调用方不要再补水平 padding**，那会叠成 32dp 的双重内距。
 * 本组件是 private 且只有本页两个调用点，没有"已在有内距容器里"的用法，故不开例外参数。
 *
 * ## 2026-10-14：未安装时补一条安装引导
 * 此前 `installed` 只影响副标题那一个词（"未安装"），而**整张卡的点击一律进隧道详情页**
 * （`onClick`）—— 于是缺二进制时用户看到的只有"未安装"三个字，没有一句话说清后果、
 * 也没有一条路能装。现在未安装时在副标题下方补：
 *  · 一句后果说明（核心未安装 ⇒ 通道 / 隧道无法启动；**配置照常可建可改**，
 *    免得用户以为"没装就什么都干不了"）；
 *  · 一颗 [UfiButton]（Small / Secondary）直达资源中心。
 *
 * 按钮嵌在可点击的 [Surface] 里是安全的：Compose 的命中测试由最内层可点击元素先消费，
 * 点按钮不会连带触发外层的"进隧道详情"。
 */
@Composable
private fun TunnelEntryCard(
    title: String,
    icon: ImageVector,
    iconTint: Color,
    running: Boolean,
    installed: Boolean,
    version: String,
    onClick: () -> Unit,
    onInstallClick: () -> Unit
) {
    val palette = LocalResolvedPalette.current
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.CardHorizontalMargin).clickable(onClick = onClick),
        shape = UfiCardDefaults.shape,
        color = palette.cardBg,
        border = BorderStroke(1.dp, palette.cardBorder)
    ) {
        Row(
            modifier = Modifier.padding(Spacing.InnerPadding),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(UfiCardDefaults.shape)
                    .background(iconTint.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(26.dp)
                )
            }
            Spacer(Modifier.width(Spacing.Large))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        title,
                        style = UfiTextStyles.panelTitleStrong,
                        color = palette.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (running) {
                        Spacer(Modifier.width(Spacing.Medium))
                        Surface(
                            color = palette.accent.copy(alpha = 0.14f),
                            shape = UfiCardDefaults.tagShape
                        ) {
                            Text(
                                "运行中",
                                style = UfiTextStyles.caption,
                                color = palette.accent,
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
                Spacer(Modifier.height(Spacing.TextStackGap))
                Text(
                    when {
                        !installed -> "未安装"
                        version.isNotBlank() -> "v$version"
                        else -> "已安装"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                // 未安装的安装引导（2026-10-14）：说清后果 + 给一条能走的路。
                if (!installed) {
                    Spacer(Modifier.height(Spacing.Small))
                    Text(
                        "核心未安装，通道 / 隧道无法启动；配置可以照常新建与修改。",
                        style = UfiTextStyles.note,
                        color = palette.textSecondary
                    )
                    Spacer(Modifier.height(Spacing.Medium))
                    UfiButton(
                        size = UfiButtonSize.Small,
                        variant = UfiButtonVariant.Secondary,
                        text = "去资源中心安装",
                        onClick = onInstallClick
                    )
                }
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = "管理",
                tint = palette.textSecondary,
                modifier = Modifier.size(Spacing.IconSizeMedium)
            )
        }
    }
}
