<template>
  <GridCard title="网络设置">
    <div class="entry-grid">
      <!--
        流量与校准（2026-10-22 合并）：原先「流量限额」「流量校准」两个瓦片 + 两个弹窗，
        合并成一个入口、一个弹窗（栏内分「限额 / 校准」，见
        `../modals/TrafficAndCalibrationModal.vue`）。

        为什么合成一个：两者改的是**同一套统计**（设备侧已用流量 / 已用时长）—— 限额决定
        "到多少提醒 / 断网"，校准把计数器手动改成某个值。两个入口分列会让用户以为它们无关；
        而两个弹窗都是模态，本来也开不了两个。

        门禁（2026-10-22 随合并不再把整块瓦片置灰）：校准确有 `Capability.TRAFFIC_LIMIT` 门禁，
        但限额栏读配置 / 看用量即使设备不支持写入也有意义，原先置灰的也只是「流量校准」那一格。
        所以门禁下沉到弹窗里的校准栏（原因就地显示 + 「执行校准」按钮置灰），
        本瓦片不再有 `disabled` / `hint` 两个 prop。
      -->
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
import { GitNetworkOutline, OptionsOutline } from '@vicons/ionicons5';
import GridCard from '@/components/GridCard.vue';
import EntryTile from '@/components/EntryTile.vue';

const emit = defineEmits<{
  /** 打开「流量与校准」弹窗（限额 / 校准两栏） */
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
