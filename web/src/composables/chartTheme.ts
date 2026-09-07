/**
 * 图表颜色与断档处理的公共实现。
 *
 * 为什么需要它：ECharts 用 CanvasRenderer 渲染，颜色最终是赋给 `ctx.fillStyle`
 * / `ctx.strokeStyle` 的。`var(--card-bg, #fff)` 这种 CSS 变量字符串**不是合法的
 * canvas 颜色**，赋值会被浏览器静默忽略，画出来是上一次的 fillStyle 或 canvas
 * 默认黑色 —— 这就是「图表里莫名出现灰色」的根因（Dashboard 和 Monitor 两页
 * 原来都把 var(...) 直接塞进 option）。tooltip 是 DOM 渲染所以不受影响，但
 * 为了口径一致也统一走这里解析。
 *
 * 顺带修掉：`--popover-color` 从未在 main.css 里定义过，原来 tooltip 一直吃
 * fallback 白底，暗色模式下白底白字。这里映射到 --card-bg。
 */
import { computed } from 'vue';
import type { ComputedRef } from 'vue';
import { useAppStore } from '@/stores/app';

export interface ChartColors {
  primary: string;
  success: string;
  warning: string;
  error: string;
  purple: string;
  textPrimary: string;
  textSecondary: string;
  textMuted: string;
  border: string;
  cardBg: string;
  popoverBg: string;
}

/** 把 CSS 变量解析成 canvas 能用的具体色值 */
function cssVar(name: string, fallback: string): string {
  if (typeof window === 'undefined') return fallback;
  const v = getComputedStyle(document.documentElement).getPropertyValue(name).trim();
  return v || fallback;
}

/**
 * 解析后的图表配色，随暗色模式切换自动重算。
 *
 * 依赖 `appStore.darkMode`：store 的 toggleDarkMode 先改 ref 再切
 * documentElement 的 .dark class，computed 到下一次渲染才求值，
 * 那时 class 已经生效，getComputedStyle 读到的是新主题的值。
 */
export function useChartColors(): ComputedRef<ChartColors> {
  const appStore = useAppStore();
  return computed<ChartColors>(() => {
    void appStore.darkMode;
    return {
      primary: cssVar('--accent-color', '#2080f0'),
      success: cssVar('--success', '#18a058'),
      warning: cssVar('--warning', '#f0a020'),
      error: cssVar('--error', '#d03050'),
      // 第四条线用的紫色没有对应 CSS 变量，保持字面量
      purple: '#8a5cf6',
      textPrimary: cssVar('--text-primary', '#1a1a1a'),
      textSecondary: cssVar('--text-secondary', '#666666'),
      textMuted: cssVar('--text-muted', '#999999'),
      border: cssVar('--border-subtle', '#e8e8e8'),
      cardBg: cssVar('--card-bg', '#ffffff'),
      popoverBg: cssVar('--card-bg', '#ffffff'),
    };
  });
}

/** 判定断档的倍数阈值：间隔超过「中位间隔 × 该值」就认为中间停止过采集 */
const GAP_FACTOR = 3;
/** 断档判定的绝对下限，避免采样很密时因为正常抖动被切成一段一段 */
const GAP_FLOOR_MS = 60_000;

/**
 * 在采样断档处插入 `null`，让折线（以及 areaStyle 填充）真正断开。
 *
 * 背景：core 停止采集期间数据库里根本没有行，接口也不会补点，于是
 * `type: 'time'` 轴上断档前后两个真实点会被 `smooth: true` 连成一条
 * 横跨整个空白区间的曲线，看起来像「这段时间数据在线性变化」。
 * ECharts 默认 `connectNulls: false`，插一个 null 点就会断开。
 *
 * 阈值取「中位间隔 × GAP_FACTOR」而不是固定值：Monitor 的点距随
 * hours 与后端降采样的桶宽变化（24h/360 点约 4 分钟一个点），写死会误判。
 *
 * @param minGapMs 绝对下限，默认 60s
 */
export function withGapBreaks<T>(
  points: T[],
  getT: (p: T) => number,
  getV: (p: T) => number,
  minGapMs: number = GAP_FLOOR_MS
): Array<[number, number | null]> {
  if (points.length < 2) return points.map((p) => [getT(p), getV(p)] as [number, number | null]);

  const ts = points.map(getT);
  const vs = points.map(getV);

  const deltas: number[] = [];
  for (let i = 1; i < ts.length; i++) deltas.push(ts[i]! - ts[i - 1]!);
  const sorted = [...deltas].sort((a, b) => a - b);
  const median = sorted[Math.floor(sorted.length / 2)] || 0;
  const limit = Math.max(median * GAP_FACTOR, minGapMs);

  const out: Array<[number, number | null]> = [];
  for (let i = 0; i < ts.length; i++) {
    const t = ts[i]!;
    if (i > 0) {
      const prevT = ts[i - 1]!;
      const gap = t - prevT;
      // null 点放在断档中间：既断线，也让 areaStyle 不去填这段空白
      if (gap > limit) out.push([prevT + Math.floor(gap / 2), null]);
    }
    out.push([t, vs[i]!]);
  }
  return out;
}
