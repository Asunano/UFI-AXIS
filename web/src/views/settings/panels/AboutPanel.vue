<template>
  <div class="settings-panel">
    <!--
      卡片编排约束（2026-09-10 起，改这一页前先读）：
      `.settings-panel` 是 2 列栅格，同一行的轨道等高、矮卡贴顶（main.css 的 align-items:start），
      所以**同一行两张卡的高度差必须 ≤ 1 行**（InfoRow 一行 ≈ 38px），
      否则矮卡下方会露出一整块页面底色 —— 改造前这里就有一块 263px 的空洞。
      当前配对（DOM 顺序即栅格顺序，行优先）：
        行 1 = 版本与来源(5 行) ｜ 运行与存储(4 行 + 进度条)
        行 2 = 电池(5 行)        ｜ 诊断信息(5 行)
      增删行之前先按这个约束重算，不要让单张卡独自变长。
    -->
    <!-- Card 1: 版本与来源 —— 原「版本信息」+「开源信息」合并。
         合并的首要理由是排版：开源信息只有 2 行，独立成卡时它与「诊断信息」同行，
         而 2 列栅格的同一行是**等高**的（main.css 的 .settings-panel），矮卡下方会
         露出 147px 整块页面底色（2026-09-10 实测）。
         其次两者语义同源：都是「这个版本从哪来、许可是什么」。 -->
    <GridCard title="版本与来源">
      <InfoRow label="应用版本" :value="versionInfo.version" />
      <InfoRow label="最低客户端版本" :value="versionInfo.minClientVersion" />
      <!-- 长 URL 单独一行并放开 InfoRow 默认的 65% 值宽 -->
      <InfoRow class="row-url" label="更新地址">
        <template #default>
          <code class="url-text" :title="versionInfo.updateUrl || '--'">{{ versionInfo.updateUrl || '--' }}</code>
        </template>
      </InfoRow>
      <InfoRow label="项目仓库">
        <template #default>
          <a :href="REPO_URL" target="_blank" rel="noopener noreferrer">github.com/Asunano/UFI-AXIS</a>
        </template>
      </InfoRow>
      <!-- 为什么这一行必须在 core 侧界面上能点到：那几个预编译二进制
           （aria2c / socat / adb / ttyd / curl / jq / sendat）是 **core APK** 打包并释放到
           设备执行的，其中 aria2c / socat 属 GPL 系 —— 分发义务（附许可证 + 提供对应源码）
           产生在 core 这一侧。app 端「关于」也放了同一个入口（AboutDeviceScreen 的「开源许可」），
           两处指向同一份文件，不各写一份文案，避免漂移。 -->
      <InfoRow label="开源许可">
        <template #default>
          <a :href="THIRD_PARTY_NOTICES_URL" target="_blank" rel="noopener noreferrer">第三方组件与许可证声明</a>
        </template>
      </InfoRow>
    </GridCard>

    <!-- Card 1.2a: 运行与存储 / Card 1.2b: 电池 —— 原「系统信息」一卡 9 行拆成两张 5 行内外的卡。
         拆的理由同样在排版：9 行让这张卡高达 420px，与同行的 3 行卡（173px）差 247px，
         矮的那张下方整块空白（2026-09-10 实测该页空洞 263px）。
         拆成两张后每张 4~5 行，与同行卡的高度差收敛到 1 行以内。
         数据源不变（core /api/system/*）：四个端点都无参数、恒 200、无失败信封；
         battery / storage 采集异常时返回 {}。
         刻意不轮询：设备只有 256MB 内存，仅在挂载与手动点「刷新」时各拉一次。 -->
    <GridCard title="运行与存储">
      <template #extra>
        <n-button size="tiny" quaternary :loading="sysLoading" @click="loadSystemInfo">刷新</n-button>
      </template>
      <!-- 标签说明：这个值来自 /api/system/root-check 的 hasRoot，判据是
           ShellExecutor.hasRootAccess() —— 即「ADB 特权通道是否可用」（底层 ADB shell 是
           uid 2000，不是 uid=0）。它与下面「诊断信息」里那个 Root（uid=0，来自 /api/diagnose
           的真实执行结果）**不是同一个探针**，实测会出现「通道可用但 shell 不是 root」，
           所以两个都保留、标签必须能区分开。 -->
      <InfoRow label="ADB 特权通道">
        <template #default>
          <span :class="sysInfo.hasRoot ? 'text-success' : 'text-error'">{{ sysRootText }}</span>
        </template>
      </InfoRow>
      <InfoRow label="服务启动时间" :value="sysStartupTimeText" />
      <InfoRow label="已运行" :value="sysStartupUptimeText" />
      <InfoRow label="存储（已用 / 总量）" :value="sysStorageText" />
      <n-progress
        v-if="sysInfo.storageLoaded"
        type="line"
        :percentage="sysStoragePercent"
        :height="6"
        style="margin-top: 8px"
      />
    </GridCard>

    <GridCard title="电池">
      <InfoRow label="电池电量" :value="sysBatteryPercentText" />
      <InfoRow label="充电状态" :value="sysBatteryChargingText" />
      <InfoRow label="电源类型" :value="sysBatteryPluggedText" />
      <InfoRow label="电池温度" :value="sysBatteryTempText" />
      <InfoRow label="电池电压" :value="sysBatteryVoltageText" />
    </GridCard>

    <!-- Card 6: 诊断信息
         2026-09-10 去重两行：
         · 「应用版本」删掉 —— 它与「版本与来源」的应用版本是同一件事（都读已安装包版本），
           canonical 来源是 /api/config/version，diagnose 那份原本还写死过 "0.1"（见
           HttpServer.kt 的注释），留着只会让人以为是两个数。
         · 「Root 状态」保留但改名为「Root（uid=0）」—— 它与「运行与存储」的 ADB 特权通道
           **不是同一探针**（这里是 executeAsRoot("id") 的真实结果，含 uid=0 才算），
           原来两个都叫 Root 却给出相反结论（实测「已获取 / 未获取」同屏），所以要分开命名。 -->
    <GridCard title="诊断信息">
      <template #extra>
        <!-- 「字段覆盖率」入口放头部动作区、结果放弹窗，两件事都是被排版逼出来的：
             · 卡内新增行 / footer 会让这张卡比同行的「电池」卡高出一截（footer ≈45px > 1 行 38px），
               违反文件头那条编排约束，电池卡下方会露出页面底色；头部动作区不吃卡体高度。
             · 10 个分组的 hit_source 是几十条映射，塞进 5 行的卡里根本读不了。 -->
        <n-button size="tiny" quaternary :loading="coverageLoading" @click="openCoverage">字段覆盖率</n-button>
        <n-button size="tiny" quaternary @click="loadDiagnostics">刷新</n-button>
      </template>
      <InfoRow label="服务器时间" :value="diag.serverTime || '--'" />
      <InfoRow label="Root（uid=0）">
        <template #default>
          <span :class="diag.root ? 'text-success' : 'text-error'">{{ diag.root ? '已获取' : '未获取' }}</span>
        </template>
      </InfoRow>
      <InfoRow label="ADB 守护进程">
        <template #default>
          <span :class="adbdRunning ? 'text-success' : 'text-error'">{{ diag.adbd || '--' }}</span>
        </template>
      </InfoRow>
      <InfoRow label="移动数据">
        <template #default>
          <span :class="mobileDataOn ? 'text-success' : 'text-error'">{{ mobileDataLabel }}</span>
        </template>
      </InfoRow>
      <InfoRow label="网关" :value="diag.gateway || '--'" />
    </GridCard>

    <!-- 字段覆盖率（GET /api/diagnose?fields=1）——「设备适配改造」的验收工具，计划书 §14.3。
         改造前后各抓一份逐字比对，是「行为没变」的直接证据。以前 web 上没有入口，
         抓基线得手调 API —— 麻烦到这一步就会被跳过，所以这里补一个按钮。
         分组视图与原始 JSON 两份都给：视图用来当场看 missing，
         「复制 JSON」给的是 core 原文，只有原文能贴进文档逐字 diff（渲染后的文字 diff 不出东西）。
         n-modal 会传送到 body，不参与本页 2 列栅格，所以不影响上面那条等高约束。 -->
    <n-modal
      v-model:show="coverageOpen"
      preset="card"
      title="字段覆盖率"
      style="width: 720px; max-width: calc(100vw - 32px)"
    >
      <!-- 这个 ref 不是为了取值，是给 copyToClipboard 当挂载容器用：
           n-modal 默认 trap-focus，插在 document.body 上的临时 textarea 会被焦点陷阱判为「外面」
           并被立刻夺走焦点，execCommand('copy') 于是抄到空（详见 utils.copyToClipboard 的注释）。
           容器随便指到弹窗子树里的哪个元素都行，所以直接借用这条已有的按钮栏，不额外套 div。 -->
      <div ref="coverageBodyRef" class="cov-bar">
        <n-button size="small" :loading="coverageLoading" @click="loadFieldCoverage">
          {{ coverageRaw ? '重新抓取' : '抓取' }}
        </n-button>
        <n-button size="small" :disabled="!coverageJson" @click="copyCoverageJson">复制 JSON</n-button>
        <!-- 这句不是客套话：core 会逐个 FieldGroup 向设备发一次查询（最多 10 组），
             设备只有 256MB 内存，这个按钮不能当刷新用。 -->
        <span class="cov-bar-note">会真向设备发查询（逐分组，最多 10 组），只在需要抓基线时点。</span>
      </div>

      <!-- 加载态用文字而不是 n-spin 遮罩：首次抓取时下面还没有内容可覆盖，遮罩会塌成一条线 -->
      <div v-if="coverageLoading && !coverageRaw" class="cov-note">正在逐分组向设备查询，可能要十几秒…</div>
      <div v-else-if="coverageNote" class="cov-note cov-note--warn">{{ coverageNote }}</div>
      <!-- core 自己统计失败时是 200 + {"error": …}（不是 HTTP 错误），必须单独认出来，
           否则会掉进「groups 为空 ⇒ 显示没有数据」那条分支，把 core 给的原因吞掉 -->
      <div v-else-if="coverageError" class="cov-note cov-note--warn">core 统计覆盖率失败：{{ coverageError }}</div>
      <!-- 排障开关关掉时 core 短路返回 {normalization_enabled:false, hint:…}：照抄它的 hint 原文。
           显示成「没有数据」会让人以为这个入口坏了，而真实原因是开关被人关掉了。 -->
      <div v-else-if="coverageDisabledHint" class="cov-note cov-note--warn">{{ coverageDisabledHint }}</div>
      <template v-else-if="coverageGroups.length">
        <div class="cov-summary">
          <span>profile：{{ coverageProfile }}</span>
          <span>合计命中 {{ coverageTotal.hit }} / 登记 {{ coverageTotal.registered }}</span>
        </div>
        <div v-for="g in coverageGroups" :key="g.name" class="cov-group">
          <div class="cov-group-head">
            <code class="cov-group-name">{{ g.name }}</code>
            <span :class="g.hit >= g.registered ? 'text-success' : 'cov-partial'">
              命中 {{ g.hit }} / 登记 {{ g.registered }}
            </span>
            <!-- queried=false 表示这一组在命令表里没有 cmd，一条查询都没发过 ——
                 与「查了但全没命中」是两件事，混在一起看会把缺命令误判成缺字段 -->
            <span v-if="!g.queried" class="cov-partial">未发查询（这一组没有登记命令）</span>
          </div>
          <div v-if="g.missing.length" class="cov-kv">
            <span class="cov-kv-key">missing</span>
            <code class="cov-kv-val">{{ g.missing.join('、') }}</code>
          </div>
          <div v-if="g.hitSource.length" class="cov-kv">
            <span class="cov-kv-key">hit_source</span>
            <div class="cov-map">
              <code v-for="h in g.hitSource" :key="h.canonical" class="cov-map-item"
                >{{ h.canonical }} ← {{ h.source }}</code
              >
            </div>
          </div>
        </div>
      </template>
      <div v-else class="cov-note">尚未抓取。</div>

      <!-- 原始 JSON 的只读框 —— 这不是「顺便也展示一下」，是「复制按钮的退路」。
           剪贴板这条路有两处不由我们控制：安全上下文（http://<局域网IP> 下 navigator.clipboard
           直接不存在）与 execCommand（已废弃 API，各浏览器随时可以拿掉）。两条都断的时候，
           用户还得拿到这份原文去做逐字 diff，所以必须有一个能 Ctrl+A 的地方。
           因此**默认展开、不做折叠**：折起来的退路等于没有退路。
           放在分组视图之下：分组视图是"当场看 missing"的主路径，不能被这个框挤到折叠线以下。
           等宽字体走 style 而不是新增 scoped class：n-input 内部 textarea-el 是 `font-family: inherit`
           （naive-ui input.cssr.mjs），挂在根节点上就能继承，省掉一条只为改字体存在的样式类。 -->
      <template v-if="coverageJson">
        <div class="cov-note">原始 JSON（复制按钮失效时在这里 Ctrl+A 全选复制）</div>
        <n-input
          :value="coverageJson"
          type="textarea"
          readonly
          :rows="10"
          style="font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace; font-size: var(--font-sm)"
        />
      </template>
    </n-modal>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted } from 'vue';
import { useMessage } from 'naive-ui';
import { getApiClient } from '@/composables/useApi';
import GridCard from '@/components/GridCard.vue';
import InfoRow from '@/components/InfoRow.vue';
import { copyToClipboard, formatBytes } from '@/composables/utils';
import { formatUptime } from '@/views/settings/settingsShared';
import { Endpoints } from '@/api/contract';

const api = getApiClient();
const message = useMessage();

// ── 开源信息（单一真源在仓库里，这里只做跳转）──
const REPO_URL = 'https://github.com/Asunano/UFI-AXIS';
const THIRD_PARTY_NOTICES_URL =
  'https://github.com/Asunano/UFI-AXIS/blob/main/core/src/main/assets/shell/THIRD-PARTY-NOTICES.md';

// ── 版本信息 ──
const versionInfo = reactive({ version: '--', minClientVersion: '--', updateUrl: '' });

// ── 系统信息（core /api/system/*）──
// contract.ts 的 Endpoints 目前没有 system 分组，路径就近集中在这里，避免散落在函数体内。
const SYSTEM_ENDPOINTS = {
  battery: '/api/system/battery',
  storage: '/api/system/storage',
  rootCheck: '/api/system/root-check',
  startupTime: '/api/system/startup-time',
} as const;

const PLUGGED_LABELS: Record<string, string> = {
  AC: '电源适配器',
  USB: 'USB',
  Wireless: '无线充电',
  None: '未接入',
};

const sysLoading = ref(false);
// 采集异常时 core 回 {}，所以每个字段都要能表达"缺失"：数值用 null，percent 沿用 core 的 -1 = 未知。
const sysInfo = reactive({
  rootLoaded: false,
  hasRoot: false,
  startupTimeMs: 0,
  batteryLoaded: false,
  batteryPercent: -1,
  batteryCharging: null as boolean | null,
  batteryPlugged: '',
  batteryTemp: null as number | null,
  batteryVoltage: null as number | null,
  storageLoaded: false,
  storageTotal: 0,
  storageUsed: 0,
  storageUsagePercent: 0,
});
// 「已运行」按拉取时刻计算：不写 Date.now() 进 computed，否则同一 startupTimeMs 重复刷新不会重算。
const sysFetchedAt = ref(0);

/** 只接受有限数值，其余（undefined / null / NaN / 空串）统一当缺失 */
function sysNumber(v: any): number | null {
  if (v === null || v === undefined || v === '') return null;
  const n = Number(v);
  return Number.isFinite(n) ? n : null;
}

