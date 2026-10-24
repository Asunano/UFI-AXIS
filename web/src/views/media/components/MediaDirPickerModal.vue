<!--
  扫描目录选择弹窗（视频 / 音乐 / 图片三页共用，2026-10-14）。

  ## 为什么存在 / 为什么是这个形态
  「扫描范围」要能选目录，而**本仓没有可复用的目录选择器组件**：既有的那套实现
  （`views/settings/panels/ServicePanel.vue` 的 DLNA 共享目录选择器）是**内联在 SFC 里**的
  一大段模板 + 函数，且那个文件不属于本次改动范围（settings 由别人负责）。
  于是这里**照搬它的机制**而不是另造一套目录树：
    · 面包屑 + 一层一层点进去，取数走 `GET /api/files/list?path=…`（与文件页同源）；
    · 起点是 `/storage` 而不是 `/storage/emulated/0` —— 后者会让外接 SD 卡
      （`/storage/XXXX-XXXX`）永远看不见（ServicePanel 里那条注释就是这么写的）；
    · 列 `/storage` 时并上 `GET /api/files/disk-usage` 的真实卷：Android 11+ 上
      `listFiles('/storage')` 可能只见 emulated/self，SD 卡会"消失"。
  差异只有两处，都是为了媒体扫描范围这个场景：
    1. 只允许在**媒体白名单**内导航（`/storage`、`/sdcard`、`/mnt/media_rw`，与 core
       `MediaRoutes.ALLOWED_DIR_PREFIXES` 逐字一致）—— 走不到 core 会 400 的路径上；
       `remote:` 远端源一并挡掉（媒体索引只认本机卷），`/storage/self` 这个指向内部存储的
       符号链接别名也不给选（core 会原样存字符串，而 MediaStore 里是
       `/storage/emulated/0/…`，配出来是个静默的空库）。
    2. 多一个「选择当前目录」：扫描目录常常就是卷根或某一级目录本身，
       只能选"子目录"的话用户得先点进去再退回来。

  ## 为什么是独立组件而不是塞进 MediaScanDirsSettings
  那个组件是浮层里的区块（列表 + 增删 + 保存），弹窗是另一层交互（导航 + 多选 + 确定）。
  混在一起会让浮层在打开弹窗时也持有浏览状态（面包屑、子目录、浏览中标志），
  关掉浮层这些状态就成了没人清理的残留。

  ## 否决过的替代方案
  · **`<n-tree>` / `<n-cascader>`**：两者都要一次性拿到整棵树，而 `/api/files/list`
    是**按层**给的（一层几十上百个目录），要拼树就得递归拉全卡 —— 在一个随身 WiFi 上
    这是几十秒的空转，而且用户通常只想去 Music / Movies 这一层。
  · **手填绝对路径就够**：路径记错一个字母会静默扫出空列表，而"目录不存在"是不报错的
    （core 刻意不要求目录存在）。所以保留手填（见 MediaScanDirsSettings），但浏览必须有。
-->
<template>
  <n-modal
    :show="show"
    class="modal-fit"
    preset="card"
    title="选择扫描目录"
    style="--modal-w: 520px; --modal-h: 640px"
    @update:show="(v: boolean) => emit('update:show', v)"
  >
    <div class="dp-path">
      <span class="dp-path-text" :title="browsePath">{{ browsePath }}</span>
      <n-button size="tiny" :disabled="!canPickMore && !isPicked(browsePath)" @click="toggle(browsePath)">
        {{ isPicked(browsePath) ? '取消选择当前目录' : '选择当前目录' }}
      </n-button>
    </div>

    <n-breadcrumb class="dp-crumbs">
      <n-breadcrumb-item v-for="c in crumbs" :key="c.path" @click="browse(c.path)">
        {{ c.label }}
      </n-breadcrumb-item>
    </n-breadcrumb>

    <n-spin :show="browsing">
      <div class="dp-list">
        <div v-for="d in children" :key="d.path" class="dp-row">
          <!-- 名字是按钮、选择是另一个按钮，两者必须**兄弟**：
               按钮里套按钮是非法 HTML，浏览器会把内层提出来（音频页队列行同一个坑）。 -->
          <button type="button" class="dp-name" :title="d.path" @click="browse(d.path)">
            <n-icon :size="14"><FolderOpenOutline /></n-icon>
            <span class="dp-name-text">{{ d.name }}</span>
          </button>
          <n-button
            size="tiny"
            :type="isPicked(d.path) ? 'primary' : 'default'"
            :disabled="!isPicked(d.path) && !canPickMore"
            @click="toggle(d.path)"
          >
            {{ isPicked(d.path) ? '已选' : '选择' }}
          </n-button>
        </div>
        <div v-if="!children.length && !browsing" class="dp-empty">
          {{ browseFail || '这个目录下没有子目录' }}
        </div>
      </div>
    </n-spin>

    <div class="dp-picked">
      <div v-for="p in picked" :key="p" class="dp-picked-row">
        <span class="dp-picked-path" :title="p">{{ p }}</span>
        <n-button size="tiny" quaternary aria-label="移除" @click="toggle(p)">✕</n-button>
      </div>
      <div v-if="!picked.length" class="dp-empty">还没有选任何目录</div>
    </div>

    <template #footer>
      <div class="dp-foot">
        <span class="dp-count">已选 {{ picked.length }} 个{{ max ? `（最多 ${max} 个）` : '' }}</span>
        <n-space>
          <n-button size="small" @click="emit('update:show', false)">取消</n-button>
          <n-button size="small" type="primary" @click="confirm">确定</n-button>
        </n-space>
      </div>
    </template>
  </n-modal>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { FolderOpenOutline } from '@vicons/ionicons5';
