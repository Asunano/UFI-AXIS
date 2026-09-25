package com.ufi_axis.ui.screens.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ufi_axis.data.model.TrafficLimitConfig
import com.ufi_axis.ui.components.common.UfiGradientSignalBar
import com.ufi_axis.ui.components.common.UfiRollingMetric
import com.ufi_axis.ui.components.common.UfiRollingText
import com.ufi_axis.ui.components.common.signalBars
import com.ufi_axis.ui.theme.GRADIENT_MID_LIGHTEN_DARK
import com.ufi_axis.ui.theme.GRADIENT_TOP_LIGHTEN_DARK
import com.ufi_axis.ui.theme.LocalResolvedPalette
import com.ufi_axis.ui.theme.StatusOnline
import com.ufi_axis.ui.theme.UfiCardDefaults
import com.ufi_axis.ui.theme.UfiTextStyles
import com.ufi_axis.ui.theme.UfiWeight
import com.ufi_axis.ui.theme.ufiCardShadow
import com.ufi_axis.ui.theme.ufiShade
import com.ufi_axis.util.FormatUtils
import com.ufi_axis.viewmodel.state.DashboardState

/**
 * 连接状态 Hero 卡 — Q1a 双主角并列（流量在左、已连接设备在右）。
 *
 * 信息架构：
 *   行1：运营商 + 设备名（纵向）  ………  已连接状态 + 信号质量条（右）
 *   行2（双主角）：
 *        左 = 本月流量（大数字 + 限额）
 *        右 = 已连接设备总数 + WiFi 信息（名称 / 频段）
 *   行3：双胶囊网速（↓下载 / ↑上传 各自独立）
 */
