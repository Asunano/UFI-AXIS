<template>
  <div class="monitor-view">
    <!-- 顶部时间范围选择 -->
    <GridCard title="系统监控">
      <div class="controls-bar">
        <n-button-group>
          <n-button
            v-for="r in timeRanges"
            :key="r.hours"
            :type="hours === r.hours ? 'primary' : 'default'"
            size="small"
            @click="hours = r.hours"
            >{{ r.label }}</n-button
          >
        </n-button-group>
        <n-button size="small" :loading="anyLoading" @click="loadAll">刷新</n-button>
        <n-button size="small" quaternary @click="showSettings = !showSettings">
          {{ showSettings ? '收起设置' : '监控设置' }}
        </n-button>
      </div>

      <!--
        监控个性化设置：真源在 core（GET/PUT /api/monitor/preferences），与 app 共享同一份。
        即改即存；未在此展示的 exportZip 是 app 专有项，
        web 只做字段级 PUT，不会把它们覆盖掉。
      -->
      <div v-if="showSettings" class="monitor-prefs">
        <div class="settings-row">
          <span class="settings-label">默认时间范围</span>
          <n-button-group>
            <n-button
              v-for="r in timeRanges"
              :key="`def-${r.hours}`"
              :type="prefs.defaultHours === r.hours ? 'primary' : 'default'"
              size="tiny"
              @click="savePrefs({ defaultHours: r.hours })"
              >{{ r.label }}</n-button
            >
          </n-button-group>
        </div>

        <div class="settings-row">
          <span class="settings-label">自动刷新间隔</span>
          <n-button-group>
            <n-button
              v-for="s in refreshChoices"
              :key="`ri-${s}`"
              :type="prefs.refreshIntervalSec === s ? 'primary' : 'default'"
              size="tiny"
              @click="savePrefs({ refreshIntervalSec: s })"
              >{{ s }}s</n-button
            >
          </n-button-group>
        </div>

        <div class="settings-row">
          <span class="settings-label">显示指标</span>
          <div class="settings-chips">
            <n-button
              v-for="m in metricChoices"
              :key="m.key"
              :type="metricEnabled(m.key) ? 'primary' : 'default'"
              size="tiny"
              @click="toggleMetric(m.key)"
              >{{ m.label }}</n-button
            >
          </div>
        </div>

        <!--
          采集总开关（POST /api/monitor/control）。
          core 侧真源是 AppSettings.backgroundServiceEnabled，与 POST /api/service/start|stop
          是同一个开关的两个入口；GET /api/monitor/preferences 的响应里**没有**任何能表达
          采集状态的字段（core MonitorRoutes 注释明确「采集总开关 collectEnabled 不在本模型内」），
          所以无状态可回读 —— 不凭空假设默认值，改用两个明确动作，同 NetworkView 飞行模式的惯例。
        -->
        <div class="settings-row">
          <span class="settings-label">数据采集</span>
          <n-popconfirm @positive-click="setCollect(true)">
            <template #trigger>
              <n-button size="tiny" type="primary" :loading="collectSaving">开启采集</n-button>
            </template>
            开启后 core 恢复写入监控历史，图表继续增长。确认继续？
          </n-popconfirm>
          <n-popconfirm @positive-click="setCollect(false)">
            <template #trigger>
              <n-button size="tiny" :loading="collectSaving">停止采集</n-button>
            </template>
            关闭后 core 停止写入监控历史（已有数据不删除，图表将不再增长）。确认继续？
          </n-popconfirm>
          <span class="settings-hint">
            关闭后 core 停止写入监控历史：已有数据不会删除，图表将不再增长； core
            未提供状态读取接口，此处不展示当前状态。
          </span>
        </div>

        <div class="settings-row">
          <span class="settings-label">Y 轴固定</span>
          <n-switch
            :value="prefs.fixedYAxis"
            size="small"
            @update:value="(v: boolean) => savePrefs({ fixedYAxis: v })"
          />
          <span class="settings-hint">关闭 = 按数据自适应缩放</span>
        </div>

        <div class="settings-row">
          <span class="settings-label">填充透明度</span>
          <n-slider
            :value="prefs.fillAlpha"
            :min="0"
            :max="1"
            :step="0.05"
            style="max-width: 220px"
            @update:value="(v: number) => savePrefs({ fillAlpha: Number(v.toFixed(2)) })"
          />
          <span class="settings-hint">{{ prefs.fillAlpha.toFixed(2) }}</span>
        </div>
      </div>
    </GridCard>

    <!-- 图表网格 -->
    <div class="chart-grid">
      <!-- CPU 使用率 -->
      <GridCard v-if="metricEnabled('cpu')" title="CPU 使用率">
        <template #extra>
          <ValueBadge
            v-if="cpuCurrent != null"
            :value="cpuCurrent.toFixed(1)"
            unit="%"
            :color="cpuBadgeColor"
            size="sm"
          />
        </template>
        <n-spin :show="loading.cpu">
          <div class="chart-container">
            <v-chart :option="cpuOption" autoresize />
          </div>
        </n-spin>
      </GridCard>

      <!-- 内存使用率 -->
      <GridCard v-if="metricEnabled('memory')" title="内存使用率">
        <template #extra>
          <ValueBadge v-if="memCurrent != null" :value="memCurrent.toFixed(1)" unit="%" color="#2080f0" size="sm" />
        </template>
        <n-spin :show="loading.memory">
          <div class="chart-container">
            <v-chart :option="memoryOption" autoresize />
          </div>
        </n-spin>
      </GridCard>

      <!-- 网络流量 -->
      <GridCard v-if="trafficEnabled" title="网络流量">
        <template #extra>
          <ValueBadge v-if="rxCurrent != null" :value="formatSpeedValue(rxCurrent)" color="#2080f0" size="sm" />
        </template>
        <n-spin :show="loading.traffic">
          <div class="chart-container">
            <v-chart :option="trafficOption" autoresize />
          </div>
        </n-spin>
      </GridCard>

      <!-- 信号强度 -->
      <GridCard v-if="signalEnabled" title="信号强度">
        <template #extra>
          <ValueBadge
            v-if="rsrpCurrent != null"
            :value="rsrpCurrent.toFixed(0)"
            unit="dBm"
            :color="signalColor(rsrpCurrent)"
            size="sm"
          />
        </template>
        <n-spin :show="loading.signal">
          <div class="chart-container">
            <v-chart :option="signalOption" autoresize />
          </div>
        </n-spin>
      </GridCard>

      <!-- 电池 -->
      <GridCard v-if="metricEnabled('battery')" title="电池">
        <template #extra>
          <ValueBadge v-if="batCurrent != null" :value="batCurrent.toFixed(1)" unit="%" color="#f0a050" size="sm" />
        </template>
        <n-spin :show="loading.battery">
          <div class="chart-container">
            <v-chart :option="batteryOption" autoresize />
          </div>
        </n-spin>
      </GridCard>

      <!-- 温度 -->
      <GridCard v-if="metricEnabled('temperature')" title="温度">
        <template #extra>
          <ValueBadge v-if="tempCurrent != null" :value="tempCurrent.toFixed(1)" unit="°C" color="#d03050" size="sm" />
        </template>
        <n-spin :show="loading.temperature">
          <div class="chart-container">
            <v-chart :option="tempOption" autoresize />
          </div>
        </n-spin>
      </GridCard>
    </div>

    <!-- 存储管理 -->
    <GridCard title="存储管理">
      <template #extra>
        <n-button size="small" type="warning" @click="showCleanModal = true">清理数据</n-button>
      </template>
      <n-spin :show="loading.storage">
        <n-data-table
          :columns="storageColumns"
          :data="storageData.tables"
          :bordered="false"
          :single-line="false"
          size="small"
        />
        <div class="storage-total">总占用: {{ storageData.total_display }}</div>
        <p class="storage-note">
          大小为按行估算值，非数据库实际占用；alert_records / sms_records 的记录数由后端固定返回，仅作占位。
        </p>
      </n-spin>
    </GridCard>

    <!-- 清理数据弹窗 -->
    <n-modal
      v-model:show="showCleanModal"
      preset="dialog"
      title="清理监控数据"
      positive-text="确认清理"
      negative-text="取消"
      style="width: 400px"
      @positive-click="doClean"
    >
      <div class="clean-form">
        <p>清理指定天数之前的历史数据：</p>
        <div class="clean-input-row">
          <span>保留最近</span>
          <n-input-number v-model:value="cleanDays" :min="1" :max="365" size="small" style="width: 120px" />
          <span>天的数据</span>
        </div>
        <p class="clean-hint">
          将清理 CPU / 内存 / 流量 / 信号 / 电池 / 告警 六张表；短信记录（sms_records）永久保留，不参与清理。<br />
          超出范围的数据将被永久删除，不可恢复。
        </p>
      </div>
    </n-modal>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, watch, onMounted } from 'vue';
