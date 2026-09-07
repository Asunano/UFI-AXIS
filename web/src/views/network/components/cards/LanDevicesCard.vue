<template>
  <GridCard :title="`局域网设备 (${clients.length})`">
    <!-- 顶部入口：已拉黑设备（点进去可单个解除 / 全部解除） -->
    <button class="acl-entry" @click="emit('open-acl')">
      <span class="acl-entry-text">查看已拉黑设备</span>
      <span class="acl-entry-count">{{ blockedList.length ? `${blockedList.length} 台` : '暂无' }}</span>
    </button>
    <div v-if="clients.length" class="lan-list">
      <div class="lan-row lan-row-head">
        <span>名称</span>
        <span>IP</span>
        <span>MAC</span>
        <span></span>
      </div>
      <div v-for="(c, i) in clients" :key="i" class="lan-row">
        <span class="lan-name">{{ c.hostname || c.ip_addr }}</span>
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
    </div>
    <div v-else class="hint-text">暂无连接设备</div>
  </GridCard>
</template>

<script setup lang="ts">
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
  'block': [WifiClient];
  'unblock': [string];
}>();
</script>

<style scoped>
.hint-text {
  font-size: 13px;
  color: var(--text-muted);
}
.lan-list {
  display: flex;
  flex-direction: column;
}
.lan-row {
  display: grid;
  grid-template-columns: minmax(0, 1.2fr) minmax(0, 1fr) minmax(0, 1.3fr) 56px;
  align-items: center;
  gap: 12px;
  padding: 8px 0;
  border-bottom: 1px solid var(--border-subtle);
  font-size: 13px;
}
.lan-row:last-child {
  border-bottom: none;
}
.lan-row-head {
  font-size: 11px;
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
  font-size: 12px;
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
  font-size: 13px;
  cursor: pointer;
}
.acl-entry:hover {
  border-color: var(--border-strong, var(--border-subtle));
}
.acl-entry-count {
  color: var(--text-muted);
  font-size: 12px;
}

@media (max-width: 768px) {
  .lan-row {
    grid-template-columns: 1fr;
    gap: 2px;
  }
  .lan-row-head {
    display: none;
  }
}
</style>
