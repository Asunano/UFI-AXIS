package com.ufi_axis.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Router
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.ufi_axis.ui.components.common.UfiCustomDialog
import com.ufi_axis.ui.components.common.UfiButton
import com.ufi_axis.ui.components.common.UfiButtonVariant
import com.ufi_axis.ui.components.common.UfiScreenScaffold
import com.ufi_axis.ui.components.common.UfiSettingsGroup
import com.ufi_axis.ui.components.common.UfiStatItem
import com.ufi_axis.ui.components.common.UfiMotion
import com.ufi_axis.ui.theme.LocalResolvedPalette
import com.ufi_axis.ui.theme.Spacing
import com.ufi_axis.ui.theme.UfiCardDefaults
import com.ufi_axis.ui.theme.UfiTextStyles
import com.ufi_axis.ui.theme.UfiWeight
import com.ufi_axis.ui.theme.ufiStandardCard
import com.ufi_axis.util.FormatUtils
import com.ufi_axis.viewmodel.MainViewModel
import com.ufi_axis.viewmodel.state.SpeedTestPhase
import com.ufi_axis.viewmodel.state.SpeedTestState
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

/**
 * 网速测试页 — 仿 Speedtest 的表盘式布局（2026-08-26 重构，第二轮）。
 *
 * 交互流程：默认**不显示**表盘，只显示「开始测速」按钮；点击后按钮换成表盘 + 取消按钮，
 * 测完停在结果上、按钮变「重新测速」。表盘与按钮互斥，不会互相压住。
 *
 * 时长不再让用户挑：NetworkModule 按「速率是否稳定」自动收尾（最短 5s，内网上限 15s /
 * 外网上限 25s）。外网地址固定为自建节点，不再提供修改入口（见第六轮）。

 *
 * 第三轮：结果区补上行、抖动、并发流数——测量逻辑见 `NetworkModule.startSpeedTest`。
 * 内网会跑「下行 → 上行」两个阶段，外网没有可写入的丢弃汇，所以只跑下行。
 *
 * 第四轮：读数从表盘中心移到**表盘下方**（`SpeedReadout`）——44sp 大字压在中心会盖住刻度值
 * 与活动弧；表盘本身缩到 260dp 并留 12dp 横向余量，避免活动弧的柔光描边被裁边。
 *
 * 第五轮（2026-08-27）：
 *  1. 读数回到表盘**内部、指针轴心正下方**（`DialReadout`）。能这么放的前提是指针改成
 *     「浮动段」——只画外圈那一段（`NEEDLE_INNER_RATIO`..1），轴心到读数之间不再有线经过。
 *     表盘同时放大到 300dp。若指针仍是「轴心→尖端」的整条线，低速（135°，左下）与高速
 *     （45°，右下）两个极端位置都会横穿读数区，这也是第四轮把读数挪到盘外的原因。
 *  2. 下载 / 上传从「三列一行的结果区」拆成表盘下方**左右两张方向卡**（`DirectionCard`），
 *     各自带方向图标、平均速率、峰值；结果区只留延迟/抖动/用时/已传输/并发流。
 *  3. 上下行区分**不换主色**：主题预设里的 `accentSecondary` 一律是 `accent` 的浅色调
 *     （69B1FF / 90E4C3 / B1A1FF / FFB989 / E5E5E5），拿来当上行主色又淡又脏。两个方向统一用
 *     `palette.accent`，靠方向箭头、`Crossfade` 的方向文案、方向卡顶部高亮条三处动画反馈。
 *
 * 第六轮（2026-08-27）：外网地址**固定**为 [SpeedTestState.EXTERNAL_NODES] 里的自建节点，
 * 删掉「测速地址」设置行与编辑弹窗。任意地址会引入两类客户端无法消除的误差：被 CDN 按
 * 文本类型 gzip 后统计到解压字节（虚高）、文件太小提前 EOF（虚低）。
 *
 * 第七轮（2026-08-27）：
 *  1. 上行**不再改表盘几何**（第五轮的活动弧内缩已删）——内缩后表盘像"缩了一圈"，和下行看着
 *     不像同一个组件，比没有区分更乱。
 *  2. 外网支持**多节点**：模式切到外网时多一排节点 chip。两类节点——自建节点（EdgeOne /
 *     Cloudflare，同一套端点契约）与第三方文件节点（直接拉别人 CDN 上的静态文件，只测下行）。
 *     只能选节点、不能改地址。
 *  3. 外网也测上行：自建节点的 `POST /upload` 是可选端点，客户端先探一次；没部署就跳过上行，
 *     方向卡显示「节点未开上行」而不是把 0 当结果。文件节点直接显示「该节点不测上行」。
 *
 * 第八轮（2026-08-27）——整页重排，三段固定骨架：
 *  1. **表盘钉在顶部**（[DIAL_SLOT_HEIGHT] 的固定槽位），任何阶段都在场、位置不变。之前它只在
 *     测速中/出结果时出现，空闲时换成占位插画，导致点「开始」的瞬间整页往下顶一大截。
 *  2. **指针补全**：改回完整的「轴心→尖端」一整条（此前为了给读数让路只画外圈一段）。读数
 *     改用「限宽 + 下移」压进正下方那个指针永远扫不到的 90° 缺口，判据 `|dx| ≤ dy`，
 *     见 [READOUT_OFFSET_Y] / [READOUT_MAX_WIDTH]；数值竖排、按位数降字号，避免超出安全宽度。
 *  3. **两块内容按阶段互斥**：切换区（内外网 chip / 节点 chip / 节点说明）只在**非测速中**显示，
 *     数据区（方向卡 / 曲线 / 统计）只在**测速中或已出结果**显示，都走 `AnimatedVisibility`
 *     的淡入 + 展开。DONE 是两者同时在场的唯一状态，所以中间数据区 `weight(1f)` 且可滚动、
 *     控制区钉在底部——表盘与按钮的位置在任何阶段都不动，只有中间那段长高长矮。
 *  4. 表盘下方常驻一行「内网 · 设备直连 / 外网 · <host>」：测速中 chip 是收起的，
 *     没这行就看不出正在测哪个节点。
 *
 * 第九轮（2026-08-27）——读数移出表盘，修「表盘下方元素重叠」：
 *  盘内读数用的是 `Modifier.offset`，而 offset **不占布局高度**：读数一变高（系统字号放大、
 *  数值变成 "1000.0"、阶段文案换行）就会溢出表盘槽位、直接画在下方元素上；同时盘内安全区
 *  （指针扫不到的正下方 90° 缺口，判据 `|dx| ≤ dy`）本身只有几十 dp 宽，字号一大就被指针划过。
 *  现在表盘内**不放任何文字**，读数进 `LiveReadout` —— 表盘正下方一个 [READOUT_SLOT_HEIGHT]
 *  的固定槽位，「数值 + 单位 + 阶段」一行、节点标识一行，高度写死，任何内容变化都顶不动下方。
 *  表盘同时缩到 [DIAL_MAX_WIDTH]，腾出的高度正好给读数槽位，整页总高不变。
 *
 * 第十轮（2026-08-27）——按 Speedtest 重排，只动排版不动测量逻辑：
 *  1. **读数回到盘内中心**（[DialReadout]）。第九轮之所以把它移出去，是因为整条指针会横穿中心；
 *     这轮把指针改成**浮动段**（只画 [NEEDLE_INNER_RATIO]..1），中心圆盘再无线经过。溢出问题
 *     也不复现：读数由**固定高度 + clipToBounds 的父 Box** 约束，不再用 `Modifier.offset`。
 *     空心表盘 + 盘外读数会让视觉重心裂成上下两块，这是与 Speedtest 差得最远的一处。
 *  2. **方向卡 → 方向行**（[DirectionRow]）：一张卡两列 + 中间竖分隔，扁平、常驻。原来两张卡各带
 *     3dp 高亮条 + 描边 + 阴影，紧贴表盘时两圈边框两道阴影把视觉切碎；且只在测速中出现，
 *     点「开始」的瞬间页面往下顶。峰值挪进统计卡。
 *  3. **横向边距全页统一** [Spacing.CardHorizontalMargin]。原来表盘 12 / 卡片 16 / 曲线 24 /
 *     按钮 32 四种混用，是"排布不好看"最直接的来源。
 *  4. **曲线并入统计卡**，统计改 3 列 × 2 行（补「下行峰值」凑满 6 格）。原来是 3 + 2，
 *     第二行两项各占一半、和上一行的三列对不齐。
 *  5. 节点标识提到表盘上方一行，对应 Speedtest 顶部的服务器信息条。
 *
 * 第十一轮（2026-08-28）——只改表盘**以下**的信息区，表盘本身不动：
 *  上一轮盘下是「方向卡（一张）+ 统计卡（一张）」两块等重白砖，统计卡里六个居中数字排成 3×2、
 *  无格线无主次，读起来像一张表格。这轮把信息按**类别**重新分层：
 *  1. 延迟 / 抖动是**链路质量**，不是带宽，提到表盘上方做成一枚描边胶囊（[LinkQualityPills]）
 *     —— Speedtest 也是把 PING / JITTER 放盘上。混在盘下统计格里它们只能和用时/已传输争权重。
 *  2. 方向行**去掉卡片容器**（[DirectionRow]）：紧贴表盘的卡片边框会把主焦点切碎，而有了容器
 *     它就和下方的统计卡等重。现在只靠字号（[DIRECTION_VALUE_SP] 26sp）+ 一条竖 hairline 分区，
 *     active 也只染色、不铺底色（无边框的底色块边缘是虚的，比不加更脏）。
 *  3. 剩下的过程量（用时 / 下行峰值 / 已传输 / 并发流）进 2×2 **田字格**（[StatGrid]），横竖各一条
 *     hairline；曲线加「瞬时速率」小标题，和格子之间也用 hairline 断开。
 *  层级最终是：盘上小胶囊 → 盘内中心大读数 → 盘下两个中号大数 → 卡内四个小数字。
 *
 * 第十二轮（2026-08-28）——底部选择区从 chip 换成**目标单选行**（[TargetSelector]）：
 *  chip 方案连试三版都不成立（两排 `UfiSingleChipSelector`、自造分段控件、公共 `UfiOptionGrid`），
 *  根因是**信息结构不匹配**：测速目标本来是「内网 + N 个外网节点」的一维单选，chip 却强迫它拆成
 *  两排（模式一排、节点一排）——两排等重看不出谁管谁；而且 chip 格里放不下「这个目标测什么」，
 *  只能在卡外再挂一行说明文字，于是底部永远是"两排格子 + 一行小字"。
 *  现在摊平成若干行，每行「图标 + 标题 + 副标 + 右侧单选点」，行间 hairline：
 *  一次点选到底（原来切外网节点要点两次），副标直接写清下行 / 上行与文件节点的局限，
 *  卡外那行独立说明随之删除。
 *  这份列表**收起在弹窗里**：底部只留一行 [TargetPickerRow] 显示当前目标，点击才弹出
 *  [TargetSelector]（点行即生效并关闭）。三行常驻会在表盘下方占掉 ~170dp，把中间数据区压没，
 *  而"测哪里"是选一次就不再动的设置。
 *  [mode] / [nodeId] 仍是两个 state：`runSpeedTest()` 与 `runExternalSpeedTest(nodeId)` 是两个入口，
 *  且切回内网时要记住上次选过的节点。
 *  表盘上方的链路质量胶囊圆角也从整圆改回全站统一的 10dp。
 *
 * 组件取用：表盘、实时曲线、盘内读数、方向行、田字格、分段控件、节点 chip 都是本文件私有
 * （公共库没有对应形态，或公共形态分不出这一屏需要的层级）；其余一律用公共组件：
 * `UfiSettingsGroup`、`UfiStatItem`、`UfiButton`（variant = Primary / Secondary）、`UfiScreenScaffold`。
 */

