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
        <!-- 菜单区独立滚动。.app-shell 为 position:fixed + overflow:hidden；
             若 n-menu 直接放在 sider 中，视口高度不足时（笔记本 768p / 浏览器缩放 / 分屏）
             底部菜单项会被裁剪且不出现滚动条，无法点击。 -->
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
            子页返回键。

            放在壳层而不由各页面自行渲染的原因：`tunnel` / `tasks` / `diagnostics` / `donate` /
            `sponsors` / `gallery` 已移出侧栏，只能从设置页进入，但这些页面自身没有返回入口，
            只能依赖浏览器返回；移动端更不可用（侧栏为抽屉，用户无法预期需要点击菜单键）。
            六个页面各自渲染会立即出现六种样式，因此由路由 meta 统一声明
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
          <!-- 设备条在桌面与移动端都保留在顶栏：无底色内联状态 -->
          <div class="header-device">
            <DeviceTopBar />
          </div>
        </div>
        <div class="header-right">
          <!--
            全局音乐控制条。放在顶栏而不是音乐页中，以便「切页不断」：
            它读取 `stores/player`，该 store 的生命周期长于任何页面。
            曲目为空时组件自身不渲染，因此不占位。
          -->
          <HeaderAudioBar />
          <!-- 状态块：第一行天气、第二行诗句（两行布局）。
               并排布局下两个挂件在同一行竞争宽度，窄屏只能整体隐藏天气；
               改为两行后固定预留宽度/高度，诗句后到达也不会挤动音乐条（顶栏 56px 可容纳）。 -->
          <div class="header-status">
            <!-- 天气挂件（可选，在设置→界面小功能里配置）-->
            <HeaderWeather />
            <!-- 每日诗词（可选）-->
            <HeaderPoem />
          </div>
          <!-- WS 状态：颜色不随明暗切换，保证始终可读 -->
          <!--
            此处**不使用** n-tooltip：其内部基于 Popover + <Transition>，
            而顶栏会在点击「退出登录 / 切换主题」时整体卸载 —— 动画未播完宿主即被移除，
            抛出 runtime-15「Cannot read properties of null (reading 'parentNode')」。
            原生 title 可以提供相同文案（见下面的 :title），无 transition 与崩溃风险。
          -->
          <div class="ws-pill" :class="wsStatusClass" :title="wsTooltip" :aria-label="`WebSocket ${wsStatusText}`">
            <span class="ws-dot" :class="wsDotClass" />
          </div>
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
          服务被停止时，除设置页外全部替换为提示页。
          页面被卸载后各自 useInterval 的 onUnmounted(stop) 生效，10s/5s/2s 的轮询一并停止，
          无需逐页增加门控。设置页必须保留，否则用户无法重新开启服务。
        -->
        <ServiceStoppedNotice v-if="showServiceStopped" />
        <!--
          路由级淡入。只对声明了 `meta.transition` 的路由启用（定时任务 / 内网穿透 / 资源中心）：
          `:css` 直接跟随该开关，未声明的路由不会添加过渡类。

          **不得恢复 mode="out-in"**：out-in 的收尾回调在离场动画结束后调用
          `instance.update()` 重新渲染 Transition 自身；而路由页内部可能还有自己的
          `<Transition>`（设置页的聚合页 ↔ 详情页），整棵树卸载时会把父级挂在 vnode 上的
          收尾钩子替换掉 —— 回调不再执行，`isLeaving` 永久停留在 true，
          内容区只剩注释占位符，表现为「点击侧栏后白屏且无法再切换」。
          此外 `:css="false"` 时离场是同步完成的，`instance.update()` 会在 patch
          过程中重入，同样崩溃（runtime-15 的实际来源）。
          当前实现只做入场淡入、离场瞬切，不存在 out-in 也就没有这条收尾路径。
        -->
        <router-view v-else v-slot="{ Component }">
          <Transition name="route-fade" :css="routeFade">
            <component :is="Component" v-if="Component" />
          </Transition>
        </router-view>
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
    A 级网络操作的进度弹窗（WiFi 总开关 / 移动数据 / 飞行模式 / 制式切换）—— **全仓只挂载这一份**。

    若由 `NetworkView.vue` 与 `DashboardView.vue` 各挂一份、进度状态各自保存在页面中，
    在仪表盘触发 WiFi 开关后切换到网络页，进度弹窗会消失，该次操作在界面上不再可见；
    且旧页面卸载时 `useApiOperation` 的 `onScopeDispose` 会停止轮询，
    操作是否生效将不再被跟踪。现在状态位于 `stores/networkOps`、执行器在本组件，
    无论从哪一页发起、切换到哪一页，看到的都是同一份进度与结论。

    挂载在此的原因：本组件在整个登录会话中只挂载一次，且位于 `App.vue` 的
    `n-message-provider` 之内（执行器需要 `useMessage`）。同时满足这两个条件的只有应用壳，
    因此 `onScopeDispose` 的边界落在「登出 / 卸载应用壳」，而不是「切页」。

    服务停止分支（`ServiceStoppedNotice`）下是安全的：n-modal 传送到 body，不依赖内容区；
    此时页面已卸载、不可能发起新操作，弹窗只可能在「操作发起后服务才停止」时出现 ——
    显示的是该次操作的失败/超时结论，应让用户看到而不是静默消失。
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
    全局音频播放器的 `<audio>` 宿主。

    **必须挂载在这一层**，不能挂在音乐页中：路由切换会卸载页面组件，
    而 `<audio>` 一旦卸载播放即中断。
    这一层（DefaultLayout）在整个已登录会话中只挂载一次。

    该组件没有任何可见 UI（见其组件说明），因此放在模板末尾不影响布局。
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
  CubeOutline,
  VideocamOutline,
  MusicalNotesOutline,
  ImageOutline,
  MenuOutline,
  SunnyOutline,
  MoonOutline,
  LogOutOutline,
  ArrowBackOutline,
  ChatbubblesOutline,
  // 侧栏重排后不再需要的四个图标（对应并入父页面的四项）已删除：
  // GlobeOutline（内网穿透）/ TimerOutline（定时任务）/ AlertCircleOutline（告警）/ AppsOutline（应用）
  StatsChartOutline,
  TerminalOutline,
  PhonePortraitOutline,
  // 「资源中心」入口已从侧栏移除，CloudDownloadOutline 随之删除。
  // 该页只负责插件组件；入口位于「设置 → 服务更新」的交叉链接与隧道页的「未安装」提示，
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

