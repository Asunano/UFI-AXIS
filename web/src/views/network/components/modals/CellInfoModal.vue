<template>
  <n-modal v-model:show="show" preset="card" title="基站信息" class="modal-fit" style="--modal-w: 520px">
    <n-spin :show="loading">
      <div v-if="cellInfo?.serving_cell" class="kv-grid">
        <InfoRow label="PCI" :value="dash(cellInfo.serving_cell.pci)" />
        <InfoRow label="EARFCN" :value="dash(cellInfo.serving_cell.earfcn ?? cellInfo.serving_cell.fc_number)" />
        <InfoRow label="频段" :value="dash(cellInfo.serving_cell.band)" />
        <InfoRow
          label="RSRP"
          :value="cellInfo.serving_cell.rsrp != null ? `${cellInfo.serving_cell.rsrp} dBm` : '--'"
        />
        <InfoRow label="SINR" :value="cellInfo.serving_cell.sinr != null ? `${cellInfo.serving_cell.sinr} dB` : '--'" />
      </div>
      <div style="margin-top: 16px">
        <div class="neighbor-head">
          <span class="section-title">邻区列表 ({{ cellInfo?.neighbor_cells?.length || 0 }})</span>
          <n-button size="tiny" text type="primary" :loading="neighborRefreshing" @click="refreshNeighborCells">
            实时刷新
          </n-button>
        </div>
        <!-- 三态（加载中 / 空 / 失败可重试）：读取失败时不能显示「未检测到邻区」，
             那是把「没读到」伪装成「没有」—— 家规第 6 条。 -->
        <div v-if="loadFailed" class="hint-text">
          邻区读取失败，
          <n-button size="tiny" text type="primary" @click="loadCellInfo">重试</n-button>
        </div>
        <div v-else-if="cellInfo?.neighbor_cells?.length" class="neighbor-list">
          <div v-for="(nc, i) in cellInfo.neighbor_cells" :key="i" class="neighbor-item">
            <div class="neighbor-id">
              <span class="neighbor-pci">PCI {{ dash(nc.pci) }}</span>
              <span class="neighbor-rat" :class="{ 'neighbor-rat--nr': isNRCell(nc) }">{{
                isNRCell(nc) ? '5G' : '4G'
              }}</span>
            </div>
            <div class="neighbor-metrics">
              <span>EARFCN {{ dash(nc.earfcn) }}</span>
              <span :class="rsrpClass(nc.rsrp)">RSRP {{ rsrpText(nc.rsrp) }}</span>
            </div>
            <n-button
              size="tiny"
              type="primary"
              secondary
              class="neighbor-lock"
              :loading="lockingIndex === i"
              :disabled="lockingIndex !== null && lockingIndex !== i"
              @click="lockSelectedCell(nc, i)"
              >锁定</n-button
            >
          </div>
        </div>
        <div v-else-if="!loading" class="hint-text">未检测到邻区</div>
      </div>

      <div v-if="cellInfo?.locked_cells?.length" style="margin-top: 16px">
        <div class="section-title" style="margin-bottom: 8px">已锁定基站</div>
        <div v-for="(lc, i) in cellInfo.locked_cells" :key="i" class="neighbor-item">
          <span>PCI {{ dash(lc.pci) }}</span>
          <span>EARFCN {{ dash(lc.earfcn) }}</span>
        </div>
        <n-button size="small" type="warning" style="margin-top: 8px" @click="unlockAllCells">全部解锁</n-button>
      </div>
    </n-spin>
  </n-modal>
</template>

<script setup lang="ts">
import { ref, computed, watch } from 'vue';
import { useMessage } from 'naive-ui';
import { getApiClient } from '@/composables/useApi';
import { parseCellList } from '@/api/contract';
import InfoRow from '@/components/InfoRow.vue';
import type { CellInfo } from '@/types';

const props = defineProps<{
  show: boolean;
  /**
   * 锁定一个邻区 / 全部解锁（2026-10-14 新增）。
   *
   * 这两个动作都是 **A 级操作**（下发 + 按 contract 的 `NetworkModeSwitchProbe` 预算轮询回读确认），
   * 执行体在 `useNetworkControls`：本组件不自己发 POST —— 那个 composable 每次调用都会新建一套
   * 页面级 ref（见其文件头），弹窗里再调一次就会多出一份状态与一次 `finishedSeq` 订阅，
   * 所以由 NetworkView 以函数形式传进来（与卡片收 props 的约定一致）。
   *
   * 返回值 = 设备是否在预算内被确认生效（超时返回 false，但这次操作本身已经结束）。
   */
  lockCell: (cell: { pci: string; earfcn: string; networkType: 'LTE' | 'NR' }) => Promise<boolean>;
  unlockCells: () => Promise<boolean>;
}>();

