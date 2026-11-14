package com.ufi_axis.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ufi_axis.data.model.BandStatusResponse
import com.ufi_axis.data.model.SignalInfo
import com.ufi_axis.ui.components.common.*
import com.ufi_axis.ui.theme.*
import com.ufi_axis.viewmodel.MainViewModel
import com.ufi_axis.viewmodel.state.NetworkState
import com.ufi_axis.viewmodel.state.deviceUnsupportedNote
import com.ufi_axis_core.contract.Capability

/** 这台设备支持的 LTE 频段（BandLockSection 的频段表，不是 core 能力集）。 */
private val supportLte = listOf(1, 3, 5, 8, 34, 38, 39, 40, 41)

/** 这台设备支持的 NR 频段。 */
private val supportNr = listOf(1, 5, 8, 28, 41, 78)

/**
 * 频段锁定组件，全屏 [BandLockScreen] 和 [BandLockDialog] 共用。
 *
 * ## 2026-11-08 重写：空锁 = 全部频段，而不是“一个都没选”
 *
 * 设备 `band-status` 用 `"0"` / `"all"` 表示该 RAT 没有限制；[BandStatusResponse] 把它
 * 归一化成空集。空集在设备语义里是**全部频段都允许**，不是“一个频段都不允许”
 * （core 也不接受空列表锁定：`NetworkController.lockBands` 会回 `no_bands`）。
 * 所以本组件把“设备报空集”映射成**该组全选**；空选只可能是加载中 / 用户正在编辑。
 *
 * ## 「全部解锁」不是清空勾选
 *
 * 用户点「全部解锁」= 两组都勾满 + 调 `lockBands(null, null)`。core 的 `unlockAll = true`
 * 会对 LTE / NR 各下发一次 `BandSelection.All`（profile 登记的全频段掩码），
 * 不是下发空列表，也不是把本地选中清空。回读 `"0"/"all"` → 空集 → 本组件再映射成全选，
 * 所以解锁后界面仍然是 15 个频段全勾。
 *
 * ## 按钮位置
 *
 * 「全部解锁」在左、「锁定所选」在右，跟随频段选择卡片的底部（同一张卡、分隔线下方），
 * 不再是滚动内容末尾的独立按钮卡。锁定按钮不显示已选数量。
 */
