/**
 * 文件上传队列：分片 + 断点续传 + 进度 + 可取消。
 *
 * ## 为什么必须用 XHR 而不是 fetch
 * **fetch 至今没有上传进度**（ReadableStream 上传在浏览器里仍是半残且不给 progress 事件），
 * 而 `XMLHttpRequest.upload.onprogress` 是唯一稳定拿到「已发送字节数」的途径。
 *
 * ## 为什么不用 naive 的 `n-upload` 内建进度
 * 它自己发 XHR，只能带**静态请求头**，而 core 的 AuthMiddleware 对
 * `/api/files/upload*` 强校验 `X-Timestamp`/`X-Nonce`/`X-Signature`，签名摘要是
 * `METHOD\nURI\nTS\nNONCE`，时间戳与 nonce 每次都得重算 —— 静态 header 在结构上
 * 就不可能满足（表现为每次上传必然 444）。所以签名走 `filesShared.authHeaders`
 * （与下载/预览同一份实现），请求由这里自己发。
 *
 * ## 两条上传路径
 * - **整体**（`POST /upload`，multipart）：小文件或老固件。一次请求搞定，少两次往返。
 * - **分片**（`session` → `chunk`×N → `complete`）：大文件。断了能续，且服务端
 *   直接把分片追加到目标目录的 `.ufipart`，避免老实现「先落内部存储临时文件再
 *   跨卷 copy」带来的 2× 磁盘峰值。
 *
 * 走哪条由**设备端下发的能力位**决定（`GET /api/files/status`），不在前端硬编码。
 *
 * ## 断点续传的三个层级
 * - **L1 同页面内**：单片失败 / 网络抖动 → 重开 session，服务端按 name+size 匹配到
 *   已有的 `.ufipart` 并回 `received`，从那一片接着传。**不需要客户端记任何东西**。
 * - **L2 跨页面刷新**：浏览器**不能持久化 `File` 句柄**（File System Access API 的
 *   可持久化 handle 在 Safari 上没有，而这个面板要在手机浏览器上用）。所以能做到的
 *   最好效果是：localStorage 留一条**线索**，刷新后提示用户「重新选择该文件以继续」，
 *   用户重选后由 L1 那套逻辑自动接上。线索丢了也不影响续传本身。
 * - **L3 跨设备**：不做。
 *
 * 队列是**串行**的：设备侧是一台跑着 Ktor 的手机，并行多路只会互相抢带宽、
 * 还更容易撞上限流；app 端同样一次只传一个。
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

/** L2 续传线索的 localStorage 键。 */
const RESUME_KEY = 'ufi.upload.resume';

export type UploadStatus = 'pending' | 'uploading' | 'done' | 'error' | 'canceled';

export interface UploadTask {
  id: number;
  name: string;
  /** 目标目录（入队时快照：队列跑到它时用户可能已经换目录了） */
  dir: string;
  size: number;
  loaded: number;
  status: UploadStatus;
  /** 失败原因，仅 status==='error' 时有值 */
  error: string;
  /** 瞬时速率（B/s），0 表示还没有采样 */
  speed: number;
  /**
   * 设备端最终落盘的文件名。与 [name] 不同时说明目标已存在、服务端自动改名了
   * （`video (1).mp4`）—— **必须显示出来**，否则用户以为覆盖了原文件。
   */
  finalName?: string;
  /** 这一条是续传上来的（面板上标一下，让「进度不从 0 开始」看起来是对的） */
  resumed?: boolean;
}

/** L2 续传线索。只是"告诉用户有东西可以续"，续传本身不依赖它。 */
export interface ResumeHint {
  dir: string;
  name: string;
  size: number;
  received: number;
  /** 记录时间（ms），用来判断是否已超过设备端的会话 TTL */
  at: number;
}

interface UploadCallbacks {
  /** 目标目录取值函数：入队时调用一次，取当时的当前目录 */
  currentPath: () => string;
  /** 每个文件成功后回调一次（用于刷新列表与磁盘读数） */
  onFileDone: (task: UploadTask) => void;
  /** 整批结束（无论成功失败）回调一次 */
  onAllSettled?: (summary: { done: number; failed: number; canceled: number }) => void;
  /** 超限被拦截时回调一次（用于给 message.warning 等外部提示） */
  onRejected?: (tasks: UploadTask[]) => void;
  /** 服务端自动改名时回调（用于提示"已存为 xxx (1).mp4"） */
  onRenamed?: (task: UploadTask) => void;
}

