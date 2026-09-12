<!--
  Webhook 渠道配置。

  为什么是弹窗而不是通知分栏里的第三块常驻内容：这一页是「配一次就不再动」的设置，
  与 GuardSettingsModal / SmsFilterModal 同一条理由 —— 设置类收进弹窗，
  分栏里只留一张能一眼看完的摘要卡（状态 + 目标地址 + 场景数）。

  硬边界：**web 不做任何投递判定**。能不能投（`configured`）、要不要重试、免打扰怎么算
  全在设备端；这里只读写配置并把测试结果原样呈现。

  为什么预设表与占位符清单不在 web 侧写死：它们由 `api/notify/webhook/config` 的 GET 回，
  手抄一份的代价是「照着界面填完却发不出去」——设备端加了预设或改了默认模板，web 这份不会跟。

  2026-09-10：新增「最低级别 + 每日上限」两个旋钮（core 侧规则同构），用的是三条渠道共用的
  `../ChannelRulesFields.vue`。这条渠道的区间是 0..1000，**0 = 不限**（core 的默认值）——
  与本机短信最少 1 条恰好相反，那个差异由共用组件按取值域说出来。

  2026-09-11：选了预设之后**只显示一个输入框**（core 用 `secret_label` / `secret_marker` /
  `secret_target` 把「用户要填的那一样东西」声明成了机器可读的元数据），请求地址 / 方法 /
  超时 / Content-Type / 请求头收进折叠的「高级设置」。理由是选了 PushPlus 的人面对
  五个请求字段并不知道该动哪一个，而他真正要填的只有一个 token。折叠**不是删功能** ——
  改过高级字段的用户仍然能在里面改回去，而且结构被改过时会自动展开（见 [secret] 的 manual 档）。
  同一轮把 Content-Type 从只读展示改成可编辑字段（与 app 侧对齐）：此前它只能被预设覆盖，
  `CUSTOM` 用户想在 JSON 与纯文本之间切换只能靠换预设。

  2026-09-11（第二轮，与 app 侧同口径）：

  - **请求体模板挪出高级设置**，成为常驻字段。它原来躺在默认收起的高级设置里，观感是
    "只有自定义预设才能改模板"，而模板恰恰是这条渠道最常要动的东西（加字段、改文案）。
  - **切换预设不再覆盖任何东西**：core 已改成每个预设各存一份 url / method / headers /
    body_template / content_type / timeout（顶层只共享 enabled / scenes / respect_dnd /
    min_level / daily_limit 与配额计数器）。于是这里的切换只 PUT `{"preset":"X"}` 再重新 GET ——
    不带本地表单那份字段（会被 core 写进**新**预设的槽位，把"从未配过"坐实成"配过旧预设的值"），
    也不由 web 把预设默认值填进表单（默认值由 core 在读不到时提供且不落盘）。
    那个「套用预设会覆盖当前填写」的确认弹窗随之删除：覆盖行为已经不存在，留着只是制造
    一个不存在的风险。
  - 占位符区是**一排 chip 按钮**（点一下插到光标处）；tooltip 在桌面够用但触屏摸不到，
    所以名字 + 说明的对照表另给一个默认收起的说明区。`{{highlight}}` 的风险提示
    **留在收起区外面** —— 它可能是短信验证码原文，藏进折叠区等于没说。