import { useInterval } from '@/composables/useRealtime';
import { useMessage } from 'naive-ui';
import type { DataTableColumns } from 'naive-ui';
import { useCancellableApi } from '@/composables/useCancellableApi';
import { Endpoints } from '@/api/contract';
import { signalColor } from '@/composables/utils';
import { useChartColors, withGapBreaks } from '@/composables/chartTheme';
import GridCard from '@/components/GridCard.vue';
import ValueBadge from '@/components/ValueBadge.vue';

import VChart from 'vue-echarts';
import { use } from 'echarts/core';
import { CanvasRenderer } from 'echarts/renderers';
import { LineChart } from 'echarts/charts';
import { GridComponent, TooltipComponent, LegendComponent, TitleComponent } from 'echarts/components';

use([CanvasRenderer, LineChart, GridComponent, TooltipComponent, LegendComponent, TitleComponent]);

const message = useMessage();
const api = useCancellableApi();

// ── 颜色（深浅自适应）──
// 不能把 `var(--x, #fff)` 直接塞进 option：ECharts 用 CanvasRenderer，颜色最终赋给
// ctx.fillStyle/strokeStyle，CSS 变量字符串不是合法 canvas 颜色，会被静默忽略 →
// 画出上一次的 fillStyle 或默认黑色（就是图里那些莫名的灰色块）。统一走 chartTheme
// 里的 getComputedStyle 解析，并随 darkMode 自动重算。
const colors = useChartColors();

