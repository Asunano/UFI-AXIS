<template>
  <n-modal v-model:show="show" preset="card" title="频段锁定" style="width: 480px; max-width: calc(100vw - 32px)">
    <n-spin :show="bandLoading">
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
    </n-spin>
    <template #footer>
      <div class="modal-footer">
        <n-button size="small" @click="show = false">关闭</n-button>
        <n-button size="small" :loading="bandLoading" @click="unlockBands">全部解锁</n-button>
        <n-button size="small" type="primary" :loading="bandLoading" @click="applyBandLock">应用锁定</n-button>
      </div>
    </template>
  </n-modal>
</template>

<script setup lang="ts">
import { ref, computed, watch } from 'vue';
import { useMessage } from 'naive-ui';
import { getApiClient } from '@/composables/useApi';

const props = defineProps<{ show: boolean }>();
const emit = defineEmits<{ 'update:show': [boolean] }>();

const show = computed({
  get: () => props.show,
  set: (v) => emit('update:show', v),
});

const message = useMessage();
const api = getApiClient();
const settleDelay = () => new Promise((resolve) => setTimeout(resolve, 600));

// 设备 /api/network/band-status 回的是契约字段 lte_band_lock / nr_band_lock（已过 allowlist）。
// 值是纯数字列表（如 "1,3,5"），'0'/'all' 表示未锁定；
// POST /api/network/band 的 lte_bands/nr_bands 同样是纯数字（core 的 WriteSpec 会挡住非数字字符）。
// 因此内部统一用不带前缀的数字，UI 上再补 B/N 前缀显示。
const LTE_BAND_PRESET = ['1', '3', '5', '8', '34', '38', '39', '40', '41'];
const NR_BAND_PRESET = ['1', '5', '8', '28', '41', '78'];
const selectedLteBands = ref(new Set<string>());
const selectedNrBands = ref(new Set<string>());
const bandLoading = ref(false);

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

async function applyBandLock() {
  bandLoading.value = true;
  try {
    const lte = [...selectedLteBands.value].join(',');
    const nr = [...selectedNrBands.value].join(',');
    await api.post('/api/network/band', {
      action: 'lock',
      lte_bands: lte || undefined,
      nr_bands: nr || undefined,
    });
    message.success('频段锁定已应用');
    // 设备写入到查询接口可见有延迟，立刻回读会拿到旧值
    await settleDelay();
    loadBandStatus();
  } catch {
    message.error('应用失败');
    await settleDelay();
    loadBandStatus();
  } finally {
    bandLoading.value = false;
  }
}

async function unlockBands() {
  bandLoading.value = true;
  try {
    await api.post('/api/network/band', { action: 'unlock' });
    message.success('频段已解锁');
  } catch {
    message.error('解锁失败');
  } finally {
    bandLoading.value = false;
    // 解锁结果同样以设备回读为准，等设备把新状态刷出来再取
    await settleDelay();
    loadBandStatus();
  }
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
