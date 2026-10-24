<!--
  全局音频播放器的**唯一** `<audio>` 宿主（2026-10-14）。

  ──── 为什么要有这么一个"没有 UI 的组件" ────
  播放状态搬进 `stores/player.ts` 之后，还需要一个地方真正把 `<audio>` 挂在 DOM 上。
  这个元素必须比页面活得久，所以挂在 `DefaultLayout` 里 —— 而 store 里是不能放 DOM 的
  （Pinia 的 store 会被 devtools 序列化、也不该持有节点）。

  于是职责切成两半：**store 管状态与取数，本组件管元素的生命周期**。
  它渲染的 `<audio>` 没有 `controls`：走带由音乐页播放条与顶栏迷你条接管，
  三套控件同时存在会让"暂停"有三个真源。

  ──── 为什么不用 `new Audio()` ────
  那样元素不在 DOM 树里，浏览器对"脱离文档的媒体元素"在某些移动端浏览器上
  会做更激进的省电降级（后台标签页直接冻住）。挂在 DOM 里并 `preload="metadata"`
  是最稳的一条。
-->
<template>
  <!-- 没有 controls、不设 src：src 由 store 换票后写入。
       刻意不加 crossorigin：不加就是"no-cors"语义的普通媒体请求，跨源也能放；
       加上它会要求 core 回 CORS 头，而这个元素根本不需要读字节（不接 Web Audio），
       平白多一条能把自己放不出声的路径。 -->
  <audio ref="el" class="global-audio" preload="metadata" />
</template>

<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue';
import { usePlayerStore } from '@/stores/player';

const player = usePlayerStore();
const el = ref<HTMLAudioElement | null>(null);

onMounted(() => {
  if (el.value) player.attach(el.value);
  // 注意：**这里不调用 `restoreFromSession()`**。恢复必须早于音乐页的 `onMounted`
  // （它要读 `player.source` 决定队列来源），而本组件的 `onMounted` 晚于 router-view
  // 里的页面 —— 所以那件事在 `DefaultLayout` 的 setup 顶层做。
  // 关页面/切后台前补一次位置快照。`pagehide` 比 `beforeunload` 在移动端可靠，
  // 而 `visibilitychange` 覆盖"切到别的 App 再也没回来"这种情况。
  window.addEventListener('pagehide', save);
  document.addEventListener('visibilitychange', onVisibility);
});

onBeforeUnmount(() => {
  window.removeEventListener('pagehide', save);
  document.removeEventListener('visibilitychange', onVisibility);
  player.persist(true);
  player.detach();
});

function save() {
  player.persist(true);
}

function onVisibility() {
  if (document.visibilityState === 'hidden') player.persist(true);
}
</script>

<style scoped>
/* 元素必须在文档里，但不该占位：`display:none` 会让部分浏览器停止解码，
   所以用"零尺寸 + 绝对定位"把它藏起来。 */
.global-audio {
  position: absolute;
  width: 0;
  height: 0;
  opacity: 0;
  pointer-events: none;
}
</style>
