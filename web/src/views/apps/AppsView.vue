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
        <!-- apps 是「当前 tab + 搜索」的服务器结果，不是全量：别再说「共 N 个应用」 -->
        <span v-if="appsLoaded && !appsError">当前筛选 {{ apps.length }} 个应用</span>
        <span v-if="appsLoaded && !appsError && searchQuery.trim()">搜索匹配 {{ filteredApps.length }} 个</span>
        <span v-if="appsLoaded && !root" class="no-root-warn">未获取 Root 权限，部分操作不可用</span>
      </div>
    </GridCard>

    <!-- 应用列表 -->
    <GridCard title="应用列表" :loading="loading">
      <template #extra>
        <span v-if="appsLoaded && !appsError" class="filter-count">{{ filteredApps.length }} / {{ apps.length }}</span>
      </template>
      <!-- 「读不到」与「确实没有」必须分开：失败给重试，成功且为空才是空态 -->
      <div v-if="appsError" class="client-empty">
        <n-button size="small" quaternary type="primary" @click="loadApps">应用列表读取失败，点击重试</n-button>
      </div>
      <div v-else-if="filteredApps.length === 0" class="client-empty">{{ emptyText }}</div>
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
    </GridCard>

    <!-- 安装APK弹窗 -->
    <!-- 2026-10-14：不再用 positive-button 的 loading 等安装结束 —— 这个输入弹窗点完就该关，
         进度与结论交给下面的 OperationProgressModal（useApiOperation 驱动） -->
    <n-modal
      v-model:show="showInstallApk"
      class="modal-fit"
      preset="dialog"
      title="安装APK"
      positive-text="安装"
      negative-text="取消"
      style="--modal-w: 460px"
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
      class="modal-fit"
      preset="dialog"
      title="从URL安装"
      positive-text="安装"
      negative-text="取消"
      style="--modal-w: 460px"
      @positive-click="installFromUrl"
    >
      <n-form label-placement="left" label-width="80">
        <n-form-item label="APK地址" required>
          <n-input v-model:value="installUrl" placeholder="https:// 下载地址" />
        </n-form-item>
        <n-form-item label="SHA-256" required>
          <!-- P0-2（2026-10-05）：APK 内容哈希必填，缺失/不匹配 core 拒绝安装 -->
          <n-input v-model:value="installSha256" placeholder="APK 文件的 SHA-256（64 位十六进制）" />
        </n-form-item>
      </n-form>
    </n-modal>

    <!-- 权限管理弹窗 -->
    <n-modal
      v-model:show="showPermission"
      class="modal-fit"
      preset="dialog"
      title="权限管理"
      positive-text="应用"
      negative-text="取消"
      :positive-button-props="{ loading: permissionLoading }"
      style="--modal-w: 480px"
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
          <!-- ui-baseline-allow: 弹窗行内表单的开关，嵌在 n-form-item 里（ToggleRow / switch-item 都会把一列表单撑成两行） -->
          <n-switch v-model:value="permissionGrant">
            <template #checked>授予</template>
            <template #unchecked>撤销</template>
          </n-switch>
        </n-form-item>
      </n-form>
    </n-modal>

    <!--
      2026-10-14：安装是 A 级操作（"下发成功"≠"装上了"），统一走全仓唯一的进度弹窗
      （`useApiOperation` + `OperationProgressModal`）：下发后**轮询回读 `/api/apps`**
      直到包名/版本变化或超时，成功/失败/重试都在这里给结论。判据与理由见下面 installOp 的注释。
    -->
    <OperationProgressModal
      :state="installOp.state.value"
      :title="installTitle"
      running-text="正在安装 APK"
      :success-text="installSuccessText"
      :hint="installHint"
      :fail-text="installOp.failReason.value"
      :retry="installRetry"
      :close="installOp.close"
      :to-background="installOp.toBackground"
    />
  </div>
</template>

