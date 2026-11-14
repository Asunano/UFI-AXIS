/**
 * 资源注册表。
 *
 * ## 用途
 * 每一张可下载资源卡片由本文件的一条 [ResourceDescriptor] 描述：
 * 显示名、说明、图标、上传后缀、状态读取、三个操作动词、上传上限来源、
 * 进度词表、独有信息行与终态钩子。
 *
 * 资源按用途分为两组，入口不混用：
 * · [WEB_RESOURCE]：Web 前端服务包，仅在「设置 → 服务更新」中渲染；
 * · [COMPONENT_RESOURCES]：frpc / cloudflared 等插件组件，仅在「资源中心」`/resources` 中渲染。
 * 两者共用 `ResourceCard` / `useResourceTask`，差异只体现在描述符上。
 *
 * ## 描述符独立成文件的原因
 * 三类资源（Web 前端 ZIP / frpc / cloudflared）的差异集中在端点与语义：
 * · Web 前端走 `POST /api/web/check`（core 自行从更新源拉包），手选 ZIP 走 multipart，
 *   其 core 侧进度实质只有 0/100（`WebUpdateManager` 只在开始与结束时写 progress）；
 * · 两个组件走 `POST /api/components/{id}/install`，手选裸二进制走 raw body，
 *   进度为真实百分比，状态机还多一档 `EXTRACTING`（tar.gz 需要先解包）。
 * 以 `if (id === 'frpc')` 表达这些差异会持续产生新分支；以描述符表达时，
 * 新增资源只需新增一条描述符（组件无需新写描述符，见 [COMPONENT_CATALOG]）。
 *
 * ## 未采用的替代方案
 * · 一个 `components` 描述符 + `fetch()` 返回数组、由页面摊平：数组形式下
 *   `install()` / `upload()` 无法得知所属组件（冻结形状中的动词不带参数），
 *   必须在页面与卡片之间增加按 id 反查描述符的间接层，该层正是 id 分支的来源，
 *   且页面还需识别哪条描述符会返回数组（即识别 kind，差异被移回页面）。
 *   现改为由工厂为目录中的每个组件各生成一条描述符，动作绑定自身 id，
 *   页面保持纯 `v-for`，「组件摊为多张卡」是自然结果。`kind` 保留用于表达
 *   取数端点的形状（见 [fetchComponentList]：collection 的多个成员共用一次回数组的请求）。
 * · 描述符直接复用 `stores/webUpdate.ts`：该 store 自带 1s 定时器、会话标记与
 *   「手动通道是同步安装」的假设，只服务 `/api/web/*`。三类资源需要同一套任务语义
 *   （见 `useResourceTask.ts` 的文件头），复用会导致 web 走 store、组件走另一条路径的分叉。
 *   设置页继续使用该 store，本页不依赖它。
 * · 给描述符增加「按钮文案」字段：冻结形状中没有该字段。三类资源共用
 *   「在线更新 / 本地上传」两个动词是准确的（分别为「设备从更新源取包安装」与
 *   「使用本机选择的文件安装」），卡片按 `status.installed` 微调文案即可。
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
 * ★ 进度由三元组 `state` + `percent` + `message` 表达，不合并为单个 percentage：
 * 三类资源的百分比语义不同：组件为真实百分比（0→100），Web ZIP 的 core 侧
 * progress 实质只有 0/100（开始与结束各写一次）；两者又各自叠加一层
 * 「浏览器 → core」的上传百分比（不在本类型中，位于 `useResourceTask` 的 `uploadPercent`）。
 * 合并为单个数字后，无法区分「60% 是下载进度还是上传进度」，
 * 而两者的用户含义不同（一个可以离开页面，一个不能关闭页面）。
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
 * 三个操作动词均为可选：`ResourceCard` 按回调是否存在决定是否渲染对应按钮，
 * 因此「Web 前端没有卸载、组件没有回滚」这类差异不需要卡片识别 id（见 [ResourceCard]）。
 */
