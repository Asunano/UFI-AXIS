<template>
  <!--
    FRP 与 Cloudflare 合并成一张卡，卡头用按钮组切换。
    两边的条目本来就是同一个 Instance 类型、六个操作一一对应，所以列表模板只写一份
    （原先是两段近乎逐字相同的模板），由 kind 决定父组件调哪一组 handler。
    切换控件用 n-button-group 而不是 n-tabs：卡内切换在本项目统一是按钮组
    （见仪表盘 NetDetailChartCard），n-tabs 留给页面级分栏。

    2026-09-21 行版式重排。原先每行是「左侧两行文字 / 右侧 6 颗 tiny 按钮」：
    - 6 颗按钮同尺寸同层级，启停（每天点）与删除（几乎不点）并排，窄屏下还铺满整行，
      一条隧道吃掉三行高度。现在只留主操作，其余进 ⋯ 菜单；
    - 状态原先靠一个 tiny tag 表达，「选中」也是一个 tiny tag，一行里挂四个 tag 时
      哪个都抓不住视线。现在运行态交给左侧状态点，「选中」交给行左侧的 accent 竖条，
      tag 只留真正异常的情况；
    - server_addr/proxy 数原先和 tag 挤在同一行副标题里，长地址 break-all 能把行撑成三行。
      现在它是独立的一列，单行省略号截断；
    - last_error 原先是一行裸红字，同样会 break-all 撑高。现在是一条 error-light 提示带，
      最多两行并自带「查看日志」出口。
  -->
  <GridCard>
    <template #title>
      <n-button-group size="small">
        <n-button :type="isFrp ? 'primary' : 'default'" size="small" @click="emit('update:kind', 'frp')">
          FRP 通道
          <!-- 计数挂在切换按钮上：不点过去也知道另一边有几条在跑 -->
          <span class="seg-count" :class="{ 'is-on': isFrp }">{{ counts.frp.running }}/{{ counts.frp.total }}</span>
        </n-button>
        <n-button :type="isFrp ? 'default' : 'primary'" size="small" @click="emit('update:kind', 'cf')">
          Cloudflare 隧道
          <span class="seg-count" :class="{ 'is-on': !isFrp }">{{ counts.cf.running }}/{{ counts.cf.total }}</span>
        </n-button>
      </n-button-group>
    </template>
    <template #extra>
      <n-space :size="6">
        <n-button size="small" type="primary" @click="emit('edit', '')">{{ isFrp ? '新建通道' : '新建隧道' }}</n-button>
        <n-button size="small" :disabled="!group.running.length" @click="emit('stop-all-kind')">停止全部</n-button>
      </n-space>
    </template>

    <!-- 组件没装时先横一条提醒：配置照常可看可改，但启动一定失败，
         所以是横幅而不是把列表整个替换掉 -->
    <div v-if="!installed" class="warn-bar">
      <i class="dot dot-warn"></i>
      <span>{{ binary }} 未安装，{{ isFrp ? '通道' : '隧道' }}无法启动。配置可以照常新建与修改。</span>
      <n-button size="tiny" type="primary" @click="emit('open-components')">去安装</n-button>
    </div>

    <div v-if="!group.items.length" class="empty">
      <n-empty :description="isFrp ? '暂无 FRP 通道配置' : '暂无 Cloudflare 隧道'">
        <template v-if="!installed" #extra>
          <n-button size="small" type="primary" @click="emit('open-components')">先安装 {{ binary }}</n-button>
        </template>
      </n-empty>
    </div>
    <div v-else class="rows">
      <div v-for="it in group.items" :key="it.name" class="row" :class="{ 'row-active': group.active === it.name }">
        <div class="row-name">
          <i class="dot" :class="dotClass(it)"></i>
          <b :title="it.name">{{ it.name }}</b>
          <span v-if="group.active === it.name" class="tag tag-accent">选中</span>
          <!-- 运行/停止已由状态点表达，tag 只留异常这一种需要文字的情况 -->
          <span v-if="it.status === 'Error'" class="tag tag-err">{{ statusLabel(it.status) }}</span>
          <span v-if="!isFrp && !it.token_set" class="tag tag-warn">未设置 token</span>
        </div>

        <div class="row-meta" :title="metaText(it)">{{ metaText(it) }}</div>

        <div class="row-act">
          <n-button
            v-if="it.running"
            size="small"
            :loading="busy === `${kind}-stop-${it.name}`"
            @click="emit('stop', it.name)"
          >
            停止
          </n-button>
          <!-- CF 且没 token：启动必然失败，主操作换成去填 token（同一个编辑弹窗） -->
          <n-button v-else-if="!isFrp && !it.token_set" size="small" type="primary" @click="emit('edit', it.name)">
            填 token
          </n-button>
          <n-button
            v-else
            size="small"
            type="primary"
            :loading="busy === `${kind}-start-${it.name}`"
            @click="emit('start', it.name)"
          >
            启动
          </n-button>

          <n-dropdown :options="rowOptions(it)" trigger="click" @select="(k: string) => onRowAction(k, it.name)">
            <n-button size="small" quaternary aria-label="更多操作">
              <template #icon>
                <n-icon><EllipsisHorizontalOutline /></n-icon>
              </template>
            </n-button>
          </n-dropdown>
        </div>

        <div v-if="it.last_error" class="row-err">
          <n-icon :size="14"><AlertCircleOutline /></n-icon>
          <p>{{ it.last_error }}</p>
          <button type="button" @click="emit('log', it.name)">查看日志</button>
        </div>
      </div>
    </div>
  </GridCard>
