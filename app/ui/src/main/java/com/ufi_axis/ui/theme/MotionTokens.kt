package com.ufi_axis.ui.theme

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween

/*
 * ╔══════════════════════════════════════════════════════════════════════╗
 * ║  动效令牌（UFI-AXIS 设计令牌的动效分册） —— ui.theme 唯一入口         ║
 * ╚══════════════════════════════════════════════════════════════════════╝
 *
 * 2026-09-04（P3a 令牌单一入口）：本文件把原先**横跨两个包**的动效令牌合并到了 `ui.theme`：
 * - `com.ufi_axis.ui.components.common.Ufi.kt` 的 `UfiMotion`（时长 / 缓动 / 按压缩放）
 * - `com.ufi_axis.ui.animation.UfiAnimations.kt` 的 `UfiAnimSpecs`（spring 参数）
 *
 * 搬动的动机（计划书 G2）：动效是唯一不在 `ui.theme` 的令牌类别，"想调一个动画时长"要在
 * `Ufi.kt`、`UfiAnimations.kt` 和几十个调用点之间来回找。搬完之后配色 / 字体 / 圆角 / 形状 /
 * elevation / 动效**全部**在 `ui.theme` 一个包里，索引见 `docs/app-theme-token-index.md`。
 *
 * ## 层次（合并后仍是单向，方向不变）
 * ```
 * UfiAnimSpecs（数值明细：spring 参数、贝塞尔控制点）
 *      ↓  只允许这个方向
 * UfiMotion（语义 token：Duration / Easing / PressScale / 语义 spec）
 *      ↓
 * 业务调用点
 * ```
 * 与 `Spacing → UfiCardDefaults`、`Typography → UfiTextStyles` 同构。
 * **[UfiAnimSpecs] 不得反向引用 [UfiMotion]** —— 明细层若去读语义层就成了循环定义，
 * 届时"数值的唯一来源"这句话不再成立（[UfiAnimSpecs.fadeEnter] 里 250 写成字面量而不是
 * 引 [UfiMotion.Duration.Fluid]，正是为了守住这个方向，见其 KDoc）。
 * 合并进同一个文件不改变这条约束，只是让两层放在一起便于对照。
 *
 * ## 旧包名的过渡层
 * 原位置各留了一个 `@Deprecated` 转发 object（`Ufi.kt` 的 `UfiMotion`、
 * `UfiAnimations.kt` 的 `UfiAnimSpecs`），**既有 import 零改动仍可编译**，
 * 只是会拿到废弃警告。约 287 处动画调用点因此不必一次性重写；新代码直接从 `ui.theme` 引入。
 */

/**
 * 动画规格明细层 —— 弹性（spring）参数与贝塞尔缓动的**唯一数值来源**。
 *
 * 设计原则：轻量、弹性、自然。
 *
 * 业务代码不要直接 import 本对象，走语义层 [UfiMotion]；本对象只被 [UfiMotion]
 * 与少数需要裸参数的组件（[CapsuleExpandStiffness]）引用。
 *
 * 为什么是泛型函数而不是 `val`：调用点既有 `animateFloatAsState` 也有 `animateColorAsState` /
 * `animateDpAsState`，`SpringSpec<T>` 的 T 必须由调用处推断，所以每个 token 是 `fun <T>`。
 *
 * 2026-09-01 批 5：全库 28 处 `spring(` 调用点的参数按「(dampingRatio, stiffness)」聚成 16 种组合，
 * 逐一在此具名并**保留原值**（差异做成 token 而不是抹平，像素/手感不变）。
 * 2026-09-04 P3a：整体从 `com.ufi_axis.ui.animation` 迁入本包，**内容逐字未改**。
 */
object UfiAnimSpecs {

    // ===== 按压 / 点击反馈 =====

    /** 通用点按缩放（0.6 / 500）：滑块拇指、[UfiMotion.press] 的底层曲线 */
    fun <T> clickScale(): SpringSpec<T> = spring(dampingRatio = 0.6f, stiffness = 500f)

    /** 按钮按压（1.0 / 600）：全部 Ufi 按钮族 + 监控页图标按钮，无回弹的紧实反馈 */
    fun <T> buttonPress(): SpringSpec<T> = spring(dampingRatio = 1f, stiffness = 600f)

    /** 小控件弹出（0.5 / 600）：勾选框、FAB，带明显回弹 */
    fun <T> controlPop(): SpringSpec<T> = spring(dampingRatio = 0.5f, stiffness = 600f)

