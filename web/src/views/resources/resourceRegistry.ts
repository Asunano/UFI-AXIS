/**
 * 资源注册表（2026-10-14 新建；2026-10-23 按「服务 / 插件」拆开）。
 *
 * ## 用途
 * 每一张可下载资源卡片，都由本文件的一条 [ResourceDescriptor] 描述：
 * 显示名 / 说明 / 图标 / 上传后缀 / 取状态 / 三个操作动词 / 上传上限从哪读 /
 * 进度词表 / 独有信息行 / 终态钩子。
 *
 * 2026-10-23 起按用途分成两组，**入口不再混**：
 * · [WEB_RESOURCE]   —— Web 前端**服务**包，只在「设置 → 服务更新」里渲染；
 * · [COMPONENT_RESOURCES] —— frpc / cloudflared 等**插件组件**，只在「资源中心」`/resources` 里渲染。
 * 两者仍共用 `ResourceCard` / `useResourceTask`，差异还是只写在描述符上。
 *
 * ## 切分理由（为什么描述符住在单独一个文件）
 * 本次收口的三类资源（Web 前端 ZIP / frpc / cloudflared）**差异全在"端点与语义"上**：
 * · Web 前端走 `POST /api/web/check`（core 自己去更新源拉包）+ 手选 ZIP 走 multipart，
 *   而且它的 core 侧进度实质只有 0/100（`WebUpdateManager` 只在开始/结束写 progress）；
 * · 两个组件走 `POST /api/components/{id}/install` + 手选裸二进制走 raw body，
 *   进度是**真百分比**，状态机还多一档 `EXTRACTING`（tar.gz 要先解包）。
 * 把这些差异写成 `if (id === 'frpc')` 会立刻长出第二、第三个分支；写成描述符则
 * **加一个新资源 = 加一条描述符**（组件连描述符都不用新写，见 [COMPONENT_CATALOG]）。
 *
 * ## 否决过的替代方案
 * · **一个 `components` 描述符 + `fetch()` 回数组、由页面摊平**：数组形式下
 *   `install()` / `upload()` 都拿不到"我是哪一个组件"（冻结形状里动词是无参的），
 *   必须在页面与卡片之间再插一层"按 id 反查描述符"的间接层 —— 那层间接层正是 id 分支的
 *   温床，而且页面还得认识"哪条描述符会回数组"（= 认识 kind，等于把差异搬回页面）。
 *   现在改成：**一个工厂为目录里的每个组件各生成一条描述符**，动作天然绑定自己的 id，
 *   页面依旧是纯 `v-for`，「组件摊成两张卡」是天然结果。`kind` 保留下来表达**取数端点的
 *   形状**（见 [fetchComponentList]：collection 的多个成员共用一次回数组的请求）。
 * · **让描述符直接复用 `stores/webUpdate.ts`**：那个 store 自带 1s 定时器、会话标记与
 *   "手动通道是同步安装"的假设，只服务 `/api/web/*`。三类资源要同一套任务语义
 *   （见 `useResourceTask.ts` 的文件头），复用它只会得到"web 走 store、组件走另一条路"的
 *   分叉。设置页继续用它，本页不碰它。
 * · **给描述符加一个"按钮文案"字段**：冻结形状里没有这个字段。而三类资源共用
 *   「在线更新 / 本地上传」两个动词是准确的（都是"让设备自己从更新源取包安装"与
 *   "用本机选的文件安装"），所以不加字段 —— 卡片按 `status.installed` 微调文案即可。
 */
import { ref } from 'vue';
import { CloudDownloadOutline, CloudOutline, GitNetworkOutline } from '@vicons/ionicons5';
import { Endpoints } from '@/api/contract';
import { getApiClient } from '@/composables/useApi';
import { formatBytes } from '@/composables/utils';
import { readByteLimit } from '@/composables/uploadLimit';
import { COMPONENT_STATE_META, UPDATE_STATE_META, type StateMeta } from '@/views/settings/components/updateShared';