@Composable
fun SpeedTestScreen(viewModel: MainViewModel, navController: NavHostController) {
    val state by viewModel.speedTestState.collectAsState()
    val palette = LocalResolvedPalette.current

    var mode by rememberSaveable { mutableStateOf(MODE_INTERNAL) }
    var nodeId by rememberSaveable { mutableStateOf(SpeedTestState.DEFAULT_EXTERNAL_NODE.id) }
    var showTargetPicker by remember { mutableStateOf(false) }
    val node = SpeedTestState.externalNode(nodeId)

    // 测速目标清单 = 内网 + 每个外网节点，摊平成一维单选项（见 [TargetSelector]）。
    // mode / nodeId 仍是两个独立 state：runSpeedTest() 与 runExternalSpeedTest(nodeId) 是两个入口，
    // 且 nodeId 要在切回内网时保留上次选的节点。
    val targets = remember {
        buildList {
            add(
                TargetOption(
                    mode = MODE_INTERNAL,
                    nodeId = null,
                    title = "内网测速",
                    // 2026-09-06：副标删除。内网就是"和设备直连"，标题已经说完了，
                    // 再写一行"与设备直连 · 下行 / 上行"属于复述。
                    subtitle = null,
                    icon = Icons.Default.Router
                )
            )
            SpeedTestState.EXTERNAL_NODES.forEach { n ->
                add(
                    TargetOption(
                        mode = MODE_EXTERNAL,
                        nodeId = n.id,
                        title = n.label,
                        subtitle = if (n.uploadUrl == null) {
                            // 文件节点分支（当前清单里没有，见 SpeedTestState.EXTERNAL_NODES）
                            "外网文件节点 · 仅下行，高带宽下偏保守"
                        } else {
                            // 2026-09-06：原为「外网自建节点 · 下行 / 上行」。那是在讲实现
                            //（谁部署的、测哪两个方向），而用户真正需要知道的是**这个数字能不能当真**：
                            // 节点在公网 CDN 边缘、带宽与路由都不受我们控制，量出来的是连通性与
                            // 大致量级，不是运营商标称速率。
                            "仅供连通性测试，不代表真实速度"
                        },
                        icon = Icons.Default.Public
                    )
                )
            }
        }
    }
    val selectedTarget = targets.indexOfFirst {
        if (mode == MODE_INTERNAL) it.mode == MODE_INTERNAL else it.nodeId == nodeId
    }.coerceAtLeast(0)

    val isExternal = mode == MODE_EXTERNAL
    val isRunning = state.isRunning
    // 数据区（方向卡 / 曲线 / 统计）只在「测速中」与「已出结果」显示；
    // 切换区（内外网 chip、节点 chip、节点说明）只在「非测速中」显示。
    // 两者在 DONE 时同时在场，这也是布局要能同时容纳它们的原因（数据区可滚动、控制区钉底）。
    val showData = isRunning || state.phase == SpeedTestPhase.DONE
    // 上行不可测时，方向行的上传格用这行小字顶掉数值——避免把「没测」显示成 0
    val uploadNote = when {
        isExternal && node.uploadUrl == null -> "该节点不测上行"
        isExternal && state.phase == SpeedTestPhase.DONE && !state.uploadMeasured -> "节点未开上行"
        else -> null
    }

    // 离开页面即中断测速（原 UID-010 Wave 2 行为保留）
    DisposableEffect(Unit) {
        onDispose { viewModel.cancelSpeedTest() }
    }

    val targetFraction = when (state.phase) {
        SpeedTestPhase.DOWNLOAD, SpeedTestPhase.UPLOAD -> gaugeFraction(state.currentMbps)
        SpeedTestPhase.DONE -> gaugeFraction(state.avgMbps)
        else -> 0f
    }
    // 指针动画：animateFloatAsState 返回的 State 只在 draw 阶段读取，
    // 避免指针每一帧都把整页拖进重组（监控页 T38 踩过的坑）。
    val needle = animateFloatAsState(
        targetValue = targetFraction,
        // 2026-09-04（P2b）：原为裸 `240 / 600`。
        // - 测速中的指针跟随 240 → Duration.Fluid（250，+10ms，在吸附容差内；240 与 250 是
        //   同一种"轻量跟随"手感，全库不该保留两个数）；
        // - 归零 / DONE 的 600 → Duration.Reveal（值不变）。
        animationSpec = tween(
            if (state.phase == SpeedTestPhase.DOWNLOAD || state.phase == SpeedTestPhase.UPLOAD) {
                UfiMotion.Duration.Fluid
            } else {
                UfiMotion.Duration.Reveal
            }
        ),
        label = "needle"
    )
    // 方向区分不靠表盘：上行沿用下行那一套表盘绘制（同色、同半径、同线宽），
    // 只靠盘内读数的方向文案与方向行当前格的高亮来表达"现在在测上行"。
    val directionColor = palette.accent

    UfiScreenScaffold(title = "网速测试", navController = navController, showBack = true) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // ═══════════ ① 顶部信息条：节点标识 + 延迟 / 抖动胶囊 ═══════════
            // 延迟与抖动是「链路质量」，和下行/上行的「带宽」不是一类指标，混在下方的统计格里
            // 会和用时/已传输那些次要数字等重。Speedtest 也是把 PING / JITTER 放在表盘上方。
            Text(
                text = if (isExternal) "外网 · ${node.host}" else "内网 · 设备直连",
                style = MaterialTheme.typography.labelSmall,
                color = palette.textSecondary,
                maxLines = 1,
                modifier = Modifier.padding(top = Spacing.Medium)
            )
            Spacer(Modifier.height(6.dp))
            LinkQualityPills(
                latencyMs = state.latencyMs,
                jitterMs = state.jitterMs,
                accent = directionColor
            )

            // ═══════════ ② 表盘 + 盘内中心读数：固定槽位，任何阶段都在，位置不变 ═══════════
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(DIAL_SLOT_HEIGHT)
                    // 槽位裁边：读数万一因系统字号放大而超高，也只被裁掉，绝不会画到下方元素上
                    .clipToBounds(),
                contentAlignment = Alignment.Center
            ) {
                SpeedDial(
                    needleFraction = needle,
                    progress = state.progress,
                    accent = directionColor,
                    trackColor = palette.divider.copy(alpha = if (palette.isDark) 0.55f else 0.7f),
                    tickColor = palette.textSecondary.copy(alpha = 0.5f),
                    labelColor = palette.textSecondary,
                    modifier = Modifier
                        // 留出横向余量：活动弧的柔光描边比轨道宽 8dp，贴边会被裁掉
                        .padding(horizontal = 12.dp)
                        .widthIn(max = DIAL_MAX_WIDTH)
                        .fillMaxWidth()
                        .aspectRatio(1f)
                )
                DialReadout(
                    state = state,
                    activeColor = directionColor,
                    textPrimary = palette.textPrimary,
                    textSecondary = palette.textSecondary
                )
            }

            // ═══════════ ③ 方向行：下载 / 上传，常驻一张卡两列，位置不随阶段变化 ═══════════
            DirectionRow(
                downMbps = state.avgMbps,
                upMbps = state.uploadMbps,
                uploadNote = uploadNote,
                downActive = state.phase == SpeedTestPhase.DOWNLOAD,
                upActive = state.phase == SpeedTestPhase.UPLOAD,
                color = directionColor,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.CardHorizontalMargin)
            )

            // ═══════════ ④ 数据区：占满中间剩余空间并可滚动 ═══════════
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                AnimatedVisibility(
                    visible = showData,
                    enter = fadeIn(tween(PHASE_SWITCH_MS)) + expandVertically(tween(PHASE_SWITCH_MS)),
                    exit = fadeOut(tween(PHASE_SWITCH_MS)) + shrinkVertically(tween(PHASE_SWITCH_MS))
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Spacer(Modifier.height(Spacing.Large))
                        // 次要信息合成一张卡：曲线 + 2×2 田字格。
                        // 原来是「六个等重的居中数字排成 3×2」，没有分隔线也没有主次，像一张表格；
                        // 延迟/抖动已提到表盘上方，这里只剩过程量。
                        UfiSettingsGroup {
                            if (state.samples.isNotEmpty()) {
                                Text(
                                    text = "瞬时速率",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = palette.textSecondary
                                )
                                Spacer(Modifier.height(6.dp))
                                SpeedSparkline(
                                    samples = state.samples,
                                    lineColor = directionColor,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(48.dp)
                                )
                                Spacer(Modifier.height(Spacing.XLarge))
                                Hairline()
                                Spacer(Modifier.height(Spacing.Small))
                            }
                            StatGrid(
                                cells = listOf(
                                    "用时" to if (state.elapsedSec > 0) formatOneDecimal(state.elapsedSec) + " s" else "—",
                                    "下行峰值" to if (state.peakMbps > 0) FormatUtils.formatMbps(state.peakMbps) + " Mbps" else "—",
                                    "已传输" to FormatUtils.formatSize(state.totalBytes, "—"),
                                    "并发流" to if (state.streams > 0) "${state.streams} 条" else "—"
                                )
                            )
                        }
                    }
                }

                // 空闲 / 失败态：数据区改放这次要测什么的说明，避免大片空白
                AnimatedVisibility(
                    visible = !showData,
                    enter = fadeIn(tween(PHASE_SWITCH_MS)),
                    exit = fadeOut(tween(PHASE_SWITCH_MS))
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(
                            horizontal = Spacing.CardHorizontalMargin,
                            vertical = Spacing.XLarge
                        )
                    ) {
                        Text(
                            // 「测什么」交给控制区那一行（跟着模式/节点变），这里只说「怎么测」，
                            // 两处文案原来都在讲测什么，是空闲页面读起来啰嗦的原因。
                            text = "多流并发 · 延迟与抖动独立探针 · 速率稳定即自动结束",
                            style = MaterialTheme.typography.labelSmall,
                            color = palette.textSecondary.copy(alpha = 0.75f),
                            textAlign = TextAlign.Center
                        )
                    }
                }

                state.errorMessage?.let { msg ->
                    Text(
                        text = msg,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (state.phase == SpeedTestPhase.ERROR) palette.error else palette.textSecondary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(
                            horizontal = Spacing.CardHorizontalMargin,
                            vertical = 6.dp
                        )
                    )
                }
            }

            // ═══════════ ⑤ 控制区：钉在底部。测速中只留「取消」，chip 全部收起 ═══════════
            AnimatedVisibility(
                visible = !isRunning,
                enter = fadeIn(tween(PHASE_SWITCH_MS)) + expandVertically(tween(PHASE_SWITCH_MS)),
                exit = fadeOut(tween(PHASE_SWITCH_MS)) + shrinkVertically(tween(PHASE_SWITCH_MS))
            ) {
                // 测速目标只显示**当前选中的一行**，点开才弹全部。
                // 三行常驻会在表盘下方堆出 ~170dp，把中间的数据区压没；而目标是个"选一次就不再动"
                // 的设置，不值得常占这么多高度。
                TargetPickerRow(
                    option = targets[selectedTarget],
                    onClick = { showTargetPicker = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.CardHorizontalMargin)
                        .padding(bottom = Spacing.Large)
                )
            }

            // 主操作按钮与全页卡片同宽（原来 32dp 边距，比卡片窄一截，是视觉不齐的另一处）
            Box(Modifier.padding(horizontal = Spacing.CardHorizontalMargin)) {
                if (isRunning) {
                    UfiButton(
                        variant = UfiButtonVariant.Secondary,
                        text = "取消测速",
                        onClick = { viewModel.cancelSpeedTest() }
                    )
                } else {
                    UfiButton(
                        text = if (state.phase == SpeedTestPhase.DONE) "重新测速" else "开始测速",
                        onClick = {
                            if (isExternal) viewModel.network.runExternalSpeedTest(nodeId)
                            else viewModel.network.runSpeedTest()
                        }
                    )
                }
            }

            Spacer(Modifier.height(Spacing.Large))
        }

        // 目标选择弹窗：点行即生效并关闭。这里没有「确认」按钮——切目标只改本地 state、不调设备
        // API，不像设备控制那几个弹窗需要「暂存 → 确认」；不传 confirm/dismissButton 时 shell 会
        // 自动在标题右上角渲染 ×。
        UfiCustomDialog(
            visible = showTargetPicker,
            onDismiss = { showTargetPicker = false },
            title = "测速目标"
        ) {
            TargetSelector(
                options = targets,
                selectedIndex = selectedTarget,
                onSelect = { index ->
                    val t = targets[index]
                    mode = t.mode
                    if (t.nodeId != null) nodeId = t.nodeId
                    showTargetPicker = false
                }
            )
        }
    }
}



