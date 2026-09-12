<!--
  通知分栏面板。

  这里原本是独立的「短信转发」页面（src/views/smsforward/SmsForwardView.vue），
  现已并入设置页的「通知」分栏，作为通知设置的第一块内容。

  2026-08-29：功能改名「邮件通知」，通道只剩 SMTP —— curl 回调与钉钉机器人连同
  `method` 字段一并从 core 删除。同时新增「邮件转发范围」（`scenes`）：
  勾中的通知场景决定这条渠道投不投；不决定"哪些场景会送到这条渠道"，那由 core 侧
  各触发源的渠道口径决定。

  后端路径**没有跟着改名**（仍是 `/api/sms-forward/*`，`contract.ts` 里的
  `smsForward` 常量同样保留）：API 手册与 app 端都按该路径引用，改名只会破坏跨端契约。

  2026-09-09：本分栏从「只有邮件」扩成**三条渠道的清单** —— 邮件、Webhook、本机短信。
  三条渠道在设备端各存自己的配置、各有 test 端点，这里每条一张摘要卡；
  Webhook 与本机短信的详细配置收进弹窗（`../components/modals/`），
  与 GuardSettingsModal / SmsFilterModal 同一条理由：设置类不与内容维度平铺。
  2026-09-10：排版重做。原来是四张平级卡（邮件 / 配置体检 / Webhook / 本机短信），
  在 1650px 宽的视口上被 `.settings-panel` 摊成 4 列、每列 ~390px，顶部对齐、底边参差，
  下方一整片空白。三个问题各有各的根因：
    · 列宽：全局栅格的列宽下限太小 → 已在 main.css 提到 420px；
      （2026-09-10 二次改：main.css 的栅格改为**固定 2 列 + 撤掉 max-width**
       —— 固定列数是为了保住各面板的等高配对，撤上限是因为 1918px 视口下它会在
       右侧留 519px 空白。本卡是 `:only-child`，两种规则下都通栏，结果不变。）
    · 底边参差：三条渠道内容行数不同，横排必然不齐 → 压成**一张卡里的纵向清单**；
    · 层级错配：「配置体检」只体检邮件这一条渠道，却被摆成第四条渠道 →
      降级进邮件行的展开区。
  2026-09-10：每一行加「最近投递」入口（`DeliveryHistoryModal`，三行共用一个实例，按 channel 区分）。
  投递记录与产生它的那套配置放在一起才找得到，所以入口落在渠道行上而不是单开一张卡。
  2026-09-10（规则同构）：core 把三条渠道的规则做成同构 —— 每条都有「最低级别」+「每日上限」。
  于是这里做了两件事：
    · 邮件的展开区补上那两个旋钮，与另两条渠道**共用** `../components/ChannelRulesFields.vue`；
    · 三行的摘要项统一成同一组、同一顺序（目标 → 最低级别 → 今日用量 → 场景），
      清单本身要能横向对比，而不是每行各说各的。
  同时新增「严重事件兜底」开关（`NotificationConfig.critical_override_enabled`，**默认开启**）。
  它是**跨渠道**的一条规则，所以摆在清单上方而不是塞进某一条渠道 —— 塞进去就得写三份开关，
  而三份改的是同一个字段。
