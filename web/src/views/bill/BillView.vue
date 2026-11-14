<template>
  <div class="bill-page">
    <!-- 页面内返回：不放进顶部状态栏（2026-11-08 用户要求）。
         顶部状态栏保持全局样式不动，返回/标题由页面自己承担。 -->
    <div class="bill-head">
      <n-button size="small" quaternary @click="goBack">
        <template #icon>
          <n-icon :size="15"><ChevronBackOutline /></n-icon>
        </template>
        返回设置
      </n-button>
      <span class="bill-title">话费账单</span>
      <span class="bill-head-spacer" />
      <n-button size="small" secondary aria-label="账单设置" @click="openBillSettings">
        <template #icon>
          <n-icon :size="16"><SettingsOutline /></n-icon>
        </template>
        设置
      </n-button>
    </div>

    <div class="bill-layout" :class="{ 'no-rail': !accounts.length }">
      <!-- 左：账号栏 -->
      <aside v-if="accounts.length" class="bill-rail">
        <!-- 窄屏（≤1024）：一行摘要，点开才展开完整账号卡。
         之前整张账号卡排在账单上方（8 个账号高 613px），手机上首屏看不到任何账单信息。 -->
        <div
          class="roster"
          role="button"
          tabindex="0"
          :aria-expanded="rosterOpen"
          @click="toggleRoster"
          @keydown.enter.prevent="toggleRoster"
          @keydown.space.prevent="toggleRoster"
        >
          <span class="av">{{ activeAccount ? accountAvatar(activeAccount) : '＋' }}</span>
          <div class="roster-main">
            <div class="roster-title">{{ activeAccount ? accountTitle(activeAccount) : '选择账号' }}</div>
            <div class="roster-sub">
              {{ activeAccount ? accountSubtitle(activeAccount) : `${accounts.length} 个账号` }}
            </div>
          </div>
          <span class="roster-toggle">{{ rosterOpen ? '收起' : '切换' }}</span>
        </div>

        <n-card v-show="!isNarrow || rosterOpen" size="small" title="账号" class="accounts-card">
          <!-- 账号搜索：账号多时（10+）直接过滤比滚动快 -->
          <n-input
            v-model:value="accountQuery"
            size="small"
            clearable
            placeholder="搜索账号 / 号码"
            class="acct-search"
            :input-props="{ 'aria-label': '搜索账号或号码' }"
          >
            <template #prefix
              ><n-icon :size="14"><SearchOutline /></n-icon
            ></template>
          </n-input>

          <div class="account-list">
            <template v-for="g in accountGroups" :key="g.key">
              <div v-if="g.items.length" class="acct-grp">{{ g.label }} · {{ g.items.length }}</div>
              <div
                v-for="a in g.items"
                :key="a.id"
                class="account-item"
                :class="{ active: a.id === activeAccountId }"
                role="button"
                tabindex="0"
                :aria-current="a.id === activeAccountId ? 'true' : undefined"
                :aria-label="`${accountTitle(a)}，${accountSubtitle(a)}`"
                @click="selectAccount(a.id)"
                @keydown.enter.prevent="selectAccount(a.id)"
                @keydown.space.prevent="selectAccount(a.id)"
              >
                <span class="av">{{ accountAvatar(a) }}</span>
                <div class="account-main">
                  <div class="account-title" :title="accountTitle(a)">{{ accountTitle(a) }}</div>
                  <div class="acct-sub" :title="accountSubtitle(a)">{{ accountSubtitle(a) }}</div>
                </div>
                <span class="st" :class="a.state === 'ready' ? 'on' : 'warn'" :title="accountStateLabel(a.state)" />
                <n-dropdown
                  trigger="click"
                  :options="[
                    { label: '改名', key: 'rename' },
                    { label: '重新登录', key: 'login' },
                    { label: '删除', key: 'remove' },
                  ]"
                  @select="(k: string) => onAccountMenu(k, a)"
                >
                  <n-button size="tiny" quaternary class="acct-more" aria-label="账号操作" @click.stop>⋯</n-button>
                </n-dropdown>
              </div>
            </template>
            <div v-if="!visibleAccounts.length" class="acct-empty">没有匹配的账号</div>
          </div>
          <!-- 添加按钮固定在卡片底部，不随列表滚动 -->
          <div
            class="account-add"
            role="button"
            tabindex="0"
            @click="showAdd = true"
            @keydown.enter.prevent="showAdd = true"
            @keydown.space.prevent="showAdd = true"
          >
            ＋ 添加账号
          </div>
        </n-card>

        <!-- 添加账号弹窗：类型 → 运营商 → 号码 → 备注，一屏完成 -->
        <n-modal v-model:show="showAdd" preset="card" title="添加账号" class="modal-fit" style="--modal-w: 460px">
          <div class="add-form">
            <div class="field">
              <label>账号类型</label>
              <n-radio-group v-model:value="addForm.access" size="small">
                <n-radio-button value="carrier">运营商账号</n-radio-button>
                <n-radio-button value="iot_card">物联卡</n-radio-button>
              </n-radio-group>
            </div>

            <template v-if="addForm.access === 'carrier'">
              <div class="field">
                <label>运营商</label>
                <n-radio-group v-model:value="addForm.carrier" size="small">
                  <n-radio-button value="mobile">中国移动</n-radio-button>
                  <n-radio-button value="unicom">中国联通</n-radio-button>
                </n-radio-group>
              </div>
              <div v-if="addForm.carrier === 'mobile'" class="field">
                <label>手机号</label>
                <n-input
                  v-model:value="addForm.phone"
                  size="small"
                  maxlength="11"
                  placeholder="11 位手机号"
                  :input-props="{ 'aria-label': '手机号' }"
                />
              </div>
            </template>
            <template v-else>
              <div class="field">
                <label>物联卡站点</label>
                <n-radio-group v-model:value="addForm.iotProvider" size="small">
                  <n-radio-button value="cloud">云上卡</n-radio-button>
                  <n-radio-button value="xj">小鲸卡</n-radio-button>
                </n-radio-group>
              </div>
              <div class="field">
                <label>充值号</label>
                <n-input
                  v-model:value="addForm.card"
                  size="small"
                  maxlength="20"
                  :placeholder="`${iotProviderLabel(addForm.iotProvider)}充值号（即卡号）`"
                  :input-props="{ 'aria-label': `${iotProviderLabel(addForm.iotProvider)}充值号` }"
                />
              </div>
              <div v-if="addForm.iotProvider === 'xj'" class="field">
                <label>手机号后四位</label>
                <n-input
                  v-model:value="addForm.verify"
                  size="small"
                  maxlength="4"
                  placeholder="卡绑定手机号的后四位"
                  :input-props="{ 'aria-label': '绑定手机号后四位' }"
                />
              </div>
            </template>

            <div class="field">
              <label>备注（可选）</label>
              <n-input
                v-model:value="addForm.label"
                size="small"
                placeholder="例如「主卡」「备用卡」"
                :input-props="{ 'aria-label': '账号备注' }"
              />
            </div>

            <p class="hint">
              {{
                addForm.access === 'iot_card'
                  ? '云上卡不需要验证码或密码，添加后直接登录读取余额 / 流量 / 到期。'
                  : '添加后需要登录：中国移动用短信验证码，中国联通扫码。发码会产生真实短信费用。'
              }}
            </p>
          </div>

          <template #footer>
            <div class="add-foot">
              <n-button size="small" @click="showAdd = false">取消</n-button>
              <n-button size="small" type="primary" :loading="busy" @click="createAccount">
                {{ addForm.access === 'iot_card' ? '添加并登录' : '添加并去登录' }}
              </n-button>
            </div>
          </template>
        </n-modal>
      </aside>

      <main class="bill-main">
        <n-card v-if="!bill && showSkeleton" size="small" class="login-cta">
          <n-skeleton text :repeat="3" />
        </n-card>

        <n-card v-else-if="!bill && status?.state === 'ready'" title="账单读取失败" size="small">
          <p class="hint">运营商会话仍有效，只是这次取数失败。重试即可，不需要重新登录。</p>
          <p v-if="error" class="error">{{ error }}</p>
          <n-button size="small" :loading="loading" @click="load(true)">重试</n-button>
          <n-button size="tiny" quaternary class="logout-inline" @click="logout">退出登录</n-button>
        </n-card>

        <!-- 未登录：右栏只放入口，真正的表单在弹窗里 -->
        <n-card v-else-if="!bill" size="small" class="login-cta">
          <div class="cta-title">
            {{ status?.state === 'qr_pending' ? '等待联通扫码' : isIotActive ? '未登录云上卡' : '未登录运营商账号' }}
          </div>
          <p class="hint">
            {{
              isIotActive
                ? '云上卡只需充值号即可登录，会话保存在设备 core。'
                : '登录状态保存在设备 core，手机端与 Web 共用。移动用短信验证码，联通扫码。'
            }}
          </p>
          <div class="row">
            <n-button size="small" type="primary" @click="loginOpen = true">登录</n-button>
            <n-button size="small" quaternary @click="loginOpen = true">添加 / 换号</n-button>
          </div>
        </n-card>

        <!-- 已登录：账单 -->
        <template v-else>
          <n-alert v-if="billLoginRequired" type="warning" title="需要登录" class="cache-alert">
            登录状态已失效，以下为最近一次数据{{ cachedAtText(billCachedAt) }}。
            <n-button text type="primary" size="tiny" @click="loginOpen = true">去登录</n-button>
          </n-alert>
          <n-alert v-else-if="billStale" type="info" title="数据可能不是最新" class="cache-alert">
            实时刷新失败，以下为最近一次数据{{ cachedAtText(billCachedAt) }}。
          </n-alert>

          <n-card size="small" class="hero-card">
            <!-- 物联卡：状态 + 关键用量 + 刷新/退出收进卡内顶部，先给结论再给明细 -->
            <div v-if="isIotBill" class="iot-status-bar">
              <div class="iot-status-main">
                <span class="status-pill" :class="cloudStatusTone">
                  {{ bill.cloud_card?.status_text || '状态未知' }}
                </span>
                <span v-if="bill.cloud_card?.days_left != null" class="iot-status-days">
                  剩余 {{ daysLeftText(bill.cloud_card.days_left) }}
                </span>
              </div>
              <div class="iot-status-headline">{{ cloudHeadline }}</div>
              <div class="iot-status-actions">
                <n-button size="small" :loading="loading" @click="load(true)">刷新</n-button>
                <n-button size="small" quaternary @click="logout">退出登录 / 换号</n-button>
              </div>
            </div>

            <!-- A+C 融合：余额 / 套餐 / 环形用量并排，真实用量与订单收在同一张卡 -->
            <div
              class="bill-hero"
              :class="{
                'has-gauge': isIotBill && (showBillGauge || heroUnlimited),
                'iot-hero': isIotBill,
              }"
            >
              <div class="hero-bal">
                <div class="label">可用余额</div>
                <div class="balance"><span>¥</span>{{ money(bill.balance_yuan) }}</div>
                <div class="hero-who">
                  <b>{{
                    bill.carrier === 'unicom'
                      ? '中国联通'
                      : bill.carrier === 'iot_card'
                        ? bill.cloud_card?.provider_name || '物联卡'
                        : '中国移动'
                  }}</b>
                  <i>·</i><span>{{ bill.phone }}</span>
                </div>
              </div>
              <div v-if="bill.plan_name || bill.cloud_card" class="hero-plan">
                <div v-if="bill.plan_name" class="fact">
                  <span>当前套餐</span>
                  <b :title="bill.plan_name">{{ bill.plan_name }}</b>
                </div>
                <template v-if="bill.cloud_card">
                  <div class="fact">
                    <span>到期</span>
                    <b>{{ bill.cloud_card.expires_at || '—' }}</b>
                  </div>
                </template>
              </div>
              <div
                v-if="isIotBill && (showBillGauge || heroUnlimited)"
                class="hero-gauge"
                role="img"
                :aria-label="`流量已用 ${heroFlowPct}%`"
              >
                <template v-if="heroUnlimited">
                  <div class="gauge-solo">
                    <b>不限量</b>
                    <span>已用 {{ gb(heroUsedGb) }} GB</span>
                  </div>
                </template>
                <template v-else>
                  <div
                    class="gauge"
                    :class="{ 'gauge-alert': cloudStatusTone === 'bad' || heroFlowPct >= 100 }"
                    :style="{ '--p': heroFlowPct }"
                  >
                    <div class="gauge-val">
                      <b>{{ heroFlowPct }}%</b>
                      <span>已用</span>
                    </div>
                  </div>
                  <div class="gauge-side">
                    <b>{{ gb(heroRemainingGb) }} GB</b>
                    <span>套餐剩余</span>
                    <small>套餐已用 {{ gb(heroUsedGb) }} / 共 {{ gb(heroTotalGb) }} GB</small>
                  </div>
                </template>
              </div>
            </div>
            <div
              v-if="bill.last_month_bill_yuan != null || bill.current_bill_yuan != null || bill.points != null"
              class="hero-stats"
            >
              <div v-if="bill.last_month_bill_yuan != null" class="stat">
                <span>上月账单</span><b>¥{{ money(bill.last_month_bill_yuan) }}</b>
              </div>
              <div v-if="bill.current_bill_yuan != null" class="stat">
                <span>本期账单</span><b>¥{{ money(bill.current_bill_yuan) }}</b>
              </div>
              <div v-if="bill.points != null" class="stat">
                <span>积分</span><b>{{ bill.points }}</b>
              </div>
            </div>
            <div v-if="bill.cloud_card" class="meta">
              <span v-if="bill.cloud_card.days_left != null">剩余 {{ daysLeftText(bill.cloud_card.days_left) }}</span>
              <span v-if="bill.cloud_card.real_name_text">{{ bill.cloud_card.real_name_text }}</span>
              <span v-if="bill.cloud_card.series_name">{{ bill.cloud_card.series_name }}</span>
            </div>
            <div v-if="bill.cloud_card?.iccid" class="sub">ICCID {{ bill.cloud_card.iccid }}</div>
            <div class="meta">
              <span v-if="bill.owner_name">机主 {{ bill.owner_name }}</span>
              <span v-if="bill.tenure_months != null">网龄 {{ tenure(bill.tenure_months) }}</span>
              <span v-if="bill.star_level != null">星级 {{ star(bill.star_level) }}</span>
              <span v-if="bill.current_bill_period || bill.current_bill_as_of">
                本期 {{ bill.current_bill_period || '—' }} · 截至 {{ bill.current_bill_as_of || '—' }}
              </span>
            </div>

            <section v-if="showBillFlow" class="bill-flow-section">
              <div v-if="!isIotBill" class="bill-section-head"><b>流量</b></div>
              <div v-if="!showBillGauge" class="flow">
                <div class="frow">
                  <span>{{ isIotBill ? '流量' : '通用' }}</span>
                  <div
                    v-if="!isIotBill"
                    class="bar"
                    role="progressbar"
                    aria-label="通用流量已用比例"
                    aria-valuemin="0"
                    aria-valuemax="100"
                    :aria-valuenow="usedPercent(bill.flow.general_used_gb, bill.flow.general_total_gb)"
                  >
                    <i :style="{ width: pct(bill.flow.general_used_gb, bill.flow.general_total_gb) }" />
                  </div>
                  <b v-if="isIotBill && bill.flow.general_unlimited" class="solo">不限量</b>
                  <b v-else>剩余 {{ gb(bill.flow.general_remaining_gb) }} GB</b>
                  <small v-if="isIotBill && bill.flow.general_unlimited"
                    >已用 {{ gb(bill.flow.general_used_gb) }} GB</small
                  >
                  <small v-else
                    >已用 {{ gb(bill.flow.general_used_gb) }} / 共 {{ gb(bill.flow.general_total_gb) }} GB ·
                    {{ usedPercent(bill.flow.general_used_gb, bill.flow.general_total_gb) }}%</small
                  >
                </div>
                <div v-if="!isIotBill" class="frow">
                  <span>定向</span>
                  <div
                    class="bar"
                    role="progressbar"
                    aria-label="定向流量已用比例"
                    aria-valuemin="0"
                    aria-valuemax="100"
                    :aria-valuenow="usedPercent(bill.flow.directed_used_gb, bill.flow.directed_total_gb)"
                  >
                    <i :style="{ width: pct(bill.flow.directed_used_gb, bill.flow.directed_total_gb) }" />
                  </div>
                  <b>剩余 {{ gb(bill.flow.directed_remaining_gb) }} GB</b>
                  <small
                    >已用 {{ gb(bill.flow.directed_used_gb) }} / 共 {{ gb(bill.flow.directed_total_gb) }} GB ·
                    {{ usedPercent(bill.flow.directed_used_gb, bill.flow.directed_total_gb) }}%</small
                  >
                </div>
                <div v-if="bill.flow.regional_remaining_gb != null" class="frow">
                  <span>区域</span>
                  <div
                    class="bar regional"
                    role="progressbar"
                    aria-label="区域流量已用比例"
                    aria-valuemin="0"
                    aria-valuemax="100"
                    :aria-valuenow="usedPercent(bill.flow.regional_used_gb, bill.flow.regional_total_gb)"
                  >
                    <i :style="{ width: pct(bill.flow.regional_used_gb, bill.flow.regional_total_gb) }" />
                  </div>
                  <b>剩余 {{ gb(bill.flow.regional_remaining_gb) }} GB</b>
                  <small
                    >已用 {{ gb(bill.flow.regional_used_gb) }} / 共 {{ gb(bill.flow.regional_total_gb) }} GB ·
                    {{ usedPercent(bill.flow.regional_used_gb, bill.flow.regional_total_gb) }}% · 有范围限制</small
                  >
                </div>
                <div v-if="!isIotBill" class="frow total">
                  <span>合计</span>
                  <div
                    class="bar"
                    role="progressbar"
                    aria-label="流量合计已用比例"
                    aria-valuemin="0"
                    aria-valuemax="100"
                    :aria-valuenow="usedPercent(totalUsed, totalAll)"
                  >
                    <i :style="{ width: pct(totalUsed, totalAll) }" />
                  </div>
                  <b
                    >剩余
                    {{
                      gb(
                        bill.flow.general_remaining_gb +
                          bill.flow.directed_remaining_gb +
                          (bill.flow.regional_remaining_gb || 0)
                      )
                    }}
                    GB</b
                  >
                  <small
                    >已用 {{ gb(totalUsed) }} / 共 {{ gb(totalAll) }} GB ·
                    {{ usedPercent(totalUsed, totalAll) }}%</small
                  >
                </div>
              </div>
              <div v-if="bill.voice" class="meta">
                语音：剩余 {{ bill.voice.remaining }} / 共 {{ bill.voice.total }} 分钟
              </div>
              <div v-if="bill.sms" class="meta">短信：剩余 {{ bill.sms.remaining }} / 共 {{ bill.sms.total }} 条</div>
              <div v-if="bill.monthly_used_gb != null" class="meta warn">
                本月累计 {{ gb(bill.monthly_used_gb) }} GB（含已过期流量包，与剩余量口径不同）
              </div>
            </section>

            <section v-if="isIotBill && hasRealDelta" class="bill-real-section">
              <div class="real-strip">
                <div class="strip-label">
                  <b>真实用量</b>
                  <span v-if="realRatio != null">虚量倍率 {{ realRatio.toFixed(2) }}×</span>
                </div>
                <div class="real-grid">
                  <div>
                    <b>{{ gb(realUsedGb) }} GB</b>
                    <span>真实已用</span>
                  </div>
                  <div>
                    <b>{{ realRemainingGb == null ? '—' : `${gb(realRemainingGb)} GB` }}</b>
                    <span>真实剩余</span>
                  </div>
                  <div>
                    <b>{{ realTotalGb == null ? '—' : `${gb(realTotalGb)} GB` }}</b>
                    <span>真实总量</span>
                  </div>
                </div>
              </div>
            </section>

            <section v-if="isIotBill" class="bill-orders-section">
              <div class="bill-section-head">
                <b>订单</b>
                <span class="sub">{{
                  cloudOrders.length
                    ? orderPageCount > 1
                      ? `最近 ${cloudOrders.length} 笔 · 第 ${safeOrderPage + 1}/${orderPageCount} 页`
                      : `最近 ${cloudOrders.length} 笔`
                    : '暂无订单'
                }}</span>
              </div>
              <div v-for="(o, i) in pagedOrders" :key="o.name + o.created_at + i" class="order-line">
                <i class="dot" />
                <div class="order-line-main">
                  <div class="l1">
                    <span class="name">{{ o.name }}</span>
                    <span v-if="o.status_text" class="m">{{ o.status_text }}</span>
                    <span v-if="o.pay_type_text" class="m">{{ o.pay_type_text }}</span>
                  </div>
                  <span v-if="o.created_at || o.expiration_time" class="l2">
                    {{
                      [
                        o.created_at ? fmtDateTime(o.created_at) : '',
                        o.expiration_time ? `有效期至 ${fmtDateTime(o.expiration_time)}` : '',
                      ]
                        .filter(Boolean)
                        .join(' · ')
                    }}
                  </span>
                </div>
                <span class="price">¥{{ money(o.paid_yuan ?? o.amount_yuan) }}</span>
              </div>
              <div v-if="orderPageCount > 1" class="order-pager">
                <n-button size="small" :disabled="safeOrderPage === 0" @click="prevOrderPage">上一页</n-button>
                <span class="sub">第 {{ safeOrderPage + 1 }} / {{ orderPageCount }} 页</span>
                <n-button size="small" :disabled="safeOrderPage >= orderPageCount - 1" @click="nextOrderPage"
                  >下一页</n-button
                >
              </div>
            </section>
          </n-card>

          <n-card v-if="!isIotBill && soon.length" title="即将到期" size="small">
            <div class="sub">24 小时内到期 · {{ soon.length }} 个包</div>
            <div v-for="p in soon" :key="p.name + p.valid_to" class="pkg">
              <n-tag size="small" :bordered="false">{{ kindLabel(p.kind) }}</n-tag>
              <span class="name">{{ p.name }}</span>
              <span class="right warn">{{ expiry(p.valid_to) }}</span>
            </div>
          </n-card>

          <n-card v-if="!isIotBill" title="全部流量包" size="small">
            <div class="sub">{{ bill.packages.length }} 个 · 剩余 {{ gb(packageRemaining) }} GB</div>
            <div class="pkg-table">
              <div class="pkg-head"><span>名称</span><span>总量</span><span>已用</span><span>到期</span></div>
              <div v-for="p in bill.packages" :key="p.name + p.valid_to" class="pkg-row">
                <span class="pkg-name">
                  <i class="kind">{{ kindLabel(p.kind) }}</i>
                  <b :title="p.name">{{ p.name }}</b>
                </span>
                <span class="pkg-nums">
                  <span class="num" data-label="总量">{{ gb(p.total_gb) }} GB</span>
                  <span class="num" data-label="已用">{{ gb(p.total_gb - p.remaining_gb) }} GB</span>
                </span>
                <span class="exp" :class="{ warn: soonKeys.has(p.name + p.valid_to) }">{{ expiry(p.valid_to) }}</span>
              </div>
            </div>
          </n-card>

          <n-alert v-for="w in warnings" :key="w" type="warning" :bordered="false" class="warn-box">{{ w }}</n-alert>

          <n-card v-if="!isIotBill" size="small" class="bill-actions-card">
            <div class="row end">
              <n-button size="small" :loading="loading" @click="load(true)">刷新</n-button>
              <n-button size="small" quaternary @click="logout">退出登录 / 换号</n-button>
            </div>
          </n-card>
        </template>

        <p v-if="error" class="error">{{ error }}</p>
      </main>
    </div>

    <!-- 登录表单（弹窗） -->
    <n-modal
      v-model:show="loginOpen"
      preset="card"
      :title="
        status?.state === 'qr_pending'
          ? '联通扫码登录'
          : isIotActive
            ? `登录${iotProviderLabel(activeIotProvider)}`
            : '登录运营商账号'
      "
      class="modal-fit login-modal"
      style="--modal-w: 520px"
    >
      <div class="login-body">
        <p class="login-lead">{{ loginLead }}</p>

        <template v-if="isIotActive">
          <div v-if="recordedPhone" class="login-target">
            <div class="login-target-main">
              <span class="login-target-label">已记录充值号</span>
              <b class="login-target-value">{{ recordedPhone }}</b>
            </div>
            <span class="login-meta">{{
              activeIotProvider === 'xj' ? '还需补填绑定手机号后四位' : '直接登录即可'
            }}</span>
          </div>
          <template v-else>
            <label class="login-label" for="iot-card">充值号</label>
            <n-input
              id="iot-card"
              v-model:value="cloudCard"
              :placeholder="`${iotProviderLabel(activeIotProvider)}充值号`"
              maxlength="20"
              :disabled="busy"
              size="large"
              class="login-field"
              :input-props="{ 'aria-label': `${iotProviderLabel(activeIotProvider)}充值号`, inputmode: 'numeric' }"
            />
          </template>
          <template v-if="activeIotProvider === 'xj'">
            <label class="login-label" for="iot-verify">绑定手机号后四位</label>
            <n-input
              id="iot-verify"
              v-model:value="cloudVerify"
              placeholder="后四位"
              maxlength="4"
              :disabled="busy"
              size="large"
              class="login-field"
              :input-props="{ 'aria-label': '绑定手机号后四位', inputmode: 'numeric', maxlength: 4 }"
            />
          </template>
          <n-button
            type="primary"
            size="large"
            block
            class="login-primary"
            :loading="busy"
            :disabled="
              (!recordedPhone && cloudCard.length < 4) || (activeIotProvider === 'xj' && cloudVerify.length !== 4)
            "
            @click="loginCloudCardActive"
          >
            登录并读取
          </n-button>
        </template>

        <template v-else>
          <n-tabs type="segment" animated :value="carrier" class="login-tabs" @update:value="onCarrierTab">
            <n-tab-pane name="mobile" tab="中国移动">
              <template v-if="codeSent">
                <div class="login-target">
                  <div class="login-target-main">
                    <span class="login-target-label">验证码发送至</span>
                    <b class="login-target-value">{{ recordedPhone || phone }}</b>
                  </div>
                  <n-button text type="primary" size="small" :loading="busy" @click="sendCode">重新发送</n-button>
                </div>
                <div class="login-label-row">
                  <label class="login-label" for="login-code">验证码</label>
                  <span class="login-meta">5 分钟内有效</span>
                </div>
                <n-input
                  id="login-code"
                  v-model:value="code"
                  placeholder="6 位短信验证码"
                  maxlength="6"
                  :disabled="busy"
                  size="large"
                  class="login-field login-code"
                  :input-props="{
                    'aria-label': '6 位短信验证码',
                    inputmode: 'numeric',
                    autocomplete: 'one-time-code',
                    maxlength: 6,
                  }"
                  @keyup.enter="code.length === 6 && verifyCode()"
                />
                <n-button
                  type="primary"
                  size="large"
                  block
                  class="login-primary"
                  :loading="busy"
                  :disabled="code.length !== 6"
                  @click="verifyCode"
                >
                  登录
                </n-button>
              </template>

              <template v-else>
                <template v-if="recordedPhone">
                  <div class="login-target">
                    <div class="login-target-main">
                      <span class="login-target-label">验证码发送至</span>
                      <b class="login-target-value">{{ recordedPhone }}</b>
                    </div>
                  </div>
                </template>
                <template v-else>
                  <label class="login-label" for="login-phone">手机号</label>
                  <n-input
                    id="login-phone"
                    v-model:value="phone"
                    placeholder="输入要登录的移动手机号"
                    maxlength="11"
                    :disabled="busy"
                    size="large"
                    class="login-field"
                    :input-props="{
                      'aria-label': '手机号',
                      inputmode: 'tel',
                      autocomplete: 'tel',
                      maxlength: 11,
                    }"
                    @keyup.enter="phone.length === 11 && sendCode()"
                  />
                </template>
                <n-button
                  type="primary"
                  size="large"
                  block
                  class="login-primary"
                  :loading="busy"
                  :disabled="!recordedPhone && phone.length !== 11"
                  @click="sendCode"
                >
                  发送验证码
                </n-button>
              </template>
            </n-tab-pane>

            <n-tab-pane name="unicom" tab="中国联通">
              <div class="login-qr">
                <n-button type="primary" size="large" block class="login-primary" :loading="busy" @click="startQr">
                  {{ qr ? '刷新二维码' : '生成登录二维码' }}
                </n-button>
                <template v-if="qr">
                  <div class="qr-wrap">
                    <img :src="`data:image/png;base64,${qr}`" alt="联通登录二维码" class="qr" />
                  </div>
                  <p class="login-meta login-qr-hint">用中国联通 App 扫码</p>
                </template>
                <p v-if="qrMessage" class="login-meta login-qr-hint">{{ qrMessage }}</p>
              </div>
            </n-tab-pane>
          </n-tabs>
        </template>

        <p class="login-note">{{ loginNote }}</p>
        <div class="login-foot">
          <n-button text quaternary size="small" @click="logout">退出登录 / 换号</n-button>
        </div>
      </div>
    </n-modal>

    <!-- 设备级账单设置：自动登录 / 参与账号 / 后台更新（与 App 同源；账号多时列表内滚动） -->
    <n-modal v-model:show="billSettingsOpen" preset="card" title="账单设置" class="modal-fit" style="--modal-w: 540px">
      <div class="bill-settings">
        <n-alert v-if="billSettingsError" type="warning" :bordered="false" class="settings-note">
          {{ billSettingsError }}
        </n-alert>

        <div v-if="billSettingsBusy && !billSettings" class="settings-loading">
          <n-skeleton text :repeat="3" />
        </div>

        <template v-else-if="billSettings">
          <n-tabs v-model:value="settingsTab" type="line" animated class="settings-tabs">
            <n-tab-pane name="auto" tab="自动登录">
              <div class="settings-section">
                <div class="settings-section-head">
                  <div>
                    <b>自动登录</b>
                    <p class="hint">会话失效时自动收码登录；所有参与账号共用下面的策略。</p>
                  </div>
                  <!-- ui-baseline-allow: 段头右侧的总开关，不是设置项行（下方策略行才用 ToggleRow 形态） -->
                  <n-switch v-model:value="billSettingsForm.keepaliveEnabled" :disabled="billSettingsBusy" />
                </div>

                <n-alert type="info" :bordered="false" class="settings-note">
                  仅限中国移动号码；SIM 必须插在当前设备，且设备读到的号码与账号一致。不满足的账号会显示「不支持」。
                </n-alert>

                <div class="settings-policy">
                  <div class="auto-setting-row">
                    <span>检查间隔</span>
                    <n-input-number
                      v-model:value="billSettingsForm.check"
                      :min="1"
                      :max="60"
                      :disabled="billSettingsBusy || !billSettingsForm.keepaliveEnabled"
                    >
                      <template #suffix>分钟</template>
                    </n-input-number>
                  </div>
                  <div class="auto-setting-row">
                    <span>重登间隔</span>
                    <n-input-number
                      v-model:value="billSettingsForm.relogin"
                      :min="10"
                      :max="720"
                      :disabled="billSettingsBusy || !billSettingsForm.keepaliveEnabled"
                    >
                      <template #suffix>分钟</template>
                    </n-input-number>
                  </div>
                  <div class="auto-setting-row">
                    <span>每日短信上限</span>
                    <n-input-number
                      v-model:value="billSettingsForm.daily"
                      :min="1"
                      :max="20"
                      :disabled="billSettingsBusy || !billSettingsForm.keepaliveEnabled"
                    >
                      <template #suffix>条/日</template>
                    </n-input-number>
                  </div>
                </div>
              </div>
            </n-tab-pane>

            <n-tab-pane name="accounts" tab="参与账号">
              <div class="settings-section">
                <div class="settings-subhead">
                  <span>参与账号</span>
                  <span class="settings-usage">
                    今日 {{ billSettings.keepalive.attempts_today }}/{{ billSettingsForm.daily }} 条
                  </span>
                </div>
                <div v-if="billSettings.accounts.length" class="settings-accounts">
                  <div v-for="a in billSettings.accounts" :key="a.id" class="settings-account">
                    <div class="settings-account-main">
                      <b>{{ settingsAccountTitle(a) }}</b>
                      <span>{{ settingsCarrier(a.carrier) }} · {{ settingsAccountStatus(a) }}</span>
                    </div>
                    <!-- ui-baseline-allow: 账号列表行里的开关，行内容由账号名/运营商决定，套 ToggleRow 会重复标签 -->
                    <n-switch
                      :value="a.participating"
                      :disabled="!a.supported || billSettingsBusy"
                      @update:value="(v: boolean) => setBillParticipating(a, v)"
                    />
                  </div>
                </div>
                <div v-else class="settings-empty">还没有运营商账号。</div>
                <p class="hint">账号较多时列表在框内滚动，不会撑高弹窗。</p>
              </div>
            </n-tab-pane>

            <n-tab-pane name="refresh" tab="后台更新">
              <div class="settings-section">
                <div class="settings-section-head">
                  <div>
                    <b>后台更新</b>
                    <p class="hint">只影响缓存新鲜度，不发短信。</p>
                  </div>
                  <!-- ui-baseline-allow: 段头右侧的总开关，与上面「自动登录」同一形态 -->
                  <n-switch v-model:value="billSettingsForm.refreshEnabled" :disabled="billSettingsBusy" />
                </div>
                <div class="auto-setting-row">
                  <span>刷新间隔</span>
                  <n-input-number
                    v-model:value="billSettingsForm.refreshInterval"
                    :min="5"
                    :max="240"
                    :disabled="billSettingsBusy || !billSettingsForm.refreshEnabled"
                  >
                    <template #suffix>分钟</template>
                  </n-input-number>
                </div>
              </div>
            </n-tab-pane>
          </n-tabs>
        </template>
      </div>
      <template #footer>
        <div class="add-foot auto-foot">
          <span class="settings-foot-hint">参与开关即时保存；其余修改点保存。</span>
          <div class="auto-foot-actions">
            <n-button size="small" @click="billSettingsOpen = false">关闭</n-button>
            <n-button
              size="small"
              type="primary"
              :loading="billSettingsBusy"
              :disabled="!billSettings"
              @click="saveBillSettingsForm"
            >
              保存
            </n-button>
          </div>
        </div>
      </template>
    </n-modal>

    <!-- 账号备注（改名）：原来用 window.prompt，原生输入框与应用风格不一致 -->
    <n-modal v-model:show="renameOpen" preset="card" title="账号备注" class="modal-fit" style="--modal-w: 380px">
      <div class="add-form">
        <div class="field">
          <label>备注</label>
          <n-input
            v-model:value="renameValue"
            maxlength="24"
            placeholder="例如「主卡」；留空则显示号码"
            :input-props="{ 'aria-label': '账号备注' }"
            @keyup.enter="saveRename"
          />
        </div>
        <p class="hint">只改本机显示名，不影响运营商账号本身。</p>
      </div>
      <template #footer>
        <div class="add-foot">
          <n-button size="small" @click="renameOpen = false">取消</n-button>
          <n-button size="small" type="primary" :loading="busy" @click="saveRename">保存</n-button>
        </div>
      </template>
    </n-modal>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, reactive, ref, watch } from 'vue';
