package com.ufi_axis.ui.components.common

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

import com.ufi_axis.ui.animation.page.LocalUfiReduceMotion
import com.ufi_axis.ui.theme.LocalResolvedPalette
import com.ufi_axis.ui.theme.UfiMotion
import com.ufi_axis.ui.theme.UfiTextStyles
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max

/**
 * 数据刷新用的**逐字符滚动**文本（钟表滚轮观感，2026-09-16 新增）。
 *
 * 用途：一句文案里只有少数字符会变（"9月16日 周三" → "9月17日 周三" 只差一个 `6→7`），
 * 换成 [Text] 直接替换整句会"整块闪一下"，而这里只让**变化的那几个字**滚过去，
 * 没变的字一像素都不动。
 *
 * ## 三条约定
 * 1. **只有变化的槽位有动画**：按 LCS 对齐旧串/新串，相同字符判为静态槽位，
 *    连动画都不建。
 * 2. **数字沿数轴滚**：以旧值为起点、新值为终点，中间数字逐格掠过
 *    （`6 → 1` 就反向掠过 5/4/3/2）。**首次出现时以 0 为起点**，非数字首次出现不滚
 *    （把"月""日""周"也滚一遍纯属噪声）。非数字的替换（三 → 四）没有数轴可走，
 *    退化成一格滚动：旧字向上滚出、新字从下方滚入。
 *    组件因此**不需要认识任何业务字表**（周几、月份名都不必内建）。
 * 3. **逐帧不重组**：整段动画只有一个 [Animatable]（0→1 的时间轴），
 *    每个槽位的进度是它的纯函数（`startMs` / `durationMs` 各自不同 = 错峰），
 *    且**只在 `Canvas` 的 draw lambda 里读** —— 与 `UfiChart` / `UfiBarChart` 同一条纪律。
 *
 * ## 不做什么
 * - **不接亚秒级读数**。`SpeedTestScreen` 的注释已经写明「数值不能放进 Crossfade ——
 *   它每 150ms 更新一次，会变成持续闪烁」。本组件的适用区间是**轮询间隔 ≥ 1 秒**
 *   （切区间 / 翻页 / 10s 仪表盘轮询 / WS 秒级推送），150ms 级读数请继续用 [Text] 直出。
 * - 只支持**单行**：几何按"一行字形"算，不处理换行与省略号。也没有 `textAlign` ——
 *   宽度就是字形实测和，要居中请在外层套 `Box(contentAlignment = …)`。
 * - **不做格式化**。串由调用方给（`FormatUtils` 负责）。自适应单位的串
 *   （`"998 KB/s"` → `"1.0 MB/s"`）请走 [UfiRollingMetric] 把单位剥出去，
 *   否则换档时变化槽位超阈值会退化成整句滚动。

 *
 * ## 几何
 * 字形在组合期预测量并缓存（key 含 style + density + **fontScale**，系统字体大小一改立刻重算）。
 * 数字槽位的宽度统一取 `0..9` 的最大字宽 —— 非等宽字体里 `1` 比 `8` 窄，
 * 不钉住宽度的话滚动过程中整行会左右抖。各字形按**基线**对齐，不是按顶边。
 *
 * @param text 要显示的文案；与上一次的差异决定哪些槽位滚动
 * @param style 文本样式；等宽族（`UfiTextStyles.monoReadout` 等）在纯数字场景下更稳
 * @param stepDurationMillis 滚**一格**的时长。多格时按 [UFI_ROLLING_EXTRA_STEP_MS] 递增，
 *   上限 [UfiMotion.Duration.Deliberate]（滚得远给更多时间，但不能拖成慢动作）
 * @param staggerMillis 相邻变化槽位的错峰（默认 [UfiMotion.STAGGER_DELAY_MS]），
 *   让 "17" 的两位不同时停。传 0 = 所有槽位同时滚
 */
