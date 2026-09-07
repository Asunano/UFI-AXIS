<template>
  <n-config-provider :theme="theme" :locale="zhCN" :date-locale="dateZhCN">
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
import InsecureAccessNotice from '@/components/InsecureAccessNotice.vue';

const appStore = useAppStore();
const theme = computed(() => (appStore.darkMode ? darkTheme : null));
</script>
