<template>
  <div class="device-grid">
    <!--
      #/device 单页布局（2026-10-14 第二次调整，用户裁决）：
        行 1 = SIM 卡      ｜ 电池
        行 2 = 连接统计     ｜ 温度传感器（摘要）
        行 3 = 承载 QoS（通栏，只读）
        行 4 = 设备控制（通栏，六项**平铺**在页面上）
      本轮变化：
        · 行 3 新增「承载 QoS」卡（2026-10-14，见卡片内注释：为什么是通栏、为什么不做轮询）；
        · 设备控制里的 LED / 性能 / Samba / USB / 定时重启 / FOTA **从弹窗搬回页面**（用户要求）；
        · 「重启设备 / 恢复出厂」移到设置页（设置 › 设备）；
        · 「流量限额 / LAN·DHCP」移到 #/network；「后台密码 / 原始数据 / 关机」移到设置页。
      温度传感器仍保留入口 + 弹窗：传感器条目数不定，摊在页面上会把整页高度拉得不可预期，
      而它平时只需要一个"最高多少度"的摘要。
    -->
    <!-- 行 1 左：SIM 卡 -->
    <!-- summary 未到 / summary 已到但 device_info 为 null（core 侧 goform 超时会静默返回 null）/
         请求失败，三种状态原先在 UI 上长得完全一样（一片空白），用户无法判断是在加载还是读取失败。
         现在：加载中交给 GridCard 的骨架，失败态带重试，空值才落到「--」 -->
    <GridCard title="SIM 卡" :loading="simCardState === 'loading'">
      <template #extra>
        <n-button size="tiny" quaternary :loading="summaryLoading" @click="refreshSummary">刷新</n-button>
      </template>
      <n-empty v-if="simCardState === 'failed'" size="small" description="设备信息读取失败，请刷新重试">
        <template #extra>
          <n-button size="tiny" :loading="summaryLoading" @click="refreshSummary">重试</n-button>
        </template>
      </n-empty>
      <div v-else>
        <InfoRow label="SIM 状态" :value="simState" />
        <InfoRow label="本机号码" :value="deviceText('identity.msisdn')" />
        <InfoRow label="IMEI" :value="deviceText('identity.imei')" />
        <InfoRow label="IMSI" :value="deviceText('identity.imsi')" />
        <InfoRow label="ICCID" :value="deviceText('identity.iccid')" />
      </div>
    </GridCard>

    <!-- 行 1 右：电池 -->
    <GridCard title="电池" :loading="batteryCardState === 'loading'">
      <template #extra>
        <n-button size="tiny" quaternary :loading="summaryLoading" @click="refreshSummary">刷新</n-button>
      </template>
      <!-- 与 SIM 卡同理：电量同样只来自 summary，加载中与读取失败必须能区分 -->
      <n-empty v-if="batteryCardState === 'failed'" size="small" description="设备信息读取失败，请刷新重试">
        <template #extra>
          <n-button size="tiny" :loading="summaryLoading" @click="refreshSummary">重试</n-button>
        </template>
      </n-empty>
      <div v-else>
        <InfoRow
          label="电量"
          :value="batteryInfo?.percent != null && batteryInfo.percent >= 0 ? `${batteryInfo.percent}%` : '--'"
        />
        <InfoRow label="状态" :value="batteryStatus" />
        <!-- 电源来源是英文枚举（AC / USB / Wireless / None），不是布尔：
             原先的 `plugged || '--'` 既把英文键名直接上屏，又把 `None`（没插电这个事实）
             当成了有效值 —— 判空改成 `!= null`，取值走中文映射（见 batteryPlugged） -->
        <InfoRow label="电源" :value="batteryPlugged" />
        <!--
          2026-10-14 修复：温度/电压此前用**真值判断**（`batteryInfo?.temperature ? … : '--'`），
          这个写法**两头都错**：
            ① `0°C` / `0V` 是合法读数，会被误判成"没有数据"（0°C 在寒冷环境完全可能出现）；
            ② 更关键的是 core 对这两项的**哨兵值是 `-1.0`**（SystemCollector.getBatteryInfo：
               `temperature > 0` 取真值 → 否则 sysfs 兜底 → 再否则 `-1.0`）。真值判断把 `-1.0`
               当成"有值"，于是渲染成 `-1°C` / `-0.00V` —— 一个看着像故障的假读数。
          现在统一成 `!= null && >= 0`：哨兵被挡掉，0 正常显示。
          同一张卡的「电量」行本来就是这个口径（`percent != null && percent >= 0`），本次是对齐它。
        -->
        <InfoRow
          label="温度"
          :value="
            batteryInfo?.temperature != null && batteryInfo.temperature >= 0 ? `${batteryInfo.temperature}°C` : '--'
          "
        />
        <InfoRow
          label="电压"
          :value="
            batteryInfo?.voltage != null && batteryInfo.voltage >= 0 ? `${batteryInfo.voltage.toFixed(2)}V` : '--'
          "
        />
        <!--
          2026-10-14 新增：core 在 battery map 里带了一个 `supported` 字段
          （SystemCollector.getBatteryInfo 第一行就填、刻意放在 try 之外 —— 任何一步读取失败它都还在），
          语义是「这个型号声明了 Capability.BATTERY 吗」。core 的 KDoc 明确写了客户端该怎么用：
          **读数照原样渲染，只额外提示可信度**（"抹成 -1%"的旧方案已被用户推翻，
          理由是红色的 -1% 比假的 50% 更像故障）。
          此前 web 完全没用这个字段 —— 于是 F50 这类无电池机型会一路显示系统恒报的 50%，
          用户没有任何线索知道这个数字不可信。
        -->
        <div v-if="batterySupported === false" class="battery-hint">可能无电池或检测不到</div>
      </div>
    </GridCard>

    <!-- 行 2 左：连接统计 -->
    <!-- 版式走全局 .stat-grid / .stat-cell（原先本文件自写了一套同名的 stat-row/stat-item），
         只把「等分五格的数字瓦片」这点差异留在 .conn-grid 里 -->
    <GridCard title="连接统计">
      <n-spin :show="connectionsLoading">
        <div class="stat-grid conn-grid">
          <div class="stat-cell">
            <span class="stat-label">TCP</span>
            <span class="stat-value">{{ connections.tcp ?? '--' }}</span>
          </div>
          <div class="stat-cell">
            <span class="stat-label">TCP6</span>
            <span class="stat-value">{{ connections.tcp6 ?? '--' }}</span>
          </div>
          <div class="stat-cell">
            <span class="stat-label">UDP</span>
            <span class="stat-value">{{ connections.udp ?? '--' }}</span>
          </div>
          <div class="stat-cell">
            <span class="stat-label">UDP6</span>
            <span class="stat-value">{{ connections.udp6 ?? '--' }}</span>
          </div>
          <div class="stat-cell">
            <span class="stat-label">Unix</span>
            <span class="stat-value">{{ connections.unix ?? '--' }}</span>
          </div>
        </div>
        <!-- 失败时保留上一次的读数（它们是"刚才的值"），但要说明这次没读到；
             全部为 `--` 时若不给这句，就分不出「读失败」与「设备真的没有连接」 -->
        <div v-if="connectionsFailed" class="client-empty">连接数读取失败，稍后自动重试</div>
      </n-spin>
    </GridCard>

    <!-- 行 2 右：温度传感器（摘要 + 入口） -->
    <GridCard title="温度传感器">
      <template #extra>
        <n-button size="tiny" quaternary @click="showThermal = true">查看详情</n-button>
      </template>
      <n-spin :show="thermalLoading">
        <div v-if="maxTempView.visible" class="thermal-summary">
          <div class="thermal-max">
            <span class="thermal-max-value" :style="{ color: maxTempView.color }">{{ maxTempView.text }}</span>
            <span class="thermal-max-label">最高温度</span>
          </div>
          <div class="thermal-count">共 {{ thermalZones.length }} 个传感器</div>
        </div>
        <!-- 失败与「真的没有传感器」是两件事：原先都渲染成一句「暂无数据」 -->
        <div v-else-if="thermalFailed" class="client-empty">
          温度读取失败
          <n-button size="tiny" quaternary @click="loadThermal">重试</n-button>
        </div>
        <!-- 首次读取期间不要先闪一句「没有传感器」 -->
        <div v-else-if="thermalLoading" class="client-empty">正在读取温度…</div>
        <div v-else class="client-empty">设备没有返回温度传感器</div>
      </n-spin>
    </GridCard>

    <!--
      行 3（通栏）：承载 QoS（EPS bearer QoS，2026-10-14 新增）。

      ## 为什么是通栏
      本页是两列栅格，而它是**奇数张**只读信息卡：半宽放在任何一行都会让那一行空出一角
      （栅格不会把后面的通栏卡塞进剩下的半格），观感就是"缺一块"。通栏 + 卡内三列排布
      既不留空洞，也不必打乱用户刚裁决过的 SIM/电池/连接统计/温度 四宫格顺序。

      ## 为什么不做 10s 轮询
      QoS 是低频量：core 侧 `CacheTTL.DEVICE_QOS` 5 分钟缓存，且它是"承载建立时协商出来的
      参数"，不是流量/温度那样的实时量。跟着本页的 10s 轮询走只会每 10s 打一次必然命中
      缓存的请求（热点流量白花）。所以：挂载时拉一次 + 卡片右上角的「刷新」手动拉。

      ## 为什么没有能力门禁
      QoS 与 `Capability.NETWORK_MODE` 不是同一个能力（前者是"读承载参数"，后者是
      "切换制式"），拿它门禁会误伤。AT 通道在很多机型上不可用是**预期状态**，
      core 对此回 200 + `available:false`，所以这张卡靠 `available` 字段表达"查不到"。
    -->
    <GridCard class="full-width" title="承载 QoS">
      <template #extra>
        <!-- 灰字说明而不是报错：AT 通道不可用是机型事实，不是故障（core 也刻意回 200） -->
        <span v-if="qosLoaded && !qos.available" class="qos-hint">本机型不支持查询</span>
        <!-- 一次都没读到过：三个 `—` 与「不支持查询」必须分得开，否则用户以为机型不行 -->
        <span v-else-if="qosFailed" class="qos-hint">读取失败，可点刷新重试</span>
        <n-button size="tiny" quaternary :loading="qosLoading" @click="loadQos">刷新</n-button>
      </template>
      <div class="qos-grid">
        <!-- 三个值一律走 qosText()：available=false 或缺字段都显示 `—`，绝不显示空串 -->
        <InfoRow label="QCI" :value="qosText(qos.qci)" />
        <InfoRow label="下行 AMBR" :value="qosText(qos.downlinkDisplay)" />
        <InfoRow label="上行 AMBR" :value="qosText(qos.uplinkDisplay)" />
      </div>
    </GridCard>

    <!--
      行 4（通栏）：设备控制 —— 六项**平铺**（2026-10-14 用户要求，不再收进弹窗）。
      写入逻辑与注释来自原 ControlTab（经 DeviceControlModal 中转），一字未改：
      那套「成功后乐观置位 → 等 600ms → 静默回读」的范式是这个页面里最容易抄错的部分。
    -->
    <GridCard class="full-width" title="设备控制" :loading="controlLoading">
      <!-- 读失败时六个开关会一起停在「关」—— 那是一个看着像真值的假状态。
           这里不替换开关（保留上一次读到的值），只说明可信度并给重试 -->
      <div v-if="controlFailed" class="client-empty">
        设备设置读取失败，开关状态可能不是最新的
        <n-button size="tiny" quaternary @click="loadControlSettings()">重试</n-button>
      </div>
      <div class="ctl-grid">
        <ToggleRow
          label="LED 指示灯"
          description="控制设备前面板 LED 指示灯的开关"
          :model-value="controlSettings.ledEnabled"
          :loading="toggleLoading.led"
          @update:model-value="(v: boolean) => postToggle('/api/device/led', v, 'led', 'ledEnabled')"
        />
        <!--
          能力门禁（2026-10-14）：性能模式 / Samba / FOTA 三项按设备声明的 `Capability` 门禁。

          ① 判据一律 `caps.unsupported(wire)`（只有"确实不在设备声明的集合里"才 true），
             **绝不用** `!caps.supports(wire)` —— 后者在"能力集还没拉到 / 那次请求失败"时
             同样为真，会把这一页所有开关集体变灰（见 stores/capabilities.ts 文件头）。
          ② 置灰必须给出原因：`n-tooltip` 包住整行，内容是 `caps.hintOf(wire)`
             （「该机型未声明性能模式能力」这类如实说明，而不是只灰不说）。
          ③ tooltip 的 trigger 直接就是 ToggleRow 的根节点（不额外套 span）：
             这样悬停行内任意位置（含标签/说明）都能出提示；套一层的话热区只剩那一层。
             行的分隔线不依赖 ToggleRow 自带的 :first-child/:last-child，改由 `.ctl-grid`
             统一接管（双列栅格里那两条不变量本来也不成立，见下方样式注释）。
        -->
        <n-tooltip :disabled="!performanceUnsupported" :title="performanceHint">
          <template #trigger>
            <ToggleRow
              label="性能模式"
              description="启用高性能模式，提升处理器运行频率"
              :model-value="controlSettings.performanceEnabled"
              :loading="toggleLoading.performance"
              :disabled="performanceUnsupported"
              @update:model-value="
                (v: boolean) => postToggle('/api/device/performance', v, 'performance', 'performanceEnabled')
              "
            />
          </template>
        </n-tooltip>
        <n-tooltip :disabled="!sambaUnsupported" :title="sambaHint">
          <template #trigger>
            <ToggleRow
              label="Samba 共享"
              description="启用 Samba 文件共享服务，允许局域网访问存储"
              :model-value="controlSettings.sambaEnabled"
              :loading="toggleLoading.samba"
              :disabled="sambaUnsupported"
              @update:model-value="(v: boolean) => postToggle('/api/device/samba', v, 'samba', 'sambaEnabled')"
            />
          </template>
        </n-tooltip>
        <ToggleRow
          label="USB 端口"
          description="对应设备 usb_port_switch，关闭后 USB 数据口停用"
          :model-value="controlSettings.usbPortEnabled"
          :loading="toggleLoading.usbPort"
          @update:model-value="(v: boolean) => postToggle('/api/device/debug', v, 'usbPort', 'usbPortEnabled')"
        />
        <ToggleRow
          label="定时重启"
          description="设置每日自动重启时间，保持系统稳定运行"
          :model-value="controlSettings.restartScheduleEnabled"
          :loading="toggleLoading.restartSchedule"
          @update:model-value="(v: boolean) => postRestartSchedule(v)"
        />
        <n-tooltip :disabled="!fotaUnsupported" :title="fotaHint">
          <template #trigger>
            <ToggleRow
              label="FOTA 自动检查更新"
              description="开启后设备按运营商策略自动检查并下载固件更新（UpgMode）"
              :model-value="controlSettings.fotaAutoUpdate"
              :loading="toggleLoading.fota"
              :disabled="fotaUnsupported"
              @update:model-value="(v: boolean) => setFotaAutoUpdate(v)"
            />
          </template>
        </n-tooltip>
      </div>
      <!-- 定时重启时间：只在开启时出现（原来那张独立卡的条件不变） -->
      <div v-if="controlSettings.restartScheduleEnabled" class="restart-time">
        <label>每日重启时间</label>
        <n-input
          v-model:value="controlSettings.restartScheduleTime"
          class="restart-input"
          placeholder="HH:MM"
          size="small"
          @blur="postRestartScheduleTime"
        />
        <span class="restart-hint">格式 HH:MM，失焦即保存</span>
      </div>
    </GridCard>

    <!--
      应用管理（2026-10-14）：原侧栏独立入口，按"侧栏太多太杂"的重排并入设备页 ——
      "这台设备上装了哪些包"本来就属于设备本体。
      用**一行入口**而不是瓦片格：本页只有这一个跳页入口，为它铺一张瓦片卡反而更乱。
      路由 `/apps` 照旧可直接打开（深链接与收藏不受影响）。
    -->
    <GridCard class="full-width" title="应用管理">
      <button class="app-entry" @click="router.push({ name: 'apps' })">
        <span class="app-entry-main">
          <span class="app-entry-title">已安装应用</span>
          <span class="app-entry-desc">查看 / 安装 / 卸载设备上的应用包</span>
        </span>
        <span class="app-entry-arrow">›</span>
      </button>
    </GridCard>

    <!-- 温度明细弹窗（本页唯一保留的弹窗；其余入口已按用户要求移出） -->
    <ThermalModal v-model:show="showThermal" />
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted } from 'vue';
import { useMessage } from 'naive-ui';
import { useRouter } from 'vue-router';
import { useInterval } from '@/composables/useRealtime';
import { useDashboardStore } from '@/stores/dashboard';
import { useCapabilitiesStore } from '@/stores/capabilities';
import { useCancellableApi } from '@/composables/useCancellableApi';
import { get } from '@/composables/utils';
import { Capability, Endpoints } from '@/api/contract';
import GridCard from '@/components/GridCard.vue';
import InfoRow from '@/components/InfoRow.vue';
import ToggleRow from '@/components/ToggleRow.vue';

