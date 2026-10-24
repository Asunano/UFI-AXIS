<template>
  <GridCard class="panel-card" title="系统状态">
    <div class="gauge-grid">
      <div class="gauge-cell">
        <!-- `?? 0` 只是给类型收窄用：v-if 已经保证非 null，这个 0 永远不会被渲染 -->
        <RingGauge
          v-if="loadPercent !== null"
          :value="loadPercent ?? 0"
          :max="100"
          label="负载"
          unit="%"
          :color="loadColor"
          :size="130"
          :stroke-width="10"
        />
        <!-- 没有数据时不要画一个 0% 的环：那是「取到了 0」的样子，不是「没取到」 -->
        <div v-else class="gauge-empty">
          <span class="gauge-empty-value">--</span>
          <span class="gauge-empty-label">负载</span>
        </div>
        <div class="gauge-subtitle">{{ cpuSubtitle }}</div>
      </div>
      <div class="gauge-cell">
        <RingGauge
          v-if="cpuTemp !== null"
          :value="cpuTemp ?? 0"
          :max="100"
          label="CPU 温度"
          unit="°C"
          :color="tempColor"
          :size="130"
          :stroke-width="10"
        />
        <div v-else class="gauge-empty">
          <span class="gauge-empty-value">--</span>
          <span class="gauge-empty-label">CPU 温度</span>
        </div>
        <div class="gauge-subtitle">{{ cpuFreqText }}</div>
      </div>
      <div class="gauge-cell">
        <RingGauge
          v-if="memoryPercent !== null"
          :value="memoryPercent ?? 0"
          :max="100"
          label="内存"
          unit="%"
          :color="memColor"
          :size="130"
          :stroke-width="10"
        />
        <div v-else class="gauge-empty">
          <span class="gauge-empty-value">--</span>
          <span class="gauge-empty-label">内存</span>
        </div>
        <div class="gauge-subtitle">{{ memorySubtitle }}</div>
      </div>
      <div class="gauge-cell">
        <RingGauge
          v-if="storagePercent !== null"
          :value="storagePercent ?? 0"
          :max="100"
          label="存储"
          unit="%"
          :color="storageColor"
          :size="130"
          :stroke-width="10"
        />
        <div v-else class="gauge-empty">
          <span class="gauge-empty-value">--</span>
          <span class="gauge-empty-label">存储</span>
        </div>
        <div class="gauge-subtitle">{{ storageSubtitle }}</div>
      </div>
    </div>
  </GridCard>
</template>

<script setup lang="ts">
/**
 * 系统状态：负载 / CPU 温度 / 内存 / 存储 四个环形仪表。
 *
 * 完全自给自足：数据全部来自 dashboardStore（取数由页面的 useInterval 驱动），
 * 色阶只依赖 useChartColors —— 所以没有 props 也没有 emit。
 * canvas 不解析 CSS 变量（赋给 ctx.strokeStyle 会被静默忽略），颜色必须是解析后的字面量。
 *
 * 显示口径（2026-10-21）：四个读数都收敛成 `number | null`，null = 没取到 —— 模板据此
 * 不渲染环、改显示 `--`。改前是 `?? 0`：接口还没回来（或失败）时四个环都停在 0%，
 * 与「真的空闲 / 真的 0 度」长得一样。
 */
import { computed } from 'vue';
import { useDashboardStore } from '@/stores/dashboard';
import { useChartColors } from '@/composables/chartTheme';
import { get } from '@/composables/utils';
import GridCard from '@/components/GridCard.vue';
import RingGauge from '@/components/RingGauge.vue';

const dashboardStore = useDashboardStore();
const colors = useChartColors();

const cpuData = computed(() => dashboardStore.realtimeCpu);
const memoryData = computed(() => dashboardStore.realtimeMemory);

/** 只把「真的数字」当读数：undefined / null / NaN 一律 null（0 是合法读数，别用真值判断） */
function numOrNull(v: unknown): number | null {
  return typeof v === 'number' && Number.isFinite(v) ? v : null;
}

/** 字节 → GB 的文字形式。原来两处各写一遍 `1_073_741_824` 与 `.toFixed(1)`。 */
const BYTES_PER_GB = 1_073_741_824;
function gbText(bytes: number): string {
  return (bytes / BYTES_PER_GB).toFixed(1);
}

