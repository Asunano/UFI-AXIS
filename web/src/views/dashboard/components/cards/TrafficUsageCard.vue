<template>
  <GridCard class="panel-card" title="流量使用情况" :loading="initialLoading">
    <template #extra>
      <n-tag size="small" :type="usageStatusType" :bordered="false" round>{{ usageStatusText }}</n-tag>
      <!-- 「历史」与「设限额」并列：一个看过去、一个改规则，都是这张卡的下钻动作 -->
      <n-button size="small" quaternary @click="showHistory = true">历史</n-button>
      <n-button size="small" quaternary type="primary" @click="emit('open-limit')">设限额</n-button>
    </template>

    <!-- C 方案（2026-11-07）：已用大数字 + 条上标提醒线 + 三格明细。
         提醒线以前只是 legend 里一句话，没有空间位置；画成刻度后能直接看出"离红线还有多远"。 -->
    <div class="usage-c">
      <div class="c-top">
        <span class="c-used" :style="{ color: usageColor }">{{ monthUsageText }}</span>
        <span class="c-of">
          {{ hasMonthLimit ? `/ ${monthLimitText} 本月` : '本月已用 · 未设置限额' }}
        </span>
      </div>

      <template v-if="hasMonthLimit">
        <div class="c-bar">
          <i :style="{ width: usedPercent + '%', background: usageColor }" />
          <span class="c-mark" :style="{ left: alertPercent + '%' }" />
        </div>
        <div class="c-legend">
          <span :style="{ color: usageColor }">已用 {{ usedPercentText }}%</span>
          <span>{{ toAlertText }}</span>
        </div>
      </template>

      <div class="c-cells" :class="{ 'is-two': !hasDailyAvg }">
        <div class="c-cell">
          <span class="c-cell-label">今日</span>
          <span class="c-cell-value">{{ todayUsageText }}</span>
        </div>
        <div class="c-cell">
          <span class="c-cell-label">剩余</span>
          <span class="c-cell-value">{{ monthRemainText }}</span>
        </div>
        <!-- 没有小时采样数据时整格不渲染：显示一个 `--` 只会让人以为"日均是 0"或坏了 -->
        <div v-if="hasDailyAvg" class="c-cell" :title="dailyAvgHint">
          <span class="c-cell-label">日均</span>
          <span class="c-cell-value">{{ dailyAvgText }}</span>
        </div>
      </div>
    </div>

    <!-- 流量历史：本卡的下钻浮层。它只读 /api/traffic/usage、不碰限额的写流程，
         所以状态留在卡里而不是抬到页面级（限额相反，见 open-limit）。
         弹窗自己在 after-enter 里才取数，常驻挂载不会白发请求。 -->
    <TrafficHistoryModal v-model:show="showHistory" />
  </GridCard>
</template>

<script setup lang="ts">
/**
 * 流量使用情况卡。
 *
 * 数据直接读 dashboardStore（Pinia 是单例，父子读的是同一份），所以不收 props ——
 * 反过来把 traffic_summary / traffic_limit 当 props 传进来，只会多一层转发。
 * 限额的**写**留在父组件：保存后要 settleDelay + loadSummary，那是页面级的取数节奏。
 */
import { computed, onBeforeUnmount, onMounted, ref } from 'vue';
import { useDashboardStore } from '@/stores/dashboard';
import { useChartColors } from '@/composables/chartTheme';
import { formatBytes, get } from '@/composables/utils';
import GridCard from '@/components/GridCard.vue';
import { getApiClient } from '@/composables/useApi';
import { getTrafficUsage } from '@/api/traffic';
import TrafficHistoryModal from '@/views/traffic/TrafficHistoryModal.vue';

const emit = defineEmits<{ (e: 'open-limit'): void }>();

const dashboardStore = useDashboardStore();
const colors = useChartColors();

/**
 * 流量历史弹窗开关。
 *
 * 与「设限额」不同，这里不 emit 给父组件：限额是**写**操作，保存后还要
 * settleDelay + 重新 loadSummary，那份节奏必须留在页面级；历史只读，
 * 转发一层只是噪音。2026-09-15 从「push 到 traffic-history 路由」改为弹窗。
 */
const showHistory = ref(false);

const trafficSummary = computed(() => get(dashboardStore.summary, 'traffic_summary', null));
const trafficLimit = computed(() => get(dashboardStore.summary, 'traffic_limit', null));

/**
 * 只在「整份 summary 还没到手」时给骨架：loadSummary 每 10s 都会把 store 的 loading 置位，
 * 拿它直接喂 GridCard 会让这张卡每 10 秒闪一次占位块。
 */
const initialLoading = computed(() => dashboardStore.loading && !dashboardStore.summary);

