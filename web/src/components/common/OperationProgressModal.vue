<!--
  进行中操作弹窗（全仓唯一一份）。由 useApiOperation 驱动，本组件只管渲染。
  running=圆环转圈；success=对勾定格 300ms 自动关；failed=叉+原因+重试。
  hidden 态不渲染（用户点了后台进行），但 useApiOperation 的轮询照常。
-->
<template>
  <n-modal
    :show="visible"
    preset="card"
    :title="title"
    style="width: 360px; max-width: calc(100vw - 32px)"
    :mask-closable="false"
    :close-on-esc="state !== 'running'"
  >
    <div class="op-progress">
      <n-icon size="52" :color="iconColor" :class="{ spinning: state === 'running' }">
        <svg viewBox="0 0 24 24" fill="none">
          <circle cx="12" cy="12" r="9" stroke="currentColor" stroke-width="2.4"
                  :opacity="state === 'running' ? 0.18 : 0.35" />
          <path v-if="state === 'running'"
                d="M12 3a9 9 0 0 1 9 9" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" />
          <path v-else-if="state === 'success'"
                d="M7.5 12.5l3 3 6-6.5" stroke="currentColor" stroke-width="2.4"
                stroke-linecap="round" stroke-linejoin="round" />
          <path v-else-if="state === 'failed'"
                d="M8.5 8.5l7 7M15.5 8.5l-7 7" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" />
        </svg>
      </n-icon>
      <div class="op-text">
        <div class="op-primary">{{ primaryText }}</div>
        <n-text v-if="state === 'running'" :depth="3" class="op-hint">{{ hint }}</n-text>
        <n-text v-else-if="state === 'failed'" type="warning" :depth="2" class="op-hint">
          设备端可能仍在后台继续，可稍后刷新查看实际状态
        </n-text>
      </div>
    </div>
    <template #footer>
      <div class="op-footer">
        <n-button v-if="state === 'running'" size="small" quaternary @click="toBackground">后台进行</n-button>
        <template v-else-if="state === 'failed'">
          <n-button size="small" quaternary @click="close">关闭</n-button>
          <n-button v-if="retry" size="small" type="primary" @click="retry">重试</n-button>
        </template>
        <!-- success 态无按钮：300ms 后自动关闭 -->
      </div>
    </template>
  </n-modal>
</template>

<script setup lang="ts">
import { computed } from 'vue';
import { NModal, NButton, NIcon, NText } from 'naive-ui';
import type { OperationDialogState } from '@/composables/useApiOperation';

const props = defineProps<{
  state: OperationDialogState;
  title: string;
  runningText: string;
  successText: string;
  hint?: string;
  failText?: string;
  retry?: () => void;
  close: () => void;
  toBackground: () => void;
}>();

// hidden（后台进行）时不渲染；idle 关闭；其余显示
const visible = computed(() => props.state.kind !== 'idle' && props.state.kind !== 'hidden');
const state = computed(() => props.state.kind);
const primaryText = computed(() =>
  state.value === 'running' ? props.runningText
  : state.value === 'success' ? props.successText
  : props.failText || `${props.title}未完成`
);
const iconColor = computed(() =>
  state.value === 'success' ? '#18a058' : state.value === 'failed' ? '#d03050' : undefined
);
</script>

<style scoped>
.op-progress { display: flex; flex-direction: column; align-items: center; gap: 14px; padding: 18px 0 10px; }
.spinning { animation: op-spin 0.9s linear infinite; }
@keyframes op-spin { to { transform: rotate(360deg); } }
.op-text { display: flex; flex-direction: column; align-items: center; gap: 4px; text-align: center; }
.op-primary { font-size: 14px; color: var(--text-primary); }
.op-hint { font-size: 12px; max-width: 280px; }
.op-footer { display: flex; justify-content: flex-end; gap: 8px; }
</style>
