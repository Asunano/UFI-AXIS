/**
 * 通用工具函数
 */

import type { WifiSettings, WifiClient } from '@/types';

/**
 * 把 `GET /api/wifi/settings` 的响应归一化成前端的 WifiSettings。
 *
 * core 输出的 key 是 goform 扁平命名（`wifi_chip1_ssid1_*`），**已经是唯一值** ——
 * 固件差异（`SSID`/`AuthMode` 驼峰名、`wifi_enable`/`wifi_onoff_state` 同义开关）
 * 全部在 core 的设备适配层归一掉了（阶段 4.1 起响应里只有 canonical），
 * 所以这里不再做多别名探测。字段清单见 `api/contract.ts` 的 `deviceFields.wifiSettings`。
 */
export function normalizeWifiSettings(d: any): WifiSettings {
  const bd = d?.wifi_chip1_ssid1_broadcast_ssid;
  return {
    ssid: d?.wifi_chip1_ssid1_ssid ?? '',
    auth_mode: d?.wifi_chip1_ssid1_auth_mode ?? '',
    encryp_type: d?.wifi_chip1_ssid1_encryp_type ?? '',
    password: d?.wifi_chip1_ssid1_passphrase ?? '',
    chip_index: d?.wifi_chip === 'chip2' ? '2' : '1',
    max_sta_num: Number(d?.wifi_chip1_ssid1_max_sta_num) || 0,
    // goform 给的是「广播被禁用」，1 = 隐藏 SSID
    broadcast_disabled: bd === '1' || bd === 1 || bd === true ? 1 : 0,
    enabled: d?.WiFiModuleSwitch === '1',
  };
}

/** `GET /api/device/lan-settings` 归一化后的视图模型（两个页面共用）。 */
export interface LanSettingsView {
  lan_ip: string;
  lan_netmask: string;
  mac_address: string;
  dhcp_enabled: boolean;
  dhcp_start: string;
  dhcp_end: string;
  /** 租约秒数。`dhcpLease`（秒）优先，其次 `dhcpLease_hour`（小时）× 3600；都没有给 0 */
  dhcp_lease_sec: number;
  mtu: number | null;
}

/**
 * 把 `GET /api/device/lan-settings` 的响应归一化成视图模型。
 *
 * 原来 `DeviceView.vue` 与 `DashboardView.vue` 各抄了一份（计划书 13.2.1），收敛到这里。
 * 字段清单见 `api/contract.ts` 的 `deviceFields.lanSettings`；core 已保证每个语义只有一个 key，
 * 所以这里不做别名探测。**注意 `dhcpLease` 与 `dhcpLease_hour` 不是别名**：一个是秒、一个是
 * 小时，部分固件只填后者。
 *
 * 写回去用 `POST /api/device/dhcp`，那边的参数名是另一套（`lan_ip` / `dhcp_type` / `dhcp_lease`），
 * 不能把读到的对象原样 POST。
 */
export function normalizeLanSettings(d: any): LanSettingsView {
  const str = (k: string) => {
    const v = d?.[k];
    return v === undefined || v === null ? '' : String(v);
  };
  return {
    lan_ip: str('lan_ipaddr'),
    lan_netmask: str('lan_netmask'),
    mac_address: str('mac_address'),
    // core 已把 "1"/"true"/"SERVER" 三种编码归一成 "1"/"0"
    dhcp_enabled: str('dhcpEnabled') === '1',
    dhcp_start: str('dhcpStart'),
    dhcp_end: str('dhcpEnd'),
    dhcp_lease_sec: Number(str('dhcpLease')) || Number(str('dhcpLease_hour')) * 3600 || 0,
    mtu: Number(str('mtu')) || null,
  };
}

/** 容器字段取数组：真数组直接用，"数组的 JSON 字符串"（双重编码）解一层。 */
function asArray(v: any): any[] {
  if (Array.isArray(v)) return v;
  if (typeof v === 'string' && v.trimStart().startsWith('[')) {
    try {
      const parsed = JSON.parse(v);
      return Array.isArray(parsed) ? parsed : [];
    } catch {
      return [];
    }
  }
  return [];
}