// 流量使用情况数据源说明（对齐 core）：
// - traffic_limit.used_bytes / limit_bytes 由 core 算好（与 /api/device/traffic-limit 同一契约），
//   前端不再自己拿 monthly_rx/tx 相加、也不自己换算单位，避免与设备判定限额的口径出现偏差
// - /api/traffic/summary 的 total_* 在 core 里同样取当月 goform 值（TrafficRoutes.buildSummary），
//   仅在 traffic_limit 还没到达时兜底
// - 今日用量取 traffic_summary.today_rx_display / today_tx_display（core 同时给了
//   today_rx_bytes / today_tx_bytes）。core 侧算法：当前月累计 − 今天第一条采样的月累计，
//   基线读 traffic_records，跨 core 重启仍然是「今天 00:00 起」
// - 不使用 /api/device/data-usage：core 的 getCellularDataUsage 忽略时间区间、按 /sys/class/net 累计，
//   today 与 month 返回同一个"自开机以来全网口累计"值，无法代表当月蜂窝用量
const monthUsedBytes = computed(() => {
  const used = Number(trafficLimit.value?.used_bytes ?? 0);
  if (Number.isFinite(used) && used > 0) return used;
  const s = trafficSummary.value;
  return Number(s?.total_rx_bytes ?? 0) + Number(s?.total_tx_bytes ?? 0);
});

const monthUsageText = computed(() => (monthUsedBytes.value ? formatBytes(monthUsedBytes.value) : '--'));
const todayUsageText = computed(() => {
  const s = trafficSummary.value;
  if (!s?.today_rx_display) return '--';
  return `${s.today_rx_display}↓ / ${s.today_tx_display}↑`;
});

// limit_bytes 由 core 算好（1024 进制），0 = 未设置额度。
// 设备侧的复合格式（如 "470_1024"）不会出现在响应里，前端不做任何单位换算。
const monthLimitBytes = computed(() => {
  const n = Number(trafficLimit.value?.limit_bytes ?? 0);
  return Number.isFinite(n) && n > 0 ? n : 0;
});
const trafficLimitEnabled = computed(() => !!trafficLimit.value?.enabled);
// 「启用了限额」和「额度有效」是两件事：开关开着但额度为 0 时没有百分比可言
const hasMonthLimit = computed(() => trafficLimitEnabled.value && monthLimitBytes.value > 0);
// 无有效额度时返回 null，让模板走「未设置限额」占位，而不是显示 0%（会被误读成没用流量）或 NaN%
const monthLimitPercent = computed(() => {
  if (!hasMonthLimit.value) return null;
  const p = (monthUsedBytes.value / monthLimitBytes.value) * 100;
  if (!Number.isFinite(p)) return null;
  return Math.min(100, Math.max(0, p));
});
const monthLimitText = computed(() => {
  if (!hasMonthLimit.value) return '未设置限额';
  return `${trafficLimit.value?.limit_value ?? ''} ${trafficLimit.value?.limit_unit_display ?? ''}`.trim();
});

// alert_percent 是 goform 原样字符串，非法值兜底 80
const alertPercent = computed(() => {
  const n = Number(trafficLimit.value?.alert_percent);
  return Number.isFinite(n) && n > 0 && n <= 100 ? n : 80;
});
/**
 * 额度色阶。判定基准仍是「已用百分比 vs 用户设的提醒线」，但呈现是倒数的剩余量，
 * 所以读起来就是「剩余越少越红」：
 *   已用 ≥ 提醒线        → error（红，剩余告急）
 *   已用 ≥ 提醒线 × 0.6  → warning（橙，剩余偏少）
 *   否则                 → success（绿，剩余充足）
 * 进度条、剩余百分比文字、右侧总流量数值共用这一个颜色。
 */
const usageColor = computed(() => {
  const p = monthLimitPercent.value;
  // info（蓝）而不是主色：主色与 --success 同为绿，用主色会让「未设额度」和「余量充足」同色
  if (p === null) return colors.value.info;
  const alert = alertPercent.value;
  if (p >= alert) return colors.value.error;
  if (p >= alert * 0.6) return colors.value.warning;
  return colors.value.success;
});
const usageStatusType = computed(() => {
  const p = monthLimitPercent.value;
  if (p === null) return 'info';
  const alert = alertPercent.value;
  if (p >= alert) return 'error';
  if (p >= alert * 0.6) return 'warning';
  return 'success';
});
const usageStatusText = computed(() => {
  if (!trafficLimitEnabled.value) return '未启用限额';
  const p = monthLimitPercent.value;
  if (p === null) return '已启用但未设置额度';
  const alert = alertPercent.value;
  if (p >= alert) return '已达上限';
  if (p >= alert * 0.6) return '用量偏高';
  return '正常';
});

/** 已用百分比（无额度时 null，模板不渲染进度条） */
const usedPercent = computed(() => monthLimitPercent.value ?? 0);
const usedPercentText = computed(() =>
  monthLimitPercent.value === null ? '--' : String(Math.round(monthLimitPercent.value))
);

/** 剩余额度（字节）：只在有有效额度时有意义 */
const monthRemainBytes = computed(() =>
  hasMonthLimit.value ? Math.max(0, monthLimitBytes.value - monthUsedBytes.value) : 0
);
const monthRemainText = computed(() => (hasMonthLimit.value ? formatBytes(monthRemainBytes.value) : '--'));

