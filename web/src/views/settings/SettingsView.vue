<template>
  <!--
    设置页 = 聚合页 + 内容区详情（2026-10-23）。
    应用侧栏保持不变（在 DefaultLayout），这里只改设置页内容区：

      聚合页：功能中心（定时任务 / 内网穿透 / 资源中心）+ 4 组设置入口 + 搜索
              + 右栏常用开关 / 快捷入口 / 设备状态。
      详情页：仍在同一个内容区，`?tab=<key>` 是唯一真相；返回只清 query，
              不弹 880×840 的 n-modal，也不在内容区里再造一条左侧导航。

    三个不能丢的既有行为：
      ① `?tab=<key>` 仍是"当前打开哪一项"的唯一真相（刷新 / 收藏 / 旧路由重定向）；
      ② 面板仍按需挂载、悬停预取、懒加载（PANEL_COMPONENTS / preloadPanel 原样保留）；
      ③ 面板组件本身不改：它们各自在卡片底部保存，详情页只负责把它整块放到内容区。
  -->
  <div ref="rootEl" class="settings-shell">
    <!-- ── 聚合页 ── -->
    <div v-if="!activeCategory" class="settings-home">
      <header class="page-head">
        <div>
          <div class="page-eyebrow">UFI-AXIS · 设置</div>
          <h1>设置</h1>
        </div>
        <div class="head-meta">
          <n-tag v-if="coreVersion" size="small" :bordered="false">v{{ coreVersion }}</n-tag>
        </div>
      </header>

      <div class="toolbar">
        <n-input
          v-model:value="query"
          class="search-input"
          size="large"
          clearable
          placeholder="搜索设置分栏或关键词，例如：端口、缓存、配对码、重启"
        >
          <template #prefix>
            <n-icon :size="17"><SearchOutline /></n-icon>
          </template>
        </n-input>
      </div>

      <!-- 功能中心：独立页面，不进入设置详情；状态标签来自 core，读不到就整颗不显示 -->
      <section v-show="!query.trim()" class="feature-band">
        <div class="band-head">
          <h2>功能中心</h2>
          <p>独立功能页面</p>
        </div>
        <div class="feature-grid">
          <button type="button" class="feature" @click="go('tasks')">
            <span class="feature-icon"
              ><n-icon :size="20"><TimerOutline /></n-icon
            ></span>
            <span class="feature-main">
              <span class="feature-title">
                定时任务
                <n-tag v-if="taskTag" size="small" :type="taskTagType" :bordered="false">{{ taskTag }}</n-tag>
              </span>
              <span class="feature-desc">按计划执行设备操作，如重启设备、开关 WiFi、切换网络制式</span>
            </span>
            <n-icon class="feature-go" :size="14"><ChevronForwardOutline /></n-icon>
          </button>

          <button type="button" class="feature" @click="go('tunnel')">
            <span class="feature-icon"
              ><n-icon :size="20"><GlobeOutline /></n-icon
            ></span>
            <span class="feature-main">
              <span class="feature-title">
                内网穿透
                <n-tag v-if="tunnelTag" size="small" :type="tunnelTagType" :bordered="false">{{ tunnelTag }}</n-tag>
              </span>
              <span class="feature-desc">通过 frp / Cloudflare 隧道将内网服务映射至公网</span>
            </span>
            <n-icon class="feature-go" :size="14"><ChevronForwardOutline /></n-icon>
          </button>

          <button type="button" class="feature" @click="go('resources')">
            <span class="feature-icon"
              ><n-icon :size="20"><CloudDownloadOutline /></n-icon
            ></span>
            <span class="feature-main">
              <span class="feature-title">
                资源中心
                <n-tag v-if="resourceTag" size="small" :type="resourceTagType" :bordered="false">{{
                  resourceTag
                }}</n-tag>
              </span>
              <span class="feature-desc">安装与更新 frpc、cloudflared 等插件组件</span>
            </span>
            <n-icon class="feature-go" :size="14"><ChevronForwardOutline /></n-icon>
          </button>
        </div>
      </section>

      <div class="home-grid">
        <div class="home-main">
          <section v-for="g in visibleGroups" :key="g.title" class="group-card">
            <div class="group-head">
              <div>
                <h2>{{ g.title }}</h2>
                <p>{{ g.desc }}</p>
              </div>
              <span class="group-count">{{ g.items.length }} 个分栏</span>
            </div>
            <div class="group-entries">
              <button
                v-for="c in g.items"
                :key="c.key"
                type="button"
                class="group-entry"
                @pointerenter="preloadPanel(c)"
                @focus="preloadPanel(c)"
                @click="open(c)"
              >
                <span class="entry-icon"
                  ><n-icon :size="17"><component :is="c.icon" /></n-icon
                ></span>
                <span class="entry-main">
                  <span class="entry-title">{{ c.label }}</span>
                  <span class="entry-desc">{{ c.desc }}</span>
                </span>
                <n-icon class="entry-chev" :size="14"><ChevronForwardOutline /></n-icon>
              </button>
            </div>
          </section>

          <div v-if="!visibleGroups.length" class="no-result">未找到匹配的设置，请尝试更短的关键词。</div>
        </div>

        <aside class="home-rail">
          <section class="rail-card">
            <div class="rail-head"><h3>常用开关</h3></div>
            <div class="rail-row">
              <span class="rail-label">后台服务</span>
              <n-switch
                size="small"
                :value="serviceStore.enabled"
                :loading="serviceStore.busy"
                :disabled="!serviceStore.loaded || serviceStore.busy"
                @update:value="toggleService"
              />
            </div>
            <div class="rail-row">
              <span class="rail-label">开机自启</span>
              <n-switch
                size="small"
                :value="serviceStore.autoStartOnBoot"
                :disabled="!serviceStore.loaded || serviceStore.busy"
                @update:value="toggleAutoStart"
              />
            </div>
            <div class="rail-row">
              <span class="rail-label">浏览器通知</span>
              <n-switch size="small" :value="browserNotifyOn" @update:value="toggleBrowserNotify" />
            </div>
          </section>

          <section class="rail-card">
            <div class="rail-head"><h3>快捷入口</h3></div>
            <!--
              这里是"跳到对应分栏"，不是即时动作。
              重启服务 / 导出备份这类动作各自有确认弹窗、口令或健康检查轮询，
              留在面板里执行；在聚合页放一颗"一键重启"只会绕过那些保护。
            -->
            <div class="quick-grid">
              <button v-for="q in QUICK_ENTRIES" :key="q.key" type="button" class="quick-btn" @click="openByKey(q.key)">
                <n-icon :size="14"><component :is="q.icon" /></n-icon>
                <span>{{ q.label }}</span>
              </button>
            </div>
          </section>

          <section class="rail-card">
            <div class="rail-head"><h3>设备状态</h3></div>
            <div class="rail-row">
              <span class="rail-label">后台服务</span><span class="rail-value">{{ serviceStateText }}</span>
            </div>
            <div class="rail-row">
              <span class="rail-label">已运行</span><span class="rail-value">{{ uptimeText }}</span>
            </div>
            <div class="rail-row">
              <span class="rail-label">存储</span><span class="rail-value">{{ storageText }}</span>
            </div>
            <div class="rail-row">
              <span class="rail-label">core</span><span class="rail-value">{{ coreVersion || '—' }}</span>
            </div>
          </section>
        </aside>
      </div>
    </div>

    <!-- ── 详情：同一内容区，不是弹窗 ── -->
    <div v-else class="settings-detail">
      <header class="detail-head">
        <n-button quaternary size="small" class="back-btn" @click="closeDetail">
          <template #icon>
            <n-icon :size="16"><ArrowBackOutline /></n-icon>
          </template>
          设置
        </n-button>
        <div class="detail-crumb">设置 / {{ activeGroup }}</div>
        <h1 class="detail-title">{{ activeCategory.label }}</h1>
        <p class="detail-desc">{{ activeCategory.desc }}</p>
      </header>
      <div class="detail-body">
        <component :is="activePanelComponent" v-if="activePanelComponent" />
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, defineAsyncComponent, nextTick, onMounted, ref, type Component } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { useMessage } from 'naive-ui';
import {
  OptionsOutline,
  SpeedometerOutline,
  ServerOutline,
  HardwareChipOutline,
  CloudDownloadOutline,
  NotificationsOutline,
  SparklesOutline,
  LinkOutline,
  DocumentTextOutline,
  ArchiveOutline,
  InformationCircleOutline,
  TimerOutline,
  GlobeOutline,
  SearchOutline,
  ChevronForwardOutline,
  ArrowBackOutline,
} from '@vicons/ionicons5';
import { Endpoints } from '@/api/contract';
import { getApiClient } from '@/composables/useApi';
import { disableBrowserNotify, enableBrowserNotify, isBrowserNotifyEnabled } from '@/composables/browserNotify';
import { useServiceStore } from '@/stores/service';
import PanelSkeleton from './PanelSkeleton.vue';
import { formatUptime } from './settingsShared';