/**
 * 一张卡片的当前状态。
 *
 * ★ **进度是三元组 `state` + `percent` + `message`，不要合并成一个 percentage**：
 * 三类的百分比语义根本不同 —— 组件是真百分比（0→100 一路涨），Web ZIP 的 core 侧
 * progress 实质只有 0/100（开始与结束各写一次），而两者**又各自叠了一层**
 * "浏览器 → core"的上传百分比（那一条不在本类型里，它在 `useResourceTask` 的
 * `uploadPercent` 上）。合并成一个数字之后，"60% 是下载到 60% 还是上传到 60%"就再也
 * 说不清，而这两种 60% 对用户的含义完全不同（一个可以走开，一个不能关页面）。
 */
export interface ResourceStatus {
  id: string;
  /** core 自报的状态键（小写），由描述符的 `stateMeta` 解释成中文标签 */
  state: string;
  /** **服务端**上报的百分比 0-100。浏览器→core 的上传进度不在这里 */
  percent: number;
  /** core 的原话（进度说明 / 失败原因），失败分类见 `classifyPushError` */
  message: string;
  currentVersion: string;
  latestVersion: string;
  installed?: boolean;
  updateAvailable?: boolean;
  available?: boolean;
  size?: number;
}

/**
 * 一条资源描述符。
 *
 * 三个操作动词都是**可选**的：`ResourceCard` 按"有没有这个回调"决定渲不渲染那颗按钮，
 * 所以"Web 前端没有卸载、组件没有回滚"这类差异不需要卡片认识 id（见 [ResourceCard]）。
 */
export interface ResourceDescriptor {
  /** 'web' | 'frpc' | 'cloudflared'；同时也是 `useResourceTask` 的状态键与快照键的来源 */
  id: string;
  /** 显示名 */
  name: string;
  /** 一句话说明 */
  desc: string;
  /** ionicons5 组件 */
  icon: unknown;
  /**
   * `singleton` = 该资源有自己的状态端点（一次回一个对象）；
   * `collection` = 状态来自一个**一次回数组**的共享端点（组件就是这种）。
   * 卡片与弹窗都不读它 —— 它只影响注册表里怎么取数（见 [fetchComponentList]）。
   */
  kind: 'singleton' | 'collection';
  /** 上传接受的后缀，如 '.zip' / '.gz' */
  accept: string;
  /** 取当前状态（列表页与轮询都用它） */
  fetch(): Promise<ResourceStatus | null>;
  /** 拉可安装的版本清单（组件是 `?refresh=true`；web 是 `/web/version`） */
  refresh?(): Promise<void>;
  /** 三个操作动词，**没实现的就不渲染那颗按钮**（不要在组件里 if kind） */
  install?(): Promise<boolean>;
  upload?(file: File, onProgress: (pct: number) => void): Promise<boolean>;
  uninstall?(): Promise<boolean>;
  rollback?(): Promise<void>;
  clear?(): Promise<void>;
  /** 上传上限：从哪个端点/字段读，由描述符自己回答（三类字段名不同） */
  uploadLimit?(status: ResourceStatus | null): number;
  /** 进度词表覆盖（组件要加 EXTRACTING；web/core 用公共表） */
  stateMeta?: Record<string, StateMeta>;
  /** 独有信息行（组件有 installed_size / upstream；web 有 mode / bundledVersion） */
  extraRows?(status: ResourceStatus | null): Array<{ label: string; value: string }>;
  /** 终态钩子 */
  onFinished?(ok: boolean): void;
  /** 组件装完要让隧道页重拉状态 */
  onInstalled?(): void;
  /** 装完会重启宿主 → 轮询要容忍不可达（本次三类都 false，但字段留着） */
  needsHostRestart?: boolean;
}

