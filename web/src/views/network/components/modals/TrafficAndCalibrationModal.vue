<template>
  <n-modal
    v-model:show="show"
    preset="card"
    title="流量与校准"
    class="modal-fit"
    style="--modal-w: 600px; --modal-h: 720px"
  >
    <!--
      两栏用 n-tabs（2026-10-22 合并「流量限额」与「流量校准」两个入口后新增）。

      为什么合并：两者改的是**同一套统计**（设备侧已用流量 / 已用时长）—— 限额决定"到多少提醒/断网"，
      校准把计数器手动改成某个值。原先两个瓦片 + 两个弹窗，用户要在两条路径之间来回确认
      "现在算的是多少"，而且两个弹窗都是模态，实际也开不了两个。合成一个弹窗、两栏并排后，
      "限额定多少"与"现在用了多少/改成多少"在同一块浮层里对照。

      但**保存语义仍分成两个动作**，所以底部按钮跟着当前栏走：
        · 限额栏 = 「保存」：写配置（`POST /api/device/data-limit`），写完 600ms 回读；
        · 校准栏 = 「执行校准」：立刻改设备计数器（`POST /api/device/flow-calibration`），
          成功后重拉当前用量。
      这正是原先坚持"不要共用一个 footer"的那条判断（两种语义混在一个按钮上必然误导），
      合并入口不等于合并动作。

      display-directive="show"：默认的 "if" 会在切走时销毁面板，用户填到一半切去看另一栏、
      再切回来就丢了输入。两栏都常驻，切换只切显隐。
    -->
    <n-tabs v-model:value="tab" type="segment" size="small" class="tc-tabs">
      <!-- ── 栏 1：限额 ── -->
      <n-tab-pane name="limit" tab="限额" display-directive="show">
        <n-spin :show="limitLoading">
          <ToggleRow
            label="流量限额"
            description="启用后，达到流量限额时将收到提醒或自动断网"
            :model-value="limitForm.enabled"
            @update:model-value="(v: boolean) => (limitForm.enabled = v)"
          />
          <div class="form-grid">
            <div class="form-item">
              <label>限额大小</label>
              <n-input-number v-model:value="limitForm.limit_size" :min="1" size="small" />
            </div>
            <div class="form-item">
              <label>限额单位</label>
              <n-select
                v-model:value="limitForm.limit_unit"
                :options="[
                  { label: 'MB', value: 'MB' },
                  { label: 'GB', value: 'GB' },
                  { label: 'TB', value: 'TB' },
                ]"
                size="small"
              />
            </div>
            <div class="form-item">
              <label>提醒百分比</label>
              <n-input-number v-model:value="limitForm.alert_percent" :min="1" :max="100" size="small" />
            </div>
            <div class="form-item">
              <label>自动清除</label>
              <!-- ui-baseline-allow: 弹窗行内表单的一个字段，整行 ToggleRow 会把两列表单撑成一行一项 -->
              <n-switch v-model:value="limitForm.auto_clear" />
            </div>
            <div class="form-item">
              <label>清除日期</label>
              <n-input-number v-model:value="limitForm.clear_date" :min="1" :max="31" size="small" />
            </div>
            <div class="form-item">
              <label>到达阈值关闭移动数据</label>
              <!-- ui-baseline-allow: 同上，与相邻的数值字段并排，属于弹窗行内表单 -->
              <n-switch v-model:value="limitForm.auto_off" />
            </div>
            <div v-if="limitForm.auto_off" class="form-item">
              <label>清零后自动重新打开</label>
              <!-- ui-baseline-allow: 同上，且它是条件出现的子项，必须紧跟父开关的版式 -->
              <n-switch v-model:value="limitForm.auto_off_restore" />
            </div>
          </div>
          <div v-if="limitForm.auto_off" class="form-hint">
            达到提醒百分比后先发邮件（需在邮件设置里勾选「流量预警」场景），发送成功 1 分钟后关闭移动数据；
            邮件发送失败则不会关闭网络。
            <template v-if="limitForm.auto_off_triggered">本计费周期已触发过，用量清零前不会再次关闭。</template>
          </div>

          <div class="monthly-stats">
            <InfoRow
              label="限额总量"
              :value="limitForm.limit_bytes > 0 ? formatBytes(limitForm.limit_bytes) : '未设置限额'"
            />
            <InfoRow label="本月已用" :value="formatBytes(limitForm.used_bytes)" />
            <InfoRow label="剩余" :value="limitForm.limit_bytes > 0 ? formatBytes(remainingBytes) : '--'" />
            <InfoRow label="本月已用（下行）" :value="formatBytes(limitForm.monthly_rx_bytes)" />
            <InfoRow label="本月已用（上行）" :value="formatBytes(limitForm.monthly_tx_bytes)" />
            <!-- 未启用限额时百分比没有意义，不显示进度条 -->
            <div v-if="limitForm.enabled" class="limit-progress">
              <n-progress
                v-if="usedPercent !== null"
                type="line"
                :percentage="usedPercent"
                :height="8"
                :status="usedPercent >= 100 ? 'error' : usedPercent >= 80 ? 'warning' : 'success'"
              />
              <span v-else class="limit-progress-hint">未设置限额</span>
            </div>
          </div>
        </n-spin>
      </n-tab-pane>

      <!-- ── 栏 2：校准 ── -->
      <n-tab-pane name="calib" tab="校准" display-directive="show">
        <n-spin :show="usageLoading">
          <!--
            能力门禁（2026-10-22 从瓦片上移到本栏）：设备明确未声明 `Capability.TRAFFIC_LIMIT` 时，
            校准不可提交。**不**把整颗入口瓦片置灰 —— 限额栏读配置、看用量仍然有意义，
            原先置灰的也只是「流量校准」那一格，不是「流量限额」。
            ⚠ 语义存疑点照旧：core 的 `Capabilities.kt` 把 TRAFFIC_LIMIT 定义为覆盖
            `POST /api/device/data-limit`，`/api/device/flow-calibration` 并不在那份清单里；
            这里沿用原来的判断「设备连限额都设不了时，让它去校准用量统计没有意义」。
          -->
          <div v-if="calibrationUnsupported" class="tc-warn">{{ calibrationBlockedText }}</div>

          <p class="calib-intro">
            把设备统计的「已用流量 / 已用时长」手动改成下面填的值，用于修正与运营商账单之间的偏差。
          </p>

          <div class="calib-field">
            <label>校准对象</label>
            <n-radio-group v-model:value="calibForm.target" size="small">
              <n-radio value="data">已用流量</n-radio>
              <n-radio value="time">已用时长</n-radio>
            </n-radio-group>
          </div>

          <div class="calib-field">
            <label>校准值</label>
            <div class="calib-value-row">
              <n-input v-model:value="calibForm.value" :placeholder="valuePlaceholder" size="small" />
              <n-select
                v-if="calibForm.target === 'data'"
                v-model:value="calibForm.unit"
                :options="dataUnitOptions"
                size="small"
                class="calib-unit"
              />
              <span v-else class="calib-unit-text">分钟</span>
            </div>
            <!-- 按钮 disabled 时必须能看见「为什么不能提交」，否则就是点不动也不说原因 -->
            <div v-if="validationHint" class="calib-hint calib-hint-warn">{{ validationHint }}</div>
          </div>

          <div class="calib-hint">
            只能填整数。填 <strong>0</strong> 等于把该计数器清零。设备拒绝这个值时，core 会把原因原样带回来显示。
          </div>
          <!--
            把"界面单位"与"提交单位"的换算写在用户看得见的地方。
            这一条是 2026-10-14 改出来的：最初按 core 文档示例的字面值原样提交，而那与
            app 的参考实现相反，会导致"填 1024 MB 实际只校准了 1 KB"。
          -->
          <div class="calib-hint">
            提交给设备的是 <strong>{{ wirePreview }}</strong
            >（设备计数器本身就以字节 / 秒计）。
          </div>

          <!--
            校准前的现值。放在这里有两个用处：① 用户能先看清"现在是多少"再决定改成多少；
            ② 校准成功后本栏会重新拉一次 `/api/device/traffic-limit` 并刷新这两行 ——
            校准改的就是这两个数，界面必须当场跟着变（core 成功时也会失效该缓存）。
          -->
          <div class="calib-current">
            <InfoRow label="当前已用流量" :value="usedBytesText" />
            <InfoRow label="当前已用时长" :value="usedTimeText" />
            <div v-if="usageFailed" class="calib-hint calib-hint-warn">
              读取当前用量失败，
              <n-button size="tiny" text type="primary" @click="loadUsage">重试</n-button>
            </div>
          </div>
        </n-spin>
      </n-tab-pane>
    </n-tabs>

    <!--
      底部按钮跟着当前栏走（见模板顶部说明）：两栏是两种语义的动作，不共用一个按钮。
      「关闭」两栏都在 —— 它只关这个浮层，不改任何设备状态。
    -->
    <template #footer>
      <div class="modal-footer">
        <n-button size="small" @click="show = false">关闭</n-button>
        <n-button v-if="tab === 'limit'" size="small" type="primary" :loading="limitSaving" @click="saveLimit">
          保存
        </n-button>
        <n-button
          v-else
          size="small"
          type="primary"
          :loading="calibSaving"
          :disabled="!canCalibrate"
          @click="submitCalibration"
        >
          执行校准
        </n-button>
      </div>
    </template>
  </n-modal>