@Composable
fun HomeConnectionCard(
    state: DashboardState,
    modifier: Modifier = Modifier,
    // 本月流量数据源：默认用 DashboardState（dashboard/summary 的 traffic_limit），
    // 但仪表盘 summary 的 traffic_limit 字段不可靠；调用方应传入「工具-流量管理」同源的
    // viewModel.trafficManagementState.limitConfig（getTrafficLimit 端点）以保证与流量管理一致。
    trafficLimitConfig: TrafficLimitConfig? = state.trafficLimitConfig,
    // WiFi 信息数据源：与「网络-无线设置」同源（NetworkModule.refreshWifi →
    // networkState.wifiSettings / wifiClients 解析派生），调用方从仪表盘传入。
    wifiSsid: String? = null,        // WiFi 名称（热点关闭时为 null）
    wifiBand: String? = null,        // 频段："2.4GHz" / "5GHz"
    wifiClientCount: Int? = null,    // 已连接设备总数（station_list + lan_station_list）
    // ── 后端 / 实时通道状态（2026-09-05 由两张独立横幅并入本卡）──
    //
    // 原来是 `UfiOfflineBanner`（后端未连接 + 最后更新时间）与 `UfiRealtimeStatusBanner`
    //（实时通道重连中 / 已断开）两张行内 Card，排在本卡上方。问题有三：
    // 1. 断网时两者**同时**成立，两张卡叠着出现、加上错误卡最多三张，内容被压下去一百多 dp，
    //    出现/消失还让整页上下弹；
    // 2. 语义重复 —— 三个地方各讲一句"连不上"，而本卡本来就是"连接状态"卡；
    // 3. 那两个组件是全库唯一调用点，只为仪表盘存在。
    // 现在合并成本卡内部一行"服务状态"条：只在异常时出现（约 26dp），两条同时异常时
    // 只显示更严重的那条（后端未连接 > 实时通道），不再重复告知。
    backendOffline: Boolean = false,
    lastUpdatedText: String? = null,
    realtimeStatus: String? = null,
    // ── 2026-09-25：行2 双主角各自整块可点（左流量 / 右已连接设备）──
    //
    // 本卡是纯展示组件（除 palette 外不读任何 Local、不持任何状态），所以两个动作都只出回调，
    // 弹窗与它们的状态一律挂在 DashboardScreen 那一层。默认 null = 不可点（本卡在
    // Gallery / Preview 里也能单独渲染，不会因为少传回调而变成点了没反应的假入口）。
    onTrafficClick: (() -> Unit)? = null,
    onClientsClick: (() -> Unit)? = null
) {
    val palette = LocalResolvedPalette.current
    val networkStatus = state.networkStatus
    val trafficRealtime = state.trafficRealtime
    val trafficLimit = trafficLimitConfig

    // ── 网络信息 ──
    val operatorName = networkStatus?.operator?.takeIf { it.isNotBlank() } ?: "未知运营商"
    val isConnected = networkStatus?.isCellularConnected ?: false

    // ── 蜂窝制式：仅使用 signalInfo.rat（真正的蜂窝 RAT） ──
    val rawRat = state.signalInfo?.rat?.trim().orEmpty()
    val ratDisplay = when {
        rawRat.isNotBlank() -> mapRat(rawRat)
        else -> "—"
    }

    // ── 设备名 ──
    val deviceName = state.deviceInfo?.device?.model
        ?: state.deviceInfo?.identity?.get("product_name")
        ?: "UFI"

    // ── 实时网速 ──
    val rxDisplay = trafficRealtime?.rx_speed_display?.takeIf { it.isNotBlank() }
        ?: FormatUtils.formatRate(trafficRealtime?.rx_speed ?: 0L)
    val txDisplay = trafficRealtime?.tx_speed_display?.takeIf { it.isNotBlank() }
        ?: FormatUtils.formatRate(trafficRealtime?.tx_speed ?: 0L)

    // ── 月流量 ──
    val hasLimit = trafficLimit?.enabled == true
    val usedBytes = (trafficLimit?.monthly_rx_bytes ?: 0L) +
            (trafficLimit?.monthly_tx_bytes ?: 0L)
    // core 已经把设备侧的复合串换算成字节（limit_bytes），这里不再自己解析单位
    val limitBytes = if (hasLimit) trafficLimit?.limit_bytes else null
    // 只要限额开关开启就显示限额值，不再要求 limitBytes > 0（避免限额设为 0 时误显"无限额"）
    val hasLimitValid = hasLimit

    // ── 信号数据 ──
    // ── 信号数据 ──
    val rsrp = state.signalInfo?.rsrp
    val bars = signalBars(rsrp)  // 0..5 格数

    // ── 渐变：不透明明暗衍生色（阶梯口径见 theme 层的 Color.ufiShade）──
    // 深色档系数改自 GRADIENT_MID_LIGHTEN_DARK / GRADIENT_TOP_LIGHTEN_DARK 常量，理由见常量定义。
    val gradColors = if (palette.isDark) {
        listOf(
            palette.accent,
            palette.accent.ufiShade(GRADIENT_MID_LIGHTEN_DARK),
            palette.accent.ufiShade(GRADIENT_TOP_LIGHTEN_DARK)
        )
    } else {
        listOf(palette.accent, palette.accent.ufiShade(0.12f), palette.accent.ufiShade(0.22f))
    }

    val cardShape = UfiCardDefaults.shape

    // 阴影：#9CA4AC @ 30%，Y偏移4，模糊半径16（与全局 token 一致，见 UfiCardDefaults.ufiCardShadow）
    Box(
        modifier = modifier
            .fillMaxWidth()
            .ufiCardShadow(elevation = 4.dp, shape = cardShape)
            .clip(cardShape)
            .background(brush = Brush.linearGradient(colors = gradColors))
            .border(
                width = 1.dp,
                // 2026-09-04（P2-中）：原写死 `Color.White.copy(alpha = 0.18f)`。
                // 描边铺在 accent 系渐变上，换配色时渐变跟着 accent 变、这道白边不变，
                // 浅色 accent 主题下会糊成看不见的一圈残留。现接 palette.gradientMuted（默认白，观感不变），
                // alpha 仍留在调用点：本卡的弱化档从 12% 到 90% 不等，收进 token 会改观感。
                color = palette.gradientMuted.copy(alpha = 0.18f),  // 四周 1px 高光描边，定义渐变卡边缘
                shape = cardShape
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            // ═══ 行1：运营商 + 设备名 + 已连接 ═══
            //
            // ★ 2026-09-24（用户定稿："已连接和信号值图标往上移动一行"）：
            //   `CenterVertically` → `Top`。左列是**两行**（运营商+制式胶囊 / 设备名），
            //   右侧那组（连接圆点 + 文字 + 信号条）只有一行，居中时它落在左列两行的**中缝**上，
            //   看着比运营商那行低半行。改成顶对齐后它与运营商行成对。
            //   上方已经没有别的内容（行1 就是卡片内容区的第一个子项），所以"往上一行"
            //   只能靠对齐实现，减 padding 是做不到的。
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    // ── 2026-09-04（P2-中）本卡内全部前景色收敛说明 ─────────────────────
                    // 改造前：这一整卡的文字/图标/胶囊底/分隔线共 17 处各自写死 Color.White[.copy(alpha)]。
                    // 问题：卡的底是 accent 明暗三段渐变（见上方 gradColors），换配色时底色会变、
                    //       这套白色内容不会变——浅色 accent 主题下白字白图标直接糊在底上没对比度，
                    //       正是 G3「换配色能换干净」要治的残留。
                    // 现在：不透明前景 → palette.onGradient；带 alpha 的弱化前景/装饰 → palette.gradientMuted。
                    //       两槽默认值都是 Color.White，6 个预设不填 ⇒ 观感零变化；alpha 档位仍留在调用点。
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = operatorName,
                            style = UfiTextStyles.metricValueCompact,
                            color = palette.onGradient,  // 原 Color.White：运营商主标题
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        Spacer(Modifier.width(8.dp))
                        // 网络制式胶囊（5G / 4G 等），放在运营商后
                        if (ratDisplay.isNotBlank()) {
                            Surface(
                                color = palette.gradientMuted.copy(alpha = 0.18f),  // 原 Color.White 18%：胶囊半透明底
                                shape = UfiCardDefaults.tagShape
                            ) {
                                Text(
                                    text = ratDisplay,
                                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 1.5.dp),
                                    style = UfiTextStyles.label.copy(fontWeight = UfiWeight.Emphasis),
                                    color = palette.onGradient,  // 原 Color.White：胶囊内制式文字
                                    maxLines = 1
                                )
                            }
                        }
                    }
                    Text(
                        text = deviceName,
                        style = UfiTextStyles.note.copy(fontWeight = UfiWeight.Medium),
                        color = palette.gradientMuted.copy(alpha = 0.7f),  // 原 Color.White 70%：设备名副标题
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.width(10.dp))
                Row(
                    // 顶对齐之后补 2dp：左列首行是 `metricValueCompact`（大字、行高高），
                    // 右侧是 `labelSmall`。纯 Top 会让这组贴在运营商文字的**上沿**、
                    // 看着略高；2dp 把它拉到与运营商文字的光学中线齐平。
                    modifier = Modifier.padding(top = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(UfiCardDefaults.pillShape)
                            .background(
                                // 未连接态的圆点：原 Color.White 40%（弱化态 → gradientMuted）。
                                // 已连接仍用 StatusOnline 语义色，与 palette 无关，不动。
                                if (isConnected) StatusOnline
                                else palette.gradientMuted.copy(alpha = 0.4f)
                            )
                    )
                    Text(
                        text = if (isConnected) "已连接" else "未连接",
                        style = MaterialTheme.typography.labelSmall,
                        color = palette.gradientMuted.copy(alpha = 0.9f),  // 原 Color.White 90%：连接状态文字
                        maxLines = 1
                    )
                    // 信号质量条（复用下方「网络质量」同款 5 段梯形视觉；未连接时 0 格全暗）
                    Spacer(Modifier.width(2.dp))
                    // 2026-08-31：私有 GradientSignalBar → 公共 UfiGradientSignalBar（默认参数与原实现一致）
                    UfiGradientSignalBar(bars = if (isConnected) bars else 0)
                }
            }

            // ═══ 行1.5：服务状态条（仅异常时出现，见构造参数处的并入说明） ═══
            // 两条同时异常时只报更严重的那条：后端整体连不上时，"实时通道断了"是它的必然结果，
            // 再单独说一遍只是噪音。
            val serviceStatus: String? = when {
                backendOffline -> buildString {
                    append("后端服务未连接")
                    if (!lastUpdatedText.isNullOrBlank()) {
                        append(" · 最后更新 ")
                        append(lastUpdatedText)
                    }
                }
                !realtimeStatus.isNullOrBlank() -> realtimeStatus
                else -> null
            }
            if (serviceStatus != null) {
                Spacer(Modifier.height(8.dp))
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    // 与 SpeedCapsule 同一档半透明底：状态条属于本卡内部元素，不另立视觉语言。
                    color = palette.gradientMuted.copy(alpha = 0.15f),
                    shape = UfiCardDefaults.subtleShape
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(UfiCardDefaults.pillShape)
                                // 语义色（error/warning）而不是 gradientMuted：这是"出问题了"的信号，
                                // 必须和卡内其它弱化白色元素区分开。
                                .background(if (backendOffline) palette.error else palette.warning)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = serviceStatus,
                            style = UfiTextStyles.label,
                            color = palette.onGradient,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            Spacer(Modifier.height(10.dp))

            // ═══ 行2：双主角（左=流量 / 右=网络质量） ═══
            //
            // ★ 2026-09-24（用户定稿："右侧的已连接设备靠左对齐"）：间隙 12dp → 16dp。
            //   右列内容本来就是 `Alignment.Start`（贴自己那一列的左边缘），"不对齐"的其实是
            //   **两列离各自左边界的距离不等**：
            //     左列文字离卡片内容左边缘 = 卡片水平内边距 16dp（见上方 `.padding(horizontal = 16.dp)`）
            //     右列文字离分隔线       = 本 Row 的间隙 12dp
            //   把间隙提到与卡片内边距同值，两列的"起跑线缩进"就一致了。
            //   分隔线仍然居中 —— `spacedBy` 在它两侧给的是同一个值。
            //   代价：两个间隙各多 4dp，可用宽度少 8dp（两列各 4dp）。
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Max),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // —— 左：本月流量 ——
                //
                // ★ 2026-09-25（本轮）：整块可点 → 打开「流量限额设置」弹窗
                //   （与工具-流量管理里的那一个是同一份组件，见 UfiDataLimitDialog）。
                //   三件事必须这么做，别简化：
                //   1. 点击区挂在**这一列**（标签 / 读数 / 限额行 + fillMaxHeight），不是挂在
                //      数字那一个 Text 上 —— 用户心里"那一栏"是整块。
                //   2. 竖分隔线是本 Row 的**另一个子项**、不在这个 Column 里，所以天然不会被
                //      点击区吃掉。想给列加 padding 的话要先算过 weight，本轮不动行2 的几何。
                //   3. `clip` 必须排在 `clickable` **之前**：本卡底是 accent 三段渐变，不裁形状
                //      时默认 ripple 是直角矩形，压在圆角卡内侧像漏了一块。indication 保持默认
                //      （不传 null）—— MonitorOverview 的 hero 入口胶囊也是渐变底 + 默认 ripple，
                //      同一套观感。
                val limitLine = if (hasLimitValid) "/ ${gb(limitBytes!!)} GB" else "/ 无限额"
                Column(
                    modifier = Modifier
                        // ★ 2026-09-24（用户定稿："中间的分割线改成居中"）：1.1f → 1f。
                        //   分隔线是本 Row 的第二个子项，位置由两侧权重决定：1.1 : 1 时它落在
                        //   约 52.4% 处，肉眼就是"往右偏"。两边等权后它正好在中线上
                        //   （两个 12dp 间隙 + 1dp 线宽是对称扣除的，不影响居中）。
                        //
                        //   代价：左列比原来窄约 5%。左列读数是 28sp ExtraBold，
                        //   `"1234.5 GB"` 这种四位整数 + 单位在窄屏上可能触到边界。
                        //   真要出现（月流量过 1TB）再单独处理，别用回不等权 —— 那等于拿
                        //   "线偏移"换"线不偏移"。
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(UfiCardDefaults.subtleShape)
                        .then(
                            if (onTrafficClick != null) {
                                Modifier
                                    .clickable(
                                        role = Role.Button,
                                        onClickLabel = "设置流量限额",
                                        onClick = onTrafficClick
                                    )
                                    // 语义挂在**整列**上（与 §5.20 无障碍同一口径）：三行文字各自
                                    // 朗读会念成"本月流量 / 12.3 GB / 斜杠 100.0 GB"三段，
                                    // 合并成一句才读得通，且动作提示由 onClickLabel 给。
                                    .semantics(mergeDescendants = true) {
                                        contentDescription =
                                            "本月流量 ${gb(usedBytes)} GB $limitLine，点击设置限额"
                                    }
                            } else Modifier
                        ),
                    verticalArrangement = Arrangement.Top
                ) {
                    Text(
                        text = "本月流量",
                        style = MaterialTheme.typography.labelMedium,
                        color = palette.gradientMuted.copy(alpha = 0.7f),  // 原 Color.White 70%：指标标签
                        maxLines = 1
                    )
                    Spacer(Modifier.height(2.dp))
                    // 2026-09-21：滚轮读数。gb() 单位固定 GB、只有小数位在动 ⇒ 变化槽位 1~2 个，
                    // 是全库最理想的接入点（不必走 UfiRollingMetric 拆单位，"GB"本来就不变）。
                    UfiRollingMetric(
                        text = "${gb(usedBytes)} GB",
                        style = UfiTextStyles.metricValue,
                        unitStyle = UfiTextStyles.metricValue,
                        color = palette.onGradient,  // 原 Color.White：本月流量大数字
                        unitSpacing = 5.dp
                    )
                    Spacer(Modifier.height(2.dp))
                    // ★ 2026-09-24（用户定稿）：有限额分支末尾的「限额」二字删掉 ——
                    //   `"/ 100.0 GB"` 已经能读懂，"限额"是冗余修饰。
                    //   **无限额分支保持 `"/ 无限额"`**：那三个字是一体的，抽掉两个字会变成
                    //   `"/ 无"`，读不成话。
                    // 2026-09-25：文案提到上面的 `limitLine`，因为整列的无障碍描述也要用同一句 ——
                    // 两处各写一遍就会出现"看到的和读到的不一样"。
                    Text(
                        text = limitLine,
                        style = MaterialTheme.typography.labelMedium,
                        color = palette.gradientMuted.copy(alpha = 0.7f),  // 原 Color.White 70%：限额说明
                        maxLines = 1
                    )
                }

                // —— 竖分隔 ——
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .fillMaxHeight()
                        .background(palette.gradientMuted.copy(alpha = 0.25f))  // 原 Color.White 25%：双主角竖分隔线
                )

                // —— 右：已连接设备总数 + WiFi 基本信息（与左列流量对称） ——
                //
                // ★ 2026-09-25（本轮）：整块可点 → 打开「在线设备」弹窗（数据与网络设置里的
                //   在线设备页同源，见 HomeOnlineDevicesDialog）。几何与左列同一套理由，
                //   clip / indication / 分隔线的说明见左列注释，不重复。
                val clientsText = wifiClientCount?.toString() ?: "—"
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(UfiCardDefaults.subtleShape)
                        .then(
                            if (onClientsClick != null) {
                                Modifier
                                    .clickable(
                                        role = Role.Button,
                                        onClickLabel = "查看在线设备",
                                        onClick = onClientsClick
                                    )
                                    .semantics(mergeDescendants = true) {
                                        contentDescription = if (wifiClientCount == null) {
                                            "已连接设备数未知，点击查看在线设备"
                                        } else {
                                            "已连接设备 $wifiClientCount 台，点击查看在线设备"
                                        }
                                    }
                            } else Modifier
                        ),
                    horizontalAlignment = Alignment.Start,
                    verticalArrangement = Arrangement.Top
                ) {
                    Text(
                        text = "已连接设备",
                        style = MaterialTheme.typography.labelMedium,
                        color = palette.gradientMuted.copy(alpha = 0.7f),  // 原 Color.White 70%：指标标签
                        maxLines = 1
                    )
                    Spacer(Modifier.height(2.dp))
                    // ★ 2026-09-24（用户定稿）：「台设备」从**与数字同行**改成**单独一行**。
                    //   左列「本月流量」是三行（标签 / 读数 / 限额行），右列原来只有两行，
                    //   两列的基线与高度都对不齐。现在右列也是三行（标签 / 数字 / 台设备），
                    //   第三行的样式与左列第三行完全一致（labelMedium + gradientMuted 70%），
                    //   两列在视觉上成对。
                    //   因此不再需要那个 `Row(verticalAlignment = Bottom)` 与 4dp 横向 Spacer。
                    UfiRollingText(
                        text = clientsText,
                        style = UfiTextStyles.metricValue,
                        color = palette.onGradient  // 原 Color.White：设备数大数字
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "台设备",
                        style = MaterialTheme.typography.labelMedium,
                        color = palette.gradientMuted.copy(alpha = 0.7f),  // 与左列第三行同值
                        maxLines = 1
                    )
                }
            }

            Spacer(Modifier.height(10.dp))

            // ═══ 行3：双胶囊网速（上传 / 下载 分开） ═══
            // 2026-09-04：顺序改为上传在前，与流量管理页统一（测速页 DirectionRow 有自己的
            // 设计结论，按用户指示保持下载在前，不在这一轮同步）。
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // 上传胶囊
                SpeedCapsule(Icons.Default.ArrowUpward, txDisplay, Modifier.weight(1f))
                // 下载胶囊
                SpeedCapsule(Icons.Default.ArrowDownward, rxDisplay, Modifier.weight(1f))
            }
        }
    }
}

