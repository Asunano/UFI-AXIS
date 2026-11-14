# `com.ufi_axis.ui.theme` — 设计令牌索引

> 本文件回答一个具体问题：**「要修改 X，应改动哪个文件的哪一行？」**
> 只描述当前代码结构，不记录未完成事项。
>
> 所有设计令牌均位于 `ui.theme` 一个包内，本文件即该包的目录。
>
> ⚠ **行号仅作辅助定位，符号名才是锚点。** 行号为实测值；`ThemePalette`（色槽已扩展至 42 个
> 构造参数）、`UfiCardDefaults`（已移入 `theme/`）、`MotionTokens` 均有改动，**行号存在整体偏移**。
> 无法按行号定位时，请按符号名检索。

---

## 0. 快速索引

| 目标 | 文件 |
|---|---|
| 颜色（换皮肤 / 加皮肤） | `ThemePresets.kt` |
| 颜色（加一个语义色槽） | `ThemePalette.kt` |
| 信号/电量/图表这类"不跟随皮肤"的色 | `Color.kt` |
| 字号 / 行高 / 字距 | `Type.kt` 的 `Typography` |
| 某个位置该用什么文字样式 | `Type.kt` 的 `UfiTextStyles` |
| 圆角、组件尺寸、间距 | `Spacing.kt` |
| 形状语义（`xxxShape`）、阴影层级 | `UfiCardDefaults.kt` |
| 动画时长 / 缓动 / 按压缩放 / spring | `MotionTokens.kt` |
| 设置页中用户可调节的滑块 | `ThemeManager.kt`（**不是**令牌，见 §3） |

本包文件清单：

```
theme/
  Color.kt            域色 + 图表色 + 语义色便捷入口
  ColorContrast.kt    WCAG 对比度阈值 + contrastRatio / onColorFor / ufiShade（混色唯一来源）
  CustomPalette.kt    自定义皮肤：一个种子色推出全部色槽
  DynamicPalette.kt   Android 12+ 动态取色（Monet）接入
  MotionTokens.kt     ★ 动效令牌（由两个包合并而来）
  Spacing.kt          圆角 + 组件尺寸 + 间距
  Theme.kt            UFIAXISTheme 根组合 + palette → M3 ColorScheme 桥
  ThemeLocals.kt      CompositionLocal（palette / UI 缩放补偿）
  ThemeManager.kt     ☆ 运行时用户设置的持久化（不是设计令牌）
  ThemePalette.kt     色槽定义 + 派生色算法
  ThemePresets.kt     ★ 预设皮肤：中性灰阶「默认」1 套 + 彩色 6 套（rose/amber/lime/emerald/blue/violet）

  Type.kt             字体族 + 字阶 + 字重 + 语义文字样式
  UfiBannerDefaults.kt 横幅（错误 / 离线 / 实时状态）的配色与形状默认值
  UfiCardDefaults.kt  形状语义 + elevation + 卡片 Modifier
  README.md           本文件
```

---

## 1. 索引表：改什么 → 去哪里

### 1.1 配色（跟随皮肤）

| 修改目标 | 文件与行 | 符号 | 影响面 |
|---|---|---|---|
| 某套皮肤的具体色值 | `ThemePresets.kt` 各预设定义处（`Default` / `Rose:143` / `Amber:165` / `Lime:193` / `Emerald:221` / `Blue:249` / `Violet:271`） | 7 套预设 | 只影响该皮肤。改 `Default` 影响未选过皮肤的用户（默认 id = `ThemePresets.DEFAULT_ID` = `"default"`，`ThemePresets.kt:81`） |
| 新增一套皮肤 | `ThemePresets.kt` 追加定义 + `:304` 加进 `allPresets` | `allPresets` / `ALLOWED_IDS:321` / `SELECTABLE_IDS:336` | 设置页自动遍历，**无需改任何 UI 代码**；`ColorTest.kt:241` 钉着 id 列表，加皮肤要同步那一行 |
| 皮肤下拉里的顺序 | `ThemePresets.kt:304` | `allPresets` 的 `listOf` 顺序 | 只影响设置页排列 |
| 默认皮肤 | `ThemeManager.kt:271`（**只有这一处**） | `prefs.getString(KEY_THEME_ID, ThemePresets.DEFAULT_ID)` + `normalizeThemeId` | `prefListener` 刻意不含 `KEY_THEME_ID` 分支（磁盘旧值会覆盖内存新值），跨实例通过 companion 共享 flow，因此**不存在「两处必须同改」的情况** |
| 新增一个**语义色槽** | `ThemePalette.kt:11`（构造参数）+ `:97`（`resolve()`）+ `ResolvedPalette` `:126` | `ThemePalette` / `resolve` / `ResolvedPalette` | 加 `ResolvedPalette` 字段会让所有构造点编译失败（`resolve()` 与 `ColorTest`）——**这是刻意的编译期保护**，可阻止遗漏修改。`ThemePalette` 新参数请给默认值，否则 6 个预设全要改 |
| 按压态 / disabled 的主色深浅档 | `ThemePalette.kt:190` / `:200`；比例常量在 `:291` / `:294` | `accentStrong` / `accentMuted` / `ACCENT_STEP` / `ACCENT_MUTE_RATIO` | 全站所有按压态与禁用态主色。修改比例即全站生效，修改后需验证明暗两种模式 |
| 主色浅底（选中行底色） | `ThemePalette.kt:229` | `accentContainer` | 选中行、被强调卡片、M3 的 `primaryContainer` / `tertiaryContainer`（`Theme.kt:26`/`:52` 桥接） |
| 次级表面（卡内分区/进度槽） | `ThemePalette.kt:239` | `surfaceMuted` | 该角色在亮暗模式下取自不同 token（亮 = `pageBg`、暗 = `cardBg` 70%），修改后需同时验证两种模式 |
| 描边 / 分隔线的 alpha | `ThemePalette.kt:152` / `:156` / `:160` / `:208` / `:243` | `cardBorder` / `dialogBorder` / `toastBorder` / `inputBorder` / `chipUnselectedBorder` | 各自独立一档，分别对应卡片/弹窗/Toast/输入框/chip。**不得合并**：弹窗描边刻意比卡片更重（用于层级区分） |
| 开关配色 | `ThemePalette.kt:88~92`（关闭态槽）+ `:168` / `:172`（开启态派生） | `switchTrackOff*` / `switchThumbOff*` / `switchTrackOn` / `switchThumbOn` | 全站所有 `UfiSwitch` |
| 遮罩 | `ThemePalette.kt:68` | `scrimLight` / `scrimDark` | 所有弹窗/抽屉背后的暗化层 |
| 品牌渐变 | — | ~~`auroraGradient` / `auroraOn` / `themeGradient` / `auroraSoft`~~ | **已删除**：四者在仓库内均无调用点，且前三个中写死的品牌极光三色是该类最后一批不跟随皮肤的输出色。需要主题渐变时使用 `accent` + `accentSecondary` + `Color.ufiShade` 组合 |
| M3 组件的取色（`MaterialTheme.colorScheme`） | `Theme.kt:23` 起 | `buildColorSchemeFromPalette` | 所有裸 M3 组件。`onPrimary`/`onSecondary`/`onTertiary` 接 `onAccent`、`onError` 接 `onError`、`outline` 接 `inputBorder`，**已无写死 `Color.White`**（该文件无需 `import Color`，可作为检查信号）；映射由 `ColorTest.kt:509-538` 固定 |