export interface ResourceDescriptor {
  /** 'web' | 'frpc' | 'cloudflared'；同时是 `useResourceTask` 的状态键与快照键来源。 */
  id: string;
  /** 显示名。 */
  name: string;
  /** 一句话说明。 */
  desc: string;
  /** ionicons5 组件。 */
  icon: unknown;
  /**
   * `singleton`：该资源有独立的状态端点（一次返回一个对象）；
   * `collection`：状态来自一次返回数组的共享端点（组件属于此类）。
   * 卡片与弹窗都不读取该字段，它只影响注册表中的取数方式（见 [fetchComponentList]）。
   */
  kind: 'singleton' | 'collection';
  /** 上传接受的后缀，如 '.zip' / '.gz'。 */
  accept: string;
  /** 读取当前状态（列表页与轮询均使用）。 */
  fetch(): Promise<ResourceStatus | null>;
  /** 拉取可安装的版本清单（组件使用 `?refresh=true`；web 使用 `/web/version`）。 */
  refresh?(): Promise<void>;
  /** 三个操作动词；未实现的动词不渲染对应按钮（组件内不得按 kind 分支）。 */
  install?(): Promise<boolean>;
  upload?(file: File, onProgress: (pct: number) => void): Promise<boolean>;
  uninstall?(): Promise<boolean>;
  rollback?(): Promise<void>;
  clear?(): Promise<void>;
  /** 上传上限的来源端点与字段，由描述符自身提供（三类资源的字段名不同）。 */
  uploadLimit?(status: ResourceStatus | null): number;
  /** 进度词表覆盖（组件需要增加 EXTRACTING；web 与 core 使用公共表）。 */
  stateMeta?: Record<string, StateMeta>;
  /** 独有信息行（组件有 installed_size / upstream；web 有 mode / bundledVersion）。 */
  extraRows?(status: ResourceStatus | null): Array<{ label: string; value: string }>;
  /** 终态钩子。 */
  onFinished?(ok: boolean): void;
  /** 组件安装完成后需通知隧道页重新拉取状态。 */
  onInstalled?(): void;
  /** 安装后是否重启宿主进程；为 true 时轮询需容忍不可达（当前三类资源均为 false）。 */
  needsHostRestart?: boolean;
}

/** 状态请求超时：core 只读取内存与文件，耗时超过 5s 即异常，应快速失败进入下一轮。 */
const STATUS_TIMEOUT_MS = 5000;
/** 手动上传成功后刷新页面的延迟（与 `stores/webUpdate.ts` 的 1200ms 一致）。 */
const RELOAD_DELAY_MS = 1200;

// ── Web 前端资源（singleton）─────────────────────────────────────────────────

