// [F24] STABLE-UI-API：公共组件签名已冻结，请勿在无向后兼容前提下修改；实验性组件请使用 @UfiExperimentalApi（见 UfiStableApi.kt / UfiExperimentalApi.kt）。
package com.ufi_axis.ui.components.common

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.ufi_axis.ui.animation.rememberUfiPressScale
import com.ufi_axis.ui.theme.LocalResolvedPalette
import com.ufi_axis.ui.theme.Spacing
import com.ufi_axis.ui.theme.UfiCardDefaults
import com.ufi_axis.ui.theme.UfiMotion
import com.ufi_axis.ui.theme.UfiTextStyles
import com.ufi_axis.ui.theme.UfiWeight
import com.ufi_axis.ui.theme.ufiCardShadow

/**
 * 引导式表单（向导）的单步声明。
 *
 * @param label 步骤条上的短标签（2~4 字，横排 N 个不换行）。
 * @param heading 面板标题：写成**这一步要回答的问题**（「什么时候执行」），
 *   而不是字段名的复述（「执行周期」）—— 后者会与步骤条标签、以及步内卡片标题三重重复。
 * @param description 面板说明；为空时不渲染。
 * @param validate 本步是否可以离开。返回 `null` = 通过；返回字符串 = **拦下的原因**，
 *   由 [UfiWizard] 渲染在底部操作栏上方并置灰「下一步」。
 *
 *   这个回调在**组合期**被调用，所以它读到的 `MutableState` 会被登记为组合读取 ——
 *   用户边打字，按钮的置灰/恢复就边跟着变，不需要调用方再自己驱动一次刷新。
 *   因此实现里只许做纯判断，不要有副作用。
 * @param content 本步的表单内容。横向内距由 [UfiWizard] 的面板统一提供，内容不要再叠一层。
 */
class UfiWizardStep(
    val label: String,
    val heading: String,
    val description: String = "",
    val validate: () -> String? = { null },
    val content: @Composable () -> Unit
)

/** [UfiWizardReviewCard] 的一行：左键名 / 右取值。 */
data class UfiWizardReviewRow(
    val label: String,
    val value: String
)

/**
 * 分步引导表单：顶部步骤条 + 中部单步面板 + 底部固定操作栏。
 *
 * ## 为什么要这个组件
 * 字段一多的编辑页（定时任务：启用 + 名称 + 分类 + 动作 + 动作参数 + 周期；条件规则：
 * 启用 + 名称 + 触发 + 触发参数 + 动作 + 动作参数）过去都是「一根长滚动条塞四五张卡片」。
 * 两个毛病：① 用户要先读完整页才知道哪些是必填；② 保存按钮在标题栏右上角，
 * 校验不通过时**静默无反应**（点了没动静，没有任何提示）。
 *
 * 本组件把这两件事收进同一处：分步只暴露当前要填的字段，
 * 而「能不能往下走」由每步的 [UfiWizardStep.validate] 显式回答，拦下时给出原因。
 *
 * ## 交互契约
 * - 步骤条可**任意跳**，前提是被跳过的每一步此刻都校验通过：往回点永远成立；往前点会逐个
 *   检查中间各步，全通过就直接落到目标步，否则把用户送到第一个不合法的那一步并由
 *   [onStepBlocked] 报出原因。这样"改一个已保存的对象"不必从第 1 步重走，同时没有任何一步的
 *   校验被绕过。
 * - 当前步 `validate()` 非 null 时，「下一步 / 完成」置灰，且原因渲染在操作栏上方。
 *   **不靠 toast 兜底** —— 置灰的按钮如果不说明理由，就只是把静默失败换了个样子。
 * - 末步的主按钮文案换成 [finishText]，点击走 [onFinish]（调用方在那里提交 + 退出）。
 *
 * ## 布局
 * `fillMaxSize`，自己吃掉全部可用高度：步骤条与操作栏固定，只有中间面板滚动。
 * 因此操作栏永远在屏幕底部可见（旧的长滚动页里 CTA 会被滚走）。
 *
 * @param currentStep 当前步序号，由调用方持有（便于返回键/外部跳转介入）。
 * @param onStepChange 请求切换到某一步。
 * @param finishLoading 末步主按钮的转圈态（提交是异步的场合传它）。为 true 时按钮自动禁用。
 * @param onExit 第 0 步时「上一步」的替代动作，配 [exitText] 一起传。
 *   不传则第 0 步左键置灰（向导是页面的全部内容、退出另有入口时用这个默认）。
 *   **当向导所在页面没有返回键时必须传**：否则用户进了第 0 步就出不去。
 */
