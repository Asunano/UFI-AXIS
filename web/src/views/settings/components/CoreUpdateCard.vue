<!--
  服务更新 · Core 更新卡。

  ## 归属（2026-10-23）
  core 是**服务**，不是插件：这张卡只在「设置 → 服务更新」（`UpdatePanel.vue`）里渲染；
  「资源中心」（`/resources`）只放 frpc / cloudflared 等插件组件。

  ## 为什么需要它
  2026-10-14 那次"三张更新卡收口到资源中心"之后，core 的检查更新、本地安装、重检 / 重置
  一度两边都没有稳定入口。这张卡把设置页原本那套能力接回服务更新页。

  ## 复用而不是重写
  状态机、轮询、重启复核全在 `stores/update.ts`；
  本地 APK 上传弹窗直接复用 `LocalUpdateModal`。本卡只负责：
    · 读 `status` / `displayState` / `lastResult` 展示；
    · 把四颗按钮翻成 store 动作。
-->
<template>
  <GridCard title="Core 服务更新">
    <div class="cu">
      <div class="cu-versions">
        <div class="cu-ver">
          <span>当前版本</span>
          <b>{{ currentVersion || '—' }}</b>
        </div>
        <div class="cu-ver">
          <span>最新版本</span>
          <b>{{ info?.latestVersion || status.latestVersion || '—' }}</b>
        </div>
        <span class="cu-state" :class="stateClass">{{ stateLabel }}</span>
      </div>

      <n-progress
        v-if="busy"
        type="line"
        :percentage="Math.min(100, Math.max(0, status.progress || 0))"
        :height="6"
        :show-indicator="false"
        processing
      />
      <p v-if="busy" class="cu-hint">{{ status.message || '正在安装，设备将自动重启…' }}</p>

      <p class="cu-hint">
        core 更新将从服务更新源下载 APK 并自动重启，也可推送本机 APK。安装期间设备会短暂断连，属正常现象。
      </p>

      <div class="cu-actions">
        <n-button size="small" type="primary" :loading="checking" :disabled="busy" @click="check"> 检查更新 </n-button>
        <n-button size="small" :disabled="busy" @click="localOpen = true">本地更新</n-button>
        <n-button size="small" quaternary :loading="rechecking" @click="recheck">重新检查状态</n-button>
        <n-button size="small" quaternary :disabled="busy" @click="reset">重置状态</n-button>
      </div>

      <div v-if="lastResultText" class="cu-result" :class="{ bad: !lastResult?.ok }">{{ lastResultText }}</div>
    </div>

    <LocalUpdateModal v-model:show="localOpen" />
  </GridCard>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { useDialog, useMessage } from 'naive-ui';
import GridCard from '@/components/GridCard.vue';
import LocalUpdateModal from '@/views/settings/components/LocalUpdateModal.vue';
import { useUpdateStore } from '@/stores/update';
import { formatLastResult } from '@/views/settings/components/updateResult';

const updateStore = useUpdateStore();
const message = useMessage();
const dialog = useDialog();

const status = computed(() => updateStore.status);
const localOpen = ref(false);
const checking = ref(false);
const rechecking = ref(false);
const info = ref<{ currentVersion: string; latestVersion: string; hasUpdate: boolean } | null>(null);

const currentVersion = computed(() => info.value?.currentVersion || status.value.currentVersion || '');
const busy = computed(() => updateStore.busy);
const stateLabel = computed(() => {
  const s = updateStore.displayState;
  const map: Record<string, string> = {
    idle: '空闲',
    downloading: '下载中',
    uploading: '上传中',
    verifying: '校验中',
    installing: '安装中',
    restarting: '重启中',
    done: '已完成',
    failed: '失败',
    need_push: '需要手动上传',
    timeout: '等待超时',
  };
  return map[s] || s;
});
const stateClass = computed(() => ({
  busy: updateStore.busy,
  ok: updateStore.displayState === 'done',
  bad:
    updateStore.displayState === 'failed' ||
    updateStore.displayState === 'timeout' ||
    updateStore.displayState === 'need_push',
}));

const lastResult = computed(() => updateStore.lastResult);
const lastResultText = computed(() => {
  const r = updateStore.lastResult;
  if (!r) return '';
  return formatLastResult(r, 'APK');
});

async function loadInfo() {
  try {
    info.value = await updateStore.fetchBackendInfo();
  } catch {
    /* 读不到清单不算错：状态行仍显示 core 自报的版本 */
  }
}

async function check() {
  checking.value = true;
  try {
    info.value = await updateStore.fetchBackendInfo();
    if (!info.value.hasUpdate) {
      message.info('已是最新版本');
      return;
    }
    await updateStore.startInstall(info.value.latestVersion);
    message.success(`已开始更新到 v${info.value.latestVersion}，设备会自行重启`);
  } catch (e: any) {
    message.error(e?.response?.data?.message || e?.response?.data?.error || e?.message || '检查更新失败');
  } finally {
    checking.value = false;
  }
}

async function recheck() {
  rechecking.value = true;
  try {
    await updateStore.recheck();
    await loadInfo();
  } finally {
    rechecking.value = false;
  }
}

function reset() {
  dialog.warning({
    title: '重置更新状态',
    content: '只在更新卡住（长时间停在下载/安装/重启）时使用。重置不会删除已下载的安装包，也不会回滚版本。',
    positiveText: '重置',
    negativeText: '取消',
    onPositiveClick: async () => {
      try {
        await updateStore.reset();
        message.success('更新状态已重置');
        await loadInfo();
      } catch (e: any) {
        message.error(e?.response?.data?.message || e?.message || '重置失败');
      }
    },
  });
}

onMounted(() => {
  void updateStore.resume();
  void loadInfo();
});

// 结论/notice 由 store 持久化；这里只消费一次性 toast，避免用户必须盯着卡片。
updateStore.$subscribe(() => {
  const n = updateStore.notice;
  if (!n) return;
  if (n.type === 'success') message.success(n.text);
  else if (n.type === 'error') message.error(n.text);
  else if (n.type === 'warning') message.warning(n.text);
  else message.info(n.text);
  updateStore.clearNotice();
});
</script>

<style scoped>
.cu {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.cu-versions {
  display: flex;
  align-items: center;
  gap: 18px;
  flex-wrap: wrap;
}
.cu-ver {
  display: flex;
  align-items: baseline;
  gap: 6px;
  font-size: 12px;
  color: var(--text-muted);
}
.cu-ver b {
  font-size: 14px;
  color: var(--text-primary);
  font-variant-numeric: tabular-nums;
}
.cu-state {
  margin-left: auto;
  font-size: 11.5px;
  padding: 2px 8px;
  border-radius: var(--radius-pill);
  background: var(--surface-elevated);
  color: var(--text-secondary);
}
.cu-state.busy {
  color: var(--warning);
}
.cu-state.ok {
  color: var(--success);
}
.cu-state.bad {
  color: var(--error);
}
.cu-hint {
  margin: 0;
  font-size: 12px;
  line-height: 1.65;
  color: var(--text-muted);
}
.cu-actions {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}
.cu-result {
  font-size: 12px;
  color: var(--text-secondary);
  border-left: 3px solid var(--success);
  padding-left: 8px;
}
.cu-result.bad {
  border-left-color: var(--error);
}
</style>
