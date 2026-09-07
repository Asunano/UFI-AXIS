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
            <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="#1E293B" stroke-width="1.25" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
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
      <n-layout-header bordered class="h-14 flex items-center justify-between px-layout">
        <div class="flex items-center gap-3">
          <n-button v-if="isMobile" quaternary circle @click="drawerVisible = true">
            <template #icon
              ><n-icon :size="20"><MenuOutline /></n-icon
            ></template>
          </n-button>
          <span class="text-base font-medium">{{ currentTitle }}</span>
          <div class="flex items-center gap-3">
            <n-divider vertical class="nav-divider" />
            <DeviceTopBar />
          </div>
        </div>
        <div class="flex items-center gap-2">
          <!-- WS 状态指示 -->
          <n-tooltip>
            <template #trigger>
              <div class="flex items-center gap-1.5 px-2 py-1 rounded-md" :class="wsStatusClass">
                <div class="w-1.5 h-1.5 rounded-full" :class="wsDotClass"></div>
                <span class="text-xs">{{ wsStatusText }}</span>
              </div>
            </template>
            WebSocket {{ wsStore.fatalReason || wsStore.status }}
          </n-tooltip>
          <!-- 暗色模式 -->
          <n-button quaternary circle @click="appStore.toggleDarkMode()">
            <template #icon>
              <n-icon :size="18">
                <SunnyOutline v-if="appStore.darkMode" />
                <MoonOutline v-else />
              </n-icon>
            </template>
          </n-button>
          <!-- 退出 -->
          <n-button quaternary circle @click="handleLogout">
            <template #icon
              ><n-icon :size="18"><LogOutOutline /></n-icon
            ></template>
          </n-button>
        </div>
      </n-layout-header>

      <!-- 内容 -->
      <n-layout-content class="bg-page-bg main-content">
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
              <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="#1E293B" stroke-width="1.25" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
                <path d="M10 3.2a9 9 0 1 0 10.8 10.8a1 1 0 0 0 -1 -1h-3.8a4.1 4.1 0 1 1 -5 -5v-4a0.9 0.9 0 0 0 -1 -0.8" />
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
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted, h } from 'vue';
import { useRouter, useRoute } from 'vue-router';
import { NIcon, useDialog } from 'naive-ui';
import type { MenuOption } from 'naive-ui';
import {
  SpeedometerOutline,
  WifiOutline,
  HardwareChipOutline,
  FolderOpenOutline,
  DownloadOutline,
  MenuOutline,
  SunnyOutline,
  MoonOutline,
  LogOutOutline,
  ChatbubblesOutline,
  GlobeOutline,
  TimerOutline,
  StatsChartOutline,
  TerminalOutline,
  AlertCircleOutline,
  AppsOutline,
  SettingsOutline,
} from '@vicons/ionicons5';
import { useAppStore } from '@/stores/app';
import { useWebSocketStore } from '@/stores/websocket';
import { useDashboardStore } from '@/stores/dashboard';
import { useServiceStore } from '@/stores/service';
import { useWsTopics } from '@/composables/useRealtime';
import { getApiClient } from '@/composables/useApi';
import DeviceTopBar from '@/components/DeviceTopBar.vue';
import ServiceStoppedNotice from '@/components/ServiceStoppedNotice.vue';

const router = useRouter();
const route = useRoute();
const appStore = useAppStore();
const wsStore = useWebSocketStore();
const dashboardStore = useDashboardStore();
const serviceStore = useServiceStore();
const dialog = useDialog();

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
const isMobile = ref(false);
// 视口高度不足时压缩菜单项高度，让 14 个入口尽量一屏放下（不够仍可滚动）
const shortViewport = ref(false);

// 侧栏自动收起阈值：768–1024 属于"能放下侧栏但内容区会很挤"，默认收成图标条。
// 只在跨过阈值的那一刻改一次，之后用户手动展开/收起不会被 resize 事件顶回去。
const NARROW_BREAKPOINT = 1024;
let wasNarrow: boolean | null = null;

function checkMobile() {
  isMobile.value = window.innerWidth < 768;
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
  // 连接 WebSocket
  wsStore.connect(appStore.token, appStore.baseUrl);
  // 服务开关状态巡检（30s，只读 core 内存开关，不碰 goform）
  serviceStore.startWatch();
  // 加载 dashboard summary
  loadSummary();
  // core 崩溃提醒（崩溃后自动重启，前端否则完全无感）
  checkCoreCrash();
});

function renderIcon(icon: any) {
  return () => h(NIcon, null, { default: () => h(icon) });
}

