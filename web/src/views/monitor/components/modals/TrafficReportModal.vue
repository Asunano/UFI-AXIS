<template>
  <n-modal
    :show="show"
    preset="card"
    title="流量报表"
    class="modal-fit"
    style="--modal-w: 560px; --modal-h: 720px"
    :bordered="false"
    @update:show="emit('update:show', $event)"
    @after-enter="onOpened"
  >
    <!-- 三档切换：沿用本页既有的 n-button-group 版式（同 MonitorControlsCard 的时间范围） -->
    <div class="period-row">
      <n-button-group>
        <n-button
          v-for="p in PERIODS"
          :key="p.value"
          :type="period === p.value ? 'primary' : 'default'"
          size="small"
          @click="switchPeriod(p.value)"
          >{{ p.label }}</n-button
        >
      </n-button-group>
      <n-button size="small" quaternary :loading="loading" @click="reload()">刷新</n-button>
    </div>

    <!-- n-spin 而不是整块换成骨架：切档时旧报表留在屏幕上，避免每切一次闪一次空屏 -->
    <n-spin :show="loading" class="report-spin">
      <!--
        错误提示与报表不再互斥（计划 §6.10 B9-3）：原来是 `v-if="error"` + `v-else-if="report"`，
        刷新失败时那块旧报表会被整块顶掉。现在错误条常驻在报表上方，报表继续显示，
        由文案说明它是上一次的结果。
      -->
      <n-alert v-if="error" type="error" :bordered="false" class="report-alert">
        {{ error }}<template v-if="report">（下方仍是上一次成功取到的报表）</template>
      </n-alert>

      <template v-if="report">
        <!-- summary 是 core 生成的一整句中文摘要，原样显示；前端不重拼（两套口径迟早分叉） -->
        <p class="summary">{{ report.summary }}</p>

        <div class="meta-row">
          <span class="meta-label">统计区间</span>
          <span class="meta-value">{{ rangeText }}</span>
        </div>
        <p class="meta-hint">
          右开区间：含起始时刻、不含结束时刻；共 {{ report.days.length }} 个自然日（缺数据的日期也在其中，计 0）。
        </p>
        <!-- 按自然日出桶，所以 day 档只有「今天」这一天，而今天还没过完 -->
        <p v-if="period === 'day'" class="day-hint">
          日报只覆盖「今天」这一天，且今天还没过完 —— 数字会随使用继续增长。要看完整趋势请切周报 / 月报。
        </p>

        <template v-if="hasUsage">
          <div class="total-grid">
            <div class="total-item">
              <span class="total-label">总用量</span>
              <span class="total-value">{{ formatBytes(report.total_bytes) }}</span>
            </div>
            <div class="total-item">
              <span class="total-label">下行</span>
              <span class="total-value">{{ formatBytes(report.rx_bytes) }}</span>
            </div>
            <div class="total-item">
              <span class="total-label">上行</span>
              <span class="total-value">{{ formatBytes(report.tx_bytes) }}</span>
            </div>
          </div>

          <div class="days-title">逐日明细</div>
          <div class="days-list">
            <div v-for="d in report.days" :key="d.day_start" class="day-row">
              <!-- day_label 由 core 用 MM-dd 格式化好，直接显示 -->
              <span class="day-label">{{ d.day_label }}</span>
              <n-progress
                type="line"
                :percentage="barPercent(d.total_bytes)"
                :height="8"
                :show-indicator="false"
                class="day-bar"
              />
              <span class="day-total">
                {{ formatBytes(d.total_bytes) }}
                <span class="day-split">↓{{ formatBytes(d.rx_bytes) }} ↑{{ formatBytes(d.tx_bytes) }}</span>
              </span>
            </div>
          </div>
        </template>

        <!-- 全 0 时不铺一排 0：那是一屏噪声，说不清"没数据"还是"没取到" -->
        <div v-else class="empty-box">
          <n-empty description="该区间内没有任何流量记录" />
        </div>
      </template>

      <div v-else-if="!loading && !error" class="empty-box">
        <n-empty description="暂无数据" />
      </div>
    </n-spin>

    <template #footer>
      <div class="modal-footer">
        <n-button size="small" @click="emit('update:show', false)">关闭</n-button>
      </div>
    </template>
  </n-modal>
