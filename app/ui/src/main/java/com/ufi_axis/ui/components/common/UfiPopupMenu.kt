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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.ufi_axis.ui.theme.LocalResolvedPalette
import com.ufi_axis.ui.theme.UfiCardDefaults
import com.ufi_axis.ui.theme.UfiTextStyles
import com.ufi_axis.ui.theme.UfiWeight
import kotlin.math.roundToInt
import com.ufi_axis.ui.theme.UfiMotion

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
 * 菜单定位策略。
 *
 * 位置在**测量之后**由 Compose 回调计算（[calculatePosition] 的 `popupContentSize` 是菜单
 * 的真实尺寸、`windowSize` 是真实窗口尺寸），因此不需要任何尺寸估算。
 *
 * 2026-09-08 换掉了原来那套手算：宽按「label 字符数 × 平均字宽（CJK 17dp / 拉丁 9dp）」估、
 * 高按「48dp/行」估，再拿 `configuration.screenHeightDp` 当窗口高度。估值只要偏大就会误判
 * 「下方放不下」提前翻到上方，翻上方后再被 clamp 到窗口顶部 —— 菜单于是压在状态栏底下，
 * 表现为「超出屏幕边界」。7 档的验证码清理间隔菜单真实高度约 270dp，估算给到 352dp，
 * 正好落进这个误判区间。
 *
 * @param anchor 调用方给的锚点矩形（窗口坐标）。**优先于** Compose 回调里的 `anchorBounds`：
 *   有调用方把 [UfiPopupMenu] 挂在锚点之外的层级（如页面根部），那时 Compose 给的是那一层的
 *   bounds 而不是真正的锚点。为 [IntRect.Zero]（没传）时才回退到 `anchorBounds`。
 * @param anchorPoint 长按点（窗口坐标），非 null 时菜单中心对齐该点。
 * @param marginPx 菜单与窗口边缘的最小间距。
 */
private class UfiMenuPositionProvider(
    private val anchor: IntRect,
    private val anchorPoint: IntOffset?,
    private val marginPx: Int
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize
    ): IntOffset {
        val w = popupContentSize.width
        val h = popupContentSize.height
        // 菜单比窗口还大时（极小屏 + 超多选项）maxX/maxY 会小于 marginPx，
        // coerceAtLeast 保证 coerceIn 的区间合法，此时贴左上角、由内部滚动/裁剪兜底。
        val maxX = (windowSize.width - w - marginPx).coerceAtLeast(marginPx)
        val maxY = (windowSize.height - h - marginPx).coerceAtLeast(marginPx)

        if (anchorPoint != null) {
            // 长按：菜单中心对齐手指落点，再整体 clamp 进窗口。
            return IntOffset(
                (anchorPoint.x - w / 2).coerceIn(marginPx, maxX),
                (anchorPoint.y - h / 2).coerceIn(marginPx, maxY)
            )
        }

        val rect = if (anchor == IntRect.Zero) anchorBounds else anchor
        // 下方优先，放不下才翻到上方（与 M3 DropdownMenu 一致）。
        val y = if (rect.bottom + h + marginPx <= windowSize.height) {
            rect.bottom
        } else {
            rect.top - h - marginPx
        }
        // 水平右对齐锚点右缘向左展开。
        return IntOffset(
            (rect.right - w).coerceIn(marginPx, maxX),
            y.coerceIn(marginPx, maxY)
        )
    }
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

    val screenMarginPx = with(density) { 8.dp.roundToPx() }
    val positionProvider = remember(anchorBounds, anchorPoint, screenMarginPx) {
        UfiMenuPositionProvider(anchorBounds, anchorPoint, screenMarginPx)
    }

    Popup(
        popupPositionProvider = positionProvider,
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
