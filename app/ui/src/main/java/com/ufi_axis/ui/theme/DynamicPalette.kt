package com.ufi_axis.ui.theme

import android.content.Context
import android.os.Build
import androidx.compose.ui.graphics.Color

/**
 * Material You 动态取色（壁纸取色）→ 本仓 [ThemePalette] 的映射。
 *
 * ## 为什么走「平台资源」而不是 M3 的 `dynamicLightColorScheme(context)`
 * 参考 https://source.android.com/docs/core/display/material ：系统从壁纸生成
 * `system_accent1_*` / `system_accent2_*` / `system_neutral1_*` / `system_neutral2_*`
 * 四条**色调梯度**（tonal palette，每条 13 档：0/10/50/100…1000），API 31+ 可直接读。
 *
 * M3 的 `dynamicLightColorScheme()` 是把这些梯度打包成一个 40+ 角色的 `ColorScheme`。
 * 但本仓渲染层吃的是 [ThemePalette] / [ResolvedPalette]，**不是** `ColorScheme`
 * （`buildColorSchemeFromPalette` 是反方向的桥）。走 `ColorScheme` 就要做
 * 「梯度 → M3 角色 → 本仓色槽」两次映射，而中间那层会**丢掉我们真正需要的东西**：
 *
 * - `ColorScheme` 只暴露挑选后的 `surface` / `surfaceVariant` / `onSurface` 等成品角色，
 *   拿不到 neutral 梯度的**任意档位**；而本仓的 `pageBg` 与 `cardBg` 必须能各自选档，
 *   否则两个表面挤在同一档上就没有层次（[surfacesAreDistinguishable] 会红灯）。
 * - `ColorScheme` 的角色语义与本仓 17 槽不是一一对应（本仓有 `iconTint` / `divider` /
 *   `gradientMuted` 这类槽，M3 没有对等角色），映射里必然要人工兜；既然要兜，
 *   不如直接从梯度里挑档 —— 挑档这件事是**可解释、可测**的（档位 ≈ 明度，见下）。
 *
 * 直接读梯度还有一个实际好处：档位与明度的对应关系是 Material 规范定的
 * （`*_900` ≈ tone 10、`*_50` ≈ tone 95），所以"neutral 前景 vs neutral 表面"的对比度
 * 由**档位差**保证，不需要在运行时算。真正需要运行时算的只有 accent 之上的前景，见下。
 *
 * ## 对比度兜底
 * 动态 accent 完全由用户壁纸决定 —— 可能是深靛蓝，也可能是荧光黄，**静态无法保证**
 * 任何对比度。所以本文件严格分两类：
 *
 * - `textPrimary` / `textSecondary` / `iconTint` / `divider` / `pageBg` / `cardBg`
 *   **一律取 neutral 梯度**，不碰 accent。档位差已经保证了对比度。
 * - `onAccent` / `onGradient` / `gradientMuted`（accent 实底之上的前景）**在运行时算**：
 *   [onColorFor] 从"近黑 / 近白"两个候选里选对比度更高的那个。
 *   这是纯函数，由 `DynamicPaletteTest` 直接钉住，不依赖 Android 运行时。
 *
 * ## 与「皮肤 id」的关系
 * 动态取色**不是**第 10 套皮肤。它是一个独立开关（`ThemeManager.dynamicEnabled`），
 * 开启时覆盖静态皮肤。[DYNAMIC_THEME_ID] 只是运行期给 palette 一个可辨识的名字，
 * **从不持久化**、也**不在** [ThemePresets.allPresets] 里 ——
 * `ThemePresets.normalizeThemeId("dynamic")` 仍会把它折叠回默认（老用户迁移路径）。
 *
 * 当年把 `dynamic` 做成皮肤 id 的教训：`setTheme("dynamic")` 要同时写
 * `_selectedThemeId` 与 `_dynamicEnabled` 两个 flow，两次写入之间的中间态会让宿主
 * 多重建一次 palette 或短暂错色。现在两件事各有各的 flow，互不干扰。
 */

/** 动态取色 palette 的运行期 id（**不持久化**，也不在 [ThemePresets.allPresets] 里）。 */
const val DYNAMIC_THEME_ID: String = "dynamic"

/** 动态取色 palette 的显示名（皮肤入口置灰时的说明文案会引用它）。 */
const val DYNAMIC_THEME_NAME: String = "动态取色"

