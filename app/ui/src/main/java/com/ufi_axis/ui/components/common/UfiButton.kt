// [F24] STABLE-UI-API：公共组件签名默认冻结。
//
// 解冻准则（D8，2026-09-04 P4c 更新）：**只有"减少入口数量"的签名改动值得解冻**。
// 纯改名、加可选参数、调整参数顺序都不算 —— 那些只会让全仓调用点白改一遍。
// 本文件在 P4c 按此准则解冻过一次：把 5 个"带文字的按钮"合并成 1 个 [UfiButton]，
// 入口数 6 → 2（[UfiButton] + 布局容器 [UfiButtonRow]）。合并后签名重新冻结。
// 实验性组件请使用 @UfiExperimentalApi（见 UfiStableApi.kt / UfiExperimentalApi.kt）。
//
// ══════════════════════════════════════════════════════════════════════════════
// 2026-09-04（P4c 按钮收敛）：本文件原有 5 个「带文字的按钮」，底层都是 M3
// Button/OutlinedButton，差异只在宽度/高度/文字样式/底色/是否支持 loading。
// 它们是目标 G1「不要一个界面出现多种 UI」剩下最大的一块 —— 同一个页面里
// 「实底小按钮」和「描边小按钮」来自两个不同组件，尺寸/圆角/disabled 透明度
// 各自演化，对不齐时只能一处一处手工追。现全部合并为：
//
//     UfiPrimaryButton        → UfiButton(variant = Primary,   size = Standard)
//     UfiSecondaryButton      → UfiButton(variant = Secondary, size = Standard)
//     UfiDangerButton         → UfiButton(variant = Danger,    size = Standard)
//     UfiSmallButton          → UfiButton(variant = Primary,   size = Small)
//     UfiOutlinedActionButton → UfiButton(variant = Subtle,    size = Small)
//
// 旧 5 个**直接删除，不留 @Deprecated 薄封装**（D6）：app/ui 是仓内模块无外部
// 消费者，留废弃层等于把 G1 要消灭的"同一件事两种写法"再造一遍；而 Kotlin 编译
// 错误本身就是一份不会漏项的待改清单。
//
// ⚠️ 规约：**新增按钮样式请加 variant（或 size），不要新建组件。**
// 判断标准很简单——如果它长得像个"带文字、可点、有按压缩放"的按钮，它就是
// [UfiButton] 的一个 variant。只有**角色专用**（交互形态本身不同）才另立组件，
// 现存 5 个合法例外：UfiFloatingActionButton / UfiSendButton / UfiToolbarAction /
// UfiPopupMenuButton / DialogButtonRow。
//
// 2026-09-04（P2d 按压反馈收口）：按压缩放统一引用 UfiMotion.PressScale.Button。
// 按钮族的按压组合是全站基准：scale = PressScale.Button(0.96) +
// spec = UfiMotion.buttonPress()（spring 1.0/600，无回弹）。新增 variant 直接复用
// 下面 [UfiButton] 里那一段，不要自选数值；其他元素类的档位见 UfiMotion.PressScale 的 KDoc。
//
// 2026-09-04（P2f 短按看不见修复）：按压缩放的**播放方式**改为
// `Modifier.ufiPressScale`（`ui/animation/PressFeedback.kt`），档位与 spec 不变。
// 原因与编排细节见该文件头部；新增 variant 直接复用下面那一行，不要退回
// `animateFloatAsState(if (isPressed) …)` 的写法 —— 那个写法短按看不见。
// ══════════════════════════════════════════════════════════════════════════════
@file:UfiStableApi
package com.ufi_axis.ui.components.common

import com.ufi_axis.ui.components.common.UfiStableApi
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import com.ufi_axis.ui.animation.ufiPressScale
import com.ufi_axis.ui.theme.DonatePink
import com.ufi_axis.ui.theme.LocalResolvedPalette
import com.ufi_axis.ui.theme.Spacing
import com.ufi_axis.ui.theme.UfiCardDefaults
import com.ufi_axis.ui.theme.UfiTextStyles
import com.ufi_axis.ui.theme.UfiWeight
import com.ufi_axis.ui.theme.UfiMotion

/**
 * 按钮视觉角色。**用 enum 而不是 `filled`/`danger` 之类 boolean 组合** ——
 * boolean 组合会允许出 `filled = false, danger = true` 这种没人定义过观感的状态，
 * enum 让"合法样式"这件事在类型上就是穷举的。
 *
 * 决定：底色 / 文字色 / 描边 / 圆角。**不决定尺寸**（尺寸看 [UfiButtonSize]）。
 */
