/**
 * 文件上传队列（带进度、可取消）。
 *
 * 为什么必须用 XHR 而不是 fetch：**fetch 至今没有上传进度**（ReadableStream 上传在
 * 浏览器里仍是半残且不给 progress 事件），而 `XMLHttpRequest.upload.onprogress` 是
 * 唯一稳定拿到「已发送字节数」的途径。之前这里是 fetch，所以页面上只能有一个转圈，
 * 传 2GB 也看不出走到哪 —— 这就是本次要修的点。
 *
 * 为什么不用 naive 的 `n-upload` 内建进度：它自己发 XHR，只能带**静态请求头**，
 * 而 core 的 AuthMiddleware 对 `/api/files/upload` 强校验 `X-Timestamp`/`X-Nonce`/
 * `X-Signature`，签名摘要是 `METHOD\nURI\nTS\nNONCE`，时间戳与 nonce 每次都得重算，
 * 静态 header 在结构上就不可能满足（表现为每次上传必然 444）。所以签名走
 * `filesShared.authHeaders`（与下载/预览同一份实现），请求由这里自己发。
 *
 * 队列是**串行**的：设备侧是一台跑着 Ktor 的手机，并行多路只会互相抢带宽、
 * 还更容易撞上限流；app 端同样一次只传一个（FileManagerModule.uploadFileToServer
 * 用 `isUploading` 挡住并发）。
 */
import { computed, ref, shallowRef } from 'vue';
import { useAppStore } from '@/stores/app';
import { authHeaders } from './filesShared';

/** 上传接口路径。签名摘要里的 URI 必须与实际请求路径逐字一致，故只此一处定义。 */
const UPLOAD_URI = '/api/files/upload';

/** 429 重试：与 app 端一致（最多 3 次尝试，退避 1.5s × 次数）。 */
const MAX_ATTEMPTS = 3;
const RETRY_BASE_MS = 1500;

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
}

