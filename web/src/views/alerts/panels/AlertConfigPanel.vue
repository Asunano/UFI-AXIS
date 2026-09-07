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
          <span class="cfg-section-title">阈值告警通知</span>
        </header>
        <div class="notify-grid">
          <div v-for="item in thresholdNotify" :key="item.field" class="notify-row">
            <div class="notify-text">
              <span class="notify-label">{{ item.label }}</span>
              <span class="notify-hint">{{ item.hint }}</span>
            </div>
            <n-switch
              :value="notifyConfig[item.field] as boolean"
              @update:value="(v: boolean) => saveNotify({ [item.field]: v })"
            />
          </div>
        </div>
      </section>

      <section class="cfg-section">
        <header class="cfg-section-head">
          <span class="cfg-section-title">事件通知</span>
          <span class="cfg-section-hint">设备侧发生的离散事件</span>
        </header>
        <div class="notify-grid">
          <div v-for="item in eventNotify" :key="item.field" class="notify-row">
            <div class="notify-text">
              <span class="notify-label">{{ item.label }}</span>
              <span class="notify-hint">{{ item.hint }}</span>
            </div>
            <n-switch
              :value="notifyConfig[item.field] as boolean"
              @update:value="(v: boolean) => saveNotify({ [item.field]: v })"
            />
          </div>
        </div>
      </section>

      <section class="cfg-section">
        <header class="cfg-section-head">
          <span class="cfg-section-title">守护与免打扰</span>
        </header>
        <div class="notify-grid">
          <div v-for="item in guardNotify" :key="item.field" class="notify-row">
            <div class="notify-text">
              <span class="notify-label">{{ item.label }}</span>
              <span class="notify-hint">{{ item.hint }}</span>
            </div>
            <n-switch
              :value="notifyConfig[item.field] as boolean"
              @update:value="(v: boolean) => saveNotify({ [item.field]: v })"
            />
          </div>
          <div class="notify-row">
            <div class="notify-text">
              <span class="notify-label">免打扰起止小时</span>
              <span class="notify-hint">
                0-23；起 &gt; 止 表示跨零点（23→7 = 当晚 23:00 至次日 07:00），两者相等则不静默
              </span>
            </div>
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
          <div class="notify-row">
            <div class="notify-text">
              <span class="notify-label">守护轮询间隔</span>
              <span class="notify-hint">15-60 分钟，仅在后台守护开启时生效</span>
            </div>
            <n-input-number
              :value="notifyConfig.guard_interval_minutes"
              :min="15"
              :max="60"
              :step="5"
              size="small"
              style="width: 110px"
              @update:value="(v: number | null) => v && saveNotify({ guard_interval_minutes: v })"
            />
          </div>
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
import GridCard from '@/components/GridCard.vue';

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
// 且 receive<AlertConfig>() 对缺省字段取默认值 —— 只回传本页可编辑的字段会把
// notifyEnabled / perType / minIntervalSec / edgeTriggeredOnly / maxRows 全部重置。
// 因此保留完整的原始配置对象，保存时在其上覆盖本页字段。
const rawConfig = ref<Record<string, any>>({});

// ── 通知设置（core /api/notifications/config；与 app 共用同一份真源）──
interface NotifyConfig {
  alert_enabled: boolean;
  connectivity_enabled: boolean;

  sms_enabled: boolean;
  verification_enabled: boolean;
  download_enabled: boolean;
  traffic_80_enabled: boolean;
  device_events_enabled: boolean;
  dnd_enabled: boolean;
  dnd_start_hour: number;
  dnd_end_hour: number;
  guard_enabled: boolean;
  guard_interval_minutes: number;
  guard_foreground_keepalive_enabled: boolean;
}

type NotifyBoolField = Exclude<keyof NotifyConfig, 'guard_interval_minutes' | 'dnd_start_hour' | 'dnd_end_hour'>;

interface NotifySwitch {
  field: NotifyBoolField;
  label: string;
  hint: string;
  group: 'threshold' | 'event' | 'guard';
}