### 1.2 域色（信号 · 电量 · 图表 · 流量 · 频段）

这一类**刻意不跟随皮肤**：它们编码的是物理含义（信号良好为绿、告警为红），随主题变化会丢失语义。

| 修改目标 | 文件与行 | 符号 | 影响面 |
|---|---|---|---|
| 信号强度 5 档 | `Color.kt:15~19` | `SignalExcellent` / `Good` / `Fair` / `Poor` / `Dead` | 仅由 `UfiSignalBars.kt` 内部的 `signalColor()` 使用（同组的 `networkTypeColor()` / `batteryColor()` 因无调用点已删除，其消费的制式与电量色常量暂时保留，待「域色是否随皮肤」决策），影响面小于表面范围 |
| 网络制式 4 档 | `Color.kt:22~25` | `Network5G` / `4G` / `3G` / `2G` | 同上 |
| 电量 3 档 | `Color.kt:28~30` | `BatteryHigh` / `Medium` / `Low` | 同上 |
| 上/下行流量色 | `Color.kt:33~34` | `TrafficDown` / `TrafficUp` | 流量卡片、图表图例 |
| 频段锁定绿/紫 | `Color.kt:38` / `:40` | `BandLte` / `BandNr` | 频段选择器。刻意与 `SignalGood` / `Network5G` 区分，不得合并 |
| 在线状态点 | `Color.kt:42` | `StatusOnline` | 首页连接卡。铺在渐变卡面上，故不跟随明暗 |
| 图表阈值线 | `Color.kt:44` / `:46` | `ChartAlert` / `ChartWarn` | 监控图表。`ChartWarn` 刻意比 `palette.warning` 更黄，避免与曲线撞色 |
| 中性描边灰 / 阴影基色 | `Color.kt:48` | `NeutralOutline` | 它是 `UfiCardDefaults.kt:241` `cardShadowColor` 的基色 → **改这一行会改全站卡片阴影颜色** |
| 图表 8 条曲线色 | `Color.kt:77`（`ChartColors(isDark)` 工厂）；数据类在 `:65` | `ChartColors` | 所有监控图表曲线。当前只随明暗、**不随皮肤**（冷调皮肤下暖色曲线会冲突，待定） |
| 成功/警告/失败的统一取法 | `Color.kt:101` | `StatusRamp.good/warn/bad` | 它只是把 `palette.success/warning/error` 包一层，**真正的值在 `ThemePalette.kt:54~59`** |

### 1.3 字体族

| 修改目标 | 文件与行 | 符号 | 影响面 |
|---|---|---|---|
| 换整套字体 | `Type.kt:25`（接口）+ `:47`（注入） | `FontSet` / `setFontSet` | 全站。**只在首屏 `UFIAXISTheme` 组合之前调用有效**（`Typography` 是顶层 `val`，首次触达即固化）——写在 `Application.onCreate` |
| 当前默认字体 | `Type.kt:31` | `SystemFontSet` | 三族（display/body/mono）当前都指向系统字体 |
| 等宽字体 | `Type.kt:55` | `MonoFont` | 所有 `mono*` 文字样式（`Type.kt:285~299`）：dBm / IP / 速率 / 时间戳 / 日志 / 代码 |
| 让"标题族"与"正文族"真的不同 | `Type.kt` 的 `SystemFontSet` 里让 `display` 与 `body` 返回不同 `FontFamily` | `SystemFontSet` | ⚠ 两者当前同为 `FontFamily.Default`（仅 `mono` 为 `FontFamily.Monospace`），因此「通过字体族区分层级」**当前不生效**，层级仅由字号与字重承担。`listItemTitle` 与顶栏两处 KDoc 已注明原因与恢复条件，**代码注释无需再调整**；是否引入第二个字体族为待定项 |

### 1.4 字阶（字号 / 行高 / 字距）

| 修改目标 | 文件与行 | 符号 | 影响面 |
|---|---|---|---|
| 任何一档的字号/行高/字距 | `Type.kt:57`（`Typography` 的 12 个槽，`:58~138`） | `Typography.headlineLarge … labelSmall` | **全站跟随**：`UfiTextStyles` 全部是 `get()` 派生而非缓存 `val`，所以改 `Typography` 一处，所有语义样式自动跟着变 |
| 某个"用在哪"的样式 | `Type.kt:175`（`UfiTextStyles`，`:179~311`） | 例：`caption:179` / `body:201` / `cardTitle:225` / `screenTitle:233` / `headerTitle:243` / `metricValue:256` | 只影响使用该语义名的调用点。**新增长尾样式无需命名**，使用 `UfiTextStyles.note.copy(...)` |
| 顶栏标题字号 | `Type.kt:243` / `:250` | `headerTitle`（22sp Bold 字距 0.56）/ `headerSubtitle`（14sp） | 所有页面顶栏。刻意从 `bodyLarge` 派生，以保持与既有渲染结果逐像素一致 |
| 胶囊导航标签 | `Type.kt:311` | `capsuleLabel` | ⚠ **不含字号**：字号由用户设置 `ThemeManager.capsuleLabelTextSp` 决定（见 §3）。刻意不复用 `caption`：胶囊栏图标偏移按实测文字高度计算，行高变化会导致几何随之变化 |

