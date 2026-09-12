<template>
  <GridCard :title="title">
    <template #extra>
      <ValueBadge v-if="badge != null" :value="badge" :unit="unit" :color="color" size="sm" />
    </template>
    <n-spin :show="loading">
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
  /** 已格式化好的当前值；null / undefined = 无数据，不显示徽标 */
  badge?: string | null;
  unit?: string;
  color?: string;
}>();
</script>

<style scoped>
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
