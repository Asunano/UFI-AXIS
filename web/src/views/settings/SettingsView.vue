<template>
  <div ref="shellEl" class="settings-shell">
    <!--
      分类导航与「设备」页同款：naive-ui 的 segment 分段标签。
      value 绑 ?tab= 而不是本地 ref：刷新、收藏、以及从旧的 /pairing、/sms-forward
      重定向过来都能直接落到对应分栏。
      n-tab-pane 默认 display-directive="if"：非激活分栏不渲染、切走即销毁，
      正好保持原来靠 :key 得到的行为 —— 每个面板在自己的 onMounted 里只拉自己的数据，
      不会一次并发 11 个请求，切走后的轮询定时器也有人清。
      不用 n-tabs 的 animated：它靠等高假设做横向位移过渡，而这里每个面板高度差很大、
      又是异步组件（挂载瞬间是空的），过渡期间会先塌成一小块再撑开，看起来就是闪一下。
      改用面板自己的淡入上浮（见 .n-tab-pane 的 pane-in），只动透明度和 4px 位移，不动布局。

      窄屏（≤768px）下这 10 个页签装不下一行，rail 会横向滚动（规则在 main.css，
      全站 segment 标签共用一份）。滚动带来一个新问题：靠 `?tab=backup` 直接进页面时，
      激活页签可能在视野之外 —— 用户看到的是一条停在「通用」的标签条，而内容却是备份页。
      所以挂载与切栏后都把激活项滚进视野，见 scrollActiveTabIntoView。
    -->
    <n-tabs type="segment" :value="activeKey" @update:value="select">
      <n-tab-pane v-for="c in CATEGORIES" :key="c.key" :name="c.key" :tab="c.label">
        <header class="pane-head">
          <h2 class="pane-title">{{ c.label }}</h2>
          <p class="pane-desc">{{ c.desc }}</p>
        </header>
        <component :is="c.component" />
      </n-tab-pane>
    </n-tabs>
  </div>
</template>

