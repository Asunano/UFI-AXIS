<!--
  一条诊断检查的卡片（2026-10-14 新建）。

  ## 用途
  把 [DiagnosticItem] + 它的运行状态渲染成一张卡：标题、**归属角标**（core / 本端）、
  状态徽标、「运行 / 重跑」按钮、结论行、标签值表、原始输出（可折叠 + 可复制）。

  ## 为什么渲染器在这里而不是在页面里
  三种渲染器（`rows` / `verdict` / `text`）的差别是**同一种数据的不同侧重**，
  不是三种页面结构。放在页面里就等于页面要 `v-if="item.render === 'verdict'"` ——
  那正是"页面出现 id 分支"的第一步。这里认的是 `render` 这个**声明出来的枚举**，
  页面只负责 `v-for`。

  ## 折叠与展开的取舍
  · `text`（网络四条）：原始输出**就是结果**，默认展开且不给折叠开关 ——
    折起来的 ping 结果等于没跑。
  · 其余：默认折叠。`diagnose` 的原文有几十行，全展开会把下面的卡片挤到屏幕外，
    而它 99% 的时候只需要看上面那行结论。
  · 无论展开与否，「复制原始输出」都在：排障要的是**原文**，而复制按钮比
    "先展开、再手选、再 Ctrl+C" 短得多。

  ## 为什么没有 n-modal
  旧 `DiagnosticModal` 把结果放在弹窗里，是因为入口是网络页的一张卡、放不下。
  现在这是一整页，结果直接在卡片里展开 —— 少一层遮罩、少一次 trap-focus 的坑
  （`copyToClipboard` 的注释里记着那个坑）。
