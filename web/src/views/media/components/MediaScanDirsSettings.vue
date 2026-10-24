<!--
  媒体扫描范围设置浮层（视频 / 音乐 / 图片三页**共用同一个组件**，2026-10-14）。

  ## 为什么三个页面必须共用这一个组件
  core 的 `MediaRoutes.put("/config")` 是按类型分开写的（`?type=video|audio|image`），
  三页各改自己那一份、互不覆盖 —— 但**界面与交互必须一模一样**，否则用户会在视频页学到
  一套操作、到音乐页又得重新学一遍。所以这里只吃一个 `kind`，其余全部共用。

  ## 2026-10-22 拆分
  内容本体搬到了 `MediaScanDirsPanel.vue`：视频页按方案 A 把「封面预热」与「扫描范围」
  收进同一个 ⚙ 浮层，需要**不带自己的浮层**地复用这块内容。本文件只剩"按钮 + 浮层"的壳，
  音乐页 / 图片页的用法（含 slot 里的排除名单）完全不变。

  ## 空列表的语义
  core `scanDirs(kind)`：**空 = 这一类不限目录**（扫全部该类型媒体），不是"什么都不扫"。
  文案写在面板里。

  ## 为什么浮层显式写 `display-directive="show"`
  音乐页的 slot 里有一个弹窗（已移除歌曲的管理弹窗），弹窗一打开浮层会因"点到外面"而关闭；
  默认 `'if'` 会把弹窗连同宿主一起卸载。`'show'` 只隐藏、不卸载。
-->
<template>
  <n-popover v-model:show="open" trigger="click" placement="bottom-end" :width="360" display-directive="show">
    <template #trigger>
      <n-button size="small" quaternary>
        <template #icon
          ><n-icon><SettingsOutline /></n-icon
        ></template>
        <span v-if="!iconOnly">{{ label }}</span>
      </n-button>
    </template>

    <MediaScanDirsPanel :kind="kind" :active="open" @saved="emit('saved')" @rescanned="emit('rescanned')">
      <template #default="{ active }">
        <slot :active="active" />
      </template>
    </MediaScanDirsPanel>
  </n-popover>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue';
import { SettingsOutline } from '@vicons/ionicons5';
import MediaScanDirsPanel from './MediaScanDirsPanel.vue';
import type { MediaKind } from '../mediaShared';

const props = defineProps<{
  /** 改哪一类的扫描目录。三页传各自的值，core 侧是三份独立配置。 */
  kind: MediaKind;
  /**
   * 触发按钮上的文字。
   *
   * 视频页按方案 A 不再单独放这个入口（并进了 ⚙ 浮层）；音乐页 / 图片页仍叫「设置」，
   * 音乐页的浮层里还有第二个区块（已移除歌曲）。
   */
  label?: string;
  /** 只显示齿轮图标（手机上给页头省宽度） */
  iconOnly?: boolean;
}>();

const emit = defineEmits<{
  /** 保存成功。页面据此重拉列表。 */
  (e: 'saved'): void;
  /** 触发了一次重新扫描。页面据此**延迟**重拉（core 侧扫描是异步的）。 */
  (e: 'rescanned'): void;
}>();

const label = computed(() => props.label || '扫描范围');
/** 浮层是否打开：既驱动面板重拉，也透传给音乐页 slot 里的排除名单。 */
const open = ref(false);
</script>
