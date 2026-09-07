# `com.ufi_axis.ui.theme` — 设计令牌索引

> 建立于 2026-09-04（P3a/P3b，计划书 `docs/theme-migration-plan.md` 的 P3）。
> 目标是回答一个问题：**「我要改 X，去改哪个文件的哪一行？」**
>
> 为什么需要这份文档：改造前令牌散在 7 个文件 3 个包，其中动效令牌横跨
> `ui.components.common`（时长/缓动）与 `ui.animation`（spring）两个包 —— 想调一个动画时长
> 要在三处之间来回找。P3a 把动效令牌搬进本包后，**所有设计令牌都在 `ui.theme` 一个包里**，
> 本文件就是那个包的目录。
>
> ⚠ 行号是 2026-09-04 的实测值。改动这些文件后行号会漂移，**符号名是可靠的锚点，行号只是近路**。

---

## 0. 三十秒版

| 想改什么 | 打开 |
|---|---|
| 颜色（换皮肤 / 加皮肤） | `ThemePresets.kt` |
| 颜色（加一个语义色槽） | `ThemePalette.kt` |
| 信号/电量/图表这类"不跟随皮肤"的色 | `Color.kt` |
| 字号 / 行高 / 字距 | `Type.kt` 的 `Typography` |
| 某个位置该用什么文字样式 | `Type.kt` 的 `UfiTextStyles` |
| 圆角、组件尺寸、间距 | `Spacing.kt` |
| 形状语义（`xxxShape`）、阴影层级 | `UfiCardDefaults.kt` |
| 动画时长 / 缓动 / 按压缩放 / spring | `MotionTokens.kt` |
| 用户在设置页能拖的那些滑块 | `ThemeManager.kt`（**不是**令牌，见 §3） |

本包文件清单：

```
theme/
  Color.kt            域色 + 图表色 + 语义色便捷入口
  MotionTokens.kt     ★ 动效令牌（2026-09-04 新建，合并自两个包）
  Spacing.kt          圆角 + 组件尺寸 + 间距
  Theme.kt            UFIAXISTheme 根组合 + palette → M3 ColorScheme 桥
  ThemeLocals.kt      CompositionLocal（palette / UI 缩放补偿）
  ThemeManager.kt     ☆ 运行时用户设置的持久化（不是设计令牌）
  ThemePalette.kt     色槽定义 + 派生色算法
  ThemePresets.kt     ★ 唯一一套预设皮肤「默认」（2026-09-05 其余全部删除）

  Type.kt             字体族 + 字阶 + 字重 + 语义文字样式
  UfiCardDefaults.kt  形状语义 + elevation + 卡片 Modifier
  README.md           本文件
```

---

## 1. 索引表：改什么 → 去哪里

### 1.1 配色（跟随皮肤）

| 想改 | 文件 : 行 | 符号 | 影响面 |
|---|---|---|---|
| 某套皮肤的具体色值 | `ThemePresets.kt:27` / `:49` / `:71` / `:93` / `:115` / `:137` | `Default` / `TechBlue` / `MintGreen` / `DreamPurple` / `VibrantOrange` / `Aurora` | 只影响该皮肤。改 `Aurora` 影响默认用户（默认皮肤 id 在 `ThemeManager.kt:27`） |
| 新增一套皮肤 | `ThemePresets.kt:27~156` 后追加 + `:159` 加进 `allPresets` | `allPresets` | 设置页 `AppearanceSettingsScreen.kt:443` 自动遍历，**无需改任何 UI 代码** |
| 皮肤下拉里的顺序 | `ThemePresets.kt:159` | `allPresets` 的 `listOf` 顺序 | 只影响设置页排列 |
| 默认皮肤 | `ThemeManager.kt:27` **和** `:132` | 两处 `"aurora"` 字面量 | 两处必须同改（一处是首次读盘默认值，一处是跨实例监听的兜底值），只改一处会出现"重启后变回去" |
| 新增一个**语义色槽** | `ThemePalette.kt:11`（构造参数）+ `:97`（`resolve()`）+ `ResolvedPalette` `:126` | `ThemePalette` / `resolve` / `ResolvedPalette` | 加 `ResolvedPalette` 字段会让所有构造点编译失败（`resolve()` 与 `ColorTest`）——**这是护栏不是麻烦**，能挡住漏改。`ThemePalette` 新参数请给默认值，否则 6 个预设全要改 |
| 按压态 / disabled 的主色深浅档 | `ThemePalette.kt:190` / `:200`；比例常量在 `:291` / `:294` | `accentStrong` / `accentMuted` / `ACCENT_STEP` / `ACCENT_MUTE_RATIO` | 全站所有按压态与禁用态主色。改比例是"一行改全站"，改完两种明暗都要看 |
| 主色浅底（选中行底色） | `ThemePalette.kt:229` | `accentContainer` | 选中行、被强调卡片、M3 的 `primaryContainer` / `tertiaryContainer`（`Theme.kt:26`/`:52` 桥接） |
| 次级表面（卡内分区/进度槽） | `ThemePalette.kt:239` | `surfaceMuted` | 唯一亮暗取自不同 token 的角色（亮 = `pageBg`、暗 = `cardBg` 70%），改它要两种模式一起看 |
| 描边 / 分隔线的 alpha | `ThemePalette.kt:152` / `:156` / `:160` / `:208` / `:243` | `cardBorder` / `dialogBorder` / `toastBorder` / `inputBorder` / `chipUnselectedBorder` | 各自独立一档，分别对应卡片/弹窗/Toast/输入框/chip。**不要合并**：弹窗描边刻意比卡片重（层级区分） |
| 开关配色 | `ThemePalette.kt:88~92`（关闭态槽）+ `:168` / `:172`（开启态派生） | `switchTrackOff*` / `switchThumbOff*` / `switchTrackOn` / `switchThumbOn` | 全站所有 `UfiSwitch` |
| 遮罩 | `ThemePalette.kt:68` | `scrimLight` / `scrimDark` | 所有弹窗/抽屉背后的暗化层 |
| 品牌渐变 | `ThemePalette.kt:260` / `:266` / `:270` / `:280` | `auroraGradient` / `auroraOn` / `themeGradient` / `auroraSoft` | ⚠ 这四个当前**零调用点**（计划书 §2.4(d) 记录，P1d 待清）。改它们现在看不到效果 |
| M3 组件的取色（`MaterialTheme.colorScheme`） | `Theme.kt:21` | `buildColorSchemeFromPalette` | 所有裸 M3 组件。⚠ 里面 `onPrimary`/`onSecondary`/`onTertiary`/`onError` 仍写死 `Color.White`（`:25`/`:29`/`:33`/`:37` 与 `:51`/`:55`/`:59`/`:63`），浅色 accent 皮肤下会看不见字 |