// ═══════════════════════════════════════════
//  梯度分段信号条（5 段，逐段亮度递增）
//  2026-08-31：私有 GradientSignalBar 已删除，实现搬到公共层
//  [com.ufi_axis.ui.components.common.UfiGradientSignalBar]
//  （NetworkScreen 里还有一份一模一样的拷贝，一并合并了）
// ═══════════════════════════════════════════


// ═══════════════════════════════════════════
//  独立网速胶囊（下载 / 上传 各一个）
// ═══════════════════════════════════════════

/** 单个网速胶囊：图标 + 数值，半透明白底圆角 */
@Composable
private fun SpeedCapsule(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    value: String,
    modifier: Modifier = Modifier
) {
    // 2026-09-04（P2-中）：本函数原有 3 处写死 Color.White（胶囊底 15% / 图标 75% / 数值不透明）。
    // 它铺在外层渐变 Hero 卡上，换配色时渐变随 accent 变、这三处不变 ⇒ 浅色 accent 主题下残留。
    // 私有 composable，加一行 palette 读取即可，公共组件签名不受影响。
    val palette = LocalResolvedPalette.current
    Surface(
        modifier = modifier,
        color = palette.gradientMuted.copy(alpha = 0.15f),  // 原 Color.White 15%：胶囊半透明底
        shape = UfiCardDefaults.subtleShape
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp, horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = palette.gradientMuted.copy(alpha = 0.75f),  // 原 Color.White 75%：上下行箭头
                modifier = Modifier.size(13.dp)
            )
            Spacer(Modifier.width(5.dp))
            // 2026-09-21：实时速率接滚轮动画。这里必须走 UfiRollingMetric 而不是 UfiRollingText ——
            // formatRate 会自适应单位（"998.0 KB/s" → "1.0 MB/s"），单位留在同一串里会让
            // 变化槽位超阈值、退化成"整句滚动"，动画收益归零。剥出单位后换档只是右边那个
            // Text 跳一下，左边的数字段仍然逐位滚。
            UfiRollingMetric(
                text = value,
                style = UfiTextStyles.bodyStrong,
                unitStyle = UfiTextStyles.bodyStrong,
                color = palette.onGradient,  // 原 Color.White：速率数值
                unitSpacing = 3.dp,
                alignment = Alignment.CenterVertically
            )
        }
    }
}