import ThermalModal from './components/modals/ThermalModal.vue';
import { formatThermalTemp, normalizeThermalZones, thermalColor, type ThermalZone } from './useThermal';

const dashboardStore = useDashboardStore();
// 能力集在应用壳（DefaultLayout）里已经拉过一次；各页只读同一个 store，
// 自己再拉一份会得到可能不一致的第二份快照（见 stores/capabilities.ts 文件头）。
const caps = useCapabilitiesStore();
const router = useRouter();
const api = useCancellableApi();
const message = useMessage();

const showThermal = ref(false);

// ── SIM / 电池：都来自 dashboard summary ──
const summaryLoading = ref(false);
/** 最近一次 summary 补拉是否失败 —— 失败且 store 里也没有快照时，卡片必须说「读失败」而不是一直转圈。 */
const summaryFailed = ref(false);

async function refreshSummary() {
  summaryLoading.value = true;
  try {
    const { data } = await api.get('/api/dashboard/summary');
    dashboardStore.updateSummary(data);
    summaryFailed.value = false;
  } catch {
    // 不再全静默：布局层会继续周期刷新，但本页两张卡要能说明「这次没读到」
    summaryFailed.value = true;
  } finally {
    summaryLoading.value = false;
  }
}

const deviceInfo = computed(() => get(dashboardStore.summary, 'device_info', null));
const batteryInfo = computed(() => get(dashboardStore.summary, 'battery', null));