<script setup lang="ts">
import { ref, computed, watch } from 'vue';
import { useMessage, useDialog } from 'naive-ui';
import { getApiClient } from '@/composables/useApi';
import GridCard from '@/components/GridCard.vue';
// 2026-10-14：安装改走「下发 → 回读确认」的通用确认器 + 全仓唯一的进度弹窗
//（用法对照 views/network/NetworkView.vue 与 components/modals/BandLockModal.vue）
import { useApiOperation } from '@/composables/useApiOperation';
import OperationProgressModal from '@/components/common/OperationProgressModal.vue';
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
/** 列表没读到：不能把失败渲染成「暂无应用」 */
const appsError = ref(false);
/** 至少成功读到过一次 —— 「未获取 Root 权限」这类提示要有数据支撑才显示 */
const appsLoaded = ref(false);
const searchQuery = ref('');
const filter = ref('all');
const root = ref(false);

// ── 安装 ──
const showInstallApk = ref(false);
const showInstallUrl = ref(false);
const installPath = ref('');
const installUrl = ref('');
// P0-2（2026-10-05）：SHA-256 必填，防任意 APK 静默安装
const installSha256 = ref('');

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

/**
 * 列表为空时的文案。
 * `filter` 是服务端筛选，所以「当前 tab 一个都没有」和「全设备一个应用都没有」是两件事：
 * 只有 all + 无搜索命中才能说「暂无应用数据」。
 */
const emptyText = computed(() => {
  if (searchQuery.value.trim()) return '没有匹配的应用';
  return filter.value === 'all' ? '暂无应用数据' : '当前分类下没有应用';
});

