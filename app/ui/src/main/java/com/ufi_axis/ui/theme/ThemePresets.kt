package com.ufi_axis.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * 主题皮肤预设表：**中性灰阶 1 套 + 彩色 6 套 = 7 套**。
 *
 * 除本表之外还有两套**运行期生成**的 palette，它们刻意**不在** [allPresets] 里
 * （没有固定色值，无法写成常量）：
 * - 动态取色（壁纸）：独立开关 `ThemeManager.dynamicEnabled` + [buildDynamicPalette]；
 * - 自定义皮肤（用户自选种子色）：皮肤 id `"custom"` + [buildCustomPalette]，见 `CustomPalette.kt`。
 *   它是**合法的持久化 id**（见 [normalizeThemeId] / [SELECTABLE_IDS]），但 [findById] 找不到它 ——
 *   这不是不一致，而是"id 合法 / 色值不在表里"的必然结果，取色链路在
 *   `ThemeManager.getCurrentPalette()` 里比 [findById] 更早接手。
 *
 * ## 沿革
 * - 2026-09-05 上午按产品要求收敛为唯一一套 [Default]，同时删掉 `dynamic`（动态取色）与
 *   `custom`（自定义强调色）两个特殊 id —— 它们的入口全仓零调用、UI 上不可达。
 * - 2026-09-05 下午拿到正式设计稿，按稿加回 8 套彩色皮肤（本节）。
 *   动态取色也一并回归，但**不再是一个"皮肤 id"**：它是一个独立开关，
 *   走 `ThemeManager.dynamicEnabled` + [buildDynamicPalette]，见 `DynamicPalette.kt`。
 *   拆开的理由：当年 `dynamic` 作为 id 存在时，`setTheme("dynamic")` 要同时写
 *   `_selectedThemeId` 与 `_dynamicEnabled` 两个 flow，中间态会让宿主多重建一次 palette
 *   或短暂错色。现在皮肤 id 只表示"用户选了哪套静态皮肤"，开关只表示"要不要被壁纸覆盖"。
 * - 2026-09-05 傍晚按产品要求**删掉 `orange`（橙色 `#f97316`）与 `cyan`（青色 `#22d3ee`）**，
 *   彩色皮肤从 8 套收敛到 6 套。两个 id 由 [normalizeThemeId] 折叠回 [DEFAULT_ID]。
 * - 2026-09-05 傍晚**自定义皮肤回归，并且这次是真的做出来了**（带取色 UI）。
 *   上午删掉的那套 `custom` / `setCustomAccent` / `buildCustomPalette` 是死代码
 *   （全仓零调用、UI 不可达）；现在的实现不是 revert：`ThemePalette` 有 17 个色槽而用户
 *   只给一个种子色，所以走的是与动态取色**同一套架构**（前景槽只取中性梯度、
 *   `on*` 走 [onColorFor] 运行时兜底），见 `CustomPalette.kt`。
 *
 * ## 6 套彩色皮肤的推导口径（重要：改色值前务必读完）
 * 设计稿每套只给三个色：主色 `p`、深色 `d`、浅色 `l`。17 个色槽全部由它们推导，
 * 推导规则写在下面每个 preset 的注释里，并被 `ColorTest` 的
 * `every preset meets the contrast floor in both modes` 逐套逐态量化钉住。
 *
 * 与设计稿**刻意不同**的两处（都是为了过对比度硬指标，不是手误）：
 *
 * 1. **深色态页面底色不用饱和的 `d`**，而是 `d` 往近黑 `#0B0B0C` 混合后的低饱和版
 *    （`pageBgDark` 混 82%、`cardBgDark` 混 58%，**玫红 / 紫色两套 2026-09-24 起改为 50%**）。
 *    直接用 `d` 铺满整个 App 会让长时间浏览很累，且卡片与页面同色系高饱和时层次会糊；
 *    现在两者 ΔL\* 在 6.5~9.2 之间（最紧的是宝蓝 6.46、最松的是翠绿 9.16），
 *    由 [MIN_SURFACE_DELTA_L_DARK]（深色态专用下限 6.0）逐套钉住。
 *    ⚠ 2026-09-24 之前这个区间是 **5.6~9.2**（玫红 5.59 / 紫色 5.73 垫底），
 *    那就是用户"玫红深色模式背景不美观"的病灶：卡片浮不起来。见 [surfacesAreDistinguishable]。
 * 2. **浅色态正文色不是 `d` 本身**。设计稿写 `text = d`、`text2 = d@60%`，但 `d@60%`
 *    压在白卡上只有 3.1~3.9:1，**过不了正文 4.5:1**。所以下移一档：
 *    `textPrimary` = `d` 往近黑混 45% 的同色相深墨，`textSecondary` = `d` 原色。
 *    这样两级正文在浅色态都 ≥ 7.08:1（最紧是柠绿），层次感也比"深色 + 淡化深色"更清晰。
 *
 * ## 亮色 accent 的实底前景（必读，三套亮皮肤的必然问题）
 * `accent` 在本仓的职责是**实底色块**（主按钮 / FAB / 进度条已填充段 / 选中填充 /
 * Hero 渐变底）。柠绿 `#a3e635`、翠绿 `#4ade80`、金黄 `#f59e0b` 亮度都很高，
 * 白字压上去只有 1.5~2.1:1（判据必须是算出来的而不是肉眼挑的 —— 已删除的橙色
 * `#f97316` 看着"够深"，实测却只有 2.80:1）。
 * 处置：这 3 套的 `onAccent` / `onGradient` / `gradientMuted` 一律取该套的**深墨色**
 * （与 `textPrimaryLight` 同值），玫红 / 宝蓝 / 紫色三套保持白色。
 * 判据取 Hero 渐变的**最亮停止点** `accent.ufiShade(0.22)`（见 [heroGradientBrightestStop]
 * 与 [ufiShade]，四张 Hero 卡与判据共用同一份实现）而不是 accent 本身 —— 那才是白字最容易
 * 失守的位置。删掉橙 / 青后，**全表最紧的一项**
 * 就是玫红浅色态的 `onGradient` 对渐变最亮停止点：3.12:1（门槛 3.0，余量仅 4%）。
 *
 * 2026-09-24：**深色态也开始有这个问题了**。那天把四张 Hero 卡的深色渐变从"往黑混"
 * 改成"往白提亮 16%"（见 [GRADIENT_TOP_LIGHTEN_DARK]），判据的深色分支同步跟上，
 * 于是深色态最亮停止点 = `accent.ufiShade(0.16)`。白字三套里最紧的是玫红深色态 3.37:1
 * （余量 12%）；金黄 / 柠绿 / 翠绿走深墨前景，底色变亮只会让它们更宽松。
 *
 * ## 2026-09-24 深色态观感批次（A / B / C，用户定稿）
 * 起因是"玫红配色深色模式背景不美观"，用户以为要**提高**对比度；实测推翻了这个前提 ——
 * 深色态 `textPrimary` 对 `pageBg` 当时是 17.61~18.79:1，而 WCAG AAA 门槛只要 7.0。
 * 真正的病灶是**表面层次太弱、而线条过响**，于是本批只动三处，且**全部只动深色支**：
 *
 * - **A 线弱化**：`dividerDark` 从卡面混 15% 白降到 **6%**；`cardBorder` 深色档白 8% → **5%**
 *   （见 [DIVIDER_ALPHA_DARK] 与 `ThemePalette.cardBorder`）。判据是
 *   [MAX_LINE_TO_SURFACE_DELTA_RATIO]：`ΔL*(divider, cardBg)` 不得超过 `ΔL*(cardBg, pageBg)`。
 *   改前 6 套彩色皮肤这个比值是 1.52~2.64，改后 0.60~0.92（默认皮肤一直是 0.54）。
 * - **B 去眩光**：6 套彩色皮肤的 `textPrimaryDark` 纯白 `#FFFFFF` → **`#EEEEEE`**
 *   （与默认皮肤对齐）。纯白压近黑是眩光 / 光晕的来源，不是清晰度。
 * - **C 抬卡面**：玫红 / 紫色的 `cardBgDark` 统一系数 58% → **50%**（per-preset 定点补偿）。
 *
 * ⚠ 本批**刻意没动**的两处，留给后续批次：
 * 1. `iconTintDark` 仍是纯白 `#FFFFFF`（6 套彩色皮肤）—— 它与 `textPrimaryDark` 原本同值，
 *    现在分叉了。B 项用户只定了正文色；图标是实心图形、眩光观感与正文不同档，
 *    要不要一起降到 `#EEEEEE` 是一个独立的设计决策（不影响任何对比度下限：白对卡面更宽松）。
 * 2. **D 项：按 L\* 反解 `cardBgDark`**。C 项只是给两套皮肤单独换了个系数，
 *    "统一混色系数在红-品红端产出明度偏低"这个根因仍在：宝蓝的 ΔL\* 6.46 就是它的残留
 *    （全表最低，也是 A 项只能取 6% 而不是 7% 的原因）。根治做法是给定目标 ΔL\*
 *    反解出 `cardBgDark` 的 L\*，再回到 sRGB —— 那时 6 套皮肤会重新共用一条推导式。
 *
 * ## 老用户迁移
 * prefs 里可能留着 `"aurora"` 等历史 id（出厂默认曾是 `"aurora"`）。
 * [normalizeThemeId] 负责把任何未知 / 已删除的 id 折叠回 [DEFAULT_ID]，
 * `ThemeManager` 在冷启动播种时调用它并把规整结果写回磁盘，于是脏值只会存在一次冷启动。
 *
 * ## 想再加皮肤怎么做
 * 在下方追加一个 `val Xxx = ThemePalette(id = "xxx", …)`，并把它加进 [allPresets] ——
 * [ALLOWED_IDS] 是从 [allPresets] 推导的，不需要两处维护。会红灯的是两处，都是**故意**的：
 *
 * - `ColorTest` 的 `accent gradient values are pinned for every preset` —— 期望表用
 *   `getValue` 取，新 id 没补期望值会直接抛，逼你把 accentStrong / accentMuted 过一遍；
 * - `ColorTest` 的 `every preset meets the contrast floor in both modes` —— 它**遍历**
 *   [allPresets]，新皮肤自动进入体检，不需要为它单独写测试。
 *
 * `preset table only contains allowed ids` 那条不会再拦你（白名单由 [allPresets] 推导），
 * 但外观页的皮肤网格同样遍历 [allPresets]，所以新皮肤加完即可选，不会变成死代码。
 */
