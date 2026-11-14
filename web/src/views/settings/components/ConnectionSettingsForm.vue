<!--
  连接与端口：core 自身的连接参数（设备后台 IP / 端口 / 密码、服务监听端口）。

  ## 为什么抽成独立组件（2026-11-12）
  原来它是 `panels/GeneralPanel.vue` 的全部内容，只能通过 `?tab=general` 进入 ——
  而这一栏一共就 4 个输入框，却要占一条路由、一次页面转场和一整屏留白。
  抽取之后同一份表单有两个宿主：
  - 设置首页的「连接与端口」弹窗（主要入口，见 SettingsView）；
  - `GeneralPanel.vue`（保留旧 `?tab=general` 深链接，内容与弹窗完全一致）。

  **保存逻辑只有这一份**：两个宿主都不持有表单状态，避免出现"弹窗里改了一半、
  页面里还显示旧值"这类双写。

  历史搬迁记录（日志/排障开关、短信验证码、更新源、封面预热等）仍在
  GeneralPanel.vue 的注释里，本组件只负责当前这 4 个键。
-->
<template>
  <div class="connection-form">
    <div class="config-section">
      <div class="section-subtitle">设备后台连接</div>
      <div class="config-grid">
        <div class="config-item">
          <span class="config-label">IP</span>
          <n-input v-model:value="form.goformIp" placeholder="192.168.0.1" size="small" />
        </div>
        <div class="config-item">
          <span class="config-label">设备后台端口</span>
          <n-input-number
            v-model:value="form.goformPort"
            :min="ConfigLimits.goformPort[0]"
            :max="ConfigLimits.goformPort[1]"
            size="small"
          />
        </div>
        <div class="config-item">
          <span class="config-label">密码</span>
          <n-input
            v-model:value="form.goformPassword"
            type="password"
            show-password-on="click"
            placeholder="留空则不修改"
            size="small"
          />
        </div>
        <div class="config-item">
          <span class="config-label">服务监听端口</span>
          <n-input-number
            v-model:value="form.port"
            :min="ConfigLimits.port[0]"
            :max="ConfigLimits.port[1]"
            size="small"
          />
        </div>
      </div>
    </div>

    <!-- 未读到配置时明确禁用：否则表单显示的是前端硬编码初值，
         看起来像设备真实状态，一点保存就把默认值写回设备。 -->
    <div v-if="!loaded" class="section-subtitle">配置未从设备读取，本栏输入与保存已禁用（避免把默认值写回设备）</div>

    <div class="connection-actions">
      <n-button size="small" type="error" quaternary :disabled="saving" @click="resetModalOpen = true">
        恢复默认
      </n-button>
      <span class="connection-spacer"></span>
      <n-button size="small" type="primary" :loading="saving" :disabled="!loaded" @click="save"> 保存 </n-button>
    </div>

    <n-modal
      v-model:show="resetModalOpen"
      preset="card"
      title="恢复默认配置"
      class="modal-fit"
      style="--modal-w: 460px"
      :mask-closable="!resetting"
    >
      <n-alert type="warning" :bordered="false">
        将把端口、设备后台连接、日志、QoS、更新源等配置全部恢复默认，<strong>需重启服务生效</strong>。
        配对信息与配对密码不受影响。
      </n-alert>
      <template #footer>
        <n-space justify="end">
          <n-button size="small" :disabled="resetting" @click="resetModalOpen = false">取消</n-button>
          <n-button size="small" type="error" :loading="resetting" @click="submitReset">恢复默认</n-button>
        </n-space>
      </template>
    </n-modal>
  </div>
</template>

<script setup lang="ts">
import { onMounted, onUnmounted, reactive, ref } from 'vue';
import { useMessage } from 'naive-ui';
import { getApiClient } from '@/composables/useApi';
import { ConfigLimits } from '@/api/contract';
import { buildChangedPayload, commitConfigSave, findUnbaselinedKeys } from '@/views/settings/settingsShared';
import { fetchConfig, updateConfig } from '@/api/config';
import { toApiErrorMessage } from '@/composables/utils';

