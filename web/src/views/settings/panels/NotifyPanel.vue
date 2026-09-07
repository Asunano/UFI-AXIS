<!--
  通知分栏面板。

  这里原本是独立的「短信转发」页面（src/views/smsforward/SmsForwardView.vue），
  现已并入设置页的「通知」分栏，作为通知设置的第一块内容。

  2026-08-29：功能改名「邮件通知」，通道只剩 SMTP —— curl 回调与钉钉机器人连同
  `method` 字段一并从 core 删除。同时新增「邮件转发范围」（`scenes`）：
  勾中的通知场景在 app 推送系统通知后会经 `POST /api/sms-forward/notify` 再发一封邮件。

  后端路径**没有跟着改名**（仍是 `/api/sms-forward/*`，`contract.ts` 里的
  `smsForward` 常量同样保留）：API 手册与 app 端都按该路径引用，改名只会破坏跨端契约。
-->
<template>
  <div class="settings-panel">
    <GridCard title="邮件通知">
      <template #extra>
        <n-button size="small" quaternary :loading="loading" @click="loadConfig">刷新</n-button>
      </template>

      <n-spin :show="loading">
        <ToggleRow
          v-model="form.enabled"
          label="启用邮件通知"
          description="关闭后 core 既不转发短信，也不再发送通知场景邮件"
        />

        <template v-if="form.enabled">
          <div class="grid">
            <div class="field">
              <span class="field-label">SMTP 服务器</span>
              <n-input v-model:value="form.smtp_host" size="small" placeholder="smtp.example.com" />
            </div>
            <div class="field">
              <span class="field-label">端口</span>
              <n-input-number v-model:value="form.smtp_port" :min="1" :max="65535" size="small" />
            </div>
            <div class="field">
              <span class="field-label">用户名</span>
              <n-input v-model:value="form.smtp_user" size="small" placeholder="you@example.com" />
            </div>
            <div class="field">
              <span class="field-label">{{ config.smtp_pass_set ? '密码（已设置，留空不修改）' : '密码' }}</span>
              <n-input
                v-model:value="form.smtp_pass"
                type="password"
                show-password-on="click"
                size="small"
                placeholder="留空则保留原密码"
              />
            </div>
            <div class="field">
              <span class="field-label">发件地址</span>
              <n-input v-model:value="form.smtp_from" size="small" placeholder="留空则使用用户名" />
            </div>
            <div class="field">
              <span class="field-label">收件地址</span>
              <n-input v-model:value="form.smtp_to" size="small" placeholder="to@example.com" />
            </div>
          </div>

          <div class="field">
            <span class="field-label">邮件转发范围</span>
            <n-checkbox-group v-model:value="form.scenes">
              <n-space :size="[14, 6]">
                <n-checkbox v-for="s in MAIL_SCENES" :key="s.id" :value="s.id" :label="s.label" />
              </n-space>
            </n-checkbox-group>
            <span class="row-hint">勾选的通知在推送到手机的同时发一封邮件；短信正文始终转发，无需勾选</span>
          </div>

          <ToggleRow
            v-model="form.forward_dev_info"
            label="附加设备信息"
            description="邮件正文里附带设备名与信号等信息"
          />

          <!--
            短信黑名单的编辑入口暂时不在这里露出（下个版本再做）。
            现在 core 的实现（SmsForwardController.forwardSms）是「发件号码或正文子串命中即不转发」，
            有三个待定的语义问题：正文也参与匹配、号码不做 +86 归一化、且不影响系统通知。
            要把这些理顺需要连 app 端一起改，改动面较大，所以先只藏入口。
            **注意不要顺手删掉 form.blacklist**：它仍要参与 GET 回填与 POST 提交，
            否则在这里保存一次配置就会把已经配好的名单清空。
          -->
        </template>
      </n-spin>

      <template #footer>
        <n-space :size="8">
          <n-button v-if="form.enabled" size="small" :loading="testing" @click="runTest"> 测试发送 </n-button>
          <n-button size="small" type="primary" :loading="saving" @click="save">保存配置</n-button>
        </n-space>
      </template>
    </GridCard>

    <GridCard title="配置体检">
      <template #extra>
        <n-button size="small" quaternary :loading="diagLoading" @click="loadDiagnose">重新检查</n-button>
      </template>
      <div v-if="!diagnose" class="row-hint">点击「重新检查」调用 core 的 /api/sms-forward/diagnose。</div>
      <div v-else class="diag-grid">
        <div v-for="item in diagRows" :key="item.label" class="diag-item">
          <span class="row-hint">{{ item.label }}</span>
          <n-tag :type="item.ok ? 'success' : 'warning'" size="tiny" :bordered="false">{{ item.text }}</n-tag>
        </div>
      </div>
    </GridCard>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted } from 'vue';
