<template>
  <!--
    2026-10-14 搬迁（日志域「各回各家」）：日志与排障的**开关**从「通用」搬到这里，
    与原有的「调试日志」查看/清空合成一栏。

    为什么搬：这七项本来就是日志域的东西，此前被拆在「通用」与「日志」两个面板里 ——
    要开详细日志得先想到去「通用」找，而「日志」面板里只有日志列表。同一个域拆在两处，
    正是这次搬迁要修的问题。

    本面板两块，**保存语义刻意不同，不要合并**：
      ① 日志开关（log_enabled / core_log_enabled / app_log_enabled / debug_mode）：
         四个开关互相有依赖（总开关关掉时后三个 :disabled），需要一个「改完一起提交」
         的时机，所以是表单 + 显式「保存」按钮（差量提交，见 saveLogSwitches）。
      ② 排障开关（goform_dump_enabled / goform_command_enabled / field_normalization_enabled）：
         改动立即生效（见 saveProbeSwitch）—— 它们是排障动作，打开的动机就是「现在就要看」，
         用完立刻关回去，不该跟别的配置攒着提交。三者各自需要说明位（端点名 + 危险警示 +
         生效时机），所以用 ToggleRow 而不是两列栅格里的 .switch-item。
  -->
  <div class="settings-panel">
    <!-- Card: 日志开关（表单式四个 + 即时生效的排障三个） -->
    <GridCard title="日志开关">
      <div class="config-section">
        <div v-if="!configLoaded" class="section-subtitle">配置未从设备读取，开关已禁用（避免把默认值写回设备）</div>
        <div class="config-grid">
          <div class="config-item switch-item">
            <span class="config-label">日志总开关</span>
            <n-switch v-model:value="logForm.logEnabled" :disabled="!configLoaded" />
          </div>
          <div class="config-item switch-item">
            <span class="config-label">后端日志</span>
            <n-switch v-model:value="logForm.coreLogEnabled" :disabled="!configLoaded || !logForm.logEnabled" />
          </div>
          <div class="config-item switch-item">
            <span class="config-label">手机端日志</span>
            <n-switch v-model:value="logForm.appLogEnabled" :disabled="!configLoaded || !logForm.logEnabled" />
          </div>
          <div class="config-item switch-item">
            <span class="config-label">详细日志（Debug）</span>
            <n-switch v-model:value="logForm.debugMode" :disabled="!configLoaded || !logForm.logEnabled" />
          </div>
        </div>
      </div>
      <div class="config-section">
        <div class="section-subtitle">排障开关（改动立即生效，无需点击下方「保存」）</div>
        <ToggleRow
          label="设备原始字段 dump（排障用，默认关，改动立即生效）"
          :description="probeDescription('goform_dump_enabled', PROBE_DUMP_DESC)"
          :model-value="logForm.goformDumpEnabled"
          :loading="probeSaving.goform_dump_enabled"
          :disabled="probeSwitchDisabled('goform_dump_enabled')"
          @update:model-value="(v: boolean) => saveProbeSwitch('goform_dump_enabled', v)"
        />
        <ToggleRow
          label="裸 goform 命令通道（排障用，默认关，改动立即生效）"
          :description="probeDescription('goform_command_enabled', PROBE_COMMAND_DESC)"
          :model-value="logForm.goformCommandEnabled"
          :loading="probeSaving.goform_command_enabled"
          :disabled="probeSwitchDisabled('goform_command_enabled')"
          @update:model-value="(v: boolean) => saveProbeSwitch('goform_command_enabled', v)"
        />
        <!--
          2026-10-10 暂时隐藏 ttyd 开关：整套真 PTY 功能尚未完工，先从设置里撤下。
          **只藏 UI，配置 key `ttyd_enabled` 与 core 侧读写逻辑全部保留** ——
          这样恢复时只需放开这一个 ToggleRow，不用重新对齐 core 的字段名。

          2026-10-14 随搬迁说明：ttyd 不是日志开关，但它和上面三个共用同一套即时保存机制
          （saveProbeSwitch / probeSaving / PROBE_NEEDS_RESTART），机制搬到本面板，它就跟着来 ——
          否则要在「通用」里留第二份 saveProbeSwitch。真要做完 PTY 功能时，更合适的落点是终端页，
          那属于另一次搬迁，此处不做决定。
        -->
        <!-- <ToggleRow
          label="真 PTY 终端（ttyd，默认关，改动立即生效）"
          :description="probeDescription('ttyd_enabled', PROBE_TTYD_DESC)"
          :model-value="logForm.ttydEnabled"
          :loading="probeSaving.ttyd_enabled"
          :disabled="probeSwitchDisabled('ttyd_enabled')"
          @update:model-value="(v: boolean) => saveProbeSwitch('ttyd_enabled', v)"
        /> -->
        <ToggleRow
          label="字段归一化（默认开，改完需重启后台服务）"
          :description="probeDescription('field_normalization_enabled', PROBE_FIELD_NORMALIZATION_DESC)"
          :model-value="logForm.fieldNormalizationEnabled"
          :loading="probeSaving.field_normalization_enabled"
          :disabled="probeSwitchDisabled('field_normalization_enabled')"
          @update:model-value="(v: boolean) => saveProbeSwitch('field_normalization_enabled', v)"
        />
      </div>
      <!-- 只有四个日志开关走这个按钮：排障三个是即时保存，上面的小标题已写明 -->
      <div class="card-actions">
        <n-button
          type="primary"
          size="small"
          :loading="savingLogSwitches"
          :disabled="!configLoaded"
          @click="saveLogSwitches"
          >保存</n-button
        >
      </div>
    </GridCard>

    <!-- Card: 调试日志（取数 / 轮询 / 清理逻辑与搬迁前逐字一致，未改动） -->
    <GridCard title="调试日志">
      <template #extra>
        <div class="log-header-actions">
          <n-switch v-model:value="autoRefreshLogs" size="small" style="--n-width: 60px">
            <template #checked>自动</template>
            <template #unchecked>手动</template>
          </n-switch>
          <n-button size="tiny" quaternary @click="loadDebugLogs">刷新</n-button>
          <n-button size="tiny" type="error" quaternary @click="clearLogs">清空</n-button>
        </div>
      </template>
      <div class="log-toolbar">
        <n-select
          v-model:value="logLevelFilter"
          :options="logLevelOptions"
          size="small"
          style="width: 140px"
          @update:value="onLogLevelChange"
        />
        <span class="log-count">共 {{ logTotal }} 条</span>
      </div>
      <div class="log-list">
        <!--
          三态（计划 §3.6）：读取失败**不能**显示成「暂无日志」—— 那是把「没读到」当成「没有」。
          失败时保留上次读到的内容并说明，同时给重试入口；只有真的读到 0 条才说「暂无日志」。
        -->
        <div v-if="logError" class="log-error">
          <span>读取失败{{ displayLogs.length > 0 ? '，下面是上次读到的内容' : '' }}</span>
          <n-button text type="primary" size="tiny" :loading="logsLoading" @click="loadDebugLogs">重试</n-button>
        </div>
        <div v-else-if="logsLoading && displayLogs.length === 0" class="client-empty">正在读取…</div>
        <div v-else-if="displayLogs.length === 0" class="client-empty">暂无日志</div>
        <div v-for="(log, i) in displayLogs" :key="i" class="log-entry">
          <span class="log-level" :class="'level-' + log.level.toLowerCase()">{{ log.level }}</span>
          <span class="log-tag">{{ log.tag }}</span>
          <span class="log-message">{{ log.message }}</span>
          <span class="log-time">{{ log.timeStr }}</span>
        </div>
      </div>
    </GridCard>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted, onUnmounted, watch } from 'vue';
