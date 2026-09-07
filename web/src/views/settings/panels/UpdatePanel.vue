<template>
  <div class="settings-panel">
    <!-- Card 1.5: 前端资源 -->
    <GridCard title="前端资源">
      <template #extra>
        <n-button size="tiny" quaternary @click="loadWebVersion">刷新</n-button>
      </template>
      <div class="web-version-section">
        <InfoRow label="当前模式">
          <template #default>
            <n-tag :type="webVersion.mode === 'override' ? 'success' : 'default'" size="small" :bordered="false">
              {{ webVersion.mode === 'override' ? '覆盖版本' : '内置版本' }}
            </n-tag>
          </template>
        </InfoRow>
        <InfoRow label="前端版本" :value="webVersion.version || '--'" />
        <InfoRow label="构建时间" :value="webVersion.buildTime || '--'" />
        <InfoRow v-if="webVersion.mode === 'override'" label="内置版本" :value="webVersion.bundledVersion || '--'" />
      </div>
      <!-- Web 自动更新状态（从 root version.json 的 web 对象自拉取） -->
      <div v-if="showWebUpdateStatus" class="web-update-status">
        <div class="web-update-status-head">
          <n-tag :type="webUpdateStateType" size="small" :bordered="false">{{ webUpdateStateLabel }}</n-tag>
          <span class="web-update-status-msg">{{ webUpdateStatus.message }}</span>
        </div>
        <n-progress
          v-if="webUpdateShowProgress"
          type="line"
          :percentage="Math.min(100, webUpdateStatus.progress)"
          :height="6"
          :show-indicator="false"
        />
        <div v-if="webUpdateStatus.latestVersion" class="web-update-status-ver">
          当前 <b>{{ webUpdateStatus.currentVersion || '—' }}</b>
          <span class="arrow">→</span>
          最新 <b>{{ webUpdateStatus.latestVersion }}</b>
        </div>
      </div>
      <div class="card-actions">
        <n-button type="info" size="small" :loading="webChecking" :disabled="webChecking" @click="checkWebUpdate"
          >检查更新</n-button
        >
        <n-button type="primary" size="small" :loading="webUploading" @click="triggerUpload">上传更新</n-button>
        <n-button v-if="webVersion.mode === 'override'" type="warning" size="small" @click="confirmClearWeb"
          >恢复内置</n-button
        >
        <n-button v-if="webVersion.hasBackup" type="default" size="small" @click="confirmRollbackWeb">回滚</n-button>
      </div>
      <input ref="fileInputRef" type="file" accept=".zip" style="display: none" @change="handleFileSelect" />
    </GridCard>

    <!-- Card 1.6: 后端更新（core APK 自更新，/api/update/*）-->
    <GridCard title="后端更新">
      <template #extra>
        <n-button size="tiny" quaternary :loading="backendLoading" @click="loadBackendStatus">刷新</n-button>
      </template>
      <InfoRow label="当前版本" :value="backendStatus.currentVersion || '--'" />
      <InfoRow label="最新版本" :value="backendLatestLabel" />
      <InfoRow label="状态">
        <template #default>
          <n-tag :type="backendStateType" size="small" :bordered="false">{{ backendStateLabel }}</n-tag>
        </template>
      </InfoRow>
      <div v-if="backendStatus.message" class="web-update-status-msg">{{ backendStatus.message }}</div>
      <n-progress
        v-if="backendShowProgress"
        type="line"
        :percentage="Math.min(100, backendStatus.progress)"
        :height="6"
        :show-indicator="false"
      />
      <!-- 浏览器 → core 的 APK 上传进度。与上面那条（core 下载上游 APK 的进度）是两段不同的链路，
           上传期间 state 为 uploading，两条同时出现时上面那条只反映 core 侧、不动，容易误读，
           所以这里带一行文字说明当前传了多少。 -->
      <div v-if="apkUploading" class="apk-upload-progress">
        <n-progress type="line" :percentage="apkUploadPercent" :height="6" :show-indicator="false" />
        <div v-if="apkUploadLabel" class="update-hint">{{ apkUploadLabel }}</div>
      </div>
      <!-- 「检查更新」的结果：只查不装。装不装由用户在二次确认弹窗里决定 -->
      <div v-if="backendCheck" class="backend-check">
        <div class="web-update-status-ver">
          当前 <b>{{ backendCheck.currentVersion || '—' }}</b>
          <span class="arrow">→</span>
          最新 <b>{{ backendCheck.latestVersion || '—' }}</b>
        </div>
        <div v-if="backendCheck.changelog" class="update-hint">更新说明：{{ backendCheck.changelog }}</div>
        <div v-if="backendCheck.apkSize > 0" class="update-hint">安装包：{{ formatBytes(backendCheck.apkSize) }}</div>
        <div v-if="!backendCheck.hasUpdate" class="update-hint">已是最新版本，无需更新。</div>
      </div>
      <div v-if="backendPhaseHint" class="update-hint">{{ backendPhaseHint }}</div>
      <div class="card-actions">
        <n-button
          type="info"
          size="small"
          :loading="backendChecking"
          :disabled="backendBusy"
          @click="checkBackendUpdate"
        >
          检查更新
        </n-button>
        <n-button type="primary" size="small" :loading="apkUploading" :disabled="backendBusy" @click="triggerApkUpload">
          上传 APK
        </n-button>
        <n-button
          v-if="backendStatus.apkPath"
          type="warning"
          size="small"
          :disabled="backendBusy"
          @click="confirmInstallLocal"
        >
          安装已上传的包
        </n-button>
        <n-button v-if="showBackendRecheck" size="small" :loading="backendLoading" @click="recheckBackendUpdate">
          重新检查状态
        </n-button>
        <n-button v-if="showBackendReset" size="small" :disabled="backendBusy" @click="resetBackendUpdate">
          重置状态
        </n-button>
      </div>
      <input ref="apkInputRef" type="file" accept=".apk" style="display: none" @change="handleApkSelect" />

      <!-- 手机 App 安装包（core 从更新清单的 frontend 对象解析）-->
      <div class="app-apk-section">
        <div class="app-apk-head">
          <span class="app-apk-title">手机 App 安装包</span>
          <n-button size="tiny" quaternary :loading="appApkLoading" @click="loadAppApkInfo">查询</n-button>
        </div>
        <template v-if="appApk.version">
          <InfoRow
            label="最新版本"
            :value="`${appApk.version}${appApk.versionCode ? ` (${appApk.versionCode})` : ''}`"
          />
          <InfoRow v-if="appApk.changelog" label="更新说明" :value="appApk.changelog" />
          <!-- 不用 GET /api/update/frontend-apk：它需要 Bearer 头，<a download> 带不上；
               apk_url 是上游直链，浏览器可直接下载 -->
          <div v-if="appApk.apkUrl" class="app-apk-link">
            <a :href="appApk.apkUrl" target="_blank" rel="noreferrer noopener">下载 APK（上游直链）</a>
          </div>
        </template>
        <div v-else class="update-hint">点「查询」向 core 请求更新清单里的 App 版本信息（结果缓存 5 分钟）。</div>
      </div>
    </GridCard>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted, onUnmounted, watch } from 'vue';
import { useInterval } from '@/composables/useRealtime';
import { useMessage, useDialog } from 'naive-ui';
import { useCancellableApi } from '@/composables/useCancellableApi';
import GridCard from '@/components/GridCard.vue';
import InfoRow from '@/components/InfoRow.vue';
import { formatBytes } from '@/composables/utils';
import { Endpoints } from '@/api/contract';
import { useUpdateStore, BACKEND_BUSY_STATES, type BackendUpdateInfo } from '@/stores/update';

const message = useMessage();
const dialog = useDialog();
const api = useCancellableApi();

// ── 前端资源 ──
const webVersion = reactive({
  mode: 'bundled' as 'bundled' | 'override',
  version: '',
  buildTime: '',
  bundledVersion: '',
  bundledBuildTime: '',
  hasBackup: false,
});
const webUploading = ref(false);
const fileInputRef = ref<HTMLInputElement | null>(null);

// ── Web 自动更新（从 root version.json 的 `web` 对象自拉取）──
const webChecking = ref(false);
const webUpdateStatus = reactive({
  state: 'idle' as string,
  progress: 0 as number,
  message: '' as string,
  currentVersion: '' as string,
  latestVersion: '' as string,
});
// 后端异步执行更新，轮询状态直到终态；autoStart=false → 由 checkWebUpdate 手动启动
const webUpdatePoll = useInterval(
  async () => {
    try {
      const { data } = await api.get('/api/web/status');
      Object.assign(webUpdateStatus, normalizeWebUpdateStatus(data));
      if (isWebUpdateTerminal(data?.state)) finishWebUpdate(data);
    } catch {
      stopWebUpdateTimer();
      webChecking.value = false;
      message.error('查询 Web 更新状态失败');
    }
  },
  1000,
  { autoStart: false }
);

const WEB_UPDATE_STATE_META: Record<
  string,
  { label: string; type: 'default' | 'info' | 'success' | 'warning' | 'error' }
> = {
  idle: { label: '空闲', type: 'default' },
  checking: { label: '检查中', type: 'info' },
  downloading: { label: '下载中', type: 'info' },
  verifying: { label: '校验中', type: 'info' },
  installing: { label: '安装中', type: 'warning' },
  uploading: { label: '上传中', type: 'info' },
  done: { label: '更新完成', type: 'success' },
  failed: { label: '更新失败', type: 'error' },
  need_push: { label: '需手动上传', type: 'warning' },
};
const webUpdateStateLabel = computed(
  () => WEB_UPDATE_STATE_META[webUpdateStatus.state]?.label ?? webUpdateStatus.state
);
const webUpdateStateType = computed(() => WEB_UPDATE_STATE_META[webUpdateStatus.state]?.type ?? 'default');
const webUpdateShowProgress = computed(
  () =>
    ['downloading', 'verifying', 'installing'].includes(webUpdateStatus.state) ||
    (webUpdateStatus.progress > 0 && !['done', 'failed', 'need_push', 'idle'].includes(webUpdateStatus.state))
);
const showWebUpdateStatus = computed(() => webChecking.value || webUpdateStatus.state !== 'idle');

function normalizeWebUpdateStatus(d: any) {
  return {
    state: (d?.state || 'idle') as string,
    progress: typeof d?.progress === 'number' ? d.progress : 0,
    message: d?.message || '',
    currentVersion: d?.current_version || '',
    latestVersion: d?.latest_version || '',
  };
}

function isWebUpdateTerminal(state: any): boolean {
  return ['done', 'failed', 'need_push', 'idle'].includes((state || '').toLowerCase());
}

function stopWebUpdateTimer() {
  webUpdatePoll.stop();
}

async function checkWebUpdate() {
  if (webChecking.value) return;
  webChecking.value = true;
  try {
    const { data: start } = await api.post('/api/web/check');
    Object.assign(webUpdateStatus, normalizeWebUpdateStatus(start));
    if (isWebUpdateTerminal(start?.state)) {
      finishWebUpdate(start);
      return;
    }
    webUpdatePoll.start();
  } catch (err: any) {
    webChecking.value = false;
    message.error(err?.response?.data?.error || '检查 Web 更新失败');
  }
}

function finishWebUpdate(d: any) {
  stopWebUpdateTimer();
  webChecking.value = false;
  Object.assign(webUpdateStatus, normalizeWebUpdateStatus(d));
  const s = (d?.state || '').toLowerCase();
  if (s === 'done') {
    message.success(`Web 已更新到 v${d?.latest_version || ''}`);
    loadWebVersion();
  } else if (s === 'failed') {
    message.error(d?.message || 'Web 更新失败');
  } else if (s === 'need_push') {
    message.warning(d?.message || '更新源不可用，请手动上传 ZIP');
  } else if (s === 'idle') {
    message.info(d?.message || 'Web 已是最新版本');
  }
}

function triggerUpload() {
  fileInputRef.value?.click();
}

async function handleFileSelect(e: Event) {
  const input = e.target as HTMLInputElement;
  const file = input.files?.[0];
  if (!file) return;
  input.value = ''; // reset so same file can be re-selected
  webUploading.value = true;
  try {
    const formData = new FormData();
    formData.append('file', file);
    const { data } = await api.post('/api/web/update', formData, {
      headers: { 'Content-Type': 'multipart/form-data' },
    });
    if (data.success) {
      message.success('前端已更新，即将刷新页面…');
      loadWebVersion();
      // 服务端 web 资源已覆盖，刷新以加载新前端
      setTimeout(() => window.location.reload(), 1200);
    } else {
      message.error(data.error || '更新失败');
    }
  } catch (err: any) {
    message.error(err?.response?.data?.error || '上传失败');
  } finally {
    webUploading.value = false;
  }
}

function confirmClearWeb() {
  dialog.warning({
    title: '恢复内置版本',
    content: '确定清除覆盖版本，恢复为 APK 内置的前端？',
    positiveText: '确定',
    negativeText: '取消',
    onPositiveClick: async () => {
      try {
        await api.post('/api/web/clear');
        message.success('已恢复内置版本，即将刷新页面…');
        loadWebVersion();
        // 必须 reload：当前页面跑的还是旧 override 的 JS bundle，而 /assets/* 带
        // immutable 长缓存，不刷新用户会以为"点了没反应"。与上传更新保持一致。
        setTimeout(() => window.location.reload(), 1200);
      } catch (e: any) {
        // core 删不干净时返回 HTTP 500 + error 文案（如"override 目录仍存在，请重启服务后重试"），
        // 直接吞掉换成"操作失败"会让用户以为已经恢复好了
        message.error(e?.response?.data?.error || e?.response?.data?.message || '操作失败');
      }
    },
  });
}

function confirmRollbackWeb() {
  dialog.warning({
    title: '回滚前端版本',
    content: '确定回滚到上一版本的前端？',
    positiveText: '回滚',
    negativeText: '取消',
    onPositiveClick: async () => {
      try {
        const { data } = await api.post('/api/web/rollback');
        if (data.error) {
          message.error(data.error);
        } else {
          message.success('已回滚，即将刷新页面…');
          loadWebVersion();
          setTimeout(() => window.location.reload(), 1200);
        }
      } catch (e: any) {
        message.error(e?.response?.data?.error || e?.response?.data?.message || '回滚失败');
      }
    },
  });
}

async function loadWebVersion() {
  try {
    const { data } = await api.get('/api/web/version');
    Object.assign(webVersion, {
      mode: data.mode || 'bundled',
      version: data.version || '',
      buildTime: data.buildTime || '',
      bundledVersion: data.bundledVersion || '',
      bundledBuildTime: data.bundledBuildTime || '',
      hasBackup: data.hasBackup ?? false,
    });
  } catch {
    /* silent */
  }
}

