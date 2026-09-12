package com.ufi_axis.ui.theme

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 外观模式：决定浅色 / 深色如何确定。
 */
enum class ThemeMode { AUTO, LIGHT, DARK }

/**
 * Manages theme selection and persistence.
 * Stores the selected theme ID and appearance mode in SharedPreferences via the existing prefs file.
 *
 * @param observeExternal 是否注册跨实例 SharedPreferences 监听。
 *   MainActivity 用默认 true（同进程其它 ThemeManager 实例写入后，本实例也能收到）。
 *   设置页等短生命周期屏幕用 false，避免泄漏监听。
 */
class ThemeManager(context: Context, observeExternal: Boolean = true) {

    private val prefs = context.getSharedPreferences("ufi_axis_prefs", Context.MODE_PRIVATE)

    /**
     * 取平台动态色资源需要 `Context`，而 [getCurrentPalette] 是**非 Composable 普通方法**，
     * 拿不到 `LocalContext`。所以在构造时留一份 applicationContext。
     *
     * 用 `applicationContext` 而不是传进来的那个：设置页构造本类时传的是 Activity context
     * （`ThemeManager(context, observeExternal = false)`），而本类的实例会被
     * `remember` 持有到屏幕销毁，直接存 Activity context 就是一条泄漏路径。
     * 动态色资源挂在 Resources 上，用 appContext 取值完全等价。
     */
    private val appContext: Context = context.applicationContext

    // ── 当前皮肤 id ──
    //
    // 2026-09-05：与 [blurEnabled] 同一病根，改为**进程内共享**（见 [pageTransition] 处的长注释）。
    // 哨兵取空串 `""`（照 [sharedPageTransition]）—— 空串不是任何合法 id，
    // 且 [ThemePresets.normalizeThemeId] 会把它折叠成 default，不会与真实值混淆。
    //
    // 2026-09-05 上午同时删掉了 `_customAccentColor` 与当时那个 `_dynamicEnabled`：
    // 自定义强调色（`setCustomAccent`）与动态取色（`enableDynamic`）全仓零调用点，
    // 皮肤网格只遍历 `ThemePresets.allPresets`（从来不含这两个 id），UI 上不可达。
    // 删除它们的直接收益是 [setTheme] 不再一次写两个 flow —— 原先皮肤 id 与动态开关
    // 之间存在中间态，而 MainActivity 同时读这两个去建 palette，会多重建一次或短暂错色。
    //
    // 2026-09-05 下午动态取色按正式设计稿**回归**（见下一个字段），但那条收益被保留了：
    // 它现在是与皮肤 id **完全独立**的开关，[setTheme] 仍然只写一个 flow。
    //
    // 2026-09-05 傍晚「自定义」皮肤也回归了，但走的是**第三条路**：它是一个真正的皮肤 id
    // （`"custom"`，会被持久化），色值由另一个独立的键「种子色」在运行期算出
    // （[customSeedColor] + [buildCustomPalette]）。[setTheme] 依然只写一个 flow ——
    // 换到自定义皮肤与改自定义的颜色是**两个动作**，各写各的键，不存在中间态。
    private val _selectedThemeId: MutableStateFlow<String> = sharedSelectedThemeId
    val selectedThemeId: StateFlow<String> = _selectedThemeId.asStateFlow()

    // ── 自定义皮肤的种子色（ARGB Int；[CUSTOM_SEED_UNSET] = 从未选过） ──
    //
    // ★ 必须是 companion 共享 flow ★ —— 与 [blurEnabled] / [themeMode] / [dynamicEnabled]
    // 完全同一病根，而且这一项**尤其**不能例外：取色器就在设置页里，而设置页那份
    // ThemeManager 实例是 `observeExternal = false`（只写不听）。若这里用实例级 flow，
    // 用户在取色器里点确认后，写入落在设置页那份实例上，而真正拿它去建 palette 的是
    // MainActivity 那份实例 —— 表现就是「选完颜色要杀进程重进才生效」。
    // 本轮之前已经为 pageTransition / transitionDurationMs / blurEnabled / themeMode /
    // selectedThemeId / uiScalePercent / dynamicEnabled 七项各修过一次同一个 bug。
    //
    // **prefListener 里刻意不给这个键加分支**（加了反而有害：磁盘旧值会在某些时序下
    // 把内存里的新值盖回去，正是 [blurSeeded] 注释里点名要避免的那种回灌）。
    //
    // 播种哨兵用独立标记位 [customSeedSeeded]（照 [blurSeeded]）：虽然
    // [CUSTOM_SEED_UNSET]（0，alpha = 0）不可能是合法种子、看着能当哨兵用，
    // 但它同时也是「读过盘、盘上确实没有」这个**合法结果**，两种状态必须分开 ——
    // 混用会让每一次构造实例都重新读一次盘。
    private val _customSeedColor: MutableStateFlow<Int> = sharedCustomSeedColor
    val customSeedColor: StateFlow<Int> = _customSeedColor.asStateFlow()

    // ── Material You 动态取色开关（默认关） ──
    //
    // 2026-09-05（下午）：动态取色回归，但**不再是一个皮肤 id**（当年 `"dynamic"` 那种做法
    // 要在 setTheme 里连写两个 flow，中间态会让宿主多重建一次 palette 或短暂错色）。
    // 现在它是一个独立开关：开启时 [getCurrentPalette] 返回壁纸生成的 palette，
    // 静态皮肤 id 原样保留在盘上（关掉开关立刻回到用户之前选的那套，不用重新挑）。
    //
    // ★ 必须是 companion 共享 flow ★ —— 与 [blurEnabled] / [themeMode] 完全同一病根：
    // 本类会被 new 出多份（MainActivity 一份、设置页一份 `observeExternal = false`、胶囊两份），
    // 实例级 flow 跨实例只能靠 prefListener，而那条链**实测不可靠**，
    // 表现就是「改了要重启才生效」。**prefListener 里刻意不给这个键加分支**
    // （加了反而有害：磁盘旧值会在某些时序下把内存里的新值盖回去）。
    //
    // 播种哨兵用独立标记位 [dynamicSeeded]（照 [blurSeeded]）：Boolean 的 true / false
    // 都是合法取值，没有可借用的非法值当"尚未播种"哨兵。
    private val _dynamicEnabled: MutableStateFlow<Boolean> = sharedDynamicEnabled
    val dynamicEnabled: StateFlow<Boolean> = _dynamicEnabled.asStateFlow()

