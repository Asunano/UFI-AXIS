<!--
  音乐页（2026-10-23 按 A + B + D 方案重做）。

  ## 版式
  · **A 资料库（桌面/平板）**：页头只留浏览动作（分类页签 / 搜索 / 排序 / 刷新 / 设置），
    下面直接是当前队列列表，底部一条两行式播放控制条。
    「最近添加 / 接下来播放」架子已删除（2026-10-23）：它占掉 280px+ 高度，
    在矮屏会把列表压到 0 行，而快速入口和列表本身重复。
  · **B 沉浸播放**：点封面 / 歌词 / 展开钮进入覆盖层：大封面 + 同步歌词 + 同一套控制条。
    默认只铺满内容区，顶栏右侧的全屏钮可覆盖顶栏与侧栏。
    队列不再常驻右栏，改成控制条上的「队列」下拉面板（桌面 340px，手机全宽）。
  · **D 手机（≤768）**：列表 + 底部迷你条；点迷你条进同一个沉浸覆盖层（全屏），
    控制条自动切到手机排布。桌面播放条用 `v-if` 隐藏，避免 CSS 硬挤。

  ## 分类（歌单 / 专辑 / 艺人 / 文件夹）
  顶部页签只切换**下方列表区的内容**，不再开侧边抽屉：
  歌单页签显示歌单列表，分组页签显示对应维度的分组列表；点进一项后在同一区域
  换成曲目，列表头的 `‹ xxx` 退回上一层。GroupBrowserPanel 以 `inline` 模式复用。

  ## 播放状态
  仍然全部在 `stores/player.ts`：本页只把队列推给 store、把行上的意图翻译成 store 动作。
  `<audio>` 宿主在常驻的 `GlobalAudioPlayer`，顶栏迷你条与本页**共用同一份真源**。
  新控制条 `AudioControlBar.vue` 同时用于底部播放条与沉浸层（variant 区分明暗与布局）。

  ## 歌词
  沉浸层右侧（手机在封面下方）是 `LyricsPanel`；资料库里点「歌词」直接进入沉浸，
  不再保留一条常驻歌词列 —— 它先把列表挤窄，又和"正在听"这件事重复。
-->
<template>
  <div class="audio-page" :class="{ 'stage-open': stageOpen, 'has-mini-bar': player.currentTrack && isMobile }">
    <!-- ═══ 页头：浏览动作（来源 / 搜索 / 排序 / 刷新 / 设置） ═══ -->
    <header class="page-head">
      <div class="head-top">
        <div class="head-text">
          <h2 class="head-title">音乐</h2>
          <span class="head-sub">{{ headSub }}</span>
        </div>
        <div class="head-tools">
          <n-input v-model:value="query" size="small" :placeholder="searchPlaceholder" clearable class="head-search" />
          <span class="sort-wrap" :class="{ 'is-disabled': !sortAvailable }" :title="sortHint">
            <span :inert="!sortAvailable || undefined">
              <MediaSortControl :model-value="sort" @update:model-value="setSort" />
            </span>
          </span>
          <n-button size="small" quaternary :loading="loading" title="刷新" @click="reload">
            <template #icon
              ><n-icon><RefreshOutline /></n-icon
            ></template>
          </n-button>
          <MediaScanDirsSettings
            kind="audio"
            label="设置"
            :icon-only="isMobile"
            @saved="reload"
            @rescanned="onRescanned"
          >
            <template #default="{ active }">
              <MediaExcludedManager :active="active" @changed="reload" />
            </template>
          </MediaScanDirsSettings>
        </div>
      </div>
      <nav class="source-tabs" role="tablist" aria-label="浏览分类">
        <button
          type="button"
          role="tab"
          :aria-selected="browseMode === 'all'"
          :class="{ on: browseMode === 'all' }"
          @click="showAll"
        >
          全部
        </button>
        <button
          type="button"
          role="tab"
          :aria-selected="browseMode === 'playlist'"
          :class="{ on: browseMode === 'playlist' }"
          @click="openPlaylistTab"
        >
          歌单
        </button>
        <button
          v-for="g in GROUP_CHIPS"
          :key="g.by"
          type="button"
          role="tab"
          :aria-selected="browseMode === g.by"
          :class="{ on: browseMode === g.by }"
          @click="openGroupTab(g.by)"
        >
          {{ g.label }}
        </button>
      </nav>
    </header>

    <MediaEmptyState
      v-if="showEmpty"
      :permission-denied="permissionDenied"
      :failed="failed"
      kind-label="音频"
      @retry="reload"
    />

    <template v-else>
      <!-- ═══ 资料库 / 当前队列（桌面与平板的主体） ═══ -->
      <section class="library-view">
        <Transition name="view-swap" mode="out-in">
          <div :key="viewKey" class="view-host">
            <div class="list-head" :class="{ 'is-browse': browseView !== 'tracks' }">
              <button
                v-if="showBrowseBack"
                type="button"
                class="list-back"
                :title="`返回${backLabel}列表`"
                @click="backToBrowse"
              >
                ‹ {{ backLabel }}
              </button>
              <span class="list-title">{{ listTitle }}</span>
              <span class="list-count">{{ listCountText }}</span>
              <span class="grow"></span>
              <n-button v-if="browseView === 'playlists'" size="tiny" quaternary @click="openCreatePlaylist">
                新建歌单
              </n-button>
              <template v-else-if="browseView === 'tracks'">
                <span class="list-actions">
                  <n-button
                    v-if="!isLibraryQueue"
                    size="tiny"
                    quaternary
                    :disabled="playableCount === 0"
                    title="按列表顺序从第一首开始播放（会关闭随机播放）"
                    @click="playAllQueue(false)"
                  >
                    播放全部
                  </n-button>
                  <n-button
                    v-if="!isLibraryQueue"
                    size="tiny"
                    quaternary
                    :disabled="playableCount === 0"
                    title="打乱顺序播放这一份队列里的全部曲目（会开启随机播放）"
                    @click="playAllQueue(true)"
                  >
                    随机播放
                  </n-button>
                  <n-button
                    v-if="queueTracks.length"
                    size="tiny"
                    quaternary
                    title="清空播放队列（不影响正在听的那一首，也不删任何文件）"
                    @click="confirmClearQueue"
                  >
                    清空
                  </n-button>
                </span>
              </template>
            </div>

            <!-- ── 歌曲列表（全部 / 某个歌单 / 某个分组） ── -->
            <template v-if="browseView === 'tracks'">
              <div class="table-head" aria-hidden="true">
                <div class="th-main">
                  <span class="th-idx">#</span>
                  <span class="th-title">标题</span>
                  <span class="th-artist">艺人</span>
                  <span class="th-album">专辑</span>
                  <span class="th-dur">时长</span>
                </div>
                <span class="th-more"></span>
              </div>

              <n-spin :show="queueLoading && queueTracks.length === 0" class="list-spin">
                <!-- 原生 overflow 滚动，不用 n-scrollbar：n-scrollbar 内部的
                 `.n-scrollbar-container` 需要一层层把高度撑满，真机 WebView 上曾出现
                 “看得见行、拖不动”的状态。原生滚动容器只依赖 `.list-spin` 的确定高度，
                 滚轮 / 触摸 / 滚动条都由浏览器实现，跨 WebView 更稳。 -->
                <div class="list-scroll" role="list" aria-label="歌曲列表">
                  <div v-if="query && !filtered.length" class="list-empty">没有匹配「{{ query }}」的曲目</div>
                  <!--
                  一行 = 可点的曲目按钮 + 右侧「⋯」。两者必须是兄弟而不是嵌套：
                  按钮里套按钮是非法 HTML，浏览器会把内层提出来，点「⋯」会连带触发播放。
                  key 用 path 而不是 id —— 歌单里已失效的条目 id 全是 0，按 id 做 key 会撞。
                -->
                  <div
                    v-for="(t, i) in filtered"
                    :key="t.path"
                    class="queue-row"
                    :class="{ 'is-current': t.path === player.currentTrack?.path, 'is-missing': t.missing }"
                  >
                    <button
                      type="button"
                      class="queue-item"
                      :disabled="t.missing"
                      :aria-current="t.path === player.currentTrack?.path ? 'true' : undefined"
                      @click="player.playTrack(t)"
                    >
                      <span class="q-index">
                        <n-icon v-if="t.path === player.currentTrack?.path" :size="14">
                          <VolumeHighOutline v-if="player.playing" />
                          <PauseOutline v-else />
                        </n-icon>
                        <template v-else>{{ i + 1 }}</template>
                      </span>
                      <span class="q-main">
                        <img v-if="thumbs[t.id]" :src="thumbs[t.id]" alt="" class="q-thumb" />
                        <span v-else class="q-thumb q-thumb-fallback">
                          <n-icon :size="14"><MusicalNotesOutline /></n-icon>
                        </span>
                        <span class="q-text">
                          <span class="q-title">{{ trackTitleOf(t) }}</span>
                          <span class="q-sub">{{ t.missing ? MISSING_NOTE : trackArtistOf(t) }}</span>
                        </span>
                      </span>
                      <span class="q-artist">{{ t.missing ? MISSING_NOTE : trackArtistOf(t) }}</span>
                      <span class="q-album">{{ t.missing ? '—' : t.album || '—' }}</span>
                      <span class="q-dur">{{ formatDuration(t.duration_ms) }}</span>
                    </button>
                    <n-dropdown trigger="click" :options="rowMenuOptions" @select="(k: string) => onRowMenu(k, t)">
                      <n-button size="tiny" quaternary class="q-more" aria-label="更多操作">⋯</n-button>
                    </n-dropdown>
                  </div>
                  <div v-if="isLibraryQueue && hasMore()" class="list-more">
                    <n-button size="small" quaternary :loading="loadingMore" @click="loadMore">
                      加载更多（{{ items.length }} / {{ total }}）
                    </n-button>
                  </div>
                </div>
              </n-spin>
            </template>

            <!-- ── 歌单浏览：直接占满下方列表区，不再往侧边抽屉里塞 ── -->
            <div v-else-if="browseView === 'playlists'" class="browse-scroll">
              <div v-if="playlistsLoading && playlists.length === 0" class="list-empty">加载歌单…</div>
              <div v-else-if="!filteredPlaylists.length" class="list-empty">
                {{ query ? `没有匹配「${query}」的歌单` : '还没有歌单，点右上角「新建歌单」' }}
              </div>
              <div v-for="p in filteredPlaylists" :key="p.id" class="browse-row">
                <button type="button" class="browse-main" @click="selectPlaylist(p.id)">
                  <span class="browse-cover">
                    <n-icon :size="20"><MusicalNotesOutline /></n-icon>
                  </span>
                  <span class="browse-text">
                    <b>{{ p.name }}</b>
                  </span>
                  <span class="browse-count">{{ p.count }} 首</span>
                </button>
                <n-dropdown
                  trigger="click"
                  :options="playlistMenuOptions"
                  @select="(k: string) => onPlaylistMenu(k, p)"
                >
                  <n-button size="tiny" quaternary class="browse-more" aria-label="更多操作">⋯</n-button>
                </n-dropdown>
              </div>
            </div>

            <!-- ── 分组浏览：专辑 / 艺人 / 文件夹都直接铺在列表区 ── -->
            <div v-else class="browse-groups">
              <GroupBrowserPanel
                inline
                :active-key="activeGroup?.key ?? ''"
                :active-by="activeGroup?.by ?? groupBy"
                :initial-by="groupBy"
                :query="query"
                @open="onGroupOpen"
              />
            </div>
          </div>
        </Transition>
      </section>

      <!-- ═══ 手机迷你条（≤768 才显示） ═══ -->
      <div
        v-if="player.currentTrack && isMobile"
        class="mini-bar"
        @click="openStage"
        @pointerdown.passive="warmStageSoon"
      >
        <img
          v-if="player.coverUrl && !stageCoverBroken"
          :src="player.coverUrl"
          alt=""
          @error="stageCoverBroken = true"
        />
        <span v-else class="mini-cover-empty"
          ><n-icon :size="16"><MusicalNotesOutline /></n-icon
        ></span>
        <span class="mini-meta">
          <b>{{ player.displayTitle }}</b>
          <span>{{ player.displayArtist }}</span>
        </span>
        <button type="button" class="mini-btn" :title="player.playing ? '暂停' : '播放'" @click.stop="player.toggle()">
          <n-icon :size="18">
            <PauseOutline v-if="player.playing" />
            <PlayOutline v-else />
          </n-icon>
        </button>
        <button type="button" class="mini-btn" title="展开播放页" @click.stop="openStage">
          <n-icon :size="16"><ExpandOutline /></n-icon>
        </button>
      </div>

      <!-- ═══ 播放条 dock（桌面 / 平板） ═══
           播放条与队列浮层共用一个定位父级：队列从播放条上沿弹出，
           不再写死 `bottom: 86px`（播放条高度会随窄桌面两行排布变化）。 -->
      <div v-if="player.currentTrack && !isMobile" class="player-dock" @mouseenter="warmStageSoon">
        <AudioControlBar
          :lyrics-open="lyricsOpen"
          @open-queue="queueOpen = !queueOpen"
          @toggle-lyrics="onToggleLyrics"
          @open-stage="openStage"
        />
        <Transition name="queue-pop">
          <QueuePop
            v-if="queueOpen && !stageOpen"
            :tracks="filtered"
            :title="queueTitle"
            :count-text="queueCountText"
            :is-library-queue="isLibraryQueue"
            :playable-count="playableCount"
            :queue-length="queueTracks.length"
            :has-more="hasMore()"
            :loading-more="loadingMore"
            :current-path="player.currentTrack?.path"
            :playing="player.playing"
            @close="queueOpen = false"
            @play="player.playTrack"
            @play-all="playAllQueue"
            @clear="confirmClearQueue"
            @load-more="loadMore"
          />
        </Transition>
      </div>
    </template>

    <!-- 资料库层的队列遮罩；沉浸层有自己的一份（在 overlay 内部） -->
    <Transition name="queue-fade">
      <div v-if="queueOpen && !stageOpen" class="queue-backdrop" @click="queueOpen = false"></div>
    </Transition>

    <!-- ═══ 沉浸播放（B）：大封面 + 歌词 + 同一套控制条
         默认只铺满内容区（不盖顶栏/侧栏）；顶栏右侧的全屏钮切到 fixed 铺满视口。 ═══ -->
    <div
      v-if="stageWarm && player.currentTrack"
      class="immersive"
      :class="{ 'is-fullscreen': stageFullscreen, 'is-open': stageOpen }"
      role="dialog"
      :aria-modal="stageFullscreen ? 'true' : undefined"
      :aria-hidden="stageOpen ? undefined : 'true'"
      aria-label="正在播放"
      tabindex="-1"
    >
      <header class="im-top">
        <span class="im-status">{{ stageStatusText }}</span>
        <span class="grow"></span>
        <button
          type="button"
          class="im-fullscreen"
          :title="stageFullscreen ? '退出全屏（保留顶栏与侧栏）' : '全屏播放（覆盖顶栏与侧栏）'"
          :aria-label="stageFullscreen ? '退出全屏' : '全屏播放'"
          :aria-pressed="stageFullscreen"
          @click="toggleStageFullscreen"
        >
          <n-icon :size="16">
            <ContractOutline v-if="stageFullscreen" />
            <ExpandOutline v-else />
          </n-icon>
        </button>
      </header>
      <div class="im-body" :class="{ 'lyrics-hidden': !lyricsOpen }">
        <div class="im-track">
          <div class="im-cover" :class="{ 'is-empty': !player.coverUrl || stageCoverBroken }">
            <img
              v-if="player.coverUrl && !stageCoverBroken"
              :src="player.coverUrl"
              :alt="player.displayTitle"
              @error="stageCoverBroken = true"
            />
            <n-icon v-else :size="46"><MusicalNotesOutline /></n-icon>
          </div>
          <div class="im-track-meta">
            <b :title="player.displayTitle">{{ player.displayTitle }}</b>
            <span :title="player.displayArtist">{{ player.displayArtist }}</span>
          </div>
        </div>
        <div class="im-lyrics">
          <LyricsPanel
            :lyric-lines="player.lyricLines"
            :current-sec="player.positionSec"
            :active="stageOpen"
            large
            @seek="player.seek($event)"
          />
        </div>
      </div>
      <!-- 遮罩只在浮层内：不能让点击穿透到 im-body，也不能跨层影响资料库 -->
      <Transition name="queue-fade">
        <div
          v-if="queueOpen && stageOpen"
          class="queue-backdrop queue-backdrop--stage"
          @click="queueOpen = false"
        ></div>
      </Transition>
      <div class="immersive-dock">
        <AudioControlBar
          variant="immersive"
          :lyrics-open="lyricsOpen"
          @open-queue="queueOpen = !queueOpen"
          @toggle-lyrics="lyricsOpen = !lyricsOpen"
          @close="closeStage"
        />
        <Transition name="queue-pop">
          <QueuePop
            v-if="queueOpen && stageOpen"
            :tracks="filtered"
            :title="queueTitle"
            :count-text="queueCountText"
            :is-library-queue="isLibraryQueue"
            :playable-count="playableCount"
            :queue-length="queueTracks.length"
            :has-more="hasMore()"
            :loading-more="loadingMore"
            :current-path="player.currentTrack?.path"
            :playing="player.playing"
            :dark="appStore.darkMode"
            @close="queueOpen = false"
            @play="player.playTrack"
            @play-all="playAllQueue"
            @clear="confirmClearQueue"
            @load-more="loadMore"
          />
        </Transition>
      </div>
    </div>

    <!-- 歌单名输入（新建 / 重命名共用一个弹窗） -->
    <n-modal
      v-model:show="nameModalOpen"
      class="modal-fit"
      preset="card"
      :title="nameModalTitle"
      style="--modal-w: 420px"
      @after-leave="onNameModalClosed"
    >
      <n-input v-model:value="nameInput" placeholder="歌单名" :maxlength="64" show-count @keyup.enter="submitName" />
      <template #footer>
        <div class="pl-name-actions">
          <n-button size="small" quaternary @click="nameModalOpen = false">取消</n-button>
          <n-button size="small" type="primary" :disabled="!nameInput.trim()" @click="submitName">确定</n-button>
        </div>
      </template>
    </n-modal>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue';