interface UploadCallbacks {
  /** 目标目录取值函数：入队时调用一次，取当时的当前目录 */
  currentPath: () => string;
  /** 每个文件成功后回调一次（用于刷新列表与磁盘读数） */
  onFileDone: (task: UploadTask) => void;
  /** 整批结束（无论成功失败）回调一次 */
  onAllSettled?: (summary: { done: number; failed: number; canceled: number }) => void;
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

export function useFileUpload(cb: UploadCallbacks) {
  const appStore = useAppStore();
  const tasks = ref<UploadTask[]>([]);
  /** 面板显示开关：由「有任务」驱动，用户手动收起后不再自动弹出，直到下一批入队 */
  const panelOpen = ref(false);

  /**
   * 进行中的 XHR。放 shallowRef 的 Map 而不是 reactive：XHR 实例不需要被追踪，
   * 深响应化一个原生对象只会让 devtools 卡住。
   */
  const inflight = shallowRef(new Map<number, XMLHttpRequest>());
  let seq = 0;
  let running = false;

  const uploading = computed(() => tasks.value.some((t) => t.status === 'uploading' || t.status === 'pending'));
  const doneCount = computed(() => tasks.value.filter((t) => t.status === 'done').length);
  const failedCount = computed(() => tasks.value.filter((t) => t.status === 'error').length);

  /**
   * 整批进度：按**字节**加权而不是按文件数。
   * 按文件数算的话，一个 1KB 的 txt 和一个 2GB 的视频各占 50%，进度条会长时间卡在 50%。
   */
  const overallPercent = computed(() => {
    const live = tasks.value.filter((t) => t.status !== 'canceled');
    const total = live.reduce((s, t) => s + t.size, 0);
    if (total <= 0) return live.length && live.every((t) => t.status === 'done') ? 100 : 0;
    const loaded = live.reduce((s, t) => s + (t.status === 'done' ? t.size : t.loaded), 0);
    return Math.min(100, Math.round((loaded / total) * 100));
  });

  function percentOf(t: UploadTask): number {
    if (t.status === 'done') return 100;
    if (t.size <= 0) return 0;
    return Math.min(100, Math.floor((t.loaded / t.size) * 100));
  }

  /** 单个文件：一次 XHR。resolve 表示这次尝试的结局，不代表整体成功。 */
  function putOnce(task: UploadTask, file: File): Promise<{ ok: boolean; status: number; error: string }> {
    return new Promise((resolve) => {
      authHeaders(UPLOAD_URI, 'POST')
        .then((headers) => {
          // 取消可能发生在算签名这段异步窗口里
          if (task.status === 'canceled') {
            resolve({ ok: false, status: 0, error: '已取消' });
            return;
          }
          const xhr = new XMLHttpRequest();
          inflight.value.set(task.id, xhr);
          xhr.open('POST', `${appStore.baseUrl || ''}${UPLOAD_URI}`, true);
          for (const [k, v] of Object.entries(headers)) xhr.setRequestHeader(k, v);

          // 速率用相邻两次 progress 采样求差；单次采样抖动大，做一次指数平滑
          let lastAt = performance.now();
          let lastLoaded = 0;
          xhr.upload.onprogress = (e) => {
            task.loaded = e.loaded;
            const now = performance.now();
            const dt = now - lastAt;
            // 采样窗口太短时不更新速率：dt 接近 0 会算出荒谬的瞬时值
            if (dt >= 400) {
              const inst = ((e.loaded - lastLoaded) * 1000) / dt;
              task.speed = task.speed > 0 ? task.speed * 0.6 + inst * 0.4 : inst;
              lastAt = now;
              lastLoaded = e.loaded;
            }
          };
          xhr.onload = () => {
            inflight.value.delete(task.id);
            const ok = xhr.status >= 200 && xhr.status < 300;
            resolve({ ok, status: xhr.status, error: ok ? '' : `HTTP ${xhr.status}` });
          };
          xhr.onerror = () => {
            inflight.value.delete(task.id);
            resolve({ ok: false, status: 0, error: '网络中断' });
          };
          xhr.onabort = () => {
            inflight.value.delete(task.id);
            resolve({ ok: false, status: 0, error: '已取消' });
          };

          const form = new FormData();
          // 字段名 path 对应目标目录；文件字段名不参与 core 侧判定（receiveMultipart 只看 part 类型）
          form.append('path', task.dir);
          form.append('file', file, task.name);
          // boundary 交给浏览器：手写 Content-Type 会漏掉 boundary，core 直接解析失败
          xhr.send(form);
        })
        .catch(() => resolve({ ok: false, status: 0, error: '签名失败' }));
    });
  }

  async function runQueue(pending: Array<{ task: UploadTask; file: File }>) {
    if (running) return;
    running = true;
    try {
      for (const { task, file } of pending) {
        if (isCanceled(task)) continue;
        task.status = 'uploading';
        let last = { ok: false, status: 0, error: '上传失败' };
        for (let attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
          if (attempt > 0) {
            // 限流退避：重发要从头开始，进度也得归零，否则条会往回跳得莫名其妙
            task.loaded = 0;
            task.speed = 0;
            await sleep(RETRY_BASE_MS * attempt);
          }
          last = await putOnce(task, file);
          if (last.ok || isCanceled(task)) break;
          // 只有 429（限流）值得重试：4xx 是请求本身不合法，重发同一份必然同样失败
          if (last.status !== 429) break;
        }
        if (isCanceled(task)) continue;
        if (last.ok) {
          task.status = 'done';
          task.loaded = task.size;
          cb.onFileDone(task);
        } else {
          task.status = 'error';
          task.error = last.error;
        }
      }
    } finally {
      running = false;
      // 队列跑完期间可能又入队了新文件（用户连点两次上传）
      const rest = tasks.value.filter((t) => t.status === 'pending');
      if (rest.length === 0) {
        cb.onAllSettled?.({
          done: doneCount.value,
          failed: failedCount.value,
          canceled: tasks.value.filter((t) => t.status === 'canceled').length,
        });
      }
    }
  }

  /** 入队并（若空闲）立即开跑。files 为空时什么都不做。 */
  function enqueue(files: File[]) {
    if (files.length === 0) return;
    const dir = cb.currentPath();
    const batch = files.map((file) => {
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
      return { task, file };
    });
    tasks.value = [...tasks.value, ...batch.map((b) => b.task)];
    panelOpen.value = true;
    void runQueue(batch);
  }

  function cancel(id: number) {
    const t = tasks.value.find((x) => x.id === id);
    if (!t || t.status === 'done' || t.status === 'error') return;
    t.status = 'canceled';
    t.speed = 0;
    inflight.value.get(id)?.abort();
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

  return {
    tasks,
    panelOpen,
    uploading,
    doneCount,
    failedCount,
    overallPercent,
    percentOf,
    enqueue,
    cancel,
    cancelAll,
    clearFinished,
    closePanel,
  };
}
