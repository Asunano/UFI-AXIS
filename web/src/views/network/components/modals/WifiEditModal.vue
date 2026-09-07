<template>
  <n-modal
    v-model:show="show"
    preset="dialog"
    title="WiFi 设置"
    style="width: 440px; max-width: calc(100vw - 32px)"
  >
    <n-form label-placement="left" label-width="80">
      <n-form-item label="SSID">
        <n-input v-model:value="wifiForm.ssid" />
      </n-form-item>
      <n-form-item label="密码">
        <n-input v-model:value="wifiForm.password" type="password" show-password-on="click" />
      </n-form-item>
      <n-form-item label="加密方式">
        <n-select v-model:value="wifiForm.auth_mode" :options="authModeOptions" />
      </n-form-item>
      <n-form-item label="频段">
        <n-radio-group v-model:value="wifiForm.chip_index">
          <n-radio value="1">2.4 GHz</n-radio>
          <n-radio value="2">5 GHz</n-radio>
        </n-radio-group>
      </n-form-item>
      <n-form-item label="隐藏 SSID">
        <n-switch v-model:value="wifiForm.broadcastHidden" />
      </n-form-item>
    </n-form>
    <template #action>
      <n-button @click="show = false">取消</n-button>
      <n-button type="primary" :loading="wifiSaving" @click="saveWifi">保存</n-button>
    </template>
  </n-modal>
</template>

<script setup lang="ts">
import { reactive, ref, computed, watch } from 'vue';
import { useMessage } from 'naive-ui';
import { getApiClient } from '@/composables/useApi';
import type { WifiSettings } from '@/types';

const props = defineProps<{ show: boolean; wifiSettings: WifiSettings | null }>();
const emit = defineEmits<{ 'update:show': [boolean]; saved: [] }>();

const show = computed({
  get: () => props.show,
  set: (v) => emit('update:show', v),
});

const message = useMessage();
const api = getApiClient();

const wifiForm = reactive({
  ssid: '',
  password: '',
  auth_mode: 'WPA2PSK',
  chip_index: '1',
  broadcastHidden: false,
});
const wifiSaving = ref(false);

// core /api/wifi/config 只对 OPEN 特判（强制 EncrypType=NONE），其余 auth_mode 原样透传给
// goform AuthMode，缺省写 "WPA2PSK"。设备回读值写法可能不同，
// 动态并入当前值，避免 select 显示空白、以及保存时把设备现有加密方式改坏。
const authModeOptions = computed(() => {
  const base = ['WPA2PSK', 'WPAPSK', 'OPEN'];
  const cur = props.wifiSettings?.auth_mode;
  const values = cur && !base.includes(cur) ? [cur, ...base] : base;
  return values.map((v) => ({ label: v === 'OPEN' ? '开放（无密码）' : v, value: v }));
});

// 打开弹窗时按当前设备配置回填表单
watch(
  () => props.show,
  (v) => {
    if (!v) return;
    const s = props.wifiSettings;
    wifiForm.ssid = s?.ssid || '';
    wifiForm.password = s?.password || '';
    wifiForm.auth_mode = s?.auth_mode || 'WPA2PSK';
    wifiForm.chip_index = s?.chip_index || '1';
    wifiForm.broadcastHidden = s?.broadcast_disabled === 1;
  },
  { immediate: true }
);

async function saveWifi() {
  wifiSaving.value = true;
  try {
    // core WifiRoutes.kt:61-74 读的是 passphrase（不是 password）；
    // encryp_type / max_sta_num 不传时 core 会把 EncrypType 硬写成 CCMP、
    // 且完全不下发 ApMaxStationNumber，所以带上设备当前值，避免保存 SSID 顺手改掉加密方式与最大连接数。
    // ChipIndex 为 "0"/"1"（chip1=2.4G, chip2=5G）
    const cur = props.wifiSettings;
    await api.post('/api/wifi/config', {
      ssid: wifiForm.ssid,
      passphrase: wifiForm.password,
      auth_mode: wifiForm.auth_mode,
      encryp_type: cur?.encryp_type || undefined,
      max_sta_num: cur?.max_sta_num || undefined,
      chip_index: wifiForm.chip_index === '2' ? '1' : '0',
      broadcast_disabled: wifiForm.broadcastHidden ? 1 : 0,
    });
    message.success('WiFi 设置已保存');
    show.value = false;
    // 设备写入到查询接口可见有延迟，二维码按当前 SSID/密码实时生成，配置一改旧图就是错的：
    // 由父组件在 saved 事件里统一回读设置 + 丢弃旧二维码缓存并视情况重拉
    emit('saved');
  } catch {
    message.error('保存失败');
  } finally {
    wifiSaving.value = false;
  }
}
</script>