interface Category {
  key: string;
  label: string;
  desc: string;
  /** 瓦片图标（ionicons5 组件） */
  icon: Component;
  /**
   * 面板模块的加载器。
   *
   * 这里**只存 loader、不存 defineAsyncComponent 实例**：同一份 loader 还要给悬停预取用
   * （见 preloadPanel），两处必须是同一个函数 —— 否则会打两次 chunk 请求。
   */
  load: () => Promise<{ default: Component }>;
}

// 面板只保留 loader：首屏只加载聚合页，重面板在被点开（或悬停预取）时才拉。
const CATEGORIES: Category[] = [
  {
    // key 保持 `general` 不变：`?tab=general` 是刷新/收藏/旧链接的落点。
    key: 'general',
    label: '连接与端口',
    desc: '设备后台连接（IP / 端口 / 密码）与服务监听端口',
    icon: OptionsOutline,
    load: () => import('./panels/GeneralPanel.vue'),
  },
  {
    key: 'performance',
    label: '性能',
    desc: 'QoS 并发与缓存 TTL、响应缓存管理，以及当前实时状态',
    icon: SpeedometerOutline,
    load: () => import('./panels/PerformancePanel.vue'),
  },
  {
    key: 'service',
    label: '服务',
    desc: '后台采集服务开关、开机自启与进程重启',
    icon: ServerOutline,
    load: () => import('./panels/ServicePanel.vue'),
  },
  {
    key: 'device',
    label: '设备',
    desc: '设备字段、后台密码与原始数据，以及重启、恢复出厂、关机等操作',
    icon: HardwareChipOutline,
    load: () => import('./panels/DevicePanel.vue'),
  },
  {
    key: 'update',
    label: '服务更新',
    desc: 'Web 前端服务与 core 的更新',
    icon: CloudDownloadOutline,
    load: () => import('./panels/UpdatePanel.vue'),
  },
  {
    key: 'notify',
    label: '通知',
    desc: '本浏览器的系统通知开关，以及邮件、Webhook、本机短信三条渠道',
    icon: NotificationsOutline,
    load: () => import('./panels/NotifyPanel.vue'),
  },
  {
    key: 'uiExtras',
    label: '小功能',
    desc: '顶栏天气与每日诗词；配置存储于设备端，与手机端共用',
    icon: SparklesOutline,
    load: () => import('./panels/UiExtrasPanel.vue'),
  },
  {
    key: 'pairing',
    label: '配对与授权',
    desc: '配对码、已配对设备与配对密码',
    icon: LinkOutline,
    load: () => import('./panels/PairingPanel.vue'),
  },
  {
    key: 'logs',
    label: '日志与排障',
    desc: '日志总开关与分级开关、排障开关，以及调试日志查看与清理',
    icon: DocumentTextOutline,
    load: () => import('./panels/LogsPanel.vue'),
  },
  {
    key: 'backup',
    label: '备份与恢复',
    desc: '导出配置备份包、从备份文件恢复',
    icon: ArchiveOutline,
    load: () => import('./panels/BackupPanel.vue'),
  },
  {
    key: 'about',
    label: '关于',
    desc: '版本、系统信息与诊断（只读）',
    icon: InformationCircleOutline,
    load: () => import('./panels/AboutPanel.vue'),
  },
];

