<!-- eslint-disable vue/valid-template-root -- 本组件故意不渲染任何内容，只负责在需要时弹 message -->
<template>
  <!-- 无 UI，仅负责在需要时弹出安全提示 -->
</template>

<script setup lang="ts">
import { onMounted, watch } from 'vue';
import { useMessage } from 'naive-ui';
import type { MessageReactive } from 'naive-ui';
import { useAppStore } from '@/stores/app';
import { isInsecurePublicAccess, isInsecureNoticeMuted, muteInsecureNoticeForADay } from '@/composables/utils';

const message = useMessage();
const appStore = useAppStore();

// 面板里有短信与验证码，HTTP 下这些内容和登录凭据都是明文传输，链路上可被窃听。
// 局域网/回环/Tailscale 等私有地址不提示，否则默认入口 http://192.168.x.x:8088 每次都弹。
const TEXT = '当前以 HTTP 明文访问，短信、验证码等敏感信息在传输中可能被窃听。建议改用 HTTPS 或仅在局域网内访问。';

let current: MessageReactive | null = null;

function notify() {
  if (current) return;
  if (!isInsecurePublicAccess(appStore.baseUrl)) return;
  if (isInsecureNoticeMuted()) return;
  current = message.warning(TEXT, {
    duration: 12_000,
    closable: true,
    // onClose 只有点右上角关闭才触发（自动超时走的是内部 hide），
    // 所以"一天内不再提示"只在用户主动关闭时生效。
    onClose: () => {
      muteInsecureNoticeForADay();
    },
    onAfterLeave: () => {
      current = null;
    },
  });
}

onMounted(notify);

// 退出登录会清掉静音标记，这里在重新登录成功时再提示一次。
// 登录是 SPA 内跳转，本组件不会重新挂载，所以必须靠 watch 而不是 onMounted。
watch(
  () => appStore.isAuthenticated,
  (authed, was) => {
    if (authed && !was) notify();
  }
);
</script>
