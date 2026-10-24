<template>
  <n-modal
    :show="show"
    preset="card"
    class="modal-fit search-modal"
    style="--modal-w: 600px; --modal-h: 720px"
    :mask-closable="true"
    :bordered="false"
    @update:show="(v: boolean) => emit('update:show', v)"
  >
    <template #header>
      <span class="sm-title">搜索文件</span>
    </template>

    <div class="sm-body">
      <!-- 输入 + 范围 -->
      <div class="sm-query">
        <n-input
          ref="inputRef"
          v-model:value="query"
          placeholder="输入文件名（支持部分匹配）"
          clearable
          @keyup.enter="runSearch"
        >
          <template #prefix
            ><n-icon><SearchOutline /></n-icon
          ></template>
        </n-input>
        <n-button type="primary" :loading="loading" :disabled="!query.trim()" @click="runSearch">搜索</n-button>
      </div>

      <div class="sm-scope">
        <n-radio-group v-model:value="scope" size="small" :disabled="loading">
          <n-radio-button v-for="s in SEARCH_SCOPES" :key="s.key" :value="s.key" :title="s.hint">
            {{ s.label }}
          </n-radio-button>
        </n-radio-group>
        <span class="sm-root" :title="rootPath">范围：{{ rootPath }}</span>
      </div>

      <!-- 结果 -->
      <div class="sm-results">
        <div v-if="loading" class="sm-hint">正在搜索…（单次最多 10 秒）</div>
        <!-- 失败必须与「没找到」分开：原来 catch 里把状态置成「已搜索 + 空结果」，
             于是请求失败与真的零命中显示同一句「未找到匹配项」 -->
        <div v-else-if="failed" class="sm-hint is-failed">
          <span>搜索失败，没拿到结果（可能是设备端超时或连接中断）。</span>
          <n-button size="tiny" text type="primary" @click="runSearch">重试</n-button>
        </div>
        <div v-else-if="!searched" class="sm-hint">输入关键词后回车开始搜索</div>
        <div v-else-if="!results.length" class="sm-hint">
          未找到匹配项（{{ SEARCH_SCOPES.find((s) => s.key === scope)?.label }}）
        </div>
        <template v-else>
          <div v-if="timedOut" class="sm-warn">搜索超时，以下为已完成的部分结果（core 单次搜索上限 10 秒）</div>
          <div v-if="results.length >= SEARCH_MAX_RESULTS" class="sm-warn">
            已达单次上限 {{ SEARCH_MAX_RESULTS }} 条，请缩小范围或使用更具体的关键词
          </div>
          <div class="sm-count">共 {{ results.length }} 项</div>
          <div v-for="(r, i) in results" :key="r.path + i" class="sm-item" :title="r.path" @click="onPick(r)">
            <FileKindIcon :file="r" />
            <div class="sm-item-body">
              <span class="sm-item-name">
                <span v-for="(p, pi) in highlightParts(r.name, query)" :key="pi" :class="{ hit: p.hit }">{{
                  p.text
                }}</span>
              </span>
              <span class="sm-item-dir">{{ dirLabel(r) }}</span>
            </div>
            <n-icon class="sm-item-go"><ArrowForwardOutline /></n-icon>
          </div>
        </template>
      </div>
    </div>
  </n-modal>
</template>

<script setup lang="ts">
/**
 * 搜索弹窗（移动端由行1 的搜索按钮打开；桌面由输入框回车打开）。
 *
 * 为什么搜索要独立成弹窗而不是沿用「列表里显示结果」：
 *  1. 多级目录下**必须能看出结果在哪一层**。列表行只有文件名 + 元信息，
 *     同名文件在不同目录时完全无法分辨（`/search` 只返回 name/path/isDirectory，
 *     连 size 都没有）。这里每行显式给出相对当前目录的位置标签。
 *  2. 搜到深层文件后要能**定位过去**——点结果直接跳到它所在目录并选中，
 *     而不是让用户自己按面包屑一层层点。core 没有「打开所在目录」接口，这是客户端补的。
 *
 * 范围三档映射 core `/search` 的 `depth`（不是客户端逐层翻目录）：
 * 当前目录=1 / 含子目录=3 / 整个存储=8。上限与超时是 core 的硬限制，如实提示。
 */
import { computed, nextTick, ref, watch } from 'vue';
import type { InputInst } from 'naive-ui';
import { getApiClient } from '@/composables/useApi';
import FileKindIcon from '../FileKindIcon.vue';
import {
  SEARCH_MAX_RESULTS,
  SEARCH_SCOPES,
  parentPathOf,
  relativeDirLabel,
  searchRootFor,
  type FileEntry,
  type SearchScope,
} from '../../filesShared';
import { ArrowForwardOutline, SearchOutline } from '@vicons/ionicons5';

const props = withDefaults(
  defineProps<{
    show: boolean;
    currentPath: string;
    initialQuery?: string;
  }>(),
  { initialQuery: '' }
);

const emit = defineEmits<{
  (e: 'update:show', v: boolean): void;
  /** 选中一个结果：目录 → 进入该目录；文件 → 跳到所在目录并选中 */
  (e: 'pick', file: FileEntry): void;
}>();

