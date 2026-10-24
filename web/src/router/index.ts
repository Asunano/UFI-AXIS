import { createRouter, createWebHashHistory } from 'vue-router';
import type { RouteLocationRaw, RouteRecordRaw } from 'vue-router';
import { useAppStore } from '@/stores/app';

/**
 * 路由元信息的类型声明（2026-10-22）。
 *
 * 为什么需要：`tunnel` / `tasks` / `diagnostics` 这些页面在 2026-10-14 的侧栏瘦身里被
 * **从侧栏移除**、改由设置页的入口进入，但它们本身没有任何返回入口 —— 进了就出不来
 * （手机上尤其明显：侧栏是抽屉，用户不知道要点汉堡键）。壳层需要一个统一的"子页"协议：
 *
 *   · `title`   顶栏标题。不在 `menuOptions` 里的页面查不到菜单名，原来会回落成 `UFI-AXIS`；
 *   · `menuKey` 侧栏高亮项。子页应当点亮它的父入口（例如隧道页点亮「设置」），
 *               否则整条侧栏没有任何一项是选中的，用户失去"我在哪"的锚点；
 *   · `back`    返回目标。有浏览器上一页时 `router.back()`，直接打开链接/刷新时回落到这里。
 *
 * 声明在 `vue-router` 的 `RouteMeta` 上，`DefaultLayout` 里读 `route.meta.title` 才有类型。
 */
declare module 'vue-router' {
  interface RouteMeta {
    /** 公开路由（登录页），不受鉴权守卫拦截 */
    public?: boolean;
    /** 顶栏标题；不在 `menuOptions` 里的页面必须给 */
    title?: string;
    /** 侧栏高亮到哪个菜单项（子页指向父入口） */
    menuKey?: string;
    /** 返回目标：没有站内上一页时用它 */
    back?: RouteLocationRaw;
    /**
     * 网络诊断工具（2026-10-22）：`ping` / `dns` / `tcp` / `traceroute`。
     * 路由本身仍渲染网络页，`NetworkView` 读到它就在页面之上叠一个诊断弹窗 ——
     * 这样每条工具都有自己的 URL（可分享、可后退），又不需要把网络页的数据编排复制一份。
     */
    diagTool?: string;
    /**
     * 内容区全出血（2026-10-23）：去掉 `main-content` 的 24px/14px 内边距。
     * 音乐页需要一条贴视口底的播放条，并且沉浸层默认正好铺满**内容区**；
     * 保留壳层 padding 会让两者都缩进 24px，看起来像没对齐。
     * 只给媒体页用 —— 其它页面仍要靠这圈 padding 做卡片留白。
     */
    flush?: boolean;
  }
}