import { useDialog, useMessage } from 'naive-ui';
import { useRouter } from 'vue-router';
import { ChevronBackOutline, SearchOutline, SettingsOutline } from '@vicons/ionicons5';
import { getApiClient } from '@/composables/useApi';
import { toApiErrorMessage } from '@/composables/utils';
import {
  activateCarrierAccount,
  addCarrierAccount,
  addIotCardAccount,
  deleteCarrierAccount,
  getCarrierAccounts,
  getCarrierBill,
  getCarrierBillOf,
  getCarrierStatus,
  getBillSettings,
  loginCloudCard as loginCloudCardApi,
  logoutCarrier,
  pollUnicomQr,
  pollUnicomQrFor,
  renameCarrierAccount,
  requestCarrierCode,
  requestCarrierCodeFor,
  saveBillSettings,
  saveBillSettingsAccount,
  startUnicomQr,
  startUnicomQrFor,
  verifyCarrierCode,
  verifyCarrierCodeFor,
  type CarrierAccount,
  type CarrierBill,
  type CarrierBillSettings,
  type CarrierBillSettingsAccount,
  type CarrierPackage,
  type CarrierStatus,
} from '@/api/carrier';

const api = getApiClient();
const message = useMessage();
const dialog = useDialog();
const router = useRouter();
/**
 * 本页布局的收窄阈值。
 *
 * 用 1024 而不是全站移动端的 768：769–1024 时侧栏仍在，再被 236px 账号栏挤一道，
 * 主区只剩 253–508px（实测 834 宽下包名列仍会被压到 0）。这一档与 main.css 的
 * 「1024 → 双列降级为单列」是同一档；账号栏在这一档收成一行摘要。
 */
