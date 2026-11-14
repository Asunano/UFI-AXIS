<template>
  <n-modal v-model:show="show" preset="card" title="频段锁定" style="width: 480px; max-width: calc(100vw - 32px)">
      <div class="band-section">
        <div class="band-group">
          <span class="band-group-label">LTE</span>
          <n-flex wrap>
            <n-tag
              v-for="b in lteBandList"
              :key="b"
              :checked="selectedLteBands.has(b)"
              checkable
              size="small"
              :bordered="false"
              @update:checked="toggleLteBand(b)"
              >B{{ b }}</n-tag
            >
          </n-flex>
        </div>
        <div class="band-group">
          <span class="band-group-label">NR</span>
          <n-flex wrap>
            <n-tag
              v-for="b in nrBandList"
              :key="b"
              :checked="selectedNrBands.has(b)"
              checkable
              size="small"
              :bordered="false"
              @update:checked="toggleNrBand(b)"
              >N{{ b }}</n-tag
            >
          </n-flex>
        </div>
      </div>
    <template #footer>
      <div class="modal-footer">
        <n-button size="small" @click="show = false">关闭</n-button>
        <n-button size="small" @click="unlockBands">全部解锁</n-button>
        <n-button size="small" type="primary" @click="applyBandLock">应用锁定</n-button>
      </div>
    </template>
  </n-modal>
  <!-- 2026-10-05 P3 修复：频段锁定是"发射后不管"最严重的写操作，接入通用确认器进度弹窗 -->
  <OperationProgressModal
    :state="op.state.value"
    title="频段锁定"
    running-text="正在应用频段设置"
    success-text="频段设置已完成"
    hint="网络栈会重启，期间信号短暂中断，属正常现象"
    :fail-text="op.failReason.value"
    :retry="opRetry"
    :close="op.close"
    :to-background="op.toBackground"
  />
</template>

<script setup lang="ts">
import { ref, computed, watch } from 'vue';
import { getApiClient } from '@/composables/useApi';
// 2026-10-05 P3 修复：删除 message/settleDelay，改用 useApiOperation 确认器（自带首延与轮询节奏）
import { useApiOperation } from '@/composables/useApiOperation';
import OperationProgressModal from '@/components/common/OperationProgressModal.vue';

const props = defineProps<{ show: boolean }>();
const emit = defineEmits<{ 'update:show': [boolean] }>();

const show = computed({
  get: () => props.show,
  set: (v) => emit('update:show', v),
});

const api = getApiClient();

// 2026-10-05 P3 修复：接入 useApiOperation 确认器（原 settleDelay 600ms 单次回读改为轮询确认）
// 并发消息通道：toast 改由确认器发，不再直接用 useMessage（E-8 单一发声源）
const op = useApiOperation();
/** 失败态"重试"按钮动作：记录最近一次用户触发的操作 */
const opRetry = ref<() => void>(() => {});

// 设备 /api/network/band-status 回的是契约字段 lte_band_lock / nr_band_lock（已过 allowlist）。
// 值是纯数字列表（如 "1,3,5"），'0'/'all' 表示未锁定；
// POST /api/network/band 的 lte_bands/nr_bands 同样是纯数字（core 的 WriteSpec 会挡住非数字字符）。
// 因此内部统一用不带前缀的数字，UI 上再补 B/N 前缀显示。
const LTE_BAND_PRESET = ['1', '3', '5', '8', '34', '38', '39', '40', '41'];
const NR_BAND_PRESET = ['1', '5', '8', '28', '41', '78'];
const selectedLteBands = ref(new Set<string>());
const selectedNrBands = ref(new Set<string>());

// 设备实际锁定的频段可能不在预设列表里，合并展示，避免"应用锁定"时把它静默丢弃
function mergeBands(preset: string[], selected: Set<string>): string[] {
  return [...new Set([...preset, ...selected])].sort((a, b) => Number(a) - Number(b));
}
const lteBandList = computed(() => mergeBands(LTE_BAND_PRESET, selectedLteBands.value));
const nrBandList = computed(() => mergeBands(NR_BAND_PRESET, selectedNrBands.value));

function toggleLteBand(b: string) {
  const s = new Set(selectedLteBands.value);
  if (s.has(b)) {
    s.delete(b);
  } else {
    s.add(b);
  }
  selectedLteBands.value = s;
}
function toggleNrBand(b: string) {
  const s = new Set(selectedNrBands.value);
  if (s.has(b)) {
    s.delete(b);
  } else {
    s.add(b);
  }
  selectedNrBands.value = s;
}

function parseBands(raw: string): Set<string> {
  if (!raw || raw === '0' || raw === 'all') return new Set();
  return new Set(
    raw
      .split(',')
      .map((s) => s.trim().replace(/^[BbNn]/, ''))
      .filter(Boolean)
  );
}

async function loadBandStatus() {
  try {
    const { data } = await api.get('/api/network/band-status');
    selectedLteBands.value = parseBands(data.lte_band_lock);
    selectedNrBands.value = parseBands(data.nr_band_lock);
  } catch {
    /* 静默 */
  }
}

/** band-status 回读与目标集合是否一致（parse 后比集合，不比字符串顺序——"1,3" 与 "3,1" 等价） */
// 2026-10-05 P3 修复：集合相等判定替代字符串比较，抗回读抖动与顺序差异
function bandsMatch(raw: string | undefined, target: Set<string>): boolean {
  const current = parseBands(raw || '');
  if (current.size !== target.size) return false;
  for (const b of target) if (!current.has(b)) return false;
  return true;
}

async function applyBandLock() {
  opRetry.value = applyBandLock;
  const lte = [...selectedLteBands.value];
  const nr = [...selectedNrBands.value];
  await op.run({
    title: '频段锁定',
    running: '正在应用频段设置',
    success: lte.length || nr.length ? '频段锁定已应用' : '频段已解锁',
    hint: '网络栈会重启，期间信号短暂中断，属正常现象',
    submit: () => api.post('/api/network/band', {
      action: 'lock',
      lte_bands: lte.join(',') || undefined,
      nr_bands: nr.join(',') || undefined,
    }),
    verify: async () => {
      const { data } = await api.get('/api/network/band-status');
      return bandsMatch(data.lte_band_lock, selectedLteBands.value)
          && bandsMatch(data.nr_band_lock, selectedNrBands.value);
    },
    onApplied: () => { loadBandStatus(); emit('update:show', true); },  // 回到选择弹窗展示真实状态
    retry: () => opRetry.value(),
  });
}

async function unlockBands() {
  opRetry.value = unlockBands;
  await op.run({
    title: '频段锁定',
    running: '正在解锁全部频段',
    success: '频段已解锁',
    hint: '网络栈会重启，期间信号短暂中断，属正常现象',
    submit: () => api.post('/api/network/band', { action: 'unlock' }),
    verify: async () => {
      const { data } = await api.get('/api/network/band-status');
      return parseBands(data.lte_band_lock || '').size === 0
          && parseBands(data.nr_band_lock || '').size === 0;
    },
    onApplied: () => { loadBandStatus(); emit('update:show', true); },
    retry: () => opRetry.value(),
  });
}

// 打开即按设备当前锁定状态刷新选择器
watch(show, (v) => {
  if (v) loadBandStatus();
});
</script>

<style scoped>
.modal-footer {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}
.band-section {
  display: flex;
  flex-direction: column;
  gap: 14px;
}
.band-group {
  display: flex;
  align-items: flex-start;
  gap: 12px;
}
.band-group-label {
  font-size: 13px;
  font-weight: 600;
  color: var(--text-secondary);
  min-width: 32px;
  padding-top: 4px;
}
</style>