// ── 后端更新（core APK 自更新，/api/update/*）──
//
// 与前端资源更新的三处差异，决定了下面必须单独写一套而不能复用 web 的那套：
// ① 失败信封不同：/api/web/* 只回 { error }，/api/update/* 回真实 HTTP 码 +
//    { success:false, ok:false, error, message, code }（如 409 UPDATE_IN_PROGRESS）。
// ② 安装期间 core 会重启自身、8088 短暂不可达，轮询失败**不能**当成错误终止。
// ③ 多一个 uploading 态与 apk_path 字段（手动上传后要用 apk_path 调 install-local）。
// ④ 2026-09-06：状态与轮询整体搬到 `stores/update.ts` —— 刷新页面 / 切走再回来必须能接着走，
//    且「更新完成」要跨 core 重启（等 /health 就绪 + 复核版本号），这段窗口比组件生命周期长。
const updateStore = useUpdateStore();
/** core 侧状态的只读视图（真源在 store，组件不再自己存一份） */
const backendStatus = computed(() => updateStore.status);
/** 「检查更新」的结果（只检查，未安装）；null = 本次会话还没查过 */
const backendCheck = ref<BackendUpdateInfo | null>(null);
const backendLoading = ref(false);
const backendChecking = ref(false);
const apkUploading = ref(false);
// 上传进度是**浏览器 → core** 这一段的本地进度，与 backendStatus.progress（core 下载上游 APK 的
// 进度）不是同一回事，所以单独存一份，不能复用 backendShowProgress 那条进度条。
const apkUploadPercent = ref(0);
const apkUploadLabel = ref('');
const apkInputRef = ref<HTMLInputElement | null>(null);