import { useDialog, useMessage } from 'naive-ui';
import {
  ContractOutline,
  ExpandOutline,
  MusicalNotesOutline,
  PauseOutline,
  PlayOutline,
  RefreshOutline,
  VolumeHighOutline,
} from '@vicons/ionicons5';
import { WsDataTopic } from '@/api/contract';
import { useWebSocketStore } from '@/stores/websocket';
import { usePlayerStore } from '@/stores/player';
import { useAppStore } from '@/stores/app';
import { formatDuration, trackArtistOf, trackTitleOf } from './mediaShared';
import {
  addPlaylistItems,
  addResultText,
  createPlaylist,
  deletePlaylist,
  fetchPlaylistItems,
  fetchPlaylists,
  playlistErrorText,
  removePlaylistItems,
  renamePlaylist,
  type PlaylistEntry,
  type PlaylistTrack,
} from './playlistShared';
import { fetchGroupTracks, groupErrorText, type MediaGroupBy } from './mediaGroups';
import { excludePaths, excludeResultText, excludedErrorText } from './mediaExcludedShared';
import { useMediaLibrary } from './useMediaLibrary';
import { useIsMobile } from '@/composables/useIsMobile';
import AudioControlBar from './components/AudioControlBar.vue';
import GroupBrowserPanel from './components/GroupBrowserPanel.vue';
import LyricsPanel from './components/LyricsPanel.vue';
import QueuePop from './components/QueuePop.vue';
import MediaEmptyState from './components/MediaEmptyState.vue';
import MediaExcludedManager from './components/MediaExcludedManager.vue';
import MediaScanDirsSettings from './components/MediaScanDirsSettings.vue';
import MediaSortControl from './components/MediaSortControl.vue';
// 「重扫之后隔多久补拉」的共用节奏（视频页 / 图片页用同一份，见该文件头）
import { useRescanRefresh } from './components/mediaRescanRefresh';

const message = useMessage();
const dialog = useDialog();
const wsStore = useWebSocketStore();
/**
 * 全局播放器。**这一页不再持有任何播放状态**（2026-10-14）：
 * 当前曲目、队列、`<audio>`、音量、封面、歌词全在 store 里，
 * 这样切页/刷新都不会把歌掐掉。详见 `stores/player.ts` 的文件头。
 */
const player = usePlayerStore();
const appStore = useAppStore();
/** ≤768：用独立的迷你条 + 全屏播放页，而不是把桌面三列压窄 */
const isMobile = useIsMobile();
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
  loadThumbs,
  setSort,
} = useMediaLibrary('audio');

const query = ref('');

