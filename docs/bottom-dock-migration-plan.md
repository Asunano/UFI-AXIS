# 悬浮胶囊 → 贴底通栏低栏 迁移计划

> 目标形态：**贴屏幕底的通栏低栏**。上方圆角 22dp、下方直角、宽度铺满；
> 没有收起态；图标 + 文字常显；选中项是实色渐变药丸。
> 背景铺满到屏幕真实底边（手势条浮在栏底色之上），内容靠 bottom padding 避开手势区。

---

## 0. 文档用法

> **现状盘点是 2026-09-23 当日的代码阅读结论**，行号是撰写时的位置，动过之后按符号名找。

- 状态标记：`[ ]` 未开始 / `[~]` 进行中 / `[x]` 完成（验收全过）/ `[!]` 受阻（补一行原因）/ `[-]` 放弃（补一行理由）
- 阶段之间是硬依赖；本次改造**中途必然有编译红的阶段**，每阶段都标了预期红灯范围
- 每完成一个阶段，在 §9 追一行

---

## 1. 已定稿的设计参数

| 项 | 值 | 备注 |
| --- | --- | --- |
| 上方圆角 | `topStart = 22.dp, topEnd = 22.dp`，下方 0 | 18 偏方、26 以上有"抽屉"感 |
| 内容区高 | 58dp | = 图标 24 + 间距 3 + 标签 10sp≈14 + 上下 padding 8.5×2 |
| 安全区 | 运行时取值，手势导航兜底 24dp / 三键约 48dp | 复用现成的 `readBottomReservedPx()` |
| 栏总高 | 58 + 安全区 | 页面 inset 要发布这个值 |
| 图标 | 24dp | 与现在一致 |
| 标签 | 10sp，Medium；选中 SemiBold | **常显**，不再有淡入淡出 |
| 格宽 | 屏宽 ÷ tabs.size（等分） | 不再随收起态插值 |
| 底色 | 浅色 `lerp(cardBg, textPrimary, 0.06f)`；深色 `lerp(cardBg, Color.Black, 0.10f)` | **两个方向必须分开写**，见 §5.16。竖向 94% → 90% |
| 上缘高光 | 6dp，浅色 `#fff@34%` / 深色 `#fff@12%` | |
| 顶边 | 1px 发丝线 `line@62%` | 贴底时用来与滚动内容分区 |
| 选中态 | **B 实色渐变药丸**：`accent → lerp(accent, #7C4DFF, 0.22f)` 160° 渐变，外发光 `accent@42%` 5dp/14dp，内顶高光 `#fff@30%` 1px；图标与标签转白 | |
| 选中药丸几何 | `inset(vertical = 4.dp, horizontal = 10.dp)`，圆角 15dp | |

参数来源：`.comate/bottom-dock-refined-preview.html`（可交互预览，规格块里的值可直接抄）。

---

## 2. 现状盘点（as-is）

### 2.1 窗口承载（`app/ui/.../common/UfiCapsuleBlurHost.kt`）

唯一写入点 `applyCapsuleWindowParams()`（`:491-540`），带逐字段全等守卫（`:522-529`，相等即 `return`，零派发）。

| 字段 | 现状 | 改后 |
| --- | --- | --- |
| `gravity` | `BOTTOM or CENTER_HORIZONTAL`（`:502`） | `BOTTOM`（通栏不需要水平居中） |
| `x` | 从不写、不在守卫比对项里，恒 0 | 不变 |
| `y` | `metrics.bottomOffsetPx`（`:537`） | **0** |
| `width` | `naturalSize.width > 0 ? it : WRAP_CONTENT`（`:497`） | **MATCH_PARENT** |
| `height` | `naturalSize.height > 0 ? it : WRAP_CONTENT`（`:499`） | 保持（栏总高由内容决定） |
| `layoutInDisplayCutoutMode` | `ALWAYS`（`:504`） | 保留 |
| `dimAmount` | 恒 0（并入同一次赋值，`:533`） | 保留 |

`bottomOffsetPx` 现在的计算式（`:1073-1075`）：

```kotlin
bottomOffsetPx = reservedPx +
    (capsuleGapFor(gestureNav) - CAPSULE_SHADOW_ROOM).roundToPx() +
    CAPSULE_LIFT.roundToPx()
```

四个来源：`reservedPx` ← `rememberBottomReservedPx()`（`:1045`，底层 `readBottomReservedPx()` `:594-610` = `max(navigationBars.bottom, systemGestures.bottom)`，手势导航兜底 `GESTURE_BOTTOM_INSET_FLOOR = 24.dp` `:77`）；`capsuleGapFor()` = 8dp（`:96`/`:105`）；**`CAPSULE_SHADOW_ROOM = 0.dp`**（`:58`，已是恒 0 的减项，只为让护栏能在表达式里找到这个 token）；`CAPSULE_LIFT` 是函数内局部 val（`:1055`），由 `ThemeManager.capsuleLiftDp` 驱动，默认 30（`ThemeManager.kt:742`）。

**flag 一个都去不掉**（`:195-207`）：`NOT_FOCUSABLE`（否则抢焦点吃返回键）、`NOT_TOUCH_MODAL`（Dialog 默认模态，靠它放行栏以上区域）、`LAYOUT_NO_LIMITS`（让 `gravity=BOTTOM` 的参考系是屏幕真实底边）、清 `DIM_BEHIND`、gate 的 `NOT_TOUCHABLE`（`:511-519` + `CapsuleTouchGate.interactive` `:395`，写入方 `MainNavGraph.kt:287`）。通栏后窗口更宽，二级页误吃触摸的面积更大，gate 这条更重要。

尺寸更新是「事件驱动 + 三次重放（立即 / `view.post` / `delay(100ms)`）+ 全等守卫」，**不是每帧**。旧的「每帧 setLayout 跟随缩放」已删（复盘在 `:1255-1261`、`:770-782`），护栏明令禁止回归。

⚠ `naturalSize` 不在任何 effect 的 key 里，「首帧 WRAP_CONTENT → 测得真值后钉死」是靠那两次重放顺带完成的。**width 改成 MATCH_PARENT 后，naturalSize 的 width 分支整条退休**。

### 2.2 手势区沉浸的基础设施：**已经齐全**

| 需要的 | 现状 | 位置 |
| --- | --- | --- |
| Activity edge-to-edge | 已有 | `MainActivity.kt:91` |
| 关三键 scrim | 已有 `isNavigationBarContrastEnforced = false` | `MainActivity.kt:95` |
| 主题透明导航栏 | 已有 | `themes.xml:76` |
| **Dialog 窗口自己**的透明导航栏 + 关 scrim | 已有，带幂等守卫 | `UfiCapsuleBlurHost.kt:875-879` |
| Dialog 不被安全区夹住 | 已有 `setDecorFitsSystemWindows(false)` + 一次性闸门 | `:888`、`:1108` |
| inset 监听（只当信号，取值回读宿主全屏 insets，绝不 CONSUMED） | 已有 | `:666-673` |
| 导航模式判别 | `tappableElement().bottom == 0 && navigationBars().bottom > 0` | `:558-564` |
| targetSdk | 36，edge-to-edge 强制 | `app/build.gradle.kts:18` |

