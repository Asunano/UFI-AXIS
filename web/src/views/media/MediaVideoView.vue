<!--
  视频页。与 app 的 `MediaVideoScreen` / `MediaVideoHome`（海报墙）对应。

  海报墙用 16:9 缩略图 + 两行文件名 + 时长角标 —— 与 app 的 `PosterCell` 同一组信息。
  点开后**在弹窗里**播放：列表留在页面上，关闭弹窗即回到原来的位置（2026-10-22 恢复弹窗）。

  2026-10-14 补齐（对齐 app，逐条见各自的注释）：
  · **文件夹视图**（页头「海报墙 / 文件夹」切换，共用 `components/MediaFolderBrowser.vue`）；
  · **排序**下推到 core（`MediaSortControl` + `setSort`）；
  · **重新扫描**接上并延迟补拉（`@rescanned` → `mediaRescanRefresh`）；
  · **播放列表**：播完自动下一集（最后一集停住）；手动上/下一集已随弹窗简化移除（弹窗只放播放器组件）；
  · **字幕 N** 角标（`MediaItem.subtitle_count`）；
  · **清空整类封面缓存**（「封面与扫描设置」⚙ 浮层里，二次确认）。

  2026-10-22 播放区定稿（本轮）：
  · **弹窗承载播放器**：点海报在 n-modal 里播放，列表留在页面上（关闭即回到原位置）。
    弹窗里只有 VideoPlayer：控制条、字幕菜单、倍速、全屏都由它自带；不再叠标题、工具条或错误卡片。
  · **播放器自己的错误卡片**负责"放不了"：容器/编码诊断在 `VideoPlayer` 里，
    下载入口也在那张卡片上。
