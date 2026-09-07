<!--
  匀速滚动折线图（canvas + rAF）。

  为什么不用 ECharts：
  ECharts 的运动只能靠 `setOption` 之间的补间，而"滚动"要求每一帧都在动。
  之前用 rAF 每 33ms 推一次 X 轴窗口再 setOption，等于让两套机制各管一半 ——
  轴自己走、数据点等下一次 setOption 才跳，两条时间线永远对不齐，观感就是别扭。

  这里的做法只有一条规则：**每个点的 x 由它自己的时间戳直接算出来**
      x = 右边缘 − (now − point.t) × 每毫秒像素数
  now 每帧取一次，所以整条曲线天然匀速左移，没有任何补间参与，也就没有"两套运动打架"。
  新点到达时就出现在右边缘附近，不需要入场位移。

  组件只负责画，不碰任何接口：数据、Y 轴范围、颜色全由父组件按 props 传进来。
-->
<template>
  <div class="slc" :style="{ minHeight: `${minHeight}px` }">
    <div v-if="legend" class="slc-legend">
      <span v-for="s in series" :key="s.id" class="slc-legend-item">
        <i class="slc-dot" :style="{ background: s.color }"></i>{{ s.name }}
      </span>
    </div>
    <div ref="plotRef" class="slc-plot">
      <canvas ref="canvasRef" @mousemove="onPointerMove" @mouseleave="hoverX = null"></canvas>
      <div v-if="tooltip" class="slc-tip" :style="tooltip.style">
        <div class="slc-tip-time">{{ tooltip.time }}</div>
        <div v-for="r in tooltip.rows" :key="r.name" class="slc-tip-row">
          <i class="slc-dot" :style="{ background: r.color }"></i>
          <span class="slc-tip-name">{{ r.name }}</span>
          <b class="slc-tip-val">{{ r.text }}</b>
        </div>
      </div>
    </div>
  </div>
</template>

<!-- 类型放普通 <script> 块：<script setup> 里不允许出现 ES 导出语句，
     而调用方（NetDetailChartCard）需要 import 这两个类型来标注自己的 series 计算属性。 -->
<script lang="ts">
export interface ScrollingPoint {
  /** 采样时刻（Date.now()） */
  t: number;
  v: number;
}

export interface ScrollingSeries {
  id: string;
  name: string;
  color: string;
  points: ScrollingPoint[];
  /** 走左轴还是右轴（量级差很远的量要分轴，如 dBm 与 dB）。默认左轴。 */
  axis?: 'left' | 'right';
  /** 是否画面积渐变（线多的图关掉，否则糊成一团） */
  area?: boolean;
  /** 线宽，默认 1.8 */
  width?: number;
}
</script>

<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted, watch } from 'vue';
import { useChartColors } from '@/composables/chartTheme';

const props = withDefaults(
  defineProps<{
    series: ScrollingSeries[];
    /** 可见时间跨度：窗口宽度固定，右边缘永远是"现在" */
    windowMs: number;
    leftRange: [number, number];
    rightRange?: [number, number];
    leftFormatter?: (v: number) => string;
    rightFormatter?: (v: number) => string;
    /** 水平网格线条数 */
    gridLines?: number;
    legend?: boolean;
    minHeight?: number;
  }>(),
  {
    gridLines: 4,
    legend: true,
    minHeight: 180,
  }
);

const colors = useChartColors();
const plotRef = ref<HTMLDivElement | null>(null);
const canvasRef = ref<HTMLCanvasElement | null>(null);

/** 鼠标在绘图区内的 x（CSS 像素），null = 未悬浮 */
const hoverX = ref<number | null>(null);

// ── 尺寸（CSS 像素）。canvas 的位图尺寸按 devicePixelRatio 放大，否则线条发虚 ──
const cssW = ref(0);
const cssH = ref(0);

const hasRight = computed(() => !!props.rightRange && props.series.some((s) => s.axis === 'right'));

/** 绘图区内边距：左右留给轴标签，底部留给时间标签 */
const PAD_TOP = 10;
/**
 * 窄屏（手机竖屏约 360px，卡片内可用宽度只剩 ~300px）时，
 * 52 + 46 的轴标签槽会吃掉整幅画面的三分之一，曲线被压成一条窄带。
 * 所以槽宽随可用宽度收窄，标签字号也跟着降一档。
 */
