// [F24] STABLE-UI-API：公共组件签名已冻结，请勿在无向后兼容前提下修改；实验性组件请使用 @UfiExperimentalApi（见 UfiStableApi.kt / UfiExperimentalApi.kt）。
package com.ufi_axis.ui.components.common

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.ufi_axis.ui.theme.LocalResolvedPalette
import com.ufi_axis.ui.theme.UfiCardDefaults
import com.ufi_axis.ui.theme.UfiTextStyles
import com.ufi_axis.ui.theme.UfiWeight
import kotlin.math.roundToInt

/**
 * 悬浮选项菜单（2026-08-27 从 `UfiDialogParts.kt` 独立成文件）。
 *
 * 这是本项目**唯一**的菜单实现，工具栏「⋮ 更多」与文件管理器长按菜单都用它。
 * 不要用 M3 原生 `DropdownMenu` —— 它在部分机型上不继承自定义 `colorScheme`，会出现白底。
 *
 * 三个公开入口，按调用成本从低到高：
 * - [UfiPopupMenuButton] —— 工具栏图标按钮 + 菜单，**自己管展开态与锚点**，调用方一行即可；
 * - [UfiPopupAnchor] —— 任意内容作为锚点（自定义按钮/卡片），把采集到的坐标交给内容 lambda；
 * - [UfiPopupMenu] —— 底层实现，需要自己维护 `visible` 与 `anchorBounds`；
 *   长按场景（菜单中心对齐手指落点）必须走这个，传 `anchorPoint`。
 */

/**
 * 选项弹窗项数据模型。
 *
 * @param id 唯一标识
 * @param label 显示文字
 * @param icon 前置图标（可选）
 * @param isDestructive 是否危险操作（用 error 色）
 * @param isSelected 是否当前选中项（显示高亮 + ✓）
 * @param isDivider 是否为分割线标记
 * @param onClick 点击回调
 */
data class UfiPopupOption(
    val id: String,
    val label: String,
    val icon: ImageVector? = null,
    val isDestructive: Boolean = false,
    val isSelected: Boolean = false,
    val isDivider: Boolean = false,
    val onClick: () -> Unit = {}
) {
    companion object {
        /** 分割线项工厂——插入 options 列表即可渲染分割线。 */
        fun divider() = UfiPopupOption("", "", isDivider = true)
    }
}

/**
 * 工具栏「更多」按钮 + 悬浮菜单，开箱即用。
 *
 * 把「展开态 + `onGloballyPositioned` 采集锚点坐标 + 关闭回调」这套样板收进组件内部 ——
 * 此前每个调用点都要自己写十来行 `IntRect(r.left.roundToInt(), …)`，抄错一处菜单就飞到屏幕外。
 *
 * 用在 `UfiScreenScaffold(actions = { ... })` 里即可，无需外层再包 Box。
 *
 * @param options 选项列表；为空时不渲染按钮（省得点开一个空菜单）
 * @param icon 按钮图标，默认「⋮」
 * @param contentDescription 无障碍描述
 * @param enabled 按钮是否可点
 */
@Composable
fun UfiPopupMenuButton(
    options: List<UfiPopupOption>,
    modifier: Modifier = Modifier,
    icon: ImageVector = Icons.Default.MoreVert,
    contentDescription: String? = "更多操作",
    enabled: Boolean = true
) {
    if (options.isEmpty()) return
    UfiPopupAnchor(options = options, modifier = modifier) { toggle ->
        IconButton(onClick = toggle, enabled = enabled) {
            Icon(icon, contentDescription = contentDescription)
        }
    }
}

/**
 * 自定义锚点 + 悬浮菜单：锚点长什么样由 [anchor] 决定，展开态与坐标采集仍由本组件负责。
 *
 * @param anchor 锚点内容，参数是「切换菜单展开」的回调，绑到你的 onClick 上即可
 */
