<!--
  扫描范围面板（2026-10-22 从 MediaScanDirsSettings 抽出的**内容本体**）。

  ## 为什么拆成 Panel + 入口两层
  视频页按方案 A 把「封面预热」和「扫描范围」收进同一个 ⚙ 浮层；而音乐页 / 图片页仍然是
  「一个按钮 + 一个浮层」的独立入口。如果只保留原来那个自带 n-popover 的组件，
  视频页就只能再套一层浮层（浮层里点浮层，点外面会把两层一起关掉）。

  所以内容本体拆到这里：`MediaScanDirsSettings` 只是"按钮 + 浮层"的壳，视频页则把本面板
  直接铺进自己的 ⚙ 浮层里。三页的行为仍然只有一份实现。

  ## `active`
  浮层每打开一次就重拉一次配置（真源在设备上，app 也能改）。独立入口把 `active` 绑到
  自己的 open 状态；嵌进视频页时绑到 ⚙ 浮层的 open。**不在 onMounted 拉**：三页各一个 GET
  没有意义。
-->
<template>
  <div class="sd-block">
    <div class="sd-title">扫描范围</div>
    <p class="sd-note">
      只把列出的目录（含子目录）算进{{ kindLabel }}库。目录暂时不存在也可以先配好 —— SD 卡拔出时配置不会被清掉。
    </p>

    <div class="sd-list">
      <div v-for="(d, i) in dirs" :key="d" class="sd-row">
        <span class="sd-path" :title="d">{{ d }}</span>
        <n-button size="tiny" quaternary aria-label="移除" @click="removeAt(i)">✕</n-button>
      </div>
      <!-- 空列表 = 不限目录（core `scanDirs` 的语义），不是"什么都不扫" -->
      <div v-if="!dirs.length" class="sd-empty">未配置 = 不限目录，扫全部{{ kindLabel }}</div>
    </div>

    <div class="sd-add">
      <n-input v-model:value="draft" size="small" placeholder="/storage/emulated/0/Movies" @keyup.enter="addDraft" />
      <n-button size="small" @click="openPicker">浏览…</n-button>
      <n-button size="small" :disabled="!canAddDraft" @click="addDraft">添加</n-button>
    </div>

    <div class="sd-foot">
      <span class="sd-count" :class="{ 'is-full': full }">{{ dirs.length }} / {{ MAX_SCAN_DIRS }}</span>
      <div class="sd-foot-actions">
        <!--
          重新扫描与扫描范围是同一件事的两个方向（"扫哪些目录" / "现在就去扫一遍"）。
          它与「保存」**互不依赖** —— 扫描用的是设备上已保存的目录，不是草稿，
          所以不跟 `dirty` 联动（否则用户改了目录没保存时会以为不能重扫）。
        -->
        <n-button size="small" quaternary :loading="rescanning" @click="rescan">重新扫描</n-button>
        <n-button size="small" type="primary" :loading="saving" :disabled="!dirty" @click="save">保存</n-button>
      </div>
    </div>

    <slot :active="props.active" />

    <MediaDirPickerModal v-model:show="pickerShow" :initial="dirs" :max="MAX_SCAN_DIRS" @confirm="onPicked" />
  </div>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { useMessage } from 'naive-ui';
import { useCancellableApi } from '@/composables/useCancellableApi';
// 别名归一（`/sdcard` → `/storage/emulated/0`）：文件页的既有实现，手填路径时复用它
import { normalizePath } from '@/views/files/filesShared';
import {
  MEDIA_KIND_LABELS,
  MAX_SCAN_DIRS,
  fetchScanDirs,
  mediaConfigErrorText,
  normalizeScanDirs,
  rescanMedia,
  saveScanDirs,
} from '../mediaConfigShared';
import type { MediaKind } from '../mediaShared';
import MediaDirPickerModal from './MediaDirPickerModal.vue';

const props = defineProps<{
  /** 改哪一类的扫描目录。三页传各自的值，core 侧是三份独立配置。 */
  kind: MediaKind;
  /** 宿主浮层是否打开；为 true 时重拉一次配置。 */
  active?: boolean;
}>();

const emit = defineEmits<{
  /** 保存成功。页面据此重拉列表 —— 扫描范围变了，列表内容就变了，不重拉会显示旧结果。 */
  (e: 'saved'): void;
  /** 触发了一次重新扫描。页面据此**延迟**重拉（core 侧扫描是异步的）。 */
  (e: 'rescanned'): void;
}>();

const kindLabel = computed(() => MEDIA_KIND_LABELS[props.kind]);

const api = useCancellableApi();
const message = useMessage();

/** 当前编辑中的列表。 */
const dirs = ref<string[]>([]);
/** 设备上已保存的那一份。用来判断"改过没有"（保存按钮的亮灭）。 */
const saved = ref<string[]>([]);
const draft = ref('');
const loading = ref(false);
const saving = ref(false);
/** 重新扫描的进行中状态。core 立刻返回，所以这个 loading 很短（只是请求本身）。 */
const rescanning = ref(false);
const pickerShow = ref(false);

/**
 * 改过没有（保存按钮的亮灭）。
 *
 * 比较前先按 **core 的清洗口径**规范化（`trim` / 去尾斜杠 / 去重 / 丢空串）：
 * 与已保存的那一份只差这些的话，按下去也写不出任何变化，按钮就不该亮。
 */
const dirty = computed(() => normalizeScanDirs(dirs.value).join('\n') !== saved.value.join('\n'));
const full = computed(() => dirs.value.length >= MAX_SCAN_DIRS);
const canAddDraft = computed(() => draft.value.trim() !== '' && !full.value);