/** 歌词列是否展开。收起后队列/大封面独占整宽。 */
const lyricsOpen = ref(true);
/** 沉浸视图：主列从队列换成大封面。 */
const stageOpen = ref(false);
/**
 * 沉浸层是否覆盖顶栏/侧栏。
 *
 * 默认 **false**：沉浸层只铺满内容区（用户仍能看见自己在哪一页、能点侧栏切走）。
 * 顶栏右侧的全屏钮把它切成 `position: fixed; inset: 0`，覆盖整个视口。
 * 关闭沉浸时复位，下次进来默认仍是"内容区"模式。
 */
const stageFullscreen = ref(false);
/** 封面 URL 失效时退回占位图标，避免正在播放页出现浏览器破图 + alt 文本。 */
const stageCoverBroken = ref(false);
watch(
  () => player.coverUrl,
  () => {
    stageCoverBroken.value = false;
  }
);
/** 顶栏状态文案：能定位到队列时显示"第 n / m 首"。 */
const stageStatusText = computed(() => {
  const path = player.currentTrack?.path;
  const idx = path ? player.queue.findIndex((t) => t.path === path) : -1;
  return idx >= 0 ? `正在播放 · 第 ${idx + 1} / ${player.queue.length} 首` : '正在播放';
});
/**
 * 打开沉浸前的焦点。关闭时还回去，键盘用户不会掉到 body 上。
 * 只做「打开时聚焦层、关闭时还原」，不在这里实现焦点陷阱：默认档顶栏/侧栏
 * 本来就是可交互的，过度限制反而让用户不能用导航离开。
 */
let stageReturnFocus: HTMLElement | null = null;
/** 队列下拉（控制条上的「队列」按钮） */
const queueOpen = ref(false);
/**
 * 沉浸层是否已在后台挂载。
 *
 * 第一次打开沉浸页要现挂整套控制条/歌词/封面，低端机上会有一次明显掉帧。
 * 在选歌后（或鼠标移到播放条上时）用 requestIdleCallback 预挂载，打开时只切
 * v-show，不再走首次创建；隐藏时 display:none，不参与布局/绘制。
 */
const stageWarm = ref(false);
let stageWarmTimer: number | null = null;
type IdleCapableWindow = Window & {
  requestIdleCallback?: (cb: () => void, opts?: { timeout: number }) => number;
  cancelIdleCallback?: (id: number) => void;
};
function warmStageSoon() {
  if (stageWarm.value || !player.currentTrack || stageWarmTimer !== null) return;
  const run = () => {
    stageWarmTimer = null;
    if (player.currentTrack) stageWarm.value = true;
  };
  const idleWindow = window as IdleCapableWindow;
  stageWarmTimer = idleWindow.requestIdleCallback
    ? idleWindow.requestIdleCallback(run, { timeout: 1200 })
    : window.setTimeout(run, 800);
}
watch(
  () => player.currentTrack?.path,
  (path) => {
    if (path) warmStageSoon();
    else stageWarm.value = false;
  },
  { immediate: true }
);

/** 进入沉浸播放：清掉上次的浮层与全屏状态，默认停在内容区模式。 */
function openStage() {
  const active = document.activeElement;
  stageReturnFocus = active instanceof HTMLElement && active !== document.body ? active : null;
  queueOpen.value = false;
  stageFullscreen.value = false;
  stageWarm.value = true;
  stageOpen.value = true;
}

function toggleStageFullscreen() {
  stageFullscreen.value = !stageFullscreen.value;
}

/**
 * 沉浸打开时锁住壳层滚动。
 *
 * 沉浸层默认是 absolute（只盖内容区），页面若还能滚，用户一滑就会把整层带走；
 * 全屏模式虽然 fixed 不会跟着滚，但背后内容仍会被滚轮带着走。两种模式都锁。
 * 类挂在 <html> 上，样式见 main.css 的 `html.stage-locked`。
 */
watch(stageOpen, async (open) => {
  document.documentElement.classList.toggle('stage-locked', open);
  if (!open) {
    stageFullscreen.value = false;
    stageReturnFocus?.focus();
    stageReturnFocus = null;
    return;
  }
  await nextTick();
  (document.querySelector('.immersive') as HTMLElement | null)?.focus();
});
onUnmounted(() => document.documentElement.classList.remove('stage-locked'));
onUnmounted(() => {
  if (stageWarmTimer === null) return;
  (window as IdleCapableWindow).cancelIdleCallback?.(stageWarmTimer);
  window.clearTimeout(stageWarmTimer);
  stageWarmTimer = null;
});

/** 切到手机布局时没有桌面播放条可挂队列浮层；不在沉浸层里就收起它。 */
watch(isMobile, (mobile) => {
  if (mobile && !stageOpen.value) queueOpen.value = false;
});

/** 歌词按钮：资料库里点它 = 进沉浸播放（歌词只在那儿）；沉浸里点它 = 显隐歌词。 */
function onToggleLyrics() {
  if (!player.currentTrack) {
    message.info('先选一首歌再打开歌词');
    return;
  }
  if (!stageOpen.value) {
    lyricsOpen.value = true;
    openStage();
    return;
  }
  lyricsOpen.value = !lyricsOpen.value;
}

/** 退出沉浸：同时收起队列下拉与全屏状态，避免回到资料库后浮层还挂着。 */
function closeStage() {
  stageOpen.value = false;
  stageFullscreen.value = false;
  queueOpen.value = false;
}

/** Esc 逐层关闭：队列下拉 → 全屏（退回内容区）→ 沉浸播放。 */
function onAudioKeydown(e: KeyboardEvent) {
  if (e.key !== 'Escape') return;
  if (queueOpen.value) {
    queueOpen.value = false;
    return;
  }
  if (stageOpen.value) {
    if (stageFullscreen.value) {
      stageFullscreen.value = false;
      return;
    }
    closeStage();
  }
}

/**
 * 当前页签：全部 / 歌单 / 专辑 / 艺人 / 文件夹。
 *
 * 页签只决定**下方列表显示什么**，不再开侧边抽屉：
 *   · 全部 → 媒体库歌曲；
 *   · 歌单 → 歌单列表（选中后在同一列表区换成曲目）；
 *   · 专辑 / 艺人 / 文件夹 → 对应分组列表（选中后同一区域换成曲目）。
 */
type BrowseMode = 'all' | 'playlist' | MediaGroupBy;
const browseMode = ref<BrowseMode>('all');

/** 页头的分组维度页签（与 GroupBrowserPanel 的维度一一对应）。 */
const BROWSE_LABELS: Record<MediaGroupBy, string> = {
  album: '专辑',
  artist: '艺人',
  folder: '文件夹',
};
const GROUP_CHIPS: Array<{ by: MediaGroupBy; label: string }> = [
  { by: 'album', label: BROWSE_LABELS.album },
  { by: 'artist', label: BROWSE_LABELS.artist },
  { by: 'folder', label: BROWSE_LABELS.folder },
];

// ── 歌单（详见 playlistShared） ──

const playlists = ref<PlaylistEntry[]>([]);
const playlistsLoading = ref(false);
/** 当前选中的歌单 id；null = 全部音乐（队列 = 媒体库列表）。 */
const activePlaylistId = ref<string | null>(null);
const activePlaylistName = ref('');
const playlistTracks = ref<PlaylistTrack[]>([]);
const playlistTracksLoading = ref(false);
const playlistMissingCount = ref(0);
/** 序号守卫：loadPlaylistTracks 的两个入口（selectPlaylist / ws data_changed）可能并发 */
let plTrackSeq = 0;

/** 已失效条目在队列里那一行的副标题。 */
const MISSING_NOTE = '文件已不在媒体库';

// ── 分组浏览（详见 mediaGroups） ──

/**
 * 当前选中的分组。null = 队列不是按分组来的。
 *
 * 与 [activePlaylistId] **互斥**：同一时刻队列只能有一个来源。
 * 两者都不选 = 队列是媒体库的平铺列表。
 */
const activeGroup = ref<{ by: MediaGroupBy; key: string; title: string } | null>(null);

/** 当前分组浏览的维度：选中的分组优先，否则看页签。 */
const groupBy = computed<MediaGroupBy>(() => {
  if (activeGroup.value) return activeGroup.value.by;
  return browseMode.value === 'artist' || browseMode.value === 'folder' ? browseMode.value : 'album';
});

/** 下方列表当前该画什么：歌曲 / 歌单列表 / 分组列表。 */
const browseView = computed<'tracks' | 'playlists' | 'groups'>(() => {
  if (activePlaylistId.value || activeGroup.value) return 'tracks';
  if (browseMode.value === 'playlist') return 'playlists';
  if (browseMode.value === 'all') return 'tracks';
  return 'groups';
});

/** 切换视图的动画 key：分类页签、进入歌单/分组都换 key。 */
const viewKey = computed(() => {
  if (activePlaylistId.value) return `playlist:${activePlaylistId.value}`;
  if (activeGroup.value) return `group:${activeGroup.value.by}:${activeGroup.value.key}`;
  if (browseMode.value === 'playlist') return 'playlists';
  if (browseMode.value === 'all') return 'tracks:all';
  return `groups:${browseMode.value}`;
});

const showBrowseBack = computed(() => !!activePlaylistId.value || !!activeGroup.value);
const backLabel = computed(() => {
  if (activePlaylistId.value) return '歌单';
  if (activeGroup.value) return BROWSE_LABELS[activeGroup.value.by];
  return '';
});

/** 清掉当前选中的歌单/分组，回到同类列表（不改变当前页签）。 */
function clearActiveSource() {
  activePlaylistId.value = null;
  activeGroup.value = null;
  playlistTracks.value = [];
  groupTracks.value = [];
  activePlaylistName.value = '';
  playlistMissingCount.value = 0;
}

/** 切页签：清掉上一个来源，避免标题、列表和播放队列三者指向不同东西。 */
function setBrowseMode(mode: BrowseMode) {
  browseMode.value = mode;
  clearActiveSource();
}

/** 「全部」页签：回到媒体库平铺列表。 */
function showAll() {
  setBrowseMode('all');
}

/** 「歌单」页签：已进入某个歌单时再点一次 = 退回歌单列表。 */
function openPlaylistTab() {
  if (browseMode.value === 'playlist' && activePlaylistId.value) {
    clearActiveSource();
    return;
  }
  setBrowseMode('playlist');
}

/** 「专辑 / 艺人 / 文件夹」页签：已进入某个分组时再点一次 = 退回分组列表。 */
function openGroupTab(by: MediaGroupBy) {
  if (browseMode.value === by && activeGroup.value) {
    clearActiveSource();
    return;
  }
  setBrowseMode(by);
}