</template>

<script setup lang="ts">
/**
 * 流量日 / 周 / 月报弹窗（2026-10-14 接入孤儿端点 `GET /api/traffic/report`）。
 *
 * ## 为什么存在
 * web 早就能配「流量报告」定时任务推到手机（`views/tasks/tasksShared.ts` 与
 * `views/settings/notifyShared.ts` 里的 `traffic_report` 动作），却自己看不到这份报表 ——
 * core 的 `/report` 自 2026-10-06 起是**两端都没人调**的孤儿端点。这里把它接上，链路才闭环。
 *
 * ## 怎么切分
 * 响应类型、取数、缓存与渲染全在本文件。**没有**把类型下沉到 `monitorShared.ts`：
 * 它是本弹窗独占的（监控页那 6 张图走 `/api/monitor/history`，与 `/report` 不同源），
 * 放进共享文件只会让下次改这里的人以为还有别的消费方。
 *
 * ## 否决过的替代方案
 *  · **ECharts 画逐日趋势**：监控页确实已经挂了 vue-echarts，但为一个「最多 31 行」的
 *    明细再起一个图表实例，要多管 resize / 主题重算 / dispose，而 naive 的 n-progress
 *    本身就是「按占比画横向条」，够用且零实例开销；
 *  · **让 core 顺手返回格式化好的用量文案**：本仓已有 `formatBytes`（composables/utils），
 *    再让 core 拼一份字符串就是两套口径（现有 `unit()` 与它的小数位并不一致）；
 *  · **缓存放父组件**：弹窗由 `useLazyModal` 懒挂载、关闭即卸载，缓存只能放这里。
 *    代价是「关掉再打开」会重新取一次 —— 这正是想要的：报表随用量一直在变。
 *
 * ## 契约（core: `TrafficRoutes.get("/report")` + `TrafficReportGenerator`）
 *  · `summary` 是 **String**，不是对象：`generator.summarize(report)` →
 *    `summarizeLine()`，形如「本周流量 12.3 GB（↓11.0 GB ↑1.3 GB），日均 1.8 GB，最高周六 4.1 GB」；
 *  · `range_start` / `range_end` 是 **epoch 毫秒**（`Report.rangeStart` 来自
 *    `Calendar.timeInMillis`，已按自然日 / 周 / 月对齐到当日 0 点）；
 *  · `day_label` 由 core 的 `SimpleDateFormat("MM-dd")` 产出，逐日明细直接用它 ——
 *    前端再 format 一遍，补零与时区写法只要有一处不同就会出现「10-4」与「10-04」并存；
 *  · `period` 非法 core 回 **400**（正常不会发生，真发生说明契约漂了，见 describeError）。
 */
import { computed, reactive, ref } from 'vue';
import { useCancellableApi } from '@/composables/useCancellableApi';
import { Endpoints } from '@/api/contract';
import { formatBytes } from '@/composables/utils';

type ReportPeriod = 'day' | 'week' | 'month';

interface ReportDay {
  /** 当日 0 点，epoch 毫秒 */
  day_start: number;
  /** core 格式化好的 `MM-dd` */
  day_label: string;
  rx_bytes: number;
  tx_bytes: number;
  total_bytes: number;
}

interface TrafficReport {
  period: ReportPeriod;
  range_start: number;
  range_end: number;
  rx_bytes: number;
  tx_bytes: number;
  total_bytes: number;
  summary: string;
  days: ReportDay[];
}

const PERIODS: { value: ReportPeriod; label: string }[] = [
  { value: 'day', label: '日报' },
  { value: 'week', label: '周报' },
  { value: 'month', label: '月报' },
];

defineProps<{ show: boolean }>();
const emit = defineEmits<{ 'update:show': [boolean] }>();