// ── 时间范围 ──
const timeRanges = [
  { label: '1h', hours: 1 },
  { label: '6h', hours: 6 },
  { label: '24h', hours: 24 },
  { label: '7d', hours: 168 },
];
const hours = ref(24);

// ── 监控个性化偏好（T40-5）──
// core 是唯一真源：`GET/PUT /api/monitor/preferences`。web 不做本地持久化，
// 每次进页面回读；回读失败静默沿用内置默认值（core_truth_silent 策略）。
interface MonitorPrefs {
  enabledTypes: string[];
  defaultHours: number;
  refreshIntervalSec: number;
  fixedYAxis: boolean;
  fillAlpha: number;
  exportZip: boolean;
}

const METRIC_KEYS = [
  'cpu',
  'memory',
  'traffic_rx',
  'traffic_tx',
  'signal_rsrp',
  'signal_sinr',
  'battery',
  'temperature',
];

// 默认值必须与 core 的 MonitorPreferences 一致，否则回读失败时两端显示不同
const prefs = ref<MonitorPrefs>({
  enabledTypes: [...METRIC_KEYS],
  defaultHours: 24,
  refreshIntervalSec: 30,
  fixedYAxis: false,
  fillAlpha: 1,
  exportZip: false,
});

const showSettings = ref(false);
const refreshChoices = [10, 30, 60, 300];
const metricChoices = [
  { key: 'cpu', label: 'CPU' },
  { key: 'memory', label: '内存' },
  { key: 'traffic_rx', label: '下行' },
  { key: 'traffic_tx', label: '上行' },
  { key: 'signal_rsrp', label: 'RSRP' },
  { key: 'signal_sinr', label: 'SINR' },
  { key: 'battery', label: '电池' },
  { key: 'temperature', label: '温度' },
];

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
 * `enabledTypes` 是完整集合语义，改单个指标也要传整份集合。
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

function toggleMetric(key: string) {
  const next = metricEnabled(key)
    ? prefs.value.enabledTypes.filter((k) => k !== key)
    : [...prefs.value.enabledTypes, key];
  savePrefs({ enabledTypes: METRIC_KEYS.filter((k) => next.includes(k)) });
}

// ── 采集总开关（POST /api/monitor/control）──
// 与偏好不是一回事：偏好只影响「画哪些图」，这个开关决定 core 还写不写监控历史。
// 状态回读：GET /api/monitor/preferences 的响应里没有任何采集开关字段（core 侧
// MonitorPreferences 把 collectEnabled 显式排除，真源在 AppSettings.backgroundServiceEnabled），
// 所以这里不做 v-model 开关、也不假设默认值，只给两个明确动作。
const collectSaving = ref(false);