-->
<template>
  <n-modal
    :show="show"
    preset="card"
    title="Webhook 通知"
    style="width: 640px; max-width: calc(100vw - 32px)"
    @update:show="emit('update:show', $event)"
  >
    <n-spin :show="loading">
      <ToggleRow
        v-model="form.enabled"
        label="启用 Webhook 通知"
        description="设备会把通知按下面的模板发送到指定地址。"
      />

      <div class="field">
        <span class="field-label">服务预设</span>
        <n-select
          :value="form.preset"
          size="small"
          :loading="switchingPreset"
          :disabled="switchingPreset"
          :options="presetOptions"
          @update:value="onPresetChange"
        />
        <span v-if="presetHint" class="row-hint">{{ presetHint }}</span>
        <span class="row-hint">{{ PRESET_ISOLATION_NOTE }}</span>
      </div>

      <!--
        预设声明了「用户要填的那一样东西」时，界面上只留这一个输入框。
        它写回的是 url 或 body_template 里 marker 那一段（哪一处由 secret_target 决定），
        换算规则见脚本里的 [secretSlot]；结构被手工改过时这一档会变成 manual，改由模板 / 高级设置承担。

        两种形态之间给一段淡入位移：这一格会因为"模板被改动"当场消失或出现，硬切会让人以为界面坏了。
      -->
      <Transition name="field-swap" mode="out-in">
        <div v-if="secret.mode === 'simple'" key="simple" class="field">
          <span class="field-label">{{ secret.label }}</span>
          <n-input
            :value="secret.value"
            size="small"
            :placeholder="'填写' + secret.label"
            @update:value="onSecretInput"
          />
          <span class="row-hint">{{ secretStatusText }}</span>
        </div>
        <div v-else-if="secret.mode === 'manual'" key="manual" class="field">
          <span class="row-hint">{{ SECRET_DETACHED_NOTE }}</span>
        </div>
      </Transition>

      <!--
        请求体模板：**常驻字段，所有预设都能改**。
        它原来在高级设置里，而高级设置对有简易输入框的预设默认收起 —— 观感是"只有自定义才能改模板"。
        副文案分两档（见 [bodyTemplateNote]）：密钥就写在模板里的那一档要**提前**说清
        "改坏结构上面那个输入框会消失"，否则输入框凭空不见时用户只会当成 bug。
      -->
      <div ref="bodyFieldRef" class="field">
        <span class="field-label">请求体模板</span>
        <n-input
          v-model:value="form.body_template"
          type="textarea"
          :rows="5"
          size="small"
          placeholder="留空表示不带请求体"
        />
        <span class="row-hint">{{ bodyTemplateNote }}</span>

        <!-- 一排 chip：点一下把占位符写到光标处。清单与说明都来自设备端，web 不维护第二张表 -->
        <div class="tag-row">
          <span class="row-hint">可用占位符（点击插入到光标处）：</span>
          <n-tooltip v-for="p in placeholders" :key="p.name" trigger="hover">
            <template #trigger>
              <n-button size="tiny" secondary @click="insertPlaceholder(p.name)">
                {{ placeholderToken(p.name) }}
              </n-button>
            </template>
            {{ p.desc }}
          </n-tooltip>
          <span v-if="placeholders.length === 0" class="row-hint">尚未从设备读到。</span>
        </div>

        <!-- 风险提示**不跟着折叠**：它讲的是"验证码会被发给第三方"，藏进收起区等于没说 -->
        <span v-if="hasHighlightPlaceholder" class="risk-hint">{{ HIGHLIGHT_RISK_NOTE }}</span>

        <!--
          说明区默认收起：十项各占一行会把弹窗撑成一条长列表，而九成的动作只是"把某个名字塞进模板"。
          但 tooltip 在触屏上摸不到，所以名字 + 说明的对照表必须另有一个可达的入口，
          展开后每行仍然可以点着插入。折叠动效用 n-collapse 自带的那份。
        -->
        <n-collapse v-if="placeholders.length > 0" class="placeholder-desc">
          <n-collapse-item :name="PLACEHOLDER_PANEL" title="查看占位符说明">
            <div v-for="p in placeholders" :key="`desc-${p.name}`" class="placeholder-desc-row">
              <n-button size="tiny" secondary @click="insertPlaceholder(p.name)">
                {{ placeholderToken(p.name) }}
              </n-button>
              <span class="row-hint">{{ p.desc }}</span>
            </div>
          </n-collapse-item>
        </n-collapse>

        <span class="row-hint">发送时占位符会替换成这条通知的实际内容；请求地址不做替换。</span>
      </div>

      <!--
        高级设置：请求地址 / 方法 / 超时 / Content-Type / 请求头。
        默认折叠是为了让「选了预设」的人只面对一个输入框；自定义预设与结构被改过的配置默认展开
        （否则那两种情况下连请求地址都看不到）。折叠状态由 [syncAdvancedDefault] 只在
        「读配置 / 换预设」两个时刻设，之后用户自己开合。
      -->
      <n-collapse v-model:expanded-names="expandedPanels" class="advanced">
        <n-collapse-item :name="ADVANCED_PANEL" title="高级设置">
          <div class="form-grid">
            <div class="field">
              <span class="field-label">请求地址</span>
              <n-input v-model:value="form.url" size="small" placeholder="https://" />
            </div>
            <div class="field">
              <span class="field-label">请求方法</span>
              <n-select v-model:value="form.method" size="small" :options="METHOD_OPTIONS" />
            </div>
          </div>
          <span v-if="urlHint" class="row-hint">{{ urlHint }}</span>

          <div class="field">
            <span class="field-label">超时</span>
            <n-input-number
              v-model:value="form.timeout_sec"
              :min="TIMEOUT_MIN_SEC"
              :max="TIMEOUT_MAX_SEC"
              size="small"
              style="width: 150px"
            >
              <template #suffix>秒</template>
            </n-input-number>
            <span class="row-hint">
              单次请求的等待上限，可填 {{ TIMEOUT_MIN_SEC }} ~ {{ TIMEOUT_MAX_SEC }}
              秒。超时按失败处理，最多重试 2 次。
            </span>
          </div>

          <div class="field">
            <span class="field-label">Content-Type</span>
            <n-input v-model:value="form.content_type" size="small" :placeholder="JSON_CONTENT_TYPE" />
            <span class="row-hint">
              常用两种：{{ JSON_CONTENT_TYPE }}（JSON 模板）与 {{ TEXT_CONTENT_TYPE }}（纯文本，ntfy
              用的就是它）。每个预设各存一份，切换预设时显示的是该预设自己那份。
            </span>
            <span class="row-hint">
              这一项决定占位符怎么转义：设备端按它含不含 json 字样判 JSON 档，JSON 档下占位符的值会先做 JSON
              字符串转义。填错的表现不是报错而是内容坏掉 —— JSON
              模板填成纯文本，正文里一个引号就能撑破请求体；纯文本模板填成 JSON，用户收到的正文里会出现 \n
              这样的字面量。
            </span>
          </div>

          <!--
            请求头两态：列表态脱敏、编辑态显示全文。
            脱敏只影响这一处显示 —— 值必须能被改，所以不能用「星号回显、原值保留」那套
            （headers 是任意键值对，回显成星号再提交就把真值覆盖掉了）。
          -->
          <div class="field">
            <div class="field-head">
              <span class="field-label">请求头</span>
              <n-button size="tiny" quaternary @click="headersEditing = !headersEditing">
                {{ headersEditing ? '完成编辑' : '编辑' }}
              </n-button>
            </div>
            <!-- 两态之间同样给一段过渡：这一格的高度差很大，硬切会把下面的字段整段顶开 -->
            <Transition name="field-swap" mode="out-in">
              <div v-if="headersEditing" key="edit" class="field-stack">
                <n-dynamic-input
                  v-model:value="headerPairs"
                  preset="pair"
                  key-placeholder="名称"
                  value-placeholder="值"
                />
                <span class="row-hint">编辑状态显示完整值。保存时提交全部请求头，删掉的行会一并生效。</span>
              </div>
              <div v-else key="list" class="field-stack">
                <div v-if="headerPairs.length === 0" class="row-hint">未设置请求头。</div>
                <div v-for="(h, i) in headerPairs" :key="`header-${i}`" class="header-row">
                  <span class="header-name">{{ h.key || '（未命名）' }}</span>
                  <span class="header-value">{{ maskHeaderValue(h.key, h.value) }}</span>
                </div>
                <span class="row-hint"
                  >凭据类请求头（名称含 authorization、token、key、secret、cookie、auth）只显示首尾几位。</span
                >
              </div>
            </Transition>
          </div>
        </n-collapse-item>
      </n-collapse>

      <!--
        最低级别 + 每日上限 + 今日用量：三条渠道共用的同一个组件。
        这条渠道的取值域是 0..1000，**0 = 不限**（core 的默认值就是不限 ——
        这次改造不能让存量用户升级后突然收不到通知）。
      -->
      <ChannelRulesFields
        v-model:min-level="form.min_level"
        v-model:daily-limit="form.daily_limit"
        :levels="config?.levels ?? []"
        :config-loaded="config !== null"
        :daily-limit-min="dailyLimitMin"
        :daily-limit-max="dailyLimitMax"
        :sent-today="config?.sent_today ?? 0"
        :quota-remaining="config?.quota_remaining ?? null"
      />

      <div class="field">
        <span class="field-label">触发场景</span>
        <n-checkbox-group v-model:value="form.scenes">
          <n-space :size="[14, 6]">
            <n-checkbox v-for="s in PUSH_CHANNEL_SCENES" :key="s.id" :value="s.id" :label="s.label" />
          </n-space>
        </n-checkbox-group>
        <span class="row-hint">只有勾选的场景会发送。一个都不勾时，只有手动测试会发出去。</span>
        <span class="row-hint">{{ SCENE_NOTE_BATTERY }}</span>
      </div>

      <ToggleRow
        v-model="form.respect_dnd"
        label="遵守免打扰时段"
        description="开启后，免打扰时段内的通知不发送到该地址。"
      />
      <!-- 免打扰与场景勾选旁边必须说这一句：否则「严重事件照样发」就是一条藏起来的规则 -->
      <span class="row-hint">{{ CRITICAL_OVERRIDE_NOTE }}</span>

      <!-- 状态码与响应体是排查 Webhook 的唯一线索，成功也要显示：
           有些服务用 HTTP 200 回业务错误码，只说「成功」会让用户停止排查。 -->
      <div v-if="testResult" class="sub-panel test-result">
        <div class="test-head">
          <n-tag size="tiny" :bordered="false" :type="testResult.success ? 'success' : 'error'">
            {{ testResult.success ? '已送达' : '未送达' }}
          </n-tag>
          <span class="row-hint">{{ testStatusText }}</span>
        </div>
        <div v-if="testResult.error" class="row-hint">{{ testResult.error }}</div>
        <div v-if="!testResult.auto_notify_enabled" class="row-hint">
          通知总开关当前关闭，自动通知不会发送；手动测试不受它约束。
        </div>
        <span class="field-label">响应内容</span>
        <n-scrollbar style="max-height: 120px">
          <pre class="resp-body">{{ testResult.response_body || '（无响应内容）' }}</pre>
        </n-scrollbar>
      </div>
    </n-spin>

    <template #footer>
      <div class="footer-bar">
        <span class="row-hint">测试使用设备端已保存的配置，未保存的修改不会生效。</span>
        <n-space :size="8">
          <n-button size="small" @click="emit('update:show', false)">关闭</n-button>
          <n-button size="small" :loading="testing" :disabled="saving" @click="runTest">发送测试</n-button>
          <n-button size="small" type="primary" :loading="saving" :disabled="testing" @click="save">保存配置</n-button>
        </n-space>
      </div>
    </template>
  </n-modal>
