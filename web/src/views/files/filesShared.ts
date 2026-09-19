/**
 * 文件页的共享类型与纯函数。
 *
 * 拆出来的理由：图标/元信息/磁盘百分比这些格式化被列表面板用，路径白名单被工具栏与
 * 导航用，预览类型判定被列表（决定要不要显示「预览」按钮）与预览弹窗（决定用
 * img/video/audio）同时用。留在 SFC 里，拆完就得复制。
 */
import { useAppStore } from '@/stores/app';
import { loadDeviceIdentity } from '@/composables/deviceIdentityLazy';
import { formatBytes } from '@/composables/utils';

/**
 * core /api/files/list 的条目。
 * 索引签名保留：core 各端点返回的字段并不一致（`/search` 只有 name/path/isDirectory，
 * `/info` 多出 permissions/owner），写死字段集会在别处报类型错。
 */
export interface FileEntry {
  name: string;
  path: string;
  isDirectory: boolean;
  size?: number;
  lastModified?: number;
  [key: string]: any;
}

/** 内部存储（primary volume）的真实路径 */
export const PRIMARY_STORAGE = '/storage/emulated/0';

/** `/sdcard` 是 primary 的别名，展示与导航统一映射到 [PRIMARY_STORAGE] */
export const SDCARD_ALIAS = '/sdcard';

/** 把 `/sdcard` 别名归一到 `/storage/emulated/0`，避免同目录双路径。 */
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
 * 是否允许导航到该路径（与 core `isUserStoragePath` 对齐，且更严一层）。
 *
 * 刻意禁止：
 * - `/storage`、`/storage/emulated` —— 中间层目录，进去只会看到系统占位/错误结构；
 * - `/sdcard` 别名本身（归一后等于 PRIMARY，可进）；
 * - 再往上的 `/`、`/data` 等。
 */