@Composable
fun UfiRollingText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = UfiTextStyles.sectionTitle,
    color: Color = LocalResolvedPalette.current.textPrimary,
    stepDurationMillis: Int = UfiMotion.Duration.Standard,
    staggerMillis: Long = UfiMotion.STAGGER_DELAY_MS
) {
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val reduceMotion = LocalUfiReduceMotion.current
    val resolvedStyle = remember(style, color) { style.copy(color = color) }

    /**
     * 上一次显示的文案。普通持有者而**不是** `MutableState`：它只在下面 `remember` 的
     * 计算块里读写，不建立 snapshot 依赖（在组合期写 state 会触发一次多余的重组）。
     */
    val previous = remember { UfiRollingTextHolder() }
    val plan = remember(text, reduceMotion, stepDurationMillis, staggerMillis) {
        ufiRollingPlan(
            from = previous.value,
            to = text,
            // 系统「降低动效」时时长归零 ⇒ 全静态槽位，直接跳变
            stepDurationMillis = if (reduceMotion) 0 else stepDurationMillis,
            staggerMillis = if (reduceMotion) 0L else staggerMillis
        ).also { previous.value = text }
    }

    // 字形预测量。fontScale 必须进 key（与 MonitorOverview 的降档同一条纪律）。
    val glyphs: Map<String, TextLayoutResult> =
        remember(plan, resolvedStyle, density.density, density.fontScale) {
            val cache = HashMap<String, TextLayoutResult>()
            fun put(s: String) {
                if (s.isNotEmpty() && s !in cache) cache[s] = measurer.measure(s, resolvedStyle)
            }
            plan.slots.forEach { slot ->
                put(slot.from)
                put(slot.to)
            }
            // 数字槽位会掠过中间数字，10 个字形一次量全
            if (plan.slots.any { it.kind == UfiRollKind.Digit }) {
                for (d in '0'..'9') put(d.toString())
            }
            cache
        }

    val metrics = remember(plan, glyphs) { ufiRollingMetrics(plan, glyphs) }
    if (metrics.widthPx <= 0f || metrics.lineHeightPx <= 0f) return

    // 一条时间轴驱动全部槽位；plan 变了就从头播（snapTo(0f) 再 animateTo）
    val clock = remember(plan) { Animatable(0f) }
    LaunchedEffect(plan) {
        if (plan.totalMs <= 0) {
            clock.snapTo(1f)
            return@LaunchedEffect
        }
        clock.snapTo(0f)
        // 时间轴本身是线性的；每个槽位在自己的区间里各跑一遍缓动（见 easedProgress）
        clock.animateTo(1f, tween(plan.totalMs, easing = LinearEasing))
    }

    Canvas(
        modifier = modifier
            .width(with(density) { metrics.widthPx.toDp() })
            .height(with(density) { metrics.lineHeightPx.toDp() })
    ) {
        val elapsed = clock.value * plan.totalMs // 绘制期读：动画只重绘、不重组
        var x = 0f
        plan.slots.forEachIndexed { index, slot ->
            val slotWidth = metrics.slotWidths[index]
            val t = slot.easedProgress(elapsed)
            // 每个槽位裁到自己的格子里：滚出去的字不能糊到邻字上
            clipRect(left = x, top = 0f, right = x + slotWidth, bottom = size.height) {
                slot.forEachVisibleGlyph(t, metrics.lineHeightPx) { glyph, dy, alpha ->
                    val layout = glyphs[glyph]
                    if (layout != null) {
                        drawGlyph(layout, color, alpha, x, slotWidth, dy, metrics.baselinePx)
                    }
                }
            }
            x += slotWidth
        }
    }
}

/**
 * 「数值 + 单位」读数：数字段走 [UfiRollingText] 滚动，单位段是普通 [Text]。
 *
 * 为什么要拆：自适应单位的格式化函数（`FormatUtils.formatRate` / `formatSize`）在换档时
 * 整串都变（`"998.0 KB/s"` → `"1.0 MB/s"`），变化槽位一超阈值就退化成"整句滚动"，
 * 动画收益归零。把单位剥出去之后，换档只是单位那个 [Text] 跳一下，数字段仍然逐位滚。
 *
 * 拆分规则见 [ufiSplitReadout]：从末尾剥掉非数字后缀。
 *
 * @param unitStyle 单位段样式；默认 [UfiTextStyles.label]，比数字小一号做层级
 * @param alignment 数字段与单位段的纵向对齐；默认 [Alignment.Bottom]（单位坐在数字基线上）
 */
