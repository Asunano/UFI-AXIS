<template>
  <div class="sms-view">
    <GridCard title="短信管理">
      <template #extra>
        <!--
          `wrap`：在 360–414px 宽度下，该区域需容纳「共 N 条 / N 条未读 + 刷新 / 全部已读 /
          多选 / 新建短信 + 拦截规则」共 7 个元素，不换行会导致相互挤压（文案重叠、按钮缩小至
          不可点击）。换行使高度增加一至两行，但每个动作保持原尺寸与可读性。
        -->
        <n-space :size="8" align="center" wrap>
          <template v-if="activeTab === 'messages'">
            <!--
              多选动作条。进入多选后它**替换**整块页面级动作，而非并列追加：
              多选期间「刷新 / 全部已读 / 新建短信」与当前所选条目无关，
              保留这些按钮会在用户确认所选内容时引入四个语义无关的动作，
              并与「删除」争夺注意力。
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
              <!-- 计数读取失败不得表示为「没有」：两个胶囊均带 v-if，失败时会一并消失，
                   外观与「共 0 条」完全一致，因此单独提供可重试入口 -->
              <n-button v-if="countsError" size="small" quaternary type="error" @click="loadCounts">
                统计读取失败，重试
              </n-button>
              <n-button size="small" :loading="contactsLoading" @click="refreshAll"> 刷新 </n-button>
              <n-button v-if="unreadCount > 0" size="small" @click="markAllRead"> 全部已读 </n-button>
              <n-button v-if="canEnterSelectMode" size="small" @click="enterSelectMode"> 多选 </n-button>
              <!--
                发短信入口的能力门禁（Capability.SMS）。禁用判据一律使用 `caps.unsupported()`，
                不使用 `!caps.supports()`：后者在能力集尚未拉取或拉取失败时同样成立，
                会使整个入口始终禁用（见 stores/capabilities.ts 文件头）。
                外层 span 用于承载 tooltip 的悬停事件 —— 浏览器不对 disabled 表单控件
                派发鼠标事件，而 `.gated-btn:disabled` 的 pointer-events:none 将悬停交给外层。
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
              「验证码设置」（启用提取 / 清理周期）与下方「拦截规则」形态一致 ——
              设置项不进入 tab 清单，点击后打开弹窗；入口位于「验证码」tab 的动作区，
              因为这两项设置仅影响该 tab 的内容。
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
          <!-- 拦截规则属于设置项而非内容维度，因此置于 header 动作区，不占用第四个 tab -->
          <n-button size="small" quaternary @click="filterModal.open()"> 拦截规则 </n-button>
        </n-space>
      </template>

      <n-tabs v-model:value="activeTab" type="segment" animated class="sms-tabs">
        <!-- show:lazy：首次进入时才渲染，之后通过 display 切换，以保留会话滚动位置与已加载消息 -->
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
                  <!-- 「读取失败」与「确实没有」必须区分：前者提供重试入口，后者才显示空态 -->
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

              <!-- 会话行右键菜单：与消息气泡使用同一套「单实例 n-dropdown + 手动定位」实现 -->
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

                 `visible` 仅在 ≤768px 生效（基础样式中对话面板本身即为 display:flex，
                 仅移动端段落将其设为 none）。因此判据为「已选中联系人」——
                 若使用 `selectedPhone || isMobile`，移动端未选联系人时该条件恒为真，
                 对话面板显示，而联系人面板的 `hidden`（要求 selectedPhone && isMobile）
                 不成立，两块面板同时处于同一 flex 行，100% + flex:1 会使页面产生横向滚动。 -->
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
                          复选框仅在多选模式下出现。乐观气泡（`opt-<ts>`，已发送未确认）的
                          复选框处于禁用状态：它没有后端 id，混入 `ids` 会使 core 拒绝整批请求（400）。
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

                  <!-- 单个右键菜单实例（不为每条气泡各渲染一个 n-dropdown） -->
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
                    不支持短信能力时输入框一并禁用：可输入但无法发送的输入框会让用户在
                    写完内容后才发现无法提交。提示直接写入 placeholder，
                    因为 tooltip 在 disabled 的输入框上同样无法显示。
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
                <!-- 同联系人列表：「读取失败」单独提供重试入口，「确实没有」才显示空态 -->
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

        <!-- 已拦截：与 app 的「已拦截」入口一致；沿用 show:lazy 以保留滚动位置与已加载页 -->
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
                      <!-- 命中规则使用记录中的快照字段：即使规则已被删除，该记录仍可解读 -->
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

    <!-- New SMS modal（宽高由 .modal-fit 统一约束，不使用行内 width） -->
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

    <!-- 验证码原文弹窗：正文可能很长，--modal-h 使内容区独立滚动、标题保持固定 -->
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

    <!-- 拦截规则弹窗：懒加载，挂载与显隐分两步（否则会丢失进出场动画） -->
    <component
      :is="filterComponent"
      v-if="filterComponent"
      :show="filterShow"
      @update:show="filterModal.setShow"
      @changed="onRulesChanged"
    />

    <!-- 验证码设置弹窗：懒加载，与拦截规则同一套「挂载与显隐分两步」 -->
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
 * ## 语义边界
 * `Capability.SMS` **仅覆盖"发送"这一个写动作**：`contract/Capabilities.kt` 的枚举 KDoc
 * 声明「覆盖的写动作：`POST /api/sms/send`」，门禁落点也只有 `RootSmsRoutes` 的 `/sms/send`；
 * 该 route 的注释进一步说明「信箱的读 / 删 / 标已读**不在此域内**」。
 * 而 `DeviceAdapter.sms`（信箱查询 / 发送 / 删除 / 已读标记 / 条数统计）对每台设备均**非空** ——
 * 即"没有短信能力"表示**无法发送**，而非"没有信箱"。
 *
 * 因此此处只对两个**发短信入口**施加门禁（页面上的「新建短信」与会话中的「发送」/ 输入框）；
 * 浏览列表、验证码、已拦截、单条 / 整段 / 批量删除、标记已读 / 未读**均照常可用**。
 * 若以 `Capability.SMS` 禁用删除，用户将在一个可见短信列表的页面上遇到无法删除的列表。
 *
 * ## 判据
 * 一律使用 `unsupported()`（仅在"明确不支持"时为 true），**不使用** `!supports()`：
 * 后者在能力集尚未拉取或拉取失败时同样成立，会使入口始终禁用
 * （见 stores/capabilities.ts 文件头）。
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
 * 三个失败状态的出口（此前为 `catch {}`）。
 *
 * 必须保留状态：联系人 / 计数 / 会话三个接口失败时，界面会退化为
 * 「暂无短信」「共 0 条」—— 与「设备中确实没有短信」在屏幕上完全一致，
 * 会被理解为短信丢失（或已被拦截规则过滤）。失败状态必须可见，并提供重试。
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