-->
<template>
  <div class="settings-panel">
    <GridCard class="full-width" title="通知渠道">
      <template #extra>
        <n-button size="small" quaternary :loading="anyLoading" @click="reloadAll">刷新</n-button>
      </template>

      <!--
        严重事件兜底：它是**跨三条渠道**的一条规则（真源在 core `NotificationConfig`），
        所以摆在渠道清单上方，而不是塞进某一条渠道的配置里 —— 塞进去就得写三份，
        而三份开关改的是同一个字段。
      -->
      <div class="sub-panel override-panel">
        <ToggleRow
          :model-value="criticalOverride"
          :loading="criticalOverrideSaving"
          :label="CRITICAL_OVERRIDE_LABEL"
          :description="CRITICAL_OVERRIDE_DESC"
          @update:model-value="saveCriticalOverride"
        />
        <span class="row-hint">{{ CRITICAL_OVERRIDE_PENETRATES }}</span>
        <span class="row-hint">{{ CRITICAL_OVERRIDE_BLOCKED }}</span>
      </div>

      <!--
        邮件是这页唯一带长表单的渠道，所以它的配置放在行内展开区，
        另两条走弹窗（设置类不与内容维度平铺，同 GuardSettingsModal / SmsFilterModal）。
      -->
      <ChannelRow
        title="邮件通知"
        :status="mailStatus"
        :lines="mailLines"
        note="关闭后 core 既不转发短信，也不再发送通知场景邮件。"
        :expanded="mailOpen"
      >
        <template #actions>
          <n-button size="small" @click="mailOpen = !mailOpen">{{ mailOpen ? '收起' : '配置' }}</n-button>
          <n-button size="small" :disabled="!config.enabled" :loading="testing" @click="runTest">测试</n-button>
          <n-button size="small" quaternary @click="openHistory(DeliveryChannel.MAIL)">{{
            HISTORY_ENTRY_LABEL
          }}</n-button>
        </template>

        <template #expanded>
          <n-spin :show="loading">
            <ToggleRow
              v-model="form.enabled"
              label="启用邮件通知"
              description="关闭后 core 既不转发短信，也不再发送通知场景邮件"
            />

            <template v-if="form.enabled">
              <div class="grid">
                <div class="field">
                  <span class="field-label">SMTP 服务器</span>
                  <n-input v-model:value="form.smtp_host" size="small" placeholder="smtp.example.com" />
                </div>
                <div class="field">
                  <span class="field-label">端口</span>
                  <n-input-number v-model:value="form.smtp_port" :min="1" :max="65535" size="small" />
                </div>
                <div class="field">
                  <span class="field-label">用户名</span>
                  <n-input v-model:value="form.smtp_user" size="small" placeholder="you@example.com" />
                </div>
                <div class="field">
                  <span class="field-label">{{ config.smtp_pass_set ? '密码（已设置，留空不修改）' : '密码' }}</span>
                  <n-input
                    v-model:value="form.smtp_pass"
                    type="password"
                    show-password-on="click"
                    size="small"
                    placeholder="留空则保留原密码"
                  />
                </div>
                <div class="field">
                  <span class="field-label">发件地址</span>
                  <n-input v-model:value="form.smtp_from" size="small" placeholder="留空则使用用户名" />
                </div>
                <div class="field">
                  <span class="field-label">收件地址</span>
                  <n-input v-model:value="form.smtp_to" size="small" placeholder="to@example.com" />
                </div>
              </div>

              <!--
                最低级别 + 每日上限 + 今日用量：三条渠道共用的同一个组件。
                邮件的区间是 0..500，**0 = 不限**（core 的默认值就是不限，
                这次改造不改变存量用户观察到的行为）。
              -->
              <ChannelRulesFields
                v-model:min-level="form.min_level"
                v-model:daily-limit="form.daily_limit"
                :levels="config.levels"
                :config-loaded="mailLoaded && !mailFailed"
                :daily-limit-min="dailyLimitMin"
                :daily-limit-max="dailyLimitMax"
                :sent-today="config.sent_today"
                :quota-remaining="config.quota_remaining"
              />

              <div class="field">
                <span class="field-label">邮件转发范围</span>
                <n-checkbox-group v-model:value="form.scenes">
                  <n-space :size="[14, 6]">
                    <n-checkbox v-for="s in MAIL_SCENES" :key="s.id" :value="s.id" :label="s.label" />
                  </n-space>
                </n-checkbox-group>
                <span class="row-hint">勾选的通知会发一封邮件；短信正文始终转发，无需勾选</span>
                <span class="row-hint">{{ SCENE_NOTE_BATTERY }}</span>
                <!-- 场景勾选旁边必须说这一句：否则「严重事件照样发」就是一条藏起来的规则。
                     邮件的免打扰开关是 NotificationConfig 的 mail_respect_dnd（在告警页），
                     不在这张表单里，所以这句话落在场景下方。 -->
                <span class="row-hint">{{ CRITICAL_OVERRIDE_NOTE }}</span>
              </div>

              <ToggleRow
                v-model="form.forward_dev_info"
                label="附加设备信息"
                description="邮件正文里附带设备名与信号等信息"
              />

              <!--
                短信拦截（号码黑名单 + 关键词）不在这里：真源是 core 的 `sms_rule` 表，
                入口在短信页「短信管理」卡片头部的「拦截规则」。
                这里不再提交 `blacklist` —— `POST /api/sms-forward/config` 是字段级合并
                （`p["blacklist"] ?: c.blacklist`），未传即保留原值。
              -->
            </template>
          </n-spin>

          <!--
            配置体检：只体检邮件这一条渠道（`/api/sms-forward/diagnose`），
            所以放在邮件行内、而不是与三条渠道平级的第四张卡。
          -->
          <div class="sub-panel">
            <div class="sub-panel-head">
              <span class="field-label">配置体检</span>
              <n-button size="tiny" quaternary :loading="diagLoading" @click="loadDiagnose">重新检查</n-button>
            </div>
            <div v-if="!diagnose" class="row-hint">尚未取到体检结果。</div>
            <div v-else class="diag-grid">
              <div v-for="item in diagRows" :key="item.label" class="diag-item">
                <span class="row-hint">{{ item.label }}</span>
                <n-tag :type="item.ok ? 'success' : 'warning'" size="tiny" :bordered="false">{{ item.text }}</n-tag>
              </div>
            </div>
          </div>

          <!-- 「测试」已经在这一行的操作区里，展开区只留保存，免得同一个动作在一屏里出现两次 -->
          <div class="card-actions">
            <n-button size="small" type="primary" :loading="saving" @click="save">保存配置</n-button>
          </div>
        </template>
      </ChannelRow>

      <ChannelRow
        title="Webhook 通知"
        :status="webhookStatus"
        :meta="webhookPresetName"
        :lines="webhookLines"
        note="按模板把通知发送到指定地址，可对接常见的推送服务。"
        :last-test="webhookTestText"
      >
        <template #actions>
          <n-button size="small" @click="webhookModal.open()">配置</n-button>
          <n-button size="small" :loading="webhookTesting" @click="runWebhookTest">测试</n-button>
          <n-button size="small" quaternary @click="openHistory(DeliveryChannel.WEBHOOK)">
            {{ HISTORY_ENTRY_LABEL }}
          </n-button>
        </template>
      </ChannelRow>

      <ChannelRow
        title="本机短信通知"
        :status="smsStatus"
        :lines="smsLines"
        note="通知由设备的 SIM 卡以短信发出，按条产生短信费用。数据网络中断时这条渠道仍可送达。"
        :last-test="smsTestText"
      >
        <template #actions>
          <n-button size="small" @click="smsModal.open()">配置</n-button>
          <n-button size="small" :loading="smsTesting" @click="confirmSmsTest">测试</n-button>
          <n-button size="small" quaternary @click="openHistory(DeliveryChannel.LOCAL_SMS)">
            {{ HISTORY_ENTRY_LABEL }}
          </n-button>
        </template>
      </ChannelRow>
    </GridCard>

    <!-- 懒加载弹窗：进出场动画与「关掉即卸载」都由 useLazyModal 处理 -->
    <component :is="webhookComponent" v-if="webhookComponent" :show="webhookShow" @update:show="onWebhookShow" />
    <component :is="smsComponent" v-if="smsComponent" :show="smsShow" @update:show="onSmsShow" />
    <!-- 投递记录：三条渠道共用这一个实例，靠 :channel 区分 -->
    <component
      :is="historyComponent"
      v-if="historyComponent"
      :show="historyShow"
      :channel="historyChannel"
      @update:show="historyModal.setShow"
    />
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted } from 'vue';
import { useDialog, useMessage } from 'naive-ui';
import { getApiClient } from '@/composables/useApi';
import { useLazyModal } from '@/composables/useLazyModal';
import {
  CRITICAL_OVERRIDE_DEFAULT,
  DAILY_LIMIT_UNLIMITED,
  DeliveryChannel,
  Endpoints,
  type ChannelRules,
} from '@/api/contract';
import GridCard from '@/components/GridCard.vue';
import ToggleRow from '@/components/ToggleRow.vue';
import ChannelRow from '../components/ChannelRow.vue';
import ChannelRulesFields from '../components/ChannelRulesFields.vue';
import {
  CRITICAL_OVERRIDE_BLOCKED,
  CRITICAL_OVERRIDE_DESC,
  CRITICAL_OVERRIDE_LABEL,
  CRITICAL_OVERRIDE_NOTE,
  CRITICAL_OVERRIDE_PENETRATES,
  MAIL_SCENES,
  SCENE_NOTE_BATTERY,
  levelLabelOf,
  quotaShortText,
} from '../notifyShared';