/** `/api/web/version` 的快照。`extraRows` 使用它，因此放入 ref 使模板能跟踪变化。 */
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
  /** `/api/web/status` 的 `max_upload_bytes`（core 侧 50MB）。0 表示老 core 未返回该字段，交由服务端拦截。 */
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
    // 该端点失败时返回 400 + `{error}`（axios 直接抛出），因此执行到此处只可能是 200；
    // 但 200 响应体仍可能带 `error` 字段（历史行为），同样按失败抛出，不静默视为成功。
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
     * ZIP 以原子覆盖方式写入 `assets/web`，不重启 core（见 `WebResourceManager.installFromZip`），
     * 因此轮询期间设备始终可达。该字段显式写为 false 而非省略，用于记录此结论：
     * 新增需要重启宿主的资源（如 core APK）时，改为 true 即可。
     */
    needsHostRestart: false,

    /**
     * 两个端点一起读取：`/api/web/status` 提供进度与版本号，`/api/web/version` 提供模式
     * （内置 / 覆盖）与内置版本；后者没有独立的状态端点，而卡片需要显示这些信息。
     * 两者都是只读文件（同一进程、同一设备），并发不增加网络往返；
     * 轮询仅在任务进行中开启（见 `useResourceTask`），请求量可忽略。
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
      // 老 core 不返回 `max_upload_bytes`：保留已读到的值，不用 0 覆盖（否则预检会放行任意大小）。
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

    /** 「检查更新」：让 core 重新读取一次 `/api/web/version`（只读、无副作用）。 */
    refresh: loadVersion,

    /**
     * 在线更新 = `POST /api/web/check`。该端点的语义为检查、下载、校验、安装一次完成
     * （见 contract 中 update 组的说明）；200 仅表示已受理或已忽略重复触发，
     * 实际结论需通过轮询 `/api/web/status` 落到 done/failed/need_push。
     */
    async install(): Promise<boolean> {
      const { data } = await getApiClient().post(Endpoints.webAssets.check, undefined, { timeout: 0 });
      return data !== undefined;
    },

    /**
     * 本地上传 = `POST /api/web/update`（multipart，字段名必须为 `file`）。
     * 同步端点：core 收完 body 就地解压安装再回包，因此 2xx 且 `success` 即表示安装完成，
     * 没有第二步，也不需要轮询。
     */
    async upload(file: File, onProgress: (pct: number) => void): Promise<boolean> {
      const form = new FormData();
      form.append('file', file);
      const { data } = await getApiClient().post(Endpoints.webAssets.update, form, {
        headers: { 'Content-Type': 'multipart/form-data' },
        // 默认 15s 对 50MB 的 ZIP 过短：传输中途会被客户端中断，而 core 仍在接收。
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
     * 「回滚」仅在存在上一版备份时才有意义，因此这两个动词使用 getter 而非固定方法：
     * 卡片按回调是否存在决定是否渲染按钮（见 `ResourceCard` 的按钮行），
     * 从而把「无备份不显示回滚 / 非覆盖版本不显示恢复内置」这两条差异保留在描述符中，
     * 卡片无需识别 `hasBackup` 或 `mode`。
     *
     * 读取 ref 而非闭包变量：getter 在模板渲染时求值，读取 ref 才会被 Vue 收集为依赖；
     * 否则 `fetch()` 更新备份状态后卡片不会重新渲染，按钮永远不会出现。
     */
    get rollback() {
      return version.value.hasBackup ? doRollback : undefined;
    },
    get clear() {
      return version.value.mode === 'override' ? doClear : undefined;
    },

    /** 上限来自 `GET /api/web/status` 的 `max_upload_bytes`，不是 `/api/components` 的对应字段。 */
    uploadLimit: () => maxUploadBytes.value,

    stateMeta: UPDATE_STATE_META,

    extraRows: () => {
      const v = version.value;
      const rows = [
        { label: '当前模式', value: v.mode === 'override' ? '覆盖版本' : '内置版本' },
        { label: '构建时间', value: v.buildTime || '--' },
      ];
      // 仅覆盖版本存在可对照的内置版本；内置模式下该行内容重复，不显示。
      if (v.mode === 'override') rows.push({ label: '内置版本', value: v.bundledVersion || '--' });
      return rows;
    },

    /**
     * 终态钩子：成功时必须刷新整页。core 侧 web 资源已覆盖，但当前页面运行的仍是旧 bundle，
     * 且 `/assets/*` 使用 immutable 长缓存，不刷新会使操作看起来没有生效。
     * 延迟为 1200ms，留出时间显示更新完成。
     */
    onFinished: (ok: boolean) => {
      if (ok) window.setTimeout(() => window.location.reload(), RELOAD_DELAY_MS);
    },
  };
}

// ── 可选组件（collection）───────────────────────────────────────────────────

/**
 * `GET /api/components` 的组件条目（core `ComponentManager.listComponents` 的字段）。
 * 仅列出本页使用的字段：该契约是手工维护的镜像，抄全反而增加漂移面。
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
 * `kind: 'collection'` 对应的实现：一次返回数组的共享端点。
 *
 * `GET /api/components` 一次返回全部组件（frpc 与 cloudflared 各一份状态），
 * 以及 `max_upload_bytes` 与当前安装任务的进度槽。两个组件的描述符都从这里取数，
 * 此处用 in-flight 去重把同一 tick 内的并发请求合并为一次，避免两张卡各自 1s 轮询
 * 导致同一端点被请求两次（core 运行在手机上，重复请求没有意义）。
 *
 * 上传上限的来源：组件类资源的上限在该列表端点的 `max_upload_bytes`
 * （core 侧 96MB），不在 `/status` 端点中；描述符的 `uploadLimit` 读取此处的快照。
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
  // force（`?refresh=true`）不并入正在执行的普通请求：它要求重新拉取清单，
  // 复用一个旧的在途请求会使「检查更新」失效。
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
 * 组件装完后的跨页失效广播。
 *
 * 组件安装或卸载会改变 `/api/tunnel/status` 的 `installed` 与版本号，该读数为隧道页所有。
 * 隧道页每次进入都会自行重拉（`onMounted` + 5s 轮询），因此该广播是额外保障；
 * 其存在理由是避免引入 store：组件状态的真源在 core，前端副本必然过期，
 * 而「通知关心它的页面重新拉取」只需要一个事件名，不需要会过期的副本。
 */
const COMPONENTS_CHANGED_EVENT = 'ufi:components-changed';

function notifyComponentsChanged(): void {
  window.dispatchEvent(new CustomEvent(COMPONENTS_CHANGED_EVENT));
}

/**
 * 组件目录。
 *
 * ★ 新增组件只需在此增加一行：描述符、卡片、弹窗与动作绑定均由
 * [createComponentDescriptor] 生成。core 侧 `ComponentManager` 的 catalog 也包含在
 * 该端点的响应中，但那份数据只有 id / 显示名 / 说明，没有图标；前端必须在首屏
 * 渲染卡片骨架（不能等一次网络往返才知道卡片数量），因此目录仍定义在前端。
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
   * 最近一次列表快照。放入 ref 是为了让 `extraRows` / getter 中的读取被 Vue 收集为依赖：
   * `installed_size`、`upstream`、`available` 等字段不在 [ResourceStatus] 中
   * （后者是三类资源的最小公共面），由描述符自行持有。
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
       * core 的进度槽全局唯一（同一时刻只允许一个安装任务，见 `ComponentManager.busy`），
       * 因此只有 `progress.id === 自身 id` 的组件携带服务端上报的 state/percent；
       * 另一张卡一律为 `idle`，否则会显示为该组件也在安装。
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

    /** 「检查更新」= `GET /api/components?refresh=true`（core 同步重新拉取一次版本清单）。 */
    async refresh(): Promise<void> {
      await load(true);
    },

    async install(): Promise<boolean> {
      const { data } = await getApiClient().post(Endpoints.components.install(id), undefined, { timeout: 0 });
      return data !== undefined;
    },

    /**
     * 本地上传使用 raw body（`application/octet-stream`），不使用 multipart：
     * cloudflared 裸二进制约 36MB，multipart 需要先在内存中缓冲一份，低端设备会 OOM。
     * core 按文件头自动识别裸 ELF 与 tar.gz。与 web 相同为同步端点：
     * `installFromUpload` 执行完才回包，因此成功即安装完成，不需要轮询。
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
      // 清单拉取失败时整组均为未知组件：直接展示 core 返回的原因，否则只会显示为未安装。
      if (snapshot.value.manifestError) {
        rows.push({ label: '更新源', value: snapshot.value.manifestError });
      }
      return rows;
    },

    /**
     * 组件安装或卸载后，隧道页需要重拉 `/api/tunnel/status`（其 `installed` 与版本号已变化）。
     * 本页不识别该端点，因此只广播一个失效信号（见 [notifyComponentsChanged]）。
     */
    onInstalled: notifyComponentsChanged,
  };
}