private const val MODE_INTERNAL = "internal"
private const val MODE_EXTERNAL = "external"

/**
 * 阶段切换（下行↔上行）时颜色 / 高亮条的过渡时长。
 *
 * 2026-09-04（P2b）：原为裸 `420`，注释写「刻意不在 UfiMotion.Duration 档位上，本文件是唯一来源」。
 * 但同一个 420 在 `MainNavGraph`（胶囊浮出）也写了一遍 —— 它并不是本文件独有的手感。
 * 现已在梯度里建档 [UfiMotion.Duration.Emphatic]（值仍是 420，"比 Deliberate 400 慢半拍的强调型入场"），
 * 两处共用一个来源；本常量只是把 token 转成本文件内部的命名。
 */
private const val PHASE_SWITCH_MS = UfiMotion.Duration.Emphatic

/** 测速目标行选中态的过渡时长 */
private const val TARGET_SWITCH_MS = UfiMotion.Duration.Base

// ==================== 表盘 ====================

/** 表盘刻度锚点（Mbps）：分段而非线性，低速段分辨率更高，与 Speedtest 的读数手感一致 */
private val GAUGE_ANCHORS = doubleArrayOf(0.0, 1.0, 5.0, 10.0, 25.0, 50.0, 100.0, 250.0, 500.0, 1000.0)
private const val GAUGE_START_ANGLE = 135f
private const val GAUGE_SWEEP_ANGLE = 270f
private const val PI_F = 3.1415927f