/**
 * 路由级淡入。
 *
 * 判据是「**目标或来源**任一声明了 `meta.transition`」，而不是只看目标：
 * 若只看目标，「设置 → 定时任务」有动画、「定时任务 → 设置」没有 ——
 * 用户点击返回时缺少过渡。
 *
 * 挂在 afterEach 而不是 watch(route) 的原因：afterEach 在路由确认之后、Vue 的渲染队列
 * （microtask）之前**同步**执行，因此该 ref 在 DOM 换页那一帧已是新值；
 * watch 默认的 pre 时机同样能赶上，但需要额外处理「初始导航」这一种情况。
 *
 * 设置页内部 `?tab=` 的切换（同一条路由、两侧都没有 meta.transition）不会触发它 ——
 * 该层由 SettingsView 自己的 <Transition> 负责，不会叠加两道动画。
 */
const routeFade = ref(false);
const removeRouteFade = router.afterEach((to, from) => {
  routeFade.value = !!(to.meta.transition || from.meta.transition);
});
const route = useRoute();
const appStore = useAppStore();
const wsStore = useWebSocketStore();
const dashboardStore = useDashboardStore();
const serviceStore = useServiceStore();
const uiExtrasStore = useUiExtrasStore();
const dialog = useDialog();
// 多标签互斥的被动提示使用 message（不打断当前操作）
const message = useMessage();

/*
 * 刷新恢复全局播放器状态。
 *
 * 必须放在**setup 顶层**，不能放入任何一个 `onMounted`。
 * Vue 的挂载是深度优先的：`DefaultLayout` 的子树（含 `router-view` → 音乐页）
 * 先挂载完成，父组件的 `onMounted` 才执行。若放在 `GlobalAudioPlayer.onMounted` 中，
 * 会晚于音乐页的 `onMounted` —— 而音乐页需要在自己的 `onMounted` 中读取
 * `player.source` 以确定「队列来源是歌单还是分组」，此时它仍为 null，
 * 会导致「刷新时正在播放某个歌单」被静默重置为「全部音乐」。
 *
 * 放在 setup 顶层还保证 `HeaderAudioBar` 首帧即可显示曲名/封面，不会先显示空条。
 */
const player = usePlayerStore();
player.restoreFromSession();

/*
 * 设备能力集。与上一条同理放在 setup 顶层 —— 各页在 setup 中就要读取
 * `capabilities.supports(...)` 决定是否渲染开关，否则会先渲染出一屏可点击的开关，
 * 随后再统一变为禁用。该调用只发起一个 GET，且**失败静默**
 * （失败时门禁全部放行，见该 store 的说明）。
 */
const capabilitiesStore = useCapabilitiesStore();
void capabilitiesStore.load();

