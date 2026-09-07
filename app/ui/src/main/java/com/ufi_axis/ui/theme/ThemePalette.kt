package com.ufi_axis.ui.theme

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

/**
 * Complete theme palette with light/dark variants for each color slot.
 * Mirrors UFITOOLSWidget's ThemeColors.Palette design.
 */
data class ThemePalette(
    val id: String,
    val name: String,
    // Accent (primary brand color)
    val accentLight: Color,
    val accentDark: Color,
    // Accent secondary (lighter variant for badges, chips)
    val accentSecondaryLight: Color,
    val accentSecondaryDark: Color,
    // Page background
    val pageBgLight: Color,
    val pageBgDark: Color,
    // Card surface
    val cardBgLight: Color,
    val cardBgDark: Color,
    // Primary text
    val textPrimaryLight: Color,
    val textPrimaryDark: Color,
    // Secondary text
    val textSecondaryLight: Color,
    val textSecondaryDark: Color,
    // Divider
    val dividerLight: Color,
    val dividerDark: Color,
    // Icon tint（仍在用：UfiInput 的 leading/trailing 图标、UfiDropdown 的箭头）
    val iconTintLight: Color,
    val iconTintDark: Color,

    // ── 语义色槽（2026-09-03 P1a 新增）────────────────────────────────────────
    // 这些色以前是 ResolvedPalette 里写死字面值的派生 getter，换配色时不会跟着变
    // （最要命的是 onAccent 恒为白：新主题用浅色 accent 时按钮文字直接看不见）。
    // 现在提升为可按主题配置的成对色槽，并且**默认值就取原来写死的那个值**，
    // 所以 6 个预设不传任何新参数也能编译、观感零变化。
    // 后续换配色方案时：在 ThemePresets.kt 里逐个预设覆盖需要的槽即可，不用再改本文件。
    /** 强调色之上的文字/图标色（accent 底的按钮、徽标）。默认白色 = 原写死值。 */
    val onAccentLight: Color = Color.White,
    val onAccentDark: Color = Color.White,
    /** 错误色。默认值 = 原 `error` getter 的写死值。 */
    val errorLight: Color = Color(0xFFE53935),
    val errorDark: Color = Color(0xFFFF6B6B),
    /** 错误浅底容器。默认值 = 原 `errorContainer` getter 的写死值。 */
    val errorContainerLight: Color = Color(0xFFFFEBEE),
    val errorContainerDark: Color = Color(0xFF3D1515),
    /** 警告色（`warningContainer` / `metricWarning` 都由它派生）。默认值 = 原写死值。 */
    val warningLight: Color = Color(0xFFFF9800),
    val warningDark: Color = Color(0xFFFFB74D),
    /** 成功色。默认值 = 原写死值。 */
    val successLight: Color = Color(0xFF43A047),
    val successDark: Color = Color(0xFF66BB6A),
    /**
     * 指标卡「正常」态数值色。默认值 = 原写死值（0xFF1C1C1E / 0xFFF2F2F7）。
     * 注意它与 textPrimary 只差一点点却是独立取值——保留成独立槽而不是直接读 textPrimary，
     * 是为了不改观感；想让它跟随正文色，在预设里填成与 textPrimary 相同的值即可。
     */
    val metricNormalLight: Color = Color(0xFF1C1C1E),
    val metricNormalDark: Color = Color(0xFFF2F2F7),
    /** 弹窗/抽屉遮罩。默认值 = 原写死值（黑 35% / 黑 50%）。 */
    val scrimLight: Color = Color.Black.copy(alpha = 0.35f),
    val scrimDark: Color = Color.Black.copy(alpha = 0.5f),

    // ── 语义色槽（2026-09-03 P1c 追加）──────────────────────────────────────────
    /**
     * 错误色之上的文字/图标色（error 实底的危险按钮、error 底徽标）。默认白色 = 原写死值。
     *
     * 为什么不复用 [onAccentLight]：危险按钮的底色是 [errorLight]，与 accent 无关。
     * 换配色时若某个主题把 accent 调成浅色、需要把 onAccent 改成深字，
     * 危险按钮却仍是深红实底、必须保持白字——两者必须能各自独立取值。
     */
    val onErrorLight: Color = Color.White,
    val onErrorDark: Color = Color.White,
    /**
     * 开关「关闭」态的轨道色。默认值 = 原写死值（浅色 0xFFE0E0E0 / 深色白 15%）。
     *
     * 这是改造前**唯一的不透明写死字面色**：浅色态 0xFFE0E0E0 是带暖底的中性灰，
     * 冷调（青/蓝）主题下一排关闭的开关会整体偏暖，看着像另一套 UI 混进来了。
     * 提升为色槽后，冷调主题可在预设里覆盖成偏冷的灰。
     */
    val switchTrackOffLight: Color = Color(0xFFE0E0E0),
    val switchTrackOffDark: Color = Color.White.copy(alpha = 0.15f),
    /** 开关「关闭」态的滑块色。默认值 = 原写死值（浅色 0xFFFAFAFA / 深色白 50%），偏暖问题同 [switchTrackOffLight]。 */
    val switchThumbOffLight: Color = Color(0xFFFAFAFA),
    val switchThumbOffDark: Color = Color.White.copy(alpha = 0.5f),

    // ── 语义色槽（2026-09-04 P2-中 追加：渐变 Hero 卡专用）──────────────────────
    // 首页连接卡 / 网络 Hero / 流量 Hero / MonitorOverview 事件汇总卡都是
    // 「accent 系渐变实底 + 一整套纯白内容」，白色以前是**四个文件里各写各的 Color.White**
    // （约 40 处）。渐变本身来自 accent，换配色时底色会跟着变，但白色内容不会——
    // 浅色 accent（如亮黄、浅青）主题下白字直接糊在底上看不见，这就是 G3 说的"换不干净"。
    // 提升为色槽后整套内容有唯一来源：某个主题把渐变调浅，只需覆盖这两个槽为深色。
    /**
     * 渐变卡上的**主前景**色（标题、数值、图标、进度条已填充段）。默认白色 = 原写死值。
     *
     * 为什么不复用 [onAccentLight]：onAccent 的底是 accent **纯色**（按钮、徽标），
     * 而这里的底是 accent 明暗三段渐变（浅色主题还会往白色方向提亮 22%），
     * 两者亮度不同档、需要各自独立取值——把渐变卡调浅时只该动本槽，不该连按钮文字一起改。
     */
    val onGradientLight: Color = Color.White,
    val onGradientDark: Color = Color.White,
    /**
     * 渐变卡上的**弱化前景/装饰**基色（次要说明文字、1px 高光描边、半透明胶囊底、
     * 竖分隔线、信号条未激活段）。默认白色 = 原写死值。
     *
     * 只定义"往哪个方向弱化"，**不定义弱化程度**：调用点各自 `.copy(alpha = …)`，
     * 因为这 20 多处的档位从 12% 排到 90%（描边 18%、胶囊底 20%、副文案 70%…），
     * 收进 token 就必然改观感。覆盖本槽时只需给 RGB，alpha 会被调用点覆盖掉。
     */
    val gradientMutedLight: Color = Color.White,
    val gradientMutedDark: Color = Color.White
) {
    /**
     * Resolve to a flat set of colors based on current dark/light mode.
     */
    fun resolve(isDark: Boolean): ResolvedPalette = ResolvedPalette(
        accent = if (isDark) accentDark else accentLight,
        accentSecondary = if (isDark) accentSecondaryDark else accentSecondaryLight,
        pageBg = if (isDark) pageBgDark else pageBgLight,
        cardBg = if (isDark) cardBgDark else cardBgLight,
        textPrimary = if (isDark) textPrimaryDark else textPrimaryLight,
        textSecondary = if (isDark) textSecondaryDark else textSecondaryLight,
        divider = if (isDark) dividerDark else dividerLight,
        iconTint = if (isDark) iconTintDark else iconTintLight,
        // 语义色槽：与上面的老色槽同样按明暗二选一，取值来源统一在 ThemePalette
        onAccent = if (isDark) onAccentDark else onAccentLight,
        error = if (isDark) errorDark else errorLight,
        errorContainer = if (isDark) errorContainerDark else errorContainerLight,
        warning = if (isDark) warningDark else warningLight,
        success = if (isDark) successDark else successLight,
        metricNormal = if (isDark) metricNormalDark else metricNormalLight,
        scrim = if (isDark) scrimDark else scrimLight,
        // P1c 追加的三组槽，选法与上面完全一致
        onError = if (isDark) onErrorDark else onErrorLight,
        switchTrackOff = if (isDark) switchTrackOffDark else switchTrackOffLight,
        switchThumbOff = if (isDark) switchThumbOffDark else switchThumbOffLight,
        // P2-中 追加的两组渐变卡槽，选法与上面完全一致
        onGradient = if (isDark) onGradientDark else onGradientLight,
        gradientMuted = if (isDark) gradientMutedDark else gradientMutedLight,
        isDark = isDark
    )
}