@Composable
fun UfiPopupAnchor(
    options: List<UfiPopupOption>,
    modifier: Modifier = Modifier,
    anchor: @Composable (toggle: () -> Unit) -> Unit
) {
    if (options.isEmpty()) return
    var expanded by remember { mutableStateOf(false) }
    var bounds by remember { mutableStateOf(IntRect.Zero) }
    Box(
        modifier = modifier.onGloballyPositioned { coords ->
            val r = coords.boundsInWindow()
            bounds = IntRect(
                r.left.roundToInt(),
                r.top.roundToInt(),
                r.right.roundToInt(),
                r.bottom.roundToInt()
            )
        }
    ) {
        anchor { expanded = !expanded }
        UfiPopupMenu(
            visible = expanded,
            onDismiss = { expanded = false },
            anchorBounds = bounds,
            options = options
        )
    }
}

/**
 * 判断字符是否为 CJK（中日韩）表意文字 / 假名 / 谚文，用于估算菜单 label 宽度。
 * 覆盖：CJK 扩展 A~F、基本汉字、兼容汉字、日文假名、韩文谚文、全角形。
 */
private fun Char.isCJK(): Boolean {
    val c = this
    return c in '\u3400'..'\u9FFF' ||
        c in '\uF900'..'\uFAFF' ||
        c in '\u3040'..'\u30FF' ||
        c in '\uAC00'..'\uD7A3' ||
        c in '\uFF00'..'\uFFEF'
}

/**
 * 悬浮选项菜单（底层实现）。
 *
 * 采用 Compose [Popup] 实现「点击锚点后浮出」的悬浮菜单，
 * 定位参考 M3 原生 DropdownMenu（锚点下方优先，空间不足翻到上方；
 * 水平默认右对齐锚点右缘向左展开），UI 完全自定义并 100% 跟随主题。
 *
 * 使用 Popup（而非 Dialog）可避免弹出新窗口导致的状态栏闪烁。
 * 白底问题由内部 M3 [Surface] 绘制主题色卡片 + 阴影解决（本版 Compose 的 Popup
 * 无 `popupBackgroundColor` 参数，不能靠它覆盖系统默认白底）。
 *
 * 调用方须用 [androidx.compose.ui.layout.onGloballyPositioned] 采集触发控件窗口坐标
 * (coordinates.boundsInWindow()) 作为 [anchorBounds] 传入，菜单据此定位 ——
 * 工具栏场景直接用 [UfiPopupMenuButton] / [UfiPopupAnchor] 就不用手写这段。
 *
 * @param visible 是否显示
 * @param onDismiss 关闭回调（点外部 / 返回键 / 选项点击后自动调）
 * @param anchorBounds 触发控件(容器)的窗口坐标 (IntRect)，用于把绝对定位换算成 Popup 相对偏移；
 *                    矩形锚点模式下也用作对齐基准。
 * @param anchorPoint 长按点的窗口绝对坐标 (IntOffset)，可选。**传入时菜单中心对齐长按点出现**
 *                   （菜单中心对齐长按点 + clamp 到屏幕内），用于列表/网格卡片长按场景；为 null 时走原矩形锚点定位。
 * @param options 选项列表
 * @param focusable 菜单窗口是否抢焦点。默认 true（普通菜单：能吃返回键、点外部自动关）。
 *                  **输入联想场景必须传 false** —— 焦点被 Popup 抢走会导致输入框失焦、
 *                  软键盘收起，用户没法边打字边看候选。代价是失去"点外部/返回键自动关"，
 *                  需要调用方自己在合适时机（选中、清空输入、关闭表单）把 [visible] 置 false。
 */