const NARROW_QUERY = '(max-width: 1024px)';
const isNarrow = ref(window.matchMedia(NARROW_QUERY).matches);
let narrowMq: MediaQueryList | null = null;
function onNarrowChange(e: MediaQueryListEvent): void {
  isNarrow.value = e.matches;
}

/** 窄屏账号卡是否展开；宽屏恒展开（模板里的 v-show 只是窄屏的折叠开关） */
const rosterOpen = ref(false);
function toggleRoster(): void {
  rosterOpen.value = !rosterOpen.value;
}

/**
 * 返回设置页。
 *
 * 用 `history.state.back` 判断「站内有没有上一页」而不是 `history.length > 1`：
 * 从外部链接直接打开本页时 length 也可能 > 1，那样 router.back() 会把用户带出应用。
 * 与 DefaultLayout 的 goBack 同一判据。
 */
function goBack(): void {
  const hasInAppPrev = !!(window.history.state as { back?: string | null } | null)?.back;
  if (hasInAppPrev) router.back();
  else void router.push({ name: 'settings' });
}

const status = ref<CarrierStatus | null>(null);

/* ── 多账号（2026-11-05，P4）──
 *
 * 老 core 没有 `/api/carrier/accounts` → 404，`loadAccounts()` 静默吞掉，
 * `accounts` 保持空 ⇒ 模板里整块不渲染，页面退回单账号行为（计划 E4）。
 */
