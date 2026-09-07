<template>
  <div class="sms-view">
    <GridCard title="短信管理">
      <template #extra>
        <n-space :size="8" align="center">
          <template v-if="activeTab === 'messages'">
            <n-tag v-if="totalCount > 0" :bordered="false" size="small"> 共 {{ totalCount }} 条 </n-tag>
            <n-tag v-if="unreadCount > 0" type="info" :bordered="false" size="small"> {{ unreadCount }} 条未读 </n-tag>
            <n-button size="small" :loading="contactsLoading" @click="refreshAll"> 刷新 </n-button>
            <n-button v-if="unreadCount > 0" size="small" @click="markAllRead"> 全部已读 </n-button>
            <n-button size="small" type="primary" @click="showNewSms = true"> 新建短信 </n-button>
          </template>
          <template v-else>
            <n-tag v-if="codes.length > 0" :bordered="false" size="small"> 共 {{ codes.length }} 条 </n-tag>
            <n-button size="small" :loading="codesLoading" @click="loadVerificationCodes"> 刷新 </n-button>
          </template>
        </n-space>
      </template>

      <n-tabs v-model:value="activeTab" type="segment" animated class="sms-tabs">
        <!-- show:lazy：首次进入才渲染，之后用 display 切换，保留会话滚动位置与已加载消息 -->
        <n-tab-pane name="messages" tab="短信" display-directive="show:lazy">
          <div class="sms-body">
            <!-- Left panel: contact list -->
            <div class="contacts-panel" :class="{ hidden: selectedPhone && isMobile }">
              <div class="contacts-tabs">
                <div class="tab-item" :class="{ active: contactFilter === 'all' }" @click="contactFilter = 'all'">
                  全部
                </div>
                <div class="tab-item" :class="{ active: contactFilter === 'unread' }" @click="contactFilter = 'unread'">
                  未读{{ unreadCount > 0 ? ` (${unreadCount})` : '' }}
                </div>
              </div>
              <n-spin :show="contactsLoading && contacts.length === 0">
                <n-scrollbar class="contacts-scroll">
                  <div v-if="filteredContacts.length === 0 && !contactsLoading" class="panel-empty">
                    <n-empty :description="contactFilter === 'unread' ? '没有未读短信' : '暂无短信'" size="small" />
                  </div>
                  <div
                    v-for="c in filteredContacts"
                    :key="c.phone"
                    class="contact-item"
                    :class="{ active: selectedPhone === c.phone }"
                    @click="selectContact(c.phone)"
                  >
                    <div class="contact-main">
                      <div class="contact-phone">{{ c.phone }}</div>
                      <div class="contact-preview">{{ c.lastMessage || '暂无消息' }}</div>
                    </div>
                    <div class="contact-meta">
                      <span class="contact-time">{{ formatRelativeTime(c.lastTime) }}</span>
                      <n-badge v-if="c.unread > 0" :value="c.unread" :max="99" type="info" />
                    </div>
                  </div>
                </n-scrollbar>
              </n-spin>
            </div>

            <!-- Right panel: conversation -->
            <div class="conversation-panel" :class="{ visible: selectedPhone || isMobile }">
              <template v-if="selectedPhone">
                <!-- Conversation header -->
                <div class="conv-header">
                  <n-button v-if="isMobile" size="tiny" quaternary class="back-btn" @click="selectedPhone = ''">
                    &larr;
                  </n-button>
                  <span class="conv-phone">{{ selectedPhone }}</span>
                  <n-tag size="tiny" :bordered="false"> {{ conversationCount }} 条消息 </n-tag>
                </div>

                <!-- Messages -->
                <n-spin :show="messagesLoading && messages.length === 0">
                  <n-scrollbar ref="scrollbarRef" class="messages-scroll">
                    <div class="messages-list">
                      <div v-if="displayMessages.length === 0 && !messagesLoading" class="panel-empty">
                        <n-empty description="暂无消息记录" size="small" />
                      </div>
                      <div v-if="canLoadMore" class="load-more-row">
                        <n-button size="tiny" quaternary :loading="messagesLoading" @click="loadMore">
                          加载更早消息
                        </n-button>
                      </div>
                      <div v-else-if="hitPageCap" class="load-more-row load-more-hint">
                        已加载最近 {{ MAX_PAGE }} 条（后端单次上限），更早消息请在设备端查看
                      </div>
                      <div v-for="msg in displayMessages" :key="msg.id" class="msg-row" :class="msg.direction">
                        <div class="msg-bubble" @contextmenu.prevent="openMsgMenu($event, msg)">
                          <div class="msg-content">{{ msg.content }}</div>
                          <div class="msg-time">
                            <span>{{ formatRelativeTime(msg.timestamp) }}</span>
                            <span v-if="msg.direction === 'outgoing'" class="msg-dir-tag">已发</span>
                          </div>
                        </div>
                      </div>
                    </div>
                  </n-scrollbar>

                  <!-- 单个右键菜单实例（此前每条气泡各渲染一个 n-dropdown） -->
                  <n-dropdown
                    :show="!!msgMenuTarget"
                    :options="msgMenuOptions"
                    trigger="manual"
                    placement="bottom-start"
                    :x="msgDropdownX"
                    :y="msgDropdownY"
                    @select="handleMsgAction"
                    @clickoutside="msgMenuTarget = null"
                  />
                </n-spin>

                <!-- Message input -->
                <div class="msg-input-bar">
                  <n-input
                    v-model:value="newMessage"
                    placeholder="输入短信内容..."
                    type="textarea"
                    :autosize="{ minRows: 1, maxRows: 4 }"
                    @keydown.enter.exact.prevent="sendMessage"
                  />
                  <n-button
                    type="primary"
                    :loading="sending"
                    :disabled="!newMessage.trim()"
                    class="send-btn"
                    @click="sendMessage"
                  >
                    发送
                  </n-button>
                </div>
              </template>

              <template v-else>
                <div class="panel-empty conv-placeholder">
                  <n-empty description="选择一个联系人查看短信" size="large" />
                </div>
              </template>
            </div>
          </div>
        </n-tab-pane>

        <n-tab-pane name="codes" tab="验证码">
          <div class="codes-body">
            <n-spin :show="codesLoading && codes.length === 0">
              <n-scrollbar class="codes-scroll">
                <div v-if="codes.length === 0 && !codesLoading" class="panel-empty">
                  <n-empty description="暂无验证码" size="small" />
                </div>
                <div v-else class="codes-list">
                  <div v-for="c in codes" :key="c.msgId" class="code-item">
                    <div class="code-value">{{ c.code }}</div>
                    <div class="code-meta">
                      <span class="code-source">{{ c.source || '未知来源' }}</span>
                      <span class="code-dot">·</span>
                      <span>{{ formatFullTime(c.timestamp) }}</span>
                      <n-tag v-if="c.keyword" size="tiny" :bordered="false">{{ c.keyword }}</n-tag>
                    </div>
                    <div class="code-snippet">{{ c.snippet }}</div>
                    <div class="code-actions">
                      <n-button size="tiny" @click="copyCode(c.code)">复制</n-button>
                      <n-button size="tiny" quaternary @click="viewOriginal(c.msgId)"> 查看原文 </n-button>
                    </div>
                  </div>
                </div>
              </n-scrollbar>
            </n-spin>
          </div>
        </n-tab-pane>
      </n-tabs>
    </GridCard>

    <!-- New SMS modal -->
    <n-modal
      v-model:show="showNewSms"
      preset="dialog"
      title="新建短信"
      positive-text="发送"
      negative-text="取消"
      style="width: 440px"
      @positive-click="sendNewSms"
    >
      <div class="new-sms-form">
        <div class="form-row">
          <span class="form-label">手机号</span>
          <n-input v-model:value="newPhone" placeholder="输入手机号码" />
        </div>
        <div class="form-row">
          <span class="form-label">短信内容</span>
          <n-input v-model:value="newSmsContent" placeholder="输入短信内容" type="textarea" :rows="4" />
        </div>
      </div>
    </n-modal>

    <!-- 验证码原文弹窗 -->

    <n-modal v-model:show="showRawSms" preset="card" title="短信原文" style="width: 440px">
      <n-spin :show="rawLoading">
        <div v-if="rawSms" class="raw-sms">
          <InfoRow label="手机号" :value="rawSms.phoneNumber || '未知'" />
          <InfoRow label="方向" :value="rawDirectionLabel" />
          <InfoRow label="时间" :value="formatFullTime(rawSms.timestamp)" />
          <div class="raw-content">{{ rawSms.content }}</div>
        </div>
        <div v-else-if="!rawLoading" class="panel-empty">
          <n-empty description="无法获取短信原文" size="small" />
        </div>
      </n-spin>
    </n-modal>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted, nextTick, watch, h } from 'vue';
