<template>
  <div class="tab-grid">
    <GridCard title="设备控制" :loading="controlLoading">
      <ToggleRow
        label="LED 指示灯"
        description="控制设备前面板 LED 指示灯的开关"
        :model-value="controlSettings.ledEnabled"
        :loading="toggleLoading.led"
        @update:model-value="(v: boolean) => postToggle('/api/device/led', v, 'led', 'ledEnabled')"
      />
      <ToggleRow
        label="性能模式"
        description="启用高性能模式，提升处理器运行频率"
        :model-value="controlSettings.performanceEnabled"
        :loading="toggleLoading.performance"
        @update:model-value="
          (v: boolean) => postToggle('/api/device/performance', v, 'performance', 'performanceEnabled')
        "
      />
      <ToggleRow
        label="Samba 共享"
        description="启用 Samba 文件共享服务，允许局域网访问存储"
        :model-value="controlSettings.sambaEnabled"
        :loading="toggleLoading.samba"
        @update:model-value="(v: boolean) => postToggle('/api/device/samba', v, 'samba', 'sambaEnabled')"
      />
      <ToggleRow
        label="USB 端口"
        description="对应设备 usb_port_switch，关闭后 USB 数据口停用"
        :model-value="controlSettings.usbPortEnabled"
        :loading="toggleLoading.usbPort"
        @update:model-value="(v: boolean) => postToggle('/api/device/debug', v, 'usbPort', 'usbPortEnabled')"
      />
      <ToggleRow
        label="定时重启"
        description="设置每日自动重启时间，保持系统稳定运行"
        :model-value="controlSettings.restartScheduleEnabled"
        :loading="toggleLoading.restartSchedule"
        @update:model-value="(v: boolean) => postRestartSchedule(v)"
      />
      <template #footer>
        <n-space>
          <n-button type="warning" :loading="actionLoading" @click="handleReboot">重启设备</n-button>
          <n-button type="error" :loading="actionLoading" @click="handleFactoryReset">恢复出厂</n-button>
        </n-space>
      </template>
    </GridCard>

    <!-- 定时重启时间设置 -->
    <GridCard v-if="controlSettings.restartScheduleEnabled" title="重启时间">
      <div class="form-grid">
        <div class="form-item">
          <label>每日重启时间</label>
          <n-input
            v-model:value="controlSettings.restartScheduleTime"
            placeholder="HH:MM"
            size="small"
            @blur="postRestartScheduleTime"
          />
        </div>
      </div>
    </GridCard>

    <!-- FOTA 空中升级 -->
    <GridCard title="FOTA 空中升级" :loading="controlLoading">
      <p class="action-desc">
        对应设备侧 goformId=SetUpgAutoSetting 的 UpgMode（1 = 开启自动检查更新，0 = 关闭）。
        关闭后设备不会自动检查/下载运营商固件更新。
      </p>
      <ToggleRow
        label="自动检查更新"
        description="开启后设备按运营商策略自动检查并下载固件更新"
        :model-value="controlSettings.fotaAutoUpdate"
        :loading="toggleLoading.fota"
        @update:model-value="(v: boolean) => setFotaAutoUpdate(v)"
      />
    </GridCard>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted } from 'vue';
import { useMessage, useDialog } from 'naive-ui';
import { useCancellableApi } from '@/composables/useCancellableApi';
import GridCard from '@/components/GridCard.vue';
import ToggleRow from '@/components/ToggleRow.vue';

const message = useMessage();
const dialog = useDialog();
const api = useCancellableApi();

const actionLoading = ref(false);
const controlLoading = ref(false);

const controlSettings = reactive({
  ledEnabled: false,
  performanceEnabled: false,
  sambaEnabled: false,
  usbPortEnabled: false,
  restartScheduleEnabled: false,
  restartScheduleTime: '04:00',
  fotaAutoUpdate: false,
});

const toggleLoading = reactive<Record<string, boolean>>({
  led: false,
  performance: false,
  samba: false,
  usbPort: false,
  restartSchedule: false,
  fota: false,
});

/**
 * 设备 goform 写入成功到「查询能读到新值」之间存在延迟：
 * 零延迟回读会拿到旧值、覆盖掉刚做的乐观置位，UI 弹回原样，
 * 用户以为没生效就反复点击。600ms 对齐 Android app（NetworkModule.setWifiSleep 等
 * 都是写入后 delay(500) 再 load），多留一点余量。只用于「写入后回读」，纯查询不加。
 */
const GOFORM_SETTLE_MS = 600;
const settleAfterWrite = () => new Promise((resolve) => setTimeout(resolve, GOFORM_SETTLE_MS));

async function loadControlSettings(silent = false) {
  if (!silent) controlLoading.value = true;
  try {
    // core GET /api/device/settings 是**归一化后的契约字段**（allowlist 默认拒绝，未登记的
    // 设备字段不会出现），键名见 contract.ts 的 DeviceFields.deviceSettings：
    // indicator_light_switch / performance_mode / samba_switch / usb_port_switch /
    // restart_schedule_switch / restart_time / UpgMode。值全是字符串 "1"/"0"（不能用 !! 判断）。

    const { data } = await api.get('/api/device/settings');
    const on = (k: string) => String(data?.[k] ?? '') === '1';
    controlSettings.ledEnabled = on('indicator_light_switch');
    controlSettings.performanceEnabled = on('performance_mode');
    controlSettings.sambaEnabled = on('samba_switch');
    controlSettings.usbPortEnabled = on('usb_port_switch');
    controlSettings.restartScheduleEnabled = on('restart_schedule_switch');
    // FOTA 自动检查更新：设备侧 UpgMode，"1" = 开（与写侧 auto_update 同向）
    controlSettings.fotaAutoUpdate = on('UpgMode');
    controlSettings.restartScheduleTime = String(data?.restart_time || '') || '04:00';
  } catch {
    /* 静默 */
  } finally {
    if (!silent) controlLoading.value = false;
  }
}

