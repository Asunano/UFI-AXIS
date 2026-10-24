<template>
  <div class="terminal-view">
    <!--
      顶部通道切换：完整复用短信页的样式与交互 —— Naive `n-tabs type="segment" animated`。
      2026-10-24：原来只有 tab 条，没有短信页那层白色卡片，segment 的浅灰轨道和页面底色
      几乎一样，激活胶囊滑动时看不出动画；现在用同样的白色卡面承载 tab 条。
      内容仍在下面的 .console-body（输入栏要吸底，不能塞进 pane），由 .pane-list
      自己做与短信 pane 相同的淡入/横向位移。
    -->
    <div class="console-tabs-card">
      <n-tabs v-model:value="activeTab" type="segment" animated class="console-tabs">
        <n-tab-pane name="at" tab="AT 命令" display-directive="if">
          <span class="pane-placeholder" aria-hidden="true"></span>
        </n-tab-pane>
        <n-tab-pane name="shell" tab="Shell" display-directive="if">
          <span class="pane-placeholder" aria-hidden="true"></span>
        </n-tab-pane>
        <!-- <n-tab-pane name="pty" tab="真 PTY" display-directive="if">
          <PtyPane v-if="activeTab === 'pty'" class="pane-list pty-host" />
        </n-tab-pane> （2026-10-10 暂时隐藏：整套 PTY 功能未完工） -->
      </n-tabs>
    </div>

    <!--
      对话主区：状态条(flex:0 0 auto) + 消息列表(flex:1 内部滚动) + 快捷命令(flex:0 0 auto)
      + 吸底输入栏(flex:0 0 auto)。列表装进 flex:1 / min-height:0 的容器，输入栏永远在
      剩余空间下方，切换 Tab / 快捷命令显隐都不会把输入框挤出可视区（对应 app 的
      Box(weight(1f)) + 同级输入栏）。
    -->
    <div class="console-body">
      <!-- ── 状态条 ── -->
      <div class="pane-status">
        <div class="status-info">
          <template v-if="activeTab === 'at'">
            <span class="status-dot" :class="{ active: atStatus.connected }"></span>
            <n-tag :type="atStatus.connected ? 'success' : 'error'" size="small" round>
              {{ atStatus.connected ? '已连接' : '未连接' }}
            </n-tag>
            <span v-if="atPlatformText !== '--'" class="status-platform">{{ atPlatformText }}</span>
            <span v-if="atMethodText !== '--'" class="status-platform">{{ atMethodText }}</span>
          </template>
          <template v-else>
            <n-tag :type="shellRoot.root ? 'warning' : 'default'" size="small" round>
              {{ shellRoot.root ? `特权 Shell${shellRoot.uid ? ' · ' + shellRoot.uid : ''}` : '普通 Shell（无特权）' }}
            </n-tag>
            <span v-if="shellRoot.method" class="status-platform">{{ shellRoot.method }}</span>
            <n-switch v-model:value="shellAsRoot" size="small" />
            <span class="opt-label">Root</span>
            <n-input-number v-model:value="shellTimeout" :min="1" :max="120" size="small" style="width: 110px" />
            <span class="opt-label">超时(s)</span>
          </template>
        </div>
        <div class="status-actions">
          <n-popconfirm @positive-click="clearActive">
            <template #trigger>
              <n-button size="small" tertiary>清空</n-button>
            </template>
            确认清空当前「{{ activeTab === 'at' ? 'AT 命令' : 'Shell' }}」历史？
          </n-popconfirm>
          <n-button
            size="small"
            :loading="activeTab === 'at' ? atStatusLoading : shellRootLoading"
            @click="activeTab === 'at' ? loadAtStatus() : loadShellRoot()"
          >
            刷新状态
          </n-button>
        </div>
      </div>

      <!-- ── 消息列表：flex:1 内部滚动 ── -->
      <!--
        真 PTY 面板（PTY 是流式全屏，没有"消息列表"概念，所以替代而非并列）。
        2026-10-10 随上面那个 tab 一并隐藏：tab 撤了、activeTab 默认也是 'at'，
        这行运行上已经不可达，但留着会让"藏干净了"这件事说不清 —— 一起注释掉，
        恢复时和 tab 一起放开。
      -->
      <!-- <PtyPane v-if="activeTab === 'pty'" class="pane-list pty-host" /> -->
      <!--
        每个 channel 一份独立的消息列表，首次访问才挂载；两份列表叠在同一个
        `.pane-stack` 里，只切透明度和横向位移，不切 display。

        2026-10-24：先前一个 ConsoleMessageList 在切 Tab 时换 messages，400 个气泡
        全部重建。拆成两份后仍用 display 切换，实测 800 个气泡的容器重新布局会有
        200–300ms 的长帧。改成绝对定位叠放 + 透明度/位移后，隐藏列表保持已有布局，
        切 Tab 不再触发整棵消息树重新布局；`visible` 仍负责切过去时滚到底。
        位移方向与短信页 n-tabs pane 一致：AT 从左侧进出，Shell 从右侧进出。
      -->
      <div class="pane-stack">
        <div
          class="pane-list"
          data-tab="at"
          :class="{ 'is-hidden': activeTab !== 'at' }"
          :aria-hidden="activeTab !== 'at'"
        >
          <ConsoleMessageList
            :visible="activeTab === 'at'"
            :messages="renderedAtMessages"
            :earlier-count="atEarlierCount"
            :loading="atSending"
            @load-earlier="loadEarlierAt"
          />
        </div>
        <div
          v-if="shellMounted"
          class="pane-list"
          data-tab="shell"
          :class="{ 'is-hidden': activeTab !== 'shell' }"
          :aria-hidden="activeTab !== 'shell'"
        >
          <ConsoleMessageList
            :visible="activeTab === 'shell'"
            :messages="renderedShellMessages"
            :earlier-count="shellEarlierCount"
            :loading="shellSending"
            @load-earlier="loadEarlierShell"
          />
        </div>
      </div>

      <!-- ── 快捷命令 ── -->
      <div v-if="activeTab !== 'pty'" class="quick-cmds">
        <n-tag v-for="q in activeQuick" :key="q.key" size="small" class="quick-tag" @click="runQuick(q.cmd)">
          {{ q.label }}
        </n-tag>
        <template v-if="activeTab === 'shell'">
          <span class="quick-sep">getprop:</span>
          <!--
            只读属性面板（2026-10-14 改）：值走 `GET /api/shell/prop/{key}` 并发取，
            不再把 `getprop xxx` 拼成命令送进 /api/shell/exec —— 那会在终端历史里
            记下一笔「用户从没敲过的命令」。整块只读，点标签不再执行任何东西。
          -->
          <n-tag v-for="k in quickProps" :key="k" size="small" class="prop-tag" :title="k">
            <span class="prop-key">{{ k }}</span>
            <span class="prop-value">{{ propValue(k) }}</span>
          </n-tag>
          <n-button size="tiny" quaternary :loading="quickPropsLoading" @click="loadQuickProps()"> 刷新属性 </n-button>
        </template>
      </div>

      <!-- ── 吸底输入栏（AT/Shell 共用，按 activeTab 绑数据）── -->
      <ConsoleInputBar
        v-if="activeTab !== 'pty'"
        v-model="activeCommand"
        :loading="activeSending"
        :placeholder="
          activeTab === 'at'
            ? '输入 AT 命令，如 AT+CSQ（Enter 发送 / Shift+Enter 换行）'
            : '输入 Shell 命令（Enter 发送 / Shift+Enter 换行）'
        "
        @send="sendActive"
      />
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted, onUnmounted, watch } from 'vue';
import { useMessage } from 'naive-ui';
import { getApiClient } from '@/composables/useApi';
import { useCancellableApi } from '@/composables/useCancellableApi';
import { Endpoints } from '@/api/contract';
import { useWebSocketStore } from '@/stores/websocket';
import ConsoleMessageList from './components/ConsoleMessageList.vue';
// 2026-10-10 随「真 PTY」tab 一并隐藏（整套功能未完工，2026-10 再做）。
// 用 `void` 占位而不是删掉 import：TS 严格模式会报 TS6133 未使用，但保留这一行
// 能让"恢复只需删掉 void、启用 tab 与面板"成为一处改动，组件文件也不会被顺手删掉。
import PtyPane from './components/PtyPane.vue';
void PtyPane;
import ConsoleInputBar from './components/ConsoleInputBar.vue';
import { nextId, ensureSeqAbove, type ConsoleMessage } from './components/types';

