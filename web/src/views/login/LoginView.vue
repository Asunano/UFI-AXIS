<template>
  <div class="login-page">
    <div class="login-card">
      <div class="login-header">
        <div class="login-logo">
          <svg width="28" height="28" viewBox="0 0 24 24" fill="none" stroke="#1E293B" stroke-width="1.25" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
            <path d="M10 3.2a9 9 0 1 0 10.8 10.8a1 1 0 0 0 -1 -1h-3.8a4.1 4.1 0 1 1 -5 -5v-4a0.9 0.9 0 0 0 -1 -0.8" />
            <path d="M15 3.5a9 9 0 0 1 5.5 5.5h-4.5a9 9 0 0 0 -1 -1v-4.5" />
          </svg>
        </div>
        <h1 class="login-title">UFI-AXIS</h1>
        <p class="login-subtitle">{{ hasDefaultPassword ? '首次配对 · 请设置管理员密码' : '随身 WiFi 管理平台' }}</p>
      </div>

      <!-- 只有首次配对（hasDefaultPassword=true）才分两步：0=设置设备密码，1=GoForm 后台设置。
           与 app 的 SetupScreen.confirmStep 语义一致 —— 两步都只是 UI 分屏，
           中间不发任何请求，最终仍由 handleLogin() 一次性 POST /pairing/confirm 提交。
           普通登录模式只有密码一屏，不渲染步骤条。 -->
      <n-steps v-if="hasDefaultPassword" :current="pairStep + 1" size="small" class="pair-steps">
        <n-step title="设置设备密码" />
        <n-step title="GoForm 后台设置" />
      </n-steps>

      <!-- 无 formRef：提交校验在 handleLogin 里手写（覆盖面比 rules 更广，含 Goform 字段），
           rules 仅用于各字段 blur 时的即时提示。原先这里挂了 ref="formRef"，
           而 <script setup> 中并没有同名变量 —— 是个永远绑不上的死属性。 -->
      <n-form :model="form" :rules="rules" label-placement="left" label-width="0">
        <!-- 第 1 步「设置设备密码」。普通登录模式下这也是唯一一屏。 -->
        <div v-if="!hasDefaultPassword || pairStep === 0" class="step-pane">
          <!-- 同源模式：自动检测服务器地址，无需手动输入 -->
          <div v-if="isSameOrigin" class="server-hint">
            <n-icon :size="14" style="color: var(--text-muted)"><GlobeOutline /></n-icon>
            <span>{{ currentOrigin }}</span>
          </div>

          <!-- 跨域模式：手动输入服务器地址 -->
          <n-form-item v-else path="serverUrl">
            <n-input
              v-model:value="form.serverUrl"
              placeholder="设备地址，如 http://192.168.0.1:8088"
              :input-props="{ autocomplete: 'url' }"
            >
              <template #prefix>
                <n-icon :size="16"><GlobeOutline /></n-icon>
              </template>
            </n-input>
          </n-form-item>

          <n-form-item path="password">
            <n-input
              v-model:value="form.password"
              type="password"
              show-password-on="click"
              :placeholder="hasDefaultPassword ? '请设置管理员密码（4-64 位）' : '设备密码'"
              :input-props="{ autocomplete: hasDefaultPassword ? 'new-password' : 'current-password' }"
              @keyup.enter="submitPasswordStep"
            >
              <template #prefix>
                <n-icon :size="16"><KeyOutline /></n-icon>
              </template>
            </n-input>
          </n-form-item>

          <!-- 初次配对：设置管理员密码（第 1 步专有） -->
          <template v-if="hasDefaultPassword">
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

            <n-form-item path="confirmPassword">
              <n-input
                v-model:value="form.confirmPassword"
                type="password"
                show-password-on="click"
                placeholder="确认管理员密码"
                :input-props="{ autocomplete: 'new-password' }"
                @keyup.enter="submitPasswordStep"
              >
                <template #prefix>
                  <n-icon :size="16"><KeyOutline /></n-icon>
                </template>
              </n-input>
            </n-form-item>
          </template>
        </div>

        <!-- 第 2 步「GoForm 后台设置」：仅配对模式存在。字段仍分开（IP / 端口 / 密码），
             端口用 n-input-number 自带范围校验，不合并成 app 那样的单个「IP:端口」文本框。 -->
        <div v-else class="step-pane">
          <!-- 初次配对：同时配置 Goform 后台（调制解调器原生管理界面）连接 -->
          <div class="goform-section">
            <div class="goform-title">调制解调器后台 (Goform)</div>
            <p class="goform-desc">
              设备经此地址访问调制解调器原生管理界面，默认通常为 192.168.0.1:8080、密码 admin。一般无需修改。
            </p>
            <div class="goform-grid">
              <div class="goform-field">
                <label>IP 地址</label>
                <n-input
                  v-model:value="goformIp"
                  placeholder="192.168.0.1"
                  :input-props="{ inputmode: 'numeric' }"
                  @keyup.enter="handleLogin"
                />
              </div>
              <div class="goform-field">
                <label>端口</label>
                <n-input-number
                  v-model:value="goformPort"
                  :min="1"
                  :max="65535"
                  placeholder="8080"
                  style="width: 100%"
                />
              </div>
            </div>
            <div class="goform-field">
              <label>密码</label>
              <n-input
                v-model:value="goformPassword"
                type="password"
                show-password-on="click"
                placeholder="admin"
                @keyup.enter="handleLogin"
              />
            </div>
          </div>

          <n-alert type="info" :bordered="false" class="login-hint">
            首次使用，请设置管理员密码。该密码用于后续登录与管理设备，请务必牢记。
          </n-alert>
        </div>

        <!-- 配对第 1 步：只跑本地校验后切到第 2 步，不发任何请求 -->
        <n-button
          v-if="hasDefaultPassword && pairStep === 0"
          type="primary"
          block
          :disabled="loading"
          style="margin-top: 8px"
          @click="goNextStep"
        >
          下一步
        </n-button>

        <!-- 配对第 2 步：上一步（保留已填内容）/ 真正提交 -->
        <div v-else-if="hasDefaultPassword" class="step-actions">
          <n-button :disabled="loading" @click="goPrevStep">上一步</n-button>
          <n-button type="primary" :loading="loading" :disabled="loading" style="flex: 1" @click="handleLogin">
            设置并进入
          </n-button>
        </div>

        <!-- 普通登录：单屏，行为保持原样 -->
        <n-button
          v-else
          type="primary"
          block
          :loading="loading"
          :disabled="loading"
          style="margin-top: 8px"
          @click="handleLogin"
        >
          登录
        </n-button>
      </n-form>

      <div v-if="error" class="login-error">
        <n-alert type="error" :bordered="false" :title="error" />
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted, watch } from 'vue';
import { useRouter } from 'vue-router';
import { useMessage } from 'naive-ui';
import { GlobeOutline, KeyOutline } from '@vicons/ionicons5';
import { useAppStore } from '@/stores/app';
import { getApiClient, resetApiClient } from '@/composables/useApi';
import { evaluatePasswordStrength } from '@/composables/utils';
import { loadDeviceIdentity } from '@/composables/deviceIdentityLazy';

