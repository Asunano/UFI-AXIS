<template>
  <GridCard :title="`局域网设备 (${clients.length})`">
    <!-- 顶部入口：已拉黑设备（点进去可单个解除 / 全部解除）。
         它在滚动区**外面** —— 设备再多也不该把入口滚走。 -->
    <button class="acl-entry" @click="emit('open-acl')">
      <span class="acl-entry-text">查看已拉黑设备</span>
      <span class="acl-entry-count">{{ blockedList.length ? `${blockedList.length} 台` : '暂无' }}</span>
    </button>
    <template v-if="clients.length">
      <!-- 表头放在滚动容器**外面**：这样它天然固定，不需要 `position: sticky` ——
           sticky 得给表头补一个与卡面同色的背景，而卡面底色由主题决定，写死换肤后会露馅。
           GridCard 的标题栏（含刷新等动作）本来就在 body 之外，同样不会跟着滚。 -->
      <div class="lan-row lan-row-head">
        <span>名称</span>
        <span>IP</span>
        <span>MAC</span>
        <span></span>
      </div>
      <!--
        2026-10-14：清单进 `n-scrollbar`（本仓 SmsView 的验证码 / 已拦截列表、
        PlaylistPanel、DeliveryHistoryModal 都是这一套），设备多时在**卡内**滚动，
        不把整页撑长 —— 用户报的"设备过多导致布局异常"就是这一条。

        为什么用 n-scrollbar 而不是裸 `overflow-y: auto`：本仓滚动区的主流写法就是它，
        滚动条外观与主题同源（裸 overflow 走的是 main.css 里那条全局 6px 样式）。
        GroupBrowserPanel 刻意用裸 overflow，是因为它要靠 `flex: 1` 吃满父容器的**高度链**；
        本卡的高度是**封顶**的（`.lan-scroll` 上的 max-height），不需要高度链，
        正好是 n-scrollbar 最省事的用法：max-height 写在组件根上，内部容器会继承它。
      -->
      <n-scrollbar class="lan-scroll">
        <div v-for="(c, i) in clients" :key="i" class="lan-row">
          <span class="lan-name">{{ c.hostname || c.ip_addr || '--' }}</span>
          <span class="lan-ip">{{ c.ip_addr || '--' }}</span>
          <span class="lan-mac">{{ c.mac || '--' }}</span>
          <span class="lan-act">
            <!-- MAC 为空没法拉黑（设备名单以 MAC 为主键） -->
            <n-button
              v-if="c.mac"
              size="tiny"
              :type="blockedMacs.has(c.mac.toLowerCase()) ? 'default' : 'error'"
              :loading="aclPending === c.mac.toLowerCase()"
              :disabled="!!aclPending"
              @click="blockedMacs.has(c.mac.toLowerCase()) ? emit('unblock', c.mac) : emit('block', c)"
            >
              {{ blockedMacs.has(c.mac.toLowerCase()) ? '解除' : '拉黑' }}
            </n-button>
          </span>
        </div>
      </n-scrollbar>
    </template>
    <div v-else class="client-empty">暂无连接设备</div>
  </GridCard>
</template>

<script setup lang="ts">
import { NScrollbar } from 'naive-ui';
import type { WifiClient } from '@/types';
import GridCard from '@/components/GridCard.vue';

defineProps<{
  clients: WifiClient[];
  blockedList: any[];
  blockedMacs: Set<string>;
  aclPending: string;
}>();

const emit = defineEmits<{
  'open-acl': [];
  block: [WifiClient];
  unblock: [string];
}>();
</script>

<style scoped>
/* 空态用全局 `.client-empty`（居中、13px、--text-muted）—— 原先的 `.hint-text` 只是左对齐
   的本地副本，与「卡内没有内容」这个语义同源，收敛掉。 */

/*
 * 清单滚动区（2026-10-14）。
 *
 * 高度不再写死一个 260px 之类的"看着差不多"的数字：一行的高度由**令牌**算出来，
 * 于是"可视 7 行"这个设计意图跟字号阶梯 / 间距阶梯绑在一起 —— 以后改 --font-base
 * 或 --space-* 时可视行数不变，不需要回来重调一个魔法数字。
 *
 * 一行 = 上下内距（--space-2 × 2）+ 行内容高 + 分隔线 1px。
 * 行内容高取"文字行"与"操作按钮"里更高的那个：文字行 = --font-base × 1.5，
 * 而 n-button size="tiny" 的固定高是 22px（naive 的尺寸表，不是这里自选的设计值），
 * 所以用 max() 兜住 —— 否则行里一出现「拉黑」按钮，实际行高就比算式高 2.5px，
 * 7 行封顶会悄悄变成 6 行半。这三个分量与下面 `.lan-row` 的 padding / line-height
 * 严格对应，改一处必须改另一处。
 *
 * 7 行：够看清一台设备的三列信息，又不会喧宾夺主（旧实现是写死的 260px ≈ 7 行，
 * 本次只是把同一个意图改成可推导的写法）。
 *
 * 为什么 max-height 写在 n-scrollbar 根上而不是内部容器：naive 的
 * `.n-scrollbar-container` 带 `max-height: inherit`，根上一写内部就继承到 ——
 * DeliveryHistoryModal 用的是同一个写法（那里是内联 style）。
 */
