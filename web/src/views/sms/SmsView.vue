<template>
  <div class="sms-view">
    <GridCard title="短信管理">
      <template #extra>
        <!--
          `wrap`：360–414px 下这块要放「共 N 条 / N 条未读 + 刷新 / 全部已读 / 多选 / 新建短信
          + 拦截规则」7 个元素，不换行就只能互相挤压（文案糊成一团、按钮缩到不可点）。
          换行是低风险方案：高度多一两行，但每个动作都保持原尺寸与可读性。
        -->
        <n-space :size="8" align="center" wrap>
          <template v-if="activeTab === 'messages'">
            <!--
              多选动作条（2026-10-14）。进入多选后它**替换**整块页面级动作，
              而不是并列追加：多选期间「刷新 / 全部已读 / 新建短信」都跟"我正要删的这几条"
              不是一回事，留着它们只会让用户在最需要确认自己选了什么的时候，
              眼前多出四个语义无关的按钮（也与「删除」争同一片注意力）。
            -->
            <template v-if="selectMode">
              <ValueBadge label="已选" :value="selectedIds.size" unit=" 项" size="sm" />
              <n-button size="small" :disabled="selectableIds.length === 0 || allSelected" @click="selectAllMessages">
                全选
              </n-button>
              <n-button size="small" @click="exitSelectMode">取消</n-button>
              <n-button
                size="small"
                type="error"
                :disabled="selectedIds.size === 0"
                :loading="deleting"
                @click="confirmDeleteSelected"
              >
                删除
              </n-button>
            </template>
            <template v-else>
              <ValueBadge v-if="totalCount > 0" label="共" :value="totalCount" unit=" 条" size="sm" />
              <ValueBadge v-if="unreadCount > 0" :value="unreadCount" unit=" 条未读" size="sm" color="var(--info)" />
              <!-- 计数读失败不能说成「没有」：两个胶囊都带 v-if，失败时会一起消失，
                   看上去与「共 0 条」完全一样，所以这里单独给一个可重试的出口 -->
              <n-button v-if="countsError" size="small" quaternary type="error" @click="loadCounts">
                统计读取失败，重试
              </n-button>
              <n-button size="small" :loading="contactsLoading" @click="refreshAll"> 刷新 </n-button>
              <n-button v-if="unreadCount > 0" size="small" @click="markAllRead"> 全部已读 </n-button>
              <n-button v-if="canEnterSelectMode" size="small" @click="enterSelectMode"> 多选 </n-button>
              <!--
                发短信入口的能力门禁（Capability.SMS）。禁用判据一律用 `caps.unsupported()`，
               绝不用 `!caps.supports()`：后者在"能力集还没拉到 / 拉取失败"时也成立，
                会把整个入口锁死（见 stores/capabilities.ts 文件头）。
                外层 span 是为了让 tooltip 能被 hover 到 —— 浏览器不对 disabled 表单控件
                派发鼠标事件，`.gated-btn:disabled` 上的 pointer-events:none 把悬停让给外层。
              -->
              <n-tooltip :disabled="!smsUnsupported" :title="smsHint">
                <template #trigger>
                  <span class="gated-wrap">
                    <n-button
                      size="small"
                      type="primary"
                      class="gated-btn"
                      :disabled="smsUnsupported"
                      @click="showNewSms = true"
                    >
                      新建短信
                    </n-button>
                  </span>
                </template>
              </n-tooltip>
            </template>
          </template>
          <template v-else-if="activeTab === 'codes'">
            <ValueBadge v-if="codes.length > 0" label="共" :value="codes.length" unit=" 条" size="sm" />
            <n-button size="small" :loading="codesLoading" @click="loadVerificationCodes"> 刷新 </n-button>
            <!--
              2026-10-14：「验证码设置」（启用提取 / 清理周期）自「设置 → 通用」迁来。
              与下面的「拦截规则」同一形态 —— 设置不进 tab 清单，点开一个弹窗；
              入口放在「验证码」这个 tab 的动作区，因为它只影响这个 tab 的内容。
            -->
            <n-button size="small" quaternary @click="codeSettingsModal.open()">验证码设置</n-button>
          </template>
          <template v-else>
            <ValueBadge v-if="blockedTotal > 0" label="共" :value="blockedTotal" unit=" 条" size="sm" />
            <n-button size="small" :loading="blockedLoading" @click="loadBlocked()"> 刷新 </n-button>
            <n-button v-if="blockedRecords.length > 0" size="small" type="error" @click="confirmClearBlocked">
              清空
            </n-button>
          </template>
          <!-- 拦截规则是设置而不是内容维度，所以放 header 动作区而不是第四个 tab -->
          <n-button size="small" quaternary @click="filterModal.open()"> 拦截规则 </n-button>
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
                  <!-- 「读不到」与「确实没有」必须分开：前者给重试，后者才是空态 -->
                  <div v-if="contactsError && contacts.length === 0" class="client-empty">
                    <n-button size="small" quaternary @click="loadContacts">读取失败，点击重试</n-button>
                  </div>
                  <div v-else-if="filteredContacts.length === 0 && !contactsLoading" class="client-empty">
                    <n-empty :description="contactFilter === 'unread' ? '没有未读短信' : '暂无短信'" size="small" />
                  </div>
                  <div
                    v-for="c in filteredContacts"
                    :key="c.phone"
                    class="contact-item"
                    :class="{ active: selectedPhone === c.phone }"
                    @click="selectContact(c.phone)"
                    @contextmenu.prevent="openContactMenu($event, c)"
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

              <!-- 会话行右键菜单：与消息气泡同一套「单实例 n-dropdown + 手动定位」写法 -->
              <n-dropdown
                :show="!!contactMenuTarget"
                :options="contactMenuOptions"
                trigger="manual"
                placement="bottom-start"
                :x="contactDropdownX"
                :y="contactDropdownY"
                @select="handleContactAction"
                @clickoutside="contactMenuTarget = null"
              />
            </div>

            <!-- Right panel: conversation

                 `visible` 只在 ≤768px 生效（基础样式里对话面板本来就是 display:flex，
                 只有移动端那段把它设成 none）。所以判据就是「选了联系人」——
                 2026-09-19 修：原来写的是 `selectedPhone || isMobile`，移动端未选联系人时
                 它恒为真 ⇒ 对话面板显示，而联系人面板的 `hidden`（要求 selectedPhone && isMobile）
                 又不成立 ⇒ 两块同时出现在同一个 flex 行里，100% + flex:1 直接把页面撑出横向滚动。 -->
            <div class="conversation-panel" :class="{ visible: selectedPhone }">
              <template v-if="selectedPhone">
                <!-- Conversation header -->
                <div class="conv-header">
                  <n-button v-if="isMobile" size="tiny" quaternary class="back-btn" @click="closeConversation">
                    &larr;
                  </n-button>
                  <span class="conv-phone">{{ selectedPhone }}</span>
                  <ValueBadge :value="conversationCount" unit=" 条消息" size="sm" variant="outline" />
                </div>

                <!-- Messages -->
                <n-spin :show="messagesLoading && messages.length === 0">
                  <n-scrollbar ref="scrollbarRef" class="messages-scroll">
                    <div class="messages-list">
                      <div v-if="messagesError && displayMessages.length === 0" class="client-empty">
                        <n-button size="small" quaternary @click="loadConversation(selectedPhone)">
                          读取失败，点击重试
                        </n-button>
                      </div>
                      <div v-else-if="displayMessages.length === 0 && !messagesLoading" class="client-empty">
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
                      <div
                        v-for="msg in displayMessages"
                        :key="msg.id"
                        class="msg-row"
                        :class="[msg.direction, { selectable: selectMode, selected: isSelected(msg) }]"
                        @click="onMsgRowClick(msg)"
                      >
                        <!--
                          复选框只在多选模式出现。乐观气泡（`opt-<ts>`，已发未确认）的复选框
                          禁用：它没有后端 id，混进 `ids` 会让 core 整批 400。
                        -->
                        <span
                          v-if="selectMode"
                          class="msg-check"
                          :title="isSelectable(msg) ? undefined : '这条短信还没写入设备，暂时不能批量删除'"
                          @click.stop
                        >
                          <n-checkbox
                            :checked="isSelected(msg)"
                            :disabled="!isSelectable(msg)"
                            @update:checked="toggleMessageSelection(msg)"
                          />
                        </span>
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
                  <!--
                    不支持短信能力时输入框也一并禁用：留一个能打字但发不出去的框，
                    用户会先写完整条短信才在点「发送」时撞墙。提示直接写进 placeholder，
                    因为 tooltip 在 disabled 的输入框上同样出不来。
                  -->
                  <n-input
                    v-model:value="newMessage"
                    :placeholder="smsUnsupported ? smsHint : '输入短信内容...'"
                    :disabled="smsUnsupported"
                    type="textarea"
                    :autosize="{ minRows: 1, maxRows: 4 }"
                    @keydown.enter.exact.prevent="sendMessage"
                  />
                  <n-tooltip :disabled="!smsUnsupported" :title="smsHint">
                    <template #trigger>
                      <span class="gated-wrap">
                        <n-button
                          type="primary"
                          :loading="sending"
                          :disabled="smsUnsupported || !newMessage.trim()"
                          class="send-btn gated-btn"
                          @click="sendMessage"
                        >
                          发送
                        </n-button>
                      </span>
                    </template>
                  </n-tooltip>
                </div>
              </template>

              <template v-else>
                <div class="client-empty conv-placeholder">
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
                <!-- 同联系人列表：「读不到」单独给重试，「确实没有」才是空态 -->
                <div v-if="codesError && codes.length === 0" class="client-empty">
                  <n-button size="small" quaternary @click="loadVerificationCodes">读取失败，点击重试</n-button>
                </div>
                <div v-else-if="codes.length === 0 && !codesLoading && !codesError" class="client-empty">
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

        <!-- 已拦截：与 app 的「已拦截」入口对齐；沿用 show:lazy 以保留滚动位置与已加载页 -->
        <n-tab-pane name="blocked" display-directive="show:lazy">
          <template #tab>
            <span class="tab-label">
              已拦截
              <n-badge v-if="blockedUnviewed > 0" :value="blockedUnviewed" :max="99" type="info" />
            </span>
          </template>
          <div class="blocked-body">
            <n-spin :show="blockedLoading && blockedRecords.length === 0">
              <n-scrollbar class="blocked-scroll">
                <div v-if="blockedError && blockedRecords.length === 0" class="client-empty">
                  <n-button size="small" quaternary @click="loadBlocked()">读取失败，点击重试</n-button>
                </div>
                <div v-else-if="blockedRecords.length === 0 && !blockedLoading && !blockedError" class="client-empty">
                  <n-empty description="暂无被拦截的短信" size="small" />
                </div>
                <div v-else class="blocked-list">
                  <div v-for="r in blockedRecords" :key="r.id" class="blocked-item">
                    <div class="blocked-head">
                      <span class="blocked-sender">{{ r.sender || '未知号码' }}</span>
                      <span class="blocked-time">{{ formatFullTime(r.blocked_at) }}</span>
                    </div>
                    <div class="blocked-snippet">{{ r.snippet || '（无正文预览）' }}</div>
                    <div class="blocked-meta">
                      <!-- 命中规则用记录里的快照字段，规则删了这条仍读得懂 -->
                      <n-tag size="tiny" :bordered="false">命中：{{ r.rule_pattern }}</n-tag>
                      <span>{{ ruleSnapshotLabel(r) }}</span>
                      <span v-if="describeBlockedPath(r.blocked_path)">
                        已拦下：{{ describeBlockedPath(r.blocked_path) }}
                      </span>
                    </div>
                    <div class="blocked-actions">
                      <n-button size="tiny" :disabled="!canDisableRule(r)" @click="confirmDisableRule(r)">
                        不再按此规则拦截
                      </n-button>
                      <span v-if="ruleStateHint(r)" class="blocked-hint">{{ ruleStateHint(r) }}</span>
                      <n-button size="tiny" quaternary type="error" @click="deleteBlocked(r)"> 删除 </n-button>
                    </div>
                  </div>
                </div>
                <div v-if="blockedHasMore" class="load-more-row">
                  <n-button size="tiny" quaternary :loading="blockedLoadingMore" @click="loadBlocked(true)">
                    加载更多
                  </n-button>
                </div>
              </n-scrollbar>
            </n-spin>
          </div>
        </n-tab-pane>
      </n-tabs>
    </GridCard>

    <!-- New SMS modal（宽高由 .modal-fit 收口，不再写行内 width） -->
    <n-modal
      v-model:show="showNewSms"
      preset="dialog"
      title="新建短信"
      positive-text="发送"
      negative-text="取消"
      class="modal-fit"
      style="--modal-w: 440px"
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

    <!-- 验证码原文弹窗：正文可能很长，--modal-h 让内容区自己滚、标题常驻 -->
    <n-modal
      v-model:show="showRawSms"
      preset="card"
      title="短信原文"
      class="modal-fit"
      style="--modal-w: 440px; --modal-h: 600px"
    >
      <n-spin :show="rawLoading">
        <div v-if="rawSms" class="raw-sms">
          <InfoRow label="手机号" :value="rawSms.phoneNumber || '未知'" />
          <InfoRow label="方向" :value="rawDirectionLabel" />
          <InfoRow label="时间" :value="formatFullTime(rawSms.timestamp)" />
          <div class="raw-content">{{ rawSms.content }}</div>
        </div>
        <div v-else-if="!rawLoading" class="client-empty">
          <n-empty description="无法获取短信原文" size="small" />
        </div>
      </n-spin>
    </n-modal>

    <!-- 拦截规则弹窗：懒加载，挂载与显隐分两拍（否则进出场动画丢） -->
    <component
      :is="filterComponent"
      v-if="filterComponent"
      :show="filterShow"
      @update:show="filterModal.setShow"
      @changed="onRulesChanged"
    />

    <!-- 验证码设置弹窗：懒加载，与拦截规则同一套「挂载与显隐分两拍」 -->
    <component
      :is="codeSettingsComponent"
      v-if="codeSettingsComponent"
      :show="codeSettingsShow"
      @update:show="codeSettingsModal.setShow"
    />
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted, nextTick, watch, h } from 'vue';
import { useInterval } from '@/composables/useRealtime';
import { useMessage, useDialog } from 'naive-ui';
import { useCancellableApi } from '@/composables/useCancellableApi';
import { useLazyModal } from '@/composables/useLazyModal';
import { useIsMobile } from '@/composables/useIsMobile';
import { useCapabilitiesStore } from '@/stores/capabilities';
import { copyToClipboard } from '@/composables/utils';
import {
  Capability,
  Endpoints,
  SmsRuleScopeLabels,
  SmsRuleMatchLabels,
  describeBlockedPath,
  type SmsRuleItem,
  type SmsBlockedRecord,
} from '@/api/contract';