object ThemePresets {

    /** 出厂默认皮肤 id。也是任何未知 id 的回落目标（见 [normalizeThemeId]）。 */
    const val DEFAULT_ID: String = "default"

    /**
     * 出厂默认皮肤：中性灰阶。
     *
     * 加回彩色皮肤后**它仍是出厂默认**（[DEFAULT_ID] 未变）—— 中性灰阶是"不选颜色"
     * 的那一档，用户没主动挑颜色时不该被塞一套彩色。
     *
     * 2026-09-08 根治：本套的三个 accent 槽原先是**明暗方向搞反**的，这是"深色模式下
     * 图标/文字发灰"的唯一根因，而不是某个页面写错了取色：
     * - `accentDark` 原 0xFF555555 对 [cardBgDark]（0xFF2A2A2A）仅 **1.9:1**，
     *   而全仓有 90+ 处把 accent 当前景（图标 tint / 文字 / 描边 / 圆环）；
     * - `accentSecondaryDark` 原 0xFF555555 同样 1.9:1；
     * - `accentSecondaryLight` 原 0xFFE5E5E5 对白卡仅 1.26:1（`FileIcon` 拿它当文件图标 tint）。
     *
     * 6 套彩色皮肤遵循的约定是"accent 永远落在**当前明暗档的可读侧**"（深色态取亮色、
     * 浅色态取深色），本套照该约定改正后，那 90+ 处前景**一次性全部合规**，
     * 无需任何调用点改动 —— 这也是为什么这里不再需要"可读版 accent"派生槽。
     *
     * accent 变亮后它当**实底**时的前景必须翻黑，所以 `onAccentDark` / `onGradientDark` /
     * `gradientMutedDark` 一并给出深墨值（与 amber / lime / emerald 三套同一处置）。
     * 浅色态 accent 仍是 0xFF222222 深灰，故 `on*Light` 保持默认白。
     */
    val Default = ThemePalette(
        id = DEFAULT_ID,
        name = "默认",
        accentLight = Color(0xFF222222),
        accentDark = Color(0xFFB0B0B0),
        accentSecondaryLight = Color(0xFF5A5A5A),
        accentSecondaryDark = Color(0xFFD9D9D9),
        pageBgLight = Color(0xFFF8F8F8),
        pageBgDark = Color(0xFF1A1A1A),
        cardBgLight = Color(0xFFFFFFFF),
        cardBgDark = Color(0xFF2A2A2A),
        textPrimaryLight = Color(0xFF111111),
        textPrimaryDark = Color(0xFFEEEEEE),
        textSecondaryLight = Color(0xFF444444),
        textSecondaryDark = Color(0xFFBBBBBB),
        dividerLight = Color(0xFFE5E5E5),
        dividerDark = Color(0xFF333333),
        iconTintLight = Color(0xFF111111),
        iconTintDark = Color(0xFFEEEEEE),
        // accent 深色态已翻亮（0xFFB0B0B0），压在它上面的前景必须翻黑，否则实底按钮/
        // 渐变 Hero 卡会变成"白字压亮灰"。浅色态 accent 仍是深灰，故 on*Light 用默认白。
        onAccentDark = Color(0xFF1A1A1A),
        onGradientDark = Color(0xFF1A1A1A),
        gradientMutedDark = Color(0xFF1A1A1A)
    )

