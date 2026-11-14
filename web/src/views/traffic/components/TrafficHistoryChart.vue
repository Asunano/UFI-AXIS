<template>
  <div class="traffic-chart">
    <v-chart :option="option" autoresize />
  </div>
</template>

<script setup lang="ts">
/**
 * 流量历史的堆叠柱状图。
 *
 * ECharts 的按需注册放在这里而不是页面里：图只在本组件渲染，注册跟着渲染方走，
 * 页面就不必 import echarts 的任何东西（与 MonitorChartCard 同一写法）。
 *
 * 配色在这里解析：canvas 不认 `var(--x)`，必须经 useChartColors() 变成字面量，
 * 而 computed 依赖它就顺带获得「切换暗色/皮肤后自动重画」。
 */
import { computed } from 'vue';
import VChart from 'vue-echarts';
import { use } from 'echarts/core';
import { CanvasRenderer } from 'echarts/renderers';
import { BarChart } from 'echarts/charts';
import { GridComponent, TooltipComponent, LegendComponent } from 'echarts/components';
import { useChartColors } from '@/composables/chartTheme';
import type { TrafficUsageResponse } from '@/api/traffic';
import { trafficHistoryBarOption } from '../trafficHistoryCharts';

use([CanvasRenderer, BarChart, GridComponent, TooltipComponent, LegendComponent]);

const props = defineProps<{ data: TrafficUsageResponse | null }>();

const colors = useChartColors();
const option = computed(() => trafficHistoryBarOption(props.data, colors.value));
</script>

<style scoped>
.traffic-chart {
  height: 300px;
  width: 100%;
}

@media (max-width: 768px) {
  .traffic-chart {
    height: 240px;
  }
}
</style>
