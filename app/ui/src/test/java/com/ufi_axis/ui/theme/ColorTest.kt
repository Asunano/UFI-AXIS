package com.ufi_axis.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Color.kt 纯函数单元测试（JVM 单元测试，无需 Robolectric / Android 运行时）。
 *
 * 覆盖：
 *  - ChartColors(isDark)：浅/暗双主题返回不同域色，且与语义状态色（success/warning/error）不撞色
 *  - StatusRamp.good/warn/bad(palette)：正确映射到 ResolvedPalette 的语义色
 *  - accentStrong / accentMuted（2026-09-03 P1b 新增）：明暗方向与弱化方向正确
 *
 * 依赖（已通过 testImplementation(libs.junit) 提供，见 app/ui-theme/build.gradle.kts）：
 *  - androidx.compose.ui:ui-graphics（androidx.compose.ui.graphics.Color，纯 Kotlin，可在 JVM 单测运行）
 *  - junit:junit
 */
class ColorTest {

    @Test
    fun `ChartColors light and dark return different values for every field`() {
        val light = ChartColors(isDark = false)
        val dark = ChartColors(isDark = true)

        assertNotEquals(light.cpu, dark.cpu)
        assertNotEquals(light.memory, dark.memory)
        assertNotEquals(light.trafficRx, dark.trafficRx)
        assertNotEquals(light.trafficTx, dark.trafficTx)
        assertNotEquals(light.signalRsrp, dark.signalRsrp)
        assertNotEquals(light.signalSinr, dark.signalSinr)
        assertNotEquals(light.battery, dark.battery)
        assertNotEquals(light.temperature, dark.temperature)
    }

    @Test
    fun `ChartColors never collide with semantic status colors in either theme`() {
        for (isDark in listOf(false, true)) {
            val palette = testPalette(isDark)
            val chart = ChartColors(isDark = isDark)
            val chartColors = with(chart) {
                listOf(cpu, memory, trafficRx, trafficTx, signalRsrp, signalSinr, battery, temperature)
            }
            val semantic = listOf(palette.success, palette.warning, palette.error)
            for (c in chartColors) {
                for (s in semantic) {
                    assertNotEquals(
                        "图表域色不应与语义状态色(success/warning/error)撞色 (isDark=$isDark)",
                        s,
                        c
                    )
                }
            }
        }
    }

    @Test
    fun `StatusRamp maps good-warn-bad to palette success-warning-error`() {
        val light = testPalette(isDark = false)
        assertEquals(light.success, StatusRamp.good(light))
        assertEquals(light.warning, StatusRamp.warn(light))
        assertEquals(light.error, StatusRamp.bad(light))

        val dark = testPalette(isDark = true)
        assertEquals(dark.success, StatusRamp.good(dark))
        assertEquals(dark.warning, StatusRamp.warn(dark))
        assertEquals(dark.error, StatusRamp.bad(dark))
    }

    /**
     * P1a 回归：ResolvedPalette 的语义色必须来自 ThemePalette 的色槽，不能再是写死值。
     * 用一套刻意"不像语义色"的自定义槽值验证链路真的通了。
     */
    @Test
    fun `semantic slots flow from ThemePalette into ResolvedPalette`() {
        val palette = ThemePresets.Default.copy(
            onAccentLight = Color(0xFF010203),
            errorLight = Color(0xFF040506),
            errorContainerLight = Color(0xFF070809),
            warningLight = Color(0xFF0A0B0C),
            successLight = Color(0xFF0D0E0F),
            metricNormalLight = Color(0xFF101112),
            scrimLight = Color(0xFF131415),
            // P1c 追加的三槽
            onErrorLight = Color(0xFF161718),
            switchTrackOffLight = Color(0xFF191A1B),
            switchThumbOffLight = Color(0xFF1C1D1E),
            // P2-中 追加的两槽（渐变 Hero 卡前景）
            onGradientLight = Color(0xFF1F2021),
            gradientMutedLight = Color(0xFF222324)
        )
        val resolved = palette.resolve(isDark = false)

        assertEquals(Color(0xFF010203), resolved.onAccent)
        assertEquals(Color(0xFF040506), resolved.error)
        assertEquals(Color(0xFF070809), resolved.errorContainer)
        assertEquals(Color(0xFF0A0B0C), resolved.warning)
        assertEquals(Color(0xFF0D0E0F), resolved.success)
        assertEquals(Color(0xFF101112), resolved.metricNormal)
        assertEquals(Color(0xFF131415), resolved.scrim)
        assertEquals(Color(0xFF161718), resolved.onError)
        assertEquals(Color(0xFF191A1B), resolved.switchTrackOff)
        assertEquals(Color(0xFF1C1D1E), resolved.switchThumbOff)
        assertEquals(Color(0xFF1F2021), resolved.onGradient)
        assertEquals(Color(0xFF222324), resolved.gradientMuted)
        // 从语义色派生的两个 metric 档也要跟着走
        assertEquals(Color(0xFF0A0B0C), resolved.metricWarning)
        assertEquals(Color(0xFF040506), resolved.metricCritical)
    }

