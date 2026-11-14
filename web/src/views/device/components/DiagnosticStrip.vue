<template>
  <GridCard class="panel-card" title="诊断指标">
    <template #extra>
      <!-- 两组数据的刷新节奏不同，直接写在标题旁：连接数是 10s 轮询，QoS 读 core 的 5 分钟缓存 -->
      <span class="diag-hint">连接 10 秒 · 承载 5 分钟</span>
    </template>

    <div class="diag">
      <!-- 左组：socket 计数。顺序固定 TCP / TCP6 / UDP / UDP6 / Unix，与 core 的字段同名 -->
      <div class="diag-group diag-conn">
        <div v-for="item in connectionItems" :key="item.label" class="diag-item">
          <span class="diag-k">{{ item.label }}</span>
          <span class="diag-v">{{ item.value }}</span>
        </div>
      </div>

      <!-- 右组：EPS 承载参数。`available:false` 时父组件已把三项统一成 `—`，这里不再判断 -->
      <div class="diag-group diag-bearer">
        <div class="diag-item">
          <span class="diag-k">QCI</span>
          <span class="diag-v">{{ qosQci }}</span>
        </div>
        <div class="diag-item">
          <span class="diag-k">下行 AMBR</span>
          <span class="diag-v">{{ qosDown }}</span>
        </div>
        <div class="diag-item">
          <span class="diag-k">上行 AMBR</span>
          <span class="diag-v">{{ qosUp }}</span>
        </div>
      </div>
    </div>

    <!--
      失败只占行末一句，不把整条带子换成错误卡：
      读数沿用上一次（“刚才的值”仍然有用），但必须说明这次没读到，否则和「设备真的没有连接」分不开。
    -->
    <p v-if="failedText" class="diag-failed" role="status">
      {{ failedText }}
      <button type="button" class="diag-retry" @click="emit('retry')">重试</button>
    </p>
  </GridCard>
</template>

<script setup lang="ts">
/**
 * 诊断指标条（2026-11-11 设备页重排）。
 *
 * 为什么并成一条而不是两张卡：连接计数与承载参数都是**诊断量**，不是决策量。
 * 它们原来各占一张卡（连接统计 5 个等分瓦片、承载 QoS 一条只放 3 个键值对的通栏），
 * 在 1440 下白占 144 + 103px，窄屏下连接统计还会退化成 5 个满宽瓦片（500px）。
 * 现在 8 个数字共用一条带子，两组之间一条竖分隔线。
 *
 * 组件只负责渲染：取数、失败标记、`—` 的归一化都在 `DeviceView`（那里才知道
 * 每张卡的三态与重试对应哪个端点）。
 */
import { computed } from 'vue';
import GridCard from '@/components/GridCard.vue';

const props = defineProps<{
  /** 连接计数；null = 这次或从未读到 */
  connections: {
    tcp: number | null;
    tcp6: number | null;
    udp: number | null;
    udp6: number | null;
    unix: number | null;
  };
  /** QoS 三项：父组件已按 `available` / 缺字段归一成 `—` 或可读文案 */
  qosQci: string;
  qosDown: string;
  qosUp: string;
  /** 非空 = 有失败要说明（连接 / 承载都可能），行末附「重试」 */
  failedText?: string;
}>();

const emit = defineEmits<{ retry: [] }>();

const connectionItems = computed(() => [
  { label: 'TCP', value: props.connections.tcp ?? '—' },
  { label: 'TCP6', value: props.connections.tcp6 ?? '—' },
  { label: 'UDP', value: props.connections.udp ?? '—' },
  { label: 'UDP6', value: props.connections.udp6 ?? '—' },
  { label: 'Unix', value: props.connections.unix ?? '—' },
]);
</script>

<style scoped>
/* 小字色统一用公共 --text-secondary：全站 --text-muted(#999) 在白底只有 2.85:1，
   11px 的标签用它达不到 AA；--text-secondary 在明暗两档都 ≥5:1。 */
.diag-hint {
  font-size: var(--font-sm);
  color: var(--text-secondary);
  white-space: nowrap;
}

.diag {
  display: grid;
  grid-template-columns: 5fr 3fr;
  align-items: start;
}

.diag-group {
  display: grid;
  grid-auto-flow: column;
  grid-auto-columns: minmax(0, 1fr);
  gap: var(--space-2);
  min-width: 0;
}

/* 两组之间的分隔线：只画一条竖线，不加底色/圆角（同一条带子里的两组数据，不是两张卡） */
.diag-bearer {
  border-left: 1px solid var(--border-subtle);
  padding-left: var(--space-4);
  margin-left: var(--space-4);
}

.diag-item {
  display: grid;
  gap: 1px;
  min-width: 0;
}

.diag-k {
  font-size: var(--font-xs);
  color: var(--text-secondary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.diag-v {
  font-size: var(--font-lg);
  font-weight: 650;
  letter-spacing: -0.01em;
  font-variant-numeric: tabular-nums;
  color: var(--text-primary);
}

.diag-failed {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  margin: var(--space-3) 0 0;
  font-size: var(--font-sm);
  color: var(--dv-warn, var(--warning));
}

.diag-retry {
  appearance: none;
  border: 0;
  background: none;
  padding: 0;
  font: inherit;
  font-size: var(--font-sm);
  /* 与父页面的 --dv-link 同一口径：--accent-color 在浅底做小字链接对比不足 */
  color: var(--dv-link, #0f7a43);
  cursor: pointer;
  text-decoration: underline;
  text-underline-offset: 3px;
}

/* 窄屏：一组一行放不下 5 个数字，改成 2 列（连接）+ 3 列（承载），竖线变横线 */
@media (max-width: 900px) {
  .diag {
    grid-template-columns: 1fr;
    gap: var(--space-3);
  }
  .diag-group {
    grid-auto-flow: row;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    row-gap: var(--space-3);
  }
  .diag-bearer {
    grid-template-columns: repeat(3, minmax(0, 1fr));
    border-left: 0;
    padding-left: 0;
    margin-left: 0;
    border-top: 1px solid var(--border-subtle);
    padding-top: var(--space-3);
  }
}
</style>