const api = useCancellableApi();

// 默认周报：与 core 的默认值一致，也是三档里唯一「既有趋势又已经过完大半」的一档
const period = ref<ReportPeriod>('week');

/** 三档各自缓存：切回已拉过的那一档不再重新请求 */
const cache = reactive<Partial<Record<ReportPeriod, TrafficReport>>>({});
/** 错误与加载态也按档记：切档时不该把上一档的报错/转圈带到这一档 */
const errors = reactive<Partial<Record<ReportPeriod, string>>>({});
const loadingMap = reactive<Record<ReportPeriod, boolean>>({ day: false, week: false, month: false });

const report = computed(() => cache[period.value] ?? null);
const error = computed(() => errors[period.value] ?? null);
const loading = computed(() => loadingMap[period.value]);

const hasUsage = computed(() => {
  const r = report.value;
  if (!r) {
    return false;
  }
  return r.total_bytes > 0 || r.days.some((d) => d.total_bytes > 0);
});

/** 条形以「区间内用量最大的那一天」为满格：绝对值没意义，趋势才有 */
const maxDayTotal = computed(() => {
  const days = report.value?.days ?? [];
  return days.reduce((max, d) => (d.total_bytes > max ? d.total_bytes : max), 0);
});

function barPercent(total: number): number {
  const max = maxDayTotal.value;
  if (max <= 0 || !Number.isFinite(total)) {
    return 0;
  }
  return Math.min(100, Math.round((total / max) * 1000) / 10);
}

/**
 * `range_start` / `range_end` 是 epoch 毫秒，这里按**浏览器本地时区**渲染。
 * 逐日明细一律用 core 给的 day_label —— 手机与浏览器不在同一时区时两个口径可能差一天，
 * 以 core（设备时区）为准。
 */
function formatStamp(ms: number): string {
  const d = new Date(ms);
  if (Number.isNaN(d.getTime())) {
    return '--';
  }
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

const rangeText = computed(() => {
  const r = report.value;
  if (!r) {
    return '--';
  }
  return `${formatStamp(r.range_start)} – ${formatStamp(r.range_end)}`;
});

/**
 * 400 与网络错误分开提示。
 *
 * 本组件只会发 day / week / month 三个值，所以真收到 400 说明**契约漂了**
 * （core 收窄了合法值集合），文案要指向版本不匹配，而不是让用户去查网络。
 */
function describeError(e: any): string {
  const status = e?.response?.status;
  const detail = e?.response?.data?.reason || e?.response?.data?.error;
  if (status === 400) {
    return `core 拒绝了报表参数（period 只认 day / week / month）${detail ? `：${detail}` : ''}`;
  }
  if (status) {
    return `获取流量报表失败（HTTP ${status}）${detail ? `：${detail}` : ''}`;
  }
  return '获取流量报表失败：连不上 core，请检查连接后重试';
}

/** 响应是 map 拼出来的，逐字段收敛一次，避免 undefined 直接进模板 */
function toReport(data: any, p: ReportPeriod): TrafficReport | null {
  if (!data || typeof data !== 'object' || !Array.isArray(data.days)) {
    return null;
  }
  return {
    period: p,
    range_start: Number(data.range_start) || 0,
    range_end: Number(data.range_end) || 0,
    rx_bytes: Number(data.rx_bytes) || 0,
    tx_bytes: Number(data.tx_bytes) || 0,
    total_bytes: Number(data.total_bytes) || 0,
    summary: String(data.summary ?? ''),
    days: data.days.map((d: any) => ({
      day_start: Number(d?.day_start) || 0,
      day_label: String(d?.day_label ?? ''),
      rx_bytes: Number(d?.rx_bytes) || 0,
      tx_bytes: Number(d?.tx_bytes) || 0,
      total_bytes: Number(d?.total_bytes) || 0,
    })),
  };
}

async function load(p: ReportPeriod, force = false) {
  // 已拉过的那一档直接命中缓存 —— 三档来回切不该每次重新请求同一档
  if (!force && cache[p]) {
    return;
  }
  loadingMap[p] = true;
  delete errors[p];
  try {
    const { data } = await api.get(Endpoints.traffic.report, { params: { period: p } });
    // data 为 undefined = 请求被 useCancellableApi 取消（弹窗已关），不写任何状态
    if (!data) {
      return;
    }
    const parsed = toReport(data, p);
    if (!parsed) {
      errors[p] = 'core 返回的报表结构无法识别';
      return;
    }
    cache[p] = parsed;
  } catch (e: any) {
    errors[p] = describeError(e);
  } finally {
    loadingMap[p] = false;
  }
}

function switchPeriod(p: ReportPeriod) {
  period.value = p;
  void load(p);
}

function reload() {
  void load(period.value, true);
}

/**
 * 打开时才取数（after-enter 而不是 onMounted）：弹窗关闭即卸载，
 * 但进场动画跑完再请求能避开动画期间的布局抖动（同 TrafficHistoryModal）。
 */
function onOpened() {
  void load(period.value);
}
</script>

<style scoped>
.period-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
  margin-bottom: 12px;
}

