<template>
  <n-layout has-sider class="app-shell">
    <!-- 侧边栏 — 桌面端常驻，移动端抽屉 -->
    <n-layout-sider
      v-if="!isMobile"
      bordered
      :width="220"
      :collapsed-width="64"
      :collapsed="collapsed"
      collapse-mode="width"
      show-trigger
      class="bg-card-bg"
      @collapse="collapsed = true"
      @expand="collapsed = false"
    >
      <div class="sider-inner">
        <div class="sider-brand">
          <div class="w-8 h-8 rounded-lg bg-white border border-black/10 flex items-center justify-center">
            <svg
              width="20"
              height="20"
              viewBox="0 0 24 24"
              fill="none"
              stroke="#1E293B"
              stroke-width="1.25"
              stroke-linecap="round"
              stroke-linejoin="round"
              aria-hidden="true"
            >
              <path d="M10 3.2a9 9 0 1 0 10.8 10.8a1 1 0 0 0 -1 -1h-3.8a4.1 4.1 0 1 1 -5 -5v-4a0.9 0.9 0 0 0 -1 -0.8" />
              <path d="M15 3.5a9 9 0 0 1 5.5 5.5h-4.5a9 9 0 0 0 -1 -1v-4.5" />
            </svg>
          </div>
          <span v-show="!collapsed" class="font-semibold text-base">UFI-AXIS</span>
        </div>
        <!-- 菜单区独立滚动。.app-shell 是 position:fixed + overflow:hidden，
             以前 n-menu 直接摊在 sider 里，屏幕一矮（笔记本 768p / 浏览器缩放 / 分屏）
             底部几项就被裁掉且没有任何滚动条，完全点不到。 -->
        <div class="sider-menu-scroll">
          <n-menu
            :collapsed="collapsed"
            :collapsed-width="64"
            :collapsed-icon-size="20"
            :options="menuOptions"
            :value="activeKey"
            :theme-overrides="menuThemeOverrides"
            @update:value="handleMenuSelect"
          />
        </div>
      </div>
    </n-layout-sider>

    <!-- 主内容区 -->
    <n-layout class="main-layout">
      <!-- 顶栏 -->
      <n-layout-header bordered class="app-header">
        <div class="header-left" :class="{ 'has-back': !!backTarget, 'has-title': showHeaderTitle }">
          <n-button v-if="isMobile" quaternary circle class="header-menu-btn" @click="drawerVisible = true">
            <template #icon
              ><n-icon :size="20"><MenuOutline /></n-icon
            ></template>
          </n-button>
          <!--
            子页返回键（2026-10-22）。

            为什么放在壳层，而不是让每个页面自己写一颗「返回设置」：`tunnel` / `tasks` /
            `diagnostics` / `donate` / `sponsors` / `gallery` 在 2026-10-14 的侧栏瘦身里被移出侧栏，
            只能从设置页进入，但页面本身没有任何返回入口 —— 进去后只能靠浏览器返回；
            手机上更是死路（侧栏是抽屉，用户不知道要点汉堡键）。
            六个页面各写一颗按钮会立刻出现六种样式，所以由路由 meta 统一声明
            （`meta.back` / `meta.title` / `meta.menuKey`），壳层只渲染这一处。
          -->
          <n-button
            v-if="backTarget"
            quaternary
            circle
            class="header-back-btn"
            aria-label="返回上一级"
            title="返回上一级"
            @click="goBack"
          >
            <template #icon
              ><n-icon :size="18"><ArrowBackOutline /></n-icon
            ></template>
          </n-button>
          <span v-if="showHeaderTitle" class="header-title">{{ currentTitle }}</span>
          <!-- 设备条在桌面与移动端都留在顶栏：2026-10-24 改为无底色内联状态 -->
          <div class="header-device">
            <DeviceTopBar />
          </div>
        </div>
        <div class="header-right">
          <!--
            全局音乐控制条（2026-10-14）。放在顶栏而不是音乐页里，
            是为了"切页不断"：它读 `stores/player`，而那个 store 比任何页面活得久。
            曲目为空时它自己不渲染，所以不占位。
          -->
          <HeaderAudioBar />
          <!-- 天气挂件（可选，在设置→界面小功能里配置）-->
          <HeaderWeather />
          <!-- 每日诗词（可选）-->
          <HeaderPoem />
          <!-- WS 状态：颜色不随明暗切换，保证始终可读 -->
          <n-tooltip>
            <template #trigger>
              <div
                class="ws-pill"
                :class="wsStatusClass"
                :title="wsStatusText"
                :aria-label="`WebSocket ${wsStatusText}`"
              >
                <span class="ws-dot" :class="wsDotClass" />
              </div>
            </template>
            WebSocket {{ wsStore.fatalReason || wsStore.status }}
          </n-tooltip>
          <n-button quaternary circle @click="appStore.toggleDarkMode()">
            <template #icon>
              <n-icon :size="18">
                <SunnyOutline v-if="appStore.darkMode" />
                <MoonOutline v-else />
              </n-icon>
            </template>
          </n-button>
          <n-button quaternary circle class="header-logout" @click="handleLogout">
            <template #icon
              ><n-icon :size="18"><LogOutOutline /></n-icon
            ></template>
          </n-button>
        </div>
      </n-layout-header>

      <!-- 内容 -->
      <n-layout-content :class="['bg-page-bg', 'main-content', { 'main-content--flush': route.meta.flush }]">
        <!--
          服务被用户停掉时，除设置页外全部换成提示页（2026-09-03）。
          页面被卸载 ⇒ 各自 useInterval 的 onUnmounted(stop) 生效，10s/5s/2s 的轮询一并静默，
          不必逐个页面加门控。设置页必须留着，否则用户没地方把服务开回来。
        -->
        <ServiceStoppedNotice v-if="showServiceStopped" />
        <router-view v-else />
      </n-layout-content>
    </n-layout>

    <!-- 移动端抽屉 -->
    <n-drawer v-if="isMobile" v-model:show="drawerVisible" :width="240" placement="left">
      <n-drawer-content :body-style="{ padding: 0 }">
        <div class="sider-inner">
          <div class="sider-brand">
            <div class="w-8 h-8 rounded-lg bg-white border border-black/10 flex items-center justify-center">
              <svg
                width="20"
                height="20"
                viewBox="0 0 24 24"
                fill="none"
                stroke="#1E293B"
                stroke-width="1.25"
                stroke-linecap="round"
                stroke-linejoin="round"
                aria-hidden="true"
              >
                <path
                  d="M10 3.2a9 9 0 1 0 10.8 10.8a1 1 0 0 0 -1 -1h-3.8a4.1 4.1 0 1 1 -5 -5v-4a0.9 0.9 0 0 0 -1 -0.8"
                />
                <path d="M15 3.5a9 9 0 0 1 5.5 5.5h-4.5a9 9 0 0 0 -1 -1v-4.5" />
              </svg>
            </div>
            <span class="font-semibold text-base">UFI-AXIS</span>
          </div>
          <div class="sider-menu-scroll">
            <n-menu
              :options="menuOptions"
              :value="activeKey"
              :theme-overrides="menuThemeOverrides"
              @update:value="handleMenuSelect"
            />
          </div>
        </div>
      </n-drawer-content>
    </n-drawer>
  </n-layout>

  <!-- 验证码/新短信 实时卡片：全局浮动，数据来自 WS notification 频道（见组件头注释） -->
  <VerificationToast />

  <!--
    A 级网络操作的进度弹窗（WiFi 总开关 / 移动数据 / 飞行模式 / 制式切换）—— **全仓只挂这一份**（2026-10-14）。

    改前 `NetworkView.vue` 与 `DashboardView.vue` 各挂一份、进度状态又各自活在页面里：
    在仪表盘点 WiFi 开关 → 切到网络页 → 进度弹窗消失、这次操作在界面上"没了"（反过来一样），
    更糟的是旧页面卸载时 `useApiOperation` 的 `onScopeDispose` 把轮询一起停了，
    于是"设备到底切没切过去"再也没人盯着。现在状态在 `stores/networkOps`、执行器在本组件，
    从哪一页发起、切到哪一页，看到的都是同一份进度与结论。

    为什么挂这一层：本组件在整个登录会话里只挂载一次，且位于 `App.vue` 的
    `n-message-provider` 之内（执行器要 `useMessage`）。这两条同时成立的地方只有应用壳，
    所以 `onScopeDispose` 的边界也正好落在"登出 / 卸载应用壳"，而不是"切页"。

    服务停止分支（`ServiceStoppedNotice`）下安全：n-modal 传送到 body，不依赖内容区；
    而那时页面已经卸载、不可能发起新操作，弹窗只可能在"操作发起之后服务才停"时出现 ——
    显示的正是那次操作的失败/超时结论，比静默消失更该让用户看见。
  -->
  <OperationProgressModal
    :state="networkOps.state"
    :title="networkOps.title"
    :running-text="networkOps.runningText"
    :success-text="networkOps.successText"
    :hint="networkOps.hint"
    :fail-text="networkOps.failReason"
    :retry="networkOps.retryLast"
    :close="networkOps.close"
    :to-background="networkOps.toBackground"
  />

  <!--
    全局音频播放器的 `<audio>` 宿主（2026-10-14）。

    **必须挂在这一层**，不能挂在音乐页里：路由切换会把页面组件卸载掉，
    而 `<audio>` 一卸载歌就停了 —— 那正是"切页就断"的根因。
    这一层（DefaultLayout）在整个已登录会话里只挂载一次。

    它没有任何可见 UI（见那个组件的说明），所以放在模板最后不影响布局。
  -->
  <GlobalAudioPlayer />
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted, h } from 'vue';
import { useRouter, useRoute } from 'vue-router';
import { NIcon, useDialog, useMessage } from 'naive-ui';
import type { MenuOption } from 'naive-ui';
import {
  SpeedometerOutline,
  WifiOutline,
  HardwareChipOutline,
  FolderOpenOutline,
  DownloadOutline,
  VideocamOutline,
  MusicalNotesOutline,
  ImageOutline,
  MenuOutline,
  SunnyOutline,
  MoonOutline,
  LogOutOutline,
  ArrowBackOutline,
  ChatbubblesOutline,
  // 2026-10-14 侧栏重排后不再需要的四个图标（对应并入父页面的四项）已删除：
  // GlobeOutline（内网穿透）/ TimerOutline（定时任务）/ AlertCircleOutline（告警）/ AppsOutline（应用）
  StatsChartOutline,
  TerminalOutline,
  // 2026-10-22：「资源中心」入口已从侧栏移除，CloudDownloadOutline 随之删除。
  // 该页只负责插件组件；入口在「设置 → 服务更新」里的交叉链接与隧道页两处「未安装」提示，
  // 路由 `/resources` 保留（服务更新本身在 settings?tab=update）。
  SettingsOutline,
} from '@vicons/ionicons5';
import { useAppStore } from '@/stores/app';
import { useWebSocketStore } from '@/stores/websocket';
import { useDashboardStore } from '@/stores/dashboard';
import { useServiceStore } from '@/stores/service';
import { useUiExtrasStore } from '@/stores/uiExtras';
import { usePlayerStore } from '@/stores/player';
import { useCapabilitiesStore } from '@/stores/capabilities';
import { useIsMobile } from '@/composables/useIsMobile';
import { useWsTopics } from '@/composables/useRealtime';
import { useApiOperation } from '@/composables/useApiOperation';
import { getApiClient } from '@/composables/useApi';
import { onActiveTabChanged, releaseActiveTab } from '@/composables/tabLock';
import { useNetworkOpsStore } from '@/stores/networkOps';
import DeviceTopBar from '@/components/DeviceTopBar.vue';
import VerificationToast from '@/components/VerificationToast.vue';
import HeaderWeather from '@/components/HeaderWeather.vue';
import HeaderPoem from '@/components/HeaderPoem.vue';
import HeaderAudioBar from '@/components/HeaderAudioBar.vue';
import GlobalAudioPlayer from '@/components/GlobalAudioPlayer.vue';
import ServiceStoppedNotice from '@/components/ServiceStoppedNotice.vue';
import OperationProgressModal from '@/components/common/OperationProgressModal.vue';