// 配对记录里的显示名。设备**身份**不再由任何常量决定（见 deviceIdentity.ts）：
// 旧代码用固定的 WEB_FINGERPRINT 上报指纹，服务端把所有浏览器当成同一台设备，
// 于是"配对设备上限"对 Web 端完全无效——任意多个浏览器只要知道密码就能登录。
const WEB_DEVICE_NAME = 'Web 管理后台';

const router = useRouter();
const appStore = useAppStore();
// 「下一步」的分步校验用 message 即时提示；请求类错误仍走页面底部的 error 提示条
const message = useMessage();

// 同源检测：如果当前页面是从设备本身 serve 的，则自动使用当前 origin
const currentOrigin = computed(() => window.location.origin);
const isSameOrigin = computed(() => {
  // 开发环境 (vite dev server) 不算同源
  if (import.meta.env.DEV) return false;
  return true;
});

const form = reactive({
  serverUrl: '',
  password: '',
  confirmPassword: '',
});

// 初次配对时一并写入的 Goform 后台连接配置（默认值与设备出厂一致）
const goformIp = ref('192.168.0.1');
const goformPort = ref(8080);
const goformPassword = ref('admin');

// 首次配对（设备未设密码）时强制设置密码；否则为普通登录
const rules = computed(() => ({
  serverUrl: { required: true, message: '请输入设备地址', trigger: 'blur' },
  password: hasDefaultPassword.value
    ? [
        { required: true, message: '请设置管理员密码', trigger: 'blur' },
        { min: 4, max: 64, message: '密码长度需 4-64 位', trigger: 'blur' },
      ]
    : { required: false, message: '请输入设备密码', trigger: 'blur' },
  confirmPassword: hasDefaultPassword.value
    ? {
        required: true,
        validator: (_rule: any, value: string) => {
          if (value !== form.password) return new Error('两次输入的密码不一致');
          return true;
        },
        trigger: ['blur', 'input'],
      }
    : { required: false },
}));

