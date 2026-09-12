<template>
  <div class="config-panel">
    <!-- 总闸 hero：开启状态一眼可见，而非埋在表单里 -->
    <GridCard class="config-hero" :bordered="true">
      <div class="hero-row">
        <div class="hero-text">
          <div class="hero-title">告警监控</div>
          <div class="hero-desc">监控设备温度、电量、信号与流量，异常时按下方渠道通知你</div>
        </div>
        <div class="hero-switch">
          <n-switch v-model:value="config.enabled" size="large" :rubber-band="false" />
          <span class="hero-state" :class="config.enabled ? 'on' : 'off'">{{
            config.enabled ? '已开启' : '已关闭'
          }}</span>
        </div>
      </div>
    </GridCard>

    <!-- 分类开关（core `AlertConfig.perType`；`AlertEngine.typeEnabled` 判据是 perType[type] === true，缺键 = 关） -->
    <GridCard title="告警分类" :loading="configLoading">
      <section class="cfg-section">
        <header class="cfg-section-head">
          <span class="cfg-section-title">按类型开关</span>
          <span class="cfg-section-hint">切换即时保存；总闸关闭时所有类型都不检测</span>
        </header>
        <div class="notify-grid">
          <ToggleRow
            v-for="item in alertTypeSwitches"
            :key="item.type"
            :label="item.label"
            :description="item.hint"
            :model-value="typeChecked(item)"
            :disabled="!config.enabled || !configLoaded"
            @update:model-value="(v: boolean) => togglePerType(item, v)"
          />
        </div>
      </section>
    </GridCard>

    <!-- 阈值分组 -->
    <GridCard title="告警阈值" :loading="configLoading">
      <template #extra>
        <n-button size="small" type="primary" :loading="saving" @click="saveConfig"> 保存配置 </n-button>
      </template>

      <section class="cfg-section">
        <header class="cfg-section-head">
          <span class="cfg-section-title">设备健康</span>
          <span class="cfg-section-hint">温度与电量越界时触发</span>
        </header>
        <div class="threshold-grid">
          <div class="threshold-item">
            <div class="threshold-label">温度警告 (°C)</div>
            <div class="threshold-hint">超过该温度即提醒</div>
            <n-input-number v-model:value="config.temperatureWarning" :min="0" :max="100" size="small" />
          </div>
          <div class="threshold-item">
            <div class="threshold-label">温度严重 (°C)</div>
            <div class="threshold-hint">高温保护阈值，触发严重告警</div>
            <n-input-number v-model:value="config.temperatureCritical" :min="0" :max="120" size="small" />
          </div>
          <div class="threshold-item">
            <div class="threshold-label">电池警告 (%)</div>
            <div class="threshold-hint">电量低于该值即提醒</div>
            <n-input-number v-model:value="config.batteryWarning" :min="0" :max="100" size="small" />
          </div>
          <div class="threshold-item">
            <div class="threshold-label">电池严重 (%)</div>
            <div class="threshold-hint">电量极低，建议尽快充电</div>
            <n-input-number v-model:value="config.batteryCritical" :min="0" :max="100" size="small" />
          </div>
        </div>
      </section>

      <section class="cfg-section">
        <header class="cfg-section-head">
          <span class="cfg-section-title">网络质量</span>
          <span class="cfg-section-hint">RSRP 信号强度，数值越负信号越差</span>
        </header>
        <div class="threshold-grid">
          <div class="threshold-item">
            <div class="threshold-label">信号警告 (RSRP dBm)</div>
            <div class="threshold-hint">信号弱于该值（越负越差）即提醒</div>
            <n-input-number v-model:value="config.signalWarningRsrp" :min="-200" :max="0" size="small" />
          </div>
          <div class="threshold-item">
            <div class="threshold-label">信号严重 (RSRP dBm)</div>
            <div class="threshold-hint">信号极差，建议调整设备位置</div>
            <n-input-number v-model:value="config.signalCriticalRsrp" :min="-200" :max="0" size="small" />
          </div>
        </div>
      </section>

      <section class="cfg-section">
        <header class="cfg-section-head">
          <span class="cfg-section-title">流量用量</span>
          <span class="cfg-section-hint">达到阈值时提醒，严重级可额外通知</span>
        </header>
        <div class="threshold-grid">
          <div class="threshold-item">
            <div class="threshold-label">流量警告 (MB)</div>
            <div class="threshold-hint">周期内流量达到该值即提醒</div>
            <n-input-number v-model:value="config.trafficWarningMb" :min="0" size="small" />
          </div>
          <div class="threshold-item">
            <div class="threshold-label">流量严重 (MB)</div>
            <div class="threshold-hint">流量接近耗尽</div>
            <n-input-number v-model:value="config.trafficCriticalMb" :min="0" size="small" />
          </div>
        </div>
      </section>
    </GridCard>

    <!-- 通知设置分组（core: /api/notifications/config，与 app 共享同一份真源） -->
    <GridCard title="通知渠道" :loading="notifyLoading">
      <section class="cfg-section">
        <header class="cfg-section-head">
          <span class="cfg-section-title">全局与渠道</span>
          <span class="cfg-section-hint">全局通知关闭后，下面所有类型都不会送达</span>
        </header>
        <div class="notify-grid">
          <ToggleRow
            v-for="item in globalNotify"
            :key="item.field"
            :label="item.label"
            :description="item.hint"
            :model-value="notifyConfig[item.field] as boolean"
            @update:model-value="(v: boolean) => saveNotify({ [item.field]: v })"
          />
        </div>
      </section>

      <section class="cfg-section">
        <header class="cfg-section-head">
          <span class="cfg-section-title">阈值告警通知</span>
        </header>
        <div class="notify-grid">
          <ToggleRow
            v-for="item in thresholdNotify"
            :key="item.field"
            :label="item.label"
            :description="item.hint"
            :model-value="notifyConfig[item.field] as boolean"
            @update:model-value="(v: boolean) => saveNotify({ [item.field]: v })"
          />
        </div>
      </section>

      <section class="cfg-section">
        <header class="cfg-section-head">
          <span class="cfg-section-title">事件通知</span>
          <span class="cfg-section-hint">设备侧发生的离散事件</span>
        </header>
        <div class="notify-grid">
          <ToggleRow
            v-for="item in eventNotify"
            :key="item.field"
            :label="item.label"
            :description="item.hint"
            :model-value="notifyConfig[item.field] as boolean"
            @update:model-value="(v: boolean) => saveNotify({ [item.field]: v })"
          />
        </div>
      </section>

      <section class="cfg-section">
        <header class="cfg-section-head">
          <span class="cfg-section-title">守护与免打扰</span>
        </header>
        <div class="notify-grid">
          <ToggleRow
            v-for="item in guardNotify"
            :key="item.field"
            :label="item.label"
            :description="item.hint"
            :model-value="notifyConfig[item.field] as boolean"
            @update:model-value="(v: boolean) => saveNotify({ [item.field]: v })"
          />
          <ToggleRow
            label="免打扰起止小时"
            description="0-23；起 > 止 表示跨零点（23→7 = 当晚 23:00 至次日 07:00），两者相等则不静默"
          >
            <template #control>
              <div class="hour-range">
                <n-input-number
                  :value="notifyConfig.dnd_start_hour"
                  :min="0"
                  :max="23"
                  size="small"
                  style="width: 84px"
                  @update:value="(v: number | null) => v !== null && saveNotify({ dnd_start_hour: v })"
                />
                <n-input-number
                  :value="notifyConfig.dnd_end_hour"
                  :min="0"
                  :max="23"
                  size="small"
                  style="width: 84px"
                  @update:value="(v: number | null) => v !== null && saveNotify({ dnd_end_hour: v })"
                />
              </div>
            </template>
          </ToggleRow>
          <ToggleRow label="守护轮询间隔" description="15-60 分钟，仅在后台守护开启时生效">
            <template #control>
              <n-input-number
                :value="notifyConfig.guard_interval_minutes"
                :min="15"
                :max="60"
                :step="5"
                size="small"
                style="width: 110px"
                @update:value="(v: number | null) => v && saveNotify({ guard_interval_minutes: v })"
              />
            </template>
          </ToggleRow>
          <ToggleRow label="通知历史保留条数" description="100-5000，同时作用于设备上的邮件记录与 App 的通知记录">
            <template #control>
              <n-input-number
                :value="notifyConfig.history_max_rows"
                :min="100"
                :max="5000"
                :step="100"
                size="small"
                style="width: 110px"
                @update:value="(v: number | null) => v && saveNotify({ history_max_rows: v })"
              />
            </template>
          </ToggleRow>
          <ToggleRow label="通知历史保留天数" description="0-365，填 0 表示不按时间清理；与条数上限先到者生效">
            <template #control>
              <n-input-number
                :value="notifyConfig.history_max_age_days"
                :min="0"
                :max="365"
                size="small"
                style="width: 110px"
                @update:value="(v: number | null) => v !== null && saveNotify({ history_max_age_days: v })"
              />
            </template>
          </ToggleRow>
        </div>
        <div class="notify-note">隧道失败提醒在「内网穿透」页设置（core 字段 notify_on_failure），不在此处重复。</div>
      </section>
    </GridCard>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted, computed } from 'vue';