@Composable
fun UfiRollingMetric(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = UfiTextStyles.metricValue,
    unitStyle: TextStyle = UfiTextStyles.label,
    color: Color = LocalResolvedPalette.current.textPrimary,
    unitColor: Color = color,
    unitSpacing: Dp = 4.dp,
    alignment: Alignment.Vertical = Alignment.Bottom,
    stepDurationMillis: Int = UfiMotion.Duration.Standard,
    staggerMillis: Long = UfiMotion.STAGGER_DELAY_MS
) {
    val parts = remember(text) { ufiSplitReadout(text) }
    Row(modifier = modifier, verticalAlignment = alignment) {
        UfiRollingText(
            text = parts.first,
            style = style,
            color = color,
            stepDurationMillis = stepDurationMillis,
            staggerMillis = staggerMillis
        )
        val unit = parts.second
        if (unit != null) {
            Spacer(Modifier.width(unitSpacing))
            Text(text = unit, style = unitStyle, color = unitColor, maxLines = 1)
        }
    }
}

/**
 * 把一条读数拆成「数字段 + 单位段」：`"12.3 MB/s"` → `"12.3"` + `"MB/s"`、
 * `"84%"` → `"84"` + `"%"`、`"3.2/8.0 GB"` → `"3.2/8.0"` + `"GB"`。
 *
 * 做法是找到**最后一个数字**，其后的内容去空白即单位。串里没有数字时（`"--"` / `"—"`）
 * 整串当数字段返回、单位为 `null` —— 占位符不该被当成单位甩到右边去。
 */
fun ufiSplitReadout(text: String): Pair<String, String?> {
    val lastDigit = text.indexOfLast { it.isDigit() }
    if (lastDigit < 0) return text to null
    val unit = text.substring(lastDigit + 1).trim()
    return text.substring(0, lastDigit + 1) to unit.takeIf { it.isNotEmpty() }
}

private fun DrawScope.drawGlyph(
    layout: TextLayoutResult,
    color: Color,
    alpha: Float,
    slotLeft: Float,
    slotWidth: Float,
    dy: Float,
    baselinePx: Float
) {
    drawText(
        textLayoutResult = layout,
        color = color,
        alpha = alpha,
        topLeft = Offset(
            // 槽位内居中：数字槽位宽度是 0..9 的最大值，窄字（1）也不会贴边
            x = slotLeft + (slotWidth - layout.size.width) / 2f,
            // 按基线对齐：数字与汉字的 layout 高度不同，按顶边对齐会一高一低
            y = baselinePx - layout.firstBaseline + dy
        )
    )
}

// ───────────────────────── 计划与几何（纯计算，可单测） ─────────────────────────

/** 多滚一格追加的时长。滚 5 格比滚 1 格远得多，同一时长会快到看不清中间数字。 */
private const val UFI_ROLLING_EXTRA_STEP_MS = 30

/**
 * 触发"整句滚动"的变化槽位数阈值。
 *
 * 十几个字同时滚是噪声不是动画，而且那种情况通常意味着**换了句式**
 * （"9月16日 周三" → "9月13日-9月19日"），逐字对齐本身就没有意义。
 */
private const val UFI_ROLLING_MAX_CHANGED_SLOTS = 4

private class UfiRollingTextHolder(var value: String? = null)

private enum class UfiRollKind {
    /** 不变：画一次，不建动画。 */
    Static,

    /** 数字：沿数轴从 [UfiRollSlot.fromDigit] 滚到 [UfiRollSlot.toDigit]，掠过中间数字。 */
    Digit,

    /** 非数字替换（含整句退化）：旧的滚出、新的滚入，只有一格。 */
    Swap
}

private class UfiRollSlot(
    val kind: UfiRollKind,
    /** 旧字形（[UfiRollKind.Swap] 用）；空串 = 没有旧值，只有新值滚入。 */
    val from: String,
    /** 新字形（静态槽位就是它本身）。 */
    val to: String,
    val fromDigit: Int = 0,
    val toDigit: Int = 0,
    val startMs: Int = 0,
    val durationMs: Int = 0
)

private class UfiRollPlan(val slots: List<UfiRollSlot>, val totalMs: Int)

/** 本槽位在全局时间轴 [elapsedMs] 处的进度（已过缓动）。 */
private fun UfiRollSlot.easedProgress(elapsedMs: Float): Float {
    if (kind == UfiRollKind.Static || durationMs <= 0) return 1f
    val local = ((elapsedMs - startMs) / durationMs).coerceIn(0f, 1f)
    // 先快后慢：`Easing.EmphasizedIn`（M3 emphasized **decelerate**，CubicBezier(0.05, 0.7, 0.1, 1)）
    // —— 甩出去、再稳稳落进槽位，这才是滚轮/钟表的手感。
    // ⚠ 别按英文语感挑成 `EmphasizedOut`：本项目里那一条是 emphasized **accelerate**
    //（0.3, 0, 0.8, 0.15），观感是"起步拖沓、尾巴猛冲"（2026-09-16 修）。
    return UfiMotion.Easing.EmphasizedIn.transform(local)
}

