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
/* 头部：标题在左、动作在右。
   `flex-wrap: wrap` + 标题行 `min-width: 0` 是给窄屏的（2026-09-19 补）：
   原先两者都没有，于是 360px 下「标题 + 状态标签 + 3 个动作按钮」这类头部
   （流量使用情况卡、折线图卡、网络信息卡）既不换行也不能省略，只能互相挤压 ——
   标题被压成一两个字，`#extra` 里的按钮文案也糊在一起。
   现在：先让 `#extra` 整块掉到第二行；真的还放不下时标题再省略号截断。 */
.grid-card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 6px 8px;
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
  /* 不给 min-width:0，flex 子项的最小尺寸是内容宽，标题会顶着 #extra 不肯让位 */
  min-width: 0;
}
.grid-card-title {
  font-size: 14px;
  font-weight: 600;
  color: var(--text-primary);
  /* 换行后仍放不下时才截断（比把 #extra 挤出卡片好） */
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
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

/* ── 底部：density 控制内边距 ──
   `flex-wrap: wrap` 是给窄屏的（2026-09-19 补）：原先没有，网络信息卡那种
   3 颗按钮的 footer（开启飞行模式 / 关闭飞行模式 / 拨号连接 ≈ 300px）在 298px 的
   卡内既不换行也无处可缩，直接溢出卡片右边界。 */
.grid-card-footer {
  padding: 12px 16px;
  border-top: 1px solid var(--border-subtle);
  display: flex;
  justify-content: flex-end;
  flex-wrap: wrap;
  gap: 8px;
}
.grid-card--compact .grid-card-footer {
  padding: 8px 12px;
}
.grid-card--spacious .grid-card-footer {
  padding: 16px 20px;
}

/* ── 窄屏收紧内边距 ──
   360px 视口下正文区只有 332px（DefaultLayout 的 14px 内距），卡片再吃掉
   左右各 16px 就只剩 298px。把水平内距降到 12px 可以还回 8px ——
   在「MAC 地址被截断」「日志正文只剩 80px」这类场景里这 8px 是实质性的。
   纵向不动：那是行距节奏，压了会让卡片显得拥挤。 */
@media (max-width: 768px) {
  .grid-card-header {
    padding: 12px 12px 0;
  }
  .grid-card-body {
    padding: 10px 12px 12px;
  }
  .grid-card--compact .grid-card-body {
    padding: 8px 10px 10px;
  }
  .grid-card--spacious .grid-card-body {
    padding: 12px 14px 14px;
  }
  .grid-card-footer {
    padding: 10px 12px;
  }
  .grid-card--spacious .grid-card-footer {
    padding: 12px 14px;
  }
  /* 动作区在窄屏改为左对齐并允许占满整行：右对齐时换行会排成阶梯状 */
  .grid-card-extra {
    justify-content: flex-start;
  }
}
</style>