    /**
     * P1a 回归：不传新色槽时，默认值必须与改造前写死的值完全一致（观感零变化）。
     *
     * 2026-09-05：原先取 `ThemePresets.Aurora`，该预设已随「只保留默认皮肤」一起删除，
     * 改取 [ThemePresets.Default]。断言的是**色槽默认值**，与具体哪套皮肤无关。
     */
    @Test
    fun `default semantic slots keep the pre-refactor hardcoded values`() {
        val light = ThemePresets.Default.resolve(isDark = false)
        val dark = ThemePresets.Default.resolve(isDark = true)

        assertEquals(Color.White, light.onAccent)
        assertEquals(Color.White, dark.onAccent)
        assertEquals(Color(0xFFE53935), light.error)
        assertEquals(Color(0xFFFF6B6B), dark.error)
        assertEquals(Color(0xFFFFEBEE), light.errorContainer)
        assertEquals(Color(0xFF3D1515), dark.errorContainer)
        assertEquals(Color(0xFFFF9800), light.warning)
        assertEquals(Color(0xFFFFB74D), dark.warning)
        assertEquals(Color(0xFF43A047), light.success)
        assertEquals(Color(0xFF66BB6A), dark.success)
        assertEquals(Color(0xFF1C1C1E), light.metricNormal)
        assertEquals(Color(0xFFF2F2F7), dark.metricNormal)
        assertEquals(Color.Black.copy(alpha = 0.35f), light.scrim)
        assertEquals(Color.Black.copy(alpha = 0.5f), dark.scrim)
        // P1c 追加的三槽：默认值同样必须与改造前的写死值逐位一致
        assertEquals(Color.White, light.onError)
        assertEquals(Color.White, dark.onError)
        assertEquals(Color(0xFFE0E0E0), light.switchTrackOff)
        assertEquals(Color.White.copy(alpha = 0.15f), dark.switchTrackOff)
        assertEquals(Color(0xFFFAFAFA), light.switchThumbOff)
        assertEquals(Color.White.copy(alpha = 0.5f), dark.switchThumbOff)
        // P2-中 追加的两槽：默认值必须仍是纯白（= 四个页面文件里原来写死的 Color.White），
        // 亮暗两套都一样——渐变卡的白色内容改造前从不区分明暗模式。
        assertEquals(Color.White, light.onGradient)
        assertEquals(Color.White, dark.onGradient)
        assertEquals(Color.White, light.gradientMuted)
        assertEquals(Color.White, dark.gradientMuted)
    }

    /**
     * P1b 回归：accentStrong 方向必须随明暗反转——浅色主题压深、深色主题提亮；
     * accentMuted 则必须朝 cardBg 靠拢（明度落在 accent 与 cardBg 之间）。
     */
    @Test
    fun `accentStrong direction flips with theme and accentMuted moves toward surface`() {
        for (preset in ThemePresets.allPresets) {
            val light = preset.resolve(isDark = false)
            assertTrue(
                "${preset.id} 浅色主题 accentStrong 应比 accent 更深",
                light.accentStrong.luma() < light.accent.luma()
            )
            val dark = preset.resolve(isDark = true)
            assertTrue(
                "${preset.id} 深色主题 accentStrong 应比 accent 更亮",
                dark.accentStrong.luma() > dark.accent.luma()
            )

            for (p in listOf(light, dark)) {
                val lo = minOf(p.accent.luma(), p.cardBg.luma())
                val hi = maxOf(p.accent.luma(), p.cardBg.luma())
                assertTrue(
                    "${preset.id} accentMuted 明度应落在 accent 与 cardBg 之间 (isDark=${p.isDark})",
                    p.accentMuted.luma() in lo..hi
                )
            }
        }
    }