/**
 * 表盘槽位高度 / 表盘最大宽度：表盘固定占顶部这么高，任何阶段都不变，切换阶段时页面不跳版。
 * 槽位比表盘本身高一点，给活动弧的柔光描边留余量。
 */
private val DIAL_MAX_WIDTH = 244.dp
private val DIAL_SLOT_HEIGHT = 258.dp

/**
 * 指针内端半径比例：指针只画 [NEEDLE_INNER_RATIO]..1 这一段（浮动段），
 * 中心 [NEEDLE_INNER_RATIO] 半径以内是**指针永远扫不到的干净圆盘**，读数就放在这里
 * （见 [DialReadout]）。若指针是「轴心→尖端」整条，低速 135°（左下）与高速 45°（右下）
 * 两个极端都会横穿中心读数。
 */
private const val NEEDLE_INNER_RATIO = 0.64f

/**
 * 盘内读数的安全宽度：约等于中心干净圆盘的内接宽度（表盘 256dp → 半径 128dp →
 * 干净圆盘半径 ≈ 82dp → 内接矩形宽 ≈ 116dp）。超出这个宽度的字会被指针划过，
 * 所以数值按位数降字号（[readoutFontSize]）而不是让它自由变宽。
 */
private val READOUT_MAX_WIDTH = 110.dp

