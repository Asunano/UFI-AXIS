<template>
  <!--
    登录 / 首次配对（2026-10-22 按 docs-dev/login-mockups/02-split-brand.html 落地）。

    结构：左侧品牌栏（LoginBrand）+ 右侧卡片；卡片内是**五选一**的视图：
      login    普通登录
      pair     首次配对（三步：配对密码 → 设备后台 → 确认）
      waiting  等待机主批准
      rejected 机主拒绝
      expired  申请过期

    为什么改成"一次只渲染一种状态"：原来批准等待是叠在表单上方的一条 alert，
    表单与提示同时占位，手机上点完登录会看到两套内容抢注意力。现在等待/拒绝/过期
    各自占满卡片，标题、说明、动作都只属于当前那件事。

    全部请求与校验逻辑没有重写：handleLogin / beginApproval / 轮询 / 错误映射原样保留，
    这里只负责把 view 映射到界面。pairSteps 的 validate 契约继续复用（拦下原因就地显示）。
  -->
  <div class="login-shell">
    <LoginBrand />

    <main class="login-form-side">
      <div class="login-card" :data-view="view">
        <!-- ① 普通登录 -->
        <section v-if="view === 'login'" class="view" data-view="login">
          <h1 class="view-title">登录</h1>
          <p class="view-sub">输入这台设备的配对密码继续。</p>

          <div v-if="isSameOrigin" class="server-hint">
            <n-icon :size="16"><GlobeOutline /></n-icon>
            <span>{{ currentOrigin }}</span>
          </div>
          <div v-else class="field">
            <label class="field-label" for="login-server">设备地址</label>
            <n-input
              id="login-server"
              v-model:value="form.serverUrl"
              :style="controlVars"
              placeholder="设备地址，如 http://192.168.0.1:8088"
              :input-props="{ autocomplete: 'url' }"
            >
              <template #prefix
                ><n-icon :size="16"><GlobeOutline /></n-icon
              ></template>
            </n-input>
          </div>

          <div class="field">
            <label class="field-label" for="login-password">配对密码</label>
            <n-input
              id="login-password"
              v-model:value="form.password"
              :style="controlVars"
              type="password"
              show-password-on="click"
              placeholder="请输入配对密码"
              :input-props="{ autocomplete: 'current-password' }"
              @keyup.enter="handleLogin"
            >
              <template #prefix
                ><n-icon :size="16"><KeyOutline /></n-icon
              ></template>
            </n-input>
          </div>

          <n-alert v-if="error" type="error" :bordered="false" class="view-alert">{{ error }}</n-alert>

          <div class="actions">
            <n-button :style="buttonVars" type="primary" :loading="loading" :disabled="loading" @click="handleLogin"
              >登录</n-button
            >
          </div>
          <n-alert v-if="identityExposedOnPlainHttp" type="warning" :bordered="false" class="login-insecure">
            当前以 HTTP 明文访问：设备身份密钥可被页面脚本读取；长期使用建议启用 HTTPS。
          </n-alert>
        </section>

        <!-- ② 首次配对：步骤条 + 当前步标题，一次只显示一步 -->
        <section v-else-if="view === 'pair'" :key="`pair-${pairStep}`" class="view" data-view="pair">
          <div class="stepper" aria-label="配对进度">
            <template v-for="(s, i) in pairSteps" :key="s.key">
              <button
                type="button"
                class="st"
                :class="{ active: i === pairStep, done: i < pairStep }"
                @click="jumpToStep(i)"
              >
                <i>{{ i < pairStep ? '✓' : i + 1 }}</i
                ><b>{{ s.label }}</b>
              </button>
              <span v-if="i < pairSteps.length - 1" class="st-line" :class="{ done: pairStep > i }"></span>
            </template>
          </div>

          <h1 class="view-title">{{ pairSteps[pairStep]?.heading }}</h1>
          <p v-if="pairSteps[pairStep]?.description" class="view-sub">{{ pairSteps[pairStep]?.description }}</p>

          <div v-if="pairStep === 0" class="pane">
            <div v-if="isSameOrigin" class="server-hint">
              <n-icon :size="16"><GlobeOutline /></n-icon>
              <span>{{ currentOrigin }}</span>
            </div>
            <div v-else class="field">
              <label class="field-label" for="pair-server">设备地址</label>
              <n-input
                id="pair-server"
                v-model:value="form.serverUrl"
                :style="controlVars"
                placeholder="设备地址，如 http://192.168.0.1:8088"
                :input-props="{ autocomplete: 'url' }"
              >
                <template #prefix
                  ><n-icon :size="16"><GlobeOutline /></n-icon
                ></template>
              </n-input>
            </div>

            <div class="field">
              <label class="field-label" for="pair-password">配对密码</label>
              <n-input
                id="pair-password"
                v-model:value="form.password"
                :style="controlVars"
                type="password"
                show-password-on="click"
                placeholder="4-64 位，建议含大小写与符号"
                :input-props="{ autocomplete: 'new-password' }"
                @keyup.enter="goNextStep"
              >
                <template #prefix
                  ><n-icon :size="16"><KeyOutline /></n-icon
                ></template>
              </n-input>
            </div>

            <div class="pw-strength">
              <div class="pw-strength-bars">
                <span
                  v-for="i in 4"
                  :key="i"
                  class="pw-bar"
                  :class="[{ active: i <= passwordStrength.score }, pwBarClass]"
                ></span>
              </div>
              <span class="pw-strength-label">{{ passwordStrength.label }}</span>
            </div>

            <div class="field">
              <label class="field-label" for="pair-confirm">确认密码</label>
              <n-input
                id="pair-confirm"
                v-model:value="form.confirmPassword"
                :style="controlVars"
                type="password"
                show-password-on="click"
                placeholder="再输入一次"
                :input-props="{ autocomplete: 'new-password' }"
                @keyup.enter="goNextStep"
              >
                <template #prefix
                  ><n-icon :size="16"><KeyOutline /></n-icon
                ></template>
              </n-input>
            </div>
          </div>

          <div v-else-if="pairStep === 1" class="pane">
            <div class="goform-grid">
              <div class="field">
                <label class="field-label" for="goform-ip">IP 地址</label>
                <n-input
                  id="goform-ip"
                  v-model:value="goformIp"
                  :style="controlVars"
                  placeholder="192.168.0.1"
                  :input-props="{ inputmode: 'numeric' }"
                  @keyup.enter="goNextStep"
                />
              </div>
              <div class="field">
                <label class="field-label" for="goform-port">端口</label>
                <n-input-number
                  id="goform-port"
                  v-model:value="goformPort"
                  :style="controlVars"
                  :min="1"
                  :max="65535"
                  placeholder="8080"
                />
              </div>
            </div>
            <div class="field">
              <label class="field-label" for="goform-password">后台密码</label>
              <n-input
                id="goform-password"
                v-model:value="goformPassword"
                :style="controlVars"
                type="password"
                show-password-on="click"
                placeholder="admin"
                @keyup.enter="goNextStep"
              />
            </div>
          </div>

          <div v-else class="pane">
            <GridCard title="将写入设备" density="compact">
              <InfoRow label="设备地址" :value="isSameOrigin ? currentOrigin : form.serverUrl" />
              <InfoRow label="配对密码" :value="passwordReview" />
              <InfoRow label="设备后台" :value="`${goformIp.trim()}:${goformPort ?? ''}`" />
              <InfoRow label="后台密码" :value="maskSecret(goformPassword)" />
            </GridCard>
          </div>

          <n-alert v-if="error" type="error" :bordered="false" class="view-alert">{{ error }}</n-alert>

          <p v-if="blockReason" class="block-reason">
            <n-icon :size="16"><AlertCircleOutline /></n-icon><span>{{ blockReason }}</span>
          </p>

          <div class="actions">
            <n-button :style="buttonVars" :disabled="pairStep === 0 || loading" @click="goPrev">上一步</n-button>
            <n-button
              :style="buttonVars"
              type="primary"
              :loading="loading"
              :disabled="!!blockReason || loading"
              @click="goNextStep"
            >
              {{ pairStep === pairSteps.length - 1 ? '设置并进入' : '下一步' }}
            </n-button>
          </div>
          <p class="legal">继续即表示这台设备将成为你唯一的登录凭据来源。忘记密码需要在设备上恢复出厂设置。</p>
          <n-alert v-if="identityExposedOnPlainHttp" type="warning" :bordered="false" class="login-insecure">
            当前以 HTTP 明文访问：设备身份密钥可被页面脚本读取；长期使用建议启用 HTTPS。
          </n-alert>
        </section>

        <!-- ③ 等待批准（独立视图，不再叠在表单上方） -->
        <section v-else-if="view === 'waiting'" class="view view-waiting" data-view="waiting">
          <div class="orb">
            <n-icon :size="34"><LockClosedOutline /></n-icon>
          </div>
          <h1 class="wait-title">{{ waitingTitle }}</h1>
          <p class="wait-desc">配对申请已发送。请在已配对设备的 App 上点「允许」，或填入通知里收到的 6 位批准码。</p>

          <div class="wait-meta">
            <span class="pill" :class="{ ok: approvalStatus === 'approved' }"><i></i>{{ waitingPill }}</span>
            <span>有效期约 {{ approvalTtlMin }} 分钟</span>
          </div>

          <div class="code-label">批准码</div>
          <div class="code-inputs">
            <input
              v-for="(ch, i) in codeBoxes"
              :key="i"
              :value="ch"
              maxlength="1"
              inputmode="text"
              autocapitalize="characters"
              autocomplete="off"
              spellcheck="false"
              :aria-label="`配对码第 ${i + 1} 位`"
              @input="onCodeInput(i, $event)"
              @keydown="onCodeKeydown(i, $event)"
              @paste="onCodePaste"
            />
          </div>
          <p class="code-hint">
            批准码不是设备屏幕上的配对码 —— 它由机主在已配对设备的 App 里查看，或通过邮件 / Webhook / 系统通知收到。
            没有码可以留空，机主点「允许」后这里会自动继续。
          </p>

          <ol class="timeline">
            <li class="done"><span class="mark">✓</span><span>配对申请已提交</span></li>
            <li :class="approvalStatus === 'approved' ? 'done' : 'now'">
              <span class="mark">{{ approvalStatus === 'approved' ? '✓' : '•' }}</span
              ><span>等待机主在 App 上确认</span>
            </li>
            <li :class="{ done: approvalStatus === 'approved' }">
              <span class="mark">{{ approvalStatus === 'approved' ? '✓' : '' }}</span
              ><span>确认后自动登录并进入仪表盘</span>
            </li>
          </ol>

          <n-alert v-if="error" type="error" :bordered="false" class="view-alert">{{ error }}</n-alert>

          <div class="wait-actions">
            <n-button :style="buttonVars" type="primary" :loading="loading" :disabled="loading" @click="handleLogin"
              >我已填好，重新验证</n-button
            >
            <n-button v-if="approvalStatus !== 'approved'" :style="buttonVars" :disabled="loading" @click="reapply"
              >重新申请</n-button
            >
          </div>
          <button v-if="!hasDefaultPassword" class="link-btn" @click="backToLogin">改用配对密码登录</button>
          <!-- 2026-10-22：与 App 同口径的自救入口。只在设备确实还有配对记录时显示，
               因为批准必然意味着 paired_count >= 1；文案按条数生成，不用"唯一设备"这种断言。 -->
          <button v-if="pairedCount > 0" class="link-btn" @click="showRescue = true">
            {{
              pairedCount === 1
                ? '设备上只有 1 条配对记录，但不是这台设备？清除后重新配对'
                : `设备上有 ${pairedCount} 条配对记录，都不是这台设备？清除后重新配对`
            }}
          </button>
        </section>

        <!-- ④ 机主拒绝 -->
        <section v-else-if="view === 'rejected'" class="view view-result" data-view="rejected">
          <div class="orb bad">
            <n-icon :size="34"><CloseOutline /></n-icon>
          </div>
          <h1 class="result-title">机主拒绝了这次申请</h1>
          <p class="result-desc">
            {{ error || '如果这是你自己的设备，请确认没有误点「拒绝」；也可以换一台已配对设备批准。' }}
          </p>
          <div class="result-actions">
            <n-button :style="buttonVars" @click="backToLogin">{{
              hasDefaultPassword ? '返回配对' : '返回登录'
            }}</n-button>
            <n-button :style="buttonVars" type="primary" @click="reapply">重新申请</n-button>
          </div>
        </section>

        <!-- ⑤ 申请过期 -->
        <section v-else-if="view === 'expired'" class="view view-result" data-view="expired">
          <div class="orb warn">
            <n-icon :size="34"><TimeOutline /></n-icon>
          </div>
          <h1 class="result-title">申请已过期</h1>
          <p class="result-desc">{{ error || '15 分钟内没有收到批准。重新申请会作废上一次的配对码。' }}</p>
          <div class="result-actions">
            <n-button :style="buttonVars" @click="backToLogin">{{
              hasDefaultPassword ? '返回配对' : '返回登录'
            }}</n-button>
            <n-button :style="buttonVars" type="primary" @click="reapply">重新申请</n-button>
          </div>
        </section>
      </div>

      <!-- 自救：清除设备上的全部配对记录（破坏性，必须输配对密码）。
           与 App 的「这台手机就是唯一已配对设备？」入口同一口径：按条数说明后果。 -->
      <n-modal
        v-model:show="showRescue"
        preset="card"
        :style="{ width: 'min(92vw, 420px)' }"
        title="清除设备上的配对记录"
      >
        <p class="result-desc">
          会清空设备上的全部配对记录（当前 {{ pairedCount }} 条），所有已配对设备都要重新配对，
          正在使用的设备会立刻掉线。
        </p>
        <p class="result-desc">
          仅在「这台设备清过数据 / 重装过浏览器环境，设备里留着一条已经登不上的旧记录」时使用。
          如果设备上还有其他正常使用的设备，请先在那边点「允许」。
        </p>
        <n-input
          v-model:value="rescuePwd"
          type="password"
          show-password-on="click"
          placeholder="配对密码"
          :disabled="rescueBusy"
          @keyup.enter="doRescue"
        />
        <n-alert v-if="rescueError" type="error" :bordered="false" style="margin-top: 10px">
          {{ rescueError }}
        </n-alert>
        <template #footer>
          <div style="display: flex; gap: 10px; justify-content: flex-end">
            <n-button :disabled="rescueBusy" @click="showRescue = false">取消</n-button>
            <n-button type="error" :loading="rescueBusy" :disabled="!rescuePwd || rescueBusy" @click="doRescue">
              确认清除
            </n-button>
          </div>
        </template>
      </n-modal>
    </main>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted, onUnmounted, watch } from 'vue';
