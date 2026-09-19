<template>
  <n-modal
    :show="show"
    preset="card"
    title="流量历史"
    class="traffic-history-modal"
    :style="modalStyle"
    :bordered="false"
    @update:show="emit('update:show', $event)"
    @after-enter="onOpened"
  >
    <!-- 区间分段控件：只有 4 个固定选项，下拉要「点开→再点一次」才能切，
         而这是本弹窗里最高频的操作。n-tabs 的 segment 版式就是分段控件。 -->
    <n-tabs type="segment" size="small" :value="range" class="range-tabs" @update:value="changeRange">
      <n-tab v-for="opt in TRAFFIC_RANGE_OPTIONS" :key="opt.value" :name="opt.value">{{ opt.label }}</n-tab>
    </n-tabs>

    <div class="pager-row">
      <div class="pager-actions">
        <n-button size="small" secondary :disabled="!data" @click="goPrev">上一段</n-button>
        <!-- has_next 为假时必须真 disabled：可点但什么都不发生的按钮就是假按钮 -->
        <n-button size="small" secondary :disabled="!canGoNext" @click="goNext">下一段</n-button>
        <n-button v-if="anchor !== null" size="small" quaternary type="primary" @click="goCurrent"> 回到当前 </n-button>
      </div>
      <!-- 段落文案由 core 按 range 生成（"9月13日-9月19日" 这类），前端不拼日期 -->
      <span class="pager-label">{{ headerLabel }}</span>
    </div>

    <!-- 手势区包住图与空态两种情形：空段也要能滑回有数据的那一段。
         pointer 事件一套覆盖触屏与鼠标拖拽，不需要分别写 touch / mouse -->
    <div class="swipe-area" @pointerdown="onPointerDown" @pointerup="onPointerUp" @pointercancel="onPointerCancel">
      <!-- n-spin 而不是整块换成骨架：翻页时旧图留在屏幕上，避免每翻一段闪一次空屏 -->
      <n-spin :show="loading">
        <TrafficHistoryChart v-if="hasUsage" :data="data" />
        <div v-else class="empty-box">
          <n-empty :description="emptyText" />
        </div>
      </n-spin>
    </div>

    <div class="total-row">
      <span class="total-label">本段合计</span>
      <span class="total-value">{{ totalText }}</span>
    </div>
  </n-modal>
</template>

<script setup lang="ts">
/**
 * 流量历史弹窗（仪表盘 → 流量使用情况卡 → 「历史」）。
 *
 * 2026-09-15 从独立页改成弹窗：这一屏的信息量就是「一个分段控件 + 一张图 + 一行合计」，
 * 撑不起一整页，而它本来就是流量卡的下钻动作 —— 弹窗能让用户看完直接回到卡片，
 * 不必再走一次返回导航。原来的路由 `traffic-history` 与 `TrafficHistoryView.vue` 已删除。
 *
 * 同时删掉了两样东西：
 *  · 图下面的逐桶明细列表 —— 与柱状图 + 点击浮层是同一份信息的两种呈现
 *  · 「历史记录从 … 开始」的起点提示与「已是最新一段」 —— 前者一屏空柱本身已说明问题，
 *    后者已由「下一段」按钮的 disabled 表达，文字重复
 *
 * 取数走 useCancellableApi：组件卸载即 abort，被取消的请求由 getTrafficUsage 归一成 null，
 * 这里据此直接 return，不写任何状态（否则会往已卸载的组件写）。
 */
import { computed, ref } from 'vue';
import { useCancellableApi } from '@/composables/useCancellableApi';
import { formatBytes } from '@/composables/utils';
import { getTrafficUsage, type TrafficUsageRange, type TrafficUsageResponse } from '@/api/traffic';
import { TRAFFIC_RANGE_OPTIONS } from './trafficHistoryShared';
import TrafficHistoryChart from './components/TrafficHistoryChart.vue';

defineProps<{ show: boolean }>();
const emit = defineEmits<{ (e: 'update:show', v: boolean): void }>();

const api = useCancellableApi();

const range = ref<TrafficUsageRange>('day');
/** null = 当前段（由 core 用 now 定位）；非 null 表示已翻到历史段 */
const anchor = ref<number | null>(null);
const data = ref<TrafficUsageResponse | null>(null);
const loading = ref(false);
const error = ref<string | null>(null);

/**
 * 请求令牌。
 *
 * 快速连点「上一段」时，先发的请求可能后到；没有这个比对，旧段的数据会盖掉
 * 新段已经渲染好的内容（表现为「点了三下只退了一格」）。只有序号仍等于最新
 * 一次请求的响应才允许写状态。
 */
let requestSeq = 0;

async function load(nextRange: TrafficUsageRange, nextAnchor: number | null) {
  const seq = ++requestSeq;
  loading.value = true;
  error.value = null;
  try {
    const res = await getTrafficUsage(api, nextRange, nextAnchor);
    // null = 请求被取消（组件已卸载）；seq 不匹配 = 已被更新的请求接管
    if (res === null || seq !== requestSeq) {
      return;
    }
    data.value = res;
  } catch {
    if (seq !== requestSeq) {
      return;
    }
    // 404 / 500 / 网络失败统一落到这里：保留屏幕上的旧段数据，只在空态里说明失败
    error.value = '加载流量历史失败，可重试';
  } finally {
    if (seq === requestSeq) {
      loading.value = false;
    }
  }
}