// ═══════════════════════════════════════════
//  辅助工具函数
// ═══════════════════════════════════════════

/** RAT 原始值 → 显示名 */
private fun mapRat(raw: String): String = when (raw.trim().lowercase()) {
    "nr", "5g", "sa", "nsa" -> "5G"
    "lte", "4g" -> "4G"
    "wcdma", "umts", "hspa", "hsdpa" -> "3G"
    "gprs", "edge", "2g" -> "2G"
    else -> raw.uppercase()
}

// 2026-09-05：私有 `Color.shade` 已删除 —— 与 NetworkScreen / TrafficManagementScreen 是
// 逐字节相同的三份拷贝，MonitorOverview 还有一份用 Oklab lerp 写的（像素与 sRGB 不同，
// 四张 Hero 卡的渐变实际不是同一条）。现统一收到主题层
// [com.ufi_axis.ui.theme.ufiShade]，那里同时是 `onGradient` 判据的输入
// （`heroGradientBrightestStop`），运行时画的和测试判的只有一份实现。
// 混合目标 Color.White / Color.Black 随之进 theme 层：它们算的是渐变**底色**、
// 不是底色之上的前景，不该接 onGradient。

/** 字节 → GB 字符串（1 位小数） */
private fun gb(bytes: Long): String =
    "%.1f".format(bytes.toDouble() / (1024.0 * 1024 * 1024))

/** 格式化 speed → 可读速度字符串 */
// 2026-08-31：私有 formatSpeed 已删除 —— 收敛到 [FormatUtils.formatRate]
//（KB/s 段由整数改为一位小数，其余档位口径不变）。
