<template>
  <div class="ring-gauge" :style="{ width: size + 'px', height: size + 'px' }">
    <svg :width="size" :height="size" :viewBox="`0 0 ${size} ${size}`">
      <g :transform="`rotate(-90 ${size / 2} ${size / 2})`">
        <!-- 背景圆环 -->
        <circle
          class="ring-track"
          :cx="size / 2"
          :cy="size / 2"
          :r="radius"
          fill="none"
          :stroke="trackColor"
          :stroke-width="strokeWidth"
        />
        <!-- 进度圆环 -->
        <circle
          class="ring-progress"
          :cx="size / 2"
          :cy="size / 2"
          :r="radius"
          fill="none"
          :stroke="resolvedColor"
          :stroke-width="strokeWidth"
          :stroke-linecap="round ? 'round' : 'butt'"
          :style="progressStyle"
        />
      </g>
    </svg>
    <div class="ring-center">
      <div class="ring-value-row">
        <span class="ring-value" :style="{ color: resolvedColor }">{{ displayValue }}</span>
        <span v-if="unit && hasValue" class="ring-unit">{{ unit }}</span>
      </div>
      <div v-if="label" class="ring-label-below">{{ label }}</div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch, nextTick } from 'vue';

const props = withDefaults(
  defineProps<{
    value: number;
    max?: number;
    label?: string;
    unit?: string;
    size?: number;
    strokeWidth?: number;
    color?: string;
    round?: boolean;
    trackColor?: string;
  }>(),
  {
    max: 100,
    size: 140,
    strokeWidth: 10,
    // 两个现有调用点都显式传 color，这个默认值实际用不到；给 var() 而不是写死 hex，
    // 是为了以后有人不传时仍然跟着换肤走（SVG 的 stroke 认 CSS 变量）。
    color: 'var(--accent-color)',
    round: true,
    trackColor: 'var(--border-subtle)',
  }
);

/**
 * `value` 声明上是必填 number，但调用方在「还没取到数」时可能传进来 undefined / NaN。
 * 原先直接 `toFixed()`，环里就会印出一个 `NaN` —— 那既不是 0% 也不是「没取到」。
 */
const hasValue = computed(() => Number.isFinite(props.value));
/** `max` 为 0 或非数时按 100 处理：除 0 会得到 Infinity，被 clamp 成满环，等于伪造 100% */
const safeMax = computed(() => (Number.isFinite(props.max) && props.max > 0 ? props.max : 100));

const ratio = computed(() => (hasValue.value ? Math.min(1, Math.max(0, props.value / safeMax.value)) : 0));
const radius = computed(() => props.size / 2 - props.strokeWidth / 2 - 4);
const circumference = computed(() => 2 * Math.PI * radius.value);

const displayValue = computed(() => {
  if (!hasValue.value) return '--';
  if (props.value >= 1000) return props.value.toFixed(0);
  return props.value.toFixed(props.value % 1 === 0 ? 0 : 2);
});

const resolvedColor = computed(() => props.color);

// 进场动画：从 0 增长到目标 ratio；后续 value 变化也平滑过渡
const animatedRatio = ref(0);
function animateTo(target: number) {
  animatedRatio.value = target;
}

onMounted(() => {
  // 初始为 0，下一帧再到目标值，触发 CSS transition
  animatedRatio.value = 0;
  nextTick(() => {
    requestAnimationFrame(() => {
      animateTo(ratio.value);
    });
  });
});

watch(ratio, (v) => {
  animateTo(v);
});

const progressStyle = computed(() => ({
  'stroke-dasharray': `${circumference.value}px`,
  'stroke-dashoffset': `${circumference.value * (1 - animatedRatio.value)}px`,
}));
</script>

<style scoped>
.ring-gauge {
  position: relative;
  display: inline-flex;
  align-items: center;
  justify-content: center;
}
.ring-track {
  opacity: 0.45;
}
.ring-progress {
  transform-origin: center;
  transition: stroke-dashoffset 1s cubic-bezier(0.22, 1, 0.36, 1);
  will-change: stroke-dashoffset;
}
.ring-center {
  position: absolute;
  inset: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  text-align: center;
}
.ring-value-row {
  display: flex;
  align-items: baseline;
  justify-content: center;
  gap: 4px;
  flex-wrap: nowrap;
  white-space: nowrap;
}
.ring-value {
  font-size: var(--font-xl);
  font-weight: 700;
  line-height: 1;
}
.ring-unit {
  font-size: var(--font-xs);
  color: var(--text-muted);
}
.ring-label-below {
  font-size: var(--font-sm);
  color: var(--text-secondary);
  margin-top: 4px;
}
</style>