    /**
     * P1b 的算法锚点：把预设的实际推导结果钉住
     * （顺序：浅色 accentStrong / 浅色 accentMuted / 深色 accentStrong / 深色 accentMuted）。
     *
     * 以后有人改 ACCENT_STEP / ACCENT_MUTE_RATIO、换混合色彩空间、或调了某个预设的 accent，
     * 这里会立刻失败——这正是想要的：梯度色不该被静默改掉。
     *
     * 2026-09-05 下午：随 8 套彩色皮肤补齐 8 组期望值（共 9 组）。
     * 期望表用 `getValue` 取，所以将来新增皮肤而忘了补期望值会直接抛，不会静默跳过。
     *
     * 2026-09-05 傍晚：`orange` / `cyan` 两套皮肤删除，对应的两组期望值一并删除（共 7 组）。
     * 期望表**多余的键不会红灯**（只按 allPresets 遍历取），但留着就是孤立数据，
     * 下一个人会以为还有那两套皮肤 —— 所以删皮肤必须顺手清这里。
     */
    @Test
    fun `accent gradient values are pinned for every preset`() {
        val expected = mapOf(
            "default" to listOf("#1D1D1D", "#A7A7A7", "#707070", "#3B3B3B"),
            "rose" to listOf("#C72B50", "#F8ADBF", "#F05479", "#801D38"),
            "amber" to listOf("#CE8509", "#FBD89D", "#F7AE32", "#84510C"),
            "lime" to listOf("#89C12D", "#DAF5AE", "#B2EA55", "#55791E"),
            "emerald" to listOf("#3EBA6C", "#B7F2CC", "#67E394", "#277645"),
            "blue" to listOf("#1F53C5", "#A8C1F7", "#487CEE", "#1A3A85"),
            "violet" to listOf("#6831C7", "#CBB0F8", "#915AF0", "#482389")
        )
        for (preset in ThemePresets.allPresets) {
            val l = preset.resolve(isDark = false)
            val d = preset.resolve(isDark = true)
            val actual = listOf(l.accentStrong.hex(), l.accentMuted.hex(), d.accentStrong.hex(), d.accentMuted.hex())
            assertEquals(preset.id, expected.getValue(preset.id), actual)
        }
        assertEquals(
            "期望表里有 allPresets 之外的 id —— 删皮肤时忘了清这里，留下的是孤立数据。",
            ThemePresets.allPresets.map { it.id }.toSet(),
            expected.keys
        )
    }

    // ══════════════ 2026-09-05：7 套皮肤的护栏 ══════════════