### 1.5 字重

| 修改目标 | 文件与行 | 符号 | 影响面 |
|---|---|---|---|
| 语义字重的实际取值 | `Type.kt:147`（`UfiWeight`，`:149~159`） | `Regular` / `Medium` / `Emphasis` / `Strong` / `Hero` / `Max` | **单点修改全站生效**。例如将全站强调文字由 Bold 改为 SemiBold，只需修改 `Strong`（`:155`）。业务代码禁止写 `FontWeight.Bold` 字面量 |

### 1.6 圆角

| 修改目标 | 文件与行 | 符号 | 影响面 |
|---|---|---|---|
| **全站基准圆角** | `Spacing.kt:12` | `CornerBase = 12.dp` | 卡片 / 按钮 / 输入框 / chip / toast / 小组件全部跟随（`UfiCardDefaults` 中 10 个 `*CornerRadius` 均指向它）。**这是全站圆角统一调整的唯一入口** |
| 弹窗圆角 | `Spacing.kt:20` | `CornerDialog = 12.dp` | 只影响弹窗。当前与 `CornerBase` 同值；保留独立常量是为了使「弹窗单独变圆」只需修改这一行 |
| 徽章/进度轨道 | `Spacing.kt:22` | `CornerMicro = 6.dp` | `microShape` |
| 圆角梯度其余档 | `Spacing.kt` 的 `Corner*` 段 | `CornerTrack 4` / `CornerTag 5` / `CornerSmall 8` / `CornerMedium 14` / `CornerLarge 16` / `CornerCapsule 24` / `CornerBubble 25` / `CornerChatBubble 12` | 各自一个语义。**不得跨语义借用**：`chatBubbleShape` 曾借用 `CornerDialog`，导致修改弹窗圆角同时改变短信气泡，因此拆分出 `CornerChatBubble` |
| 聊天输入框圆角 | `Spacing.kt` 的 `CornerChatInput = 20.dp` | `UfiCardDefaults.chatInputCornerRadius` 派生自它 | 该值不得直接定义在 `UfiCardDefaults`：全部圆角必须取自 `Spacing` |
| 细线轨道圆角 | `Spacing.kt` 的 `CornerHairline = 1.dp` | `UfiCardDefaults.hairlineCornerRadius` 派生自它 | 同上，全部圆角取自 `Spacing` |
| 气泡尖角 | `Spacing.kt` 的 `CornerBubbleTip = 4.dp` | `consoleBubbleShape` / `chatBubbleShape` 内的尖角 | 原为两个函数内 4 处就地 `4.dp`。该值与 `CornerTrack` 相同但语义不同（轨道与气泡指向），刻意分开 |

### 1.7 形状语义（业务代码应使用的类型）

业务代码**不得写 `RoundedCornerShape(N.dp)`**，应使用此处的语义 shape。

| 修改目标 | 文件与行 | 符号 | 影响面 |
|---|---|---|---|
| 语义 shape 总表 | `UfiCardDefaults.kt:38`（对象起始） | `shape:47` / `largeShape:42` / `smallShape:52` / `widgetShape:67` / `dialogShape:77` / `toastShape:82` / `inputShape:87` / `microShape:92` / `buttonShape:97` / `chipShape:102` / `bottomSheetTopShape:72` / `smsSheetTopShape:183` | 每个 `*Shape` 都从 `Spacing.Corner*` 派生 → **想调圆角改 `Spacing.kt`，想调"哪个组件用哪档"改这里** |
| 全圆 / 药丸 | `UfiCardDefaults.kt:112` | `pillShape`（`percent = 50`） | 进度条、圆点、药丸按钮。与 dp 无关，半径永远等于高度一半 |
| 梯度 shape | `UfiCardDefaults.kt:117` / `:122` / `:127` / `:132` / `:137` / `:142` | `trackShape` / `tagShape` / `subtleShape` / `mediumShape` / `iconTileShape` / `capsuleShape` | 分别对应 §1.6 的 `CornerTrack/Tag/Small/Medium/Large/Capsule` |
| 不对称气泡 | `UfiCardDefaults.kt` 的 `consoleBubbleShape` / `chatBubbleShape` | `consoleBubbleShape(isUser)` / `chatBubbleShape(isReceived)` | 主圆角取 `Spacing.CornerBubble`(25) / `CornerChatBubble`(12)，尖角取 `Spacing.CornerBubbleTip`(4)。⚠ 两种气泡主圆角差一倍，属待收敛项 |

### 1.8 组件尺寸

| 修改目标 | 文件与行 | 符号 | 影响面 |
|---|---|---|---|
| 卡片内边距/外边距 | `Spacing.kt:65~67` | `CardPadding 20` / `CardHorizontalMargin 16` / `CardBottomMargin 16` | 走 `UfiCardDefaults.padding:63` / `horizontalMargin:62` 的所有卡片 |
| 弹窗内边距/按钮高/底部留白 | `Spacing.kt:70~75` | `DialogPaddingH/V` / `DialogButtonHeight` / `DialogActionsBottom` / `DialogActionsWarningGap` | 所有 `Ufi*Dialog` |
| Toast 内边距 | `Spacing.kt:78~79` | `ToastPaddingH/V` | `UfiToastHost` |
| 开关几何 | `Spacing.kt:82~85` | `SwitchTrackWidth/Height` / `SwitchThumbSize/Margin` | 全站 `UfiSwitch`。四个值互相约束（thumb + 2×margin 应等于 track 高） |
| 顶栏高度 | `Spacing.kt:88~89` | `HeaderHeight 44` / `HeaderPaddingH 16` | 所有页面顶栏 |
| 按钮高度 | `Spacing.kt:92~93` | `ButtonHeight 48` / `SmallButtonHeight 36` | ⚠ 48dp 是 Android 触摸目标下限；该值还会被 UI 缩放系数相乘（见 §3，90% 档实际约 38.9dp，已达下限） |
| 按钮内距/描边/loading | `Spacing.kt:97~104` | `SmallButtonPaddingH 16` / `SmallButtonPaddingV 0` / `ButtonBorderWidth 1` / `ButtonLoadingIndicatorSize 18` | 由 `UfiButton.kt` 提取而来：5 个旧按钮合并为 `UfiButton(variant, size)` 后，这些是全 variant 共用档位。Standard 档内距使用 M3 `ButtonDefaults.ContentPadding`（h24/v8），不在此表 |
| 输入框高度 | `Spacing.kt:96` | `InputHeight 50` | 所有 `UfiTextField` 族 |
| chip 高度/内边距 | `Spacing.kt:99~100` | `ChipHeight 36` / `ChipPaddingH 16` | 所有 chip 族 |
| 图标尺寸 4 档 | `Spacing.kt:106~109` | `IconSizeSmall 18` / `Medium 20` / `Large 22` / `Dialog 22` | 走这些常量的图标（页面里硬编码的 `.dp` 不受影响，见下方⚠） |

