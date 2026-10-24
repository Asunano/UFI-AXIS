<template>
  <n-modal
    :show="show"
    class="modal-fit"
    preset="card"
    :title="isNew ? '新建 FRP 通道' : `编辑 ${nameInput}`"
    style="--modal-w: 720px; --modal-h: 720px"
    @update:show="emit('update:show', $event)"
  >
    <n-space vertical :size="10">
      <n-input
        v-if="isNew"
        v-model:value="nameInput"
        size="small"
        placeholder='通道名（不能含 \\ / : * ? " &lt; &gt; | 逗号，最长 64）'
      />
      <n-input
        v-model:value="toml"
        type="textarea"
        :autosize="{ minRows: 12, maxRows: 24 }"
        placeholder="frpc TOML 配置"
      />
      <span class="row-hint">
        core 只做行级解析取 serverAddr / server_port / proxy 数；toml 为空会被拒绝（400），不会写成空文件。
      </span>
    </n-space>
    <template #footer>
      <n-space justify="end" :size="8">
        <n-button size="small" @click="emit('update:show', false)">取消</n-button>
        <n-button size="small" type="primary" :loading="saving" @click="save">保存</n-button>
      </n-space>
    </template>
  </n-modal>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { useMessage } from 'naive-ui';
import { useCancellableApi } from '@/composables/useCancellableApi';
import { errText } from '@/views/tunnel/tunnelShared';

/** `name` 为空串 = 新建（此时通道名由用户在弹窗里填） */
const props = defineProps<{ show: boolean; name: string }>();
const emit = defineEmits<{ 'update:show': [boolean]; saved: [] }>();

const message = useMessage();
const api = useCancellableApi();

const nameInput = ref('');
const toml = ref('');
const saving = ref(false);
const isNew = computed(() => !props.name);

// 2026-10-22：watch 依赖从只看 show 改为 [show, name]（与 CfTokenModal 同一处理）。
// 只看 show 时，快速「关 A → 开 B」会落在 useLazyModal 的 350ms 保活窗口里 ——
// A 的迟到 GET 会把 A 的 TOML 填进标题写着 B 的编辑框，一按保存就把 A 的配置写给了 B。
watch(
  () => [props.show, props.name] as const,
  async ([v, name]) => {
    if (!v) return;
    nameInput.value = name;
    toml.value = '';
    saving.value = false;
    if (!name) return;
    try {
      const { data } = await api.get(`/api/tunnel/frp/config/${encodeURIComponent(name)}`);
      // 名字变了 → 这次 GET 的结果已过期（show 从没变过 false）
      if (nameInput.value !== name) return;
      toml.value = data?.toml || '';
    } catch (e: any) {
      message.error(errText(e, '读取配置失败'));
    }
  },
  { immediate: true }
);

async function save() {
  const name = nameInput.value.trim();
  if (!name) {
    message.error('请填写通道名');
    return;
  }
  // core 对空 toml 会 400（T40-2 之前会静默写成空文件），这里先拦一道给出更直接的提示
  if (!toml.value.trim()) {
    message.error('TOML 配置不能为空');
    return;
  }
  saving.value = true;
  try {
    await api.put(`/api/tunnel/frp/config/${encodeURIComponent(name)}`, { toml: toml.value });
    message.success('配置已保存');
    emit('update:show', false);
    emit('saved');
  } catch (e: any) {
    // 400 常见原因：名字含非法字符（\ / : * ? " < > | 逗号 .. 或超 64 字符）
    message.error(errText(e, '保存失败'));
  } finally {
    saving.value = false;
  }
}
</script>

<style scoped>
.row-hint {
  font-size: 12px;
  color: var(--text-muted);
}
</style>