    // ══════════════════════════════════════════════════════════════════════════
    // 6 套彩色皮肤（2026-09-05 设计稿；橙 / 青两套已于当日傍晚按产品要求删除）
    //
    // 每套只由设计稿的三个色推导：p = 主色、d = 深色、l = 浅色。
    // 下面 6 段的色值全部是**算出来的**，不是手挑的；推导常数只有 6 个，逐套一致：
    //
    //   deepInk       = mix(d, #0B0B0C, 45%)   同色相深墨：浅色态正文 / 图标 / 亮 accent 的前景
    //   pageBgLight   = mix(p, #F7F7F8, 94%)   6% 主色染色的近白页面底（不用纯白，否则和白卡没层次）
    //   cardBgLight   = #FFFFFF                设计稿的浅色卡
    //   pageBgDark    = mix(d, #0B0B0C, 82%)   低饱和深色页面底（不用饱和 d，理由见类 KDoc）
    //   cardBgDark    = mix(d, #0B0B0C, 58%)   比 pageBgDark 亮一档、同色相
    //                   ⚠ 2026-09-24 起玫红 / 紫色**不走这个系数**，改为 50%（per-preset 覆盖，
    //                     见那两套的注释）：统一系数在 sRGB 上对所有色相一视同仁，而 Rec.709 里
    //                     红-品红的亮度权重最低（R 0.2126），同一系数在这两个色相上产出的 L\* 最低。
    //   accentSec.L   = mix(p, d, 55%)         浅色态的次强调：`l` 压在白底上只有 1.9:1，不能用
    //
    //   textSecondaryDark = 白 60% composite 到 cardBgDark（设计稿的 alpha 值，压成实色）
    //   dividerLight      = 黑 15% composite 到 cardBgLight（同上，仓内字段是纯 Color，不接受 alpha）
    //   dividerDark       = 白 **6%** composite 到 cardBgDark
    //                   ⚠ 2026-09-24 起深色档从 15% 降到 6%（浅色档 15% 未动）。原先两态共用
    //                     一个 15%，深色态因此"线比面响"：玫红 ΔL\*(divider, cardBg) = 14.74,
    //                     是卡/页 ΔL\*(5.59) 的 2.64 倍，而默认皮肤这个比值只有 0.54。
    //                     完整推导与判据见 [DIVIDER_ALPHA_DARK] / [MAX_LINE_TO_SURFACE_DELTA_RATIO]。
    //   textPrimaryDark   = #EEEEEE（2026-09-24 起；原为纯白 #FFFFFF）
    //                   ⚠ 纯白压近黑的实测对比度是 17.61~18.79:1，而 WCAG AAA 只要 7.0 ——
    //                     超标 2.4~2.7 倍不是"更清晰"而是眩光 / 光晕。改成与默认皮肤同值的
    //                     #EEEEEE 后是 15.18~16.19:1，仍是 AAA 的 2 倍以上。
    //                     ⚠ `iconTintDark` 本批**刻意未动**（仍是纯白），见类 KDoc 末尾。
    //
    // 为什么 composite 成实色而不是存 alpha：带 alpha 的色在不同底色上观感不一致，
    // 且 [contrastRatio] 不吃 alpha —— 存实色才能被对比度测试真正验到。
    //
    // 命名：id 用英文 snake_case（与 prefs 持久化值一致），name 用中文（直接进 UI）。
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * 玫红 —— p `#ed335f` / d `#761137` / l `#f9858b`。accent 够深，实底走白字。
     *
     * ⚠ 本套的 `cardBgDark` 是**唯一两处** per-preset 覆盖之一（另一处是 [Violet]）：
     * 统一推导式 `mix(d, #0B0B0C, 58%)` 在这里产出 `#380E1E`，L\* 只有 10.91（全表最低），
     * 对 `pageBgDark` 的 ΔL\* = **5.59**，也是全表最低 —— 就是用户 2026-09-24 反馈
     * "玫红深色模式背景不美观"的直接原因。改用 **50%** 后是 `#410E21`，ΔL\* = **7.58**，
     * 与金黄 7.17 / 默认 7.80 同一档。详见下方色值行上的注释与 [MIN_SURFACE_DELTA_L_DARK]。
     */
    val Rose = ThemePalette(
        id = "rose",
        name = "玫红",
        accentLight = Color(0xFFED335F),
        accentDark = Color(0xFFED335F),
        accentSecondaryLight = Color(0xFFAC2049),
        accentSecondaryDark = Color(0xFFF9858B),
        pageBgLight = Color(0xFFF6EBEF),
        pageBgDark = Color(0xFF1E0C14),
        cardBgLight = Color(0xFFFFFFFF),
        // 2026-09-24 per-preset 覆盖：原 0xFF380E1E = mix(d, #0B0B0C, 58%)（统一系数），
        // 现 0xFF410E21 = mix(d, #0B0B0C, **50%**)。
        // 为什么当年是 58%：6 套彩色皮肤共用一条推导式，58% 是在 sRGB 上挑的"比 pageBgDark
        // 亮一档"的数，对 6 个色相一视同仁。
        // 为什么这里必须单独抬：Rec.709 里红-品红的亮度权重最低（R 系数 0.2126），
        // 同一个系数在这个色相上产出的 L\* 最低 —— 玫红与金黄的 **HSL 明度完全相同（都 13.7%）**，
        // 但 L\* 是 10.91 vs 14.27，差 31%。
        // 判据数值：ΔL\*(cardBg, pageBg) 5.59 → **7.58**；
        // 连带 textPrimary(#EEEEEE)/cardBg 13.82:1、textSecondary/cardBg 6.36:1（门槛 4.5）、
        // accent/cardBg 4.19 → **4.00**（门槛 3.0，仍达标）、accentSecondary/cardBg 6.69:1。
        // 这是**定点补偿**，不是根治：统一系数在红-品红端偏暗这件事没有被修掉，
        // 根治方案是按目标 L\* 反解 cardBgDark（D 项，单独一批）。
        cardBgDark = Color(0xFF410E21),
        textPrimaryLight = Color(0xFF460E24),
        textPrimaryDark = Color(0xFFEEEEEE), // 2026-09-24 消除纯白眩光，原 0xFFFFFFFF，见上方推导口径
        textSecondaryLight = Color(0xFF761137),
        textSecondaryDark = Color(0xFFAF9FA5),
        dividerLight = Color(0xFFEADBE1),
        // 2026-09-24：原 0xFF563240 = cardBg 混 15% 白，现 0xFF4C1C2E = 新 cardBg 混 **6%** 白。
        // 原值的 ΔL\*(divider, cardBg) = 14.74，是卡/页 ΔL\*(5.59) 的 **2.64 倍**（默认皮肤 0.54）
        // —— 线比面响，卡片浮不起来。现在是 5.30 / 7.58 = **0.70**，见 [DIVIDER_ALPHA_DARK]。
        dividerDark = Color(0xFF4C1C2E),
        iconTintLight = Color(0xFF460E24),
        iconTintDark = Color(0xFFFFFFFF)
    )