</template>

<script setup lang="ts">
/**
 * 流量与校准弹窗（2026-10-22 由 `src/components/modals/TrafficLimitModal.vue` 与
 * 本目录原 `FlowCalibrationModal.vue` 合并而来；后者已在本次合并中删除，其核实过程
 * 与单位换算的完整理由随代码搬到下方 [wireValue] 的注释里）。
 *
 * ──── 合并后的边界 ────
 * · 入口只有一个（`NetworkSettingsCard` 的「流量与校准」瓦片），弹窗只有一个，栏内分「限额 / 校准」；
 * · **两个动作的语义与实现逐字保留**：限额仍 `POST /api/device/data-limit` + 600ms 回读
 *   `/api/device/traffic-limit`；校准仍 `POST /api/device/flow-calibration` + 成功后重拉用量。
 *   单位换算（MB/GB→字节、分钟→秒）与安全整数上限一并搬来，理由见下方 [wireValue]。
 * · 当前位置：它只被 `NetworkView` 用，所以住 `views/network/components/modals/`，
 *   不进共享目录 —— 共享目录的规则是"跨页复用"，不是"弹窗一律放那儿"。
 *   （仪表盘用的是它自己那份 `views/dashboard/components/modals/TrafficLimitModal.vue`，
 *   字段与 props 都不同，本次未动。）
 *
 * ──── 为什么限额逻辑是复制而不是 import 旧弹窗 ────
 * 旧的两个组件各自把 `n-modal` 作为根节点，"只取表单"没法复用；而合并后本文件就是唯一实现，
 * 再抽一层表单组件需要新增一个文件（表单只此一处消费，不值得多一层）。所以这里保留原逻辑原样。
 */