@Composable
fun BandLockSection(viewModel: MainViewModel, state: NetworkState) {
    var selectedLte by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var selectedNr by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var loaded by remember { mutableStateOf(false) }

    val capabilities by viewModel.network.capabilityState.collectAsState()
    val bandLockSupported = capabilities.supports(Capability.BAND_LOCK)

    // 本段被 BandLockDialog 复用，那条路径未必走过 loadNetworkAll，所以自己拉一次。
    // 能力集自带“本进程只成功拉一次”的闸门，无条件调不会每次进来都发请求。
    LaunchedEffect(Unit) { viewModel.network.loadDeviceCapabilities() }

    LaunchedEffect(state.bandStatus) {
        val status = state.bandStatus
        if (status == null) {
            loaded = false
            return@LaunchedEffect
        }
        loaded = true
        // 空集 = 该 RAT 没有限制 = 全部频段允许；见上方 KDoc。
        selectedLte = status.lteBands.ifEmpty { supportLte.toSet() }
        selectedNr = status.nrBands.ifEmpty { supportNr.toSet() }
    }

    val lteColor = BandLte
    val nrColor = BandNr
    val total = selectedLte.size + selectedNr.size
    val allLte = selectedLte.size == supportLte.size
    val allNr = selectedNr.size == supportNr.size
    val allSelected = allLte && allNr
    // 设备侧“没有限制”= 两个 band-status 都是空集。和“界面全选”分开：
    // 用户手选全 15 个但还没点锁定，是“待应用”，不是已经生效的“未限制”。
    val deviceUnrestricted = state.bandStatus?.let {
        (it.lteBands.isEmpty() || it.lteBands.size == supportLte.size) &&
            (it.nrBands.isEmpty() || it.nrBands.size == supportNr.size)
    } == true
    val mode = when {
        !loaded -> "读取中…"
        total == 0 -> "未选择任何频段"
        allSelected && deviceUnrestricted -> "全部频段（未限制）"
        allSelected -> "全部频段（待应用）"
        allLte -> "仅 NR 已锁定"
        allNr -> "仅 LTE 已锁定"
        else -> "LTE + NR 已锁定"
    }

    fun toggleLte(band: Int) {
        val next = if (band in selectedLte) selectedLte - band else selectedLte + band
        // 空集在设备语义里 = 全部允许；不要造出一个“一个都不选”的无效状态。
        selectedLte = next.ifEmpty { supportLte.toSet() }
    }

    fun toggleNr(band: Int) {
        val next = if (band in selectedNr) selectedNr - band else selectedNr + band
        selectedNr = next.ifEmpty { supportNr.toSet() }
    }

    fun unlockAll() {
        // 先让 UI 变成“全选”，core 的 All 写入成功后会回读 "0"/"all" → 仍是全选。
        selectedLte = supportLte.toSet()
        selectedNr = supportNr.toSet()
        // 设备本来就没有限制：只重置本地草稿，不必再发一次写 + 弹一次进度。
        if (!deviceUnrestricted) viewModel.network.lockBands(null, null)
    }

    fun lockSelected() {
        if (total == 0) return
        if (allLte && allNr) {
            // 全选就是“不限制”；走 core 的 unlockAll 分支，避免下发 15 个数字。
            viewModel.network.lockBands(null, null)
        } else {
            // 某一组全选 = 该组不限制，传 null 让 core 走 Only("") 清限制。
            // 不能传全集：设备回读会把“全频段”归一化成 "0"/"all" → 空集，
            // NetworkModule.verify() 拿空集和全集比会永远不相等，超时误报失败。
            viewModel.network.lockBands(
                selectedLte.takeIf { !allLte && it.isNotEmpty() },
                selectedNr.takeIf { !allNr && it.isNotEmpty() }
            )
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(Spacing.Medium)) {
        if (!bandLockSupported) {
            UfiNoticeCard(message = deviceUnsupportedNote("频段锁定"))
        }

        BandLockStatusCard(
            selectedLte = selectedLte,
            selectedNr = selectedNr,
            total = total,
            allLte = allLte,
            allNr = allNr,
            loaded = loaded,
            mode = mode,
            deviceUnrestricted = deviceUnrestricted,
            signalInfo = state.signalInfo,
        )

        BandSelectionCard(
            selectedLte = selectedLte,
            selectedNr = selectedNr,
            lteColor = lteColor,
            nrColor = nrColor,
            onToggleLte = { toggleLte(it) },
            onToggleNr = { toggleNr(it) },
            onSelectAllLte = { selectedLte = supportLte.toSet() },
            onSelectAllNr = { selectedNr = supportNr.toSet() },
            actions = {
                UfiButton(
                    text = "全部解锁",
                    onClick = { unlockAll() },
                    variant = UfiButtonVariant.Secondary,
                    enabled = bandLockSupported && loaded && !allSelected,
                    modifier = Modifier.weight(1f)
                )
                UfiButton(
                    text = "锁定所选",
                    onClick = { lockSelected() },
                    enabled = bandLockSupported && loaded && total > 0 &&
                        !(allSelected && deviceUnrestricted),
                    modifier = Modifier.weight(1f)
                )
            }
        )
    }
}

@Composable
private fun BandLockStatusCard(
    selectedLte: Set<Int>,
    selectedNr: Set<Int>,
    total: Int,
    allLte: Boolean,
    allNr: Boolean,
    loaded: Boolean,
    mode: String,
    deviceUnrestricted: Boolean,
    signalInfo: SignalInfo?,
) {
    val palette = LocalResolvedPalette.current
    val totalAvail = supportLte.size + supportNr.size
    val allSelected = allLte && allNr
    val dotColor = when {
        !loaded || total == 0 -> palette.textSecondary
        allSelected && deviceUnrestricted -> palette.textSecondary
        allSelected -> palette.warning
        else -> palette.success
    }
    val animatedDotColor by animateColorAsState(
        targetValue = dotColor,
        animationSpec = tween(UfiMotion.Duration.Quick),
        label = "bandLockDot"
    )

    Box(
        Modifier
            .fillMaxWidth()
            .ufiStandardCard(elevation = 3.dp)
            .padding(16.dp)
    ) {
        Column(
            Modifier.animateContentSize(
                animationSpec = tween(UfiMotion.Duration.Standard)
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(7.dp).clip(CircleShape).background(animatedDotColor))
                Spacer(Modifier.width(8.dp))
                Text(
                    "频段锁定",
                    style = UfiTextStyles.labelStrong,
                    color = palette.textPrimary
                )
                Spacer(Modifier.width(8.dp))
                AnimatedContent(
                    targetState = mode,
                    transitionSpec = {
                        (fadeIn(tween(UfiMotion.Duration.Quick)) + slideInVertically { it / 3 })
                            .togetherWith(
                                fadeOut(tween(UfiMotion.Duration.Micro)) +
                                    slideOutVertically { -it / 3 }
                            )
                    },
                    label = "bandLockMode",
                    modifier = Modifier.weight(1f)
                ) { text ->
                    Text(
                        text,
                        style = MaterialTheme.typography.labelSmall,
                        color = palette.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.width(88.dp)) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        AnimatedContent(
                            targetState = total,
                            transitionSpec = {
                                (slideInVertically { it / 2 } +
                                    fadeIn(tween(UfiMotion.Duration.Quick)))
                                    .togetherWith(
                                        slideOutVertically { -it / 2 } +
                                            fadeOut(tween(UfiMotion.Duration.Micro))
                                    )
                            },
                            label = "bandLockTotal"
                        ) { value ->
                            Text(
                                "$value",
                                style = MaterialTheme.typography.headlineLarge,
                                fontWeight = UfiWeight.Strong,
                                color = palette.textPrimary
                            )
                        }
                        Text(
                            " / $totalAvail",
                            style = MaterialTheme.typography.labelSmall,
                            color = palette.textSecondary,
                            modifier = Modifier.padding(bottom = 3.dp)
                        )
                    }
                    Text(
                        "已选频段",
                        style = MaterialTheme.typography.labelSmall,
                        color = palette.textSecondary
                    )
                }
                Spacer(Modifier.width(16.dp))
                Column(
                    Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    BandProgressRow("LTE", selectedLte.size, supportLte.size, BandLte)
                    BandProgressRow("NR", selectedNr.size, supportNr.size, BandNr)
                }
            }

            AnimatedContent(
                targetState = selectedLte to selectedNr,
                transitionSpec = {
                    fadeIn(tween(UfiMotion.Duration.Quick))
                        .togetherWith(fadeOut(tween(UfiMotion.Duration.Micro)))
                },
                label = "bandLockTokens",
                modifier = Modifier.fillMaxWidth()
            ) { (lte, nr) ->
                BandSelectionTokens(
                    selectedLte = lte,
                    selectedNr = nr,
                    allLte = lte.size == supportLte.size,
                    allNr = nr.size == supportNr.size,
                )
            }

            HorizontalDivider(color = palette.divider)
            BandServingLine(signalInfo)
        }
    }
}

