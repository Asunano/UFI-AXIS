<template>
  <div class="terminal-view">
    <!--
      Tab 切换条（纯选择器）：
      对齐 Android AdvancedConsoleScreen 的 UfiScrollableTabRow —— 切换条与对话内容区
      是「上下兄弟」而非 n-tabs 把内容塞进 pane，从根本上避免 Naive tab-pane-wrapper
      高度链不收敛导致输入框无法吸底。pane 用空内容 + display-directive="if"（非激活不渲染、
      激活也是 0 高度），真正的布局放在下方 .console-body。
    -->
    <n-tabs
      type="segment"
      :value="activeTab"
      display-directive="if"
      class="console-tabbar"
      @update:value="(v: string) => (activeTab = v as 'at' | 'shell')"
    >
      <n-tab-pane name="at" tab="AT 命令" />
      <n-tab-pane name="shell" tab="Shell" />
    </n-tabs>

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
      <ConsoleMessageList :messages="activeMessages" :loading="activeSending" class="pane-list" />

      <!-- ── 快捷命令 ── -->
      <div class="quick-cmds">
        <n-tag v-for="q in activeQuick" :key="q.key" size="small" class="quick-tag" @click="runQuick(q.cmd)">
          {{ q.label }}
        </n-tag>
        <template v-if="activeTab === 'shell'">
          <span class="quick-sep">getprop:</span>
          <n-tag v-for="k in quickProps" :key="k" size="small" class="quick-tag" @click="runQuick('getprop ' + k)">
            {{ k }}
          </n-tag>
        </template>
      </div>

      <!-- ── 吸底输入栏（AT/Shell 共用，按 activeTab 绑数据）── -->
      <ConsoleInputBar
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
import { ref, reactive, computed, onMounted, onUnmounted } from 'vue';
import { useMessage } from 'naive-ui';
import { getApiClient } from '@/composables/useApi';
import { useWebSocketStore } from '@/stores/websocket';
import ConsoleMessageList from './components/ConsoleMessageList.vue';
import ConsoleInputBar from './components/ConsoleInputBar.vue';
import { nextId, ensureSeqAbove, type ConsoleMessage } from './components/types';

const api = getApiClient();
const message = useMessage();
const wsStore = useWebSocketStore();

// ── 当前 Tab（单布局：状态条/列表/输入栏都按它切数据）──
const activeTab = ref<'at' | 'shell'>('at');

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
// getprop 在 app 无对应 Tab，降级为 Shell 快捷命令，点击即作为普通命令送入同一消息流
const quickProps = ['ro.product.model', 'ro.build.version.release', 'ro.serialno', 'init.svc.adbd', 'gsm.network.type'];

const activeQuick = computed(() => (activeTab.value === 'at' ? quickAtCmds : quickShellCmds));

// ── 消息流（对话化）──
const atMessages = ref<ConsoleMessage[]>([]);
const shellMessages = ref<ConsoleMessage[]>([]);
const activeMessages = computed<ConsoleMessage[]>(() =>
  activeTab.value === 'at' ? atMessages.value : shellMessages.value
);

// ── AT 状态 ──
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

// ── AT 平台探测（GET /api/at/platform）──
const atPlatform = reactive({ connected: false, platform: '', method: '' });
const PLATFORM_LABELS: Record<string, string> = {
  SPREADTRUM: '展讯',
  QUALCOMM: '高通',
  UNKNOWN: '未知',
};
const atPlatformText = computed(() => {
  if (!atPlatform.connected || !atPlatform.platform) return '--';
  const key = atPlatform.platform.toUpperCase();
  return PLATFORM_LABELS[key] ? `${PLATFORM_LABELS[key]}（${key}）` : key;
});
const atMethodText = computed(() => (atPlatform.connected && atPlatform.method ? atPlatform.method : '--'));

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
function applyAtPlatform(data: any) {
  const connected = data?.connected === true;
  atPlatform.connected = connected;
  atPlatform.platform = connected ? String(data?.platform ?? '') : '';
  atPlatform.method = connected ? String(data?.method ?? '') : '';
}
async function loadAtStatus() {
  atStatusLoading.value = true;
  try {
    const [statusRes, platformRes] = await Promise.allSettled([api.get('/api/at/status'), api.get('/api/at/platform')]);
    if (platformRes.status === 'fulfilled') applyAtPlatform(platformRes.value.data);
    else applyAtPlatform(null);
    if (statusRes.status === 'rejected') throw statusRes.reason;
    const data = statusRes.value.data;
    atStatus.connected = data.connected ?? false;
    atStatus.platform = data.platform?.platform ?? '';
    atStatus.method = data.platform?.method ?? '';
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
});
// 另一端（或本端）执行命令后 core 会推 data_changed:console:<channel>，拉一次对齐
const unsubConsole = wsStore.on('data_changed', (payload: any) => {
  const changed = String(payload?.changed ?? '');
  if (changed === 'console:at') loadHistory('at');
  else if (changed === 'console:shell') loadHistory('shell');
});
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
.console-tabbar {
  flex: 0 0 auto;
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
/* 列表容器：flex:1 + min-height:0 ⇒ 占满剩余空间、内部滚动，输入栏自然钉在下方。
   本身再变成 flex 列，让内部的 n-scrollbar 用 flex:1/min-height:0 拿到确定的可用高度，
   避免「height:100% 百分比高度在 flex 链里循环依赖」导致列表随消息撑高、把输入栏顶出屏幕。 */
.pane-list {
  flex: 1 1 auto;
  min-height: 0;
  display: flex;
  flex-direction: column;
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
.quick-sep {
  font-size: 12px;
  color: var(--text-muted);
  margin-left: 4px;
}

/* ── 响应式 ── */
@media (max-width: 768px) {
  .status-info {
    gap: 6px;
  }
}
</style>
