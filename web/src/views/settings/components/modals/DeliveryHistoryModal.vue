<!--
  「最近投递」—— 某一条通知渠道每次投递的结果。

  一个组件服务三条渠道（邮件 / Webhook / 本机短信），渠道由 `channel` prop 带进来：
  三条渠道的列表形态、筛选口径、翻页逻辑完全一样，复制三份的唯一产物是三处会各自跑偏的文案。
  入口落在各渠道自己那一行的操作区里 —— 记录要跟产生它的那套配置放在一起才找得到。
  与 app 的 `DeliveryHistoryScreen` 是同一个页面的两端镜像。

  数据源是设备端 `GET /api/sms-forward/history`（core 的 `mail_send_records` 表）。
  结果是**三态**而不是"成功/失败"两态，分类走 `deliveryOutcomeOf`（全 web 唯一一处判定，
  也是它兜住"设备上的 core 还没升到 DB v12"的地方）：

  | 结论 | 含义 | 行渲染 |
  |------|------|--------|
  | sent | 已发出 | success 令牌 |
  | failed | 发起了投递但失败，`error` 是异常链 | error 令牌 |
  | skipped | 没发起，被闸门按用户配置拦下，`error` 是中文原因 | 次要色 |

  跳过原因（总开关关着 / 场景没勾 / 级别不够 / 配额用尽 / 配置不完整）由 core 写成中文直接显示，
  web 侧不维护第二张映射表 —— 抄一份的结果是 core 新增一种原因、这里显示成空白。

  为什么是弹窗：与 WebhookConfigModal / LocalSmsConfigModal 同一条理由 —— 通知分栏里
  只留一张能一眼看完的渠道清单，逐条记录属于"点进去才看"的深层内容。
-->
<template>
  <n-modal
    :show="show"
    preset="card"
    :title="title"
    style="width: 720px; max-width: calc(100vw - 32px)"
    @update:show="emit('update:show', $event)"
  >
    <!--
      三个计数分开显示：失败与跳过**不能相加**（跳过是闸门按用户自己的配置拦下的，
      并进失败数会让人去排一个不存在的故障）。两者都是该渠道的全表计数，不随筛选变化。
    -->
    <div class="summary">
      <span v-for="s in summaryItems" :key="s.label" class="summary-item">
        <span class="row-hint">{{ s.label }}</span>
        <span class="summary-value" :class="s.tone">{{ s.value }}</span>
      </span>
    </div>
    <span class="row-hint">失败与跳过是两组独立计数，不相加；两者统计该渠道的全部记录，不随下面的筛选变化。</span>

    <!--
      筛选走服务端 `result` 参数、换档从第一页重拉：只筛当前页会出现
      「摘要说有 12 条没发出，列表里只有 2 条」。
    -->
    <div class="filter-row">
      <n-radio-group :value="filter" size="small" :disabled="loading" @update:value="onFilterChange">
        <n-radio-button v-for="f in FILTERS" :key="f.value" :value="f.value">{{ f.label }}</n-radio-button>
      </n-radio-group>
      <n-button size="tiny" quaternary :loading="loading" @click="reload">刷新</n-button>
    </div>

    <n-spin :show="loading && records.length === 0">
      <!-- 首屏读取时保留这块高度：n-spin 的遮罩要有内容可覆盖，否则容器坍塌成一条线 -->
      <div v-if="records.length === 0" class="history-empty">
        <n-empty v-if="!loading" size="small" :description="emptyText">
          <template #extra>
            <span class="row-hint">{{ emptyHint }}</span>
          </template>
        </n-empty>
      </div>
      <n-scrollbar v-else style="max-height: 420px">
        <div class="history-list">
          <div v-for="r in records" :key="r.id" class="history-item">
            <div class="history-head">
              <span class="history-title">{{ r.subject || sceneLabel(r.scene) }}</span>
              <n-tag size="tiny" :bordered="false" :type="statusOf(r).tag">{{ statusOf(r).text }}</n-tag>
              <span class="history-time">{{ formatNotifyTime(r.sent_at) }}</span>
            </div>
            <div class="history-meta">
              <span>{{ sceneLabel(r.scene) }}</span>
              <span v-if="r.recipient">发往 {{ r.recipient }}</span>
            </div>
            <!-- failed 的异常链与 skipped 的中文原因都取 core 的 `error` 原文，两者只在配色上区分 -->
            <div v-if="detailOf(r)" class="history-detail" :class="statusOf(r).tone">{{ detailOf(r) }}</div>
          </div>
        </div>
        <div v-if="hasMore" class="load-more-row">
          <n-button size="tiny" quaternary :loading="loadingMore" @click="load(true)">加载更多</n-button>
        </div>
      </n-scrollbar>
    </n-spin>

    <template #footer>
      <div class="footer-bar">
        <span class="row-hint">记录保存在设备上，条数与保留天数上限在通知设置里。</span>
        <n-space :size="8">
          <n-button size="small" :disabled="records.length === 0" @click="confirmClear">清空记录</n-button>
          <n-button size="small" @click="emit('update:show', false)">关闭</n-button>
        </n-space>
      </div>
    </template>
  </n-modal>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { useDialog, useMessage } from 'naive-ui';