const api = getApiClient();
const inputRef = ref<InputInst | null>(null);
const query = ref('');
const scope = ref<SearchScope>('sub');
const results = ref<FileEntry[]>([]);
const loading = ref(false);
const searched = ref(false);
const timedOut = ref(false);
/** 请求失败（与「已搜索、零命中」互斥，见模板注释） */
const failed = ref(false);

const rootPath = computed(() => searchRootFor(scope.value, props.currentPath));

/** 结果所在目录（目录结果就是它自己；文件结果是其父目录） */
function dirLabel(r: FileEntry): string {
  const dir = r.isDirectory ? r.path : parentPathOf(r.path);
  return relativeDirLabel(dir, props.currentPath);
}

function highlightParts(name: string, q: string): { text: string; hit: boolean }[] {
  const needle = q.trim();
  if (!needle) return [{ text: name, hit: false }];
  const lower = name.toLowerCase();
  const lq = needle.toLowerCase();
  const parts: { text: string; hit: boolean }[] = [];
  let i = 0;
  while (i < name.length) {
    const idx = lower.indexOf(lq, i);
    if (idx < 0) {
      parts.push({ text: name.slice(i), hit: false });
      break;
    }
    if (idx > i) parts.push({ text: name.slice(i, idx), hit: false });
    parts.push({ text: name.slice(idx, idx + needle.length), hit: true });
    i = idx + needle.length;
  }
  return parts;
}

async function runSearch() {
  const q = query.value.trim();
  if (!q) return;
  const spec = SEARCH_SCOPES.find((s) => s.key === scope.value);
  loading.value = true;
  failed.value = false;
  try {
    const { data } = await api.get('/api/files/search', {
      params: { path: rootPath.value, query: q, depth: spec?.depth ?? 3 },
    });
    results.value = data.files || [];
    timedOut.value = data.timed_out === true;
    searched.value = true;
  } catch {
    results.value = [];
    timedOut.value = false;
    searched.value = true;
    // 只留在结果区（带重试）：再弹一条 toast 是对同一件事说两遍
    failed.value = true;
  } finally {
    loading.value = false;
  }
}

function onPick(r: FileEntry) {
  emit('pick', r);
  emit('update:show', false);
}

// 打开时：带入外部关键词并自动聚焦；有关键词就直接搜一次（桌面从输入框回车进来的路径）
watch(
  () => props.show,
  (v) => {
    if (!v) return;
    query.value = props.initialQuery;
    results.value = [];
    searched.value = false;
    timedOut.value = false;
    failed.value = false;
    nextTick(() => inputRef.value?.focus());
    if (query.value.trim()) runSearch();
  },
  { immediate: true }
);

// 切范围不自动重搜（会有一次没必要的整树扫描），但清掉旧结果避免「范围变了结果没变」的误读
watch(scope, () => {
  results.value = [];
  searched.value = false;
  timedOut.value = false;
  failed.value = false;
});
</script>

<style scoped>
.sm-title {
  font-size: var(--font-lg);
}
.sm-body {
  display: flex;
  flex-direction: column;
  gap: var(--space-3);
}
.sm-query {
  display: flex;
  align-items: center;
  gap: var(--space-2);
}
.sm-scope {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  flex-wrap: wrap;
}
/* 四档范围在 360px 下本来就排不进一行（每档 4~5 个汉字 + 内距），而 naive 的
   `.n-radio-group` 是 inline-flex + nowrap —— 溢出的那两档会被卡片裁掉。这里允许换行。 */
.sm-scope :deep(.n-radio-group) {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-1);
}
.sm-root {
  font-size: var(--font-xs);
  color: var(--text-muted);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  min-width: 0;
  flex: 1 1 auto;
}
/* 结果区不再自设高度与滚动：`max-height: 52vh + overflow-y: auto` 会在
   `.modal-fit` 的内容区（`.n-card-content`，本身已 overflow:auto）里再套一层，
   矮屏下用户要先滚外层再滚内层才看得到下面的结果。滚动统一交给内容区。 */
.sm-results {
  overscroll-behavior: contain;
  border-top: 1px solid var(--border-subtle);
  padding-top: var(--space-2);
}
.sm-hint {
  padding: var(--space-6) var(--space-4);
  text-align: center;
  color: var(--text-muted);
  font-size: var(--font-base);
}
/* 失败态：一句话 + 重试入口，竖排居中 */
.sm-hint.is-failed {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: var(--space-2);
  color: var(--text-secondary);
  line-height: 1.6;
}
.sm-warn {
  padding: var(--space-2) var(--space-3);
  background: var(--warning-light);
  border-radius: var(--radius-sm);
  font-size: var(--font-sm);
  color: var(--text-secondary);
  margin-bottom: var(--space-2);
}
.sm-count {
  font-size: var(--font-sm);
  color: var(--text-muted);
  padding: 0 var(--space-1) var(--space-1);
}
.sm-item {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  padding: var(--space-2);
  border-radius: var(--radius-sm);
  cursor: pointer;
}
.sm-item:hover {
  background: var(--surface-hover);
}
.sm-item-body {
  min-width: 0;
  flex: 1 1 auto;
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.sm-item-name {
  font-size: var(--font-md);
  color: var(--text-primary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.sm-item-name .hit {
  color: var(--accent-color);
  font-weight: 600;
}
.sm-item-dir {
  font-size: var(--font-sm);
  color: var(--text-muted);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.sm-item-go {
  flex-shrink: 0;
  color: var(--text-muted);
  font-size: var(--font-lg);
}
</style>