// 「ADB 特权通道」用 可用/不可用 而不是 已获取/未获取：它判的是通道，
// 不是 shell 的 uid（uid 那个在「诊断信息」的 Root（uid=0）里）。
const sysRootText = computed(() => (sysInfo.rootLoaded ? (sysInfo.hasRoot ? '可用' : '不可用') : '--'));
const sysStartupTimeText = computed(() =>
  sysInfo.startupTimeMs > 0 ? new Date(sysInfo.startupTimeMs).toLocaleString('zh-CN', { hour12: false }) : '--'
);
const sysStartupUptimeText = computed(() => {
  if (sysInfo.startupTimeMs <= 0 || sysFetchedAt.value <= 0) return '--';
  // 共享版 settingsShared.formatUptime 与拆分前的实现一致，收的是**毫秒**（内部自己 /1000），
  // 所以这里直接传毫秒差，不要再换算。
  return formatUptime(sysFetchedAt.value - sysInfo.startupTimeMs);
});
const sysBatteryPercentText = computed(() =>
  sysInfo.batteryLoaded && sysInfo.batteryPercent >= 0 ? `${sysInfo.batteryPercent}%` : '--'
);
const sysBatteryChargingText = computed(() =>
  sysInfo.batteryCharging === null ? '--' : sysInfo.batteryCharging ? '充电中' : '未充电'
);
const sysBatteryPluggedText = computed(
  () => PLUGGED_LABELS[sysInfo.batteryPlugged] ?? (sysInfo.batteryPlugged || '--')
);
const sysBatteryTempText = computed(() =>
  sysInfo.batteryTemp === null ? '--' : `${sysInfo.batteryTemp.toFixed(1)}°C`
);
const sysBatteryVoltageText = computed(() =>
  sysInfo.batteryVoltage === null ? '--' : `${sysInfo.batteryVoltage.toFixed(2)} V`
);
const sysStorageText = computed(() =>
  sysInfo.storageLoaded ? `${formatBytes(sysInfo.storageUsed)} / ${formatBytes(sysInfo.storageTotal)}` : '--'
);
const sysStoragePercent = computed(() => Number(Math.min(100, Math.max(0, sysInfo.storageUsagePercent)).toFixed(1)));