import { useInterval } from '@/composables/useRealtime';
import { useMessage, useDialog } from 'naive-ui';
import { useCancellableApi } from '@/composables/useCancellableApi';
import { getApiClient } from '@/composables/useApi';
import GridCard from '@/components/GridCard.vue';
import ToggleRow from '@/components/ToggleRow.vue';
import { describeConfigReject, type ConfigRejectedField } from '@/api/contract';
import { buildChangedPayload, commitConfigSave, findUnbaselinedKeys } from '@/views/settings/settingsShared';

const message = useMessage();
const dialog = useDialog();
const api = useCancellableApi();
/**
 * `/api/config` 的读写走**非取消版**客户端，与上面那个 3s 轮询用的 `useCancellableApi` 分开。
 *
 * 为什么不能共用：取消版把「面板关闭导致的中断」resolve 成 `data === undefined`。
 * 轮询无所谓（下一次就补上），但配置写入的**回滚与提示全靠响应**
 * （updated_fields / rejected_fields）—— 一次取消会被读成「设备没接受这项改动」，
 * 于是关面板的瞬间弹出一个假的「未生效，开关已回滚」。读取同理：取消会被当成读取失败。
 * 这也是搬迁前「通用」面板的取数方式（那边用的就是 getApiClient）。
 */