> ⚠ **影响面说明**：仓库内 theme 目录外仍有约 1314 处硬编码 `dp`，它们**不受 `Spacing` 影响**。
> 因此修改 `Spacing` 只影响真正引用它的调用点；历史代码回填不在计划内，CI 仅约束增量。
> 需要整页等比缩放时，使用 §3 的 UI 缩放（覆盖 `LocalDensity`，硬编码 dp 也会随之缩放）。

### 1.9 elevation / 阴影

| 修改目标 | 文件与行 | 符号 | 影响面 |
|---|---|---|---|
| 三档阴影高度 | `UfiCardDefaults.kt:58~60` | `elevationDp 4` / `elevationLevel2Dp 6` / `elevationLevel3Dp 10` | Level1 普通卡片 / Level2 重要卡片 / Level3 弹窗。经 `cardElevation():190`、`cardElevationLevel2():196`、`cardElevationLevel3():202` 提供给 M3 `Card`。⚠ **二级页转场不使用阴影**（整屏 `shadowElevation` 是卡顿主因，见 §1.14） |
| 阴影**颜色** | `UfiCardDefaults.kt:241` | `cardShadowColor`（= `NeutralOutline` @30%） | 所有走 `ufiCardShadow` / `ufiStandardCard` 的 Box 类卡片。**基色在 `Color.kt:48`** |
| 阴影的画法 | `UfiCardDefaults.kt:258` | `Modifier.ufiCardShadow` | 刻意把 `spotColor` 设为透明、只留灰色环境光 → 扁平单色阴影。M3 1.4.0 的 `ColorScheme` 没有 `shadowColor` 字段、`Surface` 硬编码黑影，所以**无法通过主题一处改全站阴影色**，只能靠这个 Modifier。⚠ 阴影高度随动画每帧变化的场景（二级页转场）不得使用该 Modifier：它需要在**组合期**取得 `Dp`，等于将插值搬回重组路径；该场景改用 `graphicsLayer` 的 `shadowElevation`，颜色仍取自本表上一行的令牌 |
| 标准卡片一站式样式 | `UfiCardDefaults.kt:289`（默认 elevation 在 `:290`） | `Modifier.ufiStandardCard` | 阴影 + 圆角裁剪 + 底色 + 1dp 描边四件事。业务用它替代手写四链调用 |

### 1.10 动效时长

| 修改目标 | 文件与行 | 符号 | 影响面 |
|---|---|---|---|
| 时长梯度（17 档） | `MotionTokens.kt:188`（`Duration`） | `Flick 90:195` / `Micro 120:197` / `Swift 160:199` / `Quick 180:201` / `Base 200:203` / `Standard 220:205` / `Fluid 250:212` / `Gentle 280:214` / `Smooth 300:216` / `Sweeping 320:225` / `Deliberate 400:227` / `Emphatic 420:233` / `Reveal 600:235` / `Languid 800:237` / `Orbit 1000:239` / `Ambient 1200:241` / `Pulse 1500:243` | 改一档 = 改所有引用该档的调用点。高危档：`Base`（全站最常用）、`Sweeping`（页面转场锚点，7 个调用点） |
| **页面转场默认时长** | `MotionTokens.kt:225` → 实际拼装在 `animation/page/UfiPageTransition.kt:33` | `Duration.Sweeping` → `UfiPageTransitionDefaultSpec` | 接口默认 spec（`UfiPageTransition.kt:85`）与宿主 fallback（`UfiPageSwitcherHost.kt:1421`）共用该值。**两处必须共用同一来源**：若分别写入 320，修改一处会导致另一处静默不同步 |
| 标题模糊时长 | `MotionTokens.kt:367` | `HEADER_TITLE_BLUR_MS = 350` | 只影响 `UfiHeader` 的标题模糊。**有意例外**（不在梯度上），原名 `PAGE_TRANSITION_MS` 与语义不符，已重命名 |
| 列表交错延迟 | `MotionTokens.kt` 的 `STAGGER_DELAY_MS = 35L` | `STAGGER_DELAY_MS` | 第 N 项延迟 = N × 本值。⚠ 唯一消费方 `Modifier.staggeredEntrance` 因无调用点已删除，本常量**暂时保留**作为交错入场的时间口径；重新实现时应引用该常量，不得写入裸值 35 |
| 数据刷新淡入 | `MotionTokens.kt:381` | `DATA_FADE_MS`（= `Duration.Smooth`） | 不得在此修改数值：它只是 `Smooth` 的别名 |
| 胶囊收起时长 | `ui/navigation/MainNavGraph.kt:109` | `CAPSULE_HIDE_MS = 360` | ⚠ **唯一位于 `ui.theme` 之外的时长**，`private const val`、单一调用点（`:448`）、有意例外。若出现第二个使用点，应并入 `Duration` |

### 1.11 动效缓动

| 修改目标 | 文件与行 | 符号 | 影响面 |
|---|---|---|---|
| 语义缓动集合 | `MotionTokens.kt:278`（`Easing`） | `Standard:280` / `Linear:282` / `Accelerate:284` / `EmphasizedIn:286` / `EmphasizedOut:288` / `Overshoot:290` | 业务禁止直接 import Compose 的 easing 常量，一律走这里 |
| 贝塞尔控制点本身 | `MotionTokens.kt:150` / `:154` / `:158` | `UfiAnimSpecs.toastDropEasing` / `emphasizedInEasing` / `emphasizedOutEasing` | 这三条曲线的**数值唯一来源**。`UfiMotion.Easing` 的 `Overshoot` / `EmphasizedIn` / `EmphasizedOut` 只是转发 |