</template>

<script setup lang="ts">
import { computed, nextTick, reactive, ref, watch } from 'vue';
import { useMessage } from 'naive-ui';
import { getApiClient } from '@/composables/useApi';
import { DAILY_LIMIT_UNLIMITED, Endpoints, type ChannelRules } from '@/api/contract';
import ToggleRow from '@/components/ToggleRow.vue';
import ChannelRulesFields from '@/views/settings/components/ChannelRulesFields.vue';
import {
  CRITICAL_OVERRIDE_NOTE,
  PUSH_CHANNEL_SCENES,
  SCENE_NOTE_BATTERY,
  formatNotifyTime,
} from '@/views/settings/notifyShared';

const props = defineProps<{ show: boolean }>();
const emit = defineEmits<{ 'update:show': [boolean] }>();

const message = useMessage();
const api = getApiClient();

/** 「用户要填的那一样东西」藏在哪一处（core 的 `secret_target`，小写口径）。 */
type SecretTarget = 'none' | 'url' | 'body';

/** 一个内置预设的默认值表（设备端回的原样，web 不加工）。 */
interface WebhookPresetItem {
  name: string;
  display_name: string;
  user_fills: string;
  /** 那个唯一输入框的名字（如「PushPlus Token」）；`secret_target = none` 时是空串。 */
  secret_label: string;
  /** 默认值里代表它的标记，形如 `<token>`。替换时**整段连尖括号一起换掉**。 */
  secret_marker: string;
  secret_target: SecretTarget;
  url: string;
  method: string;
  headers: Record<string, string>;
  content_type: string;
  body_template: string;
}