`:787-795` 的注释已经写明原理：平台把手势条那条带子当作**本窗口**的装饰区，用**窗口自己的** `navigationBarColor` 去填 —— 所以 Dialog 必须单独设透明，Activity 那套不够。这个坑已踩过并修。

**唯一缺的**：Dialog 内部的 Compose 树**没有任何** `navigationBarsPadding()` / `windowInsetsPadding`。现在图标不压手势条靠的是窗口 y 里那个 `reservedPx`。

### 2.3 页面底部留白：统一一处（好消息）

`CapsuleInsetHolder` 单例 + `Modifier.ufiCapsuleBottomInset`，6 个调用点。改造后只需改那一个计算式（从「胶囊高 + gap + lift」变成「58 + 安全区」）。

`NavInsetHandoffGuardTest` 守着两条：`capsuleInset_mustNotPublishBeforeMeasured`、`capsuleInset_providerMustNotDereferenceAtComposition` —— 这两条改造后**仍应保留**，语义不变。

### 2.4 要拆的收起/展开清单（`UfiCapsuleTabBar.kt`）

`COLLAPSED_SCALE`、`capsuleScaleOf`、`EXPAND_SPRING`、`COLLAPSE_SPRING`（2026-09-23 刚加）、`expandProgress`、`labelReveal`、`LABEL_FADE_IN_MS` / `LABEL_FADE_IN_DELAY_MS` / `LABEL_FADE_OUT_MS`、`LABEL_RISE`、`COLLAPSED_TAB_WIDTH`、`COLLAPSED_ICON_PAD`、`COLLAPSE_DELAY_MS`、`CAPSULE_SURFACE_ALPHA_COLLAPSED`、`CapsuleCollapseProbe`、`resetToken`、`sheenSweep`（2026-09-23 加的展开扫光，没有展开态就没有触发时机）。

**跨文件依赖（决定删除顺序）**：`LocalCapsuleExpandProgress` 被 `UfiCapsuleBlurHost` 读（`:1104` 创建、`:1268` provides、`UfiCapsuleTabBar.kt:724` 写入）。必须**先**拆掉 BlurHost 侧的消费，再删 TabBar 侧的生产。

### 2.5 其它硬编码了「悬浮」几何的地方

- `MainNavGraph.kt:737-738`：进出场动画底边锚点 `safeDrawing.getBottom(density) + 38.dp` —— 那个 38 是照抄 `gap 8 + lift 30`。贴底后必须改成 0，否则栏会从屏幕外 38dp 处开始动
- `MainNavGraph.kt:156`：`Modifier.padding(CAPSULE_SHADOW_ROOM)`（= padding 0）—— 删常量时一起处理
- `MainNavGraph.kt:848`：进出场用 `TransformOrigin(0.5f, 1f)` 做底部锚点缩放。贴底通栏的进出场应该是**整条纵向滑动**而不是缩放，这一处要重做

---

## 3. 目标结构

```
Dialog 窗口：width=MATCH_PARENT, gravity=BOTTOM, y=0, LAYOUT_NO_LIMITS
└ Box(fillMaxWidth, clip(RoundedCornerShape(topStart=22, topEnd=22)))
  ├ drawBehind { 底色铺满整个 Box（含安全区）+ 上缘 6dp 高光 + 顶边 1px 发丝线 }
  ├ Row(fillMaxWidth, height=58dp)        ← 图标 + 文字，等分格宽
  │ └ 4~5 × Tab（选中项画实色渐变药丸）
  └ Spacer(height = reservedDp)            ← 安全区：只有底色，没有内容
```

**沉浸的本质就是这两行分开**：背景铺满到屏幕真实底边，内容用 `Spacer` 让位。安全区值继续用 `readBottomReservedPx()`（它刻意读宿主 Activity 的全屏 `rootWindowInsets` 而不是 Dialog 自己的，防自激振荡）——逻辑一行不改，只是消费方从「窗口 y」变成「栏内 Spacer」。

---

## 4. 护栏处置（**最关键，逐条决定**）

`CapsuleRegressionGuardTest.kt`（1319 行、26 个 `@Test`）**全部是纯源码文本指纹、零行为断言**。它 import 的唯一生产符号是 `UfiMotion`，不执行任何 Compose 代码。文件头 `:11-30` 说明了为什么（`:app:ui` 只有 junit，引入 Compose UI Test + Robolectric 不划算），并留了升级出口。

> **实施第一步必须先跑一次 `:app:ui:testDebugUnitTest --tests "*CapsuleRegressionGuard*"` 拿到完整红灯清单。**
> 下面这 10 条是静态阅读确认会红的，其余 16 条要靠实跑确认 —— 不要假设它们一定绿。

### 4.1 必须**改写**（守的不变量已经不成立）

| 护栏 | 行号 | 冲突原因 | 处置 |
| --- | --- | --- | --- |
| `capsule_mustNotFillMaxWidth_soTouchesPassThrough` | `:245-251` | 禁止出现 `fillMaxWidth`，理由是"窗口撑满会吞掉整条底部触摸"。通栏**就是**要占满那条，前提消失 | 改写为新不变量：`width` 必须 `MATCH_PARENT` 且 `gravity` 含 `BOTTOM` 且 `y` 恒 0；并断言 `NOT_TOUCH_MODAL` 仍在（栏以上区域仍需穿透）。**不要**用 `constraints.maxWidth` 绕过——那是把护栏留成噪音 |
| `capsuleLift_mustBeWired` | `:840-875` | 4 条断言：`CAPSULE_LIFT` 声明存在、`bottomOffsetPx` 表达式含 `CAPSULE_LIFT` / `reservedPx` / `capsuleGapFor` / `CAPSULE_SHADOW_ROOM`。贴底后这四个概念全部消失 | 整条改写为「`y` 必须恒 0；`reservedPx` 必须出现在**栏内容的 bottom padding / Spacer** 表达式里」——把同一个"安全区不能丢"的意图搬到新结构上 |
| `bottomInsetCorner_mustSyncTo18dp` | `:706-719` | 断言 `CAPSULE_WINDOW_CORNER_RADIUS = 18.dp`，我们要 22dp | 改断言值为 22dp。注意它守的是一条**已 dead 的真模糊分支**（`UfiCapsuleBlurHost.kt:150-165`，运行期窗口背景恒 null），可以考虑连同那条 dead 分支一起删 —— 但**要单独决策**，别混在本次改造里 |
| `bottomInsetCorner_mustNotBePill` | `:729-736` | 禁止 `= 30.dp`。22dp 能过 | 与上一条成对，一起复核；若圆角将来调到 26 以上要重新看 |
| `capsuleWindow_mustStayWrapContent` | `:473-489` | 实际只断言 `Dialog(` / `usePlatformDefaultWidth = false` / 无 `Popup(` —— **能过** | 断言不动，但**方法名与注释必须改**（现在叫 "mustStayWrapContent"，而我们正是要改成 MATCH_PARENT，留着会误导后人） |

### 4.2 必须**删除**（随收起态一起退休）

