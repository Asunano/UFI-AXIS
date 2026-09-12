<template>
  <n-modal
    :show="show"
    preset="card"
    title="网速测试"
    style="width: min(720px, calc(100vw - 24px))"
    :content-style="{ maxHeight: '88vh', overflow: 'auto' }"
    @update:show="emit('update:show', $event)"
  >
    <div class="st">
      <!-- 左：表盘 + 方向 + 控制 | 右：曲线 + 统计 -->
      <div class="st-layout">
        <div class="st-left">
          <div class="st-node">{{ nodeLabel }}</div>

          <div class="st-pills" :class="{ visible: showData }">
            <span class="st-pill">PING <b>{{ formatMs(state.latencyMs) }}</b></span>
            <span class="st-pill">JITTER <b>{{ formatMs(state.jitterMs) }}</b></span>
          </div>

          <div class="st-dial-slot">
            <svg class="st-dial" viewBox="0 0 200 200" aria-hidden="true">
              <circle class="dial-track" cx="100" cy="100" r="88" />
              <g class="dial-ticks">
                <line
                  v-for="t in 24"
                  :key="t"
                  :transform="`rotate(${-120 + (t - 1) * (240 / 23)} 100 100)`"
                  x1="100"
                  y1="14"
                  x2="100"
                  :y2="t % 6 === 1 ? 22 : 18"
                />
              </g>
              <circle
                class="dial-progress"
                cx="100"
                cy="100"
                r="88"
                :style="{ strokeDasharray: dialDash }"
              />
              <g class="dial-needle" :transform="`rotate(${-120 + gaugeFraction * 240} 100 100)`">
                <line x1="100" y1="36" x2="100" y2="52" />
                <circle cx="100" cy="36" r="2.5" />
              </g>
            </svg>
            <div class="st-dial-center">
              <template v-if="!isRunning && state.phase !== 'done' && state.phase !== 'error'">
                <button type="button" class="st-go" @click="start">GO</button>
              </template>
              <template v-else-if="isRunning || state.phase === 'done'">
                <div class="st-value">{{ liveMbps }}</div>
                <div class="st-unit-row">
                  <span class="st-unit">Mbps</span>
                  <span class="st-phase">{{ phaseLabel }}</span>
                </div>
              </template>
              <template v-else>
                <button type="button" class="st-go again" @click="start">再来</button>
              </template>
            </div>
          </div>

          <div class="st-dir">
            <div class="st-dir-col" :class="{ on: state.phase === 'download' || state.phase === 'done' }">
              <svg class="st-dir-ico" viewBox="0 0 24 24" aria-hidden="true">
                <path fill="currentColor" d="M12 16l-6-6h4V4h4v6h4l-6 6zm-8 2h16v2H4v-2z" />
              </svg>
              <span class="st-dir-val">{{ fmtMbps(state.avgMbps) }}</span>
              <span class="st-dir-unit">下载 Mbps</span>
            </div>
            <div class="st-dir-sep" />
            <div
              class="st-dir-col"
              :class="{ on: state.phase === 'upload' || (state.phase === 'done' && state.uploadMeasured) }"
            >
              <svg class="st-dir-ico" viewBox="0 0 24 24" aria-hidden="true">
                <path fill="currentColor" d="M12 8l6 6h-4v6h-4v-6H6l6-6zM4 4h16v2H4V4z" />
              </svg>
              <span v-if="uploadNote" class="st-dir-note">{{ uploadNote }}</span>
              <template v-else>
                <span class="st-dir-val">{{ fmtMbps(state.uploadMbps) }}</span>
                <span class="st-dir-unit">上传 Mbps</span>
              </template>
            </div>
          </div>

          <n-progress
            v-if="isRunning"
            type="line"
            :percentage="Math.round(state.progress * 100)"
            :height="3"
            :show-indicator="false"
            class="st-progress"
          />

          <div v-if="state.errorMessage" class="st-error">{{ state.errorMessage }}</div>

          <div class="st-actions">
            <n-button v-if="isRunning" secondary block @click="cancel">取消测速</n-button>
            <n-button
              v-else-if="state.phase === 'done' || state.phase === 'error'"
              type="primary"
              block
              @click="start"
            >
              重新测速
            </n-button>
          </div>
        </div>

        <div class="st-right">
          <!-- 测速节点：常驻右侧 -->
          <div class="st-targets">
            <button
              type="button"
              class="st-target"
              :class="{ active: targetId === 'internal' }"
              :disabled="isRunning"
              @click="selectTarget('internal')"
            >
              <span class="st-t-text">
                <span class="st-t-title">内网</span>
                <span class="st-t-sub">设备直连 · 下行 / 上行</span>
              </span>
              <span class="st-t-dot" />
            </button>
            <button
              v-for="n in EXTERNAL_NODES"
              :key="n.id"
              type="button"
              class="st-target"
              :class="{ active: targetId === n.id }"
              :disabled="isRunning"
              @click="selectTarget(n.id)"
            >
              <span class="st-t-text">
                <span class="st-t-title">{{ n.label }}</span>
                <span class="st-t-sub">连通性参考 · 非真实宽带</span>
              </span>
              <span class="st-t-dot" />
            </button>
          </div>

          <div class="st-right-panel" :class="{ active: showData }">
            <Transition name="st-fade" mode="out-in">
              <div v-if="showData" key="chart" class="st-right-inner">
                <div class="st-chart-title">瞬时速率</div>
                <ScrollingLineChart
                  class="st-chart"
                  :series="chartSeries"
                  :window-ms="CHART_WINDOW_MS"
                  :sample-ms="EMIT_MS"
                  :left-range="chartRange"
                  :left-formatter="formatMbpsAxis"
                  :legend="false"
                  :time-step-ms="5000"
                  :paused="chartPaused"
                />
                <div class="st-stats">
                  <div class="st-stat"><span>用时</span><b>{{ state.elapsedSec.toFixed(1) }}s</b></div>
                  <div class="st-stat"><span>峰值</span><b>{{ fmtMbps(state.peakMbps) }}</b></div>
                  <div class="st-stat"><span>已传</span><b>{{ formatBytes(state.totalBytes) }}</b></div>
                  <div class="st-stat"><span>并发</span><b>{{ state.streams || '—' }}</b></div>
                </div>
              </div>
              <div v-else key="empty" class="st-right-empty" />
            </Transition>
          </div>
        </div>
      </div>
    </div>
  </n-modal>