/* 首屏取数时内容区还是空的，n-spin 的遮罩没有内容可覆盖会塌成一条线（本仓多处同此处理） */
.report-spin {
  display: block;
  min-height: 160px;
}

.summary {
  margin: 0 0 10px;
  font-size: 15px;
  font-weight: 600;
  color: var(--text-primary);
}

.meta-row {
  display: flex;
  align-items: baseline;
  gap: 8px;
  font-size: 13px;
}

.meta-label {
  color: var(--text-secondary);
}

.meta-value {
  color: var(--text-primary);
  font-variant-numeric: tabular-nums;
}

.meta-hint {
  margin: 4px 0 0;
  font-size: 12px;
  line-height: 1.5;
  color: var(--text-muted);
}

.day-hint {
  margin: 8px 0 0;
  padding: 8px 10px;
  border-radius: var(--radius-sm);
  background: var(--surface-hover);
  font-size: 12px;
  line-height: 1.6;
  color: var(--text-secondary);
}

.report-alert {
  margin-bottom: 8px;
}

.total-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 8px;
  margin: 12px 0;
  padding: 10px 12px;
  border-radius: var(--radius-md);
  background: var(--surface-hover);
}

.total-item {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}

.total-label {
  font-size: 12px;
  color: var(--text-secondary);
}

.total-value {
  font-size: 15px;
  font-weight: 600;
  color: var(--text-primary);
  font-variant-numeric: tabular-nums;
}

.days-title {
  margin-bottom: 6px;
  font-size: 13px;
  font-weight: 600;
  color: var(--text-primary);
}

/* 月报有 28–31 行：滚动交给 `.modal-fit` 的内容区（`--modal-h: 720px` + 内容区 overflow），
   这里不再自己套一层 `max-height: 46vh` —— 两层各自滚会让滚动条在弹窗中间再出现一条，
   且矮屏下两层都滚不到底。头尾（标题 / 关闭按钮）由 `.modal-fit` 固定。 */
.days-list {
  padding-right: 4px;
}

.day-row {
  display: grid;
  grid-template-columns: 44px 1fr 88px;
  align-items: center;
  gap: 8px;
  padding: 4px 0;
}

.day-label {
  font-size: 12px;
  color: var(--text-secondary);
  font-variant-numeric: tabular-nums;
}

.day-bar {
  min-width: 0;
}

.day-total {
  text-align: right;
  font-size: 12px;
  color: var(--text-primary);
  font-variant-numeric: tabular-nums;
}

.day-split {
  display: block;
  font-size: 11px;
  color: var(--text-muted);
}

.empty-box {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 180px;
}

.modal-footer {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}

@media (max-width: 768px) {
  .total-grid {
    grid-template-columns: 1fr 1fr;
  }
  .day-row {
    grid-template-columns: 40px 1fr 76px;
  }
}
</style>
