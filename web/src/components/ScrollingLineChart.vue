<!--
  匀速滚动折线图（canvas + rAF）。

  为什么不用 ECharts：
  ECharts 的运动只能靠 `setOption` 之间的补间，而"滚动"要求每一帧都在动。
  之前用 rAF 每 33ms 推一次 X 轴窗口再 setOption，等于让两套机制各管一半 ——
  轴自己走、数据点等下一次 setOption 才跳，两条时间线永远对不齐，观感就是别扭。

  这里的做法只有一条规则：**每个点的 x 由它自己的时间戳直接算出来**
      x = 右边缘 − (now − point.t) × 每毫秒像素数
  now 每帧取一次，所以整条曲线天然匀速左移，没有任何补间参与，也就没有"两套运动打架"。

  ── 动效模型（2026-09-09 重做）──

  模型是**纸动、笔不动**：窗口匀速左移（纸），笔尖钉在绘图区右边框上，线从笔尖底下流出来。

  1. **时间原点 = `帧时间 − 当前节奏`**（`refNow` / `cadenceMs`）。于是"上一个采样点"
     恰好落在右边框上，最新采样点落在框外。
  2. **笔尖线性推进**：最新点的显示位置/显示值从前一个点线性插值到自己的真实值，
     一个节奏周期走完。走的距离恰好等于同期窗口左移的距离，两者抵消 →
     笔尖屏幕横坐标恒等于绘图区右边框。
     **不能用缓动曲线**：纸是匀速的，笔一 ease 就会先慢后快再慢，每来一帧抖一下。
  3. **左端溶入**：越过左边界的部分不是被硬切，而是在 TAIL_FADE_PX 内渐隐。

  **节奏是从数据里量出来的，不是写死的**（`cadenceMs` = 最近几个采样间隔的中位数）。
  这一点是上一版"笔不固定"的根因：那版用的是 `sampleMs` 这个**标称**值，
  而 WS 推送的真实间隔随网络抖动（时间戳还是收包时打的）。一旦真实间隔 ≠ 标称值，
  笔尖就会偏出边框 (真实间隔 − 标称值) × 每毫秒像素数，并且每来一帧回跳一次。
  改成量测中位数后，无论 core 推 1s 还是 10s、抖不抖，笔尖都自动贴在边框上；
  取中位数而不是最近一个间隔，是为了让单个迟到/提前的包带不偏笔尖。
  `sampleMs` 退化成"还没攒够两个点时的兜底值"。

  笔尖圆点**画在裁剪框外**（合成完离屏层之后单独画）：它就压在右边框上，
  裁进去会被切掉一半。

  ── 让它"活"起来的四处（2026-09-09）──

  匀速 + 线性保证了不抖，但只有匀速会显得平。下面四处只叠在**表现层**，
  既不改数据，也不破坏"笔尖钉在右边框"这条不变量：

  1. **纵向回弹**：数值用 easeOutBack 落位（过冲约 3%，只发生在最后一段、几百毫秒内，
     落定后形状与数据完全一致）。横向依旧严格线性 —— 一动横向就钉不住边框。
  2. **笔尖心跳**：新样本到达时圆点鼓一下、辉光变强，并推出一圈涟漪，PULSE_MS 内淡掉。
     这是"来了新数据"的唯一即时反馈，比曲线本身的位移好读得多。
  3. **入场**：挂载（切页签会重建组件，所以也包括切页签）时曲线层淡入 + 上浮
     INTRO_RISE_PX。只作用在曲线与笔尖上，网格和轴标签不动 ——
     让"数据"进场，而不是整块画布抖一下。
  4. **时间标签淡入淡出**：原来贴边就直接不画，于是每隔一个 step 就"啪"一下出现/消失；
     现在按到边缘的距离淡出，读起来是"漂走"。

  四处都用「当前时间 − 某个时刻」直接算，没有任何补间状态机：切页签、切后台、
  父组件整体替换数组，都不会留下半截动画。

  它需要那些是因为它不裁剪、删点会看见跳变；我们本来就 clip 在绘图区内，
  把切口做成渐变就够了（曲线画在离屏层上再整体合成，才不会把网格线一起擦掉）。


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
    /**
     * 采样间隔的**兜底值**（ms）。真实节奏由 `cadenceMs` 从数据里量出来（最近几个间隔的
     * 中位数），只有点数不足两个、量不出间隔时才用这个值。给个数量级正确的就行。
     */
    sampleMs?: number;
    leftRange: [number, number];
    rightRange?: [number, number];
    leftFormatter?: (v: number) => string;
    rightFormatter?: (v: number) => string;
    /** 水平网格线条数 */
    gridLines?: number;
    legend?: boolean;
    minHeight?: number;
    /**
     * 时间轴步长（ms）。不传则按 windowMs/4 自动从 TIME_STEPS 挑。
     * 短窗口（如测速 30s）传 5000，避免 10s 步长把标签挤成两三枚。
     */
    timeStepMs?: number;
    /** 冻结时间原点：测速完成时曲线停住，不再继续左移 */
    paused?: boolean;
  }>(),
  {
    sampleMs: 1000,
    gridLines: 4,
    legend: true,
    minHeight: 180,
    timeStepMs: 0,
    paused: false,
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

// ── 动效参数（见文件头「动效模型」）──
/** 左端渐隐宽度（CSS 像素） */
const TAIL_FADE_PX = 26;
/** 量测节奏时回看的间隔个数（取中位数，单个迟到/提前的包带不偏笔尖） */
const CADENCE_SAMPLES = 5;
/** 心跳时长：新样本到达时笔尖亮一下、并推出一圈涟漪，在这段时间内淡掉 */
const PULSE_MS = 560;
/** 笔尖辉光的最大模糊半径（CSS 像素） */
const TIP_GLOW_PX = 12;
/** 入场时长：挂载/切页签时曲线淡入并上浮 */
const INTRO_MS = 520;
/** 入场上浮距离（CSS 像素） */
const INTRO_RISE_PX = 10;
/**
 * 纵向落点的回弹系数（easeOutBack 的 s）。1.2 对应约 3% 过冲。
 * **只用在 y 上**：横向必须严格线性，否则笔尖就钉不住边框（见文件头）。
 */
const SPRING_BACK = 1.2;

function clamp01(v: number): number {
  return v < 0 ? 0 : v > 1 ? 1 : v;
}

/** 缓出：入场与标签淡入用 */
function easeOutCubic(u: number): number {
  const p = 1 - u;
  return 1 - p * p * p;
}

/** 缓出 + 轻微过冲：让数值"落"到位而不是平推到位 */
function easeOutBack(u: number): number {
  const c3 = SPRING_BACK + 1;
  const p = u - 1;
  return 1 + c3 * p * p * p + SPRING_BACK * p * p;
}

/**
 * 当前节奏：最近 CADENCE_SAMPLES 个采样间隔的中位数。
 *
 * 从数据里**量**而不是让调用方**报**，是因为 WS 的真实推送间隔随网络抖动，
 * 时间戳还是收包时打的；用标称值算笔尖位置，真实间隔一偏笔尖就偏出边框（上一版的病根）。
 *
 * 取点数最多的那条线来量：同一张图里的几条线来自同一个 WS 包，节奏相同。
 * 真有节奏不同的线混在一张图里时，只有与本值一致的那条笔尖会严格贴边框。
 */
const cadenceMs = computed(() => {
  let ref: ScrollingPoint[] = [];
  for (const s of props.series) {
    if (s.points.length > ref.length) ref = s.points;
  }
  const gaps: number[] = [];
  for (let i = ref.length - 1; i > 0 && gaps.length < CADENCE_SAMPLES; i--) {
    gaps.push(ref[i]!.t - ref[i - 1]!.t);
  }
  if (!gaps.length) return props.sampleMs;
  gaps.sort((a, b) => a - b);
  const mid = gaps[gaps.length >> 1]!;
  // 断线重连会留下一个特别大的间隔；中位数已经能挡掉单个异常值，这里再兜一层上下限
  return Math.min(Math.max(mid, 100), props.windowMs / 3);
});

/**
 * 绘制用的时间原点：`帧时间 − 当前节奏`，也就是「上一次采样的时刻」。
 * 于是上一个采样点恰好落在右边框上，最新采样点落在框外，笔尖在两者之间线性推进。
 */
const refNow = computed(() => {
  if (props.paused) {
    // 冻结：原点钉在最新样本，笔尖与曲线不再左移
    let lastT = frameNow.value;
    for (const s of props.series) {
      const pts = s.points;
      if (pts.length) lastT = Math.max(lastT, pts[pts.length - 1]!.t);
    }
    return lastT;
  }
  return frameNow.value - cadenceMs.value;
});

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
const TIME_STEPS = [1_000, 2_000, 3_000, 5_000, 8_000, 10_000, 15_000, 30_000, 60_000, 120_000, 300_000, 600_000];
const timeStep = computed(() => {
  if (props.timeStepMs > 0) return props.timeStepMs;
  const target = props.windowMs / 5;
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
/** 本帧各条线的笔尖。drawSeries 攒、drawTips 画（见 drawTips 注释）。pulse: 1=刚到样本，0=心跳结束 */
const tips: { x: number; y: number; color: string; pulse: number }[] = [];

/** 挂载时刻。切页签会重建组件，于是入场动画自然重播 */
let mountedAt = 0;

function draw() {
  const canvas = canvasRef.value;
  const ctx = canvas?.getContext('2d');
  if (!canvas || !ctx || !plotW.value || !plotH.value) return;

  const now = refNow.value;
  const c = colors.value;
  ctx.clearRect(0, 0, cssW.value, cssH.value);
  tips.length = 0;

  drawGrid(ctx, c.border, c.textMuted);
  drawTimeLabels(ctx, now, c.textMuted);

  // 入场：曲线淡入 + 从下方上浮。只作用在曲线层与笔尖上，网格和轴标签不动 ——
  // 让"数据"进场，而不是整块画布抖一下。
  const intro = easeOutCubic(clamp01((frameNow.value - mountedAt) / INTRO_MS));
  const rise = (1 - intro) * INTRO_RISE_PX;

  // 曲线画在离屏层上再整体贴回来：左端渐隐用的是 destination-out，
  // 直接在主画布上擦会把下面的网格线一起擦掉。
  if (layer && layerCtx) {
    layerCtx.clearRect(0, 0, cssW.value, cssH.value);
    paintSeries(layerCtx, now, true);
    ctx.save();
    ctx.globalAlpha = intro;
    ctx.drawImage(layer, 0, rise, cssW.value, cssH.value);
    ctx.restore();
  } else {
    // 拿不到离屏上下文时退回直接画：只丢渐隐，不丢曲线
    paintSeries(ctx, now, false);
  }

  drawTips(ctx, intro, rise);

  if (hoverX.value !== null) {
    ctx.save();
    ctx.beginPath();
    ctx.rect(padLeft.value, PAD_TOP, plotW.value, plotH.value);
    ctx.clip();
    drawCrosshair(ctx, hoverX.value, c.textMuted);
    ctx.restore();
  }
}

/** 曲线裁剪在绘图区内：画布绝不允许画到轴标签上 */
function paintSeries(target: CanvasRenderingContext2D, now: number, withFade: boolean) {
  target.save();
  target.beginPath();
  target.rect(padLeft.value, PAD_TOP, plotW.value, plotH.value);
  target.clip();
  for (const s of props.series) drawSeries(target, s, now);
  if (withFade) fadeTail(target);
  target.restore();
}

/** 左端溶入：把最左边 [TAIL_FADE_PX] 像素按渐变擦掉，代替一刀切的硬边 */
function fadeTail(target: CanvasRenderingContext2D) {
  const x0 = padLeft.value;
  const grad = target.createLinearGradient(x0, 0, x0 + TAIL_FADE_PX, 0);
  grad.addColorStop(0, 'rgba(0, 0, 0, 1)');
  grad.addColorStop(1, 'rgba(0, 0, 0, 0)');
  const prevOp = target.globalCompositeOperation;
  target.globalCompositeOperation = 'destination-out';
  target.fillStyle = grad;
  target.fillRect(x0, PAD_TOP, TAIL_FADE_PX, plotH.value);
  target.globalCompositeOperation = prevOp;
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
  ctx.font = `${axisFont.value} -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif`;
  ctx.textBaseline = 'top';
  ctx.textAlign = 'center';
  const from = now - props.windowMs;
  const left = padLeft.value;
  const right = padLeft.value + plotW.value;
  for (let t = Math.ceil(from / step) * step; t <= now; t += step) {
    const x = xAt(t, now);
    // 贴边的标签原来是直接不画 —— 于是它每隔一个 step 就"啪"地出现/消失一次。
    // 改成按到边缘的距离淡入淡出：标签跟着数据滚出去，看着是"漂走"而不是"消失"。
    const edge = Math.min(x - left, right - x);
    if (edge <= 0) continue;
    ctx.globalAlpha = easeOutCubic(clamp01(edge / 22));
    ctx.fillStyle = textMuted;
    ctx.fillText(formatClock(t), x, PAD_TOP + plotH.value + 6);
  }
  ctx.globalAlpha = 1;
}

function drawSeries(ctx: CanvasRenderingContext2D, s: ScrollingSeries, now: number) {
  const pts = s.points;
  if (pts.length < 2) return;
  const range = rangeOf(s);
  const baseY = PAD_TOP + plotH.value;
  const n = pts.length;

  /*
   * 笔尖：最新点的显示位置与显示值都从前一个点**线性**推进到自己的真实位置/真实值，
   * 一个节奏周期（cadenceMs，量测值）走完全程。
   *
   * 必须是线性、不能用缓动曲线：纸（窗口）是匀速左移的，笔要想在屏幕上待在原地，
   * 就得匀速右移把它抵消掉。任何 ease 都会让笔尖先慢后快再慢，看着就是每来一帧抖一下。
   *
   * 进度用 `(帧时间 − 采样时刻) / cadenceMs` 算，不存"出生时刻"：父组件整体替换 points
   * 数组、WS 推送迟到、页面切回前台都不会让动画错位。
   * 插值走时间维度而不是像素维度，起点会跟着窗口一起左移，笔尖始终沿着最终那条线段走。
   */
  const last = pts[n - 1]!;
  const prev = pts[n - 2]!;
  const growX = clamp01((frameNow.value - last.t) / cadenceMs.value);
  // 纵向用 easeOutBack：数值"落"到位、末尾带一点回弹，比线性平推有生气。
  // 过冲约 3%，只发生在最后一段、几百毫秒内，落定后形状与数据完全一致。
  const growY = easeOutBack(growX);
  const tipT = prev.t + (last.t - prev.t) * growX;
  const tipV = prev.v + (last.v - prev.v) * growY;

  const xs: number[] = new Array(n);
  const ys: number[] = new Array(n);
  for (let i = 0; i < n - 1; i++) {
    xs[i] = xAt(pts[i]!.t, now);
    ys[i] = yAt(pts[i]!.v, range);
  }
  xs[n - 1] = xAt(tipT, now);
  ys[n - 1] = yAt(tipV, range);

  // 面积填充
  if (s.area !== false) {
    ctx.beginPath();
    ctx.moveTo(xs[0]!, baseY);
    for (let i = 0; i < n; i++) ctx.lineTo(xs[i]!, ys[i]!);
    ctx.lineTo(xs[n - 1]!, baseY);
    ctx.closePath();
    const grad = ctx.createLinearGradient(0, PAD_TOP, 0, baseY);
    grad.addColorStop(0, fade(s.color, 0.28));
    grad.addColorStop(1, fade(s.color, 0.02));
    ctx.fillStyle = grad;
    ctx.fill();
  }

  // 线条
  ctx.beginPath();
  for (let i = 0; i < n; i++) {
    if (i === 0) ctx.moveTo(xs[i]!, ys[i]!);
    else ctx.lineTo(xs[i]!, ys[i]!);
  }
  ctx.strokeStyle = s.color;
  ctx.lineWidth = s.width ?? 1.8;
  ctx.lineJoin = 'round';
  // 生长中的那一小段端头要圆，否则每帧都能看见一个方角在往右挪
  ctx.lineCap = 'round';
  ctx.stroke();

  // 末端圆点不在这里画：笔尖压在绘图区右边框上，裁剪框会把它切掉一半。
  // 攒起来交给 drawTips 在合成之后、裁剪之外画。
  // 值超出 Y 轴范围时不画 —— 那种情况圆点会飘到网格/标签上面去。
  const ly = ys[n - 1]!;
  if (ly >= PAD_TOP && ly <= PAD_TOP + plotH.value) {
    // 心跳：新样本刚到时为 1，PULSE_MS 内衰减到 0。平方让它收得快一点，更像一下心跳
    const decay = 1 - clamp01((frameNow.value - last.t) / PULSE_MS);
    tips.push({ x: xs[n - 1]!, y: ly, color: s.color, pulse: decay * decay });
  }
}

/**
 * 笔尖：辉光圆点 + 常驻光环 + 新样本到达时推出去的一圈涟漪。
 *
 * 画在主画布上、且不受绘图区裁剪，因为笔尖就在右边框上 ——
 * 这是唯一的"当前值"视觉指示，宁可让它压出边框一点，也不能被切一半。
 */
function drawTips(target: CanvasRenderingContext2D, intro: number, rise: number) {
  if (!tips.length) return;
  target.save();
  target.globalAlpha = intro;
  for (const tip of tips) {
    const y = tip.y + rise;

    // 涟漪：从光环边缘扩出去并淡掉，心跳结束后完全消失
    if (tip.pulse > 0.02) {
      target.beginPath();
      target.arc(tip.x, y, 6 + 10 * (1 - tip.pulse), 0, Math.PI * 2);
      target.strokeStyle = fade(tip.color, 0.32 * tip.pulse);
      target.lineWidth = 1.5;
      target.stroke();
    }

    // 常驻光环
    target.beginPath();
    target.arc(tip.x, y, 6, 0, Math.PI * 2);
    target.strokeStyle = fade(tip.color, 0.25);
    target.lineWidth = 2;
    target.stroke();

    // 圆点本体：心跳期间带辉光并鼓一下；平时不开 shadowBlur —— 它在移动端不便宜，
    // 而且"只有刚来数据时才发光"本身就是更强的对比。
    if (tip.pulse > 0.02) {
      target.save();
      target.shadowColor = fade(tip.color, 0.6);
      target.shadowBlur = TIP_GLOW_PX * tip.pulse;
      target.beginPath();
      target.arc(tip.x, y, 3.5 + 1.2 * tip.pulse, 0, Math.PI * 2);
      target.fillStyle = tip.color;
      target.fill();
      target.restore();
    } else {
      target.beginPath();
      target.arc(tip.x, y, 3.5, 0, Math.PI * 2);
      target.fillStyle = tip.color;
      target.fill();
    }
  }
  target.restore();
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
  // 与绘制同一个时间原点，否则读数会和光标下的曲线错开一格
  const now = refNow.value;
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
/** 曲线的离屏层（尺寸与主画布一致）。只为了左端渐隐能用 destination-out 而不牵连网格线。 */
let layer: HTMLCanvasElement | null = null;
let layerCtx: CanvasRenderingContext2D | null = null;

/** 离屏层跟着主画布一起按 dpr 放大，并复用同一套 CSS 像素坐标 */
function syncLayer(w: number, h: number, dpr: number) {
  if (!layer) {
    layer = document.createElement('canvas');
    layerCtx = layer.getContext('2d');
  }
  layer.width = Math.round(w * dpr);
  layer.height = Math.round(h * dpr);
  // 改 width/height 会把上下文状态（含变换矩阵）清空，所以每次都要重设
  layerCtx?.setTransform(dpr, 0, 0, dpr, 0, 0);
}

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
  syncLayer(w, h, dpr);
}

function frame() {
  raf = requestAnimationFrame(frame);
  // 后台标签页不画：省掉整条无人看的渲染，回到前台时窗口直接跳到当前时刻
  if (document.hidden) return;
  frameNow.value = Date.now();
  draw();
}

onMounted(() => {
  mountedAt = Date.now();
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