-->
<template>
  <div class="media-list-page">
    <!-- 页头始终留在页面上（播放弹窗盖在它上面）：关闭弹窗即回到原来的列表与滚动位置，
         不需要为播放另换一套页头或工具条。 -->
    <div class="page-head">
      <div class="head-text">
        <h2 class="head-title">视频</h2>
        <span class="head-sub">{{ headSub }}</span>
      </div>
      <div class="head-actions">
        <!-- 视图切换（海报墙 / 文件夹），与 app 的「首页 / 媒体库」同构 -->
        <n-radio-group v-model:value="view" size="small" class="view-switch">
          <n-radio-button value="grid">海报墙</n-radio-button>
          <n-radio-button value="folder">文件夹</n-radio-button>
        </n-radio-group>
        <!-- 搜索只作用于海报墙（文件夹视图有自己的逐层导航） -->
        <n-input
          v-if="view === 'grid'"
          v-model:value="query"
          size="small"
          placeholder="搜索文件名"
          clearable
          class="head-search"
        />
        <!-- 排序必须下推到 core（见 useMediaLibrary.setSort），不能直接 v-model -->
        <MediaSortControl :model-value="sort" @update:model-value="setSort" />
        <!-- 刷新只作用于海报墙；文件夹视图的工具条里已有一颗 -->
        <n-button v-if="view === 'grid'" size="small" quaternary :loading="loading" title="刷新" @click="refreshActive">
          <template #icon
            ><n-icon><RefreshOutline /></n-icon
          ></template>
        </n-button>
        <!--
          方案 A（2026-10-22）：封面预热 + 扫描范围合并进这一个 ⚙ 浮层。
          两者都是低频设备侧设置，平铺在页头会把"搜索 / 排序"这类浏览动作挤下去。
          预热进度仍在「立即预热一轮」正下方（触发与进度同一个视觉块）。
        -->
        <n-popover
          v-model:show="settingsOpen"
          trigger="click"
          placement="bottom-end"
          :width="340"
          display-directive="show"
        >
          <template #trigger>
            <n-button size="small" quaternary title="封面与扫描设置">
              <template #icon
                ><n-icon><SettingsOutline /></n-icon
              ></template>
            </n-button>
          </template>
          <div class="vid-settings">
            <div class="vs-title">封面预热</div>
            <div class="thumb-cfg">
              <div class="config-item switch-item">
                <span class="thumb-cfg-label">后台预热封面（闲时自动补齐，开=立即生效）</span>
                <!-- ui-baseline-allow: 浮层里的紧凑排布，用全局 .config-item.switch-item 版式（标签左 / 开关右），不套 ToggleRow -->
                <n-switch v-model:value="thumbCfg.prewarmEnabled" size="small" @update:value="savePrewarmEnabled" />
              </div>
              <div class="config-item switch-item">
                <span class="thumb-cfg-label">仅充电或电量充足（>30%）时预热</span>
                <!-- ui-baseline-allow: 同上，浮层内的紧凑开关单元格 -->
                <n-switch v-model:value="thumbCfg.wifiOnly" size="small" @update:value="saveWifiOnly" />
              </div>
              <div class="thumb-cfg-actions">
                <n-button
                  size="tiny"
                  :disabled="!thumbCfg.prewarmEnabled"
                  :loading="prewarmNowRunning"
                  @click="prewarmNow"
                >
                  立即预热一轮
                </n-button>
              </div>
              <!-- 没有「取消」按钮是刻意的：core 只提供触发与读取快照，没有取消端点 -->
              <span v-if="prewarm" class="prewarm-badge" :class="{ running: prewarm.running }">
                <template v-if="prewarm.running">
                  预热中 {{ prewarm.done }}/{{ prewarm.total
                  }}<template v-if="prewarm.failed">（失败 {{ prewarm.failed }}）</template>
                  <template v-if="prewarm.current_name">· {{ prewarm.current_name }}</template>
                  <span class="prewarm-bar"
                    ><span
                      class="prewarm-bar-fill"
                      :style="{
                        width: prewarm.total ? ((prewarm.done + prewarm.failed) / prewarm.total) * 100 + '%' : '0%',
                      }"
                  /></span>
                </template>
                <template v-else>预热完成：新增 {{ prewarm.done }} / 失败 {{ prewarm.failed }}</template>
              </span>
              <span class="thumb-cfg-note">
                进度就在上方；详细日志在设备 /sdcard/Download/UFI-AXIS/log/core/ 当天目录。
              </span>
              <!-- 清空整类封面缓存（单条重抽入口已随播放弹窗简化移除），必须二次确认 -->
              <n-button size="tiny" type="error" quaternary :loading="clearingThumbs" @click="confirmClearThumbs">
                清空整类封面缓存
              </n-button>
              <span class="thumb-cfg-note">
                会删掉设备上已生成的全部视频封面并重新抽取，比较慢（每个视频都要重新解一帧）。
              </span>
            </div>
            <div class="vs-sep"></div>
            <MediaScanDirsPanel kind="video" :active="settingsOpen" @saved="onScanDirsSaved" @rescanned="onRescanned" />
          </div>
        </n-popover>
      </div>
    </div>

    <!-- ── 播放弹窗：**只放播放器组件** ──
         ArtPlayer 自带播放/暂停、进度、音量、倍速、全屏与字幕菜单；弹窗不再叠标题、
         工具条或任何自建按钮。列表继续留在页面上，关闭即回到原位置。 -->
    <n-modal
      :show="playerOpen"
      class="player-modal"
      :mask-closable="true"
      @update:show="(v: boolean) => !v && close()"
      @after-leave="onPlayerLeave"
    >
      <div ref="playerHostEl" class="player-shell">
        <VideoPlayer
          v-if="playingItem"
          :url="streamUrl"
          :name="playingItem.name"
          :path="playingItem.path"
          :poster="thumbs[playingItem.id] || ''"
          can-download
          @download="downloadPlaying"
        />
      </div>
    </n-modal>

    <!-- 列表视图。两个视图都在 DOM 里，用 v-show 切（**不是 v-else/v-if**，那会真的卸载）：
         海报墙留着（已取到的封面不必重取），文件夹视图首次打开才挂载（见 folderEverOpened）。
         列表在弹窗后面保持挂载：关闭时封面与文件夹层级都还在原地，不必重新请求 / 重新展开。 -->
    <MediaEmptyState
      v-if="showEmpty"
      v-show="view === 'grid'"
      :permission-denied="permissionDenied"
      :failed="failed"
      kind-label="视频"
      @retry="reload"
    />

    <n-spin v-else v-show="view === 'grid'" :show="loading && items.length === 0">
      <div class="poster-grid">
        <button v-for="v in filtered" :key="v.id" type="button" class="poster-cell" @click="open(v, filtered)">
          <span class="poster-thumb">
            <span class="thumb-play" aria-hidden="true"></span>
            <img v-if="thumbs[v.id]" :src="thumbs[v.id]" :alt="v.name" class="thumb-img" loading="lazy" />
            <span v-else class="thumb-fallback">
              <n-icon :size="26"><VideocamOutline /></n-icon>
            </span>
            <span v-if="v.duration_ms" class="thumb-badge">{{ formatDuration(v.duration_ms) }}</span>
            <!-- 2026-10-14：外挂字幕条数（core 的 /list 一直在算，见 MediaItem.subtitle_count） -->
            <span v-if="v.subtitle_count" class="thumb-cc">字幕 {{ v.subtitle_count }} 条</span>
          </span>
          <span class="poster-name" :title="v.name">{{ v.name }}</span>
          <span class="poster-meta">{{ formatMediaSize(v.size) }}</span>
        </button>
      </div>
      <div v-if="hasMore()" class="load-more">
        <n-button size="small" quaternary :loading="loadingMore" @click="loadMore">
          加载更多（{{ items.length }} / {{ total }}）
        </n-button>
      </div>
    </n-spin>

    <!-- 文件夹视图：与网格并列的第二种视图，共用组件（见 components/MediaFolderBrowser.vue） -->
    <MediaFolderBrowser
      v-if="folderEverOpened"
      v-show="view === 'folder'"
      kind="video"
      :sort="sort"
      :refresh-key="refreshKey"
      @open="open"
    />
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, reactive, ref, watch } from 'vue';
import { useWebSocketStore } from '@/stores/websocket';
import { useDialog, useMessage } from 'naive-ui';
import { RefreshOutline, VideocamOutline, SettingsOutline } from '@vicons/ionicons5';
// 2026-10-14：封面设置里那几个端点原来写的是裸字面量，改成走契约
// （`Endpoints.media.thumbnailPrewarm` / `Endpoints.config.root` 与字面量逐字一致，
// 见本次改动说明）。契约里没有的端点（如 `/api/files/*`）继续用字面量。
import { Endpoints } from '@/api/contract';
import { fetchStreamUrl, formatDuration, formatMediaSize, type MediaItem } from './mediaShared';
import { useMediaLibrary } from './useMediaLibrary';
import { useRescanRefresh } from './components/mediaRescanRefresh';
import MediaEmptyState from './components/MediaEmptyState.vue';
import MediaFolderBrowser from './components/MediaFolderBrowser.vue';
import MediaScanDirsPanel from './components/MediaScanDirsPanel.vue';
import MediaSortControl from './components/MediaSortControl.vue';
import VideoPlayer from '@/components/VideoPlayer.vue';

