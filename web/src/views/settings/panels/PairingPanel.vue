<!--
  配对分栏面板。

  这里原本是独立的「配对」页面（src/views/pairing/PairingView.vue），
  现已并入设置页的「配对」分栏。逻辑与模板原样搬运，未改动任何接口路径/请求体/字段名。
-->
<template>
  <div class="settings-panel">
    <!-- 配对状态卡片 -->
    <GridCard title="配对管理">
      <n-spin :show="loading">
        <div v-if="status" class="pairing-content">
          <div class="status-badge" :class="status.paired ? 'paired' : 'unpaired'">
            <span class="status-dot" :class="status.paired ? 'active' : 'inactive'"></span>
            {{ status.paired ? `已配对 ${pairedCount} 台` : '暂无配对设备' }}
          </div>

          <div class="info-grid">
            <InfoRow label="设备标识">{{ status.device_id }}</InfoRow>
            <InfoRow label="配对码">
              <code class="pairing-code">{{ status.pairing_code || '—' }}</code>
            </InfoRow>
          </div>

          <!-- 已配对设备列表 -->
          <div v-if="pairedDevices.length > 0" class="device-list-section">
            <div class="section-title">已配对设备</div>
            <div class="device-list">
              <div v-for="(dev, idx) in pairedDevices" :key="dev.fingerprint" class="device-item">
                <div class="device-info">
                  <span class="device-idx">#{{ idx + 1 }}</span>
                  <div class="device-name-block">
                    <span class="device-name">{{ dev.device_name || '未命名' }}</span>
                    <span class="device-fp" :title="dev.fingerprint">{{ dev.fingerprint.slice(0, 16) }}...</span>
                  </div>
                </div>
                <div class="device-actions">
                  <n-button size="tiny" quaternary @click="openRenameDevice(dev)">重命名</n-button>
                  <n-button size="tiny" quaternary type="error" @click="confirmUnpairOne(dev)">移除</n-button>
                </div>
              </div>
            </div>
          </div>

          <!-- 配对信息提示 -->
          <div class="action-area">
            <n-alert type="info" :bordered="false"> 在客户端输入配对码完成配对。 </n-alert>
          </div>

          <!-- 解除全部配对 -->
          <div v-if="status.paired" class="action-area" style="margin-top: 16px">
            <n-button type="error" :loading="unpairing" @click="confirmUnpair"> 解除全部配对 </n-button>
          </div>
        </div>
      </n-spin>
    </GridCard>

    <!-- 首次配对：设置管理员密码（设备仍使用默认/初始密码时显示） -->
    <GridCard v-if="status?.has_default_password" title="设置管理员密码">
      <n-alert type="warning" :bordered="false" style="margin-bottom: 16px">
        设备仍使用默认或初始密码，存在安全风险，请尽快设置管理员密码。
      </n-alert>
      <div class="pwd-setup">
        <n-input
          v-model:value="curPwd"
          type="password"
          show-password-on="click"
          placeholder="当前密码（默认 admin）"
          :input-props="{ autocomplete: 'current-password' }"
          class="pwd-input"
        />
        <n-input
          v-model:value="newPwd"
          type="password"
          show-password-on="click"
          placeholder="新密码（4-64 位）"
          :input-props="{ autocomplete: 'new-password' }"
          class="pwd-input"
        />
        <n-input
          v-model:value="newPwd2"
          type="password"
          show-password-on="click"
          placeholder="确认新密码"
          :input-props="{ autocomplete: 'new-password' }"
          class="pwd-input"
          @keyup.enter="setAdminPassword"
        />
        <div class="goform-mini">
          <div class="goform-mini-title">调制解调器后台 (Goform)</div>
          <div class="goform-mini-grid">
            <n-input v-model:value="goformIp" placeholder="IP（默认 192.168.0.1）" class="pwd-input" />
            <n-input-number v-model:value="goformPort" :min="1" :max="65535" placeholder="端口" style="width: 120px" />
          </div>
          <n-input
            v-model:value="goformPassword"
            type="password"
            show-password-on="click"
            placeholder="密码（默认 admin）"
            class="pwd-input"
          />
        </div>
        <div class="pwd-actions">
          <n-button type="primary" :loading="settingPwd" @click="setAdminPassword">设置密码</n-button>
        </div>
      </div>
    </GridCard>

    <!-- 配对限制配置卡片 -->
    <GridCard title="配对限制配置">
      <div class="config-section">
        <div class="config-row">
          <div class="config-label-group">
            <span class="config-label">启用配对数量限制</span>
            <span class="config-hint">关闭后允许任意数量设备配对</span>
          </div>
          <n-switch v-model:value="configEnabled" :loading="saving" @update:value="onConfigChange" />
        </div>

        <div v-if="configEnabled" class="config-row">
          <div class="config-label-group">
            <span class="config-label">最大配对设备数</span>
            <span class="config-hint">0 表示不限制数量</span>
          </div>
          <div class="config-input-group">
            <n-input-number
              v-model:value="configMaxDevices"
              :min="0"
              :max="100"
              size="small"
              style="width: 120px"
              @update:value="onConfigChange"
            />
          </div>
        </div>

        <div v-if="configEnabled && configMaxDevices > 0 && status" class="usage-bar">
          <span class="usage-label">已用 {{ pairedCount }} / {{ configMaxDevices }}</span>
          <n-progress
            :percentage="Math.min(100, Math.round((pairedCount / configMaxDevices) * 100))"
            :height="6"
            :color="pairedCount >= configMaxDevices ? '#d03050' : undefined"
          />
        </div>
      </div>
    </GridCard>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted, h } from 'vue';