async function setCollect(enabled: boolean) {
  collectSaving.value = true;
  try {
    // enabled 必须是严格的 JSON boolean：core 用 jsonPrimitive.booleanOrNull 取值，
    // 字符串 "true" 会被当成缺参直接 400（error: enabled (boolean) required）。
    const { data } = await api.post(Endpoints.monitor.control, { enabled });
    // 成功信封是 { ok: true, enabled } —— 注意是 ok 不是 success（PUT /preferences 才是 success）
    if (data?.ok === false) {
      message.error(data?.message || data?.error || '操作失败');
      return;
    }
    const eff = typeof data?.enabled === 'boolean' ? data.enabled : enabled;
    message.success(eff ? '已开启数据采集' : '已停止数据采集');
  } catch (e: any) {
    const d = e?.response?.data;
    message.error(d?.message || d?.error || '操作失败');
  } finally {
    collectSaving.value = false;
  }
}

// ── 图表原始数据 ──
interface Pt {
  t: number;
  avg: number;
  min: number;
  max: number;
}

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

// ── 存储数据 ──
const storageData = ref<{
  tables: { name: string; count: number; size_kb: number }[];
  total_kb: number;
  total_display: string;
}>({
  tables: [],
  total_kb: 0,
  total_display: '0 KB',
});

// ── 清理弹窗 ──
const showCleanModal = ref(false);
const cleanDays = ref(7);

// ── 工具函数 ──
function xAxisLabelFormat(): string {
  if (hours.value <= 24) return '{HH}:{mm}';
  return '{MM}-{dd} {HH}:{mm}';
}

// core 的 DownsampledPoint.t 是 epoch 毫秒（HistoryDownsampler / SQL 桶聚合都是 ms）
function formatTime(ms: number): string {
  const d = new Date(ms);
  if (hours.value <= 24) {
    return d.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' });
  }
  return (
    d.toLocaleDateString('zh-CN', { month: '2-digit', day: '2-digit' }) +
    ' ' +
    d.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })
  );
}

// core 存的是 rxSpeed/txSpeed，单位 bytes/s（Entities.kt: TrafficRecord）
function formatSpeedAxis(val: number): string {
  if (val == null || isNaN(val)) return '';
  if (val >= 1024 * 1024) return (val / 1024 / 1024).toFixed(1) + ' MB/s';
  if (val >= 1024) return (val / 1024).toFixed(0) + ' KB/s';
  return val.toFixed(0) + ' B/s';
}

function formatSpeedValue(val: number): string {
  if (val == null || isNaN(val)) return '--';
  if (val >= 1024 * 1024) return (val / 1024 / 1024).toFixed(2) + ' MB/s';
  if (val >= 1024) return (val / 1024).toFixed(1) + ' KB/s';
  return val.toFixed(0) + ' B/s';
}

// 空数据时在图表中央显示提示，避免看起来像渲染失败
function emptyTitle(len: number): Record<string, any> {
  return {
    show: len === 0,
    text: '暂无数据',
    left: 'center',
    top: 'middle',
    textStyle: { color: colors.value.textMuted, fontSize: 13, fontWeight: 'normal' },
  };
}

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