// 状态标签表比 web 那份多两个**只存在于前端**的键：
// restarting（core 已装完正在重启，core 侧没有对应 state）与 timeout（前端等到超时）。
const BACKEND_STATE_META: Record<
  string,
  { label: string; type: 'default' | 'info' | 'success' | 'warning' | 'error' }
> = {
  ...WEB_UPDATE_STATE_META,
  restarting: { label: 'core 重启中', type: 'warning' },
  timeout: { label: '更新超时', type: 'error' },
};

const backendBusy = computed(() => backendChecking.value || apkUploading.value || updateStore.busy);
const backendStateLabel = computed(
  () => BACKEND_STATE_META[updateStore.displayState]?.label ?? updateStore.displayState
);
const backendStateType = computed(() => BACKEND_STATE_META[updateStore.displayState]?.type ?? 'default');
const backendShowProgress = computed(() => BACKEND_BUSY_STATES.includes(backendStatus.value.state));
const backendLatestLabel = computed(
  () => backendCheck.value?.latestVersion || backendStatus.value.latestVersion || '--'
);
const showBackendRecheck = computed(
  () => updateStore.phase === 'timeout' || backendStatus.value.state === 'failed'
);
const showBackendReset = computed(
  () => ['failed', 'need_push', 'done'].includes(backendStatus.value.state) || updateStore.phase === 'timeout'
);

