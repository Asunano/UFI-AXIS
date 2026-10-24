<!--
  进行中操作弹窗（全仓唯一一份）。由 useApiOperation 驱动，本组件只管渲染。
  running=圆环转圈；success=对勾定格 300ms 自动关；failed=叉+原因+重试。
  hidden 态不渲染（用户选了后台进行），但 useApiOperation 的轮询照常。

  ── 2026-10-14：running 态的「后台进行」原来是一颗空按钮，而且弹窗关不掉 ──
  两处调用方（改前 NetworkView.vue:174-183 / DashboardView.vue:90-99）给制式切换那份弹窗传的是
  `:to-background="noop"` —— 那套进度位（useNetworkControls 的 modeOpState）根本没有
  useApiOperation 的 hidden 态可切，于是「后台进行」点了没任何反应；
  而本弹窗 `mask-closable=false`、running 时 `close-on-esc` 也是 false，卡片右上角的 ×
  又因为外层没有 `@update:show` 处理（`:show` 是单向绑定）同样是死的 ——
  用户被锁在一个不可关闭的弹窗里，直到 13 次轮询上限走完。
  现在：
    · `toBackground` 变成可选。**调用方给了才渲染「后台进行」**（useApiOperation 系：
      WiFi 开关 / 频段锁定，真的有 hidden 态：弹窗消失、轮询与终态 toast 继续），
      没给的（制式切换）渲染「关闭」—— 宁可少一颗按钮，也不给一颗点了没反应的假按钮。
    · ×、ESC、底部按钮统一走 dismiss()：running 态一定有办法关掉弹窗。
  遗留（本任务刻意不做）：点了「后台进行」之后**没有**再打开进度的入口，结果只靠终态 toast；
  要做到"后台还能再看到它"得把操作状态从页面里提到 store（调研列为 P2），
  那是一次更大的重构，不在本次三个 bug 的范围内。

  ── 2026-10-14（当天晚些）：上面那条"遗留"的**状态部分已经解决**，注释曾因此过期 ──
  A 级网络操作（WiFi / 移动数据 / 飞行模式 / 制式切换）的状态已经提到
  `stores/networkOps.ts`，本组件也**全仓只挂一份**（`DefaultLayout.vue`，与
  `VerificationToast` / `GlobalAudioPlayer` 同级、在 `n-layout` 之外）——
  所以"切页就丢"没有了：从仪表盘发起、切到网络页，进度与终态都还在。

  **但"再打开进度的入口"这条限制仍在**：`hidden` 是用户主动把它收起来的，
  之后要看结果只能等终态 toast（`useApiOperation` 的 hidden 分支刻意不弹回弹窗，
  否则用户刚收起来又跳出来）。要补一个"查看进行中的操作"入口，得再给 store 加一个
  "重新展开"的动作 —— 那是独立的一小步，不是遗留 bug。

  ⚠ 改这个文件之前先看 `stores/networkOps.ts` 的 `attach()`：
  **状态**已经不依赖页面生命周期，**弹窗实例**也不再是每页一份，别又挂回页面里。
