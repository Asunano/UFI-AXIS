<template>
  <div class="downloads-view">
    <!-- 顶部状态栏 -->
    <GridCard title="下载管理">
      <div class="top-bar">
        <div class="status-info">
          <span class="status-dot" :class="{ active: aria2Running }"></span>
          <span class="status-text">{{ aria2Running ? 'aria2 运行中' : 'aria2 未启动' }}</span>
          <span v-if="aria2Version" class="status-version">v{{ aria2Version }}</span>
          <span class="status-sep">|</span>
          <span>活跃 {{ activeCount }}</span>
          <span class="status-sep">|</span>
          <span>Tracker {{ trackerCount }}</span>
          <span v-if="throttleState !== 'normal'" class="throttle-warn">
            <span class="status-sep">|</span>
            <span>节流: {{ throttleState }}{{ throttleDetail }}</span>
          </span>
        </div>
        <div class="top-actions">
          <n-button size="small" @click="showConfig = true">配置</n-button>
          <n-button size="small" @click="showTracker = true">Tracker</n-button>
          <n-button size="small" type="primary" @click="showNewTask = true">新建下载</n-button>
        </div>
      </div>
    </GridCard>

    <!-- 任务列表 -->
    <GridCard title="下载任务">
      <template #extra>
        <n-button size="tiny" quaternary :disabled="completedCount === 0" @click="clearCompleted">
          清除已完成 ({{ completedCount }})
        </n-button>
      </template>
      <n-spin :show="loading">
        <div v-if="tasks.length === 0" class="empty-state">暂无下载任务</div>
        <div v-else class="task-list">
          <div v-for="task in tasks" :key="task.id" class="task-item">
            <div class="task-header">
              <div class="task-name" :title="task.file_name || task.url">
                {{ task.file_name || task.url }}
              </div>
              <div class="task-actions">
                <n-button
                  v-if="task.status === 'downloading' || task.status === 'meta' || task.status === 'verifying'"
                  size="tiny"
                  quaternary
                  @click="pauseTask(task.id)"
                  >暂停</n-button
                >
                <n-button
                  v-else-if="task.status === 'paused'"
                  size="tiny"
                  quaternary
                  type="primary"
                  @click="resumeTask(task.id)"
                  >继续</n-button
                >
                <n-button
                  v-else-if="task.status === 'error'"
                  size="tiny"
                  quaternary
                  type="warning"
                  @click="retryTask(task.id)"
                  >重试</n-button
                >
                <n-button size="tiny" quaternary @click="openRename(task)">重命名</n-button>
                <n-button size="tiny" quaternary type="error" @click="deleteTask(task)">删除</n-button>
              </div>
            </div>
            <div class="task-meta">
              <span class="task-status" :class="'status-' + task.status">{{ statusLabel(task.status) }}</span>
              <span v-if="task.total_size > 0" class="task-size">
                {{ formatBytes(task.downloaded_bytes) }} / {{ formatBytes(task.total_size) }}
              </span>
              <span v-if="task.speed > 0" class="task-speed">{{ formatSpeed(task.speed) }}/s</span>
              <span v-if="task.connections" class="task-conn">{{ task.connections }} 连接</span>
              <span v-if="task.seeders > 0" class="task-seeders">{{ task.seeders }} 做种</span>
              <span class="task-protocol">{{ task.protocol }}</span>
            </div>
            <div
              v-if="
                task.status === 'downloading' ||
                task.status === 'meta' ||
                task.status === 'verifying' ||
                task.status === 'paused'
              "
              class="task-progress"
            >
              <n-progress
                v-if="task.progress >= 0"
                :percentage="progressPercent(task)"
                :show-indicator="true"
                :height="6"
              />
              <span v-else class="progress-unknown">进度未知（正在获取元数据）</span>
            </div>
            <div v-if="task.error" class="task-error">{{ task.error }}</div>
          </div>
        </div>
      </n-spin>
    </GridCard>

    <!-- 新建下载弹窗 -->
    <n-modal
      v-model:show="showNewTask"
      preset="dialog"
      title="新建下载"
      positive-text="开始下载"
      negative-text="取消"
      :positive-button-props="{ disabled: pathInvalid }"
      style="width: 480px"
      @positive-click="createTask"
    >
      <n-form label-placement="left" label-width="80">
        <n-form-item label="下载链接" required>
          <n-input v-model:value="newTask.url" placeholder="http/ftp/magnet/torrent 链接" type="textarea" :rows="3" />
        </n-form-item>
        <n-form-item label="文件名">
          <n-input v-model:value="newTask.file_name" placeholder="留空自动识别" />
        </n-form-item>
        <n-form-item label="保存路径">
          <div class="path-field">
            <div class="path-row">
              <n-input v-model:value="newTask.save_path" :placeholder="defaultSavePath || '留空使用默认路径'" />
              <n-button
                size="small"
                :loading="pathChecking"
                :disabled="!newTask.save_path.trim()"
                @click="validateSavePath()"
                >校验</n-button
              >
            </div>
            <div v-if="pathHint" class="path-hint" :class="pathHint.level">{{ pathHint.text }}</div>
          </div>
        </n-form-item>
        <n-form-item label="连接数">
          <n-input-number v-model:value="newTask.connections" :min="1" :max="16" size="small" style="width: 120px" />
        </n-form-item>
        <n-form-item label="限速 (KB/s)">
          <n-input-number
            v-model:value="newTask.speedLimitKB"
            :min="0"
            :max="102400"
            size="small"
            style="width: 120px"
          />
          <span class="form-hint">0 = 不限速</span>
        </n-form-item>
      </n-form>
    </n-modal>

    <!-- 配置弹窗 -->
    <n-modal
      v-model:show="showConfig"
      preset="dialog"
      title="下载配置"
      positive-text="保存"
      negative-text="取消"
      style="width: 520px"
      @positive-click="saveConfig"
    >
      <n-tabs type="segment" animated>
        <n-tab-pane name="basic" tab="基础">
          <div class="config-grid">
            <div class="config-item">
              <span class="config-label">最大并发</span>
              <n-input-number v-model:value="config.max_concurrent" :min="1" :max="10" size="small" />
            </div>
            <div class="config-item">
              <span class="config-label">每服务器连接</span>
              <n-input-number v-model:value="config.max_connections_per_server" :min="1" :max="16" size="small" />
            </div>
            <div class="config-item">
              <span class="config-label">分片数</span>
              <n-input-number v-model:value="config.split_count" :min="1" :max="64" size="small" />
            </div>
            <div class="config-item">
              <span class="config-label">默认保存路径</span>
              <n-input v-model:value="config.save_dir" size="small" />
            </div>
            <div class="config-item">
              <span class="config-label">全局限速 (KB/s)</span>
              <n-input-number v-model:value="config.globalSpeedKB" :min="0" size="small" />
            </div>
            <div class="config-item">
              <span class="config-label">单任务限速 (KB/s)</span>
              <n-input-number v-model:value="config.perTaskSpeedKB" :min="0" size="small" />
            </div>
          </div>
        </n-tab-pane>
        <n-tab-pane name="bt" tab="BT">
          <div class="config-grid">
            <div class="config-item">
              <span class="config-label">做种比率</span>
              <n-input-number v-model:value="config.bt_seed_ratio" :min="0" :max="100" :step="0.1" size="small" />
            </div>
            <div class="config-item">
              <span class="config-label">最大 Peer</span>
              <n-input-number v-model:value="config.bt_max_peers" :min="1" :max="500" size="small" />
            </div>
            <div class="config-item">
              <span class="config-label">DHT</span>
              <n-switch v-model:value="config.bt_enable_dht" />
            </div>
            <div class="config-item">
              <span class="config-label">LPD</span>
              <n-switch v-model:value="config.bt_enable_lpd" />
            </div>
          </div>
        </n-tab-pane>
        <n-tab-pane name="network" tab="网络">
          <div class="config-grid">
            <div class="config-item">
              <span class="config-label">禁用 IPv6</span>
              <n-switch v-model:value="config.disable_ipv6" />
            </div>
            <div class="config-item">
              <span class="config-label">校验证书</span>
              <n-switch v-model:value="config.check_certificate" />
            </div>
            <div class="config-item">
              <span class="config-label">最大重试</span>
              <n-input-number v-model:value="config.max_tries" :min="0" :max="100" size="small" />
            </div>
            <div class="config-item">
              <span class="config-label">重试间隔 (秒)</span>
              <n-input-number v-model:value="config.retry_wait" :min="1" :max="600" size="small" />
            </div>
          </div>
        </n-tab-pane>
        <n-tab-pane name="throttle" tab="智能节流">
          <div class="config-grid">
            <div class="config-item full">
              <span class="config-label">启用智能节流</span>
              <n-switch v-model:value="config.smart_throttle" />
            </div>
            <div class="config-item">
              <span class="config-label">温度警告 (°C)</span>
              <n-input-number v-model:value="config.throttle_temp_warn" :min="30" :max="80" size="small" />
            </div>
            <div class="config-item">
              <span class="config-label">温度临界 (°C)</span>
              <n-input-number v-model:value="config.throttle_temp_critical" :min="40" :max="95" size="small" />
            </div>
            <div class="config-item">
              <span class="config-label">CPU 警告 (%)</span>
              <n-input-number v-model:value="config.throttle_cpu_warn" :min="30" :max="100" size="small" />
            </div>
            <div class="config-item">
              <span class="config-label">CPU 临界 (%)</span>
              <n-input-number v-model:value="config.throttle_cpu_critical" :min="50" :max="100" size="small" />
            </div>
            <div class="config-item">
              <span class="config-label">电量警告 (%)</span>
              <n-input-number v-model:value="config.throttle_battery_warn" :min="5" :max="50" size="small" />
            </div>
            <div class="config-item">
              <span class="config-label">电量临界 (%)</span>
              <n-input-number v-model:value="config.throttle_battery_critical" :min="1" :max="30" size="small" />
            </div>
            <div class="config-item full">
              <span class="config-label">仅充电时下载</span>
              <n-switch v-model:value="config.only_download_when_charging" />
            </div>
          </div>
        </n-tab-pane>
      </n-tabs>
    </n-modal>

    <!-- Tracker 弹窗 -->
    <n-modal
      v-model:show="showTracker"
      preset="dialog"
      title="BT Tracker"
      positive-text="保存"
      negative-text="取消"
      style="width: 520px"
      @positive-click="saveTrackers"
    >
      <div class="tracker-info">
        <span>Tracker 数量: {{ trackerCount }}</span>
        <span v-if="trackerLastUpdated">上次更新: {{ formatTime(trackerLastUpdated) }}</span>
        <span>状态: {{ trackerStatus }}</span>
      </div>
      <div class="tracker-actions">
        <n-button size="small" :loading="trackerRefreshing" @click="refreshTrackers">刷新 Tracker 列表</n-button>
        <n-checkbox v-model:checked="trackerAutoUpdate">自动更新</n-checkbox>
        <n-input-number
          v-if="trackerAutoUpdate"
          v-model:value="trackerIntervalHours"
          :min="1"
          :max="720"
          size="small"
          style="width: 110px"
        />
        <span v-if="trackerAutoUpdate" class="form-hint">小时</span>
      </div>
      <n-input
        v-model:value="trackerText"
        type="textarea"
        :rows="10"
        placeholder="每行一个 tracker URL"
        style="margin-top: 12px"
      />
      <div class="form-hint" style="margin-top: 6px">后端以逗号分隔存储，这里按行编辑，保存时自动转换。</div>
    </n-modal>

    <!-- 重命名弹窗 -->
    <n-modal
      v-model:show="showRename"
      preset="dialog"
      title="重命名任务"
      positive-text="确定"
      negative-text="取消"
      style="width: 420px"
      @positive-click="submitRename"
    >
      <n-input v-model:value="renameName" placeholder="新的文件名" @keydown.enter="submitRename" />
      <div class="form-hint" style="margin-left: 0; margin-top: 6px">不能为空，且不能包含 / 或 \ 。</div>
    </n-modal>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted, onUnmounted, watch, h } from 'vue';