/**
 * SIM / 电池两张卡的三态判定。
 *
 * `loading` = summary 还没到（骨架）；`failed` = summary 拿不到，或 summary 到了但
 * 这一段是 null（core 侧 goform 超时会静默返回 null）；`ready` = 有数据可渲染。
 */
function cardState(info: unknown): 'loading' | 'failed' | 'ready' {
  if (!dashboardStore.summary) return summaryFailed.value ? 'failed' : 'loading';
  return info == null ? 'failed' : 'ready';
}
const simCardState = computed(() => cardState(deviceInfo.value));
const batteryCardState = computed(() => cardState(batteryInfo.value));

/**
 * core 如实转述的「本型号是否声明了电池能力」（`battery.supported`）。
 *
 * `undefined` = 老版本 core 没这个键 —— 此时**什么都不提示**，不拿默认值冒充真值
 * （与 app 侧 `thumbPrewarm` 那条约定同一个口径）。
 */
const batterySupported = computed(() => get(batteryInfo.value, 'supported', undefined));

const simState = computed(() => {
  const sim = deviceInfo.value?.sim;
  if (typeof sim === 'string') return sim;
  return sim?.sim_state || '--';
});

/**
 * goform 对「未写入」的字段返回空串而不是缺字段（例如 SIM 未写 MSISDN），
 * 而 get() 只在 undefined 时兜 fallback，空串会被原样渲染成一个空白格。
 * 这里统一把 null / undefined / 空串都显示为 `--`。
 */
