<template>
  <div class="settings-panel">
    <!-- Card 2: 通用设置 -->
    <GridCard title="通用设置">
      <template #extra>
        <n-button size="tiny" type="error" quaternary @click="resetConfig">恢复默认</n-button>
      </template>
      <div class="config-section">
        <div class="section-subtitle">Goform 连接</div>
        <div class="config-grid">
          <div class="config-item">
            <span class="config-label">IP</span>
            <n-input v-model:value="generalForm.goformIp" placeholder="192.168.0.1" size="small" />
          </div>
          <div class="config-item">
            <span class="config-label">Goform 端口</span>
            <n-input-number
              v-model:value="generalForm.goformPort"
              :min="ConfigLimits.goformPort[0]"
              :max="ConfigLimits.goformPort[1]"
              size="small"
            />
          </div>
          <div class="config-item">
            <span class="config-label">密码</span>
            <n-input
              v-model:value="generalForm.goformPassword"
              type="password"
              show-password-on="click"
              placeholder="留空则不修改"
              size="small"
            />
          </div>
          <div class="config-item">
            <span class="config-label">服务监听端口</span>
            <n-input-number
              v-model:value="generalForm.port"
              :min="ConfigLimits.port[0]"
              :max="ConfigLimits.port[1]"
              size="small"
            />
          </div>
        </div>
      </div>
      <div class="config-section">
        <div v-if="!generalLoaded" class="section-subtitle">配置未从设备读取，开关已禁用（避免把默认值写回设备）</div>
        <div class="config-grid">
          <!-- 开机自启已移到「服务控制」卡片（立即生效 + 有状态回读）。
               此处原来有第二个开关写同一个后端字段 auto_start_on_boot，两者互不刷新。 -->
          <div class="config-item switch-item">
            <span class="config-label">日志总开关</span>
            <n-switch v-model:value="generalForm.logEnabled" :disabled="!generalLoaded" />
          </div>
          <div class="config-item switch-item">
            <span class="config-label">后端日志</span>
            <n-switch
              v-model:value="generalForm.coreLogEnabled"
              :disabled="!generalLoaded || !generalForm.logEnabled"
            />
          </div>
          <div class="config-item switch-item">
            <span class="config-label">手机端日志</span>
            <n-switch v-model:value="generalForm.appLogEnabled" :disabled="!generalLoaded || !generalForm.logEnabled" />
          </div>
          <div class="config-item switch-item">
            <span class="config-label">详细日志（Debug）</span>
            <n-switch v-model:value="generalForm.debugMode" :disabled="!generalLoaded || !generalForm.logEnabled" />
          </div>
          <div class="config-item switch-item">
            <span class="config-label">设备原始字段 dump（排障用，默认关）</span>
            <n-switch v-model:value="generalForm.goformDumpEnabled" :disabled="!generalLoaded" />
          </div>
        </div>
      </div>
      <n-divider style="margin: 10px 0" />
      <div class="config-section">
        <div class="section-subtitle">短信验证码</div>
        <div class="config-grid">
          <div class="config-item switch-item">
            <span class="config-label">启用验证码提取</span>
            <n-switch v-model:value="generalForm.smsCodeEnabled" />
          </div>
          <div class="config-item">
            <span class="config-label">清理周期 (小时)</span>
            <n-input-number
              v-model:value="generalForm.smsCodeCleanupHours"
              :min="ConfigLimits.smsCodeCleanupHours[0]"
              :max="ConfigLimits.smsCodeCleanupHours[1]"
              size="small"
            />
          </div>
        </div>
      </div>
      <n-divider style="margin: 10px 0" />
      <div class="config-section">
        <div class="section-subtitle">更新配置</div>
        <div class="config-grid">
          <div class="config-item">
            <span class="config-label">更新源 URL</span>
            <n-input v-model:value="generalForm.updateUrl" placeholder="https://example.com/update.json" size="small" />
          </div>
          <div class="config-item">
            <span class="config-label">镜像基地址</span>
            <n-input
              v-model:value="generalForm.updateMirrorBase"
              placeholder="https://mirror.example.com"
              size="small"
            />
          </div>
        </div>
      </div>
      <div class="card-actions">
        <n-button type="primary" size="small" :loading="savingGeneral" :disabled="!generalLoaded" @click="saveGeneral"
          >保存</n-button
        >
      </div>
    </GridCard>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted, onUnmounted } from 'vue';
