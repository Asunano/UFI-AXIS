<!--
  视频页。与 app 的 `MediaVideoScreen` / `MediaVideoHome`（海报墙）对应。

  海报墙使用 16:9 缩略图、两行文件名与时长角标，信息组与 app 的 `PosterCell` 一致。
  点击后在弹窗中播放：列表保留在页面上，关闭弹窗即回到原位置。

  功能组成（细节见各处注释）：
  · 文件夹视图（页头「海报墙 / 文件夹」切换，共用 `components/MediaFolderBrowser.vue`）；
  · 排序下推到 core（`MediaSortControl` + `setSort`）；
  · 重新扫描后延迟补拉（`@rescanned` → `mediaRescanRefresh`）；
  · 播放列表：播放结束自动进入下一集（最后一集停止）；
  · 字幕数量角标（`MediaItem.subtitle_count`）；
  · 清空整类封面缓存（「封面与扫描设置」浮层内，需二次确认）。

  播放区：
  · 弹窗承载播放器：点击海报在 n-modal 中播放，列表保留在页面上，关闭后回到原位置。
    弹窗内仅包含 VideoPlayer，控制条、字幕菜单、倍速与全屏由其自带；不叠加标题、
    工具条或错误提示。
  · 播放器自身的错误卡片负责处理无法播放的情况：容器与编码诊断在 `VideoPlayer` 中，
    下载入口也在该卡片上。
-->
<template>
  <div class="media-list-page">
    <!-- 页头始终保留在页面上（播放弹窗覆盖其上）：关闭弹窗即回到原有的列表与滚动位置，
         不需要为播放另换一套页头或工具条。 -->
    <div class="page-head">
      <div class="head-text">
        <h2 class="head-title">视频</h2>
        <span class="head-sub">{{ headSub }}</span>
      </div>
      <div class="head-actions">
        <!-- 视图切换（海报墙 / 文件夹），与 app 的「首页 / 媒体库」结构对应 -->
        <n-radio-group v-model:value="view" size="small" class="view-switch">
          <n-radio-button value="grid">海报墙</n-radio-button>
          <n-radio-button value="folder">文件夹</n-radio-button>
        </n-radio-group>
        <!-- 搜索仅作用于海报墙（文件夹视图使用自身的逐层导航） -->
        <n-input
          v-if="view === 'grid'"
          v-model:value="query"
          size="small"
          placeholder="搜索文件名"
          clearable
          class="head-search"
        />
        <!-- 排序必须下推到 core（见 useMediaLibrary.setSort），不能直接使用 v-model -->
        <MediaSortControl :model-value="sort" @update:model-value="setSort" />
        <!-- 刷新仅作用于海报墙；文件夹视图的工具条中已有独立入口 -->
        <n-button v-if="view === 'grid'" size="small" quaternary :loading="loading" title="刷新" @click="refreshActive">
          <template #icon
            ><n-icon><RefreshOutline /></n-icon
          ></template>
        </n-button>
        <!--
          封面预热与扫描范围合并到该 ⚙ 浮层。
          两者都是低频设备侧设置，平铺在页头会挤压「搜索 / 排序」这类浏览操作。
          预热进度显示在「立即预热一轮」正下方，使触发与进度处于同一视觉块。
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
              <!-- 不提供「取消」按钮：core 只提供触发与读取快照，没有取消端点 -->
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
              <!-- 清空整类封面缓存，必须二次确认 -->
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

    <!-- ── 播放弹窗：只包含播放器组件 ──
         ArtPlayer 自带播放/暂停、进度、音量、倍速、全屏与字幕菜单；弹窗不叠加标题、
         工具条或任何自建按钮。列表继续保留在页面上，关闭即回到原位置。 -->
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

    <!-- 列表视图。两个视图都保留在 DOM 中，用 v-show 切换（不使用 v-else/v-if，后者会真正卸载）：
         海报墙保持挂载（已获取的封面无需重取），文件夹视图首次打开时才挂载（见 folderEverOpened）。
         列表在弹窗打开期间保持挂载：关闭时封面与文件夹层级均保留，无需重新请求或重新展开。 -->
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
            <!-- 外挂字幕条数（core 的 /list 返回该字段，见 MediaItem.subtitle_count） -->
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
// 封面设置使用的端点通过契约引用
// （`Endpoints.media.thumbnailPrewarm` / `Endpoints.config.root` 与字面量逐字一致）。
// 契约中不存在的端点（如 `/api/files/*`）继续使用字面量。
import { Endpoints } from '@/api/contract';
import { fetchStreamUrl, formatDuration, formatMediaSize, type MediaItem } from './mediaShared';
import { useMediaLibrary } from './useMediaLibrary';
import { useRescanRefresh } from './components/mediaRescanRefresh';
import MediaEmptyState from './components/MediaEmptyState.vue';
import MediaFolderBrowser from './components/MediaFolderBrowser.vue';
import MediaScanDirsPanel from './components/MediaScanDirsPanel.vue';
import MediaSortControl from './components/MediaSortControl.vue';
import VideoPlayer from '@/components/VideoPlayer.vue';