    /** 金黄 —— p `#f59e0b` / d `#78350f` / l `#fcd34d`。白 vs 渐变最亮点仅 2.13:1 → 走深墨前景。 */
    val Amber = ThemePalette(
        id = "amber",
        name = "金黄",
        accentLight = Color(0xFFF59E0B),
        accentDark = Color(0xFFF59E0B),
        accentSecondaryLight = Color(0xFFB0640D),
        accentSecondaryDark = Color(0xFFFCD34D),
        pageBgLight = Color(0xFFF7F2EA),
        pageBgDark = Color(0xFF1F130D),
        cardBgLight = Color(0xFFFFFFFF),
        cardBgDark = Color(0xFF391D0D),
        textPrimaryLight = Color(0xFF47220E),
        textPrimaryDark = Color(0xFFEEEEEE), // 2026-09-24 消除纯白眩光，原 0xFFFFFFFF，见上方推导口径
        textSecondaryLight = Color(0xFF78350F),
        textSecondaryDark = Color(0xFFB0A59E),
        dividerLight = Color(0xFFEBE1DB),
        // 2026-09-24：原 0xFF573F31 = cardBg 混 15% 白（ΔL\* 14.64 = 卡/页 7.17 的 2.04 倍），
        // 现 0xFF452B1C = 混 **6%** 白（ΔL\* 6.07，比值 0.85）。见 [DIVIDER_ALPHA_DARK]。
        dividerDark = Color(0xFF452B1C),
        iconTintLight = Color(0xFF47220E),
        iconTintDark = Color(0xFFFFFFFF),
        onAccentLight = Color(0xFF47220E),
        onAccentDark = Color(0xFF47220E),
        onGradientLight = Color(0xFF47220E),
        onGradientDark = Color(0xFF47220E),
        gradientMutedLight = Color(0xFF47220E),
        gradientMutedDark = Color(0xFF47220E)
    )