const isNarrow = computed(() => cssW.value > 0 && cssW.value < 420);
const padLeft = computed(() => (isNarrow.value ? 38 : 52));
const padRight = computed(() => (hasRight.value ? (isNarrow.value ? 32 : 46) : 12));
const padBottom = computed(() => (isNarrow.value ? 16 : 20));
const axisFont = computed(() => (isNarrow.value ? '9px' : '10px'));

const plotW = computed(() => Math.max(0, cssW.value - padLeft.value - padRight.value));
const plotH = computed(() => Math.max(0, cssH.value - PAD_TOP - padBottom.value));

/** 当前帧的时间原点。每帧更新一次，曲线的匀速左移完全由它驱动。 */
const frameNow = ref(Date.now());

function xAt(t: number, now: number): number {
  return padLeft.value + plotW.value - ((now - t) * plotW.value) / props.windowMs;
}

function yAt(v: number, range: [number, number]): number {
  const [lo, hi] = range;
  const span = hi - lo || 1;
  const ratio = (v - lo) / span;
  return PAD_TOP + plotH.value - ratio * plotH.value;
}

function rangeOf(s: ScrollingSeries): [number, number] {
  return s.axis === 'right' && props.rightRange ? props.rightRange : props.leftRange;
}

/** 把 #rrggbb 转成半透明；非该格式（暗色主题若写成 rgb()）原样返回 */
function fade(color: string, alpha: number): string {
  const hex = /^#([\da-f]{6})$/i.exec(color.trim())?.[1];
  if (!hex) return color;
  const n = parseInt(hex, 16);
  return `rgba(${(n >> 16) & 255}, ${(n >> 8) & 255}, ${n & 255}, ${alpha})`;
}

/**
 * 时间标签的"整齐步长"。标签画在整数时刻上（而不是固定的 x 位置），
 * 于是它们跟着数据一起往左滚，数字本身不会每帧乱跳。
 */
const TIME_STEPS = [5_000, 10_000, 15_000, 30_000, 60_000, 120_000, 300_000, 600_000];
const timeStep = computed(() => {
  const target = props.windowMs / 4;
  return TIME_STEPS.find((s) => s >= target) ?? TIME_STEPS[TIME_STEPS.length - 1]!;
});

function formatClock(t: number): string {
  const d = new Date(t);
  const mm = String(d.getMinutes()).padStart(2, '0');
  const ss = String(d.getSeconds()).padStart(2, '0');
  // 窗口短（≤2min）时秒才是重点，省掉小时更省地方
  if (props.windowMs <= 120_000) return `${mm}:${ss}`;
  return `${String(d.getHours()).padStart(2, '0')}:${mm}`;
}

function fmtLeft(v: number): string {
  return props.leftFormatter ? props.leftFormatter(v) : String(Math.round(v));
}
function fmtRight(v: number): string {
  return props.rightFormatter ? props.rightFormatter(v) : String(Math.round(v));
}

// ── 绘制 ──
function draw() {
  const canvas = canvasRef.value;
  const ctx = canvas?.getContext('2d');
  if (!canvas || !ctx || !plotW.value || !plotH.value) return;

  const now = frameNow.value;
  const c = colors.value;
  ctx.clearRect(0, 0, cssW.value, cssH.value);

  drawGrid(ctx, c.border, c.textMuted);
  drawTimeLabels(ctx, now, c.textMuted);

  // 曲线裁剪在绘图区内：画布绝不允许画到轴标签上
  ctx.save();
  ctx.beginPath();
  ctx.rect(padLeft.value, PAD_TOP, plotW.value, plotH.value);
  ctx.clip();
  for (const s of props.series) drawSeries(ctx, s, now);
  if (hoverX.value !== null) drawCrosshair(ctx, hoverX.value, c.textMuted);
  ctx.restore();
}

