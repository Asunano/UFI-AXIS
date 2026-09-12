<template>
  <div class="apps-view">
    <!-- 顶部工具栏 -->
    <GridCard title="应用管理">
      <div class="top-bar">
        <div class="top-left">
          <n-input
            v-model:value="searchQuery"
            placeholder="搜索应用名称或包名..."
            size="small"
            clearable
            class="search-input"
          >
            <template #prefix>
              <n-icon><SearchOutline /></n-icon>
            </template>
          </n-input>
          <n-tabs v-model:value="filter" type="segment" size="small" class="filter-tabs">
            <n-tab name="all">全部</n-tab>
            <n-tab name="user">用户</n-tab>
            <n-tab name="system">系统</n-tab>
          </n-tabs>
        </div>
        <div class="top-right">
          <n-button size="small" @click="showInstallApk = true">安装APK</n-button>
          <n-button size="small" @click="showInstallUrl = true">从URL安装</n-button>
          <n-button size="small" quaternary :disabled="loading" @click="loadApps">
            <template #icon
              ><n-icon><RefreshOutline /></n-icon
            ></template>
          </n-button>
        </div>
      </div>
      <div class="app-stats">
        <span>共 {{ apps.length }} 个应用</span>
        <span v-if="!root" class="no-root-warn">未获取 Root 权限，部分操作不可用</span>
      </div>
    </GridCard>

    <!-- 应用列表 -->
    <GridCard title="应用列表">
      <template #extra>
        <span class="filter-count">{{ filteredApps.length }} / {{ apps.length }}</span>
      </template>
      <n-spin :show="loading">
        <div v-if="filteredApps.length === 0 && !loading" class="empty-state">
          {{ apps.length === 0 ? '暂无应用数据' : '没有匹配的应用' }}
        </div>
        <div v-else class="app-list">
          <div
            v-for="app in filteredApps"
            :key="app.packageName"
            class="app-item"
            :class="{ 'app-frozen': app.isFrozen, 'app-disabled': !app.enabled && !app.isFrozen }"
          >
            <img v-if="app.iconBase64" class="app-icon" :src="'data:image/png;base64,' + app.iconBase64" alt="" />
            <div v-else class="app-icon app-icon-fallback">{{ (app.appName || '?').charAt(0) }}</div>
            <div class="app-info">
              <div class="app-name-row">
                <span class="app-name">{{ app.appName }}</span>
                <n-tag v-if="app.isSystem" type="warning" size="tiny" round :bordered="false">系统</n-tag>
                <n-tag v-else type="success" size="tiny" round :bordered="false">用户</n-tag>
                <n-tag v-if="app.isFrozen" type="info" size="tiny" round>已冻结</n-tag>
                <n-tag v-else-if="!app.enabled" type="default" size="tiny" round>已禁用</n-tag>
              </div>
              <div class="app-meta">
                <span class="app-package" :title="app.packageName">{{ app.packageName }}</span>
                <span class="app-version">{{ app.versionName || '--' }}</span>
              </div>
            </div>
            <div class="app-actions" @click.stop>
              <n-dropdown
                :options="appActions(app)"
                trigger="click"
                @select="(key: string | number) => handleAction(key as string, app)"
              >
                <n-button
                  size="tiny"
                  quaternary
                  :loading="actionLoadingPkg === app.packageName"
                  :disabled="actionLoadingPkg !== null"
                >
                  <template #icon
                    ><n-icon><EllipsisHorizontalOutline /></n-icon
                  ></template>
                </n-button>
              </n-dropdown>
            </div>
          </div>
        </div>
      </n-spin>
    </GridCard>

    <!-- 安装APK弹窗 -->
    <n-modal
      v-model:show="showInstallApk"
      preset="dialog"
      title="安装APK"
      positive-text="安装"
      negative-text="取消"
      :positive-button-props="{ loading: installing }"
      style="width: 460px"
      @positive-click="installApk"
    >
      <n-form label-placement="left" label-width="80">
        <n-form-item label="APK路径" required>
          <n-input v-model:value="installPath" placeholder="设备上的 APK 文件路径，如 /sdcard/download/app.apk" />
        </n-form-item>
      </n-form>
    </n-modal>

    <!-- 从URL安装弹窗 -->
    <n-modal
      v-model:show="showInstallUrl"
      preset="dialog"
      title="从URL安装"
      positive-text="安装"
      negative-text="取消"
      :positive-button-props="{ loading: installing }"
      style="width: 460px"
      @positive-click="installFromUrl"
    >
      <n-form label-placement="left" label-width="80">
        <n-form-item label="APK地址" required>
          <n-input v-model:value="installUrl" placeholder="http(s):// 下载地址" />
        </n-form-item>
      </n-form>
    </n-modal>

    <!-- 权限管理弹窗 -->
    <n-modal
      v-model:show="showPermission"
      preset="dialog"
      title="权限管理"
      positive-text="应用"
      negative-text="取消"
      :positive-button-props="{ loading: permissionLoading }"
      style="width: 480px"
      @positive-click="applyPermission"
    >
      <div v-if="permissionTarget" class="perm-target">
        <span class="perm-app-name">{{ permissionTarget.appName }}</span>
        <span class="perm-pkg">{{ permissionTarget.packageName }}</span>
      </div>
      <n-form label-placement="left" label-width="80" style="margin-top: 12px">
        <n-form-item label="权限名称" required>
          <n-input v-model:value="permissionName" placeholder="如 android.permission.CAMERA" />
        </n-form-item>
        <n-form-item label="授权">
          <n-switch v-model:value="permissionGrant">
            <template #checked>授予</template>
            <template #unchecked>撤销</template>
          </n-switch>
        </n-form-item>
      </n-form>
    </n-modal>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, watch } from 'vue';