### 1.12 spring 参数

| 修改目标 | 文件与行 | 符号 | 影响面 |
|---|---|---|---|
| 17 个 spring token | `MotionTokens.kt:61`（`UfiAnimSpecs`） | 按压类 `clickScale:66` / `buttonPress:69` / `controlPop:72` / `sendPop:75`；入场类 `staggerEnter:80` / `dialogEnter:83` / `toastDrop:86` / `panelEnter:89`；选中类 `tagPop:94` / `tooltipPop:97` / `tabSlider:100` / `pagePulse:103`；连续量 `switchThumb:108` / `colorSettle:111` / `colorSwap:114` / `sliderTrack:117` | 修改 `(dampingRatio, stiffness)` 即改变该类交互的手感。业务应经 `UfiMotion` 的同名语义入口（`:302~357`）调用，不得直接 import 本层。零调用的 `navPress` 已删除（唯一使用点 `PaginationNavButton` 已按分层规则改用 `buttonPress`） |
| 胶囊展开刚度 | `MotionTokens.kt:132` | `CapsuleExpandStiffness = 3000f` | 唯一以裸常量暴露的 spring 参数。原因：`CapsuleRegressionGuardTest` 用正则要求 `UfiCapsuleTabBar.kt:117~118` 的 `EXPAND_SPRING` 赋值处 40 字符内出现字面 `spring(`，**所以那个调用点必须保留 `spring(` 文本，不能改写成 spec 函数** |
| 为什么是 `fun <T>` 而不是 `val` | `MotionTokens.kt:50~53`（KDoc） | — | 调用点既有 `animateFloatAsState` 也有 `animateColorAsState` / `animateDpAsState`，`SpringSpec<T>` 的 `T` 必须由调用处推断 |

### 1.13 按压缩放档位

| 修改目标 | 文件与行 | 符号 | 影响面 |
|---|---|---|---|
| 4 档缩放值 | `MotionTokens.kt:272`（`PressScale`） | `Fab 0.92:274` / `Cell 0.94:276` / `Button 0.96:278` / `Chip 0.97:286` | 全站 13 处按压反馈。**唯一规则：元素面积越小，缩得越多**；同一面积量级必须同档（详见 §5 的分层规则） |
| 配套动画 spec | `MotionTokens.kt:255~260`（KDoc 规定的配对） | `Fab→controlPop()` / `Button→buttonPress()` / `Cell·Chip→tween(Duration.Micro)` | 不得自由组合：密排元素使用回弹动画会相互干扰 |
| 按压**播放方式** | `animation/PressFeedback.kt` | `rememberUfiPressScale` / `Modifier.ufiPressScale` / `rememberUfiPressed` | 全站唯一实现。缩放走前两个（事件驱动 + 最短保持 120ms，短按也有可见幅度）；**按压态配色**走 `rememberUfiPressed`（把按下态保底 120ms 再喂给 `animateColorAsState`）。⚠ 不得退回 `collectIsPressedAsState` + `animateFloatAsState/animateColorAsState` 的组合，短按将无可见反馈 |

### 1.14 二级页转场的深度层次（「离场页后退」模型）

> 二级页（宿主 ↔ detail、detail ↔ detail）转场采用「离场页后退」模型：
> 若仅做水平平移，则只有单一图层移动，缺少层次。
>
> 「进场页当卡片浮起」（缩放 + 圆角 + 中段阴影）方案在真机上存在两个问题，未采用：
> 1. **卡顿** —— 整屏 `shadowElevation`（大面积 RenderNode 投影每帧重算）+ `clip = true`
>    （离屏裁剪）+ `scale`，在中低端设备上开销高；
> 2. **顶部与底部存在静止区域，割裂感明显** —— 原因是**被转场动画包裹的只有页面内容**：
>    `MainNavGraph` 将 `Scaffold` 的 `innerPadding`（默认 `safeDrawing`，含状态栏 / 导航栏）
>    加在**包着 NavHost 的 `Box`** 上，因此状态栏色带与底部导航栏色带由 `Scaffold`
>    的 `containerColor` **静态**绘制；底部胶囊 `UfiCapsuleTabBar` 位于独立的 `Dialog`
>    窗口中，不在这棵 Compose 树上。进场页一旦 `scale = 0.94`，四周立即露出这些静止
>    的 chrome，圆角会进一步突出该缝隙。
>
> **现行模型（Material 共享轴 + 深度 / Android 预测性返回的标准做法）**：
> - **进场页**：只平移，**不缩放、不圆角、不投影**，始终满屏铺满，任何时刻都覆盖下层，
>   缝隙不会出现，同时省去开销最高的两项；
> - **离场页**（位于下层、被进场页覆盖）：只叠加 `scrim` 淡入至 30%，深度感由下层后退变暗提供；
>   不缩放（`scale` 是预测性返回掉帧与首帧异常的根因，已移除）；
> - pop 方向整套反向：返回的上级页 scrim 淡出，被关闭页只平移出屏。

