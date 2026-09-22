<template>
  <div class="settings-panel">
    <!-- Card 2: 通用设置 -->
    <GridCard title="通用设置">
      <template #extra>
        <n-button size="tiny" type="error" quaternary @click="resetConfig">恢复默认</n-button>
      </template>
      <div class="config-section">
        <div class="section-subtitle">设备后台连接</div>
        <div class="config-grid">
          <div class="config-item">
            <span class="config-label">IP</span>
            <n-input v-model:value="generalForm.goformIp" placeholder="192.168.0.1" size="small" />
          </div>
          <div class="config-item">
            <span class="config-label">设备后台端口</span>
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
        </div>
        <!-- 三个排障开关都走 ToggleRow + 即时保存（见 saveProbeSwitch）：
             它们需要说明位（端点名 + 危险警示 + 生效时机），而上面两列栅格里的
             .switch-item 那一种没有说明位，硬塞会把警示挤成第二个标签。
             注意第三个「字段归一化」与前两个的生效时机不同：它要重启后台服务，
             见 PROBE_NEEDS_RESTART。 -->
        <ToggleRow
          label="设备原始字段 dump（排障用，默认关，改动立即生效）"
          :description="probeDescription('goform_dump_enabled', PROBE_DUMP_DESC)"
          :model-value="generalForm.goformDumpEnabled"
          :loading="probeSaving.goform_dump_enabled"
          :disabled="probeSwitchDisabled('goform_dump_enabled')"
          @update:model-value="(v: boolean) => saveProbeSwitch('goform_dump_enabled', v)"
        />
        <ToggleRow
          label="裸 goform 命令通道（排障用，默认关，改动立即生效）"
          :description="probeDescription('goform_command_enabled', PROBE_COMMAND_DESC)"
          :model-value="generalForm.goformCommandEnabled"
          :loading="probeSaving.goform_command_enabled"
          :disabled="probeSwitchDisabled('goform_command_enabled')"
          @update:model-value="(v: boolean) => saveProbeSwitch('goform_command_enabled', v)"
        />
        <ToggleRow
          label="字段归一化（默认开，改完需重启后台服务）"
          :description="probeDescription('field_normalization_enabled', PROBE_FIELD_NORMALIZATION_DESC)"
          :model-value="generalForm.fieldNormalizationEnabled"
          :loading="probeSaving.field_normalization_enabled"
          :disabled="probeSwitchDisabled('field_normalization_enabled')"
          @update:model-value="(v: boolean) => saveProbeSwitch('field_normalization_enabled', v)"
        />
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
            <span class="config-label">下载方式</span>
            <n-select
              :value="updateMode"
              :options="UPDATE_MODE_OPTIONS"
              :loading="detectingGeo"
              size="small"
              :disabled="!generalLoaded"
              @update:value="onUpdateModeChange"
            />
          </div>
          <div class="config-item">
            <span class="config-label">设备出口地区</span>
            <div style="display: flex; align-items: center; gap: 8px; min-height: 28px">
              <span style="font-size: 13px">{{ geoText }}</span>
              <n-button size="tiny" :loading="detectingGeo" :disabled="!generalLoaded" @click="detectGeo(true)">
                重新检测
              </n-button>
            </div>
          </div>
        </div>
        <span class="row-hint" style="display: block; margin-top: 4px">
          「自动」由设备按出口 IP 所在地区决定：中国大陆走镜像加速，其他地区（含未测出）直连。
          镜像节点与失败换源都由设备侧处理，这里只需要选一个方式，改完点下方「保存」。
        </span>
      </div>
      <div class="card-actions">
        <n-button type="primary" size="small" :loading="savingGeneral" :disabled="!generalLoaded" @click="saveGeneral"
          >保存</n-button
        >
      </div>
    </GridCard>
    <!-- 恢复默认配置：需配对密码，core 侧 /api/config/reset 会校验 -->
    <n-modal
      v-model:show="resetModalOpen"
      preset="card"
      title="恢复默认配置"
      style="width: 460px; max-width: calc(100vw - 32px)"
      :mask-closable="!resetting"
    >
      <n-space vertical :size="12">
        <n-alert type="warning" :bordered="false">
          将把端口、设备后台连接、日志、QoS、更新源等配置全部恢复默认，<strong>需重启服务生效</strong>。
          配对信息与配对密码不受影响。
        </n-alert>
        <n-input
          v-model:value="resetPassword"
          type="password"
          show-password-on="click"
          placeholder="请输入配对密码"
          :disabled="resetting"
          @keyup.enter="submitReset"
        />
      </n-space>
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
import { ref, reactive, computed, onMounted, onUnmounted } from 'vue';
import { useMessage } from 'naive-ui';
import { getApiClient } from '@/composables/useApi';
import GridCard from '@/components/GridCard.vue';
import ToggleRow from '@/components/ToggleRow.vue';
import { ConfigLimits, Endpoints, describeConfigReject, type ConfigRejectedField } from '@/api/contract';
import { buildChangedPayload, commitConfigSave, findUnbaselinedKeys } from '@/views/settings/settingsShared';