### 1.2 域色（信号 · 电量 · 图表 · 流量 · 频段）

这一类**刻意不跟随皮肤**：它们编码的是"物理含义"（信号好=绿、告警=红），跟着主题变会丢掉语义。

| 想改 | 文件 : 行 | 符号 | 影响面 |
|---|---|---|---|
| 信号强度 5 档 | `Color.kt:15~19` | `SignalExcellent` / `Good` / `Fair` / `Poor` / `Dead` | 实际只被 `UfiSignalBars.kt` 内部使用（计划书 §2.4(c) 实测：三个 `*Color()` 函数外部零调用），影响面比看起来小 |
| 网络制式 4 档 | `Color.kt:22~25` | `Network5G` / `4G` / `3G` / `2G` | 同上 |
| 电量 3 档 | `Color.kt:28~30` | `BatteryHigh` / `Medium` / `Low` | 同上 |
| 上/下行流量色 | `Color.kt:33~34` | `TrafficDown` / `TrafficUp` | 流量卡片、图表图例 |
| 频段锁定绿/紫 | `Color.kt:38` / `:40` | `BandLte` / `BandNr` | 频段选择器。刻意与 `SignalGood` / `Network5G` 区分，不要合并 |
| 在线状态点 | `Color.kt:42` | `StatusOnline` | 首页连接卡。铺在渐变卡面上，故不跟随明暗 |
| 图表阈值线 | `Color.kt:44` / `:46` | `ChartAlert` / `ChartWarn` | 监控图表。`ChartWarn` 刻意比 `palette.warning` 更黄，避免与曲线撞色 |
| 中性描边灰 / 阴影基色 | `Color.kt:48` | `NeutralOutline` | 它是 `UfiCardDefaults.kt:241` `cardShadowColor` 的基色 → **改这一行会改全站卡片阴影颜色** |
| 图表 8 条曲线色 | `Color.kt:77`（`ChartColors(isDark)` 工厂）；数据类在 `:65` | `ChartColors` | 所有监控图表曲线。当前只随明暗、**不随皮肤**（冷调新皮肤下暖色曲线会冲突，计划书 P1e 待决） |
| 成功/警告/失败的统一取法 | `Color.kt:101` | `StatusRamp.good/warn/bad` | 它只是把 `palette.success/warning/error` 包一层，**真正的值在 `ThemePalette.kt:54~59`** |

### 1.3 字体族

| 想改 | 文件 : 行 | 符号 | 影响面 |
|---|---|---|---|
| 换整套字体 | `Type.kt:25`（接口）+ `:47`（注入） | `FontSet` / `setFontSet` | 全站。**只在首屏 `UFIAXISTheme` 组合之前调用有效**（`Typography` 是顶层 `val`，首次触达即固化）——写在 `Application.onCreate` |
| 当前默认字体 | `Type.kt:31` | `SystemFontSet` | 三族（display/body/mono）当前都指向系统字体 |
| 等宽字体 | `Type.kt:55` | `MonoFont` | 所有 `mono*` 文字样式（`Type.kt:285~299`）：dBm / IP / 速率 / 时间戳 / 日志 / 代码 |
| 让"标题族"与"正文族"真的不同 | `Type.kt:31~35` 让 `display` 与 `body` 返回不同 `FontFamily` | `SystemFontSet` | ⚠ 现状两者同为 `FontFamily.Default`，所以 `Type.kt:281` `listItemTitle` 注释里说的"靠字体族区分层级"**当前是失效的**，层级只由字号与字重承担 |

### 1.4 字阶（字号 / 行高 / 字距）

| 想改 | 文件 : 行 | 符号 | 影响面 |
|---|---|---|---|
| 任何一档的字号/行高/字距 | `Type.kt:57`（`Typography` 的 12 个槽，`:58~138`） | `Typography.headlineLarge … labelSmall` | **全站跟随**：`UfiTextStyles` 全部是 `get()` 派生而非缓存 `val`，所以改 `Typography` 一处，所有语义样式自动跟着变 |
| 某个"用在哪"的样式 | `Type.kt:175`（`UfiTextStyles`，`:179~311`） | 例：`caption:179` / `body:201` / `cardTitle:225` / `screenTitle:233` / `headerTitle:243` / `metricValue:256` | 只影响用了该语义名的调用点。**新增长尾不必加名字**，用 `UfiTextStyles.note.copy(...)` |
| 顶栏标题字号 | `Type.kt:243` / `:250` | `headerTitle`（22sp Bold 字距 0.56）/ `headerSubtitle`（14sp） | 所有页面顶栏。刻意从 `bodyLarge` 派生以与迁移前逐像素一致 |
| 胶囊导航标签 | `Type.kt:311` | `capsuleLabel` | ⚠ **不含字号**：字号由用户设置 `ThemeManager.capsuleLabelTextSp` 决定（见 §3）。刻意不复用 `caption`，因为胶囊栏图标偏移是按实测文字高度算的，行高一变几何跟着变 |

### 1.5 字重

| 想改 | 文件 : 行 | 符号 | 影响面 |
|---|---|---|---|
| 语义字重的实际取值 | `Type.kt:147`（`UfiWeight`，`:149~159`） | `Regular` / `Medium` / `Emphasis` / `Strong` / `Hero` / `Max` | **一行改全站**。例：想把"全站强调文字"从 Bold 降到 SemiBold，只改 `Strong`（`:155`）。业务代码禁止写 `FontWeight.Bold` 字面量 |

### 1.6 圆角

| 想改 | 文件 : 行 | 符号 | 影响面 |
|---|---|---|---|
| **全站基准圆角** | `Spacing.kt:12` | `CornerBase = 12.dp` | 卡片 / 按钮 / 输入框 / chip / toast / 小组件全部跟随（`UfiCardDefaults` 里 10 个 `*CornerRadius` 都指向它）。**这是"全站圆角改一档"的唯一入口** |
| 弹窗圆角 | `Spacing.kt:20` | `CornerDialog = 12.dp` | 只影响弹窗。当前与 `CornerBase` 同值；保留独立常量就是为了"弹窗单独变圆"只需改这一行 |
| 徽章/进度轨道 | `Spacing.kt:22` | `CornerMicro = 6.dp` | `microShape` |
| 圆角梯度其余档 | `Spacing.kt:27` / `:29` / `:31` / `:33` / `:35` / `:37` / `:39` / `:49` | `CornerTrack 4` / `CornerTag 5` / `CornerSmall 8` / `CornerMedium 14` / `CornerLarge 16` / `CornerCapsule 24` / `CornerBubble 25` / `CornerChatBubble 12` | 各自一个语义。**不要跨语义借用**——2026-09-03 就是因为 `chatBubbleShape` 借用 `CornerDialog`，导致"改弹窗圆角连带改短信气泡"才拆出 `CornerChatBubble` |
| 聊天输入框圆角 | `UfiCardDefaults.kt:177` | `chatInputCornerRadius = 20.dp` | ⚠ 这一档**没有走 `Spacing`**，直接写在 `UfiCardDefaults`。属遗留不一致（见 §6 末） |
| 细线轨道圆角 | `UfiCardDefaults.kt:106` | `hairlineCornerRadius = 1.dp` | 同上，也没走 `Spacing` |