/** 状态请求的超时：core 只读内存/文件，慢到 5s 就是有问题，快速失败进入下一轮。 */
const STATUS_TIMEOUT_MS = 5000;
/** 手动上传成功后刷新页面的延迟（与 `stores/webUpdate.ts` 的 1200ms 逐字一致）。 */
const RELOAD_DELAY_MS = 1200;

// ── Web 前端资源（singleton）─────────────────────────────────────────────────

/** `/api/web/version` 的快照。`extraRows` 用它，所以放进 ref 让模板能跟到变化。 */
interface WebVersionSnapshot {
  mode: string;
  buildTime: string;
  bundledVersion: string;
  hasBackup: boolean;
}

function createWebDescriptor(): ResourceDescriptor {
  const version = ref<WebVersionSnapshot>({
    mode: '',
    buildTime: '',
    bundledVersion: '',
    hasBackup: false,
  });
  /** `/api/web/status` 的 `max_upload_bytes`（core 侧 50MB）。0 = 老 core 没回，交给服务端拦。 */
  const maxUploadBytes = ref(0);

  async function loadVersion(): Promise<void> {
    const { data } = await getApiClient().get(Endpoints.webAssets.version, { timeout: STATUS_TIMEOUT_MS });
    version.value = {
      mode: String(data?.mode ?? ''),
      buildTime: String(data?.buildTime ?? ''),
      bundledVersion: String(data?.bundledVersion ?? ''),
      hasBackup: data?.hasBackup === true,
    };
  }

  async function doRollback(): Promise<void> {
    const { data } = await getApiClient().post(Endpoints.webAssets.rollback, undefined, { timeout: 0 });
    // 该端点失败时回 400 + `{error}`（axios 直接抛），所以走到这里只可能是 200；
    // 但 200 里仍可能带 `error` 字段（历史行为），照样当失败抛出去，别静默当成功。
    if (data?.error) throw new Error(String(data.error));
  }

  async function doClear(): Promise<void> {
    const { data } = await getApiClient().post(Endpoints.webAssets.clear, undefined, { timeout: 0 });
    if (data?.error) throw new Error(String(data.error));
  }

  return {
    id: 'web',
    name: 'Web 前端服务',
    desc: '运行于设备的 Web 控制面板（dist 打包的 ZIP）。属于服务更新；支持从更新源在线拉取覆盖，也支持使用本机 ZIP 本地安装。',
    icon: CloudDownloadOutline,
    kind: 'singleton',
    accept: '.zip',
    /**
     * ZIP 是**原子覆盖** `assets/web`，不重启 core（见 `WebResourceManager.installFromZip`），
     * 所以轮询期间设备一直可达。字段显式写 false 而不是省掉：让"这一条我们想过"可见 ——
     * 以后加一个装完要重启宿主的资源（如 core APK）时，照着这里改成 true 即可。
     */
    needsHostRestart: false,

    /**
     * 两个端点一起读：`/api/web/status` 是进度与版本号，`/api/web/version` 是模式
     * （内置 / 覆盖）与内置版本 —— 后者没有独立的状态端点，而卡片要把它显示出来。
     * 两者都是**只读文件**（同一个进程、同一台设备），并发不增加网络往返；
     * 轮询只在任务进行中开启（见 `useResourceTask`），量级可以忽略。
     */
    async fetch(): Promise<ResourceStatus | null> {
      const api = getApiClient();
      const [statusRes, versionRes] = await Promise.all([
        api.get(Endpoints.webAssets.status, { timeout: STATUS_TIMEOUT_MS }),
        api.get(Endpoints.webAssets.version, { timeout: STATUS_TIMEOUT_MS }).catch(() => null),
      ]);
      const d = statusRes.data;
      if (!d || typeof d !== 'object') return null;
      if (versionRes?.data) {
        version.value = {
          mode: String(versionRes.data.mode ?? ''),
          buildTime: String(versionRes.data.buildTime ?? ''),
          bundledVersion: String(versionRes.data.bundledVersion ?? ''),
          hasBackup: versionRes.data.hasBackup === true,
        };
      }
      // 老 core 不回 `max_upload_bytes`：保留已读到的值，别用 0 覆盖（否则预检退化成放行一切）
      const limit = readByteLimit(d, 'max_upload_bytes');
      if (limit > 0) maxUploadBytes.value = limit;
      return {
        id: 'web',
        state: String(d.state ?? 'idle').toLowerCase(),
        percent: Number(d.progress ?? 0),
        message: String(d.message ?? ''),
        currentVersion: String(d.current_version ?? ''),
        latestVersion: String(d.latest_version ?? ''),
      };
    },

    /** 「检查更新」= 让 core 重新读一次 `/api/web/version`（只读、无副作用）。 */
    refresh: loadVersion,

    /**
     * 在线更新 = `POST /api/web/check`。该端点的语义是**检查+下载+校验+安装一条龙**
     * （见 contract 里 update 组的说明），200 只代表"已受理/已忽略重复触发"，
     * 真正的结论要靠轮询 `/api/web/status` 落到 done/failed/need_push。
     */
    async install(): Promise<boolean> {
      const { data } = await getApiClient().post(Endpoints.webAssets.check, undefined, { timeout: 0 });
      return data !== undefined;
    },

    /**
     * 本地上传 = `POST /api/web/update`（multipart，字段名必须是 `file`）。
     * **同步端点**：core 收完 body 就地解压安装再回包，所以 2xx + `success` 就是"已装好"，
     * 没有第二步，也不需要轮询。
     */
    async upload(file: File, onProgress: (pct: number) => void): Promise<boolean> {
      const form = new FormData();
      form.append('file', file);
      const { data } = await getApiClient().post(Endpoints.webAssets.update, form, {
        headers: { 'Content-Type': 'multipart/form-data' },
        // 默认 15s 对 50MB 的 ZIP 太短：传到一半会被客户端掐断，而 core 其实还在收
        timeout: 0,
        onUploadProgress: (ev: { loaded: number; total?: number }) => {
          const total = ev.total || file.size;
          if (!total) return;
          onProgress(Math.floor((Math.min(ev.loaded, total) / total) * 100));
        },
      });
      if (!data) return false;
      if (data.success) return true;
      throw new Error(String(data.error || '前端资源安装失败'));
    },

    /**
     * 「回滚」只在**存在上一版备份**时才有意义，所以这两个动词用 getter 而不是固定方法：
     * 卡片按"有没有这个回调"决定渲不渲染那颗按钮（见 `ResourceCard` 的按钮行），
     * 于是"没备份就不显示回滚 / 不是覆盖版本就不显示恢复内置"这两条差异仍然**写在描述符里**，
     * 卡片不必认识 `hasBackup`、也不必认识 `mode`。
     *
     * 为什么读 ref 而不是闭包变量：getter 会在模板渲染时被求值，读 ref 才会被 Vue 收集成
     * 依赖 —— 否则 `fetch()` 更新了备份状态而卡片不重渲染，那颗按钮就永远不出现。
     */
    get rollback() {
      return version.value.hasBackup ? doRollback : undefined;
    },
    get clear() {
      return version.value.mode === 'override' ? doClear : undefined;
    },

    /** 上限来自 `GET /api/web/status` 的 `max_upload_bytes`（不是 `/api/components` 那个）。 */
    uploadLimit: () => maxUploadBytes.value,

    stateMeta: UPDATE_STATE_META,

    extraRows: () => {
      const v = version.value;
      const rows = [
        { label: '当前模式', value: v.mode === 'override' ? '覆盖版本' : '内置版本' },
        { label: '构建时间', value: v.buildTime || '--' },
      ];
      // 只有覆盖版本才有"内置版本"可对照 —— 内置模式下这一行是同一句话，摆出来只是噪声
      if (v.mode === 'override') rows.push({ label: '内置版本', value: v.bundledVersion || '--' });
      return rows;
    },

    /**
     * 终态钩子：**成功必须刷新整页**。core 侧的 web 资源已覆盖，但当前页面跑的还是旧
     * bundle，而 `/assets/*` 带 immutable 长缓存 —— 不刷新用户会以为"点了没反应"。
     * 延迟与改造前一致（1200ms），留出时间让用户看见"更新完成"。
     */
    onFinished: (ok: boolean) => {
      if (ok) window.setTimeout(() => window.location.reload(), RELOAD_DELAY_MS);
    },
  };
}

