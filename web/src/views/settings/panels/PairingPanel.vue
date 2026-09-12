<!--
  配对分栏面板。

  这里原本是独立的「配对」页面（src/views/pairing/PairingView.vue），
  现已并入设置页的「配对」分栏。逻辑与模板原样搬运，未改动任何接口路径/请求体/字段名。
-->
<template>
  <div class="settings-panel">
    <!-- 配对状态卡片
         2026-09-10 改为通栏（.full-width）：这张卡的高度随「已配对设备数」变化（实测 0 台
         286px、2 台 497px，且不封顶），无法与别的卡配成等高的一行。
         同日第二轮：本页三张卡**全部**通栏 —— 因为另两张的高度也都随状态变
         （密码卡 263 ↔ 476.9px；限制配置 106 ↔ 223px），同行差最大 370.9px。
         三张都通栏后本页不再有配对，「同行等高」这个约束自然消失。
         顺带收益：设备列表是「#序号 + 名称 + 指纹 + 两个按钮」的四段式，通栏后每段都宽一倍。 -->
    <GridCard class="full-width" title="配对管理">
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

    <!-- 首次配对：设置配对密码（设备仍使用默认/初始密码时显示）
         2026-09-10 第二轮改为通栏。原因是**运行时状态**：这张卡有两个变体 ——
         首次（有默认密码）带安全告警 + Goform 子表单，实测 476.9px；设过密码后变体只有
         三个输入框，263px。同时「配对限制配置」也随状态变（未启用限制时只剩 1 行，106px；
         启用后 223px）。两个高度都随状态摆 2~3 倍，**同行配对本质上不成立**：
         实测新设备（默认密码）状态下同行差 370.9px，就是一块整版空白。
         所以本页三张卡全部通栏、不做配对 —— 这是唯一对状态免疫的排法。 -->
    <GridCard v-if="isFirstRunPassword" class="full-width" title="设置配对密码">
      <n-alert class="pwd-alert" type="warning" :bordered="false">
        设备仍使用默认或初始密码，存在安全风险，请尽快设置配对密码。
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
        <!-- 强度提示与登录页保持同一算法/同一呈现（evaluatePasswordStrength）。
             顺序放在「确认新密码」之后而不是「新密码」之后：.pwd-setup 是 2 列栅格
             （见该类的注释），三个输入框正好占满第 1 行，强度条与确认框配成第 2 行；
             原来夹在新/确认之间会让第 2 行只剩一个元素、右边空一格。 -->
        <div class="pw-strength">
          <div class="pw-strength-bars">
            <span
              v-for="i in 4"
              :key="i"
              class="pw-bar"
              :class="{ active: i <= passwordStrength.score }"
              :style="i <= passwordStrength.score ? { background: passwordStrength.color } : {}"
            ></span>
          </div>
          <span class="pw-strength-label">{{ passwordStrength.label }}</span>
        </div>
        <div class="goform-mini sub-panel pwd-full">
          <div class="goform-mini-title">设备后台</div>
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
        <div class="pwd-actions pwd-full">
          <n-button type="primary" :loading="settingPwd" @click="setAdminPassword">设置密码</n-button>
        </div>
      </div>
    </GridCard>

    <!--
      已设过密码后的常驻入口：修改配对密码。
      拆成两张卡是因为原来只有 has_default_password=true 的首次配对卡，
      密码一旦设过 web 端就再也改不了（app 端没有这个限制）。
      这里不重复 Goform 子表单 —— 设备后台密码在「通用设置」/「高级」里已有入口，
      抄第二份会让同一个字段有两处来源。
      通栏理由同上面那张：它与首次配对卡是同一个卡的两个变体，高度差一倍，
      与「配对限制配置」同行必然留白（实测新设备状态 370.9px）。
    -->
    <GridCard v-else-if="status" class="full-width" title="修改配对密码">
      <div class="pwd-setup">
        <n-input
          v-model:value="curPwd"
          type="password"
          show-password-on="click"
          placeholder="当前密码"
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
        <!-- 强度条放在确认框之后：理由见首次配对卡里同一处的注释（2 列栅格占满整行） -->
        <div class="pw-strength">
          <div class="pw-strength-bars">
            <span
              v-for="i in 4"
              :key="i"
              class="pw-bar"
              :class="{ active: i <= passwordStrength.score }"
              :style="i <= passwordStrength.score ? { background: passwordStrength.color } : {}"
            ></span>
          </div>
          <span class="pw-strength-label">{{ passwordStrength.label }}</span>
        </div>
        <div class="pwd-actions pwd-full">
          <n-button type="primary" :loading="settingPwd" @click="setAdminPassword">修改密码</n-button>
        </div>
      </div>
    </GridCard>

    <!-- 配对限制配置卡片。通栏：本卡高度也随状态变（未启用限制时只剩 1 行 106px，
         启用后 3 个块 223px），与任何卡同行都会留白。三张卡通栏后本页不再有配对，
         也就没有「同行等高」这个约束。 -->
    <GridCard class="full-width" title="配对限制配置">
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
            :color="pairedCount >= configMaxDevices ? 'var(--error)' : undefined"
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
import { evaluatePasswordStrength } from '@/composables/utils';
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