const configApi = getApiClient();

// ══════════════════════════════════════════════
//  日志开关（表单式：四个开关互相有依赖，需要一个「改完一起提交」的时机）
// ══════════════════════════════════════════════

const savingLogSwitches = ref(false);
/** 是否已经从 core 读到过配置。false 时表单值只是硬编码初值，禁止编辑与保存。 */
const configLoaded = ref(false);
const configOriginal = reactive<Record<string, any>>({});

const logForm = reactive({
  // 这些初值只在「还没读到设备配置」时短暂出现，且此时开关与保存都是禁用的（见 configLoaded）。
  // 日志总闸与 core 的 DEFAULT_LOG_ENABLED 一致：默认关；后端日志与手机端日志默认开。
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
  // 真 PTY 终端（ttyd）的开关值。UI 暂时隐藏（见模板里那段注释），但值必须继续回读 ——
  // 它与上面三个共用同一套即时保存机制，且 probeSwitchDisabled 靠基线判断能不能点。
  ttydEnabled: false,
});

async function loadLogConfig() {
  try {
    const { data } = await configApi.get('/api/config');
    const mapping: Record<string, string> = {
      log_enabled: 'logEnabled',
      core_log_enabled: 'coreLogEnabled',
      app_log_enabled: 'appLogEnabled',
      debug_mode: 'debugMode',
      goform_dump_enabled: 'goformDumpEnabled',
      goform_command_enabled: 'goformCommandEnabled',
      field_normalization_enabled: 'fieldNormalizationEnabled',
      // 2026-10-07 修复（随搬迁保留）：ttyd_enabled 此前漏在这张基线表外，original 里没有它的键，
      // probeDescription 一律判成「当前 core 版本不支持此项」并禁用——core 其实支持。
      ttyd_enabled: 'ttydEnabled',
    };
    for (const [apiKey, formKey] of Object.entries(mapping)) {
      if (data[apiKey] !== undefined) {
        (logForm as any)[formKey] = data[apiKey];
        configOriginal[apiKey] = data[apiKey];
      }
    }
    configLoaded.value = true;
  } catch {
    // 2026-09-04（随开关从「通用」搬来，理由一字未改）：这里原来是 silent catch，表单于是停在
    // 硬编码初值上（日志开关全 true）却看起来像设备的真实状态 —— 用户在手机端刚关掉的开关，
    // 在 web 上显示为开启，一点保存就把它又打开了。现在明确标记「未加载」：开关与保存按钮全部禁用。
    configLoaded.value = false;
    message.warning('设备配置读取失败，日志开关暂不可编辑');
  }
}

