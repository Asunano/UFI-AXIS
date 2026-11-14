/**
 * 文件上传队列：分片、断点续传、进度与取消。
 *
 * ## 使用 XHR 而非 fetch 的原因
 * fetch 不提供上传进度（ReadableStream 上传在浏览器中支持不完整，且不提供 progress 事件），
 * `XMLHttpRequest.upload.onprogress` 是稳定获取已发送字节数的唯一途径。
 *
 * ## 不使用 `n-upload` 内建进度的原因
 * 内建实现自行发送 XHR，只能携带静态请求头；core 的 AuthMiddleware 对
 * `/api/files/upload*` 强校验 `X-Timestamp`/`X-Nonce`/`X-Signature`，签名摘要为
 * `METHOD\nURI\nTS\nNONCE`，时间戳与 nonce 必须每次重算，静态 header 无法满足
 * （表现为每次上传返回 444）。因此签名使用 `filesShared.authHeaders`（与下载、预览同一实现），
 * 请求由本模块自行发送。
 *
 * ## 两条上传路径
 * - 整体（`POST /upload`，multipart）：用于小文件或老固件。一次请求完成，少两次往返。
 * - 分片（`session` → `chunk`×N → `complete`）：用于大文件。中断后可续传，
 *   且服务端直接把分片追加到目标目录的 `.ufipart`，避免「先写内部存储临时文件再
 *   跨卷复制」造成的 2× 磁盘峰值。
 *
 * 走哪条由设备端下发的能力位决定（`GET /api/files/status`），不在前端硬编码。
 *
 * ## 断点续传的三个层级
 * - L1 同页面内：单片失败或网络抖动时重开会话，服务端按 name+size 匹配已有的 `.ufipart`
 *   并返回 `received`，从该分片继续传输，客户端无需记录任何状态。
 * - L2 跨页面刷新：浏览器不能持久化 `File` 句柄（File System Access API 的可持久化
 *   handle 在 Safari 上不可用，而该面板需要在手机浏览器上使用）。可行方案是：
 *   localStorage 保存一条线索，刷新后提示重新选择该文件以继续，用户重选后由 L1 逻辑
 *   自动接续。线索丢失不影响续传本身。
 * - L3 跨设备：不支持。
 *
 * 队列为串行：设备侧是运行 Ktor 的手机，并行多路只会互相争抢带宽，
 * 且更容易触发限流；app 端同样一次只传一个。
 */
import { computed, ref, shallowRef } from 'vue';
import { useAppStore } from '@/stores/app';
import { formatBytes } from '@/composables/utils';
import {
  authHeaders,
  effectiveUploadLimit,
  fallbackUploadCaps,
  fetchUploadCaps,
  uploadLimitLabel,
  type UploadCaps,
} from './filesShared';

/** 端点路径。签名摘要里的 URI 必须与实际请求路径逐字一致，故只此一处定义。 */
const UPLOAD_URI = '/api/files/upload';
const SESSION_URI = '/api/files/upload/session';
const CHUNK_URI = '/api/files/upload/chunk';
const COMPLETE_URI = '/api/files/upload/complete';

/** 429 重试：与 app 端一致（最多 3 次尝试，退避 1.5s × 次数）。 */
const MAX_ATTEMPTS = 3;
const RETRY_BASE_MS = 1500;

/**
 * 单个请求的超时下限。
 *
 * 设备休眠或 Wi-Fi 静默断开（对端不发送 RST）时 TCP 连接会长期挂起，XHR 既不触发 load
 * 也不触发 error，导致 pump 的 await 永不返回、`running` 永久为 true，后续 enqueue
 * 全部被 `if (running) return` 挡在 pending 且界面无提示，只能刷新页面。
 * 因此每个请求都必须设置超时。
 */
const REQUEST_TIMEOUT_MS = 60_000;
/** 带 body 的请求按最低速率折算超时预算：大分片或大文件在弱网下传输较慢，不能统一按 60s 计算。 */
const MIN_UPLOAD_BPS = 64 * 1024;
/** 能力探测（`GET /api/files/status`）的超时；该请求体量小，阻塞时应尽快降级。 */
const CAPS_TIMEOUT_MS = 10_000;