function deviceText(path: string): string {
  const v = get(deviceInfo.value, path, '');
  return v === null || v === undefined || String(v).trim() === '' ? '--' : String(v);
}

// core SystemCollector.getBatteryInfo 返回 level/scale/percent/temperature/voltage/is_charging/plugged，
// 没有 status / technology 字段
const batteryStatus = computed(() => {
  const b = batteryInfo.value;
  if (!b || b.is_charging == null) return '--';
  return b.is_charging ? '充电中' : '放电中';
});

/**
 * 电源来源的中文映射。
 *
 * `plugged` 是 core 拼出来的**字符串枚举**（`AC` / `USB` / `Wireless` / `None`），
 * 不是布尔：所以不能用 `|| '--'` 判空（那样 `None` 会原样上屏英文，而缺值又会被
 * `false` 这类合法值糊掉）。判空统一 `!= null`，取值走这张表。
 */
const BATTERY_PLUGGED_LABELS: Record<string, string> = {
  AC: '交流电源',
  USB: 'USB 供电',
  Wireless: '无线充电',
  None: '未接通',
};
const batteryPlugged = computed(() => {
  const p = batteryInfo.value?.plugged;
  if (p == null || String(p).trim() === '') return '--';
  const key = String(p);
  return BATTERY_PLUGGED_LABELS[key] ?? key;
});