import { useInterval } from '@/composables/useRealtime';
import { useMessage, useDialog } from 'naive-ui';
import { useCancellableApi } from '@/composables/useCancellableApi';
import { copyToClipboard } from '@/composables/utils';

import GridCard from '@/components/GridCard.vue';
import InfoRow from '@/components/InfoRow.vue';
import type { VerificationCode } from '@/types';

interface Contact {
  phone: string;
  count: number;
  lastMessage: string;
  lastTime: number;
  unread: number;
}

interface SmsMessage {
  id: number | string;
  phoneNumber: string;
  content: string;
  direction: 'incoming' | 'outgoing';
  timestamp: number;
  read: boolean;
}

const message = useMessage();
const dialog = useDialog();
const api = useCancellableApi();

// ── State ──
const contacts = ref<Contact[]>([]);
const messages = ref<SmsMessage[]>([]);
const selectedPhone = ref('');
const contactsLoading = ref(false);
const messagesLoading = ref(false);
const sending = ref(false);
const newMessage = ref('');
const contactFilter = ref<'all' | 'unread'>('all');
const totalCount = ref(0);
const unreadCount = ref(0);
const conversationCount = ref(0);
const showNewSms = ref(false);
const newPhone = ref('');
const newSmsContent = ref('');
const isMobile = ref(false);
const scrollbarRef = ref<any>(null);

