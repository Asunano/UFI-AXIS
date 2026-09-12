<template>
  <div class="action-badge" :class="'action-' + actionType" :style="{ width: px, height: px }">
    <n-icon :size="20"><component :is="getActionIconComp(actionType)" /></n-icon>
  </div>
</template>

<script setup lang="ts">
/**
 * 动作类型的图标 + 配色方块。定时任务卡与规则卡的「动作」节点共用同一份。
 *
 * 为什么要独立成组件：这 10 组配色原来是 20 条写死的 hex（`.action-*` 与
 * `.rule-action.action-*` 各一份逐字拷贝，且只有浅色档，暗色下浅底深字压在深色卡上）。
 * 2026-09-08 先合并成一组选择器 + `--cat-*` 令牌，但那组规则必须带
 * `.rule-action.action-x` 这个第二选择器才能压过 `.rule-action` 的兜底背景 ——
 * 一个纯粹因为「两个地方共用一套类名」而存在的特异性把戏。
 *
 * 拆卡时如果把这组 CSS 复制进两个 SFC，就等于回到两份拷贝；提到 main.css 又会把
 * 业务语义（动作类型）塞进设计系统层。收成一个组件后，配色只有这一处，
 * 兜底与覆盖在同一个 scoped 块里按源码顺序生效，那个特异性把戏也一并消失。
 */
import { computed } from 'vue';
import { getActionIconComp } from '../tasksShared';

const props = withDefaults(defineProps<{ actionType: string; size?: number }>(), { size: 40 });

const px = computed(() => `${props.size}px`);
</script>

<style scoped>
.action-badge {
  border-radius: 10px;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  /* 未知动作类型的兜底；下面的 .action-* 同特异性、位置在后，命中时覆盖它 */
  background: var(--border-subtle);
  color: var(--text-secondary);
}

/* 取值全部走 main.css 的分类色令牌（`--cat-*`），暗色档在那里一并定义 */
.action-data_toggle {
  background: var(--cat-green-bg);
  color: var(--cat-green-fg);
}
.action-wifi_toggle {
  background: var(--cat-blue-bg);
  color: var(--cat-blue-fg);
}
.action-airplane_toggle {
  background: var(--cat-red-bg);
  color: var(--cat-red-fg);
}
.action-reboot {
  background: var(--cat-orange-bg);
  color: var(--cat-orange-fg);
}
.action-shutdown {
  background: var(--cat-brown-bg);
  color: var(--cat-brown-fg);
}
.action-led_toggle {
  background: var(--cat-yellow-bg);
  color: var(--cat-yellow-fg);
}
.action-performance_mode {
  background: var(--cat-purple-bg);
  color: var(--cat-purple-fg);
}
.action-roaming_toggle {
  background: var(--cat-teal-bg);
  color: var(--cat-teal-fg);
}
.action-network_mode {
  background: var(--cat-indigo-bg);
  color: var(--cat-indigo-fg);
}
.action-custom_shell {
  background: var(--cat-neutral-bg);
  color: var(--cat-neutral-fg);
}
</style>
