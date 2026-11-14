<template>
  <!-- 横向铺开时用「标签在上、值在下」的格子，比左右对齐的行更好读 -->
  <GridCard class="panel-card net-card" title="网络信息">
    <template #extra>
      <n-button size="small" quaternary type="primary" @click="emit('open-mode')">网络模式</n-button>
      <n-button size="small" quaternary type="primary" @click="emit('open-band')">频段锁定</n-button>
      <n-tag :type="netStatus?.network?.is_connected ? 'success' : 'default'" size="small" :bordered="false">
        {{ netStatus?.network?.is_connected ? '已连接' : '未连接' }}
      </n-tag>
      <n-switch
        size="small"
        :value="!!netStatus?.mobile_data"
        :loading="mobileDataSaving"
        aria-label="移动数据"
        title="移动数据"
        @update:value="(v: boolean) => emit('set-mobile-data', v)"
      />
    </template>

    <!-- 2026-11-07 改版（B3）：制式 + 运营商/频段 + 右上信号条，
         参数用「格子」列（与流量卡同一套格子样式：label 小字在上、值在下）。 -->
    <div class="net-hero">
      <div class="net-id">
        <span class="net-type">{{ netStatus?.network_type || '--' }}</span>
        <span class="net-sub">{{ subtitleText }}</span>
      </div>
      <span class="grow"></span>
      <div class="sig-bars" :title="signalTitle" :aria-label="signalTitle">
        <i v-for="n in 5" :key="n" :class="{ on: n <= signalBars }" />
      </div>
    </div>

    <div class="net-cells">
      <div v-for="c in paramCells" :key="c.label" class="net-cell">
        <span class="net-cell-label">{{ c.label }}</span>
        <span class="net-cell-value">{{ c.value }}</span>
      </div>
    </div>
  </GridCard>
</template>

<script setup lang="ts">
/**
 * 网络信息卡。
 *
 * 网络状态与信号读 dashboardStore（单例，与父组件同一份）；
 * 漫游状态 / 移动数据的 loading 只能走 props ——
 * 它们的真源是 `useNetworkControls()`，而那个 composable **每次调用都新建一套 ref**
 * （不是模块级单例）。在本组件里再调一次会拿到互不相干的第二份状态：
 * 父组件轮询刷新的是它自己那份，这里永远显示初始值。
 *
 * 同理，移动数据开关只 emit，写操作留在持有 composable 的父组件。
 */
import { computed } from 'vue';
import { useDashboardStore } from '@/stores/dashboard';
import { get } from '@/composables/utils';
import GridCard from '@/components/GridCard.vue';

defineProps<{
  mobileDataSaving: boolean;
}>();

const emit = defineEmits<{
  (e: 'set-mobile-data', enabled: boolean): void;
  (e: 'open-mode'): void;
  (e: 'open-band'): void;
}>();

const dashboardStore = useDashboardStore();
const netStatus = computed(() => get(dashboardStore.summary, 'network_status', null));
const signal = computed(() => dashboardStore.realtimeSignal);

/** 副标题：运营商 · 频段 · 频宽。缺哪个就少哪段，不留空占位。 */
const subtitleText = computed(() => {
  const sg = signal.value;
  const parts = [
    // 2026-11-07：运营商优先读 signal（3~4.5s 推送，与 App 网络页同源）；
    // network_status 来自 10s summary，只在 signal 还没到时兜底。
    sg?.operator || netStatus.value?.operator,
    sg?.band_label || sg?.band,
    // 频宽不再放副标题：下方参数格已有「频宽 20000 MHz」，这里重复一遍只会把
    // 副标题拉长、在窄卡里被省略号截掉（2026-11-11 用户指定删除）。
  ].filter((x): x is string => !!x);
  return parts.length ? parts.join(' · ') : '--';
});

/**
 * 信号格数（1–5）：按 RSRP 分档，与 `signalLevel()` 同组阈值。
 * 拿不到 RSRP 时返回 0（一格不亮），而不是默认满格误导用户。
 */
