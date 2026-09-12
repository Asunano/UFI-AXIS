<template>
  <n-modal v-model:show="show" preset="card" title="网络模式" style="width: 460px; max-width: calc(100vw - 32px)">
    <n-radio-group v-model:value="selectedMode" name="networkMode">
      <n-flex wrap>
        <n-radio-button v-for="m in networkModes" :key="m.value" :value="m.value">
          {{ m.label }}
        </n-radio-button>
      </n-flex>
    </n-radio-group>
    <!-- 「切换中 / 尚未完成」由 useNetworkControls 的回读确认给出，不在这里猜设备状态 -->
    <n-text v-if="switching" :depth="3" class="mode-hint"> 正在切换，设备重新搜网需要一点时间 </n-text>
    <n-text v-else-if="timedOut" type="warning" class="mode-hint"> 设备尚未完成切换，可稍后刷新查看 </n-text>
    <template #footer>
      <div class="modal-footer">
        <n-button size="small" @click="show = false">取消</n-button>
        <n-button
          size="small"
          type="primary"
          :loading="modeLoading || switching"
          :disabled="switching"
          @click="emit('apply')"
        >
          应用
        </n-button>
      </div>
    </template>
  </n-modal>
</template>

<script setup lang="ts">
import { computed } from 'vue';

const props = withDefaults(
  defineProps<{
    show: boolean;
    selectedMode: string;
    modeLoading: boolean;
    networkModes: ReadonlyArray<{ label: string; value: string }>;
    /** 切换已下发、正在等设备报出目标档位 */
    switching?: boolean;
    /** 上一次切换在回读预算内没等到目标档位 */
    timedOut?: boolean;
  }>(),
  { switching: false, timedOut: false }
);

const emit = defineEmits<{
  'update:show': [boolean];
  'update:selectedMode': [string];
  apply: [];
}>();

const show = computed({
  get: () => props.show,
  set: (v) => emit('update:show', v),
});

const selectedMode = computed({
  get: () => props.selectedMode,
  set: (v) => emit('update:selectedMode', v),
});
</script>

<style scoped>
.modal-footer {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}

.mode-hint {
  display: block;
  margin-top: 12px;
  font-size: 12px;
}
</style>
