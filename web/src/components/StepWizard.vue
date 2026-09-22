<!--
  分步引导表单（向导）。web 侧公共层的第 10 个组件。

  ## 为什么需要它
  字段一多的表单（首次配对、定时任务、外部存储源、备份恢复…）过去都是「一张长表单 +
  一个提交按钮」。两个毛病：① 用户要先读完整页才知道哪些是必填；② 校验只在点提交时跑，
  不通过时只能在页面某处冒一句 error —— 而那句话离出错的字段往往隔着半屏。

  本组件把这两件事收进同一处：分步只暴露当前要填的字段，而「能不能往下走」由每步的
  [WizardStep.validate] 显式回答，拦下时把原因就地渲染在操作栏上方并置灰主按钮。

  ## 与 app 端 UfiWizard 的关系
  交互契约逐条对齐（步骤条可跳、validate 决定能否离开、拦下必须说明原因、末步主按钮换文案），
  但**形态按 web 的习惯落地**：
  - 不是整页骨架 —— 它就是一块内容，放进 `GridCard`、`n-modal` 或登录卡都行；
  - 按钮用 naive 的 `n-button`，本组件不自研按钮（web 公共层的既有分工）；
  - 确认页不另造组件：`GridCard` + `InfoRow` 拼起来就是，再加一个会与 GridCard 重叠。

  ## 用法
  ```vue
  <StepWizard
    v-model:current="step"
    :steps="steps"
    finish-text="设置并进入"
    :finish-loading="loading"
    @finish="submit"
    @blocked="(why) => message.warning(why)"
  >
    <template #password> …第 1 步的表单… </template>
    <template #goform>   …第 2 步的表单… </template>
    <template #review>   …确认页… </template>
  </StepWizard>
  ```
  `steps[i].key` 就是具名插槽的名字 —— 用 key 而不是下标，插入/调整步骤时不必改插槽名。
-->
<template>
  <div v-if="steps.length > 0" class="step-wizard">
    <!-- 步骤条：N 列等宽，每列 = 连接线 + 圆点 + 标签。
         连接线从圆点**底下穿过**，靠圆点的不透明底色遮断 —— 所以三态底色都必须不透明，
         半透明底会让线从圆点里透出来（app 端踩过这个坑：观感是"空心圈里划了一道横线"）。 -->
    <ol class="wiz-steps">
      <li
        v-for="(s, i) in steps"
        :key="s.key"
        class="wiz-step"
        :class="{ 'is-active': i === index, 'is-done': i < index }"
        @click="onStepClick(i)"
      >
        <div class="wiz-dot-row">
          <span v-if="i > 0" class="wiz-line wiz-line--left" :class="{ 'is-lit': index >= i }"></span>
          <span
            v-if="i < steps.length - 1"
            class="wiz-line wiz-line--right"
            :class="{ 'is-lit': index >= i + 1 }"
          ></span>
          <span class="wiz-dot">
            <n-icon v-if="i < index" :size="14"><CheckmarkOutline /></n-icon>
            <template v-else>{{ i + 1 }}</template>
          </span>
        </div>
        <span class="wiz-label">{{ s.label }}</span>
      </li>
    </ol>

    <!-- 面板：只有这一块随步骤切换。panelMaxHeight 传了就内滚（弹窗里用）。 -->
    <template v-if="activeStep">
      <div class="wiz-panel" :style="panelMaxHeight ? { maxHeight: panelMaxHeight } : undefined">
        <Transition :name="dir === 'back' ? 'wiz-back' : 'wiz-fwd'" mode="out-in">
          <div :key="activeStep.key" class="wiz-pane">
            <h3 class="wiz-heading">{{ activeStep.heading }}</h3>
            <p v-if="activeStep.description" class="wiz-desc">{{ activeStep.description }}</p>
            <slot :name="activeStep.key" />
          </div>
        </Transition>
      </div>
    </template>

    <!-- 操作栏。拦下原因就地写在按钮上方：
         置灰的按钮如果不说明理由，只是把"点了没反应"换了个样子。 -->
    <div class="wiz-footer">
      <Transition name="wiz-block">
        <p v-if="blockReason" class="wiz-block">
          <n-icon :size="14"><AlertCircleOutline /></n-icon>
          <span>{{ blockReason }}</span>
        </p>
      </Transition>
      <div class="wiz-actions">
        <n-button :disabled="!canGoLeft || finishLoading" @click="onLeft">{{ leftText }}</n-button>
        <n-button
          type="primary"
          style="flex: 1"
          :disabled="blockReason !== null"
          :loading="isLast && finishLoading"
          @click="onRight"
        >
          {{ isLast ? finishText : nextText }}
        </n-button>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { AlertCircleOutline, CheckmarkOutline } from '@vicons/ionicons5';