import { useInterval } from '@/composables/useRealtime';
import { useMessage, useDialog, NCheckbox } from 'naive-ui';
import { useCancellableApi } from '@/composables/useCancellableApi';
import { formatBytes, formatSpeed } from '@/composables/utils';
import GridCard from '@/components/GridCard.vue';

const message = useMessage();
const dialog = useDialog();
const api = useCancellableApi();

// ── 状态 ──
const loading = ref(false);
const tasks = ref<any[]>([]);
const aria2Running = ref(false);
const aria2Version = ref<string | null>(null);
const activeCount = ref(0);
const trackerCount = ref(0);
const throttleState = ref('normal');
const throttleTemp = ref<number | null>(null);
const throttleCpu = ref<number | null>(null);
const throttleBattery = ref<number | null>(null);
const throttleCharging = ref<boolean | null>(null);

// ── 新建任务 ──
const showNewTask = ref(false);
const defaultSavePath = ref('');
const newTask = reactive({
  url: '',
  file_name: '',
  save_path: '',
  connections: 16,
  speedLimitKB: 0,
});

// ── 保存路径校验（GET /api/downloads/validate-path）──
interface PathValidation {
  valid: boolean;
  exists: boolean;
  is_directory: boolean | null;
  writable: boolean | null;
  free_space: number;
  absolute_path: string;
}
const pathChecking = ref(false);
const pathResult = ref<PathValidation | null>(null);
let pathCheckTimer: ReturnType<typeof setTimeout> | null = null;

