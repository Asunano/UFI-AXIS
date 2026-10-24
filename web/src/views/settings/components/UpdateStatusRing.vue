<!--
  更新流程的环（2026-10-14）。

  为什么单独抽一个组件：两个更新弹窗（core APK / 前端资源 ZIP）都要"环 + 阶段文案"，
  而四种形态（真实进度 / 不确定 / 成功 / 失败）的**取值口径**必须一致 —— 抄两份的话
  "不确定态该画空环还是画一段弧"这种决定会在两处慢慢分叉。

  形态取自 app 端 `UpdateDialogs.kt` 的 `UpdateProgressRing` / `UpdateResultRing`：
  · `progress`       有真实百分比（浏览器 → core 的上传），环里显示数字，环下方只讲阶段；
  · `indeterminate`  拿不到百分比（设备端安装 / core 正在重启）。app 的做法是**同一个环**
                     画一段固定 25% 的弧并整体自转，而不是"空环 + 中心塞一个小转圈"
                     （那是一个弹窗里出现两个圆形指示器，观感很杂，见 UpdateDialogs.kt:84-94）；
  · `success/error`  终态沿用同一个环，只是换成 naive 的 status 图标（打勾 / 画叉）。
                     刻意不用实心圆底 + 白符号的 emoji 观感（app 侧同一条决策）。

  为什么文案由调用方传进来而不是在这里算：环只负责"画"，"这一阶段该说什么"是流程的事
  （app 的 UpdateProgressRing 同样是 caption / subCaption 入参）。
-->
<template>
  <div class="update-ring">
    <n-progress
      v-if="status === 'progress'"
      type="circle"
      :percentage="clamped"
      :stroke-width="8"
      :show-indicator="true"
    />
    <!-- 不确定态：一段 25% 的弧 + 整体自转（不是空环，空环会被当成"卡死了"） -->
    <div v-else-if="status === 'indeterminate'" class="update-ring-spin">
      <n-progress type="circle" :percentage="25" :stroke-width="8" :show-indicator="false" />
    </div>
    <n-progress
      v-else-if="status === 'success'"
      type="circle"
      :percentage="100"
      status="success"
      :stroke-width="8"
      :show-indicator="true"
    />
    <n-progress v-else type="circle" :percentage="100" status="error" :stroke-width="8" :show-indicator="true" />

    <div v-if="caption" class="update-ring-caption">{{ caption }}</div>
    <div v-if="subCaption" class="update-ring-subcaption">{{ subCaption }}</div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue';

const props = withDefaults(
  defineProps<{
    /** 四态之一。`indeterminate` = 这个阶段拿不到百分比（设备端安装 / core 重启）。 */
    status: 'progress' | 'indeterminate' | 'success' | 'error';
    /** 真实进度 0-100（只有 `progress` 用得上）。 */
    percent?: number;
    /** 环下方的阶段说明（第一行，正文字号）。 */
    caption?: string;
    /** 可选补充说明（第二行，小字次要色），如「请保持与设备的连接」。 */
    subCaption?: string;
  }>(),
  { percent: 0 }
);

/** naive 的 n-progress 对越界值不设防（会画出超过一圈的弧），在这里钳一次。 */
const clamped = computed(() => Math.min(100, Math.max(0, Math.round(props.percent))));
</script>

<style scoped>
/* 环与文案的间距取 16px：120px 的环视觉重量很沉，8px 会让文案像"贴"在环上
   （app 侧同一处从 8dp 改到 16dp，见 UpdateDialogs.kt:125-127）。 */
.update-ring {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 16px;
  padding: 8px 0 4px;
}
.update-ring-caption {
  font-size: 13px;
  color: var(--text-primary);
  text-align: center;
  line-height: 1.5;
  word-break: break-word;
}
.update-ring-subcaption {
  font-size: 12px;
  color: var(--text-secondary);
  text-align: center;
  line-height: 1.5;
  word-break: break-word;
  /* 第二行与第一行同一段话，间距比"环 → 文案"那档紧一档 */
  margin-top: -10px;
}
/* 不确定态的自转：转的是环本身（`.n-progress-graph-circle` 是包住 svg 的那层，
   naive 只在 gapOffsetDegree 时才给它内联 transform，这里用不到，不会打架）。 */
.update-ring-spin :deep(.n-progress-graph-circle) {
  animation: update-ring-rotate 1.6s linear infinite;
}
@keyframes update-ring-rotate {
  to {
    transform: rotate(360deg);
  }
}
/* 无限旋转是典型的前庭刺激源，而且会一直挂着动画帧回调。
   系统开了「移除动画」时停在静止的那段弧上 —— 仍然看得出"这是个没走完的环"，
   不要退化成空环（那会被当成卡死）。 */
@media (prefers-reduced-motion: reduce) {
  .update-ring-spin :deep(.n-progress-graph-circle) {
    animation: none;
  }
}
</style>
