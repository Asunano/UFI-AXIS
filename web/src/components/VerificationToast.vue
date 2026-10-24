<template>
  <!--
    验证码/新短信 实时卡片（2026-10-05）。
    数据源：WS `notification` 频道 —— core 对 verification/sms 场景只发这一个频道
    （PushChannel.SINGLE_TOPIC_SCENES 不镜像 alert，避免告警列表混进短信），所以
    websocket store 专门补订了 notification，本组件是它目前唯一的消费方。
    展示：右下角浮动卡片，验证码大字号 + 一键复制；60s 后自动消失（不堆积历史，
    历史在 短信页 → 验证码 tab）。多条连发只保留最新一条（core 侧每轮也最多推一条）。
  -->
  <transition name="vc-card">
    <div v-if="card" class="vc-toast" :class="{ 'vc-toast--sms': card.kind === 'sms' }" @click="copyCode">
      <div class="vc-toast-head">
        <span class="vc-toast-title">
          <n-icon :size="16"
            ><component :is="card.kind === 'sms' ? ChatbubblesOutline : ShieldCheckmarkOutline"
          /></n-icon>
          {{ card.kind === 'sms' ? '新短信' : '收到验证码' }}
        </span>
        <n-button size="tiny" quaternary circle class="vc-toast-close" @click.stop="card = null">
          <template #icon
            ><n-icon :size="14"><CloseOutline /></n-icon
          ></template>
        </n-button>
      </div>
      <div v-if="card.code" class="vc-code">{{ card.code }}</div>
      <!-- 发件人 / 正文可能是空串（pure 验证码推送不带 sender，短信正文也可能为空）：
           空串渲染出来是一个占高的空行，把卡片撑高且看不出是"没有"还是"没取到"。 -->
      <div v-if="card.sender" class="vc-meta">{{ card.sender }}</div>
      <div v-if="card.snippet" class="vc-snippet">{{ card.snippet }}</div>
      <div v-if="card.code" class="vc-hint">点击卡片复制验证码</div>
    </div>
  </transition>
</template>

<script setup lang="ts">
import { ref, onUnmounted } from 'vue';
import { useMessage } from 'naive-ui';
import { ChatbubblesOutline, ShieldCheckmarkOutline, CloseOutline } from '@vicons/ionicons5';
import { useWebSocketStore } from '@/stores/websocket';

interface VerificationCard {
  kind: 'verification' | 'sms';
  code: string;
  sender: string;
  snippet: string;
}

const message = useMessage();
const wsStore = useWebSocketStore();
const card = ref<VerificationCard | null>(null);
let hideTimer: ReturnType<typeof setTimeout> | null = null;

/** 60s 自动收起：验证码有时效性，留太久反而误导 */
const AUTO_HIDE_MS = 60_000;

function show(next: VerificationCard) {
  card.value = next;
  if (hideTimer) clearTimeout(hideTimer);
  hideTimer = setTimeout(() => (card.value = null), AUTO_HIDE_MS);
}

async function copyCode() {
  const code = card.value?.code;
  if (!code) return;
  try {
    await navigator.clipboard.writeText(code);
    message.success('验证码已复制');
  } catch {
    // 剪贴板 API 被拒（非安全上下文/权限）时退回 execCommand
    const ta = document.createElement('textarea');
    ta.value = code;
    document.body.appendChild(ta);
    ta.select();
    const ok = document.execCommand('copy');
    document.body.removeChild(ta);
    message[ok ? 'success' : 'error'](ok ? '验证码已复制' : '复制失败，请手动记录');
  }
}

/**
 * notification 频道 payload 与 app 端 `:ufi_notify` 解析的是同一份 PushNotification JSON：
 * { type, level, title, message, timestamp, extra }。
 * type 判定用 verification/sms（NotifyScenes 的 wire 值），extra.code 由 core 写入。
 *
 * 订阅在 `wsStore.on` 注册即生效（连接建立时统一 subscribe），所以不需要 onMounted 钩子。
 */