/** 向导单步的声明。 */
export interface WizardStep {
  /** 具名插槽的名字。用 key 而不是下标 —— 调整步骤顺序时不必改插槽名。 */
  key: string;
  /** 步骤条上的短标签（2~4 字，横排 N 个不换行）。 */
  label: string;
  /**
   * 面板标题：写成**这一步要回答的问题**（「什么时候执行」），而不是字段名的复述
   * （「执行周期」）—— 后者会与步骤条标签、以及步内小标题三重重复。
   */
  heading: string;
  /** 面板说明；不传则不渲染。 */
  description?: string;
  /**
   * 本步是否可以离开。返回 `null` = 通过；返回字符串 = **拦下的原因**，
   * 由本组件渲染在操作栏上方并置灰主按钮。
   *
   * 它在 computed 里求值，所以读到的 ref 会被登记为依赖 —— 用户边打字，按钮的
   * 置灰/恢复就边跟着变，调用方不需要再自己驱动一次刷新。因此实现里只许做纯判断。
   */
  validate?: () => string | null;
}

const props = withDefaults(
  defineProps<{
    steps: WizardStep[];
    /** 当前步序号，由调用方持有（`v-model:current`）。 */
    current: number;
    finishText?: string;
    nextText?: string;
    prevText?: string;
    /** 末步主按钮的转圈态（提交是异步的场合传它）。 */
    finishLoading?: boolean;
    /**
     * 第 0 步时左键的替代动作文案（如「取消」「选别的设备」），配 `@exit` 一起用。
     * 不传则第 0 步左键置灰。**向导所在容器没有关闭入口时必须传**，否则用户进了第 0 步就出不去。
     */
    exitText?: string;
    /** 面板最大高度（CSS 长度）。传了就内滚 —— 弹窗里用，否则弹窗会被撑出屏幕。 */
    panelMaxHeight?: string;
  }>(),
  {
    finishText: '完成',
    nextText: '下一步',
    prevText: '上一步',
    finishLoading: false,
  }
);

const emit = defineEmits<{
  'update:current': [value: number];
  finish: [];
  exit: [];
  /** 往前跳被拦下时上报原因，调用方通常 `message.warning(reason)`。 */
  blocked: [reason: string];
}>();

/** 夹一下：调用方的 current 可能越界（步骤数随数据变化时常见）。 */
const index = computed(() => Math.min(Math.max(props.current, 0), props.steps.length - 1));
const activeStep = computed(() => props.steps[index.value]);
const isLast = computed(() => index.value === props.steps.length - 1);

/** 当前步的拦下原因；null = 可以往下走。 */
const blockReason = computed<string | null>(() => activeStep.value?.validate?.() ?? null);

const canGoLeft = computed(() => index.value > 0 || !!props.exitText);
const leftText = computed(() => (index.value === 0 ? (props.exitText ?? props.prevText) : props.prevText));

/** 面板过渡方向。前进从右侧推入，后退从左侧退回。 */
const dir = ref<'fwd' | 'back'>('fwd');
watch(
  () => props.current,
  (next, prev) => {
    dir.value = next < (prev ?? next) ? 'back' : 'fwd';
  }
);

function onLeft() {
  if (index.value === 0) {
    if (props.exitText) emit('exit');
    return;
  }
  emit('update:current', index.value - 1);
}

function onRight() {
  if (isLast.value) emit('finish');
  else emit('update:current', index.value + 1);
}

/**
 * 点步骤条。
 *
 * 往回点永远成立；往前点要求**被跳过的每一步此刻都合法**。
 *
 * 为什么不是"只许往回点"：编辑一个已保存的对象时每一步本来就都填好了，逼用户从第 1 步
 * 一路点到第 3 步只是白走两屏。而只要中间各步都合法，跳过它们并不会绕过任何校验 ——
 * 这条判据同时满足"能跳"与"不许绕"。
 *
 * 拦下时不是原地不动，而是把用户带到**第一个**不合法的那一步并报出原因：
 * 停在原处只会让人反复点同一个圆点。
 */
function onStepClick(target: number) {
  if (target === index.value) return;
  if (target < index.value) {
    emit('update:current', target);
    return;
  }
  for (let i = index.value; i < target; i++) {
    const step = props.steps[i];
    if (!step) continue;
    const reason = step.validate?.() ?? null;
    if (reason !== null) {
      emit('update:current', i);
      emit('blocked', `请先完成「${step.label}」：${reason}`);
      return;
    }
  }
  emit('update:current', target);
}
</script>

<style scoped>
.step-wizard {
  display: flex;
  flex-direction: column;
  gap: var(--space-4);
}

