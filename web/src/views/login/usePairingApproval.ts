/**
 * Web 端「跨设备批准」流程（2026-10-15）。
 *
 * ## 它解决什么
 * 默认情况下，任何能进局域网的人拿到配对密码就能配一台新设备进来。机主打开
 * 「新设备需在 App 上批准」之后，网页端第一次配对会拿到 `403 APPROVAL_REQUIRED` ——
 * 这时必须走本流程：登记一次请求，等机主在已配对设备上点允许（或在网页上输入
 * 从外部通知里读到的 6 位配对码）。
 *
 * ## 为什么用 `sid`
 * `sid` 是**会话随机数**（存 `sessionStorage`，不是凭据），只做两件事：
 * 1. 把"这一次请求"与"服务端那条待批准记录"对上；
 * 2. 作为限速键（同一 sid 重复请求是幂等的，服务端不会新建记录、也不会重复通知）。
 *
 * 它由本端生成、服务端不采信它的"身份"含义 —— 所以它**不能**用来批准任何东西：
 * 配对码只发给已配对设备与机主配置的外部通知渠道，请求方永远拿不到。
 *
 * ## 只在需要时才发起
 * 本流程**只在 confirm 回 403 APPROVAL_REQUIRED 之后**才启动。不能"每次登录都先请求一次"：
 * 那样即使机主把批准关着，每次网页登录也会凭空造出一条待批准记录、把 App 弹窗/通知叫醒。
 */

/** 待批准状态（与服务端 `PairedDeviceApprovalStore.STATUS_*` 同一套字符串）。 */
export type ApprovalStatus = 'idle' | 'pending' | 'approved' | 'rejected' | 'expired' | 'unsupported';

const SID_KEY = 'ufi-axis-pairing-sid';

/** 取（或生成）本次会话的随机数。会话级：关掉标签页就作废，重开是新的对话。 */
export function getOrCreateSid(): string {
  try {
    const existing = sessionStorage.getItem(SID_KEY);
    if (existing) return existing;
    const bytes = new Uint8Array(16);
    crypto.getRandomValues(bytes);
    const sid = Array.from(bytes, (b) => b.toString(16).padStart(2, '0')).join('');
    sessionStorage.setItem(SID_KEY, sid);
    return sid;
  } catch {
    // 隐私模式下 sessionStorage 可能不可用：退化成"本次调用内有效"的随机数，
    // 批准流程照常能走完（只是刷新页面后要重新登记一次）。
    const bytes = new Uint8Array(16);
    crypto.getRandomValues(bytes);
    return Array.from(bytes, (b) => b.toString(16).padStart(2, '0')).join('');
  }
}

/** 只读当前 sid（不生成）。用于"刷新后恢复等待态"。 */
export function peekSid(): string | null {
  try {
    return sessionStorage.getItem(SID_KEY);
  } catch {
    return null;
  }
}

/**
 * 丢掉当前 sid（2026-10-21，审计 §A2）。
 *
 * 拒绝/过期之后必须换一个新的：`sid` 是"这一次申请"的标识，复用它会让人分不清
 * "上一次被拒"和"这一次还在等"。丢掉之后用户再点登录就是一次全新的申请
 * （服务端也会为被拒绝的 sid 重建条目，两边口径一致）。
 */
export function clearSid(): void {
  try {
    sessionStorage.removeItem(SID_KEY);
  } catch {
    /* 隐私模式下没有 sessionStorage，忽略 */
  }
}

export interface RequestApprovalResult {
  ok: boolean;
  status: ApprovalStatus;
  /** 服务端给的过期秒数（展示用）。 */
  expiresIn: number;
  /**
   * 失败时的 HTTP 状态码（2026-10-21，审计 §B7）。
   *
   * 之前调用方只能看到 `status='expired'`，于是 429（申请过于频繁）与 400（参数不合法）
   * 都被说成"设备端版本过旧"，用户按提示重试永远撞墙。把码带出来，让文案分得清。
   */
  httpStatus?: number;
}

/**
 * 登记一次待批准请求。
 *
 * 注意响应里**没有**配对码 —— 这是设计：请求方拿不到码，就无法自己批准自己。
 */
export async function requestApproval(
  api: { post: (url: string, body?: unknown) => Promise<{ data?: any }> },
  sid: string,
  devicePubKey: string,
  deviceName: string
): Promise<RequestApprovalResult> {
  try {
    const { data } = await api.post('/pairing/request-approval', {
      sid,
      device_pubkey: devicePubKey,
      device_name: deviceName,
    });
    const status = (data?.status ?? 'pending') as ApprovalStatus;
    return { ok: true, status, expiresIn: Number(data?.expires_in ?? 0), httpStatus: 200 };
  } catch (e: any) {
    const httpStatus = Number(e?.response?.status ?? 0) || undefined;
    // 501 NOT_SUPPORTED = 设备端没启用批准（旧 core / 未装配）。
    // 这不是错误：调用方按"不需要批准"继续走原来的配对流程即可。
    if (httpStatus === 501) {
      return { ok: false, status: 'unsupported', expiresIn: 0, httpStatus };
    }
    return { ok: false, status: 'expired', expiresIn: 0, httpStatus };
  }
}

/**
 * 轮询一次状态（不含码 —— 状态接口只回一个字符串）。
 *
 * @return 状态；**网络/服务端错误返回 `null`**（而不是 `'expired'`）。
 *
 * 为什么区分：把任何失败都当成"过期"会让一次网络抖动直接把流程判死 ——
 * 2026-10-17 线上就是这么炸的（路由路径写错回 404，前端立刻显示"配对申请已过期"，
 * 而实际上服务端根本没收到请求）。失败时应当**继续等**，
 * 真正的过期由服务端的 `expired` 状态或 TTL 决定。
 */
export async function fetchApprovalStatus(
  api: { get: (url: string, config?: unknown) => Promise<{ data?: any }> },
  sid: string
): Promise<ApprovalStatus | null> {
  try {
    const { data } = await api.get('/pairing/approval-status', { params: { sid } });
    const status = data?.status;
    if (typeof status !== 'string') return null;
    return status as ApprovalStatus;
  } catch {
    return null;
  }
}