// 已发送但后端尚未确认的乐观消息（发送后即时显示，避免界面不刷新）
const optimisticMessages = ref<SmsMessage[]>([]);

// ── 验证码 / 短信原文 ──
// 验证码与短信同为页面主体，用页内 tab 切换
const activeTab = ref<'messages' | 'codes'>('messages');
const codesLoading = ref(false);
const codes = ref<VerificationCode[]>([]);
const codesLoaded = ref(false);
const showRawSms = ref(false);

const rawLoading = ref(false);
const rawSms = ref<SmsMessage | null>(null);

// ── Dropdown state ──
const msgMenuTarget = ref<SmsMessage | null>(null);
const msgDropdownX = ref(0);
const msgDropdownY = ref(0);

// 后端 /api/sms/list 的 limit 上限为 200
const MAX_PAGE = 200;
const pageLimit = ref(50);

// ── Computed ──
const filteredContacts = computed(() => {
  if (contactFilter.value === 'unread') {
    return contacts.value.filter((c) => c.unread > 0);
  }
  return contacts.value;
});

// 展示用消息 = 权威消息 + 当前会话的乐观消息（已发未确认）
const displayMessages = computed(() => [
  ...messages.value,
  ...optimisticMessages.value.filter((o) => o.phoneNumber === selectedPhone.value),
]);

const canLoadMore = computed(
  () => messages.value.length > 0 && messages.value.length < conversationCount.value && pageLimit.value < MAX_PAGE
);

const hitPageCap = computed(() => pageLimit.value >= MAX_PAGE && messages.value.length < conversationCount.value);

const msgMenuOptions = computed(() => {
  const target = msgMenuTarget.value;
  const options: any[] = [];
  // 已读状态只对收到的短信有意义（后端 unread 仅统计 direction=received）
  if (target && target.direction === 'incoming' && !isOptimistic(target)) {
    options.push({ label: '标记为未读', key: 'markUnread' }, { type: 'divider', key: 'd1' });
  }
  options.push({
    key: 'delete',
    label: '删除',
    render: (option: any) => h('span', { style: { color: '#d03050' } }, option.label),
  });
  return options;
});