// 一行提示：绿=可写 / 橙=目录不存在（core 下载时会创建）/ 红=不可用
const pathHint = computed<{ level: 'ok' | 'warn' | 'bad'; text: string } | null>(() => {
  const r = pathResult.value;
  if (!r || !newTask.save_path.trim()) return null;
  if (r.valid) {
    return { level: 'ok', text: `可写，剩余 ${formatBytes(r.free_space >= 0 ? r.free_space : 0)}` };
  }
  if (!r.exists) return { level: 'warn', text: '目录不存在，下载时会尝试创建' };
  if (r.is_directory === false) return { level: 'bad', text: '该路径是文件，不是目录' };
  if (r.writable === false) return { level: 'bad', text: '目录不可写' };
  return { level: 'bad', text: '路径不可用' };
});

// 仅红色（路径存在但不可用）时阻止创建；留空走后端默认目录，不阻止
const pathInvalid = computed(() => pathHint.value?.level === 'bad');

async function validateSavePath() {
  const path = newTask.save_path.trim();
  if (!path) {
    pathResult.value = null;
    return;
  }
  pathChecking.value = true;
  try {
    const { data } = await api.get('/api/downloads/validate-path', { params: { path } });
    // 路径为空时 core 返回 400 + valid:false，这里已提前拦住，只处理 200
    pathResult.value = {
      valid: data?.valid === true,
      exists: data?.exists === true,
      is_directory: data?.is_directory ?? null,
      writable: data?.writable ?? null,
      free_space: Number(data?.free_space ?? -1),
      absolute_path: data?.absolute_path || path,
    };
  } catch {
    // 校验失败不阻塞创建流程
    pathResult.value = null;
  } finally {
    pathChecking.value = false;
  }
}