    // ── 外观模式（自动 / 浅色 / 深色） ──
    //
    // 2026-09-05：**这就是「深色/浅色/自动切了没反应，只能停在自动」的根因**，同上改为共享。
    // 原先是实例级 flow：设置页那份实例（`observeExternal = false`，只写不听）把值写进
    // 自己的 MutableStateFlow，而 `MainActivity` 是从**它自己那份实例**的另一个 flow
    // `collectAsState()` 出来算 isDark 的，两个对象之间只有 SharedPreferences 回调这一条桥，
    // 而那条桥在 2026-09-01 就已被判定不可靠。
    // 「只能停在自动」完全吻合：AUTO 走 `isSystemInDarkTheme()`，不需要 flow 传递。
    //
    // 哨兵用独立标记位 [themeModeSeeded]（照 [blurSeeded]）：枚举三个值**全部合法**，
    // 没有可借用的非法值；刻意**不加**第四个 `UNSEEDED` 枚举项 —— 那会污染
    // MainActivity 里 `when (themeMode)` 的穷尽分支，逼所有消费点处理一个不存在的状态。
    private val _themeMode: MutableStateFlow<ThemeMode> = sharedThemeMode
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    // ── 主 Tab 切换动画策略 id（如 "fade" / "cube3d"），由 UfiPageTransitions 注册表解析 ──
    //
    // ★★ 2026-09-01：这两项用**进程内共享**的 flow，不再每个实例各持一份 ★★
    //
    // 病症：设置页改了切换动画，必须杀进程重进才生效。
    // 原因：本类会被 new 出多份（MainActivity 一份、设置页一份、胶囊栏各一份），
    // 各自持有自己的 MutableStateFlow，靠 SharedPreferences 的变更监听器互相同步。
    // 而设置页那份是 `observeExternal = false`（它只写不听），写完之后要靠
    // MainActivity 那份收到回调才会更新 —— 实测这条回调链不可靠，冷启动重新读盘才对。
    //
    // 与其继续排查回调，不如把「同步」这件事从设计里删掉：所有实例共用同一个 flow 对象，
    // 写入即对所有读者可见，不依赖任何回调。SharedPreferences 退化为纯持久化（冷启动种子）。
    private val _pageTransition: MutableStateFlow<String> = sharedPageTransition
    val pageTransition: StateFlow<String> = _pageTransition.asStateFlow()

    // ── 动画过渡时长（毫秒），默认 [TRANSITION_DURATION_DEFAULT_MS] = 380
    //    （略长于 `UfiPageTransitionDefaultSpec` 的 320，给「模糊交叉淡入」的「慢慢清晰」
    //    留足释放时间；用户仍可在「设置→外观」微调，含 0 = 关闭转场这一档） ──
    //    同上，进程内共享（与切换动画同一个设置页、同一种失效模式）。
    private val _transitionDurationMs: MutableStateFlow<Int> = sharedTransitionDurationMs
    val transitionDurationMs: StateFlow<Int> = _transitionDurationMs.asStateFlow()

    // ── 过渡模糊开关（默认开启） ──
    //
    // ★★ 2026-09-05：改为**进程内共享** —— 与 [pageTransition] / [transitionDurationMs] 同一病根 ★★
    //
    // 2026-09-01 把上面两项搬到 companion 共享 flow 时，本项被漏下了，于是它至今还走那条
    // 当时就已判定不可靠的 prefListener 回调链。症状与当年字面一致：设置页把「过渡模糊」
    // 关掉后，写入落在设置页自己那份实例（`observeExternal = false`，只写不听）的
    // MutableStateFlow 上，而真正被 MainNavGraph 读去下发 `LocalUfiBlurEnabled` 的是
    // MainActivity 那份实例的另一个 flow 对象 —— 两个对象之间没有任何内存关系，
    // 只能靠 SharedPreferences 回调补，于是表现为「必须杀进程重进才生效」。
    //
    // 解法同上：所有实例共用同一个 flow 对象，写入即对全部读者可见，不依赖任何回调；
    // SharedPreferences 退化为纯持久化（只提供冷启动种子，见 init 里的播种）。
    private val _blurEnabled: MutableStateFlow<Boolean> = sharedBlurEnabled
    val blurEnabled: StateFlow<Boolean> = _blurEnabled.asStateFlow()

    // ── 全局 UI 缩放（百分比）：整页 dp + sp 等比缩放 ──
    //
    // 落地方式是在 [UFIAXISTheme] 里覆盖 LocalDensity，而**不是**去改 Spacing / Type 常量：
    // 全仓有 ~1500 处硬编码 dp 不走 Spacing，改常量只会让"卡片变小但内部间距不变"，
    // 缩放不均匀反而更难看。覆盖 density 则一处生效、所有 dp/sp 等比缩，且无需触碰任何页面。
    //
    // 2026-09-05：同样改为**进程内共享**（原实例级，与外观模式同一失效模式 ——
    // 设置页拖完滑块写在自己那份实例上，MainActivity 那份收不到）。
    // 哨兵取 [UI_SCALE_UNSEEDED]（`-1`，照 [TRANSITION_DURATION_UNSEEDED]）：
    // 合法区间是 90~120，负数不可能是真实取值。
    private val _uiScalePercent: MutableStateFlow<Int> = sharedUiScalePercent
    val uiScalePercent: StateFlow<Int> = _uiScalePercent.asStateFlow()

    // ══════════════ 胶囊浮层导航的可调视觉参数 ══════════════
    //
    // 共五项：抬高 / 图标尺寸 / 标签字号 / 圆角 / 图标文字间隔。
    // 默认值取**产品终稿视觉**（30dp / 38dp / 10sp / 30dp / 0dp，见 companion 区说明），
    // 未拖过滑块的用户读不到键时回落到这套终稿默认值。
    // 取值链路与 [transitionDurationMs] 完全同构：MutableStateFlow 内部可写 +
    // StateFlow 只读出口 + coerceIn 夹取的 setter + prefListener 跨实例同步。

    // ── 胶囊抬高（dp）：窗口 y 偏移在「安全基线」之上追加的产品向视觉抬高量 ──
    private val _capsuleLiftDp = MutableStateFlow(
        prefs.getInt(KEY_CAPSULE_LIFT_DP, DEFAULT_CAPSULE_LIFT_DP)
    )
    val capsuleLiftDp: StateFlow<Int> = _capsuleLiftDp.asStateFlow()

    // ── 胶囊图标尺寸（dp） ──
    private val _capsuleIconSizeDp = MutableStateFlow(
        prefs.getInt(KEY_CAPSULE_ICON_SIZE_DP, DEFAULT_CAPSULE_ICON_SIZE_DP)
    )
    val capsuleIconSizeDp: StateFlow<Int> = _capsuleIconSizeDp.asStateFlow()

    // ── 胶囊标签文字大小（sp） ──
    private val _capsuleLabelTextSp = MutableStateFlow(
        prefs.getInt(KEY_CAPSULE_LABEL_TEXT_SP, DEFAULT_CAPSULE_LABEL_TEXT_SP)
    )
    val capsuleLabelTextSp: StateFlow<Int> = _capsuleLabelTextSp.asStateFlow()