const NOTIFY_SWITCHES: NotifySwitch[] = [
  {
    field: 'alert_enabled',
    label: '阈值告警通知',
    hint: '温度/电量/流量/信号告警；同时是告警族总闸（关掉则离线上线、流量 80%、隧道失败也不发）',
    group: 'threshold',
  },
  {
    field: 'connectivity_enabled',
    label: '设备离线/上线通知',
    hint: '断开超过 1 分钟才提醒，恢复后补一条静默通知；受上面的总闸约束',
    group: 'threshold',
  },
  { field: 'traffic_80_enabled', label: '流量 80% 预警', hint: '达到限额 80% 时提醒一次', group: 'threshold' },
  { field: 'sms_enabled', label: '新短信通知', hint: '设备收到短信时提醒', group: 'event' },
  { field: 'verification_enabled', label: '验证码提取通知', hint: '依附短信开关，短信关则一并不发', group: 'event' },
  { field: 'download_enabled', label: '下载完成/失败通知', hint: '离线下载任务结束时提醒', group: 'event' },
  { field: 'device_events_enabled', label: '设备事件通知', hint: 'WiFi 客户端上下线等，默认关', group: 'event' },
  { field: 'dnd_enabled', label: '免打扰时段', hint: '时段内静默，仅严重告警可突破；起止小时见下方', group: 'guard' },
  { field: 'guard_enabled', label: '后台守护轮询', hint: 'App 退到后台后继续定时拉取告警', group: 'guard' },
  {
    field: 'guard_foreground_keepalive_enabled',
    label: '前台服务保活',
    hint: '常驻通知，降低被系统回收概率',
    group: 'guard',
  },
];

const thresholdNotify = computed(() => NOTIFY_SWITCHES.filter((s) => s.group === 'threshold'));
const eventNotify = computed(() => NOTIFY_SWITCHES.filter((s) => s.group === 'event'));
const guardNotify = computed(() => NOTIFY_SWITCHES.filter((s) => s.group === 'guard'));

const notifyConfig = reactive<NotifyConfig>({
  alert_enabled: false,
  connectivity_enabled: true,

  sms_enabled: true,
  verification_enabled: true,
  download_enabled: true,
  traffic_80_enabled: true,
  device_events_enabled: false,
  dnd_enabled: false,
  dnd_start_hour: 23,
  dnd_end_hour: 7,
  guard_enabled: false,
  guard_interval_minutes: 30,
  guard_foreground_keepalive_enabled: false,
});
const notifyLoading = ref(false);

// 回读失败静默沿用本地默认值：core 是唯一真源，但不因一次网络抖动在 UI 上报错
async function loadNotifyConfig() {
  notifyLoading.value = true;
  try {
    const { data } = await api.get('/api/notifications/config');
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
    const { data } = await api.put('/api/notifications/config', patch);
    if (data?.config) Object.assign(notifyConfig, data.config);
  } catch (e: any) {
    Object.assign(notifyConfig, before);
    message.error(e?.response?.data?.message || e?.response?.data?.error || '保存通知设置失败');
  }
}

// ── 数据加载 ──
function applyConfig(data: any) {
  rawConfig.value = { ...(data || {}) };
  Object.assign(config, {
    // 2026-09-04：兜底由 false 改为 true，与 core `AlertEngine.AlertConfig.enabled`
    // 和 app `AlertConfig.enabled` 一致。core 少返回这个字段时，原来 web 显示"关"、
    // app 显示"开"，同一个真源在两端读出相反状态（假开关的另一种形态）。
    enabled: data.enabled ?? true,
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

/* ── 通知设置 ── */
.notify-grid {
  display: flex;
  flex-direction: column;
}
.notify-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 10px 0;
  border-bottom: 1px solid var(--border-subtle);
}
.notify-row:last-child {
  border-bottom: none;
}
.notify-text {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}
.notify-label {
  font-size: 13px;
  color: var(--text-primary);
}
.notify-hint {
  font-size: 12px;
  color: var(--text-muted);
  line-height: 1.4;
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
