<template>
  <!-- 组件管理（frpc / cloudflared 不随 APK 分发，按需下载）。
       装完基本不用再看，所以从常驻卡片改成概览卡头的弹窗入口。 -->
  <n-modal
    :show="show"
    preset="card"
    title="组件管理"
    style="max-width: 760px"
    @update:show="emit('update:show', $event)"
  >
    <div v-if="comp.manifestError" class="inst-err">
      更新源不可用：{{ comp.manifestError }}（仍可用下方「本地上传」安装）
    </div>
    <div class="inst-list">
      <div v-for="c in comp.items" :key="c.id" class="inst-item">
        <div class="inst-main">
          <div class="inst-title">
            <span class="inst-name">{{ c.name }}</span>
            <n-tag v-if="c.installed" size="tiny" type="success" :bordered="false">
              已安装 {{ c.installed_version || '版本未知' }}
            </n-tag>
            <n-tag v-else size="tiny" type="warning" :bordered="false">未安装</n-tag>
            <n-tag v-if="c.update_available" size="tiny" type="info" :bordered="false">
              有新版 {{ c.latest_version }}
            </n-tag>
            <n-tag v-if="c.source === 'manual'" size="tiny" :bordered="false">本地上传</n-tag>
            <n-tag v-if="c.source === 'legacy'" size="tiny" :bordered="false">旧版迁移</n-tag>
          </div>
          <div class="inst-sub">
            {{ c.description }}
            <template v-if="c.installed">　·　占用 {{ formatBytes(c.installed_size) }}</template>
            <template v-else-if="c.download_size">　·　需下载约 {{ formatBytes(c.download_size) }}</template>
          </div>
          <div v-if="c.upstream" class="inst-sub">
            上游：{{ c.upstream }}<template v-if="c.latest_version">　·　最新 {{ c.latest_version }}</template>
          </div>
          <n-progress
            v-if="comp.progress.id === c.id && progressActive"
            :percentage="comp.progress.percent"
            :height="6"
            :show-indicator="false"
            style="margin-top: 6px"
          />
          <div v-if="comp.progress.id === c.id && comp.progress.message" class="inst-sub">
            {{ comp.progress.message }}
          </div>
        </div>
        <n-space :size="4" class="inst-actions">
          <n-button
            v-if="!c.installed || c.update_available"
            size="tiny"
            type="primary"
            :disabled="!c.available || progressActive"
            :loading="comp.progress.id === c.id && progressActive"
            @click="emit('install', c.id)"
          >
            {{ c.installed ? '更新' : '下载安装' }}
          </n-button>
          <n-button size="tiny" quaternary :disabled="progressActive" @click="pickUpload(c.id)">本地上传</n-button>
          <n-button
            v-if="c.installed"
            size="tiny"
            quaternary
            type="error"
            :disabled="progressActive"
            @click="emit('uninstall', c)"
            >卸载</n-button
          >
        </n-space>
      </div>
    </div>
    <div class="hint">
      组件从上游官方 release 直链下载，经 SHA-256 校验与 arm64 ELF 体检后安装到 filesDir/components/。
      大陆网络可在「设置 → 更新」里配置镜像前缀加速。无外网时用「本地上传」手动提供裸二进制或官方 tar.gz。
    </div>
    <input ref="uploadInput" type="file" style="display: none" @change="onUploadPicked" />
    <template #footer>
      <n-space justify="end" :size="8">
        <n-button size="small" :loading="comp.refreshing" @click="emit('refresh')">检查更新</n-button>
        <n-button size="small" @click="emit('update:show', false)">关闭</n-button>
      </n-space>
    </template>
  </n-modal>
</template>

<script setup lang="ts">
import { ref } from 'vue';
import { formatBytes } from '@/composables/utils';
import type { ComponentItem } from '@/composables/useComponentInstaller';

/**
 * 纯展示 + 动作透传。**状态与轮询刻意不在这里**：
 * 安装进度要在弹窗关闭后继续跑（"可离开本页，进度会继续"），而 n-modal 关闭时会销毁子组件。
 * 所以 comp / progressActive 由页面通过 `useComponentInstaller()` 持有，详见那个 composable 的文件头。
 */
defineProps<{
  show: boolean;
  comp: {
    items: ComponentItem[];
    progress: { id: string; state: string; percent: number; message: string };
    manifestError: string;
    refreshing: boolean;
  };
  progressActive: boolean;
}>();

const emit = defineEmits<{
  'update:show': [boolean];
  install: [string];
  uninstall: [ComponentItem];
  /** 选好文件后才发，父组件不需要碰 <input> */
  upload: [string, File];
  refresh: [];
}>();

// 隐藏的 file input 留在本组件：它只是「本地上传」按钮的实现细节，
// 父组件不该为了点一下这个按钮而持有一个 DOM ref。
const uploadInput = ref<HTMLInputElement | null>(null);
const uploadTargetId = ref('');

function pickUpload(id: string) {
  uploadTargetId.value = id;
  if (uploadInput.value) {
    uploadInput.value.value = '';
    uploadInput.value.click();
  }
}

function onUploadPicked(ev: Event) {
  const file = (ev.target as HTMLInputElement).files?.[0];
  const id = uploadTargetId.value;
  uploadTargetId.value = '';
  if (!file || !id) return;
  emit('upload', id, file);
}
</script>

<style scoped>
.inst-list {
  display: flex;
  flex-direction: column;
}
.inst-item {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  padding: 10px 0;
  border-bottom: 1px solid var(--border-subtle);
}
.inst-item:last-child {
  border-bottom: none;
}
.inst-main {
  min-width: 0;
  flex: 1;
}
.inst-title {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.inst-name {
  font-size: 13px;
  color: var(--text-primary);
  font-weight: 600;
}
.inst-sub {
  margin-top: 3px;
  font-size: 12px;
  color: var(--text-secondary);
  word-break: break-all;
}
.inst-err {
  margin-top: 3px;
  font-size: 12px;
  color: var(--error);
  word-break: break-all;
}
.inst-actions {
  flex-shrink: 0;
}
.hint {
  margin-top: 8px;
  font-size: 12px;
  color: var(--text-muted);
}

@media (max-width: 768px) {
  .inst-item {
    flex-wrap: wrap;
  }
  .inst-actions {
    width: 100%;
  }
}
</style>
