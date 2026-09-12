<template>
  <GridCard class="panel-card clients-card" title="连接设备">
    <template #extra>
      <n-tag size="small" type="info" :bordered="false">{{ clients.length }} 台在线</n-tag>
      <n-button size="small" quaternary type="primary" @click="emit('open-acl')">
        已拉黑 {{ blockedList.length }}
      </n-button>
    </template>

    <!-- 卡片高度已锁死、列表自己滚，所以不再截断到 8 条 ——
         原来的「+N 更多」是为了防止卡片被撑长，现在这个约束由 .clients-card 承担。 -->
    <div v-if="clients.length" class="client-preview">
      <div v-for="(c, i) in clients" :key="c.mac || i" class="client-preview-item">
        <span class="client-name">{{ c.hostname || c.ip_addr }}</span>
        <span class="client-mac">{{ c.mac }}</span>
        <!-- MAC 为空没法拉黑（设备名单以 MAC 为主键）；aclPending 只锁正在下发的那一行 -->
        <n-button
          v-if="c.mac"
          class="client-acl-btn"
          size="tiny"
          :type="blockedMacs.has(c.mac.toLowerCase()) ? 'default' : 'error'"
          :loading="aclPending === c.mac.toLowerCase()"
          :disabled="!!aclPending"
          @click="blockedMacs.has(c.mac.toLowerCase()) ? emit('unblock', c.mac) : emit('block', c)"
        >
          {{ blockedMacs.has(c.mac.toLowerCase()) ? '解除' : '拉黑' }}
        </n-button>
      </div>
    </div>
    <div v-else class="client-empty">暂无设备连接</div>
  </GridCard>
</template>

<script setup lang="ts">
/**
 * 已连接设备列表 + 拉黑入口。
 *
 * 名单与 pending 状态都来自 `useNetworkControls()`，它每次调用新建一套 ref
 * （不是模块级单例），所以这里只能收 props、把写操作 emit 回持有方 ——
 * 在本组件里再调一次 composable，拉黑写完覆盖的会是第二份名单，
 * 父组件的黑名单弹窗看到的还是旧的。
 *
 * `.clients-card` 的高度分配规则留在 DashboardView：那是「右栏怎么分高度」的
 * 布局决策（与左栏折线图卡对称），且它的两处媒体查询回退也写在那里，
 * 拆开只会让基础规则和覆盖规则分居两个文件。
 */
import GridCard from '@/components/GridCard.vue';
import type { WifiClient, WifiAclEntry } from '@/types';

defineProps<{
  clients: WifiClient[];
  blockedList: WifiAclEntry[];
  /** 已拉黑 MAC 的小写集合，用于判断每行按钮是「拉黑」还是「解除」 */
  blockedMacs: Set<string>;
  /** 正在下发的 MAC（小写）；'*' = 正在清空 */
  aclPending: string;
}>();

const emit = defineEmits<{
  (e: 'block', client: WifiClient): void;
  (e: 'unblock', mac: string): void;
  (e: 'open-acl'): void;
}>();
</script>

<style scoped>
/* 每行三段：名称（吃掉剩余宽度）| MAC | 拉黑按钮 */
.client-preview {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(260px, 1fr));
  gap: 4px 16px;
}
.client-preview-item {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto auto;
  align-items: center;
  gap: 8px;
  padding: 4px 0;
  font-size: 13px;
  color: var(--text-primary);
}
.client-name {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.client-mac {
  color: var(--text-muted);
  font-family: monospace;
  font-size: 12px;
}

@media (max-width: 768px) {
  /* 原来一行三段（名称 | MAC | 按钮）在 360px 上放不下，名称只剩几十像素。
     改成两列：左侧名称与 MAC 上下叠，右侧按钮跨两行居中。 */
  .client-preview {
    grid-template-columns: 1fr;
  }
  .client-preview-item {
    grid-template-columns: minmax(0, 1fr) auto;
    row-gap: 1px;
    padding: 6px 0;
  }
  .client-name {
    grid-area: 1 / 1 / 2 / 2;
  }
  .client-mac {
    grid-area: 2 / 1 / 3 / 2;
  }
  .client-acl-btn {
    grid-area: 1 / 2 / 3 / 3;
    align-self: center;
  }
}
</style>