// 2026-10-07：封面预热设置（自 GeneralPanel 迁入——视频域开关放视频页）。
// 读：GET /api/config；写：PUT /api/config（与 GeneralPanel 的 saveProbeSwitch 同一端点）。
const thumbCfg = reactive({ prewarmEnabled: false, wifiOnly: false });
/** ⚙ 浮层（封面预热 + 扫描范围）的开关；同时驱动扫描范围面板重拉。 */
const settingsOpen = ref(false);
const thumbCfgLoading = ref(false);
async function loadThumbCfg() {
  thumbCfgLoading.value = true;
  try {
    const { data } = await api.get(Endpoints.config.root);
    thumbCfg.prewarmEnabled = !!data?.thumb_prewarm_enabled;
    thumbCfg.wifiOnly = !!data?.thumb_prewarm_wifi_only;
  } catch {
    /* 读失败保持默认；开关是低危项，不打断列表 */
  } finally {
    thumbCfgLoading.value = false;
  }
}
async function saveThumbKey(key: 'thumb_prewarm_enabled' | 'thumb_prewarm_wifi_only', value: boolean) {
  try {
    const { data } = await api.put(Endpoints.config.root, { [key]: value });
    const ok = (data?.updated_fields || []).includes(key);
    if (ok) message.success('已保存，立即生效');
    else message.error('设备未接受此项改动，已回滚');
  } catch (e: any) {
    message.error(e?.response?.data?.error || '保存失败');
  }
}
function savePrewarmEnabled(v: boolean) {
  saveThumbKey('thumb_prewarm_enabled', v);
}
function saveWifiOnly(v: boolean) {
  saveThumbKey('thumb_prewarm_wifi_only', v);
}

const prewarmNowRunning = ref(false);
async function prewarmNow() {
  if (prewarmNowRunning.value) return;
  prewarmNowRunning.value = true;
  try {
    await api.post(Endpoints.media.thumbnailPrewarm);
    message.success('已触发预热');
    loadPrewarmSnapshot();
  } catch (e: any) {
    const detail = e?.response?.data?.message || e?.response?.data?.error;
    message.error(detail || '触发失败（需先打开预热开关）');
  } finally {
    setTimeout(() => {
      prewarmNowRunning.value = false;
    }, 2000);
  }
}

const message = useMessage();
const dialog = useDialog();
const {
  api,
  items,
  total,
  loading,
  loadingMore,
  permissionDenied,
  failed,
  thumbs,
  sort,
  hasMore,
  reload,
  loadMore,
  setSort,
  clearThumbCache,
} = useMediaLibrary('video');

/**
 * 视图：`grid` = 海报墙（原有），`folder` = 文件夹（2026-10-14 新增）。
 * 与 app 的「首页 / 媒体库」两栏一一对应，只是 web 侧落在页头的一个切换上。
 */
const view = ref<'grid' | 'folder'>('grid');
/**
 * 文件夹视图是否被打开过。
 *
 * 模板上是 `v-if="folderEverOpened" v-show="view === 'folder'"`，而不是单纯的 `v-if`：
 * 没打开过就**不挂载**（省掉一次用户根本没要的 `/browse` 请求）；打开过之后只隐藏不卸载 ——
 * 否则每次切回海报墙再切回来，用户都要从根目录重新走一遍（app 侧对应的是
 * `MediaVideoScreen.kt:93-95` 那条"切栏不丢滚动位置"的修法）。
 */
const folderEverOpened = ref(false);
watch(view, (v) => {
  if (v === 'folder') folderEverOpened.value = true;
});

/** 重扫之后自增，用来让文件夹视图也补拉一次（见 onRescanned）。 */
const refreshKey = ref(0);

