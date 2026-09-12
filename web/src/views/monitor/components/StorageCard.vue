<template>
  <GridCard title="存储管理">
    <template #extra>
      <n-button size="small" type="warning" @click="cleanModal.open()">清理数据</n-button>
    </template>
    <n-spin :show="loading">
      <n-data-table :columns="columns" :data="data.tables" :bordered="false" :single-line="false" size="small" />
      <div class="storage-total">总占用: {{ data.total_display }}</div>
      <p class="storage-note">
        大小为按行估算值，非数据库实际占用；alert_records / sms_records 的记录数由后端固定返回，仅作占位。
      </p>
    </n-spin>

    <component
      :is="cleanModalComponent"
      v-if="cleanModalComponent"
      :show="cleanModalShow"
      @update:show="cleanModal.setShow"
      @cleaned="emit('cleaned')"
    />
  </GridCard>
</template>

<script setup lang="ts">
/**
 * 存储占用表格 + 清理入口。
 *
 * 数据仍由父组件取（loadAll 会连带刷新它，自动刷新也走同一条路），这里只负责渲染；
 * 清理弹窗挂在本组件下，因为触发按钮在这张卡的 #extra 里。清理成功后
 * emit('cleaned')，由父组件统一重新取数 —— 清掉的历史同时影响 6 张图，
 * 不能只刷新自己这张表。
 */
import type { DataTableColumns } from 'naive-ui';
import { useLazyModal } from '@/composables/useLazyModal';
import GridCard from '@/components/GridCard.vue';
// 类型放 monitorShared 而不是这里：`<script setup>` 里不允许 export
import type { StorageData } from '../monitorShared';

defineProps<{
  data: StorageData;
  loading: boolean;
}>();

const emit = defineEmits<{
  (e: 'cleaned'): void;
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
    render: (row) => (row as any).count?.toLocaleString() ?? '--',
  },
  {
    title: '大小',
    key: 'size_kb',
    width: 120,
    render: (row) => {
      const kb = (row as any).size_kb ?? 0;
      if (kb >= 1024) return (kb / 1024).toFixed(1) + ' MB';
      return kb.toFixed(1) + ' KB';
    },
  },
];
</script>

<style scoped>
.storage-total {
  margin-top: 12px;
  font-size: 13px;
  font-weight: 500;
  color: var(--text-secondary);
  text-align: right;
}

.storage-note {
  margin: 6px 0 0;
  font-size: 12px;
  line-height: 1.5;
  color: var(--text-muted);
}
</style>
