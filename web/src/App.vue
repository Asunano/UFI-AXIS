<template>
  <n-config-provider :theme="theme" :theme-overrides="themeOverrides" :locale="zhCN" :date-locale="dateZhCN">
    <n-notification-provider>
      <n-message-provider>
        <n-dialog-provider>
          <InsecureAccessNotice />
          <router-view />
        </n-dialog-provider>
      </n-message-provider>
    </n-notification-provider>
  </n-config-provider>
</template>

<script setup lang="ts">
import { computed } from 'vue';
import { darkTheme, zhCN, dateZhCN } from 'naive-ui';
import { useAppStore } from '@/stores/app';
import { useNaiveThemeOverrides } from '@/composables/naiveTheme';
import InsecureAccessNotice from '@/components/InsecureAccessNotice.vue';

const appStore = useAppStore();
const theme = computed(() => (appStore.darkMode ? darkTheme : null));

// 让 naive 的语义色跟 main.css 的 CSS 变量走。这是全站唯一的 provider 级 themeOverrides；
// DefaultLayout 里那个 :theme-overrides 只作用于 n-menu 的矮屏行高，与配色无关。
const themeOverrides = useNaiveThemeOverrides();
</script>