import { ref, reactive, computed, watch } from 'vue';
import { useMessage } from 'naive-ui';
import { useCancellableApi } from '@/composables/useCancellableApi';
import { formatBytes, formatUptime } from '@/composables/utils';
import { Endpoints } from '@/api/contract';
import InfoRow from '@/components/InfoRow.vue';
import ToggleRow from '@/components/ToggleRow.vue';

const props = withDefaults(
  defineProps<{
    show: boolean;
    /** 校准门禁：设备明确未声明 `Capability.TRAFFIC_LIMIT`（判据见 NetworkView / capabilities store） */
    calibrationUnsupported?: boolean;
    /** 门禁原因文案（`caps.hintOf(Capability.TRAFFIC_LIMIT)`） */
    calibrationHint?: string;
  }>(),
  { calibrationUnsupported: false, calibrationHint: '' }
);
const emit = defineEmits<{ 'update:show': [boolean] }>();

const show = computed({
  get: () => props.show,
  set: (v) => emit('update:show', v),
});

const message = useMessage();
const api = useCancellableApi();

/** 当前栏。每次打开都回到「限额」：入口名是「流量与校准」，默认落在主功能上。 */
const tab = ref('limit');

// ════════════════════ 栏 1：限额 ════════════════════

// 设备 goform 写入成功到「查询能读到新值」之间存在延迟，零延迟回读会拿到旧值。600ms 对齐 Android app。
const GOFORM_SETTLE_MS = 600;
const settleAfterWrite = () => new Promise((resolve) => setTimeout(resolve, GOFORM_SETTLE_MS));

const limitLoading = ref(false);
const limitSaving = ref(false);
const limitForm = reactive({
  enabled: false,
  limit_size: 10,
  limit_unit: 'GB',
  alert_percent: 80,
  auto_clear: false,
  clear_date: 1,
  limit_bytes: 0,
  used_bytes: 0,
  monthly_rx_bytes: 0,
  monthly_tx_bytes: 0,
  // core 自制的到达阈值自动关网（响应里的 auto_off 块，不是设备字段）
  auto_off: false,
  auto_off_restore: false,
  auto_off_triggered: false,
});