/* ── 步骤条 ── */
.wiz-steps {
  display: flex;
  margin: 0;
  padding: 0;
  list-style: none;
}
.wiz-step {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: var(--space-2);
  cursor: pointer;
  user-select: none;
}
.wiz-dot-row {
  position: relative;
  width: 100%;
  height: 32px;
  display: flex;
  align-items: center;
  justify-content: center;
}
/* 每列自己画左右两条半宽线：不用绝对定位量兄弟节点的位置，
   圆点后绘制且底色不透明，于是线自然被盖住 —— 视觉等价于"点与点之间连一段"。 */
.wiz-line {
  position: absolute;
  top: 50%;
  height: 2px;
  width: 50%;
  margin-top: -1px;
  background: var(--border-subtle);
  transition: background 0.22s ease;
}
.wiz-line--left {
  left: 0;
}
.wiz-line--right {
  right: 0;
}
.wiz-line.is-lit {
  background: var(--accent-color);
}
/* 三态圆点：未到（卡片底 + 弱描边 + 序号）/ 已完成（accent 实底 + 勾）/ 当前（accent 实底 + 序号 + 光环）。
   已完成与当前**同色**是刻意的：走过的链路连成一条实色，一眼看出走到哪；
   两者的区分放在内容与层次上 —— 勾 vs 序号、以及当前那圈 accent-ring。 */
.wiz-dot {
  position: relative;
  z-index: 1;
  width: 32px;
  height: 32px;
  border-radius: var(--radius-pill);
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: var(--font-sm);
  font-weight: 600;
  background: var(--card-bg);
  border: 2px solid var(--border-subtle);
  color: var(--text-muted);
  transition:
    background 0.22s ease,
    border-color 0.22s ease,
    color 0.22s ease,
    box-shadow 0.22s ease;
}
.wiz-step.is-done .wiz-dot,
.wiz-step.is-active .wiz-dot {
  background: var(--accent-color);
  border-color: var(--accent-color);
  color: var(--on-accent);
}
.wiz-step.is-active .wiz-dot {
  box-shadow: 0 0 0 4px var(--accent-ring);
}
.wiz-label {
  font-size: var(--font-xs);
  color: var(--text-muted);
  text-align: center;
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  transition: color 0.22s ease;
}
.wiz-step.is-active .wiz-label {
  color: var(--accent-color);
  font-weight: 600;
}
.wiz-step.is-done .wiz-label {
  color: var(--text-secondary);
}

/* ── 面板 ── */
.wiz-panel {
  overflow-y: auto;
}
.wiz-pane {
  display: flex;
  flex-direction: column;
  gap: var(--space-3);
}
.wiz-heading {
  margin: 0;
  font-size: var(--font-md);
  font-weight: 600;
  color: var(--text-primary);
}
.wiz-desc {
  margin: 0;
  font-size: var(--font-sm);
  color: var(--text-muted);
  line-height: 1.6;
}

/* 面板过渡：只做"方向暗示"，不是整屏翻页 —— 步骤条还停在原位，
   整块平移会显得两者脱节。 */
.wiz-fwd-enter-active,
.wiz-fwd-leave-active,
.wiz-back-enter-active,
.wiz-back-leave-active {
  transition:
    opacity 0.2s ease,
    transform 0.2s ease;
}
.wiz-fwd-enter-from {
  opacity: 0;
  transform: translateX(14px);
}
.wiz-fwd-leave-to {
  opacity: 0;
  transform: translateX(-14px);
}
.wiz-back-enter-from {
  opacity: 0;
  transform: translateX(-14px);
}
.wiz-back-leave-to {
  opacity: 0;
  transform: translateX(14px);
}

/* ── 操作栏 ── */
.wiz-footer {
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
}
.wiz-block {
  display: flex;
  align-items: flex-start;
  gap: var(--space-2);
  margin: 0;
  padding: var(--space-2) var(--space-3);
  border-radius: var(--radius-sm);
  background: var(--warning-light);
  color: var(--warning);
  font-size: var(--font-sm);
  line-height: 1.5;
}
.wiz-block-enter-active,
.wiz-block-leave-active {
  transition:
    opacity 0.18s ease,
    transform 0.18s ease;
}
.wiz-block-enter-from,
.wiz-block-leave-to {
  opacity: 0;
  transform: translateY(-4px);
}
.wiz-actions {
  display: flex;
  gap: var(--space-2);
}

/* 窄屏：步骤条标签在 360px 下 5 列会挤成一两个字，允许它降到极小字号再省略。
   不改圆点尺寸 —— 那是点击目标，缩了更难点。 */
@media (max-width: 768px) {
  .wiz-steps {
    gap: 0;
  }
  .wiz-label {
    font-size: 10px;
  }
}
</style>
