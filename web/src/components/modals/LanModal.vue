<template>
  <n-modal v-model:show="show" preset="card" title="LAN / DHCP 设置" class="modal-fit" style="--modal-w: 560px">
    <n-spin :show="loading">
      <InfoRow label="LAN IP" :value="settings.lan_ip || '--'" />
      <InfoRow label="子网掩码" :value="settings.lan_netmask || '--'" />
      <InfoRow label="MAC 地址" :value="settings.mac_address || '--'" />
      <!--
        2026-10-14 修复：原来直接 `dhcp_enabled ? '已开启' : '已关闭'` —— 加载失败时
        `settings` 保持初值（false），界面就**确定地**显示"已关闭"，而它其实只是"没读到"。
        本卡其余各行用 `|| '--'` 已经把未知渲染成 `--`，只有这一行在伪造状态，本次对齐。
      -->
      <InfoRow label="DHCP 服务" :value="!loaded ? '--' : settings.dhcp_enabled ? '已开启' : '已关闭'" />
      <InfoRow label="DHCP 起始" :value="settings.dhcp_start || '--'" />
      <InfoRow label="DHCP 结束" :value="settings.dhcp_end || '--'" />
      <InfoRow label="租约时间" :value="leaseText" />
      <InfoRow label="MTU" :value="settings.mtu ?? '--'" />
    </n-spin>

    <template #footer>
      <div class="modal-footer">
        <n-button size="small" @click="show = false">关闭</n-button>
        <n-button size="small" :loading="loading" @click="load">刷新</n-button>
        <n-button size="small" type="primary" :loading="saving" @click="showEdit = true">编辑</n-button>
      </div>
    </template>

    <!-- 编辑表单沿用原来的 LanEditModal（它本来就是弹窗），不在本文件重写一份 -->
    <LanEditModal v-model:show="showEdit" :lan-settings="settings" @save="save" />
  </n-modal>
</template>

<script setup lang="ts">
/**
 * LAN / DHCP 弹窗（2026-10-14 从 `NetworkTab` 的「LAN / DHCP 设置」整卡搬进弹窗）。
 *
 * 只读 KV + 一个「编辑」按钮，点编辑再开原来的 `LanEditModal` —— 两层弹窗是这个仓库
 * 既有的做法（网络页的 WiFi 卡也是「卡上只读 + 编辑弹窗」），没有新造范式。
 */
import { ref, reactive, computed, watch } from 'vue';
import { useMessage } from 'naive-ui';
import { useCancellableApi } from '@/composables/useCancellableApi';
import { normalizeLanSettings } from '@/composables/utils';
import InfoRow from '@/components/InfoRow.vue';
import LanEditModal from './LanEditModal.vue';

const props = defineProps<{ show: boolean }>();
const emit = defineEmits<{ 'update:show': [boolean] }>();

const show = computed({
  get: () => props.show,
  set: (v) => emit('update:show', v),
});

const message = useMessage();
const api = useCancellableApi();

// 设备 goform 写入成功到「查询能读到新值」之间存在延迟，零延迟回读会拿到旧值。600ms 对齐 Android app。
const GOFORM_SETTLE_MS = 600;
const settleAfterWrite = () => new Promise((resolve) => setTimeout(resolve, GOFORM_SETTLE_MS));

const loading = ref(false);
const saving = ref(false);
const showEdit = ref(false);
/** 是否**成功**读到过一次 LAN 配置。false 时 DHCP 那行显示 `--` 而不是伪造"已关闭"。 */
const loaded = ref(false);
const settings = reactive({
  lan_ip: '',
  lan_netmask: '',
  mac_address: '',
  dhcp_enabled: false,
  dhcp_start: '',
  dhcp_end: '',
  dhcp_lease_sec: 0,
  mtu: null as number | null,
});

const leaseText = computed(() => {
  const s = settings.dhcp_lease_sec;
  if (!s) return '--';
  return s % 3600 === 0 ? `${s / 3600} 小时` : `${s} 秒`;
});

async function load() {
  loading.value = true;
  try {
    const { data } = await api.get('/api/device/lan-settings');
    Object.assign(settings, normalizeLanSettings(data));
    loaded.value = true;
  } catch {
    /* 静默：loaded 保持原值，DHCP 那行继续显示 `--` 而不是伪造状态 */
  } finally {
    loading.value = false;
  }
}

async function save(payload: Record<string, any>) {
  saving.value = true;
  let posted = false;
  try {
    await api.post('/api/device/dhcp', {
      lan_ip: payload.lan_ip,
      lan_netmask: payload.lan_netmask,
      dhcp_type: payload.dhcp_enabled ? 'SERVER' : 'DISABLE',
      dhcp_start: payload.dhcp_start,
      dhcp_end: payload.dhcp_end,
      dhcp_lease: String((payload.dhcp_lease_hour || 24) * 3600),
    });
    posted = true;
    message.success('LAN / DHCP 设置已保存');
  } catch {
    message.error('保存失败');
  } finally {
    // 不把表单值当成设备真实状态，回读一次（goform 可能规范化或拒绝部分字段）；
    // 成功后必须等设备侧生效，立即回读会读回旧的 LAN 配置
    if (posted) await settleAfterWrite();
    await load();
    saving.value = false;
  }
}

watch(
  () => props.show,
  (v) => {
    if (v) load();
  },
  { immediate: true }
);
</script>

<style scoped>
.modal-footer {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}
</style>
