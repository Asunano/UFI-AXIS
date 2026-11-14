<!--
  媒体三页共用的「列表为空 / 无权限 / 读取失败」占位。

  为什么单独一个组件：这三种状态的文案与处置方式完全不同（无权限要去设备上授权、
  读取失败该重试、真的没有文件则无需操作），而三页会各自撞上同样的三种 ——
  各写一份的结果是三页给出三种说法。
-->
<template>
  <div class="media-empty">
    <n-empty :description="text">
      <template v-if="hint" #extra>
        <span class="empty-hint">{{ hint }}</span>
      </template>
    </n-empty>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue';

const props = defineProps<{
  /** 无权限（core 回 403）。优先级最高：没权限时"没有文件"这句话是错的 */
  permissionDenied?: boolean;
  /** 列表请求失败（网络 / 5xx） */
  failed?: boolean;
  /** 这一类媒体的中文名，用于「没有 X 文件」 */
  kindLabel: string;
}>();

const text = computed(() => {
  if (props.permissionDenied) return '设备未授予此类型的媒体访问权限';
  if (props.failed) return '读取媒体列表失败';
  return `没有${props.kindLabel}文件`;
});

const hint = computed(() => {
  if (props.permissionDenied) return '请在设备端授予存储/媒体读取权限后重试。';
  if (props.failed) return '请检查设备连接后点右上角刷新。';
  // 真的没有文件时不给提示：扫描目录配置属于设置项，不该在空列表上教用户改配置
  return '';
});
</script>

<style scoped>
.media-empty {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 220px;
  padding: 24px 0;
}
.empty-hint {
  font-size: 12px;
  color: var(--text-muted);
}
</style>