import { useMessage } from 'naive-ui';
import { getApiClient } from '@/composables/useApi';
import { AlertType, CRITICAL_OVERRIDE_DEFAULT, Endpoints, type AlertTypeKey } from '@/api/contract';
import GridCard from '@/components/GridCard.vue';
import ToggleRow from '@/components/ToggleRow.vue';

const message = useMessage();
const api = getApiClient();

// ── 配置 ──
interface AlertConfig {
  enabled: boolean;
  temperatureWarning: number;
  temperatureCritical: number;
  batteryWarning: number;
  batteryCritical: number;
  trafficWarningMb: number;
  trafficCriticalMb: number;
  signalWarningRsrp: number;
  signalCriticalRsrp: number;
}

const config = reactive<AlertConfig>({
  enabled: false,
  temperatureWarning: 45,
  temperatureCritical: 55,
  batteryWarning: 20,
  batteryCritical: 10,
  trafficWarningMb: 1024,
  trafficCriticalMb: 2048,
  signalWarningRsrp: -100,
  signalCriticalRsrp: -115,
});

const configLoading = ref(false);
const saving = ref(false);

// core 的 PUT /api/alerts/config 用 configVersion 做版本守门（不一致返回 409），
// 且 mergeConfigPatch 只做**顶层键合并**：少传一个键 = 把它写回默认值（少传 perType 会
// 清空别端的分类开关，少传 minIntervalSec 会被重置成 1800）。
// 因此保留完整的原始配置对象，保存时在其上覆盖本页字段，configVersion 原样带回。
const rawConfig = ref<Record<string, any>>({});