    /**
     * 预设表的 id 集合必须与**本测试里显式列出的 7 个 id** 一致。
     *
     * 2026-09-05 下午改法说明：`ThemePresets.ALLOWED_IDS` 现在是从 `allPresets` **推导**的
     * （加皮肤只改一处），所以拿它来比对会变成一条恒真的废断言。人工确认的卡点搬到这里：
     * 白名单写在**测试侧**，加 / 删皮肤必然红灯。
     *
     * 红灯的正确处理**不是**把 id 加进下面的列表了事，而是先看
     * [every preset meets the contrast floor in both modes] 是否也过了 ——
     * 那条会把新皮肤的明暗两态逐项量化体检。两条都绿再改这里的列表。
     *
     * 顺序也一起钉住：`default` 必须在第一位（外观页的皮肤网格按 `allPresets` 顺序铺，
     * 出厂默认应该出现在第一格）。
     */
    @Test
    fun `preset table only contains allowed ids`() {
        assertEquals(
            "ThemePresets.allPresets 的 id 列表变了。加皮肤前先读本测试的 KDoc：" +
                "先确认对比度体检那条测试也是绿的，再来改这里的白名单。",
            listOf("default", "rose", "amber", "lime", "emerald", "blue", "violet"),
            ThemePresets.allPresets.map { it.id }
        )
        assertEquals(
            "ALLOWED_IDS 必须与 allPresets 的 id 集合完全一致（前者由后者推导，不该手写）。",
            ThemePresets.allPresets.map { it.id }.toSet(),
            ThemePresets.ALLOWED_IDS
        )
        assertEquals(
            "allPresets 里出现了重复 id —— findById 只会命中第一个，后面的静默失效。",
            ThemePresets.allPresets.size,
            ThemePresets.allPresets.map { it.id }.toSet().size
        )
        assertTrue(
            "ThemePresets.DEFAULT_ID 必须真的在 allPresets 里，否则 findById 的兜底与" +
                "normalizeThemeId 的目标是两个不同的东西。",
            ThemePresets.allPresets.any { it.id == ThemePresets.DEFAULT_ID }
        )
        assertEquals(
            "动态取色**不是**一套皮肤，不许出现在 allPresets 里 —— 它是独立开关 " +
                "ThemeManager.dynamicEnabled，palette 由 buildDynamicPalette 在运行期生成。",
            0,
            ThemePresets.allPresets.count { it.id == DYNAMIC_THEME_ID }
        )
        assertEquals(
            "「自定义」同样不许出现在 allPresets 里 —— 它没有固定色值，palette 由 " +
                "buildCustomPalette(种子色) 在运行期生成。它只出现在 SELECTABLE_IDS 里。",
            0,
            ThemePresets.allPresets.count { it.id == CUSTOM_THEME_ID }
        )
        assertEquals(
            "SELECTABLE_IDS 必须正好是 ALLOWED_IDS 多一个 custom：" +
                "前者是「合法的持久化 id」，后者是「预设表里的 id」，差集只有自定义这一项。",
            ThemePresets.ALLOWED_IDS + CUSTOM_THEME_ID,
            ThemePresets.SELECTABLE_IDS
        )
    }

