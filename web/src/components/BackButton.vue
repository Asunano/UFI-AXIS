<template>
  <n-button size="small" quaternary class="back-btn" @click="goBack">
    <template #icon>
      <n-icon :size="15"><ChevronBackOutline /></n-icon>
    </template>
    {{ label }}
  </n-button>
</template>

<script setup lang="ts">
/**
 * 页面内返回按钮（2026-11-10）。
 *
 * 定时任务 / 内网穿透 / 资源中心原来靠路由 `meta.back` 让壳层在全局顶栏渲染返回键；
 * 现在这三页和话费账单一样，返回与标题由页面自己在内容区承担。
 *
 * 抽成组件是为了三处共用同一套「站内有没有上一页」判据，避免各写一份后漂移：
 *   · 有站内上一页 → `router.back()`，保留上一页的滚动位置与状态；
 *   · 直接打开链接 / 刷新（`history.state.back` 为空）→ 回落到设置页。
 *
 * 用 `history.state.back` 而不是 `history.length > 1`：外部链接打开时 length 也可能 > 1，
 * 那样 `router.back()` 会把用户带出应用（与 DefaultLayout 的 goBack 同一判据）。
 */
import { useRouter } from 'vue-router';
import { ChevronBackOutline } from '@vicons/ionicons5';

const props = withDefaults(defineProps<{ label?: string; fallback?: string }>(), {
  label: '返回',
  fallback: 'settings',
});

const router = useRouter();

function goBack(): void {
  const hasInAppPrev = !!(window.history.state as { back?: string | null } | null)?.back;
  if (hasInAppPrev) router.back();
  else void router.push({ name: props.fallback });
}
</script>

<style scoped>
.back-btn {
  flex: none;
}
</style>