import { useRouter } from 'vue-router';
import { useDialog, useMessage } from 'naive-ui';
import {
  AlertCircleOutline,
  CloseOutline,
  GlobeOutline,
  KeyOutline,
  LockClosedOutline,
  TimeOutline,
} from '@vicons/ionicons5';
import GridCard from '@/components/GridCard.vue';
import InfoRow from '@/components/InfoRow.vue';
import LoginBrand from './components/LoginBrand.vue';
import type { WizardStep } from '@/components/StepWizard.vue';
import { useAppStore } from '@/stores/app';
import { getApiClient, resetApiClient } from '@/composables/useApi';
import { calibrateClockOffset } from '@/composables/deviceIdentity';
import { evaluatePasswordStrength } from '@/composables/utils';
import { loadDeviceIdentity } from '@/composables/deviceIdentityLazy';
import { isAnotherTabActive, markActiveTab } from '@/composables/tabLock';
import {
  clearSid,
  fetchApprovalStatus,
  getOrCreateSid,
  peekSid,
  requestApproval,
  type ApprovalStatus,
} from './usePairingApproval';

// 配对记录里的显示名。设备**身份**不再由任何常量决定（见 deviceIdentity.ts）：
// 旧代码用固定的 WEB_FINGERPRINT 上报指纹，服务端把所有浏览器当成同一台设备，
// 于是"配对设备上限"对 Web 端完全无效——任意多个浏览器只要知道密码就能登录。
const WEB_DEVICE_NAME = 'Web 管理后台';

const router = useRouter();
const appStore = useAppStore();
// 「下一步」的分步校验用 message 即时提示；请求类错误走**表单上方**的 error 提示条
// （2026-10-21 B3-3：原先在提交按钮下方，矮屏上会被推到折叠线外）
const message = useMessage();
// 多标签互斥的确认框（2026-10-21 决策 D7-3）
const dialog = useDialog();
/** 用户已在"另一个标签页已登录"的提示里点了继续 —— 本次登录不再拦截。 */
let tabOverride = false;