// ── 连接统计 ──
const connectionsLoading = ref(false);
/** 最近一次读取是否失败（读数保留，卡片上另给一句说明）。 */
const connectionsFailed = ref(false);
// core SystemCollector.getConnectionCounts 返回 tcp / tcp6 / udp / udp6 / unix，IPv6 两项之前没展示
const connections = reactive<Record<'tcp' | 'tcp6' | 'udp' | 'udp6' | 'unix', number | null>>({
  tcp: null,
  tcp6: null,
  udp: null,
  udp6: null,
  unix: null,
});
async function loadConnections() {
  connectionsLoading.value = true;
  try {
    const { data } = await api.get('/api/device/connections');
    connections.tcp = data.tcp ?? null;
    connections.tcp6 = data.tcp6 ?? null;
    connections.udp = data.udp ?? null;
    connections.udp6 = data.udp6 ?? null;
    connections.unix = data.unix ?? null;
    connectionsFailed.value = false;
  } catch {
    // 读数保留上一次的（10s 轮询里一次抖动不该清空整张卡），但要把失败标出来
    connectionsFailed.value = true;
  } finally {
    connectionsLoading.value = false;
  }
}

// ── 温度传感器：页面上只留摘要（最高温 + 传感器数），明细在弹窗里 ──
// 名称映射 / 缺值口径 / 三档颜色统一走 useThermal.ts（弹窗用同一份，避免两处显示不一致）
const thermalLoading = ref(false);
/** 最近一次读取是否失败（失败时保留上一次的摘要，但不再显示成「暂无数据」）。 */
const thermalFailed = ref(false);
const thermalZones = ref<ThermalZone[]>([]);

async function loadThermal() {
  thermalLoading.value = true;
  try {
    const { data } = await api.get('/api/device/thermal');
    thermalZones.value = normalizeThermalZones(data?.zones);
    thermalFailed.value = false;
  } catch {
    thermalFailed.value = true;
  } finally {
    thermalLoading.value = false;
  }
}

/** 最高温：只统计有有效读数的传感器；一个都没有时返回 null（页面显示空态）。 */
const maxTemp = computed<number | null>(() => {
  const valid = thermalZones.value.map((z) => z.temp).filter((t): t is number => t !== null);
  return valid.length ? Math.max(...valid) : null;
});

/** 最高温的展示形态：文案 / 颜色 / 是否渲染一起算好（模板里不必再从可空数字里二次判断）。 */
const maxTempView = computed(() => {
  const t = maxTemp.value;
  if (t === null) return { visible: false, text: '', color: '' };
  return { visible: true, text: formatThermalTemp(t), color: thermalColor(t) };
});

// ── 承载 QoS（EPS bearer QoS）──
//
// `GET /api/device/qos` → `{ available, cid, qci, downlink_kbps, uplink_kbps,
// downlink_display, uplink_display }`。
//
// 两个必须照做的点（contract.ts 的 Endpoints.device.qos 里也写了）：
//   ① **失败也是 HTTP 200**，只是 `available:false` + 空字段（AT 通道在很多机型上不可用是
//      预期状态）⇒ 这里据 `available` 显示 `—`，不按网络错误处理、不弹 toast；
//   ② `*_display` 是 core 已经换算好的文案（如 `500 Mbps`），**直接用**，
//      不在这里把 kbps 再换算一次 —— 两端各写一份换算必然分叉（一个显示 500 Mbps、
//      一个显示 500.0 Mbps）。
const qosLoading = ref(false);
/** 是否已经拿到过一次响应（用于区分"还没拉到"与"拉到了但查不到"，后者才显示灰字说明）。 */
const qosLoaded = ref(false);
/** 最近一次读取是否失败：一次都没读到过时，三个 `—` 不能被读成「机型不支持」。 */
const qosFailed = ref(false);
const qos = reactive({
  available: false,
  qci: '' as string | number,
  downlinkDisplay: '',
  uplinkDisplay: '',
});

async function loadQos() {
  qosLoading.value = true;
  try {
    const { data } = await api.get(Endpoints.device.qos);
    // 组件卸载导致请求被取消时 data 是 undefined：此时**不能**把 available 写成 false，
    // 否则卡片会闪一下"本机型不支持查询"（与 NetworkView.refreshSignal 的守卫同一个口径）。
    if (!data) return;
    qos.available = data.available === true;
    qos.qci = data.qci ?? '';
    qos.downlinkDisplay = data.downlink_display ?? '';
    qos.uplinkDisplay = data.uplink_display ?? '';
    qosLoaded.value = true;
    qosFailed.value = false;
  } catch {
    // 仍不弹 toast（AT 不可用是预期状态），但记下来让卡片能区分「没读到」与「不支持」
    qosFailed.value = true;
  } finally {
    qosLoading.value = false;
  }
}

/** 三行取值统一走这里：`available:false` / 缺字段 / 空串都显示 `—`，绝不渲染空白格。 */
function qosText(v: string | number): string {
  if (!qos.available) return '—';
  const s = String(v ?? '').trim();
  return s === '' ? '—' : s;
}

// ── 设备控制的能力门禁 ──
//
// 三项各对应一个 `Capability`（core/contract 的 Capabilities.kt 写明：性能模式 →
// `/device/performance`、Samba → `/device/samba`、FOTA → `/device/fota`）。
// LED 指示灯**没有**对应能力（那 11 项里没有 LED），所以不做门禁 —— 不为了"整齐"
// 编一个不存在的 wire 出来。
//
// 判据一律 `unsupported()`：只有"设备确实没声明"才置灰；"还没加载完 / 请求失败"
// （`supports()` 回 true 而 `unsupported()` 回 false）时全部照常可用。
const performanceUnsupported = computed(() => caps.unsupported(Capability.PERFORMANCE_MODE));
const performanceHint = computed(() => caps.hintOf(Capability.PERFORMANCE_MODE));
const sambaUnsupported = computed(() => caps.unsupported(Capability.SAMBA));
const sambaHint = computed(() => caps.hintOf(Capability.SAMBA));
const fotaUnsupported = computed(() => caps.unsupported(Capability.FOTA));
const fotaHint = computed(() => caps.hintOf(Capability.FOTA));