    /** 柠绿 —— p `#a3e635` / d `#3f6212` / l `#d9f99d`。最亮的一套，白 vs accent 仅 1.51:1 → 深墨前景。 */
    val Lime = ThemePalette(
        id = "lime",
        name = "柠绿",
        accentLight = Color(0xFFA3E635),
        accentDark = Color(0xFFA3E635),
        accentSecondaryLight = Color(0xFF6C9D22),
        accentSecondaryDark = Color(0xFFD9F99D),
        pageBgLight = Color(0xFFF2F6EC),
        pageBgDark = Color(0xFF141B0D),
        cardBgLight = Color(0xFFFFFFFF),
        cardBgDark = Color(0xFF21300F),
        textPrimaryLight = Color(0xFF283B0F),
        textPrimaryDark = Color(0xFFEEEEEE), // 2026-09-24 消除纯白眩光，原 0xFFFFFFFF，见上方推导口径
        textSecondaryLight = Color(0xFF3F6212),
        textSecondaryDark = Color(0xFFA6AC9F),
        dividerLight = Color(0xFFE2E7DB),
        // 2026-09-24：原 0xFF424F33 = cardBg 混 15% 白（ΔL\* 13.99 = 卡/页 9.12 的 1.53 倍），
        // 现 0xFF2E3C1D = 混 **6%** 白（ΔL\* 5.55，比值 0.61）。见 [DIVIDER_ALPHA_DARK]。
        dividerDark = Color(0xFF2E3C1D),
        iconTintLight = Color(0xFF283B0F),
        iconTintDark = Color(0xFFFFFFFF),
        onAccentLight = Color(0xFF283B0F),
        onAccentDark = Color(0xFF283B0F),
        onGradientLight = Color(0xFF283B0F),
        onGradientDark = Color(0xFF283B0F),
        gradientMutedLight = Color(0xFF283B0F),
        gradientMutedDark = Color(0xFF283B0F)
    )