// 同源检测：如果当前页面是从设备本身 serve 的，则自动使用当前 origin
const currentOrigin = computed(() => window.location.origin);
const isSameOrigin = computed(() => {
  // 开发环境 (vite dev server) 不算同源
  if (import.meta.env.DEV) return false;
  return true;
});

/**
 * 当前是否处于「明文 http + 身份密钥可被脚本读取」的模式（2026-10-21，审计 §B11）。
 *
 * `crypto.subtle` 只在安全上下文（https / localhost）可用；设备默认以
 * `http://192.168.x.x:8088` 提供服务，此时 deviceIdentity 走的是纯 JS 回退 ——
 * 私钥是 IndexedDB 里的 32 字节裸标量，任何同源脚本都能读走。
 */
const identityExposedOnPlainHttp = computed(() => typeof window !== 'undefined' && !window.isSecureContext);

const form = reactive({
  serverUrl: '',
  password: '',
  confirmPassword: '',
});

// 初次配对时一并写入的 Goform 后台连接配置（默认值与设备出厂一致）
const goformIp = ref('192.168.0.1');
const goformPort = ref(8080);
const goformPassword = ref('admin');

const loading = ref(false);
const error = ref('');

// ── 跨设备批准（2026-10-15）──
//
// 只在 confirm 回 403 APPROVAL_REQUIRED 之后才启用。**不能**"每次登录都先请求一次"：
// 那样即使机主把批准关着，每次网页登录也会凭空造出一条待批准记录，
// 把 App 的批准弹窗与外部通知叫醒（等于给任何人一个骚扰机主的开关）。
const approvalWaiting = ref(false);
/**
 * 设备上现有配对记录数（`/pairing/info.paired_count`，2026-10-22 core 新增）。
 *
 * 只用于自救入口的显示与文案：批准必然意味着 >= 1，但"1 条陈旧记录"与"多台正常设备"
 * 的处置完全不同，不能再用"唯一设备"这种断言糊过去。
 */
const pairedCount = ref(0);
/** 自救弹窗（清除设备上的全部配对记录）—— 破坏性操作，必须输配对密码。 */
const showRescue = ref(false);
const rescuePwd = ref('');
const rescueBusy = ref(false);
const rescueError = ref('');
const approvalStatus = ref<ApprovalStatus>('idle');
const approvalCode = ref('');
const approvalSid = ref('');
/**
 * 批准申请有效期（分钟），展示用。
 *
 * 优先用后端 `POST /pairing/request-approval` 回的 `expires_in`；拿不到时按 core 的
 * 15 分钟 TTL 兜底（`PairedDeviceApprovalStore` 的既有取值）。刷新页面恢复等待态时
 * 拿不到秒数（状态接口只回一个字符串），同样落回兜底值 —— 不编造"剩 N 分钟"。
 */
const approvalTtlMin = ref(15);
let approvalTimer: ReturnType<typeof setTimeout> | null = null;
/** 本轮轮询的起始时刻（自适应节奏用，2026-10-21）。 */
let approvalPollStartedAt = 0;
/** 轮询「代次」：stop/重启时自增，落单的异步回调据此丢弃自己的结果（2026-10-21）。 */
let approvalPollSeq = 0;
const hasDefaultPassword = ref(false);

/**
 * 控件尺寸（2026-10-22）。
 *
 * 为什么用内联变量而不是 scoped CSS：naive 把 `--n-height` / `--n-font-size` /
 * `--n-border-radius` 写在组件根节点的**内联 style** 上，外部样式表改不动它
 * （行内自定义属性优先级高于类选择器）。所以在模板上按视口传值，桌面 48 / 手机 46。
 *
 * 手机输入框字号必须 ≥16px：iOS 上聚焦小于 16px 会把整页放大。
 */
const buttonVars = {
  '--n-height': 'calc(44 * var(--u))',
  '--n-border-radius': 'calc(10 * var(--u))',
  '--n-font-size': 'max(14px, calc(15 * var(--u)))',
};
const controlVars = {
  '--n-height': 'calc(44 * var(--u))',
  '--n-border-radius': 'calc(10 * var(--u))',
  // 输入框字号下限 16px：iOS 聚焦时小于 16px 会把整页放大
  '--n-font-size': 'max(16px, calc(15 * var(--u)))',
};

/**
 * 当前视图：一次只渲染一种状态（2026-10-22）。
 *
 * 映射完全由既有状态派生，不新增业务状态：
 *   · rejected / expired  —— 轮询写入 approvalStatus 后由这里接管
 *   · waiting             —— 已提交申请（approvalWaiting）或刚被批准、正在重试登录
 *   · pair / login        —— 设备是否仍是出厂默认密码
 *
 * 为什么不做成"提示条叠在表单上"：表单与等待两套内容同时占位时，手机上用户会
 * 分不清"我到底还在登录，还是在等别人点允许"。视图互斥之后，标题与按钮都只属于当前那件事。
 */
type LoginViewName = 'login' | 'pair' | 'waiting' | 'rejected' | 'expired';

const view = computed<LoginViewName>(() => {
  if (approvalStatus.value === 'rejected') return 'rejected';
  if (approvalStatus.value === 'expired') return 'expired';
  if (approvalWaiting.value || approvalStatus.value === 'approved') return 'waiting';
  return hasDefaultPassword.value ? 'pair' : 'login';
});

/** 手机上（≤960，单列布局）切换视图时回到顶部：等待/结果比表单短，停在原位会只剩半屏 */
watch(view, () => {
  if (typeof window !== 'undefined' && window.innerWidth <= 960) {
    window.scrollTo({ top: 0, behavior: 'smooth' });
  }
});

/** 等待视图的标题与状态胶囊：批准后文案立刻变成"正在登录" */
const waitingTitle = computed(() => (approvalStatus.value === 'approved' ? '已批准，正在登录' : '等待机主批准'));
const waitingPill = computed(() => (approvalStatus.value === 'approved' ? '已批准' : '等待中'));

/**
 * 6 位码在界面上拆成 6 个格子。
 *
 * 内部仍以 `approvalCode` 为准（它会被 onApprovalCodeInput 归一化成大写字母数字），
 * 这里只是显示层：逐格输入时把六格拼起来再回写，粘贴走单独分支。
 * 留空格子组成不足 6 位的串时，handleLogin 会先拦下并提示格式，不会白耗一次 confirm。
 */
const codeBoxes = ref<string[]>(['', '', '', '', '', '']);

function focusCodeBox(index: number) {
  const inputs = document.querySelectorAll<HTMLInputElement>('.code-inputs input');
  inputs[index]?.focus();
}

function onCodeInput(index: number, e: Event) {
  const el = e.target as HTMLInputElement;
  const char = (el.value || '')
    .toUpperCase()
    .replace(/[^A-Z0-9]/g, '')
    .slice(-1);
  codeBoxes.value[index] = char;
  el.value = char;
  onApprovalCodeInput(codeBoxes.value.join(''));
  if (char) focusCodeBox(index + 1);
}

function onCodeKeydown(index: number, e: KeyboardEvent) {
  if (e.key === 'Backspace' && !codeBoxes.value[index] && index > 0) {
    codeBoxes.value[index - 1] = '';
    onApprovalCodeInput(codeBoxes.value.join(''));
    focusCodeBox(index - 1);
  } else if (e.key === 'ArrowLeft' && index > 0) {
    focusCodeBox(index - 1);
  } else if (e.key === 'ArrowRight' && index < 5) {
    focusCodeBox(index + 1);
  }
}

function onCodePaste(e: ClipboardEvent) {
  const text = (e.clipboardData?.getData('text') || '').toUpperCase().replace(/[^A-Z0-9]/g, '');
  if (!text) return;
  e.preventDefault();
  for (let i = 0; i < 6; i++) codeBoxes.value[i] = text[i] ?? '';
  onApprovalCodeInput(text.slice(0, 6));
  focusCodeBox(Math.min(text.length, 5));
}

/**
 * 重新申请（拒绝 / 过期 / 等待页的「重新申请」）。
 *
 * 先丢掉旧 sid 再发起：core 的批准记录以 sid 为单位，复用同一个 sid 会被旧状态
 * 直接弹回（2026-10-21 审计 §A2 就是这么修的）。随后走一次完整登录，
 * 服务端若仍要求批准会重新返回 403 并登记一条新申请。
 */
function reapply() {
  stopApprovalPolling();
  clearSid();
  approvalSid.value = '';
  approvalWaiting.value = false;
  approvalStatus.value = 'idle';
  approvalCode.value = '';
  codeBoxes.value = ['', '', '', '', '', ''];
  error.value = '';
  void handleLogin();
}