// 配置是否真的回读成功。分类开关的 disabled 判据之一 —— 没加载就允许切换，
// 等于拿本地默认值去覆盖 core 的真实配置。
const configLoaded = ref(false);

// ── 分类开关（core `AlertConfig.perType`）──
// `AlertEngine.typeEnabled` 的判据是 `cfg.enabled && cfg.perType[type] === true`，
// **缺键 = 关**，所以本地镜像一律按 `=== true` 归一，不做"缺省视为开"的兜底。
const perType = reactive<Record<string, boolean>>({});

interface AlertTypeSwitch {
  type: AlertTypeKey;
  label: string;
  hint: string;
  /**
   * 第二道取数闸门（core `NotificationConfig` 字段）。`ComponentFactory` 用它决定
   * **要不要向设备取数**，关着时引擎连数据都拿不到 —— 只写 perType 就是个假开关。
   * 与 app `AlertSettingsScreen` 同口径。
   */
  gate?: 'traffic_80_enabled' | 'device_events_enabled';
}

// 用 Record<AlertTypeKey, …> 而不是数组字面量：contract 的 AlertType 增删类型时这里编译不过，
// 避免再次出现"引擎在检测、但没有任何 UI 能打开"的死类型。
const ALERT_TYPE_META: Record<AlertTypeKey, Omit<AlertTypeSwitch, 'type'>> = {
  temperature: { label: '温度告警', hint: '按上方温度警告 / 严重阈值触发' },
  battery: { label: '电量告警', hint: '按上方电池警告 / 严重阈值触发' },
  traffic: { label: '流量告警', hint: '按上方绝对用量阈值（MB）触发' },
  signal: { label: '信号告警', hint: '按上方 RSRP 阈值触发' },
  connectivity: { label: '连接性告警', hint: '断开超过 1 分钟触发，恢复后自动消警（无阈值可调）' },
  traffic_limit: {
    label: '套餐限额预警',
    hint: '按套餐用量百分比提醒（阈值取设备侧设置，默认 80%）；与按绝对 MB 的「流量告警」是两类',
    gate: 'traffic_80_enabled',
  },
  device_online: {
    label: '设备接入提醒',
    hint: '有 WiFi 客户端接入时提醒（core 每 60 秒比对客户端列表）',
    gate: 'device_events_enabled',
  },
  device_offline: {
    label: '设备离开提醒',
    hint: '有 WiFi 客户端离开时提醒（与接入提醒共用同一次取数）',
    gate: 'device_events_enabled',
  },
};