### 1.7 形状语义（业务代码该用的东西）

业务代码**不要写 `RoundedCornerShape(N.dp)`**，用这里的语义 shape。

| 想改 | 文件 : 行 | 符号 | 影响面 |
|---|---|---|---|
| 语义 shape 总表 | `UfiCardDefaults.kt:38`（对象起始） | `shape:47` / `largeShape:42` / `smallShape:52` / `widgetShape:67` / `dialogShape:77` / `toastShape:82` / `inputShape:87` / `microShape:92` / `buttonShape:97` / `chipShape:102` / `bottomSheetTopShape:72` / `smsSheetTopShape:183` | 每个 `*Shape` 都从 `Spacing.Corner*` 派生 → **想调圆角改 `Spacing.kt`，想调"哪个组件用哪档"改这里** |
| 全圆 / 药丸 | `UfiCardDefaults.kt:112` | `pillShape`（`percent = 50`） | 进度条、圆点、药丸按钮。与 dp 无关，半径永远等于高度一半 |
| 梯度 shape | `UfiCardDefaults.kt:117` / `:122` / `:127` / `:132` / `:137` / `:142` | `trackShape` / `tagShape` / `subtleShape` / `mediumShape` / `iconTileShape` / `capsuleShape` | 分别对应 §1.6 的 `CornerTrack/Tag/Small/Medium/Large/Capsule` |
| 不对称气泡 | `UfiCardDefaults.kt:146`（控制台）/ `:167`（短信） | `consoleBubbleShape(isUser)` / `chatBubbleShape(isReceived)` | 两个函数内的"尖角"都写死 `4.dp`（`:150~151`、`:171~172`），不在 `Spacing` 里 |

### 1.8 组件尺寸

| 想改 | 文件 : 行 | 符号 | 影响面 |
|---|---|---|---|
| 卡片内边距/外边距 | `Spacing.kt:65~67` | `CardPadding 20` / `CardHorizontalMargin 16` / `CardBottomMargin 16` | 走 `UfiCardDefaults.padding:63` / `horizontalMargin:62` 的所有卡片 |
| 弹窗内边距/按钮高/底部留白 | `Spacing.kt:70~75` | `DialogPaddingH/V` / `DialogButtonHeight` / `DialogActionsBottom` / `DialogActionsWarningGap` | 所有 `Ufi*Dialog` |
| Toast 内边距 | `Spacing.kt:78~79` | `ToastPaddingH/V` | `UfiToastHost` |
| 开关几何 | `Spacing.kt:82~85` | `SwitchTrackWidth/Height` / `SwitchThumbSize/Margin` | 全站 `UfiSwitch`。四个值互相约束（thumb + 2×margin 应等于 track 高） |
| 顶栏高度 | `Spacing.kt:88~89` | `HeaderHeight 44` / `HeaderPaddingH 16` | 所有页面顶栏 |
| 按钮高度 | `Spacing.kt:92~93` | `ButtonHeight 48` / `SmallButtonHeight 36` | ⚠ 48dp 是 Android 触摸目标下限；注意它还会被 UI 缩放乘（见 §3，90% 档实际 ≈38.9dp，已到底线） |
| 按钮内距/描边/loading | `Spacing.kt:97~104` | `SmallButtonPaddingH 16` / `SmallButtonPaddingV 0` / `ButtonBorderWidth 1` / `ButtonLoadingIndicatorSize 18` | 2026-09-04 P4c 从 `UfiButton.kt` 提上来 —— 5 个旧按钮合并成 `UfiButton(variant, size)` 后这些是全 variant 共用档位。Standard 档内距吃 M3 `ButtonDefaults.ContentPadding`（h24/v8），不在此表 |
| 输入框高度 | `Spacing.kt:96` | `InputHeight 50` | 所有 `UfiTextField` 族 |
| chip 高度/内边距 | `Spacing.kt:99~100` | `ChipHeight 36` / `ChipPaddingH 16` | 所有 chip 族 |
| 图标尺寸 4 档 | `Spacing.kt:106~109` | `IconSizeSmall 18` / `Medium 20` / `Large 22` / `Dialog 22` | 走这些常量的图标（页面里硬编码的 `.dp` 不受影响，见下方⚠） |

> ⚠ **通用影响面提醒**：全仓 theme 目录外还有约 1314 处硬编码 `dp`（计划书 §2.5 实测），它们**不走 `Spacing`**。
> 所以改 `Spacing` 只影响真正引用了它的调用点；历史回填已明确不做（决策 D4），CI 只卡增量。
> 想"整页等比放大/缩小"请走 §3 的 UI 缩放（覆盖 `LocalDensity`，硬编码 dp 也会跟着缩）。

### 1.9 elevation / 阴影

| 想改 | 文件 : 行 | 符号 | 影响面 |
|---|---|---|---|
| 三档阴影高度 | `UfiCardDefaults.kt:58~60` | `elevationDp 4` / `elevationLevel2Dp 6` / `elevationLevel3Dp 10` | Level1 普通卡片 / Level2 重要卡片 / Level3 弹窗。经 `cardElevation():190`、`cardElevationLevel2():196`、`cardElevationLevel3():202` 提供给 M3 `Card`。⚠ 2026-09-04 二次修订后**二级页转场不再用阴影**（整屏 `shadowElevation` 是卡顿主因，见 §1.14） |
| 阴影**颜色** | `UfiCardDefaults.kt:241` | `cardShadowColor`（= `NeutralOutline` @30%） | 所有走 `ufiCardShadow` / `ufiStandardCard` 的 Box 类卡片。**基色在 `Color.kt:48`** |
| 阴影的画法 | `UfiCardDefaults.kt:258` | `Modifier.ufiCardShadow` | 刻意把 `spotColor` 设为透明、只留灰色环境光 → 扁平单色阴影。M3 1.4.0 的 `ColorScheme` 没有 `shadowColor` 字段、`Surface` 硬编码黑影，所以**无法通过主题一处改全站阴影色**，只能靠这个 Modifier。⚠ 阴影高度随动画每帧变的场景（二级页转场）不要用它 —— 它要在**组合期**拿到 `Dp`，等于把插值搬回重组路径；那里改用 `graphicsLayer` 的 `shadowElevation`，颜色仍取本表上一行的令牌 |
| 标准卡片一站式样式 | `UfiCardDefaults.kt:289`（默认 elevation 在 `:290`） | `Modifier.ufiStandardCard` | 阴影 + 圆角裁剪 + 底色 + 1dp 描边四件事。业务用它替代手写四链调用 |