/**
 * 面板的异步组件实例（按 key 建一次，之后复用）。
 *
 * 配 `loadingComponent` + `delay: 0`：点开立刻出骨架，chunk 到手即换真内容；
 * `onError` 重试两次，覆盖设备刚重启 / 浏览器刚清缓存时的 chunk 取失败。
 */
const PANEL_COMPONENTS = new Map<string, Component>(
  CATEGORIES.map((c) => [
    c.key,
    defineAsyncComponent({
      loader: c.load,
      loadingComponent: PanelSkeleton,
      delay: 0,
      onError: (_err, retry, fail, attempts) => {
        if (attempts <= 2) window.setTimeout(retry, 300);
        else fail();
      },
    }),
  ])
);

/** 已发起过预取的 key：预取只做一次，失败时删掉以便下次点击重试。 */
const preloadedKeys = new Set<string>();

function preloadPanel(c: Category) {
  if (preloadedKeys.has(c.key)) return;
  preloadedKeys.add(c.key);
  c.load().catch(() => preloadedKeys.delete(c.key));
}

/**
 * 分组视图。`items` 里的 key 必须在 [CATEGORIES] 里存在 —— 用一个断言式查找而不是
 * 把 Category 对象再抄一遍：抄一遍的后果是"某个面板的 label 改了，分组里还是旧的"。
 */
