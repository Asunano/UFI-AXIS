<template>
  <GridCard title="WiFi 热点" :loading="loading">
    <div class="kv-grid">
      <InfoRow label="状态">
        <!-- ui-baseline-allow: WiFi 热点卡的状态行是行内开关，放不下整行 ToggleRow -->
        <n-switch
          :value="wifiEnabled"
          size="small"
          :rubber-band="false"
          :aria-label="wifiEnabled ? '关闭 WiFi 热点' : '开启 WiFi 热点'"
          @update:value="emit('toggle-wifi', $event)"
        />
      </InfoRow>
      <InfoRow label="SSID" :value="settings?.ssid || '--'" />
      <InfoRow label="加密方式" :value="authModeLabel" />
      <InfoRow label="频段" :value="bandLabel" />
      <InfoRow label="密码" :value="settings?.password ? '••••••••' : '--'" />
      <InfoRow label="最大连接数" :value="maxStaNumText" />
      <InfoRow label="广播" :value="settings?.broadcast_disabled === 1 ? '隐藏（关闭广播）' : '可见'" />
      <InfoRow label="已连客户端" :value="`${clientCount} 台`" />
    </div>

    <template #footer>
      <n-button size="small" @click="emit('open-qr')">
        <template #icon
          ><n-icon><QrCodeOutline /></n-icon
        ></template>
        显示二维码
      </n-button>
      <n-button size="small" type="primary" @click="emit('edit')">修改设置</n-button>
    </template>
  </GridCard>
</template>

<script setup lang="ts">
import { computed } from 'vue';
import { QrCodeOutline } from '@vicons/ionicons5';
import InfoRow from '@/components/InfoRow.vue';
import GridCard from '@/components/GridCard.vue';
import { WifiBandOptions, wifiBandFromChipIndex, wifiSecurityOptions } from '@/api/contract';
import type { WifiSettings } from '@/types';

const props = defineProps<{
  loading: boolean;
  wifiEnabled: boolean;
  settings: WifiSettings | null;
  clientCount: number;
}>();

const emit = defineEmits<{
  'toggle-wifi': [boolean];
  'open-qr': [];
  edit: [];
}>();

/**
 * 加密方式：走 contract 的档位 label，**不直出 goform 原值**。
 * 设备回读的是 `WPA2PSK` / `WPA2PSKWPA3PSK` 这类参数名，直接上屏等于让用户看设备词汇；
 * 表外写法由 wifiSecurityOptions 置顶并入（label 会带「设备当前值」说明）。
 */
const authModeLabel = computed(() => {
  const cur = props.settings?.auth_mode;
  if (!cur) return '--';
  return wifiSecurityOptions(cur).find((o) => o.value === cur)?.label ?? cur;
});

/**
 * 频段：`chip_index`（读侧展示字段 '1'/'2'）→ `WifiBand` → 文案。
 * 判据与写侧的 `wifiBandFromChipIndex` 同一份，本卡不再各写一遍 '1'/'2' → MHz 的映射。
 * chip_index 缺失/越界时显示 `--`，不拿兜底频段编一个值出来。
 */
const bandLabel = computed(() => {
  const idx = props.settings?.chip_index;
  if (idx !== '1' && idx !== '2') return '--';
  const band = wifiBandFromChipIndex(idx);
  return WifiBandOptions.find((o) => o.value === band)?.label ?? '--';
});

/** 最大连接数：0 与空值是两回事（家规第 5 条），只有 null / undefined 才显示 `--`。 */
const maxStaNumText = computed(() => (props.settings?.max_sta_num != null ? String(props.settings.max_sta_num) : '--'));
</script>

<style scoped>
/* KV 栅格：8 项自动铺成 2~4 列。
   下限 260px 是按两个已知宽度定的 —— 通栏（~1150px 内容宽）时刚好 4 列 → 8 项 2 行；
   仍是半宽（~550px）时回落 2 列 → 4 行，与 2026-09-10 之前的观感一致。
   写死 repeat(2, ...) 的话通栏会退化成 4 行，卡片反而变高。 */
.kv-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(260px, 1fr));
  gap: 0 20px;
}
</style>