const remainingBytes = computed(() => Math.max(0, limitForm.limit_bytes - limitForm.used_bytes));

// null = 未设置限额（limit_bytes 为 0），此时百分比无意义
const usedPercent = computed<number | null>(() => {
  if (limitForm.limit_bytes <= 0) return null;
  const pct = (limitForm.used_bytes / limitForm.limit_bytes) * 100;
  return Math.round(Math.min(100, Math.max(0, pct)) * 10) / 10;
});

async function loadLimit() {
  limitLoading.value = true;
  try {
    const { data } = await api.get('/api/device/traffic-limit');
    limitForm.enabled = !!data.enabled;
    limitForm.limit_size = Number(data.limit_value) || 10;
    limitForm.limit_unit = data.limit_unit_display || 'GB';
    limitForm.alert_percent = Number(data.alert_percent) || 80;
    limitForm.auto_clear = !!data.auto_clear;
    limitForm.clear_date = Number(data.clear_date) || 1;
    limitForm.limit_bytes = Number(data.limit_bytes) || 0;
    limitForm.used_bytes = Number(data.used_bytes) || 0;
    limitForm.monthly_rx_bytes = Number(data.monthly_rx_bytes) || 0;
    limitForm.monthly_tx_bytes = Number(data.monthly_tx_bytes) || 0;
    limitForm.auto_off = !!data.auto_off?.enabled;
    limitForm.auto_off_restore = !!data.auto_off?.restore_on_reset;
    limitForm.auto_off_triggered = !!data.auto_off?.triggered;
  } catch {
    /* 静默 */
  } finally {
    limitLoading.value = false;
  }
}

async function saveLimit() {
  limitSaving.value = true;
  let posted = false;
  try {
    await api.post('/api/device/data-limit', {
      enabled: limitForm.enabled,
      // 结构化下发：数值 + 单位。设备侧的 "470_1024" 复合串由 core 拼（core 2.8），
      // 前端不再关心乘数映射。
      limit_value: limitForm.limit_size,
      limit_unit: limitForm.limit_unit,
      alert_percent: limitForm.alert_percent,
      auto_clear: limitForm.auto_clear,
      clear_date: limitForm.clear_date,
      // core 自制：达到 alert_percent 后先发邮件，发信成功 1 分钟后关闭移动数据
      auto_off_enabled: limitForm.auto_off,
      auto_off_restore: limitForm.auto_off_restore,
    });
    posted = true;
    message.success('流量限额已保存');
  } catch {
    message.error('保存失败');
  } finally {
    if (posted) await settleAfterWrite();
    await loadLimit();
    limitSaving.value = false;
  }
}

// ════════════════════ 栏 2：校准 ════════════════════

const usageLoading = ref(false);
/** 现值读取失败：显示 `--` 会让「没读到」和「真是 0」混在一起，所以单独给失败态与重试 */
const usageFailed = ref(false);
const calibSaving = ref(false);
const calibForm = reactive({
  /** `'data'` = 已用流量 / `'time'` = 已用时长，与 core 的 `target` 取值域一致 */
  target: 'data' as 'data' | 'time',
  /**
   * 用户输入的数值，**始终是字符串**。
   *
   * 界面上的单位与提交给设备的单位不同（见 [wireValue]），所以这一栏存的是
   * **界面单位下的整数**，不是要发出去的值。
   */
  value: '',
  /** `data` 维度的界面单位。取值与 app 参考实现的下拉一致（它只给 MB / GB）。 */
  unit: 'MB' as 'MB' | 'GB',
});

const dataUnitOptions = [
  { label: 'MB', value: 'MB' },
  { label: 'GB', value: 'GB' },
];

/** 当前设备统计值（校准前先给用户看，校准后重新拉一次刷新它） */
const usage = reactive({ usedBytes: null as number | null, monthlyTime: null as number | null });

const valuePlaceholder = computed(() => (calibForm.target === 'data' ? '例如 1024' : '例如 60'));

const usedBytesText = computed(() => (usage.usedBytes === null ? '--' : formatBytes(usage.usedBytes)));
// monthly_time 是"本月在线时长（秒）"（API 参考 1298 行 + app 侧同样按 3600 折算），
// 所以这里用 formatUptime（它收秒）
const usedTimeText = computed(() => (usage.monthlyTime === null ? '--' : formatUptime(usage.monthlyTime)));