@Composable
private fun BandProgressRow(
    label: String,
    count: Int,
    total: Int,
    color: Color,
) {
    val palette = LocalResolvedPalette.current
    val fraction = if (total > 0) count.toFloat() / total else 0f
    val animatedFraction by animateFloatAsState(
        targetValue = fraction.coerceIn(0f, 1f),
        animationSpec = tween(UfiMotion.Duration.Standard),
        label = "bandProgress"
    )

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = UfiWeight.Strong,
            color = color,
            modifier = Modifier.width(24.dp)
        )
        Spacer(Modifier.width(8.dp))
        Box(
            Modifier
                .weight(1f)
                .height(5.dp)
                .clip(CircleShape)
                .background(palette.divider.copy(alpha = 0.22f))
        ) {
            Box(
                Modifier
                    .fillMaxWidth(animatedFraction)
                    .fillMaxHeight()
                    .background(color)
            )
        }
        Spacer(Modifier.width(8.dp))
        Row(Modifier.width(36.dp), horizontalArrangement = Arrangement.End) {
            Text(
                "$count",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = UfiWeight.Strong,
                color = palette.textPrimary
            )
            Text(
                "/$total",
                style = MaterialTheme.typography.labelSmall,
                color = palette.textSecondary
            )
        }
    }
}

@Composable
private fun BandSelectionTokens(
    selectedLte: Set<Int>,
    selectedNr: Set<Int>,
    allLte: Boolean,
    allNr: Boolean,
) {
    val palette = LocalResolvedPalette.current
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        when {
            allLte && allNr -> BandToken("全部频段", palette.accent)
            else -> {
                if (allLte) {
                    BandToken("全部 LTE", BandLte)
                } else {
                    selectedLte.sorted().forEach { BandToken("B$it", BandLte) }
                }
                if (allNr) {
                    BandToken("全部 NR", BandNr)
                } else {
                    selectedNr.sorted().forEach { BandToken("N$it", BandNr) }
                }
            }
        }
    }
}

@Composable
private fun BandToken(text: String, color: Color) {
    val palette = LocalResolvedPalette.current
    Surface(
        color = color.copy(alpha = 0.14f),
        shape = UfiCardDefaults.microShape
    ) {
        Row(
            Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(4.dp).clip(CircleShape).background(color))
            Spacer(Modifier.width(5.dp))
            Text(
                text,
                style = MaterialTheme.typography.labelSmall,
                color = palette.textPrimary,
                fontWeight = UfiWeight.Medium
            )
        }
    }
}