/** 一个占位符：名字 + 说明。说明的唯一真源在 core，web 不维护第二张表。 */
interface WebhookPlaceholder {
  name: string;
  desc: string;
}

/**
 * GET api/notify/webhook/config 的响应形状。
 *
 * 同构的那一组规则字段（最低级别 / 每日上限 / 用量 / 取值域）直接继承 [ChannelRules]，
 * 与本机短信、邮件三处共用一份声明。
 */
interface WebhookConfigDto extends ChannelRules {
  enabled: boolean;
  preset: string;
  url: string;
  method: string;
  headers: Record<string, string>;
  body_template: string;
  content_type: string;
  timeout_ms: number;
  scenes: string[];
  respect_dnd: boolean;
  configured: boolean;
  placeholders: WebhookPlaceholder[];
  presets: WebhookPresetItem[];
}

interface WebhookTestResult {
  success: boolean;
  auto_notify_enabled: boolean;
  error?: string;
  /** null = 请求没走完（超时 / 连不上 / DNS / TLS），此时看 error */
  status_code?: number | null;
  response_body?: string | null;
  attempted_at?: number;
}

interface HeaderPair {
  key: string;
  value: string;
}

const METHOD_OPTIONS = [
  { label: 'POST', value: 'POST' },
  { label: 'GET', value: 'GET' },
  { label: 'PUT', value: 'PUT' },
];

/**
 * 超时的可填区间。设备端的校验是 `timeout_ms` 1000..60000，但 GET **不回这个值域**
 * （与同一响应里那组 `levels` / `daily_limit_min` / `daily_limit_max` 不同 —— 那三个是回的），
 * 所以这里按参考手册写死秒数区间。真正的强制点仍在设备端，这两个常量只影响输入框的钳制。
 */
const TIMEOUT_MIN_SEC = 1;
const TIMEOUT_MAX_SEC = 60;
const DEFAULT_TIMEOUT_SEC = 10;

/**
 * 兜底的每日上限区间与默认级别，**必须与 core 的 `WebhookConfig` 默认值逐字一致**
 * （`min_level = info`、`daily_limit = 0` = 不限、区间 0..1000）：GET 还没回来时界面用它们，
 * 写错就会出现「界面显示只发严重、设备上其实全发」这种反向假开关。
 */
const FALLBACK_LIMIT_MIN = DAILY_LIMIT_UNLIMITED;
const FALLBACK_LIMIT_MAX = 1000;
const DEFAULT_MIN_LEVEL = 'info';

/** 请求头名里出现这些词就按凭据处理（口径同手机端那一页）。 */
const SENSITIVE_HEADER_HINTS = ['authorization', 'token', 'key', 'secret', 'cookie', 'auth'];
const MASK_KEEP_HEAD = 4;
const MASK_KEEP_TAIL = 4;

/** 折叠面板的 name。只有一个面板，抽成常量是为了模板与脚本不各写一次字面量。 */
const ADVANCED_PANEL = 'advanced';

/** 占位符说明区的 name（另一个 n-collapse，与高级设置各自开合）。 */
const PLACEHOLDER_PANEL = 'placeholders';

/**
 * 预设下拉旁边那句「切换不会丢」。
 *
 * 不是安慰话：core 侧每个预设各存一份请求地址 / 模板 / 请求头等，A 预设里填的 token
 * 切到 B 再切回来仍在。不写出来的话，用户会因为"怕丢"而不敢试第二个预设
 * （改造前那个「会覆盖当前填写」的确认弹窗正是这种恐惧的来源，而现在它连事实都不是了）。
 */
const PRESET_ISOLATION_NOTE =
  '每个预设的请求地址、请求体模板与请求头各自保存，切换只改「当前用哪个」，不会覆盖其它预设那份。切换会立即生效并以设备端保存的配置重新加载，未保存的修改不会带过去。';

/** 当前 url / 模板与预设默认结构不同时的说明（此时不给简易输入框，见 [secret] 的 manual 档）。 */
const SECRET_DETACHED_NOTE =
  '当前配置与该预设的默认结构不同（请求地址或请求体模板被改过），无法只用一个输入框表示。请在「请求体模板」或「高级设置 → 请求地址」中直接编辑。';

/**
 * Content-Type 的两个常见取值（core 的 `JSON_CONTENT_TYPE` / `TEXT_CONTENT_TYPE`）。
 *
 * **只当输入建议用，不是白名单** —— 所以这一项做成可输入的字段而不是二选一的下拉：
 * 自建服务完全可能要 `application/x-www-form-urlencoded` 之类的 MIME，下拉会把那些人堵在门外。
 * 真正生效的值是用户填的那一份（core 只校验非空），这两个常量不参与任何判定，
 * 因此不构成"web 侧的第二张表"。
 */