    /** 翠绿 —— p `#4ade80` / d `#166534` / l `#bbf7d0`。白 vs accent 1.74:1 → 深墨前景。 */
    val Emerald = ThemePalette(
        id = "emerald",
        name = "翠绿",
        accentLight = Color(0xFF4ADE80),
        accentDark = Color(0xFF4ADE80),
        accentSecondaryLight = Color(0xFF2D9B56),
        accentSecondaryDark = Color(0xFFBBF7D0),
        pageBgLight = Color(0xFFEDF6F1),
        pageBgDark = Color(0xFF0D1B13),
        cardBgLight = Color(0xFFFFFFFF),
        cardBgDark = Color(0xFF10311D),
        textPrimaryLight = Color(0xFF113D22),
        textPrimaryDark = Color(0xFFEEEEEE), // 2026-09-24 消除纯白眩光，原 0xFFFFFFFF，见上方推导口径
        textSecondaryLight = Color(0xFF166534),
        textSecondaryDark = Color(0xFF9FADA5),
        dividerLight = Color(0xFFDCE8E1),
        // 2026-09-24：原 0xFF34503F = cardBg 混 15% 白（ΔL\* 13.97 = 卡/页 9.16 的 1.52 倍），
        // 现 0xFF1E3D2B = 混 **6%** 白（ΔL\* 5.52，比值 0.60）。见 [DIVIDER_ALPHA_DARK]。
        dividerDark = Color(0xFF1E3D2B),
        iconTintLight = Color(0xFF113D22),
        iconTintDark = Color(0xFFFFFFFF),
        onAccentLight = Color(0xFF113D22),
        onAccentDark = Color(0xFF113D22),
        onGradientLight = Color(0xFF113D22),
        onGradientDark = Color(0xFF113D22),
        gradientMutedLight = Color(0xFF113D22),
        gradientMutedDark = Color(0xFF113D22)
    )

    /** 宝蓝 —— p `#2563eb` / d `#1e3a8a` / l `#93c5fd`。accent 够深，实底走白字（渐变最亮点 3.51:1）。 */
    val Blue = ThemePalette(
        id = "blue",
        name = "宝蓝",
        accentLight = Color(0xFF2563EB),
        accentDark = Color(0xFF2563EB),
        accentSecondaryLight = Color(0xFF214CB6),
        accentSecondaryDark = Color(0xFF93C5FD),
        pageBgLight = Color(0xFFEAEEF7),
        pageBgDark = Color(0xFF0E1323),
        cardBgLight = Color(0xFFFFFFFF),
        cardBgDark = Color(0xFF131F41),
        textPrimaryLight = Color(0xFF152551),
        textPrimaryDark = Color(0xFFEEEEEE), // 2026-09-24 消除纯白眩光，原 0xFFFFFFFF，见上方推导口径
        textSecondaryLight = Color(0xFF1E3A8A),
        textSecondaryDark = Color(0xFFA1A5B3),
        dividerLight = Color(0xFFDDE1ED),
        // 2026-09-24：原 0xFF36415E = cardBg 混 15% 白（ΔL\* 15.16 = 卡/页 6.46 的 2.35 倍），
        // 现 0xFF212C4C = 混 **6%** 白（ΔL\* 5.96，比值 **0.92**，全表最紧的一档）。
        // 宝蓝的卡/页 ΔL\* 6.46 是修复后全表最低（本批没有抬它的卡面，见 [MIN_SURFACE_DELTA_L_DARK]），
        // 所以它同时是"7% 白这一档过不了 1 倍判据"的那套 —— 详见 [DIVIDER_ALPHA_DARK]。
        dividerDark = Color(0xFF212C4C),
        iconTintLight = Color(0xFF152551),
        iconTintDark = Color(0xFFFFFFFF)
    )