| 修改目标 | 文件与行 | 符号 | 影响面 |
|---|---|---|---|
| **时长 / 总开关** | `navigation/Navigation.kt` | `ufiNavTransitionDurationMs(rawMs, systemReduceMotion)` | **唯一来源**。把 `ThemeManager.transitionDurationMs`（150~600，`0`=关闭）与系统降低动效合并成一个数：`>0` 是 tween 时长，`0` ⇒ 平移/scrim **一并**不生效。`-1`（未播种哨兵值）回落默认档，而非「关闭」 |
| 曲线 | 同上 | `sharedAxisSpec`（内部 `tween(Easing.Standard)`） | 平移 / 后退进度共用该曲线，不会出现位移结束而 scrim 仍在淡出的情况。曲线为 `Easing.Standard` |
| 进度 | 同上 | `ufiSharedAxisLayer` 里的 `transition.animateFloat` | 挂在 NavHost 的 `Transition<EnterExitState>` 上，**可被可预测性手势返回逐帧 seek**。⚠ 不得改为 `animateXxxAsState`：那会引入第二个时长来源 |
| 幅度映射 | 同上 | `sharedAxisScrimAlpha()`（取 `MotionTokens.NavRecede.ScrimAlpha`） | scrim 幅度内联进 `ufiSharedAxisLayer`；时长见 `UfiNavTransitionDurationTest` |
| 角色判定（谁是**下层**） | `navigation/Navigation.kt` | `UfiNavRecedeRole.recedingEntryId`，由 `detailSharedAxisExit`（push ⇒ `initialState.id`）/ `detailSharedAxisPopEnter`（pop ⇒ `targetState.id`）写入；图层侧 `ufiSharedAxisLayer(isReceding = …)` 比对 id | ⚠ **不得改回 `navController.visibleEntries` 栈顶**。`populateVisibleEntries()` 先收集 `transitionsInProgress` 中 `maxLifecycle < STARTED` 的 entry（含已出栈、正在执行退场动画的页面），再收集 `backQueue`，因此 `.last()` 的语义是「**进场页**」而非「前景页」：pop 时进场页恰为下层，导致角色判定相反，返回动画中 detail 承担缩放与 scrim、宿主无变化。也**不得**改用 `PostExit`（其语义为「离场页」，pop 方向同样错误）。「下层」必须由**方向**决定，而方向体现在「哪一组转场函数被调用」。持有者刻意使用普通 `var`（而非 `MutableState`），仅在 `graphicsLayer` / `drawWithContent` 的 lambda 中读取，不触发重组 |
| 离场页遮罩浓度 | `MotionTokens.kt`（`NavRecede.ScrimAlpha`） | `NavRecede.ScrimAlpha = 0.30f`，色取 `palette.scrim` | 峰值 0.30（0.12 在真机上几乎无法察觉压暗，且被不透明进场页进一步遮挡，因此提高到 0.30）。scrim 绘制在**同一条 modifier 链**的 `drawWithContent` 中（不另建 `Box` + `background`，以免多一个 layout 节点与一层绘制） |

### 1.15 间距


> 未引入 `Space XS/S/M/L/XL` 尺度阶梯。
> 当前采用语义命名的间距而非尺度阶梯，下表为全部入口。

| 修改目标 | 文件与行 | 符号 | 影响面 |
|---|---|---|---|
| 页面边距 | `Spacing.kt:52` | `PagePadding 12` | 走它的页面容器 |
| 通用间距 4 档 | `Spacing.kt:56~59` | `Small 4` / `Medium 8` / `Large 12` / `XLarge 16` | 最接近尺度阶梯的一组，但缺少 24/32/48 档 |
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

**这些箭头均为单向，不得反向引用。** 典型例子：`UfiAnimSpecs.fadeEnter`（`MotionTokens.kt:146`）
中的 `250` 为字面量，未引用 `UfiMotion.Duration.Fluid` —— 明细层不得依赖语义层，
否则「数值唯一来源」不成立。两层合并在同一文件后该约束依然有效：
**同文件不等于可以互相引用**。修改 `Fluid` 时需同步 `fadeEnter`（其 KDoc 已注明）。

---

## 3. 「设计令牌」与「用户可调设置」的区分

两者都位于 `ui.theme` 包内，但性质不同：

| | 设计令牌 | 用户可调设置 |
|---|---|---|
| 位置 | `Color.kt` / `Spacing.kt` / `Type.kt` / `UfiCardDefaults.kt` / `MotionTokens.kt` / `ThemePalette.kt` / `ThemePresets.kt` | **只在 `ThemeManager.kt`** |
| 生效时机 | 编译期常量，改了要重新编译 | 运行时，用户在「设置 → 外观」拖滑块即刻生效 |
| 谁决定 | 开发者 | 每个用户各自决定（存 `SharedPreferences`，文件 `ufi_axis_prefs`） |
| 改动影响 | 所有用户、所有设备 | 只影响未自行调整的用户（修改 `DEFAULT_*`）或取值区间（修改 `MIN/MAX`） |

**`ThemeManager.kt` 里的运行时设置清单**（这些**不是**令牌）：

