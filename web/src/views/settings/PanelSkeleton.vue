<template>
  <!--
    设置面板的加载占位（2026-10-22）。

    为什么要它：设置页的 11 个面板各是一个独立 chunk，点开入口时弹窗会先出现、
    正文却要等 chunk 到手（设备 WiFi 下 150–300ms）。配 `delay: 0` 的 loadingComponent
    以后，这 200ms 显示的是"正在加载的形状"，而不是一张没有内容的空卡片。

    形状刻意贴近面板首屏：两行说明文字（对应卡头/说明）+ 一块方块（对应第一张卡）。
    换入真内容时高度大致相当，不会把弹窗从矮拉高。
    aria-hidden：骨架只是视觉占位，读屏应当直接读真内容（真内容到达后本组件即被替换）。
  -->
  <div class="panel-skeleton" aria-hidden="true">
    <n-skeleton text :repeat="2" />
    <n-skeleton text class="short" />
    <n-skeleton height="132px" :sharp="false" class="block" />
  </div>
</template>

<script setup lang="ts">
// 无脚本逻辑：纯占位。用 n-skeleton 而不是自绘灰条，是为了跟 GridCard 的
// `:loading` 占位保持同一套观感（底色/圆角/动画都来自 naive 的主题）。
</script>

<style scoped>
.panel-skeleton {
  display: flex;
  flex-direction: column;
  gap: var(--space-3);
  /* 与真实面板首屏的内距对齐：面板是 GridCard 正文（12/16/16），这里不额外加内距，
     否则骨架比内容"往里缩"一圈，换入时会跳一下 */
  padding: 0;
}
/* 第三行只占 60%：模拟"一段说明的最后一行"，也让骨架看起来不像表格 */
.short {
  width: 60%;
}
.block {
  border-radius: var(--radius-md);
}
</style>
