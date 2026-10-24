<template>
  <!-- 看护设置：配一次就不再动，所以收进弹窗而不是常驻卡片 -->
  <n-modal
    class="modal-fit"
    style="--modal-w: 560px"
    :show="show"
    preset="card"
    title="看护设置"
    @update:show="emit('update:show', $event)"
  >
    <!--
      ★ 三态：加载中 / 读取失败（可重试）/ 正常。

      2026-10-22（计划 §6.11 B10-3）：原先 `settings` 是一组写死的初值
      （自动重连 = true / 间隔 = 30s / 失败通知 = true / 最大次数 = 3），`loadSettings`
      失败时只是静默 return —— 界面照样显示这四个值，而它们**一个都不是设备上的配置**。
      用户据此以为「自动重连已经开着」，实际可能根本没开；改一个别的字段时又会把
      这些编出来的值一起当成现状。所以现在：
        · 没读到 → 不渲染任何开关，只给失败原因 + 重试；
        · 读到了但设备没返回某个字段 → 那一项显示 `--`，不拿默认值顶。
    -->
    <div v-if="loading && !loaded" class="guard-state">
      <n-spin size="small" />
      <span class="row-hint">正在读取看护设置…</span>
    </div>

    <div v-else-if="loadError" class="guard-state guard-state-err" role="alert">
      <n-icon :size="16"><AlertCircleOutline /></n-icon>
      <span class="guard-err-text">{{ loadError }}</span>
      <n-button size="tiny" :loading="loading" @click="loadSettings">重试</n-button>
    </div>

    <template v-else-if="loaded">
      <ToggleRow
        v-if="settings.auto_reconnect !== null"
        label="自动重连"
        description="进程意外退出时按下面的间隔重新拉起（仅对期望在跑的通道生效）"
        :model-value="autoReconnect"
        :loading="saving"
        @update:model-value="(v: boolean) => saveSettings({ auto_reconnect: v })"
      />
      <InfoRow v-else label="自动重连" value="-- 设备未返回该字段" />

      <ToggleRow
        v-if="settings.reconnect_interval_sec !== null"
        label="重连间隔"
        description="10 - 120 秒；改小会立即重排看护协程"
      >
        <template #control>
          <n-input-number
            :value="settings.reconnect_interval_sec"
            :min="10"
            :max="120"
            size="small"
            style="width: 120px"
            @update:value="(v: number | null) => v != null && saveSettings({ reconnect_interval_sec: v })"
          />
        </template>
      </ToggleRow>
      <InfoRow v-else label="重连间隔" value="-- 设备未返回该字段" />

      <ToggleRow
        v-if="settings.notify_on_failure !== null"
        label="失败时通知"
        description="隧道由非 Error 变为 Error 时推送通知（这是隧道通知的唯一真源）"
        :model-value="notifyOnFailure"
        :loading="saving"
        @update:model-value="(v: boolean) => saveSettings({ notify_on_failure: v })"
      />
      <InfoRow v-else label="失败时通知" value="-- 设备未返回该字段" />

      <InfoRow
        label="最大连续失败次数"
        :value="settings.max_reconnect_attempts !== null ? String(settings.max_reconnect_attempts) : '--'"
      />

      <div class="desired-row">
        <span class="row-hint">看护期望（FRP）：</span>
        <template v-if="hasFrpDesired">
          <n-tag v-for="n in frpDesired" :key="`fd-${n}`" size="tiny" :bordered="false">{{ n }}</n-tag>
          <span v-if="!frpDesired.length" class="row-hint">（空）</span>
        </template>
        <span v-else class="row-hint">--</span>
      </div>
      <div class="desired-row">
        <span class="row-hint">看护期望（CF）：</span>
        <template v-if="hasCfDesired">
          <n-tag v-for="n in cfDesired" :key="`cd-${n}`" size="tiny" :bordered="false">{{ n }}</n-tag>
          <span v-if="!cfDesired.length" class="row-hint">（空）</span>
        </template>
        <span v-else class="row-hint">--</span>
      </div>
    </template>

    <template #footer>
      <n-space justify="end" :size="8">
        <n-button size="small" @click="emit('update:show', false)">关闭</n-button>
      </n-space>
    </template>
  </n-modal>
</template>

<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue';
import { AlertCircleOutline } from '@vicons/ionicons5';
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

/**
 * 字段类型一律「可空」：`null` 表示**设备没给这个字段**，不是"值就是默认值"。
 * 三个布尔/数字开关不写初值 —— 见模板顶部那段说明（编造初值是本页最危险的一处假状态）。
 */
const settings = reactive<{
  auto_reconnect: boolean | null;
  reconnect_interval_sec: number | null;
  notify_on_failure: boolean | null;
  max_reconnect_attempts: number | null;
  frp_desired: string[] | null;
  cf_desired: string[] | null;
}>({
  auto_reconnect: null,
  reconnect_interval_sec: null,
  notify_on_failure: null,
  max_reconnect_attempts: null,
  frp_desired: null,
  cf_desired: null,
});