function pick(...keys: string[]): Category[] {
  return keys.map((k) => CATEGORIES.find((c) => c.key === k)).filter((c): c is Category => c !== undefined);
}

const GROUPS: Array<{ title: string; desc: string; items: Category[] }> = [
  {
    title: '后台服务',
    desc: '连接参数、采集服务启停、性能与排障配置',
    items: pick('general', 'service', 'performance', 'logs'),
  },
  {
    title: '设备',
    desc: '设备本体维护、配对与授权、危险操作',
    items: pick('device', 'pairing'),
  },
  {
    title: '版本与数据',
    desc: '服务更新、备份与恢复、版本与系统信息',
    items: pick('update', 'backup', 'about'),
  },
  {
    title: '提醒与外观',
    desc: '通知渠道与顶栏显示配置',
    items: pick('notify', 'uiExtras'),
  },
];

const QUICK_ENTRIES: Array<{ key: string; label: string; icon: Component }> = [
  { key: 'service', label: '服务控制', icon: ServerOutline },
  { key: 'update', label: '服务更新', icon: CloudDownloadOutline },
  { key: 'backup', label: '备份', icon: ArchiveOutline },
  { key: 'pairing', label: '配对码', icon: LinkOutline },
];

const route = useRoute();
const router = useRouter();
const message = useMessage();
const serviceStore = useServiceStore();

/** 搜索词：只过滤设置分栏，不过滤功能中心（搜索时功能中心先隐藏）。 */
const query = ref('');

/**
 * 当前打开的分栏 key（空串 = 聚合页）。
 *
 * 非法 `?tab=` 值一律当"没打开"处理，而不是回落到「通用」：
 * 直接进 /#/settings（没有 tab 参数）时不该自动打开一个面板。
 */
const activeKey = computed(() => {
  const t = route.query.tab;
  const key = typeof t === 'string' ? t : '';
  return CATEGORIES.some((c) => c.key === key) ? key : '';
});

const activeCategory = computed(() => CATEGORIES.find((c) => c.key === activeKey.value) ?? null);

const activeGroup = computed(() => {
  const cat = activeCategory.value;
  if (!cat) return '';
  return GROUPS.find((g) => g.items.some((i) => i.key === cat.key))?.title ?? '';
});

const activePanelComponent = computed<Component | null>(() => {
  const cat = activeCategory.value;
  return cat ? (PANEL_COMPONENTS.get(cat.key) ?? null) : null;
});