// ── 诊断 ──
// 刻意不收 app_version：它与「版本与来源」的应用版本同源（都读已安装包版本），
// 展示两份只会制造「哪个是真的」的疑问。canonical 来源是 /api/config/version。
const diag = reactive({
  serverTime: '',
  root: false,
  adbd: '',
  mobileData: '',
  gateway: '',
});

// diagnose 的 adbd / mobile_data 是 shell 原始输出字符串
const adbdRunning = computed(() => diag.adbd === 'running');
const mobileDataOn = computed(() => diag.mobileData === '1' || diag.mobileData.toLowerCase() === 'true');
const mobileDataLabel = computed(() => {
  if (!diag.mobileData || diag.mobileData === 'unknown') return '未知';
  return mobileDataOn.value ? '已开启' : '已关闭';
});

// ── 字段覆盖率（GET /api/diagnose?fields=1）──
// 真实结构读自 core（只读，没改）：HttpServer.kt 的 /diagnose 分支 +
// GoformFieldMapper.coverageReport()。正常形态：
//   { normalization_enabled: true, profile_id, profile_name,
//     groups: { <FieldGroup>: { queried, registered, hit, missing: [...], hit_source: {canonical: source} } } }
// 还有两种退化形态，**都是 HTTP 200**，都必须认：
//   · core 统计自身抛异常  → { error: "<原因>" }（HttpServer 的 catch 分支）
//   · 归一化开关被关掉     → { normalization_enabled: false, hint: "…" }（mapper 开头的短路）
// 所以这里不给它定强类型、也不按固定字段解，按未知形状小心取值 —— 形状随 core 演进，
// 而"能复制出原文"这件事不能跟着一起坏。
const coverageOpen = ref(false);
const coverageLoading = ref(false);
const coverageRaw = ref<Record<string, any> | null>(null);
/**
 * 弹窗内容的容器，只用来给 `copyToClipboard` 当临时 textarea 的挂载点。
 * n-modal 的 trap-focus 会把插在 body 上的 textarea 判成弹窗外元素并夺回焦点，
 * 那样 execCommand('copy') 抄到的是空选区 —— 这就是 2026-09-22 实测「点了复制没反应」的成因。
 */