/**
 * 拉当前用量。
 *
 * 路径写成裸字面量而不是 `Endpoints`：`/api/device/traffic-limit` 从来不在契约的那组里
 * （`Endpoints.device` 只登记了 capabilities / qos / flowCalibration）。
 * **不**为了这一处去动契约文件。
 */
async function loadUsage() {
  usageLoading.value = true;
  usageFailed.value = false;
  try {
    const { data } = await api.get('/api/device/traffic-limit');
    if (!data) return; // 请求被取消（组件已卸载）：保持原值，不写 null 冒充"读不到"
    // 缺值就是缺值：原来 `Number(x ?? 0) || 0` 会把「字段没回来」显示成 `0 B`，
    // 与「真的是 0」混在一起。非有限数同样落回 null → 显示 `--`。
    const bytes = Number(data.used_bytes);
    const seconds = Number(data.monthly_time);
    usage.usedBytes = data.used_bytes != null && Number.isFinite(bytes) ? bytes : null;
    usage.monthlyTime = data.monthly_time != null && Number.isFinite(seconds) ? seconds : null;
  } catch {
    // 读取失败不能静默成空值：给失败态 + 重试
    usageFailed.value = true;
    usage.usedBytes = null;
    usage.monthlyTime = null;
  } finally {
    usageLoading.value = false;
  }
}

/**
 * 提交给 core 的 `value`：**换算到设备计数器的单位**（字节 / 秒）。
 *
 * ──── 核实过程（2026-10-14 原 `FlowCalibrationModal` 文件头，随合并搬到此处）────
 * 契约只要求 `value` 是**字符串**（`Endpoints.device.flowCalibration` 的 KDoc：
 * 设备侧按文本收）。单位在 core 里**不做任何换算**，逐处读过：
 *   · `DeviceRoutes.kt` 的 `post("/flow-calibration")`：`target` 缺省 `"data"`、
 *     `value` 缺省 `"0"`，原样交给 `deviceHub.network.calibrateFlow(target, value)`；
 *   · `GoformNetworkClient.calibrateFlow`：KDoc 只写「value 校准成多少（纯数字字符串）」，
 *     实现是 `writer.writeChecked(SettingKey.FLOW_CALIBRATION, mapOf("target" to target,
 *     "value" to value))` —— 没有任何单位换算；
 *   · `ZteGoformProfile` 的 `WriteSpec(command = "FLOW_CALIBRATION_MANUAL")`：`encode` 只把
 *     value 放进 `data` 或 `time`（另一个补 `"0"`），`validate` 只校验 `^[0-9]+$`。
 * 所以单位就是**设备侧那两个字段的单位**，core 不表态。两条旁证**互相矛盾**：
 *   · core 文档示例 `{ "target": "data", "value": "1024" }` 与 profile 测试的
 *     `value = "1024"` / `value = "60"`，读作 MB / 分钟更自然（按字节该是十位数）；
 *   · app 侧参考实现 `TrafficManagementScreen.kt` 提交前把 MB/GB 换算成**字节**
 *     （`parseToBytes(calibrateValue, calibrateUnit).toString()`）。
 *
 * ★ 裁决：**按字节 / 秒收**，界面做换算。三条理由，第一条是决定性的：
 *  ① app 是**在真机上跑得通的那个客户端**，它的行为是事实标准；core 文档里的示例值
 *     只是文档作者随手写的，profile 测试那个 `"1024"` 测的是 encode 编码而不是单位；
 *  ② 校准的语义是"把设备的计数器改成这个数"，那么单位必然与**计数器本身**一致 ——
 *     设备的计数器是 `monthly_*_bytes`（字节）与 `monthly_time`（API 参考写「秒」，
 *     本栏那两行现值也是按秒用 `formatUptime` 渲染的）；
 *  ③ 按"原值提交"实现的后果是**静默错 1048576 倍**：用户填 1024 MB 实际只校准了 1 KB，
 *     而界面上会显示"已校准为 1024 MB"。错了还看不出来，比多一处换算危险得多。
 * 所以：`data` 走 MB/GB 下拉并换算成字节，`time` 固定按分钟换算成秒。
 * 与 contract 里"不要自己把 kbps 换算成 Mbps"那条口径不冲突 —— 那条说的是 core
 * **已经**换算好的展示文案，这里 core 恰恰不换算、单位由设备定，必须由客户端补上。
 */