// 输入防抖 600ms 自动校验
watch(
  () => newTask.save_path,
  (v) => {
    if (pathCheckTimer) {
      clearTimeout(pathCheckTimer);
      pathCheckTimer = null;
    }
    if (!v.trim()) {
      pathResult.value = null;
      return;
    }
    pathCheckTimer = setTimeout(() => {
      pathCheckTimer = null;
      validateSavePath();
    }, 600);
  }
);

// ── 重命名 ──
const showRename = ref(false);
const renameName = ref('');
const renameTarget = ref<any | null>(null);

// ── 配置 ──
const showConfig = ref(false);
const config = reactive({
  max_concurrent: 3,
  max_connections_per_server: 16,
  split_count: 16,
  save_dir: '',
  globalSpeedKB: 0,
  perTaskSpeedKB: 0,
  bt_seed_ratio: 1.0,
  bt_max_peers: 100,
  bt_enable_dht: true,
  bt_enable_lpd: false,
  disable_ipv6: false,
  check_certificate: true,
  max_tries: 5,
  retry_wait: 10,
  smart_throttle: false,
  throttle_temp_warn: 45,
  throttle_temp_critical: 55,
  throttle_cpu_warn: 70,
  throttle_cpu_critical: 90,
  throttle_battery_warn: 20,
  throttle_battery_critical: 10,
  only_download_when_charging: false,
});

// ── Tracker ──
const showTracker = ref(false);
const trackerText = ref('');
const trackerLastUpdated = ref(0);
const trackerStatus = ref('unknown');
const trackerRefreshing = ref(false);
const trackerAutoUpdate = ref(false);
const trackerIntervalHours = ref(24);
const deleteWithFile = ref(false);