// 展示顺序即契约顺序（AlertType 与 Kotlin `Alerts.Type.ALL` 同序）
const alertTypeSwitches: AlertTypeSwitch[] = AlertType.map((type) => ({ type, ...ALERT_TYPE_META[type] }));

// ── 通知设置（core /api/notifications/config；与 app 共用同一份真源）──
interface NotifyConfig {
  /** L1 全局总闸：管状态栏 + 邮件所有渠道（2026-09-08） */
  master_enabled: boolean;
  /** 邮件是否也遵守免打扰时段（默认不遵守）。邮件开不开在「邮件通知」页 */
  mail_respect_dnd: boolean;
  alert_enabled: boolean;
  connectivity_enabled: boolean;

  sms_enabled: boolean;
  verification_enabled: boolean;
  download_enabled: boolean;
  traffic_80_enabled: boolean;
  device_events_enabled: boolean;
  /** 隧道失败通知：本机是否弹（设备端是否推由隧道设置决定） */
  tunnel_enabled: boolean;
  dnd_enabled: boolean;
  dnd_start_hour: number;
  dnd_end_hour: number;
  guard_enabled: boolean;
  guard_interval_minutes: number;
  guard_foreground_keepalive_enabled: boolean;
  /** 通知历史保留条数：一个值同时管设备端邮件记录与 App 的状态栏通知记录 */
  history_max_rows: number;
  /** 通知历史保留天数（0 = 不按时间清理）：与条数上限先到者生效 */
  history_max_age_days: number;
  /**
   * CRITICAL 兜底（**该结构里唯一默认开启的字段**）。
   *
   * 这里只镜像字段与默认值，**开关不在本页** —— 它是三条投递渠道共用的一条规则，
   * 入口在设置页「通知」分栏（`settings/panels/NotifyPanel.vue`）。两处各放一个开关
   * 改的是同一个字段，只会让人以为那是两件事。
   */
  critical_override_enabled: boolean;
}

type NotifyBoolField = Exclude<
  keyof NotifyConfig,
  'guard_interval_minutes' | 'dnd_start_hour' | 'dnd_end_hour' | 'history_max_rows' | 'history_max_age_days'
>;

interface NotifySwitch {
  field: NotifyBoolField;
  label: string;
  hint: string;
  group: 'global' | 'threshold' | 'event' | 'guard';
}

