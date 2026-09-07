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
        <template #icon><n-icon><QrCodeOutline /></n-icon></template>
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
  'edit': [];
}>();
</script>

<style scoped>
/* 2 列 KV：8 项 → 4 行 × 2 列。
   删原 768 二次重排（断点统一由 Slice 2 处理）。 */
.kv-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 0 20px;
}
</style>