</template>

<script setup lang="ts">
import { computed } from 'vue';
import type { DropdownOption } from 'naive-ui';
import { AlertCircleOutline, EllipsisHorizontalOutline } from '@vicons/ionicons5';
import GridCard from '@/components/GridCard.vue';
import { statusLabel, type Instance } from '@/views/tunnel/tunnelShared';

/** 一类隧道的「在跑 / 总数」。两类都要，因为计数挂在两颗切换按钮上。 */
interface KindCount {
  running: number;
  total: number;
}

const props = defineProps<{
  kind: 'frp' | 'cf';
  group: { active: string; running: string[]; items: Instance[] };
  /** 两类各自的条数，用于切换按钮上的计数 */
  counts: { frp: KindCount; cf: KindCount };
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

/** 状态点：在跑（绿，带呼吸）/ 异常（红）/ 已停止（灰） */
function dotClass(it: Instance): string {
  if (it.running) return 'dot-live';
  return it.status === 'Error' ? 'dot-err' : '';
}

/** 行中间那一列。FRP 报 serverAddr 与 proxy 数，CF 只有 token 状态可报。 */
function metaText(it: Instance): string {
  if (!isFrp.value) return it.token_set ? 'token 已配置' : 'token 未配置，无法连接';
  const addr = it.server_addr ? `${it.server_addr}${it.server_port ? `:${it.server_port}` : ''}` : '未配置 serverAddr';
  return `${addr} · ${it.proxy_count ?? 0} 个 proxy`;
}

/**
 * 行 ⋯ 菜单。删除走 props.style 上色（本项目既有写法，见 FilesToolbar 的 sortOptions）。
 * 两处刻意的条件项：
 * - 已选中的行不再给「设为选中」（点了也没有变化）；
 * - 主操作已经是「填 token」时不再给「编辑 token」，同一个动作不摆两遍。
 */
function rowOptions(it: Instance): DropdownOption[] {
  const editable = isFrp.value || it.token_set;
  const options: DropdownOption[] = [];
  if (editable) options.push({ label: isFrp.value ? '编辑配置' : '编辑 token', key: 'edit' });
  options.push({ label: '查看日志', key: 'log' });
  if (props.group.active !== it.name) options.push({ label: '设为选中', key: 'activate' });
  options.push({ type: 'divider', key: 'd1' });
  options.push({
    label: isFrp.value ? '删除通道' : '删除隧道',
    key: 'delete',
    props: { style: 'color: var(--error)' },
  });
  return options;
}

function onRowAction(key: string, name: string) {
  if (key === 'edit') emit('edit', name);
  else if (key === 'log') emit('log', name);
  else if (key === 'activate') emit('activate', name);
  else if (key === 'delete') emit('delete', name);
}
</script>

<style scoped>
/* 切换按钮上的计数。选中那一侧压在主色底上，用卡片底色做成反白胶囊 + 主色数字 ——
   不用半透明白：那会在 scoped CSS 里新增一个写死颜色（check-ui-baseline 的
   scopedColorLiterals 会涨），而且换肤换掉主色后与新主色的关系无人复核。 */
.seg-count {
  margin-left: 5px;
  padding: 0 5px;
  border-radius: var(--radius-pill);
  background: var(--surface-hover);
  color: var(--text-muted);
  font-size: var(--font-xs);
  font-variant-numeric: tabular-nums;
}
.seg-count.is-on {
  background: var(--card-bg);
  color: var(--accent-color);
}

/* 组件缺失横幅：配置还能看能改，只是启动会失败，所以是提醒而非拦截 */
.warn-bar {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  flex-wrap: wrap;
  margin-bottom: var(--space-3);
  padding: var(--space-2) var(--space-3);
  border: 1px solid var(--warning);
  border-radius: var(--radius-sm);
  background: var(--warning-light);
  font-size: var(--font-sm);
}
.warn-bar span {
  flex: 1;
  min-width: 160px;
  color: var(--text-primary);
}
.empty {
  padding: var(--space-6) 0;
}

/* ── 行 ──
   分隔从「底部细线」换成「独立描边块 + 间距」：行内多了错误带之后，
   一条细线分不清错误属于上一条还是下一条。 */
.rows {
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
}
.row {
  position: relative;
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 0.9fr) auto;
  align-items: center;
  gap: var(--space-3);
  padding: var(--space-3);
  padding-left: var(--space-4);
  border: 1px solid var(--border-subtle);
  border-radius: var(--radius-sm);
  transition:
    background 0.16s ease,
    border-color 0.16s ease;
}
.row:hover {
  background: var(--surface-hover);
}
/* 选中：左竖条 + 主色描边 */
.row-active {
  border-color: var(--accent-color);
}
.row-active::before {
  content: '';
  position: absolute;
  left: 0;
  top: 10px;
  bottom: 10px;
  width: 3px;
  border-radius: 0 var(--radius-pill) var(--radius-pill) 0;
  background: var(--accent-color);
}
.row-name {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  min-width: 0;
}
.row-name b {
  font-size: var(--font-base);
  font-weight: 600;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.tag {
  flex: none;
  padding: 1px 7px;
  border-radius: var(--radius-pill);
  font-size: var(--font-xs);
  line-height: 1.5;
  background: var(--code-bg);
  color: var(--text-secondary);
}
.tag-accent {
  background: var(--accent-color-light);
  color: var(--accent-color);
}
.tag-warn {
  background: var(--warning-light);
  color: var(--warning);
}
.tag-err {
  background: var(--error-light);
  color: var(--error);
}
/* 关键信息：单行省略，完整值给 title */
.row-meta {
  font-size: var(--font-sm);
  color: var(--text-secondary);
  font-variant-numeric: tabular-nums;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.row-act {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  justify-self: end;
}
.row-err {
  grid-column: 1 / -1;
  display: flex;
  align-items: flex-start;
  gap: var(--space-2);
  padding: 7px var(--space-2);
  border-radius: var(--radius-sm);
  background: var(--error-light);
  color: var(--error);
  font-size: var(--font-sm);
  line-height: 1.5;
}
/* 最多两行：core 的 last_error 可能是一整段 dial 错误，铺开会把行挤成半屏 */
.row-err p {
  margin: 0;
  flex: 1;
  min-width: 0;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
.row-err button {
  flex: none;
  padding: 0;
  border: 0;
  border-bottom: 1px solid currentColor;
  background: none;
  color: inherit;
  font: inherit;
  font-weight: 600;
  cursor: pointer;
}

/* ── 状态点 ──
   与 TunnelRunBar 同一套语义（绿=在跑且呼吸 / 黄=缺组件 / 红=异常 / 灰=停止）。
   两处各写一份样式是刻意的：它只有 20 行，抽成公共组件反而要为"一个点"定一套 props。 */
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
.dot-err {
  background: var(--error);
}
.dot-live {
  background: var(--success);
}
.dot-live::after {
  content: '';
  position: absolute;
  inset: 0;
  border-radius: inherit;
  background: inherit;
  animation: row-pulse 1.8s ease-out infinite;
}
@keyframes row-pulse {
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
@media (prefers-reduced-motion: reduce) {
  .dot-live::after {
    animation: none;
  }
}

/* 窄屏：信息列换到第二行占满，名称与操作各守一侧。
   原实现是操作区占满整行铺 6 颗按钮。 */
@media (max-width: 768px) {
  .row {
    grid-template-columns: minmax(0, 1fr) auto;
  }
  .row-meta {
    grid-column: 1 / -1;
    white-space: normal;
  }
}
</style>