// 封面预热设置：视频域开关定义在视频页。
// 读取：GET /api/config；写入：PUT /api/config（与 GeneralPanel 的 saveProbeSwitch 使用同一端点）。
const thumbCfg = reactive({ prewarmEnabled: false, wifiOnly: false });
/** ⚙ 浮层（封面预热 + 扫描范围）的开关；同时驱动扫描范围面板重新拉取。 */
const settingsOpen = ref(false);
const thumbCfgLoading = ref(false);
async function loadThumbCfg() {
  thumbCfgLoading.value = true;
  try {
    const { data } = await api.get(Endpoints.config.root);
    thumbCfg.prewarmEnabled = !!data?.thumb_prewarm_enabled;
    thumbCfg.wifiOnly = !!data?.thumb_prewarm_wifi_only;
  } catch {
    /* 读取失败时保留默认值；该开关不影响核心功能，不打断列表加载。 */
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
 * 视图：`grid` = 海报墙，`folder` = 文件夹视图。
 * 与 app 的「首页 / 媒体库」两栏一一对应，在 web 侧表现为页头的切换控件。
 */
const view = ref<'grid' | 'folder'>('grid');
/**
 * 文件夹视图是否被打开过。
 *
 * 模板使用 `v-if="folderEverOpened" v-show="view === 'folder'"`，而非单纯的 `v-if`：
 * 未打开过时不挂载，避免产生用户未请求的 `/browse` 请求；打开过后仅隐藏不卸载，
 * 否则每次切回海报墙再切回来都需从根目录重新进入（与 app 侧
 * `MediaVideoScreen.kt:93-95` 的「切栏不丢滚动位置」一致）。
 */
const folderEverOpened = ref(false);
watch(view, (v) => {
  if (v === 'folder') folderEverOpened.value = true;
});

/** 重扫后自增，用于让文件夹视图也补拉一次（见 onRescanned）。 */
const refreshKey = ref(0);

const { refreshAfterRescan } = useRescanRefresh();
/**
 * 「重新扫描」之后补拉列表。
 *
 * core 侧扫描是异步的（`POST /api/media/rescan` 立即返回，后台执行扫描），
 * 立即重拉只会得到旧列表。三次补拉的节奏与理由见 `components/mediaRescanRefresh.ts`。
 * 同时刷新文件夹视图：其 `/browse` 数据不会因海报墙 reload 而更新。
 */
function onRescanned() {
  refreshAfterRescan(() => {
    reload();
    refreshKey.value += 1;
  });
}

/**
 * 扫描范围保存成功后重新拉取。
 *
 * 同时刷新文件夹视图：扫描目录即 `/browse` 的 roots（`browseRootsOf`），
 * 不重拉会保留已移除的根或缺少新增的根。
 */
function onScanDirsSaved() {
  reload();
  refreshKey.value += 1;
}

/**
 * 页头的「刷新」按当前视图执行刷新。
 *
 * 网格（`/list`）与文件夹视图（`/browse`）是两份数据、各有刷新入口，
 * 而页头按钮在两种视图下均可见；在文件夹视图下无响应会被误认为故障，
 * 因此按当前视图分流。
 */
function refreshActive() {
  if (view.value === 'folder') refreshKey.value += 1;
  else reload();
}

// 预热进度：① 进入页面先 GET 一次落盘快照（使退出重进后仍能看到状态）；
// ② WS data_changed/media:thumb-progress 实时推送；
// ③ running 时每 3s 轮询兜底（WS 断开时仍可获取进度）。
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
    /* 旧 core 无此端点，保持静默。 */
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
 * 播放列表 = 点击进入时的那一批视频。
 *
 * 打开时拷贝一份（`slice()`），不长期持有 `filtered` 这个 computed：
 * 用户播放过程中修改搜索词会使 `filtered` 整体变化，而播放列表应保持点击进入时的那一批。
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

/** 当前播放条目在播放列表中的下标。-1 表示它已不在列表中（自动下一集因此停止）。 */
const playingIndex = computed(() => playlist.value.findIndex((p) => p.id === playingItem.value?.id));

function canStep(delta: number): boolean {
  const i = playingIndex.value;
  if (i < 0) return false;
  const n = i + delta;
  return n >= 0 && n < playlist.value.length;
}

/**
 * 换票据的代数。自动连播连续换片时可能有两张票据在途，先发后到时据此丢弃过期结果
 * （与图片页 `viewerIndex` 的比对方式一致）。作废一张票据没有副作用：票据可重复使用、
 * 只授权该文件、10 分钟滑动过期。
 */
let playSeq = 0;

/**
 * 正在换票据（换片的中间状态）。
 *
 * 两个用途：
 * ① 「播完自动下一集」需要防重入：换票据期间 `playingItem` 仍是旧条目，
 *    此时若再次收到 `ended`，`step(1)` 会按旧下标再跳一集（连续跳两集）；
 * ② 自动连播的 `ended` 只认最后一次换片。
 */
const switching = ref(false);

/**
 * 切到某一条并换一张播放票据。
 *
 * ## 换片时不先修改 `playingItem`（首次打开除外）
 * `VideoPlayer` 按 `[url, name]` 两个 prop 一起自更新（见其内部的
 * `watch(() => [props.url, props.name])`），而 name 是格式判定的唯一依据
 * （票据地址没有后缀）。若先修改 `playingItem` 再等待票据，中间一帧为
 * 「新文件名 + 旧地址」，会按新名字选择 customType 解析旧地址（例如把 flv 地址
 * 当作 mp4 解析），票据到达后又整体重建一次。
 * 因此 url 与 name 必须在同一次更新中一起变化：Vue 会把同一 tick 内的两次赋值
 * 合并为一次 watch 回调。
 *
 * ## 换片时播放器的重建范围
 * `VideoPlayer` 在 url 变化时会 destroy 并重建 ArtPlayer 实例（原因见该处 watch 的注释：
 * customType 的绑定不会随 url 变化重新执行，从 flv 切到 mp4 会沿用上一个解复用器）。
 * 它没有 `defineExpose`，页面无法获取实例，因此不存在原地换源的路径。
 * 实际效果是：播放视图不退出、`<VideoPlayer>` 组件实例不卸载重挂、
 * 播放位置记忆与字幕/外观偏好保持连续，仅其内部的 ArtPlayer 按其设计重建一次。
 *
 * `first` = 首次打开：先切换出播放视图（此时 url 为空串，VideoPlayer 不创建实例，
 * 由空容器占位），票据到达后再创建，避免等待一次网络往返才显示画面。
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
    // 弹窗内没有自建错误区（仅包含播放器组件），失败通过 toast 说明。
    // 原因优先取 core 返回的内容：axios 的 "Request failed with status code 400" 不含有效信息。
    const msg = e?.response?.data?.error || e?.response?.data?.message || e?.message || '获取播放地址失败';
    message.error(msg);
    // 首次打开失败：直接关闭弹窗，不保留空的播放区域。
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
 * 打开播放弹窗（弹窗内仅包含播放器组件）。
 *
 * `list` 是点击进入时的列表（海报墙的搜索结果或文件夹视图的当前层），
 * 「播完自动下一集」以它为范围。不自行拼接列表：从文件夹视图进入的文件
 * 可能不在海报墙已加载的页中。
 *
 * 播放器在弹窗中呈现，覆盖在列表之上，因此打开播放不改变列表的滚动位置。
 */
function open(v: MediaItem, list: MediaItem[]) {
  playlist.value = list.slice();
  void switchTo(v, true);
}

/** 自动连播的下一集。下标越界（已在最后一集）时不执行任何操作，即停止且不循环。 */
function step(delta: number) {
  const i = playingIndex.value;
  if (i < 0) return;
  const next = playlist.value[i + delta];
  if (!next) return;
  void switchTo(next);
}

/** 播放弹窗的显隐。与 `playingItem` 分开：关闭时先等待退场动画结束，再清理内容。 */
const playerOpen = ref(false);
watch(playingItem, (v) => {
  if (v) playerOpen.value = true;
});

function close() {
  // 先作废在途的换片：票据返回时若仍被认可，会重新打开播放弹窗（playingItem 被重新赋值）。
  playSeq += 1;
  switching.value = false;
  playerOpen.value = false;
  // 内容不在此处清理：等 @after-leave（退场动画结束）后再收，
  // 否则播放器会先消失、弹窗空壳再淡出，与设置页弹窗的处理一致。
}

/** 弹窗退场动画结束后再丢弃播放状态。 */
function onPlayerLeave() {
  playingItem.value = null;
  // 清空 src 使浏览器立即停止拉流。
  streamUrl.value = '';
  // 播放列表一并清理：下次从其他入口进入时不应保留上一次的列表。
  playlist.value = [];
}

// ──────────────────────── 播完自动下一集 ────────────────────────

/** 播放器容器：用于过滤其他播放器（文件预览弹窗）的 ended 事件。 */
const playerHostEl = ref<HTMLElement | null>(null);

/**
 * 播完自动下一集（最后一集停止，不循环）。
 *
 * ## 在 document 上挂捕获阶段监听的原因
 * `VideoPlayer` 只 emit `download` / `error`，没有 `ended`，也没有 `defineExpose`，
 * 页面无法获取其内部的 `<video>` / ArtPlayer 实例。`ended` 事件不冒泡，
 * 但会经过捕获阶段（window → … → target），因此挂在 document 上的捕获监听仍能收到；
 * 再用 `contains()` 过滤其他播放器的事件。
 *
 * ## 与 app 的差异
 * app 侧 `MediaVideoPlayerScreen.kt:104-107` 使用 `pauseAtEndOfMediaItems = true`
 * （播放结束停住，不自动播放下一集）。web 侧按「播完自动下一集、最后一集停止」实现，
 * 两端在此行为上不同；连播与停止的开关暂无对应入口。
 */
function onMediaEnded(e: Event) {
  if (!playerOpen.value) return;
  const host = playerHostEl.value;
  if (!host || !(e.target instanceof Node) || !host.contains(e.target)) return;
  // 换片过程中不重复触发（见 switching 的说明）。
  if (switching.value) return;
  if (!canStep(1)) return;
  step(1);
}
onMounted(() => document.addEventListener('ended', onMediaEnded, true));
onUnmounted(() => document.removeEventListener('ended', onMediaEnded, true));

// ──────────────────────── 视频详情（video-info） ────────────────────────

/**
 * 视频详情（video-info）区。
 *
 * 此处不渲染折叠式「详情」表（`GET /api/media/video-info`：编码 / 像素格式 / 码率 /
 * 帧率 / 来源）。播放区只保留播放器与最少的切换控件，不额外增加第二层滚动区域：
 * 详情表的折叠头本身占用一行，展开后还需在限高容器内再滚动一层。
 * 端点（`Endpoints.media.videoInfo`）仍保留在契约中，供「文件信息」类入口复用。
 */

// ──────────────────────── 整类封面缓存 ────────────────────────

const clearingThumbs = ref(false);

/**
 * 清空整类封面缓存（`DELETE /api/media/thumbnail-cache?type=video`，见 `clearThumbCache`）。
 *
 * 必须二次确认并说明代价：
 * core 会删除设备上已生成的全部视频封面，之后每张都需要重新抽帧（软解一帧为秒级），
 * 大库上会有明显延迟，用户需要在操作前知悉。
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
 * 无法播放的格式提供下载入口。
 *
 * 使用票据 URL 与 `download` 属性触发浏览器下载：票据地址位于 `/api` 之外的免鉴权区，
 * `<a download>` 的裸 GET 可以使用（`/api/files/download` 需要签名头，`<a>` 无法携带）。
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
  font-size: var(--font-base);
  color: var(--text-muted);
  /* 在 340px 宽的浮层内不能使用 nowrap：长文件名会把内容顶出浮层边界
     （grid 的 min-content 会被 nowrap 撑开）。改为允许换行与断词：文件名完整可读，
     进度条换到下一行仍可辨识，代价仅为偶尔显示两行。 */
  white-space: normal;
  overflow-wrap: anywhere;
}
/* 预热进行中：主题色描边提示存在活动。
   仅设置 `border-color` 而没有 border-width 与 style 时整条规则不会生效
   （元素上不存在可染色的边框），因此此处给出完整描边。 */
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

/* 页头 / 网格 / 加载更多这几块与图片页结构相同，但两页各自 scoped：
   共用一份需要抽成组件（两页的网格比例不同，抽取后还需增加参数），
   或提升为全局样式（会污染其他页的 .poster-* 命名）。当前规模下各保留一份更清晰。 */
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
  font-size: var(--font-xl);
  font-weight: 700;
  color: var(--text-primary);
}
.head-sub {
  font-size: var(--font-base);
  color: var(--text-muted);
}
.head-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  /* 页头包含「视图切换」与「排序」两个控件后，中等宽度下该行无法容纳。
     允许其在 actions 内部换行（而不是把标题挤到下一行），
     搜索框在更窄的媒体查询中再让出宽度（见下方 @media 1024px）。 */
  flex-wrap: wrap;
  justify-content: flex-end;
}
/* 视图切换：固定宽度，六档排序文案长短不一时页头不发生位移。 */
.view-switch {
  flex: 0 0 auto;
}
.head-search {
  width: 200px;
}
/* 1024px 以下把搜索框收窄一档：页头包含视图切换 + 搜索 + 排序 + 刷新 + 两个设置入口，
   200px 的搜索框会使其占三行。该规则放在 768px 之前，因为后者的 flex:1
   需要覆盖此处的固定宽度。 */
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
/* 角标压在缩略图上，必须自带深色底才能在任何画面上保持可读，因此不使用主题令牌。
   ui-baseline-allow: 浮在缩略图上的角标，必须自带深底白字，两种主题下一致 */