    /** 发送按钮弹出（0.55 / 400）：输入栏 */
    fun <T> sendPop(): SpringSpec<T> = spring(dampingRatio = 0.55f, stiffness = 400f)

    // ===== 入场 / 转场 =====

    /** 列表交错入场（0.8 / 300） */
    fun <T> staggerEnter(): SpringSpec<T> = spring(dampingRatio = 0.8f, stiffness = 300f)

    /** 弹窗入场（0.75 / 300）—— 柔和弹性 */
    fun <T> dialogEnter(): SpringSpec<T> = spring(dampingRatio = 0.75f, stiffness = 300f)

    /** Toast 落下（0.6 / 350）—— 轻微弹跳 */
    fun <T> toastDrop(): SpringSpec<T> = spring(dampingRatio = 0.6f, stiffness = 350f)

    /** 面板入场缩放（0.5 / 400）：排程选择器 */
    fun <T> panelEnter(): SpringSpec<T> = spring(dampingRatio = 0.5f, stiffness = 400f)

    // ===== 选中 / 强调 =====

    /** 标签选中弹跳（0.5 / 1500）：排程选择器的日期标签 */
    fun <T> tagPop(): SpringSpec<T> = spring(dampingRatio = 0.5f, stiffness = 1500f)

    /** 提示气泡弹出（0.85 / 420）：图表 tooltip 的缩放与横向跟随 */
    fun <T> tooltipPop(): SpringSpec<T> = spring(dampingRatio = 0.85f, stiffness = 420f)

    /** 指示条滑动（0.8 / 500）：TabRow 选中条 */
    fun <T> tabSlider(): SpringSpec<T> = spring(dampingRatio = 0.8f, stiffness = 500f)

    /** 翻页脉冲（0.42 / 520）：监控页翻页强回弹 */
    fun <T> pagePulse(): SpringSpec<T> = spring(dampingRatio = 0.42f, stiffness = 520f)

    // ===== 连续量 / 颜色过渡 =====

    /** 开关拇指位移（0.7 / 400） */
    fun <T> switchThumb(): SpringSpec<T> = spring(dampingRatio = 0.7f, stiffness = 400f)

    /** 颜色沉降（1.0 / 400）：开关轨道与拇指配色，无回弹 */
    fun <T> colorSettle(): SpringSpec<T> = spring(dampingRatio = 1f, stiffness = 400f)

    /** 颜色快切（1.0 / 1500）：任务卡图标底色/着色，Compose 默认刚度 */
    fun <T> colorSwap(): SpringSpec<T> = spring(dampingRatio = 1f, stiffness = 1500f)

    /** 滑块轨道跟随（1.0 / 350）：非拖拽状态下的数值回落 */
    fun <T> sliderTrack(): SpringSpec<T> = spring(dampingRatio = 1f, stiffness = 350f)

    /**
     * 胶囊展开刚度（900f，dampingRatio 走 `Spring.DampingRatioNoBouncy`）。
     *
     * 只有这一项以裸常量而非 spec 函数暴露：`CapsuleRegressionGuardTest` 用正则要求
     * `UfiCapsuleTabBar` 的 `EXPAND_SPRING` 赋值处 40 字符内出现字面 `spring(`
     * （护栏意图是防止有人换成 `snap()` 导致展开瞬变），所以调用点必须保留 `spring(` 文本。
     *
     * 2026-09-04（P3a）：随本对象迁包。护栏读的是 `UfiCapsuleTabBar.kt` 的**源文本**，
     * 与本常量所在的包无关，所以迁包不影响它；调用点只改了 import 行。
     *
     * 2026-09-08：3000f → 900f。3000f 让胶囊在 ~0.15s 内长到位，而标签淡入是 320ms 的
     * tween，一次交互被拆成"胶囊先跳、字后显影"两段 —— 这就是「文字显示消失突兀」的来源。
     * 900f ≈ 0.3s，与标签同量级，整体读起来是一段运动。
     */
    const val CapsuleExpandStiffness = 900f

    // ===== 非 spring（历史遗留，由 UfiMotion 转发）=====