// 原文弹窗里的方向文案
const rawDirectionLabel = computed(() => (rawSms.value?.direction === 'outgoing' ? '已发送' : '已接收'));

// ── Data loading ──
async function loadContacts() {
  contactsLoading.value = true;
  try {
    const { data } = await api.get('/api/sms/contacts');
    // 后端 getContactList() 实际返回字段：phoneNumber / total / unread / latestMsg / latestTimestamp
    contacts.value = (data.contacts || []).map((c: any) => ({
      phone: c.phoneNumber ?? c.phone ?? '',
      count: c.total ?? c.count ?? 0,
      lastMessage: c.latestMsg ?? c.lastMessage ?? '',
      lastTime: c.latestTimestamp ?? c.lastTime ?? c.last_time ?? 0,
      unread: c.unread ?? 0,
    }));
  } catch {
    /* silent */
  } finally {
    contactsLoading.value = false;
  }
}

async function loadCounts() {
  try {
    const { data } = await api.get('/api/sms/count');
    totalCount.value = data.total ?? 0;
    unreadCount.value = data.unread ?? 0;
  } catch {
    /* silent */
  }
}

async function loadConversation(phone: string, scroll = true) {
  messagesLoading.value = true;
  try {
    const { data } = await api.get('/api/sms/list', {
      params: { phone, limit: pageLimit.value, offset: 0 },
    });
    const msgs: SmsMessage[] = (data.messages || []).map((m: any) => ({
      id: m.id,
      phoneNumber: m.phoneNumber ?? m.phone_number ?? phone,
      content: m.content ?? '',
      direction: normalizeDirection(m.direction),
      timestamp: m.timestamp ?? 0,
      read: m.read ?? true,
    }));
    // 后端按时间倒序返回（最新在上），反转使最新消息排在底部，符合直觉
    messages.value = msgs.slice().reverse();
    // 后端已确认的已发消息，移除对应乐观气泡，避免与权威数据重复
    const confirmedKeys = new Set(
      msgs.filter((m) => m.direction === 'outgoing').map((m) => `${m.phoneNumber}|${m.content}`)
    );
    if (confirmedKeys.size) {
      optimisticMessages.value = optimisticMessages.value.filter(
        (o) => !confirmedKeys.has(`${o.phoneNumber}|${o.content}`)
      );
    }
    conversationCount.value = data.total ?? data.count ?? msgs.length;

    // Mark conversation as read
    const unreadMsgs = msgs.filter((m) => !m.read);
    if (unreadMsgs.length > 0) {
      api.post('/api/sms/read-conversation', { phone }).catch(() => {});
      loadCounts();
      loadContacts();
    }

    if (scroll) scrollToBottom();
  } catch {
    /* silent */
  } finally {
    messagesLoading.value = false;
  }
}

// ── Actions ──
function selectContact(phone: string) {
  selectedPhone.value = phone;
  messages.value = [];
  optimisticMessages.value = [];
  pageLimit.value = 50;
  loadConversation(phone);
}

async function loadMore() {
  if (!selectedPhone.value || messagesLoading.value) return;
  pageLimit.value = Math.min(MAX_PAGE, pageLimit.value + 50);
  await loadConversation(selectedPhone.value, false);
}

async function markAllRead() {
  try {
    await api.post('/api/sms/mark-all-read', {});
    message.success('已全部标记为已读');
    loadCounts();
    loadContacts();
    if (selectedPhone.value) loadConversation(selectedPhone.value, false);
  } catch {
    message.error('操作失败');
  }
}

async function sendMessage() {
  const phone = selectedPhone.value;
  const text = newMessage.value.trim();
  if (!phone || !text) return;
  sending.value = true;
  try {
    await api.post('/api/sms/send', { phone, message: text });
    newMessage.value = '';
    message.success('短信已发送');
    // 乐观更新：发送成功立即把已发气泡加入当前会话，避免后端写入延迟导致界面不刷新
    optimisticMessages.value = [
      ...optimisticMessages.value,
      {
        id: 'opt-' + Date.now(),
        phoneNumber: phone,
        content: text,
        direction: 'outgoing',
        timestamp: Date.now(),
        read: true,
      },
    ];
    scrollToBottom();
    loadContacts();
    loadCounts();
    await loadConversation(phone);
    // 后端写入有延迟，稍后再同步一次以确认已发消息并去重
    setTimeout(() => {
      loadConversation(phone);
      loadContacts();
    }, 1000);
  } catch {
    message.error('发送失败');
  } finally {
    sending.value = false;
  }
}

