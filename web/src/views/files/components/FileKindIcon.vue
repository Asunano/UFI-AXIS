<template>
  <span class="file-icon-wrap" :class="`k-${kind}`" :title="kindLabel">
    <!-- 目录 -->
    <svg v-if="kind === 'dir'" viewBox="0 0 24 24" class="ficon" aria-hidden="true">
      <path fill="currentColor" d="M10 4H4c-1.1 0-2 .9-2 2v12c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V8c0-1.1-.9-2-2-2h-8l-2-2z" />
    </svg>
    <!-- 图片 -->
    <svg v-else-if="kind === 'image'" viewBox="0 0 24 24" class="ficon" aria-hidden="true">
      <path fill="currentColor" d="M21 19V5c0-1.1-.9-2-2-2H5c-1.1 0-2 .9-2 2v14c0 1.1.9 2 2 2h14c1.1 0 2-.9 2-2zM8.5 13.5l2.5 3.01L14.5 12l4.5 6H5l3.5-4.5z" />
    </svg>
    <!-- 视频 -->
    <svg v-else-if="kind === 'video'" viewBox="0 0 24 24" class="ficon" aria-hidden="true">
      <path fill="currentColor" d="M17 10.5V7c0-.55-.45-1-1-1H4c-.55 0-1 .45-1 1v10c0 .55.45 1 1 1h12c.55 0 1-.45 1-1v-3.5l4 4v-11l-4 4z" />
    </svg>
    <!-- 音频 -->
    <svg v-else-if="kind === 'audio'" viewBox="0 0 24 24" class="ficon" aria-hidden="true">
      <path fill="currentColor" d="M12 3v10.55A4 4 0 1 0 14 17V7h4V3h-6z" />
    </svg>
    <!-- 文本 -->
    <svg v-else-if="kind === 'text'" viewBox="0 0 24 24" class="ficon" aria-hidden="true">
      <path fill="currentColor" d="M14 2H6c-1.1 0-2 .9-2 2v16c0 1.1.9 2 2 2h12c1.1 0 2-.9 2-2V8l-6-6zm2 16H8v-2h8v2zm0-4H8v-2h8v2zm-3-5V3.5L18.5 9H13z" />
    </svg>
    <!-- 压缩包 -->
    <svg v-else-if="kind === 'archive'" viewBox="0 0 24 24" class="ficon" aria-hidden="true">
      <path fill="currentColor" d="M20 6h-8l-2-2H4c-1.1 0-2 .9-2 2v12c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V8c0-1.1-.9-2-2-2zm-6 8h-2v-1h-1v1h-2v-1h-1v1H6v-1H5v4h14v-4h-5v1h-1v-1zm1-3h-2V7h2v4z" />
    </svg>
    <!-- APK -->
    <svg v-else-if="kind === 'apk'" viewBox="0 0 24 24" class="ficon" aria-hidden="true">
      <path fill="currentColor" d="M6 18c0 .55.45 1 1 1h1v3.5c0 .83.67 1.5 1.5 1.5s1.5-.67 1.5-1.5V19h2v3.5c0 .83.67 1.5 1.5 1.5s1.5-.67 1.5-1.5V19h1c.55 0 1-.45 1-1V8H6v10zM3.5 8C2.67 8 2 8.67 2 9.5v7c0 .83.67 1.5 1.5 1.5S5 17.33 5 16.5v-7C5 8.67 4.33 8 3.5 8zm17 0c-.83 0-1.5.67-1.5 1.5v7c0 .83.67 1.5 1.5 1.5s1.5-.67 1.5-1.5v-7c0-.83-.67-1.5-1.5-1.5zM15.53 2.16l1.3-1.3c.2-.2.2-.51 0-.71-.2-.2-.51-.2-.71 0l-1.48 1.48A5.84 5.84 0 0 0 12 1c-.96 0-1.86.23-2.66.63L7.85.15c-.2-.2-.51-.2-.71 0-.2.2-.2.51 0 .71l1.31 1.31A5.983 5.983 0 0 0 6 7h12c0-2.02-1-3.81-2.47-4.84zM10 5H9V4h1v1zm5 0h-1V4h1v1z" />
    </svg>
    <!-- 文档 -->
    <svg v-else-if="kind === 'doc'" viewBox="0 0 24 24" class="ficon" aria-hidden="true">
      <path fill="currentColor" d="M18 2H6c-1.1 0-2 .9-2 2v16c0 1.1.9 2 2 2h12c1.1 0 2-.9 2-2V4c0-1.1-.9-2-2-2zM9 4h2v5l-1 .75L9 9V4zm9 16H6V4h1v9h5v7h6z" />
    </svg>
    <!-- 未知文件 -->
    <svg v-else viewBox="0 0 24 24" class="ficon" aria-hidden="true">
      <path fill="currentColor" d="M6 2c-1.1 0-2 .9-2 2v16c0 1.1.9 2 2 2h12c1.1 0 2-.9 2-2V8l-6-6H6zm7 7V3.5L18.5 9H13z" />
    </svg>
  </span>
</template>

<script setup lang="ts">
/** 对齐 App FileIcon 的语义分类：用 SVG，禁止 emoji。 */
import { computed } from 'vue';
import { fileIconKind, type FileEntry, type FileIconKind } from '../filesShared';

const props = defineProps<{ file: FileEntry }>();

const kind = computed<FileIconKind>(() => fileIconKind(props.file));

const kindLabel = computed(() => {
  const map: Record<FileIconKind, string> = {
    dir: '文件夹',
    image: '图片',
    video: '视频',
    audio: '音频',
    text: '文本',
    archive: '压缩包',
    apk: '安装包',
    doc: '文档',
    file: '文件',
  };
  return map[kind.value];
});
</script>

<style scoped>
.file-icon-wrap {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 36px;
  height: 36px;
  flex-shrink: 0;
  border-radius: 8px;
  color: var(--text-secondary);
  background: var(--surface-hover);
}
.file-icon-wrap.k-dir {
  color: var(--accent-color, #4f8cff);
  background: var(--accent-color-light, rgba(79, 140, 255, 0.12));
}
.file-icon-wrap.k-image {
  color: #2f9e6b;
}
.file-icon-wrap.k-video {
  color: #6b5cff;
}
.file-icon-wrap.k-audio {
  color: #c45c26;
}
.file-icon-wrap.k-text {
  color: #3b82c4;
}
.file-icon-wrap.k-archive {
  color: #a67c00;
}
.file-icon-wrap.k-apk {
  color: #34a853;
}
.file-icon-wrap.k-doc {
  color: #d64545;
}
.ficon {
  width: 20px;
  height: 20px;
}
</style>