// 已发送但后端尚未确认的乐观消息（发送后即时显示，避免界面不更新）
const optimisticMessages = ref<SmsMessage[]>([]);

// ── 批量删除 ──
//
// 三条删除接口语义不同，**不合并调用**（契约中分为三条的理由见 `Endpoints.sms.deleteBatch`）：
//   · `POST /api/sms/delete`             单条（消息气泡右键菜单）
//   · `POST /api/sms/delete-conversation` 按号码删除整段（会话行右键菜单）
//   · `POST /api/sms/delete-batch`        按短信 id 批量删除
//
// ## 多选作用于**消息列表**而非会话列表
// deleteBatch 接受的是短信 id；会话行只有号码，没有 id，删除整段需走 delete-conversation，
// 两者语义不同（"删除整段会话" ≠ "删除所勾选的条目"）。若多选置于会话行上，
// 用户勾选三段会话后按「删除」，代码无法确定应调用哪条接口，错误选择会删除整段会话。
//
// ## 入口使用按钮而非长按
// web 侧既有交互为**右键菜单**（消息气泡与会话行均使用 `@contextmenu`），
// 长按在该界面中无先例；且长按在桌面浏览器中会被文本选择或拖拽拦截，
// 在移动端浏览器中还会触发系统的长按选择与放大镜。app 使用 Compose `combinedClickable`
// 自行实现长按，行为不同。显式按钮可发现、可键盘触达，代价仅为增加一个按钮。
const selectMode = ref(false);
/** 勾选的短信 id。**仅保存后端 id（number）**：乐观气泡的 `opt-<ts>` 为字符串，混入会导致整批请求返回 400。 */
const selectedIds = ref<Set<number>>(new Set());
const deleting = ref(false);