/** 搜索过滤：按分栏名 + 描述；组内没有匹配项时整组隐藏。 */
const visibleGroups = computed(() => {
  const term = query.value.trim().toLowerCase();
  return GROUPS.map((g) => ({
    ...g,
    items: g.items.filter((c) => !term || `${c.label} ${c.desc}`.toLowerCase().includes(term)),
  })).filter((g) => g.items.length > 0);
});

// ── 滚动位置：进详情回到顶部，返回时回到聚合页原来的位置 ──
const rootEl = ref<HTMLElement | null>(null);
let homeScrollTop = 0;

function getScroller(): HTMLElement | null {
  return (rootEl.value?.closest('.n-layout-scroll-container') as HTMLElement | null) ?? null;
}

async function open(c: Category) {
  if (c.key === activeKey.value) return;
  const scroller = getScroller();
  if (scroller) homeScrollTop = scroller.scrollTop;
  preloadPanel(c);
  await router.replace({ name: 'settings', query: { ...route.query, tab: c.key } });
  await nextTick();
  getScroller()?.scrollTo({ top: 0, behavior: 'auto' });
}

function openByKey(key: string) {
  const c = CATEGORIES.find((x) => x.key === key);
  if (c) void open(c);
}

/** 关闭详情：replace 而不是 push —— 浏览器返回键应该离开设置页，而不是逐个关详情。 */
async function closeDetail() {
  await router.replace({ name: 'settings', query: {} });
  await nextTick();
  getScroller()?.scrollTo({ top: homeScrollTop, behavior: 'auto' });
}

/** 功能中心是独立页面（不是设置详情），直接 push 路由。 */
function go(name: string) {
  router.push({ name });
}

// ── 功能中心 / 设备状态的数据 ──
const taskCount = ref<number | null>(null);
const tunnelCount = ref<number | null>(null);
const updateCount = ref<number | null>(null);
const coreVersion = ref('');
const storageText = ref('—');

const taskTag = computed(() => {
  if (taskCount.value === null) return '';
  return taskCount.value > 0 ? `已启用 ${taskCount.value} 项` : '未启用';
});
const taskTagType = computed<'default' | 'success'>(() => (taskCount.value ? 'success' : 'default'));

const tunnelTag = computed(() => {
  if (tunnelCount.value === null) return '';
  return tunnelCount.value > 0 ? `在线 ${tunnelCount.value} 条` : '未运行';
});
const tunnelTagType = computed<'default' | 'success'>(() => (tunnelCount.value ? 'success' : 'default'));

const resourceTag = computed(() => {
  if (updateCount.value === null) return '';
  return updateCount.value > 0 ? `${updateCount.value} 项可更新` : '已是最新';
});
const resourceTagType = computed<'default' | 'warning'>(() => (updateCount.value ? 'warning' : 'default'));

const serviceStateText = computed(() => {
  if (!serviceStore.loaded) return '未知';
  if (!serviceStore.enabled) return '已停止';
  return serviceStore.collecting ? '运行中' : '未采集';
});

const uptimeText = computed(() => (serviceStore.loaded ? formatUptime(serviceStore.uptimeMs) : '—'));

/**
 * 拉聚合页需要的只读状态。三个功能标签都是"取不到就整颗不显示"，不写死 0：
 * 旧 core / 网络失败时显示 0 会把"没读到"说成"没在用"。
 *
 * `/api/tunnel/status` 会 fork 进程探测版本（最长约 5s），所以放在页面渲染后异步拉，
 * 不阻塞聚合页首屏。
 */
