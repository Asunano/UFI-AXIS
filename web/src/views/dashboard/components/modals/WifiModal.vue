<template>
  <n-modal
    :show="show"
    preset="card"
    title="WiFi 设置"
    style="width: 460px; max-width: calc(100vw - 32px)"
    @update:show="(v: boolean) => emit('update:show', v)"
  >
    <div class="modal-form-col">
      <div class="form-item">
        <n-switch v-model:value="wifiForm.enabled" />
        <span class="form-inline-label">启用 WiFi</span>
      </div>
      <div class="form-item">
        <label>SSID</label>
        <n-input v-model:value="wifiForm.ssid" placeholder="网络名称" size="small" />
      </div>
      <div class="form-item">
        <label>加密方式</label>
        <!-- size 跟随本弹窗其它控件（这里是 small，与相邻 n-input 对齐），
             **不与网络页 WifiEditModal 的默认尺寸对齐** —— 不是漏改。选项来源两边共用。 -->
        <n-select v-model:value="wifiForm.auth_mode" :options="authModeOptions" size="small" />
      </div>
      <!-- 开放网络没有密码可填：这一项隐藏，提交时也不带 passphrase（见 buildWifiConfigPayload） -->
      <div v-if="needsPassphrase" class="form-item">
        <label>密码</label>
        <n-input
          v-model:value="wifiForm.password"
          type="password"
          show-password-on="click"
          placeholder="8~63 位"
          size="small"
        />
      </div>
      <div class="form-item">
        <label>最大连接数</label>
        <!-- 上限 10：用户对中兴 F50 的规格结论（2026-09-22），与 core 的 validateApConfig（1~10）一致。
             2026-09-22 用户裁决改用下拉：十选一的值域由选项本身守，用户打不出 99
             （原 n-input-number 的 :max 只在用步进器时硬夹，手打仍会落进 form）。
             首项「保持不变」的控件取值是哨兵 0（不是 null）：n-select 对 null 一律判「无选中」，
             那一档就不会高亮打勾；0 本来就被 isWifiMaxStaNumAcceptable 判为不合法，
             过报文边界时由 wifiMaxStaNumForPayload 折成 null（= 不下发 max_sta_num）。
             选项与文案来自 contract，与网络页 WifiEditModal 共用同一份，不各写一份 1..10。
             永远有选中项，所以不需要 placeholder。
             本弹窗**没有频段控件**（频段是独立动作，走网络页的 WifiEditModal），不要在这里加。 -->
        <n-select v-model:value="wifiForm.maxStaNum" :options="maxStaNumOptions" size="small" />
      </div>
      <div class="form-item">
        <!-- 开=隐藏（broadcast_disabled=1），关=广播。别按"广播开关"理解，值是反的 -->
        <n-switch v-model:value="wifiForm.broadcastHidden" />
        <span class="form-inline-label">隐藏 SSID（不广播）</span>
      </div>
    </div>
    <template #footer>
      <n-space justify="end">
        <n-button size="small" @click="emit('update:show', false)">取消</n-button>
        <n-button size="small" type="primary" :loading="saving" @click="onSave">保存</n-button>
      </n-space>
    </template>
  </n-modal>
</template>

<script setup lang="ts">
import { ref, reactive, computed, watch } from 'vue';
import { useMessage } from 'naive-ui';
import {
  WIFI_AUTH_MODE_DEFAULT,
  WIFI_MAX_STA_NUM_KEEP_LABEL,
  WIFI_MAX_STA_NUM_KEEP_VALUE,
  WifiMaxStaNumOptions,
  WifiMaxStaNumRange,
  isWifiMaxStaNumAcceptable,
  wifiMaxStaNumForPayload,
  wifiSecurityNeedsPassphrase,
  wifiSecurityOptions,
} from '@/api/contract';
import type { WifiSettings } from '@/types';

const props = defineProps<{ show: boolean; wifiSettings: WifiSettings | null }>();
const emit = defineEmits<{ 'update:show': [boolean]; save: [payload: WifiSavePayload] }>();

const message = useMessage();
const saving = ref(false);

// 选项来源只有 contract 那一份常量（取值域，readonly），与网络页的 WifiEditModal 同一个来源。
// 这里浅拷贝一次：naive-ui 的 `n-select :options` 形参是 `SelectMixedOption[]`，
// readonly 数组会报 TS4104。拷贝放在 setup 而不是模板表达式里 ——
// 写成 `:options="[...X]"` 会每次渲染都重建 treemate。
const maxStaNumOptions = [...WifiMaxStaNumOptions];

