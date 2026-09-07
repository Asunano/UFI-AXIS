package com.ufi_axis.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.BatteryStd
import androidx.compose.material.icons.filled.DataUsage
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.ufi_axis.data.model.AlertConfig
import com.ufi_axis.util.FormatUtils
import com.ufi_axis.ui.components.common.*
import com.ufi_axis.ui.theme.*
import com.ufi_axis.viewmodel.MainViewModel
import kotlin.math.roundToInt

/**
 * 告警设置页（alert-notification-plan 阶段 A+B）：
 * - 告警总开关（core 端 AlertConfig.enabled 镜像）
 * - 告警类型与阈值合并成一行：右侧开关控制该类型是否检测，点击整行开阈值弹窗，
 *   副文案直接显示当前阈值（用户要求 2026-08-30）。
 * - 阈值输入失焦提交（防每字符 PUT 请求）。
 *
 * 2026-08-30：通知投递开关与免打扰时段已上移到 [NotificationsGuardScreen]（那是全局设置），
 * 本页只保留「哪些告警要检测、阈值多少」这类告警自身的配置。
 *
 * 布局：UfiScreenScaffold + UfiPageBackground + UfiSettingsGroup 分组卡。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlertSettingsScreen(
    viewModel: MainViewModel,
    navController: NavHostController
) {
    val alertsState by viewModel.tools.alertsState.collectAsState()
    val alertPrefs by viewModel.alertPrefs.configFlow.collectAsState()
    val config = alertPrefs ?: alertsState.config
    val palette = LocalResolvedPalette.current

    // 总开关状态（core 端唯一真源镜像）：enabled=false → 引擎不检测/不入库/不广播
    // 2026-09-07：兜底由 `?: true` 改成 `?: false`，与 NotificationsGuardScreen.kt 的
    // `alertPrefs?.enabled ?: false` 及 AlertConfig.enabled 的新默认值逐字一致 ——
    // 配置还没拉到就先显示"总开关开着"，会让整页各行显示为可编辑而实际写不进去。
    val masterEnabled = config?.enabled ?: false
    // T13：镜像是否已从设备拉到。未加载完成时**所有写操作控件禁用** ——
    // 否则用户点开关会用本地默认值 + configVersion=1 提交，可能把别端配置写成默认值。
    val configLoaded = config != null

    // 当前编辑的阈值类型（null = 弹窗关闭）
    var editingType by rememberSaveable { mutableStateOf<String?>(null) }

    // 拉取最新配置（连接即拉取镜像）
    LaunchedEffect(Unit) {
        viewModel.tools.loadAlerts()
    }

    // 写总开关：经 AlertPrefsRepository（带 version 守门 + 409 重试）
    // T13：镜像为空时直接放弃（ViewModel 侧也有同样守卫，这里避免连提示都走一遍网络）
    fun pushConfig(patch: AlertConfig.() -> AlertConfig) {
        val base = config ?: return
        viewModel.tools.updateAlertConfig(base.patch())
    }

    /** 单类告警开关：只改 perType 里那一个键，其余键原样带回（漏带 = 把别的类型重置成默认）。 */
    fun togglePerType(typeKey: String, on: Boolean) {
        val next = (config?.perType ?: emptyMap()).toMutableMap().apply { put(typeKey, on) }
        pushConfig { copy(perType = next) }
    }


    // 有效配置：**仅用于展示**（未加载时用本地默认值渲染，避免设置项空白）。
    // 写入路径一律走 pushConfig（内部 `config ?: return`），禁止用 effective 构造 PUT 载荷。
    val effective = config ?: AlertConfig()

    UfiScreenScaffold(title = "告警设置", navController = navController, showBack = true) { padding ->
        UfiPageBackground(modifier = Modifier.padding(padding)) {

            // ① 总开关已于 2026-08-30 从本页删除：[NotificationsGuardScreen] 的 Hero 卡
            //   就是同一个 `AlertConfig.enabled`，两处摆同一个开关只会让人怀疑哪个才算数。
            //   本页仍读它来决定各行是否可编辑（关闭时副标显示「总开关已关闭」）。

            // 镜像未拉到时的说明。原来夹在「免打扰」和「阈值配置」之间，
            // 但它解释的是整页为什么不可编辑，放到最上面才看得见。
            if (config == null) {
                UfiSettingsGroup {
                    Text(
                        text = alertsState.errorMessage?.let { "无法读取设备上的告警配置（$it）—— 下方显示默认值，暂不可修改，请检查连接后重试" }
                            ?: "告警配置加载中... 下方显示默认值，暂不可修改",
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.textSecondary
                    )
                }
            }

            // ② 原「通知渠道」组（系统通知推送 + 发送测试通知）已于 2026-08-30 上移到
            //   [NotificationsGuardScreen] 的 Hero 卡下方：它是整个通知体系的总体设置
            //   （管的是"通道通不通"），不只服务于告警，放在告警页属于层级错位。
            //   免打扰（⑥）同理一并上移。

            // ═══════════ ③ 告警类型与阈值（同一件事，2026-08-30 合并成一组） ═══════════
            // 合并前是两张卡：「告警类型」只有开关、「阈值配置」只有入口，用户要在两张卡之间
            // 来回对照才知道"温度告警开着、但阈值是多少"。现在一行搞定：
            //   右侧开关 = 这类要不要报；点整行 = 改阈值；副标 = 当前生效阈值。
            // 连接性告警没有阈值可调（固定"断开超 1 分钟触发"），所以它不可点击。
            // T13：镜像未加载完成时整组不可点（点开弹窗保存会把默认值写回设备）
            UfiSettingsGroup {
                UfiGroupHeader("告警类型与阈值")
                AlertTypeRow(
                    label = "温度告警",
                    summary = "警告 ${fmt(effective.temperatureWarning)} °C · 严重 ${fmt(effective.temperatureCritical)} °C",
                    icon = Icons.Default.Thermostat,
                    checked = config?.perType?.get("temperature") ?: false,
                    enabled = masterEnabled && configLoaded,
                    configLoaded = configLoaded,
                    masterEnabled = masterEnabled,
                    onCheckedChange = { togglePerType("temperature", it) },
                    onClick = { editingType = "temperature" }
                )
                AlertTypeRow(
                    label = "电量告警",
                    summary = "警告 ${effective.batteryWarning} % · 严重 ${effective.batteryCritical} %",
                    icon = Icons.Default.BatteryStd,
                    checked = config?.perType?.get("battery") ?: false,
                    enabled = masterEnabled && configLoaded,
                    configLoaded = configLoaded,
                    masterEnabled = masterEnabled,
                    onCheckedChange = { togglePerType("battery", it) },
                    onClick = { editingType = "battery" }
                )
                AlertTypeRow(
                    label = "流量告警",
                    summary = "警告 ${FormatUtils.formatBytes(effective.trafficWarningMb * 1024 * 1024)} · 严重 ${FormatUtils.formatBytes(effective.trafficCriticalMb * 1024 * 1024)}",
                    icon = Icons.Default.DataUsage,
                    checked = config?.perType?.get("traffic") ?: false,
                    enabled = masterEnabled && configLoaded,
                    configLoaded = configLoaded,
                    masterEnabled = masterEnabled,
                    onCheckedChange = { togglePerType("traffic", it) },
                    onClick = { editingType = "traffic" }
                )
                AlertTypeRow(
                    label = "信号告警",
                    summary = "警告 ${effective.signalWarningRsrp} dBm · 严重 ${effective.signalCriticalRsrp} dBm",
                    icon = Icons.Default.SignalCellularAlt,
                    checked = config?.perType?.get("signal") ?: false,
                    enabled = masterEnabled && configLoaded,
                    configLoaded = configLoaded,
                    masterEnabled = masterEnabled,
                    onCheckedChange = { togglePerType("signal", it) },
                    onClick = { editingType = "signal" }
                )
                AlertTypeRow(
                    label = "连接性告警",
                    summary = "断开超过 1 分钟触发 · 恢复后自动消警（无阈值可调）",
                    icon = Icons.Default.LinkOff,
                    checked = config?.perType?.get("connectivity") ?: false,
                    enabled = masterEnabled && configLoaded,
                    configLoaded = configLoaded,
                    masterEnabled = masterEnabled,
                    onCheckedChange = { togglePerType("connectivity", it) },
                    onClick = null
                )
            }


            // ⑤ 原「日常通知」6 个场景已于 2026-08-30 迁到 [DailyNotifyScreen]

            //   （Routes.DETAIL_DAILY_NOTIFY）。它们不看阈值、不入 alerts 表、不受本页
            //   AlertConfig.enabled 约束，放在告警页只会拉长这一屏并造成"关总开关它们也停"的误解。


            // ⑥ 免打扰同样上移到 [NotificationsGuardScreen]：它是全局静默时段，
            //   与"报哪些类型 / 阈值多少"无关，和「系统通知推送」一起属于通道级总体设置。

            Spacer(Modifier.height(Spacing.Large))
        }
    }

    // 弹窗：4 类阈值编辑。温度/电量/信号走滑块（UfiRangeSlider，值域固定）；
    // 流量走 [TrafficThresholdDialog]（输入框 + MB/GB 单位选择，2026-09-07）。
    when (editingType) {
        "temperature" -> ThresholdEditDialog(
            title = "温度阈值",
            unit = "°C",
            warnLabel = "警告",
            alertLabel = "严重",
            initialWarn = fmt(effective.temperatureWarning),
            initialAlert = fmt(effective.temperatureCritical),
            parseWarn = { it.toDoubleOrNull() },
            parseAlert = { it.toDoubleOrNull() },
            sliderRange = -20f..100f,
            steps = 240,
            tickStep = 10f,
            decimals = 1,
            onDismiss = { editingType = null },
            onSave = { warn, alert ->
                // T13：写入基准必须是服务端配置本身（pushConfig 内部 `config ?: return`），
                // 不能用带默认值兜底的 effective，否则未加载时会把默认值写回设备。
                pushConfig {
                    copy(
                        temperatureWarning = warn.toDoubleOrNull() ?: temperatureWarning,
                        temperatureCritical = alert.toDoubleOrNull() ?: temperatureCritical
                    )
                }
                editingType = null
            }
        )
        "battery" -> ThresholdEditDialog(
            title = "电量阈值",
            unit = "%",
            warnLabel = "警告",
            alertLabel = "严重",
            initialWarn = effective.batteryWarning.toString(),
            initialAlert = effective.batteryCritical.toString(),
            parseWarn = { it.toIntOrNull() },
            parseAlert = { it.toIntOrNull() },
            sliderRange = 0f..100f,
            steps = 100,
            tickStep = 10f,
            decimals = 0,
            onDismiss = { editingType = null },
            onSave = { warn, alert ->
                pushConfig {
                    copy(
                        batteryWarning = warn.toIntOrNull() ?: batteryWarning,
                        batteryCritical = alert.toIntOrNull() ?: batteryCritical
                    )
                }
                editingType = null
            }
        )
        // 2026-09-07：流量阈值**不再用滑块**。用户的流量额度因人而异（1 GB / 30 GB / 500 GB
        // 都常见），原来固定 0..10240 MB、steps=1024 的双 thumb 滑块要么够不到要么精度太粗。
        // 改成「直接输入数值 + 选单位（MB/GB）」，见 [TrafficThresholdDialog]。
        // 存储字段与单位不变（trafficWarningMb / trafficCriticalMb，Long，MB），
        // 保存路径仍是 onSave → pushConfig { copy(...) }。
        "traffic" -> TrafficThresholdDialog(
            initialWarnMb = effective.trafficWarningMb,
            initialCriticalMb = effective.trafficCriticalMb,
            onDismiss = { editingType = null },
            onSave = { warnMb, criticalMb ->
                pushConfig {
                    copy(
                        trafficWarningMb = warnMb,
                        trafficCriticalMb = criticalMb
                    )
                }
                editingType = null
            }
        )
        "signal" -> ThresholdEditDialog(
            title = "信号阈值",
            unit = "dBm",
            warnLabel = "警告",
            alertLabel = "严重",
            initialWarn = effective.signalWarningRsrp.toString(),
            initialAlert = effective.signalCriticalRsrp.toString(),
            parseWarn = { it.toIntOrNull() },
            parseAlert = { it.toIntOrNull() },
            sliderRange = -140f..-40f,
            steps = 100,
            tickStep = 10f,
            decimals = 0,
            onDismiss = { editingType = null },
            onSave = { warn, alert ->
                pushConfig {
                    copy(
                        signalWarningRsrp = warn.toIntOrNull() ?: signalWarningRsrp,
                        signalCriticalRsrp = alert.toIntOrNull() ?: signalCriticalRsrp
                    )
                }
                editingType = null
            }
        )
    }
}

