<template>
  <!--
    运行态条。取代原来的「隧道概览」卡（2026-09-21）。

    为什么不是 GridCard：那张卡占掉 ~170px 高度，内容只有三个几乎不变的值
    （frpc/cloudflared 版本、在跑条数、回源端口）+ 一排 5 颗 tiny 按钮 + 一句常驻说明，
    而这一页真正要看的是下面的隧道列表。现在压到 ~70px：
    - 三栏「标题行 / 读数行」两行式，栏间一条 1px 竖线。不给每栏做砖块（各自的边框+底色）——
      卡里套卡是改版前最难看的地方，而且砖块的底色会和状态色抢同一个通道；
    - 读数 --font-md/600，不是仪表盘那档大字号：版本号和端口号不是指标；
    - 原先每行下面那句解释（「隧道回源指向这个端口」）改成 title 悬浮提示；
    - 卡头与「隧道概览」标题都去掉：左侧导航已经高亮「隧道」，标题是第二遍；
    - 低频动作（组件管理/看护设置/清空全部日志/停止全部）收进 ⋯ 菜单，
      两个破坏性的在菜单里单独分组并标红；原先它们与「刷新」同尺寸并排，没有优先级之分；
    - 原来常驻在卡底的「全部停止会清空看护期望列表…」删掉 —— 那是确认弹窗该说的话
      （见 TunnelView.confirmStopAll 的 content），不该长期占版面。
  -->
  <section class="runbar">
    <div v-for="c in components" :key="c.key" class="rb-item" :class="{ 'rb-warn': !c.installed }">
      <div class="rb-head"><i class="dot" :class="dotClass(c)"></i>{{ c.name }}</div>
      <div class="rb-val">
        <template v-if="c.installed">
          {{ c.version || '版本未知' }}
          <em>{{ c.running ? `· ${c.running} 条在跑` : '· 未运行' }}</em>
        </template>
        <template v-else>
          未安装
          <!-- 这里只给一颗 tiny：它是嵌在读数行里的次要动作，
               和列表里的「启动」同尺寸会让人以为它才是本屏主按钮。 -->
          <n-button size="tiny" @click="emit('open-components')">去安装</n-button>
        </template>
      </div>
    </div>

    <div class="rb-item" title="隧道回源指向这个端口">
      <div class="rb-head"><i class="dot"></i>回源端口</div>
      <div class="rb-val">{{ localPort || '--' }}</div>
    </div>

    <div class="rb-act">
      <n-button quaternary size="small" :loading="loading" title="刷新" aria-label="刷新" @click="emit('refresh')">
        <template #icon>
          <n-icon><RefreshOutline /></n-icon>
        </template>
      </n-button>
      <n-dropdown :options="moreOptions" trigger="click" @select="onMore">
        <n-button quaternary size="small" title="更多" aria-label="更多">
          <template #icon>
            <n-icon><EllipsisHorizontalOutline /></n-icon>
          </template>
        </n-button>
      </n-dropdown>
    </div>
  </section>
</template>

<script setup lang="ts">
import type { DropdownOption } from 'naive-ui';
import { EllipsisHorizontalOutline, RefreshOutline } from '@vicons/ionicons5';

/** 一栏组件读数。「回源端口」不属于组件，单独写在模板里。
    不导出：`<script setup>` 里不能有 ES module export，页面侧那份对象是 computed 推导的，
    形状由本组件的 props 约束即可。 */
interface OverviewComponent {
  key: string;
  name: string;
  installed: boolean;
  version: string;
  running: number;
}

defineProps<{
  components: OverviewComponent[];
  localPort: number;
  loading: boolean;
}>();

const emit = defineEmits<{
  refresh: [];
  'open-components': [];
  'open-guard': [];
  'clear-logs': [];
  'stop-all': [];
}>();

/** 状态点三态：在跑（绿，带呼吸）/ 缺组件（黄）/ 已就绪但无运行（灰） */
function dotClass(c: OverviewComponent): string {
  if (!c.installed) return 'dot-warn';
  return c.running > 0 ? 'dot-live' : '';
}

