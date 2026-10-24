<template>
  <div class="monitor-view">
    <MonitorControlsCard
      :hours="hours"
      :loading="anyLoading"
      :exporting="exporting"
      @update:hours="hours = $event"
      @refresh="loadAll"
      @export="exportCsv"
      @open-storage="storageModal.open()"
      @open-settings="settingsModal.open()"
    >
      <!--
        流量报表入口（日 / 周 / 月报，`GET /api/traffic/report`）。
        放进页头控件条而不是单开一张卡：它是一次性下钻动作，常驻卡片会白占一屏
        （与仪表盘「流量使用情况 → 历史」同一个取舍）。
      -->
      <template #actions>
        <n-button size="small" @click="reportModal.open()">流量报表</n-button>
        <!--
          告警入口（2026-10-14 从设置页「更多功能」迁来）。
          原先放设置页是临时决定，理由写在那边：「原计划并进监控页，但监控页没有现成的
          入口位置」。上一轮控件条加了 `#actions` 插槽，那个理由不成立了。
          告警与监控是同一件事的两面（一个看趋势、一个看发生了什么），放在同一页才顺。
          路由不变（`/alerts` 照旧可直接打开），这里只是换了个入口位置。
        -->
        <n-button size="small" @click="router.push({ name: 'alerts' })">告警</n-button>
      </template>
    </MonitorControlsCard>

    <div class="chart-grid">
      <MonitorChartCard
        v-if="metricEnabled('cpu')"
        title="CPU 使用率"
        :option="cpuOption"
        :loading="loading.cpu"
        :failed="failed.cpu"
        :badge="cpuCurrent != null ? cpuCurrent.toFixed(1) : null"
        unit="%"
        :color="cpuBadgeColor"
        @retry="loadAll"
      />
      <MonitorChartCard
        v-if="metricEnabled('memory')"
        title="内存使用率"
        :option="memoryOption"
        :loading="loading.memory"
        :failed="failed.memory"
        :badge="memCurrent != null ? memCurrent.toFixed(1) : null"
        unit="%"
        color="var(--info)"
        @retry="loadAll"
      />
      <MonitorChartCard
        v-if="trafficEnabled"
        title="网络流量"
        :option="trafficOption"
        :loading="loading.traffic"
        :failed="failed.traffic"
        :badge="rxCurrent != null ? formatSpeedValue(rxCurrent) : null"
        color="var(--info)"
        @retry="loadAll"
      />
      <MonitorChartCard
        v-if="signalEnabled"
        title="信号强度"
        :option="signalOption"
        :loading="loading.signal"
        :failed="failed.signal"
        :badge="rsrpCurrent != null ? rsrpCurrent.toFixed(0) : null"
        unit="dBm"
        :color="rsrpCurrent != null ? signalColor(rsrpCurrent) : undefined"
        @retry="loadAll"
      />
      <MonitorChartCard
        v-if="metricEnabled('battery')"
        title="电池"
        :option="batteryOption"
        :loading="loading.battery"
        :failed="failed.battery"
        :badge="batCurrent != null ? batCurrent.toFixed(1) : null"
        unit="%"
        color="var(--warning)"
        @retry="loadAll"
      />
      <MonitorChartCard
        v-if="metricEnabled('temperature')"
        title="温度"
        :option="tempOption"
        :loading="loading.temperature"
        :failed="failed.temperature"
        :badge="tempCurrent != null ? tempCurrent.toFixed(1) : null"
        unit="°C"
        color="var(--error)"
        @retry="loadAll"
      />
    </div>

    <!--
      存储管理 / 监控设置：都是控件条上按钮打开的弹窗（2026-10-22 由底部常驻卡与
      控件条内嵌展开区改来）。懒挂载、关闭即卸载，首屏只剩图表。
      数据与保存逻辑仍留在本页：两个弹窗只收 props、回 emit，不各持一份状态。
    -->
    <component
      :is="storageModalComponent"
      v-if="storageModalComponent"
      :show="storageModalShow"
      :data="storageData"
      :loading="loading.storage"
      :failed="failed.storage"
      @update:show="storageModal.setShow"
      @retry="loadAll"
      @cleaned="loadAll"
    />

    <component
      :is="settingsModalComponent"
      v-if="settingsModalComponent"
      :show="settingsModalShow"
      :prefs="prefs"
      :prefs-failed="prefsFailed"
      @update:show="settingsModal.setShow"
      @save="savePrefs"
      @reload-prefs="reloadPrefs"
    />

    <component
      :is="reportModalComponent"
      v-if="reportModalComponent"
      :show="reportModalShow"
      title="流量报表"
      horizontal
      @update:show="reportModal.setShow"
    />
  </div>