const coverageBodyRef = ref<HTMLElement | null>(null);
/** 拿不到 field_coverage 这个块本身的原因（传输失败 / core 没回它），与 core 自己的 error 分开 */
const coverageNote = ref('');
// api 客户端默认 15s 是按"一次普通查询"定的，这里串行发最多 10 次设备查询，放宽到 60s。
const COVERAGE_TIMEOUT_MS = 60_000;

interface CoverageGroupView {
  name: string;
  queried: boolean;
  registered: number;
  hit: number;
  missing: string[];
  hitSource: { canonical: string; source: string }[];
}

const coverageJson = computed(() => (coverageRaw.value ? JSON.stringify(coverageRaw.value, null, 2) : ''));
const coverageError = computed(() => {
  const e = coverageRaw.value?.error;
  return typeof e === 'string' ? e : '';
});
const coverageDisabledHint = computed(() => {
  const raw = coverageRaw.value;
  // 只认显式的 false：缺这个键（例如退化成 {error}）不等于"开关被关了"
  if (!raw || raw.normalization_enabled !== false) return '';
  // hint 照抄 core 原文；万一哪天 core 只回 false 不回 hint，也不能显示成空白
  return String(raw.hint ?? '字段归一化已关闭，core 侧没有登记表可比对。');
});
const coverageProfile = computed(() => {
  const raw = coverageRaw.value;
  const id = String(raw?.profile_id ?? '');
  const name = String(raw?.profile_name ?? '');
  return name ? `${id}（${name}）` : id || '--';
});
const coverageGroups = computed<CoverageGroupView[]>(() => {
  const groups = coverageRaw.value?.groups;
  if (!groups || typeof groups !== 'object') return [];
  // 刻意不排序：core 是按 FieldGroup 的声明顺序输出的，两份快照顺序一致才好逐字比对
  return Object.entries(groups as Record<string, any>).map(([name, g]) => ({
    name,
    queried: !!g?.queried,
    registered: Number(g?.registered ?? 0),
    hit: Number(g?.hit ?? 0),
    missing: Array.isArray(g?.missing) ? g.missing.map((m: any) => String(m)) : [],
    hitSource: Object.entries((g?.hit_source ?? {}) as Record<string, any>).map(([canonical, source]) => ({
      canonical,
      source: String(source),
    })),
  }));
});
const coverageTotal = computed(() =>
  coverageGroups.value.reduce((acc, g) => ({ registered: acc.registered + g.registered, hit: acc.hit + g.hit }), {
    registered: 0,
    hit: 0,
  })
);

