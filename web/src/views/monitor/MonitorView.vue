<template>
  <div class="monitor-view">
    <MonitorControlsCard
      :prefs="prefs"
      :hours="hours"
      :loading="anyLoading"
      @update:hours="hours = $event"
      @refresh="loadAll"
      @save="savePrefs"
    />

    <div class="chart-grid">
      <MonitorChartCard
        v-if="metricEnabled('cpu')"
        title="CPU 使用率"
        :option="cpuOption"
        :loading="loading.cpu"
        :badge="cpuCurrent != null ? cpuCurrent.toFixed(1) : null"
        unit="%"
        :color="cpuBadgeColor"
      />
      <MonitorChartCard
        v-if="metricEnabled('memory')"
        title="内存使用率"
        :option="memoryOption"
        :loading="loading.memory"
        :badge="memCurrent != null ? memCurrent.toFixed(1) : null"
        unit="%"
        color="#2080f0"
      />
      <MonitorChartCard
        v-if="trafficEnabled"
        title="网络流量"
        :option="trafficOption"
        :loading="loading.traffic"
        :badge="rxCurrent != null ? formatSpeedValue(rxCurrent) : null"
        color="#2080f0"
      />
      <MonitorChartCard
        v-if="signalEnabled"
        title="信号强度"
        :option="signalOption"
        :loading="loading.signal"
        :badge="rsrpCurrent != null ? rsrpCurrent.toFixed(0) : null"
        unit="dBm"
        :color="rsrpCurrent != null ? signalColor(rsrpCurrent) : undefined"
      />
      <MonitorChartCard
        v-if="metricEnabled('battery')"
        title="电池"
        :option="batteryOption"
        :loading="loading.battery"
        :badge="batCurrent != null ? batCurrent.toFixed(1) : null"
        unit="%"
        color="#f0a050"
      />
      <MonitorChartCard
        v-if="metricEnabled('temperature')"
        title="温度"
        :option="tempOption"
        :loading="loading.temperature"
        :badge="tempCurrent != null ? tempCurrent.toFixed(1) : null"
        unit="°C"
        color="#d03050"
      />
    </div>

    <StorageCard :data="storageData" :loading="loading.storage" @cleaned="loadAll" />
  </div>
</template>

<script setup lang="ts">
/**
 * 监控页：只负责状态与取数编排。
 *
 * 渲染拆到 components/ 下（设置卡 / 图表卡 / 存储卡 + 清理弹窗），
 * option 构造在 monitorCharts.ts，类型与格式化在 monitorShared.ts。
 * 偏好与图表数据都留在这里：它们被多张卡同时消费，下沉到任何一张卡都会分叉。
 */
import { ref, computed, watch, onMounted } from 'vue';
import { useInterval } from '@/composables/useRealtime';
import { useMessage } from 'naive-ui';
import { useCancellableApi } from '@/composables/useCancellableApi';
import { signalColor } from '@/composables/utils';
import { useChartColors } from '@/composables/chartTheme';
import MonitorControlsCard from './components/MonitorControlsCard.vue';
import MonitorChartCard from './components/MonitorChartCard.vue';
import StorageCard from './components/StorageCard.vue';
import { bandChartOption, signalChartOption, trafficChartOption, type ChartCtx } from './monitorCharts';
import { DEFAULT_PREFS, formatSpeedValue, type MonitorPrefs, type Pt, type StorageData } from './monitorShared';

const message = useMessage();
const api = useCancellableApi();

// ── 颜色（深浅自适应）──
// 不能把 `var(--x, #fff)` 直接塞进 option：ECharts 用 CanvasRenderer，颜色最终赋给
// ctx.fillStyle/strokeStyle，CSS 变量字符串不是合法 canvas 颜色，会被静默忽略 →
// 画出上一次的 fillStyle 或默认黑色（就是图里那些莫名的灰色块）。统一走 chartTheme
// 里的 getComputedStyle 解析，并随 darkMode 自动重算。
const colors = useChartColors();