/**
 * 枚举本槽位当前可见的字形。
 *
 * 纵向位置一律用 `dy = (位置 - 进度) × 行高`：进度增大时当前字形向上走、下一个从**下方**
 * 滚入（数字变大向上滚，与里程表一致）；进度减小时自然反向。
 * 透明度按离槽位中心的距离线性衰减 —— 这就是"钟表滚轮"上下边缘的渐隐，
 * 不需要知道背景色去画遮罩。
 */
private inline fun UfiRollSlot.forEachVisibleGlyph(
    t: Float,
    lineHeight: Float,
    draw: (glyph: String, dy: Float, alpha: Float) -> Unit
) {
    when (kind) {
        UfiRollKind.Static -> draw(to, 0f, 1f)

        UfiRollKind.Digit -> {
            val p = fromDigit + (toDigit - fromDigit) * t
            val base = floor(p).toInt()
            for (k in base..base + 1) {
                val dy = (k - p) * lineHeight
                val alpha = (1f - abs(dy) / lineHeight).coerceIn(0f, 1f)
                if (alpha > 0.01f) draw((((k % 10) + 10) % 10).toString(), dy, alpha)
            }
        }

        UfiRollKind.Swap -> {
            // 0 = 旧字形的位置，1 = 新字形的位置
            for (pos in 0..1) {
                val glyph = if (pos == 0) from else to
                if (glyph.isEmpty()) continue
                val dy = (pos - t) * lineHeight
                val alpha = (1f - abs(dy) / lineHeight).coerceIn(0f, 1f)
                if (alpha > 0.01f) draw(glyph, dy, alpha)
            }
        }
    }
}

private class UfiRollingMetrics(
    val slotWidths: List<Float>,
    val widthPx: Float,
    val lineHeightPx: Float,
    val baselinePx: Float
)

private fun ufiRollingMetrics(
    plan: UfiRollPlan,
    glyphs: Map<String, TextLayoutResult>
): UfiRollingMetrics {
    if (glyphs.isEmpty()) return UfiRollingMetrics(emptyList(), 0f, 0f, 0f)
    val baseline = glyphs.values.maxOf { it.firstBaseline }
    // 行高按"基线对齐后最矮的底"算：不同字形的 layout 高度不一致
    val lineHeight = glyphs.values.maxOf { baseline - it.firstBaseline + it.size.height }
    val digitWidth = ('0'..'9').maxOf { glyphs[it.toString()]?.size?.width ?: 0 }.toFloat()
    val widths = plan.slots.map { slot ->
        when (slot.kind) {
            // 数字槽位钉死宽度，滚动中不会因为 1 比 8 窄而让整行左右抖
            UfiRollKind.Digit -> digitWidth
            else -> max(
                glyphs[slot.from]?.size?.width ?: 0,
                glyphs[slot.to]?.size?.width ?: 0
            ).toFloat()
        }
    }
    return UfiRollingMetrics(widths, widths.sum(), lineHeight, baseline)
}

private class UfiRollPair(val old: Char?, val new: Char, val unchanged: Boolean)

private fun ufiRollingStaticPlan(text: String): UfiRollPlan =
    UfiRollPlan(text.map { UfiRollSlot(UfiRollKind.Static, "", it.toString()) }, 0)

/**
 * 由"上一次文案 → 这一次文案"算出各槽位的滚动计划。
 *
 * [from] 为 `null` 表示**首次显示**：数字从 0 滚上来，其余字符直接就位。
 */