const emit = defineEmits<{ 'update:show': [boolean] }>();

const show = computed({
  get: () => props.show,
  set: (v) => emit('update:show', v),
});

const message = useMessage();
const api = getApiClient();

const cellInfo = ref<CellInfo | null>(null);
const neighborRefreshing = ref(false);
/** 首次读取 / 重试的整块加载态（配合 n-spin + 邻区三态，见模板）。 */
const loading = ref(false);
/** 读取失败标记：不能把失败渲染成「未检测到邻区」（家规第 6 条）。 */
const loadFailed = ref(false);
/** 正在锁定的邻区下标：同卡其它按钮有 pending，这里补齐，避免连点重复下发。 */
const lockingIndex = ref<number | null>(null);

// 数组字段的两形态解析（真数组 / 数组的 JSON 字符串）统一在 contract.parseCellList ——
// 展示与锁定的回读确认读的是同一份规则，不再各写一份

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

/**
 * 邻区制式判定（与 app 端 inferIsNRDlg 同判据）：NR 频点 > 100000，或 rat 以 NR / 5G 开头。
 * 邻区数据里**没有**频段号（band）字段，所以这里不做「频段号 > 255」那一判
 * （2026-10-21 修正了原注释：它写的那条判据从未实现过，留矛盾注释比不写更误导）。
 * 都判不出时按 LTE 处理 —— 锁小区提交走的也是同一个 toNetworkType()。
 */
function isNRCell(nc: { earfcn: unknown; rat: unknown }): boolean {
  const earfcn = Number(nc.earfcn);
  if (Number.isFinite(earfcn) && earfcn > 100000) return true;
  return toNetworkType(nc.rat) === 'NR';
}

/** 空值统一显示 `--`（家规第 5 条）：PCI / EARFCN 这类标识字段缺值时不再留一段空白。 */
function dash(v: unknown): string {
  return v == null || v === '' ? '--' : String(v);
}

/** 邻区 RSRP 文案：缺值统一 `--`，不要把 `null dBm` / `-- dBm` 印到界面上（家规第 5 条）。 */
function rsrpText(rsrp: unknown): string {
  return rsrp == null || rsrp === '--' || rsrp === '' ? '--' : `${rsrp} dBm`;
}

/** RSRP 数值着色：>=-85 好（绿），-85~-95 中（amber），-95~-105 弱（橙），更低极弱（红） */
function rsrpClass(rsrp: unknown): string {
  const v = Number(rsrp);
  if (!Number.isFinite(v)) return '';
  if (v >= -85) return 'rsrp-good';
  if (v >= -95) return 'rsrp-fair';
  if (v >= -105) return 'rsrp-weak';
  return 'rsrp-poor';
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
  loading.value = true;
  loadFailed.value = false;
  try {
    const { data } = await api.get('/api/network/cell-info');
    // core 回的是 cell-info 分组的契约字段（Lte_pci / Lte_fcn / Lte_bands 这些名字是
    // 冻结下来的设备原名，见 contract.ts 的 DeviceFields.cellInfo），这里拍平成前端结构
    const raw: any = data || {};
    const neighborRaw = parseCellList(raw.neighbor_cell_info);
    const lockedRaw = parseCellList(raw.locked_cell_info);
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
    // 失败标记交给模板显示「读取失败 + 重试」；toast 只做补充说明
    loadFailed.value = true;
    message.error('获取基站信息失败');
  } finally {
    loading.value = false;
  }
}

// /api/network/cell-info 走 300s 缓存，/api/network/neighbor-cells 每次实时查询，
// 因此邻区单独提供一个不吃缓存的刷新入口
async function refreshNeighborCells() {
  neighborRefreshing.value = true;
  try {
    const { data } = await api.get('/api/network/neighbor-cells');
    const list = mapNeighbors(parseCellList((data as any)?.neighbor_cell_info));
    if (cellInfo.value) cellInfo.value.neighbor_cells = list as any;
    message.success(`邻区已刷新（${list.length}）`);
  } catch {
    message.error('刷新邻区失败');
  } finally {
    neighborRefreshing.value = false;
  }
}