// ── 通用百分比图表配置 (CPU / 内存 / 电池) ──
function pctChartOption(data: Pt[] | undefined, color: string, unit: string = '%'): Record<string, any> {
  const pts = data || [];
  return {
    color: [color],
    title: emptyTitle(pts.length),
    legend: {
      data: ['最大', '最小', '平均'],
      textStyle: { color: colors.value.textSecondary, fontSize: 11 },
      top: 0,
      right: 0,
    },
    grid: { top: 30, right: 16, bottom: 24, left: 50 },
    tooltip: {
      trigger: 'axis',
      backgroundColor: colors.value.popoverBg,
      borderColor: colors.value.border,
      textStyle: { color: colors.value.textPrimary, fontSize: 12 },
      formatter: (params: any) => {
        if (!params?.length) return '';
        const p = params[0];
        // 按时间戳回查而不是用 dataIndex：withGapBreaks 会在断档处插入 null 点，
        // series 的下标已经和 pts 不再一一对应。
        const ts = p.value?.[0];
        const time = formatTime(ts);
        const pt = pts.find((x) => x.t === ts);
        if (!pt) return time;
        return (
          `<b>${time}</b><br/>` +
          `平均: ${pt.avg.toFixed(1)}${unit}<br/>` +
          `最大: ${pt.max.toFixed(1)}${unit}<br/>` +
          `最小: ${pt.min.toFixed(1)}${unit}`
        );
      },
    },
    xAxis: {
      type: 'time',
      axisLabel: { color: colors.value.textMuted, fontSize: 11, formatter: xAxisLabelFormat() },
      axisLine: { lineStyle: { color: colors.value.border } },
      splitLine: { show: false },
    },
    yAxis: {
      type: 'value',
      // fixedYAxis=true → 固定 0..100；false（默认）→ 按数据自适应缩放。
      // 只在百分比图上应用：流量/信号/温度没有公认的固定区间，硬给一个只会误导。
      ...(prefs.value.fixedYAxis ? { min: 0, max: 100 } : { scale: true }),
      axisLabel: { color: colors.value.textMuted, fontSize: 11, formatter: `{value}${unit}` },
      splitLine: { lineStyle: { color: colors.value.border, type: 'dashed' } },
    },
    series: [
      {
        name: '最大',
        type: 'line',
        smooth: true,
        symbol: 'none',
        lineStyle: { width: 1, color, opacity: 0.3 },
        areaStyle: { color, opacity: 0.1 * prefs.value.fillAlpha },
        data: withGapBreaks(
          pts,
          (p) => p.t,
          (p) => p.max
        ),
        z: 1,
      },
      {
        name: '最小',
        type: 'line',
        smooth: true,
        symbol: 'none',
        lineStyle: { width: 1, color, opacity: 0.3 },
        areaStyle: { color: colors.value.cardBg },
        data: withGapBreaks(
          pts,
          (p) => p.t,
          (p) => p.min
        ),
        z: 3,
      },
      {
        name: '平均',
        type: 'line',
        smooth: true,
        symbol: 'none',
        lineStyle: { width: 2, color },
        data: withGapBreaks(
          pts,
          (p) => p.t,
          (p) => p.avg
        ),
        z: 4,
      },
    ],
  };
}

// ── 图表 Options ──
const cpuOption = computed(() => pctChartOption(cpuData.value, colors.value.primary));
const memoryOption = computed(() => pctChartOption(memData.value, colors.value.success));
const batteryOption = computed(() => pctChartOption(batData.value, colors.value.warning));

// ── 网络流量 (双系列) ──
const trafficOption = computed(() => {
  const rx = rxDatas.value || [];
  const tx = txDatas.value || [];
  return {
    color: [colors.value.primary, colors.value.success],
    title: emptyTitle(rx.length + tx.length),
    legend: {
      data: ['下载', '上传'],
      textStyle: { color: colors.value.textSecondary, fontSize: 11 },
      top: 0,
      right: 0,
    },
    grid: { top: 30, right: 16, bottom: 24, left: 60 },
    tooltip: {
      trigger: 'axis',
      backgroundColor: colors.value.popoverBg,
      borderColor: colors.value.border,
      textStyle: { color: colors.value.textPrimary, fontSize: 12 },
      formatter: (params: any) => {
        if (!params?.length) return '';
        const time = formatTime(params[0].value[0]);
        let html = `<b>${time}</b>`;
        for (const p of params) {
          html += `<br/>${p.marker} ${p.seriesName}: ${formatSpeedValue(p.value[1])}`;
        }
        return html;
      },
    },
    xAxis: {
      type: 'time',
      axisLabel: { color: colors.value.textMuted, fontSize: 11, formatter: xAxisLabelFormat() },
      axisLine: { lineStyle: { color: colors.value.border } },
      splitLine: { show: false },
    },
    yAxis: {
      type: 'value',
      axisLabel: { color: colors.value.textMuted, fontSize: 11, formatter: formatSpeedAxis },
      splitLine: { lineStyle: { color: colors.value.border, type: 'dashed' } },
    },
    series: [
      {
        name: '下载',
        type: 'line',
        smooth: true,
        symbol: 'none',
        lineStyle: { width: 2 },
        areaStyle: { opacity: 0.06 * prefs.value.fillAlpha },
        data: withGapBreaks(
          rx,
          (p) => p.t,
          (p) => p.avg
        ),
      },
      {
        name: '上传',
        type: 'line',
        smooth: true,
        symbol: 'none',
        lineStyle: { width: 2 },
        areaStyle: { opacity: 0.06 * prefs.value.fillAlpha },
        data: withGapBreaks(
          tx,
          (p) => p.t,
          (p) => p.avg
        ),
      },
    ],
  };
});