/**
 * 拉一次设备上的配置。
 *
 * 取不到（`null` = 请求被取消）时**保持原状**：把用户的编辑清成空列表，
 * 接着一次保存就会把设备上的配置写没。
 */
async function load() {
  loading.value = true;
  try {
    const result = await fetchScanDirs(api, props.kind);
    if (result === null) return;
    dirs.value = [...result];
    saved.value = [...result];
  } catch (e: any) {
    message.error(mediaConfigErrorText('读取扫描范围', e));
  } finally {
    loading.value = false;
  }
}

// 浮层打开时重拉（真源在设备上）。独立入口与嵌进视频页的 ⚙ 浮层都走这一条。
watch(
  () => props.active,
  (v) => {
    if (v) void load();
  },
  { immediate: true }
);

/** 手填的绝对路径入列。去重与空串判断与 core 的清洗口径一致（见 mediaConfigShared）。 */
function addDraft() {
  const raw = draft.value.trim().replace(/\/+$/, '');
  if (!raw) return;
  // 只拦"明显不是绝对路径"这一种：白名单与 `..` 的判定是 core 的职责（它的 400 会点名第一个非法目录）
  if (!raw.startsWith('/')) {
    message.warning('请输入以 / 开头的绝对路径');
    return;
  }
  // `/sdcard` 是内部存储的别名：core 的白名单认它，但 MediaStore 里的路径是
  // `/storage/emulated/0/…` —— 存别名会让 `/list` 的 `startsWith("$dir/")` 一条都匹配不上，
  // 结果是**静默的空库**。`normalizePath` 正是文件页为这件事写的（别名归一，避免同目录双路径）。
  const path = normalizePath(raw);
  // `/storage/self` 是同一类别名，`normalizePath` 不认它，只能挡住
  if (path.startsWith('/storage/self')) {
    message.warning('这是内部存储的符号链接别名，请填 /storage/emulated/0/…（用别名会扫不到文件）');
    return;
  }
  if (dirs.value.includes(path)) {
    message.warning('这个目录已经在列表里了');
    return;
  }
  if (full.value) {
    message.warning(`最多 ${MAX_SCAN_DIRS} 个目录，先移除一些再加`);
    return;
  }
  dirs.value.push(path);
  draft.value = '';
}

function removeAt(i: number) {
  dirs.value.splice(i, 1);
}

/**
 * 浏览弹窗的确定结果**整表替换**编辑中的列表。
 * 弹窗打开时带的就是当前列表，用户可以在里面加、也可以把已有的取消掉。
 */
function onPicked(picked: string[]) {
  dirs.value = [...picked];
}

function openPicker() {
  pickerShow.value = true;
}

/**
 * 保存（`PUT /api/media/config?type=…`，body 是整份 `dirs`）。
 *
 * 成功后用 **core 回的 `dirs`** 当新的真源，而不是本地那份：core 会做
 * `trim` / 去尾斜杠 / 去重，回读它才能保证"界面上看到的"与"设备上存的"逐字一致。
 */
async function save() {
  saving.value = true;
  try {
    const result = await saveScanDirs(api, props.kind, dirs.value);
    if (result === null) return;
    dirs.value = [...result];
    saved.value = [...result];
    message.success(result.length ? `已保存：${result.length} 个目录` : `已保存：不限目录，扫全部${kindLabel.value}`);
    emit('saved');
  } catch (e: any) {
    message.error(mediaConfigErrorText('保存扫描范围', e));
  } finally {
    saving.value = false;
  }
}

/**
 * 触发一次重新扫描（`POST /api/media/rescan?type=…`）。
 *
 * **成功只说明"扫描已经在后台跑起来了"**，不说明列表已经更新；所以只发事件让页面延迟重拉。
 */
async function rescan() {
  rescanning.value = true;
  try {
    await rescanMedia(api, props.kind);
    message.success(`已触发重新扫描${kindLabel.value}库，稍等片刻后列表会自动刷新`);
    emit('rescanned');
  } catch (e: any) {
    message.error(mediaConfigErrorText('重新扫描', e));
  } finally {
    rescanning.value = false;
  }
}

// kind 在一个实例上不会变（三个页面各传一个字面量），留着这一条只是为了
// 万一有人把它放进动态切换的容器里时不会静默用错配置。
watch(
  () => props.kind,
  () => void load()
);
</script>

<style scoped>
.sd-block {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.sd-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--text-primary);
}
.sd-note {
  margin: 0;
  font-size: 11px;
  line-height: 1.5;
  color: var(--text-muted);
}
.sd-list {
  display: flex;
  flex-direction: column;
  gap: 2px;
  max-height: 180px;
  overflow-y: auto;
}
.sd-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}
.sd-path {
  font-size: 12px;
  color: var(--text-primary);
  word-break: break-all;
}
.sd-empty {
  padding: 8px 0;
  font-size: 12px;
  color: var(--text-muted);
}
.sd-add {
  display: flex;
  align-items: center;
  gap: 6px;
}
.sd-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}
/* 两颗按钮贴在一起，与左侧的计数分列两端 */
.sd-foot-actions {
  display: flex;
  align-items: center;
  gap: 6px;
  flex: 0 0 auto;
}
.sd-count {
  font-size: 11px;
  color: var(--text-muted);
}
/* 到上限时只换颜色提醒，不隐藏按钮：core 才是判据（它的 400 文案会点名上限） */
.sd-count.is-full {
  color: var(--warning);
}
</style>