const loading = ref(false);
const error = ref('');
const hasDefaultPassword = ref(false);

// 首次配对的分步下标：0=设置设备密码，1=GoForm 后台设置（对应 app SetupScreen 的 confirmStep）。
// 普通登录模式不使用它（模板里第 1 步的 pane 对 hasDefaultPassword=false 恒显示）。
const pairStep = ref(0);

// 模式变化（fetchPairingInfo 刷新后 true→false 或反向）时回到第 1 步：
// 否则可能停在一个已经不该存在的 GoForm 步上。提交失败不会走到这里，
// 用户已填的密码/GoForm 内容原样保留。
watch(hasDefaultPassword, () => {
  pairStep.value = 0;
});

// 密码强度（仅初次配对时展示）
const passwordStrength = computed(() => evaluatePasswordStrength(form.password));

// 同源模式：挂载即拉取配对信息，用于判断是否处于初次配对（与主登录流程解耦）
onMounted(async () => {
  if (isSameOrigin.value) {
    try {
      const { data } = await getApiClient().get('/pairing/info');
      hasDefaultPassword.value = !!data?.has_default_password;
    } catch {
      // 忽略，登录时再取
    }
  }
});

/** 拉取配对码（免鉴权），用于密码登录的配对确认。 */
async function fetchPairingInfo(): Promise<string | null> {
  const { data } = await getApiClient().get('/pairing/info');
  hasDefaultPassword.value = !!data?.has_default_password;
  return data?.pairing_code ?? null;
}

/**
 * core 的 /pairing/info 有每 IP 500ms 的限频，而本页挂载时已请求过一次，
 * 用户很快点击登录会直接吃到 429 → 登录被中断。命中 429 时等待后重试一次。
 */
async function fetchPairingInfoWithRetry(): Promise<string | null> {
  try {
    return await fetchPairingInfo();
  } catch (e: any) {
    if (e?.response?.status !== 429) throw e;
    await new Promise((resolve) => setTimeout(resolve, 700));
    return await fetchPairingInfo();
  }
}

/**
 * 第 1 步「设置设备密码」的校验：设备地址（仅跨域）、密码非空、长度 4-64、两次一致。
 * 通过返回 null，否则返回错误文案。
 * 「下一步」按钮与 handleLogin() 共用这一份——分步不削弱最终门禁，
 * handleLogin() 在此之后还会继续校验 Goform 字段。
 */
function validatePasswordStep(): string | null {
  if (!isSameOrigin.value && !form.serverUrl) return '请输入设备地址';
  // 初次配对：必须设置管理员密码（4-64 位，且两次一致）
  if (hasDefaultPassword.value) {
    if (!form.password) return '请设置管理员密码';
    if (form.password.length < 4 || form.password.length > 64) return '密码长度需 4-64 位';
    if (form.password !== form.confirmPassword) return '两次输入的密码不一致';
    return null;
  }
  if (!form.password) return '请输入设备密码';
  return null;
}

/** 配对模式：第 1 步 → 第 2 步。纯本地校验，不发请求（与 app 的「下一步」一致）。 */
function goNextStep() {
  const err = validatePasswordStep();
  if (err) {
    error.value = err;
    message.error(err);
    return;
  }
  error.value = '';
  pairStep.value = 1;
}

/** 配对模式：第 2 步 → 第 1 步。只切屏，已填内容（含 Goform）全部保留。 */
function goPrevStep() {
  error.value = '';
  pairStep.value = 0;
}

/**
 * 第 1 步输入框上的回车：配对模式走「下一步」，普通登录直接提交。
 * 第 2 步的 Goform 输入框回车则直接绑 handleLogin。
 */
function submitPasswordStep() {
  if (hasDefaultPassword.value) {
    goNextStep();
    return;
  }
  void handleLogin();
}

/**
 * 登录 / 初次配对：镜像 Android app 的配对流程。
 * 1) GET /pairing/info 取配对码
 * 2) POST /pairing/confirm（配对码 + Web 指纹 + 设备密码）换 token
 *    —— 设备未设密码时，该密码即被设为新管理员密码（对应 app 的“初次配对设置密码”）
 * 3) 写回 token，校验连通性后进入仪表盘
 */