### 1.10 动效时长

| 想改 | 文件 : 行 | 符号 | 影响面 |
|---|---|---|---|
| 时长梯度（17 档） | `MotionTokens.kt:188`（`Duration`） | `Flick 90:195` / `Micro 120:197` / `Swift 160:199` / `Quick 180:201` / `Base 200:203` / `Standard 220:205` / `Fluid 250:212` / `Gentle 280:214` / `Smooth 300:216` / `Sweeping 320:225` / `Deliberate 400:227` / `Emphatic 420:233` / `Reveal 600:235` / `Languid 800:237` / `Orbit 1000:239` / `Ambient 1200:241` / `Pulse 1500:243` | 改一档 = 改所有引用该档的调用点。高危档：`Base`（全站最常用）、`Sweeping`（页面转场锚点，7 个调用点） |
| **页面转场默认时长** | `MotionTokens.kt:225` → 实际拼装在 `animation/page/UfiPageTransition.kt:33` | `Duration.Sweeping` → `UfiPageTransitionDefaultSpec` | 接口默认 spec（`UfiPageTransition.kt:85`）与宿主 fallback（`UfiPageSwitcherHost.kt:1421`）共用它。**这是 P2c 修过的 bug 隐患**：以前两处各写一遍 320，改一处另一处静默不同步 |
| 标题模糊时长 | `MotionTokens.kt:367` | `HEADER_TITLE_BLUR_MS = 350` | 只影响 `UfiHeader` 的标题模糊。**有意例外**（不在梯度上），原名 `PAGE_TRANSITION_MS` 名不符实已改名 |
| 列表交错延迟 | `MotionTokens.kt:378` | `STAGGER_DELAY_MS = 35L` | `Modifier.staggeredEntrance`（`animation/StaggeredEntrance.kt:40`）。第 N 项延迟 = N × 本值，改成 50 会让第 10 项从 350ms 涨到 500ms |
| 数据刷新淡入 | `MotionTokens.kt:381` | `DATA_FADE_MS`（= `Duration.Smooth`） | 别在这里改数值，它只是 `Smooth` 的别名 |
| 胶囊收起时长 | `ui/navigation/MainNavGraph.kt:109` | `CAPSULE_HIDE_MS = 360` | ⚠ **唯一留在 `ui.theme` 外的时长**，`private const val`、单一调用点（`:448`）、有意例外。若哪天它有了第二个使用点，就该收进 `Duration` |

### 1.11 动效缓动

| 想改 | 文件 : 行 | 符号 | 影响面 |
|---|---|---|---|
| 语义缓动集合 | `MotionTokens.kt:278`（`Easing`） | `Standard:280` / `Linear:282` / `Accelerate:284` / `EmphasizedIn:286` / `EmphasizedOut:288` / `Overshoot:290` | 业务禁止直接 import Compose 的 easing 常量，一律走这里 |
| 贝塞尔控制点本身 | `MotionTokens.kt:150` / `:154` / `:158` | `UfiAnimSpecs.toastDropEasing` / `emphasizedInEasing` / `emphasizedOutEasing` | 这三条曲线的**数值唯一来源**。`UfiMotion.Easing` 的 `Overshoot` / `EmphasizedIn` / `EmphasizedOut` 只是转发 |

### 1.12 spring 参数

| 想改 | 文件 : 行 | 符号 | 影响面 |
|---|---|---|---|
| 17 个 spring token | `MotionTokens.kt:61`（`UfiAnimSpecs`） | 按压类 `clickScale:66` / `buttonPress:69` / `controlPop:72` / `sendPop:75`；入场类 `staggerEnter:80` / `dialogEnter:83` / `toastDrop:86` / `panelEnter:89`；选中类 `tagPop:94` / `tooltipPop:97` / `tabSlider:100` / `pagePulse:103`；连续量 `switchThumb:108` / `colorSettle:111` / `colorSwap:114` / `sliderTrack:117` | 改 `(dampingRatio, stiffness)` 即改该类交互的手感。业务应经 `UfiMotion` 的同名语义入口（`:302~357`）调用，不要直接 import 本层。2026-09-04 删掉了零调用的 `navPress`（唯一使用点 `PaginationNavButton` 已按分层规则改用 `buttonPress`） |
| 胶囊展开刚度 | `MotionTokens.kt:132` | `CapsuleExpandStiffness = 3000f` | 唯一以裸常量暴露的 spring 参数。原因：`CapsuleRegressionGuardTest` 用正则要求 `UfiCapsuleTabBar.kt:117~118` 的 `EXPAND_SPRING` 赋值处 40 字符内出现字面 `spring(`，**所以那个调用点必须保留 `spring(` 文本，不能改写成 spec 函数** |
| 为什么是 `fun <T>` 而不是 `val` | `MotionTokens.kt:50~53`（KDoc） | — | 调用点既有 `animateFloatAsState` 也有 `animateColorAsState` / `animateDpAsState`，`SpringSpec<T>` 的 `T` 必须由调用处推断 |

### 1.13 按压缩放档位

| 想改 | 文件 : 行 | 符号 | 影响面 |
|---|---|---|---|
| 4 档缩放值 | `MotionTokens.kt:272`（`PressScale`） | `Fab 0.92:274` / `Cell 0.94:276` / `Button 0.96:278` / `Chip 0.97:286` | 全站 13 处按压反馈。**唯一规则：元素面积越小，缩得越多**；同一面积量级必须同档（详见 §5 的分层规则） |
| 配套动画 spec | `MotionTokens.kt:255~260`（KDoc 规定的配对） | `Fab→controlPop()` / `Button→buttonPress()` / `Cell·Chip→tween(Duration.Micro)` | 不要自由组合：密排元素带回弹会互相"打架" |
| 按压**播放方式** | `animation/PressFeedback.kt` | `rememberUfiPressScale` / `Modifier.ufiPressScale` / `rememberUfiPressed` | 全站唯一实现。缩放走前两个（事件驱动 + 最短保持 120ms，短按也有可见幅度）；**按压态配色**走 `rememberUfiPressed`（把按下态保底 120ms 再喂给 `animateColorAsState`）。⚠ 不要退回 `collectIsPressedAsState` + `animateFloatAsState/animateColorAsState`，短按看不见 |

### 1.14 二级页转场的深度层次（「离场页后退」模型）