function wireValue(): string {
  const n = Number(calibForm.value.trim());
  if (!Number.isFinite(n)) return '0';
  if (calibForm.target === 'time') return String(n * 60); // 分钟 → 秒
  return String(calibForm.unit === 'GB' ? n * 1073741824 : n * 1048576); // MB/GB → 字节
}

/** 界面提示用：把要发出去的值回显给用户看，免得"填 MB、发字节"变成一件看不见的事。 */
const wirePreview = computed(() => {
  if (!/^\d+$/.test(calibForm.value.trim())) return '--';
  const v = wireValue();
  return calibForm.target === 'time' ? `${v} 秒` : `${v} 字节`;
});

/**
 * 换算后数值的上限。
 *
 * `value` 虽然是按字符串提交的，但换算这一步（MB/GB → 字节）是 JS 的 Number 乘法：
 * 超过 2^53−1 后乘积已经是浮点近似值，`String()` 出来的字符串从个位起就不准了 ——
 * 用户看到"校准成功"，设备计数器却被改成一个错的数。
 * 同时它也是一个体感上限：约 8 PB / 1.7 亿分钟，超过它必然是单位选错了。
 */
const MAX_WIRE_VALUE = Number.MAX_SAFE_INTEGER;

/**
 * 输入不可用的原因（空字符串 = 没有需要提示的问题）。
 *
 * 只把 `canSubmit` 接在 disabled 上会让用户看到"按钮灰着，但不知道哪里不对"，
 * 所以原因就地显示在输入框下方；空输入不提示（那是还没填，不是填错）。
 */
const inputValid = computed(() => /^\d+$/.test(calibForm.value.trim()));

/** 换算后超过安全整数：不是"设备会拒绝"，而是发出去的字符串本身已经不准了 */
const tooLarge = computed(() => inputValid.value && Number(wireValue()) > MAX_WIRE_VALUE);

const validationHint = computed(() => {
  if (!calibForm.value.trim()) return '';
  if (!inputValid.value) return '只能填非负整数：不要带小数、负号或单位（单位用右侧下拉选）';
  if (tooLarge.value) return `数值过大：换算成设备单位后不能超过 ${MAX_WIRE_VALUE}`;
  return '';
});

const canSubmit = computed(() => !calibSaving.value && inputValid.value && !tooLarge.value);

/** 设备明确未声明流量限额能力时不能提交校准（原因就地显示在本栏顶部） */
const calibrationBlockedText = computed(() => `${props.calibrationHint || '该机型未声明流量限额能力'}，校准不可用。`);
const canCalibrate = computed(() => canSubmit.value && !props.calibrationUnsupported);

/** 成功提示里说的"界面单位"，与 [wireValue] 的换算方向配套。 */
const unitHint = computed(() => (calibForm.target === 'data' ? calibForm.unit : '分钟'));

async function submitCalibration() {
  // 门禁状态在按钮上已置灰，这里再挡一次：能力集可能在弹窗打开后被重新拉取
  if (props.calibrationUnsupported) return;
  const value = wireValue();
  // 本地先拦一道：core 的 WriteSpec 用 `^[0-9]+$` 校验，非法值要白跑一趟才拿到 400。
  // 真正的拒绝仍在 core（越界等只有它知道），这里只挡格式。
  if (!/^\d+$/.test(value)) {
    message.warning('校准值只能填整数');
    return;
  }
  calibSaving.value = true;
  try {
    const { data } = await api.post(Endpoints.device.flowCalibration, { target: calibForm.target, value });
    if (!data) return; // 取消（卸载）：不弹任何 toast
    if (data.success === false) {
      // HTTP 200 + success:false：设备收下了命令但明确回失败。
      // 值域非法那一态不走这里（core 的 respondRejected 会回 400 的信封，见 catch）。
      message.error('校准失败：设备未接受这个值');
      return;
    }
    message.success(
      `已把${calibForm.target === 'data' ? '已用流量' : '已用时长'}校准为 ${calibForm.value.trim()} ${unitHint.value}`
    );
    // 校准改的就是用量计数器 ⇒ 立刻重拉，让上面两行当场反映新值（core 也会失效该缓存）
    await loadUsage();
  } catch (e: any) {
    // 400 = core 的 respondRejected（`{success:false, error, message, code}` 信封），
    // 中文原因原样显示，不吞成"校准失败"
    message.error(e?.response?.data?.message || e?.response?.data?.error || '校准失败');
  } finally {
    calibSaving.value = false;
  }
}

