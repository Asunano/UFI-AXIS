<template>
  <n-modal
    v-model:show="show"
    preset="card"
    title="基站信息"
    style="width: 520px; max-width: calc(100vw - 32px)"
  >
    <div v-if="cellInfo?.serving_cell" class="kv-grid">
      <InfoRow label="PCI" :value="String(cellInfo.serving_cell.pci ?? '--')" />
      <InfoRow
        label="EARFCN"
        :value="String(cellInfo.serving_cell.earfcn ?? cellInfo.serving_cell.fc_number ?? '--')"
      />
      <InfoRow label="频段" :value="String(cellInfo.serving_cell.band ?? '--')" />
      <InfoRow
        label="RSRP"
        :value="cellInfo.serving_cell.rsrp != null ? `${cellInfo.serving_cell.rsrp} dBm` : '--'"
      />
      <InfoRow
        label="SINR"
        :value="cellInfo.serving_cell.sinr != null ? `${cellInfo.serving_cell.sinr} dB` : '--'"
      />
    </div>
    <div style="margin-top: 16px">
      <div class="neighbor-head">
        <span class="section-title">邻区列表 ({{ cellInfo?.neighbor_cells?.length || 0 }})</span>
        <n-button size="tiny" text type="primary" :loading="neighborRefreshing" @click="refreshNeighborCells">
          实时刷新
        </n-button>
      </div>
      <div v-if="cellInfo?.neighbor_cells?.length">
        <div v-for="(nc, i) in cellInfo.neighbor_cells" :key="i" class="neighbor-item">
          <span>PCI {{ nc.pci }}</span>
          <span>EARFCN {{ nc.earfcn }}</span>
          <span>RSRP {{ nc.rsrp }} dBm</span>
          <n-button size="tiny" text @click="lockCell(nc)">锁定</n-button>
        </div>
      </div>
      <div v-else class="hint-text">未检测到邻区</div>
    </div>

    <div v-if="cellInfo?.locked_cells?.length" style="margin-top: 16px">
      <div class="section-title" style="margin-bottom: 8px">已锁定基站</div>
      <div v-for="(lc, i) in cellInfo.locked_cells" :key="i" class="neighbor-item">
        <span>PCI {{ lc.pci }}</span>
        <span>EARFCN {{ lc.earfcn }}</span>
      </div>
      <n-button size="small" type="warning" style="margin-top: 8px" @click="unlockCells">全部解锁</n-button>
    </div>
  </n-modal>
</template>

<script setup lang="ts">
import { ref, computed, watch } from 'vue';
import { useMessage } from 'naive-ui';
import { getApiClient } from '@/composables/useApi';
import InfoRow from '@/components/InfoRow.vue';
import type { CellInfo } from '@/types';

const props = defineProps<{ show: boolean }>();
const emit = defineEmits<{ 'update:show': [boolean] }>();

const show = computed({
  get: () => props.show,
  set: (v) => emit('update:show', v),
});

const message = useMessage();
const api = getApiClient();
const settleDelay = () => new Promise((resolve) => setTimeout(resolve, 600));

const cellInfo = ref<CellInfo | null>(null);
const neighborRefreshing = ref(false);

// goform 的邻区字段可能是 JSON 字符串也可能已是数组
function parseCellArray(v: any): any[] {
  if (!v) return [];
  if (Array.isArray(v)) return v;
  if (typeof v === 'string') {
    try {
      return JSON.parse(v);
    } catch {
      return [];
    }
  }
  return [];
}

/**
 * 设备侧 rat 字符串 → `POST /api/device/cell-lock` 的 `network_type` 值域（`LTE` | `NR`）。
 * core 自己也认 4G/5G/FDD LTE 这些别名，但取值域只有这两个，空值会被 validate 拒成 400，
 * 所以这里显式收敛并给 LTE 兜底（邻区列表里 rat 缺失的情况不少）。
 */
function toNetworkType(rat: unknown): 'LTE' | 'NR' {
  const v = String(rat ?? '')
    .trim()
    .toUpperCase();
  return v.startsWith('NR') || v.startsWith('5G') ? 'NR' : 'LTE';
}