const message = useMessage();
const dialog = useDialog();
const api = getApiClient();

/**
 * GET /api/sms-forward/config 的响应形状（凭据字段只回 `*_set` 屏蔽位，不回明文）。
 *
 * 同构的那一组规则字段（最低级别 / 每日上限 / 用量 / 取值域）直接继承 [ChannelRules] ——
 * 2026-09-10 起邮件也有这两个旋钮，与 Webhook、本机短信逐字一致。
 */
interface SmsForwardConfig extends ChannelRules {
  enabled: boolean;
  smtp_host: string;
  smtp_port: number;
  smtp_user: string;
  smtp_pass_set: boolean;
  smtp_from: string;
  smtp_to: string;
  forward_dev_info: boolean;
  /** 需要同时发邮件的通知场景 id 白名单（NotifyScene.sceneId） */
  scenes: string[];
}

/**
 * 可勾选的通知场景已提到 `../notifyShared`：Webhook 与本机短信勾的是同一套场景 id，
 * 每个面板各留一份的话，加一个场景必然只改到一处。
 */

/**
 * 兜底的每日上限区间，**与 core 的 `SmsForwardConfig` 默认值逐字一致**（`info` + 0 = 不限，
 * 区间 0..500）：GET 还没回来时界面用它，写错就会出现「界面显示只发严重、设备上其实全发」。
 */
const FALLBACK_LIMIT_MIN = DAILY_LIMIT_UNLIMITED;
const FALLBACK_LIMIT_MAX = 500;
const DEFAULT_MIN_LEVEL = 'info';

