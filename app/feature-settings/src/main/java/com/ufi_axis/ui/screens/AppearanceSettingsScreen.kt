package com.ufi_axis.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Wallpaper
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.ufi_axis.ui.components.common.*
import com.ufi_axis.ui.components.common.UfiSlider
import com.ufi_axis.ui.components.common.UfiSwitch
import com.ufi_axis.ui.animation.page.UfiPageTransitions
import com.ufi_axis.ui.theme.*
import com.ufi_axis.viewmodel.MainViewModel
import kotlin.math.roundToInt

/**
 * 外观设置页（v2 2026-08-11：入口 + 弹窗重构）。
 *
 * 设计：各项设置以"入口卡片"展示（图标 + 标题 + 当前状态 + 右 chevron），
 * 点击行 → 弹 UfiCustomDialog 进行具体设置，确认后生效（草稿 - 确认模式，
 * 对齐服务器页 / 设备控制页风格）。
 *
 * ## 2026-09-05：「主题皮肤」入口恢复 + 新增「动态取色」开关
 *
 * 上午皮肤收敛为唯一一套时，整条「主题皮肤」入口 + 弹窗被**删掉**（不是留成只读）——
 * 单选项的选择器在仓内口径里就是假开关。下午拿到设计稿加回 8 套彩色皮肤（共 9 套），
 * 入口随之恢复为**可选的皮肤网格**，UI 直接复用既有的 [UfiOptionGrid] / [UfiOptionItem]
 * （`leading` 槽画一个 accent 色点），没有新造选择器组件。
 *
 * 同时新增「动态取色」开关（Material You，从壁纸取色）。两者互斥，处置口径：
 * - 动态取色**开启**时，「主题皮肤」入口 `enabled = false` 且 `onClick = null`
 *   （真的不可点，不是只变灰还能按），副标题改为**说明原因**
 *   「动态取色已开启，皮肤由壁纸决定」—— 置灰必须带原因，不能让用户猜；
 * - 动态取色在**当前设备不可用**时（取不到 `system_accent1_*` 等平台资源），
 *   开关本身 `enabled = false` 并写明「需要 Android 12 及以上」。
 *   本仓 `minSdk = 31` 所以版本这一半恒成立，真正会失败的是资源探测
 *   （见 `DynamicPalette.isDynamicColorAvailable`），两者共用同一个入口判断，
 *   保证「UI 显示状态与所有闸门一致」这条硬规则不被绕过。
 *
 * ## 2026-09-05 傍晚：皮肤收敛到 7 套 + 新增「自定义」取色器
 *
 * 橙色 / 青色两套按产品要求删除（皮肤网格遍历 `allPresets`，所以这里零改动）。
 * 同时「自定义」皮肤真正落地：皮肤网格末尾**追加**一格，点它进 [AppearanceDialog.CUSTOM_COLOR]
 * 取色器（HSL 三滑块 + 诚实预览，见 [CustomColorPreview]）。
 *
 * 三处刻意的设计，都在各自的调用点有长注释：
 * - 自定义那一格的 `onSelect` **不落草稿而是直接换弹窗** —— 「草稿 = custom 但还没有种子色」
 *   是一个确认了也没颜色的半套状态；
 * - 预览显示的是**推导后的整套配色**（页面底 / 卡面 / 两级正文 / accent 实底 + 其上的文字），
 *   而不是种子色本身。只显示种子色会误导：种子色只决定 accent，而且还会被明度夹取；
 * - 确认按钮由 [paletteContrastViolations] 把关，算不出合格解就禁用并说明原因，
 *   不静默给出一套不合格的配色。
 *
 * 「自定义」**不需要**为动态取色再加一道置灰判断：它是皮肤网格里的一格，
 * 网格在弹窗里，而弹窗的唯一入口是那条已经被置灰的「主题皮肤」行。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppearanceSettingsScreen(
    viewModel: MainViewModel,
    navController: NavHostController
) {
    val context = LocalContext.current
    val themeManager = remember { ThemeManager(context, observeExternal = false) }
    val themeMode by themeManager.themeMode.collectAsState()
    val transitionDurationMs by themeManager.transitionDurationMs.collectAsState()
    val blurEnabled by themeManager.blurEnabled.collectAsState()
    val pageTransitionId by themeManager.pageTransition.collectAsState()
    val uiScalePercent by themeManager.uiScalePercent.collectAsState()
    val selectedThemeId by themeManager.selectedThemeId.collectAsState()
    val dynamicEnabled by themeManager.dynamicEnabled.collectAsState()
    // 「自定义」皮肤的种子色（ARGB Int；[ThemeManager.CUSTOM_SEED_UNSET] = 从未选过）。
    // 走 companion 共享 flow，所以取色器点确认后本页与 MainActivity **同时**看到新值。
    val customSeedColor by themeManager.customSeedColor.collectAsState()
    // 能力探测要读平台资源，结果在 App 存活期内不会变（换壁纸只改色值、不改"是否支持"），
    // 所以 remember 一次即可，不必每次重组都探。
    val dynamicSupported = remember { themeManager.isDynamicColorSupported() }

    // v2：当前打开的弹窗类型（null = 无弹窗）
    var openDialog by remember { mutableStateOf<AppearanceDialog?>(null) }

    UfiScreenScaffold(title = "外观", navController = navController, showBack = true) { padding ->
        UfiPageBackground(modifier = Modifier.padding(padding)) {
            // 2026-08-30：4 个入口卡改用公共 UfiSettingsRowCard + UfiSettingsItem
            //（原来手搓 rowModifier 四链 + 逐个复制 Row/Icon/Column/Text 结构）。
            Column(
                Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // ═══ 入口 1：外观模式 ═══
                UfiSettingsRowCard {
                    UfiSettingsItem(
                        icon = AppIconThemeMode,
                        title = "外观模式",
                        description = when (themeMode) {
                            ThemeMode.LIGHT -> "强制浅色"
                            ThemeMode.DARK -> "强制深色"
                            else -> "自动跟随系统"
                        },
                        onClick = { openDialog = AppearanceDialog.THEME_MODE },
                        trailing = { UfiSettingsChevron() }
                    )
                }

                // ═══ 入口 2：主题皮肤（7 套预设 + 自定义；动态取色开启时禁用）═══
                //
                // enabled=false **同时**把 onClick 置 null：只传 enabled 只会把文字变灰，
                // 行仍然可点（UfiSettingsItem 的 enabled 只影响文字色），
                // 那就成了"看起来禁用、按下去还弹窗"的假禁用。
                //
                // 「自定义」也在这道闸门后面：它是**皮肤网格里的一格**，网格在弹窗里，
                // 而弹窗的唯一入口就是这一行。所以动态取色开启时它自动不可达，
                // 不需要为它再加一处判断（那反而会多一个可能漏改的地方）。
                UfiSettingsRowCard {
                    UfiSettingsItem(
                        icon = Icons.Default.Palette,
                        title = "主题皮肤",
                        description = if (dynamicEnabled) {
                            "动态取色已开启，皮肤由壁纸决定"
                        } else {
                            // findById("custom") 会回落到 Default（自定义没有固定色值），
                            // 所以这里必须先判 id，否则选了自定义之后摘要会显示成"默认"。
                            val name = if (selectedThemeId == CUSTOM_THEME_ID) {
                                CUSTOM_THEME_NAME
                            } else {
                                ThemePresets.findById(selectedThemeId).name
                            }
                            "$name · 共 ${ThemePresets.SELECTABLE_IDS.size} 套"
                        },
                        enabled = !dynamicEnabled,
                        onClick = if (dynamicEnabled) null else {
                            { openDialog = AppearanceDialog.THEME_SKIN }
                        },
                        trailing = { UfiSettingsChevron() }
                    )
                }

                // ═══ 入口 3：动态取色（Material You）═══
                //
                // 不走"入口 + 弹窗"而是直接一个开关：它只有开/关两态，
                // 为两态开一个弹窗是多一次点击换零信息。
                UfiSettingsRowCard {
                    UfiSettingsItem(
                        icon = Icons.Default.Wallpaper,
                        title = "动态取色",
                        description = when {
                            !dynamicSupported -> "当前系统未提供壁纸取色（需要 Android 12 及以上）"
                            dynamicEnabled -> "已开启 · 配色跟随手机壁纸生成"
                            else -> "从手机壁纸生成整套配色（Material You）"
                        },
                        enabled = dynamicSupported,
                        onClick = if (dynamicSupported) {
                            { themeManager.setDynamicEnabled(!dynamicEnabled) }
                        } else null,
                        trailing = {
                            UfiSwitch(
                                checked = dynamicEnabled,
                                onCheckedChange = { themeManager.setDynamicEnabled(it) },
                                enabled = dynamicSupported
                            )
                        }
                    )
                }

                // ═══ 入口 4：切换动画 ═══
                UfiSettingsRowCard {
                    UfiSettingsItem(
                        icon = AppIconTransition,
                        title = "切换动画",
                        description = buildString {
                            append(UfiPageTransitions.byId(pageTransitionId).displayName)
                            append(" · ")
                            // 2026-09-04（P2f）：新增「关闭」档，摘要里必须能看出来，
                            // 否则关掉之后这里会显示成 "0ms"，像是坏了。
                            if (transitionDurationMs <= ThemeManager.TRANSITION_DURATION_OFF) {
                                append("转场已关闭")
                            } else {
                                append(transitionDurationMs)
                                append("ms")
                            }
                            if (blurEnabled) append(" · 模糊开启")
                        },
                        onClick = { openDialog = AppearanceDialog.PAGE_TRANSITION },
                        trailing = { UfiSettingsChevron() }
                    )
                }

                // ═══ 入口 5：界面缩放 ═══
                UfiSettingsRowCard {
                    UfiSettingsItem(
                        icon = Icons.Default.FormatSize,
                        title = "界面缩放",
                        description = buildString {
                            append(uiScalePercent)
                            append("%")
                            if (uiScalePercent == ThemeManager.DEFAULT_UI_SCALE_PERCENT) append(" · 默认")
                        },
                        onClick = { openDialog = AppearanceDialog.UI_SCALE },
                        trailing = { UfiSettingsChevron() }
                    )
                }

                // 组件画廊入口 2026-09-05 从本页移走：它是开发自用的预览页，对普通用户无意义，
                // 常驻在「外观」里只会让设置项变多。现在挂到「关于」页版本号连点 5 次解锁的
                // 隐藏「调试」组里（与调试日志 / 运行诊断同组），复用那一套已有门禁，不新造开关。

                Spacer(Modifier.height(Spacing.Large))
            }
        }
    }

    // ════════════════════════════════════════════════════════════════
    // 弹窗区（公共弹窗 UfiCustomDialog，统一草稿 - 确认模式）
    // ════════════════════════════════════════════════════════════════

    val palette = LocalResolvedPalette.current

    when (openDialog) {
        AppearanceDialog.THEME_MODE -> {
            var draftMode by remember { mutableStateOf(themeMode) }
            UfiCustomDialog(
                visible = true,
                onDismiss = { openDialog = null },
                title = "外观模式",
                icon = rememberVectorPainter(AppIconThemeMode),
                confirmButton = null,
                dismissButton = null,
                showCloseButton = false
            ) {
                Text(
                    "选择界面整体明暗风格。自动模式会跟随系统设置切换。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = palette.textSecondary
                )
                Spacer(Modifier.height(Spacing.Medium))
                UfiSingleChipSelector(
                    options = listOf("auto" to "自动", "light" to "浅色", "dark" to "深色"),
                    selectedValue = draftMode.name.lowercase(),
                    onSelect = { v ->
                        draftMode = when (v) {
                            "light" -> ThemeMode.LIGHT
                            "dark" -> ThemeMode.DARK
                            else -> ThemeMode.AUTO
                        }
                    }
                )
                Spacer(Modifier.height(Spacing.Medium))
                DialogButtonRow(
                    confirmText = "确认",
                    onConfirm = {
                        if (draftMode != themeMode) themeManager.setThemeMode(draftMode)
                        openDialog = null
                    },
                    dismissText = "取消",
                    onDismiss = { openDialog = null }
                )
            }
        }

        AppearanceDialog.THEME_SKIN -> {
            // 草稿 - 确认模式：与其他三个弹窗一致。皮肤切换是整树换色，
            // 边选边生效会让弹窗自己也跟着变色、用户分不清"我选的是哪一格"。
            var draftThemeId by remember { mutableStateOf(selectedThemeId) }
            val hasCustomSeed = customSeedColor != ThemeManager.CUSTOM_SEED_UNSET
            UfiCustomDialog(
                visible = true,
                onDismiss = { openDialog = null },
                title = "主题皮肤",
                icon = rememberVectorPainter(Icons.Default.Palette),
                confirmButton = null,
                dismissButton = null,
                showCloseButton = false
            ) {
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                    Text(
                        "选择配色方案。每套皮肤自带浅色 / 深色两组取值，随上面的「外观模式」切换。" +
                            "最后一格是「自定义」——点它进取色器自己挑主色。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = palette.textSecondary
                    )
                    Spacer(Modifier.height(Spacing.Medium))
                    // 直接遍历 ThemePresets.allPresets：加皮肤只改预设表，这里零改动。
                    // 复用既有的 UfiOptionGrid（弹窗内双栏选项网格），不新造选择器组件。
                    //
                    // 「自定义」是**追加**的一格而不是表里的一项：它没有固定色值
                    //（见 ThemePresets.allPresets 的 KDoc）。它的 onSelect 也与别的格不同 ——
                    // 别的格只改草稿，它直接换到取色器（不先落草稿：没选过种子色时
                    //「草稿 = custom」是一个确认了也没颜色的半套状态）。
                    UfiOptionGrid(
                        options = ThemePresets.allPresets.map { preset ->
                            UfiOptionItem(
                                value = preset.id,
                                label = preset.name,
                                leading = {
                                    ThemeSwatch(
                                        color = preset.resolve(palette.isDark).accent,
                                        borderColor = palette.cardBorder
                                    )
                                }
                            )
                        } + UfiOptionItem(
                            value = CUSTOM_THEME_ID,
                            label = if (hasCustomSeed) CUSTOM_THEME_NAME else "$CUSTOM_THEME_NAME…",
                            leading = {
                                ThemeSwatch(
                                    // 色点显示的是"这个种子色**实际**会变成的 accent"（夹取后的值），
                                    // 不是种子色本身 —— 与取色器里的预览同一口径。
                                    color = if (hasCustomSeed) {
                                        buildCustomPalette(Color(customSeedColor))
                                            .resolve(palette.isDark).accent
                                    } else {
                                        palette.textSecondary
                                    },
                                    borderColor = palette.cardBorder
                                )
                            }
                        ),
                        selectedValue = draftThemeId,
                        onSelect = { id ->
                            if (id == CUSTOM_THEME_ID) {
                                openDialog = AppearanceDialog.CUSTOM_COLOR
                            } else {
                                draftThemeId = id
                            }
                        },
                        columns = 2
                    )
                    Spacer(Modifier.height(Spacing.Medium))
                    DialogButtonRow(
                        confirmText = "确认",
                        onConfirm = {
                            if (draftThemeId != selectedThemeId) themeManager.setTheme(draftThemeId)
                            openDialog = null
                        },
                        dismissText = "取消",
                        onDismiss = { openDialog = null }
                    )
                }
            }
        }

        AppearanceDialog.CUSTOM_COLOR -> {
            // ── 取色器：HSL 三滑块 + 诚实预览 ──
            //
            // 为什么是三滑块而不是"色相条 + 饱和明度面板"：面板要自绘一张二维渐变位图 +
            // 自己处理拖拽命中，那是一个**新组件**（而且是本仓唯一一个二维手势控件，
            // 要单独处理边界吸附、按压反馈、无障碍语义）。三滑块用现成的 [UfiSlider]
            // 就能表达同样的三个自由度，而且每一维都有精确的数值标签 ——
            // 用户想复现某个色时可以照数值调，面板做不到这一点。
            //
            // 三个 draft 存的是 HSL 分量而不是一个 Color：从 Color 反解 HSL 在灰阶
            // （s = 0 时色相未定义）与极端明度（l = 0/1 时色相与饱和度都丢失）处会丢信息，
            // 表现就是"把明度拖到 0 再拖回来，色相变成红色了"。存分量则无损。
            val initialHsl = remember(customSeedColor) {
                if (customSeedColor != ThemeManager.CUSTOM_SEED_UNSET) {
                    Color(customSeedColor).toHsl()
                } else {
                    // 从未选过：拿**当前生效**的 accent 当起点，用户看到的第一屏就是
                    // "现在这个颜色"，比从一个随机的红色开始更容易理解。
                    palette.accent.toHsl()
                }
            }
            var draftHue by remember { mutableFloatStateOf(initialHsl.hue) }
            var draftSat by remember { mutableFloatStateOf(initialHsl.saturation) }
            var draftLight by remember { mutableFloatStateOf(initialHsl.lightness) }

            val seed = hslColor(draftHue, draftSat, draftLight)
            val preview = remember(seed) { buildCustomPalette(seed) }
            val violations = remember(preview) { paletteContrastViolations(preview) }
            val clamped = customSeedLightnessIsClamped(seed)

            UfiCustomDialog(
                visible = true,
                onDismiss = { openDialog = AppearanceDialog.THEME_SKIN },
                title = "自定义配色",
                icon = rememberVectorPainter(Icons.Default.Palette),
                confirmButton = null,
                dismissButton = null,
                showCloseButton = false
            ) {
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                    Text(
                        "挑一个主色，整套配色（页面底 / 卡面 / 正文 / 强调）由它推导。" +
                            "正文与卡面的明度是固定的，所以无论挑什么颜色都读得清。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = palette.textSecondary
                    )
                    Spacer(Modifier.height(Spacing.Medium))

                    CustomColorPreview(
                        preview = preview,
                        isDark = palette.isDark,
                        seed = seed,
                        borderColor = palette.cardBorder
                    )

                    Spacer(Modifier.height(Spacing.Medium))
                    UfiSlider(
                        value = draftHue,
                        onValueChange = { draftHue = it },
                        valueRange = 0f..HUE_MAX,
                        label = "色相",
                        valueLabel = "${draftHue.roundToInt()}°"
                    )
                    UfiSlider(
                        value = draftSat,
                        onValueChange = { draftSat = it },
                        valueRange = 0f..1f,
                        label = "饱和度",
                        valueLabel = "${(draftSat * PERCENT).roundToInt()}%"
                    )
                    UfiSlider(
                        value = draftLight,
                        onValueChange = { draftLight = it },
                        valueRange = 0f..1f,
                        label = "明度",
                        valueLabel = "${(draftLight * PERCENT).roundToInt()}%"
                    )

                    // 夹取提示：明度越界时 accent 会被收进可用区间，预览里已经显示的是
                    // 夹取后的真实颜色，但**必须配一句解释** —— 否则用户看到的是
                    // "滑块还在动、色块不再变"，那就是一个坏了的滑块。
                    if (clamped) {
                        Text(
                            "明度已收进可用区间（浅色态 " +
                                "${(CUSTOM_ACCENT_LIGHT_L_MIN * PERCENT).roundToInt()}~" +
                                "${(CUSTOM_ACCENT_LIGHT_L_MAX * PERCENT).roundToInt()}%、深色态 " +
                                "${(CUSTOM_ACCENT_DARK_L_MIN * PERCENT).roundToInt()}~" +
                                "${(CUSTOM_ACCENT_DARK_L_MAX * PERCENT).roundToInt()}%）：" +
                                "太亮或太暗的强调色会和卡面 / 页面底糊在一起。上面预览的是实际效果。",
                            style = MaterialTheme.typography.labelSmall,
                            color = palette.textSecondary,
                            modifier = Modifier.padding(horizontal = Spacing.XLarge)
                        )
                    }
                    // 硬指标闸门：算不出合格解时**禁止确认并说明原因**，不静默给一套不合格配色。
                    // 按当前推导公式这一支不可达（`CustomPaletteTest` 对全域种子扫过），
                    // 留着是因为"我证明过所以不检查"在有人改了推导常数之后就不成立了。
                    if (violations.isNotEmpty()) {
                        Text(
                            "这个颜色推出的配色达不到对比度要求，无法确认：" +
                                violations.joinToString("；") + "。请换一个颜色。",
                            style = MaterialTheme.typography.labelSmall,
                            color = palette.error,
                            modifier = Modifier.padding(horizontal = Spacing.XLarge)
                        )
                    }

                    Spacer(Modifier.height(Spacing.Medium))
                    DialogButtonRow(
                        confirmText = "确认",
                        enabled = violations.isEmpty(),
                        onConfirm = {
                            // 两个动作、两个键：先落种子色，再把皮肤切到 custom。
                            // 顺序无所谓（两个 flow 各自独立），但**都要写** ——
                            // 只写种子色则皮肤还停在旧的那套，用户会以为没生效。
                            themeManager.setCustomSeedColor(customSeedToArgb(seed))
                            themeManager.setTheme(CUSTOM_THEME_ID)
                            openDialog = null
                        },
                        dismissText = "返回",
                        onDismiss = { openDialog = AppearanceDialog.THEME_SKIN }
                    )
                }
            }
        }

        AppearanceDialog.PAGE_TRANSITION -> {
            var draftTransitionId by remember { mutableStateOf(pageTransitionId) }
            // P2f：0 是「关闭」的持久化表示，但滑块只在 150..600 之间取值。
            // 因此拆成两个 draft：开关 + 时长。关闭状态下时长保留上一次的可用值
            // （落到默认档），这样用户关掉再打开不会丢失自己调过的时长。
            var draftTransitionOff by remember {
                mutableStateOf(transitionDurationMs <= ThemeManager.TRANSITION_DURATION_OFF)
            }
            var draftDuration by remember {
                mutableIntStateOf(
                    if (transitionDurationMs <= ThemeManager.TRANSITION_DURATION_OFF) {
                        ThemeManager.TRANSITION_DURATION_DEFAULT_MS
                    } else {
                        transitionDurationMs
                    }
                )
            }
            var draftBlur by remember { mutableStateOf(blurEnabled) }


            UfiCustomDialog(
                visible = true,
                onDismiss = { openDialog = null },
                title = "切换动画",
                icon = rememberVectorPainter(AppIconTransition),
                confirmButton = null,
                dismissButton = null,
                showCloseButton = false
            ) {
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                    Text(
                        "主页底部 Tab 切换时的过渡效果。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = palette.textSecondary
                    )
                    Spacer(Modifier.height(Spacing.Medium))

                    // 切换动画类型选择器：双栏网格，减小弹窗体积。
                    // 数据源直接来自 UfiPageTransitions 注册表（内置 6 种 + 任意运行时注册）。
                    val transitions = UfiPageTransitions.all()
                    UfiOptionGrid(
                        options = transitions.map { t ->
                            UfiOptionItem(
                                value = t.id,
                                label = t.displayName
                            )
                        },
                        selectedValue = draftTransitionId,
                        onSelect = { draftTransitionId = it },
                        columns = 2
                    )

                    Spacer(Modifier.height(Spacing.Medium))
                    // P2f（2026-09-04）：转场时长新增「关闭」档。
                    // 为什么是独立开关而不是把滑块下限拉到 0：0 与 150 之间**刻意没有中间档**
                    // （150ms 以下的整屏转场接近瞬变，只会被感知成闪帧，比干脆关掉更差），
                    // 塞进滑块会得到一个不等距的档位表，滑到那一段还会显示成"0ms 的动画"。
                    // 关闭时隐藏滑块，避免出现"开关已关、下面还让你调时长"的自相矛盾。
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = Spacing.XLarge),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            "关闭转场动画",
                            style = UfiTextStyles.listItemTitle,
                            color = palette.textPrimary
                        )
                        UfiSwitch(
                            checked = draftTransitionOff,
                            onCheckedChange = { draftTransitionOff = it }
                        )
                    }

                    if (!draftTransitionOff) {
                        Spacer(Modifier.height(Spacing.Medium))
                        Text(
                            "动画时长",
                            style = MaterialTheme.typography.labelMedium,
                            color = palette.textSecondary
                        )
                        UfiSlider(
                            value = draftDuration.toFloat(),
                            onValueChange = { draftDuration = it.roundToInt() },
                            // 区间与档数由 ThemeManager 的 MIN/MAX 推导，不再写死 150f..600f：
                            // 150,200,…,600 共 9 档（steps = 档数 - 1 = 8）。
                            valueRange = ThemeManager.TRANSITION_DURATION_MIN_MS.toFloat()..
                                ThemeManager.TRANSITION_DURATION_MAX_MS.toFloat(),
                            steps = (ThemeManager.TRANSITION_DURATION_MAX_MS -
                                ThemeManager.TRANSITION_DURATION_MIN_MS) / 50 - 1,
                            label = "",
                            valueLabel = "${draftDuration}ms",
                        )
                    }

                    Spacer(Modifier.height(Spacing.Medium))
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = Spacing.XLarge),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            "过渡模糊",
                            style = UfiTextStyles.listItemTitle,
                            color = palette.textPrimary
                        )
                        UfiSwitch(
                            checked = draftBlur,
                            onCheckedChange = { draftBlur = it }
                        )
                    }

                    Spacer(Modifier.height(Spacing.Medium))
                    DialogButtonRow(
                        confirmText = "确认",
                        onConfirm = {
                            if (draftTransitionId != pageTransitionId) themeManager.setPageTransition(draftTransitionId)
                            // P2f：关闭 → 写 0（ThemeManager 会把它规整成 TRANSITION_DURATION_OFF）；
                            // 未关闭 → 写滑块值。只有真的变了才落盘，与其他 draft 一致。
                            val targetDuration =
                                if (draftTransitionOff) ThemeManager.TRANSITION_DURATION_OFF else draftDuration
                            if (targetDuration != transitionDurationMs) {
                                themeManager.setTransitionDurationMs(targetDuration)
                            }
                            if (draftBlur != blurEnabled) themeManager.setBlurEnabled(draftBlur)
                            openDialog = null
                        },
                        dismissText = "取消",
                        onDismiss = { openDialog = null }
                    )
                }
            }
        }

        AppearanceDialog.UI_SCALE -> {
            // 草稿 - 确认模式：拖动过程中不落盘。缩放一旦生效是整树重排，若做实时预览，
            // 弹窗自己也会跟着缩、滑块位置在手指下漂移，几乎没法拖准。
            var draftScale by remember { mutableStateOf(uiScalePercent) }
            UfiCustomDialog(
                visible = true,
                onDismiss = { openDialog = null },
                title = "界面缩放",
                icon = rememberVectorPainter(Icons.Default.FormatSize),
                confirmButton = null,
                dismissButton = null,
                showCloseButton = false
            ) {
                Text(
                    "整体缩放界面尺寸，文字与间距一起等比变化。系统的字体大小设置仍按比例生效。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = palette.textSecondary
                )
                Spacer(Modifier.height(Spacing.Medium))
                UfiSlider(
                    value = draftScale.toFloat(),
                    onValueChange = { draftScale = it.roundToInt() },
                    // 区间与 steps 由 ThemeManager 的 MIN/MAX 推导：90~120 每 5 一档共 7 档
                    valueRange = ThemeManager.UI_SCALE_PERCENT_MIN.toFloat()..ThemeManager.UI_SCALE_PERCENT_MAX.toFloat(),
                    steps = (ThemeManager.UI_SCALE_PERCENT_MAX - ThemeManager.UI_SCALE_PERCENT_MIN) / 5 - 1,
                    label = "",
                    valueLabel = "${draftScale}%"
                )
                Text(
                    when {
                        draftScale < ThemeManager.DEFAULT_UI_SCALE_PERCENT ->
                            "偏小：按钮与图标会小于系统建议的触摸尺寸"
                        draftScale == ThemeManager.DEFAULT_UI_SCALE_PERCENT -> "默认 100%"
                        else -> "偏大：部分页面的长文案可能折行增多"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = palette.textSecondary.copy(alpha = 0.7f),
                    modifier = Modifier.padding(horizontal = Spacing.XLarge)
                )
                Spacer(Modifier.height(Spacing.Medium))
                DialogButtonRow(
                    confirmText = "确认",
                    onConfirm = {
                        if (draftScale != uiScalePercent) themeManager.setUiScalePercent(draftScale)
                        openDialog = null
                    },
                    dismissText = "取消",
                    onDismiss = { openDialog = null }
                )
            }
        }

        null -> {}
    }
}

/** 外观页弹窗类型 */
private enum class AppearanceDialog { THEME_MODE, THEME_SKIN, PAGE_TRANSITION, UI_SCALE, CUSTOM_COLOR }

