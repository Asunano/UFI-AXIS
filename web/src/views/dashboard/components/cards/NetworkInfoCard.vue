<template>
  <!-- 横向铺开时用「标签在上、值在下」的格子，比左右对齐的行更好读 -->
  <GridCard class="panel-card" title="网络信息">
    <template #extra>
      <n-tag :type="netStatus?.network?.is_connected ? 'success' : 'error'" size="small" :bordered="false">
        {{ netStatus?.network?.is_connected ? '已连接' : '未连接' }}
      </n-tag>
      <n-button size="small" quaternary type="primary" @click="emit('open-mode')">网络模式</n-button>
      <n-button size="small" quaternary type="primary" @click="emit('open-band')">频段锁定</n-button>
    </template>

    <div class="stat-grid">
      <div class="stat-cell">
        <span class="stat-label">网络类型</span>
        <span class="stat-value">{{ networkTypeWithBand(netStatus?.network_type || '', signal) }}</span>
      </div>
      <div class="stat-cell">
        <span class="stat-label">运营商</span>
        <span class="stat-value">{{ netStatus?.operator || '--' }}</span>
      </div>
      <!-- 开关直接当作信息格的「值」：不额外增加卡片高度，网络卡才不会比流量卡高一截 -->
      <div class="stat-cell">
        <span class="stat-label">移动数据</span>
        <n-switch
          size="small"
          :value="!!netStatus?.mobile_data"
          :loading="mobileDataSaving"
          @update:value="(v: boolean) => emit('set-mobile-data', v)"
        />
      </div>
      <div class="stat-cell">
        <span class="stat-label">数据漫游</span>
        <span class="stat-value">{{ roamingEnabled ? '开启' : '关闭' }}</span>
      </div>
      <div class="stat-cell">
        <span class="stat-label">连接模式</span>
        <span class="stat-value">{{ connectionMode === 'auto' ? '自动拨号' : '手动拨号' }}</span>
      </div>
    </div>
  </GridCard>
</template>

<script setup lang="ts">
/**
 * 网络信息卡。
 *
 * 网络状态与信号读 dashboardStore（单例，与父组件同一份）；
 * 漫游 / 拨号模式 / 移动数据的 loading 只能走 props ——
 * 它们的真源是 `useNetworkControls()`，而那个 composable **每次调用都新建一套 ref**
 * （不是模块级单例）。在本组件里再调一次会拿到互不相干的第二份状态：
 * 父组件轮询刷新的是它自己那份，这里永远显示初始值。
 *
 * 同理，移动数据开关只 emit，写操作留在持有 composable 的父组件。
 */
import { computed } from 'vue';
import { useDashboardStore } from '@/stores/dashboard';
import { networkTypeWithBand, get } from '@/composables/utils';
import GridCard from '@/components/GridCard.vue';

defineProps<{
  roamingEnabled: boolean;
  connectionMode: string;
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
</script>
