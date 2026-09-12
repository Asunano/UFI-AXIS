<!--
  「后端服务已停止」占位页（2026-09-03）。

  用户点了设置 → 服务控制 → 停止服务后，core 侧除 HTTP 之外的自主活动全停：采集、告警、
  定时任务、短信转发、下载轮询、隧道看护。依赖实时数据的页面此时不该继续转圈或显示残留数据，
  由 DefaultLayout 用本组件整体替换正文区（页面被卸载 ⇒ 各自的 useInterval 自动停）。
-->
<template>
  <div class="service-stopped">
    <n-result status="warning" title="后端服务已停止" size="huge">
      <template #footer>
        <div class="desc">
          <p>
            后端服务已被停止，设备侧的数据采集、告警判定、定时任务、短信转发、下载与内网穿透看护都已暂停。
            本页依赖实时数据，因此暂不可用。
          </p>
          <p class="hint">
            重新开启后一切自动恢复：被暂停的下载会断点续传，定时任务重新排期。 设置页在服务停止时始终可用。
          </p>
          <p v-if="serviceStore.errorMessage" class="err">{{ serviceStore.errorMessage }}</p>
        </div>
        <n-space justify="center" :size="12">
          <n-button type="primary" :loading="serviceStore.busy" @click="enable">启动服务</n-button>
          <n-button :disabled="serviceStore.busy" @click="serviceStore.refresh()">刷新状态</n-button>
          <n-button quaternary @click="goSettings">前往设置</n-button>
        </n-space>
      </template>
    </n-result>
  </div>
</template>

<script setup lang="ts">
import { useRouter } from 'vue-router';
import { useServiceStore } from '@/stores/service';

const router = useRouter();
const serviceStore = useServiceStore();

async function enable() {
  await serviceStore.setEnabled(true);
}

function goSettings() {
  router.push({ name: 'settings' });
}
</script>

<style scoped>
.service-stopped {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 60vh;
  padding: 24px;
}

.desc {
  max-width: 420px;
  margin: 0 auto 20px;
  text-align: center;
  font-size: 13px;
  line-height: 1.7;
  opacity: 0.85;
}

.desc .hint {
  margin-top: 8px;
  font-size: 12px;
  opacity: 0.7;
}

.desc .err {
  margin-top: 8px;
  font-size: 12px;
  color: var(--error);
}
</style>