// ── 注册表 ──────────────────────────────────────────────────────────────────

/**
 * Web 前端服务包：属于「设置 → 服务更新」，由 `settings/panels/UpdatePanel.vue` 渲染。
 *
 * 资源中心只负责插件组件（frpc / cloudflared）；Web 前端与 core / App 同属服务，
 * 更新入口统一在服务更新页。
 */
export const WEB_RESOURCE: ResourceDescriptor = createWebDescriptor();

/**
 * 可选插件组件（frpc / cloudflared）：属于「资源中心」`/resources`。
 * 新增插件只需修改 [COMPONENT_CATALOG]，该数组自动跟随。
 */
export const COMPONENT_RESOURCES: ResourceDescriptor[] = COMPONENT_CATALOG.map(createComponentDescriptor);

/**
 * 失败分类中的载体名词（`classifyPushError` 仅替换该名词，其余分支不变）。
 *
 * 由描述符的上传后缀推导：`.apk` → APK，`.zip` → ZIP，其余（裸二进制 / tar.gz）→ BIN。
 * 不为描述符新增字段：冻结形状中没有该字段，而上传后缀本身已是描述符已有的信息，
 * 两者表达同一事实（用户选择何种文件，失败提示即使用该文件类型的名称）。
 */
export function artifactNoun(d: ResourceDescriptor): 'APK' | 'ZIP' | 'BIN' {
  const accept = d.accept.toLowerCase();
  if (accept.includes('.apk')) return 'APK';
  if (accept.includes('.zip')) return 'ZIP';
  return 'BIN';
}
