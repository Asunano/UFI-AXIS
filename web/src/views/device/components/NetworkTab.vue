<template>
  <div class="tab-grid">
    <!-- 流量限额 -->
    <GridCard title="流量限额">
      <n-spin :show="trafficLimitLoading">
        <ToggleRow
          label="流量限额"
          description="启用后，达到流量限额时将收到提醒或自动断网"
          :model-value="trafficLimit.enabled"
          @update:model-value="(v: boolean) => (trafficLimit.enabled = v)"
        />
        <div style="margin-bottom: 12px"></div>
        <div class="form-grid">
          <div class="form-item">
            <label>限额大小</label>
            <n-input-number v-model:value="trafficLimit.limit_size" :min="1" size="small" />
          </div>
          <div class="form-item">
            <label>限额单位</label>
            <n-select
              v-model:value="trafficLimit.limit_unit"
              :options="[
                { label: 'MB', value: 'MB' },
                { label: 'GB', value: 'GB' },
                { label: 'TB', value: 'TB' },
              ]"
              size="small"
            />
          </div>
          <div class="form-item">
            <label>提醒百分比</label>
            <n-input-number v-model:value="trafficLimit.alert_percent" :min="1" :max="100" size="small" />
          </div>
          <div class="form-item">
            <label>自动清除</label>
            <n-switch v-model:value="trafficLimit.auto_clear" />
          </div>
          <div class="form-item">
            <label>清除日期</label>
            <n-input-number v-model:value="trafficLimit.clear_date" :min="1" :max="31" size="small" />
          </div>
          <div class="form-item">
            <label>到达阈值关闭移动数据</label>
            <n-switch v-model:value="trafficLimit.auto_off" />
          </div>
          <div v-if="trafficLimit.auto_off" class="form-item">
            <label>清零后自动重新打开</label>
            <n-switch v-model:value="trafficLimit.auto_off_restore" />
          </div>
        </div>
        <div v-if="trafficLimit.auto_off" class="form-hint">
          达到提醒百分比后先发邮件（需在邮件设置里勾选「流量预警」场景），发送成功 1 分钟后关闭移动数据；
          邮件发送失败则不会关闭网络。
          <template v-if="trafficLimit.auto_off_triggered">本计费周期已触发过，用量清零前不会再次关闭。</template>
        </div>
        <div class="form-actions">
          <n-button type="primary" size="small" :loading="trafficLimitSaving" @click="saveTrafficLimit">保存</n-button>
        </div>
        <div class="monthly-stats">
          <InfoRow
            label="限额总量"
            :value="trafficLimit.limit_bytes > 0 ? formatBytes(trafficLimit.limit_bytes) : '未设置限额'"
          />
          <InfoRow label="本月已用" :value="formatBytes(trafficLimit.used_bytes)" />
          <InfoRow label="剩余" :value="trafficLimit.limit_bytes > 0 ? formatBytes(trafficRemainingBytes) : '--'" />
          <InfoRow label="本月已用（下行）" :value="formatBytes(trafficLimit.monthly_rx_bytes)" />
          <InfoRow label="本月已用（上行）" :value="formatBytes(trafficLimit.monthly_tx_bytes)" />
          <!-- 未启用限额时百分比没有意义，不显示进度条 -->
          <div v-if="trafficLimit.enabled" class="limit-progress">
            <n-progress
              v-if="trafficUsedPercent !== null"
              type="line"
              :percentage="trafficUsedPercent"
              :height="8"
              :status="trafficUsedPercent >= 100 ? 'error' : trafficUsedPercent >= 80 ? 'warning' : 'success'"
            />
            <span v-else class="limit-progress-hint">未设置限额</span>
          </div>
        </div>
      </n-spin>
    </GridCard>

    <!-- LAN / DHCP -->
    <GridCard title="LAN / DHCP 设置">
      <n-spin :show="lanLoading">
        <InfoRow label="LAN IP" :value="lanSettings.lan_ip || '--'" />
        <InfoRow label="子网掩码" :value="lanSettings.lan_netmask || '--'" />
        <InfoRow label="MAC 地址" :value="lanSettings.mac_address || '--'" />
        <InfoRow label="DHCP 服务" :value="lanSettings.dhcp_enabled ? '已开启' : '已关闭'" />
        <InfoRow label="DHCP 起始" :value="lanSettings.dhcp_start || '--'" />
        <InfoRow label="DHCP 结束" :value="lanSettings.dhcp_end || '--'" />
        <InfoRow label="租约时间" :value="lanLeaseText" />
        <InfoRow label="MTU" :value="lanSettings.mtu ?? '--'" />
        <div class="form-actions">
          <n-button size="small" @click="showLanModal = true">编辑</n-button>
        </div>
      </n-spin>
    </GridCard>

    <!-- LAN/DHCP 编辑弹窗（受控子组件：表单状态与构造逻辑在 LanEditModal 内，
         父组件只在 @save 里执行真正的接口写入） -->
    <LanEditModal v-model:show="showLanModal" :lan-settings="lanSettings" @save="saveLanSettings" />
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted } from 'vue';
import { useMessage } from 'naive-ui';
import { useCancellableApi } from '@/composables/useCancellableApi';
import { formatBytes, normalizeLanSettings } from '@/composables/utils';
import GridCard from '@/components/GridCard.vue';
import InfoRow from '@/components/InfoRow.vue';
import ToggleRow from '@/components/ToggleRow.vue';
import LanEditModal from './LanEditModal.vue';