import { useMessage, useDialog, NInput } from 'naive-ui';
import { getApiClient } from '@/composables/useApi';
import GridCard from '@/components/GridCard.vue';
import InfoRow from '@/components/InfoRow.vue';

const message = useMessage();
const dialog = useDialog();
const api = getApiClient();

const loading = ref(false);
const saving = ref(false);
const unpairing = ref(false);
const status = ref<{
  paired: boolean;
  device_id: string;
  paired_fingerprints: string[];
  paired_at: number;
  pairing_code: string;
  pairing_enabled: boolean;
  pairing_max_devices: number;
  has_default_password?: boolean;
} | null>(null);
const pairedDevices = ref<{ fingerprint: string; device_name: string; last_seen: number; created_at: number }[]>([]);

const configEnabled = ref(true);
const configMaxDevices = ref(0);
const pairedCount = computed(() => pairedDevices.value.length || (status.value?.paired_fingerprints?.length ?? 0));

// 设置管理员密码（初次配对安全向导）
const curPwd = ref('');
const newPwd = ref('');
const newPwd2 = ref('');
const settingPwd = ref(false);

// 初次配对时一并写入的 Goform 后台连接配置（默认值与设备出厂一致）
const goformIp = ref('192.168.0.1');
const goformPort = ref(8080);
const goformPassword = ref('admin');

async function loadStatus() {
  loading.value = true;
  try {
    const { data } = await api.get('/api/pairing/status');
    status.value = data;
    configEnabled.value = data.pairing_enabled ?? false;
    configMaxDevices.value = data.pairing_max_devices ?? 0;
    // statusPayload 已内联完整的 devices 列表（fingerprint/device_name/last_seen/created_at），
    // 无需再单独请求 /api/pairing/devices
    if (Array.isArray(data.devices)) {
      pairedDevices.value = data.devices;
    } else {
      loadDevices();
    }
  } catch {
    message.error('加载配对状态失败');
  } finally {
    loading.value = false;
  }
}

async function loadDevices() {
  try {
    const { data } = await api.get('/api/pairing/devices');
    pairedDevices.value = data.devices || [];
  } catch {
    // Fallback to fingerprints from status
    if (status.value?.paired_fingerprints) {
      pairedDevices.value = status.value.paired_fingerprints.map((fp) => ({
        fingerprint: fp,
        device_name: '',
        last_seen: 0,
        created_at: 0,
      }));
    }
  }
}

/** 初次配对安全向导：设置管理员密码（对应 app 的“初次配对设置密码”）。 */
async function setAdminPassword() {
  if (!newPwd.value) {
    message.error('请输入新密码');
    return;
  }
  if (newPwd.value.length < 4 || newPwd.value.length > 64) {
    message.error('新密码长度需 4-64 位');
    return;
  }
  if (newPwd.value !== newPwd2.value) {
    message.error('两次输入的新密码不一致');
    return;
  }
  if (!goformIp.value.trim() || !goformPassword.value) {
    message.error('请填写调制解调器后台地址与密码');
    return;
  }
  const gp = Number(goformPort.value);
  if (!Number.isInteger(gp) || gp < 1 || gp > 65535) {
    message.error('后台端口需在 1-65535 之间');
    return;
  }
  settingPwd.value = true;
  try {
    await api.post('/pairing/change-password', {
      old_password: curPwd.value || 'admin',
      new_password: newPwd.value,
      goform_ip: goformIp.value.trim(),
      goform_port: Number(goformPort.value),
      goform_password: goformPassword.value,
    });
    message.success('管理员密码已设置');
    curPwd.value = '';
    newPwd.value = '';
    newPwd2.value = '';
    loadStatus();
  } catch (e: any) {
    const statusCode = e?.response?.status;
    const code = e?.response?.data?.code;
    if (statusCode === 401 && code === 'WRONG_OLD_PASSWORD') {
      message.error('当前密码错误');
    } else if (statusCode === 429) {
      message.error('操作过于频繁或密码错误次数过多，请稍后重试');
    } else if (statusCode === 403) {
      message.error('配对接口仅允许同网段访问');
    } else if (code === 'INVALID_GOFORM_CONFIG') {
      message.error('调制解调器后台配置无效（IP/端口/密码格式错误）');
    } else if (code === 'INVALID_NEW_PASSWORD' || statusCode === 400) {
      message.error('新密码格式不正确（需 4-64 位）');
    } else {
      message.error(e?.response?.data?.error || '设置密码失败');
    }
  } finally {
    settingPwd.value = false;
  }
}

