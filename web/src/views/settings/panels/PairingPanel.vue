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
                    <span class="device-name">
                      {{ dev.device_name || '未命名' }}
                      <!-- 2026-10-21（审计 §C7）：标出"本浏览器"。之前 deviceFingerprint 只写不读，
                           移除自己那行时没有任何提示，点完就是静默掉线。 -->
                      <span v-if="isSelf(dev.fingerprint)" class="device-self-tag">本机</span>
                    </span>
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
          <div v-if="status.paired" class="action-area action-area--spaced">
            <n-button type="error" :loading="unpairing" @click="confirmUnpair"> 解除全部配对 </n-button>
          </div>
        </div>
      </n-spin>
    </GridCard>

    <!-- 首次配对：设置配对密码（仅在设备仍使用默认/初始密码时显示）
         通栏：这张卡带安全告警 + Goform 子表单，而「配对限制配置」的高度也随状态变
         （未启用限制时 1 行，启用后 3 个块），两张卡同行无法等高，统一通栏。 -->
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
            <n-input-number v-model:value="goformPort" :min="1" :max="65535" placeholder="端口" class="num-compact" />
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
      2026-10-24：已设过密码后的「修改配对密码」卡已删除。
      设备后台密码在「设备」页已有入口，这里再放一份是重复的；保留首次配对的
      「设置配对密码」（仅在设备仍使用默认密码时出现），避免新设备无入口。
    -->

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
              class="num-compact"
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

    <!-- 新设备批准（2026-10-19）：此前只有 App 能开关 —— Web 用户根本看不到这个能力，
         而它决定"新设备能不能只凭密码配进来"。三个通知渠道默认全关，且是额外闸门
         （还要过通知总开关 / 免打扰），所以文案不承诺"打开就一定收到"。 -->
    <GridCard class="full-width" title="新设备批准">
      <div class="config-section">
        <ToggleRow
          v-model="approvalRequired"
          label="新设备需在 App 上批准"
          description="开启后，新设备配对前需要机主在已配对设备上点「允许」，或输入通知里的配对码"
          :disabled="saving"
          @update:model-value="onApprovalToggle"
        />

        <template v-if="approvalRequired">
          <ToggleRow
            v-model="notifySystem"
            label="发本机系统通知"
            description="把配对码推到手机通知栏，方便直接读"
            :disabled="saving"
            @update:model-value="onConfigChange"
          />
          <ToggleRow
            v-model="notifyEmail"
            label="发邮件"
            description="走「邮件通知渠道」里配置的邮箱"
            :disabled="saving"
            @update:model-value="onConfigChange"
          />
          <ToggleRow
            v-model="notifyWebhook"
            label="发 Webhook"
            description="走「Webhook 通知渠道」里配置的地址"
            :disabled="saving"
            @update:model-value="onConfigChange"
          />
          <p class="config-hint approval-note">
            这三个渠道是额外闸门：还要过通知总开关与免打扰，打开不等于一定收到；配对码会明文外发（已做掩码）。
          </p>
        </template>
      </div>
    </GridCard>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted, h } from 'vue';
import { useMessage, useDialog, NInput } from 'naive-ui';
import { useRouter } from 'vue-router';
import { getApiClient } from '@/composables/useApi';
import { useAppStore } from '@/stores/app';
import { evaluatePasswordStrength } from '@/composables/utils';
import GridCard from '@/components/GridCard.vue';
import InfoRow from '@/components/InfoRow.vue';
import ToggleRow from '@/components/ToggleRow.vue';

const message = useMessage();
const dialog = useDialog();
const router = useRouter();
const appStore = useAppStore();
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
// 新设备批准 + 三个通知渠道（2026-10-19）。默认全关 —— 与 core 的默认值一致，
// 老版本 core 不返回这些字段时也回落 false（等价于"功能未启用"，不会误开）。
const approvalRequired = ref(false);
const notifySystem = ref(false);
const notifyEmail = ref(false);
const notifyWebhook = ref(false);
const pairedCount = computed(() => pairedDevices.value.length || (status.value?.paired_fingerprints?.length ?? 0));

// 首次配对密码表单（仅在设备仍使用默认密码时出现）。
// 日常「修改配对密码」卡已删除：设备后台密码在「设备」页已有入口，不在配对中心重复。
const curPwd = ref('');
const newPwd = ref('');
const newPwd2 = ref('');
const settingPwd = ref(false);

/** true = 设备仍是默认/初始密码 → 显示首次配对向导。 */
const isFirstRunPassword = computed(() => status.value?.has_default_password === true);

/** 新密码强度：与登录页共用 evaluatePasswordStrength，不另造算法。 */
const passwordStrength = computed(() => evaluatePasswordStrength(newPwd.value));