/** 距提醒线还有多少：过了就明确说"已过提醒线"，不显示负数 */
const toAlertText = computed(() => {
  if (monthLimitPercent.value === null) return '';
  const gap = alertPercent.value - monthLimitPercent.value;
  return gap > 0 ? `距提醒线 ${Math.round(gap)}%` : '已过提醒线';
});

/**
 * 日均用量。
 *
 * 数据来自 `GET /api/traffic/usage?range=month`（每小时增量累加出来的本月日桶）。
 * **口径要说清**：接口只支持「自然日/周/月/年」窗口，没有"最近 30 天"，
 * 所以这里算的是**本月日均** = 本月桶合计 ÷ 已过天数（今天几号就算几天）。
 *
 * 为什么不拿 `monthUsedBytes / 天数`：那是设备侧当月累计（goform），与小时采样是两条链路，
 * 混着算会把"上线前没采样"的日子摊进平均值。这里只用一个来源，宁可显示 `--`。
 */
const monthBucketBytes = ref(0);
const elapsedDays = ref(0);
const hasDailyAvg = computed(() => monthBucketBytes.value > 0 && elapsedDays.value > 0);
const dailyAvgText = computed(() =>
  hasDailyAvg.value ? formatBytes(Math.round(monthBucketBytes.value / elapsedDays.value)) : '--'
);
const dailyAvgHint = computed(() =>
  monthBucketBytes.value
    ? `本月累计 ${formatBytes(monthBucketBytes.value)} ÷ ${elapsedDays.value} 天（按每小时采样累加）`
    : '本月还没有小时采样数据'
);

let avgTimer: number | undefined;
async function loadDailyAvg(): Promise<void> {
  try {
    const res = await getTrafficUsage(getApiClient(), 'month');
    if (!res) return;
    monthBucketBytes.value = res.total_bytes ?? 0;
    // 窗口是整月（含未来桶），所以"已过天数"只能取今天几号
    elapsedDays.value = new Date().getDate();
  } catch {
    // 取不到就保持 `--`：这张卡的主数据不依赖它，不能因为一个次要指标把卡片打成错误态
  }
}

onMounted(() => {
  void loadDailyAvg();
  // 5 分钟刷一次：日桶按小时累加，没必要跟着 summary 的 10s 轮询跑
  avgTimer = window.setInterval(loadDailyAvg, 5 * 60 * 1000);
});
onBeforeUnmount(() => {
  if (avgTimer !== undefined) window.clearInterval(avgTimer);
});
</script>

<style scoped>
/**
 * 2026-11-07 改版（C 方案）：从"左今日+进度条 / 右本月大数字"的两栏，
 * 改成"上行已用大数字 → 进度条（带提醒线刻度）→ 三格明细"的纵向结构。
 *
 * 为什么值得换：旧版把「剩余百分比」和「进度条」并排说同一件事，
 * 而提醒线只是一句文字、没有空间位置；新版把提醒线画成条上的刻度，
 * "离红线还有多远"一眼可见，三格再补上今日/剩余/提醒线的绝对数字。
 */
.usage-c {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.c-top {
  display: flex;
  align-items: baseline;
  gap: 6px;
  flex-wrap: wrap;
}
.c-used {
  font-size: 24px;
  font-weight: 700;
  line-height: 1.1;
  font-variant-numeric: tabular-nums;
}
.c-of {
  font-size: 12.5px;
  color: var(--text-muted);
}

/* 进度条 = 已用比例；提醒线 = 条上的 2px 刻度（伸出条外 3px，避免被圆角吃掉） */
.c-bar {
  position: relative;
  height: 12px;
  border-radius: 6px;
  background: var(--border-subtle);
  overflow: hidden;
  margin-top: 2px;
}
.c-bar > i {
  display: block;
  height: 100%;
  border-radius: inherit;
  transition: width 0.25s ease;
}
.c-mark {
  position: absolute;
  top: -2px;
  bottom: -2px;
  width: 2px;
  background: var(--warning);
  opacity: 0.85;
}

.c-legend {
  display: flex;
  justify-content: space-between;
  gap: 8px;
  font-size: 11px;
  color: var(--text-muted);
}

.c-cells {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 8px;
  margin-top: 2px;
}
/* 没有日均那格时只剩两格：两等分，不要让它们只占左边 2/3 */
.c-cells.is-two {
  grid-template-columns: repeat(2, minmax(0, 1fr));
}
.c-cell {
  display: flex;
  flex-direction: column;
  gap: 1px;
  padding: 7px 9px;
  border-radius: 9px;
  background: var(--surface-elevated, var(--card-bg));
  border: 1px solid var(--border-subtle);
  min-width: 0;
}
.c-cell-label {
  font-size: 10.5px;
  color: var(--text-muted);
}
.c-cell-value {
  font-size: 13px;
  font-weight: 600;
  font-variant-numeric: tabular-nums;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* 窄屏：三格改成两列（今日那格内容最长，独占一行），避免数字被截断 */
@media (max-width: 400px) {
  /* 三格放不下：今日那格内容最长（`620M↓ / 12M↑`），独占一行 */
  .c-cells:not(.is-two) {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
  .c-cells:not(.is-two) .c-cell:first-child {
    grid-column: 1 / -1;
  }
}
</style>