async function loadAuxStatus() {
  const api = getApiClient();
  const [tasks, rules, tunnel, components, version, storage] = await Promise.allSettled([
    api.get(Endpoints.tasks.root),
    api.get(Endpoints.tasks.rules),
    api.get('/api/tunnel/status'),
    api.get(Endpoints.components.list),
    api.get(Endpoints.config.version),
    api.get(Endpoints.diagnostics.diskUsage),
  ]);

  // 「定时任务」页面有两个 tab（任务 / 自动化规则），状态标签把两边启用的都算上。
  let enabledSchedules: number | null = null;
  if (tasks.status === 'fulfilled') {
    const list = tasks.value.data?.tasks;
    if (Array.isArray(list)) {
      enabledSchedules = list.filter((t: any) => t?.enabled === true).length;
    }
  }
  if (rules.status === 'fulfilled') {
    const list = rules.value.data?.rules;
    if (Array.isArray(list)) {
      enabledSchedules = (enabledSchedules ?? 0) + list.filter((r: any) => r?.enabled === true).length;
    }
  }
  if (enabledSchedules !== null) taskCount.value = enabledSchedules;

  if (tunnel.status === 'fulfilled') {
    const data = tunnel.value.data;
    // 三种失败形态之一：HTTP 200 + success=false（见 tunnelShared 的说明）
    if (data && data.success !== false && (data.frp || data.cf_tunnel)) {
      tunnelCount.value = Number(data.frp?.running_count ?? 0) + Number(data.cf_tunnel?.running_count ?? 0);
    }
  }

  if (components.status === 'fulfilled') {
    const list = components.value.data?.components;
    if (Array.isArray(list)) {
      updateCount.value = list.filter((c: any) => c?.update_available === true).length;
    }
  }

  if (version.status === 'fulfilled') {
    const v = version.value.data?.version;
    if (typeof v === 'string' && v) coreVersion.value = v;
  }

  if (storage.status === 'fulfilled') {
    const total = Number(storage.value.data?.total ?? 0);
    const used = Number(storage.value.data?.used ?? 0);
    if (total > 0) {
      const gb = (n: number) => (n / 1024 ** 3).toFixed(1);
      storageText.value = `${gb(used)} / ${gb(total)} GB`;
    }
  }
}

// ── 常用开关：后台服务 / 开机自启走全局 service store；浏览器通知走 localStorage ──
const browserNotifyOn = ref(isBrowserNotifyEnabled());

async function toggleService(value: boolean) {
  await serviceStore.setEnabled(value);
  if (serviceStore.errorMessage) message.error(serviceStore.errorMessage);
  else message.success(value ? '后台采集已启动' : '后台采集已停止（HTTP 接口仍可用）');
}

async function toggleAutoStart(value: boolean) {
  await serviceStore.setAutoStart(value);
  if (serviceStore.errorMessage) message.error(serviceStore.errorMessage);
  else message.success(value ? '已开启开机自启' : '已关闭开机自启');
}

async function toggleBrowserNotify(value: boolean) {
  if (!value) {
    disableBrowserNotify();
    browserNotifyOn.value = false;
    message.success('已关闭浏览器通知');
    return;
  }
  try {
    const text = await enableBrowserNotify();
    browserNotifyOn.value = isBrowserNotifyEnabled();
    if (browserNotifyOn.value) message.success(text);
    else message.warning(text);
  } catch {
    browserNotifyOn.value = false;
    message.error('开启浏览器通知失败');
  }
}

onMounted(() => {
  // DefaultLayout 已经在 30s 巡检；这里补一次即时刷新，保证右栏开关和详情入口一致。
  void serviceStore.refresh();
  browserNotifyOn.value = isBrowserNotifyEnabled();
  void loadAuxStatus();
});
</script>

<style scoped>
.settings-shell {
  padding: 0;
}
.settings-home {
  width: min(1220px, 100%);
  margin: 0 auto;
}

/* ── 页面标题 ── */
.page-head {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 20px;
  padding: 0 2px 18px;
}
.page-eyebrow {
  margin-bottom: 6px;
  font-size: 11px;
  font-weight: 600;
  letter-spacing: 0.09em;
  color: var(--accent-color);
}
.page-head h1 {
  margin: 0;
  font-size: 28px;
  font-weight: 700;
  letter-spacing: -0.025em;
  line-height: 1.2;
  color: var(--text-primary);
}
.page-head p {
  max-width: 680px;
  margin: 6px 0 0;
  font-size: 13px;
  line-height: 1.65;
  color: var(--text-secondary);
}
.head-meta {
  display: flex;
  align-items: center;
  gap: 7px;
  padding-bottom: 2px;
}
.toolbar {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 18px;
}
.search-input {
  flex: 1;
  min-width: 0;
}