// 初次配对时一并写入的 Goform 后台连接配置（默认值与设备出厂一致）。
// 注意：这三个值只在首次配对卡里出现，且**从不**从 core 读回 —— core 对
// goform_password 是脱敏返回（12****78）并拒绝含 *** 的回写，见 GeneralPanel.vue:184。
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
    approvalRequired.value = data.pairing_approval_required === true;
    notifySystem.value = data.pairing_notify_system === true;
    notifyEmail.value = data.pairing_notify_email === true;
    notifyWebhook.value = data.pairing_notify_webhook === true;
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
 * 首次配对密码提交。只在「设置配对密码」卡里调用：
 * 当前密码留空按出厂默认 admin 处理（输入框 placeholder 已说明），
 * 同时写入首次配对需要的 Goform 后台连接配置。
 */
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
    message.error('请填写设备后台地址与密码');
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
      goform_port: gp,
      goform_password: goformPassword.value,
    });
    message.success('配对密码已设置');
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

/**
 * 打开「新设备需批准」时的系统通知引导（2026-10-21 决策 D5-3）。
 *
 * 批准弹窗只在 **App 可见**时才会出现：手机锁屏或 App 在后台时，新设备会一直等到机主打开 App。
 * 系统通知是那种状态下唯一的快通道，而它默认关（"通知默认全关"是既有决定，这里不改默认值）——
 * 只在这唯一一个"用户主动打开批准"的时刻把利弊说清，并给一键开启。
 */
function onApprovalToggle(value: boolean) {
  onConfigChange();
  if (!value || notifySystem.value) return;
  dialog.info({
    title: '建议同时开启系统通知',
    content:
      '批准弹窗只在 App 可见时出现：手机锁屏或 App 在后台时，新设备会一直等到机主打开 App。' +
      '开启「发本机系统通知」后，配对码会同时推到通知栏，锁屏也能看到。不开也能用，只是锁屏期间收不到提醒。',
    positiveText: '同时开启',
    negativeText: '暂不',
    onPositiveClick: () => {
      notifySystem.value = true;
      onConfigChange();
    },
  });
}

async function saveConfig() {
  saving.value = true;
  try {
    const { data } = await api.put('/api/pairing/config', {
      pairing_enabled: configEnabled.value,
      pairing_max_devices: configMaxDevices.value,
      // 2026-10-19：新设备批准 + 三个通知渠道（此前只有 App 能改）
      pairing_approval_required: approvalRequired.value,
      pairing_notify_system: notifySystem.value,
      pairing_notify_email: notifyEmail.value,
      pairing_notify_webhook: notifyWebhook.value,
    });
    // core 回显生效后的值（无效入参会被忽略），据此回写避免显示未采纳的配置
    if (typeof data?.pairing_enabled === 'boolean') configEnabled.value = data.pairing_enabled;
    if (typeof data?.pairing_max_devices === 'number') configMaxDevices.value = data.pairing_max_devices;
    if (typeof data?.pairing_approval_required === 'boolean') approvalRequired.value = data.pairing_approval_required;
    if (typeof data?.pairing_notify_system === 'boolean') notifySystem.value = data.pairing_notify_system;
    if (typeof data?.pairing_notify_email === 'boolean') notifyEmail.value = data.pairing_notify_email;
    if (typeof data?.pairing_notify_webhook === 'boolean') notifyWebhook.value = data.pairing_notify_webhook;
    message.success('配置已保存');
  } catch (e: any) {
    message.error(e?.response?.data?.error || '保存失败');
    loadStatus();
  } finally {
    saving.value = false;
  }
}

/**
 * 解除全部配对（2026-10-21 改造，审计 §B1）。
 *
 * 之前走 `POST /api/pairing/unpair` 且**不带密码** —— 而"移除单台"要密码，
 * 于是"踢掉所有人"反而是门槛最低的破坏性操作。现在 core 那两个旧端点也要求配对密码，
 * 这里补一个密码框；成功后本浏览器自己也会掉线，所以直接把登录态清掉并回登录页。
 */
