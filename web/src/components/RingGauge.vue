<template>
  <div class="ring-gauge" :style="{ width: size + 'px', height: size + 'px' }">
    <svg :width="size" :height="size" :viewBox="`0 0 ${size} ${size}`">
      <defs>
        <filter :id="gradientId" x="-50%" y="-50%" width="200%" height="200%">
          <feGaussianBlur in="SourceGraphic" stdDeviation="2" result="blur" />
          <feColorMatrix in="blur" type="matrix" values="1 0 0 0 0  0 1 0 0 0  0 0 1 0 0  0 0 0 18 -7" result="goo" />
          <feComposite in="SourceGraphic" in2="goo" operator="atop" />
        </filter>
      </defs>
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
          ref="progressRef"
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
        <span v-if="unit" class="ring-unit">{{ unit }}</span>
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
    color: '#2080f0',
    round: true,
    trackColor: 'var(--border-subtle)',
  }
);

const progressRef = ref<SVGCircleElement | null>(null);
const gradientId = `ring-glow-${Math.random().toString(36).slice(2, 9)}`;

const ratio = computed(() => Math.min(1, Math.max(0, props.value / props.max)));
const radius = computed(() => props.size / 2 - props.strokeWidth / 2 - 4);
const circumference = computed(() => 2 * Math.PI * radius.value);

const displayValue = computed(() => {
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
  font-size: 20px;
  font-weight: 700;
  line-height: 1;
}
.ring-unit {
  font-size: 11px;
  color: var(--text-muted);
}
.ring-label-below {
  font-size: 12px;
  color: var(--text-secondary);
  margin-top: 4px;
}
</style>
