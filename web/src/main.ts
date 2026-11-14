import { createApp } from 'vue';
import { createPinia } from 'pinia';
import App from './App.vue';
import { router } from './router';
import { reportFatal } from './composables/globalError';
import './styles/main.css';

const app = createApp(App);

/**
 * 全局错误边界（2026-10-30）。
 *
 * 改前没有这一层：任何一个组件在 setup / render / 生命周期里抛异常，Vue 只往控制台打一行
 * `[Vue warn]`，DOM 停在上一次成功渲染的状态 —— 用户看到的是空白页或半旧界面，
 * 页面上没有任何提示和出口，也拿不到可复制的错误信息。
 *
 * 这里只把**Vue 自己接住的**异常交给浮层（见 `composables/globalError.ts` 的分级说明）。
 * 注意 `info` 会告诉我们出错阶段（`render function` / `setup` / `watcher` …），
 * 排查时比堆栈本身更快定位。
 *
 * 2026-10-31：这里曾把 `TypeError: Cannot read properties of null (reading
 * 'parentNode' / 'subTree')` 当成 naive-ui 的动画收尾竞态直接吞掉。那个判断是错的：
 * 它是路由级 `<Transition mode="out-in">` 在离场收尾时重入渲染导致的崩溃，页面
 * 内容区会永久白屏 —— 错误被吞掉后用户连提示都看不到（见 DefaultLayout 的模板注释）。
 * 所以现在**不按错误文案做任何过滤**：渲染期异常一律交给浮层。
 */
app.config.errorHandler = (err, _instance, info) => {
  reportFatal(err, `vue:${info}`);
};

/**
 * 未被任何 try/catch 接住的同步异常。
 *
 * 不加 `capture: true`：资源加载失败（img / script 404）只在捕获阶段派发、不冒泡，
 * 它们不是「页面崩了」，不该弹浮层 —— 加上 capture 会把每张加载失败的封面图都算进来。
 */
window.addEventListener('error', (event) => {
  reportFatal(event.error ?? event.message, 'window.error');
});

/**
 * 未处理的 Promise 拒绝：**只记日志**，不弹浮层。
 *
 * 理由见 globalError.ts：后台轮询漏一个 catch 太常见，为一次网络失败糊住整个界面，
 * 比现在静默更糟。真正的接口错误由 useApi 的拦截器与各页 toast 负责。
 */
window.addEventListener('unhandledrejection', (event) => {
  console.error('[unhandledrejection]', event.reason);
});

app.use(createPinia());
app.use(router);

app.mount('#app');
// FOUC 防闪：挂载完成后显示
document.getElementById('app')?.classList.add('mounted');