/* ── 功能中心 ── */
.feature-band {
  margin-bottom: 18px;
  padding: 13px 16px 15px;
  border: 1px solid var(--border-subtle);
  border-radius: var(--radius-md);
  background: linear-gradient(180deg, var(--card-bg), var(--surface-elevated));
}
.band-head {
  display: flex;
  align-items: baseline;
  gap: 9px;
  margin-bottom: 10px;
}
.band-head h2 {
  margin: 0;
  font-size: 13px;
  font-weight: 600;
  color: var(--text-primary);
}
.band-head p {
  margin: 0;
  font-size: 11.5px;
  color: var(--text-muted);
}
.feature-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 9px;
}
.feature {
  display: flex;
  align-items: center;
  gap: 11px;
  min-width: 0;
  padding: 11px 12px;
  border: 1px solid var(--border-subtle);
  border-radius: var(--radius-sm);
  background: var(--card-bg);
  text-align: left;
  cursor: pointer;
  transition:
    border-color 0.15s,
    box-shadow 0.15s,
    transform 0.15s;
}
.feature:hover {
  border-color: var(--accent-color);
  box-shadow: 0 10px 24px -22px var(--shadow-color-strong);
  transform: translateY(-1px);
}
.feature-icon {
  display: grid;
  flex: none;
  place-items: center;
  width: 40px;
  height: 40px;
  border-radius: 11px;
  background: var(--accent-color-light);
  color: var(--accent-color);
}
.feature-main {
  flex: 1;
  min-width: 0;
}
.feature-title {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  font-weight: 600;
  color: var(--text-primary);
}
.feature-desc {
  display: block;
  margin-top: 2px;
  overflow: hidden;
  font-size: 11px;
  line-height: 1.45;
  color: var(--text-muted);
  white-space: nowrap;
  text-overflow: ellipsis;
}
.feature-go {
  flex: none;
  color: var(--text-muted);
  opacity: 0.7;
}

/* ── 聚合主体 ── */
.home-grid {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 300px;
  gap: 18px;
  align-items: start;
}
.group-card {
  margin-bottom: 14px;
  padding: 15px 15px 9px;
  border: 1px solid var(--border-subtle);
  border-radius: var(--radius-md);
  background: var(--card-bg);
}
.group-head {
  display: flex;
  align-items: flex-start;
  gap: 12px;
  padding: 0 3px 8px;
}
.group-head h2 {
  margin: 0;
  font-size: 13.5px;
  font-weight: 600;
  color: var(--text-primary);
}
.group-head p {
  margin: 3px 0 0;
  font-size: 11.5px;
  line-height: 1.5;
  color: var(--text-muted);
}
.group-count {
  margin-left: auto;
  padding-top: 1px;
  font-size: 11px;
  color: var(--text-muted);
  white-space: nowrap;
}
.group-entries {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 2px 8px;
}
.group-entry {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
  padding: 9px 8px;
  border: 1px solid transparent;
  border-radius: var(--radius-sm);
  background: none;
  text-align: left;
  cursor: pointer;
  color: inherit;
}
.group-entry:hover {
  border-color: var(--border-subtle);
  background: var(--surface-hover);
}
.entry-icon {
  display: grid;
  flex: none;
  place-items: center;
  width: 34px;
  height: 34px;
  border-radius: 9px;
  background: var(--accent-color-light);
  color: var(--accent-color);
}
.entry-main {
  flex: 1;
  min-width: 0;
}
.entry-title {
  display: block;
  overflow: hidden;
  font-size: 12.5px;
  font-weight: 600;
  color: var(--text-primary);
  white-space: nowrap;
  text-overflow: ellipsis;
}
.entry-desc {
  display: block;
  margin-top: 2px;
  overflow: hidden;
  font-size: 11px;
  line-height: 1.45;
  color: var(--text-muted);
  white-space: nowrap;
  text-overflow: ellipsis;
}
.entry-chev {
  flex: none;
  color: var(--text-muted);
  opacity: 0.65;
}
.no-result {
  padding: 30px 16px;
  border: 1px solid var(--border-subtle);
  border-radius: var(--radius-md);
  background: var(--card-bg);
  color: var(--text-muted);
  font-size: 12.5px;
  text-align: center;
}