/** L2 续传线索的 localStorage 键。 */
const RESUME_KEY = 'ufi.upload.resume';

export type UploadStatus = 'pending' | 'uploading' | 'done' | 'error' | 'canceled';

export interface UploadTask {
  id: number;
  name: string;
  /** 目标目录（入队时快照：执行到该任务时当前目录可能已改变）。 */
  dir: string;
  size: number;
  loaded: number;
  status: UploadStatus;
  /** 失败原因，仅 status==='error' 时有值 */
  error: string;
  /** 瞬时速率（B/s），0 表示尚未采样。 */
  speed: number;
  /**
   * 设备端最终落盘的文件名。与 [name] 不同表示目标已存在、服务端自动改名
   * （如 `video (1).mp4`），必须显示，否则用户会认为原文件被覆盖。
   */
  finalName?: string;
  /** 该任务由续传恢复（面板标注，用于解释进度不从 0 开始）。 */
  resumed?: boolean;
  /**
   * 失败后能否「重试」。
   * 仅真正入过队（File 引用仍在）的任务可重试；超限被拦截的任务未发送任何字节，
   * 也没有 File 引用，不应显示点击后必然报错的按钮。
   */
  retryable?: boolean;
}

/** L2 续传线索。仅用于提示存在可续传的任务，续传本身不依赖它。 */
export interface ResumeHint {
  dir: string;
  name: string;
  size: number;
  received: number;
  /** 记录时间（ms），用于判断是否已超过设备端的会话 TTL。 */
  at: number;
}

interface UploadCallbacks {
  /** 目标目录取值函数：入队时调用一次，取当时的当前目录。 */
  currentPath: () => string;
  /** 每个文件成功后回调一次（用于刷新列表与磁盘读数）。 */
  onFileDone: (task: UploadTask) => void;
  /** 整批结束（无论成功或失败）回调一次。 */
  onAllSettled?: (summary: { done: number; failed: number; canceled: number }) => void;
  /** 超限被拦截时回调一次（供 message.warning 等外部提示使用）。 */
  onRejected?: (tasks: UploadTask[]) => void;
  /** 服务端自动改名时回调（用于提示已存为 `xxx (1).mp4`）。 */
  onRenamed?: (task: UploadTask) => void;
}

/**
 * 取消判定封装为函数：`task.status` 会在 await 期间被 cancel() 从外部修改，
 * 而 TS 会按赋值处的字面量把类型收窄为 'uploading'，直接写 `=== 'canceled'`
 * 会被判为不可能的比较（TS2367）。通过函数调用绕开类型收窄，语义上也更接近「重新读取当前值」。
 */
function isCanceled(t: UploadTask): boolean {
  return t.status === 'canceled';
}

function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

/**
 * 从失败响应中提取可读原因。
 *
 * core 的失败信封为 `{"error": "..."}`；413 由 `HttpServer` 的 StatusPages 生成，
 * 内容形如 `Request body (N bytes) exceeds 204800KB limit`。
 * 统一压缩为 `HTTP ${status}` 会丢失服务端已经给出的原因。
 */
function errorTextOf(xhr: XMLHttpRequest, limitLabel: string): string {
  if (xhr.status === 413) return `文件超过单文件上限 ${limitLabel}`;
  const raw = xhr.responseText;
  if (raw) {
    try {
      const msg = JSON.parse(raw)?.error;
      if (typeof msg === 'string' && msg) return msg;
    } catch {
      /* 不是 JSON（例如反向代理返回的 HTML 错误页），退回状态码 */
    }
  }
  return `HTTP ${xhr.status}`;
}

/** XHR 的结果。`body` 是已解析的 JSON（非 JSON 时为 null）。 */
interface XhrResult {
  ok: boolean;
  status: number;
  error: string;
  body: any;
  /** 本次为超时（未收到任何响应）。超时值得重发，见 pump 中的 retriable 判定。 */
  timedOut?: boolean;
}

