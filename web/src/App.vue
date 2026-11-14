<template>
  <n-config-provider :theme="theme" :theme-overrides="themeOverrides" :locale="zhCN" :date-locale="dateZhCN">
    <n-notification-provider>
      <n-message-provider>
        <n-dialog-provider>
          <InsecureAccessNotice />
          <!-- 主人密码输入框：core 的 OwnerGate 要求密码时由 useApi 拦截器弹出（默认关，不会出现） -->
          <OwnerPasswordDialog />
          <router-view />
        </n-dialog-provider>
      </n-message-provider>
    </n-notification-provider>
  </n-config-provider>

  <!--
    致命错误浮层（2026-10-30）。刻意放在 provider **之外**：它不依赖 naive 的主题与注入，
    而触发它的场景恰恰可能是 provider 链路上某个组件崩了。位置在这里（而不是 main.ts 里
    createApp 另一个应用）是因为它要读同一个 Vue 应用里的响应式状态（`composables/globalError`）。
  -->
  <FatalErrorOverlay />
</template>

<script setup lang="ts">
import { computed } from 'vue';
import { darkTheme, zhCN, dateZhCN } from 'naive-ui';
import { useAppStore } from '@/stores/app';
import { useNaiveThemeOverrides } from '@/composables/naiveTheme';
import InsecureAccessNotice from '@/components/InsecureAccessNotice.vue';
import OwnerPasswordDialog from '@/components/OwnerPasswordDialog.vue';
import FatalErrorOverlay from '@/components/FatalErrorOverlay.vue';

const appStore = useAppStore();
const theme = computed(() => (appStore.darkMode ? darkTheme : null));

// 让 naive 的语义色跟 main.css 的 CSS 变量走。这是全站唯一的 provider 级 themeOverrides；
// DefaultLayout 里那个 :theme-overrides 只作用于 n-menu 的矮屏行高，与配色无关。
const themeOverrides = useNaiveThemeOverrides();
</script>