const router = useRouter();
const route = useRoute();
const appStore = useAppStore();
const wsStore = useWebSocketStore();
const dashboardStore = useDashboardStore();
const serviceStore = useServiceStore();
const uiExtrasStore = useUiExtrasStore();
const dialog = useDialog();
// 2026-10-21（决策 D7-3）：多标签互斥的被动提示用 message（不打断操作）
const message = useMessage();

/*
 * 刷新恢复全局播放器状态（2026-10-14）。
 *
 * ⚠ 必须在这里 —— **setup 顶层**，不能放到任何一个 `onMounted` 里。
 * Vue 的挂载是深度优先的：`DefaultLayout` 的子树（含 `router-view` → 音乐页）
 * 先挂载完，父组件的 `onMounted` 才跑。放在 `GlobalAudioPlayer.onMounted` 里
 * 就会晚于音乐页的 `onMounted` —— 而音乐页要在自己的 `onMounted` 里读
 * `player.source` 来决定"队列来源是歌单还是分组"，那时它还是 null，
 * 于是"刷新时正在放某个歌单"会被静默重置成"全部音乐"。
 *
 * 放在 setup 顶层还顺带保证了 `HeaderAudioBar` 首帧就能显示曲名/封面，
 * 不会先闪一下空条。
 */
const player = usePlayerStore();
player.restoreFromSession();