/**
 * 从系统色调梯度里挑出的 10 个基准色 —— **纯数据，不含任何 Android 类型**。
 *
 * 拆出这一层的唯一目的是让 [buildDynamicPalette] 变成可在 JVM 单测里直接调用的纯函数：
 * 测试自己造一组"荧光黄壁纸"或"深靛蓝壁纸"的色板，就能验证前景兜底是否真的翻转，
 * 而不需要 Robolectric 或真机（`:app:ui` 只声明了 `testImplementation(libs.junit)`）。
 *
 * 字段名沿用平台资源名（`accent1_600` 等），方便与
 * https://source.android.com/docs/core/display/material 的表格逐行对照。
 * **注意档位方向**：资源后缀 N 对应 tone `(1000 - N) / 10`，也就是**后缀越大越暗**
 * （`_0` = tone 100 ≈ 白，`_1000` = tone 0 = 黑）。这个方向很容易记反，记反的后果是
 * 深浅两套整体互换 —— 所以下面每个字段都注明了它大致落在哪一档。
 */
data class DynamicSourceColors(
    /** 浅色态主色。600 档（tone 40）是 Material 给"浅色主题主色"的标准档。 */
    val accent1_600: Color,
    /** 深色态主色。200 档（tone 80）—— 深色底上要用亮的一端。 */
    val accent1_200: Color,
    /**
     * 浅色态次强调。刻意取 **700**（tone 30）而不是 500/600：
     * `accentSecondary` 在本仓有当**图标 tint** 的消费点（`FileIcon` 的文件图标），
     * 压在近白卡面上必须够深；600 档在低彩度壁纸下会掉到 3:1 附近。
     */
    val accent2_700: Color,
    /** 深色态次强调。200 档（tone 80），深卡上的亮色点。 */
    val accent2_200: Color,
    /** 浅色卡面。0 档 = tone 100，几乎是白。 */
    val neutral1_0: Color,
    /**
     * 浅色页面底 + 深色态正文色（两处共用）。50 档 = tone 95。
     *
     * 一个色同时当"浅色页面底"和"深色正文"不是巧合：色调梯度的设计就是
     * tone 95 在浅色主题里是"最浅的表面"、在深色主题里是"最亮的前景"。
     */
    val neutral1_50: Color,
    /** 深色卡面。800 档（tone 20）。 */
    val neutral1_800: Color,
    /**
     * 深色页面底 + 浅色态正文色（两处共用，理由同 [neutral1_50]）。900 档（tone 10）。
     *
     * 它同时是 [onColorFor] 的"近黑"候选 —— 用 tone 10 而不是纯黑：
     * 纯黑压在彩色实底上偏硬，带一点壁纸色味的深色更协调。
     */
    val neutral1_900: Color,
    /** 浅色态副文案。neutral2 的 700 档（tone 30）：带一点壁纸色味的中灰，比纯灰更协调。 */
    val neutral2_700: Color,
    /** 深色态副文案。neutral2 的 200 档（tone 80）。 */
    val neutral2_200: Color
)

// Hero 渐变最亮停止点的比例 [GRADIENT_TOP_LIGHTEN]、分隔线透明度档 [DIVIDER_ALPHA]
// 与 sRGB 混色 [blendSrgb] 都在 `ColorContrast.kt`（internal，本包共用）。
// 本文件曾各自复制一份，与 `CustomPalette.kt` 逐字节相同 —— 改一个漏一个会让
// 动态取色与自定义皮肤的分隔线 / 渐变判据静默分叉。渐变判据统一走
// [heroGradientBrightestStop]，它与四张 Hero 卡实际画的像素同一份实现（[ufiShade]）。

/**
 * 色板 → [ThemePalette] 的**纯函数**映射（无 Android 依赖，由 `DynamicPaletteTest` 直接调）。
 *
 * 前景槽的取色纪律（这是当年删掉 `dynamic` 的直接原因，现在正面解决）：
 * - `textPrimary` / `textSecondary` / `iconTint` / `divider` / `pageBg` / `cardBg`
 *   **只取 neutral 梯度**，对比度由档位差保证，不受壁纸主色影响；
 * - `onAccent` / `onGradient` / `gradientMuted` 走 [onColorFor] **运行时算**，
 *   候选是 `neutral1_900`（近黑）与 `neutral1_50`（近白）。
 */