// ── 可选组件（collection）───────────────────────────────────────────────────

/**
 * `GET /api/components` 的组件条目（core `ComponentManager.listComponents` 的字段）。
 * 只列本页要用的那些 —— 契约是手抄镜像，抄全反而增加漂移面。
 */
interface ComponentItemSnapshot {
  id: string;
  installed: boolean;
  installed_version: string;
  installed_size: number;
  source: string;
  latest_version: string;
  download_size: number;
  upstream: string;
  available: boolean;
  update_available: boolean;
}

interface ComponentListSnapshot {
  items: ComponentItemSnapshot[];
  maxUploadBytes: number;
  manifestError: string;
  progress: { id: string; state: string; percent: number; message: string };
}

/**
 * `kind: 'collection'` 的落点：**一次回数组的共享端点**。
 *
 * `GET /api/components` 一次给出**全部**组件（frpc 与 cloudflared 各一份状态），
 * 外加 `max_upload_bytes` 与当前安装任务的进度槽。所以两个组件的描述符都从这里取数，
 * 而这里用 in-flight 去重把**同一次 tick 里的并发请求合并成一次** —— 否则两张卡各自
 * 1s 轮询，同一个端点会被打两遍（core 是一台手机，这种重复没有意义）。
 *
 * 顺带回答"上传上限从哪读"：组件这一类的上限在这个**列表端点**的 `max_upload_bytes`
 * （core 侧 96MB），**不在** `/status` 端点里 —— 所以描述符的 `uploadLimit` 读的是这里的快照。
 */
