<!--
  音乐页。

  ## 版式
  · **资料库（桌面 / 平板）**：页头只保留浏览动作（分类页签 / 搜索 / 排序 / 刷新 / 设置），
    下方为当前队列列表，底部为两行式播放控制条。
    「最近添加 / 接下来播放」入口不保留：它占用 280px+ 高度，在矮屏上会把列表压缩到 0 行，
    且快速入口与列表本身重复。
  · **沉浸播放**：点击封面 / 歌词 / 展开钮进入覆盖层：大封面 + 同步歌词 + 同一套控制条。
    默认只铺满内容区，顶栏右侧的全屏钮可覆盖顶栏与侧栏。
    队列不常驻右栏，改为控制条上的「队列」下拉面板（桌面 340px，移动端全宽）。
  · **移动端（≤768）**：列表 + 底部迷你条；点击迷你条进入同一个沉浸覆盖层（全屏），
    控制条自动切换为移动端排布。桌面播放条以 `v-if` 隐藏，避免通过 CSS 强行挤压。

  ## 分类（歌单 / 专辑 / 艺人 / 文件夹）
  顶部页签只切换**下方列表区的内容**，不使用侧边抽屉：
  歌单页签显示歌单列表，分组页签显示对应维度的分组列表；选中一项后在同一区域
  换为曲目，列表头的 `‹ xxx` 返回上一层。GroupBrowserPanel 以 `inline` 模式复用。

  ## 播放状态
  全部位于 `stores/player.ts`：本页只把队列推给 store、把行上的操作转换为 store 动作。
  `<audio>` 宿主位于常驻的 `GlobalAudioPlayer`，顶栏迷你条与本页**共用同一数据源**。
  `AudioControlBar.vue` 同时用于底部播放条与沉浸层（variant 区分明暗与布局）。

  ## 歌词
  沉浸层右侧（移动端在封面下方）为 `LyricsPanel`；资料库中点击「歌词」直接进入沉浸层，
  不保留常驻歌词列：该列既挤压列表宽度，又与"正在播放"信息重复。
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
      <!-- 分类页签使用公共 n-tabs（type=line），与应用 / 下载 / 短信页保持一致；
           避免自写 <button role="tab"> 并另行实现下划线、悬停与选中态。 -->
      <n-tabs :value="browseMode" type="line" size="small" class="source-tabs" @update:value="onBrowseTab">
        <n-tab name="all">全部</n-tab>
        <n-tab name="playlist">歌单</n-tab>
        <n-tab v-for="g in GROUP_CHIPS" :key="g.by" :name="g.by">{{ g.label }}</n-tab>
      </n-tabs>
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
              <!-- 返回上一层：使用公共 n-button（size="small"，与 #/monitor 一致），
                   与页面其它按钮共用同一套样式与尺寸。 -->
              <n-button
                v-if="showBrowseBack"
                size="small"
                quaternary
                class="list-back"
                :title="`返回${backLabel}列表`"
                @click="backToBrowse"
              >
                <template #icon>
                  <n-icon :size="14"><ChevronBackOutline /></n-icon>
                </template>
                {{ backLabel }}
              </n-button>
              <span class="list-title">{{ listTitle }}</span>
              <span class="list-count">{{ listCountText }}</span>
              <span class="grow"></span>
              <n-radio-group
                v-if="browseView !== 'tracks'"
                :value="browseLayout"
                size="small"
                aria-label="显示方式"
                @update:value="setBrowseLayout"
              >
                <n-radio-button value="list">列表</n-radio-button>
                <n-radio-button value="grid">网格</n-radio-button>
              </n-radio-group>
              <n-button v-if="browseView === 'playlists'" size="small" @click="openCreatePlaylist"> 新建歌单 </n-button>
              <template v-else-if="browseView === 'tracks'">
                <span class="list-actions">
                  <n-button
                    v-if="!isLibraryQueue"
                    size="small"
                    :disabled="playableCount === 0"
                    title="按列表顺序从第一首开始播放（会关闭随机播放）"
                    @click="playAllQueue(false)"
                  >
                    播放全部
                  </n-button>
                  <n-button
                    v-if="!isLibraryQueue"
                    size="small"
                    :disabled="playableCount === 0"
                    title="打乱顺序播放这一份队列里的全部曲目（会开启随机播放）"
                    @click="playAllQueue(true)"
                  >
                    随机播放
                  </n-button>
                  <n-button
                    v-if="queueTracks.length"
                    size="small"
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
                <!-- 使用原生 overflow 滚动，不使用 n-scrollbar：后者内部的
                 `.n-scrollbar-container` 需要逐层撑满高度，在 WebView 上会出现
                 行可见但无法拖动的情况。原生滚动容器只依赖 `.list-spin` 的确定高度，
                 滚轮 / 触摸 / 滚动条均由浏览器实现，跨 WebView 更稳定。 -->
                <div class="list-scroll" role="list" aria-label="歌曲列表">
                  <div v-if="query && !filtered.length" class="list-empty">没有匹配「{{ query }}」的曲目</div>
                  <!--
                  一行 = 可点击的曲目按钮 + 右侧「⋯」。两者必须是兄弟节点而非嵌套：
                  按钮嵌套按钮属于非法 HTML，浏览器会把内层节点提出，点击「⋯」会一并触发播放。
                  key 使用 path 而非 id —— 歌单中已失效的条目 id 全为 0，按 id 做 key 会冲突。
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
                      <n-button size="small" class="q-more" aria-label="更多操作">⋯</n-button>
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

            <!-- ── 歌单浏览：占满下方列表区，不使用侧边抽屉 ── -->
            <template v-else-if="browseView === 'playlists'">
              <div v-if="browseLayout === 'list'" class="browse-table-head" aria-hidden="true">
                <div class="browse-table-main">
                  <span class="bh-name">名称</span>
                  <span class="bh-count">歌曲数</span>
                </div>
                <span class="bh-more"></span>
              </div>
              <div class="browse-scroll" :class="{ 'is-grid': browseLayout === 'grid' }">
                <div v-if="playlistsLoading && playlists.length === 0" class="list-empty">加载歌单…</div>
                <div v-else-if="!filteredPlaylists.length" class="list-empty">
                  {{ query ? `没有匹配「${query}」的歌单` : '还没有歌单，点右上角「新建歌单」' }}
                </div>
                <div v-for="p in filteredPlaylists" :key="p.id" class="browse-row">
                  <button type="button" class="browse-main" @click="selectPlaylist(p.id)">
                    <span class="browse-cover">
                      <!-- 网格模式下封面为整块正方形，20px 图标会显得空旷；列表模式保持 20px -->
                      <n-icon :size="browseLayout === 'grid' ? 36 : 20"><MusicalNotesOutline /></n-icon>
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
                    <n-button size="small" class="browse-more" aria-label="更多操作">⋯</n-button>
                  </n-dropdown>
                </div>
              </div>
            </template>

            <!-- ── 分组浏览：专辑 / 艺人 / 文件夹都直接铺在列表区 ── -->
            <div v-else class="browse-groups">
              <GroupBrowserPanel
                inline
                :layout="browseLayout"
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
        <button
          type="button"
          class="mini-btn mini-btn--play"
          :title="player.playing ? '暂停' : '播放'"
          @click.stop="player.toggle()"
        >
          <n-icon :size="18">
            <PauseOutline v-if="player.playing" />
            <PlayOutline v-else />
          </n-icon>
        </button>
        <button type="button" class="mini-btn" title="播放队列" aria-label="播放队列" @click.stop="openStageWithQueue">
          <n-icon :size="16"><ListOutline /></n-icon>
        </button>
        <button type="button" class="mini-btn" title="展开播放页" @click.stop="openStage">
          <n-icon :size="16"><ExpandOutline /></n-icon>
        </button>
      </div>

      <!-- ═══ 播放条 dock（桌面 / 平板） ═══
           播放条与队列浮层共用同一个定位父级：队列从播放条上沿弹出，
           不使用固定的 `bottom: 86px`（播放条高度会随窄桌面两行排布变化）。 -->
      <div v-if="player.currentTrack && !isMobile" class="player-dock" @mouseenter="warmStageSoon">
        <AudioControlBar :lyrics-open="lyricsOpen" @open-queue="toggleQueue" @open-stage="openStage" />
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

    <!-- 资料库层的队列遮罩；沉浸层内另有一份（位于 overlay 内部） -->
    <Transition name="queue-fade">
      <div v-if="queueOpen && !stageOpen" class="queue-backdrop" @click="queueOpen = false"></div>
    </Transition>

    <!-- ═══ 沉浸播放：大封面 + 歌词 + 同一套控制条
         默认只铺满内容区（不覆盖顶栏 / 侧栏）；顶栏右侧的全屏钮切换为 fixed 铺满视口。 ═══ -->
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
        <Transition name="lyrics-panel-fade">
          <div v-if="lyricsOpen" class="im-lyrics">
            <LyricsPanel
              :lyric-lines="player.lyricLines"
              :current-sec="player.positionSec"
              :active="stageOpen"
              large
              @seek="player.seek($event)"
            />
          </div>
        </Transition>
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
          @open-queue="toggleQueue"
          @toggle-lyrics="toggleLyrics"
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
import { computed, onMounted, onUnmounted, ref, watch } from 'vue';
import { useDialog, useMessage } from 'naive-ui';
import {
  ChevronBackOutline,
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
// 「重扫后延迟补拉」的共用节奏（视频页 / 图片页共用同一实现，见该文件头）
import { useRescanRefresh } from './components/mediaRescanRefresh';
import { useAudioStage } from './useAudioStage';

const message = useMessage();
const dialog = useDialog();
const wsStore = useWebSocketStore();
/**
 * 全局播放器。**本页不持有任何播放状态**：
 * 当前曲目、队列、`<audio>`、音量、封面与歌词均在 store 中，
 * 因此切换页面或刷新不会中断播放。详见 `stores/player.ts` 的文件头。
 */
const player = usePlayerStore();
const appStore = useAppStore();
/** ≤768：使用独立的迷你条与全屏播放页，而非压缩桌面三列布局 */
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

/**
 * 歌单 / 专辑 / 艺人 / 文件夹的显示方式。
 *
 * 记忆保存在 localStorage 而非随路由：这是设备级的浏览偏好，不应每次进入页面重新选择。
 * 读取失败（隐私模式）时退回默认布局。
 */
type BrowseLayout = 'list' | 'grid';
const BROWSE_LAYOUT_KEY = 'ufi.media.browseLayout';
const browseLayout = ref<BrowseLayout>(
  (() => {
    try {
      // 默认使用网格布局：仅当明确存过 'list' 时使用列表，
      // 未存过或读取 localStorage 失败均按网格处理。
      return localStorage.getItem(BROWSE_LAYOUT_KEY) === 'list' ? 'list' : 'grid';
    } catch {
      return 'grid';
    }
  })()
);
/** naive 的 radio-group 回调给的是 `string | number | boolean`，这里做一次收敛。 */
function setBrowseLayout(next: string | number | boolean): void {
  const value: BrowseLayout = next === 'grid' ? 'grid' : 'list';
  browseLayout.value = value;
  try {
    localStorage.setItem(BROWSE_LAYOUT_KEY, value);
  } catch {
    /* 无法写入时仅在本页生效 */
  }
}

/** 歌词列是否展开。收起后队列与大封面独占整宽。 */
const lyricsOpen = ref(true);
/** 沉浸视图：主列从队列换成大封面。 */
/**
 * 沉浸播放层（stage）：状态与行为集中于 `useAudioStage.ts`。
 *
 * 边界：全屏、滚动锁、焦点还原、空闲预挂载、Esc 逐层关闭均由该文件负责。
 * `queueOpen` / `lyricsOpen` 保留在本文件 —— 它们同时被资料库侧的播放条使用，
 * 由本文件传入，避免 composable 反向依赖视图。
 */
const queueOpen = ref(false);

/**
 * 队列与歌词是互斥的两个浮层：同时打开时队列会覆盖歌词栏（窄屏尤为明显），
 * 且两者关闭方式不同（队列点击遮罩、歌词点击按钮），容易误判为无法关闭。
 * 因此打开一个即收起另一个 —— 单向关闭，不记忆"关闭队列后恢复歌词"的状态：
 * 该恢复逻辑在切换曲目或退出沉浸时会与 `openStage()` 的复位冲突。
 */
function toggleQueue(): void {
  queueOpen.value = !queueOpen.value;
  if (queueOpen.value) lyricsOpen.value = false;
}

function toggleLyrics(): void {
  lyricsOpen.value = !lyricsOpen.value;
  if (lyricsOpen.value) queueOpen.value = false;
}
const {
  stageOpen,
  stageFullscreen,
  stageCoverBroken,
  stageStatusText,
  stageWarm,
  warmStageSoon,
  openStage,
  toggleStageFullscreen,
  closeStage,
  onAudioKeydown,
} = useAudioStage({ player, isMobile, queueOpen });

/**
 * 移动端迷你条上的「队列」：先进入沉浸层（队列浮层挂在沉浸控制条上），再打开队列。
 *
 * `openStage()` 内部会将 `queueOpen` 置为 false（避免从资料库切入时保留上一次的浮层），
 * 因此此处在同一 tick 内将其覆盖回 true —— 两个 ref 赋值均为同步，最终状态确定，
 * 无需借助 nextTick 调整时序。
 */
function openStageWithQueue(): void {
  openStage();
  queueOpen.value = true;
  // 与 toggleQueue 同一条规则：进沉浸并开队列时，先收起歌词，避免队列压住歌词栏
  lyricsOpen.value = false;
}

/**
 * 当前页签：全部 / 歌单 / 专辑 / 艺人 / 文件夹。
 *
 * 页签只决定**下方列表显示的内容**，不使用侧边抽屉：
 *   · 全部 → 媒体库歌曲；
 *   · 歌单 → 歌单列表（选中后在同一列表区切换为曲目）；
 *   · 专辑 / 艺人 / 文件夹 → 对应分组列表（选中后同一区域切换为曲目）。
 */
type BrowseMode = 'all' | 'playlist' | MediaGroupBy;
const browseMode = ref<BrowseMode>('all');

/** 页头的分组维度页签（与 GroupBrowserPanel 的维度一一对应）。 */
const BROWSE_LABELS: Record<MediaGroupBy, string> = {
  album: '专辑',
  artist: '艺人',
  folder: '文件夹',
};
/** n-tabs 的选中回调：映射到三个入口（全部 / 歌单 / 分组） */
function onBrowseTab(name: string): void {
  if (name === 'all') {
    showAll();
    return;
  }
  if (name === 'playlist') {
    openPlaylistTab();
    return;
  }
  openGroupTab(name as MediaGroupBy);
}

const GROUP_CHIPS: Array<{ by: MediaGroupBy; label: string }> = [
  { by: 'album', label: BROWSE_LABELS.album },
  { by: 'artist', label: BROWSE_LABELS.artist },
  { by: 'folder', label: BROWSE_LABELS.folder },
];

// ── 歌单（详见 playlistShared） ──

const playlists = ref<PlaylistEntry[]>([]);
const playlistsLoading = ref(false);
/** 当前选中的歌单 id；null 表示全部音乐（队列即媒体库列表）。 */
const activePlaylistId = ref<string | null>(null);
const activePlaylistName = ref('');
const playlistTracks = ref<PlaylistTrack[]>([]);
const playlistTracksLoading = ref(false);
const playlistMissingCount = ref(0);
/** 序号守卫：loadPlaylistTracks 的两个调用入口（selectPlaylist / ws data_changed）可能并发 */
let plTrackSeq = 0;

/** 已失效条目在队列中的副标题文案。 */
const MISSING_NOTE = '文件已不在媒体库';

// ── 分组浏览（详见 mediaGroups） ──

/**
 * 当前选中的分组。null 表示队列不按分组组织。
 *
 * 与 [activePlaylistId] **互斥**：同一时刻队列只能有一个来源。
 * 两者均为 null 时，队列为媒体库的平铺列表。
 */
const activeGroup = ref<{ by: MediaGroupBy; key: string; title: string } | null>(null);

/** 当前分组浏览的维度：优先取选中的分组，否则取页签。 */
const groupBy = computed<MediaGroupBy>(() => {
  if (activeGroup.value) return activeGroup.value.by;
  return browseMode.value === 'artist' || browseMode.value === 'folder' ? browseMode.value : 'album';
});

/** 下方列表当前渲染的内容：歌曲 / 歌单列表 / 分组列表。 */
const browseView = computed<'tracks' | 'playlists' | 'groups'>(() => {
  if (activePlaylistId.value || activeGroup.value) return 'tracks';
  if (browseMode.value === 'playlist') return 'playlists';
  if (browseMode.value === 'all') return 'tracks';
  return 'groups';
});

/** 视图切换动画的 key：切换分类页签、进入歌单或分组时均更换。 */
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

/** 清除当前选中的歌单 / 分组，回到同类列表（不改变当前页签）。 */
function clearActiveSource() {
  activePlaylistId.value = null;
  activeGroup.value = null;
  playlistTracks.value = [];
  groupTracks.value = [];
  activePlaylistName.value = '';
  playlistMissingCount.value = 0;
}

/** 切换页签：清除上一个来源，避免标题、列表与播放队列指向不同数据源。 */
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
/** 序号守卫：分组面板可连续点击，先发后到的响应必须丢弃 */
let groupTrackSeq = 0;

/** 队列是否为"媒体库平铺列表"。仅该来源分页，因此只有它存在"加载更多"。 */
const isLibraryQueue = computed(() => !activePlaylistId.value && !activeGroup.value);

/**
 * 排序控件的禁用说明。
 *
 * 禁用态必须**给出理由**：无说明的置灰控件会被理解为页面故障。
 * 该文案同时说明"仅媒体库列表可排序"的原因 —— 歌单与分组的顺序由用户 / core 确定，
 * 排序对它们没有意义。
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
 * **读取页面自身的来源数组，而非 `player.queue`**：切换歌单 / 分组时，
 * `player.queue` 需等待 `syncQueue` 推送，存在一帧延迟；该帧内列表会先绘制上一份队列
 * （表现为短暂显示全部歌曲）。此处直接返回 `playlistTracks / groupTracks / items`，
 * 为空即渲染空态（配合 n-spin 显示加载），不使用旧队列填充。
 * 播放器仍以 store 为数据源，由 `syncQueue` 负责同步。
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
 * 队列中真正**可播放**的条数（`missing` 条目播放时不会出声）。
 *
 * 「播放全部 / 随机播放」的可用状态依据该值而非 `queueTracks.length`：
 * 整份条目均已失效的歌单是可能存在的（文件被删除或移动），此时按钮可用却无任何效果，
 * 比置灰更易造成困惑。
 */
const playableCount = computed(() => queueTracks.value.filter((t) => !t.missing).length);

const showEmpty = computed(() => {
  if (loading.value) return false;
  // 无权限 / 读取失败：任何页签都是全屏占位（这时下面没数据可画）
  if (permissionDenied.value || failed.value) return true;
  // 歌单页签拥有独立空态（「还没有歌单，点右上角…」）：曲库为空时仍需可进入、
  // 可新建，不应被「没有音频文件」整页占位阻挡。
  if (browseMode.value === 'playlist') return false;
  return items.value.length === 0;
});

const headSub = computed(() => {
  if (permissionDenied.value) return '未授权';
  return total.value ? `共 ${total.value} 首` : '';
});

/** 搜索框提示随当前列表变化：歌曲列表搜索歌名 / 艺人，歌单与分组列表搜索名称。 */
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

/** 排序仅对歌曲列表有意义；歌单与分组浏览时禁用。 */
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
 * 选中一个分组：以该分组的全部曲目作为当前队列。
 *
 * **不自动开始播放**，与 [selectPlaylist] 保持一致 —— 选中分组表达的是查看意图，
 * 播放意图由点击具体曲目表达。自动播放会使仅想查看的操作用户被迫切换曲目。
 */
function onGroupOpen(group: { key: string; title: string }, by: MediaGroupBy) {
  browseMode.value = by;
  activePlaylistId.value = null;
  activeGroup.value = { by, key: group.key, title: group.title };
  // 先清空：否则新分组曲目返回之前，队列仍保留上一个分组的内容，而标题已更新，
  // 该时间段内列表与标题不一致。
  groupTracks.value = [];
  // 主动切换来源：立即把空队列推给 store，避免上一份队列（全部歌曲 / 上一张专辑）
  // 在列表中多显示一帧。当前正在播放的曲目由 store 保留，不会被清除。
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
 * ## 空列表守卫的作用
 * 页面刚挂载时三个列表均为空（`items` / `playlistTracks` / `groupTracks`），
 * 而 store 中的队列可能**正是刷新恢复出来的、顶栏仍在显示的那一首**。
 * 无条件调用 `setQueue([])` 会清除该队列，表现为刷新后顶栏仍显示该曲目，
 * 但进入音乐页后即丢失。因此"新列表为空且已存在当前曲目"时跳过，待数据到达后再同步。
 *
 * 反之，用户**主动**切换来源（点击歌单 / 分组）时必须立即同步，即使新列表尚未返回：
 * 此时"当前曲目脱离队列"（store 中 `orderPos = -1`）属于预期行为，
 * 新来源中确实不存在该曲目的下一首。
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
 * 不启用 `deep`：这些 ref 在加载时均为**整体替换**（`items.value = page`、
 * `playlistTracks.value = resp.items`），引用变化即可触发；启用 deep 会在一次加载中
 * 触发十余次 setQueue，而每次都需要重算随机播放的置换。
 */
watch([items, playlistTracks, groupTracks, activePlaylistId, activeGroup], () => syncQueue());

// ── 重新扫描：延迟补拉 ──

/**
 * core 侧扫描是异步的（`POST /api/media/rescan` 立即返回，扫描在后台执行），
 * 因此 `@rescanned` 之后**不能立即 reload** —— 此时扫描尚未完成，取回的仍是旧列表，
 * 会被理解为"重扫未生效"。
 *
 * 补拉节奏（3s / 8s / 15s 三档）**不在此处自行定义**：视频页与图片页使用同一逻辑，
 * 各自实现会产生三种节奏，而"补拉间隔"在各页面之间没有差异 ——
 * 差异仅在于"刷新之后是否还需执行其它操作"（视频页需顺带刷新预热快照），
 * 该部分由调用方在回调中完成。组件卸载时相关定时器由该模块自行清除
 * （见 `mediaRescanRefresh` 的说明）。
 */
const { refreshAfterRescan } = useRescanRefresh();

function onRescanned() {
  refreshAfterRescan(reload);
}

// ── 队列编辑 ──
//
// 队列的数据源在 `stores/player.ts`，此处只把行上的操作转换为 store 动作。
//
// ⚠ **索引一律按 path 反查 `player.queue`，不得使用 `filtered` 中的下标**：
// 启用搜索后，DOM 行号与队列下标会错位，误用会移除 / 移动其它曲目，
// 且该操作不可撤销（队列是页面推给 store 的临时状态，不是歌单）。
//
// ⚠ 队列编辑仅在**本次停留期间**有效：来源列表变化时本页会重新把队列推给 store
// （见 [syncQueue] 的 watch），因此「加载更多」、切换来源、刷新页面都会按来源顺序重排，
// 手动调整的顺序与被移除的条目会重新出现。app 侧以 `AudioQueueOwner.userEdited`
// 标记"用户修改过队列"以避免该重装；web 未实现该标记 —— 它会牵动"刷新恢复"与
// "切换来源不打断播放"两条既有语义（标记的清除时机、切换来源是否计入用户编辑），
// 需独立处理。

/** 在**展示队列**中按路径反查下标。找不到时返回 -1（该行已不在队列中）。 */
function queueIndexOf(track: PlaylistTrack): number {
  return player.queue.findIndex((t) => t.path === track.path);
}

function onQueuePlayNext(track: PlaylistTrack) {
  player.playNext(track);
}

/**
 * 队列内上移 / 下移一位。
 *
 * 随机播放开启时 store 会同时将其关闭（见 `moveInQueue` 的注释）：手动顺序与
 * 打乱的播放顺序是互斥的两种意图，否则用户排序后播放仍按打乱顺序进行。
 * **提示必须在此处发出** —— store 不处理 UI，而"随机播放已关闭"若不提示，
 * 等同于静默修改了用户设置。
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
 * 点击歌单 / 分组卡片（仅切换队列、不播放）的行为保持不变；这两个按钮才是开始播放的
 * 显式入口。`random` 分支**开启**随机播放，另一分支将其关闭：两者均为用户按下按钮时
 * 明确表达的意图（"随机播放"的字面含义即为此），不构成对设置的擅自修改。
 */
function playAllQueue(random: boolean) {
  if (playableCount.value === 0) return;
  void player.playAll(random);
}

/**
 * 清空队列。
 *
 * 必须二次确认：该按钮与每行的「⋯」相邻，且会一次性清除整份队列，
 * 误操作后无法恢复（队列是页面推给 store 的临时状态，不是歌单）。
 * 文案需明确"不删除文件、正在播放的曲目仍保留"，这两点最容易被误解。
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
// 歌单数据存储于 core，此处仅为镜像：写操作后一律重新拉取，不做本地乐观更新 ——
// 加歌的真实结果（跳过条数、是否触达上限）只有 core 能够确定。

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
    // useCancellableApi 取消在途请求时返回 null，按"无结果"处理，不清空已有列表
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
 * **不影响播放器**：切换歌单仅改变后续曲目的来源，正在播放的曲目继续播放。
 * 代价是当前曲目可能不在新来源中（store 中 `orderPos = -1`），此时"上一首"置灰、
 * "下一首"变为从新来源的队首开始 —— 该行为符合实际：新来源中确实不存在该曲目的下一首。
 *
 * 队列数据源位于 store，此处只修改**来源**，由 [syncQueue] 的 watch 推送结果。
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
  // 先清空：新歌单曲目返回之前，队列仍保留上一个歌单的内容，而标题已更新
  playlistTracks.value = [];
  // 主动切换来源：立即同步空队列，避免旧队列在列表中多显示一帧。
  syncQueue(true);
  void loadPlaylistTracks(id);
}

const ADD_PREFIX = 'add:';
const NEW_PLAYLIST_KEY = '__new__';

/**
 * 队列行「⋯」菜单：加入歌单（二级菜单列出全部歌单）+ 从本歌单移出 + 从音乐库移除。
 *
 * 「从音乐库移除」调用 `POST /api/media/exclude` 的 `add` 动作，与「已从音乐库移除的歌曲」
 * 管理弹窗操作同一份名单的两个方向。若名单只能由 app 写入，该界面在 web 上将退化为只读。
 *
 * ⚠ 该操作**只修改名单、不修改文件**（core 侧的 KDoc 已明确该边界），且可撤销
 * （在本页「设置 → 已从音乐库移除」中移回）。确认文案必须说明这一点 ——
 * "移除"容易被理解为删除文件。
 */
const rowMenuOptions = computed(() => {
  const children: Array<{ label: string; key: string }> = playlists.value.map((p) => ({
    label: `${p.name}（${p.count}）`,
    key: `${ADD_PREFIX}${p.id}`,
  }));
  // 无任何歌单时该项为唯一入口，避免用户需先新建歌单再返回操作
  children.push({ label: '＋ 新建歌单…', key: `${ADD_PREFIX}${NEW_PLAYLIST_KEY}` });
  /**
   * 队列编辑项。
   *
   * ⚠ 该 options 由**所有行共用**（`n-dropdown` 逐行渲染，但选项集合只有一份），
   * 因此无法表达"首行禁用上移 / 末行禁用下移"这类逐行条件 ——
   * 与下方「从音乐库移除」受同一限制。越界情况由 [onQueueMove] 拦截并提示，
   * 而非隐藏菜单项（隐藏需要逐行的 options）。
   */
  const opts: Array<Record<string, unknown>> = [
    { label: '下一首播放', key: 'play-next' },
    { label: '上移', key: 'move-up' },
    { label: '下移', key: 'move-down' },
    // 使用「从播放队列移除」而非「从本歌单移出」：两者同在该菜单中，需可明确区分
    // （前者仅修改本次播放队列，后者修改 core 中的歌单）。
    { label: '从播放队列移除', key: 'remove-from-queue' },
    { type: 'divider', key: 'd-queue' },
  ];
  opts.push({ label: '加入歌单', key: 'add', children });
  if (activePlaylistId.value) opts.push({ label: '从本歌单移出', key: 'remove' });
  // 「从音乐库移除」**无条件列出**：该 options 是所有行共用的 computed
  // （`n-dropdown` 逐行渲染但选项集合只有一份），无法按当前行做条件判断，
  // 那需要逐行生成的 options。已失效条目在 [confirmExclude] 中拦截。
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
 * 「从音乐库移除」的二次确认与执行。
 *
 * 确认文案需同时说明三件事：不删除文件、可在何处撤销、以及该曲目将从列表中消失
 * （操作后的直接现象是"列表少了一首"，缺少说明会被判断为缺陷）。
 */
function confirmExclude(track: PlaylistTrack) {
  // 已失效条目：文件已不在媒体库中，不存在"从库中移除"的操作。
  // 在此处拦截而非在 rowMenuOptions 中，因为该 options 由所有行共用（见相关注释）。
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
    if (!r) return; // 取消（组件卸载）：不显示任何提示
    message.success(excludeResultText('add', r));
    // 被排除的路径**会直接从 /list 结果中消失**，因此必须重新拉取列表，不能仅修改本地数组 ——
    // 本地删除的行在刷新后会重新出现（core 侧过滤才是唯一数据来源）。
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
    // 明确"不删除文件"：歌单中保存的是路径，容易被理解为删除歌单会一并删除曲目
    content: `删除歌单「${entry.name}」？里面的 ${entry.count} 首歌不会被删除。`,
    positiveText: '删除',
    negativeText: '取消',
    onPositiveClick: async () => {
      try {
        await deletePlaylist(api, entry.id);
        message.success('歌单已删除');
        // 当前正在查看的歌单：退回歌单列表，但**不停止播放**
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
  // 队列来源需从 store 恢复：上次播放的是某个歌单 / 分组中的曲目时，刷新回到本页
  // 不应重置为"全部音乐" —— 否则顶栏仍在播放，列表却指向其它来源，上 / 下一首随之错误。
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
// 此处**不注册 onUnmounted 停播**：页面卸载时暂停 `audioEl` 并持久化音量会导致
// 切换页面即中断播放。`<audio>` 与音量均归全局 store（见 stores/player.ts），
// 页面卸载与停止播放是两件互不相关的事。

/**
 * 实时同步：另一端（app）修改歌单集合后立即对齐。
 *
 * 自身写操作也会收到该事件，因而会重复拉取一次 —— 与 `views/tasks` 采用同一取舍：
 * 以一次额外的 GET 换取两端一致，比为自身写操作去重的实现成本更低。
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
  /* 列表必须由页面裁剪：否则滚动容器的行会溢出到页面之外，
     呈现为"播放条浮在列表中间"。 */
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
/* ── 分类页签：细下划线式，不使用胶囊按钮 ── */
/* 页签容器：交互与下划线交给 n-tabs，这里只负责占满宽度、不产生横向溢出 */
.source-tabs {
  align-self: stretch;
  min-width: 0;
}
.source-tabs :deep(.n-tabs-nav-scroll-content) {
  gap: 2px;
}

/* 返回按钮使用公共 n-button 后仅承担布局职责 */
.list-back {
  flex: none;
}

/* 歌单 / 分组浏览列表：占满列表区，原生滚动 */
.browse-scroll,
.browse-groups {
  flex: 1;
  min-height: 0;
  min-width: 0;
  width: 100%;
  /* 不在此处限制宽度：≥1100px 时列表按 GroupBrowserPanel 的自动列数排成多列，
     固定 920px 会使宽屏右半区留空。 */
  max-width: none;
}
/* 歌单网格：与同页「专辑 / 艺人 / 文件夹」的网格（GroupBrowserPanel）采用同一套版式 ——
   无边框卡片 + 8px 内距 + 圆角方形封面 + 名称与首数，右上角绝对定位「⋯」。
   `.browse-row` 在网格模式下必须 stretch：沿用 center 会把子项压成内容宽度（实测 46px），
   封面缩为小方块居于卡片中央。 */
.browse-scroll.is-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(150px, 1fr));
  gap: 14px;
  align-content: start;
  padding: 4px;
}
.browse-scroll.is-grid .list-empty {
  grid-column: 1 / -1;
}
.browse-scroll.is-grid .browse-row {
  position: relative;
  display: flex;
  flex-direction: column;
  align-items: stretch;
  padding: 8px;
  border-radius: var(--radius-md);
}
.browse-scroll.is-grid .browse-row:hover {
  background: var(--surface-hover);
}
.browse-scroll.is-grid .browse-main {
  /* 竖向撑满卡片：名称贴着封面、「N 首」用 margin-top:auto 钉在底部，
     同一行卡片等高时计数基线才会齐（比给标题预留两行更自然）。 */
  flex: 1;
  width: 100%;
  flex-direction: column;
  align-items: stretch;
  gap: 6px;
  padding: 0;
  border-radius: 0;
}
.browse-scroll.is-grid .browse-cover {
  width: 100%;
  height: auto;
  aspect-ratio: 1;
  border-radius: var(--radius-md);
  border: 1px solid var(--border-subtle);
  font-size: 28px;
}
.browse-scroll.is-grid .browse-text {
  flex: none;
  padding: 0;
}
.browse-scroll.is-grid .browse-text b {
  font-size: var(--font-base);
  white-space: normal;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  line-clamp: 2;
  -webkit-box-orient: vertical;
  line-height: 1.4;
}
.browse-scroll.is-grid .browse-count {
  margin: auto 0 0;
  padding: 0;
}
/* 直接定位「⋯」按钮本身：n-dropdown 不生成包裹元素，
   `.browse-row > .n-dropdown` 这类选择器不会匹配，按钮会退回正常流。 */
.browse-scroll.is-grid .browse-more {
  position: absolute;
  top: 12px;
  right: 12px;
  margin: 0;
  background: color-mix(in srgb, var(--card-bg) 78%, transparent);
  backdrop-filter: blur(2px);
}

.browse-scroll {
  overflow-y: auto;
  overflow-x: hidden;
  overscroll-behavior: contain;
  -webkit-overflow-scrolling: touch;
  touch-action: pan-y;
  padding-bottom: 8px;
}
/* 列表模式：与「歌曲列表」采用同一套表头与平铺行。
   歌单 / 分组列表与曲目表必须使用同一容器语言：表头一行（名称 / 歌曲数）+
   无边框平铺行 + 整行悬停，与歌曲表结构一致。 */
.browse-table-head {
  flex: none;
  display: grid;
  grid-template-columns: minmax(0, 1fr) 44px;
  align-items: center;
  padding: 0 14px 0 8px;
  border-bottom: 1px solid var(--border-subtle);
  color: var(--text-muted);
  font-size: var(--font-xs);
}
.browse-table-main {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  align-items: center;
  gap: 10px;
}
.browse-table-head .bh-name {
  /* 36 封面 + 10 gap = 46：表头文字与行内标题同一条基线 */
  padding-left: 46px;
}
.browse-table-head .bh-count {
  text-align: right;
}
.browse-scroll:not(.is-grid) {
  display: flex;
  flex-direction: column;
  gap: 0;
  padding: 0;
}
.browse-scroll:not(.is-grid) .browse-row {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 44px;
  align-items: center;
  padding-right: 4px;
  border-radius: var(--radius-md);
}
.browse-scroll:not(.is-grid) .browse-row:hover {
  background: var(--surface-hover);
}
.browse-scroll:not(.is-grid) .browse-main {
  padding: 6px 8px;
  gap: 10px;
}
.browse-scroll:not(.is-grid) .browse-cover {
  width: 36px;
  height: 36px;
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
  min-width: 0;
  border-radius: var(--radius-sm);
  /* 长列表切换分类时避免一次性布局全部歌单 / 分组 */
}
.browse-row:hover {
  background: var(--surface-hover);
}
.browse-main {
  flex: 1;
  min-width: 0;
  display: flex;
  align-items: center;
  gap: 10px;
  border: 0;
  background: none;
  border-radius: var(--radius-sm);
  padding: 6px 8px;
  font: inherit;
  color: inherit;
  text-align: left;
  cursor: pointer;
}
.browse-cover {
  flex: none;
  width: 36px;
  height: 36px;
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
  font-size: var(--font-md);
  color: var(--text-primary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.browse-count {
  flex: none;
  margin-left: auto;
  padding-left: 8px;
  font-size: var(--font-sm);
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
/* 歌单/分组浏览时标题与「新建歌单」跟着列表走；列表已铺满，这里不再限宽 */
.list-head.is-browse {
  width: 100%;
  max-width: none;
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
  font-size: var(--font-sm);
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
  /* 4px 时三个孪生按钮贴在一起，误点率高；与文件列表同一套间距语言 */
  gap: 6px;
  flex: none;
}

/* 仅含「⋯」的按钮使用方形命中区（列宽 34px，28px 不会破坏布局）。
   高度与字号统一交给 naive 的 size="small"，不另行定义尺寸。 */
.browse-more,
.q-more {
  min-width: 28px;
}
.list-spin {
  flex: 1;
  min-height: 0;
  overflow: hidden;
}
/* n-spin 会将内容包一层 `.n-spin-content`，该层默认按内容高度增长。
   列表达到 60 条时该层可达 2900px+，滚动容器因此无法取得可滚动高度（实测
   `.list-spin` 327px、`.n-spin-content` 2934px），列表被裁剪且播放条位置失去约束。
   撑满高度后原生滚动容器才会在列表内部滚动。与 PlaylistPanel 的 `.pl-spin` 同一做法。 */
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
     表头单元格置于 `.th-main` 内，其栅格与 `.queue-item` 逐列一致；若表头直接使用
     6 列而行为 5 列加 34px，艺人 / 专辑 / 时长列会左偏 5–10px。
     右侧 14px = 8px 基础内边距 + 6px 原生滚动条预留；列表内容宽度同样减少 6px。 */
  grid-template-columns: minmax(0, 1fr) 44px;
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
  grid-template-columns: minmax(0, 1fr) 44px;
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
  font-size: var(--font-sm);
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
  font-size: var(--font-md);
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
  font-size: var(--font-base);
  color: var(--text-muted);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.q-dur {
  text-align: right;
  font-size: var(--font-sm);
  color: var(--text-muted);
  font-variant-numeric: tabular-nums;
}
/* 时长列与「更多操作」之间的间距：外层「更多」列由 34px 改为 44px，
   按钮左移 6px 以留出间隔（右侧相应减少 2px，保证按钮仍在列内并与表头列边界对齐）。 */
.q-more {
  margin-left: 6px;
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
  /* 中文按钮在 390px 顶栏中会被挤成两行（40px 宽 × 46px 高）；
     按钮不参与收缩，宽度不足时由标题先让位。 */
  white-space: nowrap;
}
.im-top button:hover {
  background: var(--stage-soft-hover);
}
/* 仅含图标的按钮必须显式定义尺寸。
   `.im-top button` 的 `padding: 5px 9px`（移动端 5px 7px）是为**带文字**的按钮设计的：
   套用在 16px 图标上时，按钮尺寸由「图标 + 行盒」决定，而 inline 图标基线下方仍保留
   字体度量的空隙，纵向位置因而偏上，表现为右上角全屏钮图标未居中。
   此处定义为 32×32 方格并使用 grid 居中，横纵均由盒模型对齐，不受字体度量影响。
   圆角沿用继承的 8px，不为该处单独更换形状。 */
.im-top .im-fullscreen {
  display: grid;
  place-items: center;
  width: 32px;
  height: 32px;
  padding: 0;
}
.im-body {
  flex: 1;
  min-height: 0;
  display: flex;
  align-items: stretch;
  /* 左右分开：封面靠左、歌词靠右，两侧保留页面边距；中间空白由 space-between 吃掉。 */
  justify-content: space-between;
  gap: clamp(32px, 6vw, 120px);
  padding: 4px clamp(24px, 6vw, 96px) 10px;
  overflow: hidden;
}
.im-body .im-track {
  flex: 0 1 380px;
  width: auto;
  min-width: 220px;
}
.im-body .im-lyrics {
  flex: 0 1 620px;
  min-width: 260px;
}
.im-track {
  align-self: center;
  display: flex;
  flex-direction: column;
  gap: 20px;
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
/* 歌词面板内部使用全局 text 令牌；沉浸层不跟随主题，必须在此处覆盖为
   中性浅色，否则浅色主题下当前行会呈现为深灰、对比度不足。 */
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
.lyrics-panel-fade-enter-active,
.lyrics-panel-fade-leave-active {
  transition:
    opacity 0.36s ease,
    transform 0.36s ease;
}
.lyrics-panel-fade-enter-from,
.lyrics-panel-fade-leave-to {
  opacity: 0;
  transform: translateX(14px);
}
.lyrics-hidden .im-body {
  justify-content: center;
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
    /* q-index / q-artist / q-album 在下方被隐藏，列数必须同步收为 2 列。
       保留 44px 第一列会使 q-main 被压缩到 44px，歌名宽度变为 0。 */
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
  /* 播放键与播放条 / 沉浸层使用同一控件语言：深色实心圆 + 表面色图标。
     使用 32px 透明图标钮会与沉浸层的实心圆不一致，同一"播放"操作呈现为不同控件。
     尺寸按迷你条密度取 36（封面为 38），形状、配色与悬停状态与两条控制条保持一致。 */
  .mini-btn--play {
    width: 36px;
    height: 36px;
    background: var(--text-primary);
    color: var(--card-bg);
    flex: none;
  }
  .mini-btn--play:hover {
    background: var(--text-primary);
    opacity: 0.9;
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
    display: flex;
    flex-direction: column;
    align-items: center;
    gap: 10px;
    padding: 0 16px 6px;
  }
  .im-body .im-track {
    flex: 0 1 auto;
    width: 100%;
    min-width: 0;
  }
  .im-body .im-lyrics {
    flex: 0 1 auto;
    width: 100%;
    min-width: 0;
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
  .lyrics-panel-fade-enter-active,
  .lyrics-panel-fade-leave-active {
    transition: none;
  }
}
/* ── 响应式列（置于最后：覆盖上方基础栅格，保证顺序稳定） ──
   必须排除 ≤768：移动端隐藏 q-index / q-artist / q-album 后仅使用两列，
   该四列规则若继续命中移动端，会把 q-main 重新压回 24px 第一列。 */
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
