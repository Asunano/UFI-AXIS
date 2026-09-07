<template>
  <GridCard title="更多设置">
    <div class="entry-grid">
      <button class="entry-tile" @click="emit('open-mode')">
        <span class="entry-icon"><n-icon :size="18"><SwapHorizontalOutline /></n-icon></span>
        <span class="entry-text">
          <span class="entry-title">网络模式</span>
          <span class="entry-desc">{{ modeLabel }}</span>
        </span>
      </button>
      <button class="entry-tile" @click="emit('open-band')">
        <span class="entry-icon"><n-icon :size="18"><LockClosedOutline /></n-icon></span>
        <span class="entry-text">
          <span class="entry-title">频段锁定</span>
          <span class="entry-desc">锁定 LTE / NR 频段</span>
        </span>
      </button>
      <button class="entry-tile" @click="emit('open-speed')">
        <span class="entry-icon"><n-icon :size="18"><SpeedometerOutline /></n-icon></span>
        <span class="entry-text">
          <span class="entry-title">网速测试</span>
          <span class="entry-desc">下行 / 上行速率</span>
        </span>
      </button>
      <button class="entry-tile" @click="emit('open-sleep')">
        <span class="entry-icon"><n-icon :size="18"><MoonOutline /></n-icon></span>
        <span class="entry-text">
          <span class="entry-title">休眠定时</span>
          <span class="entry-desc">{{ sleepTime > 0 ? `${sleepTime} 分钟后休眠` : '不休眠' }}</span>
        </span>
      </button>
      <button class="entry-tile" @click="emit('open-module')">
        <span class="entry-icon"><n-icon :size="18"><HardwareChipOutline /></n-icon></span>
        <span class="entry-text">
          <span class="entry-title">WiFi 模块信息</span>
          <span class="entry-desc">设备上报的模块字段</span>
        </span>
      </button>
      <button class="entry-tile" @click="emit('open-cell')">
        <span class="entry-icon"><n-icon :size="18"><CellularOutline /></n-icon></span>
        <span class="entry-text">
          <span class="entry-title">基站信息</span>
          <span class="entry-desc">服务小区 / 邻区 / 锁定</span>
        </span>
      </button>
    </div>
  </GridCard>
</template>

<script setup lang="ts">
import {
  SwapHorizontalOutline,
  LockClosedOutline,
  SpeedometerOutline,
  MoonOutline,
  HardwareChipOutline,
  CellularOutline,
} from '@vicons/ionicons5';
import GridCard from '@/components/GridCard.vue';

defineProps<{
  modeLabel: string;
  sleepTime: number;
}>();

const emit = defineEmits<{
  'open-mode': [];
  'open-band': [];
  'open-speed': [];
  'open-sleep': [];
  'open-module': [];
  'open-cell': [];
}>();
</script>

<style scoped>
/* 2 列 × 3 行固定布局（与上方 NetworkInfo/WiFi 热度等宽）。
   min-height 72px 让所有 tile 等高（无论 desc 长度）。
   删原 768 二次重排（断点统一由 Slice 2 处理）。 */
.entry-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
}
.entry-tile {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px 14px;
  min-height: 72px;
  border-radius: var(--radius-sm);
  border: 1px solid var(--border-subtle);
  background: var(--surface-elevated);
  cursor: pointer;
  text-align: left;
  font: inherit;
  color: inherit;
  transition:
    border-color 0.15s,
    background 0.15s;
}
.entry-tile:hover {
  border-color: var(--accent-color);
  background: var(--surface-hover);
}
.entry-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 34px;
  height: 34px;
  flex-shrink: 0;
  border-radius: var(--radius-sm);
  background: var(--accent-color-light);
  color: var(--accent-color);
}
.entry-text {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}
.entry-title {
  font-size: 14px;
  font-weight: 600;
  color: var(--text-primary);
}
.entry-desc {
  font-size: 12px;
  color: var(--text-muted);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  line-clamp: 1;
}
</style>
