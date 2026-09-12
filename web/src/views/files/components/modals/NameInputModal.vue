<template>
  <n-modal :show="show" preset="dialog" :title="title" style="width: 360px" @update:show="emit('update:show', $event)">
    <n-input v-model:value="value" :placeholder="placeholder" @keyup.enter="emit('confirm', value)" />
    <template #action>
      <n-button @click="emit('update:show', false)">取消</n-button>
      <n-button type="primary" :loading="loading" @click="emit('confirm', value)">{{ confirmText }}</n-button>
    </template>
  </n-modal>
</template>

<script setup lang="ts">
/**
 * 「输入一个名称并确认」的弹窗壳。新建文件夹 / 新建文件 / 重命名原本是三份逐字相同的
 * `n-modal preset="dialog"`，只差标题、占位符和确认按钮文案。
 *
 * 只做壳：**不校验、不请求**。合法性判断（非空、不含 `/`）留在页面的三个 handler 里 ——
 * 三者的提示文案本来就不完全一样，收进来反而要给壳加分支。
 *
 * 输入值自持，每次打开重置为 `initialValue`（重命名要预填当前文件名）。
 * 副作用：创建失败后关掉再打开，输入框会清空而不是保留上次输入 —— 弹窗关闭即销毁，
 * 这是可接受的取舍。
 */
import { ref, watch } from 'vue';

const props = withDefaults(
  defineProps<{
    show: boolean;
    title: string;
    placeholder?: string;
    confirmText?: string;
    loading?: boolean;
    initialValue?: string;
  }>(),
  {
    placeholder: '',
    confirmText: '确定',
    loading: false,
    initialValue: '',
  }
);

const emit = defineEmits<{
  (e: 'update:show', v: boolean): void;
  (e: 'confirm', value: string): void;
}>();

const value = ref(props.initialValue);

watch(
  () => props.show,
  (v) => {
    if (v) value.value = props.initialValue;
  },
  { immediate: true }
);
</script>