const hours = ref(24);

// ── 监控个性化偏好（T40-5）──
// core 是唯一真源：`GET/PUT /api/monitor/preferences`。web 不做本地持久化，
// 每次进页面回读；回读失败静默沿用内置默认值（core_truth_silent 策略）。
const prefs = ref<MonitorPrefs>({ ...DEFAULT_PREFS, enabledTypes: [...DEFAULT_PREFS.enabledTypes] });

function metricEnabled(key: string): boolean {
  return prefs.value.enabledTypes.includes(key);
}

// 流量/信号两张图各覆盖两个指标：任一开启就画（关掉的那条曲线不取数）
const trafficEnabled = computed(() => metricEnabled('traffic_rx') || metricEnabled('traffic_tx'));
const signalEnabled = computed(() => metricEnabled('signal_rsrp') || metricEnabled('signal_sinr'));

async function loadPrefs() {
  try {
    const { data } = await api.get('/api/monitor/preferences');
    prefs.value = { ...prefs.value, ...data };
    hours.value = prefs.value.defaultHours;
  } catch {
    /* 静默：沿用默认值，不在 UI 上提示 */
  }
}

/**
 * 字段级保存：只把改动的键 PUT 上去。
 * 绝不整体回传 —— 否则 web 会把自己不展示的 app 专有项（exportZip）
 * 用本地默认值覆盖掉，这正是「app 设为 A、web 打开变成 B」的成因。
 */
async function savePrefs(patch: Partial<MonitorPrefs>) {
  const before = { ...prefs.value };
  prefs.value = { ...prefs.value, ...patch };
  const needReload = patch.defaultHours !== undefined;
  try {
    const { data } = await api.put('/api/monitor/preferences', patch);
    // 以服务端回显为准：core 会做取值域校验
    if (data?.preferences) prefs.value = { ...prefs.value, ...data.preferences };
    if (needReload) hours.value = prefs.value.defaultHours;
    // refreshIntervalSec 改动无需手动重启轮询：useInterval 的 delay 是 getter，会自动重建
  } catch {
    prefs.value = before;
    message.error('保存监控设置失败');
  }
}

// ── 图表原始数据 ──
const cpuData = ref<Pt[]>([]);
const memData = ref<Pt[]>([]);
const batData = ref<Pt[]>([]);
const tempData = ref<Pt[]>([]);
const rxDatas = ref<Pt[]>([]);
const txDatas = ref<Pt[]>([]);
const rsrpDatas = ref<Pt[]>([]);
const sinrDatas = ref<Pt[]>([]);

// ── 加载状态 ──
const loading = ref({
  cpu: false,
  memory: false,
  traffic: false,
  signal: false,
  battery: false,
  temperature: false,
  storage: false,
});

const storageData = ref<StorageData>({ tables: [], total_kb: 0, total_display: '0 KB' });

// ── 当前值 (用于 ValueBadge) ──
const cpuCurrent = computed(() => cpuData.value.at(-1)?.avg ?? null);
const memCurrent = computed(() => memData.value.at(-1)?.avg ?? null);
const batCurrent = computed(() => batData.value.at(-1)?.avg ?? null);
const tempCurrent = computed(() => tempData.value.at(-1)?.avg ?? null);
const rxCurrent = computed(() => rxDatas.value.at(-1)?.avg ?? null);
const rsrpCurrent = computed(() => rsrpDatas.value.at(-1)?.avg ?? null);

const anyLoading = computed(() => Object.values(loading.value).some(Boolean));

const cpuBadgeColor = computed(() => {
  const v = cpuCurrent.value;
  if (v == null) return undefined;
  if (v >= 80) return '#d03050';
  if (v >= 60) return '#f0a050';
  return '#18a058';
});

// ── 图表 Options ──
const chartCtx = computed<ChartCtx>(() => ({
  colors: colors.value,
  hours: hours.value,
  fillAlpha: prefs.value.fillAlpha,
  fixedYAxis: prefs.value.fixedYAxis,
}));

