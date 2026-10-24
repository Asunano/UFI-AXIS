<template>
  <div class="settings-panel">
    <!-- Card 3: QoS 配置
         .full-width 是 2026-10-14 显式补上的：本面板原先只有这一张卡，靠 main.css 的
         `.settings-panel > :only-child` 自动通栏；下面加了「缓存管理」卡之后 :only-child 不再命中，
         不显式声明就会变成只占左半列（右半列裸露），与加卡前的观感不一致。 -->
    <GridCard class="full-width" title="QoS 配置">
      <!-- 三态（2026-10-21）：读不到配置时**不能**把下面那组前端默认值当成设备取值展示 ——
           原来这里是静默 catch，界面显示的是 shellMaxConcurrent:5 / cacheTtlMs:5000 这类硬编码初值，
           用户会以为「设备现在就是这个配置」。写法对齐 GeneralPanel / LogsPanel 的纪律：
           表单留在原位但整组禁用，上方明确写「读取失败 + 重试」，保存一并禁用
           （保存本来也发不出去：buildChangedPayload 对没有基线的键全部跳过）。 -->
      <div v-if="qosConfigState !== 'ok'" class="load-failed">
        <template v-if="qosConfigState === 'loading'">
          <span class="load-hint">正在读取 QoS 配置…</span>
        </template>
        <template v-else>
          <span>QoS 配置读取失败，下面的值不是设备当前取值（保存已禁用）。</span>
          <n-button size="tiny" quaternary :loading="qosConfigLoading" @click="loadQosConfig">重试</n-button>
        </template>
      </div>
      <div class="config-grid">
        <div class="config-item switch-item">
          <span class="config-label">启用</span>
          <n-switch v-model:value="qosForm.enabled" :disabled="qosConfigState !== 'ok'" />
        </div>
        <div class="config-item">
          <span class="config-label">Shell 最大并发 (1-10)</span>
          <n-input-number
            v-model:value="qosForm.shellMaxConcurrent"
            :min="ConfigLimits.qosShellMaxConcurrent[0]"
            :max="ConfigLimits.qosShellMaxConcurrent[1]"
            :disabled="qosConfigState !== 'ok'"
            size="small"
          />
        </div>
        <div class="config-item">
          <span class="config-label">缓存 TTL (500-30000 ms)</span>
          <n-input-number
            v-model:value="qosForm.cacheTtlMs"
            :min="ConfigLimits.qosCacheTtlMs[0]"
            :max="ConfigLimits.qosCacheTtlMs[1]"
            :step="500"
            :disabled="qosConfigState !== 'ok'"
            size="small"
          />
        </div>
        <div class="config-item">
          <span class="config-label">设备后台查询上限 (1-8)</span>
          <n-input-number
            v-model:value="qosForm.goformQueryLimit"
            :min="ConfigLimits.qosGoformQueryMax[0]"
            :max="ConfigLimits.qosGoformQueryMax[1]"
            :disabled="qosConfigState !== 'ok'"
            size="small"
          />
        </div>
        <div class="config-item">
          <span class="config-label">设备后台设置上限 (1-4)</span>
          <n-input-number
            v-model:value="qosForm.goformSetLimit"
            :min="ConfigLimits.qosGoformSetMax[0]"
            :max="ConfigLimits.qosGoformSetMax[1]"
            :disabled="qosConfigState !== 'ok'"
            size="small"
          />
        </div>
      </div>
      <div class="card-actions">
        <n-button type="primary" size="small" :loading="savingQos" :disabled="qosConfigState !== 'ok'" @click="saveQos"
          >保存</n-button
        >
      </div>
      <n-divider class="block-divider" />
      <div class="section-subtitle">当前状态</div>
      <div v-if="qosStatusState === 'failed'" class="load-failed">
        <span>状态读取失败，下面不是设备此刻的读数。</span>
        <n-button size="tiny" quaternary :loading="qosStatusLoading" @click="loadQosStatus">重试</n-button>
      </div>
      <div v-else-if="qosStatusState === 'loading'" class="load-hint">正在读取状态…</div>
      <div v-else-if="qosStatusData.length" class="status-grid">
        <InfoRow v-for="item in qosStatusData" :key="item.label" :label="item.label" :value="item.value" />
      </div>
      <div v-else class="text-muted">暂无状态数据</div>
    </GridCard>

    <!-- Card 3.5: 缓存管理（2026-10-14 从「服务」面板搬来，「各回各家」）───────────
         为什么算「性能」而不是「服务」：服务面板管的是**采集服务的起停**
         （start/stop/restart/autostart），而这里是「缓存」这个话题 —— 与上面 QoS 卡里的
         「缓存 TTL」是同一张表的两端：一个定缓存活多久、一个立刻把缓存清掉。
         两者放在同一个面板才讲得通，所以紧跟在 QoS 卡下面。
         **但两者不是同一个缓存**（2026-10-14 读 core 确认，别照字面想当然）：
           · `qos_cache_ttl_ms` → `ShellQoS.updateCacheTtl` / `GoformQoS.updateCacheTtl`
             （shell / goform 的查询缓存，见 ConfigRoutes.kt:216）；
           · `/api/cache/*` → `ctx.responseCache`（服务端 HTTP 响应缓存，见 HttpServer.kt:604）。
         所以清完这里的缓存**不会**改变上面「当前状态」里那两条 shell 缓存读数，
         不需要跟着回读 /api/qos/status —— 硬加上去只会让人误以为两者是同一个缓存。
         位置为什么在 QoS 卡**之后**而不是插进卡中间：QoS 卡内部「配置 → 当前状态」是一条
         连续的阅读线（先看设了什么值、再看此刻的令牌/条目数），从中间插入会把它断开。
         为什么不塞进 QoS 卡的 footer：QoS 是**配置**（一组值 + 一个「保存」），本卡是**动作**
         （清），混在一张卡里会让「保存」看起来也管着清缓存。
         为什么两张卡都显式写 .full-width：本面板从「单卡面板」变成两张卡后，
         main.css 的 `.settings-panel > :only-child` 通栏规则不再命中 —— 不写的话两张卡会各占
         一列，而 QoS 卡（表单 + 11 条状态）比本卡高出一大截，右列会空出一大块。 -->
    <GridCard class="full-width" title="缓存管理">
      <!-- 缓存统计同样是三态：读失败时原来的 0 / 0 B 会被读成"缓存是空的"，
           而它其实是"没读到"。缺值统一 --（与 §7 家规第 5 条同口径）。 -->
      <InfoRow label="条目数" :value="cacheState === 'failed' ? '--' : cacheStats.entries" />
      <InfoRow label="缓存大小" :value="cacheState === 'failed' ? '--' : cacheStats.size" />
      <div v-if="cacheState === 'failed'" class="load-failed">
        <span>缓存统计读取失败。</span>
        <n-button size="tiny" quaternary :loading="cacheLoading" @click="loadCacheStats">重试</n-button>
      </div>
      <div class="card-actions">
        <n-button type="warning" size="small" :loading="cacheLoading" @click="clearCache">清空缓存</n-button>
      </div>
      <n-divider class="block-divider" />
      <div class="section-subtitle">按模式清除</div>
      <div class="inline-group">
        <n-input v-model:value="cachePattern" class="grow" placeholder="输入匹配模式" size="small" />
        <n-button size="small" :disabled="!cachePattern" :loading="cacheLoading" @click="invalidateCache"
          >清除</n-button
        >
      </div>
    </GridCard>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted } from 'vue';