import { getApiClient } from '@/composables/useApi';
import {
  DeliveryChannel,
  Endpoints,
  HistoryListLimits,
  HistoryResultFilter,
  deliveryOutcomeOf,
  type MailHistoryResponse,
  type MailSendRecord,
} from '@/api/contract';
import { formatNotifyTime, sceneLabel } from '@/views/settings/notifyShared';

const props = defineProps<{ show: boolean; channel: string }>();
const emit = defineEmits<{ 'update:show': [boolean] }>();

const message = useMessage();
const dialog = useDialog();
const api = getApiClient();

/** 「不过滤」那一档的 value。它**不是**接口认得的取值，提交前翻成"不传 result"。 */
const FILTER_ALL = 'all';

/**
 * 四档筛选。
 *
 * 「没发出」与「已跳过」必须分开：前者是投递出了故障（去排 SMTP / HTTP / 信令），
 * 后者是闸门按用户自己的配置拦下的（去改设置）。合成一档等于把两种处置方式混在一起。
 */
const FILTERS = [
  { value: FILTER_ALL, label: '全部' },
  { value: HistoryResultFilter.SENT, label: '已发出' },
  { value: HistoryResultFilter.FAILED, label: '没发出' },
  { value: HistoryResultFilter.SKIPPED, label: '已跳过' },
];

/**
 * 弹窗标题（也是清空确认里指代"清哪一份"的那句话）。
 *
 * 整句存在这张表里而不是"渠道名 + 后缀"拼出来：Webhook 那一档按中文排版惯例要在拉丁词后
 * 留一个空格，拼接方案得为这一个特例写判断。认不出的渠道退化成"投递记录"，不显示 id。
 */
const CHANNEL_TITLES: Record<string, string> = {
  [DeliveryChannel.MAIL]: '邮件投递记录',
  [DeliveryChannel.WEBHOOK]: 'Webhook 投递记录',
  [DeliveryChannel.LOCAL_SMS]: '本机短信投递记录',
};

const records = ref<MailSendRecord[]>([]);
const loading = ref(false);
const loadingMore = ref(false);
const total = ref(0);
const failedTotal = ref(0);
const skippedTotal = ref(0);
const cursorTs = ref<number | null>(null);
const cursorId = ref<number | null>(null);
const hasMore = ref(false);
const filter = ref<string>(FILTER_ALL);

/**
 * 请求序号。**不用 ref**：它只在 [load] 内部比对，参与不了渲染，做成响应式反而会
 * 让每次发请求都触发一轮无谓的更新。自增即"作废之前所有在飞的请求"。
 */
let requestSeq = 0;

const title = computed(() => CHANNEL_TITLES[props.channel] ?? '投递记录');

