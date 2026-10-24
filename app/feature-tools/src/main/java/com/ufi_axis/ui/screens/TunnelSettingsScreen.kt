// [F24] STABLE-UI-API：公共组件签名已冻结，请勿在无向后兼容前提下修改；实验性组件请使用 @UfiExperimentalApi（见 UfiStableApi.kt / UfiExperimentalApi.kt）。
package com.ufi_axis.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import com.ufi_axis.ui.components.common.UfiPageBackground
import com.ufi_axis.ui.components.common.UfiScreenScaffold
import com.ufi_axis.ui.components.common.UfiSettingsChevron
import com.ufi_axis.ui.components.common.UfiSettingsItem
import com.ufi_axis.ui.components.common.UfiSettingsRowCard
import com.ufi_axis.ui.navigation.Routes
import com.ufi_axis.ui.theme.Spacing
import com.ufi_axis.viewmodel.MainViewModel

/**
 * 内网穿透通用设置 —— **入口列表页**（2026-10-14 由"一页装全部"拆成入口 + 三个子页）。
 *
 * 拆页判据是**一件事一页**：这一页此前同时装了三件互不相干的事 ——
 *  ① 核心组件的版本 / 下载安装 / 卸载（重操作，带进度与轮询）；
 *  ② 看护与重连（用户设定项，写 core 的 `AppSettings`）；
 *  ③ 日志动作与一堆只读路径说明。
 * 三件事的**数据来源与刷新节奏完全不同**（① 要 1.5s 拉清单 + 1s 轮询安装任务、
 * ② 只需一次带新鲜度闸门的设置读取、③ 什么都不用拉），挤在一页的结果是：
 * 用户只想改"断开自动重连"，却要先滚过整张组件清单。
 *
 * ## 本页只做入口
 * 一行一个子页（[UfiSettingsItem] + [UfiSettingsChevron]），行内文案带上"点进去能看到什么"
 * 的即时状态（已装组件数 / 看护条数），让"要不要点进去"在这一页就能判断 ——
 * 不带信息的死入口与带信息的入口在界面上分不出来。
 *
 * ## 为什么保留 [Routes.DETAIL_TUNNEL_SETTINGS] 这条路由
 * `TunnelScreen` 顶栏那颗齿轮指向的就是它。路由不变 ⇒ 调用点不动；
 * 三个子页各自一条新路由，返回一律回到本页（再由本页返回 `TunnelScreen`）。
 *
 * ## 数据加载
 * 本页仍照原样调 `clearError` / `loadTunnelSettings` / `loadComponents`（幂等）：
 * 行内描述要用组件清单与看护名单，同时"进设置就把上一条隧道错误清掉"这个行为不能丢
 * （`TunnelModule.loadTunnelSettings` 的新鲜度闸门注释里点名依赖本页每次进页面都调它）。
 * 子页各自再拉自己需要的那一份，重复调用由 module 侧的闸门吸收。
 *
 * ## 版式
 * 一项一张 [UfiSettingsRowCard]，间距走 [Spacing.SettingsCardGap] —— 与网络设置页同构，
 * **不要**改用 `UfiGroupHeader` 把几项并回一张卡（那是本页 2026-09-03 明确退掉的形态）。
 */
@Composable
fun TunnelSettingsScreen(viewModel: MainViewModel, navController: NavHostController) {
    val state by viewModel.tunnelState.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.tunnel.clearError()
        viewModel.tunnel.loadTunnelSettings()
        viewModel.tunnel.loadComponents()
    }

    val installedCount = state.components.count { it.installed }
    val componentCount = state.components.size
    val guardedCount = state.tunnelGuardedFrp.size + state.tunnelGuardedCf.size

    UfiScreenScaffold(
        title = "内网穿透设置",
        navController = navController,
        showBack = true
    ) { padding ->
        UfiPageBackground(modifier = Modifier.padding(padding)) {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.SettingsCardGap)) {

                // ── 核心组件（2026-10-14 起指向「资源中心」）──
                // frpc / cloudflared 是内网穿透功能的**核心依赖**，缺了对应二进制这一类隧道根本起不来。
                //
                // 2026-10-14：原「组件管理」整页（TunnelSettingsComponentsScreen）已由
                // **资源中心**（Routes.DETAIL_RESOURCES）取代 —— 组件与 Web 前端 ZIP 的
                // "查状态 / 下载 / 卸载 / 上传"本来就是同一套形状，收在一页之后
                // 新增一类资源只需写一条 ResourceDescriptor（不写新屏幕、不写新分支）。
                // 本行**只改跳转目标**：行内那句"已安装 N / M"仍然来自 state.components，
                // 而本页进页面时就调了 loadComponents()，所以从资源中心装完组件回来数字是新的。
                // 原页文件保留在工作区（不在本次写权限内，且是别人未提交的新文件），但界面上
                // 已经没有通往它的入口 —— 路由常量与 AppScreens 的注册按"只追加不删改"的
                // 约定原样留着，清理留给后续单独一次改动。
                UfiSettingsRowCard {
                    UfiSettingsItem(
                        title = "前往资源中心",
                        description = when {
                            componentCount > 0 -> "frpc / cloudflared：已安装 $installedCount / $componentCount 个；检查更新、下载安装、卸载与 Web 前端资源都在那里"
                            state.componentManifestLoading -> "正在获取组件清单…"
                            else -> "frpc / cloudflared 与 Web 前端资源的下载、更新与卸载"
                        },
                        icon = Icons.Default.SystemUpdate,
                        trailing = { UfiSettingsChevron() },
                        onClick = { navController.navigate(Routes.DETAIL_RESOURCES) }
                    )
                }

                // ── 通知与重连 ──
                // 三项都由后端 AppSettings 持久化：看护协程在 core 里按间隔巡检"期望在跑但没在跑"的实例，
                // 失败通知由 app 端在 /status 出现 Running→Error 边沿时发出。
                UfiSettingsRowCard {
                    UfiSettingsItem(
                        title = "连接与重连",
                        description = if (guardedCount > 0) {
                            "看护中 $guardedCount 条 · 启动失败通知、断开自动重连与巡检间隔"
                        } else {
                            "启动失败通知、断开自动重连与巡检间隔"
                        },
                        icon = Icons.Default.RestartAlt,
                        trailing = { UfiSettingsChevron() },
                        onClick = { navController.navigate(Routes.DETAIL_TUNNEL_SETTINGS_RECONNECT) }
                    )
                }

                // ── 日志与路径 ──
                // 「清除日志」是动作，保留策略 / 查看方式是只读说明，隧道类型与两条配置文件路径同属
                // "这台设备把东西放在哪"这一类；它们都不需要轮询，合成一页最省事。
                UfiSettingsRowCard {
                    UfiSettingsItem(
                        title = "日志与路径",
                        description = "清除日志、日志保留与查看方式；隧道类型与配置文件 / token 路径",
                        icon = Icons.AutoMirrored.Filled.Article,
                        trailing = { UfiSettingsChevron() },
                        onClick = { navController.navigate(Routes.DETAIL_TUNNEL_SETTINGS_LOG) }
                    )
                }
            }

            Spacer(Modifier.height(Spacing.Medium))
        }
    }
}