const api = getApiClient();
/**
 * 只读探测（getprop）单独用一个可取消客户端。
 *
 * 不能把它和上面那个 `api` 合成一个：`useCancellableApi` 会用作用域里的 signal
 * **覆盖**调用方传入的 signal，而 exec / at 那条链路靠自己的 AbortController 管取消
 * （见下方 activeControllers），两者混用会让「发送中取消」失效。
 * 分开之后，本页卸载时在途的属性请求会自动 abort，不需要手工登记。
 */
const readApi = useCancellableApi();
const message = useMessage();
const wsStore = useWebSocketStore();

// ── 当前 Tab（单布局：状态条/列表/输入栏都按它切数据）──
const activeTab = ref<'at' | 'shell' | 'pty'>('at');
/**
 * Shell 列表是否已挂载。首次切到 Shell 才挂载第二份 ConsoleMessageList；
 * 之后只切 v-show，不再销毁/重建气泡（见模板里那段注释）。
 */
const shellMounted = ref(false);
watch(
  activeTab,
  (v) => {
    if (v === 'shell') shellMounted.value = true;
  },
  { immediate: true }
);

// ── 快捷命令 ──
const quickAtCmds = [
  { key: 'AT+CSQ', cmd: 'AT+CSQ', label: '信号' },
  { key: 'ATI', cmd: 'ATI', label: '设备信息' },
  { key: 'AT+CGMI', cmd: 'AT+CGMI', label: '厂商' },
  { key: 'AT+CGMM', cmd: 'AT+CGMM', label: '型号' },
  { key: 'AT+CGSN', cmd: 'AT+CGSN', label: 'IMEI' },
  { key: 'AT+CIMI', cmd: 'AT+CIMI', label: 'IMSI' },
  { key: 'AT+CPIN?', cmd: 'AT+CPIN?', label: 'SIM' },
  { key: 'AT+COPS?', cmd: 'AT+COPS?', label: '运营商' },
  { key: 'AT+CFUN?', cmd: 'AT+CFUN?', label: '功能模式' },
  { key: 'AT+CGDCONT?', cmd: 'AT+CGDCONT?', label: 'APN' },
];
const quickShellCmds = [
  { key: 'ls /', cmd: 'ls /', label: 'ls /' },
  { key: 'ps', cmd: 'ps', label: 'ps' },
  { key: 'df -h', cmd: 'df -h', label: 'df -h' },
  { key: 'ifconfig', cmd: 'ifconfig', label: 'ifconfig' },
  { key: 'cat /proc/loadavg', cmd: 'cat /proc/loadavg', label: 'loadavg' },
  { key: 'free -m', cmd: 'free -m', label: 'free -m' },
  { key: 'uptime', cmd: 'uptime', label: 'uptime' },
  { key: 'dmesg | tail -20', cmd: 'dmesg | tail -20', label: 'dmesg' },
];
// ── 系统属性（只读探测）──
/**
 * getprop 快捷项：原来是「点一下就把 `getprop <key>` 送进 exec」，现在改成
 * **只读展示 + 并发读取**（2026-10-14）。两处取舍：
 *
 * ① 走 `GET /api/shell/prop/{key}` 而不是 `POST /api/shell/exec`：
 *    core 这条路由对 key 做白名单校验（只许 `[a-zA-Z0-9._]`，防命令注入），
 *    而 exec 是把拼好的字符串交给 shell、校验责任全在调用方；更要紧的是 exec
 *    会在终端历史里记一笔 —— 读几个只读属性不该把用户真正的操作淹掉。
 * ② 并发（Promise.allSettled）而不是逐个 await：5 个属性各走一轮 shell，
 *    串行会比并发明显慢一个数量级。
 *
 * 失败不弹 toast：设备离线 / 属性不存在都是常态，单条失败只影响它自己那格，
 * 显示 `—` 即可（整块失败也一样，不打断用户）。
 */