</template>

<script setup lang="ts">
/**
 * 网速测试 — 对齐 App 方法论与信息层级：
 * · 默认只显示「开始测速 + 底部目标选择」
 * · 点开始后才展开：节点 / PING·JITTER / 读数 / 上下行 / 曲线 / 统计
 * · 上行：时间预算内连发短 POST（浏览器无法像 OkHttp 那样单请求流式写体），
 *   上行失败不影响下行结果。
 */
import { computed, onUnmounted, ref, watch } from 'vue';
import { useMessage } from 'naive-ui';
import { useAppStore } from '@/stores/app';
import { loadDeviceIdentity } from '@/composables/deviceIdentityLazy';
import { useChartColors } from '@/composables/chartTheme';
import ScrollingLineChart from '@/components/ScrollingLineChart.vue';
import type { ScrollingPoint, ScrollingSeries } from '@/components/ScrollingLineChart.vue';

const SPEED_STREAMS = 4;
const MIN_SEC = 5;
const MAX_DL_INTERNAL = 15;
const MAX_DL_EXTERNAL = 25;
const RAMP_UP_MS = 2000;
const STABLE_WINDOW = 8;
const STABLE_TOL = 0.06;
const PING_COUNT = 10;
const PING_MAX_FAILURES = 3;
const PING_BUDGET_MS = 6000;
const MEDIAN_WINDOW = 5;
const UPLOAD_MIN_SEC = 4;
const UPLOAD_MAX_SEC = 8;
const SAMPLE_LIMIT = 120;
const EMIT_MS = 150;
/** 单次上行 POST 体大小：短请求便于在浏览器里按时间收尾，也避开边缘 413 */
const UPLOAD_POST_BYTES = 512 * 1024;