function emptyConfig(): SmsForwardConfig {
  return {
    enabled: false,
    smtp_host: '',
    smtp_port: 465,
    smtp_user: '',
    smtp_pass_set: false,
    smtp_from: '',
    smtp_to: '',
    forward_dev_info: false,
    scenes: [],
    min_level: DEFAULT_MIN_LEVEL,
    daily_limit: DAILY_LIMIT_UNLIMITED,
    sent_today: 0,
    // 不限时 core 回 null，本地初值也用 null：0 的含义是"已用尽"，恰好相反
    quota_remaining: null,
    // 取值域一律等 core 回（web 不留第二份级别表），读到之前下拉是禁用的
    levels: [],
    daily_limit_min: FALLBACK_LIMIT_MIN,
    daily_limit_max: FALLBACK_LIMIT_MAX,
  };
}

/** core 回读的原始配置（用于 `*_set` 提示与保存时的差异判断）。 */
const config = ref<SmsForwardConfig>(emptyConfig());

/**
 * 表单草稿。凭据字段（`smtp_pass`）**恒以空串开始**：
 * core 的 POST 把「空串」与「不传」都当作「保留原值」，因此留空即不修改，
 * 也就无法用该端点清空已设置的凭据 —— 只能覆盖成新的非空值。
 */
const form = reactive({
  ...emptyConfig(),
  smtp_pass: '',
  // 数字输入框允许被清空（null）。清空时钳成 0 等于把"不限"悄悄替给用户，
  // 所以这里放宽类型、由 validate 拦下，而不是静默改写他的取值。
  daily_limit: DAILY_LIMIT_UNLIMITED as number | null,
});

const dailyLimitMin = computed(() => config.value.daily_limit_min);
const dailyLimitMax = computed(() => config.value.daily_limit_max);

const loading = ref(false);
const saving = ref(false);
const testing = ref(false);
/** 邮件行的展开区（整份 SMTP 表单 + 配置体检）。默认收起，一眼先看三条渠道的状态 */
const mailOpen = ref(false);
/** 首次回读是否已完成 / 是否失败 —— 决定状态徽标是「读取中 / 读取失败」还是真实状态 */
const mailLoaded = ref(false);
const mailFailed = ref(false);

function applyConfig(data: Partial<SmsForwardConfig>) {
  const merged = { ...emptyConfig(), ...(data || {}) };
  config.value = merged;
  Object.assign(form, merged, { smtp_pass: '' });
}

async function loadConfig() {
  loading.value = true;
  try {
    const { data } = await api.get('/api/sms-forward/config');
    applyConfig(data);
    mailFailed.value = false;
  } catch (e: any) {
    mailFailed.value = true;
    message.error(e?.response?.data?.error || '加载邮件通知配置失败');
  } finally {
    mailLoaded.value = true;
    loading.value = false;
  }
}

/**
 * core 侧对 SMTP 那组字段**没有任何校验**（类型不符会静默回落原值，地址/端口一律不校验），
 * 所以必填项只能在这里拦住，否则用户会得到一个「保存成功但永远发不出去」的配置。
 *
 * `daily_limit` 是例外：core 从 2026-09-10 起会对它回 400。这里仍先拦一次 ——
 * 让用户当场看到区间，而不是提交完再读一条错误。
 */
function validate(): string | null {
  const limit = form.daily_limit;
  if (limit == null || !Number.isInteger(limit) || limit < dailyLimitMin.value || limit > dailyLimitMax.value) {
    return `每日上限需为 ${dailyLimitMin.value} ~ ${dailyLimitMax.value} 之间的整数`;
  }
  if (!form.enabled) return null;
  if (!form.smtp_host.trim()) return '请填写 SMTP 服务器';
  if (!form.smtp_user.trim()) return '请填写 SMTP 用户名';
  if (!form.smtp_to.trim()) return '请填写收件地址';
  if (!config.value.smtp_pass_set && !form.smtp_pass) return '请填写 SMTP 密码';
  if (!Number.isInteger(form.smtp_port) || form.smtp_port < 1 || form.smtp_port > 65535) {
    return 'SMTP 端口需在 1 ~ 65535 之间';
  }
  return null;
}

async function save() {
  const invalid = validate();
  if (invalid) {
    message.error(invalid);
    return;
  }
  const payload: Record<string, any> = {
    enabled: form.enabled,
    smtp_host: form.smtp_host,
    smtp_port: form.smtp_port,
    smtp_user: form.smtp_user,
    smtp_from: form.smtp_from,
    smtp_to: form.smtp_to,
    forward_dev_info: form.forward_dev_info,
    scenes: form.scenes,
    // 规则同构的两个旋钮（2026-09-10）：这也是这个端点唯一会回 400 的两个字段
    min_level: form.min_level,
    daily_limit: Number(form.daily_limit),
  };
  // 空串会被 core 当作「保留原值」，显式不传更贴合语义
  if (form.smtp_pass) payload.smtp_pass = form.smtp_pass;

  saving.value = true;
  try {
    await api.post('/api/sms-forward/config', payload);
    // POST 只回 { success: true }，不回新配置，必须重新 GET 才能刷新 *_set 屏蔽位
    await loadConfig();
    message.success('配置已保存');
  } catch (e: any) {
    // 这个端点从"永不报错"变成了可能 400（daily_limit 越界 / min_level 认不出）。
    // 直接显示 core 写好的中文原因 —— web 不翻译第二份，翻译必然与 core 分叉。
    const res = e?.response;
    const reason = res?.data?.error || res?.data?.message;
    if (res?.status === 400) {
      message.error(reason || '设备拒绝了这份配置，请检查最低级别与每日上限');
    } else {
      message.error(reason || '保存失败');
    }
  } finally {
    saving.value = false;
  }
}

