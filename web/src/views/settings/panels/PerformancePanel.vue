<template>
  <div class="settings-panel">
    <!-- Card 3: QoS 配置 -->
    <GridCard title="QoS 配置">
      <div class="config-grid">
        <div class="config-item switch-item">
          <span class="config-label">启用</span>
          <n-switch v-model:value="qosForm.enabled" />
        </div>
        <div class="config-item">
          <span class="config-label">Shell 最大并发 (1-10)</span>
          <n-input-number
            v-model:value="qosForm.shellMaxConcurrent"
            :min="ConfigLimits.qosShellMaxConcurrent[0]"
            :max="ConfigLimits.qosShellMaxConcurrent[1]"
            size="small"
          />
        </div>
        <div class="config-item">
          <span class="config-label">缓存 TTL (500-30000 ms)</span>
          <n-input-number
            v-model:value="qosForm.cacheTtlMs"
            :min="ConfigLimits.qosCacheTtlMs[0]"
            :max="ConfigLimits.qosCacheTtlMs[1]"
            :step="500"
            size="small"
          />
        </div>
        <div class="config-item">
          <span class="config-label">设备后台查询上限 (1-8)</span>
          <n-input-number
            v-model:value="qosForm.goformQueryLimit"
            :min="ConfigLimits.qosGoformQueryMax[0]"
            :max="ConfigLimits.qosGoformQueryMax[1]"
            size="small"
          />
        </div>
        <div class="config-item">
          <span class="config-label">设备后台设置上限 (1-4)</span>
          <n-input-number
            v-model:value="qosForm.goformSetLimit"
            :min="ConfigLimits.qosGoformSetMax[0]"
            :max="ConfigLimits.qosGoformSetMax[1]"
            size="small"
          />
        </div>
      </div>
      <div class="card-actions">
        <n-button type="primary" size="small" :loading="savingQos" @click="saveQos">保存</n-button>
      </div>
      <n-divider style="margin: 10px 0" />
      <div class="section-subtitle">当前状态</div>
      <div v-if="qosStatusData.length" class="status-grid">
        <InfoRow v-for="item in qosStatusData" :key="item.label" :label="item.label" :value="item.value" />
      </div>
      <div v-else class="text-muted">暂无状态数据</div>
    </GridCard>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted } from 'vue';
import { useMessage } from 'naive-ui';
import { getApiClient } from '@/composables/useApi';
import GridCard from '@/components/GridCard.vue';
import InfoRow from '@/components/InfoRow.vue';
import { ConfigLimits } from '@/api/contract';
import { buildChangedPayload, commitConfigSave } from '@/views/settings/settingsShared';

const message = useMessage();
const api = getApiClient();

// ── QoS ──
const savingQos = ref(false);
const qosOriginal = reactive<Record<string, any>>({});
// 取值范围见 contract 的 ConfigLimits（与 core ConfigRoutes 校验共用）；
// 越界字段会出现在响应的 rejected_fields 里并带 min/max，因此默认值也必须落在范围内。

const qosForm = reactive({
  enabled: false,
  shellMaxConcurrent: 5,
  cacheTtlMs: 5000,
  goformQueryLimit: 4,
  goformSetLimit: 2,
});
const qosStatus = ref<Record<string, any>>({});

// core /api/qos/status 是嵌套结构：
// { enabled, shell_qos:{root:{available,total,target}, normal:{...}, cache:{entries,ttl_ms}, batch:{...}},
//   goform_qos:{...}, cpu_temp: Int(毫摄氏度), dynamic_pool:{core,current,max} }
// 旧代码 Object.entries + String(value) → 所有嵌套对象都渲染成 [object Object]
const QOS_LABELS: Record<string, string> = {
  'shell_qos.root.available': 'Shell Root 可用令牌',
  'shell_qos.root.total': 'Shell Root 令牌总数',
  'shell_qos.root.target': 'Shell Root 目标令牌',
  'shell_qos.normal.available': 'Shell 普通可用令牌',
  'shell_qos.normal.total': 'Shell 普通令牌总数',
  'shell_qos.cache.entries': 'Shell 缓存条目',
  'shell_qos.cache.ttl_ms': 'Shell 缓存 TTL',
  cpu_temp: 'CPU 温度',
  'dynamic_pool.core': '线程池核心数',
  'dynamic_pool.current': '线程池当前数',
  'dynamic_pool.max': '线程池上限',
};