/** 阶段提示：重启期间必须明确说「core 正在重启」，不能显示成"完成"。 */
const backendPhaseHint = computed(() => {
  if (updateStore.restarting) {
    const target = updateStore.targetVersion ? ` v${updateStore.targetVersion}` : '';
    return `安装包已就位，core 正在重启${target}，请稍候 —— 接口会中断十几秒，页面正在轮询 /health 探测就绪。`;
  }
  if (updateStore.phase === 'timeout') {
    const why = updateStore.lastProbeError ? `（最后一次探测：${updateStore.lastProbeError}）` : '';
    return `等待超时，已停止轮询${why}。core 可能仍在重启，点「重新检查状态」再确认一次。`;
  }
  if (backendStatus.value.state === 'installing') {
    return '安装过程中 core 会重启自身，此期间接口短暂不可达属正常；页面会持续重试。';
  }
  if (backendStatus.value.state === 'need_push') {
    return '更新源不可达。可手动上传 APK，上传完成后点「安装已上传的包」。';
  }
  return '';
});

function updateErr(e: any, fallback: string): string {
  const d = e?.response?.data;
  return d?.message || d?.error || fallback;
}

/** 手动刷新 core 状态（该端点有副作用：INSTALLING 态时 core 会顺带回读 watchdog 日志刷新结果）。 */
async function loadBackendStatus() {
  backendLoading.value = true;
  try {
    await updateStore.refreshStatus();
  } finally {
    backendLoading.value = false;
  }
}