// 矮屏把菜单项从默认 42px 压到 34px，14 个入口 + 品牌区约省 110px
const menuThemeOverrides = computed(() => (shortViewport.value ? { itemHeight: '34px' } : undefined));

const menuOptions = computed<MenuOption[]>(() => [
  { label: '仪表盘', key: 'dashboard', icon: renderIcon(SpeedometerOutline) },
  { label: '网络', key: 'network', icon: renderIcon(WifiOutline) },
  { label: '设备', key: 'device', icon: renderIcon(HardwareChipOutline) },
  { label: '短信', key: 'sms', icon: renderIcon(ChatbubblesOutline) },
  { label: '定时任务', key: 'tasks', icon: renderIcon(TimerOutline) },
  { label: '监控', key: 'monitor', icon: renderIcon(StatsChartOutline) },
  { label: '终端', key: 'terminal', icon: renderIcon(TerminalOutline) },
  { label: '告警', key: 'alerts', icon: renderIcon(AlertCircleOutline) },
  { label: '应用', key: 'apps', icon: renderIcon(AppsOutline) },
  { label: '文件', key: 'files', icon: renderIcon(FolderOpenOutline) },
  { label: '下载', key: 'downloads', icon: renderIcon(DownloadOutline) },
  { label: '内网穿透', key: 'tunnel', icon: renderIcon(GlobeOutline) },
  // 设置页自己有分栏导航（通用/性能/服务/更新/通知/配对与授权/日志/关于），
  // 所以这里不再挂折叠子菜单 —— 原来的「通用设置 / 配对与授权」两个子项已并入分栏。
  // 短信转发也并进了「通知」分栏，旧路由在 router 里重定向。
  { label: '设置', key: 'settings', icon: renderIcon(SettingsOutline) },
]);

const activeKey = computed(() => {
  const name = route.name as string;
  return name || 'dashboard';
});

function findMenuLabel(options: MenuOption[], key: string): string | undefined {
  for (const o of options) {
    if (o.key === key) return o.label as string;
    if (o.children) {
      const r = findMenuLabel(o.children as MenuOption[], key);
      if (r) return r;
    }
  }
  return undefined;
}
const currentTitle = computed(() => {
  return findMenuLabel(menuOptions.value, activeKey.value) || 'UFI-AXIS';
});

function handleMenuSelect(key: string) {
  router.push({ name: key });
  drawerVisible.value = false;
}

// WS 状态
const wsStatusClass = computed(() => {
  if (wsStore.status === 'connected') return 'bg-green-50 dark:bg-green-900/20';
  if (wsStore.status === 'connecting') return 'bg-yellow-50 dark:bg-yellow-900/20';
  return 'bg-gray-50 dark:bg-gray-800';
});
const wsDotClass = computed(() => {
  if (wsStore.status === 'connected') return 'bg-green-500';
  if (wsStore.status === 'connecting') return 'bg-yellow-500';
  return 'bg-gray-400';
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

/**
 * 设备后端（core）崩溃提醒（2026-09-04）。
 *
 * core 崩溃后由 keepalive 脚本 / START_STICKY 自动拉起，HTTP 很快恢复 —— web 此前完全无感，
 * 用户只看到图表断了一下。这里进页面时拉一次 `GET /api/service/crash`，
 * 时间戳与 localStorage 里"已提示过的"不同就弹一次。
 *
 * 去重放本地而不是让 core 记 ack：app 与 web 是两个独立展示端，谁先 ack 另一端就永远看不到。
 * 失败一律静默（老 core 没这个端点会 404）—— 这只是提醒，不该给用户报错。
 */
const CORE_CRASH_SHOWN_KEY = 'ufi_core_crash_shown_at';

async function checkCoreCrash() {
  try {
    const api = getApiClient();
    const { data } = await api.get('/api/service/crash');
    if (!data?.crashed || !data?.timestamp) return;
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
  router.push('/login');
}

// 清理
onUnmounted(() => {
  window.removeEventListener('resize', checkMobile);
  serviceStore.stopWatch();
  unsubDataChanged?.();
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
.sider-menu-scroll::-webkit-scrollbar-thumb {
  background: rgba(128, 128, 128, 0.3);
  border-radius: 2px;
}
.sider-menu-scroll:hover::-webkit-scrollbar-thumb {
  background: rgba(128, 128, 128, 0.5);
}
/* Firefox */
.sider-menu-scroll {
  scrollbar-width: thin;
  scrollbar-color: rgba(128, 128, 128, 0.3) transparent;
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