const quickProps = ['ro.product.model', 'ro.build.version.release', 'ro.serialno', 'init.svc.adbd', 'gsm.network.type'];
const propValues = ref<Record<string, string>>({});
const quickPropsLoading = ref(false);

/** 空值 / 读取失败统一显示 `—`：留白会让人分不清「没这个属性」和「没取到」 */
function propValue(key: string): string {
  return propValues.value[key] || '—';
}

async function loadQuickProps() {
  quickPropsLoading.value = true;
  try {
    const results = await Promise.allSettled(quickProps.map((key) => readApi.get(Endpoints.shell.prop(key))));
    const next: Record<string, string> = {};
    results.forEach((r, i) => {
      const key = quickProps[i];
      if (key === undefined) {
        return;
      }
      // value 是 getprop 的原始 stdout（含结尾换行），必须自己 trim
      next[key] = r.status === 'fulfilled' ? String(r.value.data?.value ?? '').trim() : '';
    });
    propValues.value = next;
  } finally {
    quickPropsLoading.value = false;
  }
}

const activeQuick = computed(() => (activeTab.value === 'at' ? quickAtCmds : quickShellCmds));

// ── 消息流（对话化）──
const atMessages = ref<ConsoleMessage[]>([]);
const shellMessages = ref<ConsoleMessage[]>([]);