/*
 * 设备能力集（2026-10-14）。与上面那条同理放在 setup 顶层 —— 各页在 setup 里就要
 * 读 `capabilities.supports(...)` 决定开关画不画，晚一拍就会先渲染出一屏"能点"的开关
 * 再集体变灰。它只是发一个 GET，且**失败静默**（失败时门禁全放行，见那个 store 的说明）。
 */
const capabilitiesStore = useCapabilitiesStore();
void capabilitiesStore.load();

/*
 * A 级网络操作的执行器（2026-10-14）。
 *
 * 状态（进行中 / 成功 / 失败 + 原因）住在 `stores/networkOps`，所以它比任何页面活得久；
 * 但"轮询回读 + 终态 toast"这段执行逻辑需要一个**能拿到 `useMessage` 的组件作用域**，
 * 而它又必须与状态同寿命。本组件是唯一同时满足这两条的地方：整个登录会话只挂载一次，
 * 且在 `App.vue` 的 `n-message-provider` 之内。
 *
 * 于是 `useApiOperation` 里的 `onScopeDispose` 边界落在"登出 / 卸载应用壳"而不是"切页" ——
 * 这正是"在仪表盘点 WiFi 开关、切到网络页还能看到进度"的前提。
 * 详见 stores/networkOps 的文件头。
 */