const { refreshAfterRescan } = useRescanRefresh();
/**
 * 「重新扫描」之后补拉列表。
 *
 * core 侧扫描是**异步**的（`POST /api/media/rescan` 立刻返回、后台慢慢扫），
 * 立刻重拉只会拿到旧列表。三次补拉的节奏与理由见 `components/mediaRescanRefresh.ts`。
 * 同时刷新文件夹视图（它自己那份 `/browse` 数据不会因为海报墙 reload 而更新）。
 */
function onRescanned() {
  refreshAfterRescan(() => {
    reload();
    refreshKey.value += 1;
  });
}

/**
 * 扫描范围保存成功之后重拉。
 *
 * 比原来多刷新了文件夹视图：扫描目录就是 `/browse` 的 roots（`browseRootsOf`），
 * 改完不重拉的话，文件夹视图里还会列着刚被移除的那个根、或者少一个刚加的。
 */
function onScanDirsSaved() {
  reload();
  refreshKey.value += 1;
}

/**
 * 页头的「刷新」按**当前视图**刷新。
 *
 * 网格（`/list`）与文件夹视图（`/browse`）是两份数据、各有自己的刷新入口，
 * 但页头这颗按钮在两种视图下都看得见 —— 在文件夹视图里点它没反应，
 * 是最容易被当成"坏了"的那种。所以它按当前视图分流。
 */
function refreshActive() {
  if (view.value === 'folder') refreshKey.value += 1;
  else reload();
}

// 2026-10-07：单条重抽封面 + 预热进度
// 预热进度：① 进页面先 GET 一次落盘快照（解决"退出重进看不到状态"）；
// ② WS data_changed/media:thumb-progress 实时推送；③ running 时每 3s 轮询兜底（WS 断了也能看到进度）。
const wsStore = useWebSocketStore();
const prewarm = ref<{
  done: number;
  total: number;
  failed: number;
  running: boolean;
  current_name?: string;
} | null>(null);
async function loadPrewarmSnapshot() {
  try {
    const { data } = await api.get(Endpoints.media.thumbnailPrewarm);
    if (data?.snapshot) {
      prewarm.value = {
        done: Number(data.snapshot.done) || 0,
        total: Number(data.snapshot.total) || 0,
        failed: Number(data.snapshot.failed) || 0,
        running: !!data.snapshot.running,
        current_name: String(data.snapshot.current_name || ''),
      };
    } else {
      prewarm.value = null;
    }
  } catch {
    /* 旧 core 无此端点，保持静默 */
  }
}
onMounted(loadPrewarmSnapshot);
const unsubPrewarm = wsStore.on('data_changed', (payload: any) => {
  if (payload?.changed === 'media:thumb-progress') {
    prewarm.value = {
      done: Number(payload.done) || 0,
      total: Number(payload.total) || 0,
      failed: Number(payload.failed) || 0,
      running: !!payload.running,
      current_name: String(payload.current_name || ''),
    };
  }
});
onUnmounted(unsubPrewarm);
let pollTimer: number | null = null;
function syncPolling() {
  const should = prewarm.value?.running === true;
  if (should && pollTimer === null) {
    pollTimer = window.setInterval(loadPrewarmSnapshot, 3000);
  } else if (!should && pollTimer !== null) {
    window.clearInterval(pollTimer);
    pollTimer = null;
  }
}
watch(prewarm, syncPolling, { deep: true });
onUnmounted(() => {
  if (pollTimer !== null) window.clearInterval(pollTimer);
});

const query = ref('');
const playingItem = ref<MediaItem | null>(null);
const streamUrl = ref('');
/**
 * 播放列表 = 点进来的那一批视频。
 *
 * 打开时**拷贝一份**（`slice()`），不长期持有 `filtered` 那个 computed：
 * 用户播到一半改了搜索词，`filtered` 会整个变掉，而播放列表应该保持"点进来时那一批"。
 */
const playlist = ref<MediaItem[]>([]);

const filtered = computed(() => {
  const q = query.value.trim().toLowerCase();
  return q ? items.value.filter((v) => v.name.toLowerCase().includes(q)) : items.value;
});

const showEmpty = computed(
  () => !loading.value && (permissionDenied.value || failed.value || items.value.length === 0)
);

const headSub = computed(() => {
  if (permissionDenied.value) return '未授权';
  return total.value ? `共 ${total.value} 个` : '';
});

/** 当前播的那一条在播放列表里的下标。-1 = 它已经不在列表里了（自动下一集因此停住）。 */
const playingIndex = computed(() => playlist.value.findIndex((p) => p.id === playingItem.value?.id));

function canStep(delta: number): boolean {
  const i = playingIndex.value;
  if (i < 0) return false;
  const n = i + delta;
  return n >= 0 && n < playlist.value.length;
}