/**
 * 首屏只渲染最近 80 条消息（约 40 条命令）。core 历史一次最多 200 条记录，
 * 全量铺成 DOM 会让 AT/Shell 各背几百个气泡，切 Tab 时重新布局必然卡。
 * 更早的部分保留在内存里，按「加载更早」逐段追加。
 */
const RENDER_MESSAGES = 80;
const atRenderLimit = ref(RENDER_MESSAGES);
const shellRenderLimit = ref(RENDER_MESSAGES);
const renderedAtMessages = computed(() => atMessages.value.slice(-atRenderLimit.value));
const renderedShellMessages = computed(() => shellMessages.value.slice(-shellRenderLimit.value));
const atEarlierCount = computed(() => Math.max(0, atMessages.value.length - renderedAtMessages.value.length));
const shellEarlierCount = computed(() => Math.max(0, shellMessages.value.length - renderedShellMessages.value.length));
function loadEarlierAt() {
  atRenderLimit.value = Math.min(atMessages.value.length, atRenderLimit.value + RENDER_MESSAGES);
}
function loadEarlierShell() {
  shellRenderLimit.value = Math.min(shellMessages.value.length, shellRenderLimit.value + RENDER_MESSAGES);
}

// ── AT 状态 ──
/**
 * AT 通道状态。**一份**，不再拆成两个同构对象：
 * 原来 `atStatus` 与 `atPlatform` 各存一份 `{connected, platform, method}`，而模板只用到了
 * `atStatus.connected` + `atPlatform.platform/method` —— `atStatus.platform/method` 从来没上过屏，
 * 两份状态各自维护的结果是同一个「通道在不在」有两个可能互相矛盾的副本。
 *
 * 两个端点的口径本来也不同，合并后按字段各取其源（见 `loadAtStatus`）：
 *   · `GET /api/at/status`  → `{ connected, platform: { platform, method } }`（嵌套）
 *   · `GET /api/at/platform`→ `{ connected, platform, method }`（扁平，显式带 connected）
 * `connected` 一律以 status 为准（它才是「通道在不在」）；平台/方式优先取 platform 端点。
 */
const atStatus = reactive({ connected: false, platform: '', method: '' });
const atStatusLoading = ref(false);
const atCommand = ref('');
const atSending = ref(false);

// ── Shell 状态 ──
const shellRoot = reactive({ root: false, uid: '', method: '' });
const shellRootLoading = ref(false);
const shellCommand = ref('');
const shellSending = ref(false);
const shellAsRoot = ref(true);
const shellTimeout = ref(10);

// 输入栏绑定：按 activeTab 切到对应命令 ref（切 Tab 不丢未发送内容，对齐 app 的提升输入态）
const activeCommand = computed<string>({
  get: () => (activeTab.value === 'at' ? atCommand.value : shellCommand.value),
  set: (v) => {
    if (activeTab.value === 'at') atCommand.value = v;
    else shellCommand.value = v;
  },
});
const activeSending = computed(() => (activeTab.value === 'at' ? atSending.value : shellSending.value));