const EXTERNAL_NODES = [
  {
    id: 'edgeone',
    label: 'EdgeOne',
    baseUrl: 'https://speedtestone.losn.cc',
    get downloadUrl() {
      return `${this.baseUrl}/speedtest`;
    },
    get uploadUrl() {
      return `${this.baseUrl}/upload`;
    },
    get host() {
      return this.baseUrl.replace(/^https?:\/\//, '').split('/')[0];
    },
    streams: 4,
  },
] as const;

type Phase = 'idle' | 'connecting' | 'latency' | 'download' | 'upload' | 'done' | 'error';

interface SpeedState {
  phase: Phase;
  testType: string;
  currentMbps: number;
  avgMbps: number;
  peakMbps: number;
  uploadMbps: number;
  uploadMeasured: boolean;
  latencyMs: number | null;
  jitterMs: number | null;
  streams: number;
  totalBytes: number;
  elapsedSec: number;
  progress: number;
  samples: ScrollingPoint[];
  errorMessage: string | null;
}

const props = defineProps<{ show: boolean }>();
const emit = defineEmits<{ 'update:show': [boolean] }>();
const show = computed({
  get: () => props.show,
  set: (v) => emit('update:show', v),
});

const message = useMessage();
const appStore = useAppStore();
const colors = useChartColors();

/** 瞬时速率图：与仪表盘网络详情同一套 ScrollingLineChart */
const CHART_WINDOW_MS = 30_000;

const targetId = ref<'internal' | 'edgeone'>('internal');
const state = ref<SpeedState>({
  phase: 'idle',
  testType: '',
  currentMbps: 0,
  avgMbps: 0,
  peakMbps: 0,
  uploadMbps: 0,
  uploadMeasured: false,
  latencyMs: null,
  jitterMs: null,
  streams: 0,
  totalBytes: 0,
  elapsedSec: 0,
  progress: 0,
  samples: [],
  errorMessage: null,
});

const activeNode = computed(() => EXTERNAL_NODES.find((n) => n.id === targetId.value) || null);
const isRunning = computed(() =>
  ['connecting', 'latency', 'download', 'upload'].includes(state.value.phase)
);
/** 开始测速后才展示网络信息（对齐「点测试后才显示」） */
const showData = computed(() => isRunning.value || state.value.phase === 'done' || state.value.phase === 'error');

const nodeLabel = computed(() =>
  targetId.value === 'internal' ? '内网 · 设备直连' : `外网 · ${activeNode.value?.host ?? ''}`
);

/** 表盘指针 0..1：测速中读瞬时，完成读下行均值；对数刻度避免千兆挤在末端 */
const gaugeFraction = computed(() => {
  if (!showData.value) return 0;
  const mbps =
    state.value.phase === 'download' || state.value.phase === 'upload'
      ? state.value.currentMbps
      : state.value.avgMbps || state.value.currentMbps;
  return mapMbpsToFraction(mbps);
});

/** 240° 弧长：圆周 2πr，按 240/360 折算；track 用 dasharray 画缺口 */
const dialDash = computed(() => {
  const r = 88;
  const c = 2 * Math.PI * r;
  const arc = (240 / 360) * c;
  const filled = arc * gaugeFraction.value;
  return `${filled} ${c}`;
});

function mapMbpsToFraction(mbps: number): number {
  if (!Number.isFinite(mbps) || mbps <= 0) return 0;
  // 与 App gauge 类似：0–1Gbps 对数映射，低端更灵敏
  const logMax = Math.log10(1000);
  const v = Math.min(mbps, 1000);
  return Math.min(1, Math.max(0, Math.log10(Math.max(v, 1)) / logMax));
}

const liveMbps = computed(() => {
  const m = state.value.currentMbps;
  return m >= 100 ? m.toFixed(0) : m >= 10 ? m.toFixed(1) : m.toFixed(2);
});

const phaseLabel = computed(() => {
  switch (state.value.phase) {
    case 'connecting':
      return '连接中';
    case 'latency':
      return '延迟测试';
    case 'download':
      return '下行测速';
    case 'upload':
      return '上行测速';
    case 'done':
      return '完成';
    case 'error':
      return '失败';
    default:
      return '';
  }
});

const uploadNote = computed(() => {
  if (targetId.value === 'internal') return null;
  if (state.value.phase !== 'done') return null;
  return state.value.uploadMeasured ? null : '节点未开上行';
});

function selectTarget(id: string) {
  if (isRunning.value) return;
  targetId.value = id as 'internal' | 'edgeone';
}

function formatMs(ms: number | null): string {
  if (ms == null || !Number.isFinite(ms)) return '--';
  return `${ms.toFixed(ms < 10 ? 1 : 0)} ms`;
}

function fmtMbps(v: number): string {
  if (!Number.isFinite(v) || v <= 0) return '—';
  return v >= 100 ? v.toFixed(0) : v >= 10 ? v.toFixed(1) : v.toFixed(2);
}

function formatBytes(n: number): string {
  if (n <= 0) return '—';
  const mb = n / (1024 * 1024);
  if (mb >= 1024) return `${(mb / 1024).toFixed(2)} GB`;
  if (mb >= 1) return `${mb.toFixed(1)} MB`;
  return `${(n / 1024).toFixed(0)} KB`;
}

function formatMbpsAxis(v: number): string {
  if (v >= 1000) return `${(v / 1000).toFixed(1)}G`;
  if (v >= 100) return `${v.toFixed(0)}`;
  if (v >= 10) return `${v.toFixed(0)}`;
  return `${v.toFixed(1)}`;
}

/** 采样点直接进图：t 必须是采样瞬间的 Date.now()，不能每帧重算 */
const chartSeries = computed<ScrollingSeries[]>(() => {
  const points = state.value.samples;
  if (!points.length) return [];
  return [
    {
      id: 'mbps',
      name: '瞬时速率',
      color: colors.value.info,
      points,
      area: true,
      width: 2,
    },
  ];
});

const chartRange = computed<[number, number]>(() => {
  let max = 1;
  for (const p of state.value.samples) {
    if (p.v > max) max = p.v;
  }
  const top = max <= 10 ? 10 : max <= 100 ? Math.ceil(max / 10) * 10 : Math.ceil(max / 50) * 50;
  return [0, top];
});

/** 测速结束后曲线冻结，避免空窗期继续左移显得“抽搐” */
const chartPaused = computed(() => !isRunning.value);

async function speedHeaders(method: string, uri: string): Promise<Record<string, string>> {
  const signed = await (await loadDeviceIdentity()).signRequest(method, uri);
  return appStore.token ? { Authorization: `Bearer ${appStore.token}`, ...signed } : { ...signed };
}

/**
 * 测速专用请求错误：**绝不 clearAuth / 跳登录**。
 * 并发多流时偶发 401/444 不应把用户踢下线（后台轮询、签名竞态都可能误伤）。
 */
class SpeedHttpError extends Error {
  constructor(
    readonly status: number,
    message: string
  ) {
    super(message);
    this.name = 'SpeedHttpError';
  }
}

async function checkSpeedStatus(res: Response): Promise<void> {
  if (res.status === 401 || res.status === 444) {
    throw new SpeedHttpError(res.status, '鉴权失败（请确认设备时间同步后重试）');
  }
  if (res.status === 429) {
    throw new SpeedHttpError(429, '测速并发过多，请稍后再试');
  }
  if (res.status === 404) {
    throw new SpeedHttpError(404, '外网 relay 需较新 core；请先升级设备端 core');
  }
}

let aborted = false;
let job: Promise<void> | null = null;
const controllers: AbortController[] = [];

function abortControllersOnly() {
  for (const c of controllers.splice(0)) {
    try {
      c.abort();
    } catch {
      /* noop */
    }
  }
}

function cancelAll() {
  aborted = true;
  abortControllersOnly();
}

async function cancel() {
  cancelAll();
  state.value = {
    ...state.value,
    phase: 'idle',
    currentMbps: 0,
    progress: 0,
    errorMessage: '测速已取消',
  };
  const j = job;
  job = null;
  // 等旧协程真正退出，避免和下一次 start 抢 job 槽
  if (j) await j.catch(() => undefined);
}

watch(
  () => props.show,
  (v) => {
    if (!v) cancel();
  }
);

onUnmounted(() => cancelAll());

function sleep(ms: number): Promise<void> {
  return new Promise((r) => setTimeout(r, ms));
}

function median(arr: number[]): number {
  if (!arr.length) return 0;
  const s = [...arr].sort((a, b) => a - b);
  return s[Math.floor(s.length / 2)] ?? 0;
}

function isStable(samples: number[]): boolean {
  if (samples.length < STABLE_WINDOW) return false;
  const win = samples.slice(-STABLE_WINDOW);
  const mean = win.reduce((a, b) => a + b, 0) / STABLE_WINDOW;
  if (mean <= 0.5) return false;
  return win.every((x) => Math.abs(x - mean) / mean <= STABLE_TOL);
}

function sampleValues(points: ScrollingPoint[]): number[] {
  return points.map((p) => p.v);
}

function averageAfterRamp(
  bytes: number,
  rampEndAt: number | null,
  rampBytes: number,
  now: number,
  start: number
): number {
  const postRamp = rampEndAt != null && now > rampEndAt;
  const dBytes = postRamp ? bytes - rampBytes : bytes;
  const dMs = postRamp ? now - rampEndAt! : now - start;
  if (dMs <= 0 || dBytes <= 0) return 0;
  return (dBytes * 8) / (dMs / 1000) / 1_000_000;
}

async function headInternal(): Promise<void> {
  const uri = '/api/speedtest';
  const ac = new AbortController();
  controllers.push(ac);
  const t = setTimeout(() => ac.abort(), 3000);
  try {
    const res = await fetch(`${appStore.baseUrl || ''}${uri}`, {
      method: 'HEAD',
      headers: await speedHeaders('HEAD', uri),
      signal: ac.signal,
    });
    if (res.status === 401 || res.status === 444) {
      throw new SpeedHttpError(res.status, '鉴权失败（请确认设备时间同步后重试）');
    }
  } finally {
    clearTimeout(t);
  }
}

async function probeExternal(url: string): Promise<void> {
  const path = `/api/speedtest/relay?url=${encodeURIComponent(url)}`;
  const ac = new AbortController();
  controllers.push(ac);
  const t = setTimeout(() => ac.abort(), 4000);
  try {
    const res = await fetch(`${appStore.baseUrl || ''}${path}`, {
      method: 'GET',
      headers: { ...(await speedHeaders('GET', path)), Range: 'bytes=0-0' },
      signal: ac.signal,
    });
    await checkSpeedStatus(res);
    if (res.status === 206) {
      const blob = await res.blob();
      if (blob.size > 64) await res.body?.cancel();
    } else if (res.ok) {
      // 上游回 200 时不要读完整虚拟流
      await res.body?.cancel();
    }
  } finally {
    clearTimeout(t);
  }
}

async function measureLatency(downloadUrl: string | null): Promise<{ latency: number; jitter: number } | null> {
  const rtts: number[] = [];
  let fails = 0;
  const deadline = performance.now() + PING_BUDGET_MS;
  for (let i = 0; i < PING_COUNT; i++) {
    if (aborted || performance.now() > deadline) break;
    // 探针串行，取消后立即停，避免把 idle 又写成 latency
    const t0 = performance.now();
    let ok = true;
    try {
      if (downloadUrl) await probeExternal(downloadUrl);
      else await headInternal();
    } catch {
      ok = false;
    }
    if (aborted) break;
    if (ok) {
      fails = 0;
      rtts.push(performance.now() - t0);
    } else if (++fails >= PING_MAX_FAILURES) break;
    state.value = {
      ...state.value,
      phase: 'latency',
      latencyMs: rtts.length ? Math.min(...rtts) : null,
      progress: ((i + 1) / PING_COUNT) * 0.05,
    };
    if (aborted) break;
  }
  if (!rtts.length) return null;
  const latency = median(rtts);
  let diff = 0;
  for (let i = 1; i < rtts.length; i++) {
    diff += Math.abs((rtts[i] ?? 0) - (rtts[i - 1] ?? 0));
  }
  return { latency, jitter: rtts.length > 1 ? diff / (rtts.length - 1) : 0 };
}

async function drainReader(
  reader: ReadableStreamDefaultReader<Uint8Array>,
  counter: { n: number },
  stop: { v: boolean }
) {
  try {
    while (!aborted && !stop.v) {
      const { done, value } = await reader.read();
      if (done) break;
      if (value) counter.n += value.byteLength;
    }
  } catch {
    /* abort / 断开 */
  } finally {
    try {
      await reader.cancel();
    } catch {
      /* noop */
    }
  }
}

async function openInternalStream(chunks: number): Promise<ReadableStreamDefaultReader<Uint8Array>> {
  const uri = `/api/speedtest?ckSize=${chunks}`;
  const ac = new AbortController();
  controllers.push(ac);
  const res = await fetch(`${appStore.baseUrl || ''}${uri}`, {
    headers: await speedHeaders('GET', uri),
    signal: ac.signal,
  });
  await checkSpeedStatus(res);
  if (!res.ok || !res.body) throw new Error(`HTTP ${res.status}`);
  return res.body.getReader();
}

async function openExternalStream(url: string): Promise<ReadableStreamDefaultReader<Uint8Array>> {
  const path = `/api/speedtest/relay?url=${encodeURIComponent(url)}`;
  const ac = new AbortController();
  controllers.push(ac);
  const res = await fetch(`${appStore.baseUrl || ''}${path}`, {
    headers: await speedHeaders('GET', path),
    signal: ac.signal,
    cache: 'no-store',
  });
  await checkSpeedStatus(res);
  if (!res.ok || !res.body) throw new Error(`HTTP ${res.status}`);
  return res.body.getReader();
}

async function runDownloadPhase(
  downloadUrl: string | null,
  streams: number,
  maxSec: number,
  uploadFollows: boolean
): Promise<{ avg: number; peak: number; bytes: number; elapsed: number; samples: ScrollingPoint[]; streams: number }> {
  const counter = { n: 0 };
  const stop = { v: false };
  const chunks = Math.min(4096, Math.max(10, maxSec * 40));
  let live = 0;

  async function runOne(isFirst: boolean, index: number) {
    try {
      // 错开开流：并行签名/建连偶发竞态，间隔 80ms 足够且不影响测速精度
      if (index > 0) await sleep(index * 80);
      const reader = downloadUrl ? await openExternalStream(downloadUrl) : await openInternalStream(chunks);
      live++;
      await drainReader(reader, counter, stop);
      if (downloadUrl && !aborted && !stop.v) {
        while (!aborted && !stop.v) {
          try {
            const r2 = await openExternalStream(downloadUrl);
            await drainReader(r2, counter, stop);
          } catch {
            break;
          }
        }
      }
    } catch (e) {
      if (isFirst) throw e;
    } finally {
      live = Math.max(0, live - 1);
    }
  }

  const jobs: Promise<void>[] = [runOne(true, 0)];
  for (let i = 1; i < streams; i++) jobs.push(runOne(false, i));
  void jobs[0]?.catch(() => undefined);

  const start = performance.now();
  const deadline = start + maxSec * 1000;
  const samples: ScrollingPoint[] = [];
  const smooth: number[] = [];
  let lastBytes = 0;
  let lastTick = start;
  let rampEnd: number | null = null;
  let rampBytes = 0;
  let peak = 0;
  let streamsNow = 1;

  while (!aborted) {
    await sleep(EMIT_MS);
    if (aborted) break;
    const now = performance.now();
    const windowMs = now - lastTick;
    if (windowMs <= 0) continue;
    const instant = ((counter.n - lastBytes) * 8) / (windowMs / 1000) / 1_000_000;
    lastTick = now;
    lastBytes = counter.n;
    if (rampEnd == null && now - start >= RAMP_UP_MS) {
      rampEnd = now;
      rampBytes = counter.n;
    }
    smooth.push(instant);
    if (smooth.length > MEDIAN_WINDOW) smooth.shift();
    const smoothed = median(smooth);
    if (rampEnd != null && smoothed > peak) peak = smoothed;
    if (samples.length >= SAMPLE_LIMIT) samples.shift();
    // t 必须用墙钟：ScrollingLineChart 的 x 轴与 cadence 都基于 Date.now()
    samples.push({ t: Date.now(), v: Number.isFinite(instant) ? instant : 0 });
    const elapsed = (now - start) / 1000;
    const avg = averageAfterRamp(counter.n, rampEnd, rampBytes, now, start);
    streamsNow = Math.max(1, live);
    state.value = {
      ...state.value,
      phase: 'download',
      currentMbps: smoothed,
      avgMbps: avg,
      peakMbps: Math.max(state.value.peakMbps, peak),
      streams: streamsNow,
      totalBytes: counter.n,
      elapsedSec: elapsed,
      progress: uploadFollows ? Math.min(0.7, (elapsed / maxSec) * 0.7) : Math.min(1, elapsed / maxSec),
      samples: [...samples],
    };
    if (now >= deadline) break;
    if (elapsed >= MIN_SEC && rampEnd != null && isStable(sampleValues(samples))) break;
  }

  stop.v = true;
  abortControllersOnly();
  await Promise.allSettled(jobs.map((p) => p.catch(() => undefined)));
  const end = performance.now();
  const avg = averageAfterRamp(counter.n, rampEnd, rampBytes, end, start);
  return {
    avg,
    peak: Math.max(peak, avg),
    bytes: counter.n,
    elapsed: (end - start) / 1000,
    samples: [...samples],
    streams: streamsNow,
  };
}

async function probeUpload(url: string): Promise<boolean> {
  const path = `/api/speedtest/relay?url=${encodeURIComponent(url)}`;
  const ac = new AbortController();
  controllers.push(ac);
  const t = setTimeout(() => ac.abort(), 4000);
  try {
    const res = await fetch(`${appStore.baseUrl || ''}${path}`, {
      method: 'POST',
      headers: { ...(await speedHeaders('POST', path)), 'Content-Type': 'application/octet-stream' },
      body: new Uint8Array([1]),
      signal: ac.signal,
    });
    return res.ok;
  } catch {
    return false;
  } finally {
    clearTimeout(t);
  }
}

/**
 * 上行：时间预算内循环 POST 固定 512KB 块。
 * 浏览器没有 OkHttp 那种「单请求边写边计」的 body，短块 + 连发才能按秒收尾，
 * 也避开平台请求体上限。失败只记日志，不打断整体测速。
 */
/**
 * 上行：时间预算内循环 POST 固定 512KB 块。
 * @param downloadBase 下行已传字节——上传过程中「已传输」= 下行 + 上行，避免停在下行数字上。
 *
 * 字节计数：
 * - 进行中用 `upload.onprogress`（写出口速率）；
 * - 每次 POST 成功后用服务端回报的 `bytes` **对齐该请求**，最终汇总优先用确认值，
 *   避免中途 abort 时 onprogress 把「已写入内核缓冲、对端未收完」的量算进去。
 */
async function runUploadPhase(
  uploadUrl: string | null,
  downloadBase: number
): Promise<{ avg: number; bytes: number; elapsed: number; streams: number } | null> {
  const uri = uploadUrl
    ? `/api/speedtest/relay?url=${encodeURIComponent(uploadUrl)}`
    : '/api/speedtest/upload';
  const url = `${appStore.baseUrl || ''}${uri}`;
  let headers: Record<string, string> = { 'Content-Type': 'application/octet-stream' };
  try {
    headers = { ...(await speedHeaders('POST', uri)), ...headers };
  } catch (e) {
    throw e;
  }

  // crypto.getRandomValues 单次上限 65536 字节：先取一块再重复拼满，内容不影响速率
  const seed = new Uint8Array(65536);
  crypto.getRandomValues(seed);
  const chunk = new Uint8Array(UPLOAD_POST_BYTES);
  for (let o = 0; o < UPLOAD_POST_BYTES; o += seed.byteLength) {
    chunk.set(seed.subarray(0, Math.min(seed.byteLength, UPLOAD_POST_BYTES - o)), o);
  }
  const body = new Blob([chunk], { type: 'application/octet-stream' });

  /** 写出口累计（进度事件），用于瞬时速率 */
  let progressSent = 0;
  /** 服务端已确认收到的累计，最终「已传输 / 均值」优先用它 */
  let confirmedSent = 0;
  const start = performance.now();
  const maxMs = UPLOAD_MAX_SEC * 1000;
  const samples: ScrollingPoint[] = [];
  const smooth: number[] = [];
  let lastBytes = 0;
  let lastTick = start;
  let rampEnd: number | null = null;
  let rampBytes = 0;
  let peak = 0;

  let inflight: Promise<void> | null = null;
  let lastErr: unknown = null;

  /** 瞬时统计用的字节：确认值未跟上前用 progress，避免启动期全 0 */
  const liveSent = () => Math.max(confirmedSent, progressSent);

  function kick() {
    if (inflight || aborted || performance.now() - start >= maxMs) return;
    const ac = new AbortController();
    controllers.push(ac);
    void speedHeaders('POST', uri)
      .then((fresh) => {
        Object.assign(headers, fresh, { 'Content-Type': 'application/octet-stream' });
        return new Promise<void>((resolve) => {
          const xhr = new XMLHttpRequest();
          xhr.open('POST', url);
          Object.entries(headers).forEach(([k, v]) => xhr.setRequestHeader(k, v));
          let lastLoaded = 0;
          xhr.upload.onprogress = (ev) => {
            const d = ev.loaded - lastLoaded;
            if (d > 0) {
              lastLoaded = ev.loaded;
              progressSent += d;
            }
          };
          const done = () => {
            inflight = null;
            resolve();
          };
          xhr.onload = () => {
            if (xhr.status === 401 || xhr.status === 444) {
              lastErr = new SpeedHttpError(xhr.status, '上行鉴权失败（不会退出登录，请稍后重试）');
              done();
              return;
            }
            if (xhr.status >= 200 && xhr.status < 300) {
              let confirmed = lastLoaded;
              try {
                const j = JSON.parse(xhr.responseText);
                if (typeof j?.bytes === 'number' && j.bytes >= 0) confirmed = j.bytes;
              } catch {
                /* 响应异常时退回 progress */
              }
              confirmedSent += confirmed;
            } else if (xhr.status !== 0) {
              lastErr = new Error(`HTTP ${xhr.status}`);
            }
            done();
          };
          xhr.onerror = () => {
            lastErr = new Error('网络错误');
            done();
          };
          xhr.onabort = done;
          ac.signal.addEventListener('abort', () => xhr.abort());
          xhr.send(body);
        });
      })
      .catch((e) => {
        lastErr = e;
        inflight = null;
      });
  }

  kick();
  // 进入上行：清空下行曲线，单独画上行（避免两段时间戳混在一起抖）
  state.value = { ...state.value, samples: [] };
  while (!aborted && performance.now() - start < maxMs) {
    await sleep(EMIT_MS);
    if (aborted) break;
    const now = performance.now();
    const windowMs = now - lastTick;
    if (windowMs > 0) {
      const sent = liveSent();
      const instant = ((sent - lastBytes) * 8) / (windowMs / 1000) / 1_000_000;
      lastTick = now;
      lastBytes = sent;
      if (rampEnd == null && now - start >= 1500) {
        rampEnd = now;
        rampBytes = sent;
      }
      smooth.push(instant);
      if (smooth.length > MEDIAN_WINDOW) smooth.shift();
      const smoothed = median(smooth);
      if (rampEnd != null && smoothed > peak) peak = smoothed;
      if (samples.length >= SAMPLE_LIMIT) samples.shift();
      samples.push({ t: Date.now(), v: Number.isFinite(instant) ? instant : 0 });
      const elapsed = (now - start) / 1000;
      const avg = averageAfterRamp(sent, rampEnd, rampBytes, now, start);
      state.value = {
        ...state.value,
        phase: 'upload',
        currentMbps: smoothed,
        uploadMbps: avg,
        streams: 1,
        totalBytes: downloadBase + sent,
        progress: 0.7 + Math.min(0.3, elapsed / UPLOAD_MAX_SEC),
        samples: [...samples],
      };
      if (elapsed >= UPLOAD_MIN_SEC && rampEnd != null && isStable(sampleValues(samples))) break;
    }
    if (!inflight && !aborted && performance.now() - start < maxMs) kick();
  }

  abortControllersOnly();
  if (inflight) await inflight;

  // 最终字节：有服务端确认用确认值；否则退回 progress（外网节点异常时不至于显示 0）
  const finalBytes = confirmedSent > 0 ? confirmedSent : progressSent;
  if (finalBytes < 1024) throw lastErr instanceof Error ? lastErr : new Error('上行未收到有效数据');

  const end = performance.now();
  return {
    avg: averageAfterRamp(finalBytes, rampEnd, rampBytes, end, start),
    bytes: finalBytes,
    elapsed: (end - start) / 1000,
    streams: 1,
  };
}

async function start() {
  if (job && !aborted) return;
  aborted = false;
  controllers.length = 0;
  const isExternal = targetId.value !== 'internal';
  const node = isExternal ? activeNode.value : null;
  const downloadUrl = node ? node.downloadUrl : null;
  const uploadUrl = node ? node.uploadUrl : null;
  const streams = node?.streams ?? SPEED_STREAMS;
  const maxDl = isExternal ? MAX_DL_EXTERNAL : MAX_DL_INTERNAL;

  state.value = {
    phase: 'connecting',
    testType: isExternal ? 'external' : 'internal',
    currentMbps: 0,
    avgMbps: 0,
    peakMbps: 0,
    uploadMbps: 0,
    uploadMeasured: false,
    latencyMs: null,
    jitterMs: null,
    streams: 0,
    totalBytes: 0,
    elapsedSec: 0,
    progress: 0,
    samples: [],
    errorMessage: null,
  };

  job = (async () => {
    try {
      const ping = await measureLatency(downloadUrl);
      if (aborted) return;
      state.value = {
        ...state.value,
        phase: 'latency',
        latencyMs: ping?.latency ?? null,
        jitterMs: ping?.jitter ?? null,
      };

      let uploadEnabled = !isExternal;
      if (isExternal && uploadUrl) {
        uploadEnabled = await probeUpload(uploadUrl);
      }
      if (aborted) return;

      const down = await runDownloadPhase(downloadUrl, streams, maxDl, uploadEnabled);
      if (aborted) return;
      state.value = {
        ...state.value,
        avgMbps: down.avg,
        peakMbps: down.peak,
        streams: down.streams,
        totalBytes: down.bytes,
        elapsedSec: down.elapsed,
        samples: down.samples,
        progress: uploadEnabled ? 0.7 : 1,
      };

      let up: { avg: number; bytes: number; elapsed: number; streams: number } | null = null;
      let uploadError: string | null = null;
      if (uploadEnabled) {
        controllers.length = 0;
        try {
          up = await runUploadPhase(uploadUrl, down.bytes);
        } catch (e: any) {
          uploadError = e?.message || '上行失败';
          if (aborted) return;
          // 下行结果保留，仅提示上行
          state.value = {
            ...state.value,
            uploadMeasured: false,
            uploadMbps: 0,
            progress: 1,
            phase: 'done',
            currentMbps: down.avg,
            streams: down.streams,
            totalBytes: down.bytes,
            elapsedSec: down.elapsed,
            samples: down.samples,
            errorMessage: `上行测速失败：${uploadError}（下行结果已保留）`,
          };
          return;
        }
      }
      if (aborted) return;

      state.value = {
        ...state.value,
        phase: 'done',
        currentMbps: down.avg,
        uploadMbps: up?.avg ?? 0,
        uploadMeasured: up != null,
        streams: Math.max(down.streams, up?.streams ?? 0),
        totalBytes: down.bytes + (up?.bytes ?? 0),
        elapsedSec: down.elapsed + (up?.elapsed ?? 0),
        progress: 1,
        samples: down.samples,
        errorMessage: null,
      };
    } catch (e: any) {
      if (aborted) {
        state.value = { ...state.value, phase: 'idle', errorMessage: '测速已取消' };
      } else {
        const msg = e?.message || '未知错误';
        message.error(`测速失败: ${msg}`);
        state.value = {
          ...state.value,
          phase: 'error',
          errorMessage: `测速失败: ${msg}`,
        };
      }
    } finally {
      job = null;
      controllers.length = 0;
    }
  })();
}

onUnmounted(() => {
  cancelAll();
});
</script>

<style scoped>
.st {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 2px 0 8px;
}
.st-layout {
  display: grid;
  grid-template-columns: minmax(0, 1.05fr) minmax(0, 0.95fr);
  gap: 16px;
  align-items: start;
}
.st-left {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 10px;
  min-width: 0;
}
.st-right {
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.st-right-panel {
  flex: 1;
  min-height: 240px;
  display: flex;
  flex-direction: column;
}
.st-right-inner {
  display: flex;
  flex-direction: column;
  gap: 12px;
  flex: 1;
  animation: st-rise 0.35s ease;
}
.st-right-empty {
  flex: 1;
  min-height: 240px;
  border-radius: 12px;
  animation: st-fade-in 0.25s ease;
}
.st-chart-title {
  font-size: 12px;
  font-weight: 500;
  color: var(--text-secondary);
  margin-bottom: -4px;
}
.st-chart {
  width: 100%;
  min-height: 200px;
  border: 1px solid var(--border-subtle);
  border-radius: 10px;
  overflow: hidden;
}
.st-fade-enter-active,
.st-fade-leave-active {
  transition: opacity 0.2s ease, transform 0.2s ease;
}
.st-fade-enter-from {
  opacity: 0;
  transform: translateY(6px);
}
.st-fade-leave-to {
  opacity: 0;
  transform: translateY(-4px);
}
@keyframes st-rise {
  from {
    opacity: 0;
    transform: translateY(6px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}
@keyframes st-fade-in {
  from {
    opacity: 0;
  }
  to {
    opacity: 1;
  }
}
.st-pills {
  transition: opacity 0.25s ease, margin 0.25s ease;
}
.st-dir-col {
  transition: opacity 0.25s ease, color 0.25s ease;
}
.st-dial-slot {
  transition: opacity 0.25s ease;
}
@media (max-width: 680px) {
  .st-layout {
    grid-template-columns: 1fr;
  }
  .st-right-empty {
    min-height: 100px;
  }
}
.st-node {
  text-align: center;
  font-size: 12px;
  color: var(--text-secondary);
}
.st-pills {
  display: flex;
  justify-content: center;
  gap: 8px;
  opacity: 0;
  height: 0;
  overflow: hidden;
  transition: opacity 0.2s ease;
}
.st-pills.visible {
  opacity: 1;
  height: auto;
  overflow: visible;
  margin-bottom: 4px;
}
.st-pill {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 5px 14px;
  border-radius: 10px;
  border: 1px solid var(--border-subtle);
  font-size: 11px;
  color: var(--text-muted);
  letter-spacing: 0.04em;
}
.st-pill b {
  font-size: 14px;
  color: var(--text-primary);
  font-variant-numeric: tabular-nums;
  font-weight: 600;
}

/* ── 表盘 ── */
.st-dial-slot {
  position: relative;
  width: min(280px, 72vw);
  margin: 0 auto;
  aspect-ratio: 1;
}
.st-dial {
  width: 100%;
  height: 100%;
  display: block;
}
.dial-track {
  fill: none;
  stroke: var(--border-subtle);
  stroke-width: 6;
  stroke-linecap: round;
  /* 240° 可见弧：C≈552.92，可见=368.61，缺口=184.31；从 150° 起（开口朝下） */
  stroke-dasharray: 368.61 184.31;
  transform: rotate(150deg);
  transform-origin: 100px 100px;
}
.dial-progress {
  fill: none;
  stroke: var(--accent-color, #4f8cff);
  stroke-width: 7;
  stroke-linecap: round;
  transform: rotate(150deg);
  transform-origin: 100px 100px;
  filter: drop-shadow(0 0 6px rgba(79, 140, 255, 0.35));
}
.dial-ticks line {
  stroke: var(--text-muted);
  stroke-width: 1.2;
  opacity: 0.45;
}
.dial-needle line {
  stroke: var(--accent-color, #4f8cff);
  stroke-width: 2.5;
  stroke-linecap: round;
}
.dial-needle circle {
  fill: var(--accent-color, #4f8cff);
}
.st-dial-center {
  position: absolute;
  inset: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  pointer-events: none;
}
.st-go {
  pointer-events: auto;
  width: 88px;
  height: 88px;
  border-radius: 50%;
  border: none;
  cursor: pointer;
  font-size: 22px;
  font-weight: 700;
  letter-spacing: 0.06em;
  color: #fff;
  background: var(--accent-color, #4f8cff);
  box-shadow: 0 8px 24px rgba(79, 140, 255, 0.35);
  transition: transform 0.15s ease, box-shadow 0.15s ease;
}
.st-go:hover {
  transform: scale(1.04);
  box-shadow: 0 10px 28px rgba(79, 140, 255, 0.45);
}
.st-go:active {
  transform: scale(0.98);
}
.st-go.again {
  width: 72px;
  height: 72px;
  font-size: 16px;
}
.st-value {
  font-size: 44px;
  font-weight: 700;
  line-height: 1;
  font-variant-numeric: tabular-nums;
  letter-spacing: -0.03em;
}
.st-unit-row {
  margin-top: 4px;
  display: flex;
  justify-content: center;
  align-items: baseline;
  gap: 10px;
}
.st-unit {
  font-size: 13px;
  color: var(--text-secondary);
}
.st-phase {
  font-size: 11px;
  color: var(--text-muted);
}

.st-progress {
  margin: 0 24px;
}

/* ── 下载 / 上传 ── */
.st-dir {
  display: grid;
  grid-template-columns: 1fr auto 1fr;
  align-items: center;
  padding: 8px 4px 4px;
  gap: 0 8px;
  width: 100%;
}
.st-dir-sep {
  width: 1px;
  height: 56px;
  background: var(--border-subtle);
  margin: 0 10px;
}
.st-dir-col {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 2px;
  opacity: 0.45;
  transition: opacity 0.25s ease, color 0.25s ease;
}
.st-dir-col.on {
  opacity: 1;
  color: var(--accent-color, #4f8cff);
}
.st-dir-ico {
  width: 18px;
  height: 18px;
  margin-bottom: 2px;
  color: inherit;
  opacity: 0.7;
}
.st-dir-col.on .st-dir-ico {
  opacity: 1;
}
.st-dir-val {
  font-size: 28px;
  font-weight: 700;
  font-variant-numeric: tabular-nums;
  line-height: 1.1;
  color: inherit;
}
.st-dir-unit {
  font-size: 11px;
  color: var(--text-muted);
}
.st-dir-note {
  font-size: 12px;
  color: var(--text-muted);
  padding: 10px 6px;
}

.st-data {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.st-chart-wrap {
  border: 1px solid var(--border-subtle);
  border-radius: 10px;
  padding: 8px;
}
.st-chart {
  width: 100%;
  height: 72px;
  display: block;
}
.st-stats {
  display: grid;
  grid-template-columns: 1fr 1fr;
  border: 1px solid var(--border-subtle);
  border-radius: 10px;
  overflow: hidden;
}
.st-stat {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 9px 12px;
  font-size: 12px;
  color: var(--text-muted);
  border-bottom: 1px solid var(--border-subtle);
}
.st-stat:nth-child(odd) {
  border-right: 1px solid var(--border-subtle);
}
.st-stat:nth-last-child(-n + 2) {
  border-bottom: none;
}
.st-stat b {
  font-size: 13px;
  color: var(--text-primary);
  font-variant-numeric: tabular-nums;
  font-weight: 600;
}
.st-error {
  font-size: 12px;
  color: var(--error, #e88080);
  text-align: center;
}
.st-actions:empty {
  display: none;
}
.st-targets {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.st-target {
  display: flex;
  align-items: center;
  gap: 10px;
  width: 100%;
  padding: 10px 12px;
  border: 1px solid var(--border-subtle);
  border-radius: 10px;
  background: transparent;
  cursor: pointer;
  text-align: left;
  color: inherit;
  transition: border-color 0.15s ease, background 0.15s ease, opacity 0.15s ease;
}
.st-target:disabled {
  opacity: 0.55;
  cursor: not-allowed;
}
.st-target.active {
  border-color: var(--accent-color, #4f8cff);
  background: var(--accent-color-light, rgba(79, 140, 255, 0.1));
}
.st-target:hover {
  background: var(--surface-hover);
}
.st-t-text {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.st-t-title {
  font-size: 13px;
  font-weight: 600;
}
.st-t-sub {
  font-size: 11px;
  color: var(--text-muted);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.st-t-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  border: 2px solid var(--border-subtle);
  flex-shrink: 0;
}
.st-target.active .st-t-dot {
  border-color: var(--accent-color, #4f8cff);
  background: var(--accent-color, #4f8cff);
}
</style>