    /**
     * **7 套皮肤 × 明暗两态 × 5 条对比度硬指标**，全部量化验一遍。
     *
     * 做成遍历 `allPresets` 而不是逐套写死断言：将来加皮肤自动被体检，
     * 不需要（也不可能忘记）为新皮肤补一份测试。删皮肤则自动少几组，也无需改这里。
     *
     * 五条判据与它们的出处：
     * 1. `textPrimary` vs `cardBg` ≥ [MIN_TEXT_CONTRAST]（4.5，正文 AA）
     * 2. `textSecondary` vs `cardBg` ≥ [MIN_TEXT_CONTRAST] —— **刻意不放宽到 3:1**，
     *    副文案走的是 12sp 的 `UfiTextStyles.note`，不属于"大字"豁免；
     * 3. `iconTint` vs `cardBg` ≥ [MIN_NON_TEXT_CONTRAST]（3.0，非文本图形）
     * 4. `cardBg` vs `pageBg` 肉眼可分 —— 判据是 CIE L\* 差 ≥ [MIN_SURFACE_DELTA_L]，
     *    **不是** WCAG 对比度（理由见 [surfacesAreDistinguishable] 的 KDoc）；
     * 5. `onAccent` vs **accent 实底** ≥ [MIN_NON_TEXT_CONTRAST]，且 `onGradient` vs
     *    **Hero 渐变最亮停止点**（浅色态 = accent 往白混 22%）也要 ≥ 3:1。
     *    第 5 条是三套亮色皮肤的必然问题，见 `ThemePresets` 的类 KDoc。
     *
     * 顺带量一遍 `textPrimary` vs `pageBg`：有些设置行直接铺在页面底上而非卡上。
     *
     * ## 当前的余量分布（2026-09-05 傍晚删掉橙 / 青之后实测）
     * 最紧的一项是 **玫红浅色态的第 5 条**：`onGradient`（白）对渐变最亮停止点 3.12:1，
     * 门槛 3.0，余量只有 4%。第二紧的是宝蓝浅色态同一项 3.49:1。
     * 正文类（4.5 门槛）最紧的是**柠绿深色态** `textSecondary` 对卡面 6.04:1 ——
     * 删掉青色之前那个位置是青色浅色态的 5.36:1。
     * 也就是说**动 accent 比动正文色危险得多**：改玫红 / 宝蓝的 accent 前务必先跑本测试。
     */
    @Test
    fun `every preset meets the contrast floor in both modes`() {
        for (preset in ThemePresets.allPresets) {
            for (isDark in listOf(false, true)) {
                val p = preset.resolve(isDark)
                val tag = "${preset.id}(isDark=$isDark)"
                fun floorAtLeast(actual: Double, floor: Double, what: String) = assertTrue(
                    "$tag $what 实测 ${"%.2f".format(actual)}:1，低于 $floor:1。" +
                        "改色值后请重跑本测试，别只靠肉眼看截图。",
                    actual >= floor
                )
                floorAtLeast(contrastRatio(p.textPrimary, p.cardBg), MIN_TEXT_CONTRAST, "textPrimary 对 cardBg")
                floorAtLeast(contrastRatio(p.textSecondary, p.cardBg), MIN_TEXT_CONTRAST, "textSecondary 对 cardBg")
                floorAtLeast(contrastRatio(p.iconTint, p.cardBg), MIN_NON_TEXT_CONTRAST, "iconTint 对 cardBg")
                floorAtLeast(contrastRatio(p.textPrimary, p.pageBg), MIN_NON_TEXT_CONTRAST, "textPrimary 对 pageBg")
                floorAtLeast(contrastRatio(p.onAccent, p.accent), MIN_NON_TEXT_CONTRAST, "onAccent 对 accent 实底")
                floorAtLeast(
                    contrastRatio(p.onGradient, heroGradientBrightestStop(p.accent, isDark)),
                    MIN_NON_TEXT_CONTRAST,
                    "onGradient 对 Hero 渐变最亮停止点"
                )
                assertTrue(
                    "$tag cardBg 与 pageBg 的 CIE L* 差只有 " +
                        "${"%.2f".format(kotlin.math.abs(perceivedLightness(p.cardBg) - perceivedLightness(p.pageBg)))}" +
                        "，低于 $MIN_SURFACE_DELTA_L —— 卡片与页面底会糊成一片，看不出层次。",
                    surfacesAreDistinguishable(p.cardBg, p.pageBg)
                )
            }
        }
    }

    // 2026-09-05 傍晚：原先这里有一个 private 的 `heroGradientBrightestStop(accent, isDark)`。
    // 自定义皮肤的取色器需要在**运行时**判同一条判据（"不许静默给出不合格配色"的闸门），
    // 于是它与当年的 `contrastRatio` 一样被提升为生产代码：`CustomPalette.kt` 的顶层函数
    // （同包，直接可用）。本测试上面的调用点已改为直接用那一份 —— 判据实现只有一份，
    // 两份必然漂移，漂移的表现是"单测全绿但真机上那套配色不合格"。

