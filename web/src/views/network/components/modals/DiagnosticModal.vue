<template>
  <n-modal
    :show="show"
    @update:show="$emit('update:show', $event)"
  >
    <n-card
      style="width: min(680px, 92vw)"
      title="网络诊断"
      :bordered="false"
      closable
      @close="$emit('update:show', false)"
    >
      <div class="diag-form">
        <n-input v-model:value="host" placeholder="目标地址（域名或 IP，如 8.8.8.8）" :disabled="running" />
        <n-input-number v-model:value="port" :min="1" :max="65535" placeholder="端口（TCP 探测用）" style="width: 150px" :disabled="running" />
        <div class="diag-actions">
          <n-button type="primary" :loading="running && tool === 'ping'" :disabled="running" @click="run('ping')">Ping</n-button>
          <n-button :loading="running && tool === 'dns'" :disabled="running" @click="run('dns')">DNS 解析</n-button>
          <n-button :loading="running && tool === 'tcp'" :disabled="running" @click="run('tcp')">TCP 探测</n-button>
          <n-button :loading="running && tool === 'traceroute'" :disabled="running" @click="run('traceroute')">Traceroute</n-button>
        </div>
      </div>
      <n-alert v-if="error" type="error" style="margin-top: 12px">{{ error }}</n-alert>
      <pre v-if="output" class="diag-output">{{ output }}</pre>
    </n-card>
  </n-modal>
</template>

<script setup lang="ts">
import { ref } from 'vue';
import { NModal, NCard, NButton, NInput, NInputNumber, NAlert } from 'naive-ui';
import { getApiClient } from '@/composables/useApi';
import { Endpoints } from '@/api/contract';

const api = getApiClient();

defineProps<{ show: boolean }>();
defineEmits<{ (e: 'update:show', v: boolean): void }>();

const host = ref('');
const port = ref(443);
const tool = ref('');
const running = ref(false);
const output = ref('');
const error = ref('');

async function run(t: 'ping' | 'dns' | 'tcp' | 'traceroute') {
  if (!host.value.trim()) { error.value = '请先输入目标地址'; return; }
  running.value = true;
  tool.value = t;
  output.value = '';
  error.value = '';
  try {
    const body: Record<string, unknown> = { host: host.value.trim() };
    if (t === 'tcp') body.port = port.value;
    if (t === 'traceroute') body.max_hops = 15;
    const res = await api.post<{ output?: string; exit_code?: number }>(Endpoints.diagnostic[t], body);
    const data = res.data ?? {};
    output.value = String(data.output ?? JSON.stringify(data));
    if (data.exit_code !== 0 && t !== 'traceroute') {
      error.value = '未成功（exit_code=' + data.exit_code + '），输出见下方';
    }
  } catch (e: unknown) {
    error.value = e instanceof Error ? e.message : String(e);
  } finally {
    running.value = false;
  }
}
</script>

<style scoped>
.diag-form {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
}
.diag-form > .n-input:first-child {
  flex: 1 1 240px;
}
.diag-actions {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
  width: 100%;
}
.diag-output {
  margin-top: 12px;
  padding: 10px;
  background: var(--n-color-embedded, #18181c);
  border-radius: 6px;
  font-size: 12px;
  line-height: 1.5;
  white-space: pre-wrap;
  word-break: break-all;
  max-height: 360px;
  overflow: auto;
}
</style>