/** 方向行（下载 / 上传）高度：写死，让页面骨架在任何阶段都不长高长矮 */
private val DIRECTION_ROW_HEIGHT = 72.dp

/** 方向行数值字号：明显大于次要信息格（titleMedium ≈ 16sp），主次靠字号拉开而不是靠卡片 */
private val DIRECTION_VALUE_SP = 26.sp

/** 次要信息田字格每格高度 */
private val STAT_CELL_HEIGHT = 54.dp

/** Mbps → 表盘 0..1 比例（锚点间线性插值；超过 1000 夹到满量程） */
private fun gaugeFraction(mbps: Double): Float {
    if (mbps <= 0.0) return 0f
    val last = GAUGE_ANCHORS.size - 1
    if (mbps >= GAUGE_ANCHORS[last]) return 1f
    for (i in 0 until last) {
        val lo = GAUGE_ANCHORS[i]
        val hi = GAUGE_ANCHORS[i + 1]
        if (mbps <= hi) {
            val within = (mbps - lo) / (hi - lo)
            return ((i + within) / last).toFloat()
        }
    }
    return 1f
}

private fun anchorLabel(mbps: Double): String = when {
    mbps >= 1000 -> "1k"
    mbps == 0.0 -> "0"
    else -> mbps.toInt().toString()
}

/**
 * 表盘：轨道弧 + 刻度 + 刻度值 + 时间进度细弧 + 活动弧 + **浮动段指针**。
 *
 * 指针只画 [NEEDLE_INNER_RATIO]..1 这一段，中心那块圆盘指针永远扫不到，读数就叠在那里
 * （[DialReadout]，由调用方放进同一个 Box）。历史上试过两种别的放法都不行：
 *  - 整条「轴心→尖端」指针 + 盘内读数：低速 135°（左下）与高速 45°（右下）都横穿读数；
 *  - 整条指针 + 盘外固定读数槽位：不重叠了，但表盘中心空着、视觉重心裂成上下两块，
 *    和 Speedtest 差得最远。
 * 现在读数由固定高度的父 Box 约束（不是 `Modifier.offset`，所以不存在溢出压到下方元素的问题）。
 *
 * 上下行**共用同一套绘制**（同色、同半径、同线宽）：曾试过上行把活动弧内缩成细内环，实际观感
 * 是表盘"缩了一圈"、和下行像两个组件，反而更乱。方向提示交给读数区文案与方向行高亮。
 *
 * 性能：刻度 / 标签 / 静态几何全部在 `drawWithCache` 的构建块里按尺寸算一次；
 * [needleFraction] 是 State，在 `onDrawBehind` 里读，只触发重绘不触发重组。
 */