function mapNeighbors(raw: any[]) {
  return raw.map((c: any) => ({
    pci: c.pci ?? '',
    earfcn: c.earfcn ?? '',
    rsrp: c.rsrp ?? '--',
    rsrq: c.rsrq ?? '--',
    sinr: c.sinr ?? '--',
    // rat 是设备上报的制式串，锁小区时由 toNetworkType() 收敛成 LTE|NR 再提交。
    // 不能用 band（频段号）兜底，否则会锁到错误的制式。
    rat: c.rat ?? '',
  }));
}

async function loadCellInfo() {
  try {
    const { data } = await api.get('/api/network/cell-info');
    // core 回的是 cell-info 分组的契约字段（Lte_pci / Lte_fcn / Lte_bands 这些名字是
    // 冻结下来的设备原名，见 contract.ts 的 DeviceFields.cellInfo），这里拍平成前端结构
    const raw: any = data || {};
    const neighborRaw = parseCellArray(raw.neighbor_cell_info);
    const lockedRaw = parseCellArray(raw.locked_cell_info);
    const parsed: any = {
      serving_cell: {
        pci: raw.Lte_pci ?? raw.pci ?? '',
        earfcn: raw.Lte_fcn ?? raw.earfcn ?? '',
        band: raw.Lte_bands ?? raw.band ?? '',
        rsrp: raw.lte_rsrp ?? raw.rsrp ?? null,
        rsrq: raw.lte_rsrq ?? raw.rsrq ?? null,
        sinr: raw.lte_snr ?? raw.sinr ?? null,
      },
      neighbor_cells: mapNeighbors(neighborRaw),
      locked_cells: lockedRaw.map((c: any) => ({
        pci: c.pci ?? '',
        earfcn: c.earfcn ?? '',
        rat: c.rat ?? '',
      })),
    };
    cellInfo.value = parsed as CellInfo;
  } catch {
    message.error('获取基站信息失败');
  }
}

// /api/network/cell-info 走 300s 缓存，/api/network/neighbor-cells 每次实时查询，
// 因此邻区单独提供一个不吃缓存的刷新入口
async function refreshNeighborCells() {
  neighborRefreshing.value = true;
  try {
    const { data } = await api.get('/api/network/neighbor-cells');
    const list = mapNeighbors(parseCellArray((data as any)?.neighbor_cell_info));
    if (cellInfo.value) cellInfo.value.neighbor_cells = list as any;
    message.success(`邻区已刷新（${list.length}）`);
  } catch {
    message.error('刷新邻区失败');
  } finally {
    neighborRefreshing.value = false;
  }
}

async function lockCell(nc: { pci: number; earfcn: number; rat: string }) {
  try {
    // 入参是 network_type（LTE|NR），不是设备侧的 rat 字符串。
    // core 会校验取值域，非 LTE/NR 直接回 400 OUT_OF_RANGE 且不下发；
    // 旧字段名 rat 仍被兼容但会打 warn，这里不再用它。
    await api.post('/api/device/cell-lock', {
      pci: String(nc.pci),
      earfcn: String(nc.earfcn),
      network_type: toNetworkType(nc.rat),
    });
    message.success('基站已锁定');
  } catch {
    message.error('锁定失败');
  }
  // 锁定生效后驻留信息才会变，立刻回读拿到的还是旧小区
  await settleDelay();
  loadCellInfo();
}

async function unlockCells() {
  try {
    await api.post('/api/device/cell-unlock');
    message.success('基站已全部解锁');
  } catch {
    message.error('解锁失败');
  }
  await settleDelay();
  loadCellInfo();
}

watch(show, (v) => {
  if (v) loadCellInfo();
});
</script>

<style scoped>
.kv-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(200px, 1fr));
  gap: 0 20px;
}
.neighbor-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 8px;
}
.section-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--text-secondary);
}
.neighbor-item {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 6px 0;
  border-bottom: 1px solid var(--border-subtle);
  font-size: 13px;
}
.neighbor-item:last-child {
  border-bottom: none;
}
.hint-text {
  font-size: 13px;
  color: var(--text-muted);
}
</style>