| 护栏 | 行号 | 说明 |
| --- | --- | --- |
| `collapseDelay_mustBe1500ms` | `:292-298` | 正则抓 `COLLAPSE_DELAY_MS`，常量一删就抛「找不到」 |
| `collapsedScale_mustBePerceptibleButLegible` | `:307-321` | 同理，抓 `COLLAPSED_SCALE` |
| `expandTimer_mustResetOnEveryTap` | `:343-387` | 6 条断言全部围绕自动收起链路（`resetToken` / `CapsuleCollapseProbe` / `distinctUntilChanged` / `collectLatest` / `delay(COLLAPSE_DELAY_MS)` / `probe.expanded`） |
| `collapseOrigin_mustBeCenter` | `:749-757` | 断言 TabBar 有 `TransformOrigin.Center`；整体缩放的 `graphicsLayer` 删掉后就没有 transformOrigin 了 |
| `iconTransitions_mustBeDrivenByContinuousFactor` 的**第 4 条断言** | `:180-183` | 只删这一条（`EXPAND_SPRING` 后 40 字符内必须含 `spring(`）。**前 3 条保留**：颜色 lerp / alpha 插值 / `selectionFactor` 与收起无关，是选中态动画的护栏 |

删除时每条都在测试文件里留一行注释说明「2026-09-23 随悬浮胶囊形态一起退休，原护栏防的是 XX」——不要静默删掉，否则后人无法判断这个不变量是被废弃还是被遗忘。

### 4.3 **保留不动**

- `capsuleWindowParams_mustBeWrittenOnceWithEqualityGuard`（`:916-958`）：禁止 `setLayout(` / `addFlags(` / `clearFlags(` / `setDimAmount(` 回归。**这条比改造前更重要** —— 通栏后窗口更大，每帧 relayout 的代价更高
- `legacyPerTabExpansionMachinery_mustBeGone`（`:396-410`）：`assertFalse` 清单含 `LABEL_HOLD_MS` / `RAPID_THRESHOLD_MS` / `updateTabExpansion` / **`showLabel`**。删东西不会红，但 ⚠ **新写通栏代码时不要出现 `showLabel` 这个标识符** —— 标签常显时极容易顺手起这个名
- `capsuleBackground_mustBeContentLayer`（`:795-825`）：要求同时有 `.clip(` + `RoundedCornerShape(` + (`.background(` 或 `drawBehind`+`drawRect(`)。通栏仍满足（只是 shape 参数变了，它不查参数）
- `collapseOrigin_mustNotBeBottom`（`:768-775`）：`assertFalse` TabBar 里有 `TransformOrigin(0.5f, 1f)`，删了照样绿。注意 `MainNavGraph.kt:848` 确实用了它，但护栏不检查该文件
- `NavInsetHandoffGuardTest` 的两条 inset 护栏：语义不变，保留

---

## 5. 边缘情况（**动手前逐条读**）

### 5.1 三键导航下栏会很高
`readBottomReservedPx()` 在三键下返回约 48dp，栏总高 58+48 = 106dp。这是**正确**的（三键本来就该给足），但要确认：
- 页面 inset 跟着变（`CapsuleInsetHolder` 发布的是总高，自动跟随）
- 选中药丸的垂直居中只算**内容区 58dp**，不能把安全区算进去，否则药丸会偏下

### 5.2 横向拖拽切页：**删除**（2026-09-23 已定）

贴底后栏占满屏幕最底部，左右边缘约 24dp 是系统返回手势热区，拖着切 tab 会被判成返回。
传统底栏本来不支持拖拽，而这套拖拽是为「可交互悬浮浮层」设计的 —— 少一套手势少一处冲突，
也就不需要引入 `setSystemGestureExclusionRects()`（代码里现在完全没有这个 API）。

**删除范围**（`UfiCapsuleTabBar.kt`）：`draggable` / `onDelta` / `dragPos`、`isDragging`、
`CapsuleSettleProbe` 里与拖拽相关的字段。

> ⚠ **`settleIndex` 不能顺手删。** 它解决的是「pager 收到跨多页切换请求时逐页扫场，
> `selectionProgress` 从起点一路扫过来，滑块去跟它就表现为被拽回原处再追一遍」——
> 而**点击切页同样会触发逐页扫场**（点第 1 格跳到第 4 格时 pager 也是逐页扫的）。
> 拖拽只是最容易复现的入口，不是唯一入口。删拖拽后 `settleIndex` 仍要保留，
> 只是它的赋值时机从「松手」变成「点击」。
>
> 同理要甄别的还有 `isScrollingState`：它现在服务于「滚动时不自动收起」，收起态删掉后
> 这个用途消失；但如果 `settleIndex` 的归位判据也读它，就不能一起删。实施时 grep 确认。


### 5.3 手势小白条的对比度
~~手势导航下平台做 dynamic color adaptation，小白条按**背后内容**自动反色，不需要额外处理。~~

❌ **这条假设是错的，2026-09-24 真机证伪。** 小白条的明暗只看**窗口的**
`isAppearanceLightNavigationBars`，平台**不会**去采样背后像素。

为什么悬浮胶囊时代没暴露：胶囊被抬离底边，小白条压的是**页面**底色，而页面在 Activity 窗口里，
那个窗口的 appearance 由 `MainActivity.enableEdgeToEdge()` 按**系统**夜间模式定过一次。
贴底通栏之后小白条压的是**底栏 Dialog 窗口**里的栏底色，而这个窗口从来没设过 appearance
⇒ 取默认值「深背景」⇒ 小白条恒为白 ⇒ 压在白底栏上直接看不见。

处置（两个窗口各设一次，判据不同）：
- 底栏 Dialog：`CapsuleWindowMetrics.lightNavHandleSurface = dockSurfaceColor(palette).luminance() > 0.5f`
  —— 判据必须是栏**实际画的那块颜色**，不能用 `palette.isDark`（皮肤里有偏亮的深色底 / 偏暗的浅色底）。
- Activity（二级页、底栏隐藏时）：`MainActivity` 里按本应用算出的 `isDark` 设
  `isAppearanceLightNavigationBars` 与 `isAppearanceLightStatusBars`。
  `enableEdgeToEdge()` 只在 `onCreate` 按 `Configuration.uiMode` 定过一次且之后不更新 ——
  「系统深色 + 应用内浅色」这种组合下状态栏图标与小白条都会是白的、压在浅色页面上看不见。

仍然成立的部分：官方建议「手势导航栏始终透明」，我们正是如此（`navigationBarColor = TRANSPARENT`
+ `isNavigationBarContrastEnforced = false`）。**不要**给安全区额外加暗色块去"保护"小白条 ——
那会破坏沉浸。


### 5.4 二级页隐藏时的触摸
`CapsuleTouchGate.interactive`（写入方 `MainNavGraph.kt:287`）在二级页把 `FLAG_NOT_TOUCHABLE` 加上。通栏后窗口矩形更宽，这条**更重要**，不能顺手删。验收要专门测一次：二级页里点底部区域应该穿透到页面内容。

### 5.5 进出场动画要重做
`MainNavGraph.kt:848` 现在用 `TransformOrigin(0.5f, 1f)` 做「从底部中心缩放」。贴底通栏的进出场应该是**整条纵向滑动**（`translationY` 从总高滑到 0）。同时 `:737-738` 的 `+ 38.dp` 锚点必须改成 0。

### 5.6 `LocalCapsuleExpandProgress` 的删除顺序
BlurHost 侧消费在 `:1104`/`:1268`，TabBar 侧生产在 `:724`。**先拆消费，再删生产**，否则中间状态是「provides 了一个没人写的 0f」，虽然能编译但会留下误导性死代码。

