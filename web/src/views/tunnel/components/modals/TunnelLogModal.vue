<template>
  <n-modal
    :show="show"
    class="modal-fit"
    preset="card"
    :title="`${kind === 'frp' ? 'FRP' : 'CF'} 日志 · ${name}`"
    style="--modal-w: 900px; --modal-h: 720px"
    @update:show="emit('update:show', $event)"
  >
    <n-space :size="8" align="center" style="margin-bottom: 8px">
      <!-- ui-baseline-allow: 弹窗行内开关，左侧紧跟一句说明，不属于整行设置项，套 ToggleRow 反而多一条分隔线 -->
      <n-switch v-model:value="full" size="small" @update:value="reload" />
      <span class="row-hint">读取运行期日志文件尾部（关闭则只看内存最近 200 行；停止后文件不存在会自动回落内存）</span>
    </n-space>
    <!-- 读取失败不再落成「（无输出）」：那是「日志是空的」，与「没读到」是两件事 -->
    <pre v-if="!loadError" class="log-box">{{ text || '（无输出）' }}</pre>
    <div v-else class="log-box log-failed" role="alert">
      <span class="log-failed-text">{{ loadError }}</span>
      <n-button size="tiny" :loading="loading" @click="reload">重试</n-button>
    </div>
    <template #footer>
      <n-space justify="end" :size="8">
        <n-button size="small" :loading="clearing" @click="clearInstanceLog">清空本实例日志</n-button>
        <n-button size="small" :loading="loading" @click="reload">刷新</n-button>
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
/** 读取失败的原因（空串 = 读成功） */
const loadError = ref('');

function logPathOf(kind: 'frp' | 'cf', name: string): string {
  const n = encodeURIComponent(name);
  return kind === 'frp' ? `/api/tunnel/frp/config/${n}/log` : `/api/tunnel/cf/tunnel/${n}/log`;
}

/**
 * 拉一次日志。`kind` / `name` 由调用方**显式传入**并在这里与 props 比对：
 * 只看 `show` 的旧写在 useLazyModal 的 350ms 保活窗口里「关 A → 开 B」时，
 * A 的迟到响应会填进标题写着 B 的弹窗（与 CfTokenModal 同一处竞态）。
 */
async function refresh(kind: 'frp' | 'cf', name: string) {
  loading.value = true;
  loadError.value = '';
  try {
    const { data } = await api.get(logPathOf(kind, name), { params: full.value ? { full: 1 } : {} });
    // 目标变了 → 这次结果已过期
    if (props.kind !== kind || props.name !== name) {
      return;
    }
    text.value = data?.log || '';
  } catch (e: any) {
    if (props.kind !== kind || props.name !== name) {
      return;
    }
    loadError.value = errText(e, '读取日志失败');
  } finally {
    loading.value = false;
  }
}

/** 按钮 / 开关的统一入口（事件对象与开关值不能顺手当参数传进去，所以单独包一层） */
function reload() {
  return refresh(props.kind, props.name);
}

/**
 * 单实例清日志：FRP 走 /frp/config/{name}/log/clear，CF 走 /cf/tunnel/{name}/log/clear
 * （路径与 refresh() 同一套 kind 分支，name 已在 logPathOf 里 encodeURIComponent）。
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
    await api.post(`${logPathOf(props.kind, props.name)}/clear`);
    message.success('已清空本实例日志');
    // 重新拉一次而不是就地把 text 置空：清空结果由服务端确认（正常会变成「（无输出）」）
    await reload();
  } catch (e: any) {
    message.error(errText(e, '清空失败'));
  } finally {
    clearing.value = false;
  }
}

// 打开时重置为「内存最近 200 行」再拉一次，与拆分前 openLog() 的行为一致
watch(
  () => [props.show, props.kind, props.name] as const,
  ([v, kind, name]) => {
    if (!v) return;
    text.value = '';
    loadError.value = '';
    full.value = false;
    void refresh(kind, name);
  },
  { immediate: true }
);
</script>

<style scoped>
.row-hint {
  font-size: 12px;
  color: var(--text-muted);
}
/* 高度交给 .modal-fit 的内容区：自己再加一层 max-height 会出现里外两条滚动条，
   而外层那条才是能滚到卡片底部的那条，用户很容易以为日志就到头了。 */
.log-box {
  margin: 0;
  padding: var(--space-2);
  font-size: 12px;
  line-height: 1.5;
  background: var(--code-bg);
  border-radius: var(--radius-sm);
  white-space: pre-wrap;
  word-break: break-all;
}
.log-failed {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  min-height: 80px;
  color: var(--error);
}
.log-failed-text {
  flex: 1;
  min-width: 0;
}
</style>
