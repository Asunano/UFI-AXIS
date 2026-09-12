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
      <div class="text-muted" style="margin-top: 8px; font-size: 12px">
        停止后台服务会停掉设备侧全部自主活动：数据采集、告警检测、定时任务、短信转发、下载与内网穿透看护。HTTP
        接口保持可用（否则就没法再开回来），其它页面会显示"服务已停止"，重新开启后自动恢复；「重启服务」是后端进程级完全重启，接口会中断约
        10 秒。
      </div>
      <div class="card-actions">
        <n-button type="warning" size="small" :loading="serviceRestarting" @click="restartBackendService"
          >重启服务</n-button
        >
      </div>
    </GridCard>

    <!-- Card 5: 缓存管理 -->
    <GridCard title="缓存管理">
      <InfoRow label="条目数" :value="cacheStats.entries" />
      <InfoRow label="缓存大小" :value="cacheStats.size" />
      <div class="card-actions">
        <n-button type="warning" size="small" :loading="cacheLoading" @click="clearCache">清空缓存</n-button>
      </div>
      <n-divider style="margin: 10px 0" />
      <div class="section-subtitle">按模式清除</div>
      <div class="inline-group">
        <n-input v-model:value="cachePattern" placeholder="输入匹配模式" size="small" style="flex: 1" />
        <n-button size="small" :disabled="!cachePattern" :loading="cacheLoading" @click="invalidateCache"
          >清除</n-button
        >
      </div>
    </GridCard>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted, onUnmounted } from 'vue';
import { useMessage, useDialog } from 'naive-ui';
import { getApiClient } from '@/composables/useApi';
import GridCard from '@/components/GridCard.vue';
import InfoRow from '@/components/InfoRow.vue';
import { Endpoints } from '@/api/contract';
import { formatUptime } from '@/views/settings/settingsShared';
import { useServiceStore } from '@/stores/service';

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
const serviceRestarting = ref(false);
let serviceRestartTimer: ReturnType<typeof setTimeout> | null = null;

const serviceTagType = computed<'default' | 'success' | 'warning' | 'error'>(() => {
  // 重启中优先：重启时会把 loaded 置 false，若先判 loaded 会显示成"未知"
  if (serviceRestarting.value) return 'warning';
  if (!service.loaded) return 'default';
  if (!service.enabled) return 'error';
  return service.collecting ? 'success' : 'warning';
});
const serviceTagLabel = computed(() => {
  if (serviceRestarting.value) return '重启中';
  if (!service.loaded) return '未知';
  if (!service.enabled) return '已停止';
  // 开关为开但采集循环没跑 = 异常，显式暴露而不是报"运行中"
  return service.collecting ? '运行中' : '未采集';
});
// 共享版 settingsShared.formatUptime 与拆分前的实现一致，收的是**毫秒**（内部自己 /1000），
// 所以这里直接传 uptime_ms，不要再换算。
const serviceUptimeText = computed(() => (service.loaded ? formatUptime(service.uptimeMs) : '--'));

// ── 缓存 ──
const cacheStats = reactive({ entries: 0, size: '0 B' });
const cachePattern = ref('');
const cacheLoading = ref(false);

// ── ADB / Cache ──
async function loadCacheStats() {
  try {
    const { data } = await api.get('/api/cache/stats');
    // core ResponseCache.getStats(): { count, max_entries, any_cache_count, total_bytes_estimate, stale, entries[] }
    // 旧代码读 data.entries（其实是数组）与 data.size/bytes（不存在），导致条目数错乱、大小恒 0
    cacheStats.entries = Number(data.count ?? 0) + Number(data.any_cache_count ?? 0);
    cacheStats.size = formatCacheSize(Number(data.total_bytes_estimate ?? 0));
  } catch {
    /* silent */
  }
}

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

function restartBackendService() {
  dialog.warning({
    title: '重启服务',
    content: '将重启后端服务，所有接口（含本页面）会中断约 10 秒，期间正在进行的下载/上传任务会被中断。确定继续？',
    positiveText: '重启',
    negativeText: '取消',
    onPositiveClick: async () => {
      serviceRestarting.value = true;
      try {
        await api.post(Endpoints.service.restart);
      } catch {
        // 服务被停掉时连接会断开，请求失败属于预期，不当作错误
      }
      message.warning('已请求重启，约 10 秒后自动恢复');
      service.loaded = false;
      // 给 AlarmManager 的 10s 重启延迟留出余量后回读（句柄存下来，卸载时清理）
      if (serviceRestartTimer) clearTimeout(serviceRestartTimer);
      serviceRestartTimer = setTimeout(async () => {
        serviceRestartTimer = null;
        await loadServiceStatus();
        serviceRestarting.value = false;
      }, 13000);
    },
  });
}

// ── Cache ──
async function clearCache() {
  dialog.warning({
    title: '清空缓存',
    content: '确定清空所有缓存数据？',
    positiveText: '清空',
    negativeText: '取消',
    onPositiveClick: async () => {
      cacheLoading.value = true;
      try {
        await api.post('/api/cache/clear');
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

async function invalidateCache() {
  if (!cachePattern.value) return;
  cacheLoading.value = true;
  try {
    await api.post('/api/cache/invalidate', { pattern: cachePattern.value });
    message.success('缓存已按模式清除');
    loadCacheStats();
  } catch {
    message.error('清除失败');
  } finally {
    cacheLoading.value = false;
  }
}

// ══════════════════════════════════════════════
//  Helpers
// ══════════════════════════════════════════════

function formatCacheSize(bytes: number): string {
  if (!bytes || bytes <= 0) return '0 B';
  const units = ['B', 'KB', 'MB', 'GB'];
  const i = Math.min(units.length - 1, Math.floor(Math.log(bytes) / Math.log(1024)));
  return (bytes / Math.pow(1024, i)).toFixed(i > 0 ? 1 : 0) + ' ' + units[i];
}

// ══════════════════════════════════════════════
//  Lifecycle
// ══════════════════════════════════════════════

onMounted(() => {
  loadServiceStatus();
  loadCacheStats();
});

onUnmounted(() => {
  if (serviceRestartTimer) clearTimeout(serviceRestartTimer);
});
</script>

<style scoped>
/* .settings-panel 栅格与断点、.config-grid/.config-item/.config-label/.switch-item 版式、
   .section-subtitle、.card-actions 均已统一到 src/styles/main.css（全局各一份） */

/* 只在本面板使用：「日志保留天数」输入框与单位文字并排 */
.inline-group {
  display: flex;
  gap: 8px;
  align-items: center;
}

/* ── 与「调试日志」卡共用的头部操作区（LogsPanel 里各留一份）── */
.log-header-actions {
  display: flex;
  gap: 6px;
  align-items: center;
}

/* ── Text helpers ── */
.text-muted {
  color: var(--text-muted);
  font-size: 13px;
}
</style>