function backToBrowse() {
  clearActiveSource();
}

const groupTracks = ref<PlaylistTrack[]>([]);
const groupTracksLoading = ref(false);
/** 序号守卫：分组面板可以连点，先发后到的响应必须丢掉 */
let groupTrackSeq = 0;

/** 队列是否就是"媒体库平铺列表"。只有它是分页的，才谈得上"加载更多"。 */
const isLibraryQueue = computed(() => !activePlaylistId.value && !activeGroup.value);

/**
 * 排序控件的禁用说明。
 *
 * 禁用态必须**说得出理由**：一个灰掉的控件如果没有任何解释，用户只会以为页面坏了。
 * 这条文案同时也是"为什么只有媒体库能排序"的答案 —— 歌单与分组的顺序是用户 / core 排定的，
 * 排序对它们没有意义（见模板里那段的说明）。
 */
const sortHint = computed(() => {
  if (browseView.value !== 'tracks') return '排序只对歌曲列表生效：歌单 / 分组列表由 core 排定';
  return isLibraryQueue.value
    ? '排序（作用于媒体库列表）'
    : '排序只对「媒体库」列表生效：歌单与分组的顺序由用户 / core 排定，这里改不动它们';
});

const queueLoading = computed(() => {
  if (activeGroup.value) return groupTracksLoading.value;
  if (activePlaylistId.value) return playlistTracksLoading.value;
  return loading.value;
});
const queueTitle = computed(() => {
  if (activeGroup.value) return activeGroup.value.title || '分组';
  if (activePlaylistId.value) return activePlaylistName.value || '歌单';
  return '播放队列';
});
const queueCountText = computed(() => {
  if (activeGroup.value) return `${filtered.value.length} / ${groupTracks.value.length}`;
  if (activePlaylistId.value) {
    const base = `${filtered.value.length} / ${playlistTracks.value.length}`;
    return playlistMissingCount.value > 0 ? `${base} · ${playlistMissingCount.value} 首已失效` : base;
  }
  return `${filtered.value.length} / ${total.value}`;
});

/**
 * 当前列表显示的曲目。
 *
 * **读页面自己的来源数组，而不是 `player.queue`**（2026-10-23 修闪烁）：
 * 切歌单/分组时 `player.queue` 要等 `syncQueue` 推上去，慢一帧；那一帧里
 * 列表会先画上一份队列（常见表现是“闪一下全部歌曲”）。这里直接返回
 * `playlistTracks / groupTracks / items`，空就是空（配 n-spin 显示加载），
 * 不会拿旧队列顶一帧。播放器仍以 store 为真源，`syncQueue` 负责同步。
 */
const queueTracks = computed<PlaylistTrack[]>(() => {
  if (activeGroup.value) return groupTracks.value;
  if (activePlaylistId.value) return playlistTracks.value;
  return items.value;
});

const filtered = computed(() => {
  const q = query.value.trim().toLowerCase();
  if (!q) return queueTracks.value;
  return queueTracks.value.filter(
    (t) => trackTitleOf(t).toLowerCase().includes(q) || trackArtistOf(t).toLowerCase().includes(q)
  );
});

/**
 * 队列里真正**可播**的条数（`missing` 的条目点了也不会响）。
 *
 * 「播放全部 / 随机播放」的亮灭读它而不是 `queueTracks.length`：一个整份都已失效的歌单
 * 是可能存在的（文件被删/移走），此时按钮亮着而按下去什么都不发生，比灰着更让人困惑。
 */
const playableCount = computed(() => queueTracks.value.filter((t) => !t.missing).length);

const showEmpty = computed(
  () => !loading.value && (permissionDenied.value || failed.value || items.value.length === 0)
);

const headSub = computed(() => {
  if (permissionDenied.value) return '未授权';
  return total.value ? `共 ${total.value} 首` : '';
});

/** 搜索框提示随当前列表变化：歌曲列表搜歌名/艺人，歌单/分组列表搜名称。 */
const searchPlaceholder = computed(() => {
  if (browseView.value === 'playlists') return '搜索歌单';
  if (browseView.value === 'groups') return `搜索${BROWSE_LABELS[groupBy.value]}`;
  return '搜索歌名 / 艺人';
});

const filteredPlaylists = computed(() => {
  const q = query.value.trim().toLowerCase();
  if (!q) return playlists.value;
  return playlists.value.filter((p) => p.name.toLowerCase().includes(q));
});

/** 排序只对歌曲列表有意义；歌单/分组浏览时禁用。 */
const sortAvailable = computed(() => browseView.value === 'tracks' && isLibraryQueue.value);

const listTitle = computed(() => {
  if (browseView.value === 'playlists') return '歌单';
  if (browseView.value === 'groups') return BROWSE_LABELS[groupBy.value];
  return queueTitle.value;
});

const listCountText = computed(() => {
  if (browseView.value === 'playlists') return `${filteredPlaylists.value.length} 个`;
  if (browseView.value === 'groups') return '';
  return queueCountText.value;
});

const playlistMenuOptions = [
  { label: '重命名', key: 'rename' },
  { label: '删除歌单', key: 'remove' },
];
function onPlaylistMenu(key: string, entry: PlaylistEntry) {
  if (key === 'rename') openRenamePlaylist(entry);
  else if (key === 'remove') confirmRemovePlaylist(entry);
}

// ── 队列同步 ──

/**
 * 选中一个分组：把它整组的曲目换成当前队列。
 *
 * **不自动开始播放**，与 [selectPlaylist] 保持一致 —— 点分组表达的是"我要看这张专辑有什么"，
 * 播放意图由用户点某一首来表达。自动播放会把"只想看看"变成"被迫换歌"。
 */
function onGroupOpen(group: { key: string; title: string }, by: MediaGroupBy) {
  browseMode.value = by;
  activePlaylistId.value = null;
  activeGroup.value = { by, key: group.key, title: group.title };
  // 先清空：不清的话新分组的曲目到达之前，队列里还挂着上一个分组的内容，
  // 而标题已经换成了新的 —— 那几秒里列表和标题是矛盾的。
  groupTracks.value = [];
  // 主动切来源：立刻把空队列推给 store，别让上一份队列（全部歌曲/上一张专辑）
  // 在列表里多留一帧。当前正在听的那一首由 store 保留，不会被清掉。
  syncQueue(true);
  void loadGroupTracks(activeGroup.value);
}

async function loadGroupTracks(g: { by: MediaGroupBy; key: string; title: string }) {
  const seq = ++groupTrackSeq;
  groupTracksLoading.value = true;
  try {
    const tracks = await fetchGroupTracks(api, g.by, g.key);
    if (seq !== groupTrackSeq) return; // 先发后到，丢弃过期结果
    groupTracks.value = tracks;
    syncQueue(true);
    // 缩略图走与列表同一个并发闸门和同一份缓存（见 useMediaLibrary.loadThumbs）
    void loadThumbs(tracks);
  } catch (e: any) {
    if (seq !== groupTrackSeq) return;
    message.error(groupErrorText('加载分组曲目', e));
  } finally {
    if (seq === groupTrackSeq) groupTracksLoading.value = false;
  }
}

/**
 * 把当前来源的曲目推给全局 store。
 *
 * ## 为什么要那个"列表为空就跳过"的守卫
 * 页面刚挂载时三个列表都还是空的（`items` / `playlistTracks` / `groupTracks`），
 * 而 store 里的队列可能**正是刷新恢复出来的、顶栏还显示着的那一首**。
 * 无条件 `setQueue([])` 会把它擦掉 —— 表现就是"刷新后顶栏还在显示那首歌，
 * 一进音乐页就没了"。所以"新列表为空 且 已经有当前曲目"时跳过，等真正的数据到了再同步。
 *
 * 反过来，用户**主动**切来源（点歌单 / 点分组）时必须立刻同步，哪怕新列表还没到 ——
 * 那时"当前曲目掉出队列"（store 里 `orderPos = -1`）正是预期行为：
 * 新来源里确实没有"这首的下一首"。
 */
function syncQueue(force = false) {
  const hasCurrent = !!player.currentTrack;
  if (activeGroup.value) {
    if (force || groupTracks.value.length || !hasCurrent) {
      player.setQueue(groupTracks.value, {
        kind: 'group',
        by: activeGroup.value.by,
        key: activeGroup.value.key,
        title: activeGroup.value.title,
      });
    }
    return;
  }
  if (activePlaylistId.value) {
    if (force || playlistTracks.value.length || !hasCurrent) {
      player.setQueue(playlistTracks.value, {
        kind: 'playlist',
        id: activePlaylistId.value,
        name: activePlaylistName.value,
      });
    }
    return;
  }
  if (force || items.value.length || !hasCurrent) {
    player.setQueue(items.value, { kind: 'library' });
  }
}

/**
 * 队列同步的触发点。
 *
 * `deep` 刻意不开：这几个 ref 在加载时都是**整体替换**（`items.value = page`、
 * `playlistTracks.value = resp.items`），引用变了就够；开 deep 会在一次加载里
 * 触发十几次 setQueue，而每次都要重算随机播放的置换。
 */
watch([items, playlistTracks, groupTracks, activePlaylistId, activeGroup], () => syncQueue());

// ── 重新扫描：延迟补拉（2026-10-14 补）──

/**
 * core 侧扫描是异步的（`POST /api/media/rescan` 立刻返回，扫描在后台跑），
 * 所以 `@rescanned` 之后**不能立刻 reload** —— 那时扫描还没完，拉到的还是旧列表，
 * 用户会以为"重扫没生效"。
 *
 * 节奏（3s / 8s / 15s 三档补拉）**刻意不在这里自己定**：视频页与图片页用的是同一件事，
 * 各写一份就会出现三种节奏，而"隔多久补拉"没有任何页间差异 ——
 * 差异只在"刷新之后还要不要做别的"（视频页要顺带刷一次预热快照），那由调用方在回调里做。
 * 组件卸载时那一串定时器由它自己清掉（见 `mediaRescanRefresh` 的说明）。
 */
const { refreshAfterRescan } = useRescanRefresh();

function onRescanned() {
  refreshAfterRescan(reload);
}