// ── 计算属性 ──
const completedCount = computed(() => tasks.value.filter((t) => t.status === 'completed').length);

const throttleDetail = computed(() => {
  const parts: string[] = [];
  if (throttleTemp.value != null) parts.push(`${Number(throttleTemp.value).toFixed(1)}°C`);
  if (throttleCpu.value != null) parts.push(`CPU ${throttleCpu.value}%`);
  if (throttleBattery.value != null) parts.push(`电量 ${throttleBattery.value}%`);
  if (throttleCharging.value === false) parts.push('未充电');
  return parts.length ? `（${parts.join(' / ')}）` : '';
});

// core DownloadTask.progress 是 0..1 的比例，-1 表示元数据阶段进度未知
function progressPercent(task: any): number {
  const p = Number(task?.progress ?? 0);
  if (isNaN(p) || p < 0) return 0;
  return Math.round(p * 100);
}

// ── 数据加载 ──
async function loadTasks() {
  try {
    const { data } = await api.get('/api/downloads');
    tasks.value = data.tasks || [];
    activeCount.value = data.active || 0;
    aria2Running.value = data.aria2_running || false;
    aria2Version.value = data.aria2_version || null;
    trackerCount.value = data.tracker_count || 0;
    throttleState.value = data.throttle_state || 'normal';
    throttleTemp.value = data.throttle_temp ?? null;
    throttleCpu.value = data.throttle_cpu ?? null;
    throttleBattery.value = data.throttle_battery ?? null;
    throttleCharging.value = data.throttle_charging ?? null;
    if (data.config) {
      defaultSavePath.value = data.config.save_dir || '';
    }
  } catch {
    /* 静默 */
  }
}

async function loadConfig() {
  try {
    const { data } = await api.get('/api/downloads/config');
    Object.assign(config, {
      max_concurrent: data.max_concurrent ?? 3,
      max_connections_per_server: data.max_connections_per_server ?? 16,
      split_count: data.split_count ?? 16,
      save_dir: data.save_dir ?? '',
      globalSpeedKB: Math.round((data.global_speed_limit || 0) / 1024),
      perTaskSpeedKB: Math.round((data.per_task_speed_limit || 0) / 1024),
      bt_seed_ratio: data.bt_seed_ratio ?? 1.0,
      bt_max_peers: data.bt_max_peers ?? 100,
      bt_enable_dht: data.bt_enable_dht ?? true,
      bt_enable_lpd: data.bt_enable_lpd ?? false,
      disable_ipv6: data.disable_ipv6 ?? false,
      check_certificate: data.check_certificate ?? true,
      max_tries: data.max_tries ?? 5,
      retry_wait: data.retry_wait ?? 10,
      smart_throttle: data.smart_throttle ?? false,
      throttle_temp_warn: data.throttle_temp_warn ?? 45,
      throttle_temp_critical: data.throttle_temp_critical ?? 55,
      throttle_cpu_warn: data.throttle_cpu_warn ?? 70,
      throttle_cpu_critical: data.throttle_cpu_critical ?? 90,
      throttle_battery_warn: data.throttle_battery_warn ?? 20,
      throttle_battery_critical: data.throttle_battery_critical ?? 10,
      only_download_when_charging: data.only_download_when_charging ?? false,
    });
  } catch {
    /* 静默 */
  }
}

async function loadTrackers() {
  try {
    const { data } = await api.get('/api/downloads/trackers');
    // core 以逗号分隔存储（aria2 bt-tracker 格式），这里按行展示便于编辑
    trackerText.value = String(data.trackers || '')
      .split(',')
      .map((s: string) => s.trim())
      .filter(Boolean)
      .join('\n');
    trackerCount.value = data.tracker_count || 0;
    trackerLastUpdated.value = data.last_updated || 0;
    trackerStatus.value = data.status || 'unknown';
    trackerAutoUpdate.value = data.auto_update ?? false;
    trackerIntervalHours.value = data.interval_hours ?? 24;
  } catch {
    /* 静默 */
  }
}