const networkOps = useNetworkOpsStore();
networkOps.attach(useApiOperation(networkOps.opHost()));

/** 服务停止时仍完整可用的路由：设置页是把服务开回来的唯一入口。 */
const SERVICE_INDEPENDENT_ROUTES = ['settings', 'login'];

const showServiceStopped = computed(
  () => serviceStore.stopped && !SERVICE_INDEPENDENT_ROUTES.includes(route.name as string)
);

// 实时频道 → store 的写入放在应用壳里，而不是某个页面里。
// 2026-09-03：原来只有 DashboardView 注册了 signal/cpu/memory/traffic 的 handler，
// 于是「直接进网络页」或「离开仪表盘后」就没人往 dashboardStore.realtimeSignal 写，
// 网络页的「信号质量」卡读到的一直是初始空对象 —— 这就是那个偶现"取不到信号质量"。
// 壳组件常驻，订阅生命周期和 WS 连接一致；各页面只管读 store。
useWsTopics({
  signal: (d) => dashboardStore.updateRealtime('signal', d),
  cpu: (d) => dashboardStore.updateRealtime('cpu', d),
  memory: (d) => dashboardStore.updateRealtime('memory', d),
  traffic: (d) => dashboardStore.updateRealtime('traffic', d),
  battery: (d) => dashboardStore.updateRealtime('battery', d),
});

const collapsed = ref(false);
const drawerVisible = ref(false);
const isMobile = useIsMobile();
// 视口高度不足时压缩菜单项高度，让 12 个入口尽量一屏放下（不够仍可滚动）
const shortViewport = ref(false);

// 侧栏自动收起阈值：768–1024 属于"能放下侧栏但内容区会很挤"，默认收成图标条。
// 只在跨过阈值的那一刻改一次，之后用户手动展开/收起不会被 resize 事件顶回去。
const NARROW_BREAKPOINT = 1024;
let wasNarrow: boolean | null = null;

function checkMobile() {
  // isMobile 现在由 useIsMobile() composable 驱动（matchMedia），
  // 这里只处理它管不了的两件事：短视口压缩、侧栏自动收起
  shortViewport.value = window.innerHeight < 720;
  const narrow = window.innerWidth < NARROW_BREAKPOINT;
  if (wasNarrow !== narrow) {
    collapsed.value = narrow;
    wasNarrow = narrow;
  }
}