/** 告警类型行：图标 + 标题 + 当前阈值摘要 + 右侧开关；整行可点即打开阈值弹窗（无箭头）。 */
@Composable
private fun AlertTypeRow(
    label: String,
    summary: String,
    icon: ImageVector,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    configLoaded: Boolean,
    masterEnabled: Boolean,
    enabled: Boolean = true,
    /** 该类型有阈值可调时传弹窗打开动作；null = 无阈值（整行不可点）。 */
    onClick: (() -> Unit)? = null
) {
    val palette = LocalResolvedPalette.current
    // 副标优先说明"为什么不能改"，其次才是阈值摘要 —— 否则用户看到阈值却点不动会以为坏了
    val subtitle = when {
        !configLoaded -> "配置加载中，暂不可修改"
        !masterEnabled -> "总开关已关闭"
        else -> summary
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (onClick != null) Modifier.clickable(enabled = enabled, onClick = onClick)
                else Modifier
            )
            .padding(horizontal = 4.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(22.dp),
            tint = if (enabled) palette.accent else palette.textSecondary
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                label,
                style = UfiTextStyles.listItemTitle,
                color = if (enabled) palette.textPrimary else palette.textSecondary
            )
            Text(
                subtitle,
                style = UfiTextStyles.note,
                color = palette.textSecondary.copy(alpha = 0.75f),
                maxLines = 1
            )
        }
        // 不画右箭头（2026-08-30 用户要求）：这一行右侧已经有开关，再加箭头会让人
        // 分不清"点箭头"和"点开关"是两个动作。可点性由副标里的阈值摘要本身暗示。
        // 2026-08-31：M3 Switch → 公共 UfiSwitch（配色 token 相同，几何统一到 42×24dp）
        UfiSwitch(
            checked = checked,
            enabled = enabled,
            onCheckedChange = onCheckedChange
        )
    }
}