/* ── 右栏 ── */
.home-rail {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.rail-card {
  padding: 14px;
  border: 1px solid var(--border-subtle);
  border-radius: var(--radius-md);
  background: var(--card-bg);
}
.rail-head {
  margin-bottom: 8px;
}
.rail-head h3 {
  margin: 0;
  font-size: 12.5px;
  font-weight: 600;
  color: var(--text-primary);
}
.rail-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  padding: 9px 0;
  border-top: 1px solid var(--border-subtle);
  font-size: 12px;
}
.rail-row:first-of-type {
  border-top: 0;
}
.rail-label {
  color: var(--text-secondary);
}
.rail-value {
  color: var(--text-muted);
  font-size: 11.5px;
  text-align: right;
}
.quick-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 7px;
}
.quick-btn {
  display: flex;
  align-items: center;
  gap: 7px;
  min-height: 34px;
  padding: 0 9px;
  border: 1px solid var(--border-subtle);
  border-radius: var(--radius-sm);
  background: var(--surface-elevated);
  color: var(--text-secondary);
  font-size: 11.5px;
  text-align: left;
  cursor: pointer;
}
.quick-btn:hover {
  border-color: var(--accent-color);
  color: var(--accent-color);
}

/* ── 详情 ── */
.settings-detail {
  width: min(960px, 100%);
  margin: 0 auto;
  padding-bottom: 32px;
}
.detail-head {
  padding-bottom: 16px;
}
.back-btn {
  margin-bottom: 13px;
}
.detail-crumb {
  margin-bottom: 7px;
  font-size: 11px;
  color: var(--text-muted);
}
.detail-title {
  margin: 0;
  font-size: 24px;
  font-weight: 700;
  letter-spacing: -0.02em;
  color: var(--text-primary);
}
.detail-desc {
  margin: 5px 0 0;
  font-size: 12.5px;
  line-height: 1.6;
  color: var(--text-secondary);
}
.detail-body {
  min-width: 0;
}
/* 异步面板 chunk 还没到时，骨架也放在一张卡里：否则详情页只剩几根裸灰条。 */
.detail-body :deep(.panel-skeleton) {
  padding: 18px 20px;
  border: 1px solid var(--border-subtle);
  border-radius: var(--radius-md);
  background: var(--card-bg);
}

@media (max-width: 1080px) {
  .home-grid {
    grid-template-columns: minmax(0, 1fr);
  }
  .feature-grid {
    grid-template-columns: repeat(auto-fit, minmax(240px, 1fr));
  }
  .home-rail {
    display: grid;
    grid-template-columns: 1fr 1fr;
  }
}

@media (max-width: 720px) {
  .page-head {
    align-items: flex-start;
    padding-bottom: 14px;
  }
  .page-head h1 {
    font-size: 23px;
  }
  .page-head p {
    font-size: 12px;
  }
  .head-meta {
    display: none;
  }
  .feature-band {
    padding: 12px 12px 13px;
  }
  .feature-grid {
    grid-template-columns: 1fr;
  }
  .group-card {
    padding: 13px 10px 8px;
  }
  .group-entries {
    grid-template-columns: 1fr;
  }
  .home-rail {
    grid-template-columns: 1fr;
  }
  .settings-detail {
    padding-bottom: 24px;
  }
  .detail-title {
    font-size: 22px;
  }
}

/* 手机端：先看设备状态 / 常用开关 / 快捷入口，再看设置分组。
   桌面端仍保持「分组在左、rail 在右」，只有单列时把 rail 拆成三个前置卡片。 */
@media (max-width: 768px) {
  .home-grid {
    display: flex;
    flex-direction: column;
    align-items: stretch;
    gap: 14px;
  }
  .home-main {
    order: 4;
  }
  .home-rail {
    display: contents;
  }
  .home-rail .rail-card:nth-child(1) {
    order: 2;
  }
  .home-rail .rail-card:nth-child(2) {
    order: 3;
  }
  .home-rail .rail-card:nth-child(3) {
    order: 1;
  }
}
</style>