const message = useMessage();
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
  // 裸 goform 命令通道开关，管 POST /api/device/goform/query 与 POST /api/device/goform/set
  // （core 侧 rejectIfCommandDisabled，关着时两个端点都回 403）。
  // 默认 false 与 core 一致，而且必须是 false：set 绕过 profile 的 WriteSpec 值域校验、
  // 返回值也不脱敏，默认打开等于把「无校验写设备 + 明文回读」长期挂在网上。
  goformCommandEnabled: false,
  // 字段归一化总开关，core 侧默认 **true**
  // （AppSettings.fieldNormalizationEnabled 的 getter：prefs.getBoolean(KEY, true)）。
  // 默认值必须与 core 一致：这里写 false 会让「还没读到配置」时显示成关闭 —— 假状态。
  // 关掉它是排障后门（读侧原样透传设备字段），而且**改完要重启后台服务**才生效。
  fieldNormalizationEnabled: true,
  smsCodeEnabled: false,
  smsCodeCleanupHours: 24,
  updateSourceMode: 'auto',
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
      goform_command_enabled: 'goformCommandEnabled',
      field_normalization_enabled: 'fieldNormalizationEnabled',
      sms_code_enabled: 'smsCodeEnabled',
      sms_code_cleanup_hours: 'smsCodeCleanupHours',
      update_source_mode: 'updateSourceMode',
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
 *
 * **三个排障开关刻意不在这里**（goform_dump_enabled / goform_command_enabled /
 * field_normalization_enabled）：
 * 它们改走即时保存（见 saveProbeSwitch）。留在这张表里会被差量提交再处理一遍 ——
 * 即时保存已经把 generalOriginal 同步成新值，差量比较虽然不会重复 PUT，
 * 但一旦只同步了 form 没同步 original（或反过来）就会双写；而且 hasUnsavedChanges
 * 会把「刚刚已经生效的开关」算成未保存改动，让 onVisible 的回读被永久跳过。
 * 它们的读映射仍保留在 loadGeneralConfig 里：开关必须显示设备真实状态，
 * 且 probeSwitchDisabled 依赖 `apiKey in generalOriginal` 判断基线在不在。
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
  smsCodeEnabled: 'sms_code_enabled',
  smsCodeCleanupHours: 'sms_code_cleanup_hours',
  // 下载方式是 web 唯一会写的更新相关字段。
  // update_url / update_mirror_base 刻意不在这里：2026-09-22 起它们是 core 的实现细节
  // （清单地址 + 自定义前缀覆盖），web 只读不写，写了就等于又多出一个决策方。
  updateSourceMode: 'update_source_mode',
};

function hasUnsavedChanges(): boolean {
  return Object.keys(buildChangedPayload(GENERAL_FORM_KEYS, generalForm, generalOriginal)).length > 0;
}

// ── 更新配置：只给下载方式三档，不暴露地址 ──
//
// 2026-09-22 决策下沉 core（docs/update-source-core-plan.md）：唯一真源是 core 的
// `update_source_mode`，web 只读写它。`update_url` / `update_mirror_base` 都是实现细节，
// 做成输入框只会让人填错，而填错要等到去「更新」页点检查更新时才以 502 的形式暴露。
// 「用不用镜像」「用哪个镜像」「失败怎么降级」全部由 core 的 MirrorResolver 决定。

type UpdateMode = 'auto' | 'mirror' | 'direct';