/** 阈值编辑弹窗：纯滑块（自定义 UfiRangeSlider）+ 双色值标签 + 取消/保存
 * 2026-08-09 20:19 改用公共 UfiCustomDialog（UfiCustomDialog.kt L21）：标准弹窗样式 + 自定义 confirm/dismiss 槽位。
 * 之前用 Material3 AlertDialog 内部样式与项目主题不一致（无圆角/边框/scrim），且不引用公共组件违反规范。
 *
 * 2026-08-12：① 移除双输入框，仅保留滑块控制（滑块直接双向绑定 warn/alert 文本态）；
 *            ② 重写 UfiRangeSlider 交互——单一 pointerInput 统一处理「命中最近 thumb / 轨道跳转 / 拖动」，
 *               修复原父级 detectTapGestures 吞掉子级拖动事件导致「只能点不能拖」的问题；
 *            ③ 左右 thumb 用不同角色色（严重=橙 / 警告=accent）区分，弹窗顶部双色标签同步显示角色与实时数值。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ThresholdEditDialog(
    title: String,
    unit: String,
    warnLabel: String,
    alertLabel: String,
    initialWarn: String,
    initialAlert: String,
    parseWarn: (String) -> Number?,
    parseAlert: (String) -> Number?,
    sliderRange: ClosedFloatingPointRange<Float>,
    steps: Int = 0,
    /** 刻度步长（值域单位）；滑块下方按它画刻度点，拖动时淡入标签 */
    tickStep: Float = 0f,
    decimals: Int = 0,
    onDismiss: () -> Unit,
    onSave: (warn: String, alert: String) -> Unit
) {
    var warnText by remember { mutableStateOf(initialWarn) }
    var alertText by remember { mutableStateOf(initialAlert) }
    val palette = LocalResolvedPalette.current
    fun formatValue(v: Float): String = when {
        decimals <= 0 -> v.roundToInt().toString()
        else -> "%.${decimals}f".format(v)
    }

    UfiCustomDialog(
        visible = true,
        onDismiss = onDismiss,
        title = title,
        icon = rememberVectorPainter(Icons.Filled.Notifications),
        confirmButton = {
            // 保存前校验：两个值都必须是有效数字（parse 非 null）
            // 用 variant = Primary + 默认 Standard 档，与 dismiss 位的 Secondary 等高（都是 ButtonHeight）；
            // 换成 size = Small 会更矮，两者并排会一高一矮。
            UfiButton(
                text = "保存",
                onClick = {
                    if (parseWarn(warnText) != null && parseAlert(alertText) != null) onSave(warnText, alertText)
                }
            )
        },
        dismissButton = {
            UfiButton(
                variant = UfiButtonVariant.Secondary,
                text = "取消",
                onClick = onDismiss
            )
        }
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.Large)) {
            // 当前值双标签（左=小值端，右=大值端；角色随阈值类型而变）
            val warnF = parseWarn(warnText)?.toFloat() ?: sliderRange.start
            val alertF = parseAlert(alertText)?.toFloat() ?: sliderRange.start
            val warnCoerced = warnF.coerceIn(sliderRange)
            val alertCoerced = alertF.coerceIn(sliderRange)
            // reverse = true：warning 数值小于 critical（如温度/流量），slider 左端=warning，右端=critical
            val reverse = warnCoerced < alertCoerced
            val sliderStart = alertCoerced.coerceAtMost(warnCoerced)
            val sliderEnd = alertCoerced.coerceAtLeast(warnCoerced)

            // 角色色：严重=palette.warning（橙），警告=palette.accent
            val criticalColor = palette.warning
            val warningColor = palette.accent
            // 左端（小值端）角色
            val leftIsCritical = !reverse
            val leftColor = if (leftIsCritical) criticalColor else warningColor
            val leftRole = if (leftIsCritical) alertLabel else warnLabel
            val leftValue = if (leftIsCritical) alertText else warnText
            val rightColor = if (leftIsCritical) warningColor else criticalColor
            val rightRole = if (leftIsCritical) warnLabel else alertLabel
            val rightValue = if (leftIsCritical) warnText else alertText

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                SliderValueChip(role = leftRole, value = leftValue, unit = unit, color = leftColor)
                SliderValueChip(role = rightRole, value = rightValue, unit = unit, color = rightColor)
            }

            UfiRangeSlider(
                values = sliderStart..sliderEnd,
                valueRange = sliderRange,
                steps = steps,
                startThumbColor = leftColor,
                endThumbColor = rightColor,
                tickStep = tickStep,
                // 标签只在拖动时淡入，所以这里可以放不带单位的裸数值（单位在上方双色标签里）
                tickLabelFormatter = { formatValue(it) },
                onValuesChange = { range ->
                    if (reverse) {
                        // 左端 = warning（较小），右端 = critical（较大）
                        warnText = formatValue(range.start)
                        alertText = formatValue(range.endInclusive)
                    } else {
                        // 左端 = critical（较小），右端 = warning（较大）
                        alertText = formatValue(range.start)
                        warnText = formatValue(range.endInclusive)
                    }
                }
            )
        }
    }
}