/**
 * 表单字段 ↔ core 配置键。提到函数外是因为「有没有未保存改动」的判断也要用它
 * （见 [hasUnsavedChanges]），放在 saveLogSwitches 里就得抄第二份。
 *
 * **三个排障开关刻意不在这里**（goform_dump_enabled / goform_command_enabled /
 * field_normalization_enabled）：
 * 它们改走即时保存（见 saveProbeSwitch）。留在这张表里会被差量提交再处理一遍 ——
 * 即时保存已经把 configOriginal 同步成新值，差量比较虽然不会重复 PUT，
 * 但一旦只同步了 form 没同步 original（或反过来）就会双写；而且 hasUnsavedChanges
 * 会把「刚刚已经生效的开关」算成未保存改动，让 onVisible 的回读被永久跳过。
 * 它们的读映射仍保留在 loadLogConfig 里：开关必须显示设备真实状态，
 * 且 probeSwitchDisabled 依赖 `apiKey in configOriginal` 判断基线在不在。
 */
const LOG_FORM_KEYS: Record<string, string> = {
  logEnabled: 'log_enabled',
  coreLogEnabled: 'core_log_enabled',
  appLogEnabled: 'app_log_enabled',
  debugMode: 'debug_mode',
};

function hasUnsavedChanges(): boolean {
  return Object.keys(buildChangedPayload(LOG_FORM_KEYS, logForm, configOriginal)).length > 0;
}

async function saveLogSwitches() {
  if (!configLoaded.value) {
    message.warning('配置尚未从设备读取，保存已阻止');
    return;
  }
  // 无条件提示（不看用户改没改过）：缺基线的控件显示的是前端默认值而不是设备真实状态，
  // 哪怕没动过，「界面上有这个开关、设备却不认它」本身就得说出来。
  const unbaselined = findUnbaselinedKeys(LOG_FORM_KEYS, configOriginal);
  if (unbaselined.length > 0) {
    message.warning(`以下配置项当前 core 版本不支持，已忽略：${unbaselined.join('、')}`);
  }
  const payload = buildChangedPayload(LOG_FORM_KEYS, logForm, configOriginal);
  if (Object.keys(payload).length === 0) {
    message.info('没有更改');
    return;
  }
  savingLogSwitches.value = true;
  try {
    const { data } = await configApi.put('/api/config', payload);
    commitConfigSave(payload, data, configOriginal, '日志开关', message, loadLogConfig);
  } catch (e: any) {
    message.error(e?.response?.data?.error || '保存失败');
  } finally {
    savingLogSwitches.value = false;
  }
}

// ══════════════════════════════════════════════
//  排障开关：单键即时保存（生效时机各自不同，见 PROBE_NEEDS_RESTART）
// ══════════════════════════════════════════════

type ProbeApiKey = 'goform_dump_enabled' | 'goform_command_enabled' | 'ttyd_enabled' | 'field_normalization_enabled';

/** 排障开关的 api 键 → 表单字段。回滚与置位都要按键找回表单字段。 */
const PROBE_FORM_KEY: Record<
  ProbeApiKey,
  'goformDumpEnabled' | 'goformCommandEnabled' | 'ttydEnabled' | 'fieldNormalizationEnabled'