const loading = ref(false);
/** 是否已经成功读到过一次（读到之后才允许改，避免把半份数据 PUT 回去） */
const loaded = ref(false);
const loadError = ref('');
const saving = ref(false);

/** 看护期望列表：`null` = 设备没给这个字段（显示 `--`），`[]` = 设备给了但确实是空 */
const frpDesired = computed(() => settings.frp_desired ?? []);
const cfDesired = computed(() => settings.cf_desired ?? []);
const hasFrpDesired = computed(() => settings.frp_desired !== null);
const hasCfDesired = computed(() => settings.cf_desired !== null);
/**
 * 开关的显式派生值：模板里 `v-if="…!== null"` 控制渲不渲染，这里只回答「读到的是 true 吗」。
 * ToggleRow 的 `model-value` 是必填 boolean，直接传可空值会把判空责任推给模板的类型收窄。
 */
const autoReconnect = computed(() => settings.auto_reconnect === true);
const notifyOnFailure = computed(() => settings.notify_on_failure === true);

function boolOrNull(v: unknown): boolean | null {
  return typeof v === 'boolean' ? v : null;
}
function numOrNull(v: unknown): number | null {
  if (v === null || v === undefined || v === '') {
    return null;
  }
  const n = Number(v);
  return Number.isFinite(n) ? n : null;
}
function strArrOrNull(v: unknown): string[] | null {
  return Array.isArray(v) ? v.map((x) => String(x)) : null;
}

/**
 * GET 返回裸设置对象；PUT 的成功响应则包了一层 settings，两处形态不同，解析只写这一份。
 * 只覆盖**响应里确实出现**的字段：PUT 是字段级更新，回包里可能只有被改的那一项，
 * 无脑整体覆盖会把其余字段清成 `--`。
 */
function applySettings(raw: any): void {
  if (!raw || typeof raw !== 'object') {
    return;
  }
  if ('auto_reconnect' in raw) {
    settings.auto_reconnect = boolOrNull(raw.auto_reconnect);
  }
  if ('reconnect_interval_sec' in raw) {
    settings.reconnect_interval_sec = numOrNull(raw.reconnect_interval_sec);
  }
  if ('notify_on_failure' in raw) {
    settings.notify_on_failure = boolOrNull(raw.notify_on_failure);
  }
  if ('max_reconnect_attempts' in raw) {
    settings.max_reconnect_attempts = numOrNull(raw.max_reconnect_attempts);
  }
  if ('frp_desired' in raw) {
    settings.frp_desired = strArrOrNull(raw.frp_desired);
  }
  if ('cf_desired' in raw) {
    settings.cf_desired = strArrOrNull(raw.cf_desired);
  }
}

/** 一次「完整读取」的结果就是全部真源：没出现在回包里的字段一律算未返回，先用 null 兜住 */
function resetSettings(): void {
  settings.auto_reconnect = null;
  settings.reconnect_interval_sec = null;
  settings.notify_on_failure = null;
  settings.max_reconnect_attempts = null;
  settings.frp_desired = null;
  settings.cf_desired = null;
}

async function loadSettings() {
  loading.value = true;
  loadError.value = '';
  try {
    const { data } = await api.get('/api/tunnel/settings');
    if (!data || typeof data !== 'object') {
      // 200 但不是对象：这是"没读到"，不是"配置为空"
      loadError.value = '设备没有返回看护设置（响应不是对象）';
      loaded.value = false;
      return;
    }
    // 先清空再套用（而不是在请求前清空）：请求在途时旧读数还算数，
    // 清早了会在加载期间把每一项闪成「设备未返回该字段」。
    resetSettings();
    applySettings(data);
    loaded.value = true;
  } catch (e: any) {
    loadError.value = errText(e, '读取看护设置失败');
    loaded.value = false;
  } finally {
    loading.value = false;
  }
}

// PUT 是严格字段级更新（未传的字段绝不覆盖），所以只发变化的那一项
async function saveSettings(patch: Record<string, any>) {
  saving.value = true;
  try {
    const { data } = await api.put('/api/tunnel/settings', patch);
    // 回包带 settings 就以服务端为准；不带（老 core / 回包为空）就把刚提交的这一项就地采用 ——
    // 否则开关会在成功后弹回原值，看着像"保存失败"。
    applySettings(data?.settings ?? patch);
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
    if (v) {
      loadSettings();
    }
  },
  { immediate: true }
);
</script>

<style scoped>
.row-hint {
  font-size: var(--font-sm);
  color: var(--text-muted);
}
.guard-state {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  padding: var(--space-4) 0;
}
.guard-state-err {
  color: var(--error);
}
.guard-err-text {
  flex: 1;
  min-width: 0;
  font-size: var(--font-sm);
  color: var(--text-secondary);
  word-break: break-all;
}
.desired-row {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  padding-top: var(--space-2);
  flex-wrap: wrap;
}
</style>