// ══════════════════════════════════════════════════════════════
// 流量阈值：输入框 + 单位选择（2026-09-07 替换原双 thumb 滑块）
// ══════════════════════════════════════════════════════════════

/** 单位标识；与展示文案同值，所以 UfiSingleChipSelector 的 options 两侧都用它。 */
private const val TRAFFIC_UNIT_MB = "MB"
private const val TRAFFIC_UNIT_GB = "GB"

/** GB → MB 的换算系数。存储字段 [AlertConfig.trafficWarningMb] 的单位恒为 MB。 */
private const val MB_PER_GB = 1024L

/**
 * MB → 「显示数值 + 单位」。
 *
 * 能被 1024 整除就用 GB 显示（1024 MB → 「1 GB」，默认阈值就是 1 GB / 2 GB），
 * 否则原样按 MB 显示（如 1500 MB）—— 不做四舍五入，避免"打开弹窗再保存"就把值改了。
 */
private fun splitTrafficUnit(mb: Long): Pair<String, String> =
    if (mb > 0L && mb % MB_PER_GB == 0L) {
        (mb / MB_PER_GB).toString() to TRAFFIC_UNIT_GB
    } else {
        mb.toString() to TRAFFIC_UNIT_MB
    }

/**
 * 「输入数值 + 单位」→ MB。非正整数（空串 / 0 / 超 Long / 非数字）返回 null，
 * 调用方据此禁用保存并给提示。
 */