onMounted(() => {
  checkMobile();
  window.addEventListener('resize', checkMobile);
  // 2026-10-21（决策 D7-3）：同一浏览器同一时间只有一个标签页保持登录
  //（再次 confirm 会轮换 token，另一个标签页必然 444）。
  // 本页若被别的标签页接管，这里立刻提示 —— 之前的表现是"过一会儿突然被踢回登录页"。
  unsubTabLock = onActiveTabChanged((active, self) => {
    if (active && active.id !== self) {
      message.warning('本页已在另一个标签页重新登录，若继续操作可能互相踢下线；建议关闭本页或重新登录。');
    }
  });
  // 连接 WebSocket
  wsStore.connect(appStore.token, appStore.baseUrl);
  // 服务开关状态巡检（30s，只读 core 内存开关，不碰 goform）
  serviceStore.startWatch();
  // 加载 dashboard summary
  loadSummary();
  // core 崩溃提醒（崩溃后自动重启，前端否则完全无感）
  checkCoreCrash();
  // 顶栏挂件（天气 / 诗词）的配置与数据。两者都默认关闭，关着时 init 不会发数据请求；
  // 失败一律静默 —— 装饰性功能不该在顶栏弹错误。
  uiExtrasStore.init();
});

function renderIcon(icon: any) {
  return () => h(NIcon, null, { default: () => h(icon) });
}

// 矮屏把菜单项从默认 42px 压到 34px，12 个入口逐项省 8px，再加压缩后的品牌区
const menuThemeOverrides = computed(() => (shortViewport.value ? { itemHeight: '34px' } : undefined));

/*
 * ── 平铺：12 个入口同层（2026-10-14）──
 *
 * 用户裁决：「侧边栏不需要**通信与媒体**、**系统**的分类栏」。
 * 改前是「3 个置顶叶子 + 通信与媒体 ▸ 6 项 + 系统 ▸ 4 项」的两级结构，
 * 每个叶子都要先展开父项才看得见；现在全部提到顶层。
 *
 * 只去分组，**图标、顺序、label 一个字都没动**，所以：
 *   · 短信的图标仍是 ChatbubblesOutline —— 它此前与父组共用同一个图标，别误以为可以一起删；
 *   · 「设置」仍排在「终端」之后，没有因为失去父项被挪到别处。
 *
 * 2026-10-22：「资源中心」入口从侧栏移除（13 → 12 项），应用内入口改为
 * 「设置 → 服务更新」里的交叉链接与隧道页的「未安装」提示。路由 `/resources` 与 Resource
 * 页面**保留**（2026-10-23 起只放 frpc / cloudflared 等插件组件）—— 删的是菜单项，不是页面。
 *
 * 确认过没有"父项本身就是可点页面"的情况：`g-content` / `g-system` 只是容器，
 * 路由表里没有同名路由（只有 dashboard / network / device / sms … 这些叶子名），
 * 所以两个父项整体删掉即可，不需要"提升成叶子"。
 *
 * 以前 16 → 12 的合并保持不变（告警/定时任务/内网穿透/应用 已并入父页面）：
 * 它们不再占侧栏，路由全部保留（`/alerts`、`/tasks`、`/tunnel`、`/apps` 照旧可直接打开）。
 */
const menuOptions = computed<MenuOption[]>(() => [
  { label: '仪表盘', key: 'dashboard', icon: renderIcon(SpeedometerOutline) },
  { label: '网络', key: 'network', icon: renderIcon(WifiOutline) },
  { label: '设备', key: 'device', icon: renderIcon(HardwareChipOutline) },
  // 以下 6 项原属「通信与媒体」组，顺序与组内顺序一致
  { label: '短信', key: 'sms', icon: renderIcon(ChatbubblesOutline) },
  { label: '文件', key: 'files', icon: renderIcon(FolderOpenOutline) },
  { label: '下载', key: 'downloads', icon: renderIcon(DownloadOutline) },
  { label: '视频', key: 'media-video', icon: renderIcon(VideocamOutline) },
  { label: '音乐', key: 'media-audio', icon: renderIcon(MusicalNotesOutline) },
  { label: '图片', key: 'media-image', icon: renderIcon(ImageOutline) },
  // 以下 3 项原属「系统」组（「资源中心」已于 2026-10-22 移出侧栏）
  { label: '监控', key: 'monitor', icon: renderIcon(StatsChartOutline) },
  { label: '终端', key: 'terminal', icon: renderIcon(TerminalOutline) },
  { label: '设置', key: 'settings', icon: renderIcon(SettingsOutline) },
]);

