<!--
  本机短信渠道配置。

  为什么是弹窗：与 Webhook 同一条理由 —— 设置类收进弹窗，通知分栏里只留一张摘要卡。

  这条渠道与另两条最大的不同：**它按条计费**。所以界面上有三处必须写清楚：
  开关的副文案（会产生费用）、每日上限旁边的今日用量、以及「发送测试」前的二次确认
  （那一下会真的发出一条短信、扣一条今日配额）。

  取值域（级别可选值、每日上限区间）由 `api/notify/sms/config` 的 GET 回，web 不手抄第二份。

  2026-09-10：最低级别 + 每日上限 + 今日用量三项改用三条渠道共用的
  `../ChannelRulesFields.vue` —— core 已把规则做成同构，界面上也必须长成同一组旋钮。
  这条渠道**不允许「不限」**（`daily_limit_min = 1`），差异由那个组件按取值域说清楚。
-->
<template>
  <n-modal
    :show="show"
    preset="card"
    title="本机短信通知"
    style="width: 560px; max-width: calc(100vw - 32px)"
    @update:show="emit('update:show', $event)"
  >
    <n-spin :show="loading">
      <ToggleRow
        v-model="form.enabled"
        label="启用本机短信通知"
        description="通知由设备的 SIM 卡以短信发出，按条产生短信费用。"
      />

      <div class="field">
        <span class="field-label">接收号码</span>
        <n-input v-model:value="form.target_number" size="small" placeholder="接收通知短信的手机号码" />
        <span class="row-hint">可使用数字、加号、减号与空格，发送前会去掉分隔符。号码为空时该渠道不会发送。</span>
      </div>

      <!--
        最低级别 + 每日上限 + 今日用量：三条渠道同一个组件（ChannelRulesFields）。
        这条渠道的取值域刻意与另两条不同（1..50，**不允许「不限」**），
        差异由组件按 `daily_limit_min` 说清楚，不靠用户撞校验错误才发现。
      -->
      <ChannelRulesFields
        v-model:min-level="form.min_level"
        v-model:daily-limit="form.daily_limit"
        :levels="config?.levels ?? []"
        :config-loaded="config !== null"
        :daily-limit-min="dailyLimitMin"
        :daily-limit-max="dailyLimitMax"
        :sent-today="config?.sent_today ?? 0"
        :quota-remaining="config?.quota_remaining ?? null"
        :cost-note="COST_NOTE"
      />

      <div class="field">
        <span class="field-label">触发场景</span>
        <n-checkbox-group v-model:value="form.scenes">
          <n-space :size="[14, 6]">
            <n-checkbox v-for="s in PUSH_CHANNEL_SCENES" :key="s.id" :value="s.id" :label="s.label" />
          </n-space>
        </n-checkbox-group>
        <span class="row-hint">只有勾选且达到最低级别的通知会发送短信。一个都不勾时，只有手动测试会发出去。</span>
        <span class="row-hint">{{ SCENE_NOTE_BATTERY }}</span>
      </div>

      <ToggleRow v-model="form.respect_dnd" label="遵守免打扰时段" description="开启后，免打扰时段内不发送短信。" />
      <!-- 免打扰与场景勾选旁边必须说这一句：否则「严重事件照样发」就是一条藏起来的规则 -->
      <span class="row-hint">{{ CRITICAL_OVERRIDE_NOTE }}</span>

      <!-- 结论、是否计入配额、还剩几条：三样缺一个，用户就会反复点那个花钱的按钮。 -->
      <div v-if="testResult" class="sub-panel test-result">
        <div class="test-head">
          <n-tag size="tiny" :bordered="false" :type="testTagType">{{ verdictText }}</n-tag>
          <span class="row-hint">{{ testQuotaText }}</span>
        </div>
        <div v-if="testResult.error" class="row-hint">{{ testResult.error }}</div>
        <div v-if="!testResult.auto_notify_enabled" class="row-hint">
          通知总开关当前关闭，自动通知不会发送；手动测试不受它约束。
        </div>
      </div>
    </n-spin>

    <template #footer>
      <div class="footer-bar">
        <span class="row-hint">测试使用设备端已保存的配置，未保存的修改不会生效。</span>
        <n-space :size="8">
          <n-button size="small" @click="emit('update:show', false)">关闭</n-button>
          <n-button size="small" :loading="testing" :disabled="saving" @click="confirmTest">发送测试短信</n-button>
          <n-button size="small" type="primary" :loading="saving" :disabled="testing" @click="save">保存配置</n-button>
        </n-space>
      </div>
    </template>
  </n-modal>
</template>