function drawGrid(ctx: CanvasRenderingContext2D, border: string, textMuted: string) {
  const n = Math.max(1, props.gridLines);
  ctx.strokeStyle = fade(border, 0.7);
  ctx.lineWidth = 1;
  ctx.font = `${axisFont.value} -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif`;
  ctx.fillStyle = textMuted;

  for (let i = 0; i <= n; i++) {
    // +0.5 对齐到物理像素中心，否则 1px 线会被抹成 2px 灰边
    const y = Math.round(PAD_TOP + (plotH.value / n) * i) + 0.5;
    ctx.beginPath();
    ctx.moveTo(padLeft.value, y);
    ctx.lineTo(padLeft.value + plotW.value, y);
    ctx.stroke();

    const ratio = 1 - i / n;
    ctx.textBaseline = 'middle';
    ctx.textAlign = 'right';
    const [lo, hi] = props.leftRange;
    ctx.fillText(fmtLeft(lo + (hi - lo) * ratio), padLeft.value - 6, y);

    if (hasRight.value && props.rightRange) {
      ctx.textAlign = 'left';
      const [rlo, rhi] = props.rightRange;
      ctx.fillText(fmtRight(rlo + (rhi - rlo) * ratio), padLeft.value + plotW.value + 6, y);
    }
  }
}

function drawTimeLabels(ctx: CanvasRenderingContext2D, now: number, textMuted: string) {
  const step = timeStep.value;
  ctx.fillStyle = textMuted;
  ctx.font = `${axisFont.value} -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif`;
  ctx.textBaseline = 'top';
  ctx.textAlign = 'center';
  const from = now - props.windowMs;
  for (let t = Math.ceil(from / step) * step; t <= now; t += step) {
    const x = xAt(t, now);
    // 贴边的标签会被裁掉一半，直接不画
    if (x < padLeft.value + 16 || x > padLeft.value + plotW.value - 16) continue;
    ctx.fillText(formatClock(t), x, PAD_TOP + plotH.value + 6);
  }
}

function drawSeries(ctx: CanvasRenderingContext2D, s: ScrollingSeries, now: number) {
  const pts = s.points;
  if (pts.length < 2) return;
  const range = rangeOf(s);
  const baseY = PAD_TOP + plotH.value;

  // 面积填充
  if (s.area !== false) {
    ctx.beginPath();
    ctx.moveTo(xAt(pts[0]!.t, now), baseY);
    for (const p of pts) ctx.lineTo(xAt(p.t, now), yAt(p.v, range));
    ctx.lineTo(xAt(pts[pts.length - 1]!.t, now), baseY);
    ctx.closePath();
    const grad = ctx.createLinearGradient(0, PAD_TOP, 0, baseY);
    grad.addColorStop(0, fade(s.color, 0.28));
    grad.addColorStop(1, fade(s.color, 0.02));
    ctx.fillStyle = grad;
    ctx.fill();
  }

  // 线条
  ctx.beginPath();
  pts.forEach((p, i) => {
    const x = xAt(p.t, now);
    const y = yAt(p.v, range);
    if (i === 0) ctx.moveTo(x, y);
    else ctx.lineTo(x, y);
  });
  ctx.strokeStyle = s.color;
  ctx.lineWidth = s.width ?? 1.8;
  ctx.lineJoin = 'round';
  ctx.stroke();

  // 末端圆点 + 光环：唯一的"当前值"指示，跟着曲线一起匀速左移
  const last = pts[pts.length - 1]!;
  const lx = xAt(last.t, now);
  const ly = yAt(last.v, range);
  ctx.beginPath();
  ctx.arc(lx, ly, 3.5, 0, Math.PI * 2);
  ctx.fillStyle = s.color;
  ctx.fill();
  ctx.beginPath();
  ctx.arc(lx, ly, 6, 0, Math.PI * 2);
  ctx.strokeStyle = fade(s.color, 0.25);
  ctx.lineWidth = 2;
  ctx.stroke();
}

function drawCrosshair(ctx: CanvasRenderingContext2D, x: number, textMuted: string) {
  ctx.beginPath();
  ctx.setLineDash([3, 3]);
  ctx.moveTo(Math.round(x) + 0.5, PAD_TOP);
  ctx.lineTo(Math.round(x) + 0.5, PAD_TOP + plotH.value);
  ctx.strokeStyle = fade(textMuted, 0.6);
  ctx.lineWidth = 1;
  ctx.stroke();
  ctx.setLineDash([]);
}

// ── 悬浮读数 ──
function onPointerMove(e: MouseEvent) {
  const rect = (e.currentTarget as HTMLCanvasElement).getBoundingClientRect();
  const x = e.clientX - rect.left;
  hoverX.value = x >= padLeft.value && x <= padLeft.value + plotW.value ? x : null;
}