const summaryItems = computed(() => [
  { label: '总计', value: total.value, tone: '' },
  { label: '没发出', value: failedTotal.value, tone: failedTotal.value > 0 ? 'tone-failed' : '' },
  { label: '已跳过', value: skippedTotal.value, tone: skippedTotal.value > 0 ? 'tone-skipped' : '' },
]);

const emptyText = computed(() => (filter.value === FILTER_ALL ? '还没有投递记录' : '这个筛选下没有记录'));

const emptyHint = computed(() =>
  filter.value === FILTER_ALL
    ? // 不能说"只有真的发起过发送才会记一条"：三态之后，被闸门拦下的也会记一条。
      '渠道配置完整后，每次触发都会记一条，含未实际发出的那些。'
    : '「全部」档包含已发出、没发出与已跳过三类记录。'
);

/**
 * 一行的状态标记。三态的措辞刻意分开："没发出"是投递出了故障，"已跳过"是根本没发起。
 * `tone` 只用于配色（跳过用次要色而不是 error —— 它不是故障，涂成红的会让人去排一个不存在的问题）。
 */
function statusOf(record: MailSendRecord): { text: string; tag: 'success' | 'error' | 'default'; tone: string } {
  switch (deliveryOutcomeOf(record)) {
    case 'sent':
      return { text: '已发出', tag: 'success', tone: 'tone-sent' };
    case 'failed':
      return { text: '没发出', tag: 'error', tone: 'tone-failed' };
    default:
      return { text: '已跳过', tag: 'default', tone: 'tone-skipped' };
  }
}

/** 失败/跳过的说明取 core 的 `error` 原文；已发出时没有说明（core 回空串）。 */
function detailOf(record: MailSendRecord): string {
  if (deliveryOutcomeOf(record) === 'sent') return '';
  return record.error || '原因未记录';
}

/**
 * 拉一页。`cursor_ts` 与 `cursor_id` **必须成对**下发：只带一个 core 会当首页处理，
 * 表现是「加载更多」翻出与第一页重复的记录。
 *
 * 每次发起都领一个新的请求序号，回来时对不上就整份丢掉。快速连点筛选按钮时，
 * 先发的那个请求完全可能后到 —— 不比对的话它会把列表覆盖成与当前 `filter` 不符的内容，
 * 而界面上的筛选按钮还停在用户最后点的那一档，看起来就是"筛选不生效"。
 */
async function load(more = false) {
  if (more) {
    if (loadingMore.value || !hasMore.value) return;
    if (cursorTs.value === null || cursorId.value === null) return;
    loadingMore.value = true;
  } else {
    loading.value = true;
  }
  const seq = ++requestSeq;
  try {
    const params: Record<string, any> = { limit: HistoryListLimits.default, channel: props.channel };
    if (filter.value !== FILTER_ALL) params.result = filter.value;
    if (more) {
      params.cursor_ts = cursorTs.value;
      params.cursor_id = cursorId.value;
    }
    const { data } = await api.get<MailHistoryResponse>(Endpoints.smsForward.history, { params });
    // 过期响应：这期间用户已经换了筛选（或重新打开了弹窗），这一份不属于当前列表
    if (seq !== requestSeq) return;
    if (!data) return;
    const list = data.records ?? [];
    if (more) {
      const existing = new Set(records.value.map((r) => r.id));
      records.value = [...records.value, ...list.filter((r) => !existing.has(r.id))];
    } else {
      records.value = list;
    }
    total.value = data.total ?? 0;
    failedTotal.value = data.failed_total ?? 0;
    skippedTotal.value = data.skipped_total ?? 0;
    cursorTs.value = data.next_cursor_ts ?? null;
    cursorId.value = data.next_cursor_id ?? null;
    hasMore.value = !!data.has_more && data.next_cursor_ts != null && data.next_cursor_id != null;
  } catch (e: any) {
    // 过期请求的报错也要丢掉：那条错误说的不是用户正在看的这一档
    if (seq !== requestSeq) return;
    message.error(e?.response?.data?.error || (more ? '加载更多失败' : '加载投递记录失败'));
  } finally {
    // 只有最后发出的那个请求才有资格收掉 loading —— 过期请求在这里清标志会让
    // 仍在飞的新请求提前解禁筛选按钮，等于把刚修掉的竞态又放回来
    if (seq === requestSeq) {
      loading.value = false;
      loadingMore.value = false;
    }
  }
}

