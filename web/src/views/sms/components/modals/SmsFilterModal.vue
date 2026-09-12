<!--
  短信拦截规则管理（号码黑名单 + 关键词）。

  为什么是弹窗而不是短信页的第四个 tab：tab 是**内容维度**（短信 / 验证码 / 已拦截），
  规则是设置，混进去会让「切 tab」这件事同时意味着两种操作。入口挂在「短信管理」
  GridCard 的 header 动作区，与站内其它「列表页 + 设置弹窗」的做法一致。

  硬边界：**web 不做任何拦截判定**。命中与否全在 core（命中就不推、不发、不返回），
  这里只做规则的增删改查与两个开关的读写。
-->
<template>
  <n-modal v-model:show="show" preset="card" title="短信拦截规则" style="width: 560px; max-width: calc(100vw - 32px)">
    <n-spin :show="loading">
      <!--
        豁免开关：默认值与 core 的 `smsFilterExemptVerificationCode` 一致（true），
        且在读到 GET /api/config 之前禁用 —— 否则界面上是个「看着像已生效」的假开关。
      -->
      <ToggleRow
        label="验证码豁免关键词拦截"
        description="开启后验证码短信不受关键词规则影响，但仍受号码黑名单约束"
        :model-value="exempt"
        :loading="savingExempt"
        :disabled="!configLoaded || savingExempt"
        @update:model-value="saveExempt"
      />
      <div v-if="!configLoaded" class="hint-text">豁免开关状态尚未从设备读到，暂不可修改。</div>

      <div class="rules-toolbar">
        <span class="section-title">规则（{{ rules.length }} 条）</span>
        <n-space :size="8">
          <n-button size="tiny" quaternary :loading="loading" @click="loadRules">刷新</n-button>
          <n-button size="tiny" type="primary" @click="openForm(null)">新增规则</n-button>
        </n-space>
      </div>

      <!-- 新增 / 编辑表单：pattern 必填，scope 与 match_type 只有 core 认的那几个取值 -->
      <div v-if="formVisible" class="rule-form sub-panel">
        <div class="field">
          <span class="field-label">号码或关键词</span>
          <n-input v-model:value="form.pattern" size="small" placeholder="如 10086 或 中奖" />
        </div>
        <div class="form-grid">
          <div class="field">
            <span class="field-label">作用域</span>
            <n-select v-model:value="form.scope" size="small" :options="scopeOptions" />
          </div>
          <div class="field">
            <span class="field-label">匹配方式</span>
            <n-select v-model:value="form.matchType" size="small" :options="matchOptions" />
          </div>
        </div>
        <div class="field">
          <span class="field-label">备注（可选）</span>
          <n-input v-model:value="form.note" size="small" placeholder="给自己看的说明" />
        </div>
        <div class="form-actions">
          <n-button size="small" @click="closeForm">取消</n-button>
          <n-button size="small" type="primary" :loading="saving" @click="submitRule">
            {{ editingId === null ? '添加' : '保存' }}
          </n-button>
        </div>
      </div>

      <div v-if="rules.length === 0 && !loading" class="hint-text empty-hint">
        还没有任何拦截规则。命中规则的短信不会提醒、不会转发，也不会出现在短信列表里，可在「已拦截」中自查。
      </div>
      <div v-else class="rule-list">
        <div v-for="r in rules" :key="r.id" class="rule-row" :class="{ 'rule-off': !r.enabled }">
          <div class="rule-main">
            <div class="rule-pattern">{{ r.pattern }}</div>
            <div class="rule-meta">
              {{ scopeLabel(r.scope) }} · {{ matchLabel(r.match_type) }} · 命中 {{ r.hit_count }} 次
              <template v-if="r.note"> · {{ r.note }}</template>
            </div>
          </div>
          <div class="rule-actions">
            <n-button size="tiny" quaternary @click="openForm(r)">编辑</n-button>
            <n-switch
              size="small"
              :value="r.enabled"
              :loading="togglingId === r.id"
              @update:value="(v: boolean) => toggleRule(r, v)"
            />
            <n-button size="tiny" quaternary type="error" @click="confirmDelete(r)">删除</n-button>
          </div>
        </div>
      </div>
    </n-spin>

    <template #footer>
      <div class="modal-footer">
        <n-button size="small" @click="show = false">关闭</n-button>
      </div>
    </template>
  </n-modal>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted } from 'vue';