async function sendNewSms() {
  const targetPhone = newPhone.value.trim();
  const content = newSmsContent.value.trim();
  if (!targetPhone) {
    message.error('请输入手机号码');
    return false;
  }
  if (!content) {
    message.error('请输入短信内容');
    return false;
  }
  try {
    await api.post('/api/sms/send', { phone: targetPhone, message: content });
    message.success('短信已发送');
    newPhone.value = '';
    newSmsContent.value = '';
    showNewSms.value = false;
    // 切到该会话并乐观显示已发气泡
    selectedPhone.value = targetPhone;
    optimisticMessages.value = [
      ...optimisticMessages.value,
      {
        id: 'opt-' + Date.now(),
        phoneNumber: targetPhone,
        content,
        direction: 'outgoing',
        timestamp: Date.now(),
        read: true,
      },
    ];
    loadContacts();
    loadCounts();
    await loadConversation(targetPhone);
    setTimeout(() => {
      loadConversation(targetPhone);
      loadContacts();
    }, 1000);
    return true;
  } catch {
    message.error('发送失败');
    return false;
  }
}

function confirmDeleteMessage(msg: SmsMessage) {
  dialog.warning({
    title: '删除短信',
    content: '确定删除这条短信？',
    positiveText: '删除',
    negativeText: '取消',
    onPositiveClick: async () => {
      const optimistic = isOptimistic(msg);
      try {
        if (!optimistic) {
          await api.post('/api/sms/delete', { id: msg.id });
        }
        message.success('已删除');
        if (optimistic) {
          optimisticMessages.value = optimisticMessages.value.filter((m) => m.id !== msg.id);
        } else {
          messages.value = messages.value.filter((m) => m.id !== msg.id);
        }
        conversationCount.value = Math.max(0, conversationCount.value - 1);
        loadContacts();
        loadCounts();
      } catch {
        message.error('删除失败');
      }
    },
  });
}

function openMsgMenu(e: MouseEvent, msg: SmsMessage) {
  msgDropdownX.value = e.clientX;
  msgDropdownY.value = e.clientY;
  msgMenuTarget.value = msg;
}

function handleMsgAction(key: string) {
  const msg = msgMenuTarget.value;
  msgMenuTarget.value = null;
  if (!msg) return;
  if (key === 'delete') {
    confirmDeleteMessage(msg);
  } else if (key === 'markUnread') {
    // 乐观气泡（opt-*）不是后端 ID，调用会被 core 以 400 拒绝
    if (isOptimistic(msg)) return;
    api
      .post('/api/sms/read', { id: msg.id, read: false })
      .then(() => {
        message.success('已标记为未读');
        const hit = messages.value.find((m) => m.id === msg.id);
        if (hit) hit.read = false;
        loadCounts();
        loadContacts();
      })
      .catch(() => message.error('标记失败'));
  }
}

function refreshAll() {
  loadContacts();
  loadCounts();
  if (selectedPhone.value) {
    loadConversation(selectedPhone.value);
  }
}

// ── 验证码 ──
// GET /api/sms/verification-codes 无参数，成功返回 { codes: [...], count }
async function loadVerificationCodes() {
  codesLoading.value = true;
  try {
    const { data } = await api.get('/api/sms/verification-codes');
    codes.value = (data.codes || []) as VerificationCode[];
    codesLoaded.value = true;
  } catch {
    message.error('获取验证码失败');
  } finally {
    codesLoading.value = false;
  }
}

// 切到验证码 tab 时首次加载；之后由 10s 轮询接管
watch(activeTab, (tab) => {
  if (tab === 'codes' && !codesLoaded.value) loadVerificationCodes();
});