// ── AT 平台探测（GET /api/at/platform，结果并入上面的 atStatus）──
const PLATFORM_LABELS: Record<string, string> = {
  SPREADTRUM: '展讯',
  QUALCOMM: '高通',
  UNKNOWN: '未知',
};
const atPlatformText = computed(() => {
  if (!atStatus.platform) return '--';
  const key = atStatus.platform.toUpperCase();
  return PLATFORM_LABELS[key] ? `${PLATFORM_LABELS[key]}（${key}）` : key;
});
const atMethodText = computed(() => atStatus.method || '--');

// ── 历史（core 存，app / web 共享同一份）──
// 旧版把历史存在 localStorage：换个浏览器、清一次缓存就没了，手机 app 更是完全看不见。
// 现在唯一真源是 core 的 console_history 表，本页只负责拉取与展示。
const HISTORY_MAX = 500;
const HISTORY_PAGE = 200;
const LEGACY_HISTORY_KEY = 'ufi.console.history';

/** 把 core 的一条记录摊成两个气泡（命令 + 输出），口径与 app 侧一致。 */
function recordToMessages(r: any): ConsoleMessage[] {
  const isShell = r.channel === 'shell';
  const commandText = isShell ? `${r.as_root ? '# ' : '$ '}${r.command}` : String(r.command ?? '');
  let out = '';
  if (r.stdout) out += r.stdout;
  if (r.stderr) out += (out ? '\n' : '') + (isShell ? '[stderr]\n' : '') + r.stderr;
  if (r.truncated) out += (out ? '\n' : '') + '[输出过长已截断]';
  // AT 没有退出码，core 落的是 null（刻意不用 0 顶替）
  if (r.exit_code !== null && r.exit_code !== undefined) out += `\n[exit: ${r.exit_code}]`;
  if (!out) out = r.ok ? '(无输出)' : '(无响应)';
  return [
    { id: r.id * 2, role: 'user', text: commandText, timestamp: r.created_at },
    { id: r.id * 2 + 1, role: r.ok ? 'assistant' : 'error', text: out, timestamp: r.created_at },
  ];
}

async function loadHistory(channel: 'at' | 'shell') {
  try {
    const { data } = await api.get('/api/console/history', { params: { channel, limit: HISTORY_PAGE } });
    const records = Array.isArray(data?.records) ? data.records : [];
    // core 返回最新在前，聊天式列表要按时间递增
    const messages: ConsoleMessage[] = records.slice().reverse().flatMap(recordToMessages);
    if (channel === 'at') atMessages.value = messages;
    else shellMessages.value = messages;
    ensureSeqAbove(messages.length ? Math.max(...messages.map((m) => m.id)) : 0);
  } catch {
    /* 拉不到就保留当前列表（可能有本次会话刚发的命令），不清空 */
  }
}

/** 旧版 localStorage 历史的一次性迁移：上传给 core 后清掉本地键。 */
async function migrateLegacyHistory() {
  let parsed: { at?: ConsoleMessage[]; shell?: ConsoleMessage[] } | null = null;
  try {
    const raw = localStorage.getItem(LEGACY_HISTORY_KEY);
    if (!raw) return;
    parsed = JSON.parse(raw);
  } catch {
    localStorage.removeItem(LEGACY_HISTORY_KEY);
    return;
  }
  const records = [...legacyToRecords(parsed?.at ?? [], 'at'), ...legacyToRecords(parsed?.shell ?? [], 'shell')];
  if (!records.length) {
    localStorage.removeItem(LEGACY_HISTORY_KEY);
    return;
  }
  try {
    await api.post('/api/console/history/import', { records });
    // 删本地键即幂等保证：下次进页面没有它就不会重复导入
    localStorage.removeItem(LEGACY_HISTORY_KEY);
  } catch {
    /* 保留本地键，下次进页面再试（设备离线时很可能走这条） */
  }
}