    /**
     * 通用平滑入场（250ms / FastOutSlowIn）。
     *
     * 250 与 [UfiMotion.Duration.Fluid] 是同一个值 —— 本层是明细层（数值的唯一来源），
     * **不能反向依赖语义层 [UfiMotion]**，所以这里写字面量而不是引 token。
     * 改动请与 `Duration.Fluid` 同步。
     * （P3a 合并到同一个文件后这条约束依然成立：同文件不等于可以互相引用。）
     *
     * ⚠ 2026-09-04（P2b 审计）：本 spec **全库无调用点**，保留仅为兼容外部引用。
     */
    val fadeEnter: TweenSpec<Float> =
        tween(durationMillis = 250, easing = FastOutSlowInEasing)

    /** Toast 缓动 */
    val toastDropEasing: CubicBezierEasing =
        CubicBezierEasing(0.34f, 1.25f, 0.64f, 1f)

    /** M3 emphasized decelerate（入场）：极速起步、优雅减速 */
    val emphasizedInEasing: CubicBezierEasing =
        CubicBezierEasing(0.05f, 0.7f, 0.1f, 1.0f)

    /** M3 emphasized accelerate（离场） */
    val emphasizedOutEasing: CubicBezierEasing =
        CubicBezierEasing(0.3f, 0.0f, 0.8f, 0.15f)
}

/**
 * 动效语义层（UFI-AXIS 唯一动效入口）。
 *
 * 分层关系：[UfiAnimSpecs]（spring 明细）→ 本对象（语义 token）→ 业务调用点，
 * 与 `Spacing → UfiCardDefaults` 同构。业务代码不要再写 `tween(数字)` / 裸 easing，
 * 统一走 [Duration] / [Easing] / 下方语义 spec；调整全站节奏只改这里。
 *
 * 2026-09-01 批 4 从 116 处 tween 字面量收敛而来，档位值刻意保留原观感。
 * 2026-09-04 P3a：整体从 `com.ufi_axis.ui.components.common` 迁入本包，**内容逐字未改**。
 */
object UfiMotion {
    /**
     * 时长梯度（毫秒）。命名按用途而非数值，新增档位前先确认现有档位真的不适用。
     *
     * 2026-09-04（P2b 动效收口）：批 4 遗留的长尾值全部收编完毕，原注释里
     * 「150/240/250/260/320/350/420/800/1000/1500 暂未收编」这句已作废：
     * - 新增档位（**原值保留**，不吸附）：[Flick] 90 / [Fluid] 250 / [Sweeping] 320 /
     *   [Emphatic] 420 / [Orbit] 1000 / [Pulse] 1500；
     * - 吸附掉的值（差值 ≤20ms，为的是不让"同一种手感存在两个数"）：
     *   140→[Micro] 120、150→[Swift] 160、240→[Fluid] 250、260→[Gentle] 280；
     * - 800 本来就等于 [Languid]、120 等于 [Micro]，只是换 token，零观感变化；
     * - `tween(0)`（图表值域"禁用动画"）改用 `snap()`，语义正确且不再占一个时长值。
     *
     * 全库仅剩两处**有意例外**的时长不在本梯度上，各自在调用点注释说明理由：
     * `MainNavGraph.CAPSULE_HIDE_MS`（360）与 [HEADER_TITLE_BLUR_MS]（350）。
     */
    object Duration {
        /**
         * 90ms — 轻量浮层**淡出**（`UfiPopupMenu` 关闭）。
         *
         * 刻意比同一浮层的淡入（[Swift] 160）快近一倍：出场让人等 = 迟滞感。
         * 不吸附到 [Micro] 120（差 30ms 超出 20ms 容差，且 120 是"按压"语义）。
         */
        const val Flick = 90
        /** 120ms — 按压 / chip 选中等微反馈，短到不该被"看见"；降低动效下的瞬时淡入也用它 */
        const val Micro = 120
        /** 160ms — 图表 tooltip、hover 态等轻量跟手反馈；轻量浮层淡入 */
        const val Swift = 160
        /** 180ms — 列表项、开关、次级面板展开 */
        const val Quick = 180
        /** 200ms — 默认淡入淡出（全站最常用档位） */
        const val Base = 200
        /** 220ms — 输入栏 / 胶囊导航 / 标签切换 */
        const val Standard = 220
        /**
         * 250ms — 通用轻量入场 / 离场：列表气泡入场、弹窗卡片退出、页面级 fade 离场、指针跟随。
         *
         * 位于 [Standard] 220 与 [Gentle] 280 之间，语义是"比进场快半拍但不仓促"
         * （原 `UfiDialogAnim.ExitDuration = 240` 的设计意图，收编时并入本档）。
         */
        const val Fluid = 250
        /** 280ms — 页面内区块切换、导航层级转场；文字模糊转场的"起糊"相位 */
        const val Gentle = 280
        /** 300ms — 数据刷新后的内容淡入 */
        const val Smooth = 300
        /**
         * 320ms — **Tab 页转场的默认时长**（[com.ufi_axis.ui.animation.page.UfiPageTransition.spec]
         * 与宿主 fallback 共用，见 `UfiPageTransitionDefaultSpec`）、浮动分页条入场、
         * 文字模糊转场的"变清晰"相位。
         *
         * 不吸附到 [Smooth] 300：这是页面转场策略的语义锚点，保留原值以保证零观感回归。
         *
         * ⚠ 它是**默认值 / fallback**，不是"全站转场都用 320"：用户在「外观」页设置了转场时长时，
         * Tab 切页走 `ThemeManager.transitionDurationMs`，`UfiPageTransition.spec` 被调用点覆盖。
         * 2026-09-04 起**二级页转场也不再用本档** —— 它此前写死在 `navigation/Navigation.kt`，
         * 导致同一个"转场时长"滑块只管 Tab 不管二级页；现在两条链路都读用户设置
         * （归一化入口 `ufiNavTransitionDurationMs`）。
         */
        const val Sweeping = 320
        /** 400ms — 首屏 / 强调型入场 */
        const val Deliberate = 400
        /**
         * 420ms — 强调型入场，比 [Deliberate] 慢半拍：胶囊导航浮出、测速阶段切换。
         *
         * 收编前这个值在 `MainNavGraph` 与 `SpeedTestScreen.PHASE_SWITCH_MS` 各写了一遍。
         */
        const val Emphatic = 420
        /** 600ms — 主题切换圆形揭示、骨架屏首帧、图表绘入、测速指针归零 */
        const val Reveal = 600
        /** 800ms — 环形进度等"慢而稳"的自绘动画 */
        const val Languid = 800
        /** 1000ms — 无限旋转**一圈**的周期（`UfiLoadingIndicator`） */
        const val Orbit = 1000
        /** 1200ms — 无限循环 / 呼吸类环境动效 */
        const val Ambient = 1200
        /** 1500ms — 扩散脉冲一轮的周期（配对设备时的扫描波纹），全站最慢档 */
        const val Pulse = 1500
    }