import { useMessage, useDialog } from 'naive-ui';
import { useCancellableApi } from '@/composables/useCancellableApi';
import {
  Endpoints,
  SmsRuleScope,
  SmsRuleMatch,
  SmsRuleScopeLabels,
  SmsRuleMatchLabels,
  type SmsRuleItem,
} from '@/api/contract';
import ToggleRow from '@/components/ToggleRow.vue';

const props = defineProps<{ show: boolean }>();
const emit = defineEmits<{
  'update:show': [boolean];
  /** 规则集合发生变化 —— 让「已拦截」列表刷新「命中规则是否还在/是否已停用」的判断。 */
  changed: [];
}>();

const show = computed({
  get: () => props.show,
  set: (v) => emit('update:show', v),
});

const message = useMessage();
const dialog = useDialog();
const api = useCancellableApi();

const rules = ref<SmsRuleItem[]>([]);
const loading = ref(false);
const saving = ref(false);
const togglingId = ref<number | null>(null);

/** 与 core 的默认值一致（true）。configLoaded 为 false 时开关禁用，避免「假开关」。 */
const exempt = ref(true);
const configLoaded = ref(false);
const savingExempt = ref(false);

const scopeOptions = SmsRuleScope.map((s) => ({ label: SmsRuleScopeLabels[s], value: s }));
const matchOptions = SmsRuleMatch.map((m) => ({ label: SmsRuleMatchLabels[m], value: m }));

const formVisible = ref(false);
const editingId = ref<number | null>(null);
const form = reactive({
  pattern: '',
  // 新规则默认「正文 + 包含」，与 core 侧 POST /api/sms/rules 的缺省值一致
  scope: 'body' as (typeof SmsRuleScope)[number],
  matchType: 'contains' as (typeof SmsRuleMatch)[number],
  note: '',
});

function scopeLabel(scope: string): string {
  return SmsRuleScopeLabels[scope as (typeof SmsRuleScope)[number]] ?? scope;
}

function matchLabel(matchType: string): string {
  return SmsRuleMatchLabels[matchType as (typeof SmsRuleMatch)[number]] ?? matchType;
}

function errorText(e: any, fallback: string): string {
  return e?.response?.data?.message || e?.response?.data?.error || fallback;
}

async function loadRules() {
  loading.value = true;
  try {
    const { data } = await api.get(Endpoints.sms.rules);
    if (!data) return;
    rules.value = (data.rules || []) as SmsRuleItem[];
  } catch (e: any) {
    message.error(errorText(e, '加载拦截规则失败'));
  } finally {
    loading.value = false;
  }
}

/** 只读豁免开关这一个键；范围更大的通用设置在设置页，那边有自己的差量提交。 */
async function loadExempt() {
  try {
    const { data } = await api.get(Endpoints.config.root);
    if (!data) return;
    const v = data.sms_filter_exempt_verification_code;
    if (typeof v === 'boolean') {
      exempt.value = v;
      configLoaded.value = true;
    }
  } catch {
    configLoaded.value = false;
  }
}

/**
 * 单字段 PUT。失败或被 core 拒收时回滚本地值 ——
 * 「点了没反应但界面已经变了」比报错更难排查。
 */
async function saveExempt(v: boolean) {
  const prev = exempt.value;
  exempt.value = v;
  savingExempt.value = true;
  try {
    const { data } = await api.put(Endpoints.config.root, { sms_filter_exempt_verification_code: v });
    if (!data) {
      exempt.value = prev;
      return;
    }
    const updated: string[] = data.updated_fields || [];
    if (!updated.includes('sms_filter_exempt_verification_code')) {
      exempt.value = prev;
      message.error('豁免开关未生效');
      return;
    }
    message.success(v ? '验证码短信不再受关键词规则影响' : '验证码短信也按关键词规则拦截');
  } catch (e: any) {
    exempt.value = prev;
    message.error(errorText(e, '保存失败'));
  } finally {
    savingExempt.value = false;
  }
}