/** 旧记录只有 role/text，按「命令气泡 + 紧随其后的输出气泡」配对还原结构。 */
function legacyToRecords(list: ConsoleMessage[], channel: 'at' | 'shell') {
  const out: Record<string, unknown>[] = [];
  for (let i = 0; i < list.length; ) {
    const m = list[i];
    if (!m || m.role !== 'user') {
      i++;
      continue;
    }
    const asRoot = m.text.startsWith('# ');
    const command = m.text.replace(/^[#$] /, '');
    const next = list[i + 1];
    const reply = next && next.role !== 'user' ? next : null;
    out.push({
      channel,
      command,
      as_root: asRoot,
      stdout: reply?.text ?? '',
      ok: reply?.role === 'assistant',
      source: 'web',
      created_at: m.timestamp,
    });
    i += reply ? 2 : 1;
  }
  return out;
}

// ── 辅助 ──
function apiError(e: any, fallback = '请求失败'): string {
  const d = e?.response?.data;
  if (d?.error) return d.reason ? `${d.error}（${d.reason}）` : String(d.error);
  return e?.message || fallback;
}
function isCanceled(e: any): boolean {
  return (
    e?.name === 'CanceledError' || e?.code === 'ERR_CANCELED' || (e instanceof DOMException && e.name === 'AbortError')
  );
}

// ── 请求 abort（组件卸载时取消在途请求，避免响应落到已卸载组件）──
const activeControllers = new Set<AbortController>();
function appendAt(m: ConsoleMessage) {
  atMessages.value.push(m);
  if (atMessages.value.length > HISTORY_MAX) atMessages.value = atMessages.value.slice(-HISTORY_MAX);
}
function appendShell(m: ConsoleMessage) {
  shellMessages.value.push(m);
  if (shellMessages.value.length > HISTORY_MAX) shellMessages.value = shellMessages.value.slice(-HISTORY_MAX);
}

// ── AT 接口 ──
async function loadAtStatus() {
  atStatusLoading.value = true;
  try {
    const [statusRes, platformRes] = await Promise.allSettled([api.get('/api/at/status'), api.get('/api/at/platform')]);
    if (statusRes.status === 'rejected') throw statusRes.reason;
    const data = statusRes.value.data;
    atStatus.connected = data.connected ?? false;
    // 平台/方式：platform 端点成功时以它为准（它显式带 connected，未连接就是空值，
    // 不会把上一次的结果当成这一次的）；它整个请求失败时才退回 status 的嵌套字段。
    const p = platformRes.status === 'fulfilled' ? platformRes.value.data : null;
    const platform = p ? (p.connected === true ? p : null) : (data?.platform ?? null);
    atStatus.platform = String(platform?.platform ?? '');
    atStatus.method = String(platform?.method ?? '');
  } catch (e: any) {
    atStatus.connected = false;
    atStatus.platform = '';
    atStatus.method = '';
    message.error(apiError(e, '读取 AT 通道状态失败'));
  } finally {
    atStatusLoading.value = false;
  }
}
async function sendAtCommand(cmd?: string) {
  const command = (cmd ?? atCommand.value).trim();
  if (!command) return;
  // AT 命令回显（沿用终端风格前缀）
  appendAt({ id: nextId(), role: 'user', text: `$ ${command}`, timestamp: Date.now() });
  atSending.value = true;
  const controller = new AbortController();
  activeControllers.add(controller);
  try {
    const { data } = await api.post('/api/at/command', { command, source: 'web' }, { signal: controller.signal });
    appendAt({
      id: nextId(),
      role: data.success === false ? 'error' : 'assistant',
      text: data.response || '(无响应)',
      timestamp: Date.now(),
    });
    if (!cmd) atCommand.value = '';
  } catch (e: any) {
    if (isCanceled(e)) return;
    appendAt({ id: nextId(), role: 'error', text: `[错误] ${apiError(e)}`, timestamp: Date.now() });
    // 通道断开（503）时同步刷新顶部状态，避免状态栏仍显示「已连接」
    if (e?.response?.status === 503) loadAtStatus();
  } finally {
    activeControllers.delete(controller);
    atSending.value = false;
  }
}

// ── Shell 接口 ──
async function loadShellRoot() {
  shellRootLoading.value = true;
  try {
    const { data } = await api.get('/api/shell/root');
    shellRoot.root = data.root ?? false;
    // core 返回的 uid 是 `id` 命令的完整 stdout，取其中的 uid=N 片段展示
    const raw = String(data.uid ?? '').trim();
    shellRoot.uid = raw.match(/uid=\d+(\([^)]*\))?/)?.[0] || raw.split('\n')[0] || '';
    shellRoot.method = data.method ?? '';
  } catch (e: any) {
    shellRoot.root = false;
    shellRoot.uid = '';
    shellRoot.method = '';
    message.error(apiError(e, '读取 Shell 状态失败'));
  } finally {
    shellRootLoading.value = false;
  }
}
async function execShellCmd(cmd?: string) {
  const command = (cmd ?? shellCommand.value).trim();
  if (!command) return;
  const prefix = shellAsRoot.value ? '# ' : '$ ';
  appendShell({ id: nextId(), role: 'user', text: `${prefix}${command}`, timestamp: Date.now() });
  shellSending.value = true;
  const controller = new AbortController();
  activeControllers.add(controller);
  try {
    const { data } = await api.post(
      '/api/shell/exec',
      { command, as_root: shellAsRoot.value, timeout: shellTimeout.value, source: 'web' },
      { signal: controller.signal }
    );
    const exitCode = data.exit_code ?? (data.success ? 0 : -1);
    // stdout / stderr 合并为一条，stderr 段落前加标记，末尾追加 [exit: N]
    let text = '';
    if (data.stdout) text += data.stdout;
    if (data.stderr) text += (text ? '\n' : '') + '[stderr]\n' + data.stderr;
    text += `\n[exit: ${exitCode}]`;
    appendShell({
      id: nextId(),
      role: exitCode !== 0 ? 'error' : 'assistant',
      text,
      timestamp: Date.now(),
    });
    if (!cmd) shellCommand.value = '';
  } catch (e: any) {
    if (isCanceled(e)) return;
    appendShell({ id: nextId(), role: 'error', text: `[错误] ${apiError(e)}`, timestamp: Date.now() });
  } finally {
    activeControllers.delete(controller);
    shellSending.value = false;
  }
}

// ── Tab 无关的统一入口（输入栏 / 快捷命令都走这里）──
function sendActive() {
  if (activeTab.value === 'at') sendAtCommand();
  else execShellCmd();
}
function runQuick(cmd: string) {
  if (activeTab.value === 'at') sendAtCommand(cmd);
  else execShellCmd(cmd);
}
async function clearActive() {
  const channel = activeTab.value === 'at' ? 'at' : 'shell';
  try {
    // 历史在 core，清空对两端同时生效
    await api.delete('/api/console/history', { params: { channel } });
  } catch (e: any) {
    message.error(apiError(e, '清空历史失败'));
    return;
  }
  if (channel === 'at') atMessages.value = [];
  else shellMessages.value = [];
}

// ── 初始化 / 清理 ──
onMounted(async () => {
  await migrateLegacyHistory();
  loadHistory('at');
  loadHistory('shell');
  loadAtStatus();
  loadShellRoot();
  // 只读属性与 shell 状态同批探测：并发 5 个小请求，换来切到 Shell tab 时面板已是热的
  loadQuickProps();
  // 直接挂载隐藏的 Shell 列表；只渲染最近 80 条，切 Tab 时不再创建几百个气泡
  shellMounted.value = true;
});
// 另一端（或本端）执行命令后 core 会推 data_changed:console:<channel>，拉一次对齐
const unsubConsole = wsStore.on('data_changed', (payload: any) => {
  const changed = String(payload?.changed ?? '');
  if (changed === 'console:at') loadHistory('at');
  else if (changed === 'console:shell') loadHistory('shell');
});
// 2026-09-12：WS（重）连后补一次历史对齐。连上期间另一端命令靠 data_changed 实时同步，
// 但断线窗口内另一端敲的命令不会推过来，不补拉会显示成「两端历史不一致」。
watch(
  () => wsStore.status,
  (s, old) => {
    if (s === 'connected' && old && old !== 'connected') {
      loadHistory('at');
      loadHistory('shell');
    }
  }
);
onUnmounted(() => {
  activeControllers.forEach((c) => c.abort());
  activeControllers.clear();
  unsubConsole();
});
</script>

<style scoped>
.terminal-view {
  height: 100%;
  display: flex;
  flex-direction: column;
  gap: 12px;
  /* 不要给 min-height：矮视口下会被撑高、被 .main-content(overflow:hidden) 裁掉底部输入栏 */
}
.console-tabs-card {
  flex: 0 0 auto;
  padding: 10px 16px;
  background: var(--card-bg);
  border: 1px solid var(--border-subtle);
  border-radius: var(--radius-md);
}
.console-tabs {
  width: 100%;
  flex: none;
}
/* 终端内容在 n-tabs 外部；pane 里只放一个不可见占位，满足 Naive 的 tab 标签渲染路径。
   pane wrapper 也隐藏，避免空 pane 的 12px 顶部内距在 tab 条和状态条之间多出一段空白。 */
.pane-placeholder {
  display: none;
}
.console-tabs :deep(.n-tabs-pane-wrapper) {
  display: none;
}
/* ── 单布局：状态条 + 列表(滚) + 快捷 + 吸底输入栏 ── */
.console-body {
  flex: 1 1 auto;
  min-height: 0; /* 关键：让 flex 子项可以比内容矮，列表才能内部滚动 */
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.pane-status {
  flex: 0 0 auto;
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}
.status-info {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
  color: var(--text-secondary);
}
.status-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--text-muted);
}
.status-dot.active {
  background: var(--success);
}
.status-platform {
  color: var(--text-muted);
  font-size: 12px;
}
.status-actions {
  display: flex;
  gap: 8px;
}
.opt-label {
  font-size: 13px;
  color: var(--text-secondary);
}
/* 列表叠放容器：flex:1 + min-height:0 ⇒ 占满剩余空间、内部滚动，输入栏自然钉在下方。
   AT / Shell 两份列表都绝对定位到同一块区域，只切透明度和横向位移；隐藏那一份保持布局，
   切 Tab 不会让几百个气泡重新走一遍 layout。 */
