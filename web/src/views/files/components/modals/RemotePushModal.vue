<template>
  <n-modal
    v-model:show="show"
    preset="card"
    title="远端推送作业"
    class="modal-fit"
    style="--modal-w: 760px; --modal-h: 720px"
  >
    <div class="push-body">
      <p class="push-hint">
        上传到远端存储源分两段：先分片传到本机暂存区（上传进度在上传面板里）， 再由 core 推给远端 ——
        这里看的是**第二段**。关掉本窗口不影响推送，core 会继续跑。
      </p>

      <n-spin :show="loading">
        <div v-if="jobs.length" class="push-list">
          <div v-for="j in jobs" :key="j.id" class="push-item" :class="`st-${j.state}`">
            <div class="push-main">
              <div class="push-title">
                <span class="push-name">{{ j.file_name || j.id }}</span>
                <span class="push-state" :class="`st-${j.state}`">{{ stateLabel(j.state) }}</span>
              </div>
              <div class="push-dest">
                目标：{{ j.source_label || j.source_id }} · <span class="mono">{{ j.dest_path }}</span>
              </div>

              <div v-if="j.state === 'queued' || j.state === 'pushing'" class="push-bar">
                <i :style="{ width: pct(j) + '%' }" />
              </div>
              <div v-if="j.state === 'queued' || j.state === 'pushing'" class="push-meta">
                {{ pct(j) }}% · {{ formatBytes(j.sent_bytes || 0) }} / {{ formatBytes(j.total_bytes || 0) }}
              </div>

              <!-- 失败/取消的原因分两层：error 是给人看的一句话，error_detail 是原始异常 -->
              <div v-if="j.error || j.error_detail" class="push-err">
                {{ j.error || '推送失败' }}
                <span v-if="j.error_detail" class="push-err-detail">{{ j.error_detail }}</span>
              </div>
              <div class="push-time">{{ timeText(j) }}</div>
            </div>

            <div class="push-acts">
              <n-button v-if="j.state === 'queued' || j.state === 'pushing'" size="tiny" @click="cancel(j.id)">
                取消
              </n-button>
              <n-button v-if="j.retryable" size="tiny" type="primary" @click="retry(j.id)">重试</n-button>
            </div>
          </div>
        </div>
        <n-empty v-else-if="!loading" description="没有推送记录" />
      </n-spin>
    </div>

    <template #footer>
      <div class="modal-footer">
        <span class="push-active">进行中 {{ activeCount }} 个</span>
        <n-button size="small" @click="show = false">关闭</n-button>
        <n-button size="small" :disabled="!finishedCount" :loading="clearing" @click="clearFinished">
          清理已结束（{{ finishedCount }}）
        </n-button>
        <n-button size="small" :loading="loading" @click="load">刷新</n-button>
      </div>
    </template>
  </n-modal>
</template>

<script setup lang="ts">
/**
 * 远端推送作业面板（2026-10-14 新建）。
 *
 * ## 它补的是哪一段
 * 上传到远端存储源是**两段式**（core 的设计）：
 *   ① 手机 → core：走分片上传会话，落到本机暂存区，进度由上传面板显示；
 *   ② core → 远端：`RemotePushManager` 的异步推送作业，就是本面板。
 * 缺了本面板，第②段是**完全不可见**的 —— 用户传完只看到"上传成功"，
 * 远端却没有文件，也没有任何地方能看进度 / 失败原因 / 重试。
 *
 * ## 端点（core `FileRoutes` 的 remote-push 组）
 * - GET    /api/files/remote-push           → { jobs, active_count }
 * - DELETE /api/files/remote-push?job=<id>  取消在途作业（含删暂存文件）
 * - POST   /api/files/remote-push/retry     { job_id } 重试；暂存被清理时回 **409**
 * - POST   /api/files/remote-push/clear     清掉已结束记录
 *
 * ## 轮询
 * 只在弹窗打开期间轮询（2s）—— 关掉后没有理由继续打这个端点。
 * 取消一个刚成功的作业是正常竞态：core 对"作业已结束"返回 `success:false` 而不是报错，
 * 所以这里也不弹错误，只是刷新列表。
 */