const signalBars = computed(() => {
  const r = Number(signal.value?.rsrp);
  if (!Number.isFinite(r) || r === 0) return 0;
  if (r >= -80) return 5;
  if (r >= -90) return 4;
  if (r >= -100) return 3;
  if (r >= -110) return 2;
  return 1;
});
const signalTitle = computed(() => {
  const sg = signal.value;
  const r = Number(sg?.rsrp);
  if (!Number.isFinite(r) || r === 0) return '没有信号读数';
  const sinr = Number(sg?.sinr);
  return `RSRP ${r} dBm${Number.isFinite(sinr) && sinr !== 0 ? ` · SINR ${sinr} dB` : ''}`;
});

/** 四格参数：RSRP / SINR / PCI / 频宽。拿不到显示 `--`，不空着。 */
const paramCells = computed(() => {
  const sg = signal.value;
  const num = (v: unknown, unit = ''): string => {
    const n = Number(v);
    return Number.isFinite(n) && n !== 0 ? `${n}${unit}` : '--';
  };
  return [
    { label: 'RSRP', value: num(sg?.rsrp, ' dBm') },
    { label: 'SINR', value: num(sg?.sinr, ' dB') },
    { label: 'PCI', value: num(sg?.pci) },
    { label: '频宽', value: num(sg?.band_width, ' MHz') },
  ];
});
</script>

<style scoped>
/**
 * 2026-11-07（B3）：制式/信号为主视觉 + 参数格子 + 开关行。
 * 格子与流量卡「今日/剩余/日均」同一套语言（label 小字在上、值在下、1px 边框）——
 * 仪表盘上"参数格"长得一样，用户不用每张卡重新学一遍。
 */
/* 卡片在仪表盘顶栏里被 stretch 到与流量卡同高；本卡内容更矮，
   默认流式排布会把空白全留在卡片底部。改成纵向 flex：
   头部/制式区固定在顶部，参数格子 margin-top:auto 钉到底部，
   与左卡「今日/剩余/日均」同一条底线，底部不再留空。 */
.net-card {
  display: flex;
  flex-direction: column;
  height: 100%;
}
.net-card :deep(.grid-card-body) {
  display: flex;
  flex-direction: column;
  flex: 1;
}
.net-hero {
  display: flex;
  /* 在卡片里吃掉剩余高度：4G/运营商/信号条在垂直方向居中，
     不再全部堆在顶部、把空白留在中间（横屏尤其明显）。 */
  flex: 1 1 auto;
  align-items: center;
  gap: 12px;
  min-height: 64px;
}
.net-id {
  display: flex;
  flex-direction: column;
  gap: 4px;
  min-width: 0;
}
.net-type {
  font-size: 32px;
  font-weight: 700;
  line-height: 1;
  letter-spacing: -0.02em;
  color: var(--text-primary);
}
.net-sub {
  font-size: var(--font-sm);
  color: var(--text-muted);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.grow {
  flex: 1;
}

/* 五格信号条：从矮到高，贴卡片右缘 */
.sig-bars {
  flex: none;
  display: flex;
  align-items: flex-end;
  gap: 3px;
  height: 32px;
  padding-top: 2px;
}
.sig-bars i {
  width: 5px;
  border-radius: 2.5px;
  background: var(--border-subtle);
}
.sig-bars i.on {
  background: var(--accent-color);
}
.sig-bars i:nth-child(1) {
  height: 8px;
}
.sig-bars i:nth-child(2) {
  height: 13px;
}
.sig-bars i:nth-child(3) {
  height: 19px;
}
.sig-bars i:nth-child(4) {
  height: 25px;
}
.sig-bars i:nth-child(5) {
  height: 32px;
}

.net-cells {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 8px;
  /* auto 而不是固定 10px：卡片被拉高时把剩余空间留在参数区上方，
     格子始终贴住卡片底部，与左卡的三格同一条基线。 */
  margin-top: auto;
  padding-top: 10px;
}
.net-cell {
  display: flex;
  flex-direction: column;
  gap: 1px;
  padding: 7px 8px;
  border: 1px solid var(--border-subtle);
  border-radius: 9px;
  background: var(--surface-elevated, var(--card-bg));
  min-width: 0;
  text-align: center;
}
.net-cell-label {
  font-size: 10px;
  color: var(--text-muted);
  white-space: nowrap;
}
.net-cell-value {
  font-size: 12.5px;
  font-weight: 600;
  color: var(--text-primary);
  font-variant-numeric: tabular-nums;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

@media (max-width: 420px) {
  .net-cells {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
</style>