// ── 队列编辑（2026-10-14 补）──
//
// 队列的真源在 `stores/player.ts`，这里只负责把"行上的意图"翻成 store 动作。
//
// ⚠ **索引一律按 path 反查 `player.queue`，绝不用 `filtered` 里的下标**：
// 搜索框一开，DOM 里的行号与队列下标就错位了，用错一次就会移除/移动另一首歌
// （而那是不可撤销的：队列是页面推给 store 的临时状态，不是歌单）。
//
// ⚠ 队列编辑只**在本次停留期间**有效：这一页会在来源列表变化时重新把队列推给 store
// （见 [syncQueue] 的 watch），所以点「加载更多」/ 换来源 / 刷新页面都会按来源的顺序重排，
// 手排的顺序与移除掉的条目会回来。app 侧靠 `AudioQueueOwner.userEdited` 标记"用户动过队列"
// 来避免这种重装；web 这一轮**刻意没做**那套标记 —— 它会牵动"刷新恢复"与"切来源不打断播放"
// 两条既有语义（标记什么时候清、切来源算不算用户编辑），那是独立的一轮工作。

/** 在**展示队列**里按路径反查下标。找不到返回 -1（这一行已经不在队列里了）。 */
function queueIndexOf(track: PlaylistTrack): number {
  return player.queue.findIndex((t) => t.path === track.path);
}

function onQueuePlayNext(track: PlaylistTrack) {
  player.playNext(track);
}

/**
 * 队列内上移 / 下移一位。
 *
 * 随机开着时 store 会顺手把它关掉（见 `moveInQueue` 的注释）：手排顺序与"打乱的播放顺序"
 * 是互斥的两种意图，不关的话用户排完听的时候还是按打乱的走。**提示必须在这里发** ——
 * store 不碰 UI，而"随机被关了"这件事不说出来就等于静默改了用户的设置。
 */
function onQueueMove(track: PlaylistTrack, dir: 1 | -1) {
  const idx = queueIndexOf(track);
  if (idx < 0) return;
  const to = idx + dir;
  if (to < 0 || to >= player.queue.length) {
    message.info(dir < 0 ? '已经在队首了' : '已经在队尾了');
    return;
  }
  const wasShuffle = player.shuffle;
  player.moveInQueue(idx, dir);
  if (wasShuffle) message.info('已关闭随机播放，按你排的顺序播');
}

function onQueueRemove(track: PlaylistTrack) {
  const idx = queueIndexOf(track);
  if (idx < 0) return;
  void player.removeFromQueue(idx);
}

/**
 * 「播放全部 / 随机播放」。
 *
 * 点歌单 / 分组卡片那条路（只换队列、不播）保持不变，这两颗按钮才是"开始听"的显式入口。
 * `random` 那一档会**打开**随机、另一档会关掉它：两者都是用户按这颗按钮时明确表达的意图
 * （"随机播放"这个词本身就是这个意思），不是我们替他改设置。
 */
function playAllQueue(random: boolean) {
  if (playableCount.value === 0) return;
  void player.playAll(random);
}

/**
 * 清空队列。
 *
 * 二次确认是必须的：这一列的「清空」挨着每一行的「⋯」，而它一次性抹掉整份队列，
 * 误点之后用户没有任何办法恢复（队列是页面推给 store 的临时状态，不是歌单）。
 * 文案要说清"不删文件、正在听的那一首也还在"：这两条正是用户最容易误解的地方。
 */
function confirmClearQueue() {
  dialog.warning({
    title: '清空播放队列',
    content: `清空这 ${queueTracks.value.length} 首？不会删除任何文件，正在听的那一首也仍然留在播放条上（播放会停下，按播放键可以接着听）。`,
    positiveText: '清空',
    negativeText: '取消',
    onPositiveClick: () => {
      player.clearQueue();
      message.success('播放队列已清空');
    },
  });
}

// ── 歌单：取数、选择与写操作 ──
//
// 歌单本体存在 core，这里只是镜像：写完一律重拉，不做本地乐观更新 ——
// 加歌的真实结果（跳过了几条、有没有撞上限）只有 core 知道。

async function reloadPlaylists() {
  playlistsLoading.value = true;
  try {
    playlists.value = await fetchPlaylists(api);
  } catch (e: any) {
    message.error(playlistErrorText('加载歌单', e));
  } finally {
    playlistsLoading.value = false;
  }
}

async function loadPlaylistTracks(id: string) {
  const seq = ++plTrackSeq;
  playlistTracksLoading.value = true;
  try {
    const resp = await fetchPlaylistItems(api, id);
    // useCancellableApi 取消在途请求时为 null，按"没结果"处理，不要清空已有列表
    if (!resp) return;
    if (seq !== plTrackSeq) return; // 先发后到，丢弃过期结果
    activePlaylistName.value = resp.name;
    playlistTracks.value = resp.items ?? [];
    playlistMissingCount.value = resp.missing_count ?? 0;
    syncQueue(true);
    // 缩略图走与列表同一个并发闸门和同一份缓存（见 useMediaLibrary.loadThumbs）
    void loadThumbs(playlistTracks.value);
  } catch (e: any) {
    if (seq !== plTrackSeq) return;
    message.error(playlistErrorText('加载歌单曲目', e));
  } finally {
    if (seq === plTrackSeq) playlistTracksLoading.value = false;
  }
}

/**
 * 切换队列来源（歌单 ↔ 媒体库）。
 *
 * **不碰播放器**：切歌单只是换"接下来从哪挑歌"，正在听的那一首继续放。
 * 代价是当前曲目可能不在新来源里（store 里 `orderPos = -1`），此时"上一首"会置灰、
 * "下一首"变成"从新来源的队首开始" —— 那是如实的：新来源里确实没有"这首的下一首"。
 *
 * 2026-10-14：队列真源搬到 store 之后，这里不再直接改队列内容，只改**来源**，
 * 由 [syncQueue] 的 watch 把结果推上去。
 */
function selectPlaylist(id: string | null) {
  activeGroup.value = null;
  activePlaylistId.value = id;
  if (!id) {
    playlistTracks.value = [];
    playlistMissingCount.value = 0;
    activePlaylistName.value = '';
    return;
  }
  browseMode.value = 'playlist';
  // 先清空：新歌单的曲目到达之前，队列里还挂着上一个歌单的内容而标题已经换了
  playlistTracks.value = [];
  // 主动切来源：立刻同步空队列，避免旧队列在列表里多闪一帧。
  syncQueue(true);
  void loadPlaylistTracks(id);
}

const ADD_PREFIX = 'add:';
const NEW_PLAYLIST_KEY = '__new__';

/**
 * 队列行「⋯」菜单：加入歌单（二级菜单列出全部歌单）+ 从本歌单移出 + 从音乐库移除。
 *
 * 「从音乐库移除」（2026-10-14 补）走的是 `POST /api/media/exclude` 的 `add` 动作，
 * 与「已从音乐库移除的歌曲」管理弹窗是同一份名单的两个方向。此前 web 只有"移回"没有
 * "移出"，名单只能由 app 写入 —— 那样这一整套界面在 web 上等于只读，用户看到一首不想听的
 * 歌只能去手机上处理。
 *
 * ⚠ 它**只动名单、永远不动文件**（core 那段 KDoc 写死了这条边界），而且可撤销
 * （在音乐页「设置 → 已从音乐库移除」里移回），所以确认文案必须说清这一点 ——
 * 用户看到"移除"两个字很容易理解成删文件。
 */
const rowMenuOptions = computed(() => {
  const children: Array<{ label: string; key: string }> = playlists.value.map((p) => ({
    label: `${p.name}（${p.count}）`,
    key: `${ADD_PREFIX}${p.id}`,
  }));
  // 一个歌单都没有时这一条就是唯一入口 —— 否则用户得先去建一个再回来
  children.push({ label: '＋ 新建歌单…', key: `${ADD_PREFIX}${NEW_PLAYLIST_KEY}` });
  /**
   * 队列编辑那一组（2026-10-14 补）。
   *
   * ⚠ 这份 options 是**所有行共用**的一份（`n-dropdown` 逐行渲染但选项集合只有一份），
   * 所以"首行不给上移 / 末行不给下移"这种逐行的条件在这里表达不了 ——
   * 与下面「从音乐库移除」那条是同一个限制。越界的情况由 [onQueueMove] 拦下并给一句提示，
   * 而不是把菜单项藏掉（藏掉需要逐行的 options，那是另一轮改动）。
   */
  const opts: Array<Record<string, unknown>> = [
    { label: '下一首播放', key: 'play-next' },
    { label: '上移', key: 'move-up' },
    { label: '下移', key: 'move-down' },
    // 「从播放队列移除」而不是「从本歌单移出」：两条都在这个菜单里，必须一眼看出区别
    // （前者只动这次的播放队列，后者动的是 core 里那份歌单）。
    { label: '从播放队列移除', key: 'remove-from-queue' },
    { type: 'divider', key: 'd-queue' },
  ];
  opts.push({ label: '加入歌单', key: 'add', children });
  if (activePlaylistId.value) opts.push({ label: '从本歌单移出', key: 'remove' });
  // 「从音乐库移除」**无条件列出**：这一份 options 是给所有行共用的一个 computed
  // （`n-dropdown` 逐行渲染但选项集合只有一份），在里面按"当前这一行"做条件判断
  // 是做不到的 —— 那需要一个逐行的 options。已失效的条目在 [confirmExclude] 里拦。
  opts.push({ label: '从音乐库移除', key: 'exclude' });
  return opts;
});

function onRowMenu(key: string, track: PlaylistTrack) {
  if (key === 'play-next') {
    onQueuePlayNext(track);
    return;
  }
  if (key === 'move-up') {
    onQueueMove(track, -1);
    return;
  }
  if (key === 'move-down') {
    onQueueMove(track, 1);
    return;
  }
  if (key === 'remove-from-queue') {
    onQueueRemove(track);
    return;
  }
  if (key === 'remove') {
    void doRemoveFromPlaylist(track);
    return;
  }
  if (key === 'exclude') {
    confirmExclude(track);
    return;
  }
  if (!key.startsWith(ADD_PREFIX)) return;
  const target = key.slice(ADD_PREFIX.length);
  if (target === NEW_PLAYLIST_KEY) {
    openNameModal('create-and-add', '', '');
    pendingAddPath.value = track.path;
    return;
  }
  void doAddToPlaylist(target, track.path);
}

/**
 * 「从音乐库移除」的二次确认 + 执行。
 *
 * 确认文案要同时说清三件事：不删文件、可以从哪里撤销、以及它会让这首歌从列表里消失
 * （用户点完看到的第一个现象就是"列表少了一首"，不说清楚会以为出了 bug）。
 */
