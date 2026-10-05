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
          <n-icon :size="16"><component :is="card.kind === 'sms' ? ChatbubblesOutline : ShieldCheckmarkOutline" /></n-icon>
          {{ card.kind === 'sms' ? '新短信' : '收到验证码' }}
        </span>
        <n-button size="tiny" quaternary circle class="vc-toast-close" @click.stop="card = null">
          <template #icon><n-icon :size="14"><CloseOutline /></n-icon></template>
        </n-button>
      </div>
      <div v-if="card.code" class="vc-code">{{ card.code }}</div>
      <div class="vc-meta">{{ card.sender }}</div>
      <div class="vc-snippet">{{ card.snippet }}</div>
      <div v-if="card.code" class="vc-hint">点击卡片复制验证码</div>
    </div>
  </transition>
</template>

<script setup lang="ts">
import { ref, onMounted, onUnmounted } from 'vue';
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

onMounted(() => {
  /* 订阅在 wsStore.on 注册即生效（连接建立时统一 subscribe） */
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
  width: 280px;
  max-width: calc(100vw - 32px);
  padding: 14px 16px;
  border-radius: 12px;
  background: var(--card-bg, #fff);
  border: 1px solid var(--border-subtle, #e5e7eb);
  box-shadow: 0 8px 28px rgba(0, 0, 0, 0.16);
  cursor: pointer;
  user-select: none;
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
  font-size: 13px;
  font-weight: 600;
  color: var(--text-secondary);
}
.vc-toast-close {
  margin-right: -8px;
}
.vc-code {
  font-size: 30px;
  font-weight: 700;
  letter-spacing: 4px;
  font-variant-numeric: tabular-nums;
  color: var(--accent, #2563eb);
  line-height: 1.2;
  margin-bottom: 6px;
}
.vc-meta {
  font-size: 12px;
  color: var(--text-secondary);
  margin-bottom: 2px;
}
.vc-snippet {
  font-size: 12px;
  color: var(--text-muted, #9ca3af);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.vc-hint {
  margin-top: 8px;
  font-size: 11px;
  color: var(--text-muted, #9ca3af);
}

/* 进出场：右下滑入 */
.vc-card-enter-active,
.vc-card-leave-active {
  transition: transform 0.25s ease, opacity 0.25s ease;
}
.vc-card-enter-from,
.vc-card-leave-to {
  transform: translateY(12px);
  opacity: 0;
}
</style>