    // ── 胶囊圆角（dp）：窗口层 drawable 与内容层 RoundedCornerShape 共用同一个值 ──
    private val _capsuleCornerDp = MutableStateFlow(
        prefs.getInt(KEY_CAPSULE_CORNER_DP, DEFAULT_CAPSULE_CORNER_DP)
    )
    val capsuleCornerDp: StateFlow<Int> = _capsuleCornerDp.asStateFlow()

    // ── 图标与文字间隔（dp）：两行 Column 中图标行与文字行之间的行间距 ──
    //
    // 语义：UfiCapsuleTabBar 里图标行在上、文字行在下，两行由 Column 的
    // verticalArrangement.spacedBy(本值) 撑开，直接参与 Column 排版
    // （不再是旧实现的「上浮 translationY 追加项」，也不再是显式 Spacer）。
    // 取 0 为合法下限（不是非法值），因此下游判据用 `>= 0` 而非 `> 0`。
    private val _capsuleIconTextSpacingDp = MutableStateFlow(
        prefs.getInt(KEY_CAPSULE_ICON_TEXT_SPACING_DP, DEFAULT_CAPSULE_ICON_TEXT_SPACING_DP)
    )
    val capsuleIconTextSpacingDp: StateFlow<Int> = _capsuleIconTextSpacingDp.asStateFlow()