@Composable
private fun SpeedDial(
    needleFraction: State<Float>,
    progress: Float,
    accent: Color,
    trackColor: Color,
    tickColor: Color,
    labelColor: Color,
    modifier: Modifier = Modifier
) {
    val measurer = rememberTextMeasurer()
    val labelStyle: TextStyle = UfiTextStyles.caption.copy(color = labelColor)
    val labels: List<TextLayoutResult> = remember(labelStyle) {
        GAUGE_ANCHORS.map { measurer.measure(AnnotatedString(anchorLabel(it)), labelStyle) }
    }

    Box(modifier, contentAlignment = Alignment.Center) {
        Spacer(
            Modifier
                .fillMaxSize()
                .drawWithCache {
                    val strokeW = 13.dp.toPx()
                    val cx = size.width / 2f
                    val cy = size.height / 2f
                    val outerR = size.minDimension / 2f
                    val arcR = outerR - strokeW / 2f - 1.dp.toPx()
                    val arcTopLeft = Offset(cx - arcR, cy - arcR)
                    val arcSize = Size(arcR * 2, arcR * 2)

                    val tickOuterR = arcR - strokeW / 2f - 3.dp.toPx()
                    val majorLen = 9.dp.toPx()
                    val minorLen = 5.dp.toPx()
                    val labelR = tickOuterR - majorLen - 11.dp.toPx()
                    val needleLen = tickOuterR - 3.dp.toPx()
                    // 指针内端：中心 NEEDLE_INNER_RATIO 半径以内留给盘内读数，指针不进去
                    val needleInnerLen = needleLen * NEEDLE_INNER_RATIO
                    val lastIdx = GAUGE_ANCHORS.size - 1

                    fun pointAt(angleDeg: Float, radius: Float): Offset {
                        val rad = angleDeg * PI_F / 180f
                        return Offset(
                            cx + radius * cos(rad.toDouble()).toFloat(),
                            cy + radius * sin(rad.toDouble()).toFloat()
                        )
                    }

                    // 刻度：每两个锚点之间再插 4 根小刻度
                    val majorTicks = ArrayList<Pair<Offset, Offset>>(lastIdx + 1)
                    val minorTicks = ArrayList<Pair<Offset, Offset>>(lastIdx * 4)
                    val labelSpots = ArrayList<Offset>(lastIdx + 1)
                    for (i in 0..lastIdx) {
                        val angle = GAUGE_START_ANGLE + GAUGE_SWEEP_ANGLE * i / lastIdx
                        majorTicks += pointAt(angle, tickOuterR) to pointAt(angle, tickOuterR - majorLen)
                        val layout = labels[i]
                        val spot = pointAt(angle, labelR)
                        labelSpots += Offset(spot.x - layout.size.width / 2f, spot.y - layout.size.height / 2f)
                        if (i == lastIdx) continue
                        for (m in 1..4) {
                            val a = GAUGE_START_ANGLE + GAUGE_SWEEP_ANGLE * (i + m / 5f) / lastIdx
                            minorTicks += pointAt(a, tickOuterR) to pointAt(a, tickOuterR - minorLen)
                        }
                    }

                    val glowStroke = Stroke(width = strokeW + 8.dp.toPx(), cap = StrokeCap.Round)
                    val arcStroke = Stroke(width = strokeW, cap = StrokeCap.Round)
                    // 最外圈细弧：测试时间进度（已用时 / 最长时长）
                    val timeStroke = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
                    val timeR = outerR - 1.dp.toPx()
                    val timeTopLeft = Offset(cx - timeR, cy - timeR)
                    val timeSize = Size(timeR * 2, timeR * 2)
                    val timeSweep = GAUGE_SWEEP_ANGLE * progress.coerceIn(0f, 1f)
                    val majorStroke = 1.6.dp.toPx()
                    val minorStroke = 1.dp.toPx()
                    val needleStroke = 3.5.dp.toPx()

                    onDrawBehind {
                        drawArc(
                            color = trackColor,
                            startAngle = GAUGE_START_ANGLE,
                            sweepAngle = GAUGE_SWEEP_ANGLE,
                            useCenter = false,
                            topLeft = arcTopLeft,
                            size = arcSize,
                            style = arcStroke
                        )
                        minorTicks.forEach { (a, b) -> drawLine(tickColor, a, b, minorStroke) }
                        majorTicks.forEach { (a, b) -> drawLine(tickColor, a, b, majorStroke) }
                        labels.forEachIndexed { i, layout -> drawText(layout, topLeft = labelSpots[i]) }
                        if (timeSweep > 0.5f) {
                            drawArc(
                                color = accent.copy(alpha = 0.4f),
                                startAngle = GAUGE_START_ANGLE,
                                sweepAngle = timeSweep,
                                useCenter = false,
                                topLeft = timeTopLeft,
                                size = timeSize,
                                style = timeStroke
                            )
                        }

                        val f = needleFraction.value.coerceIn(0f, 1f)
                        if (f > 0.001f) {
                            val sweep = GAUGE_SWEEP_ANGLE * f
                            drawArc(
                                color = accent.copy(alpha = 0.16f),
                                startAngle = GAUGE_START_ANGLE,
                                sweepAngle = sweep,
                                useCenter = false,
                                topLeft = arcTopLeft,
                                size = arcSize,
                                style = glowStroke
                            )
                            drawArc(
                                color = accent,
                                startAngle = GAUGE_START_ANGLE,
                                sweepAngle = sweep,
                                useCenter = false,
                                topLeft = arcTopLeft,
                                size = arcSize,
                                style = arcStroke
                            )
                        }
                        val needleAngle = GAUGE_START_ANGLE + GAUGE_SWEEP_ANGLE * f
                        // 浮动段指针：内端从 needleInnerLen 起，中心那块圆盘留给读数
                        drawLine(
                            accent,
                            pointAt(needleAngle, needleInnerLen),
                            pointAt(needleAngle, needleLen),
                            needleStroke,
                            cap = StrokeCap.Round
                        )
                    }
                }
        )
    }
}



/**
 * 盘内中心读数：阶段（上）→ 数值（中，视觉主角）→ 单位（下），竖排居中，就是 Speedtest 的样子。
 *
 * 叠在 [SpeedDial] 同一个 Box 里，靠三点保证不被指针划过、也不会溢出压到下方元素：
 *  1. 指针是浮动段，中心 [NEEDLE_INNER_RATIO] 半径内没有线经过；
 *  2. 限宽 [READOUT_MAX_WIDTH] + 数值按位数降字号（[readoutFontSize]），字再多也不越过安全宽度；
 *  3. 父 Box 是**固定高度**槽位并 `clipToBounds`，不像 `Modifier.offset` 那样脱离布局。
 *
 * 阶段文案用 [Crossfade]，数值**不能**放进去——它每 150ms 更新一次，会变成持续闪烁。
 */
@Composable
private fun DialReadout(
    state: SpeedTestState,
    activeColor: Color,
    textPrimary: Color,
    textSecondary: Color
) {
    val idle = state.phase == SpeedTestPhase.IDLE || state.phase == SpeedTestPhase.ERROR
    val shown = if (state.phase == SpeedTestPhase.DONE) state.avgMbps else state.currentMbps
    val shownText = if (idle) "—" else FormatUtils.formatMbps(shown)
    Column(
        modifier = Modifier.widthIn(max = READOUT_MAX_WIDTH),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Crossfade(
            targetState = state.phase,
            animationSpec = tween(PHASE_SWITCH_MS),
            label = "phase"
        ) { phase ->
            val icon: ImageVector? = when (phase) {
                SpeedTestPhase.DOWNLOAD -> Icons.Default.KeyboardArrowDown
                SpeedTestPhase.UPLOAD -> Icons.Default.KeyboardArrowUp
                else -> null
            }
            val label = when (phase) {
                SpeedTestPhase.CONNECTING -> "连接中"
                SpeedTestPhase.LATENCY -> "测延迟"
                SpeedTestPhase.DOWNLOAD -> "下载中"
                SpeedTestPhase.UPLOAD -> "上传中"
                SpeedTestPhase.DONE -> "完成"
                SpeedTestPhase.ERROR -> "失败"
                SpeedTestPhase.IDLE -> "待测速"
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                if (icon != null) {
                    Icon(
                        icon,
                        contentDescription = null,
                        tint = activeColor,
                        modifier = Modifier.size(14.dp)
                    )
                }
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    color = if (icon != null) activeColor else textSecondary
                )
            }
        }
        Text(
            text = shownText,
            style = UfiTextStyles.monoMetric.copy(
                fontSize = readoutFontSize(shownText),
                fontWeight = UfiWeight.Strong
            ),
            maxLines = 1,
            textAlign = TextAlign.Center,
            color = if (idle) textSecondary else textPrimary
        )
        Text(
            text = "Mbps",
            style = MaterialTheme.typography.labelSmall,
            color = textSecondary,
            maxLines = 1
        )
    }
}