/** 破坏性两项走 props.style 上色（本项目既有写法，见 FilesToolbar 的 sortOptions）。 */
const moreOptions: DropdownOption[] = [
  { label: '组件管理', key: 'components' },
  { label: '看护设置', key: 'guard' },
  { type: 'divider', key: 'd1' },
  { label: '清空全部日志', key: 'clear-logs', props: { style: 'color: var(--error)' } },
  { label: '停止全部隧道', key: 'stop-all', props: { style: 'color: var(--error)' } },
];

function onMore(key: string) {
  if (key === 'components') emit('open-components');
  else if (key === 'guard') emit('open-guard');
  else if (key === 'clear-logs') emit('clear-logs');
  else if (key === 'stop-all') emit('stop-all');
}
</script>

<style scoped>
.runbar {
  display: flex;
  align-items: stretch;
  flex-wrap: wrap;
  padding: var(--space-3) var(--space-2);
  background: var(--card-bg);
  border: 1px solid var(--border-subtle);
  border-radius: var(--radius-md);
}
.rb-item {
  flex: 1 1 200px;
  min-width: 0;
  padding: 0 var(--space-3);
  display: flex;
  flex-direction: column;
  justify-content: center;
  gap: 3px;
}
.rb-item + .rb-item {
  border-left: 1px solid var(--border-subtle);
}
.rb-head {
  display: flex;
  align-items: center;
  gap: 7px;
  font-size: var(--font-sm);
  color: var(--text-secondary);
}
.rb-val {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  font-size: var(--font-md);
  font-weight: 600;
  color: var(--text-primary);
  font-variant-numeric: tabular-nums;
}
/* 同行的补充信息（「· 2 条在跑」）：灰、常规字重 —— 它是读数的注脚，不是第二个读数 */
.rb-val em {
  font-style: normal;
  font-size: var(--font-sm);
  font-weight: 400;
  color: var(--text-muted);
}
.rb-warn .rb-val {
  color: var(--warning);
}
.rb-act {
  flex: none;
  display: flex;
  align-items: center;
  gap: var(--space-1);
  padding-left: var(--space-2);
}

/* ── 状态点与呼吸光环 ──
   这是本页唯一的动效，而且有语义：只有真的有进程在跑才会呼吸。 */
.dot {
  flex: none;
  width: 8px;
  height: 8px;
  border-radius: var(--radius-pill);
  background: var(--border-subtle);
  position: relative;
}
.dot-warn {
  background: var(--warning);
}
.dot-live {
  background: var(--success);
}
/* 光环是独立伪元素：从点的大小扩散到 3 倍并淡出，scale/opacity 都走合成层，不触发重排 */
.dot-live::after {
  content: '';
  position: absolute;
  inset: 0;
  border-radius: inherit;
  background: inherit;
  animation: rb-pulse 1.8s ease-out infinite;
}
@keyframes rb-pulse {
  0% {
    transform: scale(1);
    opacity: 0.55;
  }
  70%,
  100% {
    transform: scale(3);
    opacity: 0;
  }
}
/* 尊重系统的「减少动态效果」：关掉呼吸，只留常亮的点 */
@media (prefers-reduced-motion: reduce) {
  .dot-live::after {
    animation: none;
  }
}

/* 窄屏：三栏竖排，竖线换横线；动作区提到最上面右对齐 ——
   落在最后一行会被读成「属于第三栏」。 */
@media (max-width: 768px) {
  .rb-item {
    flex: 1 1 100%;
    padding: var(--space-2) var(--space-3);
  }
  .rb-item + .rb-item {
    border-left: 0;
    border-top: 1px solid var(--border-subtle);
  }
  .rb-act {
    order: -1;
    width: 100%;
    justify-content: flex-end;
    padding: 0 var(--space-2) var(--space-1);
  }
}
</style>