.thumb-badge {
  position: absolute;
  right: 6px;
  bottom: 6px;
  padding: 1px 5px;
  border-radius: 3px;
  background: rgba(0, 0, 0, 0.72);
  color: #fff;
  font-size: var(--font-sm);
  line-height: 1.5;
  font-variant-numeric: tabular-nums;
}
/* 字幕角标：与时长角标分处两角（右上），使用主色。它表示「存在额外内容」，
   而非浮在画面上的黑底读数，因此使用令牌而不是深色字面量。 */
.thumb-cc {
  position: absolute;
  left: 6px;
  top: 6px;
  padding: 1px 5px;
  border-radius: 3px;
  background: var(--accent-color);
  color: var(--on-accent);
  font-size: var(--font-sm);
  line-height: 1.5;
}
.poster-name {
  font-size: var(--font-base);
  font-weight: 500;
  color: var(--text-primary);
  line-height: 1.4;
  /* 两行截断：文件名通常较长，一行放不下且不应把整个单元格撑高。 */
  display: -webkit-box;
  -webkit-line-clamp: 2;
  line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
.poster-meta {
  font-size: var(--font-sm);
  color: var(--text-muted);
}

.load-more {
  display: flex;
  justify-content: center;
  padding: 16px 0;
}

/* 播放弹窗：只承载 VideoPlayer。播放器自带控制条、字幕菜单、倍速与全屏，
   此处不提供标题栏、工具条或自建错误卡片；弹窗宽度即播放器宽度上限。 */
.player-modal {
  /* 宽度同时受视口宽、960px 上限与视口高（16:9 + 上下留白）约束；
     aspect-ratio 固定为矩形，避免内容改变弹窗比例。 */
  width: min(960px, calc(100vw - 32px), calc((100vh - 120px) * 16 / 9));
  aspect-ratio: 16 / 9;
}
.player-shell {
  /* n-modal 会把 slot 根节点的 class 合并到弹窗卡片上，此处不能写 width:100%：
     那会覆盖上面的 960px 上限，使左右仅剩 16px。 */
  line-height: 0;
}
/* 错误卡片同样填满 16:9 的弹窗，避免下半部分露出白色背景。 */
.player-modal :deep(.vp-unsupported) {
  height: 100%;
  min-height: 0;
  box-sizing: border-box;
}
@media (max-width: 768px) {
  /* 页头竖排：标题与「搜索 + 刷新」各占一行，搜索框占满剩余宽度。
     横排时 200px 的搜索框会把标题挤成两行（360px 视口无法容纳标题 + 200px + 刷新）。 */
  .page-head {
    flex-direction: column;
    align-items: stretch;
    gap: 10px;
  }
  /* 手机页头：第一行「视图切换 + 排序 + 两个图标」，搜索独占第二行。
     搜索与它们同行时，390px 宽下输入框仅剩 53px，无法使用。 */
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
  /* 视频设置内容比音乐/图片的独立浮层更长（预热 + 扫描目录 + 缓存操作），
     小屏与矮屏下必须自身滚动。高度同时受 70vh 与 600px 约束：
     仅写 calc(100vh - 120px) 时浮层仍可能从触发按钮下方超出屏幕。 */
  max-height: min(70vh, 600px);
  overflow-y: auto;
  overscroll-behavior: contain;
  -webkit-overflow-scrolling: touch;
  padding-right: 2px;
}
.vs-title {
  font-size: var(--font-base);
  font-weight: 600;
  color: var(--text-primary);
}
.vs-sep {
  border-top: 1px solid var(--border-subtle);
  margin: 2px 0;
}

/* 海报 hover：圆底 + 三角，提示可播放（与 app 的 PosterCell 一致）。
   圆底与三角均使用令牌：--on-accent 在两种主题下均为浅色，--brand-panel-1 为深色。 */
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

/* 封面设置 popover：从 2 列栅格（开关在左 / 说明在右）改为纵向堆叠。开关行使用全局
   `.config-item.switch-item`（标签左、控件右）—— 该版式用于两列栅格内的开关，
   而 340px 的浮层无法容纳 ToggleRow 的整行版式（14px 标签 + 12px 说明 + 分隔线）。 */
.thumb-cfg {
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
}
.thumb-cfg-label {
  flex: 1;
  min-width: 0;
  font-size: var(--font-base);
  line-height: 1.4;
}
/* 「立即预热一轮」右对齐：它是该浮层中唯一的动作按钮。 */
.thumb-cfg-actions {
  display: flex;
  justify-content: flex-end;
}
.thumb-cfg-note {
  font-size: var(--font-sm);
  color: var(--text-muted);
  line-height: 1.5;
}
</style>