function confirmUnpair() {
  const pwd = ref('');
  dialog.warning({
    title: '解除全部配对',
    content: () =>
      h('div', { style: 'display:flex;flex-direction:column;gap:10px' }, [
        h(
          'span',
          // 不要写 Markdown 星号：dialog 的 content 是纯文本，`**所有**` 会原样上屏
          `确定解除全部 ${pairedCount.value} 台设备的配对？解除后所有客户端（含当前浏览器）都需要重新配对。`
        ),
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
    positiveText: '确定解除',
    negativeText: '取消',
    onPositiveClick: async () => {
      if (!pwd.value) {
        message.error('请输入配对密码');
        return false;
      }
      unpairing.value = true;
      try {
        await api.post('/api/pairing/unpair', { password: pwd.value });
        message.success('已解除全部配对，请重新配对');
        // 自己的 token 已经随记录一起失效：主动清态回登录页，别等下一次请求撞 444。
        appStore.setAuthFailureReason('已解除全部配对，请重新配对');
        appStore.clearAuth({ keepBaseUrl: true });
        router.push('/login');
      } catch (e: any) {
        const code = e?.response?.data?.code;
        if (code === 'MISSING_PASSWORD') message.error('需要提供配对密码');
        else if (code === 'INVALID_PASSWORD') message.error('配对密码错误');
        else if (code === 'PASSWORD_LOCKED') message.error('密码错误次数过多，请 15 分钟后重试');
        else message.error(e?.response?.data?.error || '解除配对失败');
        return false;
      } finally {
        unpairing.value = false;
      }
    },
  });
}

/**
 * 移除单台设备（2026-10-21 修订，审计 §C7）。
 *
 * 两处修正：
 * 1. 去掉对 `data.rotated` 的判断 —— core 的 DELETE 只回 `{success:true}`，这个字段
 *    从来没有出现过，那一分支是死代码（会让提示永远停在"已移除设备配对"）；
 * 2. 移除的若是**本机**，token 当场失效：主动清态回登录页并说明原因，
 *    否则用户点完只看到界面还开着、下一次请求才被 444 踢走。
 */
function confirmUnpairOne(dev: { fingerprint: string; device_name: string }) {
  const pwd = ref('');
  const label = dev.device_name || dev.fingerprint.slice(0, 16);
  const removingSelf = isSelf(dev.fingerprint);
  dialog.warning({
    title: removingSelf ? '移除本机（会立即退出登录）' : '移除设备',
    content: () =>
      h('div', { style: 'display:flex;flex-direction:column;gap:10px' }, [
        h(
          'span',
          removingSelf
            ? `"${label}" 就是当前这个浏览器。移除后本页会立即退出登录，需要用配对密码重新配对。`
            : `移除设备 "${label}" 的配对需要验证配对密码，移除后该客户端需重新配对。`
        ),
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
    positiveText: removingSelf ? '移除并退出' : '移除',
    negativeText: '取消',
    onPositiveClick: async () => {
      if (!pwd.value) {
        message.error('请输入配对密码');
        return false;
      }
      try {
        await api.delete(`/api/pairing/devices/${encodeURIComponent(dev.fingerprint)}`, {
          data: { password: pwd.value },
        });
        message.success(removingSelf ? '已移除本机配对，请重新配对' : '已移除设备配对');
        if (removingSelf) {
          appStore.setAuthFailureReason('本浏览器已被移出配对列表，请重新配对');
          appStore.clearAuth({ keepBaseUrl: true });
          router.push('/login');
        } else {
          loadStatus();
        }
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

/** 这一行是不是当前浏览器（服务端在配对响应里回显的指纹，只用于展示）。 */
function isSelf(fingerprint: string): boolean {
  return !!appStore.deviceFingerprint && fingerprint === appStore.deviceFingerprint;
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
  /* 不给 min-width:0，flex 子项的最小尺寸是内容宽：设备名上限 32 字符（见保存时的校验），
     32 个汉字 13px ≈ 416px，会把右侧「重命名 / 移除」顶出卡片右边界。 */
  min-width: 0;
}
.device-idx {
  font-size: 12px;
  color: var(--text-muted);
  font-weight: 600;
  flex-shrink: 0;
}
.device-name-block {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}
.device-name {
  font-size: 13px;
  font-weight: 500;
  color: var(--text-primary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.device-fp {
  font-family: 'Courier New', monospace;
  font-size: 11px;
  color: var(--text-muted);
  /* 16 位 hex + `...` 等宽 ≈110px，同样不可断词 */
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
/* 「本机」标记（2026-10-21）：标出当前浏览器那一行，移除它等于把自己踢下线。
   品牌色走 `--accent-color`（计划 B8-1）：原先引用的是一个**不存在的令牌名** `--accent`
   并带一个写死的兜底蓝色值，于是这两行一直是那块写死色，换肤 / 暗色都不会跟着变。 */
.device-self-tag {
  display: inline-block;
  margin-left: 6px;
  padding: 0 6px;
  border-radius: 6px;
  font-size: 11px;
  line-height: 16px;
  font-weight: 500;
  color: var(--accent-color);
  background: color-mix(in srgb, var(--accent-color) 14%, transparent);
  vertical-align: middle;
}
.device-actions {
  display: flex;
  gap: 4px;
  flex-shrink: 0;
}
.action-area {
  margin-top: 8px;
}
/* 「解除全部配对」与上面的提示条拉开一格（原先是内联 style="margin-top:16px"，计划 B8-1） */
.action-area--spaced {
  margin-top: var(--space-4);
}
/* 端口 / 最大设备数这类窄数字框：固定宽度避免 n-input-number 铺满整格 */
.num-compact {
  width: 120px;
}
/* 「新设备批准」卡尾那句说明：与原内联 style="margin:8px 0 0" 同值 */
.approval-note {
  margin: var(--space-2) 0 0;
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
/* 密码强度条：与 LoginView.vue 的同名类保持一致的观感（各自 scoped，故需本地一份）。
   算法（evaluatePasswordStrength）已经共用，重复的只有这段样式 —— 计划 §6.9 B8-1 与
   §6.4 B3-3 都记了这一条：本轮**保留**两份（合并要动 LoginView，属 B3），不在这里抽公共件。 */
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