// CPU 用 info（蓝）而不是主色：主色现在与 --success 同为绿，内存图正好用 success，
// 两张图会变成同一个绿。系列配色的要求是互相可区分，不跟着换肤走。
const cpuOption = computed(() => bandChartOption(cpuData.value, colors.value.info, chartCtx.value));
const memoryOption = computed(() => bandChartOption(memData.value, colors.value.success, chartCtx.value));
const batteryOption = computed(() => bandChartOption(batData.value, colors.value.warning, chartCtx.value));
// 温度不夹 0..100：没有公认区间，套「Y 轴固定」只会把曲线压成一条直线
const tempOption = computed(() => bandChartOption(tempData.value, colors.value.error, chartCtx.value, ' °C', false));
const trafficOption = computed(() => trafficChartOption(rxDatas.value, txDatas.value, chartCtx.value));
const signalOption = computed(() => signalChartOption(rsrpDatas.value, sinrDatas.value, chartCtx.value));

// ── 数据加载 ──
async function fetchHistory(type: string): Promise<Pt[]> {
  try {
    const { data } = await api.get('/api/monitor/history', {
      params: { type, hours: hours.value, points: 360 },
    });
    return data.points || [];
  } catch {
    return [];
  }
}

async function loadSingle(type: string, target: { value: Pt[] }, key: keyof typeof loading.value) {
  loading.value[key] = true;
  try {
    target.value = await fetchHistory(type);
  } finally {
    loading.value[key] = false;
  }
}

async function loadTraffic() {
  loading.value.traffic = true;
  try {
    const [rx, tx] = await Promise.all([fetchHistory('traffic_rx'), fetchHistory('traffic_tx')]);
    rxDatas.value = rx;
    txDatas.value = tx;
  } finally {
    loading.value.traffic = false;
  }
}

async function loadSignal() {
  loading.value.signal = true;
  try {
    const [rsrp, sinr] = await Promise.all([fetchHistory('signal_rsrp'), fetchHistory('signal_sinr')]);
    rsrpDatas.value = rsrp;
    sinrDatas.value = sinr;
  } finally {
    loading.value.signal = false;
  }
}

async function loadStorage() {
  loading.value.storage = true;
  try {
    const { data } = await api.get('/api/monitor/storage');
    storageData.value = {
      tables: data.tables || [],
      total_kb: data.total_kb || 0,
      total_display: data.total_display || '0 KB',
    };
  } catch {
    /* 静默 */
  } finally {
    loading.value.storage = false;
  }
}

async function loadAll() {
  // 关掉的指标不取数（与 app 的 enabledMonitorTypes 过滤同口径），避免为不显示的图白跑请求
  await Promise.all([
    metricEnabled('cpu') ? loadSingle('cpu', cpuData, 'cpu') : Promise.resolve(),
    metricEnabled('memory') ? loadSingle('memory', memData, 'memory') : Promise.resolve(),
    metricEnabled('battery') ? loadSingle('battery', batData, 'battery') : Promise.resolve(),
    metricEnabled('temperature') ? loadSingle('temperature', tempData, 'temperature') : Promise.resolve(),
    trafficEnabled.value ? loadTraffic() : Promise.resolve(),
    signalEnabled.value ? loadSignal() : Promise.resolve(),
    loadStorage(),
  ]);
}

// ── 监听时间范围变化 ──
watch(hours, () => {
  loadAll();
});

// ── 初始化 + 自动刷新（间隔由 core 侧偏好 refreshIntervalSec 决定，改设置后自动重建）──
useInterval(loadAll, () => prefs.value.refreshIntervalSec * 1000);

onMounted(async () => {
  // 先回读偏好再取数：否则会用默认 24h 拉一遍、再按 defaultHours 拉第二遍
  await loadPrefs();
  loadAll();
});
</script>

<style scoped>
.monitor-view {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.chart-grid {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 16px;
}

@media (max-width: 1024px) {
  .chart-grid {
    grid-template-columns: 1fr;
  }
}
</style>
