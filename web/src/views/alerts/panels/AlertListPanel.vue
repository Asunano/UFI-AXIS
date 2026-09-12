<template>
  <div class="list-panel">
    <!-- 告警列表卡片 -->
    <GridCard title="告警列表">
      <template #extra>
        <n-tag size="small" :bordered="false">
          共 {{ totalCount }} 条<template v-if="unreadTotal > 0"> · 未确认 {{ unreadTotal }}</template>
        </n-tag>
      </template>

      <div class="summary-row">
        <div class="stat-tile sub-panel stat-critical">
          <span class="stat-num">{{ levelCounts.critical }}</span>
          <span class="stat-label">严重</span>
        </div>
        <div class="stat-tile sub-panel stat-warning">
          <span class="stat-num">{{ levelCounts.warning }}</span>
          <span class="stat-label">警告</span>
        </div>
        <div class="stat-tile sub-panel stat-info">
          <span class="stat-num">{{ levelCounts.info }}</span>
          <span class="stat-label">提示</span>
        </div>
        <div class="stat-tile sub-panel stat-unread">
          <span class="stat-num">{{ unreadTotal }}</span>
          <span class="stat-label">未确认</span>
        </div>
      </div>

      <div class="list-toolbar">
        <n-space size="small">
          <n-button size="tiny" :type="filterTab === 'all' ? 'primary' : 'default'" @click="filterTab = 'all'">
            全部
          </n-button>
          <n-button
            size="tiny"
            :type="filterTab === 'unacknowledged' ? 'primary' : 'default'"
            @click="filterTab = 'unacknowledged'"
          >
            未确认
          </n-button>
        </n-space>
        <n-space size="small">
          <n-button size="tiny" quaternary :loading="alertsLoading" @click="loadAlerts()"> 刷新 </n-button>
          <n-button
            size="tiny"
            type="warning"
            :disabled="unreadTotal === 0 && unacknowledgedCount === 0"
            :loading="ackAllLoading"
            @click="ackAll"
          >
            全部确认
          </n-button>
          <!--
            确认已恢复：只确认 resolvedAt 非空的告警，带一个可选的「最短存在时长」。
            用 popover 收纳选项，避免为一个可选参数再占一块常驻 UI。
          -->
          <n-popover v-model:show="ackResolvedPop" trigger="click" placement="bottom-end">
            <template #trigger>
              <n-button size="tiny" :loading="ackResolvedLoading">确认已恢复</n-button>
            </template>
            <div class="ack-resolved-pop">
              <div class="ack-resolved-title">确认已恢复的告警</div>
              <div class="ack-resolved-hint">
                可按「最短存在时长」过滤：低于该时长的已恢复告警不确认；选「不限」则不做时长过滤。
              </div>
              <n-radio-group v-model:value="minAgeSec" size="small">
                <n-space vertical :size="4">
                  <n-radio v-for="c in MIN_AGE_CHOICES" :key="c.value" :value="c.value">
                    {{ c.label }}
                  </n-radio>
                </n-space>
              </n-radio-group>
              <n-button size="tiny" type="primary" :loading="ackResolvedLoading" @click="ackResolved"> 确认 </n-button>
            </div>
          </n-popover>
        </n-space>
      </div>

      <n-spin :show="alertsLoading && alerts.length === 0">
        <div v-if="filteredAlerts.length === 0" class="empty-state">
          <n-empty description="暂无告警" />
        </div>
        <div v-else class="alert-list">
          <div v-for="alert in filteredAlerts" :key="alert.id" class="alert-item">
            <div class="alert-level" :class="'level-' + alert.level">
              <n-icon :size="18">
                <AlertCircleOutline v-if="alert.level === 'critical'" />
                <WarningOutline v-else-if="alert.level === 'warning'" />
                <InformationCircleOutline v-else />
              </n-icon>
            </div>
            <div class="alert-body">
              <div class="alert-top">
                <n-tag :type="levelTagType(alert.level)" size="tiny" :bordered="false">
                  {{ typeLabel(alert.type) }}
                </n-tag>
                <n-tag v-if="(alert.count ?? 1) > 1" size="tiny" :bordered="false"> × {{ alert.count }} </n-tag>
                <n-tag v-if="alert.resolvedAt" type="success" size="tiny" :bordered="false"> 已恢复 </n-tag>
                <span class="alert-time">{{ relativeTime(alert.timestamp) }}</span>
              </div>
              <div class="alert-message">{{ alert.message }}</div>
              <div v-if="hasDetail(alert)" class="alert-detail">
                当前: {{ alert.value || '--' }} / 阈值: {{ alert.threshold || '--' }}
              </div>
              <div v-if="(alert.count ?? 1) > 1 && alert.firstSeenAt" class="alert-detail">
                首次出现: {{ relativeTime(alert.firstSeenAt) }}
              </div>
            </div>
            <div class="alert-action">
              <n-button
                v-if="!alert.acknowledged"
                size="tiny"
                type="primary"
                :loading="ackLoadingId === alert.id"
                @click="ackAlert(alert.id)"
              >
                确认
              </n-button>
              <n-icon v-else :size="16" class="ack-check">
                <CheckmarkCircleOutline />
              </n-icon>
              <n-button
                size="tiny"
                quaternary
                type="error"
                :loading="delLoadingId === alert.id"
                style="margin-left: 4px"
                @click="deleteAlert(alert.id)"
              >
                <n-icon :size="14"><TrashOutline /></n-icon>
              </n-button>
            </div>
          </div>
        </div>
        <div v-if="hasMore && filterTab === 'all'" class="load-more">
          <n-button size="tiny" quaternary :loading="loadingMore" @click="loadAlerts(true)"> 加载更多 </n-button>
        </div>
      </n-spin>
    </GridCard>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue';