const storageData = computed(() => {
  const s = get(dashboardStore.summary, 'storage', null);
  if (!s) return null;
  const used = Number(s.used) || 0;
  const total = Number(s.total) || 0;
  // total 为 0 时没有百分比可言（除零）→ null，让模板显示 --
  return { used, total, percent: total > 0 ? (used / total) * 100 : null };
});

const loadPercent = computed(() => numOrNull(cpuData.value?.usage_percent));
const cpuTemp = computed(() => numOrNull(cpuData.value?.temperature));
const memoryPercent = computed(() => numOrNull(memoryData.value?.usage_percent));
const storagePercent = computed(() => storageData.value?.percent ?? null);

const loadColor = computed(() => {
  const v = loadPercent.value ?? 0;
  if (v >= 80) return colors.value.error;
  if (v >= 60) return colors.value.warning;
  // 低档一律 info（蓝）：主色已与 --success 同为绿，跟隔壁「温度正常=绿」会撞在一起
  return colors.value.info;
});
const tempColor = computed(() => {
  const t = cpuTemp.value ?? 0;
  if (t >= 75) return colors.value.error;
  if (t >= 55) return colors.value.warning;
  return colors.value.success;
});
const memColor = computed(() => {
  const v = memoryPercent.value ?? 0;
  if (v >= 85) return colors.value.error;
  if (v >= 70) return colors.value.warning;
  return colors.value.info;
});
const storageColor = computed(() => {
  const v = storagePercent.value ?? 0;
  if (v >= 85) return colors.value.error;
  if (v >= 70) return colors.value.warning;
  return colors.value.info;
});

const cpuFreqText = computed(() => {
  const cores = cpuData.value?.cores as Array<{ freq_mhz: number; freq_display: string }> | undefined;
  if (!cores?.length) return '--';
  const avg = cores.reduce((sum, c) => sum + (c.freq_mhz || 0), 0) / cores.length;
  if (avg >= 1000) return `${(avg / 1000).toFixed(2)} GHz`;
  return `${avg.toFixed(0)} MHz`;
});
const cpuSubtitle = computed(() => `${cpuData.value?.core_count ?? '--'} 核 · ${cpuFreqText.value}`);

const memorySubtitle = computed(() => {
  const m = memoryData.value;
  if (!m?.total) return '--';
  return `${gbText(Number(m.used) || 0)} / ${gbText(m.total)} GB`;
});

const storageSubtitle = computed(() => {
  const s = storageData.value;
  if (!s?.total) return '--';
  return `${gbText(s.used)} / ${gbText(s.total)} GB`;
});
</script>

<style scoped>
.gauge-grid {
  display: grid;
  /* auto-fit：按可用宽度连续换行（4 / 3 / 2 / 1 列），不再依赖断点硬切 */
  grid-template-columns: repeat(auto-fit, minmax(140px, 1fr));
  gap: var(--space-4);
  /* 环的外径：模板里 RingGauge 的 :size 也是 130（prop 不走 CSS），
     无数据占位块靠这个变量与环对齐 */
  --gauge-size: 130px;
}
/* 原名 .gauge-card —— 它并不是一张卡，只是仪表 + 副标题的居中单元格，改名避免与卡壳混淆 */
.gauge-cell {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: var(--space-2);
}
/* 无数据占位：与 RingGauge 同尺寸的虚线圈 + 「--」，保持这一格不塌陷也不伪装成 0% */
.gauge-empty {
  width: var(--gauge-size);
  height: var(--gauge-size);
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 2px;
  border: 1px dashed var(--border-subtle);
  border-radius: 50%;
}
.gauge-empty-value {
  font-size: var(--font-xl);
  font-weight: 700;
  color: var(--text-muted);
}
.gauge-empty-label {
  font-size: var(--font-sm);
  color: var(--text-secondary);
}
.gauge-subtitle {
  font-size: var(--font-sm);
  color: var(--text-muted);
  text-align: center;
}

@media (max-width: 768px) {
  /* auto-fit + minmax(140px) 在 360px 手机上恰好挤成 2 列 ~142px，环塞得下但副标题会折行。
     显式锁 2 列并收紧间距，排布可预期；4 个仪表刚好两行。 */
  .gauge-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: var(--space-3);
    /* 2 列后每格只有 ~140px，130 的环勉强塞得下；占位块跟着缩，免得撑破格子 */
    --gauge-size: 120px;
  }
}
</style>