/**
 * 把 `GET /api/wifi/clients` 的响应归一化成客户端列表。
 *
 * 原来 `NetworkView.vue` 与 `DashboardView.vue` 各抄了一份（计划书 13.2.2），收敛到这里。
 *
 * 两件事是契约、不能省：
 * ① `station_list`（WiFi 侧）与 `lan_station_list`（LAN 侧）**并列**，要合并 —— app 端一直是
 *    合并展示的，只读 `station_list` 会比 app 少显示有线客户端；同一个 mac 出现在两边时去重。
 * ② 容器值可能是数组，也可能是数组的 JSON 字符串（关掉 core 的 `field_normalization_enabled`
 *    排障时就是后者），两种形态都要能解。
 */
export function normalizeWifiClients(d: any): WifiClient[] {
  const rows = [...asArray(d?.station_list), ...asArray(d?.lan_station_list)];
  const out: WifiClient[] = [];
  const seen = new Set<string>();
  for (const c of rows) {
    if (!c || typeof c !== 'object') continue;
    const mac = String(c.mac_addr ?? '');
    const key = mac.toLowerCase();
    if (key && seen.has(key)) continue;
    if (key) seen.add(key);
    out.push({ ...c, hostname: String(c.hostname ?? ''), ip_addr: String(c.ip_addr ?? ''), mac });
  }
  return out;
}