async function copyCode(code: string) {
  const ok = await copyToClipboard(code);
  if (ok) message.success('已复制验证码');
  else message.error('复制失败，请手动选择复制');
}

// GET /api/sms/{id}，id 必须为 > 0 的整数；404 表示短信已被删除
async function viewOriginal(msgId: number) {
  if (!Number.isInteger(msgId) || msgId <= 0) {
    message.error('短信 ID 无效');
    return;
  }
  showRawSms.value = true;
  rawLoading.value = true;
  rawSms.value = null;
  try {
    const { data } = await api.get(`/api/sms/${msgId}`);
    rawSms.value = {
      id: data.id,
      phoneNumber: data.phoneNumber ?? '',
      content: data.content ?? '',
      direction: normalizeDirection(data.direction),
      timestamp: data.timestamp ?? 0,
      read: data.read ?? true,
    };
  } catch (e: any) {
    if (e?.response?.status === 404) {
      message.warning('该短信已被删除');
      showRawSms.value = false;
    } else {
      message.error('获取短信原文失败');
    }
  } finally {
    rawLoading.value = false;
  }
}

// ── Helpers ──

// 乐观气泡的 id 形如 opt-<ts>，不是后端 ID
function isOptimistic(msg: SmsMessage): boolean {
  return String(msg.id).startsWith('opt-');
}

// 后端 direction 为 received / sent，前端模型与 CSS 使用 incoming / outgoing
function normalizeDirection(d: string | undefined): 'incoming' | 'outgoing' {
  if (d === 'outgoing' || d === 'sent') return 'outgoing';
  return 'incoming';
}

function formatRelativeTime(ts: number): string {
  if (!ts) return '';
  const now = Date.now();
  const time = ts > 1e12 ? ts : ts * 1000;
  const diff = now - time;
  const date = new Date(time);
  const today = new Date();
  today.setHours(0, 0, 0, 0);
  const yesterday = new Date(today.getTime() - 86400000);

  const pad = (n: number) => String(n).padStart(2, '0');
  const hhmm = `${pad(date.getHours())}:${pad(date.getMinutes())}`;

  if (diff < 60_000) return '刚刚';
  if (diff < 3_600_000) return `${Math.floor(diff / 60_000)}分钟前`;
  if (diff < 86_400_000 && time >= today.getTime()) return hhmm;
  if (time >= yesterday.getTime() && time < today.getTime()) return `昨天 ${hhmm}`;
  if (time >= today.getTime() - 7 * 86_400_000) {
    const days = ['日', '一', '二', '三', '四', '五', '六'];
    return `周${days[date.getDay()]} ${hhmm}`;
  }
  const y = date.getFullYear();
  const m = pad(date.getMonth() + 1);
  const d = pad(date.getDate());
  return y === today.getFullYear() ? `${m}-${d} ${hhmm}` : `${y}-${m}-${d} ${hhmm}`;
}

// 验证码/原文场景需要完整时间（utils.ts 里没有现成的日期格式化函数）
function formatFullTime(ts: number): string {
  if (!ts) return '未知时间';
  const time = ts > 1e12 ? ts : ts * 1000;
  return new Date(time).toLocaleString('zh-CN');
}

function scrollToBottom() {
  // 用 NScrollbar 自带 scrollTo 滚到底部（position:'bottom' 对应 MAX_SAFE_INTEGER，
  // 不依赖已测得的高度；behavior 默认即时，无平滑动画，避免抽搐）
  nextTick(() => {
    const inst: any = scrollbarRef.value;
    if (inst && typeof inst.scrollTo === 'function') {
      inst.scrollTo({ position: 'bottom' });
    }
  });
}

function checkMobile() {
  isMobile.value = window.innerWidth <= 768;
}

// ── Lifecycle ──
onMounted(() => {
  checkMobile();
  window.addEventListener('resize', checkMobile);
  loadContacts();
  loadCounts();
});

useInterval(() => {
  loadCounts();
  // 新号码来的短信只会出现在联系人列表里，必须一起刷新
  loadContacts();
  if (selectedPhone.value) {
    // 轮询不强制滚底，避免用户在阅读历史消息时被拽回底部
    loadConversation(selectedPhone.value, false);
  }
  // 只在验证码 tab 可见时刷新，避免无谓请求
  if (activeTab.value === 'codes') loadVerificationCodes();
}, 10_000);

