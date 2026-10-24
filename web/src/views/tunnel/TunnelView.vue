<template>
  <div class="tunnel-view">
    <!--
      2026-09-05 重排：页面默认只有「概览」+「隧道」两张通栏卡。
      原来是 2 列栅格里塞 5 张卡，概览与看护设置各占半宽、旁边空一半；
      组件管理与看护设置属于「配置一次就不看」的东西，已收进卡头的两个弹窗入口。

      2026-09-08 拆分：卡片与弹窗各自成文件（本文件原 1188 行）。这里只留
      数据装配 + 动作编排 —— 也就是那些必须在页面层持有的东西：
      轮询、act() 的统一错误处理、以及组件安装进度（见 useComponentInstaller 的文件头）。

      2026-09-21：概览卡换成 TunnelRunBar（一条 ~70px 的运行态条，不再是卡），
      理由与取舍写在那个组件的文件头。

      ★ 2026-10-14：**组件安装整体搬去「资源中心」页**（用户原话："当前分得太散了"）。
      原来本页有一整套就地安装：ComponentsModal 弹窗 + useComponentInstaller 的 1s 进度轮询
      + 四张卡的安装/卸载/上传动作。它和设置页的「更新」面板各摆一份"下载并安装"的入口，
      而两者装的东西（frpc/cloudflared vs 前端包/core APK）在用户眼里是同一类事。
      现在：本页只留「未安装」的**提示 + 指路**（三处按钮都跳 `resources`），
      ComponentsModal 的挂载已断开，本页也不再持有 useComponentInstaller。
      注意本页**仍然**要读 `/api/tunnel/status` 的 `installed` 字段：提示本身依赖它。
    -->
    <TunnelRunBar
      :components="overviewComponents"
      :local-port="status.localPort"
      :loading="loading"
      :loaded="statusLoaded"
      :error="loadErrors.status"
      @refresh="loadAll"
      @open-resources="goResources"
      @open-guard="guardModal.open()"
      @clear-logs="confirmClearAllLogs"
      @stop-all="confirmStopAll"
    />

    <TunnelListCard
      :kind="tunnelKind"
      :group="activeGroup"
      :counts="kindCounts"
      :installed="activeInstalled"
      :binary="activeBinary"
      :busy="busy"
      :error="activeError"
      :initial-loading="loading && !loadedOnce"
      :retrying="loading"
      @update:kind="tunnelKind = $event"
      @stop-all-kind="stopAllOfKind"
      @open-resources="goResources"
      @retry="loadAll"
      @start="startItem"
      @stop="stopItem"
      @edit="editItem"
      @log="showLog"
      @activate="activateItem"
      @delete="deleteItem"
    />

    <!-- 四个弹窗全部按需加载：都是「点开才看」的东西，常驻挂载既占首包又让
         自包含弹窗内部的 watch 一直在。挂载/显隐时序由 useLazyModal 管
         （所以是 :show + @update:show，不是 v-model:show），写法与网络页一致。 -->
    <!-- 2026-10-14：这里原来挂第五个弹窗 `ComponentsModal`（frpc/cloudflared 的下载安装列表）。
         已移到资源中心，本页**不要再恢复这个挂载**。
         关于那个组件文件本身：全仓 grep 后确认它已**没有任何消费者**，本该一并删除；
         但删之前发现它的工作区版本里有 60 余行**尚未提交**的改动（2026-10-14 的「本地上传进度
         接到 UI 上」那一版，git HEAD 里只有旧版），删掉就等于把那批改动弄丢。
         所以本次**只断开挂载、保留文件**，把"删不删"留给资源中心那一侧决定
         （要么复用它的列表 UI，要么确认无用后连同下面的 composable 一起删）。
         它原来的取数层 `useComponentInstaller.ts` 同样**已无消费者**：本页不再调用它，
         唯一的引用方就是上面那个已断开挂载的 ComponentsModal.vue（而且只 import 了一个类型）。
         那个文件不在本次改造的写权限内，留给它的 owner 处理（要删就与 ComponentsModal 一起删）。 -->

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
import { useRouter } from 'vue-router';
import { useInterval } from '@/composables/useRealtime';
import { useMessage, useDialog } from 'naive-ui';
import { useCancellableApi } from '@/composables/useCancellableApi';
import { useLazyModal } from '@/composables/useLazyModal';
import { errText, type Instance } from './tunnelShared';
import TunnelRunBar from './components/cards/TunnelRunBar.vue';
import TunnelListCard from './components/cards/TunnelListCard.vue';
// 四个弹窗都是按需加载，组件本体与显隐时序由 useLazyModal 管（见下方 guardModal 等）。
// 2026-10-14：组件安装弹窗 ComponentsModal 已移到资源中心，这里不再有它。

