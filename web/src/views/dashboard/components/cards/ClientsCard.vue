<template>
  <GridCard class="panel-card clients-card" title="连接设备" :loading="loading">
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
        <!-- 主机名为主、IP 与 MAC 为次：原来 name/ip 挤在同一个 span 里，两者字号字色相同，
             一串 IP 看起来和主机名一样重要 -->
        <span class="client-main">
          <span class="client-name">{{ c.hostname || c.ip_addr || '--' }}</span>
          <span class="client-sub">
            <!-- 只有真的取到主机名时才在副行重复 IP，否则主行已经是 IP 了 -->
            <span v-if="c.hostname && c.ip_addr" class="client-ip">{{ c.ip_addr }}</span>
            <span class="client-mac">{{ c.mac || '--' }}</span>
          </span>
        </span>
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
  /** 首次取数是否仍在途：加载期显示骨架，不要先甩一句「暂无设备连接」 */
  loading: boolean;
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
/* 每行两段：设备信息（吃掉剩余宽度）| 拉黑按钮 */
.client-preview {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(260px, 1fr));
  gap: var(--space-1) var(--space-4);
}
.client-preview-item {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  align-items: center;
  gap: var(--space-2);
  padding: var(--space-1) 0;
  font-size: var(--font-base);
  color: var(--text-primary);
}
.client-main {
  display: flex;
  flex-direction: column;
  gap: 1px;
  /* min-width:0 + 两个子项各自省略号：MAC 是一长串无空格文本，
     不给收缩边界会把整行（连同右侧按钮）推出卡片 */
  min-width: 0;
}
.client-name {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.client-sub {
  display: flex;
  align-items: baseline;
  gap: var(--space-2);
  min-width: 0;
  font-size: var(--font-sm);
  color: var(--text-muted);
}
.client-ip {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.client-mac {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-family: monospace;
}

@media (max-width: 768px) {
  /* 原来一行三段（名称 | MAC | 按钮）在 360px 上放不下，名称只剩几十像素。
     现在信息本来就在一个纵向块里，窄屏只需把行距放松一点、按钮保持右侧居中。 */
  .client-preview {
    grid-template-columns: 1fr;
  }
  .client-preview-item {
    row-gap: 1px;
    padding: 6px 0;
  }
  .client-acl-btn {
    align-self: center;
  }
}
</style>