/** 色相滑块的上界（度）。放在这里而不是内联 360f，是为了让三个滑块的区间口径都有名字。 */
private const val HUE_MAX: Float = 360f

/** 0~1 归一值转百分比显示用的乘数。 */
private const val PERCENT: Float = 100f

/**
 * 皮肤色卡：一个圆形色点，供 [UfiOptionGrid] 的 `leading` 槽用。
 *
 * 2026-09-05 傍晚由 `(preset, isDark)` 改为直接吃一个 [Color]：加了「自定义」那一格之后，
 * 色点的来源有两种（预设表里的 accent / 种子色推出来的 accent），而且自定义还有
 * "没设置过"的第三态。让调用点各自算出要显示的颜色比在这里塞一个三分支判断清楚得多。
 *
 * 调用点一律传 `resolve(isDark).accent` 而不是固定读 `accentLight` —— 用户在深色模式下
 * 看到的应该是那套皮肤**深色态**的主色。7 套里 6 套的明暗 accent 同值，
 * 但 `default` 的不同（浅色 #222222 / 深色 #555555），固定读浅色会让默认那套的
 * 色点在深色模式下比实际更黑。
 *
 * 描边用 `cardBorder` 兜住"色点与弹窗底同色"的极端情况（浅色态白卡 + 近白色点）。
 */
