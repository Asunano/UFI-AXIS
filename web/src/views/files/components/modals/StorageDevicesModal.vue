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
    style="--modal-w: 480px"
    :bordered="false"
  >
    <!-- 宽高与滚动由全局 .modal-fit 统一约束：头尾固定，内容区自己滚，
         卡片不会被不可预知长度的列表顶出视口 -->
    <div class="sd-body">
      <!-- 一行摘要：几个卷、几个远端源。不给"合计容量"——
           卷的容量是格式化字符串（17.0G），再解析回来求和容易和实际值不一致。 -->
      <p class="sd-sum">
        {{ volumes.length }} 个本机卷 · {{ remoteSources.length }} 个远端源
        <span v-if="browsingRemote" class="sd-sum-note">当前在远端源</span>
      </p>

      <!-- ── 本机存储：一行一个卷，点整行切换 ── -->
      <div class="sd-label">本机存储</div>
      <div v-if="loading && !volumes.length" class="sd-empty">正在读取本机存储信息…</div>
      <div v-else-if="volumes.length" class="sd-list">
        <button
          v-for="d in volumes"
          :key="d.mount || d.label"
          type="button"
          class="sd-row"
          :class="{ 'is-current': isCurrentVolume(d.mount) }"
          :aria-current="isCurrentVolume(d.mount) ? 'true' : undefined"
          :disabled="!d.mount"
          @click="pickVolume(d.mount)"
        >
          <span class="sd-glyph">▣</span>
          <span class="sd-who">
            <span class="sd-name" :title="d.label">{{ d.label }}</span>
            <span class="sd-kind" :title="d.mount">{{ d.mount || '未知挂载点' }}</span>
          </span>
          <span class="sd-size">
            <b>{{ d.available }}</b>
            <small>共 {{ d.size }}</small>
          </span>
          <span v-if="isCurrentVolume(d.mount)" class="sd-tag">当前</span>
          <span v-else class="sd-switch">切换</span>
        </button>
      </div>
      <div v-else class="sd-empty">还没有读到本机存储信息，请稍后刷新。</div>

      <!-- ── 远端存储源：同一套行，协议走 chip；点整行切换 ── -->
      <div class="sd-label">远端存储源</div>
      <div v-if="remoteSources.length" class="sd-list">
        <button
          v-for="s in remoteSources"
          :key="s.id"
          type="button"
          class="sd-row"
          :class="{ 'is-current': isCurrentRemote(s.id) }"
          :aria-current="isCurrentRemote(s.id) ? 'true' : undefined"
          @click="pickRemote(s.id)"
        >
          <span class="sd-proto">{{ protoLabel(s.protocol) }}</span>
          <span class="sd-who">
            <span class="sd-name" :title="s.label || s.id">{{ s.label || s.id }}</span>
            <span class="sd-kind mono" :title="addrOf(s)">{{ addrOf(s) }}</span>
          </span>
          <span v-if="isCurrentRemote(s.id)" class="sd-tag">当前</span>
          <span v-else class="sd-switch">切换</span>
        </button>
      </div>
      <div v-else class="sd-empty">
        还没有配置远端存储源。配置后，远端目录会和本机卷一样出现在文件列表里，可直接浏览、上传与下载。
      </div>

      <div class="sd-links">
        <n-button text type="primary" size="small" @click="emit('manage-sources')">管理远端存储源…</n-button>
        <n-button text type="primary" size="small" @click="emit('open-push')">
          远端推送作业{{ pushActive ? `（${pushActive} 个进行中）` : '' }}…
        </n-button>
      </div>
    </div>
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
import { REMOTE_PREFIX, diskPercent, normalizePath, volumeRootOf } from '../../filesShared';

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
 * 2026-11-07 改版：从"一个卷一张卡（标题/进度条/三列读数/挂载点）"改成
 * **一行一个卷**，本机卷与远端源共用同一套行样式。
 *
 * 取舍：卡片能塞下比例条与三列读数，但四张卡就要滚屏，且同一套字段重复四遍；
 * 个人设备上"哪个还有空间"才是主问题，一行里"可用 / 总量 + 当前/切换"足够回答。
 * 宽高与滚动仍交给全局 `.modal-fit`（头尾固定、内容区自己滚）。
 */
.sd-body {
  min-width: 0;
}

