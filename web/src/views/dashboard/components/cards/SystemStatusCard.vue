<template>
  <GridCard class="panel-card" title="系统状态">
    <div class="gauge-grid">
      <div class="gauge-cell">
        <RingGauge
          :value="loadPercent"
          :max="100"
          label="负载"
          unit="%"
          :color="loadColor"
          :size="130"
          :stroke-width="10"
        />
        <div class="gauge-subtitle">{{ cpuSubtitle }}</div>
      </div>
      <div class="gauge-cell">
        <RingGauge
          :value="cpuTemp"
          :max="100"
          label="CPU"
          unit="°C"
          :color="tempColor"
          :size="130"
          :stroke-width="10"
        />
        <div class="gauge-subtitle">{{ cpuFreqText }}</div>
      </div>
      <div class="gauge-cell">
        <RingGauge
          :value="memoryPercent ?? 0"
          :max="100"
          label="内存"
          unit="%"
          :color="memColor"
          :size="130"
          :stroke-width="10"
        />
        <div class="gauge-subtitle">{{ memorySubtitle }}</div>
      </div>
      <div class="gauge-cell">
        <RingGauge
          :value="storagePercent"
          :max="100"
          label="存储"
          unit="%"
          :color="storageColor"
          :size="130"
          :stroke-width="10"
        />
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

const storageData = computed(() => {
  const s = get(dashboardStore.summary, 'storage', null);
  if (!s) return { percent: 0, used: 0, total: 0, available: 0 };
  const percent = s.total > 0 ? (s.used / s.total) * 100 : 0;
  return { ...s, percent };
});

const loadPercent = computed(() => cpuData.value?.usage_percent ?? 0);
const cpuTemp = computed(() => cpuData.value?.temperature ?? 0);
const memoryPercent = computed(() => memoryData.value?.usage_percent ?? null);
const storagePercent = computed(() => storageData.value.percent);

const loadColor = computed(() => {
  const v = loadPercent.value;
  if (v >= 80) return colors.value.error;
  if (v >= 60) return colors.value.warning;
  // 低档一律 info（蓝）：主色已与 --success 同为绿，跟隔壁「温度正常=绿」会撞在一起
  return colors.value.info;
});
const tempColor = computed(() => {
  const t = cpuTemp.value;
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
  const v = storagePercent.value;
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
  if (!m?.total) return '加载中...';
  const used = (m.used / 1_073_741_824).toFixed(1);
  const total = (m.total / 1_073_741_824).toFixed(1);
  return `${used} / ${total} GB`;
});

const storageSubtitle = computed(() => {
  const s = storageData.value;
  if (!s.total) return '--';
  const fmt = (b: number) => (b / 1_073_741_824).toFixed(1) + ' GB';
  return `${fmt(s.used)} / ${fmt(s.total)}`;
});
</script>

<style scoped>
.gauge-grid {
  display: grid;
  /* auto-fit：按可用宽度连续换行（4 / 3 / 2 / 1 列），不再依赖断点硬切 */
  grid-template-columns: repeat(auto-fit, minmax(140px, 1fr));
  gap: 16px;
}
/* 原名 .gauge-card —— 它并不是一张卡，只是仪表 + 副标题的居中单元格，改名避免与卡壳混淆 */
.gauge-cell {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
}
.gauge-subtitle {
  font-size: 12px;
  color: var(--text-muted);
  text-align: center;
}

@media (max-width: 768px) {
  /* auto-fit + minmax(140px) 在 360px 手机上恰好挤成 2 列 ~142px，环塞得下但副标题会折行。
     显式锁 2 列并收紧间距，排布可预期；4 个仪表刚好两行。 */
  .gauge-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 12px;
  }
}
</style>