let saveTimer: ReturnType<typeof setTimeout> | null = null;
function onConfigChange() {
  if (saveTimer) clearTimeout(saveTimer);
  saveTimer = setTimeout(saveConfig, 500);
}

async function saveConfig() {
  saving.value = true;
  try {
    const { data } = await api.put('/api/pairing/config', {
      pairing_enabled: configEnabled.value,
      pairing_max_devices: configMaxDevices.value,
    });
    // core 回显生效后的值（无效入参会被忽略），据此回写避免显示未采纳的配置
    if (typeof data?.pairing_enabled === 'boolean') configEnabled.value = data.pairing_enabled;
    if (typeof data?.pairing_max_devices === 'number') configMaxDevices.value = data.pairing_max_devices;
    message.success('配置已保存');
  } catch (e: any) {
    message.error(e?.response?.data?.error || '保存失败');
    loadStatus();
  } finally {
    saving.value = false;
  }
}

function confirmUnpair() {
  dialog.warning({
    title: '解除全部配对',
    content: `确定解除全部 ${pairedCount.value} 台设备的配对？解除后所有客户端需要重新配对。`,
    positiveText: '确定解除',
    negativeText: '取消',
    onPositiveClick: async () => {
      unpairing.value = true;
      try {
        await api.post('/api/pairing/unpair');
        message.success('已解除全部配对');
        loadStatus();
      } catch {
        message.error('解除配对失败');
      } finally {
        unpairing.value = false;
      }
    },
  });
}

// core 的 DELETE /api/pairing/devices/{fp} 需要设备密码（双因素），
// 旧代码的 passwordInput 是个从未与任何输入框绑定的局部变量 → 永远发 password:undefined → 恒 401 MISSING_PASSWORD
function confirmUnpairOne(dev: { fingerprint: string; device_name: string }) {
  const pwd = ref('');
  const label = dev.device_name || dev.fingerprint.slice(0, 16);
  dialog.warning({
    title: '移除设备',
    content: () =>
      h('div', { style: 'display:flex;flex-direction:column;gap:10px' }, [
        h('span', `移除设备 "${label}" 的配对需要验证设备密码，移除后该客户端需重新配对。`),
        h(NInput, {
          value: pwd.value,
          type: 'password',
          showPasswordOn: 'click',
          placeholder: '设备密码',
          'onUpdate:value': (v: string) => {
            pwd.value = v;
          },
        }),
      ]),
    positiveText: '移除',
    negativeText: '取消',
    onPositiveClick: async () => {
      if (!pwd.value) {
        message.error('请输入设备密码');
        return false;
      }
      try {
        const { data } = await api.delete(`/api/pairing/devices/${encodeURIComponent(dev.fingerprint)}`, {
          data: { password: pwd.value },
        });
        // rotated=true 表示服务端轮换了 Token，其余客户端（含当前浏览器）需重新配对/登录
        message.success(data?.rotated ? '已移除设备；服务端已轮换 Token，其他客户端需重新配对' : '已移除设备配对');
        loadStatus();
      } catch (e: any) {
        const code = e?.response?.data?.code;
        if (code === 'MISSING_PASSWORD') message.error('需要提供设备密码');
        else if (code === 'INVALID_PASSWORD') message.error('设备密码错误');
        else if (code === 'PASSWORD_LOCKED') message.error('密码错误次数过多，请 15 分钟后重试');
        else if (code === 'DEVICE_NOT_FOUND') message.error('该设备已不在配对列表中');
        else message.error(e?.response?.data?.error || '操作失败');
        return false;
      }
    },
  });
}

