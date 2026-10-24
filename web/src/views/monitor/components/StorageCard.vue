<template>
  <n-modal
    :show="show"
    preset="card"
    title="存储管理"
    class="modal-fit"
    style="--modal-w: 560px; --modal-h: 560px"
    :bordered="false"
    @update:show="emit('update:show', $event)"
  >
    <n-spin :show="loading" class="storage-spin">
      <!-- 失败条：读不到存储表时不能沿用「0 KB / 空表」，那会被读成「没有可清理的数据」 -->
      <div v-if="failed" class="storage-failed">
        <span class="storage-failed-text">存储信息读取失败</span>
        <n-button size="tiny" quaternary type="primary" @click="emit('retry')">重试</n-button>
      </div>

      <!-- scroll-x 480：两个 120px 的固定列 + 名称列在窄弹窗里会把
           `traffic_records` 这类表名压成 60px（折 3 行）。给一个最小内容宽让表格
           自己横向滚，而不是把内容压到读不出来。 -->
      <n-data-table
        v-if="data.tables.length"
        :columns="columns"
        :data="data.tables"
        :bordered="false"
        :single-line="false"
        size="small"
        :scroll-x="480"
      />
      <div v-else-if="!loading && !failed" class="client-empty">暂无可清理的数据表</div>

      <div class="storage-total">总占用: {{ failed ? '--' : data.total_display }}</div>
      <p class="storage-note">
        大小为按行估算值，非数据库实际占用；alert_records / sms_records 的记录数由后端固定返回，仅作占位。
      </p>
    </n-spin>

    <template #footer>
      <div class="storage-foot">
        <n-button size="small" type="warning" @click="cleanModal.open()">清理数据</n-button>
        <n-button size="small" @click="emit('update:show', false)">关闭</n-button>
      </div>
    </template>
  </n-modal>

  <component
    :is="cleanModalComponent"
    v-if="cleanModalComponent"
    :show="cleanModalShow"
    @update:show="cleanModal.setShow"
    @cleaned="emit('cleaned')"
  />
</template>

<script setup lang="ts">
/**
 * 存储管理（占用表格 + 清理入口）。
 *
 * 2026-10-22：由页面底部的常驻 GridCard 改为控件条按钮打开的弹窗 ——
 * 首屏只看图表；存储表是「偶尔看一眼、顺手清一下」的下钻内容，不该常驻占一屏。
 * 文件名沿用 StorageCard（改动范围只在本批次；内容与状态契约没变）。
 *
 * 数据仍由父组件取（loadAll 会连带刷新它，自动刷新也走同一条路），这里只负责渲染；
 * 弹窗由 MonitorView 用 useLazyModal 懒挂载，关闭即卸载 —— 但取数留在 loadAll 里，
 * 所以「打开就是当前值」，与改造前一致。
 * 清理成功后 emit('cleaned')，由父组件统一重新取数 —— 清掉的历史同时影响 6 张图，
 * 不能只刷新自己这张表。
 *
 * 2026-10-21（计划 §6.10 B9-2）：`size_kb ?? 0` 与 `count ?? '--'` 两种空值口径
 * 统一成 `--`。`0` 是合法读数（表刚清理过），只有「字段缺失 / 请求失败」才显示 `--`。
 *
 * 宽高走 `.modal-fit`：窄屏/矮屏由那个类统一收口，内容区自己滚。
 */
import type { DataTableColumns } from 'naive-ui';
import { useLazyModal } from '@/composables/useLazyModal';
// 类型放 monitorShared 而不是这里：`<script setup>` 里不允许 export
import type { StorageData } from '../monitorShared';

defineProps<{
  show: boolean;
  data: StorageData;
  loading: boolean;
  /** 存储接口失败（父组件保留上一次的表，本组件负责说明失败） */
  failed?: boolean;
}>();

const emit = defineEmits<{
  (e: 'update:show', show: boolean): void;
  (e: 'cleaned'): void;
  (e: 'retry'): void;
}>();

const cleanModal = useLazyModal(() => import('./modals/CleanDataModal.vue'));
const cleanModalComponent = cleanModal.component;
const cleanModalShow = cleanModal.show;

const columns: DataTableColumns = [
  { title: '数据表', key: 'name' },
  {
    title: '记录数',
    key: 'count',
    width: 120,
    render: (row) => {
      const count = (row as any).count;
      // 0 条是合法读数；null / undefined / 非数字才是「没取到」
      if (count == null || !Number.isFinite(Number(count))) return '--';
      return Number(count).toLocaleString();
    },
  },
  {
    title: '大小',
    key: 'size_kb',
    width: 120,
    render: (row) => {
      const raw = (row as any).size_kb;
      if (raw == null || !Number.isFinite(Number(raw))) return '--';
      const kb = Number(raw);
      if (kb >= 1024) return (kb / 1024).toFixed(1) + ' MB';
      return kb.toFixed(1) + ' KB';
    },
  },
];
</script>

<style scoped>
/* 首帧取数时内容区还是空的，n-spin 的遮罩没有内容可覆盖会塌成一条线（本仓多处同此处理） */
.storage-spin {
  display: block;
  min-height: 120px;
}

.storage-failed {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: var(--space-2);
  margin-bottom: var(--space-2);
  padding: var(--space-1) var(--space-2);
  border-radius: var(--radius-sm);
  background: var(--error-light);
}

.storage-failed-text {
  font-size: var(--font-sm);
  color: var(--error);
}

.storage-total {
  margin-top: var(--space-3);
  font-size: var(--font-base);
  font-weight: 500;
  color: var(--text-secondary);
  text-align: right;
}

.storage-note {
  margin: var(--space-1) 0 0;
  font-size: var(--font-sm);
  line-height: 1.5;
  color: var(--text-muted);
}

/* 清理是破坏性动作，和「关闭」分列两端：原来它挂在卡片头部右上角，没有相邻按钮，
   挪到弹窗底部后如果紧挨「关闭」，误点的代价是一次不可恢复的删除。 */
.storage-foot {
  display: flex;
  justify-content: space-between;
  gap: var(--space-2);
}
</style>