function humanizeQosKey(path: string): string {
  if (QOS_LABELS[path]) return QOS_LABELS[path]!;
  return path
    .split('.')
    .map((seg) => seg.replace(/_/g, ' ').replace(/^./, (s) => s.toUpperCase()))
    .join(' / ');
}

function formatQosValue(path: string, value: any): string {
  if (path === 'cpu_temp') {
    const n = Number(value);
    if (!n) return '--';
    // thermal_zone 读数是毫摄氏度（如 42000）
    return (n > 1000 ? n / 1000 : n).toFixed(1) + ' °C';
  }
  if (path.endsWith('ttl_ms')) return `${Number(value)} ms`;
  if (typeof value === 'boolean') return value ? '是' : '否';
  return String(value);
}

function flattenQos(obj: any, prefix = '', out: { label: string; value: string }[] = []) {
  for (const [key, value] of Object.entries(obj || {})) {
    const path = prefix ? `${prefix}.${key}` : key;
    // enabled 在 /status 里是硬编码 true，不代表配置项，避免与上方开关冲突
    if (path === 'enabled') continue;
    if (value !== null && typeof value === 'object' && !Array.isArray(value)) {
      flattenQos(value, path, out);
    } else {
      out.push({ label: humanizeQosKey(path), value: formatQosValue(path, value) });
    }
  }
  return out;
}

const qosStatusData = computed(() => flattenQos(qosStatus.value));

async function loadQosStatus() {
  try {
    const { data } = await api.get('/api/qos/status');
    qosStatus.value = data;
  } catch {
    /* silent */
  }
}

async function loadQosConfig() {
  try {
    const { data } = await api.get('/api/config');
    const qosKeys = [
      'qos_enabled',
      'qos_shell_max_concurrent',
      'qos_cache_ttl_ms',
      'qos_goform_query_max',
      'qos_goform_set_max',
    ] as string[];
    const formKeys = ['enabled', 'shellMaxConcurrent', 'cacheTtlMs', 'goformQueryLimit', 'goformSetLimit'] as string[];
    for (let i = 0; i < qosKeys.length; i++) {
      const key = qosKeys[i]!;
      const formKey = formKeys[i]!;
      if (data[key] !== undefined) {
        (qosForm as any)[formKey] = data[key];
        qosOriginal[key] = data[key];
      }
    }
  } catch {
    /* silent */
  }
}

async function saveQos() {
  const formKeys: Record<string, string> = {
    enabled: 'qos_enabled',
    shellMaxConcurrent: 'qos_shell_max_concurrent',
    cacheTtlMs: 'qos_cache_ttl_ms',
    goformQueryLimit: 'qos_goform_query_max',
    goformSetLimit: 'qos_goform_set_max',
  };
  const payload = buildChangedPayload(formKeys, qosForm, qosOriginal);
  if (Object.keys(payload).length === 0) {
    message.info('没有更改');
    return;
  }
  savingQos.value = true;
  try {
    const { data } = await api.put('/api/config', payload);
    commitConfigSave(payload, data, qosOriginal, 'QoS 配置', message, loadQosConfig);
    loadQosStatus();
  } catch (e: any) {
    message.error(e?.response?.data?.error || '保存失败');
  } finally {
    savingQos.value = false;
  }
}

onMounted(() => {
  loadQosConfig();
  loadQosStatus();
});
</script>

<style scoped>
/* .settings-panel 栅格与断点、.config-grid/.config-item/.config-label/.switch-item 版式、
   .section-subtitle、.card-actions 均已统一到 src/styles/main.css（全局各一份） */

/* ── QoS status grid ── */
.status-grid {
  display: flex;
  flex-direction: column;
}

/* ── Text helpers ── */
.text-muted {
  color: var(--text-muted);
  font-size: 13px;
}
</style>