enum class UfiButtonVariant {
    /** 实底 accent + `palette.onAccent` 文字。页面/弹窗的主操作。原 `UfiPrimaryButton` / `UfiSmallButton`。 */
    Primary,

    /** accent 描边 + accent 文字，无底色。与 [Primary] 并排的次操作（取消/返回）。原 `UfiSecondaryButton`。 */
    Secondary,

    /** 实底 `palette.error` + `palette.onError` 文字。破坏性操作（删除/清空）。原 `UfiDangerButton`。 */
    Danger,

    /**
     * 弱描边（`palette.dialogBorder`）+ 正文色文字 + 更小的 `subtleShape` 圆角。
     * 用于「重新检测 / 同步 / 选择 APK」这类**按需操作**，视觉重量低于 [Secondary]，
     * 适合塞进 UfiSettingsItem 的 trailing 或卡片标题行右侧。原 `UfiOutlinedActionButton`。
     */
    Subtle,

    /**
     * [DonatePink] 描边 + 同色文字，几何与 [Secondary] 完全一致，只换颜色。
     *
     * 唯一用途：关于页的赞赏入口。为什么单独开一档而不是在调用点自己拼一个描边 Row ——
     * 赞赏是个按钮，几何（高度 / 圆角 / 内距 / 按压缩放）必须与全站按钮一致，
     * 只有配色是刻意例外；把这个例外收进 variant，调用点就不会又长出一个伪按钮。
     *
     * 粉色**不跟随皮肤**（理由见 [DonatePink]），所以这一档在任何主题下观感都相同。
     */
    Donate,
}

/**
 * 按钮尺寸档。决定：高度 / 内边距 / 文字样式 / [UfiButton] 的 `fillWidth` 默认值。
 */
enum class UfiButtonSize {
    /** 高 [Spacing.ButtonHeight]（48dp，Android 触摸目标下限），M3 默认内距，加粗正文。默认铺满宽度。 */
    Standard,

    /** 高 [Spacing.SmallButtonHeight]（36dp），内距 h16/v0，`tagStrong` 小字。默认自适应宽度。 */
    Small,
}

/**
 * 全站唯一的「带文字按钮」入口（P4c 由 5 个组件合并而来，映射表见文件头注释）。
 *
 * @param variant 视觉角色，见 [UfiButtonVariant]。
 * @param size 尺寸档，见 [UfiButtonSize]。
 * @param loading `true` 时左侧插入转圈并**自动禁用点击**（`enabled && !loading`），
 *   行为与合并前的 `UfiPrimaryButton` 完全一致。合并前只有 Primary/Secondary 两个
 *   Standard 按钮支持 loading，现在所有 variant × size 都支持。
 * @param fillWidth 是否铺满可用宽度。**默认按 [size] 推导**（Standard 铺满 / Small 自适应），
 *   这恰好等于合并前 5 个组件各自的固定行为，所以全部调用点都不需要显式传它。
 *   之所以做成独立参数而不是让 size 硬性隐含：宽度是布局属性、尺寸是视觉属性，
 *   把两者焊死会逼出"为了不铺满而降一档高度"这种拿视觉换布局的用法（G1 反例）。
 *   横排等宽仍然用 `modifier = Modifier.weight(1f)` + [UfiButtonRow]，不要动这个参数。
 * @param icon 文字左侧的前导图标，`null` 表示不显示。尺寸固定 [Spacing.IconSizeSmall]，
 *   `tint` 跟随按钮的 `contentColor`（各 variant 自己的文字色），所以传进来的矢量应是单色。
 *   与 [loading] **互斥**：loading 期间该槽位让给转圈，避免图标 + 转圈同时挤在文字左边。
 */
