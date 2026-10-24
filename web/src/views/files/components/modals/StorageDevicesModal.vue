<template>
  <!--
    存储与外接设备（2026-10-14 由工具栏 popover 改成的弹窗）。

    为什么必须从 popover 改成弹窗：
      · popover 是 teleport 到 body 的绝对定位层，**没有自己的高度上限**。磁盘多、
        远端源多时内容会一路顶出视口下沿，而它既不能滚、也不会被 mask 兜住；
      · 窄屏上 popover 还要和触发点抢横向空间，placement 翻转后仍可能贴边被裁；
      · 弹窗有 mask + 自身滚动区，360px 宽也能完整看完所有卷与远端源。

    弹窗内容 = 原来 popover 里的全部内容（本机卷读数 / 卷切换入口 / 远端源入口 /
    两个管理入口），只是把「点一下就地展开」换成「点一下开窗」。
  -->
  <n-modal
    v-model:show="show"
    preset="card"
    title="存储与外接设备"
    class="modal-fit storage-devices-modal"
    style="--modal-w: 560px"
    :bordered="false"
  >
    <!-- 宽高与滚动由全局 .modal-fit 统一约束：头尾固定，内容区自己滚，
         卡片不会被不可预知长度的列表顶出视口 -->
    <div class="sd-body">
      <!-- ── 本机卷（内部存储 / SD 卡 / U 盘）── -->
      <section class="sd-section">
        <div class="sd-section-head">
          <span class="sd-section-title">本机存储</span>
          <span class="sd-section-hint">{{ browsingRemote ? '当前浏览的是远端源' : '点击切换到该存储' }}</span>
        </div>

        <!-- 打开弹窗时就地刷新读数，所以先有一瞬间「列表还是空的」：
             那一段必须显示「正在读取」而不是「还没有读到」，否则每次打开都闪一次失败腔调 -->
        <div v-if="loading && !volumes.length" class="sd-empty">正在读取本机存储信息…</div>
        <div v-else-if="volumes.length" class="sd-vol-list">
          <button
            v-for="d in volumes"
            :key="d.mount || d.label"
            type="button"
            class="sd-vol"
            :class="{ 'is-current': isCurrentVolume(d.mount) }"
            :aria-current="isCurrentVolume(d.mount) ? 'true' : undefined"
            @click="pickVolume(d.mount)"
          >
            <div class="sd-vol-head">
              <span class="sd-vol-label">{{ d.label }}</span>
              <ValueBadge v-if="isCurrentVolume(d.mount)" class="sd-badge" size="sm" value="当前" />
              <span class="sd-vol-pct">{{ d.usedPct }}<small>% 已用</small></span>
            </div>

            <div class="sd-bar">
              <i
                :style="{
                  width: d.usedPct + '%',
                  background: d.usedPct > 90 ? 'var(--error)' : 'var(--accent-color)',
                }"
              />
            </div>

            <div class="sd-stats stat-grid">
              <div class="stat-cell">
                <span class="stat-label">可用空间</span>
                <span class="stat-value">{{ d.available }}</span>
              </div>
              <div class="stat-cell">
                <span class="stat-label">已用空间</span>
                <span class="stat-value">{{ d.used }}</span>
              </div>
              <div class="stat-cell">
                <span class="stat-label">总容量</span>
                <span class="stat-value">{{ d.size }}</span>
              </div>
            </div>

            <div v-if="d.mount" class="sd-mount">
              <span>挂载点</span><b class="mono">{{ d.mount }}</b>
            </div>
            <div v-if="d.mount && !isCurrentVolume(d.mount)" class="sd-enter">点击切换到此存储</div>
          </button>
        </div>
        <div v-else class="sd-empty">还没有读到本机存储信息，请稍后刷新。</div>
      </section>

      <!-- ── 远端存储源（2026-10-14）：只列启用中的源，停用的源在 core 侧同样不可解析 ── -->
      <section class="sd-section">
        <div class="sd-section-head">
          <span class="sd-section-title">远端存储源</span>
          <span class="sd-section-hint">FTP / WebDAV / SMB / S3</span>
        </div>

        <div v-if="remoteSources.length" class="sd-remote-list">
          <button
            v-for="s in remoteSources"
            :key="s.id"
            type="button"
            class="sd-remote"
            :class="{ 'is-current': isCurrentRemote(s.id) }"
            :aria-current="isCurrentRemote(s.id) ? 'true' : undefined"
            @click="pickRemote(s.id)"
          >
            <div class="sd-remote-main">
              <div class="sd-remote-title">
                <span class="sd-remote-label">{{ s.label || s.id }}</span>
                <ValueBadge class="sd-proto" size="sm" :value="protoLabel(s.protocol)" />
                <ValueBadge v-if="isCurrentRemote(s.id)" class="sd-badge" size="sm" value="当前" />
              </div>
              <div class="sd-remote-addr">{{ addrOf(s) }}</div>
              <div v-if="capsTextOf(s)" class="sd-remote-caps">{{ capsTextOf(s) }}</div>
            </div>
          </button>
        </div>
        <div v-else class="sd-empty">
          还没有配置远端存储源。配置后，远端目录会和本机卷一样出现在文件列表里，可直接浏览、上传与下载。
        </div>
      </section>

      <!-- 两个管理入口：原来贴在 popover 底部，现在放在内容区末尾（窄屏可换行，不挤页脚） -->
      <div class="sd-links">
        <n-button text type="primary" size="small" @click="emit('manage-sources')">管理远端存储源…</n-button>
        <n-button text type="primary" size="small" @click="emit('open-push')">
          远端推送作业{{ pushActive ? `（${pushActive} 个进行中）` : '' }}…
        </n-button>
      </div>
    </div>

    <template #footer>
      <div class="sd-footer">
        <n-button size="small" @click="show = false">关闭</n-button>
      </div>
    </template>
  </n-modal>