// ── 验证码 / 短信原文 ──
// 验证码与短信同为页面主体，用页内 tab 切换
const activeTab = ref<'messages' | 'codes' | 'blocked'>('messages');
const codesLoading = ref(false);
const codes = ref<VerificationCode[]>([]);
/** 同 contacts / messages：请求失败不得呈现为「暂无验证码」，需保留可重试的出口。 */
const codesError = ref(false);
const codesLoaded = ref(false);
const showRawSms = ref(false);

const rawLoading = ref(false);
const rawSms = ref<SmsMessage | null>(null);

// ── 已拦截（core 命中规则后不推送、不转发、不返回；web 仅渲染记录）──
// 分页方式与告警列表一致：keyset 游标 + 「加载更多」，游标为 (blocked_at, id) 两个显式参数。
const BLOCKED_PAGE = 50;
/** 「已查看水位」：id 大于该值的记录计入角标。与 app 的 `smsBlockedSeenId` 语义相同。 */
const LS_BLOCKED_SEEN_ID = 'ufi_axis_sms_blocked_seen_id';
const blockedRecords = ref<SmsBlockedRecord[]>([]);
const blockedLoading = ref(false);
const blockedLoadingMore = ref(false);
/** 同 codesError：加载失败且当前无数据时，列表区需显示「读取失败，点击重试」。 */
const blockedError = ref(false);
const blockedLoaded = ref(false);
const blockedTotal = ref(0);
const blockedCursorTs = ref<number | null>(null);
const blockedCursorId = ref<number | null>(null);
const blockedHasMore = ref(false);
/** 已翻过页时不再自动刷新首页，否则会清除用户已加载的历史页（同 AlertListPanel）。 */
const blockedPaginated = ref(false);
const blockedSeenId = ref(readBlockedSeenId());
/** 规则集合仅用于判断「命中的规则是否存在、是否已停用」；展示一律使用记录中的快照。 */
const rules = ref<SmsRuleItem[]>([]);

// 规则管理弹窗（懒加载）
const filterModal = useLazyModal(() => import('./components/modals/SmsFilterModal.vue'));
const filterComponent = filterModal.component;
const filterShow = filterModal.show;

// 验证码设置弹窗（懒加载；这两项设置仅影响本页的验证码 tab）
const codeSettingsModal = useLazyModal(() => import('./components/modals/SmsCodeSettingsModal.vue'));
const codeSettingsComponent = codeSettingsModal.component;
const codeSettingsShow = codeSettingsModal.show;

// ── Dropdown state ──
const msgMenuTarget = ref<SmsMessage | null>(null);
const msgDropdownX = ref(0);
const msgDropdownY = ref(0);

// 会话行右键菜单（与消息气泡同一套实现，仅菜单项不同）
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

// 展示用消息 = 权威消息 + 当前会话的乐观消息（已发送未确认）
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
 * 「全选」范围为**当前会话中已加载的条目**（受 core 单次 200 条上限约束），而非整段会话。
 * 不包含未加载的历史消息：纳入前需先分页拉取整段会话，而拉取完成后界面上仍只有当前可见的
 * 数十条 —— 界面与"全选"语义不一致时，一次点击可能删除数百条历史短信。
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
  // 已读状态仅对收到的短信有意义（后端 unread 仅统计 direction=received）
  if (target && target.direction === 'incoming' && !isOptimistic(target)) {
    options.push({ label: '标记为未读', key: 'markUnread' }, { type: 'divider', key: 'd1' });
  }
  options.push({
    key: 'delete',
    label: '删除',
    // 使用令牌而非 hex：基线扫描器只检查 <style>，JS 中的硬编码颜色不会被检测到，
    // 但同样无法跟随暗色模式与换肤（与 .msg-bubble 中使用令牌的其它位置保持同一来源）。
    render: (option: any) => h('span', { style: { color: 'var(--error)' } }, option.label),
  });
  return options;
});

const contactMenuOptions = computed(() => [
  { key: 'delete-conversation', label: '删除整段会话' },
  { type: 'divider', key: 'd-contact' },
  { key: 'blacklist', label: '加入黑名单' },
]);

/** 角标 = 已加载记录中 id 大于「已查看水位」的条数（与 app 的 smsBlockedUnviewed 语义相同）。 */
const blockedUnviewed = computed(() => blockedRecords.value.filter((r) => r.id > blockedSeenId.value).length);

