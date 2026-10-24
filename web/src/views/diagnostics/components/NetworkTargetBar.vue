<!--
  网络诊断的共用目标（2026-10-14 新建）。

  ## 为什么是一个"组的工具栏"而不是每张卡片各带输入框
  四条工具（ping / DNS / TCP / traceroute）回答的是**同一个目标**的不同侧面 ——
  旧 `DiagnosticModal` 就是一个 host 输入框管四个按钮。做成每卡一份输入框之后，
  "在 ping 里填的地址"和"在 traceroute 里填的地址"会变成两个独立的值，
  用户点第二条时还得再填一遍（而且很容易填成不一样的两个目标，结论就没法互相印证）。

  它由 `DIAGNOSTIC_GROUPS` 的 `toolbar` 字段声明，页面用 `<component :is>` 渲染 ——
  这样"网络组需要输入"这件事写在注册表里，**页面不认识 group 的语义**。

  ## 状态为什么不在这里
  输入值住在 `networkTarget.ts` 的 `networkTarget`（模块作用域），因为描述符的
  `run()` 必须读到它，而描述符拿不到组件实例。本组件只是那份状态的一个受控视图。
  它**刻意不放在 `diagnosticRegistry.ts` 里**：那个文件要 import 本组件（本组件是网络组的
  `toolbar`），本组件又要读那份状态 —— 放在一起就是循环 import（理由见 `networkTarget.ts`）。
-->
<template>
  <div class="ntb sub-panel">
    <n-input
      v-model:value="networkTarget.host"
      class="ntb-host"
      placeholder="目标地址（域名或 IP，如 8.8.8.8）"
      clearable
    />
    <n-input-number v-model:value="networkTarget.port" class="ntb-port" :min="1" :max="65535" placeholder="端口" />
    <!-- 端口只被 TCP 探测读走（core 侧 ping / dns / traceroute 都不吃它）——
         不说清楚的话，用户会以为改端口会影响其它三条。 -->
    <span class="ntb-note">端口只被 TCP 探测使用；其余三条只吃目标地址。</span>
  </div>
</template>

<script setup lang="ts">
import { networkTarget } from '../networkTarget';
</script>

<style scoped>
/* 描边/内距/圆角/底色走 main.css 的全局 .sub-panel */
.ntb {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--space-2) var(--space-3);
  margin-bottom: var(--space-3);
}
/* flex: 1 1 240px：宽屏下地址框吃掉剩余宽度，窄屏（卡内 ~298px）自动换行到整行 */
.ntb-host {
  flex: 1 1 240px;
}
.ntb-port {
  flex: 0 0 130px;
}
.ntb-note {
  flex: 1 1 100%;
  font-size: var(--font-sm);
  color: var(--text-muted);
}
</style>