@Composable
private fun BandServingLine(signalInfo: SignalInfo?) {
    val palette = LocalResolvedPalette.current
    val rsrp = signalInfo?.rsrp
    val rat = signalInfo?.rat?.trim()?.takeIf { it.isNotEmpty() }
    val band = signalInfo?.bandLabelDisplay
    val serving = listOfNotNull(rat, band).joinToString(" · ").ifEmpty { "--" }
    val signalText = if (rsrp == null) "RSRP --" else "RSRP $rsrp dBm · ${signalLevelText(rsrp)}"

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(if (serving != "--") palette.success else palette.textSecondary)
        )
        Spacer(Modifier.width(6.dp))
        Text(
            "当前驻网",
            style = MaterialTheme.typography.labelSmall,
            color = palette.textSecondary
        )
        Spacer(Modifier.width(6.dp))
        Text(
            serving,
            style = MaterialTheme.typography.labelSmall,
            color = palette.textPrimary,
            fontWeight = UfiWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            signalText,
            style = MaterialTheme.typography.labelSmall,
            color = palette.textSecondary,
            maxLines = 1
        )
        if (rsrp != null) {
            Spacer(Modifier.width(4.dp))
            UfiSignalBars(rsrp = rsrp, barWidth = 3.dp, maxHeight = 12.dp)
        }
    }
}

@Composable
private fun BandSelectionCard(
    selectedLte: Set<Int>,
    selectedNr: Set<Int>,
    lteColor: Color,
    nrColor: Color,
    onToggleLte: (Int) -> Unit,
    onToggleNr: (Int) -> Unit,
    onSelectAllLte: () -> Unit,
    onSelectAllNr: () -> Unit,
    actions: @Composable RowScope.() -> Unit,
) {
    val palette = LocalResolvedPalette.current

    Box(
        Modifier
            .fillMaxWidth()
            .ufiStandardCard()
    ) {
        Column {
            Column(
                Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(Spacing.Medium)
            ) {
                BandGroupHeader(
                    title = "LTE 频段",
                    count = selectedLte.size,
                    total = supportLte.size,
                    color = lteColor,
                    allSelected = selectedLte.size == supportLte.size,
                    onSelectAll = onSelectAllLte
                )
                UfiMultiChipSelector(
                    options = supportLte.map { it.toString() to "B$it" },
                    selectedValues = selectedLte.map { it.toString() }.toSet(),
                    onToggle = { onToggleLte(it.toInt()) },
                    accentColor = lteColor,
                    chipHeight = 44.dp,
                    chipPaddingH = 18.dp,
                    chipTextStyle = UfiTextStyles.label
                )

                HorizontalDivider(color = palette.divider)

                BandGroupHeader(
                    title = "NR 频段",
                    count = selectedNr.size,
                    total = supportNr.size,
                    color = nrColor,
                    allSelected = selectedNr.size == supportNr.size,
                    onSelectAll = onSelectAllNr
                )
                UfiMultiChipSelector(
                    options = supportNr.map { it.toString() to "N$it" },
                    selectedValues = selectedNr.map { it.toString() }.toSet(),
                    onToggle = { onToggleNr(it.toInt()) },
                    accentColor = nrColor,
                    chipHeight = 44.dp,
                    chipPaddingH = 18.dp,
                    chipTextStyle = UfiTextStyles.label
                )
            }

            HorizontalDivider(color = palette.divider)
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(Spacing.Medium),
                verticalAlignment = Alignment.CenterVertically,
                content = actions
            )
        }
    }
}

@Composable
private fun BandGroupHeader(
    title: String,
    count: Int,
    total: Int,
    color: Color,
    allSelected: Boolean,
    onSelectAll: () -> Unit,
) {
    val palette = LocalResolvedPalette.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            title,
            style = UfiTextStyles.labelStrong,
            color = color
        )
        Spacer(Modifier.width(6.dp))
        Text(
            "$count/$total",
            style = MaterialTheme.typography.labelSmall,
            color = palette.textSecondary
        )
        Spacer(Modifier.weight(1f))
        // 2026-11-08：无论 allSelected 与否都占住 52×36 的槽位。
        // 之前全选时把“全选”整个移除，取消一个频段后按钮突然出现 → 行高/宽度跳变。
        // 现在槽位固定，按钮只在槽位内淡入/缩放，不会再触发 Re-layout。
        Box(
            Modifier.width(52.dp).height(36.dp),
            contentAlignment = Alignment.CenterEnd
        ) {
            androidx.compose.animation.AnimatedVisibility(
                visible = !allSelected,
                enter = fadeIn(tween(UfiMotion.Duration.Quick)) + scaleIn(initialScale = 0.92f),
                exit = fadeOut(tween(UfiMotion.Duration.Micro)) + scaleOut(targetScale = 0.92f)
            ) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(onClick = onSelectAll),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "全选",
                        style = UfiTextStyles.label,
                        color = color
                    )
                }
            }
        }
    }
}