// ── 信号强度 (双系列) ──
const signalOption = computed(() => {
  const rsrp = rsrpDatas.value || [];
  const sinr = sinrDatas.value || [];
  return {
    color: [colors.value.primary, colors.value.warning],
    title: emptyTitle(rsrp.length + sinr.length),
    legend: {
      data: ['RSRP', 'SINR'],
      textStyle: { color: colors.value.textSecondary, fontSize: 11 },
      top: 0,
      right: 0,
    },
    grid: { top: 30, right: 50, bottom: 24, left: 50 },
    tooltip: {
      trigger: 'axis',
      backgroundColor: colors.value.popoverBg,
      borderColor: colors.value.border,
      textStyle: { color: colors.value.textPrimary, fontSize: 12 },
      formatter: (params: any) => {
        if (!params?.length) return '';
        const time = formatTime(params[0].value[0]);
        let html = `<b>${time}</b>`;
        for (const p of params) {
          const u = p.seriesName === 'RSRP' ? 'dBm' : 'dB';
          html += `<br/>${p.marker} ${p.seriesName}: ${p.value[1]?.toFixed(1) ?? '--'} ${u}`;
        }
        return html;
      },
    },
    xAxis: {
      type: 'time',
      axisLabel: { color: colors.value.textMuted, fontSize: 11, formatter: xAxisLabelFormat() },
      axisLine: { lineStyle: { color: colors.value.border } },
      splitLine: { show: false },
    },
    yAxis: [
      {
        type: 'value',
        name: 'RSRP (dBm)',
        position: 'left',
        nameTextStyle: { color: colors.value.textMuted, fontSize: 11 },
        axisLabel: { color: colors.value.textMuted, fontSize: 11 },
        splitLine: { lineStyle: { color: colors.value.border, type: 'dashed' } },
      },
      {
        type: 'value',
        name: 'SINR (dB)',
        position: 'right',
        nameTextStyle: { color: colors.value.textMuted, fontSize: 11 },
        axisLabel: { color: colors.value.textMuted, fontSize: 11 },
        splitLine: { show: false },
      },
    ],
    series: [
      {
        name: 'RSRP',
        type: 'line',
        smooth: true,
        symbol: 'none',
        lineStyle: { width: 2 },
        yAxisIndex: 0,
        data: withGapBreaks(
          rsrp,
          (p) => p.t,
          (p) => p.avg
        ),
      },
      {
        name: 'SINR',
        type: 'line',
        smooth: true,
        symbol: 'none',
        lineStyle: { width: 2 },
        yAxisIndex: 1,
        data: withGapBreaks(
          sinr,
          (p) => p.t,
          (p) => p.avg
        ),
      },
    ],
  };
});

