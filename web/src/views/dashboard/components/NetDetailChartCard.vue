<template>
  <GridCard class="panel-card chart-card">
    <!-- 头部左侧不是标题而是 Tab 按钮组，走 GridCard 的 #title 槽 -->
    <template #title>
      <n-button-group size="small">
        <n-button
          :type="netDetailTab === 'traffic' ? 'primary' : 'default'"
          size="small"
          @click="netDetailTab = 'traffic'"
        >
          网络详情
        </n-button>
        <n-button
          :type="netDetailTab === 'signal' ? 'primary' : 'default'"
          size="small"
          @click="netDetailTab = 'signal'"
        >
          信号详情
        </n-button>
      </n-button-group>
    </template>
    <template v-if="netDetailTab === 'traffic'" #extra>
      <n-tag size="small" type="success" :bordered="false"> 上行 {{ formatSpeed(trafficData?.tx_speed ?? 0) }} </n-tag>
      <n-tag size="small" type="info" :bordered="false"> 下行 {{ formatSpeed(trafficData?.rx_speed ?? 0) }} </n-tag>
    </template>

    <!-- 网络详情：实时流量 -->
    <template v-if="netDetailTab === 'traffic'">
      <div class="readout-bar">
        <ValueBadge label="当月发送" :value="trafficSummary?.month_tx_display || '--'" size="lg" variant="outline" />
        <ValueBadge label="当月接收" :value="trafficSummary?.month_rx_display || '--'" size="lg" variant="outline" />
      </div>

      <!-- 上行/下行的名称与实时值已在卡头的 tag 里，legend 关掉免得说两遍 -->
      <ScrollingLineChart
        class="chart-container"
        :series="trafficSeries"
        :window-ms="TRAFFIC_WINDOW_MS"
        :sample-ms="TRAFFIC_SAMPLE_MS"
        :left-range="trafficRange"
        :left-formatter="formatSpeedAxis"
        :legend="false"
      />
    </template>

    <!-- 信号详情：RSRP / RSSI / RSRQ / SINR 折线 -->
    <template v-else>
      <div class="readout-bar">
        <ValueBadge label="RSRP" :value="rsrpText" size="lg" variant="outline" :color="rsrpColor" />
        <ValueBadge label="SINR" :value="sinrText" size="lg" variant="outline" />
        <ValueBadge label="RSRQ" :value="rsrqText" size="lg" variant="outline" />
        <ValueBadge label="RSSI" :value="rssiText" size="lg" variant="outline" />
      </div>

      <!-- 四条线必须有颜色索引，legend 打开 -->
      <ScrollingLineChart
        class="chart-container"
        :series="signalSeries"
        :window-ms="SIGNAL_WINDOW_MS"
        :sample-ms="SIGNAL_SAMPLE_MS"
        :left-range="dbmYRange"
        :right-range="dbYRange"
        :left-formatter="formatDbm"
        :right-formatter="formatDb"
      />
    </template>
  </GridCard>
</template>

<script setup lang="ts">
import { ref, computed } from 'vue';
import { useDashboardStore } from '@/stores/dashboard';
import { useChartColors } from '@/composables/chartTheme';
import { formatSpeed, get, signalColor } from '@/composables/utils';
import { useWsTopics } from '@/composables/useRealtime';
import GridCard from '@/components/GridCard.vue';
import ValueBadge from '@/components/ValueBadge.vue';
import ScrollingLineChart from '@/components/ScrollingLineChart.vue';
import type { ScrollingSeries } from '@/components/ScrollingLineChart.vue';

const dashboardStore = useDashboardStore();

// ── 颜色常量 ──
// canvas 不解析 CSS 变量（赋给 ctx.strokeStyle 会被静默忽略），所以这里统一走
// useChartColors()：内部用 getComputedStyle 把变量解析成具体色值，并随暗色模式重算。
const colors = useChartColors();

// ── 实时数据（来自 store，由 DefaultLayout 统一写入）──
const trafficData = computed(() => dashboardStore.realtimeTraffic);
const signal = computed(() => dashboardStore.realtimeSignal);
const trafficSummary = computed(() => get(dashboardStore.summary, 'traffic_summary', null));

const rsrpColor = computed(() => {
  const r = signal.value?.rsrp;
  return r != null ? signalColor(r) : colors.value.textSecondary;
});

// 读数条文本：缺值时只显示 '--'，不要拼出 "-- dBm"
function readout(v: number | null | undefined, unit: string): string {
  return v != null ? `${v} ${unit}` : '--';
}
const rsrpText = computed(() => readout(signal.value?.rsrp, 'dBm'));
const rssiText = computed(() => readout(signal.value?.rssi, 'dBm'));
const rsrqText = computed(() => readout(signal.value?.rsrq, 'dB'));
const sinrText = computed(() => readout(signal.value?.sinr, 'dB'));