    /**
     * 按压缩放梯度（[androidx.compose.ui.graphics.graphicsLayer] 的 scaleX/scaleY 目标值）。
     *
     * 2026-09-04（P2d 按压反馈收口）：全库 16 处 `if (isPressed) 0.9xf` 的裸字面量收敛到这里。
     *
     * ## 唯一规则：**元素面积越小，缩得越多**
     * 同一面积量级的元素必须落在同一档，跨档只允许因为"面积量级不同"。
     * 按面积从小到大：[Fab] → [Cell] → [Button] → [Chip]。
     *
     * 配套的 spec 也按档固定，不要自由组合：
     * - [Fab] → `controlPop()`（带回弹，浮起元素才配得上过冲）
     * - [Button] → `buttonPress()`（无回弹、紧实）
     * - [Cell] / [Chip] → `tween(Duration.Micro)`（密排元素回弹会互相"打架"）
     *
     * ## 怎么播（2026-09-04 P2f）
     * **本对象只定义"缩多少"，不负责"怎么播"。**播放一律走
     * [com.ufi_axis.ui.animation.ufiPressScale] / `rememberUfiPressScale`
     * （`ui/animation/PressFeedback.kt`），它保证短按也有可见幅度。
     *
     * ⚠ 不要退回 `animateFloatAsState(if (isPressed) PressScale.X else 1f, spec)` 的写法：
     * 那是"跟随目标值"的动画，抬手瞬间目标翻回 1f 就被就地拉回，而本梯度四档的落差只有
     * 0.03~0.08，短按一两帧走出的位移不足一个像素 —— 用户实测反馈过"按压动画只有长按才生效"。
     * 详细成因与编排见 `PressFeedback.kt` 文件头。
     */
    object PressScale {
        /** 0.92 — FAB：全站唯一的浮起圆钮，面积最小、需要最强反馈 */
        const val Fab = 0.92f
        /** 0.94 — 密排网格小格（排程选择器的 7 列日期格），格子小、缩小才看得出来 */
        const val Cell = 0.94f
        /** 0.96 — 按钮族（5 个 Ufi 按钮 + 页面内的 OutlinedButton 图标按钮） */
        const val Button = 0.96f
        /**
         * 0.97 — chip / 选项格 / 可点列表行：面积最大的一类可点区。
         *
         * 收编前监控页三个筛选 chip 各自写 0.94、而公共 `CategoryChip` / 弹窗选项格写 0.97 ——
         * 同一种胶囊筛选器在不同页面缩不一样多，正是"同类元素动画不一致"的典型。
         * 统一取公共组件那一档（0.97）。
         */
        const val Chip = 0.97f
    }

