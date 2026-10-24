<template>
  <n-modal v-model:show="show" preset="dialog" title="WiFi 设置" class="modal-fit" style="--modal-w: 440px">
    <!-- 窄屏（≤768px）标签改顶置：left + label-width 90 在 360px 视口下给输入框只剩
         ~200px（弹窗 440 → 实际 336，扣掉 90 标签与内距），SSID/密码看得见一半。 -->
    <n-form :label-placement="isMobile ? 'top' : 'left'" label-width="90">
      <n-form-item label="SSID">
        <n-input v-model:value="wifiForm.ssid" />
      </n-form-item>
      <n-form-item label="加密方式">
        <!-- size 跟随本弹窗其它控件（这里是默认尺寸，与相邻 n-input 对齐），
             **不与仪表盘 WifiModal 的 size="small" 对齐** —— 不是漏改。选项来源两边共用。 -->
        <n-select v-model:value="wifiForm.auth_mode" :options="authModeOptions" />
      </n-form-item>
      <!-- 开放网络没有密码可填：这一项隐藏，提交时也不带 passphrase（见 buildWifiConfigPayload）。
           留着一个填了没用的输入框，等于告诉用户"密码生效了" —— 那正是假开关。 -->
      <n-form-item v-if="needsPassphrase" label="密码">
        <n-input v-model:value="wifiForm.password" type="password" show-password-on="click" placeholder="8~63 位" />
      </n-form-item>
      <n-form-item label="最大连接数">
        <!-- 上限 10：用户对中兴 F50 的规格结论（2026-09-22），与 core 的 validateApConfig（1~10）一致。
             2026-09-22 用户裁决改用下拉：十选一的值域由选项本身守，用户打不出 99
             （原 n-input-number 的 :max 只在用步进器时硬夹，手打仍会落进 form）。
             首项「保持不变」的控件取值是哨兵 0（不是 null）：n-select 对 null 一律判「无选中」，
             那一档就不会高亮打勾；0 本来就被 isWifiMaxStaNumAcceptable 判为不合法，
             过报文边界时由 wifiMaxStaNumForPayload 折成 null（= 不下发 max_sta_num）。
             选项与文案来自 contract，两个弹窗共用一份。永远有选中项，所以不需要 placeholder。 -->
        <n-select v-model:value="wifiForm.maxStaNum" :options="maxStaNumOptions" />
      </n-form-item>
      <n-form-item label="频段">
        <!-- 选项（含要发出去的 chip1/chip2）来自 contract.WifiBandOptions：
             界面写「2.4 GHz」，线上发的必须是设备词汇 chip1/chip2。
             频段是独立动作：走 POST /api/wifi/band，会重启 WiFi 模块，所以保存时单独确认（见 saveWifi）。
             2026-09-22 用户裁决：由一排 n-radio 改为下拉，弹窗内只留一种选择交互 —— 只换控件，逻辑不动。 -->
        <n-select v-model:value="wifiForm.band" :options="bandOptions" />
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
import { useDialog, useMessage } from 'naive-ui';
import { getApiClient } from '@/composables/useApi';
import { useIsMobile } from '@/composables/useIsMobile';
import {
  WIFI_AUTH_MODE_DEFAULT,
  WIFI_MAX_STA_NUM_KEEP_LABEL,
  WIFI_MAX_STA_NUM_KEEP_VALUE,
  WifiBandOptions,
  WifiMaxStaNumOptions,
  WifiMaxStaNumRange,
  buildWifiConfigPayload,
  isWifiMaxStaNumAcceptable,
  wifiBandFromChipIndex,
  wifiMaxStaNumForPayload,
  wifiSecurityNeedsPassphrase,
  wifiSecurityOptions,
} from '@/api/contract';
import type { WifiBand } from '@/api/contract';
import type { WifiSettings } from '@/types';

