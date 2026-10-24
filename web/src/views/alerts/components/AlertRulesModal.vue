<!--
  告警「规则设置」弹窗（2026-10-22）。

  为什么是弹窗：告警页原先用「列表 / 配置」两个 segment 页签把两件不同频率的事并列在页头。
  现在列表常驻、规则收进这一层，入口是页头的「规则设置」按钮。

  内容直接复用 `../panels/AlertConfigPanel.vue`（原「配置」页签的整块面板），
  所以总闸、分类、阈值、通知渠道的字段、默认值、两种保存时机（总闸/阈值要保存、开关即时保存）
  以及 409 版本守门**一条都没改**。这里只提供弹窗壳，不碰它的任何逻辑。

  面板走 defineAsyncComponent：只在第一次打开弹窗时下载，列表首屏不背这份表单的 chunk。
  弹窗内容随关闭销毁（n-modal 默认 display-directive="if"），面板的 onMounted 只在打开时
  各拉一次配置；列表在弹窗之外，开关弹窗不会影响它的筛选、分页与已加载数据。
-->
<template>
  <n-modal
    :show="show"
    preset="card"
    title="规则设置"
    closable
    class="modal-fit"
    style="--modal-w: 880px; --modal-h: 840px"
    @update:show="emit('update:show', $event)"
  >
    <component :is="AlertConfigPanel" />
  </n-modal>
</template>

<script setup lang="ts">
import { defineAsyncComponent } from 'vue';

defineProps<{ show: boolean }>();
const emit = defineEmits<{ 'update:show': [boolean] }>();

const AlertConfigPanel = defineAsyncComponent(() => import('../panels/AlertConfigPanel.vue'));
</script>