    /**
     * 老用户迁移：磁盘上的已删除 id 必须**回落**到默认，不能抛、也不能原样透传。
     *
     * 出厂默认曾是 `"aurora"`（`ThemeManager` 旧代码里写死），所以升级用户的
     * SharedPreferences 里大概率就存着一个已经不存在的皮肤 id。
     * 回落逻辑抽成 [ThemePresets.normalizeThemeId] 纯函数正是为了能在这里钉住。
     *
     * 2026-09-05 傍晚两处变动，**方向相反**，一起放在这条测试里对照：
     * - `orange` / `cyan` 两套皮肤被删 ⇒ 进入本列表（它们现在是"已删除的 id"）；
     * - `custom` 从本列表**移出** ⇒ 自定义皮肤真正落地了，它现在是一个**合法 id**
     *   （见下面那条独立测试）。
     */
    @Test
    fun `removed and unknown theme ids fall back to default`() {
        val gone = listOf(
            "aurora", "tech_blue", "mint_green", "dream_purple", "vibrant_orange",
            // 2026-09-05 傍晚按产品要求删除的两套彩色皮肤
            "orange", "cyan",
            // `dynamic` 虽然功能回归了，但它现在是**独立开关**（ThemeManager.dynamicEnabled）
            // 而不是皮肤 id，所以作为 id 仍必须被折叠。
            "dynamic",
            // 脏数据
            "", "  ", "Default", "不存在的皮肤"
        )
        for (id in gone) {
            assertEquals(
                "已删除 / 未知的皮肤 id '$id' 必须回落到 default，否则老用户升级后是空白或错色",
                ThemePresets.DEFAULT_ID,
                ThemePresets.normalizeThemeId(id)
            )
            // findById 也必须回落到同一套色，两条入口不许行为分叉
            assertEquals(id, ThemePresets.Default, ThemePresets.findById(id))
        }
        assertEquals(
            "null（键不存在）同样要回落到 default",
            ThemePresets.DEFAULT_ID,
            ThemePresets.normalizeThemeId(null)
        )
        assertEquals(
            "合法 id 必须原样返回，不能被规整掉",
            ThemePresets.DEFAULT_ID,
            ThemePresets.normalizeThemeId(ThemePresets.DEFAULT_ID)
        )
        for (preset in ThemePresets.allPresets) {
            assertEquals(
                "预设 id '${preset.id}' 必须原样返回",
                preset.id,
                ThemePresets.normalizeThemeId(preset.id)
            )
        }
    }

    /**
     * `"custom"` 的**双重身份**：它是合法的持久化 id，但**不在预设表里**。
     *
     * 这两件事看着矛盾，实际是"id 合法 / 色值不在表里"的必然结果，本测试就是把这个
     * 分工写成可执行的说明，免得下一个人看到 `findById("custom") == Default`
     * 以为是 bug 又把 custom 塞回 `allPresets`（塞回去就要给它编一组假色值）。
     *
     * 取色链路的真实顺序在 `ThemeManager.getCurrentPalette()`：
     * `custom + 有种子色` → [buildCustomPalette]；`custom + 没种子色` → 落到
     * [ThemePresets.findById] ⇒ Default。也就是说下面第二条断言正是**种子色缺失回落**。
     */
    @Test
    fun `custom is a legal theme id but has no entry in the preset table`() {
        assertEquals(
            "\"custom\" 必须原样返回 —— 它是用户在皮肤网格里真的能选中、并且要被持久化的一档。",
            CUSTOM_THEME_ID,
            ThemePresets.normalizeThemeId(CUSTOM_THEME_ID)
        )
        assertTrue(
            "\"custom\" 必须在 SELECTABLE_IDS 里",
            CUSTOM_THEME_ID in ThemePresets.SELECTABLE_IDS
        )
        assertTrue(
            "\"custom\" 不许在 ALLOWED_IDS（= 预设表 id 集合）里",
            CUSTOM_THEME_ID !in ThemePresets.ALLOWED_IDS
        )
        assertEquals(
            "findById(\"custom\") 必须回落到 Default —— 这条路径就是「id 是 custom 但盘上" +
                "没有种子色」时的兜底，见 ThemeManager.getCurrentPalette 的 KDoc。",
            ThemePresets.Default,
            ThemePresets.findById(CUSTOM_THEME_ID)
        )
    }