/*
 * A 级网络操作的执行器。
 *
 * 状态（进行中 / 成功 / 失败 + 原因）位于 `stores/networkOps`，因此其生命周期长于任何页面；
 * 但「轮询回读 + 终态 toast」这段执行逻辑需要一个**能取得 `useMessage` 的组件作用域**，
 * 且必须与状态同寿命。本组件是唯一同时满足这两个条件的位置：整个登录会话只挂载一次，
 * 且位于 `App.vue` 的 `n-message-provider` 之内。
 *
 * 因此 `useApiOperation` 中的 `onScopeDispose` 边界落在「登出 / 卸载应用壳」而不是「切页」——
 * 这是「在仪表盘触发 WiFi 开关、切换到网络页仍能看到进度」的前提。
 * 详见 stores/networkOps 的文件头。
 */
const networkOps = useNetworkOpsStore();
networkOps.attach(useApiOperation(networkOps.opHost()));

/** 服务停止时仍完整可用的路由：设置页是把服务开回来的唯一入口。 */
const SERVICE_INDEPENDENT_ROUTES = ['settings', 'login'];

const showServiceStopped = computed(
  () => serviceStore.stopped && !SERVICE_INDEPENDENT_ROUTES.includes(route.name as string)
);

// 实时频道 → store 的写入放在应用壳中，而不是某个页面中。
// 若只在 DashboardView 注册 signal/cpu/memory/traffic 的 handler，
// 则「直接进入网络页」或「离开仪表盘后」没有代码向 dashboardStore.realtimeSignal 写入，
// 网络页的「信号质量」卡只能读到初始空对象。
// 壳组件常驻，订阅生命周期与 WS 连接一致；各页面只读取 store。
useWsTopics({
  signal: (d) => dashboardStore.updateRealtime('signal', d),
  // 实时连接态。core 在采集 signal 的同一次 goform 查询中读到的 ppp_status，
  // 与 signal 同频推送；此处只合并 ppp 一个字段（不改动 mobile_data 等用户意图字段）。
  connection: (d) => dashboardStore.updateConnectionStatus(String(d?.ppp_status ?? '')),
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

// 侧栏自动收起阈值：768–1024 区间可以容纳侧栏，但内容区会明显拥挤。
// 自动收起目前未启用（见 checkMobile）；该常量保留作为阈值说明。
const NARROW_BREAKPOINT = 1024;

function checkMobile() {
  // isMobile 由 useIsMobile() composable 驱动（matchMedia），
  // 此处只处理它无法覆盖的两件事：短视口压缩与侧栏自动收起（当前未启用）。
  shortViewport.value = window.innerHeight < 720;
  // **不再**按窗口宽度自动折叠侧栏。
  // 折叠状态下 naive-ui 会为每个菜单项内部挂载 n-tooltip（Popover + Transition）：
  // 点击菜单 → :value 变化 → 菜单项重新渲染 → 动画中的 tooltip 宿主被移除，
  // Vue 抛出 runtime-15（parentNode / subTree 为 null）。移动端访问必然处于窄屏，
  // 因此这是**必然出现**的路径。窄屏已由下面的 isMobile 使用抽屉，无需再次折叠。
  void NARROW_BREAKPOINT;
}

onMounted(() => {
  checkMobile();
  window.addEventListener('resize', checkMobile);
  // 同一浏览器同一时间只有一个标签页保持登录
  //（再次 confirm 会轮换 token，另一个标签页必然收到 444）。
  // 本页若被其它标签页接管，立即在此提示，避免出现「操作一段时间后被跳回登录页」且无说明。
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
 * ── 平铺：12 个入口同层 ──
 *
 * 侧边栏不保留「通信与媒体」「系统」分类层。原结构为「3 个置顶叶子 + 通信与媒体 ▸ 6 项 +
 * 系统 ▸ 4 项」的两级结构，每个叶子都需要先展开父项；现在全部提升到顶层。
 *
 * 仅移除分组，**图标、顺序与 label 均未改动**，因此：
 *   · 短信的图标仍为 ChatbubblesOutline —— 它此前与父项共用同一图标，不可随父项一并删除；
 *   · 「设置」仍排在「终端」之后，未因失去父项而移动位置。
 *
 * 「资源中心」入口已从侧栏移除（13 → 12 项），应用内入口改为
 * 「设置 → 服务更新」中的交叉链接与隧道页的「未安装」提示。路由 `/resources` 与
 * Resource 页面**保留**（只承载 frpc / cloudflared 等插件组件）—— 删除的是菜单项，不是页面。
 *
 * 已确认不存在「父项本身即可点击页面」的情况：`g-content` / `g-system` 只是容器，
 * 路由表中没有同名路由（只存在 dashboard / network / device / sms … 这些叶子名），
 * 因此两个父项可以整体删除，不需要「提升成叶子」。
 *
 * 告警 / 定时任务 / 内网穿透 / 应用 已并入父页面，不再占用侧栏，
 * 路由全部保留（`/alerts`、`/tasks`、`/tunnel`、`/apps` 仍可直接打开）。
 */
const menuOptions = computed<MenuOption[]>(() => [
  { label: '仪表盘', key: 'dashboard', icon: renderIcon(SpeedometerOutline) },
  { label: '网络', key: 'network', icon: renderIcon(WifiOutline) },
  { label: '设备', key: 'device', icon: renderIcon(HardwareChipOutline) },
  // 以下 6 项原属「通信与媒体」组，顺序与组内顺序一致
  { label: '短信', key: 'sms', icon: renderIcon(ChatbubblesOutline) },
  { label: '文件', key: 'files', icon: renderIcon(FolderOpenOutline) },
  { label: '下载', key: 'downloads', icon: renderIcon(DownloadOutline) },
  // 第三方程序（应用商店）：装的是 Linux 程序，不是 APK，见 ProgramsView 顶部说明
  { label: '程序', key: 'programs', icon: renderIcon(CubeOutline) },
  { label: '视频', key: 'media-video', icon: renderIcon(VideocamOutline) },
  { label: '音乐', key: 'media-audio', icon: renderIcon(MusicalNotesOutline) },
  { label: '图片', key: 'media-image', icon: renderIcon(ImageOutline) },
  // 以下 3 项原属「系统」组（「资源中心」已移出侧栏）
  { label: '监控', key: 'monitor', icon: renderIcon(StatsChartOutline) },
  { label: '终端', key: 'terminal', icon: renderIcon(TerminalOutline) },
  // 远程屏幕：后端开关默认关闭，关闭时页面会明确报 403，而不是白屏
  { label: '投屏', key: 'screen', icon: renderIcon(PhonePortraitOutline) },
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
/** 顶栏 WS 状态的原生 tooltip 文案（原来是 n-tooltip，见模板里的说明） */
const wsTooltip = computed(() => {
  const reason = wsStore.fatalReason ? `（${wsStore.fatalReason}）` : '';
  return `WebSocket ${wsStore.status}${reason}`;
});

const activeKey = computed(() => {
  const name = route.name as string;
  return route.meta.menuKey ?? (name || 'dashboard');
});

function findMenuLabel(options: MenuOption[], key: string): string | undefined {
  // 平铺后即一次 find；保留函数形态是为了调用点（currentTitle）无需改动。
  // 不得在此添加 children 递归：分组已经移除，递归只会掩盖新增的嵌套结构。
  return options.find((o) => o.key === key)?.label as string | undefined;
}

/**
 * 顶栏标题：**先看路由 meta.title**，再看菜单名。
 *
 * 子页（隧道、定时任务、诊断中心…）不在菜单中，若缺少 meta.title 会回落为 `UFI-AXIS` ——
 * 顶栏将无法表明当前页面。meta.title 用于这类页面；
 * 菜单页不写 title，仍使用菜单 label，避免同一名称维护两处。
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

/**
 * 侧栏菜单点击：直接切换路由。
 *
 * 不得在此加入 `setTimeout` / `nextTick` 之类的错帧补丁：白屏的根因是路由级
 * `<Transition>` 的 `out-in`（见上方模板注释），而不是 naive-ui 菜单 tooltip 的收尾竞态。
 * 把路由切换推迟到下一帧只会让点击显得迟钝，并在缺少浏览器验证时掩盖真正的错误来源。
 */
function handleMenuSelect(key: string) {
  drawerVisible.value = false;
  router.push({ name: key });
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

/** 多标签互斥监听的取消函数（在 onMounted 中赋值）。 */
let unsubTabLock: (() => void) | null = null;

/**
 * 设备后端（core）崩溃提醒。
 *
 * core 崩溃后由 keepalive 脚本 / START_STICKY 自动拉起，HTTP 很快恢复，web 侧此前无从感知，
 * 用户只能看到图表中断。此处进入页面时请求一次 `GET /api/service/crash`，
 * 时间戳与 localStorage 中「已提示过的」不同时弹窗一次。
 *
 * 去重放在本地，而不是由 core 记录 ack：app 与 web 是两个独立展示端，任一端先 ack 都会导致
 * 另一端永远看不到。失败一律静默（旧 core 没有该端点会返回 404）—— 这只是提醒，不应报错。
 *
 * 仅提示 `recent === true` 的崩溃：`crashed` 对三个月前的记录也恒为 true，
 * 仅依赖本地 localStorage 去重时，更换浏览器或清除站点数据会使旧崩溃重新弹窗；
 * 「是否值得提示」的 7 天窗口由 core 使用同一时钟判定（见 `ServiceRoutes.CRASH_NOTICE_WINDOW_MS`）。
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
  // 退出即释放「活跃标签页」锁，其它标签页不再被提示。
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
  removeRouteFade();
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
   （height:100% + overflow-x:hidden ⇒ overflow-y 计算为 auto）。该包裹层不是 flex 容器，
   因此下面的 .main-content{flex:1} 不起作用：内容区高度等于内容高度，顶栏以下的空白
   实际来自 .main-layout 自身的背景；滚动也发生在这层外层容器上，页面变长时顶栏会一并滚走。
   将其改为撑满的 flex 列后，内容区才真正占满顶栏以下的区域并独立滚动。 */
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

/* ── 路由级淡入 ──
   只有声明了 `meta.transition` 的路由会走到这里（见 router/index.ts 与
   下方的 `:css` 开关）。时长 160ms：比设置页内部那层（160/180ms）略快，
   因为整页切换的视觉位移更大，过慢会显得卡顿。

   离场必须是瞬时的（transition: none）：不使用 out-in 时新旧页面会同时存在于
   DOM，旧页面淡出会在中间态露出两层文字。入场仍为淡入。 */
.route-fade-enter-active {
  transition: opacity 0.16s ease;
}
.route-fade-leave-active {
  transition: none;
}
.route-fade-enter-from {
  opacity: 0;
}
@media (prefers-reduced-motion: reduce) {
  .route-fade-enter-active {
    transition: none;
  }
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
/* 返回键与汉堡键同尺寸，同样不得被标题压缩（标题在窄屏使用省略号） */
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

/* 天气 + 诗句：上下两行，右对齐。
   **固定预留宽度/高度**（不是按内容自然宽）：诗句比天气晚到，内容从 0 变有会把
   左侧音乐条整体挤动一下。min-width 与诗句的 max-width 阶梯同步，min-height 按两行
   20 + 2 + 14 ≈ 36px 预留；顶栏固定 56px 不会因此变高。 */
.header-status {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  /* 固定两行高度后必须顶对齐：否则诗句未到时天气在 36px 里居中，
     诗句一到就往上跳一行。flex-start 让天气始终占第一行。 */
  justify-content: flex-start;
  gap: 2px;
  min-width: 190px;
  min-height: 36px;
  flex: 0 1 auto;
}
/* 天气挂件在 ≤1180px 曾被整体 display:none —— 那是并排布局下竞争宽度的取舍。
   两行布局中它只占自己那一行，不会挤压诗句，因此在此恢复显示；
   `:deep` 选择器带两层类，优先级高于组件内的单类规则。 */
.header-status :deep(.weather-chip) {
  display: inline-flex;
  max-width: 100%;
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
@media (max-width: 1180px) {
  .header-status {
    min-width: 150px;
  }
}
@media (max-width: 1024px) {
  .header-status {
    min-width: 130px;
  }
}
@media (max-width: 900px) {
  .header-status {
    min-width: 110px;
  }
  /* 子页/终端已经有返回键和标题时，设备状态让位，避免窄屏横向挤压 */
  .header-left.has-back .header-device,
  .header-left.has-title .header-device {
    display: none;
  }
}
@media (max-width: 480px) {
  /* 两行状态块在 360 宽下最多占 104px：再宽会把 ws/主题/退出挤出去（顶栏是 overflow:hidden，
     挤出去的按钮点不到）。诗句自身还有 100px 的 max-width，两层约束取小。 */
  .header-status {
    min-width: 100px;
    max-width: 104px;
  }
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
  /* 菜单滚动到底后不应带动外层页面滚动 */
  overscroll-behavior: contain;
}

/* 220px 宽的侧栏中默认滚动条占用空间过多，收细并去掉轨道 */
.sider-menu-scroll::-webkit-scrollbar {
  width: 4px;
}
.sider-menu-scroll::-webkit-scrollbar-track {
  background: transparent;
}
/* 滚动条滑块：刻意使用中性灰半透明而不是 --border-subtle。
   该滑块需要同时覆盖浅色与深色侧栏，中性灰叠加在两种底色上均可见；
   若使用 --border-subtle，在暗色下会与背景难以区分（该变量本身是随明暗切换的低对比描边色）。
   取值已提取到 main.css 的 --scrollbar-thumb / --scrollbar-thumb-hover。 */
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