/**
 * 当前路由对应的侧栏高亮项。
 *
 * 平铺后不再需要"父项"这一层（可展开分组已删除），但**子页**仍要能点亮父入口：
 * `tunnel` / `tasks` 这类页面已不在 `menuOptions` 里，直接拿路由名匹配会导致
 * 整条侧栏没有任何一项选中 —— 用户失去"我在哪"的锚点。子页在路由 meta 里用
 * `menuKey` 指向父入口（见 router/index.ts 的 RouteMeta 说明）。
 */
const activeKey = computed(() => {
  const name = route.name as string;
  return route.meta.menuKey ?? (name || 'dashboard');
});

function findMenuLabel(options: MenuOption[], key: string): string | undefined {
  // 平铺后就是一次 find；保留函数形态是为了调用点（currentTitle）不用改。
  // 不要再往里加 children 递归：分组已经去掉，递归只会掩盖"又有人加了嵌套结构"这件事。
  return options.find((o) => o.key === key)?.label as string | undefined;
}

/**
 * 顶栏标题：**先看路由 meta.title**，再看菜单名。
 *
 * 子页（隧道、定时任务、诊断中心…）不在菜单里，原来会回落成 `UFI-AXIS` ——
 * 顶栏等于在说"你不在任何页面"。meta.title 就是给它们用的；
 * 菜单页不写 title，仍走原来的 label，避免同一名称维护两处。
 */
const currentTitle = computed(() => {
  return route.meta.title || findMenuLabel(menuOptions.value, activeKey.value) || 'UFI-AXIS';
});

/** 子页的返回目标；为空 = 当前页在侧栏里，不需要返回键 */
const backTarget = computed(() => route.meta.back ?? null);

/**
 * 顶栏标题只在需要它的时候出现：
 * · 有返回键的子页（定时任务 / 内网穿透 / 诊断中心…）保留标题，避免只剩一颗返回键；
 * · 终端页没有内容区 h1，也保留；
 * · 仪表盘、设置等主页面内容区已经有 h1，顶栏不再重复。
 */
const showHeaderTitle = computed(() => !!backTarget.value || route.name === 'terminal');

/**
 * 返回上一级。
 *
 * 优先 `router.back()`：从设置页进来时，上一页（含它的滚动位置与状态）还在 history 里，
 * 回去就是原样。直接打开链接 / 刷新后没有站内上一页 —— vue-router 会把上一条记录写在
 * `history.state.back` 里，此时为 null —— 回落到路由 meta 声明的父级；
 * 否则 `router.back()` 会把用户带出应用（回到新标签页或外部页面）。
 */
function goBack() {
  const hasInAppPrev = !!(window.history.state as { back?: string | null } | null)?.back;
  if (hasInAppPrev) router.back();
  else if (backTarget.value) router.push(backTarget.value);
}

function handleMenuSelect(key: string) {
  router.push({ name: key });
  drawerVisible.value = false;
}

// WS 状态 —— 只切换 class 名；颜色写在 CSS 固定值里，不随 dark 模式翻转
const wsStatusClass = computed(() => {
  if (wsStore.status === 'connected') return 'is-connected';
  if (wsStore.status === 'connecting') return 'is-connecting';
  return 'is-offline';
});
const wsDotClass = computed(() => {
  if (wsStore.status === 'connected') return 'dot-ok';
  if (wsStore.status === 'connecting') return 'dot-warn';
  return 'dot-muted';
});
const wsStatusText = computed(() => {
  if (wsStore.status === 'connected') return '已连接';
  if (wsStore.status === 'connecting') return '连接中';
  return '未连接';
});

// 加载 dashboard summary
async function loadSummary() {
  dashboardStore.loading = true;
  try {
    const api = getApiClient();
    const { data } = await api.get('/api/dashboard/summary');
    dashboardStore.updateSummary(data);
  } catch (e) {
    console.error('Failed to load dashboard summary:', e);
  } finally {
    dashboardStore.loading = false;
  }
}

// 监听 data_changed → 重新加载（服务停止期间不再拉，顶栏保留最后一次已知值）
const unsubDataChanged = wsStore.on('data_changed', () => {
  if (serviceStore.stopped) return;
  loadSummary();
});

/** 多标签互斥监听的取消函数（2026-10-21 决策 D7-3，在 onMounted 里赋值）。 */
let unsubTabLock: (() => void) | null = null;