> = {
  goform_dump_enabled: 'goformDumpEnabled',
  goform_command_enabled: 'goformCommandEnabled',
  ttyd_enabled: 'ttydEnabled',
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
 * 前两个键是每次请求现读 prefs，所以是即时的。别把几个键当同一种生效语义。
 */
const PROBE_NEEDS_RESTART: Record<ProbeApiKey, boolean> = {
  goform_dump_enabled: false,
  goform_command_enabled: false,
  ttyd_enabled: false,
  field_normalization_enabled: true,
};

/** 各自独立的 loading：几个开关互不相干，共用一个会让点 A 时 B 也转圈且被禁用。 */
const probeSaving = reactive<Record<ProbeApiKey, boolean>>({
  goform_dump_enabled: false,
  goform_command_enabled: false,
  ttyd_enabled: false,
  field_normalization_enabled: false,
});

const PROBE_DUMP_DESC =
  '控制 GET /api/device/goform（设备原始字段 dump，不走 profile 归一化也不脱敏），关着时该端点回 403。' +
  '改动立即生效，无需点击下方「保存」。';
// 2026-10-10 随上面的 ttyd 开关 ToggleRow 一并隐藏（整套功能未完工，2026-10 再做）。
// 同样用 `void` 占位而非删除：TS 严格模式报 TS6133，且保留它能让"恢复 = 放开 ToggleRow
// 并删掉 void"成为一处改动，描述文案不必重新誊写（改文案的历史很容易丢）。
const PROBE_TTYD_DESC =
  '打开后终端页出现「真 PTY」标签页：由设备上的 ttyd 提供 root 环境真实 shell（会话 30 分钟滑动过期）。' +
  '默认关闭：该功能授予浏览器侧完整的 shell 执行能力，仅在排障时开启。';
void PROBE_TTYD_DESC;

const PROBE_COMMAND_DESC =
  '控制 POST /api/device/goform/query 与 POST /api/device/goform/set，关着时两个端点都回 403。' +
  '危险：set 会绕过 profile 的所有值域校验直接写设备，两个端点的返回值也都不脱敏（真密码、真 IMEI）。' +
  '改动立即生效，无需点击下方「保存」；仅在排障时临时启用，完成后立即关闭。';
const PROBE_FIELD_NORMALIZATION_DESC =
  '开=按设备 profile 的登记表把设备字段归一化成统一字段名（默认，正常使用就该开着）；' +
  '关=读侧原样透传设备原始字段，仅排障用。已实测的副作用：流量限额整块会变成默认值' +
  '（显示未开启限额、限额为 0），因为限额里有三个字段是 core 从复合串拆出来的派生键，' +
  '设备上本来不存在；字段覆盖率页也没有登记表可比对。仪表盘 / 网络 / WiFi 与信号数据不受影响。' +
  '改完需重启后台服务才生效：配置值立刻就能读回来，但 core 只在构造组件图时读一次这个开关，' +
  '不重启的话归一化行为不会变。';

/**
 * 禁用时把**原因**说出来，而不是只灰着。
 *
 * 为什么必须说：这几个键比其它字段新，最常见的情形就是「设备上的 core 比 web 旧、
 * GET /api/config 里没有这个键」。只灰掉的话用户只会以为界面坏了或权限不够，
 * 于是去翻别的开关；写明「当前 core 版本不支持」他才知道该去升 core。
 * 这也顺带补上了移出 LOG_FORM_KEYS 后 findUnbaselinedKeys 不再覆盖这几键的告知职责。
 */
function probeDescription(apiKey: ProbeApiKey, base: string): string {
  if (!configLoaded.value) return `${base}（配置尚未从设备读取，暂不可修改）`;
  if (!(apiKey in configOriginal)) return `${base}（当前 core 版本不支持此项，已禁用）`;
  return base;
}

/**
 * 几个排障开关走「即时保存」而不是表单式差量提交。
 *
 * 为什么与上面那四个日志开关不一样：① 全仓页面里的 ToggleRow 都是即时语义
 *    （DeviceControlModal 的 LED/Samba、AlertConfigPanel 全部…），表单式只用在弹窗表单里；
 *    放一个表单式开关在这里，用户拖完不点保存就走 = 假开关（刷新回默认、全程零反馈）。
 * ② 它们是**排障动作**不是配置项：打开的动机就是「现在就要看 dump / 现在就要发裸命令 /
 *    现在就要看设备原始字段名」，用完立刻关回去，不该跟日志级别一起攒着提交。
 *
 * 注意「即时保存」≠「即时生效」：`field_normalization_enabled` 的写入是即时的
 * （回读立刻能拿到新值），但归一化行为要重启后台服务才变 —— 成功提示按
 * PROBE_NEEDS_RESTART 分两种文案，不能一律说「立即生效」。
 *
 * 写法照 DeviceControlModal.postToggle：只在**确认生效后**才置位（ToggleRow 是完全受控组件，
 * 父组件不回写它就不动，所以失败路径写回原值即回滚），期间 :loading 挡住重复点。
 * 与 postToggle 的区别是这里不整卡回读 —— loadLogConfig 会把用户正在编辑的日志开关一起冲掉，
 * 所以只同步这一个键的 form 与 original。
 */
async function saveProbeSwitch(apiKey: ProbeApiKey, value: boolean) {
  if (probeSaving[apiKey]) return;
  const formKey = PROBE_FORM_KEY[apiKey];
  const previous = logForm[formKey];
  probeSaving[apiKey] = true;
  try {
    const { data } = await configApi.put('/api/config', { [apiKey]: value });
    const updated: string[] = data?.updated_fields || [];
    if (!updated.includes(apiKey)) {
      // 没进 updated_fields 就是没生效（老 core 没有 rejected_fields，只能这样推断），
      // 此时 UI 必须回到原值，否则开关显示的是一个设备并不认的状态。
      logForm[formKey] = previous;
      const rejected: ConfigRejectedField[] = data?.rejected_fields || [];
      const detail = rejected.find((r) => r.field === apiKey);
      message.error(detail ? `未生效 —— ${describeConfigReject(detail)}` : `${apiKey} 未生效，设备未接受此项改动`);
      return;
    }
    // form 与 original 两处都要更新：original 不更新的话 hasUnsavedChanges 会一直是 true，
    // onVisible 的回读被永久跳过（手机端改了也看不到）。
    logForm[formKey] = value;
    configOriginal[apiKey] = value;
    const verb = value ? '已开启' : '已关闭';
    // 需重启的那一项用 warning 而不是 success：说「保存成功」会让用户以为行为已经变了。
    if (PROBE_NEEDS_RESTART[apiKey]) message.warning(`${verb}，需重启后台服务才会生效`);
    else message.success(`${verb}，立即生效`);
  } catch (e: any) {
    logForm[formKey] = previous;
    message.error(e?.response?.data?.error || '保存失败，开关已回滚');
  } finally {
    probeSaving[apiKey] = false;
  }
}

/**
 * 排障开关（`goform_dump_enabled` / `goform_command_enabled` /
 * `field_normalization_enabled`）的禁用判定。
 *
 * 为什么只有它们要多一层判断：它们是最近才加进 core 的键，最容易撞上「设备上的 core 比
 * web 旧、GET /api/config 里根本没这个键」。此时 configOriginal 缺基线 —— 开关拖得动、
 * PUT 上去也不会进 updated_fields，即典型的假开关。而这几个偏偏是排障入口，
 * 用户正指望靠它们看设备真实状态，显示成可用最误导人，所以直接禁用，
 * 并由 probeDescription 说明禁用原因。
 *
 * 这条判定**不能为了「看起来可用」而放宽**：旧 core 上禁用是正确行为，不是 bug。
 *
 * 其余字段不做这个处理：它们都是老键，且保存时的 findUnbaselinedKeys 提示已经够用。
 */
function probeSwitchDisabled(apiKey: ProbeApiKey): boolean {
  return !configLoaded.value || !(apiKey in configOriginal);
}

// ── 调试日志 ──
const logLevelFilter = ref('ALL');
const logLevelOptions = [
  { label: '全部 (ALL)', value: 'ALL' },
  { label: 'INFO', value: 'INFO' },
  { label: 'DEBUG', value: 'DEBUG' },
  { label: 'WARN', value: 'WARN' },
  { label: 'ERROR', value: 'ERROR' },
];
const logs = ref<any[]>([]);
const logTotal = ref(0);
/** 读取失败标记（计划 §3.6）：与「读到 0 条」是两件事，不能都渲染成「暂无日志」 */
const logError = ref(false);
const logsLoading = ref(false);
const autoRefreshLogs = ref(false);
// 调试日志自动刷新；由 watch(autoRefreshLogs) 开关
const logPoll = useInterval(() => loadDebugLogs(), 3000, { autoStart: false });

const displayLogs = computed(() => [...logs.value].reverse().map(parseLogLine));

// core 的 /api/debug-logs 返回的是字符串数组，格式 "HH:mm:ss.SSS [LEVEL] [TAG] message"，
// 不是对象数组 —— 旧代码读 log.level.toLowerCase() 会直接抛异常把整张卡片渲染中断
const LOG_LINE_RE = /^(\d{2}:\d{2}:\d{2}(?:\.\d+)?)\s+\[([A-Z]+)\]\s+\[([^\]]*)\]\s*([\s\S]*)$/;

