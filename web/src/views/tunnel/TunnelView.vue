<template>
  <div class="tunnel-view">
    <!--
      2026-09-05 重排：页面默认只有「概览」+「隧道」两张通栏卡。
      原来是 2 列栅格里塞 5 张卡，概览与看护设置各占半宽、旁边空一半；
      组件管理与看护设置属于「配置一次就不看」的东西，已收进卡头的两个弹窗入口。

      2026-09-08 拆分：卡片与弹窗各自成文件（本文件原 1188 行）。这里只留
      数据装配 + 动作编排 —— 也就是那些必须在页面层持有的东西：
      轮询、act() 的统一错误处理、以及组件安装进度（见 useComponentInstaller 的文件头）。
    -->
    <TunnelOverviewCard
      :components="overviewComponents"
      :local-port="status.localPort"
      :loading="loading"
      @refresh="loadAll"
      @open-components="componentsModal.open()"
      @open-guard="guardModal.open()"
      @clear-logs="confirmClearAllLogs"
      @stop-all="confirmStopAll"
    />

    <TunnelListCard
      :kind="tunnelKind"
      :group="activeGroup"
      :installed="activeInstalled"
      :binary="activeBinary"
      :busy="busy"
      @update:kind="tunnelKind = $event"
      @stop-all-kind="stopAllOfKind"
      @open-components="componentsModal.open()"
      @start="startItem"
      @stop="stopItem"
      @edit="editItem"
      @log="showLog"
      @activate="activateItem"
      @delete="deleteItem"
    />

    <!-- 五个弹窗全部按需加载：都是「点开才看」的东西，常驻挂载既占首包又让
         自包含弹窗内部的 watch 一直在。挂载/显隐时序由 useLazyModal 管
         （所以是 :show + @update:show，不是 v-model:show），写法与网络页一致。 -->
    <component
      :is="componentsComponent"
      v-if="componentsComponent"
      :show="componentsShow"
      :comp="comp"
      :progress-active="progressActive"
      @update:show="componentsModal.setShow"
      @install="installComponent"
      @uninstall="confirmUninstall"
      @upload="uploadComponent"
      @refresh="loadComponents(true)"
    />

    <component :is="guardComponent" v-if="guardComponent" :show="guardShow" @update:show="guardModal.setShow" />

    <component
      :is="frpEditorComponent"
      v-if="frpEditorComponent"
      :show="frpEditorShow"
      :name="editorName"
      @update:show="frpEditorModal.setShow"
      @saved="loadAll"
    />
    <component
      :is="cfEditorComponent"
      v-if="cfEditorComponent"
      :show="cfEditorShow"
      :name="editorName"
      @update:show="cfEditorModal.setShow"
      @saved="loadAll"
    />

    <component
      :is="logComponent"
      v-if="logComponent"
      :show="logShow"
      :kind="logKind"
      :name="logName"
      @update:show="logModal.setShow"
    />
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted } from 'vue';
import { useInterval } from '@/composables/useRealtime';
import { useMessage, useDialog } from 'naive-ui';
import { useCancellableApi } from '@/composables/useCancellableApi';
import { useComponentInstaller } from '@/composables/useComponentInstaller';
import { useLazyModal } from '@/composables/useLazyModal';
import { errText, type Instance } from './tunnelShared';
import TunnelOverviewCard from './components/cards/TunnelOverviewCard.vue';
import TunnelListCard from './components/cards/TunnelListCard.vue';
// 五个弹窗都是按需加载，组件本体与显隐时序由 useLazyModal 管（见下方 componentsModal 等）。

const message = useMessage();
const dialog = useDialog();
const api = useCancellableApi();

const loading = ref(false);
const busy = ref<string | null>(null);

const status = reactive({
  frpVersion: '',
  frpRunning: 0,
  frpInstalled: true,
  cfVersion: '',
  cfRunning: 0,
  cfInstalled: true,
  localPort: 0,
});

const frp = reactive({ active: '', running: [] as string[], items: [] as Instance[] });
const cf = reactive({ active: '', running: [] as string[], items: [] as Instance[] });

// ── 两类隧道合并成一张卡（2026-09-05）──
// frp.items 与 cf.items 本来就是同一个 Instance[]，六个操作也一一对应，
// 所以列表模板只写一份（TunnelListCard），由 tunnelKind 决定调哪一组 handler。
// 下面这几个 wrapper 是卡片与 frp*/cf* 两组函数之间的唯一桥梁 ——
// 新增一类隧道时只要在这里各加一个分支，卡片不用动。
const tunnelKind = ref<'frp' | 'cf'>('frp');
const isFrp = computed(() => tunnelKind.value === 'frp');
const activeGroup = computed(() => (isFrp.value ? frp : cf));
const startItem = (name: string) => (isFrp.value ? startFrp(name) : startCf(name));
const stopItem = (name: string) => (isFrp.value ? stopFrp(name) : stopCf(name));
const activateItem = (name: string) => (isFrp.value ? activateFrp(name) : activateCf(name));
const deleteItem = (name: string) => (isFrp.value ? confirmDeleteFrp(name) : confirmDeleteCf(name));
const stopAllOfKind = () => (isFrp.value ? stopAllFrp() : stopAllCf());