.sd-sum {
  margin: 0 0 var(--space-2);
  font-size: 12px;
  color: var(--text-muted);
}
.sd-sum-note {
  margin-left: 6px;
  color: var(--accent-color);
}

.sd-label {
  /* 吸顶：滚动容器是 naive 的 .n-card-content，长列表里始终能看到"自己在看哪一段" */
  position: sticky;
  top: 0;
  z-index: 1;
  background: var(--card-bg);
  margin: var(--space-3) 0 var(--space-1);
  font-size: 11px;
  font-weight: 600;
  letter-spacing: 0.04em;
  color: var(--text-muted);
}

.sd-list {
  display: flex;
  flex-direction: column;
}

/* 一行：图标 / 名称+地址 / 可用+总量 / 当前|切换 */
.sd-row {
  display: flex;
  align-items: center;
  gap: 10px;
  width: 100%;
  padding: 10px 6px;
  border: 0;
  border-top: 1px solid var(--border-subtle);
  background: transparent;
  color: inherit;
  font: inherit;
  text-align: left;
  cursor: pointer;
  border-radius: var(--radius-sm);
}
.sd-row:hover:not(:disabled) {
  background: var(--surface-hover);
}
.sd-row.is-current {
  background: var(--accent-color-light);
  cursor: default;
}
.sd-row:disabled {
  cursor: default;
  opacity: 0.7;
}

.sd-glyph,
.sd-proto {
  flex: none;
  width: 30px;
  height: 26px;
  display: grid;
  place-items: center;
  border-radius: var(--radius-sm);
  background: var(--surface-elevated);
  color: var(--text-secondary);
  font-size: 13px;
}
.sd-proto {
  width: 52px;
  font-size: 10.5px;
  font-weight: 700;
  letter-spacing: 0.02em;
  color: var(--accent-color);
  background: var(--accent-color-light);
}

.sd-who {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 1px;
}
.sd-name {
  font-size: 13px;
  font-weight: 600;
  color: var(--text-primary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.sd-kind {
  font-size: 11px;
  color: var(--text-muted);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.sd-kind.mono {
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
}

.sd-size {
  flex: none;
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 1px;
}
.sd-size b {
  font-size: 13px;
  font-variant-numeric: tabular-nums;
  color: var(--text-primary);
}
.sd-size small {
  font-size: 11px;
  color: var(--text-muted);
  font-variant-numeric: tabular-nums;
}

/* 当前 = 标签；其余 = 一个不抢眼的「切换」提示（整行都可点） */
.sd-tag {
  flex: none;
  padding: 1px 8px;
  border-radius: 999px;
  font-size: 11px;
  line-height: 18px;
  color: var(--accent-color);
  background: var(--accent-color-light);
}
.sd-switch {
  flex: none;
  padding: 2px 9px;
  border: 1px solid var(--border-subtle);
  border-radius: var(--radius-sm);
  font-size: 12px;
  color: var(--text-secondary);
}
.sd-row:hover:not(:disabled) .sd-switch {
  border-color: var(--accent-color);
  color: var(--accent-color);
}

.sd-empty {
  padding: 10px 6px;
  font-size: 12px;
  line-height: 1.5;
  color: var(--text-muted);
  border-top: 1px solid var(--border-subtle);
}

.sd-links {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  margin-top: var(--space-3);
}

/* 窄屏（≤420，含 320 宽的老机型）：模态宽 = 视口 − 24，内容区再减 padding，
   360 下留给"名称"的宽度只剩 ~110px。收紧图标/间距/次要字号，把空间让给名称，
   名称与地址本来就有 ellipsis，长卷名不会撑破布局。行高仍 ≥44px（触屏最小命中区）。 */
@media (max-width: 420px) {
  .sd-row {
    gap: 8px;
    padding: 9px 4px;
  }
  .sd-glyph {
    width: 26px;
  }
  .sd-proto {
    width: 46px;
    font-size: 10px;
  }
  .sd-size b {
    font-size: 12.5px;
  }
  .sd-switch {
    padding: 2px 7px;
    font-size: 11.5px;
  }
  .sd-size small {
    /* 手机上"共 XX"不是首要信息，让出来的宽度给名称/挂载点 */
    display: none;
  }
  .sd-links {
    gap: 8px;
  }
}
</style>