### 5.7 `naturalSize` 的 width 分支
改 MATCH_PARENT 后 width 分支退休，但 **height 分支要留**（栏总高仍由内容测量决定）。不要顺手把整个 `LocalCapsuleNaturalSize` 删掉。

### 5.8 圆角与 clip 的关系
底色、高光、发丝线都要被 22dp 上圆角裁剪，所以 `clip` 必须在 `drawBehind` **之前**。发丝线画在 `top = 0`，clip 之后它在圆角处会自然收窄——这是想要的效果，不要为了"线要通到边"去掉 clip。

### 5.9 横屏 / 挖孔
`LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS` 保留。横屏下 `readBottomReservedPx()` 可能返回 0（导航栏移到侧边），此时栏总高 = 58dp，图标直接贴屏幕底边 —— 这是对的，但要实测确认没有被侧边导航栏挡住左右两格。

### 5.10 不要在本次改造里动真模糊那条 dead 分支
`UfiCapsuleBlurHost` 里 `wantBlur` 恒 false、窗口背景恒 null 的那套（`:150-165`、`:1205-1206`、`:1247`）与本次无关。它牵着 `bottomInsetCorner_*` 两条护栏，**单独决策、单独一次改动**，混进来会让 diff 无法审查。

### 5.11 tabs 数量与窄屏标签
tab 数可能是 4 或 5（旧注释按 5 个算过 340dp）。等分格宽后，5 tab × 360dp 窄屏 = 每格 72dp，
「仪表盘」三字 10sp 约 30dp，放得下。但要实测确认：
- 标签 **`maxLines = 1` + `TextOverflow.Ellipsis`**，绝不允许换行（换行会把内容区顶破 58dp）
- 选中药丸 `horizontal inset 10dp` 后宽 52dp，包不包得住三字标签 —— 包不住就把 inset 降到 6dp

### 5.12 超宽屏 / 平板 / 折叠屏展开
通栏在 800dp 宽屏上每格 200dp，图标散得极开、观感崩坏。
处理：**背景仍通栏铺满，但 tabs 行给一个 `widthIn(max = 480.dp)` 并居中**。
两者分离是本设计的既有结构（§3），加这条约束不额外增加复杂度。

### 5.13 字体缩放（fontScale）
系统字体放到 1.5× 时 10sp 标签渲染成 15sp，固定 `height(58.dp)` 会把内容压扁/裁切。
处理：内容区用 **`heightIn(min = 58.dp)`** 而不是 `height(58.dp)`，让它能长高；
页面 inset 发布的是**实测总高**而不是常量 58 + reserved（`CapsuleInsetHolder` 本来就是发布实测值，
这条只要求不要图省事改成写死常量）。
⚠ 栏长高后窗口 height 也要跟着变 —— 靠 `naturalSize.height` 那条链路，见 §5.7。

### 5.14 选中态 B 的白字对比度（**换配色时会踩**）
选中药丸是实色 accent + 白图标白字。项目有多套配色，某些主题的 accent 偏亮（浅黄绿、浅青）时
白字对比度不足 3:1，直接不可读。
处理：按 accent 的相对亮度择一 —— `luminance() > 0.55f` 时药丸内的图标与文字改用
`palette.textPrimary` 而不是白色。这个判据要写成一个小函数并加注释，
否则下次有人加主题时又会踩（这正是 2026-09-03「上一版主题残影」那类问题的同源形态）。

### 5.15 圆角的双份真源
窗口背景圆角 `CAPSULE_WINDOW_CORNER_RADIUS`（dead 分支用）与 Compose 侧 `clip` 的圆角是**两个值**。
本次把 Compose 侧改 22dp 时，那个常量也要同步改 22dp（护栏 `bottomInsetCorner_mustSyncTo18dp`
正是守这个"同步"意图，不要只改一边把护栏断言值糊过去）。

### 5.16 底色公式在深色主题下方向会反（**必须分开写**）
`lerp(cardBg, textPrimary, 0.06f)` 在浅色下是「往深压」（textPrimary 是深色），
但在深色主题下 textPrimary 是**浅色** —— 同一个公式会把栏**提亮**，方向正好相反，
结果是深色主题下底栏发灰发亮，比现在更糟。
处理：浅色 `lerp(cardBg, textPrimary, 0.06f)`、深色 `lerp(cardBg, Color.Black, 0.10f)`，
两个分支各自写死，并在注释里写明「这两个系数不是同一个量纲，不要为了"统一"合并」。

### 5.17 栏底色与页面底色过于接近
浅色主题 page ≈ `#eef1f6`、栏 ≈ `#f0f2f5`，几乎同色，分界只靠 1px 发丝线 + 6dp 上缘高光。
实测若仍分不清，优先把 §1 的 0.06 提到 0.09（而不是加粗发丝线 —— 粗线会回到"贴纸边"那种廉价感）。

### 5.18 输入法弹起
底栏在 Dialog 窗口里且带 `FLAG_NOT_FOCUSABLE`，输入法不会推它，**会被键盘直接遮住** —— 这是正确行为
（底栏该被遮）。要验证的是不出现「栏浮在键盘之上」的错觉：键盘弹起时栏应完全不可见，
而不是露出上圆角那一条。

### 5.19 RTL
格顺序与药丸位移都基于 `indicatorPos × 格宽`，RTL 下 Compose 会自动镜像 `Row`，
但**手算的药丸 x 偏移不会自动镜像**。实施时用 `LocalLayoutDirection` 判一次，或改用
`Modifier.offset { }` 配合 `layoutDirection` 取反。当前胶囊也有这个问题（旧代码同样手算），
所以不算回归，但既然重写就一次做对。

### 5.20 无障碍
- 标签常显后，Tab 的 `contentDescription` 与可见文字重复 → 会被朗读两遍。
  处理：图标 `contentDescription = null`，把语义挂在整个 Tab 的 `semantics { }` 上（`role = Tab` + `selected`）
- 触摸目标：内容区 58dp ≥ 48dp 下限，通过；但 §5.12 的 `widthIn(max)` 之后单格宽 480/5 = 96dp，仍通过

### 5.21 `ufiCapsuleBottomInset` 与页面自带 bottomBar 叠加
6 个调用点中若有页面自己还有底部条（媒体页的 mini bar），会出现 inset 双算。
实施前 grep 一遍这 6 处，确认没有"既用 `ufiCapsuleBottomInset` 又自己加了 bottom padding"的。

**核查结果（2026-09-24，阶段 2 落地后实查）**

- 6 个调用点：`DashboardScreen:229` / `NetworkScreen:210` / `MonitorScreen:549` / `MonitorScreen:762` /
  `ToolsScreen:70` / `SettingsScreen:276`，`extra` 一律 `Spacing.Medium`(8dp)。
- **媒体页不是主 Tab**（`BOTTOM_TABS` = 仪表盘/网络/监控/工具/我的），mini bar 所在的三个页面都是 detail 路由，
  底栏在那里已整条滑出 + `alpha=0` + `FLAG_NOT_TOUCHABLE`。原先担心的「mini bar 被底栏压住」**不成立**。
- ❗**真正的双算在页壳**：`UfiScaffold.kt:623` 的内容 Box 有 `windowInsetsPadding(WindowInsets.navigationBars)`，
  而 `ufiCapsuleBottomInset` 发布的值里**也含**安全区 ⇒ 6 个调用点各多留一个 `navigationBars.bottom`
  （手势≈24dp / 三键≈48dp）。这是**迁移引入**的：旧口径下胶囊的 30dp 抬高恰好抵掉了这一项。