-->
<template>
  <GridCard :title="item.name">
    <template #extra>
      <!--
        归属角标（2026-10-14 补）：告诉用户"这一项另一端有没有"。
        判据是描述符的 `item.scope`（见 `diagnosticRegistry.ts` 文件头「归属」一节），
        跨端自检脚本核对的也是同一个字段 —— 所以这里**不认 id、也不认 group**。
        ★ 两档**刻意同色**（只用 --text-muted / --border-subtle）：用颜色区分就是让
        颜色承担语义，而本页的颜色已经全部给了状态徽标（正常 / 注意 / 失败 / 信息）；
        再加一套配色会让人以为角标也是状态，而且暗色/换肤下必然有一档看不清。
      -->
      <span class="dc-scope">{{ SCOPE_LABEL[item.scope] }}</span>
      <span v-if="state.result" class="dc-pill" :class="`dc-pill--${state.result.status}`">
        {{ STATUS_LABEL[state.result.status] }}
      </span>
      <span v-if="state.finishedAt > 0" class="dc-time">{{ finishedText }}</span>
      <n-button v-if="item.action" size="tiny" quaternary @click="goAction">{{ item.action.label }}</n-button>
      <!--
        写入动作（2026-10-14 补）：**只在这一条检查的结论说"有事可做"时才存在**
        （见 `DiagnosticWriteAction` 的 KDoc —— 它是结果的一部分，不是描述符的静态字段）。
        一律二次确认：popconfirm 的确认点击同时充当"用户手势"，浏览器通知授权要靠它。
        危险动作用错误色，且确认文案里已经写清"改什么 / 能不能撤销"。
      -->
      <n-popconfirm
        v-if="write"
        :type="write.danger ? 'error' : 'warning'"
        :positive-text="write.danger ? '确认执行' : '确认'"
        negative-text="取消"
        show-icon
        @positive-click="onWrite"
      >
        <template #trigger>
          <n-button size="tiny" :type="write.danger ? 'error' : 'primary'" secondary :loading="writing">
            {{ write.label }}
          </n-button>
        </template>
        <div class="dc-confirm">
          <p class="dc-confirm-title">{{ write.confirmTitle }}</p>
          <p class="dc-confirm-body">{{ write.confirmBody }}</p>
        </div>
      </n-popconfirm>
      <n-button size="tiny" quaternary :loading="state.running" :disabled="state.queued" @click="emit('run')">
        {{ state.result ? '重跑' : '运行' }}
      </n-button>
    </template>

    <!-- 一句话：这个检查回答什么问题。**永远显示** —— 折叠态下它是唯一的上下文。 -->
    <p class="dc-desc">{{ item.desc }}</p>

    <!-- 从没跑过 / 排队中 / 正在跑：三种"还没有结论"的状态要分得开，
         否则进页面的一瞬间 11 张卡全在转圈，看不出哪些是真的在跑。 -->
    <div v-if="!state.result && state.running" class="dc-note">正在运行…</div>
    <div v-else-if="!state.result && state.queued" class="dc-note">排队中（自动检查串行执行，前一条跑完才轮到）</div>
    <div v-else-if="!state.result" class="dc-note">
      {{ item.mode === 'manual' ? '尚未运行 —— 填好目标后点右上角运行' : '尚未运行' }}
    </div>

    <template v-else>
      <!-- verdict：主信息是一个判定，判定词要能一眼看到（root 通道 / SELinux / 服务状态） -->
      <div v-if="item.render === 'verdict'" class="dc-verdict" :class="`dc-verdict--${state.result.status}`">
        <span class="dc-verdict-word">{{ STATUS_LABEL[state.result.status] }}</span>
        <span class="dc-verdict-text">{{ state.result.summary }}</span>
      </div>
      <p v-else class="dc-summary">{{ state.result.summary }}</p>

      <div v-if="state.result.rows && state.result.rows.length" class="dc-rows">
        <!-- key 里带上下标：标签理论上可能重复（如 core 将来多回两张同名表），
             只用 label 当 key 会撞键并让 Vue 打警告。 -->
        <div v-for="(row, i) in state.result.rows" :key="`${i}-${row.label}`" class="dc-row">
          <span class="dc-row-label">{{ row.label }}</span>
          <span class="dc-row-value">{{ row.value }}</span>
        </div>
      </div>

      <template v-if="state.result.raw">
        <div class="dc-raw-bar">
          <!-- text 渲染器不给折叠开关：原文就是结果（见文件头） -->
          <n-button v-if="item.render !== 'text'" size="tiny" quaternary @click="rawOpen = !rawOpen">
            {{ rawOpen ? '收起原始输出' : '展开原始输出' }}
          </n-button>
          <span v-else class="dc-raw-title">原始输出</span>
          <n-button size="tiny" quaternary @click="copyRaw">复制原始输出</n-button>
        </div>
        <pre v-if="rawOpen" class="dc-raw">{{ state.result.raw }}</pre>
      </template>
    </template>
  </GridCard>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue';
import { useRouter } from 'vue-router';
import { useMessage } from 'naive-ui';
import GridCard from '@/components/GridCard.vue';
import { copyToClipboard } from '@/composables/utils';
import { reasonOf } from '../diagnosticRegistry';
import type { DiagnosticItem, DiagnosticScope, DiagnosticStatus } from '../diagnosticRegistry';
import type { DiagnosticRunState } from '../useDiagnosticRun';

const props = defineProps<{ item: DiagnosticItem; state: DiagnosticRunState }>();
const emit = defineEmits<{ (e: 'run'): void }>();

const router = useRouter();
const message = useMessage();

/**
 * 这一次结论给出的写入动作（可能没有）。直接读 `state.result` 而不是缓存一份：
 * 结果一换（重跑 / 补全之后自动重跑），按钮必须跟着换 —— 补完之后 `missing` 空了，
 * 动作就消失，这正是我们要的"补完按钮自己走开"。
 */
const write = computed(() => props.state.result?.write);

/** 写动作在途。用它锁住按钮：写动作**不是幂等**的（会真的跑 pm grant / 弹授权框）。 */
const writing = ref(false);