/**
 * Resolved (flat) palette for the current dark/light mode.
 * Components access colors directly from this class.
 */
data class ResolvedPalette(
    val accent: Color,
    val accentSecondary: Color,
    val pageBg: Color,
    val cardBg: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val divider: Color,
    val iconTint: Color,
    // ── 语义色（2026-09-03 P1a：由派生 getter 改为存储字段）─────────────────────
    // 刻意不给默认值：加字段时所有构造点（resolve() 与测试）会编译失败，
    // 这正是换配色时防止漏改的护栏，比默认值静默兜底更有价值。
    val onAccent: Color,
    val error: Color,
    val errorContainer: Color,
    val warning: Color,
    val success: Color,
    val metricNormal: Color,
    val scrim: Color,
    // ── 2026-09-03 P1c：同样由写死 getter 改为存储字段（理由同上）─────────────────
    val onError: Color,
    val switchTrackOff: Color,
    val switchThumbOff: Color,
    // ── 2026-09-04 P2-中：渐变 Hero 卡上的整套前景色（原先散在 4 个页面文件里写死 Color.White）
    val onGradient: Color,
    val gradientMuted: Color,
    val isDark: Boolean
) {
    /** Card border color: subtle alpha-based border */
    val cardBorder: Color
        get() = if (isDark) Color.White.copy(alpha = 0.08f) else Color.Black.copy(alpha = 0.06f)

    /** Dialog border color: 更明显的描边（参考 UFITOOLS-Widget 的 2dp 强边框，浅色~18% / 深色~30%） */
    val dialogBorder: Color
        get() = if (isDark) Color.White.copy(alpha = 0.30f) else Color.Black.copy(alpha = 0.18f)

    /** Toast border color */
    val toastBorder: Color
        get() = if (isDark) Color.White.copy(alpha = 0.10f) else Color.Black.copy(alpha = 0.08f)

    // switchTrackOff / switchThumbOff（2026-09-03 P1c）已提升为构造字段（见上），
    // 原 `if (isDark) Color.White.copy(...) else Color(0xFFE0E0E0/0xFFFAFAFA)` 写死实现删除。
    // 浅色态那两个不透明灰是全项目最后残留的写死不透明字面色，冷调主题下会整体偏暖。

    /** Switch track checked color (accent at 30% alpha) */
    val switchTrackOn: Color
        get() = accent.copy(alpha = 0.3f)

    /** Switch thumb checked color */
    val switchThumbOn: Color
        get() = accent

    // onAccent（accent 底上的文字/图标色）已提升为构造字段（见上），
    // 原来的 `get() = Color.White` 写死实现删除。

    /**
     * 强调色「深一档」：hover / pressed 等需要比常态更实的状态。
     *
     * 2026-09-03（P1b）：以前各处按压态各自 `accent.copy(alpha = …)`，
     * 半透明会透出底色，同一按钮放在卡上/页面上按下去颜色还不一样。这里给出唯一实现。
     *
     * 方向按明暗反转——浅色主题底色亮，要往下压才显得"更重"；
     * 深色主题底色暗，压深会糊进背景，必须提亮才看得出按压反馈。
     * 与黑色混合在 sRGB 下等价于 HSV 的 V × 0.84（色相、饱和度不变）；
     * 提亮方向没有等价的纯 V 变换（饱和 accent 的 V 往往已是 1.0，无法再乘大），
     * 故用与白色混合，代价是饱和度略降，观感上正是"高光"。
     */
    val accentStrong: Color
        get() = srgbMix(accent, if (isDark) Color.White else Color.Black, ACCENT_STEP)

    /**
     * 强调色「弱化档」：disabled 态。
     *
     * 2026-09-03（P1b）：往当前卡面色 [cardBg] 混 60%——保留色相让人认得出"这本该是主色按钮"，
     * 明度贴近承载它的表面，于是自动"退到背景里"。
     * 不用 `alpha` 实现是因为半透明档在不同底色上观感不一致（同 [accentStrong] 的理由）。
     */
    val accentMuted: Color
        get() = srgbMix(accent, cardBg, ACCENT_MUTE_RATIO)

    /** Warning container: subtle warning tint over card surface */
    val warningContainer: Color
        get() = lerp(cardBg, warning, if (isDark) 0.12f else 0.08f)

    /** Input field border (unfocused) */
    val inputBorder: Color
        get() = if (isDark) Color.White.copy(alpha = 0.12f) else Color.Black.copy(alpha = 0.12f)

    /** Input field border (focused) */
    val inputBorderFocused: Color
        get() = accent

    /** Chip selected background */
    val chipSelectedBg: Color
        get() = accent.copy(alpha = 0.12f)

    /**
     * 主色浅底容器（选中行、被强调的卡片底色）。
     *
     * 2026-09-01：为收敛 `MaterialTheme.colorScheme.primaryContainer` / `tertiaryContainer` 而加。
     * 取值刻意沿用 `buildColorSchemeFromPalette` 里 primaryContainer 的原值（亮 10% / 暗 20%），
     * 所以替换 colorScheme 写法时观感不变；`tertiaryContainer`（原 8% / 15%）也并到这里，
     * 那处差异肉眼不可见，换来"主色浅底只有一个来源"。
     *
     * 与 [chipSelectedBg]（12%）的分工：chip 是小色块要更跳一点，容器是大面积底色。
     */
    val accentContainer: Color
        get() = accent.copy(alpha = if (isDark) 0.2f else 0.1f)

    /**
     * 次级表面：比 [cardBg] 更"退后"的底色，用于卡内分区、进度槽、置灰行。
     *
     * 2026-09-01：为收敛 `MaterialTheme.colorScheme.surfaceVariant` 而加。取值与
     * `buildColorSchemeFromPalette` 完全一致（亮色 = [pageBg]，暗色 = [cardBg] 70%）——
     * 这是唯一一个亮暗取自不同 token 的角色，直接写 `pageBg` 会改暗色观感，故单独立 token。
     */
    val surfaceMuted: Color
        get() = if (isDark) cardBg.copy(alpha = 0.7f) else pageBg

    /** Chip unselected border */
    val chipUnselectedBorder: Color
        get() = if (isDark) Color.White.copy(alpha = 0.12f) else Color.Black.copy(alpha = 0.12f)

    /** Bottom nav indicator */
    val navIndicator: Color
        get() = accent.copy(alpha = 0.15f)

    /** Metric value colors (semantic, for dashboard metric cards) */
    // metricNormal 已提升为构造字段（见上）；warning/critical 继续从语义色派生，避免同一概念两处配。
    val metricWarning: Color
        get() = warning
    val metricCritical: Color
        get() = error

    // scrim 已提升为构造字段（见上），原 `Color.Black.copy(alpha = …)` 写死实现删除。

    /** 极光渐变（缎带 / 主按钮 / FAB / Hero 微光）。固定品牌签名：青→靛→紫，深浅共用亮色停止点。 */
    val auroraGradient: Brush
        get() = Brush.horizontalGradient(
            colors = listOf(Color(0xFF34E7E4), Color(0xFF5B8CFF), Color(0xFFB14CFF))
        )

    /** 极光渐变之上的文字/图标色（深色字保证对比度，深浅主题均适用）。 */
    val auroraOn: Color
        get() = Color(0xFF04121A)

    /** 主题渐变（由当前主题的 accent + accentSecondary 派生）。跟随主题色变换，用于 Hero / 大色块等需要主题色但不锁定具体色的位置。 */
    val themeGradient: Brush
        get() = Brush.horizontalGradient(
            colors = listOf(
                accent,
                lerp(accent, accentSecondary, 0.5f),
                accentSecondary
            )
        )

    /** 极光柔光（Hero 微光填充等），半透明品牌色。 */
    val auroraSoft: Brush
        get() = Brush.horizontalGradient(
            colors = listOf(
                Color(0xFF34E7E4).copy(alpha = 0.18f),
                Color(0xFF5B8CFF).copy(alpha = 0.18f),
                Color(0xFFB14CFF).copy(alpha = 0.18f)
            )
        )
}