/**
 * 换票据的代数。自动连播连续换片时会有两张票据在飞，先发后到时靠它丢弃过期结果
 * （照图片页 `viewerIndex` 的比对）。作废一张票据没有副作用：它可重复使用、
 * 只授权那一个文件、10 分钟滑动过期。
 */
let playSeq = 0;

/**
 * 正在换票据（换片的中间态）。
 *
 * 两个用途：
 * ① 「播完自动下一集」要防重入 —— 换票据期间 `playingItem` 还是旧那一条，
 *    这期间若再来一个 `ended`，`step(1)` 会按旧下标再跳一集（连跳两集）；
 * ② 自动连播的 ended 只认最后一次换片。
 */
const switching = ref(false);

/**
 * 切到某一条并换一张播放票据。
 *
 * ## 换片为什么不先改 `playingItem`（除了首次打开）
 * `VideoPlayer` 是按 `[url, name]` **两个 prop 一起**自更新的（见它里面那处
 * `watch(() => [props.url, props.name])`），
 * 而 name 是格式判定的唯一依据（票据地址没有后缀）。若先改 `playingItem` 再等票据，
 * 中间那一帧就是"新文件名 + 旧地址" —— 它会按新名字挑 customType 去解旧地址
 * （flv 的地址被当成 mp4 解），紧接着票据到货又整个重建一次。
 * 所以 url 与 name 必须在**同一次更新**里一起变：Vue 会把同一个 tick 里的两次赋值
 * 合并成一次 watch 回调。
 *
 * ## "换片不重建播放器"在这套代码里到底指什么
 * `VideoPlayer` 内部换 url 时**刻意**会 destroy + 重建 ArtPlayer 实例
 * （那处 watch 的注释写了原因：customType 的绑定不会随 url 变化重来，
 * 从 flv 切到 mp4 会带着上一个解复用器）。它没有 `defineExpose`，页面拿不到实例，
 * 也就没有"原地换源"这条路 —— 那要改 `VideoPlayer.vue`，不在本次写权限内。
 * 所以这里做到的是：**播放视图不退出、`<VideoPlayer>` 组件实例不卸载重挂、
 * 播放位置记忆与字幕/外观偏好连续**，只有它内部的 ArtPlayer 按它自己的设计重建一次。
 *
 * `first` = 首次打开：先把播放视图切出来（此时 url 还是空串，VideoPlayer 自己不建实例，
 * 由空容器占位），票据到货后再建 —— 否则要等一个网络往返画面才切过去。
 */
async function switchTo(item: MediaItem, first = false) {
  const s = ++playSeq;
  switching.value = true;
  if (first) {
    playingItem.value = item;
    streamUrl.value = '';
  }
  try {
    const url = await fetchStreamUrl(api, item.path);
    if (s !== playSeq) return;
    streamUrl.value = url;
    playingItem.value = item;
  } catch (e: any) {
    if (s !== playSeq) return;
    // 弹窗里没有自建错误区（只放播放器组件），所以失败用 toast 说明。
    // 原因优先取 core 的原话：axios 那句 "Request failed with status code 400" 没有信息量。
    const msg = e?.response?.data?.error || e?.response?.data?.message || e?.message || '获取播放地址失败';
    message.error(msg);
    // 首次打开失败：直接收起弹窗，不留下一个空的黑框
    if (first) {
      playerOpen.value = false;
      playingItem.value = null;
      streamUrl.value = '';
      playlist.value = [];
    }
  } finally {
    if (s === playSeq) switching.value = false;
  }
}

/**
 * 打开播放弹窗（弹窗里只放播放器组件）。
 *
 * `list` 是**点进来的那个列表**（海报墙的搜索结果 / 文件夹视图的当前一层）：
 * "播完自动下一集"以它为范围。刻意不自己拼一份 —— 从文件夹点进来的文件
 * 可能压根不在海报墙已加载的那几页里。
 *
 * 切过去之后把播放区滚进视野：手机上点的海报常在列表底部，不滚的话播放器在屏幕外，
 * 看起来就像"点了没反应"。`block: 'start'` 让播放器顶到滚动容器上沿。
 */
function open(v: MediaItem, list: MediaItem[]) {
  playlist.value = list.slice();
  void switchTo(v, true);
}

/** 自动连播的下一集。下标越界（已经在最后一集）时什么也不做 —— 停住，不循环。 */
function step(delta: number) {
  const i = playingIndex.value;
  if (i < 0) return;
  const next = playlist.value[i + delta];
  if (!next) return;
  void switchTo(next);
}

/** 播放弹窗的显隐。与 `playingItem` 分开：关闭时先让它跑完退场动画，再清内容。 */
const playerOpen = ref(false);
watch(playingItem, (v) => {
  if (v) playerOpen.value = true;
});

function close() {
  // 先让在途的换片作废：票据回来时若还认它，会把播放弹窗又打开（playingItem 被重新赋值）
  playSeq += 1;
  switching.value = false;
  playerOpen.value = false;
  // 内容不在这里清：等 @after-leave（退场动画结束）再收，
  // 否则播放器会先消失、弹窗空壳再淡出 —— 与设置页弹窗同一条口径。
}

