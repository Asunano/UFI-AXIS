import { createRouter, createWebHashHistory } from 'vue-router';
import type { RouteRecordRaw } from 'vue-router';
import { useAppStore } from '@/stores/app';

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
      },
      {
        path: 'apps',
        name: 'apps',
        component: () => import('@/views/apps/AppsView.vue'),
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
        path: 'tunnel',
        name: 'tunnel',
        component: () => import('@/views/tunnel/TunnelView.vue'),
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