</template>

<script setup lang="ts">
/**
 * 监控页：只负责状态与取数编排。
 *
 * 渲染拆到 components/ 下（控件条 / 图表卡 + 设置弹窗 / 存储弹窗 / 清理弹窗），
 * option 构造在 monitorCharts.ts，类型与格式化在 monitorShared.ts。
 * 偏好与图表数据都留在这里：它们被多张卡同时消费，下沉到任何一张卡都会分叉。
 */
import { ref, computed, watch, onMounted } from 'vue';
import { useRouter } from 'vue-router';
import { buildMonitorCsv, downloadCsv, type MetricSeries } from '@/composables/monitorCsv';
import { useInterval } from '@/composables/useRealtime';
import { useMessage } from 'naive-ui';
import { useCancellableApi } from '@/composables/useCancellableApi';
import { useLazyModal } from '@/composables/useLazyModal';
import { signalColor } from '@/composables/utils';
import { useChartColors } from '@/composables/chartTheme';
import MonitorControlsCard from './components/MonitorControlsCard.vue';
import MonitorChartCard from './components/MonitorChartCard.vue';
import { bandChartOption, signalChartOption, trafficChartOption, type ChartCtx } from './monitorCharts';
import { DEFAULT_PREFS, formatSpeedValue, type MonitorPrefs, type Pt, type StorageData } from './monitorShared';

const message = useMessage();
const router = useRouter();
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
// 每次进页面回读；回读失败不再静默当作真值（那会把本地默认值当成设备配置显示，
// 见计划 §6.10 B9-1），而是置 prefsFailed 让控件条挂一条「读取失败 + 重试」。
const prefs = ref<MonitorPrefs>({ ...DEFAULT_PREFS, enabledTypes: [...DEFAULT_PREFS.enabledTypes] });
const prefsFailed = ref(false);

function metricEnabled(key: string): boolean {
  return prefs.value.enabledTypes.includes(key);
}

// 流量/信号两张图各覆盖两个指标：任一开启就画（关掉的那条曲线不取数）
const trafficEnabled = computed(() => metricEnabled('traffic_rx') || metricEnabled('traffic_tx'));
const signalEnabled = computed(() => metricEnabled('signal_rsrp') || metricEnabled('signal_sinr'));

/**
 * 回读偏好时会把 hours 改成 core 里的 defaultHours，这会触发下面的 `watch(hours)`
 * 再拉一遍历史 —— 与 onMounted 里紧跟的那次 `loadAll()` 重复（首屏两倍的
 * `/api/monitor/history`）。用一次性标记吞掉这次由回读引起的触发：
 * 标记只在取值**真的变了**时置位，否则 watcher 不触发、标记会残留下来吞掉下一次
 * 用户的真实切换。
 */
let skipHoursReload = false;

async function loadPrefs() {
  const prevHours = hours.value;
  try {
    const { data } = await api.get('/api/monitor/preferences');
    prefs.value = { ...prefs.value, ...data };
    prefsFailed.value = false;
    if (prefs.value.defaultHours !== prevHours) {
      skipHoursReload = true;
      hours.value = prefs.value.defaultHours;
    }
  } catch {
    prefsFailed.value = true;
  }
}

