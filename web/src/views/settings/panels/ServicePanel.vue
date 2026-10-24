<template>
  <div class="settings-panel">
    <!-- Card 4: 服务控制（2026-08-26） -->
    <GridCard title="服务控制">
      <template #extra>
        <div class="log-header-actions">
          <n-tag :type="serviceTagType" size="small" :bordered="false">{{ serviceTagLabel }}</n-tag>
          <n-button size="tiny" quaternary @click="loadServiceStatus">刷新</n-button>
        </div>
      </template>
      <div class="config-grid">
        <div class="config-item switch-item">
          <span class="config-label">后台服务</span>
          <n-switch
            :value="service.enabled"
            :disabled="!service.loaded || serviceBusy"
            :loading="serviceBusy"
            @update:value="toggleBackgroundService"
          />
        </div>
        <div class="config-item switch-item">
          <span class="config-label">开机自启</span>
          <n-switch :value="service.autoStartOnBoot" :disabled="!service.loaded" @update:value="toggleAutoStart" />
        </div>
      </div>
      <InfoRow label="采集循环" :value="service.loaded ? (service.collecting ? '运行中' : '已停止') : '--'" />
      <InfoRow label="运行时长" :value="serviceUptimeText" />
      <div class="text-muted config-hint">
        停止后台服务会停掉设备侧全部自主活动：数据采集、告警检测、定时任务、短信转发、下载与内网穿透看护。HTTP
        接口保持可用（否则就没法再开回来），其它页面会显示"服务已停止"，重新开启后自动恢复；「重启服务」会销毁并重新拉起后端服务组件（进程通常不退出，别按"进程级重启"理解，core
        侧 `BackendService.kt:145-146` 有口径澄清），接口会中断约 10 秒，重启完成后本面板会自动等到就绪。
      </div>
      <!-- 重启看护的实时状态（2026-10-14）：原来只有一个写死的 13s 定时器，超时后停在"未知"且不会自恢复；
           现在由 useServiceRestart 轮询 /health 直到就绪，这里显示"已等 Ns"或失败原因。
           颜色走 n-text 的语义色，不写死色值（check-ui-baseline 会卡）。 -->
      <n-text v-if="serviceRestartText" :type="serviceRestartType" class="restart-status">
        {{ serviceRestartText }}
      </n-text>
      <div class="card-actions">
        <n-button
          type="warning"
          size="small"
          :loading="serviceRestarting"
          :disabled="serviceRestarting"
          @click="restartBackendService"
          >重启服务</n-button
        >
        <!-- 重试：服务端拒绝过 → 重新发一次请求；只是等超时 → 只重新看护，不再打一次重启
             （理由见 useServiceRestart.retry 的注释：超时多半是设备慢，服务其实已经在起来了）。 -->
        <n-button v-if="serviceRestartFailed" size="small" quaternary @click="retryServiceRestart">重试</n-button>
      </div>
    </GridCard>

    <!-- Card: DLNA MediaServer（2026-10-09） -->
    <GridCard title="DLNA 投屏">
      <template #extra>
        <n-tag :type="dlna.running ? 'success' : 'default'" size="small" :bordered="false">{{
          dlna.loaded ? (dlna.running ? '运行中' : dlna.enabled ? '已开启' : '关闭') : '--'
        }}</n-tag>
      </template>
      <div class="config-grid">
        <div class="config-item switch-item">
          <span class="config-label">共享到局域网</span>
          <n-switch
            :value="dlna.enabled && dlna.ready"
            :disabled="!dlna.loaded || dlnaBusy || !dlna.ready"
            :loading="dlnaBusy"
            @update:value="toggleDlna"
          />
        </div>
      </div>
      <InfoRow label="共享目录" :value="dlnaDirsText" />
      <div v-if="dlna.loaded && !dlna.ready" class="text-muted config-hint">
        请先添加至少一个共享目录，否则开关不可用。
      </div>
      <div class="card-actions">
        <n-button size="small" @click="openDlnaDirs">选择共享目录</n-button>
      </div>
      <!-- 尺寸走 `.modal-fit`（2026-10-14）。改前这里只写了 `style="max-width: 520px"`：
           行内 max-width 压过了 main.css 里 `.n-modal` 的全局兜底，而 card preset 又没有默认宽度
           （`.n-card{width:100%}`），于是这个弹窗在手机上直接铺满整屏、左右零间距；
           目录一多还会比屏幕高。现在宽高都由那个类收口。 -->
      <n-modal
        v-model:show="dlnaDirsModal"
        preset="card"
        title="选择 DLNA 共享目录"
        class="modal-fit"
        style="--modal-w: 520px"
      >
        <div class="dlna-current">当前：{{ dlnaBrowsePath }}</div>
        <n-breadcrumb class="dlna-crumbs">
          <n-breadcrumb-item v-for="c in dlnaCrumbs" :key="c.path" @click="browseDlna(c.path)">
            {{ c.name }}
          </n-breadcrumb-item>
        </n-breadcrumb>
        <n-spin :show="dlnaBrowsing">
          <n-list v-if="dlnaChildren.length" hoverable clickable size="small">
            <n-list-item v-for="d in dlnaChildren" :key="d.path" @click="browseDlna(d.path)">
              <div class="dlna-row">
                <!-- 目录名可能很长（/storage/emulated/0/Android/data/…）：必须 min-width:0 + 省略号，
                     否则它会把右侧的「选择」按钮整颗挤出 360px 的弹窗。完整路径放 title。 -->
                <span class="dlna-row-name" :title="d.path">{{ d.name }}</span>
                <n-button
                  size="tiny"
                  :type="dlnaPicked.includes(d.path) ? 'primary' : 'default'"
                  @click.stop="togglePick(d.path)"
                  >{{ dlnaPicked.includes(d.path) ? '已选' : '选择' }}</n-button
                >
              </div>
            </n-list-item>
          </n-list>
          <div v-else class="client-empty">无子目录</div>
        </n-spin>
        <template #footer>
          <div class="dlna-footer">
            <span class="dlna-footer-count">已选 {{ dlnaPicked.length }} 个目录</span>
            <n-space>
              <n-button size="small" @click="dlnaDirsModal = false">取消</n-button>
              <n-button size="small" type="primary" :loading="dlnaBusy" @click="saveDlnaDirs">保存</n-button>
            </n-space>
          </div>
        </template>
      </n-modal>
    </GridCard>

    <!-- Card 5「缓存管理」已移到「性能」面板（PerformancePanel，2026-10-14「各回各家」搬迁）。
         理由：本面板管的是**采集服务的起停**（start/stop/restart/autostart），而「清缓存」是
         缓存话题 —— 它和性能面板里的「缓存 TTL」是一件事的两半（一个定缓存活多久、一个立刻
         清掉），拆在两个面板里讲不通。搬走后本面板只剩服务控制 + DLNA 投屏，域是干净的。
         原先那块的 import / ref / 函数（cacheStats / cachePattern / cacheLoading /
         loadCacheStats / clearCache / invalidateCache / formatCacheSize）已随块一并删除，
         避免 noUnusedLocals 报错与死代码 —— 已确认它们没有被 DLNA 那块复用。 -->
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted } from 'vue';
import { useMessage, useDialog } from 'naive-ui';
import { getApiClient } from '@/composables/useApi';
import GridCard from '@/components/GridCard.vue';
import InfoRow from '@/components/InfoRow.vue';
import { Endpoints } from '@/api/contract';
import { formatUptime } from '@/views/settings/settingsShared';
import { useServiceStore } from '@/stores/service';
import { useServiceRestart } from '@/composables/useServiceRestart';