/** [ResolvedPalette.accentStrong] 的混合比例：一档 ≈ 16%，明显但不至于变成另一个颜色。 */
private const val ACCENT_STEP = 0.16f

/** [ResolvedPalette.accentMuted] 的混合比例：往表面色混 60%，保留色相但退到低强调层级。 */
private const val ACCENT_MUTE_RATIO = 0.60f

/**
 * sRGB 分量线性混合（保持 [from] 的 alpha）。
 *
 * 2026-09-03：这里刻意不用 `androidx.compose.ui.graphics.lerp(Color, Color, Float)`——
 * 它在 Oklab 空间插值，结果无法由十六进制值直接推算，评审和回归对色时不好核对；
 * 而 accentStrong / accentMuted 要的只是"同色相深/浅一档"，sRGB 分量混合已足够，
 * 且与黑色混合恰好等价于 HSV 的 V 缩放（H、S 不变），行为可预测。
 * 仍保留 `warningContainer` / `themeGradient` 原有的 `lerp`，避免改动它们的观感。
 */
private fun srgbMix(from: Color, to: Color, ratio: Float): Color = Color(
    red = from.red + (to.red - from.red) * ratio,
    green = from.green + (to.green - from.green) * ratio,
    blue = from.blue + (to.blue - from.blue) * ratio,
    alpha = from.alpha
)