import { useMessage, useDialog } from 'naive-ui';
import { getApiClient } from '@/composables/useApi';
import GridCard from '@/components/GridCard.vue';
import { ConfigLimits } from '@/api/contract';
import { buildChangedPayload, commitConfigSave } from '@/views/settings/settingsShared';

const message = useMessage();
const dialog = useDialog();
const api = getApiClient();

// ── 通用设置 ──

const savingGeneral = ref(false);
/** 是否已经从 core 读到过配置。false 时表单值只是硬编码初值，禁止编辑与保存。 */
const generalLoaded = ref(false);
const generalOriginal = reactive<Record<string, any>>({});
const generalForm = reactive({
  goformIp: '',
  goformPort: 8080,
  goformPassword: '',
  port: 8080,
  // autoStart 已移除：开机自启由「服务控制」卡片经 /api/service/autostart 单独管理，
  // 留在这里会形成第二份写同一后端字段的表单值。
  // 这些初值只在「还没读到设备配置」时短暂出现，且此时开关与保存都是禁用的
  // （见 generalLoaded）。日志总闸与 core 的 DEFAULT_LOG_ENABLED 一致：默认关。
  logEnabled: false,
  coreLogEnabled: true,
  appLogEnabled: true,
  debugMode: false,
  // GET /api/device/goform（设备原始 dump）的开关，core 侧默认关，关着时该端点回 403
  goformDumpEnabled: false,
  smsCodeEnabled: false,
  smsCodeCleanupHours: 24,
  updateUrl: '',
  updateMirrorBase: '',
});

async function loadGeneralConfig() {
  try {
    const { data } = await api.get('/api/config');
    const mapping: Record<string, string> = {
      goform_ip: 'goformIp',
      goform_port: 'goformPort',
      goform_password: 'goformPassword',
      port: 'port',
      log_enabled: 'logEnabled',
      core_log_enabled: 'coreLogEnabled',
      app_log_enabled: 'appLogEnabled',
      debug_mode: 'debugMode',
      goform_dump_enabled: 'goformDumpEnabled',
      sms_code_enabled: 'smsCodeEnabled',
      sms_code_cleanup_hours: 'smsCodeCleanupHours',
      update_url: 'updateUrl',
      update_mirror_base: 'updateMirrorBase',
    };
    for (const [apiKey, formKey] of Object.entries(mapping)) {
      if (data[apiKey] !== undefined) {
        (generalForm as any)[formKey] = data[apiKey];
        generalOriginal[apiKey] = data[apiKey];
      }
    }
    // core 对 goform_password 做脱敏返回（如 12****78），且会拒绝含 *** 的回写。
    // 直接把脱敏串填进密码框会让用户误以为那是真实密码，改为留空 = 不修改。
    generalForm.goformPassword = '';
    generalOriginal['goform_password'] = '';
    generalLoaded.value = true;
  } catch {
    // 2026-09-04：这里原来是 silent catch，表单于是停在硬编码初值上（日志开关全 true）
    // 却看起来像设备的真实状态 —— 用户在手机端刚关掉的开关，在 web 上显示为开启，
    // 一点保存就把它又打开了。现在明确标记「未加载」：开关与保存按钮全部禁用。
    generalLoaded.value = false;
    message.warning('设备配置读取失败，通用设置暂不可编辑');
  }
}

/**
 * 表单字段 ↔ core 配置键。提到函数外是因为「有没有未保存改动」的判断也要用它
 * （见 [hasUnsavedChanges]），放在 saveGeneral 里就得抄第二份。
 */
