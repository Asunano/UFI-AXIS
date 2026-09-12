/**
 * 读取 CSS 自定义属性的当前值。
 *
 * 为什么需要它：`main.css` 的 `:root` / `.dark` 是全站颜色的唯一真源，但有两类消费方
 * **不认 `var(...)` 字符串**，必须拿到解析后的具体色值：
 *   1. ECharts —— CanvasRenderer 最终把颜色赋给 `ctx.fillStyle`，`var(--x)` 会被静默忽略
 *      （见 composables/chartTheme.ts 顶部注释记录的那次「图表莫名变灰」）
 *   2. Naive UI 的 `themeOverrides` —— 它要在 JS 里按主色推导 hover/pressed 等派生色，
 *      拿到字符串没法算（见 composables/naiveTheme.ts）
 *
 * 时序约定：`stores/app.ts` 的 `toggleDarkMode()` 先改 `darkMode` ref、再切
 * `documentElement` 上的 `.dark` class。依赖本函数的 computed 只要同时 `void appStore.darkMode`，
 * 求值就会推迟到下一次渲染 —— 那时 class 已生效，这里读到的是新主题的值。
 * **不要**把本函数的结果缓存到模块级常量，否则换暗色时不会更新。
 */
export function cssVar(name: string, fallback: string): string {
  if (typeof window === 'undefined') return fallback;
  const v = getComputedStyle(document.documentElement).getPropertyValue(name).trim();
  return v || fallback;
}
