/**
 * 远端推送作业摘要（2026-10-14 新建，替换 FilesView 里那个只存 `active_count` 的角标）。
 *
 * ## 它解决的是什么
 * 上传到远端存储源是**两段式**（core 的设计）：① 手机 → core 落到本机暂存区（进度在上传面板里）；
 * ② core → 远端（`RemotePushManager` 的异步作业）。第二段原来只在 `RemotePushModal` 打开时可见，
 * 而那个入口藏在文件页工具栏的「存储」弹层里 —— 用户传完只看到"上传成功"、远端其实还没文件，
 * 关掉弹窗后既没有进度也没有失败重试入口（`RemotePushModal` 的轮询是 `watch(show)` 起停的）。
 *
 * 这里把**原来那一条 15s 轮询**（同一个端点 `GET /api/files/remote-push`、同一个节奏、
 * 同样"没有启用中的远端源就不打端点"）从"只取一个数字"升级成一份摘要：
 * 活跃作业数 + 按字节加权的总进度 + 正在推的文件名 + 失败/可重试数。
 * **没有新增第二条轮询** —— 弹窗自己那条 2s 轮询仍然只在弹窗打开时跑。
 *
 * ## 状态为什么是实例级
 * 摘要只是"当前这一屏要不要显示一条横幅"，`refresh()` 在挂载时立刻拉一次就能补齐，
 * 不需要像批量队列那样跨页面卸载存活（对比 `useBatchOperation` 文件头里那段取舍说明）。
 */
import { onScopeDispose, ref } from 'vue';
import { getApiClient } from '@/composables/useApi';

/** 与原来角标轮询同一个节奏：横幅只需要"大概在跑什么、有没有失败"，细进度在弹窗里（2s） */
const PUSH_POLL_MS = 15_000;

/** core 的作业状态里，这两个算"还在跑"（见 RemotePushModal 的 STATE_LABEL） */
const ACTIVE_STATES = ['queued', 'pushing'];

export interface RemotePushSummary {
  /** 排队中 + 推送中的作业数 */
  active: number;
  /** 已失败的作业数（需要用户处理的那些） */
  failed: number;
  /** 其中 core 标了 retryable 的（暂存文件还在，能直接重试） */
  retryable: number;
  /** 活跃作业按字节加权的总进度（0~100） */
  percent: number;
  /** 正在推的文件名（可能为空） */
  currentName: string;
}

function jobPercent(j: any): number {
  const p = Number(j?.progress);
  if (Number.isFinite(p) && p > 0) return Math.min(100, Math.round(p));
  const total = Number(j?.total_bytes) || 0;
  if (total > 0) return Math.min(100, Math.round(((Number(j?.sent_bytes) || 0) / total) * 100));
  return 0;
}

/**
 * 把 `/api/files/remote-push` 的响应压成一份摘要。没有任何要盯的东西时返回 null
 * （横幅据此不渲染，而不是显示一条"0 项"的空条）。
 */
export function summarizeRemotePush(jobs: unknown, activeCount: unknown): RemotePushSummary | null {
  const list: any[] = Array.isArray(jobs) ? jobs : [];
  const activeJobs = list.filter((j) => ACTIVE_STATES.includes(String(j?.state)));
  const failedJobs = list.filter((j) => String(j?.state) === 'failed');
  const counted = Number(activeCount);
  const active = Number.isFinite(counted) ? counted : activeJobs.length;
  if (!active && !failedJobs.length) return null;

  // 总进度按**字节**加权而不是各作业百分比取平均：一个大文件 + 一个刚排队的小文件，
  // 取平均会让进度虚高（与上传面板 overallPercent 同一条口径）。
  const sent = activeJobs.reduce((s, j) => s + (Number(j?.sent_bytes) || 0), 0);
  const total = activeJobs.reduce((s, j) => s + (Number(j?.total_bytes) || 0), 0);
  const percent =
    total > 0
      ? Math.min(100, Math.round((sent / total) * 100))
      : activeJobs.reduce((m, j) => Math.max(m, jobPercent(j)), 0);

  const current = activeJobs.find((j) => String(j?.state) === 'pushing') ?? activeJobs[0];
  return {
    active,
    failed: failedJobs.length,
    retryable: failedJobs.filter((j) => j?.retryable === true).length,
    percent,
    currentName: String(current?.file_name || ''),
  };
}

/**
 * @param isEnabled 有没有启用中的远端存储源。**没有就完全不轮询** —— 那种情况下不可能有推送作业，
 *                  白打端点（这条判断是原来角标就有的，保留）。
 */
export function useRemotePushSummary(isEnabled: () => boolean) {
  const api = getApiClient();
  const summary = ref<RemotePushSummary | null>(null);
  let timer: ReturnType<typeof setInterval> | null = null;

  async function refresh(): Promise<void> {
    if (!isEnabled()) {
      summary.value = null;
      return;
    }
    try {
      const { data } = await api.get('/api/files/remote-push');
      summary.value = summarizeRemotePush(data?.jobs, data?.active_count);
    } catch {
      /* 静默：老固件没有这个端点（与原来一致） */
    }
  }

  timer = setInterval(refresh, PUSH_POLL_MS);
  // 整页路由切走时必须停：否则离开文件页后还在打 remote-push（原来靠 FilesView 的 onUnmounted 清）
  onScopeDispose(() => {
    if (timer) {
      clearInterval(timer);
      timer = null;
    }
  });

  return { summary, refresh };
}