/** 当前这类隧道依赖的二进制装了没 —— 没装时列表上方横一条提醒并给安装入口 */
const activeInstalled = computed(() => (isFrp.value ? status.frpInstalled : status.cfInstalled));
const activeBinary = computed(() => (isFrp.value ? 'frpc' : 'cloudflared'));

/** 概览里的两个组件行；「本机服务端口」不属于组件，写在卡片模板里 */
const overviewComponents = computed(() => [
  {
    key: 'frp',
    name: 'frpc',
    installed: status.frpInstalled,
    version: status.frpVersion,
    running: status.frpRunning,
  },
  {
    key: 'cf',
    name: 'cloudflared',
    installed: status.cfInstalled,
    version: status.cfVersion,
    running: status.cfRunning,
  },
]);

// 组件管理与看护设置从常驻卡片改成弹窗，入口在概览卡头
const componentsModal = useLazyModal(() => import('./components/modals/ComponentsModal.vue'));
const guardModal = useLazyModal(() => import('./components/modals/GuardSettingsModal.vue'));
const componentsComponent = componentsModal.component;
const componentsShow = componentsModal.show;
const guardComponent = guardModal.component;
const guardShow = guardModal.show;

// 组件安装的状态与 1s 进度轮询挂在页面上（弹窗关了也要继续），只把展示交给 ComponentsModal。
// onChanged：组件装好/卸掉后 installed 与版本号变了，隧道侧的 status 得重拉。
const { comp, progressActive, loadComponents, installComponent, confirmUninstall, uploadComponent } =
  useComponentInstaller({ onChanged: () => loadStatus() });

// ── 编辑与日志弹窗的目标 ──
// 两个编辑器共用一个 editorName：同一时刻只可能开一个（由 tunnelKind 决定开哪个）。
// 先写 editorName 再 open()：open() 内部会先挂载一帧（此时 show 仍为 false），
// 子组件的 show watch 到下一帧才触发回读，那时 name 已经就位。
const editorName = ref('');
const frpEditorModal = useLazyModal(() => import('./components/modals/FrpConfigModal.vue'));
const cfEditorModal = useLazyModal(() => import('./components/modals/CfTokenModal.vue'));
const frpEditorComponent = frpEditorModal.component;
const frpEditorShow = frpEditorModal.show;
const cfEditorComponent = cfEditorModal.component;
const cfEditorShow = cfEditorModal.show;
function editItem(name: string) {
  editorName.value = name;
  if (isFrp.value) frpEditorModal.open();
  else cfEditorModal.open();
}

const logModal = useLazyModal(() => import('./components/modals/TunnelLogModal.vue'));
const logComponent = logModal.component;
const logShow = logModal.show;
const logKind = ref<'frp' | 'cf'>('frp');
const logName = ref('');
function showLog(name: string) {
  logKind.value = tunnelKind.value;
  logName.value = name;
  logModal.open();
}

async function loadStatus() {
  try {
    // 该端点会 fork 进程探测版本号（最长 5s），因此轮询间隔不宜太短
    const { data } = await api.get('/api/tunnel/status');
    status.frpVersion = data?.frp?.version || '';
    status.frpRunning = data?.frp?.running_count ?? 0;
    status.frpInstalled = data?.frp?.installed !== false;
    status.cfVersion = data?.cf_tunnel?.version || '';
    status.cfRunning = data?.cf_tunnel?.running_count ?? 0;
    status.cfInstalled = data?.cf_tunnel?.installed !== false;
    status.localPort = data?.local_port ?? 0;
  } catch {
    /* 静默 */
  }
}

async function loadFrp() {
  try {
    const { data } = await api.get('/api/tunnel/frp/configs');
    frp.active = data?.active || '';
    frp.running = data?.running || [];
    frp.items = data?.items || [];
  } catch {
    /* 静默 */
  }
}

async function loadCf() {
  try {
    const { data } = await api.get('/api/tunnel/cf/tunnels');
    cf.active = data?.active || '';
    cf.running = data?.running || [];
    cf.items = data?.items || [];
  } catch {
    /* 静默 */
  }
}

/** 看护设置不在这里拉：它只在 GuardSettingsModal 里显示，由那个组件打开时自取。 */
async function loadAll() {
  loading.value = true;
  try {
    await Promise.all([loadStatus(), loadFrp(), loadCf(), loadComponents()]);
  } finally {
    loading.value = false;
  }
}

/** 统一处理形态②：HTTP 200 但 success=false（见 tunnelShared.errText 的说明）。 */
async function act(key: string, fn: () => Promise<any>, okMsg: string, failMsg: string) {
  busy.value = key;
  try {
    const { data } = await fn();
    if (data?.success === false) {
      message.error(data?.message || failMsg);
    } else {
      message.success(okMsg);
    }
  } catch (e: any) {
    message.error(errText(e, failMsg));
  } finally {
    busy.value = null;
    await loadAll();
  }
}