const message = useMessage();
const api = getApiClient();

const saving = ref(false);
/** 是否已经从 core 读到过配置。false 时表单值只是硬编码初值，禁止编辑与保存。 */
const loaded = ref(false);
const original = reactive<Record<string, unknown>>({});

const form = reactive({
  goformIp: '',
  goformPort: 8080,
  goformPassword: '',
  port: 8080,
});

async function load() {
  try {
    const data = await fetchConfig(api);
    // 请求被取消（组件已卸载）：直接返回，不要落进下面的 catch。
    if (!data) return;
    const mapping: Record<string, string> = {
      goform_ip: 'goformIp',
      goform_port: 'goformPort',
      goform_password: 'goformPassword',
      port: 'port',
    };
    for (const [apiKey, formKey] of Object.entries(mapping)) {
      const value = data[apiKey];
      if (value === undefined) continue;
      switch (formKey) {
        case 'goformIp':
          form.goformIp = String(value);
          break;
        case 'goformPort':
          form.goformPort = Number(value);
          break;
        case 'goformPassword':
          form.goformPassword = String(value);
          break;
        case 'port':
          form.port = Number(value);
          break;
      }
      original[apiKey] = value;
    }
    // core 对 goform_password 做脱敏返回（如 12****78），且会拒绝含 *** 的回写。
    // 直接把脱敏串填进密码框会让用户误以为那是真实密码，改为留空 = 不修改。
    form.goformPassword = '';
    original['goform_password'] = '';
    loaded.value = true;
  } catch {
    loaded.value = false;
    message.warning('设备配置读取失败，本栏配置暂不可编辑');
  }
}

const FORM_KEYS: Record<string, string> = {
  goformIp: 'goform_ip',
  goformPort: 'goform_port',
  goformPassword: 'goform_password',
  port: 'port',
};

function hasUnsavedChanges(): boolean {
  return Object.keys(buildChangedPayload(FORM_KEYS, form, original)).length > 0;
}

async function save() {
  if (!loaded.value) {
    message.warning('配置尚未从设备读取，保存已阻止');
    return;
  }
  const unbaselined = findUnbaselinedKeys(FORM_KEYS, original);
  if (unbaselined.length > 0) {
    message.warning(`以下配置项当前 core 版本不支持，已忽略：${unbaselined.join('、')}`);
  }
  const payload = buildChangedPayload(FORM_KEYS, form, original);
  if (Object.keys(payload).length === 0) {
    message.info('没有更改');
    return;
  }
  saving.value = true;
  try {
    const data = await updateConfig(api, payload);
    commitConfigSave(payload, data, original, '连接与端口', message, load);
  } catch (e) {
    message.error(toApiErrorMessage(e, '保存失败'));
  } finally {
    saving.value = false;
  }
}

const resetModalOpen = ref(false);
const resetting = ref(false);

async function submitReset() {
  resetting.value = true;
  try {
    const { data } = await api.post('/api/config/reset');
    message.warning(data.message || '配置已恢复默认，需重启服务生效');
    resetModalOpen.value = false;
    load();
  } catch (e) {
    message.error(toApiErrorMessage(e, '恢复失败'));
  } finally {
    resetting.value = false;
  }
}

/** 页面重新可见时回读：这些字段的真源在 core，手机端随时可能改。 */
function onVisible() {
  if (document.visibilityState !== 'visible') return;
  if (hasUnsavedChanges()) return;
  load();
}

onMounted(() => {
  load();
  document.addEventListener('visibilitychange', onVisible);
});

onUnmounted(() => {
  document.removeEventListener('visibilitychange', onVisible);
});
</script>

<style scoped>
.connection-form {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.connection-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

.connection-spacer {
  flex: 1;
}
</style>