/** 格式化字节数 */
export function formatBytes(bytes: number): string {
  if (bytes >= 1_073_741_824) return `${(bytes / 1_073_741_824).toFixed(2)} GB`;
  if (bytes >= 1_048_576) return `${(bytes / 1_048_576).toFixed(1)} MB`;
  if (bytes >= 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${bytes} B`;
}

/** 格式化速度 */
export function formatSpeed(bytesPerSec: number): string {
  if (bytesPerSec >= 1_048_576) return `${(bytesPerSec / 1_048_576).toFixed(1)} MB/s`;
  if (bytesPerSec >= 1024) return `${(bytesPerSec / 1024).toFixed(1)} KB/s`;
  return `${bytesPerSec} B/s`;
}

/** 格式化运行时间 */
export function formatUptime(seconds: number): string {
  const d = Math.floor(seconds / 86400);
  const h = Math.floor((seconds % 86400) / 3600);
  const m = Math.floor((seconds % 3600) / 60);
  if (d > 0) return `${d}天 ${h}小时 ${m}分`;
  if (h > 0) return `${h}小时 ${m}分`;
  return `${m}分钟`;
}

/** 信号强度等级 */
export function signalLevel(rsrp: number): 'excellent' | 'good' | 'fair' | 'poor' {
  if (rsrp >= -80) return 'excellent';
  if (rsrp >= -90) return 'good';
  if (rsrp >= -105) return 'fair';
  return 'poor';
}

/** 信号强度颜色 */
export function signalColor(rsrp: number): string {
  switch (signalLevel(rsrp)) {
    case 'excellent':
      return '#18a058';
    case 'good':
      return '#2080f0';
    case 'fair':
      return '#f0a020';
    case 'poor':
      return '#d03050';
  }
}

/** 网络类型显示名 */
export function networkTypeLabel(type: string): string {
  const map: Record<string, string> = {
    '5G': '5G',
    NR: '5G',
    LTE: '4G',
    WCDMA: '3G',
    HSPA: '3G+',
    HSUPA: '3G+',
    HSDPA: '3G+',
    EDGE: '2G',
    GSM: '2G',
  };
  return map[type?.toUpperCase()] || type || '无信号';
}

/**
 * 网络类型 + 当前注册频段，如 `5G n78` / `4G B3`。
 *
 * 频段直接读 `/api/network/signal`（及同源 WS signal 推送）的 **`band_label`** ——
 * 它是 core 派生的服务小区统一字段，前缀已经拼好（`"n78"` / `"B3"`）。
 *
 * 2026-08-29 修：原来自己按 `networkTypeLabel(type)` 分制式，5G 取 `nr_band` 拼 `N`、
 * 否则取 `lte_band` 拼 `B`。两个问题：① 拼前缀的规则在 core 与 web 各有一份，会漂移
 * （core 用小写 `n`）；② NSA 双连接时 `network_type` 是 `"NSA"`，`networkTypeLabel` 不认，
 * 于是走进 LTE 分支贴上 4G 频段，而 core 的 `ServingCell.derive` 明确按字段存在性取 NR。
 * `band_label` 缺失（两侧都没值）就只显示制式，不编造。
 */
export function networkTypeWithBand(type: string, signal?: Record<string, any> | null): string {
  const label = networkTypeLabel(type);
  const band = signal?.band_label ? String(signal.band_label) : '';
  return band ? `${label} ${band}` : label;
}

/** 电池状态图标 */
export function batteryStatusText(status: string): string {
  const map: Record<string, string> = {
    CHARGING: '充电中',
    FULL: '已充满',
    DISCHARGING: '放电中',
    NOT_CHARGING: '未充电',
  };
  return map[status] || status;
}

/** 安全取值 */
export function get(obj: any, path: string, fallback: any = null): any {
  return path.split('.').reduce((o, k) => (o && o[k] !== undefined ? o[k] : fallback), obj);
}

/**
 * 从未知异常中提取可读的错误信息（替代 `catch (e: any)` 中的 `e?.response?.data?.error`）。
 *
 * axios 抛出的错误是 `unknown`，必须收窄后才能读 `response`；本函数按 axios 信封结构判断，
 * 非网络错误（`Error`）回退到 `message`，其余归到 fallback。调用方传入与场景对应的中文兜底文案。
 */
export function toApiErrorMessage(e: unknown, fallback = '操作失败'): string {
  if (e && typeof e === 'object') {
    const resp = (e as { response?: unknown }).response;
    if (resp && typeof resp === 'object') {
      const err = (resp as { data?: unknown }).data;
      if (err && typeof err === 'object' && typeof (err as { error?: unknown }).error === 'string') {
        return (err as { error: string }).error;
      }
    }
    if (e instanceof Error && e.message) return e.message;
  }
  return fallback;
}

/**
 * 复制文本到剪贴板。
 * navigator.clipboard 只在安全上下文（https / localhost）下存在，本面板通常通过
 * http://<局域网IP>:8088 访问，此时它是 undefined，直接调用会抛 TypeError。
 * 因此先做特性检测，失败再退回 textarea + execCommand('copy')。
 */
export async function copyToClipboard(text: string): Promise<boolean> {
  if (navigator.clipboard?.writeText) {
    try {
      await navigator.clipboard.writeText(text);
      return true;
    } catch {
      /* 权限被拒或 WebView 限制，继续走兜底方案 */
    }
  }
  try {
    const ta = document.createElement('textarea');
    ta.value = text;
    // 不能用 display:none / visibility:hidden，否则 select() 无效
    ta.setAttribute('readonly', '');
    ta.style.position = 'fixed';
    ta.style.top = '-9999px';
    ta.style.opacity = '0';
    document.body.appendChild(ta);
    ta.select();
    ta.setSelectionRange(0, text.length);
    const ok = document.execCommand('copy');
    document.body.removeChild(ta);
    return ok;
  } catch {
    return false;
  }
}

/**
 * 判断主机名是否属于私有/本地地址（局域网、回环、链路本地、IPv6 ULA、mDNS）。
 * 用于区分"局域网内明文访问"（可接受）与"公网明文访问"（有窃听风险）。
 */
export function isPrivateHost(hostname: string): boolean {
  const host = hostname.replace(/^\[|\]$/g, '').toLowerCase();
  if (!host) return true;
  if (host === 'localhost' || host.endsWith('.localhost')) return true;
  // mDNS / 内网自定义域
  if (host.endsWith('.local') || host.endsWith('.lan') || host.endsWith('.internal')) return true;
  // Tailscale MagicDNS：流量本身走 WireGuard 加密，明文 http 也不暴露在公网
  if (host.endsWith('.ts.net')) return true;

  // IPv6
  if (host.includes(':')) {
    if (host === '::1') return true;
    if (/^f[cd][0-9a-f]{2}:/.test(host)) return true; // fc00::/7 ULA
    if (/^fe[89ab][0-9a-f]:/.test(host)) return true; // fe80::/10 链路本地
    return false;
  }
  const m = host.match(/^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$/);
  // 不是 IP 字面量（是域名）就当作公网
  if (!m) return false;
  const [a, b] = [Number(m[1]), Number(m[2])];
  if (a === 127 || a === 10) return true;
  if (a === 172 && b >= 16 && b <= 31) return true;
  if (a === 192 && b === 168) return true;
  if (a === 169 && b === 254) return true; // 链路本地
  if (a === 100 && b >= 64 && b <= 127) return true; // CGNAT（Tailscale 等）
  return false;
}

/** 单个端点是否为"明文 HTTP + 非私有主机"。 */
function isInsecureEndpoint(protocol: string, hostname: string): boolean {
  return protocol === 'http:' && !isPrivateHost(hostname);
}

/**
 * 是否处于不安全访问状态：页面本身或 API 端点任一走明文 HTTP 且主机非私有。
 *
 * 只看浏览器实际使用的协议，所以内网穿透不会误判：
 * - Cloudflare 隧道对外一律 https，`location.protocol` 就是 `https:` → 不提示；
 * - 反向代理/隧道在边缘卸载 TLS 时浏览器侧同样是 https → 不提示（浏览器这一段确实是加密的）；
 * - FRP 的 vhost_http 之类明文暴露到公网 → 提示，这不是误报。
 *
 * 跨域模式下页面与 API 可以不同源（`appStore.baseUrl`），短信/验证码实际走的是 API
 * 那一段，所以两段都要看。
 */
export function isInsecurePublicAccess(apiBaseUrl?: string): boolean {
  if (typeof window === 'undefined') return false;
  const { protocol, hostname } = window.location;
  if (isInsecureEndpoint(protocol, hostname)) return true;
  if (apiBaseUrl) {
    try {
      const u = new URL(apiBaseUrl, window.location.href);
      if (isInsecureEndpoint(u.protocol, u.hostname)) return true;
    } catch {
      /* 地址非法就不判断，避免误报 */
    }
  }
  return false;
}

// ── HTTP 明文提示的"一天内不再提示" ──
// 只在用户主动点关闭时写入；自动消失不算，避免用户没看见就被静音一天。
const LS_INSECURE_NOTICE_DISMISSED = 'ufi-insecureNoticeDismissedAt';
const INSECURE_NOTICE_MUTE_MS = 86_400_000;

export function isInsecureNoticeMuted(): boolean {
  const at = Number(localStorage.getItem(LS_INSECURE_NOTICE_DISMISSED) ?? '');
  if (!Number.isFinite(at) || at <= 0) return false;
  const elapsed = Date.now() - at;
  // 时间被往前调过（elapsed < 0）时按未静音处理，否则可能永久哑掉
  return elapsed >= 0 && elapsed < INSECURE_NOTICE_MUTE_MS;
}

export function muteInsecureNoticeForADay(): void {
  localStorage.setItem(LS_INSECURE_NOTICE_DISMISSED, String(Date.now()));
}

/** 退出登录时调用：重新登录属于新会话，安全提示要重新出现。 */
export function resetInsecureNoticeMute(): void {
  localStorage.removeItem(LS_INSECURE_NOTICE_DISMISSED);
}

/** 密码强度评估（0-4 分）。 */
export interface PasswordStrength {
  score: number;
  label: string;
  color: string;
}

export function evaluatePasswordStrength(pw: string): PasswordStrength {
  if (!pw) return { score: 0, label: '空', color: '#d03050' };
  let score = 0;
  if (pw.length >= 8) score++;
  if (/[a-z]/.test(pw) && /[A-Z]/.test(pw)) score++;
  if (/\d/.test(pw)) score++;
  if (/[^A-Za-z0-9]/.test(pw)) score++;
  const labels = ['弱', '弱', '中', '强', '很强'];
  const colors = ['#d03050', '#d03050', '#f0a020', '#18a058', '#18a058'];
  const idx = Math.min(score, 4);
  return { score: idx, label: labels[idx] ?? '弱', color: colors[idx] ?? '#d03050' };
}