> 2026-09-04 新增，同日**二次修订**。二级页（宿主 ↔ detail、detail ↔ detail）转场原来只有
> 水平平移 —— 单一图层在动，没有层次。
>
> 上午的第一版是「进场页当卡片浮起」（缩放 + 圆角 + 中段阴影）。真机实测两个问题，已推翻：
> 1. **卡顿** —— 整屏 `shadowElevation`（大面积 RenderNode 投影每帧重算）+ `clip = true`
>    （离屏裁剪）+ `scale`，中低端机上很贵；
> 2. **顶部/底部各有一块不动的区域，割裂感重** —— 根因是**被转场动画包裹的只有页面内容**：
>    `MainNavGraph` 把 `Scaffold` 的 `innerPadding`（默认 `safeDrawing`，含状态栏 / 导航栏）
>    加在**包着 NavHost 的那个 `Box`** 上，于是状态栏色带与底部导航栏色带由 `Scaffold`
>    的 `containerColor` **静态**绘制；底部胶囊 `UfiCapsuleTabBar` 更是活在独立的 `Dialog`
>    窗口里，压根不在这棵 Compose 树上。进场页一旦 `scale = 0.94`，四周立刻露出这些静止
>    的 chrome，圆角把这条缝描得更清楚。
>
> **现行模型（Material 共享轴 + 深度 / Android 预测性返回的标准做法）**：
> - **进场页**：只平移，**不缩放、不圆角、不投影**，始终满屏铺满 ⇒ 任何时刻都盖住下层，
>   缝无从出现，同时省掉最贵的两项；
> - **离场页**（在下层、被进场页覆盖）：`scale 1 → 0.96` + `scrim` 淡入到 12%，
>   深度感由"下层后退变暗"提供；
> - pop 方向整套反向：回到的上级页 `0.96 → 1` + scrim 淡出，被关掉那页只平移出屏。

| 想改 | 文件 : 行 | 符号 | 影响面 |
|---|---|---|---|
| **时长 / 总开关** | `navigation/Navigation.kt` | `ufiNavTransitionDurationMs(rawMs, systemReduceMotion)` | **唯一来源**。把 `ThemeManager.transitionDurationMs`（150~600，`0`=关闭）与系统降低动效合并成一个数：`>0` 是 tween 时长，`0` ⇒ 平移/后退缩放/scrim **一并**不生效。`-1`（未播种哨兵）回落默认档而不是"关闭" |
| 曲线 | 同上 | `ufiNavTransitionSpec<T>(durationMillis)` | 平移、淡入、后退进度共用它 ⇒ 不会出现"位移停了 scrim 还在淡"。曲线 = `Easing.Standard` |
| 进度 | 同上 | `ufiNavRecedeLayer` 里的 `transition.animateFloat` | 挂在 NavHost 的 `Transition<EnterExitState>` 上，**可被可预测性手势返回逐帧 seek**。⚠ 不要改成 `animateXxxAsState` —— 那是第二个时长来源（P2c 修过的漂移） |
| 幅度映射 | 同上 | `UfiNavRecedeProfile`（纯函数） | 只剩 `recedingScale` / `scrimAlpha`。纯 JVM 可测，见 `UfiNavRecedeProfileTest` |
| 角色判定（谁是**下层**） | `navigation/Navigation.kt` | `UfiNavRecedeRole.recedingEntryId`，由 `detailExit`（push ⇒ `initialState.id`）/ `detailPopEnter`（pop ⇒ `targetState.id`）写入；图层侧 `ufiNavRecedeLayer(isReceding = …)` 比对 id | ⚠ **不要改回 `navController.visibleEntries` 栈顶**（2026-09-05 P0 修）。`populateVisibleEntries()` 先收 `transitionsInProgress` 里 `maxLifecycle < STARTED` 的 entry（含已出栈、正在跑退场动画的那页）再收 `backQueue`，所以 `.last()` 的语义是「**进场页**」而不是「前景页」—— pop 时进场页恰恰是下层 ⇒ 角色判反，返回动画里 detail 吃缩放+scrim、宿主完全不动。也**不要**改用 `PostExit`（那是「离场页」，pop 方向同样错）。「下层」必须由**方向**决定，而方向就藏在「哪一组转场函数被调用」里。持有者刻意是普通 `var`（非 `MutableState`）⇒ 只在 `graphicsLayer` / `drawWithContent` 的 lambda 里读，零重组 |
| 缩放幅度 | `MotionTokens.kt:309`（`NavRecede`） | `ScaleTo 0.96:316` | 只作用于**离场页**。整屏量级只能落在 0.94~0.97（面积越大缩得越少，与 `PressScale` 同一条规律）。⚠ **不要再加"进场页缩放"档位**（原 `CardScaleFrom 0.94` 已删）—— 见本节开头第 2 条 |
| 离场页遮罩浓度 | `MotionTokens.kt:324` | `NavRecede.ScrimAlpha = 0.12f`，色取 `palette.scrim` | 只取峰值 0.12：scrim 原强度是弹窗遮罩（黑 35%/50%），整屏用原值会让返回过程中下层黑成一片。scrim 画在**同一条 modifier 链**的 `drawWithContent` 里（不另起 `Box` + `background`，那样多一个 layout 节点与一层绘制） |

### 1.15 间距


> 本轮**没有**新增 `Space XS/S/M/L/XL` 阶梯（计划书 P3d，可选项，本次跳过）。
> 现状是"语义命名的间距"而不是"尺度阶梯"，下表是全部入口。

| 想改 | 文件 : 行 | 符号 | 影响面 |
|---|---|---|---|
| 页面边距 | `Spacing.kt:52` | `PagePadding 12` | 走它的页面容器 |
| 通用间距 4 档 | `Spacing.kt:56~59` | `Small 4` / `Medium 8` / `Large 12` / `XLarge 16` | 最接近"阶梯"的一组，但缺 24/32/48 档 |
| 分组间距 | `Spacing.kt:53` / `:55` / `:112` / `:113` | `GroupSpacing 8` / `SectionTop 12` / `SectionSpacing 24` / `SectionTitleToCard 12` | 设置页分组、区块标题与卡片的距离 |
| 卡内边距 | `Spacing.kt:54` / `:65` | `InnerPadding 12` / `CardPadding 20` | 两者并存：`InnerPadding` 是遗留名，`CardPadding` 是 `UfiCardDefaults.padding` 的来源 |

---

## 2. 依赖方向（改动前必读）

```
Spacing ──────► UfiCardDefaults      （圆角基准 → 形状语义）
Typography ───► UfiTextStyles         （字阶 → 语义样式）
ThemePalette ─► ResolvedPalette       （成对色槽 → 按明暗拍平）
UfiAnimSpecs ─► UfiMotion             （spring/贝塞尔数值 → 语义 token）
Color.NeutralOutline ─► cardShadowColor
```