private fun trafficToMb(text: String, unit: String): Long? {
    val value = text.trim().toLongOrNull() ?: return null
    if (value <= 0L) return null
    return if (unit == TRAFFIC_UNIT_GB) value * MB_PER_GB else value
}

/**
 * 流量阈值弹窗：两个「数值 + 单位」字段（公共组件 [UfiDialogValueUnitField]，
 * 内部由 UfiDigitField + UfiSingleChipSelector 拼装）。
 *
 * 为什么不复用 [ThresholdEditDialog]：那个弹窗的交互本体是双 thumb 滑块，必须有一个
 * 有限值域；而流量额度上不封顶（用户可能是 500 GB 套餐），滑块在这里天然不合适。
 * 温度/电量/信号仍走滑块——它们的物理值域是固定的，滑块比键盘快。
 *
 * 校验语义与原弹窗一致并更严：两值都必须是正整数，且**警告 < 严重**，否则保存按钮禁用
 * （原弹窗只校验"能解析成数字"）。
 *
 * @param onSave 已换算成 MB 的两个值，调用方直接 `copy(trafficWarningMb = ..., trafficCriticalMb = ...)`。
 */
@Composable
private fun TrafficThresholdDialog(
    initialWarnMb: Long,
    initialCriticalMb: Long,
    onDismiss: () -> Unit,
    onSave: (warnMb: Long, criticalMb: Long) -> Unit
) {
    val palette = LocalResolvedPalette.current
    val (warnInitValue, warnInitUnit) = splitTrafficUnit(initialWarnMb)
    val (criticalInitValue, criticalInitUnit) = splitTrafficUnit(initialCriticalMb)
    var warnText by remember { mutableStateOf(warnInitValue) }
    var warnUnit by remember { mutableStateOf(warnInitUnit) }
    var criticalText by remember { mutableStateOf(criticalInitValue) }
    var criticalUnit by remember { mutableStateOf(criticalInitUnit) }

    val warnMb = trafficToMb(warnText, warnUnit)
    val criticalMb = trafficToMb(criticalText, criticalUnit)
    // 顺序校验必须在**换算之后**比：1 GB 与 900 MB 直接比数字会得出反的结论
    val orderInvalid = warnMb != null && criticalMb != null && warnMb >= criticalMb
    val canSave = warnMb != null && criticalMb != null && !orderInvalid
    val unitOptions = listOf(TRAFFIC_UNIT_MB to TRAFFIC_UNIT_MB, TRAFFIC_UNIT_GB to TRAFFIC_UNIT_GB)

    UfiCustomDialog(
        visible = true,
        onDismiss = onDismiss,
        title = "流量阈值",
        icon = rememberVectorPainter(Icons.Filled.DataUsage),
        confirmButton = {
            UfiButton(
                text = "保存",
                enabled = canSave,
                onClick = { if (canSave) onSave(warnMb!!, criticalMb!!) }
            )
        },
        dismissButton = {
            UfiButton(
                variant = UfiButtonVariant.Secondary,
                text = "取消",
                onClick = onDismiss
            )
        }
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.Large)) {
            UfiDialogValueUnitField(
                label = "警告阈值",
                value = warnText,
                onValueChange = { warnText = it },
                unitOptions = unitOptions,
                selectedUnit = warnUnit,
                onUnitChange = { warnUnit = it },
                placeholder = "如 1",
                isError = warnMb == null,
                errorMessage = if (warnMb == null) "请输入大于 0 的整数" else null
            )
            UfiDialogValueUnitField(
                label = "严重阈值",
                value = criticalText,
                onValueChange = { criticalText = it },
                unitOptions = unitOptions,
                selectedUnit = criticalUnit,
                onUnitChange = { criticalUnit = it },
                placeholder = "如 2",
                isError = criticalMb == null || orderInvalid,
                errorMessage = when {
                    criticalMb == null -> "请输入大于 0 的整数"
                    orderInvalid -> "严重阈值必须大于警告阈值"
                    else -> null
                }
            )
            // 回显换算后的实际生效值：单位选错（把 2 GB 当 2 MB）在这一行立刻看得出来
            Text(
                text = if (canSave) {
                    "生效阈值：警告 ${FormatUtils.formatBytes(warnMb!! * 1024 * 1024)}" +
                        " · 严重 ${FormatUtils.formatBytes(criticalMb!! * 1024 * 1024)}"
                } else {
                    "两项都必须是大于 0 的整数，且警告阈值小于严重阈值"
                },
                style = UfiTextStyles.note,
                color = if (canSave) palette.textSecondary else palette.error
            )
        }
    }
}

/** 滑块上方双色值标签：圆点 + 角色 + 数值 + 单位，与对应 thumb 颜色一致 */
@Composable
private fun SliderValueChip(
    role: String,
    value: String,
    unit: String,
    color: Color
) {
    val palette = LocalResolvedPalette.current
    Surface(
        shape = UfiCardDefaults.subtleShape,
        color = color.copy(alpha = 0.12f),
        border = BorderStroke(1.dp, color.copy(alpha = 0.5f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(color)
            )
            Spacer(Modifier.width(6.dp))
            Text(role, style = MaterialTheme.typography.labelSmall, color = palette.textSecondary)
            Spacer(Modifier.width(4.dp))
            Text(
                value,
                style = UfiTextStyles.bodyEmphasis,
                color = palette.textPrimary
            )
            Text(" $unit", style = MaterialTheme.typography.labelSmall, color = palette.textSecondary)
        }
    }
}

private fun fmt(d: Double): String = if (d == d.toLong().toDouble()) d.toLong().toString() else d.toString()

