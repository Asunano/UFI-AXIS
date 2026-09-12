/**
 * Naive UI 的 `themeOverrides` —— 让 naive 组件的语义色跟着 `main.css` 的 CSS 变量走。
 *
 * 为什么需要：`App.vue` 原来只传 `:theme="darkMode ? darkTheme : null"`，**没有任何 overrides**。
 * 于是全站有两套配色互不相干：naive 组件用它内置的绿色主色 `#18a058`，自研元素用
 * `--accent-color`（当时是 `#2080f0`，蓝）。同一个页面里 `n-button type="primary"` 是绿的、
 * 自研强调条是蓝的；换配色时也要改两处，而第二处根本不存在。
 *
 * 2026-09-08 决策 D1：naive 跟随 `--accent-color`（而不是反过来），因为 `--accent-color`
 * 已经是 27 处 `var()` 引用的既定口径，改它那侧要动的地方多得多。
 * 同日后续：`--accent-color` 改回 naive 默认绿 `#18a058`，所以现在两侧本来就同色 ——
 * 但这条 overrides 仍然要留着，否则以后换肤只有自研元素跟着变、naive 组件不动。

 *
 * **刻意不覆盖的东西**：
 *   - `common.borderRadius`：naive 默认 3px，`--radius-md` 是 12px。改它会把所有按钮/输入框
 *     变成大圆角，属于视觉改版而不是令牌收口，本轮明确不做。
 *   - 字号：同理，`--font-*` 比 naive 默认小一档（`tailwind.config.js` 里也记录了同一个坑）。
 *
 * `success/warning/error/info` 四色的当前取值与 naive 内置默认**逐字相同**
 * （`#18a058` / `#f0a020` / `#d03050` / `#2080f0`），所以接过来是零观感变化，
 * 纯粹为了「以后改 main.css 一处就能全站生效」。
 */
import { computed } from 'vue';
import type { ComputedRef } from 'vue';
import type { GlobalThemeOverrides } from 'naive-ui';
import { useAppStore } from '@/stores/app';
import { cssVar } from '@/composables/cssVar';

/** 解析 `#rgb` / `#rrggbb` / `rgb()` / `rgba()` 为 [r,g,b]，认不出就返回 null */
function parseRgb(color: string): [number, number, number] | null {
  const c = color.trim();
  if (c.startsWith('#')) {
    const hex = c.slice(1);
    if (hex.length === 3) {
      const r = parseInt(hex[0]! + hex[0]!, 16);
      const g = parseInt(hex[1]! + hex[1]!, 16);
      const b = parseInt(hex[2]! + hex[2]!, 16);
      return [r, g, b];
    }
    if (hex.length === 6 || hex.length === 8) {
      return [parseInt(hex.slice(0, 2), 16), parseInt(hex.slice(2, 4), 16), parseInt(hex.slice(4, 6), 16)];
    }
    return null;
  }
  const m = /rgba?\(\s*([\d.]+)[\s,]+([\d.]+)[\s,]+([\d.]+)/i.exec(c);
  if (!m) return null;
  return [Number(m[1]), Number(m[2]), Number(m[3])];
}

/**
 * 朝白（ratio > 0）或黑（ratio < 0）混色，用于推导 hover / pressed 档。
 *
 * naive 自己也是这么分档的：hover 比主色亮一点、pressed 暗一点。这里取 ±15%，
 * 拿 naive 默认绿 `#18a058` 验算得 `#3aae71` / `#148849`，与 naive 内置的
 * `#36ad6a` / `#0c7a43` 同一量级 —— 派生色不追求逐位一致，只要层级关系正确。
 *
 * 解析失败时原样返回：宁可 hover 没变化，也不要算出一个乱色。
 */
function shade(color: string, ratio: number): string {
  const rgb = parseRgb(color);
  if (!rgb) return color;
  const target = ratio > 0 ? 255 : 0;
  const t = Math.abs(ratio);
  const [r, g, b] = rgb.map((v) => Math.round(v + (target - v) * t)) as [number, number, number];
  return `rgb(${r}, ${g}, ${b})`;
}

/** 一个语义色展开成 naive 需要的四档（基色 / hover / pressed / suppl） */
function ramp(base: string) {
  return {
    color: base,
    hover: shade(base, 0.15),
    pressed: shade(base, -0.15),
    suppl: shade(base, 0.15),
  };
}

/**
 * 随暗色模式 / 皮肤自动重算的 naive 主题覆盖。
 *
 * 两个 `void` 是刻意的依赖声明（读了才建立响应式依赖），时序理由见 `composables/cssVar.ts`：
 * store 先改 ref、再改 DOM（`.dark` class / `data-theme` 属性），computed 到下一次渲染才求值。
 */
export function useNaiveThemeOverrides(): ComputedRef<GlobalThemeOverrides> {
  const appStore = useAppStore();
  return computed<GlobalThemeOverrides>(() => {
    void appStore.darkMode;
    void appStore.themeId;
    const primary = ramp(cssVar('--accent-color', '#18a058'));
    const success = ramp(cssVar('--success', '#18a058'));
    const warning = ramp(cssVar('--warning', '#f0a020'));
    const error = ramp(cssVar('--error', '#d03050'));
    const info = ramp(cssVar('--info', '#2080f0'));
    return {
      common: {
        primaryColor: primary.color,
        primaryColorHover: primary.hover,
        primaryColorPressed: primary.pressed,
        primaryColorSuppl: primary.suppl,

        successColor: success.color,
        successColorHover: success.hover,
        successColorPressed: success.pressed,
        successColorSuppl: success.suppl,

        warningColor: warning.color,
        warningColorHover: warning.hover,
        warningColorPressed: warning.pressed,
        warningColorSuppl: warning.suppl,

        errorColor: error.color,
        errorColorHover: error.hover,
        errorColorPressed: error.pressed,
        errorColorSuppl: error.suppl,

        infoColor: info.color,
        infoColorHover: info.hover,
        infoColorPressed: info.pressed,
        infoColorSuppl: info.suppl,
      },
    };
  });
}
