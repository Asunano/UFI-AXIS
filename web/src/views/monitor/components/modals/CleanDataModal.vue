<template>
  <n-modal
    :show="show"
    preset="dialog"
    title="清理监控数据"
    positive-text="确认清理"
    negative-text="取消"
    style="width: 400px"
    @update:show="emit('update:show', $event)"
    @positive-click="doClean"
  >
    <div class="clean-form">
      <p>清理指定天数之前的历史数据：</p>
      <div class="clean-input-row">
        <span>保留最近</span>
        <n-input-number v-model:value="cleanDays" :min="1" :max="365" size="small" style="width: 120px" />
        <span>天的数据</span>
      </div>
      <p class="clean-hint">
        将清理 CPU / 内存 / 流量 / 信号 / 电池 / 告警 六张表；短信记录（sms_records）永久保留，不参与清理。<br />
        超出范围的数据将被永久删除，不可恢复。
      </p>
    </div>
  </n-modal>
</template>

<script setup lang="ts">
/**
 * 清理监控历史。
 *
 * 天数自持：弹窗关闭即销毁（n-modal 默认 display-directive="if"），下次打开回到
 * 默认 7 天 —— 这里正是想要的行为，删除操作不该记住上次填的值。
 *
 * 清理成功后 emit('cleaned') 而不是自己重新取数：被删的历史同时影响 6 张图和存储表，
 * 统一交给页面级的 loadAll。
 */
import { ref } from 'vue';
import { useMessage } from 'naive-ui';
import { useCancellableApi } from '@/composables/useCancellableApi';

defineProps<{ show: boolean }>();
const emit = defineEmits<{ 'update:show': [boolean]; cleaned: [] }>();

const message = useMessage();
const api = useCancellableApi();

const cleanDays = ref(7);

/** 返回 false 会让 naive 保持弹窗打开 —— 失败时不关，用户可以重试 */
async function doClean(): Promise<boolean> {
  try {
    const { data } = await api.post('/api/monitor/clean', {
      type: 'all',
      days: cleanDays.value,
    });
    // core 返回 deleted: { 表名: 条数 }
    const deleted =
      data?.deleted && typeof data.deleted === 'object'
        ? Object.values(data.deleted as Record<string, number>).reduce((a, b) => a + (Number(b) || 0), 0)
        : 0;
    message.success(`已清理 ${deleted} 条记录（${data?.cutoff_days ?? cleanDays.value} 天前）`);
    emit('cleaned');
    return true;
  } catch {
    message.error('清理失败');
    return false;
  }
}
</script>

<style scoped>
.clean-form p {
  margin: 0 0 12px;
  font-size: 14px;
  color: var(--text-primary);
}

.clean-input-row {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 14px;
  color: var(--text-secondary);
}

.clean-hint {
  font-size: 12px;
  color: var(--text-muted);
}
</style>