import { useCancellableApi } from '@/composables/useCancellableApi';
import { breadcrumbSegments, type FileEntry } from '@/views/files/filesShared';

const props = defineProps<{
  /** 弹窗显隐（`v-model:show`）。 */
  show: boolean;
  /** 打开时已经选中的目录（= 当前配置），用户可以在它基础上增删。 */
  initial: string[];
  /** 可选条数上限。0 = 不限制（调用方没传时用）。 */
  max?: number;
}>();

const emit = defineEmits<{
  (e: 'update:show', value: boolean): void;
  /** 确定时把选中的目录整体交出去（父组件负责与自己的列表合并 / 替换）。 */
  (e: 'confirm', dirs: string[]): void;
}>();

// 模板里直接用 props 名，这里显式引一次以免 noUnusedLocals 报错（与 PlaylistPanel 同做法）
void props;

/**
 * 浏览起点。
 *
 * `/storage` 而不是 `/storage/emulated/0`：内部存储与 SD 卡（`/storage/XXXX-XXXX`）
 * 在它下面平级可见，写死 emulated/0 就永远看不到外接卷（ServicePanel 里那条注释的结论）。
 */
const BROWSE_ROOT = '/storage';

const api = useCancellableApi();
const browsePath = ref(BROWSE_ROOT);
const children = ref<{ path: string; name: string }[]>([]);
const browsing = ref(false);
const picked = ref<string[]>([]);
/** 上一次浏览的失败原因（core 的 400 文案优先）。空串 = 没有失败。 */
const browseFail = ref('');

const maxCount = computed(() => props.max ?? 0);
const canPickMore = computed(() => maxCount.value === 0 || picked.value.length < maxCount.value);
const crumbs = computed(() => breadcrumbSegments(browsePath.value));

/**
 * 可浏览 / 可选的路径判据：与 core 的**媒体**白名单同一口径
 * （`MediaRoutes.ALLOWED_DIR_PREFIXES` = `/storage`、`/sdcard`、`/mnt/media_rw`，分隔符敏感）。
 *
 * 刻意不复用 `filesShared.isAllowedRoot`：它额外把 `/storage` 与 `/storage/emulated`
 * 这两个**中间层**判成不可进（文件页的既有结论 —— 进去只看得到系统占位结构）。
 * 但扫描目录的浏览必须从 `/storage` 出发（SD 卡与内部存储只在它下面平级可见），
 * 也得穿过 `/storage/emulated` 才到得了内部存储，所以这里要的是"core 会不会收"，
 * 而不是"文件页愿不愿意让用户点进去"。
 */
const MEDIA_WHITELIST = ['/storage', '/sdcard', '/mnt/media_rw'];

function inMediaWhitelist(path: string): boolean {
  return MEDIA_WHITELIST.some((pre) => path === pre || path.startsWith(`${pre}/`));
}

/**
 * 内部存储的**符号链接别名**：`/storage/self` → `/storage/emulated/0`。
 *
 * 列出来但不能进、不能选：core 把字符串**原样**存进配置，而 MediaStore 里的路径是
 * `/storage/emulated/0/…` —— `/list` 的过滤是 `path.startsWith("$dir/")`，
 * 用别名配出来的结果是一个**静默的空库**（不报错、也没有任何提示）。
 * `/sdcard` 是同一类别名，但它不会出现在 `/storage` 的子目录里，手填那一侧由
 * `filesShared.normalizePath` 归一。
 */
function isAliasDir(path: string): boolean {
  return path === '/storage/self' || path.startsWith('/storage/self/');
}