/**
 * 执行写动作。
 *
 * 三件事的顺序不能换：
 * ① 先锁按钮（`writing`）—— 连点两次会发两条 `pm grant`，第二次的结果会覆盖第一次的提示；
 * ② `run()` 必须在**用户手势的同一个 tick 里**发起：`Notification.requestPermission()`
 *    在无手势上下文会被浏览器直接拒绝。所以这里不 await 任何东西再调它；
 * ③ 成功之后**自动重跑这一条**：写动作改的是设备状态，而卡片上那份结论是写之前的。
 *    不重跑的话用户会看到"权限已补全"旁边还挂着"缺 3 项权限"。
 */
async function onWrite() {
  const action = write.value;
  if (!action || writing.value) return;
  writing.value = true;
  try {
    const text = await action.run();
    message.success(text);
    emit('run');
  } catch (e) {
    message.error(`执行失败：${reasonOf(e)}`);
  } finally {
    writing.value = false;
  }
}

/**
 * 状态徽标文案。**不用「成功 / 失败」**：`info` 这一档恰恰是"不是故障、但也不是正常"
 * （服务被用户主动停掉、shell 不是 root 但通道可用），叫它"失败"会把人引去修一个没坏的东西。
 */
const STATUS_LABEL: Record<DiagnosticStatus, string> = {
  ok: '正常',
  warn: '注意',
  fail: '失败',
  info: '信息',
};

/**
 * 归属角标的文案。`core` 直接用英文原文（它与 core 这个叫法在两端、在端点路径里
 * 都是同一个词，翻成"设备"反而会与分组标题「设备环境」撞车）；`client` 说「本端」——
 * "客户端"在这个页面上会被读成"core 的客户端"（那正是 app 与 web 的关系），
 * 而这里要表达的是"**这一端**才有"。
 */
const SCOPE_LABEL: Record<DiagnosticScope, string> = {
  core: 'core',
  client: '本端',
};

/** `text` 渲染器的原文默认展开；其余默认折叠（见文件头）。 */
const rawOpen = ref(props.item.render === 'text');

const finishedText = computed(() =>
  props.state.finishedAt > 0 ? new Date(props.state.finishedAt).toLocaleTimeString('zh-CN', { hour12: false }) : ''
);

/** 跳转用方法而不是在模板里写 `item.action.to`：`v-if` 收窄不到同一个元素的属性绑定上。 */
function goAction() {
  if (props.item.action) router.push(props.item.action.to);
}

async function copyRaw() {
  const raw = props.state.result?.raw;
  if (!raw) return;
  if (await copyToClipboard(raw)) message.success('已复制原始输出');
  else message.error('复制失败，请在下方原文里手动选中复制');
}
</script>

<style scoped>
/* ── 头部徽标与时间 ──
   颜色全部走令牌；四档与 main.css 的语义色一一对应，不新增写死取值。 */
.dc-pill {
  padding: 1px 8px;
  border-radius: var(--radius-pill);
  font-size: var(--font-sm);
  font-weight: 600;
  line-height: 1.6;
}
.dc-pill--ok {
  color: var(--success);
  background: var(--success-light);
}
.dc-pill--warn {
  color: var(--warning);
  background: var(--warning-light);
}
.dc-pill--fail {
  color: var(--error);
  background: var(--error-light);
}
.dc-pill--info {
  color: var(--accent-color);
  background: var(--accent-color-light);
}
.dc-time {
  font-size: var(--font-sm);
  color: var(--text-muted);
}

/* ── 归属角标（core / 本端）──
   两档同色：语义全在文字里，颜色不参与区分（理由见模板里的注释）。
   描边 + 次要色文字，与状态徽标（有底色）刻意长得不一样，免得被当成第二档状态。 */
.dc-scope {
  padding: 1px 8px;
  border: 1px solid var(--border-subtle);
  border-radius: var(--radius-pill);
  font-size: var(--font-sm);
  color: var(--text-muted);
}

/* ── 写动作的二次确认 ──
   标题与正文分开两行：确认框里"要做什么"和"代价是什么"必须一眼分得开。
   颜色只用令牌（正文次要色），不新增写死取值。 */
