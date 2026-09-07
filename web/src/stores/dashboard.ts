import { defineStore } from 'pinia';
import { ref, shallowRef } from 'vue';
import type {
  DashboardData,
  SignalInfo,
  CpuInfo,
  TrafficRealtime,
  BatteryInfo,
  MemoryInfo,
} from '@/types';

/** 实时推送频道 → 对应载荷类型（与 WsChannel 对齐）。 */
type RealtimeType = 'signal' | 'cpu' | 'traffic' | 'battery' | 'memory';

export const useDashboardStore = defineStore('dashboard', () => {
  const summary = ref<DashboardData | null>(null);
  const loading = ref(false);
  const lastUpdated = ref(0);

  // 实时数据（WebSocket / REST 推送更新）。初始 null，由 DefaultLayout / 各页写入。
  const realtimeSignal = shallowRef<SignalInfo | null>(null);
  const realtimeCpu = shallowRef<CpuInfo | null>(null);
  const realtimeTraffic = shallowRef<TrafficRealtime | null>(null);
  const realtimeBattery = shallowRef<BatteryInfo | null>(null);
  const realtimeMemory = shallowRef<MemoryInfo | null>(null);

  function updateSummary(data: DashboardData) {
    summary.value = data;
    lastUpdated.value = Date.now();
  }

  function updateRealtime(
    type: RealtimeType,
    data: SignalInfo | CpuInfo | TrafficRealtime | BatteryInfo | MemoryInfo,
  ) {
    switch (type) {
      case 'signal':
        realtimeSignal.value = data as SignalInfo;
        break;
      case 'cpu':
        realtimeCpu.value = data as CpuInfo;
        break;
      case 'traffic':
        realtimeTraffic.value = data as TrafficRealtime;
        break;
      case 'battery':
        realtimeBattery.value = data as BatteryInfo;
        break;
      case 'memory':
        realtimeMemory.value = data as MemoryInfo;
        break;
    }
  }

  return {
    summary,
    loading,
    lastUpdated,
    realtimeSignal,
    realtimeCpu,
    realtimeTraffic,
    realtimeBattery,
    realtimeMemory,
    updateSummary,
    updateRealtime,
  };
});
