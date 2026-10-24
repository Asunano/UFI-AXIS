<!--
  诊断中心（2026-10-14 新建，路由 `/diagnostics`，**侧栏没有入口**，只从「设置 → 关于 → 诊断中心」进）。

  ## 用途
  把"设备自己现在是什么状态"与"这个目标通不通"两类检查收在一处，并支持**导出一份诊断包**。
  它取代了两处分散的东西：
    · 「关于」页那张 5 行的「诊断信息」卡（只够放四个字段，且塞不下会出网、有原始输出、能导出文件的界面）；
    · 网络页的「网络诊断」弹窗（ping / DNS / TCP / traceroute 四条，已随本次改动删除）。

  ## 为什么是独立路由而不是「关于」里的一个弹窗
  「关于」本身已经是设置页里的一个弹窗（`SettingsView` 的 `n-modal`），再往里套一层会同时踩三件事：
  ① 弹窗里的滚动区高度是 `calc(100vh - 220px)`，再嵌一层就没有可用高度；
  ② 网络四条**会真的向外部发包**，需要一个"我点了才跑"的显式边界，而弹窗一打开就全在跑；
  ③ 导出要生成文件下载，弹窗的焦点陷阱与遮罩对"另存为"这种浏览器级交互只会添乱。
  用户已就此拍板：关于页只放一颗入口，内容做独立路由。

  ## 本文件刻意只做四件事
  ① `v-for` 遍历 `DIAGNOSTIC_GROUPS` 渲染三段，每段里再 `v-for` 遍历该组的检查项
     —— **没有任何 id 分支**（没有 `item.id === 'cpu-probe'`），也没有按 group 写死的模板片段；
  ② 用户点「开始检查」时，把 `mode === 'auto'` 的检查排进串行队列跑一遍（**不自动开始**）；
  ③ 消费 runner 的状态（running / queued / result）传给卡片；
  ④ 一颗「导出诊断包」。
  检查项之间的差异（打哪个端点、怎么读成一句结论、用哪个渲染器、要不要跳转）
  全在 `diagnosticRegistry.ts` 的描述符里 —— **加一项检查不用改这个文件**。

  ## 为什么页面卸载时不取消任何在途请求
  与资源中心同一条决策（见 `useDiagnosticRun.ts` 的文件头）：`traceroute` 一条最长 120 秒，
  状态活在模块作用域，切页回来结果还在。用 `useCancellableApi()` 的话，
  "卸载中断"会 resolve 成 `data === undefined`，被读成"core 回了空响应"，
  一次切页就把结论写成假的"拿不到"。
-->
<template>
  <div class="diagnostics-view">
    <!-- 顶部说明与全局动作。不放进卡片：这两颗按钮管的是整页，不属于任何一条检查。 -->
    <div class="dx-toolbar sub-panel">
      <!--
        页面自己带一个标题，是因为**顶栏那个标题对本页不成立**：DefaultLayout 的
        `currentTitle` 是从侧栏 `menuOptions` 里按路由名查出来的，而本页刻意不挂菜单
        （低频排障页，见 router 的注释），查不到就回退成应用名「UFI-AXIS」——
        用户点了「诊断中心」却看到顶栏写着 UFI-AXIS，会怀疑自己点错了。
        `#/gallery` 是同样的处境（它也没标题，因为它只给改 UI 的人用）。
      -->
      <h2 class="dx-page-title">诊断中心</h2>
      <p class="dx-intro">
        设备环境与服务快照都是只读检查。不会自动开始：点上方「开始检查」才跑，一次只跑一条（串行）—— CPU 探针本身要 5~15
        秒，并发发出去只会把设备的 shell 通道打满、把彼此拖到超时。
        网络工具会真的向目标发包，所以要点哪条跑哪条。跑完可以导出一份 .txt 诊断包：
        包含全量快照、各检查项结果与最近日志，可以直接贴进 issue。
      </p>
      <!--
        归属角标的图例（2026-10-14 补）。**必须有这一句**：角标只有 `core` / `本端` 两个词，
        不解释一句没人知道它回答的是"**另一端有没有这一项**"（而不是"这一项重不重要"）。
        单独一段而不是并进上面那段：上面讲"这一页怎么用"，这段讲"角标怎么读"，
        两件事混在一段里读者会直接跳过。它也**只出现一次** —— 写在每张卡上就是噪声。
      -->
      <p class="dx-intro">
        每张卡右上角的角标说明这一项的归属：core = 查设备 / core 自己的状态，App 与网页端都有； 本端 =
        只在这一端才有（浏览器通知授权、本页的实时推送通道）。哪些检查两端都有，由 scripts/check-diagnostics-scope.mjs
        核对，缺一端会直接报错。
      </p>
      <div class="dx-toolbar-actions">
        <n-button size="small" :loading="busyCount > 0" @click="run.runAuto()">开始检查</n-button>
        <n-button size="small" type="primary" :loading="exporting" @click="onExport">导出诊断包</n-button>
      </div>
    </div>

    <section v-for="g in DIAGNOSTIC_GROUPS" :key="g.key" class="dx-group">
      <h3 class="dx-group-title">{{ g.title }}</h3>
      <p class="dx-group-desc">{{ g.desc }}</p>
      <!--
        该组共用的输入区。**页面不知道它是干什么的** —— 注册表说这一组有 toolbar，就渲染它。
        目前只有网络组有（四条工具共用一份目标地址，见 NetworkTargetBar 的注释）。
        `:is` 必须排在 `v-if` 之前：eslint 的 vue/attributes-order 把 `<component>` 的 `is`
        当作 DEFINITION 类属性（与 SettingsView 里同一条写法一致）。
      -->
      <component :is="g.toolbar" v-if="g.toolbar" />
      <div class="dx-cards">
        <DiagnosticCard
          v-for="item in itemsOfGroup(g.key)"
          :key="item.id"
          :item="item"
          :state="run.stateOf(item.id)"
          @run="run.runNow(item)"
        />
      </div>
    </section>
  </div>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue';