import GridCard from '@/components/GridCard.vue';
import InfoRow from '@/components/InfoRow.vue';
import ValueBadge from '@/components/ValueBadge.vue';
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

/**
 * 短信能力门禁（`Capability.SMS`）。
 *
 * ## 语义边界（照 core 侧定的，不是猜的）
 * `Capability.SMS` 覆盖的**只有"发送"这一个写动作**：`contract/Capabilities.kt` 的枚举 KDoc
 * 写的是「覆盖的写动作：`POST /api/sms/send`」，门禁落点也只有 `RootSmsRoutes` 的 `/sms/send`；
 * 那个 route 自己的注释还补了一句「信箱的读 / 删 / 标已读**不在此域内**」。
 * 而 `DeviceAdapter.sms`（信箱查询 / 发送 / 删除 / 已读标记 / 条数统计）对每台设备都是**非空**的 ——
 * 即"没有短信能力"指的是**发不出去**，不是"没有信箱"。
 *
 * 所以这里只门禁两个**发短信入口**（页面上的「新建短信」与会话里的「发送」/输入框）；
 * 浏览列表、验证码、已拦截、单条/整段/批量删除、标记已读/未读**一律照常可用**。
 * 若拿 `Capability.SMS` 去禁用删除，用户会在一个"短信列表就在眼前"的页面上看到一个删不掉的列表。
 *
 * ## 判据
 * 一律用 `unsupported()`（只有"明确不支持"才 true），**不用** `!supports()`：
 * 后者在能力集还没拉到 / 拉取失败时也成立，会把入口锁死（见 stores/capabilities.ts 文件头）。
 */