// ══════════════════════════════════════════════
//  Data Loading
// ══════════════════════════════════════════════

async function loadVersion() {
  try {
    const { data } = await api.get('/api/config/version');
    versionInfo.version = data.version || '--';
    versionInfo.minClientVersion = data.min_client_version || '--';
    versionInfo.updateUrl = data.update_url || '';
  } catch {
    /* silent */
  }
}

// 四个 /api/system/* 端点并发拉取：任何一个失败（网络/超时）都不影响其余卡片内容，
// 因此用 allSettled 而不是 all；core 侧这些端点恒 200，失败只会来自传输层。
async function loadSystemInfo() {
  sysLoading.value = true;
  try {
    const [battery, storage, rootCheck, startup] = await Promise.allSettled([
      api.get(SYSTEM_ENDPOINTS.battery),
      api.get(SYSTEM_ENDPOINTS.storage),
      api.get(SYSTEM_ENDPOINTS.rootCheck),
      api.get(SYSTEM_ENDPOINTS.startupTime),
    ]);

    if (battery.status === 'fulfilled') {
      const d = battery.value.data ?? {};
      const percent = sysNumber(d.percent);
      const temp = sysNumber(d.temperature);
      const voltage = sysNumber(d.voltage);
      // 空对象 {} = 采集失败：percent 缺失时按未知(-1)处理，其余保持 null 显示 --
      sysInfo.batteryLoaded = percent !== null || temp !== null || voltage !== null;
      sysInfo.batteryPercent = percent ?? -1;
      sysInfo.batteryCharging = typeof d.is_charging === 'boolean' ? d.is_charging : null;
      sysInfo.batteryPlugged = typeof d.plugged === 'string' ? d.plugged : '';
      sysInfo.batteryTemp = temp;
      sysInfo.batteryVoltage = voltage;
    }

    if (storage.status === 'fulfilled') {
      const d = storage.value.data ?? {};
      const total = sysNumber(d.total);
      const used = sysNumber(d.used);
      // total 为 0 时进度条无意义（也除不出百分比），按未加载处理
      sysInfo.storageLoaded = total !== null && total > 0;
      sysInfo.storageTotal = total ?? 0;
      sysInfo.storageUsed = used ?? 0;
      sysInfo.storageUsagePercent = sysNumber(d.usage_percent) ?? 0;
    }

    if (rootCheck.status === 'fulfilled') {
      // 字段是 camelCase 的 hasRoot（与 /api/diagnose 的 root 不同源，各自独立展示）
      sysInfo.hasRoot = !!rootCheck.value.data?.hasRoot;
      sysInfo.rootLoaded = true;
    }

    if (startup.status === 'fulfilled') {
      sysInfo.startupTimeMs = sysNumber(startup.value.data?.startupTimeMs) ?? 0;
    }

    sysFetchedAt.value = Date.now();
  } finally {
    sysLoading.value = false;
  }
}

