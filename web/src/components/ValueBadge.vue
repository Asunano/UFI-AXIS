<template>
  <span
    class="value-badge"
    :class="[`badge-${size}`, `badge-${variant}`, { 'badge-tint-self': selfTinted }]"
    :style="badgeStyle"
  >
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
  // tinted 且传了 color：底色由 `.badge-tint-self` 用 currentColor 叠出来（理由见 style 里的注释），
  // 这里不给 background，否则会盖掉那层叠色。
  return {
    background: props.color ? undefined : 'var(--accent-color-light)',
    color: props.color || 'var(--accent-color)',
  };
});

/**
 * 需要「底色跟着文字色走」的场景：tinted + 传了 color。
 *
 * 2026-10-21 修：原来的 `tintedBg` 只会把 **hex** 换算成 `rgba(r,g,b,0.1)`，
 * 其余一律退回 `--accent-color-light`。而调用方传的可能是 CSS 变量
 * （`var(--warning)`，以及 A1 把 `signalColor()` 改成返回 `var(--signal-*)` 之后
 * 的监控页信号徽标）—— 那些分支就落成「橙字绿底」，且换肤后底色永远不跟。
 * 变量值要到运行时才由浏览器解析，JS 里算不出来，所以改由 CSS 的 `currentColor`
 * 低透明度叠层实现（见 `.badge-tint-self`），hex 与 `var()` 因此走同一条路径。
 */
const selfTinted = computed(() => props.variant === 'tinted' && !!props.color);
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
/* ── 自染色底（tinted 且传了 color）──
   底色 = 「文字色 × 10%」。用 currentColor 叠层而不是在 JS 里算 rgba：
   `color` 可能是 hex，也可能是 `var(--warning)` / `var(--signal-weak)` 这类 CSS 变量 ——
   后者的具体值只有浏览器到运行时才知道，JS 里只能猜（原来就猜成了 `--accent-color-light`，
   于是橙字配绿底，且换肤后底色永远不跟）。这里让底色直接继承文字色，两种形态一条路径。

   · `isolation: isolate` 给本元素建独立层叠上下文，`z-index: -1` 的伪元素才会退到
     **本元素的文字之下**、而不是整页背景之下（否则底色会被卡片背景遮住）；
   · `border-radius: inherit` 跟着胶囊令牌（--radius-pill）走，不另写一份圆角。 */
.value-badge.badge-tint-self {
  position: relative;
  isolation: isolate;
}
.value-badge.badge-tint-self::before {
  content: '';
  position: absolute;
  inset: 0;
  z-index: -1;
  border-radius: inherit;
  background: currentColor;
  opacity: 0.1;
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