const caps = useCapabilitiesStore();
const smsUnsupported = computed(() => caps.unsupported(Capability.SMS));
const smsHint = computed(() => caps.hintOf(Capability.SMS));

// ── State ──
const contacts = ref<Contact[]>([]);
const messages = ref<SmsMessage[]>([]);
const selectedPhone = ref('');
const contactsLoading = ref(false);
const messagesLoading = ref(false);
/**
 * 三个「静默失败」的出口（原先是 `catch {}`）。
 *
 * 为什么必须留状态：联系人 / 计数 / 会话三个接口失败时，界面会退化成
 * 「暂无短信」「共 0 条」—— 那与「设备里确实没有短信」在屏幕上完全一样，
 * 用户会以为短信丢了（或以为被拦截规则吃掉了）。失败要能说出来，并且给重试。
 */
const contactsError = ref(false);
const messagesError = ref(false);
const countsError = ref(false);
const sending = ref(false);
const newMessage = ref('');
const contactFilter = ref<'all' | 'unread'>('all');
const totalCount = ref(0);
const unreadCount = ref(0);
const conversationCount = ref(0);
const showNewSms = ref(false);
const newPhone = ref('');
const newSmsContent = ref('');
const isMobile = useIsMobile();
const scrollbarRef = ref<any>(null);

// 已发送但后端尚未确认的乐观消息（发送后即时显示，避免界面不刷新）
const optimisticMessages = ref<SmsMessage[]>([]);

// ── 批量删除（2026-10-14）──
//
// 三条删除接口语义不同，**不合并调用**（契约里刻意分成三条的理由见 `Endpoints.sms.deleteBatch`）：
//   · `POST /api/sms/delete`             单条（气泡右键菜单，已有）
//   · `POST /api/sms/delete-conversation` 按号码整段（会话行右键菜单，已有）
//   · `POST /api/sms/delete-batch`        一批短信 id（本次新增）
//
// ## 多选为什么挂在**消息列表**上、而不是会话列表上
// deleteBatch 收的是短信 id；会话行只有号码，没有 id —— 想删一整段得走 delete-conversation，
// 那是另一条语义（"这段会话全删" ≠ "我勾的这几条"）。把多选画在会话行上，
// 用户勾了三段会话之后按「删除」，代码只能猜他要哪一条接口，猜错就是把整段会话删了。
//
// ## 入口为什么是按钮而不是长按
// 本仓 web 侧的既有习惯是**右键菜单**（消息气泡与会话行都是 `@contextmenu`），
// 长按在这个界面里没有任何先例；而且长按在桌面浏览器上会被文本选择/拖拽吃掉，
// 在移动端浏览器里还会触发系统的"长按选中/放大镜"，和 app（Compose `combinedClickable`）不同 ——
// app 的长按是自己实现的、不会被系统抢走。显式按钮可发现、可键盘触达，
// 代价只是多一个按钮，所以选它。
const selectMode = ref(false);
/** 勾选的短信 id。**只装后端 id（number）**：乐观气泡的 `opt-<ts>` 是字符串，进去就是整批 400。 */
const selectedIds = ref<Set<number>>(new Set());
const deleting = ref(false);

