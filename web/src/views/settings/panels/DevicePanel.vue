<template>
  <!--
    容器改用 main.css 的全局 `.settings-panel`（2 列 + 1024 折单列），原先本文件自写了一份
    flex 竖排，是全仓唯一的第二种容器口径（计划 §6.9 B8-4）。

    三张卡都显式通栏（`.full-width`）：本页的卡片高度是「入口瓦片数」决定的
    （1 颗 ≈ 104px、2 颗 ≈ 180px、危险操作 ≈ 150px），两张不等高的卡配对会在矮卡下方
    露出整块页面底色（main.css 里 `.settings-panel` 的 2 列 + `align-items:start` 那条约束）。
    通栏后本页等价于竖向堆叠，观感与改动前一致，只是栅格/断点从此由全局那一份管。
  -->
  <div class="settings-panel">
    <!-- 只读信息入口 -->
    <GridCard class="full-width" title="设备信息">
      <div class="entry-grid">
        <EntryTile
          :icon="WifiOutline"
          title="WiFi 模块信息"
          desc="设备上报的模块字段（只读）"
          @click="showModule = true"
        />
      </div>
    </GridCard>

    <!-- 维护入口 -->
    <GridCard class="full-width" title="设备维护">
      <div class="entry-grid">
        <EntryTile
          :icon="LockClosedOutline"
          title="设备后台密码"
          desc="修改设备管理后台的登录密码"
          @click="showPassword = true"
        />
        <EntryTile
          :icon="CodeSlashOutline"
          title="设备后台原始数据"
          desc="完整字段快照（含敏感信息）"
          @click="showGoform = true"
        />
      </div>
    </GridCard>

    <!--
      危险操作（2026-10-14 从 #/device 集中到设置页）：
      「重启设备 / 恢复出厂」原来在 #/device 的「设备控制」卡里，「关机」是那张卡的入口瓦片。
      三个都是**不可撤销**的动作，集中到设置页的独立卡片里，与日常开关分开 ——
      在 #/device 那种一眼扫过去的平铺布局里，它们和 LED/Samba 排在一起太容易误点。

      2026-10-21（计划 §6.9 B8-4）：这里**没有**加「输入配对密码」的口令校验，原因是协议层缺支持 ——
      core 的 `/api/device/{reboot,factory-reset,shutdown}` 三个端点都不读请求体、也不校验配对密码
      （DeviceRoutes.kt 的 post("/reboot") 等三支），web 侧单独加一个口令框只能是"填了也没人验"的假门槛，
      比没有更糟（用户会以为自己被保护了）。要落地得先让 core 这三个端点接受并校验口令。
      能做的部分已做：失败原因不再被吞成「重启失败」，见 [deviceActionError]。
    -->
    <!-- 通栏（.full-width）：三颗危险动作按钮 + 一行说明在 2 列栅格里占满一整行，
         否则第 2 行只占左半、右半露出整块页面底色（与 UpdatePanel「2+1 通栏」同一排法）。 -->
    <GridCard class="full-width" title="危险操作">
      <p class="danger-desc">以下操作会中断所有连接，且不可撤销。请确认设备上所有重要任务已完成。</p>
      <n-space>
        <n-button type="warning" :loading="rebooting" @click="handleReboot">重启设备</n-button>
        <n-button type="error" :loading="resetting" @click="handleFactoryReset">恢复出厂</n-button>
        <n-button type="error" :loading="shuttingDown" @click="handleShutdown">关机</n-button>
      </n-space>
    </GridCard>

    <!-- 三个弹窗组件都在共享目录 @/components/modals/（不只本面板用） -->
    <ModuleInfoModal v-model:show="showModule" />
    <PasswordModal v-model:show="showPassword" />
    <GoformModal v-model:show="showGoform" />
  </div>
</template>

<script setup lang="ts">
/**
 * 设置 › 设备（2026-10-14 新建）。
 *
 * 为什么单独开一栏而不是塞进「通用」：这一栏收的是**设备本体**的东西 ——
 * 只读的模块字段、后台密码、原始字段快照，以及三个不可撤销的危险动作。
 * 它们既不属于「通用」（Token / 端口 / 更新源这些 core 自身的配置），
 * 也不属于「服务」（core 采集服务的起停），单独一栏才找得到。
 *
 * 来源：#/device 的「设备设置」入口卡（WiFi 模块信息原在网络页的「更多设置」，
 * 也一并归到这里 —— 它是只读字段，和「关于 / 系统信息」同类，不是网络配置项）。
 */