**这些箭头都是单向的，不要反向引用。** 最容易踩的一条：`UfiAnimSpecs.fadeEnter`（`MotionTokens.kt:146`）
里的 `250` 写成字面量而不是引 `UfiMotion.Duration.Fluid` —— 因为明细层不能依赖语义层，
否则"数值唯一来源"这句话就不成立了。P3a 把两层合并到同一个文件后这条约束依然有效：
**同文件不等于可以互相引用**。改 `Fluid` 时请顺手同步 `fadeEnter`（其 KDoc 已注明）。

---

## 3. 「设计令牌」 vs 「用户可调设置」——改错地方会白费功夫

这是最容易走错的一处。两者都在 `ui.theme` 包里，但性质完全不同：

| | 设计令牌 | 用户可调设置 |
|---|---|---|
| 位置 | `Color.kt` / `Spacing.kt` / `Type.kt` / `UfiCardDefaults.kt` / `MotionTokens.kt` / `ThemePalette.kt` / `ThemePresets.kt` | **只在 `ThemeManager.kt`** |
| 生效时机 | 编译期常量，改了要重新编译 | 运行时，用户在「设置 → 外观」拖滑块即刻生效 |
| 谁决定 | 开发者 | 每个用户各自决定（存 `SharedPreferences`，文件 `ufi_axis_prefs`） |
| 改动影响 | 所有用户、所有设备 | 只影响"没自己调过"的用户（改 `DEFAULT_*`）或取值区间（改 `MIN/MAX`） |

**`ThemeManager.kt` 里的运行时设置清单**（这些**不是**令牌）：

| 设置项 | 读取出口 | 默认 / 区间 | 注意 |
|---|---|---|---|
| 转场时长 | `transitionDurationMs:68`，setter `:221` | 默认 `TRANSITION_DURATION_DEFAULT_MS = 380`（`:393`）；区间 `MIN 150`（`:384`）~ `MAX 600`（`:390`）；`OFF = 0`（`:378`） | `0` 不是"时长 0 的转场"，而是切到降低动效通道（接 `LocalUfiReduceMotion`）。0 与 150 之间**刻意留空**（150ms 以下整屏转场只会被感知成闪帧）。上限**不要放宽**（>600ms 会被感知成卡顿）。越界值由 `normalizeTransitionDuration:402` 兜住。**2026-09-04 起本设置同时管 Tab 切页与二级页转场**（二级页那条链路的归一化入口是 `navigation/Navigation.kt` 的 `ufiNavTransitionDurationMs`）—— 此前二级页写死 320，同一个滑块只管半个 App |
| 转场动画类型 | `pageTransition:61`，setter `:201` | 默认 `"fade"` | 策略 id 由 `UfiPageTransitions` 注册表解析 |
| 过渡模糊开关 | `blurEnabled:74`，setter `:230` | 默认 `true` | 只作用于 **Tab 切页**（`UfiPageSwitcher` 宿主的 `applyTransitionBlur`），经 `LocalUfiBlurEnabled` 下传；弱机由 `UfiPageSwitcherDefaults.isBlurSupported` 强制降级。⚠ 2026-09-05 曾被误当成"切页抽搐/卡顿"的根因整体删除，已完整还原 —— 真实根因是胶囊索引双数据源 / `UfiScreenScaffold` 缺 `background()` / 胶囊窗口 relayout 重放这三处。正向护栏见 `NavInsetHandoffGuardTest.pageSwitcher_mustBlurDuringTransition` |
| 全局 UI 缩放 | `uiScalePercent:84`，setter `:239` | `UI_SCALE_BASE = 0.9f`（`:421`）× `percent/100`；默认 100（`:422`）；区间 90~120（`:423~424`） | 落地方式是在 `Theme.kt:117~125` 覆盖 `LocalDensity`，**不是**改 `Spacing`/`Type` 常量 —— 全仓约 1500 处硬编码 dp 不走 `Spacing`，改常量只会"卡片变小内部间距不变"。下限 90% 已让 `ButtonHeight 48dp` 落到 ≈38.9dp（接近触摸目标下限），不要再放低 |
| 胶囊抬高 | `capsuleLiftDp:98`，setter `:257` | 默认 30（`:445`），区间 0~40（`:446~447`） | 位置参数 |
| 胶囊图标尺寸 | `capsuleIconSizeDp:104`，setter `:267` | 默认 38（`:455`），区间 18~40（`:456~457`） | 上限必须 ≥ 默认值，否则 `coerceIn` 会把默认值夹掉 |
| 胶囊标签字号 | `capsuleLabelTextSp:110`，setter `:277` | 默认 10（`:460`），区间 8~14（`:461~462`） | 与令牌 `UfiTextStyles.capsuleLabel`（`Type.kt:311`）**配合**：样式来自令牌、字号来自本设置 |
| 胶囊圆角 | `capsuleCornerDp:116`，setter `:288` | 默认 30（`:465`），区间 10~30（`:466~467`） | 与 `Spacing.CornerCapsule`（24dp）**无关**，别改错 |
| 胶囊图标文字间隔 | `capsuleIconTextSpacingDp:127`，setter `:299` | 默认 0（`:477`），区间 0~12（`:478~479`） | 0 是**合法**下限，下游判据用 `>= 0` 而不是 `> 0` |
| 一键恢复胶囊默认 | `resetCapsuleMetrics:312` | — | 复用五个 setter，保证"重置路径"与"拖动路径"行为不漂移 |
| 皮肤 id | `selectedThemeId` | 默认 `ThemePresets.DEFAULT_ID`（`"default"`） | 皮肤**内容**是令牌（`ThemePresets.kt`），**选了哪个**是用户设置。合法 id = 7 套预设 + `"custom"`（见 `ThemePresets.SELECTABLE_IDS`） |
| 明暗模式 | `themeMode` | `AUTO` | companion 共享 flow + `themeModeSeeded` 标记位 |
| 动态取色开关 | `dynamicEnabled` / `setDynamicEnabled` | 关 | 与皮肤 id **正交**的独立开关，开启时覆盖皮肤（`buildDynamicPalette`）；播种与 setter 都过能力闸门 |
| 自定义皮肤的种子色 | `customSeedColor` / `setCustomSeedColor` | `CUSTOM_SEED_UNSET`（0 = 从未选过） | 一个 ARGB Int 推出全部 17 槽（`CustomPalette.kt` 的 `buildCustomPalette`）。**不是**当年那个只覆盖 accent 的 `customAccentColor`（死代码，2026-09-05 上午删除），键名也刻意换成 `ufi_custom_seed_color` |