/**
 * 自救：清除设备上的全部配对记录（2026-10-22）。
 *
 * 场景：设备里留着一条**已经死掉**的旧配对记录（这台浏览器清过数据 / 换过环境，WebCrypto
 * 身份已丢），它仍然满足"存在已配对设备"，于是本次配对永远等不到批准。
 *
 * 与 App 的自救入口同一口径：走 `POST /pairing/unpair`（需要配对密码），清空后重新取
 * `/pairing/info` 回到表单。**破坏性**：所有已配对设备都会掉线，所以必须二次确认 + 输密码。
 */
async function doRescue() {
  if (!rescuePwd.value || rescueBusy.value) return;
  rescueBusy.value = true;
  rescueError.value = '';
  try {
    await getApiClient().post('/pairing/unpair', { password: rescuePwd.value });
    showRescue.value = false;
    rescuePwd.value = '';
    // 收尾批准等待态：sid 已经作废，留着会让下次打开页面"恢复"出一个不存在的等待。
    stopApprovalPolling();
    clearSid();
    approvalSid.value = '';
    approvalWaiting.value = false;
    approvalStatus.value = 'idle';
    approvalCode.value = '';
    codeBoxes.value = ['', '', '', '', '', ''];
    pairedCount.value = 0;
    error.value = '';
    message.success('已清空设备上的配对记录，请重新登录');
    // 重新取一次 info：unpair 不会重置配对密码，所以这里通常回到"密码登录"表单。
    try {
      const { data } = await getApiClient().get('/pairing/info');
      hasDefaultPassword.value = !!data?.has_default_password;
      pairedCount.value = Number(data?.paired_count ?? 0);
    } catch {
      // 忽略：下一次点击登录会再取
    }
  } catch (e: any) {
    const status = e?.response?.status;
    const code = e?.response?.data?.code;
    if (status === 401 && code === 'INVALID_PASSWORD') rescueError.value = '配对密码错误';
    else if (status === 401 && code === 'MISSING_PASSWORD') rescueError.value = '请输入配对密码';
    else if (status === 429) rescueError.value = '密码错误次数过多，请稍后再试';
    else if (status === 403) rescueError.value = e?.response?.data?.error || '该操作只能在设备所在的局域网内完成';
    else rescueError.value = '清除失败，请稍后重试';
  } finally {
    rescueBusy.value = false;
  }
}

/** 返回登录/配对表单：停轮询但不丢当前申请（用户可能只是去改密码再提交） */
function backToLogin() {
  stopApprovalPolling();
  approvalWaiting.value = false;
  approvalStatus.value = 'idle';
  error.value = '';
}

/**
 * 6 位配对码输入：统一转大写并剔除非字母数字（2026-10-21，审计 §C5）。
 *
 * 码的字符集是大写（`PairedDeviceApprovalStore.CODE_ALPHABET`），机主是照通知逐字敲的，
 * 敲成小写完全正常。服务端现在也按大小写不敏感比较，这里再顺手归一化一次，
 * 免得用户看着"我明明输对了"却过不去。
 */
function onApprovalCodeInput(value: string) {
  approvalCode.value = (value || '')
    .toUpperCase()
    .replace(/[^A-Z0-9]/g, '')
    .slice(0, 6);
}

// 首次配对的分步下标：0=配对密码，1=设备后台，2=确认（对应 app SetupScreen 的 confirmStep）。
// 普通登录模式不使用它（那一侧不渲染向导）。
const pairStep = ref(0);

// 模式变化（fetchPairingInfo 刷新后 true→false 或反向）时回到第 1 步：
// 否则可能停在一个已经不该存在的步上。提交失败不会走到这里，
// 用户已填的密码/GoForm 内容原样保留。
watch(hasDefaultPassword, () => {
  pairStep.value = 0;
});

// 密码强度（仅初次配对时展示）
const passwordStrength = computed(() => evaluatePasswordStrength(form.password));

/**
 * 强度条的着色档（2026-10-21，B3-3）。
 *
 * `evaluatePasswordStrength()` 返回的是**写死的 hex**（`composables/utils.ts` 属 A1 独占，
 * 本批次不能改），在模板里 `:style` 直接绑色值等于在视图里又抄一份色值。
 * 这里只按 score 选一个类，颜色全部落在 `--error / --warning / --success` 上；
 * 分档与 utils 保持一致：0–1 弱、2 中、3–4 强。
 * （A1 若把 utils 的 `color` 改成令牌，可以退回单行 `:style` 绑定。）
 */
const pwBarClass = computed(() => {
  const score = passwordStrength.value.score;
  if (score >= 3) return 'pw-bar--strong';
  if (score === 2) return 'pw-bar--fair';
  return 'pw-bar--weak';
});

/** 确认页上的密码回显：位数 + 强度，不回显明文（改它的地方是第 1 步的输入框）。 */
const passwordReview = computed(() =>
  form.password
    ? `${maskSecret(form.password)}（${form.password.length} 位 · ${passwordStrength.value.label}）`
    : '未设置'
);

function maskSecret(value: string): string {
  if (!value) return '未填写';
  return '•'.repeat(Math.min(value.length, 12));
}

/**
 * 首次配对的三步。heading 写成「这一步要回答什么」，
 * validate 决定能不能离开本步 —— 由本文件的 blockReason 在 computed 里求值，所以边打字边生效。
 */
const pairSteps = computed<WizardStep[]>(() => [
  {
    key: 'password',
    label: '配对密码',
    heading: '设置配对密码',
    description: '这台设备今后凭它登录与管理，请务必牢记。设备当前还是出厂默认密码。',
    validate: validatePasswordStep,
  },
  {
    key: 'goform',
    label: '设备后台',
    heading: '设备自带的网页后台怎么连',
    description: '设备经此地址访问它自带的管理界面，默认通常是 192.168.0.1:8080、密码 admin，一般无需修改。',
    validate: validateGoformStep,
  },
  {
    key: 'review',
    label: '确认',
    heading: '核对后写入设备',
    description: '前两步只在本地填写；点「设置并进入」才会真正提交给设备。',
  },
]);

/** 当前步能否离开（由 pairSteps[i].validate 回答；null = 可以） */
const blockReason = computed(() => pairSteps.value[pairStep.value]?.validate?.() ?? null);

/**
 * 点步骤条跳转（与旧 StepWizard 的行为一致）：可回退；向前跳要求途经的每一步都合法，
 * 否则停在原地并把原因说出来（置灰/静默不跳都是"点了没反应"）。
 */
function jumpToStep(target: number) {
  if (target === pairStep.value) return;
  if (target < pairStep.value) {
    pairStep.value = target;
    return;
  }
  for (let i = pairStep.value; i < target; i++) {
    const reason = pairSteps.value[i]?.validate?.();
    if (reason) {
      message.warning(reason);
      return;
    }
  }
  pairStep.value = target;
}

function goPrev() {
  if (pairStep.value > 0) pairStep.value -= 1;
}

// 同源模式：挂载即拉取配对信息，用于判断是否处于初次配对（与主登录流程解耦）
onMounted(async () => {
  // 2026-10-21（审计 §B8）：444 掉线时留了一句原因，这里读一次就清空。
  const failReason = appStore.consumeAuthFailureReason();
  if (failReason) error.value = failReason;

  if (isSameOrigin.value) {
    try {
      const { data } = await getApiClient().get('/pairing/info');
      hasDefaultPassword.value = !!data?.has_default_password;
      pairedCount.value = Number(data?.paired_count ?? 0);
    } catch {
      // 忽略，登录时再取
    }
  }
  // 2026-10-21（审计 §C6）：刷新页面后把「等待批准」恢复出来
  void restoreApprovalState();
});

/**
 * 刷新后恢复「等待批准」的状态（2026-10-21，审计 §C6）。
 *
 * 之前所有状态都在内存 ref 里，刷新就没了，而 sid 还在 sessionStorage、服务端 pending 也还在 ——
 * 用户只看到一张普通的登录表单，不知道自己其实还在等机主点允许。
 *
 * 这里**只查一次**、不发新申请：`GET /pairing/approval-status` 无副作用，
 * 而且"还在不在等"必须由服务端回答（15 分钟 TTL 是它的）。
 */
async function restoreApprovalState() {
  const sid = peekSid();
  if (!sid || approvalWaiting.value) return;
  approvalSid.value = sid;
  const st = await fetchApprovalStatus(getApiClient() as any, sid);
  if (st === 'pending') {
    approvalWaiting.value = true;
    approvalStatus.value = 'pending';
    startApprovalPolling();
  } else if (st === 'rejected' || st === 'expired') {
    // 收尾态：丢掉 sid，让下一次登录成为一次干净的新申请
    clearSid();
    approvalSid.value = '';
  }
}