- ❗`MonitorScreen:575` 的浮动分页条用写死的 `offset(y = -40.dp)` 定位，通栏顶边在 82~106dp 处 ⇒ 被压住。
  （同文件 `:1374` 是同一份数字，但在二级页上、底栏已隐藏，**不要连带改**。）
- `ThemeManager` 的 `capsuleLiftDp`（设置 → 外观 → 胶囊抬高）已成死开关：窗口 y 恒 0，拧了没反应。归阶段 3 清理。



---

## 6. 阶段 1：新建通栏低栏组件（编译保持绿）

> 策略：**不原地改 `UfiCapsuleTabBar`，而是新建 `UfiBottomDock`**，用一个开关切换挂载。
> 这样每一步都能编译、能装包对比，也符合「排查性删除不等于永久删除」——旧实现留到新实现验收通过再删。

- [x] 1.1 新建 `app/ui/.../common/UfiBottomDock.kt`：按 §1 参数实现静态通栏低栏（无动画、无收起、标签常显、选中态 B）。格宽等分，选中药丸位置用 `animateFloatAsState` 跟随 `selectedIndex`。
- [x] 1.2 背景/内容分离（§3）：`drawBehind` 铺满含安全区，内容 `Row(height=58.dp)` + `Spacer(reservedDp)`。安全区值从 `LocalCapsuleBottomReserved`（新增 local，由 BlurHost 提供现成的 `reservedPx`）读取。
- [x] 1.3 `MainNavGraph` 加一个编译期常量开关（如 `private const val USE_BOTTOM_DOCK = true`）在两个实现间切换，**先默认 false**。

**验收**
- [x] `:app:ui:compileDebugKotlin` 通过，`CapsuleRegressionGuardTest` **全绿**（还没动旧实现）
- [ ] 开关打到 true 装包，肉眼核对 §1 的 8 项参数 —— **推迟到阶段 2 之后**：阶段 1 只有内容层，窗口层还是 wrap-content + `y` 抬起，此时打开开关看到的不是通栏形态，核对无意义

---

## 7. 阶段 2：窗口层切换 + 护栏改写（**会红，必须一次做完**）

- [x] 2.1 `applyCapsuleWindowParams`：width → MATCH_PARENT、gravity → BOTTOM、y → 0（§2.1 表）。`bottomOffsetPx` 与 `CAPSULE_LIFT` / `capsuleGapFor` / `CAPSULE_SHADOW_ROOM` 一并退休。
- [x] 2.2 `MainNavGraph`：`:737-738` 的 `+ 38.dp` → 0；`:156` 的 `CAPSULE_SHADOW_ROOM` padding 去掉；`:848` 进出场改纵向滑动（§5.5）。
      实际落地：抬升项归 0 后，滑出距离改为 `safeDrawing.getBottom + DOCK_CONTENT_MIN_HEIGHT`（= 栏自身总高）——
      只把 38 改成 0 会让隐藏态剩 58dp 内容带留在屏幕上、滑不干净。
- [x] 2.3 页面 inset 计算式改为「58 + 安全区」（`CapsuleInsetHolder`）。
      裁决：与 §5.13 的「发布实测总高」**取较大值**。两者常规字号下相等（dock 根 Column = 内容区 + 安全区 Spacer），
      放大字号时实测更大；取 max 同时满足两条。
      同时补了计划未覆盖的缺口：`UfiBottomDock` 需 `onSizeChanged` 回写 `LocalCapsuleNaturalSize` ——
      此前只有 `UfiCapsuleTabBar` 供数，开关切 true 后「测量前不发布」闸门会永不放行、页面停在 88dp 兜底且不报错。
- [x] 2.4 删横向拖拽（§5.2 已定）：`draggable` / `onDelta` / `dragPos` / `isDragging`。
      `settleIndex` 已保留，赋值时机改到 `CapsuleTab.onClick`；`isScrollingState` **必须保留**
      （归位判据 `latestIndex == settling && !isScrollingState.value` 确实读它）。
- [x] 2.5 护栏按 §4.1 改写 5 条、§4.2 删除 5 条，每条留注释说明原委。
      实际执行（2026-09-24）见 §9：改写 5 条（含一条从 §4.3「保留」改判为改写）、删除 4 条 + 1 条断言、
      新增 6 条；§4.1 的圆角 18→22 **不改**（理由并入护栏 KDoc，与 §5.10 的"单独决策"一致）。
- [x] 2.6 开关默认值改 true。
- [x] 2.7（计划外，补缺口）药丸接 pager 连续进度。页面横滑手势（`UfiPageSwitcher(swipeEnabled = true)`）**没有**随 §5.2 一起删，
      只有「在栏本体上拖拽」被删。药丸若只跟整数下标，横滑时不跟手、要等落定才跳 —— 相对胶囊是观感退化。
      已移植胶囊那套 `Animatable` + 三分支（钉住 / 跟手 / 归位）与 `awaitSettleAuthority` 的判据，
      `DOCK_SELECTION_SPEC` 随之删除（统一走与页面同源的 `indicatorSettleSpec`）。
- [x] 2.8（计划外，补缺口）图标与标签着色逐帧跟药丸。2.7 之后药丸跟手但取色仍是布尔式，
      横滑到两格之间时旧格白字落在同色栏底上、约 200~400ms 不可读。已改为每格一份 `selectionFactor`
      （`1 - |progress - index|`），读取压在绘制期（图标 `drawWithCache` + `ColorFilter`、标签 `ColorProducer`）。
- [x] 2.9（计划外，§5.21 实查发现的两处真问题）
      ① 页壳 `UfiScaffold.kt:623` 的 `windowInsetsPadding(navigationBars)` 与 inset 里的安全区双算，
         6 个调用点各多留 24~48dp。**裁决**：不动页壳（二级页确实需要它），改由 `ufiCapsuleBottomInset`
         显式减掉页壳已消费的那一段，并加一条护栏钉住这个配对关系。
         实际落地：减法提成 `private fun Density.capsuleBottomClearance(total, navigationBars, extra)`
         = `(total − navigationBars.bottom).coerceAtLeast(0.dp) + extra`，并派生 `Modifier.ufiCapsuleBottomLift`
         给 ② 复用（否则 feature 模块要抄第二份公式）。`extra`(8dp 呼吸) 刻意排在扣减之外；
         只减 navigationBars 一路，因为 `readBottomReservedPx` 的口径是 `max(navigationBars, systemGestures)`
         且带 24dp 手势兜底，页壳只 padding 了 navigationBars，兜底高出来的差额必须留着。
         护栏 `capsuleInset_mustDeductShellConsumedNavigationBars`：两端各断言一次，单独改任一端就红。
      ② `MonitorScreen.kt` 浮动分页条的 `offset(y = -40.dp)` 改用 `ufiCapsuleBottomLift`（`EventsCenterContent`
         里那份同值 `-40.dp` 在二级页上仍是对的，已确认未连带改）。

**验收**
- [x] `:app:ui:testDebugUnitTest --tests "*CapsuleRegressionGuard*"` 与 `"*NavInsetHandoff*"` 全绿
      （2026-09-24：35 + 22 = 57 条，BUILD SUCCESSFUL）