/**
 * 「检查更新」= **只检查**。
 *
 * 原来这里直接打 `POST /api/update/check`，而那个端点的语义是「检查+下载+校验+安装+重启」
 * 一条龙 —— 点一下"检查"就把 core 装了，全程没有确认。现在改成先打 backend-info
 * （只读清单 + 版本比对，不碰 core 的状态机），展示「当前 → 最新」+ changelog，
 * 有新版才弹二次确认，用户点了才真正触发安装。
 */
async function checkBackendUpdate() {
  if (backendBusy.value) return;
  backendChecking.value = true;
  try {
    const info = await updateStore.fetchBackendInfo();
    backendCheck.value = info;
    if (!info.hasUpdate) {
      message.info(`已是最新版本（v${info.currentVersion || '--'}）`);
      return;
    }
    confirmBackendInstall(info);
  } catch (e: any) {
    // 502 UPSTREAM_FAILED：更新源不可用（URL 不可达 / 不是版本清单 JSON）
    message.error(updateErr(e, '检查后端更新失败'));
  } finally {
    backendChecking.value = false;
  }
}

/** 二次确认：必须说清 core 会重启、接口会中断十几秒。 */
function confirmBackendInstall(info: BackendUpdateInfo) {
  const size = info.apkSize > 0 ? `，安装包 ${formatBytes(info.apkSize)}` : '';
  const changelog = info.changelog ? `更新说明：${info.changelog}。` : '';
  dialog.warning({
    title: `发现新版本 v${info.latestVersion}`,
    content:
      `当前 v${info.currentVersion || '--'} → 最新 v${info.latestVersion}${size}。${changelog}` +
      '确认后 core 会自行下载、校验并安装，安装完成会重启自身：期间所有接口（含本页面）中断十几秒，' +
      '正在进行的下载/上传任务会被打断。确定现在更新？',
    positiveText: '下载并安装',
    negativeText: '取消',
    onPositiveClick: async () => {
      try {
        await updateStore.startInstall(info.latestVersion);
        message.info('已开始下载安装，期间接口可能不可达；离开本页也会继续跟踪进度');
      } catch (e: any) {
        message.error(updateErr(e, '触发更新失败'));
      }
    },
  });
}

