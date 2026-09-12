<template>
  <!-- WiFi 与局域网设置都走弹窗，不在概览里铺开 -->
  <GridCard class="panel-card" title="WiFi 信息">
    <template #extra>
      <!-- 开关即状态：不再另放一个「已开启/已关闭」的 tag，避免两处说同一件事却可能不一致 -->
      <n-switch :value="wifiEnabled" size="small" @update:value="(v: boolean) => emit('toggle-enabled', v)" />
      <n-button size="small" quaternary type="primary" @click="emit('open-settings')">WiFi 设置</n-button>
    </template>

    <!--
      信息与二维码左右并排：二维码是这张卡最常用的东西，占右侧固定列，
      信息列自适应剩余宽度（窄屏回落成上下，见 @media）。
      图不能直接 <img src="/api/wifi/qrcode">：img 请求不带 Authorization 头会被 core 拦掉，
      所以走 composable 里注入 Bearer 的 axios 取 blob 再转 object URL。
    -->
    <div class="wifi-layout">
      <div class="wifi-info">
        <div v-if="wifiSettings" class="stat-grid wifi-stat-grid">
          <div class="stat-cell">
            <span class="stat-label">SSID</span>
            <span class="stat-value">{{ wifiSettings.ssid || '--' }}</span>
          </div>
          <div class="stat-cell">
            <span class="stat-label">加密方式</span>
            <span class="stat-value">{{ wifiAuthText }}</span>
          </div>
          <div class="stat-cell">
            <span class="stat-label">频段</span>
            <span class="stat-value">{{ wifiSettings.chip_index === '2' ? '5 GHz' : '2.4 GHz' }}</span>
          </div>
          <div class="stat-cell">
            <span class="stat-label">SSID 广播</span>
            <span class="stat-value">{{ wifiSettings.broadcast_disabled ? '已隐藏' : '开启' }}</span>
          </div>
          <div class="stat-cell">
            <span class="stat-label">最大接入数</span>
            <span class="stat-value">{{ wifiSettings.max_sta_num || '--' }}</span>
          </div>
        </div>
        <div v-else class="client-empty">WiFi 信息读取失败</div>
      </div>

      <div class="wifi-qr">
        <img v-if="qrUrl" class="wifi-qr-img" :src="qrUrl" alt="WiFi 二维码" />
        <n-spin v-else-if="qrLoading" size="small" />
        <span v-else class="wifi-qr-hint">{{ qrError || '二维码不可用' }}</span>
        <span v-if="qrUrl" class="wifi-qr-hint">扫码连接 {{ wifiSettings?.ssid || 'WiFi' }}</span>
      </div>
    </div>
  </GridCard>
</template>

<script setup lang="ts">
/**
 * WiFi 信息卡（含卡内二维码）。
 *
 * 纯展示：WiFi 设置、开关状态与二维码 blob 全部由 `useNetworkControls()` 持有，
 * 而那个 composable 每次调用都新建一套 ref（不是模块级单例）——
 * 在这里再调一次会拿到第二份互不相干的状态，父组件轮询刷新的永远不是这一份。
 * 二维码的 object URL 也必须由持有方 revoke，所以这里连 qrUrl 都只读不建。
 */
import { computed } from 'vue';
import GridCard from '@/components/GridCard.vue';
import type { WifiSettings } from '@/types';

const props = defineProps<{
  wifiSettings: WifiSettings | null;
  wifiEnabled: boolean;
  qrUrl: string;
  qrLoading: boolean;
  qrError: string;
}>();

const emit = defineEmits<{
  (e: 'toggle-enabled', enabled: boolean): void;
  (e: 'open-settings'): void;
}>();

/** goform 的 auth_mode / encryp_type 原样拼接；两者都为空或 NONE 视为开放网络 */
const wifiAuthText = computed(() => {
  const s = props.wifiSettings;
  if (!s) return '--';
  const parts = [s.auth_mode, s.encryp_type]
    .map((v) => String(v ?? '').trim())
    .filter((v) => v && v.toUpperCase() !== 'NONE');
  return parts.length ? parts.join(' / ') : '开放';
});
</script>

<style scoped>
/* ── 左信息 / 右二维码 ── */
.wifi-layout {
  display: flex;
  align-items: flex-start;
  gap: 20px;
}
.wifi-info {
  /* min-width:0 是必须的：里面是 grid，不给它就会被最长的 SSID 撑出卡片 */
  flex: 1 1 auto;
  min-width: 0;
}
/* 信息列只剩半卡宽，全局 .stat-grid 的 auto-fit(120px) 会挤成 1 列。
   固定两列更稳：五个字段排成 3 行，高度刚好和右侧二维码相当。
   scoped 选择器 (0,2,0) 压过 main.css 里的 .stat-grid (0,1,0)，与源码顺序无关。 */
.wifi-stat-grid {
  grid-template-columns: repeat(2, minmax(0, 1fr));
}

/* ── WiFi 二维码（卡内右侧固定列，不走弹窗）── */
.wifi-qr {
  flex: 0 0 auto;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6px;
}
.wifi-qr-img {
  width: 140px;
  max-width: 100%;
  height: auto;
  display: block;
  border-radius: var(--radius-sm);
  /* 与 components/modals/QrModal.vue 同一条豁免：二维码底色必须是纯白，
     接 --card-bg 会让暗色下扫不出来。 */
  background: #fff;
}
.wifi-qr-hint {
  font-size: 12px;
  color: var(--text-muted);
  text-align: center;
}

@media (max-width: 768px) {
  /* 窄屏放不下左右两列：回落成「信息在上、二维码在下」，
     二维码这时可以放大一点 —— 它是整张卡里最有用的东西 */
  .wifi-layout {
    flex-direction: column;
    gap: 12px;
  }
  .wifi-qr {
    align-self: center;
    padding-top: 12px;
    border-top: 1px solid var(--border-subtle);
    width: 100%;
  }
  .wifi-qr-img {
    width: 168px;
  }
}
</style>