function confirmExclude(track: PlaylistTrack) {
  // 已失效的条目：文件已经不在媒体库里，没有"从库里移除"这回事。
  // 拦在这里而不是在 rowMenuOptions 里，是因为那份 options 是所有行共用的一份（见那里的注释）。
  if (track.missing) {
    message.warning('这首歌的文件已不在媒体库，无需移除');
    return;
  }
  const name = trackTitleOf(track);
  dialog.warning({
    title: '从音乐库移除',
    content: `把「${name}」移出音乐库？文件不会被删除，只是从列表里隐藏；随时可以在本页「设置 → 已从音乐库移除的歌曲」里移回。`,
    positiveText: '移除',
    negativeText: '取消',
    onPositiveClick: () => doExclude(track),
  });
}

async function doExclude(track: PlaylistTrack) {
  try {
    const r = await excludePaths(api, 'add', [track.path]);
    if (!r) return; // 取消（组件卸载）：不弹任何 toast
    message.success(excludeResultText('add', r));
    // 被排除的路径**从 /list 的结果里直接消失**，所以必须重拉列表，不能只改本地数组 ——
    // 本地删一行的话，刷新后它又会回来（core 侧过滤才是唯一真相）。
    reload();
    if (activePlaylistId.value) await loadPlaylistTracks(activePlaylistId.value);
  } catch (e: any) {
    message.error(excludedErrorText('从音乐库移除', e));
  }
}

async function doAddToPlaylist(playlistId: string, path: string) {
  try {
    const r = await addPlaylistItems(api, playlistId, [path]);
    const name = playlists.value.find((p) => p.id === playlistId)?.name ?? '歌单';
    message.success(addResultText(name, r));
    await reloadPlaylists();
    if (activePlaylistId.value === playlistId) await loadPlaylistTracks(playlistId);
  } catch (e: any) {
    message.error(playlistErrorText('加入歌单', e));
  }
}

async function doRemoveFromPlaylist(track: PlaylistTrack) {
  const id = activePlaylistId.value;
  if (!id) return;
  try {
    const removed = await removePlaylistItems(api, id, [track.path]);
    message.success(`已移出 ${removed} 首`);
    await loadPlaylistTracks(id);
    await reloadPlaylists();
  } catch (e: any) {
    message.error(playlistErrorText('移出歌单', e));
  }
}

// ── 歌单名弹窗（新建 / 新建并加入 / 重命名 共用） ──

type NameMode = 'create' | 'create-and-add' | 'rename';

const nameModalOpen = ref(false);
const nameInput = ref('');
const nameMode = ref<NameMode>('create');
/** rename 的目标歌单 id。 */
const nameTargetId = ref('');
/** create-and-add 时要顺带加进去的曲目路径。 */
const pendingAddPath = ref('');

const nameModalTitle = computed(() => (nameMode.value === 'rename' ? '重命名歌单' : '新建歌单'));

function openNameModal(mode: NameMode, targetId: string, initial: string) {
  nameMode.value = mode;
  nameTargetId.value = targetId;
  nameInput.value = initial;
  nameModalOpen.value = true;
}

function openCreatePlaylist() {
  pendingAddPath.value = '';
  openNameModal('create', '', '');
}

function openRenamePlaylist(entry: PlaylistEntry) {
  pendingAddPath.value = '';
  openNameModal('rename', entry.id, entry.name);
}

/** 弹窗完全关闭后再清状态：关闭动画期间清掉会让标题/输入框闪一下。 */
function onNameModalClosed() {
  nameInput.value = '';
  nameTargetId.value = '';
  pendingAddPath.value = '';
}

async function submitName() {
  const name = nameInput.value.trim();
  if (!name) return;
  const mode = nameMode.value;
  const targetId = nameTargetId.value;
  const addPath = pendingAddPath.value;
  nameModalOpen.value = false;
  try {
    if (mode === 'rename') {
      await renamePlaylist(api, targetId, name);
      message.success(`已重命名为「${name}」`);
      await reloadPlaylists();
      if (activePlaylistId.value === targetId) activePlaylistName.value = name;
      return;
    }
    const id = await createPlaylist(api, name);
    if (mode === 'create-and-add' && id && addPath) {
      const r = await addPlaylistItems(api, id, [addPath]);
      message.success(addResultText(name, r));
    } else {
      message.success(`已创建歌单「${name}」`);
    }
    await reloadPlaylists();
  } catch (e: any) {
    message.error(playlistErrorText(mode === 'rename' ? '重命名' : '创建歌单', e));
  }
}

function confirmRemovePlaylist(entry: PlaylistEntry) {
  dialog.warning({
    title: '删除歌单',
    // 说清"不删文件"：歌单里存的是路径，用户很容易以为删歌单会连歌一起删
    content: `删除歌单「${entry.name}」？里面的 ${entry.count} 首歌不会被删除。`,
    positiveText: '删除',
    negativeText: '取消',
    onPositiveClick: async () => {
      try {
        await deletePlaylist(api, entry.id);
        message.success('歌单已删除');
        // 正在看的就是它：退回歌单列表，但**不停止播放**
        if (activePlaylistId.value === entry.id) selectPlaylist(null);
        await reloadPlaylists();
      } catch (e: any) {
        message.error(playlistErrorText('删除歌单', e));
      }
    },
  });
}

onMounted(() => document.addEventListener('keydown', onAudioKeydown));

onMounted(() => {
  // 队列来源要从 store 恢复：用户上次在放"某个歌单/某个分组"里的歌，刷新回到这一页
  // 不该被重置成"全部音乐" —— 否则顶栏那条还在放，列表却指向别处，上/下一首也会错。
  const src = player.source;
  if (src?.kind === 'playlist') {
    browseMode.value = 'playlist';
    activePlaylistId.value = src.id;
    activePlaylistName.value = src.name;
  } else if (src?.kind === 'group') {
    browseMode.value = src.by;
    activeGroup.value = { by: src.by, key: src.key, title: src.title };
  }
  reload();
  void reloadPlaylists();
  if (activePlaylistId.value) void loadPlaylistTracks(activePlaylistId.value);
  if (activeGroup.value) void loadGroupTracks(activeGroup.value);
});
// 2026-10-14：这里**不再有 onUnmounted 停播**。
// 原来卸载时会 `audioEl.pause()` 并把音量落盘 —— 那正是"切页就停播"的根因。
// 现在 `<audio>` 与音量都归全局 store（见 stores/player.ts），
// 页面卸载与停止播放是两件互不相干的事。

/**
 * 实时：另一端（app）改了歌单集合就立即对齐。
 *
 * 自己的写操作也会收到这一帧，于是会多重拉一次 —— 与 `views/tasks` 同一取舍：
 * 多一次 GET 换"两端永远一致"，比为自己的写操作去重更省心。
 */
const unsubPlaylists = wsStore.on('data_changed', (payload: any) => {
  if (String(payload?.changed ?? '') !== WsDataTopic.MEDIA_PLAYLISTS) return;
  void reloadPlaylists();
  const id = activePlaylistId.value;
  if (id) void loadPlaylistTracks(id);
});
onUnmounted(() => unsubPlaylists());
onUnmounted(() => document.removeEventListener('keydown', onAudioKeydown));
</script>

<style scoped>
.audio-page {
  position: relative;
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
  /* 列表必须被页面裁住：否则滚动容器的行会穿出页面、
     看起来像"播放条浮在列表中间"（2026-10-23 实机截图问题）。 */
  overflow: hidden;
}

/* ── 页头：只放"浏览"动作 ── */
.page-head {
  flex: none;
  display: flex;
  flex-direction: column;
  align-items: stretch;
  gap: 10px;
  padding: 14px 18px 8px;
}
.head-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
}
.head-text {
  display: flex;
  align-items: baseline;
  gap: 8px;
  min-width: 0;
}
.head-title {
  margin: 0;
  font-size: 26px;
  font-weight: 700;
  letter-spacing: -0.02em;
  color: var(--text-primary);
}
.head-sub {
  font-size: var(--font-sm);
  color: var(--text-muted);
  white-space: nowrap;
}
.head-tools {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  justify-content: flex-end;
  min-width: 0;
}
/* ── 分类页签：细下划线式，不再是一排灰色胶囊 ── */
.source-tabs {
  display: flex;
  align-items: center;
  gap: 2px;
  align-self: stretch;
  border-bottom: 1px solid var(--border-subtle);
  overflow-x: auto;
  scrollbar-width: none;
}
.source-tabs::-webkit-scrollbar {
  display: none;
}
.source-tabs button {
  position: relative;
  flex: none;
  border: 0;
  background: none;
  padding: 8px 14px;
  font: inherit;
  font-size: 13px;
  color: var(--text-secondary);
  cursor: pointer;
  transition: color 0.15s ease;
}
.source-tabs button:hover {
  color: var(--text-primary);
}
.source-tabs button.on {
  color: var(--text-primary);
  font-weight: 600;
}
.source-tabs button.on::after {
  content: '';
  position: absolute;
  left: 12px;
  right: 12px;
  bottom: -1px;
  height: 2px;
  border-radius: 2px;
  background: var(--accent-color);
}

/* 列表头的「‹ 歌单 / ‹ 专辑」返回上一层 */
.list-back {
  flex: none;
  border: 0;
  background: none;
  border-radius: var(--radius-sm);
  padding: 3px 7px 3px 3px;
  font: inherit;
  font-size: var(--font-sm);
  color: var(--text-secondary);
  cursor: pointer;
}
.list-back:hover {
  color: var(--text-primary);
  background: var(--surface-hover);
}

