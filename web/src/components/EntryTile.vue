<template>
  <!--
    入口瓦片（计划 §8.1）。合并了原先散在 5 个文件里的同一份 `.entry-*`：
      SettingsView / network/NetworkSettingsCard / network/MoreSettingsCard /
      settings/panels/{Device,About}Panel
    （UpdatePanel 那三张资源卡在 2026-10-14 已收成「说明 + 一粒按钮」，本来就没有瓦片。）

    视觉基准 = SettingsView 原实现，逐像素照抄：38px 图标格 + 20px 图标、12px 图文间距、
    min-height 72（≤768px 收 64）、说明折两行。网络页那两份原先是 34px 图标格 / 18px 图标 /
    单行省略号描述 —— 按本计划「六处观感一致」统一到基准，所以那两处会多 4px 图标、
    说明允许折行（报告里列为需目视确认的点）。

    `disabled` + `hint` 是网络页两种门禁瓦片的共同需求（设备未声明能力时整块置灰 + 说明原因）。
    原先每个消费方各写一份 `<n-tooltip><span class="gate-wrap"><button disabled>`：浏览器不对
    disabled 的表单控件派发鼠标事件，所以指针事件必须由外层 span 接住。这段结构收进本组件，
    消费方只传 `:disabled` / `:hint`。
  -->
  <n-tooltip :disabled="!gatedHint" :title="hint">
    <template #trigger>
      <!-- 外层 span 取代瓦片成为栅格子项（n-tooltip 的 trigger 不生成包裹元素，只渲染这一棵）。
           它用 flex + min-width:0，按钮才在两个方向上都吃到栅格的 stretch，与「直接放一颗
           button」的布局等价。描边/内距/圆角/底色仍走 main.css 的全局 .sub-panel。 -->
      <span class="entry-tile-wrap">
        <button
          class="entry-tile sub-panel"
          :disabled="disabled"
          @click="emit('click')"
          @pointerenter="emit('pointerenter')"
          @focus="emit('focus')"
        >
          <span class="entry-icon">
            <n-icon :size="20"><component :is="icon" /></n-icon>
          </span>
          <span class="entry-text">
            <span class="entry-title">{{ title }}</span>
            <span v-if="desc" class="entry-desc">{{ desc }}</span>
          </span>
        </button>
      </span>
    </template>
  </n-tooltip>
</template>

<script setup lang="ts">
import { computed, type Component } from 'vue';

const props = withDefaults(
  defineProps<{
    /** 图标（@vicons 组件或任意 Vue 组件）；20px 由本组件统一，消费方不用再传 size */
    icon: Component;
    /** 入口名（主文本，14px 半粗） */
    title: string;
    /** 一句说明（次要文本，最多折两行；不传则不留空行） */
    desc?: string;
    /** 能力门禁：整块瓦片置灰、不可点。判据由调用方给（见 stores/capabilities.ts 文件头） */
    disabled?: boolean;
    /** 置灰原因：只在 disabled 时以 tooltip 给出 —— 只灰不说等于让用户以为界面坏了 */
    hint?: string;
  }>(),
  { desc: '', disabled: false, hint: '' }
);

/**
 * 瓦片被点击。用显式事件而不是原生 click 透传：置灰时不该有任何事件冒到栅格/卡片上。
 *
 * `pointerenter` / `focus` 是给「打开前预取」用的（设置页在指针移到瓦片上时先拉面板 chunk，
 * 点下去就是秒开）。转发而不是让 DOM 事件自己冒泡：组件根节点是 `n-tooltip`，
 * 它的 trigger 是一棵被重排过的子树，原生监听落到哪个元素上并不可控；
 * 绑在内层 button 上才是确定的语义（"这颗瓦片被悬停/聚焦"）。
 */
const emit = defineEmits<{ click: []; pointerenter: []; focus: [] }>();

/** 只有「置灰 + 有原因」才挂 tooltip；可点的瓦片不该冒出说明气泡。 */
const gatedHint = computed(() => props.disabled && props.hint !== '');
</script>

<style scoped>
/* 栅格子项（原先直接是 button）。min-width:0 让过长的说明能在瓦片内折行，
   而不是把栅格列顶宽。 */
.entry-tile-wrap {
  display: flex;
  min-width: 0;
}
/* 按钮填满这一格：横向靠 flex:1，纵向靠 flex 容器默认的 align-items:stretch
   （栅格行被拉高时，瓦片跟着等高，与原先「button 直接作栅格子项」一致）。 */
.entry-tile-wrap > .entry-tile {
  flex: 1 1 auto;
  min-width: 0;
}
.entry-tile {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  /* 72 / 64 是设计档位（图标格 38 + 上下内距 + 一行文字）：短说明的瓦片也要等高 */
  min-height: 72px;
  cursor: pointer;
  text-align: left;
  font: inherit;
  color: inherit;
  transition:
    border-color 0.15s,
    background 0.15s;
}
.entry-tile:hover {
  border-color: var(--accent-color);
  background: var(--surface-hover);
}
/* 门禁置灰。pointer-events:none 是把指针事件让给外层 span（tooltip 的 trigger）——
   浏览器不对 disabled 的表单控件派发鼠标事件。 */
.entry-tile:disabled {
  pointer-events: none;
  cursor: not-allowed;
  opacity: 0.5;
}
.entry-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 38px;
  height: 38px;
  flex-shrink: 0;
  border-radius: var(--radius-sm);
  background: var(--accent-color-light);
  color: var(--accent-color);
}
.entry-text {
  display: flex;
  flex-direction: column;
  gap: 3px;
  min-width: 0;
}
.entry-title {
  font-size: var(--font-md);
  font-weight: 600;
  color: var(--text-primary);
}
/* 说明允许折两行而不是省略号：用户正是靠这句话判断该点哪个入口 ——
   否则「配对码、已配对设备与配对密码」这类描述会被吃掉半句。 */
.entry-desc {
  font-size: var(--font-sm);
  color: var(--text-muted);
  line-height: 1.45;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

@media (max-width: 768px) {
  /* 单列后不靠 min-height 对齐左右两格，收紧一档免得入口把卡片拉太长 */
  .entry-tile {
    min-height: 64px;
  }
}
</style>
