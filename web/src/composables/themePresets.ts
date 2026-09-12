/**
 * 配色主题（皮肤）注册表。
 *
 * 机制：`main.css` 的 `:root` / `.dark` 是**默认皮肤**的令牌值；每个额外皮肤写在同一个文件
 * 末尾的「配色主题」小节里，成对出现，靠 `<html data-theme="xxx">` 生效：
 *
 * ```css
 * [data-theme='ocean'] {         // 浅色档：覆盖需要改的颜色令牌
 *   --accent-color: #0f766e;
 * }
 * .dark[data-theme='ocean'] {    // 暗色档
 *   --accent-color: #2dd4bf;
 * }
 * ```
 *
 * 特异性说明（决定了这套写法能不能生效）：`:root` 与 `[data-theme]` 都是 0,1,0，
 * 靠**源码顺序**取胜 —— 所以皮肤块必须放在 `main.css` 里 `:root` / `.dark` **之后**，
 * 这也是为什么不把它们拆成单独文件再 import（多一个引入顺序的坑）。
 * 暗色档 `.dark[data-theme]` 是 0,2,0，天然压过 `.dark`(0,1,0)，与顺序无关。
 *
 * 皮肤只该覆盖**颜色**令牌。圆角 / 间距 / 字号属于版式，不随皮肤变（与 Android 侧同口径）。
 *
 * 加一个皮肤要改两处，缺一不可：
 *   1. `styles/main.css` 末尾「配色主题」小节加上面那对块
 *   2. 本文件 `THEME_PRESETS` 加一条 `{ id, label }`
 * 只加 CSS 不加注册表 → 选不到；只加注册表不加 CSS → 选了没反应（等于假开关）。
 *
 * 消费方无需改动：`composables/naiveTheme.ts`（naive 组件色）与
 * `composables/chartTheme.ts`（ECharts 色）都是运行时读 CSS 变量的，
 * 且都声明了对 `themeId` 的依赖，切皮肤会自动重算。
 */

export interface ThemePreset {
  /** 写进 `<html data-theme>` 的 id，同时是 localStorage 的存储值 */
  id: string;
  /** 展示名 */
  label: string;
}

/** 默认皮肤：它的取值就是 `main.css` 的 `:root` / `.dark`，没有独立的 `[data-theme]` 块 */
export const DEFAULT_THEME_ID = 'default';

export const THEME_PRESETS: ThemePreset[] = [{ id: DEFAULT_THEME_ID, label: '默认' }];

/**
 * 把任意输入折叠成合法 id。
 *
 * 为什么需要：localStorage 里可能留着上一个版本删掉的皮肤 id（用户降级、或皮肤被下线），
 * 直接拿去写 `data-theme` 会得到一个没有任何 CSS 匹配的属性值 —— 页面看着是默认皮肤，
 * 但设置里显示的是那个已经不存在的名字。统一折叠回默认，避免这种「显示与实际不一致」。
 */
export function normalizeThemeId(id: string | null | undefined): string {
  if (!id) return DEFAULT_THEME_ID;
  return THEME_PRESETS.some((p) => p.id === id) ? id : DEFAULT_THEME_ID;
}
