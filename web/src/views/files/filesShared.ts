/**
 * 文件页的共享类型与纯函数。
 *
 * 拆分原因：图标、元信息与磁盘百分比等格式化逻辑由列表面板使用；路径白名单由工具栏与
 * 导航使用；预览类型判定由列表（决定是否显示「预览」按钮）与预览弹窗（决定使用
 * img/video/audio）共同使用。保留在 SFC 内会导致重复实现。
 */
import { useAppStore } from '@/stores/app';
import { loadDeviceIdentity } from '@/composables/deviceIdentityLazy';
import { formatBytes } from '@/composables/utils';

/**
 * core /api/files/list 的条目。
 * 保留索引签名：core 各端点返回的字段不一致（`/search` 只有 name/path/isDirectory，
 * `/info` 另有 permissions/owner），固定字段集会在其他位置产生类型错误。
 */
export interface FileEntry {
  name: string;
  path: string;
  isDirectory: boolean;
  size?: number;
  lastModified?: number;
  [key: string]: any;
}

/** 内部存储（primary volume）的真实路径。 */
export const PRIMARY_STORAGE = '/storage/emulated/0';

/** `/sdcard` 是 primary 的别名，展示与导航统一映射到 [PRIMARY_STORAGE]。 */
export const SDCARD_ALIAS = '/sdcard';

/**
 * 远端存储源的路径前缀。
 *
 * 与 core 的 `FileProviderRegistry.REMOTE_PREFIX` 必须同值；客户端路径形态为
 * `remote:<sourceId>/<相对路径>`（由 `toClientPath` 拼出）。
 * 修改此处必须同步修改 core，否则远端源访问会报路径非法。
 */
export const REMOTE_PREFIX = 'remote:';

/** 把 `/sdcard` 别名归一为 `/storage/emulated/0`，避免同一目录出现两条路径。 */
export function normalizePath(p: string): string {
  if (!p) return PRIMARY_STORAGE;
  if (p === SDCARD_ALIAS) return PRIMARY_STORAGE;
  if (p.startsWith(`${SDCARD_ALIAS}/`)) return PRIMARY_STORAGE + p.slice(SDCARD_ALIAS.length);
  return p;
}

export function isPrimaryStoragePath(p: string): boolean {
  const n = normalizePath(p);
  return n === PRIMARY_STORAGE || n.startsWith(`${PRIMARY_STORAGE}/`);
}

/**
 * 是否允许导航到该路径（与 core `isUserStoragePath` 对齐，且更严格）。
 *
 * 刻意禁止：
 * - `/storage`、`/storage/emulated`：中间层目录，内容为系统占位或非预期结构；
 * - `/sdcard` 别名本身（归一后等于 PRIMARY，可进入）；
 * - `/`、`/data` 及更上层的路径。
 */
export function isAllowedRoot(p: string): boolean {
  const n = normalizePath(p);
  // 远端存储源：`remote:<sourceId>[/相对路径]`，与 core 的
  // FileProviderRegistry.REMOTE_PREFIX 同值。文件 API 全线接受该路径形式
  // （list / upload / download / rename / delete / mkdir / search 均走 registry.resolve），
  // 因此远端源与本地卷共用同一套浏览逻辑；缺少该分支会使远端源报「不在可访问范围内」。
  if (n.startsWith(REMOTE_PREFIX)) return n.length > REMOTE_PREFIX.length;
  if (isPrimaryStoragePath(n)) return true;
  // 其它卷：/storage/XXXX-XXXX（SD 卡）及其子目录
  if (n === '/storage') return false;
  if (n === '/storage/emulated') return false;
  if (n.startsWith('/storage/')) return true;
  // USB / SD 的另一条挂载路径
  if (n === '/mnt/media_rw') return false;
  if (n.startsWith('/mnt/media_rw/')) return true;
  return false;
}

export interface Crumb {
  label: string;
  path: string;
}

/**
 * 面包屑。主存储根显示为「内部存储」，不暴露 storage/emulated/0；
 * 其它卷显示真实目录名。
 */