let componentListInFlight: Promise<ComponentListSnapshot> | null = null;

function normalizeComponentList(data: unknown): ComponentListSnapshot {
  const raw = (data ?? {}) as Record<string, unknown>;
  return {
    items: Array.isArray(raw.components) ? (raw.components as ComponentItemSnapshot[]) : [],
    maxUploadBytes: readByteLimit(raw, 'max_upload_bytes'),
    manifestError: typeof raw.manifest_error === 'string' ? raw.manifest_error : '',
    progress: {
      id: typeof raw.id === 'string' ? raw.id : '',
      state: String(raw.state ?? 'idle').toLowerCase(),
      percent: Number(raw.percent ?? 0),
      message: typeof raw.message === 'string' ? raw.message : '',
    },
  };
}

function fetchComponentList(force = false): Promise<ComponentListSnapshot> {
  // force（`?refresh=true`）**不并入**正在飞的普通请求：它要的是"重新拉一次清单"，
  // 复用一个旧的在途请求等于把用户点的「检查更新」吞掉。
  const req = (async () => {
    const { data } = await getApiClient().get(Endpoints.components.list, {
      params: force ? { refresh: 'true' } : {},
      timeout: force ? 0 : STATUS_TIMEOUT_MS,
    });
    return normalizeComponentList(data);
  })();
  if (force) return req;
  componentListInFlight = req;
  req
    .catch(() => undefined)
    .then(() => {
      if (componentListInFlight === req) componentListInFlight = null;
    });
  return req;
}

/**
 * 组件装完后的**跨页失效广播**。
 *
 * 组件装/卸会改变 `/api/tunnel/status` 的 `installed` / 版本号，而那个读数是隧道页的。
 * 隧道页每次进入都会自己重拉（`onMounted` + 5s 轮询），所以今天这条广播是"多一层保险"；
 * 它存在的理由是**不引入 store**：组件状态的真源在 core，前端存一份必然过期，
 * 而"通知关心它的页面去重拉"这件事只需要一个事件名，不需要一份会过期的副本。
 */
