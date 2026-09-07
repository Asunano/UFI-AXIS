// [F24] STABLE-UI-API：公共组件签名已冻结，请勿在无向后兼容前提下修改；实验性组件请使用 @UfiExperimentalApi（见 UfiStableApi.kt / UfiExperimentalApi.kt）。
package com.ufi_axis.ui.components.common

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.ufi_axis.ui.theme.LocalResolvedPalette
import com.ufi_axis.ui.theme.Spacing
import com.ufi_axis.ui.theme.UfiCardDefaults
import com.ufi_axis.ui.theme.ufiCardShadow
// 本文件所在包内有一个同名的 @Deprecated 转发壳 `UfiMotion`（Ufi.kt，P3a 留给旧调用点）。
// 这里按 Ufi.kt 自己的做法用别名指向 ui.theme 下的**正本**，避免歧义与废弃告警。
import com.ufi_axis.ui.theme.UfiMotion as ThemeMotion
import com.ufi_axis.ui.theme.UfiTextStyles
import com.ufi_axis.ui.theme.UfiWeight
import kotlinx.coroutines.launch

/*
 * ╔══════════════════════════════════════════════════════════════════════╗
 * ║  下拉单选 —— 全站唯一实现（2026-09-04 P4e）                            ║
 * ╚══════════════════════════════════════════════════════════════════════╝
 *
 * 收敛前同一种「触发器 + 悬浮单选列表」交互有三套实现：
 *
 * 1. 本文件旧版 `UfiDropdown` —— M3 `ExposedDropdownMenuBox` + `OutlinedTextField`：
 *    56dp 带浮动 label 的输入框 + 实心三角箭头 + 原生 `DropdownMenu`。弹层容器色走 M3
 *    `surfaceContainer`，**未被 palette 覆盖**，于是它是全库唯一偏紫的弹层；且无进场动画、
 *    无屏底翻转定位。这就是"下拉 UI 有问题"的那一个。
 * 2. `ScheduleSelector.kt` 的私有 `TimeField` —— 44dp `surfaceMuted` 卡 + 线性箭头 + 手写
 *    `Popup`（7 项限高滚动、打开自动滚到选中项、下方空间不足向上弹、fade + scale 进场）。
 * 3. `UfiDateRangePickerDialog.kt` 的私有 `UfiDropdownField` —— 与 2 代码重复度极高
 *    （同样手写 Popup + 自动滚动），差异只有 5 项限高、cardBg 底色、左对齐 + 选中 ✓、
 *    没有向上翻转与进场动画。两份注释还在互相引用。
 *
 * 三者交互完全同构（点触发器 → 弹浮层 → 单选 → 关闭；都不需搜索、不多选、非阻断），
 * 差异纯粹是实现史遗留 —— 正是 G1 要消灭的"同一件事多种 UI"。
 *
 * **合并基准取 2（TimeField 的观感）**：它是三者里唯一修过屏底遮挡、且带进场动画的实现，
 * 也是用户实测认可的那一套。因此本组件 = TimeField 的实现 + 泛型化的取值接口。
 * 相对旧 `UfiDropdownField` 的两处有意变更：
 * - 弹层项**居中、不带选中 ✓**（沿用 TimeField v12 的决定：Text 独占整行居中比"左侧顶格 + 右侧勾"更工整，
 *   且 ✓ 出现/消失会把文本挤得左右抖动）；选中态仍靠 accent 12% 底色 + accent 文字 + 加粗区分。
 * - 限高从 5 项改为 7 项（年份 21 项、日期 31 项，7 项可见更好用）。
 *
 * 旧签名 `UfiDropdown(label, selectedValue: String, options: List<Pair<String,String>>, …, enabled)`
 * 已不复存在：`enabled` 零调用点（直接删，不留形参），`label` 语义改为 [unitSuffix]（见其 KDoc），
 * `List<Pair<String,String>>` 改为泛型 `List<T>` + [optionLabel]，避免 Int 调用点来回 `toString`/`toInt`。
 * 按 D8「只有减少入口数量的签名改动值得解冻」，本次 3 → 1 属可解冻情形。
 */

/** 触发器高度。 */
private val UfiDropdownTriggerHeight = 44.dp

/**
 * 弹层单项的**最小**高度（实际高度由文字 + 垂直内距自适应，长文案换行时会自然变高）。
 *
 * 2026-09-04：原来单项与触发器同高（44dp 硬高度），一屏七项之间的空隙显得很松；
 * 现在改为 `heightIn(min = …) + padding(vertical)`，单行文案约 20 + 4×2 = 28dp 起，
 * 36dp 的下限同时保住可点面积。
 */