// 旧代码 content 是空串且 newName 只是局部变量，弹窗里没有任何输入框 → 只能提交原名
function openRenameDevice(dev: { fingerprint: string; device_name: string }) {
  const name = ref(dev.device_name || '');
  dialog.info({
    title: '重命名设备',
    content: () =>
      h(NInput, {
        value: name.value,
        placeholder: '设备名称（1-32 字符）',
        maxlength: 32,
        'onUpdate:value': (v: string) => {
          name.value = v;
        },
      }),
    positiveText: '保存',
    negativeText: '取消',
    onPositiveClick: async () => {
      const trimmed = name.value.trim();
      if (!trimmed || trimmed.length > 32) {
        message.error('名称需 1-32 字符');
        return false;
      }
      try {
        await api.patch(`/api/pairing/devices/${encodeURIComponent(dev.fingerprint)}`, {
          device_name: trimmed,
        });
        message.success('已重命名');
        loadStatus();
      } catch (e: any) {
        const code = e?.response?.data?.code;
        if (code === 'INVALID_DEVICE_NAME') message.error('名称需 1-32 字符');
        else if (code === 'DEVICE_NOT_FOUND') message.error('该设备已不在配对列表中');
        else message.error(e?.response?.data?.error || '重命名失败');
        return false;
      }
    },
  });
}

onMounted(loadStatus);

// 分栏面板会随 tab 切换反复挂载/卸载，onConfigChange 的 500ms 防抖定时器必须清理，
// 否则卸载后定时器回调仍会触发 saveConfig 并写已销毁组件的 ref
onUnmounted(() => {
  if (saveTimer) {
    clearTimeout(saveTimer);
    saveTimer = null;
  }
});
</script>

<style scoped>
/* .settings-panel 栅格与断点已统一到 src/styles/main.css（全局，8 个面板共用一份） */
.pairing-content {
  padding: 4px 0;
}
.status-badge {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 6px 16px;
  border-radius: 20px;
  font-size: 14px;
  font-weight: 500;
  margin-bottom: 20px;
}
.status-badge.paired {
  background: rgba(24, 160, 88, 0.1);
  color: #18a058;
}
.status-badge.unpaired {
  background: rgba(208, 48, 80, 0.1);
  color: #d03050;
}
.status-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
}
.status-dot.active {
  background: #18a058;
}
.status-dot.inactive {
  background: #d03050;
}
.info-grid {
  display: flex;
  flex-direction: column;
  gap: 12px;
  margin-bottom: 24px;
}
.pairing-code {
  font-family: 'Courier New', monospace;
  font-size: 15px;
  letter-spacing: 2px;
  background: var(--hover-color, #f5f5f5);
  padding: 2px 8px;
  border-radius: 4px;
}
.device-list-section {
  margin-bottom: 20px;
}
.section-title {
  font-size: 14px;
  font-weight: 500;
  color: var(--text-primary);
  margin-bottom: 8px;
}
.device-list {
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.device-item {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 8px 12px;
  border-radius: 8px;
  background: var(--hover-color, #f5f5f5);
}
.device-info {
  display: flex;
  align-items: center;
  gap: 10px;
}
.device-idx {
  font-size: 12px;
  color: var(--text-muted);
  font-weight: 600;
}
.device-name-block {
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.device-name {
  font-size: 13px;
  font-weight: 500;
  color: var(--text-primary);
}
.device-fp {
  font-family: 'Courier New', monospace;
  font-size: 11px;
  color: var(--text-muted);
}
.device-actions {
  display: flex;
  gap: 4px;
  flex-shrink: 0;
}
.action-area {
  margin-top: 8px;
}
.config-section {
  display: flex;
  flex-direction: column;
  gap: 16px;
}
.config-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 16px;
}
.config-label-group {
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.config-label {
  font-size: 14px;
  color: var(--text-primary);
}
.config-hint {
  font-size: 12px;
  color: var(--text-muted);
}
.config-input-group {
  flex-shrink: 0;
}
.usage-bar {
  margin-top: 4px;
}
.usage-label {
  font-size: 12px;
  color: var(--text-secondary);
  margin-bottom: 4px;
  display: block;
}
.pwd-setup {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.pwd-actions {
  display: flex;
  justify-content: flex-end;
}
.goform-mini {
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: 14px;
  border: 1px solid var(--border-subtle);
  border-radius: 10px;
  background: var(--page-bg);
}
.goform-mini-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--text-secondary);
}
.goform-mini-grid {
  display: flex;
  gap: 12px;
  align-items: center;
}
@media (max-width: 768px) {
  .pairing-code {
    font-size: 13px;
    word-break: break-all;
  }
  .config-row {
    flex-direction: column;
    align-items: flex-start;
  }
}
</style>
