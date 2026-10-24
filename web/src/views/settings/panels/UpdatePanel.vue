<template>
  <div class="settings-panel">
    <!--
      服务更新（2026-10-23 重新划界；2026-10-24 下掉手机 App）。

      这一页只负责**服务**：
        · Web 前端控制面板（ResourceCard + WEB_RESOURCE）
        · core 后端（CoreUpdateCard）
      插件组件（frpc / cloudflared）在「资源中心」/resources，不在这里出现。
      手机 App 安装包不在 Web 端提供更新入口（由 App 自身负责）。
    -->
    <GridCard class="full-width" title="服务更新源">
      <div class="config-grid">
        <div class="config-item">
          <span class="config-label">下载方式</span>
          <n-select
            :value="updateMode"
            :options="UPDATE_MODE_OPTIONS"
            :loading="savingUpdateMode"
            :disabled="!updateSourceLoaded || savingUpdateMode"
            size="small"
            @update:value="onUpdateModeChange"
          />
        </div>
        <div class="config-item">
          <span class="config-label">设备出口地区</span>
          <div class="geo-row">
            <span class="geo-text">{{ geoText }}</span>
            <n-button size="tiny" :loading="detectingGeo" :disabled="!updateSourceLoaded" @click="detectGeo(true)">
              重新检测
            </n-button>
          </div>
        </div>
      </div>
      <div class="update-hint">
        「自动」模式由设备根据出口 IP 所属地区选择下载源：中国大陆使用镜像加速，其他地区（含未测出地区）使用直连。
        镜像节点与失败切换均由设备端处理，选择后立即生效，无需另行保存。 下载方式仅影响本页的服务更新包。
      </div>
      <div v-if="!updateSourceLoaded" class="update-hint">
        配置尚未从设备读取，或当前 core 版本不支持该项设置，下载方式暂不可修改。
      </div>
    </GridCard>

    <!-- Web 前端服务包：走 /api/web/*；服务更新页与服务更新源同屏，不再绕到资源中心。 -->
    <ResourceCard class="full-width" :descriptor="WEB_RESOURCE" />

    <!-- core 后端：状态机 / 轮询 / 重启复核都在 stores/update.ts。 -->
    <CoreUpdateCard class="full-width" />
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue';
import { useMessage } from 'naive-ui';
import { useCancellableApi } from '@/composables/useCancellableApi';
import GridCard from '@/components/GridCard.vue';
import {
  Endpoints,
  UpdateSourceMode,
  describeConfigReject,
  type ConfigRejectedField,
  type UpdateSourceModeValue,
} from '@/api/contract';
import CoreUpdateCard from '@/views/settings/components/CoreUpdateCard.vue';
import ResourceCard from '@/views/resources/components/ResourceCard.vue';
import { WEB_RESOURCE } from '@/views/resources/resourceRegistry';
import { useResourceTask } from '@/views/resources/useResourceTask';

const message = useMessage();
const api = useCancellableApi();

// ── 更新源（只影响服务更新包：Web 前端 / core / App）─────────────────────────
//
// 唯一真源是 core 的 `update_source_mode`（见 contract 里 UpdateSourceMode 的注释）：
// **读** = `GET /api/config` 的 `update_source_mode`；**写** = `PUT /api/config`
// body `{ update_source_mode: 'auto' | 'mirror' | 'direct' }`，响应按 `updated_fields` 确认。
const UPDATE_SOURCE_KEY = 'update_source_mode';

const UPDATE_MODE_OPTIONS = [
  { label: '自动（按设备出口地区）', value: UpdateSourceMode.AUTO },
  { label: '镜像加速（中国大陆推荐）', value: UpdateSourceMode.MIRROR },
  { label: '直连 GitHub', value: UpdateSourceMode.DIRECT },
];

/** 下拉当前值。`auto` 是 core 的**持久模式**（不是「瞬时动作」），所以可以正常回显。 */
const updateMode = ref<UpdateSourceModeValue>(UpdateSourceMode.AUTO);
/**
 * `GET /api/config` 里有没有 `update_source_mode`。
 *
 * 这个键是 2026-09-22 才下沉到 core 的，设备上的 core 比 web 旧时配置里根本没它 ——
 * 此时下拉必须禁用，否则就是一个拖得动、看着像生效、设备侧什么都没变的假控件。
 */
const updateSourceLoaded = ref(false);
/** 保存中：既当 n-select 的 loading，也用来挡住重复提交（同一次选择只发一个 PUT）。 */
const savingUpdateMode = ref(false);

async function loadUpdateSource() {
  try {
    const { data } = await api.get('/api/config');
    // useCancellableApi：面板卸载时在途请求 resolve 成 data=undefined。这里直接返回，
    // 不要把已经读到的基线抹成「未加载」—— 那会让下拉在返回本页时先灰一下。
    if (!data) return;
    if (data[UPDATE_SOURCE_KEY] !== undefined) {
      updateMode.value = data[UPDATE_SOURCE_KEY] as UpdateSourceModeValue;
      updateSourceLoaded.value = true;
    } else {
      updateSourceLoaded.value = false;
    }
  } catch {
    // 读不到配置时一律禁用：此时表单里只有前端默认值，显示成「自动」是假状态
    updateSourceLoaded.value = false;
  }
}

/**
 * 改下载方式：选完即写。
 *
 * 为什么不等「保存」：本卡的用途是「现在就要按这个源去取包」，而用户的下一步动作是下面
 * Web / core 卡上的「检查更新」。若还攒着提交，他会先拿到一次超时，再回头发现源没生效。
 */