@Composable
fun UfiWizard(
    steps: List<UfiWizardStep>,
    currentStep: Int,
    onStepChange: (Int) -> Unit,
    onFinish: () -> Unit,
    modifier: Modifier = Modifier,
    finishText: String = "完成",
    finishLoading: Boolean = false,
    exitText: String? = null,
    onExit: (() -> Unit)? = null,
    onStepBlocked: (String) -> Unit = {}
) {
    if (steps.isEmpty()) return
    val palette = LocalResolvedPalette.current
    val lastIndex = steps.lastIndex
    val index = currentStep.coerceIn(0, lastIndex)
    // 组合期求值：见 UfiWizardStep.validate 的说明（按钮置灰随输入实时跟随）
    val blockReason = steps[index].validate()

    Column(modifier = modifier.fillMaxSize()) {
        WizardStepper(
            labels = steps.map { it.label },
            currentStep = index,
            onStepClick = { target ->
                // 往前跳：被跳过的每一步（含当前步）都必须**此刻**就是合法的。
                //
                // 为什么不是"只许往回点"：编辑一个已保存的对象时，每一步本来就都填好了，
                // 逼用户从第 1 步一路点到第 3 步只是白走两屏。而只要中间各步都合法，
                // 跳过它们并不会绕过任何校验 —— 这条判据同时满足"能跳"与"不许绕"。
                //
                // 拦下时不是原地不动，而是把用户带到**第一个**不合法的那一步并说明原因：
                // 停在原处只会让人反复点同一个圆点。
                val firstBad = if (target <= index) null else {
                    (index until target)
                        .asSequence()
                        .map { it to steps[it].validate() }
                        .firstOrNull { (_, reason) -> reason != null }
                }
                if (firstBad == null) {
                    onStepChange(target)
                } else {
                    val (badIndex, reason) = firstBad
                    onStepChange(badIndex)
                    onStepBlocked("请先完成「${steps[badIndex].label}」：$reason")
                }
            }
        )

        AnimatedContent(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            targetState = index,
            transitionSpec = {
                // 前进：新面板从右侧推入；后退：从左侧退回。位移取容器宽度的 1/PANEL_SLIDE_DIVISOR，
                // 只做"方向暗示"而不是整屏翻页 —— 步骤条还停在原位，整屏平移会显得两者脱节。
                val forward = targetState > initialState
                val enter = fadeIn(tween(UfiMotion.Duration.Standard)) + slideInHorizontally {
                    if (forward) it / PANEL_SLIDE_DIVISOR else -it / PANEL_SLIDE_DIVISOR
                }
                val exit = fadeOut(tween(UfiMotion.Duration.Swift)) + slideOutHorizontally {
                    if (forward) -it / PANEL_SLIDE_DIVISOR else it / PANEL_SLIDE_DIVISOR
                }
                (enter togetherWith exit).using(SizeTransform(clip = false))
            },
            label = "wizardPanel"
        ) { panelIndex ->
            val step = steps[panelIndex.coerceIn(0, lastIndex)]
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(
                        horizontal = Spacing.CardHorizontalMargin,
                        vertical = Spacing.Large
                    ),
                verticalArrangement = Arrangement.spacedBy(Spacing.Large)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.Small)) {
                    Text(
                        text = step.heading,
                        style = UfiTextStyles.panelTitleStrong,
                        color = palette.textPrimary
                    )
                    if (step.description.isNotBlank()) {
                        Text(
                            text = step.description,
                            style = UfiTextStyles.note,
                            color = palette.textSecondary
                        )
                    }
                }
                step.content()
            }
        }

        UfiDivider()
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(palette.cardBg)
                .padding(
                    horizontal = Spacing.CardHorizontalMargin,
                    vertical = Spacing.Large
                ),
            verticalArrangement = Arrangement.spacedBy(Spacing.Medium)
        ) {
            AnimatedVisibility(
                visible = blockReason != null,
                enter = fadeIn(tween(UfiMotion.Duration.Quick)) + expandVertically(),
                exit = fadeOut(tween(UfiMotion.Duration.Micro)) + shrinkVertically()
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.WarningAmber,
                        contentDescription = null,
                        tint = palette.warning,
                        modifier = Modifier.size(Spacing.IconSizeSmall)
                    )
                    Spacer(Modifier.width(Spacing.Medium))
                    Text(
                        // blockReason 在这一帧可能已变 null（退出动画期间），留空串即可
                        text = blockReason.orEmpty(),
                        style = UfiTextStyles.note,
                        color = palette.warning
                    )
                }
            }
            UfiButtonRow {
                // 第 0 步没有"上一步"可去：调用方给了 onExit 就把左键让给它（返回上一级 / 取消），
                // 否则置灰。向导所在页面若没有返回键，这是唯一的出口。
                val exitMode = index == 0 && onExit != null
                UfiButton(
                    text = if (exitMode) (exitText ?: "取消") else "上一步",
                    onClick = { if (exitMode) onExit?.invoke() else onStepChange(index - 1) },
                    modifier = Modifier.weight(1f),
                    variant = UfiButtonVariant.Secondary,
                    enabled = (index > 0 || exitMode) && !finishLoading
                )
                UfiButton(
                    text = if (index == lastIndex) finishText else "下一步",
                    onClick = {
                        if (index == lastIndex) onFinish() else onStepChange(index + 1)
                    },
                    modifier = Modifier.weight(1f),
                    enabled = blockReason == null,
                    loading = index == lastIndex && finishLoading
                )
            }
        }
    }
}