// ── 设备控制（六项平铺；逻辑与注释来自原 ControlTab，经 DeviceControlModal 中转，一字未改） ──
const controlLoading = ref(false);
/** 最近一次读设备设置是否失败 —— 失败时六个开关会一起停在「关」，那是一个看着像真值的假状态。 */
const controlFailed = ref(false);

const controlSettings = reactive({
  ledEnabled: false,
  performanceEnabled: false,
  sambaEnabled: false,
  usbPortEnabled: false,
  restartScheduleEnabled: false,
  restartScheduleTime: '04:00',
  fotaAutoUpdate: false,
});

const toggleLoading = reactive<Record<string, boolean>>({
  led: false,
  performance: false,
  samba: false,
  usbPort: false,
  restartSchedule: false,
  fota: false,
});

/**
 * 设备 goform 写入成功到「查询能读到新值」之间存在延迟：
 * 零延迟回读会拿到旧值、覆盖掉刚做的乐观置位，UI 弹回原样，
 * 用户以为没生效就反复点击。600ms 对齐 Android app（NetworkModule.setWifiSleep 等
 * 都是写入后 delay(500) 再 load），多留一点余量。只用于「写入后回读」，纯查询不加。
 */
const GOFORM_SETTLE_MS = 600;
const settleAfterWrite = () => new Promise((resolve) => setTimeout(resolve, GOFORM_SETTLE_MS));

async function loadControlSettings(silent = false) {
  if (!silent) controlLoading.value = true;
  try {
    // core GET /api/device/settings 是**归一化后的契约字段**（allowlist 默认拒绝，未登记的
    // 设备字段不会出现），键名见 contract.ts 的 DeviceFields.deviceSettings：
    // indicator_light_switch / performance_mode / samba_switch / usb_port_switch /
    // restart_schedule_switch / restart_time / UpgMode。值全是字符串 "1"/"0"（不能用 !! 判断）。
    const { data } = await api.get('/api/device/settings');
    const on = (k: string) => String(data?.[k] ?? '') === '1';
    controlSettings.ledEnabled = on('indicator_light_switch');
    controlSettings.performanceEnabled = on('performance_mode');
    controlSettings.sambaEnabled = on('samba_switch');
    controlSettings.usbPortEnabled = on('usb_port_switch');
    controlSettings.restartScheduleEnabled = on('restart_schedule_switch');
    // FOTA 自动检查更新：设备侧 UpgMode，"1" = 开（与写侧 auto_update 同向）
    controlSettings.fotaAutoUpdate = on('UpgMode');
    controlSettings.restartScheduleTime = String(data?.restart_time || '') || '04:00';
    controlFailed.value = false;
  } catch {
    // 不再全静默：读失败时开关值全是默认的「关」，卡片要给一句可信度说明
    controlFailed.value = true;
  } finally {
    if (!silent) controlLoading.value = false;
  }
}

// ToggleRow 是完全受控组件（:value="modelValue"），父组件不回写状态开关就不会动，
// 所以这里成功后先本地置位、再静默回读设备真实状态；
// 回读必须等 GOFORM_SETTLE_MS，否则会读回旧值把乐观置位冲掉（见 settleAfterWrite 注释）
async function postToggle(
  url: string,
  enabled: boolean,
  loadingKey: string,
  stateKey: 'ledEnabled' | 'performanceEnabled' | 'sambaEnabled' | 'usbPortEnabled'
) {
  toggleLoading[loadingKey] = true;
  let posted = false;
  try {
    await api.post(url, { enabled });
    posted = true;
    // 只有成功才乐观置位：失败时保持原值，随后的回读会给出设备真实状态
    controlSettings[stateKey] = enabled;
    message.success('设置已更新');
  } catch {
    message.error('设置失败');
  } finally {
    if (posted) await settleAfterWrite();
    await loadControlSettings(true);
    toggleLoading[loadingKey] = false;
  }
}

async function postRestartSchedule(enabled: boolean) {
  toggleLoading.restartSchedule = true;
  let posted = false;
  try {
    await api.post('/api/device/restart-schedule', {
      enabled,
      time: controlSettings.restartScheduleTime,
    });
    posted = true;
    controlSettings.restartScheduleEnabled = enabled;
    message.success('定时重启设置已更新');
  } catch {
    message.error('设置失败');
  } finally {
    if (posted) await settleAfterWrite();
    await loadControlSettings(true);
    toggleLoading.restartSchedule = false;
  }
}

async function postRestartScheduleTime() {
  if (!controlSettings.restartScheduleEnabled) return;
  if (!/^\d{1,2}:\d{2}$/.test(controlSettings.restartScheduleTime)) {
    message.warning('重启时间格式应为 HH:MM');
    return;
  }
  toggleLoading.restartSchedule = true;
  let posted = false;
  try {
    await api.post('/api/device/restart-schedule', {
      enabled: true,
      time: controlSettings.restartScheduleTime,
    });
    posted = true;
    message.success('重启时间已更新');
  } catch {
    message.error('设置失败');
  } finally {
    if (posted) await settleAfterWrite();
    await loadControlSettings(true);
    toggleLoading.restartSchedule = false;
  }
}

