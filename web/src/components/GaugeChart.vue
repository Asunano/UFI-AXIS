<template>
  <svg :width="size" :height="size / 2 + 20" :viewBox="`0 0 ${size} ${size / 2 + 20}`">
    <!-- 轨道 -->
    <path :d="arcPath" fill="none" :stroke="trackColor" :stroke-width="strokeWidth" stroke-linecap="round" />
    <!-- 进度弧 -->
    <path
      :d="arcPath"
      fill="none"
      :stroke="resolvedColor"
      :stroke-width="strokeWidth"
      stroke-linecap="round"
      :stroke-dasharray="`${arcLength} ${circumference}`"
      :stroke-dashoffset="`${arcLength * (1 - ratio)}`"
      class="gauge-progress"
    />
    <!-- 中心文字 -->
    <text :x="size / 2" :y="size / 2 - 4" text-anchor="middle" class="gauge-value">{{ computedDisplay }}</text>
    <!-- 非百分比档（max ≠ 100）时数值本身已经带上 unit（见 computedDisplay），这里不再重复一行 -->
    <text v-if="unit && !unitInlined" :x="size / 2" :y="size / 2 + 12" text-anchor="middle" class="gauge-unit">
      {{ unit }}
    </text>
    <text v-if="label" :x="size / 2" :y="size / 2 + 28" text-anchor="middle" class="gauge-label">{{ label }}</text>
  </svg>
</template>

<script setup lang="ts">
import { computed } from 'vue';

const props = withDefaults(
  defineProps<{
    value: number;
    max?: number;
    label?: string;
    unit?: string;
    size?: number;
    color?: string;
    thresholds?: [number, string][];
    trackColor?: string;
    strokeWidth?: number;
    displayValue?: string;
  }>(),
  {
    max: 100,
    size: 120,
    color: 'auto',
    trackColor: 'var(--border-subtle)',
    strokeWidth: 10,
  }
);

/**
 * `value` 声明为必填 number，但调用方在「还没取到数」时可能传 undefined / NaN ——
 * 原先 `Math.round(NaN)` 会直接在表盘中央印一个 `NaN`。现在统一走 `--`。
 */
const hasValue = computed(() => Number.isFinite(props.value));
/** `max` 为 0 / 非数时按 100 处理：除 0 得 Infinity，clamp 后等于伪造满档 */
const safeMax = computed(() => (Number.isFinite(props.max) && props.max > 0 ? props.max : 100));

const ratio = computed(() => (hasValue.value ? Math.min(1, Math.max(0, props.value / safeMax.value)) : 0));

/**
 * 数值文本里已经并进去的单位。
 *
 * `max = 100` 是百分比语义，数字自带 `%`（与 unit 无关，保持各调用点现有观感）；
 * 其余档位是绝对量，若只印一个裸数字（如「45」）读者无从判断量纲，
 * 所以把 `unit` 拼进数值文本 —— 此时下面那行小字 unit 不再重复渲染。
 */
const unitInlined = computed(() => safeMax.value !== 100 && !!props.unit);
const computedDisplay = computed(() => {
  if (props.displayValue) return props.displayValue;
  if (!hasValue.value) return '--';
  if (safeMax.value === 100) return `${Math.round(props.value)}%`;
  return props.unit ? `${Math.round(props.value)} ${props.unit}` : `${Math.round(props.value)}`;
});

// 半圆弧参数
const cx = computed(() => props.size / 2);
const cy = computed(() => props.size / 2);
const r = computed(() => props.size / 2 - props.strokeWidth / 2 - 4);
const circumference = computed(() => Math.PI * r.value);
const arcPath = computed(() => {
  const c = cx.value;
  const y = cy.value;
  const radius = r.value;
  return `M ${c - radius} ${y} A ${radius} ${radius} 0 0 1 ${c + radius} ${y}`;
});
const arcLength = computed(() => circumference.value);

// 颜色解析
const resolvedColor = computed(() => {
  if (props.color && props.color !== 'auto') return props.color;
  if (props.thresholds?.length) {
    for (const [threshold, color] of [...props.thresholds].reverse()) {
      if (props.value >= threshold) return color;
    }
    return props.thresholds[0]?.[1] || 'var(--success)';
  }
  // 默认阈值：绿→黄→红
  const pct = ratio.value * 100;
  if (pct >= 80) return 'var(--error)';
  if (pct >= 60) return 'var(--warning)';
  return 'var(--success)';
});
</script>

<style scoped>
.gauge-progress {
  transition:
    stroke-dashoffset 0.6s ease,
    stroke 0.3s ease;
}
.gauge-value {
  font-size: 22px;
  font-weight: 700;
  fill: var(--text-primary);
}
.gauge-unit {
  font-size: var(--font-sm);
  fill: var(--text-muted);
}
.gauge-label {
  font-size: var(--font-sm);
  fill: var(--text-secondary);
}
</style>