import { ref, computed, watch, onUnmounted } from 'vue';
import { useMessage } from 'naive-ui';
import { useCancellableApi } from '@/composables/useCancellableApi';
import { formatBytes } from '@/composables/utils';

const props = defineProps<{ show: boolean }>();
const emit = defineEmits<{ 'update:show': [boolean] }>();

const show = computed({
  get: () => props.show,
  set: (v) => emit('update:show', v),
});

const message = useMessage();
const api = useCancellableApi();

interface PushJob {
  id: string;
  file_name: string;
  source_id: string;
  source_label: string;
  dest_path: string;
  state: string;
  progress: number;
  sent_bytes: number;
  total_bytes: number;
  error?: string | null;
  error_detail?: string | null;
  created_at: number;
  finished_at: number;
  retryable: boolean;
}

const jobs = ref<PushJob[]>([]);
const loading = ref(false);
const clearing = ref(false);
const activeCount = ref(0);

const finishedCount = computed(
  () => jobs.value.filter((j) => j.state === 'success' || j.state === 'failed' || j.state === 'cancelled').length
);

const STATE_LABEL: Record<string, string> = {
  queued: '排队中',
  pushing: '推送中',
  success: '已完成',
  failed: '失败',
  cancelled: '已取消',
};

function stateLabel(s: string): string {
  return STATE_LABEL[s] || s;
}

/** 进度百分比：优先用服务端的 progress；它缺省时用字节数兜底算。 */
function pct(j: PushJob): number {
  if (typeof j.progress === 'number' && j.progress > 0) return Math.min(100, Math.round(j.progress));
  if (j.total_bytes > 0) return Math.min(100, Math.round(((j.sent_bytes || 0) / j.total_bytes) * 100));
  return 0;
}

function fmtTime(ms?: number | null): string {
  if (!ms) return '';
  const d = new Date(ms);
  const p = (n: number) => String(n).padStart(2, '0');
  return `${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`;
}

function timeText(j: PushJob): string {
  const created = fmtTime(j.created_at);
  if (j.finished_at) return `${created} 开始 · ${fmtTime(j.finished_at)} 结束`;
  return created ? `${created} 开始` : '';
}

async function load() {
  loading.value = true;
  try {
    const { data } = await api.get('/api/files/remote-push');
    jobs.value = data?.jobs || [];
    activeCount.value =
      data?.active_count ?? jobs.value.filter((j) => j.state === 'queued' || j.state === 'pushing').length;
  } catch {
    /* 静默：老固件没有这个端点 */
  } finally {
    loading.value = false;
  }
}

async function cancel(id: string) {
  try {
    const { data } = await api.delete('/api/files/remote-push', { params: { job: id } });
    // success:false = 点到的是一个刚结束的作业（客户端列表可能慢 1 秒），不是错误
    if (data?.success === false) message.info('该作业已经结束了');
    await load();
  } catch {
    message.error('取消失败');
  }
}

async function retry(id: string) {
  try {
    await api.post('/api/files/remote-push/retry', { job_id: id });
    message.success('已重新排队');
    await load();
  } catch (e: any) {
    // 409 = 暂存文件已被清理，重试的前提没了 —— core 给的文案就是让用户重新上传
    if (e?.response?.status === 409) {
      message.warning(e.response?.data?.error || '暂存文件已清理，请重新上传');
    } else {
      message.error('重试失败');
    }
    await load();
  }
}

async function clearFinished() {
  clearing.value = true;
  try {
    const { data } = await api.post('/api/files/remote-push/clear');
    message.success(`已清理 ${data?.removed ?? 0} 条记录`);
    await load();
  } catch {
    message.error('清理失败');
  } finally {
    clearing.value = false;
  }
}

// 打开时拉一次 + 每 2s 轮询；关掉即停（没有理由在后台继续打这个端点）
let timer: ReturnType<typeof setInterval> | null = null;

function stopPoll() {
  if (timer) {
    clearInterval(timer);
    timer = null;
  }
}

