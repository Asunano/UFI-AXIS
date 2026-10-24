<!--
  资源中心（2026-10-14 新建，路由 `/resources`）。

  2026-10-23 职责收窄：**这里只负责插件组件**（frpc / cloudflared 等，`/api/components`）。
  Web 前端服务包与 core 的更新属于「服务」，统一在「设置 → 服务更新」
  （`UpdatePanel.vue`）；两边的卡片仍共用 `ResourceCard` / `useResourceTask`，
  但入口与文案必须分开，避免"资源中心里能更 core / 更新页里又跳资源中心"的来回绕。

  页面仍只做三件事：
  ① `v-for` 遍历 [COMPONENT_RESOURCES] 渲染 `ResourceCard`；
  ② 首屏为每条描述符读一次状态（`load()` 顺带 resume：读到忙态就自动续上轮询）；
  ③ 消费 runner 的一次性提示（toast）。
  插件之间的差异（端点、上传格式、进度语义、独有信息行）全在 `resourceRegistry.ts`。
-->
<template>
  <div class="resources-view">
    <header class="res-head">
      <h1>插件与组件</h1>
      <n-button size="small" tertiary @click="goServiceUpdates">前往服务更新</n-button>
    </header>

    <ResourceCard v-for="d in COMPONENT_RESOURCES" :key="d.id" :descriptor="d" />
  </div>
</template>

<script setup lang="ts">
import { onMounted, watch } from 'vue';
import { useMessage } from 'naive-ui';
import { useRouter } from 'vue-router';
import ResourceCard from './components/ResourceCard.vue';
import { COMPONENT_RESOURCES } from './resourceRegistry';
import { useResourceTask, type ResourceNotice } from './useResourceTask';

const message = useMessage();
const router = useRouter();
const task = useResourceTask();

/**
 * 在**渲染之前**把每条描述符的状态建出来（`stateOf` 惰性创建会写响应式对象，
 * 模板渲染期间首次调用会多触发一轮渲染）。
 */
for (const d of COMPONENT_RESOURCES) {
  task.stateOf(d.id);
}

function showNotice(n: ResourceNotice) {
  if (n.type === 'success') message.success(n.text);
  else if (n.type === 'error') message.error(n.text);
  else if (n.type === 'warning') message.warning(n.text);
  else message.info(n.text);
}

for (const d of COMPONENT_RESOURCES) {
  watch(
    () => task.stateOf(d.id).notice,
    (n) => {
      if (!n) return;
      showNotice(n);
      task.clearNotice(d.id);
    },
    { immediate: true }
  );
}

onMounted(() => {
  for (const d of COMPONENT_RESOURCES) {
    // load() 内部会在读到忙态时自动续上轮询（刷新页面 / 从别处发起的更新也能被接住）
    void task.load(d);
  }
});

/** 交叉入口：插件更新和服务更新是两个页面，别让用户在两页之间自己猜。 */
function goServiceUpdates() {
  router.push({ name: 'settings', query: { tab: 'update' } });
}
</script>

<style scoped>
.resources-view {
  display: flex;
  flex-direction: column;
  gap: 16px;
}
.res-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 0 2px 4px;
}
.res-head h1 {
  margin: 0;
  font-size: 24px;
  font-weight: 700;
  letter-spacing: -0.02em;
  color: var(--text-primary);
}
@media (max-width: 720px) {
  .res-head {
    flex-direction: column;
    align-items: flex-start;
    gap: 10px;
  }
  .res-head h1 {
    font-size: 21px;
  }
}
</style>