// ── FRP 动作 ──
// start 是**同步**的：core 内部启进程后等 1.5s 存活探测，秒退即判失败并把原因写进 message，
// 所以返回时状态已确定，不需要额外轮询。
const startFrp = (name: string) =>
  act(
    `frp-start-${name}`,
    () => api.post(`/api/tunnel/frp/config/${encodeURIComponent(name)}/start`),
    `${name} 已启动`,
    `${name} 启动失败`
  );
const stopFrp = (name: string) =>
  act(
    `frp-stop-${name}`,
    () => api.post(`/api/tunnel/frp/config/${encodeURIComponent(name)}/stop`),
    `${name} 已停止`,
    `${name} 停止失败`
  );
const activateFrp = (name: string) =>
  act(
    `frp-act-${name}`,
    () => api.post(`/api/tunnel/frp/config/${encodeURIComponent(name)}/activate`),
    `已选中 ${name}`,
    '设置选中失败'
  );
const stopAllFrp = () =>
  act('frp-stop-all', () => api.post('/api/tunnel/frp/stop'), '已停止全部 FRP 通道', '部分 frpc 停止失败');

function confirmDeleteFrp(name: string) {
  dialog.warning({
    title: '删除 FRP 通道',
    content: `确定删除「${name}」？配置文件会被删除；若通道在运行会先尝试停止，停不掉则不会删除。`,
    positiveText: '删除',
    negativeText: '取消',
    onPositiveClick: () =>
      act(
        `frp-del-${name}`,
        () => api.delete(`/api/tunnel/frp/config/${encodeURIComponent(name)}`),
        `${name} 已删除`,
        `${name} 删除失败`
      ),
  });
}

// ── CF 动作 ──
const startCf = (name: string) =>
  act(
    `cf-start-${name}`,
    () => api.post(`/api/tunnel/cf/tunnel/${encodeURIComponent(name)}/start`),
    `${name} 已启动`,
    `${name} 启动失败`
  );
const stopCf = (name: string) =>
  act(
    `cf-stop-${name}`,
    () => api.post(`/api/tunnel/cf/tunnel/${encodeURIComponent(name)}/stop`),
    `${name} 已停止`,
    `${name} 停止失败`
  );
const activateCf = (name: string) =>
  act(
    `cf-act-${name}`,
    () => api.post(`/api/tunnel/cf/tunnel/${encodeURIComponent(name)}/activate`),
    `已选中 ${name}`,
    '设置选中失败'
  );
const stopAllCf = () =>
  act('cf-stop-all', () => api.post('/api/tunnel/cf/stop'), '已停止全部 CF 隧道', '部分 cloudflared 停止失败');

function confirmDeleteCf(name: string) {
  dialog.warning({
    title: '删除 Cloudflare 隧道',
    content: `确定删除「${name}」？token 会一并删除；若隧道在运行会先尝试停止，停不掉则不会删除。`,
    positiveText: '删除',
    negativeText: '取消',
    onPositiveClick: () =>
      act(
        `cf-del-${name}`,
        () => api.delete(`/api/tunnel/cf/tunnel/${encodeURIComponent(name)}`),
        `${name} 已删除`,
        `${name} 删除失败`
      ),
  });
}

/** 与日志弹窗里的「清空本实例日志」是两个不同动作：这个清两个引擎所有实例。 */
function confirmClearAllLogs() {
  dialog.warning({
    title: '清空全部日志',
    content: '会清空两个引擎所有实例的内存缓冲，并截断运行期日志文件。',
    positiveText: '清空',
    negativeText: '取消',
    onPositiveClick: async () => {
      try {
        await api.post('/api/tunnel/logs/clear');
        message.success('已清空全部日志');
      } catch (e: any) {
        message.error(errText(e, '清空失败'));
      }
    },
  });
}

function confirmStopAll() {
  dialog.warning({
    title: '停止全部隧道',
    content: '会停止所有 FRP 通道与 CF 隧道，并清空看护期望列表（之后不会被自动重连拉起）。',
    positiveText: '全部停止',
    negativeText: '取消',
    onPositiveClick: () => act('stop-all', () => api.post('/api/tunnel/stop'), '已全部停止', '部分进程停止失败'),
  });
}

onMounted(() => {
  loadAll();
});

// status 端点会 fork 进程探版本，5s 已是比较克制的间隔
useInterval(() => {
  loadStatus();
  loadFrp();
  loadCf();
}, 5000);
</script>

<style scoped>
.tunnel-view {
  /* 只有「概览」「隧道」两张通栏卡，竖排即可。
     原来是 2 列栅格 + .full-width 混排，概览与看护设置各占半宽、旁边空一半 —— 那两张卡已收进弹窗。 */
  display: flex;
  flex-direction: column;
  gap: 16px;
}
</style>