const message = useMessage();
const dialog = useDialog();
const api = getApiClient();
// 全局服务状态（布局层据此决定是否把正文换成「服务已停止」提示页）。
// 本面板保留自己的局部 reactive（含"重启中"等面板专属态），但每次开关后必须同步全局，
// 否则用户在这里停了服务，其它页面要等 30s 巡检才反应过来。
const serviceStore = useServiceStore();

// ── 服务控制（2026-08-26）──
// enabled = 后台采集开关（持久化在 core AppSettings.backgroundServiceEnabled）；
// collecting = DataScheduler 实际是否在跑；http_running 恒为 true（能收到响应就说明 HTTP 没停）。
const service = reactive({
  loaded: false,
  enabled: false,
  collecting: false,
  uptimeMs: 0,
  autoStartOnBoot: false,
});
const serviceBusy = ref(false);

// ── 服务重启看护（2026-10-14 重写）──
// 2026-10-14 之前：`serviceRestarting` + 一个写死的 13s `setTimeout` 回读（原 `:139-140` / `:224-229`），
// 状态还是组件实例级的、`onUnmounted` 顺手清掉定时器（原 `:365-367`）。两个毛病：
// ① 13s 是**猜的**，设备慢一点就回读到"未知"且**再也不会自己恢复**；
// ② 切走再回来只剩 `service.loaded = false` 的"未知"，没有任何"上次重启到底成没成"的记录。
// 现在换成 useServiceRestart（轮询 /health + 明确超时上限 + 复核服务运行时长），状态放模块作用域。
const {
  restarting: serviceRestarting,
  failed: serviceRestartFailed,
  statusText: serviceRestartText,
  request: requestServiceRestart,
  retry: retryServiceRestart,
} = useServiceRestart({
  onRequested: () => {
    message.warning('已请求重启，正在等待服务就绪…');
  },
  onReady: () => {
    // 确认就绪 → 立刻回读真实状态，并同步全局 store（否则布局层要等 30s 巡检才反应过来）
    void loadServiceStatus();
    void serviceStore.refresh();
    message.success('后端服务已重启就绪');
  },
  onFailed: (reason) => {
    message.error(reason);
    // 失败也回读一次：接口可能已经能答了（那就显示真实状态），也可能仍不可达
    void loadServiceStatus();
  },
});