export function breadcrumbSegments(path: string): Crumb[] {
  const n = normalizePath(path);
  // 远端存储源：`remote:<id>[/相对路径]`。
  // 首段必须保留 `remote:<id>` 整体；按 '/' 拆分会产生 `/remote:abc` 之类非法路径，
  // 点击面包屑会报路径非法。
  if (n.startsWith(REMOTE_PREFIX)) {
    const rest = n.slice(REMOTE_PREFIX.length);
    const slash = rest.indexOf('/');
    const id = slash < 0 ? rest : rest.slice(0, slash);
    const crumbs: Crumb[] = [{ label: `远端：${id}`, path: `${REMOTE_PREFIX}${id}/` }];
    if (slash >= 0) {
      let acc = `${REMOTE_PREFIX}${id}`;
      for (const part of rest.slice(slash).split('/').filter(Boolean)) {
        acc += `/${part}`;
        crumbs.push({ label: part, path: acc });
      }
    }
    return crumbs;
  }
  if (isPrimaryStoragePath(n)) {
    const crumbs: Crumb[] = [{ label: '内部存储', path: PRIMARY_STORAGE }];
    const rest = n.slice(PRIMARY_STORAGE.length);
    if (rest && rest !== '/') {
      let acc = PRIMARY_STORAGE;
      for (const part of rest.split('/').filter(Boolean)) {
        acc += `/${part}`;
        crumbs.push({ label: part, path: acc });
      }
    }
    return crumbs;
  }
  const parts = n.split('/').filter(Boolean);
  if (!parts.length) return [{ label: '根目录', path: '/' }];
  let acc = '';
  return parts.map((part) => {
    acc += `/${part}`;
    return { label: part, path: acc };
  });
}

/** 去除尾部斜杠（`/a/b/` → `/a/b`；根路径 `/` 保持不变）。 */
export function trimTrailingSlash(p: string): string {
  const t = p.replace(/\/+$/, '');
  return t === '' ? '/' : t;
}

/**
 * 当前路径所属卷的根：内部存储为 `/storage/emulated/0`，SD 卡为 `/storage/XXXX-XXXX`。
 *
 * 搜索的「整个存储」档以卷根为起点（core 的 `/search` 使用 `root.walkTopDown()`）；
 * 以 `/` 或 `/storage` 为起点会被 core 的 `safeResolve` 拒绝（不在用户存储白名单内）。
 */
export function volumeRootOf(path: string): string {
  const n = normalizePath(path);
  if (isPrimaryStoragePath(n)) return PRIMARY_STORAGE;
  const m = n.match(/^(\/storage\/[^/]+|\/mnt\/media_rw\/[^/]+)/);
  return m?.[1] ?? PRIMARY_STORAGE;
}

/** 路径的父目录（与 core `parentOf` 规则一致：去除末段；已到根则返回自身）。 */
export function parentPathOf(path: string): string {
  const p = trimTrailingSlash(path);
  const idx = p.lastIndexOf('/');
  if (idx < 0) return p;
  return idx === 0 ? '/' : p.slice(0, idx);
}

/** 路径末段（用作显示名）。 */
export function baseNameOf(path: string): string {
  const p = trimTrailingSlash(path);
  const idx = p.lastIndexOf('/');
  return idx < 0 ? p : p.slice(idx + 1);
}

/**
 * 隐藏文件（以 `.` 开头）。
 *
 * core `/files/list` 不过滤隐藏项，该开关只能在客户端实现；因此「显示隐藏文件」
 * 必须作用于列表渲染层，而不是重新请求。
 */
export function isHiddenName(name: string): boolean {
  return name.startsWith('.') && name !== '.' && name !== '..';
}

/**
 * 在目标目录中生成不冲突的名字：`a.txt` → `a (2).txt`。
 *
 * 必须在客户端实现：core 的 `/copy` 使用 `REPLACE_EXISTING` 静默覆盖，
 * `/move` 遇到已存在目标返回 `success:false`（目标已存在）。
 * 两者都不改名，直接粘贴会导致数据丢失或操作失败。
 *
 * @param existing 目标目录当前已有的名字集合（粘贴过程中新增的名字也必须并入）
 */