    /**
     * 设置项图标色（[UfiSettingsItem] 的默认 tint = `textPrimary`）对**卡面**的对比度
     * 必须 ≥ [MIN_NON_TEXT_CONTRAST]，明暗两态都要过。
     *
     * 背景：默认 tint 曾经是 `accent`，而 `default` 预设的 `accentDark`（0xFF555555）
     * 压在 `cardBgDark`（0xFF2A2A2A）上只有 ~1.9:1 —— 真机上就是「深色模式下设置项
     * 图标是灰的，看不见」。改成 `textPrimary` 后这条测试负责钉住它不再退回去。
     *
     * 判据取 WCAG 2.1 的 3:1（非文本图形/图标的最低要求），不用 4.5:1：
     * 图标是大面积实心图形，不承担逐字辨认的负担。
     * 顺带也量一遍 `textPrimary` 对 `pageBg` —— 有些设置行直接铺在页面底上而非卡上。
     */
    @Test
    fun `settings item icon tint keeps at least 3 to 1 contrast on card and page`() {
        for (preset in ThemePresets.allPresets) {
            for (isDark in listOf(false, true)) {
                val p = preset.resolve(isDark)
                val onCard = contrastRatio(p.textPrimary, p.cardBg)
                val onPage = contrastRatio(p.textPrimary, p.pageBg)
                assertTrue(
                    "${preset.id}(isDark=$isDark) textPrimary 对 cardBg 的对比度 " +
                        "${"%.2f".format(onCard)}:1 低于 $MIN_NON_TEXT_CONTRAST:1 —— " +
                        "设置项图标会糊在卡面上（这正是 2026-09-05 修掉的那个 bug）。",
                    onCard >= MIN_NON_TEXT_CONTRAST
                )
                assertTrue(
                    "${preset.id}(isDark=$isDark) textPrimary 对 pageBg 的对比度 " +
                        "${"%.2f".format(onPage)}:1 低于 $MIN_NON_TEXT_CONTRAST:1。",
                    onPage >= MIN_NON_TEXT_CONTRAST
                )
            }
        }
    }

    /**
     * 反向锚点：记录**为什么不能**把设置项图标接回 `accent`。
     *
     * 这条断言故意断言"accent 在深色下达不到 3:1"——它不是在要求 accent 保持难看，
     * 而是把当年的根因固化成可执行的说明：只要 `default` 的 `accentDark` 还是这个值，
     * 谁把 `UfiSettingsItem` 的默认 tint 改回 accent，深色下就一定不可读。
     * 若将来真的重新标定了 `accentDark`（使它在深色下 ≥ 3:1），这条会红灯，
     * 那时可以连同本测试一起删除。
     */
    @Test
    fun `accent is documented as unusable for icon tint in dark mode`() {
        val dark = ThemePresets.Default.resolve(isDark = true)
        val ratio = contrastRatio(dark.accent, dark.cardBg)
        assertTrue(
            "default 的 accentDark 对 cardBgDark 现在是 ${"%.2f".format(ratio)}:1。" +
                "若它已 ≥ $MIN_NON_TEXT_CONTRAST:1，说明 accent 被重新标定过，本测试可以删除；" +
                "在那之前，UfiSettingsItem 的默认 tint 不许接回 accent。",
            ratio < MIN_NON_TEXT_CONTRAST
        )
    }

    /** 相对亮度（Rec.709 权重，仅用于比较深浅方向，不做 WCAG 对比度计算）。 */
    private fun Color.luma(): Float = 0.2126f * red + 0.7152f * green + 0.0722f * blue

    // 2026-09-05：原先这里还有一份 private 的 `wcagLuminance()` / `contrastRatio()`。
    // 皮肤从 1 套变成 9 套、并且动态取色需要在**运行时**判对比度之后，
    // 判据实现必须只有一份（两份必然漂移），已提升为生产代码
    // `ColorContrast.kt` 的顶层函数（同包，直接可用）：
    //   contrastRatio(a, b) / perceivedLightness(c) / surfacesAreDistinguishable(a, b)
    // 上面的 [luma] 刻意保留：它只回答"谁更亮"，不需要正确的伽马，与对比度不是一回事。

    private fun Color.hex(): String = String.format(
        "#%02X%02X%02X",
        (red * 255f + 0.5f).toInt(),
        (green * 255f + 0.5f).toInt(),
        (blue * 255f + 0.5f).toInt()
    )