// ── 任务操作 ──
async function createTask() {
  if (!newTask.url.trim()) {
    message.error('请输入下载链接');
    return false;
  }
  try {
    await api.post('/api/downloads', {
      url: newTask.url.trim(),
      file_name: newTask.file_name || undefined,
      save_path: newTask.save_path || undefined,
      connections: newTask.connections,
      speed_limit: newTask.speedLimitKB > 0 ? newTask.speedLimitKB * 1024 : undefined,
    });
    message.success('任务已创建');
    newTask.url = '';
    newTask.file_name = '';
    newTask.save_path = '';
    newTask.connections = 16;
    newTask.speedLimitKB = 0;
    loadTasks();
    return true;
  } catch (e: any) {
    if (e?.response?.data?.duplicate) {
      // core 409 会给出去重后的建议文件名，不用它会导致两个任务写同一个文件
      const suggested = e.response.data.suggested_filename || '';
      const existingName = e.response.data.existing_task?.file_name || '';
      dialog.warning({
        title: '重复任务',
        content: suggested
          ? `已存在相同下载${existingName ? `（${existingName}）` : ''}，强制添加将另存为 "${suggested}"。`
          : '已存在相同下载，是否强制添加？',
        positiveText: '强制添加',
        negativeText: '取消',
        onPositiveClick: async () => {
          try {
            await api.post('/api/downloads?force=true', {
              url: newTask.url.trim(),
              file_name: suggested || newTask.file_name || undefined,
              save_path: newTask.save_path || undefined,
              connections: newTask.connections,
              speed_limit: newTask.speedLimitKB > 0 ? newTask.speedLimitKB * 1024 : undefined,
            });
            message.success('任务已强制添加');
            showNewTask.value = false;
            loadTasks();
          } catch {
            message.error('添加失败');
          }
        },
      });
      return false;
    }
    message.error(e?.response?.data?.error || '创建失败');
    return false;
  }
}

// core 的 pause/resume/delete/clear-completed 均返回 HTTP 200 + { success: bool }
function assertOk(data: any, fallback: string): boolean {
  if (data?.success === false) {
    message.error(data.error || fallback);
    return false;
  }
  return true;
}

async function pauseTask(id: string) {
  try {
    const { data } = await api.post(`/api/downloads/${id}/pause`);
    if (!assertOk(data, '暂停失败')) return;
    message.success('已暂停');
    loadTasks();
  } catch {
    message.error('操作失败');
  }
}

async function resumeTask(id: string) {
  try {
    const { data } = await api.post(`/api/downloads/${id}/resume`);
    if (!assertOk(data, '继续失败')) return;
    message.success('已继续');
    loadTasks();
  } catch {
    message.error('操作失败');
  }
}

async function retryTask(id: string) {
  try {
    const { data } = await api.post(`/api/downloads/${id}/retry`);
    if (!assertOk(data, '重试失败')) return;
    message.success('正在重试');
    loadTasks();
  } catch {
    message.error('操作失败');
  }
}

function openRename(task: any) {
  renameTarget.value = task;
  renameName.value = task.file_name || task.url || '';
  showRename.value = true;
}

// core POST /api/downloads/{id}/rename 请求体 { name }，失败给 400 + OPERATION_FAILED
async function submitRename() {
  const name = renameName.value.trim();
  if (!name) {
    message.error('文件名不能为空');
    return false;
  }
  if (name.includes('/') || name.includes('\\')) {
    message.error('文件名不能包含 / 或 \\');
    return false;
  }
  const target = renameTarget.value;
  if (!target) return false;
  try {
    const { data } = await api.post(`/api/downloads/${target.id}/rename`, { name });
    if (!assertOk(data, '重命名失败')) return false;
    message.success('已重命名');
    showRename.value = false;
    renameTarget.value = null;
    loadTasks();
    return true;
  } catch (e: any) {
    message.error(e?.response?.data?.message || e?.response?.data?.error || '重命名失败');
    return false;
  }
}

function deleteTask(task: any) {
  deleteWithFile.value = false;
  dialog.warning({
    title: '删除任务',
    content: () =>
      h('div', [
        h('div', { style: 'margin-bottom:10px' }, `确定删除 "${task.file_name || task.url}" ？`),
        h(
          NCheckbox,
          {
            checked: deleteWithFile.value,
            'onUpdate:checked': (v: boolean) => {
              deleteWithFile.value = v;
            },
          },
          { default: () => '同时删除已下载的文件' }
        ),
      ]),
    positiveText: '删除',
    negativeText: '取消',
    onPositiveClick: async () => {
      try {
        const { data } = await api.delete(
          `/api/downloads/${task.id}?delete_file=${deleteWithFile.value ? 'true' : 'false'}`
        );
        if (!assertOk(data, '删除失败')) return;
        message.success('已删除');
        loadTasks();
      } catch {
        message.error('删除失败');
      }
    },
  });
}