/** 测试用的是 core 已持久化的配置，未保存的改动不会生效。 */
async function runTest() {
  testing.value = true;
  try {
    const { data } = await api.post('/api/sms-forward/test', {});
    if (data?.success) message.success('测试邮件已发送');
    else message.error(data?.error || '测试发送失败');
  } catch (e: any) {
    message.error(e?.response?.data?.error || '测试发送失败');
  } finally {
    testing.value = false;
  }
}

// ── 配置体检 ──
interface SmsForwardDiagnose {
  config_enabled: boolean;
  smtp_host_set: boolean;
  smtp_port: number;
  smtp_user_set: boolean;
  smtp_pass_set: boolean;
  smtp_to_set: boolean;
  /** core 判定的「可发信」：enabled + 服务器/用户名/密码/收件地址齐全 */
  sendable: boolean;
  /** 已勾选的通知场景数 */
  scene_count: number;
}

const diagnose = ref<SmsForwardDiagnose | null>(null);
const diagLoading = ref(false);

const diagRows = computed(() => {
  const d = diagnose.value;
  if (!d) return [];
  return [
    { label: '邮件通知总开关', ok: d.config_enabled, text: d.config_enabled ? '已启用' : '未启用' },
    { label: '可发信', ok: d.sendable, text: d.sendable ? '就绪' : '配置不完整' },
    { label: 'SMTP 服务器', ok: d.smtp_host_set, text: d.smtp_host_set ? '已填写' : '缺失' },
    { label: 'SMTP 端口', ok: d.smtp_port > 0, text: String(d.smtp_port) },
    { label: 'SMTP 用户名', ok: d.smtp_user_set, text: d.smtp_user_set ? '已填写' : '缺失' },
    { label: 'SMTP 密码', ok: d.smtp_pass_set, text: d.smtp_pass_set ? '已设置' : '缺失' },
    { label: '收件地址', ok: d.smtp_to_set, text: d.smtp_to_set ? '已填写' : '缺失' },
    { label: '转发场景', ok: d.scene_count > 0, text: `${d.scene_count} 个` },
  ];
});

async function loadDiagnose() {
  diagLoading.value = true;
  try {
    const { data } = await api.get('/api/sms-forward/diagnose');
    diagnose.value = data;
  } catch (e: any) {
    message.error(e?.response?.data?.error || '体检失败');
  } finally {
    diagLoading.value = false;
  }
}

/**
 * 邮件行的状态徽标。「可发信」这个判定**只认 core 的 `diagnose.sendable`** ——
 * 与 Webhook / 本机短信只认设备端 `configured` 同一条规矩：
 * web 另算一套的话，就会出现「界面说就绪、实际发不出去」。
 * 所以体检结果在挂载时就取一次（一次 GET，和另两条渠道的配置回读同级）。
 */
const mailStatus = computed<{ text: string; type: TagType }>(() => {
  if (mailFailed.value) return { text: '读取失败', type: 'error' };
  if (!mailLoaded.value) return { text: '读取中', type: 'default' };
  if (!config.value.enabled) return { text: '未启用', type: 'default' };
  if (diagnose.value && !diagnose.value.sendable) return { text: '配置不完整', type: 'warning' };
  return { text: '已启用', type: 'success' };
});

/**
 * 三条渠道行的摘要项**顺序与措辞一律相同**（目标 → 最低级别 → 今日用量 → 场景）：
 * 规则同构之后，清单本身也该能横向对比，而不是每行各说各的。
 */
const mailLines = computed(() => [
  { label: 'SMTP', value: config.value.smtp_host || '未填写' },
  { label: '收件地址', value: config.value.smtp_to || '未填写' },
  // 显示名在下发的取值域里查，不在 web 侧翻译（那张表已经与手机端分叉过一次）
  { label: '最低级别', value: levelLabelOf(config.value.levels, config.value.min_level) },
  { label: '今日用量', value: quotaShortText(config.value) },
  {
    label: '转发场景',
    value: config.value.scenes.length ? `已勾选 ${config.value.scenes.length} 个` : '未勾选（仅短信正文转发）',
  },
]);

// ── CRITICAL 兜底（core `NotificationConfig.critical_override_enabled`，默认 true）──

/**
 * 本地初值取 [CRITICAL_OVERRIDE_DEFAULT]（= true），**必须与 core 逐字一致**：
 * 回读失败时界面沿用这个初值，写反就是「UI 显示关着、设备上其实开着」的假开关。
 */