import { useMessage, useDialog } from 'naive-ui';
import { getApiClient } from '@/composables/useApi';
import GridCard from '@/components/GridCard.vue';
import { SearchOutline, RefreshOutline, EllipsisHorizontalOutline } from '@vicons/ionicons5';

interface AppInfo {
  packageName: string;
  appName: string;
  apkPath?: string;
  versionName: string;
  isSystem: boolean;
  isFrozen: boolean;
  enabled: boolean;
  iconBase64?: string;
}

const message = useMessage();
const dialog = useDialog();
const api = getApiClient();

// ── 状态 ──
const loading = ref(false);
const apps = ref<AppInfo[]>([]);
const searchQuery = ref('');
const filter = ref('all');
const root = ref(false);

// ── 安装 ──
const showInstallApk = ref(false);
const showInstallUrl = ref(false);
const installPath = ref('');
const installUrl = ref('');
const installing = ref(false);

// ── 权限管理 ──
const showPermission = ref(false);
const permissionTarget = ref<AppInfo | null>(null);
const permissionName = ref('');
const permissionGrant = ref(true);
const permissionLoading = ref(false);

// ── 操作中的包名（用于禁用重复操作） ──
const actionLoadingPkg = ref<string | null>(null);

// ── 计算属性 ──
const filteredApps = computed(() => {
  const q = searchQuery.value.trim().toLowerCase();
  if (!q) return apps.value;
  return apps.value.filter((a) => a.appName.toLowerCase().includes(q) || a.packageName.toLowerCase().includes(q));
});

// ── 数据加载 ──
async function loadApps() {
  loading.value = true;
  try {
    const { data } = await api.get('/api/apps', { params: { filter: filter.value } });
    apps.value = data.apps || [];
    root.value = data.root ?? false;
  } catch {
    message.error('加载应用列表失败');
  } finally {
    loading.value = false;
  }
}

// ── 安装操作 ──
// core 的 freeze/unfreeze/enable/disable/clear/force-stop/permission 均返回
// HTTP 200 + { success: bool }（无 root 时几乎必然 false），必须显式判断
function assertOk(data: any, fallback: string): boolean {
  if (data?.success === false) {
    message.error(data.message || data.error || fallback);
    return false;
  }
  return true;
}

function errMsg(e: any, fallback: string): string {
  return e?.response?.data?.message || e?.response?.data?.error || fallback;
}

async function installApk() {
  if (!installPath.value.trim()) {
    message.error('请输入APK文件路径');
    return false;
  }
  installing.value = true;
  try {
    const { data } = await api.post('/api/apps/install', { path: installPath.value.trim() });
    message.success(data.message || '安装成功');
    installPath.value = '';
    loadApps();
    return true;
  } catch (e: any) {
    // core 安装失败返回 500 + { success:false, message }
    message.error(errMsg(e, '安装失败'));
    return false;
  } finally {
    installing.value = false;
  }
}