const accounts = ref<CarrierAccount[]>([]);
const activeAccountId = ref<string | null>(null);
const showAdd = ref(false);
/** 登录表单弹窗（2026-11-08：从内联卡改为弹窗） */
const loginOpen = ref(false);
/** 账号搜索关键词（账号多时过滤用） */
const accountQuery = ref('');
const addForm = reactive<{
  access: 'carrier' | 'iot_card';
  /** 物联卡站点：cloud=云上卡，xj=小鲸卡。 */
  iotProvider: 'cloud' | 'xj';
  carrier: 'mobile' | 'unicom';
  phone: string;
  card: string;
  /** 小鲸卡的绑定手机号后四位；不落盘。 */
  verify: string;
  label: string;
}>({
  access: 'carrier',
  iotProvider: 'cloud',
  carrier: 'mobile',
  phone: '',
  card: '',
  verify: '',
  label: '',
});
const cloudCard = ref('');
const cloudVerify = ref('');

/** 物联卡 provider → 展示名。 */
function iotProviderLabel(provider?: string): string {
  return provider === 'xj' ? '小鲸卡' : provider === 'cloud' ? '云上卡' : '物联卡';
}

/** 改名弹窗（替代 window.prompt） */
const renameOpen = ref(false);
const renameTarget = ref<CarrierAccount | null>(null);
const renameValue = ref('');

/** 头像字：运营商首字 / 云上卡用「卡」 */
function accountAvatar(a: CarrierAccount): string {
  if (a.access_method === 'iot_card') return a.iot_provider === 'xj' ? '鲸' : '云';
  return a.carrier === 'unicom' ? '联' : '移';
}

/** 账号分组：运营商账号 / 物联卡（组头带数量） */
const accountGroups = computed(() => [
  { key: 'carrier', label: '运营商账号', items: filteredAccounts.value.filter((a) => a.access_method !== 'iot_card') },
  { key: 'iot', label: '物联卡', items: filteredAccounts.value.filter((a) => a.access_method === 'iot_card') },
]);

const filteredAccounts = computed(() => {
  const q = accountQuery.value.trim().toLowerCase();
  if (!q) return accounts.value;
  return accounts.value.filter((a) =>
    [accountTitle(a), accountSubtitle(a), a.phone, a.label]
      .filter(Boolean)
      .some((t) => String(t).toLowerCase().includes(q))
  );
});
const visibleAccounts = computed(() => filteredAccounts.value);

/** 「⋯」菜单：改名 / 重新登录 / 删除 */
function onAccountMenu(key: string, a: CarrierAccount): void {
  if (key === 'rename') {
    openRename(a);
    return;
  }
  if (key === 'remove') {
    void removeAccount(a);
    return;
  }
  if (key === 'login') {
    selectAccount(a.id);
    loginOpen.value = true;
  }
}

function accountStateLabel(state: string): string {
  return (
    (
      { ready: '已登录', login_required: '需要登录', not_configured: '未配置', error: '异常' } as Record<string, string>
    )[state] || state
  );
}

function accountTitle(a: CarrierAccount): string {
  if (a.label) return a.label;
  if (a.access_method === 'iot_card') {
    return `${iotProviderLabel(a.iot_provider)} ${a.phone.slice(-4)}`;
  }
  if (a.phone) return a.phone;
  const carrier = a.carrier === 'unicom' ? '联通账号' : '移动账号';
  return `${carrier} ${a.id.slice(-4)}`;
}

function accountSubtitle(a: CarrierAccount): string {
  // 状态不再只靠色点表达：非 ready 时把文字拼进副标题，色弱/触屏（无 hover）也能读出来。
  const stateText = a.state === 'ready' ? '' : accountStateLabel(a.state);
  // 状态放在最前面：窄栏下副标题会被截断，放后面会被省略号吃掉。
  if (a.access_method === 'iot_card') {
    return [stateText, a.phone, `物联卡 · ${iotProviderLabel(a.iot_provider)}`].filter(Boolean).join(' · ');
  }
  const carrier = a.carrier === 'unicom' ? '中国联通' : '中国移动';
  const base = a.label && a.phone ? `${a.phone} · ${carrier}` : carrier;
  return [stateText, base].filter(Boolean).join(' · ');
}

const activeAccount = computed(() => accounts.value.find((a) => a.id === activeAccountId.value) || null);
/** 当前活动物联卡的站点；老单账号数据没有 provider 时按云上卡处理。 */
const activeIotProvider = computed<'cloud' | 'xj'>(() => (activeAccount.value?.iot_provider === 'xj' ? 'xj' : 'cloud'));
const isIotActive = computed(
  () => activeAccount.value?.access_method === 'iot_card' || status.value?.carrier === 'iot_card'
);
const recordedPhone = computed(() => activeAccount.value?.phone || '');
const isIotBill = computed(() => bill.value?.access_method === 'iot_card' || bill.value?.carrier === 'iot_card');
const realUsedGb = computed(() => bill.value?.cloud_card?.real_used_gb ?? null);
const realRemainingGb = computed(() => bill.value?.cloud_card?.real_remaining_gb ?? null);
const realTotalGb = computed(() => bill.value?.cloud_card?.real_total_gb ?? null);
const realRatio = computed(() => {
  const used = bill.value?.flow?.general_used_gb;
  const real = realUsedGb.value;
  return used != null && real != null && real > 0 ? used / real : null;
});
/** hero 环形用量的数据源：移动/联通看「合计」，物联卡只有通用一档。 */
const heroUsedGb = computed(() => (isIotBill.value ? bill.value?.flow?.general_used_gb : totalUsed.value) ?? 0);
const heroTotalGb = computed(() => (isIotBill.value ? (bill.value?.flow?.general_total_gb ?? 0) : totalAll.value));
const heroRemainingGb = computed(() =>
  isIotBill.value
    ? (bill.value?.flow?.general_remaining_gb ?? 0)
    : (bill.value?.flow?.general_remaining_gb ?? 0) +
      (bill.value?.flow?.directed_remaining_gb ?? 0) +
      (bill.value?.flow?.regional_remaining_gb ?? 0)
);
const heroUnlimited = computed(() => !!bill.value?.flow?.general_unlimited && isIotBill.value);
const heroFlowPct = computed(() => usedPercent(heroUsedGb.value, heroTotalGb.value));
const cloudStatusTone = computed<'good' | 'bad' | 'warn'>(() => {
  const text = bill.value?.cloud_card?.status_text || '';
  if (/停机|停用|注销|失效|异常|欠费|过期/.test(text)) return 'bad';
  if (/正常|在用|活跃|已实名|未停机/.test(text)) return 'good';
  return 'warn';
});
const cloudHeadline = computed(() => {
  if (heroUnlimited.value) return `不限量 · 已用 ${gb(heroUsedGb.value)} GB`;
  return `套餐已用 ${heroFlowPct.value}% · 剩余 ${gb(heroRemainingGb.value)} GB`;
});
/** 环形只在物联卡「有总量且不是不限量」时出现；移动/联通继续用下面的分段流量条。 */
const showBillGauge = computed(() => isIotBill.value && !heroUnlimited.value && heroTotalGb.value > 0);
/** 流量段是否还有内容：非物联卡（分段条）/ 无环形时的物联卡 / 有语音短信月累计的物联卡 */
const showBillFlow = computed(
  () =>
    !isIotBill.value ||
    !showBillGauge.value ||
    !!bill.value?.voice ||
    !!bill.value?.sms ||
    bill.value?.monthly_used_gb != null
);

/** 小鲸卡 used == realUsed（无虚量），重复展示“真实用量”没有意义。 */
const hasRealDelta = computed(() => {
  const used = bill.value?.flow?.general_used_gb;
  const real = realUsedGb.value;
  return used != null && real != null && Math.abs(real - used) > 0.01;
});

async function loadAccounts() {
  try {
    const resp = await getCarrierAccounts(api);
    accounts.value = resp.accounts || [];
    activeAccountId.value = resp.active_id || null;
  } catch (e) {
    // 404 = 老 core；其它错误也最多是"没有账号列表"，不该阻塞账单页。
    if ((e as { response?: { status?: number } })?.response?.status !== 404) {
      console.warn('[bill] 读取账号列表失败', e);
    }
    accounts.value = [];
  }
}

async function selectAccount(id: string) {
  if (id === activeAccountId.value) return;
  loading.value = true;
  // 切号时先清掉旧账单：否则左侧已高亮新账号、右侧还挂着旧账号的余额。
  // 运营商取数可能要几秒，这段时间由 showSkeleton 占位。
  bill.value = null;
  warnings.value = [];
  error.value = '';
  billLoginRequired.value = false;
  billStale.value = false;
  billCachedAt.value = null;
  try {
    await activateCarrierAccount(api, id);
    activeAccountId.value = id;
    // 切号也先吃 core 缓存：forced refresh 会让用户每次切号都等运营商上游。
    // 需要最新数据时页面底部还有「刷新」按钮（load(true)）。
    await load(false);
    rosterOpen.value = false;
    message.success('已切换账号');
  } catch (e) {
    message.error(toApiErrorMessage(e, '切换账号失败'));
    // 切号没成功：活动账号没变，重新拉回原账号的账单，别停在「账单读取失败」。
    await load();
  } finally {
    loading.value = false;
  }
}

async function createAccount() {
  if (addForm.access === 'iot_card') {
    const provider = addForm.iotProvider;
    const card = addForm.card.replace(/\D/g, '');
    const verify = addForm.verify.replace(/\D/g, '');
    if (card.length < 4) {
      message.warning('请输入物联卡充值号');
      return;
    }
    if (provider === 'xj' && verify.length !== 4) {
      message.warning('请输入绑定手机号后四位');
      return;
    }
    busy.value = true;
    try {
      const account = await addIotCardAccount(api, { card, label: addForm.label, provider });
      await loginCloudCardApi(api, account.id, card, provider === 'xj' ? verify : undefined);
      showAdd.value = false;
      addForm.card = '';
      addForm.verify = '';
      addForm.label = '';
      cloudCard.value = '';
      cloudVerify.value = '';
      rosterOpen.value = false;
      await loadAccounts();
      await load(true);
      message.success(`${iotProviderLabel(provider)}已添加并登录`);
    } catch (e) {
      message.error(toApiErrorMessage(e, `添加${iotProviderLabel(provider)}失败`));
      // 账号可能已经建好，只是登录失败：收起表单并预填卡号/尾号，让用户直接重试登录。
      cloudCard.value = card;
      cloudVerify.value = provider === 'xj' ? verify : '';
      showAdd.value = false;
      rosterOpen.value = false;
      await loadAccounts();
      await load(true);
    } finally {
      busy.value = false;
    }
    return;
  }
  if (addForm.carrier === 'mobile' && addForm.phone.length !== 11) {
    message.warning('请输入 11 位手机号');
    return;
  }
  busy.value = true;
  try {
    await addCarrierAccount(api, {
      carrier: addForm.carrier,
      phone: addForm.carrier === 'mobile' ? addForm.phone : '',
      label: addForm.label,
    });
    showAdd.value = false;
    addForm.phone = '';
    addForm.label = '';
    rosterOpen.value = false;
    await loadAccounts();
    await load(true);
    message.success('账号已添加');
  } catch (e) {
    message.error(toApiErrorMessage(e, '添加账号失败'));
  } finally {
    busy.value = false;
  }
}