export function isAllowedRoot(p: string): boolean {
  const n = normalizePath(p);
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
 * 面包屑。主存储根只显示「内部存储」，不再暴露 storage/emulated/0。
 * 其它卷仍显示真实目录名。
 */
export function breadcrumbSegments(path: string): Crumb[] {
  const n = normalizePath(path);
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

/** 去掉尾部斜杠（`/a/b/` → `/a/b`；根 `/` 保持 `/`）。 */
export function trimTrailingSlash(p: string): string {
  const t = p.replace(/\/+$/, '');
  return t === '' ? '/' : t;
}

/**
 * 当前路径所属**卷**的根：内部存储 → `/storage/emulated/0`；SD 卡 → `/storage/XXXX-XXXX`。
 *
 * 搜索的「整个存储」档要以卷根为起点（core 的 `/search` 是 `root.walkTopDown()`），
 * 用 `/` 或 `/storage` 作起点会被 core 的 `safeResolve` 拒绝（不在用户存储白名单内）。
 */
export function volumeRootOf(path: string): string {
  const n = normalizePath(path);
  if (isPrimaryStoragePath(n)) return PRIMARY_STORAGE;
  const m = n.match(/^(\/storage\/[^/]+|\/mnt\/media_rw\/[^/]+)/);
  return m?.[1] ?? PRIMARY_STORAGE;
}

/** 路径的父目录（与 core `parentOf` 同口径：末段去掉；已到根则返回自身）。 */
export function parentPathOf(path: string): string {
  const p = trimTrailingSlash(path);
  const idx = p.lastIndexOf('/');
  if (idx < 0) return p;
  return idx === 0 ? '/' : p.slice(0, idx);
}

/** 路径末段（显示名）。 */
export function baseNameOf(path: string): string {
  const p = trimTrailingSlash(path);
  const idx = p.lastIndexOf('/');
  return idx < 0 ? p : p.slice(idx + 1);
}

/**
 * 隐藏文件（以 `.` 开头）。
 *
 * core `/files/list` 不过滤隐藏项，开关只能做在客户端 —— 所以「显示隐藏文件」
 * 必须作用在**列表渲染**这一层，而不是重新请求。
 */
export function isHiddenName(name: string): boolean {
  return name.startsWith('.') && name !== '.' && name !== '..';
}

/**
 * 在目标目录里挑一个不冲突的名字：`a.txt` → `a (2).txt`。
 *
 * 必须做在客户端：core 的 `/copy` 用 `REPLACE_EXISTING` 静默覆盖，
 * `/move` 遇到已存在直接返回 `success:false`（"目标已存在"）——
 * 两者都不改名，直接粘贴就会「要么丢数据、要么失败」。
 *
 * @param existing 目标目录**当前已有**的名字集合（粘贴过程中新增的也要并入）
 */
export function uniqueChildName(existing: Iterable<string>, name: string): string {
  const taken = new Set(existing);
  if (!taken.has(name)) return name;
  // 隐藏文件 `.gitignore` 这类「点在第 0 位」不该被当成扩展名分隔符
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
 * 搜索结果**所在目录**相对当前目录的展示文案 —— 多级目录下「这个结果到底在哪」的答案。
 *
 * 三档刻意分开表述，因为同名文件在不同层级很常见，只说文件名等于没说：
 *   · 就在当前目录        → 「当前目录」
 *   · 在当前目录之下      → 相对路径（`Backup` / `Archive/2024`）
 *   · 在当前目录之上      → `↑ DCIM`（指向上级分支，避免误读成子目录）
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
  return new Date(ts).toLocaleString('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' });
}

/** core diskMap 返回的全是字符串（size/used/available 已格式化，usePercent 形如 "57%"） */
export function diskPercent(d: any): number {
  const raw = String(d?.usePercent ?? '0').replace('%', '');
  const n = Number(raw);
  return isNaN(n) ? 0 : Math.min(100, Math.max(0, Math.round(n)));
}

/** /api/files/search 只返回 name/path/isDirectory，没有 size/lastModified */
export function fileMeta(f: FileEntry): string {
  if (f.isDirectory) return '文件夹';
  if (f.size == null || f.lastModified == null) return '文件';
  return `${formatBytes(f.size)} · ${formatDate(f.lastModified)}`;
}

/** 文件类别短名，对应 SVG 图标（禁止 emoji）。 */
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
 * 在线文本编辑/打开的扩展名。
 * 与 app `FileKind` 的 TEXT 档对齐（svg 单独走图片预览，不在此表）。
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
// /api/files/stream 需要鉴权头，不能直接给 <img>/<video> src，
// 必须 fetch 成 blob 后转 object URL，并在关闭/卸载时 revoke 防止泄漏。
/** 整文件读入内存，超过这个大小直接劝退，避免浏览器内存爆掉 */
export const PREVIEW_MAX_BYTES = 100 * 1024 * 1024;

/**
 * 预览后缀。以 app `FileKind` 为基准，再按**浏览器**实际能播/能解码做过滤：
 * - heic/heif：多数桌面浏览器无法解码，不进表（进表只会弹破图）
 * - flv/avi/3gp：Chromium 系基本不支持，进表会变成空播放器
 * - svg：浏览器可直接当图片显示，保留预览入口
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
 * 刻意**没有** `extract`：压缩包双击只开详情弹窗。解压会改设备文件系统，
 * 双击这种一碰就触发、又没有确认的手势不该直接执行 —— 与 app 侧
 * `FileManagerRoot.resolveOpenRoute` 同一条口径（那边 2026-09-15 已改）。
 */
export type OpenAction = 'preview' | 'text' | 'install' | 'info' | 'none';

/** 归档类型：可解压的（zip/tar/tar.gz/单文件 gz）返回具体类型，rar/7z 等返回 null。 */
export function archiveKindOf(name: string): 'zip' | 'tgz' | 'tar' | 'gz' | null {
  const lower = name.toLowerCase();
  if (lower.endsWith('.zip')) return 'zip';
  if (lower.endsWith('.tar.gz') || lower.endsWith('.tgz')) return 'tgz';
  if (lower.endsWith('.tar')) return 'tar';
  if (lower.endsWith('.gz')) return 'gz';
  return null;
}

/** 该文件能否被「解压」（rar/7z 需原生库，当前不支持，故为 false）。 */
export function canExtract(f: FileEntry): boolean {
  return !f.isDirectory && archiveKindOf(f.name) != null;
}

export function openActionOf(f: FileEntry): OpenAction {
  if (f.isDirectory) return 'none';
  if (canPreview(f)) return 'preview';
  if (isTextEditable(f)) return 'text';
  if (extOf(f.name) === 'apk') return 'install';
  // 压缩包 / 文档 / 未知扩展名都落这里：详情弹窗里再由主操作按钮触发解压
  return 'info';
}

/** 「打开」这个动作对该类型的说法，用于菜单首项与详情弹窗主按钮（与 app `openActionLabel` 对齐） */
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

/** 详情弹窗右侧的唯一主操作。`null` = 这个类型没有成立的主操作，弹窗退化成单个「关闭」。 */
export interface PrimaryFileAction {
  /** 与 `FilesView.handleAction` 的 key 同一套 */
  action: string;
  label: string;
}

/**
 * 主操作判定。分支顺序照抄 app 的 `primaryFileAction`：
 * `canExtract` 在 apk 之前（按文件名后缀判），apk 不给 `open`（那只会把本弹窗再开一次）。
 *
 * 不可解压的 rar/7z、pdf 这类文档、未知扩展名一律返回 null：
 * 宁可少一个按钮，也不放一个点了只会重新挂起同一个弹窗的假按钮。
 */
export function primaryFileActionOf(f: FileEntry): PrimaryFileAction | null {
  if (f.isDirectory) return { action: 'open', label: '进入' };
  if (canExtract(f)) return { action: 'extract', label: '解压' };
  if (extOf(f.name) === 'apk') return { action: 'install', label: '安装 APK' };
  if (canPreview(f) || isTextEditable(f)) return { action: 'open', label: openActionLabelOf(f) };
  return null;
}

// ── 搜索范围 ──
/**
 * 三档搜索范围。**深度直接映射 core `/search` 的 `depth` 参数**（不是客户端翻目录）：
 * core 侧 `root.walkTopDown().maxDepth(depth)`，root 自身算第 0 层，所以
 * depth=1 恰好等于「只看当前目录的直接子项」。
 *
 * 上限是 core 的 `MAX_SEARCH_DEPTH = 8`，超出会被夹取 —— 所以「整个存储」用 8，
 * 而不是想当然的 99。另有两个 core 侧硬限制要如实告知用户：
 * 单次最多 `MAX_SEARCH_RESULTS = 50` 条、墙钟 `SEARCH_TIMEOUT_MS = 10s`（超时返回已找到的部分）。
 */
export type SearchScope = 'dir' | 'sub' | 'volume';

export const SEARCH_SCOPES: { key: SearchScope; label: string; depth: number; hint: string }[] = [
  { key: 'dir', label: '当前目录', depth: 1, hint: '只看本层' },
  { key: 'sub', label: '含子目录', depth: 3, hint: '向下 3 层' },
  { key: 'volume', label: '整个存储', depth: 8, hint: '全卷，最多 50 条' },
];

/** core `/search` 的两条硬限制，UI 需要如实提示而不是假装没有。 */
export const SEARCH_MAX_RESULTS = 50;
export const SEARCH_MAX_DEPTH = 8;

/**
 * 搜索起点：按范围取「当前目录」或「当前卷根」。
 * 注意 core 的 `/search?path=` 也会 `safeResolve`，起点不合法会被 400 拒掉，
 * 所以这里必须给卷根而不是 `/`。
 */
export function searchRootFor(scope: SearchScope, currentPath: string): string {
  return scope === 'volume' ? volumeRootOf(currentPath) : normalizePath(currentPath);
}

/**
 * 裸 fetch 的鉴权头：Bearer + 设备签名。
 *
 * 下载、预览与上传绕过了 axios，所以拿不到 useApi 里的签名拦截器；core 的
 * AuthMiddleware 对**所有** /api 请求强制验签，少这三个头就是 444。
 * 签名的 URI 必须是服务端看到的 path+query，因此传相对路径而不是拼好的绝对 URL。
 *
 * @param method 必须与真实发出的请求方法一致：签名摘要的第一段就是 METHOD 大写，
 *   用 GET 的签名去发 POST 会被判为签名无效（这条在文件上传上踩过 —— 上传原来走
 *   n-upload 的原生 XHR，只能手写静态头，而签名必须逐请求现算，结果恒失败）。
 */
export async function authHeaders(uri: string, method = 'GET'): Promise<Record<string, string>> {
  const appStore = useAppStore();
  const signed = await (await loadDeviceIdentity()).signRequest(method, uri);
  return appStore.token ? { Authorization: `Bearer ${appStore.token}`, ...signed } : { ...signed };
}