private val UfiDropdownOptionMinHeight = 36.dp

/** 触发器/弹层项的横向内距。2026-09-04：14dp → Spacing.Large（12dp），给窄触发器（年/月/日）多留 4dp 给文字。 */
private val UfiDropdownPaddingH = Spacing.Large

/** 弹层默认最大可见项数，超出滚动。 */
private const val UfiDropdownMaxVisibleItems = 7

/**
 * 下拉单选字段 —— 全站唯一实现。
 *
 * 触发器：[UfiDropdownTriggerHeight] 高的 `surfaceMuted` 卡片，值居中 + 可选单位后缀 + 线性下箭头。
 * 弹层：手写 [Popup]（**不用 M3 `DropdownMenu`** —— 它内部自带 `verticalScroll`，再套一层会以
 * 无限高度约束测量而抛 `IllegalStateException`，且容器色不走 palette），宽度 = 触发器**实测宽度**、
 * [maxVisibleItems] 项限高滚动、打开时自动把选中项滚到可见区、下方空间不足时向上弹、
 * fade + scale 进场（`transformOrigin` 固定在贴触发器的那一侧）。
 *
 * @param selectedValue 当前选中值。与 [options] 用 `==` 比对，因此 T 需有可靠的 equals（Int/String/枚举均可）。
 * @param options 选项列表，按显示顺序。
 * @param onValueSelected 选中回调；点选后弹层自动关闭。
 * @param unitSuffix 值的**单位后缀**（"小时" / "分钟" / "年" / "月" / "日"），显示在值右侧、箭头左侧。
 *   为空串时整个 Text 不渲染 —— 否则空串仍会占掉 start 内距，把居中的值挤向左侧。
 *   注意这不是 M3 那种浮动 label：若要给字段起标题，用外层的行标签或相邻输入框的 label 承担。
 * @param maxVisibleItems 弹层最大可见项数，超出滚动。
 * @param optionLabel 值 → 显示文案。时分用 `{ "%02d".format(it) }` 补零，其余默认 `toString()`。
 */