/** 盘内数值按位数降档：位数越多字号越小，保证整串不超出 [READOUT_MAX_WIDTH] */
private fun readoutFontSize(text: String): TextUnit = when {
    text.length >= 6 -> 30.sp   // 1000.0
    text.length >= 5 -> 36.sp   // 100.0 / 9.87
    else -> 42.sp
}



// ==================== 方向行（下载 / 上传） ====================

/**
 * 表盘正下方的方向行：**两列大数 + 中间竖分隔，无卡片容器**，固定高度 [DIRECTION_ROW_HEIGHT]。
 *
 * 这一块是整页除表盘外唯一的视觉焦点（Speedtest 盘下就是这两个大数），所以不给它卡片：
 *  - 最早是左右两张独立卡，各带 3dp 高亮条 + 描边 + 阴影，两圈边框两道阴影把表盘下方切碎；
 *  - 后来合成一张卡，但它和下方的次要信息卡成了两块等重白砖，主次分不出来。
 * 现在只靠字号（[DIRECTION_VALUE_SP]，比次要信息大一倍）和一条竖 hairline 分区。
 *
 * [uploadNote] 非 null 时上传格用小字顶掉数值（"该节点不测上行" / "节点未开上行"），
 * 避免把「没测」显示成 0。
 */
@Composable
private fun DirectionRow(
    downMbps: Double,
    upMbps: Double,
    uploadNote: String?,
    downActive: Boolean,
    upActive: Boolean,
    color: Color,
    modifier: Modifier = Modifier
) {
    val palette = LocalResolvedPalette.current
    Row(
        modifier = modifier.height(DIRECTION_ROW_HEIGHT),
        verticalAlignment = Alignment.CenterVertically
    ) {
        DirectionCell(
            title = "下载",
            icon = Icons.Default.KeyboardArrowDown,
            mbps = downMbps,
            note = null,
            active = downActive,
            color = color,
            modifier = Modifier.weight(1f)
        )
        Box(
            Modifier
                .width(1.dp)
                .height(38.dp)
                .background(palette.divider)
        )
        DirectionCell(
            title = "上传",
            icon = Icons.Default.KeyboardArrowUp,
            mbps = upMbps,
            note = uploadNote,
            active = upActive,
            color = color,
            modifier = Modifier.weight(1f)
        )
    }
}

/**
 * 方向行的一格：图标 + 标题（一行）→ 数值 + 单位（一行）。
 *
 * [active] 时数值与图标染色、标题也提亮，走 `animateColorAsState`
 * —— 阶段切换的视觉反馈全靠这个，纯文字变化用户看不出来。没有卡片就不再画底色块：
 * 无边框的底色块边缘是虚的，比不加更脏。
 */
@Composable
private fun DirectionCell(
    title: String,
    icon: ImageVector,
    mbps: Double,
    note: String?,
    active: Boolean,
    color: Color,
    modifier: Modifier = Modifier
) {
    val palette = LocalResolvedPalette.current
    val hasValue = note == null && mbps > 0
    val valueColor by animateColorAsState(
        targetValue = if (hasValue || active) color else palette.textSecondary,
        animationSpec = tween(PHASE_SWITCH_MS),
        label = "cellValue"
    )
    Column(
        modifier = modifier.fillMaxHeight(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                icon,
                contentDescription = null,
                tint = valueColor,
                modifier = Modifier.size(15.dp)
            )
            Text(
                text = title,
                style = MaterialTheme.typography.labelSmall,
                color = if (active) valueColor else palette.textSecondary
            )
        }
        Spacer(Modifier.height(2.dp))
        if (note != null) {
            Text(
                text = note,
                style = MaterialTheme.typography.labelSmall,
                color = palette.textSecondary,
                maxLines = 1
            )
        } else {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = if (mbps > 0) FormatUtils.formatMbps(mbps) else "—",
                    style = UfiTextStyles.monoMetric.copy(
                        fontSize = DIRECTION_VALUE_SP,
                        fontWeight = UfiWeight.Strong
                    ),
                    maxLines = 1,
                    color = valueColor
                )
                Spacer(Modifier.width(3.dp))
                Text(
                    text = "Mbps",
                    style = MaterialTheme.typography.labelSmall,
                    color = palette.textSecondary,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
            }
        }
    }
}

// ==================== 链路质量胶囊 / 次要信息格 ====================

/**
 * 表盘上方的延迟 / 抖动胶囊：一枚描边胶囊，内部两格 + 中间竖 hairline。
 *
 * 放在盘上而不是盘下的统计格里，是因为这两项是**链路质量**、和带宽不是一类指标；
 * 混进「用时 / 已传输 / 并发流」那一格会被当成过程量，视觉上还得和它们争同一份权重。
 */
@Composable
private fun LinkQualityPills(latencyMs: Double?, jitterMs: Double?, accent: Color) {
    val palette = LocalResolvedPalette.current
    // 圆角跟随全站基准（Spacing.CornerBase），不用整圆胶囊
    val pillShape = UfiCardDefaults.smallShape
    Row(
        modifier = Modifier
            .clip(pillShape)
            .background(palette.cardBg)
            .border(1.dp, palette.cardBorder, pillShape)
            .padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        PillMetric("延迟", latencyMs?.let { formatOneDecimal(it) + " ms" }, accent)
        Spacer(Modifier.width(10.dp))
        Box(
            Modifier
                .width(1.dp)
                .height(12.dp)
                .background(palette.divider)
        )
        Spacer(Modifier.width(10.dp))
        PillMetric("抖动", jitterMs?.let { formatOneDecimal(it) + " ms" }, accent)
    }
}

@Composable
private fun PillMetric(label: String, value: String?, accent: Color) {
    val palette = LocalResolvedPalette.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = palette.textSecondary)
        Spacer(Modifier.width(5.dp))
        Text(
            text = value ?: "—",
            style = UfiTextStyles.label.copy(fontWeight = UfiWeight.Emphasis),
            maxLines = 1,
            color = if (value == null) palette.textSecondary else accent
        )
    }
}

/**
 * 次要信息 2×2 田字格：横竖各一条 hairline，四格等宽。
 *
 * 用格线而不是纯留白分隔，是因为四个居中数字排在一起没有边界时会读成一串；
 * 项目里流量卡（`UfiTrafficTile(embedded = true)`）也是这个扁平格线风格。
 */