// ── 验证码 / 短信原文 ──
// 验证码与短信同为页面主体，用页内 tab 切换
const activeTab = ref<'messages' | 'codes' | 'blocked'>('messages');
const codesLoading = ref(false);
const codes = ref<VerificationCode[]>([]);
/** 同 contacts/messages：请求失败不能落成「暂无验证码」，要留可重试的出口。 */
const codesError = ref(false);
const codesLoaded = ref(false);
const showRawSms = ref(false);

const rawLoading = ref(false);
const rawSms = ref<SmsMessage | null>(null);

// ── 已拦截（core 命中就不推、不发、不返回；web 只渲染记录）──
// 分页与告警列表同款：keyset 游标 + 「加载更多」，游标是 (blocked_at, id) 两个显式参数。
const BLOCKED_PAGE = 50;
/** 「已查看水位」：id 大于它的记录计入角标。与 app 的 `smsBlockedSeenId` 同语义。 */
const LS_BLOCKED_SEEN_ID = 'ufi_axis_sms_blocked_seen_id';
const blockedRecords = ref<SmsBlockedRecord[]>([]);
const blockedLoading = ref(false);
const blockedLoadingMore = ref(false);
/** 同 codesError：加载失败且手上没有数据时，列表区要给「读取失败，点击重试」。 */
const blockedError = ref(false);
const blockedLoaded = ref(false);
const blockedTotal = ref(0);
const blockedCursorTs = ref<number | null>(null);
const blockedCursorId = ref<number | null>(null);
const blockedHasMore = ref(false);
/** 已翻过页就不再自动刷新首页，否则会把用户加载出来的历史页清掉（同 AlertListPanel）。 */
const blockedPaginated = ref(false);
const blockedSeenId = ref(readBlockedSeenId());
/** 规则集合只用来判断「命中的那条规则还在不在、是不是已经停用」，展示一律用记录里的快照。 */
const rules = ref<SmsRuleItem[]>([]);

// 规则管理弹窗（懒加载）
const filterModal = useLazyModal(() => import('./components/modals/SmsFilterModal.vue'));
const filterComponent = filterModal.component;
const filterShow = filterModal.show;

// 验证码设置弹窗（懒加载，2026-10-14 自设置页「通用」迁来：这两项只影响本页的验证码 tab）
const codeSettingsModal = useLazyModal(() => import('./components/modals/SmsCodeSettingsModal.vue'));
const codeSettingsComponent = codeSettingsModal.component;
const codeSettingsShow = codeSettingsModal.show;

// ── Dropdown state ──
const msgMenuTarget = ref<SmsMessage | null>(null);
const msgDropdownX = ref(0);
const msgDropdownY = ref(0);

// 会话行右键菜单（与消息气泡同一套写法，只是菜单项不同）
const contactMenuTarget = ref<Contact | null>(null);
const contactDropdownX = ref(0);
const contactDropdownY = ref(0);

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

// ── 多选（批量删除）──

/** 后端 id（正整数）。乐观气泡的 `opt-<ts>` 是字符串，塞进 `ids` 会让 core 以 400 拒掉整批。 */
function numericId(msg: SmsMessage): number | null {
  return typeof msg.id === 'number' && Number.isInteger(msg.id) && msg.id > 0 ? msg.id : null;
}

function isSelectable(msg: SmsMessage): boolean {
  return numericId(msg) !== null;
}

function isSelected(msg: SmsMessage): boolean {
  const id = numericId(msg);
  return id !== null && selectedIds.value.has(id);
}

/**
 * 「全选」的范围 = **当前会话里已加载的这批**（受 core 单次 200 条上限约束），不是整段会话。
 * 刻意不把未加载的历史消息也算进来：那得先把整段会话分页拉完，而拉完之后用户眼前仍然只有
 * 屏幕上这几十条 —— 界面与"全选"的语义对不上时，用户会以为自己也就能删掉看得见的这些，
 * 结果一次点击删掉几百条历史短信。
 */
const selectableIds = computed(() => messages.value.map(numericId).filter((id): id is number => id !== null));

const allSelected = computed(
  () => selectableIds.value.length > 0 && selectableIds.value.every((id) => selectedIds.value.has(id))
);

const canEnterSelectMode = computed(() => messages.value.length > 0);

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
    // 令牌而不是 hex：基线扫描器只看 <style>，JS 里的写死色它扫不到，
    // 但暗色/换肤下同样不跟（与 .msg-bubble 里用 token 的其它地方保持同一来源）。
    render: (option: any) => h('span', { style: { color: 'var(--error)' } }, option.label),
  });
  return options;
});

const contactMenuOptions = computed(() => [
  { key: 'delete-conversation', label: '删除整段会话' },
  { type: 'divider', key: 'd-contact' },
  { key: 'blacklist', label: '加入黑名单' },
]);

/** 角标 = 已加载记录里 id 大于「已查看水位」的条数（与 app 的 smsBlockedUnviewed 同口径）。 */
const blockedUnviewed = computed(() => blockedRecords.value.filter((r) => r.id > blockedSeenId.value).length);

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
    contactsError.value = false;
  } catch {
    // 不把失败写成空列表：列表区据此显示「读取失败，点击重试」
    contactsError.value = true;
  } finally {
    contactsLoading.value = false;
  }
}

async function loadCounts() {
  try {
    const { data } = await api.get('/api/sms/count');
    totalCount.value = data.total ?? 0;
    unreadCount.value = data.unread ?? 0;
    countsError.value = false;
  } catch {
    countsError.value = true;
  }
}

async function loadConversation(phone: string, scroll = true) {
  messagesLoading.value = true;
  messagesError.value = false;
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
    // 同 loadContacts：失败不能渲染成「暂无消息记录」，要让用户看见并能重试
    messagesError.value = true;
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
  // 换会话就退出多选：勾选的是"上一个会话里的那几条"，留着模式会让新会话
  // 一进来就满屏复选框，而用户并没有在这个会话里点过「多选」。
  exitSelectMode();
  loadConversation(phone);
}

