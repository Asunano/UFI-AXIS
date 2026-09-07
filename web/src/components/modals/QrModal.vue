<template>
  <n-modal
    v-model:show="show"
    preset="card"
    title="WiFi 二维码"
    style="width: 340px; max-width: calc(100vw - 32px)"
    @after-leave="emit('released')"
  >
    <div class="qr-body">
      <n-spin :show="qrLoading">
        <div class="qr-frame">
          <img v-if="qrUrl" :src="qrUrl" alt="WiFi 连接二维码" class="qr-image" />
          <div v-else class="qr-placeholder">{{ qrError || '二维码加载中' }}</div>
        </div>
      </n-spin>
      <div class="qr-ssid">{{ ssid || '--' }}</div>
      <div class="hint-text">扫码即可连接当前热点</div>
    </div>
    <template #footer>
      <div class="modal-footer">
        <n-button size="small" @click="show = false">关闭</n-button>
        <n-button size="small" type="primary" :loading="qrLoading" @click="emit('refresh')">刷新</n-button>
      </div>
    </template>
  </n-modal>
</template>

<script setup lang="ts">
import { computed } from 'vue';

const props = defineProps<{
  show: boolean;
  qrUrl: string;
  qrLoading: boolean;
  qrError: string;
  ssid?: string;
}>();

const emit = defineEmits<{
  'update:show': [boolean];
  refresh: [];
  released: [];
}>();

const show = computed({
  get: () => props.show,
  set: (v) => emit('update:show', v),
});
</script>

<style scoped>
.modal-footer {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}
.qr-body {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
}
.qr-frame {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 200px;
  height: 200px;
  border: 1px solid var(--border-subtle);
  border-radius: var(--radius-sm);
  background: #ffffff;
  overflow: hidden;
}
.qr-image {
  width: 100%;
  height: 100%;
  object-fit: contain;
}
.qr-placeholder {
  font-size: 13px;
  color: var(--text-muted);
  padding: 0 12px;
  text-align: center;
}
.qr-ssid {
  font-size: 15px;
  font-weight: 600;
  color: var(--text-primary);
  word-break: break-all;
  text-align: center;
}
.hint-text {
  font-size: 13px;
  color: var(--text-muted);
}
</style>
