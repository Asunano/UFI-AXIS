<template>
  <div class="alerts-shell">
    <!--
      2026-10-22：去掉「列表 / 配置」两个内嵌页签。
      告警页的绝大多数访问是「现在有什么问题」，规则（总闸 / 分类 / 阈值 / 通知渠道）是低频操作；
      原来两件事并列摆在页头，用户每看一次告警都得先确认自己在哪个页签。现在页面只有列表，
      规则收进页头按钮打开的弹窗（AlertRulesModal → 复用 panels/AlertConfigPanel.vue）。
      弹窗是列表的兄弟节点：开关弹窗只挂载/销毁弹窗内容，列表面板（筛选、分页、已加载告警、
      15s 轮询）不受影响。
    -->
    <header class="alerts-head">
      <div class="head-text">
        <h2 class="head-title">告警</h2>
        <span class="head-sub">当前与历史告警、确认与恢复状态</span>
      </div>
      <n-button size="small" type="primary" class="head-action" @click="rulesOpen = true"> 规则设置 </n-button>
    </header>

    <!-- 列表仍走 defineAsyncComponent：首屏只加载它，配置表单只随弹窗下载，不进主 chunk -->
    <component :is="AlertListPanel" />

    <AlertRulesModal v-model:show="rulesOpen" />
  </div>
</template>

<script setup lang="ts">
import { ref, defineAsyncComponent } from 'vue';
import AlertRulesModal from './components/AlertRulesModal.vue';

const AlertListPanel = defineAsyncComponent(() => import('./panels/AlertListPanel.vue'));

const rulesOpen = ref(false);
</script>

<style scoped>
.alerts-shell {
  display: flex;
  flex-direction: column;
  gap: var(--space-4);
  padding: 0;
}

/* 页头：标题 / 说明在左，「规则设置」在右。
   `flex-wrap` + 标题 `flex-shrink: 0` 是给窄屏的：放不下时整块按钮换到第二行，
   既不挤压标题，也不会像等分 flex 那样把按钮压出可视区。 */
.alerts-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: var(--space-2) var(--space-3);
}
.head-text {
  display: flex;
  align-items: baseline;
  gap: var(--space-2);
  min-width: 0;
}
.head-title {
  flex-shrink: 0;
  margin: 0;
  font-size: var(--font-xl);
  font-weight: 700;
  color: var(--text-primary);
}
/* 说明在窄屏只做省略：它比标题次要，不该为了露全而把标题/按钮挤走 */
.head-sub {
  min-width: 0;
  font-size: var(--font-sm);
  color: var(--text-muted);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
/* space-between 在单行时就把按钮推到最右；换行后单行只有它一个，
   靠 margin-left: auto 仍然贴右（否则 space-between 会把它留在左侧） */
.head-action {
  flex-shrink: 0;
  margin-left: auto;
}

@media (max-width: 768px) {
  /* 360px 下「标题 + 说明 + 按钮」一行放不下：说明独占一行，按钮换到第二行右对齐。
     按钮尺寸不动（small 保证可点面积），标题也不参与收缩。 */
  .head-text {
    flex: 1 1 100%;
  }
  .head-title {
    font-size: var(--font-lg);
  }
}
</style>