</template>

<script setup lang="ts">
/**
 * 存储与外接设备弹窗（2026-10-14 新建，替代 FilesToolbar 里的 n-popover）。
 *
 * ## 为什么不是「把 popover 调窄一点」
 * 原来的 `.disk-pop` 写死 264px，且每块磁盘都占一整段（标题 / 百分比 / 进度条 / 四行读数），
 * 两块盘 + 两个远端源就接近 600px 高。popover 没有 max-height，窄屏/矮屏直接顶出视口；
 * 它又是 teleport 的绝对定位层，出屏后既滚不到也关不掉（只能点空白处）。
 * 弹窗天生有 mask 和可滚动内容区，是这类「内容长度不可预知」的列表的正确容器。
 *
 * ## 数据从哪来
 * 全部由父组件（FilesView）持有并传入，本组件**不发请求**：
 *   · `disks`           ← `GET /api/files/disk-usage`
 *   · `storageSources`  ← `GET /api/storage/sources`
 * 这样就不会把 `loadStorageSources()` 里「拉完源再问一次推送摘要」的时序搬进弹窗，
 * 也不会因为弹窗开关而多一条轮询。父组件在打开时刷新一次（见 FilesView 的 watch）。
 *
 * ## 交互口径
 * 选卷/选远端源后**先 emit 再关窗**：与原来 popover 的行为一致 —— 用户点完就想看目录，
 * 弹窗留着会挡住刚打开的列表。
 */
import { computed } from 'vue';
import ValueBadge from '@/components/ValueBadge.vue';
import { REMOTE_PREFIX, capsTextOf, diskPercent, normalizePath, volumeRootOf } from '../../filesShared';

const props = withDefaults(
  defineProps<{
    show: boolean;
    /** `GET /api/files/disk-usage` 的 disks（字段由 core 给，形态不固定，沿用 any） */
    disks?: any[];
    /** 本机卷读数是否在读取中（打开弹窗时父组件会就地刷新一次） */
    loading?: boolean;
    /** `GET /api/storage/sources` 的 sources */
    storageSources?: any[];
    currentPath?: string;
    /** 进行中的远端推送作业数（`GET /api/files/remote-push` 的 active_count） */
    pushActive?: number;
  }>(),
  {
    disks: () => [],
    loading: false,
    storageSources: () => [],
    currentPath: '',
    pushActive: 0,
  }
);

const emit = defineEmits<{
  (e: 'update:show', v: boolean): void;
  /** 切换到某个本机卷（载荷是挂载点） */
  (e: 'navigate-to-volume', mount: string): void;
  /** 浏览某个远端源（载荷是源 id） */
  (e: 'navigate-to-remote', id: string): void;
  (e: 'manage-sources'): void;
  (e: 'open-push'): void;
}>();