/** 能不能进这个目录 / 能不能把它选成扫描目录。 */
function canEnter(path: string): boolean {
  return inMediaWhitelist(path) && !isAliasDir(path);
}

function isPicked(path: string): boolean {
  return picked.value.includes(path);
}

/** 选中 / 取消选中。撞上限时**不动**（按钮此时已置灰，这里是第二道闸）。 */
function toggle(path: string) {
  const i = picked.value.indexOf(path);
  if (i >= 0) picked.value.splice(i, 1);
  else if (canPickMore.value) picked.value.push(path);
}

/**
 * 列一层目录。
 *
 * 失败时**面包屑停在原处**、只把子目录列表清空并显示 core 的文案（`400 Invalid path`
 * 之类）—— 那比"加载失败"有用，也不会让人以为目录树被重置了。
 * 取数被取消（组件卸载）时 `data` 是 undefined，此时原样返回、什么都不动。
 */
async function browse(path: string) {
  if (!canEnter(path)) return;
  browsing.value = true;
  try {
    const { data } = await api.get('/api/files/list', { params: { path } });
    if (!data) return;
    browsePath.value = path;
    browseFail.value = '';
    const list: FileEntry[] = (data.files || []).filter(
      (f: FileEntry) => f.isDirectory && !String(f.path).startsWith('remote:') && canEnter(f.path)
    );
    const mapped = list.map((f) => ({
      path: f.path,
      name: f.name || String(f.path).split('/').filter(Boolean).pop() || f.path,
    }));
    // 列 /storage 时并上 disk-usage 的真实卷：Android 11+ 上 listFiles('/storage')
    // 可能只见 emulated/self，外接 SD 卡（/storage/XXXX-XXXX）会"消失"。
    // 并上来的挂载点是 `/storage/emulated/0` 这种**真实路径**，所以内部存储即使
    // 不走 `/storage/emulated` 那一层也进得去。
    if (path.replace(/\/+$/, '') === BROWSE_ROOT) {
      try {
        const du = await api.get('/api/files/disk-usage');
        for (const d of du.data?.disks || []) {
          const mount = String(d?.mount || '');
          if (mount && canEnter(mount) && !mapped.some((c) => c.path === mount)) {
            mapped.push({ path: mount, name: String(d?.label || mount) });
          }
        }
      } catch {
        /* disk-usage 失败不阻塞浏览：至少还有 /api/files/list 那一份 */
      }
    }
    children.value = mapped;
  } catch (e: any) {
    // 只把这次浏览的失败说出来；core 的 400（路径非法）文案比"加载失败"有用
    const msg = e?.response?.data?.message || e?.response?.data?.error;
    children.value = [];
    browseFail.value = String(msg || '目录读取失败');
  } finally {
    browsing.value = false;
  }
}

function confirm() {
  emit('confirm', [...picked.value]);
  emit('update:show', false);
}

/**
 * 每次打开都重新开始：拉一层 `/storage`，并把当前配置作为已选。
 *
 * 不缓存上一次的浏览位置：配置目录通常就是那两三个，从根开始点比"猜上次在哪"更可预期。
 */
watch(
  () => props.show,
  (v) => {
    if (!v) return;
    picked.value = [...props.initial];
    browseFail.value = '';
    children.value = [];
    void browse(BROWSE_ROOT);
  },
  { immediate: true }
);
</script>

<style scoped>
.dp-path {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 8px;
}
.dp-path-text {
  font-size: 12px;
  color: var(--text-secondary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.dp-crumbs {
  margin-bottom: 8px;
}
.dp-list {
  max-height: 240px;
  overflow-y: auto;
}
.dp-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 2px 0;
}
.dp-name {
  display: flex;
  align-items: center;
  gap: 6px;
  flex: 1;
  min-width: 0;
  padding: 6px 8px;
  border: none;
  border-radius: var(--radius-sm);
  background: transparent;
  color: var(--text-primary);
  font-size: 13px;
  text-align: left;
  cursor: pointer;
}
.dp-name:hover {
  background: var(--surface-hover);
}
.dp-name-text {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.dp-empty {
  padding: 12px 8px;
  font-size: 12px;
  color: var(--text-muted);
  text-align: center;
}
.dp-picked {
  margin-top: 10px;
  padding-top: 8px;
  border-top: 1px solid var(--border-subtle);
  max-height: 120px;
  overflow-y: auto;
}
.dp-picked-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 2px 0;
}
.dp-picked-path {
  font-size: 12px;
  color: var(--text-primary);
  word-break: break-all;
}
.dp-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}
.dp-count {
  font-size: 12px;
  color: var(--text-muted);
}
</style>