@Composable
fun UfiButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: UfiButtonVariant = UfiButtonVariant.Primary,
    size: UfiButtonSize = UfiButtonSize.Standard,
    enabled: Boolean = true,
    loading: Boolean = false,
    fillWidth: Boolean = size == UfiButtonSize.Standard,
    icon: ImageVector? = null,
) {
    val palette = LocalResolvedPalette.current
    val interactionSource = remember { MutableInteractionSource() }

    // 尺寸/形状/描边：逐项复刻合并前的取值，不新造档位。
    val sizedModifier = modifier
        .then(if (fillWidth) Modifier.fillMaxWidth() else Modifier)
        .height(
            when (size) {
                UfiButtonSize.Standard -> Spacing.ButtonHeight
                UfiButtonSize.Small -> Spacing.SmallButtonHeight
            }
        )
        // 2026-09-04（P2f 短按看不见）：原为 collectIsPressedAsState + animateFloatAsState +
        // graphicsLayer 三段。为什么原来短按看不见：animateFloatAsState 只"跟随目标值"，
        // 抬手瞬间目标翻回 1f，动画就地反向拉回；按钮档落差仅 0.04，一帧只走出约 6%
        // （scale ≈ 0.998，零点几个像素），而在滚动容器里 Press 甚至与 Release 同帧送达，
        // 可用时长是 0 —— 所以只有长按看得见。
        // 现在怎么保证：ufiPressScale 改成事件驱动手动编排，抬手时若按下相位不足
        // Duration.Micro（120ms）会先把"缩到位"补播完再弹回，短按也有完整可见缩放。
        // 档位与 spec 原样传入（PressScale.Button + buttonPress），长按观感零回归。
        .ufiPressScale(interactionSource, UfiMotion.PressScale.Button, UfiMotion.buttonPress())

    val shape = when (variant) {
        // Subtle 沿用旧 UfiOutlinedActionButton 的 subtleShape（8dp）—— 比 buttonShape(12dp) 更方，
        // 是它"视觉重量更低"的一部分，不要顺手抹平成 buttonShape。
        UfiButtonVariant.Subtle -> UfiCardDefaults.subtleShape
        else -> UfiCardDefaults.buttonShape
    }

    val contentPadding = when (size) {
        // Standard 沿用 M3 默认（h24/v8）—— 合并前 Primary/Secondary/Danger 都没显式传，吃的就是这个。
        UfiButtonSize.Standard -> ButtonDefaults.ContentPadding
        UfiButtonSize.Small -> PaddingValues(
            horizontal = Spacing.SmallButtonPaddingH,
            vertical = Spacing.SmallButtonPaddingV
        )
    }

    val content: @Composable RowScope.() -> Unit = {
        if (loading) {
            UfiLoadingIndicator(
                modifier = Modifier.size(Spacing.ButtonLoadingIndicatorSize),
                strokeWidth = LOADING_STROKE_WIDTH,
                // 与紧邻的按钮文字同色：文字吃的是 M3 提供的 contentColor（Primary=onAccent、
                // Secondary=accent、Danger=onError），转圈若用默认 accent，在 accent 实底上
                // 就是同色压同色，且与文字不同色。
                color = LocalContentColor.current
            )
            Spacer(Modifier.width(Spacing.Small))
        } else if (icon != null) {
            // 与 loading 共用文字左侧这一个槽位：转圈时不再画图标，否则两者会挤在一起。
            // 不给 contentDescription —— 图标是文字的装饰，按钮语义已由 text 提供，
            // 补一遍会让读屏念两次。
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(Spacing.IconSizeSmall)
            )
            Spacer(Modifier.width(Spacing.Small))
        }
        when {
            size == UfiButtonSize.Small -> Text(text, style = UfiTextStyles.tagStrong)

            // 2026-09-03：Standard 档按钮文案强制单行。按钮多为等宽 weight(1f) 布局，稍长的中文标签
            // （如「自定义起止日期」）会在半行宽度下折成两行，把按钮撑高、破掉 ButtonHeight
            // 的固定高度。宁可省略号也不换行；真放不下应该缩短文案而不是靠折行。
            //
            // ⚠️ Danger 是唯一例外：合并前的 UfiDangerButton 就没设 maxLines，这里刻意保留该差异，
            // 以保证 P4c 是"零观感变化"的纯入口收敛。要统一成单行请单独一次改动（见 P4c 遗留项）。
            variant == UfiButtonVariant.Danger -> Text(text, fontWeight = UfiWeight.Strong)

            else -> Text(text, fontWeight = UfiWeight.Strong, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }

    when (variant) {
        UfiButtonVariant.Primary, UfiButtonVariant.Danger -> Button(
            onClick = onClick,
            modifier = sizedModifier,
            enabled = enabled && !loading,
            shape = shape,
            contentPadding = contentPadding,
            colors = if (variant == UfiButtonVariant.Danger) {
                ButtonDefaults.buttonColors(
                    containerColor = palette.error,
                    // 2026-09-03（P1c）：原为写死 `Color.White`。底色是 palette.error（可按主题配置），
                    // 换配色时若某主题把 error 调成浅红/珊瑚色，白字直接糊在底上看不见。
                    // 这里接 onError 而不是 onAccent —— 语义上"错误色之上的内容色"，
                    // 与 accent 无关，两者必须能独立取值（见 ThemePalette.onErrorLight 的说明）。
                    contentColor = palette.onError,
                    disabledContainerColor = palette.error.copy(alpha = DANGER_DISABLED_CONTAINER_ALPHA)
                    // disabledContentColor 刻意不传：合并前的 UfiDangerButton 也没传，吃的是 M3 默认
                    // （onSurface@38%）。传了就变观感了。
                )
            } else {
                ButtonDefaults.buttonColors(
                    containerColor = palette.accent,
                    contentColor = palette.onAccent,
                    disabledContainerColor = palette.accent.copy(alpha = FILLED_DISABLED_CONTAINER_ALPHA),
                    disabledContentColor = palette.onAccent.copy(alpha = FILLED_DISABLED_CONTENT_ALPHA)
                )
            },
            interactionSource = interactionSource,
            content = content
        )

        UfiButtonVariant.Secondary, UfiButtonVariant.Subtle, UfiButtonVariant.Donate -> {
            // 三档共用 OutlinedButton 骨架，差异只在「描边色 / 文字色 / disabled 透明度」三项。
            // Donate 与 Secondary 几何完全一致（同 buttonShape、同描边粗细），只换成不跟随皮肤的粉。
            val outlineColor = when (variant) {
                UfiButtonVariant.Secondary -> palette.accent
                UfiButtonVariant.Donate -> DonatePink
                else -> palette.dialogBorder
            }
            val labelColor = when (variant) {
                UfiButtonVariant.Secondary -> palette.accent
                UfiButtonVariant.Donate -> DonatePink
                else -> palette.textPrimary
            }
            val disabledAlpha = when (variant) {
                // Donate 沿用 Secondary 的 disabled 透明度：两者是同一种描边按钮，
                // 没有理由让"赞赏被禁用"看起来比"次操作被禁用"更淡或更实。
                UfiButtonVariant.Secondary, UfiButtonVariant.Donate -> SECONDARY_DISABLED_CONTENT_ALPHA
                else -> SUBTLE_DISABLED_CONTENT_ALPHA
            }
            OutlinedButton(
                onClick = onClick,
                modifier = sizedModifier,
                enabled = enabled && !loading,
                shape = shape,
                contentPadding = contentPadding,
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = labelColor,
                    disabledContentColor = labelColor.copy(alpha = disabledAlpha)
                ),
                border = BorderStroke(Spacing.ButtonBorderWidth, outlineColor),
                interactionSource = interactionSource,
                content = content
            )
        }
    }
}

