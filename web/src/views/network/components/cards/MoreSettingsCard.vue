<template>
  <GridCard title="更多设置">
    <div class="entry-grid">
      <button class="entry-tile sub-panel" @click="emit('open-mode')">
        <span class="entry-icon"
          ><n-icon :size="18"><SwapHorizontalOutline /></n-icon
        ></span>
        <span class="entry-text">
          <span class="entry-title">网络模式</span>
          <span class="entry-desc">{{ modeLabel }}</span>
        </span>
      </button>
      <button class="entry-tile sub-panel" @click="emit('open-band')">
        <span class="entry-icon"
          ><n-icon :size="18"><LockClosedOutline /></n-icon
        ></span>
        <span class="entry-text">
          <span class="entry-title">频段锁定</span>
          <span class="entry-desc">锁定 LTE / NR 频段</span>
        </span>
      </button>
      <button class="entry-tile sub-panel" @click="emit('open-speed')">
        <span class="entry-icon"
          ><n-icon :size="18"><SpeedometerOutline /></n-icon
        ></span>
        <span class="entry-text">
          <span class="entry-title">网速测试</span>
          <span class="entry-desc">下行 / 上行速率</span>
        </span>
      </button>
      <button class="entry-tile sub-panel" @click="emit('open-sleep')">
        <span class="entry-icon"
          ><n-icon :size="18"><MoonOutline /></n-icon
        ></span>
        <span class="entry-text">
          <span class="entry-title">休眠定时</span>
          <span class="entry-desc">{{ sleepTime > 0 ? `${sleepTime} 分钟后休眠` : '不休眠' }}</span>
        </span>
      </button>
      <button class="entry-tile sub-panel" @click="emit('open-module')">
        <span class="entry-icon"
          ><n-icon :size="18"><HardwareChipOutline /></n-icon
        ></span>
        <span class="entry-text">
          <span class="entry-title">WiFi 模块信息</span>
          <span class="entry-desc">设备上报的模块字段</span>
        </span>
      </button>
      <button class="entry-tile sub-panel" @click="emit('open-cell')">
        <span class="entry-icon"
          ><n-icon :size="18"><CellularOutline /></n-icon
        ></span>
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
/* 描边/内距/圆角/底色走 main.css 的全局 .sub-panel */
.entry-tile {
  display: flex;
  align-items: center;
  gap: 12px;
  min-height: 72px;
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

@media (max-width: 768px) {
  /* 2 列在 298px 卡内每格 143px，扣掉 34px 图标与 12px 间距后文字只剩 ~97px，
     而「服务小区 / 邻区 / 锁定」「锁定 LTE / NR 频段」「设备上报的模块字段」
     这些描述都是 108~130px ⇒ 全部被省略号吃掉半句，等于看不到这个入口是干什么的。
     窄屏折单列：整宽 298px 足够放下最长那条。
     （原注释说「断点统一由 Slice 2 处理」，但那个统一断点从未落地到本文件。） */
  .entry-grid {
    grid-template-columns: 1fr;
    gap: 8px;
  }
  /* 单列后不需要靠 min-height 对齐左右两格，收紧一点免得 6 个 tile 把卡片拉太长 */
  .entry-tile {
    min-height: 60px;
  }
}
</style>