/**
 * 打开时才取数，而不是 onMounted：弹窗是常驻在卡片里的，
 * 挂载即请求等于每次进仪表盘都白拉一次流量历史。
 * 用 after-enter 而不是 watch(show)：进场动画跑完再请求，避免动画期间的布局抖动。
 */
function onOpened() {
  load(range.value, anchor.value);
}

function changeRange(next: TrafficUsageRange) {
  range.value = next;
  // 换区间等于回到当前段：上一段的 anchor 是按旧桶宽算出来的，跨区间没有意义
  anchor.value = null;
  load(next, null);
}

/**
 * 翻页一律把上一次响应的 prev_anchor / next_anchor **原样**回传。
 * 前端自己算「前一天」会在 DST 切换日与月末错位（日视图那天是 23 或 25 个桶）。
 */
function goPrev() {
  const d = data.value;
  if (!d) {
    return;
  }
  anchor.value = d.prev_anchor;
  load(range.value, d.prev_anchor);
}

function goNext() {
  const d = data.value;
  if (!d || !d.has_next) {
    return;
  }
  anchor.value = d.next_anchor;
  load(range.value, d.next_anchor);
}

function goCurrent() {
  anchor.value = null;
  load(range.value, null);
}

/**
 * 弹窗宽度只能写成内联 style。
 *
 * n-modal 的卡片是 teleport 到 body、由 NModal 自己 render 的节点，scoped 样式的
 * `data-v-*` 属性到不了它 —— 所以此前写在 `<style scoped>` 里的 `.traffic-history-modal`
 * 宽度规则从未生效，弹窗一直贴着屏幕两边（本仓库其它弹窗都是内联 style，如 FileInfoModal）。
 * `calc(100vw - 32px)` 保证窄屏左右各留 16px 余量。
 */
const modalStyle = 'width: 720px; max-width: calc(100vw - 32px)';

const canGoNext = computed(() => data.value?.has_next === true);
const headerLabel = computed(() => data.value?.label || '—');
/** 桶为空数组、或所有桶都是 0，都不画图（详见 trafficHistoryCharts 里的说明） */
const hasUsage = computed(() => data.value?.buckets.some((b) => b.total_bytes > 0) === true);
const totalText = computed(() => (data.value ? formatBytes(data.value.total_bytes) : '--'));

const emptyText = computed(() => {
  if (error.value) {
    return error.value;
  }
  if (!data.value) {
    return '暂无数据';
  }
  if (data.value.buckets.length === 0) {
    return '这段时间没有可用的分桶数据';
  }
  return '这段时间没有流量记录';
});

// ── 左右滑动/拖拽翻页 ──
/**
 * 阈值给到 56px：点柱子看浮层时通常只有几像素抖动，阈值太小会把「看数值」
 * 误判成翻页。同时要求横向位移大于纵向，竖向为主的手势留给弹窗内滚动
 * （配合 CSS 的 touch-action: pan-y）。
 */
const SWIPE_THRESHOLD_PX = 56;
let dragPointerId: number | null = null;
let dragStartX = 0;
let dragStartY = 0;

function onPointerDown(e: PointerEvent) {
  // 只跟第一根手指：多点触控时后来的 pointer 不参与判定，免得两指缩放被当成翻页
  if (dragPointerId !== null) {
    return;
  }
  dragPointerId = e.pointerId;
  dragStartX = e.clientX;
  dragStartY = e.clientY;
}

function onPointerUp(e: PointerEvent) {
  if (dragPointerId !== e.pointerId) {
    return;
  }
  dragPointerId = null;
  const dx = e.clientX - dragStartX;
  const dy = e.clientY - dragStartY;
  if (Math.abs(dx) < SWIPE_THRESHOLD_PX || Math.abs(dx) <= Math.abs(dy)) {
    return;
  }
  // 往右拖 = 把时间轴往回拉 = 上一段；往左拖 = 下一段（goNext 自己守 has_next）
  if (dx > 0) {
    goPrev();
  } else {
    goNext();
  }
}

function onPointerCancel(e: PointerEvent) {
  if (dragPointerId === e.pointerId) {
    dragPointerId = null;
  }
}
</script>

<style scoped>
.range-tabs {
  margin-bottom: 12px;
}

.pager-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
  margin-bottom: 8px;
}
.pager-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.pager-label {
  font-size: 13px;
  font-weight: 600;
  color: var(--text-primary);
  font-variant-numeric: tabular-nums;
}

/* touch-action: pan-y —— 横向手势归本容器（翻页），竖向仍然交给弹窗滚动 */
.swipe-area {
  touch-action: pan-y;
}
.empty-box {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 300px;
}

.total-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-top: 12px;
  padding-top: 12px;
  border-top: 1px solid var(--border-subtle);
}
.total-label {
  font-size: 13px;
  color: var(--text-secondary);
}
.total-value {
  font-size: 15px;
  font-weight: 600;
  color: var(--text-primary);
  font-variant-numeric: tabular-nums;
}

@media (max-width: 768px) {
  .empty-box {
    height: 240px;
  }
  /* 窄屏把段落文案换行到按钮下方，避免"上一段/下一段"被挤成两行 */
  .pager-row {
    flex-direction: column;
    align-items: flex-start;
    gap: 6px;
  }
}
</style>