- [x] `:app:assembleBenchmark` 通过（2026-09-24，连带 `:app:ui:testDebugUnitTest` 全量 188 条全绿；
      其中 `UfiAlertToastRulesTest.unknown_alert_type_falls_back_to_raw_value` 是**迁移前就红**的陈旧断言
      —— 它断言 `alertTypeLabel("cpu_temp") == "CPU 温度"`，而该 type 早在 2026-09-21 对齐 AlertEngine 时删掉，
      已按「测试断言旧行为就改测试」修正）
- [ ] 手工：手势导航机型 —— 图标不压小白条、底色铺到屏幕底边、小白条自动反色
- [ ] 手工：三键导航机型 —— 栏总高约 106dp，药丸垂直居中在上方 58dp 内
- [ ] 手工：二级页 —— 点底部区域能穿透到页面内容（§5.4）
- [ ] 手工：横屏 —— 左右两格没有被侧边导航栏挡住（§5.9）
- [ ] 手工：字体缩放调到 1.5×，栏能长高、标签不换行不裁切（§5.11、§5.13）
- [ ] 手工：输入法弹起时栏完全不可见，不露上圆角（§5.18）
- [ ] 手工：切一套 accent 偏亮的配色，确认选中药丸里的文字仍可读（§5.14）
- [ ] 手工：点第 1 格直接跳第 4 格（跨多页），药丸不应"先被拽回原处再追一遍"（§5.2 的 settleIndex）
- [ ] 手工：深色主题下栏底色应比卡片**更深**，不是更亮（§5.16 —— 这条最容易写反）
- [ ] 手工：横滑切页时药丸**跟手**、图标与标签的颜色同相位跟着走（2.7 / 2.8）；
      滑到一半松手回弹、快速连续横滑，都不应出现药丸或颜色的二次抽动
- [ ] 手工：5 个主 Tab 的列表滚到底，最后一行与栏之间只有一段呼吸间距（2.9 ①，修好前是两段）
- [ ] 手工：监控 → 总览 → 聚合模式（事件类型多于一页），浮动分页条不被栏压住（2.9 ②）


---

## 8. 阶段 3：清理旧实现

> ⚠ **前置条件：阶段 2 的手工验收（§7 那串"手工"项）必须先在真机过一遍。**
> 阶段 3 会删掉 `UfiCapsuleTabBar` 与编译期开关，删完就没有一行回退的余地了；
> §6 的策略原话是「旧实现留到新实现验收通过再删」。自动化验证（编译 + 57 条护栏 + 188 条单测
> + `:app:assembleBenchmark`）已全绿，但那些**都证明不了栏在真机上长对了**。


- [ ] 3.1 删 `UfiCapsuleTabBar` 的收起/展开整套（§2.4 清单），注意 §5.6 的删除顺序。
- [ ] 3.2 删编译期开关与旧组件本体；`naturalSize` 只留 height 分支（§5.7）。
      ⚠ **先抽公共 settle 助手再删文件**：`UfiBottomDock` 现在复用了 `UfiCapsuleTabBar.kt` 里的
      `settledIndexOf` / `authorityCaughtUp` / `settleNeedsConfirmWindow` / `indicatorSettleSpec` /
      `IDLE_SETTLE_CONFIRM_MS` / `SETTLE_SNAP_EPSILON` / `AUTHORITY_CATCHUP_TIMEOUT_MS`。
      连带项：`awaitDockSettleAuthority` 与 `awaitSettleAuthority` 是同一套编排的两份副本
      （当时没能合并，因为护栏 `authorityCatchUp_mustHaveNaNAndTimeoutFallbacks` 用字面量
      `private suspend fun awaitSettleAuthority` 截函数体、放宽可见性就会红）——抽公共文件时一并合并，
      那条护栏的字面量同步改。
- [ ] 3.3 把 `.comate/` 下四个预览 HTML 归档或删除（它们是过程产物，不该留在仓库里）。
- [ ] 3.4 删死设置项 `ThemeManager.capsuleLiftDp`（`DEFAULT_CAPSULE_LIFT_DP` / `CAPSULE_LIFT_DP_MIN/MAX` /
      setter / prefs key）**以及设置页里暴露它的入口**。窗口 y 恒 0 后它拧了没反应，属于"能点但无效"的 UI。
- [ ] 3.5 按 §5.10 决策圆角 dead 分支：`CAPSULE_WINDOW_CORNER_RADIUS(18dp)` 与 `DOCK_SHAPE(22dp)` 现在是两份真源，
      但前者只服务 `wantBlur == false` 的不可达分支（窗口背景恒 null）。要么删 dead 分支连带常量与两条护栏，
      要么改 22 并把护栏改成守 `DOCK_SHAPE`。
- [ ] 3.6（2.9 实施时发现的语义空洞）`CapsuleInsetHolder` 的发布口径只看「栏测量了没」，不看「栏此刻可见吗」。
      底栏窗口自 2026-09-15 起挂载后不再卸载，二级页只把内容动到 `alpha=0`，但 inset 仍发布真值 ——
      二级页若有页面读 `ufiCapsuleBottomInset` 就会凭空多留一段。当前 6 个调用点都在主 Tab，**暂不触发**；
      属迁移前就存在的形态，顺手收掉即可（把 `showBottomBar` 也纳入发布判据）。

**验收**
- [ ] 全量 `:app:assembleBenchmark` + 两个护栏测试通过
- [ ] 全仓 grep 确认无残留引用：`COLLAPSED_SCALE` / `expandProgress` / `labelReveal` / `CAPSULE_LIFT` / `CAPSULE_SHADOW_ROOM`

---

## 9. 变更记录