onUnmounted(() => {
  window.removeEventListener('resize', checkMobile);
});
</script>

<style scoped>
.sms-view {
  display: flex;
  flex-direction: column;
}

.sms-tabs {
  margin-top: 4px;
}

/* tab 头 + pane 内边距约占 50px，主体高度相应下调，避免整页出现滚动条 */
.sms-body,
.codes-body {
  display: flex;
  height: calc(100vh - 250px);
  min-height: 360px;
  border: 1px solid var(--border-color, var(--border-subtle));
  border-radius: 8px;
  overflow: hidden;
}

.codes-body {
  flex-direction: column;
  background: var(--card-bg);
}

.codes-scroll {
  flex: 1 1 auto;
  min-height: 0;
  overflow: hidden;
}

/* n-spin 包裹滚动区时默认不约束高度，导致内部滚动区高度坍塌无法滚动；
   让其填满并把高度向下传递（flex + min-height:0） */
.contacts-panel :deep(.n-spin-container),
.conversation-panel :deep(.n-spin-container),
.codes-body :deep(.n-spin-container),
.contacts-panel :deep(.n-spin-content),
.conversation-panel :deep(.n-spin-content),
.codes-body :deep(.n-spin-content) {
  flex: 1 1 auto;
  min-height: 0;
  display: flex;
  flex-direction: column;
}

/* ── Contacts panel ── */
.contacts-panel {
  width: 30%;
  min-width: 260px;
  max-width: 360px;
  border-right: 1px solid var(--border-color, var(--border-subtle));
  display: flex;
  flex-direction: column;
  background: var(--card-bg);
}

.contacts-tabs {
  display: flex;
  border-bottom: 1px solid var(--border-color, var(--border-subtle));
  flex-shrink: 0;
}

.tab-item {
  flex: 1;
  text-align: center;
  padding: 10px 0;
  font-size: 13px;
  cursor: pointer;
  color: var(--text-secondary);
  transition:
    color 0.2s,
    border-color 0.2s;
  border-bottom: 2px solid transparent;
  user-select: none;
}

.tab-item:hover {
  color: var(--text-primary);
}

.tab-item.active {
  color: #2080f0;
  border-bottom-color: #2080f0;
  font-weight: 500;
}

.contacts-scroll {
  flex: 1 1 auto;
  min-height: 0;
  overflow: hidden;
}

.contact-item {
  display: flex;
  align-items: center;
  padding: 12px 14px;
  cursor: pointer;
  border-bottom: 1px solid var(--border-color, var(--border-subtle));
  transition: background 0.15s;
  gap: 10px;
}

.contact-item:hover {
  background: rgba(0, 0, 0, 0.04);
}

.contact-item.active {
  background: rgba(32, 128, 240, 0.08);
}

.contact-main {
  flex: 1;
  min-width: 0;
}

.contact-phone {
  font-size: 13px;
  font-weight: 500;
  color: var(--text-primary);
  margin-bottom: 4px;
}

.contact-preview {
  font-size: 12px;
  color: var(--text-muted);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  max-width: 180px;
}

.contact-meta {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 6px;
  flex-shrink: 0;
}

.contact-time {
  font-size: 11px;
  color: var(--text-muted);
  white-space: nowrap;
}

/* ── Conversation panel ── */
.conversation-panel {
  flex: 1;
  display: flex;
  flex-direction: column;
  background: var(--page-bg);
  min-width: 0;
}

.conv-header {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 16px;
  border-bottom: 1px solid var(--border-color, var(--border-subtle));
  background: var(--card-bg);
  flex-shrink: 0;
}

.conv-phone {
  font-size: 14px;
  font-weight: 600;
  color: var(--text-primary);
}

.back-btn {
  font-size: 16px;
  padding: 0 6px;
}

.messages-scroll {
  flex: 1 1 auto;
  min-height: 0;
  overflow: hidden;
}

/* 确保滚动定位即时生效，无平滑滚动动画 */
.messages-scroll :deep(.n-scrollbar-container) {
  scroll-behavior: auto;
}