/** 换筛选、刷新、重新打开都从第一页开始：游标是上一档筛选的位置，带着它翻页会错行。 */
function reload() {
  records.value = [];
  cursorTs.value = null;
  cursorId.value = null;
  hasMore.value = false;
  load(false);
}

function onFilterChange(value: string) {
  if (value === filter.value) return;
  filter.value = value;
  reload();
}

/**
 * 清空**只清当前渠道**：`channel` 参数不传时 core 会清掉全部渠道的记录，
 * 于是在 Webhook 这一页按下清空会把邮件的失败记录一起删掉。文案也只讲当前渠道。
 */
function confirmClear() {
  dialog.warning({
    title: '清空记录',
    content: `将删除设备上保存的「${title.value}」，其他渠道的记录不受影响。已经发出的通知本身不受影响。`,
    positiveText: '清空',
    negativeText: '取消',
    onPositiveClick: async () => {
      try {
        await api.delete(Endpoints.smsForward.history, { params: { channel: props.channel } });
        total.value = 0;
        failedTotal.value = 0;
        skippedTotal.value = 0;
        reload();
        message.success('已清空');
      } catch (e: any) {
        message.error(e?.response?.data?.error || '清空失败');
      }
    },
  });
}

// 每次打开都从第一页重拉：记录是设备侧产生的，关着的这段时间会变。
// 也监听 channel —— 弹窗关闭后有 350ms 的卸载延迟（见 useLazyModal），
// 在这段时间里换一条渠道再打开，组件实例还是同一个，只有 prop 变了。
watch(
  () => [props.show, props.channel],
  ([show]) => {
    if (!show) return;
    filter.value = FILTER_ALL;
    reload();
  },
  { immediate: true }
);
</script>

<style scoped>
.row-hint {
  font-size: 12px;
  color: var(--text-muted);
  line-height: 1.5;
}

/* ── 计数摘要 ── */
.summary {
  display: flex;
  flex-wrap: wrap;
  gap: 6px 24px;
  padding: 4px 0 6px;
}
.summary-item {
  display: inline-flex;
  align-items: baseline;
  gap: 6px;
}
.summary-value {
  font-size: 16px;
  font-weight: 600;
  color: var(--text-primary);
}

.filter-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
  padding: 10px 0;
}

.history-empty {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 140px;
  padding: 24px 0;
}

/* ── 记录列表 ── */
.history-list {
  display: flex;
  flex-direction: column;
}
.history-item {
  padding: 10px 0;
  border-bottom: 1px solid var(--border-subtle);
}
.history-item:last-child {
  border-bottom: none;
}
.history-head {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.history-title {
  font-size: 13px;
  font-weight: 500;
  color: var(--text-primary);
  word-break: break-word;
}
.history-time {
  margin-left: auto;
  font-size: 11px;
  color: var(--text-muted);
  white-space: nowrap;
}
.history-meta {
  display: flex;
  align-items: baseline;
  flex-wrap: wrap;
  gap: 4px 14px;
  margin-top: 4px;
  font-size: 12px;
  color: var(--text-muted);
  word-break: break-all;
}
.history-detail {
  margin-top: 4px;
  font-size: 12px;
  line-height: 1.5;
  word-break: break-word;
}

/* 三态配色一律走令牌：跳过用次要文字色（不是故障），失败才用 error */
.tone-sent {
  color: var(--success);
}
.tone-failed {
  color: var(--error);
}
.tone-skipped {
  color: var(--text-secondary);
}

.load-more-row {
  display: flex;
  justify-content: center;
  padding: 10px 0;
}

.footer-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
  width: 100%;
}
</style>