// 网络详情 / 信号详情 切换
const netDetailTab = ref<'traffic' | 'signal'>('traffic');

// ── 折线图（2026-09-05 换成 ScrollingLineChart）──
//
// 此前这里塞着整套 ECharts option + 一个 rAF 循环推 X 轴窗口 + 一阶低通缓动 Y 轴。
// 问题是运动被两处各管一半（轴靠 rAF 走、点靠 setOption 跳），两条时间线对不齐，越调越怪。
// 现在渲染整体交给 `components/ScrollingLineChart.vue`：每个点的 x 由自己的时间戳直接算出，
// 曲线天然匀速左移，本文件只负责「攒数据 + 给 Y 轴范围」。
//
// 保留的两个既有结论（别再退回去）：信号图用 3min 窗口（10s 一帧，60s 窗口只有 6 个点）；
// RSRP/RSSI 走左轴 dBm、RSRQ/SINR 走右轴 dB（量级差几十 dB，同轴会全贴边）。
//
// 已知取舍：窗口宽度固定，不再随数据"长出来"。刚进页面时曲线只占右侧一小截，
// 流量图一分钟内填满，信号图（10s 一帧）要 3 分钟。之前那套动态窗口每来一帧就横向缩放一次，
// 那个"呼吸"比空白更碍眼，所以这次选固定窗口。
const TRAFFIC_WINDOW_MS = 60_000; // 流量满窗 60s（约 60 点，1s 一帧）
const SIGNAL_WINDOW_MS = 180_000; // 信号满窗 3min（约 18 点，10s 一帧）
// 标称采样间隔：ScrollingLineChart 的笔尖推进速度按它算（与真实节奏一致时笔尖在屏幕上静止）。
// 两个值都来自 core 的 WS 推送节奏，改那边的节奏时这里要跟着改。
const TRAFFIC_SAMPLE_MS = 1_000;
const SIGNAL_SAMPLE_MS = 10_000;
const CHART_KEEP_SLACK_MS = 5_000; // 缓冲区比窗口多留一点，左端不出现缺口
const TRAFFIC_FLOOR_KBPS = 128; // 流量 Y 轴下限：空闲时几百字节的噪点不该被放大成大波浪

/** 按时间裁剪缓冲区（代替按点数裁剪，否则不同帧率的两条流各留成不同时长） */
function trimByTime<T extends { t: number }>(buf: T[], windowMs: number): T[] {
  const cutoff = Date.now() - windowMs - CHART_KEEP_SLACK_MS;
  const keepFrom = buf.findIndex((p) => p.t >= cutoff);
  return keepFrom > 0 ? buf.slice(keepFrom) : buf;
}

/** 量化到 1 / 2 / 5 × 10ⁿ 档：上界只在真的跨档时才动，平时纹丝不动 */
function niceCeil(v: number): number {
  const base = 10 ** Math.floor(Math.log10(v));
  const n = v / base;
  return (n <= 1 ? 1 : n <= 2 ? 2 : n <= 5 ? 5 : 10) * base;
}

/** 信号 Y 轴上下界：按 step 的倍数取整，同样是为了让轴平时不动 */
function steppedRange(values: number[], step: number, fallback: [number, number]): [number, number] {
  if (!values.length) return fallback;
  return [Math.floor((Math.min(...values) - 1) / step) * step, Math.ceil((Math.max(...values) + 1) / step) * step];
}

// ── 实时流量历史 ──
interface TrafficPt {
  t: number;
  /** 下行 KB/s */
  rx: number;
  /** 上行 KB/s */
  tx: number;
}
const trafficHistory = ref<TrafficPt[]>([]);

function pushTrafficPoint(rx: number, tx: number) {
  trafficHistory.value.push({ t: Date.now(), rx: rx / 1024, tx: tx / 1024 });
  trafficHistory.value = trimByTime(trafficHistory.value, TRAFFIC_WINDOW_MS);
}

// ── 实时信号历史 ──
interface SignalPt {
  t: number;
  rsrp: number;
  sinr: number;
  rsrq: number;
  rssi: number;
}
const signalHistory = ref<SignalPt[]>([]);

function pushSignalPoint(data: Record<string, any> | null) {
  if (!data) return;
  signalHistory.value.push({
    t: Date.now(),
    rsrp: data.rsrp ?? 0,
    sinr: data.sinr ?? 0,
    rsrq: data.rsrq ?? 0,
    rssi: data.rssi ?? 0,
  });
  signalHistory.value = trimByTime(signalHistory.value, SIGNAL_WINDOW_MS);
}

// ── 轴范围与系列 ──
const trafficRange = computed<[number, number]>(() => {
  const speeds = trafficHistory.value.flatMap((p) => [p.rx, p.tx]);
  return [0, niceCeil(Math.max(TRAFFIC_FLOOR_KBPS, ...speeds) * 1.25)];
});