const props = defineProps<{ show: boolean; wifiSettings: WifiSettings | null }>();
const emit = defineEmits<{
  'update:show': [boolean];
  saved: [];
  /**
   * 2026-10-14：换频段是一次 A 级操作（下发 + 13 次回读确认），执行体在
   * `useNetworkControls.switchWifiBand`，本组件只把目标频段交上去（理由见 submit 里的注释）。
   * 只传频段、不传 SSID：判据只认生效频段（设备每个 chip 的 AP 各有一份配置，切过去之后
   * 回读到的 SSID 是目标 chip 那个 AP 自己的，拿它比对必然对不上 —— 见 composable 里的说明）。
   */
  'switch-band': [chip: WifiBand];
}>();

const show = computed({
  get: () => props.show,
  set: (v) => emit('update:show', v),
});

const message = useMessage();
const dialog = useDialog();
const api = getApiClient();
/** 与 CSS 的 768 档同一判据（见模板里 label-placement 的说明） */
const isMobile = useIsMobile();

const wifiForm = reactive({
  ssid: '',
  password: '',
  auth_mode: WIFI_AUTH_MODE_DEFAULT,
  /**
   * 选择器控件层取值：`WIFI_MAX_STA_NUM_KEEP_VALUE`（0）= 「保持不变」= 提交时不带 max_sta_num。
   * 报文边界上由 wifiMaxStaNumForPayload 折成 null，0 不会离开这个组件。
   */
  maxStaNum: WIFI_MAX_STA_NUM_KEEP_VALUE as number,
  /** 设备词汇：chip1 = 2.4G、chip2 = 5G。要发出去的就是这个值（见 contract.WifiBands） */
  band: 'chip1' as WifiBand,
  broadcastHidden: false,
});
const wifiSaving = ref(false);

// 选项来源只有 contract 那两份常量（取值域，readonly）。这里各浅拷贝一次：
// naive-ui 的 `n-select :options` 形参是 `SelectMixedOption[]`，readonly 数组会报 TS4104。
// 拷贝放在 setup 而不是模板表达式里 —— 写成 `:options="[...X]"` 会每次渲染都重建 treemate。
const maxStaNumOptions = [...WifiMaxStaNumOptions];
const bandOptions = [...WifiBandOptions];

// 加密方式的 4 个档位与 auth_mode/encryp_type 的配对关系是设备契约，统一放在 contract.ts；
// 设备回读的写法不在表里时由 wifiSecurityOptions 置顶并入，避免 select 空白 / 顺手改坏
const authModeOptions = computed(() => wifiSecurityOptions(props.wifiSettings?.auth_mode));
const needsPassphrase = computed(() => wifiSecurityNeedsPassphrase(wifiForm.auth_mode));

/**
 * 设备当前频段。读侧只有展示字段 `chip_index`（'1'/'2'），翻成写侧取值域由 contract 负责。
 * 「有没有变」按**设备现值**判，不按打开弹窗那一刻的快照：期间被回读刷新过也不会误发。
 */
const currentBand = computed(() => wifiBandFromChipIndex(props.wifiSettings?.chip_index));

// 打开弹窗时按当前设备配置回填表单
watch(
  () => props.show,
  (v) => {
    if (!v) return;
    const s = props.wifiSettings;
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
    wifiForm.band = currentBand.value;
    wifiForm.broadcastHidden = s?.broadcast_disabled === 1;
  },
  { immediate: true }
);

async function saveWifi() {
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
  // 频段没动就直接保存：切频段会踢掉所有 WiFi 客户端，不能每次保存 SSID 都顺带来一次
  if (wifiForm.band === currentBand.value) {
    await submit(false);
    return;
  }
  const label = WifiBandOptions.find((o) => o.value === wifiForm.band)?.label ?? wifiForm.band;
  dialog.warning({
    title: '切换 WiFi 频段',
    content:
      `将把 WiFi 切换到 ${label}。这条命令会重启设备的 WiFi 模块并在该频段上打开 WiFi，` +
      '当前通过 WiFi 连接的设备（包括你自己）会短暂断开，需要重新连接。' +
      '其余改动（SSID / 密码等）会在切换前一起保存。',
    positiveText: '切换并保存',
    negativeText: '暂不执行',
    onPositiveClick: () => submit(true),
    // 「暂不执行」= 一个字段都不发。半套下发（存了 SSID 却没换频段）会让弹窗上的频段
    // 显示成一个设备并不在用的值 —— 那就是假开关。
    onNegativeClick: () => message.info('已取消，本次没有修改任何设置'),
  });
}