export function uniqueChildName(existing: Iterable<string>, name: string): string {
  const taken = new Set(existing);
  if (!taken.has(name)) return name;
  // 隐藏文件（如 `.gitignore`）的首位点号不作为扩展名分隔符。
  const dot = name.lastIndexOf('.');
  const hasExt = dot > 0;
  const base = hasExt ? name.slice(0, dot) : name;
  const ext = hasExt ? name.slice(dot) : '';
  for (let i = 2; i < 1000; i++) {
    const cand = `${base} (${i})${ext}`;
    if (!taken.has(cand)) return cand;
  }
  return `${base} (${Date.now()})${ext}`;
}

/**
 * 搜索结果所在目录相对当前目录的展示文案，用于回答多级目录下「结果位于何处」。
 *
 * 各情况分别表述（同名文件在不同层级很常见，仅显示文件名不足以定位）：
 *   · 就在当前目录        → 「当前目录」
 *   · 在当前目录之下      → 相对路径（`Backup` / `Archive/2024`）
 *   · 在当前目录之上      → `↑ DCIM`（指向上级分支，避免误读为子目录）
 *   · 不同分支（换了卷）  → `…/两级目录名`
 */
export function relativeDirLabel(dir: string, currentPath: string): string {
  const d = trimTrailingSlash(normalizePath(dir));
  const c = trimTrailingSlash(normalizePath(currentPath));
  if (d === c) return '当前目录';
  if (d.startsWith(`${c}/`)) return d.slice(c.length + 1);
  if (c.startsWith(`${d}/`)) {
    return d === PRIMARY_STORAGE ? '↑ 内部存储' : `↑ ${baseNameOf(d)}`;
  }
  const parts = d.split('/').filter(Boolean);
  return `…/${parts.slice(-2).join('/')}`;
}