const wifiForm = reactive({
  enabled: true,
  ssid: '',
  password: '',
  auth_mode: WIFI_AUTH_MODE_DEFAULT,
  /**
   * 选择器控件层取值：`WIFI_MAX_STA_NUM_KEEP_VALUE`（0）= 「保持不变」= 提交时不带 max_sta_num。
   * 往 save 事件外抛之前由 wifiMaxStaNumForPayload 折成 null，0 不会离开这个组件。
   */
  maxStaNum: WIFI_MAX_STA_NUM_KEEP_VALUE as number,
  broadcastHidden: false,
});

interface WifiSavePayload {
  enabled: boolean;
  ssid: string;
  password: string;
  auth_mode: string;
  maxStaNum: number | null;
  broadcastHidden: boolean;
  cur: WifiSettings | null;
}

// 加密方式的 4 个档位与 auth_mode/encryp_type 的配对关系是设备契约，统一放在 contract.ts；
// 设备回读的写法不在表里时由 wifiSecurityOptions 置顶并入，避免 select 空白 / 顺手改坏
const authModeOptions = computed(() => wifiSecurityOptions(props.wifiSettings?.auth_mode));
const needsPassphrase = computed(() => wifiSecurityNeedsPassphrase(wifiForm.auth_mode));

// 打开时把当前只读数据填入表单
watch(
  () => props.show,
  (v) => {
    if (!v) return;
    const s = props.wifiSettings;
    wifiForm.enabled = s?.enabled ?? true;
    wifiForm.ssid = s?.ssid || '';
    wifiForm.password = s?.password || '';
    wifiForm.auth_mode = s?.auth_mode || WIFI_AUTH_MODE_DEFAULT;
    // 读不到（0 / 空 / NaN）或设备报了个 1..10 之外的值，都落到「保持不变」档（哨兵 0）：
    // 不替固件猜一个数，也不把越界值当草稿带回去。落成哨兵而不是 null，是为了让下拉真正
    // **高亮打勾**在「保持不变」那一行（n-select 对 null 一律判无选中）。
    // 判定复用 contract 的 isWifiMaxStaNumAcceptable —— 这里传进去的一定是 number
    //（Number(undefined) = NaN、Number(null) = 0，都判 false），不会撞上它对 null 返回 true 的分支。
    const readMaxSta = Number(s?.max_sta_num);
    wifiForm.maxStaNum = isWifiMaxStaNumAcceptable(readMaxSta) ? readMaxSta : WIFI_MAX_STA_NUM_KEEP_VALUE;
    wifiForm.broadcastHidden = s?.broadcast_disabled === 1;
  },
  { immediate: true }
);

function onSave() {
  // 守门保留（**不许放宽**）：判据仍是 contract 的 isWifiMaxStaNumAcceptable，这里只额外放过
  // 「保持不变」档（哨兵 0 本来就不合法，但它的语义是「不下发」而不是「值填错了」）。
  // 下拉之后正常路径已经打不出越界值，这一层还挡着回填 / 契约漂移 —— 真拦下来时要说原因，
  // 静默丢掉一个用户看得见的值会变成「点了保存、提示成功、设备没变」。
  if (wifiForm.maxStaNum !== WIFI_MAX_STA_NUM_KEEP_VALUE && !isWifiMaxStaNumAcceptable(wifiForm.maxStaNum)) {
    message.warning(
      `最大连接数必须是 ${WifiMaxStaNumRange.min}~${WifiMaxStaNumRange.max} 的整数（中兴 F50 最大支持 10 个），选「${WIFI_MAX_STA_NUM_KEEP_LABEL}」表示不修改`
    );
    return;
  }
  saving.value = true;
  try {
    // 控件层的哨兵 0 在这里折成 null = 不下发 max_sta_num：`WifiSavePayload.maxStaNum`
    // 是 `number | null`，父组件拿到的已经是报文取值，0 不会越过这个边界。
    emit('save', {
      ...wifiForm,
      maxStaNum: wifiMaxStaNumForPayload(wifiForm.maxStaNum),
      cur: props.wifiSettings,
    });
    emit('update:show', false);
  } finally {
    saving.value = false;
  }
}
</script>

<style scoped>
.modal-form-col {
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: 4px 0;
}
.form-item {
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.form-item label {
  font-size: 12px;
  color: var(--text-secondary);
  font-weight: 500;
}
.form-inline-label {
  font-size: 13px;
  color: var(--text-primary);
  margin-left: 8px;
}
</style>