> 上面四项**全部**是 companion 共享 flow + 独立播种标记位，且 `prefListener` 里**没有**它们的分支 —— 详见 `ThemeManager` 里那几段长注释（同一个「改了要杀进程重进才生效」的 bug 修过七次）。
> `getCurrentPalette()` 是非 Composable 的 `.value` 直读，宿主侧的 `remember` key 必须覆盖 `selectedThemeId` / `dynamicEnabled` / `customSeedColor` 三项（由 `NavInsetHandoffGuardTest` 钉住）。

设置页 UI 在 `app/feature-settings/.../AppearanceSettingsScreen.kt`（转场时长写入 `:324`、UI 缩放写入 `:413`、皮肤列表遍历 `:443`）。
`MIN/MAX` 之所以是 `public`：**滑块的 `valueRange` 与 setter 的 `coerceIn` 必须出自同一份区间定义**，否则两处会漂移。

另有三个组件内的**回退常量**刻意不与用户设置对齐，别去"修"它们：
`UfiCapsuleBlurHost.CAPSULE_WINDOW_CORNER_RADIUS = 18.dp`（`:163`）、
`UfiCapsuleTabBar.CAPSULE_CORNER = 18`（`:88`）、`UfiCapsuleTabBar.ICON_SIZE = 24.dp`（`:93`）——
它们只在读到非法值时兜底，同时是回归护栏的比对锚点。

---

## 4. 规范名 ↔ 现名映射表

`docs/design-system-spec.md` 用 M3 语义命名，本项目用自研命名。
**项目有意不改名（决策 D1）**：改名要重写 405 处引用，属纯风格 churn，不减少任何入口（决策 D8）。
所以需要这张桥梁表 —— 拿规范当"色值/字阶取值参考"时照它翻译。

### 4.1 颜色

| 规范（M3 语义） | 本项目 | 位置 |
|---|---|---|
| `primary` | `accent` | `ThemePalette.kt:15`（槽）/ `:98`（拍平） |
| `onPrimary` | `onAccent` | `ThemePalette.kt:46` |
| `primaryContainer` / `accentSoft` | `accentContainer` | `ThemePalette.kt:229` |
| `onPrimaryContainer` | *（无独立槽，复用 `textPrimary`）* | 桥接见 `Theme.kt:27` / `:53` |
| `secondary` | `accentSecondary` | `ThemePalette.kt:18` |
| `surface` | `cardBg` | `ThemePalette.kt:24` |
| `surfaceVariant` | `surfaceMuted` | `ThemePalette.kt:239` |
| `background` | `pageBg` | `ThemePalette.kt:21` |
| `onSurface` | `textPrimary` | `ThemePalette.kt:27` |
| `onSurfaceVariant` | `textSecondary` | `ThemePalette.kt:30` |
| `outline` | `textSecondary`（M3 桥接）/ 语义上是 `cardBorder` 一族 | `Theme.kt:46` / `:72`；`ThemePalette.kt:152` |
| `outlineVariant` | `divider` | `ThemePalette.kt:33`；桥接 `Theme.kt:47` / `:73` |
| `error` / `onError` | `error` / `onError` | `ThemePalette.kt:49` / `:79` |
| `accentStrong` | `accentStrong`（同名） | `ThemePalette.kt:190` |
| `accentMuted` | `accentMuted`（同名） | `ThemePalette.kt:200` |

### 4.2 字阶

| 规范角色 | 规范值 | 本项目最接近 | 位置 |
|---|---|---|---|
| Display 28–32 / ≥700 | — | `Typography.headlineLarge` 28sp Bold | `Type.kt:58` |
| Title 18–20 / ≥600 | — | `titleLarge` 20sp SemiBold | `Type.kt:79` |
| Body 14 / =400 | — | `bodyMedium` 14sp Normal | `Type.kt:104` |
| Label 13–14 / ≥500 | — | `labelLarge` 13sp Medium | `Type.kt:118` |
| Caption 12 / =400 | 12sp | `labelSmall` **11sp** Medium | `Type.kt:132`（字号在 `:135`） |

### 4.3 圆角

| 规范 | 规范值 | 本项目 | 位置 |
|---|---|---|---|
| `cornerXS` | 4 | `CornerTrack` 4dp | `Spacing.kt:27` |
| `cornerS` | 8 | `CornerSmall` 8dp | `Spacing.kt:31` |
| `cornerM` | 12 | `CornerBase` 12dp ✅ 已对齐 | `Spacing.kt:12` |
| `cornerL` | 16 | `CornerLarge` 16dp（但**弹窗**用的是 `CornerDialog` 12dp） | `Spacing.kt:35` / `:20` |
| `cornerXL` | 24 | `CornerCapsule` 24dp（值同、语义不同：项目里是"胶囊按钮"而非"大容器/浮层"） | `Spacing.kt:37` |
| `cornerFull` | 999 | `UfiCardDefaults.pillShape`（`percent = 50`，等价） | `UfiCardDefaults.kt:112` |

### 4.4 动效

| 规范 | 规范值 | 本项目最接近 |
|---|---|---|
| `D100` 按压 | 100ms | `Duration.Micro` 120（`MotionTokens.kt:197`） |
| `D200` 状态切换 | 200ms | `Duration.Base` 200 ✅（`:203`） |
| `D300` 卡片/弹窗入场 | 300ms | `Duration.Smooth` 300 ✅（`:216`） |
| `D500` 页面转场 | 500ms | **无 500 档**；页面转场实际用 `Sweeping` 320（`:225`） |
| stagger 40ms | 40ms | `STAGGER_DELAY_MS` 35L（`:378`） |

### 4.5 有意例外清单（**不要"顺手改齐"**）

| 项 | 现值 | 规范 | 为什么保留 |
|---|---|---|---|
| `Spacing.CornerTag`（`:29`） | 5dp | 4 基栅格里没有 5 | 从 94 处字面量收敛时**刻意保留原观感**，抹平成 4 会改一批行内小块 |
| `Spacing.CornerBubble`（`:39`） | 25dp | 最近的是 `cornerXL` 24 | 控制台会话气泡原值。⚠ 它与短信气泡 `CornerChatBubble` 12dp 差一倍，两处都是"聊天气泡"却观感不同 —— 属待收敛项（计划书 P4e），但那是**组件一致性**问题，不是"对齐规范"问题 |
| `UfiTextStyles.caption`（`Type.kt:179` → `labelSmall` 11sp，`:135`） | 11sp | Caption 12sp | 全站最高频样式（约 123 处）。抬到 12sp 会让密集列表整体变高，属大范围观感变更，需单独评审 |
| `UfiMotion.HEADER_TITLE_BLUR_MS`（`MotionTokens.kt:367`） | 350ms | 不在任何梯度 | 刻意比页面转场 320 长半拍，让"标题最后一个变清晰"。吸附到 320 差 30ms 超出 20ms 容差，吸到 400 明显拖沓 |
| `MainNavGraph.CAPSULE_HIDE_MS`（`:109`） | 360ms | 不在任何梯度 | 胶囊收起的慢-快-慢曲线专用。单一调用点、`private`，收进梯度会为一个用途多开一档 |

