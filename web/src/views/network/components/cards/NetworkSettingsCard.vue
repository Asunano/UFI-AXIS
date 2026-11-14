<template>
  <GridCard title="网络设置">
    <div class="entry-grid">
      <!-- 2026-11-10：网络模式入口移到「网络信息」右栏（用户要求），本卡剩 6 项。
           按用途排列（连接 → 流量与局域网 → 排查工具），但**不写分组标题**：
           组与组之间只留一行间距，靠图标配色区分（绿=连接 / 蓝=配置 / 琥珀=工具）。 -->
      <EntryTile
        :icon="LockClosedOutline"
        title="频段锁定"
        desc="锁定 LTE / NR 频段"
        :disabled="bandUnsupported"
        :hint="bandHint"
        @click="emit('open-band')"
      />
      <EntryTile :icon="CellularOutline" title="基站信息" desc="服务小区 / 邻区 / 锁定" @click="emit('open-cell')" />
      <EntryTile
        :icon="OptionsOutline"
        title="流量与校准"
        desc="限额大小 / 提醒百分比 / 到达阈值自动断网；手动修正已用量"
        @click="emit('open-traffic-calibration')"
      />
      <EntryTile
        :icon="GitNetworkOutline"
        title="LAN / DHCP 设置"
        desc="LAN IP / 掩码 / 地址池 / 租约"
        @click="emit('open-lan')"
      />
      <EntryTile
        :icon="PulseOutline"
        title="网络诊断"
        desc="Ping / DNS / TCP / 路由追踪（点开不自动执行）"
        @click="emit('open-diag')"
      />
      <EntryTile :icon="SpeedometerOutline" title="网速测试" desc="下行 / 上行速率" @click="emit('open-speed')" />
    </div>
  </GridCard>
</template>

<script setup lang="ts">
/**
 * 网络页的第二张入口卡（2026-10-14）。
 *
 * 为什么单独一张卡而不是塞进「更多设置」：用户明确要求「更多设置」保持 **3×2**（6 个瓦片），
 * 而这两项是从 `#/device` 搬过来的配置项，加进去就变 4 行。单独一张卡既满足 3×2，
 * 也让「只读的网络工具入口（更多设置）」与「会写设备的配置入口（本卡）」分开。
 *
 * 两项都只做入口，表单在两个弹窗里：
 *   · 流量与校准 → `@/views/network/components/modals/TrafficAndCalibrationModal.vue`（本页私有）；
 *   · LAN / DHCP → `@/components/modals/LanModal.vue`（放共享目录，因为不再属于某一个 view）。
 *
 * 2026-10-22：瓦片从 3 个回到 2 个（限额 + 校准合并），栅格保持 `auto-fit, minmax(240px, 1fr)`：
 * 整幅卡宽下两项各占一半，窄屏自然回落 2 列 / 1 列，不留空洞。
 */
import {
  CellularOutline,
  GitNetworkOutline,
  LockClosedOutline,
  OptionsOutline,
  PulseOutline,
  SpeedometerOutline,
} from '@vicons/ionicons5';
import GridCard from '@/components/GridCard.vue';
import EntryTile from '@/components/EntryTile.vue';

defineProps<{
  bandUnsupported: boolean;
  bandHint: string;
}>();

const emit = defineEmits<{
  'open-band': [];
  'open-cell': [];
  'open-diag': [];
  'open-speed': [];
  'open-traffic-calibration': [];
  'open-lan': [];
}>();
</script>

<style scoped>
/* auto-fit 而不是固定 2 列：240px 是瓦片里最长那条描述不被省略号吃掉的下限
   （描述允许折两行，见 EntryTile）。
   瓦片本身（含图标尺寸 / 图文间距 / hover / 禁用态 / tooltip trigger 结构）已收敛到
   `src/components/EntryTile.vue`（计划 §8.1）；本文件只留栅格与断点。 */
.entry-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(240px, 1fr));
  gap: var(--space-3);
}
/* 窄屏折单列：2 列时每格约 143px，扣掉 38px 图标与 12px 间距后描述只剩 ~97px
   （与「更多设置」卡同一个理由与同一个断点）。 */
@media (max-width: 768px) {
  .entry-grid {
    grid-template-columns: 1fr;
    gap: var(--space-2);
  }
}
</style>