const unsub = wsStore.on('notification', (data: any) => {
  const type = String(data?.type ?? '');
  if (type !== 'verification' && type !== 'sms') return;
  const extra = data?.extra ?? {};
  show({
    kind: type === 'verification' ? 'verification' : 'sms',
    code: String(extra.code ?? ''),
    sender: String(extra.sender ?? ''),
    snippet: String(extra.snippet ?? data?.message ?? ''),
  });
});

onUnmounted(() => {
  unsub();
  if (hideTimer) clearTimeout(hideTimer);
});
</script>

<style scoped>
.vc-toast {
  position: fixed;
  right: 20px;
  bottom: 24px;
  z-index: 3000;
  /* 2026-10-14：280 → 320。右下角这块离视线中心最远，用户反馈"有点小" ——
     加宽比加高更划算：标题/号码/发件人/正文都是单行，宽度直接决定能读多少字，
     而高度每加 1px 都在往屏幕上方顶。 */
  width: 320px;
  max-width: calc(100vw - 32px);
  padding: 16px 18px;
  border-radius: 14px;
  background: var(--card-bg, #fff);
  border: 1px solid var(--border-subtle, #e5e7eb);
  /* 投影颜色走令牌（自带暗色档）；几何留在这里，令牌只给颜色 */
  box-shadow: 0 8px 28px var(--shadow-color-strong);
  cursor: pointer;
  user-select: none;
  /* 闪烁的载体：outline 默认 0 宽（不可见），由 keyframes 脉动到 3px。
     颜色只在这里声明一次，keyframes 里只动宽度。
     注意令牌名是 `--accent-color`：`--accent` 从未定义过，之前这四处恒落到写死蓝
     （与品牌绿不符，且不跟换肤走）。 */
  outline: 0 solid var(--accent-color);
  outline-offset: 2px;
  /* 进场后闪两下（约 1.2s）。只动 border-color / outline-width —— 两者都不触发布局，
     不会让下面的内容抖；**不碰 transform/opacity**，因为进场过渡正占着它们，抢同一属性会跳帧。 */
  animation: vc-toast-flash 0.6s ease-in-out 2;
}

/*
 * 闪烁：静止态 ↔ 强调态各 50%。
 *
 * 用 outline 而不是外发光（box-shadow）：外发光必须带半透明颜色，而令牌里没有
 * 半透明强调色 —— 那会引入一处写死的 rgba（UI 基线门禁会拦）。outline 可以
 * 直接用 var(--accent-color) 实色，且**不参与布局**（画在边框外侧，不挤动任何东西），
 * 所以脉动 outline-width 是纯绘制、零回流。
 */
@keyframes vc-toast-flash {
  0%,
  100% {
    border-color: var(--border-subtle, #e5e7eb);
    outline-width: 0;
  }
  50% {
    border-color: var(--accent-color);
    outline-width: 3px;
  }
}

/* 尊重系统的「减少动态效果」：不闪，改用静态的强调描边保证仍然显眼 */
@media (prefers-reduced-motion: reduce) {
  .vc-toast {
    animation: none;
    border-color: var(--accent-color);
  }
}
.vc-toast--sms {
  cursor: default;
}
.vc-toast-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 8px;
}
.vc-toast-title {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 14px;
  font-weight: 600;
  color: var(--text-secondary);
}
.vc-toast-close {
  margin-right: -8px;
}
.vc-code {
  font-size: 34px;
  font-weight: 700;
  letter-spacing: 4px;
  font-variant-numeric: tabular-nums;
  color: var(--accent-color);
  line-height: 1.2;
  margin-bottom: 6px;
}
.vc-meta {
  font-size: 13px;
  color: var(--text-secondary);
  margin-bottom: 2px;
}
.vc-snippet {
  font-size: 13px;
  color: var(--text-muted, #9ca3af);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.vc-hint {
  margin-top: 8px;
  font-size: 12px;
  color: var(--text-muted, #9ca3af);
}

/* 进出场：右下滑入 */
.vc-card-enter-active,
.vc-card-leave-active {
  transition:
    transform 0.25s ease,
    opacity 0.25s ease;
}
.vc-card-enter-from,
.vc-card-leave-to {
  transform: translateY(12px);
  opacity: 0;
}
</style>
