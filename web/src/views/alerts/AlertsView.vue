<template>
  <div class="alerts-shell">
    <!--
      分类导航与「设置」页同款：naive-ui 的 segment 分段标签 + defineAsyncComponent 面板。
      n-tab-pane 默认 display-directive="if"：非激活分栏不渲染、切走即销毁，
      每个面板在自己的 onMounted 里只拉自己的数据，列表面板的 15s 轮询在切走时由
      useInterval 自动 stop（见 useRealtime），不会泄漏定时器。
      不用 animated：面板高度差很大、又是异步组件，过渡期间会先塌成一小块再撑开。
      改用面板自己的淡入上浮（见 :deep(.n-tab-pane) 的 pane-in）。
    -->
    <n-tabs type="segment" :value="activeKey" @update:value="select">
      <n-tab-pane v-for="c in CATEGORIES" :key="c.key" :name="c.key" :tab="c.label">
        <header class="pane-head">
          <h2 class="pane-title">{{ c.label }}</h2>
          <p class="pane-desc">{{ c.desc }}</p>
        </header>
        <component :is="c.component" />
      </n-tab-pane>
    </n-tabs>
  </div>
</template>

<script setup lang="ts">
import { ref, defineAsyncComponent } from 'vue';

interface Category {
  key: string;
  label: string;
  desc: string;
  component: unknown;
}

// 面板全部走 defineAsyncComponent：告警页首屏默认只加载「列表」面板（用户打开告警
// 通常是为了看「现在有什么问题」），「配置」这类表单面板点开才加载，不进主 chunk。
const CATEGORIES: Category[] = [
  {
    key: 'list',
    label: '列表',
    desc: '当前与历史告警、确认与恢复状态',
    component: defineAsyncComponent(() => import('./panels/AlertListPanel.vue')),
  },
  {
    key: 'config',
    label: '配置',
    desc: '告警阈值、启用开关与通知渠道',
    component: defineAsyncComponent(() => import('./panels/AlertConfigPanel.vue')),
  },
];

const activeKey = ref('list');

function select(key: string) {
  activeKey.value = key;
}
</script>

<style scoped>
.alerts-shell {
  padding: 0;
}

/* 切分栏时面板淡入并轻微上浮。只动 opacity/transform，不参与布局，
   所以不会像 n-tabs 的 animated 那样在高度变化时闪一下。 */
.alerts-shell :deep(.n-tab-pane) {
  animation: pane-in 0.22s cubic-bezier(0.4, 0, 0.2, 1);
}
@keyframes pane-in {
  from {
    opacity: 0;
    transform: translateY(4px);
  }
  to {
    opacity: 1;
    transform: none;
  }
}

.pane-head {
  margin-bottom: 16px;
  padding-bottom: 12px;
  border-bottom: 1px solid var(--border-subtle);
}
.pane-title {
  margin: 0;
  font-size: 16px;
  font-weight: 600;
  color: var(--text-primary);
}
.pane-desc {
  margin: 4px 0 0;
  font-size: 13px;
  color: var(--text-muted);
}
</style>
