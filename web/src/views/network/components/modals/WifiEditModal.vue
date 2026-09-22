<template>
  <n-modal v-model:show="show" preset="dialog" title="WiFi 设置" style="width: 440px; max-width: calc(100vw - 32px)">
    <n-form label-placement="left" label-width="90">
      <n-form-item label="SSID">
        <n-input v-model:value="wifiForm.ssid" />
      </n-form-item>
      <n-form-item label="加密方式">
        <n-select v-model:value="wifiForm.auth_mode" :options="authModeOptions" />
      </n-form-item>
      <!-- 开放网络没有密码可填：这一项隐藏，提交时也不带 passphrase（见 buildWifiConfigPayload）。
           留着一个填了没用的输入框，等于告诉用户"密码生效了" —— 那正是假开关。 -->
      <n-form-item v-if="needsPassphrase" label="密码">
        <n-input v-model:value="wifiForm.password" type="password" show-password-on="click" placeholder="8~63 位" />
      </n-form-item>
      <n-form-item label="最大连接数">
        <!-- 上限 10：用户对中兴 F50 的规格结论（2026-09-22），与 core 的 validateApConfig（1~10）一致。
             :max 只在用步进器时硬夹，手打 99 仍会落进 form —— 提交守门在 isWifiMaxStaNumAcceptable。 -->
        <n-input-number
          v-model:value="wifiForm.maxStaNum"
          :min="WifiMaxStaNumRange.min"
          :max="WifiMaxStaNumRange.max"
          :precision="0"
          placeholder="留空 = 不修改"
        />
      </n-form-item>
      <n-form-item label="频段">
        <n-radio-group v-model:value="wifiForm.chip_index">
          <n-radio value="1">2.4 GHz</n-radio>
          <n-radio value="2">5 GHz</n-radio>
        </n-radio-group>
      </n-form-item>
      <n-form-item label="隐藏 SSID">
        <!-- 开=隐藏（broadcast_disabled=1），关=广播。别按"广播开关"理解，值是反的 -->
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
import {
  WIFI_AUTH_MODE_DEFAULT,
  WifiMaxStaNumRange,
  buildWifiConfigPayload,
  isWifiMaxStaNumAcceptable,
  wifiSecurityNeedsPassphrase,
  wifiSecurityOptions,
} from '@/api/contract';
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
  auth_mode: WIFI_AUTH_MODE_DEFAULT,
  /** null = 留空，语义是「不修改」 */
  maxStaNum: null as number | null,
  chip_index: '1',
  broadcastHidden: false,
});
const wifiSaving = ref(false);

// 加密方式的 4 个档位与 auth_mode/encryp_type 的配对关系是设备契约，统一放在 contract.ts；
// 设备回读的写法不在表里时由 wifiSecurityOptions 置顶并入，避免 select 空白 / 顺手改坏
const authModeOptions = computed(() => wifiSecurityOptions(props.wifiSettings?.auth_mode));
const needsPassphrase = computed(() => wifiSecurityNeedsPassphrase(wifiForm.auth_mode));

// 打开弹窗时按当前设备配置回填表单
watch(
  () => props.show,
  (v) => {
    if (!v) return;
    const s = props.wifiSettings;
    wifiForm.ssid = s?.ssid || '';
    wifiForm.password = s?.password || '';
    wifiForm.auth_mode = s?.auth_mode || WIFI_AUTH_MODE_DEFAULT;
    // 0 / NaN 都当「读不到」处理：填 0 进去会在保存时下发一个不合法的最大连接数
    wifiForm.maxStaNum = Number(s?.max_sta_num) > 0 ? Number(s?.max_sta_num) : null;
    wifiForm.chip_index = s?.chip_index || '1';
    wifiForm.broadcastHidden = s?.broadcast_disabled === 1;
  },
  { immediate: true }
);

async function saveWifi() {
  // 拦下来要说原因：静默丢掉这一项会变成「填了 0 点保存，提示成功，设备没变」
  if (!isWifiMaxStaNumAcceptable(wifiForm.maxStaNum)) {
    message.warning(
      `最大连接数必须是 ${WifiMaxStaNumRange.min}~${WifiMaxStaNumRange.max} 的整数（中兴 F50 最大支持 10 个），留空表示不修改`
    );
    return;
  }
  wifiSaving.value = true;
  try {
    // 报文由 buildWifiConfigPayload 统一拼（passphrase 的 OPEN 特例、encryp_type 的
    // 配对/透传、broadcast_disabled 的方向都在那里，两个 WiFi 表单共用同一份规则）。
    // chip_index 在写接口里是 "0"/"1"（chip1=2.4G → "0"，chip2=5G → "1"）
    await api.post(
      '/api/wifi/config',
      buildWifiConfigPayload({
        ssid: wifiForm.ssid,
        authMode: wifiForm.auth_mode,
        passphrase: wifiForm.password,
        maxStaNum: wifiForm.maxStaNum,
        hidden: wifiForm.broadcastHidden,
        chipIndex: wifiForm.chip_index === '2' ? '1' : '0',
        fallbackEncrypType: props.wifiSettings?.encryp_type,
      })
    );
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