function parseLogLine(raw: any): { level: string; tag: string; message: string; timeStr: string } {
  const line = typeof raw === 'string' ? raw : String(raw?.message ?? raw ?? '');
  const m = LOG_LINE_RE.exec(line);
  if (!m) return { level: 'INFO', tag: '', message: line, timeStr: '' };
  return { timeStr: m[1] || '', level: m[2] || 'INFO', tag: m[3] || '', message: m[4] || '' };
}

async function loadDebugLogs() {
  logsLoading.value = true;
  try {
    const params: Record<string, any> = { limit: 200 };
    if (logLevelFilter.value !== 'ALL') params.level = logLevelFilter.value;
    const { data } = await api.get('/api/debug-logs', { params });
    // 取消版客户端把「面板卸载导致的中断」resolve 成 data === undefined（见 useCancellableApi），
    // 那不是读取失败，别把空态改成错误态
    if (!data) return;
    logs.value = data.logs || [];
    logTotal.value = data.total ?? logs.value.length;
    logError.value = false;
  } catch {
    // 原先是 silent catch：请求失败时列表停在旧内容（首屏则是空的）却显示「暂无日志」。
    logError.value = true;
  } finally {
    logsLoading.value = false;
  }
}

// ── Logs ──
function onLogLevelChange() {
  loadDebugLogs();
}

