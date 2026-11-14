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
 * 连接状态 Hero 卡：双主角并列，流量在左、已连接设备在右。
 *
 * 信息架构：
 *   行1：运营商 + 设备名（纵向）  ………  已连接状态 + 信号质量条（右）
 *   行2（双主角）：
 *        左 = 本月流量（数值 + 限额）
 *        右 = 已连接设备总数
 *   行3：双胶囊网速（↓下载 / ↑上传 各自独立）
 */
@Composable
fun HomeConnectionCard(
    state: DashboardState,
    modifier: Modifier = Modifier,
    // 本月流量数据源：默认取 DashboardState（dashboard/summary 的 traffic_limit），
    // 但仪表盘 summary 的 traffic_limit 字段不可靠；调用方应传入「工具-流量管理」同源的
    // viewModel.trafficManagementState.limitConfig（getTrafficLimit 端点），以保证与流量
    // 管理页一致。
    trafficLimitConfig: TrafficLimitConfig? = state.trafficLimitConfig,
    // WiFi 信息数据源：与「网络-无线设置」同源（NetworkModule.refreshWifi →
    // networkState.wifiSettings / wifiClients 解析派生），由调用方从仪表盘传入。
    wifiSsid: String? = null,        // WiFi 名称（热点关闭时为 null）
    wifiBand: String? = null,        // 频段："2.4GHz" / "5GHz"
    wifiClientCount: Int? = null,    // 已连接设备总数（station_list + lan_station_list）
    // ── 后端 / 实时通道状态（合并为卡内一行「服务状态」条）──
    //
    // 这两项由后端连通性与实时通道状态派生，不单独渲染横幅：
    // 1. 断网时两者同时成立，多张提示叠加会把内容区下移一百多 dp，出现与消失还会引起整页
    //    高度跳变；
    // 2. 语义重复：多处各自提示「连不上」，而本卡即「连接状态」卡；
    // 3. 本卡是连接状态的唯一提示点。
    // 规则：该状态条仅在异常时出现（约 26dp），两条同时异常时只显示更严重的一条
    //（后端未连接 > 实时通道），不重复告知。
    backendOffline: Boolean = false,
    lastUpdatedText: String? = null,
    realtimeStatus: String? = null,
    // ── 行2 双主角各自整块可点（左 = 流量，右 = 已连接设备）──
    //
    // 本卡是纯展示组件（除 palette 外不读取任何 Local，不持有状态），因此两个动作只暴露
    // 回调，弹窗及其状态一律由 DashboardScreen 持有。默认 null = 不可点（本卡在 Gallery /
    // Preview 中也可单独渲染，缺少回调时不应成为无响应的假入口）。
    onTrafficClick: (() -> Unit)? = null,
    onClientsClick: (() -> Unit)? = null
) {
    val palette = LocalResolvedPalette.current
    val networkStatus = state.networkStatus
    val trafficRealtime = state.trafficRealtime
    val trafficLimit = trafficLimitConfig

    // ── 网络信息 ──
    // 设备上报的是 CMCC/CUCC/CTCC 一类缩写，展示前统一转换为中文名。
    //
    // 运营商优先读取 signal 快照（3~4.5s 推送，与网络页 hero 同源）；networkStatus 仅在
    // signal 尚未到达时兜底 —— 它来自 10s 的 summary，换卡或换运营商时最多滞后 10s。
    val operatorRaw = state.signalInfo?.operator?.takeIf { it.isNotBlank() }
        ?: networkStatus?.operator
    val operatorName = com.ufi_axis.data.model.CarrierNames
        .displayName(operatorRaw)
        .ifBlank { "未知运营商" }
    // 连接态读取 networkStatus：ppp_status 由 connection WS 实时覆盖（见 DashboardModule）。
    val isConnected = networkStatus?.isCellularConnected ?: false

    // ── 蜂窝制式：仅使用 signalInfo.rat（真正的蜂窝 RAT） ──
    val rawRat = state.signalInfo?.rat?.trim().orEmpty()
    val ratDisplay = when {
        rawRat.isNotBlank() -> mapRat(rawRat)
        else -> "—"
    }

    // 制式后附加频段（如 `5G N78`）。
    // 频段读 core 派生的服务小区统一字段 band_label（NR 优先），显示层转大写
    //（n78 → N78，见 SignalInfo.bandLabelDisplay）。缺失时只显示制式，不做推测。
    val bandDisplay = state.signalInfo?.bandLabelDisplay.orEmpty()
    val ratWithBand = if (bandDisplay.isNotBlank()) "$ratDisplay $bandDisplay" else ratDisplay

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
    // core 已将设备侧的复合串换算为字节（limit_bytes），此处不再解析单位
    val limitBytes = if (hasLimit) trafficLimit?.limit_bytes else null
    // 限额开关开启即显示限额值，不再要求 limitBytes > 0（避免限额为 0 时误显示「无限额」）
    val hasLimitValid = hasLimit

    // ── 信号数据 ──
    val rsrp = state.signalInfo?.rsrp
    val bars = signalBars(rsrp)  // 0..5 格数

    // ── 渐变：使用不透明的明暗衍生色（阶梯口径见 theme 层的 Color.ufiShade）──
    // 深色档系数取自 GRADIENT_MID_LIGHTEN_DARK / GRADIENT_TOP_LIGHTEN_DARK 常量，理由见常量定义。
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

    // 阴影：#9CA4AC @ 30%，Y 偏移 4，模糊半径 16（与全局 token 一致，见 UfiCardDefaults.ufiCardShadow）
    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = UfiCardDefaults.heroMinHeight)
            .ufiCardShadow(elevation = 4.dp, shape = cardShape)
            .clip(cardShape)
            .background(brush = Brush.linearGradient(colors = gradColors))
            .border(
                width = 1.dp,
                // 描边颜色接 palette.gradientMuted（默认白），不使用硬编码的 Color.White：
                // 描边位于 accent 系渐变之上，换配色时渐变随 accent 变化而白色不随之变化，
                // 浅色 accent 主题下会残留一圈不可见的描边。alpha 保留在调用点：本卡的弱化
                // 档从 12% 到 90% 不等，收敛进 token 会改变观感。
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
            // 右组与左列顶对齐（CenterVertically → Top）：左列是两行（运营商 + 制式胶囊 /
            // 设备名），右组（连接圆点 + 文字 + 信号条）只有一行；居中时它落在左列两行的
            // 中缝上，比运营商行低半行。顶对齐后它与运营商行成对。
            // 行1 即卡片内容区的第一个子项，上方没有其他内容，因此「上移一行」只能通过对齐
            // 实现，减少 padding 无法达到。
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    // ── 本卡前景色口径 ──
                    // 卡底为 accent 明暗三段渐变（见上方 gradColors），因此前景不得硬编码
                    // Color.White 一类固定色：换配色时底色变化而固定色不变，浅色 accent
                    // 主题下白字白图标与底色缺乏对比度。
                    // 规则：不透明前景使用 palette.onGradient；带 alpha 的弱化前景与装饰使用
                    // palette.gradientMuted。两槽默认值均为 Color.White，6 个预设不填时观感
                    // 与硬编码白色等价；alpha 档位保留在调用点（本卡弱化档从 12% 到 90%）。
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = operatorName,
                            style = UfiTextStyles.metricValueCompact,
                            color = palette.onGradient,  // 不透明前景：运营商主标题
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            // 中文 19sp 与下方英文 12sp 的字形左侧留白不同；「中」比「F」的
                            // 墨迹右偏约 2px，此处做 0.7dp 光学校正，保证两行左边线一致。
                            modifier = Modifier
                                .weight(1f, fill = false)
                                .offset(x = (-0.7).dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        // 网络制式胶囊（5G / 4G 等），位于运营商之后
                        if (ratDisplay.isNotBlank()) {
                            Surface(
                                color = palette.gradientMuted.copy(alpha = 0.18f),  // 弱化前景 18%：胶囊半透明底
                                shape = UfiCardDefaults.tagShape
                            ) {
                                Text(
                                    text = ratWithBand,
                                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 1.5.dp),
                                    style = UfiTextStyles.label.copy(fontWeight = UfiWeight.Emphasis),
                                    color = palette.onGradient,  // 不透明前景：胶囊内制式文字
                                    maxLines = 1
                                )
                            }
                        }
                    }
                    Text(
                        text = deviceName,
                        style = UfiTextStyles.note.copy(fontWeight = UfiWeight.Medium),
                        color = palette.gradientMuted.copy(alpha = 0.7f),  // 弱化前景 70%：设备名副标题
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.width(10.dp))
                Row(
                    // 顶对齐后补 2dp：左列首行使用 `metricValueCompact`（大字号、行高较高），
                    // 右侧使用 `labelSmall`。纯 Top 对齐会使该组贴近运营商文字上沿而显得偏高；
                    // 2dp 使其与运营商文字的光学中线齐平。
                    modifier = Modifier.padding(top = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(UfiCardDefaults.pillShape)
                            .background(
                                // 未连接态的圆点使用弱化前景（等价于 Color.White 40%）。
                                // 已连接使用 StatusOnline 语义色，与 palette 无关，保持不变。
                                if (isConnected) StatusOnline
                                else palette.gradientMuted.copy(alpha = 0.4f)
                            )
                    )
                    Text(
                        text = if (isConnected) "已连接" else "未连接",
                        style = MaterialTheme.typography.labelSmall,
                        color = palette.gradientMuted.copy(alpha = 0.9f),  // 弱化前景 90%：连接状态文字
                        maxLines = 1
                    )
                    // 信号质量条（复用「网络质量」同款 5 段梯形视觉；未连接时 0 格全暗）
                    Spacer(Modifier.width(2.dp))
                    // 公共 UfiGradientSignalBar（默认参数与私有实现一致）
                    UfiGradientSignalBar(bars = if (isConnected) bars else 0)
                }
            }

            // ═══ 行1.5：服务状态条（仅异常时出现，见构造参数处的并入说明） ═══
            // 两条同时异常时只报更严重的一条：后端整体不可达时，「实时通道断开」是其必然
            // 结果，单独提示属于噪音。
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
                                // 使用语义色（error / warning）而非 gradientMuted：这是「出现故障」
                                // 的信号，必须与卡内其他弱化元素区分。
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

            // ═══ 行2：双主角（左 = 流量 / 右 = 网络质量） ═══
            //
            // 列间隙 16dp 与卡片水平内边距同值：右列内容本身为 `Alignment.Start`，视觉上的
            // 不对齐来自两列距各自左边界的距离不等 ——
            //   左列文字距卡片内容左边缘 = 卡片水平内边距 16dp（见上方 `.padding(horizontal = 16.dp)`）
            //   右列文字距分隔线         = 本 Row 的间隙
            // 取相同值后两列缩进一致。分隔线仍然居中：`spacedBy` 在它两侧给出相同间距。
            // 代价：两个间隙各增加 4dp，可用宽度减少 8dp（每列 4dp）。
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Max),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // —— 左：本月流量 ——
                //
                // 整块可点 → 打开「流量限额设置」弹窗（与工具-流量管理中的是同一份组件，
                // 见 UfiDataLimitDialog）。以下三点为约束：
                //   1. 点击区挂在该列上（标签 / 读数 / 限额行 + fillMaxHeight），而不是挂在
                //      数字那个 Text 上：用户认知中的可点区域是整列。
                //   2. 竖分隔线是本 Row 的另一个子项、不在该 Column 内，因此不会被点击区
                //      覆盖。为列增加 padding 时需先核算 weight。
                //   3. `clip` 必须排在 `clickable` 之前：本卡底部是 accent 三段渐变，不裁剪
                //      形状时默认 ripple 为直角矩形，在圆角卡内侧形成溢出块。indication 保持
                //      默认（不传 null）—— MonitorOverview 的 hero 入口胶囊同为渐变底 +
                //      默认 ripple，观感一致。
                val limitLine = if (hasLimitValid) "/ ${gb(limitBytes!!)} GB" else "/ 无限额"
                Column(
                    modifier = Modifier
                        // 分隔线为本 Row 的第二个子项，位置由两侧权重决定：权重不等时它偏离
                        // 中线，等权后正好位于中线（两侧间隙与 1dp 线宽对称扣除，不影响居中）。
                        //
                        // 代价：左列宽度较不等权时减少约 5%。左列读数为 28sp ExtraBold，
                        // `"1234.5 GB"` 这类四位整数加单位在窄屏上可能触及边界。
                        // 月流量超过 1TB 时再单独处理，不使用不等权方案 —— 那是以线偏移换取
                        // 线不偏移。
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
                                    // 语义挂在整列上：三行文字各自朗读会拆成「本月流量 / 12.3 GB /
                                    // 斜杠 100.0 GB」三段，合并为一句才通顺，动作提示由
                                    // onClickLabel 提供。
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
                        color = palette.gradientMuted.copy(alpha = 0.7f),  // 弱化前景 70%：指标标签
                        maxLines = 1
                    )
                    Spacer(Modifier.height(2.dp))
                    // 滚轮读数：gb() 单位固定为 GB，仅小数位变化，变化槽位为 1~2 个，
                    // 因此无需经 UfiRollingMetric 拆分单位。
                    UfiRollingMetric(
                        text = "${gb(usedBytes)} GB",
                        style = UfiTextStyles.metricValue,
                        unitStyle = UfiTextStyles.metricValue,
                        color = palette.onGradient,  // 不透明前景：本月流量大数字
                        unitSpacing = 5.dp
                    )
                    Spacer(Modifier.height(2.dp))
                    // 有限额分支末尾不保留「限额」二字：`"/ 100.0 GB"` 语义已完整，附加为
                    // 冗余修饰。无限额分支保持 `"/ 无限额"`：这三个字是一个整体，抽掉会变成
                    // `"/ 无"`，语义不成立。
                    // 文案统一由 `limitLine` 提供，整列的无障碍描述也使用同一句，避免「看到的
                    // 与读到的不一致」。
                    Text(
                        text = limitLine,
                        style = MaterialTheme.typography.labelMedium,
                        color = palette.gradientMuted.copy(alpha = 0.7f),  // 弱化前景 70%：限额说明
                        maxLines = 1
                    )
                }

                // —— 竖分隔 ——
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .fillMaxHeight()
                        .background(palette.gradientMuted.copy(alpha = 0.25f))  // 弱化前景 25%：双主角竖分隔线
                )

                // —— 右：已连接设备总数（与左列流量对称） ——
                //
                // 整块可点 → 打开「在线设备」弹窗（数据与网络设置中的在线设备页同源，见
                // HomeOnlineDevicesDialog）。几何与左列同一套约束，clip / indication /
                // 分隔线的说明见左列注释。
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
                        color = palette.gradientMuted.copy(alpha = 0.7f),  // 弱化前景 70%：指标标签
                        maxLines = 1
                    )
                    Spacer(Modifier.height(2.dp))
                    // 「台设备」单独一行而非与数字同行：左列「本月流量」是三行（标签 / 读数 /
                    // 限额行），右列若仅两行，两列的基线与高度无法对齐。右列为三行（标签 /
                    // 数字 / 台设备）后，第三行样式与左列第三行一致（labelMedium +
                    // gradientMuted 70%），两列在视觉上成对。
                    // 因此无需 `Row(verticalAlignment = Bottom)` 与横向 Spacer 组合。
                    UfiRollingText(
                        text = clientsText,
                        style = UfiTextStyles.metricValue,
                        color = palette.onGradient  // 不透明前景：设备数大数字
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
            // 顺序为上传在前，与流量管理页统一（测速页 DirectionRow 按其自身设计保持下载在前）。
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
//  实现位于公共层
//  [com.ufi_axis.ui.components.common.UfiGradientSignalBar]
// ═══════════════════════════════════════════


// ═══════════════════════════════════════════
//  独立网速胶囊（下载 / 上传 各一个）
// ═══════════════════════════════════════════

/** 单个网速胶囊：图标 + 数值，半透明底圆角 */
@Composable
private fun SpeedCapsule(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    value: String,
    modifier: Modifier = Modifier
) {
    // 三处颜色均接 palette，不使用硬编码的 Color.White：本组件铺在外层渐变 Hero 卡上，
    // 换配色时渐变随 accent 变化而固定白色不变，浅色 accent 主题下会残留。
    val palette = LocalResolvedPalette.current
    Surface(
        modifier = modifier,
        color = palette.gradientMuted.copy(alpha = 0.15f),  // 弱化前景 15%：胶囊半透明底
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
                tint = palette.gradientMuted.copy(alpha = 0.75f),  // 弱化前景 75%：上下行箭头
                modifier = Modifier.size(13.dp)
            )
            Spacer(Modifier.width(5.dp))
            // 实时速率接入滚轮动画。此处必须使用 UfiRollingMetric 而非 UfiRollingText：
            // formatRate 会自适应单位（「998.0 KB/s」→「1.0 MB/s」），单位保留在同一串中会使
            // 变化槽位超出阈值，退化为整句滚动，动画收益归零。剥出单位后换档只是右侧单位
            // Text 变化，左侧数字段仍逐位滚动。
            UfiRollingMetric(
                text = value,
                style = UfiTextStyles.bodyStrong,
                unitStyle = UfiTextStyles.bodyStrong,
                color = palette.onGradient,  // 不透明前景：速率数值
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
    // EN-DC = E-UTRAN New Radio Dual Connectivity，即 LTE 锚点 + 5G NR 的 NSA 组网；
    // 它是 5G 的一种部署方式，不是独立制式。设备侧直接上报 "EN-DC" 时不能原样展示。
    "nr", "5g", "sa" -> "5G"
    // NSA 单独标注：EN-DC / ENDC / NSA 都是 LTE 锚点 + NR 双连接。
    "nsa", "endc", "en-dc", "en_dc", "nr-nsa", "nr_nsa" -> "5G NSA"
    "lte", "4g" -> "4G"
    "wcdma", "umts", "hspa", "hsdpa" -> "3G"
    "gprs", "edge", "2g" -> "2G"
    else -> raw.uppercase()
}

// 渐变明暗衍生色统一使用主题层 [com.ufi_axis.ui.theme.ufiShade]，本文件不另实现：
// 该层同时是 `onGradient` 判据的输入（`heroGradientBrightestStop`），运行时绘制与测试
// 判断必须共用同一份实现。多份拷贝会分叉（Oklab lerp 与 sRGB 混合的插值结果不同，
// 同一张卡的渐变像素不一致）。混合目标 Color.White / Color.Black 也收在主题层：
// 它们计算的是渐变底色，不是底色之上的前景，不应接 onGradient。

/** 字节 → GB 字符串（1 位小数） */
private fun gb(bytes: Long): String =
    "%.1f".format(bytes.toDouble() / (1024.0 * 1024 * 1024))

/** 格式化 speed → 可读速度字符串 */
// 速度格式化统一使用 [FormatUtils.formatRate]
//（KB/s 段为一位小数，其余档位口径不变）。