.pane-stack {
  flex: 1 1 auto;
  min-height: 0;
  position: relative;
  /* 切 Tab 时两个列表做横向位移，超出部分在栈内裁掉 */
  overflow: hidden;
}
.pane-list {
  position: absolute;
  inset: 0;
  min-height: 0;
  display: flex;
  flex-direction: column;
  opacity: 0;
  visibility: hidden;
  pointer-events: none;
  transition:
    opacity 0.18s ease,
    transform 0.18s ease,
    visibility 0s linear 0.18s;
}
/* 与短信 n-tabs pane 同方向：旧页向左/右退出，新页从另一侧进入 */
.pane-list[data-tab='at'] {
  transform: translateX(-32px);
}
.pane-list[data-tab='shell'] {
  transform: translateX(32px);
}
.pane-list:not(.is-hidden) {
  opacity: 1;
  visibility: visible;
  pointer-events: auto;
  transform: translateX(0);
  transition:
    opacity 0.18s ease,
    transform 0.18s ease;
}
@media (prefers-reduced-motion: reduce) {
  .pane-list,
  .pane-list:not(.is-hidden) {
    transition: none;
  }
}
@media (max-width: 768px) {
  /* 与短信卡片在移动端的左右内距对齐，tab 轨道宽度保持一致 */
  .console-tabs-card {
    padding: 8px 12px;
  }
}