import { useMessage, useDialog } from 'naive-ui';
import { useCancellableApi } from '@/composables/useCancellableApi';
import GridCard from '@/components/GridCard.vue';
import InfoRow from '@/components/InfoRow.vue';
import { ConfigLimits } from '@/api/contract';
import { buildChangedPayload, commitConfigSave, findUnbaselinedKeys } from '@/views/settings/settingsShared';

const message = useMessage();
const dialog = useDialog();
// 取数一律走 useCancellableApi（本仓规范）：组件卸载即 abort 在途请求。
// 被取消的请求 resolve 成 `{ data: undefined }` 而不是抛错，所以下面每个 await 后都要有
// `if (!data) return;` 守卫 —— 少了它，卸载瞬间会把 undefined 当成「后端回了空」，
// 要么弹一条假报错，要么把已读到的状态抹掉。
const api = useCancellableApi();

// ── QoS ──
const savingQos = ref(false);
/**
 * 两个取数的三态（2026-10-21）。原来两处 catch 都是静默的：
 * 失败后表单停在硬编码初值、状态区停在"暂无状态数据"，两者都被读成设备的真实情况。
 * 现在明确区分「加载中 / 有数据 / 读取失败（可重试）」，失败时隐藏默认值并禁用保存。
 */
const qosConfigState = ref<'loading' | 'ok' | 'failed'>('loading');
const qosStatusState = ref<'loading' | 'ok' | 'failed'>('loading');
const qosConfigLoading = ref(false);
const qosStatusLoading = ref(false);
const qosOriginal = reactive<Record<string, any>>({});
// 取值范围见 contract 的 ConfigLimits（与 core ConfigRoutes 校验共用）；
// 越界字段会出现在响应的 rejected_fields 里并带 min/max，因此默认值也必须落在范围内。