const criticalOverride = ref(CRITICAL_OVERRIDE_DEFAULT);
const criticalOverrideSaving = ref(false);

/** 回读失败静默沿用默认值（口径同告警页的通知设置）：core 是唯一真源，但不为一次网络抖动报错。 */
async function loadCriticalOverride() {
  try {
    const { data } = await api.get(Endpoints.notifications.config);
    if (typeof data?.critical_override_enabled === 'boolean') {
      criticalOverride.value = data.critical_override_enabled;
    }
  } catch {
    /* 静默 */
  }
}

/** 字段级 PATCH + 失败回滚：开关显示与设备真值不一致就是假开关。 */
async function saveCriticalOverride(next: boolean) {
  const before = criticalOverride.value;
  criticalOverride.value = next;
  criticalOverrideSaving.value = true;
  try {
    const { data } = await api.put(Endpoints.notifications.config, { critical_override_enabled: next });
    if (typeof data?.config?.critical_override_enabled === 'boolean') {
      criticalOverride.value = data.config.critical_override_enabled;
    }
  } catch (e: any) {
    criticalOverride.value = before;
    message.error(e?.response?.data?.error || e?.response?.data?.message || '保存严重事件兜底开关失败');
  } finally {
    criticalOverrideSaving.value = false;
  }
}

/** 任一渠道在回读中 —— 只用于卡头刷新按钮的 loading */
const anyLoading = computed(() => loading.value || diagLoading.value || webhookLoading.value || smsLoading.value);

function reloadAll() {
  loadConfig();
  loadDiagnose();
  loadWebhook();
  loadLocalSms();
  loadCriticalOverride();
}

onMounted(reloadAll);

// ══════════════════════════════════════════════════════════════
// Webhook 渠道（摘要卡；详细配置在 WebhookConfigModal 里）
// ══════════════════════════════════════════════════════════════

/**
 * 摘要卡只用得上这几个字段，全量形状在弹窗里。
 * 规则那几项从 [ChannelRules] 里挑 —— 三行显示的是同一组事实，字段名也得是同一份。
 */
interface WebhookSummary
  extends Pick<ChannelRules, 'min_level' | 'daily_limit' | 'sent_today' | 'quota_remaining' | 'levels'> {
  enabled: boolean;
  preset: string;
  url: string;
  scenes: string[];
  configured: boolean;
  presets: { name: string; display_name: string }[];
}

interface WebhookTestSummary {
  success: boolean;
  error?: string;
  status_code?: number | null;
  response_body?: string | null;
}

/** 卡片上响应体只留一句话的长度，完整摘要在弹窗的测试结果区 */
const RESPONSE_BODY_INLINE_CHARS = 60;

const webhook = ref<WebhookSummary | null>(null);
const webhookLoading = ref(false);
const webhookTesting = ref(false);
const webhookFailed = ref(false);
const webhookTest = ref<WebhookTestSummary | null>(null);

type TagType = 'default' | 'success' | 'warning' | 'error';

/**
 * 状态徽标。判据全部来自设备端回的字段（尤其 `configured` —— 那是渠道自己的
 * 「能不能投」判定），web 不另算一套，否则会出现「界面说就绪、实际发不出去」。
 */
const webhookStatus = computed<{ text: string; type: TagType }>(() => {
  if (webhookFailed.value) return { text: '读取失败', type: 'error' };
  const c = webhook.value;
  if (!c) return { text: '读取中', type: 'default' };
  if (!c.enabled) return { text: '未启用', type: 'default' };
  if (!c.url) return { text: '未填地址', type: 'warning' };
  if (!c.configured) return { text: '配置未填完', type: 'warning' };
  return { text: '已启用', type: 'success' };
});

const webhookPresetName = computed(() => {
  const c = webhook.value;
  if (!c) return '';
  return c.presets.find((p) => p.name === c.preset)?.display_name || c.preset;
});

const webhookSceneText = computed(() => {
  const n = webhook.value?.scenes.length ?? 0;
  return n > 0 ? `已勾选 ${n} 个` : '未勾选（仅手动测试会发送）';
});

const webhookLines = computed(() => [
  { label: '目标地址', value: webhook.value?.url || '未填写' },
  {
    label: '最低级别',
    // 显示名取该渠道下发的取值域里那份 label，web 不再翻译第二份
    value: webhook.value ? levelLabelOf(webhook.value.levels, webhook.value.min_level) : '--',
  },
  { label: '今日用量', value: webhook.value ? quotaShortText(webhook.value) : '--' },
  { label: '触发场景', value: webhookSceneText.value },
]);

const webhookTestText = computed(() => {
  const r = webhookTest.value;
  if (!r) return '';
  const status = r.status_code == null ? '未收到响应' : `HTTP ${r.status_code}`;
  const head = r.success ? '已送达' : '未送达';
  const body = (r.response_body || '').slice(0, RESPONSE_BODY_INLINE_CHARS);
  const detail = r.success ? body : r.error || body;
  return detail ? `${head} · ${status} · ${detail}` : `${head} · ${status}`;
});