/**
 * 真正下发。顺序是先 `/api/wifi/config` 再 `/api/wifi/band`：
 * 换频段会重启 WiFi 模块并顶掉连接，把它放最后，SSID/密码这些才有机会先落到设备上。
 *
 * 2026-10-14：`/api/wifi/band` 不再由本组件直发 —— 它改走父组件的 A 级确认器
 * （`useNetworkControls.switchWifiBand`），所以这里只负责"发配置 + 把频段切换交给父组件"。
 * 原因见下面那段注释与 [emit('switch-band')]。
 */
async function submit(switchBand: boolean) {
  wifiSaving.value = true;
  try {
    // 报文由 buildWifiConfigPayload 统一拼（passphrase 的 OPEN 特例、encryp_type 的
    // 配对/透传、broadcast_disabled 的方向都在那里，两个 WiFi 表单共用同一份规则）。
    await api.post(
      '/api/wifi/config',
      buildWifiConfigPayload({
        ssid: wifiForm.ssid,
        authMode: wifiForm.auth_mode,
        passphrase: wifiForm.password,
        // 控件层的哨兵 0（「保持不变」）在这里折成 null = 不下发 max_sta_num。
        // 折算只有 contract.wifiMaxStaNumForPayload 一处，0 不会越过这个边界。
        maxStaNum: wifiMaxStaNumForPayload(wifiForm.maxStaNum),
        hidden: wifiForm.broadcastHidden,
        fallbackEncrypType: props.wifiSettings?.encryp_type,
      })
    );
  } catch (e: any) {
    // 取值域非法时 core 回 400 + 原因，直接把原因显示出来，别压成一句「保存失败」
    message.error(e?.response?.data?.error || '保存失败');
    return;
  } finally {
    wifiSaving.value = false;
  }

  if (!switchBand) {
    message.success('WiFi 设置已保存');
    show.value = false;
    // 设备写入到查询接口可见有延迟，二维码按当前 SSID/密码实时生成，配置一改旧图就是错的：
    // 由父组件在 saved 事件里统一回读设置 + 丢弃旧二维码缓存并视情况重拉。
    // 这一支**不需要** A 级：`/api/wifi/config` 是 AP 配置表的普通写入，不重启模块
    // （会重启模块的是 `/api/wifi/band`，见下）。
    emit('saved');
    return;
  }

  /*
   * 2026-10-14 修（审计 G2：切频段没有确认器）。
   *
   * 改前：这里 `await api.post('/api/wifi/band')` 成功后 `emit('saved')` →
   * `NetworkView.handleWifiSaved` → `onWifiSaved`，那边等 600ms **单读一次**。
   * core 写成功确实清了读缓存（`WifiRoutes.kt:199-208` 的 `invalidateWifi()`，所以回读真的打设备），
   * 但设备侧这条命令会**重启 WiFi 模块**（`GoformWifiClient.kt:490-521`，语义是"在该频段上启用 WiFi"），
   * 600ms 时模块还没起来 —— 回读到的还是旧频段/旧 SSID，界面要等下一轮 10s 轮询才自己变对。
   *
   * 现在：把这次切换交给父组件，由 A 级入口 `useNetworkControls.switchWifiBand` 下发并
   * 按 contract 的 `NetworkModeSwitchProbe` 预算轮询回读生效频段（判据与理由见那个入口的注释：
   * 只认归一后的频段，不含 SSID —— 设备每个 chip 的 AP 各有一份配置）。
   * 本组件**先关窗再上交**（同制式切换的 applyModeAndClose）：A 级回读最长 30.6s，
   * 不该让配置弹窗空转，进度与结论由全局进度弹窗呈现。
   * 组件自己不持有那个 A 级入口：`useNetworkControls()` 每次调用都会新建一套页面级 ref，
   * 弹窗里再调一次就会多一份订阅（见 useNetworkControls 文件头），所以动作一律从父组件来。
   */
  show.value = false;
  emit('switch-band', wifiForm.band);
}
</script>