@Composable
private fun ThemeSwatch(color: Color, borderColor: Color) {
    Box(
        Modifier
            .size(Spacing.IconSizeSmall)
            .clip(CircleShape)
            .background(color, CircleShape)
            .border(Spacing.ButtonBorderWidth, borderColor, CircleShape)
    )
}

/**
 * 取色器的**诚实预览**：显示"这个种子色实际会变成什么样"，而不是只显示种子色本身。
 *
 * 为什么不能只画一个种子色色块：种子色只决定 `accent`，而且还会被明度夹取
 * （见 `CUSTOM_ACCENT_LIGHT_L_MIN`）。用户真正会看到的是"页面底上摆一张卡、
 * 卡上有两级正文、还有一块 accent 实底"这个组合。只给种子色的话，
 * 挑一个很亮的黄，用户以为整个界面会是亮黄的，确认之后发现只有按钮是黄的 ——
 * 那不是"预览"，那是误导。
 *
 * 所以这里按**当前明暗态**渲染四样东西，四样都直接取自 [preview] 的解析结果：
 * 1. 外层底 = `pageBg`（页面底）
 * 2. 内层卡 = `cardBg` + `divider` 描边（卡面与页面底的层次是硬指标之一）
 * 3. 卡上两行字 = `textPrimary` / `textSecondary`（正文两级）
 * 4. 一块 accent 实底 + 压在它上面的 `onAccent` 文字（实底前景的对比度也是硬指标）
 *
 * 另外把种子色本身也画成一个小圆点摆在标题行，让"我选的色"与"它变成的 accent"
 * 能被直接对比 —— 夹取发生时这两个点会明显不同，那正是需要用户看见的信息。
 */