@Composable
fun UfiPopupMenu(
    visible: Boolean,
    onDismiss: () -> Unit,
    anchorBounds: IntRect = IntRect.Zero,
    anchorPoint: IntOffset? = null,
    options: List<UfiPopupOption>,
    focusable: Boolean = true
) {
    if (options.isEmpty()) return

    // P1-1：统一读项目调色板（随主题切换），不再直读 M3 colorScheme
    val palette = LocalResolvedPalette.current
    val cardShape = UfiCardDefaults.shape
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val itemPaddingH = 14.dp
    val itemPaddingV = 9.dp

    // 轻量过渡：纯淡入 / 淡出（仅透明度变化，无位移）。
    // 不用 AnimatedVisibility 包裹 Surface——它初始不可见时会让 Popup 折叠到 (0,0)，
    // 定位跳变正是之前「从左上角飞入」的元凶。这里改用 graphicsLayer{alpha} 手动控透明度，
    // Popup 始终以正确 offset 挂载，alpha 由 LaunchedEffect 单向驱动；关闭时先播淡出再卸载窗口。
    var internalVisible by remember { mutableStateOf(false) }
    var alpha by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(visible) {
        // 2026-09-04（P2b）：原为裸 `tween(150)` / `tween(90)`。
        // - 淡入 150 → Duration.Swift（160，+10ms，在允许的吸附容差内；150 与梯度上的 160
        //   是同一种"轻量跟手"手感，不该存在两个数）；
        // - 淡出 90 → Duration.Flick（值不变）。「出比进快近一倍」是刻意的，不要拉平成同一档。
        if (visible) {
            internalVisible = true
            alpha = 0f
            animate(0f, 1f, animationSpec = tween(UfiMotion.Duration.Swift)) { value, _ -> alpha = value }
        } else {
            animate(alpha, 0f, animationSpec = tween(UfiMotion.Duration.Flick)) { value, _ -> alpha = value }
            internalVisible = false
        }
    }

    // 关闭入口：点外部 / 返回键 / 选项点击 → 通知父组件把 visible 置 false
    fun dismiss() {
        onDismiss()
    }

    // 仅当完全不可见（visible 与退出动画期间的 internalVisible 均为假）才卸载 Popup
    if (!visible && !internalVisible) return

    // ── 估算菜单尺寸并定位 ──
    // 关键：Compose 的 Popup(alignment=TopStart, offset) 会把 offset 叠加到「父容器
    // （包着按钮的 Box）的窗口左上角」上，而非直接使用绝对窗口坐标。因此这里先按绝对窗口
    // 坐标估算菜单一角，再减去锚点左上角转成相对偏移；否则菜单会被整体平移到屏幕外
    // （表现为点击无反应）。
    val offset = remember(anchorBounds, anchorPoint, options.size) {
        // 宽度按最长 label 动态估算：图标 18dp + 间距 10dp + label 估算宽度 + 两侧 padding 28dp
        val maxLabelChars = options.maxOf { it.label.length }
        val avgCharDp = if (options.any { it.label.any(Char::isCJK) }) 17.0 else 9.0
        val labelWidthDp = (maxLabelChars * avgCharDp).coerceAtMost(180.0)
        val estW = (labelWidthDp + 72.0).dp  // 72 = 18 (icon) + 10 (spacer) + 28 (paddingH 14*2) + 16 (buffer)
        val winW = with(density) { configuration.screenWidthDp.dp.roundToPx() }
        val winH = with(density) { configuration.screenHeightDp.dp.roundToPx() }
        val estWPx = with(density) { estW.roundToPx() }
        // estHPx = 选项数 * 48dp（行高，含 padding）
        val lineHeightPx = with(density) { 48.dp.roundToPx() }
        val menuVPadding = with(density) { 8.dp.roundToPx() }
        val estHPx = options.size * lineHeightPx + menuVPadding * 2
        val screenMarginPx = with(density) { 8.dp.roundToPx() }

        // 先按绝对窗口坐标算出菜单左上角 absX / absY，最后统一减去锚点容器左上角
        // (anchorBounds.left/top) 转成 Popup 的相对 offset（Popup 的 offset 是叠加到
        // 父容器窗口左上角上的，而非直接绝对窗口坐标）。
        val absPos: IntOffset = if (anchorPoint != null) {
            // ── 长按点定位：菜单中心对齐长按点 + clamp 到屏幕内 ──
            // 菜单中心对齐手指长按点：长按点偏右时菜单向左展开，偏左时向右展开；
            // 长按点在屏幕顶部时菜单往下排，在底部时往上排，始终保持 ±estH/2 围绕长按点。
            val px = anchorPoint.x
            val py = anchorPoint.y
            val x = (px - estWPx / 2).coerceIn(screenMarginPx, (winW - estWPx - screenMarginPx).coerceAtLeast(screenMarginPx))
            val y = (py - estHPx / 2).coerceIn(screenMarginPx, (winH - estHPx - screenMarginPx).coerceAtLeast(screenMarginPx))
            IntOffset(x, y)
        } else {
            // ── 矩形锚点定位：右对齐到锚点右缘 + 锚点下方优先（工具栏「更多」菜单等沿用）──
            val spaceBelow = winH - anchorBounds.bottom
            val y = if (spaceBelow >= estHPx + screenMarginPx) {
                anchorBounds.bottom
            } else {
                // 翻上方：菜单底部 = 锚点顶部 - margin
                (anchorBounds.top - estHPx - screenMarginPx)
                    .coerceAtLeast(screenMarginPx)
            }
            var x = (anchorBounds.right - estWPx).coerceAtLeast(screenMarginPx)
            // 边界保护：右缘不能超出屏幕 - margin
            if (x + estWPx > winW - screenMarginPx) {
                x = (winW - estWPx - screenMarginPx).coerceAtLeast(screenMarginPx)
            }
            IntOffset(x, y)
        }
        IntOffset(absPos.x - anchorBounds.left, absPos.y - anchorBounds.top)
    }

    Popup(
        alignment = Alignment.TopStart,
        offset = offset,
        onDismissRequest = { dismiss() },
        properties = PopupProperties(
            focusable = focusable,
            // 这两项依赖窗口焦点，focusable=false 时本身就不会触发（输入联想场景由调用方控关闭）
            dismissOnBackPress = focusable,
            dismissOnClickOutside = focusable,
            clippingEnabled = false
        )
    ) {
        Surface(
            shape = cardShape,
            color = palette.cardBg,
            shadowElevation = 8.dp,
            border = BorderStroke(0.5.dp, palette.dialogBorder.copy(alpha = 0.6f)),
            modifier = Modifier
                .widthIn(min = 100.dp, max = 220.dp)
                .alpha(alpha)
        ) {
            Column {
                options.forEach { opt ->
                    when {
                        opt.isDivider -> {
                            HorizontalDivider(
                                color = palette.dialogBorder.copy(alpha = 0.5f),
                                modifier = Modifier.padding(horizontal = itemPaddingH, vertical = 3.dp)
                            )
                        }
                        else -> {
                            val textColor = when {
                                opt.isDestructive -> palette.error
                                opt.isSelected -> palette.accent
                                else -> palette.textPrimary
                            }
                            val iconTint = when {
                                opt.isDestructive -> palette.error
                                opt.isSelected -> palette.accent
                                else -> palette.textSecondary
                            }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null
                                    ) {
                                        opt.onClick()
                                        dismiss()
                                    }
                                    .padding(horizontal = itemPaddingH, vertical = itemPaddingV),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (opt.icon != null) {
                                    Icon(opt.icon, null, tint = iconTint, modifier = Modifier.size(18.dp))
                                } else if (opt.isSelected) {
                                    Icon(Icons.Default.Check, null, tint = palette.accent, modifier = Modifier.size(18.dp))
                                } else {
                                    Spacer(Modifier.size(18.dp))
                                }
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    text = opt.label,
                                    style = UfiTextStyles.body.copy(
                                        fontWeight = if (opt.isSelected || opt.isDestructive) UfiWeight.Medium else UfiWeight.Regular
                                    ),
                                    color = textColor,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false)
                                )
                            }
                        }
                    }
                }
            }
            // 底部内边距，让最后一项不贴边
            Spacer(Modifier.height(4.dp))
        }
    }
}