import { useInterval } from '@/composables/useRealtime';
import { useMessage } from 'naive-ui';
import {
  AlertCircleOutline,
  WarningOutline,
  InformationCircleOutline,
  CheckmarkCircleOutline,
  TrashOutline,
} from '@vicons/ionicons5';
import { useCancellableApi } from '@/composables/useCancellableApi';
import { Endpoints } from '@/api/contract';
import GridCard from '@/components/GridCard.vue';

const message = useMessage();
const api = useCancellableApi();

// ── 告警列表 ──
const alerts = ref<any[]>([]);
const alertsLoading = ref(false);
const filterTab = ref<'all' | 'unacknowledged'>('all');
const ackLoadingId = ref<number | null>(null);
const ackAllLoading = ref(false);
const delLoadingId = ref<number | null>(null);
// 确认已恢复：0 = 不限（不带 minAgeSec），其余为秒数
const ackResolvedPop = ref(false);
const ackResolvedLoading = ref(false);
const minAgeSec = ref(0);
const MIN_AGE_CHOICES: { label: string; value: number }[] = [
  { label: '不限', value: 0 },
  { label: '5 分钟', value: 300 },
  { label: '30 分钟', value: 1800 },
  { label: '1 小时', value: 3600 },
  { label: '24 小时', value: 86400 },
];
const totalCount = ref(0);
const unreadTotal = ref(0);
const nextCursor = ref<string | null>(null);
const hasMore = ref(false);
const loadingMore = ref(false);
const paginated = ref(false);

// ── 计算属性 ──
const unacknowledgedCount = computed(() => alerts.value.filter((a) => !a.acknowledged).length);

const filteredAlerts = computed(() => {
  if (filterTab.value === 'unacknowledged') {
    return alerts.value.filter((a) => !a.acknowledged);
  }
  return alerts.value;
});

// 概览条：按级别统计当前列表中的告警数，让用户一进来就看到「严重 X / 警告 Y / 提示 Z」
const levelCounts = computed(() => {
  const c = { critical: 0, warning: 0, info: 0 };
  for (const a of alerts.value) {
    if (a.level === 'critical') c.critical++;
    else if (a.level === 'warning') c.warning++;
    else if (a.level === 'info') c.info++;
  }
  return c;
});

