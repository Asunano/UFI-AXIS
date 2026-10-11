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
import com.ufi_axis.ui.theme.Spacing
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
 *
 * 2026-10-11（布局第三次）：
 *  - 四个二级入口（网络模式 / 频段锁定 / 基站信息 / DHCP）改用 [UfiEntryCard] 且**各自独立成卡**，
 *    不再挤在一张卡里用分隔线分（用户裁决「改成 UfiEntryCard，同时改成独立，不要分组」）。
 *  - 根 Column **不再**加 `padding(horizontal = CardHorizontalMargin)`：
 *    [UfiSettingsRowCard] 自带同样的外边距，两者叠加成 32dp，开关行离屏幕边远得离谱
 *    （用户反馈「DHCP / 禁用 FOTA / 文件共享 左右间隔过大」）；[UfiEntryCard] 走的又是
 *    **内**边距，两种卡混排时左右边界天然不对齐。边距统一交给各卡片组件自己负责。
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

        // 2026-10-11：**不再**在根 Column 加 `padding(horizontal = CardHorizontalMargin)`。
        //
        // 根因（用户反馈「DHCP / 禁用 FOTA / 文件共享 左右间隔过大」）：
        // [UfiSettingsRowCard] 内部**自带** `.padding(horizontal = Spacing.CardHorizontalMargin)`，
        // 而根 Column 又加了一层同样的 16dp → 实际 32dp，开关行的文字离屏幕边远得离谱；
        // 而 [UfiEntryCard] 走的是 `padding(Spacing.InnerPadding)`（**内**边距）不带外边距，
        // 两种卡混排时左右边界天然不对齐。
        //
        // 所以边距统一交给各卡片组件自己负责（EntryCard 不加、SettingsRowCard 自带），
        // 根 Column 只管纵向节奏。
        Column(
            Modifier
                .padding(padding)
                .fillMaxWidth(),
            // 卡间距与网络设置页一致（10dp / Spacing.SettingsCardGap）
            verticalArrangement = Arrangement.spacedBy(Spacing.SettingsCardGap)
        ) {
            Spacer(Modifier.height(8.dp))

            // ═══ 数据漫游开关（独立行卡片） ═══
            UfiSettingsRowCard {
                UfiSettingsToggle(
                    icon = Icons.Default.Public,
                    title = "数据漫游",
                    description = if (roamingEnabled) "漫游中" else "已关闭",
                    checked = roamingEnabled,
                    onCheckedChange = { viewModel.network.setRoamingEnabled(it) }
                )
            }

            // 设备不支持网络制式切换时，「网络模式」入口**整行不渲染**（见下方说明），
            // 这里补一句原因。只说「设备不支持」，不说"功能未开启"或"权限不足"：
            // 那两句会把用户引向"我去哪儿开一下"，而这台设备压根没有这个能力，没地方可开。
            // 与原 NetworkModeScreen 的口径一致。
            //
            // 2026-10-11：从「置灰那一行」改成「不渲染 + 说明」——
            // [UfiEntryCard] 没有 enabled 参数（签名已 F24 冻结），而要让它"能看不能点"
            // 就得改公共组件签名。宁可不渲染 + 说清原因，也不为绕开冻结去动公共组件。
            if (!modeSupported) {
                UfiNoticeCard(message = deviceUnsupportedNote("切换网络制式"))
            }

            // ═══ 四个二级入口：各自独立一张卡（2026-10-11） ═══
            //
            // 形态与**网络设置页完全一致**（用户裁决「参考网络设置界面」）：
            // `UfiSettingsRowCard` + `UfiSettingsItem` + `trailing = { UfiSettingsChevron() }`，
            // 卡间距 10dp。
            //
            // 2026-10-11 修正：上一轮我改成了 [UfiEntryCard]，方向错了。
            // [UfiEntryCard] 是 Surface 卡壳 + 自带右箭头 + 徽标那套（隧道/通道列表用），
            // 而全站设置类页面的入口行口径是 [UfiSettingsItem] + [UfiSettingsChevron] ——
            // 见 NetworkScreen 的「蜂窝网络高级设置」「网速测试」「在线设备」三行。
            // 同一类东西在 App 里必须是同一个组件，否则用户一眼就看出"这张不对"。
            // 入口行仍各自独立成卡、不分组（上一轮的这条裁决保留）。
            //
            // 能力门禁：`UfiSettingsItem` **有 enabled 参数**（且 2026-09-08 修过
            // 「enabled=false 必须吞掉点击」的假开关问题），所以设备不支持 NETWORK_MODE 时
            // 照旧置灰那一行 + 上面 [UfiNoticeCard] 说明原因，不必再"整行不渲染"。
            UfiSettingsRowCard {
                UfiSettingsItem(
                    icon = Icons.Default.SettingsInputAntenna,
                    title = "网络模式",
                    description = "当前 ${networkModeCurrentLabel}",
                    onClick = { showNetworkModeDialog = true },
                    enabled = modeSupported,
                    trailing = { UfiSettingsChevron() }
                )
            }
            UfiSettingsRowCard {
                UfiSettingsItem(
                    icon = Icons.Default.Tune,
                    title = "频段锁定",
                    description = "锁定指定 LTE / NR 频段",
                    onClick = { navController.navigate("detail/band-lock") },
                    trailing = { UfiSettingsChevron() }
                )
            }
            UfiSettingsRowCard {
                UfiSettingsItem(
                    icon = Icons.Default.GpsFixed,
                    title = "基站信息",
                    description = "查看/锁定邻区基站",
                    onClick = { navController.navigate("detail/cell-lock") },
                    trailing = { UfiSettingsChevron() }
                )
            }
            UfiSettingsRowCard {
                UfiSettingsItem(
                    icon = Icons.Default.Link,
                    title = "DHCP 设置",
                    description = "局域网地址分配与租约配置",
                    onClick = { showDhcpDialog = true },
                    trailing = { UfiSettingsChevron() }
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