const NOTIFY_SWITCHES: NotifySwitch[] = [
  {
    field: 'master_enabled',
    label: '全局通知',
    hint: '关闭后不会收到任何通知（App 内的「发送测试通知」仍可使用）',
    group: 'global',
  },
  {
    field: 'mail_respect_dnd',
    label: '邮件遵守免打扰',
    hint: '默认关闭：免打扰时段内仍会发送邮件。是否发送邮件在 App 的「邮件通知」中设置',
    group: 'global',
  },
  {
    field: 'alert_enabled',
    label: '阈值告警通知',
    hint: '温度、电量、流量、信号超出阈值时提醒',
    group: 'threshold',
  },
  {
    field: 'connectivity_enabled',
    label: '设备离线/上线通知',
    hint: '断开超过 1 分钟才提醒，恢复后补一条静默通知',
    group: 'threshold',
  },
  { field: 'traffic_80_enabled', label: '流量 80% 预警', hint: '达到限额 80% 时提醒一次', group: 'threshold' },
  { field: 'sms_enabled', label: '新短信通知', hint: '设备收到短信时提醒', group: 'event' },
  {
    field: 'verification_enabled',
    label: '验证码提取通知',
    hint: '识别短信中的验证码并单独提示，与「新短信通知」互不影响',
    group: 'event',
  },
  { field: 'download_enabled', label: '下载完成/失败通知', hint: '离线下载任务结束时提醒', group: 'event' },
  { field: 'device_events_enabled', label: '设备事件通知', hint: 'WiFi 客户端上下线等，默认关闭', group: 'event' },
  {
    field: 'tunnel_enabled',
    label: '隧道失败通知',
    hint: '隧道启动失败或意外断开时提醒；还需在「隧道设置」中开启失败通知',
    group: 'event',
  },
  {
    field: 'dnd_enabled',
    label: '免打扰时段',
    // 「严重可突破」是有条件的：那道穿透由通知设置里的「严重事件兜底」控制（默认开启）。
    // 无条件地写"仅严重告警可突破"，在用户关掉兜底之后就是一句假话。
    hint: '时段内静默；「严重事件兜底」开启时严重级别仍会发出。起止小时见下方',
    group: 'guard',
  },
  { field: 'guard_enabled', label: '后台守护轮询', hint: 'App 退到后台后继续定时拉取告警', group: 'guard' },
  {
    field: 'guard_foreground_keepalive_enabled',
    label: '前台服务保活',
    hint: '常驻通知，降低被系统回收概率',
    group: 'guard',
  },
];

const globalNotify = computed(() => NOTIFY_SWITCHES.filter((s) => s.group === 'global'));
const thresholdNotify = computed(() => NOTIFY_SWITCHES.filter((s) => s.group === 'threshold'));
const eventNotify = computed(() => NOTIFY_SWITCHES.filter((s) => s.group === 'event'));
const guardNotify = computed(() => NOTIFY_SWITCHES.filter((s) => s.group === 'guard'));

// 本地初值必须与 core `NotificationConfig` 的默认值逐字一致（2026-09-07 起通知一律不默认开启，
// 唯一的例外是 2026-09-10 新增的 critical_override_enabled = true）：loadNotifyConfig 失败时
// 会静默沿用这份初值，写反就会让 web 上的显示与设备真值相反 —— 假开关。
const notifyConfig = reactive<NotifyConfig>({
  master_enabled: false,
  mail_respect_dnd: false,
  alert_enabled: false,
  connectivity_enabled: false,

  sms_enabled: false,
  verification_enabled: false,
  download_enabled: false,
  traffic_80_enabled: false,
  device_events_enabled: false,
  tunnel_enabled: false,
  dnd_enabled: false,
  dnd_start_hour: 23,
  dnd_end_hour: 7,
  guard_enabled: false,
  guard_interval_minutes: 30,
  guard_foreground_keepalive_enabled: false,
  history_max_rows: 500,
  history_max_age_days: 30,
  critical_override_enabled: CRITICAL_OVERRIDE_DEFAULT,
});
const notifyLoading = ref(false);

// 回读失败静默沿用本地默认值：core 是唯一真源，但不因一次网络抖动在 UI 上报错
async function loadNotifyConfig() {
  notifyLoading.value = true;
  try {
    const { data } = await api.get(Endpoints.notifications.config);
    Object.assign(notifyConfig, data || {});
  } catch {
    /* 静默 */
  } finally {
    notifyLoading.value = false;
  }
}

// 字段级 PATCH：core 的 PUT 只覆盖请求里出现的键，不会重置其他字段。
// 乐观更新 + 失败回滚，避免开关显示与设备实际状态不一致。
async function saveNotify(patch: Partial<NotifyConfig>) {
  const before: Partial<NotifyConfig> = {};
  for (const k of Object.keys(patch) as (keyof NotifyConfig)[]) {
    (before as any)[k] = notifyConfig[k];
  }
  Object.assign(notifyConfig, patch);
  try {
    const { data } = await api.put(Endpoints.notifications.config, patch);
    if (data?.config) Object.assign(notifyConfig, data.config);
  } catch (e: any) {
    Object.assign(notifyConfig, before);
    message.error(e?.response?.data?.message || e?.response?.data?.error || '保存通知设置失败');
  }
}

// ── 数据加载 ──
function syncPerType(src: unknown) {
  for (const k of Object.keys(perType)) {
    delete perType[k];
  }
  if (src && typeof src === 'object') {
    for (const [k, v] of Object.entries(src as Record<string, unknown>)) {
      perType[k] = v === true;
    }
  }
}