const show = computed({
  get: () => props.show,
  set: (v: boolean) => emit('update:show', v),
});

/** 卷列表：把 core 的字段整理成模板要的形态（label / mount / 三个容量文案 / 百分比） */
const volumes = computed(() =>
  props.disks.map((d) => ({
    label: d.label || d.mount || '存储',
    mount: String(d.mount || ''),
    size: d.size || '--',
    used: d.used || '--',
    available: d.available || '--',
    usedPct: diskPercent(d),
  }))
);

/** 只列启用中的远端源：停用的源在 core 侧同样不参与 registry 解析，列出来点了会失败 */
const remoteSources = computed(() => (props.storageSources || []).filter((s) => s.enabled !== false));

const browsingRemote = computed(() => String(props.currentPath || '').startsWith(REMOTE_PREFIX));

/**
 * 当前所在卷的根。浏览远端源时**不把内部存储标成「当前」**：
 * `volumeRootOf('remote:xxx/')` 会回落到 PRIMARY_STORAGE，直接用会把「当前」徽标打错。
 */
const currentVolumeRoot = computed(() => (browsingRemote.value ? '' : volumeRootOf(props.currentPath)));

function isCurrentVolume(mount: string): boolean {
  if (!mount) return false;
  return normalizePath(mount) === currentVolumeRoot.value;
}

/**
 * 当前是否正在浏览某个远端源。
 * 必须按 `remote:<id>` **整段**比较：只用 startsWith(`remote:${id}`) 时，
 * 源 `a` 会把 `remote:ab/...` 也判成当前（id 前缀相撞）。
 */
function isCurrentRemote(id: string): boolean {
  const p = String(props.currentPath || '');
  return p === `${REMOTE_PREFIX}${id}` || p.startsWith(`${REMOTE_PREFIX}${id}/`);
}

function pickVolume(mount: string) {
  if (!mount || isCurrentVolume(mount)) return;
  emit('navigate-to-volume', mount);
  show.value = false;
}

function pickRemote(id: string) {
  if (!id || isCurrentRemote(id)) return;
  emit('navigate-to-remote', id);
  show.value = false;
}

const PROTO_LABEL: Record<string, string> = {
  ftp: 'FTP',
  webdav: 'WebDAV',
  smb: 'SMB',
  s3: 'S3',
};

function protoLabel(p: string): string {
  return PROTO_LABEL[p] || p || '远端';
}

/** 一行地址：S3 给 endpoint/bucket，其余给 host:port + 共享名/起始路径 */
function addrOf(s: any): string {
  if (s.protocol === 's3') {
    return `${s.endpoint || `s3.${s.region || 'us-east-1'}.amazonaws.com`} / ${s.bucket || '--'}`;
  }
  const port = s.port || (s.protocol === 'ftp' ? 21 : s.protocol === 'webdav' ? 80 : s.protocol === 'smb' ? 445 : 443);
  const share = s.share ? ` / ${s.share}` : '';
  const base = s.basePath && s.basePath !== '/' ? ` / ${s.basePath}` : '';
  return `${s.host || '--'}:${port}${share}${base}`;
}
</script>

<style scoped>
/**
 * 配色/圆角/间距一律走 main.css 的令牌，不写死取值（check-ui-baseline.mjs 卡增量）。
 * 自适应策略：
 *   · 宽高都交给全局 `.modal-fit`（main.css）：宽 = min(560px, 视口 − 32/24px)，
 *     高 = min(720px, 视口 − 48/24px)，头尾固定、内容区自己滚；
 *   · 卷卡片的三列读数在窄屏折成两列，长挂载点用 anywhere 断行，不产生横向溢出。
 */
.sd-body {
  min-width: 0;
}

.sd-section + .sd-section {
  margin-top: var(--space-4);
}

.sd-section-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: var(--space-2);
  margin-bottom: var(--space-2);
  flex-wrap: wrap;
}
.sd-section-title {
  font-size: var(--font-sm);
  font-weight: 600;
  color: var(--text-secondary);
}
.sd-section-hint {
  font-size: var(--font-xs);
  color: var(--text-muted);
}

.sd-vol-list,
.sd-remote-list {
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
}