const serviceTagType = computed<'default' | 'success' | 'warning' | 'error'>(() => {
  // 重启中/未确认优先：重启时会把 loaded 置 false，若先判 loaded 会显示成"未知"
  if (serviceRestartFailed.value) return 'error';
  if (serviceRestarting.value) return 'warning';
  if (!service.loaded) return 'default';
  if (!service.enabled) return 'error';
  return service.collecting ? 'success' : 'warning';
});
const serviceTagLabel = computed(() => {
  if (serviceRestarting.value) return '重启中';
  if (serviceRestartFailed.value) return '重启未确认';
  if (!service.loaded) return '未知';
  if (!service.enabled) return '已停止';
  // 开关为开但采集循环没跑 = 异常，显式暴露而不是报"运行中"
  return service.collecting ? '运行中' : '未采集';
});
/** 重启状态行的语义色（用 n-text 的 type，不写死色值） */
const serviceRestartType = computed<'warning' | 'success' | 'error'>(() => {
  if (serviceRestartFailed.value) return 'error';
  if (serviceRestarting.value) return 'warning';
  return 'success';
});
// 共享版 settingsShared.formatUptime 与拆分前的实现一致，收的是**毫秒**（内部自己 /1000），
// 所以这里直接传 uptime_ms，不要再换算。
const serviceUptimeText = computed(() => (service.loaded ? formatUptime(service.uptimeMs) : '--'));

// ── 缓存管理 ──
// 2026-10-14 整体移到 PerformancePanel：cacheStats / cachePattern / cacheLoading / loadCacheStats /
// clearCache / invalidateCache / formatCacheSize 与「按模式清除」那段 UI 全部搬走，不留死代码
// （已逐个确认没有被 DLNA 那块复用）。理由见模板里那条注释：它和性能面板的「缓存 TTL」是一件事的两半。
// 顺带记一笔：本面板的 `api` 仍是 getApiClient()（服务控制与 DLNA 沿用旧写法，不在本次搬迁范围内），
// 性能面板那边新写的取数走 useCancellableApi()。

// ── 服务控制 ──
// 路径统一走 contract 常量（Endpoints.service.*），改错会有类型错误，不再靠手抄字符串。
async function loadServiceStatus() {
  try {
    const { data } = await api.get(Endpoints.service.status);
    service.enabled = !!data.enabled;
    service.collecting = !!data.collecting;
    service.uptimeMs = Number(data.uptime_ms ?? 0);
    service.autoStartOnBoot = !!data.auto_start_on_boot;
    service.loaded = true;
  } catch {
    service.loaded = false;
  }
}

async function toggleBackgroundService(value: boolean) {
  serviceBusy.value = true;
  try {
    await api.post(value ? Endpoints.service.start : Endpoints.service.stop);
    message.success(value ? '后台采集已启动' : '后台采集已停止（HTTP 接口仍可用）');
  } catch (e: any) {
    message.error(e?.response?.data?.error || '操作失败');
  } finally {
    // 无论成功失败都回读真实状态，避免开关与后端不一致
    await loadServiceStatus();
    await serviceStore.refresh();
    serviceBusy.value = false;
  }
}

async function toggleAutoStart(value: boolean) {
  try {
    await api.post(Endpoints.service.autostart, { enabled: value });
    service.autoStartOnBoot = value;
    message.success(value ? '已开启开机自启' : '已关闭开机自启');
  } catch (e: any) {
    message.error(e?.response?.data?.error || '设置失败');
    loadServiceStatus();
  }
}