    // ── 跨实例同步：设置页写入后，MainActivity 实例也能收到（同一 SharedPreferences 实例） ──
    //
    // 2026-09-05：**这里刻意只剩胶囊五项 + 三项历史遗留**。皮肤 id / 外观模式 / UI 缩放
    // 三个分支已被删除 —— 它们现在走 companion 共享 flow，写入即对所有读者可见，
    // 留着回调分支反而有害：磁盘旧值会在某些时序下把内存里的新值盖回去
    // （正是 [blurSeeded] 注释里点名要避免的那种回灌）。
    //
    // 同理，动态取色开关（[KEY_DYNAMIC_ENABLED]）与自定义种子色（[KEY_CUSTOM_SEED]）
    // **一开始就没有分支，也不许加**。它们都是 companion 共享 flow，加分支只会引入回灌风险。
    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        when (key) {
            KEY_PAGE_TRANSITION -> {
                _pageTransition.value = prefs.getString(KEY_PAGE_TRANSITION, "fade") ?: "fade"
            }
            KEY_TRANSITION_DURATION_MS -> _transitionDurationMs.value =
                normalizeTransitionDuration(
                    prefs.getInt(KEY_TRANSITION_DURATION_MS, TRANSITION_DURATION_DEFAULT_MS)
                )
            KEY_BLUR_ENABLED -> _blurEnabled.value =
                prefs.getBoolean(KEY_BLUR_ENABLED, true)
            KEY_CAPSULE_LIFT_DP -> _capsuleLiftDp.value =
                prefs.getInt(KEY_CAPSULE_LIFT_DP, DEFAULT_CAPSULE_LIFT_DP)
            KEY_CAPSULE_ICON_SIZE_DP -> _capsuleIconSizeDp.value =
                prefs.getInt(KEY_CAPSULE_ICON_SIZE_DP, DEFAULT_CAPSULE_ICON_SIZE_DP)
            KEY_CAPSULE_LABEL_TEXT_SP -> _capsuleLabelTextSp.value =
                prefs.getInt(KEY_CAPSULE_LABEL_TEXT_SP, DEFAULT_CAPSULE_LABEL_TEXT_SP)
            KEY_CAPSULE_CORNER_DP -> _capsuleCornerDp.value =
                prefs.getInt(KEY_CAPSULE_CORNER_DP, DEFAULT_CAPSULE_CORNER_DP)
            KEY_CAPSULE_ICON_TEXT_SPACING_DP -> _capsuleIconTextSpacingDp.value =
                prefs.getInt(KEY_CAPSULE_ICON_TEXT_SPACING_DP, DEFAULT_CAPSULE_ICON_TEXT_SPACING_DP)
        }
    }

    init {
        // 共享 flow 的冷启动播种：只有第一个实例会真正读盘（哨兵已被填过就跳过），
        // 之后的实例直接看到内存里的最新值 —— 这也是"设置页写完立刻生效"的关键。
        //
        // 2026-09-05（P3）：整块套 `synchronized`。下面每一项都是 check-then-act
        //（读哨兵 → 读盘 → 写 flow / 置标记位），而 ThemeManager 会被 new 出多份
        //（MainActivity 一份、设置页一份、胶囊两份）。当前所有构造点都在主线程，属**理论**竞态；
        // 但一旦有人在后台线程 new 一份（如预热 / WorkManager 里取配色），两个实例就可能
        // 同时看到"未播种"、各读一次盘、后写的把先写的盖掉 —— 而这类竞态的现场极难重现。
        // 锁的代价是每个实例构造一次无竞争的 monitor（纳秒级），远低于它排除的风险。
        // 锁对象取 `ThemeManager::class.java`：companion 里的可见对象，与被保护的
        // companion 级状态同生命周期，且不会与调用方持有的锁产生依赖关系。
        synchronized(ThemeManager::class.java) {
            if (sharedPageTransition.value.isEmpty()) {
                sharedPageTransition.value = prefs.getString(KEY_PAGE_TRANSITION, "fade") ?: "fade"
            }
            if (sharedTransitionDurationMs.value == TRANSITION_DURATION_UNSEEDED) {
                sharedTransitionDurationMs.value = normalizeTransitionDuration(
                    prefs.getInt(KEY_TRANSITION_DURATION_MS, TRANSITION_DURATION_DEFAULT_MS)
                )
            }
            // 模糊开关的播种：Boolean 没有可当哨兵的非法值（true / false 都合法），
            // 所以用一个独立的标记位表达「已读过盘」，语义等价于时长那项的 -1。
            if (!blurSeeded) {
                sharedBlurEnabled.value = prefs.getBoolean(KEY_BLUR_ENABLED, true)
                blurSeeded = true
            }
            // 皮肤 id 的播种 + **老用户迁移**：出厂默认曾是 "aurora"，而本轮只保留 "default"，
            // 所以磁盘上可能存着已删除的 id。规整结果若与盘上不同就写回去，
            // 让脏值只存在一次冷启动，而不是每次启动都要再折叠一遍。
            if (sharedSelectedThemeId.value.isEmpty()) {
                val stored = prefs.getString(KEY_THEME_ID, ThemePresets.DEFAULT_ID)
                val normalized = ThemePresets.normalizeThemeId(stored)
                sharedSelectedThemeId.value = normalized
                if (normalized != stored) {
                    prefs.edit().putString(KEY_THEME_ID, normalized).apply()
                }
            }
            // 外观模式的播种：枚举三个值全合法，同 blurEnabled 用独立标记位。
            if (!themeModeSeeded) {
                sharedThemeMode.value =
                    runCatching { ThemeMode.valueOf(prefs.getString(KEY_THEME_MODE, "AUTO") ?: "AUTO") }
                        .getOrDefault(ThemeMode.AUTO)
                themeModeSeeded = true
            }
            // 动态取色开关的播种：Boolean 无可用哨兵，同 blurEnabled 用独立标记位。
            //
            // 顺手过一遍**能力闸门**：盘上存着 true 但当前设备取不到动态色资源（换机 / 刷了
            // 没有 Monet 的 ROM）时，内存值必须落到 false —— 否则 UI 上开关是开的、
            // 而 getCurrentPalette 会回落到静态皮肤，就成了"显示状态与实际不一致"的假开关。
            if (!dynamicSeeded) {
                val stored = prefs.getBoolean(KEY_DYNAMIC_ENABLED, false)
                sharedDynamicEnabled.value = stored && isDynamicColorAvailable(appContext)
                dynamicSeeded = true
            }
            // UI 缩放的播种：-1 哨兵；顺手夹取，兜住旧版本可能残留的越界值。
            if (sharedUiScalePercent.value == UI_SCALE_UNSEEDED) {
                sharedUiScalePercent.value = prefs.getInt(KEY_UI_SCALE_PERCENT, DEFAULT_UI_SCALE_PERCENT)
                    .coerceIn(UI_SCALE_PERCENT_MIN, UI_SCALE_PERCENT_MAX)
            }
            // 自定义种子色的播种：独立标记位（0 既是"没有种子"也会与"还没读盘"混淆，见字段注释）。
            // 顺手把 alpha 补成不透明：盘上只可能是本类写的值，但补一次的代价是零，
            // 而半透明种子会让 buildCustomPalette 推出一整套带 alpha 的槽 —— 那会绕过全部对比度判据。
            if (!customSeedSeeded) {
                val stored = prefs.getInt(KEY_CUSTOM_SEED, CUSTOM_SEED_UNSET)
                sharedCustomSeedColor.value =
                    if (stored == CUSTOM_SEED_UNSET) CUSTOM_SEED_UNSET else stored or OPAQUE_ALPHA_MASK
                customSeedSeeded = true
            }
        }
        if (observeExternal) prefs.registerOnSharedPreferenceChangeListener(prefListener)
    }

    /**
     * Set appearance mode (auto / light / dark).
     *
     * `_themeMode` 指向进程内共享的 flow（见其声明处），所以这一行写入**立刻**对所有
     * ThemeManager 实例的读者可见 —— 包括 MainActivity 那份实例算 isDark 用的那个订阅。
     * 下面的 prefs 写入只负责持久化（冷启动种子），不再承担「跨实例同步」的职责。
     */
    fun setThemeMode(mode: ThemeMode) {
        _themeMode.value = mode
        prefs.edit().putString(KEY_THEME_MODE, mode.name).apply()
    }

    /**
     * 切换皮肤（按预设 id）。未知 / 已删除的 id 由 [ThemePresets.normalizeThemeId] 折叠回默认。
     *
     * 只写**一个** flow。这一点是刻意保持的：当年 `dynamic` 是一个皮肤 id，
     * `setTheme("dynamic")` 要同时写 `_selectedThemeId` 与 `_dynamicEnabled`，
     * 而 `MainActivity` 同时读这两个去建 palette，两次写入之间的中间态会让它
     * 多重建一次 palette 或短暂错色。现在动态取色是独立开关（[setDynamicEnabled]），
     * 两条路径互不干扰。
     *
     * 调用点：「设置 → 外观 → 主题皮肤」的皮肤网格（2026-09-05 下午随 8 套彩色皮肤恢复）。
     * 合法 id 除了 7 套预设还包括 [CUSTOM_THEME_ID]（见 `ThemePresets.SELECTABLE_IDS`）。
     */
    fun setTheme(id: String) {
        val normalized = ThemePresets.normalizeThemeId(id)
        _selectedThemeId.value = normalized
        prefs.edit().putString(KEY_THEME_ID, normalized).apply()
    }

    /**
     * 写入「自定义」皮肤的种子色。
     *
     * 只写种子色，**不动** [selectedThemeId] —— 与 [setTheme] 严格分工：
     * "换到自定义皮肤"和"改自定义皮肤的颜色"是两个动作。取色器确认时会依次调这两个，
     * 但它们各写各的 flow 与各自的 prefs 键，任何一次写入都不产生"半套状态"。
     *
     * alpha 被强制补成不透明：种子色必须是实色，否则 [buildCustomPalette] 会推出一整套
     * 带 alpha 的色槽，而 [contrastRatio] 不吃 alpha —— 那等于把全部对比度判据绕过去。
     *
     * `_customSeedColor` 指向进程内共享的 flow（见其声明处），所以这一行写入**立刻**
     * 对所有 ThemeManager 实例的读者可见，包括 MainActivity 那份实例用来建 palette 的
     * `remember(selectedThemeId, dynamicEnabled, customSeedColor)`。
     * prefs 写入只负责持久化（冷启动种子）。
     */
    fun setCustomSeedColor(argb: Int) {
        val opaque = argb or OPAQUE_ALPHA_MASK
        if (_customSeedColor.value == opaque) return
        _customSeedColor.value = opaque
        prefs.edit().putInt(KEY_CUSTOM_SEED, opaque).apply()
    }

    /**
     * 开 / 关 Material You 动态取色。
     *
     * `_dynamicEnabled` 指向进程内共享的 flow（见其声明处），所以这一行写入**立刻**对所有
     * ThemeManager 实例的读者可见 —— 包括 MainActivity 那份实例用来建 palette 的
     * `remember(selectedThemeId, dynamicEnabled)`。prefs 写入只负责持久化（冷启动种子）。
     *
     * **开启前先过能力闸门**：设备取不到动态色资源时直接落回 false 并把 false 落盘。
     * 这一条不是防御性冗余 —— UI 侧已经把开关置灰了，但"UI 与闸门一致"这件事必须在
     * 数据层也成立，否则将来任何新调用点（脚本、深链、测试）都可能写进一个不可能生效的 true。
     */
    fun setDynamicEnabled(enabled: Boolean) {
        val effective = enabled && isDynamicColorAvailable(appContext)
        _dynamicEnabled.value = effective
        prefs.edit().putBoolean(KEY_DYNAMIC_ENABLED, effective).apply()
    }

    /** 当前设备是否支持动态取色（版本闸门 + 资源能力探测），供「外观」页决定开关是否可点。 */
    fun isDynamicColorSupported(): Boolean = isDynamicColorAvailable(appContext)

    /**
     * 设置主 Tab 切换动画（持久化策略 id，如 "fade" / "slide" / "cube3d" / "flip3d"）。
     * 供「外观」设置页的「切换动画」下拉调用；MainActivity 的同进程 ThemeManager 实例
     * 通过 [prefListener] 自动收到变更并驱动 UfiPageSwitcher 重建。
     */
    fun setPageTransition(id: String) {
        _pageTransition.value = id
        prefs.edit().putString(KEY_PAGE_TRANSITION, id).apply()
    }

    /**
     * 设置动画过渡时长（毫秒）。
     *
     * 取值口径（2026-09-04 · P2f）：
     * - [TRANSITION_DURATION_OFF]（`0`）= **关闭转场**。它不是"时长 0 的转场"，而是把
     *   宿主切到已有的降低动效通道（瞬移 + 极短淡入），见 `MainNavGraph` 里
     *   `LocalUfiReduceMotion` 的接线；
     * - 其余取值夹在 [TRANSITION_DURATION_MIN_MS] ~ [TRANSITION_DURATION_MAX_MS]。
     *
     * 为什么下端不做成连续到 0：150ms 以下的整屏转场已接近瞬变，只会被感知成"闪一下"，
     * 属于比关闭更差的中间态，所以 0 与 150 之间**刻意留空**（UI 上是一个独立开关而不是滑块档位）。
     * 上限同样刻意不放宽 —— 理由见 [TRANSITION_DURATION_MAX_MS]。
     *
     * 越界输入（含旧版本残留的脏数据）一律被 [normalizeTransitionDuration] 兜住。
     */
    fun setTransitionDurationMs(ms: Int) {
        val clamped = normalizeTransitionDuration(ms)
        _transitionDurationMs.value = clamped
        prefs.edit().putInt(KEY_TRANSITION_DURATION_MS, clamped).apply()
    }

    /**
     * 设置过渡模糊是否启用。
     *
     * `_blurEnabled` 指向进程内共享的 flow（见其声明处），所以这一行写入**立刻**对所有
     * ThemeManager 实例的读者可见 —— 包括 MainActivity 那份实例下发的 `LocalUfiBlurEnabled`。
     * 下面的 prefs 写入只负责持久化（冷启动种子），不再承担「跨实例同步」的职责。
     */
    fun setBlurEnabled(enabled: Boolean) {
        _blurEnabled.value = enabled
        prefs.edit().putBoolean(KEY_BLUR_ENABLED, enabled).apply()
    }

    /**
     * 设置全局 UI 缩放（百分比）。有效范围 [UI_SCALE_PERCENT_MIN] ~ [UI_SCALE_PERCENT_MAX]，超出会夹取。
     * 写法与 [setCapsuleLiftDp] 同构（coerceIn 夹取 + 同值短路，滑块拖动是高频事件）。
     */
    fun setUiScalePercent(percent: Int) {
        val v = percent.coerceIn(UI_SCALE_PERCENT_MIN, UI_SCALE_PERCENT_MAX)
        if (_uiScalePercent.value == v) return
        _uiScalePercent.value = v
        prefs.edit().putInt(KEY_UI_SCALE_PERCENT, v).apply()
    }

    // ══════════════ 胶囊视觉参数 setter ══════════════
    //
    // 五个 setter 共用同一套写法，与 [setTransitionDurationMs] 同构，另加两层保护：
    // 1) `coerceIn` 把越界值夹回合法区间 —— 设置页滑块已限幅，但外部调用 / 旧版本
    //    残留的脏数据也必须被兜住，否则会把非法尺寸写进 SharedPreferences；
    // 2) **同值短路**（`if (_x.value == v) return`）—— 滑块拖动是高频事件（一次拖动
    //    可产生数十次回调），同值时直接返回可避免无谓的 StateFlow 通知与磁盘写入。

    /**
     * 设置胶囊抬高量（dp）。有效范围 [CAPSULE_LIFT_DP_MIN] ~ [CAPSULE_LIFT_DP_MAX]，超出会夹取。
     */
    fun setCapsuleLiftDp(dp: Int) {
        val v = dp.coerceIn(CAPSULE_LIFT_DP_MIN, CAPSULE_LIFT_DP_MAX)
        if (_capsuleLiftDp.value == v) return
        _capsuleLiftDp.value = v
        prefs.edit().putInt(KEY_CAPSULE_LIFT_DP, v).apply()
    }

    /**
     * 设置胶囊图标尺寸（dp）。有效范围 [CAPSULE_ICON_SIZE_DP_MIN] ~ [CAPSULE_ICON_SIZE_DP_MAX]，超出会夹取。
     */
    fun setCapsuleIconSizeDp(dp: Int) {
        val v = dp.coerceIn(CAPSULE_ICON_SIZE_DP_MIN, CAPSULE_ICON_SIZE_DP_MAX)
        if (_capsuleIconSizeDp.value == v) return
        _capsuleIconSizeDp.value = v
        prefs.edit().putInt(KEY_CAPSULE_ICON_SIZE_DP, v).apply()
    }

    /**
     * 设置胶囊标签文字大小（sp）。有效范围 [CAPSULE_LABEL_TEXT_SP_MIN] ~ [CAPSULE_LABEL_TEXT_SP_MAX]，超出会夹取。
     */
    fun setCapsuleLabelTextSp(sp: Int) {
        val v = sp.coerceIn(CAPSULE_LABEL_TEXT_SP_MIN, CAPSULE_LABEL_TEXT_SP_MAX)
        if (_capsuleLabelTextSp.value == v) return
        _capsuleLabelTextSp.value = v
        prefs.edit().putInt(KEY_CAPSULE_LABEL_TEXT_SP, v).apply()
    }

    /**
     * 设置胶囊圆角（dp，窗口层与内容层共用）。
     * 有效范围 [CAPSULE_CORNER_DP_MIN] ~ [CAPSULE_CORNER_DP_MAX]，超出会夹取。
     */
    fun setCapsuleCornerDp(dp: Int) {
        val v = dp.coerceIn(CAPSULE_CORNER_DP_MIN, CAPSULE_CORNER_DP_MAX)
        if (_capsuleCornerDp.value == v) return
        _capsuleCornerDp.value = v
        prefs.edit().putInt(KEY_CAPSULE_CORNER_DP, v).apply()
    }

    /**
     * 设置图标与文字的间隔（dp）。
     * 有效范围 [CAPSULE_ICON_TEXT_SPACING_DP_MIN] ~ [CAPSULE_ICON_TEXT_SPACING_DP_MAX]，超出会夹取。
     */
    fun setCapsuleIconTextSpacingDp(dp: Int) {
        val v = dp.coerceIn(CAPSULE_ICON_TEXT_SPACING_DP_MIN, CAPSULE_ICON_TEXT_SPACING_DP_MAX)
        if (_capsuleIconTextSpacingDp.value == v) return
        _capsuleIconTextSpacingDp.value = v
        prefs.edit().putInt(KEY_CAPSULE_ICON_TEXT_SPACING_DP, v).apply()
    }

    /**
     * 把五项胶囊视觉参数一次性恢复到出厂默认值。
     *
     * 复用五个 setter 而不是直接批量写盘：夹取、同值短路、StateFlow 通知与
     * prefListener 跨实例同步的语义完全一致，不会出现「重置路径」与「拖动路径」行为漂移。
     */
    fun resetCapsuleMetrics() {
        setCapsuleLiftDp(DEFAULT_CAPSULE_LIFT_DP)
        setCapsuleIconSizeDp(DEFAULT_CAPSULE_ICON_SIZE_DP)
        setCapsuleLabelTextSp(DEFAULT_CAPSULE_LABEL_TEXT_SP)
        setCapsuleCornerDp(DEFAULT_CAPSULE_CORNER_DP)
        setCapsuleIconTextSpacingDp(DEFAULT_CAPSULE_ICON_TEXT_SPACING_DP)
    }

    /**
     * 取当前应生效的 palette（**非 Composable**，直接读 flow 的 `.value`）。
     *
     * 优先级：**动态取色开着 → 壁纸 palette**；否则 → 用户选的皮肤
     * （`"custom"` → 种子色算出来的那一套；其余 → 预设表）。
     * 动态色取不到时（`dynamicPaletteOrNull` 返回 null）静默回落到静态皮肤 ——
     * 这条路径正常情况下走不到：[setDynamicEnabled] 与 init 的播种都过了能力闸门，
     * 留着是为了兜住"App 存活期间系统资源变得不可用"这种极端情况，不让首帧崩。
     *
     * ## 自定义皮肤的**种子色缺失回落**
     * `id == "custom"` 但种子色是 [CUSTOM_SEED_UNSET] 时落到 `ThemePresets.findById("custom")`，
     * 也就是 `Default`。什么时候会遇到：
     * - 用户清了 App 数据但 `ufi_theme_id` 那条恰好还在（不同 prefs 键不保证同时可读）；
     * - 手动改过 prefs / 从别的设备恢复了部分备份；
     * - 将来某个版本换了种子色的键名而忘了迁移。
     *
     * 刻意**不**在这里造一个"默认种子色"顶上去：那会让用户看到一套自己没选过的颜色，
     * 而且与皮肤网格里"自定义那一格显示的是什么"对不上。回落到出厂默认是唯一诚实的选择，
     * 用户重新进一次取色器即可（网格里"自定义"那一格此时显示的正是"未设置"）。
     *
     * ⚠️ 调用点必须让 `remember` 的 key 覆盖**三个**输入：
     * `remember(selectedThemeId, dynamicEnabled, customSeedColor) { themeManager.getCurrentPalette() }`。
     * 漏掉任何一个的表现都是"改了没反应"（本方法不是 Composable，不会因为 flow 变化自己重算）：
     * 漏 `dynamicEnabled` = 拨了开关颜色不变；漏 `customSeedColor` = **在取色器里换了颜色，
     * 整页颜色不动**。见 `MainActivity`。
     *
     * 未知 id 走 [ThemePresets.findById] 的回落（返回 Default，不抛）——
     * 这个 id 来自上一个版本的 SharedPreferences，删皮肤不该让老用户崩在首帧。
     */
    fun getCurrentPalette(): ThemePalette {
        if (_dynamicEnabled.value) {
            dynamicPaletteOrNull(appContext)?.let { return it }
        }
        val seed = _customSeedColor.value
        if (_selectedThemeId.value == CUSTOM_THEME_ID && seed != CUSTOM_SEED_UNSET) {
            return buildCustomPalette(Color(seed))
        }
        return ThemePresets.findById(_selectedThemeId.value)
    }

    companion object {
        private const val KEY_THEME_ID = "ufi_theme_id"
        private const val KEY_THEME_MODE = "ufi_theme_mode"
        private const val KEY_PAGE_TRANSITION = "ufi_page_transition"
        private const val KEY_TRANSITION_DURATION_MS = "ufi_transition_duration_ms"
        private const val KEY_BLUR_ENABLED = "ufi_blur_enabled"

        /**
         * Material You 动态取色开关的持久化键。
         *
         * 键名与 2026-09-05 上午删掉的那个**故意相同**（`ufi_dynamic_enabled`）：
         * 语义完全一致（"要不要用壁纸取色"），沿用旧键等于顺手把老用户的选择接回来，
         * 比换个新键再写一段迁移代码干净。播种时会过一遍能力闸门，所以盘上残留的
         * `true` 不会在不支持的设备上变成假开关（见 init）。
         */
        private const val KEY_DYNAMIC_ENABLED = "ufi_dynamic_enabled"

        /**
         * 「自定义」皮肤的种子色键（ARGB Int）。
         *
         * 刻意**不**沿用 2026-09-05 上午判废的那个 `ufi_custom_accent`，与
         * [KEY_DYNAMIC_ENABLED] 的处置相反，理由是**语义变了**：
         * 老键存的是"自定义**强调色**"（只覆盖 accent 一个槽），新键存的是"整套配色的**种子**"
         * （17 个槽全部由它推导，见 [buildCustomPalette]）。同一个 Int 在两套语义下含义不同，
         * 沿用旧键等于让老数据以新语义被解释。
         *
         * 而且这里根本没有数据要迁移：`setCustomAccent` 在被删除之前**全仓零调用点**，
         * 也就是说老键在任何设备上都从未被写过。既然没有老数据，"沿用旧键接回老用户选择"
         * 这个唯一的好处不存在，换新键是纯收益。老键继续留在盘上不管它 ——
         * 写一段"升级时删键"的迁移代码反而要新增一条只跑一次的分支。
         */
        private const val KEY_CUSTOM_SEED = "ufi_custom_seed_color"

        /**
         * 种子色的"从未选过"哨兵。
         *
         * `0` = ARGB `0x00000000`（alpha = 0，全透明），而 [customSeedToArgb] 恒把 alpha 写成
         * `0xFF` ⇒ 本类写出去的值永远不可能是 0，不会与真实取值混淆。
         * 用它而不是"另开一个布尔键"是因为取值域里天然有这个空位，不必多存一个键。
         */
        const val CUSTOM_SEED_UNSET: Int = 0

        /**
         * ARGB 的不透明 alpha 掩码。`or` 上它即把任意 Int 补成不透明色。
         *
         * 用 `val` 而不是 `const val`：`shl` 是中缀函数调用，不是 Kotlin 认可的
         * 编译期常量表达式。写成 `-16777216` 那种十进制字面量倒是能当 const，但没人读得懂。
         */
        private val OPAQUE_ALPHA_MASK: Int = 0xFF shl 24

        // ── 进程内共享的切换动画状态（见 [pageTransition] 处的说明） ──
        //
        // 所有 ThemeManager 实例共用这两个 flow 对象，写入即对全部读者可见，不依赖
        // SharedPreferences 回调。空串 / 负数为「尚未播种」哨兵，由第一个被构造的实例
        // 从 prefs 读一次填上（"" 与负数都不是合法取值，不会与真实值混淆）。
        //
        // 2026-09-04（P2f）：时长哨兵**从 0 改为 -1**。这是必须的 —— 本轮把 `0` 变成了
        // 合法取值（= 关闭转场，见 [setTransitionDurationMs]），继续拿 0 当"未播种"会让
        // 「用户把时长调到 0」与「还没读盘」两种状态无法区分。
        private val sharedPageTransition = MutableStateFlow("")
        private const val TRANSITION_DURATION_UNSEEDED = -1
        private val sharedTransitionDurationMs = MutableStateFlow(TRANSITION_DURATION_UNSEEDED)

        // 2026-09-05：过渡模糊开关也并入共享（原先是实例级 flow，正是「改了要杀进程」的成因）。
        //
        // 播种哨兵与另两项不同：Boolean 的 true / false **都是合法取值**，没有可借用的
        // 非法值当「尚未播种」哨兵（这正是时长那项当年能把 0 让位给 -1 的前提）。
        // 因此改用一个独立的标记位 —— 它承担的角色与 [TRANSITION_DURATION_UNSEEDED]
        // 完全相同：只让第一个被构造的实例真正读一次盘，之后的实例直接看内存里的最新值，
        // 免得后建的实例（如设置页那份）用磁盘旧值把用户刚改的内存值盖回去。
        private val sharedBlurEnabled = MutableStateFlow(true)

        // 四个播种标记位都加 @Volatile（2026-09-05 P3）：读写在 init 的
        // `synchronized(ThemeManager::class.java)` 块内，锁本身已提供互斥与可见性；
        // @Volatile 是第二道保险 —— 万一将来有人在锁外加一处读（例如"已播种就跳过整段"
        // 的快路径），也不会读到别的线程写完却还没同步过来的旧值。
        // 加它的代价只是一次 volatile 读/写，而这段代码每个实例只跑一次。
        @Volatile
        private var blurSeeded = false

        // ══════════════ 2026-09-05：皮肤 id / 外观模式 / UI 缩放 并入共享 ══════════════
        //
        // 三者原先都是实例级 flow，成因与症状与上面三项**逐字相同**（设置页那份实例
        // `observeExternal = false` 只写不听，MainActivity 那份读的是另一个 flow 对象）。
        // 其中「外观模式」的表现最明显：深色/浅色选了没反应、只能停在自动
        // —— 因为 AUTO 走 `isSystemInDarkTheme()`，唯一一档不需要 flow 传递。
        //
        // 哨兵各按取值域挑，三种范式对应三种情况：
        //   • String  → 空串（照 [sharedPageTransition]，空串不是合法 id）
        //   • enum    → 独立标记位（照 [blurSeeded]，三个值全合法；刻意不加第四个 UNSEEDED 枚举项）
        //   • Int     → -1（照 [TRANSITION_DURATION_UNSEEDED]，合法区间 90~120）
        private val sharedSelectedThemeId = MutableStateFlow("")
        private val sharedThemeMode = MutableStateFlow(ThemeMode.AUTO)
        @Volatile
        private var themeModeSeeded = false
        private const val UI_SCALE_UNSEEDED = -1
        private val sharedUiScalePercent = MutableStateFlow(UI_SCALE_UNSEEDED)

        // ── 动态取色开关（2026-09-05 下午随 8 套彩色皮肤一起回归） ──
        //
        // 与上面几项同一范式：companion 共享 flow + 独立播种标记位。
        // 之所以第一天就照这个范式写（而不是先用实例级 flow 再"等出问题再改"）：
        // 本轮之前已经为 pageTransition / transitionDurationMs / blurEnabled /
        // themeMode / selectedThemeId / uiScalePercent 六项各修过一次同一个 bug
        // （「改了要杀进程重进才生效」），成因逐字相同。它是「设置 → 外观」页里的开关，
        // 与那六项走的是**同一条**跨实例链路，没有任何理由认为它会例外。
        private val sharedDynamicEnabled = MutableStateFlow(false)
        @Volatile
        private var dynamicSeeded = false

        // ── 自定义皮肤的种子色（2026-09-05 傍晚，随「自定义」皮肤真正落地） ──
        //
        // 同一范式：companion 共享 flow + 独立播种标记位。理由见 [customSeedColor] 字段注释 ——
        // 写入方（取色器，在设置页那份 `observeExternal = false` 的实例上）与读取方
        // （MainActivity 那份实例的 getCurrentPalette）是两个对象，实例级 flow 之间
        // 只有 prefListener 这一条桥，而那条桥 2026-09-01 就已被判定不可靠。
        private val sharedCustomSeedColor = MutableStateFlow(CUSTOM_SEED_UNSET)
        @Volatile
        private var customSeedSeeded = false

        /** 转场时长「关闭」档：0 = 不播转场，接 `LocalUfiReduceMotion`（见 [setTransitionDurationMs]）。 */
        const val TRANSITION_DURATION_OFF = 0

        /**
         * 转场时长滑块下限（毫秒）。低于此值没有中间档 —— 要么 [TRANSITION_DURATION_OFF]（关闭），
         * 要么 ≥ 本值，因为 150ms 以下的转场已经接近瞬变、只会被感知成闪帧。
         */
        const val TRANSITION_DURATION_MIN_MS = 150

        /**
         * 转场时长滑块上限（毫秒）。**不要放宽**：超过 600ms 的整屏转场会被直接感知成卡顿
         * （这一档已经比 Material 的建议上限长一倍）。
         */
        const val TRANSITION_DURATION_MAX_MS = 600

        /** 转场时长默认值（毫秒）。略长于页面转场默认档（`UfiMotion.Duration.Sweeping` 320）。 */
        const val TRANSITION_DURATION_DEFAULT_MS = 380

        /**
         * 把任意输入规整为合法的转场时长：`≤0` → [TRANSITION_DURATION_OFF]（关闭），
         * 其余夹到 [TRANSITION_DURATION_MIN_MS] ~ [TRANSITION_DURATION_MAX_MS]。
         *
         * setter 与 `prefListener`（读旧版本残留数据）共用它，避免两条入口各写一份夹取逻辑
         * —— 那正是「改一处另一处不同步」的老毛病。
         */
        fun normalizeTransitionDuration(ms: Int): Int =
            if (ms <= TRANSITION_DURATION_OFF) {
                TRANSITION_DURATION_OFF
            } else {
                ms.coerceIn(TRANSITION_DURATION_MIN_MS, TRANSITION_DURATION_MAX_MS)
            }

        // ══════════════ 全局 UI 缩放 ══════════════
        //
        // 口径：[UI_SCALE_BASE] 是**基准**，把上一轮标定为"视觉刚好合适"的那一档（原 90%）
        // 固化成基准，于是设置页里的 100% 就是默认视觉 —— 用户不必再理解"为什么默认 90%"。
        // 实际 density 乘数 = UI_SCALE_BASE × percent / 100。
        //
        // 区间 90%~120%（实际 0.81×~1.08×）：下限 90% 对应 ButtonHeight 48dp × 0.81 ≈ 38.9dp，
        // 已接近 Android 48dp 触摸目标下限，再往下点击命中率会掉，所以不开放更小档；
        // 上限 120% 对应 1.08×，略大于设备原生尺寸，留给需要放大的场景。
        // MIN/MAX 公开（非 private）：设置页滑块的 valueRange 与 setter 的 coerceIn 必须
        // 出自同一份区间定义，否则两处会漂移。
        private const val KEY_UI_SCALE_PERCENT = "ufi_ui_scale_percent"
        const val UI_SCALE_BASE = 0.9f
        const val DEFAULT_UI_SCALE_PERCENT = 100
        const val UI_SCALE_PERCENT_MIN = 90
        const val UI_SCALE_PERCENT_MAX = 120

        // ══════════════ 胶囊视觉参数：键名 / 默认值 / 取值区间 ══════════════
        //
        // ★ 默认值口径（本轮整体等比放大 1.25× 调整，改动前必读）：
        //   五个 DEFAULT_* 现在等于**产品终稿视觉**，而不再是改造前的硬编码值 ——
        //   抬高 30dp（位置参数，刻意不动）、图标 26dp（原按 30×1.25 取 38，
        //   2026-09-04 因 Tab 单元格被撑成近正方形而降到 26，理由见下方 KEY_CAPSULE_ICON_SIZE_DP）、
        //   标签 10sp（8×1.25=10）、圆角 30dp（24×1.25=30）、
        //   图标文字间隔 0dp（0×1.25=0，位置参数，刻意不动）。
        //
        //   老用户若此前从未拖过滑块，升级后会看到新的终稿视觉（这是产品预期的一次性对齐）；
        //   拖过滑块的用户读得到自己的键，不受影响。
        //
        //   注意：组件里那几个同名的**回退常量**（UfiCapsuleBlurHost.CAPSULE_WINDOW_CORNER_RADIUS
        //   = 18.dp、UfiCapsuleTabBar.CAPSULE_CORNER = 18、ICON_SIZE = 24.dp）刻意**不动**：
        //   它们只在读到非法值时兜底，同时作为回归护栏的比对锚点（G21/G22）。
        //
        // MIN/MAX 公开（非 private）：设置页要用它们构造滑块的 valueRange 与 steps，
        // 保证「UI 限幅」与「setter 夹取」出自同一份区间定义，不会两处漂移。

        private const val KEY_CAPSULE_LIFT_DP = "capsule_lift_dp"
        const val DEFAULT_CAPSULE_LIFT_DP = 30
        const val CAPSULE_LIFT_DP_MIN = 0
        const val CAPSULE_LIFT_DP_MAX = 40

        // 图标尺寸：默认 26dp。上限 40dp 保留给"我就要大图标"的用户。
        // 2026-09-04 由 38 降到 26：38dp 会把 Tab 单元格撑成近正方形
        // （格宽 62dp、格高 = 图标 + 标签 ≈ 51dp），而选中滑块的圆角恒取自身半高 ——
        // 宽高比接近 1 时必然渲染成一个圆点，调圆角救不回来（用户反馈"还是个圆点"）。
        // 26dp 配 62dp 格宽后格高约 39dp，药丸约 58×39（比例 ~1.5），才读得出胶囊形。
        // 注意：已手动拖过「图标尺寸」滑块的设备 prefs 里存着自己的值，不受本默认值影响，
        // 需要在设置里「重置胶囊尺寸」才会拿到新观感。
        // 图标变大后的「顶到胶囊上边框被裁切」由 UfiCapsuleTabBar.CAPSULE_INNER_VPAD
        // （上下各 13dp 固定间隔）+ 两行布局呼吸空间共同兜住，40dp 档同样安全。
        private const val KEY_CAPSULE_ICON_SIZE_DP = "capsule_icon_size_dp"
        const val DEFAULT_CAPSULE_ICON_SIZE_DP = 26
        const val CAPSULE_ICON_SIZE_DP_MIN = 18
        const val CAPSULE_ICON_SIZE_DP_MAX = 40

        private const val KEY_CAPSULE_LABEL_TEXT_SP = "capsule_label_text_sp"
        const val DEFAULT_CAPSULE_LABEL_TEXT_SP = 10
        const val CAPSULE_LABEL_TEXT_SP_MIN = 8
        const val CAPSULE_LABEL_TEXT_SP_MAX = 14

        private const val KEY_CAPSULE_CORNER_DP = "capsule_corner_dp"
        const val DEFAULT_CAPSULE_CORNER_DP = 30
        const val CAPSULE_CORNER_DP_MIN = 10
        const val CAPSULE_CORNER_DP_MAX = 30

        // 图标与文字间隔：0 是**合法**下限（等价于两行完全贴拢），
        // 因此下游判据必须用 `>= 0`，不能沿用其它四项的 `> 0` 非法值判据。
        //
        // 默认 0dp（原 4dp，本轮按用户反馈调回 0）：UfiCapsuleTabBar 里它是两行
        // Column 的 verticalArrangement.spacedBy 间隔，直接参与 Column 排版；
        // Tab 高度 wrap 内容（iconSize + 间隔 + textHeight），任意取值都不会穿模
        // （图标底部锚定，放大也不会压到文字）。
        private const val KEY_CAPSULE_ICON_TEXT_SPACING_DP = "capsule_icon_text_spacing_dp"
        const val DEFAULT_CAPSULE_ICON_TEXT_SPACING_DP = 0
        const val CAPSULE_ICON_TEXT_SPACING_DP_MIN = 0
        const val CAPSULE_ICON_TEXT_SPACING_DP_MAX = 12
    }
}
