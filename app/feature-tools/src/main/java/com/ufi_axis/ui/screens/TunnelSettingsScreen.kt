// [F24] STABLE-UI-API：公共组件签名已冻结，请勿在无向后兼容前提下修改；实验性组件请使用 @UfiExperimentalApi（见 UfiStableApi.kt / UfiExperimentalApi.kt)。
package com.ufi_axis.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.ufi_axis.data.notification.NotificationCenter
import com.ufi_axis.data.notification.NotifyPrefs
import com.ufi_axis.ui.components.common.*
import com.ufi_axis.ui.theme.LocalResolvedPalette
import com.ufi_axis.ui.theme.Spacing
import com.ufi_axis.ui.theme.UfiTextStyles
import com.ufi_axis.viewmodel.MainViewModel
import com.ufi_axis.viewmodel.state.ComponentInfo
import kotlinx.coroutines.delay

/**
 * T30（2026-08-26）：内网穿透通用设置页。设置项全部落在后端 `AppSettings`，
 * 经 `GET/PUT /api/tunnel/settings` 读写，本页只负责显示与提交（失败回滚）。
 *
 * - **启动失败通知**：app 端在 `/status` 出现 非Error→Error 边沿时经 NotificationCenter 发出
 *   （只在 app 正轮询隧道状态时检测，后台不常驻）
 * - **断开自动重连**：core `TunnelManager` 看护协程按巡检间隔拉起"期望在跑但没在跑"的实例；
 *   手动停止的不拉起，后端重启会恢复上次在跑的实例，连续失败达上限后停手
 * - **日志**：运行期全量写入 `logs/<隧道名>.log`，停止该隧道时整份删除；此处仅提供清除按钮
 *
 * 2026-09-03 两处改造：
 * ① 布局改为全站统一的 `UfiSettingsGroup` + `UfiGroupHeader` + `UfiSettingsItem` 组合，
 *    删掉本文件自造的 SettingSectionCard / SwitchRow / SliderRow / InfoRow —— 它们和
 *    告警设置、监控设置那套公共组件是两套观感（卡片圆角、内边距、标题字号都不一致）。
 * ② 组件管理（frpc / cloudflared）不再是独立二级页，直接内联成本页第一组：
 *    它只服务于内网穿透，多一次跳转除了藏深没有别的作用。
 *
 * 2026-09-04：这一组从「可选组件」改名为「核心组件」——frpc / cloudflared 是隧道能不能起来的
 * 硬依赖，叫"可选"会让用户以为可以不装；同时把操作按钮从"独占一行的 Row"收回
 * `UfiSettingsItem(trailing = …)`，恢复与同页其它设置行一致的行距。
 */