/**
 * 确认页的一张信息卡（标题 + 若干「键 / 值」行）。
 *
 * 为什么把行做成 `List` 参数而不是 `content` 槽：行间分隔线要"最后一行不画"，
 * 交给调用方用槽位拼就必然各写一遍索引判断；条件行用 `buildList { if (…) add(…) }` 表达即可。
 */
@Composable
fun UfiWizardReviewCard(
    title: String,
    icon: ImageVector,
    rows: List<UfiWizardReviewRow>,
    modifier: Modifier = Modifier
) {
    val palette = LocalResolvedPalette.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(UfiCardDefaults.mediumShape)
            .background(palette.cardBg)
            .border(Spacing.WizardCardBorder, palette.divider, UfiCardDefaults.mediumShape)
            .padding(Spacing.InnerPadding)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = palette.accent,
                modifier = Modifier.size(Spacing.IconSizeSmall)
            )
            Spacer(Modifier.width(Spacing.Medium))
            Text(
                text = title,
                style = UfiTextStyles.cardTitle,
                color = palette.textPrimary
            )
        }
        rows.forEachIndexed { i, row ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = Spacing.Medium),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = row.label,
                    style = UfiTextStyles.note,
                    color = palette.textSecondary
                )
                Spacer(Modifier.width(Spacing.Large))
                // 取值占满剩余宽度并右对齐：这样长取值（Shell 命令、cron）会在自己那半边折行，
                // 而不是把左边的键名挤掉。
                Text(
                    text = row.value,
                    style = UfiTextStyles.noteEmphasis,
                    color = palette.textPrimary,
                    textAlign = TextAlign.End,
                    modifier = Modifier.weight(1f)
                )
            }
            if (i != rows.lastIndex) UfiDivider()
        }
    }
}

// ──────────── 私有实现 ────────────

/**
 * 步骤条：N 列等宽，每列 = 连接线 + 圆点 + 标签。
 *
 * 连接线不用绝对定位（Compose 里那要 Layout 或 onGloballyPositioned 量宽）：
 * 每列自己画左右两条半宽线，圆点在同一个 Box 里**后绘制**且底色不透明，
 * 于是线自然被圆点盖住 —— 视觉等价于"点与点之间连一段"，但不需要知道兄弟节点的位置。
 */
