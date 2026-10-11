package com.ufi_axis.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.ufi_axis.ui.components.common.*
import com.ufi_axis.ui.theme.LocalResolvedPalette
import com.ufi_axis.ui.theme.Spacing
import com.ufi_axis.ui.theme.ufiStandardCard
import com.ufi_axis.viewmodel.MainViewModel
import com.ufi_axis.viewmodel.state.deviceUnsupportedNote
import com.ufi_axis_core.contract.Capability
import com.ufi_axis_core.contract.NetworkMode

/**
 * 蜂窝网络高级设置（二级页面）
 *
 * 从网络主屏「蜂窝网络高级设置」单一入口进入，
 * 展示网络模式 / 频段锁定 / 基站信息 / DHCP 设置 / 网络功能等子入口。
 *
 * 2026-08-27：
 *  - 补 `showBack = true`。本模块其它二级页（频段锁定 / 网络模式 / 基站信息）都传了，
 *    只有这里和网络功能页漏了，进来之后没有返回箭头。
 *  - DHCP 设置由二级页面改回弹窗（[DhcpSettingsDialog]），路由 `detail/dhcp-settings` 一并删除。
 */
@Composable
fun CellularAdvancedScreen(viewModel: MainViewModel, navController: NavHostController) {
    val deviceSettingsState by viewModel.deviceSettingsState.collectAsState()
    val roamingEnabled = deviceSettingsState.settings?.roamingEnabled ?: false
    var showDhcpDialog by remember { mutableStateOf(false) }

    // 2026-10-10：网络模式改为弹窗（原先是独立页面 detail/network-mode）。
    var showNetworkModeDialog by remember { mutableStateOf(false) }

    // 能力门禁：与原 NetworkModeScreen 同一口径 —— 设备不支持 NETWORK_MODE 时点不开。
    // ⚠ 能力集未拉到 / 拉失败时 supports() 恒为 true，保持现状照旧可点（原页面也是这么做的，
    // 见其 KDoc；改成 false 会让「能力集还没到」的用户永远点不开）。
    val capabilities by viewModel.network.capabilityState.collectAsState()
    val modeSupported = capabilities.supports(Capability.NETWORK_MODE)

    // 2026-10-11：「网络功能」入口删除，FOTA / 文件共享(SAMBA) 两个开关并入本页
    // （原 NetworkFeaturesScreen 只有这两个开关，为两个开关单开一个二级页不值当）。
    // 能力门禁口径与原页一致：core 对 /api/device/fota 与 /api/device/samba 各有 route 门禁，
    // 缺能力回 501，这里前置到 UI，开关在点之前就灰掉。
    val fotaSupported = capabilities.supports(Capability.FOTA)
    val sambaSupported = capabilities.supports(Capability.SAMBA)
    // fotaAutoUpdateOn 为 null = 设备没报这个字段，此时按「未禁用」显示。
    val fotaOff = deviceSettingsState.settings?.fotaAutoUpdateOn?.let { !it } ?: false
    var sambaOn by remember { mutableStateOf(false) }
    // SAMBA 的真值从 settings 回显；fotaOff 上面已从同一处算出。
    LaunchedEffect(deviceSettingsState.settings) {
        deviceSettingsState.settings?.let { sambaOn = it.sambaOn }
    }

    // 进入时加载 deviceSettings（漫游开关 + 网络模式当前制式都依赖此数据）
    LaunchedEffect(Unit) { viewModel.network.loadDeviceSettings() }

    // 入口行副标题就地显示当前制式，省掉「进页面才知道现在是什么」这一步。
    // 真源与弹窗内一致：优先 API 回的 network_mode_label，缺省时用 contract 映射。
    val networkState by viewModel.networkState.collectAsState()
    val networkModeCurrentLabel = deviceSettingsState.settings?.networkModeLabel
        ?: networkModeLabel(
            networkState.pendingNetworkMode
                ?: NetworkMode.fromBearer(netModeOf(deviceSettingsState.settings))
        )
    // 「切换中」优先于回读值：设备重新注册期间 /api/device/settings 仍报**旧**档位，
    // 直接按回读值显示会出现"点了 5G、这行还是 4G，过一会儿才自己变对"。
    val networkModeSwitching = networkState.pendingNetworkMode != null
    val networkModeTimedOut = networkState.modeSwitchTimedOut

    UfiScreenScaffold(
        title = "蜂窝网络高级设置",
        navController = navController,
        showBack = true
    ) { padding ->
        val palette = LocalResolvedPalette.current

        Column(
            Modifier
                .padding(padding)
                .fillMaxWidth()
                .padding(horizontal = Spacing.CardHorizontalMargin),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Spacer(Modifier.height(8.dp))

            // ═══ 数据漫游开关（独立行卡片） ═══
            Box(Modifier.ufiStandardCard().padding(horizontal = 16.dp, vertical = 4.dp)) {
                UfiSettingsToggle(
                    icon = Icons.Default.Public,
                    title = "数据漫游",
                    description = if (roamingEnabled) "漫游中" else "已关闭",
                    checked = roamingEnabled,
                    onCheckedChange = { viewModel.network.setRoamingEnabled(it) }
                )
            }

            // 设备不支持网络制式切换时，合一卡片里的那一行会被置灰（见 NavRowItem 的 enabled）。
            // 这里补一句原因 —— 只说「设备不支持」，不说"功能未开启"或"权限不足"：
            // 那两句会把用户引向"我去哪儿开一下"，而这台设备压根没有这个能力，没地方可开。
            // 与原 NetworkModeScreen 的口径一致。
            if (!modeSupported) {
                UfiNoticeCard(message = deviceUnsupportedNote("切换网络制式"))
            }

            // ═══ 网络模式 / 频段锁定 / APN / 基站信息（合一卡片） ═══
            Box(Modifier.ufiStandardCard().padding(16.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    // 网络模式
                    //
                    // 2026-10-10 用户裁决：不再跳独立页面，改为**点开弹窗**（当前制式 + 可切换）。
                    // 独立页原本只有两块内容（英雄卡 + 连接模式磁贴）却占掉整屏，浪费；
                    // 而「变更网络制式」本来就是弹窗交互，跳页只是多一次无意义的层级跳转。
                    // 下拉用公共 [UfiDropdown]（全站唯一实现，见 WifiSettingsDialog 的同款用法）。
                    UfiSettingsItem(
                        icon = Icons.Default.SettingsInputAntenna,
                        title = "网络模式",
                        enabled = modeSupported,
                        description = "当前 ${networkModeCurrentLabel}",
                        onClick = { showNetworkModeDialog = true }
                    )

                    HorizontalDivider(color = palette.divider.copy(alpha = 0.3f))

                    // 频段锁定
                    UfiSettingsItem(
                        icon = Icons.Default.Tune,
                        title = "频段锁定",
                        description = "锁定指定 LTE / NR 频段",
                        onClick = { navController.navigate("detail/band-lock") }
                    )

                    HorizontalDivider(color = palette.divider.copy(alpha = 0.3f))

                    // 基站信息
                    UfiSettingsItem(
                        icon = Icons.Default.GpsFixed,
                        title = "基站信息",
                        description = "查看/锁定邻区基站",
                        onClick = { navController.navigate("detail/cell-lock") }
                    )
                }
            }

            // ═══ DHCP 设置 ═══
            //
            // 2026-10-11：改用公共 [UfiSettingsRowCard] + [UfiSettingsItem]。
            // 原先这里是 `Box(ufiStandardCard().padding(16.dp))` 手搓卡壳 + 私有
            // `NavRowItem` 手搓行，两者都是绕过公共组件的私有实现：
            //   - NavRowItem 的 padding(vertical = 8.dp) / width(12.dp) / size(24.dp)
            //     全是硬编码 dp，绕开了主题 token，全站其他设置行都不是这个内边距；
            //   - UfiSettingsRowCard 的 KDoc 明确写了「别再各自手搓
            //     Box(ufiStandardCard(...).clip(...).background(...))」。
            // [UfiSettingsItem] 已覆盖 icon/title/description/onClick/trailing/enabled，
            // 且它 2026-09-08 就修过「enabled=false 时必须吞掉点击」的假开关问题。
            UfiSettingsRowCard {
                UfiSettingsItem(
                    icon = Icons.Default.Link,
                    title = "DHCP 设置",
                    description = "局域网地址分配与租约配置",
                    onClick = { showDhcpDialog = true }
                )
            }

            // ═══ FOTA / 文件共享（原「网络功能」二级页的两个开关，并入本页） ═══
            //
            // 2026-10-11：删除「网络功能」入口与 detail/network-features 路由。
            // 原 NetworkFeaturesScreen 只有这两个开关，为两个开关单开一个二级页不值当；
            // 能力门禁与回显口径完全沿用原页（见文件头部状态声明处的说明）。
            UfiSettingsRowCard {
                // FOTA：置灰时保留原说明、原因另起一行 —— 开关的 checked 仍是设备真值
                // （不支持 ≠ 关着，本仓禁止假开关）。
                UfiSettingsToggle(
                    icon = Icons.Default.SystemUpdate,
                    title = "禁用 FOTA",
                    description = if (fotaSupported) {
                        "阻止运营商推送更新"
                    } else {
                        "阻止运营商推送更新\n" + deviceUnsupportedNote("运营商自动升级（FOTA）")
                    },
                    checked = fotaOff,
                    onCheckedChange = { viewModel.network.setFotaDisabled(it) },
                    enabled = fotaSupported
                )
                UfiDivider()
                UfiSettingsToggle(
                    icon = Icons.Default.FolderShared,
                    title = "文件共享 (SAMBA)",
                    description = if (sambaSupported) {
                        "SMB 局域网文件共享"
                    } else {
                        "SMB 局域网文件共享\n" + deviceUnsupportedNote("文件共享（SAMBA）")
                    },
                    checked = sambaOn,
                    onCheckedChange = { sambaOn = it; viewModel.network.setSambaSetting(it) },
                    enabled = sambaSupported
                )
            }

            Spacer(Modifier.height(32.dp))
        }

        DhcpSettingsDialog(
            viewModel = viewModel,
            visible = showDhcpDialog,
            onDismiss = { showDhcpDialog = false }
        )

        // 网络模式弹窗：当前制式 + UfiDropdown 切换。2026-10-10 由独立页面收进这里。
        NetworkModeDialog(
            viewModel = viewModel,
            visible = showNetworkModeDialog,
            onDismiss = { showNetworkModeDialog = false }
        )
    }
}