watch(
  () => props.show,
  (v) => {
    if (v) {
      load();
      stopPoll();
      timer = setInterval(load, 2000);
    } else {
      stopPoll();
    }
  },
  { immediate: true }
);

// 组件被销毁（例如整页路由切换）时定时器也要清
onUnmounted(stopPoll);
</script>

<style scoped>
/* 宽高与滚动由全局 `.modal-fit` 统一约束：原来这里手写
   `max-height: clamp(160px, calc(100vh - 260px), 560px)`，与弹窗自身的高度上限叠成两层约束。 */
.push-body {
  min-width: 0;
}
.push-hint {
  margin: 0 0 12px;
  font-size: 12px;
  color: var(--text-muted);
  line-height: 1.6;
}
.push-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.push-item {
  display: flex;
  align-items: flex-start;
  gap: 12px;
  padding: 10px 12px;
  border: 1px solid var(--border-subtle);
  border-radius: var(--radius-sm);
}
/* 失败/取消的整块略微泛红，扫一眼就能挑出来 */
.push-item.st-failed,
.push-item.st-cancelled {
  border-color: var(--error);
}
.push-main {
  flex: 1;
  min-width: 0;
}
.push-title {
  display: flex;
  align-items: center;
  gap: 8px;
}
.push-name {
  font-size: 14px;
  font-weight: 600;
  color: var(--text-primary);
  word-break: break-all;
}
.push-state {
  font-size: 11px;
  padding: 0 6px;
  border-radius: 999px;
  color: var(--text-secondary);
  background: var(--page-bg);
  flex-shrink: 0;
}
.push-state.st-pushing,
.push-state.st-queued {
  color: var(--accent-color);
  background: var(--accent-color-light);
}
.push-state.st-success {
  color: var(--success);
}
.push-state.st-failed,
.push-state.st-cancelled {
  color: var(--error);
}
.push-dest {
  margin-top: 2px;
  font-size: 12px;
  color: var(--text-secondary);
  word-break: break-all;
}
.push-bar {
  margin-top: 8px;
  height: 4px;
  border-radius: 999px;
  background: var(--border-subtle);
  overflow: hidden;
}
.push-bar > i {
  display: block;
  height: 100%;
  background: var(--accent-color);
  transition: width 0.4s ease;
}
.push-meta {
  margin-top: 4px;
  font-size: 11px;
  color: var(--text-muted);
  font-variant-numeric: tabular-nums;
}
.push-err {
  margin-top: 6px;
  font-size: 12px;
  color: var(--error);
  word-break: break-all;
}
.push-err-detail {
  display: block;
  margin-top: 2px;
  color: var(--text-muted);
  font-size: 11px;
}
.push-time {
  margin-top: 4px;
  font-size: 11px;
  color: var(--text-muted);
}
.push-acts {
  display: flex;
  flex-direction: column;
  gap: 6px;
  flex-shrink: 0;
}
/* 与全局 .modal-footer 的差异：这里页脚左侧还有一段「进行中」文字（`.push-active`），
   纵向要居中而不是拉伸，所以额外覆盖 align-items（计划 §8.2 保留差异，不硬抹平）。
   display / justify-content / gap 走 main.css 那一份。 */
.modal-footer {
  align-items: center;
}
.push-active {
  margin-right: auto;
  font-size: 12px;
  color: var(--text-muted);
}
.mono {
  font-family: monospace;
}
/* 断点 2026-10-21 由 480 并回 768（全站只留 768/1024 两档） */
@media (max-width: 768px) {
  .push-item {
    flex-direction: column;
    align-items: stretch;
  }
  .push-acts {
    flex-direction: row;
    justify-content: flex-end;
  }
  /* 页脚 4 项（进行中 + 关闭 + 清理 + 刷新）在 360px 下放不进一行：
     允许换行，按钮仍保持完整可点尺寸 */
  .modal-footer {
    flex-wrap: wrap;
  }
  .push-active {
    flex: 1 1 100%;
    margin-right: 0;
  }
}
</style>