// ToggleRow 是完全受控组件（:value="modelValue"），父组件不回写状态开关就不会动，
// 所以这里成功后先本地置位、再静默回读设备真实状态；
// 回读必须等 GOFORM_SETTLE_MS，否则会读回旧值把乐观置位冲掉（见 settleAfterWrite 注释）
async function postToggle(
  url: string,
  enabled: boolean,
  loadingKey: string,
  stateKey: 'ledEnabled' | 'performanceEnabled' | 'sambaEnabled' | 'usbPortEnabled'
) {
  toggleLoading[loadingKey] = true;
  let posted = false;
  try {
    await api.post(url, { enabled });
    posted = true;
    // 只有成功才乐观置位：失败时保持原值，随后的回读会给出设备真实状态
    controlSettings[stateKey] = enabled;
    message.success('设置已更新');
  } catch {
    message.error('设置失败');
  } finally {
    if (posted) await settleAfterWrite();
    await loadControlSettings(true);
    toggleLoading[loadingKey] = false;
  }
}

async function postRestartSchedule(enabled: boolean) {
  toggleLoading.restartSchedule = true;
  let posted = false;
  try {
    await api.post('/api/device/restart-schedule', {
      enabled,
      time: controlSettings.restartScheduleTime,
    });
    posted = true;
    controlSettings.restartScheduleEnabled = enabled;
    message.success('定时重启设置已更新');
  } catch {
    message.error('设置失败');
  } finally {
    if (posted) await settleAfterWrite();
    await loadControlSettings(true);
    toggleLoading.restartSchedule = false;
  }
}

async function postRestartScheduleTime() {
  if (!controlSettings.restartScheduleEnabled) return;
  if (!/^\d{1,2}:\d{2}$/.test(controlSettings.restartScheduleTime)) {
    message.warning('重启时间格式应为 HH:MM');
    return;
  }
  toggleLoading.restartSchedule = true;
  let posted = false;
  try {
    await api.post('/api/device/restart-schedule', {
      enabled: true,
      time: controlSettings.restartScheduleTime,
    });
    posted = true;
    message.success('重启时间已更新');
  } catch {
    message.error('设置失败');
  } finally {
    if (posted) await settleAfterWrite();
    await loadControlSettings(true);
    toggleLoading.restartSchedule = false;
  }
}

function handleReboot() {
  dialog.warning({
    title: '确认重启',
    content: '设备将立即重启，期间所有连接将断开。',
    positiveText: '确认重启',
    negativeText: '取消',
    onPositiveClick: async () => {
      actionLoading.value = true;
      try {
        await api.post('/api/device/reboot');
        message.success('重启指令已发送');
      } catch {
        message.error('重启失败');
      } finally {
        actionLoading.value = false;
      }
    },
  });
}

function handleFactoryReset() {
  dialog.error({
    title: '恢复出厂设置',
    content: '此操作将清除所有配置和数据，不可撤销！',
    positiveText: '确认恢复',
    negativeText: '取消',
    onPositiveClick: async () => {
      actionLoading.value = true;
      try {
        await api.post('/api/device/factory-reset');
        message.success('恢复出厂指令已发送');
      } catch {
        message.error('操作失败');
      } finally {
        actionLoading.value = false;
      }
    },
  });
}

// 设备侧就是 goformId=SetUpgAutoSetting&UpgMode=0|1（0 = 关闭自动检查更新，1 = 开启），
// core 把它同时登记成读写字段：写 POST /api/device/fota { auto_update }，
// 读 GET /api/device/settings 的 UpgMode，所以这里能像其它开关一样回显真实状态。
//
// 规范入参是 auto_update（**正向**：true = 允许自动升级）。旧入参 enabled 是反的
// （true = 禁用），core 仍兼容但会打 warn，这里已改用 auto_update。
async function setFotaAutoUpdate(autoUpdate: boolean) {
  toggleLoading.fota = true;
  let posted = false;
  try {
    await api.post('/api/device/fota', { auto_update: autoUpdate });
    posted = true;
    // 与 postToggle 同一套：成功才乐观置位，失败保持原值等回读纠正
    controlSettings.fotaAutoUpdate = autoUpdate;
    message.success(autoUpdate ? '已开启 FOTA 自动检查更新' : '已关闭 FOTA 自动检查更新');
  } catch {
    message.error('设置失败');
  } finally {
    if (posted) await settleAfterWrite();
    await loadControlSettings(true);
    toggleLoading.fota = false;
  }
}

onMounted(() => {
  loadControlSettings();
});
</script>

<style scoped>
.tab-grid {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 16px;
}
.form-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(200px, 1fr));
  gap: 12px;
  margin-bottom: 12px;
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
.action-desc {
  font-size: 13px;
  color: var(--text-secondary);
  margin: 0 0 12px;
  line-height: 1.6;
}
@media (max-width: 768px) {
  .tab-grid {
    grid-template-columns: 1fr;
  }
  .form-grid {
    grid-template-columns: 1fr;
  }
}
</style>