/** 弹窗退场动画结束后再丢弃播放状态 */
function onPlayerLeave() {
  playingItem.value = null;
  // 清空 src 让浏览器立刻停止拉流
  streamUrl.value = '';
  // 播放列表一起收掉：下次从别处点进来不该还带着上一次那一批
  playlist.value = [];
}

// ──────────────────────── 播完自动下一集 ────────────────────────

/** 播放器容器：只用于把"别人的播放器"（文件预览弹窗那个）的 ended 事件过滤掉。 */
const playerHostEl = ref<HTMLElement | null>(null);

/**
 * 播完自动下一集（**最后一集停住，不循环**）。
 *
 * ## 为什么在 document 上挂**捕获阶段**的监听
 * `VideoPlayer` 只 emit `download` / `error`，没有 `ended`，也没有 `defineExpose`，
 * 页面拿不到它内部的 `<video>` / ArtPlayer 实例。而 `ended` 事件**不冒泡** ——
 * 但它会经过捕获阶段（window → … → target），所以挂在 document 上的**捕获**监听
 * 照样能收到；再用 `contains()` 把其它播放器的事件过滤掉。
 *
 * ## 与 app 的差异（刻意）
 * app 侧 `MediaVideoPlayerScreen.kt:104-107` 是 `pauseAtEndOfMediaItems = true`
 * （**播完停住，不自动下一个**）。本次按需求"播完自动下一集、最后一集停住"实现，
 * 两端在这一条上不同；想连播/停住的开关留给将来（控制条上没有对应入口）。
 */
function onMediaEnded(e: Event) {
  if (!playerOpen.value) return;
  const host = playerHostEl.value;
  if (!host || !(e.target instanceof Node) || !host.contains(e.target)) return;
  // 换片途中不再接一发（见 switching 的说明）
  if (switching.value) return;
  if (!canStep(1)) return;
  step(1);
}
onMounted(() => document.addEventListener('ended', onMediaEnded, true));
onUnmounted(() => document.removeEventListener('ended', onMediaEnded, true));

// ──────────────────────── 视频详情（video-info） ────────────────────────

/**
 * 2026-10-22：**整块删掉**。
 *
 * 这里原先是一个折叠的「详情」表（`GET /api/media/video-info`：编码 / 像素格式 / 码率 /
 * 帧率 / 来源），住在播放弹窗里。本轮要求播放时只留播放器与最少的切换控件，且不要
 * 再为它在播放区旁边开第二层滚动 —— 详情表的折叠头本身就占一行，展开后还要在限高
 * 容器里再滚一层。端点（`Endpoints.media.videoInfo`）仍留在契约里，
 * 以后要放回某个"文件信息"入口时不用重新接。
 */

// ──────────────────────── 整类封面缓存 ────────────────────────

const clearingThumbs = ref(false);

/**
 * 清空**整类**封面缓存（`DELETE /api/media/thumbnail-cache?type=video`，见 `clearThumbCache`）。
 *
 * 必须二次确认并说清代价：
 * core 会把设备上已生成的视频封面全删掉，之后每张都要重新抽帧（软解一帧是秒级），
 * 大库上会明显慢一阵 —— 用户点之前得知道这件事，而不是点完抱怨"怎么变卡了"。
 */
function confirmClearThumbs() {
  dialog.warning({
    title: '清空整类封面缓存？',
    content: '会删掉设备上已生成的全部视频封面并重新抽取，比较慢（每个视频都要重新解一帧）。清完后封面会陆续重新出现。',
    positiveText: '清空并重抽',
    negativeText: '取消',
    onPositiveClick: async () => {
      clearingThumbs.value = true;
      try {
        await clearThumbCache();
        message.success('已清空，封面正在重新生成');
      } catch (e: any) {
        message.error(e?.response?.data?.message || e?.response?.data?.error || '清空封面缓存失败');
      } finally {
        clearingThumbs.value = false;
      }
    },
  });
}

/**
 * 放不了的格式给一条下载出路。
 *
 * 用票据 URL + `download` 属性触发浏览器下载：票据地址在 `/api` 之外的免鉴权区，
 * `<a download>` 这种裸 GET 正好能用（`/api/files/download` 要签名头，`<a>` 带不上）。
 */
function downloadPlaying() {
  const item = playingItem.value;
  if (!item || !streamUrl.value) return;
  const a = document.createElement('a');
  a.href = streamUrl.value;
  a.download = item.name;
  a.click();
}

onMounted(() => {
  reload();
  loadThumbCfg();
});
</script>