const trafficSeries = computed<ScrollingSeries[]>(() => [
  {
    id: 'rx',
    name: '下行',
    color: colors.value.info,
    points: trafficHistory.value.map((p) => ({ t: p.t, v: p.rx })),
    area: true,
    width: 2,
  },
  {
    id: 'tx',
    name: '上行',
    color: colors.value.success,
    points: trafficHistory.value.map((p) => ({ t: p.t, v: p.tx })),
    area: true,
    width: 2,
  },
]);

const dbmYRange = computed(() =>
  steppedRange(
    signalHistory.value.flatMap((p) => [p.rsrp, p.rssi]),
    10,
    [-120, -50]
  )
);
const dbYRange = computed(() =>
  steppedRange(
    signalHistory.value.flatMap((p) => [p.rsrq, p.sinr]),
    5,
    [-20, 30]
  )
);

// 四条线不画面积：叠在一起会糊成一团，只靠线色区分
const signalSeries = computed<ScrollingSeries[]>(() => [
  {
    id: 'rsrp',
    name: 'RSRP',
    color: colors.value.info,
    points: signalHistory.value.map((p) => ({ t: p.t, v: p.rsrp })),
    area: false,
    width: 1.8,
  },
  {
    id: 'rssi',
    name: 'RSSI',
    color: colors.value.success,
    points: signalHistory.value.map((p) => ({ t: p.t, v: p.rssi })),
    area: false,
    width: 1.8,
  },
  {
    id: 'rsrq',
    name: 'RSRQ',
    color: colors.value.warning,
    points: signalHistory.value.map((p) => ({ t: p.t, v: p.rsrq })),
    axis: 'right',
    area: false,
    width: 1.8,
  },
  {
    id: 'sinr',
    name: 'SINR',
    color: colors.value.purple,
    points: signalHistory.value.map((p) => ({ t: p.t, v: p.sinr })),
    axis: 'right',
    area: false,
    width: 1.8,
  },
]);

/**
 * 左轴刻度用紧凑写法（`512K` / `1.5M`）而不是 `512 KB/s`。
 * 完整单位在卡头的「上行/下行」tag 上已经写了，刻度再重复一遍只会把轴标签槽撑宽 ——
 * 手机竖屏时那个槽本来就已经吃掉三分之一画幅。
 */
function formatSpeedAxis(val: number): string {
  if (val >= 1024) return (val / 1024).toFixed(1) + 'M';
  return val.toFixed(0) + 'K';
}
function formatDbm(val: number): string {
  return `${Math.round(val)}`;
}
function formatDb(val: number): string {
  return `${Math.round(val)}`;
}

// 只订阅本页图表缓冲需要的频道。
// store 的写入（realtimeTraffic / realtimeSignal / realtimeCpu / realtimeMemory）
// 已统一挪到应用壳 DefaultLayout —— 否则一离开本页就没人往 store 写，网络页读到的是空对象。
useWsTopics({
  traffic: (data) => pushTrafficPoint(data.rx_speed || 0, data.tx_speed || 0),
  signal: (data) => pushSignalPoint(data),
});
</script>

<style scoped>
/* 折线图卡：吃掉左栏剩余高度，图表区随之伸缩。
   overflow:hidden 由 GridCard 自带 —— 画布绝不允许画到卡片外面。 */
.chart-card {
  flex: 1 1 auto;
  min-height: 260px;
  display: flex;
  flex-direction: column;
}
/* 卡壳是 GridCard，要伸缩的是它的 .grid-card-body */
.chart-card :deep(.grid-card-body) {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
}

/* 读数条容器。流量（当月收发）与信号（RSRP/SINR/RSRQ/RSSI）共用一个容器样式，
   胶囊统一走 ValueBadge 的 outline + lg。 */
.readout-bar {
  display: flex;
  flex-wrap: wrap;
  justify-content: center;
  gap: 10px;
  margin-bottom: 12px;
}

/* 高度不再写死：宽屏下吃掉卡片剩余空间，min-height 兜住极矮窗口 */
.chart-container {
  flex: 1;
  min-height: 180px;
  width: 100%;
}

/*
  移动端：卡片本身与图表区都降一档高度，读数条收紧。
  信号页有 4 个读数（RSRP/SINR/RSRQ/RSSI），360px 宽只能排 2 个一行 = 两行，
  所以这里把 gap 收小；轴标签槽的收窄在 ScrollingLineChart 内部按画布宽度自适应。
*/
@media (max-width: 768px) {
  .chart-card {
    min-height: 220px;
  }
  .readout-bar {
    gap: 6px;
    margin-bottom: 10px;
  }
  .chart-container {
    min-height: 160px;
  }
}
</style>