/** 悬浮位置对应的时刻，以及每条线在该时刻最近的一个采样点 */
const tooltip = computed(() => {
  const x = hoverX.value;
  if (x === null || !plotW.value) return null;
  const now = frameNow.value;
  const t = now - ((padLeft.value + plotW.value - x) * props.windowMs) / plotW.value;

  const rows = props.series
    .map((s) => {
      let best: ScrollingPoint | null = null;
      let bestDist = Infinity;
      for (const p of s.points) {
        const d = Math.abs(p.t - t);
        if (d < bestDist) {
          bestDist = d;
          best = p;
        }
      }
      if (!best) return null;
      const f = s.axis === 'right' ? fmtRight : fmtLeft;
      return { name: s.name, color: s.color, text: f(best.v) };
    })
    .filter((r): r is { name: string; color: string; text: string } => r !== null);
  if (!rows.length) return null;

  // 靠右悬浮时把气泡翻到左边，免得被卡片边缘裁掉
  const flip = x > padLeft.value + plotW.value * 0.6;
  return {
    time: formatClock(t),
    rows,
    style: {
      left: `${x}px`,
      transform: flip ? 'translate(calc(-100% - 10px), 0)' : 'translate(10px, 0)',
    },
  };
});

// ── 帧循环与尺寸同步 ──
let raf = 0;
let observer: ResizeObserver | null = null;

function syncSize() {
  const host = plotRef.value;
  const canvas = canvasRef.value;
  if (!host || !canvas) return;
  const w = host.clientWidth;
  const h = host.clientHeight;
  if (!w || !h) return;
  cssW.value = w;
  cssH.value = h;
  const dpr = window.devicePixelRatio || 1;
  canvas.width = Math.round(w * dpr);
  canvas.height = Math.round(h * dpr);
  canvas.style.width = `${w}px`;
  canvas.style.height = `${h}px`;
  const ctx = canvas.getContext('2d');
  // setTransform 而不是 scale：每次尺寸变化都从单位矩阵重设，不会叠加缩放
  ctx?.setTransform(dpr, 0, 0, dpr, 0, 0);
}

function frame() {
  raf = requestAnimationFrame(frame);
  // 后台标签页不画：省掉整条无人看的渲染，回到前台时窗口直接跳到当前时刻
  if (document.hidden) return;
  frameNow.value = Date.now();
  draw();
}

onMounted(() => {
  syncSize();
  if (typeof ResizeObserver !== 'undefined' && plotRef.value) {
    observer = new ResizeObserver(() => syncSize());
    observer.observe(plotRef.value);
  }
  raf = requestAnimationFrame(frame);
});

onUnmounted(() => {
  cancelAnimationFrame(raf);
  observer?.disconnect();
});

// 暗色模式切换后网格/标签颜色要立刻跟上，不必等下一帧数据
watch(colors, () => draw());
</script>

<style scoped>
.slc {
  display: flex;
  flex-direction: column;
  min-height: 0;
  flex: 1;
}
.slc-legend {
  display: flex;
  flex-wrap: wrap;
  gap: 4px 16px;
  margin-bottom: 6px;
  font-size: 12px;
  color: var(--text-secondary);
}
.slc-legend-item {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}
.slc-dot {
  width: 8px;
  height: 8px;
  border-radius: var(--radius-pill);
  flex: none;
}
.slc-plot {
  position: relative;
  flex: 1;
  min-height: 0;
  width: 100%;
}
.slc-plot canvas {
  display: block;
}
/* 悬浮读数：跟着 crosshair 走，pointer-events 关掉否则会自己抢走 mousemove */
.slc-tip {
  position: absolute;
  top: 8px;
  pointer-events: none;
  background: var(--card-bg);
  border: 1px solid var(--border-subtle);
  border-radius: var(--radius-sm);
  padding: 6px 8px;
  font-size: 12px;
  color: var(--text-primary);
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.08);
  white-space: nowrap;
}
.slc-tip-time {
  color: var(--text-muted);
  margin-bottom: 2px;
  font-variant-numeric: tabular-nums;
}
.slc-tip-row {
  display: flex;
  align-items: center;
  gap: 6px;
}
.slc-tip-name {
  color: var(--text-secondary);
}
.slc-tip-val {
  margin-left: auto;
  font-variant-numeric: tabular-nums;
}
</style>