function clearCompleted() {
  deleteWithFile.value = false;
  dialog.warning({
    title: '清除已完成',
    content: () =>
      h('div', [
        h('div', { style: 'margin-bottom:10px' }, `确定清除 ${completedCount.value} 个已完成的任务？`),
        h(
          NCheckbox,
          {
            checked: deleteWithFile.value,
            'onUpdate:checked': (v: boolean) => {
              deleteWithFile.value = v;
            },
          },
          { default: () => '同时删除已下载的文件' }
        ),
      ]),
    positiveText: '清除',
    negativeText: '取消',
    onPositiveClick: async () => {
      try {
        const { data } = await api.post(
          `/api/downloads/clear-completed?delete_file=${deleteWithFile.value ? 'true' : 'false'}`
        );
        if (!assertOk(data, '操作失败')) return;
        message.success(`已清除 ${data?.cleared ?? 0} 个任务`);
        loadTasks();
      } catch {
        message.error('操作失败');
      }
    },
  });
}

// ── 配置保存 ──
async function saveConfig() {
  try {
    await api.put('/api/downloads/config', {
      max_concurrent: config.max_concurrent,
      max_connections_per_server: config.max_connections_per_server,
      split_count: config.split_count,
      save_dir: config.save_dir,
      global_speed_limit: config.globalSpeedKB * 1024,
      per_task_speed_limit: config.perTaskSpeedKB * 1024,
      bt_seed_ratio: config.bt_seed_ratio,
      bt_max_peers: config.bt_max_peers,
      bt_enable_dht: config.bt_enable_dht,
      bt_enable_lpd: config.bt_enable_lpd,
      disable_ipv6: config.disable_ipv6,
      check_certificate: config.check_certificate,
      max_tries: config.max_tries,
      retry_wait: config.retry_wait,
      smart_throttle: config.smart_throttle,
      throttle_temp_warn: config.throttle_temp_warn,
      throttle_temp_critical: config.throttle_temp_critical,
      throttle_cpu_warn: config.throttle_cpu_warn,
      throttle_cpu_critical: config.throttle_cpu_critical,
      throttle_battery_warn: config.throttle_battery_warn,
      throttle_battery_critical: config.throttle_battery_critical,
      only_download_when_charging: config.only_download_when_charging,
    });
    message.success('配置已保存');
    return true;
  } catch {
    message.error('保存失败');
    return false;
  }
}

// ── Tracker ──
async function refreshTrackers() {
  trackerRefreshing.value = true;
  try {
    const { data } = await api.post('/api/downloads/trackers/refresh');
    if (data?.success === false) {
      trackerStatus.value = data.status || 'error';
      message.error(`刷新失败（${trackerStatus.value}）`);
      return;
    }
    trackerCount.value = data.tracker_count || 0;
    trackerStatus.value = data.status || 'unknown';
    message.success(`Tracker 已刷新，共 ${data.tracker_count} 条`);
    loadTrackers();
  } catch {
    message.error('刷新失败');
  } finally {
    trackerRefreshing.value = false;
  }
}

async function saveTrackers() {
  try {
    // 转回 core 的逗号分隔格式
    const joined = trackerText.value
      .split(/[\n,]/)
      .map((s) => s.trim())
      .filter(Boolean)
      .join(',');
    const { data } = await api.post('/api/downloads/trackers/save', { trackers: joined });
    if (data?.success === false) {
      message.error('保存失败');
      return false;
    }
    trackerCount.value = data.tracker_count || 0;
    // 自动更新开关/间隔存在 config 里，一并落盘
    await api.put('/api/downloads/config', {
      bt_tracker_auto_update: trackerAutoUpdate.value,
      bt_tracker_update_interval_hours: trackerIntervalHours.value,
    });
    message.success(`Tracker 已保存，共 ${data.tracker_count} 条`);
    return true;
  } catch {
    message.error('保存失败');
    return false;
  }
}