const JSON_CONTENT_TYPE = 'application/json; charset=utf-8';
const TEXT_CONTENT_TYPE = 'text/plain; charset=utf-8';

/**
 * `highlight` 的风险提示。**这是 web 侧的提示，不是 core 那份 `desc`** ——
 * core 只负责说"这个占位符是什么"，"插进模板等于把验证码交给第三方"是本界面该讲的后果。
 */
const HIGHLIGHT_PLACEHOLDER = 'highlight';
const HIGHLIGHT_RISK_NOTE =
  '{{highlight}} 可能是短信验证码原文。插入模板后，验证码会随通知一起发送给第三方服务；只在你信任该服务时使用。';

const loading = ref(false);
const saving = ref(false);
const testing = ref(false);
const headersEditing = ref(false);

/** 切换预设期间锁住下拉：那一步要走一次 PUT + 一次 GET，中途再点会拿到过期回显。 */
const switchingPreset = ref(false);

/** 高级设置的开合。默认值只在「读配置 / 换预设」时由 [syncAdvancedDefault] 设一次。 */
const expandedPanels = ref<string[]>([ADVANCED_PANEL]);

/** 请求体模板那一格的容器：占位符按钮要拿里面 textarea 的光标位置。 */
const bodyFieldRef = ref<HTMLElement | null>(null);

/** 设备端回读的原始配置：用于「用户改过没有」的判断与只读回显位。 */
const config = ref<WebhookConfigDto | null>(null);
const testResult = ref<WebhookTestResult | null>(null);

const form = reactive({
  enabled: false,
  preset: 'CUSTOM',
  url: '',
  method: 'POST',
  body_template: '',
  content_type: '',
  /** 界面按秒展示，提交时换算回毫秒 */
  timeout_sec: DEFAULT_TIMEOUT_SEC as number | null,
  /** 规则同构的两个旋钮（2026-09-10）：本地初值与 core 默认值一致 */
  min_level: DEFAULT_MIN_LEVEL,
  daily_limit: DAILY_LIMIT_UNLIMITED as number | null,
  scenes: [] as string[],
  respect_dnd: true,
});

/** 请求头在界面上是有序键值对；提交时再拼回 map（设备端那边是整体替换语义）。 */
const headerPairs = ref<HeaderPair[]>([]);

const presetOptions = computed(() =>
  (config.value?.presets ?? []).map((p) => ({ label: p.display_name, value: p.name }))
);

const dailyLimitMin = computed(() => config.value?.daily_limit_min ?? FALLBACK_LIMIT_MIN);
const dailyLimitMax = computed(() => config.value?.daily_limit_max ?? FALLBACK_LIMIT_MAX);

const presetHint = computed(() => {
  const fills = presetByName(form.preset)?.user_fills;
  return fills ? `需要自己填写：${fills}` : '';
});

/** 占位符清单（含说明）由设备端回，顺序即展示顺序。 */
const placeholders = computed<WebhookPlaceholder[]>(() => config.value?.placeholders ?? []);
const hasHighlightPlaceholder = computed(() => placeholders.value.some((p) => p.name === HIGHLIGHT_PLACEHOLDER));

/**
 * 简易输入框的三态。
 *
 * - `none`：这个预设没有"只填一样"的东西（自定义），高级设置里全手填；
 * - `simple`：当前值仍是预设默认结构，中间那段就是用户填的密钥，一个输入框就够；
 * - `manual`：用户手工改过前后缀（换了自建服务器地址、重写了模板），没法再用一个输入框表示。
 */
type SecretState =
  | { mode: 'none' }
  | { mode: 'manual' }
  | { mode: 'simple'; label: string; value: string; target: SecretTarget };

const secret = computed<SecretState>(() => {
  const preset = presetByName(form.preset);
  if (!preset || preset.secret_target === 'none') return { mode: 'none' };
  const slot = secretSlot(preset);
  if (!slot) return { mode: 'manual' };
  const current = preset.secret_target === 'url' ? form.url : form.body_template;
  const framed =
    current.length >= slot.prefix.length + slot.suffix.length &&
    current.startsWith(slot.prefix) &&
    current.endsWith(slot.suffix);
  if (!framed) return { mode: 'manual' };
  const filled = current.slice(slot.prefix.length, current.length - slot.suffix.length);
  return {
    mode: 'simple',
    label: preset.secret_label || '密钥',
    // 中间那段还是 marker 原文 = 预设刚套上、用户还没填。显示成空串才不会让人以为已经填好了。
    value: filled === preset.secret_marker ? '' : filled,
    target: preset.secret_target,
  };
});

const secretStatusText = computed(() => {
  const s = secret.value;
  if (s.mode !== 'simple') return '';
  if (!s.value) {
    return s.target === 'url'
      ? '尚未填写。留着示例占位时设备端判定为未配置齐全，不会投递。'
      : '尚未填写。留着示例占位时目标服务会拒收这条请求。';
  }
  const where = s.target === 'url' ? '请求地址' : '请求体模板';
  return `已填写（${s.value.length} 位），会写入${where}的对应位置。其余字段用的是该预设的默认值。`;
});

