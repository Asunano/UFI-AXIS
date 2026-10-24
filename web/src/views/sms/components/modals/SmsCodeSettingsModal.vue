<!--
  验证码设置（2026-10-14）。

  为什么从「设置 → 通用」搬到短信页：`sms_code_enabled` 与 `sms_code_cleanup_hours` 只影响
  本页「验证码」这个 tab 的提取与缓存清理，住在通用设置里没有任何人会去找 —— 它们既不是
  全局项，也不影响别的页面。与「拦截规则」同一形态：设置不进 tab 清单（tab 是内容维度），
  入口挂在页面头部动作区，点开一个弹窗。

  写盘走 `/api/config`，但**不自己拼 PUT 的 body**：必须复用 `settingsShared` 的三个函数。
  2026-09-04 的事故就是 GET /api/config 失败时表单停在硬编码默认值上、一点保存把这些
  **从没读到过的值**全量 PUT 上去，把手机端刚改的开关又顶了回去（见
  `settingsShared.buildChangedPayload` 的注释）。所以这里的规矩同样两条：
    · 没读到设备配置 → 所有控件与保存全部禁用；
    · 只提交「值变了、且在设备端读到过基线」的键（差量 + 无基线不提交）。

  与 app 侧的对应实现（`SmsSettingsScreen.kt`）：两项的语义逐字对齐 ——
  `sms_code_cleanup_hours` 的 `0` 表示**永不清理**，而不是"立刻清理"。
-->
<template>
  <n-modal v-model:show="show" preset="card" title="验证码设置" class="modal-fit" style="--modal-w: 460px">
    <n-spin :show="loading">
      <ToggleRow
        label="启用验证码提取"
        description="自动从新收到的短信里识别验证码，在「验证码」中查看；识别完全在设备本地进行"
        :model-value="form.smsCodeEnabled"
        :disabled="fieldDisabled('sms_code_enabled')"
        @update:model-value="(v: boolean) => (form.smsCodeEnabled = v)"
      />

      <div class="field">
        <span class="field-label">清理周期 (小时)</span>
        <n-input-number
          v-model:value="form.smsCodeCleanupHours"
          :min="HOURS_MIN"
          :max="HOURS_MAX"
          :disabled="fieldDisabled('sms_code_cleanup_hours')"
          size="small"
        />
        <span class="hint-text">{{ CLEANUP_HINT }}</span>
      </div>

      <!-- 读不到基线时把原因说出来，而不是留一个拖得动、保存时被吞掉的控件 -->
      <div v-if="!configLoaded" class="hint-text">
        设备配置尚未读到，暂不可修改（避免把前端默认值写回设备）；可关闭弹窗重开再试。
      </div>
      <div v-else-if="unbaselinedLabels.length > 0" class="hint-text">
        当前设备端版本不支持：{{ unbaselinedLabels.join('、') }}，该项不会被保存。
      </div>
    </n-spin>

    <template #footer>
      <div class="modal-footer">
        <n-button size="small" @click="show = false">关闭</n-button>
        <n-button size="small" type="primary" :loading="saving" :disabled="!configLoaded" @click="save">保存</n-button>
      </div>
    </template>
  </n-modal>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted } from 'vue';
import { useMessage } from 'naive-ui';
import { useCancellableApi } from '@/composables/useCancellableApi';
import { ConfigLimits, Endpoints } from '@/api/contract';
import { buildChangedPayload, commitConfigSave, findUnbaselinedKeys } from '@/views/settings/settingsShared';
import ToggleRow from '@/components/ToggleRow.vue';

const props = defineProps<{ show: boolean }>();
const emit = defineEmits<{ 'update:show': [boolean] }>();

const show = computed({
  get: () => props.show,
  set: (v) => emit('update:show', v),
});

const message = useMessage();
const api = useCancellableApi();

/**
 * 表单字段 ↔ core 配置键。表提到函数外：保存时的差量、加载时的基线记录、
 * 以及「哪些键没读到」三处都要用它，写在 save() 里就得抄三份。
 */
const CONFIG_KEYS: Record<string, string> = {
  smsCodeEnabled: 'sms_code_enabled',
  smsCodeCleanupHours: 'sms_code_cleanup_hours',
};

/**
 * 上面那张表的 apiKey → 界面用语。
 *
 * 为什么必须有一张映射：`findUnbaselinedKeys` 返回的是 **core 的英文配置键**
 * （`sms_code_cleanup_hours`），直接 `join('、')` 上屏等于让用户读设备日志。
 * 就近维护在消费它的文件里（本目录只有一个消费方）；core 加键时这里会漏一个，
 * 所以下面留了「未知键显示原名」的兜底而不是静默丢弃。
 */
const CONFIG_KEY_LABELS: Record<string, string> = {
  sms_code_enabled: '启用验证码提取',
  sms_code_cleanup_hours: '清理周期（小时）',
};

/**
 * 取值范围**只从 [ConfigLimits] 取**（core 侧 `ConfigLimits.SMS_CODE_CLEANUP_HOURS`，
 * 0..720）：这里另写一份数字，core 改区间时界面就会放行注定被 400 拒掉的值。
 */