const qosForm = reactive({
  enabled: false,
  shellMaxConcurrent: 5,
  cacheTtlMs: 5000,
  goformQueryLimit: 4,
  goformSetLimit: 2,
});
const qosStatus = ref<Record<string, any>>({});

// core /api/qos/status 是嵌套结构：
// { enabled, shell_qos:{root:{available,total,target}, normal:{...}, cache:{entries,ttl_ms}, batch:{...}},
//   goform_qos:{...}, cpu_temp: Int(毫摄氏度), dynamic_pool:{core,current,max} }
// 旧代码 Object.entries + String(value) → 所有嵌套对象都渲染成 [object Object]
const QOS_LABELS: Record<string, string> = {
  'shell_qos.root.available': 'Shell Root 可用令牌',
  'shell_qos.root.total': 'Shell Root 令牌总数',
  'shell_qos.root.target': 'Shell Root 目标令牌',
  'shell_qos.normal.available': 'Shell 普通可用令牌',
  'shell_qos.normal.total': 'Shell 普通令牌总数',
  'shell_qos.cache.entries': 'Shell 缓存条目',
  'shell_qos.cache.ttl_ms': 'Shell 缓存 TTL',
  cpu_temp: 'CPU 温度',
  'dynamic_pool.core': '线程池核心数',
  'dynamic_pool.current': '线程池当前数',
  'dynamic_pool.max': '线程池上限',
};

/**
 * 完整路径 → 中文名。上面那张表覆盖的是**当前 core 的已知字段**；
 * 字段一旦增补（core 是唯一真源，web 不该预知全部），就落到下面的分段映射。
 */
const QOS_SEGMENT_LABELS: Record<string, string> = {
  shell_qos: 'Shell 令牌池',
  goform_qos: '设备后台令牌池',
  root: 'Root',
  normal: '普通',
  cache: '缓存',
  batch: '批量',
  entries: '条目数',
  ttl_ms: 'TTL',
  available: '可用',
  total: '总数',
  target: '目标',
  dynamic_pool: '动态线程池',
  core: '核心',
  current: '当前',
  max: '上限',
  min: '下限',
  cpu_temp: 'CPU 温度',
};

/**
 * 路径 → 中文标签。
 *
 * 兜底**不再**把键名首字母大写拼成 `Shell Qos Xxx`（那是把英文键直接送上屏）：
 * 先按段查中文，任何一段认不出就整条显示成「未知项（原路径）」——
 * 中文名 + 原始路径，用户看得懂"这不是你能改的项"，也能把路径报给开发者。
 */
function humanizeQosKey(path: string): string {
  if (QOS_LABELS[path]) return QOS_LABELS[path]!;
  const segments = path.split('.');
  const labels = segments.map((seg) => QOS_SEGMENT_LABELS[seg]);
  if (labels.every((l): l is string => !!l)) return labels.join(' · ');
  return `未知项（${path}）`;
}

function formatQosValue(path: string, value: any): string {
  // 缺值统一 --（0 是合法值：温度 0 摄氏度、令牌数 0 都要照实显示）
  if (value == null) return '--';
  if (path === 'cpu_temp') {
    const n = Number(value);
    if (!Number.isFinite(n)) return '--';
    // thermal_zone 读数是毫摄氏度（如 42000）
    return (n > 1000 ? n / 1000 : n).toFixed(1) + ' °C';
  }
  if (path.endsWith('ttl_ms')) return `${Number(value)} ms`;
  if (typeof value === 'boolean') return value ? '是' : '否';
  return String(value);
}

function flattenQos(obj: any, prefix = '', out: { label: string; value: string }[] = []) {
  for (const [key, value] of Object.entries(obj || {})) {
    const path = prefix ? `${prefix}.${key}` : key;
    // enabled 在 /status 里是硬编码 true，不代表配置项，避免与上方开关冲突
    if (path === 'enabled') continue;
    if (value !== null && typeof value === 'object' && !Array.isArray(value)) {
      flattenQos(value, path, out);
    } else {
      out.push({ label: humanizeQosKey(path), value: formatQosValue(path, value) });
    }
  }
  return out;
}

const qosStatusData = computed(() => flattenQos(qosStatus.value));