.messages-list {
  padding: 16px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

/* ── Load more ── */
.load-more-row {
  display: flex;
  justify-content: center;
  padding: 2px 0 6px;
}

.load-more-hint {
  font-size: 11px;
  color: var(--text-muted);
  text-align: center;
}

/* ── Message bubbles ── */
.msg-row {
  display: flex;
}

.msg-row.incoming {
  justify-content: flex-start;
}

.msg-row.outgoing {
  justify-content: flex-end;
}

.msg-bubble {
  max-width: 70%;
  padding: 10px 14px;
  border-radius: 12px;
  font-size: 13px;
  line-height: 1.5;
  position: relative;
  word-break: break-word;
  cursor: context-menu;
}

.msg-row.incoming .msg-bubble {
  background: var(--card-bg);
  color: var(--text-primary);
  border-bottom-left-radius: 4px;
  border: 1px solid var(--border-color, var(--border-subtle));
}

.msg-row.outgoing .msg-bubble {
  background: #2080f0;
  color: #fff;
  border-bottom-right-radius: 4px;
}

.msg-content {
  white-space: pre-wrap;
}

.msg-time {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-top: 4px;
  font-size: 11px;
  opacity: 0.6;
  justify-content: flex-end;
}

.msg-dir-tag {
  font-size: 10px;
  opacity: 0.8;
}

/* ── Message input bar ── */
.msg-input-bar {
  display: flex;
  align-items: flex-end;
  gap: 8px;
  padding: 12px 16px;
  border-top: 1px solid var(--border-color, var(--border-subtle));
  background: var(--card-bg);
  flex-shrink: 0;
  position: sticky;
  bottom: 0;
  z-index: 2;
}

.send-btn {
  flex-shrink: 0;
  height: 34px;
}

/* ── Empty / placeholder ── */
.panel-empty {
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 40px 16px;
  color: var(--text-muted);
}

.conv-placeholder {
  flex: 1;
}

/* ── New SMS form ── */
.new-sms-form {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.form-row {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.form-label {
  font-size: 13px;
  color: var(--text-secondary);
  font-weight: 500;
}

/* ── 验证码列表 / 原文弹窗 ── */
.codes-list {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(260px, 1fr));
  gap: 10px;
  padding: 12px;
}

.code-item {
  padding: 12px 14px;
  border: 1px solid var(--border-color, var(--border-subtle));
  border-radius: 8px;
  background: var(--card-bg);
}

.code-value {
  font-size: 22px;
  font-weight: 600;
  letter-spacing: 2px;
  color: var(--text-primary);
  font-family: 'Consolas', 'Menlo', monospace;
}

.code-meta {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
  margin-top: 4px;
  font-size: 12px;
  color: var(--text-secondary);
}

.code-source {
  font-weight: 500;
}

.code-dot {
  color: var(--text-muted);
}

.code-snippet {
  margin-top: 6px;
  font-size: 11px;
  color: var(--text-muted);
  line-height: 1.5;
  word-break: break-word;
}

.code-actions {
  display: flex;
  gap: 8px;
  margin-top: 10px;
}

.raw-sms {
  display: flex;
  flex-direction: column;
}

.raw-content {
  margin-top: 12px;
  padding: 12px;
  border-radius: 8px;
  background: var(--page-bg);
  font-size: 13px;
  line-height: 1.6;
  color: var(--text-primary);
  white-space: pre-wrap;
  word-break: break-word;
}

/* ── Mobile responsive ── */
@media (max-width: 768px) {
  .sms-body,
  .codes-body {
    height: calc(100vh - 230px);
    min-height: 300px;
  }

  .codes-list {
    grid-template-columns: 1fr;
  }

  .contacts-panel {
    width: 100%;
    max-width: none;
    min-width: 0;
    border-right: none;
  }

  .contacts-panel.hidden {
    display: none;
  }

  .conversation-panel {
    display: none;
  }

  .conversation-panel.visible {
    display: flex;
  }

  .contact-preview {
    max-width: none;
  }

  .msg-bubble {
    max-width: 85%;
  }
}
</style>