// ── 辅助函数 ──
function typeLabel(type: string): string {
  const map: Record<string, string> = {
    temperature: '温度',
    battery: '电池',
    traffic: '流量',
    signal: '信号',
    connectivity: '连接',
    traffic_limit: '套餐限额',
    device_online: '设备接入',
    device_offline: '设备离开',
  };
  return map[type] || type;
}

function levelTagType(level: string): 'error' | 'warning' | 'info' {
  if (level === 'critical') return 'error';
  if (level === 'warning') return 'warning';
  return 'info';
}

// core 的 AlertRecord.timestamp / firstSeenAt / resolvedAt 都是 epoch 毫秒
function relativeTime(ts: number): string {
  const time = Number(ts);
  if (!time) return '--';
  const diff = Math.floor((Date.now() - time) / 1000);
  if (diff < 60) return '刚刚';
  if (diff < 3600) return `${Math.floor(diff / 60)} 分钟前`;
  if (diff < 86400) return `${Math.floor(diff / 3600)} 小时前`;
  return `${Math.floor(diff / 86400)} 天前`;
}

// connectivity 告警的 value / threshold 可能为空串，避免渲染出「当前:  / 阈值: 」
function hasDetail(alert: any): boolean {
  return !!String(alert?.value ?? '').trim() || !!String(alert?.threshold ?? '').trim();
}

// ── 数据加载 ──
async function loadAlerts(more = false) {
  if (more) {
    if (!nextCursor.value) return;
    loadingMore.value = true;
  } else {
    alertsLoading.value = true;
  }
  try {
    const params: Record<string, any> = { limit: 50 };
    if (more) params.cursor = nextCursor.value;
    const { data } = await api.get('/api/alerts/list', { params });
    const list: any[] = data.alerts || [];
    alerts.value = more ? [...alerts.value, ...list] : list;
    totalCount.value = data.total ?? alerts.value.length;
    unreadTotal.value = data.counts?.unread ?? unacknowledgedCount.value;
    nextCursor.value = data.nextCursor ?? null;
    hasMore.value = !!data.hasMore && !!data.nextCursor;
    paginated.value = more;
  } catch {
    if (!more) message.error('加载告警列表失败');
    else message.error('加载更多失败');
  } finally {
    alertsLoading.value = false;
    loadingMore.value = false;
  }
}

// ── 告警确认 ──
async function ackAlert(id: number) {
  ackLoadingId.value = id;
  try {
    await api.post('/api/alerts/ack', { id });
    const alert = alerts.value.find((a) => a.id === id);
    if (alert) alert.acknowledged = true;
    if (unreadTotal.value > 0) unreadTotal.value--;
    message.success('已确认');
  } catch (e: any) {
    message.error(e?.response?.data?.error || '确认失败');
  } finally {
    ackLoadingId.value = null;
  }
}

// core 提供 /api/alerts/ack-all（不传 ids 时按 unreadOnly 批量确认），避免逐条 N+1 请求
async function ackAll() {
  ackAllLoading.value = true;
  try {
    const { data } = await api.post('/api/alerts/ack-all', {});
    alerts.value.forEach((a) => {
      a.acknowledged = true;
    });
    unreadTotal.value = 0;
    message.success(`已确认 ${data?.updated ?? 0} 条告警`);
  } catch (e: any) {
    message.error(e?.response?.data?.error || '批量确认失败');
  } finally {
    ackAllLoading.value = false;
  }
}

/**
 * 只确认「已恢复」的告警（core /api/alerts/ack-resolved）。
 * minAgeSec 可选：缺省/不传 = 不做时长过滤；这里 0 表示不限，只在 > 0 时才带上该字段
 * （不要传 null —— 语义等价但白白让 core 多走一次判空）。
 * 成功信封是 { success: true, updated }，与 ack-all 同口径。
 * 恢复状态由 core 判定，前端无法就地推断哪些条目被确认，所以直接重新拉一次列表。
 */