// ── 重启服务（2026-10-14 重写，理由见上方 useServiceRestart 那段注释）──
// 2026-10-14 之前是：`serviceRestarting = true` → POST → `message.warning('约 10 秒后自动恢复')`
// → `service.loaded = false` → `setTimeout(回读, 13000)`。13s 是**猜的**，猜错就永远停在"未知"。
// 现在 POST 与等待都交给 useServiceRestart.request()：读基线 → POST → 轮询 /health 直到就绪（上限 90s）。
function restartBackendService() {
  if (serviceRestarting.value) return;
  dialog.warning({
    title: '重启服务',
    content: '将重启后端服务，所有接口（含本页面）会中断约 10 秒，期间正在进行的下载/上传任务会被中断。确定继续？',
    positiveText: '重启',
    negativeText: '取消',
    onPositiveClick: () => {
      // 回读会失败到服务回来为止：先按"读不到"处理，别把重启前的旧值当现状展示
      service.loaded = false;
      // **不要 await**：看护最长 90 秒，await 会让弹窗一直转圈。状态由面板那行"重启中…"承接。
      // "已请求重启"的提示由 onRequested 在 POST 被受理后发出（被拒时只弹错误，不弹这句）。
      void requestServiceRestart();
    },
  });
}

// ── DLNA MediaServer（2026-10-09）──
// ready=false（未配目录）时开关置灰：core 也会拒（400），这里提前拦住体验更好。
const dlna = reactive({ loaded: false, enabled: false, running: false, ready: false, dirs: [] as string[] });
const dlnaBusy = ref(false);
const dlnaDirsText = computed(() => (dlna.dirs.length ? dlna.dirs.join('、') : '未配置（至少需要一个目录）'));

async function loadDlnaStatus() {
  try {
    const { data } = await api.get(Endpoints.dlna.status);
    dlna.enabled = !!data.enabled;
    dlna.running = !!data.running;
    dlna.ready = !!data.ready;
    dlna.dirs = Array.isArray(data.dirs) ? data.dirs : [];
    dlna.loaded = true;
  } catch {
    dlna.loaded = false;
  }
}

async function toggleDlna(value: boolean) {
  dlnaBusy.value = true;
  try {
    const { data } = await api.put(Endpoints.dlna.config, { enabled: value });
    dlna.enabled = !!data.enabled;
    dlna.running = !!data.running;
    dlna.ready = !!data.ready;
    dlna.dirs = Array.isArray(data.dirs) ? data.dirs : [];
    message.success(value ? 'DLNA 已开启' : 'DLNA 已关闭');
  } catch (e: unknown) {
    const msg = (e as { response?: { data?: { error?: string } } })?.response?.data?.error;
    message.error(msg || '保存失败');
  } finally {
    dlnaBusy.value = false;
  }
}

// 目录选择弹层：走 /api/files/list 浏览（与文件页同源），多选后一次 PUT。
// 根用 /storage：内部存储与 SD 卡（/storage/XXXX-XXXX）平级可见，起点写死 emulated/0 会永远看不到外接卷
const PRIMARY_ROOT = '/storage';
const dlnaDirsModal = ref(false);
const dlnaBrowsePath = ref(PRIMARY_ROOT);
const dlnaChildren = ref<{ path: string; name: string }[]>([]);
const dlnaBrowsing = ref(false);
const dlnaPicked = ref<string[]>([]);

const dlnaCrumbs = computed(() => {
  const crumbs = [{ path: PRIMARY_ROOT, name: '存储' }];
  const rel = dlnaBrowsePath.value.startsWith(PRIMARY_ROOT + '/')
    ? dlnaBrowsePath.value.slice(PRIMARY_ROOT.length)
    : '';
  let acc = PRIMARY_ROOT;
  for (const seg of rel.split('/').filter(Boolean)) {
    acc += '/' + seg;
    crumbs.push({ path: acc, name: seg });
  }
  return crumbs;
});

async function browseDlna(path: string) {
  dlnaBrowsing.value = true;
  try {
    const { data } = await api.get('/api/files/list', { params: { path } });
    dlnaBrowsePath.value = path;
    const children = (data.files || [])
      .filter((f: { is_directory?: boolean }) => f.is_directory !== false)
      .map((f: { path: string }) => ({
        path: f.path,
        name: f.path.split('/').filter(Boolean).pop() || f.path,
      }));
    // 列 /storage 时并上 disk-usage 的真实卷：Android 11+ 上 listFiles('/storage')
    // 可能只见 emulated/self，外接 SD 卡（/storage/XXXX-XXXX）会"消失"。
    if (path.replace(/\/+$/, '') === '/storage') {
      try {
        const du = await api.get('/api/files/disk-usage');
        for (const d of du.data?.disks || []) {
          if (d.mount && !children.some((c: { path: string }) => c.path === d.mount)) {
            children.push({ path: d.mount, name: d.label || d.mount });
          }
        }
      } catch {
        /* disk-usage 失败不阻塞浏览 */
      }
    }
    dlnaChildren.value = children;
  } catch {
    message.error('加载目录失败');
  } finally {
    dlnaBrowsing.value = false;
  }
}