/** 关闭当前会话（移动端返回）。多选模式跟着会话一起退，否则会留下一个看不见的选中集。 */
function closeConversation() {
  selectedPhone.value = '';
  messages.value = [];
  optimisticMessages.value = [];
  exitSelectMode();
}

async function loadMore() {
  if (!selectedPhone.value || messagesLoading.value) return;
  pageLimit.value = Math.min(MAX_PAGE, pageLimit.value + 50);
  await loadConversation(selectedPhone.value, false);
}

// ── 多选 / 批量删除 ──

function enterSelectMode() {
  selectMode.value = true;
  selectedIds.value = new Set();
}

/** 退出多选并清空勾选。取消 / 删完 / 换会话 / 关会话 / 切 tab 都走它。 */
function exitSelectMode() {
  selectMode.value = false;
  selectedIds.value = new Set();
}

function onMsgRowClick(msg: SmsMessage) {
  if (!selectMode.value) return;
  toggleMessageSelection(msg);
}

function toggleMessageSelection(msg: SmsMessage) {
  const id = numericId(msg);
  if (id === null) return;
  // 换一个新 Set 而不是就地 add/delete：就地改也能触发（Vue 的集合是响应式的），
  // 但整值替换让"选中集变了"这件事在代码里看得见，不必依赖对响应式集合的隐式依赖。
  const next = new Set(selectedIds.value);
  if (next.has(id)) next.delete(id);
  else next.add(id);
  selectedIds.value = next;
}

function selectAllMessages() {
  selectedIds.value = new Set(selectableIds.value);
}

function confirmDeleteSelected() {
  const ids = [...selectedIds.value];
  if (ids.length === 0) return;
  dialog.warning({
    title: '删除短信',
    // 说清删几条 + 不可撤销。这条路径**没有**回收站，也不像「标记已读」那样可以反着点回去。
    content: `将删除 ${ids.length} 条短信，此操作不可撤销。`,
    positiveText: '删除',
    negativeText: '取消',
    onPositiveClick: () => deleteSelected(ids),
  });
}

/**
 * `POST Endpoints.sms.deleteBatch`，body `{ ids }` → `{ success, deleted, requested }`。
 *
 * 文案按 **`deleted`** 说，不按 `success`：core 的判据是 `deleted > 0`，所以
 * "一条都没删掉"回的是 `success: false` —— 那不是操作失败，是这些短信本来就不在了
 * （多半在别处已经删过）。把它报成「删除失败」会让用户以为自己操作错了，然后反复重试。
 */
async function deleteSelected(ids: number[]) {
  deleting.value = true;
  try {
    const { data } = await api.post(Endpoints.sms.deleteBatch, { ids });
    // 组件卸载时请求被取消，data 为 undefined（见 useCancellableApi）
    if (!data) return;
    const deleted = Number(data.deleted ?? 0);
    const requested = Number(data.requested ?? ids.length);
    const missing = Math.max(0, requested - deleted);
    if (deleted <= 0) {
      message.warning(`所选 ${requested} 条短信均已不存在`);
    } else if (missing > 0) {
      message.success(`已删除 ${deleted} 条（${missing} 条已不存在）`);
    } else {
      message.success(`已删除 ${deleted} 条短信`);
    }
    exitSelectMode();
    // 刷新列表：勾选期间 10s 轮询可能已经换过这批数据，本地过滤不如重新拉一遍准
    loadContacts();
    loadCounts();
    if (selectedPhone.value) await loadConversation(selectedPhone.value, false);
  } catch (e: any) {
    // 三种失败分开说，判据一律是 HTTP 状态码，**不用** core 回的中文 `message`
    // （那是给日志看的，措辞随时会变，拿它当判据等于把文案变成契约）：
    //   · 400        —— 请求本身被拒（一个有效的 id 都没有），服务端一条都没删 ⇒ 重选；
    //   · 其它状态码 —— 服务端收到了但没做成（5xx），删没删不确定 ⇒ 稍后重试；
    //   · 没有状态码 —— 请求根本没到服务端（断网 / 超时）⇒ 先查网络。
    const status = e?.response?.status;
    if (status === 400) {
      message.error('删除失败：没有有效的短信 ID，请刷新后重试');
    } else if (status) {
      message.error('删除失败：服务端出错，请稍后重试');
    } else {
      message.error('删除失败，请检查网络后重试');
    }
  } finally {
    deleting.value = false;
  }
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
  // 多选期间不开右键菜单：这时候点一条消息的意图是"选中/取消它"，
  // 再弹一个「删除 / 标记为未读」的单条菜单，等于在同一块区域里给两套互相矛盾的语义。
  if (selectMode.value) return;
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
    codesError.value = false;
    codesLoaded.value = true;
  } catch {
    // 保留上次成功的列表，只把失败标出来（空态分支据此让位给重试）
    codesError.value = true;
    message.error('获取验证码失败');
  } finally {
    codesLoading.value = false;
  }
}