async function loadQosStatus() {
  if (qosStatusState.value !== 'ok') qosStatusState.value = 'loading';
  qosStatusLoading.value = true;
  try {
    const { data } = await api.get('/api/qos/status');
    // 取消（面板已卸载）：保留上一次的状态标记，别把它判成"读取失败"
    if (!data) return;
    qosStatus.value = data;
    qosStatusState.value = 'ok';
  } catch {
    // 失败要可见：状态区显示「读取失败 + 重试」，而不是"暂无状态数据"
    qosStatusState.value = 'failed';
  } finally {
    qosStatusLoading.value = false;
  }
}

async function loadQosConfig() {
  if (qosConfigState.value !== 'ok') qosConfigState.value = 'loading';
  qosConfigLoading.value = true;
  try {
    const { data } = await api.get('/api/config');
    if (!data) return;
    const qosKeys = [
      'qos_enabled',
      'qos_shell_max_concurrent',
      'qos_cache_ttl_ms',
      'qos_goform_query_max',
      'qos_goform_set_max',
    ] as string[];
    const formKeys = ['enabled', 'shellMaxConcurrent', 'cacheTtlMs', 'goformQueryLimit', 'goformSetLimit'] as string[];
    for (let i = 0; i < qosKeys.length; i++) {
      const key = qosKeys[i]!;
      const formKey = formKeys[i]!;
      if (data[key] !== undefined) {
        (qosForm as any)[formKey] = data[key];
        qosOriginal[key] = data[key];
      }
    }
    qosConfigState.value = 'ok';
  } catch {
    // 读不到配置时**不能**把 qosForm 里的硬编码初值当成设备取值展示（见模板顶部说明）
    qosConfigState.value = 'failed';
  } finally {
    qosConfigLoading.value = false;
  }
}

async function saveQos() {
  const formKeys: Record<string, string> = {
    enabled: 'qos_enabled',
    shellMaxConcurrent: 'qos_shell_max_concurrent',
    cacheTtlMs: 'qos_cache_ttl_ms',
    goformQueryLimit: 'qos_goform_query_max',
    goformSetLimit: 'qos_goform_set_max',
  };
  // 无条件提示「core 不认的键」：缺基线的控件显示的是这里的前端默认值（如 cacheTtlMs: 5000），
  // 不是设备真实取值，buildChangedPayload 又会把它整个跳过 —— 用户只会看到「没有更改」。
  const unbaselined = findUnbaselinedKeys(formKeys, qosOriginal);
  if (unbaselined.length > 0) {
    message.warning(`以下配置项当前 core 版本不支持，已忽略：${unbaselined.join('、')}`);
  }
  const payload = buildChangedPayload(formKeys, qosForm, qosOriginal);
  if (Object.keys(payload).length === 0) {
    message.info('没有更改');
    return;
  }
  savingQos.value = true;
  try {
    const { data } = await api.put('/api/config', payload);
    // 取消（面板已卸载）：直接返回。不能把 undefined 交给 commitConfigSave ——
    // 它会把 payload 里每个键都判成「被拒」并弹一条假报错。
    if (!data) return;
    commitConfigSave(payload, data, qosOriginal, 'QoS 配置', message, loadQosConfig);
    loadQosStatus();
  } catch (e: any) {
    message.error(e?.response?.data?.error || '保存失败');
  } finally {
    savingQos.value = false;
  }
}

// ── 缓存管理（2026-10-14 从 ServicePanel 搬来）──────────────────────────────
//
// core 侧 `ResponseCache.getStats()` 的形状：
// { count, max_entries, any_cache_count, total_bytes_estimate, stale, entries[] }。
// 旧代码读的是 data.entries（那其实是数组）与 data.size / data.bytes（都不存在），
// 于是条目数错乱、大小恒为 0 B —— 下面这两个字段名不要再改回去。
const cacheStats = reactive({ entries: 0, size: '0 B' });
/** 缓存统计的加载态：读失败时三处读数显示 --，不能把 0 / 0 B 当成"缓存是空的" */
const cacheState = ref<'loading' | 'ok' | 'failed'>('loading');
const cachePattern = ref('');
/** 清空与按模式清除共用一个 loading：两者都打同一个后端缓存，同时点没有意义。 */
const cacheLoading = ref(false);