async function loadWebhook() {
  webhookLoading.value = true;
  try {
    const { data } = await api.get(Endpoints.notify.webhookConfig);
    webhook.value = {
      enabled: !!data?.enabled,
      preset: data?.preset || '',
      url: data?.url || '',
      scenes: data?.scenes ?? [],
      configured: !!data?.configured,
      presets: data?.presets ?? [],
      min_level: data?.min_level || '',
      // 级别取值域也留着：这一行要显示的是 core 下发的显示名
      levels: data?.levels ?? [],
      // 这两个数字**原样收下，不用 `|| 0` 兜**：这条渠道 0 是「不限」的合法哨兵，
      // 把 NaN（字段缺失、类型不符）落到 0 等于把用户设的限额显示成"不限"。
      // 读不出整数时由 [webhookQuotaText] 显示成"--"，而不是在这里编一个数字。
      daily_limit: Number(data?.daily_limit),
      sent_today: Number(data?.sent_today),
      // 不限时 core 回 null，原样留着 —— 换成 0 就把"不限"显示成"已用尽"
      quota_remaining: data?.quota_remaining ?? null,
    };
    webhookFailed.value = false;
  } catch {
    // 卡片自己用「读取失败」徽标表达，不再弹一条 message（进设置页就会撞见）
    webhookFailed.value = true;
  } finally {
    webhookLoading.value = false;
  }
}

/** 测试用设备端已保存的配置；状态码与响应体摘要都要显示，成功也显示。 */
async function runWebhookTest() {
  webhookTesting.value = true;
  try {
    const { data } = await api.post(Endpoints.notify.webhookTest, {});
    webhookTest.value = data;
    if (data?.success) {
      message.success(data?.status_code ? `测试已送达（HTTP ${data.status_code}）` : '测试已送达');
    } else {
      message.error(data?.error || '测试未送达');
    }
  } catch (e: any) {
    message.error(e?.response?.data?.error || '测试请求失败');
  } finally {
    webhookTesting.value = false;
  }
}

// ══════════════════════════════════════════════════════════════
// 本机短信渠道（摘要卡；详细配置在 LocalSmsConfigModal 里）
// ══════════════════════════════════════════════════════════════

interface LocalSmsSummary
  extends Pick<ChannelRules, 'min_level' | 'daily_limit' | 'sent_today' | 'quota_remaining' | 'levels'> {
  enabled: boolean;
  target_number: string;
  scenes: string[];
  configured: boolean;
}

interface LocalSmsTestSummary {
  success: boolean;
  error?: string;
  verdict?: string;
  counted_toward_quota: boolean;
  sent_today: number;
  daily_limit: number;
}

const localSms = ref<LocalSmsSummary | null>(null);
const smsLoading = ref(false);
const smsTesting = ref(false);
const smsFailed = ref(false);
const smsTest = ref<LocalSmsTestSummary | null>(null);

const smsStatus = computed<{ text: string; type: TagType }>(() => {
  if (smsFailed.value) return { text: '读取失败', type: 'error' };
  const c = localSms.value;
  if (!c) return { text: '读取中', type: 'default' };
  if (!c.enabled) return { text: '未启用', type: 'default' };
  if (!c.target_number) return { text: '未填号码', type: 'warning' };
  if (!c.configured) return { text: '配置未填完', type: 'warning' };
  return { text: '已启用', type: 'success' };
});

const smsLevelText = computed(() =>
  // 显示名取该渠道下发的取值域里那份 label，web 不再翻译第二份
  localSms.value ? levelLabelOf(localSms.value.levels, localSms.value.min_level) : '--'
);

const smsSceneText = computed(() => {
  const n = localSms.value?.scenes.length ?? 0;
  return n > 0 ? `已勾选 ${n} 个` : '未勾选（仅手动测试会发送）';
});

const smsLines = computed(() => [
  { label: '接收号码', value: localSms.value?.target_number || '未填写' },
  { label: '最低级别', value: smsLevelText.value },
  { label: '今日用量', value: localSms.value ? quotaShortText(localSms.value) : '--' },
  { label: '触发场景', value: smsSceneText.value },
]);

const smsTestText = computed(() => {
  const r = smsTest.value;
  if (!r) return '';
  const head = r.success ? '已发出' : r.error || '未发出';
  const quota = r.counted_toward_quota ? '已计入今日配额' : '未计入今日配额';
  return `${head} · ${quota} · 今日 ${r.sent_today}/${r.daily_limit}`;
});