const message = useMessage();
const api = useCancellableApi();

// 设备 goform 写入成功到「查询能读到新值」之间存在延迟，零延迟回读会拿到旧值。600ms 对齐 Android app。
const GOFORM_SETTLE_MS = 600;
const settleAfterWrite = () => new Promise((resolve) => setTimeout(resolve, GOFORM_SETTLE_MS));

// ─── 流量限额 ───
const trafficLimitLoading = ref(false);
const trafficLimitSaving = ref(false);
const trafficLimit = reactive({
  enabled: false,
  limit_size: 10,
  limit_unit: 'GB',
  alert_percent: 80,
  auto_clear: false,
  clear_date: 1,
  limit_bytes: 0,
  used_bytes: 0,
  monthly_rx_bytes: 0,
  monthly_tx_bytes: 0,
  // core 自制的到达阈值自动关网（响应里的 auto_off 块，不是设备字段）
  auto_off: false,
  auto_off_restore: false,
  auto_off_triggered: false,
});

const trafficRemainingBytes = computed(() => Math.max(0, trafficLimit.limit_bytes - trafficLimit.used_bytes));

// null = 未设置限额（limit_bytes 为 0），此时百分比无意义
const trafficUsedPercent = computed<number | null>(() => {
  if (trafficLimit.limit_bytes <= 0) return null;
  const pct = (trafficLimit.used_bytes / trafficLimit.limit_bytes) * 100;
  return Math.round(Math.min(100, Math.max(0, pct)) * 10) / 10;
});

async function loadTrafficLimit() {
  trafficLimitLoading.value = true;
  try {
    const { data } = await api.get('/api/device/traffic-limit');
    trafficLimit.enabled = !!data.enabled;
    trafficLimit.limit_size = Number(data.limit_value) || 10;
    trafficLimit.limit_unit = data.limit_unit_display || 'GB';
    trafficLimit.alert_percent = Number(data.alert_percent) || 80;
    trafficLimit.auto_clear = !!data.auto_clear;
    trafficLimit.clear_date = Number(data.clear_date) || 1;
    trafficLimit.limit_bytes = Number(data.limit_bytes) || 0;
    trafficLimit.used_bytes = Number(data.used_bytes) || 0;
    trafficLimit.monthly_rx_bytes = Number(data.monthly_rx_bytes) || 0;
    trafficLimit.monthly_tx_bytes = Number(data.monthly_tx_bytes) || 0;
    trafficLimit.auto_off = !!data.auto_off?.enabled;
    trafficLimit.auto_off_restore = !!data.auto_off?.restore_on_reset;
    trafficLimit.auto_off_triggered = !!data.auto_off?.triggered;
  } catch {
    /* 静默 */
  } finally {
    trafficLimitLoading.value = false;
  }
}