@Composable
private fun CustomColorPreview(
    preview: ThemePalette,
    isDark: Boolean,
    seed: Color,
    borderColor: Color
) {
    val p = preview.resolve(isDark)
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.XLarge)
            .clip(UfiCardDefaults.shape)
            .background(p.pageBg)
            .border(Spacing.ButtonBorderWidth, borderColor, UfiCardDefaults.shape)
            .padding(Spacing.Large),
        verticalArrangement = Arrangement.spacedBy(Spacing.Medium)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.Small)
        ) {
            ThemeSwatch(color = seed, borderColor = borderColor)
            Text(
                "选中的色",
                style = MaterialTheme.typography.labelSmall,
                color = p.textSecondary
            )
            Spacer(Modifier.weight(1f))
            Text(
                if (isDark) "深色态预览" else "浅色态预览",
                style = MaterialTheme.typography.labelSmall,
                color = p.textSecondary
            )
        }
        Column(
            Modifier
                .fillMaxWidth()
                .clip(UfiCardDefaults.subtleShape)
                .background(p.cardBg)
                .border(Spacing.ButtonBorderWidth, p.divider, UfiCardDefaults.subtleShape)
                .padding(Spacing.Large),
            verticalArrangement = Arrangement.spacedBy(Spacing.Small)
        ) {
            Text("正文示例", style = UfiTextStyles.listItemTitle, color = p.textPrimary)
            Text(
                "副文案：这一行用的是 textSecondary。",
                style = MaterialTheme.typography.labelSmall,
                color = p.textSecondary
            )
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(Spacing.ChipHeight)
                    .clip(UfiCardDefaults.buttonShape)
                    .background(p.accent),
                contentAlignment = Alignment.Center
            ) {
                Text("主按钮", style = UfiTextStyles.listItemTitle, color = p.onAccent)
            }
        }
    }
}