import { useMessage } from 'naive-ui';
import { getApiClient } from '@/composables/useApi';
import GridCard from '@/components/GridCard.vue';
import ToggleRow from '@/components/ToggleRow.vue';

const message = useMessage();
const api = getApiClient();

/** GET /api/sms-forward/config 的响应形状（凭据字段只回 `*_set` 屏蔽位，不回明文）。 */
interface SmsForwardConfig {
  enabled: boolean;
  smtp_host: string;
  smtp_port: number;
  smtp_user: string;
  smtp_pass_set: boolean;
  smtp_from: string;
  smtp_to: string;
  forward_dev_info: boolean;
  blacklist: string[];
  /** 需要同时发邮件的通知场景 id 白名单（NotifyScene.sceneId） */
  scenes: string[];
}

/**
 * 可勾选的通知场景。id 必须与 app 的 `NotifyScene.sceneId` 完全一致 ——
 * core 只做集合包含判断，写错的 id 会静默不发信。
 *
 * 不含 `sms`：短信走 core 的专用转发链路（带黑名单过滤），勾它会一条短信两封邮件。
 */
const MAIL_SCENES = [
  { id: 'alert', label: '阈值告警' },
  { id: 'connectivity', label: '设备离线/上线' },
  { id: 'verification', label: '验证码提取' },
  { id: 'traffic80', label: '流量预警' },
  { id: 'download', label: '下载完成/失败' },
  { id: 'tunnel', label: '隧道异常' },
  { id: 'events', label: '设备事件' },
];

function emptyConfig(): SmsForwardConfig {
  return {
    enabled: false,
    smtp_host: '',
    smtp_port: 465,
    smtp_user: '',
    smtp_pass_set: false,
    smtp_from: '',
    smtp_to: '',
    forward_dev_info: false,
    blacklist: [],
    scenes: [],
  };
}

/** core 回读的原始配置（用于 `*_set` 提示与保存时的差异判断）。 */
const config = ref<SmsForwardConfig>(emptyConfig());

/**
 * 表单草稿。凭据字段（`smtp_pass`）**恒以空串开始**：
 * core 的 POST 把「空串」与「不传」都当作「保留原值」，因此留空即不修改，
 * 也就无法用该端点清空已设置的凭据 —— 只能覆盖成新的非空值。
 */
const form = reactive({
  ...emptyConfig(),
  smtp_pass: '',
});

const loading = ref(false);
const saving = ref(false);
const testing = ref(false);

function applyConfig(data: Partial<SmsForwardConfig>) {
  const merged = { ...emptyConfig(), ...(data || {}) };
  config.value = merged;
  Object.assign(form, merged, { smtp_pass: '' });
}

async function loadConfig() {
  loading.value = true;
  try {
    const { data } = await api.get('/api/sms-forward/config');
    applyConfig(data);
  } catch (e: any) {
    message.error(e?.response?.data?.error || '加载邮件通知配置失败');
  } finally {
    loading.value = false;
  }
}

/**
 * core 侧对这组字段**没有任何校验**（类型不符会静默回落原值，地址/端口一律不校验），
 * 所以必填项只能在这里拦住，否则用户会得到一个「保存成功但永远发不出去」的配置。
 */
function validate(): string | null {
  if (!form.enabled) return null;
  if (!form.smtp_host.trim()) return '请填写 SMTP 服务器';
  if (!form.smtp_user.trim()) return '请填写 SMTP 用户名';
  if (!form.smtp_to.trim()) return '请填写收件地址';
  if (!config.value.smtp_pass_set && !form.smtp_pass) return '请填写 SMTP 密码';
  if (!Number.isInteger(form.smtp_port) || form.smtp_port < 1 || form.smtp_port > 65535) {
    return 'SMTP 端口需在 1 ~ 65535 之间';
  }
  return null;
}