/* ── 快捷命令 ── */
.quick-cmds {
  flex: 0 0 auto;
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  align-items: center;
}
.quick-tag {
  cursor: pointer;
}
/* 属性标签是只读展示（没有 cursor: pointer，点了不执行任何东西）。
   n-tag 的内容被包在 .n-tag__content 里，key / value 的间距只能从那里给。 */
.prop-tag :deep(.n-tag__content) {
  display: inline-flex;
  align-items: baseline;
  gap: 6px;
}
.prop-key {
  color: var(--text-muted);
}
.prop-value {
  font-weight: 600;
  font-variant-numeric: tabular-nums;
}
.quick-sep {
  font-size: 12px;
  color: var(--text-muted);
  margin-left: 4px;
}

/* ── 响应式 ── */
@media (max-width: 768px) {
  .pane-status {
    gap: 8px;
  }
  .status-info {
    gap: 6px;
  }
  /* 768 档状态条要同时装下「连接态 + 清空 + 刷新状态」：平台名与提权方式是只读的
     次要属性（点一次「刷新状态」仍在，只是不常驻），先收起来 —— 不收的话
     `flex-wrap` 会把右侧两颗按钮挤到第二行，白吃掉一截本就不多的列表高度。 */
  .status-platform {
    display: none;
  }
}
</style>