// 设备侧就是 goformId=SetUpgAutoSetting&UpgMode=0|1（0 = 关闭自动检查更新，1 = 开启），
// core 把它同时登记成读写字段：写 POST /api/device/fota { auto_update }，
// 读 GET /api/device/settings 的 UpgMode，所以这里能像其它开关一样回显真实状态。
//
// 规范入参是 auto_update（**正向**：true = 允许自动升级）。旧入参 enabled 是反的
// （true = 禁用），core 仍兼容但会打 warn，这里已改用 auto_update。
async function setFotaAutoUpdate(autoUpdate: boolean) {
  toggleLoading.fota = true;
  let posted = false;
  try {
    await api.post('/api/device/fota', { auto_update: autoUpdate });
    posted = true;
    // 与 postToggle 同一套：成功才乐观置位，失败保持原值等回读纠正
    controlSettings.fotaAutoUpdate = autoUpdate;
    message.success(autoUpdate ? '已开启 FOTA 自动检查更新' : '已关闭 FOTA 自动检查更新');
  } catch {
    message.error('设置失败');
  } finally {
    if (posted) await settleAfterWrite();
    await loadControlSettings(true);
    toggleLoading.fota = false;
  }
}

onMounted(() => {
  loadThermal();
  loadConnections();
  loadControlSettings();
  // QoS 只在这里拉一次（+ 卡片上的手动刷新）：core 侧 5 分钟缓存，它是承载建立时协商出来的
  // 低频参数，不该跟着下面的 10s 轮询每 10s 打一次必然命中缓存的请求。
  loadQos();
  // summary 只在 DefaultLayout 挂载时取一次；直接进入本页或那次请求失败时
  // SIM / 电池卡会没有数据，所以本页自己补拉（与 DashboardView / NetworkView 一致）
  refreshSummary();
});

// 温度与连接数是实时量，之前只在挂载时取一次就再也不更新。
// 温度弹窗打开时跳过页面侧那一轮：弹窗自己在轮询同一个端点，重复请求纯浪费热点流量。
// 设备控制项**不轮询**：它们只在用户操作时变（原 ControlTab 也是只在挂载时读一次）。
// 承载 QoS 同样不在这里（低频 + core 5 分钟缓存，理由见 loadQos 调用处）。
useInterval(() => {
  if (!showThermal.value) loadThermal();
  loadConnections();
}, 10_000);
</script>

<style scoped>
/* 两列（2026-10-14 用户裁决：「改成 2 列布局，当前下方即可还是一行」）：
     行 1 = SIM 卡 ｜ 电池；行 2 = 连接统计 ｜ 温度传感器。
   这四张都是"读一个值"的只读卡，成对放两列才不浪费横向空间；
   行 3 起（承载 QoS / 设备控制 / 应用管理）通栏 —— 用户明确下方保持一行。

   `align-items: start` 而不是 stretch：两列高度天然不齐（SIM 五行、电池四到五行、
   连接统计一行、温度摘要两行），stretch 会把短卡拉到与长卡同高、卡内留一大片空白；
   start 让每张卡按自己的内容收口 —— 上沿对齐、下沿参差，这是用户点名的做法。

   通栏用 :deep() 而不是裸类名：类名落在子组件（GridCard）根节点上。 */
.device-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: var(--space-4);
  align-items: start;
}
.device-grid > :deep(.full-width) {
  grid-column: 1 / -1;
}

/* ── 连接统计 ──
   版式基本走全局 `.stat-grid / .stat-cell / .stat-label / .stat-value`
   （原先本文件自己写了一份同名的 stat-row / stat-item），这里只覆盖差异：
   等分五列、瓦片底色、数字档位。scoped 选择器 (0,2,0) 压过 main.css 的 (0,1,0)，
   与源码顺序无关（与 WifiInfoCard 的 .wifi-stat-grid 同一做法）。 */
.conn-grid {
  grid-template-columns: repeat(5, minmax(0, 1fr));
  gap: var(--space-4);
}
.conn-grid .stat-cell {
  align-items: center;
  padding: var(--space-3) var(--space-2);
  background: var(--page-bg);
  border-radius: var(--radius-sm);
}
.conn-grid .stat-label {
  margin-bottom: var(--space-1);
}
.conn-grid .stat-value {
  font-size: var(--font-xl);
  font-weight: 700;
}

/* 应用管理入口：一行可点，右侧箭头提示"会跳走"（与页面上那些开关区分开） */
.app-entry {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  width: 100%;
  padding: var(--space-2) var(--space-3);
  border: 1px solid var(--border-subtle);
  border-radius: var(--radius-sm);
  background: transparent;
  font: inherit;
  color: inherit;
  cursor: pointer;
  text-align: left;
  transition:
    border-color 0.15s,
    background 0.15s;
}
.app-entry:hover {
  border-color: var(--accent-color);
  background: var(--surface-hover);
}
.app-entry-main {
  display: flex;
  flex-direction: column;
  gap: var(--space-1);
  flex: 1;
  min-width: 0;
}
.app-entry-title {
  font-size: var(--font-md);
  font-weight: 600;
  color: var(--text-primary);
}
.app-entry-desc {
  font-size: var(--font-sm);
  color: var(--text-muted);
}
.app-entry-arrow {
  font-size: var(--font-xl);
  line-height: 1;
  color: var(--text-muted);
  flex-shrink: 0;
}
/* 「可能无电池或检测不到」—— core 的 battery.supported === false 时才出现。
   与只读说明同档（12px / text-muted）：它是可信度说明、不是错误，
   所以刻意不用 error 色，避免把"这台机器本来就没电池"渲染成故障。 */