// 原文弹窗中的方向文案
const rawDirectionLabel = computed(() => (rawSms.value?.direction === 'outgoing' ? '已发送' : '已接收'));

// ── Data loading ──
async function loadContacts() {
  contactsLoading.value = true;
  try {
    const { data } = await api.get('/api/sms/contacts');
    // 后端 getContactList() 实际返回的字段：phoneNumber / total / unread / latestMsg / latestTimestamp
    contacts.value = (data.contacts || []).map((c: any) => ({
      phone: c.phoneNumber ?? c.phone ?? '',
      count: c.total ?? c.count ?? 0,
      lastMessage: c.latestMsg ?? c.lastMessage ?? '',
      lastTime: c.latestTimestamp ?? c.lastTime ?? c.last_time ?? 0,
      unread: c.unread ?? 0,
    }));
    contactsError.value = false;
  } catch {
    // 不将失败写成空列表：列表区据此显示「读取失败，点击重试」
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
    // 后端按时间倒序返回（最新在前），反转后最新消息位于底部
    messages.value = msgs.slice().reverse();
    // 后端已确认的已发消息：移除对应乐观气泡，避免与权威数据重复
    const confirmedKeys = new Set(
      msgs.filter((m) => m.direction === 'outgoing').map((m) => `${m.phoneNumber}|${m.content}`)
    );
    if (confirmedKeys.size) {
      optimisticMessages.value = optimisticMessages.value.filter(
        (o) => !confirmedKeys.has(`${o.phoneNumber}|${o.content}`)
      );
    }
    conversationCount.value = data.total ?? data.count ?? msgs.length;

    // 将会话标记为已读
    const unreadMsgs = msgs.filter((m) => !m.read);
    if (unreadMsgs.length > 0) {
      api.post('/api/sms/read-conversation', { phone }).catch(() => {});
      loadCounts();
      loadContacts();
    }

    if (scroll) scrollToBottom();
  } catch {
    // 同 loadContacts：失败不得渲染为「暂无消息记录」，需让用户看到并可重试
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
  // 切换会话即退出多选：勾选内容属于上一个会话，保留多选模式会使新会话
  // 直接显示复选框，而用户并未在该会话中启用「多选」。
  exitSelectMode();
  loadConversation(phone);
}

/** 关闭当前会话（移动端返回）。多选模式随会话一同退出，否则会残留不可见的选中集。 */
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

/** 退出多选并清空勾选。取消 / 删除完成 / 切换会话 / 关闭会话 / 切换 tab 均调用此函数。 */
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
  // 替换为新的 Set，而非就地 add/delete：就地修改同样可触发更新（Vue 的集合是响应式的），
  // 但整体替换使"选中集已变更"在代码中显式可见，不依赖对响应式集合的隐式行为。
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
    // 明确删除条数与不可撤销性：该路径**没有**回收站，也无法像「标记已读」那样反向恢复。
    content: `将删除 ${ids.length} 条短信，此操作不可撤销。`,
    positiveText: '删除',
    negativeText: '取消',
    onPositiveClick: () => deleteSelected(ids),
  });
}