/**
 * 打开时重置并重拉。
 *
 * 与原两个弹窗一致：每次打开都重新读一次（限额配置 / 当前用量），校准输入清空 ——
 * 上一次填了一半的值不该被当成这次的输入。
 * 两栏都常驻（见模板说明），所以一次打开要把两栏的数据都准备好。
 */
watch(
  () => props.show,
  (v) => {
    if (!v) return;
    tab.value = 'limit';
    calibForm.value = '';
    loadLimit();
    loadUsage();
  },
  { immediate: true }
);
</script>

<style scoped>
/* 页签条与面板之间 naive 没有内建间距（`tabs-pane-wrapper` 只负责溢出与动画），
   补一档免得页签紧贴表单第一行。 */
.tc-tabs :deep(.n-tabs-nav) {
  margin-bottom: var(--space-3);
}

/* ═════════ 栏 1：限额（原 TrafficLimitModal 的 scoped 规则，取值换成同名令牌） ═════════ */
.form-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(200px, 1fr));
  gap: var(--space-3);
  margin: var(--space-2) 0 var(--space-3);
}
.form-item {
  display: flex;
  flex-direction: column;
  gap: var(--space-1);
}
.form-item label {
  font-size: var(--font-sm);
  color: var(--text-secondary);
  font-weight: 500;
}
.form-hint {
  font-size: var(--font-sm);
  color: var(--text-muted);
  line-height: 1.6;
  margin-bottom: var(--space-3);
}
.monthly-stats {
  padding-top: var(--space-3);
  border-top: 1px solid var(--border-subtle);
}
.limit-progress {
  margin-top: var(--space-2);
}
.limit-progress-hint {
  font-size: var(--font-sm);
  color: var(--text-muted);
}

/* ═════════ 栏 2：校准（原 FlowCalibrationModal 的 scoped 规则） ═════════ */
.calib-intro {
  margin: 0 0 var(--space-3);
  font-size: var(--font-sm);
  color: var(--text-muted);
  line-height: 1.6;
}
.calib-field {
  display: flex;
  flex-direction: column;
  gap: var(--space-1);
  margin-bottom: var(--space-3);
}
.calib-field label {
  font-size: var(--font-sm);
  color: var(--text-secondary);
  font-weight: 500;
}
/* 数值 + 单位并排：单位是这一栏的一部分，不另起一行（否则"填 1024"与"单位 MB"
   在视觉上会被读成两件不相干的事）。 */
.calib-value-row {
  display: flex;
  align-items: center;
  gap: var(--space-2);
}
.calib-value-row .n-input {
  flex: 1 1 auto;
  min-width: 0;
}
.calib-unit {
  flex: 0 0 auto;
  width: 84px;
}
.calib-unit-text {
  flex: 0 0 auto;
  width: 84px;
  font-size: var(--font-sm);
  color: var(--text-secondary);
}
.calib-hint {
  font-size: var(--font-sm);
  color: var(--text-muted);
  line-height: 1.6;
}
/* 两条 calib-hint 挨着时要留一点缝，否则会被读成同一段话 */
.calib-hint + .calib-hint {
  margin-top: var(--space-1);
}
/* 输入不可用的原因：与说明同排，但用 warning 色把"这是问题"和"这是解释"分开 */
.calib-hint-warn {
  color: var(--warning);
}
.calib-current {
  margin-top: var(--space-3);
  padding-top: var(--space-3);
  border-top: 1px solid var(--border-subtle);
}

/* ═════════ 门禁提示（本栏顶部，能力不支持时） ═════════ */
.tc-warn {
  margin-bottom: var(--space-3);
  font-size: var(--font-sm);
  color: var(--warning);
  line-height: 1.6;
}

/* 窄屏（≤768px，全站唯一移动阈值）：限额两列表单折单列。
   原先 200px 的最小列宽在 360px 视口下本来也会自然变成 1 列，这条只是把
   "刚好挤成两列的半窄窗"一并折掉，与本页其他弹窗同口径。 */
@media (max-width: 768px) {
  .form-grid {
    grid-template-columns: 1fr;
  }
}
</style>