/**
 * 锁定一个邻区（2026-10-14 改：接 A 级确认器）。
 *
 * 改前：本组件 `api.post('/api/device/cell-lock')` → `message.success('基站已锁定')` →
 * `settleDelay()`（600ms）→ **单次** `loadCellInfo()`，**没有任何判据**。
 * 设备侧锁定后要重新驻留小区，600ms 时服务小区还是原来那个 —— 界面显示的锁定结果与设备真值
 * 不一致，且"成功"是下发被受理就直接报的（受理 ≠ 生效）。
 *
 * 现在：`props.lockCell` 内部按 contract 的预算轮询回读，判据是服务小区的 pci/earfcn
 * 已等于目标（NR 目标见 composable 里的说明）。本组件**不再自己报成功/失败** ——
 * 终态 toast 与失败原因由确认器统一给（它有 hidden 后台进行、重试这些收敛手段）。
 * 这里只在操作结束后回读一次，把界面拉到设备真值：超时那一支更需要它（设备可能稍后才生效）。
 */
async function lockSelectedCell(nc: { pci: number; earfcn: number; rat: string }, index: number) {
  // 同一时刻只允许一个锁定动作在飞（按钮 pending + 互斥禁用，见模板）
  if (lockingIndex.value !== null) return;
  lockingIndex.value = index;
  try {
    // 入参是 network_type（LTE|NR），不是设备侧的 rat 字符串；收敛只有 toNetworkType 一处。
    // core 会校验取值域，非 LTE/NR 直接回 400 OUT_OF_RANGE 且不下发（DeviceRoutes.kt:640-644）。
    await props.lockCell({
      pci: String(nc.pci),
      earfcn: String(nc.earfcn),
      networkType: toNetworkType(nc.rat),
    });
    await loadCellInfo();
  } finally {
    lockingIndex.value = null;
  }
}

/** 全部解锁。同 [lockSelectedCell]：A 级确认器（判据 = 锁定名单清空）+ 收敛后回读一次。 */
async function unlockAllCells() {
  await props.unlockCells();
  await loadCellInfo();
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
/* 2026-10-05 布局重排：原来是 PCI/EARFCN/RSRP/按钮 挤一行靠 wrap 兜底，
   窄屏换行后按钮位置漂移、数字不对齐。改为固定两行结构：
   第一行 PCI + 制式徽标（右推锁定钮），第二行 EARFCN + RSRP（等宽数字对齐）。
   行内剩余空间交给 margin-right:auto 吸收，任何宽度下不再溢出。 */
.neighbor-item {
  display: flex;
  align-items: center;
  gap: 4px 12px;
  padding: 6px 0;
  border-bottom: 1px solid var(--border-subtle);
  font-size: 13px;
}
.neighbor-id {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}
.neighbor-pci {
  font-weight: 600;
  font-variant-numeric: tabular-nums;
}
.neighbor-rat {
  font-size: 10px;
  line-height: 1;
  padding: 2px 5px;
  border-radius: 4px;
  color: var(--text-muted);
  background: color-mix(in srgb, var(--text-muted) 14%, transparent);
}
.neighbor-rat--nr {
  color: var(--accent-color);
  background: color-mix(in srgb, var(--accent-color) 14%, transparent);
  font-weight: 600;
}
.neighbor-metrics {
  display: flex;
  align-items: center;
  gap: 12px;
  flex: 1;
  min-width: 0;
  color: var(--text-secondary);
  font-variant-numeric: tabular-nums;
}
.neighbor-lock {
  flex-shrink: 0;
  margin-left: auto;
}
/* RSRP 四档语义色走 A1 同波次新增的 --signal-*（取值见计划 §6.1）。
   不用 --success/--warning/--error：那三档会把 fair 与 weak 压成同一个色，
   四档信息就丢了。 */
.rsrp-good {
  color: var(--signal-good);
  font-weight: 600;
}
.rsrp-fair {
  color: var(--signal-fair);
}
.rsrp-weak {
  color: var(--signal-weak);
}
.rsrp-poor {
  color: var(--signal-poor);
}
.neighbor-item:last-child {
  border-bottom: none;
}
.hint-text {
  font-size: 13px;
  color: var(--text-muted);
}
</style>