/* 歌单 / 分组浏览列表：占满列表区，原生滚动 */
.browse-scroll,
.browse-groups {
  flex: 1;
  min-height: 0;
  min-width: 0;
  width: 100%;
  /* 宽屏上不要把 40px 的封面拉到 1200px 另一端：内容限宽，
     计数和「⋯」仍在右侧，但不会隔着一整屏空白。 */
  max-width: 920px;
}
.browse-scroll {
  overflow-y: auto;
  overflow-x: hidden;
  overscroll-behavior: contain;
  -webkit-overflow-scrolling: touch;
  touch-action: pan-y;
  padding-bottom: 8px;
}
.browse-groups {
  display: flex;
  flex-direction: column;
}
.browse-row {
  display: flex;
  align-items: center;
  border-radius: var(--radius-md);
  /* 长列表只渲染视口内的行，避免切分类时一次性布局所有歌单/分组 */
}
.browse-row:hover {
  background: var(--surface-hover);
}
.browse-main {
  flex: 1;
  min-width: 0;
  display: flex;
  align-items: center;
  gap: 12px;
  border: 0;
  background: none;
  border-radius: var(--radius-md);
  padding: 10px 8px;
  font: inherit;
  color: inherit;
  text-align: left;
  cursor: pointer;
}
.browse-cover {
  flex: none;
  width: 44px;
  height: 44px;
  display: grid;
  place-items: center;
  border-radius: var(--radius-sm);
  background: var(--surface-elevated);
  color: var(--text-muted);
}
.browse-text {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.browse-text b {
  font-size: 13px;
  color: var(--text-primary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.browse-count {
  flex: none;
  margin-left: 12px;
  font-size: 11px;
  color: var(--text-muted);
  font-variant-numeric: tabular-nums;
}
.browse-more {
  margin-right: 4px;
}

.head-search {
  width: 190px;
}
.sort-wrap {
  display: inline-flex;
}
.sort-wrap.is-disabled {
  opacity: 0.5;
}

/* ── 主体列表 ── */
.library-view {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  padding: 0 10px;
  overflow: hidden;
}
.list-head {
  flex: none;
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 8px 8px;
}
/* 歌单/分组浏览时列表限宽 920px，标题与「新建歌单」也跟列表同宽 */
.list-head.is-browse {
  width: 100%;
  max-width: 920px;
}
.list-title {
  min-width: 0;
  font-size: var(--font-md);
  font-weight: 600;
  color: var(--text-primary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.list-count {
  flex: none;
  font-size: var(--font-xs);
  color: var(--text-muted);
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
}
.list-head .grow {
  flex: 1;
}
.list-actions {
  display: flex;
  align-items: center;
  gap: 4px;
  flex: none;
}
.list-spin {
  flex: 1;
  min-height: 0;
  overflow: hidden;
}
/* n-spin 会把内容包一层 `.n-spin-content`，它默认按内容高度长。
   列表有 60 条时这层会到 2900px+，滚动容器因此拿不到可滚动高度（实测
   `.list-spin` 327px、`.n-spin-content` 2934px），列表被裁掉、播放条位置也失去约束。
   撑满后原生滚动容器才真正在列表内部滚。与 PlaylistPanel 的 `.pl-spin` 同一手法。 */
.list-spin :deep(.n-spin-content) {
  height: 100%;
}
.list-scroll {
  height: 100%;
  overflow-y: auto;
  overflow-x: hidden;
  /* 滚到边界不要带动壳层；触摸滚动明确交给浏览器处理 */
  overscroll-behavior: contain;
  -webkit-overflow-scrolling: touch;
  touch-action: pan-y;
  /* 固定预留滚动条宽度：表头据此多留 6px，列才不会因为滚动条出现/消失而横跳 */
  scrollbar-gutter: stable;
}
.track-list {
  padding: 4px 0 8px;
}
.table-head {
  display: grid;
  /* 外层与 `.queue-row` 同构：1fr 内容列 + 34px「更多」列。
     表头单元格放进 `.th-main`，它的栅格与 `.queue-item` 逐列一致 —— 之前表头直接
     用 6 列、行是 5 列套 34px，艺人/专辑/时长会左偏 5–10px（2026-10-23 实测）。
     右侧 14px = 8px 基础内边距 + 6px 原生滚动条预留；列表内容宽度同样少了 6px。 */
  grid-template-columns: minmax(0, 1fr) 34px;
  align-items: center;
  padding: 0 14px 0 8px;
  border-bottom: 1px solid var(--border-subtle);
  font-size: 11px;
  color: var(--text-muted);
}
.th-main {
  display: grid;
  grid-template-columns: 24px minmax(0, 1.6fr) minmax(0, 1fr) minmax(0, 0.8fr) 52px;
  gap: 10px;
  align-items: center;
  padding: 6px 0;
}
.table-head .th-dur {
  text-align: right;
}
.queue-row {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 34px;
  align-items: center;
  border-radius: var(--radius-md);
}
.queue-row:hover {
  background: var(--surface-hover);
}
.queue-row.is-current {
  background: var(--accent-color-light);
}
.queue-row.is-missing {
  opacity: 0.55;
}
.queue-item {
  display: grid;
  grid-template-columns: 24px minmax(0, 1.6fr) minmax(0, 1fr) minmax(0, 0.8fr) 52px;
  gap: 10px;
  align-items: center;
  min-width: 0;
  border: 0;
  background: none;
  padding: 6px 8px;
  text-align: left;
  cursor: pointer;
  color: inherit;
  font: inherit;
}
.queue-item:disabled {
  cursor: default;
}
.q-index {
  text-align: right;
  font-size: var(--font-xs);
  color: var(--text-muted);
  font-variant-numeric: tabular-nums;
}
.is-current .q-index {
  color: var(--accent-color);
}
.q-main {
  display: flex;
  align-items: center;
  gap: 9px;
  min-width: 0;
}
.q-thumb {
  width: 36px;
  height: 36px;
  border-radius: var(--radius-sm);
  object-fit: cover;
  flex: none;
}
.q-thumb-fallback {
  display: grid;
  place-items: center;
  background: var(--surface-elevated);
  color: var(--text-muted);
}
.q-text {
  min-width: 0;
}
.q-title {
  display: block;
  font-size: 13px;
  color: var(--text-primary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.q-sub {
  display: none;
  font-size: 11px;
  color: var(--text-muted);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.q-artist,
.q-album {
  font-size: 12px;
  color: var(--text-muted);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.q-dur {
  text-align: right;
  font-size: var(--font-xs);
  color: var(--text-muted);
  font-variant-numeric: tabular-nums;
}
.q-more {
  margin-right: 4px;
}
.list-more {
  display: flex;
  justify-content: center;
  padding: 10px 0 16px;
}

/* ── 队列下拉 ── */
.queue-backdrop {
  position: absolute;
  inset: 0;
  z-index: 50;
}
/* 资料库播放条 dock：队列浮层相对它定位，永远从播放条上沿弹出。
   dock 抬到遮罩之上，队列按钮在浮层打开时仍可点第二次关闭；
   浮层自身 z-index 56 只在 dock 的 stacking context 内比较。 */
.player-dock,
.immersive-dock {
  position: relative;
  z-index: 56;
  flex: 0 0 auto;
  min-width: 0;
}
/* 沉浸层打开时，资料库 dock 必须退回普通层级；否则 z-index:56 会压过
   z-index:40 的沉浸层，挡住沉浸层里的队列按钮。 */
.audio-page.stage-open .player-dock {
  z-index: auto;
}
.player-dock .queue-pop,
.immersive-dock .queue-pop {
  position: absolute;
  right: 16px;
  bottom: calc(100% + 8px);
  transform-origin: bottom right;
}
/* 队列开合：浮层从播放条上沿轻微抬起，遮罩同步淡入淡出 */
.queue-pop-enter-active,
.queue-pop-leave-active {
  transition:
    opacity 0.16s ease,
    transform 0.16s ease;
}
.queue-pop-enter-from,
.queue-pop-leave-to {
  opacity: 0;
  transform: translateY(6px) scale(0.985);
}
.queue-fade-enter-active,
.queue-fade-leave-active {
  transition: opacity 0.16s ease;
}
.queue-fade-enter-from,
.queue-fade-leave-to {
  opacity: 0;
}

/* 沉浸层里的队列浮层换深色由 QueuePop 的 `dark` prop 负责，不在页面级覆盖子组件内部。 */

.list-empty {
  padding: 28px 12px;
  text-align: center;
  font-size: var(--font-sm);
  color: var(--text-muted);
}

/* ── 手机迷你条（桌面隐藏） ── */
.mini-bar {
  display: none;
}

/* ── 沉浸播放 ── */
.immersive {
  position: absolute;
  inset: 0;
  z-index: 40;
  display: flex;
  flex-direction: column;
  min-height: 0;
  overflow: hidden;
  /* 滚到边界不要把背景页面带着滚 */
  overscroll-behavior: contain;
  /* 沉浸层跟随全局明暗：默认白底，html.dark 才切深色。
     局部变量让封面/歌词/控制条使用同一套浅色或深色场景。
     ui-baseline-allow: 沉浸层明暗两套固定场景色，不跟随皮肤令牌 */
  --brand-ink: #1a1a1a;
  --brand-glow-soft: rgba(0, 0, 0, 0.06);
  --brand-logo-bg: #16181d;
  --brand-panel-1: #ffffff;
  --brand-panel-2: #f5f7fa;
  --stage-bg: #ffffff;
  --stage-ink: #16181d;
  --stage-dim: #7b8492;
  --stage-top-dim: #8b95a5;
  --stage-soft: rgba(0, 0, 0, 0.05);
  --stage-soft-hover: rgba(0, 0, 0, 0.09);
  --stage-button-ink: #3f4756;
  --stage-cover-a: #e7edf3;
  --stage-cover-b: #f7f9fb;
  --stage-cover-ink: #8b95a5;
  --stage-cover-shadow: rgba(0, 0, 0, 0.18);
  --stage-lyric: #8a94a3;
  --stage-lyric-hover: #5e6878;
  color: var(--brand-ink);
  background: var(--stage-bg);
  /* 沉浸层背景保持纯色：满屏渐变/图片首次栅格化会带来 60–300ms 的首开长任务；
     视觉层次交给封面阴影、歌词与控制条，稳定优先。 */
  /* 固定满屏层：把布局/绘制限制在本层内，避免打开时触发整页重排重绘 */
  contain: layout paint;
  /* 预挂载时仍参与布局（visibility:hidden），但不绘制、不挡点击。
     这样打开沉浸页只切 visibility/opacity，不再对整层做首次布局。 */
  visibility: hidden;
  opacity: 0;
  pointer-events: none;
  transition:
    opacity 0.18s ease,
    visibility 0s linear 0.18s;
}
.immersive.is-open {
  visibility: visible;
  opacity: 1;
  pointer-events: auto;
  transition: opacity 0.18s ease;
}
/* 深色模式沿用原来的深色场景；浅色模式不加载这些值。 */
.dark .immersive {
  /* ui-baseline-allow: 沉浸层深色场景固定色 */
  --brand-ink: #e9edf3;
  --brand-glow-soft: rgba(255, 255, 255, 0.12);
  --brand-logo-bg: #ffffff;
  --brand-panel-1: #14171d;
  --brand-panel-2: #1d2129;
  --stage-bg: #0b0d11;
  --stage-ink: #e9edf3;
  --stage-dim: #8d98a8;
  --stage-top-dim: #9aa4b4;
  --stage-soft: rgba(255, 255, 255, 0.06);
  --stage-soft-hover: rgba(255, 255, 255, 0.12);
  --stage-button-ink: #cbd5e1;
  --stage-cover-a: #2b4a63;
  --stage-cover-b: #0e151d;
  --stage-cover-ink: #7f8b9c;
  --stage-cover-shadow: rgba(0, 0, 0, 0.85);
  --stage-lyric: #7b8798;
  --stage-lyric-hover: #a9b4c4;
}
.im-top,
.im-body {
  position: relative;
  z-index: 1;
}
/* 全屏档：由顶栏右侧的全屏钮切换。fixed 后覆盖顶栏与侧栏；
   默认档保持 absolute（只铺满内容区），用户仍能看见并操作壳层导航。 */
.immersive.is-fullscreen {
  position: fixed;
  z-index: 100;
}
/* 打开时程序化聚焦到容器，不画整屏焦点框；真正的按钮仍保留 focus-visible。 */
.immersive:focus {
  outline: none;
}
.im-top {
  flex: none;
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 14px 20px;
  font-size: 12px;
  color: var(--stage-top-dim);
}
.im-status {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--stage-dim);
  font-size: 11.5px;
}
.im-top .grow {
  flex: 1;
}
.im-top button {
  flex: none;
  border: 0;
  background: var(--stage-soft);
  border-radius: 8px;
  padding: 5px 9px;
  color: var(--stage-button-ink);
  cursor: pointer;
  font-size: 12px;
  /* 中文按钮在 390px 顶栏里曾被挤成两行（40px 宽 × 46px 高）；
     按钮不参与收缩，宽度不够时由标题先让位。 */
  white-space: nowrap;
}
.im-top button:hover {
  background: var(--stage-soft-hover);
}
.im-body {
  flex: 1;
  min-height: 0;
  display: grid;
  grid-template-columns: minmax(0, 340px) minmax(0, 1fr);
  /* 单行吃满内容区高度：歌词面板必须是「有确定高度」的滚动容器，
     否则 .lyrics-panel 的 height:100% 会退化成内容高度（5000px），
     由外层 overflow:hidden 裁掉，既不能滑也不会自动居中。 */
  grid-template-rows: minmax(0, 1fr);
  gap: 40px;
  padding: 4px 40px 10px;
  overflow: hidden;
}
.im-track {
  align-self: center;
  display: flex;
  flex-direction: column;
  gap: 14px;
  min-width: 0;
  width: 100%;
}
.im-track-meta {
  min-width: 0;
  text-align: center;
}
.im-track-meta b {
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--stage-ink);
  font-size: 18px;
  font-weight: 600;
  line-height: 1.35;
}
.im-track-meta span {
  display: block;
  margin-top: 5px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--stage-dim);
  font-size: 12.5px;
  line-height: 1.4;
}
.im-cover {
  align-self: center;
  aspect-ratio: 1;
  width: 100%;
  max-height: 100%;
  border-radius: 18px;
  overflow: hidden;
  /* ui-baseline-allow: 沉浸层固定深色场景里的封面占位 */
  background: linear-gradient(135deg, var(--stage-cover-a), var(--stage-cover-b));
  /* 封面阴影收小：40/80px 的投影在打开瞬间要整块栅格化，性价比不高 */
  box-shadow: 0 24px 48px -32px var(--stage-cover-shadow);
  display: grid;
  place-items: center;
  color: var(--stage-cover-ink);
}
.im-cover img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}
.im-lyrics {
  align-self: stretch;
  height: 100%;
  max-height: 100%;
  min-height: 0;
  min-width: 0;
  display: flex;
  overflow: hidden;
}
.im-lyrics :deep(.lyrics-panel) {
  flex: 1;
  min-height: 0;
}
/* 歌词面板内部用的是全局 text 令牌；沉浸层不跟随主题，必须在这里覆盖成
   中性浅色，否则浅色主题下“当前行”会变成深灰、几乎看不见。 */
.im-lyrics :deep(.lyrics-line) {
  color: var(--stage-lyric);
}
.im-lyrics :deep(.lyrics-line:hover) {
  color: var(--stage-lyric-hover);
}
.im-lyrics :deep(.lyrics-line.current) {
  color: var(--stage-ink);
}
.im-lyrics :deep(.lyrics-line.plain) {
  color: var(--stage-lyric-hover);
}
.im-lyrics :deep(.empty-title) {
  color: var(--stage-button-ink);
}
.im-lyrics :deep(.empty-hint) {
  color: var(--stage-dim);
}
.lyrics-hidden .im-lyrics {
  display: none;
}
.lyrics-hidden .im-body {
  grid-template-columns: 1fr;
  justify-items: center;
}
.lyrics-hidden .im-track {
  max-width: 340px;
}
.lyrics-hidden .im-cover {
  max-width: 340px;
}
/* 分类 / 歌单 / 分组切换 */
.view-host {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
}
.view-swap-enter-active,
.view-swap-leave-active {
  transition:
    opacity 0.14s ease,
    transform 0.14s ease;
}
.view-swap-enter-from {
  opacity: 0;
  transform: translateY(6px);
}
.view-swap-leave-to {
  opacity: 0;
  transform: translateY(-4px);
}
@media (prefers-reduced-motion: reduce) {
  .immersive,
  .view-swap-enter-active,
  .view-swap-leave-active,
  .queue-pop-enter-active,
  .queue-pop-leave-active,
  .queue-fade-enter-active,
  .queue-fade-leave-active {
    transition: none;
  }
}

/* ── 手机（≤768） ── */
@media (max-width: 768px) {
  .table-head {
    display: none;
  }
  .queue-item {
    /* q-index / q-artist / q-album 在下面被隐藏；列数必须同步收成 2 列。
       保留 44px 第一列会让 q-main 被塞进 44px，歌名宽度变成 0（2026-10-23 修）。 */
    grid-template-columns: minmax(0, 1fr) auto;
    gap: 10px;
    padding: 8px 4px;
  }
  .q-index,
  .q-artist,
  .q-album {
    display: none;
  }
  .q-sub {
    display: block;
    margin-top: 1px;
  }
  .q-thumb {
    width: 44px;
    height: 44px;
  }
  .page-head {
    align-items: stretch;
    gap: 6px;
    padding: 8px 12px 4px;
  }
  .head-sub {
    display: none;
  }
  .head-actions {
    gap: 6px;
  }
  .head-search {
    order: 10;
    flex: 1 1 100%;
    width: 100%;
  }
  .source-tabs {
    gap: 0;
  }
  .source-tabs button {
    padding: 7px 10px;
    font-size: 12px;
  }
  .library-view {
    padding: 0 6px;
  }
  /* 只有迷你播放条真的在屏幕上时才给列表留底部空间：
     54px 条高 + 10px 底距 + 10px 间隙 = 74px；无播放条时列表直接铺到底。 */
  .audio-page.has-mini-bar .library-view {
    padding-bottom: 74px;
  }
  .list-head {
    flex-wrap: wrap;
    row-gap: 4px;
    padding: 4px 6px 6px;
  }
  /* 手机：标题/计数占第一行，播放全部/随机/清空整体落到第二行，
     否则返回键 + 长歌单名 + 失效计数 + 三个动作会挤成三行。 */
  .list-head .grow {
    display: none;
  }
  .list-title {
    flex: 1 1 auto;
  }
  .list-actions {
    flex: 1 1 100%;
    justify-content: flex-end;
  }
  .q-thumb {
    width: 44px;
    height: 44px;
  }
  .q-title {
    font-size: 13px;
  }
  .desktop-bar {
    display: none;
  }
  .mini-bar {
    position: absolute;
    left: 8px;
    right: 8px;
    bottom: 10px;
    z-index: 20;
    display: flex;
    align-items: center;
    gap: 10px;
    padding: 7px 10px;
    border: 1px solid var(--border-subtle);
    border-radius: var(--radius-lg);
    background: var(--card-bg);
    box-shadow: var(--shadow-color-strong);
    cursor: pointer;
  }
  .mini-bar img,
  .mini-cover-empty {
    width: 38px;
    height: 38px;
    border-radius: var(--radius-sm);
    object-fit: cover;
    flex: none;
  }
  .mini-cover-empty {
    display: grid;
    place-items: center;
    background: var(--surface-elevated);
    color: var(--text-muted);
  }
  .mini-meta {
    flex: 1;
    min-width: 0;
  }
  .mini-meta b {
    display: block;
    font-size: 12.5px;
    color: var(--text-primary);
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }
  .mini-meta span {
    display: block;
    font-size: 11px;
    color: var(--text-muted);
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }
  .mini-btn {
    border: 0;
    background: none;
    width: 32px;
    height: 32px;
    border-radius: 50%;
    display: grid;
    place-items: center;
    color: var(--text-primary);
    cursor: pointer;
  }
  .mini-btn:hover {
    background: var(--surface-hover);
  }
  /* 队列浮层的宽度交给 QueuePop 自己；这里只把它钉在 dock 上沿，
     并覆盖桌面档的 right:16px，让手机左右各留 8px。 */
  .player-dock .queue-pop,
  .immersive-dock .queue-pop {
    left: 8px;
    right: 8px;
    width: auto;
  }
  .im-body {
    grid-template-columns: 1fr;
    grid-template-rows: auto minmax(0, 1fr);
    gap: 10px;
    padding: 0 16px 6px;
    justify-items: center;
  }
  .im-cover {
    width: min(62vw, 240px);
    max-width: 240px;
  }
  .im-lyrics {
    width: 100%;
    max-height: 34vh;
  }
  .im-top {
    gap: 4px;
    padding: 10px 12px;
  }
  .im-top button {
    padding: 5px 7px;
    font-size: 11.5px;
  }
}

@media (prefers-reduced-motion: reduce) {
  .queue-pop {
    transition: none;
  }
}
/* ── 响应式列（放在最后：覆盖上面的基础栅格，保证顺序稳定） ──
   必须排除 ≤768：手机档把 q-index/q-artist/q-album 隐藏后只用两列，
   这条四列规则若继续命中手机，会把 q-main 重新塞回 24px 第一列。 */
@media (min-width: 769px) and (max-width: 1024px) {
  .th-main {
    grid-template-columns: 24px minmax(0, 1.6fr) minmax(0, 1fr) 52px;
  }
  .th-album,
  .q-album {
    display: none;
  }
  .queue-item {
    grid-template-columns: 24px minmax(0, 1.6fr) minmax(0, 1fr) 52px;
  }
}
</style>