const COMPONENTS_CHANGED_EVENT = 'ufi:components-changed';

function notifyComponentsChanged(): void {
  window.dispatchEvent(new CustomEvent(COMPONENTS_CHANGED_EVENT));
}

/**
 * 组件目录。
 *
 * ★ **加一个组件 = 在这里加一行**：描述符、卡片、弹窗、动作绑定全部由
 * [createComponentDescriptor] 生成。core 侧 `ComponentManager` 的 catalog 也在这个
 * 端点的响应里，但那份**只有 id / 显示名 / 说明**，没有图标，而且前端必须在首屏就能
 * 渲染出卡片骨架（不能等一次网络往返才知道有几张卡），所以目录仍然写在前端。
 */
const COMPONENT_CATALOG: Array<{ id: string; name: string; desc: string; icon: unknown }> = [
  {
    id: 'frpc',
    name: 'frpc',
    desc: 'FRP 内网穿透客户端，将本机端口映射至自有 frps 服务器；需预先准备 frps 与配置。',
    icon: GitNetworkOutline,
  },
  {
    id: 'cloudflared',
    name: 'cloudflared',
    desc: 'Cloudflare Tunnel 客户端，通过 Cloudflare 边缘网络暴露本机服务，无需公网 IP。',
    icon: CloudOutline,
  },
];

function createComponentDescriptor(entry: {
  id: string;
  name: string;
  desc: string;
  icon: unknown;
}): ResourceDescriptor {
  const { id } = entry;
  /**
   * 最近一次列表快照。放进 ref 是为了让 `extraRows` / getter 里的读取被 Vue 收集成依赖 ——
   * `installed_size`、`upstream`、`available` 这些字段不在 [ResourceStatus] 里
   * （它是三类资源的最小公共面），所以由描述符自己持有。
   */
  const snapshot = ref<ComponentListSnapshot>({
    items: [],
    maxUploadBytes: 0,
    manifestError: '',
    progress: { id: '', state: 'idle', percent: 0, message: '' },
  });

  const item = () => snapshot.value.items.find((i) => i.id === id) ?? null;

  async function load(force: boolean): Promise<void> {
    snapshot.value = await fetchComponentList(force);
  }

  return {
    id,
    name: entry.name,
    desc: entry.desc,
    icon: entry.icon,
    kind: 'collection',
    accept: '.gz,.tgz',
    needsHostRestart: false,

    async fetch(): Promise<ResourceStatus | null> {
      await load(false);
      const snap = snapshot.value;
      const it = item();
      /**
       * core 的进度槽是**全局唯一**的（同一时刻只允许一个安装任务，见
       * `ComponentManager.busy`），所以只有 `progress.id === 自己` 的那个组件才带
       * 服务端自报的 state/percent；另一张卡一律 `idle`，否则会显示成"它也在装"。
       */
      const mine = snap.progress.id === id;
      return {
        id,
        state: mine ? snap.progress.state : 'idle',
        percent: mine ? snap.progress.percent : 0,
        message: mine ? snap.progress.message : '',
        currentVersion: it?.installed_version ?? '',
        latestVersion: it?.latest_version ?? '',
        installed: it?.installed ?? false,
        updateAvailable: it?.update_available ?? false,
        available: it?.available ?? false,
        size: it?.installed_size || it?.download_size || 0,
      };
    },

    /** 「检查更新」= `GET /api/components?refresh=true`（core 同步重拉一次版本清单）。 */
    async refresh(): Promise<void> {
      await load(true);
    },

    async install(): Promise<boolean> {
      const { data } = await getApiClient().post(Endpoints.components.install(id), undefined, { timeout: 0 });
      return data !== undefined;
    },

    /**
     * 本地上传走 **raw body**（`application/octet-stream`），不是 multipart：
     * cloudflared 裸二进制约 36MB，multipart 那套要先在内存里攒一份，低端设备会 OOM。
     * core 按文件头自动识别裸 ELF 与 tar.gz。与 web 一样是**同步**端点 ——
     * `installFromUpload` 跑完才回包，所以成功即安装完成，不需要轮询。
     */
    async upload(file: File, onProgress: (pct: number) => void): Promise<boolean> {
      const { data } = await getApiClient().post(Endpoints.components.upload(id), file, {
        headers: { 'Content-Type': 'application/octet-stream' },
        timeout: 0,
        onUploadProgress: (ev: { loaded: number; total?: number }) => {
          const total = ev.total || file.size;
          if (!total) return;
          onProgress(Math.floor((Math.min(ev.loaded, total) / total) * 100));
        },
      });
      if (!data) return false;
      if (data.success === false) throw new Error(String(data.error || `${id} 上传安装失败`));
      return true;
    },

    async uninstall(): Promise<boolean> {
      const { data } = await getApiClient().post(Endpoints.components.uninstall(id), undefined, { timeout: 0 });
      return data?.success !== false;
    },

    /** 上限来自 `GET /api/components` 的 `max_upload_bytes`（core 侧 96MB）。 */
    uploadLimit: () => snapshot.value.maxUploadBytes,

    stateMeta: COMPONENT_STATE_META,

    extraRows: () => {
      const it = item();
      const rows: Array<{ label: string; value: string }> = [];
      if (it?.installed) {
        rows.push({ label: '占用空间', value: formatBytes(it.installed_size) });
      } else if (it?.download_size) {
        rows.push({ label: '需下载', value: formatBytes(it.download_size) });
      }
      if (it?.upstream) rows.push({ label: '上游', value: it.upstream });
      // 清单拉不到时整组都是"未知组件"：把 core 的原话摆出来，否则用户只看到两个未安装
      if (snapshot.value.manifestError) {
        rows.push({ label: '更新源', value: snapshot.value.manifestError });
      }
      return rows;
    },

    /**
     * 组件装/卸后隧道页要重拉 `/api/tunnel/status`（它的 `installed` / 版本号变了）。
     * 本页不认识那个端点，所以只广播一个失效信号（见 [notifyComponentsChanged]）。
     */
    onInstalled: notifyComponentsChanged,
  };
}