// ───────────────────────────────────────────────────────────
// v2（2026-08-11）：手绘 SVG 矢量图标（对齐设备控制页风格）
// 单色 path（Color.Black 会被 Icon tint 覆盖），24×24 viewport
// ───────────────────────────────────────────────────────────

/** 外观模式：左半填充的圆（表示明暗/跟随系统） */
private val AppIconThemeMode: ImageVector = ImageVector.Builder(
    name = "AppIconThemeMode", defaultWidth = Spacing.IconCanvas, defaultHeight = Spacing.IconCanvas,
    viewportWidth = 24f, viewportHeight = 24f
).apply {
    path(stroke = SolidColor(Color.Black), strokeLineWidth = 2f) {
        moveTo(12f, 3f)
        arcTo(9f, 9f, 0f, true, true, 12f, 21f)
        arcTo(9f, 9f, 0f, true, true, 12f, 3f)
    }
    path(fill = SolidColor(Color.Black)) {
        moveTo(12f, 3f)
        arcTo(9f, 9f, 0f, false, false, 12f, 21f)
        close()
    }
}.build()

/** 切换动画：双右箭头（>> 表示过渡/前进） */
private val AppIconTransition: ImageVector = ImageVector.Builder(
    name = "AppIconTransition", defaultWidth = Spacing.IconCanvas, defaultHeight = Spacing.IconCanvas,
    viewportWidth = 24f, viewportHeight = 24f
).apply {
    path(
        stroke = SolidColor(Color.Black),
        strokeLineWidth = 2f,
        strokeLineCap = StrokeCap.Round
    ) {
        moveTo(7f, 5f); lineTo(13f, 12f); lineTo(7f, 19f)
        moveTo(14f, 5f); lineTo(20f, 12f); lineTo(14f, 19f)
    }
}.build()

