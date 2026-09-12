<template>
  <span class="value-badge" :class="[`badge-${size}`, `badge-${variant}`]" :style="badgeStyle">
    <span v-if="label" class="badge-label">{{ label }}</span>
    <span class="badge-value">{{ value }}{{ unit || '' }}</span>
  </span>
</template>

<script setup lang="ts">
import { computed } from 'vue';

const props = withDefaults(
  defineProps<{
    label?: string;
    value: string | number;
    unit?: string;
    color?: string;
    size?: 'sm' | 'md' | 'lg';
    /**
     * tinted：主题色淡底（默认，监控页在用）。
     * outline：中性底 + 描边，用于「读数条」这类要靠数值本身说话、不想被色块抢注意力的场景
     * （仪表盘的 RSRP/SINR/当月收发原本各写了一份 999px 胶囊，现统一收进这里）。
     */
    variant?: 'tinted' | 'outline';
  }>(),
  {
    size: 'md',
    variant: 'tinted',
  }
);

const badgeStyle = computed(() => {
  if (props.variant === 'outline') {
    // 中性底交给 CSS 类，这里只负责「有 color 就染数值」——没给 color 时用正文色
    return { color: props.color || 'var(--text-primary)' };
  }
  return { background: tintedBg.value, color: props.color || 'var(--accent-color)' };
});

const tintedBg = computed(() => {
  const c = props.color;
  // 不传 color 时数值取 var(--accent-color)，底色也必须走同一个令牌 ——
  // 原来这里兜底写死 #2080f0（蓝），主色改绿后就成了「绿字蓝底」
  if (!c) return 'var(--accent-color-light)';
  // 将 hex 转为淡色背景
  if (c.startsWith('#')) {
    const r = parseInt(c.slice(1, 3), 16);
    const g = parseInt(c.slice(3, 5), 16);
    const b = parseInt(c.slice(5, 7), 16);
    return `rgba(${r}, ${g}, ${b}, 0.1)`;
  }
  return 'var(--accent-color-light)';
});
</script>

<style scoped>
.value-badge {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  /* 原来写死 20px，与别处的 999px 是两套胶囊写法；统一到令牌 */
  border-radius: var(--radius-pill);
  padding: 3px 10px;
  font-weight: 500;
  white-space: nowrap;
}
.badge-sm {
  font-size: 11px;
  padding: 2px 8px;
}
.badge-md {
  font-size: 13px;
  padding: 3px 12px;
}
/* lg：读数条尺寸（数值比标签大一档、字重更重），供仪表盘的信号 / 流量读数用 */
.badge-lg {
  padding: 6px 14px;
  align-items: baseline;
  gap: 6px;
}
.badge-lg .badge-label {
  font-size: 11px;
}
.badge-lg .badge-value {
  font-size: 14px;
  font-weight: 700;
}
.badge-outline {
  background: var(--surface-elevated);
  border: 1px solid var(--border-subtle);
}
.badge-outline .badge-label {
  color: var(--text-muted);
  opacity: 1;
}
.badge-label {
  opacity: 0.7;
}
.badge-value {
  font-variant-numeric: tabular-nums;
}
</style>