/**
 * 设备后端（core）崩溃提醒（2026-09-04）。
 *
 * core 崩溃后由 keepalive 脚本 / START_STICKY 自动拉起，HTTP 很快恢复 —— web 此前完全无感，
 * 用户只看到图表断了一下。这里进页面时拉一次 `GET /api/service/crash`，
 * 时间戳与 localStorage 里"已提示过的"不同就弹一次。
 *
 * 去重放本地而不是让 core 记 ack：app 与 web 是两个独立展示端，谁先 ack 另一端就永远看不到。
 * 失败一律静默（老 core 没这个端点会 404）—— 这只是提醒，不该给用户报错。
 *
 * 2026-10-23：只弹 `recent === true` 的崩溃。`crashed` 对三个月前的记录也恒为 true，
 * 只靠本地 localStorage 去重时，换浏览器/清站点数据就会让旧崩溃重新弹窗；
 * 「值不值得提示」的 7 天窗口由 core 用同一时钟判定（见 `ServiceRoutes.CRASH_NOTICE_WINDOW_MS`）。
 */
const CORE_CRASH_SHOWN_KEY = 'ufi_core_crash_shown_at';

async function checkCoreCrash() {
  try {
    const api = getApiClient();
    const { data } = await api.get('/api/service/crash');
    if (!data?.crashed || !data?.timestamp) return;
    // 旧 core 没有 recent 字段 → undefined，按"新鲜"处理，保持升级前的行为。
    if (data?.recent === false) return;
    const ts = String(data.timestamp);
    if (localStorage.getItem(CORE_CRASH_SHOWN_KEY) === ts) return;
    localStorage.setItem(CORE_CRASH_SHOWN_KEY, ts);
    const when = new Date(Number(data.timestamp)).toLocaleString();
    dialog.warning({
      title: '设备后端曾崩溃并已自动重启',
      content: [`时间：${when}`, data.summary && `原因：${data.summary}`, data.file && `详情：${data.file}`]
        .filter(Boolean)
        .join('\n'),
      positiveText: '知道了',
    });
  } catch {
    /* 静默：老 core 无此端点 / 网络不可达都不该打扰用户 */
  }
}

function handleLogout() {
  wsStore.disconnect();
  appStore.clearAuth();
  // 2026-10-21（决策 D7-3）：退出即释放"活跃标签页"锁，其它标签页不再被提示。
  releaseActiveTab();
  // 能力集是**每台设备**的事实（插件声明的硬件能力），换设备后旧的声明是错的：
  // 留着会让新设备被上一台的能力门禁掉。与 resetApiClient 同一时机。
  capabilitiesStore.reset();
  router.push('/login');
}

// 清理
onUnmounted(() => {
  window.removeEventListener('resize', checkMobile);
  serviceStore.stopWatch();
  unsubDataChanged?.();
  unsubTabLock?.();
});
</script>

<style scoped>
/* ── 全屏应用壳：侧栏+顶栏固定，内容区独立滚动 ── */
.app-shell {
  position: fixed;
  inset: 0;
  overflow: hidden;
}
/* 主区纵向撑满，内容区独立滚动，使顶栏与侧栏固定 */
.main-layout {
  display: flex;
  flex-direction: column;
  height: 100%;
}
/* naive-ui 的每个 n-layout 都会把 slot 再包一层 .n-layout-scroll-container
   （height:100% + overflow-x:hidden ⇒ overflow-y 计算为 auto）。这层包裹不是 flex 容器，
   所以下面的 .main-content{flex:1} 原先完全不起作用：内容区高度=内容高度，顶栏以下的空白
   其实是 .main-layout 自己的白底；滚动也发生在这层外层容器上，页面变长时顶栏会被一起滚走。
   把它改成撑满的 flex 列，内容区才真正占满顶栏以下的区域并独立滚动。 */
.main-layout > :deep(.n-layout-scroll-container) {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
  overflow: hidden;
}
.main-layout > :deep(.n-layout-scroll-container) > .n-layout-header {
  flex: 0 0 auto;
}
.main-content {
  flex: 1;
  min-height: 0;
  overflow: hidden;
}
/* 滚动条与内边距都交给内容区自己的包裹层；浅灰背景由 .main-content 提供（见 main.css） */
.main-content > :deep(.n-layout-scroll-container) {
  height: 100%;
  padding: 24px;
}
/* 全出血路由（音乐页）：去掉四边 padding，页面自己管留白。
   用双类名压过后面的 `@media (max-width: 768px)` 里那条 14px 规则。 */
.main-content.main-content--flush > :deep(.n-layout-scroll-container) {
  padding: 0;
}