async function loadCacheStats() {
  if (cacheState.value !== 'ok') cacheState.value = 'loading';
  try {
    const { data } = await api.get('/api/cache/stats');
    if (!data) return;
    // count 只算主缓存；any_cache_count 是第二个缓存（getOrPutAny）且**不计入 count**，
    // 两个相加才是界面上「条目数」想表达的总数。
    cacheStats.entries = Number(data.count ?? 0) + Number(data.any_cache_count ?? 0);
    cacheStats.size = formatCacheSize(Number(data.total_bytes_estimate ?? 0));
    cacheState.value = 'ok';
  } catch {
    cacheState.value = 'failed';
  }
}

/**
 * 清空缓存。二次确认的文案口径沿用 ServicePanel 的版本，但补上「清掉的是什么」：
 * 这是**服务端响应缓存**（设备状态、网络信息的只读快照），清掉只会让下一次读接口重新取数，
 * 不动任何配置与业务数据。不写清楚的话，用户会把它和监控页的「清理数据」当成同一件事而不敢点。
 */
function clearCache() {
  dialog.warning({
    title: '清空缓存',
    content:
      '将清空服务端响应缓存（设备状态、网络信息等接口的只读快照）。不影响任何配置与数据，' +
      '清掉后下一次读接口会重新向设备取数。确定继续？',
    positiveText: '清空',
    negativeText: '取消',
    onPositiveClick: async () => {
      cacheLoading.value = true;
      try {
        const { data } = await api.post('/api/cache/clear');
        if (!data) return; // 取消（面板已卸载）
        message.success('缓存已清空');
        loadCacheStats();
      } catch {
        message.error('清空失败');
      } finally {
        cacheLoading.value = false;
      }
    },
  });
}

/**
 * 按 glob 模式失效缓存。
 *
 * `pattern` 是 **glob 不是正则**，而且 core 侧「缺省或非字符串会回落 `*`，等同 clear」
 * （见 docs/UFI-AXIS-Core-API-Reference.md「缓存管理 /api/cache」）—— 所以这里保持
 * 「空串不发请求」的前置判断，别把它改成把空串当通配提交上去。
 * 另外无论删掉几条，core 都回 success 且**不含删除条数**，模式写错不会有提示；
 * 因此下面照旧回读一次 stats，让用户自己看到条目数有没有变。
 */
async function invalidateCache() {
  if (!cachePattern.value) return;
  cacheLoading.value = true;
  try {
    const { data } = await api.post('/api/cache/invalidate', { pattern: cachePattern.value });
    if (!data) return; // 取消（面板已卸载）
    message.success('缓存已按模式清除');
    loadCacheStats();
  } catch {
    message.error('清除失败');
  } finally {
    cacheLoading.value = false;
  }
}

/** 字节数 → 可读大小。随「缓存管理」卡一并从 ServicePanel 搬来，实现逐字未改。 */
function formatCacheSize(bytes: number): string {
  if (!bytes || bytes <= 0) return '0 B';
  const units = ['B', 'KB', 'MB', 'GB'];
  const i = Math.min(units.length - 1, Math.floor(Math.log(bytes) / Math.log(1024)));
  return (bytes / Math.pow(1024, i)).toFixed(i > 0 ? 1 : 0) + ' ' + units[i];
}

onMounted(() => {
  loadQosConfig();
  loadQosStatus();
  loadCacheStats();
});
</script>

<style scoped>
/* .settings-panel 栅格与断点、.config-grid/.config-item/.config-label/.switch-item 版式、
   .section-subtitle、.card-actions 均已统一到 src/styles/main.css（全局各一份） */

/* ── QoS status grid ── */
.status-grid {
  display: flex;
  flex-direction: column;
}

/* ── 缓存管理 ── */
/* 「按模式清除」那一行的输入框 + 按钮并排。随「缓存管理」卡从 ServicePanel 搬来
   （那边原有注释写的是「日志保留天数」，是更早一次改动的残留，已按实际用途更正）。 */
.inline-group {
  display: flex;
  gap: 8px;
  align-items: center;
}
/* 输入框占满剩余宽度（原来是行内 style="flex: 1"，收进类） */
.inline-group .grow {
  flex: 1;
}

/* 卡内分隔线：两条原本各写一份 `style="margin: 10px 0"`，收进一个类 */
.block-divider {
  margin: 10px 0;
}

/* 三态文案（2026-10-21）：失败态必须能点到「重试」，所以是 flex 行而不是一句灰字 */
.load-failed {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  font-size: 12px;
  color: var(--error);
}
.load-hint {
  font-size: 12px;
  color: var(--text-muted);
}

/* ── Text helpers ── */
.text-muted {
  color: var(--text-muted);
  font-size: 13px;
}
</style>