/**
 * 等宽横排按钮容器（**布局容器，不是按钮**，所以 P4c 没把它并进 [UfiButton]）。
 *
 * 典型用法：`UfiButtonRow { UfiButton(..., variant = Secondary, modifier = Modifier.weight(1f)); UfiButton(..., modifier = Modifier.weight(1f)) }`
 */
@Composable
fun UfiButtonRow(
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.Medium),
        content = content
    )
}

// ── disabled 透明度 / 描边粗细等"非 dp"常量 ─────────────────────────────────────
// 这些值全部照抄合并前的 5 个组件，改动任意一个都会改观感，不是可以随手调的旋钮。
// 各 variant 的 disabled 透明度**刻意各不相同**（历史各自演化的结果），P4c 只做入口收敛，
// 不顺手抹平；要统一成一套 disabled 规则请单独一次改动并过一遍画廊页。

/** loading 转圈的描边粗细（Float，非 dp —— UfiLoadingIndicator 内部按 Stroke 用）。 */
private const val LOADING_STROKE_WIDTH = 2f

/** 实底 accent 按钮 disabled 底色透明度（旧 UfiPrimaryButton / UfiSmallButton）。 */
private const val FILLED_DISABLED_CONTAINER_ALPHA = 0.4f

/** 实底 accent 按钮 disabled 文字透明度（旧 UfiPrimaryButton / UfiSmallButton）。 */
private const val FILLED_DISABLED_CONTENT_ALPHA = 0.6f

/** error 实底按钮 disabled 底色透明度（旧 UfiDangerButton，比实底 accent 更淡）。 */
private const val DANGER_DISABLED_CONTAINER_ALPHA = 0.3f

/** accent 描边按钮 disabled 文字透明度（旧 UfiSecondaryButton）。 */
private const val SECONDARY_DISABLED_CONTENT_ALPHA = 0.5f

/** 弱描边按钮 disabled 文字透明度（旧 UfiOutlinedActionButton）。 */
private const val SUBTLE_DISABLED_CONTENT_ALPHA = 0.4f