/**
 * 请求体模板下面那句说明，分两档（与 app 侧同口径）。
 *
 * 密钥就写在模板里的那一档（PushPlus）要**提前**说清：模板一旦被改得对不上预设的前后缀，
 * [secret] 就切不出"用户填的那一段"，上面那个简易输入框会消失。
 * 这句话必须在用户动手改模板之前就摆着，否则输入框凭空不见时只会被当成 bug。
 */
const bodyTemplateNote = computed(() => {
  const s = secret.value;
  if (s.mode === 'simple' && s.target === 'body') {
    return `占位符会在发送时替换成通知内容。「${s.label}」就写在这份模板里：改动它周围的结构后，上面那个输入框会消失，改为在这里直接编辑。`;
  }
  return '占位符会在发送时替换成通知内容；每个预设各有一份模板，可随时修改。';
});

/** 地址一眼可见的两类问题：协议不对、预设里的占位没换掉。设备端也会拒，这里先说出来。 */
const urlHint = computed(() => {
  const url = form.url.trim();
  if (!url) return '地址为空时该渠道不会发送。';
  if (!isHttpUrl(url)) return '地址需以 http:// 或 https:// 开头。';
  if (url.includes('<')) return '地址中尖括号包裹的部分是示例，需替换为真实值。';
  return '';
});

const testStatusText = computed(() => {
  const r = testResult.value;
  if (!r) return '';
  const status = r.status_code == null ? '未收到响应，请求未完成' : `HTTP ${r.status_code}`;
  const at = r.attempted_at ? ` · ${formatNotifyTime(r.attempted_at)}` : '';
  return `${status}${at}`;
});

function presetByName(name: string): WebhookPresetItem | undefined {
  return (config.value?.presets ?? []).find((p) => p.name === name);
}

/**
 * 把预设默认值按 `secret_marker` 切成前后缀，作为「中间那段是用户填的密钥」的参照系。
 *
 * 为什么要这么绕：存储里存的是**替换完的**最终 url / body_template，marker 一旦被替换就找不回来
 * （没有"密钥"这个独立字段可读）。只有拿预设默认值当模子，才能反推出中间那一段。
 * 前后缀对不上就说明用户改过别的部分 —— 那种情况只能交给高级设置，硬替换会把他的改动吃掉。
 */
function secretSlot(preset: WebhookPresetItem): { prefix: string; suffix: string } | null {
  const marker = preset.secret_marker || '';
  if (!marker) return null;
  const template = preset.secret_target === 'url' ? preset.url || '' : preset.body_template || '';
  const at = template.indexOf(marker);
  if (at < 0) return null;
  return { prefix: template.slice(0, at), suffix: template.slice(at + marker.length) };
}

/**
 * 简易输入框写回 url / body_template。
 *
 * 两处是硬要求：
 * ① 替换掉的是**整段 marker（连尖括号）**。留下 `<abc123>` 这种半截值会让 core 的
 *    `isConfigured` 一直判"含裸 `<` = 未配置齐全"，渠道永远不投递；
 * ② 清空时写回 marker 而不是留空。空串会拼出 `https://ntfy.sh/` 这类"看着合法、实际打不到目标"
 *    的地址并且能存下来；保住 marker 才能让"没填"继续被判成没填。
 */
function onSecretInput(next: string) {
  const preset = presetByName(form.preset);
  if (!preset) return;
  const slot = secretSlot(preset);
  if (!slot) return;
  const value = next.trim();
  const merged = `${slot.prefix}${value || preset.secret_marker}${slot.suffix}`;
  if (preset.secret_target === 'url') {
    form.url = merged;
  } else {
    form.body_template = merged;
  }
}

/** 按钮上显示的占位符字面量。拼在脚本里是因为模板插值遇到内层 `}}` 会提前截断。 */
function placeholderToken(name: string): string {
  return `{{${name}}}`;
}

/**
 * 把占位符插到请求体模板的**光标处**，而不是追加到末尾 ——
 * 用户点按钮时想插的位置就是他刚点的那个位置，追加到末尾等于让他再剪贴一次。
 */
function insertPlaceholder(name: string) {
  const token = placeholderToken(name);
  const el = bodyFieldRef.value?.querySelector<HTMLTextAreaElement>('textarea');
  const current = form.body_template;
  if (!el) {
    form.body_template = current + token;
    return;
  }
  const start = el.selectionStart ?? current.length;
  const end = el.selectionEnd ?? start;
  form.body_template = current.slice(0, start) + token + current.slice(end);
  // 光标停在插入内容之后，连点几个占位符才能按顺序排下去（DOM 里的值下一帧才更新）
  const caret = start + token.length;
  nextTick(() => {
    el.focus();
    el.setSelectionRange(caret, caret);
  });
}

/**
 * 高级设置的默认开合：简易输入框够用时收起，否则展开。
 * 只在读配置与换预设两个时刻调用 —— 之后用户自己开合的状态不该被界面重置。
 */
function syncAdvancedDefault() {
  expandedPanels.value = secret.value.mode === 'simple' ? [] : [ADVANCED_PANEL];
}

function isHttpUrl(url: string): boolean {
  const lower = url.toLowerCase();
  return lower.startsWith('http://') || lower.startsWith('https://');
}

/**
 * 列表态的请求头脱敏：保留首尾几位。
 * 「我配的是哪一个 token」看首尾就能认出来，而截图不会把完整凭据带出去。
 */
