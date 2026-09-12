<template>
  <n-modal v-model:show="show" preset="card" title="WiFi 模块信息" style="width: 520px; max-width: calc(100vw - 32px)">
    <n-spin :show="moduleInfoLoading">
      <div class="module-body">
        <div v-if="moduleInfoRows.length" class="kv-table">
          <div v-for="row in moduleInfoRows" :key="row.key" class="kv-row">
            <span class="kv-key">{{ row.key }}</span>
            <span class="kv-value">{{ row.value }}</span>
          </div>
        </div>
        <div v-else-if="moduleInfoError" class="hint-text">{{ moduleInfoError }}</div>
        <div v-else class="hint-text">设备未返回模块信息</div>

        <n-collapse v-if="moduleInfo" style="margin-top: 12px">
          <n-collapse-item title="查看原始 JSON" name="raw">
            <pre class="module-info-pre sub-panel">{{ JSON.stringify(moduleInfo, null, 2) }}</pre>
          </n-collapse-item>
        </n-collapse>
      </div>
    </n-spin>
    <template #footer>
      <div class="modal-footer">
        <n-button size="small" @click="show = false">关闭</n-button>
        <n-button size="small" type="primary" :loading="moduleInfoLoading" @click="loadModuleInfo">刷新</n-button>
      </div>
    </template>
  </n-modal>
</template>

<script setup lang="ts">
import { ref, computed, watch } from 'vue';
import { getApiClient } from '@/composables/useApi';

const props = defineProps<{ show: boolean }>();
const emit = defineEmits<{ 'update:show': [boolean] }>();

const show = computed({
  get: () => props.show,
  set: (v) => emit('update:show', v),
});

const api = getApiClient();

const moduleInfo = ref<Record<string, unknown> | null>(null);
const moduleInfoLoading = ref(false);
const moduleInfoError = ref('');

// 设备返回的是 goform 原始键值，字段集不固定，所以按拿到的键直接平铺，
// 不做白名单映射，免得新固件多出来的字段被吃掉
const moduleInfoRows = computed(() => {
  const info = moduleInfo.value;
  if (!info) return [];
  return Object.entries(info).map(([key, value]) => ({
    key,
    value: value == null || value === '' ? '--' : typeof value === 'object' ? JSON.stringify(value) : String(value),
  }));
});

async function loadModuleInfo() {
  moduleInfoLoading.value = true;
  moduleInfoError.value = '';
  try {
    const { data } = await api.get('/api/wifi/module-info');
    moduleInfo.value = data;
  } catch {
    moduleInfoError.value = '获取模块信息失败';
  } finally {
    moduleInfoLoading.value = false;
  }
}

// 打开即按需拉取（仅首次），刷新按钮可强制重载
watch(
  show,
  (v) => {
    if (v && !moduleInfo.value) loadModuleInfo();
  },
  { immediate: true }
);
</script>

<style scoped>
.modal-footer {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}
.module-body {
  max-height: min(420px, calc(100vh - 240px));
  overflow-y: auto;
  padding-right: 4px;
}
.kv-table {
  display: flex;
  flex-direction: column;
}
.kv-row {
  display: grid;
  grid-template-columns: minmax(0, 160px) minmax(0, 1fr);
  gap: 12px;
  padding: 7px 0;
  border-bottom: 1px solid var(--border-subtle);
  font-size: 13px;
}
.kv-row:last-child {
  border-bottom: none;
}
.kv-key {
  color: var(--text-secondary);
  word-break: break-all;
}
.kv-value {
  color: var(--text-primary);
  word-break: break-all;
  white-space: pre-wrap;
}
/* 描边/内距/圆角/底色走 main.css 的全局 .sub-panel */
.module-info-pre {
  font-size: 12px;
  font-family: monospace;
  white-space: pre-wrap;
  word-break: break-all;
  margin: 0;
  color: var(--text-primary);
  max-height: 260px;
  overflow-y: auto;
}
.hint-text {
  font-size: 13px;
  color: var(--text-muted);
}
</style>