/** 超时/失败后的「重新检查状态」入口：再确认一次，忙态则重新续上轮询。 */
async function recheckBackendUpdate() {
  backendLoading.value = true;
  try {
    await updateStore.recheck();
  } finally {
    backendLoading.value = false;
  }
}

function triggerApkUpload() {
  apkInputRef.value?.click();
}

async function handleApkSelect(e: Event) {
  const input = e.target as HTMLInputElement;
  const file = input.files?.[0];
  if (!file) return;
  input.value = '';
  apkUploading.value = true;
  apkUploadPercent.value = 0;
  apkUploadLabel.value = `准备上传 ${file.name}（${formatBytes(file.size)}）`;
  try {
    const formData = new FormData();
    // 字段名必须是 file：core 只认这一个 multipart 字段名
    formData.append('file', file);
    const { data } = await api.post(Endpoints.update.upload, formData, {
      headers: { 'Content-Type': 'multipart/form-data' },
      timeout: 0,
      // core APK 约 15MB，走设备 Wi-Fi 要几十秒。没有进度只有一个转圈的按钮时，
      // 用户无法区分"在传"和"卡死了"。e.total 在个别浏览器/代理下缺失，回落到 file.size。
      onUploadProgress: (ev: { loaded: number; total?: number }) => {
        const total = ev.total || file.size;
        if (!total) return;
        const loaded = Math.min(ev.loaded, total);
        apkUploadPercent.value = Math.floor((loaded / total) * 100);
        apkUploadLabel.value =
          apkUploadPercent.value >= 100
            ? '已传完，设备正在校验并落盘…'
            : `${apkUploadPercent.value}% · ${formatBytes(loaded)} / ${formatBytes(total)}`;
      },
    });
    if (data?.apk_path) {
      updateStore.setApkPath(data.apk_path);
      message.success('APK 已上传，点「安装已上传的包」继续');
    } else {
      message.error(data?.message || '上传失败');
    }
    await loadBackendStatus();
  } catch (err: any) {
    // 409 UPDATE_IN_PROGRESS：已有更新在跑；400 BAD_REQUEST：不是 APK / 没收到文件
    message.error(updateErr(err, '上传 APK 失败'));
  } finally {
    apkUploading.value = false;
    apkUploadPercent.value = 0;
    apkUploadLabel.value = '';
  }
}

function confirmInstallLocal() {
  const apkPath = backendStatus.value.apkPath;
  if (!apkPath) return;
  dialog.warning({
    title: '安装已上传的包',
    content:
      '安装时 core 会重启自身，接口（含本页面）会中断十几秒；安装完成后页面会等 /health 恢复再报结果。确定继续？',
    positiveText: '安装',
    negativeText: '取消',
    onPositiveClick: async () => {
      try {
        const ok = await updateStore.installLocal(apkPath);
        if (!ok) {
          message.error(backendStatus.value.message || '安装未启动');
          return;
        }
        message.info('已开始安装，期间接口可能不可达');
      } catch (e: any) {
        message.error(updateErr(e, '安装失败'));
      }
    },
  });
}

async function resetBackendUpdate() {
  try {
    // reset 是"忘掉这次更新"的语义：store 会同时清掉本地阶段、apkPath 与超时标记
    await updateStore.reset();
    backendCheck.value = null;
    message.success('已重置更新状态');
  } catch (e: any) {
    message.error(updateErr(e, '重置失败'));
  }
}