| 设置项 | 读取出口 | 默认 / 区间 | 注意 |
|---|---|---|---|
| 转场时长 | `transitionDurationMs:68`，setter `:221` | 默认 `TRANSITION_DURATION_DEFAULT_MS = 380`（`:393`）；区间 `MIN 150`（`:384`）~ `MAX 600`（`:390`）；`OFF = 0`（`:378`） | `0` 不表示「时长 0 的转场」，而是切换到降低动效通道（接 `LocalUfiReduceMotion`）。0 与 150 之间**刻意留空**（150ms 以下的整屏转场会被感知为闪帧）。上限**不得放宽**（>600ms 会被感知为卡顿）。越界值由 `normalizeTransitionDuration:402` 处理。**本设置同时控制 Tab 切页与二级页转场**（二级页链路的归一化入口为 `navigation/Navigation.kt` 的 `ufiNavTransitionDurationMs`） |
| 转场动画类型 | `pageTransition:61`，setter `:201` | 默认 `"fade"` | 策略 id 由 `UfiPageTransitions` 注册表解析 |
| 过渡模糊开关 | `blurEnabled:74`，setter `:230` | 默认 `true` | 只作用于 **Tab 切页**（`UfiPageSwitcher` 宿主的 `applyTransitionBlur`），经 `LocalUfiBlurEnabled` 下传；弱机由 `UfiPageSwitcherDefaults.isBlurSupported` 强制降级。⚠ 该开关曾因被误判为切页卡顿的根因而整体删除，现已完整还原；实际根因为胶囊索引双数据源、`UfiScreenScaffold` 缺少 `background()`、胶囊窗口 relayout 重放三处。正向约束见 `NavInsetHandoffGuardTest.pageSwitcher_mustBlurDuringTransition` |
| 全局 UI 缩放 | `uiScalePercent:84`，setter `:239` | `UI_SCALE_BASE = 0.9f`（`:421`）× `percent/100`；默认 100（`:422`）；区间 90~120（`:423~424`） | 落地方式是在 `Theme.kt:117~125` 覆盖 `LocalDensity`，**不是**修改 `Spacing`/`Type` 常量 —— 仓库内约 1500 处硬编码 dp 不经过 `Spacing`，修改常量只会导致卡片缩小而内部间距不变。下限 90% 已使 `ButtonHeight 48dp` 降至约 38.9dp（接近触摸目标下限），不得继续下调 |
| 胶囊抬高 | `capsuleLiftDp:98`，setter `:257` | 默认 30（`:445`），区间 0~40（`:446~447`） | 位置参数 |
| 胶囊图标尺寸 | `capsuleIconSizeDp`，setter 同区块 | 默认 **26**（`ThemeManager.kt:755`），区间 18~40（`:756~757`） | 默认值由 38 调整为 26：38dp 会使 Tab 单元格接近正方形，选中滑块圆角取自身半高，必然渲染为圆点。上限必须 ≥ 默认值，否则 `coerceIn` 会截断默认值 |
| 胶囊标签字号 | `capsuleLabelTextSp:110`，setter `:277` | 默认 10（`:460`），区间 8~14（`:461~462`） | 与令牌 `UfiTextStyles.capsuleLabel`（`Type.kt:311`）**配合**：样式来自令牌、字号来自本设置 |
| 胶囊圆角 | `capsuleCornerDp:116`，setter `:288` | 默认 30（`:465`），区间 10~30（`:466~467`） | 与 `Spacing.CornerCapsule`（24dp）**无关**，注意区分 |
| 胶囊图标文字间隔 | `capsuleIconTextSpacingDp:127`，setter `:299` | 默认 0（`:477`），区间 0~12（`:478~479`） | 0 是**合法**下限，下游判据用 `>= 0` 而不是 `> 0` |
| 一键恢复胶囊默认 | `resetCapsuleMetrics:312` | — | 复用五个 setter，确保重置路径与拖动路径行为一致 |
| 皮肤 id | `selectedThemeId` | 默认 `ThemePresets.DEFAULT_ID`（`"default"`） | 皮肤**内容**是令牌（`ThemePresets.kt`），**选了哪个**是用户设置。合法 id = 7 套预设 + `"custom"`（见 `ThemePresets.SELECTABLE_IDS`） |
| 明暗模式 | `themeMode` | `AUTO` | companion 共享 flow + `themeModeSeeded` 标记位 |
| 动态取色开关 | `dynamicEnabled` / `setDynamicEnabled` | 关 | 与皮肤 id **正交**的独立开关，开启时覆盖皮肤（`buildDynamicPalette`）；播种与 setter 都过能力闸门 |
| 自定义皮肤的种子色 | `customSeedColor` / `setCustomSeedColor` | `CUSTOM_SEED_UNSET`（0 = 从未选过） | 一个 ARGB Int 推出全部 17 槽（`CustomPalette.kt` 的 `buildCustomPalette`）。**不是**仅覆盖 accent 的旧字段 `customAccentColor`（死代码，已删除），配置键已改为 `ufi_custom_seed_color` |

> 上述四项**全部**采用 companion 共享 flow 加独立播种标记位，`prefListener` 中**不含**这些分支 —— 详见 `ThemeManager` 中的相关注释（「修改后需杀进程重启才生效」这一缺陷曾多次修复）。
> `getCurrentPalette()` 是非 Composable 的 `.value` 直读，宿主侧的 `remember` key 必须覆盖 `selectedThemeId` / `dynamicEnabled` / `customSeedColor` 三项（由 `NavInsetHandoffGuardTest` 固定）。

设置页 UI 在 `app/feature-settings/.../AppearanceSettingsScreen.kt`（转场时长写入 `:324`、UI 缩放写入 `:413`、皮肤列表遍历 `:443`）。
`MIN/MAX` 声明为 `public` 的原因：滑块 `valueRange` 与 setter 的 `coerceIn` 必须使用同一份区间定义，否则两处会不一致。

另有三个组件内**回退常量**刻意不与用户设置对齐，不应「修正」：
`UfiCapsuleBlurHost.CAPSULE_WINDOW_CORNER_RADIUS = 18.dp`（`:163`）、
`UfiCapsuleTabBar.CAPSULE_CORNER = 18`（`:88`）、`UfiCapsuleTabBar.ICON_SIZE = 24.dp`（`:93`）——
它们只在读到非法值时兜底，同时是回归护栏的比对锚点。

---

## 4. 规范名 ↔ 现名映射表

Material 3 设计规范用 M3 语义命名，本项目用自研命名。
**项目有意不改名**：改名需重写 405 处引用，属于纯风格变更，且不减少任何入口。
因此提供本映射表：以规范作为色值或字阶取值参考时，按此表对照。

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
| `outline` | `inputBorder`（M3 桥接）；卡片描边语义仍为 `cardBorder` 一族 | `Theme.kt:89` / `:116`；`ThemePalette.kt:240` |
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

### 4.5 有意例外清单（**不应统一**）

| 项 | 现值 | 规范 | 保留原因 |
|---|---|---|---|
| `Spacing.CornerTag`（`:29`） | 5dp | 4 基栅格里没有 5 | 从 94 处字面量收敛时**刻意保留原有观感**；取整为 4 会改变一批行内小元素 |
| `Spacing.CornerBubble`（`:39`） | 25dp | 最近的是 `cornerXL` 24 | 控制台会话气泡原值。⚠ 与短信气泡 `CornerChatBubble` 12dp 相差一倍，两者同为聊天气泡但观感不同，属于待收敛项；该问题属于**组件一致性**，而非规范对齐 |
| `UfiTextStyles.caption`（`Type.kt:179` → `labelSmall` 11sp，`:135`） | 11sp | Caption 12sp | 全站最高频样式（约 123 处）。提升至 12sp 会使密集列表整体增高，属大范围观感变更，需单独评审 |
| `UfiMotion.HEADER_TITLE_BLUR_MS`（`MotionTokens.kt:367`） | 350ms | 不在任何梯度 | 刻意比页面转场的 320 略长，使标题最后变清晰。取 320 相差 30ms，超出 20ms 容差；取 400 明显拖沓 |
| `MainNavGraph.CAPSULE_HIDE_MS`（`:109`） | 360ms | 不在任何梯度 | 胶囊收起的慢-快-慢曲线专用。单一调用点、`private`，收进梯度会为一个用途多开一档 |