fun buildDynamicPalette(source: DynamicSourceColors): ThemePalette {
    val ink = source.neutral1_900
    val paper = source.neutral1_50
    // 浅色态：判据取渐变最亮点；深色态：渐变最亮点就是 accent 本身（往暗混）。
    // 两态都交给 heroGradientBrightestStop —— 之前浅色态内联写 mix(accent, White, 22%)、
    // 深色态直接传 accent，正是该函数两个分支的手写版。
    val onAccentLight = onColorFor(
        background = heroGradientBrightestStop(source.accent1_600, isDark = false),
        dark = ink,
        light = paper
    )
    val onAccentDark = onColorFor(
        background = heroGradientBrightestStop(source.accent1_200, isDark = true),
        dark = ink,
        light = paper
    )

    return ThemePalette(
        id = DYNAMIC_THEME_ID,
        name = DYNAMIC_THEME_NAME,
        accentLight = source.accent1_600,
        accentDark = source.accent1_200,
        accentSecondaryLight = source.accent2_700,
        accentSecondaryDark = source.accent2_200,
        pageBgLight = source.neutral1_50,
        pageBgDark = source.neutral1_900,
        cardBgLight = source.neutral1_0,
        cardBgDark = source.neutral1_800,
        textPrimaryLight = ink,
        textPrimaryDark = paper,
        textSecondaryLight = source.neutral2_700,
        textSecondaryDark = source.neutral2_200,
        dividerLight = blendSrgb(source.neutral1_0, ink, DIVIDER_ALPHA),
        dividerDark = blendSrgb(source.neutral1_800, paper, DIVIDER_ALPHA),

        iconTintLight = ink,
        iconTintDark = paper,
        onAccentLight = onAccentLight,
        onAccentDark = onAccentDark,
        onGradientLight = onAccentLight,
        onGradientDark = onAccentDark,
        gradientMutedLight = onAccentLight,
        gradientMutedDark = onAccentDark
    )
}

// ══════════════════════════════════════════════════════════════════════════════
// Android 侧：读平台资源
// ══════════════════════════════════════════════════════════════════════════════

/**
 * 系统版本闸门：动态取色的资源族是 **API 31（Android 12）** 引入的。
 *
 * ⚠️ 本仓 `minSdk = 31`（见 `app/build.gradle.kts` 与 `app/ui/build.gradle.kts`），
 * 所以这个判断在**当前配置下恒为 true**。刻意保留而不是删掉，有两个作用：
 * 1. 把"这个能力有版本门槛"这件事写在代码里，将来若下调 minSdk 不会静默出错；
 * 2. 让 [isDynamicColorAvailable] 的语义是完整的两段闸门（版本 + 能力），
 *    而 UI 只看那一个入口，不必自己拼版本判断。
 *
 * **真正会失败的是下面那半段能力探测**：AOSP 派生 / 部分定制 ROM 可能没有 Monet 实现，
 * 资源 id 存在但取值抛 `Resources.NotFoundException`。所以"开关是否可用"不能只看版本。
 */
val isDynamicColorSupportedByOs: Boolean
    get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

/**
 * 读系统色调梯度。任一档取不到就返回 `null`（**整组失败**，不做部分填充）。
 *
 * 为什么不逐档兜底：混着用"真动态色 + 静态兜底色"会得到一套色相自相矛盾的 palette
 * （accent 是壁纸的紫、页面底却是灰的），比干脆退回静态皮肤更糟。
 */
fun readDynamicSourceColors(context: Context): DynamicSourceColors? {
    if (!isDynamicColorSupportedByOs) return null
    return runCatching {
        fun res(id: Int): Color = Color(context.getColor(id))
        DynamicSourceColors(
            accent1_600 = res(android.R.color.system_accent1_600),
            accent1_200 = res(android.R.color.system_accent1_200),
            accent2_700 = res(android.R.color.system_accent2_700),
            accent2_200 = res(android.R.color.system_accent2_200),
            neutral1_0 = res(android.R.color.system_neutral1_0),
            neutral1_50 = res(android.R.color.system_neutral1_50),
            neutral1_800 = res(android.R.color.system_neutral1_800),
            neutral1_900 = res(android.R.color.system_neutral1_900),
            neutral2_700 = res(android.R.color.system_neutral2_700),
            neutral2_200 = res(android.R.color.system_neutral2_200)
        )
    }.getOrNull()
}

/**
 * 动态取色**当前设备上是否真的可用**（版本闸门 + 资源能力探测）。
 *
 * 「设置 → 外观」的开关用它决定是否可点：本仓硬规则是**UI 显示状态必须与所有闸门一致**，
 * 不允许出现"开关能拨、拨了没反应"的假开关。探测失败时开关置灰并给出原因文案。
 */
fun isDynamicColorAvailable(context: Context): Boolean = readDynamicSourceColors(context) != null

/**
 * 取动态 palette；不可用时返回 `null`，由调用方回落到静态皮肤。
 *
 * 刻意**不做缓存**：调用点是 `ThemeManager.getCurrentPalette()`，而它被
 * `MainActivity` 的 `remember(selectedThemeId, dynamicEnabled)` 包着，一次组合最多调一次。
 * 加缓存反而会挡住"用户换壁纸后重进 App 应该看到新颜色"这条路径
 * （换壁纸会让系统重建资源并重启 Activity，此时必须重新读盘）。
 */
fun dynamicPaletteOrNull(context: Context): ThemePalette? =
    readDynamicSourceColors(context)?.let(::buildDynamicPalette)