<style scoped>
.prewarm-badge {
  font-size: 12px;
  color: var(--text-muted);
  /* 2026-10-14：搬进 340px 宽的浮层后不能再 nowrap —— 长文件名会把内容顶出浮层边界
     （grid 的 min-content 会被 nowrap 撑开）。改成允许换行 + 断词：名字完整可读，
     进度条换到下一行也仍读得出来，代价只是偶尔两行。 */
  white-space: normal;
  overflow-wrap: anywhere;
}
/* 预热进行中：主题色描边提示还有动态。
   2026-10-21：原来这里只写了 `border-color`，而没有 border-width / style —— 整条规则从未生效
   （元素上根本没有边框可染色）。补成一条真正的描边，才是注释里说的那个提示。 */
.prewarm-badge.running {
  border: 1px solid var(--accent-color);
  padding: 1px 6px;
  border-radius: var(--radius-sm);
}
.prewarm-bar {
  display: inline-block;
  width: 64px;
  height: 4px;
  margin-left: 8px;
  border-radius: var(--radius-sm);
  background: var(--border-subtle);
  overflow: hidden;
  vertical-align: middle;
}
.prewarm-bar-fill {
  display: block;
  height: 100%;
  border-radius: var(--radius-sm);
  background: var(--accent-color);
  transition: width 0.4s ease;
}

/* 页头 / 网格 / 加载更多这几块与图片页同构，但两页各自 scoped：
   共用一份要么抽成组件（两页的网格比例不同，抽了还要加参数），
   要么提到全局（会污染其它页的 .poster-* 命名）。当前规模下各留一份更清楚。 */
.media-list-page {
  display: flex;
  flex-direction: column;
  gap: 16px;
}
.page-head {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 16px;
  flex-wrap: wrap;
}
.head-text {
  display: flex;
  align-items: baseline;
  gap: 10px;
  min-width: 0;
}
.head-title {
  margin: 0;
  font-size: 20px;
  font-weight: 700;
  color: var(--text-primary);
}
.head-sub {
  font-size: 12px;
  color: var(--text-muted);
}
.head-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  /* 2026-10-14：页头多了「视图切换」与「排序」两个控件，中等宽度下这一排放不下。
     允许它们在 actions **内部**换行（而不是把标题挤到下一行），
     搜索框在窄一档的媒体查询里再让出宽度（见下面的 @media 1024px）。 */
  flex-wrap: wrap;
  justify-content: flex-end;
}
/* 视图切换：固定宽度，六档排序文案长短不一时页头不跟着抖 */
.view-switch {
  flex: 0 0 auto;
}
.head-search {
  width: 200px;
}
/* 2026-10-14：1024px 以下把搜索框收窄一档 —— 页头现在是
   视图切换 + 搜索 + 排序 + 刷新 + 两个设置入口，200px 的搜索框会把它们顶成三行。
   放在 768px 那条之前：那条的 flex:1 需要压过这里的固定宽度。 */
@media (max-width: 1024px) {
  .head-search {
    width: 140px;
  }
}

.poster-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(240px, 1fr));
  gap: 16px;
}
.poster-cell {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 0;
  border: none;
  background: transparent;
  cursor: pointer;
  text-align: left;
  font: inherit;
  color: inherit;
  min-width: 0;
}
.poster-thumb {
  position: relative;
  display: block;
  width: 100%;
  aspect-ratio: 16 / 9;
  border-radius: var(--radius-md);
  overflow: hidden;
  background: var(--surface-elevated);
  transition:
    transform 0.18s ease,
    box-shadow 0.18s ease;
}
.poster-cell:hover .poster-thumb {
  transform: translateY(-2px);
  box-shadow: 0 6px 18px var(--shadow-color-soft);
}
.thumb-img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}
.thumb-fallback {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 100%;
  height: 100%;
  color: var(--text-muted);
}
/* 角标压在缩略图上，必须自带深色底才在任何画面上都读得出来 —— 这里刻意不用主题令牌。
   ui-baseline-allow: 浮在缩略图上的角标，必须自带深底白字，两种主题下一致 */
.thumb-badge {
  position: absolute;
  right: 6px;
  bottom: 6px;
  padding: 1px 5px;
  border-radius: 3px;
  background: rgba(0, 0, 0, 0.72);
  color: #fff;
  font-size: 11px;
  line-height: 1.5;
  font-variant-numeric: tabular-nums;
}
/* 字幕角标：与时长角标分处两角（右上），用主色 —— 它是"有额外内容"的正面标记，
   不是浮在画面上的黑底读数，所以走令牌而不是深色字面量 */