async function save() {
  const invalid = validate();
  if (invalid) {
    message.error(invalid);
    return;
  }
  const payload: Record<string, any> = {
    enabled: form.enabled,
    smtp_host: form.smtp_host,
    smtp_port: form.smtp_port,
    smtp_user: form.smtp_user,
    smtp_from: form.smtp_from,
    smtp_to: form.smtp_to,
    forward_dev_info: form.forward_dev_info,
    // 编辑入口已隐藏，但仍要原样回传：core 那边「未传保留原值」虽然也能兜住，
    // 显式带上更不容易被后来的改动误伤（比如有人把 payload 改成只传变更字段）
    blacklist: form.blacklist,
    scenes: form.scenes,
  };
  // 空串会被 core 当作「保留原值」，显式不传更贴合语义
  if (form.smtp_pass) payload.smtp_pass = form.smtp_pass;

  saving.value = true;
  try {
    await api.post('/api/sms-forward/config', payload);
    // POST 只回 { success: true }，不回新配置，必须重新 GET 才能刷新 *_set 屏蔽位
    await loadConfig();
    message.success('配置已保存');
  } catch (e: any) {
    message.error(e?.response?.data?.error || '保存失败');
  } finally {
    saving.value = false;
  }
}

/** 测试用的是 core 已持久化的配置，未保存的改动不会生效。 */
async function runTest() {
  testing.value = true;
  try {
    const { data } = await api.post('/api/sms-forward/test', {});
    if (data?.success) message.success('测试邮件已发送');
    else message.error(data?.error || '测试发送失败');
  } catch (e: any) {
    message.error(e?.response?.data?.error || '测试发送失败');
  } finally {
    testing.value = false;
  }
}

// ── 配置体检 ──
interface SmsForwardDiagnose {
  config_enabled: boolean;
  smtp_host_set: boolean;
  smtp_port: number;
  smtp_user_set: boolean;
  smtp_pass_set: boolean;
  smtp_to_set: boolean;
  /** core 判定的「可发信」：enabled + 服务器/用户名/密码/收件地址齐全 */
  sendable: boolean;
  /** 已勾选的通知场景数 */
  scene_count: number;
}

const diagnose = ref<SmsForwardDiagnose | null>(null);
const diagLoading = ref(false);

const diagRows = computed(() => {
  const d = diagnose.value;
  if (!d) return [];
  return [
    { label: '邮件通知总开关', ok: d.config_enabled, text: d.config_enabled ? '已启用' : '未启用' },
    { label: '可发信', ok: d.sendable, text: d.sendable ? '就绪' : '配置不完整' },
    { label: 'SMTP 服务器', ok: d.smtp_host_set, text: d.smtp_host_set ? '已填写' : '缺失' },
    { label: 'SMTP 端口', ok: d.smtp_port > 0, text: String(d.smtp_port) },
    { label: 'SMTP 用户名', ok: d.smtp_user_set, text: d.smtp_user_set ? '已填写' : '缺失' },
    { label: 'SMTP 密码', ok: d.smtp_pass_set, text: d.smtp_pass_set ? '已设置' : '缺失' },
    { label: '收件地址', ok: d.smtp_to_set, text: d.smtp_to_set ? '已填写' : '缺失' },
    { label: '转发场景', ok: d.scene_count > 0, text: `${d.scene_count} 个` },
  ];
});

async function loadDiagnose() {
  diagLoading.value = true;
  try {
    const { data } = await api.get('/api/sms-forward/diagnose');
    diagnose.value = data;
  } catch (e: any) {
    message.error(e?.response?.data?.error || '体检失败');
  } finally {
    diagLoading.value = false;
  }
}

onMounted(() => {
  loadConfig();
});
</script>

<style scoped>
/* .settings-panel 栅格与断点已统一到 src/styles/main.css（全局，8 个面板共用一份） */
/* .row-hint 仍在用：字段说明、体检项标签都靠它，不随 ToggleRow 一起退役 */
.row-hint {
  font-size: 12px;
  color: var(--text-muted);
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
.grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 0 14px;
}
.diag-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 10px 14px;
}
.diag-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

@media (max-width: 768px) {
  .grid,
  .diag-grid {
    grid-template-columns: 1fr;
  }
}
</style>