| 日期 | 阶段 | 内容 |
| --- | --- | --- |
| 2026-09-23 | — | 建立本文档。设计参数定稿（22/58/24/选中态 B/发丝线）；确认手势区沉浸的基础设施已齐全，唯一缺的是把 `reservedPx` 从窗口 y 挪进栏内 padding；静态确认 10 条护栏需改写或删除，待实跑补全清单。**待决**：§5.2 横向拖拽去留。 |
| 2026-09-23 | — | §5.2 定为**删除横向拖拽**，并记下「`settleIndex` 不能顺手删」（点击跨多页切换同样触发 pager 逐页扫场）。边缘情况补到 21 条，其中两条是静态复核时发现的真问题：§5.16 底色 lerp 公式在深色主题下方向会反（会把栏提亮，比现状更糟）、§5.14 实色药丸配白字在 accent 偏亮的主题下对比度不足。另补 §5.12 超宽屏 tabs 需 `widthIn(max)` 居中、§5.13 fontScale 需 `heightIn(min)`、§5.19 RTL 手算偏移不会自动镜像、§5.20 标签常显后的重复朗读。 |
| 2026-09-24 | 1 | 阶段 1 完成。`UfiBottomDock.kt`（456 行）由子代理产出，§5 的 5.1/5.9/5.11~5.20 逐条落实；该子代理**报告任务失败**且确实只做完 1.1 与 1.2 的一半 —— `LocalCapsuleBottomReserved` 只声明未 provide（值恒 0）、1.3 的开关完全没加，两处由我补齐。`:app:ui:compileDebugKotlin` + `CapsuleRegressionGuardTest` / `NavInsetHandoffTest` 全绿，旧实现零改动。`USE_BOTTOM_DOCK` 默认 `false`，装包核对推迟到阶段 2 之后（内容层单独打开看不到通栏形态）。 |
| 2026-09-24 | 2.5 | 护栏改写完成，`CapsuleRegressionGuardTest` 35 条 + `NavInsetHandoffGuardTest` 21 条全绿（改写前 34 + 20 = 54；**§4 开头「1319 行、26 个 @Test」的计数已过时**，实跑是 34 条）。实跑红灯只有 4 条（`capsuleLift_mustBeWired`、`dialogHost_mustAnchorBottomCenter`、`settleDrivers_*` 的 `probe.dragging`、`settleConfirmWindow_*` 的 `motionSettlePending = true` ≥2）—— §4.2 列的 5 条**当时都还是绿的**（旧实现要到阶段 3 才删），删它们的理由是"守的产品形态已下线"，不是"它会红"。三处与 §4 原计划不同：① 圆角 `bottomInsetCorner_*` **不改 22dp**（通栏圆角的真源是 `DOCK_SHAPE`，与窗口侧那个常量不构成一对；后者仍与 `UfiCapsuleTabBar.CAPSULE_CORNER(18)` 配对，随 §5.10 的 dead 分支单独决策）；② `collapseOrigin_mustNotBeBottom` 从 §4.3「保留」改判为**改写**（它只读 tabBar，已无守护对象；真正在线的"底边锚点缩放"风险在 `MainNavGraph` 进出场里，断言整条搬过去并加钉滑动距离来源）；③ `capsule_mustNotFillMaxWidth` 的改写把重点放在**窗口高度**（`MATCH_PARENT` 宽是目标，全屏高才是新的"吞掉整页触摸"形态）。新增 6 条守通栏新红线：栏底色在内容层 + 窗口 background 恒 null、药丸只画在内容区、通栏不得长出收起态且标签单行、页面 inset = 58 + 安全区且 58 只有一份、底色按 `palette.isDark` 分两支、栏必须回写实测高度给「测量前不发布」闸门供数。 |
| 2026-09-24 | 2 | 首次装包真机反馈两条，均已修。① **整屏都变成导航栏**：`DockTab` 用了 `fillMaxHeight()`，而 tabs 行只有 `heightIn(min = 58dp)`（下限，maxHeight 无上界），通栏窗口传下来的 maxHeight 是**整屏高度** ⇒ 单格撑满整屏、一路把 Row/Box/根 Column 撑满、根 Column 的 `drawBehind` 底色铺满全屏。悬浮胶囊时代同一句是安全的（wrap-content 小窗的 maxHeight 本就只有胶囊那么高）—— **形态迁移改变了既有 modifier 的语义**，光看那一行看不出问题。改为 `defaultMinSize(minHeight = maxOf(58dp, 48dp))`（只给下限、上限交给内容），并新增护栏 `dockMustNotFillAvailableHeight_orTheBarEatsTheWholeScreen` 钉死 `fillMaxHeight`/`fillMaxSize` 不得出现。② 上缘 6dp 高光由「白色→透明渐变」改为**主题色 `palette.accent` 实色**（用户定稿：高光用主题色、不要渐变）。③ 栏底色的竖向渐变（0.94→0.90，两端只差 4% 亮度、真机读不出渐变只读出「上下不一样干净」）随后也按用户要求改为**实色** `dockSurface = dockBase.shadeBy(0.92f)`（取原两端中间值，整体明度不变）；护栏 `dockSelectionPill_mustBeDrawnInsideContentAreaOnly` 的底色指纹从 `drawRect(brush = Brush.verticalGradient` 同步改为 `drawRect(color = dockSurface`。顶边发丝线与选中药丸自身的 160° 渐变（选中态 B 的定稿）**保留不变**。护栏 35 → 36 条，`:app:ui:testDebugUnitTest` 全绿，`:app:assembleBenchmark` 产出 `app/build/outputs/apk/benchmark/app-benchmark.apk`（8.1 MB）交付真机验收。 |
| 2026-09-24 | 2 | **第三轮真机反馈：「太难看了，改朴素点」** —— 删掉全部装饰层，只留「实色底 + 1px 发丝线 + 实色药丸」。逐项：① 栏顶那条 6dp 高光带整条删除（白色渐变→主题色实色都试过，实色版观感像"底栏自己又长了个顶栏"），连带删 `DOCK_TOP_SHEEN_HEIGHT`；② 药丸**外发光**删除（6 圈同心圆角矩形按 `(1-t)²` 近似高斯，5dp 满 alpha → 14dp 归零）—— 在实色底栏上读不成"光"、只读成一圈脏边，顺带省掉每帧 6 次 `drawRoundRect`（药丸逐帧跟手，这 6 次每帧都画）；③ 药丸的 **160° 渐变填充**改实色 accent（与"不要渐变"一致，`pillFillBrush()` 与 `PILL_GRADIENT_*` 一并退休）；④ 药丸**内顶 1px 白高光**删除。**保留**：22dp 上圆角、58dp 内容区、安全区 Spacer、顶边 1 物理像素发丝线（用户第一轮就明确要的）、药丸圆角 15dp 与内缩 4/10dp。§1 参数表里「选中态 B = 实色渐变药丸」据此改判为**纯实色**。删除的参数推导全部留在 `drawSelectionPill` 的 KDoc 墓碑里，不要凭记忆重写。护栏无需改动（没有任何断言钉过这些装饰层），`:app:ui:testDebugUnitTest` 与 `:app:assembleBenchmark` 全绿。 |
| 2026-09-24 | 2 | **第四轮真机反馈：「改成白底 + 手势区小白条没有沉浸」。** ① 底色直接改成 `palette.cardBg`（浅色主题下即白底），收进 `internal fun dockSurfaceColor(palette)`；原来那套 `lerp(cardBg, …)` 压暗一档再乘 0.92 的混色退休，`shadeBy()` 一并删除。§5.16「两支必须分开写」的坑仍然真实，但现在**根本不做混色**，护栏 `dockSurfaceColor_mustBranchByResolvedDarkness` 改写为 `dockSurfaceColor_mustBeSinglePaletteToken_andDriveGestureHandleAppearance`。② **§5.3 的假设被真机证伪**：小白条不会按背后像素自动反色，它只看**窗口的** `isAppearanceLightNavigationBars`。悬浮胶囊时代小白条压的是 Activity 窗口里的页面，看不出问题；贴底通栏后它压的是**底栏 Dialog 窗口**，而那个窗口从未设过 appearance ⇒ 默认「深背景」⇒ 小白条恒为白 ⇒ 压在白底栏上隐形。修法是两个窗口各设一次、判据不同：Dialog 用 `dockSurfaceColor(palette).luminance() > 0.5f`（栏实际画的颜色，不是 `palette.isDark` —— 皮肤里有偏亮的深色底），Activity 用本应用算出的 `isDark` 同时设导航栏与状态栏（`enableEdgeToEdge()` 只在 onCreate 按**系统**夜间模式定过一次且不再更新，「系统深色 + 应用内浅色」下状态栏图标也是白的）。§5.3 已就地改写并标注证伪。 |
| 2026-09-24 | 2 | **第五轮真机反馈：深色模式下全 App 底部一条约手势区高度的白带**（主 Tab 与二级页都有 ⇒ 不是底栏画的；浅色主题下白底上看不出来 ⇒ 一直存在）。根因是 `Theme.UFIAXIS`（parent `android:Theme.Material.Light.NoActionBar`）**从未声明** `navigationBarColor` / `enforceNavigationBarContrast`，继承的是不透明浅色导航栏；运行期 `enableEdgeToEdge()` 虽会改透明，但那发生在 `onCreate`——窗口已按主题创建过，存在窗口期，而部分 ROM 会在窗口创建时**快照**这个值、之后不再重读。同一个坑 2026-08 在 `UfiDialogWindowTheme` 上修过（那里的注释原文就是「必须写在主题里而不是只在 Kotlin 侧设，否则每次重建闪一帧白边」），Activity 侧一直漏着。处置：把 `navigationBarColor` / `enforceNavigationBarContrast` / `statusBarColor` / `enforceStatusBarContrast` 四项补进 `Theme.UFIAXIS`。⚠ 待真机确认；若仍白，则是 ROM 按「App 声明 Light 主题」强制补的手势条背景，需要换绕法（下一步用 `adb shell dumpsys window` 核对导航栏实际取色）。 |
| 2026-09-24 | 2 | **上一条判断被推翻，真因定位（ColorOS 16 / Android 16）。** 主题四项无效：读 `androidx.activity` 1.12.4 的 `EdgeToEdge.kt` 确认默认 `navigationBarStyle = SystemBarStyle.auto(DefaultLightScrim=0xE6FFFFFF, …)`，但 `auto()` 的 `nightMode == MODE_NIGHT_AUTO` 会让 `getScrimWithEnforcedContrast()` 直接返回 `TRANSPARENT`（`:199-205`）—— 所以导航栏色本来就是透明的，那四项运行期也会被覆盖。**真相是用户看到的不是"白带"**：截图 + 问答确认为「底栏灰色到小白条上方就停了，手势区露出页面纯黑」（浅色主题下页面近白、卡片近白，所以完全看不出）。成因：底栏那个 `windowIsFloating = true` 的 Dialog 窗口，在 Android 15/16 上**DecorView 会消费系统窗口 inset**（androidx 自己的注释：*"when the window size is WRAP_CONTENT, DecorView would consume the system window insets"*），`decorFitsSystemWindows = false` 对 floating 窗口不再生效 ⇒ 栏内容被顶到手势区上方、窗口最底部那一条没有像素。修法（不碰红线 R1、不改全局 `dialogTheme` 的 floating 语义）：① `CapsuleInsetHolder` 新增 `bottomReservedDp` 跨窗口发布预留区高度；② `MainNavGraph` 在**主窗口**（一定铺到屏幕真实底边）用同一个 `dockSurfaceColor(palette)` 补画那条带子，画在 `SharedTransitionLayout` 之后、不加任何手势修饰符、alpha 跟 `enterProgress`；③ 顺带修 `isGestureNavigationMode` —— 判据去掉 `&& navBar > 0`（ColorOS 手势导航下 `navigationBars.bottom` 也可能是 0，会让 24dp 保底失效），并把 `tappableElement` 加进 `readBottomReservedPx` 的并集。⚠ 仍待真机确认；上一条补的主题四项保留（本身正确、覆盖窗口创建期，只是不是本次的因）。 |
| 2026-09-24 | 2 | **底部白带：真因定位并修复（第三次改判，这次真机确认通过）。** 用「洋红/青色双色标记 + 三路 inset 读数」的诊断版一次定死：真机读数 `nav=56 ges=112 tap=0 reserved=112px/35dp`，且**青色完全盖住洋红** ⇒ 前两条改判里「底栏窗口铺不到屏幕底边」的假设**被证伪**，窗口几何一直是对的。决定性线索是用户提供的：启动检测页（底栏窗口**还没创建**）小白条正常，而二级页（底栏隐藏但**窗口仍挂载**）白带照旧 ⇒ 白带的归属是「只要底栏那个 Dialog 窗口存在」。真因：该窗口缺 `FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS` —— 平台契约里 `Window.setNavigationBarColor` **只在带这个 flag 时生效**，不带时是 no-op、系统改用自己那套不透明导航栏底色。Activity 窗口有这个 flag（`Theme.Material` 的 `windowDrawsSystemBarBackgrounds=true` + `enableEdgeToEdge` 也补），Dialog 主题默认没有 ⇒ 我们在主题与 `applyWindowBlur` 里写的两处 `navigationBarColor = TRANSPARENT` 全程空转。修法（都在 flag 单一真源里，走 `applyCapsuleWindowParams` 同一次带相等性守卫的写入，**不动全局 `dialogTheme`**）：`CAPSULE_WINDOW_FLAGS_ON` 加 `FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS`；`CAPSULE_WINDOW_FLAGS_OFF` 加 `FLAG_TRANSLUCENT_NAVIGATION` / `FLAG_TRANSLUCENT_STATUS`（这两个置位同样会让 navigationBarColor 被忽略，而 `windowIsTranslucent = true` 会带上它们）。收尾：删掉诊断代码、删掉已证明多余的「主窗口补画安全区带子」与 `CapsuleInsetHolder.bottomReservedDp`（纯重复绘制），并把 `themes.xml` 与 `MainActivity` 里那两处基于错误假设写下的注释改成"防御性、不是本次的因"。遗留待决策：`reserved` 取的是 `systemGestures`(112px/35dp) 而非 `navigationBars`(56px/17.5dp) —— 悬浮胶囊时代取手势热区是对的（不能落进热区），通栏贴底本来就横跨热区，这个值现在只让栏白高约 17dp。 |
| 2026-09-24 | 2 | **阶段 2 全部完成**（2.1~2.6 + 计划外的 2.7/2.8/2.9），`USE_BOTTOM_DOCK = true` 已是线上形态。自动化验收：`:app:ui:compileDebugKotlin` / `:app:compileDebugKotlin` / `:app:ui:testDebugUnitTest`（188 条）/ `:app:assembleBenchmark` 全绿。三处**计划未覆盖、实施中才暴露**的问题：① 阶段 1 把 `LocalCapsuleBottomReserved` 只声明未 provide，且 `UfiBottomDock` 不回写 `naturalSize` —— 开关切 true 后「测量前不发布」闸门会永不放行、所有页面静默停在 88dp 兜底（**不报错**，只是留白不对）；② 页面横滑手势并没有随 §5.2 一起删（删的只是「在栏本体上拖拽」），药丸若只跟整数下标就不跟手 ⇒ 补 2.7 移植连续进度、2.8 补图标/标签同相位着色；③ §5.21 实查发现真正的双算在**页壳** `UfiScaffold` 的 `windowInsetsPadding(navigationBars)`（旧口径被胶囊 30dp 抬高抵消，抬高归零后露出来），以及 `MonitorScreen` 浮动分页条写死的 `-40.dp`。另：`DOCK_SELECTION_SPEC`(200ms) 随 2.7 删除，两处归位统一走与页面同源的 `indicatorSettleSpec`。顺手修了一条**迁移前就红**的陈旧断言（`UfiAlertToastRulesTest` 的 `cpu_temp`）。**阶段 3 已加前置条件：真机手工验收未过之前不删旧实现。** |