---

## 5. 新增组件的令牌选择流程

**按顺序执行**，不得跳过步骤：

1. **先复用**。用现有语义令牌 + `Spacing` 尺度表达；内边距与触控尺寸对齐最近的组件族
   （按钮族看 `Spacing.kt:92`，列表行看 `UfiTextStyles.listItemTitle`，卡片看 `UfiCardDefaults.shape`）。
   最小触控 48×48dp，注意 UI 缩放 90% 档会把 48dp 压到 ≈38.9dp。
2. **派生优先**。缺色时先从强调色 5 档梯度派生 —— `accent`（`ThemePalette.kt:98`）/
   `accentStrong`（`:190`）/ `accentContainer`（`:229`）/ `onAccent`（`:107`）/ `accentMuted`（`:200`）。
   **不得新增色相**：色相增多后，切换皮肤时会出现无法同步的元素。
3. **确需新增才新增**，命名 `<category><Role><Variant>`（例 `statusWarningBg`），
   加进 `ThemePalette` 成对色槽（light/dark 均需提供），**并在本文件 §1 对应小节登记一行**。
   未登记的令牌等同于不存在，后续维护者会重新创建同名令牌。
4. **过对比度闸门**。正文 ≥4.5:1、大文本（≥18sp 或 ≥14sp+700）≥3:1、
   **按钮/图标等 UI 组件 ≥3:1**（WCAG 1.4.11，不是 1.4.3）。不通过则回退到相邻梯度。
   采用分档而非统一阈值的原因：高明度橙色一类 accent 配白字在物理上无法达到 4.5:1，统一阈值最终必然导致事后人为放宽。
5. **禁止硬编码**。组件中不得出现裸色值、裸字号、裸 `tween(数字)`、裸 `RoundedCornerShape(N.dp)`。
   代码审查的硬性要求为「无字面量」。

### 5.1 按压反馈的分层规则

**唯一规则：元素面积越小，缩得越多。** 同一面积量级的元素**必须**落在同一档，
跨档只允许因面积量级不同，不允许基于「该页面需要更明显」等主观原因。

| 档 | 值 | 用于 | 配套 spec |
|---|---|---|---|
| `PressScale.Fab`（`MotionTokens.kt:269`） | 0.92 | FAB（全站唯一浮起圆钮） | `controlPop()`（带回弹，适用于浮起元素的过冲） |
| `PressScale.Cell`（`:271`） | 0.94 | 密排网格小格（排程选择器 7 列日期格） | `tween(Duration.Micro)` |
| `PressScale.Button`（`:273`） | 0.96 | 按钮族（`UfiButton` + 页面内 OutlinedButton 图标按钮 + 分页导航 pill 按钮） | `buttonPress()`（无回弹、紧实） |
| `PressScale.Chip`（`:281`） | 0.97 | chip / 选项格 / 可点列表行（面积最大的一类可点区） | `tween(Duration.Micro)` |

反例：监控页三个筛选 chip 各自写入 `0.94`，而公共 `CategoryChip` 与弹窗选项格写入 `0.97`，
导致同一种胶囊筛选器在不同页面的缩放不一致。因此**值与 spec 均按档固定，不得自由组合**。

---

## 6. 动效令牌的定义位置

**动效令牌仅有一条定义路径。**

| | 路径 | 状态 |
|---|---|---|
| 全部代码 | `com.ufi_axis.ui.theme.UfiMotion` / `UfiAnimSpecs`（`theme/MotionTokens.kt`） | 唯一定义、唯一入口 |
| 旧路径 | `components.common.UfiMotion`（`Ufi.kt`）/ `animation.UfiAnimSpecs`（`UfiAnimations.kt`） | 两个 `@Deprecated` 转发壳已删除；`UfiAnimations.kt` 整文件删除 |

同类收敛的参考数据：

- 消费方共 **32 个文件**，分三类：显式 import 旧包 8 个、`components.common` 同包隐式引用 20 个、
  通过 `import components.common.*` 星号导入解析 4 个（`SmsScreen` / `AdvancedConsoleScreen` /
  `TaskScreen` / `UpdateDialogs`，另 `SetupScreen` 同型）。第三类最易遗漏：它们既无旧 import
  可供 grep，也不在同包清单中，**仅能由编译器发现**（编译报错为
  `Unresolved reference 'UfiMotion'`）。
- 判据为「引用了 `UfiMotion.` 且文件中没有 `com.ufi_axis.ui.theme.UfiMotion` 时补一行 import」，
  可一次覆盖三类。副作用：仅在注释或 KDoc 中提及 `UfiAnimSpecs.` 的文件也会被误判，多出的 3 行 import 已删除。
- 验收标准：`./gradlew :app:assembleDebug` 通过，且日志中 **`UfiMotion is deprecated` 警告为 0**
  （其余警告均为 Compose 自身的 API 弃用）。

一条仍然适用的规则：**同时以 `*` 导入两个包会产生名称冲突**（同名 object 优先级相同，导致
`Overload resolution ambiguity`），解决方式是添加显式 import：显式导入优先于星号导入。

### 6.1 已知不一致

1. `chatInputCornerRadius` / `hairlineCornerRadius` 已改为从 `Spacing` 派生：
   `Spacing.CornerChatInput`（20dp）/ `CornerHairline`（1dp）。
2. `consoleBubbleShape` / `chatBubbleShape` 的尖角已改为派生自 `Spacing.CornerBubbleTip`（4dp）；
   `UfiCardDefaults` 中「圆角全部取自 `Spacing`」成立。
3. `Theme.kt` 的 M3 桥接不再写死 `Color.White`（见 §1.1 最后一行）。
4. `outline` 使用单一答案：M3 `outline` 接 `inputBorder`，
   `Theme.kt:46-53` 说明了「为何不接更弱的 `cardBorder`」。
5. `aurora*` / `themeGradient` 因零调用已删除（见 §1.1 品牌渐变行）。
6. 间距仍为语义命名而非尺度阶梯，缺少 24/32/48 档。
7. `MonitorOverview` 的严重度色块图标仍写死 `Color.White`（severity 实底之上的前景），
   属于「语义实底 + 白前景」这一更大议题。