/* ── 顶栏 ── */
.app-header {
  height: 56px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 0 16px;
  overflow: hidden;
  box-sizing: border-box;
}
.header-left {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
  flex: 0 1 auto;
}
.header-menu-btn {
  flex-shrink: 0;
}
/* 返回键与汉堡键同尺寸，同样不许被标题挤掉（标题在窄屏会省略号） */
.header-back-btn {
  flex-shrink: 0;
}
.header-title {
  font-size: 16px;
  font-weight: 500;
  line-height: 1.2;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  min-width: 2.5em;
  flex: 0 1 auto;
}
.header-device {
  display: flex;
  align-items: center;
  min-width: 0;
  flex: 0 1 auto;
}
.header-right {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 6px;
  flex: 1 1 auto;
  min-width: 0;
}

/* WS 状态收成一个可读的点，文字放 tooltip；顶栏不再被“已连接”三个字占宽。 */
.ws-pill {
  width: 24px;
  height: 24px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  flex-shrink: 0;
  user-select: none;
}
.ws-pill.is-connected {
  background: var(--success-light);
  color: var(--success);
}
.ws-pill.is-connecting {
  background: var(--warning-light);
  color: var(--warning);
}
.ws-pill.is-offline {
  background: var(--cat-neutral-bg);
  color: var(--cat-neutral-fg);
}
.ws-dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  flex-shrink: 0;
}
.ws-dot.dot-ok {
  background: var(--success);
}
.ws-dot.dot-warn {
  background: var(--warning);
}
.ws-dot.dot-muted {
  background: var(--text-muted);
}

@media (max-width: 768px) {
  .app-header {
    padding: 0 12px;
    gap: 6px;
  }
  .header-left {
    gap: 6px;
  }
  .header-title {
    font-size: 15px;
  }
  .header-right {
    gap: 3px;
  }
}
@media (max-width: 900px) {
  /* 子页/终端已经有返回键和标题时，设备状态让位，避免窄屏横向挤压 */
  .header-left.has-back .header-device,
  .header-left.has-title .header-device {
    display: none;
  }
}
@media (max-width: 480px) {
  /* 移动端顶栏收紧内距，给退出按钮留出位置 */
  .app-header {
    padding: 0 8px;
    gap: 4px;
  }
  .header-left {
    gap: 4px;
  }
  .header-right {
    gap: 2px;
  }
  .header-device :deep(.dtb-name) {
    max-width: 54px;
  }
}
@media (max-width: 380px) {
  /* 360px 档再让出设备名，仅保留设备图标 + 在线点；详情仍可点开 */
  .header-device :deep(.dtb-name) {
    display: none;
  }
}

/* ── 侧栏 / 抽屉：品牌区固定，菜单区独立滚动 ── */
.sider-inner {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
}
.sider-brand {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 20px 16px;
  flex-shrink: 0;
}
.sider-menu-scroll {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  /* 菜单滚到底后不要把外层页面一起带着滚 */
  overscroll-behavior: contain;
}

/* 220px 宽的侧栏里默认滚动条太占地方，收细并去掉轨道 */
.sider-menu-scroll::-webkit-scrollbar {
  width: 4px;
}
.sider-menu-scroll::-webkit-scrollbar-track {
  background: transparent;
}
/* 滚动条滑块：刻意用中性灰半透明而不是 --border-subtle。
   它要同时压在浅色与深色侧栏上，中性灰叠加在两种底色上都能看见；
   接 --border-subtle 反而会在暗色下和背景糊在一起（那个变量本身就是随明暗切换的低对比描边色）。
   取值已提到 main.css 的 --scrollbar-thumb / --scrollbar-thumb-hover。 */
.sider-menu-scroll::-webkit-scrollbar-thumb {
  background: var(--scrollbar-thumb);
  border-radius: 2px;
}
.sider-menu-scroll:hover::-webkit-scrollbar-thumb {
  background: var(--scrollbar-thumb-hover);
}
/* Firefox */
.sider-menu-scroll {
  scrollbar-width: thin;
  scrollbar-color: var(--scrollbar-thumb) transparent;
}

/* 矮屏（笔记本 768p / 浏览器缩放 / 分屏）：压缩品牌区，把高度让给菜单 */
@media (max-height: 720px) {
  .sider-brand {
    padding: 10px 16px;
  }
}

@media (max-width: 768px) {
  .main-content > :deep(.n-layout-scroll-container) {
    padding: 14px;
  }
}
</style>