function clearLogs() {
  dialog.warning({
    title: '清空日志',
    content: '确定清空所有调试日志？',
    positiveText: '清空',
    negativeText: '取消',
    onPositiveClick: async () => {
      try {
        await api.delete('/api/debug-logs');
        message.success('日志已清空');
        loadDebugLogs();
      } catch {
        message.error('清空失败');
      }
    },
  });
}

/**
 * 面板重新可见时重新拉一次配置。
 *
 * 2026-10-14 随开关从「通用」搬来，**不是新加的**：那边原来就靠这一段修
 * 「手机端已关闭、web 端却显示开启」—— 这些开关的唯一真源在 core，而手机端 App 随时可以改它们。
 * 开关搬了家，这个修复不能留在原处，否则同一个 bug 会以「日志面板里显示的是旧快照」的形式复发。
 *
 * 有未保存改动时跳过：否则用户改了一半的四个日志开关会被后台状态冲掉。
 */
function onVisible() {
  if (document.visibilityState !== 'visible') return;
  if (hasUnsavedChanges()) return;
  loadLogConfig();
}

// ══════════════════════════════════════════════
//  Lifecycle
// ══════════════════════════════════════════════

watch(autoRefreshLogs, (val) => {
  if (val) logPoll.start();
  else logPoll.stop();
});

onMounted(() => {
  // 面板是弹窗内容，只在被打开时挂载，所以这两次取数不会挤在首屏（见 SettingsView 注释）
  loadLogConfig();
  loadDebugLogs();
  document.addEventListener('visibilitychange', onVisible);
});

onUnmounted(() => {
  // 原 SettingsView 只清了 serviceRestartTimer，没有显式停这个 3s 轮询。
  // 显式 stop 保证「开着自动刷新时切走分栏」不会留下后台请求。
  logPoll.stop();
  document.removeEventListener('visibilitychange', onVisible);
});
</script>

<style scoped>
/* .settings-panel 栅格与断点已统一到 src/styles/main.css（全局，8 个面板共用一份） */