@Composable
fun <T> UfiDropdown(
    selectedValue: T,
    options: List<T>,
    onValueSelected: (T) -> Unit,
    modifier: Modifier = Modifier,
    unitSuffix: String = "",
    maxVisibleItems: Int = UfiDropdownMaxVisibleItems,
    optionLabel: (T) -> String = { it.toString() }
) {
    val palette = LocalResolvedPalette.current
    val shape = UfiCardDefaults.shape
    var expanded by remember { mutableStateOf(false) }
    // ⚠ 这个局部变量**不能叫 maxHeight**：BoxWithConstraints 的 receiver 有同名成员，
    // 在它的 lambda 里同名成员优先，外层局部会被静默遮蔽 —— 收敛前的 TimeField 就踩了这个坑，
    // `heightIn(max = maxHeight)` 实际拿到的是**约束上限（常是整屏高）**而不是七项高度，
    // 所以弹层能长得比预期高很多。改名后限高才真正生效。
    val popupMaxHeight = UfiDropdownOptionMinHeight * maxVisibleItems
    val scrollState = rememberScrollState()
    val selectedIndex = options.indexOf(selectedValue).coerceAtLeast(0)
    // LocalDensity 是 @Composable 调用，只能在函数体顶层捕获：
    // LaunchedEffect 的 lambda 是 suspend 块（非 @Composable），内部不能再读。
    val density = LocalDensity.current
    // 是否向上弹：由 [UfiDropdownPositionProvider] 在定位阶段用**真实 windowSize + 实测弹层高度**
    // 判定后回传，这里只用于进场动画的 transformOrigin（贴触发器那一侧张开）。
    var openUpward by remember { mutableStateOf(false) }
    // 触发器的**实测**宽度。不能用 BoxWithConstraints.maxWidth 当弹层宽度：那是约束上限，
    // 触发器自身在 wrap-content / widthIn 等情形下可能比它窄，弹层就会比按钮宽出一截。
    var triggerWidthPx by remember { mutableStateOf(0) }

    BoxWithConstraints(modifier = modifier) {
        val triggerWidth = if (triggerWidthPx > 0) with(density) { triggerWidthPx.toDp() } else maxWidth
        val gapPx = with(density) { Spacing.Small.roundToPx() }
        val edgeMarginPx = with(density) { Spacing.Large.roundToPx() }
        val positionProvider = remember(gapPx, edgeMarginPx) {
            UfiDropdownPositionProvider(gapPx, edgeMarginPx) { upward -> openUpward = upward }
        }

        LaunchedEffect(expanded) {
            if (expanded) {
                val itemHeightPx = with(density) { UfiDropdownOptionMinHeight.toPx() }.toInt()
                // 减 40% 弹层高度：让选中项落在可见区中偏上，而不是紧贴顶边
                val targetY = (selectedIndex * itemHeightPx - with(density) { popupMaxHeight.toPx() * 0.4f }.toInt())
                    .coerceAtLeast(0)
                scrollState.animateScrollTo(targetY)
            }
        }

        Surface(
            onClick = { expanded = !expanded },
            shape = shape,
            color = palette.surfaceMuted,
            border = BorderStroke(1.dp, palette.textSecondary.copy(alpha = 0.6f)),
            modifier = Modifier
                .fillMaxWidth()
                .height(UfiDropdownTriggerHeight)
                .onSizeChanged { triggerWidthPx = it.width }
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = UfiDropdownPaddingH),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // weight(1f) + textAlign=Center：值在「后缀/箭头之外的剩余空间」内居中。
                // 不用 fillMaxWidth()——它会吞掉整行宽度把同级后缀与箭头挤成 0dp。
                Text(
                    text = optionLabel(selectedValue),
                    style = UfiTextStyles.bodyLead.copy(fontWeight = UfiWeight.Emphasis),
                    color = palette.textPrimary,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    modifier = Modifier.weight(1f)
                )
                if (unitSuffix.isNotEmpty()) {
                    Text(
                        text = unitSuffix,
                        style = UfiTextStyles.label,
                        color = palette.textSecondary,
                        modifier = Modifier.padding(start = Spacing.Medium)
                    )
                }
                Icon(
                    Icons.Filled.KeyboardArrowDown,
                    contentDescription = null,
                    tint = palette.textSecondary,
                    // 2026-09-04：IconSizeMedium(20) → IconSizeSmall(18)。窄触发器（日期弹窗的
                    // 年/月/日 各占 1/3 宽）里箭头每多占 1dp，留给"2026"的宽度就少 1dp，
                    // 之前会把年份截成"202"。
                    modifier = Modifier.size(Spacing.IconSizeSmall)
                )
            }
        }

        if (expanded) {
            Popup(
                popupPositionProvider = positionProvider,
                onDismissRequest = { expanded = false },
                properties = PopupProperties(
                    focusable = true,
                    dismissOnClickOutside = true,
                    dismissOnBackPress = true,
                    // 定位已由 provider 自己夹进窗口（四边留 Spacing.Large 余量），不需要系统再裁一刀；
                    // 关掉裁剪柔阴影才能完整画出来，否则贴边时阴影会被切平。
                    clippingEnabled = false
                )
            ) {
                // 原生 Popup 不带 enter/exit 过渡，故在弹层首次 composition 时用 Animatable 手动播
                // fade + scale。transformOrigin 固定在「贴触发器的那一侧」（向下弹从顶边、向上弹从底边
                // 张开），使弹层看起来是从触发器长出来的，而非在空中凭空放大。
                val enterAlpha = remember { Animatable(0f) }
                val enterScale = remember { Animatable(0.94f) }
                LaunchedEffect(Unit) {
                    launch {
                        enterAlpha.animateTo(
                            1f,
                            tween(ThemeMotion.Duration.Quick, easing = ThemeMotion.Easing.Standard)
                        )
                    }
                    launch {
                        enterScale.animateTo(1f, ThemeMotion.panelEnter())
                    }
                }

                Surface(
                    shape = shape,
                    color = palette.cardBg,
                    border = BorderStroke(1.dp, palette.textSecondary.copy(alpha = 0.5f)),
                    modifier = Modifier
                        .width(triggerWidth)
                        .heightIn(max = popupMaxHeight)
                        .graphicsLayer {
                            alpha = enterAlpha.value
                            scaleX = enterScale.value
                            scaleY = enterScale.value
                            transformOrigin = TransformOrigin(0f, if (openUpward) 1f else 0f)
                        }
                        // 2026-09-04：原为 M3 Surface 的 `shadowElevation = 12.dp` + `tonalElevation = 2.dp`。
                        // 那套阴影边缘硬、又重，贴屏幕边时观感像弹层"超出"了页面边距；tonalElevation 还会
                        // 往 cardBg 上叠一层 M3 的色调，与全站卡片不是同一种底。现在改用项目自己的柔阴影
                        // [ufiCardShadow] + Level 2（重要卡片档，6dp），与全站卡片同一套阴影观感。
                        // 位置在 graphicsLayer **之后**（= 更内层），阴影才会跟着进场动画一起缩放淡入。
                        .ufiCardShadow(
                            elevation = UfiCardDefaults.elevationLevel2Dp,
                            shape = shape
                        )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(scrollState)
                    ) {
                        options.forEach { option ->
                            val selected = option == selectedValue
                            // 选中态底色/文字色 tween 过渡，避免点选瞬间的硬切换
                            val rowBg by animateColorAsState(
                                targetValue = if (selected) palette.accent.copy(alpha = 0.12f) else palette.cardBg,
                                animationSpec = tween(ThemeMotion.Duration.Base),
                                label = "dropdownItemBg"
                            )
                            val rowTextColor by animateColorAsState(
                                targetValue = if (selected) palette.accent else palette.textPrimary,
                                animationSpec = tween(ThemeMotion.Duration.Base),
                                label = "dropdownItemFg"
                            )
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    // 2026-09-04：原为固定 44dp（与触发器同高），项间显得很松。
                                    // 改为「最小高度 + 垂直内距」自适应：单行文案实际约 28dp，
                                    // 36dp 下限保住可点面积，长文案换行也能自然变高。
                                    .heightIn(min = UfiDropdownOptionMinHeight)
                                    .clickable {
                                        onValueSelected(option)
                                        expanded = false
                                    }
                                    .background(rowBg)
                                    .padding(
                                        horizontal = UfiDropdownPaddingH,
                                        vertical = Spacing.Small
                                    ),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = optionLabel(option),
                                    color = rowTextColor,
                                    style = UfiTextStyles.bodyLead.copy(
                                        fontWeight = if (selected) UfiWeight.Emphasis else UfiWeight.Regular
                                    ),
                                    maxLines = 1,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 下拉弹层的定位器 —— 把弹层夹在窗口内，并在下方空间不够时向上翻转。
 *
 * ## 为什么不用 `Popup(alignment = TopStart, offset = …)`
 * 那套写法（收敛前的实现）有两个真问题：
 * 1. **只夹得住纵向，横向完全不夹。** 触发器很宽（如组件画廊里占满一行的下拉）且贴近屏幕边缘时，
 *    弹层等宽于触发器、又带柔阴影，`clippingEnabled = false` 下就会有一截连同阴影压在屏幕边距外。
 * 2. **向上翻转的判据是猜的。** 它用 `LocalConfiguration.screenHeightDp` 当窗口高度，
 *    这个值**不含状态栏/导航栏/输入法的实际占用**，也拿不到弹层的**实测高度**（只能用限高上限估），
 *    于是"下方够不够"经常判错——短列表明明放得下却向上弹，或反之被挤出屏幕。
 *
 * [PopupPositionProvider] 拿到的是**真实的** `windowSize`、`anchorBounds` 与 `popupContentSize`，
 * 三者都准，所以翻转判定与边缘夹取都放在这里做。
 *
 * @param gapPx 弹层与触发器之间的间隙（[Spacing.Small]）。
 * @param edgeMarginPx 窗口四边的最小留白（[Spacing.Large]）。取值要 ≥ 阴影的视觉半径，
 *   否则夹到边界时柔阴影仍会贴死边缘。
 * @param onOpenUpwardChange 翻转结果回传，供进场动画把 `transformOrigin` 放在贴触发器的那一侧。
 *   在定位阶段写 Compose 状态是安全的：`mutableStateOf` 默认结构相等策略，值没变就不会触发重组，
 *   因此不会自激；进场动画有 180ms，慢一帧拿到正确的 origin 肉眼无感。
 */
private class UfiDropdownPositionProvider(
    private val gapPx: Int,
    private val edgeMarginPx: Int,
    private val onOpenUpwardChange: (Boolean) -> Unit
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize
    ): IntOffset {
        val spaceBelow = windowSize.height - anchorBounds.bottom - gapPx - edgeMarginPx
        val spaceAbove = anchorBounds.top - gapPx - edgeMarginPx
        // 只有"下方装不下、且上方比下方宽裕"才翻转 —— 两边都装不下时仍向下弹，
        // 因为向下弹再被下面的 coerceIn 夹住，看起来是贴着触发器往下延伸，比倒挂更自然。
        val openUpward = popupContentSize.height > spaceBelow && spaceAbove > spaceBelow
        onOpenUpwardChange(openUpward)

        val x = anchorBounds.left.coerceIn(
            edgeMarginPx,
            (windowSize.width - popupContentSize.width - edgeMarginPx).coerceAtLeast(edgeMarginPx)
        )
        val rawY = if (openUpward) {
            anchorBounds.top - gapPx - popupContentSize.height
        } else {
            anchorBounds.bottom + gapPx
        }
        val y = rawY.coerceIn(
            edgeMarginPx,
            (windowSize.height - popupContentSize.height - edgeMarginPx).coerceAtLeast(edgeMarginPx)
        )
        return IntOffset(x, y)
    }
}

