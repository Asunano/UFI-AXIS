<template>
  <div class="grid-card" :class="[`grid-card--${density}`, { 'grid-card-collapsible': collapsible }]">
    <!-- 顶部 accent 色条 -->
    <div v-if="accent" class="grid-card-accent" :style="{ background: accent }"></div>

    <!-- 头部 -->
    <div
      v-if="title || $slots.title || $slots.extra"
      class="grid-card-header"
      :class="{ 'grid-card-header-clickable': collapsible }"
      @click="collapsible && (collapsed = !collapsed)"
    >
      <div class="grid-card-title-row">
        <!-- #title 供「头部左侧不是一句标题」的卡使用（如仪表盘折线卡的 Tab 按钮组）；
             不传时退化为普通标题文本 -->
        <slot name="title"
          ><span class="grid-card-title">{{ title }}</span></slot
        >
        <span v-if="collapsible" class="grid-card-chevron" :class="{ 'chevron-up': !collapsed }">
          <n-icon :size="14"><ChevronDownOutline /></n-icon>
        </span>
      </div>
      <div v-if="$slots.extra && !collapsible" class="grid-card-extra">
        <slot name="extra"></slot>
      </div>
    </div>

    <!-- 主体：loading 时显示 n-skeleton 占位，避免闪烁 -->
    <div v-show="!collapsed" class="grid-card-body">
      <template v-if="loading">
        <n-skeleton :text="skeletonText" :repeat="skeletonRepeat" class="grid-card-skeleton" />
      </template>
      <template v-else>
        <slot />
      </template>
    </div>

    <!-- 底部 -->
    <div v-if="$slots.footer" class="grid-card-footer">
      <slot name="footer"></slot>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue';
import { ChevronDownOutline } from '@vicons/ionicons5';

const props = withDefaults(
  defineProps<{
    title?: string;
    loading?: boolean;
    accent?: string;
    collapsible?: boolean;
    defaultCollapsed?: boolean;
    /** 密度档：紧凑/默认/宽松（影响 body / footer 内边距）。 */
    density?: 'compact' | 'default' | 'spacious';
    /** 加载占位：行数。content-heavy 卡可调高，简单的可调低。 */
    skeletonRepeat?: number;
    /** 加载占位：是否按文本模式（更轻量）。 */
    skeletonText?: boolean;
  }>(),
  {
    loading: false,
    collapsible: false,
    defaultCollapsed: false,
    density: 'default',
    skeletonRepeat: 3,
    skeletonText: true,
  }
);

const collapsed = ref(props.defaultCollapsed);

defineSlots<{
  default(): any;
  title?(): any;
  extra?(): any;
  footer?(): any;
}>();
</script>

<style scoped>
.grid-card {
  background: var(--card-bg);
  border-radius: var(--radius-md);
  border: 1px solid var(--border-subtle);
  overflow: hidden;
  position: relative;
}
.grid-card-accent {
  height: 3px;
  width: 100%;
}
.grid-card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 14px 16px 0;
}
.grid-card-header-clickable {
  cursor: pointer;
  user-select: none;
}
.grid-card-header-clickable:hover {
  background: var(--surface-hover);
}
.grid-card-title-row {
  display: flex;
  align-items: center;
  gap: 6px;
}
.grid-card-title {
  font-size: 14px;
  font-weight: 600;
  color: var(--text-primary);
}
.grid-card-chevron {
  display: inline-flex;
  transition: transform 0.2s ease;
  color: var(--text-muted);
}
.chevron-up {
  transform: rotate(180deg);
}
.grid-card-extra {
  display: flex;
  align-items: center;
  gap: 8px;
  /* 窄卡（如仪表盘右栏）头部动作可能有 3–4 个，不换行会把标题挤没 */
  flex-wrap: wrap;
  justify-content: flex-end;
}

/* ── 主体：density 控制内边距 ──
   compact: 8/12/12 ; default: 12/16/16 ; spacious: 16/20/20 */
.grid-card-body {
  padding: 12px 16px 16px;
}
.grid-card--compact .grid-card-body {
  padding: 8px 12px 12px;
}
.grid-card--spacious .grid-card-body {
  padding: 16px 20px 20px;
}

/* skeleton 占位给最小高度，避免 loading 期间卡片塌成一行 */
.grid-card-skeleton {
  padding: 4px 0;
}

/* ── 底部：density 控制内边距 ── */
.grid-card-footer {
  padding: 12px 16px;
  border-top: 1px solid var(--border-subtle);
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}
.grid-card--compact .grid-card-footer {
  padding: 8px 12px;
}
.grid-card--spacious .grid-card-footer {
  padding: 16px 20px;
}
</style>