function maskHeaderValue(name: string, value: string): string {
  const lower = (name || '').toLowerCase();
  if (!SENSITIVE_HEADER_HINTS.some((h) => lower.includes(h))) return value;
  if (value.length <= MASK_KEEP_HEAD + MASK_KEEP_TAIL) return `***（${value.length} 位）`;
  return `${value.slice(0, MASK_KEEP_HEAD)}…${value.slice(-MASK_KEEP_TAIL)}（${value.length} 位）`;
}

function msToSec(ms: number | undefined): number {
  const sec = Math.round((Number(ms) || DEFAULT_TIMEOUT_SEC * 1000) / 1000);
  return Math.min(TIMEOUT_MAX_SEC, Math.max(TIMEOUT_MIN_SEC, sec));
}

function headersMap(): Record<string, string> {
  const out: Record<string, string> = {};
  for (const pair of headerPairs.value) {
    const name = (pair.key ?? '').trim();
    if (!name) continue;
    out[name] = pair.value ?? '';
  }
  return out;
}

function applyConfig(data: WebhookConfigDto) {
  config.value = data;
  // 先判数再回落，**绝不能写成 `Number(...) || DAILY_LIMIT_UNLIMITED`**：这条渠道的 0 是
  // 「不限」的合法哨兵，`||` 会把 NaN（字段缺失、类型不符）当成假值落到 0 ——
  // 表现是用户设的限额被悄悄显示成"不限"，再按一次保存就真的提交成不限了。
  // 回落值取设备端下发的 `daily_limit_min`（经 dailyLimitMin，它已带 GET 未回时的兜底）：
  // 前端写死下限的话，core 改了区间这边不会报错，只会显示一个设备侧不接受的值。
  const limit = Number(data.daily_limit);
  Object.assign(form, {
    enabled: !!data.enabled,
    preset: data.preset || 'CUSTOM',
    url: data.url || '',
    method: (data.method || 'POST').toUpperCase(),
    body_template: data.body_template || '',
    content_type: data.content_type || '',
    timeout_sec: msToSec(data.timeout_ms),
    min_level: data.min_level || DEFAULT_MIN_LEVEL,
    daily_limit: Number.isInteger(limit) ? limit : dailyLimitMin.value,
    scenes: [...(data.scenes ?? [])],
    respect_dnd: data.respect_dnd !== false,
  });
  headerPairs.value = Object.entries(data.headers ?? {}).map(([key, value]) => ({
    key,
    value: String(value ?? ''),
  }));
  headersEditing.value = false;
  syncAdvancedDefault();
}

async function loadConfig() {
  loading.value = true;
  try {
    const { data } = await api.get(Endpoints.notify.webhookConfig);
    applyConfig(data);
  } catch (e: any) {
    message.error(e?.response?.data?.error || '加载 Webhook 配置失败');
  } finally {
    loading.value = false;
  }
}

/**
 * 切换预设。
 *
 * 只 PUT `{"preset":"X"}`，一个 per-preset 字段都不带，然后重新 GET：
 *
 * - 带上本地表单那份 url / 模板 / headers，core 会把它们写进**新**预设的槽位 ——
 *   于是"这个预设从未配过"就被坐实成"配成了旧预设的值"；
 * - 由 web 把该预设的默认值填进表单再提交同样有害：默认值是 core 在"读不到"时给的、
 *   刻意不落盘，提交一次之后将来改了预设默认模板就传不到这些用户；
 * - 切完必须以回读为准重画，本地那份是上一个预设的值。
 *
 * `preset` 发的是预设表里的 `name`（下拉的 value 就是它）。**认不出的名字 core 回 400** ——
 * 所以这里把 core 的报错原文显示出来，而不是自己编一句"切换失败"。
 */
async function onPresetChange(name: string) {
  if (name === form.preset || switchingPreset.value) return;
  switchingPreset.value = true;
  try {
    await api.put(Endpoints.notify.webhookConfig, { preset: name });
    await loadConfig();
  } catch (e: any) {
    message.error(e?.response?.data?.error || '切换预设失败');
  } finally {
    switchingPreset.value = false;
  }
}

/**
 * 本地校验只挡住「一眼可见的填错」，让用户当场看到原因；
 * 设备端仍会逐条校验（400 + 说明），两边判据一致。
 */
function validate(): string | null {
  const url = form.url.trim();
  if (url && !isHttpUrl(url)) return '请求地址需以 http:// 或 https:// 开头';
  if (url.includes('<')) return '请求地址中尖括号包裹的部分需替换为真实值';
  // Content-Type 现在是可编辑字段，core 的校验是"不能为空"，这里先拦一次让用户当场看到原因
  if (!form.content_type.trim()) return 'Content-Type 不能为空';
  const sec = form.timeout_sec;
  if (sec == null || !Number.isInteger(sec) || sec < TIMEOUT_MIN_SEC || sec > TIMEOUT_MAX_SEC) {
    return `超时需为 ${TIMEOUT_MIN_SEC} ~ ${TIMEOUT_MAX_SEC} 之间的整数秒`;
  }
  for (const pair of headerPairs.value) {
    const name = (pair.key ?? '').trim();
    const value = pair.value ?? '';
    if (!name && value) return '请求头名称不能为空';
    if (/[\r\n]/.test(name)) return '请求头名称不能含换行';
    if (/[\r\n]/.test(value)) return '请求头的值不能含换行';
  }
  // 每日上限：core 也会校验并回 400，这里先拦一次让用户当场看到区间
  const limit = form.daily_limit;
  if (limit == null || !Number.isInteger(limit) || limit < dailyLimitMin.value || limit > dailyLimitMax.value) {
    return `每日上限需为 ${dailyLimitMin.value} ~ ${dailyLimitMax.value} 之间的整数`;
  }
  return null;
}