async function loadLocalSms() {
  smsLoading.value = true;
  try {
    const { data } = await api.get(Endpoints.notify.smsConfig);
    localSms.value = {
      enabled: !!data?.enabled,
      target_number: data?.target_number || '',
      min_level: data?.min_level || '',
      // 级别取值域也留着：这一行要显示的是 core 下发的显示名
      levels: data?.levels ?? [],
      // 同 Webhook：原样收下，不用 `|| 0` 兜。这条渠道**根本不接受 0**（最少 1 条），
      // 把 NaN 落到 0 会让清单显示成"不限"，恰好是这条花钱的渠道最不该出现的那句话。
      daily_limit: Number(data?.daily_limit),
      scenes: data?.scenes ?? [],
      configured: !!data?.configured,
      sent_today: Number(data?.sent_today),
      // 这条渠道的上限最少 1 条，所以实际不会是 null；仍按契约原样留着不改写
      quota_remaining: data?.quota_remaining ?? null,
    };
    smsFailed.value = false;
  } catch {
    smsFailed.value = true;
  } finally {
    smsLoading.value = false;
  }
}

/** 这一下会真的发出一条短信，所以必须先确认（与弹窗里那颗按钮同一套文案）。 */
function confirmSmsTest() {
  dialog.warning({
    title: '发送测试短信',
    content:
      '设备会立即向接收号码发出一条真实短信，产生一条短信费用，并占用一条今日配额。测试不受总开关、免打扰与场景勾选影响，但仍受最低级别与每日上限约束。',
    positiveText: '发送',
    negativeText: '取消',
    onPositiveClick: () => {
      runSmsTest();
    },
  });
}

async function runSmsTest() {
  smsTesting.value = true;
  try {
    const { data } = await api.post(Endpoints.notify.smsTest, {});
    smsTest.value = data;
    // 用量在这一次调用后就变了，回读一次让「今日 N/M」跟上
    await loadLocalSms();
    if (data?.success) {
      message.success(`测试短信已发出（今日 ${data.sent_today}/${data.daily_limit}）`);
    } else if (data?.verdict === 'PENDING' || data?.verdict === 'NO_RESPONSE') {
      message.warning('无法确认是否已发出，设备不会自动重发，请先查看手机是否收到');
    } else {
      message.error(data?.error || '测试短信未发出');
    }
  } catch (e: any) {
    message.error(e?.response?.data?.error || '测试请求失败');
  } finally {
    smsTesting.value = false;
  }
}

// ── 两个配置弹窗（懒加载）──
const webhookModal = useLazyModal(() => import('../components/modals/WebhookConfigModal.vue'));
const webhookComponent = webhookModal.component;
const webhookShow = webhookModal.show;

const smsModal = useLazyModal(() => import('../components/modals/LocalSmsConfigModal.vue'));
const smsComponent = smsModal.component;
const smsShow = smsModal.show;

/** 关闭弹窗时回读一次：弹窗里改完的配置要反映到卡片摘要上。 */
function onWebhookShow(v: boolean) {
  webhookModal.setShow(v);
  if (!v) loadWebhook();
}

function onSmsShow(v: boolean) {
  smsModal.setShow(v);
  if (!v) loadLocalSms();
}

// ── 投递记录弹窗（懒加载，三条渠道共用一个实例）──

/** 三行的入口文案必须逐字一致：同一件事在三处出现三种说法会让人以为是三个功能。 */
const HISTORY_ENTRY_LABEL = '最近投递';

/**
 * 记录视图**不为三条渠道各写一份**：列表形态、四档筛选、游标翻页完全一样，
 * 差别只有一个 `channel` 查询参数。复制三份的唯一产物是三处会各自跑偏的文案。
 */
const historyModal = useLazyModal(() => import('../components/modals/DeliveryHistoryModal.vue'));
const historyComponent = historyModal.component;
const historyShow = historyModal.show;
const historyChannel = ref<string>(DeliveryChannel.MAIL);

/** 先换 channel 再 open()：弹窗内部按 channel 变化重拉第一页（见该组件的 watch）。 */
function openHistory(channel: string) {
  historyChannel.value = channel;
  historyModal.open();
}
</script>

<style scoped>
/* .settings-panel 栅格与断点已统一到 src/styles/main.css（全局，8 个面板共用一份） */
/* .row-hint 仍在用：字段说明、体检项标签都靠它，不随 ToggleRow 一起退役 */
.row-hint {
  font-size: 12px;
  color: var(--text-muted);
}
.field {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 10px 0;
}
.field-label {
  font-size: 13px;
  color: var(--text-secondary);
}
.grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 0 14px;
}
.diag-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 10px 14px;
}
.diag-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

/* ── 严重事件兜底（渠道清单上方的跨渠道规则，.sub-panel 是全局类）── */
.override-panel {
  display: flex;
  flex-direction: column;
  gap: 4px;
  margin-bottom: 12px;
}

/* ── 配置体检（邮件行展开区内的二级分区，.sub-panel 是全局类）── */
.sub-panel-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 8px;
}

@media (max-width: 768px) {
  .grid,
  .diag-grid {
    grid-template-columns: 1fr;
  }
}
</style>