async function handleLogin() {
  error.value = '';
  // 第 1 步字段（设备地址 / 密码长度 / 两次一致）——与「下一步」共用同一份校验。
  // 分步只是 UI，这里仍然跑全量校验：即使用户绕过第 1 步也过不了这道门禁。
  const stepErr = validatePasswordStep();
  if (stepErr) {
    error.value = stepErr;
    // 出错字段在第 1 步上，退回去让用户看得见（这不是提交失败，是校验没过）
    if (hasDefaultPassword.value) pairStep.value = 0;
    return;
  }
  if (hasDefaultPassword.value) {
    // Goform 后台连接配置校验（与设备出厂默认值一致，一般无需改动）
    if (!goformIp.value.trim() || !goformPassword.value) {
      error.value = '请填写调制解调器后台地址与密码';
      return;
    }
    const gp = Number(goformPort.value);
    if (!Number.isInteger(gp) || gp < 1 || gp > 65535) {
      error.value = '后台端口需在 1-65535 之间';
      return;
    }
  }

  loading.value = true;
  try {
    resetApiClient();
    // 同源模式：不设置 baseUrl，axios 使用相对路径（当前 origin）
    // 跨域模式：先定位设备，后续 /pairing/info、/pairing/confirm 才能打到设备
    const serverUrl = isSameOrigin.value ? '' : form.serverUrl;
    if (!isSameOrigin.value) appStore.setBaseUrl(serverUrl);

    const api = getApiClient();

    // 1. 取配对码
    // 设备已初始化（非默认密码）时允许空配对码：后端支持凭设备密码登录（登录=自动配对），
    // 一次性配对码仅在设备首次初始化（配对）时必需。
    let pairingCode: string | null = null;
    try {
      pairingCode = await fetchPairingInfoWithRetry();
    } catch (e: any) {
      if (e.response?.status === 403) {
        error.value = '设备仅允许本地网络访问，请确认处于同一局域网';
      } else if (e.response?.status === 429) {
        error.value = '操作过于频繁，请稍后重试';
      } else {
        error.value = '无法获取设备配对信息';
      }
      return;
    }
    if (!pairingCode && hasDefaultPassword.value) {
      error.value = '设备未处于配对模式，无法完成首次配对';
      return;
    }

    // 2. 挑战-应答 + 设备密码换 token（初次配对时该密码即被设为新密码）
    // Goform 后台配置仅在首次配对（初始化设备）时提交；普通登录不携带，
    // 避免用表单默认值覆盖设备上已配置的 Goform 连接设置。
    //
    // 设备身份 = IndexedDB 里**不可导出**的 ECDSA 私钥（见 deviceIdentity.ts）。
    // 这里上报公钥并对服务端下发的一次性挑战签名，服务端据公钥自行计算指纹。
    // 旧实现上报常量 WEB_FINGERPRINT，导致所有浏览器在服务端看来是同一台设备，
    // 配对上限完全失效——这是本次改造要修掉的根因，别再退回去。
    let confirmRes;
    try {
      const devId = await loadDeviceIdentity();
      const identity = await devId.getDeviceIdentity();
      const { data: challengeRes } = await api.post('/pairing/challenge', {});
      const challenge = challengeRes?.challenge;
      if (!challenge) {
        error.value = '无法获取设备验证挑战，请重试';
        return;
      }
      confirmRes = await api.post('/pairing/confirm', {
        pairing_code: pairingCode ?? '',
        device_pubkey: identity.publicKeySpki,
        challenge,
        signature: await devId.signChallenge(challenge),
        password: form.password || undefined,
        device_name: WEB_DEVICE_NAME,
        ...(hasDefaultPassword.value
          ? {
              goform_ip: goformIp.value.trim(),
              goform_port: Number(goformPort.value),
              goform_password: goformPassword.value,
            }
          : {}),
      });
    } catch (e: any) {
      handleConfirmError(e);
      return;
    }

    const token = confirmRes.data?.token;
    if (!token) {
      error.value = '登录失败：未返回 Token';
      return;
    }
    appStore.setToken(token);
    // 服务端算出的指纹：配对界面据此高亮"本浏览器"，不再由前端自行推导
    appStore.setDeviceFingerprint(confirmRes.data?.fingerprint ?? '');

    // 3. 连通性 + 鉴权校验
    await api.get('/health');
    await api.get('/api/system/uptime');

    router.push('/dashboard');
  } catch (e: any) {
    handleNetworkError(e);
  } finally {
    loading.value = false;
  }
}