// ── 数据加载 ──
async function loadApps() {
  loading.value = true;
  try {
    // filter 是服务端筛选：apps 只是当前 tab 的结果，不是全量
    const { data } = await api.get('/api/apps', { params: { filter: filter.value } });
    apps.value = data.apps || [];
    root.value = data.root ?? false;
    appsError.value = false;
    appsLoaded.value = true;
  } catch {
    // 读不到 ≠ 一个应用都没有：置错误态，列表区给重试（旧数据也不冒充当前 tab 的结果）
    appsError.value = true;
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

/**
 * 安装确认器（2026-10-14 接入）。
 *
 * ## 原来错在哪
 * 2026-10-14：`installApk` / `installFromUrl` 是 POST 完直接
 * `message.success(data.message || '安装成功')`，然后 `loadApps()` 一次。
 * 但那条 message 只代表 **core 收下了请求**：`AppRoutes.kt` 的 install 分支（38-56 行）
 * 返回的是 `appManager.installApk()` 的结果，而安装本身要等 `adb install -r` /
 * PackageInstaller 回调（`AppManager.kt:660-798`），并且那条路径上还有
 * "exitCode 为 0 但没看到 Success 也算成功"的判据（`AppManager.kt:680-681`）。
 * 结果是用户看到绿 toast 后只能自己去列表里翻版本号确认；失败也只在 catch 里一闪而过。
 *
 * ## "装上了"的判据（从 core 读出来的，不是猜的）
 * - `/api/apps`（`AppRoutes.kt:19-27`）返回 `{ apps, count, root }`，每项含
 *   `packageName` / `versionName` / `apkPath` 等字段（在 `AppManager.kt:306-316` 组装）；
 * - 安装结束（成功或失败）后 core 都会 `invalidateAppListCache()`（`AppManager.kt:508`、
 *   `:594`），所以紧接着读 `/api/apps` 拿到的是**真实重拉**的列表，不会被那 5 分钟缓存挡住；
 * - 所以判据 = **列表差分**：出现安装前不存在的包名，或既有包的 `versionName` 变了。
 *   core 的 install 响应**不回带被安装的包名**，也没有"读 APK 清单"的端点，
 *   因此差分是唯一能落地的判据（快照必须在 POST **之前**取，见 runInstallPath 的 submit）。
 * - 已知边界：**同版本覆盖安装**（`adb install -r` 同一个 APK）列表不会有任何变化，
 *   会被判成"未在预期时间内完成" —— 那时 `useApiOperation` 给的是 warning 而不是 error
 *   （"设备可能仍在后台继续"）。宁可这样，也不要再回到"core 说成功就报成功"。
 */
const installOp = useApiOperation();
/** 失败态「重试」按钮的动作：记住最近一次用户触发的安装（与 BandLockModal 同一手法） */
const installRetry = ref<() => void>(() => {});
/** 回读差分出来的结论（"已安装 com.x" / "已更新 com.x（1.0 → 1.1）"），成功文案用它 */
const installChange = ref('');
/**
 * 弹窗标题与 running 提示跟随入口。
 * 标题必须与传给 `useApiOperation` 的 `title` 一致 —— 失败文案是 `${title}失败` /
 * `${title}未在预期时间内完成…`，两处不一致就会出现"弹窗说应用安装、结论说从 URL 安装"。
 */
const installTitle = ref('应用安装');
const installHint = ref('设备端要等安装回调，通常十几秒到一分钟；期间可点「后台进行」');
/** 安装前的 (包名 → 版本名) 快照 */
let installBaseline: Map<string, string> | null = null;

/**
 * 安装请求的超时。
 *
 * core 侧这条链路的耗时预算是：adb connect 10s（`AppManager.kt:663`）+ adb install -r 120s
 * （`:673`）+ 回退多策略最多再等 120s（`:772`），从 URL 安装还要加一次最长 120s 的下载（`:566`）。
 * 而全局 axios 的超时是 15s（`composables/useApi.ts:13`）—— 不单独放宽的话，
 * 安装必然在客户端先超时、页面报"失败"而设备端其实还在装（这正是原来那套"只报已提交"的
 * 反面：绿 toast 不可信，红 toast 也不可信）。所以这里给足 6 分钟。
 */
const INSTALL_TIMEOUT_MS = 360_000;

/** 成功文案：包名/版本要等回读差分出来才知道，所以用 computed 而不是写死的字符串 */
const installSuccessText = computed(() => installChange.value || '安装已完成');

/** 静默读一次全量应用列表。它是确认器的内部回读，不碰页面的 loading、也不弹错 */
async function readAppVersions(): Promise<Map<string, string>> {
  // filter=all：安装的是系统应用还是用户应用事先不知道，回读必须看全量
  const { data } = await api.get('/api/apps', { params: { filter: 'all' } });
  const list: AppInfo[] = data?.apps || [];
  return new Map(list.map((a) => [a.packageName, a.versionName || '']));
}

/**
 * 取"安装前"的基线快照。
 *
 * **空列表一律当"没基线"（null）**：core 的 `loadAllAppsUncached` 在 shell 失败时返回空列表
 * （`AppManager.kt:270`），若把它当基线，任何包在差分里都会显得是"刚装上的" ——
 * 那是最坏的一种假绿。取不到就退回"未确认"（warning），不要编一个成功出来。
 */
async function captureInstallBaseline(): Promise<void> {
  const snap = await readAppVersions().catch(() => null);
  installBaseline = snap && snap.size > 0 ? snap : null;
}

/** 安装前后的列表差分。返回一句人话结论；没有任何变化时返回 '' */
function diffInstall(before: Map<string, string>, now: Map<string, string>): string {
  for (const [pkg] of now) {
    if (!before.has(pkg)) return `已安装 ${pkg}`;
  }
  for (const [pkg, ver] of now) {
    const old = before.get(pkg);
    if (old !== undefined && old !== ver) return `已更新 ${pkg}（${old || '未知'} → ${ver || '未知'}）`;
  }
  return '';
}

/** 回读判定：命中一次就停止轮询（预算与节奏复用 contract 的 SwitchProbe） */
async function verifyInstalled(): Promise<boolean> {
  // 没拿到基线就没法差分 —— 走"未确认"，不谎报成功（宁可 warning 也不要假绿）
  if (!installBaseline) return false;
  const change = diffInstall(installBaseline, await readAppVersions());
  if (!change) return false;
  installChange.value = change;
  return true;
}

/**
 * 安装 APK（从设备路径）。
 * 返回 `false` 时输入弹窗留着（校验没过）；通过则**立刻关掉输入弹窗**，
 * 后续进度/结论在 `OperationProgressModal` 里。
 */
function installApk() {
  const path = installPath.value.trim();
  if (!path) {
    message.error('请输入APK文件路径');
    return false;
  }
  installRetry.value = () => runInstallPath(path);
  void runInstallPath(path);
  return true;
}

async function runInstallPath(path: string) {
  installChange.value = '';
  installTitle.value = '应用安装';
  installHint.value = '设备端要等安装回调，通常十几秒到一分钟；期间可点「后台进行」';
  await installOp.run({
    title: '应用安装',
    running: '正在安装 APK',
    // success 用 getter：包名/版本要等回读差分出来才知道（见 diffInstall）
    get success() {
      return installChange.value || '安装已完成';
    },
    hint: installHint.value,
    submit: async () => {
      // 快照必须在 POST **之前**取：装完再取就没有"变化"可比了。
      // 取不到（超时/老固件/空列表）就是 null，verify 会如实走"未确认"而不是假装成功。
      await captureInstallBaseline();
      const res = await api.post('/api/apps/install', { path }, { timeout: INSTALL_TIMEOUT_MS });
      installPath.value = '';
      return res;
    },
    verify: verifyInstalled,
    // 生效后再刷一次列表：让用户当场看到版本号/新条目（这才是"确认装上了"的可见证据）
    onApplied: () => {
      loadApps();
    },
    retry: () => installRetry.value(),
  });
}

/**
 * 从 URL 安装。
 * 返回 `false` 时输入弹窗留着。**SHA-256 的必填校验一个字都没动**（P0-2，core 端同样强校验）。
 */
function installFromUrl() {
  const url = installUrl.value.trim();
  if (!url) {
    message.error('请输入APK下载地址');
    return false;
  }
  // P0-2（2026-10-05）：sha256 必填，core 端同样强校验
  const sha = installSha256.value.trim().toLowerCase();
  if (!/^[0-9a-f]{64}$/.test(sha)) {
    message.error('请输入 64 位十六进制 SHA-256');
    return false;
  }
  installRetry.value = () => runInstallUrl(url, sha);
  void runInstallUrl(url, sha);
  return true;
}

async function runInstallUrl(url: string, sha: string) {
  installChange.value = '';
  installTitle.value = '从 URL 安装';
  installHint.value = '设备端要先下载并校验 SHA-256 再安装，可能超过一分钟；期间可点「后台进行」';
  await installOp.run({
    title: '从 URL 安装',
    running: '正在下载并安装 APK',
    get success() {
      return installChange.value || '安装已完成';
    },
    hint: installHint.value,
    submit: async () => {
      await captureInstallBaseline();
      const res = await api.post('/api/apps/install-url', { url, sha256: sha }, { timeout: INSTALL_TIMEOUT_MS });
      installUrl.value = '';
      installSha256.value = '';
      return res;
    },
    verify: verifyInstalled,
    onApplied: () => {
      loadApps();
    },
    retry: () => installRetry.value(),
  });
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
        // 与 freeze/enable/clear 同一口径：HTTP 200 也可能是 { success:false }（无 root / pm 拒绝）。
        // 少了这一句就会在**没卸掉**时报「已卸载」—— 典型的假成功。
        if (!assertOk(data, '卸载失败')) return;
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

/* ── 空状态/失败态：统一走全局 .client-empty（main.css），本文件不再自绘 .empty-state ── */

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