/**
 * `POST Endpoints.sms.deleteBatch`，body `{ ids }` → `{ success, deleted, requested }`。
 *
 * 提示文案依据 **`deleted`** 而非 `success`：core 的判据为 `deleted > 0`，
 * 因此"一条都未删除"时返回 `success: false` —— 这不属于操作失败，而是这些短信本已不存在
 * （通常已在别处删除）。若按「删除失败」提示，用户会误认为操作有误并反复重试。
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
    // 刷新列表：勾选期间的 10s 轮询可能已更新该批数据，重新拉取比本地过滤更准确
    loadContacts();
    loadCounts();
    if (selectedPhone.value) await loadConversation(selectedPhone.value, false);
  } catch (e: any) {
    // 三类失败分别提示，判据一律为 HTTP 状态码，**不使用** core 返回的中文 `message`
    // （该字段用于日志，措辞可能变化，作为判据等于将其变为契约）：
    //   · 400        —— 请求本身被拒绝（无有效 id），服务端未删除任何条目 ⇒ 重新选择；
    //   · 其它状态码 —— 服务端已接收但处理失败（5xx），删除结果不确定 ⇒ 稍后重试；
    //   · 无状态码   —— 请求未到达服务端（断网 / 超时）⇒ 先检查网络。
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
    // 乐观更新：发送成功后立即将已发气泡加入当前会话，避免后端写入延迟导致界面不更新
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
    // 后端写入存在延迟，稍后再次同步以确认已发消息并去重
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
    // 切换到该会话并乐观显示已发气泡
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
  // 多选期间不打开右键菜单：此时点击消息的意图是选中或取消选中，
  // 再弹出「删除 / 标记为未读」的单条菜单会在同一区域引入两套互相矛盾的语义。
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
    // 乐观气泡（opt-*）不是后端 ID，请求会被 core 以 400 拒绝
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
    // 保留上次成功获取的列表，仅标记失败状态（空态分支据此让位给重试入口）
    codesError.value = true;
    message.error('获取验证码失败');
  } finally {
    codesLoading.value = false;
  }
}

// 切换到验证码 tab 时首次加载；之后由 10s 轮询负责
watch(activeTab, (tab) => {
  // 离开「短信」tab 即退出多选：动作条仅存在于该 tab 的动作区，
  // 保留模式会使用户切回时仍看到复选框，而其间并未再次启用「多选」。
  if (tab !== 'messages') exitSelectMode();
  if (tab === 'codes' && !codesLoaded.value) loadVerificationCodes();
  if (tab === 'blocked') {
    // 规则集合仅在该 tab 使用（判断命中规则是否存在），进入时再拉取
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

/** 进入「已拦截」tab（或在该 tab 上刷新到新数据）即将水位提升至当前最大 id，角标归零。 */
function markBlockedSeen() {
  if (blockedRecords.value.length === 0) return;
  const maxId = Math.max(...blockedRecords.value.map((r) => r.id));
  if (maxId <= blockedSeenId.value) return;
  blockedSeenId.value = maxId;
  localStorage.setItem(LS_BLOCKED_SEEN_ID, String(maxId));
}

/**
 * GET /api/sms/blocked —— 列表字段为 `records`，游标为 `cursor_ts` + `cursor_id`
 * （两者必须成对传入，仅传其一会遗漏记录或产生重复）。
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
    // 「加载更多」失败时列表中仍有数据，不应切换为错误态；仅首屏失败才进入重试态
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
    /* silent：无法获取规则只会使「不再按此规则拦截」暂时禁用，不影响记录展示 */
  }
}

function findRule(record: SmsBlockedRecord): SmsRuleItem | undefined {
  return rules.value.find((r) => r.id === record.rule_id);
}

/** 仅当「规则存在且仍启用」时才能停用 */
function canDisableRule(record: SmsBlockedRecord): boolean {
  return !!findRule(record)?.enabled;
}

function ruleStateHint(record: SmsBlockedRecord): string {
  const rule = findRule(record);
  if (!rule) return '该规则已删除，无需再停用';
  if (!rule.enabled) return '该规则已停用';
  return '';
}

/** 命中规则的作用域与匹配方式，取自记录中的快照字段。 */
function ruleSnapshotLabel(record: SmsBlockedRecord): string {
  const scope = SmsRuleScopeLabels[record.rule_scope as keyof typeof SmsRuleScopeLabels] ?? record.rule_scope;
  const match = SmsRuleMatchLabels[record.rule_match as keyof typeof SmsRuleMatchLabels] ?? record.rule_match;
  return `${scope} · ${match}`;
}

/**
 * 误拦后仅提供「停用规则」，**不提供「恢复这条短信」**：
 * 邮件与推送均已跳过，无法事后补发。
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
        // 字段级合并：仅传 enabled，pattern / scope 由 core 保留原值
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
        // 清空成功后即为「确定没有」，不得让更早读取失败残留的重试态覆盖当前空列表
        blockedError.value = false;
        message.success('已清空');
      } catch {
        message.error('清空失败');
      }
    },
  });
}

/** 规则被增删改后重新拉取规则集合 —— 记录行的「不再按此规则拦截」可用状态随之变化。 */
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
          // 若当前正在查看该会话，则关闭它（连同多选模式）
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
        // 号码黑名单 = scope=sender + match_type=equals（仅拦截该号码，不拦截正文中提及该号码的短信）
        await api.post(Endpoints.sms.rules, {
          pattern: contact.phone,
          scope: 'sender',
          match_type: 'equals',
        });
        message.success('已加入黑名单');
        // 该号码的短信此后不再出现在列表中，正在查看的会话必须关闭（连同多选模式）
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

