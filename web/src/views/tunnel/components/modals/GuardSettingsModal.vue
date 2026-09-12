<template>
  <!-- 看护设置：配一次就不再动，所以收进弹窗而不是常驻卡片 -->
  <n-modal
    :show="show"
    preset="card"
    title="看护设置"
    style="max-width: 560px"
    @update:show="emit('update:show', $event)"
  >
    <ToggleRow
      label="自动重连"
      description="进程意外退出时按下面的间隔重新拉起（仅对期望在跑的通道生效）"
      :model-value="settings.auto_reconnect"
      :loading="saving"
      @update:model-value="(v: boolean) => saveSettings({ auto_reconnect: v })"
    />
    <ToggleRow label="重连间隔" description="10 - 120 秒；改小会立即重排看护协程">
      <template #control>
        <n-input-number
          :value="settings.reconnect_interval_sec"
          :min="10"
          :max="120"
          size="small"
          style="width: 120px"
          @update:value="(v: number | null) => v && saveSettings({ reconnect_interval_sec: v })"
        />
      </template>
    </ToggleRow>
    <ToggleRow
      label="失败时通知"
      description="隧道由非 Error 变为 Error 时推送通知（这是隧道通知的唯一真源）"
      :model-value="settings.notify_on_failure"
      :loading="saving"
      @update:model-value="(v: boolean) => saveSettings({ notify_on_failure: v })"
    />
    <InfoRow label="最大连续失败次数" :value="String(settings.max_reconnect_attempts)" />
    <div class="desired-row">
      <span class="row-hint">看护期望（FRP）：</span>
      <n-space :size="4">
        <n-tag v-for="n in settings.frp_desired" :key="`fd-${n}`" size="tiny" :bordered="false">{{ n }}</n-tag>
        <span v-if="!settings.frp_desired.length" class="row-hint">（空）</span>
      </n-space>
    </div>
    <div class="desired-row">
      <span class="row-hint">看护期望（CF）：</span>
      <n-space :size="4">
        <n-tag v-for="n in settings.cf_desired" :key="`cd-${n}`" size="tiny" :bordered="false">{{ n }}</n-tag>
        <span v-if="!settings.cf_desired.length" class="row-hint">（空）</span>
      </n-space>
    </div>
    <template #footer>
      <n-space justify="end" :size="8">
        <n-button size="small" @click="emit('update:show', false)">关闭</n-button>
      </n-space>
    </template>
  </n-modal>
</template>

<script setup lang="ts">
import { reactive, ref, watch } from 'vue';
import { useMessage } from 'naive-ui';
import { useCancellableApi } from '@/composables/useCancellableApi';
import InfoRow from '@/components/InfoRow.vue';
import ToggleRow from '@/components/ToggleRow.vue';
import { errText } from '@/views/tunnel/tunnelShared';

/**
 * 看护设置自持数据：这些字段只在本弹窗里显示，页面别处不用，
 * 所以不必跟着页面的 loadAll 一起拉 —— 打开时拉一次即可（少一次常驻请求）。
 *
 * 与「组件管理」不同，这里没有跨弹窗生命周期的后台任务，所以自持是安全的。
 */
const props = defineProps<{ show: boolean }>();
const emit = defineEmits<{ 'update:show': [boolean] }>();

const message = useMessage();
const api = useCancellableApi();

const settings = reactive({
  auto_reconnect: true,
  reconnect_interval_sec: 30,
  notify_on_failure: true,
  max_reconnect_attempts: 3,
  frp_desired: [] as string[],
  cf_desired: [] as string[],
});
const saving = ref(false);

async function loadSettings() {
  try {
    // GET 返回裸设置对象；PUT 的成功响应则包了一层 settings，两处形态不同
    const { data } = await api.get('/api/tunnel/settings');
    Object.assign(settings, {
      auto_reconnect: data?.auto_reconnect ?? true,
      reconnect_interval_sec: data?.reconnect_interval_sec ?? 30,
      notify_on_failure: data?.notify_on_failure ?? true,
      max_reconnect_attempts: data?.max_reconnect_attempts ?? 3,
      frp_desired: data?.frp_desired ?? [],
      cf_desired: data?.cf_desired ?? [],
    });
  } catch {
    /* 静默 */
  }
}

// PUT 是严格字段级更新（未传的字段绝不覆盖），所以只发变化的那一项
async function saveSettings(patch: Record<string, any>) {
  saving.value = true;
  try {
    const { data } = await api.put('/api/tunnel/settings', patch);
    if (data?.settings) Object.assign(settings, data.settings);
  } catch (e: any) {
    message.error(errText(e, '保存看护设置失败'));
    await loadSettings();
  } finally {
    saving.value = false;
  }
}

// 每次打开都重拉：看护期望列表会被「全部停止」等页面动作改掉，用缓存会显示过期内容
watch(
  () => props.show,
  (v) => {
    if (v) loadSettings();
  },
  { immediate: true }
);
</script>

<style scoped>
.row-hint {
  font-size: 12px;
  color: var(--text-muted);
}
.desired-row {
  display: flex;
  align-items: center;
  gap: 8px;
  padding-top: 8px;
  flex-wrap: wrap;
}
</style>