/* 整张卡就是切换入口：用原生 button 拿键盘/回车，同时把浏览器默认外观清零 */
.sd-vol,
.sd-remote {
  appearance: none;
  display: block;
  width: 100%;
  margin: 0;
  padding: var(--space-3);
  border: 1px solid var(--border-subtle);
  border-radius: var(--radius-md);
  background: var(--card-bg);
  color: inherit;
  font: inherit;
  text-align: left;
  cursor: pointer;
  transition:
    background 0.15s ease,
    box-shadow 0.15s ease;
}
.sd-vol:hover,
.sd-remote:hover {
  background: var(--surface-hover);
}
.sd-vol.is-current,
.sd-remote.is-current {
  background: var(--accent-color-light);
  box-shadow: inset 3px 0 0 var(--accent-color);
  cursor: default;
}

.sd-vol-head {
  display: flex;
  align-items: baseline;
  gap: var(--space-2);
  flex-wrap: wrap;
}
.sd-vol-label {
  font-size: var(--font-md);
  font-weight: 600;
  color: var(--text-primary);
  overflow-wrap: anywhere;
}
/* 「当前」徽标与协议 chip 都是 ValueBadge（原先各写一份 999px 胶囊）；
   这里只保留「不许被长名字挤扁」这一条布局约束，配色交给组件与令牌。 */
.sd-badge {
  flex-shrink: 0;
}
.sd-vol-pct {
  margin-left: auto;
  font-size: var(--font-lg);
  font-weight: 600;
  color: var(--text-primary);
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
}
.sd-vol-pct small {
  margin-left: 2px;
  font-size: var(--font-xs);
  font-weight: 400;
  color: var(--text-muted);
}

.sd-bar {
  height: 8px;
  margin: var(--space-2) 0;
  border-radius: var(--radius-pill);
  background: var(--border-subtle);
  overflow: hidden;
}
.sd-bar > i {
  display: block;
  height: 100%;
  transition: width 0.4s ease;
}

/* 三列读数走全局 `.stat-grid / .stat-cell`，这里只把列数从 auto-fit 收窄成固定三列
   （scoped 选择器特异性高于 main.css，无需改全局） */
.sd-stats {
  grid-template-columns: repeat(3, minmax(0, 1fr));
}

.sd-mount {
  display: flex;
  align-items: baseline;
  gap: var(--space-2);
  margin-top: var(--space-2);
  font-size: var(--font-xs);
  color: var(--text-muted);
  min-width: 0;
}
.sd-mount > span {
  flex-shrink: 0;
}
.sd-mount > b {
  min-width: 0;
  font-weight: 400;
  color: var(--text-secondary);
  word-break: break-all;
}
.mono {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
}
.sd-enter {
  margin-top: var(--space-1);
  font-size: var(--font-xs);
  color: var(--accent-color);
}

.sd-remote-main {
  min-width: 0;
}
.sd-remote-title {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  flex-wrap: wrap;
}
.sd-remote-label {
  font-size: var(--font-md);
  font-weight: 600;
  color: var(--text-primary);
  overflow-wrap: anywhere;
}
.sd-proto {
  flex-shrink: 0;
}
.sd-remote-addr {
  margin-top: 2px;
  font-size: var(--font-sm);
  color: var(--text-secondary);
  word-break: break-all;
}
.sd-remote-caps {
  margin-top: 2px;
  font-size: var(--font-xs);
  color: var(--text-muted);
  overflow-wrap: anywhere;
}

.sd-empty {
  padding: var(--space-3);
  border: 1px dashed var(--border-subtle);
  border-radius: var(--radius-md);
  font-size: var(--font-sm);
  line-height: 1.6;
  color: var(--text-muted);
}

.sd-links {
  display: flex;
  align-items: center;
  gap: var(--space-4);
  flex-wrap: wrap;
  margin-top: var(--space-4);
  padding-top: var(--space-3);
  border-top: 1px solid var(--border-subtle);
}

.sd-footer {
  display: flex;
  justify-content: flex-end;
}

/* 窄屏（断点 2026-10-21 由 480 并回 768）：三列读数折两列，
   避免每列只剩 60px 时数字被拆行 */
@media (max-width: 768px) {
  .sd-stats {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
  .sd-section-head {
    align-items: flex-start;
  }
  .sd-vol,
  .sd-remote {
    padding: var(--space-2);
  }
  .sd-links {
    gap: var(--space-2);
  }
}
</style>