/** 拉取配对码（免鉴权），用于密码登录的配对确认。 */
async function fetchPairingInfo(): Promise<string | null> {
  const { data } = await getApiClient().get('/pairing/info');
  hasDefaultPassword.value = !!data?.has_default_password;
  pairedCount.value = Number(data?.paired_count ?? 0);
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
 * 第 1 步「配对密码」的校验：设备地址（仅跨域）、密码非空、长度 4-64、两次一致。
 * 通过返回 null，否则返回**拦下的原因**（渲染在操作栏上方）。
 * 普通登录模式下这份校验也在 handleLogin() 里跑 —— 分步不削弱最终门禁。
 */
function validatePasswordStep(): string | null {
  if (!isSameOrigin.value && !form.serverUrl) return '请输入设备地址';
  // 初次配对：必须设置配对密码（4-64 位，且两次一致）
  if (hasDefaultPassword.value) {
    if (!form.password) return '请设置配对密码';
    if (form.password.length < 4 || form.password.length > 64) return '密码长度需 4-64 位';
    if (form.password !== form.confirmPassword) return '两次输入的密码不一致';
    return null;
  }
  if (!form.password) return '请输入配对密码';
  return null;
}

/** 第 2 步「设备后台」的校验。默认值即设备出厂值，一般直接通过。 */
function validateGoformStep(): string | null {
  if (!goformIp.value.trim()) return '请填写设备后台 IP 地址';
  if (!goformPassword.value) return '请填写设备后台密码';
  const port = Number(goformPort.value);
  if (!Number.isInteger(port) || port < 1 || port > 65535) return '后台端口需在 1-65535 之间';
  return null;
}

/**
 * 输入框上的回车 = 点一下主按钮：当前步合法就往下走，最后一步则提交。
 * 向导自己的按钮走 goNextStep / goPrev，不经过这里。
 */
function goNextStep() {
  const err = pairSteps.value[pairStep.value]?.validate?.() ?? null;
  if (err) {
    error.value = err;
    message.error(err);
    return;
  }
  error.value = '';
  if (pairStep.value < pairSteps.value.length - 1) pairStep.value += 1;
  else void handleLogin();
}

/**
 * 登录 / 初次配对：镜像 Android app 的配对流程。
 * 1) GET /pairing/info 取配对码
 * 2) POST /pairing/confirm（配对码 + Web 指纹 + 配对密码）换 token
 *    —— 设备未设密码时，该密码即被设为新配对密码（对应 app 的“初次配对设置密码”）
 * 3) 写回 token，校验连通性后进入仪表盘
 */
async function handleLogin() {
  error.value = '';
  // 2026-10-21（审计 §B10）：批准轮询在 approved 时会自动重登，机主也可能同时点
  // 「我已填好，重新验证」。两条 confirm 并发会各自换一次 token：后完成的覆盖前一个，
  // 先完成的后续请求立刻 444 —— 表现就是"刚批完又被踢回登录页"。
  if (loading.value) return;
  // 2026-10-21（决策 D7-3）：同一浏览器同一时间只能有一个标签页保持登录 ——
  // 再次 confirm 会轮换 token，另一个标签页随即 444。这里把"静默互踢"变成知情选择。
  if (!tabOverride && isAnotherTabActive()) {
    dialog.warning({
      title: '本设备已在另一个标签页登录',
      content:
        '同一浏览器同一时间只支持一个标签页保持登录：继续登录会让另一个标签页掉线（需要它重新登录）。' +
        '建议关掉其它标签页，或先在其中退出。',
      positiveText: '仍然继续',
      negativeText: '取消',
      onPositiveClick: () => {
        tabOverride = true;
        void handleLogin();
      },
    });
    return;
  }
  // 第 1 步字段（设备地址 / 密码长度 / 两次一致）——与「下一步」共用同一份校验。
  // 分步只是 UI，这里仍然跑全量校验：即使用户绕过第 1 步也过不了这道门禁。
  const stepErr = validatePasswordStep();
  if (stepErr) {
    error.value = stepErr;
    // 出错字段在第 1 步上，退回去让用户看得见（这不是提交失败，是校验没过）
    if (hasDefaultPassword.value) pairStep.value = 0;
    return;
  }
  // 2026-10-21（审计 §C5）：6 位码本地先校验一遍 —— 少一个字符就发出去会白耗一次
  // confirm（还会轮换掉配对码），而用户看不出任何原因。
  const approvalCodeInput = (approvalCode.value || '').trim().toUpperCase();
  if (approvalCodeInput && !/^[A-Z0-9]{6}$/.test(approvalCodeInput)) {
    error.value = '配对码是 6 位字母或数字，请核对后重试';
    return;
  }
  if (hasDefaultPassword.value) {
    // Goform 后台连接配置校验（与设备出厂默认值一致，一般无需改动）
    const goformErr = validateGoformStep();
    if (goformErr) {
      error.value = goformErr;
      pairStep.value = 1;
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
    // 设备已初始化（非默认密码）时允许空配对码：后端支持凭配对密码登录（登录=自动配对），
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
      // 局域网来源在 has_default_password=true 时一定拿得到配对码，所以「有默认密码却没有码」
      // 只有一种成因：请求是从隧道转发进来的，core 有意不下发（见 PairingRoutes 的隧道来源降级）。
      error.value = '首次配对只能在设备所在的局域网内完成';
      return;
    }

    // 2. 挑战-应答 + 配对密码换 token（初次配对时该密码即被设为新密码）
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
        // 批准流程（2026-10-15）：登记过的会话随机数 + 机主从通知里读到的配对码。
        // 没走批准时两者都为空 —— 服务端按"不需要批准"处理（开关默认关）。
        ...(approvalSid.value ? { sid: approvalSid.value } : {}),
        ...(approvalCodeInput ? { approval_code: approvalCodeInput } : {}),
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

    // 3. 连通性 + 鉴权校验。
    // /health 免鉴权且带 core 服务器时间 → 先校准签名时钟，再打需要签名的 uptime，
    // 否则本机时钟漂移 >5min 时这一步必然 STALE_TIMESTAMP（而配对端点不验时间戳，
    // 于是表现成"能配对、一用就失败"）。
    //
    // 2026-10-21（审计 §B9）：token 只能在这之前落库（uptime 要签名），所以校验失败时
    // **必须把这次写入回滚** —— 否则界面说"登录失败"，而刷新页面（路由守卫只看 token
    // 在不在）却能直接进 dashboard，提示和事实互相矛盾，还白占了上一份 token 的轮换。
    try {
      const healthRes = await api.get('/health');
      calibrateClockOffset(healthRes.data?.timestamp);
      await api.get('/api/system/uptime');
    } catch (e: any) {
      appStore.clearAuth({ keepBaseUrl: true });
      const reason = e?.response?.data?.error || e?.message || '网络异常';
      error.value = `配对已完成，但连通性校验失败（${reason}），请重新登录`;
      return;
    }

    // 2026-10-21（决策 D7-3）：占住"活跃标签页"这把锁，其它标签页再登录时会先收到提示。
    markActiveTab();
    tabOverride = false;
    router.push('/dashboard');
  } catch (e: any) {
    handleNetworkError(e);
  } finally {
    loading.value = false;
  }
}

/**
 * 启动批准流程（2026-10-15）：登记一次请求 + 轮询状态。
 *
 * 登记幂等（同一 sid 重复请求不会新建记录、也不会重复通知）—— 所以用户连点登录
 * 不会把机主的手机刷屏。
 */
async function beginApproval() {
  try {
    const devId = await loadDeviceIdentity();
    const identity = await devId.getDeviceIdentity();
    approvalSid.value = getOrCreateSid();
    const res = await requestApproval(
      getApiClient() as any,
      approvalSid.value,
      identity.publicKeySpki,
      WEB_DEVICE_NAME
    );
    if (!res.ok) {
      // 提交失败就**别进等待态**：否则用户会对着"等待批准"干等，最后只等到一句
      // "已过期"（2026-10-17 线上就是这个表现：路由 404 → 等待 → 轮询也 404 → 过期）。
      //
      // 2026-10-21（审计 §B7）：区分 HTTP 成因。之前除 501 之外一律说"设备端版本过旧"，
      // 而最常见的其实是 429（同 IP 15 分钟最多 5 条新申请）—— 用户按提示重试永远撞墙。
      if (res.status === 'unsupported') {
        error.value = '设备端未启用批准流程，请改用局域网配对';
      } else if (res.httpStatus === 429) {
        error.value = '配对申请过于频繁，请稍后再试（同一网络 15 分钟内最多 5 次）';
      } else if (res.httpStatus === 400) {
        error.value = '配对申请被拒绝（参数不合法），请刷新页面后重试';
      } else {
        error.value = '提交配对申请失败，请重试';
      }
      return;
    }
    // 旧 core（未修 rejected 复用）可能把一次被拒的 sid 原样回 `rejected`：
    // 这时不要进等待态，换一个全新 sid 让用户能真正重新申请。
    if (res.status === 'rejected' || res.status === 'expired') {
      clearSid();
      approvalSid.value = '';
      error.value = '上一次申请已结束，请重新点击登录发起新的申请';
      return;
    }
    approvalWaiting.value = true;
    approvalStatus.value = 'pending';
    error.value = '';
    // 后端给了 expires_in 就用它（秒 → 分钟，至少 1 分钟以免显示「0 分钟」）；
    // 没给则按 core 的 15 分钟 TTL 兜底，不编造具体秒数。
    approvalTtlMin.value = res.expiresIn > 0 ? Math.max(1, Math.round(res.expiresIn / 60)) : 15;
    message.info('已提交配对申请：请在已配对设备上点「允许」，或输入通知里的配对码');
    startApprovalPolling();
  } catch (e: any) {
    handleNetworkError(e);
  }
}

/**
 * 等待批准期间的状态轮询节奏（毫秒，2026-10-21）。
 *
 * 原来是**固定 3 秒**。core 现在能在 1 秒内把弹窗推到 App（`ApprovalPushNudger`），
 * 剩下的时间几乎全花在「机主看到弹窗 → 点允许」上；而**点完到网页真正进去**还要等
 * 下一次轮询 —— 固定 3 秒就等于在用户觉得"明明已经点了"的地方再加平均 1.5 秒。
 *
 * 所以改成自适应：刚开始最密（机主大概率就在旁边，点了要立刻过），越等越稀
 * （多半是没人在，别在 15 分钟 TTL 里白打几百个请求）。
 * `/pairing/approval-status` 是免鉴权、纯内存查询、无独立限速的读接口，单次成本可以忽略。
 */
function approvalPollDelay(elapsedMs: number): number {
  if (elapsedMs < 20_000) return 700;
  if (elapsedMs < 60_000) return 1_500;
  return 3_000;
}

/**
 * 启动状态轮询：批准即带着同一个 sid 重试登录，拒绝/过期则收尾。
 *
 * ## 为什么不是 `setInterval`（2026-10-21 改写）
 * 1. `setInterval` **不等**上一次请求返回 —— 网络一慢就叠出几个并发 GET，
 *    回来顺序还可能乱（后发的先回，把新状态覆盖成旧的）；「问完再排下一次」没有这个问题；
 * 2. 自适应节奏本来就要每次重算间隔。
 */
function startApprovalPolling() {
  stopApprovalPolling();
  approvalPollStartedAt = Date.now();
  // stopApprovalPolling() 刚自增过代次，本轮用这个值；之后任何人 stop 都会让 seq 对不上
  const seq = approvalPollSeq;

  const tick = async () => {
    if (seq !== approvalPollSeq) return;
    const st = await fetchApprovalStatus(getApiClient() as any, approvalSid.value);
    // 请求期间可能已经被 stop（已批准 / 已拒绝 / 离开页面）—— 落单的结果必须丢掉，
    // 否则会出现「已经进了 dashboard，又被一个迟到的 expired 打回登录页」
    if (seq !== approvalPollSeq) return;
    // null = 这次请求本身失败（网络抖动 / 服务端报错）—— **不**当作过期，
    // 继续等下一轮；真正的过期由服务端返回 expired 或 TTL 决定。
    if (st !== null) {
      approvalStatus.value = st;
      if (st === 'approved') {
        stopApprovalPolling();
        approvalWaiting.value = false;
        // 已批准：重试登录。这一次服务端不会再回 403（同一 sid 已标记 approved）。
        void handleLogin();
        return;
      }
      if (st === 'rejected' || st === 'expired') {
        stopApprovalPolling();
        approvalWaiting.value = false;
        // 2026-10-21（审计 §A2）：收尾态必须丢掉 sid —— 否则用户再点登录还是同一次申请，
        // 服务端（旧版本）会一直回同一个 rejected，15 分钟 TTL 内变成死胡同。
        clearSid();
        approvalSid.value = '';
        error.value =
          st === 'rejected' ? '机主拒绝了这次配对申请，可重新点击登录再申请一次' : '配对申请已过期，请重新登录';
        return;
      }
    }
    if (seq !== approvalPollSeq) return;
    approvalTimer = setTimeout(
      () => {
        void tick();
      },
      approvalPollDelay(Date.now() - approvalPollStartedAt)
    );
  };

  // 立刻先问一次：登记那一下状态必然是 pending，这一步主要是让"提交成功 → 显示等待中"
  // 之间没有空窗，同时覆盖「刚登记就被批准」这种极快的情况。
  approvalTimer = setTimeout(() => {
    void tick();
  }, 0);
}

function stopApprovalPolling() {
  approvalPollSeq++;
  if (approvalTimer) {
    clearTimeout(approvalTimer);
    approvalTimer = null;
  }
}

onUnmounted(stopApprovalPolling);

/** 配对确认阶段的错误映射（对应 API 参考文档的错误码）。 */
function handleConfirmError(e: any) {
  const status = e.response?.status;
  const code = e.response?.data?.code;
  if (status === 401 && code === 'INVALID_CODE') {
    error.value = '配对码无效，请刷新页面后重试';
  } else if (status === 401 && code === 'INVALID_PASSWORD') {
    error.value = '配对密码错误';
  } else if (status === 401 && (code === 'INVALID_DEVICE_KEY' || code === 'INVALID_CHALLENGE')) {
    // 挑战一次性且 2 分钟过期；重新点登录会重新走 challenge，不需要用户改任何输入
    error.value = '设备身份校验失败，请重新点击登录';
  } else if (status === 429 && code === 'PASSWORD_LOCKED') {
    error.value = '配对密码错误次数过多，已锁定，请 15 分钟后再试';
  } else if (status === 409 && code === 'ALREADY_PAIRED') {
    error.value = '配对设备数量已达上限，请先在「配对管理」解除其他设备';
  } else if (status === 400 && code === 'PASSWORD_REQUIRED') {
    error.value = '请输入配对密码';
  } else if (status === 400 && code === 'INVALID_GOFORM_CONFIG') {
    error.value = '设备后台配置无效（IP/端口/密码格式错误）';
  } else if (status === 400) {
    error.value = '请求参数缺失';
  } else if (status === 403 && code === 'APPROVAL_REQUIRED') {
    // 2026-10-15：新设备需要机主批准。**不是错误**，而是一个"去走批准流程"的指令 ——
    // 所以这里不写 error.value，而是启动登记 + 轮询。
    void beginApproval();
  } else if (status === 403 && code === 'DEVICE_BLOCKED') {
    // 2026-10-21（决策 D4）：core 现在会带 `retry_after_seconds`。
    // 解除拉黑**不会**立即生效（冷却最长 1 小时），之前只能写"最长 1 小时"，
    // 用户不知道该等 5 分钟还是一小时 —— 反复重试反而在刷新冷却计数。
    const secs = Number(e.response?.data?.retry_after_seconds ?? 0);
    const wait = secs > 0 ? (secs < 60 ? `${secs} 秒` : `${Math.ceil(secs / 60)} 分钟`) : '';
    error.value = wait
      ? `本设备已被拉黑，无法配对或登录。还需等待约 ${wait}（解除拉黑不会立即生效）。`
      : '本设备已被拉黑，无法配对或登录。';
  } else if (status === 403) {
    // core 把「首次配对」限制在局域网内，文案由服务端给出（含具体动作名）
    error.value = e.response?.data?.error || '该操作只能在设备所在的局域网内完成';
  } else if (status === 429) {
    error.value = '操作过于频繁，请稍后重试';
  } else {
    handleNetworkError(e);
  }
}

function handleNetworkError(e: any) {
  if (e.response?.status === 401) {
    // 2026-10-05 修复：core 的 401 语义是「这次请求本身有问题（时间戳/签名），凭据仍有效」，
    // 只有 444 才是吊销（AuthMiddleware 三档设计）。旧文案把时钟漂移说成"凭据已失效"，
    // 用户反复重登毫无用处 —— 按响应体的 code 给出真正可执行的指引。
    const code = e.response?.data?.code;
    if (code === 'STALE_TIMESTAMP') {
      error.value = '设备时间校验失败：请检查本机时间是否准确（设置→自动确定时间），并与设备时间保持一致';
    } else if (code === 'INVALID_SIGNATURE' || code === 'INVALID_DEVICE_KEY') {
      error.value = '请求签名校验失败，请刷新页面重试；若持续出现请在设备管理中删除本浏览器后重新配对';
    } else {
      error.value = '登录校验失败，请重试';
    }
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
/*
  登录页的尺寸全部由一个视口比例单位 `--u` 派生：
    --u = clamp(0.94px, 100vw / 1200, 1px)
  1200px 及以上**恒为 1px**（= 设计稿 02-split-brand 的原尺寸）；更窄按比例缩，
  下限 0.94px 保证手机上的输入框/按钮仍接近 44~46px 的触控高度。
  所有 `calc(N * var(--u))` 里的 N 都是"1200px 视口下的设计值"，
  于是不同设备上**只有整体比例变化**，元素之间的相对关系不变。

  为什么必须有上限（2026-10-23）：设计稿的尺寸是绝对 px（卡片 520、标题 24、输入 48）。
  此前写成 `max(0.94px, 100vw / 1200)`，1920px 视口下 u=1.6 —— 卡片 832、标题 38、
  输入框 77，整页比设计稿大一圈，正是"自适应之后整体变大"的来源。
  左栏是 47% 百分比、卡片是按 u 算的固定宽，两者本来就不需要同步放大：
  宽屏多出来的空间应当变成留白，而不是继续撑大控件。宽屏要更紧凑就把上限往下调
  （例如 0.92px），所有尺寸会一起等比缩小。
*/
.login-shell {
  --u: clamp(0.94px, calc(100vw / 1200), 1px);
  /* 登录页锁在视口内：内容再长也不让整页滚动，卡片自己成为滚动边界 */
  height: 100vh;
  height: 100dvh;
  min-height: 0;
  overflow: hidden;
  display: grid;
  grid-template-columns: minmax(calc(360 * var(--u)), 47%) 1fr;
  background: var(--page-bg);
}
.login-form-side {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 100%;
  min-height: 0;
  padding: calc(28 * var(--u)) calc(24 * var(--u));
  overflow: hidden;
  min-width: 0;
}
.login-card {
  width: 100%;
  max-width: calc(460 * var(--u));
  /* 卡片高度不超过可用区域；正常登录内容会直接放下，只有更长的配对/等待视图才内部滚动 */
  max-height: 100%;
  overflow-y: auto;
  overscroll-behavior: contain;
  background: var(--card-bg);
  border: 1px solid var(--border-subtle);
  border-radius: calc(18 * var(--u));
  padding: calc(30 * var(--u)) calc(34 * var(--u)) calc(26 * var(--u));
  box-shadow: 0 24px 64px -44px var(--shadow-color-strong);
  animation: card-in 0.52s cubic-bezier(0.2, 0.7, 0.3, 1) both;
}

/* ── 视图切换：一次只渲染一块 ── */
.view {
  animation: view-in 0.26s cubic-bezier(0.2, 0.7, 0.3, 1) both;
}
@keyframes view-in {
  from {
    opacity: 0;
    transform: translateY(6px);
  }
  to {
    opacity: 1;
    transform: none;
  }
}
@keyframes card-in {
  from {
    opacity: 0;
    transform: translateY(16px) scale(0.992);
  }
  to {
    opacity: 1;
    transform: none;
  }
}
.pane {
  animation: view-in 0.28s cubic-bezier(0.2, 0.7, 0.3, 1) both;
}

.view-title {
  font-size: calc(22 * var(--u));
  font-weight: 700;
  letter-spacing: -0.015em;
  margin-bottom: calc(6 * var(--u));
  color: var(--text-primary);
}
.view-sub {
  font-size: calc(13 * var(--u));
  color: var(--text-secondary);
  line-height: 1.65;
  margin-bottom: calc(18 * var(--u));
}

/* ── 步骤条 ── */
.stepper {
  display: flex;
  align-items: center;
  gap: calc(12 * var(--u));
  margin-bottom: calc(18 * var(--u));
}
.st {
  display: inline-flex;
  align-items: center;
  gap: calc(8 * var(--u));
  font-size: calc(13 * var(--u));
  color: var(--text-muted);
  white-space: nowrap;
  /* 步骤条是可点的按钮（回退 / 前跳），这里把浏览器默认按钮样式清掉 */
  background: none;
  border: 0;
  padding: 0;
  font-family: inherit;
  cursor: pointer;
}
.st i {
  width: calc(24 * var(--u));
  height: calc(24 * var(--u));
  border-radius: 50%;
  display: grid;
  place-items: center;
  font-style: normal;
  font-size: calc(12 * var(--u));
  border: 1px solid var(--input-border);
  background: var(--card-bg);
  color: var(--text-muted);
  transition: 0.2s;
}
.st b {
  font-weight: 500;
}
.st.active {
  color: var(--text-primary);
}
.st.active b {
  font-weight: 600;
}
.st.active i {
  border-color: var(--accent-color);
  color: var(--accent-color);
  background: var(--accent-color-light);
  animation: step-pop 0.3s cubic-bezier(0.2, 0.7, 0.3, 1);
}
.st.done {
  color: var(--text-secondary);
}
.st.done i {
  background: var(--accent-color);
  border-color: var(--accent-color);
  color: var(--on-accent);
}
.st:hover i {
  border-color: var(--accent-color);
}
.st-line {
  flex: 1;
  height: 1px;
  min-width: calc(14 * var(--u));
  background: var(--border-subtle);
  transition: background 0.25s ease;
}
.st-line.done {
  background: var(--accent-color);
}
@keyframes step-pop {
  0% {
    transform: scale(0.78);
  }
  62% {
    transform: scale(1.1);
  }
  100% {
    transform: scale(1);
  }
}

/* ── 输入 ── */
.field {
  margin-bottom: calc(14 * var(--u));
}
.field-label {
  display: block;
  font-size: calc(12.5 * var(--u));
  font-weight: 500;
  color: var(--text-secondary);
  margin-bottom: calc(7 * var(--u));
}
.field :deep(.n-input),
.field :deep(.n-input-number) {
  width: 100%;
}
.server-hint {
  display: flex;
  align-items: center;
  gap: calc(9 * var(--u));
  padding: calc(10 * var(--u)) calc(12 * var(--u));
  border-radius: calc(10 * var(--u));
  background: var(--surface-elevated);
  border: 1px solid var(--border-subtle);
  font-size: calc(12.5 * var(--u));
  color: var(--text-secondary);
  margin-bottom: calc(16 * var(--u));
}
.server-hint .n-icon {
  color: var(--text-muted);
  flex: none;
}

/* ── 密码强度 ── */
.pw-strength {
  display: flex;
  align-items: center;
  gap: calc(12 * var(--u));
  margin: calc(-6 * var(--u)) 0 calc(18 * var(--u));
}
.pw-strength-bars {
  display: flex;
  gap: calc(4 * var(--u));
  flex: 1;
}
.pw-bar {
  flex: 1;
  height: calc(4 * var(--u));
  border-radius: calc(2 * var(--u));
  background: var(--border-subtle);
  transition: background 0.2s;
}
.pw-bar.active.pw-bar--weak {
  background: var(--error);
}
.pw-bar.active.pw-bar--fair {
  background: var(--warning);
}
.pw-bar.active.pw-bar--strong {
  background: var(--success);
}
.pw-strength-label {
  font-size: calc(13 * var(--u));
  color: var(--text-muted);
  min-width: calc(30 * var(--u));
  text-align: right;
}

/* ── 设备后台两步 ── */
.goform-grid {
  display: grid;
  grid-template-columns: 1fr calc(132 * var(--u));
  gap: calc(14 * var(--u));
}

/* ── 拦下原因 / 错误条 ── */
.view-alert {
  margin: 0 0 calc(14 * var(--u));
}
.block-reason {
  display: flex;
  align-items: center;
  gap: calc(7 * var(--u));
  color: var(--error);
  font-size: calc(12.5 * var(--u));
  margin: calc(2 * var(--u)) 0 calc(14 * var(--u));
}

/* ── 动作区：同一行等宽等高 ── */
.actions,
.wait-actions,
.result-actions {
  display: flex;
  gap: calc(12 * var(--u));
  align-items: stretch;
  margin-top: calc(18 * var(--u));
}
.actions :deep(.n-button),
.wait-actions :deep(.n-button),
.result-actions :deep(.n-button) {
  flex: 1 1 0;
  min-width: 0;
}
.legal {
  margin-top: calc(14 * var(--u));
  font-size: calc(11.5 * var(--u));
  color: var(--text-muted);
  line-height: 1.55;
}
/* HTTP 提示是安全提醒，但不能把卡片重新撑出视口；字号/内边距压紧 */
.login-insecure {
  margin-top: calc(12 * var(--u));
}
.login-insecure :deep(.n-alert-body__content) {
  font-size: calc(11.5 * var(--u));
  line-height: 1.5;
}

/* ── 等待批准 ── */
.view-waiting,
.view-result {
  text-align: center;
  padding-top: calc(4 * var(--u));
}
.orb {
  width: calc(84 * var(--u));
  height: calc(84 * var(--u));
  margin: 0 auto calc(24 * var(--u));
  border-radius: 50%;
  display: grid;
  place-items: center;
  position: relative;
  background: var(--warning-light);
  color: var(--warning);
}
.orb::after {
  content: '';
  position: absolute;
  inset: calc(-11 * var(--u));
  border-radius: 50%;
  border: 2px solid var(--warning);
  opacity: 0.35;
  animation: orb-ring 1.8s ease-out infinite;
}
.orb.bad {
  background: var(--error-light);
  color: var(--error);
}
.orb.bad::after {
  border-color: var(--error);
  animation: none;
  opacity: 0.25;
}
.orb.warn {
  background: var(--warning-light);
  color: var(--warning);
}
.orb.warn::after {
  border-color: var(--warning);
  animation: none;
  opacity: 0.25;
}
@keyframes orb-ring {
  0% {
    transform: scale(0.72);
    opacity: 0.5;
  }
  100% {
    transform: scale(1.18);
    opacity: 0;
  }
}
.wait-title {
  font-size: calc(24 * var(--u));
  font-weight: 700;
  letter-spacing: -0.015em;
  margin-bottom: calc(9 * var(--u));
  color: var(--text-primary);
}
.wait-desc {
  font-size: calc(14 * var(--u));
  line-height: 1.75;
  color: var(--text-secondary);
  margin: 0 auto calc(20 * var(--u));
}
.wait-meta {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: calc(12 * var(--u));
  margin-bottom: calc(22 * var(--u));
  font-size: calc(13 * var(--u));
  color: var(--text-muted);
}
.pill {
  display: inline-flex;
  align-items: center;
  gap: calc(7 * var(--u));
  background: var(--warning-light);
  color: var(--warning);
  border-radius: var(--radius-pill);
  padding: calc(5 * var(--u)) calc(12 * var(--u));
  font-weight: 600;
  font-size: calc(13 * var(--u));
}
.pill.ok {
  background: var(--success-light);
  color: var(--success);
}
.pill i {
  width: calc(6 * var(--u));
  height: calc(6 * var(--u));
  border-radius: 50%;
  background: currentColor;
  animation: pill-blink 1.2s infinite;
}
.pill.ok i {
  animation: none;
}
@keyframes pill-blink {
  50% {
    opacity: 0.3;
  }
}

.code-label {
  font-size: calc(13 * var(--u));
  color: var(--text-secondary);
  margin-bottom: calc(9 * var(--u));
  text-align: left;
}
.code-inputs {
  display: grid;
  grid-template-columns: repeat(6, 1fr);
  gap: calc(9 * var(--u));
}
.code-inputs input {
  width: 100%;
  min-width: 0;
  height: calc(56 * var(--u));
  text-align: center;
  /* 码格字号同时满足 iOS 的 16px 下限；两侧都用同一比例，高宽比保持稳定 */
  font-size: max(16px, calc(22 * var(--u)));
  font-weight: 700;
  border: 1px solid var(--input-border);
  border-radius: calc(12 * var(--u));
  background: var(--card-bg);
  color: var(--text-primary);
  outline: none;
  transition:
    border-color 0.15s,
    box-shadow 0.15s,
    transform 0.1s;
}
.code-inputs input:focus {
  border-color: var(--accent-color);
  box-shadow: 0 0 0 3px var(--accent-ring);
  transform: translateY(-1px);
}
.code-hint {
  font-size: calc(12 * var(--u));
  color: var(--text-muted);
  line-height: 1.65;
  margin: calc(9 * var(--u)) 0 calc(20 * var(--u));
  text-align: left;
}

.timeline {
  list-style: none;
  text-align: left;
  border-top: 1px solid var(--border-subtle);
  padding-top: calc(16 * var(--u));
  margin-bottom: calc(22 * var(--u));
}
.timeline li {
  display: flex;
  gap: calc(11 * var(--u));
  align-items: flex-start;
  font-size: calc(13 * var(--u));
  color: var(--text-muted);
  padding: calc(6 * var(--u)) 0;
  line-height: 1.5;
}
.timeline li .mark {
  width: calc(18 * var(--u));
  height: calc(18 * var(--u));
  border-radius: 50%;
  border: 1.5px solid var(--input-border);
  display: grid;
  place-items: center;
  flex: none;
  margin-top: 1px;
  font-size: calc(11 * var(--u));
}
.timeline li.done {
  color: var(--text-secondary);
}
.timeline li.done .mark {
  background: var(--accent-color);
  border-color: var(--accent-color);
  color: var(--on-accent);
}
.timeline li.now {
  color: var(--text-primary);
  font-weight: 500;
}
.timeline li.now .mark {
  border-color: var(--warning);
  color: var(--warning);
}

.link-btn {
  display: block;
  width: 100%;
  margin-top: calc(16 * var(--u));
  background: none;
  border: none;
  color: var(--text-secondary);
  font-size: calc(13 * var(--u));
  cursor: pointer;
  padding: calc(4 * var(--u));
}
.link-btn:hover {
  color: var(--accent-color);
}

.result-title {
  font-size: calc(24 * var(--u));
  font-weight: 700;
  margin-bottom: calc(9 * var(--u));
  color: var(--text-primary);
}
.result-desc {
  font-size: calc(14 * var(--u));
  color: var(--text-secondary);
  line-height: 1.75;
  margin: 0 auto calc(24 * var(--u));
}

/* ── 布局断点只改"排几列"，字号与内距继续由 --u 等比缩放 ──
   单列阈值与 mockup 02-split-brand 对齐为 960：769–960 的平板仍算"手机形态"，
   用轻抬头 + 单列卡片；961 以上一律桌面两栏。品牌区在 961–1024 的收紧规则
   放在 LoginBrand.vue（与 mockup 的 1024 断点一致）。 */

@media (max-width: 960px) {
  /* 手机：单列（品牌条由 LoginBrand 自己切换） */
  .login-shell {
    grid-template-columns: 1fr;
    grid-template-rows: auto 1fr;
    align-content: start;
    height: auto;
    min-height: 100dvh;
    overflow: visible;
  }
  .login-form-side {
    height: auto;
    min-height: 0;
    overflow: visible;
    padding: calc(8 * var(--u)) calc(12 * var(--u)) calc(20 * var(--u) + env(safe-area-inset-bottom));
    /* 卡片在品牌栏以下的剩余空间里垂直居中；内容高于视口时按 safe center
       退化为顶部对齐，页面继续自然滚动，不会被裁掉。 */
    align-items: center;
    align-items: safe center;
  }
  .login-card {
    max-height: none;
    overflow: visible;
    overscroll-behavior: auto;
    margin: 0 auto;
    padding: calc(20 * var(--u)) calc(16 * var(--u)) calc(18 * var(--u));
    border-radius: calc(14 * var(--u));
  }
}

@media (max-width: 420px) {
  /* 只改排列：后台 IP/端口竖排、长按钮竖排 */
  .goform-grid {
    grid-template-columns: 1fr;
    gap: 0;
  }
  .wait-actions,
  .result-actions {
    flex-direction: column-reverse;
  }
  .wait-actions :deep(.n-button),
  .result-actions :deep(.n-button) {
    flex: 0 0 auto;
    width: 100%;
  }
}

@media (prefers-reduced-motion: reduce) {
  .login-card,
  .view,
  .pane,
  .orb::after,
  .pill i,
  .st.active i {
    animation: none;
  }
}
</style>
