package com.ufi_axis.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density

/**
 * Derive a Material 3 color scheme from a ResolvedPalette.
 * This ensures M3 components (that use MaterialTheme.colorScheme) still work
 * with the multi-theme system.
 *
 * 2026-09-03 由 private 放宽为 internal：组件画廊的明暗预览必须复用同一套映射，否则预览里
 * 只换 palette 不换 colorScheme，凡是读 `MaterialTheme.colorScheme` 的 M3 组件都会串色。
 *
 * 2026-09-04（P3）本层不再出现任何颜色字面量。桥接层是**裸 M3 组件**唯一的取色来源，
 * 它一旦写死值，换配色时这些组件就集体掉队——而且比组件内残留的写死白更难发现，
 * 因为页面代码里根本搜不到。改完后本文件已不再需要 `import Color`，这就是"没有第二来源"的体检信号。
 * 逐项取舍见函数体开头的注释块。
 */
internal fun buildColorSchemeFromPalette(resolved: ResolvedPalette): ColorScheme {
    // ── 2026-09-04（P3）桥接层去写死值：取舍集中记在这里，亮/暗两个分支各自只留一行短注释，
    //    避免同一段理由抄两遍。
    //
    // onPrimary / onSecondary / onTertiary ← resolved.onAccent（原：亮暗两套都写死 Color.White）
    //   primary / secondary / tertiary 三个角色全部来自 accent 家族，而"accent 底上的文字/图标色"
    //   在 P1a 已经有专门色槽 onAccent（默认值就是白，所以接上去观感零变化）。
    //   写死白的真实后果：浅色 accent 皮肤下裸 M3 组件白字白底——本仓已有 3 处裸
    //   `Button(buttonColors(containerColor = palette.accent))`（TaskScreen ×2、PairingConfigScreen ×1）
    //   的 contentColor 就落在 onPrimary 上。
    //   隐患留档：onSecondary / onTertiary 承载的 accentSecondary 在多数预设的**浅色态是更浅的淡色**
    //   （default #E5E5E5、mint #90E4C3、orange #FFB989），它并不是"与 accent 同明度的同色系派生"，
    //   白字在其上本就不可读。今天没有裸 M3 组件消费这两个角色（FilledTonalButton 走的是
    //   secondaryContainer / onSecondaryContainer），所以接 onAccent 是"至少可按主题配置"的正确方向；
    //   真出现 accentSecondary 实底承载文字的场景，应在 ThemePalette 里新增 onAccentSecondary 槽，
    //   而不是回头在本层写死。
    //
    // onError ← resolved.onError（原：亮暗两套都写死 Color.White）
    //   P1c 已为它单独立槽，理由见 ThemePalette.onErrorLight：error 是与 accent 无关的独立底色，
    //   不能跟着 onAccent 走，必须能各自取值。
    //
    // outline ← resolved.inputBorder（原：resolved.textSecondary）
    //   M3 的 outline 语义是"可交互元素的描边"，实际消费者是 OutlinedButton 的默认 border 与
    //   OutlinedTextField 的 unfocusedBorderColor。项目里这两者的自研对应物用的正是 inputBorder
    //   （UfiInput / UfiDropdown / UfiStandardDialogs 的 unfocusedBorderColor）与同为 12% 的
    //   chipUnselectedBorder。原来接 textSecondary 是**中灰实色**，比自研描边重一个数量级，
    //   于是同一屏里出现两种描边强度——"一个界面出现多种 UI"的一种形态。
    //   不接更弱的 cardBorder(6~8%)：那是卡片外框的量级，压在可点控件上会几乎看不见，
    //   outline 要的是"能看出这里可交互"，12% 是本仓交互描边的既有档位。
    //
    // outlineVariant 保持 resolved.divider（本次不动）
    //   M3 的 outlineVariant 是弱分隔线，与项目的 divider 槽一一对应，本就没接错。
    //   各调用点再叠 alpha（UfiDivider 的 0.08）属于局部微调，不该固化进桥接层。
    //   且全仓 12 处 HorizontalDivider 都显式传了 color，本角色目前零消费——改它收益为零、风险非零。
    //
    // secondaryContainer ← resolved.accentContainer（原：resolved.accent.copy(alpha = 0.15f/0.08f)）
    //   accentContainer 就是 2026-09-01 为收敛 primaryContainer / tertiaryContainer 而立的
    //   "主色浅底唯一来源"，当时已把 tertiaryContainer 的 8%/15% 并入；secondaryContainer 是漏掉的
    //   第三处，留着等于桥接层自带一套 alpha。观感有极小变化：亮 8%→10%、暗 15%→20%，
    //   唯一消费者是 SmsScreen 那个裸 FilledTonalButton（"开启功能"）。
    return if (resolved.isDark) {
        darkColorScheme(
            primary = resolved.accent,
            onPrimary = resolved.onAccent,              // P3：原 Color.White
            primaryContainer = resolved.accentContainer,
            onPrimaryContainer = resolved.textPrimary,
            secondary = resolved.accentSecondary,
            onSecondary = resolved.onAccent,            // P3：原 Color.White
            secondaryContainer = resolved.accentContainer, // P3：原 accent.copy(alpha = 0.15f)
            onSecondaryContainer = resolved.textPrimary,
            tertiary = resolved.accentSecondary,
            onTertiary = resolved.onAccent,             // P3：原 Color.White
            tertiaryContainer = resolved.accentContainer,
            onTertiaryContainer = resolved.textPrimary,
            error = resolved.error,
            onError = resolved.onError,                 // P3：原 Color.White
            errorContainer = resolved.errorContainer,
            onErrorContainer = resolved.error,
            background = resolved.pageBg,
            onBackground = resolved.textPrimary,
            surface = resolved.cardBg,
            onSurface = resolved.textPrimary,
            surfaceVariant = resolved.surfaceMuted,
            onSurfaceVariant = resolved.textSecondary,
            outline = resolved.inputBorder,             // P3：原 textSecondary（中灰实色，描边过重）
            outlineVariant = resolved.divider,
        )
    } else {
        lightColorScheme(
            primary = resolved.accent,
            onPrimary = resolved.onAccent,              // P3：原 Color.White
            primaryContainer = resolved.accentContainer,
            onPrimaryContainer = resolved.textPrimary,
            secondary = resolved.accentSecondary,
            onSecondary = resolved.onAccent,            // P3：原 Color.White
            secondaryContainer = resolved.accentContainer, // P3：原 accent.copy(alpha = 0.08f)
            onSecondaryContainer = resolved.textPrimary,
            tertiary = resolved.accentSecondary,
            onTertiary = resolved.onAccent,             // P3：原 Color.White
            tertiaryContainer = resolved.accentContainer,
            onTertiaryContainer = resolved.textPrimary,
            error = resolved.error,
            onError = resolved.onError,                 // P3：原 Color.White
            errorContainer = resolved.errorContainer,
            onErrorContainer = resolved.error,
            background = resolved.pageBg,
            onBackground = resolved.textPrimary,
            surface = resolved.cardBg,
            onSurface = resolved.textPrimary,
            surfaceVariant = resolved.surfaceMuted,
            onSurfaceVariant = resolved.textSecondary,
            outline = resolved.inputBorder,             // P3：原 textSecondary（中灰实色，描边过重）
            outlineVariant = resolved.divider,
        )
    }
}