.lan-scroll {
  --lan-row-height: calc(var(--space-2) * 2 + max(var(--font-base) * 1.5, 22px) + 1px);
  max-height: calc(var(--lan-row-height) * 7);
}

/* 滚到边界时不要把整页一起带着滚（裸 overflow 时写在滚动元素上，
   这里是 n-scrollbar 内部那层真正的滚动容器）。 */
.lan-scroll :deep(.n-scrollbar-container) {
  overscroll-behavior: contain;
}

.lan-row {
  display: grid;
  grid-template-columns: minmax(0, 1.2fr) minmax(0, 1fr) minmax(0, 1.3fr) 56px;
  align-items: center;
  gap: 12px;
  /* 右侧留出 8px：n-scrollbar 的滑块是**悬浮**在内容上的（宽 6px、压在右边缘），
     不留的话「拉黑」按钮的右边缘会被盖住一条。表头用的是同一个类，两处对齐不受影响。 */
  padding: var(--space-2) var(--space-2) var(--space-2) 0;
  border-bottom: 1px solid var(--border-subtle);
  font-size: var(--font-base);
  /* 行高必须显式给：上面的 --lan-row-height 是按 1.5 算的，不能让它随宿主漂移 */
  line-height: 1.5;
}
.lan-row:last-child {
  border-bottom: none;
}
.lan-row-head {
  font-size: var(--font-xs);
  color: var(--text-muted);
  letter-spacing: 0.04em;
}
.lan-name {
  color: var(--text-primary);
  font-weight: 500;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.lan-ip {
  color: var(--text-secondary);
  font-variant-numeric: tabular-nums;
}
.lan-mac {
  color: var(--text-muted);
  font-family: monospace;
  font-size: var(--font-sm);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.lan-act {
  display: flex;
  justify-content: flex-end;
}
.acl-entry {
  display: flex;
  align-items: center;
  justify-content: space-between;
  width: 100%;
  margin-bottom: 8px;
  padding: 8px 10px;
  border: 1px solid var(--border-subtle);
  border-radius: 8px;
  background: transparent;
  color: var(--text-primary);
  font-size: var(--font-base);
  cursor: pointer;
}
.acl-entry:hover {
  border-color: var(--border-subtle);
}
.acl-entry-count {
  color: var(--text-muted);
  font-size: var(--font-sm);
}

/*
 * 2026-10-14：断点 768 → 1024。
 *
 * 为什么必须跟着父布局走：这里是**视口**媒体查询，而本卡在 /#/network 的两列栅格里
 * 只占**半宽** —— 四列行（名称 / IP / MAC / 操作）实测需要约 460px，对应视口约 1000px。
 * 沿用 768px 的话，视口 900px 时卡片只有 ~430px、MAC 已被省略号吃到看不清，
 * 而这个断点却不触发（塌陷与拥挤同时不发生，最难受的一种组合）。
 * 提到 1024px 后：视口 ≤1024（卡片 ≤约 490px）就换成两行式。
 *
 * 代价：单列布局（视口 ≤1024px 时网络页栅格已折单列，卡片反而是全宽）也会走两行式，
 * 偏保守 —— 但两行式在 800px 宽下同样好读，不会出错，所以接受。
 * 更干净的做法是容器查询（@container），本仓尚未使用该范式，不在此单独引入。
 */
@media (max-width: 1024px) {
  .lan-row {
    grid-template-columns: 1fr;
    gap: 2px;
  }
  .lan-row-head {
    display: none;
  }
  /* 两行式下每行变成 4 行内容（名称 / IP / MAC / 操作），行高约 104px。
     若仍按 7 行封顶，手机上只露出 2 台多一点 —— 这里按"可视 3 台"重算，
     所以 --lan-row-height 必须跟着布局一起改，不能沿用宽屏那个值。
     3 行文字 = --font-base × 1.5 × 3 = --font-base × 4.5；操作按钮独占一行 22px；
     行间 gap 2px × 3。 */
  .lan-scroll {
    --lan-row-height: calc(var(--font-base) * 4.5 + 22px + 2px * 3 + var(--space-2) * 2 + 1px);
    max-height: calc(var(--lan-row-height) * 3);
  }
}

/*
 * 矮屏（≤720px 高：笔记本 768p / 浏览器缩放 / 分屏，2026-10-21 补）。
 * 上面两档的「可视 7 台 / 3 台」是按满高视口定的；矮屏下本卡下方还有内容，
 * 按原值会把整页撑出滚动条。宽屏档收到 4 行、两行式档收到 2 行。
 * 高度令牌仍由 --lan-row-height 推导，不写死 px。
 */
@media (max-height: 720px) {
  .lan-scroll {
    max-height: calc(var(--lan-row-height) * 4);
  }
}
@media (max-height: 720px) and (max-width: 1024px) {
  .lan-scroll {
    max-height: calc(var(--lan-row-height) * 2);
  }
}
</style>