function openForm(rule: SmsRuleItem | null) {
  editingId.value = rule?.id ?? null;
  form.pattern = rule?.pattern ?? '';
  form.scope = rule?.scope ?? 'body';
  form.matchType = rule?.match_type ?? 'contains';
  form.note = rule?.note ?? '';
  formVisible.value = true;
}

function closeForm() {
  formVisible.value = false;
  editingId.value = null;
}

async function submitRule() {
  const pattern = form.pattern.trim();
  // core 也会拦空 pattern（`contains ""` 会命中每一条短信），但错误提示要在这里给，
  // 否则用户只看到一个 400。
  if (!pattern) {
    message.error('请填写要拦截的号码或关键词');
    return;
  }
  const body = { pattern, scope: form.scope, match_type: form.matchType, note: form.note.trim() };
  saving.value = true;
  try {
    if (editingId.value === null) {
      await api.post(Endpoints.sms.rules, body);
      message.success('规则已添加');
    } else {
      await api.put(Endpoints.sms.rule(editingId.value), body);
      message.success('规则已保存');
    }
    closeForm();
    await loadRules();
    emit('changed');
  } catch (e: any) {
    message.error(errorText(e, '保存规则失败'));
  } finally {
    saving.value = false;
  }
}

/** 启停：PUT 只带 `enabled`，其余字段由 core 的字段级合并保留原值。 */
async function toggleRule(rule: SmsRuleItem, enabled: boolean) {
  togglingId.value = rule.id;
  const hit = rules.value.find((r) => r.id === rule.id);
  if (hit) hit.enabled = enabled;
  try {
    await api.put(Endpoints.sms.rule(rule.id), { enabled });
    emit('changed');
  } catch (e: any) {
    if (hit) hit.enabled = !enabled;
    message.error(errorText(e, '切换失败'));
  } finally {
    togglingId.value = null;
  }
}

function confirmDelete(rule: SmsRuleItem) {
  dialog.warning({
    title: '删除规则',
    content: `删除后命中「${rule.pattern}」的短信会重新提醒与转发。已被拦下的短信不会补发。`,
    positiveText: '删除',
    negativeText: '取消',
    onPositiveClick: async () => {
      try {
        await api.delete(Endpoints.sms.rule(rule.id));
        rules.value = rules.value.filter((r) => r.id !== rule.id);
        message.success('已删除');
        emit('changed');
      } catch (e: any) {
        message.error(errorText(e, '删除失败'));
      }
    },
  });
}

onMounted(() => {
  loadRules();
  loadExempt();
});
</script>

<style scoped>
.modal-footer {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}
.hint-text {
  font-size: 12px;
  color: var(--text-muted);
  line-height: 1.5;
}
.empty-hint {
  padding: 12px 0;
}
.rules-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin: 14px 0 8px;
}
.section-title {
  font-size: 13px;
  font-weight: 500;
  color: var(--text-secondary);
}
/* 描边/内距/圆角/底色走 main.css 的全局 .sub-panel */
.rule-form {
  margin-bottom: 10px;
}
.form-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 0 12px;
}
.field {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 6px 0;
}
.field-label {
  font-size: 12px;
  color: var(--text-secondary);
}
.form-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  padding-top: 8px;
}
.rule-list {
  display: flex;
  flex-direction: column;
}
.rule-row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px 0;
  border-bottom: 1px solid var(--border-subtle);
}
.rule-row:last-child {
  border-bottom: none;
}
.rule-off {
  opacity: 0.55;
}
.rule-main {
  flex: 1;
  min-width: 0;
}
.rule-pattern {
  font-size: 13px;
  font-weight: 500;
  color: var(--text-primary);
  word-break: break-all;
}
.rule-meta {
  margin-top: 2px;
  font-size: 12px;
  color: var(--text-muted);
}
.rule-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-shrink: 0;
}

@media (max-width: 768px) {
  .form-grid {
    grid-template-columns: 1fr;
  }
}
</style>
