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
  jpg: 'image', jpeg: 'image', png: 'image', gif: 'image', svg: 'image', webp: 'image', bmp: 'image',
  mp4: 'video', mkv: 'video', mov: 'video', m4v: 'video', webm: 'video',
  mp3: 'audio', wav: 'audio', flac: 'audio', ogg: 'audio', aac: 'audio', m4a: 'audio', opus: 'audio',
  pdf: 'doc', doc: 'doc', docx: 'doc', xls: 'doc', xlsx: 'doc',
  zip: 'archive', tar: 'archive', gz: 'archive', rar: 'archive', '7z': 'archive',
  apk: 'apk',
  txt: 'text', log: 'text', json: 'text', md: 'text', yaml: 'text', yml: 'text', toml: 'text',
  conf: 'text', cfg: 'text', ini: 'text', sh: 'text', py: 'text', js: 'text', ts: 'text',
  kt: 'text', java: 'text',
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
  'txt', 'log', 'md', 'csv', 'ini', 'conf', 'cfg', 'prop', 'properties',
  'json', 'xml', 'yaml', 'yml', 'toml',
  'html', 'htm', 'css', 'js', 'ts', 'kt', 'kts', 'java', 'gradle',
  'sh', 'bash', 'zsh', 'py', 'c', 'cpp', 'h', 'hpp', 'go', 'rs', 'sql', 'env',
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

/** 双击后的「打开」意图：媒体预览 → 文本编辑 → APK 安装 → 详情（不可在线打开） */
export type OpenAction = 'preview' | 'text' | 'install' | 'info' | 'none';

export function openActionOf(f: FileEntry): OpenAction {
  if (f.isDirectory) return 'none';
  if (canPreview(f)) return 'preview';
  if (isTextEditable(f)) return 'text';
  if (extOf(f.name) === 'apk') return 'install';
  return 'info';
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