    /**
     * 二级页转场「离场页后退」的幅度档位（`com.ufi_axis.ui.navigation` 的 detail 转场专用）。
     *
     * 2026-09-04：二级页转场原来只有水平平移，单一图层在动 = 没有层次感。现在叠加
     * 「离场页退到后面去」的两件套（缩放 + 遮罩），本对象只定义**幅度**，播放进度与时长由
     * [com.ufi_axis.ui.navigation.UfiNavRecedeProfile] / `ufiNavTransitionDurationMs`
     * 唯一给出（跟随 [ThemeManager] 的用户设置 150..600，`0` = 关闭）。
     *
     * ## ⚠ 只作用于「离场页」，进场页一律不缩放
     * 同日二次修订删掉了原来的 `CardScaleFrom`（进场页 0.94 → 1 的「卡片浮起」）。
     * 原因不是幅度不合适，而是这个 App 的 chrome（状态栏色带 / 底部胶囊窗口）**不在
     * 转场动画容器里**：进场页只要 `scale < 1`，四周立刻露出那些静止的 chrome，
     * 观感是"顶部和底部各有一块不动的区域"。进场页必须满屏铺满，深度感全部交给下层。
     * 详细论证见 `navigation/Navigation.kt` 文件头的铁律 A / B。
     *
     * ## 取值理由
     * 幅度刻意**很小**：整屏元素的缩放感知强度与面积成正比，同样 4% 的缩放放在
     * 一个按钮上几乎看不见、放在整屏上已经是"明显推远"。参照 [PressScale] 的同一条规律
     * （面积越大缩得越少），整屏这一量级只能落在 0.94~0.97 区间。
     */
    object NavRecede {
        /**
         * 0.96 — 离场页「退到后面去」的缩放终点。
         *
         * 只退 4%：离场页在下层且被进场页逐步遮住，退太多会在进场页尚未覆盖的那一侧
         * 露出页面外的容器底色（真机上就是边缘一条缝）。
         */
        const val ScaleTo = 0.96f

        /**
         * 0.12 — 离场页遮罩（`palette.scrim`）的**峰值**不透明度。
         *
         * scrim 色本身是弹窗遮罩强度（黑 35% / 50%），整屏转场只需要"压暗一档"表达层级，
         * 直接用原强度会让返回过程中下层黑成一片。0.12 是仍能看出压暗、又不至于抢戏的下限。
         */
        const val ScrimAlpha = 0.12f
    }

    /** 缓动曲线集合：业务不要直接 import Compose 的 easing 常量。 */
    object Easing {
        /** 标准缓动：绝大多数进出场 */
        val Standard: androidx.compose.animation.core.Easing = FastOutSlowInEasing
        /** 匀速：无限循环 / 进度类 */
        val Linear: androidx.compose.animation.core.Easing = LinearEasing
        /** 加速离场：弹窗退出等"消失得更快"的场景 */
        val Accelerate: androidx.compose.animation.core.Easing = FastOutLinearInEasing
        /** 强调减速（M3 emphasized decelerate）：入场 */
        val EmphasizedIn: androidx.compose.animation.core.Easing = UfiAnimSpecs.emphasizedInEasing
        /** 强调加速（M3 emphasized accelerate）：离场 */
        val EmphasizedOut: androidx.compose.animation.core.Easing = UfiAnimSpecs.emphasizedOutEasing
        /** 轻微过冲：Toast 落下 */
        val Overshoot: androidx.compose.animation.core.Easing = UfiAnimSpecs.toastDropEasing
    }

    /** Standard entrance animation */
    val standard: AnimationSpec<Float>
        get() = tween(Duration.Deliberate, easing = Easing.Standard)

    /** Slow, gentle animation for backgrounds and subtle elements */
    val slow: AnimationSpec<Float>
        get() = tween(Duration.Languid, easing = Easing.Standard)

    // ===== 弹性（spring）语义入口：全部转发 UfiAnimSpecs，数值只存在于那一层 =====
    // 泛型是必需的：调用点既有 animateFloatAsState 也有 animateColorAsState / animateDpAsState。

