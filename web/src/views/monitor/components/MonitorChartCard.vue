<template>
  <GridCard :title="title">
    <template #extra>
      <ValueBadge v-if="badge != null" :value="badge" :unit="unit" :color="color" size="sm" />
    </template>
    <n-spin :show="loading">
      <!--
        失败条：历史接口失败时不能只留一张空图 —— 那与「这段时间没有数据」长得一样。
        曲线保留上一次的结果（父组件不清空），这里明说「可能不是最新的」并给重试。
      -->
      <div v-if="failed" class="chart-failed">
        <span class="chart-failed-text">数据读取失败，曲线可能不是最新的</span>
        <n-button size="tiny" quaternary type="primary" @click="emit('retry')">重试</n-button>
      </div>
      <div class="chart-container">
        <v-chart :option="option" autoresize />
      </div>
    </n-spin>
  </GridCard>
</template>

<script setup lang="ts">
/**
 * 单张监控折线图卡片。
 *
 * 6 张图原来在 MonitorView 里各写一份「GridCard + ValueBadge + n-spin + v-chart」，
 * 除了标题、徽标和 option 完全一样。徽标值由父组件格式化后传字符串进来：
 * 各指标的小数位和单位规则不同（%、dBm、MB/s），放进本组件只会变成一堆分支。
 *
 * ECharts 的按需注册放在这里而不是父组件：图只在这个组件里渲染，
 * 注册跟着渲染方走，父组件就不必再 import echarts 的任何东西。
 */
import GridCard from '@/components/GridCard.vue';
import ValueBadge from '@/components/ValueBadge.vue';

import VChart from 'vue-echarts';
import { use } from 'echarts/core';
import { CanvasRenderer } from 'echarts/renderers';
import { LineChart } from 'echarts/charts';
import { GridComponent, TooltipComponent, LegendComponent, TitleComponent } from 'echarts/components';

use([CanvasRenderer, LineChart, GridComponent, TooltipComponent, LegendComponent, TitleComponent]);

defineProps<{
  title: string;
  option: Record<string, any>;
  loading: boolean;
  /** 本指标的历史取数失败（与「没有数据」区分，见计划 §6.10 B9-1） */
  failed?: boolean;
  /** 已格式化好的当前值；null / undefined = 无数据，不显示徽标 */
  badge?: string | null;
  unit?: string;
  color?: string;
}>();

const emit = defineEmits<{ (e: 'retry'): void }>();
</script>

<style scoped>
.chart-failed {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: var(--space-2);
  margin-bottom: var(--space-2);
  padding: 4px 10px;
  border-radius: var(--radius-sm);
  background: var(--error-light);
}

.chart-failed-text {
  font-size: var(--font-sm);
  color: var(--error);
}

.chart-container {
  height: 280px;
  width: 100%;
}

@media (max-width: 768px) {
  .chart-container {
    height: 220px;
  }
}
</style>