// 验证码与原文场景需要完整时间（utils.ts 中没有现成的日期格式化函数）
function formatFullTime(ts: number): string {
  if (!ts) return '未知时间';
  const time = ts > 1e12 ? ts : ts * 1000;
  return new Date(time).toLocaleString('zh-CN');
}

function scrollToBottom() {
  // 使用 NScrollbar 自带的 scrollTo 滚动到底部（position:'bottom' 对应 MAX_SAFE_INTEGER，
  // 不依赖已测量的高度；behavior 默认为即时，无平滑动画）
  nextTick(() => {
    const inst: any = scrollbarRef.value;
    if (inst && typeof inst.scrollTo === 'function') {
      inst.scrollTo({ position: 'bottom' });
    }
  });
}

/**
 * Esc 退出多选。与文件页（`FilesView.onKeydown`）遵循同一约定：
 * 多选是临时状态，必须提供不使用鼠标即可退出的出口，否则用户只能点击「取消」。
 * 弹窗打开时不处理 Esc：该按键归 naive-ui 的弹窗用于关闭，一次按键只应触发一个动作。
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
  // 拦截记录在挂载时拉取一次：角标需在用户进入「已拦截」之前可见
  loadBlocked();
});

useInterval(() => {
  loadCounts();
  // 新号码的短信只会出现在联系人列表中，必须一并刷新
  loadContacts();
  if (selectedPhone.value) {
    // 轮询不强制滚动到底部，避免用户阅读历史消息时被拉回底部
    loadConversation(selectedPhone.value, false);
  }
  // 仅在验证码 tab 可见时刷新，避免无效请求
  if (activeTab.value === 'codes') loadVerificationCodes();
}, 10_000);

// 拦截记录使用较慢的独立节奏：该数据同时供角标使用，因此不能仅在该 tab 可见时刷新；
// 已翻页时跳过，否则会清除已加载的历史页（同 AlertListPanel 的 paginated 守卫）。
useInterval(() => {
  if (!blockedPaginated.value) loadBlocked();
}, 30_000);

onUnmounted(() => {
  window.removeEventListener('keydown', onKeydown);
});
</script>

<style scoped>
/* 高度链路：不使用 `calc(100dvh - 250px)` 将「顶栏 + 卡头 + tab 头 + 布局内距」
   估算为固定数值 —— 估算偏差会使整页多出或缺少一截（多则出现滚动条，少则压缩列表）。
   改为自内容区（.main-content 的滚动容器为 height:100%）逐层通过 flex 传递。 */
.sms-view {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
}

/* 卡壳为 GridCard，其自身带有 `overflow: hidden` —— 链路中任意一层未衔接时，
   多余高度会被**静默裁剪**而非产生滚动条。因此三层各自声明 min-height: 0。 */
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

/* n-tabs 本身已是 flex 列，但 nav / pane-wrapper / pane 默认为普通块，
   高度传递到 pane 即中断 —— 需补齐这三处（未激活的 pane 由 naive 以行内 display:none
   隐藏，与此处的 display:flex 不冲突）。 */
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

/* n-spin 包裹滚动区时默认不约束高度，导致内部滚动区高度塌陷而无法滚动；
   使其填满并把高度向下传递（flex + min-height:0） */
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

/* 选中态绘制在**整行**而非为气泡描边：已发气泡的底色即为 `--accent-color`，
   以 accent 描边在两种主题下均不可见。整行底色与气泡自身颜色无关。 */
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
   空态统一使用全局 `.client-empty`（main.css）。
   `.conv-placeholder` 是唯一保留的本地修饰：它需占满整个会话区，
   否则「选择一个联系人」会贴近顶部而非居中。 */
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
   该区间侧栏已自动收起（DefaultLayout 的 NARROW_BREAKPOINT=1024），内容区宽度反而小于
   1025–1200px。联系人栏的 `30% + min-width: 260px` 会把会话区压缩到 400px 出头，
   气泡单行仅剩十余字 —— 此处下调联系人栏的最小宽度。
   必须写在 768px 段落**之前**：同特异性下后者优先，避免影响移动端布局。 */
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