async function onUpdateModeChange(mode: UpdateSourceModeValue) {
  if (savingUpdateMode.value || mode === updateMode.value) return;
  const previous = updateMode.value;
  savingUpdateMode.value = true;
  try {
    const { data } = await api.put('/api/config', { [UPDATE_SOURCE_KEY]: mode });
    // 取消（面板已卸载）：不置位、不报错
    if (!data) return;
    const updated: string[] = data.updated_fields || [];
    if (!updated.includes(UPDATE_SOURCE_KEY)) {
      // 没进 updated_fields 就是没生效（老 core 没有 rejected_fields，只能这样推断），
      // UI 必须回到原值，否则下拉显示的是一个设备并不认的模式。
      updateMode.value = previous;
      const rejected: ConfigRejectedField[] = data.rejected_fields || [];
      const detail = rejected.find((r) => r.field === UPDATE_SOURCE_KEY);
      message.error(detail ? `未生效 —— ${describeConfigReject(detail)}` : '下载方式未生效，设备未接受此项改动');
      return;
    }
    updateMode.value = mode;
    message.success('下载方式已生效');
    // 选了自动但还没测过地区：顺手测一次，让用户立刻看到判定依据。
    // 测不出来也不改模式 —— core 侧「地区未知」按直连走，且会在检查更新时自己重试。
    if (mode === UpdateSourceMode.AUTO && !geoCountry.value) detectGeo(true);
  } catch (e: any) {
    updateMode.value = previous;
    message.error(e?.response?.data?.error || '保存失败，下载方式已回滚');
  } finally {
    savingUpdateMode.value = false;
  }
}

// ── 设备出口地区：core 的 GeoDetector 探出网 IP 归属，core 的 auto 判定用的就是它 ──
const geoCountry = ref('');
const detectingGeo = ref(false);

/**
 * 国家/地区码 → 中文名（2026-10-21）。
 * 用平台自带的 `Intl.DisplayNames` 而不是手抄一张 ISO 3166 表：core 加新地区时不会漏项。
 */
let regionNames: Intl.DisplayNames | null | undefined;
function countryLabel(code: string): string {
  const c = code.trim().toUpperCase();
  if (!c) return '未检测';
  if (c === 'CN') return '中国大陆（CN）';
  try {
    if (regionNames === undefined) regionNames = new Intl.DisplayNames(['zh-CN'], { type: 'region' });
    const name = regionNames?.of(c);
    if (name && name !== c) return `${name}（${c}）`;
  } catch {
    regionNames = null;
  }
  return `其他地区（${c}）`;
}

const geoText = computed(() => countryLabel(geoCountry.value));

/**
 * 读取/重测出口地区。
 * @param force true = 强制重测（POST detect，三源全挂回 502）；false = 只读 core 的缓存快照
 * @returns 国家码；空串 = 没测出来（**不等于海外**）
 */
async function detectGeo(force: boolean): Promise<string> {
  detectingGeo.value = true;
  try {
    const { data } = force ? await api.post(Endpoints.geo.detect) : await api.get(Endpoints.geo.root);
    // 取消（面板已卸载）：返回已读到的值，不要把它冲成空串 —— 那会让下次进页面先闪一下「未检测」
    if (!data) return geoCountry.value;
    geoCountry.value = String(data?.country || '').trim();
    if (force) {
      if (geoCountry.value) message.success(`已检测到出口地区：${geoText.value}`);
      else message.warning('三个地理源都不可达，未能测出地区');
    }
    return geoCountry.value;
  } catch {
    if (force) message.error('地区检测失败，设备可能不可达');
    return '';
  } finally {
    detectingGeo.value = false;
  }
}

// ── Web 前端服务包：复用资源卡 + 任务 runner ──
const resourceTask = useResourceTask();
// 在渲染前把状态建出来（与 ResourcesView 同一手法，避免模板首次渲染时写响应式对象）
resourceTask.stateOf(WEB_RESOURCE.id);

watch(
  () => resourceTask.stateOf(WEB_RESOURCE.id).notice,
  (n) => {
    if (!n) return;
    if (n.type === 'success') message.success(n.text);
    else if (n.type === 'error') message.error(n.text);
    else if (n.type === 'warning') message.warning(n.text);
    else message.info(n.text);
    resourceTask.clearNotice(WEB_RESOURCE.id);
  },
  { immediate: true }
);

// ── 手机 App 安装包：2026-10-24 从 Web 端移除（App 更新由 App 自身负责）──

/**
 * 页面重新可见时回读一次下载方式。
 * `update_source_mode` 的唯一真源在 core，手机端 App 随时可以改它；不回读就会显示旧值。
 * 正在保存时跳过，否则会把用户刚选、还在 PUT 途中的值冲回去。
 */
function onVisible() {
  if (document.visibilityState !== 'visible') return;
  if (savingUpdateMode.value) return;
  loadUpdateSource();
}

onMounted(() => {
  loadUpdateSource();
  detectGeo(false);
  document.addEventListener('visibilitychange', onVisible);
  // Web 前端包的 load 自带 resume：带着进行中的更新刷新整页也能续上轮询。
  void resourceTask.load(WEB_RESOURCE);
});

onUnmounted(() => {
  document.removeEventListener('visibilitychange', onVisible);
});
</script>

<style scoped>
/* .settings-panel 栅格与断点已统一到 src/styles/main.css（全局，8 个面板共用一份） */

/* ── 更新源 ── */
.geo-row {
  display: flex;
  align-items: center;
  gap: 8px;
  min-height: 28px;
}
.geo-text {
  font-size: 13px;
  color: var(--text-primary);
}
.update-hint {
  margin-top: 8px;
  font-size: 12px;
  color: var(--text-muted);
  line-height: 1.5;
}
.update-hint:first-child {
  margin-top: 0;
}
</style>