// 配对密码表单。首次配对（has_default_password=true）与日常修改共用同一组 ref、
// 同一个提交函数：两套输入框只在模板上分卡，逻辑（尤其是 8 个错误码分支）只有一份。
const curPwd = ref('');
const newPwd = ref('');
const newPwd2 = ref('');
const settingPwd = ref(false);

/** true = 设备仍是默认/初始密码 → 首次配对向导；false = 已设过密码 → 常驻「修改配对密码」。 */
const isFirstRunPassword = computed(() => status.value?.has_default_password === true);

/** 新密码强度：与登录页共用 evaluatePasswordStrength，不另造算法。 */
const passwordStrength = computed(() => evaluatePasswordStrength(newPwd.value));

// 初次配对时一并写入的 Goform 后台连接配置（默认值与设备出厂一致）。
// 注意：这三个值只在首次配对卡里出现，且**从不**从 core 读回 —— core 对
// goform_password 是脱敏返回（12****78）并拒绝含 *** 的回写，见 GeneralPanel.vue:184。
// 修改密码卡不含 Goform 子表单，也不会提交任何 goform_* 字段。
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

/**
 * 配对密码提交。两张卡（首次设置 / 日常修改）共用这一份实现：
 * 端点、请求体字段名与 8 个错误码分支全部保持原样，只有「是否带 Goform 配置」按
 * 首次配对与否分流 —— 修改密码卡里根本没有 Goform 输入框，绝不能提交那三个字段
 * （否则会把硬编码的出厂默认值 192.168.0.1/8080/admin 写回设备）。
 */