/**
 * 分类开关的显示状态 = `perType[type]` AND 取数闸门。
 * 带闸门的三类（套餐限额 / 设备接入 / 设备离开）闸门关着时引擎拿不到数据，
 * 此时显示"开"就是假开关 —— 与 app `alertTypeGateEnabled` 同判据。
 */
function typeChecked(item: AlertTypeSwitch): boolean {
  const on = perType[item.type] === true;
  return item.gate ? on && notifyConfig[item.gate] === true : on;
}

/**
 * 同步取数闸门。
 * 设备接入 / 离开共用 `device_events_enabled`（core 一次取数供两个方向），
 * 所以只有**两类都关**时才关闸门，否则关掉一类会把另一类的数据来源一起掐掉。
 */
async function syncTypeGate(item: AlertTypeSwitch, nextPerType: Record<string, boolean>) {
  if (!item.gate) {
    return;
  }
  const sibling =
    item.type === 'device_online' ? 'device_offline' : item.type === 'device_offline' ? 'device_online' : null;
  const want = nextPerType[item.type] === true || (sibling !== null && nextPerType[sibling] === true);
  if (notifyConfig[item.gate] !== want) {
    await saveNotify({ [item.gate]: want });
  }
}

/**
 * 单类告警开关：**整份 perType 回传，只改那一个键**。
 * core `mergeConfigPatch` 对 perType 是整体替换（顶层键级合并，不做深合并），
 * 漏带任何键都会把对应类型静默重置成默认（缺键 = 关）。
 * 乐观更新 + 失败回滚，避免开关显示与 core 真值不一致。
 */
async function togglePerType(item: AlertTypeSwitch, on: boolean) {
  if (!configLoaded.value) {
    return;
  }
  const nextPerType: Record<string, boolean> = { ...perType, [item.type]: on };
  const before = perType[item.type] === true;
  perType[item.type] = on;
  try {
    // rawConfig 带着 configVersion —— 版本守门靠它，漏掉必然 409
    const { data } = await api.put('/api/alerts/config', { ...rawConfig.value, perType: nextPerType });
    if (data?.config) {
      rawConfig.value = { ...data.config };
      syncPerType(data.config.perType);
    } else {
      rawConfig.value = { ...rawConfig.value, perType: nextPerType };
    }
    await syncTypeGate(item, nextPerType);
  } catch (e: any) {
    perType[item.type] = before;
    const res = e?.response;
    if (res?.status === 409) {
      // 版本冲突：其他端已改过配置。同步到最新真值后让用户再切一次。
      const cur = res.data?.config;
      if (cur) {
        rawConfig.value = { ...cur };
        syncPerType(cur.perType);
      }
      message.error(res.data?.message || '配置已被其他端修改，已同步最新状态，请重试');
    } else {
      message.error(res?.data?.message || res?.data?.error || '保存告警分类失败');
    }
  }
}

function applyConfig(data: any) {
  rawConfig.value = { ...(data || {}) };
  configLoaded.value = true;
  syncPerType(data?.perType);
  Object.assign(config, {
    // 2026-09-07：兜底改回 false，与 core `AlertEngine.AlertConfig.enabled`
    // 和 app `AlertConfig.enabled` 的新默认值（不默认开启）一致。core 少返回这个
    // 字段时若兜底成 true，会出现"web 显示开、app 显示关"的假开关。
    enabled: data.enabled ?? false,
    temperatureWarning: data.temperatureWarning ?? 45,
    temperatureCritical: data.temperatureCritical ?? 55,
    batteryWarning: data.batteryWarning ?? 20,
    batteryCritical: data.batteryCritical ?? 10,
    trafficWarningMb: data.trafficWarningMb ?? 1024,
    trafficCriticalMb: data.trafficCriticalMb ?? 2048,
    signalWarningRsrp: data.signalWarningRsrp ?? -100,
    signalCriticalRsrp: data.signalCriticalRsrp ?? -115,
  });
}

async function loadConfig() {
  configLoading.value = true;
  try {
    const { data } = await api.get('/api/alerts/config');
    applyConfig(data);
  } catch {
    message.error('加载告警配置失败');
  } finally {
    configLoading.value = false;
  }
}

