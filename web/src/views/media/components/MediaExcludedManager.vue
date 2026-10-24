<!--
  音乐页「已从音乐库移除的歌曲」区块 + 管理弹窗（2026-10-14）。

  ## 为什么只属于音乐页
  `/api/media/excluded` / `/api/media/exclude` **只对 `type=audio` 生效**
  （core 的排除名单挂在音频库上，视频/图片没有对应物），所以它不是三页共用的东西 ——
  它作为「设置」浮层里的**第二个区块**挂在音乐页（第一个区块是扫描范围，
  见 MediaScanDirsSettings；入口形态由那个组件统一提供）。

  ## 为什么只能按路径显示
  被排除的路径**从 `/list` 的结果里直接消失**（core `filterListItems` 的第四层过滤），
  所以客户端拿不到它们的标题 / 艺人 / 时长 —— 一条元数据都没有。这不是"懒得查"，
  是查不到：`/list` 是唯一的元数据来源，而它按定义不会返回这些条目。
  于是列表显示"文件名当主标题、全路径当副标题"（文件名由 [excludedFileName] 取末段）。

  ## 三个必须如实说的点（都来自 core 的语义，不是文案偏好）
  1. **`full` 要提前说**：core 算好了 `total >= max` 才回给客户端，就是为了让界面
     在用户动手**之前**提示"名单已满，再加需要先移除一些"，而不是等他点完发现没生效。
  2. **`affected < requested` 是唯一能发现"只生效一部分"的途径**（见 contract.ts）。
     它对 `remove` 与 `add` 的含义不同，所以文案由 `excludeResultText` 分情况给，
     不在这里各写一遍。
  3. **只动名单，不删文件**。这句话必须写在界面上：用户看到"移除"两个字，
     最坏的想象是文件被删了。core 刻意把它和 `POST /api/files/delete` 分成两条接口，
     界面也得把这个区别说出来。

  ## 为什么弹窗放在这个区块内部（以及它带来的一条约束）
  区块与弹窗共享同一份名单状态（`total` / `full` / `paths`），拆到页面级会多一份要同步的真源。
  代价是它要求外层浮层 `display-directive="show"`：弹窗一打开，浮层会因为"点到外面"而关闭，
  默认的 `'if'` 会把宿主组件一起卸载、弹窗随即消失（那条理由写在 MediaScanDirsSettings 里）。

  ## 否决过的替代方案
  · **把名单做成音乐页主区的一个面板**：它是低频的维护动作（撤销误移除），
    常驻一列会挤掉歌单 / 队列 / 歌词中的一列，而"移除"这件事本身在播放页并不常做。
  · **每条移回时做乐观更新**（先从本地列表删掉再发请求）：名单真源在设备上，
    app 也在改；乐观更新会让"我点了移回但列表里还是少一首"变成看不见的错。
    写完重拉才是准的（与 playlistShared 同一条理由）。
  · **「全部清空」不加二次确认**：它会一次性把用户攒了很久的名单清掉（可能是几十上百条），
    且没有撤销入口 —— 与本仓其它破坏性操作（删歌单 / 删存储源）一样必须确认。
-->
<template>
  <div class="ex-block">
    <div class="ex-title">已从音乐库移除的歌曲</div>
    <p class="ex-note">只改名单，不删文件。被移除的曲目不会出现在音乐列表与播放队列里。</p>
    <!-- 提前提示：名单满时再加是加不进去的（core 用 full 告诉我们，别等用户点了才说） -->
    <div v-if="full" class="ex-warn">名单已满（{{ total }} / {{ max }}），再加需要先移除一些。</div>
    <div class="ex-row">
      <span class="ex-count">{{ loading ? '读取中…' : `${total} 首已移除` }}</span>
      <n-button size="small" :loading="loading" @click="openList">管理…</n-button>
    </div>
    <div v-if="loadError" class="ex-error">
      <span class="ex-error-text">{{ loadError }}</span>
      <n-button size="tiny" quaternary @click="load">重试</n-button>
    </div>

    <n-modal
      v-model:show="listOpen"
      class="modal-fit"
      preset="card"
      title="已从音乐库移除的歌曲"
      style="--modal-w: 560px"
    >
      <p class="ex-note">
        这些路径已从音乐库列表里消失，所以拿不到标题 / 艺人等元数据 ——
        只能按路径显示文件名。移回后会重新出现在列表与队列里。
      </p>
      <div v-if="full" class="ex-warn">名单已满（{{ total }} / {{ max }}），再加需要先移除一些。</div>

      <n-spin :show="loading">
        <div class="ex-list">
          <div v-for="p in paths" :key="p" class="ex-item">
            <span class="ex-text">
              <span class="ex-name" :title="p">{{ excludedFileName(p) }}</span>
              <span class="ex-path" :title="p">{{ p }}</span>
            </span>
            <n-button size="tiny" :loading="isBusy(p)" :disabled="isBusy(p)" @click="doRemove(p)">移回</n-button>
          </div>
          <div v-if="!paths.length && !loading" class="ex-empty">名单是空的 —— 没有歌曲被移除</div>
        </div>
      </n-spin>

      <template #footer>
        <div class="ex-foot">
          <n-button size="small" type="error" :disabled="!paths.length" :loading="clearing" @click="confirmClear">
            全部清空
          </n-button>
          <n-button size="small" @click="listOpen = false">关闭</n-button>
        </div>
      </template>
    </n-modal>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref, watch } from 'vue';