    /**
     * 紫色 —— p `#7c3aed` / d `#4c1d95` / l `#c4b5fd`。accent 够深，实底走白字（渐变最亮点 3.83:1）。
     *
     * ⚠ 本套的 `cardBgDark` 与 [Rose] 一样是 per-preset 覆盖（统一系数 58% → **50%**）：
     * 原 `#261346` 的 ΔL\*(cardBg, pageBg) = 5.73，是全表倒数第二（仅高于玫红 5.59）。
     * 品红-紫同样落在 Rec.709 亮度权重最低的那一段（R 0.2126 + B 0.0722）。
     */
    val Violet = ThemePalette(
        id = "violet",
        name = "紫色",
        accentLight = Color(0xFF7C3AED),
        accentDark = Color(0xFF7C3AED),
        accentSecondaryLight = Color(0xFF622ABD),
        accentSecondaryDark = Color(0xFFC4B5FD),
        pageBgLight = Color(0xFFF0ECF7),
        pageBgDark = Color(0xFF170E25),
        cardBgLight = Color(0xFFFFFFFF),
        // 2026-09-24 per-preset 覆盖：原 0xFF261346 = mix(d, #0B0B0C, 58%)（统一系数），
        // 现 0xFF2B1450 = mix(d, #0B0B0C, **50%**)，与 [Rose] 同一处置、同一档系数。
        // 判据数值：ΔL\*(cardBg, pageBg) 5.73 → **7.50**；textPrimary(#EEEEEE)/cardBg 13.73:1、
        // textSecondary/cardBg 6.39:1（门槛 4.5）、accentSecondary/cardBg 8.63:1。
        // `accent/cardBg` 2.92 → **2.79**：本套深色态本来就在
        // `ColorTest.ACCENT_FLOOR_EXEMPTIONS` 里（紫色 accent 是 6 套里色相最暗的一套，
        // 卡面又是它自己的色相调出来的），抬亮卡面会让这一项更低 —— 例外清单不变，
        // 可读兜底仍是 accentSecondary（8.63:1）。想真正修掉它要重标定 accentDark，是另一件事。
        // 同为定点补偿，根治方案见 D 项（按目标 L\* 反解 cardBgDark）。
        cardBgDark = Color(0xFF2B1450),
        textPrimaryLight = Color(0xFF2F1557),
        textPrimaryDark = Color(0xFFEEEEEE), // 2026-09-24 消除纯白眩光，原 0xFFFFFFFF，见上方推导口径
        textSecondaryLight = Color(0xFF4C1D95),
        textSecondaryDark = Color(0xFFA8A1B5),
        dividerLight = Color(0xFFE4DDEF),
        // 2026-09-24：原 0xFF473662 = 旧 cardBg 混 15% 白（ΔL\* 14.92 = 卡/页 5.73 的 2.61 倍），
        // 现 0xFF38225B = **新** cardBg 混 **6%** 白（ΔL\* 5.78，比值 0.77）。见 [DIVIDER_ALPHA_DARK]。
        dividerDark = Color(0xFF38225B),
        iconTintLight = Color(0xFF2F1557),
        iconTintDark = Color(0xFFFFFFFF)
    )


    /**
     * 全部**有固定色值**的可选皮肤 —— 外观页的皮肤网格直接遍历它，
     * 所以加进来即可选，不会变成死代码。
     *
     * [Default] 放在首位且仍是出厂默认（[DEFAULT_ID] 未变）：中性灰阶是"不选颜色"的那一档，
     * 用户不主动挑颜色时应该拿到它，而不是被塞一套彩色。
     *
     * ⚠️ 「自定义」（[CUSTOM_THEME_ID]）**不在这里**：它的色值由用户种子色在运行期算出
     * （[buildCustomPalette]），写不成常量。外观页的皮肤网格在遍历本表之后**额外追加**
     * 一个自定义格子，见 `AppearanceSettingsScreen`。
     */
    val allPresets: List<ThemePalette> = listOf(
        Default, Rose, Amber, Lime, Emerald, Blue, Violet
    )

