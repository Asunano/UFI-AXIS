<template>
  <n-modal
    v-model:show="show"
    preset="card"
    title="设备后台原始数据"
    class="modal-fit"
    style="--modal-w: 720px; --modal-h: 720px"
    @after-leave="clearData"
  >
    <p class="danger-desc">
      这是设备后台的完整快照，可能包含 IMSI / ICCID / MSISDN / WiFi 密码等敏感信息，截图或分享前请自行脱敏。
    </p>
    <n-spin :show="loading">
      <div v-if="data">
        <n-button size="tiny" text style="margin-bottom: 8px" @click="expanded = !expanded">
          {{ expanded ? '收起' : '展开' }}
        </n-button>
        <pre v-show="expanded" class="goform-pre">{{ JSON.stringify(data, null, 2) }}</pre>
      </div>
      <n-empty v-else-if="!loading" description="点击刷新按钮获取设备后台数据" />
    </n-spin>
    <template #footer>
      <div class="modal-footer">
        <n-button size="small" @click="show = false">关闭</n-button>
        <n-button size="small" type="primary" :loading="loading" @click="loadGoform">刷新</n-button>
      </div>
    </template>
  </n-modal>
</template>

<script setup lang="ts">
/**
 * 设备后台原始数据弹窗（2026-10-14 从 `AdvancedTab` 的「设备后台原始数据」整卡搬进弹窗）。
 *
 * 这一项最该收进弹窗：它是**按需加载**的重内容（一大坨 JSON），放在页面上既占高度、
 * 又容易让人误点「加载」。改成入口之后，只有真想看原始字段的人才会打开它。
 * 敏感信息警告保留（原文案）。
 */
import { ref, computed, watch } from 'vue';
import { useMessage } from 'naive-ui';
import { useCancellableApi } from '@/composables/useCancellableApi';

const props = defineProps<{ show: boolean }>();
const emit = defineEmits<{ 'update:show': [boolean] }>();

const show = computed({
  get: () => props.show,
  set: (v) => emit('update:show', v),
});

const message = useMessage();
const api = useCancellableApi();

const loading = ref(false);
const data = ref<Record<string, any> | null>(null);
const expanded = ref(false);

async function loadGoform() {
  loading.value = true;
  try {
    const res = await api.get('/api/device/goform');
    data.value = res.data;
    message.success('设备后台数据已加载');
  } catch (e: any) {
    // 403 = core 侧 goform_dump_enabled 默认关闭（不是鉴权失败，不会被拦截器踢去登录页）
    if (e?.response?.status === 403) {
      // 文案指向开关所在的分栏名，而不是配置键名：键名是内部契约，用户在界面上找不到它。
      message.warning('设备原始字段 dump 已关闭，可在「设置 › 通用」里打开');
    } else {
      message.error('加载设备后台数据失败');
    }
  } finally {
    loading.value = false;
  }
}

/**
 * 退场动画真正结束后再丢弃快照。
 *
 * ★ 2026-10-22：清空时机从「show 变 false 的同一帧」挪到这里。
 * 原来一关就把 data 置 null，而 naive 的退场过渡还要跑约 200ms ——
 * 那 200ms 里用户看到的是一张**已经空了、还在淡出**的卡片
 * （与设置页「内容先消失、弹窗后消失」是同一类问题，见 SettingsView 的 renderKey 注释）。
 * 清空的目的没变（下次打开不要先闪出上一次的旧快照），只是改在退场结束之后做。
 */
function clearData() {
  data.value = null;
}

// 打开时自动拉一次（原页面是手动点「加载」，但既然已经是「点入口 → 弹窗」的语义，
// 再让用户点第二次没有意义）。关闭时**不**在这里清空，见上面的 clearData。
watch(
  () => props.show,
  (v) => {
    if (v) {
      expanded.value = false;
      loadGoform();
    }
  },
  { immediate: true }
);
</script>

<style scoped>
.danger-desc {
  font-size: 13px;
  color: var(--text-secondary);
  margin: 0 0 12px;
  line-height: 1.6;
}
.goform-pre {
  font-size: 11px;
  font-family: 'JetBrains Mono', 'Fira Code', monospace;
  white-space: pre-wrap;
  word-break: break-all;
  color: var(--text-primary);
  background: var(--page-bg);
  padding: 12px;
  border-radius: 8px;
  /* 不再自设 max-height：卡片高度已由 `.modal-fit` 的 --modal-h 封顶、内容区自己滚，
     这里再压一层就是「里外两条滚动条」。展开/收起仍由 v-show 控制，行为不变。 */
  overflow-y: auto;
  line-height: 1.5;
}
.modal-footer {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}
</style>