// ── 配置保存（409 版本守门）──
async function saveConfig() {
  saving.value = true;
  try {
    const { data } = await api.put('/api/alerts/config', {
      ...rawConfig.value,
      enabled: config.enabled,
      temperatureWarning: config.temperatureWarning,
      temperatureCritical: config.temperatureCritical,
      batteryWarning: config.batteryWarning,
      batteryCritical: config.batteryCritical,
      trafficWarningMb: config.trafficWarningMb,
      trafficCriticalMb: config.trafficCriticalMb,
      signalWarningRsrp: config.signalWarningRsrp,
      signalCriticalRsrp: config.signalCriticalRsrp,
    });
    // core 返回自增后的 configVersion，必须回写，否则下次保存必然 409
    if (data?.config) rawConfig.value = { ...data.config };
    message.success('配置已保存');
  } catch (e: any) {
    const res = e?.response;
    if (res?.status === 409) {
      // 版本冲突：其他端已改过配置。只同步版本号，保留用户当前输入，让其再点一次保存。
      const cur = res.data?.config;
      if (cur) rawConfig.value = { ...cur };
      message.error(res.data?.message || '配置已被其他端修改，请再次点击保存以覆盖');
    } else {
      message.error(res?.data?.message || res?.data?.error || '保存失败');
    }
  } finally {
    saving.value = false;
  }
}

onMounted(() => {
  loadConfig();
  loadNotifyConfig();
});
</script>

<style scoped>
.config-panel {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

/* ── 总闸 hero ── */
.config-hero {
  background: var(--surface-elevated);
}
.hero-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
}
.hero-text {
  display: flex;
  flex-direction: column;
  gap: 4px;
  min-width: 0;
}
.hero-title {
  font-size: 16px;
  font-weight: 600;
  color: var(--text-primary);
}
.hero-desc {
  font-size: 13px;
  color: var(--text-muted);
  line-height: 1.5;
}
.hero-switch {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-shrink: 0;
}
.hero-state {
  font-size: 13px;
  font-weight: 600;
}
.hero-state.on {
  color: var(--success);
}
.hero-state.off {
  color: var(--text-muted);
}

/* ── 分组 ── */
.cfg-section {
  padding: 4px 0 16px;
}
.cfg-section:last-child {
  padding-bottom: 0;
}
.cfg-section + .cfg-section {
  border-top: 1px solid var(--border-subtle);
  padding-top: 16px;
}
.cfg-section-head {
  display: flex;
  align-items: baseline;
  gap: 10px;
  margin-bottom: 12px;
}
.cfg-section-title {
  font-size: 14px;
  font-weight: 600;
  color: var(--text-primary);
}
.cfg-section-hint {
  font-size: 12px;
  color: var(--text-muted);
}

/* ── 阈值网格 ── */
.threshold-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 14px 20px;
}
.threshold-item {
  display: flex;
  flex-direction: column;
  gap: 3px;
}
.threshold-label {
  font-size: 13px;
  font-weight: 500;
  color: var(--text-secondary);
}
.threshold-hint {
  font-size: 11px;
  line-height: 1.4;
  color: var(--text-muted);
}

/* ── 通知设置 ──
   设置行本身已改用 components/ToggleRow.vue（原先本文件那份 .notify-row/.notify-text/
   .notify-label/.notify-hint 与 ToggleRow 语义完全相同、取值全不同，是 G1 要消除的
   「同版式两种表现」；2026-09-08 按决策 D4(a) 统一到 ToggleRow 取值）。
   这里只保留「行的容器」与「脚注」。 */
.notify-grid {
  display: flex;
  flex-direction: column;
}
/* 免打扰起止两个小时输入框：ToggleRow 的 #control 只有一个插槽位，
   两个输入框需要自己的容器来定间距（原先是靠 .notify-row 的 space-between 撑开） */
.hour-range {
  display: flex;
  align-items: center;
  gap: var(--space-2);
}
.notify-note {
  margin-top: 10px;
  font-size: 12px;
  color: var(--text-muted);
}

/* ── 响应式 ── */
@media (max-width: 768px) {
  .threshold-grid {
    grid-template-columns: 1fr;
  }
}
</style>
