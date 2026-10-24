<template>
  <GridCard title="更多设置">
    <div class="entry-grid">
      <!--
        能力门禁（2026-10-14）：网络模式 → `Capability.NETWORK_MODE`，
        频段锁定 → `Capability.BAND_LOCK`。两者都是**整块入口置灰**（瓦片本身是按钮，
        不支持时点不动），并用 tooltip 给出原因（`caps.hintOf(wire)`）—— 只灰不说
        等于让用户以为界面坏了。

        禁用判据由父组件传入，但父组件算的是 `caps.unsupported()`（只有"设备确实没声明"
        才 true），**不是** `!caps.supports()`：后者在"能力集还没拉到 / 请求失败"时同样为真，
        会把这两个入口一起锁死（见 stores/capabilities.ts 文件头）。

        为什么门禁值走 props 而不是在本卡里直接读 store：本页的卡片一律是"受控子组件
        （props 下行 + 事件上行）"，门禁状态属于页面对设备能力的判断，留在页面里
        才能在 NetworkView.vue 一处看清"这一页有哪些入口被门禁"。

        外层 span 是 tooltip 的 trigger：浏览器不对 disabled 的表单控件派发鼠标事件，
        所以 `pointer-events` 由置灰态让给那层 span。这套结构（tooltip + span + 置灰样式）
        连同 `.gate-wrap` / `.gated-btn` 已收进 `src/components/EntryTile.vue`（计划 §8.1）：
        本卡只传 `:disabled` / `:hint`。
      -->
      <EntryTile
        :icon="SwapHorizontalOutline"
        title="网络模式"
        :desc="modeLabel"
        :disabled="modeUnsupported"
        :hint="modeHint"
        @click="emit('open-mode')"
      />
      <EntryTile
        :icon="LockClosedOutline"
        title="频段锁定"
        desc="锁定 LTE / NR 频段"
        :disabled="bandUnsupported"
        :hint="bandHint"
        @click="emit('open-band')"
      />
      <EntryTile
        :icon="MedkitOutline"
        title="网络诊断"
        desc="Ping / DNS / TCP / 路由追踪（点开不自动执行）"
        @click="emit('open-diag')"
      />
      <EntryTile :icon="SpeedometerOutline" title="网速测试" desc="下行 / 上行速率" @click="emit('open-speed')" />
      <EntryTile
        :icon="MoonOutline"
        title="休眠定时"
        :desc="sleepTime > 0 ? `${sleepTime} 分钟后休眠` : '不休眠'"
        @click="emit('open-sleep')"
      />
      <EntryTile :icon="CellularOutline" title="基站信息" desc="服务小区 / 邻区 / 锁定" @click="emit('open-cell')" />
      <!--
        2026-10-14：「WiFi 模块信息」已从这里**移出到设置页**（设置 › 设备 › WiFi 模块信息）。
        理由是它不是网络配置项、而是只读的设备上报字段，和「关于 / 系统信息」同类；
        移走后本卡正好 6 个瓦片 = 3 行 × 2 列，与下面 .entry-grid 的注释一致
        （此前 7 个瓦片会多出一个孤零零的第 4 行）。
      -->
    </div>
  </GridCard>
</template>

<script setup lang="ts">
import {
  SwapHorizontalOutline,
  LockClosedOutline,
  MedkitOutline,
  SpeedometerOutline,
  MoonOutline,
  CellularOutline,
} from '@vicons/ionicons5';
import GridCard from '@/components/GridCard.vue';
import EntryTile from '@/components/EntryTile.vue';

defineProps<{
  modeLabel: string;
  sleepTime: number;
  /** 网络模式：设备明确未声明 `Capability.NETWORK_MODE`（判据见模板内注释） */
  modeUnsupported: boolean;
  /** 置灰时的原因文案（`caps.hintOf(Capability.NETWORK_MODE)`） */
  modeHint: string;
  /** 频段锁定：设备明确未声明 `Capability.BAND_LOCK` */
  bandUnsupported: boolean;
  /** 置灰时的原因文案（`caps.hintOf(Capability.BAND_LOCK)`） */
  bandHint: string;
}>();

const emit = defineEmits<{
  'open-mode': [];
  'open-band': [];
  'open-speed': [];
  'open-diag': [];
  'open-sleep': [];
  'open-cell': [];
}>();
</script>

<style scoped>
/* 2 列 × 3 行固定布局（与上方 NetworkInfo/WiFi 热度等宽）。
   瓦片本体（含 72px 等高、图标格、hover、禁用态、tooltip trigger 结构）在
   `src/components/EntryTile.vue`（计划 §8.1）；本文件只留栅格与断点。 */
.entry-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: var(--space-3);
}

@media (max-width: 768px) {
  /* 2 列在 298px 卡内每格 143px，扣掉 38px 图标与 12px 间距后文字只剩 ~97px，
     而「服务小区 / 邻区 / 锁定」「锁定 LTE / NR 频段」「设备上报的模块字段」
     这些描述都是 108~130px ⇒ 全部被吃掉半句，等于看不到这个入口是干什么的。
     窄屏折单列：整宽 298px 足够放下最长那条。 */
  .entry-grid {
    grid-template-columns: 1fr;
    gap: var(--space-2);
  }
}
</style>