/** 配对确认阶段的错误映射（对应 API 参考文档的错误码）。 */
function handleConfirmError(e: any) {
  const status = e.response?.status;
  const code = e.response?.data?.code;
  if (status === 401 && code === 'INVALID_CODE') {
    error.value = '配对码无效，请刷新页面后重试';
  } else if (status === 401 && code === 'INVALID_PASSWORD') {
    error.value = '设备密码错误';
  } else if (status === 401 && (code === 'INVALID_DEVICE_KEY' || code === 'INVALID_CHALLENGE')) {
    // 挑战一次性且 2 分钟过期；重新点登录会重新走 challenge，不需要用户改任何输入
    error.value = '设备身份校验失败，请重新点击登录';
  } else if (status === 429 && code === 'PASSWORD_LOCKED') {
    error.value = '设备密码错误次数过多，已锁定，请 15 分钟后再试';
  } else if (status === 409 && code === 'ALREADY_PAIRED') {
    error.value = '配对设备数量已达上限，请先在「配对管理」解除其他设备';
  } else if (status === 400 && code === 'PASSWORD_REQUIRED') {
    error.value = '请输入设备密码';
  } else if (status === 400 && code === 'INVALID_GOFORM_CONFIG') {
    error.value = '调制解调器后台配置无效（IP/端口/密码格式错误）';
  } else if (status === 400) {
    error.value = '请求参数缺失';
  } else if (status === 429) {
    error.value = '操作过于频繁，请稍后重试';
  } else {
    handleNetworkError(e);
  }
}

function handleNetworkError(e: any) {
  if (e.response?.status === 401) {
    error.value = '凭据已失效，请重新登录';
  } else if (e.code === 'ECONNABORTED') {
    error.value = '连接超时，请检查设备地址是否正确';
  } else if (e.code === 'ERR_NETWORK') {
    error.value = '无法连接到设备，请检查网络和地址';
  } else {
    error.value = e.response?.data?.error || '连接失败';
  }
}
</script>

<style scoped>
.login-page {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--page-bg);
  padding: 16px;
}
.login-card {
  width: 100%;
  max-width: 380px;
  background: var(--card-bg);
  border-radius: 16px;
  padding: 40px 32px;
  border: 1px solid var(--border-subtle);
}
.login-header {
  text-align: center;
  margin-bottom: 32px;
}
.login-logo {
  width: 48px;
  height: 48px;
  border-radius: 12px;
  /* 与 :app 启动器图标同款：白底 + chart-donut 字形（#1E293B），不再用旧蓝色占位 */
  background: #ffffff;
  border: 1px solid var(--border-subtle);
  display: inline-flex;
  align-items: center;
  justify-content: center;
  margin-bottom: 12px;
}
.login-title {
  font-size: 22px;
  font-weight: 700;
  color: var(--text-primary);
  margin: 0;
}
.login-subtitle {
  font-size: 13px;
  color: var(--text-muted);
  margin-top: 4px;
}
.login-error {
  margin-top: 16px;
}
.server-hint {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 8px 12px;
  margin-bottom: 12px;
  background: var(--page-bg);
  border-radius: 8px;
  font-size: 13px;
  color: var(--text-secondary);
}
.login-hint {
  margin-top: 4px;
  margin-bottom: 4px;
  font-size: 12px;
  line-height: 1.5;
}
.pair-steps {
  margin-bottom: 20px;
}
/* 切步骤时面板淡入并轻微上浮，写法同 SettingsView 的 .pane-in：
   只动 opacity/transform，不参与布局，所以两步高度不同也不会闪一下。 */
.step-pane {
  animation: step-pane-in 0.22s cubic-bezier(0.4, 0, 0.2, 1);
}
@keyframes step-pane-in {
  from {
    opacity: 0;
    transform: translateY(4px);
  }
  to {
    opacity: 1;
    transform: none;
  }
}
.step-actions {
  display: flex;
  gap: 10px;
  margin-top: 8px;
}
.pw-strength {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 12px;
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
.goform-section {
  margin: 4px 0 8px;
  padding: 14px 14px 6px;
  border: 1px solid var(--border-subtle);
  border-radius: 10px;
  background: var(--page-bg);
}
.goform-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--text-secondary);
  margin-bottom: 4px;
}
.goform-desc {
  font-size: 11px;
  line-height: 1.5;
  color: var(--text-muted);
  margin: 0 0 10px;
}
.goform-grid {
  display: grid;
  grid-template-columns: 1fr 110px;
  gap: 10px;
  margin-bottom: 10px;
}
.goform-field {
  display: flex;
  flex-direction: column;
  gap: 4px;
  margin-bottom: 10px;
}
.goform-field label {
  font-size: 11px;
  color: var(--text-muted);
}
</style>
