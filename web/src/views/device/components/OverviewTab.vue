<template>
  <div class="tab-grid">
    <!-- 左列：SIM 卡 → 连接统计 → 电池 -->
    <div class="tab-col">
      <!-- SIM 卡 -->
      <GridCard title="SIM 卡">
        <template #extra>
          <n-button size="tiny" quaternary :loading="summaryLoading" @click="refreshSummary">刷新</n-button>
        </template>
        <!-- summary 未到 / summary 已到但 device_info 为 null（core 侧 goform 超时会静默返回 null）/ 正常，
           三种状态在 UI 上原本长得完全一样（一片空白），用户无法判断是在加载还是读取失败 -->
        <div v-if="!dashboardStore.summary" class="card-placeholder">
          <n-spin size="small" />
        </div>
        <n-empty v-else-if="!deviceInfo" size="small" description="设备信息读取失败，请刷新重试">
          <template #extra>
            <n-button size="tiny" :loading="summaryLoading" @click="refreshSummary">重试</n-button>
          </template>
        </n-empty>
        <div v-else>
          <InfoRow label="SIM 状态" :value="simState" />
          <InfoRow label="本机号码" :value="deviceText('identity.msisdn')" />
          <InfoRow label="IMEI" :value="deviceText('identity.imei')" />
          <InfoRow label="IMSI" :value="deviceText('identity.imsi')" />
          <InfoRow label="ICCID" :value="deviceText('identity.iccid')" />
        </div>
        <!-- 卡槽切换：2026-08-29 暂时隐藏（保留代码，后续换其它型号可能用得上）。
            原因：core 只有写接口 POST /api/sim/switch，**没有查询当前卡槽的稳定接口**
            （设备侧 sim_slot 只出现在默认关闭的 GET /api/device/goform dump 里），
            UI 无法回显当前是哪个卡槽，四个按钮看起来像状态其实全是盲切。
            恢复时把下面两段取消注释即可，script 里的 SIM_SLOTS / switchSimSlot 都还在。
      <div class="sim-slot-row">
        <span class="sim-slot-label">卡槽切换</span>
        <n-space :size="6">
          <n-button
            v-for="opt in SIM_SLOTS"
            :key="opt.value"
            size="tiny"
            :loading="simSwitching === opt.value"
            :disabled="simSwitching !== null"
            @click="switchSimSlot(opt.value, opt.label)"
          >
            {{ opt.label }}
          </n-button>
        </n-space>
      </div>
      <div class="sim-slot-hint">
        切换会重新拨号，蜂窝网络会短暂中断；卡槽序号由 core 换算成设备值，不校验该型号是否支持。
      </div>
      -->
      </GridCard>

      <!-- 连接统计 -->
      <GridCard title="连接统计">
        <n-spin :show="connectionsLoading">
          <div class="stat-row">
            <div class="stat-item">
              <span class="stat-label">TCP</span>
              <span class="stat-value">{{ connections.tcp ?? '--' }}</span>
            </div>
            <div class="stat-item">
              <span class="stat-label">TCP6</span>
              <span class="stat-value">{{ connections.tcp6 ?? '--' }}</span>
            </div>
            <div class="stat-item">
              <span class="stat-label">UDP</span>
              <span class="stat-value">{{ connections.udp ?? '--' }}</span>
            </div>
            <div class="stat-item">
              <span class="stat-label">UDP6</span>
              <span class="stat-value">{{ connections.udp6 ?? '--' }}</span>
            </div>
            <div class="stat-item">
              <span class="stat-label">Unix</span>
              <span class="stat-value">{{ connections.unix ?? '--' }}</span>
            </div>
          </div>
        </n-spin>
      </GridCard>

      <!-- 电池 -->
      <GridCard title="电池">
        <template #extra>
          <n-button size="tiny" quaternary :loading="summaryLoading" @click="refreshSummary">刷新</n-button>
        </template>
        <!-- 与 SIM 卡同理：电量同样只来自 summary，加载中与读取失败必须能区分 -->
        <div v-if="!dashboardStore.summary" class="card-placeholder">
          <n-spin size="small" />
        </div>
        <n-empty v-else-if="!batteryInfo" size="small" description="设备信息读取失败，请刷新重试">
          <template #extra>
            <n-button size="tiny" :loading="summaryLoading" @click="refreshSummary">重试</n-button>
          </template>
        </n-empty>
        <div v-else>
          <InfoRow
            label="电量"
            :value="batteryInfo?.percent != null && batteryInfo.percent >= 0 ? `${batteryInfo.percent}%` : '--'"
          />
          <InfoRow label="状态" :value="batteryStatus" />
          <InfoRow label="电源" :value="batteryInfo?.plugged || '--'" />
          <InfoRow label="温度" :value="batteryInfo?.temperature ? `${batteryInfo.temperature}°C` : '--'" />
          <InfoRow label="电压" :value="batteryInfo?.voltage ? `${batteryInfo.voltage.toFixed(2)}V` : '--'" />
        </div>
      </GridCard>
    </div>

    <!-- 右列：温度传感器（独占一列，传感器条目多时纵向铺开） -->
    <div class="tab-col">
      <GridCard title="温度传感器">
        <n-spin :show="thermalLoading">
          <div v-if="thermalZones.length" class="thermal-grid">
            <div v-for="zone in thermalZones" :key="zone.name" class="thermal-item">
              <span class="thermal-name">{{ zone.name }}</span>
              <span class="thermal-temp" :style="{ color: thermalColor(zone.temp) }">{{ zone.temp }}°C</span>
            </div>
          </div>
          <n-empty v-else description="无数据" />
        </n-spin>
      </GridCard>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted } from 'vue';