async function saveTrafficLimit() {
  trafficLimitSaving.value = true;
  let posted = false;
  try {
    await api.post('/api/device/data-limit', {
      enabled: trafficLimit.enabled,
      // 结构化下发：数值 + 单位。设备侧的 "470_1024" 复合串由 core 拼（core 2.8），
      // 前端不再关心乘数映射。
      limit_value: trafficLimit.limit_size,
      limit_unit: trafficLimit.limit_unit,
      alert_percent: trafficLimit.alert_percent,
      auto_clear: trafficLimit.auto_clear,
      clear_date: trafficLimit.clear_date,
      // core 自制：达到 alert_percent 后先发邮件，发信成功 1 分钟后关闭移动数据
      auto_off_enabled: trafficLimit.auto_off,
      auto_off_restore: trafficLimit.auto_off_restore,
    });
    posted = true;
    message.success('流量限额已保存');
  } catch {
    message.error('保存失败');
  } finally {
    if (posted) await settleAfterWrite();
    await loadTrafficLimit();
    trafficLimitSaving.value = false;
  }
}

// ─── LAN / DHCP ───
const lanLoading = ref(false);
const lanSaving = ref(false);
const showLanModal = ref(false);
const lanSettings = reactive({
  lan_ip: '',
  lan_netmask: '',
  mac_address: '',
  dhcp_enabled: false,
  dhcp_start: '',
  dhcp_end: '',
  dhcp_lease_sec: 0,
  mtu: null as number | null,
});
const lanLeaseText = computed(() => {
  const s = lanSettings.dhcp_lease_sec;
  if (!s) return '--';
  return s % 3600 === 0 ? `${s / 3600} 小时` : `${s} 秒`;
});

async function loadLanSettings() {
  lanLoading.value = true;
  try {
    const { data } = await api.get('/api/device/lan-settings');
    Object.assign(lanSettings, normalizeLanSettings(data));
  } catch {
    /* 静默 */
  } finally {
    lanLoading.value = false;
  }
}

async function saveLanSettings(payload: Record<string, any>) {
  lanSaving.value = true;
  let posted = false;
  try {
    await api.post('/api/device/dhcp', {
      lan_ip: payload.lan_ip,
      lan_netmask: payload.lan_netmask,
      dhcp_type: payload.dhcp_enabled ? 'SERVER' : 'DISABLE',
      dhcp_start: payload.dhcp_start,
      dhcp_end: payload.dhcp_end,
      dhcp_lease: String((payload.dhcp_lease_hour || 24) * 3600),
    });
    posted = true;
    message.success('LAN / DHCP 设置已保存');
  } catch {
    message.error('保存失败');
  } finally {
    // 不把表单值当成设备真实状态，回读一次（goform 可能规范化或拒绝部分字段）；
    // 成功后必须等设备侧生效，立即回读会读回旧的 LAN 配置
    if (posted) await settleAfterWrite();
    await loadLanSettings();
    lanSaving.value = false;
  }
}

onMounted(() => {
  loadTrafficLimit();
  loadLanSettings();
});
</script>

<style scoped>
.tab-grid {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 16px;
}
.form-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(200px, 1fr));
  gap: 12px;
  margin-bottom: 12px;
}
.form-item {
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.form-item label {
  font-size: 12px;
  color: var(--text-secondary);
  font-weight: 500;
}
.form-hint {
  margin-top: 8px;
  font-size: 12px;
  line-height: 1.5;
  color: var(--text-secondary);
}
.form-actions {
  display: flex;
  justify-content: flex-end;
  margin-top: 4px;
}
.monthly-stats {
  margin-top: 12px;
  padding-top: 8px;
  border-top: 1px solid var(--border-subtle);
}
.limit-progress {
  margin-top: 10px;
}
.limit-progress-hint {
  font-size: 12px;
  color: var(--text-muted);
}
.action-desc {
  font-size: 13px;
  color: var(--text-secondary);
  margin: 0 0 12px;
  line-height: 1.6;
}
@media (max-width: 768px) {
  .tab-grid {
    grid-template-columns: 1fr;
  }
  .form-grid {
    grid-template-columns: 1fr;
  }
}
</style>