.battery-hint {
  margin-top: var(--space-1);
  font-size: var(--font-sm);
  color: var(--text-muted);
}

/* ── 承载 QoS ──
   通栏卡里三列排布：三行 InfoRow 若整幅拉满，会变成"标签贴最左、值贴最右"的
   一眼扫不到一起的两端（本页半宽卡的 InfoRow 间距是合适的，通栏不合适）。 */
.qos-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 0 var(--space-6);
}
/* InfoRow 自带的分隔线只给"非末行"，那是为竖排列表设计的；这里是**一行三格**，
   末格没有线会看着像漏画。选择器刻意比子组件里的 `.info-row:last-child` 更具体
   （多一个 :last-child，0-4-0 > 0-3-0），这样不用 !important 也能补齐。 */
.qos-grid :deep(.info-row:last-child) {
  border-bottom: 1px solid var(--border-subtle);
}
/* 「本机型不支持查询」：与只读说明同档（12px / text-muted）。
   刻意不用 error 色 —— AT 通道不可用是机型事实，不是故障（core 也刻意回 200）。 */
.qos-hint {
  font-size: var(--font-sm);
  color: var(--text-muted);
}

/* ── 温度摘要 ── */
.thermal-summary {
  display: flex;
  flex-direction: column;
  gap: var(--space-1);
}
.thermal-max {
  display: flex;
  align-items: baseline;
  gap: var(--space-2);
}
.thermal-max-value {
  font-size: var(--font-2xl);
  font-weight: 700;
  font-variant-numeric: tabular-nums;
}
.thermal-max-label {
  font-size: var(--font-sm);
  color: var(--text-secondary);
}
.thermal-count {
  font-size: var(--font-sm);
  color: var(--text-muted);
}

/* ── 设备控制：六项两列平铺（通栏卡够宽，两列比一列省一半高度） ── */
.ctl-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 0 var(--space-6);
}
/* ToggleRow 的分隔策略是给**单列列表**设计的（`:first-child` 去上内距、`:last-child`
   去下边框），进了双列栅格这两条不变量就失效：首行右格多一段上内距，
   倒数第二项（第 5 项）留着一条没配对的底边框。
   所以这里由栅格自己接管：每行等距，分隔线画成「行首」（从第 3 项起 = 第二行开始），
   双列时每个格子上沿各一条（列间有 24px 间距，线在中间断开，与卡内两列的分栏一致）。
   选择器把 `.toggle-row` 写两遍是为了压过 ToggleRow 里的 `.toggle-row:first-child`
   （同为 0,3,0）—— 两个 SFC 的样式顺序由打包顺序决定，不能靠源码顺序赌。 */
.ctl-grid :deep(.toggle-row.toggle-row) {
  padding: var(--space-3) 0;
  border-bottom: none;
}
.ctl-grid :deep(.toggle-row.toggle-row:nth-child(n + 3)) {
  border-top: 1px solid var(--border-subtle);
}
/* 被能力门禁禁用的开关，把指针事件让给它所在的那一行（tooltip 的 trigger 是整行）：
   浏览器对 disabled 的表单控件不派发鼠标事件，悬停开关那一小块时提示出不来 ——
   而用户看到灰开关的第一反应正是去悬停/点它。悬停标签本来就能出提示，这里补的是开关本身。 */
.ctl-grid :deep(.n-switch--disabled) {
  pointer-events: none;
}
.restart-time {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: var(--space-3);
  margin-top: var(--space-2);
  padding-top: var(--space-3);
  border-top: 1px solid var(--border-subtle);
}
.restart-time label {
  font-size: var(--font-base);
  color: var(--text-secondary);
}
/* 定时重启时间框：原先写在模板上的 inline `width: 120px` */
.restart-input {
  width: 120px;
}
.restart-hint {
  font-size: var(--font-sm);
  color: var(--text-muted);
}

/* ── 1024 档（768–1024 仍是两列，但卡内可用宽约 250px）──
   五格数字按 5 等分时每格只剩 ~45px，「TCP6 / 1024」会被压成两行；
   改 3 列（3 + 2）后每格约 70px，数字与标签都放得下。 */
@media (max-width: 1024px) {
  .conn-grid {
    grid-template-columns: repeat(3, minmax(0, 1fr));
    gap: var(--space-3);
  }
}

/* 窄屏（手机）塌成单列。
   断点取 768px 而不是另起一个 720px：main.css 的「断点约定」写明全站只用
   768 / 1024 两档，新引入第三个数值会让"什么时候塌"在各页之间对不上；
   768 本来就是"移动形态"那一档，语义一致。 */
@media (max-width: 768px) {
  .device-grid {
    grid-template-columns: 1fr;
  }
  .conn-grid {
    grid-template-columns: 1fr;
  }
  .ctl-grid {
    grid-template-columns: 1fr;
  }
  /* 单列时「行首」按相邻兄弟算：第 2 项也要有分隔线（第 3 项起已被上面的 nth-child 规则覆盖） */
  .ctl-grid :deep(.toggle-row + .toggle-row) {
    border-top: 1px solid var(--border-subtle);
  }
  /* 三列在 298px 卡内每格不足 100px，「下行 AMBR / 500 Mbps」会互相挤压，折单列 */
  .qos-grid {
    grid-template-columns: 1fr;
  }
}
</style>