async function loadDiagnostics() {
  try {
    const { data } = await api.get(Endpoints.diagnose);
    applyDiagnostics(data);
  } catch {
    /* silent */
  }
}

/** 解析 /api/diagnose 的基础字段。抽出来是因为带 `fields=1` 的那次请求回的是同一份信封。 */
function applyDiagnostics(data: any) {
  // server_time 是 epoch 毫秒；旧代码直接渲染原始数字
  diag.serverTime = data.server_time
    ? new Date(Number(data.server_time)).toLocaleString('zh-CN', { hour12: false })
    : '--';
  diag.root = !!data.root;
  // adbd / mobile_data / gateway 都是 shell 输出的字符串（如 "running"/"stopped"/"1"/"0"/"unknown"），
  // 旧代码用 !! 判断 → "stopped"、"0"、"unknown" 全部被当成 true
  diag.adbd = String(data.adbd ?? '').trim();
  diag.mobileData = String(data.mobile_data ?? '').trim();
  diag.gateway = String(data.gateway ?? '').trim();
}

/** 开弹窗顺带抓第一份；已经有结果就不再自动抓 —— 回看旧快照不该再向设备发 10 次查询。 */
function openCoverage() {
  coverageOpen.value = true;
  if (!coverageRaw.value && !coverageLoading.value) loadFieldCoverage();
}