    /**
     * P3 回归（2026-09-04）：`buildColorSchemeFromPalette`（自研 palette → M3 ColorScheme 的桥接层）
     * 不许再出现颜色字面量。这层是**裸 M3 组件**唯一的取色来源，写死值时页面代码里搜不到，
     * 所以必须由测试盯住。
     *
     * 用一组刻意"不像语义色"的槽值验证四个 on* 角色真的来自 palette，
     * 并钉住 outline / outlineVariant / 三个 *Container 的归属。
     */
    @Test
    fun `color scheme bridge reads on-roles outline and containers from palette`() {
        val palette = ThemePresets.Default.copy(
            onAccentLight = Color(0xFF012345),
            onAccentDark = Color(0xFF012345),
            onErrorLight = Color(0xFF6789AB),
            onErrorDark = Color(0xFF6789AB)
        )
        for (isDark in listOf(false, true)) {
            val resolved = palette.resolve(isDark)
            val scheme = buildColorSchemeFromPalette(resolved)
            val tag = "isDark=$isDark"

            // ── on* 四槽：原先亮暗两套都写死 Color.White ──
            assertEquals(tag, resolved.onAccent, scheme.onPrimary)
            assertEquals(tag, resolved.onAccent, scheme.onSecondary)
            assertEquals(tag, resolved.onAccent, scheme.onTertiary)
            assertEquals(tag, resolved.onError, scheme.onError)

            // ── outline：交互描边，接 inputBorder(12%)，不再是 textSecondary 那个中灰实色 ──
            assertEquals(tag, resolved.inputBorder, scheme.outline)
            assertNotEquals(tag, resolved.textSecondary, scheme.outline)
            // outlineVariant 是 M3 的弱分隔线，继续对应 divider 槽
            assertEquals(tag, resolved.divider, scheme.outlineVariant)

            // ── 主色浅底只有一个来源 accentContainer（secondaryContainer 曾自带一套 alpha）──
            assertEquals(tag, resolved.accentContainer, scheme.primaryContainer)
            assertEquals(tag, resolved.accentContainer, scheme.secondaryContainer)
            assertEquals(tag, resolved.accentContainer, scheme.tertiaryContainer)
        }
    }

    /**
     * 构造一个最小可用的 ResolvedPalette。
     *
     * 2026-09-03（P1a）：原先只传 11 个参数，其中 success/warning/error 是体内按 isDark 算的 getter。
     * 现在这些语义色改成了存储字段（可按主题配置），必须显式传入；
     * 同时零消费的 btnBg / dataHighlight 已删除。这里的取值沿用改造前 getter 的原值，
     * 保证依赖它们的两个测试（撞色检查、StatusRamp 映射）判定口径不变。
     */
    private fun testPalette(isDark: Boolean = false): ResolvedPalette = ResolvedPalette(
        accent = Color(0xFF111111),
        accentSecondary = Color(0xFF222222),
        pageBg = Color(0xFF333333),
        cardBg = Color(0xFF444444),
        textPrimary = Color(0xFF555555),
        textSecondary = Color(0xFF666666),
        divider = Color(0xFF777777),
        iconTint = Color(0xFF999999),
        onAccent = Color.White,
        error = if (isDark) Color(0xFFFF6B6B) else Color(0xFFE53935),
        errorContainer = if (isDark) Color(0xFF3D1515) else Color(0xFFFFEBEE),
        warning = if (isDark) Color(0xFFFFB74D) else Color(0xFFFF9800),
        success = if (isDark) Color(0xFF66BB6A) else Color(0xFF43A047),
        metricNormal = if (isDark) Color(0xFFF2F2F7) else Color(0xFF1C1C1E),
        scrim = Color.Black.copy(alpha = if (isDark) 0.5f else 0.35f),
        // 2026-09-03（P1c）：onError / switchTrackOff / switchThumbOff 也改成了存储字段，
        // 取值同样沿用改造前 getter 的原值。
        onError = Color.White,
        switchTrackOff = if (isDark) Color.White.copy(alpha = 0.15f) else Color(0xFFE0E0E0),
        switchThumbOff = if (isDark) Color.White.copy(alpha = 0.5f) else Color(0xFFFAFAFA),
        // 2026-09-04（P2-中）：渐变 Hero 卡的整套前景色也改成了存储字段，
        // 取值沿用四个页面文件里原来写死的 Color.White。
        onGradient = Color.White,
        gradientMuted = Color.White,
        isDark = isDark
    )
}

// 2026-09-05：原先这里有一个 private 的 `MIN_NON_TEXT_CONTRAST = 3.0`。
// 它与生产代码 `ColorContrast.kt` 里的同名常量重复了，已删除本地副本 ——
// 阈值必须与运行时兜底用的是同一个值，否则"测试过了但真机不合格"。