---

## 5. 新增组件时怎么选令牌

规范 §7.7 的落地版。**按顺序**走，不要跳步：

1. **先复用**。用现有语义令牌 + `Spacing` 尺度表达；内边距与触控尺寸对齐最近的组件族
   （按钮族看 `Spacing.kt:92`，列表行看 `UfiTextStyles.listItemTitle`，卡片看 `UfiCardDefaults.shape`）。
   最小触控 48×48dp，注意 UI 缩放 90% 档会把 48dp 压到 ≈38.9dp。
2. **派生优先**。缺色时先从强调色 5 档梯度派生 —— `accent`（`ThemePalette.kt:98`）/
   `accentStrong`（`:190`）/ `accentContainer`（`:229`）/ `onAccent`（`:107`）/ `accentMuted`（`:200`）。
   **不要新增色相**：色相一多，换皮肤时就有东西跟不上。
3. **确需新增才新增**，命名 `<category><Role><Variant>`（例 `statusWarningBg`），
   加进 `ThemePalette` 成对色槽（light/dark 都要给），**并在本 README §1 对应小节登记一行**。
   没登记的令牌等于不存在 —— 下一个人找不到它，就会再造一个。
4. **过对比度闸门**。正文 ≥4.5:1、大文本（≥18sp 或 ≥14sp+700）≥3:1、
   **按钮/图标等 UI 组件 ≥3:1**（WCAG 1.4.11，不是 1.4.3）。不过就回退到相邻梯度。
   分档而非一刀切的理由：高明度橙一类 accent 配白字物理上过不了 4.5:1，一刀切的结局必然是事后人为放宽。
5. **禁硬编码**。组件里不出现裸色值 / 裸字号 / 裸 `tween(数字)` / 裸 `RoundedCornerShape(N.dp)`。
   PR 红线就是"无字面量"。

### 5.1 按压反馈的分层规则（2026-09-04 P2d 定）

**唯一规则：元素面积越小，缩得越多。** 同一面积量级的元素**必须**落在同一档，
跨档只允许因为"面积量级不同"，不允许因为"这个页面我想更明显一点"。

| 档 | 值 | 用于 | 配套 spec |
|---|---|---|---|
| `PressScale.Fab`（`MotionTokens.kt:269`） | 0.92 | FAB（全站唯一浮起圆钮） | `controlPop()`（带回弹，浮起元素才配得上过冲） |
| `PressScale.Cell`（`:271`） | 0.94 | 密排网格小格（排程选择器 7 列日期格） | `tween(Duration.Micro)` |
| `PressScale.Button`（`:273`） | 0.96 | 按钮族（`UfiButton` + 页面内 OutlinedButton 图标按钮 + 分页导航 pill 按钮） | `buttonPress()`（无回弹、紧实） |
| `PressScale.Chip`（`:281`） | 0.97 | chip / 选项格 / 可点列表行（面积最大的一类可点区） | `tween(Duration.Micro)` |

反面教材（收编前的真实状态）：监控页三个筛选 chip 各自写 `0.94`，而公共 `CategoryChip` 与弹窗
选项格写 `0.97` —— 同一种胶囊筛选器在不同页面缩不一样多。这正是 G1「同一种按钮有不同动画表现」
的典型形态，所以现在**值和 spec 都按档固定，不要自由组合**。

---

## 6. 动效令牌的迁移状态（读旧代码时会遇到）

P3a 只搬定义、**没有改约 287 处调用点**（一次性改会让 diff 大到无法评审）。所以现在有两条路径并存：

| | 路径 | 状态 |
|---|---|---|
| 新代码 | `com.ufi_axis.ui.theme.UfiMotion` / `UfiAnimSpecs` | ✅ 唯一真实定义（`theme/MotionTokens.kt`） |
| 旧代码 | `com.ufi_axis.ui.components.common.UfiMotion`（`Ufi.kt`）<br>`com.ufi_axis.ui.animation.UfiAnimSpecs`（`UfiAnimations.kt`） | ⚠ `@Deprecated` 转发壳，**零行为、零字面量**，只把调用转到 `ui.theme` |

要点：

- 旧 import **零改动仍可编译**，只会拿到废弃警告。改一个文件时顺手把 import 换到 `ui.theme` 即可，
  不必成批改。全部迁完后删掉两个转发壳。
- 用**转发 `object`** 而不是 `typealias`：Kotlin 的 `typealias` 只在类型位置生效，
  `UfiMotion.Duration.Base` 这种"通过对象名访问成员"的表达式位置用不了别名，旧调用点会直接编译失败。
- **同时 `*` 导入 `components.common` 与 `theme` 的文件会撞名**（两个同名 object 优先级相同 →
  `Overload resolution ambiguity`）。解法是加一行显式 `import com.ufi_axis.ui.theme.UfiMotion`
  ——显式导入优先级高于星号导入。实测本仓只有 `MonitorScreen.kt` 命中，已加（见其 import 处注释）。

### 6.1 已知的遗留不一致（登记但本轮未改）

1. `UfiCardDefaults.chatInputCornerRadius = 20.dp`（`:177`）与 `hairlineCornerRadius = 1.dp`（`:106`）
   **没有走 `Spacing.Corner*`**，是形状语义层里直接写的 dp。其余 12 个 `*CornerRadius` 都是从
   `Spacing` 派生的，所以"改圆角只改 `Spacing.kt`"这句话对这两档不成立。
2. `consoleBubbleShape` / `chatBubbleShape` 里的"尖角" `4.dp`（`:150~151` / `:171~172`）同样是就地字面量。
3. `Theme.kt` 的 M3 桥接里 `onPrimary` / `onSecondary` / `onTertiary` / `onError` 仍写死 `Color.White`
   （`:25`/`:29`/`:33`/`:37`、`:51`/`:55`/`:59`/`:63`），而 `ResolvedPalette` 已经有可配置的
   `onAccent` / `onError` 色槽 —— 浅色 accent 皮肤下裸 M3 组件会出现白字白底。属换肤链路残留（计划书 P1c 范畴）。
4. 同一个 `outline` 概念在项目里有两套答案：M3 桥接把 `outline` 接到 `textSecondary`（`Theme.kt:46`/`:72`），
   而语义上的描边色是 `cardBorder` 一族。裸 M3 组件的描边因此比自研组件重得多。
5. `auroraGradient` / `auroraOn` / `auroraSoft` / `themeGradient`（`ThemePalette.kt:260~287`）当前零调用点。
6. 间距仍是"语义命名"而非"尺度阶梯"，缺 24/32/48 档（P3d 可选项，本轮跳过）。