import { useMessage } from 'naive-ui';
import DiagnosticCard from './components/DiagnosticCard.vue';
import { DIAGNOSTIC_GROUPS, DIAGNOSTICS, itemsOfGroup, reasonOf } from './diagnosticRegistry';
import { useDiagnosticRun } from './useDiagnosticRun';
import { exportDiagnosticBundle } from './diagnosticExport';

const message = useMessage();
const run = useDiagnosticRun();

/**
 * 在**渲染之前**把每条检查的状态建出来。
 * `stateOf` 是惰性创建（会写响应式对象），在模板渲染期间首次调用会多触发一轮渲染；
 * 这里先建好，卡片 setup 读到的就是现成的那一份 —— 手法与 `ResourcesView` 逐字一致。
 */
for (const item of DIAGNOSTICS) {
  run.stateOf(item.id);
}

/** 有多少条还在跑或排队（顶部那颗「重跑」的 loading 用它，不必自己维护一个计数）。 */
const busyCount = computed(
  () => DIAGNOSTICS.filter((item) => run.stateOf(item.id).running || run.stateOf(item.id).queued).length
);

const exporting = ref(false);

/**
 * 导出诊断包。
 *
 * 包里写的是**屏幕上这份结果**，不是重跑一遍（理由见 `diagnosticExport.ts` 的文件头）。
 * 抓取失败已经在包内如实记录，所以这里 catch 到的只会是浏览器层的问题（blob / 下载被拦），
 * 那种情况必须弹出来 —— 否则用户以为文件已经存下来了。
 */
async function onExport() {
  if (exporting.value) return;
  exporting.value = true;
  try {
    const name = await exportDiagnosticBundle(run.snapshot());
    message.success(`已导出 ${name}`);
  } catch (e) {
    message.error(`导出失败：${reasonOf(e)}`);
  } finally {
    exporting.value = false;
  }
}
</script>

<style scoped>
.diagnostics-view {
  display: flex;
  flex-direction: column;
  gap: var(--space-5);
}

/* ── 顶部说明与动作 ──
   描边/内距/圆角/底色走 main.css 的全局 .sub-panel。 */
.dx-toolbar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-3);
}
/* 标题独占一行（见模板里的注释：顶栏那个标题对本页不成立） */
.dx-page-title {
  flex: 1 1 100%;
  margin: 0;
  font-size: var(--font-xl);
  font-weight: 600;
  color: var(--text-primary);
}
.dx-intro {
  flex: 1 1 420px;
  margin: 0;
  font-size: var(--font-base);
  color: var(--text-secondary);
  line-height: 1.7;
}
.dx-toolbar-actions {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-2);
}

/* ── 分组 ── */
.dx-group-title {
  margin: 0;
  font-size: var(--font-lg);
  font-weight: 600;
  color: var(--text-primary);
}
/* 标题前的一条短竖线：与设置页的分组标题同一手法（同一个视觉语言）。 */
.dx-group-title::before {
  content: '';
  display: inline-block;
  width: 3px;
  height: 14px;
  margin-right: var(--space-2);
  vertical-align: -2px;
  border-radius: 2px;
  background: var(--accent-color);
}
.dx-group-desc {
  margin: var(--space-1) 0 var(--space-3);
  font-size: var(--font-sm);
  color: var(--text-muted);
  line-height: 1.6;
}
/* 单列堆叠：诊断卡的正文（标签值表 / 原文）宽度差异很大，
   两列栅格会让 `diagnose` 那种几十行的原文卡把同行的卡压成一长条。 */
.dx-cards {
  display: flex;
  flex-direction: column;
  gap: var(--space-3);
}

@media (max-width: 768px) {
  .diagnostics-view {
    gap: var(--space-4);
  }
  .dx-toolbar-actions {
    /* 窄屏让两颗按钮占满整行：右对齐换行会排成阶梯状 */
    flex: 1 1 100%;
  }
}
</style>