<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue';
import { useDialog, useMessage } from 'naive-ui';
import { getApiClient } from '@/composables/useApi';
import { Endpoints, type ChannelRules } from '@/api/contract';
import ToggleRow from '@/components/ToggleRow.vue';
import ChannelRulesFields from '@/views/settings/components/ChannelRulesFields.vue';
import { CRITICAL_OVERRIDE_NOTE, PUSH_CHANNEL_SCENES, SCENE_NOTE_BATTERY } from '@/views/settings/notifyShared';

const props = defineProps<{ show: boolean }>();
const emit = defineEmits<{ 'update:show': [boolean] }>();

const message = useMessage();
const dialog = useDialog();
const api = getApiClient();

/**
 * GET api/notify/sms/config 的响应形状。
 *
 * 同构的那一组规则字段（最低级别 / 每日上限 / 用量 / 取值域）直接继承 [ChannelRules] ——
 * 三条渠道的 DTO 各抄一遍会让"同构"只停留在 core 侧。
 */
interface LocalSmsConfigDto extends ChannelRules {
  enabled: boolean;
  target_number: string;
  scenes: string[];
  respect_dnd: boolean;
  configured: boolean;
}

interface LocalSmsTestResult {
  success: boolean;
  auto_notify_enabled: boolean;
  error?: string;
  /** 设备结论，取值来自设备端；一律映射成中文再显示 */
  verdict?: string;
  detail?: string;
  counted_toward_quota: boolean;
  sent_today: number;
  quota_remaining: number;
  daily_limit: number;
  attempted_at?: number;
}

/** 号码去掉分隔符后的长度区间，与设备端的校验同口径。 */
const MIN_NUMBER_CHARS = 3;
const MAX_NUMBER_CHARS = 20;

/** 兜底的每日上限区间：GET 正常时用设备端回的值，这里只在还没读到时撑住输入框。 */
const FALLBACK_LIMIT_MIN = 1;
const FALLBACK_LIMIT_MAX = 50;

/** 只有这条渠道花钱，所以它在共用旋钮下面多一句代价说明（另两条渠道不传这个 prop）。 */
const COST_NOTE = '本机短信按条计费：级别越低、上限越高，可能产生的费用越多。';

const loading = ref(false);
const saving = ref(false);
const testing = ref(false);

const config = ref<LocalSmsConfigDto | null>(null);
const testResult = ref<LocalSmsTestResult | null>(null);

const form = reactive({
  enabled: false,
  target_number: '',
  min_level: 'critical',
  daily_limit: 5 as number | null,
  scenes: [] as string[],
  respect_dnd: true,
});

const dailyLimitMin = computed(() => config.value?.daily_limit_min ?? FALLBACK_LIMIT_MIN);
const dailyLimitMax = computed(() => config.value?.daily_limit_max ?? FALLBACK_LIMIT_MAX);

/**
 * 设备结论 → 中文。
 *
 * 「未回报最终状态」与「无法确认」两档必须带上「可能已发出」：它们记在投递记录里是失败，
 * 但短信很可能真的发出去了。只显示一个「失败」会让用户再点一次 —— 那才是真花钱。
 */
const VERDICT_TEXT: Record<string, string> = {
  SENT: '设备已发出',
  FAILED: '设备发送失败',
  PENDING: '设备未回报最终状态，可能已发出',
  REJECTED: '设备未受理',
  NO_RESPONSE: '无法确认是否已发出',
  EXCEPTION: '发送过程出错',
};

const verdictText = computed(() => {
  const r = testResult.value;
  if (!r) return '';
  if (r.success) return '设备已发出';
  return VERDICT_TEXT[r.verdict ?? ''] ?? '未发出';
});

/** 未确认的两档给「警告」而不是「错误」：它们不是失败，是结果不明。 */
const testTagType = computed<'success' | 'warning' | 'error'>(() => {
  const r = testResult.value;
  if (!r) return 'error';
  if (r.success) return 'success';
  if (r.verdict === 'PENDING' || r.verdict === 'NO_RESPONSE') return 'warning';
  return 'error';
});

const testQuotaText = computed(() => {
  const r = testResult.value;
  if (!r) return '';
  const counted = r.counted_toward_quota ? '已计入今日配额' : '未计入今日配额';
  return `${counted} · 今日已发送 ${r.sent_today}/${r.daily_limit} 条，剩余 ${r.quota_remaining} 条`;
});

/**
 * 号码能不能拨出去，与设备端的判据一致：去掉减号与空格后长度 3~20，
 * 除首位可选的加号外全是数字。真正的强制点仍在设备端，这里只影响能不能提交。
 */
function isDialableNumber(raw: string): boolean {
  const compact = raw.replace(/[-\s]/g, '');
  if (compact.length < MIN_NUMBER_CHARS || compact.length > MAX_NUMBER_CHARS) return false;
  const digits = compact.startsWith('+') ? compact.slice(1) : compact;
  return digits.length > 0 && /^\d+$/.test(digits);
}