async function installFromUrl() {
  if (!installUrl.value.trim()) {
    message.error('请输入APK下载地址');
    return false;
  }
  installing.value = true;
  try {
    const { data } = await api.post('/api/apps/install-url', { url: installUrl.value.trim() });
    message.success(data.message || '安装请求已提交');
    installUrl.value = '';
    loadApps();
    return true;
  } catch (e: any) {
    message.error(errMsg(e, '安装失败'));
    return false;
  } finally {
    installing.value = false;
  }
}

// ── 应用操作 ──
async function toggleFreeze(app: AppInfo) {
  const endpoint = app.isFrozen ? '/api/apps/unfreeze' : '/api/apps/freeze';
  const label = app.isFrozen ? '解冻' : '冻结';
  actionLoadingPkg.value = app.packageName;
  try {
    const { data } = await api.post(endpoint, { packageName: app.packageName });
    if (!assertOk(data, `${label}失败`)) return;
    message.success(`已${label} ${app.appName}`);
    loadApps();
  } catch (e: any) {
    message.error(errMsg(e, `${label}失败`));
  } finally {
    actionLoadingPkg.value = null;
  }
}

async function toggleEnable(app: AppInfo) {
  const action = app.enabled ? 'disable' : 'enable';
  const label = app.enabled ? '禁用' : '启用';
  actionLoadingPkg.value = app.packageName;
  try {
    const { data } = await api.post(`/api/apps/${action}`, { packageName: app.packageName });
    if (!assertOk(data, `${label}失败`)) return;
    message.success(`已${label} ${app.appName}`);
    loadApps();
  } catch (e: any) {
    message.error(errMsg(e, `${label}失败`));
  } finally {
    actionLoadingPkg.value = null;
  }
}

async function forceStop(app: AppInfo) {
  actionLoadingPkg.value = app.packageName;
  try {
    const { data } = await api.post('/api/apps/force-stop', { packageName: app.packageName });
    if (!assertOk(data, '强制停止失败')) return;
    message.success(`已强制停止 ${app.appName}`);
    loadApps();
  } catch (e: any) {
    message.error(errMsg(e, '操作失败'));
  } finally {
    actionLoadingPkg.value = null;
  }
}

function confirmClearData(app: AppInfo) {
  dialog.warning({
    title: '清除数据',
    content: `确定清除 "${app.appName}" 的所有应用数据？此操作不可恢复。`,
    positiveText: '清除',
    negativeText: '取消',
    onPositiveClick: async () => {
      actionLoadingPkg.value = app.packageName;
      try {
        const { data } = await api.post('/api/apps/clear', { packageName: app.packageName });
        if (!assertOk(data, '清除数据失败')) return;
        message.success(`已清除 ${app.appName} 的数据`);
        loadApps();
      } catch (e: any) {
        message.error(errMsg(e, '清除数据失败'));
      } finally {
        actionLoadingPkg.value = null;
      }
    },
  });
}

function confirmUninstall(app: AppInfo) {
  dialog.warning({
    title: '卸载应用',
    content: app.isSystem
      ? `"${app.appName}" 是系统应用，卸载可能导致系统功能异常甚至无法启动。确定继续？`
      : `确定卸载 "${app.appName}"？`,
    positiveText: '卸载',
    negativeText: '取消',
    onPositiveClick: async () => {
      actionLoadingPkg.value = app.packageName;
      try {
        const { data } = await api.post('/api/apps/uninstall', { packageName: app.packageName });
        message.success(data.message || `已卸载 ${app.appName}`);
        loadApps();
      } catch (e: any) {
        message.error(errMsg(e, '卸载失败'));
      } finally {
        actionLoadingPkg.value = null;
      }
    },
  });
}

function openPermission(app: AppInfo) {
  permissionTarget.value = app;
  permissionName.value = '';
  permissionGrant.value = true;
  showPermission.value = true;
}

async function applyPermission() {
  if (!permissionName.value.trim() || !permissionTarget.value) {
    message.error('请输入权限名称');
    return false;
  }
  permissionLoading.value = true;
  try {
    const { data } = await api.post('/api/apps/permission', {
      packageName: permissionTarget.value.packageName,
      permission: permissionName.value.trim(),
      grant: permissionGrant.value,
    });
    if (!assertOk(data, '权限操作失败')) return false;
    message.success(`权限已${permissionGrant.value ? '授予' : '撤销'}`);
    permissionName.value = '';
    return true;
  } catch (e: any) {
    message.error(errMsg(e, '权限操作失败'));
    return false;
  } finally {
    permissionLoading.value = false;
  }
}