/** 带 body 的请求的超时预算：按最低 64KB/s 折算，不低于普通请求的 60s。 */
function uploadTimeoutMs(bytes: number): number {
  return Math.max(REQUEST_TIMEOUT_MS, Math.ceil(bytes / MIN_UPLOAD_BPS) * 1000);
}

export function useFileUpload(cb: UploadCallbacks) {
  const appStore = useAppStore();
  const tasks = ref<UploadTask[]>([]);
  const panelOpen = ref(false);
  const inflight = shallowRef(new Map<number, XMLHttpRequest>());
  /** 分片路径下进行中的会话 id；取消时需调用 DELETE 清理 `.ufipart`。 */
  const activeSessions = shallowRef(new Map<number, string>());
  let seq = 0;
  let running = false;

  /** 能力位：首次入队时探测一次并缓存（页面生命周期内设备端不会变化）。 */
  let caps: UploadCaps | null = null;
  const capsRef = ref<UploadCaps>(fallbackUploadCaps());

  /** L2 线索列表，供面板展示可续传任务。 */
  const resumeHints = ref<ResumeHint[]>([]);

  const uploading = computed(() => tasks.value.some((t) => t.status === 'pending' || t.status === 'uploading'));
  const doneCount = computed(() => tasks.value.filter((t) => t.status === 'done').length);
  const failedCount = computed(() => tasks.value.filter((t) => t.status === 'error').length);

  /** 整批进度按字节加权：按文件数计算会在传输大文件时长时间停在同一进度。 */
  const overallPercent = computed(() => {
    const total = tasks.value.reduce((s, t) => s + (t.size || 0), 0);
    if (total <= 0) return 0;
    const loaded = tasks.value.reduce((s, t) => s + (t.status === 'done' ? t.size : t.loaded), 0);
    return Math.min(100, Math.round((loaded / total) * 100));
  });

  function percentOf(t: UploadTask): number {
    if (t.status === 'done') return 100;
    if (t.size <= 0) return 0;
    return Math.min(100, Math.floor((t.loaded / t.size) * 100));
  }

  async function ensureCaps(): Promise<UploadCaps> {
    if (caps) return caps;
    caps = await fetchUploadCaps({
      get: async (u: string) => {
        // 不复用 useApi 实例：能力探测可能发生在任意调用栈上，
        // 而 useCancellableApi 绑定组件生命周期。直接携带签名头发送请求最简单。
        const headers = await authHeaders(u, 'GET');
        // 必须设置超时：这是 pump 的第一个 await，设备休眠时裸 fetch 会一直挂起，
        // 整个队列连第一个文件都无法开始。超时由 fetchUploadCaps 的 catch 转换为回退能力位。
        // 不使用 AbortSignal.timeout：部分旧版手机浏览器不支持，取值会抛 TypeError
        // 并被同一个 catch 吞为「探测失败」，导致分片能力被静默丢弃。
        const controller = new AbortController();
        const timer = setTimeout(() => controller.abort(), CAPS_TIMEOUT_MS);
        try {
          const res = await fetch(`${appStore.baseUrl || ''}${u}`, { headers, signal: controller.signal });
          return { data: res.ok ? await res.json() : undefined };
        } finally {
          clearTimeout(timer);
        }
      },
    });
    capsRef.value = caps;
    return caps;
  }

  // ──────────────────────── L2 续传线索 ────────────────────────

  function loadResumeHints(ttlSeconds: number) {
    try {
      const raw = localStorage.getItem(RESUME_KEY);
      const list: ResumeHint[] = raw ? JSON.parse(raw) : [];
      const ttlMs = (ttlSeconds || 0) * 1000;
      const now = Date.now();
      // 超过设备端会话 TTL 的线索直接丢弃：对应 `.ufipart` 已被清理，
      // 保留只会让用户点击后从 0 开始，比不提示更差。
      resumeHints.value = ttlMs > 0 ? list.filter((h) => now - h.at < ttlMs) : [];
      saveResumeHints();
    } catch {
      resumeHints.value = [];
    }
  }

  function saveResumeHints() {
    try {
      localStorage.setItem(RESUME_KEY, JSON.stringify(resumeHints.value));
    } catch {
      /* 隐私模式下 localStorage 可能不可写；忽略即可，仅缺少一个提示。 */
    }
  }

  function rememberResume(t: UploadTask) {
    const idx = resumeHints.value.findIndex((h) => h.dir === t.dir && h.name === t.name && h.size === t.size);
    const hint: ResumeHint = { dir: t.dir, name: t.name, size: t.size, received: t.loaded, at: Date.now() };
    if (idx >= 0) resumeHints.value[idx] = hint;
    else resumeHints.value.push(hint);
    saveResumeHints();
  }

  function forgetResume(t: UploadTask) {
    resumeHints.value = resumeHints.value.filter((h) => !(h.dir === t.dir && h.name === t.name && h.size === t.size));
    saveResumeHints();
  }

  /** 用户主动清除一条续传线索（仅移除本地提示，不删除设备端的 `.ufipart`）。 */
  function dismissResume(hint: ResumeHint) {
    resumeHints.value = resumeHints.value.filter((h) => h !== hint);
    saveResumeHints();
  }

  // ──────────────────────── 底层请求 ────────────────────────

  /**
   * 发送一个带签名的 XHR。`onUp` 用于接收上传进度。
   *
   * 每次调用都重新计算签名：摘要包含时间戳与 nonce，复用会被判为 STALE_TIMESTAMP。
   */
  function signedXhr(
    method: string,
    uri: string,
    body: XMLHttpRequestBodyInit | null,
    taskId: number | null,
    onUp?: (loaded: number) => void,
    timeoutMs: number = REQUEST_TIMEOUT_MS
  ): Promise<XhrResult> {
    return new Promise((resolve) => {
      // 签名摘要使用的 URI 必须与实际请求路径逐字一致，query 也计入其中。
      authHeaders(uri, method)
        .then((headers) => {
          const xhr = new XMLHttpRequest();
          if (taskId !== null) inflight.value.set(taskId, xhr);
          xhr.open(method, `${appStore.baseUrl || ''}${uri}`, true);
          // 必须在 open 之后设置，否则触发 InvalidStateError。
          xhr.timeout = timeoutMs;
          for (const [k, v] of Object.entries(headers)) xhr.setRequestHeader(k, v);
          if (onUp) xhr.upload.onprogress = (e) => onUp(e.loaded);
          xhr.onload = () => {
            if (taskId !== null) inflight.value.delete(taskId);
            const ok = xhr.status >= 200 && xhr.status < 300;
            let parsed: any = null;
            try {
              parsed = xhr.responseText ? JSON.parse(xhr.responseText) : null;
            } catch {
              parsed = null;
            }
            resolve({
              ok,
              status: xhr.status,
              error: ok ? '' : errorTextOf(xhr, uploadLimitLabel(effectiveUploadLimit(capsRef.value))),
              body: parsed,
            });
          };
          xhr.onerror = () => {
            if (taskId !== null) inflight.value.delete(taskId);
            resolve({ ok: false, status: 0, error: '网络中断', body: null });
          };
          xhr.ontimeout = () => {
            if (taskId !== null) inflight.value.delete(taskId);
            resolve({ ok: false, status: 0, error: '请求超时', body: null, timedOut: true });
          };
          xhr.onabort = () => {
            if (taskId !== null) inflight.value.delete(taskId);
            resolve({ ok: false, status: 0, error: '已取消', body: null });
          };
          xhr.send(body);
        })
        .catch(() => resolve({ ok: false, status: 0, error: '签名失败', body: null }));
    });
  }

  /**
   * 删除设备端会话（清理路径之一）。
   *
   * `keepalive` 版本见 [installUnloadGuard]：那里必须使用 fetch 而非 sendBeacon，
   * 因为 sendBeacon 无法设置自定义头，而 `/api` 强制要求设备签名。
   */
  async function deleteSession(sessionId: string) {
    if (!sessionId) return;
    const uri = `${CHUNK_URI.replace('/chunk', '/session')}?session=${encodeURIComponent(sessionId)}`;
    await signedXhr('DELETE', uri, null, null);
  }

  // ──────────────────────── 整体上传 ────────────────────────

  /** 通过一次 multipart 请求传完。小文件与老固件走此路径。 */
  async function uploadWhole(task: UploadTask, file: File): Promise<XhrResult> {
    const form = new FormData();
    // 字段名 path 对应目标目录；文件字段名不参与 core 侧判定（receiveMultipart 只看 part 类型）。
    form.append('path', task.dir);
    form.append('file', file, task.name);
    // boundary 交给浏览器生成：手写 Content-Type 会遗漏 boundary，core 解析失败。
    return signedXhr(
      'POST',
      UPLOAD_URI,
      form,
      task.id,
      (loaded) => {
        task.loaded = loaded;
        sampleSpeed(task, loaded);
      },
      // 整体上传一次推送整个文件，超时需按文件大小计算。
      uploadTimeoutMs(task.size)
    );
  }

  // ──────────────────────── 分片上传 ────────────────────────

  /**
   * 分片上传：`session` → `chunk`×N → `complete`。
   *
   * 每片一个 XHR。取消在片间与片内均生效（`inflight` 中的 xhr 被 abort）。
   */
  async function uploadChunked(task: UploadTask, file: File, chunkSize: number): Promise<XhrResult> {
    // ① 开启会话。服务端在此完成路径校验、自动改名、空间与配额检查、续传探测。
    const open = await signedXhr(
      'POST',
      SESSION_URI,
      JSON.stringify({ path: task.dir, name: task.name, size: task.size }),
      task.id
    );
    if (!open.ok) return open;
    const sessionId = String(open.body?.session_id || '');
    if (!sessionId) return { ok: false, status: 0, error: '设备端未返回上传会话', body: null };

    activeSessions.value.set(task.id, sessionId);
    // 优先使用服务端下发的 chunk_size：客户端自行指定会使续传的分片序号错位。
    const size = Number(open.body?.chunk_size) || chunkSize;
    let received = Number(open.body?.received) || 0;
    const finalName = String(open.body?.file_name || task.name);
    task.finalName = finalName;
    if (open.body?.renamed === true) cb.onRenamed?.(task);
    if (received > 0) {
      task.resumed = true;
      task.loaded = received;
    }

    // ② 逐片发送。index 由服务端的 received 推导，不维护客户端计数器；
    //    续传与重试均依赖它自然对齐。
    while (received < task.size) {
      if (isCanceled(task)) return { ok: false, status: 0, error: '已取消', body: null };
      const index = Math.floor(received / size);
      const start = index * size;
      const end = Math.min(start + size, task.size);
      let blob: Blob;
      try {
        blob = file.slice(start, end);
      } catch {
        // 源文件在上传途中被修改或删除时，Chrome 会在此抛出 NotReadableError。
        await deleteSession(sessionId);
        return { ok: false, status: 0, error: '源文件已变更或无法读取', body: null };
      }
      const base = received;
      const uri = `${CHUNK_URI}?session=${encodeURIComponent(sessionId)}&index=${index}`;
      const res = await signedXhr(
        'PUT',
        uri,
        blob,
        task.id,
        (loaded) => {
          task.loaded = base + loaded;
          sampleSpeed(task, task.loaded);
        },
        // 分片按本片字节数放宽：设备端分片可能有数十 MB，弱网下 60s 不足。
        uploadTimeoutMs(end - start)
      );

      if (res.status === 409) {
        // 顺序不匹配：服务端在响应中返回其认可的 next_index / received，据此纠正后重试。
        const fixed = Number(res.body?.received);
        if (Number.isFinite(fixed) && fixed >= 0 && fixed !== received) {
          received = fixed;
          task.loaded = fixed;
          continue;
        }
        return res;
      }
      if (res.status === 410) {
        // 会话过期。重新开启一次：服务端会按 name+size 匹配已有 `.ufipart` 继续。
        activeSessions.value.delete(task.id);
        return { ok: false, status: 410, error: '上传会话已过期', body: null };
      }
      if (!res.ok) {
        // 包含 507（空间不足，服务端刻意保留了进度）：记录线索，使用户在清理空间后可以继续。
        // 取消不记录：cancel() 已 DELETE 掉会话（`.ufipart` 不存在），
        // 记录只会让面板提示可续传，而用户重选文件后仍从 0 开始。
        if (!isCanceled(task)) rememberResume(task);
        return res;
      }
      const next = Number(res.body?.received);
      received = Number.isFinite(next) ? next : received + (end - start);
      task.loaded = received;
      rememberResume(task);
    }

    // ③ 收尾：服务端校验总大小后 rename 到目标名。
    const done = await signedXhr('POST', COMPLETE_URI, JSON.stringify({ session_id: sessionId }), task.id);
    activeSessions.value.delete(task.id);
    if (done.ok) forgetResume(task);
    return done;
  }

  /** 速率由相邻两次 progress 采样求差得到；单次采样抖动较大，做一次指数平滑。 */
  const speedState = new Map<number, { at: number; loaded: number }>();
  function sampleSpeed(task: UploadTask, loaded: number) {
    const now = performance.now();
    const prev = speedState.get(task.id);
    if (!prev) {
      speedState.set(task.id, { at: now, loaded });
      return;
    }
    const dt = now - prev.at;
    // 采样窗口过短时不更新：dt 接近 0 会得到失真的瞬时值。
    if (dt < 400) return;
    const inst = ((loaded - prev.loaded) * 1000) / dt;
    task.speed = task.speed > 0 ? task.speed * 0.6 + inst * 0.4 : inst;
    speedState.set(task.id, { at: now, loaded });
  }

  // ──────────────────────── 队列 ────────────────────────

  /**
   * task.id → 待传的 File。
   *
   * 队列从任务表中不断取下一个 pending 任务，而不是执行一次性传入的批次，
   * 因此需要按 id 找回 File 对象（File 不能放入 `tasks`：不可序列化，
   * 且放入响应式数组会让 Vue 代理一个大对象）。
   */
  const pendingFiles = new Map<number, File>();

  /** 取任务表中第一个 pending 任务。取不到时返回 null（队列结束）。 */
  function nextPending(): { task: UploadTask; file: File } | null {
    for (const task of tasks.value) {
      if (task.status !== 'pending') continue;
      const file = pendingFiles.get(task.id);
      if (!file) {
        // 该情况不应发生；若发生则将其标记为失败，避免队列阻塞在该任务上。
        task.status = 'error';
        task.error = '文件引用已丢失，请重新选择';
        task.retryable = false;
        continue;
      }
      return { task, file };
    }
    return null;
  }

  /**
   * 串行执行队列直到没有 pending 任务。
   *
   * 队列从任务表中拉取：`running` 保证同一时刻只有一条任务在传（设备侧是手机，
   * 并行只会互相争抢带宽）。入队只把任务写入任务表并调用本函数；本函数已在运行时
   * 直接返回，新任务由当前循环的下一轮取走执行。因此循环必须在每次迭代重新读取
   * 任务表，不能只在入口处理一次性传入的批次。
   */
  async function pump() {
    if (running) return;
    running = true;
    try {
      const c = await ensureCaps();
      for (;;) {
        const next = nextPending();
        if (!next) break;
        const { task, file } = next;
        task.status = 'uploading';
        task.speed = 0;
        speedState.delete(task.id);
        // 小于一个分片的文件走整体上传：减少两次往返，也避免只有一个分片的多余会话。
        const chunked = c.supportsChunked && task.size > c.chunkSize;
        let last: XhrResult = { ok: false, status: 0, error: '上传失败', body: null };
        for (let attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
          if (attempt > 0) {
            if (!chunked) {
              // 整体上传重发从头开始，进度同时归零，避免进度条回退。
              task.loaded = 0;
              task.speed = 0;
            }
            await sleep(RETRY_BASE_MS * attempt);
          }
          last = chunked ? await uploadChunked(task, file, c.chunkSize) : await uploadWhole(task, file);
          if (last.ok || isCanceled(task)) break;
          // 429 限流可重试；分片路径下 410（会话过期）也可重试，重开会话会自动续传。
          // 超时（设备休眠或 Wi-Fi 静默断开）同样可重试：连接已失效，重发才有机会得到结果。
          // 其余 4xx 表示请求本身不合法，重发相同请求必然失败。
          const retriable = last.status === 429 || last.timedOut === true || (chunked && last.status === 410);
          if (!retriable) break;
        }
        // File 引用：成功或取消后立即释放；失败时保留，面板的「重试」依赖它原样重发
        // （分片路径下服务端按 name+size 匹配已有 `.ufipart`，即可自动续传）。
        if (last.ok || isCanceled(task)) pendingFiles.delete(task.id);
        // 速率采样一律释放：否则 speedState 会随已传文件数单调增长。
        speedState.delete(task.id);
        if (isCanceled(task)) continue;
        if (last.ok) {
          task.status = 'done';
          task.loaded = task.size;
          forgetResume(task);
          cb.onFileDone(task);
        } else {
          task.status = 'error';
          task.error = last.error;
        }
      }
    } finally {
      running = false;
      cb.onAllSettled?.({
        done: doneCount.value,
        failed: failedCount.value,
        canceled: tasks.value.filter((t) => t.status === 'canceled').length,
      });
    }
  }

  /**
   * 入队并在空闲时启动队列。列表为空时不执行任何操作。
   *
   * 每条任务可携带自己的目标目录（文件夹上传时按相对路径放入对应目录），
   * 不传则使用当前目录。
   *
   * 超限文件不入队、不发送请求，见 `filesShared.UPLOAD_MAX_BYTES_FALLBACK` 的注释：
   * 服务端 413 虽然在 `onCall` 阶段抛出，但浏览器要推完整个 body 才会读取该响应，
   * 不在客户端拦截等于消耗一遍上行带宽。
   */
  async function enqueueItems(list: { file: File; dir?: string }[]) {
    if (list.length === 0) return;
    const baseDir = cb.currentPath();
    const c = await ensureCaps();
    const limit = effectiveUploadLimit(c);
    const limitLabel = uploadLimitLabel(limit);

    const accepted: UploadTask[] = [];
    const rejected: UploadTask[] = [];

    for (const item of list) {
      const file = item.file;
      const task: UploadTask = {
        id: ++seq,
        name: file.name,
        dir: item.dir ?? baseDir,
        size: file.size,
        loaded: 0,
        status: 'pending',
        error: '',
        speed: 0,
      };
      if (file.size > limit) {
        task.status = 'error';
        // 同时报告实际大小：仅提示超限无法得知文件大小。
        task.error = `${formatBytes(file.size)}，超过单文件上限 ${limitLabel}`;
        // 未入队（未发送任何字节）且没有 File 引用，因此不可重试。
        task.retryable = false;
        rejected.push(task);
      } else {
        pendingFiles.set(task.id, file);
        task.retryable = true;
        accepted.push(task);
      }
    }

    // 超限任务同样进入列表：面板是本次操作的唯一反馈出口，静默丢弃会让用户误认为已上传。
    tasks.value = [...tasks.value, ...rejected, ...accepted];
    panelOpen.value = true;
    if (rejected.length > 0) cb.onRejected?.(rejected);
    if (accepted.length > 0) {
      // 已在运行时直接返回，新任务会由当前循环接上（见 pump 的注释）。
      void pump();
    } else if (!running) {
      // 整批均超限：队列不会运行，需在此补发收尾回调，否则调用方收不到整批结束通知。
      cb.onAllSettled?.({ done: 0, failed: rejected.length, canceled: 0 });
    }
  }

  /** 兼容旧调用：所有文件均使用当前目录。 */
  async function enqueue(files: File[]) {
    await enqueueItems(files.map((file) => ({ file })));
  }

  function cancel(id: number) {
    const t = tasks.value.find((x) => x.id === id);
    if (!t || t.status === 'done' || t.status === 'error') return;
    t.status = 'canceled';
    t.speed = 0;
    inflight.value.get(id)?.abort();
    // 已取消的任务不会再传，释放 File 引用（尚未轮到它时 pump 也就无法取到）。
    pendingFiles.delete(id);
    // 分片路径：必须让设备端删除 `.ufipart`，否则会残留无主分片文件。
    const sid = activeSessions.value.get(id);
    if (sid) {
      activeSessions.value.delete(id);
      void deleteSession(sid);
      forgetResume(t);
    }
  }

  function cancelAll() {
    for (const t of tasks.value) {
      if (t.status === 'pending' || t.status === 'uploading') cancel(t.id);
    }
  }

  /**
   * 重试一条失败的上传。
   *
   * 与批量队列的 `retryItem` 遵循同一规则：只处理 `error` 状态，把状态改回 `pending`
   * 后交给 `pump` 接续，不重开整个批次（重新选择文件会丢失已完成任务的进度）。
   *
   * 前提是 File 引用仍存在：`pump` 刻意只在成功或取消时释放（见该处注释）。
   * 分片路径下服务端按 name+size 匹配已有 `.ufipart` 并返回 `received`，重试即可自动续传；
   * 整体上传则从头重发，进度条同步归零。
   */
  function retry(id: number): void {
    const t = tasks.value.find((x) => x.id === id);
    if (!t || t.status !== 'error') return;
    if (!pendingFiles.has(t.id)) {
      t.error = '文件引用已丢失，请重新选择该文件';
      t.retryable = false;
      return;
    }
    t.status = 'pending';
    t.error = '';
    t.loaded = 0;
    t.speed = 0;
    t.resumed = false;
    t.finalName = undefined;
    panelOpen.value = true;
    // 队列已在运行时直接返回，该任务会由当前循环接上（见 pump 的注释）。
    void pump();
  }

  /** 清除已结束的任务行，正在传输的任务保留；全部清空时同时收起面板。 */
  function clearFinished() {
    tasks.value = tasks.value.filter((t) => t.status === 'pending' || t.status === 'uploading');
    if (tasks.value.length === 0) panelOpen.value = false;
  }

  function closePanel() {
    // 仅收起面板，不改动队列：传输继续进行，重新点击上传会再次展开。
    panelOpen.value = false;
  }

  /**
   * 页面卸载时尽力删除在途会话。
   *
   * 必须使用 `fetch(..., { keepalive: true })`，不能使用 `navigator.sendBeacon`：
   * sendBeacon 只能设置 Content-Type，无法设置自定义头，而 `/api` 强制要求
   * `X-Timestamp`/`X-Nonce`/`X-Signature`。keepalive fetch 可携带自定义头，
   * 且在页面卸载后仍会发出（body 上限 64KB，本请求几乎没有 body）。
   *
   * 覆盖用户正常关闭标签页的情况。浏览器崩溃或进程被强杀仍会遗漏，
   * 该情况由设备端的 TTL 惰性清理与 `/list` 清理兜底。
   *
   * 返回卸载函数，调用方在 `onUnmounted` 中调用。
   */
  function installUnloadGuard(): () => void {
    const handler = () => {
      for (const sid of activeSessions.value.values()) {
        const uri = `${CHUNK_URI.replace('/chunk', '/session')}?session=${encodeURIComponent(sid)}`;
        // 此处不能 await（页面正在卸载），也不能使用 signedXhr（XHR 在 unload 时会被中断）。
        authHeaders(uri, 'DELETE')
          .then((headers) => fetch(`${appStore.baseUrl || ''}${uri}`, { method: 'DELETE', headers, keepalive: true }))
          .catch(() => undefined);
      }
    };
    window.addEventListener('beforeunload', handler);
    return () => window.removeEventListener('beforeunload', handler);
  }

  return {
    tasks,
    panelOpen,
    uploading,
    doneCount,
    failedCount,
    overallPercent,
    percentOf,
    caps: capsRef,
    resumeHints,
    enqueue,
    enqueueItems,
    cancel,
    cancelAll,
    retry,
    clearFinished,
    closePanel,
    ensureCaps,
    loadResumeHints,
    dismissResume,
    installUnloadGuard,
  };
}