<script setup lang="ts">
import { computed, defineAsyncComponent, nextTick, onMounted, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';

const route = useRoute();
const router = useRouter();

const shellEl = ref<HTMLElement | null>(null);

interface Category {
  key: string;
  label: string;
  desc: string;
  component: unknown;
}

// 面板全部走 defineAsyncComponent：设置页首屏只加载被打开的那一栏，
// 「后端更新」「配对」这些重面板不进主 chunk。
const CATEGORIES: Category[] = [
  {
    key: 'general',
    label: '通用',
    desc: 'Token、设备后台连接、服务监听端口、日志开关、短信验证码与更新源',
    component: defineAsyncComponent(() => import('./panels/GeneralPanel.vue')),
  },
  {
    key: 'performance',
    label: '性能',
    desc: 'QoS 并发与缓存 TTL，以及当前实时状态',
    component: defineAsyncComponent(() => import('./panels/PerformancePanel.vue')),
  },
  {
    key: 'service',
    label: '服务',
    desc: '后台采集服务开关、开机自启、进程重启与响应缓存',
    component: defineAsyncComponent(() => import('./panels/ServicePanel.vue')),
  },
  {
    key: 'update',
    label: '更新',
    desc: 'Web 控制面板资源与 core 自更新，含手机 App 安装包',
    component: defineAsyncComponent(() => import('./panels/UpdatePanel.vue')),
  },
  {
    key: 'notify',
    label: '通知',
    desc: '邮件、Webhook 与本机短信三条通知渠道的配置与测试',
    component: defineAsyncComponent(() => import('./panels/NotifyPanel.vue')),
  },
  {
    key: 'uiExtras',
    label: '小功能',
    desc: '顶栏天气与每日诗词。配置存在设备端，与手机端共用同一份',
    component: defineAsyncComponent(() => import('./panels/UiExtrasPanel.vue')),
  },
  {
    key: 'pairing',
    label: '配对与授权',
    desc: '配对码、已配对设备与配对密码',
    component: defineAsyncComponent(() => import('./panels/PairingPanel.vue')),
  },
  {
    key: 'logs',
    label: '日志',
    desc: '调试日志查看与清理（开关在「通用」分栏）',
    component: defineAsyncComponent(() => import('./panels/LogsPanel.vue')),
  },
  {
    key: 'backup',
    label: '备份与恢复',
    desc: '导出配置备份包、从备份文件恢复',
    component: defineAsyncComponent(() => import('./panels/BackupPanel.vue')),
  },
  {
    key: 'about',
    label: '关于',
    desc: '版本、系统信息与诊断（只读）',
    component: defineAsyncComponent(() => import('./panels/AboutPanel.vue')),
  },
];

const DEFAULT_KEY = 'general';

// 非法 ?tab= 值回落到「通用」
const activeKey = computed(() => {
  const t = route.query.tab;
  const key = typeof t === 'string' ? t : '';
  return CATEGORIES.some((c) => c.key === key) ? key : DEFAULT_KEY;
});

function select(key: string) {
  if (key === activeKey.value) return;
  // replace 而不是 push：浏览器返回键应该离开设置页，而不是在分栏之间逐个回退
  router.replace({ name: 'settings', query: { ...route.query, tab: key } });
}

/**
 * 把激活页签滚进视野。**只在 rail 真的可滚动时才动**（窄屏横向滚动形态）。
 *
 * 为什么必须有：窄屏下 rail 横向滚动（规则在 main.css），而靠 `?tab=backup` 直接进页面时
 * 激活项可能排在第 9 位、完全在视野之外 —— 用户看到标签条停在「通用」、内容却是备份页，
 * 只会以为标签条坏了。
 *
 * 用 `scrollLeft` 手算而不是 `scrollIntoView`：后者在某些浏览器上会顺带把**整页**纵向滚一下
 * （元素在纵向也不完全可见时），进设置页就被莫名往下带一截。这里只改横向偏移，不碰纵向。
 *
 * `inline: 'center'` 的等价算法：把激活项中心对到 rail 可视区中心，再夹到合法滚动范围。
 */
async function scrollActiveTabIntoView() {
  await nextTick();
  const rail = shellEl.value?.querySelector<HTMLElement>('.n-tabs-rail');
  // 不可滚动（宽屏铺满整行）时什么都不做
  if (!rail || rail.scrollWidth <= rail.clientWidth) return;
  const active = rail.querySelector<HTMLElement>('.n-tabs-tab--active');
  if (!active) return;
  const target = active.offsetLeft + active.offsetWidth / 2 - rail.clientWidth / 2;
  rail.scrollTo({ left: Math.max(0, Math.min(target, rail.scrollWidth - rail.clientWidth)), behavior: 'smooth' });
}

onMounted(scrollActiveTabIntoView);
// 切栏后也要跟一次：点最右侧那几个页签时，它本身只露出一半
watch(activeKey, scrollActiveTabIntoView);
</script>

<style scoped>
.settings-shell {
  padding: 0;
}

/* 切分栏时面板淡入并轻微上浮。只动 opacity/transform，不参与布局，
   所以不会像 n-tabs 的 animated 那样在高度变化时闪一下。 */
.settings-shell :deep(.n-tab-pane) {
  animation: pane-in 0.22s cubic-bezier(0.4, 0, 0.2, 1);
}
@keyframes pane-in {
  from {
    opacity: 0;
    transform: translateY(4px);
  }
  to {
    opacity: 1;
    transform: none;
  }
}

.pane-head {
  margin-bottom: 16px;
  padding-bottom: 12px;
  border-bottom: 1px solid var(--border-subtle);
}
.pane-title {
  margin: 0;
  font-size: 16px;
  font-weight: 600;
  color: var(--text-primary);
}
.pane-desc {
  margin: 4px 0 0;
  font-size: 13px;
  color: var(--text-muted);
}
</style>