async function loginCloudCardActive() {
  const id = activeAccountId.value;
  if (!id) {
    message.warning('没有活动账号');
    return;
  }
  const provider = activeIotProvider.value;
  const card = cloudCard.value.replace(/\D/g, '');
  const verify = cloudVerify.value.replace(/\D/g, '');
  if (!recordedPhone.value && card.length < 4) {
    message.warning('请输入物联卡充值号');
    return;
  }
  if (provider === 'xj' && verify.length !== 4) {
    message.warning('请输入绑定手机号后四位');
    return;
  }
  busy.value = true;
  try {
    await loginCloudCardApi(api, id, card, provider === 'xj' ? verify : undefined);
    await load(true);
    message.success(`${iotProviderLabel(provider)}登录成功`);
  } catch (e) {
    message.error(toApiErrorMessage(e, `${iotProviderLabel(provider)}登录失败`));
  } finally {
    busy.value = false;
  }
}

function removeAccount(a: CarrierAccount): void {
  // 破坏性操作走站内确认框（原来用 window.confirm，样式与应用脱节且不可定制文案）
  dialog.warning({
    title: '删除账号',
    content: `删除「${accountTitle(a)}」在本机的会话与自动登录配置？运营商账号本身不受影响。`,
    positiveText: '删除',
    negativeText: '取消',
    onPositiveClick: async () => {
      try {
        await deleteCarrierAccount(api, a.id);
        await loadAccounts();
        await load(true);
        message.success('账号已删除');
      } catch (e) {
        message.error(toApiErrorMessage(e, '删除账号失败'));
      }
    },
  });
}

/** 打开改名弹窗（替代 window.prompt） */
function openRename(a: CarrierAccount): void {
  renameTarget.value = a;
  renameValue.value = a.label || '';
  renameOpen.value = true;
}

async function saveRename(): Promise<void> {
  const a = renameTarget.value;
  if (!a) return;
  busy.value = true;
  try {
    await renameCarrierAccount(api, a.id, renameValue.value.trim());
    renameOpen.value = false;
    await loadAccounts();
    message.success('备注已更新');
  } catch (e) {
    message.error(toApiErrorMessage(e, '改名失败'));
  } finally {
    busy.value = false;
  }
}
const bill = ref<CarrierBill | null>(null);
/** core 返回的缓存元数据：会话失效但有最近数据 / 实时刷新失败 / 缓存时刻。 */
const billLoginRequired = ref(false);
const billStale = ref(false);
const billCachedAt = ref<number | null>(null);

function cachedAtText(ts?: number | null): string {
  if (!ts) return '';
  const d = new Date(ts);
  const pad = (n: number) => String(n).padStart(2, '0');
  return ` · 更新于 ${d.getMonth() + 1}/${d.getDate()} ${pad(d.getHours())}:${pad(d.getMinutes())}`;
}
const ORDER_PAGE_SIZE = 5;
const orderPage = ref(0);
const cloudOrders = computed(() => bill.value?.cloud_card?.orders ?? []);
const orderPageCount = computed(() => Math.max(1, Math.ceil(cloudOrders.value.length / ORDER_PAGE_SIZE)));
const safeOrderPage = computed(() => Math.min(orderPage.value, orderPageCount.value - 1));
const pagedOrders = computed(() => {
  const start = safeOrderPage.value * ORDER_PAGE_SIZE;
  return cloudOrders.value.slice(start, start + ORDER_PAGE_SIZE);
});
function prevOrderPage() {
  orderPage.value = Math.max(0, safeOrderPage.value - 1);
}
function nextOrderPage() {
  orderPage.value = Math.min(orderPageCount.value - 1, safeOrderPage.value + 1);
}
watch(activeAccountId, () => {
  orderPage.value = 0;
});
const warnings = ref<string[]>([]);
const loading = ref(false);
const busy = ref(false);

// 账单就绪且不再需要登录时才收起登录弹窗：
// 缓存账单（login_required）也会让 bill 非空，不能在那种情况下把登录框关掉。
watch([bill, billLoginRequired], ([v, needLogin]) => {
  if (v && !needLogin) loginOpen.value = false;
});
const error = ref('');
const hint = ref('');
/**
 * 首次进入 / 切号时的占位态。
 *
 * 判据刻意包含 `status === null`：status 还没回来时既不知道有没有登录，
 * 也不该显示「未登录」或「读取失败」——之前这两个假状态都会先闪一下。
 */
const showSkeleton = computed(() => !bill.value && !error.value && (loading.value || status.value === null));

const carrier = ref<'mobile' | 'unicom'>('mobile');
const phone = ref('');
const code = ref('');
const codeSent = ref(false);

/** 登录弹窗顶部一句话：只说明当前登录方式，长解释移到弹窗底部注脚。 */
const loginLead = computed(() => {
  if (isIotActive.value) {
    return activeIotProvider.value === 'xj' ? '输入小鲸卡充值号与绑定手机号后四位' : '输入云上卡充值号';
  }
  return carrier.value === 'unicom' ? '使用中国联通 App 扫码登录' : '使用短信验证码登录';
});
/** 登录弹窗底部注脚：会话位置、费用与有效期，按登录方式各说各的。 */
const loginNote = computed(() => {
  if (isIotActive.value) {
    return '登录状态保存在设备 core，手机端与 Web 共用。';
  }
  if (carrier.value === 'unicom') {
    return '会话约 2 小时，过期需重扫；登录状态保存在设备 core，手机端与 Web 共用。';
  }
  return '发码会产生真实短信费用。登录状态保存在设备 core，手机端与 Web 共用。';
});

function onCarrierTab(value: string | number): void {
  if (value === 'mobile' || value === 'unicom') switchCarrier(value);
}

const qr = ref<string | null>(null);
const qrMessage = ref('');
let qrTimer: number | null = null;

/** 设备级账单设置（自动登录 / 后台更新）；与 App 同源。 */
const billSettingsOpen = ref(false);
const billSettings = ref<CarrierBillSettings | null>(null);
const billSettingsBusy = ref(false);
const billSettingsError = ref('');
const settingsTab = ref<'auto' | 'accounts' | 'refresh'>('auto');
const billSettingsForm = reactive({
  keepaliveEnabled: false,
  check: 5,
  relogin: 25,
  daily: 8,
  refreshEnabled: true,
  refreshInterval: 20,
});

const totalUsed = computed(() => {
  if (!bill.value) return 0;
  const f = bill.value.flow;
  return f.general_used_gb + f.directed_used_gb + (f.regional_used_gb || 0);
});
const totalAll = computed(() => {
  if (!bill.value) return 0;
  const f = bill.value.flow;
  return f.general_total_gb + f.directed_total_gb + (f.regional_total_gb || 0);
});
/** 即将到期包的键集合：表格里给对应行的「到期」标警戒色 */
const soonKeys = computed(() => new Set(soon.value.map((p) => p.name + p.valid_to)));

const soon = computed(() => {
  if (!bill.value) return [];
  const now = Date.now();
  return bill.value.packages.filter((p) => {
    const end = p.valid_to ? Date.parse(p.valid_to) : NaN;
    return Number.isFinite(end) && end > now && end - now <= 24 * 3600 * 1000;
  });
});
const packageRemaining = computed(() => (bill.value?.packages || []).reduce((sum, p) => sum + p.remaining_gb, 0));

/* 金额/流量/时间统一走 Intl：千分位、两位小数、小时制都不再由各调用点手拼。
   实测问题：订单时间原来直接把 ISO 字符串（2026-11-09T04:36:59）印出来。 */