@Composable
private fun StatGrid(cells: List<Pair<String, String>>) {
    Column(Modifier.fillMaxWidth()) {
        cells.chunked(2).forEachIndexed { rowIndex, row ->
            if (rowIndex > 0) Hairline()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(STAT_CELL_HEIGHT),
                verticalAlignment = Alignment.CenterVertically
            ) {
                row.forEachIndexed { colIndex, (label, value) ->
                    if (colIndex > 0) VHairline()
                    UfiStatItem(
                        value = value,
                        label = label,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
private fun Hairline() {
    val palette = LocalResolvedPalette.current
    Box(
        Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(palette.divider)
    )
}

@Composable
private fun VHairline() {
    val palette = LocalResolvedPalette.current
    Box(
        Modifier
            .width(1.dp)
            .height(30.dp)
            .background(palette.divider)
    )
}

// ==================== 测速目标选择 ====================

/** 一个测速目标：内网（[nodeId] 为 null）或某个外网节点。[subtitle] 为 null 时不渲染副标行。 */
private data class TargetOption(
    val mode: String,
    val nodeId: String?,
    val title: String,
    val subtitle: String?,
    val icon: ImageVector
)

/**
 * 收起态的目标行：只显示**当前选中**的目标，点击打开 [TargetSelector] 弹窗。
 *
 * 常驻三行会在表盘下方占掉 ~170dp，把中间数据区压没；而"测哪里"是选一次就不再动的设置，
 * 不值得常占这么多高度。右侧箭头表示可展开。
 */
@Composable
private fun TargetPickerRow(
    option: TargetOption,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = LocalResolvedPalette.current
    Row(
        modifier = modifier
            .ufiStandardCard()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            option.icon,
            contentDescription = null,
            tint = palette.accent,
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = option.title,
                style = UfiTextStyles.bodyStrong,
                color = palette.textPrimary,
                maxLines = 1
            )
            option.subtitle?.takeIf { it.isNotBlank() }?.let { sub ->
                Text(
                    text = sub,
                    style = MaterialTheme.typography.labelSmall,
                    color = palette.textSecondary,
                    maxLines = 1
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = "更改",
            style = MaterialTheme.typography.labelSmall,
            color = palette.textSecondary
        )
        Icon(
            Icons.Default.KeyboardArrowRight,
            contentDescription = null,
            tint = palette.textSecondary,
            modifier = Modifier.size(18.dp)
        )
    }
}

/**
 * 测速目标单选列表：内网与每个外网节点摊平成若干行，行间 hairline。**只在弹窗里用**，
 * 所以自己不带卡片容器（弹窗本身就是卡）。
 *
 * 为什么不用 chip：目标其实是「内网 + N 个外网节点」的一维单选，chip 却强迫它拆成两排
 * （模式一排、节点一排）——两排等重、看不出谁管谁，而且 chip 格里塞不下「这个目标测什么」，
 * 只能在卡外再挂一行说明文字。行式单选一次点选到底，每行自带副标（下行 / 上行、是否只测下行）。
 */
@Composable
private fun TargetSelector(
    options: List<TargetOption>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier) {
        options.forEachIndexed { index, option ->
            if (index > 0) Hairline()
            TargetRow(
                option = option,
                selected = index == selectedIndex,
                onClick = { onSelect(index) }
            )
        }
    }
}

@Composable
private fun TargetRow(option: TargetOption, selected: Boolean, onClick: () -> Unit) {
    val palette = LocalResolvedPalette.current
    val bg by animateColorAsState(
        targetValue = if (selected) {
            palette.accent.copy(alpha = if (palette.isDark) 0.14f else 0.07f)
        } else {
            Color.Transparent
        },
        animationSpec = tween(TARGET_SWITCH_MS),
        label = "targetBg"
    )
    val tint by animateColorAsState(
        targetValue = if (selected) palette.accent else palette.textSecondary,
        animationSpec = tween(TARGET_SWITCH_MS),
        label = "targetTint"
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(bg)
            .selectable(
                selected = selected,
                role = Role.RadioButton,
                onClick = onClick
            )
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            option.icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = option.title,
                style = UfiTextStyles.body.copy(
                    fontWeight = if (selected) UfiWeight.Strong else UfiWeight.Medium
                ),
                color = if (selected) palette.accent else palette.textPrimary,
                maxLines = 1
            )
            option.subtitle?.takeIf { it.isNotBlank() }?.let { sub ->
                Text(
                    text = sub,
                    style = MaterialTheme.typography.labelSmall,
                    color = palette.textSecondary,
                    maxLines = 1
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        RadioDot(selected)
    }
}

/** 单选圆点：18dp 描边圆 + 选中时 8dp 实心内点 */
@Composable
private fun RadioDot(selected: Boolean) {
    val palette = LocalResolvedPalette.current
    val ring by animateColorAsState(
        targetValue = if (selected) palette.accent else palette.cardBorder,
        animationSpec = tween(TARGET_SWITCH_MS),
        label = "dotRing"
    )
    Box(
        modifier = Modifier
            .size(18.dp)
            .border(2.dp, ring, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        if (selected) {
            Box(
                Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(palette.accent)
            )
        }
    }
}

// ==================== 实时曲线 ====================

/**
 * 瞬时速率实时曲线：填充 + 折线，纵向按当前采样最大值自适应。
 * 几何在 `drawWithCache` 里按 (samples, size) 构建一次，重绘只重放。
 */
@Composable
private fun SpeedSparkline(
    samples: List<Float>,
    lineColor: Color,
    modifier: Modifier = Modifier
) {
    Spacer(
        modifier.drawWithCache {
            val n = samples.size
            val w = size.width
            val h = size.height
            val maxV = samples.max().coerceAtLeast(0.001f)
            val stepX = if (n > 1) w / (n - 1) else w
            val line = Path()
            val fill = Path()
            fill.moveTo(0f, h)
            for (i in 0 until n) {
                val x = stepX * i
                val y = h - (samples[i] / maxV) * (h - 2f) - 1f
                if (i == 0) line.moveTo(x, y) else line.lineTo(x, y)
                fill.lineTo(x, y)
            }
            fill.lineTo(if (n > 1) stepX * (n - 1) else w, h)
            fill.close()
            val stroke = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round)
            onDrawBehind {
                drawPath(fill, lineColor.copy(alpha = 0.16f))
                drawPath(line, lineColor, style = stroke)
            }
        }
    )
}

// ==================== 格式化 ====================

// 2026-08-31：私有 formatSpeed / formatBytes 已删除 —— 分别收敛到
// [com.ufi_axis.util.FormatUtils.formatMbps]（读数密度不变）与
// [com.ufi_axis.util.FormatUtils.formatSize]（"已传输" 现在 KB 段带一位小数、GB 段由两位改一位，并有 TB/PB 兜底）。

private fun formatOneDecimal(v: Double): String = String.format(Locale.US, "%.1f", v)

