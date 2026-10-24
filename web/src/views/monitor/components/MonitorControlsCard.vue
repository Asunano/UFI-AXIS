<template>
  <GridCard title="系统监控">
    <div class="controls-bar">
      <div class="controls-group">
        <n-button-group>
          <n-button
            v-for="r in TIME_RANGES"
            :key="r.hours"
            :type="hours === r.hours ? 'primary' : 'default'"
            size="small"
            @click="emit('update:hours', r.hours)"
            >{{ r.label }}</n-button
          >
        </n-button-group>
        <n-button size="small" :loading="loading" @click="emit('refresh')">刷新</n-button>
      </div>

      <!--
        页面级动作插槽：本卡只放「与监控设置/取数相关」的控件。
        流量报表这类独立功能由 MonitorView 传入触发按钮（弹窗也归它管），
        免得这张卡去持有自己用不上的弹窗状态。
      -->
      <div v-if="$slots.actions" class="controls-group">
        <slot name="actions" />
      </div>

      <!--
        存储管理 / 监控设置：2026-10-22 从「底部常驻卡 + 控件条内嵌展开区」改成两个弹窗入口。
        两者都是「点开看一眼或调一次就关」的动作，常驻会把首屏图表挤下去。
        单独成一组：窄屏（≤768px）每个组独占一行，这两个入口不会和刷新/告警挤成一条。
      -->
      <div class="controls-group">
        <n-button size="small" @click="emit('open-storage')">存储管理</n-button>
        <!-- 与存储管理同一档按钮样式：quaternary 会在浅色底上退化成纯文字，
             看起来不像可点击入口。 -->
        <n-button size="small" @click="emit('open-settings')">监控设置</n-button>
      </div>
    </div>
  </GridCard>
</template>

<script setup lang="ts">
/**
 * 时间范围 + 页面入口的控件条。
 *
 * 这里只留「取数口径」与「打开哪个弹窗」两类控件：监控偏好（prefs）由 MonitorView
 * 持有并下发到弹窗，本卡不再渲染任何设置项 —— 设置项都在 MonitorSettingsModal 里，
 * 这样才能保证图表的 fillAlpha / fixedYAxis / enabledTypes 与设置用的是同一份状态。
 *
 * `exporting` / `export` 是历史遗留的 CSV 导出接线（模板里已无对应按钮），
 * 本批次不动它，避免牵连 MonitorView 的导出逻辑。
 */
import GridCard from '@/components/GridCard.vue';
import { TIME_RANGES } from '../monitorShared';

defineProps<{
  hours: number;
  /** 任一图表在取数中 —— 只用于刷新按钮的 loading */
  loading: boolean;
  /** CSV 导出进行中（遗留接线，见文件头） */
  exporting?: boolean;
}>();

const emit = defineEmits<{
  (e: 'update:hours', hours: number): void;
  (e: 'refresh'): void;
  (e: 'export'): void;
  (e: 'open-storage'): void;
  (e: 'open-settings'): void;
}>();

/** #actions：页面级动作按钮（当前是 MonitorView 的「流量报表」「告警」入口） */
defineSlots<{ actions?(): any }>();
</script>

<style scoped>
.controls-bar {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: var(--space-2) var(--space-3);
}

.controls-group {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: var(--space-2);
}

/* 桌面端一行放得下（4 档区间 + 刷新 + 报表/告警 + 两个弹窗入口 ≈ 520px）。
   手机端不再让每个 group 独占整行（会把所有按钮挤在左侧、右侧留一大片空白），
   改成 4 列网格：时间档 + 刷新占第一行，报表/告警/存储/监控设置各占一格第二行。 */
@media (max-width: 768px) {
  .controls-bar {
    display: grid;
    grid-template-columns: repeat(4, minmax(0, 1fr));
    gap: 6px;
  }
  .controls-group {
    display: contents;
  }
  .controls-bar > .controls-group > :deep(.n-button) {
    width: 100%;
    min-width: 0;
  }
  .controls-bar > .controls-group:first-child :deep(.n-button-group) {
    grid-column: span 3;
    display: flex;
    width: 100%;
    min-width: 0;
  }
  .controls-bar > .controls-group:first-child :deep(.n-button-group .n-button) {
    flex: 1 1 0;
    min-width: 0;
  }
}
</style>