@Composable
fun TunnelSettingsScreen(viewModel: MainViewModel, navController: NavHostController) {
    val state by viewModel.tunnelState.collectAsState()
    val context = LocalContext.current
    val palette = LocalResolvedPalette.current
    var toastMessage by remember { mutableStateOf<ToastMessage?>(null) }
    var showClearLogDialog by remember { mutableStateOf(false) }
    var pendingUninstall by remember { mutableStateOf<String?>(null) }

    // 设置以后端 AppSettings 为准，进页面拉一次（否则显示的可能是上一次会话的默认值）
    // 组件列表也在这里"直接加载"：core 侧 refresh=false 只读本地安装状态，不再同步打外网，
    // 所以这次请求是快的，进页面立刻就能看到 frpc / cloudflared 两行。
    LaunchedEffect(Unit) {
        viewModel.tunnel.clearError()
        viewModel.tunnel.loadTunnelSettings()
        viewModel.tunnel.loadComponents()
    }
    // 懒加载：远端清单（最新版本 / 下载体积 / 是否有新版）由 core 在后台拉，
    // 这里每 1.5s 回读一次直到拿到 —— 用户不需要手动点「检查更新」才看到内容。
    LaunchedEffect(state.componentManifestLoading) {
        while (state.componentManifestLoading) {
            delay(1500)
            viewModel.tunnel.loadComponents()
        }
    }
    // 安装进度：只在有任务时 1s 轮询，任务落终态后 refreshComponentTask 会自己补拉列表
    LaunchedEffect(state.componentTask.active) {
        while (state.componentTask.active) {
            delay(1000)
            viewModel.tunnel.refreshComponentTask()
        }
    }

    /** 后端"期望在跑"的实例（看护依据），让用户能解释"它为什么自己起来了" */
    val guardedNames = state.tunnelGuardedFrp + state.tunnelGuardedCf
    val task = state.componentTask
    val manifestLoading = state.componentManifestLoading

    UfiScreenScaffold(
        title = "内网穿透设置",
        navController = navController,
        showBack = true
    ) { padding ->
        UfiPageBackground(modifier = Modifier.padding(padding)) {

            // ── 核心组件 ──
            // frpc / cloudflared 是内网穿透功能的**核心依赖**，缺了对应二进制这一类隧道根本起不来，
            // 只是不随 APK 分发（解压后合计约 50MB），需要用户从上游官方 release 下载，
            // core 侧做 SHA-256 校验 + arm64 ELF 体检后原子安装。
            // 本地上传兜底只在 Web 面板提供 —— app 端要走 SAF 选文件 + 几十 MB 流式上传，收益不足。
            if (state.componentManifestError.isNotBlank()) {
                UfiErrorBanner(
                    message = "更新源不可用：${state.componentManifestError}（缺少 frpc / cloudflared 时隧道无法启动，可在 Web 面板用本地上传安装）",
                    onRetry = { viewModel.tunnel.loadComponents(refresh = true) },
                    modifier = Modifier.padding(horizontal = Spacing.CardHorizontalMargin)
                )
            }
            UfiSettingsGroup {
                // 「检查更新」放标题右侧：它是这一组的次要动作，摆在卡片底部当通栏按钮
                // 会被误认成主操作，而且卡片越长它离标题越远。
                UfiGroupHeader("核心组件") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (manifestLoading) {
                            UfiLoadingIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2f)
                            Spacer(Modifier.width(6.dp))
                        }
                        UfiButton(
                            size = UfiButtonSize.Small,
                            text = "检查更新",
                            onClick = { viewModel.tunnel.loadComponents(refresh = true) },
                            enabled = !task.active && !manifestLoading
                        )
                    }
                }
                if (state.components.isEmpty()) {
                    Text(
                        if (manifestLoading) "正在获取组件清单…" else "没读到组件信息，可点右上角「检查更新」重试；未安装 frpc / cloudflared 前对应隧道无法启动。",
                        style = UfiTextStyles.note,
                        color = palette.textSecondary
                    )
                }
                state.components.forEachIndexed { index, c ->
                    if (index > 0) UfiDivider()
                    UfiSettingsItem(
                        title = c.name,
                        description = buildComponentDetail(c, manifestLoading),
                        // 操作按钮回到 UfiSettingsItem 的 trailing 槽（与本页其它设置行一致）：
                        // size = Small 的按钮本身很窄，「更新」+「卸载」两颗用 spacedBy(6.dp) 并排也塞得下。
                        // 之前把按钮拆成"独占一行右对齐的 Row"，一个组件就变成
                        // 设置行(自带 vertical 10dp) + 按钮行 + 消息/进度行，再叠 UfiDivider，
                        // 两行组件之间的间距被撑到同页其它设置行的两倍以上。
                        trailing = {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (!c.installed || c.updateAvailable) {
                                    UfiButton(
                                        size = UfiButtonSize.Small,
                                        text = if (c.installed) "更新" else "下载安装",
                                        onClick = {
                                            viewModel.tunnel.installComponent(c.id)
                                            toastMessage =
                                                ToastMessage("已开始下载，可离开本页", ToastType.INFO)
                                        },
                                        enabled = c.available && !task.active
                                    )
                                }
                                if (c.installed) {
                                    UfiButton(
                                        size = UfiButtonSize.Small,
                                        text = "卸载",
                                        onClick = { pendingUninstall = c.id },
                                        enabled = !task.active
                                    )
                                }
                            }
                        }
                    )
                    // 进度条与状态消息只在"这个组件正在安装"时出现：空闲态不留占位行，
                    // 行高才和同页其它 UfiSettingsItem 对齐；装完列表会自己刷新版本信息。
                    if (task.id == c.id && task.active) {
                        if (task.message.isNotBlank()) {
                            Text(
                                task.message,
                                style = UfiTextStyles.note,
                                color = palette.accent,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        UfiCompactProgressBar(
                            progress = task.percent / 100f,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }

            // ── 通知与重连 ──
            // 三项都由后端 AppSettings 持久化：看护协程在 core 里按间隔巡检"期望在跑但没在跑"的实例，
            // 失败通知由 app 端在 /status 出现 Running→Error 边沿时发出。
            UfiSettingsGroup {
                UfiGroupHeader("通知与重连")
                UfiSettingsToggle(
                    title = "启动失败通知",
                    description = "打开隧道页面时检测到某条隧道从运行中变为失败，就发一条系统通知（每次断开只发一条）。" +
                        "app 没在轮询隧道状态时不会检测。受设置→通知与守护里的「系统通知推送」总开关约束。",
                    checked = state.tunnelNotifyOnFailure,
                    onCheckedChange = { on ->
                        viewModel.tunnel.setNotifyOnFailure(on)
                        // 只检测总闸；通知权限本身已由「系统通知推送」那一处统一接管，不在这里重复检测
                        if (on && !NotifyPrefs.switchOn(
                                context, NotificationCenter.KEY_ALERT_NOTIF, false
                            )
                        ) {
                            toastMessage = ToastMessage(
                                text = "「系统通知推送」总开关未开启，隧道通知发不出来",
                                type = ToastType.WARNING,
                                durationMs = 5000L,
                                subtitle = "去 设置 → 通知与守护 → 告警设置 打开"
                            )
                        }
                    }
                )
                UfiDivider()
                UfiSettingsToggle(
                    title = "断开自动重连",
                    description = "进程意外退出后按下方间隔自动拉起；后端服务重启也会恢复上次在跑的隧道。" +
                        "连续失败 ${state.tunnelMaxReconnectAttempts} 次后停手，等手动处理。" +
                        "手动点过“停止”的隧道不会被拉起。",
                    checked = state.tunnelAutoReconnect,
                    onCheckedChange = { viewModel.tunnel.setAutoReconnect(it) }
                )
                UfiDivider()
                UfiSettingsItem(
                    title = "巡检间隔",
                    description = "看护协程多久检查一次隧道是否还活着",
                    enabled = state.tunnelAutoReconnect
                )
                UfiSlider(
                    value = state.tunnelReconnectIntervalSec.toFloat(),
                    onValueChange = { viewModel.tunnel.setReconnectInterval((it.toInt() / 10) * 10) },
                    valueRange = 10f..120f,
                    steps = 10,
                    valueLabel = "${state.tunnelReconnectIntervalSec} 秒",
                    enabled = state.tunnelAutoReconnect
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("10 秒", style = UfiTextStyles.caption, color = palette.textSecondary)
                    Text("120 秒", style = UfiTextStyles.caption, color = palette.textSecondary)
                }
                if (guardedNames.isNotEmpty()) {
                    UfiDivider()
                    UfiInfoRow(label = "看护中", value = guardedNames.joinToString("、"))
                }
            }

            // ── 日志 ──
            UfiSettingsGroup {
                UfiGroupHeader("日志") {
                    UfiButton(size = UfiButtonSize.Small, text = "清除日志", onClick = { showClearLogDialog = true })
                }
                UfiSettingsItem(
                    title = "保留策略",
                    description = "运行期全量写入日志文件，停止该隧道时整份删除"
                )
                UfiDivider()
                UfiSettingsItem(
                    title = "查看方式",
                    description = "隧道页默认显示最近 200 行；点“查看完整”读日志文件（最多尾部 256KB）"
                )
                UfiDivider()
                UfiInfoRow(label = "日志文件", value = "files/<引擎>/logs/<隧道名>.log")
            }

            // ── 关于 ──
            UfiSettingsGroup {
                UfiGroupHeader("关于")
                UfiSettingsItem(
                    title = "隧道类型",
                    description = "FRP · Cloudflare Tunnel（可同时运行多条）"
                )
                UfiDivider()
                UfiInfoRow(label = "FRP 配置", value = "files/frp/configs/<隧道名>.toml")
                UfiInfoRow(label = "CF token", value = "files/cloudflared/tunnels/<隧道名>.token")
            }

            Spacer(Modifier.height(Spacing.Medium))
        }

        UfiToastHost(toastMessage = toastMessage, onDismiss = { toastMessage = null })
    }

    UfiConfirmDialog(
        visible = showClearLogDialog,
        title = "确认清除",
        text = "将清除 FRP 与 Cloudflare Tunnel 的全部日志缓冲，无法恢复。继续？",
        confirmText = "清除",
        destructive = true,
        onConfirm = {
            viewModel.tunnel.clearLogs()
            showClearLogDialog = false
            toastMessage = ToastMessage("日志已清除", ToastType.SUCCESS)
        },
        onDismiss = { showClearLogDialog = false }
    )

    pendingUninstall?.let { id ->
        UfiConfirmDialog(
            visible = true,
            title = "卸载 $id",
            text = "会删除该二进制与元数据。隧道配置不受影响，但在重新安装前无法启动。",
            confirmText = "卸载",
            destructive = true,
            onConfirm = {
                viewModel.tunnel.uninstallComponent(id)
                pendingUninstall = null
            },
            onDismiss = { pendingUninstall = null }
        )
    }
}

/**
 * 组件副标题：已装看占用与来源，未装看需下载多少。
 *
 * @param manifestLoading 远端清单还在后台拉：此时"更新源无可用版本"只是"还没拿到"，
 *   不能直接报无可用版本，否则用户以为坏了。
 */
private fun buildComponentDetail(c: ComponentInfo, manifestLoading: Boolean = false): String {
    val parts = mutableListOf<String>()
    if (c.installed) {
        parts += "已安装 " + c.installedVersion.ifBlank { "版本未知" }
        if (c.installedSize > 0) parts += com.ufi_axis.util.FormatUtils.formatBytes(c.installedSize)
        when (c.source) {
            "manual" -> parts += "本地上传"
            "legacy" -> parts += "旧版迁移"
        }
        if (c.updateAvailable) parts += "有新版 ${c.latestVersion}"
        else if (manifestLoading) parts += "正在检查新版本…"
    } else {
        parts += "未安装"
        if (c.downloadSize > 0) parts += "需下载约 " + com.ufi_axis.util.FormatUtils.formatBytes(c.downloadSize)
        if (!c.available) parts += if (manifestLoading) "正在获取最新版本…" else "更新源无可用版本"
    }
    return parts.joinToString(" · ")
}
