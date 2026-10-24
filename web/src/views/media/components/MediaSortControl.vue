<!--
  媒体三页共用的排序控件（2026-10-14）。

  ──── 为什么单独一个组件 ────
  三页（视频 / 音乐 / 图片）的排序需求**逐字相同**：六档、同样的取值、同样必须下推到 core。
  各写一份的结果是其中一页的档位表写岔（core 的 `listComparator` 对认不出的 `sort`
  **静默回落到按日期排**，不会报错），用户会以为"这页的排序坏了"。

  ──── 为什么是"六档平铺"而不是"维度 + 方向" ────
  见 `mediaShared.MEDIA_SORT_OPTIONS` 的说明：两个控件要多点一次，且换维度时方向要不要重置
  是额外状态。六档一次点清，且与 app 的 `UfiSortAction` 一一对应。

  ──── 为什么是 select 而不是一排按钮 ────
  媒体页页头已经有搜索框、刷新、设置等控件，再塞六个按钮会把页头撑成两行
  （窄屏尤其明显）。一个下拉只占一格。
-->
<template>
  <n-select
    class="media-sort"
    size="small"
    :value="value"
    :options="options"
    :consistent-menu-width="false"
    :title="`排序：${currentLabel}`"
    @update:value="onChange"
  />
</template>

<script setup lang="ts">
import { computed } from 'vue';
import { MEDIA_SORT_OPTIONS, mediaSortOf, mediaSortValue, type MediaSort } from '../mediaShared';

const props = defineProps<{ modelValue: MediaSort }>();
const emit = defineEmits<{ 'update:modelValue': [MediaSort] }>();

/** 选项直接由 `MEDIA_SORT_OPTIONS` 生成，**不在这里再抄一份档位表**（抄一份必然分叉）。 */
const options = MEDIA_SORT_OPTIONS.map((o) => ({ label: o.label, value: o.value }));

const value = computed(() => mediaSortValue(props.modelValue));
const currentLabel = computed(() => options.find((o) => o.value === value.value)?.label ?? '');

function onChange(v: string) {
  emit('update:modelValue', mediaSortOf(v));
}
</script>

<style scoped>
/* 宽度固定：六档文案长短不一（「最近修改」4 字、「名称 A→Z」6 字），
   不定宽会在切换档位时让整个页头抖动。 */
.media-sort {
  width: 118px;
  flex: 0 0 auto;
}
@media (max-width: 768px) {
  /* 窄屏页头本来就挤，排序是低频操作，压到刚好看得清文案 */
  .media-sort {
    width: 104px;
  }
}
</style>