    /**
     * 允许出现在 [allPresets] 里的 id 白名单。
     *
     * **由 [allPresets] 推导**，不是手写常量 —— 手写会变成"加皮肤要改两处"，
     * 而漏改的表现是新皮肤被 [normalizeThemeId] 静默折叠回默认（用户点了没反应）。
     * 真正的"人工确认"卡点放在 `ColorTest` 里：那条测试把 7 个 id 显式列了一遍，
     * 加皮肤会红灯，红灯即提醒去看对比度体检结果。
     *
     * ⚠️ 它的语义严格是"**预设表**里的 id"，**不是**"所有合法的持久化 id" ——
     * 后者见 [SELECTABLE_IDS]（多一个 [CUSTOM_THEME_ID]）。刻意不把 custom 塞进本集合：
     * `ColorTest` 有一条 `ALLOWED_IDS == allPresets.map{id}.toSet()` 的恒等断言，
     * 它是"白名单必须由表推导、不许手写"这条纪律的执行者，塞进去就把它废掉了。
     */
    val ALLOWED_IDS: Set<String> = allPresets.map { it.id }.toSet()

    /**
     * 全部**合法的持久化皮肤 id** = [ALLOWED_IDS] + [CUSTOM_THEME_ID]。
     *
     * 为什么要有两个集合而不是一个：本仓有两类"皮肤"，它们在"色值从哪来"这件事上根本不同 ——
     * - 预设皮肤：色值是常量，[findById] 能取到；
     * - 自定义皮肤：色值是 `种子色 + buildCustomPalette` 在运行期算的，表里没有它。
     *
     * [normalizeThemeId] 判的是"这个 id 合不合法"，所以它必须用本集合；
     * 而 [findById] 判的是"表里有没有这套色"，所以 `findById("custom")` 返回 [Default]。
     * 两者**不是**行为分叉：`ThemeManager.getCurrentPalette()` 在 [findById] 之前就把
     * `custom + 有种子色` 这条路径接走了，[findById] 只会在"id 是 custom 但盘上没种子色"
     * 时被走到 —— 那时回落到默认恰恰是想要的行为（见该方法的 KDoc）。
     */
    val SELECTABLE_IDS: Set<String> = ALLOWED_IDS + CUSTOM_THEME_ID

    /**
     * 按 id 取皮肤，未知 id 回落到 [Default]（**不抛异常**）。
     *
     * 回落而不是抛：这个 id 来自 SharedPreferences，也就是**上一个版本的数据**。
     * 删皮肤是常规操作，让老用户升级后崩在冷启动的第一帧显然不可接受。
     *
     * 注意 `findById(CUSTOM_THEME_ID)` 同样返回 [Default] —— 自定义皮肤没有固定色值，
     * 见 [SELECTABLE_IDS] 的说明。
     */
    fun findById(id: String): ThemePalette =
        allPresets.find { it.id == id } ?: Default

    /**
     * 把任意持久化 id 规整成合法 id：[SELECTABLE_IDS] 内原样返回，其余一律折叠到 [DEFAULT_ID]。
     *
     * 会被折叠的历史脏值：空串 / null（键不存在）、老预设
     * `aurora` / `tech_blue` / `mint_green` / `dream_purple` / `vibrant_orange`
     * （出厂默认曾是 `aurora`）、**2026-09-05 傍晚删掉的 `orange`（橙色）与 `cyan`（青色）**，
     * 以及曾经的特殊 id `dynamic`。
     *
     * 本函数是"白名单外一律回落"的写法，所以删皮肤**不需要**为被删的 id 补任何分支 ——
     * `orange` / `cyan` 从 [allPresets] 移除的同一刻就自动落在白名单外，
     * 迁移即刻生效。上面把它们写进清单只是为了让"这两个 id 曾经存在过"可被搜索到。
     *
     * `dynamic` **刻意继续被折叠**：动态取色已回归，但它现在是一个独立开关
     * （`ThemeManager.dynamicEnabled`）而不是皮肤 id。老用户盘上若存着 `"dynamic"`，
     * 正确行为是"皮肤回落到默认 + 开关按它自己的键（默认关）取值"，
     * 而不是把它当成第 8 套皮肤 —— [findById] 找不到它会返回 [Default]，两者必须一致。
     *
     * `custom` 与 `dynamic` **处置相反**：它 2026-09-05 傍晚重新变成了一个**合法 id**
     * （见 [SELECTABLE_IDS]），因为"自定义"确实是用户在皮肤网格里选出来的一档，
     * 需要被持久化下来；而动态取色是一个正交的开关，与"选了哪套皮肤"并存。
     *
     * 独立成纯函数（而不是内联进 `ThemeManager.init`）是为了能在 JVM 单测里直接钉住
     * 迁移行为 —— 老用户升级不回落就是白屏或错色，这条不该靠真机翻页去发现。
     */
    fun normalizeThemeId(id: String?): String =
        if (id != null && id in SELECTABLE_IDS) id else DEFAULT_ID
}