private fun ufiRollingPlan(
    from: String?,
    to: String,
    stepDurationMillis: Int,
    staggerMillis: Long
): UfiRollPlan {
    if (to.isEmpty() || stepDurationMillis <= 0) return ufiRollingStaticPlan(to)
    if (from == to) return ufiRollingStaticPlan(to)

    val pairs = if (from == null) {
        to.map { c ->
            // 首次：只有数字滚（从 0 起），"月""日""周"这些直接就位
            UfiRollPair(old = if (c.isDigit()) '0' else null, new = c, unchanged = !c.isDigit())
        }
    } else {
        ufiRollingAlign(from, to)
    }

    val changed = pairs.count { !it.unchanged }
    if (changed > max(UFI_ROLLING_MAX_CHANGED_SLOTS, to.length / 2)) {
        // 退化成整句一格滚动
        val slot = UfiRollSlot(
            kind = UfiRollKind.Swap,
            from = from.orEmpty(),
            to = to,
            durationMs = stepDurationMillis
        )
        return UfiRollPlan(listOf(slot), stepDurationMillis)
    }

    var order = 0
    var total = 0
    val slots = pairs.map { pair ->
        val oldDigit = pair.old?.takeIf { it.isDigit() }?.let { it - '0' }
        val newDigit = pair.new.takeIf { it.isDigit() }?.let { it - '0' }
        when {
            pair.unchanged -> UfiRollSlot(UfiRollKind.Static, "", pair.new.toString())

            oldDigit != null && newDigit != null -> {
                if (oldDigit == newDigit) {
                    UfiRollSlot(UfiRollKind.Static, "", pair.new.toString())
                } else {
                    val steps = abs(newDigit - oldDigit)
                    val duration = (stepDurationMillis + UFI_ROLLING_EXTRA_STEP_MS * (steps - 1))
                        .coerceAtMost(UfiMotion.Duration.Deliberate)
                    val start = (order++ * staggerMillis).toInt()
                    total = max(total, start + duration)
                    UfiRollSlot(
                        kind = UfiRollKind.Digit,
                        from = "",
                        to = pair.new.toString(),
                        fromDigit = oldDigit,
                        toDigit = newDigit,
                        startMs = start,
                        durationMs = duration
                    )
                }
            }

            else -> {
                val start = (order++ * staggerMillis).toInt()
                total = max(total, start + stepDurationMillis)
                UfiRollSlot(
                    kind = UfiRollKind.Swap,
                    from = pair.old?.toString().orEmpty(),
                    to = pair.new.toString(),
                    startMs = start,
                    durationMs = stepDurationMillis
                )
            }
        }
    }
    return UfiRollPlan(slots, total)
}

/**
 * 把旧串与新串按 LCS 对齐，输出**以新串为骨架**的槽位序列。
 *
 * 为什么要 LCS 而不是逐下标比：`"9月9日" → "9月10日"` 长度不同，逐下标比会判成
 * "9→1、月→0、9→月、日→日" 满盘皆变；LCS 能认出 `9`/`月`/`日` 没动，只有插入的 `1` 与
 * `9→0` 在变。串长最多二十几个字符，一次 O(n·m) 的表可以忽略。
 *
 * 被删掉的旧字符与新插入的字符按位置一一配对（配不上的插入就是"从无到有"）；
 * 多余的删除直接消失 —— 它在新串里没有落脚点，硬留一个滚出去的空槽会改变整行宽度。
 */
private fun ufiRollingAlign(from: String, to: String): List<UfiRollPair> {
    val n = from.length
    val m = to.length
    val dp = Array(n + 1) { IntArray(m + 1) }
    for (i in n - 1 downTo 0) {
        for (j in m - 1 downTo 0) {
            dp[i][j] = if (from[i] == to[j]) {
                dp[i + 1][j + 1] + 1
            } else {
                max(dp[i + 1][j], dp[i][j + 1])
            }
        }
    }

    val result = ArrayList<UfiRollPair>(m)
    val pendingOld = ArrayList<Char>()
    val pendingNew = ArrayList<Char>()
    fun flush() {
        pendingNew.forEachIndexed { k, c ->
            result += UfiRollPair(old = pendingOld.getOrNull(k), new = c, unchanged = false)
        }
        pendingOld.clear()
        pendingNew.clear()
    }

    var i = 0
    var j = 0
    while (i < n && j < m) {
        when {
            from[i] == to[j] -> {
                flush()
                result += UfiRollPair(old = to[j], new = to[j], unchanged = true)
                i++
                j++
            }
            dp[i + 1][j] >= dp[i][j + 1] -> {
                pendingOld += from[i]
                i++
            }
            else -> {
                pendingNew += to[j]
                j++
            }
        }
    }
    while (i < n) {
        pendingOld += from[i]
        i++
    }
    while (j < m) {
        pendingNew += to[j]
        j++
    }
    flush()
    return result
}