@Composable
private fun WizardStepper(
    labels: List<String>,
    currentStep: Int,
    onStepClick: (Int) -> Unit
) {
    val palette = LocalResolvedPalette.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = Spacing.Medium,
                vertical = Spacing.Large
            )
    ) {
        labels.forEachIndexed { i, label ->
            val done = i < currentStep
            val active = i == currentStep
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clickable(
                        // 整列（含标签）都是命中区：圆点只有 WizardStepDot 大，单靠它太难点。
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { onStepClick(i) }
                    ),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(Spacing.WizardStepDot),
                    contentAlignment = Alignment.Center
                ) {
                    // 第 i 段连线（i-1 → i）在 currentStep >= i 时点亮
                    if (i > 0) {
                        Connector(
                            lit = currentStep >= i,
                            modifier = Modifier.align(Alignment.CenterStart)
                        )
                    }
                    if (i < labels.lastIndex) {
                        Connector(
                            lit = currentStep >= i + 1,
                            modifier = Modifier.align(Alignment.CenterEnd)
                        )
                    }
                    StepDot(indexLabel = (i + 1).toString(), done = done, active = active)
                }
                Spacer(Modifier.height(Spacing.Medium))
                Text(
                    text = label,
                    style = if (active) UfiTextStyles.captionEmphasis else UfiTextStyles.caption,
                    color = if (active) palette.accent else palette.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
private fun Connector(lit: Boolean, modifier: Modifier = Modifier) {
    val palette = LocalResolvedPalette.current
    val color by animateColorAsState(
        targetValue = if (lit) palette.accent else palette.divider,
        animationSpec = UfiMotion.colorSwap(),
        label = "wizardConnector"
    )
    Box(
        modifier = modifier
            .fillMaxWidth(CONNECTOR_HALF_WIDTH)
            .height(Spacing.WizardStepConnector)
            .background(color)
    )
}

/**
 * 步骤圆点。三态：未到（`cardBg` 实底 + 弱描边 + 序号）/ 当前（accent 实底 + 序号 + 阴影 + 放大）/
 * 已完成（accent 实底 + 勾）。
 *
 * ## 为什么三态底色都必须不透明
 * 连接线是从圆点**底下穿过**的（见 [WizardStepper] 的实现说明），靠圆点的不透明底色遮断。
 * 已完成态最初用的是 `accent @14%` 淡底 + 描边，于是那条线从圆点里透出来，
 * 观感是"空心圈里划了一道横线"——既不像"完成"，又把本该被藏住的实现细节暴露了。
 *
 * ## 当前步与已完成步为什么同色
 * 走过的链路（已完成 + 当前）连成一条 accent 实色，一眼能看出"走到哪了"；
 * 两者的区分放在**内容与层次**上：序号 vs 勾选、[ACTIVE_DOT_POP] 放大、以及一层阴影。
 *
 * 缩放的编排方式照搬 `ScheduleSelector` 的 TagCell：按压缩放与选中弹跳**相乘**后交给
 * 同一个 `graphicsLayer` —— 拆成两层会各自建图层，且两段动画的复合观感会变。
 */
@Composable
private fun StepDot(indexLabel: String, done: Boolean, active: Boolean) {
    val palette = LocalResolvedPalette.current
    val interactionSource = remember { MutableInteractionSource() }
    val pressScale by rememberUfiPressScale(
        interactionSource = interactionSource,
        pressedScale = UfiMotion.PressScale.Cell,
        spec = UfiMotion.buttonPress()
    )
    val popScale by animateFloatAsState(
        targetValue = if (active) ACTIVE_DOT_POP else 1f,
        animationSpec = UfiMotion.tagPop(),
        label = "wizardDotPop"
    )
    val reached = done || active
    val containerColor by animateColorAsState(
        targetValue = if (reached) palette.accent else palette.cardBg,
        animationSpec = UfiMotion.colorSwap(),
        label = "wizardDotBg"
    )
    val borderColor by animateColorAsState(
        targetValue = if (reached) palette.accent else palette.divider,
        animationSpec = UfiMotion.colorSwap(),
        label = "wizardDotBorder"
    )
    val contentColor by animateColorAsState(
        targetValue = if (reached) palette.onAccent else palette.textSecondary,
        animationSpec = UfiMotion.colorSwap(),
        label = "wizardDotFg"
    )
    Box(
        modifier = Modifier
            .size(Spacing.WizardStepDot)
            .graphicsLayer {
                scaleX = pressScale * popScale
                scaleY = pressScale * popScale
            }
            // 阴影必须排在 clip/background 之前（全站 ufiCardShadow 的用法约定）。
            .then(
                if (active) {
                    Modifier.ufiCardShadow(Spacing.WizardActiveDotElevation, CircleShape)
                } else {
                    Modifier
                }
            )
            .clip(CircleShape)
            .background(containerColor)
            .border(Spacing.WizardStepConnector, borderColor, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        if (done) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(Spacing.WizardStepCheck)
            )
        } else {
            Text(
                text = indexLabel,
                style = UfiTextStyles.caption.copy(fontWeight = UfiWeight.Strong),
                color = contentColor
            )
        }
    }
}

/** 面板横向位移量 = 容器宽度 / 6（方向暗示，不是整屏翻页）。 */
private const val PANEL_SLIDE_DIVISOR = 6

/** 每列自画的半宽连线占比。 */
private const val CONNECTOR_HALF_WIDTH = 0.5f

/** 当前步圆点的弹跳倍率（与 TagCell 的选中弹跳同档）。 */
private const val ACTIVE_DOT_POP = 1.06f