const HOURS_MIN = ConfigLimits.smsCodeCleanupHours[0];
const HOURS_MAX = ConfigLimits.smsCodeCleanupHours[1];

/** 与 core `AppSettings.smsCodeCleanupHours` 的默认值一致；只在读到配置之前短暂出现。 */
const DEFAULT_CLEANUP_HOURS = 24;

const CLEANUP_HINT = `可填 ${HOURS_MIN} ~ ${HOURS_MAX} 小时：超过该时长的验证码缓存会被自动删除，填 ${HOURS_MIN} 表示永不清理。`;

const loading = ref(false);
const saving = ref(false);
/** 是否读到过设备配置。false 时全部控件与保存禁用 —— 见文件头那次事故。 */
const configLoaded = ref(false);
/** core 回读的原始配置，差量提交与「有没有基线」都以它为准。 */
const original = reactive<Record<string, any>>({});

const form = reactive({
  smsCodeEnabled: false,
  // 数字输入框允许被清空（null）。清空时不能静默改写成某个数字 —— 那等于替用户改了他的设置，
  // 所以放宽类型、由 save() 的校验拦下并说明区间。
  smsCodeCleanupHours: DEFAULT_CLEANUP_HOURS as number | null,
});

/**
 * 设备端没有回、因而不可能被保存的键（老版本 core 不认这一项）。
 * 用 settingsShared 的实现而不是就地写一遍：那段注释里的坑（没基线的键提交上去
 * 会被静默吞掉，控件变成空转开关）在哪个页面都成立。
 */
const unbaselined = computed(() => findUnbaselinedKeys(CONFIG_KEYS, original));

/** 上屏用：中文名优先，认不出的键至少把原名显示出来（不静默丢项）。 */
const unbaselinedLabels = computed(() => unbaselined.value.map((key) => CONFIG_KEY_LABELS[key] ?? key));

/**
 * 单个键的禁用判定：没读到配置、或这个键压根没基线时禁用。
 * 后者是「设备端版本比 web 旧」的形态 —— 开关拖得动、PUT 上去也不会进 `updated_fields`，
 * 显示成可用最误导人（同 GeneralPanel 的 probeSwitchDisabled）。
 */
function fieldDisabled(apiKey: string): boolean {
  return !configLoaded.value || !(apiKey in original) || saving.value;
}

async function loadConfig() {
  loading.value = true;
  try {
    const { data } = await api.get(Endpoints.config.root);
    // data 为空 = 请求被取消（弹窗已卸载）或 core 没回体。两种情况都按「没读到」处理：
    // 表单绝不能停在默认值上还能保存（那正是 2026-09-04 那次事故的形态）。
    if (!data) return;
    for (const [formKey, apiKey] of Object.entries(CONFIG_KEYS)) {
      const v = data[apiKey];
      if (v !== undefined) {
        (form as any)[formKey] = v;
        original[apiKey] = v;
      }
    }
    configLoaded.value = true;
  } catch (e: any) {
    // 这个弹窗是用户主动打开的，读不到就说出来；但**只禁用控件，绝不落一份默认值下去**。
    configLoaded.value = false;
    message.error(e?.response?.data?.error || '读取设备配置失败，验证码设置暂不可修改');
  } finally {
    loading.value = false;
  }
}

async function save() {
  if (!configLoaded.value) {
    message.warning('设备配置尚未读取，保存已阻止');
    return;
  }
  const hours = form.smsCodeCleanupHours;
  if (hours === null || !Number.isInteger(hours) || hours < HOURS_MIN || hours > HOURS_MAX) {
    message.error(`清理周期需为 ${HOURS_MIN} ~ ${HOURS_MAX} 之间的整数（${HOURS_MIN} 表示永不清理）`);
    return;
  }
  const payload = buildChangedPayload(CONFIG_KEYS, form, original);
  if (Object.keys(payload).length === 0) {
    message.info('没有更改');
    return;
  }
  saving.value = true;
  try {
    const { data } = await api.put(Endpoints.config.root, payload);
    // 请求被取消（弹窗已关闭）：既不该报「部分未生效」，也不必再提示成功
    if (data?.__canceled) return;
    commitConfigSave(payload, data, original, '验证码设置', message, loadConfig);
  } catch (e: any) {
    message.error(e?.response?.data?.error || '保存失败');
  } finally {
    saving.value = false;
  }
}

// 弹窗由 useLazyModal 懒挂载（关闭即卸载），所以挂载一次 = 打开一次，在这里取数即可
onMounted(loadConfig);
</script>

<style scoped>
/* `.modal-footer` / `.field` 已上收到 src/styles/main.css（计划 §8.2 C2）。
   只留一条差异覆盖：本弹窗的 `.field-label` 用 `--font-sm`（12px），
   与同弹窗的 `.hint-text` 同档 —— 全局那份是 13px（`--font-base`）。
   scoped 选择器是 (0,2,0)，天然压过全局的 (0,1,0)。 */
.hint-text {
  font-size: var(--font-sm);
  color: var(--text-muted);
  line-height: 1.5;
}
.field-label {
  font-size: var(--font-sm);
}
</style>