import { useDialog, useMessage } from 'naive-ui';
import { useCancellableApi } from '@/composables/useCancellableApi';
import {
  excludePaths,
  excludeResultText,
  excludedErrorText,
  excludedFileName,
  fetchExcluded,
} from '../mediaExcludedShared';

const props = defineProps<{
  /**
   * 外层浮层是否打开。
   *
   * 名单的真源在设备上（app 也能改），所以每次打开浮层都要重拉一次；
   * 这个区块活在浮层内容里，自己看不到浮层的显隐，只能由父组件传进来
   * （MediaScanDirsSettings 通过 slot 把 `active` 给它）。
   */
  active?: boolean;
}>();

const emit = defineEmits<{
  /** 名单变了。页面据此重拉音乐列表 —— 移回的歌会重新出现，不重拉就还是看不到。 */
  (e: 'changed'): void;
}>();

const api = useCancellableApi();
const message = useMessage();
const dialog = useDialog();

const paths = ref<string[]>([]);
const total = ref(0);
const max = ref(0);
const full = ref(false);
const loading = ref(false);
const loadError = ref('');
const listOpen = ref(false);
/**
 * 正在移回的那几条路径（逐行 loading）。
 *
 * 用数组而不是"单个正在飞"的标志：不同行的移回是**彼此独立**的操作，
 * 用一个全局标志串行化的话，用户连点两行时第二次点击会静默失效（没有反馈，看起来像坏了）。
 */
const busyPaths = ref<string[]>([]);
const clearing = ref(false);

/**
 * 拉一次名单。
 *
 * 取不到（`null` = 请求被取消）时**保持原状**：把已有的名单清成空会让用户以为
 * 名单被清了，接着一次"全部清空"就没有意义了。失败只记文案、不动数据。
 */
async function load() {
  loading.value = true;
  try {
    const data = await fetchExcluded(api);
    if (data === null) return;
    paths.value = data.paths;
    total.value = data.total;
    max.value = data.max;
    full.value = data.full;
    loadError.value = '';
  } catch (e: any) {
    loadError.value = excludedErrorText('读取已移除歌曲', e);
  } finally {
    loading.value = false;
  }
}

/** 打开管理弹窗时顺手重拉一次：用户可能刚从 app 那边改过。 */
function openList() {
  listOpen.value = true;
  void load();
}

/** 这一行是不是正在移回。 */
function isBusy(path: string): boolean {
  return busyPaths.value.includes(path);
}

/** 单条移回（`action: 'remove'`）。文案由 excludeResultText 分情况给，见文件头第 2 点。 */
async function doRemove(path: string) {
  if (isBusy(path)) return;
  busyPaths.value.push(path);
  try {
    const res = await excludePaths(api, 'remove', [path]);
    if (res === null) return;
    message.success(excludeResultText('remove', res));
    await load();
    emit('changed');
  } catch (e: any) {
    message.error(excludedErrorText('移回歌曲', e));
  } finally {
    busyPaths.value = busyPaths.value.filter((p) => p !== path);
  }
}

/** 全部清空。二次确认里明说"不会删文件" —— 用户对"清空"两个字的想象比实际严重得多。 */
function confirmClear() {
  if (clearing.value || !paths.value.length) return;
  dialog.warning({
    title: '清空已移除名单',
    content: `确定把 ${total.value} 首全部移回音乐库？只改名单，不会删除任何文件。`,
    positiveText: '全部移回',
    negativeText: '取消',
    onPositiveClick: async () => {
      await doClear();
    },
  });
}

async function doClear() {
  clearing.value = true;
  try {
    const res = await excludePaths(api, 'clear');
    if (res === null) return;
    message.success(excludeResultText('clear', res));
    await load();
    emit('changed');
  } catch (e: any) {
    message.error(excludedErrorText('清空名单', e));
  } finally {
    clearing.value = false;
  }
}

// 浮层内容可能是惰性挂载（首次打开才渲染）也可能是常驻（display-directive="show"），
// 两条路径都要覆盖：挂载时如果已经是打开状态就拉一次，之后靠 watch 跟。
onMounted(() => {
  if (props.active) void load();
});
watch(
  () => props.active,
  (v) => {
    if (v) void load();
  }
);
</script>

<style scoped>
.ex-block {
  display: flex;
  flex-direction: column;
  gap: 6px;
  margin-top: 12px;
  padding-top: 10px;
  border-top: 1px solid var(--border-subtle);
}
.ex-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--text-primary);
}
.ex-note {
  margin: 0;
  font-size: 11px;
  line-height: 1.5;
  color: var(--text-muted);
}
.ex-warn {
  padding: 6px 8px;
  border-radius: var(--radius-sm);
  background: var(--warning-light);
  color: var(--warning);
  font-size: 11px;
  line-height: 1.5;
}
.ex-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}
.ex-count {
  font-size: 12px;
  color: var(--text-primary);
}
.ex-error {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  color: var(--error);
  font-size: 11px;
}
.ex-error-text {
  line-height: 1.5;
  word-break: break-all;
}
.ex-list {
  display: flex;
  flex-direction: column;
  max-height: 320px;
  overflow-y: auto;
}
.ex-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 6px 0;
  border-bottom: 1px solid var(--border-subtle);
}
.ex-item:last-child {
  border-bottom: none;
}
.ex-text {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}
.ex-name {
  font-size: 13px;
  color: var(--text-primary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.ex-path {
  font-size: 11px;
  color: var(--text-muted);
  word-break: break-all;
}
.ex-empty {
  padding: 16px 0;
  font-size: 12px;
  color: var(--text-muted);
  text-align: center;
}
.ex-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}
</style>