// ── 辅助 ──
function statusLabel(status: string): string {
  const map: Record<string, string> = {
    pending: '等待中',
    downloading: '下载中',
    paused: '已暂停',
    completed: '已完成',
    error: '错误',
    meta: '获取元数据',
    verifying: '校验中',
    removed: '已移除',
  };
  return map[status] || status;
}

function formatTime(ts: number): string {
  if (!ts) return '--';
  // core TrackerMeta.lastUpdated 是 System.currentTimeMillis()（毫秒）
  const d = new Date(ts);
  return d.toLocaleString('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' });
}

// ── 自动刷新 ──
onMounted(() => {
  loading.value = true;
  Promise.all([loadTasks(), loadConfig(), loadTrackers()]).finally(() => {
    loading.value = false;
  });
});

// 有任务在跑就 2s 刷一轮（与 app 端一致），全是终态时降到 6s 心跳 —— 列表不能完全冻住，
// meta→verifying→downloading→completed 这些跃迁全靠后端推进，前端必须一直看着。
const hasActiveTask = computed(() =>
  tasks.value.some((t) => ['downloading', 'pending', 'meta', 'verifying'].includes(t.status))
);
useInterval(loadTasks, () => (hasActiveTask.value ? 2000 : 6000));

onUnmounted(() => {
  if (pathCheckTimer) clearTimeout(pathCheckTimer);
});
</script>

<style scoped>
.downloads-view {
  display: flex;
  flex-direction: column;
  gap: 16px;
}
.top-bar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  flex-wrap: wrap;
  gap: 12px;
}
.status-info {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  color: var(--text-secondary);
}
.status-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--text-muted);
}
.status-dot.active {
  background: var(--success);
}
.status-version {
  color: var(--text-muted);
  font-size: 12px;
}
.status-sep {
  color: var(--border-subtle);
}
.throttle-warn {
  color: var(--warning);
}
.top-actions {
  display: flex;
  gap: 8px;
}
.empty-state {
  text-align: center;
  padding: 40px 0;
  color: var(--text-muted);
  font-size: 14px;
}
.task-list {
  display: flex;
  flex-direction: column;
}
.task-item {
  padding: 12px 0;
  border-bottom: 1px solid var(--border-subtle);
}
.task-item:last-child {
  border-bottom: none;
}
.task-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 12px;
}
.task-name {
  font-size: 13px;
  color: var(--text-primary);
  word-break: break-all;
  flex: 1;
  line-height: 1.4;
}
.task-actions {
  display: flex;
  gap: 4px;
  flex-shrink: 0;
}
.task-meta {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 6px;
  font-size: 12px;
  color: var(--text-muted);
}
.task-status {
  font-weight: 500;
}
.status-downloading {
  color: var(--accent-color);
}
.status-paused {
  color: var(--text-secondary);
}
.status-completed {
  color: var(--success);
}
.status-error {
  color: var(--error);
}
.status-meta {
  color: var(--warning);
}
.status-verifying {
  color: var(--accent-color);
}
.status-pending {
  color: var(--text-muted);
}
.task-speed {
  color: var(--accent-color);
}
.task-progress {
  margin-top: 8px;
}
.task-error {
  margin-top: 6px;
  font-size: 12px;
  color: var(--error);
}
/* .config-grid / .config-item / .config-item.full / .config-label 已统一到
   src/styles/main.css（全局一份，与 settings 三个面板共用，含 768px 折单列） */
.form-hint {
  font-size: 12px;
  color: var(--text-muted);
  margin-left: 8px;
}
.path-field {
  display: flex;
  flex-direction: column;
  gap: 4px;
  width: 100%;
}
.path-row {
  display: flex;
  align-items: center;
  gap: 8px;
}
.path-hint {
  font-size: 12px;
  line-height: 1.4;
}
.path-hint.ok {
  color: var(--success);
}
.path-hint.warn {
  color: var(--warning);
}
.path-hint.bad {
  color: var(--error);
}
.tracker-info {
  display: flex;
  gap: 16px;
  font-size: 13px;
  color: var(--text-secondary);
  margin-bottom: 8px;
}
.tracker-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  margin-bottom: 4px;
}
.progress-unknown {
  font-size: 12px;
  color: var(--text-muted);
}
@media (max-width: 768px) {
  .top-bar {
    flex-direction: column;
    align-items: flex-start;
  }
  .tracker-info {
    flex-direction: column;
    gap: 4px;
  }
}
</style>