// ── 温度 ──
const tempOption = computed(() => {
  const pts = tempData.value || [];
  return {
    color: [colors.value.error],
    title: emptyTitle(pts.length),
    legend: {
      data: ['最大', '最小', '平均'],
      textStyle: { color: colors.value.textSecondary, fontSize: 11 },
      top: 0,
      right: 0,
    },
    grid: { top: 30, right: 16, bottom: 24, left: 50 },
    tooltip: {
      trigger: 'axis',
      backgroundColor: colors.value.popoverBg,
      borderColor: colors.value.border,
      textStyle: { color: colors.value.textPrimary, fontSize: 12 },
      formatter: (params: any) => {
        if (!params?.length) return '';
        const p = params[0];
        // 同 pctChartOption：断档 null 点会打乱 dataIndex，按时间戳回查
        const ts = p.value?.[0];
        const time = formatTime(ts);
        const pt = pts.find((x) => x.t === ts);
        if (!pt) return time;
        return (
          `<b>${time}</b><br/>` +
          `平均: ${pt.avg.toFixed(1)} °C<br/>` +
          `最大: ${pt.max.toFixed(1)} °C<br/>` +
          `最小: ${pt.min.toFixed(1)} °C`
        );
      },
    },
    xAxis: {
      type: 'time',
      axisLabel: { color: colors.value.textMuted, fontSize: 11, formatter: xAxisLabelFormat() },
      axisLine: { lineStyle: { color: colors.value.border } },
      splitLine: { show: false },
    },
    yAxis: {
      type: 'value',
      axisLabel: { color: colors.value.textMuted, fontSize: 11, formatter: '{value} °C' },
      splitLine: { lineStyle: { color: colors.value.border, type: 'dashed' } },
    },
    series: [
      {
        name: '最大',
        type: 'line',
        smooth: true,
        symbol: 'none',
        lineStyle: { width: 1, color: colors.value.error, opacity: 0.3 },
        areaStyle: { color: colors.value.error, opacity: 0.1 * prefs.value.fillAlpha },
        data: withGapBreaks(
          pts,
          (p) => p.t,
          (p) => p.max
        ),
        z: 1,
      },
      {
        name: '最小',
        type: 'line',
        smooth: true,
        symbol: 'none',
        lineStyle: { width: 1, color: colors.value.error, opacity: 0.3 },
        areaStyle: { color: colors.value.cardBg },
        data: withGapBreaks(
          pts,
          (p) => p.t,
          (p) => p.min
        ),
        z: 3,
      },
      {
        name: '平均',
        type: 'line',
        smooth: true,
        symbol: 'none',
        lineStyle: { width: 2, color: colors.value.error },
        data: withGapBreaks(
          pts,
          (p) => p.t,
          (p) => p.avg
        ),
        z: 4,
      },
    ],
  };
});

// ── 存储表格列 ──
const storageColumns: DataTableColumns = [
  { title: '数据表', key: 'name' },
  {
    title: '记录数',
    key: 'count',
    width: 120,
    render: (row) => (row as any).count?.toLocaleString() ?? '--',
  },
  {
    title: '大小',
    key: 'size_kb',
    width: 120,
    render: (row) => {
      const kb = (row as any).size_kb ?? 0;
      if (kb >= 1024) return (kb / 1024).toFixed(1) + ' MB';
      return kb.toFixed(1) + ' KB';
    },
  },
];

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

// ── 清理数据 ──
async function doClean() {
  try {
    const { data } = await api.post('/api/monitor/clean', {
      type: 'all',
      days: cleanDays.value,
    });
    // core 返回 deleted: { 表名: 条数 }
    const deleted =
      data?.deleted && typeof data.deleted === 'object'
        ? Object.values(data.deleted as Record<string, number>).reduce((a, b) => a + (Number(b) || 0), 0)
        : 0;
    message.success(`已清理 ${deleted} 条记录（${data?.cutoff_days ?? cleanDays.value} 天前）`);
    loadAll();
    return true;
  } catch {
    message.error('清理失败');
    return false;
  }
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

.controls-bar {
  display: flex;
  align-items: center;
  gap: 12px;
}

/* 原名 .settings-panel —— 与 settings/panels 的同名栅格类语义完全不同（那边是 auto-fit 卡片栅格，
   这里只是折叠出来的偏好设置竖列）。该类已提升为 main.css 的全局类，故此处改名避免撞车。 */
.monitor-prefs {
  display: flex;
  flex-direction: column;
  gap: 10px;
  margin-top: 12px;
  padding-top: 12px;
  border-top: 1px solid var(--border-subtle, #eee);
}

.settings-row {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
}

.settings-label {
  min-width: 96px;
  font-size: 13px;
  color: var(--text-secondary, #555);
}

.settings-chips {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.settings-hint {
  font-size: 12px;
  color: var(--text-muted, #888);
}

.chart-grid {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 16px;
}

.chart-container {
  height: 280px;
  width: 100%;
}

.storage-total {
  margin-top: 12px;
  font-size: 13px;
  font-weight: 500;
  color: var(--text-secondary);
  text-align: right;
}

.storage-note {
  margin: 6px 0 0;
  font-size: 12px;
  line-height: 1.5;
  color: var(--text-muted);
}

.clean-form p {
  margin: 0 0 12px;
  font-size: 14px;
  color: var(--text-primary);
}

.clean-input-row {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 14px;
  color: var(--text-secondary);
}

.clean-hint {
  font-size: 12px;
  color: var(--text-muted);
}

@media (max-width: 1024px) {
  .chart-grid {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 768px) {
  .chart-container {
    height: 220px;
  }
}
@media (max-width: 768px) {
  .chart-grid {
    grid-template-columns: 1fr;
  }
}
</style>