async function ackResolved() {
  ackResolvedLoading.value = true;
  try {
    const body: Record<string, number> = {};
    if (minAgeSec.value > 0) body.minAgeSec = minAgeSec.value;
    const { data } = await api.post(Endpoints.alerts.ackResolved, body);
    message.success(`已确认 ${data?.updated ?? 0} 条已恢复告警`);
    ackResolvedPop.value = false;
    await loadAlerts();
  } catch (e: any) {
    message.error(e?.response?.data?.message || e?.response?.data?.error || '确认已恢复告警失败');
  } finally {
    ackResolvedLoading.value = false;
  }
}

async function deleteAlert(id: number) {
  delLoadingId.value = id;
  try {
    await api.post('/api/alerts/delete', { id });
    const removed = alerts.value.find((a) => a.id === id);
    if (removed && !removed.acknowledged && unreadTotal.value > 0) unreadTotal.value--;
    alerts.value = alerts.value.filter((a) => a.id !== id);
    if (totalCount.value > 0) totalCount.value--;
    message.success('已删除');
  } catch (e: any) {
    message.error(e?.response?.data?.error || '删除失败');
  } finally {
    delLoadingId.value = null;
  }
}

// ── 挂载即拉；自动刷新 (15 秒，已翻页时不刷新避免清空历史页) ──
// useInterval 在组件卸载时自动 stop（见 useRealtime），告警页切到「配置」Tab 即停止轮询。
onMounted(() => {
  loadAlerts();
});

useInterval(() => {
  if (!paginated.value) loadAlerts();
}, 15000);
</script>

<style scoped>
.list-panel {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

/* 概览条：4 个统计块，按级别着色，给一眼可见的态势 */
.summary-row {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 10px;
  margin-bottom: 14px;
}
/* 描边/内距/圆角/底色走 main.css 的全局 .sub-panel */
.stat-tile {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 2px;
}
.stat-num {
  font-size: 22px;
  font-weight: 700;
  line-height: 1.1;
  font-variant-numeric: tabular-nums;
}
.stat-label {
  font-size: 12px;
  color: var(--text-muted);
}
.stat-critical .stat-num {
  color: var(--error);
}
.stat-warning .stat-num {
  color: var(--warning);
}
.stat-info .stat-num {
  color: var(--accent-color);
}
.stat-unread .stat-num {
  color: var(--text-primary);
}

.list-toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 12px;
}
.empty-state {
  padding: 40px 0;
}
/* 确认已恢复 popover */
.ack-resolved-pop {
  display: flex;
  flex-direction: column;
  gap: 8px;
  max-width: 240px;
}
.ack-resolved-title {
  font-size: 13px;
  color: var(--text-primary);
}
.ack-resolved-hint {
  font-size: 12px;
  line-height: 1.5;
  color: var(--text-muted);
}
.load-more {
  display: flex;
  justify-content: center;
  padding-top: 10px;
}

/* ── 告警条目 ── */
.alert-list {
  display: flex;
  flex-direction: column;
}
.alert-item {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  padding: 10px 0;
  border-bottom: 1px solid var(--border-subtle);
}
.alert-item:last-child {
  border-bottom: none;
}
.alert-level {
  flex-shrink: 0;
  margin-top: 2px;
}
.level-critical {
  color: var(--error);
}
.level-warning {
  color: var(--warning);
}
.level-info {
  color: var(--accent-color);
}
.alert-body {
  flex: 1;
  min-width: 0;
}
.alert-top {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 4px;
}
.alert-time {
  font-size: 12px;
  color: var(--text-muted);
}
.alert-message {
  font-size: 13px;
  color: var(--text-primary);
  line-height: 1.4;
  word-break: break-all;
}
.alert-detail {
  font-size: 12px;
  color: var(--text-muted);
  margin-top: 4px;
}
.alert-action {
  flex-shrink: 0;
  display: flex;
  align-items: center;
  margin-top: 2px;
}
.ack-check {
  color: var(--success);
  opacity: 0.6;
}

/* ── 响应式 ── */
@media (max-width: 768px) {
  .list-toolbar {
    flex-wrap: wrap;
    gap: 8px;
  }
  .alert-item {
    flex-wrap: wrap;
  }
  .alert-action {
    width: 100%;
    justify-content: flex-end;
    margin-top: 4px;
  }
}
</style>
