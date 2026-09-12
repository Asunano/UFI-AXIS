<template>
  <n-modal v-model:show="show" preset="card" title="WiFi 休眠定时" style="width: 420px; max-width: calc(100vw - 32px)">
    <n-spin :show="sleepLoading">
      <div class="sleep-body">
        <div class="hint-text">空闲超过设定时长后关闭 WiFi；填 0 表示永不休眠。单位是分钟。</div>
        <n-input-number v-model:value="sleepTime" :min="0" :step="5" size="small" style="width: 180px">
          <template #suffix>分钟</template>
        </n-input-number>
        <div class="quick-options">
          <n-button size="tiny" :type="sleepTime === 0 ? 'primary' : 'default'" @click="sleepTime = 0">不休眠</n-button>
          <n-button size="tiny" :type="sleepTime === 5 ? 'primary' : 'default'" @click="sleepTime = 5">5 分钟</n-button>
          <n-button size="tiny" :type="sleepTime === 15 ? 'primary' : 'default'" @click="sleepTime = 15"
            >15 分钟</n-button
          >
          <n-button size="tiny" :type="sleepTime === 30 ? 'primary' : 'default'" @click="sleepTime = 30"
            >30 分钟</n-button
          >
        </div>
      </div>
    </n-spin>
    <template #footer>
      <div class="modal-footer">
        <n-button size="small" @click="show = false">关闭</n-button>
        <n-button size="small" type="primary" :loading="sleepSaving" @click="emit('save')">保存</n-button>
      </div>
    </template>
  </n-modal>
</template>

<script setup lang="ts">
import { computed } from 'vue';

const props = defineProps<{
  show: boolean;
  sleepTime: number;
  sleepLoading: boolean;
  sleepSaving: boolean;
}>();

const emit = defineEmits<{
  'update:show': [boolean];
  'update:sleepTime': [number];
  save: [];
}>();

const show = computed({
  get: () => props.show,
  set: (v) => emit('update:show', v),
});

const sleepTime = computed({
  get: () => props.sleepTime,
  set: (v) => emit('update:sleepTime', v),
});
</script>

<style scoped>
.modal-footer {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}
.sleep-body {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.quick-options {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}
.hint-text {
  font-size: 13px;
  color: var(--text-muted);
}
</style>
