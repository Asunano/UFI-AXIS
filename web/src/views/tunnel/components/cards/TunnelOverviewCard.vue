<template>
  <GridCard title="隧道概览">
    <template #extra>
      <n-space :size="6">
        <n-button size="tiny" quaternary :loading="loading" @click="emit('refresh')">刷新</n-button>
        <n-button size="tiny" quaternary @click="emit('open-components')">组件管理</n-button>
        <n-button size="tiny" quaternary @click="emit('open-guard')">看护设置</n-button>
        <n-button size="tiny" @click="emit('clear-logs')">清空全部日志</n-button>
        <n-button size="tiny" type="error" ghost @click="emit('stop-all')">全部停止</n-button>
      </n-space>
    </template>
    <!--
      概览不用胶囊读数条：frpc / cloudflared 是「组件装了没、在跑几条」这种带状态的东西，
      套一层胶囊底色反而把状态色和背景色搅在一起。改成「状态点 + 两行文字」的裸排版，
      唯一的动效是在跑时状态点外围有一圈呼吸光环（.ov-dot-live），它是有语义的：
      亮着且在呼吸 = 真的有进程在跑。
    -->
    <div class="overview">
      <div v-for="c in components" :key="c.key" class="ov-item" :class="{ 'ov-item-off': !c.installed }">
        <span class="ov-dot" :class="{ 'ov-dot-live': c.running > 0 }"></span>
        <div class="ov-text">
          <div class="ov-line">
            <span class="ov-name">{{ c.name }}</span>
            <span class="ov-value">{{ c.installed ? c.version || '版本未知' : '未安装' }}</span>
          </div>
          <div class="ov-sub">
            <template v-if="!c.installed">组件缺失，对应隧道无法启动</template>
            <template v-else-if="c.running">{{ c.running }} 条在跑</template>
            <template v-else>已就绪，当前无运行</template>
          </div>
        </div>
        <n-button v-if="!c.installed" size="tiny" type="primary" @click="emit('open-components')">去安装</n-button>
      </div>

      <div class="ov-item">
        <span class="ov-dot ov-dot-idle"></span>
        <div class="ov-text">
          <div class="ov-line">
            <span class="ov-name">本机服务端口</span>
            <span class="ov-value">{{ localPort || '--' }}</span>
          </div>
          <div class="ov-sub">隧道回源指向这个端口</div>
        </div>
      </div>
    </div>
    <div class="hint">
      「全部停止」会同时清空看护期望列表（frp_desired / cf_desired），停掉的通道不会被自动重连拉起。
    </div>
  </GridCard>
</template>

<script setup lang="ts">
import GridCard from '@/components/GridCard.vue';

/** 概览行。「本机服务端口」不属于组件，单独写在模板里。
    不导出：`<script setup>` 里不能有 ES module export，页面侧那份对象是 computed 推导出来的，
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
</script>

<style scoped>
/* ── 概览：状态点 + 两行文字，没有胶囊底色 ──
   胶囊底色的问题是它自带一层背景，跟「运行中/未安装」的状态色抢同一个通道；
   这里把状态交给左侧那颗点，文字保持裸排版，卡片里就只有一种视觉语言。 */
.overview {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
  gap: 4px 24px;
}
.ov-item {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  padding: 10px 0;
}
.ov-text {
  flex: 1;
  min-width: 0;
}
.ov-line {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 8px;
}
.ov-name {
  font-size: 13px;
  color: var(--text-secondary);
}
.ov-value {
  font-size: 15px;
  font-weight: 600;
  color: var(--text-primary);
  font-variant-numeric: tabular-nums;
  letter-spacing: -0.01em;
}
.ov-sub {
  margin-top: 2px;
  font-size: 12px;
  color: var(--text-muted);
}
/* 未安装：整行压暗，让「缺组件」这件事一眼可见，但不至于像报错那样刺眼 */
.ov-item-off .ov-value {
  color: var(--text-muted);
  font-weight: 500;
}

/* ── 状态点与呼吸光环 ──
   这是这张卡唯一的动效，而且是有语义的：只有真的有进程在跑才会呼吸。 */
.ov-dot {
  flex: none;
  width: 8px;
  height: 8px;
  margin-top: 6px;
  border-radius: var(--radius-pill);
  background: var(--text-muted);
  position: relative;
}
.ov-dot-idle {
  background: var(--border-subtle);
}
.ov-item-off .ov-dot {
  background: var(--warning);
}
.ov-dot-live {
  background: var(--success);
}
/* 光环是独立的伪元素：从点的大小扩散到 3 倍并淡出，
   scale 与 opacity 都走合成层，不触发重排 */
.ov-dot-live::after {
  content: '';
  position: absolute;
  inset: 0;
  border-radius: inherit;
  background: inherit;
  animation: ov-pulse 1.8s ease-out infinite;
}
@keyframes ov-pulse {
  0% {
    transform: scale(1);
    opacity: 0.55;
  }
  70% {
    transform: scale(3);
    opacity: 0;
  }
  100% {
    transform: scale(3);
    opacity: 0;
  }
}
/* 尊重系统的「减少动态效果」：关掉呼吸，只留常亮的点 */
@media (prefers-reduced-motion: reduce) {
  .ov-dot-live::after {
    animation: none;
  }
}

.hint {
  margin-top: 8px;
  font-size: 12px;
  color: var(--text-muted);
}
</style>