const UPDATE_MODE_OPTIONS = [
  { label: '自动（按设备出口地区）', value: 'auto' },
  { label: '镜像加速（中国大陆推荐）', value: 'mirror' },
  { label: '直连 GitHub', value: 'direct' },
];

/** 下拉当前值。`auto` 现在是 core 的持久模式，可以正常显示，不再是「瞬时动作」。 */
const updateMode = computed<UpdateMode>(() => generalForm.updateSourceMode as UpdateMode);

function onUpdateModeChange(mode: UpdateMode) {
  generalForm.updateSourceMode = mode;
  // 选了自动但还没测过地区：顺手测一次，让用户立刻看到判定依据。
  // 测不出来也不改模式 —— core 侧「地区未知」按直连走，且会在检查更新时自己重试。
  if (mode === 'auto' && !geoCountry.value) detectGeo(true);
}

// ── 设备出口地区：core 的 GeoDetector 探出网 IP 归属，core 的 auto 判定用的就是它 ──

const geoCountry = ref('');
const detectingGeo = ref(false);

const geoText = computed(() => {
  if (!geoCountry.value) return '未检测';
  return geoCountry.value === 'CN' ? '中国大陆（CN）' : geoCountry.value;
});

/**
 * 读取/重测出口地区。
 * @param force true = 强制重测（POST detect，三源全挂回 502）；false = 只读 core 的缓存快照
 * @returns 国家码；空串 = 没测出来（**不等于海外**）
 */
async function detectGeo(force: boolean): Promise<string> {
  detectingGeo.value = true;
  try {
    const { data } = force ? await api.post(Endpoints.geo.detect) : await api.get(Endpoints.geo.root);
    // country 为空串 = 从未探测成功，此时 source 恒为 unknown
    geoCountry.value = String(data?.country || '').trim();
    if (force) {
      if (geoCountry.value) message.success(`已检测到出口地区：${geoText.value}`);
      else message.warning('三个地理源都不可达，未能测出地区');
    }
    return geoCountry.value;
  } catch {
    if (force) message.error('地区检测失败，设备可能不可达');
    return '';
  } finally {
    detectingGeo.value = false;
  }
}

// ── 三个排障开关：单键即时保存（生效时机各自不同，见 PROBE_NEEDS_RESTART）──

type ProbeApiKey = 'goform_dump_enabled' | 'goform_command_enabled' | 'field_normalization_enabled';

/** 排障开关的 api 键 → 表单字段。回滚与置位都要按键找回表单字段。 */
const PROBE_FORM_KEY: Record<ProbeApiKey, 'goformDumpEnabled' | 'goformCommandEnabled' | 'fieldNormalizationEnabled'> =
  {
    goform_dump_enabled: 'goformDumpEnabled',
    goform_command_enabled: 'goformCommandEnabled',
    field_normalization_enabled: 'fieldNormalizationEnabled',
  };

/**
 * 改完要不要重启后台服务。
 *
 * 为什么写死在前端：`PUT /api/config` 的 `needs_restart` 只覆盖
 * port / goform_ip / goform_port / goform_password（ConfigRoutes.kt:264），
 * `field_normalization_enabled` **不在**那张清单里，所以靠响应判断不出来。
 *
 * 为什么它确实需要重启：core 只在构造组件图时读一次这个开关
 * （ComponentFactory.resolveDeviceProfile()），之后 profile 被固化进各设备客户端；
 * 前两个键是每次请求现读 prefs，所以是即时的。别把三个键当同一种生效语义。
 */
const PROBE_NEEDS_RESTART: Record<ProbeApiKey, boolean> = {
  goform_dump_enabled: false,
  goform_command_enabled: false,
  field_normalization_enabled: true,
};

/** 各自独立的 loading：三个开关互不相干，共用一个会让点 A 时 B 也转圈且被禁用。 */
const probeSaving = reactive<Record<ProbeApiKey, boolean>>({
  goform_dump_enabled: false,
  goform_command_enabled: false,
  field_normalization_enabled: false,
});

const PROBE_DUMP_DESC =
  '控制 GET /api/device/goform（设备原始字段 dump，不走 profile 归一化也不脱敏），关着时该端点回 403。' +
  '改动立即生效，不需要点下方「保存」。';
