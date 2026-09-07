<template>
  <n-modal v-model:show="show" preset="card" title="网络模式" style="width: 460px; max-width: calc(100vw - 32px)">
    <n-radio-group v-model:value="selectedMode" name="networkMode">
      <n-flex wrap>
        <n-radio-button v-for="m in networkModes" :key="m.value" :value="m.value">
          {{ m.label }}
        </n-radio-button>
      </n-flex>
    </n-radio-group>
    <template #footer>
      <div class="modal-footer">
        <n-button size="small" @click="show = false">取消</n-button>
        <n-button size="small" type="primary" :loading="modeLoading" @click="emit('apply')">应用</n-button>
      </div>
    </template>
  </n-modal>
</template>

<script setup lang="ts">
import { computed } from 'vue';

const props = defineProps<{
  show: boolean;
  selectedMode: string;
  modeLoading: boolean;
  networkModes: ReadonlyArray<{ label: string; value: string }>;
}>();

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
</style>