    /** 通用点按缩放反馈 */
    fun <T> press(): SpringSpec<T> = UfiAnimSpecs.clickScale()

    /** 按钮按压（无回弹，紧实） */
    fun <T> buttonPress(): SpringSpec<T> = UfiAnimSpecs.buttonPress()

    /** 小控件弹出（勾选框 / FAB） */
    fun <T> controlPop(): SpringSpec<T> = UfiAnimSpecs.controlPop()

    /** 发送按钮弹出 */
    fun <T> sendPop(): SpringSpec<T> = UfiAnimSpecs.sendPop()

    /** 弹窗入场 */
    fun <T> dialog(): SpringSpec<T> = UfiAnimSpecs.dialogEnter()

    /** 列表交错入场 */
    fun <T> stagger(): SpringSpec<T> = UfiAnimSpecs.staggerEnter()

    /** Toast 落下 */
    fun <T> toast(): SpringSpec<T> = UfiAnimSpecs.toastDrop()

    /** 面板入场缩放 */
    fun <T> panelEnter(): SpringSpec<T> = UfiAnimSpecs.panelEnter()

    /** 标签选中弹跳 */
    fun <T> tagPop(): SpringSpec<T> = UfiAnimSpecs.tagPop()

    /** 提示气泡弹出 */
    fun <T> tooltipPop(): SpringSpec<T> = UfiAnimSpecs.tooltipPop()

    /** 指示条滑动 */
    fun <T> tabSlider(): SpringSpec<T> = UfiAnimSpecs.tabSlider()

    /** 翻页脉冲 */
    fun <T> pagePulse(): SpringSpec<T> = UfiAnimSpecs.pagePulse()

    /** 开关拇指位移 */
    fun <T> switchThumb(): SpringSpec<T> = UfiAnimSpecs.switchThumb()

    /** 颜色沉降（无回弹） */
    fun <T> colorSettle(): SpringSpec<T> = UfiAnimSpecs.colorSettle()

    /** 颜色快切 */
    fun <T> colorSwap(): SpringSpec<T> = UfiAnimSpecs.colorSwap()

    /** 滑块轨道跟随 */
    fun <T> sliderTrack(): SpringSpec<T> = UfiAnimSpecs.sliderTrack()

    /**
     * 标题模糊入场时长（`UfiHeader` 的标题 `RenderEffect` 模糊 8→0），毫秒。
     *
     * 2026-09-04（P2a）：本常量原名 `PAGE_TRANSITION_MS`，**名不符实** —— 它唯一的使用点
     * 是 `UfiHeader` 的标题模糊，与页面转场毫无关系；页面转场的默认时长在
     * `UfiPageTransitionDefaultSpec`（[Duration.Sweeping] 320）。改名的直接动机是：
     * 有人想调转场时长时会先搜到这个名字，改完发现转场没变、标题却变了。
     *
     * 350ms 是**有意例外**，不在 [Duration] 梯度上：它比页面转场（320）刻意长半拍，
     * 让"标题最后一个变清晰"，形成头部收尾的层次感。吸附到 320 差 30ms 超出容差，
     * 吸附到 [Duration.Deliberate] 400 会明显拖沓，故保留原值并在此固化为唯一来源。
     */
    const val HEADER_TITLE_BLUR_MS = 350

    /**
     * 列表项交错入场的**逐项延迟**（毫秒）。
     *
     * 2026-09-04（P2a）：原来这里是 `50L`、而真正在跑的 `Modifier.staggeredEntrance`
     * 写的是 `index * 35L` —— 同一个概念两个值，且 `50L` 没有任何调用点（纯死常量）。
     * 统一取 **35L**：这是当时真实在跑的值，改成 50 会让第 10 项的延迟从 350ms 涨到 500ms，
     * 长列表能肉眼看出"入场变慢"。以"不制造观感变化"为准则，向实际行为对齐而不是向死常量对齐。
     *
     * 2026-09-07：`Modifier.staggeredEntrance` 本身也因全库零调用点被删除（见
     * `animation/Entrance.kt` 的文件头）。本常量**暂留**为交错入场的唯一时间口径 ——
     * 重新实现时直接引用它，不要再写裸 35。
     */
    const val STAGGER_DELAY_MS = 35L

    /** Content fade duration for data updates */
    const val DATA_FADE_MS = Duration.Smooth
}