async function setAdminPassword() {
  const firstRun = isFirstRunPassword.value;
  // 首次配对沿用原有行为：当前密码留空按出厂默认 admin 处理（输入框 placeholder 已说明）。
  // 修改密码时必须显式输入当前密码，否则等于让 core 去猜。
  if (!firstRun && !curPwd.value) {
    message.error('请输入当前密码');
    return;
  }
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
  if (firstRun) {
    if (!goformIp.value.trim() || !goformPassword.value) {
      message.error('请填写设备后台地址与密码');
      return;
    }
    const gp = Number(goformPort.value);
    if (!Number.isInteger(gp) || gp < 1 || gp > 65535) {
      message.error('后台端口需在 1-65535 之间');
      return;
    }
  }
  settingPwd.value = true;
  try {
    const payload: Record<string, any> = {
      old_password: firstRun ? curPwd.value || 'admin' : curPwd.value,
      new_password: newPwd.value,
    };
    if (firstRun) {
      payload.goform_ip = goformIp.value.trim();
      payload.goform_port = Number(goformPort.value);
      payload.goform_password = goformPassword.value;
    }
    await api.post('/pairing/change-password', payload);
    message.success(firstRun ? '配对密码已设置' : '配对密码已修改');
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
      message.error('设备后台配置无效（IP/端口/密码格式错误）');
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

// core 的 DELETE /api/pairing/devices/{fp} 需要配对密码（双因素），
// 旧代码的 passwordInput 是个从未与任何输入框绑定的局部变量 → 永远发 password:undefined → 恒 401 MISSING_PASSWORD
function confirmUnpairOne(dev: { fingerprint: string; device_name: string }) {
  const pwd = ref('');
  const label = dev.device_name || dev.fingerprint.slice(0, 16);
  dialog.warning({
    title: '移除设备',
    content: () =>
      h('div', { style: 'display:flex;flex-direction:column;gap:10px' }, [
        h('span', `移除设备 "${label}" 的配对需要验证配对密码，移除后该客户端需重新配对。`),
        h(NInput, {
          value: pwd.value,
          type: 'password',
          showPasswordOn: 'click',
          placeholder: '配对密码',
          'onUpdate:value': (v: string) => {
            pwd.value = v;
          },
        }),
      ]),
    positiveText: '移除',
    negativeText: '取消',
    onPositiveClick: async () => {
      if (!pwd.value) {
        message.error('请输入配对密码');
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
        if (code === 'MISSING_PASSWORD') message.error('需要提供配对密码');
        else if (code === 'INVALID_PASSWORD') message.error('配对密码错误');
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
  background: var(--success-light);
  color: var(--success);
}
.status-badge.unpaired {
  background: var(--error-light);
  color: var(--error);
}
.status-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
}
.status-dot.active {
  background: var(--success);
}
.status-dot.inactive {
  background: var(--error);
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
  background: var(--surface-elevated);
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
  background: var(--surface-elevated);
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
/* 密码表单栅格（2026-09-10 第二轮：flex 竖排 → 2 列栅格）。
   改的原因：两张密码卡都改成通栏了（见模板顶部注释），卡片宽到 1180~1650，
   竖排会让每个密码框拉成一千多像素宽。改成 2 列后三个输入框占满第 1 行、
   强度条与确认框配第 2 行，宽度用得上、行数还少一半。
   沿用 main.css 的 .config-grid 口径：2 列 + 768 折单列，不新增断点。
   `.pwd-full` 是通栏项（Goform 子表单、按钮行）。 */
.pwd-setup {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: var(--space-3);
  align-items: start;
}
.pwd-setup > .pwd-full {
  grid-column: 1 / -1;
}
/* 首次配对卡里的安全告警：在 .pwd-setup 之外，所以自己带下边距
   （原来是内联 style="margin-bottom:16px"，改用栅格 gap 后统一收到这里） */
.pwd-alert {
  margin-bottom: var(--space-3);
}
.pwd-actions {
  display: flex;
  justify-content: flex-end;
}
/* 密码强度条：与 LoginView.vue 的同名类保持一致的观感（各自 scoped，故需本地一份） */
.pw-strength {
  display: flex;
  align-items: center;
  gap: 10px;
  margin: -4px 0 0;
}
@media (max-width: 768px) {
  .pwd-setup {
    grid-template-columns: 1fr;
  }
}
.pw-strength-bars {
  display: flex;
  gap: 4px;
  flex: 1;
}
.pw-bar {
  flex: 1;
  height: 4px;
  border-radius: 2px;
  background: var(--border-subtle);
  transition: background 0.2s;
}
.pw-strength-label {
  font-size: 12px;
  color: var(--text-muted);
  min-width: 28px;
  text-align: right;
}
/* 描边/内距/圆角/底色走 main.css 的全局 .sub-panel（见模板 class） */
.goform-mini {
  display: flex;
  flex-direction: column;
  gap: 12px;
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