const PROBE_COMMAND_DESC =
  '控制 POST /api/device/goform/query 与 POST /api/device/goform/set，关着时两个端点都回 403。' +
  '危险：set 会绕过 profile 的所有值域校验直接写设备，两个端点的返回值也都不脱敏（真密码、真 IMEI）。' +
  '改动立即生效，不需要点下方「保存」；只在排障时临时打开，看完立刻关回去。';
const PROBE_FIELD_NORMALIZATION_DESC =
  '开=按设备 profile 的登记表把设备字段归一化成统一字段名（默认，正常使用就该开着）；' +
  '关=读侧原样透传设备原始字段，仅排障用（此时信号数据会变空、字段名对比页没有登记表可比对）。' +
  '改完需重启后台服务才生效：配置值立刻就能读回来，但 core 只在构造组件图时读一次这个开关，' +
  '不重启的话归一化行为不会变。';

/**
 * 禁用时把**原因**说出来，而不是只灰着。
 *
 * 为什么必须说：这三个键比其它字段新，最常见的情形就是「设备上的 core 比 web 旧、
 * GET /api/config 里没有这个键」。只灰掉的话用户只会以为界面坏了或权限不够，
 * 于是去翻别的开关；写明「当前 core 版本不支持」他才知道该去升 core。
 * 这也顺带补上了移出 GENERAL_FORM_KEYS 后 findUnbaselinedKeys 不再覆盖这三键的告知职责。
 */
function probeDescription(apiKey: ProbeApiKey, base: string): string {
  if (!generalLoaded.value) return `${base}（配置尚未从设备读取，暂不可修改）`;
  if (!(apiKey in generalOriginal)) return `${base}（当前 core 版本不支持此项，已禁用）`;
  return base;
}

/**
 * 三个排障开关走「即时保存」而不是表单式差量提交。
 *
 * 为什么与本卡其它字段不一样：① 全仓页面里的 ToggleRow 都是即时语义
 *    （ControlTab 的 LED/Samba、AlertConfigPanel 全部…），表单式只用在弹窗表单里；
 *    放一个表单式开关在这里，用户拖完不点保存就走 = 假开关（刷新回默认、全程零反馈）。
 * ② 它们是**排障动作**不是配置项：打开的动机就是「现在就要看 dump / 现在就要发裸命令 /
 *    现在就要看设备原始字段名」，用完立刻关回去，不该跟 IP/端口/密码一起攒着提交。
 *
 * 注意「即时保存」≠「即时生效」：`field_normalization_enabled` 的写入是即时的
 * （回读立刻能拿到新值），但归一化行为要重启后台服务才变 —— 成功提示按
 * PROBE_NEEDS_RESTART 分两种文案，不能一律说「立即生效」。
 *
 * 写法照 ControlTab.postToggle：只在**确认生效后**才置位（ToggleRow 是完全受控组件，
 * 父组件不回写它就不动，所以失败路径写回原值即回滚），期间 :loading 挡住重复点。
 * 与 postToggle 的区别是这里不整卡回读 —— loadGeneralConfig 会把用户正在编辑的
 * IP / 端口 / 密码一起冲掉，所以只同步这一个键的 form 与 original。
 */
async function saveProbeSwitch(apiKey: ProbeApiKey, value: boolean) {
  if (probeSaving[apiKey]) return;
  const formKey = PROBE_FORM_KEY[apiKey];
  const previous = generalForm[formKey];
  probeSaving[apiKey] = true;
  try {
    const { data } = await api.put('/api/config', { [apiKey]: value });
    const updated: string[] = data?.updated_fields || [];
    if (!updated.includes(apiKey)) {
      // 没进 updated_fields 就是没生效（老 core 没有 rejected_fields，只能这样推断），
      // 此时 UI 必须回到原值，否则开关显示的是一个设备并不认的状态。
      generalForm[formKey] = previous;
      const rejected: ConfigRejectedField[] = data?.rejected_fields || [];
      const detail = rejected.find((r) => r.field === apiKey);
      message.error(detail ? `未生效 —— ${describeConfigReject(detail)}` : `${apiKey} 未生效，设备未接受此项改动`);
      return;
    }
    // form 与 original 两处都要更新：original 不更新的话 hasUnsavedChanges 会一直是 true，
    // onVisible 的回读被永久跳过（手机端改了也看不到）。
    generalForm[formKey] = value;
    generalOriginal[apiKey] = value;
    const verb = value ? '已开启' : '已关闭';
    // 需重启的那一项用 warning 而不是 success：说「保存成功」会让用户以为行为已经变了。
    if (PROBE_NEEDS_RESTART[apiKey]) message.warning(`${verb}，需重启后台服务才会生效`);
    else message.success(`${verb}，立即生效`);
  } catch (e: any) {
    generalForm[formKey] = previous;
    message.error(e?.response?.data?.error || '保存失败，开关已回滚');
  } finally {
    probeSaving[apiKey] = false;
  }
}