function togglePick(path: string) {
  const i = dlnaPicked.value.indexOf(path);
  if (i >= 0) dlnaPicked.value.splice(i, 1);
  else dlnaPicked.value.push(path);
}

function openDlnaDirs() {
  dlnaPicked.value = [...dlna.dirs];
  dlnaDirsModal.value = true;
  browseDlna(PRIMARY_ROOT);
}

async function saveDlnaDirs() {
  dlnaBusy.value = true;
  try {
    const { data } = await api.put(Endpoints.dlna.config, { dirs: dlnaPicked.value });
    dlna.enabled = !!data.enabled;
    dlna.running = !!data.running;
    dlna.ready = !!data.ready;
    dlna.dirs = Array.isArray(data.dirs) ? data.dirs : [];
    dlnaDirsModal.value = false;
    message.success('共享目录已保存');
  } catch (e: unknown) {
    const msg = (e as { response?: { data?: { error?: string } } })?.response?.data?.error;
    message.error(msg || '保存失败');
  } finally {
    dlnaBusy.value = false;
  }
}

// ══════════════════════════════════════════════
//  Lifecycle
// ══════════════════════════════════════════════

onMounted(() => {
  loadServiceStatus();
  // loadCacheStats() 已随「缓存管理」卡移到 PerformancePanel（2026-10-14）
  loadDlnaStatus();
});

// 2026-10-14：原来这里清的是「重启回读」的 setTimeout 句柄。现在定时器在看护内部
// （模块作用域的 useServiceRestart），且**必须**活得比本组件久——否则切页回来就看不到结果了，
// 所以卸载时不再清任何东西：回调解绑由 useServiceRestart 的 onScopeDispose 负责。
</script>

<style scoped>
/* .settings-panel 栅格与断点、.config-grid/.config-item/.config-label/.switch-item 版式、
   .section-subtitle、.card-actions 均已统一到 src/styles/main.css（全局各一份） */

/* .inline-group（模式输入框 + 按钮并排）已随「缓存管理」卡移到 PerformancePanel（2026-10-14）——
   它在搬走前就只被那一块用，留着是死代码。 */

/* ── 与「调试日志」卡共用的头部操作区（LogsPanel 里各留一份）──
   ⚠ 本轮**保留这份本地副本**：`.log-header-actions` 在 LogsPanel 里还有一份，
   与 `.text-muted` 同属「设置域字段类/排版类上收」的范畴。收公共件由 C2 负责
   （docs-dev/web-ui-remediation-plan.md §8.2），本轮不抽 —— 抽了会同时改到 B8 的文件。 */
.log-header-actions {
  display: flex;
  gap: 6px;
  align-items: center;
}

/* ── 重启看护的状态行（2026-10-14）：只管版式，颜色走 n-text 的 type，不写死色值 ── */
.restart-status {
  display: block;
  margin-top: 8px;
  font-size: 12px;
}

/* ── DLNA 共享目录弹窗（2026-10-21：原来这里全是行内样式）──
   尺寸仍由 `.modal-fit` 收口（`--modal-w: 520px`），下面只放版式。 */
.dlna-current {
  margin-bottom: 8px;
  font-size: 12px;
  color: var(--text-muted);
  word-break: break-all;
}
.dlna-crumbs {
  margin-bottom: 8px;
}
.dlna-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 8px;
}
/* 目录名过长时省略而不是把「选择」按钮挤出弹窗；完整名字走 title 悬停 */
.dlna-row-name {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.dlna-footer {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 8px;
}
.dlna-footer-count {
  font-size: 12px;
  color: var(--text-muted);
}

/* ── Text helpers ──
   ⚠ `.text-muted` 在多个设置面板与弹窗里各有一份，`.config-hint`（说明句的小字号 +
   上外边距）同理。本轮只把**行内样式**收进类，不收公共件 —— 上收由 C2 负责
   （docs-dev/web-ui-remediation-plan.md §8.2）。 */
.text-muted {
  color: var(--text-muted);
  font-size: 13px;
}
.config-hint {
  margin-top: 8px;
  font-size: 12px;
  line-height: 1.5;
}
</style>