/** 偏好读取失败后的重试：先拿回偏好（hours 可能随之变化），再按新口径取数 */
async function reloadPrefs() {
  await loadPrefs();
  await loadAll();
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

/**
 * 取数失败标记（键与 loading 一一对应）。
 * 与「这段时间没有数据」是两件事：失败时保留上一次的曲线、由卡片显示
 * 「读取失败 + 重试」，否则空图会把失败伪装成「暂无数据」（计划 §6.10 B9-1）。
 */
const failed = ref({
  cpu: false,
  memory: false,
  traffic: false,
  signal: false,
  battery: false,
  temperature: false,
  storage: false,
});

// total_display 的初值是 `--` 而不是 `0 KB`：首帧还没取到数据，写 0 KB 是假读数
const storageData = ref<StorageData>({ tables: [], total_kb: 0, total_display: '--' });

// ── 当前值 (用于 ValueBadge) ──
const cpuCurrent = computed(() => cpuData.value.at(-1)?.avg ?? null);
const memCurrent = computed(() => memData.value.at(-1)?.avg ?? null);
const batCurrent = computed(() => batData.value.at(-1)?.avg ?? null);
const tempCurrent = computed(() => tempData.value.at(-1)?.avg ?? null);
const rxCurrent = computed(() => rxDatas.value.at(-1)?.avg ?? null);
const rsrpCurrent = computed(() => rsrpDatas.value.at(-1)?.avg ?? null);

const anyLoading = computed(() => Object.values(loading.value).some(Boolean));

// 徽标是 CSS 上下文（ValueBadge 的 :style），所以这里用令牌引用而不是 useChartColors()
// 的字面量 —— 换肤/暗色下会自动跟。图表本身走 colors（canvas 不认 var()）。
const cpuBadgeColor = computed(() => {
  const v = cpuCurrent.value;
  if (v == null) return undefined;
  if (v >= 80) return 'var(--error)';
  if (v >= 60) return 'var(--warning)';
  return 'var(--success)';
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
/** 失败返回 null（而不是空数组）：调用方据此区分「读失败」与「这段时间没有数据」 */
async function fetchHistory(type: string): Promise<Pt[] | null> {
  try {
    const { data } = await api.get('/api/monitor/history', {
      params: { type, hours: hours.value, points: 360 },
    });
    return data.points || [];
  } catch {
    return null;
  }
}

async function loadSingle(type: string, target: { value: Pt[] }, key: keyof typeof loading.value) {
  loading.value[key] = true;
  try {
    const pts = await fetchHistory(type);
    failed.value[key] = pts === null;
    // 失败时保留上一次的曲线：清空只会让图变成「暂无数据」，那是另一种假状态
    if (pts) target.value = pts;
  } finally {
    loading.value[key] = false;
  }
}

async function loadTraffic() {
  loading.value.traffic = true;
  try {
    const [rx, tx] = await Promise.all([fetchHistory('traffic_rx'), fetchHistory('traffic_tx')]);
    failed.value.traffic = rx === null || tx === null;
    if (rx) rxDatas.value = rx;
    if (tx) txDatas.value = tx;
  } finally {
    loading.value.traffic = false;
  }
}

async function loadSignal() {
  loading.value.signal = true;
  try {
    const [rsrp, sinr] = await Promise.all([fetchHistory('signal_rsrp'), fetchHistory('signal_sinr')]);
    failed.value.signal = rsrp === null || sinr === null;
    if (rsrp) rsrpDatas.value = rsrp;
    if (sinr) sinrDatas.value = sinr;
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
      total_kb: data.total_kb ?? 0,
      // 缺值一律 `--`，不拿 0 KB 冒充「已取到」
      total_display: data.total_display || '--',
    };
    failed.value.storage = false;
  } catch {
    // 失败不清空旧表：由存储弹窗显示「读取失败 + 重试」，总占用显示 `--`
    failed.value.storage = true;
  } finally {
    loading.value.storage = false;
  }
}

/**
 * 2026-10-06 新增：监控数据 CSV 导出。
 * 导出的就是当前页面已加载（勾选显示 + 当前时间范围）的序列 ——「所见即所得」，
 * 与 app 端 exportZip 的全量口径不同（web 不拉未展示的指标，避免额外取数）。
 */
const exporting = ref(false);
async function exportCsv() {
  if (exporting.value) return;
  exporting.value = true;
  try {
    const series: MetricSeries[] = [];
    if (metricEnabled('cpu')) series.push({ key: 'cpu', label: 'CPU使用率(%)', points: cpuData.value });
    if (metricEnabled('memory')) series.push({ key: 'memory', label: '内存使用率(%)', points: memData.value });
    if (metricEnabled('temperature')) series.push({ key: 'temperature', label: '温度(°C)', points: tempData.value });
    if (metricEnabled('battery')) series.push({ key: 'battery', label: '电池(%)', points: batData.value });
    if (metricEnabled('traffic_rx') && rxDatas.value.length)
      series.push({ key: 'traffic_rx', label: '下行(B/s)', points: rxDatas.value });
    if (metricEnabled('traffic_tx') && txDatas.value.length)
      series.push({ key: 'traffic_tx', label: '上行(B/s)', points: txDatas.value });
    if (metricEnabled('signal_rsrp') && rsrpDatas.value.length)
      series.push({ key: 'signal_rsrp', label: 'RSRP(dBm)', points: rsrpDatas.value });
    if (metricEnabled('signal_sinr') && sinrDatas.value.length)
      series.push({ key: 'signal_sinr', label: 'SINR(dB)', points: sinrDatas.value });
    if (!series.length) {
      message.warning('没有可导出的指标：请先在监控设置中勾选要显示的指标');
      return;
    }
    downloadCsv(`ufi-monitor-${new Date().toISOString().slice(0, 10)}.csv`, buildMonitorCsv(series, hours.value));
  } finally {
    exporting.value = false;
  }
}

/**
 * 流量报表弹窗（2026-10-24）：直接复用仪表盘的「流量历史」弹窗。
 *
 * 监控页原来的 /report 逐日表格与主界面的 /usage 分桶图数据口径太接近，
 * 维护两套 UI 没有收益；这里只换标题，区间切换、翻页、柱形图、合计都复用同一组件。
 * 仍然是 useLazyModal：一次性下钻，没必要在监控页常驻。
 */
const reportModal = useLazyModal(() => import('@/views/traffic/TrafficHistoryModal.vue'));
const reportModalComponent = reportModal.component;
const reportModalShow = reportModal.show;

/**
 * 存储管理 / 监控设置弹窗（2026-10-22）。
 *
 * 两个入口都在控件条上，弹窗本身同样走 useLazyModal 懒挂载、关闭即卸载：
 * 首屏不该为「可能不点开」的存储表格与十来行设置付出渲染成本。
 * 存储取数仍留在 loadAll（自动刷新同一个口径），所以弹窗一打开就是当前值；
 * 设置的状态在父组件，弹窗关闭不会丢未保存内容（设置是即改即存，本来也没有未保存态）。
 */
const storageModal = useLazyModal(() => import('./components/StorageCard.vue'));
const storageModalComponent = storageModal.component;
const storageModalShow = storageModal.show;

const settingsModal = useLazyModal(() => import('./components/modals/MonitorSettingsModal.vue'));
const settingsModalComponent = settingsModal.component;
const settingsModalShow = settingsModal.show;

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
// 回读偏好导致的那一次 hours 变化已经由 onMounted 后面的 loadAll 覆盖，跳过它以免首屏重复取数
watch(hours, () => {
  if (skipHoursReload) {
    skipHoursReload = false;
    return;
  }
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