/**
 * 全局主题。
 *
 * [uiScalePercent] 是**全局 UI 缩放**（百分比，默认 [ThemeManager.DEFAULT_UI_SCALE_PERCENT]）：
 * 通过覆盖 `LocalDensity` 一处生效，所有 dp 与 sp 同时等比缩放。
 *
 * 为什么改 density 而不是改 Spacing / Type 常量：全仓约 1500 处 dp 是页面里硬编码的、不走
 * `Spacing.*`，改常量只会让"卡片外框变小、内部间距不变"，缩放不均匀反而更难看；改 density
 * 则无需触碰任何页面代码，连 Canvas 里的 `dp.toPx()`（表盘、图表）也自动跟着缩。
 *
 * 只乘 `density`、不动 `fontScale`：sp → px 走 `sp × density × fontScale`，所以字号会跟着
 * 一起缩小（用户选择的"字和布局等比缩"），同时**系统字体放大设置依然按比例生效**——
 * fontScale 仍是系统值，只是基准变小了。若某天想改成"只缩布局、字号不变"，把 fontScale
 * 除以同一个 scale 即可反向补偿。
 *
 * WindowInsets 不受影响：状态栏 / 导航栏高度是系统给的 px，density 变小后换算出的 dp 变大，
 * 实际物理留白不变，这是正确行为。
 */
@Composable
fun UFIAXISTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    palette: ThemePalette = ThemePresets.Default,
    uiScalePercent: Int = ThemeManager.DEFAULT_UI_SCALE_PERCENT,
    content: @Composable () -> Unit
) {
    // Resolve palette for current dark/light mode
    val resolved = palette.resolve(darkTheme)

    // Derive M3 color scheme from resolved palette
    val colorScheme = buildColorSchemeFromPalette(resolved)

    // Provide palette through CompositionLocal
    ProvideThemePalette(palette = palette, isDark = darkTheme) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography
        ) {
            val base = LocalDensity.current
            // 基准 UI_SCALE_BASE：把"视觉上刚好合适"的那一档标定为 100%，
            // 于是设置页里的 100% 就是默认值，用户不必去理解"为什么默认是 90%"。
            val scale = ThemeManager.UI_SCALE_BASE * (
                uiScalePercent
                    .coerceIn(ThemeManager.UI_SCALE_PERCENT_MIN, ThemeManager.UI_SCALE_PERCENT_MAX) / 100f
                )
            CompositionLocalProvider(
                LocalDensity provides Density(
                    density = base.density * scale,
                    fontScale = base.fontScale
                ),
                // 给 Dialog / Popup 补偿用：它们会被平台重新 provide LocalDensity，
                // 需要在内容根部靠 UfiInheritUiScale 再套一次。
                LocalUfiUiScale provides scale,
                LocalUfiBaseDensity provides base.density,
                content = content
            )
        }
    }
}