// ── 注册表 ──────────────────────────────────────────────────────────────────

/**
 * Web 前端**服务**包：属于「设置 → 服务更新」，由 `settings/panels/UpdatePanel.vue` 渲染。
 *
 * 2026-10-23 从资源中心拆出：资源中心只负责插件组件（frpc / cloudflared），
 * Web 前端与 core / App 一样是「服务」，更新入口统一在服务更新页。
 */
export const WEB_RESOURCE: ResourceDescriptor = createWebDescriptor();

/**
 * 可选**插件组件**（frpc / cloudflared）：属于「资源中心」`/resources`。
 * 加一个插件只动 [COMPONENT_CATALOG]，这张数组自动跟着长。
 */
export const COMPONENT_RESOURCES: ResourceDescriptor[] = COMPONENT_CATALOG.map(createComponentDescriptor);

/**
 * 失败分类里的**载体名词**（`classifyPushError` 只换这一个名词，分支一字不改）。
 *
 * 从描述符的上传后缀推：`.apk` → APK、`.zip` → ZIP、其余（裸二进制 / tar.gz）→ BIN。
 * 为什么不给描述符加一个字段：冻结形状里没有它，而"上传后缀"本来就是描述符已有的信息，
 * 两者是同一件事（用户选什么文件，失败时就说那个文件叫什么）。
 */
export function artifactNoun(d: ResourceDescriptor): 'APK' | 'ZIP' | 'BIN' {
  const accept = d.accept.toLowerCase();
  if (accept.includes('.apk')) return 'APK';
  if (accept.includes('.zip')) return 'ZIP';
  return 'BIN';
}