export function formatDate(ts: number): string {
  if (!ts) return '--';
  const d = new Date(ts);
  /**
   * 同年只显示「月-日 时:分」（列表元信息需要简短），跨年时补充年份：
   * 缺少年份时，去年 3 月与今年 3 月修改的文件在列表与详情中无法区分。
   */
  const sameYear = d.getFullYear() === new Date().getFullYear();
  return d.toLocaleString(
    'zh-CN',
    sameYear
      ? { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' }
      : { year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' }
  );
}

/** core diskMap 返回的均为字符串（size/used/available 已格式化，usePercent 形如 "57%"）。 */
export function diskPercent(d: any): number {
  const raw = String(d?.usePercent ?? '0').replace('%', '');
  const n = Number(raw);
  return isNaN(n) ? 0 : Math.min(100, Math.max(0, Math.round(n)));
}

/** /api/files/search 仅返回 name/path/isDirectory，没有 size/lastModified。 */
export function fileMeta(f: FileEntry): string {
  if (f.isDirectory) return '文件夹';
  if (f.size == null || f.lastModified == null) return '文件';
  return `${formatBytes(f.size)} · ${formatDate(f.lastModified)}`;
}

/** 文件类别短名，用于选择对应的 SVG 图标；不使用 emoji。 */
export type FileIconKind = 'dir' | 'image' | 'video' | 'audio' | 'text' | 'archive' | 'apk' | 'doc' | 'file';

const EXT_ICON_KIND: Record<string, FileIconKind> = {
  jpg: 'image',
  jpeg: 'image',
  png: 'image',
  gif: 'image',
  svg: 'image',
  webp: 'image',
  bmp: 'image',
  mp4: 'video',
  mkv: 'video',
  mov: 'video',
  m4v: 'video',
  webm: 'video',
  mp3: 'audio',
  wav: 'audio',
  flac: 'audio',
  ogg: 'audio',
  aac: 'audio',
  m4a: 'audio',
  opus: 'audio',
  pdf: 'doc',
  doc: 'doc',
  docx: 'doc',
  xls: 'doc',
  xlsx: 'doc',
  zip: 'archive',
  tar: 'archive',
  gz: 'archive',
  rar: 'archive',
  '7z': 'archive',
  apk: 'apk',
  txt: 'text',
  log: 'text',
  json: 'text',
  md: 'text',
  yaml: 'text',
  yml: 'text',
  toml: 'text',
  conf: 'text',
  cfg: 'text',
  ini: 'text',
  sh: 'text',
  py: 'text',
  js: 'text',
  ts: 'text',
  kt: 'text',
  java: 'text',
};

function extOf(name: string): string {
  return name.split('.').pop()?.toLowerCase() || '';
}

export function fileIconKind(f: FileEntry): FileIconKind {
  if (f.isDirectory) return 'dir';
  return EXT_ICON_KIND[extOf(f.name)] || 'file';
}

/**
 * 可在线编辑或打开的扩展名。
 * 与 app `FileKind` 的 TEXT 档对齐（svg 单独走图片预览，不在本表内）。
 */
export const TEXT_EDIT_EXTS = [
  'txt',
  'log',
  'md',
  'csv',
  'ini',
  'conf',
  'cfg',
  'prop',
  'properties',
  'json',
  'xml',
  'yaml',
  'yml',
  'toml',
  'html',
  'htm',
  'css',
  'js',
  'ts',
  'kt',
  'kts',
  'java',
  'gradle',
  'sh',
  'bash',
  'zsh',
  'py',
  'c',
  'cpp',
  'h',
  'hpp',
  'go',
  'rs',
  'sql',
  'env',
];

export function isTextEditable(f: FileEntry): boolean {
  return TEXT_EDIT_EXTS.includes(extOf(f.name));
}

// ── 媒体预览 ──
// /api/files/stream 需要鉴权头，不能直接作为 <img>/<video> 的 src，
// 必须先 fetch 成 blob 再转 object URL，并在关闭或卸载时 revoke 以避免泄漏。
/** 整文件读入内存的上限；超过该大小不提供预览，避免浏览器内存耗尽。 */
export const PREVIEW_MAX_BYTES = 100 * 1024 * 1024;

/**
 * 预览后缀。以 app `FileKind` 为基准，再按浏览器实际解码与播放能力过滤：
 * - heic/heif：多数桌面浏览器无法解码，不纳入（纳入只会显示损坏图片）
 * - flv/avi/3gp：Chromium 系基本不支持，纳入会得到空播放器
 * - svg：浏览器可直接作为图片显示，保留预览入口
 */
const PREVIEW_IMAGE_EXTS = ['jpg', 'jpeg', 'png', 'gif', 'webp', 'bmp', 'svg'];
const PREVIEW_VIDEO_EXTS = ['mp4', 'webm', 'mkv', 'mov', 'm4v'];
const PREVIEW_AUDIO_EXTS = ['mp3', 'wav', 'ogg', 'flac', 'm4a', 'aac', 'opus'];

export type PreviewKind = 'image' | 'video' | 'audio' | '';

export function previewKindOf(name: string): PreviewKind {
  const ext = extOf(name);
  if (PREVIEW_IMAGE_EXTS.includes(ext)) return 'image';
  if (PREVIEW_VIDEO_EXTS.includes(ext)) return 'video';
  if (PREVIEW_AUDIO_EXTS.includes(ext)) return 'audio';
  return '';
}

export function canPreview(f: FileEntry): boolean {
  return !f.isDirectory && previewKindOf(f.name || '') !== '';
}

/**
 * 双击后的「打开」意图：媒体预览 → 文本编辑 → APK 安装 → 详情（不可在线打开）。
 *
 * 刻意不包含 `extract`：压缩包双击只打开详情弹窗。解压会修改设备文件系统，
 * 双击这种无确认的手势不应直接执行，与 app 侧 `FileManagerRoot.resolveOpenRoute`
 * 遵循同一规则。
 */
export type OpenAction = 'preview' | 'text' | 'install' | 'info' | 'none';

/** 归档类型：可解压的格式（zip/tar/tar.gz/单文件 gz）返回具体类型，rar/7z 等返回 null。 */
export function archiveKindOf(name: string): 'zip' | 'tgz' | 'tar' | 'gz' | null {
  const lower = name.toLowerCase();
  if (lower.endsWith('.zip')) return 'zip';
  if (lower.endsWith('.tar.gz') || lower.endsWith('.tgz')) return 'tgz';
  if (lower.endsWith('.tar')) return 'tar';
  if (lower.endsWith('.gz')) return 'gz';
  return null;
}

/** 该文件能否被「解压」（rar/7z 需要原生库，当前不支持，故返回 false）。 */
export function canExtract(f: FileEntry): boolean {
  return !f.isDirectory && archiveKindOf(f.name) != null;
}

export function openActionOf(f: FileEntry): OpenAction {
  if (f.isDirectory) return 'none';
  if (canPreview(f)) return 'preview';
  if (isTextEditable(f)) return 'text';
  if (extOf(f.name) === 'apk') return 'install';
  // 压缩包、文档与未知扩展名均落在此分支：详情弹窗内由主操作按钮触发解压。
  return 'info';
}

/** 该类型的「打开」动作文案，用于菜单首项与详情弹窗主按钮（与 app `openActionLabel` 对齐）。 */
export function openActionLabelOf(f: FileEntry): string {
  if (f.isDirectory) return '进入';
  switch (previewKindOf(f.name || '')) {
    case 'image':
      return '查看图片';
    case 'video':
      return '播放视频';
    case 'audio':
      return '播放音频';
  }
  if (isTextEditable(f)) return '查看 / 编辑';
  return '详情';
}

/** 详情弹窗右侧的唯一主操作。`null` 表示该类型没有可用的主操作，弹窗退化为单个「关闭」。 */
export interface PrimaryFileAction {
  /** 与 `FilesView.handleAction` 使用同一组 key */
  action: string;
  label: string;
}

/**
 * 主操作判定。分支顺序与 app 的 `primaryFileAction` 一致：
 * `canExtract` 在 apk 之前（按文件名后缀判断）；apk 不提供 `open`
 * （该操作只会重新打开当前弹窗）。
 *
 * 不可解压的 rar/7z、pdf 等文档、未知扩展名一律返回 null：
 * 宁可少一个按钮，也不提供点击后仅重新打开同一弹窗的无效按钮。
 */
export function primaryFileActionOf(f: FileEntry): PrimaryFileAction | null {
  if (f.isDirectory) return { action: 'open', label: '进入' };
  if (canExtract(f)) return { action: 'extract', label: '解压' };
  if (extOf(f.name) === 'apk') return { action: 'install', label: '安装 APK' };
  if (canPreview(f) || isTextEditable(f)) return { action: 'open', label: openActionLabelOf(f) };
  return null;
}

// ── 上传 ──
/**
 * 单文件上传体积上限的保守回退值。
 *
 * 真值由设备端下发（`GET /api/files/status` 的 `max_upload_bytes`，对应 core 的
 * `FileRoutes.UPLOAD_BODY_LIMIT`），见 [fetchUploadCaps]。此处保留一份用于两种情况：
 * ① 老固件不返回对应字段；② 能力探测失败。
 *
 * 必须在客户端拦截，而不能仅依赖服务端 413：
 * 服务端确实在 `onCall` 阶段读取 `Content-Length` 就返回 413，并未真正接收整个文件；
 * 但在没有 `Expect: 100-continue` 协商时，客户端发出请求头后即无条件推送 body，
 * 浏览器要等 body 发完才处理响应，于是 `xhr.upload.onprogress` 会跑到 100%，
 * `onload` 才拿到 413。结果是进度满格后才提示超限，而这段时间的上行带宽已被消耗。
 */
export const UPLOAD_MAX_BYTES_FALLBACK = 200 * 1024 * 1024;

/** 分片上传的能力位（`GET /api/files/status`）。老固件只返回 `isExternalStorageManager`。 */
export interface UploadCaps {
  /** 整体上传（`POST /upload`）的请求体上限 */
  maxUploadBytes: number;
  /** 设备端是否支持分片上传（三端点 + 续传） */
  supportsChunked: boolean;
  /**
   * 分片大小。必须使用设备端返回的值切片，
   * 自行指定会使 `received / chunk_size` 推导的下一片序号错位，续传拼出损坏文件。
   */
  chunkSize: number;
  /** 分片上传的单文件上限 */
  maxChunkedBytes: number;
  /** 上传会话的无活动超时（秒），用于判断本地续传线索是否还有效 */
  sessionTtlSeconds: number;
}

/** 探测失败或老固件时的能力位：只走整体上传，上限使用保守回退值。 */
export function fallbackUploadCaps(): UploadCaps {
  return {
    maxUploadBytes: UPLOAD_MAX_BYTES_FALLBACK,
    supportsChunked: false,
    chunkSize: 0,
    maxChunkedBytes: UPLOAD_MAX_BYTES_FALLBACK,
    sessionTtlSeconds: 0,
  };
}

/**
 * 读取设备端的上传能力位。任何异常都回退到 [fallbackUploadCaps]（不抛出）：
 * 探测失败不应使上传功能整体不可用，降级为整体上传仍可传输小文件。
 */
export async function fetchUploadCaps(api: { get: (u: string) => Promise<{ data?: any }> }): Promise<UploadCaps> {
  try {
    const { data } = await api.get('/api/files/status');
    const max = Number(data?.max_upload_bytes);
    const chunk = Number(data?.chunk_size);
    const maxChunked = Number(data?.max_chunked_upload_bytes);
    return {
      maxUploadBytes: Number.isFinite(max) && max > 0 ? max : UPLOAD_MAX_BYTES_FALLBACK,
      supportsChunked: data?.supports_chunked_upload === true && Number.isFinite(chunk) && chunk > 0,
      chunkSize: Number.isFinite(chunk) && chunk > 0 ? chunk : 0,
      maxChunkedBytes: Number.isFinite(maxChunked) && maxChunked > 0 ? maxChunked : UPLOAD_MAX_BYTES_FALLBACK,
      sessionTtlSeconds: Number(data?.upload_session_ttl_seconds) || 0,
    };
  } catch {
    return fallbackUploadCaps();
  }
}

/** 单文件实际可传的上限：支持分片时取分片上限，否则取整体上限。 */
export function effectiveUploadLimit(caps: UploadCaps): number {
  return caps.supportsChunked ? caps.maxChunkedBytes : caps.maxUploadBytes;
}

/** 上限的人类可读写法，用于提示文案。 */
export function uploadLimitLabel(bytes: number): string {
  if (bytes >= 1024 * 1024 * 1024)
    return `${(bytes / 1024 / 1024 / 1024).toFixed(bytes % (1024 * 1024 * 1024) === 0 ? 0 : 1)} GB`;
  return `${Math.round(bytes / 1024 / 1024)} MB`;
}

// ── 搜索范围 ──
/**
 * 三档搜索范围。深度直接映射 core `/search` 的 `depth` 参数（不是客户端逐目录遍历）：
 * core 侧为 `root.walkTopDown().maxDepth(depth)`，root 自身算第 0 层，
 * 因此 depth=1 恰好等于「只看当前目录的直接子项」。
 *
 * 上限为 core 的 `MAX_SEARCH_DEPTH = 8`，超出会被夹取，因此「整个存储」使用 8。
 * 另有两个 core 侧硬限制需在 UI 中如实告知：
 * 单次最多 `MAX_SEARCH_RESULTS = 50` 条；墙钟超时 `SEARCH_TIMEOUT_MS = 10s`（超时返回已找到的部分）。
 */
export type SearchScope = 'dir' | 'sub' | 'volume';

export const SEARCH_SCOPES: { key: SearchScope; label: string; depth: number; hint: string }[] = [
  { key: 'dir', label: '当前目录', depth: 1, hint: '只看本层' },
  { key: 'sub', label: '含子目录', depth: 3, hint: '向下 3 层' },
  { key: 'volume', label: '整个存储', depth: 8, hint: '全卷，最多 50 条' },
];

/** core `/search` 的两条硬限制，UI 需如实提示。 */
export const SEARCH_MAX_RESULTS = 50;
export const SEARCH_MAX_DEPTH = 8;

/**
 * 搜索起点：按范围取当前目录或当前卷根。
 * core 的 `/search?path=` 同样经过 `safeResolve`，起点不合法会被 400 拒绝，
 * 因此这里必须提供卷根而不是 `/`。
 */
export function searchRootFor(scope: SearchScope, currentPath: string): string {
  return scope === 'volume' ? volumeRootOf(currentPath) : normalizePath(currentPath);
}

/**
 * 裸 fetch 的鉴权头：Bearer + 设备签名。
 *
 * 下载、预览与上传绕过 axios，拿不到 useApi 的签名拦截器；core 的
 * AuthMiddleware 对所有 /api 请求强制验签，缺少这三个头会返回 444。
 * 签名 URI 必须是服务端看到的 path+query，因此传相对路径而不是拼好的绝对 URL。
 *
 * @param method 必须与真实发出的请求方法一致：签名摘要第一段为 METHOD 大写，
 *   用 GET 的签名发送 POST 会被判为签名无效。
 */
export async function authHeaders(uri: string, method = 'GET'): Promise<Record<string, string>> {
  const appStore = useAppStore();
  const signed = await (await loadDeviceIdentity()).signRequest(method, uri);
  return appStore.token ? { Authorization: `Bearer ${appStore.token}`, ...signed } : { ...signed };
}

// ── 成败判定 ──
/**
 * core 的写操作普遍返回 HTTP 200 + `{ success: false, error }`，只看 HTTP 状态码会把
 * 「目标已存在」「路径非法」这类失败当成成功。
 *
 * 本函数是文件域唯一的判定依据，取代了同一页面内并存的三套写法：
 *   · `data?.success === false`：正确，但仅用于一处；
 *   · `data?.success !== false`：正确，但丢弃了 `error` 原文，逐项面板只能显示「操作失败」；
 *   · `!data?.success`：错误，响应缺字段时会把成功判为失败。
 *
 * @param fallback 响应中没有可读原因时的兜底文案
 * @param onError  提示出口（页面注入 `message.error`）。不传则仅返回 false，
 *                 由调用方决定如何提示或将原因带入队列
 * @returns true = 成功
 */
export function assertOk(data: any, fallback: string, onError?: (msg: string) => void): boolean {
  if (data?.success === false) {
    onError?.(errorOf(data, fallback));
    return false;
  }
  return true;
}

/** core 失败信封中的可读原因：`error` 优先，其次 `message`，均不存在时使用兜底文案。 */
export function errorOf(data: any, fallback: string): string {
  const msg = data?.error || data?.message;
  return typeof msg === 'string' && msg.trim() ? msg : fallback;
}

// ── 存储源能力列表文案 ──
/**
 * `capabilities` 的展示文本。
 *
 * core 正常返回数组；老固件可能缺字段，也可能返回字符串。
 * 调用方必须同时处理两种类型：对字符串调用 `.join` 会抛出 TypeError，导致整块列表渲染失败。
 */
export function capsTextOf(source: any): string {
  const caps = source?.capabilities;
  if (Array.isArray(caps)) {
    return caps.filter((c: unknown) => typeof c === 'string' && c).join(' · ');
  }
  if (typeof caps === 'string') return caps;
  return '';
}

// ── 进度行文案（上传面板与批量面板共用）──
/**
 * 两条队列的状态集合不同（上传多一个 `uploading`，批量多一个 `running`），但渲染规则必须一致：
 * 两处各自实现 `stateText` / `stateClass` 会导致文案不一致（「排队中 / 待处理」「完成 / 成功」）。
 * 此处合并为一份。`percent` 仅对进行中状态有意义（上传有字节进度，批量没有）。
 */
export type ProgressState = 'pending' | 'running' | 'uploading' | 'done' | 'error' | 'canceled';

export function progressStateText(status: ProgressState, percent?: number): string {
  switch (status) {
    case 'pending':
      return '等待中';
    case 'running':
    case 'uploading':
      return percent == null ? '进行中' : `${percent}%`;
    case 'done':
      return '完成';
    case 'canceled':
      return '已取消';
    default:
      return '失败';
  }
}

/** 进度行状态样式（对应 `.up-state` / `.bp-state` 的 is-* 三档，两个文件同名同值）。 */
export function progressStateClass(status: ProgressState): string {
  if (status === 'error') return 'is-error';
  if (status === 'done') return 'is-done';
  if (status === 'canceled') return 'is-muted';
  return '';
}
