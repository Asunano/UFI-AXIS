<template>
  <GridCard title="WiFi 热点" :loading="loading">
    <div class="kv-grid">
      <InfoRow label="状态" :value="wifiEnabled ? '已开启' : '已关闭'" />
      <InfoRow label="SSID" :value="settings?.ssid || '--'" />
      <InfoRow label="加密方式" :value="settings?.auth_mode || '--'" />
      <InfoRow
        label="频段"
        :value="settings?.chip_index === '1' ? '2.4 GHz' : settings?.chip_index === '2' ? '5 GHz' : '--'"
      />
      <InfoRow label="密码" :value="settings?.password ? '••••••••' : '--'" />
      <InfoRow label="最大连接数" :value="String(settings?.max_sta_num || '--')" />
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
import { QrCodeOutline } from '@vicons/ionicons5';
import InfoRow from '@/components/InfoRow.vue';
import GridCard from '@/components/GridCard.vue';
import type { WifiSettings } from '@/types';

defineProps<{
  loading: boolean;
  wifiEnabled: boolean;
  settings: WifiSettings | null;
  clientCount: number;
}>();

const emit = defineEmits<{
  'open-qr': [];
  edit: [];
}>();
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