.thumb-cc {
  position: absolute;
  left: 6px;
  top: 6px;
  padding: 1px 5px;
  border-radius: 3px;
  background: var(--accent-color);
  color: var(--on-accent);
  font-size: 11px;
  line-height: 1.5;
}
.poster-name {
  font-size: 13px;
  font-weight: 500;
  color: var(--text-primary);
  line-height: 1.4;
  /* 两行截断：文件名常常很长，一行放不下又不该把整格撑高 */
  display: -webkit-box;
  -webkit-line-clamp: 2;
  line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
.poster-meta {
  font-size: 11px;
  color: var(--text-muted);
}

.load-more {
  display: flex;
  justify-content: center;
  padding: 16px 0;
}

/* 播放弹窗：**只承载 VideoPlayer**。播放器自带控制条、字幕菜单、倍速与全屏，
   这里不再有标题栏、工具条或自建错误卡片；弹窗宽度就是播放器的宽度上限。 */
.player-modal {
  /* 宽度同时受视口宽、960 上限、视口高（16:9 + 上下留白）约束；
     aspect-ratio 固定成矩形，避免内容把弹窗撑成奇怪的比例。 */
  width: min(960px, calc(100vw - 32px), calc((100vh - 120px) * 16 / 9));
  aspect-ratio: 16 / 9;
}
.player-shell {
  /* n-modal 会把 slot 根节点的 class 合到弹窗卡片上，这里不要再写 width:100%：
     那会把上面的 960px 上限覆盖成「视口 - 32px」，左右只剩 16px。 */
  line-height: 0;
}
/* 错误卡片也填满 16:9 的弹窗，避免下半截露出白底 */
.player-modal :deep(.vp-unsupported) {
  height: 100%;
  min-height: 0;
  box-sizing: border-box;
}
@media (max-width: 768px) {
  /* 页头竖排：标题与「搜索 + 刷新」各占一行，搜索框吃满剩余宽度。
     横排时那个 200px 的搜索框会把标题挤成两行（360px 视口放不下 标题+200+刷新）。 */
  .page-head {
    flex-direction: column;
    align-items: stretch;
    gap: 10px;
  }
  /* 手机页头：第一行「视图切换 + 排序 + 两个图标」，搜索独占第二行。
     之前让搜索和它们挤一行，390 宽下输入框只剩 53px —— 等于不可用。 */
  .head-actions {
    flex-wrap: wrap;
    gap: 8px;
  }
  .head-search {
    order: 10;
    flex: 1 1 100%;
    width: 100%;
    margin-left: 0;
  }
  .head-actions > .n-select {
    margin-left: auto;
  }
  .poster-grid {
    grid-template-columns: repeat(auto-fill, minmax(150px, 1fr));
    gap: 10px;
  }
}
/* ⚙ 浮层：封面预热在上、扫描范围在下，中间一条细分隔线 */
.vid-settings {
  display: flex;
  flex-direction: column;
  gap: 10px;
  /* 视频设置内容比音乐/图片的独立浮层长（预热 + 扫描目录 + 缓存操作），
     小屏/矮屏下必须自己滚动。高度同时受 70vh 和 600px 约束：
     只写 calc(100vh - 120px) 时浮层仍可能从触发按钮下方伸出屏幕。 */
  max-height: min(70vh, 600px);
  overflow-y: auto;
  overscroll-behavior: contain;
  -webkit-overflow-scrolling: touch;
  padding-right: 2px;
}
.vs-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--text-primary);
}
.vs-sep {
  border-top: 1px solid var(--border-subtle);
  margin: 2px 0;
}

/* 海报 hover：圆底 + 三角，提示"可播放"（与 app 的 PosterCell 一致）。
   圆底与三角都走令牌：--on-accent 在两种主题下都是浅色，--brand-panel-1 是深色。 */
.thumb-play {
  position: absolute;
  left: 50%;
  top: 50%;
  z-index: 2;
  width: 46px;
  height: 46px;
  border-radius: 50%;
  background: var(--on-accent);
  display: grid;
  place-items: center;
  opacity: 0;
  transform: translate(-50%, -50%) scale(0.9);
  transition:
    opacity 0.18s ease,
    transform 0.18s ease;
  pointer-events: none;
}
.thumb-play::after {
  content: '';
  margin-left: 3px;
  border-left: 14px solid var(--brand-panel-1);
  border-top: 9px solid transparent;
  border-bottom: 9px solid transparent;
}
.poster-cell:hover .thumb-play,
.poster-cell:focus-visible .thumb-play {
  opacity: 1;
  transform: translate(-50%, -50%) scale(1);
}

/* 2026-10-07：封面设置 popover（自 GeneralPanel 迁入） */
/* 2026-10-21：从 2 列栅格（开关在左 / 说明在右）改成纵向堆叠。开关行改用全局
   `.config-item.switch-item`（标签左、控件右）—— 家规里「两列栅格内的开关」就归那一版式，
   而 340px 的浮层塞不下 ToggleRow 的整行版式（14px 标签 + 12px 说明 + 分隔线）。 */
.thumb-cfg {
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
}
.thumb-cfg-label {
  flex: 1;
  min-width: 0;
  font-size: 12px;
  line-height: 1.4;
}
/* 「立即预热一轮」贴右：它是这个浮层里唯一的动作按钮 */
.thumb-cfg-actions {
  display: flex;
  justify-content: flex-end;
}
.thumb-cfg-note {
  font-size: 11px;
  color: var(--text-muted, #999);
  line-height: 1.5;
}
</style>