import { useInterval } from '@/composables/useRealtime';
import { useDashboardStore } from '@/stores/dashboard';
import { useCancellableApi } from '@/composables/useCancellableApi';
import { get } from '@/composables/utils';
import GridCard from '@/components/GridCard.vue';
import InfoRow from '@/components/InfoRow.vue';

const dashboardStore = useDashboardStore();
const api = useCancellableApi();

const summaryLoading = ref(false);

async function refreshSummary() {
  summaryLoading.value = true;
  try {
    const { data } = await api.get('/api/dashboard/summary');
    dashboardStore.updateSummary(data);
  } catch {
    /* 静默：布局层仍会周期性刷新 */
  } finally {
    summaryLoading.value = false;
  }
}

const deviceInfo = computed(() => get(dashboardStore.summary, 'device_info', null));
const batteryInfo = computed(() => get(dashboardStore.summary, 'battery', null));

const simState = computed(() => {
  const sim = deviceInfo.value?.sim;
  if (typeof sim === 'string') return sim;
  return sim?.sim_state || '--';
});

/**
 * goform 对「未写入」的字段返回空串而不是缺字段（例如 SIM 未写 MSISDN），
 * 而 get() 只在 undefined 时兜 fallback，空串会被原样渲染成一个空白格。
 * 这里统一把 null / undefined / 空串都显示为 `--`。
 */
function deviceText(path: string): string {
  const v = get(deviceInfo.value, path, '');
  return v === null || v === undefined || String(v).trim() === '' ? '--' : String(v);
}

// core SystemCollector.getBatteryInfo 返回 level/scale/percent/temperature/voltage/is_charging/plugged，
// 没有 status / technology 字段
const batteryStatus = computed(() => {
  const b = batteryInfo.value;
  if (!b || b.is_charging == null) return '--';
  return b.is_charging ? '充电中' : '放电中';
});

function thermalColor(temp: number): string {
  if (temp >= 70) return '#d03050';
  if (temp >= 50) return '#f0a020';
  return '#18a058';
}

const thermalLoading = ref(false);
const thermalZones = ref<Array<{ name: string; temp: number }>>([]);
async function loadThermal() {
  thermalLoading.value = true;
  try {
    const { data } = await api.get('/api/device/thermal');
    thermalZones.value = (data.zones || []).map((z: any) => ({
      name: z.name || z.type || 'unknown',
      temp: z.temperature ?? z.temp ?? 0,
    }));
  } catch {
    /* 静默 */
  } finally {
    thermalLoading.value = false;
  }
}

// 新增：连接统计
const connectionsLoading = ref(false);
// core SystemCollector.getConnectionCounts 返回 tcp / tcp6 / udp / udp6 / unix，IPv6 两项之前没展示
const connections = reactive<Record<'tcp' | 'tcp6' | 'udp' | 'udp6' | 'unix', number | null>>({
  tcp: null,
  tcp6: null,
  udp: null,
  udp6: null,
  unix: null,
});
async function loadConnections() {
  connectionsLoading.value = true;
  try {
    const { data } = await api.get('/api/device/connections');
    connections.tcp = data.tcp ?? null;
    connections.tcp6 = data.tcp6 ?? null;
    connections.udp = data.udp ?? null;
    connections.udp6 = data.udp6 ?? null;
    connections.unix = data.unix ?? null;
  } catch {
    /* 静默 */
  } finally {
    connectionsLoading.value = false;
  }
}

onMounted(() => {
  loadThermal();
  loadConnections();
  // summary 只在 DefaultLayout 挂载时取一次；直接进入本页或那次请求失败时
  // SIM / 电池卡会没有数据，所以本页自己补拉（与 DashboardView / NetworkView 一致）
  refreshSummary();
});

// 温度与连接数是实时量，之前只在挂载时取一次就再也不更新
useInterval(() => {
  loadThermal();
  loadConnections();
}, 10_000);
</script>

<style scoped>
/* 两列各自独立纵向堆叠：左列 SIM / 连接统计 / 电池，右列只有温度传感器。
   用不了原来的「grid 直接放卡 + full-width」—— 那种排法下卡片按行对齐，
   左列三张卡的高度会被右侧那张牵着走（温度传感器条目数不定）。 */
.tab-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 16px;
  align-items: start;
}
.tab-col {
  display: flex;
  flex-direction: column;
  gap: 16px;
  min-width: 0;
}
.stat-row {
  display: flex;
  gap: 16px;
}
.stat-item {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: 12px 8px;
  background: var(--page-bg);
  border-radius: 8px;
}
.stat-label {
  font-size: 12px;
  color: var(--text-secondary);
  margin-bottom: 4px;
}
.sim-slot-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  padding-top: 10px;
  margin-top: 6px;
  border-top: 1px solid var(--border-subtle);
}
.sim-slot-label {
  font-size: 13px;
  color: var(--text-secondary);
}
.sim-slot-hint {
  margin-top: 6px;
  font-size: 12px;
  color: var(--text-muted);
}
.stat-value {
  font-size: 20px;
  font-weight: 700;
  color: var(--text-primary);
  font-variant-numeric: tabular-nums;
}
.card-placeholder {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 120px;
}
.thermal-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(180px, 1fr));
  gap: 8px;
}
.thermal-item {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 6px 10px;
  background: var(--page-bg);
  border-radius: 6px;
}
.thermal-name {
  font-size: 12px;
  color: var(--text-secondary);
}
.thermal-temp {
  font-size: 14px;
  font-weight: 600;
  font-variant-numeric: tabular-nums;
}
@media (max-width: 768px) {
  .tab-grid {
    grid-template-columns: 1fr;
  }
  .stat-row {
    flex-direction: column;
  }
}
</style>