function applyConfig(data: LocalSmsConfigDto) {
  config.value = data;
  // 先判数再回落，**不能写成 `Number(...) || 下限`**：`||` 会把 NaN（字段缺失、类型不符）
  // 静默改写成一个看起来正常的数字，回读失败在界面上完全看不出来。
  // 回落值取设备端下发的 `daily_limit_min`（经 dailyLimitMin，它已带 GET 未回时的兜底）——
  // 前端写死一个下限的话，core 改了区间这边不会报错，只会显示一个设备侧不接受的值。
  const limit = Number(data.daily_limit);
  Object.assign(form, {
    enabled: !!data.enabled,
    target_number: data.target_number || '',
    min_level: data.min_level || 'critical',
    daily_limit: Number.isInteger(limit) ? limit : dailyLimitMin.value,
    scenes: [...(data.scenes ?? [])],
    respect_dnd: data.respect_dnd !== false,
  });
}

async function loadConfig() {
  loading.value = true;
  try {
    const { data } = await api.get(Endpoints.notify.smsConfig);
    applyConfig(data);
  } catch (e: any) {
    message.error(e?.response?.data?.error || '加载本机短信配置失败');
  } finally {
    loading.value = false;
  }
}

function validate(): string | null {
  const number = form.target_number.trim();
  if (number && !isDialableNumber(number)) return '接收号码格式不正确，请检查后重填';
  const limit = form.daily_limit;
  if (limit == null || !Number.isInteger(limit) || limit < dailyLimitMin.value || limit > dailyLimitMax.value) {
    return `每日上限需为 ${dailyLimitMin.value} ~ ${dailyLimitMax.value} 之间的整数`;
  }
  return null;
}

async function save() {
  const invalid = validate();
  if (invalid) {
    message.error(invalid);
    return;
  }
  const payload = {
    enabled: form.enabled,
    target_number: form.target_number.trim(),
    min_level: form.min_level,
    daily_limit: Number(form.daily_limit),
    scenes: form.scenes,
    respect_dnd: form.respect_dnd,
  };
  saving.value = true;
  try {
    await api.put(Endpoints.notify.smsConfig, payload);
    // PUT 已经回了新配置，仍再 GET 一次：与邮件卡同口径，今日用量等只读位以回读为准。
    await loadConfig();
    message.success('配置已保存');
  } catch (e: any) {
    message.error(e?.response?.data?.error || '保存失败');
  } finally {
    saving.value = false;
  }
}

/**
 * 测试前的二次确认。
 *
 * 这个按钮与另两条渠道的「发送测试」不是一回事：它会真的从 SIM 卡发出一条短信。
 * 不确认的话，误点一次就是一条话费加一条配额。
 */
function confirmTest() {
  dialog.warning({
    title: '发送测试短信',
    content:
      '设备会立即向接收号码发出一条真实短信，产生一条短信费用，并占用一条今日配额。测试不受总开关、免打扰与场景勾选影响，但仍受最低级别与每日上限约束。',
    positiveText: '发送',
    negativeText: '取消',
    onPositiveClick: () => {
      runTest();
    },
  });
}

async function runTest() {
  testing.value = true;
  try {
    const { data } = await api.post(Endpoints.notify.smsTest, {});
    testResult.value = data;
    // 配额与用量在这一次调用后就变了，回读一次让卡片上的「今日 N/M」跟上
    await loadConfig();
    if (data?.success) {
      message.success(`测试短信已发出（今日 ${data.sent_today}/${data.daily_limit}）`);
    } else if (data?.verdict === 'PENDING' || data?.verdict === 'NO_RESPONSE') {
      message.warning('无法确认是否已发出，设备不会自动重发，请先查看手机是否收到');
    } else {
      message.error(data?.error || '测试短信未发出');
    }
  } catch (e: any) {
    message.error(e?.response?.data?.error || '测试请求失败');
  } finally {
    testing.value = false;
  }
}

// 每次打开都重拉：今日用量会随时间与另一端的操作变化，用上次的缓存会显示过期数字。
watch(
  () => props.show,
  (v) => {
    if (v) loadConfig();
  },
  { immediate: true }
);
</script>

<style scoped>
.row-hint {
  font-size: 12px;
  color: var(--text-muted);
  line-height: 1.5;
}
.field {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 10px 0;
}
.field-label {
  font-size: 13px;
  color: var(--text-secondary);
}
.test-result {
  display: flex;
  flex-direction: column;
  gap: 6px;
  margin-top: 10px;
}
.test-head {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.footer-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
  width: 100%;
}
</style>