async function loadFieldCoverage() {
  coverageLoading.value = true;
  coverageNote.value = '';
  try {
    // 用 params 而不是手拼 URL：请求签名走的是 client.getUri(config)（见 useApi.ts 的注释），
    // 手拼的 query 与签名用的 URI 不是同一套序列化时会 100% 验签失败。
    const { data } = await api.get(Endpoints.diagnose, { params: { fields: 1 }, timeout: COVERAGE_TIMEOUT_MS });
    // 同一份响应里带着基础诊断字段，顺手刷新：那几行是 root shell 跑出来的，能省一趟就省一趟
    applyDiagnostics(data);
    const cov = data?.field_coverage;
    if (cov && typeof cov === 'object') {
      coverageRaw.value = cov;
    } else {
      // core 只在 `fields=1` 时才塞这个块；真缺了说明这版 core 不认这个参数，
      // 如实说出来，不要让它长得像"设备一个字段都没命中"
      coverageRaw.value = null;
      coverageNote.value = '响应里没有 field_coverage —— 这版 core 可能还不认 ?fields=1。';
    }
  } catch (e: any) {
    coverageRaw.value = null;
    coverageNote.value = `抓取失败：${e?.message || '未知错误'}`;
  } finally {
    coverageLoading.value = false;
  }
}

/**
 * 复制 core 原文的格式化 JSON。
 * 复制的必须是原始 JSON 而不是上面渲染出来的文字：验收要做的是两份快照逐字 diff，
 * 渲染文本（"命中 9 / 登记 10"）diff 不出 hit_source 那一层的变化。
 * 复制实现复用 composables/utils 的 copyToClipboard（它带 execCommand 兜底 ——
 * 本面板常从 http://<局域网IP>:8088 打开，那里 navigator.clipboard 是 undefined）。
 * 第二个参数是关键：这里是 n-modal 内部，兜底用的临时 textarea 必须挂在弹窗子树里，
 * 否则 trap-focus 会把焦点抢走，复制到的是空内容（2026-09-22 实测的 bug）。
 */
async function copyCoverageJson() {
  if (!coverageJson.value) return;
  if (await copyToClipboard(coverageJson.value, coverageBodyRef.value)) {
    message.success('已复制 field_coverage 原始 JSON');
  } else {
    // 指路到下面那个只读框，而不是笼统说"手动复制"——用户得知道手动复制该去哪一块
    message.error('复制失败，请在下方「原始 JSON」框里 Ctrl+A 全选复制');
  }
}

// ══════════════════════════════════════════════
//  Lifecycle
// ══════════════════════════════════════════════
// 本面板全部只读且无轮询（系统信息刻意只在挂载与手动刷新时各拉一次），因此没有需要
// 在 onUnmounted 里清理的定时器。

onMounted(() => {
  loadVersion();
  loadSystemInfo();
  loadDiagnostics();
});
</script>