.dc-confirm {
  max-width: 320px;
}
.dc-confirm-title {
  margin: 0 0 var(--space-1);
  font-size: var(--font-base);
  font-weight: 600;
  color: var(--text-primary);
}
.dc-confirm-body {
  margin: 0;
  font-size: var(--font-sm);
  line-height: 1.6;
  color: var(--text-secondary);
  word-break: break-word;
}

/* ── 说明与"还没有结论" ── */
.dc-desc {
  margin: 0 0 var(--space-2);
  font-size: var(--font-base);
  color: var(--text-secondary);
  line-height: 1.6;
}
.dc-note {
  font-size: var(--font-base);
  color: var(--text-muted);
  padding: var(--space-2) 0;
}

/* ── 结论 ── */
.dc-summary {
  margin: 0;
  font-size: var(--font-md);
  color: var(--text-primary);
  line-height: 1.6;
}
/* verdict：左侧一条色带 + 放大的判定词。色带与徽标同源，四档一一对应。 */
.dc-verdict {
  display: flex;
  align-items: baseline;
  flex-wrap: wrap;
  gap: var(--space-2);
  padding: var(--space-2) var(--space-3);
  border-left: 3px solid var(--border-subtle);
  border-radius: var(--radius-sm);
  background: var(--surface-elevated);
}
.dc-verdict--ok {
  border-left-color: var(--success);
}
.dc-verdict--warn {
  border-left-color: var(--warning);
}
.dc-verdict--fail {
  border-left-color: var(--error);
}
.dc-verdict--info {
  border-left-color: var(--accent-color);
}
.dc-verdict-word {
  font-size: var(--font-lg);
  font-weight: 700;
  color: var(--text-primary);
}
.dc-verdict-text {
  flex: 1 1 200px;
  min-width: 0;
  font-size: var(--font-base);
  color: var(--text-secondary);
  line-height: 1.6;
}

/* ── 标签值表 ──
   与 InfoRow 的观感对齐（标签次要色、值主色），但这里是**表**而不是行组件：
   一条检查最多十几行，用 InfoRow 会把每一行都撑成 38px 的行高节奏。 */
.dc-rows {
  display: flex;
  flex-direction: column;
  gap: 2px;
  margin-top: var(--space-2);
}
.dc-row {
  display: flex;
  gap: var(--space-2);
  font-size: var(--font-base);
  line-height: 1.6;
}
.dc-row-label {
  flex: 0 0 128px;
  color: var(--text-muted);
}
/* 值可能是几十字符的 id 输出或路径：**折行，不省略号** ——
   排障要看的正是那串字符，截断了等于没给。 */
.dc-row-value {
  flex: 1;
  min-width: 0;
  color: var(--text-primary);
  word-break: break-all;
}

/* ── 原始输出 ── */
.dc-raw-bar {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: var(--space-2);
  margin-top: var(--space-3);
}
.dc-raw-title {
  font-size: var(--font-sm);
  font-weight: 500;
  color: var(--text-muted);
}
.dc-raw {
  margin: var(--space-2) 0 0;
  padding: var(--space-3);
  border-radius: var(--radius-sm);
  background: var(--code-bg);
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: var(--font-sm);
  line-height: 1.5;
  color: var(--text-primary);
  white-space: pre-wrap;
  word-break: break-all;
  /* 原文可能几十上百行：给个上限并内滚，否则单张卡就能占满整屏，
     下面的检查项全被推到屏幕外。 */
  max-height: 320px;
  overflow: auto;
}

@media (max-width: 768px) {
  /* 128px 的标签列在 298px 的卡内只剩 ~160px 给值，id 输出这类长串会一行两三个字符。
     窄屏改成上下两行。 */
  .dc-row {
    flex-direction: column;
    gap: 0;
  }
  .dc-row-label {
    flex: none;
  }
  .dc-raw {
    max-height: 50vh;
  }
}
</style>
