<template>
  <GridCard class="panel-card" title="流量使用情况">
    <template #extra>
      <n-tag size="small" :type="usageStatusType" :bordered="false" round>{{ usageStatusText }}</n-tag>
      <n-button size="small" quaternary type="primary" @click="emit('open-limit')">设限额</n-button>
    </template>

    <div class="usage-wide">
      <!-- 左：今日流量（进度条上方）+ 使用进度条 -->
      <div class="usage-bar-col">
        <div class="usage-bar-head">
          <span class="usage-bar-label">今日</span>
          <span class="usage-bar-value">{{ todayUsageText }}</span>
        </div>
        <!-- 进度条按「剩余额度」倒数：100% → 0%，越少越红（颜色见 usageColor） -->
        <template v-if="hasMonthLimit">
          <n-progress
            class="usage-bar"
            type="line"
            :percentage="monthRemainPercent"
            :color="usageColor"
            rail-color="var(--border-subtle)"
            :height="8"
            :border-radius="4"
            :show-indicator="false"
          />
          <div class="usage-bar-meta">
            <span :style="{ color: usageColor }">剩余 {{ monthRemainPercentText }}%</span>
            <span>提醒线 {{ alertPercent }}%</span>
          </div>
        </template>
        <div v-else class="usage-bar-meta usage-bar-meta-only">
          {{ trafficLimitEnabled ? '已启用限额，但设备未设置额度' : '未启用流量限额' }}
        </div>
      </div>

      <!-- 右：本月总流量。已用值独占一行当主角，额度与说明各自换行退到次要位置 -->
      <div class="usage-hero">
        <span class="usage-hero-value" :style="{ color: usageColor }">{{ monthUsageText }}</span>
        <span v-if="hasMonthLimit" class="usage-hero-total">/ {{ monthLimitText }}</span>
        <span class="usage-hero-label">本月总流量</span>
      </div>
    </div>
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
import { computed } from 'vue';
import { useDashboardStore } from '@/stores/dashboard';
import { useChartColors } from '@/composables/chartTheme';
import { formatBytes, get } from '@/composables/utils';
import GridCard from '@/components/GridCard.vue';

const emit = defineEmits<{ (e: 'open-limit'): void }>();

const dashboardStore = useDashboardStore();
const colors = useChartColors();

const trafficSummary = computed(() => get(dashboardStore.summary, 'traffic_summary', null));
const trafficLimit = computed(() => get(dashboardStore.summary, 'traffic_limit', null));

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
/**
 * 剩余额度百分比：进度条与文案都按「倒数」呈现（满额 100% → 用尽 0%），
 * 比「已用 N%」更贴合「还能用多少」这个真正要看的问题。
 * 无有效额度时按满额处理 —— 此时进度条本身不渲染，这个值只是兜底不让模板拿到 null。
 */
const monthRemainPercent = computed(() => (monthLimitPercent.value === null ? 100 : 100 - monthLimitPercent.value));
const monthRemainPercentText = computed(() => String(Math.round(monthRemainPercent.value)));
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
</script>

<style scoped>
/* 左：今日流量 + 使用进度条（吃掉剩余宽度）；右：本月总流量大数值。
   「剩余」已按需求去掉 —— 有进度条和「总/额度」并排就够读出剩余，多一格反而分散重点。 */
.usage-wide {
  display: flex;
  align-items: center;
  gap: 24px;
  flex-wrap: wrap;
}
.usage-bar-col {
  flex: 1;
  min-width: 180px;
}
/* 进度条上方的今日流量：标签 + 值同一行，不占额外高度 */
.usage-bar-head {
  display: flex;
  align-items: baseline;
  gap: 6px;
  margin-bottom: 6px;
}
.usage-bar-label {
  font-size: 12px;
  color: var(--text-muted);
}
.usage-bar-value {
  font-size: 13px;
  font-weight: 600;
  color: var(--text-primary);
  font-variant-numeric: tabular-nums;
}
.usage-bar-meta {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  margin-top: 6px;
  font-size: 12px;
  color: var(--text-muted);
}
/* 没有有效额度时只有一行说明文字，不需要两端对齐 */
.usage-bar-meta-only {
  justify-content: flex-start;
}

.usage-hero {
  display: flex;
  flex-direction: column;
  /* 靠右对齐：这块被放到卡片右侧，标签与数值都贴右边才不会看着像左栏的续行 */
  align-items: flex-end;
  gap: 2px;
}
.usage-hero-label {
  font-size: 12px;
  color: var(--text-muted);
}
/* 已用值 / 额度 / 说明各占一行（.usage-hero 是竖排 flex），
   已用值靠字号与字重独占视觉重心，额度退成一行小字参照 */
.usage-hero-value {
  font-size: 26px;
  font-weight: 700;
  line-height: 1.15;
  letter-spacing: -0.02em;
  font-variant-numeric: tabular-nums;
}
.usage-hero-total {
  font-size: 13px;
  color: var(--text-secondary);
  font-variant-numeric: tabular-nums;
}

@media (max-width: 768px) {
  /* 窄屏时横排放不下：进度条列与总流量数值上下堆叠，数值改回左对齐 */
  .usage-wide {
    flex-direction: column;
    align-items: stretch;
    gap: 14px;
  }
  .usage-hero {
    align-items: flex-start;
  }
}
</style>