<style scoped>
/* .settings-panel 栅格与断点已统一到 src/styles/main.css（全局，8 个面板共用一份） */

/* ── 更新地址行 ──
   InfoRow 默认给值限了 65% 宽并省略号（InfoRow.vue 的 .info-value），对短值是对的，
   但更新地址是本页唯一需要看清 / 抄走的长字符串，65% 会把它截成 "…/ma…"。
   这里只对这一行放开到整行，并把字号降到 12px —— 实测同一条 URL 在 12px 下能整行放下。
   不用 <code> 等宽：68 个字符在 12px 等宽下约 490px，会超出 482px 的可用宽。 */
.row-url :deep(.info-value) {
  max-width: 100%;
}
.url-text {
  font-size: 12px;
  color: var(--text-secondary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* ── Text helpers ── */
.text-success {
  color: var(--success);
}
.text-error {
  color: var(--error);
}

/* ── 字段覆盖率弹窗 ──
   全部走令牌：这一页的 <style> 里一个写死颜色都没有，check-ui-baseline 的
   scopedColorLiterals 只卡增量，本文件要保持 0。
   n-modal 会把内容传送到 body，但 scoped 属性是渲染时打在元素上的，跟着一起走，
   所以这些选择器在弹窗里依然生效。 */
.cov-bar {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 12px;
}
.cov-bar-note {
  font-size: var(--font-sm);
  color: var(--text-secondary);
}
.cov-note {
  font-size: var(--font-base);
  color: var(--text-secondary);
  padding: 8px 0;
}
.cov-note--warn {
  color: var(--warning);
}
.cov-summary {
  display: flex;
  flex-wrap: wrap;
  gap: 4px 16px;
  padding-bottom: 8px;
  border-bottom: 1px solid var(--border-subtle);
  font-size: var(--font-sm);
  color: var(--text-secondary);
}
.cov-group {
  padding: 8px 0;
  border-bottom: 1px solid var(--border-subtle);
}
.cov-group:last-child {
  border-bottom: none;
}
.cov-group-head {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 4px 10px;
  font-size: var(--font-sm);
  color: var(--text-secondary);
}
.cov-group-name {
  font-size: var(--font-base);
  font-weight: 600;
  color: var(--text-primary);
}
.cov-partial {
  color: var(--warning);
}
.cov-kv {
  display: flex;
  gap: 8px;
  margin-top: 4px;
  font-size: var(--font-sm);
}
.cov-kv-key {
  flex: 0 0 68px;
  color: var(--text-muted);
}
/* 这些字段名是拿去逐字比对的，宁可折行占高也不能省略号截断（对照 .url-text 那条）*/
.cov-kv-val,
.cov-map {
  min-width: 0;
  color: var(--text-secondary);
  word-break: break-all;
}
.cov-map {
  display: flex;
  flex-wrap: wrap;
  gap: 4px 8px;
}
.cov-map-item {
  padding: 0 4px;
  border-radius: var(--radius-sm);
  background: var(--code-bg);
}

@media (max-width: 768px) {
  /* 上面那条「12px 下能整行放下」的实测是按 482px 列宽算的；360px 视口下卡内只有
     ~298px，68 个字符 12px 非等宽 ≈410px ⇒ 约 1/4 被静默切掉。
     而 `.url-text` 是 inline 元素（原先是 <code>），**inline 上 overflow / ellipsis 无效**，
     只有 nowrap 生效 —— 所以连省略号都没有，用户看不出被截过。
     窄屏改成整行换行显示：这条 URL 的用途就是抄走，宁可占 2~3 行也不能少字符。 */
  /* `.row-url` 是传给 InfoRow 的 class，Vue 把它合并到组件根节点上 ——
     也就是说这个元素同时带 `.info-row` 与 `.row-url`，直接写 `.row-url` 即可，
     不需要（也不能用）后代选择器去找 `.info-row`。 */
  .row-url {
    flex-direction: column;
    align-items: flex-start;
    gap: 2px;
  }
  .row-url :deep(.info-value) {
    text-align: left;
    max-width: 100%;
    /* InfoRow 基础样式在 .info-value 上写了 nowrap + ellipsis，这里放开 */
    white-space: normal;
    overflow: visible;
  }
  .url-text {
    display: block;
    white-space: normal;
    word-break: break-all;
    overflow: visible;
  }
}
</style>