async function save() {
  const invalid = validate();
  if (invalid) {
    message.error(invalid);
    return;
  }
  // headers 一律提交全量：设备端对它是整体替换（按键合并的话删不掉旧的凭据头）。
  const payload = {
    enabled: form.enabled,
    preset: form.preset,
    url: form.url.trim(),
    method: form.method,
    headers: headersMap(),
    body_template: form.body_template,
    content_type: form.content_type.trim(),
    timeout_ms: Number(form.timeout_sec) * 1000,
    min_level: form.min_level,
    daily_limit: Number(form.daily_limit),
    scenes: form.scenes,
    respect_dnd: form.respect_dnd,
  };
  saving.value = true;
  try {
    await api.put(Endpoints.notify.webhookConfig, payload);
    // PUT 已经回了新配置，仍再 GET 一次：与邮件卡同口径，只读回显位（configured）以回读为准。
    await loadConfig();
    message.success('配置已保存');
  } catch (e: any) {
    message.error(e?.response?.data?.error || '保存失败');
  } finally {
    saving.value = false;
  }
}

/** 测试不受总开关、免打扰与场景勾选约束，但仍要求地址填完整。 */
async function runTest() {
  testing.value = true;
  try {
    const { data } = await api.post(Endpoints.notify.webhookTest, {});
    testResult.value = data;
    if (data?.success) {
      message.success(data?.status_code ? `测试已送达（HTTP ${data.status_code}）` : '测试已送达');
    } else {
      message.error(data?.error || '测试未送达');
    }
  } catch (e: any) {
    message.error(e?.response?.data?.error || '测试请求失败');
  } finally {
    testing.value = false;
  }
}

// 每次打开都重拉：配置也可能在手机端被改过，用上次的缓存会显示过期内容。
watch(
  () => props.show,
  (v) => {
    if (v) loadConfig();
  },
  { immediate: true }
);
</script>

<style scoped>
/*
  这一处的动效参数：web 侧还没有全局的时长 / 缓动令牌（main.css 只有颜色、圆角、字号三组），
  所以时长与缓动写成两个局部自定义属性（见 .field-swap-*），供那组过渡共用 ——
  直接把 160ms 抄进每条规则的话，以后统一节奏就得逐条改。颜色一律用既有令牌。
*/
.row-hint {
  font-size: 12px;
  color: var(--text-muted);
  line-height: 1.5;
}
/* 风险提示只此一处用告警色：它讲的是「验证码会被发给第三方」这类后果，与普通说明不同权重 */
.risk-hint {
  font-size: 12px;
  color: var(--warning);
  line-height: 1.5;
}
.advanced {
  margin: 6px 0;
}
/* 折叠区里的说明行：按钮 + 说明并排，长说明自然换行到按钮右侧 */
.placeholder-desc {
  margin: 4px 0;
}
.placeholder-desc-row {
  display: flex;
  align-items: baseline;
  gap: 8px;
  padding: 3px 0;
}
/* Transition 的直接子节点必须是单个元素，所以两态各包一层；版式与 .field 内部一致 */
.field-stack {
  display: flex;
  flex-direction: column;
  gap: 4px;
}
/*
  条件渲染的两态互换（简易输入框 ↔ 结构被改过的说明、请求头列表态 ↔ 编辑态）。
  时长与缓动走上面那两个局部变量；只动透明度与很小的位移 —— 高度动画会在
  n-collapse 自己的过渡里叠加成抖动。
*/
.field-swap-enter-active,
.field-swap-leave-active {
  /* 声明在过渡规则自己身上：scoped 样式里的 :root 会被编译成 :root[data-v-…]，选不中 html */
  --webhook-swap-duration: 160ms;
  --webhook-swap-ease: ease-out;
  transition:
    opacity var(--webhook-swap-duration) var(--webhook-swap-ease),
    transform var(--webhook-swap-duration) var(--webhook-swap-ease);
}
.field-swap-enter-from,
.field-swap-leave-to {
  opacity: 0;
  transform: translateY(-4px);
}
.field {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 10px 0;
}
.field-label {
  font-size: 13px;
  color: var(--text-secondary);
}
.field-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}
.form-grid {
  display: grid;
  grid-template-columns: 2fr 1fr;
  gap: 0 14px;
}
.tag-row {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
}
.header-row {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 12px;
  padding: 4px 0;
  border-bottom: 1px solid var(--border-subtle);
}
.header-name {
  font-size: 13px;
  color: var(--text-primary);
  word-break: break-all;
}
.header-value {
  font-size: 12px;
  color: var(--text-muted);
  word-break: break-all;
  text-align: right;
}
.test-result {
  display: flex;
  flex-direction: column;
  gap: 6px;
  margin-top: 10px;
}
.test-head {
  display: flex;
  align-items: center;
  gap: 8px;
}
.resp-body {
  margin: 0;
  font-size: 12px;
  line-height: 1.5;
  color: var(--text-secondary);
  white-space: pre-wrap;
  word-break: break-all;
}
.footer-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
  width: 100%;
}

@media (max-width: 768px) {
  .form-grid {
    grid-template-columns: 1fr;
  }
}
</style>