const decimal2 = new Intl.NumberFormat('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
const timeHm = new Intl.DateTimeFormat('zh-CN', { hour: '2-digit', minute: '2-digit', hour12: false });
const dateTimeFmt = new Intl.DateTimeFormat('zh-CN', {
  year: 'numeric',
  month: '2-digit',
  day: '2-digit',
  hour: '2-digit',
  minute: '2-digit',
  hour12: false,
});

function money(v?: number | null): string {
  return v == null ? '—' : decimal2.format(v);
}
function gb(v?: number | null): string {
  return v == null ? '—' : decimal2.format(v);
}
/** 绝对时间（订单创建/有效期）：可读格式，避免把 ISO 的 "T" 直接印在界面上 */
function fmtDateTime(v?: string | number | null): string {
  if (v == null || v === '') return '—';
  // 各运营商返回的时间格式并不统一：ISO 字符串、10 位秒、13 位毫秒都出现过。
  // 旧实现只走 Date.parse，数字会被原样上屏（截图里的 1762423380000 就是这么来的）。
  let t: number;
  if (typeof v === 'number') {
    t = v < 1e12 ? v * 1000 : v;
  } else {
    const raw = String(v).trim();
    const num = Number(raw);
    t = /^\d+$/.test(raw) ? (num < 1e12 ? num * 1000 : num) : Date.parse(raw);
  }
  return Number.isFinite(t) ? dateTimeFmt.format(t) : String(v);
}
function daysLeftText(v?: number | null): string {
  if (v == null) return '—';
  return v < 0 ? '已过期' : `${v} 天`;
}
function usedPercent(used?: number | null, total?: number | null): number {
  if (used == null || total == null || total <= 0) return 0;
  return Math.min(100, Math.round((used / total) * 100));
}
function pct(used?: number | null, total?: number | null): string {
  return `${usedPercent(used, total)}%`;
}
function tenure(months: number): string {
  const y = Math.floor(months / 12);
  const m = months % 12;
  return y > 0 ? `${y}年${m}个月` : `${m}个月`;
}
function star(v: number): string {
  return Number.isInteger(v) ? String(v) : v.toFixed(1);
}
function kindLabel(kind: CarrierPackage['kind']): string {
  return kind === 'general' ? '通用' : kind === 'directed' ? '定向' : '区域';
}
function expiry(value?: string | null): string {
  if (!value) return '未标到期';
  const end = Date.parse(value);
  if (!Number.isFinite(end)) return '未标到期';
  if (end <= Date.now()) return '已过期';
  const d = new Date(end);
  const hh = timeHm.format(d);
  // 按“日历日”判断今天/明天，不能按 24 小时：明天 00:00 距离现在可能不足 24h
  const today = new Date();
  const tomorrow = new Date(today.getFullYear(), today.getMonth(), today.getDate() + 1);
  if (d.toDateString() === today.toDateString()) return `今天 ${hh}`;
  if (d.toDateString() === tomorrow.toDateString()) return `明天 ${hh}`;
  return `${d.getMonth() + 1}-${String(d.getDate()).padStart(2, '0')}`;
}

function settingsCarrier(carrier?: string): string {
  if (carrier === 'mobile') return '中国移动';
  if (carrier === 'unicom') return '中国联通';
  if (carrier === 'iot_card') return '物联卡';
  return '账号';
}

function settingsAccountTitle(a: CarrierBillSettingsAccount): string {
  return a.label || a.phone || a.id.slice(-4);
}

function settingsAccountStatus(a: CarrierBillSettingsAccount): string {
  if (a.supported) return a.participating ? '已参与' : '未参与';
  const text = a.message || '';
  if (a.carrier !== 'mobile') return '不支持（非移动）';
  if (text.includes('读不到')) return '不支持（读不到 SIM 号码）';
  if (text.includes('不一致')) return '不支持（SIM 与账号不一致）';
  if (text.includes('未就绪')) return '不支持（SIM 未就绪）';
  if (text.includes('手机号')) return '不支持（缺完整手机号）';
  if (text.includes('只支持中国移动')) return '不支持（SIM 不是移动）';
  return '不支持';
}

function syncBillSettingsForm(value: CarrierBillSettings): void {
  billSettingsForm.keepaliveEnabled = value.keepalive.enabled;
  billSettingsForm.check = value.keepalive.check_interval_minutes;
  billSettingsForm.relogin = value.keepalive.relogin_interval_minutes;
  billSettingsForm.daily = value.keepalive.daily_sms_limit;
  billSettingsForm.refreshEnabled = value.refresh.enabled;
  billSettingsForm.refreshInterval = value.refresh.interval_minutes;
}

async function loadBillSettings(): Promise<void> {
  billSettingsBusy.value = true;
  billSettingsError.value = '';
  try {
    const value = await getBillSettings(api);
    billSettings.value = value;
    syncBillSettingsForm(value);
  } catch (e) {
    const status = (e as { response?: { status?: number } })?.response?.status;
    billSettingsError.value =
      status === 404 || status === 501
        ? '当前 core 不支持设备级账单设置，请先升级 core。'
        : toApiErrorMessage(e, '读取账单设置失败');
  } finally {
    billSettingsBusy.value = false;
  }
}

async function openBillSettings(): Promise<void> {
  billSettingsOpen.value = true;
  await loadBillSettings();
}

async function saveBillSettingsForm(): Promise<void> {
  billSettingsBusy.value = true;
  billSettingsError.value = '';
  try {
    const value = await saveBillSettings(api, {
      keepalive: {
        enabled: billSettingsForm.keepaliveEnabled,
        check_interval_minutes: billSettingsForm.check,
        relogin_interval_minutes: billSettingsForm.relogin,
        daily_sms_limit: billSettingsForm.daily,
      },
      refresh: {
        enabled: billSettingsForm.refreshEnabled,
        interval_minutes: billSettingsForm.refreshInterval,
      },
    });
    billSettings.value = value;
    syncBillSettingsForm(value);
    message.success('账单设置已保存');
  } catch (e) {
    billSettingsError.value = toApiErrorMessage(e, '保存账单设置失败');
    message.error(billSettingsError.value);
  } finally {
    billSettingsBusy.value = false;
  }
}

async function setBillParticipating(account: CarrierBillSettingsAccount, participating: boolean): Promise<void> {
  billSettingsBusy.value = true;
  try {
    const value = await saveBillSettingsAccount(api, account.id, participating);
    billSettings.value = value;
    message.success(participating ? '已加入自动登录' : '已退出自动登录');
  } catch (e) {
    message.error(toApiErrorMessage(e, '保存参与状态失败'));
    await loadBillSettings();
  } finally {
    billSettingsBusy.value = false;
  }
}

watch(loginOpen, (open) => {
  if (!open) return;
  // 打开登录弹窗时跟随当前账号的运营商，避免上次停在联通、移动账号也先显示扫码。
  const current = activeAccount.value?.carrier;
  if (!isIotActive.value && (current === 'mobile' || current === 'unicom') && carrier.value !== current) {
    switchCarrier(current);
  }
});

async function load(force = false) {
  loading.value = true;
  error.value = '';
  try {
    // 账号列表与当前账号状态一起刷：老 core 上 loadAccounts 内部吞 404。
    await loadAccounts();
    const s = await getCarrierStatus(api);
    status.value = s;
    // 2026-11-09：会话失效时 core 也可能返回缓存账单（login_required=true）。
    // 所以不能只在 state=ready 时才请求 bill，否则缓存永远看不到。
    const hasAccount = !!activeAccountId.value || s.state === 'ready' || s.state === 'login_required';
    if (hasAccount) {
      try {
        // 有 activeAccountId 时走账号维度（明确的 core 磁盘缓存入口）；
        // 老 core 没有账号列表 / 单账号模式才回退旧 /bill。
        const id = activeAccountId.value;
        const resp = id ? await getCarrierBillOf(api, id, force) : await getCarrierBill(api, force);
        bill.value = resp.bill || null;
        warnings.value = resp.warnings || [];
        billLoginRequired.value = !!resp.login_required;
        billStale.value = !!resp.stale;
        billCachedAt.value = resp.cached_at ?? null;
        if (!bill.value && s.message) error.value = s.message;
      } catch (e) {
        // 409 = 没有缓存且未登录：回到登录卡。
        if ((e as { response?: { status?: number } })?.response?.status === 409) {
          bill.value = null;
          billLoginRequired.value = false;
          billStale.value = false;
          billCachedAt.value = null;
          status.value = { state: 'login_required', message: toApiErrorMessage(e, '需要登录') };
          error.value = toApiErrorMessage(e, '需要登录');
        } else {
          throw e;
        }
      }
    } else {
      bill.value = null;
      billLoginRequired.value = false;
      billStale.value = false;
      billCachedAt.value = null;
      if (s.message) error.value = s.message;
    }
  } catch (e) {
    const msg = toApiErrorMessage(e, '读取话费账单失败');
    // core 对过期会话回 409：必须回到登录卡，而不是停在「账单读取失败」。
    if ((e as { response?: { status?: number } })?.response?.status === 409) {
      bill.value = null;
      status.value = { state: 'login_required', message: msg };
      error.value = msg;
    } else if (bill.value) {
      // 已有账单时刷新失败：保留旧数据（比整页变成错误卡好），用 toast 说明这次没刷上。
      message.error(msg);
    } else {
      error.value = msg;
    }
  } finally {
    loading.value = false;
  }
}

/** 页面从后台回来时重新校验一次，会话过期就自动回登录卡。 */
async function onVisible() {
  if (document.visibilityState === 'visible') await load();
}

function switchCarrier(next: 'mobile' | 'unicom') {
  carrier.value = next;
  codeSent.value = false;
  code.value = '';
  qr.value = null;
  qrMessage.value = '';
  stopQrPolling();
}

async function sendCode() {
  busy.value = true;
  try {
    const s = activeAccountId.value
      ? await requestCarrierCodeFor(api, activeAccountId.value, phone.value)
      : await requestCarrierCode(api, phone.value);
    status.value = s;
    codeSent.value = true;
    hint.value = '验证码已发送，5 分钟内有效';
    message.success(hint.value);
  } catch (e) {
    message.error(toApiErrorMessage(e, '发送验证码失败'));
  } finally {
    busy.value = false;
  }
}

async function verifyCode() {
  busy.value = true;
  try {
    if (activeAccountId.value) {
      await verifyCarrierCodeFor(api, activeAccountId.value, code.value);
    } else {
      await verifyCarrierCode(api, code.value);
    }
    message.success('登录成功');
    await load(true);
  } catch (e) {
    message.error(toApiErrorMessage(e, '登录失败'));
  } finally {
    busy.value = false;
  }
}

function stopQrPolling() {
  if (qrTimer != null) {
    window.clearInterval(qrTimer);
    qrTimer = null;
  }
}

async function startQr() {
  busy.value = true;
  stopQrPolling();
  try {
    const s = activeAccountId.value ? await startUnicomQrFor(api, activeAccountId.value) : await startUnicomQr(api);
    qr.value = s.qr_png_base64 || null;
    qrMessage.value = '等待扫码…';
    if (qr.value) {
      qrTimer = window.setInterval(async () => {
        try {
          const p = activeAccountId.value ? await pollUnicomQrFor(api, activeAccountId.value) : await pollUnicomQr(api);
          status.value = p;
          qrMessage.value =
            p.state === 'scanned'
              ? '已扫码，请在手机上确认'
              : p.state === 'ready'
                ? '登录成功'
                : p.state === 'expired' || p.state === 'error'
                  ? p.message || '二维码已失效'
                  : '等待扫码…';
          if (p.state === 'ready') {
            stopQrPolling();
            await load(true);
          } else if (p.state === 'expired' || p.state === 'error') {
            stopQrPolling();
          }
        } catch (e) {
          stopQrPolling();
          message.error(toApiErrorMessage(e, '扫码状态读取失败'));
        }
      }, 2000);
    }
  } catch (e) {
    message.error(toApiErrorMessage(e, '生成二维码失败'));
  } finally {
    busy.value = false;
  }
}

function logout(): void {
  dialog.warning({
    title: '退出登录',
    content: isIotActive.value
      ? '退出后需要重新输入充值号才能读取账单。'
      : '退出后需要重新短信验证码 / 扫码登录，发码会产生真实短信费用。',
    positiveText: '退出登录',
    negativeText: '取消',
    onPositiveClick: async () => {
      stopQrPolling();
      try {
        await logoutCarrier(api);
      } catch (e) {
        message.error(toApiErrorMessage(e, '退出登录失败'));
        return;
      }
      bill.value = null;
      qr.value = null;
      codeSent.value = false;
      code.value = '';
      await load();
    },
  });
}

onMounted(() => {
  narrowMq = window.matchMedia(NARROW_QUERY);
  isNarrow.value = narrowMq.matches;
  narrowMq.addEventListener('change', onNarrowChange);
  load();
  document.addEventListener('visibilitychange', onVisible);
});
onUnmounted(() => {
  stopQrPolling();
  narrowMq?.removeEventListener('change', onNarrowChange);
  document.removeEventListener('visibilitychange', onVisible);
});
</script>

<style scoped>
/* ───────────────────────── 页面骨架 ───────────────────────── */
.bill-page {
  /* 本页专用的可读性令牌（2026-11-09）。
     全站令牌 --text-muted(#999) 在白底只有 2.85:1、--warning(#f0a020) 只有 2.15:1，
     10.5–12px 的小字（账号副标题、表头、到期警告、本月累计）达不到 WCAG AA 4.5:1。
     这里只修本页，不动全站令牌 —— 那是另一轮全局调整。 */
  /* ui-baseline-allow: 本页这两个令牌就是要覆盖全站 --text-muted/--warning 的对比度不足（2.85:1 / 2.15:1）；待全站令牌统一后删除 */
  --bill-muted: #6b6b6b;
  --bill-warn: #b45309;
  /* 2026-11-10：去掉 1180 上限，账单页占满内容区（与其它页面一致） */
  max-width: none;
  margin: 0;
  display: grid;
  gap: 12px;
}
.dark .bill-page {
  /* ui-baseline-allow: 暗色档的对应取值，理由同上一条 */
  --bill-muted: #a6a6a6;
  --bill-warn: #f0a020;
}

/* 键盘焦点：naive 按钮默认 outline:none 且无阴影，键盘用户看不出焦点在哪 */
.bill-page :deep(.n-button:focus-visible) {
  outline: 2px solid var(--accent-color);
  outline-offset: 2px;
}

.bill-head {
  display: flex;
  align-items: center;
  gap: 10px;
}
.bill-title {
  font-size: var(--font-lg);
  font-weight: 700;
  color: var(--text-primary);
}
.bill-head-spacer {
  flex: 1;
}

/* 设备级账单设置：与 App 的「账单设置 › 自动登录」同源 */
.bill-settings {
  display: flex;
  flex-direction: column;
  gap: 18px;
}
.settings-section {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.settings-section + .settings-section {
  border-top: 1px solid var(--border-subtle);
  padding-top: 16px;
}
.settings-section-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
}
.settings-section-head b {
  font-size: 13px;
  color: var(--text-primary);
}
.settings-section-head .hint {
  margin: 4px 0 0;
  font-size: 11px;
  line-height: 1.5;
}
.settings-note {
  margin: 0;
}
.settings-subhead {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: 12px;
  font-weight: 600;
  color: var(--text-primary);
}
.settings-usage {
  font-size: 11px;
  font-weight: 400;
  color: var(--bill-muted);
}
.settings-tabs {
  margin-top: 2px;
}
.settings-tabs :deep(.n-tab-pane) {
  padding-top: 8px;
}
.settings-accounts {
  display: flex;
  flex-direction: column;
  max-height: 300px;
  overflow: auto;
  border: 1px solid var(--border-subtle);
  border-radius: 8px;
}
.settings-account {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 9px 10px;
}
.settings-account + .settings-account {
  border-top: 1px solid var(--border-subtle);
}
.settings-account-main {
  display: flex;
  flex-direction: column;
  gap: 2px;
  flex: 1;
  min-width: 0;
}
.settings-account-main b {
  font-size: 12.5px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.settings-account-main span {
  font-size: 10.5px;
  color: var(--bill-muted);
  line-height: 1.4;
}
.settings-empty {
  padding: 14px;
  text-align: center;
  font-size: 11px;
  color: var(--bill-muted);
  border: 1px dashed var(--border-subtle);
  border-radius: 8px;
}
.settings-policy {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.settings-foot-hint {
  font-size: 10.5px;
  color: var(--bill-muted);
}
.settings-loading {
  padding: 8px 0;
}

.bill-layout {
  display: grid;
  grid-template-columns: 272px minmax(0, 1fr);
  gap: 12px;
  align-items: start;
}
/* 没有账号（老 core 404 / 首次使用）时不能留 272px 空列，否则整页被推到右边 */
.bill-layout.no-rail {
  grid-template-columns: minmax(0, 1fr);
}
.bill-rail {
  min-width: 0;
}
.cache-alert {
  margin-bottom: 12px;
}

.bill-main {
  display: flex;
  flex-direction: column;
  gap: 12px;
  min-width: 0;
}

/* ───────────────────────── 账号栏 ───────────────────────── */
.acct-search {
  margin-bottom: 8px;
}
.acct-grp {
  font-size: 10.5px;
  color: var(--bill-muted);
  margin: 8px 2px 4px;
}

.account-list {
  display: flex;
  flex-direction: column;
  /* 关键：column + flex-wrap:wrap 会把超出 max-height 的账号排进右侧的第二/第三列，
     再由 overflow 裁掉。之前 12 个账号在 1440×700 下变成 3 列，后两列完全不可见。 */
  flex-wrap: nowrap;
  gap: 4px;
  max-height: calc(100dvh - 340px);
  overflow-y: auto;
  overflow-x: hidden;
  overscroll-behavior: contain;
}
.acct-empty {
  padding: 14px 4px;
  text-align: center;
  font-size: 11.5px;
  color: var(--bill-muted);
}

.account-item {
  display: flex;
  align-items: center;
  gap: 9px;
  padding: 7px 9px;
  border: 1px solid transparent;
  border-radius: 10px;
  cursor: pointer;
  min-width: 0;
  transition:
    background-color 0.15s ease,
    border-color 0.15s ease;
}
.account-item:hover {
  background: var(--surface-hover);
}
.account-item.active {
  background: var(--accent-color-light);
  border-color: color-mix(in srgb, var(--accent-color) 28%, transparent);
}
.account-item:focus-visible {
  outline: 2px solid var(--accent-color);
  outline-offset: 2px;
}

.av {
  width: 28px;
  height: 28px;
  flex: none;
  border-radius: 8px;
  display: grid;
  place-items: center;
  font-size: 11.5px;
  font-weight: 600;
  background: var(--surface-elevated);
  color: var(--text-secondary);
}
.account-item.active .av {
  background: var(--card-bg);
  color: var(--accent-color);
}

.account-main {
  flex: 1;
  min-width: 0;
}
.account-title {
  font-size: 12.5px;
  font-weight: 600;
  color: var(--text-primary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.acct-sub {
  font-size: 10.5px;
  color: var(--bill-muted);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.st {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  flex: none;
  background: var(--text-muted);
}
.st.on {
  background: var(--accent-color);
}
.st.warn {
  background: var(--warning);
}

.acct-more {
  flex: none;
  opacity: 0.65;
  width: 28px;
  height: 28px;
}
.acct-more:hover {
  opacity: 1;
}

.account-add {
  margin-top: 8px;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  height: 36px;
  border: 1px dashed var(--border-subtle);
  border-radius: 9px;
  color: var(--text-secondary);
  font-size: 12.5px;
  cursor: pointer;
  user-select: none;
}
.account-add:hover {
  border-color: var(--accent-color);
  color: var(--accent-color);
}
.account-add:focus-visible {
  outline: 2px solid var(--accent-color);
  outline-offset: 2px;
  border-color: var(--accent-color);
}

/* 窄屏账号摘要（宽屏隐藏，见 @media 1024） */
.roster {
  display: none;
  align-items: center;
  gap: 9px;
  padding: 8px 10px;
  background: var(--card-bg);
  border: 1px solid var(--border-subtle);
  border-radius: 12px;
  cursor: pointer;
  min-width: 0;
}
.roster:focus-visible {
  outline: 2px solid var(--accent-color);
  outline-offset: 2px;
}
.roster-main {
  flex: 1;
  min-width: 0;
}
.roster-title {
  font-size: 12.5px;
  font-weight: 600;
  color: var(--text-primary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.roster-sub {
  font-size: 10.5px;
  color: var(--bill-muted);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.roster-toggle {
  flex: none;
  font-size: 12.5px;
  font-weight: 600;
  /* 用次级文字色（5.7:1）而不是主色：品牌绿 #18a058 在白底只有 3.4:1，12px 文字不达标 */
  color: var(--text-secondary);
}

/* ───────────────────────── 弹窗表单 ───────────────────────── */
.add-form {
  display: flex;
  flex-direction: column;
  align-items: stretch;
  gap: 12px;
}
.add-form .field {
  margin-bottom: 0;
}
.add-form .field > label {
  display: block;
  font-size: 11.5px;
  color: var(--bill-muted);
  margin-bottom: 5px;
}
.add-form :deep(.n-radio-group) {
  display: flex;
  width: 100%;
}
.add-form :deep(.n-radio-button) {
  flex: 1;
  text-align: center;
}
.add-form .hint {
  margin: 0;
  font-size: 11px;
  line-height: 1.5;
}
.add-foot {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}
.field {
  margin-bottom: 10px;
}
.carrier-tabs {
  display: flex;
  gap: 8px;
  margin-bottom: 12px;
}

/* ── 登录弹窗（2026-11-12 重排） ──
   顶部只留一句登录方式，长解释（会话位置/费用/有效期）收进底部注脚；
   登录动作统一为全宽主按钮，重发退成文字按钮，避免和「登录」抢主次。 */
.login-body {
  display: flex;
  flex-direction: column;
}
.login-lead {
  margin: 0 0 14px;
  font-size: 12.5px;
  line-height: 1.5;
  color: var(--bill-muted);
}
.login-tabs {
  margin-bottom: 14px;
}
.login-tabs :deep(.n-tabs-nav) {
  width: 100%;
}
.login-tabs :deep(.n-tabs-rail) {
  width: 100%;
}
.login-tabs :deep(.n-tabs-tab) {
  flex: 1;
  justify-content: center;
}
.login-target {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 12px;
  padding: 10px 12px;
  border: 1px solid var(--border-subtle);
  border-radius: 9px;
  background: var(--card-bg);
}
.login-target-main {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}
.login-target-label {
  font-size: 10.5px;
  color: var(--bill-muted);
}
.login-target-value {
  font-size: 13.5px;
  font-weight: 600;
  font-variant-numeric: tabular-nums;
  color: var(--text-primary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.login-label {
  display: block;
  margin: 0 0 6px;
  font-size: 11px;
  color: var(--bill-muted);
}
.login-label-row {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 10px;
  margin: 12px 0 6px;
}
.login-label-row .login-label {
  margin: 0;
}
.login-meta {
  font-size: 10.5px;
  line-height: 1.5;
  color: var(--bill-muted);
}
.login-field {
  margin-bottom: 12px;
}
.login-code :deep(.n-input__input-el),
.login-code :deep(.n-input__placeholder) {
  text-align: center;
}
.login-code :deep(.n-input__input-el) {
  font-size: 18px;
  letter-spacing: 0.32em;
  font-variant-numeric: tabular-nums;
}
.login-code :deep(.n-input__placeholder) {
  font-size: 13px;
  letter-spacing: normal;
}
.login-primary {
  width: 100%;
  margin-top: 4px;
}
.login-note {
  margin: 14px 0 0;
  padding-top: 12px;
  border-top: 1px solid var(--border-subtle);
  font-size: 11px;
  line-height: 1.55;
  color: var(--bill-muted);
}
.login-foot {
  display: flex;
  justify-content: flex-end;
  margin-top: 8px;
}
.login-qr-hint {
  margin: 8px 0 0;
  text-align: center;
}
.auto-setting-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
}
.auto-setting-row > span {
  font-size: 12px;
  color: var(--bill-muted);
}
.auto-setting-row :deep(.n-input-number) {
  width: 156px;
  flex: none;
}
.auto-foot {
  justify-content: space-between;
}
.auto-foot-actions {
  display: flex;
  gap: 8px;
  margin-left: auto;
}

/* ───────────────────────── 通用文字 ───────────────────────── */
.hint {
  margin: 0 0 12px;
  font-size: 12px;
  color: var(--bill-muted);
  line-height: 1.6;
}
.sub {
  font-size: 12px;
  color: var(--bill-muted);
  line-height: 1.5;
}
.sub.strong {
  color: var(--text-primary);
  font-weight: 600;
}
.error {
  margin: 8px 0 0;
  font-size: 12px;
  color: var(--error);
}
.row {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 10px;
  flex-wrap: wrap;
}
.row.end {
  justify-content: flex-end;
  margin: 4px 0 0;
}
.logout-inline {
  margin-left: 8px;
}

/* 首次读取占位 */
.loading-row {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 6px 0;
}

/* ───────────────────────── 余额 hero（A+C 融合） ───────────────────────── */
/* 三栏：余额 / 套餐事实 / 环形用量。中间栏填掉了旧版左右各空的块；
   窄屏折行见 1024 / 480 两条媒体查询。 */
.iot-status-bar {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px 14px;
  padding-bottom: 10px;
  margin-bottom: 12px;
  border-bottom: 1px solid var(--border-subtle);
}
.iot-status-main {
  display: flex;
  align-items: center;
  gap: 10px;
}
.iot-status-headline {
  flex: 0 1 auto;
  min-width: 0;
  font-size: clamp(12.5px, 0.95vw, 15px);
  color: var(--text-secondary);
  line-height: 1.5;
}
.iot-status-days {
  font-size: 11.5px;
  color: var(--bill-muted);
  white-space: nowrap;
}
.status-pill {
  display: inline-flex;
  align-items: center;
  padding: 2px 8px;
  border-radius: 999px;
  font-size: 11px;
  font-weight: 600;
  line-height: 1.5;
}
.status-pill.bad {
  color: var(--error);
  background: color-mix(in srgb, var(--error) 12%, transparent);
}
.status-pill.good {
  color: var(--success);
  background: color-mix(in srgb, var(--success) 12%, transparent);
}
.status-pill.warn {
  color: var(--warning);
  background: color-mix(in srgb, var(--warning) 14%, transparent);
}
.iot-status-actions {
  margin-left: auto;
  display: flex;
  align-items: center;
  gap: 8px;
}
.bill-hero {
  display: grid;
  grid-template-columns: minmax(0, 1.1fr) minmax(190px, 0.75fr);
  gap: 12px 28px;
  align-items: center;
}
.bill-hero.has-gauge {
  grid-template-columns: minmax(0, 1.1fr) minmax(190px, 0.75fr) auto;
}
.bill-hero.iot-hero {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: clamp(18px, 1.8vw, 34px) clamp(26px, 3vw, 56px);
}
.bill-hero.iot-hero .hero-gauge {
  order: -1;
}
.bill-hero.iot-hero .gauge {
  width: clamp(128px, 14vw, 230px);
  height: clamp(128px, 14vw, 230px);
}
.hero-card {
  /* n-card 的 body 内边距由 naive 提供，这里只管 hero 自身的节奏 */
  contain: layout;
}
.bill-actions-card :deep(.n-card__content) {
  padding-top: 8px;
  padding-bottom: 8px;
}
.hero-who {
  display: flex;
  flex-wrap: wrap;
  gap: 6px 8px;
  align-items: baseline;
  font-size: 12px;
  color: var(--bill-muted);
}
.hero-who b {
  color: var(--text-primary);
  font-weight: 600;
}
.hero-who i {
  font-style: normal;
  color: var(--border-subtle);
}
.hero-plan {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding-left: 24px;
  border-left: 1px solid var(--border-subtle);
  min-width: 0;
}
/* 环形用量：conic-gradient 画环，::before 盖出内圆。
   --p 是 0–100 的整数（heroFlowPct），track 用 --border-subtle 保证暗色下同灰阶。 */
.hero-gauge {
  display: flex;
  align-items: center;
  gap: clamp(24px, 1.8vw, 36px);
  min-width: 0;
}
.gauge {
  --p: 0;
  width: 116px;
  height: 116px;
  flex: none;
  border-radius: 50%;
  background: conic-gradient(var(--accent-color) calc(var(--p) * 1%), var(--border-subtle) 0);
  display: grid;
  place-items: center;
  position: relative;
}
.gauge.gauge-alert {
  background: conic-gradient(var(--error) calc(var(--p) * 1%), var(--border-subtle) 0);
}
.gauge::before {
  content: '';
  position: absolute;
  inset: 10px;
  border-radius: 50%;
  background: var(--card-bg);
}
.gauge-val {
  position: relative;
  z-index: 1;
  text-align: center;
}
.gauge-val b {
  display: block;
  font-size: clamp(23px, 1.9vw, 34px);
  font-weight: 700;
  line-height: 1;
  letter-spacing: -0.02em;
  font-variant-numeric: tabular-nums;
}
.gauge-val span {
  display: block;
  margin-top: 3px;
  font-size: 10.5px;
  color: var(--bill-muted);
}
.gauge-side {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 150px;
}
.gauge-side b {
  font-size: clamp(20px, 2vw, 34px);
  font-weight: 700;
  letter-spacing: -0.01em;
  font-variant-numeric: tabular-nums;
  color: var(--text-primary);
}
.gauge-side span {
  font-size: 11px;
  color: var(--bill-muted);
}
.gauge-side small {
  font-size: 10.5px;
  color: var(--bill-muted);
}
.gauge-solo {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding-left: 24px;
  border-left: 1px solid var(--border-subtle);
}
.gauge-solo b {
  font-size: 20px;
  font-weight: 700;
  color: var(--text-primary);
}
.gauge-solo span {
  font-size: 11px;
  color: var(--bill-muted);
}
/* 账单统计：hero 下方一条细行，不再挤进 hero 右侧 */
.hero-stats {
  display: flex;
  flex-wrap: wrap;
  gap: 8px 22px;
  margin-top: 14px;
  padding-top: 12px;
  border-top: 1px solid var(--border-subtle);
}
.label {
  font-size: 12px;
  color: var(--bill-muted);
}
.balance {
  font-size: clamp(30px, 2.6vw, 46px);
  font-weight: 700;
  line-height: 1.1;
  letter-spacing: -0.02em;
  margin-top: 2px;
  font-variant-numeric: tabular-nums;
}
.balance span {
  font-size: 14px;
  font-weight: 500;
  margin-right: 2px;
}

.hero-facts {
  display: flex;
  align-items: flex-start;
  gap: 24px;
  margin-left: auto;
  flex-wrap: wrap;
}
.fact {
  /* 允许在窄容器里收缩，否则 ellipsis 不生效、整块会把 hero 撑出卡片 */
  min-width: 0;
}
.fact span {
  display: block;
  font-size: 10.5px;
  color: var(--bill-muted);
}
.fact b {
  display: block;
  max-width: min(260px, 100%);
  font-size: clamp(13.5px, 1.1vw, 17px);
  font-variant-numeric: tabular-nums;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.fact b.ok {
  color: var(--accent-color);
}

.stat {
  display: grid;
  gap: 2px;
}
.stat span {
  font-size: 11px;
  color: var(--bill-muted);
}
.stat b {
  font-size: 16px;
  font-variant-numeric: tabular-nums;
}

.meta {
  display: flex;
  flex-wrap: wrap;
  gap: 6px 14px;
  margin-top: 10px;
  font-size: 12px;
  color: var(--bill-muted);
}
.meta.warn {
  color: var(--bill-warn);
}

/* ───────────────────────── 流量 ───────────────────────── */
.flow {
  display: grid;
  gap: 12px;
}
.frow {
  display: grid;
  grid-template-columns: 44px minmax(0, 1fr) auto;
  gap: 8px 12px;
  align-items: center;
}
.frow > span:first-child {
  font-size: 13px;
  font-weight: 600;
}
.bar {
  height: 9px;
  border-radius: 999px;
  background: var(--border-subtle);
  box-shadow: inset 0 1px 2px var(--shadow-color-soft);
  overflow: hidden;
}
.bar i {
  display: block;
  height: 100%;
  background: var(--accent-color);
  border-radius: 999px;
  min-width: 3px;
  box-shadow: 0 0 8px var(--accent-glow);
  transition: width 240ms ease;
}
.bar.regional i {
  background: var(--warning);
  box-shadow: 0 0 8px var(--warning-light);
}
.frow.total .bar i {
  background: var(--text-primary);
  box-shadow: none;
}
.frow b {
  font-size: 13px;
  white-space: nowrap;
  font-variant-numeric: tabular-nums;
}
/* 不限量：没有进度条时让文字跨过条列，否则会停在中段、与上下行错位 */
.frow b.solo {
  grid-column: 2 / -1;
  justify-self: start;
}
.frow small {
  grid-column: 2 / -1;
  font-size: 11px;
  color: var(--bill-muted);
}
/* 真实用量：标签块在左（含虚量倍率）、三格在右，替代旧的「标题+网格+尾注」三段 */
/* 融合卡内的分区：流量 / 真实用量 / 订单，共用同一条顶部分隔线节奏 */
.bill-flow-section,
.bill-real-section,
.bill-orders-section {
  margin-top: 14px;
  padding-top: 12px;
  border-top: 1px solid var(--border-subtle);
}
.bill-section-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 8px;
}
.bill-section-head b {
  font-size: 12.5px;
  font-weight: 600;
  color: var(--text-primary);
}
.bill-section-head .sub {
  margin: 0;
  font-size: 11px;
  color: var(--bill-muted);
}
.bill-real-section .real-strip {
  margin-top: 0;
  padding-top: 0;
  border-top: 0;
}
.real-strip {
  display: grid;
  grid-template-columns: 116px minmax(0, 1fr);
  gap: 8px 24px;
  align-items: center;
  margin-top: 10px;
  padding-top: 12px;
  border-top: 1px solid var(--border-subtle);
}
.strip-label {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}
.strip-label b {
  font-size: 12.5px;
  font-weight: 600;
  color: var(--text-primary);
}
.strip-label span {
  font-size: 10.5px;
  color: var(--bill-muted);
  white-space: nowrap;
}
.real-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 8px 24px;
  margin: 0;
}
.real-grid b {
  display: block;
  color: var(--text-primary);
  font-size: 16px;
  font-variant-numeric: tabular-nums;
}
.real-grid span {
  display: block;
  color: var(--text-secondary);
  font-size: 12px;
}

/* ───────────────────────── 流量包 / 订单 ───────────────────────── */
.pkg {
  display: flex;
  align-items: baseline;
  gap: 8px;
  padding: 8px 0;
  border-bottom: 1px solid var(--border-subtle);
  font-size: 12px;
  flex-wrap: wrap;
}
.pkg:last-child {
  border-bottom: 0;
}
.pkg .name {
  flex: 1;
  min-width: 160px;
}
.pkg .right {
  white-space: nowrap;
}
.pkg .right.warn {
  color: var(--bill-warn);
}
.pkg .exp {
  color: var(--bill-muted);
  font-size: 11px;
  margin-left: auto;
}

.pkg-table {
  display: flex;
  flex-direction: column;
}
.pkg-head,
.pkg-row {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 84px 84px 76px;
  gap: 10px;
  align-items: center;
}
.pkg-head {
  font-size: 10.5px;
  color: var(--bill-muted);
  padding: 4px 0;
  border-bottom: 1px solid var(--border-subtle);
}
.pkg-head span:not(:first-child),
.pkg-row .num,
.pkg-row .exp {
  text-align: right;
}
.pkg-row {
  padding: 7px 0;
  border-bottom: 1px solid var(--border-subtle);
  font-size: 12px;
}
.pkg-row:last-child {
  border-bottom: 0;
}
.pkg-name {
  display: flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
}
.pkg-name b {
  font-weight: 600;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.pkg-name .kind {
  flex: none;
  font-style: normal;
  font-size: 10px;
  color: var(--bill-muted);
  background: var(--surface-elevated);
  border: 1px solid var(--border-subtle);
  border-radius: 5px;
  padding: 0 5px;
}
/* 宽屏让两个数字直接参与四列网格；≤1024 时作为一组换到第二行 */
.pkg-nums {
  display: contents;
}
.pkg-row .num {
  font-variant-numeric: tabular-nums;
  color: var(--text-secondary);
}
.pkg-row .exp {
  font-variant-numeric: tabular-nums;
  color: var(--bill-muted);
}
.pkg-row .exp.warn {
  color: var(--bill-warn);
}

/* A+C：订单一行式横条 —— 圆点 + 名称/状态 + 日期 + 右侧金额 */
.order-line {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px 0;
  border-bottom: 1px solid var(--border-subtle);
}
.order-line:last-of-type {
  border-bottom: 0;
}
.order-line .dot {
  width: 7px;
  height: 7px;
  flex: none;
  border-radius: 50%;
  background: var(--accent-color);
}
.order-line-main {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.order-line-main .l1 {
  display: flex;
  align-items: baseline;
  gap: 8px;
  min-width: 0;
}
.order-line-main .name {
  font-size: 12.5px;
  font-weight: 600;
  color: var(--text-primary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.order-line-main .m {
  font-size: 11px;
  color: var(--bill-muted);
  white-space: nowrap;
}
.order-line-main .l2 {
  font-size: 10.5px;
  color: var(--bill-muted);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.order-line .price {
  margin-left: auto;
  font-size: 13px;
  font-weight: 600;
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
  color: var(--text-primary);
}
.order-pager {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-top: 8px;
}

.warn-box {
  margin-top: 4px;
}

/* ───────────────────────── 自动登录 ───────────────────────── */
.auto-card {
  margin-top: 4px;
}
.auto-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px;
  margin: 12px 0;
}

/* 二维码：底色必须纯白，接 --card-bg 会让暗色下扫不出来 */
.qr-wrap {
  display: flex;
  justify-content: center;
  margin: 12px 0 8px;
}
.qr {
  width: 220px;
  height: 220px;
  object-fit: contain;
  /* ui-baseline-allow: 二维码底色必须纯白，接 --card-bg 会让暗色下扫不出来（与 QrModal 同一条） */
  background: #fff;
  border-radius: 8px;
  padding: 6px;
}

/* ───────────────────────── 响应式 ───────────────────────── */
@media (max-width: 1180px) {
  .bill-layout {
    grid-template-columns: 236px minmax(0, 1fr);
  }
}

@media (max-width: 1024px) {
  /* 单列；账号栏收成一行摘要，账单拿到整个内容区宽度 */
  .bill-layout,
  .bill-layout.no-rail {
    grid-template-columns: minmax(0, 1fr);
  }
  .roster {
    display: flex;
  }
  .bill-rail :deep(.n-card) {
    margin-top: 10px;
  }
  .account-list {
    /* 组头 26px + 5 行整（每行 53px + 4px 间距）：避免在行中间切断，看起来像被裁掉 */
    max-height: 312px;
  }
  /* 窄屏两栏：余额 + 环形；套餐事实整行掉到下方（横向排布，不再被 260px 截断） */
  .bill-hero,
  .bill-hero.has-gauge {
    grid-template-columns: minmax(0, 1fr) auto;
  }
  .hero-plan {
    grid-column: 1 / -1;
    flex-direction: row;
    flex-wrap: wrap;
    align-items: baseline;
    gap: 4px 18px;
    padding-left: 0;
    padding-top: 12px;
    border-left: 0;
    border-top: 1px solid var(--border-subtle);
  }
  .hero-gauge {
    grid-column: 2;
    grid-row: 1;
    padding-top: 0;
  }
}

/* 流量包表格改两行式：
   4 列固定占 274px，在 320–390 的整宽下包名列会归零；在 769–1024 的
   「侧栏 + 账号栏」夹击下主区只剩 253–508px，同样会把包名挤没（实测 834 宽为 0）。
   所以阈值取 1024，而不是只在小手机生效。 */
@media (max-width: 1024px) {
  .pkg-head {
    display: none;
  }
  .pkg-row {
    grid-template-columns: minmax(0, 1fr) auto;
    grid-template-areas:
      'name exp'
      'nums nums';
    gap: 4px 8px;
    padding: 9px 0;
  }
  .pkg-name {
    grid-area: name;
  }
  .pkg-row .exp {
    grid-area: exp;
    text-align: right;
  }
  .pkg-nums {
    display: flex;
    grid-area: nums;
    gap: 16px;
    font-size: 11px;
  }
  .pkg-row .num {
    text-align: left;
  }
  .pkg-row .num::before {
    content: attr(data-label) ' ';
    color: var(--bill-muted);
  }
}

@media (max-width: 480px) {
  .auto-grid {
    grid-template-columns: 1fr;
  }
  /* 手机：hero 收成一列 —— 余额、套餐、环形用量依次向下；
     环形与读数保持横向（环 96px + 数字列），再窄也不会挤成一团 */
  .bill-hero,
  .bill-hero.has-gauge {
    grid-template-columns: minmax(0, 1fr);
    gap: 14px;
  }
  .hero-plan {
    padding-left: 0;
    border-left: 0;
    padding-top: 12px;
    border-top: 1px solid var(--border-subtle);
    flex-direction: row;
    flex-wrap: wrap;
    align-items: baseline;
    gap: 4px 18px;
  }
  .hero-gauge {
    grid-column: auto;
    grid-row: auto;
    width: 100%;
    padding-top: 12px;
    border-top: 1px solid var(--border-subtle);
  }
  .gauge {
    width: 96px;
    height: 96px;
  }
  .gauge::before {
    inset: 8px;
  }
  .gauge-val b {
    font-size: 19px;
  }
  .gauge-side {
    min-width: 0;
  }
  .gauge-solo {
    padding-left: 0;
    border-left: 0;
  }
  /* 手机上套餐名完整显示（没有 hover，省略号等于永久丢失信息） */
  .fact b {
    white-space: normal;
    overflow: visible;
    text-overflow: clip;
    line-height: 1.4;
  }
  .real-strip {
    grid-template-columns: minmax(0, 1fr);
  }
  .strip-label {
    flex-direction: row;
    align-items: baseline;
    gap: 10px;
  }
  .real-grid {
    grid-template-columns: repeat(3, minmax(0, 1fr));
    gap: 8px;
  }
  /* 订单行：金额不再右贴，换到名称下方，避免把日期挤没 */
  .order-line {
    align-items: flex-start;
  }
  .order-line .price {
    margin-left: 0;
  }
  .order-line-main .l1 {
    flex-wrap: wrap;
  }
}

/* 触屏：⋯ 放大到可点范围（桌面仍保持紧凑） */
@media (pointer: coarse) {
  .acct-more {
    width: 34px;
    height: 34px;
  }
}

@media (prefers-reduced-motion: reduce) {
  .bar i {
    transition: none;
  }
}
</style>