// ── 通过 ADB 授予全部权限 ──
async function grantAllPerms(app: AppInfo) {
  actionLoadingPkg.value = app.packageName;
  try {
    const { data } = await api.post('/api/apps/grant-all-permissions', { packageName: app.packageName });
    if (!assertOk(data, '权限授予失败')) return;
    message.success(data.message || `已授予 ${app.appName} 全部运行时权限`);
  } catch (e: any) {
    message.error('权限授予失败: ' + errMsg(e, '未知错误'));
  } finally {
    actionLoadingPkg.value = null;
  }
}

// ── 下拉菜单 ──
function appActions(app: AppInfo) {
  return [
    { label: app.isFrozen ? '解冻' : '冻结', key: 'freeze' },
    { label: app.enabled ? '禁用' : '启用', key: 'enable' },
    { label: '强制停止', key: 'force-stop' },
    { label: '清除数据', key: 'clear' },
    { type: 'divider', key: 'd1' },
    { label: '授予全部权限 (ADB)', key: 'grant-perms' },
    { label: '权限管理', key: 'permission' },
    { type: 'divider', key: 'd2' },
    { label: '卸载', key: 'uninstall', props: { style: 'color: var(--error)' } },
  ];
}

function handleAction(key: string, app: AppInfo) {
  switch (key) {
    case 'freeze':
      toggleFreeze(app);
      break;
    case 'enable':
      toggleEnable(app);
      break;
    case 'force-stop':
      forceStop(app);
      break;
    case 'clear':
      confirmClearData(app);
      break;
    case 'grant-perms':
      grantAllPerms(app);
      break;
    case 'permission':
      openPermission(app);
      break;
    case 'uninstall':
      confirmUninstall(app);
      break;
  }
}

// ── 初始化 ──
watch(filter, () => {
  loadApps();
});
loadApps();
</script>

<style scoped>
.apps-view {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

/* ── 顶部工具栏 ── */
.top-bar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  flex-wrap: wrap;
  gap: 12px;
}
.top-left {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
  flex: 1;
  min-width: 0;
}
.search-input {
  width: 220px;
}
.filter-tabs {
  flex-shrink: 0;
}
.top-right {
  display: flex;
  gap: 8px;
  flex-shrink: 0;
}
.app-stats {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-top: 8px;
  font-size: 12px;
  color: var(--text-muted);
}
.no-root-warn {
  color: var(--warning);
}
.filter-count {
  font-size: 12px;
  color: var(--text-muted);
}

/* ── 应用列表 ── */
.app-list {
  display: flex;
  flex-direction: column;
}
.app-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 10px 0;
  border-bottom: 1px solid var(--border-subtle);
  gap: 12px;
  transition: opacity 0.2s;
}
.app-item:last-child {
  border-bottom: none;
}
.app-item.app-frozen {
  opacity: 0.6;
}
.app-item.app-disabled {
  opacity: 0.55;
}
.app-icon {
  width: 32px;
  height: 32px;
  border-radius: 6px;
  flex-shrink: 0;
  object-fit: cover;
}
.app-icon-fallback {
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--surface-elevated);
  color: var(--text-muted);
  font-size: 14px;
  font-weight: 500;
  text-transform: uppercase;
  user-select: none;
}
.app-info {
  flex: 1;
  min-width: 0;
}
.app-name-row {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
}
.app-name {
  font-size: 13px;
  font-weight: 500;
  color: var(--text-primary);
}
.app-meta {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 4px;
  font-size: 12px;
  color: var(--text-muted);
}
.app-package {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 280px;
}
.app-version {
  flex-shrink: 0;
}
.app-actions {
  flex-shrink: 0;
}

/* ── 空状态 ── */
.empty-state {
  text-align: center;
  padding: 40px 0;
  color: var(--text-muted);
  font-size: 14px;
}

/* ── 权限弹窗 ── */
.perm-target {
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.perm-app-name {
  font-size: 14px;
  font-weight: 500;
  color: var(--text-primary);
}
.perm-pkg {
  font-size: 12px;
  color: var(--text-muted);
}

/* ── 响应式 ── */
@media (max-width: 768px) {
  .top-bar {
    flex-direction: column;
    align-items: stretch;
  }
  .top-left {
    flex-direction: column;
    align-items: stretch;
  }
  .search-input {
    width: 100%;
  }
  .top-right {
    justify-content: flex-end;
  }
  .app-package {
    max-width: 160px;
  }
  .app-meta {
    flex-direction: column;
    align-items: flex-start;
    gap: 2px;
  }
}
</style>