/**
 * 取消判定包一层函数：`task.status` 会在 await 期间被 cancel() 从外部改掉，
 * 但 TS 会按赋值处的字面量把类型收窄成 'uploading'，直接写 `=== 'canceled'`
 * 会被判成不可能的比较（TS2367）。走函数调用绕开收窄，语义上也更贴近「现在再读一次」。
 */
function isCanceled(t: UploadTask): boolean {
  return t.status === 'canceled';
}

function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

/**
 * 从失败响应里取出可读的原因。
 *
 * core 的失败信封是 `{"error": "..."}`；413 那条由 `HttpServer` 的 StatusPages 给出，
 * 内容形如 `Request body (N bytes) exceeds 204800KB limit`。
 * 之前这里一律压成 `HTTP ${status}`，等于把服务端已经说清楚的原因又藏了回去。
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

/** XHR 的结局。`body` 是已解析的 JSON（非 JSON 时为 null）。 */
interface XhrResult {
  ok: boolean;
  status: number;
  error: string;
  body: any;
}

export function useFileUpload(cb: UploadCallbacks) {
  const appStore = useAppStore();
  const tasks = ref<UploadTask[]>([]);
  const panelOpen = ref(false);
  const inflight = shallowRef(new Map<number, XMLHttpRequest>());
  /** 分片路径下正在跑的会话 id，取消时要调 DELETE 把 `.ufipart` 清掉 */
  const activeSessions = shallowRef(new Map<number, string>());
  let seq = 0;
  let running = false;

  /** 能力位：首次入队时探一次并缓存（页面生命周期内设备端不会变）。 */
  let caps: UploadCaps | null = null;
  const capsRef = ref<UploadCaps>(fallbackUploadCaps());

  /** L2 线索列表，供面板展示"可续传"。 */
  const resumeHints = ref<ResumeHint[]>([]);

  const uploading = computed(() => tasks.value.some((t) => t.status === 'pending' || t.status === 'uploading'));
  const doneCount = computed(() => tasks.value.filter((t) => t.status === 'done').length);
  const failedCount = computed(() => tasks.value.filter((t) => t.status === 'error').length);

  /** 整批进度按**字节**加权：按文件数算会在传大文件时长时间卡在同一格。 */
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
        // 这里不复用 useApi 的实例：能力探测发生在任意调用栈上，
        // 而 useCancellableApi 绑定组件生命周期。直接用签名头发一次最简单。
        const headers = await authHeaders(u, 'GET');
        const res = await fetch(`${appStore.baseUrl || ''}${u}`, { headers });
        return { data: res.ok ? await res.json() : undefined };
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
      // 超过设备端会话 TTL 的线索直接丢：那边的 `.ufipart` 已经被清理闸门删了，
      // 留着只会让用户点进去发现"从 0 开始"，比不提示更糟
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
      /* 隐私模式下 localStorage 可能不可写，忽略 —— 只是少了个提示 */
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

  /** 用户主动清掉一条续传线索（同时让设备端删 `.ufipart`）。 */
  function dismissResume(hint: ResumeHint) {
    resumeHints.value = resumeHints.value.filter((h) => h !== hint);
    saveResumeHints();
  }

  // ──────────────────────── 底层请求 ────────────────────────

  /**
   * 发一个带签名的 XHR。`onUp` 用来接上传进度。
   *
   * 每次调用都重算签名：摘要含时间戳与 nonce，复用会被判 STALE_TIMESTAMP。
   */
  function signedXhr(
    method: string,
    uri: string,
    body: XMLHttpRequestBodyInit | null,
    taskId: number | null,
    onUp?: (loaded: number) => void
  ): Promise<XhrResult> {
    return new Promise((resolve) => {
      // 签名摘要用的 URI 必须与实际请求路径逐字一致，query 也算在内
      authHeaders(uri, method)
        .then((headers) => {
          const xhr = new XMLHttpRequest();
          if (taskId !== null) inflight.value.set(taskId, xhr);
          xhr.open(method, `${appStore.baseUrl || ''}${uri}`, true);
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
   * 删除设备端会话（清理闸门 1）。
   *
   * `keepalive` 版本见 [installUnloadGuard] —— 那里必须用 fetch 而不是 sendBeacon：
   * sendBeacon 设不了自定义头，而 `/api` 强制要设备签名。
   */
  async function deleteSession(sessionId: string) {
    if (!sessionId) return;
    const uri = `${CHUNK_URI.replace('/chunk', '/session')}?session=${encodeURIComponent(sessionId)}`;
    await signedXhr('DELETE', uri, null, null);
  }

  // ──────────────────────── 整体上传 ────────────────────────

  /** 一次 multipart 请求传完。小文件与老固件走这条。 */
  async function uploadWhole(task: UploadTask, file: File): Promise<XhrResult> {
    const form = new FormData();
    // 字段名 path 对应目标目录；文件字段名不参与 core 侧判定（receiveMultipart 只看 part 类型）
    form.append('path', task.dir);
    form.append('file', file, task.name);
    // boundary 交给浏览器：手写 Content-Type 会漏掉 boundary，core 直接解析失败
    return signedXhr('POST', UPLOAD_URI, form, task.id, (loaded) => {
      task.loaded = loaded;
      sampleSpeed(task, loaded);
    });
  }

  // ──────────────────────── 分片上传 ────────────────────────

  /**
   * 分片上传：`session` → `chunk`×N → `complete`。
   *
   * 每片一个 XHR。取消在片之间与片内都能生效（`inflight` 里的 xhr 被 abort）。
   */
  async function uploadChunked(task: UploadTask, file: File, chunkSize: number): Promise<XhrResult> {
    // ① 开会话。服务端在这里做完路径校验、自动改名、空间/配额检查、续传探测
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
    // 服务端下发的 chunk_size 优先：客户端自己定会让续传的片序号对不上
    const size = Number(open.body?.chunk_size) || chunkSize;
    let received = Number(open.body?.received) || 0;
    const finalName = String(open.body?.file_name || task.name);
    task.finalName = finalName;
    if (open.body?.renamed === true) cb.onRenamed?.(task);
    if (received > 0) {
      task.resumed = true;
      task.loaded = received;
    }

    // ② 逐片发。index 由服务端的 received 推导，不自己维护计数器 ——
    //    续传与重试都靠它自然对齐
    while (received < task.size) {
      if (isCanceled(task)) return { ok: false, status: 0, error: '已取消', body: null };
      const index = Math.floor(received / size);
      const start = index * size;
      const end = Math.min(start + size, task.size);
      let blob: Blob;
      try {
        blob = file.slice(start, end);
      } catch {
        // 源文件在上传途中被改/删，Chrome 会在这里抛 NotReadableError
        await deleteSession(sessionId);
        return { ok: false, status: 0, error: '源文件已变更或无法读取', body: null };
      }
      const base = received;
      const uri = `${CHUNK_URI}?session=${encodeURIComponent(sessionId)}&index=${index}`;
      const res = await signedXhr('PUT', uri, blob, task.id, (loaded) => {
        task.loaded = base + loaded;
        sampleSpeed(task, task.loaded);
      });

      if (res.status === 409) {
        // 顺序不匹配：服务端在 extra 里回了它认的 next_index / received，据此纠正后重试
        const fixed = Number(res.body?.received);
        if (Number.isFinite(fixed) && fixed >= 0 && fixed !== received) {
          received = fixed;
          task.loaded = fixed;
          continue;
        }
        return res;
      }
      if (res.status === 410) {
        // 会话过期。重开一次：服务端会按 name+size 匹配到已有 `.ufipart` 继续
        activeSessions.value.delete(task.id);
        return { ok: false, status: 410, error: '上传会话已过期', body: null };
      }
      if (!res.ok) {
        // 包含 507（空间不足，服务端刻意保留了进度）—— 记线索让用户清完空间能接着传
        rememberResume(task);
        return res;
      }
      const next = Number(res.body?.received);
      received = Number.isFinite(next) ? next : received + (end - start);
      task.loaded = received;
      rememberResume(task);
    }

    // ③ 收尾：服务端校验总大小后 rename 到目标名
    const done = await signedXhr('POST', COMPLETE_URI, JSON.stringify({ session_id: sessionId }), task.id);
    activeSessions.value.delete(task.id);
    if (done.ok) forgetResume(task);
    return done;
  }

  /** 速率用相邻两次 progress 采样求差；单次采样抖动大，做一次指数平滑。 */
  const speedState = new Map<number, { at: number; loaded: number }>();
  function sampleSpeed(task: UploadTask, loaded: number) {
    const now = performance.now();
    const prev = speedState.get(task.id);
    if (!prev) {
      speedState.set(task.id, { at: now, loaded });
      return;
    }
    const dt = now - prev.at;
    // 采样窗口太短时不更新：dt 接近 0 会算出荒谬的瞬时值
    if (dt < 400) return;
    const inst = ((loaded - prev.loaded) * 1000) / dt;
    task.speed = task.speed > 0 ? task.speed * 0.6 + inst * 0.4 : inst;
    speedState.set(task.id, { at: now, loaded });
  }

  // ──────────────────────── 队列 ────────────────────────

  /**
   * task.id → 待传的 File。
   *
   * 队列不再"跑一个传进来的批次"，而是**不断从任务表里取下一个 pending**，
   * 所以必须有个地方按 id 找回 File 对象（File 不能放进 `tasks`：它不可序列化，
   * 且放进响应式数组会让 Vue 去代理一个大对象）。
   */
  const pendingFiles = new Map<number, File>();

  /** 取任务表里第一个 pending。取不到返回 null（队列结束）。 */
  function nextPending(): { task: UploadTask; file: File } | null {
    for (const task of tasks.value) {
      if (task.status !== 'pending') continue;
      const file = pendingFiles.get(task.id);
      if (!file) {
        // 理论上不该发生；真发生了就把它标失败而不是让队列卡在这一条上
        task.status = 'error';
        task.error = '文件引用已丢失，请重新选择';
        continue;
      }
      return { task, file };
    }
    return null;
  }

  /**
   * 串行跑队列直到没有 pending。
   *
   * ## 2026-09-19 修：上传中再选文件会被静默丢弃
   * 原来是 `runQueue(pending)` + 开头 `if (running) return`。于是：
   * 选 3 个文件开始传 → 传的过程中再选 2 个 → `enqueue` 把这 2 条 task 推进列表、
   * 调 `runQueue`，而 `running` 为真 ⇒ **直接 return**，那 2 条永远停在 `pending`，
   * 进度条一动不动；`finally` 里也只是"检查还有没有 pending 来决定要不要报收尾"，
   * 并不会去跑它们。表现出来就是「多文件上传不生效」。
   *
   * 现在改成从任务表拉：`running` 仍然保证同一时刻只有一条在传（设备侧是一台手机，
   * 并行只会互相抢带宽），但新入队的文件会被当前这轮循环自然接上，
   * 不需要调用方关心"队列是不是已经在跑了"。
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
        // 小于一个分片的文件走整体上传：少两次往返，也避免"一片就完事"的多余会话
        const chunked = c.supportsChunked && task.size > c.chunkSize;
        let last: XhrResult = { ok: false, status: 0, error: '上传失败', body: null };
        for (let attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
          if (attempt > 0) {
            if (!chunked) {
              // 整体上传重发要从头开始，进度也得归零，否则条会往回跳得莫名其妙
              task.loaded = 0;
              task.speed = 0;
            }
            await sleep(RETRY_BASE_MS * attempt);
          }
          last = chunked ? await uploadChunked(task, file, c.chunkSize) : await uploadWhole(task, file);
          if (last.ok || isCanceled(task)) break;
          // 429 限流值得重试；分片路径下 410（会话过期）也值得 —— 重开会话会自动续传。
          // 其余 4xx 是请求本身不合法，重发同一份必然同样失败。
          const retriable = last.status === 429 || (chunked && last.status === 410);
          if (!retriable) break;
        }
        // 无论成功失败取消，这条的 File 都不再需要，及早释放引用
        pendingFiles.delete(task.id);
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
   * 入队并（若空闲）开跑。files 为空时什么都不做。
   *
   * **超限文件不入队、不发请求** —— 见 `filesShared.UPLOAD_MAX_BYTES_FALLBACK` 的注释：
   * 服务端的 413 虽然在 `onCall` 阶段就抛，但浏览器要把整个 body 推完才会去读那个响应，
   * 所以不在这里挡就等于白烧一遍上行带宽。
   */
  async function enqueue(files: File[]) {
    if (files.length === 0) return;
    const dir = cb.currentPath();
    const c = await ensureCaps();
    const limit = effectiveUploadLimit(c);
    const limitLabel = uploadLimitLabel(limit);

    const accepted: UploadTask[] = [];
    const rejected: UploadTask[] = [];

    for (const file of files) {
      const task: UploadTask = {
        id: ++seq,
        name: file.name,
        dir,
        size: file.size,
        loaded: 0,
        status: 'pending',
        error: '',
        speed: 0,
      };
      if (file.size > limit) {
        task.status = 'error';
        // 把实际大小一起报出来：只说"超限"用户还得自己去查文件多大
        task.error = `${formatBytes(file.size)}，超过单文件上限 ${limitLabel}`;
        rejected.push(task);
      } else {
        pendingFiles.set(task.id, file);
        accepted.push(task);
      }
    }

    // 超限的也进列表：面板是这次操作的唯一反馈出口，静默丢弃会让用户以为文件传上去了
    tasks.value = [...tasks.value, ...rejected, ...accepted];
    panelOpen.value = true;
    if (rejected.length > 0) cb.onRejected?.(rejected);
    if (accepted.length > 0) {
      // 已在跑时直接返回，新任务会被当前循环接上（见 pump 的注释）
      void pump();
    } else if (!running) {
      // 整批都超限：队列不会跑，收尾回调得在这里补，否则调用方等不到"这批结束"
      cb.onAllSettled?.({ done: 0, failed: rejected.length, canceled: 0 });
    }
  }

  function cancel(id: number) {
    const t = tasks.value.find((x) => x.id === id);
    if (!t || t.status === 'done' || t.status === 'error') return;
    t.status = 'canceled';
    t.speed = 0;
    inflight.value.get(id)?.abort();
    // 取消掉的不会再传，释放 File 引用（还没轮到它时 pump 也就取不到了）
    pendingFiles.delete(id);
    // 分片路径：必须让设备端把 `.ufipart` 删掉，否则就是一份垃圾
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

  /** 清掉已结束的行；正在传的保留。全清空时顺手收起面板。 */
  function clearFinished() {
    tasks.value = tasks.value.filter((t) => t.status === 'pending' || t.status === 'uploading');
    if (tasks.value.length === 0) panelOpen.value = false;
  }

  function closePanel() {
    // 只收起面板，不动队列：传输继续，重新点上传会再弹出来
    panelOpen.value = false;
  }

  /**
   * 页面卸载时尽力删掉在途会话（清理闸门 1 的第二半）。
   *
   * **必须用 `fetch(..., { keepalive: true })`，不能用 `navigator.sendBeacon`**：
   * sendBeacon 只能设 Content-Type，设不了自定义头，而 `/api` 强制要
   * `X-Timestamp`/`X-Nonce`/`X-Signature`。keepalive fetch 能带自定义头，
   * 且在页面卸载后仍会发出（body 限 64KB，我们这个请求几乎没有 body）。
   *
   * 覆盖"用户正常关标签页"。浏览器崩溃 / 强杀进程仍然漏 ——
   * 那种情况由设备端的 TTL 惰性清理与 `/list` 清理兜住。
   *
   * 返回一个卸载函数，调用方在 `onUnmounted` 里调。
   */
  function installUnloadGuard(): () => void {
    const handler = () => {
      for (const sid of activeSessions.value.values()) {
        const uri = `${CHUNK_URI.replace('/chunk', '/session')}?session=${encodeURIComponent(sid)}`;
        // 这里不能 await（页面正在卸载），也不能靠 signedXhr（XHR 在 unload 时会被中断）
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
    cancel,
    cancelAll,
    clearFinished,
    closePanel,
    ensureCaps,
    loadResumeHints,
    dismissResume,
    installUnloadGuard,
  };
}
