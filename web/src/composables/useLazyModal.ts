/**
 * 懒加载弹窗的挂载 / 显隐时序。
 *
 * 为什么需要它：`<XxxModal v-if="show" v-model:show="show" />` 这种写法（本项目原来 5 处都是）
 * 会把进出场动画全吃掉 ——
 *   · 进场：v-if 与 show 同时变 true，组件首帧渲染时 `show` 已经是 true。
 *     Vue 的 `<Transition>` 默认不在首次挂载时播放动画（要 `appear`），所以直接硬切上屏。
 *   · 出场：show 变 false 的同一 tick，v-if 也把组件销毁了，退场动画根本没机会跑。
 *
 * 这里把两件事拆开：
 *   1. `open()` 先 await `loader()` 拿到组件本身，挂载一帧（此时 show 还是 false），
 *      等这帧渲染完成后才置 `show = true` —— 于是 show 是 false → true 的真实变化，动画正常播。
 *      **不用 `defineAsyncComponent`**：那东西即使模块已缓存也要多等一个微任务才 resolve，
 *      "组件挂上"与"show 置 true"的先后就变得不确定；这里直接持有解析后的组件，时序是确定的。
 *   2. 关闭时只置 `show = false`，延迟 [UNMOUNT_DELAY_MS] 再卸载。既播完退场动画，
 *      又保留「关掉就卸载」的原有好处 —— 自包含弹窗（基站信息、测速）内部的轮询与定时器
 *      依赖 onUnmounted 才会停，常驻挂载会让它们一直跑在后台。
 *
 * 用法：
 *   const cell = useLazyModal(() => import('./CellInfoModal.vue'));
 *   const cellComponent = cell.component;
 *   const cellShow = cell.show;
 *   ---
 *   <n-button @click="cell.open()">基站信息</n-button>
 *   <component :is="cellComponent" v-if="cellComponent" :show="cellShow" @update:show="cell.setShow" />
 */
import { ref, shallowRef, nextTick, onUnmounted, type Component, type ShallowRef } from 'vue';

/** naive-ui 弹窗的退场过渡约 300ms，留一点余量再卸载 */
const UNMOUNT_DELAY_MS = 350;

export function useLazyModal(loader: () => Promise<{ default: Component } | Component>) {
  /** 解析后的组件；null = 未加载/已卸载（对应模板里的 v-if） */
  // 显式标注类型：只写 shallowRef(null) 会被推断成 ShallowRef<null>，后面赋组件就报错
  const component: ShallowRef<Component | null> = shallowRef(null);
  /** 弹窗自身的显隐（对应 :show） */
  const show = ref(false);

  let unmountTimer: ReturnType<typeof setTimeout> | null = null;

  function cancelUnmount() {
    if (unmountTimer) {
      clearTimeout(unmountTimer);
      unmountTimer = null;
    }
  }

  async function open() {
    // 快速连点 / 关了立刻又开：先取消待执行的卸载，否则刚打开就被卸掉
    cancelUnmount();
    if (!component.value) {
      // 第二次之后命中模块缓存，没有额外网络开销
      const mod = (await loader()) as { default?: Component };
      component.value = mod.default ?? (mod as Component);
      // 让这一帧把组件挂上（show 仍是 false），下一步才有"从 false 变 true"可播
      await nextTick();
    }
    show.value = true;
  }

  /** 绑到弹窗的 `@update:show` 上 */
  function setShow(v: boolean) {
    if (v) {
      open();
      return;
    }
    show.value = false;
    cancelUnmount();
    unmountTimer = setTimeout(() => {
      component.value = null;
      unmountTimer = null;
    }, UNMOUNT_DELAY_MS);
  }

  onUnmounted(cancelUnmount);

  return { component, show, open, setShow };
}
