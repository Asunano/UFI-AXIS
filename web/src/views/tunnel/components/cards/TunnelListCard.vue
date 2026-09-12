<template>
  <!--
    FRP 与 Cloudflare 合并成一张卡，卡头用按钮组切换。
    两边的条目本来就是同一个 Instance 类型、六个操作一一对应，所以列表模板只写一份
    （原先是两段近乎逐字相同的模板），由 kind 决定父组件调哪一组 handler。
    切换控件用 n-button-group 而不是 n-tabs：卡内切换在本项目统一是按钮组
    （见仪表盘 NetDetailChartCard），n-tabs 留给页面级分栏。
  -->
  <GridCard>
    <template #title>
      <n-button-group size="small">
        <n-button :type="isFrp ? 'primary' : 'default'" size="small" @click="emit('update:kind', 'frp')">
          FRP 通道
        </n-button>
        <n-button :type="isFrp ? 'default' : 'primary'" size="small" @click="emit('update:kind', 'cf')">
          Cloudflare 隧道
        </n-button>
      </n-button-group>
    </template>
    <template #extra>
      <n-space :size="6">
        <n-button size="tiny" type="primary" @click="emit('edit', '')">{{ isFrp ? '新建通道' : '新建隧道' }}</n-button>
        <n-button size="tiny" type="error" ghost :disabled="!group.running.length" @click="emit('stop-all-kind')">
          停止全部
        </n-button>
      </n-space>
    </template>

    <!-- 组件没装时先横一条提醒：配置照常可看可改，但启动一定失败，
         所以是横幅而不是把列表整个替换掉 -->
    <div v-if="!installed" class="warn-bar">
      <span class="warn-text">{{ binary }} 未安装，{{ isFrp ? '通道' : '隧道' }}无法启动</span>
      <n-button size="tiny" type="primary" @click="emit('open-components')">去安装</n-button>
    </div>

    <div v-if="!group.items.length" class="empty">
      <n-empty :description="isFrp ? '暂无 FRP 通道配置' : '暂无 Cloudflare 隧道'">
        <template v-if="!installed" #extra>
          <n-button size="small" type="primary" @click="emit('open-components')">先安装 {{ binary }}</n-button>
        </template>
      </n-empty>
    </div>
    <div v-else class="inst-list">
      <div v-for="it in group.items" :key="it.name" class="inst-item">
        <div class="inst-main">
          <div class="inst-title">
            <span class="inst-name">{{ it.name }}</span>
            <n-tag v-if="group.active === it.name" size="tiny" type="info" :bordered="false">选中</n-tag>
            <n-tag :type="statusTagType(it.status)" size="tiny" :bordered="false">{{ statusLabel(it.status) }}</n-tag>
            <n-tag v-if="!isFrp && !it.token_set" size="tiny" type="warning" :bordered="false">未设置 token</n-tag>
          </div>
          <!-- serverAddr / proxy 数只有 FRP 有 -->
          <div v-if="isFrp" class="inst-sub">
            {{ it.server_addr || '未配置 serverAddr'
            }}<template v-if="it.server_port">:{{ it.server_port }}</template> 　·　{{ it.proxy_count }} 个 proxy
          </div>
          <div v-if="it.last_error" class="inst-err">{{ it.last_error }}</div>
        </div>
        <n-space :size="4" class="inst-actions">
          <n-button
            v-if="!it.running"
            size="tiny"
            type="primary"
            :loading="busy === `${kind}-start-${it.name}`"
            @click="emit('start', it.name)"
            >启动</n-button
          >
          <n-button v-else size="tiny" :loading="busy === `${kind}-stop-${it.name}`" @click="emit('stop', it.name)"
            >停止</n-button
          >
          <n-button size="tiny" quaternary @click="emit('edit', it.name)">编辑</n-button>
          <n-button size="tiny" quaternary @click="emit('log', it.name)">日志</n-button>
          <n-button v-if="group.active !== it.name" size="tiny" quaternary @click="emit('activate', it.name)"
            >设为选中</n-button
          >
          <n-button size="tiny" quaternary type="error" @click="emit('delete', it.name)">删除</n-button>
        </n-space>
      </div>
    </div>
  </GridCard>
</template>

<script setup lang="ts">
import { computed } from 'vue';
import GridCard from '@/components/GridCard.vue';
import { statusLabel, statusTagType, type Instance } from '@/views/tunnel/tunnelShared';

const props = defineProps<{
  kind: 'frp' | 'cf';
  group: { active: string; running: string[]; items: Instance[] };
  /** 这类隧道依赖的二进制装了没 —— 没装时列表上方横一条提醒并给安装入口 */
  installed: boolean;
  /** frpc / cloudflared，仅用于文案 */
  binary: string;
  /** 形如 `frp-start-<name>` 的忙碌键，父组件那一份，用于给对应按钮上 loading */
  busy: string | null;
}>();

const emit = defineEmits<{
  'update:kind': ['frp' | 'cf'];
  'stop-all-kind': [];
  'open-components': [];
  start: [string];
  stop: [string];
  /** 空串 = 新建 */
  edit: [string];
  log: [string];
  activate: [string];
  delete: [string];
}>();

const isFrp = computed(() => props.kind === 'frp');
</script>

<style scoped>
/* 组件缺失横幅：配置还能看能改，只是启动会失败，所以是提醒而非拦截 */
.warn-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
  margin-bottom: 10px;
  padding: 8px 12px;
  border: 1px solid var(--warning);
  border-radius: var(--radius-sm);
  background: var(--warning-light);
}
.warn-text {
  font-size: 12px;
  color: var(--text-primary);
}
.empty {
  padding: 24px 0;
}
.inst-list {
  display: flex;
  flex-direction: column;
}
.inst-item {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  padding: 10px 0;
  border-bottom: 1px solid var(--border-subtle);
}
.inst-item:last-child {
  border-bottom: none;
}
.inst-main {
  min-width: 0;
  flex: 1;
}
.inst-title {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.inst-name {
  font-size: 13px;
  color: var(--text-primary);
  font-weight: 600;
}
.inst-sub {
  margin-top: 3px;
  font-size: 12px;
  color: var(--text-secondary);
  word-break: break-all;
}
.inst-err {
  margin-top: 3px;
  font-size: 12px;
  color: var(--error);
  word-break: break-all;
}
.inst-actions {
  flex-shrink: 0;
}

@media (max-width: 768px) {
  .inst-item {
    flex-wrap: wrap;
  }
  .inst-actions {
    width: 100%;
  }
}
</style>