import { ref } from 'vue';
import { useMessage, useDialog } from 'naive-ui';
import { CodeSlashOutline, LockClosedOutline, WifiOutline } from '@vicons/ionicons5';
import { useCancellableApi } from '@/composables/useCancellableApi';
import GridCard from '@/components/GridCard.vue';
import EntryTile from '@/components/EntryTile.vue';
import ModuleInfoModal from '@/components/modals/ModuleInfoModal.vue';
import PasswordModal from '@/components/modals/PasswordModal.vue';
import GoformModal from '@/components/modals/GoformModal.vue';

const message = useMessage();
const dialog = useDialog();
const api = useCancellableApi();

const showModule = ref(false);
const showPassword = ref(false);
const showGoform = ref(false);

// 三个危险动作各有一个 loading：它们各自独立，共用一个会让另一个按钮也跟着转圈
const rebooting = ref(false);
const resetting = ref(false);
const shuttingDown = ref(false);

/**
 * 把失败原因说清楚，而不是一律吞成「重启失败」（计划 §6.9 B8-4 的「错误透出」那一半）。
 *
 * 三种情形要分开，因为用户能做的事完全不同：
 *  · 有 `error` 字段 —— 直接透出 core 原文（如 409 的「更新进行中，暂不接受该操作」）；
 *  · 500 而没有 error —— reboot / factory-reset / shutdown 这三个端点失败时只回
 *    `{success:false}`（docs/UFI-AXIS-Core-API-Reference.md §失败信封），没有原因可透，
 *    所以说「设备未受理」，别让用户以为是网络问题一路重试；
 *  · 没有 response —— 请求根本没到服务端（断网 / 服务已停）。
 */
function deviceActionError(e: any, fallback: string): string {
  const res = e?.response;
  if (!res) return `${fallback}：无法连接设备端服务`;
  const text = res.data?.error || res.data?.message;
  if (text) return `${fallback}：${text}`;
  if (res.status >= 500) return `${fallback}：设备未受理该指令（未返回原因）`;
  return `${fallback}（HTTP ${res.status}）`;
}

function handleReboot() {
  dialog.warning({
    title: '确认重启',
    content: '设备将立即重启，期间所有连接将断开。',
    positiveText: '确认重启',
    negativeText: '取消',
    onPositiveClick: async () => {
      rebooting.value = true;
      try {
        await api.post('/api/device/reboot');
        message.success('重启指令已发送');
      } catch (e: any) {
        message.error(deviceActionError(e, '重启失败'));
      } finally {
        rebooting.value = false;
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
      resetting.value = true;
      try {
        await api.post('/api/device/factory-reset');
        message.success('恢复出厂指令已发送');
      } catch (e: any) {
        message.error(deviceActionError(e, '恢复出厂失败'));
      } finally {
        resetting.value = false;
      }
    },
  });
}

function handleShutdown() {
  dialog.error({
    title: '确认关机',
    content: '设备将立即关闭，所有连接将断开。请确认已保存所有工作。',
    positiveText: '确认关机',
    negativeText: '取消',
    onPositiveClick: async () => {
      shuttingDown.value = true;
      try {
        await api.post('/api/device/shutdown');
        message.success('关机指令已发送');
      } catch (e: any) {
        message.error(deviceActionError(e, '关机失败'));
      } finally {
        shuttingDown.value = false;
      }
    },
  });
}
</script>

<style scoped>
/* 容器栅格：改用 main.css 的全局 `.settings-panel`（2 列 + 1024 折单列），
   原先本文件自写的一份 flex 竖排是全仓唯一的第二种容器口径（计划 §6.9 B8-4）。 */

/* ── 入口瓦片 ──
   瓦片本体（图标格 / 图文间距 / hover / 说明折行 / 断点高度档）已收敛到
   `src/components/EntryTile.vue`（计划 §8.1，2026-10-21）：原先这组 `.entry-*` 在
   `SettingsView`、`network/{NetworkSettingsCard,MoreSettingsCard}`、`settings/panels/{Device,About}Panel`
   各有一份副本，本文件是其中之一，且 `.entry-desc` 口径曾与设置页不一致（B8 先改成 clamp，
   C1 再统一）。本文件只留栅格。 */
.entry-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(240px, 1fr));
  gap: var(--space-3);
}
.danger-desc {
  font-size: var(--font-base);
  color: var(--text-secondary);
  margin: 0 0 var(--space-3);
  line-height: 1.6;
}
@media (max-width: 768px) {
  .entry-grid {
    grid-template-columns: 1fr;
  }
}
</style>
