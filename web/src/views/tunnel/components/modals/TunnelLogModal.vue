<template>
  <n-modal
    :show="show"
    preset="card"
    :title="`${kind === 'frp' ? 'FRP' : 'CF'} 日志 · ${name}`"
    style="max-width: 900px"
    @update:show="emit('update:show', $event)"
  >
    <n-space :size="8" align="center" style="margin-bottom: 8px">
      <n-switch v-model:value="full" size="small" @update:value="refreshLog" />
      <span class="row-hint">读取运行期日志文件尾部（关闭则只看内存最近 200 行；停止后文件不存在会自动回落内存）</span>
    </n-space>
    <pre class="log-box">{{ text || '（无输出）' }}</pre>
    <template #footer>
      <n-space justify="end" :size="8">
        <n-button size="small" :loading="clearing" @click="clearInstanceLog">清空本实例日志</n-button>
        <n-button size="small" :loading="loading" @click="refreshLog">刷新</n-button>
        <n-button size="small" @click="emit('update:show', false)">关闭</n-button>
      </n-space>
    </template>
  </n-modal>
</template>

<script setup lang="ts">
import { ref, watch } from 'vue';
import { useMessage } from 'naive-ui';
import { useCancellableApi } from '@/composables/useCancellableApi';
import { errText } from '@/views/tunnel/tunnelShared';

const props = defineProps<{ show: boolean; kind: 'frp' | 'cf'; name: string }>();
const emit = defineEmits<{ 'update:show': [boolean] }>();

const message = useMessage();
const api = useCancellableApi();

const text = ref('');
const full = ref(false);
const loading = ref(false);
const clearing = ref(false);

function logPath(): string {
  const n = encodeURIComponent(props.name);
  return props.kind === 'frp' ? `/api/tunnel/frp/config/${n}/log` : `/api/tunnel/cf/tunnel/${n}/log`;
}

async function refreshLog() {
  loading.value = true;
  try {
    const { data } = await api.get(logPath(), { params: full.value ? { full: 1 } : {} });
    text.value = data?.log || '';
  } catch (e: any) {
    message.error(errText(e, '读取日志失败'));
  } finally {
    loading.value = false;
  }
}

/**
 * 单实例清日志：FRP 走 /frp/config/{name}/log/clear，CF 走 /cf/tunnel/{name}/log/clear
 * （路径与 logPath() 同一套 kind 分支，name 已在 logPath 里 encodeURIComponent）。
 *
 * core **不校验 name 是否存在**，未知名字也恒回 { success: true } / HTTP 200，
 * 所以 name 为空必须前端自己拦住 —— 否则会打到形如 .../config//log/clear 的路径却「成功」。
 * 与概览卡上的「清空全部日志」（/api/tunnel/logs/clear，两个引擎所有实例）是两个不同的动作。
 */
async function clearInstanceLog() {
  if (!props.name.trim()) {
    message.error('实例名为空，未发送请求');
    return;
  }
  clearing.value = true;
  try {
    await api.post(`${logPath()}/clear`);
    message.success('已清空本实例日志');
    // 重新拉一次而不是就地把 text 置空：清空结果由服务端确认（正常会变成「（无输出）」）
    await refreshLog();
  } catch (e: any) {
    message.error(errText(e, '清空失败'));
  } finally {
    clearing.value = false;
  }
}

// 打开时重置为「内存最近 200 行」再拉一次，与拆分前 openLog() 的行为一致
watch(
  () => props.show,
  (v) => {
    if (!v) return;
    text.value = '';
    full.value = false;
    refreshLog();
  },
  { immediate: true }
);
</script>

<style scoped>
.row-hint {
  font-size: 12px;
  color: var(--text-muted);
}
.log-box {
  max-height: 50vh;
  overflow: auto;
  margin: 0;
  padding: 10px;
  font-size: 12px;
  line-height: 1.5;
  background: var(--code-bg);
  border-radius: 4px;
  white-space: pre-wrap;
  word-break: break-all;
}
</style>