/**
 * 三个排障开关（`goform_dump_enabled` / `goform_command_enabled` /
 * `field_normalization_enabled`）的禁用判定。
 *
 * 为什么只有它们要多一层判断：它们是最近才加进 core 的键，最容易撞上「设备上的 core 比
 * web 旧、GET /api/config 里根本没这个键」。此时 generalOriginal 缺基线 —— 开关拖得动、
 * PUT 上去也不会进 updated_fields，即典型的假开关。而这几个偏偏是排障入口，
 * 用户正指望靠它们看设备真实状态，显示成可用最误导人，所以直接禁用，
 * 并由 probeDescription 说明禁用原因。
 *
 * 这条判定**不能为了「看起来可用」而放宽**：旧 core 上禁用是正确行为，不是 bug。
 *
 * 其余字段不做这个处理：它们都是老键，且保存时的 findUnbaselinedKeys 提示已经够用。
 */
function probeSwitchDisabled(apiKey: ProbeApiKey): boolean {
  return !generalLoaded.value || !(apiKey in generalOriginal);
}

async function saveGeneral() {
  if (!generalLoaded.value) {
    message.warning('配置尚未从设备读取，保存已阻止');
    return;
  }
  // 无条件提示（不看用户改没改过）：缺基线的控件显示的是前端默认值而不是设备真实状态，
  // 哪怕没动过，「界面上有这个开关、设备却不认它」本身就得说出来。
  const unbaselined = findUnbaselinedKeys(GENERAL_FORM_KEYS, generalOriginal);
  if (unbaselined.length > 0) {
    message.warning(`以下配置项当前 core 版本不支持，已忽略：${unbaselined.join('、')}`);
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

/**
 * 恢复默认配置。
 *
 * core 侧 `/api/config/reset` 从 2026-09-10 起要求配对密码：此前它只要求「已配对身份」，
 * 而它会清掉配置里的一切 —— 一台借出去用过、配对记录还没删的旧手机就能远程触发。
 * 所以这里不能再用 dialog.warning 一键确认，必须收一次密码。
 */
const resetModalOpen = ref(false);
const resetPassword = ref('');
const resetting = ref(false);

function resetConfig() {
  resetPassword.value = '';
  resetModalOpen.value = true;
}

async function submitReset() {
  if (!resetPassword.value) {
    message.warning('请输入配对密码');
    return;
  }
  resetting.value = true;
  try {
    const { data } = await api.post('/api/config/reset', { password: resetPassword.value });
    message.warning(data.message || '配置已恢复默认，需重启服务生效');
    resetModalOpen.value = false;
    resetPassword.value = '';
    // 只刷新本分栏：QoS / 版本信息等其余分栏此刻并未挂载，
    // 下次打开它们时会各自 onMounted 重新拉取，无需在这里代劳。
    loadGeneralConfig();
  } catch (e: any) {
    message.error(e?.response?.data?.error || '恢复失败');
  } finally {
    resetting.value = false;
  }
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
  // 只读缓存快照，不强制出网重测：进设置页不该为了显示一行地区去打三个地理源
  detectGeo(false);
  document.addEventListener('visibilitychange', onVisible);
});

onUnmounted(() => {
  document.removeEventListener('visibilitychange', onVisible);
});
</script>

<style scoped>
/* .settings-panel 栅格与断点、.config-grid/.config-item/.config-label/.switch-item 版式、
   .section-subtitle、.card-actions 均已统一到 src/styles/main.css（全局各一份） */

/* ── Config layout ── */
.config-section {
  margin-bottom: 4px;
}
.config-section:last-child {
  margin-bottom: 0;
}
</style>