// 手机 App 安装包信息（core 解析更新清单的 frontend 对象，结果缓存 5 分钟）
const appApk = reactive({ version: '', versionCode: '', changelog: '', apkUrl: '' });
const appApkLoading = ref(false);

async function loadAppApkInfo() {
  appApkLoading.value = true;
  try {
    const { data } = await api.get(Endpoints.update.frontendInfo);
    Object.assign(appApk, {
      version: data?.version || '',
      versionCode: data?.versionCode != null ? String(data.versionCode) : '',
      changelog: data?.changelog || '',
      apkUrl: data?.apk_url || '',
    });
  } catch (e: any) {
    // 502 UPSTREAM_FAILED：更新源不可达
    message.error(updateErr(e, '查询 App 版本失败'));
  } finally {
    appApkLoading.value = false;
  }
}

// ══════════════════════════════════════════════
//  Lifecycle
// ══════════════════════════════════════════════

/**
 * store 产生的一次性提示由面板消费（store 里没有 `useMessage` 上下文）。
 * 消费后立刻 clear，避免面板重新挂载时把旧提示再弹一遍。
 */
watch(
  () => updateStore.notice,
  (n) => {
    if (!n) return;
    if (n.type === 'success') message.success(n.text);
    else if (n.type === 'error') message.error(n.text);
    else if (n.type === 'warning') message.warning(n.text);
    else message.info(n.text);
    updateStore.clearNotice();
    if (n.type === 'success') {
      // 更新完成：清掉「有新版」的检查结果，并回读一次 core 状态
      backendCheck.value = null;
      loadBackendStatus();
    }
  },
  { immediate: true }
);

onMounted(() => {
  loadWebVersion();
  // resume：拉一次 /api/update/status，**若 core 仍处忙态就自动续上轮询**
  // —— 刷新页面 / 切走再回来后进度继续走，而不是冻在原地。
  updateStore.resume();
  loadAppApkInfo();
});

onUnmounted(() => {
  // 原 SettingsView 的 onUnmounted 只清了 serviceRestartTimer，Web 资源那条轮询是泄漏，这里补上。
  stopWebUpdateTimer();
  // 后端更新的轮询**故意不停**：它活在 store 里，离开设置页也要继续跟到"更新完成"。
});
</script>

<style scoped>
/* .settings-panel 栅格与断点已统一到 src/styles/main.css（全局，8 个面板共用一份） */

/* ── 前端资源 ── */
.web-version-section {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

/* ── Web 自动更新状态 ── */
.web-update-status {
  margin-top: 12px;
  padding: 10px 12px;
  border: 1px solid var(--border-subtle);
  border-radius: 8px;
  background: var(--bg-subtle, #fafafa);
}
.web-update-status-head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
}
/* 前端资源定义、后端更新（原 LINE 96）复用同一份 */
.web-update-status-msg {
  font-size: 13px;
  color: var(--text-secondary);
  word-break: break-all;
}
.web-update-status-ver {
  margin-top: 8px;
  font-size: 13px;
  color: var(--text-primary);
}
.web-update-status-ver .arrow {
  margin: 0 6px;
  color: var(--text-muted);
}

/* .card-actions 已统一到 src/styles/main.css（全局一份，四个面板共用） */

/* ── 后端更新 ── */
.apk-upload-progress {
  margin-top: 8px;
}
.backend-check {
  margin-top: 10px;
  padding: 8px 10px;
  border: 1px solid var(--border-subtle);
  border-radius: 8px;
  background: var(--bg-subtle, #fafafa);
}
.update-hint {
  margin-top: 8px;
  font-size: 12px;
  color: var(--text-muted);
  line-height: 1.5;
}
.app-apk-section {
  margin-top: 14px;
  padding-top: 10px;
  border-top: 1px solid var(--border-subtle);
}
.app-apk-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 4px;
}
.app-apk-title {
  font-size: 13px;
  color: var(--text-secondary);
}
.app-apk-link {
  margin-top: 6px;
  font-size: 13px;
}
</style>