const GENERAL_FORM_KEYS: Record<string, string> = {
  goformIp: 'goform_ip',
  goformPort: 'goform_port',
  goformPassword: 'goform_password',
  port: 'port',
  logEnabled: 'log_enabled',
  coreLogEnabled: 'core_log_enabled',
  appLogEnabled: 'app_log_enabled',
  debugMode: 'debug_mode',
  goformDumpEnabled: 'goform_dump_enabled',
  smsCodeEnabled: 'sms_code_enabled',
  smsCodeCleanupHours: 'sms_code_cleanup_hours',
  updateUrl: 'update_url',
  updateMirrorBase: 'update_mirror_base',
};

function hasUnsavedChanges(): boolean {
  return Object.keys(buildChangedPayload(GENERAL_FORM_KEYS, generalForm, generalOriginal)).length > 0;
}

async function saveGeneral() {
  if (!generalLoaded.value) {
    message.warning('配置尚未从设备读取，保存已阻止');
    return;
  }
  const payload = buildChangedPayload(GENERAL_FORM_KEYS, generalForm, generalOriginal);
  if (Object.keys(payload).length === 0) {
    message.info('没有更改');
    return;
  }
  savingGeneral.value = true;
  try {
    const { data } = await api.put('/api/config', payload);
    commitConfigSave(payload, data, generalOriginal, '通用设置', message, loadGeneralConfig);
  } catch (e: any) {
    message.error(e?.response?.data?.error || '保存失败');
  } finally {
    savingGeneral.value = false;
  }
}

function resetConfig() {
  dialog.warning({
    title: '恢复默认配置',
    content: '将把包含端口、Goform 密码在内的全部配置恢复默认，并需重启服务生效。确定继续？',
    positiveText: '恢复默认',
    negativeText: '取消',
    onPositiveClick: async () => {
      try {
        const { data } = await api.post('/api/config/reset');
        message.warning(data.message || '配置已恢复默认，需重启服务生效');
        // 只刷新本分栏：QoS / 版本信息等其余分栏此刻并未挂载，
        // 下次打开它们时会各自 onMounted 重新拉取，无需在这里代劳。
        loadGeneralConfig();
      } catch (e: any) {
        message.error(e?.response?.data?.error || '恢复失败');
      }
    },
  });
}

/**
 * 页面重新可见时重新拉一次配置。
 *
 * 为什么需要：这些开关的唯一真源在 core，而**手机端 App 随时可以改它们**。
 * 原来本面板只在 onMounted 拉一次，于是「用户在手机上关掉日志 → 切回 web 这个标签页」
 * 看到的仍是几十分钟前的快照，表现为「手机端已关闭，web 端却显示开启」。
 *
 * 有未保存改动时跳过：否则用户填了一半的表单会被后台状态冲掉。
 */
function onVisible() {
  if (document.visibilityState !== 'visible') return;
  if (hasUnsavedChanges()) return;
  loadGeneralConfig();
}

onMounted(() => {
  loadGeneralConfig();
  document.addEventListener('visibilitychange', onVisible);
});

onUnmounted(() => {
  document.removeEventListener('visibilitychange', onVisible);
});
</script>

<style scoped>
/* .settings-panel 栅格与断点已统一到 src/styles/main.css（全局，8 个面板共用一份） */

/* ── Config layout ── */
.config-section {
  margin-bottom: 4px;
}
.config-section:last-child {
  margin-bottom: 0;
}
.section-subtitle {
  font-size: 12px;
  color: var(--text-muted);
  margin-bottom: 8px;
  font-weight: 500;
}
.config-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12px;
}
.config-item {
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.config-label {
  font-size: 13px;
  color: var(--text-secondary);
}
.switch-item {
  flex-direction: row;
  align-items: center;
  justify-content: space-between;
}
.inline-group {
  display: flex;
  gap: 8px;
  align-items: center;
}

/* .card-actions 已统一到 src/styles/main.css（全局一份，四个面板共用） */

/* ── Responsive ── */
@media (max-width: 768px) {
  .config-grid {
    grid-template-columns: 1fr;
  }
}
</style>