-->
<template>
  <n-modal
    :show="visible"
    preset="card"
    :title="title"
    class="modal-fit"
    style="--modal-w: 360px"
    :mask-closable="false"
    :close-on-esc="state !== 'running'"
    @update:show="onShowChange"
  >
    <div class="op-progress">
      <n-icon size="52" :color="iconColor" :class="{ spinning: state === 'running' }">
        <svg viewBox="0 0 24 24" fill="none">
          <circle
            cx="12"
            cy="12"
            r="9"
            stroke="currentColor"
            stroke-width="2.4"
            :opacity="state === 'running' ? 0.18 : 0.35"
          />
          <path
            v-if="state === 'running'"
            d="M12 3a9 9 0 0 1 9 9"
            stroke="currentColor"
            stroke-width="2.4"
            stroke-linecap="round"
          />
          <path
            v-else-if="state === 'success'"
            d="M7.5 12.5l3 3 6-6.5"
            stroke="currentColor"
            stroke-width="2.4"
            stroke-linecap="round"
            stroke-linejoin="round"
          />
          <path
            v-else-if="state === 'failed'"
            d="M8.5 8.5l7 7M15.5 8.5l-7 7"
            stroke="currentColor"
            stroke-width="2.4"
            stroke-linecap="round"
          />
        </svg>
      </n-icon>
      <div class="op-text">
        <div class="op-primary">{{ primaryText }}</div>
        <n-text v-if="state === 'running'" :depth="3" class="op-hint">{{ hint }}</n-text>
        <n-text v-else-if="state === 'failed'" type="warning" :depth="2" class="op-hint">
          设备端可能仍在后台继续，可稍后刷新查看实际状态
        </n-text>
      </div>
    </div>
    <template #footer>
      <div class="op-footer">
        <!-- running 态：文案与行为必须一致 —— 有真后台态才叫「后台进行」，否则就是「关闭」 -->
        <template v-if="state === 'running'">
          <n-button v-if="toBackground" size="small" quaternary @click="dismiss">后台进行</n-button>
          <n-button v-else size="small" quaternary @click="dismiss">关闭</n-button>
        </template>
        <template v-else-if="state === 'failed'">
          <n-button size="small" quaternary @click="close">关闭</n-button>
          <n-button v-if="retry" size="small" type="primary" @click="retry">重试</n-button>
        </template>
        <!-- success 态无按钮：300ms 后自动关闭 -->
      </div>
    </template>
  </n-modal>
</template>

<script setup lang="ts">
import { computed } from 'vue';
import { NModal, NButton, NIcon, NText } from 'naive-ui';
import type { OperationDialogState } from '@/composables/useApiOperation';

const props = defineProps<{
  state: OperationDialogState;
  title: string;
  runningText: string;
  successText: string;
  hint?: string;
  failText?: string;
  retry?: () => void;
  close: () => void;
  /**
   * 「后台进行」。**可选**：只有真的能转后台的调用方（useApiOperation 系）才传，
   * 传了才渲染这颗按钮 —— 见文件头 2026-10-14 的说明。
   */
  toBackground?: () => void;
}>();

// hidden（后台进行）时不渲染；idle 关闭；其余显示
const visible = computed(() => props.state.kind !== 'idle' && props.state.kind !== 'hidden');
const state = computed(() => props.state.kind);
const primaryText = computed(() =>
  state.value === 'running'
    ? props.runningText
    : state.value === 'success'
      ? props.successText
      : props.failText || `${props.title}未完成`
);
const iconColor = computed(() =>
  state.value === 'success' ? 'var(--success)' : state.value === 'failed' ? 'var(--error)' : undefined
);

/**
 * 关闭弹窗的唯一入口（卡片右上角 ×、ESC、running/failed 态的按钮都走这里）。
 * 2026-10-14：原来没有任何 `@update:show` 处理 —— `:show` 是单向绑定，卡片自带的 ×
 * 点了只会 emit 一个没人听的事件，于是又多了一处"看着能关、其实关不掉"。
 */
function onShowChange(show: boolean) {
  if (!show) dismiss();
}

/**
 * 用户主动关掉弹窗。running 态有 toBackground 就交给它（状态机进 hidden，操作继续、
 * 终态还有 toast，且 useApiOperation 的并发闸门认 hidden，不会被第二个操作趁虚而入）；
 * 没有就退回 close（制式切换：关掉弹窗后 probeNetworkMode 照旧跑完，
 * 进度由「更多设置」卡上的「切换中 / 尚未完成」标签呈现）。
 */
function dismiss() {
  if (props.state.kind === 'running' && props.toBackground) props.toBackground();
  else props.close();
}
</script>

<style scoped>
.op-progress {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 14px;
  padding: 18px 0 10px;
}
.spinning {
  animation: op-spin 0.9s linear infinite;
}
@keyframes op-spin {
  to {
    transform: rotate(360deg);
  }
}
.op-text {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 4px;
  text-align: center;
}
.op-primary {
  font-size: 14px;
  color: var(--text-primary);
}
.op-hint {
  font-size: 12px;
  max-width: 280px;
}
.op-footer {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}
</style>