/* ── Config layout ──
   .config-grid / .config-item / .config-label / .switch-item / .section-subtitle / .card-actions
   都在 main.css 全局各一份；这里只留卡片内二级分区的间距（与「通用」那份同一取值）。 */
.config-section {
  margin-bottom: 4px;
}
.config-section:last-child {
  margin-bottom: 0;
}

/* ── Debug logs ── */
.log-header-actions {
  display: flex;
  gap: 6px;
  align-items: center;
}
.log-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 10px;
}
.log-count {
  font-size: 12px;
  color: var(--text-muted);
}
.log-list {
  max-height: 400px;
  overflow-y: auto;
  border: 1px solid var(--border-subtle);
  border-radius: 8px;
  background: var(--surface-elevated);
}
.log-entry {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  padding: 5px 10px;
  font-family: 'Cascadia Code', 'Fira Code', 'JetBrains Mono', monospace;
  font-size: 12px;
  line-height: 1.5;
  border-bottom: 1px solid var(--border-subtle);
}
.log-entry:last-child {
  border-bottom: none;
}
.log-entry:hover {
  background: var(--surface-hover);
}
.log-level {
  flex-shrink: 0;
  font-weight: 600;
  min-width: 48px;
  text-align: center;
  padding: 0 4px;
  border-radius: 3px;
  font-size: 11px;
  line-height: 1.7;
}
.level-info {
  color: var(--accent-color);
  background: var(--accent-color-light);
}
/* debug 这档是这组语义色里唯一的「中性」成员，走 A1 同波次新增的 `--neutral-light` /
   `--neutral-light-bg`（2026-10-21，计划 §6.1）：`--cat-neutral-*` 是不透明色块
   （当字色用会跟正文抢亮度）、`--surface-hover` 又太淡（当字色等于没有），
   两者都代替不了这一对。两档取值与原写死值逐字相同。 */
.level-debug {
  color: var(--neutral-light);
  background: var(--neutral-light-bg);
}
.level-warn {
  color: var(--warning);
  background: var(--warning-light);
}
.level-error {
  color: var(--error);
  background: var(--error-light);
}
.log-tag {
  flex-shrink: 0;
  color: var(--text-secondary);
  min-width: 60px;
  font-weight: 500;
}
.log-message {
  flex: 1;
  color: var(--text-primary);
  word-break: break-all;
  white-space: pre-wrap;
}
.log-time {
  flex-shrink: 0;
  color: var(--text-muted);
  font-size: 11px;
}

/* 读取失败的提示条：列表区自己的失败态（不是空态），点击「重试」重新拉 */
.log-error {
  display: flex;
  align-items: center;
  justify-content: center;
  flex-wrap: wrap;
  gap: var(--space-2);
  padding: var(--space-3) var(--space-2);
  font-size: var(--font-base);
  color: var(--error);
}

@media (max-width: 768px) {
  /* 一条日志 4 段：级别(48) + 标签(60) + 时间(66) + 3×8 间距 + 20 内距 = 218px 固定占用，
     正文在 298px 的卡内只剩 ~80px ⇒ 6~7 字一行，一条堆栈能撑成几十行。
     窄屏改成两行：第一行是「级别 · 标签 · 时间」的元信息，第二行是整宽正文。 */
  .log-entry {
    flex-wrap: wrap;
    gap: 4px 8px;
  }
  .log-time {
    /* 时间跟在级别与标签后面，与它们同处第一行 */
    order: 1;
    margin-left: auto;
  }
  .log-message {
    /* flex-basis:100% 强制换行到第二行并占满整宽 */
    order: 2;
    flex: 1 0 100%;
  }
  .log-tag {
    /* 60px 下限在窄屏没必要，标签自己有多宽就多宽 */
    min-width: 0;
  }
  /* 400px 的内滚区在 640px 高的手机上几乎占满剩余屏，形成页面+列表双层滚动，
     触屏滑动容易被内层吃掉。降到 60vh 让外层页面仍有可抓的边。 */
  .log-list {
    max-height: 60vh;
  }
}
</style>