const message = useMessage();
const dialog = useDialog();
const api = useCancellableApi();
const router = useRouter();

/**
 * 三处「未安装」入口（运行态条读数行、列表上方横幅、空列表）统一走这里。
 *
 * 为什么由页面转发而不是让卡片自己 `router.push`：卡片在本项目里一律只 emit
 * （见 TunnelListCard 的六个动作），跳转是页面级的编排；集中一处也方便以后要带
 * query（例如高亮某个组件）时只改一行。**不带 query**：资源中心是别人在新建的页，
 * 路由契约只冻结了 `name: 'resources'`，凭空塞参数等于单方面加接口。
 */
function goResources() {
  router.push({ name: 'resources' });
}

const loading = ref(false);
const busy = ref<string | null>(null);

/**
 * 三个取数端点各自的失败原因（空串 = 最近一次读成功）。
 *
 * 旧实现三处 catch 全是「静默」，于是读失败会显示成「暂无 FRP 通道配置」/「未运行」——
 * 用户会去重配一条已经存在的隧道，或者以为进程挂了。失败必须能被看见、能被重试。
 */
const loadErrors = reactive({ status: '', frp: '', cf: '' });
/** `/api/tunnel/status` 是否成功读到过：没读到过时运行态条的读数一律显示 `--` */
const statusLoaded = ref(false);
/** 首屏是否已经拉过一轮：卡片骨架只用于首屏，避免每次动作后整卡闪一下 */
const loadedOnce = ref(false);

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
/** 当前这类隧道列表的读取失败原因（两类各自记账，切过去才不会串） */
const activeError = computed(() => (isFrp.value ? loadErrors.frp : loadErrors.cf));

/** 两类各自的「在跑 / 总数」，挂在列表卡那两颗切换按钮上：
    不切过去也知道另一边有几条在跑。running 是名字数组，长度即条数。 */
const kindCounts = computed(() => ({
  frp: { running: frp.running.length, total: frp.items.length },
  cf: { running: cf.running.length, total: cf.items.length },
}));

/** 运行态条里的两个组件读数；「回源端口」不属于组件，写在那个组件的模板里 */
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

// 看护设置从常驻卡片改成弹窗，入口在运行态条的 ⋯ 菜单。
// 2026-10-14：原来是"组件管理与看护设置"两个弹窗，组件管理那个已随资源中心收口删除。
const guardModal = useLazyModal(() => import('./components/modals/GuardSettingsModal.vue'));
const guardComponent = guardModal.component;
const guardShow = guardModal.show;

// 2026-10-14：这里原来还有 useComponentInstaller({ onChanged: () => loadStatus() })，
// 为 ComponentsModal 提供 comp / progressActive 与安装/卸载/上传动作。整体搬到资源中心后
// 本页不再需要它 —— 安装进度也不再由本页的 1s 轮询承担（那个 composable 现在无消费者）。
// 本页仍然依赖 /api/tunnel/status 的 installed 字段（下面 activeInstalled / overviewComponents），
// 所以 loadStatus 里那段读法一个字都没动。

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
    loadErrors.status = '';
    statusLoaded.value = true;
  } catch (e: any) {
    loadErrors.status = errText(e, '读取隧道运行状态失败');
  }
}

async function loadFrp() {
  try {
    const { data } = await api.get('/api/tunnel/frp/configs');
    frp.active = data?.active || '';
    frp.running = data?.running || [];
    frp.items = data?.items || [];
    loadErrors.frp = '';
  } catch (e: any) {
    loadErrors.frp = errText(e, '读取 FRP 通道失败');
  }
}

async function loadCf() {
  try {
    const { data } = await api.get('/api/tunnel/cf/tunnels');
    cf.active = data?.active || '';
    cf.running = data?.running || [];
    cf.items = data?.items || [];
    loadErrors.cf = '';
  } catch (e: any) {
    loadErrors.cf = errText(e, '读取 Cloudflare 隧道失败');
  }
}

/** 看护设置不在这里拉：它只在 GuardSettingsModal 里显示，由那个组件打开时自取。 */
async function loadAll() {
  loading.value = true;
  try {
    // 2026-10-14：原来还有 loadComponents()（组件清单，给 ComponentsModal 用）。已随安装 UI 搬走。
    await Promise.all([loadStatus(), loadFrp(), loadCf()]);
  } finally {
    loading.value = false;
    loadedOnce.value = true;
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