// 切到验证码 tab 时首次加载；之后由 10s 轮询接管
watch(activeTab, (tab) => {
  // 离开「短信」tab 就退出多选：动作条只在那个 tab 的动作区里，
  // 留着模式会让用户切回来时发现复选框还在，而中间他并没有再点过「多选」。
  if (tab !== 'messages') exitSelectMode();
  if (tab === 'codes' && !codesLoaded.value) loadVerificationCodes();
  if (tab === 'blocked') {
    // 规则集合只在这个 tab 用得上（判断命中规则是否还在），进来再拉
    loadRules();
    if (!blockedLoaded.value) loadBlocked();
    else markBlockedSeen();
  }
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

// ── 已拦截 ──

function readBlockedSeenId(): number {
  const raw = Number(localStorage.getItem(LS_BLOCKED_SEEN_ID) ?? '0');
  return Number.isFinite(raw) && raw > 0 ? raw : 0;
}

/** 进入「已拦截」tab（或在该 tab 上刷新到新数据）即把水位抬到当前最大 id，角标归零。 */
function markBlockedSeen() {
  if (blockedRecords.value.length === 0) return;
  const maxId = Math.max(...blockedRecords.value.map((r) => r.id));
  if (maxId <= blockedSeenId.value) return;
  blockedSeenId.value = maxId;
  localStorage.setItem(LS_BLOCKED_SEEN_ID, String(maxId));
}

/**
 * GET /api/sms/blocked —— 列表字段是 `records`，游标是 `cursor_ts` + `cursor_id`
 * （两个必须成对带上，只带一个会漏记录或翻出重复）。
 */
async function loadBlocked(more = false) {
  if (more) {
    if (blockedLoadingMore.value || !blockedHasMore.value) return;
    if (blockedCursorTs.value === null || blockedCursorId.value === null) return;
    blockedLoadingMore.value = true;
  } else {
    blockedLoading.value = true;
  }
  try {
    const params: Record<string, any> = { limit: BLOCKED_PAGE };
    if (more) {
      params.cursor_ts = blockedCursorTs.value;
      params.cursor_id = blockedCursorId.value;
    }
    const { data } = await api.get(Endpoints.sms.blocked, { params });
    // 组件卸载时请求被取消，data 为 undefined（见 useCancellableApi）
    if (!data) return;
    const list = (data.records || []) as SmsBlockedRecord[];
    if (more) {
      const existing = new Set(blockedRecords.value.map((r) => r.id));
      blockedRecords.value = [...blockedRecords.value, ...list.filter((r) => !existing.has(r.id))];
    } else {
      blockedRecords.value = list;
    }
    blockedTotal.value = data.total ?? blockedRecords.value.length;
    blockedCursorTs.value = data.next_cursor_ts ?? null;
    blockedCursorId.value = data.next_cursor_id ?? null;
    blockedHasMore.value = !!data.has_more && data.next_cursor_ts != null && data.next_cursor_id != null;
    blockedPaginated.value = more;
    blockedError.value = false;
    blockedLoaded.value = true;
    if (activeTab.value === 'blocked') markBlockedSeen();
  } catch {
    // 「加载更多」失败时列表里仍有数据，不该抢走展示；只有首屏失败才落到重试态
    if (!more) blockedError.value = true;
    message.error(more ? '加载更多失败' : '加载拦截记录失败');
  } finally {
    blockedLoading.value = false;
    blockedLoadingMore.value = false;
  }
}

async function loadRules() {
  try {
    const { data } = await api.get(Endpoints.sms.rules);
    if (!data) return;
    rules.value = (data.rules || []) as SmsRuleItem[];
  } catch {
    /* silent：拿不到规则只会让「不再按此规则拦截」暂时禁用，不影响记录展示 */
  }
}

function findRule(record: SmsBlockedRecord): SmsRuleItem | undefined {
  return rules.value.find((r) => r.id === record.rule_id);
}

/** 只有「规则还在且仍启用」时才能停用它 */
function canDisableRule(record: SmsBlockedRecord): boolean {
  return !!findRule(record)?.enabled;
}

function ruleStateHint(record: SmsBlockedRecord): string {
  const rule = findRule(record);
  if (!rule) return '该规则已删除，无需再停用';
  if (!rule.enabled) return '该规则已停用';
  return '';
}

/** 命中规则的作用域 · 匹配方式，取记录里的快照字段。 */
function ruleSnapshotLabel(record: SmsBlockedRecord): string {
  const scope = SmsRuleScopeLabels[record.rule_scope as keyof typeof SmsRuleScopeLabels] ?? record.rule_scope;
  const match = SmsRuleMatchLabels[record.rule_match as keyof typeof SmsRuleMatchLabels] ?? record.rule_match;
  return `${scope} · ${match}`;
}

/**
 * 误拦后只提供「停用规则」，**不做「恢复这条短信」**：
 * 邮件已经没发、推送已经没推，事后补不回来。
 */
function confirmDisableRule(record: SmsBlockedRecord) {
  const rule = findRule(record);
  if (!rule || !rule.enabled) return;
  dialog.warning({
    title: '不再按此规则拦截',
    content: `将停用规则「${rule.pattern}」。之后命中它的短信会正常提醒与转发；已经被拦下的短信不会补发。`,
    positiveText: '停用规则',
    negativeText: '取消',
    onPositiveClick: async () => {
      try {
        // 字段级合并：只传 enabled，pattern / scope 由 core 保留原值
        await api.put(Endpoints.sms.rule(rule.id), { enabled: false });
        message.success('规则已停用');
        await loadRules();
      } catch (e: any) {
        message.error(e?.response?.data?.message || e?.response?.data?.error || '停用失败');
      }
    },
  });
}

async function deleteBlocked(record: SmsBlockedRecord) {
  try {
    await api.delete(Endpoints.sms.blockedRecord(record.id));
    blockedRecords.value = blockedRecords.value.filter((r) => r.id !== record.id);
    blockedTotal.value = Math.max(0, blockedTotal.value - 1);
    message.success('已删除');
  } catch {
    message.error('删除失败');
  }
}

function confirmClearBlocked() {
  dialog.warning({
    title: '清空拦截记录',
    content: '将删除全部拦截记录。规则不受影响，之后命中规则的短信仍会被拦下并重新记录。',
    positiveText: '清空',
    negativeText: '取消',
    onPositiveClick: async () => {
      try {
        await api.delete(Endpoints.sms.blocked);
        blockedRecords.value = [];
        blockedTotal.value = 0;
        blockedCursorTs.value = null;
        blockedCursorId.value = null;
        blockedHasMore.value = false;
        blockedPaginated.value = false;
        // 清空成功即「确定没有」，不能让更早一次读失败留下的重试态盖在此刻的空列表上
        blockedError.value = false;
        message.success('已清空');
      } catch {
        message.error('清空失败');
      }
    },
  });
}

/** 规则被增删改之后重新拉规则集合 —— 记录行的「不再按此规则拦截」是否可用要跟着变。 */
function onRulesChanged() {
  loadRules();
  if (!blockedPaginated.value) loadBlocked();
}

// ── 会话行「加入黑名单」──

function openContactMenu(e: MouseEvent, contact: Contact) {
  contactDropdownX.value = e.clientX;
  contactDropdownY.value = e.clientY;
  contactMenuTarget.value = contact;
}

function handleContactAction(key: string) {
  const contact = contactMenuTarget.value;
  contactMenuTarget.value = null;
  if (!contact) return;

  if (key === 'delete-conversation') {
    dialog.warning({
      title: '删除整段会话',
      content: `将删除与 ${contact.phone} 的全部短信记录，此操作不可恢复。`,
      positiveText: '删除全部',
      negativeText: '取消',
      onPositiveClick: async () => {
        try {
          await api.post('/api/sms/delete-conversation', { phone: contact.phone });
          message.success('已删除');
          loadContacts();
          // 如果当前正在看这段会话，关闭它（连同多选模式）
          if (selectedPhone.value === contact.phone) closeConversation();
        } catch {
          message.error('删除失败');
        }
      },
    });
    return;
  }

  if (key !== 'blacklist') return;
  dialog.warning({
    title: '加入黑名单',
    content:
      `该号码（${contact.phone}）之后的短信不再提醒、不再转发，也不会出现在列表里，` +
      '可在「已拦截」中查看。已收到的历史短信不受影响。',
    positiveText: '加入黑名单',
    negativeText: '取消',
    onPositiveClick: async () => {
      try {
        // 号码黑名单 = scope=sender + match_type=equals（只拦这个号码，不拦正文里提到它的短信）
        await api.post(Endpoints.sms.rules, {
          pattern: contact.phone,
          scope: 'sender',
          match_type: 'equals',
        });
        message.success('已加入黑名单');
        // 该号码的短信从此不出现在列表里，正看着的这段会话必须关掉（连同多选模式）
        if (selectedPhone.value === contact.phone) closeConversation();
        loadContacts();
        loadCounts();
        onRulesChanged();
      } catch (e: any) {
        message.error(e?.response?.data?.message || e?.response?.data?.error || '加入黑名单失败');
      }
    },
  });
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

/**
 * Esc 退出多选。与文件页（`FilesView.onKeydown`）同一条约定：
 * 多选是个「临时状态」，必须有不用鼠标也能退出的出口 —— 否则用户只能去点「取消」，
 * 而勾选了一屏之后他更想先"退出去看看"，不是先做决定。
 * 弹窗打开时不抢 Esc：那个键归 naive-ui 的弹窗自己（关弹窗），一次按键只该做一件事。
 */
function onKeydown(e: KeyboardEvent) {
  if (e.key !== 'Escape') return;
  if (showNewSms.value || showRawSms.value) return;
  if (selectMode.value) exitSelectMode();
}

// ── Lifecycle ──
onMounted(() => {
  window.addEventListener('keydown', onKeydown);
  loadContacts();
  loadCounts();
  // 首页拦截记录挂载即拉一次：角标要在用户还没点进「已拦截」时就能看到
  loadBlocked();
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

// 拦截记录单独用更慢的节奏：它同时供角标使用，所以不能只在该 tab 可见时刷；
// 已翻页时跳过，否则会把加载出来的历史页清掉（同 AlertListPanel 的 paginated 守卫）。
useInterval(() => {
  if (!blockedPaginated.value) loadBlocked();
}, 30_000);

onUnmounted(() => {
  window.removeEventListener('keydown', onKeydown);
});
</script>

<style scoped>
/* 高度链路（2026-10-21）：页面不再用 `calc(100dvh - 250px)` 把「顶栏 + 卡头 + tab 头 +
   布局内距」估成一个固定数字。估错一档就整页多/少一截：多了出滚动条，少了把列表压扁。
   现在改成从内容区（.main-content 的滚动容器是 height:100%）一路 flex 传下来。 */
.sms-view {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
}

/* 卡壳是 GridCard（不归本批次），它自带 `overflow: hidden` —— 链路里任何一层没接上，
   多出来的高度会被**静默裁掉**而不是出现滚动条。所以三层各自声明 min-height: 0。 */
.sms-view :deep(.grid-card) {
  display: flex;
  flex-direction: column;
  flex: 1 1 auto;
  min-height: 0;
}

.sms-view :deep(.grid-card-body) {
  display: flex;
  flex-direction: column;
  flex: 1 1 auto;
  min-height: 0;
}

.sms-tabs {
  margin-top: var(--space-1);
  flex: 1 1 auto;
  min-height: 0;
}

/* n-tabs 自己已经是 flex 列，但 nav / pane-wrapper / pane 默认是普通块，
   高度传到 pane 就断了 —— 这三处补齐（inactive pane 靠 naive 的行内 display:none 隐藏，
   与这里的 display:flex 不冲突）。 */
.sms-tabs :deep(.n-tabs-nav) {
  flex: 0 0 auto;
}

.sms-tabs :deep(.n-tabs-pane-wrapper),
.sms-tabs :deep(.n-tab-pane) {
  display: flex;
  flex-direction: column;
  flex: 1 1 auto;
  min-height: 0;
}

.sms-body,
.codes-body,
.blocked-body {
  display: flex;
  flex: 1 1 auto;
  min-height: 0;
  border: 1px solid var(--border-subtle);
  border-radius: var(--radius-sm);
  overflow: hidden;
}

.codes-body,
.blocked-body {
  flex-direction: column;
  background: var(--card-bg);
}

.codes-scroll,
.blocked-scroll {
  flex: 1 1 auto;
  min-height: 0;
  overflow: hidden;
}

/* tab 标签里的角标（未查看的拦截记录数） */
.tab-label {
  display: inline-flex;
  align-items: center;
  gap: var(--space-2);
}

/* n-spin 包裹滚动区时默认不约束高度，导致内部滚动区高度坍塌无法滚动；
   让其填满并把高度向下传递（flex + min-height:0） */
.contacts-panel :deep(.n-spin-container),
.conversation-panel :deep(.n-spin-container),
.codes-body :deep(.n-spin-container),
.blocked-body :deep(.n-spin-container),
.contacts-panel :deep(.n-spin-content),
.conversation-panel :deep(.n-spin-content),
.codes-body :deep(.n-spin-content),
.blocked-body :deep(.n-spin-content) {
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
  border-right: 1px solid var(--border-subtle);
  display: flex;
  flex-direction: column;
  background: var(--card-bg);
}

.contacts-tabs {
  display: flex;
  border-bottom: 1px solid var(--border-subtle);
  flex-shrink: 0;
}

.tab-item {
  flex: 1;
  text-align: center;
  padding: 10px 0;
  font-size: var(--font-base);
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
  color: var(--accent-color);
  border-bottom-color: var(--accent-color);
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
  padding: var(--space-3);
  cursor: pointer;
  border-bottom: 1px solid var(--border-subtle);
  transition: background 0.15s;
  gap: 10px;
}

.contact-item:hover {
  background: var(--surface-hover);
}

.contact-item.active {
  background: var(--accent-color-light);
}

.contact-main {
  flex: 1;
  min-width: 0;
}

.contact-phone {
  font-size: var(--font-base);
  font-weight: 500;
  color: var(--text-primary);
  margin-bottom: var(--space-1);
}

.contact-preview {
  font-size: var(--font-sm);
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
  font-size: var(--font-xs);
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
  padding: 10px var(--space-4);
  border-bottom: 1px solid var(--border-subtle);
  background: var(--card-bg);
  flex-shrink: 0;
}

.conv-phone {
  font-size: var(--font-md);
  font-weight: 600;
  color: var(--text-primary);
}

.back-btn {
  font-size: var(--font-lg);
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
  padding: var(--space-4);
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
}

/* ── Load more ── */
.load-more-row {
  display: flex;
  justify-content: center;
  padding: 2px 0 6px;
}

.load-more-hint {
  font-size: var(--font-xs);
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

/* ── 多选模式 ── */
.msg-row.selectable {
  align-items: center;
  gap: 8px;
  cursor: pointer;
}

/* 复选框贴着气泡：incoming 在左、outgoing 靠右整组右对齐，
   所以它总在气泡的内侧，不会把已发气泡推离右边缘。 */
.msg-check {
  flex-shrink: 0;
  display: inline-flex;
}

.msg-row.selectable .msg-bubble {
  cursor: pointer;
}

/* 选中态画在**整行**上而不是给气泡描边：已发气泡的底色就是 `--accent-color`，
   拿 accent 去描它的边在两种主题下都看不出来。整行底色与气泡自身颜色无关。 */
.msg-row.selected {
  background: var(--accent-color-light);
  border-radius: var(--radius-sm);
}

/* 禁用按钮上的 tooltip 需要外层接住指针事件：
   浏览器不对 disabled 表单控件派发鼠标事件，把 pointer-events 让给外层 span
   （否则「新建短信 / 发送」被禁用时，说明为什么不支持的提示永远弹不出来）。
   `flex-shrink: 0` 是替原来的按钮接住发送栏里的约束 —— 现在 span 才是那个 flex 子项。 */
.gated-wrap {
  display: inline-flex;
  flex-shrink: 0;
}

.gated-btn:disabled {
  pointer-events: none;
}

.msg-bubble {
  max-width: 70%;
  padding: 10px var(--space-3);
  border-radius: var(--radius-md);
  font-size: var(--font-base);
  line-height: 1.5;
  position: relative;
  word-break: break-word;
  cursor: context-menu;
}

.msg-row.incoming .msg-bubble {
  background: var(--card-bg);
  color: var(--text-primary);
  border-bottom-left-radius: 4px;
  border: 1px solid var(--border-subtle);
}

.msg-row.outgoing .msg-bubble {
  background: var(--accent-color);
  color: var(--on-accent);
  border-bottom-right-radius: 4px;
}

.msg-content {
  white-space: pre-wrap;
}

.msg-time {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-top: var(--space-1);
  font-size: var(--font-xs);
  opacity: 0.6;
  justify-content: flex-end;
}

.msg-dir-tag {
  font-size: var(--font-xs);
  opacity: 0.8;
}

/* ── Message input bar ── */
.msg-input-bar {
  display: flex;
  align-items: flex-end;
  gap: var(--space-2);
  padding: var(--space-3) var(--space-4);
  border-top: 1px solid var(--border-subtle);
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

/* ── 空态 ──
   空态统一走全局 `.client-empty`（main.css）。原先这里还有一份 `.panel-empty`
   （flex 居中 + 40px 内距），与全局那份做同一件事，只差内距，故删掉。
   `.conv-placeholder` 是唯一保留的本地修饰：它要占满整个会话区，
   否则「选择一个联系人」会贴着顶部而不是居中。 */
.conv-placeholder {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: var(--space-4) 0;
}

/* ── New SMS form ── */
.new-sms-form {
  display: flex;
  flex-direction: column;
  gap: var(--space-3);
}

.form-row {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.form-label {
  font-size: var(--font-base);
  color: var(--text-secondary);
  font-weight: 500;
}

/* ── 验证码列表 / 原文弹窗 ── */
.codes-list {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(260px, 1fr));
  gap: 10px;
  padding: var(--space-3);
}

.code-item {
  padding: var(--space-3);
  border: 1px solid var(--border-subtle);
  border-radius: var(--radius-sm);
  background: var(--card-bg);
}

.code-value {
  font-size: var(--font-xl);
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
  margin-top: var(--space-1);
  font-size: var(--font-sm);
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
  font-size: var(--font-xs);
  color: var(--text-muted);
  line-height: 1.5;
  word-break: break-word;
}

.code-actions {
  display: flex;
  gap: var(--space-2);
  margin-top: 10px;
}

.raw-sms {
  display: flex;
  flex-direction: column;
}
.raw-content {
  margin-top: var(--space-3);
  padding: var(--space-3);
  border-radius: var(--radius-sm);
  background: var(--page-bg);
  font-size: var(--font-base);
  line-height: 1.6;
  color: var(--text-primary);
  white-space: pre-wrap;
  word-break: break-word;
}

/* ── 已拦截列表 ── */
.blocked-list {
  display: flex;
  flex-direction: column;
  padding: var(--space-1) var(--space-3);
}

.blocked-item {
  padding: var(--space-3) 0;
  border-bottom: 1px solid var(--border-subtle);
}

.blocked-item:last-child {
  border-bottom: none;
}

.blocked-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
}

.blocked-sender {
  font-size: var(--font-base);
  font-weight: 500;
  color: var(--text-primary);
  word-break: break-all;
}

.blocked-time {
  font-size: var(--font-xs);
  color: var(--text-muted);
  white-space: nowrap;
}

.blocked-snippet {
  margin-top: var(--space-1);
  font-size: var(--font-sm);
  line-height: 1.5;
  color: var(--text-secondary);
  word-break: break-word;
}

.blocked-meta {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: var(--space-2);
  margin-top: 6px;
  font-size: var(--font-sm);
  color: var(--text-muted);
}

.blocked-actions {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: var(--space-2);
  margin-top: var(--space-2);
}

.blocked-hint {
  font-size: var(--font-xs);
  color: var(--text-muted);
}

/* ── 中间档（768–1024）──
   侧栏在这一档已自动收起（DefaultLayout 的 NARROW_BREAKPOINT=1024），内容区反而比
   1025–1200px 窄。联系人栏的 `30% + min-width: 260px` 会把会话区压到 400px 出头，
   气泡一行只剩十几个字 —— 这一段把联系人栏的下限让出一档。
   必须写在 768px 的那段**之前**：同特异性下后者胜出，手机形态才不会被这里盖掉。 */
@media (max-width: 1024px) {
  .contacts-panel {
    width: 30%;
    min-width: 200px;
    max-width: 300px;
  }
}

/* ── Mobile responsive ── */
@media (max-width: 768px) {
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