const routes: RouteRecordRaw[] = [
  {
    path: '/login',
    name: 'login',
    component: () => import('@/views/login/LoginView.vue'),
    meta: { public: true },
  },
  {
    path: '/',
    component: () => import('@/layouts/DefaultLayout.vue'),
    children: [
      { path: '', redirect: '/dashboard' },
      {
        path: 'dashboard',
        name: 'dashboard',
        component: () => import('@/views/dashboard/DashboardView.vue'),
      },
      {
        path: 'network',
        name: 'network',
        component: () => import('@/views/network/NetworkView.vue'),
      },
      // 网络诊断（2026-10-22）：四条工具各自独立路由，但都渲染网络页 ——
      // 进入时在页面之上叠一个弹窗（见 NetworkView 的 NetworkDiagnosticsModal）。
      // 选择器本身也有路由（network-diagnostics），这样关掉具体工具后退能回到四项列表。
      {
        path: 'network/diagnostics',
        name: 'network-diagnostics',
        component: () => import('@/views/network/NetworkView.vue'),
        meta: { title: '网络诊断', menuKey: 'network' },
      },
      {
        path: 'network/diagnostics/ping',
        name: 'network-diagnostics-ping',
        component: () => import('@/views/network/NetworkView.vue'),
        meta: { title: '网络诊断', menuKey: 'network', diagTool: 'ping' },
      },
      {
        path: 'network/diagnostics/dns',
        name: 'network-diagnostics-dns',
        component: () => import('@/views/network/NetworkView.vue'),
        meta: { title: '网络诊断', menuKey: 'network', diagTool: 'dns' },
      },
      {
        path: 'network/diagnostics/tcp',
        name: 'network-diagnostics-tcp',
        component: () => import('@/views/network/NetworkView.vue'),
        meta: { title: '网络诊断', menuKey: 'network', diagTool: 'tcp' },
      },
      {
        path: 'network/diagnostics/traceroute',
        name: 'network-diagnostics-traceroute',
        component: () => import('@/views/network/NetworkView.vue'),
        meta: { title: '网络诊断', menuKey: 'network', diagTool: 'traceroute' },
      },
      {
        path: 'device',
        name: 'device',
        component: () => import('@/views/device/DeviceView.vue'),
      },
      {
        path: 'sms',
        name: 'sms',
        component: () => import('@/views/sms/SmsView.vue'),
      },
      {
        // 短信转发已并入设置页的「通知」分栏（后续扩展为通知设置中心）。
        // 保留这条路由只为不让旧书签 404 —— core 的 /api/sms-forward/* 接口没有删。
        path: 'sms-forward',
        redirect: { name: 'settings', query: { tab: 'notify' } },
      },
      {
        path: 'tasks',
        name: 'tasks',
        component: () => import('@/views/tasks/TasksView.vue'),
        // 侧栏瘦身后只从「设置 › 更多功能」进入：需要标题、父入口高亮与返回目标（见 RouteMeta 的说明）
        meta: { title: '定时任务', menuKey: 'settings', back: { name: 'settings' } },
      },
      {
        path: 'monitor',
        name: 'monitor',
        component: () => import('@/views/monitor/MonitorView.vue'),
      },
      {
        path: 'terminal',
        name: 'terminal',
        component: () => import('@/views/terminal/TerminalView.vue'),
      },
      {
        path: 'alerts',
        name: 'alerts',
        component: () => import('@/views/alerts/AlertsView.vue'),
        // 2026-10-14 从侧栏移到监控页入口；与 tunnel/tasks 同类：需要标题、父入口高亮与返回目标
        meta: { title: '告警', menuKey: 'monitor', back: { name: 'monitor' } },
      },
      {
        path: 'apps',
        name: 'apps',
        component: () => import('@/views/apps/AppsView.vue'),
        // 唯一的入口在「设备」页的应用列表，侧栏同样没有这一项
        meta: { title: '应用管理', menuKey: 'device', back: { name: 'device' } },
      },
      {
        path: 'files',
        name: 'files',
        component: () => import('@/views/files/FilesView.vue'),
      },
      {
        path: 'downloads',
        name: 'downloads',
        component: () => import('@/views/downloads/DownloadsView.vue'),
      },
      {
        // 媒体库拆成三条独立路由，与 app 的 MEDIA_LIBRARY_VIDEO / _AUDIO / _IMAGE 一一对应。
        // 不做成"一页三个标签"：三类的列表版式、播放方式与页内状态都不同，
        // 合在一页时切标签要把上一类的播放器与 blob URL 全部收干净，那条清理链很容易漏。
        path: 'media',
        redirect: { name: 'media-video' },
      },
      {
        path: 'media/video',
        name: 'media-video',
        component: () => import('@/views/media/MediaVideoView.vue'),
      },
      {
        path: 'media/audio',
        name: 'media-audio',
        component: () => import('@/views/media/MediaAudioView.vue'),
        meta: { flush: true },
      },
      {
        path: 'media/image',
        name: 'media-image',
        component: () => import('@/views/media/MediaImageView.vue'),
      },
      {
        path: 'tunnel',
        name: 'tunnel',
        component: () => import('@/views/tunnel/TunnelView.vue'),
        meta: { title: '内网穿透', menuKey: 'settings', back: { name: 'settings' } },
      },
      {
        // 资源中心（2026-10-14 新建；2026-10-23 职责收窄）：**只负责插件组件**
        // （frpc / cloudflared）。Web 前端服务包、core 与 App 是「服务更新」，
        // 入口在 `settings?tab=update`（UpdatePanel）；两页互相有交叉入口，不再混在一起。
        //
        // 为什么单开一页而不是继续挂在隧道页里：插件的**取数与任务语义是同一套**
        // （提交 → 轮询 core 自报的状态 → 终态），而使用者跨页（隧道页两处「未安装」
        // 提示都指到这里）。页面本身只是 `COMPONENT_RESOURCES` 的 `v-for`。
        path: 'resources',
        name: 'resources',
        component: () => import('@/views/resources/ResourcesView.vue'),
        // 2026-10-22：侧栏移除该入口（仍可从设置「服务更新」与隧道页跳入）。
        // 没有菜单项就没有 label 可查，顶栏必须靠 meta.title；返回与高亮按「设置子页」处理。
        meta: { title: '资源中心', menuKey: 'settings', back: { name: 'settings' } },
      },
      {
        // 诊断中心（2026-10-14）：设备环境 / 网络工具 / 服务快照三段只读检查 + 导出诊断包。
        // **刻意不挂进侧栏 menuOptions**：它是从「设置 → 关于 → 诊断中心」进的低频排障页，
        // 放进侧栏会与「监控」抢位置，而侧栏刚因为"太多太杂"做过一轮瘦身。
        // 与 #/gallery 同一个做法：不挂菜单、靠 name 跳转，但仍然在 DefaultLayout 下，
        // 所以照样受登录守卫保护，不会变成免鉴权入口。
        path: 'diagnostics',
        name: 'diagnostics',
        component: () => import('@/views/diagnostics/DiagnosticsView.vue'),
        // 从「设置 › 关于 › 诊断中心」进入：返回时带上 tab=about，直接回到那个面板
        meta: {
          title: '诊断中心',
          menuKey: 'settings',
          back: { name: 'settings', query: { tab: 'about' } },
        },
      },
      {
        // 赞赏（2026-10-14）：两张赞赏码（左支付宝 / 右微信）+ 一段说明，**不放名单**。
        // 与下面的 sponsors 拆成两条路由而不是一页两栏：码要够大才扫得出来、名单会一直长，
        // 挤在一张卡里窄屏必然互相压（用户原话「防止内容挤压」）。两页都不挂侧栏。
        path: 'donate',
        name: 'donate',
        component: () => import('@/views/donate/DonateView.vue'),
        meta: {
          title: '赞赏',
          menuKey: 'settings',
          back: { name: 'settings', query: { tab: 'about' } },
        },
      },
      {
        // 赞助与感谢（2026-10-14）：只有赞助者名单（赞赏码 2026-10-14 拆去上面的 donate）。
        // 同样不挂侧栏 —— 它是「关于」里的一颗入口，不是日常导航的一站。
        path: 'sponsors',
        name: 'sponsors',
        component: () => import('@/views/sponsors/SponsorsView.vue'),
        meta: {
          title: '赞助与感谢',
          menuKey: 'settings',
          back: { name: 'settings', query: { tab: 'about' } },
        },
      },
      {
        path: 'settings',
        name: 'settings',
        component: () => import('@/views/settings/SettingsView.vue'),
      },
      {
        // 组件画廊：UI 改动的人工验收页。刻意**不挂进侧栏 menuOptions**（那是给用户的导航，
        // 这页是给改 UI 的人用的），靠 #/gallery 直接访问。仍在 DefaultLayout 下，
        // 所以照样受登录守卫保护，不会变成免鉴权入口。
        path: 'gallery',
        name: 'gallery',
        component: () => import('@/views/gallery/GalleryView.vue'),
        // 验收页同样不挂侧栏：给一个明确的出口，免得改 UI 的人也被困住
        meta: { title: '组件画廊', menuKey: 'settings', back: { name: 'settings' } },
      },
      {
        // 配对与授权已并入设置页分栏。同样保留旧路径做重定向。
        path: 'pairing',
        redirect: { name: 'settings', query: { tab: 'pairing' } },
      },
    ],
  },
  {
    path: '/:pathMatch(.*)*',
    redirect: '/dashboard',
  },
];

export const router = createRouter({
  history: createWebHashHistory(),
  routes,
});

// 路由守卫：未登录 → 跳转登录页
router.beforeEach((to) => {
  const appStore = useAppStore();
  if (!to.meta.public && !appStore.isAuthenticated) {
    return { name: 'login' };
  }
});
